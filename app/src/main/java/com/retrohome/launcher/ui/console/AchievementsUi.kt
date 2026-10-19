package com.retrohome.launcher.ui.console

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.retrohome.launcher.data.Ra
import com.retrohome.launcher.data.RaStatus
import com.retrohome.launcher.data.RaSummary
import com.retrohome.launcher.i18n.LocalStrings
import com.retrohome.launcher.ui.PadGlyph
import com.retrohome.launcher.ui.Palette
import com.retrohome.launcher.ui.rememberRemoteImage

/** Полоса прогресса: золотая, когда открыто всё. */
@Composable
fun AchievementsBar(ra: RaSummary, modifier: Modifier = Modifier, height: Dp = 6.dp) {
    val target = if (ra.total > 0) ra.earned.toFloat() / ra.total else 0f
    val p by animateFloatAsState(target.coerceIn(0f, 1f), tween(500), label = "achBar")
    val color = if (ra.complete) FavGold else Palette.accent
    Box(modifier.height(height).clip(RoundedCornerShape(50)).background(Palette.text.copy(alpha = 0.12f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(p).clip(RoundedCornerShape(50)).background(color))
    }
}

/** «🏆 Достижения: 12 из 40 · 30%» с полосой под ней. */
@Composable
fun AchievementsLine(ra: RaSummary, modifier: Modifier = Modifier, compact: Boolean = false) {
    val s = LocalStrings.current
    Column(modifier) {
        val text = if (ra.complete) "🏆 ${s.ra.achComplete}  ·  ${ra.total}"
        else "🏆 ${s.ra.achievements}: " + s.ra.achProgress.format(ra.earned, ra.total, ra.percent)
        Text(
            text,
            color = if (ra.complete) FavGold else Palette.text,
            fontSize = if (compact) 12.sp else 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        AchievementsBar(ra, Modifier.fillMaxWidth(if (compact) 0.9f else 0.7f), height = if (compact) 4.dp else 6.dp)
    }
}

private val grayscale = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Значок достижения: цветной — открыто, серый и бледный — ещё нет. */
@Composable
private fun Badge(a: Ra.Achievement, size: Dp) {
    val img = rememberRemoteImage(a.badge.takeIf { it.isNotBlank() }?.let(Ra::badgeUrl))
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(Palette.surfaceHi),
        contentAlignment = Alignment.Center,
    ) {
        if (img != null) {
            Image(
                img, null, contentScale = ContentScale.Crop,
                colorFilter = if (a.unlocked) null else grayscale,
                modifier = Modifier.fillMaxSize().alpha(if (a.unlocked) 1f else 0.45f),
            )
        } else Text(if (a.unlocked) "🏆" else "🔒", fontSize = (size.value * 0.4f).sp)
    }
}

/** «2024-03-01 12:30:00» → «1 мар. 2024» (через общий формат «когда»). */
private fun parseDate(d: String): Long? = runCatching {
    val f = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
    f.parse(d.take(19).replace('T', ' '))?.time
}.getOrNull()

/**
 * Все достижения игры: прогресс сверху, ниже список со значками.
 * Y — показать только ещё не открытые.
 */
@Composable
fun AchievementsPanel(
    e: ConsoleEntry,
    detail: Ra.GameProgress?,
    loading: Boolean,
    status: RaStatus,
    lockedOnly: Boolean,
    list: LazyListState,
    cancelGlyph: String,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    Column(
        modifier
            .fillMaxWidth(0.88f)
            .fillMaxHeight(0.9f)
            .clip(RoundedCornerShape(22.dp))
            .background(Palette.surface)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🏆", fontSize = 22.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(s.ra.achievements, color = Palette.dim, fontSize = 12.sp)
                Text(e.label, color = Palette.text, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val ra = detail?.let { RaSummary(it.gameId, it.total, it.earned, it.earnedHardcore) } ?: e.ra
            if (ra != null) {
                Text(
                    s.ra.achProgress.format(ra.earned, ra.total, ra.percent),
                    color = if (ra.complete) FavGold else Palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                )
            }
        }
        val ra = detail?.let { RaSummary(it.gameId, it.total, it.earned, it.earnedHardcore) } ?: e.ra
        if (ra != null) {
            Spacer(Modifier.height(8.dp))
            AchievementsBar(ra, Modifier.fillMaxWidth(), height = 8.dp)
        }
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            val extra = listOfNotNull(
                s.ra.achPoints.format(detail.earnedPoints, detail.points),
                detail.earnedHardcore.takeIf { it > 0 }?.let { s.ra.achHardcore.format(it) },
            ).joinToString("  ·  ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(extra, color = Palette.dim, fontSize = 12.sp, modifier = Modifier.weight(1f))
                Text(if (lockedOnly) s.ra.achFilterLocked else s.ra.achFilterAll, color = Palette.accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        Spacer(Modifier.height(10.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val items = detail?.achievements?.let { all -> if (lockedOnly) all.filter { !it.unlocked } else all }
            when {
                items == null -> Text(
                    when {
                        loading -> s.ra.achLoading
                        status == RaStatus.AUTH_ERROR -> s.ra.achAuthError
                        status == RaStatus.NETWORK_ERROR -> s.ra.achNetError
                        else -> s.ra.achLoading
                    },
                    color = Palette.dim, fontSize = 14.sp, modifier = Modifier.align(Alignment.Center),
                )
                items.isEmpty() -> Text(s.ra.achNoneLeft, color = FavGold, fontSize = 15.sp, modifier = Modifier.align(Alignment.Center))
                else -> LazyColumn(
                    state = list,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = PaddingValues(bottom = 6.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(items, key = { it.id }) { a -> AchievementRow(a) }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            PadGlyph(cancelGlyph)
            Spacer(Modifier.width(6.dp))
            Text(s.hintBack, color = Palette.dim, fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            PadGlyph("Y")
            Spacer(Modifier.width(6.dp))
            Text(s.ra.hintFilter, color = Palette.dim, fontSize = 13.sp)
            Spacer(Modifier.width(16.dp))
            Text("▲▼  " + s.ra.hintScroll, color = Palette.dim, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AchievementRow(a: Ra.Achievement) {
    val s = LocalStrings.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (a.unlocked) Palette.surfaceHi.copy(alpha = 0.9f) else Palette.surfaceHi.copy(alpha = 0.35f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Badge(a, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                a.title, color = if (a.unlocked) Palette.text else Palette.text.copy(alpha = 0.6f),
                fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                a.description, color = Palette.dim, fontSize = 12.sp, lineHeight = 15.sp,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                s.ra.achPointsShort.format(a.points),
                color = if (a.unlocked) FavGold else Palette.dim, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            )
            val at = a.unlockedAt?.let(::parseDate)
            Text(
                if (at != null) s.ra.achUnlocked.format(s.ago(at)) else if (a.unlocked) "✓" else "🔒 " + s.ra.achLocked,
                color = if (a.unlocked) Palette.accent else Palette.dim, fontSize = 11.sp, maxLines = 1,
            )
            if (a.earnedHardcore != null) Text("HC", color = Color(0xFFFF8A65), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
}
