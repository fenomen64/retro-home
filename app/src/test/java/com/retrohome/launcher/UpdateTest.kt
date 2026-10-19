package com.retrohome.launcher

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
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

    private fun rel(v: String, notes: String, draft: Boolean = false, pre: Boolean = false) = """
        {"tag_name":"v$v","draft":$draft,"prerelease":$pre,"html_url":"https://github.com/fenomen64/retro-home/releases/tag/v$v",
         "body":"$notes","assets":[{"name":"RetroHome-$v.apk","size":100,
         "browser_download_url":"https://github.com/fenomen64/retro-home/releases/download/v$v/RetroHome-$v.apk"}]}
    """.trimIndent()

    // Список выпусков (GitHub отдаёт от новых к старым)
    private val list = "[" + listOf(
        rel("0.42", "- Бета", pre = true),
        rel("0.41", "- **Сорок один:** раз.\n- Два."),
        rel("0.40", "- Сорок."),
        rel("0.39", "- Тридцать девять."),
        rel("0.38", "- Тридцать восемь."),
    ).joinToString(",") + "]"

    @Test
    fun collectsAllVersionsSinceInstalled() {
        val r = UpdateCheck.parseList(list, current = "0.39")!!
        assertEquals("0.41", r.version)
        // Все версии новее установленной, от старой к новой; бета не попадает
        assertEquals(listOf("0.40", "0.41"), r.changes.map { it.first })
        assertEquals("- Сорок.", r.changes.first().second)
        // Уже на новейшей — обновляться не на что
        assertEquals(emptyList<String>(), UpdateCheck.parseList(list, current = "0.41")!!.history.map { it.first })
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

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun closingSettingsReturnsToStartMenu() {
        val pads = com.retrohome.launcher.ui.PadRegistry()
        val state = com.retrohome.launcher.ui.console.ConsoleState()
        val actions = RecordingConsoleActions()
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides RU, com.retrohome.launcher.ui.LocalPadRegistry provides pads) {
                com.retrohome.launcher.ui.console.ConsoleHome(com.retrohome.launcher.ui.console.buildConsoleTabs(emptyList(), com.retrohome.launcher.data.LauncherData(), RU, com.retrohome.launcher.ui.console.GamesInfo(hasFolder = true)), state, ModeOverride.AUTO, false, actions)
            }
        }
        val settingsAt = startMenu(RU, ModeOverride.AUTO, actions).indexOfFirst { it.title == RU.settings }
        compose.runOnIdle { pads.dispatch(com.retrohome.launcher.ui.Pad.START) }
        compose.runOnIdle { repeat(settingsAt) { pads.dispatch(com.retrohome.launcher.ui.Pad.DOWN) } }
        compose.runOnIdle { pads.dispatch(com.retrohome.launcher.ui.Pad.CONFIRM) }
        compose.runOnIdle {
            assertEquals("settings", actions.log.last())
            assertNull(state.overlay)
        }
        // Настройки закрыли — снова меню START, курсор на «Настройках»
        compose.runOnIdle { state.screenClosed() }
        compose.runOnIdle {
            assertEquals(com.retrohome.launcher.ui.console.ConsoleOverlay.Start, state.overlay)
            assertEquals(settingsAt, state.menuIndex)
        }
        // Пункт, который не открывает экран лаунчера (например, «Обновить данные»), — меню не возвращается
        val refreshAt = startMenu(RU, ModeOverride.AUTO, actions).indexOfFirst { it.title == RU.updateMeta }
        compose.runOnIdle { state.menuIndex = refreshAt; pads.dispatch(com.retrohome.launcher.ui.Pad.CONFIRM) }
        compose.runOnIdle { state.screenClosed(); assertNull(state.overlay) }
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

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun updateScreenWorksWithButtonsAndListsEveryVersion() {
        val r = UpdateCheck.parseList(list, current = "0.39")!!
        val installed = mutableListOf<String>()
        show {
            // Как на приставке: управление кнопками
            val im = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.LaunchedEffect(Unit) { im.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
                // Под экраном — главный экран со своими кнопками (в приложении он прикрыт и курсор не получает)
                androidx.compose.foundation.layout.Box(
                    androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomCenter).testTag("under").focusable()
                ) { androidx.compose.material3.Text("под низом") }
                androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    UpdateScreen(UpdateState.Available(r), "0.39", true, onBack = {}, onCheck = {},
                        onInstall = { installed += it.version }, onPermission = {}, onOpenPage = {}, onToggleAuto = {})
                }
            }
        }
        compose.waitForIdle()
        // Курсор сам встал на первую строку — «Скачать и установить»
        compose.onNodeWithText("Скачать и установить 0.41 · 0.0 МБ").assertIsFocused()
        compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionCenter) }
        compose.runOnIdle { assertEquals(listOf("0.41"), installed) }
        // Крестовина листает вниз, до списка изменений, и не уходит на экран под низом
        repeat(12) { compose.onRoot().performKeyInput { pressKey(androidx.compose.ui.input.key.Key.DirectionDown) }; compose.waitForIdle() }
        compose.onNodeWithTag("under").assertIsNotFocused()
        compose.onNodeWithText("• Два.").assertIsFocused()
        // Описания всех версий новее установленной, от старой к новой
        compose.onNodeWithTag("notes:0.40", useUnmergedTree = true).assertTextEquals("Версия 0.40")
        compose.onNodeWithTag("notes:0.41", useUnmergedTree = true).assertTextEquals("Версия 0.41")
        compose.onAllNodesWithText("Версия 0.39").assertCountEquals(0)
        compose.onRoot().captureRoboImage("build/screenshots/62_update_versions.png")
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
