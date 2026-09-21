package com.indoav

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element

/**
 * IndoAV — https://www.indoav.com
 *
 * Tiga bagian yang diminta:
 *   - Terbaru  : `/?filter=terbaru` (halaman berikutnya `/halaman/{n}?filter=...`)
 *   - Trending : `/trending`, datanya dari JSON `GET /trending/feed`
 *   - Pencarian: `/cari?kata-kunci=...` (halaman berikutnya `/cari/halaman/{n}?kata-kunci=...`)
 *
 * Bagian pemutaran tidak bisa langsung memakai URL dari situs karena:
 *   1. URL m3u8 hanya keluar dari `POST /video/load/<token>/`, dan tokennya harus
 *      dirakit sendiri dari `data-filecode` + `aria-label` (lihat [IndoavCrypto]).
 *   2. Segmennya berupa JPEG yang isinya MPEG-TS terenkripsi AES, sehingga harus
 *      dilewatkan [IndoavProxy] lebih dulu.
 */
class Indoav : MainAPI() {

    override var mainUrl = "https://www.indoav.com"
    override var name = "Indoav"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val userAgent = IndoavProxy.USER_AGENT

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language" to "id-ID,id;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    private val imageHeaders = mapOf(
        "Referer" to "https://www.indoav.com/",
        "User-Agent" to userAgent
    )

    private fun htmlHeaders(referer: String = "$mainUrl/") =
        browserHeaders + mapOf("Referer" to referer)

    override val mainPage = mainPageOf(
        "trending:now" to "Trending Sekarang",
        "trending:day" to "Trending Hari Ini",
        "trending:week" to "Trending Minggu Ini",
        "trending:month" to "Trending Bulan Ini",
        "filter:terbaru" to "Terbaru",
        "filter:banyak-dilihat" to "Banyak Dilihat",
        "filter:banyak-disukai" to "Banyak Disukai",
        "filter:banyak-dikomentari" to "Banyak Dikomentari",
        "filter:durasi-panjang" to "Durasi Panjang",
        "genre:bokep-indo" to "Bokep Indo",
        "genre:bokep-jilbab" to "Bokep Jilbab",
        "genre:bokep-viral" to "Bokep Viral",
        "genre:bokep-janda" to "Bokep Janda",
        "genre:bokep-tante" to "Bokep Tante",
        "genre:bokep-mahasiswi" to "Bokep Mahasiswi",
        "genre:bokep-onlyfans" to "Bokep OnlyFans"
    )

    // ------------------------------------------------------------ Halaman utama

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val kind = request.data.substringBefore(':')
        val value = request.data.substringAfter(':', "")

        return when (kind) {
            "trending" -> {
                // `/trending/feed` mengirim satu pool berisi 40 video sekaligus,
                // jadi hanya halaman pertama yang berisi.
                val items = if (page > 1) emptyList() else trendingItems(value)
                newHomePageResponse(request.name, items, false)
            }

            "genre" -> listingResponse(request.name, "$mainUrl/genre/$value", page, null)

            else -> listingResponse(request.name, mainUrl, page, value)
        }
    }

    private suspend fun listingResponse(
        name: String,
        baseUrl: String,
        page: Int,
        filter: String?
    ): HomePageResponse {
        val url = when {
            filter.isNullOrBlank() && page <= 1 -> baseUrl
            filter.isNullOrBlank() -> "$baseUrl/halaman/$page"
            page <= 1 -> "$baseUrl/?filter=$filter"
            else -> "$baseUrl/halaman/$page?filter=$filter"
        }
        val cards = fetchCards(url)
        return newHomePageResponse(name, cards, cards.size >= PAGE_THRESHOLD)
    }

    // ---------------------------------------------------------------- Trending

    @Volatile
    private var trendingCache: Pair<Long, JSONObject>? = null

    /**
     * `GET /trending/feed` -> `{"success":true,"pool_size":40,"sections":{...},
     * "trendings":{"now":[...],"day":[...],"week":[...],"month":[...]}}`.
     * Hasilnya di-cache sebentar supaya empat baris Trending tidak menembak
     * endpoint yang sama empat kali.
     */
    private suspend fun trendingFeed(): JSONObject? {
        trendingCache?.let { (stamp, json) ->
            if (System.currentTimeMillis() - stamp < TRENDING_TTL_MS) return json
        }
        return try {
            val json = JSONObject(
                app.get(
                    "$mainUrl/trending/feed",
                    headers = browserHeaders + mapOf(
                        "Accept" to "application/json, text/plain, */*",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to "$mainUrl/trending"
                    )
                ).text
            )
            trendingCache = System.currentTimeMillis() to json
            json
        } catch (e: Exception) {
            Log.e(TAG, "gagal memuat trending: ${e.message}")
            null
        }
    }

    private suspend fun trendingItems(section: String): List<SearchResponse> {
        val trendings = trendingFeed()?.optJSONObject("trendings") ?: return emptyList()
        val array: JSONArray = trendings.optJSONArray(section)
            ?: trendings.optJSONArray("day")
            ?: return emptyList()

        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val slug = item.optString("title_slug").trim()
            if (slug.isBlank()) return@mapNotNull null
            val title = item.optString("title").trim().ifBlank { slug }
            val poster = item.optString("thumbnail_image").takeIf { it.isNotBlank() }

            newMovieSearchResponse(title, "$mainUrl/video/$slug", TvType.NSFW) {
                this.posterUrl = poster
                this.posterHeaders = imageHeaders
            }
        }
    }

    // ---------------------------------------------------------------- Daftar

    private suspend fun fetchCards(url: String): List<SearchResponse> = try {
        app.get(url, headers = htmlHeaders()).document
            .select("article.video-card")
            .mapNotNull { it.toSearchResult() }
    } catch (e: Exception) {
        Log.e(TAG, "gagal memuat daftar $url: ${e.message}")
        emptyList()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = selectFirst("a.video-card__link")
            ?: selectFirst("h2.video-card__title a")
            ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        // Judul di dalam <h2> dipotong dengan "...", sedangkan aria-label utuh.
        val title = link.attr("aria-label").trim()
            .ifBlank { selectFirst("h2.video-card__title a")?.text()?.trim().orEmpty() }
            .ifBlank { link.attr("title").trim() }
        if (title.isBlank()) return null

        val image = selectFirst("img.video-card__image")
        val poster = fixUrlNull(
            image?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: image?.attr("data-src")?.takeIf { it.isNotBlank() }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = poster
            this.posterHeaders = imageHeaders
        }
    }

    // --------------------------------------------------------------- Pencarian

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val keyword = query.trim()
        if (keyword.isBlank()) return newSearchResponseList(emptyList(), false)

        val encoded = java.net.URLEncoder.encode(keyword, "UTF-8").replace("+", "%20")
        val url = if (page <= 1) {
            "$mainUrl/cari?kata-kunci=$encoded"
        } else {
            "$mainUrl/cari/halaman/$page?kata-kunci=$encoded"
        }

        val cards = fetchCards(url)
        return newSearchResponseList(cards, cards.size >= PAGE_THRESHOLD)
    }

    override suspend fun search(query: String): List<SearchResponse> = search(query, 1).items

    // ---------------------------------------------------------------- Detail

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = htmlHeaders()).document

        val title = document.selectFirst("h1.video-page-title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[itemprop=name]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("meta[itemprop=thumbnailUrl]")?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?: document.selectFirst("meta[property=og:image]")?.attr("content")
                    ?.takeIf { it.isNotBlank() }
        )

        val plot = document.selectFirst("meta[itemprop=description]")?.attr("content")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val duration = document.selectFirst("meta[itemprop=duration]")?.attr("content")
            ?.let(::isoDurationToSeconds)

        // Blok "trending" di halaman detail baru diisi lewat JS, jadi rekomendasi
        // diambil dari feed trending dan video yang sedang dibuka dibuang.
        val recommendations = try {
            trendingItems("day").filter { it.url != url }.take(RECOMMENDATION_LIMIT)
        } catch (_: Exception) {
            emptyList()
        }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = imageHeaders
            this.plot = plot
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    // --------------------------------------------------------------- Pemutaran

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageUrl = data.trim()
        if (pageUrl.isBlank()) return false

        val document = try {
            app.get(pageUrl, headers = htmlHeaders()).document
        } catch (e: Exception) {
            Log.e(TAG, "gagal membuka halaman video: ${e.message}")
            return false
        }

        // Situs mengambil penanda dari potongan URL (`/video/<slug>`), kecuali untuk
        // stream "EM" yang memakai `data-filecode` — lihat `function m(e,a)` di bundle player.
        val slug = pageUrl.trimEnd('/').substringAfterLast('/')

        val streams = document.select("a.select-stream-link").mapNotNull { element ->
            val fileCode = element.attr("data-filecode").trim()
            // Label harus apa adanya ("EM"); server menolak token bila diganti.
            val label = element.attr("aria-label").trim().removePrefix("Stream ").trim()
            if (label.isBlank()) return@mapNotNull null
            val identifier = if (label == "EM") fileCode else slug
            if (identifier.isBlank()) null else identifier to label
        }.distinct()

        var found = false
        for ((identifier, label) in streams) {
            val token = IndoavCrypto.buildToken(identifier, label)
            val m3u8 = resolveStream(pageUrl, token) ?: continue
            val localUrl = IndoavProxy.playlistUrl(m3u8, pageUrl) ?: continue

            callback(
                newExtractorLink(
                    name = "$name - $label",
                    source = name,
                    url = localUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to "$mainUrl/"
                    )
                    this.quality = Qualities.Unknown.value
                }
            )
            found = true
        }
        return found
    }

    /** `POST /video/load/<token>/` -> URL m3u8 (masih terenkripsi RC4). */
    private suspend fun resolveStream(pageUrl: String, token: String): String? = try {
        val mediaType = "application/x-www-form-urlencoded".toMediaTypeOrNull()
        val response = app.post(
            "$mainUrl/video/load/$token/",
            headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to pageUrl,
                "Origin" to mainUrl,
                "Accept" to "*/*",
                "Content-Type" to "application/x-www-form-urlencoded",
                IndoavCrypto.HEADER_NAME to IndoavCrypto.HEADER_VALUE
            ),
            requestBody = IndoavCrypto.buildRequestBody(token).toRequestBody(mediaType)
        )
        IndoavCrypto.decodeResponse(response.text)?.takeIf { it.startsWith("http") }
    } catch (e: Exception) {
        Log.e(TAG, "gagal mengambil tautan stream: ${e.message}")
        null
    }

    // ---------------------------------------------------------------- Helper

    /** `P0DT0H5M24S` -> detik. */
    private fun isoDurationToSeconds(value: String): Int? {
        val match = ISO_DURATION.find(value) ?: return null
        val days = match.groupValues[1].toIntOrNull() ?: 0
        val hours = match.groupValues[2].toIntOrNull() ?: 0
        val minutes = match.groupValues[3].toIntOrNull() ?: 0
        val seconds = match.groupValues[4].toIntOrNull() ?: 0
        val total = ((days * 24 + hours) * 60 + minutes) * 60 + seconds
        return total.takeIf { it > 0 }
    }

    companion object {
        private const val TAG = "Indoav"

        /** Situs selalu mengirim 40 kartu per halaman. */
        private const val PAGE_THRESHOLD = 20
        private const val RECOMMENDATION_LIMIT = 12
        private const val TRENDING_TTL_MS = 5 * 60_000L

        private val ISO_DURATION = Regex("""P(?:(\d+)D)?T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?""")
    }
}
