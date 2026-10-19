package com.retrohome.launcher

import com.retrohome.launcher.data.AppInfo
import com.retrohome.launcher.data.ConsoleZone
import com.retrohome.launcher.data.Emulators
import com.retrohome.launcher.data.GameSystem
import com.retrohome.launcher.data.HomeItem
import com.retrohome.launcher.data.HomeLayout
import com.retrohome.launcher.data.LauncherData
import com.retrohome.launcher.data.ModeOverride
import com.retrohome.launcher.data.Recent
import com.retrohome.launcher.i18n.EN
import com.retrohome.launcher.i18n.RU
import com.retrohome.launcher.ui.console.buildConsoleTabs
import com.retrohome.launcher.ui.isConsoleMode
import com.retrohome.launcher.ui.phone.filterApps
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorDetectionTest {
    private fun systems(pkg: String, label: String) = Emulators.match(pkg, label)?.systems

    @Test fun userEmulatorsAreRecognized() {
        assertEquals(listOf(GameSystem.PSP), systems("org.ppsspp.ppsspp", "PPSSPP"))
        assertEquals(listOf(GameSystem.PS2), systems("xyz.aethersx2.android", "NetherSX2 Classic"))
        assertEquals(listOf(GameSystem.PS2), systems("com.unknown.fork", "NetherSX2 Classic"))
        assertEquals(listOf(GameSystem.NDS), systems("me.magnum.melonds", "melonDS"))
        assertEquals(listOf(GameSystem.PS1), systems("com.github.stenzek.duckstation", "DuckStation"))
        assertEquals(listOf(GameSystem.N3DS), systems("org.citra.citra_emu", "Citra"))
        assertEquals(listOf(GameSystem.N3DS), systems("org.citra.emu", "Citra MMJ"))
        assertEquals(listOf(GameSystem.SWITCH), systems("dev.eden.eden_emulator", "Eden"))
        assertEquals(listOf(GameSystem.PC), systems("app.gamenative", "GameNative"))
        assertEquals(listOf(GameSystem.PC), systems("com.some.other", "GameNative"))
        assertEquals(listOf(GameSystem.JOIPLAY), systems("cyou.joiplay.joiplay", "JoiPlay"))
        assertTrue(Emulators.match("com.swordfish.lemuroid", "Lemuroid")!!.multi)
        assertTrue(Emulators.match("com.retroarch.aarch64", "RetroArch")!!.multi)
    }

    @Test fun ordinaryAppsAreNotEmulators() {
        assertNull(Emulators.match("com.android.chrome", "Chrome"))
        assertNull(Emulators.match("com.google.android.youtube", "YouTube"))
        assertNull(Emulators.match("com.example.gardenplanner", "Garden of Eden Planner"))
        assertNull(Emulators.match("org.telegram.messenger", "Telegram"))
    }
}

class HomeLayoutTest {
    private fun app(id: Long, page: Int, x: Int, y: Int, key: String) = HomeItem(id, page, x, y, app = key)

    @Test fun addFindsFirstFreeCellAndOverflowsToNextPage() {
        var items = emptyList<HomeItem>()
        var pages = 1
        repeat(5) { i ->
            val r = HomeLayout.addApp(items, pages, "p$i/a", 0, 2, 2)
            items = r.first; pages = r.second
        }
        assertEquals(2, pages)
        assertEquals(4, items.count { it.page == 0 })
        assertEquals(1, items.count { it.page == 1 })
        assertEquals(items.map { it.id }.distinct().size, items.size)
    }

    @Test fun dropAppOnAppMakesFolder() {
        val items = listOf(app(1, 0, 0, 0, "a/a"), app(2, 0, 1, 0, "b/b"))
        val r = HomeLayout.move(items, 2, 0, 0, 0, "Папка")
        assertEquals(1, r.size)
        assertTrue(r[0].isFolder)
        assertEquals(listOf("a/a", "b/b"), r[0].folderApps)
    }

    @Test fun dropAppOnFolderAddsToIt() {
        val folder = HomeItem(1, 0, 0, 0, folderName = "F", folderApps = listOf("a/a", "b/b"))
        val r = HomeLayout.move(listOf(folder, app(2, 0, 2, 2, "c/c")), 2, 0, 0, 0, "x")
        assertEquals(listOf("a/a", "b/b", "c/c"), r.single().folderApps)
    }

    @Test fun moveToDockAndEmptyCell() {
        val items = listOf(app(1, 0, 0, 0, "a/a"))
        val dock = HomeLayout.move(items, 1, HomeItem.DOCK, 3, 5, "x").single()
        assertEquals(HomeItem.DOCK, dock.page); assertEquals(3, dock.x); assertEquals(0, dock.y)
        val moved = HomeLayout.move(items, 1, 1, 2, 3, "x").single()
        assertEquals(1, moved.page); assertEquals(2, moved.x); assertEquals(3, moved.y)
    }

    @Test fun folderShrinksBackToIconWhenOneLeft() {
        val folder = HomeItem(1, 0, 0, 0, folderName = "F", folderApps = listOf("a/a", "b/b"))
        val (r, _) = HomeLayout.takeOutOfFolder(listOf(folder), 1, 1, "b/b", 4, 4)
        assertEquals(2, r.size)
        assertTrue(r.none { it.isFolder })
        assertEquals(setOf("a/a", "b/b"), r.map { it.app }.toSet())
    }

    @Test fun shrinkingGridKeepsAllIcons() {
        val items = listOf(app(1, 0, 5, 5, "a/a"), app(2, 0, 0, 0, "b/b"))
        val (r, _) = HomeLayout.fitToGrid(items, 1, 4, 5)
        assertEquals(2, r.size)
        assertTrue(r.all { it.x < 4 && it.y < 5 })
    }

    @Test fun reflowKeepsOrderWithoutGaps() {
        // 5 столбцов -> 4: порядок чтения сохраняется, дыр нет
        val items = (0 until 7).map { i -> app(i + 1L, 0, i % 5, i / 5, "k$i/a") } + HomeItem(99, HomeItem.DOCK, 0, 0, app = "d/d")
        val (r, pages) = HomeLayout.reflow(items, 1, 4, 6)
        val page = r.filter { !it.isDock }.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))
        assertEquals((0 until 7).map { "k$it/a" }, page.map { it.app })
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 0 to 1, 1 to 1, 2 to 1), page.map { it.x to it.y })
        assertEquals(1, pages)
        assertEquals(HomeItem.DOCK, r.single { it.app == "d/d" }.page)
        // меньше места, чем значков: лишние на следующий экран, следующий экран сдвигается
        val two = items.filter { !it.isDock } + app(50, 1, 0, 0, "next/a")
        val (r2, pages2) = HomeLayout.reflow(two, 2, 2, 2)
        assertEquals(3, pages2)
        assertEquals(2, r2.single { it.app == "next/a" }.page)
    }

    @Test fun alphabeticalSortRemovesDuplicatesAndGaps() {
        val items = listOf(
            app(1, 1, 3, 3, "c/c"), app(2, 0, 4, 0, "a/a"), app(3, 0, 0, 2, "c/c"),
            HomeItem(4, 0, 2, 2, folderName = "Игры", folderApps = listOf("x/x", "y/y")),
            app(5, HomeItem.DOCK, 0, 0, "b/b"), app(6, 0, 1, 1, "b/b"),
        )
        val (r, pages) = HomeLayout.sortAlphabetically(items, 4, 4) { it.substringBefore('/') }
        val page = r.filter { !it.isDock }.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))
        assertEquals(listOf(null, "a/a", "c/c"), page.map { it.app })
        assertEquals(listOf(0 to 0, 1 to 0, 2 to 0), page.map { it.x to it.y })
        assertEquals(1, pages)
    }

    @Test fun pruneRemovesUninstalled() {
        val folder = HomeItem(1, 0, 0, 0, folderName = "F", folderApps = listOf("a/a", "b/b"))
        val r = HomeLayout.pruneMissing(listOf(folder, app(2, 0, 1, 0, "c/c")), setOf("a/a"))
        assertEquals(1, r.size)
        assertEquals("a/a", r.single().app)
    }

    @Test fun cannotDeleteNonEmptyPage() {
        val items = listOf(app(1, 1, 0, 0, "a/a"))
        assertEquals(2, HomeLayout.deletePage(items, 2, 1).second)
        val (r, pages) = HomeLayout.deletePage(items, 3, 0)
        assertEquals(2, pages)
        assertEquals(0, r.single().page)
    }
}

class AppSyncTest {
    private val small = LauncherData(settings = com.retrohome.launcher.data.Settings(columns = 2, rows = 2), pageCount = 1)

    @Test fun firstRunPlacesEverythingInOrder() {
        val keys = listOf("a/a", "b/b", "c/c", "d/d", "e/e", "f/f")
        val d = HomeLayout.syncApps(small, keys, dockKeys = listOf("f/f"))
        assertEquals("f/f", d.home.single { it.isDock }.app)
        val pageItems = d.home.filter { !it.isDock }.sortedWith(compareBy({ it.page }, { it.y }, { it.x }))
        assertEquals(listOf("a/a", "b/b", "c/c", "d/d", "e/e"), pageItems.map { it.app })
        assertEquals(2, d.pageCount)
        assertTrue(d.allAppsPlaced && d.homeSeeded)
    }

    @Test fun newInstallAppearsButRemovedIconStaysRemoved() {
        var d = HomeLayout.syncApps(small, listOf("a/a", "b/b"), emptyList())
        val bId = d.home.single { it.app == "b/b" }.id
        d = d.copy(home = HomeLayout.remove(d.home, bId))
        d = HomeLayout.syncApps(d, listOf("a/a", "b/b", "z/z"), emptyList())
        assertTrue(d.home.any { it.app == "z/z" })
        assertTrue(d.home.none { it.app == "b/b" })
        // повторная сверка ничего не меняет
        assertEquals(d, HomeLayout.syncApps(d, listOf("a/a", "b/b", "z/z"), emptyList()))
    }

    @Test fun autoAddCanBeTurnedOff() {
        var d = HomeLayout.syncApps(small, listOf("a/a"), emptyList())
        d = d.copy(settings = d.settings.copy(autoAddNew = false))
        d = HomeLayout.syncApps(d, listOf("a/a", "n/n"), emptyList())
        assertTrue(d.home.none { it.app == "n/n" })
    }

    @Test fun appInFolderIsNotDuplicated() {
        val folder = HomeItem(1, 0, 0, 0, folderName = "F", folderApps = listOf("a/a", "b/b"))
        val d = HomeLayout.syncApps(small.copy(home = listOf(folder)), listOf("a/a", "b/b", "c/c"), emptyList())
        assertEquals(2, d.home.size)
        assertEquals("c/c", d.home.single { !it.isFolder }.app)
    }
}

class ConsoleTabsTest {
    private val apps = listOf(
        AppInfo("org.ppsspp.ppsspp/a", "org.ppsspp.ppsspp", "PPSSPP", false, Emulators.match("org.ppsspp.ppsspp", "PPSSPP")),
        AppInfo("com.game/a", "com.game", "Some Game", true, null),
        AppInfo("com.chrome/a", "com.chrome", "Chrome", false, null),
    )

    @Test fun zonesAndOverrides() {
        val tabs = buildConsoleTabs(apps, LauncherData(), RU, com.retrohome.launcher.ui.console.GamesInfo(hasFolder = true))
        assertEquals(listOf("home", "emulators", "games"), tabs.map { it.id })
        assertEquals(listOf("PPSSPP"), tabs[1].rows[0].entries.map { it.label })
        assertEquals("Эмулятор: PlayStation Portable", tabs[1].rows[0].entries[0].subtitle)
        assertEquals(listOf("Some Game"), tabs[2].rows[0].entries.map { it.label })

        val moved = buildConsoleTabs(
            apps,
            LauncherData(zoneOverrides = mapOf("com.chrome/a" to ConsoleZone.GAME, "com.game/a" to ConsoleZone.NONE)),
            EN,
            com.retrohome.launcher.ui.console.GamesInfo(hasFolder = true),
        )
        assertEquals(listOf("Chrome"), moved[2].rows[0].entries.map { it.label })
        assertEquals("Games", moved[2].title)
    }

    @Test fun lastPlayedIsOnlyTheNewestGame() {
        val games = listOf(
            com.retrohome.launcher.data.Game("1", "/r/a.iso", com.retrohome.launcher.data.GameSystem.PS2, "Okami", emptyList(), "ps2"),
            com.retrohome.launcher.data.Game("2", "/r/b.iso", com.retrohome.launcher.data.GameSystem.PS2, "Persona 4", emptyList(), "ps2"),
        )
        val d = LauncherData(
            // Эмуляторы и приложения запускались позже, но в «Последнюю игру» не попадают
            recents = listOf(Recent("com.chrome/a", 500), Recent("org.ppsspp.ppsspp/a", 400), Recent("game:2", 300), Recent("game:1", 200)),
            favorites = listOf("org.ppsspp.ppsspp/a", "game:1"),
        )
        val home = buildConsoleTabs(apps, d, RU, com.retrohome.launcher.ui.console.GamesInfo(games, hasFolder = true))[0]
        assertTrue(home.list)
        assertEquals(listOf("Persona 4"), home.rows[0].entries.map { it.label })
        assertEquals(listOf("PPSSPP", "Okami"), home.rows[1].entries.map { it.label })
        assertTrue(home.rows[1].entries.all { it.favorite })
        // Без игр в недавних — пусто, даже если запускались приложения
        val noGames = buildConsoleTabs(apps, d.copy(recents = listOf(Recent("com.game/a", 1))), RU, com.retrohome.launcher.ui.console.GamesInfo(games, hasFolder = true))[0]
        assertTrue(noGames.rows[0].entries.isEmpty())
    }
}

class MiscTest {
    @Test fun modeFollowsRotationUnlessForced() {
        assertTrue(isConsoleMode(ModeOverride.AUTO, landscape = true))
        assertTrue(!isConsoleMode(ModeOverride.AUTO, landscape = false))
        assertTrue(isConsoleMode(ModeOverride.CONSOLE, landscape = false))
        assertTrue(!isConsoleMode(ModeOverride.PHONE, landscape = true))
    }

    @Test fun searchPrefersWordStart() {
        val list = listOf(
            AppInfo("1/a", "1", "Google Play Games", false, null),
            AppInfo("2/a", "2", "Play Store", false, null),
            AppInfo("3/a", "3", "Display", false, null),
        )
        assertEquals(listOf("Play Store", "Google Play Games", "Display"), filterApps(list, "play").map { it.label })
    }

    @Test fun savedDataSurvivesRoundTripAndOldFiles() {
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        val d = LauncherData(home = listOf(HomeItem(1, 0, 1, 2, app = "a/b")), favorites = listOf("a/b"))
        val back = json.decodeFromString(LauncherData.serializer(), json.encodeToString(LauncherData.serializer(), d))
        assertEquals(d, back)
        // Файл от будущей версии с незнакомыми полями всё равно читается
        val legacy = json.decodeFromString(LauncherData.serializer(), """{"pageCount":3,"someNewField":1}""")
        assertNotNull(legacy)
        assertEquals(3, legacy.pageCount)
    }
}

/** Время в игре и порядок игр на вкладках. */
class PlayTimeTest {
    private val start = 1_000_000L

    @Test fun sessionAddsTime() {
        val d0 = com.retrohome.launcher.data.LauncherData(session = com.retrohome.launcher.data.PlaySession("game:a", start))
        val d1 = com.retrohome.launcher.data.PlayTime.finish(d0, start + 30 * 60_000)
        org.junit.Assert.assertNull(d1.session)
        org.junit.Assert.assertEquals(30 * 60_000L, d1.playStats["game:a"]!!.totalMs)
        org.junit.Assert.assertEquals(1, d1.playStats["game:a"]!!.sessions)
        // вторая сессия прибавляется; слишком короткая (игра не запустилась) не считается
        val d2 = com.retrohome.launcher.data.PlayTime.finish(d1.copy(session = com.retrohome.launcher.data.PlaySession("game:a", start + 3600_000)), start + 3600_000 + 5_000)
        org.junit.Assert.assertEquals(30 * 60_000L, d2.playStats["game:a"]!!.totalMs)
        // очень длинная — скорее всего устройство уснуло: не больше 6 часов
        val d3 = com.retrohome.launcher.data.PlayTime.finish(d2.copy(session = com.retrohome.launcher.data.PlaySession("game:a", start)), start + 20 * 3600_000L)
        org.junit.Assert.assertEquals(30 * 60_000L + 6 * 3600_000L, d3.playStats["game:a"]!!.totalMs)
        // без сессии ничего не меняется
        org.junit.Assert.assertSame(d3, com.retrohome.launcher.data.PlayTime.finish(d3, start))
    }

    @Test fun sortModes() {
        fun e(label: String, ms: Long, last: Long) = com.retrohome.launcher.ui.console.ConsoleEntry(
            "game:$label", label, "", false, com.retrohome.launcher.data.ConsoleZone.GAME, playMs = ms, lastPlayed = last)
        val list = listOf(e("Okami", 0, 0), e("Bully", 5000, 100), e("Ico", 9000, 50))
        org.junit.Assert.assertEquals(listOf("Bully", "Ico", "Okami"), com.retrohome.launcher.ui.console.sortGames(list, "name").map { it.label })
        org.junit.Assert.assertEquals(listOf("Bully", "Ico", "Okami"), com.retrohome.launcher.ui.console.sortGames(list, "recent").map { it.label })
        org.junit.Assert.assertEquals(listOf("Ico", "Bully", "Okami"), com.retrohome.launcher.ui.console.sortGames(list, "playtime").map { it.label })
    }

    @Test fun durationsAndDates() {
        val s = com.retrohome.launcher.i18n.RU
        org.junit.Assert.assertEquals("меньше минуты", s.duration(30_000))
        org.junit.Assert.assertEquals("45 мин", s.duration(45 * 60_000L))
        org.junit.Assert.assertEquals("3 ч 20 мин", s.duration(200 * 60_000L))
        val now = System.currentTimeMillis()
        org.junit.Assert.assertEquals("сегодня", s.ago(now, now))
        org.junit.Assert.assertEquals("вчера", s.ago(now - 86_400_000L, now))
        org.junit.Assert.assertEquals("5 дн. назад", s.ago(now - 5 * 86_400_000L, now))
    }
}

/** Точный учёт времени по системной статистике. */
class UsageTimeTest {
    private val P = "org.ppsspp.ppsspp"
    private fun ev(t: Long, type: Int, pkg: String = P, cls: String = "Main") = com.retrohome.launcher.data.UsageTime.Ev(t, type, pkg, cls)
    private val R = com.retrohome.launcher.data.UsageTime.RESUMED
    private val Pa = com.retrohome.launcher.data.UsageTime.PAUSED
    private val min = 60_000L

    @Test fun countsOnlyForegroundTime() {
        // играли 30 мин, свернули, час в другом приложении, вернулись через «недавние» ещё на 20 мин
        val events = listOf(
            ev(0, R), ev(30 * min, Pa),
            ev(30 * min, R, "com.youtube"), ev(90 * min, Pa, "com.youtube"),
            ev(91 * min, R), ev(111 * min, Pa),
        )
        val r = com.retrohome.launcher.data.UsageTime.foreground(events, P, 0, 120 * min)
        org.junit.Assert.assertEquals(50 * min, r.ms)
        org.junit.Assert.assertEquals(111 * min, r.lastEnd)
        org.junit.Assert.assertFalse(r.expired)
    }

    @Test fun screenOffStopsAndOpenIntervalCountsToNow() {
        val events = listOf(ev(0, R), ev(10 * min, com.retrohome.launcher.data.UsageTime.SCREEN_OFF, ""), ev(70 * min, R))
        val r = com.retrohome.launcher.data.UsageTime.foreground(events, P, 0, 75 * min)
        org.junit.Assert.assertEquals(15 * min, r.ms)
    }

    @Test fun twoActivitiesOfSameAppAreOneInterval() {
        // эмулятор открыл экран игры поверх своего меню: меню пауза после того, как игра на экране
        val events = listOf(ev(0, R, cls = "Menu"), ev(1 * min, R, cls = "Game"), ev(1 * min + 10, Pa, cls = "Menu"), ev(20 * min, Pa, cls = "Game"))
        org.junit.Assert.assertEquals(20 * min, com.retrohome.launcher.data.UsageTime.foreground(events, P, 0, 30 * min).ms)
    }

    @Test fun longBreakEndsSession() {
        val events = listOf(ev(0, R), ev(10 * min, Pa), ev(13 * 60 * min, R), ev(14 * 60 * min, Pa))
        val r = com.retrohome.launcher.data.UsageTime.foreground(events, P, 0, 15 * 60 * min)
        org.junit.Assert.assertEquals(10 * min, r.ms)
        org.junit.Assert.assertTrue(r.expired)
    }

    @Test fun addUsageKeepsSessionOpen() {
        val d0 = com.retrohome.launcher.data.LauncherData(session = com.retrohome.launcher.data.PlaySession("game:a", 0, pkg = P))
        val d1 = com.retrohome.launcher.data.PlayTime.addUsage(d0, com.retrohome.launcher.data.UsageTime.Result(30 * min, 30 * min, false), 40 * min)
        org.junit.Assert.assertEquals(30 * min, d1.playStats["game:a"]!!.totalMs)
        org.junit.Assert.assertEquals(40 * min, d1.session!!.counted)
        val d2 = com.retrohome.launcher.data.PlayTime.addUsage(d1, com.retrohome.launcher.data.UsageTime.Result(5 * min, 50 * min, true), 60 * min)
        org.junit.Assert.assertEquals(35 * min, d2.playStats["game:a"]!!.totalMs)
        org.junit.Assert.assertNull(d2.session)
    }
}

/** Порядок и скрытие вкладок консоли. */
class TabOrderTest {
    private val defaults = listOf("home", "sys:GC", "sys:PS2", "emulators", "games")

    @Test fun arrangeKeepsUserOrderAndPlacesNewTabs() {
        val user = listOf("home", "games", "sys:PS2", "emulators", "sys:GC")
        org.junit.Assert.assertEquals(user, com.retrohome.launcher.data.TabOrder.arrange(defaults, user))
        // появилась новая консоль GBA — встаёт перед GC, своим соседом по умолчанию
        val withGba = listOf("home", "sys:GBA", "sys:GC", "sys:PS2", "emulators", "games")
        org.junit.Assert.assertEquals(listOf("home", "games", "sys:PS2", "emulators", "sys:GBA", "sys:GC"),
            com.retrohome.launcher.data.TabOrder.arrange(withGba, user))
        // пустой порядок — как по умолчанию; главная всегда первая
        org.junit.Assert.assertEquals(defaults, com.retrohome.launcher.data.TabOrder.arrange(defaults, emptyList()))
        org.junit.Assert.assertEquals("home", com.retrohome.launcher.data.TabOrder.arrange(defaults, listOf("games", "home")).first())
    }

    @Test fun moveSwapsButNotPastHome() {
        org.junit.Assert.assertEquals(listOf("home", "sys:PS2", "sys:GC", "emulators", "games"),
            com.retrohome.launcher.data.TabOrder.move(defaults, "sys:GC", 1))
        org.junit.Assert.assertEquals(defaults, com.retrohome.launcher.data.TabOrder.move(defaults, "sys:GC", -1))
        org.junit.Assert.assertEquals(defaults, com.retrohome.launcher.data.TabOrder.move(defaults, "home", 1))
        org.junit.Assert.assertEquals(defaults, com.retrohome.launcher.data.TabOrder.move(defaults, "games", 1))
    }

    @Test fun hiddenTabsDisappearButHomeStays() {
        val g = com.retrohome.launcher.data.Game("1", "/r/1", com.retrohome.launcher.data.GameSystem.PS2, "Okami", emptyList(), "ps2")
        val data = com.retrohome.launcher.data.LauncherData(settings = com.retrohome.launcher.data.Settings(
            hiddenTabs = setOf("games", "home"), tabOrder = listOf("home", "emulators", "sys:PS2")))
        val tabs = com.retrohome.launcher.ui.console.buildConsoleTabs(emptyList(), data, com.retrohome.launcher.i18n.RU,
            com.retrohome.launcher.ui.console.GamesInfo(listOf(g), true))
        org.junit.Assert.assertEquals(listOf("home", "emulators", "sys:PS2"), tabs.map { it.id })
        val all = com.retrohome.launcher.ui.console.buildConsoleTabs(emptyList(), data, com.retrohome.launcher.i18n.RU,
            com.retrohome.launcher.ui.console.GamesInfo(listOf(g), true), includeHidden = true)
        org.junit.Assert.assertEquals(listOf("home", "emulators", "sys:PS2", "games"), all.map { it.id })
    }
}

/** Виджеты на рабочем столе: место, перемещение, размер, перестройка сетки. */
class WidgetLayoutTest {
    private fun app(id: Long, x: Int, y: Int, page: Int = 0) = com.retrohome.launcher.data.HomeItem(id, page, x, y, app = "app$id/Main")
    private val L = com.retrohome.launcher.data.HomeLayout

    @Test fun widgetTakesFreeAreaAndCoversCells() {
        val items = listOf(app(1, 0, 0), app(2, 1, 0))
        val (r, pages) = L.addWidget(items, 1, 77, "com.clock/.Widget", 4, 2, 0, 5, 6)
        val w = r.first { it.isWidget }
        org.junit.Assert.assertEquals(1, pages)
        org.junit.Assert.assertEquals(0 to 1, w.x to w.y) // первая строка занята значками
        org.junit.Assert.assertEquals(w, L.itemAt(r, 0, 3, 2))
        org.junit.Assert.assertFalse(w.isFolder)
        // новый значок не встаёт под виджет
        val (r2, _) = L.addApp(r, 1, "new/Main", 0, 5, 6)
        val n = r2.first { it.app == "new/Main" }
        org.junit.Assert.assertEquals(2 to 0, n.x to n.y)
        // нет места на экране — виджет уходит на новый
        val full = (0 until 30).map { app(100L + it, it % 5, it / 5) }
        val (r3, p3) = L.addWidget(full, 1, 78, "w", 2, 2, 0, 5, 6)
        org.junit.Assert.assertEquals(2, p3)
        org.junit.Assert.assertEquals(1, r3.first { it.isWidget }.page)
    }

    @Test fun movingAndResizing() {
        val w = com.retrohome.launcher.data.HomeItem(10, 0, 0, 0, widgetId = 5, spanX = 2, spanY = 2)
        val items = listOf(w, app(1, 3, 0), app(2, 0, 3))
        // значок на виджет не кладётся (и папка не создаётся)
        org.junit.Assert.assertEquals(items, L.move(items, 1, 0, 1, 1, "Папка", 5, 6))
        // виджет — только на свободное место
        org.junit.Assert.assertEquals(items, L.move(items, 10, 0, 2, 0, "", 5, 6)) // задел бы значок (3,0)
        val moved = L.move(items, 10, 0, 1, 1, "", 5, 6).first { it.id == 10L }
        org.junit.Assert.assertEquals(1 to 1, moved.x to moved.y)
        org.junit.Assert.assertEquals(items, L.move(items, 10, com.retrohome.launcher.data.HomeItem.DOCK, 0, 0, "", 5, 6))
        // размер: на месте не помещается — ищется другое место
        val big = L.resizeWidget(items, 10, 4, 2, 5, 6).first { it.id == 10L }
        org.junit.Assert.assertEquals(listOf(4, 2), listOf(big.spanX, big.spanY))
        org.junit.Assert.assertTrue(big.y >= 1)
        // больше сетки нельзя
        val clamped = L.resizeWidget(items, 10, 9, 9, 5, 6).first { it.id == 10L }
        org.junit.Assert.assertEquals(2, clamped.spanX) // 5×6 не помещается рядом со значками — размер не меняется
    }

    @Test fun reflowAndSortKeepWidgets() {
        val w = com.retrohome.launcher.data.HomeItem(10, 0, 0, 1, widgetId = 5, spanX = 5, spanY = 2)
        val items = listOf(app(1, 0, 0), app(2, 4, 0), w, app(3, 2, 4))
        // сетка 4 колонки: виджет сужается до 4, значки обтекают
        val (r, _) = L.reflow(items, 1, 4, 6)
        val rw = r.first { it.isWidget }
        org.junit.Assert.assertEquals(4, rw.spanX)
        org.junit.Assert.assertEquals(0 to 1, rw.x to rw.y)
        r.filter { !it.isWidget }.forEach { org.junit.Assert.assertFalse("${it.app} под виджетом", rw.covers(it.x, it.y) && it.page == rw.page) }
        val (s2, _) = L.sortAlphabetically(items, 5, 6) { it }
        org.junit.Assert.assertEquals(w, s2.first { it.isWidget })
        s2.filter { !it.isWidget }.forEach { org.junit.Assert.assertFalse(w.covers(it.x, it.y) && it.page == 0) }
        // удалённые приложения убираются, виджеты остаются
        org.junit.Assert.assertEquals(listOf(w), L.pruneMissing(items, emptySet()))
    }
}

/** Наборы значков: разбор appfilter.xml. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(application = android.app.Application::class, sdk = [35])
class AppFilterTest {
    @Test fun parsesComponentsAndBackground() {
        val xml = """<?xml version="1.0" encoding="utf-8"?>
            <resources>
              <iconback img1="iconback" img2="iconback2"/>
              <iconmask img1="iconmask"/>
              <iconupon img1="iconupon"/>
              <scale factor="0.8"/>
              <item component="ComponentInfo{com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity}" drawable="retroarch"/>
              <item component="ComponentInfo{org.ppsspp.ppsspp/.PpssppActivity}" drawable="ppsspp"/>
              <item component=":LAUNCHER_ACTION_APP_DRAWER" drawable="drawer"/>
            </resources>"""
        val f = com.retrohome.launcher.data.AppFilter.parse(xml)
        org.junit.Assert.assertEquals("retroarch", f.drawableFor("com.retroarch/com.retroarch.browser.mainmenu.MainMenuActivity"))
        // активность с точкой раскрывается в полное имя
        org.junit.Assert.assertEquals("ppsspp", f.drawableFor("org.ppsspp.ppsspp/org.ppsspp.ppsspp.PpssppActivity"))
        // другой экран того же пакета — значок пакета
        org.junit.Assert.assertEquals("retroarch", f.drawableFor("com.retroarch/com.retroarch.Other"))
        org.junit.Assert.assertNull(f.drawableFor("com.unknown/Main"))
        org.junit.Assert.assertEquals(listOf("iconback", "iconback2"), f.backs)
        org.junit.Assert.assertEquals("iconmask", f.mask)
        org.junit.Assert.assertEquals(0.8f, f.scale, 0.001f)
        org.junit.Assert.assertEquals(2, f.components.size)
    }
}

/** Виджет раздвигает значки, но не ложится на другой виджет. */
class WidgetMoveTest {
    private val L = com.retrohome.launcher.data.HomeLayout
    private fun app(id: Long, x: Int, y: Int) = com.retrohome.launcher.data.HomeItem(id, 0, x, y, app = "a$id/M")

    @Test fun iconsMoveOutOfTheWay() {
        val w = com.retrohome.launcher.data.HomeItem(10, 0, 0, 3, widgetId = 5, spanX = 4, spanY = 2)
        val items = listOf(w, app(1, 0, 0), app(2, 1, 0), app(3, 2, 0))
        val (r, pages) = L.moveWidget(items, 1, 10, 0, 0, 0, 5, 6)
        val mw = r.first { it.id == 10L }
        org.junit.Assert.assertEquals(0 to 0, mw.x to mw.y)
        org.junit.Assert.assertEquals(1, pages)
        // все значки на месте и не под виджетом
        org.junit.Assert.assertEquals(4, r.size)
        r.filter { !it.isWidget }.forEach { org.junit.Assert.assertFalse("${it.app} под виджетом", mw.covers(it.x, it.y)) }
        // на другой виджет — нельзя
        val other = com.retrohome.launcher.data.HomeItem(11, 0, 3, 1, widgetId = 6, spanX = 2, spanY = 2)
        val (r2, _) = L.moveWidget(items + other, 1, 10, 0, 0, 0, 5, 6)
        org.junit.Assert.assertEquals(items + other, r2)
        // за край не выходит
        val (r3, _) = L.moveWidget(items, 1, 10, 0, 4, 5, 5, 6)
        org.junit.Assert.assertEquals(1 to 4, r3.first { it.id == 10L }.let { it.x to it.y })
    }
}

/** Описание — для игры именно этой консоли. */
class PlatformsTest {
    private val P = com.retrohome.launcher.data.Platforms

    @Test fun byWikidataPlatforms() {
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.WRONG, P.byLabels(com.retrohome.launcher.data.GameSystem.PS1, listOf("PlayStation 5", "Microsoft Windows", "Xbox Series X/S", "Nintendo Switch 2")))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.CONFIRMED, P.byLabels(com.retrohome.launcher.data.GameSystem.PS1, listOf("PlayStation", "Microsoft Windows")))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.CONFIRMED, P.byLabels(com.retrohome.launcher.data.GameSystem.GC, listOf("Dreamcast", "Nintendo GameCube")))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.WRONG, P.byLabels(com.retrohome.launcher.data.GameSystem.GB, listOf("Game Boy Advance")))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.UNKNOWN, P.byLabels(com.retrohome.launcher.data.GameSystem.PS1, emptyList()))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.UNKNOWN, P.byLabels(com.retrohome.launcher.data.GameSystem.PC, listOf("Microsoft Windows")))
    }

    @Test fun byText() {
        val revelation = "Final Fantasy VII Revelation — будущая компьютерная игра в жанре ролевого экшена для PlayStation 5, Windows, Xbox Series X/S, Nintendo Switch 2"
        val original = "Final Fantasy VII — японская ролевая игра, разработанная Square для игровой приставки PlayStation в 1997 году."
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.WRONG, P.byText(com.retrohome.launcher.data.GameSystem.PS1, revelation))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.CONFIRMED, P.byText(com.retrohome.launcher.data.GameSystem.PS1, original))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.UNKNOWN, P.byText(com.retrohome.launcher.data.GameSystem.PS1, "Final Fantasy VII — ролевая игра."))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.CONFIRMED, P.byText(com.retrohome.launcher.data.GameSystem.PS2, "Okami is a 2006 action-adventure game for the PlayStation 2."))
        org.junit.Assert.assertEquals(com.retrohome.launcher.data.Platforms.Verdict.WRONG, P.byText(com.retrohome.launcher.data.GameSystem.PS2, "for the original PlayStation"))
    }
}

/** Статьи о персонажах, франшизах и фильмах не принимаются за статью об игре. */
class GameArticleTest {
    private fun ok(desc: String, extract: String, lang: String, title: String = "t") =
        com.retrohome.launcher.data.Wiki.looksLikeGame(com.retrohome.launcher.data.Wiki.Summary(title, desc, extract), lang)

    @Test fun charactersAreNotGames() {
        org.junit.Assert.assertFalse(ok("", "Ти́фа Ло́кхарт (яп. ティファ・ロックハート Тифа Роккуха:то, англ. Tifa Lockhart) — персонаж компьютерной ролевой игры Final Fantasy VII (1997) от компании Square.", "ru"))
        org.junit.Assert.assertFalse(ok("Fictional character from Final Fantasy VII", "Tifa Lockhart is a character in Square's Final Fantasy video game series.", "en"))
        org.junit.Assert.assertFalse(ok("Japanese media franchise", "Pokémon is a Japanese media franchise consisting of video games, animated series and films.", "en"))
        org.junit.Assert.assertFalse(ok("", "Final Fantasy VII: Advent Children — японский полнометражный анимационный фильм, продолжение игры Final Fantasy VII.", "ru"))
    }

    @Test fun seriesAndCardGamesAreNotGames() {
        org.junit.Assert.assertFalse(ok("", "The Legend of Heroes: Gagharv Trilogy is a series of role-playing video games developed by Nihon Falcom.", "en"))
        org.junit.Assert.assertFalse(ok("Video game series", "Pokémon is a series of video games developed by Game Freak.", "en"))
        org.junit.Assert.assertFalse(ok("Video game character", "Cloud Strife is the protagonist of Final Fantasy VII.", "en"))
        org.junit.Assert.assertFalse(ok("", "Pokémon — коллекционная карточная игра, основанная на франшизе Pokémon.", "ru", "Pokémon (коллекционная карточная игра)"))
        org.junit.Assert.assertFalse(ok("", "Pokémon — серия компьютерных игр.", "ru"))
        org.junit.Assert.assertFalse(ok("Collectible card game", "The Pokémon Trading Card Game is a collectible card game.", "en", "Pokémon Trading Card Game"))
    }

    @Test fun gamesAreGames() {
        org.junit.Assert.assertTrue(ok("", "Final Fantasy VII (яп. ファイナルファンタジーVII, англ.) — компьютерная ролевая игра, разработанная компанией Square для PlayStation.", "ru"))
        org.junit.Assert.assertTrue(ok("1997 video game", "Final Fantasy VII is a 1997 role-playing video game developed by Square. The game's story follows Cloud Strife, a character...", "en"))
        org.junit.Assert.assertTrue(ok("1998 video game", "Pokémon Trading Card Game is a 1998 video game for the Game Boy Color.", "en", "Pokémon Trading Card Game (video game)"))
        org.junit.Assert.assertTrue(ok("", "Okami is an action-adventure game based on Japanese folklore; a film adaptation was rumored.", "en"))
    }
}

/** Название статьи похоже на игру — но не общая статья о серии и не другая часть. */
class TitleMatchTest {
    private val W = com.retrohome.launcher.data.Wiki

    @Test fun similarTitles() {
        assertEquals(0, W.similarity("The Legend of Heroes II: Prophecy of the Moonlight Witch", "The Legend of Heroes II - Prophecy of the Moonlight Witch"))
        assertNotNull(W.similarity("The Legend of Heroes III: Song of the Ocean", "Legend of Heroes - Song of the Ocean"))
        assertNotNull(W.similarity("The Legend of Heroes: A Tear of Vermillion", "Legend of Heroes - Tears of Vermillion"))
        assertNotNull(W.similarity("Skies of Arcadia", "Skies of Arcadia Legends"))
        assertNotNull(W.similarity("Pokémon Brilliant Diamond and Shining Pearl", "Pokémon Brilliant Diamond"))
    }

    @Test fun seriesArticlesDoNotMatchOneGame() {
        assertNull(W.similarity("The Legend of Heroes", "Legend of Heroes - Tears of Vermillion"))
        assertNull(W.similarity("Pokémon (коллекционная карточная игра)", "Pokémon Brilliant Diamond"))
        assertNull(W.similarity("Pokémon (video game series)", "Pokémon Brilliant Diamond"))
        assertNull(W.similarity("Final Fantasy", "Final Fantasy VII"))
        assertNull(W.similarity("The Legend of Heroes II: Prophecy of the Moonlight Witch", "Legend of Heroes - Song of the Ocean"))
    }

    @Test fun exactTitle() {
        assertTrue(W.sameTitle("The Legend of Heroes: A Tear of Vermillion", "The Legend of Heroes - A Tear of Vermillion"))
        assertTrue(W.sameTitle("The Legend of Heroes: A Tear of Vermillion", "Legend of Heroes - Tears of Vermillion"))
        org.junit.Assert.assertFalse(W.sameTitle("Final Fantasy VII Remake", "Final Fantasy VII"))
        org.junit.Assert.assertFalse(W.sameTitle("The Legend of Heroes III: Song of the Ocean", "Legend of Heroes - Song of the Ocean"))
    }

    @Test fun portMentionedLateInIntro() {
        val P = com.retrohome.launcher.data.Platforms
        val text = "Released for the PC-9801, Sega Saturn and PlayStation. " + "Filler text. ".repeat(80) + "Remade for the PlayStation Portable."
        assertEquals(com.retrohome.launcher.data.Platforms.Verdict.CONFIRMED, P.byText(com.retrohome.launcher.data.GameSystem.PSP, text))
    }

    @Test fun yearAndPublisherForOurConsole() {
        val D = com.retrohome.launcher.data.Wikidata
        fun claim(value: String, platform: String? = null) = """{"mainsnak":{"datavalue":{"value":$value}},"rank":"normal"""" +
            (platform?.let { ""","qualifiers":{"P400":[{"datavalue":{"value":{"id":"$it"}}}]}""" } ?: "") + "}"
        val body = """{"entities":{"Q5":{"id":"Q5","claims":{
            "P577":[${claim("{\"time\":\"+1996-09-30T00:00:00Z\"}")},${claim("{\"time\":\"+2002-11-04T00:00:00Z\"}", "Q188642")}],
            "P123":[${claim("{\"id\":\"Q20\"}")},${claim("{\"id\":\"Q21\"}", "Q188642")}]},"sitelinks":{}}}}"""
        val e = D.parseEntity(body)!!
        val labels = mapOf("Q20" to mapOf("en" to "Virgin Interactive"), "Q21" to mapOf("en" to "BAM! Entertainment"))
        val gba = D.facts(e, labels, com.retrohome.launcher.data.GameSystem.GBA)
        assertEquals(2002, gba.year)
        assertEquals(listOf("BAM! Entertainment"), gba.publishers)
        val pc = D.facts(e, labels, com.retrohome.launcher.data.GameSystem.PS1)
        assertEquals(1996, pc.year)
        assertEquals(listOf("Virgin Interactive", "BAM! Entertainment"), pc.publishers)
    }
}

/** У JVM предел — 255 параметров в конструкторе; при его превышении лаунчер не запустится вовсе. */
class StringsLimitTest {
    @Test fun constructorsFitJvmLimit() {
        for (c in listOf(com.retrohome.launcher.i18n.Strings::class.java, com.retrohome.launcher.data.Settings::class.java)) {
            val max = c.declaredConstructors.maxOf { it.parameterCount }
            assertTrue("${c.simpleName}: $max параметров — пора выносить поля во вложенный класс", max < 240)
        }
        assertEquals("Достижения", com.retrohome.launcher.i18n.RU.ra.achievements)
        assertEquals("Achievements", com.retrohome.launcher.i18n.EN.ra.achievements)
    }
}

/** Причины сетевых сбоев переводятся в понятные объяснения. */
class NetworkCheckTest {
    private val N = com.retrohome.launcher.data.NetworkCheck
    @Test fun classify() {
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.OK, N.classify(200, null))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.LIMIT, N.classify(429, null))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.REJECTED, N.classify(403, null))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.SERVER, N.classify(503, null))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.NO_DNS, N.classify(0, "UnknownHostException: Unable to resolve host"))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.TIMEOUT, N.classify(0, "SocketTimeoutException: timeout"))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.TLS, N.classify(0, "SSLHandshakeException: Chain validation failed"))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.NO_PERMISSION, N.classify(0, "SecurityException: Permission denied (missing INTERNET permission?)"))
        assertEquals(com.retrohome.launcher.data.NetworkCheck.Kind.REFUSED, N.classify(0, "ConnectException: Failed to connect"))
        for (k in com.retrohome.launcher.data.NetworkCheck.Kind.entries.filter { it != com.retrohome.launcher.data.NetworkCheck.Kind.OK }) {
            assertTrue(com.retrohome.launcher.i18n.RU.net.explain(k).isNotBlank())
            assertTrue(com.retrohome.launcher.i18n.EN.net.explain(k).isNotBlank())
        }
    }
}
