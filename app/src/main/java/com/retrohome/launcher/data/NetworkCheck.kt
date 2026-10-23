package com.retrohome.launcher.data

/**
 * Проверка соединения со всеми сайтами, откуда лаунчер берёт обложки, описания и достижения.
 * Показывает точную причину сбоя — чтобы не гадать, почему «ничего не грузится».
 */
object NetworkCheck {
    /**
     * id — для подписи на нужном языке (covers, wikiEn, wikiRu, wikidata, switch, ra).
     * unauthorizedIsOk — ответ «нужен ключ» (401) тоже значит, что сайт доступен.
     */
    data class Target(val id: String, val url: String, val unauthorizedIsOk: Boolean = false, val withAccount: Boolean = false)

    val targets = listOf(
        Target("covers", "https://thumbnails.libretro.com/"),
        // Запасной источник обложек, если сайт libretro недоступен
        Target("coversMirror", "https://api.github.com/repos/libretro-thumbnails/Sony_-_PlayStation"),
        Target("wikiEn", "https://en.wikipedia.org/w/api.php?action=query&meta=siteinfo&format=json"),
        Target("wikiRu", "https://ru.wikipedia.org/w/api.php?action=query&meta=siteinfo&format=json"),
        Target("wikidata", "https://www.wikidata.org/w/api.php?action=query&meta=siteinfo&format=json"),
        // Обложки Switch — первым делом из базы Nintendo Europe
        Target("switchEu", "https://searching.nintendo-europe.com/en/select?q=*&fq=application_id_s:0100f2c0115b6000&rows=1&wt=json&fl=title"),
        Target("switchTdb", "https://art.gametdb.com/switch/coverM/US/AX7QA.jpg"),
        Target("switch", "https://tinfoil.media/"),
        // Запасной источник данных Switch, если tinfoil недоступен
        Target("switchMirror", "https://api.nlib.cc/nx/0100F2C0115B6000?fields=name"),
        // Не главная страница (она закрыта защитой от роботов), а тот же API, что использует лаунчер.
        // Без ключа он отвечает «нужен ключ» — значит, сайт доступен
        // Обновления лаунчера: страница релизов на GitHub (оттуда же скачивается APK)
        Target("updates", "https://github.com/fenomen64/retro-home/releases"),
        Target("ra", "https://retroachievements.org/API/API_GetConsoleIDs.php", unauthorizedIsOk = true),
    )

    /** Вид проблемы — по нему подбирается объяснение и совет. */
    enum class Kind { OK, NO_DNS, TIMEOUT, REFUSED, TLS, LIMIT, REJECTED, SERVER, NO_PERMISSION, BAD_ACCOUNT, OTHER }

    data class Result(val target: Target, val code: Int, val ms: Long, val error: String?) {
        val host: String get() = runCatching { java.net.URL(target.url).host }.getOrDefault(target.url)
        /** Текст ошибки без ключа доступа (на случай, если в нём окажется адрес запроса). */
        val safeError: String? get() = error?.replace(Regex("""([?&]y=)[^&\s"]+"""), "$1***")
        val kind: Kind get() = when {
            code == 401 && target.unauthorizedIsOk -> Kind.OK
            // Проверяли с аккаунтом: сайт ответил, но логин или ключ не подошли
            (code == 401 || code == 403) && target.withAccount -> Kind.BAD_ACCOUNT
            else -> classify(code, error)
        }
    }

    fun classify(code: Int, error: String?): Kind {
        if (code in 200..399) return Kind.OK
        if (code == 429) return Kind.LIMIT
        if (code == 403 || code == 401) return Kind.REJECTED
        if (code >= 500) return Kind.SERVER
        if (code > 0) return Kind.OTHER
        val e = error.orEmpty()
        return when {
            "UnknownHost" in e -> Kind.NO_DNS
            "SocketTimeout" in e || "timeout" in e.lowercase() -> Kind.TIMEOUT
            "SSL" in e || "Certificate" in e || "CertPath" in e || "Handshake" in e -> Kind.TLS
            "Security" in e || "EPERM" in e || "Permission" in e -> Kind.NO_PERMISSION
            "ConnectException" in e || "ECONNREFUSED" in e || "NoRouteToHost" in e || "ENETUNREACH" in e -> Kind.REFUSED
            else -> Kind.OTHER
        }
    }

    /**
     * Проверить все сайты по очереди. onEach — результат каждого, по мере готовности.
     * raAccount — логин и ключ RetroAchievements: если указаны, проверяется и вход.
     */
    fun run(transport: Transport, userAgent: String, raAccount: Pair<String, String>? = null, onEach: (Result) -> Unit = {}): List<Result> =
        targets.map { base ->
            val t = if (base.id == "ra" && raAccount != null)
                Target("ra", Ra.profileUrl(raAccount.first, raAccount.second), withAccount = true) else base
            val start = System.nanoTime()
            val r = transport.open(t.url, null, userAgent)
            Result(t, r.code, (System.nanoTime() - start) / 1_000_000, r.error).also(onEach)
        }
}
