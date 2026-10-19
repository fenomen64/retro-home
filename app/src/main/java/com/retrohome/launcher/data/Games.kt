package com.retrohome.launcher.data

import kotlinx.serialization.Serializable

/** Игра, найденная в папке ROMs. key — стабильный идентификатор для недавних/избранного. */
@Serializable
data class Game(
    /** Идентификатор документа в выбранной папке (SAF) или путь — для тестов. */
    val id: String,
    /** Обычный путь к файлу, нужен эмуляторам вроде RetroArch. */
    val path: String,
    val system: GameSystem,
    val title: String,
    /** Метки из имени файла: регион, версия — «USA», «Rev 1». */
    val tags: List<String> = emptyList(),
    /** Папка внутри ROMs, из которой взята игра. */
    val folder: String,
    /** Все диски многодисковой игры (первый — тот же файл, что id/path). */
    val discs: List<Disc> = emptyList(),
    /** Номер игры для эмулятора, который запускает её по номеру (Vita: «PCSE00120»). */
    val titleId: String? = null,
) {
    val key: String get() = GAME_PREFIX + id
    val fileName: String get() = path.substringAfterLast('/')

    companion object {
        const val GAME_PREFIX = "game:"
    }
}

@Serializable
data class Disc(val id: String, val path: String, val number: Int)

fun isGameKey(key: String) = key.startsWith(Game.GAME_PREFIX)

/** Как называются папки с играми для каждой системы (сравниваем без регистра, пробелов и знаков). */
object FolderNames {
    private val aliases: Map<GameSystem, List<String>> = mapOf(
        GameSystem.NES to listOf("nes", "famicom", "fc", "dendy", "nintendoentertainmentsystem"),
        GameSystem.SNES to listOf("snes", "sfc", "superfamicom", "supernintendo", "supernes"),
        GameSystem.N64 to listOf("n64", "nintendo64"),
        GameSystem.GC to listOf("gc", "gamecube", "ngc", "nintendogamecube"),
        GameSystem.WII to listOf("wii", "nintendowii"),
        GameSystem.WIIU to listOf("wiiu", "nintendowiiu"),
        GameSystem.SWITCH to listOf("switch", "ns", "nsw", "nintendoswitch"),
        GameSystem.GB to listOf("gb", "gameboy", "nintendogameboy"),
        GameSystem.GBC to listOf("gbc", "gameboycolor", "gameboycolour"),
        GameSystem.GBA to listOf("gba", "gameboyadvance"),
        GameSystem.NDS to listOf("nds", "ds", "nintendods"),
        GameSystem.N3DS to listOf("3ds", "n3ds", "nintendo3ds"),
        GameSystem.PS1 to listOf("psx", "ps1", "ps", "playstation", "playstation1", "sonyplaystation"),
        GameSystem.PS2 to listOf("ps2", "playstation2", "sonyplaystation2"),
        GameSystem.PS3 to listOf("ps3", "playstation3"),
        GameSystem.PSP to listOf("psp", "playstationportable"),
        GameSystem.VITA to listOf("psvita", "vita", "psv"),
        GameSystem.MD to listOf("megadrive", "genesis", "md", "segagenesis", "segamegadrive", "gen"),
        GameSystem.SMS to listOf("mastersystem", "sms", "segamastersystem"),
        GameSystem.GG to listOf("gamegear", "gg", "segagamegear"),
        GameSystem.SATURN to listOf("saturn", "segasaturn", "ss"),
        GameSystem.DC to listOf("dreamcast", "dc", "segadreamcast"),
        GameSystem.ARCADE to listOf("arcade", "mame", "fbneo", "fba", "finalburn", "neogeo", "cps1", "cps2", "cps3"),
        GameSystem.PCE to listOf("pcengine", "pce", "tg16", "turbografx16", "turbografx"),
        GameSystem.PC to listOf("pc", "windows", "win", "steam"),
        GameSystem.JOIPLAY to listOf("joiplay", "rpgmaker", "renpy"),
        GameSystem.SCUMMVM to listOf("scummvm"),
    )

    private val byAlias: Map<String, GameSystem> =
        aliases.flatMap { (sys, names) -> names.map { it to sys } }.toMap()

    fun normalize(name: String) = name.lowercase().filter { it.isLetterOrDigit() }

    fun systemFor(folderName: String): GameSystem? = byAlias[normalize(folderName)]
}

/** Какие файлы считаются играми для системы. */
object GameFiles {
    private val disc = setOf("cue", "chd", "iso", "m3u", "ccd", "mds", "img", "pbp", "ecm")
    private val retroArchive = setOf("zip", "7z")

    val extensions: Map<GameSystem, Set<String>> = mapOf(
        GameSystem.NES to setOf("nes", "fds", "unf", "unif") + retroArchive,
        GameSystem.SNES to setOf("sfc", "smc", "fig", "swc", "bs") + retroArchive,
        GameSystem.N64 to setOf("n64", "z64", "v64") + retroArchive,
        GameSystem.GC to setOf("iso", "gcm", "gcz", "rvz", "ciso", "dol", "m3u", "nkit.iso"),
        GameSystem.WII to setOf("iso", "wbfs", "rvz", "gcz", "wad", "ciso", "wia"),
        GameSystem.WIIU to setOf("wua", "wud", "wux", "rpx"),
        GameSystem.SWITCH to setOf("nsp", "xci", "nca", "nro"),
        GameSystem.GB to setOf("gb") + retroArchive,
        GameSystem.GBC to setOf("gbc", "gb") + retroArchive,
        GameSystem.GBA to setOf("gba") + retroArchive,
        GameSystem.NDS to setOf("nds", "dsi") + retroArchive,
        GameSystem.N3DS to setOf("3ds", "cci", "cia", "cxi", "3dsx", "app", "z3dsx", "zcci", "zcxi"),
        GameSystem.PS1 to disc + setOf("exe"),
        GameSystem.PS2 to setOf("iso", "chd", "cso", "zso", "gz", "mdf", "nrg", "img", "cue", "elf"),
        GameSystem.PS3 to setOf("iso"),
        GameSystem.PSP to setOf("iso", "cso", "chd", "pbp", "elf", "zso"),
        // .psvita — заглушка ES-DE с номером игры внутри; сама игра установлена в Vita3K
        GameSystem.VITA to setOf("psvita", "vpk", "zip"),
        GameSystem.MD to setOf("md", "gen", "smd", "bin", "sgd", "68k") + retroArchive,
        GameSystem.SMS to setOf("sms") + retroArchive,
        GameSystem.GG to setOf("gg") + retroArchive,
        GameSystem.SATURN to setOf("cue", "chd", "ccd", "iso", "mds", "m3u"),
        GameSystem.DC to setOf("cdi", "chd", "gdi", "cue", "m3u"),
        GameSystem.ARCADE to retroArchive,
        GameSystem.PCE to setOf("pce", "cue", "chd", "ccd", "sgx") + retroArchive,
        GameSystem.SCUMMVM to setOf("scummvm"),
    )

    /** Папки внутри системы, где лежат не игры. */
    val ignoredDirs = setOf(
        "media", "images", "image", "covers", "boxart", "screenshots", "manuals", "videos",
        "saves", "save", "states", "savestates", "bios", "system", "downloaded_media", "cheats", "textures",
    )

    /** Расширение с учётом двойных вроде «.nkit.iso». */
    fun extension(fileName: String): String {
        val lower = fileName.lowercase()
        if (lower.endsWith(".nkit.iso")) return "nkit.iso"
        return lower.substringAfterLast('.', "")
    }

    fun accepts(system: GameSystem, fileName: String): Boolean {
        if (fileName.startsWith(".")) return false
        val ext = extension(fileName)
        return ext.isNotEmpty() && ext in (extensions[system] ?: emptySet())
    }
}

/** Разобранное имя файла: чистое название, метки в скобках и номер диска. */
data class ParsedTitle(val title: String, val tags: List<String>, val disc: Int?)

/**
 * Красивое название из имени файла.
 * Понимает вложенные скобки, двойные расширения (.gba.zip), пометки дисков (CD1, disk 2),
 * мусорные слова (rus, undub, v1.01), ID игр Switch и распространённые сокращения (ff7, mgs).
 */
object TitleParser {
    private val romExts: Set<String> by lazy {
        GameFiles.extensions.values.flatten().toSet() - setOf("zip", "7z") + setOf("bin", "img", "smc", "sfc")
    }
    private val discRe = Regex("""(?i)[\s\-_.,]*\b(?:disc|disk|cd|диск)\s*[-_#]?\s*(\d{1,2})\b""")
    private val versionRe = Regex("""(?i)\bv\d+(?:\.\d+)*[a-z]?\b""")
    private val revRe = Regex("""(?i)\brev\s*[\w.]+\b""")
    private val hexIdRe = Regex("""\b[0-9A-Fa-f]{16}\b""")
    private val noiseWords = setOf("rus", "russian", "eng", "undub", "fixed", "translated", "hack", "unl", "repack")
    private val rusWords = setOf("rus", "russian")

    /** Сокращения, которыми часто называют файлы. */
    private val abbreviations = mapOf(
        "ff1" to "Final Fantasy", "ff2" to "Final Fantasy II", "ff3" to "Final Fantasy III",
        "ff4" to "Final Fantasy IV", "ff5" to "Final Fantasy V", "ff6" to "Final Fantasy VI",
        "ff7" to "Final Fantasy VII", "ff8" to "Final Fantasy VIII", "ff9" to "Final Fantasy IX",
        "ff10" to "Final Fantasy X", "ff12" to "Final Fantasy XII", "ffvii" to "Final Fantasy VII",
        "ffviii" to "Final Fantasy VIII", "ffix" to "Final Fantasy IX", "ffx" to "Final Fantasy X",
        "fft" to "Final Fantasy Tactics", "ffta" to "Final Fantasy Tactics Advance",
        "mgs" to "Metal Gear Solid", "mgs1" to "Metal Gear Solid", "mgs2" to "Metal Gear Solid 2",
        "mgs3" to "Metal Gear Solid 3", "bof" to "Breath of Fire", "bof1" to "Breath of Fire",
        "bof2" to "Breath of Fire II", "bof3" to "Breath of Fire III", "bof4" to "Breath of Fire IV",
        "bof5" to "Breath of Fire V", "re1" to "Resident Evil", "re2" to "Resident Evil 2", "re3" to "Resident Evil 3",
        "re4" to "Resident Evil 4", "sotn" to "Castlevania - Symphony of the Night", "kh" to "Kingdom Hearts",
        "kh1" to "Kingdom Hearts", "kh2" to "Kingdom Hearts II", "gow" to "God of War", "gow1" to "God of War",
        "gow2" to "God of War II", "dq8" to "Dragon Quest VIII", "sh1" to "Silent Hill", "sh2" to "Silent Hill 2",
        "sh3" to "Silent Hill 3", "loz" to "The Legend of Zelda", "smw" to "Super Mario World",
        "sm64" to "Super Mario 64", "ctr" to "Crash Team Racing", "mm" to "Mega Man",
    )

    fun parse(fileName: String): Pair<String, List<String>> = full(fileName).let { it.title to it.tags }

    fun full(fileName: String): ParsedTitle {
        var base = fileName
        val ext = GameFiles.extension(fileName)
        if (ext.isNotEmpty() && base.lowercase().endsWith(".$ext")) base = base.dropLast(ext.length + 1)
        // «Игра.gba.zip» — внутри архива ещё одно расширение
        val inner = base.substringAfterLast('.', "").lowercase()
        if (inner.isNotEmpty() && inner in romExts && base.length > inner.length + 1) base = base.dropLast(inner.length + 1)

        // Скобки с учётом вложенности: снаружи — название, внутри — метки
        val title = StringBuilder()
        val tags = ArrayList<String>()
        val cur = StringBuilder()
        var depth = 0
        for (ch in base) {
            when (ch) {
                '(', '[', '{' -> { if (depth > 0) cur.append(ch); depth++ }
                ')', ']', '}' -> when {
                    depth == 0 -> {}
                    depth == 1 -> { depth = 0; cur.toString().trim().takeIf { it.isNotEmpty() }?.let(tags::add); cur.clear() }
                    else -> { depth--; cur.append(ch) }
                }
                else -> if (depth > 0) cur.append(ch) else title.append(ch)
            }
        }
        if (depth > 0) cur.toString().trim().takeIf { it.isNotEmpty() }?.let(tags::add)

        var t = title.toString().replace('_', ' ')
        var disc: Int? = null
        discRe.find(t)?.let { m -> disc = m.groupValues[1].toIntOrNull(); t = t.removeRange(m.range) }
        tags.removeAll { tag ->
            val m = discRe.find(" $tag")
            if (m != null && m.value.trim().length >= tag.length - 1) { if (disc == null) disc = m.groupValues[1].toIntOrNull(); true } else false
        }
        t = t.replace(hexIdRe, " ").replace(versionRe, " ").replace(revRe, " ")
        val words = t.split(Regex("""\s+""")).filter { it.isNotEmpty() }
        if (words.any { it.lowercase().trim('-', '.') in rusWords }) tags += "RUS"
        t = words.filter { it.lowercase().trim('-', '.', ',') !in noiseWords }.joinToString(" ")
        t = t.trim(' ', '-', '_', '.', ',', ':').replace(Regex("""\s+"""), " ").replace(Regex("""\s+-\s*$"""), "")

        // «Legend of Zelda, The» -> «The Legend of Zelda»
        Regex("""^(.*?), (The|A|An)(\s-\s.*|:.*)?$""").find(t)?.let { m ->
            val (main, art, rest) = m.destructured
            t = "$art $main$rest"
        }
        abbreviations[t.lowercase().replace(" ", "")]?.let { t = it }
        if (t.isEmpty()) t = base.trim()
        return ParsedTitle(t, tags, disc)
    }

    private val regionCodes = mapOf(
        "u" to "USA", "e" to "Europe", "j" to "Japan", "w" to "World", "r" to "Russia",
        "ue" to "USA, Europe", "eu" to "Europe", "ju" to "Japan, USA", "uj" to "USA, Japan",
    )

    /** Метки для показа: регион, язык; служебный мусор убирается. */
    fun displayTags(tags: List<String>): List<String> = tags.mapNotNull { tag ->
        val l = tag.lowercase()
        when {
            "rus" in l -> "RUS"
            l in regionCodes -> regionCodes[l]
            tag == "!" || hexIdRe.matches(tag) || versionRe.matches(tag) || VitaGames.isTitleId(tag) -> null
            Regex("""\d{1,2}\.\d{1,2}\.\d{2,4}""").containsMatchIn(tag) -> null
            tag.length > 18 -> null
            else -> tag
        }
    }.distinct().take(2)

    /** Регионы из меток для подбора обложки: «U» -> usa, «USA, Europe» -> usa, europe. */
    fun regionTokens(tags: List<String>): Set<String> = tags.flatMap { tag ->
        val l = tag.lowercase().trim()
        (regionCodes[l]?.lowercase() ?: l).split(',').map { it.trim() }
    }.toSet()
}

/** Разбор .cue и .m3u: какие файлы — части другой игры и сами по себе в список не идут. */
object DiscSets {
    private val cueFile = Regex("""(?i)^\s*FILE\s+"([^"]+)"""")
    private val cueFileBare = Regex("""(?i)^\s*FILE\s+(\S+)""")

    fun cueTracks(text: String): List<String> = text.lineSequence().mapNotNull { line ->
        cueFile.find(line)?.groupValues?.get(1) ?: cueFileBare.find(line)?.groupValues?.get(1)
    }.map { it.substringAfterLast('/').substringAfterLast('\\') }.toList()

    fun m3uEntries(text: String): List<String> = text.lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .map { it.substringAfterLast('/').substringAfterLast('\\') }
        .toList()
}
