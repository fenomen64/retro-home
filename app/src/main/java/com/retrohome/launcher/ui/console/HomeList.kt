package com.retrohome.launcher.ui.console

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.ui.AppIcon
import com.retrohome.launcher.ui.Palette
import com.retrohome.launcher.ui.rememberCover
import com.retrohome.launcher.ui.systemColor

/** Звезда избранного на обложке: крупная, всегда золотая, с тенью — видна на любой картинке. */
@Composable
fun FavoriteStar(modifier: Modifier = Modifier, size: TextUnit = 22.sp) {
    Text(
        "★",
        modifier = modifier,
        style = TextStyle(
            color = FavGold,
            fontSize = size,
            fontWeight = FontWeight.Bold,
            shadow = Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), blurRadius = 6f),
        ),
    )
}

/**
 * Главная вкладка консольного режима: слева «Последняя игра» и избранное столбиком
 * (листается вверх/вниз), справа — сведения о выбранном: время в игре, жанр, описание.
 */
@Composable
internal fun HomeList(
    tab: ConsoleTab,
    state: ConsoleState,
    onTap: (row: Int, index: Int) -> Unit,
    onLongPress: (row: Int, index: Int) -> Unit,
) {
    val recentRow = tab.rows.getOrNull(0)
    val favRow = tab.rows.getOrNull(1)
    val row = (state.rowSel[state.tabId] ?: 0).coerceIn(0, (tab.rows.size - 1).coerceAtLeast(0))
    val favSel = (state.itemSel["${state.tabId}:1"] ?: 0).coerceIn(0, ((favRow?.entries?.size ?: 1) - 1).coerceAtLeast(0))
    val selected: ConsoleEntry? = if (row == 0) recentRow?.entries?.firstOrNull() else favRow?.entries?.getOrNull(favSel)

    Row(Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, top = 4.dp, bottom = 6.dp)) {
        Column(Modifier.weight(0.46f).fillMaxHeight()) {
            if (recentRow != null) {
                SectionTitle(recentRow.title ?: "", active = row == 0)
                val e = recentRow.entries.firstOrNull()
                if (e == null) EmptyBox(recentRow.emptyText, Modifier.fillMaxWidth().height(84.dp))
                else RecentCard(e, selected = row == 0, onTap = { onTap(0, 0) }, onLongPress = { onLongPress(0, 0) })
                Spacer(Modifier.height(8.dp))
            }
            if (favRow != null) {
                val count = favRow.entries.size.takeIf { it > 0 }?.let { "  ·  $it" } ?: ""
                SectionTitle((favRow.title ?: "") + count, active = row == 1)
                if (favRow.entries.isEmpty()) EmptyBox(favRow.emptyText, Modifier.fillMaxWidth().weight(1f))
                else FavoritesColumn(
                    favRow.entries, if (row == 1) favSel else -1, favSel, Modifier.weight(1f),
                    onTap = { onTap(1, it) }, onLongPress = { onLongPress(1, it) },
                )
            }
        }
        Spacer(Modifier.width(20.dp))
        InfoPanel(selected, Modifier.weight(0.54f).fillMaxHeight())
    }
}

@Composable
private fun SectionTitle(text: String, active: Boolean) {
    Text(
        text,
        color = if (active) Palette.text else Palette.dim,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun EmptyBox(text: String, modifier: Modifier) {
    Box(
        modifier.clip(RoundedCornerShape(18.dp)).background(Palette.surface.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Palette.dim, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
    }
}

/** Цвет и рамка выбранного пункта. */
@Composable
private fun selectionModifier(selected: Boolean, shape: RoundedCornerShape): Modifier {
    val hl = highlight()
    val bg by animateColorAsState(
        if (selected) lerpColor(Palette.surface, hl, 0.28f) else Palette.surface.copy(alpha = 0.55f), tween(160), label = "sel",
    )
    return Modifier
        .clip(shape)
        .background(bg)
        .then(if (selected) Modifier.border(2.5.dp, hl, shape) else Modifier)
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t)

/** Последняя игра: обложка, название, консоль, время в игре и когда запускали. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RecentCard(e: ConsoleEntry, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(18.dp)
    val scale by animateFloatAsState(if (selected) 1.02f else 1f, label = "recentScale")
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .scale(scale)
            .then(selectionModifier(selected, shape))
            .combinedClickable(onClick = onTap, onLongClick = onLongPress)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (e.kind) {
            EntryKind.ACTION -> ActionFace(e)
            else -> {
                Thumb(e, Modifier.fillMaxHeight())
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.label, color = Palette.text, fontSize = 16.sp, fontWeight = FontWeight.Bold, lineHeight = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    // «GBA · Последний запуск: сегодня» — одной строкой, чтобы осталось место достижениям
                    val last = if (e.lastPlayed > 0) "  ·  ${s.lastPlayed}: ${s.ago(e.lastPlayed, System.currentTimeMillis())}" else ""
                    Text((e.system?.short ?: e.subtitle) + last, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    if (e.playMs > 0) Text("${s.playtime}: ${s.duration(e.playMs)}", color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    e.ra?.let { AchievementsLine(it, Modifier.padding(top = 3.dp), compact = true) }
                }
            }
        }
    }
}

/** Маленькая обложка игры (3:4) или значок приложения. */
@Composable
private fun Thumb(e: ConsoleEntry, modifier: Modifier, iconSize: Dp = 40.dp) {
    if (e.kind != EntryKind.GAME) {
        Box(modifier.aspectRatio(0.75f), contentAlignment = Alignment.Center) { AppIcon(e.key, iconSize) }
        return
    }
    val c = e.system?.let { systemColor(it) } ?: Palette.surfaceHi
    Box(
        modifier
            .aspectRatio(0.75f)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.verticalGradient(listOf(c, c.copy(red = c.red * 0.45f, green = c.green * 0.45f, blue = c.blue * 0.45f)))),
        contentAlignment = Alignment.Center,
    ) {
        val cover = rememberCover(e.cover)
        if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        else Text(e.system?.short ?: "", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Избранное столбиком: обложка, название, консоль и наигранное время. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FavoritesColumn(
    entries: List<ConsoleEntry>,
    selected: Int,
    remembered: Int,
    modifier: Modifier,
    onTap: (Int) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val s = LocalStrings.current
    val listState = rememberLazyListState()
    val fadePx = with(LocalDensity.current) { FAV_FADE.roundToPx() }
    // Выбранный пункт всегда виден целиком и не под затуханием края. Сдвиг считается по тому,
    // где пункт стоит сейчас (даже если список ещё едет от прошлого нажатия), — ровно на сколько нужно
    LaunchedEffect(remembered, entries.size) {
        if (remembered !in entries.indices) return@LaunchedEffect
        withFrameNanos { }
        fun current() = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == remembered }
        if (current() == null) {
            // Пункт далеко (например, вернулись на вкладку) — сначала прыжком к нему
            listState.scrollToItem(remembered)
            withFrameNanos { }
        }
        val info = listState.layoutInfo
        val item = current() ?: return@LaunchedEffect
        val top = info.viewportStartOffset + (if (remembered > 0) fadePx else 0)
        val bottom = info.viewportEndOffset - (if (remembered < entries.lastIndex) fadePx else 0)
        val delta = when {
            item.offset < top -> (item.offset - top).toFloat()
            item.offset + item.size > bottom -> (item.offset + item.size - bottom).toFloat()
            else -> 0f
        }
        when {
            // Крайние пункты — до упора, чтобы край списка не гас поверх них
            delta != 0f && remembered == 0 -> listState.animateScrollToItem(0)
            delta != 0f && remembered == entries.lastIndex -> listState.animateScrollBy(delta + item.size)
            delta != 0f -> listState.animateScrollBy(delta)
        }
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth().fadingVertical(listState).testTag("favList"),
        verticalArrangement = Arrangement.spacedBy(5.dp),
        contentPadding = PaddingValues(vertical = 3.dp, horizontal = 3.dp),
    ) {
        itemsIndexed(entries, key = { _, e -> e.key }) { i, e ->
            val sel = i == selected
            val shape = RoundedCornerShape(14.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .testTag("fav:$i")
                    .then(selectionModifier(sel, shape))
                    .combinedClickable(onClick = { onTap(i) }, onLongClick = { onLongPress(i) })
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Thumb(e, Modifier.fillMaxHeight(), iconSize = 34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        e.label, color = Palette.text, fontSize = 15.sp,
                        fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (e.kind == EntryKind.GAME) e.system?.short ?: "" else e.subtitle,
                        color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (e.playMs > 0) {
                    Text("⏱ " + s.duration(e.playMs), color = Palette.dim, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
                }
            }
        }
    }
}

/** Список гаснет сверху/снизу, если там есть ещё пункты. */
/** Затухание краёв списка избранного (выбранный пункт под него не заезжает). */
private val FAV_FADE = 14.dp

private fun Modifier.fadingVertical(state: androidx.compose.foundation.lazy.LazyListState, edge: Dp = FAV_FADE): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val top = state.canScrollBackward
        val bottom = state.canScrollForward
        if (!top && !bottom || size.height <= 0f) return@drawWithContent
        val f = (edge.toPx() / size.height).coerceAtMost(0.3f)
        drawRect(
            Brush.verticalGradient(
                0f to (if (top) Color.Transparent else Color.Black),
                f to Color.Black,
                1f - f to Color.Black,
                1f to (if (bottom) Color.Transparent else Color.Black),
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/** Сведения о выбранном: для игры — как на её странице, для приложения — значок и подпись. */
@Composable
private fun InfoPanel(e: ConsoleEntry?, modifier: Modifier) {
    val s = LocalStrings.current
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.surface.copy(alpha = 0.55f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        if (e == null) return@Box
        // Новая прокрутка для каждого пункта
        key(e.key) {
            when (e.kind) {
                EntryKind.GAME -> Column(Modifier.fillMaxSize()) {
                    Text(e.subtitle, color = Palette.dim, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(e.label, color = Palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, lineHeight = 25.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    val scroll = rememberScrollState()
                    Column(Modifier.weight(1f).fillMaxWidth().fadingEdges(scroll).verticalScroll(scroll)) {
                        Text(playLine(s, e), color = Palette.accent, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                        e.ra?.let { AchievementsLine(it, Modifier.padding(top = 5.dp)) }
                        FactsGrid(pageFacts(s, e))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            e.description ?: s.noDescription,
                            color = Palette.text.copy(alpha = 0.88f), fontSize = 13.sp, lineHeight = 18.sp,
                        )
                    }
                }
                EntryKind.APP -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    AppIcon(e.key, 72.dp)
                    Spacer(Modifier.height(12.dp))
                    Text(e.label, color = Palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(e.subtitle, color = Palette.dim, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                EntryKind.ACTION -> Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
                    Text(e.label, color = Palette.text, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text(e.subtitle, color = Palette.dim, fontSize = 14.sp, lineHeight = 19.sp)
                }
            }
        }
    }
}
