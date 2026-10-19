package com.retrohome.launcher.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Все настройки и раскладка лаунчера в одном JSON-файле в личной папке приложения.
 * Файл переживает обновления APK; пропадает только при удалении лаунчера.
 */
class Store(private val file: File, scope: CoroutineScope) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val _data = MutableStateFlow(load())
    val data: StateFlow<LauncherData> = _data
    private val saveRequests = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch(Dispatchers.IO) {
            for (r in saveRequests) save(_data.value)
        }
    }

    fun update(transform: (LauncherData) -> LauncherData) {
        _data.update(transform)
        saveRequests.trySend(Unit)
    }

    private fun load(): LauncherData = try {
        if (file.exists()) json.decodeFromString(LauncherData.serializer(), file.readText()) else LauncherData()
    } catch (e: Exception) {
        // Повреждённый файл не должен ломать запуск; оставляем копию для разбора.
        file.copyTo(File(file.parentFile, file.name + ".broken"), overwrite = true)
        LauncherData()
    }

    private fun save(d: LauncherData) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(LauncherData.serializer(), d))
        tmp.renameTo(file)
    }
}
