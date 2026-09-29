package com.virtuavixen

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.nodes.Element

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
        val items = document.select("div.btp_post_card, div.card").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.btp_post_card__title")
            ?: selectFirst("a.btp_post_card__img_hover")
            ?: selectFirst("a")
            ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null
        val title = selectFirst("a.btp_post_card__title")?.text()?.trim()
            ?: selectFirst("h6")?.text()?.trim()
            ?: selectFirst("img")?.attr("alt")?.trim()
            ?: return null
        if (title.isBlank()) return null

        val img = selectFirst("img.btp_post_card__img") ?: selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val quality = selectFirst(".card-img-overlay .badge")?.text()?.trim().orEmpty()

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
        return document.select("div.btp_post_card, div.card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to USER_AGENT)).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: "VirtuaVixen Video"

        val poster = fixUrlNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("img.btp_post_card__img")?.attr("data-src")
                ?: document.selectFirst("img.btp_post_card__img")?.attr("src")
        )

        val plot = document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("div.desc, div.entry-content, div.video-details")?.text()?.trim()

        val tags = document.select("a[href*='/tag/']").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }.distinct()

        val actors = document.select("a[href*='/model/']").mapNotNull {
            it.selectFirst(".btp_post_card__author_text")?.text()?.trim()
                ?: it.text().trim().takeIf { m -> m.isNotEmpty() }
        }.distinct()

        val recommendations = document.select("div.btp_post_card, div.card").mapNotNull {
            it.toSearchResult()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT
            )
            this.plot = plot
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
        val document = app.get(data, headers = mapOf("User-Agent" to USER_AGENT)).document
        var foundAny = false

        // 1. Ekstraksi dari elemen Flowplayer / FV Player data-item
        document.select("[data-item]").forEach { el ->
            val dataItem = el.attr("data-item")
            if (dataItem.isNotBlank() && dataItem.contains("sources")) {
                try {
                    val json = JSONObject(dataItem)
                    val sources = json.optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val srcObj = sources.optJSONObject(i) ?: continue
                            val streamUrl = fixUrlNull(srcObj.optString("src")) ?: continue
                            val type = srcObj.optString("type")

                            if (streamUrl.contains(".m3u8") || streamUrl.contains("stream-loader") || type.contains("mpegurl")) {
                                var generated = false
                                try {
                                    M3u8Helper.generateM3u8(
                                        source = name,
                                        streamUrl = streamUrl,
                                        referer = "$mainUrl/",
                                        headers = mapOf(
                                            "Referer" to "$mainUrl/",
                                            "User-Agent" to USER_AGENT
                                        )
                                    ).forEach {
                                        generated = true
                                        foundAny = true
                                        callback(it)
                                    }
                                } catch (_: Exception) {}

                                if (!generated) {
                                    foundAny = true
                                    callback(
                                        newExtractorLink(
                                            source = name,
                                            name = name,
                                            url = streamUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = "$mainUrl/"
                                            this.headers = mapOf(
                                                "Referer" to "$mainUrl/",
                                                "User-Agent" to USER_AGENT
                                            )
                                        }
                                    )
                                }
                            } else {
                                foundAny = true
                                callback(
                                    newExtractorLink(
                                        source = name,
                                        name = name,
                                        url = streamUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.referer = "$mainUrl/"
                                        this.headers = mapOf(
                                            "Referer" to "$mainUrl/",
                                            "User-Agent" to USER_AGENT
                                        )
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // 2. Fallback: Ekstraksi dari JSON-LD Schema (VideoObject)
        if (!foundAny) {
            document.select("script[type=application/ld+json]").forEach { script ->
                val content = script.data()
                if (content.contains("contentUrl")) {
                    val contentUrl = Regex(""""contentUrl"\s*:\s*"([^"]+)"""").find(content)?.groupValues?.get(1)
                    if (!contentUrl.isNullOrBlank()) {
                        val cleanUrl = fixUrl(contentUrl.replace("\\/", "/"))
                        foundAny = true
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = cleanUrl,
                                type = if (cleanUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.headers = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "User-Agent" to USER_AGENT
                                )
                            }
                        )
                    }
                }
            }
        }

        // 3. Fallback: Ekstraksi iframe (jika disematkan host eksternal)
        document.select("iframe").forEach { iframe ->
            val src = fixUrlNull(iframe.attr("src")) ?: return@forEach
            val loaded = loadExtractor(src, referer = data, subtitleCallback, callback)
            if (loaded) foundAny = true
        }

        return foundAny
    }
}
