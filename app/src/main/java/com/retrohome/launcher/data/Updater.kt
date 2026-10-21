package com.retrohome.launcher.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Обновление лаунчера прямо из него: новая версия берётся из релизов GitHub
 * (тех же, что публикуются после каждого обновления), скачивается, проверяется подпись,
 * и Android показывает обычное окно установки поверх текущей версии.
 */
object UpdateCheck {
    const val REPO = "fenomen64/retro-home"
    /** Последние выпуски одним запросом: из них — новейший и описания всех версий новее установленной. */
    const val LIST_URL = "https://api.github.com/repos/$REPO/releases?per_page=30"
    const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"

    /**
     * Автопроверка — примерно раз в час (не чаще, чем раз в 55 минут). Один запрос к api.github.com —
     * далеко от лимита GitHub (60 запросов в час без входа).
     */
    const val INTERVAL_MS = 110_000L

    data class Release(
        val version: String,
        val apkUrl: String,
        val size: Long,
        val notes: String,
        val page: String,
        /** Все версии новее установленной, от старой к новой: версия → что в ней нового. */
        val history: List<Pair<String, String>> = emptyList(),
    ) {
        /** Что нового — по версиям; если списка нет, то хотя бы о последней. */
        val changes: List<Pair<String, String>> get() = history.ifEmpty { listOf(version to notes) }
    }

    /** Один выпуск GitHub → версия и ссылка на APK (null — в выпуске нет APK). */
    fun parse(json: String): Release? = parse(JSONObject(json))

    /**
     * Список выпусков GitHub → новейший выпуск с APK; в history — все версии новее current
     * (до новейшей включительно), от старой к новой.
     */
    fun parseList(json: String, current: String): Release? {
        val arr = org.json.JSONArray(json)
        val all = (0 until arr.length()).mapNotNull { runCatching { parse(arr.getJSONObject(it)) }.getOrNull() }
        val latest = all.maxWithOrNull { a, b -> compare(a.version, b.version) } ?: return null
        val history = all
            .filter { isNewer(it.version, current) && compare(it.version, latest.version) <= 0 }
            .distinctBy { it.version }
            .sortedWith { a, b -> compare(a.version, b.version) }
            .map { it.version to it.notes }
        return latest.copy(history = history)
    }

    private fun parse(o: JSONObject): Release? {
        if (o.optBoolean("draft") || o.optBoolean("prerelease")) return null
        val version = o.optString("tag_name").removePrefix("v").trim()
        if (version.isEmpty()) return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.optString("name").endsWith(".apk", ignoreCase = true)) {
                return Release(
                    version = version,
                    apkUrl = a.optString("browser_download_url"),
                    size = a.optLong("size"),
                    notes = o.optString("body").trim(),
                    page = o.optString("html_url").ifEmpty { RELEASES_PAGE },
                )
            }
        }
        return null
    }

    /** remote новее current? Сравнение по числам: 0.10 новее 0.9, 0.38.1 новее 0.38. */
    fun isNewer(remote: String, current: String): Boolean = compare(remote, current) > 0

    fun compare(x: String, y: String): Int {
        fun parts(v: String) = v.removePrefix("v").split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val r = parts(x)
        val c = parts(y)
        for (i in 0 until maxOf(r.size, c.size)) {
            val a = r.getOrElse(i) { 0 }
            val b = c.getOrElse(i) { 0 }
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    /** Заметки к релизу без разметки Markdown — для показа на экране. */
    fun plainNotes(md: String): String = md.lines().joinToString("\n") { line ->
        line.replace(Regex("""\*\*(.+?)\*\*"""), "$1")
            .replace(Regex("""`([^`]*)`"""), "$1")
            .replace(Regex("""^\s*[-*]\s+"""), "• ")
            .replace(Regex("""^#+\s*"""), "")
    }.trim()
}

/** Что сейчас с обновлением. */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latest: String) : UpdateState
    data class Available(val release: UpdateCheck.Release) : UpdateState
    data class Downloading(val release: UpdateCheck.Release, val progress: Float) : UpdateState
    /** Нужно разрешить лаунчеру устанавливать приложения (один раз). */
    data class NeedPermission(val release: UpdateCheck.Release) : UpdateState
    data class Installing(val release: UpdateCheck.Release) : UpdateState
    data class Failed(val reason: Reason, val release: UpdateCheck.Release? = null, val detail: String? = null) : UpdateState

    enum class Reason { NETWORK, RATE_LIMIT, NO_APK, DOWNLOAD, SIGNATURE, INSTALL }
}

class Updater(private val context: Context, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    val currentVersion: String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull() ?: "0"

    init {
        // Новая версия, найденная раньше (лаунчер с тех пор перезапускался), — снова «доступна» без запроса в сеть
        restoreKnown()?.let { if (UpdateCheck.isNewer(it.version, currentVersion)) _state.value = UpdateState.Available(it) }
        // Обновились — скачанный файл больше не нужен
        if (_state.value == UpdateState.Idle) File(context.cacheDir, "update").deleteRecursively()
    }

    private fun remember(r: UpdateCheck.Release) {
        val history = org.json.JSONArray()
        r.history.forEach { (v, n) -> history.put(org.json.JSONArray().put(v).put(n)) }
        prefs.edit()
            .putString("known.version", r.version).putString("known.url", r.apkUrl).putLong("known.size", r.size)
            .putString("known.notes", r.notes).putString("known.page", r.page).putString("known.history", history.toString())
            .apply()
    }

    private fun restoreKnown(): UpdateCheck.Release? {
        val v = prefs.getString("known.version", null) ?: return null
        val url = prefs.getString("known.url", null) ?: return null
        val history = runCatching {
            val a = org.json.JSONArray(prefs.getString("known.history", "[]"))
            (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
        }.getOrDefault(emptyList())
            // Часть этих версий могла уже стоять — показываем только то, что новее установленной
            .filter { UpdateCheck.isNewer(it.first, currentVersion) }
        return UpdateCheck.Release(v, url, prefs.getLong("known.size", 0L), prefs.getString("known.notes", "").orEmpty(),
            prefs.getString("known.page", UpdateCheck.RELEASES_PAGE).orEmpty(), history)
    }

    /** Новая версия, о которой уже известно (для меню и строки настроек). */
    fun availableVersion(): String? = when (val s = _state.value) {
        is UpdateState.Available -> s.release.version
        is UpdateState.Downloading -> s.release.version
        is UpdateState.NeedPermission -> s.release.version
        is UpdateState.Installing -> s.release.version
        is UpdateState.Failed -> s.release?.version
        else -> null
    }

    /**
     * Тихая фоновая проверка: не чаще раза в пару минут. Найдено — вызывается onFound
     * (один раз на каждую новую версию, чтобы не надоедать).
     */
    /** true — проверка запущена (onDone вызовется по её окончании); false — сейчас не нужна. */
    fun autoCheck(onDone: () -> Unit = {}, onFound: (String) -> Unit): Boolean {
        val last = prefs.getLong("checkedAt", 0L)
        val now = System.currentTimeMillis()
        // Недавно проверяли — сеть не трогаем
        if (now - last in 0 until UpdateCheck.INTERVAL_MS) return false
        return check(manual = false, onDone = onDone) { version ->
            if (prefs.getString("announced", null) != version) {
                prefs.edit().putString("announced", version).apply()
                onFound(version)
            }
        }
    }

    private var lastLogged: String? = null

    fun check(manual: Boolean = true, onDone: () -> Unit = {}, onFound: (String) -> Unit = {}): Boolean {
        if (job?.isActive == true) return false
        // Идёт скачивание или установка — фоновая проверка не мешает
        val busy = _state.value is UpdateState.Downloading || _state.value is UpdateState.Installing || _state.value is UpdateState.NeedPermission
        if (!manual && busy) return false
        job = scope.launch {
          try {
            val before = _state.value
            // Фоновая проверка незаметна: «Проверяю…» показываем только при ручной
            if (manual) _state.value = UpdateState.Checking
            val result = withContext(Dispatchers.IO) { fetchLatest() }
            prefs.edit().putLong("checkedAt", System.currentTimeMillis()).apply()
            // Фоновая проверка не меняет известную новую версию на ошибку сети
            if (manual || result !is UpdateState.Failed) _state.value = result
            // Фоновые проверки идут часто — в журнал только то, что изменилось
            if (manual || describe(result) != lastLogged) DiagLog.add("обновление: проверка ${if (manual) "вручную" else "в фоне"} → ${describe(result)}")
            lastLogged = describe(result)
            if (result is UpdateState.Available) {
                remember(result.release)
                onFound(result.release.version)
            }
            // Тихая проверка без сети — не пугаем ошибкой, просто ничего не показываем
            if (!manual && result is UpdateState.Failed) _state.value = before.takeIf { it !is UpdateState.Checking } ?: UpdateState.Idle
          } finally {
            onDone()
          }
        }
        return true
    }

    private fun describe(s: UpdateState) = when (s) {
        is UpdateState.Available -> "есть ${s.release.version}"
        is UpdateState.UpToDate -> "актуальная (на GitHub ${s.latest})"
        is UpdateState.Failed -> "ошибка ${s.reason}${s.detail?.let { ": $it" } ?: ""}"
        else -> s.toString()
    }

    /** Последний ответ GitHub и его метка: повторный запрос «изменилось ли что-то» не тратит лимит GitHub. */
    private val cacheFile get() = File(context.filesDir, "releases-cache.json")

    private fun fetchLatest(): UpdateState = try {
        val c = open(UpdateCheck.LIST_URL)
        c.setRequestProperty("Accept", "application/vnd.github+json")
        // Условный запрос: если выпусков не прибавилось, GitHub отвечает «304 не изменилось»,
        // и такие ответы не входят в лимит 60 запросов в час — поэтому проверять можно часто
        val etag = prefs.getString("etag", null)
        if (etag != null && cacheFile.exists()) c.setRequestProperty("If-None-Match", etag)
        when (val code = c.responseCode) {
            200, 304 -> {
                val body = if (code == 304) cacheFile.readText() else c.inputStream.bufferedReader().use { it.readText() }.also { b ->
                    runCatching { cacheFile.writeText(b) }
                    c.getHeaderField("ETag")?.let { prefs.edit().putString("etag", it).apply() }
                }
                val r = UpdateCheck.parseList(body, currentVersion)
                when {
                    r == null -> UpdateState.Failed(UpdateState.Reason.NO_APK)
                    UpdateCheck.isNewer(r.version, currentVersion) -> UpdateState.Available(r)
                    else -> UpdateState.UpToDate(r.version)
                }
            }
            403, 429 -> UpdateState.Failed(UpdateState.Reason.RATE_LIMIT, detail = "HTTP $code")
            else -> UpdateState.Failed(UpdateState.Reason.NETWORK, detail = "HTTP $code")
        }
    } catch (e: Exception) {
        UpdateState.Failed(UpdateState.Reason.NETWORK, detail = e.javaClass.simpleName)
    }

    private fun open(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000
        readTimeout = 30_000
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "RetroHome/$currentVersion (Android launcher; github.com/${UpdateCheck.REPO})")
    }

    private fun apkFile(r: UpdateCheck.Release) = File(File(context.cacheDir, "update"), "RetroHome-${r.version}.apk")

    /** Скачать и установить. Уже скачано — сразу к установке. */
    fun install(r: UpdateCheck.Release) {
        if (job?.isActive == true) return
        job = scope.launch {
            val file = apkFile(r)
            if (!(file.exists() && file.length() == r.size)) {
                _state.value = UpdateState.Downloading(r, 0f)
                val ok = withContext(Dispatchers.IO) { download(r, file) }
                if (!ok) {
                    _state.value = UpdateState.Failed(UpdateState.Reason.DOWNLOAD, r)
                    return@launch
                }
            }
            // Ставим только наш же лаунчер, подписанный нашим же ключом и новее текущего
            val problem = withContext(Dispatchers.IO) { verify(file) }
            if (problem != null) {
                file.delete()
                DiagLog.add("обновление: файл отклонён — $problem")
                _state.value = UpdateState.Failed(UpdateState.Reason.SIGNATURE, r, problem)
                return@launch
            }
            if (!context.packageManager.canRequestPackageInstalls()) {
                _state.value = UpdateState.NeedPermission(r)
                openInstallPermission()
                return@launch
            }
            _state.value = UpdateState.Installing(r)
            val err = withContext(Dispatchers.IO) { runCatching { commit(file) }.exceptionOrNull() }
            if (err != null) _state.value = UpdateState.Failed(UpdateState.Reason.INSTALL, r, err.javaClass.simpleName)
        }
    }

    /** Вернулись из настроек Android: разрешение дали — продолжаем установку. */
    fun onResume() {
        val s = _state.value
        if (s is UpdateState.NeedPermission && context.packageManager.canRequestPackageInstalls()) install(s.release)
    }

    fun openInstallPermission() {
        val i = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    fun openReleasePage(r: UpdateCheck.Release?) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(r?.page ?: UpdateCheck.RELEASES_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(i) }
    }

    private fun download(r: UpdateCheck.Release, file: File): Boolean = try {
        file.parentFile?.mkdirs()
        // Старые скачанные версии не копим
        file.parentFile?.listFiles()?.forEach { if (it != file) it.delete() }
        val part = File(file.path + ".part")
        val c = open(r.apkUrl)
        c.setRequestProperty("Accept", "application/octet-stream")
        if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
        val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
        var done = 0L
        var shown = SystemClock.uptimeMillis()
        c.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    val now = SystemClock.uptimeMillis()
                    if (now - shown > 150 && total > 0) {
                        shown = now
                        _state.value = UpdateState.Downloading(r, (done.toFloat() / total).coerceIn(0f, 1f))
                    }
                }
            }
        }
        if (r.size > 0 && part.length() != r.size) throw IllegalStateException("размер ${part.length()} ≠ ${r.size}")
        part.renameTo(file)
    } catch (e: Exception) {
        DiagLog.add("обновление: не скачалось — ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    /** null — файл годится; иначе — что не так. */
    private fun verify(file: File): String? {
        val pm = context.packageManager
        val archive = pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
            ?: return "файл повреждён"
        if (archive.packageName != context.packageName) return "это другое приложение (${archive.packageName})"
        val mine = pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        if (archive.longVersionCode <= mine.longVersionCode) return "версия не новее установленной"
        val theirs = signers(archive.signingInfo)
        val ours = signers(mine.signingInfo)
        // Не смогли прочитать подпись из файла (бывает на старых Android) — решает сама система:
        // поверх установленного лаунчера она не поставит файл с чужой подписью
        if (theirs.isEmpty()) {
            DiagLog.add("обновление: подпись файла не прочиталась — проверит Android при установке")
            return null
        }
        if (theirs != ours) return "подпись не совпадает"
        return null
    }

    private fun signers(info: android.content.pm.SigningInfo?): Set<String> {
        info ?: return emptySet()
        val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        val md = MessageDigest.getInstance("SHA-256")
        // При ротации ключа история длиннее — сравниваем текущий (последний) ключ
        val list = if (info.hasMultipleSigners()) certs.toList() else listOfNotNull(certs.lastOrNull())
        return list.map { c -> md.digest(c.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        DiagLog.add("обновление: установка запущена")
    }

    /** Ответ системы об установке. */
    internal fun onInstallStatus(status: Int, message: String?) {
        val r = (_state.value as? UpdateState.Installing)?.release
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_SUCCESS -> Unit // процесс сейчас перезапустится
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = r?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            else -> {
                DiagLog.add("обновление: установка не удалась — $status ${message.orEmpty()}")
                _state.value = UpdateState.Failed(UpdateState.Reason.INSTALL, r, message)
            }
        }
    }
}

/** Принимает от системы результат установки и, если нужно, показывает окно подтверждения. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
        }
        (context.applicationContext as? com.retrohome.launcher.LauncherApp)?.controller?.updater
            ?.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }

    companion object {
        const val ACTION = "com.retrohome.launcher.UPDATE_STATUS"
    }
}
