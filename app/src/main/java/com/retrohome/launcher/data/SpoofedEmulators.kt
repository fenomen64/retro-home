package com.retrohome.launcher.data

import android.content.ComponentName
import android.content.Context

/**
 * Эмуляторы, которые ставятся под чужим именем пакета. Ночная «оптимизированная» сборка Eden
 * называется com.miHoYo.Yuanshen (как Genshin Impact), чтобы прошивка включала для неё игровые
 * ускорения. Отличаем её от настоящей игры по экрану эмуляции внутри.
 */
object SpoofedEmulators {
    const val GENSHIN = "com.miHoYo.Yuanshen"

    /** Пакет → (экран эмуляции, id эмулятора). */
    private val known = mapOf(
        GENSHIN to ("org.yuzu.yuzu_emu.activities.EmulationActivity" to "switch"),
    )

    fun isSpoofable(pkg: String) = pkg in known

    /** Эмулятор под чужим пакетом: только если внутри действительно есть его экран эмуляции. */
    fun detect(context: Context, pkg: String): EmulatorDef? {
        val (activity, id) = known[pkg] ?: return null
        val ok = runCatching { context.packageManager.getActivityInfo(ComponentName(pkg, activity), 0) }.isSuccess
        return if (ok) Emulators.all.firstOrNull { it.id == id } else null
    }
}
