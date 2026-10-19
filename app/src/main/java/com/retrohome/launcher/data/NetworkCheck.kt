package com.retrohome.launcher.data

/**
 * Проверка соединения со всеми сайтами, откуда лаунчер берёт обложки, описания и достижения.
 * Показывает точную причину сбоя — чтобы не гадать, почему «ничего не грузится».
 */
object NetworkCheck {
    /** id — для подписи на нужном языке (covers, wikiEn, wikiRu, wikidata, switch, ra). */
    data class Target(val id: String, val url: String)

    val targets = listOf(
        Target("covers", "https://thumbnails.libretro.com/"),
        Target("wikiEn", "https://en.wikipedia.org/w/api.php?action=query&meta=siteinfo&format=json"),
        Target("wikiRu", "https://ru.wikipedia.org/w/api.php?action=query&meta=siteinfo&format=json"),
        Target("wikidata", "https://www.wikidata.org/w/api.php?action=query&meta=siteinfo&format=json"),
        Target("switch", "https://tinfoil.media/"),
        Target("ra", "https://retroachievements.org/"),
    )

    /** Вид проблемы — по нему подбирается объяснение и совет. */
    enum class Kind { OK, NO_DNS, TIMEOUT, REFUSED, TLS, LIMIT, REJECTED, SERVER, NO_PERMISSION, OTHER }

    data class Result(val target: Target, val code: Int, val ms: Long, val error: String?) {
        val host: String get() = runCatching { java.net.URL(target.url).host }.getOrDefault(target.url)
        val kind: Kind get() = classify(code, error)
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

    /** Проверить все сайты по очереди. onEach — результат каждого, по мере готовности. */
    fun run(transport: Transport, userAgent: String, onEach: (Result) -> Unit = {}): List<Result> =
        targets.map { t ->
            val start = System.nanoTime()
            val r = transport.open(t.url, null, userAgent)
            Result(t, r.code, (System.nanoTime() - start) / 1_000_000, r.error).also(onEach)
        }
}
