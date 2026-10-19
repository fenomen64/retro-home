package com.retrohome.launcher.data

import android.content.ComponentName
import android.content.Context

/**
 * Эмуляторы под чужим или незнакомым именем пакета.
 *
 * Ночная «оптимизированная» сборка Eden называется com.miHoYo.Yuanshen (как Genshin Impact), чтобы прошивка
 * включала для неё игровые ускорения; Legacy, ночные и прочие сборки выходят под своими, всё новыми именами.
 * Узнаём их по экрану эмуляции внутри: класс экрана сборки наследуют от исходного проекта и не переименовывают.
 */
object SpoofedEmulators {
    const val GENSHIN = "com.miHoYo.Yuanshen"

    /** Экран эмуляции → эмулятор (id в [Emulators]) и рецепт запуска (id в [Launchers]). */
    data class Signature(val activity: String, val emulatorId: String, val recipeId: String)

    val signatures = listOf(
        Signature("org.yuzu.yuzu_emu.activities.EmulationActivity", "switch", "eden"),
        Signature("org.sudachi.sudachi_emu.activities.EmulationActivity", "switch", "sudachi"),
        Signature("org.citron.citron_emu.activities.EmulationActivity", "switch", "citron"),
    )

    /** Пакеты, которые бывают и не эмулятором (настоящая игра): берём, только если внутри эмулятор. */
    private val spoofable = setOf(GENSHIN)

    fun isSpoofable(pkg: String) = pkg in spoofable

    /** Какой эмулятор внутри пакета — по экрану эмуляции (null — не эмулятор из известных). */
    fun signatureOf(context: Context, pkg: String): Signature? = signatures.firstOrNull { sig ->
        runCatching { context.packageManager.getActivityInfo(ComponentName(pkg, sig.activity), 0) }.isSuccess
    }

    /** Эмулятор под чужим пакетом (Genshin): только если внутри действительно есть его экран эмуляции. */
    fun detect(context: Context, pkg: String): EmulatorDef? {
        if (pkg !in spoofable) return null
        return signatureOf(context, pkg)?.let { sig -> Emulators.all.firstOrNull { it.id == sig.emulatorId } }
    }

    /** Незнакомый пакет с экраном эмуляции известного эмулятора (Eden Legacy Nightly и т. п.). */
    fun detectUnknown(context: Context, pkg: String): EmulatorDef? =
        signatureOf(context, pkg)?.let { sig -> Emulators.all.firstOrNull { it.id == sig.emulatorId } }
}
