package com.retrohome.launcher.data

import kotlinx.serialization.Serializable

/** Язык интерфейса и описаний. */
enum class Lang { RU, EN }

/** Какой режим показывать. AUTO — по повороту устройства. */
enum class ModeOverride { AUTO, CONSOLE, PHONE }

/** Куда приложение попадает в консольном режиме. */
enum class ConsoleZone { EMULATOR, GAME, NONE }

@Serializable
data class Settings(
    val lang: Lang = Lang.RU,
    val mode: ModeOverride = ModeOverride.AUTO,
    /** true — «подтверждение» на кнопке B (как у Nintendo), «назад» на A. */
    val swapAB: Boolean = false,
    val columns: Int = 5,
    val rows: Int = 6,
    /** Сразу класть значок нового установленного приложения на рабочий стол. */
    val autoAddNew: Boolean = true,
    /** Папка ROMs, выбранная в системном окне (адрес SAF). */
    val romsTree: String? = null,
    /** Сами загружать обложки и описания для новых игр. */
    val autoMeta: Boolean = true,
    /** Набор звуков интерфейса консольного режима: off, soft, retro, crystal, click. */
    val sfxPack: String = "soft",
    val sfxVolume: Int = 70,
    /** Фоновая музыка консольного режима: off, ambient, chiptune, lofi, custom. */
    val music: String = "ambient",
    val musicVolume: Int = 40,
    /** Своя мелодия: адрес файла, выбранного пользователем. */
    val customMusic: String? = null,
    /** Порядок игр на вкладках консолей: name, recent, playtime. */
    val sortGames: String = "name",
    /** Чем листать игры в ряду: dpad — крестовина, triggers — курки L2/R2, both — и тем и другим. */
    val carouselKeys: String = "dpad",
    /** Проверять обновления на GitHub (примерно раз в час, при возвращении на главный экран). */
    val updateCheck: Boolean = true,
    /** Перед игрой с достижениями проверять сервер RetroAchievements и предупреждать уведомлением, если он недоступен. */
    val raCheck: Boolean = false,
    /** A на игре открывает её страницу (false — сразу запускает). */
    val gamePage: Boolean = true,
    /** Тема оформления: night, oled, graphite, light. */
    val theme: String = "night",
    /** Цвет выделения. */
    val accent: String = "coral",
    /** Вкладки консолей окрашиваются в цвет консоли. */
    val tabColors: Boolean = true,
    /** Порядок вкладок консольного режима (id вкладок); новые встают на своё обычное место. */
    val tabOrder: List<String> = emptyList(),
    val hiddenTabs: Set<String> = emptySet(),
    /** Вибрация при навигации: off, light, medium, strong. */
    val haptics: String = "light",
    /** Жесты на рабочем столе: свайп вниз — уведомления, вверх — все приложения. */
    val gestures: Boolean = true,
    /** Набор значков (пакет) или null — обычные значки. */
    val iconPack: String? = null,
    /** Форма значков: system — как в системе, или circle, squircle, rounded, square, teardrop, none. */
    val iconShape: String = "system",
    /** Масштаб консольного интерфейса в процентах; 0 — авто (по размеру экрана). */
    val uiScale: Int = 0,
    /**
     * Почта для связи в запросах к Википедии: так требуют её правила, и с ней лимит запросов
     * 200 в минуту вместо 10. Видит только Википедия.
     */
    val contactEmail: String? = null,
    /** Аккаунт RetroAchievements: логин и личный ключ Web API (ключ уходит только на retroachievements.org). */
    val raUser: String? = null,
    val raKey: String? = null,
)

/**
 * Элемент телефонного рабочего стола.
 * page = -1 означает док, тогда x — номер слота.
 * Если [app] == null, это папка.
 */
@Serializable
data class HomeItem(
    val id: Long,
    val page: Int,
    val x: Int,
    val y: Int,
    val app: String? = null,
    val folderName: String = "",
    val folderApps: List<String> = emptyList(),
    /** Виджет: номер у системы (0 — не виджет), поставщик и размер в клетках. */
    val widgetId: Int = 0,
    val widgetProvider: String? = null,
    val spanX: Int = 1,
    val spanY: Int = 1,
) {
    val isWidget: Boolean get() = widgetId != 0
    val isFolder: Boolean get() = app == null && !isWidget
    val isDock: Boolean get() = page == DOCK

    /** Занимает ли элемент клетку (x, y) своей страницы. */
    fun covers(x: Int, y: Int): Boolean =
        x >= this.x && x < this.x + spanX && y >= this.y && y < this.y + spanY

    companion object {
        const val DOCK = -1
    }
}

@Serializable
data class Recent(val key: String, val time: Long)

/** Сколько играли: всего миллисекунд, сколько раз и когда в последний раз. */
@Serializable
data class PlayStat(val totalMs: Long = 0, val sessions: Int = 0, val last: Long = 0)

/**
 * Запущенная из лаунчера игра. Без доступа к статистике время считается до возврата в лаунчер;
 * с доступом (pkg задан) — только пока эмулятор pkg на экране, counted — до какого момента уже посчитано.
 */
@Serializable
data class PlaySession(
    val key: String,
    val start: Long,
    val pkg: String? = null,
    val counted: Long = start,
    val lastActive: Long = start,
)

@Serializable
data class LauncherData(
    val settings: Settings = Settings(),
    val pageCount: Int = 2,
    val home: List<HomeItem> = emptyList(),
    /** Рабочий стол уже заполнялся стартовым набором. */
    val homeSeeded: Boolean = false,
    /** Все установленные приложения один раз разложены по экранам. */
    val allAppsPlaced: Boolean = false,
    /** Приложения, которые лаунчер уже видел: новыми считаются только остальные. */
    val knownApps: Set<String> = emptySet(),
    /** Ручные назначения зон консоли: ключ приложения -> зона. */
    val zoneOverrides: Map<String, ConsoleZone> = emptyMap(),
    val favorites: List<String> = emptyList(),
    val recents: List<Recent> = emptyList(),
    /** Скрыты из списка приложений телефонного режима. */
    val hiddenInDrawer: Set<String> = emptySet(),
    /** Ручное назначение папок: имя папки -> система (имя GameSystem) или IGNORE. */
    val folderOverrides: Map<String, String> = emptyMap(),
    /** Выбранный эмулятор для системы: имя GameSystem -> id рецепта запуска. */
    val systemEmulator: Map<String, String> = emptyMap(),
    /** Названия игр, исправленные вручную: id игры -> название. */
    val titleOverrides: Map<String, String> = emptyMap(),
    /** Время в играх: ключ игры -> статистика. */
    val playStats: Map<String, PlayStat> = emptyMap(),
    /** Игра, которая сейчас запущена из лаунчера. */
    val session: PlaySession? = null,
)

/** Порядок вкладок консоли с учётом выбора пользователя. */
object TabOrder {
    /**
     * Вкладки в порядке пользователя. Вкладки, которых нет в его списке (например, новая консоль),
     * встают туда же, где были бы по умолчанию — перед ближайшей следующей знакомой вкладкой.
     */
    fun arrange(defaultIds: List<String>, order: List<String>): List<String> {
        val result = order.filter { it in defaultIds }.distinct().toMutableList()
        defaultIds.forEachIndexed { i, id ->
            if (id in result) return@forEachIndexed
            val next = defaultIds.drop(i + 1).firstOrNull { it in result }
            if (next == null) result.add(id) else result.add(result.indexOf(next), id)
        }
        // Главная всегда первая
        if (result.remove("home")) result.add(0, "home")
        return result
    }

    /** Сдвинуть вкладку на шаг вверх (-1) или вниз (1). Главную не двигаем. */
    fun move(current: List<String>, id: String, dir: Int): List<String> {
        val i = current.indexOf(id)
        val j = i + dir
        if (i < 0 || id == "home" || j < 1 || j >= current.size) return current
        return current.toMutableList().also { java.util.Collections.swap(it, i, j) }
    }
}

/** Учёт времени в игре. */
object PlayTime {
    /** Короче — считаем, что игра не запустилась или её сразу закрыли. */
    const val MIN_MS = 20_000L
    /** Дольше одной сессии не считаем: устройство, скорее всего, просто уснуло с игрой. */
    const val MAX_MS = 6 * 3600_000L

    /** Точный учёт: прибавить время, когда эмулятор был на экране; сессия продолжается до новой игры. */
    fun addUsage(d: LauncherData, r: UsageTime.Result, now: Long): LauncherData {
        val s = d.session ?: return d
        val old = d.playStats[s.key] ?: PlayStat()
        val stat = if (r.ms > 0) old.copy(totalMs = old.totalMs + r.ms, last = maxOf(old.last, r.lastEnd)) else old
        val next = if (r.expired) null else s.copy(counted = now, lastActive = maxOf(s.lastActive, r.lastEnd))
        return d.copy(session = next, playStats = d.playStats + (s.key to stat))
    }

    /** Закрыть сессию: прибавить время к статистике игры. */
    fun finish(d: LauncherData, now: Long): LauncherData {
        val s = d.session ?: return d
        val dur = now - s.start
        val old = d.playStats[s.key] ?: PlayStat()
        val stat = if (dur < MIN_MS) old.copy(last = maxOf(old.last, s.start))
        else old.copy(totalMs = old.totalMs + dur.coerceAtMost(MAX_MS), sessions = old.sessions + 1, last = now)
        return d.copy(session = null, playStats = d.playStats + (s.key to stat))
    }
}

/**
 * Соседний вариант в списке по кругу: dir = 1 — следующий, dir = -1 — предыдущий.
 * Если текущего значения нет в списке, берётся первый (или последний при dir = -1).
 */
fun <T> List<T>.cycle(current: T, dir: Int): T {
    if (isEmpty()) return current
    val i = indexOf(current)
    if (i < 0) return if (dir < 0) last() else first()
    return this[(i + dir).mod(size)]
}
