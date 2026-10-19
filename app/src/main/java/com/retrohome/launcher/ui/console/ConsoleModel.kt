package com.retrohome.launcher.ui.console

import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.ConsoleZone
import com.retrohome.launcher.data.DescSource
import com.retrohome.launcher.data.Game
import com.retrohome.launcher.data.GameMeta
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.Lang
import com.retrohome.launcher.data.TitleParser
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.PlayStat
import com.retrohome.launcher.data.TabOrder
import com.retrohome.launcher.data.zone
import com.retrohome.launcher.i18n.Strings

enum class EntryKind { APP, GAME, ACTION }

data class ConsoleEntry(
    val key: String,
    val label: String,
    val subtitle: String,
    val favorite: Boolean,
    val zone: ConsoleZone,
    val kind: EntryKind = EntryKind.APP,
    val system: GameSystem? = null,
    /** Файл обложки (для игр), если загружен. */
    val cover: String? = null,
    /** Описание на выбранном языке или, если его нет, на другом. */
    val description: String? = null,
    /** Описание показано не на выбранном языке. */
    val descFallback: Boolean = false,
    /** Сколько дисков у игры (0 — один файл). */
    val discCount: Int = 0,
    /** Откуда описание (DescSource), для подписи. */
    val descSource: String? = null,
    /** Снимок экрана из игры (файл), фон за выбранной игрой. */
    val snap: String? = null,
    /** Сколько наиграно и когда запускали в последний раз (0 — не запускали). */
    val playMs: Long = 0,
    val lastPlayed: Long = 0,
    /** Факты для страницы игры. */
    val year: Int? = null,
    val genres: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    /** Как искалось описание (журнал последнего поиска). */
    val lookupLog: List<String> = emptyList(),
    /** Достижения RetroAchievements (null — нет или игра не опознана). */
    val ra: com.retrohome.launcher.data.RaSummary? = null,
)

data class ConsoleRow(val title: String?, val entries: List<ConsoleEntry>, val emptyText: String)

/** list — вкладка-список (главная): «Последняя игра» и избранное столбиком, справа — сведения о выбранном. */
data class ConsoleTab(val id: String, val title: String, val rows: List<ConsoleRow>, val list: Boolean = false)

/** На главной — только последняя игра. */
const val RECENT_LIMIT = 1
const val ACTION_PICK_ROMS = "action:pickRoms"

/** Что нужно знать про игры, чтобы собрать вкладки. */
data class GamesInfo(
    val games: List<Game> = emptyList(),
    /** Папка ROMs выбрана и доступна. */
    val hasFolder: Boolean = false,
    /** Чем запускается каждая система (название эмулятора) или null, если нечем. */
    val meta: Map<String, GameMeta> = emptyMap(),
    val titleOverrides: Map<String, String> = emptyMap(),
    val playStats: Map<String, PlayStat> = emptyMap(),
    /** Порядок игр на вкладках консолей: name, recent, playtime. */
    val sort: String = "name",
    /** Достижения по играм (Game.id → сводка). */
    val ra: Map<String, com.retrohome.launcher.data.RaSummary> = emptyMap(),
    val runner: (GameSystem) -> String? = { null },
) {
    /** Название для показа: исправленное вручную, официальное из базы или из имени файла. */
    fun title(g: Game): String = titleOverrides[g.id] ?: meta[g.id]?.name ?: g.title
}

/** Собрать вкладки консольного режима из приложений, игр и настроек. */
fun buildConsoleTabs(
    apps: List<AppInfo>,
    data: LauncherData,
    s: Strings,
    gi: GamesInfo = GamesInfo(),
    /** Для экрана настройки вкладок: вернуть и скрытые. */
    includeHidden: Boolean = false,
): List<ConsoleTab> {
    val favorites = data.favorites.toSet()
    val recentTime = data.recents.groupBy { it.key }.mapValues { (_, v) -> v.maxOf { it.time } }
    val consoleApps = apps.filter { it.zone(data.zoneOverrides) != ConsoleZone.NONE }
    val appByKey = consoleApps.associateBy { it.key }
    val gameByKey = gi.games.associateBy { it.key }

    fun appEntry(a: AppInfo): ConsoleEntry {
        val zone = a.zone(data.zoneOverrides)
        val subtitle = when {
            zone == ConsoleZone.GAME -> s.androidGame
            a.emulator == null -> s.emulatorFor
            a.emulator.multi -> "${s.emulatorFor}: ${s.manySystems}"
            else -> "${s.emulatorFor}: " + a.emulator.systems.joinToString(", ") { it.title(s.lang) }
        }
        return ConsoleEntry(a.key, a.label, subtitle, a.key in favorites, zone)
    }

    fun gameEntry(g: Game): ConsoleEntry {
        val runner = gi.runner(g.system)
        val parts = listOf(g.system.short) + TitleParser.displayTags(g.tags) +
            (if (g.discs.size > 1) listOf(s.discs.format(g.discs.size)) else emptyList()) +
            listOf(if (runner != null) s.runsIn.format(runner) else s.noRunner)
        val m = gi.meta[g.id]
        val (primary, other) = if (s.lang == Lang.RU) m?.descRu to m?.descEn else m?.descEn to m?.descRu
        val (srcPrimary, srcOther) = if (s.lang == Lang.RU) m?.srcRu to m?.srcEn else m?.srcEn to m?.srcRu
        val stat = gi.playStats[g.key]
        val last = maxOf(stat?.last ?: 0, recentTime[g.key] ?: 0)
        return ConsoleEntry(
            g.key, gi.title(g), parts.joinToString(" · "), g.key in favorites, ConsoleZone.GAME, EntryKind.GAME, g.system,
            cover = m?.cover,
            description = primary ?: other,
            descFallback = primary == null && other != null,
            discCount = g.discs.size,
            descSource = if (primary != null) srcPrimary ?: DescSource.WIKIPEDIA else if (other != null) srcOther ?: DescSource.WIKIPEDIA else null,
            snap = m?.snap,
            playMs = stat?.totalMs ?: 0,
            lastPlayed = last,
            year = m?.year,
            genres = (if (s.lang == Lang.RU) m?.genresRu else m?.genresEn).orEmpty().ifEmpty { m?.genresEn.orEmpty() },
            developers = m?.developers.orEmpty(),
            publishers = m?.publishers.orEmpty(),
            lookupLog = m?.lookupLog.orEmpty(),
            ra = gi.ra[g.id],
        )
    }

    fun entryFor(key: String): ConsoleEntry? =
        gameByKey[key]?.let(::gameEntry) ?: appByKey[key]?.let(::appEntry)

    // «Последняя игра» — только игры из папки ROMs (не эмуляторы и не приложения)
    val recent = data.recents.sortedByDescending { it.time }
        .distinctBy { it.key }
        .mapNotNull { r -> gameByKey[r.key]?.let(::gameEntry) }
        .take(RECENT_LIMIT)
    val favs = data.favorites.mapNotNull(::entryFor)

    val homeRows = ArrayList<ConsoleRow>()
    // Пока папка не выбрана, первой в «Недавних» стоит плитка выбора папки
    val pick = if (!gi.hasFolder) {
        listOf(ConsoleEntry(ACTION_PICK_ROMS, s.pickRoms, s.pickRomsDesc, false, ConsoleZone.NONE, EntryKind.ACTION))
    } else emptyList()
    homeRows += ConsoleRow(s.rowRecent, pick.ifEmpty { recent }, s.emptyRecent)
    homeRows += ConsoleRow(s.rowFavorites, favs, s.emptyFavorites)

    val tabs = ArrayList<ConsoleTab>()
    tabs += ConsoleTab("home", s.tabHome, homeRows, list = true)
    // Вкладка на каждую консоль, где нашлись игры, в порядке списка систем
    gi.games.groupBy { it.system }.toSortedMap(compareBy { it.ordinal }).forEach { (system, list) ->
        tabs += ConsoleTab(
            "sys:${system.name}", system.short,
            listOf(ConsoleRow(null, sortGames(list.map(::gameEntry), gi.sort), s.emptySystem)),
        )
    }
    tabs += ConsoleTab(
        "emulators", s.tabEmulators,
        listOf(ConsoleRow(null, consoleApps.filter { it.zone(data.zoneOverrides) == ConsoleZone.EMULATOR }.map(::appEntry), s.emptyEmulators)),
    )
    tabs += ConsoleTab(
        "games", s.tabGames,
        listOf(ConsoleRow(null, consoleApps.filter { it.zone(data.zoneOverrides) == ConsoleZone.GAME }.map(::appEntry), s.emptyGames)),
    )
    // Порядок и скрытие вкладок — как настроил пользователь; главная всегда первая и видна
    val byId = tabs.associateBy { it.id }
    val hidden = data.settings.hiddenTabs
    return TabOrder.arrange(tabs.map { it.id }, data.settings.tabOrder)
        .mapNotNull { byId[it] }
        .filter { includeHidden || it.id == "home" || it.id !in hidden }
}

/** Порядок игр на вкладке: по названию, недавние сверху или больше наигранные сверху. */
fun sortGames(list: List<ConsoleEntry>, mode: String): List<ConsoleEntry> {
    val byName = compareBy<ConsoleEntry> { it.label.lowercase() }
    return when (mode) {
        "recent" -> list.sortedWith(compareByDescending<ConsoleEntry> { it.lastPlayed }.then(byName))
        "playtime" -> list.sortedWith(compareByDescending<ConsoleEntry> { it.playMs }.thenByDescending { it.lastPlayed }.then(byName))
        else -> list.sortedWith(byName)
    }
}
