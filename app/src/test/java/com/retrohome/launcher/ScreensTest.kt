package com.retrohome.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.foundation.background
import org.junit.Assert.assertNotEquals
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.foundation.focusable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.swipeRight
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.click
import com.github.takahirom.roborazzi.captureRoboImage
import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.GameSystem as GS
import com.retrohome.launcher.audio.Sfx
import com.retrohome.launcher.data.ConsoleZone
import com.retrohome.launcher.data.Emulators
import com.retrohome.launcher.data.HomeItem
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.data.Recent
import com.retrohome.launcher.data.Settings
import com.retrohome.launcher.i18n.EN
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.i18n.Strings
import com.retrohome.launcher.ui.IconLoader
import com.retrohome.launcher.ui.LocalIconLoader
import com.retrohome.launcher.ui.LocalPadRegistry
import com.retrohome.launcher.ui.Pad
import com.retrohome.launcher.ui.PadRegistry
import com.retrohome.launcher.ui.SettingsActions
import com.retrohome.launcher.ui.SettingsScreen
import com.retrohome.launcher.ui.console.ConsoleActions
import com.retrohome.launcher.ui.console.ConsoleHome
import com.retrohome.launcher.ui.console.ConsoleOverlay
import com.retrohome.launcher.ui.console.ConsoleState
import com.retrohome.launcher.ui.console.buildConsoleTabs
import com.retrohome.launcher.ui.phone.PhoneActions
import com.retrohome.launcher.ui.phone.PhoneHome
import com.retrohome.launcher.ui.phone.PhoneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Цветные заглушки вместо настоящих иконок. */
private object FakeIcons : IconLoader {
    private val colors = intArrayOf(
        0xFF4F8EF7.toInt(), 0xFF2BB673.toInt(), 0xFFF5A623.toInt(), 0xFFB45CF0.toInt(),
        0xFF00B8D9.toInt(), 0xFFEF5DA8.toInt(), 0xFF7A8BA6.toInt(), 0xFFE5484D.toInt(),
    )

    override fun peek(key: String, sizePx: Int): ImageBitmap = make(key, sizePx)
    override fun load(key: String, sizePx: Int): ImageBitmap = make(key, sizePx)

    private fun make(key: String, size: Int): ImageBitmap {
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = colors[Math.floorMod(key.hashCode(), colors.size)]
        c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.28f, size * 0.28f, p)
        p.color = 0xFFFFFFFF.toInt()
        p.textSize = size * 0.45f
        p.textAlign = Paint.Align.CENTER
        val letter = key.substringAfterLast('.').substringBefore('/').take(1).uppercase()
        c.drawText(letter, size / 2f, size / 2f + p.textSize * 0.35f, p)
        return bmp.asImageBitmap()
    }
}

/** Значки достижений-заглушки: цветной кружок-медаль (без загрузки из сети). */
private object FakeBadges : com.retrohome.launcher.ui.RemoteImageLoader {
    override fun peek(url: String): ImageBitmap = make(url)
    override fun load(url: String): ImageBitmap = make(url)
    private fun make(url: String): ImageBitmap {
        val s = 64
        val bmp = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = Math.floorMod(url.hashCode() * 37, 360).toFloat()
        c.drawColor(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.6f, 0.45f)))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.HSVToColor(floatArrayOf((hue + 30) % 360, 0.5f, 0.95f)); c.drawCircle(32f, 30f, 20f, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(32f, 30f, 8f, p)
        return bmp.asImageBitmap()
    }
}

/** Обложки-заглушки: цветной «бокс-арт» с названием, чтобы не использовать чужие картинки. */
private object FakeCovers : com.retrohome.launcher.ui.CoverLoader {
    /** «Снимок экрана»: небо, холмы и пиксельные блоки, как в ретро-игре. */
    private fun snap(name: String): ImageBitmap {
        val w = 320; val h = 224
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint()
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF5AA0E6.toInt(), 0xFFF2C38B.toInt(), android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0xFF3E8E4B.toInt(); c.drawCircle(80f, 260f, 140f, p)
        p.color = 0xFF2F6E3B.toInt(); c.drawCircle(250f, 280f, 150f, p)
        p.color = 0xFFB5652B.toInt()
        for (i in 0 until 10) c.drawRect(40f + i * 24, 120f, 60f + i * 24, 140f, p)
        p.color = 0xFFFFFFFF.toInt(); c.drawCircle(260f, 50f, 22f, p)
        return bmp.asImageBitmap()
    }

    override fun peek(name: String): ImageBitmap = make(name)
    override fun load(name: String): ImageBitmap = make(name)
    private fun make(name: String): ImageBitmap {
        if (name.contains("_s.")) return snap(name)
        // Пропорции как у настоящих коробок: GBA — почти квадрат, SNES (США) — шире, чем выше
        val (w, h) = when {
            "[square]" in name -> 400 to 400
            "[wide]" in name -> 420 to 300
            else -> 300 to 420
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val hue = Math.floorMod(name.hashCode(), 360).toFloat()
        val top = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.75f))
        val bottom = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40) % 360, 0.7f, 0.25f))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.LinearGradient(0f, 0f, 0f, h.toFloat(), top, bottom, android.graphics.Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.color = 0x22FFFFFF
        c.drawCircle(w * 0.7f, h * 0.35f, w * 0.45f, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 34f; p.isFakeBoldText = true
        name.removeSuffix(".webp").replace("[square]", "").replace("[wide]", "").trim().split(' ').chunked(2).forEachIndexed { i, words ->
            c.drawText(words.joinToString(" "), 20f, h - 120f + i * 40f, p)
        }
        p.color = 0xFF000000.toInt(); c.drawRect(0f, 0f, w.toFloat(), 36f, p)
        p.color = 0xFFFFFFFF.toInt(); p.textSize = 22f; c.drawText("PlayStation 2", 16f, 26f, p)
        return bmp.asImageBitmap()
    }
}

private fun app(pkg: String, label: String, game: Boolean = false) =
    AppInfo("$pkg/Main", pkg, label, game, Emulators.match(pkg, label))

private val sampleApps = listOf(
    app("com.retroarch.aarch64", "RetroArch"),
    app("org.ppsspp.ppsspp", "PPSSPP"),
    app("xyz.aethersx2.android", "NetherSX2 Classic"),
    app("me.magnum.melonds", "melonDS"),
    app("com.github.stenzek.duckstation", "DuckStation"),
    app("org.citra.citra_emu", "Citra"),
    app("dev.eden.eden_emulator", "Eden"),
    app("app.gamenative", "GameNative"),
    app("cyou.joiplay.joiplay", "JoiPlay"),
    app("com.swordfish.lemuroid", "Lemuroid"),
    app("com.mojang.minecraftpe", "Minecraft", game = true),
    app("com.innersloth.spacemafia", "Among Us", game = true),
    app("com.android.chrome", "Chrome"),
    app("org.telegram.messenger", "Telegram"),
    app("com.google.android.youtube", "YouTube"),
    app("com.android.vending", "Play Маркет"),
    app("com.android.settings", "Настройки"),
    app("com.android.camera", "Камера"),
    app("com.google.android.apps.photos", "Фото"),
    app("com.spotify.music", "Spotify"),
    app("com.discord", "Discord"),
)

private val sampleData = LauncherData(
    recents = listOf(
        Recent("xyz.aethersx2.android/Main", 900),
        Recent("org.ppsspp.ppsspp/Main", 800),
        Recent("com.github.stenzek.duckstation/Main", 700),
        Recent("dev.eden.eden_emulator/Main", 600),
        Recent("com.mojang.minecraftpe/Main", 500),
    ),
    favorites = listOf("xyz.aethersx2.android/Main", "org.citra.citra_emu/Main", "com.swordfish.lemuroid/Main"),
    pageCount = 2,
    home = listOf(
        HomeItem(1, 0, 0, 0, app = "org.telegram.messenger/Main"),
        HomeItem(2, 0, 1, 0, app = "com.google.android.youtube/Main"),
        HomeItem(3, 0, 2, 0, app = "com.spotify.music/Main"),
        HomeItem(4, 0, 3, 0, app = "com.discord/Main"),
        HomeItem(
            5, 0, 0, 1, folderName = "Эмуляторы",
            folderApps = listOf("org.ppsspp.ppsspp/Main", "xyz.aethersx2.android/Main", "me.magnum.melonds/Main", "org.citra.citra_emu/Main"),
        ),
        HomeItem(6, HomeItem.DOCK, 0, 0, app = "com.android.chrome/Main"),
        HomeItem(7, HomeItem.DOCK, 1, 0, app = "com.android.camera/Main"),
        HomeItem(8, HomeItem.DOCK, 2, 0, app = "com.google.android.apps.photos/Main"),
        HomeItem(9, HomeItem.DOCK, 3, 0, app = "com.android.vending/Main"),
        HomeItem(10, HomeItem.DOCK, 4, 0, app = "com.android.settings/Main"),
    ),
)

internal class RecordingConsoleActions : ConsoleActions {
    val log = mutableListOf<String>()
    override fun launch(key: String) { log += "launch:$key" }
    override fun toggleFavorite(key: String) { log += "fav:$key" }
    override fun setZone(key: String, zone: ConsoleZone) { log += "zone:$key:$zone" }
    override fun appInfo(key: String) { log += "info:$key" }
    override fun uninstall(key: String) { log += "uninstall:$key" }
    override fun openSearch() { log += "search" }
    override fun openSettings() { log += "settings" }
    override fun openConsoleApps() { log += "consoleApps" }
    override fun toggleLang() { log += "lang" }
    override fun cycleMode(dir: Int) { log += "mode:$dir" }
    override fun openWifi() { log += "wifi" }
    override fun openBluetooth() { log += "bt" }
    override fun openAndroidSettings() { log += "android" }
    override fun pickRomsFolder() { log += "pick" }
    override fun rescanGames() { log += "rescan" }
    override fun runnerName(system: com.retrohome.launcher.data.GameSystem) = "NetherSX2"
    override fun cycleRunner(system: com.retrohome.launcher.data.GameSystem, dir: Int) { log += "cycle:$system:$dir" }
    override fun refreshMeta() { log += "meta" }
    override fun launchDisc(key: String, index: Int) { log += "disc:$key:$index" }
    override fun fixTitle(key: String) { log += "fix:$key" }
    override fun cycleSfx(dir: Int) { log += "sfx:$dir" }
    override fun cycleMusic(dir: Int) { log += "music:$dir" }
    override fun cycleSortGames(dir: Int) { log += "sort:$dir" }
}

/** Записывает, какие звуки прозвучали. */
private class RecordingSfx : com.retrohome.launcher.audio.SfxPlayer {
    val played = mutableListOf<com.retrohome.launcher.audio.Sfx>()
    override fun play(s: com.retrohome.launcher.audio.Sfx) { played += s }
}

private object NoPhone : PhoneActions {
    override fun launch(key: String) {}
    override fun moveItem(id: Long, page: Int, x: Int, y: Int) {}
    override fun removeItem(id: Long) {}
    override fun addToHome(key: String, page: Int) {}
    override fun hideInDrawer(key: String) {}
    override fun appInfo(key: String) {}
    override fun uninstall(key: String) {}
    override fun renameFolder(id: Long, name: String) {}
    override fun takeOutOfFolder(folderId: Long, key: String) {}
    override fun addPage() {}
    override fun deletePage(page: Int) {}
    override fun openWallpaper() {}
    override fun openSettings() {}
}

private const val SHOTS = "build/screenshots"

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class ScreensTest {
    @get:Rule val compose = createComposeRule()
    private val pads = PadRegistry()
    private val sfx = RecordingSfx()

    private fun show(strings: Strings = RU, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides strings,
                LocalIconLoader provides FakeIcons,
                com.retrohome.launcher.ui.LocalCoverLoader provides FakeCovers,
                com.retrohome.launcher.ui.LocalRemoteImages provides FakeBadges,
                com.retrohome.launcher.audio.LocalSfx provides sfx,
                LocalPadRegistry provides pads,
            ) { content() }
        }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun consoleHome() {
        val state = ConsoleState()
        val actions = RecordingConsoleActions()
        show { ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, com.retrohome.launcher.ui.console.GamesInfo(hasFolder = true)), state, ModeOverride.AUTO, false, actions) }
        compose.onRoot().captureRoboImage("$SHOTS/1_console_home.png")

        // Навигация кнопками: главная — столбик, вниз из «Последней игры» в избранное
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.DOWN) }
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("launch:org.citra.citra_emu/Main", actions.log.last()) }
        compose.runOnIdle { pads.dispatch(Pad.UP); pads.dispatch(Pad.Y) }
        compose.runOnIdle { assertEquals("fav:xyz.aethersx2.android/Main", actions.log.last()) }
        // Влево/вправо на главной ничего не делают
        compose.runOnIdle { pads.dispatch(Pad.RIGHT); pads.dispatch(Pad.Y) }
        compose.runOnIdle { assertEquals("fav:xyz.aethersx2.android/Main", actions.log.last()) }
        compose.runOnIdle { pads.dispatch(Pad.R1) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/2_console_emulators.png")
        compose.runOnIdle { pads.dispatch(Pad.R1) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/3_console_games.png")
        compose.runOnIdle { pads.dispatch(Pad.L1); pads.dispatch(Pad.START) }
        compose.runOnIdle { assertTrue(state.overlay == ConsoleOverlay.Start) }
        // Ищем пункт «Язык» по названию, а не по номеру
        val langIndex = com.retrohome.launcher.ui.console.startMenu(RU, ModeOverride.AUTO, actions).indexOfFirst { it.title == RU.language }
        compose.runOnIdle { repeat(langIndex) { pads.dispatch(Pad.DOWN) } }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/4_console_start_menu.png")
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("lang", actions.log.last()); assertTrue(state.overlay != null) }
        compose.runOnIdle { pads.dispatch(Pad.CANCEL); pads.dispatch(Pad.X) }
        compose.runOnIdle { assertTrue(state.overlay is ConsoleOverlay.Item) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/5_console_item_menu.png")
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun consoleHomeEnglish() {
        show(EN) {
            ConsoleHome(buildConsoleTabs(sampleApps, sampleData, EN, com.retrohome.launcher.ui.console.GamesInfo(hasFolder = true)), ConsoleState().apply { tabId = "emulators" }, ModeOverride.AUTO, false, RecordingConsoleActions())
        }
        compose.onRoot().captureRoboImage("$SHOTS/6_console_emulators_en.png")
    }

    @Test
    @Config(qualifiers = "w432dp-h768dp-port-xhdpi")
    fun phoneHome() {
        val state = PhoneState()
        val byKey = sampleApps.associateBy { it.key }
        show {
            // Вместо настоящих обоев
            androidx.compose.foundation.layout.Box(
                androidx.compose.ui.Modifier.fillMaxSize().background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(androidx.compose.ui.graphics.Color(0xFF2B5876), androidx.compose.ui.graphics.Color(0xFF4E4376))
                    )
                )
            ) { PhoneHome(sampleData, byKey, sampleApps, state, NoPhone) }
        }
        compose.onRoot().captureRoboImage("$SHOTS/7_phone_home.png")
        compose.runOnIdle { state.drawerOpen = true }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/8_phone_drawer.png")
        compose.runOnIdle { state.drawerOpen = false; state.openFolder = 5 }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/9_phone_folder.png")
    }


    @Test
    @Config(qualifiers = "w432dp-h768dp-port-xhdpi")
    fun phoneDragAndDrop() {
        val moves = mutableListOf<String>()
        val launched = mutableListOf<String>()
        val actions = object : PhoneActions by NoPhone {
            override fun moveItem(id: Long, page: Int, x: Int, y: Int) { moves += "$id->$page:$x,$y" }
            override fun launch(key: String) { launched += key }
        }
        show { PhoneHome(sampleData, sampleApps.associateBy { it.key }, sampleApps, PhoneState(), actions) }
        // Тап запускает приложение
        compose.onNodeWithText("YouTube").performClick()
        compose.runOnIdle { assertEquals(listOf("com.google.android.youtube/Main"), launched) }
        // Долгое нажатие и перетаскивание Telegram в клетку (2,3)
        val target = compose.onNodeWithText("Discord").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithText("Telegram").performTouchInput {
            down(center)
            advanceEventTime(900)
            val dx = (target.center.x - visibleSize.width / 2f) // примерно к Discord по горизонтали
            moveBy(androidx.compose.ui.geometry.Offset(dx / 2, 40f))
            moveBy(androidx.compose.ui.geometry.Offset(dx / 2, 600f))
            up()
        }
        compose.runOnIdle {
            assertEquals(1, moves.size)
            assertTrue(moves[0], moves[0].startsWith("1->0:"))
        }
    }

    @Test
    @Config(qualifiers = "w432dp-h768dp-port-xhdpi")
    fun phoneLongPressEmptyOpensMenu() {
        val opened = mutableListOf<String>()
        val actions = object : PhoneActions by NoPhone {
            override fun openSettings() { opened += "settings" }
        }
        val state = PhoneState()
        show { PhoneHome(sampleData, sampleApps.associateBy { it.key }, sampleApps, state, actions) }
        compose.onRoot().performTouchInput {
            down(androidx.compose.ui.geometry.Offset(width * 0.7f, height * 0.5f))
            advanceEventTime(1000)
            up()
        }
        compose.runOnIdle { assertTrue("sheet=" + state.sheet, state.sheet != null); state.sheet = null }
        // Видимая шестерёнка и кнопка Start ведут в настройки
        compose.onNodeWithText("⚙").performClick()
        compose.runOnIdle { assertEquals(listOf("settings"), opened) }
        compose.runOnIdle { assertTrue(pads.dispatch(Pad.START)) }
        compose.runOnIdle { assertEquals(listOf("settings", "settings"), opened) }
        compose.runOnIdle { assertTrue(!pads.dispatch(Pad.DOWN)) }
    }

    /** Иконка обязана смениться вместе с приложением (раньше оставалась старая). */
    @Test
    fun iconFollowsItsApp() {
        val loaded = mutableListOf<String>()
        val loader = object : IconLoader {
            override fun peek(key: String, sizePx: Int): ImageBitmap? = null
            override fun load(key: String, sizePx: Int): ImageBitmap { synchronized(loaded) { loaded += key }; return FakeIcons.load(key, sizePx) }
        }
        val current = mutableStateOf("a.first/Main")
        compose.setContent {
            CompositionLocalProvider(LocalIconLoader provides loader) {
                com.retrohome.launcher.ui.AppIcon(current.value, 48.dp)
            }
        }
        compose.waitUntil(5000) { synchronized(loaded) { "a.first/Main" in loaded } }
        compose.runOnIdle { current.value = "b.second/Main" }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.waitUntil(5000) { synchronized(loaded) { "b.second/Main" in loaded } }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun consoleWithGames() {
        val games = listOf(
            "God of War II" to GS.PS2, "Shadow of the Colossus" to GS.PS2, "Persona 4" to GS.PS2, "Okami" to GS.PS2,
            "Ratchet & Clank: Going Commando" to GS.PS2, "Patapon" to GS.PSP, "Crisis Core: Final Fantasy VII" to GS.PSP,
            "The Legend of Zelda: A Link Between Worlds" to GS.N3DS, "Pokemon - Emerald Version" to GS.GBA,
        ).mapIndexed { i, (t, s) -> com.retrohome.launcher.data.Game("$i", "/r/$i", s, t, emptyList(), s.short) }
        val gi = com.retrohome.launcher.ui.console.GamesInfo(games, hasFolder = true) { if (it == GS.GBA) "RetroArch" else "NetherSX2" }
        val data = sampleData.copy(recents = listOf(Recent("game:0", 900), Recent("game:5", 800), Recent("game:7", 700)) + sampleData.recents)
        val state = ConsoleState().apply { tabId = "sys:PS2" }
        val actions = RecordingConsoleActions()
        show { ConsoleHome(buildConsoleTabs(sampleApps, data, RU, gi), state, ModeOverride.AUTO, false, actions) }
        compose.onRoot().captureRoboImage("$SHOTS/11_console_ps2.png")
        // A открывает страницу игры, второе A на ней — запуск
        compose.runOnIdle { pads.dispatch(Pad.RIGHT); pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("game:3", state.page); assertTrue(actions.log.none { it.startsWith("launch") }) }
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("launch:game:3", actions.log.last()) }
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertNull(state.page); state.tabId = "home" }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/12_console_home_games.png")
        compose.runOnIdle { state.tabId = "sys:PS2"; pads.dispatch(Pad.X) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/13_console_game_menu.png")
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.DOWN); pads.dispatch(Pad.RIGHT) }
        compose.runOnIdle { assertEquals("cycle:PS2:1", actions.log.last()) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun gamePage() {
        val now = System.currentTimeMillis()
        val skies = com.retrohome.launcher.data.Game("sk", "/r/sk.iso", GS.GC, "Skies of Arcadia Legends - The Shadow of the Templars", listOf("USA"), "gc",
            discs = emptyList())
        val ff = com.retrohome.launcher.data.Game("ff", "/r/ff1.chd", GS.GC, "Tales of Symphonia", listOf("USA"), "gc",
            discs = listOf(1, 2).map { com.retrohome.launcher.data.Disc("ff$it", "/r/ff$it.chd", it) })
        val meta = mapOf(
            "sk" to com.retrohome.launcher.data.GameMeta(
                cover = "Skies of Arcadia.webp", snap = "sk_s.webp",
                descRu = "Skies of Arcadia — компьютерная ролевая игра, разработанная студией Overworks и изданная Sega. " +
                    "Игрок управляет небесным пиратом Вайсом, который вместе с друзьями путешествует по миру парящих островов на воздушном корабле. ".repeat(5),
                srcRu = "wikipedia", year = 2000, genresRu = listOf("компьютерная ролевая игра"), developers = listOf("Overworks"), publishers = listOf("Sega"),
            ),
        )
        val stats = mapOf("game:sk" to com.retrohome.launcher.data.PlayStat(totalMs = (3 * 60 + 20) * 60_000L, sessions = 4, last = now - 86_400_000L))
        val gi = com.retrohome.launcher.ui.console.GamesInfo(listOf(skies, ff), true, meta, playStats = stats) { "Dolphin" }
        val state = ConsoleState().apply { tabId = "sys:GC" }
        val actions = RecordingConsoleActions()
        show { ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, gi), state, ModeOverride.AUTO, false, actions) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/22_console_snap_backdrop.png")
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/23_game_page.png")
        compose.onNodeWithText("Наиграно: 3 ч 20 мин  ·  Последний запуск: вчера").assertExists()
        compose.onNodeWithText("Разработчик: Overworks", substring = true).assertExists()
        // Кнопки: Играть, Избранное, Ещё. Вправо — избранное
        compose.runOnIdle { pads.dispatch(Pad.RIGHT); pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("fav:game:sk", actions.log.last()) }
        compose.runOnIdle { pads.dispatch(Pad.DOWN) }
        compose.runOnIdle { assertTrue(state.pageScroll.value > 0) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/23b_game_page_scrolled.png")
        // R2 — следующая игра ряда; у неё два диска: Диск 1, Диск 2, Избранное, Ещё
        compose.runOnIdle { pads.dispatch(Pad.R2) }
        compose.runOnIdle { assertEquals("game:ff", state.page); assertEquals(0, state.pageButton) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/24_game_page_discs.png")
        compose.runOnIdle { pads.dispatch(Pad.RIGHT); pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("disc:game:ff:1", actions.log.last()) }
        // X — меню игры поверх страницы; B закрывает меню, страница остаётся
        compose.runOnIdle { pads.dispatch(Pad.X) }
        compose.runOnIdle { assertTrue(state.overlay is com.retrohome.launcher.ui.console.ConsoleOverlay.Item) }
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertNull(state.overlay); assertEquals("game:ff", state.page) }
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertNull(state.page) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun gamePageButtons() {
        val ff = com.retrohome.launcher.data.Game("ff", "/r/ff1.chd", GS.PS1, "Final Fantasy VII", listOf("USA"), "psx",
            discs = listOf(1, 2, 3).map { com.retrohome.launcher.data.Disc("ff$it", "/r/ff$it.chd", it) })
        val meta = mapOf("ff" to com.retrohome.launcher.data.GameMeta(cover = "ff.webp",
            descRu = "Final Fantasy VII — японская ролевая игра, разработанная Square для PlayStation в 1997 году.", srcRu = "wikipedia",
            year = 1997, genresRu = listOf("компьютерная ролевая игра"), developers = listOf("Square"), publishers = listOf("Square", "Square Enix")))
        val gi = com.retrohome.launcher.ui.console.GamesInfo(listOf(ff), true, meta) { "DuckStation" }
        fun entry(fav: Boolean) = buildConsoleTabs(sampleApps, sampleData.copy(favorites = if (fav) listOf("game:ff") else emptyList()), RU, gi)
            .flatMap { t -> t.rows.flatMap { it.entries } }.first { it.key == "game:ff" }
        val e = mutableStateOf(entry(false))
        val focused = mutableStateOf(1)
        show {
            com.retrohome.launcher.ui.console.GamePage(e.value, focused.value, androidx.compose.foundation.rememberScrollState(), "✕", "○", true,
                onButton = {}, onBack = {}, onOptions = {}, onFavorite = {}, onSwitch = {})
        }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        // «Играть» переехал на выбранный «Диск 2», у «Диск 1» его больше нет
        compose.onNodeWithText("▶  Играть · Диск 2").assertExists()
        compose.onNodeWithText("Диск 1").assertExists()
        compose.onRoot().captureRoboImage("$SHOTS/25_page_disc2_selected.png")
        // Выбрано «Избранное» — подписи «Играть» нигде нет
        compose.runOnIdle { focused.value = 3 }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithText("Играть", substring = true).fetchSemanticsNodes().size)
        // Добавили в избранное: посередине анимации кнопка заливается золотом
        compose.mainClock.autoAdvance = false
        val fav = entry(true)
        e.value = fav
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        repeat(16) { compose.mainClock.advanceTimeByFrame(); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        compose.onRoot().captureRoboImage("$SHOTS/26_page_favorite_filling.png")
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/27_page_favorite_done.png")
        compose.onNodeWithText("В избранном").assertExists()
        // Фокус ушёл с кнопки — она остаётся золотой
        compose.runOnIdle { focused.value = 0 }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/28_page_favorite_unfocused.png")
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun homeListWithInfo() {
        val now = System.currentTimeMillis()
        val games = listOf(
            com.retrohome.launcher.data.Game("ff", "/r/ff1.chd", GS.PS1, "Final Fantasy VII", listOf("RUS"), "psx",
                discs = listOf(1, 2, 3).map { com.retrohome.launcher.data.Disc("ff$it", "/r/ff$it.chd", it) }),
            com.retrohome.launcher.data.Game("sk", "/r/sk.iso", GS.GC, "Skies of Arcadia Legends", listOf("USA"), "gc"),
            com.retrohome.launcher.data.Game("bs", "/r/bs.gba", GS.GBA, "Broken Sword: The Shadow of the Templars", listOf("USA"), "gba"),
        )
        val meta = mapOf(
            "ff" to com.retrohome.launcher.data.GameMeta(cover = "ff.webp", snap = "ff_s.webp",
                descRu = "Final Fantasy VII — японская ролевая игра, разработанная Square для PlayStation в 1997 году. ".repeat(4), srcRu = "wikipedia",
                year = 1997, genresRu = listOf("компьютерная ролевая игра"), developers = listOf("Square"), publishers = listOf("Square", "Square Enix")),
            "sk" to com.retrohome.launcher.data.GameMeta(cover = "sk.webp",
                descRu = "Skies of Arcadia — компьютерная ролевая игра, разработанная студией Overworks и изданная Sega.", srcRu = "wikipedia",
                year = 2000, genresRu = listOf("компьютерная ролевая игра"), developers = listOf("Overworks"), publishers = listOf("Sega")),
        )
        val stats = mapOf(
            "game:ff" to com.retrohome.launcher.data.PlayStat(totalMs = (12 * 60 + 40) * 60_000L, sessions = 9, last = now - 3_600_000L),
            "game:sk" to com.retrohome.launcher.data.PlayStat(totalMs = 95 * 60_000L, sessions = 2, last = now - 3 * 86_400_000L),
        )
        val gi = com.retrohome.launcher.ui.console.GamesInfo(games, true, meta, playStats = stats) { "DuckStation" }
        val data = sampleData.copy(
            recents = listOf(Recent("xyz.aethersx2.android/Main", now), Recent("game:ff", now - 3_600_000L)) + sampleData.recents,
            favorites = listOf("game:ff", "game:sk", "game:bs", "xyz.aethersx2.android/Main", "org.citra.citra_emu/Main"),
        )
        val state = ConsoleState()
        val actions = RecordingConsoleActions()
        show { ConsoleHome(buildConsoleTabs(sampleApps, data, RU, gi), state, ModeOverride.AUTO, false, actions) }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Final Fantasy VII").fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().captureRoboImage("$SHOTS/29_home_last_played.png")
        // Вниз — в избранное, ещё вниз — вторая игра; справа её сведения
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.DOWN) }
        compose.waitForIdle()
        compose.onNodeWithText("Разработчик: Overworks", substring = true).assertExists()
        compose.onRoot().captureRoboImage("$SHOTS/30_home_favorites.png")
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.DOWN) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/31_home_favorite_app.png")
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("launch:xyz.aethersx2.android/Main", actions.log.last()) }
        // Вверх до конца — снова «Последняя игра»; A открывает её страницу
        compose.runOnIdle { repeat(6) { pads.dispatch(Pad.UP) }; pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("game:ff", state.page) }
        // Вкладка GC: на обложке избранной игры — крупная золотая звезда
        compose.runOnIdle { pads.dispatch(Pad.CANCEL); state.tabId = "sys:GC" }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/32_favorite_star_on_cover.png")
    }

    /** Пролистывание избранного вниз и вверх: выбранная игра всегда видна целиком (экран как у Retroid Pocket G2). */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun favoritesListKeepsSelectionVisible() {
        val titles = listOf("Pokémon Brilliant Diamond", "Metal Slug Advance", "Avatar - The Last Airbender - The Burning Earth",
            "Final Fantasy VII", "Skies of Arcadia Legends", "Broken Sword", "Okami", "Persona 4", "Patapon")
        val games = titles.mapIndexed { i, t -> com.retrohome.launcher.data.Game("g$i", "/r/$i", GS.GBA, t, emptyList(), "gba") }
        val gi = com.retrohome.launcher.ui.console.GamesInfo(games, true) { "RetroArch" }
        val data = sampleData.copy(recents = listOf(Recent("game:g1", 10)), favorites = games.map { it.key })
        val state = ConsoleState()
        show { ConsoleHome(buildConsoleTabs(sampleApps, data, RU, gi), state, ModeOverride.AUTO, false, RecordingConsoleActions()) }
        compose.waitForIdle()
        fun assertVisible(i: Int, step: String) {
            // boundsInRoot уже обрезан рамкой списка — сравниваем видимую высоту с настоящей высотой пункта
            val node = compose.onNodeWithTag("fav:$i", useUnmergedTree = true).fetchSemanticsNode()
            val visible = node.boundsInRoot.height
            assertTrue("$step: пункт $i виден на $visible из ${node.size.height} px", visible >= node.size.height - 1)
        }
        compose.runOnIdle { pads.dispatch(Pad.DOWN) }
        compose.waitForIdle()
        // Медленно: по одному нажатию
        for (i in 1 until titles.size) {
            compose.runOnIdle { pads.dispatch(Pad.DOWN) }
            compose.waitForIdle()
            assertVisible(i, "вниз")
        }
        compose.onRoot().captureRoboImage("$SHOTS/33_favorites_scrolled_end.png")
        for (i in titles.size - 2 downTo 0) {
            compose.runOnIdle { pads.dispatch(Pad.UP) }
            compose.waitForIdle()
            assertVisible(i, "вверх")
        }
        // Как на приставке: следующее нажатие приходит, пока список ещё едет от предыдущего
        compose.mainClock.autoAdvance = false
        for (i in 1 until titles.size) {
            compose.runOnIdle { pads.dispatch(Pad.DOWN) }
            compose.mainClock.advanceTimeBy(90)
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertVisible(titles.size - 1, "вниз с прерыванием прокрутки")
        for (i in titles.size - 2 downTo 0) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { pads.dispatch(Pad.UP) }
            compose.mainClock.advanceTimeBy(90)
        }
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertVisible(0, "вверх с прерыванием прокрутки")
        for (step in 1..titles.size - 1) {
            compose.mainClock.autoAdvance = false
            compose.runOnIdle { pads.dispatch(Pad.DOWN) }
            compose.mainClock.advanceTimeBy(140)
            compose.mainClock.autoAdvance = true
            compose.waitForIdle()
            assertVisible(step, "вниз с паузой 140 мс")
        }
        compose.runOnIdle { repeat(titles.size) { pads.dispatch(Pad.UP) } }
        compose.waitForIdle()
        compose.runOnIdle { pads.dispatch(Pad.DOWN) }
        compose.waitForIdle()
        // Быстро: несколько нажатий подряд, не дожидаясь прокрутки
        compose.runOnIdle { repeat(5) { pads.dispatch(Pad.DOWN) } }
        compose.waitForIdle()
        assertVisible(5, "быстро вниз")
        compose.runOnIdle { repeat(3) { pads.dispatch(Pad.UP) } }
        compose.waitForIdle()
        assertVisible(2, "быстро вверх")
        // Ушли на другую вкладку и вернулись — выбор глубоко в списке, он должен быть виден сразу
        compose.runOnIdle { repeat(4) { pads.dispatch(Pad.DOWN) } }
        compose.waitForIdle()
        compose.runOnIdle { pads.dispatch(Pad.R1) }
        compose.waitForIdle()
        compose.runOnIdle { pads.dispatch(Pad.L1) }
        compose.waitForIdle()
        assertVisible(6, "после смены вкладки")
        // Открыли страницу игры и вернулись назад
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.waitForIdle()
        compose.runOnIdle { pads.dispatch(Pad.R2); pads.dispatch(Pad.R2) }
        compose.waitForIdle()
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.waitForIdle()
        assertVisible(8, "после страницы игры (R2 по избранному)")
        // R1 на странице игры — следующая вкладка консоли, сразу страница выбранной там игры
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.waitForIdle()
        compose.runOnIdle { pads.dispatch(Pad.R1) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals("sys:GBA", state.tabId); assertEquals("game:g2", state.page) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun achievementsOnGamePage() {
        val now = System.currentTimeMillis()
        val ff = com.retrohome.launcher.data.Game("ff", "/r/ff1.chd", GS.PS1, "Final Fantasy VII", listOf("USA"), "psx")
        val sk = com.retrohome.launcher.data.Game("sk", "/r/sk.iso", GS.GC, "Skies of Arcadia Legends", listOf("USA"), "gc")
        val meta = mapOf(
            "ff" to com.retrohome.launcher.data.GameMeta(cover = "ff.webp", snap = "ff_s.webp",
                descRu = "Final Fantasy VII — японская ролевая игра, разработанная Square для PlayStation в 1997 году.", srcRu = "wikipedia",
                year = 1997, genresRu = listOf("компьютерная ролевая игра"), developers = listOf("Square"), publishers = listOf("Square")),
            "sk" to com.retrohome.launcher.data.GameMeta(cover = "sk.webp"),
        )
        val stats = mapOf("game:ff" to com.retrohome.launcher.data.PlayStat(totalMs = 41 * 3_600_000L, sessions = 30, last = now - 3_600_000L))
        val titles = listOf(
            "Тяжёлое детство" to "Сбежать из реактора №1", "Мотоциклетная погоня" to "Пройти погоню без повреждений",
            "Золотой чокобо" to "Вырастить золотого чокобо", "Рыцари круглого стола" to "Получить материю Knights of the Round",
            "Оружие Рубина" to "Победить Ruby Weapon", "Оружие Изумруда" to "Победить Emerald Weapon",
            "Кот Сит" to "Пригласить в команду Cait Sith", "Последний лимит" to "Открыть все финальные лимиты",
        )
        val achievements = titles.mapIndexed { i, (t, d) ->
            com.retrohome.launcher.data.Ra.Achievement(i + 1, t, d, listOf(5, 10, 25, 50)[i % 4], "b$i", i,
                earned = if (i % 3 != 2) "2026-10-1${i % 9} 12:00:00" else null,
                earnedHardcore = if (i == 0) "2026-10-10 12:00:00" else null)
        }
        val detail = com.retrohome.launcher.data.Ra.GameProgress(11319, "Final Fantasy VII", 8, achievements.count { it.unlocked }, 1, achievements, now)
        val ra = com.retrohome.launcher.data.RaState(
            com.retrohome.launcher.data.RaStatus.OK,
            matches = mapOf("ff" to 11319, "sk" to 2),
            totals = mapOf(11319 to 8, 2 to 40),
            progress = mapOf(2 to com.retrohome.launcher.data.Ra.Completion(2, 40, 40, 40)),
            details = mapOf(11319 to detail),
        )
        val summaries = listOf(ff, sk).mapNotNull { g -> ra.summary(g.id)?.let { g.id to it } }.toMap()
        val gi = com.retrohome.launcher.ui.console.GamesInfo(listOf(ff, sk), true, meta, playStats = stats, ra = summaries) { "DuckStation" }
        val data = sampleData.copy(recents = listOf(Recent("game:ff", now)), favorites = listOf("game:ff", "game:sk"))
        val state = ConsoleState()
        val opened = mutableListOf<Int>()
        val actions = object : ConsoleActions by RecordingConsoleActions() {
            override fun openAchievements(raGameId: Int) { opened += raGameId }
        }
        show { ConsoleHome(buildConsoleTabs(sampleApps, data, RU, gi), state, ModeOverride.AUTO, false, actions, ra = ra) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/34_home_achievements.png")
        // Страница игры: строка прогресса и кнопка «Достижения»
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("🏆 Достижения: 6 из 8 · 75%").fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().captureRoboImage("$SHOTS/35_page_achievements.png")
        compose.runOnIdle { pads.dispatch(Pad.RIGHT); pads.dispatch(Pad.CONFIRM) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(listOf(11319), opened)
            assertTrue(state.overlay is com.retrohome.launcher.ui.console.ConsoleOverlay.Achievements)
        }
        compose.onNodeWithText("Золотой чокобо").assertExists()
        compose.onRoot().captureRoboImage("$SHOTS/36_achievements_panel.png")
        // Y — только ещё не открытые
        compose.runOnIdle { pads.dispatch(Pad.Y) }
        compose.waitForIdle()
        compose.onNodeWithText("Ещё не открытые").assertExists()
        assertEquals(0, compose.onAllNodesWithText("Тяжёлое детство").fetchSemanticsNodes().size)
        compose.onRoot().captureRoboImage("$SHOTS/37_achievements_locked.png")
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertNull(state.overlay); assertEquals("game:ff", state.page) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun raAccountScreen() {
        var saved: Pair<String?, String?>? = null
        show {
            com.retrohome.launcher.ui.RaAccountScreen("player", "KEY123", onBack = {}) { u, k -> saved = u to k; "Player" to 1234 }
        }
        compose.waitForIdle()
        compose.onNodeWithText("Сохранить и проверить").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Вход выполнен: Player, 1234 очков", substring = true).assertExists()
        assertEquals("player" to "KEY123", saved)
        compose.onRoot().captureRoboImage("$SHOTS/38_ra_account.png")
    }

    /** Вкладки, ушедшие за край, выезжают на экран при переключении (экран как у Retroid Pocket G2). */
    @Test
    @Config(qualifiers = "w640dp-h360dp-land-xxhdpi")
    fun selectedTabAlwaysVisible() {
        val systems = listOf(GS.GC, GS.SWITCH, GS.GBA, GS.PS1, GS.PS2, GS.PSP, GS.DC, GS.N64, GS.SNES, GS.NDS, GS.N3DS, GS.MD)
        val games = systems.mapIndexed { i, sys -> com.retrohome.launcher.data.Game("g$i", "/r/$i", sys, "Game $i", emptyList(), sys.short) }
        val gi = com.retrohome.launcher.ui.console.GamesInfo(games, true) { "RetroArch" }
        val tabs = buildConsoleTabs(sampleApps, sampleData, RU, gi)
        val state = ConsoleState()
        // Как на скриншоте: идёт загрузка, справа счётчик — места под вкладки меньше
        show { ConsoleHome(tabs, state, ModeOverride.AUTO, false, RecordingConsoleActions(), metaProgress = "⟳ 0/142") }
        compose.waitForIdle()
        fun assertTabVisible(step: String) {
            val id = state.tabId
            val node = compose.onNodeWithTag("tab:$id", useUnmergedTree = true).fetchSemanticsNode()
            val visible = node.boundsInRoot.width
            assertTrue("$step: вкладка $id видна на $visible из ${node.size.width} px", visible >= node.size.width - 1)
        }
        // R1 до конца и дальше по кругу на первую
        repeat(tabs.size + 1) { i ->
            compose.runOnIdle { pads.dispatch(Pad.R1) }
            compose.waitForIdle()
            assertTabVisible("R1 #${i + 1}")
        }
        compose.onRoot().captureRoboImage("$SHOTS/39_tabs_scrolled.png")
        // L1 назад: с первой — на последнюю
        repeat(3) { i ->
            compose.runOnIdle { pads.dispatch(Pad.L1) }
            compose.waitForIdle()
            assertTabVisible("L1 #${i + 1}")
        }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun networkCheckScreen() {
        val N = com.retrohome.launcher.data.NetworkCheck
        val fake = listOf(
            com.retrohome.launcher.data.NetworkCheck.Result(N.targets[0], 200, 180, null),
            com.retrohome.launcher.data.NetworkCheck.Result(N.targets[1], 429, 950, null),
            com.retrohome.launcher.data.NetworkCheck.Result(N.targets[2], 0, 15000, "SocketTimeoutException: timeout"),
            com.retrohome.launcher.data.NetworkCheck.Result(N.targets[3], 0, 40, "UnknownHostException: Unable to resolve host \"www.wikidata.org\""),
            com.retrohome.launcher.data.NetworkCheck.Result(N.targets[4], 0, 300, "SSLHandshakeException: Chain validation failed"),
            com.retrohome.launcher.data.NetworkCheck.Result(N.targets[5], 200, 420, null),
        )
        show { com.retrohome.launcher.ui.NetworkCheckScreen(onBack = {}) { onEach -> fake.forEach(onEach) } }
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Работает · 180 мс").fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("Сайт ограничил запросы", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(compose.onAllNodesWithText("неверной даты и времени", substring = true).fetchSemanticsNodes().isNotEmpty())
        compose.onRoot().captureRoboImage("$SHOTS/40_network_check.png")
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun gamePageCanBeTurnedOff() {
        val g = com.retrohome.launcher.data.Game("sk", "/r/sk.iso", GS.GC, "Skies", emptyList(), "gc")
        val gi = com.retrohome.launcher.ui.console.GamesInfo(listOf(g), true) { "Dolphin" }
        val state = ConsoleState().apply { tabId = "sys:GC" }
        val actions = RecordingConsoleActions()
        show { ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, gi), state, ModeOverride.AUTO, false, actions, gamePage = false) }
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("launch:game:sk", actions.log.last()); assertNull(state.page) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun consoleWithCovers() {
        val titles = listOf("God of War II", "Okami", "Persona 4", "Ratchet Clank", "Shadow of the Colossus")
        val games = titles.mapIndexed { i, t -> com.retrohome.launcher.data.Game("$i", "/r/$i", GS.PS2, t, emptyList(), "ps2") }
        val meta = games.associate { g ->
            g.id to com.retrohome.launcher.data.GameMeta(
                cover = if (g.title == "Persona 4") null else "${g.title}.webp",
                descRu = if (g.title == "Okami") null else "${g.title} — компьютерная игра в жанре action-adventure, разработанная для PlayStation 2. " +
                    "Игрок путешествует по огромному миру, сражается с врагами и разгадывает головоломки. ".repeat(16),
                descEn = "${g.title} is an action-adventure video game developed for the PlayStation 2. ".repeat(4),
            )
        }
        val gi = com.retrohome.launcher.ui.console.GamesInfo(games, hasFolder = true, meta = meta) { "NetherSX2" }
        val state = ConsoleState().apply { tabId = "sys:PS2" }
        show {
            ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, gi), state, ModeOverride.AUTO, false, RecordingConsoleActions(),
                metaProgress = "Обложки: 37 из 52")
        }
        compose.onRoot().captureRoboImage("$SHOTS/16_console_covers.png")
        compose.runOnIdle { pads.dispatch(Pad.X) }
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertTrue(state.overlay is com.retrohome.launcher.ui.console.ConsoleOverlay.Description) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/17_console_description.png")
        compose.runOnIdle { pads.dispatch(Pad.DOWN) }
        compose.runOnIdle { assertTrue(state.descScroll.value > 0) }
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertNull(state.overlay) }
        // Оками: русского описания нет — показывается английское с пометкой
        compose.runOnIdle { pads.dispatch(Pad.RIGHT) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/18_console_fallback.png")
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun soundsAndDiscs() {
        val ff7 = com.retrohome.launcher.data.Game(
            "d1", "/r/ff7 disk1.chd", GS.PS1, "Final Fantasy VII", listOf("RUS"), "psx",
            discs = listOf(1, 2, 3).map { com.retrohome.launcher.data.Disc("d$it", "/r/ff7 disk$it.chd", it) },
        )
        val sh = com.retrohome.launcher.data.Game("s1", "/r/sh.chd", GS.PS1, "Silent Hill", emptyList(), "psx")
        val gi = com.retrohome.launcher.ui.console.GamesInfo(listOf(ff7, sh), hasFolder = true) { "DuckStation" }
        val state = ConsoleState().apply { tabId = "sys:PS1" }
        val actions = RecordingConsoleActions()
        show {
            ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, gi), state, ModeOverride.AUTO, false, actions,
                audio = com.retrohome.launcher.ui.console.AudioState("retro", "chiptune"))
        }
        compose.runOnIdle { pads.dispatch(Pad.RIGHT); pads.dispatch(Pad.RIGHT); pads.dispatch(Pad.L1); pads.dispatch(Pad.R1) }
        // второй RIGHT упирается в край ряда — лишнего звука нет
        compose.runOnIdle { assertEquals(listOf(Sfx.MOVE, Sfx.TAB, Sfx.TAB), sfx.played) }
        compose.runOnIdle { pads.dispatch(Pad.LEFT); pads.dispatch(Pad.X) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/19_discs_menu.png")
        // Меню игры: Запустить, Диск 2, Диск 3, Избранное, Запускать через, Исправить название
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.DOWN); pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("disc:game:d1:2", actions.log.last()) }
        // Меню Start: Поиск, Порядок игр, Звуки, Музыка…
        compose.runOnIdle { pads.dispatch(Pad.START); pads.dispatch(Pad.DOWN); pads.dispatch(Pad.RIGHT) }
        compose.runOnIdle { assertEquals("sort:1", actions.log.last()) }
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.RIGHT) }
        compose.runOnIdle { assertEquals("sfx:1", actions.log.last()) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/20_start_audio.png")
        compose.runOnIdle { assertEquals(Sfx.SELECT, sfx.played.last()) }
        // Влево — предыдущий вариант, а не следующий
        compose.runOnIdle { pads.dispatch(Pad.LEFT) }
        compose.runOnIdle { assertEquals("sfx:-1", actions.log.last()) }
        compose.runOnIdle { pads.dispatch(Pad.DOWN); pads.dispatch(Pad.LEFT); pads.dispatch(Pad.RIGHT) }
        compose.runOnIdle { assertEquals(listOf("music:-1", "music:1"), actions.log.takeLast(2)) }
    }

    @Test
    fun allSoundFilesPresent() {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<Application>()
        for (pack in listOf("soft", "retro", "crystal", "click")) for (s in com.retrohome.launcher.audio.Sfx.entries) {
            val id = ctx.resources.getIdentifier("sfx_${pack}_${s.file}", "raw", ctx.packageName)
            assertTrue("sfx_${pack}_${s.file}", id != 0)
        }
        assertTrue(com.retrohome.launcher.audio.MusicTrack.entries.all { it.res == null || it.res != 0 })
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun consoleNoFolder() {
        show { ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU), ConsoleState(), ModeOverride.AUTO, false, RecordingConsoleActions()) }
        compose.onRoot().captureRoboImage("$SHOTS/14_console_pick_folder.png")
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun foldersScreen() {
        val folders = listOf(
            com.retrohome.launcher.data.FolderInfo("ps2", GS.PS2, GS.PS2, 14),
            com.retrohome.launcher.data.FolderInfo("psp", GS.PSP, GS.PSP, 6),
            com.retrohome.launcher.data.FolderInfo("3ds", GS.N3DS, GS.N3DS, 3),
            com.retrohome.launcher.data.FolderInfo("atari2600", null, null, 0),
            com.retrohome.launcher.data.FolderInfo("nes", GS.NES, GS.NES, 0),
        )
        show { com.retrohome.launcher.ui.FoldersScreen(folders, true, false, true, {}, {}, {}) }
        compose.onRoot().captureRoboImage("$SHOTS/15_folders.png")
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun settings() {
        val settings = mutableStateOf(Settings())
        val steps = mutableListOf<String>()
        show {
            SettingsScreen(settings.value, "0.1", focusFirst = true, onBack = {}, a = object : SettingsActions {
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
                override fun cycleSfx(dir: Int) { steps += "sfx:$dir" }
                override fun cycleSfxVolume(dir: Int) {}
                override fun cycleMusic(dir: Int) {}
                override fun cycleMusicVolume(dir: Int) {}
                override fun pickMusic() {}
            })
        }
        compose.onRoot().captureRoboImage("$SHOTS/10_settings.png")
        // Настройки — по разделам: заходим в «Звук»
        compose.onNodeWithText(RU.secSound).performClick()
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/10b_settings_sound.png")
        // Стрелки у переключателя: ‹ — назад, › — вперёд; нажатие на строку — вперёд
        compose.onNode(androidx.compose.ui.test.hasScrollAction()).performScrollToNode(androidx.compose.ui.test.hasText(RU.sounds))
        val soft = RU.packName("soft")
        compose.onNode(androidx.compose.ui.test.hasText("‹") and androidx.compose.ui.test.hasAnySibling(androidx.compose.ui.test.hasText(soft)), useUnmergedTree = true).performClick()
        compose.onNode(androidx.compose.ui.test.hasText("›") and androidx.compose.ui.test.hasAnySibling(androidx.compose.ui.test.hasText(soft)), useUnmergedTree = true).performClick()
        compose.onNodeWithText(RU.sounds).performClick()
        compose.runOnIdle { assertEquals(listOf("sfx:-1", "sfx:1", "sfx:1"), steps) }
    }

    @org.junit.After fun resetTheme() = com.retrohome.launcher.ui.Palette.apply("night", "coral")

    /** Цвет вкладки консоли и светлая тема. */
    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun themesAndTabColors() {
        val games = listOf("God of War II", "Okami", "Persona 4").mapIndexed { i, t ->
            com.retrohome.launcher.data.Game("$i", "/r/$i", GS.PS2, t, emptyList(), "ps2")
        } + com.retrohome.launcher.data.Game("s", "/r/s", GS.SWITCH, "Metroid Dread", emptyList(), "switch")
        val meta = mapOf("0" to com.retrohome.launcher.data.GameMeta(cover = "God of War II.webp"), "1" to com.retrohome.launcher.data.GameMeta(cover = "Okami.webp"))
        val gi = com.retrohome.launcher.ui.console.GamesInfo(games, true, meta) { "NetherSX2" }
        val state = ConsoleState().apply { tabId = "sys:PS2" }
        val tabColors = mutableStateOf(true)
        show { ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, gi), state, ModeOverride.AUTO, false, RecordingConsoleActions(), tabColors = tabColors.value) }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/25_tab_color_ps2.png")
        compose.runOnIdle { state.tabId = "sys:SWITCH" }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/26_tab_color_switch.png")
        compose.runOnIdle { com.retrohome.launcher.ui.Palette.apply("light", "blue"); state.tabId = "sys:PS2" }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/27_light_theme.png")
        compose.runOnIdle { com.retrohome.launcher.ui.Palette.apply("oled", "green"); tabColors.value = false }
        compose.mainClock.advanceTimeBy(800)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/28_oled_theme.png")
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun consoleTabsScreen() {
        val tabs = listOf("home" to "Главная", "sys:GC" to "GC", "sys:PS2" to "PS2", "emulators" to "Эмуляторы", "games" to "Игры")
            .map { com.retrohome.launcher.ui.TabItem(it.first, it.second) }
        val hidden = mutableStateOf(setOf("games"))
        val moves = mutableListOf<String>()
        show {
            com.retrohome.launcher.ui.ConsoleTabsScreen(tabs, hidden.value, focusFirst = false, onBack = {},
                onToggle = { id -> hidden.value = if (id in hidden.value) hidden.value - id else hidden.value + id },
                onMove = { id, dir -> moves += "$id:$dir" })
        }
        compose.onRoot().captureRoboImage("$SHOTS/29_console_tabs.png")
        compose.onNodeWithText("Игры").performClick()
        compose.runOnIdle { assertTrue(hidden.value.isEmpty()) }
        compose.onAllNodesWithText("▼")[0].performClick()
        compose.runOnIdle { assertEquals(listOf("sys:GC:1"), moves) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun settingsLight() {
        com.retrohome.launcher.ui.Palette.apply("light", "purple")
        show {
            SettingsScreen(Settings(theme = "light", accent = "purple"), "0.12", focusFirst = false, onBack = {}, a = object : SettingsActions {
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
            })
        }
        compose.onRoot().captureRoboImage("$SHOTS/30_settings_light.png")
    }

    /** Виджет на рабочем столе: значки его обтекают, долгое нажатие открывает меню виджета. */
    @Test
    @Config(qualifiers = "w432dp-h768dp-port-xhdpi")
    fun phoneWidgets() {
        val w = HomeItem(50, 0, 0, 0, widgetId = 7, widgetProvider = "com.clock/.ClockWidget", spanX = 4, spanY = 2)
        val data = sampleData.copy(home = listOf(w) + sampleData.home.map { if (it.page == 0 && !it.isDock) it.copy(y = it.y + 2) else it })
        val state = PhoneState()
        val resized = mutableListOf<String>()
        show {
            PhoneHome(data, sampleApps.associateBy { it.key }, sampleApps, state, object : PhoneActions by NoPhone {
                override fun resizeWidget(id: Long, w: Int, h: Int) { resized += "$id:$w×$h" }
                override fun moveItem(id: Long, page: Int, x: Int, y: Int) { resized += "move:$id:$page:$x,$y" }
            })
        }
        compose.onRoot().captureRoboImage("$SHOTS/32_phone_widget.png")
        compose.onNodeWithText("ClockWidget").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(RU.widgets, state.sheet?.title) }
        compose.onNodeWithText(RU.widgetSize).performClick()
        compose.onNodeWithText("2 × 2").performClick()
        compose.runOnIdle { assertEquals(listOf("50:2×2"), resized) }
        // «Переместить»: касание клетки ставит туда виджет
        compose.onNodeWithText("ClockWidget").performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(RU.moveWidget).performClick()
        compose.runOnIdle { assertEquals(50L, state.movingWidget) }
        compose.onNodeWithText(RU.moveWidgetHint).assertExists()
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.1f, height * 0.6f)) }
        compose.runOnIdle { assertTrue(resized.last(), resized.last().startsWith("move:50:0:0,")); assertNull(state.movingWidget) }
    }

    @Test
    @Config(qualifiers = "w432dp-h768dp-port-xhdpi")
    fun widgetPicker() {
        val options = listOf(
            com.retrohome.launcher.data.WidgetOption("com.clock/.Big", "Часы и погода", "Часы", "com.clock", 4, 2),
            com.retrohome.launcher.data.WidgetOption("com.clock/.Small", "Будильник", "Часы", "com.clock", 2, 1),
            com.retrohome.launcher.data.WidgetOption("com.music/.Player", "Плеер", "Музыка", "com.music", 4, 1),
        )
        var picked: String? = null
        show {
            com.retrohome.launcher.ui.WidgetPickerScreen(options, preview = { o -> FakeCovers.load(o.label) }, onBack = {}) { picked = it.provider }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/33_widget_picker.png")
        compose.onNodeWithText("Плеер").performClick()
        compose.runOnIdle { assertEquals("com.music/.Player", picked) }
    }

    /** Кнопки в стиле консолей: значки PlayStation, цвета SNES, GameCube и т.д. */
    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun consoleGlyphStyles() {
        val systems = listOf(null, GS.PS2, GS.SNES, GS.N64, GS.GC, GS.GBA, GS.SWITCH, GS.DC, GS.PC)
        show {
            androidx.compose.foundation.layout.Column(
                androidx.compose.ui.Modifier.background(com.retrohome.launcher.ui.Palette.bgBottom).padding(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                systems.forEach { sys ->
                    CompositionLocalProvider(com.retrohome.launcher.ui.LocalGlyphStyle provides com.retrohome.launcher.ui.glyphStyleFor(sys)) {
                        androidx.compose.foundation.layout.Row(
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            androidx.compose.material3.Text(sys?.short ?: "—", color = com.retrohome.launcher.ui.Palette.text,
                                modifier = androidx.compose.ui.Modifier.width(60.dp))
                            listOf("A", "B", "X", "Y").forEach { com.retrohome.launcher.ui.PadGlyph(it) }
                        }
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("$SHOTS/31_console_glyphs.png")
        val ps = com.retrohome.launcher.ui.glyphStyleFor(GS.PS2)!!
        assertEquals(com.retrohome.launcher.ui.GlyphShape.CROSS, ps.looks["A"]!!.shape)
        assertEquals(com.retrohome.launcher.ui.GlyphShape.TRIANGLE, ps.looks["Y"]!!.shape)
        assertNull(com.retrohome.launcher.ui.glyphStyleFor(null))
    }

    /** Буквы A/B/X/Y по центру круга, даже при крупном шрифте в настройках телефона. */
    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun padGlyphsLargeFont() {
        show {
            val d = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 1.3f)) {
                androidx.compose.foundation.layout.Row(
                    androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF14151C)).padding(16.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                ) {
                    listOf("A", "B", "X", "Y", "START", "L1").forEach { com.retrohome.launcher.ui.PadGlyph(it) }
                }
            }
        }
        compose.onRoot().captureRoboImage("$SHOTS/21_pad_glyphs.png")
    }

    /** Кнопки влево/вправо на выделенной строке-переключателе листают назад и вперёд. */
    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun choiceRowDpad() {
        val steps = mutableListOf<Int>()
        val fr = androidx.compose.ui.focus.FocusRequester()
        show {
            com.retrohome.launcher.ui.ChoiceRow("Звуки", "Мягкие", androidx.compose.ui.Modifier.focusRequester(fr)) { steps += it }
            // На приставке фокус появляется после первого нажатия кнопки — включаем этот режим
            val im = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                im.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard)
                fr.requestFocus()
            }
        }
        compose.waitForIdle()
        // Как и на устройстве, первое нажатие крестовины после касания экрана
        // только выводит Android из сенсорного режима (показывает рамку фокуса)
        compose.onNodeWithText("Звуки").performKeyInput { pressKey(Key.DirectionUp) }
        compose.onNodeWithText("Звуки").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Звуки").performKeyInput { pressKey(Key.DirectionLeft) }
        compose.onNodeWithText("Звуки").performKeyInput { pressKey(Key.DirectionRight) }
        compose.runOnIdle { assertEquals(listOf(-1, -1, 1), steps) }
    }
}

/** Retro Home, открытый значком как обычное приложение. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class AppModeUiTest {
    @get:Rule val compose = createComposeRule()
    private val pads = PadRegistry()

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun backOnHomeTabExitsOnlyInAppMode() {
        var exits = 0
        val appMode = mutableStateOf(true)
        val state = ConsoleState()
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides RU,
                LocalIconLoader provides FakeIcons,
                com.retrohome.launcher.ui.LocalCoverLoader provides FakeCovers,
                com.retrohome.launcher.ui.LocalRemoteImages provides FakeBadges,
                com.retrohome.launcher.audio.LocalSfx provides RecordingSfx(),
                LocalPadRegistry provides pads,
                com.retrohome.launcher.ui.LocalAppMode provides appMode.value,
                com.retrohome.launcher.ui.LocalAppExit provides { exits++ },
            ) {
                ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, com.retrohome.launcher.ui.console.GamesInfo(hasFolder = true)), state, ModeOverride.AUTO, false, RecordingConsoleActions())
            }
        }
        // Не на главной вкладке «назад» сначала возвращает на главную
        compose.runOnIdle { pads.dispatch(Pad.R1); pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertEquals(0, state.tab); assertEquals(0, exits) }
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertEquals(1, exits) }
        // На главном экране (не в режиме приложения) «назад» ничего не закрывает
        compose.runOnIdle { appMode.value = false }
        compose.runOnIdle { pads.dispatch(Pad.CANCEL) }
        compose.runOnIdle { assertEquals(1, exits) }
    }

    /** Пункты меню START на экране и нажатия должны совпадать (раньше «Настройки» открывали Wi-Fi). */
    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun startMenuItemsOpenWhatTheySayInAppMode() {
        val state = ConsoleState()
        val actions = RecordingConsoleActions()
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides RU,
                LocalIconLoader provides FakeIcons,
                com.retrohome.launcher.ui.LocalCoverLoader provides FakeCovers,
                com.retrohome.launcher.ui.LocalRemoteImages provides FakeBadges,
                com.retrohome.launcher.audio.LocalSfx provides RecordingSfx(),
                LocalPadRegistry provides pads,
                com.retrohome.launcher.ui.LocalAppMode provides true,
            ) {
                ConsoleHome(buildConsoleTabs(sampleApps, sampleData, RU, com.retrohome.launcher.ui.console.GamesInfo(hasFolder = true)), state, ModeOverride.AUTO, false, actions)
            }
        }
        compose.runOnIdle { pads.dispatch(Pad.START) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        // На экране нет пункта «Режим»
        compose.onAllNodesWithText(RU.mode).assertCountEquals(0)
        // Кнопками: дойти до «Настройки» и нажать
        val menu = com.retrohome.launcher.ui.console.startMenu(RU, ModeOverride.AUTO, actions, appMode = true)
        val settings = menu.indexOfFirst { it.title == RU.settings }
        compose.runOnIdle { repeat(settings) { pads.dispatch(Pad.DOWN) }; pads.dispatch(Pad.CONFIRM) }
        compose.runOnIdle { assertEquals("settings", actions.log.last()) }
        // Касанием: тап по «Wi-Fi» открывает именно Wi-Fi
        val wifi = menu.indexOfFirst { it.title == RU.wifi }
        compose.runOnIdle { state.menuIndex = wifi; state.overlay = ConsoleOverlay.Start }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        compose.onNodeWithText(RU.wifi).performClick()
        compose.runOnIdle { assertEquals("wifi", actions.log.last()) }
    }

    @Test fun startMenuHasNoModeSwitchInAppMode() {
        val actions = RecordingConsoleActions()
        val normal = com.retrohome.launcher.ui.console.startMenu(RU, ModeOverride.AUTO, actions)
        val app = com.retrohome.launcher.ui.console.startMenu(RU, ModeOverride.AUTO, actions, appMode = true)
        assertTrue(normal.any { it.title == RU.mode })
        assertTrue(app.none { it.title == RU.mode })
        assertEquals(normal.size - 1, app.size)
    }

    @Test fun iconOpensAppModeAndHomeStaysHome() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        val pm = app.packageManager
        val launcher = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER).setPackage(app.packageName), 0,
        ).map { it.activityInfo.name }
        assertEquals(listOf(AppModeActivity::class.java.name), launcher)
        val home = pm.queryIntentActivities(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME).setPackage(app.packageName), 0,
        ).map { it.activityInfo.name }
        assertEquals(listOf(MainActivity::class.java.name), home)
    }
}


/** Обложки в своих пропорциях и масштаб интерфейса на невысоких экранах. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class CoverAndScaleUiTest {
    @get:Rule val compose = createComposeRule()
    private val pads = PadRegistry()

    private fun pageEntry(cover: String) = com.retrohome.launcher.ui.console.ConsoleEntry(
        key = "game:ff", label = "Final Fantasy V Advance", favorite = false, zone = ConsoleZone.GAME, kind = com.retrohome.launcher.ui.console.EntryKind.GAME,
        system = GS.GBA, cover = cover, subtitle = "GBA · USA · запуск в GBA.emu",
        description = "Final Fantasy V — японская ролевая игра для приставки Super Famicom, разработанная и выпущенная компанией Square в 1992 году, пятая номерная часть серии Final Fantasy. ".repeat(4),
        descSource = "wikipedia", year = 1992, genres = listOf("компьютерная ролевая игра"), developers = listOf("Square"),
    )

    private fun page(cover: String, scale: Float, shot: String) {
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(
                LocalStrings provides RU,
                LocalIconLoader provides FakeIcons,
                com.retrohome.launcher.ui.LocalCoverLoader provides FakeCovers,
                com.retrohome.launcher.ui.LocalRemoteImages provides FakeBadges,
                com.retrohome.launcher.audio.LocalSfx provides RecordingSfx(),
                LocalPadRegistry provides pads,
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density * scale, base.fontScale),
            ) {
                com.retrohome.launcher.ui.console.GamePage(pageEntry(cover), 0, androidx.compose.foundation.rememberScrollState(), "✕", "○", true,
                    onButton = {}, onBack = {}, onOptions = {}, onFavorite = {}, onSwitch = {})
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/$shot")
    }

    /** Квадратная обложка GBA показывается целиком, рамка — квадратная. */
    @Test
    @Config(qualifiers = "w960dp-h540dp-land-xhdpi")
    fun squareCoverIsNotCropped() {
        page("FF5 [square].webp", 1f, "40_page_square_cover.png")
        val node = compose.onNodeWithTag("pageCover", useUnmergedTree = true).fetchSemanticsNode()
        val r = node.size
        assertTrue("рамка ${r.width}×${r.height} должна быть квадратной", kotlin.math.abs(r.width - r.height) <= 2)
    }

    /** Телефон друга: экран 411 dp в высоту. Без масштаба описание зажато, с авто-масштабом — как на Retroid. */
    @Test
    @Config(qualifiers = "w911dp-h411dp-land-xxhdpi")
    fun shortScreenIsScaled() {
        page("FF3 [wide].webp", 1f, "41_page_short_screen_no_scale.png")
        assertEquals(0.76f, com.retrohome.launcher.ui.UiScale.auto(411), 0.01f)
    }

    @Test
    @Config(qualifiers = "w911dp-h411dp-land-xxhdpi")
    fun shortScreenAutoScale() {
        page("FF3 [wide].webp", com.retrohome.launcher.ui.UiScale.auto(411), "42_page_short_screen_auto_scale.png")
    }

    @Test fun scaleRules() {
        assertEquals(1f, com.retrohome.launcher.ui.UiScale.auto(540), 0.001f)
        assertEquals(1f, com.retrohome.launcher.ui.UiScale.auto(800), 0.001f)
        assertEquals(0.7f, com.retrohome.launcher.ui.UiScale.auto(300), 0.001f)
        assertEquals(0.9f, com.retrohome.launcher.ui.UiScale.factor(90, 411), 0.001f)
    }
}


/** Настройки по разделам, возврат на прежнее место, звуки; листание курками; переключение игры пальцем; правый стик. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class NavigationUiTest {
    @get:Rule val compose = createComposeRule()
    private val pads = PadRegistry()
    private val sfx = RecordingSfx()

    private fun show(stick: androidx.compose.runtime.State<Float> = mutableStateOf(0f), sounds: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            CompositionLocalProvider(
                LocalStrings provides RU,
                LocalIconLoader provides FakeIcons,
                com.retrohome.launcher.ui.LocalCoverLoader provides FakeCovers,
                com.retrohome.launcher.ui.LocalRemoteImages provides FakeBadges,
                com.retrohome.launcher.audio.LocalSfx provides sfx,
                LocalPadRegistry provides pads,
                com.retrohome.launcher.ui.LocalRightStick provides stick,
                com.retrohome.launcher.ui.LocalScreenSounds provides sounds,
            ) { content() }
        }
    }

    private val noop = object : SettingsActions {
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
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun settingsSectionsRememberWhereYouCameFrom() {
        val nav = com.retrohome.launcher.ui.SettingsNav()
        val open = mutableStateOf(true)
        show(sounds = true) {
            // Как на приставке: управление кнопками (в сенсорном режиме Android курсор не показывает)
            val im = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.LaunchedEffect(Unit) { im.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            if (open.value) SettingsScreen(Settings(), "0.1", focusFirst = true, onBack = {}, a = noop, nav = nav)
        }
        // Главный экран настроек — только разделы
        compose.onNodeWithText(RU.secGames).assertExists()
        compose.onAllNodesWithText(RU.emulators).assertCountEquals(0)
        // Заходим в «Игры», выбираем «Эмуляторы» (вложенный экран)
        compose.onNodeWithText(RU.secGames).performClick()
        compose.onNodeWithText(RU.emulators).performClick()
        compose.runOnIdle { assertEquals("emulators", nav.memory("GAMES").last); assertTrue(sfx.played.contains(Sfx.SELECT)) }
        // Вложенный экран закрыли — настройки снова на экране, курсор там, откуда заходили
        compose.runOnIdle { open.value = false }
        compose.runOnIdle { open.value = true }
        compose.waitForIdle()
        compose.onNodeWithText(RU.emulators).assertIsFocused()
        // Назад из раздела — к списку разделов, курсор на «Игры»
        compose.onAllNodesWithText("‹")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText(RU.secGames).assertIsFocused()
        compose.runOnIdle { assertTrue(sfx.played.contains(Sfx.BACK)) }
        compose.onRoot().captureRoboImage("$SHOTS/50_settings_menu.png")
    }

    @OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun settingsCursorStopsAtListEdgesAndDpadSounds() {
        val nav = com.retrohome.launcher.ui.SettingsNav()
        nav.section = "GAMES"
        show(sounds = true) {
            val im = androidx.compose.ui.platform.LocalInputModeManager.current
            androidx.compose.runtime.LaunchedEffect(Unit) { im.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
            // Как в приложении: под настройками лежит главный экран со своими кнопками
            androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                androidx.compose.foundation.layout.Box(
                    Modifier.align(androidx.compose.ui.Alignment.BottomCenter)
                        .testTag("under")
                        .focusable()
                ) { androidx.compose.material3.Text("под низом") }
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
                    SettingsScreen(Settings(), "0.1", focusFirst = true, onBack = {}, a = noop, nav = nav)
                }
            }
        }
        compose.waitForIdle()
        val first = focusedText()
        // Курсор, поставленный при открытии, — без звука
        compose.runOnIdle { assertEquals(0, sfx.played.count { it == Sfx.MOVE }) }
        // Вниз до упора и ещё дальше
        repeat(40) { compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }; compose.waitForIdle() }
        compose.onNodeWithTag("under").assertIsNotFocused()
        val last = focusedText()
        assertNotEquals(first, last)
        val moves = sfx.played.count { it == Sfx.MOVE }
        assertTrue("крестовина звучит: $moves", moves >= 3)
        // У последней строки курсор упирается: ещё вниз — стоит на месте и не звучит
        compose.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        compose.waitForIdle()
        assertEquals(last, focusedText())
        assertEquals(moves, sfx.played.count { it == Sfx.MOVE })
        // Вверх — сразу на соседнюю строку, со звуком
        compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        compose.waitForIdle()
        assertNotEquals(last, focusedText())
        assertEquals(moves + 1, sfx.played.count { it == Sfx.MOVE })
        // Вверх до упора: курсор не уходит ни на «‹» в заголовке, ни куда-либо ещё
        repeat(40) { compose.onRoot().performKeyInput { pressKey(Key.DirectionUp) }; compose.waitForIdle() }
        assertEquals(first, focusedText())
        compose.onAllNodesWithText("‹")[0].assertIsNotFocused()
        compose.onNodeWithTag("under").assertIsNotFocused()
    }

    private fun focusedText(): String {
        val node = compose.onNode(androidx.compose.ui.test.isFocused()).fetchSemanticsNode()
        val texts = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            if (androidx.compose.ui.semantics.SemanticsProperties.Text in n.config)
                n.config[androidx.compose.ui.semantics.SemanticsProperties.Text].forEach { t -> texts.add(t.text) }
            n.children.forEach { walk(it) }
        }
        walk(node)
        return texts.joinToString(" | ")
    }

    private fun gcGames(): com.retrohome.launcher.ui.console.GamesInfo {
        val games = listOf("Skies of Arcadia", "Tales of Symphonia", "Wind Waker").mapIndexed { i, t ->
            com.retrohome.launcher.data.Game("g$i", "/r/g$i.iso", GS.GC, t, emptyList(), "gc")
        }
        val meta = games.associate { it.id to com.retrohome.launcher.data.GameMeta(cover = it.title + ".webp", descRu = "Описание игры. ".repeat(80)) }
        return com.retrohome.launcher.ui.console.GamesInfo(games, true, meta) { "Dolphin" }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun triggersScrollTheCarouselWhenChosen() {
        val keys = mutableStateOf("triggers")
        val state = ConsoleState().apply { tabId = "sys:GC" }
        val tabs = buildConsoleTabs(sampleApps, sampleData, RU, gcGames())
        show { ConsoleHome(tabs, state, ModeOverride.AUTO, false, RecordingConsoleActions(), carouselKeys = keys.value) }
        val first = compose.runOnIdle { state.selected(tabs)!!.key }
        // Только курки: крестовина ряд не листает, R2 — листает
        compose.runOnIdle { pads.dispatch(Pad.RIGHT) }
        compose.runOnIdle { assertEquals(first, state.selected(tabs)!!.key) }
        compose.runOnIdle { pads.dispatch(Pad.R2) }
        val second = compose.runOnIdle { state.selected(tabs)!!.key }
        assertTrue(second != first)
        compose.runOnIdle { pads.dispatch(Pad.L2) }
        compose.runOnIdle { assertEquals(first, state.selected(tabs)!!.key) }
        // «Крестовина» (по умолчанию): курки ряд не листают
        compose.runOnIdle { keys.value = "dpad" }
        compose.runOnIdle { pads.dispatch(Pad.R2) }
        compose.runOnIdle { assertEquals(first, state.selected(tabs)!!.key) }
        compose.runOnIdle { pads.dispatch(Pad.RIGHT) }
        compose.runOnIdle { assertEquals(second, state.selected(tabs)!!.key) }
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun gamesRowShowsOnlyTitleAndBigCovers() {
        val state = ConsoleState().apply { tabId = "sys:GC" }
        val tabs = buildConsoleTabs(sampleApps, sampleData, RU, gcGames())
        show { ConsoleHome(tabs, state, ModeOverride.AUTO, false, RecordingConsoleActions()) }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("$SHOTS/52_games_row.png")
        // Под рядом — только название; описания и прочих сведений нет
        compose.onNodeWithTag("rowTitle", useUnmergedTree = true).assertTextEquals("Skies of Arcadia")
        compose.onAllNodesWithText("Описание игры.", substring = true).assertCountEquals(0)
        // Обложки заметно крупнее прежнего потолка в 168dp
        val h = compose.onAllNodesWithText("Tales of Symphonia", useUnmergedTree = true).fetchSemanticsNodes().size
        val card = compose.onRoot().fetchSemanticsNode()
        val density = card.layoutInfo.density.density
        val cards = compose.onAllNodes(androidx.compose.ui.test.hasClickAction()).fetchSemanticsNodes()
            .map { it.boundsInRoot.height / density }
        assertTrue("высота карточек: $cards ($h)", cards.any { it > 200f })
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun rightStickScrollsTheDescription() {
        val stick = androidx.compose.runtime.mutableFloatStateOf(0f)
        val state = ConsoleState().apply { tabId = "sys:GC" }
        val tabs = buildConsoleTabs(sampleApps, sampleData, RU, gcGames())
        show(stick) { ConsoleHome(tabs, state, ModeOverride.AUTO, false, RecordingConsoleActions()) }
        compose.runOnIdle { pads.dispatch(Pad.CONFIRM) }
        compose.mainClock.advanceTimeBy(600)
        compose.runOnIdle { assertTrue(state.page != null); assertEquals(0, state.pageScroll.value) }
        // Пока стик наклонён, прокрутка идёт каждый кадр — время двигаем вручную
        compose.mainClock.autoAdvance = false
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { stick.floatValue = 1f }
        compose.mainClock.advanceTimeBy(400)
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { stick.floatValue = 0f }
        compose.mainClock.advanceTimeBy(100)
        val down = state.pageScroll.value
        assertTrue("описание прокрутилось вниз: $down", down > 100)
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { stick.floatValue = -1f }
        compose.mainClock.advanceTimeBy(800)
        androidx.compose.runtime.snapshots.Snapshot.withMutableSnapshot { stick.floatValue = 0f }
        compose.mainClock.advanceTimeBy(100)
        assertTrue(state.pageScroll.value < down)
        compose.mainClock.autoAdvance = true
    }

    @Test
    @Config(qualifiers = "w911dp-h411dp-land-xxhdpi")
    fun fingerSwitchesGamesOnThePage() {
        val switched = mutableListOf<Int>()
        val e = com.retrohome.launcher.ui.console.ConsoleEntry(
            key = "game:g0", label = "Skies of Arcadia", subtitle = "GC", favorite = false, zone = ConsoleZone.GAME,
            kind = com.retrohome.launcher.ui.console.EntryKind.GAME, system = GS.GC, cover = "Skies of Arcadia.webp",
        )
        show {
            com.retrohome.launcher.ui.console.GamePage(e, 0, androidx.compose.foundation.rememberScrollState(), "A", "B", true,
                onButton = {}, onBack = {}, onOptions = {}, onFavorite = {}, onSwitch = { switched += it })
        }
        compose.onRoot().captureRoboImage("$SHOTS/51_page_arrows.png")
        // Стрелки у краёв страницы, под большими пальцами: левая — слева от обложки, правая — у правого края
        val cover = compose.onNodeWithTag("pageCover", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val prev = compose.onNodeWithTag("prevGame", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val next = compose.onNodeWithTag("nextGame", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val width = compose.onRoot().fetchSemanticsNode().boundsInRoot.width
        assertTrue("левая стрелка не на обложке", prev.right <= cover.left)
        assertTrue("правая стрелка у правого края", next.left > cover.right && width - next.right < width * 0.06f)
        compose.onNodeWithTag("nextGame", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("prevGame", useUnmergedTree = true).performClick()
        // Свайп по странице: влево — следующая игра, вправо — предыдущая
        compose.onRoot().performTouchInput { swipeLeft() }
        compose.onRoot().performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(listOf(1, -1, 1, -1), switched) }
    }
}
