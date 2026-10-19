package com.retrohome.launcher.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * RetroAchievements (retroachievements.org): достижения для старых игр. Их открывает эмулятор
 * (RetroArch, DuckStation, PPSSPP, AetherSX2, Dolphin…), в котором выполнен вход в RetroAchievements,
 * а лаунчер только показывает прогресс — через Web API сайта по логину и личному ключу пользователя.
 *
 * Здесь — адреса запросов, разбор ответов, отпечатки файлов и сопоставление игр. Без сети и Android.
 */
object Ra {
    private const val API = "https://retroachievements.org/API/"
    private val json = Json { ignoreUnknownKeys = true }

    /** Номера консолей на сайте (как в библиотеке rcheevos). */
    val consoleIds: Map<GameSystem, Int> = mapOf(
        GameSystem.MD to 1, GameSystem.N64 to 2, GameSystem.SNES to 3, GameSystem.GB to 4,
        GameSystem.GBA to 5, GameSystem.GBC to 6, GameSystem.NES to 7, GameSystem.PCE to 8,
        GameSystem.SMS to 11, GameSystem.PS1 to 12, GameSystem.GG to 15, GameSystem.GC to 16,
        GameSystem.NDS to 18, GameSystem.WII to 19, GameSystem.PS2 to 21, GameSystem.SATURN to 39,
        GameSystem.DC to 40, GameSystem.PSP to 41, GameSystem.N3DS to 62,
    )

    /**
     * Консоли, где игру можно опознать точно — по отпечатку (MD5) файла, как это делает эмулятор.
     * У дисковых консолей отпечаток считается сложно (по файлам внутри образа), там — по названию.
     */
    val hashable = setOf(
        GameSystem.NES, GameSystem.SNES, GameSystem.N64, GameSystem.GB, GameSystem.GBC, GameSystem.GBA,
        GameSystem.MD, GameSystem.SMS, GameSystem.GG, GameSystem.PCE,
    )

    /** Файлы больше этого хэшируются по первым 64 МБ (так же делает rcheevos). */
    const val MAX_HASH_BYTES = 64 * 1024 * 1024

    fun supported(system: GameSystem) = system in consoleIds

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    fun gameListUrl(consoleId: Int, key: String, hashes: Boolean) =
        "${API}API_GetGameList.php?i=$consoleId&f=1&h=${if (hashes) 1 else 0}&y=${enc(key)}"

    fun completionUrl(user: String, key: String, offset: Int = 0) =
        "${API}API_GetUserCompletionProgress.php?u=${enc(user)}&c=500&o=$offset&y=${enc(key)}"

    fun gameProgressUrl(user: String, key: String, gameId: Int) =
        "${API}API_GetGameInfoAndUserProgress.php?g=$gameId&u=${enc(user)}&y=${enc(key)}"

    fun profileUrl(user: String, key: String) =
        "${API}API_GetUserProfile.php?u=${enc(user)}&y=${enc(key)}"

    fun badgeUrl(badge: String) = "https://media.retroachievements.org/Badge/${enc(badge)}.png"

    // --- Разбор ответов (поля бывают и числами, и строками — старый формат API) ---

    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
    private fun JsonElement?.int(): Int = str()?.toDoubleOrNull()?.toInt() ?: 0
    private fun JsonObject.any(vararg keys: String): JsonElement? = keys.firstNotNullOfOrNull { this[it] }

    /** Игра из списка консоли. */
    @Serializable
    data class ListGame(val id: Int, val title: String, val total: Int, val points: Int = 0, val hashes: List<String> = emptyList())

    fun parseGameList(body: String): List<ListGame> = runCatching {
        (json.parseToJsonElement(body) as JsonArray).mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val id = o.any("ID", "id").int().takeIf { it > 0 } ?: return@mapNotNull null
            ListGame(
                id = id,
                title = o.any("Title", "title").str() ?: return@mapNotNull null,
                total = o.any("NumAchievements", "numAchievements").int(),
                points = o.any("Points", "points").int(),
                hashes = (o.any("Hashes", "hashes") as? JsonArray).orEmpty().mapNotNull { it.str()?.lowercase() },
            )
        }
    }.getOrDefault(emptyList())

    /** Прогресс по игре из общего списка пользователя. */
    @Serializable
    data class Completion(val gameId: Int, val total: Int, val earned: Int, val earnedHardcore: Int)

    /** Страница общего прогресса: записи и сколько всего игр у пользователя. */
    fun parseCompletion(body: String): Pair<List<Completion>, Int> = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val list = (o.any("Results", "results") as? JsonArray).orEmpty().mapNotNull { e ->
            val r = e as? JsonObject ?: return@mapNotNull null
            Completion(
                r.any("GameID", "gameId").int(),
                r.any("MaxPossible", "maxPossible").int(),
                r.any("NumAwarded", "numAwarded").int(),
                r.any("NumAwardedHardcore", "numAwardedHardcore").int(),
            ).takeIf { it.gameId > 0 }
        }
        list to o.any("Total", "total").int()
    }.getOrDefault(emptyList<Completion>() to 0)

    @Serializable
    data class Achievement(
        val id: Int,
        val title: String,
        val description: String,
        val points: Int,
        val badge: String,
        val order: Int,
        /** Когда открыто ("2024-03-01 12:00:00") или null. */
        val earned: String? = null,
        val earnedHardcore: String? = null,
    ) {
        val unlocked get() = earned != null || earnedHardcore != null
        val unlockedAt get() = earnedHardcore ?: earned
    }

    @Serializable
    data class GameProgress(
        val gameId: Int,
        val title: String,
        val total: Int,
        val earned: Int,
        val earnedHardcore: Int,
        val achievements: List<Achievement>,
        val fetched: Long = 0,
    ) {
        val points get() = achievements.sumOf { it.points }
        val earnedPoints get() = achievements.filter { it.unlocked }.sumOf { it.points }
    }

    fun parseGameProgress(body: String, now: Long = System.currentTimeMillis()): GameProgress? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val id = o.any("ID", "id").int().takeIf { it > 0 } ?: return null
        // Без достижений сайт отдаёт пустой массив вместо объекта
        val map = o.any("Achievements", "achievements") as? JsonObject
        val list = map?.values.orEmpty().mapNotNull { e ->
            val a = e as? JsonObject ?: return@mapNotNull null
            Achievement(
                id = a.any("ID", "id").int(),
                title = a.any("Title", "title").str() ?: return@mapNotNull null,
                description = a.any("Description", "description").str().orEmpty(),
                points = a.any("Points", "points").int(),
                badge = a.any("BadgeName", "badgeName").str().orEmpty(),
                order = a.any("DisplayOrder", "displayOrder").int(),
                earned = a.any("DateEarned", "dateEarned").str(),
                earnedHardcore = a.any("DateEarnedHardcore", "dateEarnedHardcore").str(),
            )
        }.sortedWith(compareBy({ it.order }, { it.id }))
        GameProgress(
            gameId = id,
            title = o.any("Title", "title").str().orEmpty(),
            total = o.any("NumAchievements", "numAchievements").int().takeIf { it > 0 } ?: list.size,
            earned = o.any("NumAwardedToUser", "numAwardedToUser").int().takeIf { it > 0 } ?: list.count { it.unlocked },
            earnedHardcore = o.any("NumAwardedToUserHardcore", "numAwardedToUserHardcore").int(),
            achievements = list,
            fetched = now,
        )
    }.getOrNull()

    /** Ответ профиля: логин и очки (для проверки входа), null — ответ не похож на профиль. */
    fun parseProfile(body: String): Pair<String, Int>? = runCatching {
        val o = json.parseToJsonElement(body).jsonObject
        val user = o.any("User", "user").str() ?: return null
        user to o.any("TotalPoints", "totalPoints").int()
    }.getOrNull()

    // --- Отпечатки файлов (правила rcheevos) ---

    /**
     * MD5 содержимого ROM-файла по правилам RetroAchievements: заголовки копировщиков отбрасываются,
     * ROM N64 приводятся к «родному» порядку байт. data — содержимое файла (из zip — первый файл внутри).
     */
    fun hash(system: GameSystem, data: ByteArray): String? {
        if (data.isEmpty()) return null
        var bytes = if (data.size > MAX_HASH_BYTES) data.copyOf(MAX_HASH_BYTES) else data
        var from = 0
        when (system) {
            GameSystem.NES ->
                if (bytes.size > 16 && (bytes.startsWith("NES\u001a") || bytes.startsWith("FDS\u001a"))) from = 16
            GameSystem.SNES -> if (bytes.size % 0x2000 == 512) from = 512
            GameSystem.PCE -> if (bytes.size and 512 != 0) from = 512
            GameSystem.N64 -> {
                bytes = when (bytes[0].toInt() and 0xFF) {
                    0x80, 0xE8, 0x22 -> bytes
                    0x37 -> swap(bytes, 2)   // .v64
                    0x40 -> swap(bytes, 4)   // .n64
                    else -> return null       // не ROM N64
                }
            }
            else -> {}
        }
        val md = MessageDigest.getInstance("MD5")
        md.update(bytes, from, bytes.size - from)
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun ByteArray.startsWith(s: String) = s.indices.all { this[it] == s[it].code.toByte() }

    private fun swap(b: ByteArray, width: Int): ByteArray {
        val out = b.copyOf()
        var i = 0
        while (i + width <= out.size) {
            for (k in 0 until width) out[i + k] = b[i + width - 1 - k]
            i += width
        }
        return out
    }

    // --- Сопоставление по названию ---

    /** «Legend of Zelda, The: A Link to the Past» → «The Legend of Zelda: A Link to the Past». */
    fun displayTitle(raTitle: String): String {
        val m = Regex("""^(.+?), (The|A|An)\b(.*)$""").find(raTitle) ?: return raTitle
        return "${m.groupValues[2]} ${m.groupValues[1]}${m.groupValues[3]}"
    }

    /** Хаки, homebrew, прототипы и наборы-подмножества — не та игра, что лежит у пользователя. */
    private fun isSpecial(t: String) = t.startsWith("~") || t.contains("[Subset", ignoreCase = true)

    /**
     * Игра на сайте для файла: сначала по отпечатку, затем по названию (точное совпадение
     * без учёта регистра, знаков, артиклей и римских цифр, либо те же слова).
     */
    fun match(titles: List<String>, list: List<ListGame>, hash: String?): ListGame? {
        if (hash != null) list.firstOrNull { hash.lowercase() in it.hashes }?.let { return it }
        val candidates = list.filter { !isSpecial(it.title) }
        for (t in titles.filter { it.isNotBlank() }) {
            val key = Thumbnails.norm(t)
            if (key.isEmpty()) continue
            candidates.firstOrNull { Thumbnails.norm(displayTitle(it.title)) == key }?.let { return it }
            // У сайта бывают альтернативные названия через « | »: «Biohazard | Resident Evil»
            candidates.firstOrNull { g -> g.title.split(" | ").any { Thumbnails.norm(displayTitle(it)) == key } }?.let { return it }
        }
        for (t in titles.filter { it.isNotBlank() }) {
            candidates.firstOrNull { Wiki.sameWords(displayTitle(it.title), t) }?.let { return it }
        }
        return null
    }

    /** Процент открытого (0–100). */
    fun percent(earned: Int, total: Int): Int = if (total <= 0) 0 else (earned * 100 / total).coerceIn(0, 100)
}

/** Сводка по достижениям игры для интерфейса. */
data class RaSummary(
    val gameId: Int,
    val total: Int,
    val earned: Int,
    val earnedHardcore: Int = 0,
) {
    val percent get() = Ra.percent(earned, total)
    val complete get() = total > 0 && earned >= total
}
