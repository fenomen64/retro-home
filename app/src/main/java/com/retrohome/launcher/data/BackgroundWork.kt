package com.retrohome.launcher.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Служба на время игры, когда Retro Home открыт как обычное приложение (не главный экран).
 * Иначе Android «замораживает» свёрнутое приложение, и проверки RetroAchievements (и обновлений)
 * во время игры не идут. Как главный экран лаунчер и так не замораживается — там служба не нужна.
 * В шторке — тихий значок «Retro Home следит за RetroAchievements».
 */
class RaWatchService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty()
        val head = intent?.getStringExtra(EXTRA_HEAD) ?: "Retro Home"
        val nm = getSystemService(NotificationManager::class.java)
        // Самая низкая важность: без звука и всплывания, только значок
        nm?.createNotificationChannel(NotificationChannel(CHANNEL, intent?.getStringExtra(EXTRA_CHANNEL) ?: head, NotificationManager.IMPORTANCE_MIN))
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(com.retrohome.launcher.R.drawable.ic_launcher_monochrome)
            .setContentTitle(head)
            .setContentText(title)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, n)
        }.onFailure {
            DiagLog.add("служба слежения не запустилась: ${it.javaClass.simpleName}")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        const val CHANNEL = "ra_watch_service"
        private const val ID = 4203
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_HEAD = "head"
        private const val EXTRA_CHANNEL = "channel"

        /** Запустить на время игры (вызывается, пока лаунчер на экране, — так Android разрешает). */
        fun start(context: Context, head: String, title: String, channel: String) {
            val i = Intent(context, RaWatchService::class.java)
                .putExtra(EXTRA_HEAD, head).putExtra(EXTRA_TITLE, title).putExtra(EXTRA_CHANNEL, channel)
            runCatching { context.startForegroundService(i) }
                .onFailure { DiagLog.add("служба слежения: запуск запрещён — ${it.javaClass.simpleName}") }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, RaWatchService::class.java)) }
        }
    }
}

/**
 * Проверка обновлений по расписанию Android — раз в 15 минут (чаще система не разрешает), даже если
 * Retro Home закрыт или открыт как обычное приложение. Пока лаунчер работает, он проверяет и сам — раз в пару минут.
 */
class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val c = (application as? com.retrohome.launcher.LauncherApp)?.controller ?: return false
        return c.backgroundUpdateCheck { jobFinished(params, false) }
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val JOB_ID = 4204
        const val PERIOD_MS = 15 * 60_000L

        /** Включить или выключить расписание (вслед за настройкой «Проверять автоматически»). */
        fun schedule(context: Context, enabled: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                js.cancel(JOB_ID)
                return
            }
            // Уже стоит — не пересоздаём, иначе отсчёт начинался бы заново при каждом запуске
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, UpdateJobService::class.java))
                .setPeriodic(PERIOD_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }.onFailure { DiagLog.add("расписание обновлений: ${it.javaClass.simpleName}") }
        }
    }
}

/**
 * Проверка сервера RetroAchievements по расписанию Android — раз в 15 минут (чаще система не разрешает),
 * даже если Retro Home закрыт или заморожен. Пока лаунчер работает, он проверяет и сам — раз в 5 минут.
 */
class RaJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        val c = (application as? com.retrohome.launcher.LauncherApp)?.controller ?: return false
        return c.backgroundRaCheck { jobFinished(params, false) }
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val JOB_ID = 4205
        const val PERIOD_MS = 15 * 60_000L

        /** Включить или выключить расписание (вслед за настройкой слежения за RetroAchievements). */
        fun schedule(context: Context, enabled: Boolean) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (!enabled) {
                js.cancel(JOB_ID)
                return
            }
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, RaJobService::class.java))
                .setPeriodic(PERIOD_MS)
                // Без интернета проверять нечего — Android запустит, когда сеть появится
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setPersisted(true)
                .build()
            runCatching { js.schedule(job) }.onFailure { DiagLog.add("расписание RetroAchievements: ${it.javaClass.simpleName}") }
        }
    }
}
