package com.retrohome.launcher

import com.retrohome.launcher.data.DescSource
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameMeta
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.Html
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.SwitchDb
import com.retrohome.launcher.data.Thumbnails
import com.retrohome.launcher.data.Wiki
import com.retrohome.launcher.data.Wikidata
import com.retrohome.launcher.data.cycle
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.ui.console.GamesInfo
import com.retrohome.launcher.ui.console.buildConsoleTabs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Переключение вариантов в обе стороны. */
class CycleTest {
    @Test fun bothDirectionsWrap() {
        val l = listOf("soft", "retro", "crystal", "click", "off")
        assertEquals("retro", l.cycle("soft", 1))
        assertEquals("off", l.cycle("soft", -1))
        assertEquals("soft", l.cycle("off", 1))
        assertEquals("click", l.cycle("off", -1))
        // неизвестное значение (например, старая настройка) — к краю списка
        assertEquals("soft", l.cycle("???", 1))
        assertEquals("off", l.cycle("???", -1))
        val vol = (0..100 step 10).toList()
        assertEquals(60, vol.cycle(70, -1))
        assertEquals(100, vol.cycle(0, -1))
    }
}

/** Википедия: перенаправления помогают найти статью под другим названием. */
class WikiRedirectTest {
    @Test fun redirectTitleCounts() {
        val body = """{"batchcomplete":true,"query":{"search":[
            {"ns":0,"title":"Lunar: The Silver Star","pageid":1,"redirecttitle":"Lunar: Silver Star Story Complete"},
            {"ns":0,"title":"Lunar: Eternal Blue","pageid":2}]}}"""
        val hits = Wiki.parseHits(body)
        assertEquals("Lunar: Silver Star Story Complete", hits[0].redirect)
        assertNull(hits[1].redirect)
        assertEquals(listOf("Lunar: The Silver Star"), Wiki.rankHits(hits, "Lunar - Silver Star Story Complete"))
        // Старый формат ответа (объект вместо строки) тоже понимаем
        val old = """{"query":{"search":[{"title":"A","redirecttitle":{"mTextform":"B"}}]}}"""
        assertEquals("B", Wiki.parseHits(old)[0].redirect)
    }

    @Test fun libretroNamesBecomeReadable() {
        assertEquals("Ratchet & Clank", Thumbnails.displayName("Ratchet _ Clank (USA) (En,Fr,De,Es,It)"))
        assertEquals("Lunar - Silver Star Story Complete", Thumbnails.displayName("Lunar - Silver Star Story Complete (USA)"))
    }

    @Test fun htmlEntities() {
        assertEquals("Pokémon™ & ’", Html.decode("Pok&#233;mon™ &amp; &#x2019;"))
        assertEquals("&unknown;", Html.decode("&unknown;"))
    }
}

/** Викиданные: поиск по синонимам и справка из фактов. */
class WikidataTest {
    private val search = """{"searchinfo":{"search":"Trails in the Sky Second Chapter"},"search":[
        {"id":"Q1","title":"Q1","label":"The Legend of Heroes: Trails in the Sky","description":"video game series",
         "match":{"type":"label","language":"en","text":"The Legend of Heroes: Trails in the Sky"}},
        {"id":"Q2","title":"Q2","display":{"label":{"value":"The Legend of Heroes: Trails in the Sky SC","language":"en"},
         "description":{"value":"2006 video game","language":"en"}},
         "match":{"type":"alias","language":"en","text":"Trails in the Sky Second Chapter"}},
        {"id":"Q3","title":"Q3","label":"Trails in the Sky Second Chapter OST","description":"soundtrack album",
         "match":{"type":"label","language":"en","text":"Trails in the Sky Second Chapter OST"}}]}"""

    @Test fun picksGameByAlias() {
        val c = Wikidata.parseSearch(search)
        assertEquals(3, c.size)
        assertEquals("The Legend of Heroes: Trails in the Sky SC", c[1].label)
        // серия и саундтрек отбрасываются, игра находится по синониму
        assertEquals("Q2", Wikidata.pick(c, "Trails in the Sky Second Chapter"))
        assertNull(Wikidata.pick(c, "Совсем другая игра"))
    }

    private val entity = """{"entities":{"Q2":{"type":"item","id":"Q2",
        "labels":{"en":{"language":"en","value":"The Legend of Heroes: Trails in the Sky SC"}},
        "descriptions":{"en":{"language":"en","value":"2006 video game"}},
        "claims":{
          "P136":[{"mainsnak":{"snaktype":"value","property":"P136","datavalue":{"value":{"entity-type":"item","numeric-id":744038,"id":"Q744038"},"type":"wikibase-entityid"}},"rank":"normal"}],
          "P178":[{"mainsnak":{"snaktype":"value","property":"P178","datavalue":{"value":{"entity-type":"item","id":"Q1154428"},"type":"wikibase-entityid"}},"rank":"normal"}],
          "P123":[{"mainsnak":{"snaktype":"value","property":"P123","datavalue":{"value":{"entity-type":"item","id":"Q1154428"},"type":"wikibase-entityid"}},"rank":"normal"},
                  {"mainsnak":{"snaktype":"value","property":"P123","datavalue":{"value":{"entity-type":"item","id":"Q999"},"type":"wikibase-entityid"}},"rank":"deprecated"}],
          "P400":[{"mainsnak":{"snaktype":"value","property":"P400","datavalue":{"value":{"entity-type":"item","id":"Q5014725"},"type":"wikibase-entityid"}},"rank":"normal"},
                  {"mainsnak":{"snaktype":"value","property":"P400","datavalue":{"value":{"entity-type":"item","id":"Q170323"},"type":"wikibase-entityid"}},"rank":"normal"}],
          "P577":[{"mainsnak":{"snaktype":"value","property":"P577","datavalue":{"value":{"time":"+2011-10-29T00:00:00Z","precision":11},"type":"time"}},"rank":"normal"},
                  {"mainsnak":{"snaktype":"value","property":"P577","datavalue":{"value":{"time":"+2006-03-09T00:00:00Z","precision":11},"type":"time"}},"rank":"normal"}]},
        "sitelinks":{"enwiki":{"site":"enwiki","title":"The Legend of Heroes: Trails in the Sky SC","badges":[]}}}}}"""

    private val labels = """{"entities":{
        "Q744038":{"labels":{"en":{"language":"en","value":"role-playing video game"},"ru":{"language":"ru","value":"компьютерная ролевая игра"}}},
        "Q1154428":{"labels":{"en":{"language":"en","value":"Nihon Falcom"}}},
        "Q5014725":{"labels":{"en":{"language":"en","value":"Microsoft Windows"},"ru":{"language":"ru","value":"Windows"}}},
        "Q170323":{"labels":{"en":{"language":"en","value":"PlayStation Portable"}}}}}"""

    @Test fun parsesEntityAndDescribes() {
        val e = Wikidata.parseEntity(entity)!!
        assertEquals(2006, e.year)
        assertEquals(mapOf("en" to "The Legend of Heroes: Trails in the Sky SC"), e.wiki)
        assertEquals(listOf("Q1154428"), e.refs[Wikidata.PUBLISHER]) // устаревшее значение пропущено
        val l = Wikidata.parseLabels(labels)
        val ru = Wikidata.describe(e, l, "ru", "x")!!
        assertTrue(ru, ru.startsWith("The Legend of Heroes: Trails in the Sky SC — компьютерная игра 2006 года."))
        assertTrue(ru, "Жанр: компьютерная ролевая игра" in ru)
        assertTrue(ru, "Разработчик: Nihon Falcom" in ru) // нет русского названия — берём английское
        assertTrue(ru, "Платформы: Windows, PlayStation Portable" in ru)
        val en = Wikidata.describe(e, l, "en", "x")!!
        assertTrue(en, en.startsWith("The Legend of Heroes: Trails in the Sky SC — 2006 video game."))
        assertTrue(en, "Released: 2006" in en)
        // Слишком мало фактов — справку не показываем
        assertNull(Wikidata.describe(e.copy(refs = emptyMap(), year = 2006), l, "ru", "x"))
        assertNull(Wikidata.parseEntity("""{"entities":{"Q0":{"id":"Q0","missing":""}}}"""))
    }
}

/** Switch: Title ID, список игр и страница с описанием (настоящие данные tinfoil.media). */
class SwitchDbTest {
    private fun res(name: String) = javaClass.classLoader!!.getResourceAsStream(name)!!.bufferedReader().readText()

    @Test fun titleIdFromFileName() {
        assertEquals("0100000011D90000", SwitchDb.titleId("Pokemon Brilliant Diamond [0100000011D90000][v0].nsp"))
        // обновление (…800) ведёт к базовой игре
        assertEquals("0100000011D90000", SwitchDb.titleId("Pokemon Brilliant Diamond [0100000011d90800][v196608].nsp"))
        assertNull(SwitchDb.titleId("Super Mario Odyssey.xci"))
        assertNull(SwitchDb.titleId("game [0100000011D90001].nsp"))
    }

    @Test fun parsesTitleList() {
        val list = SwitchDb.parseList(res("switch_titles.json"))
        assertTrue(list.size > 400)
        val byId = list.toMap()
        assertEquals("Pokémon Brilliant Diamond", byId["0100000011D90000"])
        assertTrue(list.any { it.second == "HIM & HER 3" })
        assertTrue(list.none { '<' in it.second || '™' in it.second })
    }

    @Test fun matchesSwitchGameByName() {
        val list = SwitchDb.parseList(res("switch_titles.json"))
        val idByName = list.groupBy({ it.second }, { it.first }).mapValues { it.value.first() }
        val idx = Thumbnails.Index(idByName.keys.toList())
        assertEquals("Pokémon Brilliant Diamond", Thumbnails.match("Pokemon Brilliant Diamond.nsp", idx))
        assertEquals("The Legend of Zelda: Breath of the Wild", Thumbnails.match("The Legend of Zelda - Breath of the Wild.xci", idx))
        assertEquals("Mario Kart 8 Deluxe", Thumbnails.match("Mario Kart 8 Deluxe [v0].nsp", idx))
    }

    @Test fun parsesTitlePage() {
        val p = SwitchDb.parsePage(res("switch_page.html"))!!
        assertEquals("Pokémon Brilliant Diamond", p.name)
        val d = p.description!!
        assertTrue(d, d.startsWith("Welcome to the test region!"))
        assertTrue(d, d.length in 300..900)
        assertTrue(d, "&#233;" !in d && "<" !in d)
        assertNull(SwitchDb.parsePage("<html><head><title>Error</title></head></html>"))
    }

    @Test fun shortenCutsAtSentence() {
        val long = "First sentence here. ".repeat(60)
        val s = SwitchDb.shorten(long, 300)!!
        assertTrue(s.length <= 300)
        assertTrue(s.endsWith("."))
    }
}

/** Подпись источника под описанием. */
class SourceLabelTest {
    @Test fun labelFollowsSource() {
        val g = Game("g1", "/r/x.nsp", GameSystem.SWITCH, "Pokemon Brilliant Diamond", emptyList(), "switch")
        fun entry(m: GameMeta) = buildConsoleTabs(emptyList(), LauncherData(), RU, GamesInfo(listOf(g), true, mapOf("g1" to m)))[1].rows[0].entries[0]
        assertEquals(DescSource.ESHOP, entry(GameMeta(descEn = "eShop text", srcEn = DescSource.ESHOP)).descSource)
        assertEquals(DescSource.WIKIDATA, entry(GameMeta(descRu = "Справка", srcRu = DescSource.WIKIDATA, descEn = "x", srcEn = DescSource.ESHOP)).descSource)
        // старые записи без источника — это Википедия
        assertEquals(DescSource.WIKIPEDIA, entry(GameMeta(descRu = "Текст")).descSource)
        assertNull(entry(GameMeta()).descSource)
        assertNotNull(RU.sourceName(DescSource.ESHOP))
    }
}
