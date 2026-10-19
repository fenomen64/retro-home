package com.retrohome.launcher.ui.console

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrohome.launcher.data.ConsoleZone
import com.retrohome.launcher.audio.LocalSfx
import com.retrohome.launcher.audio.Sfx
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.i18n.Strings
import com.retrohome.launcher.ui.AppIcon
import com.retrohome.launcher.ui.LocalPadRegistry
import com.retrohome.launcher.ui.Pad
import com.retrohome.launcher.ui.PadGlyph
import com.retrohome.launcher.ui.Palette
import com.retrohome.launcher.ui.systemColor
import com.retrohome.launcher.ui.glyphStyleFor
import com.retrohome.launcher.ui.LocalGlyphStyle
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.layout.ContentScale
import com.retrohome.launcher.ui.rememberCover

interface ConsoleActions {
    fun launch(key: String)
    fun toggleFavorite(key: String)
    fun setZone(key: String, zone: ConsoleZone)
    fun appInfo(key: String)
    fun uninstall(key: String)
    fun openSearch()
    fun openSettings()
    fun openConsoleApps()
    fun toggleLang()
    /** dir: 1 — следующий вариант, -1 — предыдущий. */
    fun cycleMode(dir: Int = 1)
    fun openWifi()
    fun openBluetooth()
    fun openAndroidSettings()
    fun pickRomsFolder()
    fun rescanGames()
    /** Название эмулятора, который запустит игры системы, и можно ли выбрать другой. */
    fun runnerName(system: GameSystem): String?
    fun cycleRunner(system: GameSystem, dir: Int = 1)
    fun refreshMeta()
    fun launchDisc(key: String, index: Int)
    fun fixTitle(key: String)
    /** Заново поискать обложку и описание игры. */
    fun refreshGame(key: String) {}
    /** Порядок игр на вкладках консолей. */
    fun cycleSortGames(dir: Int = 1) {}
    /** Открыта панель достижений игры сайта RetroAchievements — загрузить полный список. */
    fun openAchievements(raGameId: Int) {}
    fun cycleSfx(dir: Int = 1)
    fun cycleMusic(dir: Int = 1)
}

/** Текущие звуковые настройки для меню Start. */
data class AudioState(val sfxPack: String = "soft", val music: String = "off")

sealed interface ConsoleOverlay {
    data object Start : ConsoleOverlay
    data class Item(val entry: ConsoleEntry) : ConsoleOverlay
    data class Description(val entry: ConsoleEntry, val log: Boolean = false) : ConsoleOverlay
    /** Достижения игры (ключ — чтобы прогресс обновлялся на лету). */
    data class Achievements(val key: String) : ConsoleOverlay
}

/** Состояние консольного экрана: живёт выше переключателя режимов, чтобы не сбрасываться при повороте. */
class ConsoleState {
    /** Вкладка запоминается по имени: при появлении новых вкладок консолей выбор не съезжает. */
    var tabId by mutableStateOf("home")
    var tabIds: List<String> = listOf("home")
    var tab: Int
        get() = tabIds.indexOf(tabId).coerceAtLeast(0)
        set(v) { tabId = tabIds.getOrElse(v) { "home" } }
    val rowSel = mutableStateMapOf<String, Int>()
    val itemSel = mutableStateMapOf<String, Int>()
    var overlay by mutableStateOf<ConsoleOverlay?>(null)
    var menuIndex by mutableIntStateOf(0)
    var descScroll by mutableStateOf(ScrollState(0))
    /** Открытая страница игры (ключ) — лежит под меню, поэтому меню можно открыть поверх неё. */
    var page by mutableStateOf<String?>(null)
    var pageButton by mutableIntStateOf(0)
    var pageScroll by mutableStateOf(ScrollState(0))
    var achList by mutableStateOf(androidx.compose.foundation.lazy.LazyListState())
    /** В панели достижений — только ещё не открытые. */
    var achLockedOnly by mutableStateOf(false)

    fun openPage(key: String) {
        page = key
        pageButton = 0
        pageScroll = ScrollState(0)
    }

    fun row(tabs: List<ConsoleTab>): Int =
        (rowSel[tabId] ?: 0).coerceIn(0, (tabs.getOrNull(tab)?.rows?.size ?: 1) - 1)

    fun item(tabs: List<ConsoleTab>, row: Int): Int {
        val size = tabs.getOrNull(tab)?.rows?.getOrNull(row)?.entries?.size ?: 0
        return (itemSel["$tabId:$row"] ?: 0).coerceIn(0, (size - 1).coerceAtLeast(0))
    }

    fun selected(tabs: List<ConsoleTab>): ConsoleEntry? {
        val r = row(tabs)
        return tabs.getOrNull(tab)?.rows?.getOrNull(r)?.entries?.getOrNull(item(tabs, r))
    }

    fun select(row: Int, item: Int) {
        rowSel[tabId] = row
        itemSel["$tabId:$row"] = item
    }

    /** Нажатие Home: закрыть всё лишнее. keepPage — вернулись из игры: страница игры остаётся. */
    fun reset(keepPage: Boolean = false) {
        overlay = null
        if (!keepPage) page = null
    }
}

data class MenuEntry(
    val title: String,
    val value: String? = null,
    val closeAfter: Boolean = true,
    /** Для переключателей: шаг назад (-1) или вперёд (1) — кнопками влево/вправо и стрелками ‹ ›. */
    val step: ((Int) -> Unit)? = null,
    val action: () -> Unit,
)

/** Пункт-переключатель: нажатие листает вперёд, влево/вправо — в обе стороны. */
fun choiceEntry(title: String, value: String, step: (Int) -> Unit) =
    MenuEntry(title, value, closeAfter = false, step = step) { step(1) }

fun startMenu(s: Strings, mode: ModeOverride, a: ConsoleActions, audio: AudioState = AudioState(), gameSort: String = "name"): List<MenuEntry> = listOf(
    MenuEntry(s.search) { a.openSearch() },
    choiceEntry(s.sortGames, s.sortName(gameSort)) { a.cycleSortGames(it) },
    choiceEntry(s.sounds, s.packName(audio.sfxPack)) { a.cycleSfx(it) },
    choiceEntry(s.music, s.trackName(audio.music)) { a.cycleMusic(it) },
    MenuEntry(s.rescan) { a.rescanGames() },
    MenuEntry(s.updateMeta) { a.refreshMeta() },
    MenuEntry(s.romsFolder) { a.pickRomsFolder() },
    choiceEntry(s.language, s.langName) { a.toggleLang() },
    choiceEntry(
        s.mode,
        when (mode) {
            ModeOverride.AUTO -> s.modeAuto
            ModeOverride.CONSOLE -> s.modeConsole
            ModeOverride.PHONE -> s.modePhone
        },
    ) { a.cycleMode(it) },
    MenuEntry(s.consoleApps) { a.openConsoleApps() },
    MenuEntry(s.settings) { a.openSettings() },
    MenuEntry(s.wifi) { a.openWifi() },
    MenuEntry(s.bluetooth) { a.openBluetooth() },
    MenuEntry(s.androidSettings) { a.openAndroidSettings() },
    MenuEntry(s.close) {},
)

fun itemMenu(
    s: Strings, e: ConsoleEntry, a: ConsoleActions,
    onDescription: () -> Unit = {}, onLog: () -> Unit = {}, onAchievements: () -> Unit = {},
): List<MenuEntry> = when (e.kind) {
    EntryKind.ACTION -> listOf(MenuEntry(s.launch) { a.launch(e.key) })
    EntryKind.GAME -> listOfNotNull(
        MenuEntry(s.launch) { a.launch(e.key) },
        if (e.description != null) MenuEntry(s.description) { onDescription() } else null,
        e.ra?.let { MenuEntry(s.ra.achievements, "${it.percent}%") { onAchievements() } },
    ) + (1 until e.discCount).map { i -> MenuEntry(s.disc.format(i + 1)) { a.launchDisc(e.key, i) } } + listOfNotNull(
        MenuEntry(if (e.favorite) s.removeFavorite else s.addFavorite) { a.toggleFavorite(e.key) },
        choiceEntry(s.launchVia, e.system?.let { a.runnerName(it) } ?: s.noRunner) { dir ->
            e.system?.let { a.cycleRunner(it, dir) }
        },
        MenuEntry(s.fixTitle) { a.fixTitle(e.key) },
        MenuEntry(s.refreshGame) { a.refreshGame(e.key) },
        if (e.lookupLog.isNotEmpty()) MenuEntry(s.lookupLog) { onLog() } else null,
    )
    EntryKind.APP -> appMenu(s, e, a)
}

private fun appMenu(s: Strings, e: ConsoleEntry, a: ConsoleActions): List<MenuEntry> = listOfNotNull(
    MenuEntry(s.launch) { a.launch(e.key) },
    MenuEntry(if (e.favorite) s.removeFavorite else s.addFavorite) { a.toggleFavorite(e.key) },
    if (e.zone == ConsoleZone.GAME) MenuEntry(s.moveToEmulators) { a.setZone(e.key, ConsoleZone.EMULATOR) }
    else MenuEntry(s.moveToGames) { a.setZone(e.key, ConsoleZone.GAME) },
    MenuEntry(s.hideFromConsole) { a.setZone(e.key, ConsoleZone.NONE) },
    MenuEntry(s.appInfo) { a.appInfo(e.key) },
    MenuEntry(s.uninstall) { a.uninstall(e.key) },
)

@Composable
fun ConsoleHome(
    tabs: List<ConsoleTab>,
    state: ConsoleState,
    mode: ModeOverride,
    swapAB: Boolean,
    actions: ConsoleActions,
    active: Boolean = true,
    metaProgress: String? = null,
    audio: AudioState = AudioState(),
    /** A на игре открывает её страницу (false — сразу запуск). */
    gamePage: Boolean = true,
    gameSort: String = "name",
    /** Вкладки консолей окрашиваются в цвет консоли. */
    tabColors: Boolean = true,
    /** Достижения RetroAchievements (полные списки для панели). */
    ra: com.retrohome.launcher.data.RaState = com.retrohome.launcher.data.RaState(),
) {
    val s = LocalStrings.current
    val gamePageNow by rememberUpdatedState(gamePage)
    val gameSortNow by rememberUpdatedState(gameSort)
    val sfx = LocalSfx.current
    val audioNow by rememberUpdatedState(audio)
    state.tabIds = tabs.map { it.id }
    val tabsNow by rememberUpdatedState(tabs)
    val modeNow by rememberUpdatedState(mode)
    val sNow by rememberUpdatedState(s)

    fun openDescription(e: ConsoleEntry, log: Boolean = false) {
        state.descScroll = ScrollState(0)
        state.overlay = ConsoleOverlay.Description(e, log)
        sfx.play(Sfx.MENU)
    }

    fun openAchievements(e: ConsoleEntry) {
        val ra = e.ra ?: return
        state.achList = androidx.compose.foundation.lazy.LazyListState()
        state.achLockedOnly = false
        state.overlay = ConsoleOverlay.Achievements(e.key)
        actions.openAchievements(ra.gameId)
        sfx.play(Sfx.MENU)
    }

    fun menuItems(): List<MenuEntry> = when (val o = state.overlay) {
        ConsoleOverlay.Start -> startMenu(sNow, modeNow, actions, audioNow, gameSortNow)
        is ConsoleOverlay.Item -> itemMenu(
            sNow, o.entry, actions, { openDescription(o.entry) }, { openDescription(o.entry, log = true) },
            { openAchievements(o.entry) },
        )
        is ConsoleOverlay.Description, is ConsoleOverlay.Achievements, null -> emptyList()
    }

    fun runMenu(i: Int) {
        val items = menuItems()
        val m = items.getOrNull(i) ?: return
        if (m.closeAfter) state.overlay = null
        m.action()
    }

    /** Запись в табах по ключу (для открытой страницы игры — всегда свежие данные). */
    fun entryByKey(key: String?): ConsoleEntry? =
        key?.let { k -> tabsNow.asSequence().flatMap { it.rows }.flatMap { it.entries }.firstOrNull { it.key == k } }

    /** Открыть игру: страница или сразу запуск — как выбрано в настройках. */
    fun activate(e: ConsoleEntry) {
        if (e.kind == EntryKind.GAME && gamePageNow) {
            state.openPage(e.key)
            sfx.play(Sfx.SELECT)
        } else {
            sfx.play(Sfx.LAUNCH)
            actions.launch(e.key)
        }
    }

    fun pressPageButton(e: ConsoleEntry, i: Int) {
        state.pageButton = i
        when (val b = pageButtons(e).getOrNull(i)) {
            PageButton.Play -> { sfx.play(Sfx.LAUNCH); actions.launch(e.key) }
            is PageButton.Disc -> { sfx.play(Sfx.LAUNCH); actions.launchDisc(e.key, b.index) }
            PageButton.Achievements -> openAchievements(e)
            PageButton.Favorite -> { sfx.play(Sfx.FAVORITE); actions.toggleFavorite(e.key) }
            PageButton.More -> { state.menuIndex = 0; state.overlay = ConsoleOverlay.Item(e); sfx.play(Sfx.MENU) }
            null -> {}
        }
    }

    /** Соседняя игра в том же ряду (L1/R1 на странице игры). */
    fun switchGame(dir: Int) {
        val t = tabsNow
        val row = state.row(t)
        val entries = t.getOrNull(state.tab)?.rows?.getOrNull(row)?.entries.orEmpty()
        val games = entries.withIndex().filter { it.value.kind == EntryKind.GAME }
        if (games.size < 2) return
        val cur = games.indexOfFirst { it.value.key == state.page }.coerceAtLeast(0)
        val next = games[(cur + dir).mod(games.size)]
        state.select(row, next.index)
        state.openPage(next.value.key)
        sfx.play(Sfx.MOVE)
    }

    val handler: (Pad) -> Boolean = remember(state, actions, sfx) {
        padHandler@{ pad ->
            val t = tabsNow
            val pageEntry = if (state.overlay == null) entryByKey(state.page) else null
            if (state.page != null && state.overlay == null && pageEntry == null) state.page = null
            if (pageEntry != null) {
                val n = pageButtons(pageEntry).size
                when (pad) {
                    Pad.LEFT -> if (state.pageButton > 0) { state.pageButton--; sfx.play(Sfx.MOVE) }
                    Pad.RIGHT -> if (state.pageButton < n - 1) { state.pageButton++; sfx.play(Sfx.MOVE) }
                    Pad.UP -> state.pageScroll.dispatchRawDelta(-200f)
                    Pad.DOWN -> state.pageScroll.dispatchRawDelta(200f)
                    Pad.CONFIRM -> pressPageButton(pageEntry, state.pageButton.coerceIn(0, n - 1))
                    Pad.CANCEL -> { state.page = null; sfx.play(Sfx.BACK) }
                    Pad.X -> { state.menuIndex = 0; state.overlay = ConsoleOverlay.Item(pageEntry); sfx.play(Sfx.MENU) }
                    Pad.Y -> { actions.toggleFavorite(pageEntry.key); sfx.play(Sfx.FAVORITE) }
                    Pad.L1 -> switchGame(-1)
                    Pad.R1 -> switchGame(1)
                    Pad.START -> { state.menuIndex = 0; state.overlay = ConsoleOverlay.Start; sfx.play(Sfx.MENU) }
                    Pad.SELECT -> { sfx.play(Sfx.SELECT); actions.openSearch() }
                }
                true
            } else if (state.overlay is ConsoleOverlay.Achievements) {
                when (pad) {
                    Pad.UP -> state.achList.dispatchRawDelta(-180f)
                    Pad.DOWN -> state.achList.dispatchRawDelta(180f)
                    Pad.Y -> {
                        state.achLockedOnly = !state.achLockedOnly
                        state.achList = androidx.compose.foundation.lazy.LazyListState()
                        sfx.play(Sfx.SELECT)
                    }
                    Pad.CANCEL, Pad.X, Pad.START -> { state.overlay = null; sfx.play(Sfx.BACK) }
                    else -> {}
                }
                true
            } else if (state.overlay is ConsoleOverlay.Description) {
                when (pad) {
                    Pad.UP -> state.descScroll.dispatchRawDelta(-220f)
                    Pad.DOWN -> state.descScroll.dispatchRawDelta(220f)
                    Pad.LEFT, Pad.RIGHT, Pad.L1, Pad.R1, Pad.Y, Pad.SELECT -> {}
                    else -> { state.overlay = null; sfx.play(Sfx.BACK) }
                }
                true
            } else if (state.overlay != null) {
                val n = menuItems().size
                when (pad) {
                    Pad.UP -> { state.menuIndex = (state.menuIndex - 1 + n) % n; sfx.play(Sfx.MOVE) }
                    Pad.DOWN -> { state.menuIndex = (state.menuIndex + 1) % n; sfx.play(Sfx.MOVE) }
                    Pad.LEFT, Pad.RIGHT -> {
                        val m = menuItems().getOrNull(state.menuIndex)
                        val step = m?.step
                        if (step != null) { step(if (pad == Pad.LEFT) -1 else 1); sfx.play(Sfx.SELECT) }
                    }
                    Pad.CONFIRM -> {
                        val m = menuItems().getOrNull(state.menuIndex)
                        sfx.play(if (m?.title == sNow.launch) Sfx.LAUNCH else Sfx.SELECT)
                        runMenu(state.menuIndex)
                    }
                    Pad.CANCEL, Pad.START, Pad.X -> { state.overlay = null; sfx.play(Sfx.BACK) }
                    else -> {}
                }
                true
            } else {
                val row = state.row(t)
                val rows = t.getOrNull(state.tab)?.rows.orEmpty()
                val entries = rows.getOrNull(row)?.entries.orEmpty()
                val item = state.item(t, row)
                val listTab = t.getOrNull(state.tab)?.list == true
                if (listTab && (pad == Pad.UP || pad == Pad.DOWN || pad == Pad.LEFT || pad == Pad.RIGHT)) {
                    // Главная — столбик: «Последняя игра», под ней избранное сверху вниз
                    when (pad) {
                        Pad.UP -> when {
                            row > 0 && item > 0 -> { state.select(row, item - 1); sfx.play(Sfx.MOVE) }
                            row > 0 -> { state.rowSel[state.tabId] = row - 1; sfx.play(Sfx.MOVE) }
                        }
                        Pad.DOWN -> when {
                            row == 0 && rows.size > 1 -> { state.select(1, 0); sfx.play(Sfx.MOVE) }
                            row > 0 && item < entries.size - 1 -> { state.select(row, item + 1); sfx.play(Sfx.MOVE) }
                        }
                        else -> {}
                    }
                    return@padHandler true
                }
                when (pad) {
                    Pad.LEFT -> if (item > 0) { state.select(row, item - 1); sfx.play(Sfx.MOVE) }
                    Pad.RIGHT -> if (item < entries.size - 1) { state.select(row, item + 1); sfx.play(Sfx.MOVE) }
                    Pad.UP -> if (row > 0) { state.rowSel[state.tabId] = row - 1; sfx.play(Sfx.MOVE) }
                    Pad.DOWN -> if (row < rows.size - 1) { state.rowSel[state.tabId] = row + 1; sfx.play(Sfx.MOVE) }
                    Pad.L1 -> { state.tab = (state.tab - 1 + t.size) % t.size; sfx.play(Sfx.TAB) }
                    Pad.R1 -> { state.tab = (state.tab + 1) % t.size; sfx.play(Sfx.TAB) }
                    Pad.CONFIRM -> state.selected(t)?.let { activate(it) }
                    Pad.X -> state.selected(t)?.let { state.menuIndex = 0; state.overlay = ConsoleOverlay.Item(it); sfx.play(Sfx.MENU) }
                    Pad.Y -> state.selected(t)?.let { actions.toggleFavorite(it.key); sfx.play(Sfx.FAVORITE) }
                    Pad.START -> { state.menuIndex = 0; state.overlay = ConsoleOverlay.Start; sfx.play(Sfx.MENU) }
                    Pad.SELECT -> { sfx.play(Sfx.SELECT); actions.openSearch() }
                    Pad.CANCEL -> if (state.tab != 0) { state.tab = 0; sfx.play(Sfx.BACK) }
                }
                true
            }
        }
    }

    val registry = LocalPadRegistry.current
    if (active) {
        DisposableEffect(registry, handler) {
            registry.register(handler)
            onDispose { registry.unregister(handler) }
        }
    }

    val confirmGlyph = if (swapAB) "B" else "A"
    val cancelGlyph = if (swapAB) "A" else "B"

    // Цвет текущей консоли: фон и выделение плавно перетекают при смене вкладки
    val tabSystem = tabs.getOrNull(state.tab)?.id?.removePrefix("sys:")?.takeIf { tabColors && tabs[state.tab].id.startsWith("sys:") }
        ?.let { runCatching { GameSystem.valueOf(it) }.getOrNull() }
    val tint by animateColorAsState(tabSystem?.let { systemColor(it) } ?: Palette.accent, tween(350), label = "tabTint")
    val bgTint by animateFloatAsState(if (tabSystem != null) (if (Palette.theme.light) 0.14f else 0.28f) else 0f, tween(350), label = "bgTint")
    // Кнопки на экране — в стиле консоли текущей вкладки (значки PlayStation, цвета SNES и т.д.)
    val glyphs = if (tabColors) glyphStyleFor(tabSystem) else null
    CompositionLocalProvider(LocalGlyphStyle provides glyphs) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(Palette.bgTop, tint, bgTint), Palette.bgBottom)))
    ) {
        // Фон за выбранной игрой: снимок экрана из неё, а если его нет — размытая обложка
        val sel = state.selected(tabs)
        val snapBg = rememberCover(sel?.snap)
        val backdrop = rememberCover(sel?.cover)
        if (snapBg != null) {
            Image(
                snapBg, null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().blur(3.dp).alpha(0.42f),
            )
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Palette.bgTop.copy(alpha = 0.55f), 0.45f to Color.Transparent, 1f to Palette.bgBottom.copy(alpha = 0.95f))))
        } else if (backdrop != null) {
            Image(
                backdrop, null,
                contentScale = ContentScale.Crop,
                // На OLED-чёрной теме фон почти не подсвечиваем — чёрный остаётся чёрным
                modifier = Modifier.fillMaxSize().blur(36.dp).alpha(if (Palette.theme.id == "oled") 0.16f else 0.30f),
            )
        }
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            CompositionLocalProvider(LocalHighlight provides if (tabSystem != null) tint else null) {
                TabBar(tabs, state.tab, metaProgress) { state.tab = it; sfx.play(Sfx.TAB) }
                val tab = tabs.getOrNull(state.tab)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (tab != null) TabContent(tab, state, actions, ::activate)
                }
            }
            HintBar(
                hasSelection = state.selected(tabs) != null,
                confirm = confirmGlyph,
                opensPage = gamePage && state.selected(tabs)?.kind == EntryKind.GAME,
                onLaunch = { state.selected(tabs)?.let { activate(it) } },
                onOptions = { state.selected(tabs)?.let { state.menuIndex = 0; state.overlay = ConsoleOverlay.Item(it) } },
                onFavorite = { state.selected(tabs)?.let { actions.toggleFavorite(it.key) } },
                onSearch = { actions.openSearch() },
                onMenu = { state.menuIndex = 0; state.overlay = ConsoleOverlay.Start },
            )
        }

        // Страница игры — поверх рядов, под меню
        val pageEntry = entryByKey(state.page)
        AnimatedVisibility(pageEntry != null, enter = fadeIn(), exit = fadeOut()) {
            // Последняя открытая игра остаётся на экране, пока страница плавно исчезает
            val shown = remember { mutableStateOf<ConsoleEntry?>(null) }
            if (pageEntry != null) shown.value = pageEntry
            shown.value?.let { e ->
                CompositionLocalProvider(LocalGlyphStyle provides if (tabColors) glyphStyleFor(e.system) else null) {
                GamePage(
                    e = e,
                    focused = state.pageButton,
                    scroll = state.pageScroll,
                    confirmGlyph = confirmGlyph,
                    cancelGlyph = cancelGlyph,
                    canSwitch = (tabs.getOrNull(state.tab)?.rows?.getOrNull(state.row(tabs))?.entries?.count { it.kind == EntryKind.GAME } ?: 0) > 1,
                    onButton = { i -> pressPageButton(e, i) },
                    onBack = { state.page = null; sfx.play(Sfx.BACK) },
                    onOptions = { state.menuIndex = 0; state.overlay = ConsoleOverlay.Item(e); sfx.play(Sfx.MENU) },
                    onFavorite = { actions.toggleFavorite(e.key); sfx.play(Sfx.FAVORITE) },
                    onSwitch = ::switchGame,
                )
                }
            }
        }
        // Жест «назад» на экране: закрыть меню, затем страницу игры
        BackHandler(enabled = active && (state.overlay != null || pageEntry != null)) {
            if (state.overlay != null) state.overlay = null else state.page = null
            sfx.play(Sfx.BACK)
        }

        // Затемнение под меню; тап по нему закрывает меню
        AnimatedVisibility(state.overlay != null, enter = fadeIn(), exit = fadeOut()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Palette.scrim)
                    .clickable(remember { MutableInteractionSource() }, null) { state.overlay = null }
            )
        }
        val overlay = state.overlay
        AnimatedVisibility(
            overlay == ConsoleOverlay.Start,
            Modifier.align(Alignment.CenterEnd),
            enter = slideInHorizontally { it } + fadeIn(),
            exit = slideOutHorizontally { it } + fadeOut(),
        ) {
            MenuPanel(
                title = s.menuTitle,
                items = startMenu(s, mode, actions, audio, gameSort),
                selected = state.menuIndex,
                cancelGlyph = cancelGlyph,
                modifier = Modifier.fillMaxHeight().width(330.dp).statusBarsPadding().navigationBarsPadding(),
                onStep = { i, dir -> state.menuIndex = i; menuItems().getOrNull(i)?.step?.invoke(dir) },
                onClick = { state.menuIndex = it; runMenu(it) },
            )
        }
        if (overlay is ConsoleOverlay.Item) {
            Box(Modifier.align(Alignment.Center)) {
                MenuPanel(
                    title = overlay.entry.label,
                    items = itemMenu(
                        s, overlay.entry, actions, { openDescription(overlay.entry) }, { openDescription(overlay.entry, log = true) },
                        { openAchievements(overlay.entry) },
                    ),
                    selected = state.menuIndex,
                    cancelGlyph = cancelGlyph,
                    modifier = Modifier.widthIn(max = 360.dp).clip(RoundedCornerShape(20.dp)),
                    onStep = { i, dir -> state.menuIndex = i; menuItems().getOrNull(i)?.step?.invoke(dir) },
                onClick = { state.menuIndex = it; runMenu(it) },
                )
            }
        }
        if (overlay is ConsoleOverlay.Description) {
            DescriptionPanel(overlay.entry, state.descScroll, cancelGlyph, Modifier.align(Alignment.Center), overlay.log)
        }
        if (overlay is ConsoleOverlay.Achievements) {
            val e = entryByKey(overlay.key)
            if (e == null) state.overlay = null
            else {
                val id = e.ra?.gameId
                // Значки кнопок — как на странице игры (○ для PlayStation и т.д.)
                CompositionLocalProvider(LocalGlyphStyle provides if (tabColors) glyphStyleFor(e.system) else null) {
                    AchievementsPanel(
                        e, id?.let { ra.details[it] }, id != null && id in ra.loading, ra.status,
                        state.achLockedOnly, state.achList, cancelGlyph, Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}
}

@Composable
private fun DescriptionPanel(e: ConsoleEntry, scroll: ScrollState, cancelGlyph: String, modifier: Modifier, log: Boolean = false) {
    val s = LocalStrings.current
    Row(
        modifier
            .fillMaxWidth(0.86f)
            .fillMaxHeight(0.86f)
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.surface)
            .padding(18.dp)
    ) {
        val cover = rememberCover(e.cover)
        if (cover != null) {
            Image(
                cover, null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight().widthIn(max = 220.dp).clip(RoundedCornerShape(12.dp)),
            )
            Spacer(Modifier.width(18.dp))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Text(e.label, color = Palette.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(e.subtitle, color = Palette.dim, fontSize = 13.sp, maxLines = 1)
            if (e.descFallback) {
                Text(if (s.lang == com.retrohome.launcher.data.Lang.RU) s.englishOnly else s.russianOnly, color = Palette.accent, fontSize = 12.sp)
            }
            Spacer(Modifier.height(10.dp))
            Box(Modifier.weight(1f).verticalScroll(scroll)) {
                if (log) Text(e.lookupLog.joinToString("\n"), color = Palette.text, fontSize = 13.sp, lineHeight = 19.sp)
                else Text(e.description ?: s.noDescription, color = Palette.text, fontSize = 15.sp, lineHeight = 21.sp)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PadGlyph(cancelGlyph)
                Spacer(Modifier.width(6.dp))
                Text(s.hintBack, color = Palette.dim, fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                if (e.description != null) {
                    Text(s.sourceName(e.descSource), color = Palette.dim, fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
private fun TabBar(tabs: List<ConsoleTab>, current: Int, progress: String?, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PadGlyph("L1", Modifier.clickable { onSelect((current - 1 + tabs.size) % tabs.size) })
        LazyRow(
            Modifier.weight(1f).padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(horizontal = 4.dp),
        ) {
            itemsIndexed(tabs, key = { _, t -> t.id }) { i, t ->
                val sel = i == current
                val hl = LocalHighlight.current
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (sel) hl ?: Palette.text else Color.Transparent)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 16.dp, vertical = 7.dp)
                ) {
                    Text(
                        t.title,
                        color = if (sel) (if (hl != null) Color.White else Palette.bgBottom) else Palette.dim,
                        fontSize = 15.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
        if (progress != null) {
            Text(progress, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(end = 10.dp))
        }
        PadGlyph("R1", Modifier.clickable { onSelect((current + 1) % tabs.size) })
    }
}

@Composable
private fun TabContent(tab: ConsoleTab, state: ConsoleState, actions: ConsoleActions, activate: (ConsoleEntry) -> Unit) {
    val sfx = LocalSfx.current
    if (tab.list) {
        HomeList(
            tab, state,
            onTap = { r, i -> state.select(r, i); tab.rows[r].entries.getOrNull(i)?.let(activate) },
            onLongPress = { r, i ->
                state.select(r, i)
                tab.rows[r].entries.getOrNull(i)?.let { state.menuIndex = 0; state.overlay = ConsoleOverlay.Item(it); sfx.play(Sfx.MENU) }
            },
        )
        return
    }
    val single = tab.rows.size == 1
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cardSize: Dp = if (single) (maxHeight * 0.48f).coerceIn(96.dp, 168.dp)
        else (maxHeight / tab.rows.size - 54.dp).coerceIn(64.dp, 120.dp)
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = if (single) Arrangement.Center else Arrangement.SpaceEvenly,
        ) {
            val activeRow = (state.rowSel[state.tabId] ?: 0).coerceIn(0, tab.rows.size - 1)
            tab.rows.forEachIndexed { r, row ->
                val isActive = r == activeRow
                val sel = (state.itemSel["${state.tabId}:$r"] ?: 0).coerceIn(0, (row.entries.size - 1).coerceAtLeast(0))
                if (row.title != null) {
                    Row(Modifier.padding(start = 28.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            row.title,
                            color = if (isActive) Palette.text else Palette.dim,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        val name = row.entries.getOrNull(sel)?.label
                        if (isActive && name != null) {
                            Text("  ·  $name", color = Palette.dim, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                CardRow(
                    entries = row.entries,
                    emptyText = row.emptyText,
                    selected = sel,
                    rowActive = isActive,
                    cardSize = cardSize,
                    tabKey = "${tab.id}:$r",
                    onTap = { i ->
                        state.select(r, i)
                        activate(row.entries[i])
                    },
                    onLongPress = { i ->
                        state.select(r, i)
                        state.menuIndex = 0
                        state.overlay = ConsoleOverlay.Item(row.entries[i])
                        sfx.play(Sfx.MENU)
                    },
                )
                if (single) {
                    val e = row.entries.getOrNull(sel)
                    Column(Modifier.padding(start = 32.dp, top = 18.dp)) {
                        Text(
                            e?.label ?: "",
                            color = Palette.text,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            (e?.subtitle ?: "") + (e?.takeIf { it.playMs > 0 }?.let { "  ·  ⏱ " + LocalStrings.current.duration(it.playMs) } ?: ""),
                            color = Palette.dim,
                            fontSize = 15.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (e?.description != null) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                e.description,
                                color = Palette.text.copy(alpha = 0.8f),
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(end = 32.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CardRow(
    entries: List<ConsoleEntry>,
    emptyText: String,
    selected: Int,
    rowActive: Boolean,
    cardSize: Dp,
    tabKey: String,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    if (entries.isEmpty()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(cardSize)
                .padding(horizontal = 28.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Palette.surface.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(emptyText, color = Palette.dim, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(selected, tabKey, entries.size) {
        listState.animateScrollToItem((selected - 1).coerceAtLeast(0))
    }
    LazyRow(
        state = listState,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 28.dp, vertical = 10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
            val isSel = rowActive && i == selected
            val scale by animateFloatAsState(if (isSel) 1.08f else 1f, label = "cardScale")
            val w = when (e.kind) {
                EntryKind.GAME -> cardSize * 0.75f
                EntryKind.ACTION -> cardSize * 2.6f
                EntryKind.APP -> cardSize
            }
            val bg = if (e.kind == EntryKind.GAME && e.system != null) {
                val c = systemColor(e.system)
                Brush.verticalGradient(listOf(c, c.copy(red = c.red * 0.45f, green = c.green * 0.45f, blue = c.blue * 0.45f)))
            } else Brush.verticalGradient(List(2) { if (isSel) Palette.surfaceHi else Palette.surface })
            Box(
                Modifier
                    .width(w)
                    .height(cardSize)
                    .scale(scale)
                    .clip(RoundedCornerShape(22.dp))
                    .background(bg)
                    .then(if (isSel) Modifier.border(3.dp, highlight(), RoundedCornerShape(22.dp)) else Modifier)
                    .combinedClickable(onClick = { onTap(i) }, onLongClick = { onLongPress(i) }),
                contentAlignment = Alignment.Center,
            ) {
                when (e.kind) {
                    EntryKind.APP -> AppIcon(e.key, cardSize * 0.58f)
                    EntryKind.GAME -> GameFace(e)
                    EntryKind.ACTION -> ActionFace(e)
                }
                if (e.favorite) FavoriteStar(Modifier.align(Alignment.TopEnd).padding(5.dp), size = 24.sp)
            }
        }
    }
}

/** Лицевая сторона карточки игры, пока нет обложки: консоль и название. */
@Composable
internal fun GameFace(e: ConsoleEntry) {
    val cover = rememberCover(e.cover)
    if (cover != null) {
        Box(Modifier.fillMaxSize()) {
            Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        return
    }
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Text(
            e.system?.short ?: "",
            color = Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black.copy(alpha = 0.25f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Spacer(Modifier.weight(1f))
        Text(
            e.label,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            lineHeight = 17.sp,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
internal fun ActionFace(e: ConsoleEntry) {
    Row(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("＋", color = Palette.accent, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(e.label, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp)
            Text(e.subtitle, color = Palette.dim, fontSize = 12.sp, lineHeight = 15.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun HintBar(
    hasSelection: Boolean,
    confirm: String,
    opensPage: Boolean,
    onLaunch: () -> Unit,
    onOptions: () -> Unit,
    onFavorite: () -> Unit,
    onSearch: () -> Unit,
    onMenu: () -> Unit,
) {
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (hasSelection) {
            Hint(confirm, if (opensPage) s.open else s.hintLaunch, onLaunch)
            Hint("X", s.hintOptions, onOptions)
            Hint("Y", s.hintFavorite, onFavorite)
        }
        Spacer(Modifier.weight(1f))
        Hint("SELECT", s.hintSearch, onSearch)
        Hint("START", s.hintMenu, onMenu)
    }
}

@Composable
private fun Hint(glyph: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PadGlyph(glyph)
        Spacer(Modifier.width(6.dp))
        Text(text, color = Palette.text, fontSize = 13.sp)
    }
}

@Composable
fun MenuPanel(
    title: String,
    items: List<MenuEntry>,
    selected: Int,
    cancelGlyph: String,
    modifier: Modifier = Modifier,
    onStep: (Int, Int) -> Unit = { i, dir -> items.getOrNull(i)?.step?.invoke(dir) },
    onClick: (Int) -> Unit,
) {
    val s = LocalStrings.current
    Column(modifier.background(Palette.surface).padding(vertical = 14.dp)) {
        Text(
            title,
            color = Palette.text,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        val listState = rememberLazyListState()
        LaunchedEffect(selected) { listState.animateScrollToItem((selected - 2).coerceAtLeast(0)) }
        LazyColumn(state = listState, modifier = Modifier.weight(1f, fill = false)) {
            itemsIndexed(items) { i, m ->
                val sel = i == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 2.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) Palette.surfaceHi else Color.Transparent)
                        .then(if (sel) Modifier.border(2.dp, Palette.accent, RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { onClick(i) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(m.title, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    if (m.value != null && m.step != null) {
                        // Стрелки нажимаются пальцем: ‹ — назад, › — вперёд
                        Text(
                            "‹", color = Palette.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onStep(i, -1) }
                                .padding(horizontal = 6.dp),
                        )
                        Text(m.value, color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                        Text(
                            "›", color = Palette.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onStep(i, 1) }
                                .padding(horizontal = 6.dp),
                        )
                    } else if (m.value != null) {
                        Text(m.value, color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        Row(Modifier.padding(start = 20.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            PadGlyph(cancelGlyph)
            Spacer(Modifier.width(6.dp))
            Text(s.hintBack, color = Palette.dim, fontSize = 13.sp)
        }
    }
}

/** Цвет выделения на вкладке консоли (null — обычный акцент темы). */
val LocalHighlight = compositionLocalOf<Color?> { null }

@Composable
fun highlight(): Color = LocalHighlight.current ?: Palette.accent
