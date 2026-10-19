package com.retrohome.launcher.data

import android.content.Intent
import android.net.Uri

/**
 * Как передать игру конкретному эмулятору.
 * Данные о компонентах и параметрах взяты из открытых описаний платформ Daijishō
 * (github.com/TapiocaFox/Daijishou, папка platforms) — они проверены сообществом на Android.
 */
data class LaunchRecipe(
    val id: String,
    val name: String,
    val systems: Set<GameSystem>,
    /** Пакеты в порядке предпочтения; берётся первый установленный. Пустой — смотри [packagePrefix]. */
    val packages: List<String> = emptyList(),
    val packagePrefix: String? = null,
    /** Возможные классы активити: у разных сборок бывают разные. */
    val activities: List<String>,
    val build: (ctx: LaunchContext) -> Intent,
)

/** Всё, что нужно рецепту, чтобы собрать запуск. */
data class LaunchContext(
    val pkg: String,
    val activity: String,
    /** content:// адрес файла из выбранной папки ROMs. */
    val uri: Uri,
    /** Обычный путь к файлу. */
    val path: String,
    val system: GameSystem,
)

private fun base(c: LaunchContext, action: String? = null): Intent =
    Intent().apply {
        setClassName(c.pkg, c.activity)
        if (action != null) this.action = action
        addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }

/** Расширенный адрес передаём и как строку (так ждут эмуляторы), и в clipData, чтобы сработало разрешение. */
private fun Intent.grantUri(uri: Uri): Intent = apply {
    clipData = android.content.ClipData.newRawUri("rom", uri)
}

object Launchers {
    /** Ядра RetroArch по умолчанию для каждой системы. */
    val retroArchCores: Map<GameSystem, String> = mapOf(
        GameSystem.NES to "fceumm",
        GameSystem.SNES to "snes9x",
        GameSystem.N64 to "mupen64plus_next_gles3",
        GameSystem.GB to "gambatte",
        GameSystem.GBC to "gambatte",
        GameSystem.GBA to "mgba",
        GameSystem.NDS to "melonds",
        GameSystem.N3DS to "citra",
        GameSystem.PS1 to "pcsx_rearmed",
        GameSystem.PSP to "ppsspp",
        GameSystem.MD to "genesis_plus_gx",
        GameSystem.SMS to "genesis_plus_gx",
        GameSystem.GG to "genesis_plus_gx",
        GameSystem.SATURN to "yabasanshiro",
        GameSystem.DC to "flycast",
        GameSystem.ARCADE to "fbneo",
        GameSystem.PCE to "mednafen_pce_fast",
        GameSystem.GC to "dolphin",
        GameSystem.WII to "dolphin",
    )

    private val melonActions = mapOf(
        "me.magnum.melonds" to "me.magnum.melonds.LAUNCH_ROM",
        "me.magnum.melonds.nightly" to "me.magnum.melonds.nightly.LAUNCH_ROM",
        "me.magnum.melonds.dev" to "me.magnum.melonds.dev.LAUNCH_ROM",
        "me.magnum.melondualds" to "me.magnum.melonds.dev.LAUNCH_ROM",
    )

    val all: List<LaunchRecipe> = listOf(
        LaunchRecipe(
            "ppsspp", "PPSSPP", setOf(GameSystem.PSP),
            packages = listOf("org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "org.ppsspp.ppsspplegacy"),
            activities = listOf("org.ppsspp.ppsspp.PpssppActivity"),
        ) { c ->
            base(c, Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(c.uri, "application/octet-stream")
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
        },
        LaunchRecipe(
            "sx2", "NetherSX2 / AetherSX2", setOf(GameSystem.PS2),
            packagePrefix = "xyz.aethersx2",
            activities = listOf("xyz.aethersx2.android.EmulationActivity"),
        ) { c -> base(c, Intent.ACTION_MAIN).putExtra("bootPath", c.uri.toString()).grantUri(c.uri) },
        LaunchRecipe(
            "duckstation", "DuckStation", setOf(GameSystem.PS1),
            packages = listOf("com.github.stenzek.duckstation"),
            activities = listOf("com.github.stenzek.duckstation.EmulationActivity"),
        ) { c -> base(c).putExtra("bootPath", c.uri.toString()).grantUri(c.uri) },
        LaunchRecipe(
            "melonds", "melonDS", setOf(GameSystem.NDS),
            packages = melonActions.keys.toList(),
            activities = listOf("me.magnum.melonds.ui.emulator.EmulatorActivity"),
        ) { c -> base(c, melonActions[c.pkg] ?: "me.magnum.melonds.LAUNCH_ROM").putExtra("uri", c.uri.toString()).grantUri(c.uri) },
        LaunchRecipe(
            "drastic", "DraStic", setOf(GameSystem.NDS),
            packages = listOf("com.dsemu.drastic"),
            activities = listOf("com.dsemu.drastic.DraSticActivity"),
        ) { c -> base(c).setData(c.uri) },
        LaunchRecipe(
            "citra", "Azahar / Citra", setOf(GameSystem.N3DS),
            packages = listOf(
                "org.azahar_emu.azahar", "io.github.lime3ds.android", "io.github.azaharplus.android",
                "org.citra.citra_emu", "org.citra.citra_emu.canary", "org.gamerytb.lemonade.canary",
            ),
            activities = listOf("org.citra.citra_emu.activities.EmulationActivity", "io.github.lime3ds.android.activities.EmulationActivity"),
        ) { c -> base(c, Intent.ACTION_VIEW).setData(c.uri) },
        LaunchRecipe(
            "citra_mmj", "Citra MMJ", setOf(GameSystem.N3DS),
            packages = listOf("org.citra.emu"),
            activities = listOf("org.citra.emu.ui.EmulationActivity"),
        ) { c -> base(c, Intent.ACTION_VIEW).putExtra("GamePath", c.path) },
        LaunchRecipe(
            "eden", "Eden / Yuzu", setOf(GameSystem.SWITCH),
            packages = listOf(
                "dev.eden.eden_emulator", "dev.legacy.eden_emulator", "dev.eden.eden_nightly",
                "dev.eden.eden_emulator.nightly", "org.yuzu.yuzu_emu", "org.yuzu.yuzu_emu.ea",
            ),
            activities = listOf("org.yuzu.yuzu_emu.activities.EmulationActivity"),
        ) { c -> base(c, "android.nfc.action.TECH_DISCOVERED").setData(c.uri) },
        LaunchRecipe(
            "sudachi", "Sudachi", setOf(GameSystem.SWITCH),
            packages = listOf("org.sudachi.sudachi_emu", "org.sudachi.sudachi_emu.ea"),
            activities = listOf("org.sudachi.sudachi_emu.activities.EmulationActivity"),
        ) { c -> base(c, "android.nfc.action.TECH_DISCOVERED").setData(c.uri) },
        LaunchRecipe(
            "citron", "Citron", setOf(GameSystem.SWITCH),
            packages = listOf("org.citron.citron_emu"),
            activities = listOf("org.citron.citron_emu.activities.EmulationActivity"),
        ) { c -> base(c, "android.nfc.action.TECH_DISCOVERED").setData(c.uri) },
        LaunchRecipe(
            "dolphin", "Dolphin", setOf(GameSystem.GC, GameSystem.WII),
            packages = listOf("org.dolphinemu.dolphinemu", "org.dolphinemu.handheld", "org.dolphinemu.mmjr3", "org.dolphinemu.mmjr"),
            activities = listOf("org.dolphinemu.dolphinemu.ui.main.MainActivity"),
        ) { c -> base(c, Intent.ACTION_MAIN).putExtra("AutoStartFile", c.uri.toString()).grantUri(c.uri) },
        LaunchRecipe(
            "flycast", "Flycast", setOf(GameSystem.DC),
            packages = listOf("com.flycast.emulator"),
            activities = listOf("com.flycast.emulator.MainActivity"),
        ) { c -> base(c, Intent.ACTION_VIEW).setData(c.uri) },
        LaunchRecipe(
            "mupen", "Mupen64Plus FZ", setOf(GameSystem.N64),
            packages = listOf("org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro", "org.mupen64plusae.v3.alpha"),
            activities = listOf("paulscode.android.mupen64plusae.SplashActivity"),
        ) { c -> base(c, Intent.ACTION_VIEW).setData(c.uri) },
        LaunchRecipe(
            "pizzaboy", "Pizza Boy GBC", setOf(GameSystem.GB, GameSystem.GBC),
            packages = listOf("it.dbtecno.pizzaboypro", "it.dbtecno.pizzaboy"),
            activities = listOf("it.dbtecno.pizzaboypro.MainActivity", "it.dbtecno.pizzaboy.MainActivity"),
        ) { c -> base(c).putExtra("rom_uri", c.uri.toString()).grantUri(c.uri) },
        LaunchRecipe(
            "pizzaboygba", "Pizza Boy GBA", setOf(GameSystem.GBA),
            packages = listOf("it.dbtecno.pizzaboygbapro", "it.dbtecno.pizzaboygba"),
            activities = listOf("it.dbtecno.pizzaboygbapro.MainActivity", "it.dbtecno.pizzaboygba.MainActivity"),
        ) { c -> base(c).putExtra("rom_uri", c.uri.toString()).grantUri(c.uri) },
        LaunchRecipe(
            "myboy", "My Boy!", setOf(GameSystem.GBA),
            packages = listOf("com.fastemulator.gba"),
            activities = listOf("com.fastemulator.gba.EmulatorActivity"),
        ) { c -> base(c, Intent.ACTION_VIEW).setData(c.uri) },
        LaunchRecipe(
            "myoldboy", "My OldBoy!", setOf(GameSystem.GB, GameSystem.GBC),
            packages = listOf("com.fastemulator.gbc"),
            activities = listOf("com.fastemulator.gbc.EmulatorActivity"),
        ) { c -> base(c, Intent.ACTION_VIEW).setData(c.uri) },
        LaunchRecipe(
            "retroarch", "RetroArch", retroArchCores.keys,
            packages = listOf("com.retroarch.aarch64", "com.retroarch", "com.retroarch.ra32"),
            activities = listOf("com.retroarch.browser.retroactivity.RetroActivityFuture"),
        ) { c ->
            val core = retroArchCores[c.system] ?: ""
            Intent().apply {
                setClassName(c.pkg, c.activity)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                putExtra("ROM", c.path)
                putExtra("LIBRETRO", "/data/data/${c.pkg}/cores/${core}_libretro_android.so")
                putExtra("CONFIGFILE", "/storage/emulated/0/Android/data/${c.pkg}/files/retroarch.cfg")
                putExtra("QUITFOCUS", "")
            }
        },
    )

    fun byId(id: String) = all.firstOrNull { it.id == id }

    /** Установленный пакет для рецепта, если он есть. */
    fun installedPackage(recipe: LaunchRecipe, installed: Set<String>): String? {
        recipe.packages.firstOrNull { it in installed }?.let { return it }
        val prefix = recipe.packagePrefix ?: return null
        return installed.filter { it.startsWith(prefix) }.sorted().firstOrNull()
    }

    /** Чем можно запускать игры системы на этом устройстве — в порядке предпочтения. */
    fun available(system: GameSystem, installed: Set<String>): List<LaunchRecipe> =
        all.filter { system in it.systems && installedPackage(it, installed) != null }

    /** Эмулятор для системы: выбранный вручную, иначе первый подходящий установленный. */
    fun choose(system: GameSystem, installed: Set<String>, chosenId: String?): LaunchRecipe? {
        val options = available(system, installed)
        return options.firstOrNull { it.id == chosenId } ?: options.firstOrNull()
    }
}
