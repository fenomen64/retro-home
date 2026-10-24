package com.retrohome.launcher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Обложка и описания одной игры, сохранённые на устройстве. */
@Serializable
data class GameMeta(
    /** Имя файла обложки в папке covers, если нашлась. */
    val cover: String? = null,
    val descEn: String? = null,
    val descRu: String? = null,
    val wikiEn: String? = null,
    val wikiRu: String? = null,
    /** Уже искали (чтобы не дёргать сеть при каждом запуске). */
    val coverTried: Boolean = false,
    val descTried: Boolean = false,
    /** Официальное название (из базы обложек или Википедии) — показывается на карточке. */
    val name: String? = null,
    /** Версия алгоритма поиска: при улучшении поиска ненайденное ищется заново. */
    val ver: Int = 1,
    /** Откуда взято описание: wikipedia, wikidata или eshop. */
    val srcEn: String? = null,
    val srcRu: String? = null,
    /** Был сетевой сбой (например, лимит запросов): не искать повторно раньше этого времени. */
    val retryAt: Long = 0,
    /** Имя обложки в базе libretro — по нему же ищутся снимки экрана. */
    val thumbName: String? = null,
    /** Снимок экрана из игры (файл в covers) — фон за выбранной игрой. */
    val snap: String? = null,
    val snapTried: Boolean = false,
    /** Факты для страницы игры (из Викиданных). */
    val qid: String? = null,
    val year: Int? = null,
    val genresEn: List<String> = emptyList(),
    val genresRu: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val factsTried: Boolean = false,
    /** Описание проверено на соответствие консоли (старые описания перепроверяются один раз). */
    val platformChecked: Boolean = false,
    /** Как искалось описание в последний раз — для экрана «Как искалось описание». */
    val lookupLog: List<String> = emptyList(),
    /** Версия проверки найденного описания: при её росте описания перепроверяются один раз. */
    val textVer: Int = 0,
)

/**
 * 2 — отсев статей о персонажах, франшизах и т.п.
 * 4 — короткие названия больше не совпадают с длинными («The Captain» ≠ «Captain Toad»):
 *     описания, взятые из статьи с непохожим названием, ищутся заново.
 */
// 5 — поиск всегда с названием консоли; для названий из одного слова статья должна подтверждать
//     консоль (иначе находился, например, фильм с тем же названием) — такие описания ищутся заново.
const val TEXT_VERSION = 5

/**
 * 3 — цепочка источников описаний (Википедия → Викиданные → eShop) и обложки Switch.
 * 4 — сетевые сбои больше не считаются «не нашлось», статьи грузятся пачкой.
 * 5 — проверка консоли и отсев статей о персонажах: ненайденное ищется заново.
 */
// 6: один раз заново ищем то, что раньше не нашлось (могло не найтись из-за сбоев сети в старых версиях)
const val META_VERSION = 6

/** Источники описаний — по ним показывается подпись под текстом. */
object DescSource {
    const val WIKIPEDIA = "wikipedia"
    const val WIKIDATA = "wikidata"
    const val ESHOP = "eshop"
}

/** Расшифровка HTML-сущностей: &amp; &#233; &#x2019; и т.п. */
object Html {
    private val named = mapOf("amp" to "&", "quot" to "\"", "apos" to "'", "lt" to "<", "gt" to ">", "nbsp" to " ")
    private val entity = Regex("&(#x[0-9a-fA-F]+|#[0-9]+|[a-zA-Z]+);")

    fun decode(s: String): String = entity.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") || e.startsWith("#X") -> e.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
            e.startsWith("#") -> e.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
            else -> named[e]
        } ?: m.value
    }

    fun stripTags(s: String) = s.replace(Regex("<[^>]*>"), "")
}

/** Каталоги обложек libretro для систем. У Switch каталога нет. */
object Thumbnails {
    const val BASE = "https://thumbnails.libretro.com"

    val dirs: Map<GameSystem, String> = mapOf(
        GameSystem.NES to "Nintendo - Nintendo Entertainment System",
        GameSystem.SNES to "Nintendo - Super Nintendo Entertainment System",
        GameSystem.N64 to "Nintendo - Nintendo 64",
        GameSystem.GC to "Nintendo - GameCube",
        GameSystem.WII to "Nintendo - Wii",
        GameSystem.WIIU to "Nintendo - Wii U",
        GameSystem.GB to "Nintendo - Game Boy",
        GameSystem.GBC to "Nintendo - Game Boy Color",
        GameSystem.GBA to "Nintendo - Game Boy Advance",
        GameSystem.NDS to "Nintendo - Nintendo DS",
        GameSystem.N3DS to "Nintendo - Nintendo 3DS",
        GameSystem.PS1 to "Sony - PlayStation",
        GameSystem.PS2 to "Sony - PlayStation 2",
        GameSystem.PS3 to "Sony - PlayStation 3",
        GameSystem.PSP to "Sony - PlayStation Portable",
        GameSystem.VITA to "Sony - PlayStation Vita",
        GameSystem.MD to "Sega - Mega Drive - Genesis",
        GameSystem.SMS to "Sega - Master System - Mark III",
        GameSystem.GG to "Sega - Game Gear",
        GameSystem.SATURN to "Sega - Saturn",
        GameSystem.DC to "Sega - Dreamcast",
        GameSystem.ARCADE to "FBNeo - Arcade Games",
        GameSystem.PCE to "NEC - PC Engine - TurboGrafx 16",
        GameSystem.SCUMMVM to "ScummVM",
    )

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    // --- Запасной источник: те же обложки в репозиториях libretro-thumbnails на GitHub ---
    // (thumbnails.libretro.com у части провайдеров не открывается: DNS или блокировка)

    const val MIRROR_RAW = "https://raw.githubusercontent.com/libretro-thumbnails"
    const val MIRROR_API = "https://api.github.com/repos/libretro-thumbnails"

    /** «Nintendo - Game Boy Advance» → репозиторий «Nintendo_-_Game_Boy_Advance». */
    fun mirrorRepo(system: GameSystem): String? = dirs[system]?.replace(' ', '_')

    /** Файл картинки: dir — Named_Boxarts, Named_Snaps или Named_Titles. */
    fun fileUrl(system: GameSystem, dir: String, name: String, mirror: Boolean): String? =
        if (mirror) mirrorRepo(system)?.let { "$MIRROR_RAW/$it/master/$dir/${enc(name)}.png" }
        else dirs[system]?.let { "$BASE/${enc(it)}/$dir/${enc(name)}.png" }

    /** Содержимое папки репозитория (GitHub API: дерево по ветке или по sha). */
    fun mirrorTreeUrl(system: GameSystem, ref: String): String? = mirrorRepo(system)?.let { "$MIRROR_API/$it/git/trees/$ref" }

    private fun treeEntries(json: String): List<JsonObject> =
        runCatching { Json.parseToJsonElement(json).jsonObject["tree"]?.jsonArray?.map { it.jsonObject } }.getOrNull().orEmpty()

    /** sha вложенной папки в ответе GitHub API. */
    fun treeDirSha(json: String, dir: String): String? = treeEntries(json).firstOrNull {
        it["path"]?.jsonPrimitive?.content == dir && it["type"]?.jsonPrimitive?.content == "tree"
    }?.get("sha")?.jsonPrimitive?.content

    /** Имена обложек (без .png) из ответа GitHub API — тот же список, что на сайте libretro. */
    fun treePngNames(json: String): List<String> = treeEntries(json).mapNotNull { e ->
        e["path"]?.jsonPrimitive?.content?.takeIf { it.endsWith(".png", ignoreCase = true) }?.dropLast(4)
    }

    /**
     * Часть файлов в репозитории — ссылки на другой файл (одна обложка на несколько версий игры).
     * Сайт libretro отдаёт по ним картинку, а GitHub — текст ссылки: имя файла, на который она ведёт.
     */
    fun linkTarget(bytes: ByteArray): String? {
        if (bytes.size < 5 || bytes.size > 512) return null
        val isImage = (bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte()) || (bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte())
        if (isImage) return null
        val text = String(bytes).trim()
        if ('\n' in text || !text.endsWith(".png", ignoreCase = true)) return null
        return text.substringAfterLast('/').dropLast(4)
    }

    fun indexUrl(system: GameSystem): String? = dirs[system]?.let { "$BASE/${enc(it)}/Named_Boxarts/" }

    fun imageUrl(system: GameSystem, name: String): String? =
        dirs[system]?.let { "$BASE/${enc(it)}/Named_Boxarts/${enc(name)}.png" }

    /** Снимок экрана из игры; если его нет — заставка (титульный экран). */
    fun snapUrls(system: GameSystem, name: String): List<String> =
        dirs[system]?.let { d -> listOf("Named_Snaps", "Named_Titles").map { "$BASE/${enc(d)}/$it/${enc(name)}.png" } }.orEmpty()

    /** Правило libretro: эти символы в именах заменяются на «_». */
    fun sanitize(name: String): String = name.map { if (it in "&*/:`<>?\\|\"") '_' else it }.joinToString("")

    private val entry = Regex("""<a href="[^"]+">([^<]+)\.png</a>""")

    /** Имена обложек из страницы-списка каталога. */
    fun parseIndex(html: String): List<String> =
        entry.findAll(html).map { decodeHtml(it.groupValues[1]) }.toList()

    private fun decodeHtml(s: String) = s.replace("&amp;", "&").replace("&#39;", "'").replace("&quot;", "\"")
        .replace("&lt;", "<").replace("&gt;", ">")

    private val roman = mapOf(
        "ii" to "2", "iii" to "3", "iv" to "4", "v" to "5", "vi" to "6", "vii" to "7", "viii" to "8",
        "ix" to "9", "x" to "10", "xi" to "11", "xii" to "12", "xiii" to "13", "xiv" to "14", "xv" to "15",
    )
    private val stop = setOf("the", "a", "an", "of", "and", "de", "la", "le", "les", "el", "no")

    private fun plain(s: String) =
        java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    /** Слова названия: без регистра и артиклей, римские цифры в арабские, «&» как «and». */
    fun words(title: String): List<String> =
        plain(title.replace("&", " and ")).split(Regex("[^\\p{L}\\p{Nd}]+")).filter { it.isNotEmpty() }
            .map { roman[it] ?: it }

    /** Сравнение названий целиком: «Final Fantasy IX» = «final fantasy 9» = «FinalFantasy9». */
    fun norm(title: String): String {
        val w = words(TitleParser.full("$title.x").title)
        return (if (w.firstOrNull() == "the") w.drop(1) else w).joinToString("")
    }

    fun tokens(title: String): Set<String> =
        words(title).filter { it !in stop }.map { if (it.length > 3 && it.endsWith("s")) it.dropLast(1) else it }.toSet()

    private fun numbers(t: Set<String>) = t.filter { w -> w.all { it.isDigit() } }.toSet()

    /** Подготовленный список обложек системы: считается один раз. */
    class Index(val names: List<String>) {
        internal val byNorm: Map<String, List<String>> = names.groupBy { norm(it) }
        internal val tokenized: List<Pair<String, Set<String>>> =
            names.map { it to tokens(TitleParser.full("$it.x").title) }
    }

    private val unwanted = listOf("demo", "beta", "proto", "sample", "taikenban", "kiosk", "preview", "trial", "hack", "making of", "passport", "sampler")
    private val regionOrder = listOf("usa", "world", "europe", "australia", "uk", "russia", "japan")

    fun match(fileName: String, names: List<String>): String? = match(fileName, Index(names))

    /**
     * Найти обложку для файла игры.
     * 1) точное имя файла; 2) то же название (без учёта регистра, знаков, римских цифр);
     * 3) похожее название по словам — номер части из нашего названия обязан совпасть.
     * Среди подходящих выбирается регион из имени файла, иначе USA, и без пометок «Demo/Beta».
     */
    fun match(fileName: String, index: Index, titleOverride: String? = null): String? {
        val ext = GameFiles.extension(fileName)
        val base = if (ext.isNotEmpty()) fileName.dropLast(ext.length + 1) else fileName
        if (titleOverride == null) {
            val wanted = sanitize(base)
            index.names.firstOrNull { it == wanted }?.let { return it }
        }
        val parsed = TitleParser.full(fileName)
        val title = titleOverride ?: parsed.title
        val ourTags = TitleParser.regionTokens(parsed.tags)
        val key = norm(title)
        if (key.isEmpty()) return null
        var candidates = index.byNorm[key].orEmpty()
        if (candidates.isEmpty()) {
            val ours = tokens(title)
            if (ours.isEmpty()) return null
            val ourNums = numbers(ours)
            var best = 0.0
            val scored = ArrayList<Pair<String, Double>>()
            for ((name, theirs) in index.tokenized) {
                if (theirs.isEmpty() || !theirs.containsAll(ourNums)) continue
                val common = ours.count { it in theirs }
                if (common == 0) continue
                val dice = 2.0 * common / (ours.size + theirs.size)
                if (dice >= 0.75) { scored += name to dice; if (dice > best) best = dice }
            }
            candidates = scored.filter { it.second >= best - 0.001 }.map { it.first }
        }
        if (candidates.isEmpty()) return null
        return candidates.minByOrNull { name ->
            val theirTags = TitleParser.regionTokens(TitleParser.full("$name.x").tags) +
                TitleParser.full("$name.x").tags.map { it.lowercase() }
            var score = 0
            if (theirTags.any { t -> unwanted.any { t.contains(it) } }) score += 1000
            if (parsed.disc == null && theirTags.any { it.startsWith("disc") }) score += 50
            if (Regex("\\(disc \\d+\\)", RegexOption.IGNORE_CASE).containsMatchIn(name)) score += 50
            score -= theirTags.count { it in ourTags } * 100
            val region = theirTags.mapNotNull { t -> regionOrder.indexOf(t).takeIf { it >= 0 } }.minOrNull() ?: regionOrder.size
            score += region * 10
            score += theirTags.size
            score
        }
    }

    /**
     * Красивое название из имени обложки: «Final Fantasy IX (USA) (Disc 1)» -> «Final Fantasy IX».
     * В именах libretro «&» и «:» заменены на «_» — возвращаем их обратно.
     */
    fun displayName(name: String): String =
        TitleParser.full(name.replace(" _ ", " & ").replace("_ ", ": ").replace('_', ' ') + ".x").title
            .replace(Regex("\\s+"), " ").trim()
}

/** Поиск описаний в Википедии: английская статья и русская по межъязыковой ссылке. */
object Wiki {
    private val json = Json { ignoreUnknownKeys = true }

    fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun searchUrl(lang: String, query: String) =
        "https://$lang.wikipedia.org/w/api.php?action=query&list=search&srlimit=10&srprop=redirecttitle&format=json&formatversion=2&srsearch=${enc(query)}"

    fun summaryUrl(lang: String, title: String) =
        "https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_'))}?redirect=true"

    fun langLinkUrl(title: String, to: String, from: String = "en") =
        "https://$from.wikipedia.org/w/api.php?action=query&prop=langlinks&format=json&formatversion=2&redirects=1&lllang=$to&titles=${enc(title)}"

    fun searchQuery(lang: String, title: String, system: GameSystem) =
        if (lang == "ru") "$title компьютерная игра ${system.en}" else "$title video game ${system.en}"

    fun parseSearch(body: String): List<String> = parseHits(body).map { it.title }

    /** Результат поиска: статья и перенаправление, по которому она нашлась (например, старое название игры). */
    data class Hit(val title: String, val redirect: String? = null)

    fun parseHits(body: String): List<Hit> = runCatching {
        json.parseToJsonElement(body).jsonObject["query"]!!.jsonObject["search"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val r = o["redirecttitle"]
            val redirect = when (r) {
                is JsonPrimitive -> r.content
                is JsonObject -> (r["title"] ?: r["mTextform"] ?: r["*"])?.jsonPrimitive?.content
                else -> null
            }
            Hit(o["title"]!!.jsonPrimitive.content, redirect?.takeIf { it.isNotBlank() })
        }
    }.getOrDefault(emptyList())

    fun parseLangLink(body: String): String? = runCatching {
        val pages = json.parseToJsonElement(body).jsonObject["query"]!!.jsonObject["pages"] as JsonArray
        (pages.first().jsonObject["langlinks"] as JsonArray).first().jsonObject["title"]!!.jsonPrimitive.content
    }.getOrNull()

    data class Summary(val title: String, val description: String, val extract: String)

    /**
     * Несколько статей одним запросом: вступление статьи, краткое описание,
     * признак страницы неоднозначности и ссылка на статью на другом языке.
     */
    fun pagesUrl(lang: String, titles: List<String>, linkTo: String? = null): String =
        "https://$lang.wikipedia.org/w/api.php?action=query&format=json&formatversion=2&redirects=1" +
            "&prop=extracts%7Cpageprops" + (linkTo?.let { "%7Clanglinks&lllang=$it&lllimit=max" } ?: "") +
            "&exintro=1&explaintext=1&exlimit=max&ppprop=disambiguation%7Cwikibase-shortdesc%7Cwikibase_item" +
            "&titles=" + titles.joinToString("%7C") { enc(it) }

    data class Page(
        val title: String,
        val description: String,
        val extract: String,
        val disambiguation: Boolean,
        /** Заголовок статьи на другом языке, если есть. */
        val link: String?,
        /** Элемент Викиданных (Q…) — оттуда берутся жанр, разработчик, год. */
        val qid: String? = null,
    ) {
        fun summary() = Summary(title, description, extract)
    }

    /** Разбор ответа pagesUrl: запрошенный заголовок -> статья (с учётом перенаправлений). */
    fun parsePages(body: String, requested: List<String>): Map<String, Page> = runCatching {
        val q = json.parseToJsonElement(body).jsonObject["query"]!!.jsonObject
        fun mapping(key: String): Map<String, String> = (q[key] as? JsonArray).orEmpty().mapNotNull {
            val o = it.jsonObject
            val from = o["from"]?.jsonPrimitive?.content ?: return@mapNotNull null
            val to = o["to"]?.jsonPrimitive?.content ?: return@mapNotNull null
            from to to
        }.toMap()
        val normalized = mapping("normalized")
        val redirects = mapping("redirects")
        val pages = (q["pages"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e.jsonObject
            if (o.containsKey("missing") || o.containsKey("invalid")) return@mapNotNull null
            val props = o["pageprops"] as? JsonObject
            val link = (o["langlinks"] as? JsonArray)?.firstOrNull()?.jsonObject?.get("title")?.jsonPrimitive?.content
            Page(
                o["title"]!!.jsonPrimitive.content,
                props?.get("wikibase-shortdesc")?.jsonPrimitive?.content ?: "",
                Texts.shorten(o["extract"]?.jsonPrimitive?.content ?: "", limit = 1400, enough = 600) ?: "",
                props?.containsKey("disambiguation") == true,
                link,
                props?.get("wikibase_item")?.jsonPrimitive?.content,
            )
        }.associateBy { it.title }
        requested.mapNotNull { r ->
            val n = normalized[r] ?: r
            val t = redirects[n] ?: n
            pages[t]?.let { r to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun parseSummary(body: String): Summary? = runCatching {
        val o: JsonObject = json.parseToJsonElement(body).jsonObject
        if (o["type"]?.jsonPrimitive?.content == "disambiguation") return null
        Summary(
            o["title"]!!.jsonPrimitive.content,
            o["description"]?.jsonPrimitive?.content ?: "",
            o["extract"]?.jsonPrimitive?.content ?: "",
        )
    }.getOrNull()

    /** Статья действительно про игру. */
    fun looksLikeGame(s: Summary, lang: String): Boolean {
        val gameWords = if (lang == "ru") listOf("игра", "игры", "игру", "видеоигр", "игр ") else listOf("game")
        fun firstIndex(text: String, words: List<String>) = words.mapNotNull { w -> text.indexOf(w).takeIf { it >= 0 } }.minOrNull()
        // Определение в начале статьи: «X — компьютерная игра…», а не «X — персонаж игры…».
        // Скобки убираем: там японские и английские названия с сокращениями («яп.», «англ.»)
        var lead = s.extract.take(500)
        repeat(3) { lead = lead.replace(Regex("\\([^()]*\\)"), "") }
        // Само название статьи не учитываем: «Pokémon Trading Card Game» для Game Boy — всё же игра
        val name = s.title.replace(Regex("""\s*\([^)]*\)"""), "").trim().lowercase()
        var body = lead.take(350).lowercase()
        if (name.length >= 3) body = body.replace(name, " ")
        val head = s.description.lowercase() + " · " + body
        val game = firstIndex(head, gameWords) ?: return false
        val other = listOfNotNull(firstIndex(head, notGame), gameThenNot.find(head)?.range?.first).minOrNull()
        return other == null || game < other
    }

    /** «video game series», «game character», «game console» — слово «game» тут не про саму игру. */
    private val gameThenNot = Regex("""\bgames?\s+(series|franchise|character|console|company|developer|publisher|studio|soundtrack|music|composer|designer|engine|magazine|award)""")

    /** Статьи не про саму игру: персонажи, франшизы, фильмы, музыка, компании, приставки. */
    private val notGame = listOf(
        "персонаж", "character", "франшиз", "franchise", "media mix",
        "фильм", "film", "movie", "аниме", "anime", "манга", "manga", "телесериал", "television series", "мультсериал",
        "саундтрек", "soundtrack", "альбом", "album", "компания", "company", "студия", "studio", "developer of",
        "игровая приставка", "игровая консоль", "video game console", "handheld game console",
        // серии игр, карточные и настольные игры
        "серия", "series", "карточная игра", "коллекционная карточная", "card game", "trading card",
        "настольная", "board game", "tabletop",
    )


    /**
     * Подходящие заголовки из результатов поиска, лучшие первыми.
     * Совсем непохожие отбрасываются, чтобы не показать описание чужой игры.
     */
    fun rankTitles(results: List<String>, gameTitle: String): List<String> = rankHits(results.map { Hit(it) }, gameTitle)

    /** Насколько название похоже на искомое: 0 — то же, 1 — начало совпадает, 2 — содержит; null — непохоже. */
    fun similarity(candidate: String, gameTitle: String): Int? {
        val key = Thumbnails.norm(gameTitle)
        if (key.isEmpty()) return null
        val n = Thumbnails.norm(candidate.replace(Regex("""\s*\([^)]*\)"""), ""))
        if (n.isEmpty()) return null
        // Название из одного слова («The Captain», «Tetris») слишком общее: статья с более
        // длинным названием («Captain Toad: Treasure Tracker», «Tetris Effect») — это другая игра
        val single = titleTokens(gameTitle).size < 2
        return when {
            n == key -> 0
            single -> null
            n.startsWith(key) -> 1
            // Статья названа короче игры: «Skies of Arcadia» для «Skies of Arcadia Legends».
            // Но не на два слова короче и не без номера части: «Pokémon» — не «Pokémon Brilliant Diamond»,
            // «The Legend of Heroes» — не «The Legend of Heroes: A Tear of Vermillion», «Final Fantasy» — не «Final Fantasy VII»
            key.startsWith(n) && n.length >= 4 && shorterOk(candidate, gameTitle) -> 1
            key.length >= 6 && n.contains(key) -> 2
            // Те же слова, у статьи лишь номер части: «Legend of Heroes - Song of the Ocean» = «The Legend of Heroes III: Song of the Ocean»
            sameWords(candidate, gameTitle, extraNumbers = true) -> 2
            else -> null
        }
    }

    /**
     * Статью раньше (до TEXT_VERSION 4) приняли бы только из-за начала названия: игра из одного
     * слова, статья длиннее — «The Captain» → «Captain Toad: Treasure Tracker». Такие описания ищем заново.
     */
    fun looseOnly(candidate: String, gameTitle: String): Boolean {
        val key = Thumbnails.norm(gameTitle)
        val n = Thumbnails.norm(candidate.replace(Regex("""\s*\([^)]*\)"""), ""))
        if (key.isEmpty() || n == key || titleTokens(gameTitle).size >= 2) return false
        return n.startsWith(key) || key.length >= 6 && n.contains(key)
    }

    /** Название из одного слова («The Captain», «Okami»): по нему легко найти чужую статью — нужна проверка консоли. */
    fun isShortTitle(t: String) = titleTokens(t).size < 2

    private fun titleTokens(t: String) = Thumbnails.tokens(t.replace(Regex("""\s*\([^)]*\)"""), ""))
    private fun isNumber(w: String) = w.all { it.isDigit() }

    private fun shorterOk(candidate: String, gameTitle: String): Boolean {
        val theirs = titleTokens(candidate)
        val missing = titleTokens(gameTitle) - theirs
        return theirs.size >= 2 && missing.size <= 1 && missing.none(::isNumber)
    }

    /** Слова совпадают (без артиклей, «:» и «-», множественного числа); extraNumbers — у статьи может быть ещё номер. */
    fun sameWords(candidate: String, gameTitle: String, extraNumbers: Boolean = false): Boolean {
        val ours = titleTokens(gameTitle)
        val theirs = titleTokens(candidate)
        if (ours.size < 2 || !theirs.containsAll(ours)) return false
        val extra = theirs - ours
        return extra.isEmpty() || extraNumbers && extra.all(::isNumber)
    }

    /** Название статьи — точно название игры (такой статье можно верить, даже если консоль указана только в тексте). */
    fun sameTitle(candidate: String, gameTitle: String): Boolean =
        similarity(candidate, gameTitle) == 0 || sameWords(candidate, gameTitle)

    /**
     * Игра упомянута в начале статьи под другим названием — так в Википедии пишут
     * региональные названия: «Avatar: The Last Airbender (known as Avatar: The Legend
     * of Aang in Europe) is a video game…». Короткие названия не проверяем — слишком легко совпасть.
     */
    fun leadMentions(extract: String, gameTitle: String): Boolean {
        val w = Thumbnails.words(gameTitle)
        val key = (if (w.firstOrNull() == "the") w.drop(1) else w).joinToString("")
        if (key.length < 8) return false
        return Thumbnails.words(extract.take(600)).joinToString("").contains(key)
    }

    /** То же для результатов с перенаправлениями: статья подходит, если похоже её название или перенаправление. */
    fun rankHits(results: List<Hit>, gameTitle: String): List<String> =
        results.mapNotNull { h ->
            val rank = listOfNotNull(similarity(h.title, gameTitle), h.redirect?.let { similarity(it, gameTitle) }).minOrNull()
            // При равенстве: сначала статья без уточнения, затем «(video game)», затем остальные (ремейки и т.п.)
            val tie = when {
                !h.title.contains('(') -> 0
                h.title.endsWith("(video game)") || h.title.endsWith("(компьютерная игра)") -> 1
                else -> 2
            }
            rank?.let { h.title to it * 10 + tie }
        }.sortedBy { it.second }.map { it.first }.distinct()
}

/**
 * Викиданные — второй источник описаний. Находит игру по названию и синонимам
 * (часто там, где поиск Википедии промахнулся), даёт ссылки на статьи Википедии
 * и факты: жанр, разработчик, издатель, год, платформы, серия.
 */
object Wikidata {
    private val json = Json { ignoreUnknownKeys = true }
    private const val API = "https://www.wikidata.org/w/api.php"

    /** Свойства, из которых собирается описание. */
    const val GENRE = "P136"
    const val DEVELOPER = "P178"
    const val PUBLISHER = "P123"
    const val PLATFORM = "P400"
    const val SERIES = "P179"
    const val DATE = "P577"
    private val itemProps = listOf(GENRE, DEVELOPER, PUBLISHER, PLATFORM, SERIES)

    fun searchUrl(title: String, lang: String) =
        "$API?action=wbsearchentities&format=json&type=item&limit=10&language=$lang&uselang=$lang&search=${Wiki.enc(title)}"

    fun entityUrl(id: String) =
        "$API?action=wbgetentities&format=json&props=labels%7Cdescriptions%7Cclaims%7Csitelinks&languages=en%7Cru&sitefilter=enwiki%7Cruwiki&ids=$id"

    fun labelsUrl(ids: Collection<String>) =
        "$API?action=wbgetentities&format=json&props=labels&languages=en%7Cru&ids=${ids.joinToString("%7C")}"

    data class Candidate(val id: String, val label: String, val description: String, val matched: String?)

    fun parseSearch(body: String): List<Candidate> = runCatching {
        json.parseToJsonElement(body).jsonObject["search"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val display = o["display"] as? JsonObject
            val label = o["label"]?.jsonPrimitive?.content
                ?: (display?.get("label") as? JsonObject)?.get("value")?.jsonPrimitive?.content ?: ""
            val desc = o["description"]?.jsonPrimitive?.content
                ?: (display?.get("description") as? JsonObject)?.get("value")?.jsonPrimitive?.content ?: ""
            val matched = (o["match"] as? JsonObject)?.get("text")?.jsonPrimitive?.content
            Candidate(o["id"]!!.jsonPrimitive.content, label, desc, matched)
        }
    }.getOrDefault(emptyList())

    private fun isGame(c: Candidate, lang: String): Boolean {
        val d = c.description.lowercase()
        if (listOf("series", "franchise", "серия", "франшиза", "character", "персонаж", "soundtrack", "album", "film", "фильм").any { it in d }) return false
        return if (lang == "ru") "игра" in d else "game" in d
    }

    /** Лучший элемент для игры: описание «video game», название или синоним похожи на искомое. */
    fun pick(candidates: List<Candidate>, title: String, lang: String = "en"): String? = pickAll(candidates, title, lang).firstOrNull()

    /** Все подходящие элементы, лучшие первыми (среди них потом выбирается нужная консоль). */
    fun pickAll(candidates: List<Candidate>, title: String, lang: String = "en"): List<String> =
        candidates.filter { isGame(it, lang) }.mapNotNull { c ->
            val rank = listOfNotNull(Wiki.similarity(c.label, title), c.matched?.let { Wiki.similarity(it, title) }).minOrNull()
            rank?.let { c.id to it }
        }.sortedBy { it.second }.map { it.first }.distinct()

    data class Entity(
        val id: String,
        val labels: Map<String, String>,
        val descriptions: Map<String, String>,
        /** Статьи Википедии: «en» / «ru» -> заголовок. */
        val wiki: Map<String, String>,
        /** Свойство -> элементы (жанры, платформы и т.д.). */
        val refs: Map<String, List<String>>,
        val year: Int?,
        /** Год выхода на конкретной платформе (уточнение P400 у даты). */
        val yearOn: Map<String, Int> = emptyMap(),
        /** Издатели на конкретной платформе. */
        val publishersOn: Map<String, List<String>> = emptyMap(),
    ) {
        fun referencedIds(): Set<String> = refs.values.flatMap { it.take(6) }.toSet() + publishersOn.values.flatten()
    }

    private fun langValues(o: JsonObject?): Map<String, String> =
        o?.mapNotNull { (k, v) -> (v as? JsonObject)?.get("value")?.jsonPrimitive?.content?.let { k to it } }?.toMap().orEmpty()

    fun parseEntity(body: String): Entity? = parseEntities(body).values.firstOrNull()

    /** Несколько элементов одним ответом (для проверки платформ у кандидатов). */
    fun parseEntities(body: String): Map<String, Entity> = runCatching {
        json.parseToJsonElement(body).jsonObject["entities"]!!.jsonObject.entries
            .mapNotNull { (id, el) -> entityOf(id, el.jsonObject)?.let { id to it } }.toMap()
    }.getOrDefault(emptyMap())

    fun entitiesUrl(ids: Collection<String>) =
        "$API?action=wbgetentities&format=json&props=labels%7Cdescriptions%7Cclaims%7Csitelinks&languages=en%7Cru&sitefilter=enwiki%7Cruwiki&ids=${ids.joinToString("%7C")}"

    private fun entityOf(id: String, o: JsonObject): Entity? = runCatching {
        if (o.containsKey("missing")) return null
        val claims = o["claims"] as? JsonObject
        fun values(p: String): List<JsonObject> = (claims?.get(p) as? JsonArray).orEmpty().mapNotNull { c ->
            val co = c.jsonObject
            if (co["rank"]?.jsonPrimitive?.content == "deprecated") return@mapNotNull null
            ((co["mainsnak"] as? JsonObject)?.get("datavalue") as? JsonObject)?.get("value") as? JsonObject
        }
        val refs = itemProps.associateWith { p -> values(p).mapNotNull { it["id"]?.jsonPrimitive?.content }.distinct() }
            .filterValues { it.isNotEmpty() }
        fun yearOf(v: JsonObject) =
            Regex("""^[+]?(\d{4})""").find(v["time"]?.jsonPrimitive?.content ?: "")?.groupValues?.get(1)?.toIntOrNull()
        val year = values(DATE).mapNotNull(::yearOf).minOrNull()
        // Значения с уточнением «платформа»: дата выхода и издатель именно порта
        fun onPlatform(p: String): List<Pair<JsonObject, List<String>>> = (claims?.get(p) as? JsonArray).orEmpty().mapNotNull { c ->
            val co = c.jsonObject
            if (co["rank"]?.jsonPrimitive?.content == "deprecated") return@mapNotNull null
            val v = ((co["mainsnak"] as? JsonObject)?.get("datavalue") as? JsonObject)?.get("value") as? JsonObject ?: return@mapNotNull null
            val plats = ((co["qualifiers"] as? JsonObject)?.get(PLATFORM) as? JsonArray).orEmpty().mapNotNull { q ->
                (((q as? JsonObject)?.get("datavalue") as? JsonObject)?.get("value") as? JsonObject)?.get("id")?.jsonPrimitive?.content
            }
            v to plats
        }
        val yearOn = HashMap<String, Int>()
        onPlatform(DATE).forEach { (v, plats) -> yearOf(v)?.let { y -> plats.forEach { pl -> yearOn[pl] = minOf(yearOn[pl] ?: y, y) } } }
        val publishersOn = HashMap<String, MutableList<String>>()
        onPlatform(PUBLISHER).forEach { (v, plats) ->
            v["id"]?.jsonPrimitive?.content?.let { id -> plats.forEach { pl -> publishersOn.getOrPut(pl) { ArrayList() }.add(id) } }
        }
        val sitelinks = o["sitelinks"] as? JsonObject
        val wiki = listOf("en", "ru").mapNotNull { l ->
            ((sitelinks?.get("${l}wiki") as? JsonObject)?.get("title"))?.jsonPrimitive?.content?.let { l to it }
        }.toMap()
        Entity(id, langValues(o["labels"] as? JsonObject), langValues(o["descriptions"] as? JsonObject), wiki, refs, year, yearOn, publishersOn)
    }.getOrNull()

    /** Подписи элементов: id -> (язык -> название). */
    fun parseLabels(body: String): Map<String, Map<String, String>> = runCatching {
        val out = HashMap<String, Map<String, String>>()
        json.parseToJsonElement(body).jsonObject["entities"]!!.jsonObject.forEach { (id, v) ->
            val o = v as? JsonObject ?: return@forEach
            val labels = langValues(o["labels"] as? JsonObject)
            out[id] = labels
            // Элемент объединили с другим: запрошенный старый номер тоже получает подпись
            (o["redirects"] as? JsonObject)?.get("from")?.jsonPrimitive?.content?.let { out[it] = labels }
        }
        out
    }.getOrDefault(emptyMap())

    /** Факты для страницы игры. */
    data class Facts(
        val year: Int?,
        val genresEn: List<String>,
        val genresRu: List<String>,
        val developers: List<String>,
        val publishers: List<String>,
    )

    /** system — консоль игры: год и издатель берутся для неё, если в Викиданных они указаны отдельно. */
    fun facts(e: Entity, labels: Map<String, Map<String, String>>, system: GameSystem? = null): Facts {
        fun names(ids: List<String>, lang: String, max: Int) =
            ids.mapNotNull { id -> labels[id]?.let { it[lang] ?: it["en"] ?: it["ru"] } }.distinct().take(max)
        fun names(p: String, lang: String, max: Int) = names(e.refs[p].orEmpty(), lang, max)
        val ours = system?.let { Platforms.ids(it) }.orEmpty()
        val year = e.yearOn.filterKeys { it in ours }.values.minOrNull() ?: e.year
        val publishers = e.publishersOn.filterKeys { it in ours }.values.flatten().distinct()
            .ifEmpty { e.refs[PUBLISHER].orEmpty() }
        return Facts(
            year,
            names(GENRE, "en", 3), names(GENRE, "ru", 3),
            // Названия компаний обычно пишутся по-английски
            names(DEVELOPER, "en", 3), names(publishers, "en", 3),
        )
    }

    /**
     * Короткое описание из фактов на нужном языке. Нужно хотя бы два факта,
     * иначе текст получится пустым и бесполезным — тогда null.
     */
    fun describe(e: Entity, labels: Map<String, Map<String, String>>, lang: String, fallbackTitle: String): String? {
        fun name(id: String) = labels[id]?.let { it[lang] ?: it["en"] ?: it["ru"] }
        fun list(p: String, max: Int) = e.refs[p].orEmpty().mapNotNull(::name).distinct().take(max).joinToString(", ").ifEmpty { null }
        val ru = lang == "ru"
        val facts = listOfNotNull(
            list(GENRE, 3)?.let { (if (ru) "Жанр: " else "Genre: ") + it },
            list(DEVELOPER, 3)?.let { (if (ru) "Разработчик: " else "Developer: ") + it },
            list(PUBLISHER, 3)?.let { (if (ru) "Издатель: " else "Publisher: ") + it },
            e.year?.let { (if (ru) "Год выхода: " else "Released: ") + it },
            list(PLATFORM, 6)?.let { (if (ru) "Платформы: " else "Platforms: ") + it },
            list(SERIES, 1)?.let { (if (ru) "Серия: " else "Series: ") + it },
        )
        if (facts.size < 2) return null
        val title = e.labels[lang] ?: e.labels["en"] ?: fallbackTitle
        val what = e.descriptions[lang]?.takeIf { it.isNotBlank() }
            ?: if (ru) (e.year?.let { "компьютерная игра $it года" } ?: "компьютерная игра")
            else (e.year?.let { "$it video game" } ?: "video game")
        return "$title — $what.\n\n" + facts.joinToString("\n")
    }
}

/**
 * Игры Switch: у libretro их нет, поэтому обложка (иконка игры) и описание из eShop
 * берутся с tinfoil.media. Игра находится по Title ID из имени файла или по названию
 * в общем списке игр Switch.
 */
object SwitchDb {
    const val LIST_URL = "https://tinfoil.media/Title/ApiJson/"

    // --- GameTDB: сканы настоящих коробок Switch (3.8 МБ архив со списком игр, картинки по номеру) ---
    const val TDB_URL = "https://www.gametdb.com/switchtdb.zip"
    /** EN — европейская коробка (PEGI), US — американская. */
    val TDB_REGIONS = listOf("EN", "US", "DE", "FR", "ES", "IT", "JA")
    fun tdbCoverUrl(region: String, id: String) = "https://art.gametdb.com/switch/coverM/$region/$id.jpg"

    /**
     * Из архива switchtdb.zip: (номер GameTDB, английское название). Читается потоком — файл большой.
     * Только игры с настоящей коробкой: у цифровых (тип «eShop») GameTDB рисует заглушку —
     * красный футляр с надписью Nintendo eShop и баннером, это не обложка.
     */
    fun parseTdbZip(input: java.io.InputStream): List<Pair<String, String>> {
        java.util.zip.ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: return emptyList()
                if (e.name.endsWith(".xml")) return parseTdb(zip)
            }
        }
    }

    fun parseTdb(input: java.io.InputStream): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val p = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        p.setInput(input, "UTF-8")
        var inGame = false
        var id: String? = null
        var name: String? = null
        var en: String? = null
        var locale: String? = null
        var type: String? = null
        var ev = p.eventType
        while (ev != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) when (p.name) {
                "game" -> { inGame = true; id = null; en = null; locale = null; type = null; name = p.getAttributeValue(null, "name") }
                "type" -> if (inGame) type = p.nextText().trim()
                "id" -> if (inGame && id == null) id = p.nextText().trim()
                "locale" -> locale = p.getAttributeValue(null, "lang")
                "title" -> if (inGame && locale == "EN" && en == null) en = p.nextText().trim()
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG && p.name == "game") {
                val title = en?.ifBlank { null } ?: name?.replace(Regex("""\s*\([^)]*\)\s*$"""), "")?.trim()
                if (id != null && !title.isNullOrBlank() && !type.equals("eShop", ignoreCase = true)) out += id!! to title.replace('\t', ' ')
                inGame = false
            }
            ev = p.next()
        }
        return out
    }
    fun iconUrl(id: String) = "https://tinfoil.media/ti/$id/512/512/"
    fun pageUrl(id: String) = "https://tinfoil.media/Title/$id"

    // --- Европейская база Nintendo (поиск каталога nintendo.com/eu) — по Title ID ---
    // Точный поиск по номеру игры, есть и самые новые игры; картинки: коробка, квадрат, широкая

    private const val EU = "https://searching.nintendo-europe.com/en/select"
    private const val EU_FIELDS = "title,application_id_s,image_url,image_url_sq_s,image_url_h16x9_s,system_names_txt"

    fun euByIdUrl(id: String) = "$EU?q=*&fq=application_id_s:${id.lowercase()}&rows=5&wt=json&fl=$EU_FIELDS"
    fun euByNameUrl(name: String) =
        "$EU?q=${java.net.URLEncoder.encode(name, "UTF-8")}&fq=type:GAME&rows=8&wt=json&fl=$EU_FIELDS"

    data class EuGame(val id: String, val title: String, val box: String?, val square: String?, val wide: String?, val systems: List<String>)

    fun parseEu(json: String): List<EuGame> = runCatching {
        val docs = Json.parseToJsonElement(json).jsonObject["response"]?.jsonObject?.get("docs")?.jsonArray.orEmpty()
        docs.mapNotNull { d ->
            val o = d.jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNullSafe()?.takeIf { it.isNotBlank() }
            val id = str("application_id_s")?.uppercase() ?: return@mapNotNull null
            val systems = (o["system_names_txt"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe() }.orEmpty()
            EuGame(id, clean(str("title") ?: ""), str("image_url"), str("image_url_sq_s"), str("image_url_h16x9_s"), systems)
        }
    }.getOrDefault(emptyList())

    /** Из найденных — версия для Switch (не для Switch 2), с картинкой. */
    fun pickEu(games: List<EuGame>, id: String? = null): EuGame? {
        val pool = games.filter { id == null || it.id.equals(id, ignoreCase = true) }
        return pool.firstOrNull { "Nintendo Switch" in it.systems && (it.box != null || it.square != null) }
            ?: pool.firstOrNull { it.box != null || it.square != null }
    }

    // --- Запасной источник: Nlib API (api.nlib.cc, база TitleDB) — по Title ID ---
    // (tinfoil.media у части провайдеров не открывается)
    const val NLIB = "https://api.nlib.cc/nx"
    fun nlibInfoUrl(id: String) = "$NLIB/$id?fields=name,description,screens"
    fun nlibIconUrl(id: String) = "$NLIB/$id/icon/512"

    /** Ответ Nlib: название, описание из eShop и снимки экрана. */
    fun parseNlib(json: String): Page? = runCatching {
        val o = kotlinx.serialization.json.Json.parseToJsonElement(json).jsonObject
        val name = o["name"]?.let { (it as? JsonPrimitive)?.contentOrNullSafe() }?.let(::clean)?.ifEmpty { null }
        val desc = o["description"]?.let { (it as? JsonPrimitive)?.contentOrNullSafe() }?.let { shorten(it) }
        val shots = (o["screens"] as? JsonObject)?.get("screenshots")?.let { it as? JsonArray }
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNullSafe()?.replaceFirst("http://", "https://") }.orEmpty()
        if (name == null && desc == null) null else Page(name, desc, shots)
    }.getOrNull()

    private fun JsonPrimitive.contentOrNullSafe(): String? = if (this is kotlinx.serialization.json.JsonNull) null else content

    // 16 шестнадцатеричных цифр, начинается с 01; базовая игра кончается на 000, обновление — на 800
    private val idRe = Regex("""(?i)(?<![0-9a-f])(01[0-9a-f]{11})([08])00(?![0-9a-f])""")

    /** Title ID базовой игры из имени файла (обновление 0100...800 приводится к 0100...000). */
    fun titleId(fileName: String): String? = idRe.find(fileName)?.let { (it.groupValues[1] + "000").uppercase() }

    private val rowRe = Regex(""""id":"([0-9A-Fa-f]{16})","name":"((?:[^"\\]|\\.)*)"""")

    /** Список игр: (Title ID, название). */
    fun parseList(body: String): List<Pair<String, String>> =
        rowRe.findAll(body).mapNotNull { m ->
            val name = clean(Html.decode(Html.stripTags(unescapeJson(m.groupValues[2]))))
            if (name.isEmpty()) null else m.groupValues[1].uppercase() to name
        }.toList()

    /** Без значков ™ ® © и лишних пробелов. */
    fun clean(name: String): String = name.replace(Regex("[™®©]"), "").replace(Regex("\\s+"), " ").trim()

    private fun unescapeJson(s: String): String {
        if ('\\' !in s) return s
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'u' -> if (i + 5 < s.length) {
                        s.substring(i + 2, i + 6).toIntOrNull(16)?.let { sb.append(it.toChar()) }
                        i += 6; continue
                    }
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> {}
                    else -> sb.append(n)
                }
                i += 2
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    data class Page(val name: String?, val description: String?, val shots: List<String> = emptyList())

    private val ogTitle = Regex("""<meta property="og:title" content="([^"]*)"""")
    private val descBlock = Regex("""<h2>Description</h2>\s*<p>(.*?)</p>""", RegexOption.DOT_MATCHES_ALL)
    private val shotRe = Regex("""<img src="(https://tinfoil\.media/i/[^"]+)"""")
    private val ogDesc = Regex("""<meta property="og:description" content="([^"]*)"""", RegexOption.DOT_MATCHES_ALL)

    fun parsePage(html: String): Page? {
        if ("<title>Error</title>" in html) return null
        val name = ogTitle.find(html)?.groupValues?.get(1)?.let { clean(Html.decode(it)) }?.ifEmpty { null }
        val raw = (descBlock.find(html) ?: ogDesc.find(html))?.groupValues?.get(1)
        val desc = raw?.let { shorten(Html.decode(Html.stripTags(it.replace(Regex("(?i)<br\\s*/?>"), "\n")))) }
        val shots = shotRe.findAll(html).map { it.groupValues[1] }.distinct().toList()
        return Page(name, desc, shots)
    }

    /** Первые абзацы описания из магазина — не длиннее ~900 знаков, обрезка по концу предложения. */
    fun shorten(text: String, limit: Int = 900): String? = Texts.shorten(text, limit)
}

/** Обрезка длинных текстов для карточки описания. */
object Texts {
    /** Абзацы по порядку, пока не наберётся enough знаков; не длиннее limit, обрезка по концу предложения. */
    fun shorten(text: String, limit: Int = 900, enough: Int = 350): String? {
        val paras = text.replace("\r", "").split(Regex("\n\\s*\n|\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return null
        val out = StringBuilder()
        for (p in paras) {
            if (out.isNotEmpty() && out.length + p.length > limit) break
            if (out.isNotEmpty()) out.append("\n\n")
            out.append(p)
            if (out.length >= enough) break
        }
        if (out.length <= limit) return out.toString()
        val cut = out.substring(0, limit)
        val end = cut.lastIndexOfAny(charArrayOf('.', '!', '?'))
        return if (end > limit / 2) cut.substring(0, end + 1) else cut.trimEnd() + "…"
    }
}

/**
 * Проверка, что статья или элемент Викиданных — про игру именно для нашей консоли.
 * Иначе на «Final Fantasy VII» для PS1 легко найти ремейк для PS5 — другую игру.
 */
object Platforms {
    /**
     * PORT — по Викиданным игра для других консолей, но в статье наша упомянута
     * (порт или переиздание, которое Викиданные не перечислили).
     */
    enum class Verdict { CONFIRMED, WRONG, UNKNOWN, PORT }

    /** Номера нашей консоли в Викиданных. */
    fun ids(system: GameSystem): Set<String> = knownIds[system].orEmpty()

    /** Как называется консоль в Викиданных (английская подпись платформы). */
    private val labels: Map<GameSystem, Regex> = mapOf(
        GameSystem.PS1 to "PlayStation( \\(console\\))?",
        GameSystem.PS2 to "PlayStation 2",
        GameSystem.PS3 to "PlayStation 3",
        GameSystem.PSP to "PlayStation Portable",
        GameSystem.VITA to "PlayStation Vita",
        GameSystem.NES to "(Nintendo Entertainment System|Family Computer|Famicom)",
        GameSystem.SNES to "(Super Nintendo Entertainment System|Super Famicom)",
        GameSystem.N64 to "Nintendo 64",
        GameSystem.GC to "(Nintendo )?GameCube",
        GameSystem.WII to "Wii",
        GameSystem.WIIU to "Wii U",
        GameSystem.SWITCH to "Nintendo Switch",
        GameSystem.GB to "Game Boy",
        GameSystem.GBC to "Game Boy Color",
        GameSystem.GBA to "Game Boy Advance",
        GameSystem.NDS to "Nintendo DS",
        GameSystem.N3DS to "(New )?Nintendo 3DS",
        GameSystem.MD to "(Sega )?(Mega Drive|Genesis)",
        GameSystem.SMS to "(Sega )?Master System",
        GameSystem.GG to "(Sega )?Game Gear",
        GameSystem.SATURN to "(Sega )?Saturn",
        GameSystem.DC to "(Sega )?Dreamcast",
        GameSystem.PCE to "(PC Engine|TurboGrafx-16)",
        GameSystem.ARCADE to "(arcade.*|.*arcade (system board|game|video game).*)",
    ).mapValues { Regex("^(${it.value})$", RegexOption.IGNORE_CASE) }

    /** Упоминания консолей в тексте статьи (на любом языке пишут по-английски). */
    private val text: Map<GameSystem, Regex> = mapOf(
        GameSystem.PS1 to "PlayStation(?!\\s*(2|3|4|5|Portable|Vita|VR|Plus|Network|Store|Now|Classic))|\\bPS(1|X|one)\\b",
        GameSystem.PS2 to "PlayStation 2|\\bPS2\\b",
        GameSystem.PS3 to "PlayStation 3|\\bPS3\\b",
        GameSystem.PSP to "PlayStation Portable|\\bPSP\\b",
        GameSystem.VITA to "PlayStation Vita|PS Vita",
        GameSystem.NES to "Nintendo Entertainment System|\\bNES\\b|Famicom(?! Disk)|Dendy|Денди",
        GameSystem.SNES to "Super Nintendo|\\bSNES\\b|Super Famicom",
        GameSystem.N64 to "Nintendo 64|\\bN64\\b",
        GameSystem.GC to "GameCube",
        GameSystem.WII to "\\bWii\\b(?! U)",
        GameSystem.WIIU to "Wii U",
        GameSystem.SWITCH to "Nintendo Switch(?! 2)",
        GameSystem.GB to "Game Boy(?! (Advance|Color))",
        GameSystem.GBC to "Game Boy Color",
        GameSystem.GBA to "Game Boy Advance|\\bGBA\\b",
        GameSystem.NDS to "Nintendo DS|\\bNDS\\b",
        GameSystem.N3DS to "3DS",
        GameSystem.MD to "Mega Drive|Genesis|Мега Драйв",
        GameSystem.SMS to "Master System",
        GameSystem.GG to "Game Gear",
        GameSystem.SATURN to "Saturn",
        GameSystem.DC to "Dreamcast",
        GameSystem.PCE to "PC Engine|TurboGrafx",
    ).mapValues { Regex(it.value, RegexOption.IGNORE_CASE) }

    /** Можно ли вообще проверить платформу (для ПК, JoiPlay, ScummVM — нет). */
    fun checkable(system: GameSystem) = system in labels

    /** Вердикт по платформам из Викиданных: подписи платформ элемента на английском. */
    fun byLabels(system: GameSystem, platformLabels: List<String>): Verdict = byPlatforms(system, emptyList(), platformLabels)

    /** Номера консолей в Викиданных — подтверждают платформу, даже если подпись не загрузилась. */
    private val knownIds: Map<GameSystem, Set<String>> = mapOf(
        GameSystem.PS1 to setOf("Q10677"), GameSystem.PS2 to setOf("Q10680"), GameSystem.PS3 to setOf("Q10683"),
        GameSystem.PSP to setOf("Q170323"), GameSystem.VITA to setOf("Q188808"), GameSystem.NES to setOf("Q172742"),
        GameSystem.SNES to setOf("Q183259"), GameSystem.N64 to setOf("Q184839"), GameSystem.GC to setOf("Q182172"),
        GameSystem.WII to setOf("Q8079"), GameSystem.WIIU to setOf("Q56942"), GameSystem.SWITCH to setOf("Q19610114"),
        GameSystem.GB to setOf("Q186437"), GameSystem.GBC to setOf("Q203992"), GameSystem.GBA to setOf("Q188642"),
        GameSystem.NDS to setOf("Q170325"), GameSystem.N3DS to setOf("Q203597"), GameSystem.MD to setOf("Q10676"),
        GameSystem.SMS to setOf("Q209868"), GameSystem.GG to setOf("Q751719"), GameSystem.SATURN to setOf("Q200912"),
        GameSystem.DC to setOf("Q184198"),
    )

    /**
     * Вердикт по платформам элемента: ids — номера, names — английские подписи (null — не загрузилась).
     * «Не та консоль» — только если подписи есть у всех платформ и нашей среди них нет.
     */
    fun byPlatforms(system: GameSystem, ids: List<String>, names: List<String?>): Verdict {
        val re = labels[system] ?: return Verdict.UNKNOWN
        if (ids.any { it in knownIds[system].orEmpty() }) return Verdict.CONFIRMED
        if (names.isEmpty()) return Verdict.UNKNOWN
        if (names.any { it != null && re.matches(it.trim()) }) return Verdict.CONFIRMED
        return if (names.any { it == null }) Verdict.UNKNOWN else Verdict.WRONG
    }

    /**
     * Вердикт по тексту: упомянута наша консоль (где угодно во введении — порты часто в конце) — да;
     * только другие консоли — нет; ни одной — неизвестно.
     */
    fun byText(system: GameSystem, extract: String): Verdict {
        val ours = text[system] ?: return Verdict.UNKNOWN
        if (ours.containsMatchIn(extract.take(3000))) return Verdict.CONFIRMED
        val lead = extract.take(700)
        val others = text.filterKeys { it != system }.values.any { it.containsMatchIn(lead) } ||
            Regex("PlayStation [45]|Xbox|Windows|Switch 2", RegexOption.IGNORE_CASE).containsMatchIn(lead)
        return if (others) Verdict.WRONG else Verdict.UNKNOWN
    }
}
