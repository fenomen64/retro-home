package com.retrohome.launcher

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Иконка приложения — адаптивная: заполняет любую форму значка и поддерживает тематические значки. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, sdk = [35])
class AppIconTest {
    @Test fun iconIsAdaptiveWithMonochrome() {
        val app = RuntimeEnvironment.getApplication()
        val icon = app.getDrawable(R.drawable.ic_launcher)
        assertTrue("иконка должна быть адаптивной, иначе Android сажает её в белый кружок", icon is AdaptiveIconDrawable)
        icon as AdaptiveIconDrawable
        assertNotNull(icon.background)
        assertNotNull(icon.foreground)
        assertNotNull(icon.monochrome)

        // Снимки: полный слой 108 dp и как в круглой маске — для проверки глазами
        val size = 432
        val out = File("build/screenshots").apply { mkdirs() }
        fun render(name: String, clip: Path?, layers: List<android.graphics.drawable.Drawable>) {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            clip?.let { c.clipPath(it) }
            // Видимая часть — центральные 72 из 108 dp
            val k = size / 72f
            c.scale(k, k)
            c.translate(-18f, -18f)
            layers.forEach { it.setBounds(0, 0, 108, 108); it.draw(c) }
            File(out, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val circle = Path().apply { addCircle(size / 2f, size / 2f, size / 2f, Path.Direction.CW) }
        render("app_icon_circle.png", circle, listOf(icon.background, icon.foreground))
        render("app_icon_square.png", null, listOf(icon.background, icon.foreground))
        render("app_icon_mono.png", circle, listOf(icon.monochrome!!))
    }
}
