package com.retrohome.launcher.data

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Уведомления лаунчера: состояние RetroAchievements и новые версии. */
object Notifier {
    /** Можно ли показывать уведомления (на Android 13+ нужно разрешение). */
    fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return context.getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() == true
    }

    /**
     * Показать (или заменить — тот же id) уведомление. false — показать нельзя (нет разрешения).
     * IMPORTANCE_HIGH всплывает поверх эмулятора.
     */
    fun post(
        context: Context, id: Int, channel: String, channelName: String, importance: Int,
        title: String, text: String, tap: PendingIntent? = null,
    ): Boolean {
        if (!canNotify(context)) return false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        nm.createNotificationChannel(NotificationChannel(channel, channelName, importance))
        val n = Notification.Builder(context, channel)
            .setSmallIcon(com.retrohome.launcher.R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setCategory(Notification.CATEGORY_STATUS)
            .apply { if (tap != null) setContentIntent(tap) }
            .build()
        nm.notify(id, n)
        return true
    }
}
