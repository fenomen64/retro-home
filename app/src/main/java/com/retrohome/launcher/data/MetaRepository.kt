package com.retrohome.launcher.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.net.HttpURLConnection
import java.net.URL

/**
 * Загружает обложки и описания в фоне по одной игре и хранит их на устройстве.
 * После загрузки всё работает без интернета.
 */
/** Ответ сервера: код, тело (для 200) и заголовок Retry-After. */
class HttpResponse(
    val code: Int,
    val body: ByteArray? = null,
    val retryAfter: String? = null,
    /** Почему не удалось соединиться (для кода 0): «SocketTimeoutException: …». */
    val error: String? = null,
)

/** Сетевой транспорт; в тестах подменяется заготовленными ответами. */
fun interface Transport {
    /** null тело и код 0 — нет связи. */
    fun open(url: String, accept: String?, userAgent: String): HttpResponse
}

object UrlTransport : Transport {
    override fun open(url: String, accept: String?, userAgent: String): HttpResponse {
        val c = try { URL(url).openConnection() as HttpURLConnection } catch (e: Exception) { return HttpResponse(0, error = describe(e)) }
        return try {
            c.connectTimeout = 15_000
            c.readTimeout = 45_000
            c.setRequestProperty("User-Agent", userAgent)
            if (accept != null) c.setRequestProperty("Accept", accept)
            val code = c.responseCode
            if (code == 200) HttpResponse(200, c.inputStream.use { it.readBytes() })
            else HttpResponse(code, retryAfter = c.getHeaderField("Retry-After"))
        } catch (e: Exception) {
            // Не только IOException: без разрешения на сеть бывает SecurityException
            HttpResponse(0, error = describe(e))
        } finally {
            c.disconnect()
        }
    }

    fun describe(e: Throwable): String = e.javaClass.simpleName + (e.message?.let { ": " + it.take(160) } ?: "")
}

class MetaRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transport: Transport = UrlTransport,
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
    private val isOnline: (() -> Boolean)? = null,
    /** Почта для связи, которую пользователь указал для Википедии (с ней лимит запросов в 20 раз выше). */
    private val contact: () -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val metaFile = File(context.filesDir, "meta.json")
    val coversDir = File(context.filesDir, "covers").apply { mkdirs() }
    private val indexDir = File(context.cacheDir, "thumbidx").apply { mkdirs() }
    private val serializer = MapSerializer(String.serializer(), GameMeta.serializer())

    private val _meta = MutableStateFlow(load())
    val meta: StateFlow<Map<String, GameMeta>> = _meta

    /** Сколько игр обработано из скольких; null — загрузка не идёт. */
    private val _progress = MutableStateFlow<Pair<Int, Int>?>(null)
    val progress: StateFlow<Pair<Int, Int>?> = _progress

    private var job: Job? = null
    private val indexes = HashMap<GameSystem, Thumbnails.Index>()

    fun coverFile(name: String) = File(coversDir, name)

    private fun load(): Map<String, GameMeta> = runCatching {
        if (metaFile.exists()) json.decodeFromString(serializer, metaFile.readText()) else emptyMap()
    }.getOrDefault(emptyMap())

    private fun save() = runCatching {
        val tmp = File(metaFile.parentFile, metaFile.name + ".tmp")
        tmp.writeText(json.encodeToString(serializer, _meta.value))
        tmp.renameTo(metaFile)
    }

    private fun online(): Boolean {
        isOnline?.let { return it() }
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun waiting(m: GameMeta?, retryMissing: Boolean) =
        m != null && !retryMissing && m.retryAt > System.currentTimeMillis()

    /** Нужна обложка или снимок экрана (это не Википедия — грузится быстро и параллельно). */
    private fun needsVisual(m: GameMeta?, retryMissing: Boolean): Boolean {
        if (m == null) return true
        if (waiting(m, retryMissing)) return false
        if (!m.coverTried || !m.snapTried) return true
        return m.cover == null && (retryMissing || m.ver < META_VERSION)
    }

    /** Нужно описание или факты (Википедия и Викиданные — с учётом их лимита запросов). */
    private fun needsText(m: GameMeta?, retryMissing: Boolean): Boolean {
        if (m == null) return true
        if (waiting(m, retryMissing)) return false
        if (!m.descTried || !m.factsTried || !m.platformChecked || m.textVer < TEXT_VERSION) return true
        return (m.descEn == null && m.descRu == null) && (retryMissing || m.ver < META_VERSION)
    }

    /**
     * Догрузить то, чего ещё нет. retryMissing — повторить поиск и для игр,
     * где в прошлый раз ничего не нашлось (кнопка «Обновить обложки и описания»).
     * titleFor — название, исправленное вручную.
     *
     * Сначала для всех игр параллельно грузятся обложки и снимки экрана (сервер libretro не
     * ограничивает частоту), потом по одной — описания из Википедии.
     */
    fun fetch(games: List<Game>, titleFor: (Game) -> String? = { null }, retryMissing: Boolean = false) {
        // Уже идёт загрузка (или повтор, если просят повтор) — не начинаем заново, иначе она никогда не дойдёт до конца
        if (job?.isActive == true && (!retryMissing || jobRetry)) { DiagLog.add("Загрузка уже идёт — новый запуск пропущен"); return }
        job?.cancel()
        jobRetry = retryMissing
        if (retryMissing) { indexFailedAt.clear(); downUntil.clear() }
        job = scope.launch(Dispatchers.IO) {
            if (!online()) { DiagLog.add("Загрузка не начата: Android сообщает, что интернета нет"); return@launch }
            val visual = games.filter { needsVisual(_meta.value[it.id], retryMissing) }
            val text = games.filter { needsText(_meta.value[it.id], retryMissing) }
            val total = visual.size + text.size
            val waitingCount = games.count { waiting(_meta.value[it.id], retryMissing) }
            DiagLog.add("Загрузка${if (retryMissing) " (повтор ненайденного)" else ""}: игр ${games.size}, нужны обложки ${visual.size}, описания ${text.size}, ждут повтора после сбоя $waitingCount")
            if (total == 0) return@launch
            val done = AtomicInteger()
            _progress.value = 0 to total
            try {
                val gate = Semaphore(4)
                coroutineScope {
                    visual.map { g ->
                        async {
                            gate.withPermit {
                                if (isActive) processVisual(g, titleFor(g), force = false)
                                _progress.value = done.incrementAndGet() to total
                            }
                        }
                    }.awaitAll()
                }
                save()
                text.forEachIndexed { i, g ->
                    if (!isActive) return@launch
                    processText(g, titleFor(g), force = false)
                    _progress.value = done.incrementAndGet() to total
                    if (i % 5 == 4) save()
                }
                DiagLog.add("Загрузка завершена")
            } catch (e: kotlinx.coroutines.CancellationException) {
                DiagLog.add("Загрузка прервана")
                throw e
            } catch (e: Throwable) {
                DiagLog.error("Загрузка остановилась", e)
            } finally {
                save()
                _progress.value = null
            }
        }
    }

    /** Заново найти обложку и описание одной игры (после исправления названия). */
    fun refetch(game: Game, title: String?) {
        scope.launch(Dispatchers.IO) {
            if (!online()) return@launch
            _progress.value = 0 to 1
            try {
                processVisual(game, title, force = true)
                processText(game, title, force = true)
            } finally {
                save()
                _progress.value = null
            }
        }
    }

    /** Обложка и снимок экрана одной игры. */
    private fun processVisual(g: Game, title: String?, force: Boolean) {
        val old = _meta.value[g.id] ?: GameMeta()
        var m = old
        var failed = false
        val sw = if (g.system == GameSystem.SWITCH) SwitchLookup(g, title) else null
        if (force || !old.coverTried || old.cover == null) {
            val before = errorsHere()
            lastProblem.set(null)
            val found = runCatching { if (sw != null) fetchSwitchCover(sw) else fetchCover(g, title) }
                .onFailure { DiagLog.error(tag(g) + ": обложка", it) }.getOrNull()
            // Не нашлось из-за сбоя сети — это не «нет обложки», поищем ещё раз позже
            val error = found == null && errorsHere() > before
            when {
                found != null -> DiagLog.add(tag(g) + ": обложка ✓ «${found.third}»")
                error -> DiagLog.add(tag(g) + ": обложка — сбой сети (${lastProblem.get() ?: "?"})")
            }
            failed = failed || error
            m = m.copy(
                cover = found?.first ?: old.cover,
                name = found?.second ?: (if (force) null else old.name),
                thumbName = found?.third ?: old.thumbName,
                coverTried = old.coverTried && !force || !error,
            )
        }
        if (force || !old.snapTried || (old.snap == null && m.thumbName != old.thumbName)) {
            val before = errorsHere()
            val snap = runCatching { if (sw != null) fetchSwitchSnap(sw) else fetchSnap(g, m.thumbName ?: findThumbName(g, title)) }
                .onFailure { DiagLog.error(tag(g) + ": снимок экрана", it) }.getOrNull()
            val error = snap == null && errorsHere() > before
            failed = failed || error
            m = m.copy(snap = snap ?: old.snap, snapTried = old.snapTried && !force || !error)
        }
        finish(g, m, failed)
    }

    private fun tag(g: Game) = "[${g.system.name}] ${g.title}"

    /** Сохранённые списки обложек: консоль, число имён, когда загружен (для отчёта «Диагностика»). */
    fun indexInfo(): List<Triple<String, Int, Long>> =
        indexDir.listFiles().orEmpty().filter { it.name.endsWith(".txt") }.sortedBy { it.name }.map { f ->
            Triple(f.name.removeSuffix(".txt"), runCatching { f.readLines().count { it.isNotBlank() } }.getOrDefault(-1), f.lastModified())
        }

    @Volatile private var jobRetry = false

    /** Идёт ли сейчас загрузка. */
    val running: Boolean get() = job?.isActive == true

    /** Журнал поиска описания текущей игры (показывается в меню игры). */
    private val trace = ArrayList<String>()
    private fun log(line: String) { if (trace.size < 80) trace += line }

    /**
     * Описания ищутся строго по одной игре: журнал и загруженные элементы Викиданных — общие,
     * а обновление одной игры из меню может совпасть с общей загрузкой.
     */
    private val textLock = Any()

    private fun mark(v: Platforms.Verdict) = when (v) {
        Platforms.Verdict.CONFIRMED -> "✓"
        Platforms.Verdict.WRONG -> "✗ другая консоль"
        Platforms.Verdict.UNKNOWN -> "?"
        Platforms.Verdict.PORT -> "≈ консоль только в тексте статьи"
    }

    /** Описание и факты одной игры. */
    private fun processText(g: Game, title: String?, force: Boolean) = synchronized(textLock) { processTextLocked(g, title, force) }

    private fun processTextLocked(g: Game, title: String?, force: Boolean) {
        val old = _meta.value[g.id] ?: GameMeta()
        var m = old
        var failed = false
        val sw = if (g.system == GameSystem.SWITCH) SwitchLookup(g, title) else null
        entityStash.clear()
        trace.clear()
        val recheck = !old.platformChecked || old.textVer < TEXT_VERSION
        if (force || !old.descTried || (old.descEn == null && old.descRu == null) || recheck) {
            // Для поиска лучше всего подходит официальное название из базы обложек,
            // запасной вариант — название из имени файла
            val queries = listOfNotNull(title, m.name ?: sw?.name, g.title).distinct()
            val before = errorsHere()
            lastProblem.set(null)
            val d = runCatching { fetchDescription(g, queries, sw) }
                .onFailure { DiagLog.error(tag(g) + ": описание", it) }.getOrNull()
            val error = d == null && errorsHere() > before
            DiagLog.add(tag(g) + ": описание " + when {
                d != null -> "✓ " + listOfNotNull(d.en?.let { "en" }, d.ru?.let { "ru" }).joinToString("+") + (d.wikiEn ?: d.wikiRu)?.let { " («$it»)" }.orEmpty()
                error -> "— сбой сети (${lastProblem.get() ?: "?"})"
                else -> "— не найдено (искали: ${queries.joinToString(" | ")})"
            })
            if (error) log("Сбой сети (${lastProblem.get() ?: "?"}) — повтор позже. Причину покажет Настройки → «Проверка соединения»")
            failed = failed || error
            m = if (d != null) m.copy(
                // Новое (проверенное) описание целиком заменяет старое
                descEn = d.en, descRu = d.ru, wikiEn = d.wikiEn, wikiRu = d.wikiRu, srcEn = d.srcEn, srcRu = d.srcRu,
                name = m.name ?: d.wikiEn?.replace(Regex("""\s*\([^)]*\)$"""), ""),
                qid = d.qid,
                descTried = true, platformChecked = true,
            ) else if (!error && recheck && runCatching { wrongPlatform(g.system, old.qid, listOfNotNull(old.descEn, old.descRu).joinToString(" ")) || notAGameArticle(old) }.getOrDefault(false)) m.copy(
                // Старое описание было про игру для другой консоли, а правильного не нашлось — убираем
                descEn = null, descRu = null, wikiEn = null, wikiRu = null, srcEn = null, srcRu = null, qid = null,
                descTried = true, platformChecked = true,
            ) else m.copy(
                descTried = old.descTried && !force || !error,
                platformChecked = old.platformChecked || !error,
            )
        }
        val qidChanged = m.qid != old.qid
        if (qidChanged) m = m.copy(year = null, genresEn = emptyList(), genresRu = emptyList(), developers = emptyList(), publishers = emptyList())
        if (force || !old.factsTried || qidChanged || recheck) {
            val before = errorsHere()
            val f = runCatching { fetchFacts(m, g.system) }.onFailure { DiagLog.error(tag(g) + ": факты", it) }.getOrNull()
            val error = f == null && errorsHere() > before
            failed = failed || error
            m = m.copy(
                qid = f?.first ?: m.qid,
                year = f?.second?.year ?: m.year,
                genresEn = f?.second?.genresEn ?: m.genresEn,
                genresRu = f?.second?.genresRu ?: m.genresRu,
                developers = f?.second?.developers ?: m.developers,
                publishers = f?.second?.publishers ?: m.publishers,
                factsTried = old.factsTried && !force || !error,
            )
        }
        if (trace.isNotEmpty()) m = m.copy(lookupLog = trace.toList())
        if (!failed) m = m.copy(textVer = TEXT_VERSION)
        finish(g, m, failed)
    }

    /** Сохранить результат; после сетевого сбоя — повторить не раньше чем через полчаса. */
    private fun finish(g: Game, result: GameMeta, failed: Boolean) {
        val now = System.currentTimeMillis()
        val retryAt = if (failed) now + 30 * 60_000 else result.retryAt.takeIf { it > now && it - now <= 30 * 60_000 && runFailed.contains(g.id) } ?: 0
        if (failed) runFailed.add(g.id)
        _meta.update { it + (g.id to result.copy(ver = META_VERSION, retryAt = retryAt)) }
    }

    /** Игры, у которых в этом проходе уже был сетевой сбой (чтобы второй этап не стёр отметку). */
    private val runFailed = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    // --- Сеть ---

    /**
     * Сетевые сбои (лимит запросов, нет связи, ошибка сервера) — в отличие от «не нашлось».
     * Считаются отдельно для каждого потока: обложки грузятся параллельно.
     */
    private val netErrors = ThreadLocal.withInitial { 0 }
    private fun errorsHere(): Int = netErrors.get() ?: 0

    /** Последняя сетевая проблема в этом потоке: «en.wikipedia.org: HTTP 429» — для журнала поиска. */
    private val lastProblem = ThreadLocal<String?>()

    /** Последняя сетевая проблема вообще (для подсказки в интерфейсе). */
    private val _problem = MutableStateFlow<String?>(null)
    val problem: StateFlow<String?> = _problem

    /** Подпись запросов: с почтой для связи Википедия считает лаунчер «опознанным» и даёт 200 запросов в минуту вместо 10. */
    /**
     * Правила Википедии требуют, чтобы программа указывала, как с ней связаться (почту или адрес
     * страницы с контактами), — иначе запросы режутся. По умолчанию это страница проекта на GitHub
     * (там есть Issues); почта, если пользователь её указал, добавляется к ней.
     */
    fun userAgent(): String {
        val c = contact()?.trim()?.takeIf { it.contains('@') || it.startsWith("http") }
        val who = if (c != null) "$PROJECT_URL; $c" else PROJECT_URL
        return "RetroHome/$VERSION ($who; personal Android game launcher)"
    }

    /** Пауза между запросами к Википедии: под её лимит (10 в минуту без почты, 200 с почтой). */
    private fun wikimediaGap(): Long = 350
    private val throttleLock = Object()
    private var lastWikimedia = 0L

    /**
     * Википедия и Викиданные ограничивают частоту запросов: при слишком частых отвечают
     * «429 — слишком много запросов». Поэтому запросы к ним идут не чаще раза в 0,6 с,
     * а на 429 лаунчер ждёт, сколько попросит сервер, и повторяет.
     */
    private fun throttle(url: String) {
        val host = runCatching { URL(url).host }.getOrDefault("")
        if (!host.endsWith("wikipedia.org") && !host.endsWith("wikidata.org")) return
        synchronized(throttleLock) {
            val wait = lastWikimedia + wikimediaGap() - System.currentTimeMillis()
            if (wait > 0) sleep(wait)
            lastWikimedia = System.currentTimeMillis()
        }
    }

    private fun request(url: String, accept: String?): ByteArray? {
        var last: HttpResponse? = null
        for (attempt in 0..3) {
            throttle(url)
            val r = transport.open(url, accept, userAgent())
            last = r
            val wait: Long = when (r.code) {
                200 -> { _problem.value = null; return r.body }
                // адрес сайта не находится (DNS, блокировка) — повтор через секунды не поможет
                0 -> if (r.error?.contains("UnknownHost") == true) break
                    // сайт не отвечает — одна повторная попытка, дальше считаем сбоем (а не ждём минутами)
                    else if (attempt >= 1) break else 2L
                // сервер перегружен — подождать и повторить
                500, 502, 503, 504 -> (2L shl attempt).coerceAtMost(5)
                // «слишком много запросов» — ждём, сколько попросил сервер
                429 -> (r.retryAfter?.trim()?.toLongOrNull() ?: (2L shl attempt)).coerceIn(1, 30)
                // сайт отклонил запрос (например, фильтр по подписи или по адресу) — это не «нет такой страницы»
                403 -> break
                else -> return null // 404 и т.п. — такой страницы просто нет
            }
            if (attempt < 3) sleep(wait * 1000)
        }
        netErrors.set(errorsHere() + 1)
        val host = runCatching { URL(url).host }.getOrDefault("?")
        val why = last?.let { if (it.code == 0) it.error ?: "нет соединения" else "HTTP ${it.code}" } ?: "?"
        // В журнал — первый сбой: обычно он и есть причина, остальные — следствия
        "$host: $why".also { if (lastProblem.get() == null) lastProblem.set(it); _problem.value = it }
        return null
    }

    private fun get(url: String): String? = request(url, "application/json, text/html")?.let { String(it) }

    private fun getBytes(url: String): ByteArray? = request(url, null)

    /** Список обложек каталога системы; хранится неделю. */
    private fun index(system: GameSystem): Thumbnails.Index? = synchronized(indexes) { loadIndex(system) }

    /** Сайт обложек libretro недоступен (DNS, блокировка, таймауты) — берём те же обложки с GitHub. */
    @Volatile private var mirror = false

    /** Когда список обложек консоли последний раз не загрузился ни откуда: не пытаться снова для каждой игры. */
    private val indexFailedAt = java.util.concurrent.ConcurrentHashMap<GameSystem, Long>()

    /** Сначала источник, который работал последним, потом другой. */
    private fun sourceOrder() = if (mirror) listOf(true, false) else listOf(false, true)

    private fun useSource(fromMirror: Boolean) {
        if (fromMirror == mirror) return
        mirror = fromMirror
        DiagLog.add(
            if (fromMirror) "Сайт обложек libretro недоступен (${lastProblem.get() ?: "?"}) — беру обложки с GitHub"
            else "Сайт обложек libretro снова отвечает — беру обложки с него"
        )
    }

    private fun loadIndex(system: GameSystem): Thumbnails.Index? {
        indexes[system]?.let { return it }
        val file = File(indexDir, system.name + ".txt")
        // Пустой список (ответ испорчен фильтром, VPN или страницей-заглушкой) не запоминаем
        val fresh = file.exists() && file.length() > 0 && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 3600 * 1000
        if (fresh) {
            val names = file.readLines().filter { it.isNotBlank() }
            DiagLog.add("Список обложек ${system.name}: из кэша, ${names.size} имён")
            return Thumbnails.Index(names).also { indexes[system] = it }
        }
        if (Thumbnails.indexUrl(system) == null) {
            DiagLog.add("Список обложек ${system.name}: для этой консоли в базе libretro обложек нет")
            return null
        }
        // Только что не загрузился — не ждать сеть заново для каждой игры, повторим позже
        indexFailedAt[system]?.let { t ->
            if (System.currentTimeMillis() - t < 2 * 60_000) {
                netErrors.set(errorsHere() + 1)
                if (lastProblem.get() == null) lastProblem.set("список обложек ${system.name} недоступен, повтор позже")
                return null
            }
        }
        var names: List<String>? = null
        for (fromMirror in sourceOrder()) {
            names = if (fromMirror) indexFromMirror(system) else indexFromLibretro(system)
            if (names != null) { useSource(fromMirror); break }
        }
        if (names == null) {
            indexFailedAt[system] = System.currentTimeMillis()
            return null
        }
        indexFailedAt.remove(system)
        file.writeText(names.joinToString("\n"))
        return Thumbnails.Index(names).also { indexes[system] = it }
    }

    /** Ответ сайта, в котором нет ни одной обложки, — это сбой (страница-заглушка, обрыв), а не «обложек нет». */
    private fun emptyListProblem(system: GameSystem, host: String, body: String) {
        netErrors.set(errorsHere() + 1)
        val problem = "$host: вместо списка обложек пришла другая страница"
        if (lastProblem.get() == null) lastProblem.set(problem)
        _problem.value = problem
        DiagLog.add("Список обложек ${system.name}: начало ответа: " + body.take(160).replace(Regex("\\s+"), " "))
    }

    private fun indexFromLibretro(system: GameSystem): List<String>? {
        val url = Thumbnails.indexUrl(system) ?: return null
        val html = get(url) ?: return null.also { DiagLog.add("Список обложек ${system.name}: libretro не ответил (${lastProblem.get() ?: "?"})") }
        val parsed = Thumbnails.parseIndex(html)
        DiagLog.add("Список обложек ${system.name}: libretro, ${html.length / 1024} КБ, ${parsed.size} имён")
        if (parsed.isEmpty()) { emptyListProblem(system, "thumbnails.libretro.com", html); return null }
        return parsed
    }

    private fun indexFromMirror(system: GameSystem): List<String>? {
        val rootUrl = Thumbnails.mirrorTreeUrl(system, "master") ?: return null
        val root = get(rootUrl) ?: return null.also { DiagLog.add("Список обложек ${system.name}: GitHub не ответил (${lastProblem.get() ?: "?"})") }
        val sha = Thumbnails.treeDirSha(root, "Named_Boxarts")
            ?: return null.also { emptyListProblem(system, "api.github.com", root) }
        val dirUrl = Thumbnails.mirrorTreeUrl(system, sha) ?: return null
        val dir = get(dirUrl) ?: return null.also { DiagLog.add("Список обложек ${system.name}: GitHub не ответил (${lastProblem.get() ?: "?"})") }
        val names = Thumbnails.treePngNames(dir)
        DiagLog.add("Список обложек ${system.name}: GitHub, ${names.size} имён")
        if (names.isEmpty()) { emptyListProblem(system, "api.github.com", dir); return null }
        return names
    }

    /**
     * Картинка из базы обложек: с сайта libretro, а если он недоступен — с GitHub
     * (там часть файлов — ссылки на другой файл, по ним идём дальше).
     */
    private fun thumbBytes(system: GameSystem, dir: String, name: String): ByteArray? {
        for (fromMirror in sourceOrder()) {
            val before = errorsHere()
            var bytes = getBytes(Thumbnails.fileUrl(system, dir, name, fromMirror) ?: return null)
            if (bytes != null && fromMirror) Thumbnails.linkTarget(bytes)?.let { target ->
                bytes = getBytes(Thumbnails.fileUrl(system, dir, target, true) ?: return null)
            }
            if (bytes != null) { useSource(fromMirror); return bytes }
            // Файла просто нет (404) — в другом источнике его тоже нет; пробуем другой только после сбоя сети
            if (errorsHere() == before) return null
        }
        return null
    }

    /** Файл обложки и официальное название из базы. */
    private fun fetchCover(g: Game, title: String?): Triple<String, String, String>? {
        val idx = index(g.system) ?: return null
        val name = Thumbnails.match(g.fileName, idx, title)
            ?: return null.also { DiagLog.add(tag(g) + ": обложка — нет похожего имени в списке (файл «${g.fileName}»${title?.let { t -> ", название «$t»" }.orEmpty()})") }
        val bytes = thumbBytes(g.system, "Named_Boxarts", name)
            ?: return null.also { DiagLog.add(tag(g) + ": обложка «$name» — картинка не загрузилась (${lastProblem.get() ?: "нет на сервере"})") }
        // Уменьшаем до разумного размера, чтобы не занимать память
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outHeight / (sample * 2) >= 560) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null.also { DiagLog.add(tag(g) + ": обложка «$name» — не удалось прочитать картинку (${bytes.size} байт, начало: ${String(bytes.copyOf(minOf(24, bytes.size))).replace(Regex("[^\\x20-\\x7E]"), ".")})") }
        val out = saveCover(g, bmp)
        bmp.recycle()
        return Triple(out, Thumbnails.displayName(name), name)
    }

    private fun saveCover(g: Game, bmp: Bitmap, suffix: String = ""): String {
        val out = "${g.id.hashCode().toUInt().toString(16)}$suffix.webp"
        File(coversDir, out).outputStream().use { bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 85, it) }
        return out
    }

    // --- Switch ---

    /** Список игр Switch с tinfoil.media; хранится неделю. */
    private class SwitchIndex(entries: List<Pair<String, String>>) {
        val byId: Map<String, String> = entries.toMap()
        val idByName: Map<String, String> = entries.groupBy({ it.second }, { it.first }).mapValues { it.value.first() }
        val index = Thumbnails.Index(idByName.keys.toList())
    }

    private var switchIndex: SwitchIndex? = null

    @Synchronized
    private fun switchIndex(): SwitchIndex? {
        switchIndex?.let { return it }
        val file = File(indexDir, "SWITCH_tinfoil.tsv")
        val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 3600 * 1000
        fun cached() = file.readLines().mapNotNull { l -> l.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        val entries = if (fresh) cached() else {
            val body = tinfoil(SwitchDb.LIST_URL)?.let { String(it) }
            val list = body?.let(SwitchDb::parseList).orEmpty()
            when {
                list.isNotEmpty() -> list.also { file.writeText(it.joinToString("\n") { e -> "${e.first}\t${e.second}" }) }
                // tinfoil не ответил — старый список лучше, чем никакого (игры почти не меняют Title ID)
                file.exists() && file.length() > 0 -> cached().also { DiagLog.add("Список игр Switch: tinfoil недоступен, взят сохранённый (${it.size})") }
                else -> {
                    DiagLog.add("Список игр Switch: tinfoil недоступен (${lastProblem.get() ?: "?"}) — игры без Title ID в имени файла не опознать")
                    return null
                }
            }
        }
        if (entries.isEmpty()) return null
        return SwitchIndex(entries).also { switchIndex = it }
    }

    /** Сайты, которые не отвечали: до этого времени к ним не обращаемся. */
    private val downUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /**
     * Запрос к сайту, который бывает недоступен (tinfoil.media, поиск Nintendo). После сбоя
     * (таймаут, ошибка сервера, нет адреса) 10 минут не ждём его: каждый зависший запрос —
     * до пары минут, а игр много. Пропуск считается сбоем сети, чтобы игра, которой нет
     * в других источниках, не записалась как «не найдено».
     */
    private fun flaky(site: String, url: String): ByteArray? {
        if (System.currentTimeMillis() < (downUntil[site] ?: 0)) {
            netErrors.set(errorsHere() + 1)
            if (lastProblem.get() == null) lastProblem.set("$site недоступен, повтор позже")
            return null
        }
        val before = errorsHere()
        val bytes = getBytes(url)
        if (bytes == null && errorsHere() > before) {
            downUntil[site] = System.currentTimeMillis() + 10 * 60_000
            DiagLog.add("$site не отвечает (${lastProblem.get() ?: "?"}) — 10 минут беру данные Switch из других источников")
        }
        return bytes
    }

    private fun tinfoil(url: String) = flaky("tinfoil.media", url)
    private fun nintendo(url: String) = flaky("nintendo-europe.com", url)

    /** Title ID по названию — поиском в базе Nintendo (когда в имени файла номера нет, а списка tinfoil нет). */
    private fun idByNintendoSearch(name: String): String? {
        val found = nintendo(SwitchDb.euByNameUrl(name))?.let { SwitchDb.parseEu(String(it)) }.orEmpty()
        val want = Thumbnails.Index(listOf(name))
        // Берём только игру с тем же названием (без учёта знаков и регистра), а не первую попавшуюся
        val hit = found.firstOrNull { "Nintendo Switch" in it.systems && Thumbnails.match(it.title, want, null) != null }
        if (hit != null) DiagLog.add("[SWITCH] «$name»: Title ID ${hit.id} найден поиском Nintendo")
        return hit?.id
    }

    /** Поиск игры Switch: Title ID из имени файла или по названию. Страница игры грузится один раз. */
    private inner class SwitchLookup(val game: Game, val title: String?) {
        val id: String? by lazy {
            if (title == null) SwitchDb.titleId(game.fileName)?.let { return@lazy it }
            val idx = runCatching { switchIndex() }.getOrNull()
                ?: return@lazy SwitchDb.titleId(game.fileName) ?: idByNintendoSearch(title ?: game.title)
            Thumbnails.match(game.fileName, idx.index, title)?.let { idx.idByName[it] } ?: SwitchDb.titleId(game.fileName)
        }

        /** Игра в европейской базе Nintendo (по Title ID): коробка, квадратная и широкая картинки. */
        val eu: SwitchDb.EuGame? by lazy {
            val tid = id ?: return@lazy null
            nintendo(SwitchDb.euByIdUrl(tid))?.let { SwitchDb.pickEu(SwitchDb.parseEu(String(it)), tid) }
        }
        val page: SwitchDb.Page? by lazy {
            val tid = id ?: return@lazy null
            tinfoil(SwitchDb.pageUrl(tid))?.let { SwitchDb.parsePage(String(it)) }?.let { return@lazy it }
            // tinfoil не ответил или страницы нет — запасной источник Nlib (по тому же Title ID)
            get(SwitchDb.nlibInfoUrl(tid))?.let(SwitchDb::parseNlib)
                ?.also { DiagLog.add("[SWITCH] ${game.title}: данные из запасного источника (api.nlib.cc)") }
        }
        val name: String? get() = id?.let { i -> switchIndex?.byId?.get(i) } ?: page?.name
    }

    private fun fetchSwitchCover(sw: SwitchLookup): Triple<String, String?, String?>? {
        val id = sw.id ?: return null
        // Лучше всего — коробка из европейской базы Nintendo: настоящая обложка, сохраняем как есть
        sw.eu?.box?.let { url -> getBytes(url)?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } }?.let { box ->
            val out = saveCover(sw.game, box)
            box.recycle()
            return Triple(out, sw.eu?.title?.ifBlank { null } ?: sw.name, null)
        }
        // Иначе квадратная картинка: Nintendo, затем tinfoil, затем Nlib
        val icon = listOf(
            { sw.eu?.square?.let { getBytes(it) } },
            { tinfoil(SwitchDb.iconUrl(id)) },
            { getBytes(SwitchDb.nlibIconUrl(id)) },
        ).firstNotNullOfOrNull { load -> load()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) } } ?: return null.also { DiagLog.add("[SWITCH] ${sw.game.title}: иконка не загрузилась (${lastProblem.get() ?: "нет в базах"})") }
        val poster = switchPoster(icon)
        icon.recycle()
        val out = saveCover(sw.game, poster)
        poster.recycle()
        return Triple(out, sw.name, null)
    }

    /**
     * Иконка Switch квадратная, а карточки — как коробка 3:4. Делаем «коробку»:
     * сверху и снизу размытый фон в цветах самой иконки, иконка по центру.
     */
    private fun switchPoster(icon: Bitmap): Bitmap {
        val w = 384
        val h = 512
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // Фон: середина иконки в пропорции 3:4, сильно размытая (уменьшить и растянуть обратно)
        val cw = icon.width * 3 / 4
        val middle = Bitmap.createBitmap(icon, (icon.width - cw) / 2, 0, cw, icon.height)
        val tiny = Bitmap.createScaledBitmap(middle, 9, 12, true)
        val blur = Bitmap.createScaledBitmap(tiny, w, h, true)
        c.drawBitmap(blur, 0f, 0f, null)
        c.drawColor(android.graphics.Color.argb(90, 0, 0, 0))
        if (middle != icon) middle.recycle()
        val top = (h - w) / 2f
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(icon, null, RectF(0f, top, w.toFloat(), top + w), paint)
        tiny.recycle()
        blur.recycle()
        return out
    }

    // --- Описания: источники по очереди, следующий — только если в предыдущем не нашлось ---

    data class Descriptions(
        val en: String?, val ru: String?,
        val wikiEn: String?, val wikiRu: String?,
        val srcEn: String?, val srcRu: String?,
        val qid: String? = null,
        val entity: Wikidata.Entity? = null,
    )


    /** Жанр, разработчик, издатель и год — из Викиданных по статье Википедии. */
    private fun fetchFacts(m: GameMeta, system: GameSystem): Pair<String, Wikidata.Facts>? {
        val qid = m.qid
            ?: m.wikiEn?.let { t -> pages("en", listOf(t))[t]?.qid }
            ?: m.wikiRu?.let { t -> pages("ru", listOf(t))[t]?.qid }
            ?: return null
        val cached = entityStash[qid]
        val e = cached ?: get(Wikidata.entityUrl(qid))?.let(Wikidata::parseEntity) ?: return null
        val ids = e.referencedIds()
        val labels = labelsFor(ids) ?: return null
        return qid to Wikidata.facts(e, labels, system)
    }

    /** Имя в базе libretro для игр, у которых обложка нашлась ещё старой версией лаунчера. */
    private fun findThumbName(g: Game, title: String?): String? {
        val idx = index(g.system) ?: return null
        return Thumbnails.match(g.fileName, idx, title)
    }

    /** Снимок экрана из игры (или заставка), уменьшенный и сохранённый рядом с обложками. */
    private fun fetchSnap(g: Game, thumb: String?): String? {
        if (thumb == null) return null
        for (dir in listOf("Named_Snaps", "Named_Titles")) {
            val bytes = thumbBytes(g.system, dir, thumb) ?: continue
            return saveSnap(g, bytes)
        }
        return null
    }

    private fun fetchSwitchSnap(sw: SwitchLookup): String? {
        // Снимок экрана из игры (tinfoil или Nlib), а если его нет — широкая картинка из базы Nintendo
        val url = sw.page?.shots?.firstOrNull() ?: sw.eu?.wide ?: return null
        return getBytes(url)?.let { saveSnap(sw.game, it) }
    }

    private fun saveSnap(g: Game, bytes: ByteArray): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 960) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val out = saveCover(g, bmp, "_s")
        bmp.recycle()
        return out
    }

    /** Статьи одним запросом (с переходом по перенаправлениям). */
    private fun pages(lang: String, titles: List<String>, linkTo: String? = null): Map<String, Wiki.Page> =
        if (titles.isEmpty()) emptyMap()
        else get(Wiki.pagesUrl(lang, titles, linkTo))?.let { Wiki.parsePages(it, titles) }.orEmpty()

    private fun page(lang: String, title: String): Wiki.Page? =
        pages(lang, listOf(title))[title]?.takeIf { !it.disambiguation && it.extract.isNotBlank() }

    // Подписи жанров, компаний и консолей повторяются от игры к игре — храним их на устройстве
    private val labelsFile = File(context.filesDir, "wd_labels.json")
    private val labelsSerializer = MapSerializer(String.serializer(), MapSerializer(String.serializer(), String.serializer()))
    private val labelCache: MutableMap<String, Map<String, String>> by lazy {
        runCatching { HashMap(json.decodeFromString(labelsSerializer, labelsFile.readText())) }.getOrDefault(HashMap())
    }

    /** Подписи элементов: из сохранённых, недостающие — одним запросом. null — запрос не удался. */
    @Synchronized
    private fun labelsFor(ids: Collection<String>): Map<String, Map<String, String>>? {
        val missing = ids.filter { it !in labelCache }.distinct()
        if (missing.isNotEmpty()) {
            for (chunk in missing.chunked(50)) {
                val got = get(Wikidata.labelsUrl(chunk))?.let(Wikidata::parseLabels) ?: return null
                labelCache.putAll(got)
                chunk.filter { it !in got }.forEach { labelCache[it] = emptyMap() }
            }
            runCatching { labelsFile.writeText(json.encodeToString(labelsSerializer, labelCache)) }
        }
        return ids.associateWith { labelCache[it].orEmpty() }
    }

    /** Загруженные элементы Викиданных за обработку одной игры (чтобы не грузить их дважды). */
    private val entityStash = HashMap<String, Wikidata.Entity>()

    /** Английские подписи платформ элементов. */
    private fun platformLabels(entities: Collection<Wikidata.Entity>): Map<String, Map<String, String>> {
        val ids = entities.flatMap { it.refs[Wikidata.PLATFORM].orEmpty() }.distinct().take(50)
        return labelsFor(ids).orEmpty()
    }

    private fun verdictOf(system: GameSystem, e: Wikidata.Entity?, labels: Map<String, Map<String, String>>, text: String): Platforms.Verdict {
        val ids = e?.refs?.get(Wikidata.PLATFORM).orEmpty()
        val names = ids.map { labels[it]?.get("en")?.takeIf { n -> n.isNotBlank() } }
        val byData = Platforms.byPlatforms(system, ids, names)
        val byText = Platforms.byText(system, text)
        return when (byData) {
            Platforms.Verdict.CONFIRMED -> byData
            // Викиданные не знают о порте, а статья о нём пишет («…переиздана для PlayStation Portable»)
            Platforms.Verdict.WRONG -> if (byText == Platforms.Verdict.CONFIRMED) Platforms.Verdict.PORT else byData
            else -> byText
        }
    }

    /**
     * Для какой консоли статьи: по платформам из Викиданных, а если их там нет — по тексту.
     * Отбрасывает ремейки и одноимённые игры для других консолей.
     */
    private fun verdicts(system: GameSystem, pages: List<Wiki.Page>): Map<String, Platforms.Verdict> {
        if (!Platforms.checkable(system)) return pages.associate { it.title to Platforms.Verdict.UNKNOWN }
        val qids = pages.mapNotNull { it.qid }.distinct()
        val entities = if (qids.isEmpty()) emptyMap() else get(Wikidata.entitiesUrl(qids))?.let(Wikidata::parseEntities).orEmpty()
        entityStash.putAll(entities)
        val labels = platformLabels(entities.values)
        return pages.associate { p -> p.title to verdictOf(system, p.qid?.let { entities[it] }, labels, p.description + " " + p.extract) }
    }

    /**
     * Из подходящих статей — подтверждённая для нашей консоли, иначе та, где консоль не указана,
     * иначе статья с точно таким же названием, где наша консоль упомянута только в тексте (порт).
     * Ремейки («Final Fantasy VII Remake» упоминает PlayStation как оригинал) так не проходят: название другое.
     */
    private fun choose(system: GameSystem, candidates: List<Wiki.Page>, exact: (Wiki.Page) -> Boolean = { false }): Wiki.Page? {
        if (candidates.isEmpty()) return null
        val v = verdicts(system, candidates)
        candidates.forEach { log("   • ${it.title} ${mark(v[it.title] ?: Platforms.Verdict.UNKNOWN)}") }
        return candidates.firstOrNull { v[it.title] == Platforms.Verdict.CONFIRMED }
            ?: candidates.firstOrNull { v[it.title] == Platforms.Verdict.UNKNOWN }
            ?: candidates.firstOrNull { v[it.title] == Platforms.Verdict.PORT && exact(it) }
    }

    private fun findArticle(lang: String, g: Game, title: String, linkTo: String?): Wiki.Page? {
        // Сначала с уточнением «игра + платформа», потом просто по названию
        val tried = HashSet<String>()
        for (q in listOf(Wiki.searchQuery(lang, title, g.system), title)) {
            val errBefore = errorsHere()
            val results = get(Wiki.searchUrl(lang, q))?.let(Wiki::parseHits).orEmpty()
            log(if (errorsHere() > errBefore) "$lang: «$q» — сбой сети" else "$lang: «$q» — найдено ${results.size}")
            val ranked = Wiki.rankHits(results, title).filter { tried.add(it) }.take(5)
            val found = pages(lang, ranked, linkTo)
            val good = ranked.mapNotNull { found[it] }
                .filter { p ->
                    val ok = !p.disambiguation && p.extract.isNotBlank() && Wiki.looksLikeGame(p.summary(), lang)
                    if (!ok) log("   • ${p.title} ✗ не статья об игре")
                    ok
                }
            val exact = { p: Wiki.Page ->
                Wiki.sameTitle(p.title, title) || results.any { h -> h.title == p.title && h.redirect?.let { Wiki.sameTitle(it, title) } == true }
            }
            choose(g.system, good, exact)?.let { log("   → ${it.title}"); return it }
            // Название статьи не совпало: может, игра упомянута в её начале под другим (региональным) названием
            val others = results.map { it.title }.filter { tried.add(it) }.take(3)
            val more = pages(lang, others, linkTo)
            val mentioned = others.mapNotNull { more[it] }
                .filter { !it.disambiguation && Wiki.looksLikeGame(it.summary(), lang) && Wiki.leadMentions(it.extract, title) }
            choose(g.system, mentioned)?.let { log("   → ${it.title} (по другому названию)"); return it }
        }
        return null
    }

    private fun findWikidata(g: Game, titles: List<String>): Wikidata.Entity? {
        for (t in titles) {
            val lang = if (t.any { it in '\u0400'..'\u04FF' }) "ru" else "en"
            val errBefore = errorsHere()
            val ids = get(Wikidata.searchUrl(t, lang))?.let(Wikidata::parseSearch)?.let { Wikidata.pickAll(it, t, lang) }.orEmpty().take(4)
            log(if (errorsHere() > errBefore) "Викиданные: «$t» — сбой сети" else "Викиданные: «$t» — подходящих ${ids.size}")
            if (ids.isEmpty()) continue
            val entities = get(Wikidata.entitiesUrl(ids))?.let(Wikidata::parseEntities).orEmpty()
            entityStash.putAll(entities)
            val ordered = ids.mapNotNull { entities[it] }
            if (!Platforms.checkable(g.system)) ordered.firstOrNull()?.let { return it }
            val labels = platformLabels(ordered)
            val verdicts = ordered.associateWith { verdictOf(g.system, it, labels, "") }
            ordered.firstOrNull { verdicts[it] == Platforms.Verdict.CONFIRMED }?.let { return it }
            ordered.firstOrNull { verdicts[it] == Platforms.Verdict.UNKNOWN }?.let { return it }
        }
        return null
    }

    /** Старое описание взято из статьи не об игре (например, о персонаже)? */
    private fun notAGameArticle(old: GameMeta): Boolean {
        val checks = listOfNotNull(old.wikiEn?.let { "en" to it }, old.wikiRu?.let { "ru" to it })
            .filter { (lang, _) -> (if (lang == "en") old.srcEn else old.srcRu).let { it == null || it == DescSource.WIKIPEDIA } }
        return checks.any { (lang, title) ->
            val p = page(lang, title) ?: return@any false
            !Wiki.looksLikeGame(p.summary(), lang).also { if (!it) log("Старое описание «$title» — не статья об игре, убрано") }
        }
    }

    /** Старое описание было про игру для другой консоли? (проверка уже найденных раньше описаний) */
    private fun wrongPlatform(system: GameSystem, qid: String?, text: String): Boolean {
        if (qid == null || !Platforms.checkable(system)) return false
        val e = entityStash[qid] ?: get(Wikidata.entityUrl(qid))?.let(Wikidata::parseEntity) ?: return false
        return verdictOf(system, e, platformLabels(listOf(e)), text) == Platforms.Verdict.WRONG
    }

    private fun fetchDescription(g: Game, titles: List<String>, sw: SwitchLookup?): Descriptions? {
        val errStart = errorsHere()
        // 1) Википедия: английская статья, русская по межъязыковой ссылке или поиском
        var en: Wiki.Page? = null
        var ru: Wiki.Page? = null
        for (t in titles) { en = findArticle("en", g, t, linkTo = "ru"); if (en != null) break }
        en?.link?.let { ru = page("ru", it) }
        if (ru == null) for (t in titles) { ru = findArticle("ru", g, t, linkTo = "en"); if (ru != null) break }
        if (en == null) ru?.link?.let { en = page("en", it) }
        var enText = en?.extract
        var ruText = ru?.extract
        var srcEn = en?.let { DescSource.WIKIPEDIA }
        var srcRu = ru?.let { DescSource.WIKIPEDIA }

        // 2) Викиданные: находят игру по синонимам и ведут к статьям, которые поиск не нашёл
        var entity: Wikidata.Entity? = null
        if (enText == null && ruText == null) {
            entity = findWikidata(g, titles)
            entity?.wiki?.get("en")?.let { t -> page("en", t)?.let { en = it; enText = it.extract; srcEn = DescSource.WIKIPEDIA } }
            entity?.wiki?.get("ru")?.let { t -> page("ru", t)?.let { ru = it; ruText = it.extract; srcRu = DescSource.WIKIPEDIA } }
        }

        // 3) Для Switch — описание из магазина eShop (только на английском)
        if (enText == null && sw != null) {
            sw.page?.description?.let { enText = it; srcEn = DescSource.ESHOP }
        }

        // 4) Последний вариант — короткая справка из фактов Викиданных на обоих языках
        if (enText == null && ruText == null && entity != null) {
            val ids = entity.referencedIds()
            val labels = labelsFor(ids).orEmpty()
            val fallback = titles.firstOrNull() ?: g.title
            Wikidata.describe(entity, labels, "en", fallback)?.let { enText = it; srcEn = DescSource.WIKIDATA }
            Wikidata.describe(entity, labels, "ru", fallback)?.let { ruText = it; srcRu = DescSource.WIKIDATA }
        }

        if (enText == null && ruText == null) {
            log(if (errorsHere() > errStart) "Итог: не удалось загрузить из-за сбоя сети" else "Итог: описание не найдено")
            return null
        }
        log("Итог: EN — ${srcEn ?: "нет"}${en?.title?.let { " «$it»" } ?: ""}, RU — ${srcRu ?: "нет"}${ru?.title?.let { " «$it»" } ?: ""}")
        return Descriptions(enText, ruText, en?.title, ru?.title, srcEn, srcRu, en?.qid ?: ru?.qid ?: entity?.id, entity)
    }

    companion object {
        const val VERSION = "0.37"
        /** Страница проекта — контакт в подписи запросов (Issues для связи). */
        const val PROJECT_URL = "https://github.com/fenomen64/retro-home"
        const val USER_AGENT = "RetroHome/$VERSION (personal Android game launcher)"
    }
}
