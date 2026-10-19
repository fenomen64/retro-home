package com.retrohome.launcher.data

/**
 * Журнал загрузки обложек и описаний для отчёта «Диагностика»: что искалось, что нашлось
 * и почему нет. Только в памяти, последние строки; ни почты, ни ключей сюда не пишем.
 */
object DiagLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)

    fun add(line: String) {
        val t = synchronized(fmt) { fmt.format(java.util.Date()) }
        synchronized(lines) {
            lines.addLast("$t $line")
            while (lines.size > MAX) lines.removeFirst()
        }
    }

    /** Ошибка программы (не сети): класс и сообщение. */
    fun error(where: String, e: Throwable) = add("$where — ОШИБКА ${e.javaClass.simpleName}: ${e.message?.take(200)}")

    fun snapshot(): List<String> = synchronized(lines) { lines.toList() }

    fun clear() = synchronized(lines) { lines.clear() }
}
