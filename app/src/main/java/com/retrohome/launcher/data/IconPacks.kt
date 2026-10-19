package com.retrohome.launcher.data

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.core.graphics.drawable.toBitmap
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/** Установленный набор значков. */
data class IconPackInfo(val packageName: String, val label: String)

/**
 * Разбор appfilter.xml набора значков — стандарт, который понимают Nova, Lawnchair и другие лаунчеры:
 * `<item component="ComponentInfo{пакет/активность}" drawable="имя"/>`, плюс подложка
 * (iconback), маска (iconmask), накладка (iconupon) и масштаб (scale) для значков, которых в наборе нет.
 */
class AppFilter(
    /** "пакет/активность" -> имя картинки. */
    val components: Map<String, String>,
    val backs: List<String> = emptyList(),
    val mask: String? = null,
    val upon: String? = null,
    val scale: Float = 1f,
) {
    /** Значок для приложения: точное совпадение экрана, иначе любой значок того же пакета. */
    fun drawableFor(key: String): String? {
        components[key]?.let { return it }
        val pkg = key.substringBefore('/')
        return components.entries.firstOrNull { it.key.substringBefore('/') == pkg }?.value
    }

    companion object {
        private val componentRe = Regex("""ComponentInfo\{([^/}]+)/([^}]+)\}""")

        /** «ComponentInfo{com.app/.Main}» -> «com.app/com.app.Main». */
        fun componentKey(raw: String): String? {
            val m = componentRe.find(raw) ?: return null
            val pkg = m.groupValues[1].trim()
            var cls = m.groupValues[2].trim()
            if (cls.startsWith(".")) cls = pkg + cls
            return "$pkg/$cls"
        }

        fun parse(parser: XmlPullParser): AppFilter {
            val map = LinkedHashMap<String, String>()
            val backs = ArrayList<String>()
            var mask: String? = null
            var upon: String? = null
            var scale = 1f
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    fun attr(n: String) = parser.getAttributeValue(null, n)
                    when (parser.name) {
                        "item" -> {
                            val key = attr("component")?.let(::componentKey)
                            val d = attr("drawable")
                            if (key != null && !d.isNullOrBlank() && key !in map) map[key] = d
                        }
                        "iconback" -> (0 until parser.attributeCount).forEach { i ->
                            parser.getAttributeValue(i)?.takeIf { it.isNotBlank() }?.let(backs::add)
                        }
                        "iconmask" -> mask = attr("img1") ?: attr("img")
                        "iconupon" -> upon = attr("img1") ?: attr("img")
                        "scale" -> scale = attr("factor")?.toFloatOrNull() ?: 1f
                    }
                }
                event = parser.next()
            }
            return AppFilter(map, backs, mask, upon, scale.coerceIn(0.3f, 1.2f))
        }

        fun parse(xml: String): AppFilter {
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(xml.reader())
            return parse(p)
        }
    }
}

/** Поиск наборов значков и рисование значков из выбранного набора. */
class IconPacks(private val context: Context) {
    private val pm = context.packageManager

    /** Наборы, объявленные для популярных лаунчеров (их понимает почти любой набор). */
    fun installed(): List<IconPackInfo> {
        val intents = listOf(
            Intent("org.adw.launcher.THEMES"),
            Intent("com.novalauncher.THEME"),
            Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME"),
            Intent(Intent.ACTION_MAIN).addCategory("com.teslacoilsw.launcher.THEME"),
        )
        return intents.flatMap { i -> runCatching { pm.queryIntentActivities(i, 0) }.getOrDefault(emptyList()) }
            .map { it.activityInfo.packageName }
            .distinct()
            .mapNotNull { pkg ->
                runCatching { IconPackInfo(pkg, pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()) }.getOrNull()
            }
            .sortedBy { it.label.lowercase() }
    }

    private var loaded: Pair<String, Pack>? = null

    private class Pack(val res: Resources, val pkg: String, val filter: AppFilter) {
        fun drawable(name: String): Drawable? = runCatching {
            val id = res.getIdentifier(name, "drawable", pkg).takeIf { it != 0 }
                ?: res.getIdentifier(name, "mipmap", pkg).takeIf { it != 0 } ?: return null
            @Suppress("DEPRECATION")
            res.getDrawable(id, null)
        }.getOrNull()
    }

    private fun pack(pkg: String): Pack? {
        loaded?.takeIf { it.first == pkg }?.let { return it.second }
        val res = runCatching { pm.getResourcesForApplication(pkg) }.getOrNull() ?: return null
        val filter = runCatching {
            val xmlId = res.getIdentifier("appfilter", "xml", pkg)
            if (xmlId != 0) AppFilter.parse(res.getXml(xmlId))
            else res.assets.open("appfilter.xml").use { AppFilter.parse(it.readBytes().decodeToString()) }
        }.getOrNull() ?: return null
        return Pack(res, pkg, filter).also { loaded = pkg to it }
    }

    /**
     * Значок приложения из набора. Если отдельного значка нет, но в наборе есть подложка —
     * обычный значок рисуется на ней (с маской и накладкой), чтобы все значки были в одном стиле.
     * null — набор не нашёлся: тогда показывается обычный значок.
     */
    fun icon(pkg: String, key: String, original: () -> Drawable?, sizePx: Int): Bitmap? {
        val p = pack(pkg) ?: return null
        p.filter.drawableFor(key)?.let { name -> p.drawable(name)?.let { return it.toBitmap(sizePx, sizePx) } }
        val back = p.filter.backs.firstOrNull()?.let { p.drawable(it) } ?: return null
        val orig = original() ?: return null
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val full = Rect(0, 0, sizePx, sizePx)
        back.bounds = full; back.draw(c)
        val inner = (sizePx * p.filter.scale).toInt().coerceAtLeast(1)
        val layer = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val lc = Canvas(layer)
        val off = (sizePx - inner) / 2
        lc.drawBitmap(orig.toBitmap(inner, inner), off.toFloat(), off.toFloat(), null)
        p.filter.mask?.let { p.drawable(it) }?.let { m ->
            val paint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
            lc.drawBitmap(m.toBitmap(sizePx, sizePx), 0f, 0f, paint)
        }
        c.drawBitmap(layer, 0f, 0f, null)
        p.filter.upon?.let { p.drawable(it) }?.let { u -> u.bounds = full; u.draw(c) }
        return out
    }
}
