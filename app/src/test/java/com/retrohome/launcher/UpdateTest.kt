package com.retrohome.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.data.UpdateCheck
import com.retrohome.launcher.data.UpdateState
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.ui.UpdateScreen
import com.retrohome.launcher.ui.console.startMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UpdateTest {
    @get:Rule val compose = createComposeRule()

    // Так отвечает api.github.com/repos/fenomen64/retro-home/releases/latest (сокращено)
    private val latest = """
        {"tag_name":"v0.39","draft":false,"prerelease":false,
         "html_url":"https://github.com/fenomen64/retro-home/releases/tag/v0.39",
         "body":"- **Обновление из лаунчера:** проверка и установка.\n- Мелкие исправления.",
         "assets":[{"name":"RetroHome-0.39.apk","size":25575043,
           "content_type":"application/vnd.android.package-archive",
           "browser_download_url":"https://github.com/fenomen64/retro-home/releases/download/v0.39/RetroHome-0.39.apk"}]}
    """.trimIndent()

    @Test
    fun parsesGithubRelease() {
        val r = UpdateCheck.parse(latest)!!
        assertEquals("0.39", r.version)
        assertEquals(25575043L, r.size)
        assertTrue(r.apkUrl.endsWith("/v0.39/RetroHome-0.39.apk"))
        assertTrue(r.page.endsWith("/tag/v0.39"))
        // Без APK, черновик или пре-релиз — не предлагаем
        assertNull(UpdateCheck.parse("""{"tag_name":"v0.40","assets":[{"name":"notes.txt"}]}"""))
        assertNull(UpdateCheck.parse(latest.replace("\"prerelease\":false", "\"prerelease\":true")))
    }

    @Test
    fun comparesVersionsAsNumbers() {
        assertTrue(UpdateCheck.isNewer("0.39", "0.38"))
        assertTrue(UpdateCheck.isNewer("0.40", "0.9"))
        assertTrue(UpdateCheck.isNewer("v1.0", "0.99"))
        assertTrue(UpdateCheck.isNewer("0.38.1", "0.38"))
        assertFalse(UpdateCheck.isNewer("0.38", "0.38"))
        assertFalse(UpdateCheck.isNewer("0.37", "0.38"))
        assertFalse(UpdateCheck.isNewer("0.38", "0.38.0"))
    }

    @Test
    fun checksAboutHourly() {
        val minutes = UpdateCheck.INTERVAL_MS / 60_000
        assertTrue("интервал $minutes мин", minutes in 50..60)
    }

    @Test
    fun notesWithoutMarkdown() {
        val n = UpdateCheck.plainNotes(UpdateCheck.parse(latest)!!.notes)
        assertEquals("• Обновление из лаунчера: проверка и установка.\n• Мелкие исправления.", n)
    }

    @Test
    fun startMenuOffersUpdateFirst() {
        val a = RecordingConsoleActions()
        val items = startMenu(RU, ModeOverride.AUTO, a, update = "0.39")
        assertEquals("Обновить до 0.39", items.first().title)
        // Без новой версии пункта нет
        assertTrue(startMenu(RU, ModeOverride.AUTO, a).none { it.title.startsWith("Обновить до") })
    }

    private fun show(content: @Composable () -> Unit) = compose.setContent {
        CompositionLocalProvider(LocalStrings provides RU) { content() }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun updateScreenOffersInstall() {
        val r = UpdateCheck.parse(latest)!!
        val installed = mutableListOf<String>()
        show {
            UpdateScreen(UpdateState.Available(r), "0.38", true, onBack = {}, onCheck = {},
                onInstall = { installed += it.version }, onPermission = {}, onOpenPage = {}, onToggleAuto = {})
        }
        compose.onNodeWithTag("updateStatus").assertTextEquals("Доступна версия 0.39")
        compose.onRoot().captureRoboImage("build/screenshots/60_update_available.png")
        compose.onNodeWithText("Скачать и установить 0.39 · 24.4 МБ").performClick()
        compose.runOnIdle { assertEquals(listOf("0.39"), installed) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun updateScreenStates() {
        val r = UpdateCheck.parse(latest)!!
        val state = androidx.compose.runtime.mutableStateOf<UpdateState>(UpdateState.Idle)
        var checks = 0
        show {
            UpdateScreen(state.value, "0.38", true, onBack = {}, onCheck = { checks++ },
                onInstall = {}, onPermission = {}, onOpenPage = {}, onToggleAuto = {})
        }
        // Открыли экран без проверки — проверка запускается сама
        compose.runOnIdle { assertEquals(1, checks) }
        compose.runOnIdle { state.value = UpdateState.UpToDate("0.38") }
        compose.onNodeWithTag("updateStatus").assertTextEquals("Версия 0.38 — самая новая")
        compose.runOnIdle { state.value = UpdateState.Downloading(r, 0.45f) }
        compose.onNodeWithText("Скачивание… 45%").assertExists()
        compose.onRoot().captureRoboImage("build/screenshots/61_update_downloading.png")
        compose.runOnIdle { state.value = UpdateState.Failed(UpdateState.Reason.SIGNATURE, r, "подпись не совпадает") }
        compose.onNodeWithTag("updateStatus").assertTextEquals("Файл не прошёл проверку (подпись не совпадает) — установка отменена")
        compose.runOnIdle { state.value = UpdateState.NeedPermission(r) }
        compose.onNodeWithText("Разрешите установку обновлений").assertExists()
    }
}
