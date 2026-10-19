package com.retrohome.launcher.ui

import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged

/**
 * Память экрана со списком: где он был прокручен и какая строка была выбрана.
 * Возвращаешься на экран (из раздела, из вложенного экрана) — курсор встаёт туда же, а не в начало.
 */
class FocusMemory {
    val list = LazyListState()
    /** id строки, на которой стоял курсор. */
    var last: String? = null
}

val LocalFocusMemory = compositionLocalOf<FocusMemory?> { null }

/** Звуки навигации на экранах-списках (в консольном режиме; на телефоне — тишина). */
val LocalScreenSounds = staticCompositionLocalOf { false }

/**
 * Строка запоминается под этим id; если на ней стоял курсор до ухода с экрана —
 * при возвращении курсор снова встаёт на неё.
 */
fun Modifier.rememberFocus(id: String): Modifier = composed {
    val mem = LocalFocusMemory.current ?: return@composed this
    val req = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (mem.last == id) runCatching { req.requestFocus() } }
    this.focusRequester(req).onFocusChanged { if (it.isFocused) mem.last = id }
}

/**
 * Разделы настроек и память каждого из них. Живёт выше экрана настроек, поэтому переживает
 * переход во вложенные экраны (эмуляторы, папки…) и обратно.
 */
class SettingsNav {
    var section by mutableStateOf<String?>(null)
    private val memories = HashMap<String, FocusMemory>()
    fun memory(key: String?): FocusMemory = memories.getOrPut(key ?: "") { FocusMemory() }

    /** Настройки открыли заново (из меню) — начинаем с начала. */
    fun reset() {
        section = null
        memories.clear()
    }
}

/**
 * Когда на экране последний раз нажимали крестовину. Курсор, пришедший на строку сразу после
 * нажатия, — это перемещение (звучит); курсор, поставленный при открытии экрана или раздела, — нет.
 */
class NavKeys {
    var at: Long = Long.MIN_VALUE / 2
    fun recent() = uptimeMs() - at < 400
}

val LocalNavKeys = staticCompositionLocalOf<NavKeys?> { null }

/** Время для «тихого» первого фокуса: курсор, поставленный при открытии экрана, не звучит. */
internal fun uptimeMs() = SystemClock.uptimeMillis()
