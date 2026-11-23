package com.kingbokep

import com.lagradost.api.Log
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Kingbokep : MainAPI() {

    override var mainUrl = "https://kingbokep.tv"
    override var name = "KingBokep"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    companion object {
        private const val TAG = "Kingbokep"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

        private const val CATEGORIES_CACHE_TTL_MS = 6 * 60 * 60 * 1000L
        private const val FALLBACK_RETRY_COOLDOWN_MS = 60 * 1000L
        private const val PAGE_SIZE_ESTIMATE = 24

        private val FRAME_SRC_REGEX = Regex(
            """(?:const|let|var)\s+frameSrc\s*=\s*["']([^"']+)["']"""
        )
        private val PASS_MD5_REGEX = Regex("""(/pass_md5/[^'"\s\\]+)""")

        private val FALLBACK_CATEGORIES: List<Pair<String, String>> = listOf(
            "/category/indonesia/" to "Bokep Indo",
            "/category/abg/" to "Bokep ABG",
            "/category/bispak/" to "Bispak",
            "/category/tante/" to "Bokep Tante",
            "/category/jilbab/" to "Bokep Jilbab",
            "/category/mahasiswi/" to "Mahasiswi",
            "/category/cam/" to "CAM",
            "/category/scandal/" to "Scandal",
            "/category/perawan/" to "Perawan",
            "/category/masturbasi/" to "Masturbasi",
            "/category/sma/" to "SMA",
            "/category/live-show/" to "Live Show",
            "/category/janda/" to "Janda",
            "/category/toge/" to "Toge",
            "/category/ngintip/" to "Ngintip",
            "/category/viral/" to "Indo Viral",
            "/category/tobrut/" to "Tobrut",
            "/category/tiktoker/" to "Tiktoker",
            "/category/pembantu/" to "Pembantu",
            "/category/stw/" to "STW",
            "/category/colmek/" to "Colmek",
            "/category/selebgram/" to "Selebgram",
            "/category/binor/" to "Binor",
            "/category/selingkuh/" to "Selingkuh",
            "/category/chindo/" to "Chindo",
            "/category/threesome/" to "Threesome",
            "/category/vcs/" to "VCS",
            "/category/artis/" to "Artis",
            "/category/cindo/" to "Cindo",
            "/category/lesbian/" to "Lesbian",
            "/category/pijat/" to "Tukang Pijat",
            "/category/bokep-ai/" to "Bokep AI",
            "/category/eksib/" to "Eksib",
            "/category/bumil/" to "Bumil",
            "/category/cucked/" to "Cucked",
            "/category/anal/" to "Anal",
            "/category/melayu/" to "Melayu",
        )
    }

    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
        )

    private val categoryLock = Any()

    @Volatile
    private var cachedMainPage: List<MainPageData>? = null

    @Volatile
    private var lastCategoryAttemptMs: Long = 0L

    @Volatile
    private var lastFetchSucceeded: Boolean = false

    override val mainPage: List<MainPageData>
        get() = getMainPageItems()

    private fun getMainPageItems(): List<MainPageData> {
        val now = System.currentTimeMillis()
        val cached = cachedMainPage
        if (cached != null) {
            val ttl = if (lastFetchSucceeded) CATEGORIES_CACHE_TTL_MS else FALLBACK_RETRY_COOLDOWN_MS
            if (now - lastCategoryAttemptMs < ttl) return cached
        }

        synchronized(categoryLock) {
            val innerNow = System.currentTimeMillis()
            val innerCached = cachedMainPage
            if (innerCached != null) {
                val ttl = if (lastFetchSucceeded) CATEGORIES_CACHE_TTL_MS else FALLBACK_RETRY_COOLDOWN_MS
                if (innerNow - lastCategoryAttemptMs < ttl) return innerCached
            }

            lastCategoryAttemptMs = innerNow
            val scraped = runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(8_000L) {
                        scrapeCategories()
                    }
                }
            }.getOrNull().orEmpty()

            val categories = if (scraped.isNotEmpty()) {
                lastFetchSucceeded = true
                scraped
            } else {
                lastFetchSucceeded = false
                FALLBACK_CATEGORIES
            }

            val items = buildList {
                add("" to "Newest")
                addAll(categories)
            }

            val result = mainPageOf(*items.toTypedArray())
            cachedMainPage = result
            return result
        }
    }

    private suspend fun scrapeCategories(): List<Pair<String, String>> {
        val document = app.get("$mainUrl/categories/", headers = defaultHeaders, timeout = 8).document
        val anchors = document.select("ul[aria-label*=Kategori] li a[href^=/category/], main ul li a[href^=/category/]")
        val seen = LinkedHashSet<String>()
        val list = mutableListOf<Pair<String, String>>()

        for (anchor in anchors) {
            val rawHref = anchor.attr("href").trim()
            if (rawHref.isBlank()) continue
            val path = normalizeCategoryPath(rawHref)
            if (!seen.add(path)) continue

            val label = anchor.selectFirst("span.font-semibold")?.text()?.trim()
                ?: anchor.selectFirst("span")?.text()?.trim()
                ?: continue
            if (label.isBlank()) continue

            list.add(path to label)
        }
        return list
    }

    private fun normalizeCategoryPath(rawHref: String): String {
        val withoutHost = rawHref.removePrefix(mainUrl)
        val withLeading = if (withoutHost.startsWith("/")) withoutHost else "/$withoutHost"
        return if (withLeading.endsWith("/")) withLeading else "$withLeading/"
    }

    // ---------------------------------------------------------------- Main Page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = buildListingUrl(request.data, page)
        val document = app.get(url, headers = defaultHeaders).document
        val items = parseVideoCards(document)
        val hasNext = hasNextPage(document, page, items.size)

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = true
            ),
            hasNext = hasNext
        )
    }

    private fun buildListingUrl(data: String, page: Int): String {
        val trimmed = data.removePrefix(mainUrl).trim('/')
        return if (trimmed.isEmpty()) {
            if (page <= 1) "$mainUrl/" else "$mainUrl/page/$page/"
        } else {
            if (page <= 1) "$mainUrl/$trimmed/" else "$mainUrl/$trimmed/page/$page/"
        }
    }

    // ---------------------------------------------------------------- Search

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)

        val encoded = URLEncoder.encode(clean, "UTF-8")
        val url = if (page <= 1) {
            "$mainUrl/search/?keyword=$encoded"
        } else {
            "$mainUrl/search/?keyword=$encoded&page=$page"
        }

        val document = app.get(url, headers = defaultHeaders).document
        val items = parseVideoCards(document)
        val hasNext = hasNextPage(document, page, items.size)
        return newSearchResponseList(items, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    // ---------------------------------------------------------------- Detail

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document
        val videoObject = findVideoObject(document)

        val title = document.selectFirst("h1.catalog-title, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: videoObject?.optString("name")?.trim()?.removePrefix("Video ")?.trim()?.takeIf { it.isNotBlank() }
            ?: "KingBokep Video"

        val poster = fixUrlNull(
            document.selectFirst("video#bokep-player, video[poster]")?.attr("poster")?.takeIf { it.isNotBlank() }
                ?: videoObject?.optString("thumbnailUrl")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }
        )

        val plot = videoObject?.optString("description")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val year = Regex("""(\d{4})""").find(videoObject?.optString("uploadDate") ?: "")
            ?.groupValues?.get(1)?.toIntOrNull()

        val durationMinutes = parseIsoDurationMinutes(videoObject?.optString("duration"))

        val categoryTags = document.select("main a[href^=/category/]").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotEmpty() }
        }.distinct()

        val tags = categoryTags.ifEmpty {
            val genreArray = videoObject?.optJSONArray("genre")
            if (genreArray != null) {
                (0 until genreArray.length()).mapNotNull { idx ->
                    genreArray.optString(idx).trim().takeIf { it.isNotEmpty() }
                }.distinct()
            } else {
                emptyList()
            }
        }

        val recommendations = parseVideoCards(document).filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
            this.plot = plot
            this.year = year
            this.tags = tags
            this.recommendations = recommendations
            if (durationMinutes != null) this.duration = durationMinutes
        }
    }

    // ---------------------------------------------------------------- Video Links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.contains(".m3u8", ignoreCase = true)) {
            return emitM3u8(data, callback)
        }

        val document = app.get(data, headers = defaultHeaders).document
        val videoObject = findVideoObject(document)

        // 1. Direct HLS / MP4 stream from <video> or JSON-LD contentUrl
        val directUrl = document.selectFirst("video#bokep-player, video[data-src], video[src]")?.let { el ->
            el.attr("data-src").ifBlank { el.attr("src") }.takeIf { it.isNotBlank() }
        }
            ?: document.selectFirst("video source[src]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: videoObject?.optString("contentUrl")?.takeIf { it.isNotBlank() }

        if (!directUrl.isNullOrBlank()) {
            val fixedStreamUrl = fixUrlNull(directUrl) ?: directUrl
            if (fixedStreamUrl.contains(".m3u8", ignoreCase = true)) {
                if (emitM3u8(fixedStreamUrl, callback)) return true
            } else {
                callback.invoke(
                    newExtractorLink(name, name, fixedStreamUrl, ExtractorLinkType.VIDEO) {
                        this.referer = "$mainUrl/"
                        this.headers = defaultHeaders
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        }

        // 2. Fallback for older videos using iframe / JS frameSrc embeds
        val embedUrls = LinkedHashSet<String>()
        document.select("iframe").forEach { iframe ->
            val raw = iframe.attr("src").ifBlank { iframe.attr("data-src") }.trim()
            fixUrlNull(raw)?.let { embedUrls.add(it) }
        }
        FRAME_SRC_REGEX.findAll(document.html()).forEach { match ->
            fixUrlNull(match.groupValues[1].trim())?.let { embedUrls.add(it) }
        }

        var foundLinks = false
        for (embedUrl in embedUrls) {
            var perEmbedFound = false
            val trackedCallback: (ExtractorLink) -> Unit = { link ->
                perEmbedFound = true
                foundLinks = true
                callback(link)
            }

            loadExtractor(embedUrl, referer = data, subtitleCallback, trackedCallback)

            if (!perEmbedFound) {
                if (resolveDoodRedirectFallback(embedUrl, data, subtitleCallback, trackedCallback)) {
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }

    private suspend fun emitM3u8(streamUrl: String, callback: (ExtractorLink) -> Unit): Boolean {
        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
        )
        val generated = runCatching {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = streamUrl,
                referer = "$mainUrl/",
                headers = streamHeaders
            )
        }.getOrDefault(emptyList())

        if (generated.isNotEmpty()) {
            generated.forEach(callback)
            return true
        }

        callback.invoke(
            newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = streamHeaders
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    private suspend fun resolveDoodRedirectFallback(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(embedUrl, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer))
            val finalUrl = response.okhttpResponse.request.url.toString()
            if (finalUrl != embedUrl) {
                var redirectedFound = false
                loadExtractor(finalUrl, referer = referer, subtitleCallback) { link ->
                    redirectedFound = true
                    callback(link)
                }
                if (redirectedFound) return true
            }

            val html = response.text
            val passMd5Path = PASS_MD5_REGEX.find(html)?.groupValues?.get(1) ?: return false
            val origin = Regex("""^(https?://[^/]+)""").find(finalUrl)?.groupValues?.get(1) ?: return false
            val passMd5Url = if (passMd5Path.startsWith("http")) passMd5Path else "$origin$passMd5Path"
            val token = Regex("""makePlay[^}]*?([a-zA-Z0-9]{10,})""").find(html)?.groupValues?.get(1)
                ?: passMd5Path.substringAfterLast("/")

            val prefix = app.get(
                passMd5Url,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to finalUrl)
            ).text.trim()
            if (!prefix.startsWith("http")) return false

            val randomChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            val suffix = buildString(10) { repeat(10) { append(randomChars.random()) } }
            val videoUrl = "$prefix$suffix?token=$token&expiry=${System.currentTimeMillis()}"

            callback.invoke(
                newExtractorLink(name, name, videoUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$origin/"
                    this.headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$origin/")
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "Embed fallback failed for $embedUrl: ${e.message}")
            false
        }
    }

    // ---------------------------------------------------------------- Helpers

    private fun parseVideoCards(document: Document): List<SearchResponse> =
        document.select("li.video-card").mapNotNull { it.toSearchResult() }.distinctBy { it.url }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrlNull(anchor.attr("href")) ?: return null

        val title = selectFirst(".video-card-title")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: anchor.attr("title").trim().takeIf { it.isNotBlank() }
            ?: return null

        val img = selectFirst("img")
        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-original")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
        }
    }

    private fun hasNextPage(document: Document, currentPage: Int, itemCount: Int): Boolean {
        val totalPages = document.selectFirst("goto-select")?.attr("data-totalpages")?.toIntOrNull()
        if (totalPages != null) return currentPage < totalPages

        if (document.selectFirst("a[href*=/page/${currentPage + 1}/], a[href*=page=${currentPage + 1}]") != null) {
            return true
        }
        return itemCount >= PAGE_SIZE_ESTIMATE
    }

    private fun findVideoObject(document: Document): JSONObject? {
        for (script in document.select("script[type=application/ld+json]")) {
            val json = runCatching { JSONObject(script.data()) }.getOrNull() ?: continue
            if (json.optString("@type") == "VideoObject") return json

            val graph = json.optJSONArray("@graph") ?: continue
            for (i in 0 until graph.length()) {
                val node = graph.optJSONObject(i) ?: continue
                if (node.optString("@type") == "VideoObject") return node
            }
        }
        return null
    }

    private fun parseIsoDurationMinutes(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val match = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?""").find(value)
            ?: return null
        val days = match.groupValues[1].toIntOrNull() ?: 0
        val hours = match.groupValues[2].toIntOrNull() ?: 0
        val minutes = match.groupValues[3].toIntOrNull() ?: 0
        val seconds = match.groupValues[4].toDoubleOrNull()?.toInt() ?: 0
        val totalSeconds = days * 86400 + hours * 3600 + minutes * 60 + seconds
        if (totalSeconds <= 0) return null
        return maxOf(1, (totalSeconds + 30) / 60)
    }
}
