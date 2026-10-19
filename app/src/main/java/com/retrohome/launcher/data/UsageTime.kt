package com.retrohome.launcher.data

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process

/**
 * Точный учёт времени в игре по системной статистике Android («Доступ к истории использования»):
 * считается только время, когда эмулятор был на экране. Свернули игру — отсчёт встал,
 * вернулись любым способом (хоть через список открытых приложений) — пошёл снова.
 */
object UsageTime {
    /** Событие статистики: время, тип, приложение и экран (activity). */
    data class Ev(val time: Long, val type: Int, val pkg: String, val cls: String = "")

    // Типы событий Android (UsageEvents.Event)
    const val RESUMED = 1      // ACTIVITY_RESUMED / MOVE_TO_FOREGROUND
    const val PAUSED = 2       // ACTIVITY_PAUSED / MOVE_TO_BACKGROUND
    const val STOPPED = 23     // ACTIVITY_STOPPED
    const val SCREEN_OFF = 16  // SCREEN_NON_INTERACTIVE
    const val SHUTDOWN = 26    // DEVICE_SHUTDOWN

    /** Перерыв дольше этого — сессия считается законченной (эмулятор открыли уже для другого). */
    const val SESSION_GAP_MS = 12 * 3600_000L

    data class Result(val ms: Long, val lastEnd: Long, val expired: Boolean)

    /**
     * Сколько приложение [pkg] было на экране в промежутке [from, to].
     * lastEnd — когда его в последний раз убрали с экрана (или to, если оно ещё открыто).
     * expired — был перерыв дольше [SESSION_GAP_MS]: всё, что после него, уже не эта игра.
     */
    fun foreground(events: List<Ev>, pkg: String, from: Long, to: Long, lastActive: Long = from): Result {
        val open = HashSet<String>()
        var start = 0L
        var total = 0L
        var lastEnd = lastActive
        for (e in events.sortedBy { it.time }) {
            if (e.time < from || e.time > to) continue
            when {
                e.type == SCREEN_OFF || e.type == SHUTDOWN -> {
                    if (open.isNotEmpty()) { total += e.time - start; lastEnd = e.time; open.clear() }
                }
                e.pkg != pkg -> {}
                e.type == RESUMED -> {
                    if (open.isEmpty()) {
                        if (e.time - lastEnd > SESSION_GAP_MS) return Result(total, lastEnd, expired = true)
                        start = e.time
                    }
                    open += e.cls
                }
                e.type == PAUSED || e.type == STOPPED -> {
                    if (open.remove(e.cls) && open.isEmpty()) { total += e.time - start; lastEnd = e.time }
                }
            }
        }
        if (open.isNotEmpty()) { total += to - start; lastEnd = to }
        return Result(total, lastEnd, expired = false)
    }

    fun hasAccess(context: Context): Boolean = runCatching {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    /** События статистики за промежуток (пусто, если доступа нет). */
    fun events(context: Context, from: Long, to: Long): List<Ev> = runCatching {
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val q = usm.queryEvents(from, to)
        val out = ArrayList<Ev>()
        val e = UsageEvents.Event()
        while (q.hasNextEvent()) {
            q.getNextEvent(e)
            out += Ev(e.timeStamp, e.eventType, e.packageName ?: "", e.className ?: "")
        }
        out
    }.getOrDefault(emptyList())
}
