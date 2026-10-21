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
 * Слежение за RetroAchievements во время игры: проверка при запуске и дальше примерно раз в 5 минут,
 * пока игра идёт (до возвращения в лаунчер). Уведомления — только при смене состояния (см. [RaWatch.transition]).
 */
class RaMonitor(
    private val context: Context,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val version: () -> String,
    /** Сообщить пользователю (уведомление или, без разрешения, надпись внизу экрана). */
    private val report: (RaWatch.Event, String) -> Unit,
    /** Слежение началось (true) или закончилось (false) — в режиме приложения держим службу. */
    private val onRunning: (Boolean, String) -> Unit = { _, _ -> },
    private val intervalMs: Long = 5 * 60_000L,
    /** Повторная проверка перед тем, как сообщить о сбое: при запуске игры — быстро, во время игры — с запасом. */
    private val firstConfirmMs: Long = 3_000L,
    private val confirmMs: Long = 15_000L,
    /** Лаунчер должен пробыть на экране столько, чтобы считать игру законченной (мелькание при запуске эмулятора не в счёт). */
    private val graceMs: Long = 10_000L,
    private val probe: () -> RaWatch.Result = { RaWatch.probe(context, version()) },
) {
    private var job: kotlinx.coroutines.Job? = null
    private var pendingStop: kotlinx.coroutines.Job? = null
    private var title: String = ""

    /** Последнее известное состояние сервера в этой игре (null — ещё не проверяли). */
    var last: RaWatch.Result? = null
        private set

    val running: Boolean get() = job?.isActive == true

    /** Запущена игра с достижениями — начинаем следить заново. */
    fun start(title: String) {
        cancelJobs()
        this.title = title
        last = null
        DiagLog.add("RetroAchievements: слежу за «$title» (раз в ${intervalMs / 60_000} мин)")
        onRunning(true, title)
        job = scope.launch {
            val until = System.currentTimeMillis() + MAX_MS
            var first = true
            try {
                while (isActive && System.currentTimeMillis() < until) {
                    val r = check(if (first) firstConfirmMs else confirmMs)
                    first = false
                    DiagLog.add("RetroAchievements («$title»): $r")
                    RaWatch.transition(last, r)?.let { report(it, title) }
                    last = r
                    delay(intervalMs)
                }
            } finally {
                if (job == coroutineContext[kotlinx.coroutines.Job]) {
                    job = null
                    onRunning(false, title)
                }
            }
        }
    }

    /** Проверку выключили — прекращаем сразу. */
    fun stop(reason: String = "выключено") {
        if (!running) { pendingStop?.cancel(); return }
        DiagLog.add("RetroAchievements: слежение за «$title» остановлено — $reason")
        cancelJobs()
        onRunning(false, title)
    }

    /** Лаунчер снова на экране. Если он там задержится — игра закончена, перестаём следить. */
    fun launcherResumed() {
        if (!running) return
        pendingStop?.cancel()
        pendingStop = scope.launch {
            delay(graceMs)
            stop("вернулись в лаунчер")
        }
    }

    /** Лаунчер ушёл с экрана (снова игра) — продолжаем следить. */
    fun launcherPaused() {
        pendingStop?.cancel()
        pendingStop = null
    }

    private fun cancelJobs() {
        pendingStop?.cancel()
        pendingStop = null
        val j = job
        job = null
        j?.cancel()
    }

    /** Сбой подтверждаем повторной проверкой — короткий обрыв не повод тревожить. */
    private suspend fun check(confirm: Long): RaWatch.Result {
        val first = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probe() }
        if (first == RaWatch.Result.UP) return first
        delay(confirm)
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { probe() }
    }

    private companion object {
        /** Страховка: дольше 8 часов подряд не следим. */
        const val MAX_MS = 8 * 60 * 60_000L
    }
}
