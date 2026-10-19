package com.retrohome.launcher

import android.app.Application
import android.content.Intent
import android.net.Uri
import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.Emulators
import com.retrohome.launcher.data.FOLDER_IGNORE
import com.retrohome.launcher.data.FolderNames
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.JavaFileSource
import com.retrohome.launcher.data.LaunchContext
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.Launchers
import com.retrohome.launcher.data.Recent
import com.retrohome.launcher.data.Scanner
import com.retrohome.launcher.data.TitleParser
import com.retrohome.launcher.data.docIdToPath
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.ui.console.ACTION_PICK_ROMS
import com.retrohome.launcher.ui.console.EntryKind
import com.retrohome.launcher.ui.console.GamesInfo
import com.retrohome.launcher.ui.console.buildConsoleTabs
import com.retrohome.launcher.ui.romsLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

class ScannerTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String, text: String = "x") {
        val f = File(tmp.root, path)
        f.parentFile!!.mkdirs()
        f.writeText(text)
    }

    private fun buildRoms(): File {
        file("ROMs/PS2/God of War (USA).iso")
        file("ROMs/PS2/Ico (Europe) (En,Fr).chd")
        file("ROMs/PS2/notes.txt")
        file("ROMs/psx/Final Fantasy VII (USA).m3u", "Final Fantasy VII (USA) (Disc 1).chd\nFinal Fantasy VII (USA) (Disc 2).chd\n")
        file("ROMs/psx/Final Fantasy VII (USA) (Disc 1).chd")
        file("ROMs/psx/Final Fantasy VII (USA) (Disc 2).chd")
        file("ROMs/psx/Crash Bandicoot (USA).cue", "FILE \"Crash Bandicoot (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n")
        file("ROMs/psx/Crash Bandicoot (USA).bin")
        file("ROMs/psp/Patapon (Europe).cso")
        file("ROMs/GBA/Pokemon - Emerald Version (USA, Europe).gba")
        file("ROMs/GBA/media/cover.png")
        file("ROMs/GBA/.hidden.gba")
        file("ROMs/3DS/Zelda - A Link Between Worlds.3ds")
        file("ROMs/Switch/Super Mario Odyssey/Super Mario Odyssey [0100000000010000].nsp")
        file("ROMs/Unknown Stuff/x.bin")
        file("ROMs/MyGames/Shadow of the Colossus.iso")
        File(tmp.root, "ROMs/NES").mkdirs()
        return File(tmp.root, "ROMs")
    }

    @Test fun findsGamesByFolderAndHidesDiscParts() {
        val r = Scanner.scan(JavaFileSource(buildRoms()), overrides = emptyMap(), now = 1)
        val titles = r.games.groupBy({ it.system }, { it.title })
        assertEquals(listOf("God of War", "Ico"), titles[GameSystem.PS2]!!.sorted())
        // .m3u скрывает отдельные диски, .cue скрывает дорожку .bin
        assertEquals(listOf("Crash Bandicoot", "Final Fantasy VII"), titles[GameSystem.PS1]!!.sorted())
        assertEquals(listOf("Patapon"), titles[GameSystem.PSP])
        assertEquals(listOf("Pokemon - Emerald Version"), titles[GameSystem.GBA])
        assertEquals(listOf("Zelda - A Link Between Worlds"), titles[GameSystem.N3DS])
        assertEquals(listOf("Super Mario Odyssey"), titles[GameSystem.SWITCH])
        assertEquals(listOf("USA", "Europe"), r.games.first { it.title == "Pokemon - Emerald Version" }.tags.flatMap { it.split(", ") })

        val unknown = r.folders.first { it.name == "Unknown Stuff" }
        assertNull(unknown.system)
        assertEquals(0, unknown.gameCount)
        assertEquals(2, r.folders.first { it.name == "psx" }.gameCount)
        assertEquals(GameSystem.NES, r.folders.first { it.name == "NES" }.system)
        assertTrue(r.games.none { it.system == GameSystem.NES })
    }

    @Test fun manualFolderAssignment() {
        val roms = buildRoms()
        val r = Scanner.scan(JavaFileSource(roms), mapOf("MyGames" to "PS2", "psp" to FOLDER_IGNORE))
        assertTrue(r.games.any { it.title == "Shadow of the Colossus" && it.system == GameSystem.PS2 && it.folder == "MyGames" })
        assertTrue(r.games.none { it.system == GameSystem.PSP })
        assertEquals(GameSystem.PSP, r.folders.first { it.name == "psp" }.detected)
        assertNull(r.folders.first { it.name == "psp" }.system)
    }

    @Test fun folderNameVariants() {
        assertEquals(GameSystem.PS1, FolderNames.systemFor("PSX"))
        assertEquals(GameSystem.PS1, FolderNames.systemFor("PlayStation"))
        assertEquals(GameSystem.MD, FolderNames.systemFor("Mega Drive"))
        assertEquals(GameSystem.MD, FolderNames.systemFor("genesis"))
        assertEquals(GameSystem.N3DS, FolderNames.systemFor("3ds"))
        assertEquals(GameSystem.GC, FolderNames.systemFor("GameCube"))
        assertEquals(GameSystem.GBC, FolderNames.systemFor("Game Boy Color"))
        assertEquals(GameSystem.SWITCH, FolderNames.systemFor("switch"))
        assertNull(FolderNames.systemFor("Downloads"))
    }

    @Test fun titles() {
        assertEquals(
            "The Legend of Zelda - Ocarina of Time" to listOf("USA", "!"),
            TitleParser.parse("Legend of Zelda, The - Ocarina of Time (USA) [!].z64"),
        )
        assertEquals("Metroid Prime" to listOf("USA"), TitleParser.parse("Metroid_Prime (USA).nkit.iso"))
        assertEquals("Tekken 3" to emptyList<String>(), TitleParser.parse("Tekken 3.chd"))
    }

    @Test fun paths() {
        assertEquals("/storage/1234-ABCD/ROMs/psp/x.iso", docIdToPath("1234-ABCD:ROMs/psp/x.iso"))
        assertEquals("/storage/emulated/0/ROMs", docIdToPath("primary:ROMs"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class LaunchRecipeTest {
    private val uri = Uri.parse("content://com.android.externalstorage.documents/tree/1234-ABCD%3AROMs/document/1234-ABCD%3AROMs%2Fpsp%2Fx.iso")

    private fun build(id: String, pkg: String, system: GameSystem, activityIndex: Int = 0): Intent {
        val r = Launchers.byId(id)!!
        return r.build(LaunchContext(pkg, r.activities[activityIndex], uri, "/storage/1234-ABCD/ROMs/x.iso", system))
    }

    @Test fun userEmulatorsHaveRecipes() {
        val installed = setOf(
            "org.ppsspp.ppsspp", "xyz.aethersx2.cturnip", "me.magnum.melonds", "com.github.stenzek.duckstation",
            "org.citra.citra_emu", "dev.eden.eden_emulator", "com.retroarch.aarch64", "com.swordfish.lemuroid",
        )
        assertEquals("ppsspp", Launchers.choose(GameSystem.PSP, installed, null)!!.id)
        assertEquals("sx2", Launchers.choose(GameSystem.PS2, installed, null)!!.id)
        assertEquals("xyz.aethersx2.cturnip", Launchers.installedPackage(Launchers.byId("sx2")!!, installed))
        assertEquals("melonds", Launchers.choose(GameSystem.NDS, installed, null)!!.id)
        assertEquals("duckstation", Launchers.choose(GameSystem.PS1, installed, null)!!.id)
        assertEquals("citra", Launchers.choose(GameSystem.N3DS, installed, null)!!.id)
        assertEquals("eden", Launchers.choose(GameSystem.SWITCH, installed, null)!!.id)
        // Game Boy: Lemuroid игры извне не принимает, поэтому RetroArch
        assertEquals("retroarch", Launchers.choose(GameSystem.GB, installed, null)!!.id)
        // Ручной выбор: PS1 через RetroArch
        assertEquals("retroarch", Launchers.choose(GameSystem.PS1, installed, "retroarch")!!.id)
        // Выбранного эмулятора нет — берётся доступный
        assertEquals("duckstation", Launchers.choose(GameSystem.PS1, installed, "epsxe")!!.id)
        assertNull(Launchers.choose(GameSystem.DC, setOf("org.ppsspp.ppsspp"), null))
    }

    @Test fun intentsMatchKnownFormats() {
        val psp = build("ppsspp", "org.ppsspp.ppsspp", GameSystem.PSP)
        assertEquals("org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity", psp.component!!.flattenToString())
        assertEquals(Intent.ACTION_VIEW, psp.action)
        assertEquals(uri, psp.data)
        assertEquals("application/octet-stream", psp.type)
        assertTrue(psp.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        val ps2 = build("sx2", "xyz.aethersx2.cturnip", GameSystem.PS2)
        assertEquals("xyz.aethersx2.android.EmulationActivity", ps2.component!!.className)
        assertEquals(uri.toString(), ps2.getStringExtra("bootPath"))
        assertEquals(uri, ps2.clipData!!.getItemAt(0).uri)

        val nds = build("melonds", "me.magnum.melonds", GameSystem.NDS)
        assertEquals("me.magnum.melonds.LAUNCH_ROM", nds.action)
        assertEquals(uri.toString(), nds.getStringExtra("uri"))

        val sw = build("eden", "dev.eden.eden_emulator", GameSystem.SWITCH)
        assertEquals("android.nfc.action.TECH_DISCOVERED", sw.action)
        assertEquals("org.yuzu.yuzu_emu.activities.EmulationActivity", sw.component!!.className)

        val ra = build("retroarch", "com.retroarch.aarch64", GameSystem.GBA)
        assertEquals("/storage/1234-ABCD/ROMs/x.iso", ra.getStringExtra("ROM"))
        assertEquals("/data/data/com.retroarch.aarch64/cores/mgba_libretro_android.so", ra.getStringExtra("LIBRETRO"))
        assertEquals("/storage/emulated/0/Android/data/com.retroarch.aarch64/files/retroarch.cfg", ra.getStringExtra("CONFIGFILE"))
    }

    @Test fun folderLabel() {
        assertEquals("SD: ROMs", romsLabel("content://com.android.externalstorage.documents/tree/1234-ABCD%3AROMs"))
        assertEquals("Internal: Games/ROMs", romsLabel("content://com.android.externalstorage.documents/tree/primary%3AGames%2FROMs"))
    }
}

class ConsoleGamesTabsTest {
    private val ppsspp = AppInfo("org.ppsspp.ppsspp/a", "org.ppsspp.ppsspp", "PPSSPP", false, Emulators.match("org.ppsspp.ppsspp", "PPSSPP"))
    private val games = listOf(
        Game("1", "/r/ps2/b.iso", GameSystem.PS2, "Burnout 3", listOf("USA"), "ps2"),
        Game("2", "/r/psp/p.cso", GameSystem.PSP, "Patapon", emptyList(), "psp"),
        Game("3", "/r/ps2/a.iso", GameSystem.PS2, "Ape Escape 3", emptyList(), "ps2"),
    )

    @Test fun noFolderShowsPickTile() {
        val tabs = buildConsoleTabs(listOf(ppsspp), LauncherData(), RU, GamesInfo())
        assertEquals(2, tabs[0].rows.size)
        val first = tabs[0].rows[0].entries.first()
        assertEquals(ACTION_PICK_ROMS, first.key)
        assertEquals(EntryKind.ACTION, first.kind)
        assertEquals(listOf("home", "emulators", "games"), tabs.map { it.id })
    }

    @Test fun systemTabsInConsoleOrderWithRunner() {
        val gi = GamesInfo(games, hasFolder = true) { if (it == GameSystem.PSP) "PPSSPP" else null }
        val d = LauncherData(recents = listOf(Recent("game:2", 10)), favorites = listOf("game:3"))
        val tabs = buildConsoleTabs(listOf(ppsspp), d, RU, gi)
        assertEquals(listOf("home", "sys:PS2", "sys:PSP", "emulators", "games"), tabs.map { it.id })
        assertEquals(listOf("Ape Escape 3", "Burnout 3"), tabs[1].rows[0].entries.map { it.label })
        assertEquals("PS2 · USA · нет эмулятора", tabs[1].rows[0].entries[1].subtitle)
        assertEquals("PSP · запуск в PPSSPP", tabs[2].rows[0].entries[0].subtitle)
        // Игры попадают в недавние и избранное
        assertEquals(listOf("Patapon"), tabs[0].rows[0].entries.map { it.label })
        assertEquals(listOf("Ape Escape 3"), tabs[0].rows[1].entries.map { it.label })
        assertTrue(tabs[0].rows[1].entries[0].favorite)
    }
}
