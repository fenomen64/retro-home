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
