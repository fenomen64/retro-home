package com.retrohome.launcher

import com.retrohome.launcher.data.GameMeta
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.Thumbnails
import com.retrohome.launcher.data.Wiki
import com.retrohome.launcher.i18n.EN
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.ui.console.GamesInfo
import com.retrohome.launcher.ui.console.buildConsoleTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverMatchTest {
    /** Настоящий список обложек PS2 из libretro (8,5 тыс. имён). */
    private val ps2: List<String> by lazy {
        javaClass.classLoader!!.getResourceAsStream("ps2_boxarts.txt")!!.bufferedReader().readLines()
    }

    @Test fun exactFileName() {
        assertEquals("God of War II (USA)", Thumbnails.match("God of War II (USA).iso", ps2))
    }

    @Test fun titleWithoutTagsPrefersUsa() {
        assertEquals("God of War II (USA)", Thumbnails.match("God of War II.iso", ps2))
        assertEquals("Shadow of the Colossus (USA)", Thumbnails.match("Shadow of the Colossus.chd", ps2))
    }

    @Test fun regionFromFileWins() {
        assertEquals(
            "Shadow of the Colossus (Europe, Australia) (En,Fr,De,Es,It)",
            Thumbnails.match("Shadow of the Colossus (Europe).iso", ps2),
        )
    }

    @Test fun ampersandAndAvoidsDemos() {
        assertEquals("Ratchet _ Clank (USA) (En,Fr,De,Es,It)", Thumbnails.match("Ratchet & Clank (USA).iso", ps2))
        // У Ico есть пробная версия (Taikenban) — её брать не надо
        val ico = Thumbnails.match("Ico.iso", ps2)!!
        assertFalse(ico, ico.contains("Taikenban"))
    }

    @Test fun unknownGameHasNoCover() {
        assertNull(Thumbnails.match("Совсем Неизвестная Игра.iso", ps2))
    }

    @Test fun indexAndUrls() {
        val html = """<tr><td><a href="God%20of%20War%20II%20(USA).png">God of War II (USA).png</a></td></tr>
            <tr><td><a href="Ratchet%20_%20Clank.png">Ratchet _ Clank &amp; Co.png</a></td></tr>"""
        assertEquals(listOf("God of War II (USA)", "Ratchet _ Clank & Co"), Thumbnails.parseIndex(html))
        assertEquals(
            "https://thumbnails.libretro.com/Sony%20-%20PlayStation%202/Named_Boxarts/God%20of%20War%20II%20%28USA%29.png",
            Thumbnails.imageUrl(GameSystem.PS2, "God of War II (USA)"),
        )
        assertNull(Thumbnails.indexUrl(GameSystem.SWITCH))
    }
}

class WikiTest {
    @Test fun parsesApiResponses() {
        val search = """{"batchcomplete":true,"query":{"searchinfo":{"totalhits":3},"search":[
            {"ns":0,"title":"Shadow of the Colossus","pageid":1},{"ns":0,"title":"Ico","pageid":2}]}}"""
        assertEquals(listOf("Shadow of the Colossus", "Ico"), Wiki.parseSearch(search))

        val links = """{"batchcomplete":true,"query":{"pages":[{"pageid":1,"ns":0,"title":"Shadow of the Colossus",
            "langlinks":[{"lang":"ru","title":"Shadow of the Colossus"}]}]}}"""
        assertEquals("Shadow of the Colossus", Wiki.parseLangLink(links))
        assertNull(Wiki.parseLangLink("""{"query":{"pages":[{"pageid":1,"title":"X"}]}}"""))

        val summary = """{"type":"standard","title":"Shadow of the Colossus","description":"2005 video game",
            "extract":"Shadow of the Colossus is an action-adventure game..."}"""
        val s = Wiki.parseSummary(summary)!!
        assertTrue(Wiki.looksLikeGame(s, "en"))
        assertNull(Wiki.parseSummary("""{"type":"disambiguation","title":"Ico","extract":"Ico may refer to"}"""))

        val ru = Wiki.Summary("Shadow of the Colossus", "компьютерная игра 2005 года", "Shadow of the Colossus — компьютерная игра…")
        assertTrue(Wiki.looksLikeGame(ru, "ru"))
        assertFalse(Wiki.looksLikeGame(Wiki.Summary("Colossus", "statue", "A colossus is a statue"), "en"))
    }

    @Test fun picksTheRightArticle() {
        assertEquals(
            listOf("Shadow of the Colossus", "Shadow of the Colossus (2018 video game)"),
            Wiki.rankTitles(listOf("Colossus", "Shadow of the Colossus (2018 video game)", "Shadow of the Colossus"), "Shadow of the Colossus"),
        )
        // «Persona 4» — полное название статьи длиннее, но подходит
        assertEquals("Shin Megami Tensei: Persona 4", Wiki.rankTitles(listOf("Shin Megami Tensei: Persona 4", "Atlus"), "Persona 4").first())
        assertEquals("Pokémon Emerald", Wiki.rankTitles(listOf("Pokémon Emerald"), "Pokemon - Emerald Version").first())
        // Совсем другие статьи не берём
        assertTrue(Wiki.rankTitles(listOf("Sony", "PlayStation 2"), "God of War II").isEmpty())
    }
}

class MetaInTabsTest {
    private val game = Game("g1", "/r/ps2/x.iso", GameSystem.PS2, "Ico", emptyList(), "ps2")

    @Test fun descriptionFollowsLanguageWithFallback() {
        val both = mapOf("g1" to GameMeta(cover = "c.webp", descEn = "EN text", descRu = "RU текст"))
        val ruTab = buildConsoleTabs(emptyList(), LauncherData(), RU, GamesInfo(listOf(game), true, both))[1]
        val e = ruTab.rows[0].entries[0]
        assertEquals("RU текст", e.description)
        assertEquals("c.webp", e.cover)
        assertFalse(e.descFallback)
        assertEquals("EN text", buildConsoleTabs(emptyList(), LauncherData(), EN, GamesInfo(listOf(game), true, both))[1].rows[0].entries[0].description)

        val enOnly = mapOf("g1" to GameMeta(descEn = "EN text"))
        val f = buildConsoleTabs(emptyList(), LauncherData(), RU, GamesInfo(listOf(game), true, enOnly))[1].rows[0].entries[0]
        assertEquals("EN text", f.description)
        assertTrue(f.descFallback)
    }
}
