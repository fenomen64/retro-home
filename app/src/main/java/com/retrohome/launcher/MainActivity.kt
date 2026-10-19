package com.retrohome.launcher

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.i18n.stringsFor
import com.retrohome.launcher.audio.LocalSfx
import com.retrohome.launcher.audio.MusicPlayer
import com.retrohome.launcher.audio.SoundPlayer
import com.retrohome.launcher.audio.HapticPlayer
import com.retrohome.launcher.audio.Feedback
import com.retrohome.launcher.audio.hapticLevelOf
import com.retrohome.launcher.audio.LocalHapticTarget
import com.retrohome.launcher.audio.musicTrackOf
import com.retrohome.launcher.audio.soundPackOf
import com.retrohome.launcher.ui.CoverLoader
import com.retrohome.launcher.ui.isConsoleMode
import com.retrohome.launcher.ui.IconLoader
import com.retrohome.launcher.ui.LocalCoverLoader
import com.retrohome.launcher.ui.LocalIconLoader
import com.retrohome.launcher.ui.LocalPadRegistry
import com.retrohome.launcher.ui.MainScreen
import com.retrohome.launcher.ui.Pad
import com.retrohome.launcher.ui.PadRegistry
import com.retrohome.launcher.ui.Palette
import kotlinx.coroutines.flow.MutableSharedFlow

/**
 * Главный экран (HOME). Его же наследует [AppModeActivity] — запуск значком как обычное приложение.
 */
open class MainActivity : ComponentActivity() {
    /** Открыт как приложение, а не как главный экран: всегда консоль, «назад» — выход. */
    protected open val appMode: Boolean get() = false

    companion object {
        private const val REQ_CONFIGURE_WIDGET = 7101
    }

    private val pads = PadRegistry()
    private val consumedKeys = HashSet<Int>()
    /** true — Home нажали, когда лаунчер уже был на экране. */
    private val homeEvents = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)
    private val controller get() = (application as LauncherApp).controller
    private val pickTree = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) controller.setRomsFolder(uri)
    }
    private val pickMusic = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) controller.setCustomMusic(uri)
    }
    /** Виджет, который сейчас добавляется (ждём разрешения или настройки). */
    private data class PendingWidget(val id: Int, val option: com.retrohome.launcher.data.WidgetOption, val page: Int)
    private var pendingWidget: PendingWidget? = null

    private val bindWidget = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val p = pendingWidget ?: return@registerForActivityResult
        if (r.resultCode == RESULT_OK) configureOrPlace(p) else cancelWidget(p)
    }

    /** Добавление виджета: разрешение (один раз) → настройка (если нужна) → на рабочий стол. */
    private fun addWidget(option: com.retrohome.launcher.data.WidgetOption, page: Int) {
        val w = controller.widgets
        val id = runCatching { w.allocate() }.getOrNull() ?: return toastFailed()
        val p = PendingWidget(id, option, page)
        pendingWidget = p
        if (w.bindIfAllowed(id, option.provider)) {
            configureOrPlace(p)
        } else {
            val cn = android.content.ComponentName.unflattenFromString(option.provider) ?: return cancelWidget(p)
            val intent = Intent(android.appwidget.AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, cn)
            runCatching { bindWidget.launch(intent) }.onFailure { cancelWidget(p) }
        }
    }

    private fun configureOrPlace(p: PendingWidget) {
        val info = controller.widgets.info(p.id)
        if (info != null && controller.widgets.needsConfigure(info)) {
            if (!controller.widgets.startConfigure(this, p.id, REQ_CONFIGURE_WIDGET)) cancelWidget(p)
            return
        }
        placeWidget(p)
    }

    private fun placeWidget(p: PendingWidget) {
        pendingWidget = null
        controller.addWidget(p.id, p.option.provider, p.option.spanX, p.option.spanY, p.page)
    }

    private fun cancelWidget(p: PendingWidget) {
        pendingWidget = null
        controller.widgets.delete(p.id)
    }

    private fun toastFailed() {
        android.widget.Toast.makeText(this, stringsFor(controller.store.data.value.settings.lang).widgetFailed, android.widget.Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Настройка виджета запускается системой через startActivityForResult")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIGURE_WIDGET) return
        val p = pendingWidget ?: return
        if (resultCode == RESULT_OK) placeWidget(p) else cancelWidget(p)
    }

    override fun onStart() {
        super.onStart()
        controller.widgets.startListening()
    }

    override fun onStop() {
        controller.widgets.stopListening()
        handler.removeCallbacks(restoreOrientation)
        // Пока лаунчер скрыт, его поворот закреплён таким, каким он был на экране: при возврате
        // система не спрашивает датчик (его старое значение и переворачивало обои)
        if (!appMode && controller.store.data.value.settings.mode == ModeOverride.AUTO) {
            requestedOrientation = if (lastOrientation == Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        super.onStop()
    }

    /** Настоящие виджеты на рабочем столе. */
    private val widgetRenderer = object : com.retrohome.launcher.ui.phone.WidgetRenderer {
        @androidx.compose.runtime.Composable
        override fun Widget(item: com.retrohome.launcher.data.HomeItem, modifier: androidx.compose.ui.Modifier, gestures: com.retrohome.launcher.data.WidgetGestures) {
            val view = remember(item.widgetId) { controller.widgets.view(item.widgetId) }
            if (view == null) {
                // Приложение виджета удалили или оно недоступно — заглушка, которую можно убрать долгим нажатием
                com.retrohome.launcher.ui.phone.PlaceholderWidgets.Widget(item, modifier, gestures)
                return
            }
            androidx.compose.foundation.layout.BoxWithConstraints(modifier.then(androidx.compose.ui.Modifier.padding(4.dp))) {
                val w = maxWidth.value
                val h = maxHeight.value
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { (view.parent as? android.view.ViewGroup)?.removeView(view); view },
                    modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    update = { v ->
                        v.gestures = gestures
                        controller.widgets.updateSize(v, w, h)
                    },
                )
            }
        }
    }

    private lateinit var sounds: SoundPlayer
    private lateinit var haptics: HapticPlayer
    private lateinit var music: MusicPlayer

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val restoreOrientation = Runnable { applyOrientation(controller.store.data.value.settings.mode) }

    private fun orientationFor(mode: ModeOverride) = if (appMode) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else when (mode) {
        ModeOverride.AUTO -> ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        ModeOverride.CONSOLE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        ModeOverride.PHONE -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun applyOrientation(mode: ModeOverride) {
        val o = orientationFor(mode)
        if (requestedOrientation != o) requestedOrientation = o
    }

    override fun onResume() {
        super.onResume()
        // Возвращаемся из приложения: сначала прежний поворот лаунчера, а отслеживание поворота
        // включаем, когда анимация возврата закончилась. Иначе система на миг берёт старый поворот
        // датчика и обои переворачиваются.
        handler.removeCallbacks(restoreOrientation)
        handler.postDelayed(restoreOrientation, 700)
        controller.onLauncherResumed()
        music.setResumed(true)
    }

    private var lastOrientation = Configuration.ORIENTATION_PORTRAIT

    override fun onPause() {
        lastOrientation = resources.configuration.orientation
        music.setResumed(false)
        super.onPause()
    }

    override fun onDestroy() {
        sounds.release()
        music.release()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Главный экран нельзя закрыть кнопкой «назад»; приложение — уходит в фон (как обычные игры)
        onBackPressedDispatcher.addCallback(this) { if (appMode) moveTaskToBack(true) }

        val c = controller
        sounds = SoundPlayer(this)
        haptics = HapticPlayer(this)
        val feedback = Feedback(listOf(sounds, haptics))
        music = MusicPlayer(this)
        // Тема — сразу, чтобы первый кадр уже был в нужных цветах
        c.store.data.value.settings.let { Palette.apply(it.theme, it.accent) }
        // Загрузчик значков пересоздаётся при смене набора — экраны перерисуют значки
        fun iconLoader() = object : IconLoader {
            override fun peek(key: String, sizePx: Int): ImageBitmap? = c.apps.peekIcon(key, sizePx)?.asImageBitmap()
            override fun load(key: String, sizePx: Int): ImageBitmap? = c.apps.icon(key, sizePx)?.asImageBitmap()
        }
        c.apps.iconPack = c.store.data.value.settings.iconPack

        val coverCache = object : android.util.LruCache<String, ImageBitmap>(32) {}
        val covers = object : CoverLoader {
            override fun peek(name: String): ImageBitmap? = synchronized(coverCache) { coverCache.get(name) }
            override fun load(name: String): ImageBitmap? {
                val f = c.metas.coverFile(name)
                if (!f.exists()) return null
                val bmp = android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() ?: return null
                synchronized(coverCache) { coverCache.put(name, bmp) }
                return bmp
            }
        }

        setContent {
            val data by c.store.data.collectAsStateWithLifecycle()
            val apps by c.apps.apps.collectAsStateWithLifecycle()
            val mode = data.settings.mode
            LaunchedEffect(mode) { applyOrientation(mode) }
            val st = data.settings
            val icons = remember(st.iconPack) { c.apps.iconPack = st.iconPack; iconLoader() }
            val consoleNow = appMode || isConsoleMode(st.mode, LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE)
            LaunchedEffect(st.sfxPack, st.sfxVolume) { sounds.configure(soundPackOf(st.sfxPack), st.sfxVolume) }
            LaunchedEffect(st.haptics) { haptics.level = hapticLevelOf(st.haptics) }
            LaunchedEffect(st.theme, st.accent) { Palette.apply(st.theme, st.accent) }
            LaunchedEffect(consoleNow, st.music, st.customMusic, st.musicVolume) {
                music.configure(consoleNow, musicTrackOf(st.music), st.customMusic, st.musicVolume)
            }
            // Светлая тема: тёмные значки строки состояния там, где фон — цвет темы, а не обои
            var themedSurface by remember { mutableStateOf(false) }
            val lightBars = Palette.theme.light && themedSurface
            LaunchedEffect(lightBars) {
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = lightBars
                    isAppearanceLightNavigationBars = lightBars
                }
            }
            MaterialTheme(colorScheme = if (Palette.theme.light) lightColorScheme(primary = Palette.accent) else darkColorScheme(primary = Palette.accent)) {
                CompositionLocalProvider(
                    LocalStrings provides stringsFor(data.settings.lang),
                    LocalPadRegistry provides pads,
                    LocalIconLoader provides icons,
                    LocalCoverLoader provides covers,
                    LocalSfx provides feedback,
                    LocalHapticTarget provides { haptics.target() },
                    com.retrohome.launcher.ui.phone.LocalWidgetRenderer provides widgetRenderer,
                    com.retrohome.launcher.ui.LocalAppMode provides appMode,
                    com.retrohome.launcher.ui.LocalAppExit provides { moveTaskToBack(true) },
                ) {
                    MainScreen(
                        data = data,
                        apps = apps,
                        controller = c,
                        homeEvents = homeEvents,
                        landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE,
                        version = packageManager.getPackageInfo(packageName, 0).versionName ?: "",
                        onPickRoms = { runCatching { pickTree.launch(null) } },
                        onPickMusic = { runCatching { pickMusic.launch(arrayOf("audio/*")) } },
                        onThemedSurface = { themedSurface = it },
                        onAddWidget = ::addWidget,
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) {
            homeEvents.tryEmit(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))
        }
    }

    private fun mapKey(code: Int, swap: Boolean): Pad? = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> Pad.UP
        KeyEvent.KEYCODE_DPAD_DOWN -> Pad.DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> Pad.LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> Pad.RIGHT
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> Pad.CONFIRM
        KeyEvent.KEYCODE_BUTTON_A -> if (swap) Pad.CANCEL else Pad.CONFIRM
        KeyEvent.KEYCODE_BUTTON_B -> if (swap) Pad.CONFIRM else Pad.CANCEL
        KeyEvent.KEYCODE_BUTTON_X -> Pad.X
        KeyEvent.KEYCODE_BUTTON_Y -> Pad.Y
        KeyEvent.KEYCODE_BUTTON_START -> Pad.START
        KeyEvent.KEYCODE_BUTTON_SELECT -> Pad.SELECT
        KeyEvent.KEYCODE_BUTTON_L1 -> Pad.L1
        KeyEvent.KEYCODE_BUTTON_R1 -> Pad.R1
        else -> null
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Вибрировать будем тем геймпадом, на котором нажимают кнопки
        if (event.action == KeyEvent.ACTION_DOWN && ::haptics.isInitialized) haptics.lastDeviceId = event.deviceId
        val pad = mapKey(event.keyCode, controller.store.data.value.settings.swapAB)
            ?: return super.dispatchKeyEvent(event)

        // 1) Консольный экран сам обрабатывает кнопки
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (pads.dispatch(pad)) {
                consumedKeys.add(event.keyCode)
                return true
            }
        } else if (event.action == KeyEvent.ACTION_UP && consumedKeys.remove(event.keyCode)) {
            return true
        }

        // 2) Остальные экраны (настройки, поиск, список) — обычная навигация фокусом
        return when (pad) {
            Pad.CONFIRM -> {
                val native = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER || event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
                if (native) super.dispatchKeyEvent(event)
                else super.dispatchKeyEvent(
                    KeyEvent(event.downTime, event.eventTime, event.action, KeyEvent.KEYCODE_DPAD_CENTER, event.repeatCount)
                )
            }
            Pad.CANCEL -> {
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> super.dispatchKeyEvent(event)
        }
    }
}
