package com.retrohome.launcher.data

/** Игровые платформы. Короткое имя показывается на вкладках и значках. */
enum class GameSystem(val short: String, val ru: String, val en: String) {
    NES("NES", "NES / Dendy", "NES"),
    SNES("SNES", "Super Nintendo", "Super Nintendo"),
    N64("N64", "Nintendo 64", "Nintendo 64"),
    GC("GC", "GameCube", "GameCube"),
    WII("Wii", "Wii", "Wii"),
    WIIU("Wii U", "Wii U", "Wii U"),
    SWITCH("Switch", "Nintendo Switch", "Nintendo Switch"),
    GB("GB", "Game Boy", "Game Boy"),
    GBC("GBC", "Game Boy Color", "Game Boy Color"),
    GBA("GBA", "Game Boy Advance", "Game Boy Advance"),
    NDS("NDS", "Nintendo DS", "Nintendo DS"),
    N3DS("3DS", "Nintendo 3DS", "Nintendo 3DS"),
    PS1("PS1", "PlayStation", "PlayStation"),
    PS2("PS2", "PlayStation 2", "PlayStation 2"),
    PS3("PS3", "PlayStation 3", "PlayStation 3"),
    PSP("PSP", "PlayStation Portable", "PlayStation Portable"),
    VITA("Vita", "PS Vita", "PS Vita"),
    MD("MD", "Sega Mega Drive", "Sega Genesis / Mega Drive"),
    SMS("SMS", "Sega Master System", "Sega Master System"),
    GG("GG", "Sega Game Gear", "Sega Game Gear"),
    SATURN("Saturn", "Sega Saturn", "Sega Saturn"),
    DC("DC", "Dreamcast", "Dreamcast"),
    ARCADE("Arcade", "Аркадные автоматы", "Arcade"),
    PCE("PCE", "PC Engine", "PC Engine / TurboGrafx-16"),
    PC("PC", "Игры для ПК", "PC games"),
    JOIPLAY("JoiPlay", "RPG Maker / Ren'Py", "RPG Maker / Ren'Py"),
    SCUMMVM("ScummVM", "Квесты ScummVM", "ScummVM adventures");

    fun title(lang: Lang) = if (lang == Lang.RU) ru else en
}

/**
 * Описание известного эмулятора.
 * Распознаём по началу имени пакета или по словам в названии/пакете (это ловит форки).
 */
data class EmulatorDef(
    val id: String,
    val name: String,
    val systems: List<GameSystem>,
    val packagePrefixes: List<String> = emptyList(),
    val keywords: List<String> = emptyList(),
    /** Эмулятор многих систем (RetroArch, Lemuroid). */
    val multi: Boolean = false,
)

object Emulators {
    private val retro = listOf(
        GameSystem.NES, GameSystem.SNES, GameSystem.N64, GameSystem.GB, GameSystem.GBC, GameSystem.GBA,
        GameSystem.NDS, GameSystem.PS1, GameSystem.PSP, GameSystem.MD, GameSystem.SMS, GameSystem.GG,
        GameSystem.SATURN, GameSystem.DC, GameSystem.ARCADE, GameSystem.PCE,
    )

    val all: List<EmulatorDef> = listOf(
        EmulatorDef("retroarch", "RetroArch", retro, listOf("com.retroarch"), listOf("retroarch"), multi = true),
        EmulatorDef(
            "lemuroid", "Lemuroid", Lemuroid.systems,
            listOf(Lemuroid.PACKAGE_PREFIX), listOf("lemuroid"), multi = true,
        ),
        EmulatorDef("ppsspp", "PPSSPP", listOf(GameSystem.PSP), listOf("org.ppsspp."), listOf("ppsspp")),
        EmulatorDef("armsx2", "ARMSX2", listOf(GameSystem.PS2), listOf("com.armsx2"), listOf("armsx2")),
        EmulatorDef(
            "sx2", "NetherSX2 / AetherSX2", listOf(GameSystem.PS2),
            listOf("xyz.aethersx2", "net.pcsx2"), listOf("nethersx2", "aethersx2", "pcsx2", "sx2"),
        ),
        EmulatorDef("duckstation", "DuckStation", listOf(GameSystem.PS1), listOf("com.github.stenzek.duckstation"), listOf("duckstation")),
        EmulatorDef("epsxe", "ePSXe", listOf(GameSystem.PS1), listOf("com.epsxe."), listOf("epsxe")),
        EmulatorDef("melonds", "melonDS", listOf(GameSystem.NDS), listOf("me.magnum.melonds"), listOf("melonds")),
        EmulatorDef("drastic", "DraStic", listOf(GameSystem.NDS), listOf("com.dsemu.drastic"), listOf("drastic")),
        EmulatorDef(
            "citra", "Citra / Azahar", listOf(GameSystem.N3DS),
            listOf("org.citra.", "io.github.lime3ds", "io.github.borked3ds", "org.azahar", "io.github.mandarine3ds"),
            listOf("citra", "lime3ds", "azahar", "mandarine", "borked3ds"),
        ),
        EmulatorDef(
            "switch", "Eden / Yuzu", listOf(GameSystem.SWITCH),
            listOf("dev.eden.", "org.yuzu.", "org.suyu.", "org.sudachi.", "org.citron.", "emu.skyline"),
            listOf("eden_emu", "eden emulator", "yuzu", "suyu", "sudachi", "citron", "strato", "skyline"),
        ),
        EmulatorDef("dolphin", "Dolphin", listOf(GameSystem.GC, GameSystem.WII), listOf("org.dolphinemu."), listOf("dolphin")),
        EmulatorDef("cemu", "Cemu", listOf(GameSystem.WIIU), listOf("info.cemu."), listOf("cemu")),
        EmulatorDef("vita3k", "Vita3K", listOf(GameSystem.VITA), listOf("org.vita3k.", "org.vita3kplus."), listOf("vita3k")),
        EmulatorDef("emucorev", "EmuCoreV", listOf(GameSystem.VITA), listOf("com.sbro.emucorev"), listOf("emucorev")),
        EmulatorDef("ps3", "aPS3e / RPCSX", listOf(GameSystem.PS3), emptyList(), listOf("aps3e", "rpcsx", "rpcs3")),
        EmulatorDef("flycast", "Flycast", listOf(GameSystem.DC), listOf("com.flycast."), listOf("flycast")),
        EmulatorDef("redream", "Redream", listOf(GameSystem.DC), listOf("io.recompiled.redream"), listOf("redream")),
        EmulatorDef("mupen", "Mupen64Plus", listOf(GameSystem.N64), listOf("org.mupen64plusae"), listOf("mupen64")),
        EmulatorDef("yaba", "Yaba Sanshiro", listOf(GameSystem.SATURN), listOf("org.devmiyax."), listOf("yaba")),
        EmulatorDef("myboy", "My Boy!", listOf(GameSystem.GBA), listOf("com.fastemulator.gba"), emptyList()),
        EmulatorDef("myoldboy", "My OldBoy!", listOf(GameSystem.GB, GameSystem.GBC), listOf("com.fastemulator.gbc"), emptyList()),
        EmulatorDef("pizzaboygba", "Pizza Boy GBA", listOf(GameSystem.GBA), listOf("it.dbtecno.pizzaboygba"), listOf("pizza boy gba")),
        EmulatorDef("pizzaboy", "Pizza Boy GBC", listOf(GameSystem.GB, GameSystem.GBC), listOf("it.dbtecno.pizzaboy"), emptyList()),
        EmulatorDef(
            "pc", "GameNative / Winlator", listOf(GameSystem.PC),
            listOf("com.winlator", "app.gamenative", "com.xiaoji.egggame"),
            listOf("gamenative", "winlator", "gamehub", "mobox"),
        ),
        // Эмуляторы Explusalpha (Robert Broglia): в названиях нет слов вроде «Mega Drive», поэтому — по пакету
        EmulatorDef("mdemu", "MD.emu", listOf(GameSystem.MD, GameSystem.SMS, GameSystem.GG), listOf("com.explusalpha.mdemu"), listOf("md.emu")),
        EmulatorDef("nesemu", "NES.emu", listOf(GameSystem.NES), listOf("com.explusalpha.nesemu"), listOf("nes.emu")),
        EmulatorDef("snes9xplus", "Snes9x EX+", listOf(GameSystem.SNES), listOf("com.explusalpha.snes9x"), listOf("snes9x ex")),
        EmulatorDef("gbaemu", "GBA.emu", listOf(GameSystem.GBA), listOf("com.explusalpha.gbaemu"), listOf("gba.emu")),
        EmulatorDef("gbcemu", "GBC.emu", listOf(GameSystem.GB, GameSystem.GBC), listOf("com.explusalpha.gbcemu"), listOf("gbc.emu")),
        EmulatorDef("pceemu", "PCE.emu", listOf(GameSystem.PCE), listOf("com.explusalpha.pceemu"), listOf("pce.emu")),
        EmulatorDef("neoemu", "NEO.emu", listOf(GameSystem.ARCADE), listOf("com.explusalpha.neoemu"), listOf("neo.emu")),
        EmulatorDef("saturnemu", "Saturn.emu", listOf(GameSystem.SATURN), listOf("com.explusalpha.saturnemu"), listOf("saturn.emu")),
        EmulatorDef("joiplay", "JoiPlay", listOf(GameSystem.JOIPLAY), listOf("cyou.joiplay."), listOf("joiplay")),
        EmulatorDef("mame", "MAME4droid", listOf(GameSystem.ARCADE), listOf("com.seleuco.mame4"), listOf("mame4droid")),
        EmulatorDef("scummvm", "ScummVM", listOf(GameSystem.SCUMMVM), listOf("org.scummvm."), listOf("scummvm")),
    )

    /** id эмуляторов, опознанных по названию (нет в списке известных). */
    const val AUTO_PREFIX = "auto:"

    /**
     * Слова в названии или пакете, по которым понятно, для какой консоли эмулятор.
     * Порядок важен: сначала более точные («Wii U» раньше «Wii», «PS2» раньше «PS1»).
     */
    private val systemWords: List<Pair<GameSystem, List<String>>> = listOf(
        GameSystem.PS2 to listOf("ps2", "pcsx2", "sx2", "playstation 2", "playstation2"),
        GameSystem.PS3 to listOf("ps3", "rpcs3", "playstation 3"),
        GameSystem.VITA to listOf("vita", "psvita", "ps vita"),
        GameSystem.PSP to listOf("psp", "playstation portable"),
        GameSystem.PS1 to listOf("ps1", "psx", "psone", "playstation 1"),
        GameSystem.WIIU to listOf("wii u", "wiiu"),
        GameSystem.WII to listOf("wii"),
        GameSystem.GC to listOf("gamecube", "game cube"),
        GameSystem.SWITCH to listOf("switch", "nintendo switch"),
        GameSystem.N3DS to listOf("3ds", "nintendo 3ds"),
        GameSystem.NDS to listOf("nds", "nintendo ds"),
        GameSystem.N64 to listOf("n64", "nintendo 64"),
        GameSystem.GBA to listOf("gba", "game boy advance", "gameboy advance"),
        GameSystem.GBC to listOf("gbc", "game boy color", "gameboy color"),
        GameSystem.GB to listOf("game boy", "gameboy"),
        GameSystem.SNES to listOf("snes", "super nintendo", "super famicom"),
        GameSystem.NES to listOf("nes", "famicom", "dendy"),
        GameSystem.MD to listOf("genesis", "mega drive", "megadrive"),
        GameSystem.SMS to listOf("master system"),
        GameSystem.GG to listOf("game gear", "gamegear"),
        GameSystem.SATURN to listOf("saturn"),
        GameSystem.DC to listOf("dreamcast"),
        GameSystem.PCE to listOf("pc engine", "pcengine", "turbografx"),
        GameSystem.ARCADE to listOf("mame", "arcade"),
    )

    /** Для каких консолей эмулятор — по словам в названии и пакете (пусто — непонятно). */
    fun guessSystems(packageName: String, label: String): List<GameSystem> {
        // «ps2emu» → «ps2 emu»: приставка «emu» часто приклеена к названию консоли
        val text = "${packageName.lowercase().replace('.', ' ').replace('_', ' ')} ${label.lowercase()}".replace("emu", " emu")
        val found = systemWords.filter { (_, words) -> words.any { keywordHit(text, it) } }.map { it.first }
        // «Wii U» — не «Wii», «Game Boy Advance/Color» — не просто «Game Boy»
        return found.filterNot { s ->
            (s == GameSystem.WII && GameSystem.WIIU in found && !keywordHit(text.replace("wii u", "").replace("wiiu", ""), "wii")) ||
                (s == GameSystem.GB && (GameSystem.GBA in found || GameSystem.GBC in found))
        }
    }

    /**
     * Эмулятор, которого нет в списке известных: консоль понятна по названию, и приложение
     * умеет открывать файлы (opensFiles) — значит, это не просто игра с похожим названием.
     */
    fun guess(packageName: String, label: String, opensFiles: Boolean): EmulatorDef? {
        if (!opensFiles) return null
        val systems = guessSystems(packageName, label).takeIf { it.isNotEmpty() } ?: return null
        return EmulatorDef(AUTO_PREFIX + packageName, label, systems, listOf(packageName), multi = systems.size > 3)
    }

    /** Подобрать эмулятор по пакету и названию приложения. */
    fun match(packageName: String, label: String): EmulatorDef? {
        val pkg = packageName.lowercase()
        all.firstOrNull { def -> def.packagePrefixes.any { pkg.startsWith(it) } }?.let { return it }
        val text = "$pkg ${label.lowercase()}"
        return all.firstOrNull { def -> def.keywords.any { keywordHit(text, it) } }
    }

    /** Слово должно стоять отдельно, чтобы «eden» не ловился внутри других слов. */
    private fun keywordHit(text: String, keyword: String): Boolean {
        var from = 0
        while (true) {
            val i = text.indexOf(keyword, from)
            if (i < 0) return false
            val before = if (i == 0) ' ' else text[i - 1]
            val afterIdx = i + keyword.length
            val after = if (afterIdx >= text.length) ' ' else text[afterIdx]
            if (!before.isLetterOrDigit() && !after.isLetter()) return true
            from = i + 1
        }
    }
}
