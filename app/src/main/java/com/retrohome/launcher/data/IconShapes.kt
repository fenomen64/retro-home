package com.retrohome.launcher.data

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import androidx.core.graphics.drawable.toBitmap

/**
 * Форма значков приложений. Адаптивный значок (фон + рисунок) лаунчер обрезает сам:
 * по форме из системы («как в системе») или по выбранной в настройках.
 * Маски — в координатах 100×100, как config_icon_mask в Android.
 */
object IconShapes {
    const val SYSTEM = "system"
    const val NONE = "none"

    private val masks = linkedMapOf(
        "circle" to "M50,0A50,50,0,1,1,50,100A50,50,0,1,1,50,0Z",
        "squircle" to "M50,0C10,0 0,10 0,50 0,90 10,100 50,100 90,100 100,90 100,50 100,10 90,0 50,0Z",
        "rounded" to "M50,0L88,0C94.6,0 100,5.4 100,12L100,88C100,94.6 94.6,100 88,100L12,100C5.4,100 0,94.6 0,88L0,12C0,5.4 5.4,0 12,0L50,0Z",
        "square" to "M0,0H100V100H0Z",
        "teardrop" to "M50,0A50,50,0,0,1,100,50L100,85A15,15,0,0,1,85,100L50,100A50,50,0,0,1,50,0Z",
    )

    /** Варианты по порядку в настройках. */
    val ids: List<String> = listOf(SYSTEM) + masks.keys + NONE

    /** Форма значков, заданная в системе (её меняют темы и настройка «Форма значков»). */
    fun systemMask(): String? = runCatching {
        val r = Resources.getSystem()
        val id = r.getIdentifier("config_icon_mask", "string", "android")
        if (id == 0) null else r.getString(id).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Маска для выбранного варианта; null — не обрезать (значок как есть). */
    fun maskFor(id: String): String? = when (id) {
        NONE -> null
        SYSTEM -> systemMask() ?: masks["squircle"]
        else -> masks[id] ?: systemMask()
    }

    /**
     * Значок размером size×size. Адаптивный — по маске: слои занимают 150% значка
     * (видна центральная часть, как в Android), края маски сглажены. Обычный — как есть.
     */
    fun render(d: Drawable, size: Int, mask: String?): Bitmap {
        if (d !is AdaptiveIconDrawable || mask == null) return d.toBitmap(size, size)
        val path = runCatching { PathParser.createPathFromPathData(mask) }.getOrNull() ?: return d.toBitmap(size, size)
        path.transform(Matrix().apply { setScale(size / 100f, size / 100f) })
        // Слои рисуем целиком, затем переносим внутрь фигуры: картинка служит «заливкой» пути
        val full = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val fc = Canvas(full)
        val inset = size / 4
        for (layer in listOfNotNull(d.background, d.foreground)) {
            layer.setBounds(-inset, -inset, size + inset, size + inset)
            layer.draw(fc)
        }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = android.graphics.BitmapShader(full, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        Canvas(out).drawPath(path, paint)
        full.recycle()
        return out
    }

    /** Путь маски (для проверок и предпросмотра). */
    fun path(mask: String, size: Float): Path? = runCatching {
        PathParser.createPathFromPathData(mask).apply { transform(Matrix().apply { setScale(size / 100f, size / 100f) }) }
    }.getOrNull()
}
