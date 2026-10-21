package com.vikuy

import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SearchResponseList
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSearchResponseList
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Vikuy : MainAPI() {

    companion object {
        private const val DEFAULT_DOMAIN = "https://vikuy.click"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override var mainUrl = DEFAULT_DOMAIN
    override var name = "Vikuy"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to "$mainUrl/",
        )

    override val mainPage = mainPageOf(
        "explore.php?tab=latest&safe=0" to "Terbaru",
        "explore.php?tab=latest" to "Terbaru (Safe Search)",
    )

    // ---------------------------------------------------------------- Main Page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page <= 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}${separator}page=$page"
        }

        val document = app.get(url, headers = defaultHeaders).document
        val items = parseCards(document)
        val hasNext = document.selectFirst("a[aria-label='Next page'], a.page-link[href*='page=']") != null

        return newHomePageResponse(request.name, items, hasNext = hasNext && items.isNotEmpty())
    }

    // ---------------------------------------------------------------- Search

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)

        val results = mutableListOf<SearchResponse>()

        // 1. Coba pencarian profil uploader / creator (contoh: 'thegoat', 'lupachin536')
        val userSlug = clean.lowercase().replace(" ", "")
        runCatching {
            val userUrl = "$mainUrl/user.php?u=$userSlug${if (page > 1) "&page=$page" else ""}"
            val userDoc = app.get(userUrl, headers = defaultHeaders).document
            results.addAll(parseCards(userDoc))
        }

        // 2. Coba endpoint explore.php?q=... (jika backend server memperbaiki query SQL)
        if (results.isEmpty()) {
            runCatching {
                val encoded = URLEncoder.encode(clean, "UTF-8")
                val searchUrl = "$mainUrl/explore.php?tab=latest&q=$encoded${if (page > 1) "&page=$page" else ""}"
                val res = app.get(searchUrl, headers = defaultHeaders)
                if (res.code in 200..399) {
                    results.addAll(parseCards(res.document))
                }
            }
        }

        // 3. Fallback: pencarian lokal dari explore terbaru (mencocokkan judul video)
        if (results.isEmpty() && page <= 2) {
            runCatching {
                val exploreUrl = "$mainUrl/explore.php?tab=latest&safe=0&page=$page"
                val exploreDoc = app.get(exploreUrl, headers = defaultHeaders).document
                val filtered = parseCards(exploreDoc).filter {
                    it.name.contains(clean, ignoreCase = true)
                }
                results.addAll(filtered)
            }
        }

        val distinct = results.distinctBy { it.url }
        return newSearchResponseList(distinct, distinct.isNotEmpty())
    }

    // ---------------------------------------------------------------- Load

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = defaultHeaders).document

        val title = document.selectFirst(".video-title-big")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
            ?: document.selectFirst("title")?.text()?.replace(Regex("""\s*-\s*Vikuy.*"""), "")?.trim()
            ?: "Video"

        val poster = document.selectFirst("video#mainVid")?.attr("poster")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val uploader = document.selectFirst(".uploader-name")?.text()?.trim()
        val handle = document.selectFirst(".uploader-handle")?.text()?.trim()
        val desc = document.selectFirst(".watch-description")?.text()?.trim()

        val plot = buildString {
            if (!desc.isNullOrBlank()) append(desc)
            if (!uploader.isNullOrBlank()) {
                if (isNotEmpty()) append("\n\n")
                append("Uploader: $uploader")
                if (!handle.isNullOrBlank()) append(" ($handle)")
            }
        }.takeIf { it.isNotBlank() }

        val recommendations = document.select("a.related-card").mapNotNull { card ->
            val href = card.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val relTitle = card.selectFirst(".related-title")?.text()?.trim() ?: return@mapNotNull null
            val relPoster = card.selectFirst(".related-thumb img")?.attr("src")?.takeIf { it.isNotBlank() }
            newMovieSearchResponse(relTitle, fixUrl(href), TvType.NSFW) {
                this.posterUrl = relPoster?.let { fixUrlNull(it) }
                this.posterHeaders = defaultHeaders
            }
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster?.let { fixUrlNull(it) }
            this.posterHeaders = defaultHeaders
            this.plot = plot
            this.recommendations = recommendations
        }
    }

    // ---------------------------------------------------------------- Load Links

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // 1. Ambil URL video mentah
        val rawVideoUrl: String? = if (data.contains(".mp4") || data.contains(".m3u8")) {
            data
        } else {
            val document = app.get(data, headers = defaultHeaders).document
            val fromVideo = document.selectFirst(
                "video#mainVid source[src], video source[src], video#mainVid[src], video[src]"
            )?.attr("src")?.takeIf { it.isNotBlank() }

            if (fromVideo != null) {
                fromVideo
            } else {
                // Cadangan: coba halaman unduhan jika <source> tidak ada di player
                val videoId = data.substringAfter("v=", "").substringBefore("&")
                if (videoId.isNotBlank()) {
                    val dlDoc = app.get("$mainUrl/download.php?v=$videoId", headers = defaultHeaders).document
                    dlDoc.selectFirst("a[href*='serve_video.php']")?.attr("href")?.takeIf { it.isNotBlank() }
                } else null
            }
        }

        if (rawVideoUrl.isNullOrBlank()) return false

        val fullRawUrl = fixUrl(rawVideoUrl)

        // 2. Jika melalui serve_video.php, ikuti 302 redirect untuk mendapatkan URL CDN .mp4 langsung
        val finalStreamUrl: String = if (fullRawUrl.contains("serve_video.php")) {
            runCatching {
                val headRes = app.get(
                    fullRawUrl,
                    headers = mapOf("Referer" to data),
                    allowRedirects = false
                )
                val loc = headRes.headers["Location"] ?: headRes.headers["location"]
                if (!loc.isNullOrBlank()) fixUrl(loc) else fullRawUrl
            }.getOrDefault(fullRawUrl)
        } else {
            fullRawUrl
        }

        val isM3u8 = finalStreamUrl.contains(".m3u8")
        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = finalStreamUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            ) {
                this.referer = "$mainUrl/"
                this.headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/"
                )
                this.quality = Qualities.P720.value
            }
        )

        return true
    }

    // ---------------------------------------------------------------- Helpers

    private fun Element.toSearchResult(): SearchResponse? {
        val href = when {
            tagName().equals("a", ignoreCase = true) -> attr("href")
            else -> selectFirst("a[href*=/watch?v=]")?.attr("href")
                ?: selectFirst("a[href*=/watch]")?.attr("href")
                ?: selectFirst("a")?.attr("href")
        }?.takeIf { it.isNotBlank() } ?: return null

        val title = selectFirst(".video-title")?.text()?.trim()
            ?: selectFirst("h2, h3")?.text()?.trim()
            ?: return null

        val poster = selectFirst(".video-thumb img, img")?.let { img ->
            val src = img.attr("src").ifBlank { img.attr("data-src") }
            src.takeIf { it.isNotBlank() && !it.endsWith(".mp4") }
        }?.let { fixUrlNull(it) }

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
        }
    }

    private fun parseCards(document: Document): List<SearchResponse> =
        document.select(".video-card").mapNotNull { it.toSearchResult() }
}
