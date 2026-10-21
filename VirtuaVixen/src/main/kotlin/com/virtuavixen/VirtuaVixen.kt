package com.virtuavixen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.util.UUID

class VirtuaVixen : MainAPI() {
    override var mainUrl = "https://virtuavixen.com"
    override var name = "VirtuaVixen"
    override val hasMainPage = true
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override val mainPage = mainPageOf(
        "all-videos/page/%d/" to "Newest",
        "best-ai-porn-videos-week/page/%d/" to "Best Week",
        "best-ai-porn-videos-month/page/%d/" to "Best Month",
        "best-ai-porn-videos-of-all-time/page/%d/" to "Best All Time"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            "$mainUrl/${request.data.replace("page/%d/", "")}"
        } else {
            "$mainUrl/${request.data.format(page)}"
        }

        val document = app.get(pageUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        val items = document.select("article.vve-card, div.btp_post_card, div.card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.vve-card__link")
            ?: selectFirst("h3.vve-card__title a")
            ?: selectFirst("a.btp_post_card__title")
            ?: selectFirst("a.btp_post_card__img_hover")
            ?: selectFirst("a")
            ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = selectFirst("h3.vve-card__title")?.text()?.trim()
            ?: selectFirst("a.vve-card__link")?.attr("aria-label")?.trim()
            ?: selectFirst("a.btp_post_card__title")?.text()?.trim()
            ?: selectFirst("h6")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val img = selectFirst("img.vve-card__img")
            ?: selectFirst("img.btp_post_card__img")
            ?: selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: selectFirst("noscript img")?.attr("src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val quality = selectFirst("span.vve-card__res, .card-img-overlay .badge")?.text()?.trim().orEmpty()

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT
            )
            if (quality.isNotEmpty()) addQuality(quality)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=${query.trim().replace(" ", "+")}"
        val document = app.get(searchUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        return document.select("article.vve-card, div.btp_post_card, div.card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val fixedUrl = url.replace("https://vv2.dtsen.workers.dev", mainUrl)
        val document = app.get(fixedUrl, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.vve-h1, h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: "VirtuaVixen Video"

        val poster = fixUrlNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("div.vve-player img")?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("img.btp_post_card__img")?.attr("data-src")
                ?: document.selectFirst("img.btp_post_card__img")?.attr("src")
        )

        val plot = document.selectFirst("div.vve-desc__content, div.vve-desc, div.desc, div.entry-content, div.video-details")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val tags = document.select("a[href*='/tag/'], a[href*='/category/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }.distinct()

        val actors = document.select("a.vve-creator__name, a[href*='/model/']").mapNotNull {
            it.ownText().trim().takeIf { m -> m.isNotEmpty() }
                ?: it.selectFirst(".btp_post_card__author_text")?.text()?.trim()
                ?: it.text().trim().takeIf { m -> m.isNotEmpty() }
        }.distinct()

        val rawDurText = document.selectFirst("span.vve-up__dur, span.vve-card__dur, .duration, .video-duration")?.text()?.trim()

        val durationSeconds: Int? = run {
            val sec = document.selectFirst("meta[property='video:duration']")?.attr("content")?.trim()?.toIntOrNull()
                ?: document.selectFirst("meta[name='video:duration']")?.attr("content")?.trim()?.toIntOrNull()
            if (sec != null && sec > 0) return@run sec

            if (!rawDurText.isNullOrBlank()) {
                val parts = rawDurText.split(":").mapNotNull { it.trim().toIntOrNull() }
                val totalSec = when (parts.size) {
                    3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
                    2 -> parts[0] * 60 + parts[1]
                    1 -> parts[0]
                    else -> null
                }
                if (totalSec != null && totalSec > 0) return@run totalSec
            }

            val isoDur = document.selectFirst("meta[itemprop='duration']")?.attr("content")?.trim()
            if (!isoDur.isNullOrBlank()) {
                val h = Regex("""(\d+)H""", RegexOption.IGNORE_CASE).find(isoDur)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val m = Regex("""(\d+)M""", RegexOption.IGNORE_CASE).find(isoDur)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val s = Regex("""(\d+)S""", RegexOption.IGNORE_CASE).find(isoDur)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val totalSec = h * 3600 + m * 60 + s
                if (totalSec > 0) return@run totalSec
            }
            null
        }

        val durationMinutes = durationSeconds?.let { maxOf(1, (it + 30) / 60) }
        val formattedDuration = rawDurText ?: durationSeconds?.let {
            val m = it / 60
            val s = it % 60
            "%02d:%02d".format(m, s)
        }

        val fullPlot = buildString {
            if (!formattedDuration.isNullOrBlank()) {
                append("Durasi: $formattedDuration")
            }
            if (!plot.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append(plot)
            }
        }.takeIf { it.isNotBlank() }

        val recommendations = document.select("article.vve-card, div.btp_post_card, div.card").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, fixedUrl, TvType.NSFW, fixedUrl) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT
            )
            this.plot = fullPlot
            this.duration = durationMinutes
            this.tags = tags
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fixedData = data.replace("https://vv2.dtsen.workers.dev", mainUrl)
        val document = app.get(fixedData, headers = mapOf("User-Agent" to USER_AGENT)).document
        var foundAny = false

        // 1. Ekstraksi dari elemen Flowplayer / FV Player data-item
        val dataItemElements = document.select("[data-item]")
        for (el in dataItemElements) {
            val dataItem = el.attr("data-item")
            if (dataItem.isBlank() || !dataItem.contains("sources")) continue

            try {
                val json = JSONObject(dataItem)
                val sources = json.optJSONArray("sources") ?: continue
                for (i in 0 until sources.length()) {
                    val srcObj = sources.optJSONObject(i) ?: continue
                    var streamLoaderUrl = fixUrlNull(srcObj.optString("src")) ?: continue
                    streamLoaderUrl = streamLoaderUrl
                        .replace("https://virtuavixen.com", mainUrl)
                        .replace("https://vv2.dtsen.workers.dev", mainUrl)

                    // 1a. Otentikasi FV Player anti-rip performance check
                    try {
                        app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to "fv_player_performance",
                                "summary" to streamLoaderUrl
                            ),
                            headers = mapOf(
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "X-Requested-With" to "XMLHttpRequest",
                                "User-Agent" to USER_AGENT
                            ),
                            referer = fixedData
                        )
                    } catch (_: Exception) {}

                    // 1b. Unduh Master Playlist
                    val masterText = app.get(
                        streamLoaderUrl,
                        headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to USER_AGENT
                        )
                    ).text

                    // Temukan subplaylist (varian resolusi)
                    val playlistMatches = Regex("""#EXT-X-STREAM-INF:.*RESOLUTION=(\d+x\d+).*\r?\n(https://[^\r\n]+)""")
                        .findAll(masterText)
                        .toList()

                    val cachedKeys = mutableMapOf<String, ByteArray>()

                    if (playlistMatches.isNotEmpty()) {
                        for (match in playlistMatches) {
                            val resDim = match.groupValues[1]
                            val subUrl = match.groupValues[2]
                                .replace("https://virtuavixen.com", mainUrl)
                                .replace("https://vv2.dtsen.workers.dev", mainUrl)
                            val qualityNum = getQualityFromName("${resDim.substringAfter("x")}p")

                            try {
                                val subText = app.get(
                                    subUrl,
                                    headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "User-Agent" to USER_AGENT
                                    )
                                ).text

                                val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI=["']([^"']+)["']""").find(subText)
                                val keyUrl = keyMatch?.groupValues?.get(1)
                                    ?.replace("https://virtuavixen.com", mainUrl)
                                    ?.replace("https://vv2.dtsen.workers.dev", mainUrl)

                                val keyBytes = if (keyUrl != null) {
                                    cachedKeys.getOrPut(keyUrl) {
                                        val raw = app.get(
                                            keyUrl,
                                            headers = mapOf(
                                                "Referer" to "$mainUrl/",
                                                "User-Agent" to USER_AGENT
                                            )
                                        ).body.bytes()
                                        if (raw.size > 16) raw.copyOfRange(raw.size - 16, raw.size) else raw
                                    }
                                } else null

                                val id = UUID.randomUUID().toString()
                                val proxiedUrl = if (keyBytes != null) {
                                    VirtuaVixenProxy.register(id, subText, keyBytes)
                                } else null

                                val finalUrl = proxiedUrl ?: subUrl
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name ${resDim.substringAfter("x")}p",
                                        url = finalUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.quality = qualityNum
                                        this.referer = "$mainUrl/"
                                        this.headers = mapOf(
                                            "Referer" to "$mainUrl/",
                                            "User-Agent" to USER_AGENT
                                        )
                                    }
                                )
                                foundAny = true
                            } catch (_: Exception) {}
                        }
                    } else {
                        // Single playlist
                        try {
                            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI=["']([^"']+)["']""").find(masterText)
                            val keyUrl = keyMatch?.groupValues?.get(1)
                                ?.replace("https://virtuavixen.com", mainUrl)
                                ?.replace("https://vv2.dtsen.workers.dev", mainUrl)
                            val keyBytes = if (keyUrl != null) {
                                val raw = app.get(
                                    keyUrl,
                                    headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "User-Agent" to USER_AGENT
                                    )
                                ).body.bytes()
                                if (raw.size > 16) raw.copyOfRange(raw.size - 16, raw.size) else raw
                            } else null

                            val id = UUID.randomUUID().toString()
                            val proxiedUrl = if (keyBytes != null) {
                                VirtuaVixenProxy.register(id, masterText, keyBytes)
                            } else null

                            val finalUrl = proxiedUrl ?: streamLoaderUrl
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = name,
                                    url = finalUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "User-Agent" to USER_AGENT
                                    )
                                }
                            )
                            foundAny = true
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Fallback: Ekstraksi iframe
        if (!foundAny) {
            document.select("iframe").forEach { iframe ->
                val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
                val loaded = loadExtractor(src, referer = fixedData, subtitleCallback, callback)
                if (loaded) foundAny = true
            }
        }

        return foundAny
    }
}
