package com.retrohome.launcher.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Состояние входа в RetroAchievements. */
enum class RaStatus { NO_ACCOUNT, OK, AUTH_ERROR, NETWORK_ERROR }

/** Всё, что лаунчер знает о достижениях. */
data class RaState(
    val status: RaStatus = RaStatus.NO_ACCOUNT,
    /** Игра лаунчера (Game.id) → игра на сайте (null — на сайте не найдена). */
    val matches: Map<String, Int?> = emptyMap(),
    /** Сколько достижений у игры на сайте. */
    val totals: Map<Int, Int> = emptyMap(),
    /** Прогресс пользователя по играм сайта. */
    val progress: Map<Int, Ra.Completion> = emptyMap(),
    /** Полные списки достижений (грузятся при открытии). */
    val details: Map<Int, Ra.GameProgress> = emptyMap(),
    /** Игры сайта, список которых сейчас грузится. */
    val loading: Set<Int> = emptySet(),
) {
    /** Сводка для игры лаунчера (null — достижений нет или игра не опознана). */
    fun summary(gameId: String): RaSummary? {
        val id = matches[gameId] ?: return null
        val d = details[id]
        val p = progress[id]
        val total = d?.total ?: p?.total?.takeIf { it > 0 } ?: totals[id] ?: return null
        if (total <= 0) return null
        // Полный список свежее общего прогресса, если загружен позже
        return if (d != null) RaSummary(id, total, d.earned, d.earnedHardcore)
        else RaSummary(id, total, p?.earned ?: 0, p?.earnedHardcore ?: 0)
    }
}

/**
 * Достижения RetroAchievements: списки игр консолей (хранятся неделю), сопоставление файлов
 * с играми сайта, общий прогресс пользователя и полные списки достижений игр.
 * Ключ доступа уходит только на retroachievements.org.
 */
class AchievementsRepository(
    context: Context,
    private val scope: CoroutineScope,
    private val transport: Transport = UrlTransport,
    /** Логин и ключ Web API; null — аккаунт не указан. */
    private val account: () -> Pair<String, String>?,
    /** Содержимое ROM-файла игры (для отпечатка) или null. */
    private val readRom: (Game) -> ByteArray? = { null },
    private val now: () -> Long = { System.currentTimeMillis() },
    private val pause: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val dir = File(context.filesDir, "ra").apply { mkdirs() }
    private val matchFile = File(dir, "matches.json")
    private val progressFile = File(dir, "progress.json")

    @Serializable
    private data class Saved(
        val matches: Map<String, Int> = emptyMap(),
        /** Игры, не найденные на сайте, и когда проверяли. */
        val missing: Map<String, Long> = emptyMap(),
        val totals: Map<Int, Int> = emptyMap(),
        val user: String? = null,
    )

    private var saved = load()
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<RaState> = _state

    private val lock = Mutex()
    private var syncJob: Job? = null
    private var lastProgress = 0L

    private fun load(): Saved = runCatching { json.decodeFromString(Saved.serializer(), matchFile.readText()) }.getOrDefault(Saved())

    private fun initialState(): RaState {
        val acc = account()
        if (acc == null || saved.user != acc.first) return RaState(if (acc == null) RaStatus.NO_ACCOUNT else RaStatus.OK)
        val progress = runCatching {
            json.decodeFromString(ListSerializer(Ra.Completion.serializer()), progressFile.readText())
        }.getOrDefault(emptyList()).associateBy { it.gameId }
        val m: Map<String, Int?> = saved.matches + saved.missing.mapValues { null }
        return RaState(RaStatus.OK, m, saved.totals, progress)
    }

    private fun persist() = runCatching {
        matchFile.writeText(json.encodeToString(Saved.serializer(), saved))
        progressFile.writeText(json.encodeToString(ListSerializer(Ra.Completion.serializer()), _state.value.progress.values.toList()))
    }

    private val userAgent get() = "RetroHome/${MetaRepository.VERSION} (personal Android game launcher)"

    /** Запрос к сайту: тело, или null с отметкой ошибки входа/сети. */
    private fun get(url: String): String? {
        for (attempt in 0..2) {
            val r = transport.open(url, "application/json", userAgent)
            when (r.code) {
                200 -> return r.body?.let { String(it) }
                401, 403 -> { _state.update { it.copy(status = RaStatus.AUTH_ERROR) }; return null }
                429 -> pause(((r.retryAfter?.toLongOrNull() ?: 3L).coerceIn(1, 20)) * 1000)
                0, 500, 502, 503, 504 -> pause(1500L * (attempt + 1))
                else -> return null
            }
        }
        _state.update { if (it.status == RaStatus.AUTH_ERROR) it else it.copy(status = RaStatus.NETWORK_ERROR) }
        return null
    }

    /** Аккаунт сменился или удалён: забыть всё прежнее. */
    private fun resetFor(user: String?) {
        saved = Saved(user = user)
        runCatching { matchFile.delete(); progressFile.delete() }
        dir.listFiles { f -> f.name.startsWith("list_") }?.forEach { it.delete() }
        _state.value = RaState(if (user == null) RaStatus.NO_ACCOUNT else RaStatus.OK)
    }

    /** Проверить логин и ключ: имя и очки, или null (с причиной в state.status). */
    fun checkAccount(user: String, key: String): Pair<String, Int>? =
        get(Ra.profileUrl(user, key))?.let(Ra::parseProfile)

    /** Список игр консоли с достижениями (с отпечатками для картриджей). */
    private fun list(system: GameSystem, key: String): List<Ra.ListGame>? {
        val cid = Ra.consoleIds[system] ?: return null
        val file = File(dir, "list_$cid.json")
        val fresh = file.exists() && now() - file.lastModified() < 7L * 24 * 3600_000
        if (fresh) runCatching { return json.decodeFromString(ListSerializer(Ra.ListGame.serializer()), file.readText()) }
        val body = get(Ra.gameListUrl(cid, key, system in Ra.hashable)) ?: return null
        val games = Ra.parseGameList(body)
        if (games.isNotEmpty()) runCatching { file.writeText(json.encodeToString(ListSerializer(Ra.ListGame.serializer()), games)) }
        return games
    }

    /**
     * Опознать игры и обновить прогресс. titlesFor — названия игры для поиска
     * (исправленное вручную, из базы обложек, из имени файла).
     */
    fun sync(games: List<Game>, titlesFor: (Game) -> List<String>, force: Boolean = false) {
        val acc = account() ?: run { if (saved.user != null) resetFor(null); return }
        if (syncJob?.isActive == true && !force) return
        syncJob = scope.launch(Dispatchers.IO) {
            lock.withLock {
                val (user, key) = acc
                if (saved.user != user) resetFor(user)
                _state.update { it.copy(status = RaStatus.OK) }
                val nowMs = now()
                val todo = games.filter { g ->
                    Ra.supported(g.system) && g.id !in saved.matches &&
                        (saved.missing[g.id]?.let { force || nowMs - it > 7L * 24 * 3600_000 } ?: true)
                }
                for ((system, list) in todo.groupBy { it.system }) {
                    val site = list(system, key) ?: if (_state.value.status == RaStatus.AUTH_ERROR) return@withLock else continue
                    for (g in list) {
                        val hash = if (system in Ra.hashable) runCatching { readRom(g)?.let { Ra.hash(system, it) } }.getOrNull() else null
                        val found = Ra.match(titlesFor(g), site, hash)
                        saved = if (found != null) saved.copy(
                            matches = saved.matches + (g.id to found.id),
                            missing = saved.missing - g.id,
                            totals = saved.totals + (found.id to found.total),
                        ) else saved.copy(missing = saved.missing + (g.id to nowMs))
                    }
                    publishMatches()
                    persist()
                }
                publishMatches()
                refreshProgressLocked(user, key, force = true)
                persist()
            }
        }
    }

    private fun publishMatches() {
        val m: Map<String, Int?> = saved.matches + saved.missing.mapValues { null }
        _state.update { it.copy(matches = m, totals = saved.totals) }
    }

    /** Общий прогресс (не чаще раза в 2 минуты, если не force) — например, после возвращения из игры. */
    fun refreshProgress(force: Boolean = false) {
        val (user, key) = account() ?: return
        if (saved.user != user) return
        scope.launch(Dispatchers.IO) {
            lock.withLock { refreshProgressLocked(user, key, force) }
            persist()
        }
    }

    private fun refreshProgressLocked(user: String, key: String, force: Boolean) {
        if (!force && now() - lastProgress < 120_000) return
        val all = ArrayList<Ra.Completion>()
        var offset = 0
        while (true) {
            val body = get(Ra.completionUrl(user, key, offset)) ?: return
            val (page, total) = Ra.parseCompletion(body)
            all += page
            offset += page.size
            if (page.isEmpty() || offset >= total || offset >= 5000) break
        }
        lastProgress = now()
        _state.update { it.copy(progress = all.associateBy { c -> c.gameId }, status = RaStatus.OK) }
    }

    /** Полный список достижений игры сайта (при открытии; свежий список не перезагружается). */
    fun loadDetail(raGameId: Int, maxAgeMs: Long = 60_000) {
        val (user, key) = account() ?: return
        val cur = _state.value.details[raGameId]
        if (cur != null && now() - cur.fetched < maxAgeMs) return
        if (raGameId in _state.value.loading) return
        _state.update { it.copy(loading = it.loading + raGameId) }
        scope.launch(Dispatchers.IO) {
            try {
                val p = get(Ra.gameProgressUrl(user, key, raGameId))?.let { Ra.parseGameProgress(it, now()) }
                if (p != null) _state.update { s ->
                    // Заодно обновим общий прогресс этой игры
                    val c = Ra.Completion(raGameId, p.total, p.earned, p.earnedHardcore)
                    s.copy(details = s.details + (raGameId to p), progress = s.progress + (raGameId to c), status = RaStatus.OK)
                }
            } finally {
                _state.update { it.copy(loading = it.loading - raGameId) }
            }
        }
    }

    /** Забыть, что игра не нашлась (например, после исправления названия). */
    fun forget(gameId: String) {
        saved = saved.copy(matches = saved.matches - gameId, missing = saved.missing - gameId)
        publishMatches()
        persist()
    }

    /** Аккаунт удалён. */
    fun signOut() = resetFor(null)
}
