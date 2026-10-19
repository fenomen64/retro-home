package com.retrohome.launcher

import android.app.Application
import android.graphics.Bitmap
import com.retrohome.launcher.data.DescSource
import com.retrohome.launcher.data.DiagLog
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameMeta
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.HttpResponse
import com.retrohome.launcher.data.META_VERSION
import com.retrohome.launcher.data.MetaRepository
import com.retrohome.launcher.data.SwitchDb
import com.retrohome.launcher.data.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** tinfoil.media недоступен (как у пользователя) — данные Switch берутся из Nlib (api.nlib.cc). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class SwitchMirrorTest {
    private val totk = Game("tk", "/ROMs/switch/Zelda TotK [0100F2C0115B6000][v0].nsp", GameSystem.SWITCH, "Zelda TotK", emptyList(), "switch")

    // Ответ Nlib в том виде, как его отдаёт api.nlib.cc (ссылки на снимки — http)
    private val nlib = """{"description":"An epic adventure across the land and skies of Hyrule awaits in The Legend of Zelda™: Tears of the Kingdom for Nintendo Switch™.",
        "id":"0100F2C0115B6000","name":"The Legend of Zelda™: Tears of the Kingdom",
        "screens":{"count":2,"screenshots":["http://api.nlib.cc/nx/0100F2C0115B6000/screen/1","http://api.nlib.cc/nx/0100F2C0115B6000/screen/2"]}}"""

    private val jpg: ByteArray by lazy {
        val b = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF2266AA.toInt()) }
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }
    private val dnsFail = HttpResponse(0, error = "java.net.UnknownHostException: Unable to resolve host \"tinfoil.media\": No address associated with hostname")
    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before fun reset() = DiagLog.clear()

    private fun repo(route: (String) -> HttpResponse): MetaRepository {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "meta.json").delete()
        app.cacheDir.deleteRecursively()
        val t = object : Transport {
            override fun open(url: String, accept: String?, userAgent: String): HttpResponse { calls += url; return route(url) }
        }
        return MetaRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO), t, sleep = {}, isOnline = { true })
    }

    private fun waitFor(r: MetaRepository, g: Game): GameMeta {
        r.fetch(listOf(g))
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50)
            val m = r.meta.value[g.id]
            if (!r.running && m?.ver == META_VERSION) return m
        }
        error("не дождались загрузки")
    }

    @Test fun switchDataComesFromNlibWhenTinfoilIsDown() {
        val r = repo { url ->
            when {
                "tinfoil.media" in url -> dnsFail
                url.startsWith("https://api.nlib.cc/nx/0100F2C0115B6000?") -> HttpResponse(200, nlib.toByteArray())
                url.startsWith("https://api.nlib.cc/nx/0100F2C0115B6000/icon") -> HttpResponse(200, jpg)
                url.startsWith("https://api.nlib.cc/nx/0100F2C0115B6000/screen/1") -> HttpResponse(200, jpg)
                "wikipedia.org" in url && "list=search" in url -> HttpResponse(200, """{"query":{"search":[]}}""".toByteArray())
                "wbsearchentities" in url -> HttpResponse(200, """{"search":[]}""".toByteArray())
                else -> HttpResponse(404)
            }
        }
        val m = waitFor(r, totk)
        assertNotNull("обложка из иконки Nlib", m.cover)
        assertNotNull("снимок экрана из Nlib", m.snap)
        assertEquals("The Legend of Zelda: Tears of the Kingdom", m.name)
        assertTrue(m.descEn!!.startsWith("An epic adventure"))
        assertEquals(DescSource.ESHOP, m.srcEn)
        // Снимки — по https, хотя Nlib присылает ссылки http
        assertTrue(calls.any { it == "https://api.nlib.cc/nx/0100F2C0115B6000/screen/1" })
        assertTrue(DiagLog.snapshot().any { "api.nlib.cc" in it })
    }

    @Test fun staleSwitchListIsUsedWhenTinfoilIsDown() {
        val app = RuntimeEnvironment.getApplication()
        val r = repo { url -> if ("tinfoil.media" in url || "nlib" in url) dnsFail else HttpResponse(404) }
        // Список, сохранённый месяц назад
        val f = File(File(app.cacheDir, "thumbidx").apply { mkdirs() }, "SWITCH_tinfoil.tsv")
        f.writeText("0100F2C0115B6000\tThe Legend of Zelda: Tears of the Kingdom")
        f.setLastModified(System.currentTimeMillis() - 30L * 24 * 3600 * 1000)
        val g = Game("tk2", "/ROMs/switch/Zelda Tears of the Kingdom.nsp", GameSystem.SWITCH, "Zelda Tears of the Kingdom", emptyList(), "switch")
        r.refetch(g, "The Legend of Zelda: Tears of the Kingdom")
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && DiagLog.snapshot().none { "взят сохранённый" in it }) Thread.sleep(50)
        assertTrue(DiagLog.snapshot().joinToString("\n"), DiagLog.snapshot().any { "Список игр Switch: tinfoil недоступен, взят сохранённый (1)" in it })
    }

    /** Как у пользователя: tinfoil зависает или отвечает 500 — не ждём его для каждой игры, берём из Nlib. */
    @Test fun hangingTinfoilIsSkippedAfterFirstFailure() {
        val ids = listOf("0100A83022A22000", "0100BA4026E94000", "0100DC0018CD6000", "010004E01523C000", "0100D9B00E22C000", "01009D6022DC2000")
        val games = ids.mapIndexed { i, id -> Game("g$i", "/ROMs/switch/Game $i [$id][v0].nsp", GameSystem.SWITCH, "Game $i", emptyList(), "switch") }
        val r = repo { url ->
            when {
                "tinfoil.media" in url -> HttpResponse(0, error = "java.net.SocketTimeoutException: timeout")
                url.startsWith("https://api.nlib.cc/nx/") && "/icon" in url -> HttpResponse(200, jpg)
                url.startsWith("https://api.nlib.cc/nx/") && "?" in url -> {
                    val id = url.substringAfter("/nx/").substringBefore("?")
                    HttpResponse(200, """{"id":"$id","name":"Game $id","description":"About $id"}""".toByteArray())
                }
                "wikipedia.org" in url && "list=search" in url -> HttpResponse(200, """{"query":{"search":[]}}""".toByteArray())
                "wbsearchentities" in url -> HttpResponse(200, """{"search":[]}""".toByteArray())
                else -> HttpResponse(404)
            }
        }
        r.fetch(games)
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end && (r.running || games.any { r.meta.value[it.id]?.ver != META_VERSION })) Thread.sleep(50)
        val m = r.meta.value
        assertTrue(games.all { m[it.id]?.cover != null })
        assertTrue(games.all { m[it.id]?.descEn?.startsWith("About") == true })
        // tinfoil пробовали лишь в начале (несколько игр грузятся параллельно), а не для каждой из шести
        assertTrue(calls.count { "tinfoil.media" in it }.toString(), calls.count { "tinfoil.media" in it } <= 8)
        assertTrue(DiagLog.snapshot().any { "беру данные Switch из запасного источника" in it })
    }

    @Test fun nlibParsing() {
        val p = SwitchDb.parseNlib(nlib)!!
        assertEquals("The Legend of Zelda: Tears of the Kingdom", p.name)
        assertEquals(listOf("https://api.nlib.cc/nx/0100F2C0115B6000/screen/1", "https://api.nlib.cc/nx/0100F2C0115B6000/screen/2"), p.shots)
        assertEquals(null, SwitchDb.parseNlib("""{"error":"not found"}"""))
    }
}
