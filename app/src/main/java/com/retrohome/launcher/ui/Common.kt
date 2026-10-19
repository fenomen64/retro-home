package com.retrohome.launcher.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import com.retrohome.launcher.data.GameSystem
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Палитра по умолчанию. Позже станет одной из тем. */
/** Цвета темы оформления. */
data class ThemeColors(
    val id: String,
    val bgTop: Color,
    val bgBottom: Color,
    val surface: Color,
    val surfaceHi: Color,
    val text: Color,
    val dim: Color,
    val scrim: Color,
    val light: Boolean = false,
)

object Themes {
    val NIGHT = ThemeColors("night", Color(0xFF151826), Color(0xFF0B0C12), Color(0xFF1F2335), Color(0xFF2A2F46), Color(0xFFF2F3F7), Color(0xFF8C92A6), Color(0xCC07080D))
    val OLED = ThemeColors("oled", Color(0xFF000000), Color(0xFF000000), Color(0xFF121214), Color(0xFF222226), Color(0xFFF2F2F2), Color(0xFF8A8A92), Color(0xE6000000))
    val GRAPHITE = ThemeColors("graphite", Color(0xFF26272B), Color(0xFF141517), Color(0xFF303136), Color(0xFF3D3F45), Color(0xFFF0F0F2), Color(0xFF9A9CA3), Color(0xCC0E0E10))
    val LIGHT = ThemeColors("light", Color(0xFFF3F4F8), Color(0xFFE2E5EE), Color(0xFFFFFFFF), Color(0xFFDDE1EC), Color(0xFF1A1C24), Color(0xFF666B7A), Color(0x99B8BDCA), light = true)
    val all = listOf(NIGHT, OLED, GRAPHITE, LIGHT)
    fun of(id: String) = all.firstOrNull { it.id == id } ?: NIGHT

    /** Цвет выделения (акцент). */
    val accents: List<Pair<String, Color>> = listOf(
        "coral" to Color(0xFFFF5A5F),
        "orange" to Color(0xFFFF8A3D),
        "gold" to Color(0xFFE8B100),
        "green" to Color(0xFF2FBF71),
        "teal" to Color(0xFF14B3AD),
        "blue" to Color(0xFF4C8DFF),
        "purple" to Color(0xFF9B6BFF),
        "pink" to Color(0xFFFF5FA2),
    )
    fun accent(id: String) = accents.firstOrNull { it.first == id }?.second ?: accents.first().second
}

/**
 * Цвета интерфейса. Тема и акцент меняются на лету: значения хранятся в состоянии Compose,
 * и всё, что их читает, перерисовывается само.
 */
object Palette {
    var theme by mutableStateOf(Themes.NIGHT)
    var accentColor by mutableStateOf(Themes.accents.first().second)

    fun apply(themeId: String, accentId: String) {
        theme = Themes.of(themeId)
        accentColor = Themes.accent(accentId)
    }

    val bgTop get() = theme.bgTop
    val bgBottom get() = theme.bgBottom
    val surface get() = theme.surface
    val surfaceHi get() = theme.surfaceHi
    val accent get() = accentColor
    val text get() = theme.text
    val dim get() = theme.dim
    val scrim get() = theme.scrim
}

/** Цвет консоли для карточек игр. */
fun systemColor(s: com.retrohome.launcher.data.GameSystem): Color = when (s) {
    com.retrohome.launcher.data.GameSystem.PS1, com.retrohome.launcher.data.GameSystem.PS2,
    com.retrohome.launcher.data.GameSystem.PS3 -> Color(0xFF2F5BB7)
    com.retrohome.launcher.data.GameSystem.PSP, com.retrohome.launcher.data.GameSystem.VITA -> Color(0xFF3B4A6B)
    com.retrohome.launcher.data.GameSystem.SWITCH -> Color(0xFFD7263D)
    com.retrohome.launcher.data.GameSystem.N3DS, com.retrohome.launcher.data.GameSystem.NDS -> Color(0xFF8E44AD)
    com.retrohome.launcher.data.GameSystem.GB, com.retrohome.launcher.data.GameSystem.GBC -> Color(0xFF6A8E3A)
    com.retrohome.launcher.data.GameSystem.GBA -> Color(0xFF5B3FA8)
    com.retrohome.launcher.data.GameSystem.GC, com.retrohome.launcher.data.GameSystem.WII,
    com.retrohome.launcher.data.GameSystem.WIIU -> Color(0xFF5C4BBF)
    com.retrohome.launcher.data.GameSystem.NES, com.retrohome.launcher.data.GameSystem.SNES,
    com.retrohome.launcher.data.GameSystem.N64 -> Color(0xFFB03A2E)
    com.retrohome.launcher.data.GameSystem.MD, com.retrohome.launcher.data.GameSystem.SMS,
    com.retrohome.launcher.data.GameSystem.GG, com.retrohome.launcher.data.GameSystem.SATURN,
    com.retrohome.launcher.data.GameSystem.DC -> Color(0xFF1F6FB2)
    else -> Color(0xFF4A5068)
}

/** Кнопки геймпада в терминах интерфейса (A/B уже с учётом настройки «поменять местами»). */
enum class Pad { UP, DOWN, LEFT, RIGHT, CONFIRM, CANCEL, X, Y, START, SELECT, L1, R1 }

/** Куда отдавать нажатия кнопок. Последний зарегистрированный обработчик главный. */
class PadRegistry {
    private val handlers = ArrayList<(Pad) -> Boolean>()
    fun register(h: (Pad) -> Boolean) { handlers.add(h) }
    fun unregister(h: (Pad) -> Boolean) { handlers.remove(h) }
    fun dispatch(pad: Pad): Boolean = handlers.lastOrNull()?.invoke(pad) ?: false
}

val LocalPadRegistry = staticCompositionLocalOf { PadRegistry() }

/** Загрузка иконок; в тестах подменяется. */
interface IconLoader {
    /** Уже готовая иконка из кэша, без ожидания. */
    fun peek(key: String, sizePx: Int): ImageBitmap?
    fun load(key: String, sizePx: Int): ImageBitmap?
}

val LocalIconLoader = staticCompositionLocalOf<IconLoader> {
    object : IconLoader {
        override fun peek(key: String, sizePx: Int): ImageBitmap? = null
        override fun load(key: String, sizePx: Int): ImageBitmap? = null
    }
}

/** Загрузка обложек игр с диска; в тестах подменяется. */
interface CoverLoader {
    fun peek(name: String): ImageBitmap?
    fun load(name: String): ImageBitmap?
}

val LocalCoverLoader = staticCompositionLocalOf<CoverLoader> {
    object : CoverLoader {
        override fun peek(name: String): ImageBitmap? = null
        override fun load(name: String): ImageBitmap? = null
    }
}

/** Картинка обложки или ничего, пока грузится. */
@Composable
fun rememberCover(name: String?): ImageBitmap? {
    val loader = LocalCoverLoader.current
    var bmp by remember(name) { mutableStateOf(name?.let { loader.peek(it) }) }
    LaunchedEffect(name) {
        if (name != null && bmp == null) bmp = withContext(Dispatchers.IO) { loader.load(name) }
    }
    return bmp
}

/**
 * Пропорции обложки (ширина / высота): у консолей они разные — у Game Boy почти квадратные,
 * у SNES (США) — шире, чем выше. Пока обложки нет — 3:4.
 */
fun coverAspect(img: ImageBitmap?): Float =
    img?.takeIf { it.height > 0 }?.let { (it.width.toFloat() / it.height).coerceIn(0.5f, 1.8f) } ?: 0.75f

@Composable
fun AppIcon(key: String, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val loader = LocalIconLoader.current
    var bmp by remember(key, px, loader) { mutableStateOf(loader.peek(key, px)) }
    LaunchedEffect(key, px, loader) {
        if (bmp == null) bmp = withContext(Dispatchers.IO) { loader.load(key, px) }
    }
    val b = bmp
    if (b != null) {
        Image(b, contentDescription = null, modifier = modifier.size(size))
    } else {
        Box(
            modifier
                .size(size)
                .clip(RoundedCornerShape(size * 0.28f))
                .background(Color(0x33FFFFFF))
        )
    }
}

/**
 * Строка списка, которой удобно управлять и пальцем, и крестовиной:
 * при фокусе с кнопок появляется рамка.
 */
@Composable
fun FocusRow(
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Box(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 3.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (focused) Palette.surfaceHi else Palette.surface)
            .border(
                if (focused) BorderStroke(2.dp, Palette.accent) else BorderStroke(0.dp, Color.Transparent),
                RoundedCornerShape(14.dp),
            )
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
fun TitleAndDesc(title: String, desc: String? = null, value: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Palette.text, fontSize = 16.sp)
            if (desc != null) Text(desc, color = Palette.dim, fontSize = 13.sp)
        }
        if (value != null) Text(value, color = Palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * Строка-переключатель: кнопка «влево» — предыдущий вариант, «вправо» — следующий,
 * нажатие (A или палец по строке) — следующий. Стрелки ‹ › нажимаются пальцем.
 */
@Composable
fun ChoiceRow(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    desc: String? = null,
    leading: (@Composable () -> Unit)? = null,
    onStep: (Int) -> Unit,
) {
    FocusRow(modifier.dpadSteps(onStep), onClick = { onStep(1) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            leading?.invoke()
            Column(Modifier.weight(1f)) {
                Text(title, color = Palette.text, fontSize = 16.sp)
                if (desc != null) Text(desc, color = Palette.dim, fontSize = 13.sp)
            }
            ChoiceValue(value, onStep)
        }
    }
}

/** Значение с нажимаемыми стрелками: ‹ значение ›. */
@Composable
fun ChoiceValue(value: String, onStep: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "‹", color = Palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onStep(-1) }.padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Text(value, color = Palette.accent, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(
            "›", color = Palette.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onStep(1) }.padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

/** Кнопки «влево/вправо» на выделенной строке листают варианты назад/вперёд. */
fun Modifier.dpadSteps(onStep: (Int) -> Unit): Modifier = onPreviewKeyEvent { e ->
    val dir = when (e.key) {
        Key.DirectionLeft -> -1
        Key.DirectionRight -> 1
        else -> 0
    }
    if (dir == 0) return@onPreviewKeyEvent false
    if (e.type == KeyEventType.KeyDown) onStep(dir)
    true
}

/** Значок кнопки геймпада: круг с буквой или плашка с названием. */
@Composable
fun PadGlyph(label: String, modifier: Modifier = Modifier) {
    if (label.length == 1) {
        RoundGlyph(label, modifier)
        return
    }
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.text)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Palette.bgBottom, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

/** Как нарисована кнопка: буквой или значком PlayStation. */
enum class GlyphShape { LETTER, CROSS, CIRCLE, SQUARE, TRIANGLE }

/** Вид одной кнопки: форма, цвет круга и цвет буквы/значка. */
data class GlyphLook(val shape: GlyphShape, val bg: Color, val fg: Color, val text: String? = null)

/** Кнопки «как на консоли»: ключ — кнопка A/B/X/Y по коду Android. */
class GlyphStyle(val looks: Map<String, GlyphLook>)

/** Кнопки по позиции на крестовине Android: A — нижняя, B — правая, X — левая, Y — верхняя. */
private fun letters(a: Color, b: Color, x: Color, y: Color, fgA: Color = Color.White, fgB: Color = Color.White,
                    fgX: Color = Color.White, fgY: Color = Color.White) = GlyphStyle(
    mapOf(
        "A" to GlyphLook(GlyphShape.LETTER, a, fgA), "B" to GlyphLook(GlyphShape.LETTER, b, fgB),
        "X" to GlyphLook(GlyphShape.LETTER, x, fgX), "Y" to GlyphLook(GlyphShape.LETTER, y, fgY),
    )
)

private val red = Color(0xFFD9302C)
private val yellow = Color(0xFFF2C230)
private val blue = Color(0xFF3F5CC8)
private val green = Color(0xFF2E9E57)
private val ink = Color(0xFF1C1D22)

/**
 * Кнопки в стиле консоли текущей вкладки.
 * PlayStation — значки ✕ ○ □ △ на тех же местах (нижняя, правая, левая, верхняя);
 * остальные — буквы в цветах кнопок своих контроллеров.
 */
fun glyphStyleFor(system: GameSystem?): GlyphStyle? {
    val light = Color(0xFFEDEEF2)
    return when (system) {
        null -> null
        GameSystem.PS1, GameSystem.PS2, GameSystem.PS3, GameSystem.PSP, GameSystem.VITA -> GlyphStyle(
            mapOf(
                "A" to GlyphLook(GlyphShape.CROSS, light, Color(0xFF5C7FD6)),
                "B" to GlyphLook(GlyphShape.CIRCLE, light, Color(0xFFE5484D)),
                "X" to GlyphLook(GlyphShape.SQUARE, light, Color(0xFFD873B8)),
                "Y" to GlyphLook(GlyphShape.TRIANGLE, light, Color(0xFF2FB59A)),
            )
        )
        GameSystem.SNES -> letters(red, yellow, blue, green, fgB = ink)
        // Nintendo DS / 3DS — те же цвета, что у SNES и японской Famicom
        GameSystem.NDS, GameSystem.N3DS -> letters(red, yellow, blue, green, fgB = ink)
        // N64: синяя A, зелёная B; жёлтые C-кнопки — на месте X и Y
        GameSystem.N64 -> letters(Color(0xFF2F5BD8), green, yellow, yellow, fgX = ink, fgY = ink)
        // GameCube: большая зелёная A, красная B, X и Y — светлые «фасолины» на фиолетовом корпусе
        GameSystem.GC -> letters(Color(0xFF2DB36A), red, Color(0xFFD9D4F0), Color(0xFFD9D4F0),
            fgX = Color(0xFF4B3D8F), fgY = Color(0xFF4B3D8F))
        // Game Boy: бордовые A и B, X и Y — в цветах зелёного экрана
        GameSystem.GB, GameSystem.GBC -> letters(Color(0xFF9B2257), Color(0xFF9B2257), Color(0xFF8BAC0F), Color(0xFF8BAC0F),
            fgX = Color(0xFF0F380F), fgY = Color(0xFF0F380F))
        // Game Boy Advance: фиолетовый корпус и серые кнопки
        GameSystem.GBA -> letters(Color(0xFF5A3FA0), Color(0xFF5A3FA0), Color(0xFFB9B3CF), Color(0xFFB9B3CF),
            fgX = Color(0xFF3A2A70), fgY = Color(0xFF3A2A70))
        // NES: красные A и B, серые X и Y
        GameSystem.NES -> letters(Color(0xFFC8102E), Color(0xFFC8102E), Color(0xFFA7A9AC), Color(0xFFA7A9AC), fgX = ink, fgY = ink)
        // Switch: неоновые Joy-Con — красный и синий
        GameSystem.SWITCH -> letters(Color(0xFFFF4554), Color(0xFFFF4554), Color(0xFF00B8E6), Color(0xFF00B8E6))
        // Wii / Wii U: белые кнопки с фирменным голубым
        GameSystem.WII, GameSystem.WIIU -> letters(Color.White, Color.White, Color.White, Color.White,
            fgA = Color(0xFF009AC7), fgB = Color(0xFF009AC7), fgX = Color(0xFF009AC7), fgY = Color(0xFF009AC7))
        GameSystem.DC -> letters(red, blue, yellow, green, fgX = ink)
        // Sega Saturn (японская): A, B, C — цветные
        GameSystem.SATURN -> letters(Color(0xFF3F5CC8), Color(0xFF2E9E57), Color(0xFFD9302C), Color(0xFFF2C230), fgY = ink)
        GameSystem.PC -> letters(Color(0xFF3FA535), red, Color(0xFF2F6FD6), Color(0xFFF2B630), fgY = ink)
        else -> null
    }
}

/** Стиль кнопок текущей вкладки (null — обычные кнопки темы). */
val LocalGlyphStyle = androidx.compose.runtime.compositionLocalOf<GlyphStyle?> { null }

/**
 * Круглая кнопка A/B/X/Y. Буква рисуется по её реальным границам, поэтому стоит ровно
 * по центру круга при любом шрифте и размере текста в настройках телефона
 * (обычный текст центрируется по строке, и буква съезжает вниз).
 */
@Composable
private fun RoundGlyph(label: String, modifier: Modifier) {
    val look = LocalGlyphStyle.current?.looks?.get(label)
    val bg = look?.bg ?: Palette.text
    val fgColor = look?.fg ?: Palette.bgBottom
    val fg = fgColor.toArgb()
    val shape = look?.shape ?: GlyphShape.LETTER
    val ringColor = Palette.text.copy(alpha = 0.4f)
    val density = LocalDensity.current
    val textPx = with(density) { 12.sp.toPx() }
    val paint = remember(fg, textPx) {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = fg
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
        }
    }
    Box(
        modifier
            .size(22.dp)
            .semantics { contentDescription = label }
            .drawBehind {
                drawCircle(bg)
                // Тёмная кнопка (как у Switch) на тёмном фоне — тонкий светлый ободок, чтобы её было видно
                if (bg.luminance() < 0.1f && !Palette.theme.light) {
                    val ringW = 1.2.dp.toPx()
                    drawCircle(ringColor, size.minDimension / 2 - ringW / 2, style = androidx.compose.ui.graphics.drawscope.Stroke(ringW))
                }
                val r = size.minDimension * 0.24f
                val w = size.minDimension * 0.09f
                val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = w, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                    join = androidx.compose.ui.graphics.StrokeJoin.Round)
                when (shape) {
                    GlyphShape.LETTER -> {
                        // Размер буквы — от шрифта, но не больше 60% круга
                        paint.textSize = minOf(textPx, size.minDimension * 0.6f)
                        val bounds = android.graphics.Rect()
                        val t = look?.text ?: label
                        paint.getTextBounds(t, 0, t.length, bounds)
                        drawContext.canvas.nativeCanvas.drawText(t, center.x - bounds.exactCenterX(), center.y - bounds.exactCenterY(), paint)
                    }
                    GlyphShape.CROSS -> {
                        drawLine(fgColor, center + Offset(-r, -r), center + Offset(r, r), w, androidx.compose.ui.graphics.StrokeCap.Round)
                        drawLine(fgColor, center + Offset(-r, r), center + Offset(r, -r), w, androidx.compose.ui.graphics.StrokeCap.Round)
                    }
                    GlyphShape.CIRCLE -> drawCircle(fgColor, r * 1.05f, style = stroke)
                    GlyphShape.SQUARE -> drawRect(fgColor, center - Offset(r * 0.95f, r * 0.95f), Size(r * 1.9f, r * 1.9f), style = stroke)
                    GlyphShape.TRIANGLE -> {
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(center.x, center.y - r * 1.05f)
                            lineTo(center.x + r * 1.1f, center.y + r * 0.8f)
                            lineTo(center.x - r * 1.1f, center.y + r * 0.8f)
                            close()
                        }
                        drawPath(path, fgColor, style = stroke)
                    }
                }
            }
    )
}
