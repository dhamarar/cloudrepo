package com.pemersatufun

import com.lagradost.cloudstream3.ErrorLoadingException
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
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Pemersatufun : MainAPI() {

    companion object {
        private const val DEFAULT_DOMAIN = "https://pemersatufun.com"

        /**
         * Situs ini berjalan di beberapa host (terlihat di `window.__clusterHosts`).
         * Dipakai hanya sebagai cadangan kalau host aktif sedang tidak bisa diakses.
         */
        private val CANDIDATE_DOMAINS = listOf(
            "https://pemersatufun.com",
            "https://bokeppemersatu.net",
            "https://imperial.pemersatufun.com",
            "https://legacy.pemersatu.store",
            "https://pemersatu.store",
        )

        /** Batas lompatan halaman saat menelusuri paginasi berbasis cursor. */
        private const val MAX_PAGE_WALK = 20

        /**
         * CDN video (tnmr.org) hanya menerima User-Agent browser mobile.
         * UA desktop, ExoPlayer, okhttp, dan Dalvik semuanya dijawab 403.
         */
        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

        private const val PLAYER_REFERER = "https://luluvdo.com/"
    }

    override var mainUrl = DEFAULT_DOMAIN
    override var name = "Pemersatufun"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = true
    override val supportedTypes = setOf(TvType.NSFW)

    private val defaultHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to MOBILE_UA,
            "Referer" to "$mainUrl/",
        )

    private val playerHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to MOBILE_UA,
            "Referer" to PLAYER_REFERER,
        )

    override val mainPage = mainPageOf(
        "" to "Terbaru",
        "cari-malam/hijab" to "Hijab",
        "cari-malam/abg" to "ABG",
        "cari-malam/binal" to "Binal",
        "cari-malam/talent" to "Talent",
        "cari-malam/18yo" to "18yo",
        "cari-malam/ometv" to "OMETV",
        "cari-malam/jilbab" to "Jilbab",
    )

    // ---------------------------------------------------------------- katalog

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = if (request.data.isBlank()) {
            // Halaman 1 = beranda, halaman berikutnya = /arsip-malam/{n}
            val path = if (page <= 1) "/" else "/arsip-malam/$page"
            fetch(path)?.let { parseCards(it) } ?: emptyList()
        } else {
            followPages("/${request.data}", page)?.let { parseCards(it) } ?: emptyList()
        }

        val list = items.distinctBy { it.url }
        return newHomePageResponse(request.name, list, list.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val clean = query.trim()
        if (clean.isEmpty()) return newSearchResponseList(emptyList(), false)

        // Situs memakai /find?q= yang 301 ke /cari-malam/{slug}; ikut saja supaya
        // normalisasi (mis. pemotongan jumlah kata) selalu sama dengan situsnya.
        val encoded = URLEncoder.encode(clean, "UTF-8")
        var document = followPages("/find?q=$encoded", page)
        var items = document?.let { parseCards(it) } ?: emptyList()

        // Cadangan kalau redirect tidak diikuti: bentuk slug langsung.
        if (items.isEmpty()) {
            val slug = clean.lowercase().split(Regex("\\s+")).take(2).joinToString("-")
            if (slug.isNotBlank()) {
                document = followPages("/cari-malam/$slug", page)
                items = document?.let { parseCards(it) } ?: emptyList()
            }
        }

        val list = items.distinctBy { it.url }
        val hasNext = document?.selectFirst("link[rel=next]") != null
        return newSearchResponseList(list, hasNext)
    }

    // ---------------------------------------------------------------- detail

    override suspend fun load(url: String): LoadResponse {
        val document = fetch(url) ?: throw ErrorLoadingException("Gagal memuat halaman")
        val video = findVideoObject(document)

        val title = video?.optString("name")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: "Video"

        val poster = video?.optJSONArray("thumbnailUrl")?.optString(0)?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.takeIf { it.isNotBlank() }

        val description = video?.optString("description")?.trim()?.takeIf { it.isNotBlank() }
        val duration = parseIsoDuration(video?.optString("duration"))
        val year = Regex("""(\d{4})""").find(video?.optString("uploadDate") ?: "")
            ?.groupValues?.get(1)?.toIntOrNull()
        val views = video?.optJSONObject("interactionStatistic")
            ?.optLong("userInteractionCount")?.takeIf { it > 0 }

        val embedUrl = video?.optString("embedUrl")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("iframe[src*=luluvdo]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("iframe[src^=http]")?.attr("src")?.takeIf { it.isNotBlank() }

        val plot = buildString {
            if (description != null) append(description)
            if (views != null) {
                if (isNotEmpty()) append("\n\n")
                append("Dilihat %,d kali".format(views))
            }
        }.takeIf { it.isNotBlank() }

        val recommendations = parseCards(document).filter { it.url != url }

        return newMovieLoadResponse(title, url, TvType.NSFW, embedUrl ?: url) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
            this.plot = plot
            this.year = year
            this.recommendations = recommendations
            if (duration != null) this.duration = duration
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embedUrl = resolveEmbedUrl(data) ?: return false

        if (embedUrl.contains("luluvdo.com") || embedUrl.contains("lulustream.com")) {
            val stream = PemersatufunExtractor.getStream(embedUrl, defaultHeaders)
            if (!stream.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(name, name, stream, ExtractorLinkType.M3U8) {
                        this.referer = PLAYER_REFERER
                        this.headers = playerHeaders
                        this.quality = Qualities.P720.value
                    }
                )
                return true
            }
        }

        // Host lain: serahkan ke extractor bawaan CloudStream.
        loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)
        return true
    }

    // ---------------------------------------------------------------- helper

    private suspend fun resolveEmbedUrl(data: String): String? {
        if (data.contains("luluvdo.com") || data.contains("lulustream.com")) return data
        val document = fetch(data) ?: return null
        return document.selectFirst("iframe[src*=luluvdo]")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: findVideoObject(document)?.optString("embedUrl")?.takeIf { it.isNotBlank() }
    }

    /**
     * Ambil halaman dan, kalau host aktif sedang mati, coba host cadangan.
     * `url` boleh path relatif ("/arsip-malam/2") atau URL absolut.
     */
    private suspend fun fetch(url: String): Document? {
        val targets = linkedSetOf<String>()
        if (url.startsWith("http")) {
            targets.add(url)
            val tail = url.substringAfter("://").substringAfter("/", "")
            CANDIDATE_DOMAINS.forEach { targets.add("$it/$tail") }
        } else {
            targets.add("$mainUrl$url")
            CANDIDATE_DOMAINS.forEach { targets.add("$it$url") }
        }

        var lastError: Exception? = null
        for (target in targets) {
            try {
                val response = app.get(target, headers = defaultHeaders, timeout = 20)
                if (response.code in 200..399) {
                    val finalUrl = response.okhttpResponse.request.url
                    val host = "${finalUrl.scheme}://${finalUrl.host}"
                    if (host.startsWith("http")) mainUrl = host
                    return response.document
                }
            } catch (e: Exception) {
                lastError = e
            }
        }
        if (lastError != null) throw lastError
        return null
    }

    /** Telusuri paginasi `rel="next"` sampai halaman yang diminta. */
    private suspend fun followPages(path: String, page: Int): Document? {
        var document = fetch(path) ?: return null
        var current = 1
        while (current < page) {
            if (current >= MAX_PAGE_WALK) return null
            val next = document.selectFirst("link[rel=next]")?.attr("href")?.takeIf { it.isNotBlank() }
                ?: return null
            document = fetch(next) ?: return null
            current++
        }
        return document
    }

    private fun parseCards(document: Document): List<SearchResponse> =
        document.select("article[data-video-code]").mapNotNull { it.toSearchResult() }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("data-video-url").takeIf { it.isNotBlank() }
            ?: selectFirst("a[href*=/malam/]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null

        val title = attr("data-video-title").takeIf { it.isNotBlank() }
            ?: selectFirst("h2")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            attr("data-video-thumb").takeIf { it.isNotBlank() }
                ?: selectFirst("img")?.attr("src")
        )

        return newMovieSearchResponse(title, fixUrl(href), TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
        }
    }

    /** Cari blok JSON-LD `VideoObject` (langsung atau di dalam `@graph`). */
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

    /** "PT3090S" / "PT1H2M3S" -> detik. */
    private fun parseIsoDuration(value: String?): Int? {
        if (value.isNullOrBlank()) return null
        val match = Regex("""P(?:(\d+)D)?(?:T(?:(\d+)H)?(?:(\d+)M)?(?:(\d+(?:\.\d+)?)S)?)?""").find(value)
            ?: return null
        val days = match.groupValues[1].toIntOrNull() ?: 0
        val hours = match.groupValues[2].toIntOrNull() ?: 0
        val minutes = match.groupValues[3].toIntOrNull() ?: 0
        val seconds = match.groupValues[4].toDoubleOrNull()?.toInt() ?: 0
        return (days * 86400 + hours * 3600 + minutes * 60 + seconds).takeIf { it > 0 }
    }
}
