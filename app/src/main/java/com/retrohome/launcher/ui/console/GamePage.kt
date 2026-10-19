package com.retrohome.launcher.ui.console

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.offset
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.i18n.Strings
import com.retrohome.launcher.ui.PadGlyph
import com.retrohome.launcher.ui.Palette
import com.retrohome.launcher.ui.rememberCover
import com.retrohome.launcher.ui.systemColor

/** Кнопки страницы игры. */
sealed interface PageButton {
    data object Play : PageButton
    data class Disc(val index: Int) : PageButton
    data object Achievements : PageButton
    data object Favorite : PageButton
    data object More : PageButton
}

fun pageButtons(e: ConsoleEntry): List<PageButton> =
    listOf(PageButton.Play) + (1 until e.discCount).map { PageButton.Disc(it) } +
        listOfNotNull(PageButton.Achievements.takeIf { e.ra != null }) + listOf(PageButton.Favorite, PageButton.More)

/**
 * Подпись кнопки. «▶ Играть» — только у выбранного диска: «Диск 1, ▶ Играть · Диск 2, Диск 3».
 * У игры на одном диске кнопка «▶ Играть» всегда с подписью — это её единственный смысл.
 * Звезда у «Избранного» рисуется отдельно (у неё своя анимация).
 */
fun PageButton.label(s: Strings, e: ConsoleEntry, selected: Boolean): String {
    fun disc(n: Int) = if (selected) "▶  " + s.play + " · " + s.discShort.format(n) else s.discShort.format(n)
    return when (this) {
        PageButton.Play -> if (e.discCount > 1) disc(1) else "▶  " + s.play
        is PageButton.Disc -> disc(index + 1)
        PageButton.Achievements -> "🏆  " + s.ra.achievements + (e.ra?.let { "  ${it.percent}%" } ?: "")
        PageButton.Favorite -> if (e.favorite) s.inFavorites else s.toFavorites
        PageButton.More -> "⋯  " + s.more
    }
}

/** Цвет «в избранном». */
val FavGold = Color(0xFFFFC53D)
private val Gold = FavGold
private val GoldLight = Color(0xFFFFE08A)
private val OnGold = Color(0xFF3B2A00)

/** Строки «Год / Жанр / Разработчик / Издатель», только известные. */
fun pageFacts(s: Strings, e: ConsoleEntry): List<Pair<String, String>> = listOfNotNull(
    e.year?.let { s.year to it.toString() },
    e.genres.takeIf { it.isNotEmpty() }?.let { s.genre to it.joinToString(", ") },
    e.developers.takeIf { it.isNotEmpty() }?.let { s.developer to it.joinToString(", ") },
    e.publishers.takeIf { it.isNotEmpty() && it != e.developers }?.let { s.publisher to it.joinToString(", ") },
)

/** «Наиграно 3 ч 20 мин · Последний запуск: вчера» или «Ещё не запускали». */
fun playLine(s: Strings, e: ConsoleEntry, now: Long = System.currentTimeMillis()): String = when {
    e.playMs > 0 -> "${s.playtime}: ${s.duration(e.playMs)}" + if (e.lastPlayed > 0) "  ·  ${s.lastPlayed}: ${s.ago(e.lastPlayed, now)}" else ""
    e.lastPlayed > 0 -> "${s.lastPlayed}: ${s.ago(e.lastPlayed, now)}"
    else -> s.neverPlayed
}

/**
 * Страница игры: обложка, снимок экрана фоном, описание, факты, время в игре
 * и кнопки «Играть», выбор диска, избранное, «Ещё».
 */
@Composable
fun GamePage(
    e: ConsoleEntry,
    focused: Int,
    scroll: ScrollState,
    confirmGlyph: String,
    cancelGlyph: String,
    canSwitch: Boolean,
    onButton: (Int) -> Unit,
    onBack: () -> Unit,
    onOptions: () -> Unit,
    onFavorite: () -> Unit,
    onSwitch: (Int) -> Unit,
) {
    val s = LocalStrings.current
    val buttons = pageButtons(e)
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Palette.bgTop, Palette.bgBottom)))
            // Касания не проходят к экрану под страницей
            .clickable(remember { MutableInteractionSource() }, null) {}
    ) {
        // Фон: снимок из игры, а если его нет — сильно размытая обложка
        val snap = rememberCover(e.snap)
        val cover = rememberCover(e.cover)
        when {
            snap != null -> Image(snap, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().alpha(0.55f))
            cover != null -> Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().blur(40.dp).alpha(0.35f))
        }
        // Затемнение слева и снизу, чтобы текст читался на любом снимке
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Palette.bgBottom.copy(alpha = 0.97f), 0.55f to Palette.bgBottom.copy(alpha = 0.82f), 1f to Palette.bgBottom.copy(alpha = 0.35f))
            )
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Palette.bgBottom.copy(alpha = 0.9f))))

        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(start = 36.dp, end = 36.dp, top = 20.dp, bottom = 8.dp)) {
                val coverHeight = (maxHeight * 0.92f)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    // Обложка
                    Box(
                        Modifier
                            .height(coverHeight)
                            .aspectRatio(0.75f)
                            .shadow(18.dp, RoundedCornerShape(18.dp))
                            .clip(RoundedCornerShape(18.dp))
                            .background(e.system?.let { systemColor(it) } ?: Palette.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        else Text(
                            e.label, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Spacer(Modifier.width(32.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Text(e.subtitle, color = Palette.dim, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        // Длинные названия — чуть мельче, чтобы не съедали место описания
                        val long = e.label.length > 34
                        Text(
                            e.label, color = Palette.text, fontSize = if (long) 25.sp else 30.sp, fontWeight = FontWeight.Bold,
                            lineHeight = if (long) 29.sp else 34.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(6.dp))
                        // Время в игре, факты и описание прокручиваются вместе: листаешь описание —
                        // факты уезжают вверх и освобождают место тексту
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            Column(
                                Modifier.fillMaxSize().fadingEdges(scroll).verticalScroll(scroll).padding(end = 10.dp)
                            ) {
                                Text(playLine(s, e), color = Palette.accent, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                                e.ra?.let { AchievementsLine(it, Modifier.padding(top = 6.dp)) }
                                FactsGrid(pageFacts(s, e))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    e.description ?: s.noDescription,
                                    color = Palette.text.copy(alpha = 0.9f), fontSize = 14.sp, lineHeight = 20.sp,
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                            ScrollBar(scroll, Modifier.align(Alignment.CenterEnd))
                        }
                        if (e.description != null) {
                            Text(s.sourceName(e.descSource), color = Palette.dim, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                        Spacer(Modifier.height(10.dp))
                        val buttonsScroll = rememberLazyListState()
                        // Отступ от края — шире затухания ряда, чтобы выбранная кнопка не уходила в тень
                        val edgePx = with(LocalDensity.current) { 36.dp.roundToPx() }
                        // Прокручиваем ряд, только если выбранная кнопка не видна целиком, и ровно настолько,
                        // чтобы она поместилась (а не «в начало», обрезая соседей)
                        LaunchedEffect(focused, e.key) {
                            withFrameNanos { }
                            val info = buttonsScroll.layoutInfo
                            val item = info.visibleItemsInfo.firstOrNull { it.index == focused }
                            val start = info.viewportStartOffset + edgePx
                            val end = info.viewportEndOffset - edgePx
                            when {
                                focused == 0 -> buttonsScroll.animateScrollToItem(0)
                                item == null -> buttonsScroll.animateScrollToItem(focused.coerceAtLeast(0))
                                item.offset < start -> buttonsScroll.animateScrollBy((item.offset - start).toFloat())
                                item.offset + item.size > end -> buttonsScroll.animateScrollBy((item.offset + item.size - end).toFloat())
                            }
                        }
                        LazyRow(
                            state = buttonsScroll,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
                            modifier = Modifier.fadingSides(buttonsScroll),
                        ) {
                            itemsIndexed(buttons) { i, b ->
                                PageButtonView(b, e, selected = i == focused) { onButton(i) }
                            }
                        }
                    }
                }
            }
            // Подсказки кнопок
            Row(
                Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PageHint(confirmGlyph, s.select) { onButton(focused) }
                PageHint(cancelGlyph, s.hintBack, onBack)
                PageHint("X", s.hintOptions, onOptions)
                PageHint("Y", s.hintFavorite, onFavorite)
                Spacer(Modifier.weight(1f))
                if (canSwitch) {
                    PageHint("L1", "", { onSwitch(-1) })
                    PageHint("R1", s.otherGame, { onSwitch(1) })
                }
            }
        }
    }
}

@Composable
private fun PageHint(glyph: String, text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PadGlyph(glyph)
        if (text.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Text(text, color = Palette.text, fontSize = 13.sp)
        }
    }
}

/** Факты в две колонки: «Год · Жанр», «Разработчик · Издатель» — вдвое ниже, чем столбиком. */
@Composable
internal fun FactsGrid(facts: List<Pair<String, String>>) {
    if (facts.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        facts.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                row.forEach { (k, v) ->
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = Palette.dim)) { append("$k: ") }
                            append(v)
                        },
                        color = Palette.text, fontSize = 13.sp, lineHeight = 17.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** Края прокручиваемого текста плавно гаснут — видно, что выше или ниже есть ещё. */
internal fun Modifier.fadingEdges(scroll: ScrollState, edge: Dp = 22.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val h = size.height
        if (h <= 0f) return@drawWithContent
        val f = (edge.toPx() / h).coerceAtMost(0.4f)
        val top = scroll.value > 0
        val bottom = scroll.value < scroll.maxValue
        if (!top && !bottom) return@drawWithContent
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

/** Тонкая полоска справа: сколько текста и где мы сейчас. Только если есть что прокручивать. */
@Composable
private fun ScrollBar(scroll: ScrollState, modifier: Modifier) {
    if (scroll.maxValue <= 0 || scroll.maxValue == Int.MAX_VALUE) return
    BoxWithConstraints(modifier.fillMaxHeight().width(3.dp).padding(vertical = 4.dp)) {
        val track = constraints.maxHeight.toFloat()
        val content = track + scroll.maxValue
        val thumb = (track * track / content).coerceAtLeast(track * 0.12f)
        val offset = (track - thumb) * scroll.value / scroll.maxValue
        val density = LocalDensity.current
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(50)).background(Palette.text.copy(alpha = 0.08f)))
        Box(
            Modifier
                .offset { IntOffset(0, offset.toInt()) }
                .height(with(density) { thumb.toDp() })
                .fillMaxWidth()
                .clip(RoundedCornerShape(50))
                .background(Palette.text.copy(alpha = 0.35f))
        )
    }
}

/**
 * Кнопка страницы игры. Выбранная — залита цветом оформления и обведена; «Играть» переезжает вслед за выбором.
 * «Избранное» при добавлении заливается золотом слева направо, звезда подпрыгивает и от неё расходится кольцо.
 */
@Composable
private fun PageButtonView(b: PageButton, e: ConsoleEntry, selected: Boolean, onClick: () -> Unit) {
    val s = LocalStrings.current
    val shape = RoundedCornerShape(50)
    val fav = b == PageButton.Favorite && e.favorite
    // Анимации привязаны к игре: при переключении L1/R1 на другую игру — без «заливки» заново
    val fill = remember(e.key, b) { Animatable(if (fav) 1f else 0f) }
    val pop = remember(e.key, b) { Animatable(1f) }
    val burst = remember(e.key, b) { Animatable(1f) }
    LaunchedEffect(fav) {
        val target = if (fav) 1f else 0f
        if (fill.value == target) return@LaunchedEffect
        coroutineScope {
            launch { fill.animateTo(target, tween(if (fav) 520 else 280, easing = FastOutSlowInEasing)) }
            if (fav) {
                launch {
                    pop.animateTo(1.55f, tween(150, easing = FastOutSlowInEasing))
                    pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 380f))
                }
                launch { burst.snapTo(0f); burst.animateTo(1f, tween(650, easing = LinearOutSlowInEasing)) }
            }
        }
    }
    val bg by animateColorAsState(if (selected) Palette.accent else Palette.surface.copy(alpha = 0.85f), tween(160), label = "bg")
    val goldness = fill.value
    val baseText = if (selected) Color.White else Palette.text
    val textColor = lerp(baseText, OnGold, (goldness * 1.6f).coerceIn(0f, 1f))
    val scale by animateFloatAsState(if (selected) 1.06f else 1f, spring(dampingRatio = 0.6f, stiffness = 500f), label = "scale")
    Box(
        Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow((10f * goldness).dp, shape, clip = false, ambientColor = Gold, spotColor = Gold)
            .clip(shape)
            .background(bg)
            .drawBehind {
                if (goldness > 0f) {
                    val w = size.width * goldness
                    drawRect(Brush.horizontalGradient(0f to Gold, 1f to GoldLight, startX = 0f, endX = w.coerceAtLeast(1f)), size = Size(w, size.height))
                }
            }
            .border(
                if (selected) BorderStroke(3.dp, Color.White.copy(alpha = 0.95f)) else BorderStroke(0.dp, Color.Transparent),
                shape,
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (b == PageButton.Favorite) {
                Box(
                    Modifier
                        .drawBehind {
                            val t = burst.value
                            if (t < 1f) {
                                drawCircle(
                                    Gold.copy(alpha = (1f - t) * 0.9f),
                                    radius = size.minDimension * (0.5f + 1.6f * t),
                                    style = Stroke(width = 3.dp.toPx() * (1f - t) + 0.5f),
                                )
                            }
                        }
                        .graphicsLayer { scaleX = pop.value; scaleY = pop.value },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(if (fav) "★" else "☆", color = textColor, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                b.label(s, e, selected),
                color = textColor,
                fontSize = 15.sp,
                fontWeight = if (selected || fav) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

/** Ряд кнопок гаснет по краям, если за краем есть ещё кнопки. */
private fun Modifier.fadingSides(state: androidx.compose.foundation.lazy.LazyListState, edge: Dp = 28.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val left = state.canScrollBackward
        val right = state.canScrollForward
        if (!left && !right || size.width <= 0f) return@drawWithContent
        val f = (edge.toPx() / size.width).coerceAtMost(0.3f)
        drawRect(
            Brush.horizontalGradient(
                0f to (if (left) Color.Transparent else Color.Black),
                f to Color.Black,
                1f - f to Color.Black,
                1f to (if (right) Color.Transparent else Color.Black),
            ),
            blendMode = BlendMode.DstIn,
        )
    }
