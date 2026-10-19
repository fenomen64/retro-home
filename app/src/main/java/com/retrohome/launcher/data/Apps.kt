package com.retrohome.launcher.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Process
import android.os.UserHandle
import android.util.LruCache
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Установленное приложение с иконкой в меню. key = "пакет/активити". */
data class AppInfo(
    val key: String,
    val packageName: String,
    val label: String,
    /** Android помечает приложение как игру. */
    val isGame: Boolean,
    val emulator: EmulatorDef?,
)

fun appKey(c: ComponentName) = "${c.packageName}/${c.className}"

fun keyToComponent(key: String): ComponentName? {
    val i = key.indexOf('/')
    if (i <= 0) return null
    return ComponentName(key.substring(0, i), key.substring(i + 1))
}

/** Зона консольного режима с учётом ручных назначений. */
fun AppInfo.zone(overrides: Map<String, ConsoleZone>): ConsoleZone =
    overrides[key] ?: when {
        emulator != null -> ConsoleZone.EMULATOR
        isGame -> ConsoleZone.GAME
        else -> ConsoleZone.NONE
    }

/** Список приложений с автообновлением при установке и удалении. */
class AppRepository(private val context: Context, private val scope: CoroutineScope) {
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val user: UserHandle = Process.myUserHandle()
    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps

    private val iconCache = object : LruCache<String, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            refresh(packageName)
            notifyRemoved(packageName)
        }
        override fun onPackageAdded(packageName: String, user: UserHandle) = refresh(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = refresh(packageName)
        override fun onPackagesAvailable(p: Array<out String>, user: UserHandle, r: Boolean) = refresh(null)
        override fun onPackagesUnavailable(p: Array<out String>, user: UserHandle, r: Boolean) = refresh(null)
    }

    init {
        launcherApps.registerCallback(callback)
        refresh(null)
    }

    fun refresh(changedPackage: String?) {
        if (changedPackage != null) {
            synchronized(iconCache) {
                iconCache.snapshot().keys.filter { it.startsWith("$changedPackage/") }.forEach { iconCache.remove(it) }
            }
        }
        scope.launch(Dispatchers.IO) {
            val own = context.packageName
            // Кто умеет открывать файлы игр — так опознаются эмуляторы, которых нет в списке известных
            val openers = FileOpeners.packages(context)
            val list = launcherApps.getActivityList(null, user)
                .filter { it.componentName.packageName != own }
                .map { info ->
                    val ai = info.applicationInfo
                    @Suppress("DEPRECATION")
                    val isGame = ai.category == ApplicationInfo.CATEGORY_GAME ||
                        (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
                    val label = info.label.toString()
                    AppInfo(
                        key = appKey(info.componentName),
                        packageName = info.componentName.packageName,
                        label = label,
                        isGame = isGame,
                        emulator = Emulators.match(info.componentName.packageName, label)
                            ?: Emulators.guess(info.componentName.packageName, label, info.componentName.packageName in openers),
                    )
                }
                .sortedBy { it.label.lowercase() }
            _apps.value = list
        }
    }

    private val _removed = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Пакеты, которые удалили с устройства (не временно недоступные на карте памяти). */
    val removed: kotlinx.coroutines.flow.SharedFlow<String> = _removed

    internal fun notifyRemoved(pkg: String) { _removed.tryEmit(pkg) }

    val iconPacks = IconPacks(context)

    /** Выбранный набор значков (null — обычные значки приложений). */
    @Volatile var iconPack: String? = null
        set(value) {
            if (field == value) return
            field = value
            synchronized(iconCache) { iconCache.evictAll() }
        }

    fun peekIcon(key: String, sizePx: Int): Bitmap? = synchronized(iconCache) { iconCache.get("$key@$sizePx") }

    /** Иконка нужного размера в пикселях; вызывать не на главном потоке. */
    fun icon(key: String, sizePx: Int): Bitmap? {
        val cacheKey = "$key@$sizePx"
        synchronized(iconCache) { iconCache.get(cacheKey) }?.let { return it }
        val component = keyToComponent(key) ?: return null
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(component)
        val info = launcherApps.resolveActivity(intent, user) ?: return null
        val dpi = context.resources.displayMetrics.densityDpi
        val pack = iconPack
        val themed = pack?.let { runCatching { iconPacks.icon(it, key, { info.getIcon(dpi) }, sizePx) }.getOrNull() }
        val bmp = themed ?: (info.getIcon(dpi) ?: return null).toBitmap(sizePx, sizePx)
        synchronized(iconCache) { iconCache.put(cacheKey, bmp) }
        return bmp
    }

    fun launch(key: String, bounds: Rect? = null): Boolean {
        val component = keyToComponent(key) ?: return false
        return try {
            launcherApps.startMainActivity(component, user, bounds, null)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun openAppInfo(key: String) {
        val component = keyToComponent(key) ?: return
        try {
            launcherApps.startAppDetailsActivity(component, user, null, null)
        } catch (_: Exception) {
        }
    }

    fun uninstall(key: String) {
        val component = keyToComponent(key) ?: return
        val intent = Intent(Intent.ACTION_DELETE, android.net.Uri.parse("package:${component.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /** Подобрать стартовый набор для дока: браузер, камера, галерея, магазин, настройки. */
    fun defaultDockKeys(): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com")),
            Intent("android.media.action.STILL_IMAGE_CAMERA"),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_GALLERY),
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MARKET),
            Intent(android.provider.Settings.ACTION_SETTINGS),
        )
        val all = _apps.value
        return intents.mapNotNull { intent ->
            val pkg = try {
                pm.resolveActivity(intent, 0)?.activityInfo?.packageName
            } catch (_: Exception) {
                null
            }
            if (pkg == null || pkg == "android") null else all.firstOrNull { it.packageName == pkg }?.key
        }.distinct()
    }
}

/**
 * Какие приложения открывают файлы по ссылке content:// («Открыть с помощью…»).
 * Эмуляторы объявляют это по-разному: с типом файла или только по адресу — проверяются оба варианта.
 */
object FileOpeners {
    private val probe = android.net.Uri.parse("content://com.retrohome.probe/roms/game.bin")

    private fun intents(pkg: String? = null): List<Pair<Intent, Boolean>> = listOf(
        Intent(Intent.ACTION_VIEW).setData(probe) to false,
        Intent(Intent.ACTION_VIEW).setDataAndType(probe, "application/octet-stream") to true,
    ).onEach { (i, _) -> if (pkg != null) i.setPackage(pkg) }

    /** Все пакеты, которые умеют открывать файлы. */
    fun packages(context: android.content.Context): Set<String> = runCatching {
        val pm = context.packageManager
        intents().flatMap { (i, _) -> pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName } }.toSet()
    }.getOrDefault(emptySet())

    /** Активити пакета, которая открывает файлы, и как она это объявила (с типом файла или без). */
    fun activityOf(context: android.content.Context, pkg: String): Pair<String, Boolean>? = runCatching {
        val pm = context.packageManager
        intents(pkg).firstNotNullOfOrNull { (i, withType) ->
            pm.queryIntentActivities(i, 0).firstOrNull()?.activityInfo?.name?.let { it to withType }
        }
    }.getOrNull()
}
