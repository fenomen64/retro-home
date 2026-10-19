package com.retrohome.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.AdaptiveIconDrawable
import com.retrohome.launcher.data.AppRepository
import com.retrohome.launcher.data.IconShapes
import com.retrohome.launcher.i18n.RU
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Форма значков: адаптивные значки обрезаются по выбранной (или системной) маске. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class IconShapeTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private fun icon() = app.getDrawable(R.drawable.ic_launcher) as AdaptiveIconDrawable
    private fun alpha(b: Bitmap, x: Int, y: Int) = Color.alpha(b.getPixel(x, y))

    @Test fun shapesCropTheIcon() {
        val n = 96
        val circle = IconShapes.render(icon(), n, IconShapes.maskFor("circle"))
        val square = IconShapes.render(icon(), n, IconShapes.maskFor("square"))
        val drop = IconShapes.render(icon(), n, IconShapes.maskFor("teardrop"))
        // Картинка всех форм — для проверки глазами
        val ids = IconShapes.ids
        val sheet = Bitmap.createBitmap(ids.size * 112, 112, Bitmap.Config.ARGB_8888)
        val c = Canvas(sheet)
        c.drawColor(Color.rgb(58, 48, 46))
        ids.forEachIndexed { i, id -> c.drawBitmap(IconShapes.render(icon(), n, IconShapes.maskFor(id)), i * 112f + 8, 8f, null) }
        File("build/screenshots").apply { mkdirs() }.resolve("icon_shapes.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertNotNull("маска круга разбирается", IconShapes.path(IconShapes.maskFor("circle")!!, 96f))
        // Круг: углы прозрачные, середина закрашена
        assertEquals(0, alpha(circle, 1, 1))
        assertEquals(255, alpha(circle, n / 2, n / 2))
        // Квадрат: угол закрашен — фон занимает всю форму, без белой подложки
        assertEquals(255, alpha(square, 1, 1))
        // Капля: почти острый угол только справа снизу (у круга там пусто)
        assertEquals(0, alpha(drop, 1, 1))
        assertEquals(255, alpha(drop, n - 6, n - 6))
        assertEquals(0, alpha(circle, n - 6, n - 6))

    }

    /** Обычный квадратный значок (так отдают значки многие прошивки) тоже обрезается по форме. */
    @Test fun squareBitmapIconsAreCroppedToo() {
        val n = 96
        val square = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 60, 60)) }
        val round = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888).also {
            Canvas(it).drawCircle(n / 2f, n / 2f, n / 2f - 2, android.graphics.Paint().apply { color = Color.BLUE; isAntiAlias = true })
        }
        val sq = IconShapes.render(android.graphics.drawable.BitmapDrawable(app.resources, square), n, IconShapes.maskFor("circle"))
        assertEquals(0, alpha(sq, 1, 1))
        assertEquals(255, alpha(sq, n / 2, n / 2))
        // У значка своя форма (прозрачные углы) — не трогаем
        assertFalse(IconShapes.isFullBleed(round))
        val rd = IconShapes.render(android.graphics.drawable.BitmapDrawable(app.resources, round), n, IconShapes.maskFor("square"))
        assertEquals(0, alpha(rd, 1, 1))
        // «Без обрезки» — квадрат остаётся квадратом
        assertEquals(255, alpha(IconShapes.render(android.graphics.drawable.BitmapDrawable(app.resources, square), n, IconShapes.maskFor("none")), 1, 1))
    }

    @Test fun choicesAndNames() {
        assertEquals(listOf("system", "circle", "squircle", "rounded", "square", "teardrop", "none"), IconShapes.ids)
        assertNull(IconShapes.maskFor("none"))
        // «Как в системе» всегда даёт какую-то маску (если система не сказала — сквиркл)
        assertNotNull(IconShapes.maskFor("system"))
        assertEquals("Как в системе", RU.ext.shape.name("system"))
        assertEquals("Капля", RU.ext.shape.name("teardrop"))
    }

    @Test fun changingShapeClearsCachedIcons() {
        val repo = AppRepository(app, CoroutineScope(SupervisorJob() + Dispatchers.IO))
        assertFalse("форма не менялась", repo.refreshShape())
        repo.iconShape = "circle"
        repo.iconShape = "circle"
        assertFalse(repo.refreshShape())
        repo.iconShape = "square"
        assertNotEquals("circle", repo.iconShape)
        assertTrue(IconShapes.maskFor("square") != IconShapes.maskFor("circle"))
    }
}
