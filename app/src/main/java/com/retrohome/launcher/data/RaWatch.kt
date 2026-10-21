package com.retrohome.launcher.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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

    /** Сетевой запрос (вызывать не из главного потока). */
    fun probe(context: Context, version: String): Result {
        if (!online(context)) return Result.OFFLINE
        return try {
            val c = (URL(PROBE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 5_000
                readTimeout = 6_000
                setRequestProperty("User-Agent", "RetroHome/$version (Android launcher)")
            }
            val code = c.responseCode
            val body = runCatching { (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(4000) } }.getOrNull()
            c.disconnect()
            if (isUp(code, body)) Result.UP else Result.DOWN
        } catch (_: Exception) {
            Result.DOWN
        }
    }

    private fun online(context: Context): Boolean {
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Можно ли показывать уведомления (на Android 13+ нужно разрешение). */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    /** Уведомление «сервер недоступен». false — показать нельзя (нет разрешения). */
    fun notify(context: Context, channelName: String, title: String, text: String): Boolean {
        if (!canNotify(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        // Важность высокая — всплывает поверх эмулятора, пока игра ещё не началась
        nm.createNotificationChannel(NotificationChannel(CHANNEL, channelName, NotificationManager.IMPORTANCE_HIGH))
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(com.retrohome.launcher.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .build()
        nm.notify(NOTIFICATION_ID, n)
        return true
    }
}
