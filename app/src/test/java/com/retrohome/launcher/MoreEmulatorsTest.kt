package com.retrohome.launcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.retrohome.launcher.data.Emulators
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.JavaFileSource
import com.retrohome.launcher.data.LaunchContext
import com.retrohome.launcher.data.Launchers
import com.retrohome.launcher.data.Lemuroid
import com.retrohome.launcher.data.Scanner
import com.retrohome.launcher.data.TitleParser
import com.retrohome.launcher.data.VitaGames
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lemuroid, эмуляторы Explusalpha (MD.emu и др.) и игры PS Vita. */
class MoreEmulatorsLogicTest {
    @After fun reset() { Lemuroid.links = emptyMap() }

    @Test fun explusalphaEmulatorsAreRecognized() {
        assertEquals("mdemu", Emulators.match("com.explusalpha.MdEmu", "MD.emu")!!.id)
        assertEquals(listOf(GameSystem.MD, GameSystem.SMS, GameSystem.GG), Emulators.match("com.explusalpha.MdEmu", "MD.emu")!!.systems)
        assertEquals("nesemu", Emulators.match("com.explusalpha.NesEmu", "NES.emu")!!.id)
        assertEquals("snes9xplus", Emulators.match("com.explusalpha.Snes9xPlus", "Snes9x EX+")!!.id)
        assertEquals("gbaemu", Emulators.match("com.explusalpha.GbaEmu", "GBA.emu")!!.id)
        assertEquals("saturnemu", Emulators.match("com.explusalpha.SaturnEmu", "Saturn.emu")!!.id)
    }

    @Test fun mdEmuLaunchesMegaDriveDirectly() {
        assertEquals(listOf("mdemu"), Launchers.available(GameSystem.MD, setOf("com.explusalpha.MdEmu")).map { it.id })
        assertEquals(listOf("mdemu"), Launchers.available(GameSystem.GG, setOf("com.explusalpha.MdEmu")).map { it.id })
        assertTrue(Launchers.knows("com.explusalpha.MdEmu"))
    }

    @Test fun lemuroidIsOfferedForItsSystems() {
        val pkgs = setOf("com.swordfish.lemuroid")
        for (sys in listOf(GameSystem.NES, GameSystem.SNES, GameSystem.GBA, GameSystem.MD, GameSystem.PS1, GameSystem.PSP)) {
            assertEquals(sys.name, listOf("lemuroid"), Launchers.available(sys, pkgs).map { it.id })
        }
        // С RetroArch по умолчанию — RetroArch (запускает любой файл), Lemuroid выбирается вручную
        val both = setOf("com.swordfish.lemuroid", "com.retroarch.aarch64")
        assertEquals("retroarch", Launchers.choose(GameSystem.SNES, both, null)!!.id)
        assertEquals("lemuroid", Launchers.choose(GameSystem.SNES, both, "lemuroid")!!.id)
        assertTrue(Launchers.knows("com.swordfish.lemuroid"))
    }

    @Test fun lemuroidTitlesMatchOurGames() {
        assertEquals(Lemuroid.normalize("The Legend of Zelda - The Minish Cap"), Lemuroid.normalize("Legend of Zelda, The - The Minish Cap (USA)"))
        assertEquals(Lemuroid.normalize("Pokemon Emerald"), Lemuroid.normalize("Pokémon Emerald [!]"))
        assertEquals(Lemuroid.normalize("Sonic & Knuckles"), Lemuroid.normalize("Sonic and Knuckles (World)"))
        assertEquals(17, Lemuroid.gameIdOf("com.swordfish.lemuroid", "game_17"))
        assertNull(Lemuroid.gameIdOf("com.swordfish.lemuroid", "open_settings"))
        assertNull(Lemuroid.gameIdOf("com.other.app", "game_17"))
        Lemuroid.links = mapOf(Lemuroid.normalize("Super Mario World (USA)") to 5)
        assertEquals(5, Lemuroid.idFor(listOf("Super Mario World")))
        assertNull(Lemuroid.idFor(listOf("Super Mario Kart")))
    }

    @Test fun vitaGameIdFromStubOrName() {
        assertEquals("PCSE00120", VitaGames.titleId("Persona 4 Golden.psvita", "PCSE00120\n"))
        assertEquals("PCSB00245", VitaGames.titleId("Persona 4 Golden [PCSB00245].vpk", null))
        assertEquals("PCSB00245", VitaGames.titleId("game.psvita", "  pcsb00245  "))
        assertNull(VitaGames.titleId("Persona 4 Golden.vpk", null))
        // Номер в квадратных скобках — не регион, в подписи не показываем
        assertTrue(TitleParser.displayTags(listOf("PCSB00245", "USA")) == listOf("USA"))
    }

    @Test fun vitaFolderWithStubAndGameShowsOneEntry() {
        val root = kotlin.io.path.createTempDirectory("roms").toFile()
        try {
            val dir = java.io.File(root, "psvita").apply { mkdirs() }
            java.io.File(dir, "Persona 4 Golden.psvita").writeText("PCSE00120\n")
            java.io.File(dir, "Persona 4 Golden.vpk").writeText("zip")
            java.io.File(dir, "Gravity Rush [PCSA00011].vpk").writeText("zip")
            // Распакованная игра в папке: внутри нет файлов игр, в список не попадает
            java.io.File(dir, "PCSE00120/sce_sys").mkdirs()
            java.io.File(dir, "PCSE00120/eboot.bin").writeText("x")
            val games = Scanner.scan(JavaFileSource(root), emptyMap()).games
            assertEquals(listOf("Gravity Rush", "Persona 4 Golden"), games.map { it.title })
            assertEquals(listOf("PCSA00011", "PCSE00120"), games.map { it.titleId })
            assertTrue(games.last().fileName.endsWith(".psvita"))
        } finally { root.deleteRecursively() }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class MoreEmulatorsIntentTest {
    private val uri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3AROMs/document/primary%3AROMs%2Fmd%2FSonic.md")

    @After fun reset() { Lemuroid.links = emptyMap() }

    @Test fun mdEmuOpensFile() {
        val i = Launchers.byId("mdemu")!!.build(LaunchContext("com.explusalpha.MdEmu", "com.imagine.BaseActivity", uri, "/x/Sonic.md", GameSystem.MD))
        assertEquals("com.explusalpha.MdEmu", i.component!!.packageName)
        assertEquals("com.imagine.BaseActivity", i.component!!.className)
        assertEquals(Intent.ACTION_VIEW, i.action)
        assertEquals(uri, i.data)
        assertTrue(i.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /** Сохранения пишутся рядом с игрой — эмулятор получает доступ ко всей папке ROMs. */
    @Test fun explusalphaGetsWholeRomsFolder() {
        val tree = Uri.parse("content://com.android.externalstorage.documents/tree/F929-FA97%3AROMs")
        val i = Launchers.byId("gbaemu")!!.build(LaunchContext("com.explusalpha.GbaEmu", "com.imagine.BaseActivity", uri, "/x", GameSystem.GBA, tree = tree))
        val clip = i.clipData!!
        assertEquals(listOf(uri, tree), (0 until clip.itemCount).map { clip.getItemAt(it).uri })
        assertTrue(i.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION != 0)
        assertTrue(i.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertEquals(uri, i.data)
        // Без папки — только сам файл, без доступа «по префиксу»
        val plain = Launchers.byId("gbaemu")!!.build(LaunchContext("com.explusalpha.GbaEmu", "com.imagine.BaseActivity", uri, "/x", GameSystem.GBA))
        assertEquals(1, plain.clipData!!.itemCount)
        assertTrue(plain.flags and Intent.FLAG_GRANT_PREFIX_URI_PERMISSION == 0)
    }

    @Test fun vita3kGetsTitleId() {
        val r = Launchers.byId("vita3k")!!
        val i = r.build(LaunchContext("org.vita3k.emulator", "org.vita3k.emulator.Emulator", uri, "/x", GameSystem.VITA, gameId = "PCSE00120"))
        assertEquals("org.vita3k.emulator.Emulator", i.component!!.className)
        assertArrayEquals(arrayOf("-r", "PCSE00120"), i.getStringArrayExtra("AppStartParameters"))
        // Номера нет — открывается список игр Vita3K
        val open = r.build(LaunchContext("org.vita3k.emulator", "org.vita3k.emulator.Emulator", uri, "/x", GameSystem.VITA))
        assertEquals(Launchers.VITA3K_MAIN, open.component!!.className)
        assertNull(open.getStringArrayExtra("AppStartParameters"))
    }

    @Test fun lemuroidUsesShortcutNumberOrOpensLibrary() {
        val r = Launchers.byId("lemuroid")!!
        val ctx = LaunchContext("com.swordfish.lemuroid", Lemuroid.GAME_ACTIVITY, uri, "/x", GameSystem.MD, titles = listOf("Sonic the Hedgehog", "Sonic the Hedgehog (USA, Europe)"))
        val open = r.build(ctx)
        assertEquals(Lemuroid.MAIN_ACTIVITY, open.component!!.className)
        assertEquals(Intent.ACTION_MAIN, open.action)

        Lemuroid.links = mapOf(Lemuroid.normalize("Sonic The Hedgehog (USA, Europe)") to 42)
        val play = r.build(ctx)
        assertEquals(Intent.ACTION_VIEW, play.action)
        assertEquals(Lemuroid.GAME_ACTIVITY, play.component!!.className)
        assertEquals("lemuroid://com.swordfish.lemuroid/play-game/id/42", play.dataString)
    }

    @Test fun lemuroidLinksPersist() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        Lemuroid.remember(app, "Streets of Rage 2 (USA)", 7)
        Lemuroid.links = emptyMap()
        Lemuroid.load(app)
        assertEquals(7, Lemuroid.idFor(listOf("Streets of Rage 2")))
    }
}
