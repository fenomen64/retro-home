package com.retrohome.launcher.ui

/**
 * Масштаб консольного интерфейса. Интерфейс рассчитан на экран высотой 540 dp (Retroid Pocket G2);
 * на экранах ниже (например, телефон с плотным экраном — около 410 dp) всё уменьшается пропорционально,
 * чтобы страница игры и списки выглядели так же, а не теснили описание.
 */
object UiScale {
    const val DESIGN_SHORT_SIDE_DP = 540f

    /** Варианты в настройках: 0 — авто, остальное — проценты. */
    val options = listOf(0, 70, 80, 90, 100, 110, 120)

    fun auto(shortSideDp: Int): Float = (shortSideDp / DESIGN_SHORT_SIDE_DP).coerceIn(0.7f, 1f)

    fun factor(setting: Int, shortSideDp: Int): Float = if (setting <= 0) auto(shortSideDp) else setting / 100f
}
