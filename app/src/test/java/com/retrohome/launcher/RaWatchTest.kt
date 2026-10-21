package com.retrohome.launcher

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollAction
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.data.RaWatch
import com.retrohome.launcher.data.RaWatch.Result as R
import com.retrohome.launcher.data.RaWatch.Event as E
import com.retrohome.launcher.data.Settings
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.ui.SettingsActions
import com.retrohome.launcher.ui.SettingsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Проверка RetroAchievements перед игрой. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class RaWatchTest {
    @get:Rule val compose = createComposeRule()

    @Test fun serverUpOrDown() {
        // Работающий сервер отвечает своим JSON — неважно, успехом или «неизвестным запросом»
        assertTrue(RaWatch.isUp(200, """{"Success":true,"MinimumVersion":"1.0"}"""))
        assertTrue(RaWatch.isUp(200, """{"Success":false,"Error":"Unknown request"}"""))
        assertTrue(RaWatch.isUp(401, """{"Success":false,"Error":"Credentials invalid"}"""))
        // Как сейчас при сбое: Cloudflare отдаёт 502, страницы техработ, пустота
        assertFalse(RaWatch.isUp(502, "error code: 502"))
        assertFalse(RaWatch.isUp(503, "<html>Maintenance</html>"))
        assertFalse(RaWatch.isUp(200, "<html>We'll be right back</html>"))
        assertFalse(RaWatch.isUp(200, null))
    }

    @Test fun onlyGamesWithAchievementsAreChecked() {
        // Лаунчер уже сверился с сайтом: проверяем только игры, которые там есть
        assertTrue(RaWatch.relevant(GameSystem.PS2, matchKnown = true, raGameId = 2763))
        assertFalse(RaWatch.relevant(GameSystem.PS2, matchKnown = true, raGameId = null))
        // Аккаунт не указан — по консоли: у Switch и Vita достижений нет
        assertTrue(RaWatch.relevant(GameSystem.GBA, matchKnown = false, raGameId = null))
        assertFalse(RaWatch.relevant(GameSystem.SWITCH, matchKnown = false, raGameId = null))
        assertFalse(RaWatch.relevant(GameSystem.VITA, matchKnown = false, raGameId = null))
    }

    @Test fun notifiesOnlyWhenStateChanges() {
        // Каждые 5 минут: работает, работает, сбой, сбой, пропал интернет, нет интернета, вернулось, работает
        val seq = listOf(R.UP, R.UP, R.DOWN, R.DOWN, R.OFFLINE, R.OFFLINE, R.UP, R.UP)
        var prev: RaWatch.Result? = null
        val events = seq.map { r -> RaWatch.transition(prev, r).also { prev = r } }
        assertEquals(listOf(null, null, E.DOWN, null, E.OFFLINE, null, E.RESTORED, null), events)
        // Сервер недоступен уже при запуске — сообщаем сразу; работает при запуске — молчим
        assertEquals(E.DOWN, RaWatch.transition(null, R.DOWN))
        assertEquals(E.OFFLINE, RaWatch.transition(null, R.OFFLINE))
        assertEquals(null, RaWatch.transition(null, R.UP))
    }

    @Test fun updateScheduleFollowsSetting() {
        val app = RuntimeEnvironment.getApplication()
        val js = app.getSystemService(android.app.job.JobScheduler::class.java)
        com.retrohome.launcher.data.UpdateJobService.schedule(app, true)
        val job = js.getPendingJob(com.retrohome.launcher.data.UpdateJobService.JOB_ID)!!
        assertEquals(15 * 60_000L, job.intervalMillis)
        assertTrue(job.isPersisted)
        com.retrohome.launcher.data.UpdateJobService.schedule(app, false)
        assertEquals(null, js.getPendingJob(com.retrohome.launcher.data.UpdateJobService.JOB_ID))
    }

    @Test fun updateTextMatchesRealInterval() {
        assertFalse(RU.ext.upd.autoDesc.contains("час"))
        assertTrue(RU.ext.upd.autoDesc.contains("пару минут"))
    }

    private class MemStore : com.retrohome.launcher.data.RaWatcher.Store {
        override var last: RaWatch.Result? = null
        override var checkedAt: Long = 0
    }

    private fun watcher(
        answers: ArrayDeque<RaWatch.Result>, events: MutableList<Pair<RaWatch.Event, String?>>,
        store: MemStore = MemStore(), games: MutableList<Boolean> = mutableListOf(), intervalMs: Long = 20, graceMs: Long = 200,
    ) = com.retrohome.launcher.data.RaWatcher(
        RuntimeEnvironment.getApplication(), kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        version = { "test" }, report = { e, t -> events += e to t }, onGame = { on, _ -> games += on },
        store = store, intervalMs = intervalMs, firstConfirmMs = 5, confirmMs = 5, graceMs = graceMs,
        probe = { synchronized(answers) { answers.removeFirstOrNull() ?: R.UP } },
    )

    private fun waitDrained(answers: ArrayDeque<RaWatch.Result>) = kotlinx.coroutines.runBlocking {
        kotlinx.coroutines.withTimeout(10_000) { while (synchronized(answers) { answers.isNotEmpty() }) kotlinx.coroutines.delay(10) }
        kotlinx.coroutines.delay(150)
    }

    @Test fun watchesAlwaysAndNotifiesOnlyOnChanges() {
        // Игры нет — лаунчер просто следит за сервером в фоне
        val answers = ArrayDeque(listOf(
            R.UP,               // работает — молчим
            R.DOWN, R.UP,       // короткий обрыв — молчим
            R.DOWN, R.DOWN,     // сбой — одно уведомление
            R.DOWN, R.DOWN,     // всё ещё сбой — молчим
            R.OFFLINE, R.OFFLINE, // вне игры пропавший интернет — не повод тревожить
            R.DOWN, R.DOWN,     // сбой продолжается — молчим
            R.UP,               // заработал — уведомление
            R.UP, R.UP,         // работает — молчим
        ))
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<RaWatch.Event, String?>>())
        val w = watcher(answers, events)
        w.enable()
        waitDrained(answers)
        w.disable()
        assertEquals(listOf(E.DOWN to null, E.RESTORED to null), events.toList())
    }

    @Test fun duringGameAlsoReportsMissingInternet() {
        val answers = ArrayDeque(listOf(R.UP, R.DOWN, R.DOWN, R.OFFLINE, R.OFFLINE, R.UP))
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<RaWatch.Event, String?>>())
        val w = watcher(answers, events)
        w.gameStarted("Skies of Arcadia")
        waitDrained(answers)
        w.disable()
        assertEquals(
            listOf(E.DOWN to "Skies of Arcadia", E.OFFLINE to "Skies of Arcadia", E.RESTORED to "Skies of Arcadia"),
            events.toList(),
        )
    }

    @Test fun rememberedStateIsNotRepeatedButGameStartReminds() {
        val store = MemStore().apply { last = R.DOWN } // о сбое уже сообщили (лаунчер перезапускался)
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<RaWatch.Event, String?>>())
        val answers = ArrayDeque(listOf(R.DOWN, R.DOWN))
        val w = watcher(answers, events, store, intervalMs = 60_000)
        w.enable()
        waitDrained(answers)
        assertEquals(emptyList<Pair<RaWatch.Event, String?>>(), events.toList()) // не повторяем
        // Но запуск игры при неработающем сервере — напоминание
        synchronized(answers) { answers.addAll(listOf(R.DOWN, R.DOWN)) }
        w.gameStarted("Skies of Arcadia")
        waitDrained(answers)
        w.disable()
        assertEquals(listOf(E.DOWN to "Skies of Arcadia"), events.toList())
    }

    @Test fun gameEndsWhenLauncherStaysButWatchingContinues() {
        val games = java.util.Collections.synchronizedList(mutableListOf<Boolean>())
        val events = java.util.Collections.synchronizedList(mutableListOf<Pair<RaWatch.Event, String?>>())
        val w = watcher(ArrayDeque(), events, games = games, intervalMs = 60_000)
        w.gameStarted("Skies of Arcadia")
        // Лаунчер мелькнул при запуске эмулятора — игра продолжается
        w.launcherResumed(); Thread.sleep(50); w.launcherPaused(); Thread.sleep(400)
        assertEquals("Skies of Arcadia", w.game)
        // Вернулись в лаунчер и остались — игра закончена, но за сервером следим дальше
        w.launcherResumed(); Thread.sleep(600)
        assertEquals(null, w.game)
        assertTrue(w.enabled)
        assertEquals(listOf(true, false), games.toList())
        w.disable()
        assertFalse(w.enabled)
    }

    @Test fun raScheduleFollowsSetting() {
        val app = RuntimeEnvironment.getApplication()
        val js = app.getSystemService(android.app.job.JobScheduler::class.java)
        com.retrohome.launcher.data.RaJobService.schedule(app, true)
        val job = js.getPendingJob(com.retrohome.launcher.data.RaJobService.JOB_ID)!!
        assertEquals(15 * 60_000L, job.intervalMillis)
        com.retrohome.launcher.data.RaJobService.schedule(app, false)
        assertEquals(null, js.getPendingJob(com.retrohome.launcher.data.RaJobService.JOB_ID))
    }

    @Test fun offByDefault() {
        assertFalse(Settings().raCheck)
    }

    @Test fun notificationNeedsPermission() {
        val app = RuntimeEnvironment.getApplication()
        val w = RU.ext.raWatch
        // Без разрешения (Android 13+) — показать нельзя, лаунчер покажет надпись внизу экрана
        assertFalse(RaWatch.notify(app, w.channel, w.downTitle, w.downText.format("Skies of Arcadia")))
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertTrue(RaWatch.notify(app, w.channel, w.downTitle, w.downText.format("Skies of Arcadia")))
        val nm = app.getSystemService(NotificationManager::class.java)
        val posted = shadowOf(nm).allNotifications
        assertEquals(1, posted.size)
        // Свой значок уведомлений во весь квадрат (а не крошечный силуэт иконки приложения)
        assertEquals(com.retrohome.launcher.R.drawable.ic_notification, posted[0].smallIcon.resId)
        assertEquals("RetroAchievements недоступен", posted[0].extras.getString("android.title"))
        assertTrue(posted[0].extras.getCharSequence("android.text").toString().contains("Skies of Arcadia"))
        // Канал с высокой важностью — всплывает поверх эмулятора
        assertEquals(NotificationManager.IMPORTANCE_HIGH, nm.getNotificationChannel(RaWatch.CHANNEL).importance)
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun settingInCoversAndAchievementsSection() {
        var toggles = 0
        val nav = com.retrohome.launcher.ui.SettingsNav().apply { section = "DATA" }
        val actions = object : SettingsActions {
            override fun toggleLang() {}
            override fun setMode(mode: ModeOverride) {}
            override fun toggleSwap() {}
            override fun toggleAutoAdd() {}
            override fun sortHome() {}
            override fun cycleColumns(dir: Int) {}
            override fun cycleRows(dir: Int) {}
            override fun openConsoleApps() {}
            override fun openHiddenApps() {}
            override fun openDefaultHome() {}
            override fun pickRoms() {}
            override fun openFolders() {}
            override fun openEmulators() {}
            override fun toggleAutoMeta() {}
            override fun cycleSfx(dir: Int) {}
            override fun cycleSfxVolume(dir: Int) {}
            override fun cycleMusic(dir: Int) {}
            override fun cycleMusicVolume(dir: Int) {}
            override fun pickMusic() {}
            override fun toggleRaCheck() { toggles++ }
        }
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides RU) {
                SettingsScreen(Settings(), "0.43", focusFirst = false, onBack = {}, a = actions, nav = nav)
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(RU.ext.raWatch.title))
        compose.onNodeWithText(RU.ext.raWatch.title).assertExists()
        compose.onNodeWithText(RU.ext.raWatch.title).performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }
}
