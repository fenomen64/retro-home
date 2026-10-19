package com.retrohome.launcher

import android.app.Application
import android.graphics.Bitmap
import com.retrohome.launcher.data.DiagLog
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameMeta
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.HttpResponse
import com.retrohome.launcher.data.META_VERSION
import com.retrohome.launcher.data.MetaRepository
import com.retrohome.launcher.data.Thumbnails
import com.retrohome.launcher.data.Transport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** Сайт обложек libretro недоступен (как у пользователя: DNS не находит адрес) — обложки берутся с GitHub. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class CoverMirrorTest {
    private fun gba(id: String, title: String) = Game(id, "/ROMs/gba/$title.gba", GameSystem.GBA, title, emptyList(), "gba")
    private val wars = gba("aw", "Advance Wars")
    private val batman = gba("bm", "Batman - Rise of Sin Tzu")

    private val png: ByteArray by lazy {
        val b = Bitmap.createBitmap(40, 56, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val rootTree = """{"sha":"r","tree":[{"path":".gitignore","type":"blob","sha":"a"},
        {"path":"Named_Boxarts","type":"tree","sha":"boxsha"},{"path":"Named_Snaps","type":"tree","sha":"s"}],"truncated":false}"""
    private val boxTree = """{"sha":"boxsha","tree":[
        {"path":"Advance Wars (USA).png","type":"blob","mode":"100644","sha":"1"},
        {"path":"Batman - Rise of Sin Tzu (USA) (Beta).png","type":"blob","mode":"120000","sha":"2"},
        {"path":"Batman - Rise of Sin Tzu (USA).png","type":"blob","mode":"100644","sha":"3"}],"truncated":false}"""

    private val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
    private val dnsFail = HttpResponse(0, error = "java.net.UnknownHostException: Unable to resolve host \"thumbnails.libretro.com\": No address associated with hostname")

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

    private fun waitAll(r: MetaRepository, games: List<Game>): Map<String, GameMeta> {
        val end = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50)
            val m = r.meta.value
            if (!r.running && games.all { m[it.id]?.ver == META_VERSION }) return m
        }
        error("не дождались загрузки")
    }

    @Test fun coversComeFromGitHubWhenLibretroIsUnreachable() {
        val r = repo { url ->
            when {
                "thumbnails.libretro.com" in url -> dnsFail
                url.endsWith("/git/trees/master") -> HttpResponse(200, rootTree.toByteArray())
                url.endsWith("/git/trees/boxsha") -> HttpResponse(200, boxTree.toByteArray())
                // Файл-ссылка: GitHub отдаёт имя файла, на который она ведёт
                "raw.githubusercontent.com" in url && "Beta" in url -> HttpResponse(200, "Batman - Rise of Sin Tzu (USA).png".toByteArray())
                "raw.githubusercontent.com" in url && "/Named_Boxarts/" in url -> HttpResponse(200, png)
                else -> HttpResponse(404)
            }
        }
        val m = waitAll(r.also { it.fetch(listOf(wars, batman)) }, listOf(wars, batman))
        assertNotNull(m["aw"]!!.cover)
        assertNotNull(m["bm"]!!.cover)
        assertEquals("Advance Wars (USA)", m["aw"]!!.thumbName)
        // Сайт libretro пробовали один раз (адрес не находится — без повторов), дальше сразу GitHub
        assertEquals(1, calls.count { "thumbnails.libretro.com" in it })
        assertTrue(calls.any { it.startsWith("https://raw.githubusercontent.com/libretro-thumbnails/Nintendo_-_Game_Boy_Advance/master/Named_Boxarts/Advance%20Wars%20%28USA%29.png") })
        assertTrue(DiagLog.snapshot().joinToString("\n"), DiagLog.snapshot().any { "беру обложки с GitHub" in it })
    }

    @Test fun unreachableEverywhereDoesNotRetryForEveryGame() {
        val games = (1..20).map { gba("g$it", "Game $it") }
        val r = repo { url -> if ("thumbnails.libretro.com" in url || "github" in url) dnsFail else HttpResponse(404) }
        val m = waitAll(r.also { it.fetch(games) }, games)
        // Список обложек не скачивается заново для каждой из 20 игр
        assertTrue(calls.toString(), calls.count { "/Named_Boxarts/" in it && it.endsWith("/") } <= 1)
        assertTrue(calls.count { "/git/trees/master" in it } <= 1)
        // Это сбой, а не «обложки нет»: повторим позже
        assertTrue(games.all { m[it.id]!!.cover == null && !m[it.id]!!.coverTried && m[it.id]!!.retryAt > 0 })
    }

    @Test fun repeatedRetryDoesNotRestartTheLoad() {
        val r = repo { url ->
            Thread.sleep(200)
            if ("thumbnails.libretro.com" in url && url.endsWith("/")) HttpResponse(200, """<a href="x">Advance Wars (USA).png</a>""".toByteArray()) else HttpResponse(404)
        }
        r.fetch(listOf(wars), retryMissing = true)
        r.fetch(listOf(wars), retryMissing = true)
        r.fetch(listOf(wars), retryMissing = true)
        waitAll(r, listOf(wars))
        val starts = DiagLog.snapshot().count { "Загрузка (повтор ненайденного)" in it }
        assertEquals(1, starts)
        assertEquals(2, DiagLog.snapshot().count { "Загрузка уже идёт" in it })
    }

    @Test fun mirrorHelpers() {
        assertEquals("Nintendo_-_Game_Boy_Advance", Thumbnails.mirrorRepo(GameSystem.GBA))
        assertEquals("Sega_-_Mega_Drive_-_Genesis", Thumbnails.mirrorRepo(GameSystem.MD))
        assertEquals("boxsha", Thumbnails.treeDirSha(rootTree, "Named_Boxarts"))
        assertEquals(listOf("Advance Wars (USA)", "Batman - Rise of Sin Tzu (USA) (Beta)", "Batman - Rise of Sin Tzu (USA)"), Thumbnails.treePngNames(boxTree))
        assertEquals("Batman - Rise of Sin Tzu (USA)", Thumbnails.linkTarget("Batman - Rise of Sin Tzu (USA).png".toByteArray()))
        assertNull(Thumbnails.linkTarget(png))
        assertNull(Thumbnails.linkTarget("<html>blocked</html>".toByteArray()))
    }
}
