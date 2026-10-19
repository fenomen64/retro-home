package com.retrohome.launcher

import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.JavaFileSource
import com.retrohome.launcher.data.Scanner
import com.retrohome.launcher.data.Thumbnails
import com.retrohome.launcher.data.TitleParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Имена файлов взяты со скриншотов реальной коллекции. */
class RealNamesTest {
    private fun list(name: String) = Thumbnails.Index(
        javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readLines()
    )
    private val ps1 by lazy { list("Sony_-_PlayStation.txt") }
    private val psp by lazy { list("Sony_-_PlayStation_Portable.txt") }
    private val gba by lazy { list("Nintendo_-_Game_Boy_Advance.txt") }
    private val gc by lazy { list("Nintendo_-_GameCube.txt") }
    private val dc by lazy { list("Sega_-_Dreamcast.txt") }

    @Test fun titlesAreCleaned() {
        val r = TitleParser.full("Rayman Advance (U) [T+Rus1.2 Djinn(MagicGame)].gba.zip")
        assertEquals("Rayman Advance", r.title)
        assertEquals(listOf("USA", "RUS"), TitleParser.displayTags(r.tags))
        val p = TitleParser.full("Pokemon - Emerald Version (UE) [T+Rus0.9.0_beta kozzztya (08.07.2015)].gba.zip")
        assertEquals("Pokemon - Emerald Version", p.title)
        assertEquals(listOf("USA, Europe", "RUS"), TitleParser.displayTags(p.tags))

        assertEquals("Final Fantasy VII" to 1, TitleParser.full("ff7 rus disk1.chd").let { it.title to it.disc })
        assertEquals("Final Fantasy 9" to 4, TitleParser.full("Final Fantasy 9- CD4 (RUS) (Kudos).chd").let { it.title to it.disc })
        assertEquals("Breath of Fire III", TitleParser.full("BoF3 rus v1.01.bin").title)
        assertEquals("Metal Gear Solid" to 2, TitleParser.full("mgs cd2.chd").let { it.title to it.disc })
        assertEquals("WILDARMS2" to 1, TitleParser.full("WILDARMS2 cd 1.chd").let { it.title to it.disc })
        assertEquals("Grandia" to 2, TitleParser.full("Grandia Undub CD2.chd").let { it.title to it.disc })
        assertEquals("Lunar Silver Star Story Complete", TitleParser.full("Lunar Silver Star Story Complete CD1.cue").title)
        assertEquals("Shenmue", TitleParser.full("Shenmue v1.001 (2000)(Sega)(PAL)(M5)[!].gdi").title)
        assertEquals("Skies of Arcadia Legends", TitleParser.full("Skies of Arcadia Legends Undub.iso").title)
        val sw = TitleParser.full("Pokemon Brilliant Diamond [0100000011D90000][v0].nsp")
        assertEquals("Pokemon Brilliant Diamond", sw.title)
        assertTrue(TitleParser.displayTags(sw.tags).isEmpty())
        assertEquals("Pokemon Odyssey", TitleParser.full("Pokemon Odyssey v4.1.1.gba").title)
    }

    @Test fun coversForRenamedGames() {
        assertEquals("Final Fantasy VII (USA)", Thumbnails.match("ff7 rus disk1.chd", ps1))
        assertEquals("Final Fantasy IX (USA)", Thumbnails.match("Final Fantasy 9- CD1 (RUS) (Kudos).chd", ps1))
        assertEquals("Breath of Fire III (USA)", Thumbnails.match("BoF3 rus v1.01.bin", ps1))
        assertEquals("Metal Gear Solid (USA)", Thumbnails.match("mgs cd1.chd", ps1))
        assertEquals("Wild Arms 2 (USA)", Thumbnails.match("WILDARMS2 cd 1.chd", ps1))
        assertEquals("Grandia (USA)", Thumbnails.match("Grandia Undub CD1.chd", ps1))
        assertEquals("Lunar - Silver Star Story Complete (USA)", Thumbnails.match("Lunar Silver Star Story Complete CD1.cue", ps1))

        assertEquals("Legend of Heroes II, The - Prophecy of the Moonlight Witch (USA)",
            Thumbnails.match("Legend of Heroes - Prophecy of the Moonlight Witch.iso", psp))
        assertEquals("Legend of Heroes III, The - Song of the Ocean (USA)",
            Thumbnails.match("Legend of Heroes - Song of the Ocean.iso", psp))
        assertEquals("Legend of Heroes, The - A Tear of Vermillion (USA)",
            Thumbnails.match("Legend of Heroes - Tears of Vermillion.iso", psp))
        assertEquals("Legend of Heroes - Trails in the Sky - Second Chapter (Europe) (PSN)",
            Thumbnails.match("The Legend of Heroes Trails in The Sky Second Chapter (Disc 1).iso", psp))

        assertEquals("Rayman Advance (USA) (En,Fr,De,Es,It)", Thumbnails.match("Rayman Advance (U) [T+Rus1.2 Djinn(MagicGame)].gba.zip", gba))
        assertEquals("Pokemon - Emerald Version (USA, Europe)",
            Thumbnails.match("Pokemon - Emerald Version (UE) [T+Rus0.9.0_beta kozzztya (08.07.2015)].gba.zip", gba))
        assertEquals("Final Fantasy V Advance (USA)", Thumbnails.match("Final Fantasy V Advance (U) [T+Rus].gba.zip", gba))
        assertEquals("Final Fantasy VI Advance (USA)", Thumbnails.match("Final Fantasy VI Advance (U) [T+Rus].gba.zip", gba))
        assertEquals("Breath of Fire (USA)", Thumbnails.match("Breath of Fire (U) [T+Rus].gba.zip", gba))
        assertEquals("Metal Slug Advance (USA)", Thumbnails.match("Metal Slug Advance (U) [T+Rus].gba.zip", gba))
        assertEquals("Rayman 3 (USA) (En,Fr,Es)", Thumbnails.match("Rayman 3 (U) [T+Rus].gba.zip", gba))

        assertEquals("Skies of Arcadia Legends (Europe) (En,Fr,De,Es)", Thumbnails.match("Skies of Arcadia Legends Undub (Europe).iso", gc))
        assertEquals("Shenmue (USA)", Thumbnails.match("Shenmue v1.001 (2000)(Sega)(US)[!].gdi", dc))
        assertEquals("Shenmue II (Europe) (En,Fr,De,Es)", Thumbnails.match("Shenmue II v1.001 (2001)(Sega)(PAL)[!].gdi", dc))
    }

    @Test fun noFalseMatches() {
        // Номер части обязан совпасть: «Spider-Man 3» — не «Spider-Man»
        val m = Thumbnails.match("Spider-Man 3 (U) [T+Rus].gba.zip", gba)
        assertTrue(m == null || m.startsWith("Spider-Man 3"))
        assertNull(Thumbnails.match("Pokemon Odyssey v4.1.1.gba", gba))
        // «Final Fantasy V» не путается с «VI»
        assertTrue(Thumbnails.match("Final Fantasy V Advance.gba", gba)!!.startsWith("Final Fantasy V Advance"))
    }

    @Test fun officialNames() {
        assertEquals("The Legend of Heroes II - Prophecy of the Moonlight Witch",
            Thumbnails.displayName("Legend of Heroes II, The - Prophecy of the Moonlight Witch (USA)"))
        assertEquals("Final Fantasy IX", Thumbnails.displayName("Final Fantasy IX (USA) (Disc 1)"))
    }
}

class DiscGroupingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(path: String) {
        val f = File(tmp.root, path); f.parentFile!!.mkdirs(); f.writeText("x")
    }

    @Test fun discsBecomeOneGame() {
        listOf("ff7 rus disk1.chd", "ff7 rus disk2.chd", "ff7 rus disk3.chd", "mgs cd1.chd", "mgs cd2.chd",
            "BoF3 rus v1.01.chd", "Silent Hill (USA).chd").forEach { file("ROMs/psx/$it") }
        listOf("Pokemon Brilliant Diamond [0100000011D90000][v0].nsp", "Pokemon Brilliant Diamond [0100000011D90800][v196608].nsp")
            .forEach { file("ROMs/switch/$it") }
        val r = Scanner.scan(JavaFileSource(File(tmp.root, "ROMs")), emptyMap())
        val ps1 = r.games.filter { it.system == GameSystem.PS1 }.associateBy { it.title }
        assertEquals(setOf("Final Fantasy VII", "Metal Gear Solid", "Breath of Fire III", "Silent Hill"), ps1.keys)
        assertEquals(listOf(1, 2, 3), ps1["Final Fantasy VII"]!!.discs.map { it.number })
        assertTrue(ps1["Final Fantasy VII"]!!.path.endsWith("disk1.chd"))
        assertTrue(ps1["Silent Hill"]!!.discs.isEmpty())
        // Обновление Switch не показывается отдельной игрой
        assertEquals(1, r.games.count { it.system == GameSystem.SWITCH })
    }
}
