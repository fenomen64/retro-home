package com.retrohome.launcher.data

import kotlinx.serialization.Serializable

/** Источник файлов: папка, выбранная через системное окно (SAF), или обычная папка в тестах. */
interface FileSource {
    data class Node(val id: String, val name: String, val isDir: Boolean)

    fun root(): Node?
    fun children(node: Node): List<Node>
    fun readText(node: Node, maxBytes: Int = 64 * 1024): String?
    fun pathOf(node: Node): String
}

/** Что нашлось в каждой папке внутри ROMs. */
@Serializable
data class FolderInfo(
    val name: String,
    /** Система по названию папки. */
    val detected: GameSystem? = null,
    /** Система с учётом ручного назначения; null — папка не используется. */
    val system: GameSystem? = null,
    val gameCount: Int = 0,
)

@Serializable
data class ScanResult(
    val games: List<Game> = emptyList(),
    val folders: List<FolderInfo> = emptyList(),
    val time: Long = 0,
)

/** Значение ручного назначения «не использовать папку». */
const val FOLDER_IGNORE = "IGNORE"

object Scanner {
    private const val MAX_DEPTH = 2
    private val switchTitleId = Regex("(?i)\\b01[0-9a-f]{14}\\b")
    /** Файлы BIOS (SCPH1001.bin, «PSX BIOS.bin», ps2-0230a-20080220.bin…) — не игры. */
    private val biosRe = Regex("(?i)^(scph[-_ ]?\\d{3,5}|ps[12x]?[-_ ]?\\d{4}[a-z]?-\\d{8})|(?<![a-z0-9])bios(?![a-z0-9])")
    fun isBios(fileName: String) = biosRe.containsMatchIn(fileName)
    private val extraTrack = Regex("(?i)track\\s*0*([2-9]|[1-9]\\d+)")

    fun scan(src: FileSource, overrides: Map<String, String>, now: Long = System.currentTimeMillis()): ScanResult {
        val root = src.root() ?: return ScanResult(time = now)
        val games = ArrayList<Game>()
        val folders = ArrayList<FolderInfo>()
        for (dir in src.children(root).filter { it.isDir && !it.name.startsWith(".") }.sortedBy { it.name.lowercase() }) {
            val detected = FolderNames.systemFor(dir.name)
            val system = when (val o = overrides[dir.name]) {
                null -> detected
                FOLDER_IGNORE -> null
                else -> runCatching { GameSystem.valueOf(o) }.getOrNull()
            }
            val found = if (system != null && GameFiles.extensions.containsKey(system)) {
                scanSystemDir(src, dir, system, dir.name, 0).let { if (system == GameSystem.VITA) VitaGames.dedupe(it) else it }
            } else emptyList()
            games += found
            folders += FolderInfo(dir.name, detected, system, found.size)
        }
        return ScanResult(games.sortedBy { it.title.lowercase() }, folders, now)
    }

    private fun scanSystemDir(src: FileSource, dir: FileSource.Node, system: GameSystem, folder: String, depth: Int): List<Game> {
        val kids = src.children(dir)
        val files = kids.filter { !it.isDir && GameFiles.accepts(system, it.name) }
        // Файлы, которые являются частью другой игры: дорожки .cue и диски из .m3u
        val hidden = HashSet<String>()
        for (f in files) {
            when (GameFiles.extension(f.name)) {
                "m3u" -> src.readText(f)?.let { hidden += DiscSets.m3uEntries(it).map(String::lowercase) }
                "cue" -> src.readText(f)?.let { hidden += DiscSets.cueTracks(it).map(String::lowercase) }
            }
        }
        val result = ArrayList<Game>()
        // Диски одной игры («… CD1», «… (Disc 2)») объединяются в одну карточку
        val discGroups = LinkedHashMap<String, MutableList<Pair<Game, Int>>>()
        for (f in files) {
            if (f.name.lowercase() in hidden) continue
            // Switch: обновления (ID на …800) и дополнения — не отдельные игры
            if (system == GameSystem.SWITCH) {
                val tid = switchTitleId.find(f.name)?.value
                if (tid != null && !tid.endsWith("000")) continue
            }
            if (isBios(f.name)) continue
            val p = TitleParser.full(f.name)
            // Образ из нескольких дорожек без .cue: игра — первая дорожка, остальные (музыка) пропускаем
            if (GameFiles.extension(f.name) == "bin" && p.tags.any { extraTrack.matches(it.trim()) }) continue
            val titleId = if (system == GameSystem.VITA) VitaGames.titleId(f.name, if (GameFiles.extension(f.name) == "psvita") src.readText(f, 256) else null) else null
            val g = Game(f.id, src.pathOf(f), system, p.title, p.tags, folder, titleId = titleId)
            if (p.disc != null) discGroups.getOrPut(p.title.lowercase()) { ArrayList() } += g to p.disc
            else result += g
        }
        for (group in discGroups.values) {
            val sorted = group.sortedBy { it.second }
            val first = sorted.first().first
            result += first.copy(
                discs = if (sorted.size > 1) sorted.map { (g, n) -> Disc(g.id, g.path, n) } else emptyList(),
            )
        }
        if (depth < MAX_DEPTH) {
            for (sub in kids.filter { it.isDir && !it.name.startsWith(".") && it.name.lowercase() !in GameFiles.ignoredDirs }) {
                result += scanSystemDir(src, sub, system, folder, depth + 1)
            }
        }
        return result
    }
}

/**
 * Игры PS Vita. Vita3K запускает только установленные в нём игры — по номеру вида PCSE00120.
 * Номер берётся из заглушки ES-DE (файл .psvita с номером внутри) или из имени файла «… [PCSE00120].vpk».
 */
object VitaGames {
    private val idRe = Regex("""\b([A-Z]{4}\d{5})\b""")

    fun titleId(fileName: String, content: String?): String? =
        content?.let { idRe.find(it.trim().uppercase())?.groupValues?.get(1) } ?: idRe.find(fileName.uppercase())?.groupValues?.get(1)

    fun isTitleId(s: String) = idRe.matches(s.trim())

    /** Рядом с заглушкой часто лежит и сам файл игры (для установки) — показываем игру один раз, по заглушке. */
    fun dedupe(games: List<Game>): List<Game> {
        val stubs = games.filter { GameFiles.extension(it.fileName) == "psvita" }
        if (stubs.isEmpty()) return games
        val ids = stubs.mapNotNull { it.titleId }.toSet()
        val titles = stubs.map { it.title.lowercase() }.toSet()
        return games.filter { g ->
            GameFiles.extension(g.fileName) == "psvita" || (g.titleId !in ids && g.title.lowercase() !in titles)
        }
    }
}

/** Обычная папка на диске — для тестов и отладки. */
class JavaFileSource(private val rootDir: java.io.File) : FileSource {
    override fun root() = if (rootDir.isDirectory) FileSource.Node(rootDir.absolutePath, rootDir.name, true) else null
    override fun children(node: FileSource.Node) =
        java.io.File(node.id).listFiles().orEmpty().map { FileSource.Node(it.absolutePath, it.name, it.isDirectory) }
    override fun readText(node: FileSource.Node, maxBytes: Int) = runCatching {
        java.io.File(node.id).inputStream().use { String(it.readNBytes(maxBytes)) }
    }.getOrNull()
    override fun pathOf(node: FileSource.Node) = node.id
}
