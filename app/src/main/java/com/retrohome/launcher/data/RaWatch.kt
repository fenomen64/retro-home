package com.retrohome.launcher.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import java.net.HttpURLConnection
import java.net.URL

/**
 * Проверка RetroAchievements перед игрой. Если сервер достижений не отвечает, эмулятор не сможет
 * отправить полученные достижения, и они не засчитаются. Лаунчер проверяет сервер в момент запуска
 * игры с достижениями и, если он недоступен, присылает уведомление (игра при этом запускается).
 */
object RaWatch {
    /**
     * Тот же адрес, на который эмуляторы (rcheevos) отправляют достижения. Запрос без аккаунта:
     * сервер просто сообщает последнюю версию библиотеки интеграции.
     */
    const val PROBE_URL = "https://retroachievements.org/dorequest.php?r=latestintegration"

    const val CHANNEL = "ra_watch"
    private const val NOTIFICATION_ID = 4201

    /** Консоли, для которых на RetroAchievements есть достижения. */
    val systems: Set<GameSystem> = setOf(
        GameSystem.NES, GameSystem.SNES, GameSystem.N64, GameSystem.GB, GameSystem.GBC, GameSystem.GBA,
        GameSystem.NDS, GameSystem.GC, GameSystem.WII, GameSystem.PS1, GameSystem.PS2, GameSystem.PSP,
        GameSystem.MD, GameSystem.SMS, GameSystem.GG, GameSystem.SATURN, GameSystem.DC,
        GameSystem.ARCADE, GameSystem.PCE,
    )

    /**
     * Нужна ли проверка для игры. Если лаунчер уже сверился с сайтом (указан аккаунт), знает точно:
     * matchKnown — игра сверялась, raGameId — её номер на сайте (null — достижений нет).
     * Иначе — по консоли.
     */
    fun relevant(system: GameSystem, matchKnown: Boolean, raGameId: Int?): Boolean =
        if (matchKnown) raGameId != null else system in systems

    /**
     * Ответ сервера → работает ли он. Работающий сервер всегда отвечает своим JSON с полем «Success»
     * (true или false — неважно: даже «неизвестный запрос» значит, что сервер на месте и примет достижения).
     * Ошибка 5xx, страница техработ, чужой ответ или тишина — не работает.
     */
    fun isUp(code: Int, body: String?): Boolean = code in 200..499 && body != null && body.contains("\"Success\"")

    enum class Result { UP, DOWN, OFFLINE }

    /** О чём сообщить пользователю. */
    enum class Event { DOWN, OFFLINE, RESTORED }

    /**
     * Сообщать ли о новом результате проверки. Без спама: о недоступности — один раз (и ещё раз, только
     * если сменилась причина: пропал интернет ↔ сбой сервера); о доступности — только когда связь вернулась
     * после сбоя. Сервер работает с самого начала — молчим.
     */
    fun transition(prev: Result?, now: Result): Event? = when (now) {
        Result.UP -> if (prev == Result.DOWN || prev == Result.OFFLINE) Event.RESTORED else null
        Result.DOWN -> if (prev != Result.DOWN) Event.DOWN else null
        Result.OFFLINE -> if (prev != Result.OFFLINE) Event.OFFLINE else null
    }

    /** Сайты, по которым проверяем, есть ли интернет вообще (отвечают мгновенно и без содержимого). */
    private val internetProbes = listOf(
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://www.cloudflare.com/cdn-cgi/trace",
    )

    /**
     * Сетевой запрос (вызывать не из главного потока). Сервер не ответил — отличаем «нет интернета»
     * от «сбой на стороне RetroAchievements» по тому, открываются ли другие сайты.
     */
    fun probe(context: Context, version: String): Result {
        if (!online(context)) return Result.OFFLINE
        val up = try {
            val c = (URL(PROBE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", "RetroHome/$version (Android launcher)")
            }
            val code = c.responseCode
            val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(4000) } }.getOrNull()
            c.disconnect()
            isUp(code, body)
        } catch (_: Exception) {
            false
        }
        if (up) return Result.UP
        return if (internetProbes.any { reachable(it) }) Result.DOWN else Result.OFFLINE
    }

    private fun reachable(url: String): Boolean = try {
        val c = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 4_000; readTimeout = 4_000 }
        val code = c.responseCode
        c.disconnect()
        code in 200..399
    } catch (_: Exception) {
        false
    }

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Можно ли показывать уведомления (на Android 13+ нужно разрешение). */
    fun canNotify(context: Context): Boolean = Notifier.canNotify(context)

    /** Уведомление о состоянии сервера. Всегда одно и то же место: новое заменяет прежнее, не копятся. */
    fun notify(context: Context, channelName: String, title: String, text: String): Boolean =
        Notifier.post(context, NOTIFICATION_ID, CHANNEL, channelName, NotificationManager.IMPORTANCE_HIGH, title, text)
}

/**
 * Слежение за сервером RetroAchievements, пока включена настройка: раз в 5 минут, пока лаунчер работает,
 * и по расписанию Android (раз в 15 минут), если лаунчер закрыт или заморожен. При запуске игры с
 * достижениями — проверка сразу. Уведомления только при смене состояния (см. [RaWatch.transition]);
 * последнее состояние хранится, поэтому после перезапуска лаунчера уведомления не повторяются.
 */
class RaWatcher(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val version: () -> String,
    /** Сообщить пользователю. title — идущая игра или null (игры нет). */
    private val report: (RaWatch.Event, String?) -> Unit,
    /** Игра с достижениями началась (true) или закончилась (false) — в режиме приложения держим службу. */
    private val onGame: (Boolean, String) -> Unit = { _, _ -> },
    private val store: Store = PrefsStore(context),
    private val intervalMs: Long = 5 * 60_000L,
    /** Повторная проверка перед тем, как сообщить о сбое: при запуске игры — быстро, в остальное время — с запасом. */
    private val firstConfirmMs: Long = 3_000L,
    private val confirmMs: Long = 15_000L,
    /** Лаунчер должен пробыть на экране столько, чтобы считать игру законченной (мелькание при запуске эмулятора не в счёт). */
    private val graceMs: Long = 10_000L,
    private val probe: () -> RaWatch.Result = { RaWatch.probe(context, version()) },
) {
    /** Где хранится последнее известное состояние сервера. */
    interface Store {
        var last: RaWatch.Result?
        var checkedAt: Long
    }

    class PrefsStore(context: Context) : Store {
        private val prefs = context.getSharedPreferences("ra_watch", Context.MODE_PRIVATE)
        override var last: RaWatch.Result?
            get() = prefs.getString("last", null)?.let { runCatching { RaWatch.Result.valueOf(it) }.getOrNull() }
            set(v) { prefs.edit().putString("last", v?.name).apply() }
        override var checkedAt: Long
            get() = prefs.getLong("checkedAt", 0L)
            set(v) { prefs.edit().putLong("checkedAt", v).apply() }
    }

    private val lock = kotlinx.coroutines.sync.Mutex()
    private var loop: kotlinx.coroutines.Job? = null
    private var pendingEnd: kotlinx.coroutines.Job? = null
    private val wake = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)

    /** Идущая игра с достижениями (null — игры нет). */
    var game: String? = null
        private set
    /** Только что запустили игру: о недоступности напомнить, даже если о ней уже сообщали. */
    private var gameJustStarted = false

    val enabled: Boolean get() = loop?.isActive == true
    val last: RaWatch.Result? get() = store.last

    /** Настройку включили (или лаунчер запустился с ней) — проверяем сейчас и дальше раз в 5 минут. */
    fun enable() {
        if (enabled) return
        DiagLog.add("RetroAchievements: слежу за сервером (раз в ${intervalMs / 60_000} мин)")
        loop = scope.launch {
            while (isActive) {
                checkNow()
                kotlinx.coroutines.withTimeoutOrNull(intervalMs) { wake.receive() }
            }
        }
    }

    /** Настройку выключили. */
    fun disable() {
        endGame("проверку выключили")
        loop?.cancel()
        loop = null
    }

    /** Запущена игра с достижениями — проверить сразу. */
    fun gameStarted(title: String) {
        pendingEnd?.cancel()
        game = title
        gameJustStarted = true
        DiagLog.add("RetroAchievements: запущена «$title» — проверяю сразу")
        onGame(true, title)
        if (enabled) wake.trySend(Unit) else enable()
    }

    /** Запущена игра без достижений или что-то другое — прежняя игра закончена. */
    fun endGame(reason: String) {
        pendingEnd?.cancel()
        pendingEnd = null
        val g = game ?: return
        game = null
        gameJustStarted = false
        DiagLog.add("RetroAchievements: игра «$g» закончена — $reason (слежение за сервером продолжается)")
        onGame(false, g)
    }

    /** Лаунчер снова на экране. Если он там задержится — игра закончена. */
    fun launcherResumed() {
        if (game == null) return
        pendingEnd?.cancel()
        pendingEnd = scope.launch {
            delay(graceMs)
            endGame("вернулись в лаунчер")
        }
    }

    /** Лаунчер ушёл с экрана (снова игра) — игра продолжается. */
    fun launcherPaused() {
        pendingEnd?.cancel()
        pendingEnd = null
    }

    /**
     * Одна проверка (из цикла или по расписанию Android). skipIfRecentMs — не проверять,
     * если только что проверяли (расписание срабатывает и тогда, когда цикл ещё жив).
     */
    suspend fun checkNow(skipIfRecentMs: Long = 0) = lock.withLock {
        if (skipIfRecentMs > 0 && System.currentTimeMillis() - store.checkedAt in 0 until skipIfRecentMs) return@withLock
        val started = gameJustStarted
        val r = check(if (started) firstConfirmMs else confirmMs)
        store.checkedAt = System.currentTimeMillis()
        val title = game
        DiagLog.add("RetroAchievements${title?.let { " («$it»)" } ?: ""}: $r")
        // Вне игры пропавший интернет — не повод тревожить и ничего не говорит о сервере
        if (title == null && r == RaWatch.Result.OFFLINE) return@withLock
        val prev = store.last
        val event = if (started && r != RaWatch.Result.UP) RaWatch.transition(null, r) else RaWatch.transition(prev, r)
        if (started) gameJustStarted = false
        event?.let { report(it, title) }
        store.last = r
    }

    /** Сбой подтверждаем повторной проверкой — короткий обрыв не повод тревожить. */
    private suspend fun check(confirm: Long): RaWatch.Result {
        val first = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probe() }
        if (first == RaWatch.Result.UP) return first
        delay(confirm)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probe() }
    }
}
