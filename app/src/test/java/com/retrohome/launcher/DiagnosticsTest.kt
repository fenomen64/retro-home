package com.retrohome.launcher

import android.app.Application
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.retrohome.launcher.data.AppRepository
import com.retrohome.launcher.data.DiagLog
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameLibrary
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.HttpResponse
import com.retrohome.launcher.data.MetaRepository
import com.retrohome.launcher.data.Store
import com.retrohome.launcher.data.Transport
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.ui.DiagnosticsScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Отчёт «Диагностика обложек и описаний». */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class DiagnosticsTest {
    @get:Rule val compose = createComposeRule()

    private val crash = Game("cr", "/ROMs/psx/Crash Bandicoot (USA).bin", GameSystem.PS1, "Crash Bandicoot", listOf("USA"), "psx")

    @Before fun reset() = DiagLog.clear()

    private fun waitDone(r: MetaRepository) {
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            Thread.sleep(50)
            if (!r.running && r.progress.value == null && DiagLog.snapshot().any { "Загрузка завершена" in it }) return
        }
        error("не дождались загрузки")
    }

    private fun repo(t: Transport): MetaRepository {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "meta.json").delete()
        app.cacheDir.deleteRecursively()
        return MetaRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO), t, sleep = {}, isOnline = { true })
    }

    @Test fun logExplainsWhyNothingWasFound() {
        val t = object : Transport {
            override fun open(url: String, accept: String?, userAgent: String) = when {
                // В списке обложек нет ничего похожего на игру
                url.endsWith("/Named_Boxarts/") -> HttpResponse(200, """<a href="Spyro%20(USA).png">Spyro (USA).png</a>""".toByteArray())
                else -> HttpResponse(200, """{"batchcomplete":true,"query":{"search":[]}}""".toByteArray())
            }
        }
        val r = repo(t)
        r.fetch(listOf(crash))
        waitDone(r)
        val log = DiagLog.snapshot().joinToString("\n")
        assertTrue(log, "игр 1, нужны обложки 1, описания 1" in log)
        assertTrue(log, "Список обложек PS1: libretro" in log && "1 имён" in log)
        assertTrue(log, "[PS1] Crash Bandicoot: обложка — нет похожего имени в списке" in log)
        assertTrue(log, "[PS1] Crash Bandicoot: описание — не найдено" in log)
    }

    @Test fun networkFailureAndBrokenImagesAreLogged() {
        val t = object : Transport {
            override fun open(url: String, accept: String?, userAgent: String) = when {
                url.endsWith("/Named_Boxarts/") -> HttpResponse(200, """<a href="Crash%20Bandicoot%20(USA).png">Crash Bandicoot (USA).png</a>""".toByteArray())
                // Вместо картинки пришла страница
                "thumbnails.libretro.com" in url -> HttpResponse(200, "<html>blocked</html>".toByteArray())
                else -> HttpResponse(0, error = "java.net.SocketTimeoutException: timeout")
            }
        }
        val r = repo(t)
        r.fetch(listOf(crash))
        waitDone(r)
        val log = DiagLog.snapshot().joinToString("\n")
        assertTrue(log, "обложка «Crash Bandicoot (USA)» — не удалось прочитать картинку" in log && "<html>blocked" in log)
        assertTrue(log, "описание — сбой сети" in log && "SocketTimeoutException" in log)
    }

    @Test fun reportHasFoldersAndCountsButNoEmail() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "diag.json").delete()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = Store(File(app.filesDir, "diag.json"), scope)
        val c = LauncherController(app, store, AppRepository(app, scope), GameLibrary(app, scope), repo(object : Transport {
            override fun open(url: String, accept: String?, userAgent: String) = HttpResponse(404)
        }), scope)
        store.update { d -> d.copy(settings = d.settings.copy(contactEmail = "someone@example.com", raKey = "SECRETKEY")) }
        DiagLog.add("Сканирование: папок 1, игр 0")
        val report = c.diagnostics()
        assertTrue(report, "Почта для Википедии: указана" in report)
        assertFalse(report, "someone@example.com" in report)
        assertFalse(report, "SECRETKEY" in report)
        assertTrue(report, "Папка ROMs: НЕ ВЫБРАНА" in report)
        assertTrue(report, "Сканирование: папок 1, игр 0" in report)
        assertTrue(report, report.startsWith("Retro Home"))
    }

    @Test fun shareButtonSendsTheReport() {
        var shared: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides RU) {
                DiagnosticsScreen(onBack = {}, report = { "Retro Home test\n\nПапки (0):" }, onShare = { shared = it }, onCopy = {}, onRetry = {})
            }
        }
        compose.onNodeWithText(RU.ext.diag.share).performClick()
        compose.runOnIdle { assertEquals("Retro Home test\n\nПапки (0):", shared) }
        compose.onNodeWithText("Папки (0):").assertExists()
    }

    @Test
    @Config(qualifiers = "w768dp-h432dp-land-xhdpi")
    fun screenshot() {
        val report = """Retro Home 0.29 · Android 15 (SDK 35) · Retroid Pocket G2
Интернет: есть · Язык: RU · Автозагрузка: вкл · Почта для Википедии: нет
Папка ROMs: выбрана, доступ есть · сканирование: 19.10 23:40
Загрузка сейчас: не идёт

Папки (3):
  psx → PS1: игр 12
  gba → GBA: игр 30
  Games → не распознана: игр 0

Игры по консолям (всего 42):
  GBA: 30 · обложки 0 · описания 0 · не искали 0 · ждут повтора 0"""
        compose.setContent {
            CompositionLocalProvider(LocalStrings provides RU) {
                DiagnosticsScreen(onBack = {}, report = { report }, onShare = {}, onCopy = {}, onRetry = {})
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/diagnostics.png")
    }

    @Test fun logKeepsOnlyRecentLines() {
        repeat(1000) { DiagLog.add("строка $it") }
        val lines = DiagLog.snapshot()
        assertEquals(400, lines.size)
        assertTrue(lines.last().endsWith("строка 999"))
    }
}
