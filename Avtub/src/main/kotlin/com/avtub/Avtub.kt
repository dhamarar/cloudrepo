package com.avtub

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Avtub : MainAPI() {
    override var mainUrl = "https://avtub.cx"
    override var name = "AVTub"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.NSFW
    )

    override val mainPage = mainPageOf(
        "category/bokep-indo/page/%d/?filter=latest" to "Terbaru",
        "category/bokep-jilbab/page/%d/?filter=latest" to "Jilbab"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            "$mainUrl/${request.data.replace("page/%d/", "")}"
        } else {
            "$mainUrl/${request.data.format(page)}"
        }

        val document = app.get(pageUrl).document
        val items = document.select("article.thumb-block, div.thumb-block").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null
        val title = selectFirst("header.entry-header span")?.text()?.trim()
            ?: selectFirst("header.entry-header")?.text()?.trim()
            ?: a.attr("title").trim()
            ?: return null
        if (title.isBlank()) return null

        val img = selectFirst("img")
        val poster = fixUrlNull(
            attr("data-main-thumb").takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )
        val quality = selectFirst(".hd-video")?.text()?.trim().orEmpty()

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
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl).document
        return document.select("article.thumb-block, div.thumb-block").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim()
            ?: document.selectFirst("meta[itemprop=name]")?.attr("content")?.trim()
            ?: "AVTub Video"

        val poster = fixUrlNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".post-thumbnail img, .video-player img, article img")?.let {
                    it.attr("src").takeIf { s -> s.isNotBlank() && !s.startsWith("data:") }
                        ?: it.attr("data-src").takeIf { s -> s.isNotBlank() }
                }
        )

        val plot = document.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()
            ?: document.selectFirst(".entry-content, .video-details")?.text()?.trim()

        val tags = document.select(".video-tags a, a[rel=tag]").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to USER_AGENT
            )
            this.plot = plot
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        val iframes = document.select("iframe").mapNotNull {
            val raw = it.attr("src").ifBlank { it.attr("data-src") }
            fixUrlNull(raw)
        }.distinct()

        iframes.forEach { iframeUrl ->
            var iframeFound = false
            val perIframeCallback: (ExtractorLink) -> Unit = { link ->
                iframeFound = true
                foundLinks = true
                callback(link)
            }

            // 1. Coba lewat sistem loadExtractor CloudStream
            loadExtractor(iframeUrl, referer = data, subtitleCallback, perIframeCallback)

            // 2. Fallback direct invoke jika iframe ini belum tertangani
            if (!iframeFound) {
                when {
                    iframeUrl.contains("morencius.com", ignoreCase = true) ->
                        MorenciusExtractor().getUrl(iframeUrl, referer = data, subtitleCallback, perIframeCallback)
                    iframeUrl.contains("vstream.id", ignoreCase = true) ->
                        VStreamExtractor().getUrl(iframeUrl, referer = data, subtitleCallback, perIframeCallback)
                    iframeUrl.contains("vidara.to", ignoreCase = true) || iframeUrl.contains("bnvcq8s.com", ignoreCase = true) ->
                        VidaraExtractor().getUrl(iframeUrl, referer = data, subtitleCallback, perIframeCallback)
                    iframeUrl.contains("ystream.id", ignoreCase = true) ->
                        YStreamExtractor().getUrl(iframeUrl, referer = data, subtitleCallback, perIframeCallback)
                }
            }
        }

        return foundLinks
    }
}
