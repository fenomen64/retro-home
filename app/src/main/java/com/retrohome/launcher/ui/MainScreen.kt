package com.retrohome.launcher.ui

import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.runtime.produceState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.retrohome.launcher.LauncherController
import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.ConsoleZone
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.data.zone
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.ui.console.ConsoleActions
import com.retrohome.launcher.ui.console.ConsoleHome
import com.retrohome.launcher.ui.console.ConsoleState
import com.retrohome.launcher.ui.console.buildConsoleTabs
import com.retrohome.launcher.ui.phone.PhoneActions
import com.retrohome.launcher.ui.phone.PhoneHome
import com.retrohome.launcher.ui.phone.PhoneState
import kotlinx.coroutines.flow.Flow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.ui.console.ACTION_PICK_ROMS
import com.retrohome.launcher.ui.console.GamesInfo
import com.retrohome.launcher.ui.console.AudioState
import com.retrohome.launcher.audio.LocalSfx
import com.retrohome.launcher.audio.Sfx

enum class Screen { SETTINGS, CONSOLE_APPS, HIDDEN_APPS, SEARCH, FOLDERS, SYSTEM_PICKER, EMULATORS, TITLE_EDIT, CONSOLE_TABS, WIDGETS, CONTACT, RA_ACCOUNT }

/** Выбор режима: по повороту или принудительно из настроек. */
fun isConsoleMode(mode: ModeOverride, landscape: Boolean) = when (mode) {
    ModeOverride.CONSOLE -> true
    ModeOverride.PHONE -> false
    ModeOverride.AUTO -> landscape
}

@Composable
fun MainScreen(
    data: LauncherData,
    apps: List<AppInfo>,
    controller: LauncherController,
    homeEvents: Flow<Boolean>,
    landscape: Boolean,
    version: String,
    onPickRoms: () -> Unit = {},
    onPickMusic: () -> Unit = {},
    /** Сейчас на экране фон темы (а не обои) — для цвета значков строки состояния. */
    onThemedSurface: (Boolean) -> Unit = {},
    /** Добавить выбранный виджет на экран page (привязка и настройка — в Activity). */
    onAddWidget: (com.retrohome.launcher.data.WidgetOption, Int) -> Unit = { _, _ -> },
) {
    val s = LocalStrings.current
    val console = isConsoleMode(data.settings.mode, landscape)
    val stack = remember { mutableStateListOf<Screen>() }
    val consoleState = remember { ConsoleState() }
    val phoneState = remember { PhoneState() }
    LaunchedEffect(console, stack.size, phoneState.drawerOpen) {
        onThemedSurface(console || stack.isNotEmpty() || phoneState.drawerOpen)
    }
    val appsByKey = remember(apps) { apps.associateBy { it.key } }
    val scan by controller.games.result.collectAsState()
    val scanning by controller.games.scanning.collectAsState()
    val hasFolder = remember(data.settings.romsTree, scan) { controller.hasRomsAccess() }
    var pickerFolder by remember { mutableStateOf<String?>(null) }
    var editingKey by remember { mutableStateOf<String?>(null) }
    // Название эмулятора для каждой системы пересчитывается при смене приложений или выбора
    val runners = remember(apps, data.systemEmulator) {
        GameSystem.entries.associateWith { controller.runnerFor(it)?.name }
    }
    val meta by controller.metas.meta.collectAsState()
    val usageAccess by controller.usageAccess.collectAsState()
    val metaProgress by controller.metas.progress.collectAsState()
    val raState by controller.achievements.state.collectAsState()
    // Сводки достижений пересчитываются только при изменении прогресса или сопоставлений, не при каждой загрузке списка
    val raSummaries = remember(scan, raState.matches, raState.totals, raState.progress, raState.details) {
        scan.games.mapNotNull { g -> raState.summary(g.id)?.let { g.id to it } }.toMap()
    }
    val gamesInfo = remember(scan, hasFolder, runners, meta, data.titleOverrides, data.playStats, data.settings.sortGames, raSummaries) {
        GamesInfo(
            if (hasFolder) scan.games else emptyList(), hasFolder, meta, data.titleOverrides,
            data.playStats, data.settings.sortGames, raSummaries,
        ) { runners[it] }
    }

    fun push(sc: Screen) {
        consoleState.overlay = null
        phoneState.sheet = null
        stack.remove(sc)
        stack.add(sc)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }

    // Пробный звук при смене набора — чтобы сразу услышать выбранный вариант
    val sfx = LocalSfx.current
    var firstPack by remember { mutableStateOf(true) }
    // Пробная вибрация при смене силы
    var firstHaptics by remember { mutableStateOf(true) }
    LaunchedEffect(data.settings.haptics) {
        if (firstHaptics) { firstHaptics = false; return@LaunchedEffect }
        sfx.play(Sfx.LAUNCH)
    }
    LaunchedEffect(data.settings.sfxPack, data.settings.sfxVolume) {
        if (firstPack) { firstPack = false; return@LaunchedEffect }
        kotlinx.coroutines.delay(150)
        sfx.play(Sfx.SELECT)
    }

    LaunchedEffect(homeEvents) {
        homeEvents.collect { wasVisible ->
            stack.clear()
            // Вернулись из игры — страница игры остаётся открытой; Home в самом лаунчере закрывает всё
            consoleState.reset(keepPage = !wasVisible)
            if (wasVisible) phoneState.onHome() else {
                phoneState.sheet = null
                phoneState.openFolder = null
            }
        }
    }

    val consoleActions = remember(controller) {
        object : ConsoleActions {
            override fun launch(key: String) = if (key == ACTION_PICK_ROMS) onPickRoms() else controller.launch(key)
            override fun toggleFavorite(key: String) = controller.toggleFavorite(key)
            override fun pickRomsFolder() = onPickRoms()
            override fun rescanGames() = controller.rescanGames()
            override fun runnerName(system: GameSystem) = controller.runnerFor(system)?.name
            override fun cycleRunner(system: GameSystem, dir: Int) = controller.cycleRunner(system, dir)
            override fun refreshMeta() = controller.refreshMeta()
            override fun refreshGame(key: String) = controller.refreshGame(key)
            override fun openAchievements(raGameId: Int) = controller.achievements.loadDetail(raGameId)
            override fun cycleSortGames(dir: Int) = controller.cycleSortGames(dir)
            override fun launchDisc(key: String, index: Int) = controller.launchGame(key, index)
            override fun cycleSfx(dir: Int) = controller.cycleSfx(dir)
            override fun cycleMusic(dir: Int) = controller.cycleMusic(dir)
            override fun fixTitle(key: String) {
                editingKey = key
                push(Screen.TITLE_EDIT)
            }
            override fun setZone(key: String, zone: ConsoleZone) = controller.setZone(key, zone)
            override fun appInfo(key: String) = controller.appInfo(key)
            override fun uninstall(key: String) = controller.uninstall(key)
            override fun openSearch() = push(Screen.SEARCH)
            override fun openSettings() = push(Screen.SETTINGS)
            override fun openConsoleApps() = push(Screen.CONSOLE_APPS)
            override fun toggleLang() = controller.toggleLang()
            override fun cycleMode(dir: Int) = controller.cycleMode(dir)
            override fun openWifi() = controller.openWifi()
            override fun openBluetooth() = controller.openBluetooth()
            override fun openAndroidSettings() = controller.openAndroidSettings()
        }
    }

    val phoneActions = remember(controller, s) {
        object : PhoneActions {
            override fun launch(key: String) = controller.launch(key)
            override fun moveItem(id: Long, page: Int, x: Int, y: Int) = controller.moveItem(id, page, x, y, s.folderDefault)
            override fun removeItem(id: Long) = controller.removeItem(id)
            override fun addToHome(key: String, page: Int) = controller.addToHome(key, page)
            override fun hideInDrawer(key: String) = controller.hideInDrawer(key)
            override fun appInfo(key: String) = controller.appInfo(key)
            override fun uninstall(key: String) = controller.uninstall(key)
            override fun renameFolder(id: Long, name: String) = controller.renameFolder(id, name)
            override fun takeOutOfFolder(folderId: Long, key: String) = controller.takeOutOfFolder(folderId, key)
            override fun addPage() = controller.addPage()
            override fun deletePage(page: Int) = controller.deletePage(page)
            override fun openWallpaper() = controller.openWallpaper(s.wallpaper)
            override fun openSettings() = push(Screen.SETTINGS)
            override fun openWidgets() = push(Screen.WIDGETS)
            override fun resizeWidget(id: Long, w: Int, h: Int) = controller.resizeWidget(id, w, h)
            override fun openNotifications() = controller.openNotifications()
        }
    }

    val settingsActions = remember(controller) {
        object : SettingsActions {
            override fun toggleLang() = controller.toggleLang()
            override fun setMode(mode: ModeOverride) = controller.setMode(mode)
            override fun toggleSwap() = controller.toggleSwap()
            override fun toggleAutoAdd() = controller.toggleAutoAdd()
            override fun sortHome() = controller.sortHomeAlphabetically()
            override fun cycleColumns(dir: Int) = controller.cycleColumns(dir)
            override fun cycleRows(dir: Int) = controller.cycleRows(dir)
            override fun openConsoleApps() = push(Screen.CONSOLE_APPS)
            override fun openHiddenApps() = push(Screen.HIDDEN_APPS)
            override fun openDefaultHome() = controller.openDefaultHome()
            override fun pickRoms() = onPickRoms()
            override fun openFolders() = push(Screen.FOLDERS)
            override fun openEmulators() = push(Screen.EMULATORS)
            override fun toggleAutoMeta() = controller.toggleAutoMeta()
            override fun cycleSfx(dir: Int) = controller.cycleSfx(dir)
            override fun cycleSfxVolume(dir: Int) = controller.cycleSfxVolume(dir)
            override fun cycleMusic(dir: Int) = controller.cycleMusic(dir)
            override fun cycleMusicVolume(dir: Int) = controller.cycleMusicVolume(dir)
            override fun pickMusic() = onPickMusic()
            override fun cycleSortGames(dir: Int) = controller.cycleSortGames(dir)
            override fun toggleGamePage() = controller.toggleGamePage()
            override fun cycleTheme(dir: Int) = controller.cycleTheme(dir)
            override fun cycleAccent(dir: Int) = controller.cycleAccent(dir)
            override fun toggleTabColors() = controller.toggleTabColors()
            override fun openConsoleTabs() = push(Screen.CONSOLE_TABS)
            override fun cycleHaptics(dir: Int) = controller.cycleHaptics(dir)
            override fun openUsageAccess() = controller.openUsageAccess()
            override fun toggleGestures() = controller.toggleGestures()
            override fun cycleIconPack(dir: Int) = controller.cycleIconPack(dir)
            override fun editContact() = push(Screen.CONTACT)
            override fun editRaAccount() = push(Screen.RA_ACCOUNT)
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (console) {
            val tabs = remember(apps, data, s, gamesInfo) { buildConsoleTabs(apps, data, s, gamesInfo) }
            ConsoleHome(
                tabs = tabs,
                state = consoleState,
                mode = data.settings.mode,
                swapAB = data.settings.swapAB,
                actions = consoleActions,
                active = stack.isEmpty(),
                metaProgress = metaProgress?.let { (done, total) -> s.metaProgress.format(done, total) },
                audio = AudioState(data.settings.sfxPack, data.settings.music),
                gamePage = data.settings.gamePage,
                gameSort = data.settings.sortGames,
                tabColors = data.settings.tabColors,
                ra = raState,
            )
        } else {
            val visibleHome = remember(data.home, appsByKey) {
                data.home.mapNotNull { item ->
                    if (item.isWidget) item
                    else if (!item.isFolder) item.takeIf { it.app in appsByKey }
                    else item.copy(folderApps = item.folderApps.filter { it in appsByKey })
                        .takeIf { it.folderApps.isNotEmpty() }
                }
            }
            val drawerApps = remember(apps, data.hiddenInDrawer) { apps.filter { it.key !in data.hiddenInDrawer } }
            PhoneHome(
                data = data.copy(home = visibleHome),
                apps = appsByKey,
                drawerApps = drawerApps,
                state = phoneState,
                actions = phoneActions,
                gestures = data.settings.gestures,
            )
        }

        when (stack.lastOrNull()) {
            Screen.SETTINGS -> SettingsScreen(
                data.settings, version, console, ::pop, settingsActions,
                romsLabel = if (hasFolder) data.settings.romsTree?.let { romsLabel(it) } else null,
                usageAccess = usageAccess,
                iconPackName = remember(data.settings.iconPack) { controller.iconPackName(data.settings.iconPack) },
            )
            Screen.RA_ACCOUNT -> RaAccountScreen(
                data.settings.raUser, data.settings.raKey, ::pop,
                onSave = { u, k -> controller.setRaAccount(u, k) },
            )
            Screen.CONTACT -> ContactEditScreen(data.settings.contactEmail, ::pop) { email ->
                controller.setContactEmail(email)
                pop()
            }
            Screen.WIDGETS -> {
                val options by produceState<List<com.retrohome.launcher.data.WidgetOption>?>(null) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { controller.widgets.options() }
                }
                val px = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.roundToPx() }
                WidgetPickerScreen(
                    options,
                    preview = { o -> controller.widgets.preview(o, px)?.asImageBitmap() },
                    onBack = ::pop,
                ) { o ->
                    pop()
                    onAddWidget(o, phoneState.currentPage)
                }
            }
            Screen.CONSOLE_TABS -> {
                val all = remember(apps, data, s, gamesInfo) {
                    buildConsoleTabs(apps, data, s, gamesInfo, includeHidden = true).map { TabItem(it.id, it.title) }
                }
                ConsoleTabsScreen(
                    all, data.settings.hiddenTabs, console, ::pop,
                    onToggle = controller::toggleTabHidden,
                    onMove = { id, dir -> controller.moveTab(all.map { it.id }, id, dir) },
                )
            }
            Screen.FOLDERS -> FoldersScreen(
                scan.folders, hasFolder, scanning, console, ::pop, onPickRoms,
                onFolder = { f ->
                    pickerFolder = f.name
                    push(Screen.SYSTEM_PICKER)
                },
                romsLabel = data.settings.romsTree?.let { romsLabel(it) },
            )
            Screen.SYSTEM_PICKER -> {
                val f = scan.folders.firstOrNull { it.name == pickerFolder }
                if (f == null) pop() else SystemPickerScreen(f, data.folderOverrides[f.name], ::pop) { v ->
                    controller.setFolderOverride(f.name, v)
                    pop()
                }
            }
            Screen.EMULATORS -> EmulatorsScreen(
                systems = scan.games.map { it.system }.distinct().sortedBy { it.ordinal },
                runner = { runners[it] },
                optionsCount = { controller.runnerOptions(it).size },
                focusFirst = console,
                onBack = ::pop,
                onCycle = { sys, dir -> controller.cycleRunner(sys, dir) },
            )
            Screen.CONSOLE_APPS -> ConsoleAppsScreen(apps, data.zoneOverrides, console, ::pop) { k, z -> controller.setZone(k, z) }
            Screen.HIDDEN_APPS -> HiddenAppsScreen(
                apps.filter { it.key in data.hiddenInDrawer }, console, ::pop
            ) { controller.unhideInDrawer(it) }
            Screen.SEARCH -> {
                val pool = remember(apps, data.zoneOverrides, gamesInfo, console, s) {
                    val appItems = (if (console) apps.filter { it.zone(data.zoneOverrides) != ConsoleZone.NONE } else apps)
                        .map { SearchItem(it.key, it.label) }
                    val gameItems = if (console) gamesInfo.games.map {
                        SearchItem(it.key, gamesInfo.title(it), it.system.title(s.lang), it.system)
                    } else emptyList()
                    gameItems + appItems
                }
                SearchScreen(pool, ::pop) { key -> pop(); controller.launch(key) }
            }
            Screen.TITLE_EDIT -> {
                val g = editingKey?.let { controller.gameByKey(it) }
                if (g == null) pop() else TitleEditScreen(
                    initial = gamesInfo.title(g),
                    fileName = g.fileName,
                    hasOverride = g.id in data.titleOverrides,
                    onBack = ::pop,
                ) { t ->
                    controller.setTitleOverride(g.key, t)
                    pop()
                }
            }
            null -> {}
        }
    }
}

/** Короткая подпись выбранной папки: «SD-карта: ROMs». */
fun romsLabel(tree: String): String {
    val id = android.net.Uri.decode(tree.substringAfterLast("/tree/")).substringBefore("/document/")
    val vol = id.substringBefore(':')
    val rel = id.substringAfter(':', "")
    val where = if (vol.equals("primary", true)) "Internal" else "SD"
    return if (rel.isEmpty()) where else "$where: $rel"
}
