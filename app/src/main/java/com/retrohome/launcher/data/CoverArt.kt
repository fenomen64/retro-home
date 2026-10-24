package com.retrohome.launcher.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils

/**
 * Своя «цифровая коробка» для игр, у которых есть только квадратная картинка (игры только из eShop):
 * в пропорциях коробки Switch, без чужих логотипов. Сверху полоса «SWITCH · DIGITAL», под ней картинка
 * целиком, внизу название игры. Фон — размытая сама картинка.
 */
object CoverArt {
    const val W = 400
    const val H = 645
    /** Цвет Switch в лаунчере (как у вкладки). */
    private const val ACCENT = 0xFFD7263D.toInt()

    fun digitalCase(art: Bitmap, title: String, platform: String = "SWITCH"): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val filter = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

        // Фон: картинка на весь футляр, сильно размытая и затемнённая
        val side = minOf(art.width, art.height)
        val crop = Rect((art.width - side) / 2, (art.height - side) / 2, (art.width + side) / 2, (art.height + side) / 2)
        val tiny = Bitmap.createBitmap(10, 16, Bitmap.Config.ARGB_8888)
        Canvas(tiny).drawBitmap(art, crop, Rect(0, 0, 10, 16), filter)
        c.drawBitmap(tiny, null, Rect(0, 0, W, H), filter)
        tiny.recycle()
        c.drawColor(Color.argb(150, 10, 10, 14))

        // Верхняя полоса
        val band = 46f
        c.drawRect(0f, 0f, W.toFloat(), band, Paint().apply { color = Color.argb(235, 18, 18, 22) })
        c.drawRect(0f, band - 3f, W.toFloat(), band, Paint().apply { color = ACCENT })
        val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 17f; typeface = Typeface.DEFAULT_BOLD; letterSpacing = 0.18f
        }
        c.drawRoundRect(RectF(16f, 13f, 22f, 31f), 3f, 3f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT })
        c.drawText(platform, 32f, 29f, label)
        label.color = Color.argb(170, 255, 255, 255)
        label.textSize = 14f
        c.drawText("DIGITAL", W - 16f - label.measureText("DIGITAL"), 28.5f, label)

        // Картинка целиком, со скруглением и тенью
        val pad = 18f
        val top = band + 16f
        val art1 = RectF(pad, top, W - pad, top + (W - 2 * pad))
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; setShadowLayer(14f, 0f, 6f, Color.argb(160, 0, 0, 0)) }
        c.drawRoundRect(art1, 16f, 16f, shadow)
        c.save()
        val clip = android.graphics.Path().apply { addRoundRect(art1, 16f, 16f, android.graphics.Path.Direction.CW) }
        c.clipPath(clip)
        c.drawBitmap(art, crop, art1, filter)
        c.restore()

        // Название игры внизу на тёмной подложке — посередине между картинкой и нижней полоской
        val fade = Paint().apply {
            shader = LinearGradient(0f, art1.bottom, 0f, H.toFloat(), Color.argb(0, 0, 0, 0), Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, art1.bottom, W.toFloat(), H.toFloat(), fade)
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; typeface = Typeface.DEFAULT_BOLD }
        val width = (W - 2 * pad).toInt()
        val layout = StaticLayout.Builder.obtain(title, 0, title.length, tp, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.05f)
            .setMaxLines(3)
            .setEllipsize(TextUtils.TruncateAt.END)
            .build()
        val area = (H - 26f) - art1.bottom
        val textTop = art1.bottom + ((area - layout.height) / 2f).coerceAtLeast(14f)
        c.save()
        c.translate(pad, textTop)
        layout.draw(c)
        c.restore()
        c.drawRect(pad, H - 22f, pad + 56f, H - 17f, Paint().apply { color = ACCENT })
        return out
    }
}
