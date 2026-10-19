package com.retrohome.launcher

import android.app.Application
import com.retrohome.launcher.data.AchievementsRepository
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.HttpResponse
import com.retrohome.launcher.data.Ra
import com.retrohome.launcher.data.RaStatus
import com.retrohome.launcher.data.Transport
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
import java.io.File
import java.security.MessageDigest

private fun md5(b: ByteArray, from: Int = 0) =
    MessageDigest.getInstance("MD5").apply { update(b, from, b.size - from) }.digest().joinToString("") { "%02x".format(it) }

/** Отпечатки файлов — по правилам rcheevos (как считает сам эмулятор). */
class RaHashTest {
    private val rom = ByteArray(0x8000) { (it * 7 + 3).toByte() }

    @Test fun plainCartridgesHashWholeFile() {
        assertEquals(md5(rom), Ra.hash(GameSystem.GBA, rom))
        assertEquals(md5(rom), Ra.hash(GameSystem.GB, rom))
        assertEquals(md5(rom), Ra.hash(GameSystem.MD, rom))
    }

    @Test fun nesHeaderIsIgnored() {
        val header = "NES\u001a".toByteArray() + ByteArray(12)
        assertEquals(md5(rom), Ra.hash(GameSystem.NES, header + rom))
        assertEquals(md5(rom), Ra.hash(GameSystem.NES, rom))
    }

    @Test fun snesCopierHeaderIsIgnored() {
        assertEquals(md5(rom), Ra.hash(GameSystem.SNES, ByteArray(512) + rom))
        assertEquals(md5(rom), Ra.hash(GameSystem.SNES, rom))
    }

    @Test fun pceHeaderIsIgnored() {
        assertEquals(md5(rom), Ra.hash(GameSystem.PCE, ByteArray(512) + rom))
    }

    @Test fun n64ByteOrderIsNormalized() {
        val z64 = ByteArray(64) { (it + 1).toByte() }.also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40 }
        val v64 = z64.copyOf().also { b -> for (i in b.indices step 2) { val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t } }
        val n64 = z64.copyOf().also { b -> for (i in b.indices step 4) { b.reverse(i, i + 4) } }
        val expected = md5(z64)
        assertEquals(expected, Ra.hash(GameSystem.N64, z64))
        assertEquals(expected, Ra.hash(GameSystem.N64, v64))
        assertEquals(expected, Ra.hash(GameSystem.N64, n64))
        assertNull(Ra.hash(GameSystem.N64, ByteArray(64)))
    }

    /**
     * Эталон: те же файлы, посчитанные настоящей библиотекой RetroAchievements (rcheevos,
     * rc_hash_generate_from_file) — заголовки NES/SNES/PCE и порядок байт N64 обрабатываются одинаково.
     */
    @Test fun matchesReferenceRcheevosHashes() {
        val rom = ByteArray(0x40000) { ((it * 31 + 7) and 0xFF).toByte() }
        val ref = "7c9bf00fd6836f46b8d3b6f4d18e9298"
        assertEquals(ref, Ra.hash(GameSystem.GBA, rom))
        assertEquals(ref, Ra.hash(GameSystem.MD, rom))
        assertEquals(ref, Ra.hash(GameSystem.NES, "NES\u001a".toByteArray() + ByteArray(12) + rom))
        assertEquals(ref, Ra.hash(GameSystem.SNES, ByteArray(512) + rom))
        assertEquals(ref, Ra.hash(GameSystem.PCE, ByteArray(512) + rom))
        val z64 = rom.copyOf().also { it[0] = 0x80.toByte(); it[1] = 0x37; it[2] = 0x12; it[3] = 0x40 }
        val v64 = z64.copyOf().also { b -> for (i in b.indices step 2) { val t = b[i]; b[i] = b[i + 1]; b[i + 1] = t } }
        val n64 = z64.copyOf().also { b -> for (i in b.indices step 4) b.reverse(i, i + 4) }
        val refN64 = "5b5a0a5744eee1ce51aa23bcce4fa82d"
        assertEquals(refN64, Ra.hash(GameSystem.N64, z64))
        assertEquals(refN64, Ra.hash(GameSystem.N64, v64))
        assertEquals(refN64, Ra.hash(GameSystem.N64, n64))
    }

    private fun ByteArray.reverse(from: Int, to: Int) {
        var a = from; var b = to - 1
        while (a < b) { val t = this[a]; this[a] = this[b]; this[b] = t; a++; b-- }
    }
}

/** Сопоставление файла с игрой сайта и разбор ответов. */
class RaMatchTest {
    private val list = listOf(
        Ra.ListGame(1, "~Hack~ Final Fantasy VII: Ultimate", 50),
        Ra.ListGame(2, "Final Fantasy VII", 72, hashes = listOf("aaa")),
        Ra.ListGame(3, "Legend of Heroes, The: A Tear of Vermillion", 40),
        Ra.ListGame(4, "Biohazard | Resident Evil", 30),
        Ra.ListGame(5, "Pokemon - Emerald Version", 80, hashes = listOf("bbb")),
        Ra.ListGame(6, "Final Fantasy VII [Subset - Bonus]", 10),
    )

    @Test fun byHashFirst() {
        assertEquals(5, Ra.match(listOf("что-то совсем другое"), list, "BBB")?.id)
    }

    @Test fun byTitle() {
        assertEquals(2, Ra.match(listOf("Final Fantasy VII"), list, null)?.id)
        assertEquals(3, Ra.match(listOf("The Legend of Heroes - A Tear of Vermillion"), list, null)?.id)
        assertEquals(4, Ra.match(listOf("Resident Evil"), list, null)?.id)
        assertEquals(5, Ra.match(listOf("Pokémon - Emerald Version"), list, null)?.id)
        // Хак и подмножество не принимаются за саму игру
        assertNull(Ra.match(listOf("Final Fantasy VII: Ultimate"), list, null))
        assertNull(Ra.match(listOf("Final Fantasy VIII"), list, null))
    }

    @Test fun parsesGameList() {
        val body = """[{"Title":"Final Fantasy VII","ID":11319,"ConsoleID":12,"NumAchievements":72,"Points":761,"Hashes":["A1B2"]},
            {"Title":"Old format","ID":"55","NumAchievements":"12"}]"""
        val g = Ra.parseGameList(body)
        assertEquals(listOf(11319, 55), g.map { it.id })
        assertEquals(72, g[0].total)
        assertEquals(listOf("a1b2"), g[0].hashes)
        assertEquals(12, g[1].total)
    }

    @Test fun parsesCompletion() {
        val body = """{"Count":2,"Total":2,"Results":[
            {"GameID":11319,"Title":"Final Fantasy VII","ConsoleID":12,"MaxPossible":72,"NumAwarded":20,"NumAwardedHardcore":5},
            {"GameID":515,"MaxPossible":40,"NumAwarded":40,"NumAwardedHardcore":40}]}"""
        val (list, total) = Ra.parseCompletion(body)
        assertEquals(2, total)
        assertEquals(Ra.Completion(11319, 72, 20, 5), list[0])
    }

    @Test fun parsesGameProgress() {
        val body = """{"ID":11319,"Title":"Final Fantasy VII","ConsoleID":12,"NumAchievements":3,"NumAwardedToUser":2,"NumAwardedToUserHardcore":1,
            "Achievements":{
              "9":{"ID":9,"Title":"Третье","Description":"c","Points":25,"BadgeName":"300","DisplayOrder":3},
              "7":{"ID":7,"Title":"Первое","Description":"a","Points":5,"BadgeName":"100","DisplayOrder":1,"DateEarned":"2024-03-01 12:00:00","DateEarnedHardcore":"2024-03-01 12:00:00"},
              "8":{"ID":8,"Title":"Второе","Description":"b","Points":10,"BadgeName":"200","DisplayOrder":2,"DateEarned":"2024-03-02 10:00:00"}}}"""
        val p = Ra.parseGameProgress(body, now = 1L)!!
        assertEquals(listOf("Первое", "Второе", "Третье"), p.achievements.map { it.title })
        assertEquals(2, p.earned)
        assertEquals(1, p.earnedHardcore)
        assertEquals(15, p.earnedPoints)
        assertEquals(40, p.points)
        assertFalse(p.achievements[2].unlocked)
        // Игра без достижений: вместо объекта пустой массив
        val empty = Ra.parseGameProgress("""{"ID":5,"Title":"X","NumAchievements":0,"Achievements":[]}""")!!
        assertTrue(empty.achievements.isEmpty())
    }

    @Test fun percent() {
        assertEquals(27, Ra.percent(20, 72))
        assertEquals(100, Ra.percent(72, 72))
        assertEquals(0, Ra.percent(0, 0))
    }
}

/** Весь путь: опознать игры, загрузить прогресс и список достижений — на заготовленных ответах сайта. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class AchievementsFlowTest {
    private val gbaRom = ByteArray(4096) { (it % 251).toByte() }
    private val emerald = Game("e", "/r/gba/Pokemon Emerald (rus).gba", GameSystem.GBA, "Pokemon Emerald (rus)", emptyList(), "gba")
    private val ff7 = Game("f", "/r/psx/ff7.chd", GameSystem.PS1, "Final Fantasy VII", emptyList(), "psx")
    private val unknown = Game("u", "/r/psx/homebrew.chd", GameSystem.PS1, "My Homebrew", emptyList(), "psx")
    private val switchGame = Game("s", "/r/switch/x.nsp", GameSystem.SWITCH, "Zelda", emptyList(), "switch")

    private fun repo(t: Transport, account: Pair<String, String>? = "player" to "KEY123"): AchievementsRepository {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "ra").deleteRecursively()
        return AchievementsRepository(
            app, CoroutineScope(SupervisorJob() + Dispatchers.IO), t, account = { account },
            readRom = { g -> if (g.id == "e") gbaRom else null }, pause = {},
        )
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < end) { if (cond()) return; Thread.sleep(30) }
        error("не дождались")
    }

    private fun ok(s: String) = HttpResponse(200, s.toByteArray())

    @Test fun matchesGamesAndLoadsProgress() {
        val calls = mutableListOf<String>()
        val t = Transport { url, _, _ ->
            synchronized(calls) { calls += url }
            when {
                // Список GBA — с отпечатками; русская версия опознаётся по файлу, хотя название другое
                "API_GetGameList" in url && "i=5&" in url ->
                    ok("""[{"ID":515,"Title":"Pokemon - Emerald Version","NumAchievements":80,"Hashes":["${md5(gbaRom)}"]}]""")
                "API_GetGameList" in url && "i=12&" in url ->
                    ok("""[{"ID":11319,"Title":"Final Fantasy VII","NumAchievements":72}]""")
                "API_GetUserCompletionProgress" in url ->
                    ok("""{"Count":1,"Total":1,"Results":[{"GameID":11319,"MaxPossible":72,"NumAwarded":18,"NumAwardedHardcore":0}]}""")
                "API_GetGameInfoAndUserProgress" in url && "g=515" in url ->
                    ok("""{"ID":515,"Title":"Pokemon - Emerald Version","NumAchievements":2,"NumAwardedToUser":1,
                        "Achievements":{"1":{"ID":1,"Title":"Первый значок","Description":"d","Points":5,"BadgeName":"1","DisplayOrder":1,"DateEarned":"2024-01-01 00:00:00"},
                                        "2":{"ID":2,"Title":"Чемпион","Description":"d","Points":50,"BadgeName":"2","DisplayOrder":2}}}""")
                else -> HttpResponse(404)
            }
        }
        val r = repo(t)
        r.sync(listOf(emerald, ff7, unknown, switchGame), titlesFor = { listOf(it.title) })
        waitFor { r.state.value.progress.isNotEmpty() }
        val st = r.state.value
        assertEquals(515, st.matches["e"])
        assertEquals(11319, st.matches["f"])
        assertTrue("u" in st.matches && st.matches["u"] == null)
        assertFalse("Switch на сайте нет" , "s" in st.matches)
        // Прогресс: 18 из 72 у FF7; Emerald ещё не запускали — 0 из 80
        assertEquals(com.retrohome.launcher.data.RaSummary(11319, 72, 18, 0), st.summary("f"))
        assertEquals(0, st.summary("e")!!.earned)
        assertEquals(80, st.summary("e")!!.total)
        assertNull(st.summary("u"))
        // Ключ только в запросах к retroachievements.org
        assertTrue(calls.all { it.startsWith("https://retroachievements.org/API/") && "y=KEY123" in it })

        // Открыли достижения Emerald — полный список
        r.loadDetail(515)
        waitFor { r.state.value.details[515] != null }
        val d = r.state.value.details[515]!!
        assertEquals(listOf("Первый значок", "Чемпион"), d.achievements.map { it.title })
        assertEquals(1, r.state.value.summary("e")!!.earned)
        assertEquals(2, r.state.value.summary("e")!!.total)

        // Повторная синхронизация не грузит списки консолей заново (хранятся неделю)
        val before = calls.count { "API_GetGameList" in it }
        r.sync(listOf(emerald, ff7), titlesFor = { listOf(it.title) }, force = true)
        waitFor { calls.count { "API_GetUserCompletionProgress" in it } >= 2 }
        assertEquals(before, calls.count { "API_GetGameList" in it })
    }

    @Test fun wrongKeyIsReported() {
        val r = repo(Transport { _, _, _ -> HttpResponse(401) })
        assertNull(r.checkAccount("player", "bad"))
        assertEquals(RaStatus.AUTH_ERROR, r.state.value.status)
    }

    @Test fun profileCheck() {
        val r = repo(Transport { url, _, _ ->
            if ("API_GetUserProfile" in url) ok("""{"User":"Player","TotalPoints":1234}""") else HttpResponse(404)
        })
        assertEquals("Player" to 1234, r.checkAccount("player", "KEY"))
    }

    @Test fun noAccountDoesNothing() {
        var called = false
        val r = repo(Transport { _, _, _ -> called = true; HttpResponse(404) }, account = null)
        r.sync(listOf(ff7), titlesFor = { listOf(it.title) })
        Thread.sleep(300)
        assertFalse(called)
        assertEquals(RaStatus.NO_ACCOUNT, r.state.value.status)
        assertNotNull(r.state.value)
    }
}
