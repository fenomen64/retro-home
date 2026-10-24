package com.retrohome.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint

/**
 * Обложка Switch из квадратной картинки eShop: картинка во всю ширину, над ней чёрная полоска
 * с красной надписью «SWITCH» (и серой «DIGITAL» справа у игр, выходивших только в eShop).
 * Без чужих логотипов, у всех игр одного размера.
 */
object CoverArt {
    const val W = 400
    const val BAND = 44
    const val H = W + BAND
    /** Цвет Switch в лаунчере (как у вкладки). */
    private const val ACCENT = 0xFFE4313F.toInt()

    /** badge — подпись справа на полосе («DIGITAL») или null. */
    fun digitalCase(art: Bitmap, badge: String? = null, platform: String = "SWITCH"): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT; textSize = 20f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.16f
        }
        val base = BAND / 2f - (label.descent() + label.ascent()) / 2f
        c.drawText(platform, 16f, base, label)
        if (badge != null) {
            label.color = Color.argb(150, 255, 255, 255)
            label.textSize = 14f
            val b2 = BAND / 2f - (label.descent() + label.ascent()) / 2f
            c.drawText(badge, W - 16f - label.measureText(badge), b2, label)
        }
        val side = minOf(art.width, art.height)
        val crop = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
        c.drawBitmap(art, crop, RectF(0f, BAND.toFloat(), W.toFloat(), H.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        return out
    }
}
