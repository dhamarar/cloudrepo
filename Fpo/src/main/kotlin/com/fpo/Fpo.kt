package com.fpo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class Fpo : MainAPI() {
    override var mainUrl = "https://www.fpo.xxx"
    override var name = "FPO"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "" to "Featured",
        "new-1/" to "Newest",
        "top-2/" to "Top Rated",
        "popular-2/" to "Popular"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data
        val url = when {
            section.isEmpty() -> {
                if (page <= 1) "$mainUrl/" else "$mainUrl/?from=$page"
            }
            page <= 1 -> "$mainUrl/$section"
            else -> "$mainUrl/$section$page/"
        }

        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = document.select("div.item").mapNotNull { it.toSearchResult() }
        val hasNext = items.isNotEmpty() && (document.selectFirst("div.pagination li.next a, div.pagination a:contains(Next)") != null || items.size >= 10)

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        if (!href.contains("/video/")) return null

        val title = selectFirst("strong.title")?.text()?.trim()
            ?.ifEmpty { null }
            ?: aTag.attr("title").trim().ifEmpty { null }
            ?: selectFirst("img")?.attr("alt")?.trim()?.ifEmpty { null }
            ?: return null

        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )

        val isHd = selectFirst(".is-hd") != null

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to userAgent
            )
            if (isHd) addQuality("HD")
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val cleanQuery = query.trim().replace(" ", "+")
        val url = if (page <= 1) "$mainUrl/search/?q=$cleanQuery" else "$mainUrl/search/?q=$cleanQuery&from_videos=$page"
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document
        val items = document.select("div.item").mapNotNull { it.toSearchResult() }
        val hasNext = items.isNotEmpty() && (document.selectFirst("div.pagination li.next a, div.pagination a:contains(Next)") != null || items.size >= 20)
        return newSearchResponseList(items, hasNext = hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = mapOf("User-Agent" to userAgent)).document

        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Video"

        val poster = fixUrlNull(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst(".player-holder img, div.player img")?.attr("src")
        )

        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        val html = document.html()
        val flashvarsMatch = Regex("""var\s+flashvars\s*=\s*\{([\s\S]*?)\};""").find(html)
        var categories = listOf<String>()
        var actors = listOf<String>()

        if (flashvarsMatch != null) {
            val block = flashvarsMatch.groupValues[1]
            val kv = Regex("""(\w+)\s*:\s*'([^']*)'""").findAll(block).associate {
                it.groupValues[1] to it.groupValues[2]
            }
            kv["video_categories"]?.takeIf { it.isNotBlank() }?.let { catStr ->
                categories = catStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
            kv["video_models"]?.takeIf { it.isNotBlank() }?.let { actStr ->
                actors = actStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to userAgent
            )
            this.plot = plot
            if (categories.isNotEmpty()) {
                this.tags = categories
            }
            if (actors.isNotEmpty()) {
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = mapOf("User-Agent" to userAgent)).document
        val html = document.html()

        extractStreamsFromFlashvars(html, callback)

        document.select("iframe").forEach { iframe ->
            val src = fixUrl(iframe.attr("src"))
            if (src.contains("fpo.xxx/embed/")) {
                val embedHtml = app.get(src, referer = data, headers = mapOf("User-Agent" to userAgent)).text
                extractStreamsFromFlashvars(embedHtml, callback)
            } else if (src.isNotBlank() && !src.contains("adtng.com")) {
                loadExtractor(src, referer = data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun extractStreamsFromFlashvars(
        html: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val flashvarsMatch = Regex("""var\s+flashvars\s*=\s*\{([\s\S]*?)\};""").find(html) ?: return
        val block = flashvarsMatch.groupValues[1]
        val kv = Regex("""(\w+)\s*:\s*'([^']*)'""").findAll(block).associate {
            it.groupValues[1] to it.groupValues[2]
        }

        val streams = mutableListOf<Pair<String, String>>()

        kv["video_url"]?.takeIf { it.startsWith("http") }?.let { u ->
            val label = kv["video_url_text"]?.takeIf { it.isNotBlank() } ?: "LQ"
            streams.add(label to u)
        }

        kv["video_alt_url"]?.takeIf { it.startsWith("http") }?.let { u ->
            val label = kv["video_alt_url_text"]?.takeIf { it.isNotBlank() } ?: "HQ"
            streams.add(label to u)
        }

        kv.forEach { (k, v) ->
            if (k.startsWith("video_alt_url") &&
                k !in listOf("video_alt_url", "video_alt_url_text", "video_alt_url_hd") &&
                !k.endsWith("_text") && !k.endsWith("_hd") &&
                v.startsWith("http")
            ) {
                val label = kv["${k}_text"]?.takeIf { it.isNotBlank() } ?: k
                streams.add(label to v)
            }
        }

        streams.distinctBy { it.second }.forEach { (qualityLabel, streamUrl) ->
            val qualityVal = getQualityInt(qualityLabel, streamUrl)
            callback.invoke(
                newExtractorLink(
                    name = "$name $qualityLabel",
                    source = name,
                    url = streamUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "User-Agent" to userAgent
                    )
                    this.quality = qualityVal
                }
            )
        }
    }

    private fun getQualityInt(label: String, url: String): Int {
        val combined = "$label $url".lowercase()
        return when {
            combined.contains("1080") -> Qualities.P1080.value
            combined.contains("720") || combined.contains("hq") -> Qualities.P720.value
            combined.contains("480") -> Qualities.P480.value
            combined.contains("360") || combined.contains("lq") -> Qualities.P360.value
            combined.contains("240") -> Qualities.P240.value
            else -> Qualities.Unknown.value
        }
    }
}
