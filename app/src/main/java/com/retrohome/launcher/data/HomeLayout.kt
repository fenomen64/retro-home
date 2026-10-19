package com.retrohome.launcher.data

/**
 * Чистая логика раскладки телефонного рабочего стола: без Android, чтобы её можно было тестировать.
 */
object HomeLayout {

    const val DOCK_SLOTS = 5

    fun itemAt(items: List<HomeItem>, page: Int, x: Int, y: Int): HomeItem? =
        if (page == HomeItem.DOCK) items.firstOrNull { it.page == page && it.x == x }
        else items.firstOrNull { it.page == page && it.covers(x, y) }

    /** Свободна ли область w×h с левым верхним углом (x, y); ignoreId — сам перемещаемый элемент. */
    fun areaFree(items: List<HomeItem>, page: Int, x: Int, y: Int, w: Int, h: Int, cols: Int, rows: Int, ignoreId: Long = -1): Boolean {
        if (x < 0 || y < 0 || x + w > cols || y + h > rows) return false
        for (yy in y until y + h) for (xx in x until x + w) {
            val it = itemAt(items, page, xx, yy)
            if (it != null && it.id != ignoreId) return false
        }
        return true
    }

    /** Первое свободное место w×h на странице. */
    fun freeArea(items: List<HomeItem>, page: Int, w: Int, h: Int, cols: Int, rows: Int, ignoreId: Long = -1): Pair<Int, Int>? {
        for (y in 0..rows - h) for (x in 0..cols - w) {
            if (areaFree(items, page, x, y, w, h, cols, rows, ignoreId)) return x to y
        }
        return null
    }

    /**
     * Поставить виджет: на [preferredPage], если там есть место нужного размера, иначе на
     * следующие страницы (новая создаётся при необходимости). Размер не больше сетки.
     */
    fun addWidget(
        items: List<HomeItem>, pageCount: Int, widgetId: Int, provider: String?,
        w: Int, h: Int, preferredPage: Int, cols: Int, rows: Int,
    ): Pair<List<HomeItem>, Int> {
        val sw = w.coerceIn(1, cols)
        val sh = h.coerceIn(1, rows)
        var page = preferredPage.coerceAtLeast(0)
        var pages = pageCount
        while (true) {
            if (page >= pages) pages = page + 1
            val at = freeArea(items, page, sw, sh, cols, rows)
            if (at != null) {
                val item = HomeItem(nextId(items), page, at.first, at.second, widgetId = widgetId, widgetProvider = provider, spanX = sw, spanY = sh)
                return (items + item) to pages
            }
            page++
        }
    }

    /**
     * Перенести виджет. Значки, оказавшиеся под ним, переезжают на свободные клетки
     * (этой страницы, а если места нет — следующих). На другой виджет класть нельзя.
     */
    fun moveWidget(
        items: List<HomeItem>, pageCount: Int, id: Long, page: Int, x: Int, y: Int, cols: Int, rows: Int,
    ): Pair<List<HomeItem>, Int> {
        val wd = items.firstOrNull { it.id == id && it.isWidget } ?: return items to pageCount
        if (page == HomeItem.DOCK || page < 0) return items to pageCount
        val w = wd.spanX.coerceAtMost(cols)
        val h = wd.spanY.coerceAtMost(rows)
        val tx = x.coerceIn(0, cols - w)
        val ty = y.coerceIn(0, rows - h)
        val moved = wd.copy(page = page, x = tx, y = ty, spanX = w, spanY = h)
        val others = items.filter { it.id != id }
        val under = others.filter { !it.isDock && it.page == page && overlaps(it, moved) }
        if (under.any { it.isWidget }) return items to pageCount
        var result = others.filter { it !in under } + moved
        var pages = maxOf(pageCount, page + 1)
        for (icon in under) {
            var p = page
            while (true) {
                if (p >= pages) pages = p + 1
                val cell = freeCell(result, p, cols, rows)
                if (cell != null) { result = result + icon.copy(page = p, x = cell.first, y = cell.second); break }
                p++
            }
        }
        return result to pages
    }

    private fun overlaps(a: HomeItem, b: HomeItem): Boolean =
        a.x < b.x + b.spanX && b.x < a.x + a.spanX && a.y < b.y + b.spanY && b.y < a.y + a.spanY

    /**
     * Изменить размер виджета. Если на прежнем месте новый размер не помещается — ищется
     * другое место на той же странице; если и там нет — размер не меняется.
     */
    fun resizeWidget(items: List<HomeItem>, id: Long, w: Int, h: Int, cols: Int, rows: Int): List<HomeItem> {
        val wd = items.firstOrNull { it.id == id && it.isWidget } ?: return items
        val sw = w.coerceIn(1, cols)
        val sh = h.coerceIn(1, rows)
        val at = if (areaFree(items, wd.page, wd.x, wd.y, sw, sh, cols, rows, id)) wd.x to wd.y
        else freeArea(items, wd.page, sw, sh, cols, rows, id) ?: return items
        return items.map { if (it.id == id) it.copy(x = at.first, y = at.second, spanX = sw, spanY = sh) else it }
    }

    fun nextId(items: List<HomeItem>): Long = (items.maxOfOrNull { it.id } ?: 0L) + 1

    /** Первая свободная клетка на странице, слева направо, сверху вниз. */
    fun freeCell(items: List<HomeItem>, page: Int, cols: Int, rows: Int): Pair<Int, Int>? {
        for (y in 0 until rows) for (x in 0 until cols) {
            if (itemAt(items, page, x, y) == null) return x to y
        }
        return null
    }

    /**
     * Добавить приложение на стол: на [preferredPage], а если там места нет — на следующие страницы.
     * Возвращает новые элементы и, возможно, увеличенное число страниц.
     */
    fun addApp(
        items: List<HomeItem>,
        pageCount: Int,
        key: String,
        preferredPage: Int,
        cols: Int,
        rows: Int,
    ): Pair<List<HomeItem>, Int> {
        var page = preferredPage.coerceAtLeast(0)
        var pages = pageCount
        while (true) {
            if (page >= pages) pages = page + 1
            val cell = freeCell(items, page, cols, rows)
            if (cell != null) {
                val item = HomeItem(nextId(items), page, cell.first, cell.second, app = key)
                return (items + item) to pages
            }
            page++
        }
    }

    /**
     * Переместить элемент в клетку. Если клетка занята:
     *  - приложение на приложение — создаётся папка;
     *  - приложение на папку — приложение добавляется в папку;
     *  - иначе элементы меняются местами.
     */
    fun move(
        items: List<HomeItem>, id: Long, page: Int, x: Int, y: Int, newFolderName: String,
        cols: Int = 99, rows: Int = 99,
    ): List<HomeItem> {
        val dragged = items.firstOrNull { it.id == id } ?: return items
        val ty = if (page == HomeItem.DOCK) 0 else y
        if (dragged.page == page && dragged.x == x && (page == HomeItem.DOCK || dragged.y == ty)) return items
        // Виджет переносится только на свободное место нужного размера и не в док
        if (dragged.isWidget) {
            if (page == HomeItem.DOCK || !areaFree(items, page, x, ty, dragged.spanX, dragged.spanY, cols, rows, id)) return items
            return items.map { if (it.id == id) it.copy(page = page, x = x, y = ty) else it }
        }
        val target = itemAt(items, page, x, ty)
        // На виджет значок не кладём
        if (target != null && target.isWidget) return items
        return when {
            target == null ->
                items.map { if (it.id == id) it.copy(page = page, x = x, y = ty) else it }

            !dragged.isFolder && !target.isFolder -> {
                val folder = target.copy(
                    app = null,
                    folderName = newFolderName,
                    folderApps = listOf(target.app!!, dragged.app!!),
                )
                items.filter { it.id != id }.map { if (it.id == target.id) folder else it }
            }

            !dragged.isFolder && target.isFolder ->
                items.filter { it.id != id }.map {
                    if (it.id == target.id) it.copy(folderApps = (it.folderApps + dragged.app!!).distinct()) else it
                }

            else -> items.map {
                when (it.id) {
                    id -> it.copy(page = target.page, x = target.x, y = target.y)
                    target.id -> it.copy(page = dragged.page, x = dragged.x, y = dragged.y)
                    else -> it
                }
            }
        }
    }

    fun remove(items: List<HomeItem>, id: Long) = items.filter { it.id != id }

    /** Все приложения, у которых уже есть значок на столе, в доке или в папке. */
    fun keysOnHome(items: List<HomeItem>): Set<String> =
        items.flatMap { listOfNotNull(it.app) + it.folderApps }.toSet()

    /**
     * Сверить рабочий стол со списком установленных приложений.
     *  - первый запуск: заполнить док и разложить все приложения по экранам по алфавиту;
     *  - потом: класть на стол только новые установленные приложения (если включено).
     * Приложения, убранные со стола вручную, обратно не возвращаются.
     */
    fun syncApps(d: LauncherData, installedSorted: List<String>, dockKeys: List<String>): LauncherData {
        val cols = d.settings.columns
        val rows = d.settings.rows
        var items = d.home
        var pages = d.pageCount
        if (!d.homeSeeded) {
            dockKeys.take(DOCK_SLOTS).forEachIndexed { i, key ->
                if (itemAt(items, HomeItem.DOCK, i, 0) == null) {
                    items = items + HomeItem(nextId(items), HomeItem.DOCK, i, 0, app = key)
                }
            }
        }
        val onHome = keysOnHome(items)
        val toAdd = when {
            !d.allAppsPlaced -> installedSorted
            d.settings.autoAddNew -> installedSorted.filter { it !in d.knownApps }
            else -> emptyList()
        }.filter { it !in onHome && it !in d.hiddenInDrawer }
        for (key in toAdd) {
            val r = addApp(items, pages, key, 0, cols, rows)
            items = r.first
            pages = r.second
        }
        val known = d.knownApps + installedSorted
        if (items == d.home && pages == d.pageCount && d.homeSeeded && d.allAppsPlaced && known == d.knownApps) return d
        return d.copy(home = items, pageCount = pages, homeSeeded = true, allAppsPlaced = true, knownApps = known)
    }

    /** Убрать приложение из папки; папка из одного приложения превращается в обычный значок. */
    fun removeFromFolder(items: List<HomeItem>, folderId: Long, key: String): List<HomeItem> =
        items.mapNotNull { item ->
            if (item.id != folderId) return@mapNotNull item
            val rest = item.folderApps - key
            when (rest.size) {
                0 -> null
                1 -> item.copy(app = rest.first(), folderApps = emptyList(), folderName = "")
                else -> item.copy(folderApps = rest)
            }
        }

    /** Вынести приложение из папки на свободное место той же страницы (или дальше). */
    fun takeOutOfFolder(
        items: List<HomeItem>,
        pageCount: Int,
        folderId: Long,
        key: String,
        cols: Int,
        rows: Int,
    ): Pair<List<HomeItem>, Int> {
        val folder = items.firstOrNull { it.id == folderId } ?: return items to pageCount
        val stripped = removeFromFolder(items, folderId, key)
        val page = if (folder.isDock) 0 else folder.page
        return addApp(stripped, pageCount, key, page, cols, rows)
    }

    /** Удалить с рабочего стола значки удалённых приложений. */
    fun pruneMissing(items: List<HomeItem>, installed: Set<String>): List<HomeItem> =
        items.mapNotNull { item ->
            if (item.isWidget) {
                item
            } else if (!item.isFolder) {
                if (item.app in installed) item else null
            } else {
                val left = item.folderApps.filter { it in installed }
                when (left.size) {
                    0 -> null
                    1 -> item.copy(app = left.first(), folderApps = emptyList(), folderName = "")
                    else -> item.copy(folderApps = left)
                }
            }
        }

    /** Удалить пустую страницу и сдвинуть следующие. */
    fun deletePage(items: List<HomeItem>, pageCount: Int, page: Int): Pair<List<HomeItem>, Int> {
        if (pageCount <= 1 || items.any { it.page == page }) return items to pageCount
        val shifted = items.map { if (it.page > page) it.copy(page = it.page - 1) else it }
        return shifted to pageCount - 1
    }

    /**
     * Смена размера сетки: значки каждого экрана выстраиваются заново в прежнем порядке
     * (слева направо, сверху вниз) без пустых клеток. Виджеты остаются на своих местах
     * (если не помещаются — сдвигаются или уменьшаются до размера сетки), значки обтекают их.
     * Что не поместилось — уходит на вставленный следом экран. Пустые экраны и док не трогаются.
     */
    fun reflow(items: List<HomeItem>, pageCount: Int, cols: Int, rows: Int): Pair<List<HomeItem>, Int> {
        val dock = items.filter { it.isDock }
        val result = ArrayList<HomeItem>(items.size)
        var outPage = 0
        for (page in 0 until maxOf(pageCount, (items.maxOfOrNull { it.page } ?: -1) + 1)) {
            val onPage = items.filter { it.page == page && !it.isDock }.sortedWith(compareBy({ it.y }, { it.x }))
            if (onPage.isEmpty()) { outPage++; continue }
            var pending = onPage
            while (pending.isNotEmpty()) {
                val (placed, rest) = packPage(pending, outPage, cols, rows)
                result += placed
                outPage++
                pending = rest
            }
        }
        return (result + dock) to maxOf(outPage, 1)
    }

    /**
     * Разложить элементы на одну страницу: сначала виджеты (стараясь сохранить их место),
     * потом значки по порядку в свободные клетки. Возвращает разложенное и то, что не влезло.
     */
    private fun packPage(ordered: List<HomeItem>, page: Int, cols: Int, rows: Int): Pair<List<HomeItem>, List<HomeItem>> {
        val placed = ArrayList<HomeItem>()
        val rest = ArrayList<HomeItem>()
        for (wd in ordered.filter { it.isWidget }) {
            val w = wd.spanX.coerceAtMost(cols)
            val h = wd.spanY.coerceAtMost(rows)
            val x = wd.x.coerceAtMost(cols - w)
            val y = wd.y.coerceAtMost(rows - h)
            val at = if (areaFree(placed, page, x, y, w, h, cols, rows)) x to y else freeArea(placed, page, w, h, cols, rows)
            if (at == null) rest += wd else placed += wd.copy(page = page, x = at.first, y = at.second, spanX = w, spanY = h)
        }
        for (icon in ordered.filter { !it.isWidget }) {
            val cell = freeCell(placed, page, cols, rows)
            if (cell == null) rest += icon else placed += icon.copy(page = page, x = cell.first, y = cell.second)
        }
        return placed to rest
    }

    /**
     * Разложить всё по алфавиту: сначала папки, потом приложения; дубли значков убираются.
     * Виджеты остаются на своих местах, значки занимают свободные клетки. Док не меняется.
     */
    fun sortAlphabetically(
        items: List<HomeItem>,
        cols: Int,
        rows: Int,
        labelOf: (String) -> String,
    ): Pair<List<HomeItem>, Int> {
        val dock = items.filter { it.isDock }
        val rest = items.filter { !it.isDock }
        val widgets = rest.filter { it.isWidget }
        val folders = rest.filter { it.isFolder }.sortedBy { it.folderName.lowercase() }
        val inDock = dock.mapNotNull { it.app }.toSet()
        val apps = rest.filter { !it.isFolder && !it.isWidget && it.app !in inDock }
            .distinctBy { it.app }
            .sortedBy { labelOf(it.app!!).lowercase() }
        val placed = ArrayList<HomeItem>(widgets)
        var page = 0
        for (item in folders + apps) {
            while (true) {
                val cell = freeCell(placed, page, cols, rows)
                if (cell != null) { placed += item.copy(page = page, x = cell.first, y = cell.second); break }
                page++
            }
        }
        val pages = maxOf(1, (placed.maxOfOrNull { it.page } ?: 0) + 1)
        return (placed + dock) to pages
    }

    /** При уменьшении сетки значки, оказавшиеся за краем, переносятся на свободные места. */
    fun fitToGrid(items: List<HomeItem>, pageCount: Int, cols: Int, rows: Int): Pair<List<HomeItem>, Int> {
        val inside = items.filter { it.isDock || (it.x < cols && it.y < rows) }
        val outside = items.filter { !it.isDock && (it.x >= cols || it.y >= rows) }
        var result = inside
        var pages = pageCount
        for (item in outside) {
            var page = item.page
            while (true) {
                if (page >= pages) pages = page + 1
                val cell = freeCell(result, page, cols, rows)
                if (cell != null) {
                    result = result + item.copy(page = page, x = cell.first, y = cell.second)
                    break
                }
                page++
            }
        }
        return result to pages
    }
}
