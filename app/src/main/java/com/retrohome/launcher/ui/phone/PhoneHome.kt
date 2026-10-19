package com.retrohome.launcher.ui.phone

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.border
import com.retrohome.launcher.data.WidgetGestures
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.HomeItem
import com.retrohome.launcher.data.HomeLayout
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.ui.AppIcon
import com.retrohome.launcher.ui.LocalPadRegistry
import com.retrohome.launcher.ui.Pad
import com.retrohome.launcher.ui.Palette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

interface PhoneActions {
    fun launch(key: String)
    fun moveItem(id: Long, page: Int, x: Int, y: Int)
    fun removeItem(id: Long)
    fun addToHome(key: String, page: Int)
    fun hideInDrawer(key: String)
    fun appInfo(key: String)
    fun uninstall(key: String)
    fun renameFolder(id: Long, name: String)
    fun takeOutOfFolder(folderId: Long, key: String)
    fun addPage()
    fun deletePage(page: Int)
    fun openWallpaper()
    fun openSettings()
    fun openWidgets() {}
    fun resizeWidget(id: Long, w: Int, h: Int) {}
    fun openNotifications() {}
    fun setIconShape(id: String) {}
}

/** Как показать виджет; в приложении — настоящий виджет, в тестах — заглушка. */
interface WidgetRenderer {
    @Composable
    fun Widget(item: HomeItem, modifier: Modifier, gestures: WidgetGestures)
}

val LocalWidgetRenderer = staticCompositionLocalOf<WidgetRenderer> { PlaceholderWidgets }

/** Заглушка виджета: полупрозрачная плашка с именем; долгое нажатие и перетаскивание работают. */
object PlaceholderWidgets : WidgetRenderer {
    @Composable
    override fun Widget(item: HomeItem, modifier: Modifier, gestures: WidgetGestures) {
        var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
        Box(
            modifier
                .padding(6.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color.White.copy(alpha = 0.2f))
                .onGloballyPositioned { coords = it }
                .pointerInput(item.id) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p -> coords?.localToRoot(p)?.let { gestures.onLongPress(it.x, it.y) } },
                        onDrag = { change, _ -> change.consume(); coords?.localToRoot(change.position)?.let { gestures.onDrag(it.x, it.y) } },
                        onDragEnd = { gestures.onRelease() },
                        onDragCancel = { gestures.onRelease() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                item.widgetProvider?.substringAfterLast('.')?.substringAfterLast('/') ?: LocalStrings.current.widgets,
                style = labelStyle.copy(fontSize = 14.sp),
            )
        }
    }
}

data class Sheet(val title: String, val entries: List<Pair<String, () -> Unit>>)

class PhoneState {
    var drawerOpen by mutableStateOf(false)
    var openFolder by mutableStateOf<Long?>(null)
    var sheet by mutableStateOf<Sheet?>(null)
    var homeTick by mutableIntStateOf(0)
    /** Открытый экран рабочего стола — сюда добавляются виджеты. */
    var currentPage by mutableIntStateOf(0)
    /** Виджет, который переносят касанием (из меню «Переместить»). */
    var movingWidget by mutableStateOf<Long?>(null)

    /** Нажатие Home: закрыть открытое, а если ничего не открыто — на первый экран. */
    fun onHome() {
        if (drawerOpen || openFolder != null || sheet != null || movingWidget != null) {
            drawerOpen = false; openFolder = null; sheet = null; movingWidget = null
        } else {
            homeTick++
        }
    }
}

private val labelStyle = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    textAlign = TextAlign.Center,
    shadow = Shadow(Color.Black.copy(alpha = 0.8f), Offset(0f, 1f), 6f),
)

/** Состояние перетаскивания значка по рабочему столу. */
private class DragState {
    var item by mutableStateOf<HomeItem?>(null)
    var pointer by mutableStateOf(Offset.Zero)
    var travelled = 0f
    /** За какую клетку виджета взяли (от его левого верхнего угла). */
    var grabX = 0
    var grabY = 0
}

private sealed interface DropTarget {
    data object Remove : DropTarget
    data class Cell(val page: Int, val x: Int, val y: Int) : DropTarget
}

@Composable
fun PhoneHome(
    data: LauncherData,
    apps: Map<String, AppInfo>,
    drawerApps: List<AppInfo>,
    state: PhoneState,
    actions: PhoneActions,
    /** Свайп вниз — уведомления, свайп вверх — все приложения. */
    gestures: Boolean = true,
) {
    val s = LocalStrings.current
    val cols = data.settings.columns
    val rows = data.settings.rows
    val pageCount by rememberUpdatedState(data.pageCount)
    val pager = rememberPagerState { pageCount }
    val scope = rememberCoroutineScope()
    val drag = remember { DragState() }
    var gridRect by remember { mutableStateOf(Rect.Zero) }
    var dockRect by remember { mutableStateOf(Rect.Zero) }
    var removeRect by remember { mutableStateOf(Rect.Zero) }
    var rootWidth by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current

    LaunchedEffect(state.homeTick) { if (state.homeTick > 0) pager.animateScrollToPage(0) }
    LaunchedEffect(pager.currentPage) { state.currentPage = pager.currentPage }

    // Кнопка Start на геймпаде открывает настройки и в телефонном режиме
    val registry = LocalPadRegistry.current
    val padHandler: (Pad) -> Boolean = remember(actions) {
        { pad -> if (pad == Pad.START) { actions.openSettings(); true } else false }
    }
    DisposableEffect(registry, padHandler) {
        registry.register(padHandler)
        onDispose { registry.unregister(padHandler) }
    }

    BackHandler(state.drawerOpen || state.openFolder != null || state.sheet != null || state.movingWidget != null) {
        when {
            state.sheet != null -> state.sheet = null
            state.movingWidget != null -> state.movingWidget = null
            state.openFolder != null -> state.openFolder = null
            else -> state.drawerOpen = false
        }
    }

    fun targetAt(p: Offset): DropTarget? = when {
        removeRect.contains(p) -> DropTarget.Remove
        dockRect.contains(p) -> {
            val slot = ((p.x - dockRect.left) / (dockRect.width / HomeLayout.DOCK_SLOTS)).toInt()
            DropTarget.Cell(HomeItem.DOCK, slot.coerceIn(0, HomeLayout.DOCK_SLOTS - 1), 0)
        }
        gridRect.contains(p) -> {
            val cx = ((p.x - gridRect.left) / (gridRect.width / cols)).toInt().coerceIn(0, cols - 1)
            val cy = ((p.y - gridRect.top) / (gridRect.height / rows)).toInt().coerceIn(0, rows - 1)
            val w = drag.item?.takeIf { it.isWidget }
            if (w != null) {
                // Виджет: клетка под пальцем минус место, за которое его взяли
                DropTarget.Cell(
                    pager.currentPage,
                    (cx - drag.grabX).coerceIn(0, (cols - w.spanX).coerceAtLeast(0)),
                    (cy - drag.grabY).coerceIn(0, (rows - w.spanY).coerceAtLeast(0)),
                )
            } else DropTarget.Cell(pager.currentPage, cx, cy)
        }
        else -> null
    }

    fun sizeSheet(item: HomeItem) {
        val sizes = listOf(1 to 1, 2 to 1, 2 to 2, 3 to 2, 4 to 1, 4 to 2, 3 to 3, 4 to 3, 4 to 4)
            .filter { (w, h) -> w <= cols && h <= rows }
        state.sheet = Sheet(
            s.widgetSize,
            sizes.map { (w, h) ->
                val mark = if (w == item.spanX && h == item.spanY) "  •" else ""
                "$w × $h$mark" to { actions.resizeWidget(item.id, w, h) }
            },
        )
    }

    fun itemSheet(item: HomeItem) {
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (item.isWidget) {
            entries += s.moveWidget to { state.movingWidget = item.id }
            entries += s.widgetSize to { sizeSheet(item) }
            entries += s.removeWidget to { actions.removeItem(item.id) }
            state.sheet = Sheet(s.widgets, entries)
            return
        }
        if (item.isFolder) {
            entries += s.rename to { state.openFolder = item.id }
            entries += s.removeFromHome to { actions.removeItem(item.id) }
        } else {
            val key = item.app!!
            entries += s.removeFromHome to { actions.removeItem(item.id) }
            entries += s.appInfo to { actions.appInfo(key) }
            entries += s.uninstall to { actions.uninstall(key) }
        }
        state.sheet = Sheet(
            if (item.isFolder) item.folderName.ifBlank { s.folderDefault } else apps[item.app]?.label ?: "",
            entries,
        )
    }

    fun onDrop() {
        val item = drag.item ?: return
        val small = drag.travelled < with(density) { 10.dp.toPx() }
        drag.item = null
        if (small) {
            itemSheet(item)
            return
        }
        when (val t = targetAt(drag.pointer)) {
            DropTarget.Remove -> actions.removeItem(item.id)
            is DropTarget.Cell -> actions.moveItem(item.id, t.page, t.x, t.y)
            null -> {}
        }
    }

    // Перелистывание экранов, когда значок держат у края
    LaunchedEffect(drag.item) {
        if (drag.item == null) return@LaunchedEffect
        val edge = with(density) { 28.dp.toPx() }
        while (drag.item != null) {
            val p = drag.pointer
            val dir = when {
                p.x < edge -> -1
                rootWidth > 0 && p.x > rootWidth - edge -> 1
                else -> 0
            }
            if (dir != 0) {
                delay(450)
                val still = drag.pointer.x < edge || drag.pointer.x > rootWidth - edge
                if (drag.item != null && still) {
                    val next = pager.currentPage + dir
                    if (next >= pageCount && dir > 0) actions.addPage()
                    if (next >= 0) {
                        delay(50)
                        pager.animateScrollToPage(next.coerceAtMost(pager.pageCount - 1).coerceAtLeast(0))
                    }
                    delay(400)
                }
            } else {
                delay(80)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { rootWidth = it.size.width.toFloat() }
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            // Полоса «Убрать» видна только во время перетаскивания
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .onGloballyPositioned { removeRect = it.boundsInRoot() },
                contentAlignment = Alignment.Center,
            ) {
                val moving = state.movingWidget
                if (moving != null && drag.item == null) {
                    Text(
                        s.moveWidgetHint,
                        style = labelStyle.copy(fontSize = 14.sp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.45f))
                            .clickable { state.movingWidget = null }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    )
                } else if (drag.item == null) {
                    Text(
                        "⚙",
                        style = labelStyle.copy(fontSize = 22.sp),
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(end = 10.dp)
                            .clip(CircleShape)
                            .clickable { actions.openSettings() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                if (drag.item != null) {
                    val hot = removeRect.contains(drag.pointer)
                    Text(
                        "✕  ${s.dropToRemove}",
                        color = if (hot) Palette.accent else Color.White,
                        fontWeight = FontWeight.SemiBold,
                        style = labelStyle.copy(fontSize = 15.sp),
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .onGloballyPositioned { gridRect = it.boundsInRoot() }
                    .pointerInput(gestures) {
                        var acc = 0f
                        detectVerticalDragGestures(
                            onDragStart = { acc = 0f },
                            onVerticalDrag = { _, dy -> acc += dy },
                            onDragEnd = {
                                // Жесты можно выключить в настройках — тогда список открывается только ручкой ︿
                                if (gestures && acc < -120f) state.drawerOpen = true
                                if (gestures && acc > 120f) actions.openNotifications()
                            },
                        )
                    }
            ) {
                HorizontalPager(
                    state = pager,
                    userScrollEnabled = drag.item == null,
                    modifier = Modifier.fillMaxSize(),
                    beyondViewportPageCount = 1,
                ) { page ->
                    PageGrid(
                        page = page,
                        items = data.home.filter { it.page == page },
                        apps = apps,
                        cols = cols,
                        rows = rows,
                        drag = drag,
                        hoverTarget = drag.item?.let { targetAt(drag.pointer) },
                        onLaunch = actions::launch,
                        onOpenFolder = { state.openFolder = it },
                        onDragEnd = ::onDrop,
                        movingWidget = data.home.firstOrNull { it.id == state.movingWidget },
                        onPlaceWidget = { x, y ->
                            state.movingWidget?.let { id -> actions.moveItem(id, page, x, y) }
                            state.movingWidget = null
                        },
                        onWidgetLongPress = { item, x, y ->
                            drag.travelled = 0f
                            drag.pointer = Offset(x, y)
                            val cw = gridRect.width / cols
                            val ch = gridRect.height / rows
                            drag.grabX = ((x - gridRect.left) / cw).toInt() - item.x
                            drag.grabY = ((y - gridRect.top) / ch).toInt() - item.y
                            drag.item = item
                        },
                        onWidgetDrag = { x, y ->
                            val p = Offset(x, y)
                            drag.travelled += abs(p.x - drag.pointer.x) + abs(p.y - drag.pointer.y)
                            drag.pointer = p
                        },
                        onEmptyLongPress = {
                            val empty = data.home.none { it.page == page }
                            val entries = mutableListOf<Pair<String, () -> Unit>>(
                                s.widgets to { actions.openWidgets() },
                                s.wallpaper to { actions.openWallpaper() },
                                s.addPage to { actions.addPage() },
                            )
                            if (empty && data.pageCount > 1) entries += s.deletePage to { actions.deletePage(page) }
                            entries += "${s.shape.title}: ${s.shape.name(data.settings.iconShape)}" to {
                                state.sheet = Sheet(
                                    s.shape.title,
                                    com.retrohome.launcher.data.IconShapes.ids.map { id ->
                                        val mark = if (id == data.settings.iconShape) "  •" else ""
                                        (s.shape.name(id) + mark) to { actions.setIconShape(id) }
                                    },
                                )
                            }
                            entries += s.settings to { actions.openSettings() }
                            state.sheet = Sheet("", entries)
                        },
                    )
                }
            }
            PageDots(pager, data.pageCount)
            // Ручка списка приложений
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(22.dp)
                    .clickable(remember { MutableInteractionSource() }, null) { state.drawerOpen = true },
                contentAlignment = Alignment.Center,
            ) {
                Text("︿", style = labelStyle.copy(fontSize = 16.sp))
            }
            Dock(
                items = data.home.filter { it.isDock },
                apps = apps,
                drag = drag,
                hover = drag.item?.let { targetAt(drag.pointer) as? DropTarget.Cell }?.takeIf { it.page == HomeItem.DOCK },
                onPositioned = { dockRect = it },
                onLaunch = actions::launch,
                onOpenFolder = { state.openFolder = it },
                onDragEnd = ::onDrop,
            )
        }

        // Значок (или контур виджета) под пальцем во время перетаскивания
        drag.item?.takeIf { it.isWidget }?.let { item ->
            val cw = gridRect.width / cols
            val ch = gridRect.height / rows
            val w = with(density) { (cw * item.spanX).toDp() }
            val h = with(density) { (ch * item.spanY).toDp() }
            Box(
                Modifier
                    .offset {
                        IntOffset(
                            (drag.pointer.x - cw * (drag.grabX + 0.5f)).roundToInt(),
                            (drag.pointer.y - ch * (drag.grabY + 0.5f)).roundToInt(),
                        )
                    }
                    .size(w, h)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.25f))
                    .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(20.dp))
            )
        }
        drag.item?.takeIf { !it.isWidget }?.let { item ->
            val size = 56.dp
            val half = with(density) { (size / 2).toPx() }
            Box(
                Modifier.offset {
                    IntOffset((drag.pointer.x - half).roundToInt(), (drag.pointer.y - half * 1.6f).roundToInt())
                }
            ) {
                ItemIcon(item, size)
            }
        }

        AnimatedVisibility(
            state.drawerOpen,
            enter = slideInVertically { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut(),
        ) {
            AppDrawer(
                apps = drawerApps,
                columns = cols,
                onClose = { state.drawerOpen = false },
                onSettings = { state.drawerOpen = false; actions.openSettings() },
                onLaunch = { state.drawerOpen = false; actions.launch(it) },
                onLongPress = { app ->
                    state.sheet = Sheet(
                        app.label,
                        listOf(
                            s.addToHome to { actions.addToHome(app.key, pager.currentPage) },
                            s.hideFromDrawer to { actions.hideInDrawer(app.key) },
                            s.appInfo to { actions.appInfo(app.key) },
                            s.uninstall to { actions.uninstall(app.key) },
                        ),
                    )
                },
            )
        }

        val folder = state.openFolder?.let { id -> data.home.firstOrNull { it.id == id && it.isFolder } }
        if (folder != null) {
            FolderPanel(
                folder = folder,
                apps = apps,
                onClose = { state.openFolder = null },
                onLaunch = { state.openFolder = null; actions.launch(it) },
                onRename = { actions.renameFolder(folder.id, it) },
                onLongPress = { key ->
                    state.sheet = Sheet(
                        apps[key]?.label ?: "",
                        listOf(
                            s.takeOut to { actions.takeOutOfFolder(folder.id, key) },
                            s.appInfo to { actions.appInfo(key) },
                        ),
                    )
                },
            )
        } else if (state.openFolder != null) {
            state.openFolder = null
        }

        state.sheet?.let { sheet ->
            ActionSheet(sheet, onDismiss = { state.sheet = null })
        }
    }
}

@Composable
private fun PageGrid(
    page: Int,
    items: List<HomeItem>,
    apps: Map<String, AppInfo>,
    cols: Int,
    rows: Int,
    drag: DragState,
    hoverTarget: DropTarget?,
    onLaunch: (String) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onDragEnd: () -> Unit,
    movingWidget: HomeItem? = null,
    onPlaceWidget: (Int, Int) -> Unit = { _, _ -> },
    onWidgetLongPress: (HomeItem, Float, Float) -> Unit = { _, _, _ -> },
    onWidgetDrag: (Float, Float) -> Unit = { _, _ -> },
    onEmptyLongPress: () -> Unit,
) {
    val renderer = LocalWidgetRenderer.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val cellW = maxWidth / cols
        val cellH = maxHeight / rows
        val cellWpx = constraints.maxWidth.toFloat() / cols
        val cellHpx = constraints.maxHeight.toFloat() / rows
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(items, cols, rows) {
                    detectTapGestures(onLongPress = { p ->
                        val x = (p.x / cellWpx).toInt()
                        val y = (p.y / cellHpx).toInt()
                        if (items.none { it.covers(x, y) }) onEmptyLongPress()
                    })
                }
        )
        val hover = hoverTarget as? DropTarget.Cell
        if (hover != null && hover.page == page) {
            val dw = drag.item?.takeIf { it.isWidget }
            Box(
                Modifier
                    .offset(cellW * hover.x, cellH * hover.y)
                    .size(cellW * (dw?.spanX ?: 1), cellH * (dw?.spanY ?: 1))
                    .padding(4.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.18f))
            )
        }
        for (item in items) {
            if (item.x >= cols || item.y >= rows) continue
            if (item.isWidget) {
                key(item.id) {
                    val dragging = drag.item?.id == item.id
                    val gestures = remember(item) {
                        object : WidgetGestures {
                            override fun onLongPress(x: Float, y: Float) = onWidgetLongPress(item, x, y)
                            override fun onDrag(x: Float, y: Float) = onWidgetDrag(x, y)
                            override fun onRelease() = onDragEnd()
                        }
                    }
                    renderer.Widget(
                        item,
                        Modifier
                            .offset(cellW * item.x, cellH * item.y)
                            .size(cellW * item.spanX.coerceAtMost(cols - item.x), cellH * item.spanY.coerceAtMost(rows - item.y))
                            .alpha(if (dragging) 0.35f else 1f),
                        gestures,
                    )
                }
                continue
            }
            key(item.id) { Box(
                Modifier
                    .offset(cellW * item.x, cellH * item.y)
                    .size(cellW, cellH)
            ) {
                HomeCell(item, apps, drag, onLaunch, onOpenFolder, onDragEnd, showLabel = true)
            } }
        }
        // Режим «Переместить виджет»: касание клетки ставит туда левый верхний угол виджета
        if (movingWidget != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.12f))
                    .pointerInput(movingWidget.id, cols, rows) {
                        detectTapGestures { p ->
                            val x = (p.x / cellWpx).toInt().coerceIn(0, (cols - movingWidget.spanX).coerceAtLeast(0))
                            val y = (p.y / cellHpx).toInt().coerceIn(0, (rows - movingWidget.spanY).coerceAtLeast(0))
                            onPlaceWidget(x, y)
                        }
                    }
            )
            if (movingWidget.page == page) {
                Box(
                    Modifier
                        .offset(cellW * movingWidget.x, cellH * movingWidget.y)
                        .size(cellW * movingWidget.spanX, cellH * movingWidget.spanY)
                        .padding(4.dp)
                        .border(2.dp, Color.White, RoundedCornerShape(20.dp))
                )
            }
        }
    }
}

@Composable
private fun HomeCell(
    item: HomeItem,
    apps: Map<String, AppInfo>,
    drag: DragState,
    onLaunch: (String) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onDragEnd: () -> Unit,
    showLabel: Boolean,
) {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val dragging = drag.item?.id == item.id
    Column(
        Modifier
            .fillMaxSize()
            .alpha(if (dragging) 0.25f else 1f)
            .onGloballyPositioned { coords = it }
            .pointerInput(item) {
                detectTapGestures(onTap = {
                    if (item.isFolder) onOpenFolder(item.id) else onLaunch(item.app!!)
                })
            }
            .pointerInput(item) {
                detectDragGesturesAfterLongPress(
                    onDragStart = { local ->
                        drag.travelled = 0f
                        drag.pointer = coords?.localToRoot(local) ?: local
                        drag.item = item
                    },
                    onDrag = { change, amount ->
                        change.consume()
                        drag.travelled += abs(amount.x) + abs(amount.y)
                        drag.pointer += amount
                    },
                    onDragEnd = onDragEnd,
                    onDragCancel = onDragEnd,
                )
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        ItemIcon(item, 52.dp)
        if (showLabel) {
            Spacer(Modifier.height(4.dp))
            val label = if (item.isFolder) item.folderName else apps[item.app]?.label ?: ""
            Text(
                label,
                style = labelStyle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun ItemIcon(item: HomeItem, size: Dp) {
    if (!item.isFolder) {
        AppIcon(item.app!!, size)
        return
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.28f))
            .background(Color.White.copy(alpha = 0.22f))
            .padding(size * 0.12f)
    ) {
        val mini = size * 0.34f
        item.folderApps.take(4).forEachIndexed { i, key ->
            Box(Modifier.offset(x = (mini + size * 0.04f) * (i % 2), y = (mini + size * 0.04f) * (i / 2))) {
                AppIcon(key, mini)
            }
        }
    }
}

@Composable
private fun PageDots(pager: PagerState, count: Int) {
    Row(
        Modifier.fillMaxWidth().height(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { i ->
            Box(
                Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (i == pager.currentPage) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.45f))
            )
        }
    }
}

@Composable
private fun Dock(
    items: List<HomeItem>,
    apps: Map<String, AppInfo>,
    drag: DragState,
    hover: DropTarget.Cell?,
    onPositioned: (Rect) -> Unit,
    onLaunch: (String) -> Unit,
    onOpenFolder: (Long) -> Unit,
    onDragEnd: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .height(76.dp)
            .clip(RoundedCornerShape(26.dp))
            .background(Color.White.copy(alpha = 0.14f))
            .onGloballyPositioned { onPositioned(it.boundsInRoot()) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (slot in 0 until HomeLayout.DOCK_SLOTS) {
            val item = items.firstOrNull { it.x == slot }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .then(
                        if (hover?.x == slot) Modifier.clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.18f))
                        else Modifier
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item != null) key(item.id) { HomeCell(item, apps, drag, onLaunch, onOpenFolder, onDragEnd, showLabel = false) }
            }
        }
    }
}

@Composable
fun ActionSheet(sheet: Sheet, onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(Palette.surface)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .navigationBarsPadding()
                .padding(vertical = 12.dp)
        ) {
            if (sheet.title.isNotBlank()) {
                Text(
                    sheet.title,
                    color = Palette.text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            for ((title, action) in sheet.entries) {
                Text(
                    title,
                    color = Palette.text,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onDismiss(); action() }
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                )
            }
            Spacer(Modifier.width(1.dp).height(4.dp))
        }
    }
}
