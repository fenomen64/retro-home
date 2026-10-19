package com.retrohome.launcher.ui

import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.Key
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.FOLDER_IGNORE
import com.retrohome.launcher.data.FolderInfo
import com.retrohome.launcher.data.GameFiles
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.ConsoleZone
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.data.Settings
import com.retrohome.launcher.data.zone
import com.retrohome.launcher.i18n.LocalStrings

/** Общая рамка экрана: заголовок с кнопкой «назад» и непрозрачный фон. */
@Composable
fun ScreenFrame(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onBack)
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.bgBottom)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹",
                color = Palette.text,
                fontSize = 28.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 2.dp),
            )
            Text(title, color = Palette.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.weight(1f)) { content() }
    }
}

interface SettingsActions {
    fun toggleLang()
    fun setMode(mode: ModeOverride)
    fun toggleSwap()
    fun toggleAutoAdd()
    fun sortHome()
    fun cycleColumns(dir: Int = 1)
    fun cycleRows(dir: Int = 1)
    fun openConsoleApps()
    fun openHiddenApps()
    fun openDefaultHome()
    fun pickRoms()
    fun openFolders()
    fun openEmulators()
    fun toggleAutoMeta()
    fun cycleSfx(dir: Int = 1)
    fun cycleSfxVolume(dir: Int = 1)
    fun cycleMusic(dir: Int = 1)
    fun cycleMusicVolume(dir: Int = 1)
    fun pickMusic()
    fun cycleSortGames(dir: Int = 1) {}
    fun toggleGamePage() {}
    fun cycleTheme(dir: Int = 1) {}
    fun cycleAccent(dir: Int = 1) {}
    fun toggleTabColors() {}
    fun openConsoleTabs() {}
    fun cycleHaptics(dir: Int = 1) {}
    fun openUsageAccess() {}
    fun toggleGestures() {}
    fun cycleIconPack(dir: Int = 1) {}
    fun cycleIconShape(dir: Int = 1) {}
    fun editContact() {}
    fun editRaAccount() {}
    fun openNetworkCheck() {}
    fun openDiagnostics() {}
}

/** Заголовок раздела настроек. */
@Composable
fun SectionHeader(text: String) {
    Text(
        text.uppercase(),
        color = Palette.accent,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 28.dp, top = 18.dp, bottom = 4.dp),
    )
}

/** Вкладка в списке настройки вкладок. */
data class TabItem(val id: String, val title: String)

/** Порядок и видимость вкладок консольного режима. */
@Composable
fun ConsoleTabsScreen(
    tabs: List<TabItem>,
    hidden: Set<String>,
    focusFirst: Boolean,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onMove: (String, Int) -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst) { if (focusFirst) runCatching { first.requestFocus() } }
    ScreenFrame(s.consoleTabs, onBack) {
        Column(Modifier.fillMaxSize()) {
            Text(s.consoleTabsHint, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(tabs, key = { it.id }) { t ->
                    val home = t.id == "home"
                    val shown = home || t.id !in hidden
                    val mod = (if (t == tabs.getOrNull(1) || (tabs.size == 1 && home)) Modifier.focusRequester(first) else Modifier)
                        .then(if (home) Modifier else Modifier.tabMoveKeys { onMove(t.id, it) })
                    FocusRow(mod, onClick = { if (!home) onToggle(t.id) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (shown) "●" else "○", color = if (shown) Palette.accent else Palette.dim, fontSize = 16.sp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(t.title, color = if (shown) Palette.text else Palette.dim, fontSize = 16.sp)
                                Text(
                                    if (home) s.tabAlwaysFirst else if (shown) s.tabShown else s.tabHidden,
                                    color = Palette.dim, fontSize = 12.sp,
                                )
                            }
                            if (!home) {
                                listOf("▲" to -1, "▼" to 1).forEach { (glyph, dir) ->
                                    Text(
                                        glyph, color = Palette.accent, fontSize = 18.sp,
                                        modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onMove(t.id, dir) }
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** L1/R1 или влево/вправо на строке вкладки — сдвинуть её вверх/вниз. */
private fun Modifier.tabMoveKeys(onMove: (Int) -> Unit): Modifier = onPreviewKeyEvent { e ->
    val dir = when (e.key) {
        Key.ButtonL1, Key.DirectionLeft -> -1
        Key.ButtonR1, Key.DirectionRight -> 1
        else -> 0
    }
    if (dir == 0) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) onMove(dir)
    true
}

@Composable
fun SettingsScreen(
    settings: Settings,
    version: String,
    focusFirst: Boolean,
    onBack: () -> Unit,
    a: SettingsActions,
    romsLabel: String? = null,
    usageAccess: Boolean = false,
    /** Название выбранного набора значков (null — обычные значки). */
    iconPackName: String? = null,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst) { if (focusFirst) runCatching { first.requestFocus() } }
    val appMode = LocalAppMode.current
    ScreenFrame(s.settingsTitle, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionHeader(s.secGeneral) }
            item {
                ChoiceRow(s.language, s.langName, Modifier.focusRequester(first)) { a.toggleLang() }
            }
            // В режиме приложения экран всегда консольный — выбирать нечего
            if (!appMode) item {
                ModeRow(settings.mode, a::setMode)
            }
            item {
                ChoiceRow(s.swapAB, if (settings.swapAB) s.on else s.off, desc = s.swapABDesc) { a.toggleSwap() }
            }
            item {
                FocusRow(onClick = a::openDefaultHome) { TitleAndDesc(s.setDefault, s.setDefaultDesc) }
            }

            item { SectionHeader(s.secLook) }
            item {
                ChoiceRow(s.theme, s.themeName(settings.theme)) { a.cycleTheme(it) }
            }
            item {
                ChoiceRow(
                    s.accent, s.accentName(settings.accent),
                    leading = {
                        Box(Modifier.padding(end = 12.dp).size(18.dp).clip(RoundedCornerShape(50)).background(Palette.accent))
                    },
                ) { a.cycleAccent(it) }
            }
            item {
                ChoiceRow(s.tabColors, if (settings.tabColors) s.on else s.off, desc = s.tabColorsDesc) { a.toggleTabColors() }
            }
            item {
                FocusRow(onClick = a::openConsoleTabs) { TitleAndDesc(s.consoleTabs, s.consoleTabsDesc) }
            }
            item {
                val target = com.retrohome.launcher.audio.LocalHapticTarget.current
                val motor = remember(settings.haptics) { target() }
                val where = when (motor) {
                    com.retrohome.launcher.audio.HapticTarget.GAMEPAD -> s.motorGamepad
                    com.retrohome.launcher.audio.HapticTarget.SYSTEM -> s.motorSystem
                    com.retrohome.launcher.audio.HapticTarget.NONE -> s.motorNone
                }
                ChoiceRow(s.haptics, s.hapticsName(settings.haptics), desc = s.hapticsDesc + "\n" + where) { a.cycleHaptics(it) }
            }

            item { SectionHeader(s.secSound) }
            item {
                ChoiceRow(s.sounds, s.packName(settings.sfxPack), desc = s.soundsDesc) { a.cycleSfx(it) }
            }
            item {
                ChoiceRow(s.sfxVolume, "${settings.sfxVolume}%") { a.cycleSfxVolume(it) }
            }
            item {
                ChoiceRow(s.music, s.trackName(settings.music), desc = s.musicDesc) { a.cycleMusic(it) }
            }
            item {
                ChoiceRow(s.musicVolume, "${settings.musicVolume}%") { a.cycleMusicVolume(it) }
            }
            item {
                FocusRow(onClick = a::pickMusic) { TitleAndDesc(s.pickMusic, s.pickMusicDesc) }
            }

            item { SectionHeader(s.secGames) }
            item {
                FocusRow(onClick = a::pickRoms) { TitleAndDesc(s.romsFolder, s.pickRomsDesc, romsLabel ?: s.notSelected) }
            }
            item {
                FocusRow(onClick = a::openFolders) { TitleAndDesc(s.folders, s.foldersDesc) }
            }
            item {
                FocusRow(onClick = a::openEmulators) { TitleAndDesc(s.emulators, s.emulatorsDesc) }
            }
            item {
                ChoiceRow(s.gamePage, if (settings.gamePage) s.on else s.off, desc = s.gamePageDesc) { a.toggleGamePage() }
            }
            item {
                ChoiceRow(s.sortGames, s.sortName(settings.sortGames), desc = s.sortGamesDesc) { a.cycleSortGames(it) }
            }
            item {
                FocusRow(onClick = a::editContact) {
                    TitleAndDesc(s.contactEmail, s.contactEmailDesc, settings.contactEmail ?: s.notSet)
                }
            }
            item {
                FocusRow(onClick = a::openNetworkCheck) { TitleAndDesc(s.net.title, s.net.desc) }
            }
            item {
                FocusRow(onClick = a::openDiagnostics) { TitleAndDesc(s.diag.title, s.diag.desc) }
            }
            item {
                FocusRow(onClick = a::editRaAccount) {
                    TitleAndDesc(s.ra.raAccount, s.ra.raAccountDesc, settings.raUser?.takeIf { settings.raKey != null } ?: s.notSet)
                }
            }
            item {
                FocusRow(onClick = a::openUsageAccess) {
                    TitleAndDesc(s.usageAccess, s.usageAccessDesc, if (usageAccess) s.usageOn else s.usageOff)
                }
            }
            item {
                ChoiceRow(s.autoMeta, if (settings.autoMeta) s.on else s.off, desc = s.autoMetaDesc) { a.toggleAutoMeta() }
            }
            item {
                FocusRow(onClick = a::openConsoleApps) { TitleAndDesc(s.consoleApps, s.consoleAppsDesc) }
            }

            item { SectionHeader(s.secPhone) }
            item {
                ChoiceRow(s.gestures, if (settings.gestures) s.on else s.off, desc = s.gesturesDesc) { a.toggleGestures() }
            }
            item {
                ChoiceRow(s.iconPack, iconPackName ?: s.iconPackSystem, desc = s.iconPackDesc) { a.cycleIconPack(it) }
            }
            item {
                ChoiceRow(s.shape.title, s.shape.name(settings.iconShape), desc = s.shape.desc) { a.cycleIconShape(it) }
            }
            item {
                ChoiceRow(s.autoAdd, if (settings.autoAddNew) s.on else s.off, desc = s.autoAddDesc) { a.toggleAutoAdd() }
            }
            item {
                var armed by remember { mutableStateOf(false) }
                FocusRow(onClick = {
                    if (armed) { a.sortHome(); armed = false } else armed = true
                }) {
                    TitleAndDesc(s.sortAbc, s.sortAbcDesc, if (armed) s.tapAgain else null)
                }
            }
            item {
                ChoiceRow("${s.grid}: ${s.columns}", settings.columns.toString()) { a.cycleColumns(it) }
            }
            item {
                ChoiceRow("${s.grid}: ${s.rows}", settings.rows.toString()) { a.cycleRows(it) }
            }
            item {
                FocusRow(onClick = a::openHiddenApps) { TitleAndDesc(s.hiddenApps, s.hiddenAppsDesc) }
            }
            item {
                Text(
                    "Retro Home · ${s.version} $version",
                    color = Palette.dim,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(24.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeRow(current: ModeOverride, onSelect: (ModeOverride) -> Unit) {
    val s = LocalStrings.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(s.mode, color = Palette.text, fontSize = 16.sp)
        Text(s.modeDesc, color = Palette.dim, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row {
            Box(Modifier.padding(end = 0.dp)) { Chip(s.modeAuto, current == ModeOverride.AUTO) { onSelect(ModeOverride.AUTO) } }
            Chip(s.modeConsole, current == ModeOverride.CONSOLE) { onSelect(ModeOverride.CONSOLE) }
            Chip(s.modePhone, current == ModeOverride.PHONE) { onSelect(ModeOverride.PHONE) }
        }
    }
}

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(
        Modifier
            .padding(start = 6.dp)
            .clip(RoundedCornerShape(50))
            .background(if (selected) Palette.accent else Palette.surfaceHi)
            .border(
                if (focused) BorderStroke(2.dp, Palette.text) else BorderStroke(0.dp, Color.Transparent),
                RoundedCornerShape(50),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (selected) Color.White else Palette.text, fontSize = 13.sp)
    }
}

@Composable
fun ConsoleAppsScreen(
    apps: List<AppInfo>,
    overrides: Map<String, ConsoleZone>,
    focusFirst: Boolean,
    onBack: () -> Unit,
    onSetZone: (String, ConsoleZone) -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst) { if (focusFirst) runCatching { first.requestFocus() } }
    ScreenFrame(s.consoleApps, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Text(s.consoleAppsDesc, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp))
            }
            items(apps, key = { it.key }) { app ->
                val zone = app.zone(overrides)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(Palette.surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.key, 36.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, color = Palette.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val det = app.emulator?.let { e ->
                            if (e.multi) e.name else e.systems.joinToString(", ") { it.short }
                        }
                        if (det != null) Text(det, color = Palette.dim, fontSize = 12.sp, maxLines = 1)
                    }
                    Row(horizontalArrangement = Arrangement.End) {
                        val firstMod = if (app == apps.firstOrNull()) Modifier.focusRequester(first) else Modifier
                        Box(firstMod) { Chip(s.zoneEmulator, zone == ConsoleZone.EMULATOR) { onSetZone(app.key, ConsoleZone.EMULATOR) } }
                        Chip(s.zoneGame, zone == ConsoleZone.GAME) { onSetZone(app.key, ConsoleZone.GAME) }
                        Chip(s.zoneNone, zone == ConsoleZone.NONE) { onSetZone(app.key, ConsoleZone.NONE) }
                    }
                }
            }
        }
    }
}

@Composable
fun HiddenAppsScreen(
    hidden: List<AppInfo>,
    focusFirst: Boolean,
    onBack: () -> Unit,
    onUnhide: (String) -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst, hidden.isEmpty()) { if (focusFirst && hidden.isNotEmpty()) runCatching { first.requestFocus() } }
    ScreenFrame(s.hiddenApps, onBack) {
        if (hidden.isEmpty()) {
            Text(s.noHiddenApps, color = Palette.dim, modifier = Modifier.padding(24.dp))
        }
        LazyColumn(Modifier.fillMaxSize()) {
            items(hidden, key = { it.key }) { app ->
                val mod = if (app == hidden.first()) Modifier.focusRequester(first) else Modifier
                FocusRow(mod, onClick = { onUnhide(app.key) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app.key, 36.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(app.label, color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        Text(s.show, color = Palette.accent, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(
    items: List<SearchItem>,
    onBack: () -> Unit,
    onLaunch: (String) -> Unit,
) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    val shown = remember(items, query) { filterByLabel(items, query) { it.label } }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    ScreenFrame(s.search, onBack) {
        Column(Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Palette.surface)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                if (query.isEmpty()) Text(s.searchHint, color = Palette.dim, fontSize = 15.sp)
                BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 15.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { shown.firstOrNull()?.let { onLaunch(it.key) } }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (shown.isEmpty()) Text(s.noResults, color = Palette.dim, modifier = Modifier.padding(24.dp))
            LazyColumn(Modifier.fillMaxSize()) {
                items(shown, key = { it.key }) { item ->
                    FocusRow(onClick = { onLaunch(item.key) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (item.system != null) SystemBadge(item.system, 36.dp) else AppIcon(item.key, 36.dp)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(item.label, color = Palette.text, fontSize = 15.sp)
                                if (item.subtitle != null) Text(item.subtitle, color = Palette.dim, fontSize = 12.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}


/** Строка поиска: приложение или игра. */
data class SearchItem(val key: String, val label: String, val subtitle: String? = null, val system: GameSystem? = null)

/** Поиск без учёта регистра; совпадение с начала названия и слова идёт первым. */
fun <T> filterByLabel(items: List<T>, query: String, label: (T) -> String): List<T> {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return items
    return items.filter { label(it).lowercase().contains(q) }.sortedBy { item ->
        val l = label(item).lowercase()
        when {
            l.startsWith(q) -> 0
            l.split(' ', '-', '.', ':').any { it.startsWith(q) } -> 1
            else -> 2
        }
    }
}

/** Квадратик с цветом и коротким именем консоли. */
@Composable
fun SystemBadge(system: GameSystem, size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(systemColor(system)),
        contentAlignment = Alignment.Center,
    ) {
        Text(system.short, color = Color.White, fontSize = (size.value * 0.28f).sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** Папки внутри ROMs: к какой консоли относится каждая. */
@Composable
fun FoldersScreen(
    folders: List<FolderInfo>,
    hasFolder: Boolean,
    scanning: Boolean,
    focusFirst: Boolean,
    onBack: () -> Unit,
    onPickRoms: () -> Unit,
    onFolder: (FolderInfo) -> Unit,
    romsLabel: String? = null,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst, folders.isEmpty()) { if (focusFirst) runCatching { first.requestFocus() } }
    // Сначала папки с консолью, затем нераспознанные
    val sorted = remember(folders) { folders.sortedWith(compareBy({ it.system == null }, { it.name.lowercase() })) }
    ScreenFrame(s.folders, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                FocusRow(Modifier.focusRequester(first), onClick = onPickRoms) {
                    TitleAndDesc(s.romsFolder, if (scanning) s.scanning else s.pickRomsDesc, if (hasFolder) romsLabel else s.notSelected)
                }
            }
            items(sorted, key = { it.name }) { f ->
                FocusRow(onClick = { onFolder(f) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (f.system != null) SystemBadge(f.system, 32.dp) else Box(Modifier.size(32.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = Palette.text, fontSize = 15.sp)
                            Text(
                                f.system?.title(s.lang) ?: s.notRecognized,
                                color = if (f.system != null) Palette.dim else Palette.dim.copy(alpha = 0.6f),
                                fontSize = 12.sp,
                            )
                        }
                        if (f.system != null) Text(s.gamesCount.format(f.gameCount), color = Palette.accent, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Выбор консоли для папки. value: null — авто, FOLDER_IGNORE — не использовать, иначе имя системы. */
@Composable
fun SystemPickerScreen(
    folder: FolderInfo,
    current: String?,
    onBack: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }
    val systems = remember { GameSystem.entries.filter { GameFiles.extensions.containsKey(it) } }
    ScreenFrame(s.chooseSystem.format(folder.name), onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                FocusRow(Modifier.focusRequester(first), onClick = { onSelect(null) }) {
                    TitleAndDesc(
                        s.auto + (folder.detected?.let { ": " + it.title(s.lang) } ?: ""),
                        value = if (current == null) "✓" else null,
                    )
                }
            }
            item {
                FocusRow(onClick = { onSelect(FOLDER_IGNORE) }) {
                    TitleAndDesc(s.folderIgnore, value = if (current == FOLDER_IGNORE) "✓" else null)
                }
            }
            items(systems, key = { it.name }) { sys ->
                FocusRow(onClick = { onSelect(sys.name) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SystemBadge(sys, 28.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(sys.title(s.lang), color = Palette.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        if (current == sys.name) Text("✓", color = Palette.accent, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/** Эмулятор для каждой консоли, в которой есть игры. Нажатие — следующий из установленных. */
@Composable
fun EmulatorsScreen(
    systems: List<GameSystem>,
    runner: (GameSystem) -> String?,
    optionsCount: (GameSystem) -> Int,
    focusFirst: Boolean,
    onBack: () -> Unit,
    onCycle: (GameSystem, Int) -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    LaunchedEffect(focusFirst, systems.isEmpty()) { if (focusFirst && systems.isNotEmpty()) runCatching { first.requestFocus() } }
    ScreenFrame(s.emulators, onBack) {
        if (systems.isEmpty()) Text(s.noGamesYet, color = Palette.dim, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            items(systems, key = { it.name }) { sys ->
                val mod = if (sys == systems.first()) Modifier.focusRequester(first) else Modifier
                val many = optionsCount(sys) > 1
                FocusRow(if (many) mod.dpadSteps { onCycle(sys, it) } else mod, onClick = { onCycle(sys, 1) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SystemBadge(sys, 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(sys.title(s.lang), color = Palette.text, fontSize = 15.sp)
                            if (many) Text(s.launchVia, color = Palette.dim, fontSize = 12.sp)
                        }
                        val name = runner(sys) ?: s.noRunner
                        if (many) ChoiceValue(name) { onCycle(sys, it) }
                        else Text(name, color = Palette.accent, fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

/** Ручное исправление названия игры: по нему ищутся обложка и описание. */
@Composable
fun TitleEditScreen(
    initial: String,
    fileName: String,
    hasOverride: Boolean,
    onBack: () -> Unit,
    onSave: (String?) -> Unit,
) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf(initial) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    ScreenFrame(s.fixTitle, onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            Text(s.fixTitleDesc, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Text(fileName, color = Palette.dim.copy(alpha = 0.7f), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(120) },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSave(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
            FocusRow(onClick = { onSave(text) }) { TitleAndDesc(s.save) }
            if (hasOverride) FocusRow(onClick = { onSave(null) }) { TitleAndDesc(s.resetTitle) }
        }
    }
}

/** Выбор виджета: виджеты сгруппированы по приложениям, у каждого — превью и размер в клетках. */
@Composable
fun WidgetPickerScreen(
    options: List<com.retrohome.launcher.data.WidgetOption>?,
    preview: (com.retrohome.launcher.data.WidgetOption) -> androidx.compose.ui.graphics.ImageBitmap?,
    onBack: () -> Unit,
    onPick: (com.retrohome.launcher.data.WidgetOption) -> Unit,
) {
    val s = LocalStrings.current
    ScreenFrame(s.widgetsTitle, onBack) {
        when {
            options == null -> {}
            options.isEmpty() -> Text(s.noWidgets, color = Palette.dim, modifier = Modifier.padding(24.dp))
            else -> LazyColumn(Modifier.fillMaxSize()) {
                options.groupBy { it.appLabel }.forEach { (app, list) ->
                    item(key = "h:$app") { SectionHeader(app) }
                    items(list, key = { it.provider }) { o ->
                        FocusRow(onClick = { onPick(o) }) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                var img by remember(o.provider) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                                LaunchedEffect(o.provider) {
                                    img = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { preview(o) }
                                }
                                Box(
                                    Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)).background(Palette.surfaceHi),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    img?.let { androidx.compose.foundation.Image(it, null, modifier = Modifier.fillMaxSize().padding(4.dp)) }
                                }
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(o.label, color = Palette.text, fontSize = 16.sp)
                                    Text("${o.spanX} × ${o.spanY}", color = Palette.dim, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Почта для связи в запросах к Википедии. */
@Composable
fun ContactEditScreen(initial: String?, onBack: () -> Unit, onSave: (String?) -> Unit) {
    val s = LocalStrings.current
    var text by remember { mutableStateOf(initial.orEmpty()) }
    val field = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    val valid = text.trim().let { it.contains('@') && it.substringAfter('@').contains('.') }
    ScreenFrame(s.contactEmail, onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
            Text(s.contactEmailLong, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.surface)
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (text.isEmpty()) Text("name@example.com", color = Palette.dim, fontSize = 17.sp)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(100).replace(" ", "") },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 17.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Email,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { if (valid) onSave(text) }),
                    modifier = Modifier.fillMaxWidth().focusRequester(field),
                )
            }
            FocusRow(onClick = { if (valid) onSave(text) }) {
                TitleAndDesc(s.save, if (!valid && text.isNotEmpty()) s.contactInvalid else null)
            }
            if (initial != null) FocusRow(onClick = { onSave(null) }) { TitleAndDesc(s.contactRemove) }
        }
    }
}

/** Аккаунт RetroAchievements: логин и ключ Web API, проверка входа. */
@Composable
fun RaAccountScreen(
    initialUser: String?,
    initialKey: String?,
    onBack: () -> Unit,
    onSave: suspend (String?, String?) -> Pair<String, Int>?,
) {
    val s = LocalStrings.current
    var user by remember { mutableStateOf(initialUser.orEmpty()) }
    var key by remember { mutableStateOf(initialKey.orEmpty()) }
    var result by remember { mutableStateOf<String?>(null) }
    var checking by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val userField = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { userField.requestFocus() } }
    val ready = user.isNotBlank() && key.isNotBlank()

    fun save() {
        if (!ready || checking) return
        checking = true
        result = s.ra.raChecking
        scope.launch {
            val ok = onSave(user, key)
            checking = false
            result = if (ok != null) s.ra.raOk.format(ok.first, ok.second) else s.ra.raFail
        }
    }

    @Composable
    fun Field(value: String, hint: String, label: String, modifier: Modifier, onChange: (String) -> Unit) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
            Text(label, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp, bottom = 2.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.surface)
                    .padding(horizontal = 18.dp, vertical = 12.dp)
            ) {
                if (value.isEmpty()) Text(hint, color = Palette.dim, fontSize = 16.sp)
                BasicTextField(
                    value = value,
                    onValueChange = { onChange(it.take(64).trim()) },
                    singleLine = true,
                    textStyle = TextStyle(color = Palette.text, fontSize = 16.sp),
                    cursorBrush = SolidColor(Palette.accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Ascii,
                        imeAction = ImeAction.Next,
                        autoCorrectEnabled = false,
                    ),
                    modifier = modifier.fillMaxWidth(),
                )
            }
        }
    }

    ScreenFrame(s.ra.raAccount, onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp).verticalScroll(rememberScrollState())) {
            Text(s.ra.raLong, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(6.dp))
            Field(user, "username", s.ra.raUserLabel, Modifier.focusRequester(userField)) { user = it; result = null }
            Field(key, "aBcD1234…", s.ra.raKeyLabel, Modifier) { key = it; result = null }
            FocusRow(onClick = ::save) {
                TitleAndDesc(s.ra.raCheck, result)
            }
            if (initialUser != null) FocusRow(onClick = {
                scope.launch { onSave(null, null); onBack() }
            }) { TitleAndDesc(s.ra.raRemove) }
        }
    }
}

/** Проверка соединения: каждый сайт — работает или почему нет, с советом. */
@Composable
fun NetworkCheckScreen(
    onBack: () -> Unit,
    check: suspend (onEach: (com.retrohome.launcher.data.NetworkCheck.Result) -> Unit) -> Unit,
) {
    val s = LocalStrings.current
    val results = remember { androidx.compose.runtime.mutableStateMapOf<String, com.retrohome.launcher.data.NetworkCheck.Result>() }
    var running by remember { mutableStateOf(false) }
    var round by remember { mutableStateOf(0) }
    LaunchedEffect(round) {
        results.clear()
        running = true
        try { check { r -> results[r.target.id] = r } } finally { running = false }
    }
    ScreenFrame(s.net.title, onBack) {
        Column(Modifier.fillMaxSize().padding(horizontal = 4.dp).verticalScroll(rememberScrollState())) {
            Text(s.net.intro, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            Spacer(Modifier.height(6.dp))
            com.retrohome.launcher.data.NetworkCheck.targets.forEach { t ->
                val r = results[t.id]
                val kind = r?.kind
                val ok = kind == com.retrohome.launcher.data.NetworkCheck.Kind.OK
                val desc = when {
                    r == null -> if (running) s.net.checking else s.net.waiting
                    ok -> s.net.ok.format(r.ms)
                    // Совет и техническая причина — по ней можно понять, что именно не так
                    else -> s.net.explain(kind!!) + "\n" + r.host + ": " + (r.safeError ?: "HTTP ${r.code}")
                }
                FocusRow(onClick = {}) {
                    TitleAndDesc(s.net.label(t.id), desc, if (r == null) "…" else if (ok) "✓" else "✗")
                }
            }
            if (!running && results.size == com.retrohome.launcher.data.NetworkCheck.targets.size &&
                results.values.all { it.kind == com.retrohome.launcher.data.NetworkCheck.Kind.OK }) {
                Text(s.net.allOk, color = Palette.accent, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            }
            FocusRow(onClick = { if (!running) round++ }) { TitleAndDesc(if (running) s.net.checking else s.net.again) }
        }
    }
}

/** Пометка в отчёте, что загрузка ещё идёт (тогда экран сам обновляет отчёт). */
const val DIAG_RUNNING = "Загрузка сейчас: идёт"

/**
 * Диагностика обложек и описаний: отчёт текстом, который можно отправить разработчику.
 * Отчёт разбит на абзацы-строки, чтобы его можно было листать и крестовиной.
 */
@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    report: () -> String,
    onShare: (String) -> Unit,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
) {
    val s = LocalStrings.current
    var round by remember { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val text = remember(round) { report() }
    // Пока идёт поиск, отчёт обновляется сам
    LaunchedEffect(round) {
        kotlinx.coroutines.delay(3000)
        if (DIAG_RUNNING in text) round++
    }
    // Абзацы, а длинные (журнал) — кусками по 12 строк: каждый кусок — строка списка, крестовина листает по ним
    val blocks = remember(text) {
        text.trimEnd().split("\n\n").flatMap { b -> b.lines().chunked(12).map { it.joinToString("\n") } }
    }
    ScreenFrame(s.diag.title, onBack) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Text(s.diag.intro, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) }
            item { FocusRow(onClick = { onShare(text) }) { TitleAndDesc(s.diag.share, s.diag.shareDesc) } }
            item { FocusRow(onClick = { onCopy(text); copied = true }) { TitleAndDesc(s.diag.copy, if (copied) s.diag.copied else null) } }
            item { FocusRow(onClick = { onRetry(); round++ }) { TitleAndDesc(s.diag.retry, s.diag.retryDesc) } }
            items(blocks.size) { i ->
                FocusRow(onClick = {}) {
                    Text(
                        blocks[i], color = Palette.text, fontSize = 12.sp, lineHeight = 16.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    )
                }
            }
        }
    }
}
