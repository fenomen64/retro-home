package com.retrohome.launcher

import android.app.Application
import android.graphics.Bitmap
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameMeta
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.HttpResponse
import com.retrohome.launcher.data.JavaFileSource
import com.retrohome.launcher.data.META_VERSION
import com.retrohome.launcher.data.MetaRepository
import com.retrohome.launcher.data.Scanner
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
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** Список обложек, испорченный по дороге (VPN, фильтр, страница-заглушка), не должен запоминаться на неделю. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class CoverIndexTest {
    private val crash = Game("cr", "/ROMs/psx/Crash Bandicoot (USA).bin", GameSystem.PS1, "Crash Bandicoot", listOf("USA"), "psx")

    private val png: ByteArray by lazy {
        val b = Bitmap.createBitmap(40, 56, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private val psxIndex = """<a href="Crash%20Bandicoot%20(USA).png">Crash Bandicoot (USA).png</a>"""

    @Volatile private var broken = true

    private val t = object : Transport {
        override fun open(url: String, accept: String?, userAgent: String): HttpResponse = when {
            "thumbnails.libretro.com" in url && url.endsWith("/Named_Boxarts/") ->
                HttpResponse(200, (if (broken) "<html><body>Доступ ограничен</body></html>" else psxIndex).toByteArray())
            "thumbnails.libretro.com" in url -> HttpResponse(200, png)
            else -> HttpResponse(404)
        }
    }

    private fun waitFor(r: MetaRepository, retry: Boolean): GameMeta {
        r.fetch(listOf(crash), retryMissing = retry)
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50)
            val m = r.meta.value["cr"]
            if (r.progress.value == null && m?.ver == META_VERSION && m.coverTried == !broken || (m?.cover != null)) return m!!
        }
        error("не дождались загрузки")
    }

    @Test fun brokenIndexIsAFailureNotAMissingCover() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "meta.json").delete()
        app.cacheDir.deleteRecursively()
        val r = MetaRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO), t, sleep = {}, isOnline = { true })
        val first = waitFor(r, retry = false)
        assertNull(first.cover)
        // Не «обложки нет», а сбой: повторим позже, и причина видна
        assertFalse(first.coverTried)
        assertTrue(first.retryAt > System.currentTimeMillis())
        assertNotNull(r.problem.value)
        // Сайт снова отдаёт нормальный список — обложка находится, испорченный список не запомнился
        broken = false
        val second = waitFor(r, retry = true)
        assertNotNull(second.cover)
    }

    @Test fun biosNames() {
        assertTrue(Scanner.isBios("SCPH1001.bin"))
        assertTrue(Scanner.isBios("scph-5501.bin"))
        assertTrue(Scanner.isBios("ps2-0230a-20080220.bin"))
        assertTrue(Scanner.isBios("gba_bios.bin"))
        assertTrue(!Scanner.isBios("BioShock.iso"))
        assertTrue(!Scanner.isBios("Crash Bandicoot (USA).bin"))
    }

    @Test fun ps1BinWithoutCueIsAGame() {
        val root = kotlin.io.path.createTempDirectory("roms").toFile()
        try {
            val dir = File(root, "psx").apply { mkdirs() }
            File(dir, "Crash Bandicoot (USA).bin").writeText("x")
            // BIOS — не игры
            File(dir, "SCPH1001.bin").writeText("x")
            File(dir, "PSX BIOS (USA).bin").writeText("x")
            // Несколько дорожек без .cue — одна игра
            File(dir, "Tekken 3 (USA) (Track 1).bin").writeText("x")
            File(dir, "Tekken 3 (USA) (Track 2).bin").writeText("x")
            // .bin из .cue отдельно не показывается
            File(dir, "Spyro the Dragon (USA).cue").writeText("FILE \"Spyro the Dragon (USA).bin\" BINARY\n  TRACK 01 MODE2/2352\n")
            File(dir, "Spyro the Dragon (USA).bin").writeText("x")
            val games = Scanner.scan(JavaFileSource(root), emptyMap()).games
            assertEquals(listOf("Crash Bandicoot", "Spyro the Dragon", "Tekken 3"), games.map { it.title })
            assertTrue(games.first { it.title == "Spyro the Dragon" }.fileName.endsWith(".cue"))
        } finally { root.deleteRecursively() }
    }
}
