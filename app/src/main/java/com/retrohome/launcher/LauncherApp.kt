package com.retrohome.launcher

import android.app.Application
import com.retrohome.launcher.data.cycle
import kotlinx.coroutines.flow.MutableStateFlow
import com.retrohome.launcher.data.PlaySession
import com.retrohome.launcher.data.PlayTime
import com.retrohome.launcher.data.UsageTime
import com.retrohome.launcher.data.TabOrder
import android.content.Context
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.retrohome.launcher.data.GameLibrary
import com.retrohome.launcher.data.MetaRepository
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.LaunchContext
import com.retrohome.launcher.data.LaunchRecipe
import com.retrohome.launcher.data.Launchers
import com.retrohome.launcher.data.isGameKey
import com.retrohome.launcher.i18n.stringsFor
import android.provider.Settings
import com.retrohome.launcher.data.AppRepository
import com.retrohome.launcher.data.ConsoleZone
import com.retrohome.launcher.data.HomeItem
import com.retrohome.launcher.data.HomeLayout
import com.retrohome.launcher.data.Lang
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.data.Recent
import com.retrohome.launcher.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class LauncherApp : Application() {
    lateinit var controller: LauncherController
        private set

    override fun onCreate() {
        super.onCreate()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val store = Store(File(filesDir, "launcher.json"), scope)
        val apps = AppRepository(this, scope)
        controller = LauncherController(this, store, apps, GameLibrary(this, scope),
            MetaRepository(this, scope, contact = { store.data.value.settings.contactEmail }), scope)
    }
}

/** Все изменения данных лаунчера: интерфейс только вызывает эти методы. */
class LauncherController(
    private val context: Context,
    val store: Store,
    val apps: AppRepository,
    val games: GameLibrary,
    val metas: MetaRepository,
    private val scope: CoroutineScope,
) {
    /** Достижения RetroAchievements для страниц игр. */
    val achievements = com.retrohome.launcher.data.AchievementsRepository(
        context, scope,
        account = {
            val st = store.data.value.settings
            if (st.raUser.isNullOrBlank() || st.raKey.isNullOrBlank()) null else st.raUser to st.raKey
        },
        readRom = ::readRom,
    )

    /** Содержимое ROM-файла из папки ROMs (для отпечатка игры); из zip — первый файл внутри. */
    private fun readRom(g: com.retrohome.launcher.data.Game): ByteArray? {
        val tree = data.settings.romsTree ?: return null
        val uri = com.retrohome.launcher.data.SafFileSource(context, Uri.parse(tree)).documentUri(g.id)
        val max = com.retrohome.launcher.data.Ra.MAX_HASH_BYTES
        return context.contentResolver.openInputStream(uri)?.use { input ->
            if (g.fileName.endsWith(".zip", ignoreCase = true)) {
                java.util.zip.ZipInputStream(input).use { z ->
                    generateSequence { z.nextEntry }.firstOrNull { !it.isDirectory } ?: return null
                    z.readNBytes(max)
                }
            } else input.readNBytes(max)
        }
    }

    /** Проверка соединения с сайтами обложек, описаний и достижений (результаты — по мере готовности). */
    suspend fun checkNetwork(onEach: (com.retrohome.launcher.data.NetworkCheck.Result) -> Unit) =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            // Результат каждого сайта показывается сразу, не дожидаясь остальных
            val st = data.settings
            val ra = if (st.raUser.isNullOrBlank() || st.raKey.isNullOrBlank()) null else st.raUser to st.raKey
            com.retrohome.launcher.data.NetworkCheck.run(com.retrohome.launcher.data.UrlTransport, metas.userAgent(), ra, onEach)
        }

    /** Названия игры для поиска на RetroAchievements. */
    private fun raTitles(g: com.retrohome.launcher.data.Game): List<String> =
        listOfNotNull(titleOverride(g), metas.meta.value[g.id]?.name, g.title).distinct()

    fun syncAchievements(force: Boolean = false) =
        achievements.sync(games.result.value.games, ::raTitles, force)

    /** Сохранить аккаунт RetroAchievements и проверить его. Возвращает имя и очки или null. */
    suspend fun setRaAccount(user: String?, key: String?): Pair<String, Int>? {
        val u = user?.trim()?.takeIf { it.isNotEmpty() }
        val k = key?.trim()?.takeIf { it.isNotEmpty() }
        store.update { d -> d.copy(settings = d.settings.copy(raUser = u, raKey = k)) }
        if (u == null || k == null) { achievements.signOut(); return null }
        val ok = kotlinx.coroutines.withContext(Dispatchers.IO) { achievements.checkAccount(u, k) }
        if (ok != null) syncAchievements(force = true)
        return ok
    }

    init {
        // Игры, ярлыки которых созданы в Lemuroid: название → номер в его библиотеке
        com.retrohome.launcher.data.Lemuroid.load(context)
    }

    init {
        // Достижения: опознать новые игры после каждого сканирования
        scope.launch {
            games.result.collect { r -> if (r.games.isNotEmpty()) achievements.sync(r.games, ::raTitles) }
        }
    }

    init {
        // После каждого сканирования догружаем обложки и описания новых игр
        scope.launch {
            games.result.collect { r ->
                if (r.time > 0) com.retrohome.launcher.data.DiagLog.add(
                    "Сканирование: папок ${r.folders.size}, игр ${r.games.size}" +
                        r.folders.joinToString("; ", " — ") { f -> "${f.name}→${f.system?.name ?: "не используется"} (${f.gameCount})" }
                )
                if (r.games.isNotEmpty() && !data.settings.autoMeta) com.retrohome.launcher.data.DiagLog.add("Автозагрузка обложек и описаний выключена в настройках")
                if (r.games.isNotEmpty() && data.settings.autoMeta) metas.fetch(r.games, ::titleOverride)
            }
        }
        // Первый запуск: всё раскладываем по экранам; дальше — новые установки
        scope.launch {
            apps.apps.collect { list ->
                if (list.isEmpty()) return@collect
                val keys = list.map { it.key }
                val dock = if (store.data.value.homeSeeded) emptyList() else apps.defaultDockKeys()
                store.update { d -> HomeLayout.syncApps(d, keys, dock) }
            }
        }
        // Значки удалённых приложений убираем со стола
        scope.launch {
            apps.removed.collect { pkg ->
                store.update { d ->
                    val onHome = HomeLayout.keysOnHome(d.home)
                    val stale = (onHome + d.favorites + d.knownApps).filter { it.startsWith("$pkg/") }.toSet()
                    if (stale.isEmpty()) d
                    else d.copy(
                        home = HomeLayout.pruneMissing(d.home, onHome - stale),
                        favorites = d.favorites - stale,
                        // при повторной установке значок снова появится на столе
                        knownApps = d.knownApps - stale,
                    )
                }
            }
        }
    }


    private val data: LauncherData get() = store.data.value

    // --- Запуск ---
    private fun recordRecent(key: String) = store.update { d ->
        val list = (listOf(Recent(key, System.currentTimeMillis())) + d.recents.filter { it.key != key }).take(50)
        d.copy(recents = list)
    }

    fun launch(key: String) {
        if (isGameKey(key)) {
            launchGame(key)
            return
        }
        if (apps.launch(key)) {
            recordRecent(key)
            // Открыли другое приложение из лаунчера — время прошлой игры больше не идёт
            settleSession()
            store.update { d -> d.copy(session = null) }
        }
    }

    private val strings get() = stringsFor(data.settings.lang)

    private fun toast(text: String) = Toast.makeText(context, text, Toast.LENGTH_LONG).show()

    // --- Игры ---
    /** Установленные пакеты; пакет «под чужим именем» (Genshin = ночной Eden) — только если это и правда эмулятор. */
    private fun installedPackages(): Set<String> = apps.apps.value
        .filter { !com.retrohome.launcher.data.SpoofedEmulators.isSpoofable(it.packageName) || it.emulator != null }
        .map { it.packageName }.toSet()

    fun runnerFor(system: GameSystem): LaunchRecipe? =
        Launchers.choose(system, installedPackages(), data.systemEmulator[system.name], autoRecipes())

    fun runnerOptions(system: GameSystem): List<LaunchRecipe> = Launchers.available(system, installedPackages(), autoRecipes())

    /**
     * Запуск для эмуляторов без готового рецепта (новые, вроде только что установленного):
     * консоль известна, а игру им можно передать обычным «открыть файл». Пересчитывается при смене приложений.
     */
    private var autoCache: Pair<List<com.retrohome.launcher.data.AppInfo>, List<LaunchRecipe>>? = null

    private fun autoRecipes(): List<LaunchRecipe> {
        val list = apps.apps.value
        autoCache?.takeIf { it.first === list }?.let { return it.second }
        val recipes = list.filter { it.emulator != null && !Launchers.knows(it.packageName) }
            .distinctBy { it.packageName }
            .mapNotNull { a ->
                val (activity, withType) = com.retrohome.launcher.data.FileOpeners.activityOf(context, a.packageName) ?: return@mapNotNull null
                val def = a.emulator!!
                Launchers.generic(a.packageName, if (def.id.startsWith(com.retrohome.launcher.data.Emulators.AUTO_PREFIX)) a.label else def.name,
                    def.systems.toSet(), activity, withType)
            }
        autoCache = list to recipes
        return recipes
    }

    /** Переключить эмулятор для системы на следующий (dir = 1) или предыдущий (dir = -1) из установленных. */
    fun cycleRunner(system: GameSystem, dir: Int = 1) {
        val options = runnerOptions(system)
        if (options.size < 2) return
        val next = options.cycle(runnerFor(system) ?: options.first(), dir)
        store.update { d -> d.copy(systemEmulator = d.systemEmulator + (system.name to next.id)) }
    }

    fun hasRomsAccess(): Boolean = games.hasAccess(data.settings.romsTree)

    fun setRomsFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        store.update { d -> d.copy(settings = d.settings.copy(romsTree = uri.toString())) }
        rescanGames()
    }

    private fun titleOverride(g: com.retrohome.launcher.data.Game): String? = data.titleOverrides[g.id]

    fun refreshMeta() = metas.fetch(games.result.value.games, ::titleOverride, retryMissing = true)

    /** Заново поискать обложку и описание одной игры. */
    fun refreshGame(key: String) {
        val g = gameByKey(key) ?: return
        metas.refetch(g, titleOverride(g))
    }

    fun gameByKey(key: String) = games.result.value.games.firstOrNull { it.key == key }

    /** Исправить название игры (null — вернуть из имени файла) и заново найти обложку и описание. */
    fun setTitleOverride(key: String, title: String?) {
        val g = gameByKey(key) ?: return
        val clean = title?.trim()?.takeIf { it.isNotEmpty() }
        store.update { d ->
            d.copy(titleOverrides = if (clean == null) d.titleOverrides - g.id else d.titleOverrides + (g.id to clean))
        }
        metas.refetch(g, clean)
        // С новым названием игра может найтись и на RetroAchievements
        achievements.forget(g.id)
        syncAchievements()
    }

    fun toggleAutoMeta() {
        store.update { d -> d.copy(settings = d.settings.copy(autoMeta = !d.settings.autoMeta)) }
        if (data.settings.autoMeta) metas.fetch(games.result.value.games, ::titleOverride)
    }

    fun rescanGames() = games.rescan(data.settings.romsTree, data.folderOverrides)

    /** При возврате в лаунчер: пересканировать, если давно не смотрели. */
    fun maybeRescan() {
        if (games.scanning.value) return
        if (System.currentTimeMillis() - games.result.value.time > 30_000) rescanGames()
    }

    fun setFolderOverride(folder: String, value: String?) {
        store.update { d ->
            d.copy(folderOverrides = if (value == null) d.folderOverrides - folder else d.folderOverrides + (folder to value))
        }
        rescanGames()
    }

    fun launchGame(key: String, discIndex: Int = 0) {
        val base = games.result.value.games.firstOrNull { it.key == key } ?: return
        // Для многодисковой игры подставляем выбранный диск
        val game = base.discs.getOrNull(discIndex)?.let { d -> base.copy(id = d.id, path = d.path) } ?: base
        val tree = data.settings.romsTree ?: return
        val recipe = runnerFor(game.system)
        if (recipe == null) {
            toast(strings.noEmulator.format(game.system.title(data.settings.lang)))
            return
        }
        val pkg = Launchers.installedPackage(recipe, installedPackages()) ?: return
        val pm = context.packageManager
        val activity = recipe.activities.firstOrNull { cls ->
            runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0) }.isSuccess
        } ?: recipe.activities.first()
        val titles = listOfNotNull(data.titleOverrides[base.id], base.title, base.fileName.substringBeforeLast('.'))
        val ctx = LaunchContext(pkg, activity, games.documentUri(tree, game), game.path, game.system, base.titleId, titles, Uri.parse(tree))
        try {
            try {
                context.startActivity(Launchers.withFolderAccess(recipe.build(ctx), ctx.tree))
            } catch (e: SecurityException) {
                // Доступ ко всей папке выдать не удалось — запускаем только с самим файлом игры
                context.startActivity(recipe.build(ctx.copy(tree = null)))
            }
            if (recipe.id == "lemuroid" && com.retrohome.launcher.data.Lemuroid.idFor(titles) == null) {
                // Lemuroid открылся на своём списке игр — подсказать, как запускать сразу
                val home = com.retrohome.launcher.data.Lemuroid.canReceiveShortcuts(context)
                toast(if (home) strings.ext.emu.lemuroidHint else strings.ext.emu.lemuroidHintNotHome)
            }
            // Vita3K открылся на своём списке: номер игры не нашёлся
            if (game.system == GameSystem.VITA && base.titleId == null) toast(strings.ext.emu.vitaNoId)
            recordRecent(key)
            // Время в игре: точно по статистике Android, если есть доступ, иначе — до возврата в лаунчер
            settleSession()
            val now = System.currentTimeMillis()
            val precise = UsageTime.hasAccess(context)
            store.update { d ->
                val stat = d.playStats[key] ?: com.retrohome.launcher.data.PlayStat()
                val counted = if (precise) d.playStats + (key to stat.copy(sessions = stat.sessions + 1, last = now)) else d.playStats
                d.copy(session = PlaySession(key, now, pkg = if (precise) pkg else null), playStats = counted)
            }
        } catch (e: Exception) {
            toast(strings.launchFailed.format(recipe.name))
        }
    }

    /** Есть ли доступ к статистике использования (точный учёт времени в играх). */
    val usageAccess = MutableStateFlow(UsageTime.hasAccess(context))

    /** Лаунчер снова на экране: посчитать время запущенной игры. */
    fun onLauncherResumed() {
        usageAccess.value = UsageTime.hasAccess(context)
        // Ярлыки Lemuroid, закреплённые на этом главном экране
        val installed = installedPackages()
        scope.launch(Dispatchers.IO) { com.retrohome.launcher.data.Lemuroid.syncPinned(context, installed) }
        // Вернулись из игры — могли открыться новые достижения
        achievements.refreshProgress()
        settleSession()
        maybeRescan()
    }

    /**
     * Обновить время текущей игры. Точная сессия продолжается (в игру можно вернуться
     * через список открытых приложений), обычная — закрывается.
     */
    private fun settleSession() {
        val ses = data.session ?: return
        val now = System.currentTimeMillis()
        val pkg = ses.pkg
        if (pkg != null && usageAccess.value) {
            val events = UsageTime.events(context, ses.counted, now)
            val r = UsageTime.foreground(events, pkg, ses.counted, now, ses.lastActive)
            store.update { d -> if (d.session?.start != ses.start) d else PlayTime.addUsage(d, r, now) }
        } else if (pkg == null) {
            store.update { d -> PlayTime.finish(d, now) }
        } else {
            // Доступ к статистике отозвали — дальше этой игре время не идёт
            store.update { d -> d.copy(session = null) }
        }
    }

    /** Открыть системный экран «Доступ к истории использования». */
    fun openUsageAccess() {
        val withPkg = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val plain = Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(withPkg) }.recoverCatching { context.startActivity(plain) }
    }

    // --- Оформление ---
    fun cycleTheme(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(theme = com.retrohome.launcher.ui.Themes.all.map { it.id }.cycle(d.settings.theme, dir)))
    }

    fun cycleAccent(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(accent = com.retrohome.launcher.ui.Themes.accents.map { it.first }.cycle(d.settings.accent, dir)))
    }

    fun toggleTabColors() = store.update { d -> d.copy(settings = d.settings.copy(tabColors = !d.settings.tabColors)) }

    fun cycleHaptics(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(haptics = listOf("off", "light", "medium", "strong").cycle(d.settings.haptics, dir)))
    }

    /** Сдвинуть вкладку консоли; ids — текущий порядок всех вкладок. */
    fun moveTab(ids: List<String>, id: String, dir: Int) = store.update { d ->
        d.copy(settings = d.settings.copy(tabOrder = TabOrder.move(ids, id, dir)))
    }

    fun toggleTabHidden(id: String) = store.update { d ->
        if (id == "home") return@update d
        val h = d.settings.hiddenTabs
        d.copy(settings = d.settings.copy(hiddenTabs = if (id in h) h - id else h + id))
    }

    // --- Обновление лаунчера ---
    val updater = com.retrohome.launcher.data.Updater(context, scope)

    /** При возвращении на главный экран: тихая проверка (если включена) и продолжение установки после разрешения. */
    fun onResumeUpdates() {
        updater.onResume()
        if (!store.data.value.settings.updateCheck) return
        updater.autoCheck { v ->
            val s = if (store.data.value.settings.lang == com.retrohome.launcher.data.Lang.RU) com.retrohome.launcher.i18n.RU else com.retrohome.launcher.i18n.EN
            android.widget.Toast.makeText(context, s.ext.upd.toast.format(v), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    fun toggleUpdateCheck() = store.update { d -> d.copy(settings = d.settings.copy(updateCheck = !d.settings.updateCheck)) }

    fun cycleCarouselKeys(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(carouselKeys = listOf("dpad", "triggers", "both").cycle(d.settings.carouselKeys, dir)))
    }

    fun cycleSortGames(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(sortGames = listOf("name", "recent", "playtime").cycle(d.settings.sortGames, dir)))
    }

    fun toggleGamePage() = store.update { d -> d.copy(settings = d.settings.copy(gamePage = !d.settings.gamePage)) }

    fun appInfo(key: String) = apps.openAppInfo(key)
    fun uninstall(key: String) = apps.uninstall(key)

    // --- Консоль ---
    fun toggleFavorite(key: String) = store.update { d ->
        d.copy(favorites = if (key in d.favorites) d.favorites - key else d.favorites + key)
    }

    fun setZone(key: String, zone: ConsoleZone) = store.update { d ->
        d.copy(zoneOverrides = d.zoneOverrides + (key to zone))
    }

    // --- Настройки ---
    fun toggleLang() = store.update { d ->
        d.copy(settings = d.settings.copy(lang = if (d.settings.lang == Lang.RU) Lang.EN else Lang.RU))
    }

    fun setMode(mode: ModeOverride) = store.update { d -> d.copy(settings = d.settings.copy(mode = mode)) }

    fun cycleMode(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(mode = ModeOverride.entries.cycle(d.settings.mode, dir)))
    }

    fun sortHomeAlphabetically() = store.update { d ->
        val labels = apps.apps.value.associate { it.key to it.label }
        val (items, pages) = HomeLayout.sortAlphabetically(d.home, d.settings.columns, d.settings.rows) { labels[it] ?: it }
        d.copy(home = items, pageCount = pages)
    }

    fun toggleAutoAdd() = store.update { d -> d.copy(settings = d.settings.copy(autoAddNew = !d.settings.autoAddNew)) }

    // --- Звук ---
    fun cycleSfx(dir: Int = 1) = store.update { d ->
        val order = listOf("soft", "retro", "crystal", "click", "off")
        d.copy(settings = d.settings.copy(sfxPack = order.cycle(d.settings.sfxPack, dir)))
    }

    fun cycleMusic(dir: Int = 1) = store.update { d ->
        val order = buildList {
            addAll(listOf("ambient", "chiptune", "lofi"))
            if (d.settings.customMusic != null) add("custom")
            add("off")
        }
        d.copy(settings = d.settings.copy(music = order.cycle(d.settings.music, dir)))
    }

    private val volumes = (0..100 step 10).toList()
    private fun stepVolume(v: Int, dir: Int) = volumes.cycle(v.coerceIn(0, 100) / 10 * 10, dir)
    fun cycleSfxVolume(dir: Int = 1) = store.update { d -> d.copy(settings = d.settings.copy(sfxVolume = stepVolume(d.settings.sfxVolume, dir))) }
    fun cycleMusicVolume(dir: Int = 1) = store.update { d -> d.copy(settings = d.settings.copy(musicVolume = stepVolume(d.settings.musicVolume, dir))) }

    fun setCustomMusic(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        store.update { d -> d.copy(settings = d.settings.copy(customMusic = uri.toString(), music = "custom")) }
    }

    fun toggleSwap() = store.update { d -> d.copy(settings = d.settings.copy(swapAB = !d.settings.swapAB)) }

    fun cycleColumns(dir: Int = 1) = regrid { c, r -> (4..6).toList().cycle(c, dir) to r }
    fun cycleRows(dir: Int = 1) = regrid { c, r -> c to (5..7).toList().cycle(r, dir) }

    private fun regrid(f: (Int, Int) -> Pair<Int, Int>) = store.update { d ->
        val (c, r) = f(d.settings.columns, d.settings.rows)
        val (items, pages) = HomeLayout.reflow(d.home, d.pageCount, c, r)
        d.copy(settings = d.settings.copy(columns = c, rows = r), home = items, pageCount = pages)
    }

    // --- Рабочий стол ---
    fun moveItem(id: Long, page: Int, x: Int, y: Int, folderName: String) = store.update { d ->
        if (d.home.any { it.id == id && it.isWidget }) {
            val (items, pages) = HomeLayout.moveWidget(d.home, d.pageCount, id, page, x, y, d.settings.columns, d.settings.rows)
            d.copy(home = items, pageCount = pages)
        } else {
            d.copy(home = HomeLayout.move(d.home, id, page, x, y, folderName, d.settings.columns, d.settings.rows))
        }
    }

    fun removeItem(id: Long) {
        // У виджета освобождаем и его номер в системе
        data.home.firstOrNull { it.id == id && it.isWidget }?.let { widgets.delete(it.widgetId) }
        store.update { d -> d.copy(home = HomeLayout.remove(d.home, id)) }
    }

    // --- Виджеты ---
    val widgets = com.retrohome.launcher.data.WidgetHostManager(context)

    fun addWidget(widgetId: Int, provider: String, spanX: Int, spanY: Int, page: Int) = store.update { d ->
        val (items, pages) = HomeLayout.addWidget(d.home, d.pageCount, widgetId, provider, spanX, spanY, page, d.settings.columns, d.settings.rows)
        d.copy(home = items, pageCount = pages)
    }

    fun resizeWidget(id: Long, w: Int, h: Int) = store.update { d ->
        d.copy(home = HomeLayout.resizeWidget(d.home, id, w, h, d.settings.columns, d.settings.rows))
    }

    // --- Жесты и значки ---
    /** Почта для связи в запросах к Википедии (пустая — убрать). */
    fun setContactEmail(email: String?) {
        val clean = email?.trim()?.takeIf { it.contains('@') }
        store.update { d -> d.copy(settings = d.settings.copy(contactEmail = clean)) }
        // С почтой лимит выше — сразу догрузить то, что ещё не найдено
        if (clean != null) metas.fetch(games.result.value.games, ::titleOverride)
    }

    fun toggleGestures() = store.update { d -> d.copy(settings = d.settings.copy(gestures = !d.settings.gestures)) }

    /** Наборы значков: null (обычные значки) и установленные наборы по кругу. */
    fun cycleIconPack(dir: Int = 1) = store.update { d ->
        val options = listOf<String?>(null) + apps.iconPacks.installed().map { it.packageName }
        d.copy(settings = d.settings.copy(iconPack = options.cycle(d.settings.iconPack, dir)))
    }

    fun setIconShape(id: String) = store.update { d -> d.copy(settings = d.settings.copy(iconShape = id)) }

    /** Масштаб интерфейса консоли: 0 — авто, иначе проценты. */
    fun cycleUiScale(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(uiScale = com.retrohome.launcher.ui.UiScale.options.cycle(d.settings.uiScale, dir)))
    }

    /** Форма значков по кругу: как в системе, круг, капля… */
    fun cycleIconShape(dir: Int = 1) = store.update { d ->
        d.copy(settings = d.settings.copy(iconShape = com.retrohome.launcher.data.IconShapes.ids.cycle(d.settings.iconShape, dir)))
    }

    fun iconPackName(pkg: String?): String? = pkg?.let { p -> apps.iconPacks.installed().firstOrNull { it.packageName == p }?.label ?: p }

    /** Свайп вниз по рабочему столу: открыть шторку уведомлений (как в обычных лаунчерах). */
    @android.annotation.SuppressLint("WrongConstant")
    fun openNotifications() {
        runCatching {
            val sbm = context.getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        }
    }

    fun addToHome(key: String, page: Int) = store.update { d ->
        val (items, pages) = HomeLayout.addApp(d.home, d.pageCount, key, page, d.settings.columns, d.settings.rows)
        d.copy(home = items, pageCount = pages)
    }

    fun hideInDrawer(key: String) = store.update { d -> d.copy(hiddenInDrawer = d.hiddenInDrawer + key) }
    fun unhideInDrawer(key: String) = store.update { d -> d.copy(hiddenInDrawer = d.hiddenInDrawer - key) }

    fun renameFolder(id: Long, name: String) = store.update { d ->
        d.copy(home = d.home.map { if (it.id == id) it.copy(folderName = name.trim()) else it })
    }

    fun takeOutOfFolder(folderId: Long, key: String) = store.update { d ->
        val (items, pages) = HomeLayout.takeOutOfFolder(d.home, d.pageCount, folderId, key, d.settings.columns, d.settings.rows)
        d.copy(home = items, pageCount = pages)
    }

    fun addPage() = store.update { d -> d.copy(pageCount = d.pageCount + 1) }

    fun deletePage(page: Int) = store.update { d ->
        val (items, pages) = HomeLayout.deletePage(d.home, d.pageCount, page)
        d.copy(home = items, pageCount = pages)
    }

    // --- Диагностика ---

    /**
     * Отчёт о том, почему у игр нет обложек или описаний: устройство, настройки (без почты и ключей),
     * найденные папки, итоги по консолям, примеры ненайденного и журнал последней загрузки.
     */
    fun diagnostics(): String {
        val d = data
        val st = d.settings
        val scan = games.result.value
        val meta = metas.meta.value
        val now = System.currentTimeMillis()
        val df = java.text.SimpleDateFormat("dd.MM HH:mm", java.util.Locale.US)
        val sb = StringBuilder()
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        sb.appendLine("Retro Home $version · Android ${android.os.Build.VERSION.RELEASE} (SDK ${android.os.Build.VERSION.SDK_INT}) · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        val cm = context.getSystemService(android.net.ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val net = when {
            caps == null -> "нет"
            caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> "есть (VPN)"
            else -> "есть"
        }
        sb.appendLine("Интернет: $net · Язык: ${st.lang} · Автозагрузка: ${if (st.autoMeta) "вкл" else "ВЫКЛ"} · Почта для Википедии: ${if (st.contactEmail.isNullOrBlank()) "нет" else "указана"}")
        sb.appendLine("Папка ROMs: ${if (st.romsTree == null) "НЕ ВЫБРАНА" else if (hasRomsAccess()) "выбрана, доступ есть" else "выбрана, НЕТ ДОСТУПА"} · сканирование: ${if (scan.time > 0) df.format(java.util.Date(scan.time)) else "не было"}")
        sb.appendLine("Загрузка сейчас: ${metas.progress.value?.let { "идёт ${it.first}/${it.second}" } ?: if (metas.running) "идёт" else "не идёт"}${metas.problem.value?.let { " · последняя проблема: $it" }.orEmpty()}")
        sb.appendLine()
        sb.appendLine("Папки (${scan.folders.size}):")
        scan.folders.forEach { f ->
            sb.appendLine("  ${f.name} → ${f.system?.name ?: if (f.detected == null) "не распознана" else "не используется"}: игр ${f.gameCount}")
        }
        sb.appendLine()
        sb.appendLine("Игры по консолям (всего ${scan.games.size}):")
        scan.games.groupBy { it.system }.toSortedMap(compareBy { it.name }).forEach { (sys, list) ->
            val ms = list.map { meta[it.id] }
            sb.appendLine(
                "  ${sys.name}: ${list.size} · обложки ${ms.count { it?.cover != null }} · описания ${ms.count { it?.descEn != null || it?.descRu != null }}" +
                    " · не искали ${ms.count { it == null }} · ждут повтора ${ms.count { it != null && it.retryAt > now }}"
            )
        }
        val idx = metas.indexInfo()
        if (idx.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Списки обложек: " + idx.joinToString(", ") { (sys, n, t) -> "$sys $n (${df.format(java.util.Date(t))})" })
        }
        val missing = scan.games.filter { meta[it.id]?.cover == null }.take(10)
        if (missing.isNotEmpty()) {
            sb.appendLine()
            sb.appendLine("Примеры игр без обложки:")
            missing.forEach { g ->
                val m = meta[g.id]
                val state = when {
                    m == null -> "ещё не искали"
                    m.retryAt > now -> "сбой, повтор через ${(m.retryAt - now) / 60_000 + 1} мин"
                    m.coverTried -> "искали, не нашли"
                    else -> "не искали"
                }
                val desc = when {
                    m?.descEn != null || m?.descRu != null -> "описание есть"
                    m?.descTried == true -> "описание не найдено"
                    else -> "описание не искали"
                }
                sb.appendLine("  [${g.system.name}] «${g.fileName}» → «${titleOverride(g) ?: g.title}»: $state, $desc")
            }
        }
        val log = com.retrohome.launcher.data.DiagLog.snapshot()
        sb.appendLine()
        sb.appendLine("Журнал (${log.size}):")
        if (log.isEmpty()) sb.appendLine("  пусто — с запуска лаунчера загрузка не начиналась")
        log.takeLast(150).forEach { sb.appendLine("  $it") }
        return sb.toString()
    }

    /** Отправить текст через «Поделиться» (Telegram, почта и т. д.). */
    fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text).putExtra(Intent.EXTRA_SUBJECT, title)
        runCatching { context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    fun copyText(text: String) {
        val cb = context.getSystemService(android.content.ClipboardManager::class.java) ?: return
        cb.setPrimaryClip(android.content.ClipData.newPlainText("Retro Home", text))
    }

    // --- Системные экраны ---
    private fun open(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            try {
                context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {
            }
        }
    }

    fun openWifi() = open(Intent(Settings.ACTION_WIFI_SETTINGS))
    fun openBluetooth() = open(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
    fun openAndroidSettings() = open(Intent(Settings.ACTION_SETTINGS))
    fun openDefaultHome() = open(Intent(Settings.ACTION_HOME_SETTINGS))
    fun openWallpaper(title: String) = open(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), title))
}
