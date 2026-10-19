package com.retrohome.launcher.data

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.SizeF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.core.graphics.drawable.toBitmap
import kotlin.math.hypot

/** Виджет, который можно добавить: поставщик, название, приложение и размер по умолчанию в клетках. */
data class WidgetOption(
    val provider: String,
    val label: String,
    val appLabel: String,
    val appPackage: String,
    val spanX: Int,
    val spanY: Int,
    val configurable: Boolean = false,
)

/** Касания по виджету: долгое нажатие начинает перетаскивание (координаты — в окне). */
interface WidgetGestures {
    fun onLongPress(x: Float, y: Float)
    fun onDrag(x: Float, y: Float)
    fun onRelease()
}

/**
 * Виджет на рабочем столе. Обычные касания уходят самому виджету (кнопки плеера, список и т.д.),
 * а долгое нажатие перехватывается: можно перетащить виджет или открыть его меню.
 */
class LauncherWidgetView(context: Context) : AppWidgetHostView(context) {
    var gestures: WidgetGestures? = null
    private var downX = 0f
    private var downY = 0f
    private var longPressed = false
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    private val longPress = Runnable {
        longPressed = true
        parent?.requestDisallowInterceptTouchEvent(true)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val (x, y) = inWindow(downX, downY)
        gestures?.onLongPress(x, y)
    }

    private fun inWindow(x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        getLocationInWindow(loc)
        return (loc[0] + x) to (loc[1] + y)
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; longPressed = false
                removeCallbacks(longPress)
                postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (!longPressed && hypot(ev.x - downX, ev.y - downY) > slop) removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> removeCallbacks(longPress)
        }
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) {
            // После долгого нажатия весь жест — наш
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                longPressed = false
                gestures?.onRelease()
            }
            return true
        }
        track(ev)
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (longPressed) {
            when (ev.actionMasked) {
                MotionEvent.ACTION_MOVE -> { val (x, y) = inWindow(ev.x, ev.y); gestures?.onDrag(x, y) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { longPressed = false; gestures?.onRelease() }
            }
            return true
        }
        track(ev)
        // Берём весь жест, даже если виджет сам на касание не реагирует, — иначе не поймать долгое нажатие
        super.onTouchEvent(ev)
        return true
    }
}

/** Хозяин виджетов: выдаёт номера, создаёт окна виджетов и помнит их между перерисовками. */
class WidgetHostManager(private val context: Context) {
    private val manager: AppWidgetManager? = runCatching { AppWidgetManager.getInstance(context) }.getOrNull()
    private val host: AppWidgetHost = object : AppWidgetHost(context, HOST_ID) {
        override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
            LauncherWidgetView(context)
    }
    private val views = HashMap<Int, LauncherWidgetView>()

    fun startListening() = runCatching { host.startListening() }
    fun stopListening() = runCatching { host.stopListening() }

    fun info(id: Int): AppWidgetProviderInfo? = runCatching { manager?.getAppWidgetInfo(id) }.getOrNull()

    /** Все виджеты установленных приложений. */
    fun options(): List<WidgetOption> {
        val m = manager ?: return emptyList()
        val pm = context.packageManager
        val list = runCatching { m.getInstalledProvidersForProfile(Process.myUserHandle()) }.getOrDefault(emptyList())
        return list.mapNotNull { info ->
            runCatching {
                val pkg = info.provider.packageName
                WidgetOption(
                    provider = info.provider.flattenToString(),
                    label = info.loadLabel(pm),
                    appLabel = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(),
                    appPackage = pkg,
                    spanX = spanX(info),
                    spanY = spanY(info),
                    configurable = needsConfigure(info),
                )
            }.getOrNull()
        }.sortedWith(compareBy({ it.appLabel.lowercase() }, { it.label.lowercase() }))
    }

    private fun cells(px: Int): Int {
        val dp = px / context.resources.displayMetrics.density
        return ((dp + 30) / 70).toInt().coerceIn(1, 4)
    }

    private fun spanX(info: AppWidgetProviderInfo): Int =
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellWidth > 0) info.targetCellWidth.coerceIn(1, 4) else cells(info.minWidth)

    private fun spanY(info: AppWidgetProviderInfo): Int =
        if (Build.VERSION.SDK_INT >= 31 && info.targetCellHeight > 0) info.targetCellHeight.coerceIn(1, 4) else cells(info.minHeight)

    /** Нужна ли настройка перед добавлением (у некоторых она необязательна). */
    fun needsConfigure(info: AppWidgetProviderInfo): Boolean {
        if (info.configure == null) return false
        if (Build.VERSION.SDK_INT >= 31) {
            val optional = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_CONFIGURATION_OPTIONAL != 0
            val reconfigurable = info.widgetFeatures and AppWidgetProviderInfo.WIDGET_FEATURE_RECONFIGURABLE != 0
            if (optional && reconfigurable) return false
        }
        return true
    }

    fun preview(option: WidgetOption, sizePx: Int): Bitmap? = runCatching {
        val info = providerInfo(option.provider) ?: return null
        val d = info.loadPreviewImage(context, context.resources.displayMetrics.densityDpi)
            ?: info.loadIcon(context, context.resources.displayMetrics.densityDpi) ?: return null
        val w = d.intrinsicWidth.takeIf { it > 0 } ?: sizePx
        val h = d.intrinsicHeight.takeIf { it > 0 } ?: sizePx
        val scale = minOf(1f, sizePx.toFloat() / maxOf(w, h))
        d.toBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1))
    }.getOrNull()

    fun providerInfo(provider: String): AppWidgetProviderInfo? {
        val cn = ComponentName.unflattenFromString(provider) ?: return null
        return runCatching { manager?.getInstalledProvidersForProfile(Process.myUserHandle())?.firstOrNull { it.provider == cn } }.getOrNull()
    }

    fun allocate(): Int = host.allocateAppWidgetId()

    /** Привязать виджет без вопроса (можно, если пользователь уже разрешил лаунчеру виджеты). */
    fun bindIfAllowed(id: Int, provider: String): Boolean {
        val cn = ComponentName.unflattenFromString(provider) ?: return false
        return runCatching { manager?.bindAppWidgetIdIfAllowed(id, cn) == true }.getOrDefault(false)
    }

    fun delete(id: Int) {
        views.remove(id)
        runCatching { host.deleteAppWidgetId(id) }
    }

    /** Окно виджета (одно на номер, чтобы не пересоздавать при перерисовке). */
    fun view(id: Int): LauncherWidgetView? {
        views[id]?.let { return it }
        val info = info(id) ?: return null
        val v = runCatching { host.createView(context, id, info) as LauncherWidgetView }.getOrNull() ?: return null
        views[id] = v
        return v
    }

    /** Сообщить виджету его размер в dp, чтобы он подобрал раскладку. */
    fun updateSize(view: AppWidgetHostView, wDp: Float, hDp: Float) = runCatching {
        if (Build.VERSION.SDK_INT >= 31) {
            view.updateAppWidgetSize(Bundle(), listOf(SizeF(wDp, hDp)))
        } else {
            @Suppress("DEPRECATION")
            view.updateAppWidgetSize(null, wDp.toInt(), hDp.toInt(), wDp.toInt(), hDp.toInt())
        }
    }

    fun startConfigure(activity: android.app.Activity, id: Int, requestCode: Int): Boolean = runCatching {
        host.startAppWidgetConfigureActivityForResult(activity, id, 0, requestCode, null)
        true
    }.getOrDefault(false)

    companion object {
        const val HOST_ID = 4821
    }
}
