package com.retrohome.launcher.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/** id документа SAF («1234-ABCD:ROMs/psp/x.iso») в обычный путь. */
fun docIdToPath(docId: String): String {
    val vol = docId.substringBefore(':')
    val rel = docId.substringAfter(':', "")
    val base = if (vol.equals("primary", ignoreCase = true)) "/storage/emulated/0" else "/storage/$vol"
    return if (rel.isEmpty()) base else "$base/$rel"
}

/** Папка, выбранная пользователем в системном окне. Доступ сохраняется навсегда. */
class SafFileSource(private val context: Context, private val tree: Uri) : FileSource {
    private val resolver get() = context.contentResolver

    override fun root(): FileSource.Node? = runCatching {
        val id = DocumentsContract.getTreeDocumentId(tree)
        FileSource.Node(id, id.substringAfterLast('/').substringAfter(':'), true)
    }.getOrNull()

    override fun children(node: FileSource.Node): List<FileSource.Node> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, node.id)
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
        )
        val out = ArrayList<FileSource.Node>()
        runCatching {
            resolver.query(uri, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0) ?: continue
                    val name = c.getString(1) ?: continue
                    out += FileSource.Node(id, name, c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR)
                }
            }
        }
        return out
    }

    override fun readText(node: FileSource.Node, maxBytes: Int): String? = runCatching {
        resolver.openInputStream(documentUri(node.id))?.use { String(it.readNBytes(maxBytes)) }
    }.getOrNull()

    override fun pathOf(node: FileSource.Node) = docIdToPath(node.id)

    fun documentUri(docId: String): Uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
}

/** Найденные игры: хранятся в кэше, чтобы список появлялся сразу при запуске. */
class GameLibrary(private val context: Context, private val scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cacheFile = File(context.filesDir, "games.json")
    private val _result = MutableStateFlow(loadCache())
    val result: StateFlow<ScanResult> = _result
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning
    private var job: Job? = null

    fun hasAccess(tree: String?): Boolean {
        if (tree == null) return false
        val uri = Uri.parse(tree)
        return context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    }

    fun rescan(tree: String?, overrides: Map<String, String>) {
        if (!hasAccess(tree)) {
            _result.value = ScanResult()
            return
        }
        job?.cancel()
        job = scope.launch(Dispatchers.IO) {
            _scanning.value = true
            try {
                val r = Scanner.scan(SafFileSource(context, Uri.parse(tree)), overrides)
                _result.value = r
                runCatching { cacheFile.writeText(json.encodeToString(ScanResult.serializer(), r)) }
            } finally {
                _scanning.value = false
            }
        }
    }

    fun documentUri(tree: String, game: Game): Uri = SafFileSource(context, Uri.parse(tree)).documentUri(game.id)

    private fun loadCache(): ScanResult = runCatching {
        if (cacheFile.exists()) json.decodeFromString(ScanResult.serializer(), cacheFile.readText()) else ScanResult()
    }.getOrElse { ScanResult() }
}
