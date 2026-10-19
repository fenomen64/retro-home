package com.retrohome.launcher

import android.app.Application
import android.graphics.Bitmap
import com.retrohome.launcher.data.DescSource
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameMeta
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.HttpResponse
import com.retrohome.launcher.data.META_VERSION
import com.retrohome.launcher.data.MetaRepository
import com.retrohome.launcher.data.Transport
import com.retrohome.launcher.data.Wiki
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Весь путь поиска обложки и описания на заготовленных ответах серверов
 * (в том же формате, что отдают libretro, Википедия и Викиданные).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class MetaFlowTest {
    private val skies = Game("sk", "/ROMs/gc/Skies of Arcadia Legends Undub.iso", GameSystem.GC, "Skies of Arcadia Legends", emptyList(), "gc")

    private val png: ByteArray by lazy {
        val b = Bitmap.createBitmap(40, 56, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val gcIndex = """<a href="Skies%20of%20Arcadia%20Legends%20(USA).png">Skies of Arcadia Legends (USA).png</a>
        <a href="Skies%20of%20Arcadia%20Legends%20(Europe).png">Skies of Arcadia Legends (Europe) (En,Fr,De,Es).png</a>"""

    // Поиск: статья называется «Skies of Arcadia», а «Legends» — перенаправление на неё
    private val enSearch = """{"batchcomplete":true,"query":{"searchinfo":{"totalhits":40},"search":[
        {"ns":0,"title":"Skies of Arcadia","pageid":296400,"redirecttitle":"Skies of Arcadia Legends"},
        {"ns":0,"title":"List of GameCube games","pageid":1}]}}"""

    private val enPages = """{"batchcomplete":true,"query":{"pages":[
        {"pageid":296400,"ns":0,"title":"Skies of Arcadia","extract":"Skies of Arcadia is a 2000 role-playing video game developed by Overworks and published by Sega for the Dreamcast.\nAn enhanced port, Skies of Arcadia Legends, was released for the GameCube in 2002.",
         "pageprops":{"wikibase-shortdesc":"2000 video game","wikibase_item":"Q1047592"},"langlinks":[{"lang":"ru","title":"Skies of Arcadia"}]}]}}"""

    private val ruPages = """{"batchcomplete":true,"query":{"pages":[
        {"pageid":1,"ns":0,"title":"Skies of Arcadia","extract":"Skies of Arcadia — компьютерная ролевая игра, разработанная Overworks и изданная Sega в 2000 году."}]}}"""

    private val skiesEntity = """{"entities":{"Q1047592":{"id":"Q1047592","labels":{"en":{"language":"en","value":"Skies of Arcadia"}},
        "claims":{
          "P136":[{"mainsnak":{"datavalue":{"value":{"id":"Q744038"},"type":"wikibase-entityid"}},"rank":"normal"}],
          "P178":[{"mainsnak":{"datavalue":{"value":{"id":"Q1888"},"type":"wikibase-entityid"}},"rank":"normal"}],
          "P123":[{"mainsnak":{"datavalue":{"value":{"id":"Q122741"},"type":"wikibase-entityid"}},"rank":"normal"}],
          "P577":[{"mainsnak":{"datavalue":{"value":{"time":"+2000-10-05T00:00:00Z"},"type":"time"}},"rank":"normal"}]},
        "sitelinks":{}}}}"""
    private val skiesLabels = """{"entities":{
        "Q744038":{"labels":{"en":{"language":"en","value":"role-playing video game"},"ru":{"language":"ru","value":"компьютерная ролевая игра"}}},
        "Q1888":{"labels":{"en":{"language":"en","value":"Overworks"}}},
        "Q122741":{"labels":{"en":{"language":"en","value":"Sega"},"ru":{"language":"ru","value":"Sega"}}}}}"""

    private class Fake(val routes: (String) -> HttpResponse) : Transport {
        val calls = mutableListOf<String>()
        override fun open(url: String, accept: String?, userAgent: String): HttpResponse {
            synchronized(calls) { calls += url }
            return routes(url)
        }
    }

    private fun ok(s: String) = HttpResponse(200, s.toByteArray())

    private fun repo(t: Transport): MetaRepository {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "meta.json").delete()
        app.cacheDir.deleteRecursively()
        return MetaRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO), t, sleep = {}, isOnline = { true })
    }

    private fun runFetch(r: MetaRepository, games: List<Game>, retry: Boolean = false): Map<String, GameMeta> {
        r.fetch(games, retryMissing = retry)
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50)
            val m = r.meta.value
            if (r.progress.value == null && games.all { m[it.id]?.ver == META_VERSION }) return m
        }
        error("не дождались загрузки")
    }

    @Test fun skiesOfArcadiaGetsDescriptionEvenAfterRateLimit() {
        var limited = 0
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(gcIndex)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                // первый запрос к Википедии упирается в лимит — лаунчер ждёт и повторяет
                "en.wikipedia.org" in url && "list=search" in url ->
                    if (limited++ == 0) HttpResponse(429, retryAfter = "3") else ok(enSearch)
                "en.wikipedia.org" in url && "prop=extracts" in url -> ok(enPages)
                "ru.wikipedia.org" in url && "prop=extracts" in url -> ok(ruPages)
                "wbgetentities" in url && "ids=Q1047592" in url -> ok(skiesEntity)
                "wbgetentities" in url -> ok(skiesLabels)
                else -> HttpResponse(404)
            }
        }
        val m = runFetch(repo(t), listOf(skies))["sk"]!!
        // Факты для страницы игры — по элементу Викиданных из статьи
        assertEquals("Q1047592", m.qid)
        assertEquals(2000, m.year)
        assertEquals(listOf("role-playing video game"), m.genresEn)
        assertEquals(listOf("компьютерная ролевая игра"), m.genresRu)
        assertEquals(listOf("Overworks"), m.developers)
        assertEquals(listOf("Sega"), m.publishers)
        assertTrue(m.factsTried)
        // Снимок экрана из игры для фона
        assertNotNull(m.snap)
        assertEquals("Skies of Arcadia Legends (USA)", m.thumbName)
        assertTrue(t.calls.any { "/Named_Snaps/" in it })
        assertNotNull(m.cover)
        assertEquals("Skies of Arcadia Legends", m.name)
        assertTrue(m.descEn, m.descEn!!.startsWith("Skies of Arcadia is a 2000 role-playing video game"))
        assertTrue(m.descEn!!.contains("GameCube in 2002"))
        assertTrue(m.descRu!!.startsWith("Skies of Arcadia — компьютерная ролевая игра"))
        assertEquals(DescSource.WIKIPEDIA, m.srcRu)
        assertTrue(m.descTried)
        assertEquals(0L, m.retryAt)
        // статьи грузятся пачкой: поиск (2 раза из-за 429) + статьи EN + статья RU
        assertEquals(4, t.calls.count { "wikipedia.org" in it })
    }

    @Test fun networkFailureIsNotTreatedAsNotFound() {
        // Википедия и Викиданные всё время отвечают «слишком много запросов»
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(gcIndex)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                else -> HttpResponse(429, retryAfter = "5")
            }
        }
        val r = repo(t)
        val m = runFetch(r, listOf(skies))["sk"]!!
        assertNotNull(m.cover)
        assertNull(m.descEn)
        assertFalse("сбой сети — надо поискать ещё раз", m.descTried)
        assertTrue(m.retryAt > System.currentTimeMillis())

        // Автоматически сразу не повторяем (полчаса пауза), а по кнопке «Обновить» — повторяем
        val before = t.calls.size
        r.fetch(listOf(skies))
        Thread.sleep(300)
        assertEquals(before, t.calls.size)
    }

    @Test fun networkProblemIsExplainedInLog() {
        // Обложки грузятся, а Википедия не отвечает: в журнале — какой сайт и почему
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(gcIndex)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                else -> HttpResponse(0, error = "SocketTimeoutException: timeout")
            }
        }
        val r = repo(t)
        val m = runFetch(r, listOf(skies))["sk"]!!
        val log = m.lookupLog.joinToString("\n")
        assertTrue(log, m.lookupLog.any { "en.wikipedia.org: SocketTimeoutException" in it })
        assertTrue(log, m.lookupLog.any { "Проверка соединения" in it })
        assertFalse(m.descTried)
        assertNotNull(r.problem.value)
    }

    @Test fun rejectedRequestIsRetriedLater() {
        // 403 — сайт отклонил запрос (фильтр против роботов): это не «описания нет», а повод повторить позже
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(gcIndex)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                else -> HttpResponse(403)
            }
        }
        val m = runFetch(repo(t), listOf(skies))["sk"]!!
        assertFalse(m.descTried)
        assertTrue(m.retryAt > System.currentTimeMillis())
    }

    @Test fun realNotFoundIsRemembered() {
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(gcIndex)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                "list=search" in url -> ok("""{"query":{"search":[]}}""")
                "wbsearchentities" in url -> ok("""{"search":[]}""")
                else -> HttpResponse(404)
            }
        }
        val m = runFetch(repo(t), listOf(skies))["sk"]!!
        assertTrue(m.descTried)
        assertEquals(0L, m.retryAt)
    }

    @Test fun wikidataFactsWhenNoArticle() {
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(gcIndex)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                "list=search" in url -> ok("""{"query":{"search":[{"ns":0,"title":"Sega","pageid":5}]}}""")
                "wbsearchentities" in url -> ok("""{"search":[{"id":"Q7","label":"Skies of Arcadia Legends","description":"2002 video game",
                    "match":{"type":"label","language":"en","text":"Skies of Arcadia Legends"}}]}""")
                "wbgetentities" in url && "claims" in url -> ok("""{"entities":{"Q7":{"id":"Q7",
                    "labels":{"en":{"language":"en","value":"Skies of Arcadia Legends"}},
                    "claims":{"P178":[{"mainsnak":{"datavalue":{"value":{"id":"Q100"},"type":"wikibase-entityid"}},"rank":"normal"}],
                      "P577":[{"mainsnak":{"datavalue":{"value":{"time":"+2002-12-26T00:00:00Z"},"type":"time"}},"rank":"normal"}]},
                    "sitelinks":{}}}}""")
                "wbgetentities" in url -> ok("""{"entities":{"Q100":{"labels":{"en":{"language":"en","value":"Overworks"}}}}}""")
                else -> HttpResponse(404)
            }
        }
        val m = runFetch(repo(t), listOf(skies))["sk"]!!
        assertEquals(DescSource.WIKIDATA, m.srcRu)
        assertTrue(m.descRu, "Разработчик: Overworks" in m.descRu!!)
        assertTrue(m.descRu, "Год выхода: 2002" in m.descRu!!)
    }

    @Test fun regionalTitleFoundInArticleLead() {
        val avatar = Game("av", "/ROMs/gba/Avatar - The Legend of Aang (Europe) (En,Fr,De,Nl).gba", GameSystem.GBA,
            "Avatar - The Legend of Aang", listOf("Europe"), "gba")
        val gbaIndex = """<a href="x.png">Avatar - The Legend of Aang (Europe) (En,Fr,De,Nl).png</a>"""
        val search = """{"query":{"search":[
            {"ns":0,"title":"Avatar: The Last Airbender","pageid":1},
            {"ns":0,"title":"Avatar: The Last Airbender (video game)","pageid":2}]}}"""
        val pages = """{"query":{"pages":[
            {"title":"Avatar: The Last Airbender","extract":"Avatar: The Last Airbender (also known as Avatar: The Legend of Aang in some regions) is an American animated fantasy television series.",
             "pageprops":{"wikibase-shortdesc":"American animated television series"}},
            {"title":"Avatar: The Last Airbender (video game)","extract":"Avatar: The Last Airbender (known as Avatar: The Legend of Aang in Europe) is a 2006 action-adventure video game based on the animated television series.",
             "pageprops":{"wikibase-shortdesc":"2006 video game"}}]}}"""
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(gbaIndex)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                "en.wikipedia.org" in url && "list=search" in url -> ok(search)
                "en.wikipedia.org" in url && "prop=extracts" in url -> ok(pages)
                "list=search" in url -> ok("""{"query":{"search":[]}}""")
                else -> HttpResponse(404)
            }
        }
        val m = runFetch(repo(t), listOf(avatar))["av"]!!
        assertEquals("Avatar - The Legend of Aang", m.name)
        assertTrue(m.descEn, m.descEn!!.contains("2006 action-adventure video game"))
        assertEquals("Avatar: The Last Airbender (video game)", m.wikiEn)
    }

    @Test fun descriptionMatchesTheConsole() {
        // Final Fantasy VII для PS1: ремейк и Revelation — другие игры для новых консолей
        val ff7 = Game("ff", "/ROMs/psx/ff7 rus disk1.chd", GameSystem.PS1, "Final Fantasy VII", emptyList(), "psx")
        val ps1Index = """<a href="x.png">Final Fantasy VII (USA) (Disc 1).png</a>"""
        val searchNew = """{"query":{"search":[{"ns":0,"title":"Final Fantasy VII Remake"},{"ns":0,"title":"Final Fantasy VII Revelation"}]}}"""
        val searchOld = """{"query":{"search":[{"ns":0,"title":"Final Fantasy VII"}]}}"""
        val pagesNew = """{"query":{"pages":[
            {"title":"Final Fantasy VII Remake","extract":"Final Fantasy VII Remake is a 2020 action role-playing game. It is a remake of the 1997 PlayStation game.","pageprops":{"wikibase-shortdesc":"2020 video game","wikibase_item":"Q2"}},
            {"title":"Final Fantasy VII Revelation","extract":"Final Fantasy VII Revelation is an upcoming action role-playing game.","pageprops":{"wikibase-shortdesc":"upcoming video game","wikibase_item":"Q3"}}]}}"""
        val pagesOld = """{"query":{"pages":[
            {"title":"Final Fantasy VII","extract":"Final Fantasy VII is a 1997 role-playing video game developed by Square for the PlayStation.","pageprops":{"wikibase-shortdesc":"1997 video game","wikibase_item":"Q1"}}]}}"""
        fun ent(id: String, vararg platforms: String) = """"$id":{"id":"$id","claims":{"P400":[""" +
            platforms.joinToString(",") { """{"mainsnak":{"datavalue":{"value":{"id":"$it"},"type":"wikibase-entityid"}},"rank":"normal"}""" } +
            """]},"sitelinks":{}}"""
        val labels = """{"entities":{"P1":{"labels":{"en":{"value":"PlayStation"}}},"P4":{"labels":{"en":{"value":"PlayStation 4"}}},"P5":{"labels":{"en":{"value":"PlayStation 5"}}}}}"""
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok(ps1Index)
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                "en.wikipedia.org" in url && "list=search" in url && "video%20game" in url -> ok(searchNew)
                "en.wikipedia.org" in url && "list=search" in url -> ok(searchOld)
                "en.wikipedia.org" in url && "prop=extracts" in url && "Remake" in url -> ok(pagesNew)
                "en.wikipedia.org" in url && "prop=extracts" in url -> ok(pagesOld)
                "wbgetentities" in url && "claims" in url && "Q2" in url -> ok("""{"entities":{""" + ent("Q2", "P4", "P5") + "," + ent("Q3", "P5") + "}}")
                "wbgetentities" in url && "claims" in url -> ok("""{"entities":{""" + ent("Q1", "P1") + "}}")
                "wbgetentities" in url -> ok(labels)
                else -> HttpResponse(404)
            }
        }
        val m = runFetch(repo(t), listOf(ff7))["ff"]!!
        assertEquals("Final Fantasy VII", m.wikiEn)
        assertTrue(m.descEn!!.contains("1997"))
        assertEquals("Q1", m.qid)
        assertTrue(m.platformChecked)
    }

    @Test fun characterArticleIsSkipped() {
        // Английская Википедия не ответила, в русской первым находится персонаж — его нельзя брать
        val ff7 = Game("ff", "/ROMs/psx/ff7.chd", GameSystem.PS1, "Final Fantasy VII", emptyList(), "psx")
        val search = """{"query":{"search":[{"ns":0,"title":"Тифа Локхарт","redirecttitle":"Final Fantasy VII Tifa"},{"ns":0,"title":"Final Fantasy VII"}]}}"""
        val pages = """{"query":{"pages":[
            {"title":"Тифа Локхарт","extract":"Ти́фа Ло́кхарт (яп. ティファ・ロックハート, англ. Tifa Lockhart) — персонаж компьютерной ролевой игры Final Fantasy VII (1997) от компании Square."},
            {"title":"Final Fantasy VII","extract":"Final Fantasy VII — компьютерная ролевая игра, разработанная Square для игровой приставки PlayStation в 1997 году."}]}}"""
        val t = Fake { url ->
            when {
                "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok("""<a href="x.png">Final Fantasy VII (USA) (Disc 1).png</a>""")
                "thumbnails.libretro.com" in url -> HttpResponse(200, png)
                "en.wikipedia.org" in url -> ok("""{"query":{"search":[]}}""")
                "ru.wikipedia.org" in url && "list=search" in url -> ok(search)
                "ru.wikipedia.org" in url && "prop=extracts" in url -> ok(pages)
                "wbsearchentities" in url -> ok("""{"search":[]}""")
                else -> HttpResponse(404)
            }
        }
        val m = runFetch(repo(t), listOf(ff7))["ff"]!!
        assertEquals("Final Fantasy VII", m.wikiRu)
        assertTrue(m.descRu!!.startsWith("Final Fantasy VII — компьютерная ролевая игра"))
        // журнал поиска объясняет, почему Тифа отброшена
        assertTrue(m.lookupLog.joinToString("\n"), m.lookupLog.any { "Тифа Локхарт ✗" in it })
        assertTrue(m.lookupLog.any { it.startsWith("Итог") })
    }

    // The Legend of Heroes для PSP: в Викиданных у статей только PC-98 и Windows, о PSP-версии пишет сама статья
    private val lohSearch = """{"query":{"search":[
        {"ns":0,"title":"The Legend of Heroes","redirecttitle":"Legend of Heroes"},
        {"ns":0,"title":"The Legend of Heroes II: Prophecy of the Moonlight Witch"},
        {"ns":0,"title":"The Legend of Heroes: A Tear of Vermillion"},
        {"ns":0,"title":"The Legend of Heroes III: Song of the Ocean"}]}}"""
    private fun lohPage(title: String, qid: String, extract: String) =
        """{"title":"$title","extract":"$extract","pageprops":{"wikibase-shortdesc":"video game","wikibase_item":"$qid"}}"""
    private val lohPages = """{"query":{"pages":[""" + listOf(
        lohPage("The Legend of Heroes", "Q10", "The Legend of Heroes is a 1989 role-playing video game for the PC-8801, later ported to the TurboGrafx-16."),
        lohPage("The Legend of Heroes II: Prophecy of the Moonlight Witch", "Q11",
            "The Legend of Heroes II: Prophecy of the Moonlight Witch is a 1994 role-playing video game developed by Nihon Falcom. " +
            "It was released for the PC-9801, Sega Saturn, PlayStation and Windows. " + "Lorem ipsum dolor sit amet. ".repeat(30) +
            "A remake for the PlayStation Portable was released in North America in 2008."),
        lohPage("The Legend of Heroes: A Tear of Vermillion", "Q12",
            "The Legend of Heroes: A Tear of Vermillion is a 1996 role-playing video game. A remake was released for the PlayStation Portable in 2005."),
        lohPage("The Legend of Heroes III: Song of the Ocean", "Q13",
            "The Legend of Heroes III: Song of the Ocean is a 1999 role-playing video game. It was remade for the PlayStation Portable in 2006."),
    ).joinToString(",") + "]}}"
    private fun wdEnt(id: String, vararg platforms: String) = """"$id":{"id":"$id","claims":{"P400":[""" +
        platforms.joinToString(",") { """{"mainsnak":{"datavalue":{"value":{"id":"$it"},"type":"wikibase-entityid"}},"rank":"normal"}""" } +
        """]},"sitelinks":{}}"""
    private val lohEntities = """{"entities":{""" + listOf(wdEnt("Q10", "Q1"), wdEnt("Q11", "Q2", "Q3"), wdEnt("Q12", "Q2", "Q3"), wdEnt("Q13", "Q3")).joinToString(",") + "}}"
    private val lohLabels = """{"entities":{"Q1":{"labels":{"en":{"value":"TurboGrafx-16"}}},"Q2":{"labels":{"en":{"value":"PC-9801"}}},"Q3":{"labels":{"en":{"value":"Microsoft Windows"}}}}}"""
    private fun lohTransport() = Fake { url ->
        when {
            "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") -> ok("")
            "thumbnails.libretro.com" in url -> HttpResponse(404)
            "en.wikipedia.org" in url && "list=search" in url -> ok(lohSearch)
            "en.wikipedia.org" in url && "prop=extracts" in url -> ok(lohPages)
            "wbgetentities" in url && "claims" in url -> ok(lohEntities)
            "wbgetentities" in url -> ok(lohLabels)
            "list=search" in url -> ok("""{"query":{"search":[]}}""")
            "wbsearchentities" in url -> ok("""{"search":[]}""")
            else -> HttpResponse(404)
        }
    }
    private fun psp(id: String, title: String) = Game(id, "/ROMs/psp/$title.iso", GameSystem.PSP, title, emptyList(), "psp")

    @Test fun portMentionedOnlyInArticleIsAccepted() {
        val games = listOf(
            psp("l2", "The Legend of Heroes II - Prophecy of the Moonlight Witch"),
            psp("l4", "The Legend of Heroes - A Tear of Vermillion"),
            psp("l3", "The Legend of Heroes III - Song of the Ocean"),
        )
        val m = runFetch(repo(lohTransport()), games)
        assertEquals(m["l2"]!!.lookupLog.joinToString("\n"), "The Legend of Heroes II: Prophecy of the Moonlight Witch", m["l2"]!!.wikiEn)
        assertEquals("The Legend of Heroes: A Tear of Vermillion", m["l4"]!!.wikiEn)
        assertEquals("The Legend of Heroes III: Song of the Ocean", m["l3"]!!.wikiEn)
        // Общая статья «The Legend of Heroes» (другая консоль) не подходит ни одной из игр
        assertTrue(m.values.none { it.wikiEn == "The Legend of Heroes" })
        assertTrue(m["l2"]!!.lookupLog.any { "≈" in it })
    }

    @Test fun simultaneousRefreshesKeepSeparateLogs() {
        val r = repo(lohTransport())
        val a = psp("l2", "The Legend of Heroes II - Prophecy of the Moonlight Witch")
        val b = psp("l3", "The Legend of Heroes III - Song of the Ocean")
        r.refetch(a, null)
        r.refetch(b, null)
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            val m = r.meta.value
            if (listOf("l2", "l3").all { m[it]?.lookupLog?.any { l -> l.startsWith("Итог") } == true && m[it]?.factsTried == true }) break
            Thread.sleep(50)
        }
        val m = r.meta.value
        val logA = m["l2"]!!.lookupLog
        val logB = m["l3"]!!.lookupLog
        assertTrue(logA.joinToString("\n"), logA.none { "Song of the Ocean»" in it })
        assertTrue(logB.joinToString("\n"), logB.none { "Moonlight Witch»" in it })
        assertEquals(1, logA.count { it.startsWith("Итог") })
        assertEquals(1, logB.count { it.startsWith("Итог") })
    }

    @Test fun cardGameIsNotTheVideoGame() {
        // Pokémon Brilliant Diamond: в русской Википедии статьи нет — карточная игра «Pokémon» не подходит
        val bd = Game("bd", "/ROMs/switch/Pokemon Brilliant Diamond.nsp", GameSystem.SWITCH, "Pokémon Brilliant Diamond", emptyList(), "switch")
        val ruSearch = """{"query":{"search":[{"ns":0,"title":"Покемон"},{"ns":0,"title":"Pokémon (серия игр)"},{"ns":0,"title":"Pokémon (коллекционная карточная игра)"}]}}"""
        val ruPages = """{"query":{"pages":[
            {"title":"Покемон","extract":"Покемо́н — медиафраншиза, принадлежащая компании Nintendo."},
            {"title":"Pokémon (серия игр)","extract":"Pokémon — серия компьютерных игр."},
            {"title":"Pokémon (коллекционная карточная игра)","extract":"Pokémon — коллекционная карточная игра, основанная на франшизе."}]}}"""
        val enSearch = """{"query":{"search":[{"ns":0,"title":"Pokémon Brilliant Diamond and Shining Pearl"},{"ns":0,"title":"Pokémon (video game series)"}]}}"""
        val enPages = """{"query":{"pages":[
            {"title":"Pokémon Brilliant Diamond and Shining Pearl","extract":"Pokémon Brilliant Diamond and Pokémon Shining Pearl are 2021 role-playing video games for the Nintendo Switch.","pageprops":{"wikibase-shortdesc":"2021 video game"}},
            {"title":"Pokémon (video game series)","extract":"Pokémon is a series of video games.","pageprops":{"wikibase-shortdesc":"Video game series"}}]}}"""
        val t = Fake { url ->
            when {
                "tinfoil.media" in url -> HttpResponse(404)
                "en.wikipedia.org" in url && "list=search" in url -> ok(enSearch)
                "en.wikipedia.org" in url && "prop=extracts" in url -> ok(enPages)
                "ru.wikipedia.org" in url && "list=search" in url -> ok(ruSearch)
                "ru.wikipedia.org" in url && "prop=extracts" in url -> ok(ruPages)
                "wbsearchentities" in url -> ok("""{"search":[]}""")
                else -> HttpResponse(404)
            }
        }
        val m = runFetch(repo(t), listOf(bd))["bd"]!!
        assertEquals("Pokémon Brilliant Diamond and Shining Pearl", m.wikiEn)
        assertNull(m.lookupLog.joinToString("\n"), m.descRu)
        assertNull(m.wikiRu)
    }

    @Test fun contactEmailGoesIntoUserAgent() {
        val agents = mutableListOf<String>()
        val t = object : Transport {
            override fun open(url: String, accept: String?, userAgent: String): HttpResponse {
                synchronized(agents) { agents += userAgent }
                return if (url.endsWith("/Named_Boxarts/")) ok(gcIndex) else HttpResponse(404)
            }
        }
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "meta.json").delete()
        var email: String? = null
        val r = MetaRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO), t, sleep = {}, isOnline = { true }, contact = { email })
        assertFalse(r.userAgent().contains("@"))
        // Без почты лаунчер всё равно представляется — адресом страницы проекта
        assertTrue(r.userAgent(), r.userAgent().contains(MetaRepository.PROJECT_URL))
        email = "player@example.com"
        assertTrue(r.userAgent(), r.userAgent().startsWith("RetroHome/") && r.userAgent().contains("player@example.com"))
        runFetch(r, listOf(skies))
        assertTrue(agents.isNotEmpty() && agents.all { "player@example.com" in it })
    }

    @Test fun leadMentionNeedsLongEnoughTitle() {
        assertTrue(Wiki.leadMentions("Foo (known as The Legend of Aang in Europe) is a game", "Legend of Aang"))
        assertFalse(Wiki.leadMentions("Ico is a game by Team Ico", "Ico"))
        assertFalse(Wiki.leadMentions("Some other game", "Avatar - The Legend of Aang"))
    }

    @Test fun pagesResponseFollowsRedirectsAndSkipsDisambiguation() {
        val body = """{"query":{"normalized":[{"from":"skies of arcadia legends","to":"Skies of arcadia legends"}],
            "redirects":[{"from":"Skies of arcadia legends","to":"Skies of Arcadia"}],
            "pages":[{"title":"Skies of Arcadia","extract":"Text.","pageprops":{"wikibase-shortdesc":"2000 video game"}},
                     {"title":"Ico","extract":"Ico may refer to:","pageprops":{"disambiguation":""}},
                     {"title":"Nope","missing":true}]}}"""
        val p = Wiki.parsePages(body, listOf("skies of arcadia legends", "Ico", "Nope"))
        assertEquals("Skies of Arcadia", p["skies of arcadia legends"]!!.title)
        assertEquals("2000 video game", p["skies of arcadia legends"]!!.description)
        assertTrue(p["Ico"]!!.disambiguation)
        assertNull(p["Nope"])
    }
}
