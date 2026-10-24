package com.retrohome.launcher

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.retrohome.launcher.data.CoverArt
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoverArtPreviewTest {
    @Test fun digitalCaseSize() {
        val art = Bitmap.createBitmap(500, 500, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF3366AA.toInt()) }
        val out = CoverArt.digitalCase(art, "DIGITAL")
        assertEquals(CoverArt.W, out.width)
        assertEquals(CoverArt.H, out.height)
        // Превью на настоящих картинках (если положены рядом при разработке)
        val dir = System.getenv("COVER_PREVIEW_DIR") ?: return
        for ((file, badge) in listOf("well.jpg" to "DIGITAL", "sea.jpg" to null)) {
            val src = BitmapFactory.decodeFile(File(dir, file).path) ?: continue
            File(dir, file.replace(".jpg", "_case.png")).outputStream().use { CoverArt.digitalCase(src, badge).compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
