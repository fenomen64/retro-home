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
                scanSystemDir(src, dir, system, dir.name, 0)
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
            val p = TitleParser.full(f.name)
            val g = Game(f.id, src.pathOf(f), system, p.title, p.tags, folder)
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
