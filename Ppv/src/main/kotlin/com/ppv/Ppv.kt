package com.ppv

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvResponse(
    val streams: List<PpvCategoryGroup>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvCategoryGroup(
    val category: String = "",
    val id: Int = 0,
    val always_live: Boolean? = false,
    val streams: List<PpvStreamItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvStreamItem(
    val id: Int = 0,
    val name: String = "",
    val tag: String? = null,
    val source_tag: String? = null,
    val poster: String? = null,
    val uri_name: String? = null,
    val starts_at: Long = 0L,
    val ends_at: Long = 0L,
    val always_live: Int? = 0,
    val locale: String? = null,
    val category_name: String? = null,
    val iframe: String? = null,
    val substreams: List<PpvSubstream>? = null,
    val viewers: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvSubstream(
    val id: Int = 0,
    val name: String = "",
    val tag: String? = null,
    val uri_name: String? = null,
    val source_tag: String? = null,
    val locale: String? = null,
    val iframe: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvStreamDetailResponse(
    val success: Boolean? = false,
    val data: PpvStreamDetail? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvStreamDetail(
    val id: Int = 0,
    val name: String = "",
    val poster: String? = null,
    val tag: String? = null,
    val source_tag: String? = null,
    val description: String? = null,
    val m3u8: String? = null,
    val sources: List<PpvSourceFrame>? = null,
    val substreams: List<PpvSubstream>? = null,
    val start_timestamp: Long = 0L,
    val end_timestamp: Long = 0L,
    val viewers: Int? = null,
    val uri: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PpvSourceFrame(
    val name: String? = null,
    val default: Boolean? = false,
    val type: String? = null,
    val data: String? = null
)

class Ppv : MainAPI() {
    override var mainUrl = "https://ppv.st"
    override var name = "PPV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "all" to "Semua Pertandingan",
        "live" to "Sedang Live",
        "football" to "Sepak Bola (Football)",
        "motorsports" to "Motorsports",
        "american-football" to "American Football (NFL)",
        "basketball" to "Basketball (NBA/WNBA)",
        "baseball" to "Baseball (MLB)",
        "combat-sports" to "Combat Sports / UFC",
        "ice-hockey" to "Ice Hockey (NHL)",
        "wrestling" to "Wrestling (WWE/AEW)",
        "rugby" to "Rugby",
        "cricket" to "Cricket",
        "247" to "24/7 Channels"
    )

    private val jsonMapper = jacksonObjectMapper().apply {
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
    }

    private inline fun <reified T> parseJson(text: String): T? {
        return try {
            jsonMapper.readValue<T>(text)
        } catch (_: Exception) {
            null
        }
    }

    private var cachedGroups: List<PpvCategoryGroup>? = null
    private var lastFetchTime = 0L

    private suspend fun fetchAllGroups(force: Boolean = false): List<PpvCategoryGroup> {
        val now = System.currentTimeMillis()
        if (!force && cachedGroups != null && (now - lastFetchTime) < 30_000L) {
            return cachedGroups!!
        }

        val apiUrls = listOf(
            "https://api.ppv.st/api/streams",
            "https://api.ppv.cx/api/streams"
        )

        for (api in apiUrls) {
            try {
                val text = app.get(api, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                    "Origin" to mainUrl,
                    "Referer" to "$mainUrl/"
                )).text
                val parsed = parseJson<PpvResponse>(text)
                if (parsed != null && !parsed.streams.isNullOrEmpty()) {
                    cachedGroups = parsed.streams
                    lastFetchTime = now
                    return parsed.streams
                }
            } catch (_: Exception) {}
        }
        return cachedGroups ?: emptyList()
    }

    private val timeFormat24 = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun isLiveNow(startsAtSec: Long, endsAtSec: Long, alwaysLive: Boolean): Boolean {
        if (alwaysLive || startsAtSec <= 0L) return true
        val now = System.currentTimeMillis()
        val startMs = startsAtSec * 1000L
        val endMs = if (endsAtSec > 0L) endsAtSec * 1000L else startMs + (4 * 3600 * 1000L)
        return now in startMs..endMs
    }

    private fun getCountdownOrStatus(startsAtSec: Long, endsAtSec: Long, alwaysLive: Boolean): String {
        if (alwaysLive || startsAtSec <= 0L) return "24/7 LIVE"
        val now = System.currentTimeMillis()
        val startMs = startsAtSec * 1000L
        val endMs = if (endsAtSec > 0L) endsAtSec * 1000L else startMs + (4 * 3600 * 1000L)

        return when {
            now < startMs -> {
                val diff = startMs - now
                val diffSec = diff / 1000
                val days = diffSec / (24 * 3600)
                val hours = (diffSec % (24 * 3600)) / 3600
                val minutes = (diffSec % 3600) / 60
                when {
                    days > 0 -> "Starts in ${days}d ${hours}h"
                    hours > 0 -> "Starts in ${hours}h ${minutes}m"
                    minutes > 0 -> "Starts in ${minutes}m"
                    else -> "Starts in < 1m"
                }
            }
            now <= endMs -> "LIVE"
            else -> "ENDED / REPLAY"
        }
    }

    private fun PpvStreamItem.toSearchResult(): SearchResponse {
        val isAlways = always_live == 1
        val startMs = starts_at * 1000L
        val time24 = if (startMs > 0L && !isAlways) timeFormat24.format(Date(startMs)) else "--:--"
        val countdown = getCountdownOrStatus(starts_at, ends_at, isAlways)
        val tagPrefix = tag?.takeIf { it.isNotBlank() }?.let { "[$it] " }.orEmpty()
        val displayTitle = if (time24 != "--:--") "$time24 • $tagPrefix$name" else "$tagPrefix$name"
        val targetUrl = "$mainUrl/watch/$id"

        return newLiveSearchResponse(displayTitle, targetUrl, TvType.Live) {
            this.posterUrl = fixUrlNull(poster)
            addQuality(countdown)
        }
    }

    private fun matchesCategorySlug(catName: String, slug: String): Boolean {
        val lower = catName.lowercase(Locale.ROOT)
        return when (slug) {
            "football" -> lower.contains("football") && !lower.contains("american") && !lower.contains("australian")
            "motorsports" -> lower.contains("motorsport") || lower.contains("racing")
            "american-football" -> lower.contains("american football")
            "basketball" -> lower.contains("basketball")
            "baseball" -> lower.contains("baseball")
            "combat-sports" -> lower.contains("combat") || lower.contains("ufc") || lower.contains("mma")
            "ice-hockey" -> lower.contains("hockey")
            "wrestling" -> lower.contains("wrestling")
            "rugby" -> lower.contains("rugby")
            "cricket" -> lower.contains("cricket")
            "247" -> lower.contains("24/7") || lower.contains("channel")
            else -> lower.contains(slug)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val groups = fetchAllGroups()

        if (request.data == "all") {
            val homeLists = groups.mapNotNull { cg ->
                val items = cg.streams?.sortedWith(
                    compareBy<PpvStreamItem> {
                        !isLiveNow(it.starts_at, it.ends_at, it.always_live == 1)
                    }.thenBy {
                        if (it.starts_at <= 0L) Long.MAX_VALUE else it.starts_at
                    }
                )?.map { it.toSearchResult() } ?: emptyList()

                if (items.isNotEmpty()) {
                    HomePageList(
                        name = "${cg.category} (${items.size})",
                        list = items,
                        isHorizontalImages = true
                    )
                } else null
            }
            return newHomePageResponse(homeLists)
        }

        if (request.data == "live") {
            val allLive = groups.flatMap { it.streams ?: emptyList() }
                .filter { isLiveNow(it.starts_at, it.ends_at, it.always_live == 1) }
                .distinctBy { it.id }
                .map { it.toSearchResult() }

            return newHomePageResponse(
                HomePageList(
                    name = "Sedang Berlangsung (${allLive.size})",
                    list = allLive,
                    isHorizontalImages = true
                )
            )
        }

        val matchingGroups = groups.filter { matchesCategorySlug(it.category, request.data) }
        val matchingStreams = matchingGroups.flatMap { it.streams ?: emptyList() }
            .sortedWith(
                compareBy<PpvStreamItem> {
                    !isLiveNow(it.starts_at, it.ends_at, it.always_live == 1)
                }.thenBy {
                    if (it.starts_at <= 0L) Long.MAX_VALUE else it.starts_at
                }
            )
            .map { it.toSearchResult() }

        return newHomePageResponse(
            HomePageList(
                name = "${request.name} (${matchingStreams.size})",
                list = matchingStreams,
                isHorizontalImages = true
            )
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val groups = fetchAllGroups()
        val allStreams = groups.flatMap { it.streams ?: emptyList() }.distinctBy { it.id }

        return allStreams.filter { item ->
            item.name.lowercase(Locale.ROOT).contains(cleanQuery) ||
                item.tag?.lowercase(Locale.ROOT)?.contains(cleanQuery) == true ||
                item.category_name?.lowercase(Locale.ROOT)?.contains(cleanQuery) == true
        }.map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val streamId = url.removePrefix(mainUrl).substringAfter("/watch/").substringBefore("/").substringBefore("?").trim()
        val streamIdInt = streamId.toIntOrNull()

        // 1. Coba ambil detail dari API /api/streams/{id}
        val detail = try {
            val detailUrl = "https://api.ppv.st/api/streams/$streamId"
            val text = app.get(detailUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Origin" to mainUrl,
                "Referer" to "$mainUrl/"
            )).text
            parseJson<PpvStreamDetailResponse>(text)?.data
        } catch (_: Exception) {
            null
        }

        // 2. Fallback ke cached item
        val groups = fetchAllGroups()
        val cachedItem = groups.flatMap { it.streams ?: emptyList() }.firstOrNull { it.id == streamIdInt }

        val title = detail?.name ?: cachedItem?.name ?: "PPV Stream"
        val poster = fixUrlNull(detail?.poster ?: cachedItem?.poster)
        val startSec = detail?.start_timestamp?.takeIf { it > 0L } ?: cachedItem?.starts_at ?: 0L
        val endSec = detail?.end_timestamp?.takeIf { it > 0L } ?: cachedItem?.ends_at ?: 0L
        val isAlways = cachedItem?.always_live == 1

        val startMs = startSec * 1000L
        val time24 = if (startMs > 0L && !isAlways) timeFormat24.format(Date(startMs)) else "--:--"
        val fullDate = if (startMs > 0L && !isAlways) SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date(startMs)) else "24/7"
        val countdown = getCountdownOrStatus(startSec, endSec, isAlways)
        val catName = cachedItem?.category_name ?: "Live Sports"
        val tag = detail?.tag ?: cachedItem?.tag.orEmpty()

        val episodeList = mutableListOf<Episode>()

        // A. Cek direct m3u8 pada detail jika ada
        if (!detail?.m3u8.isNullOrBlank()) {
            episodeList.add(
                newEpisode(detail!!.m3u8!!) {
                    this.name = "Stream 1 • Direct HLS"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        // B. Cek sources iframe pada detail
        detail?.sources?.forEachIndexed { idx, src ->
            val srcUrl = src.data ?: return@forEachIndexed
            val sName = src.name?.ifBlank { "Server ${idx + 1}" } ?: "Server ${idx + 1}"
            episodeList.add(
                newEpisode(srcUrl) {
                    this.name = "Stream ${episodeList.size + 1} • $sName"
                    this.episode = episodeList.size + 1
                    this.posterUrl = poster
                }
            )
        }

        // C. Cek iframe utama dari cachedItem jika belum ada
        if (episodeList.isEmpty() && !cachedItem?.iframe.isNullOrBlank()) {
            val srcTag = cachedItem?.source_tag?.ifBlank { "Main Server" } ?: "Main Server"
            episodeList.add(
                newEpisode(cachedItem!!.iframe!!) {
                    this.name = "Stream 1 • $srcTag"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        // D. Tambahkan substreams (multibahasa / multi-source)
        val allSubstreams = detail?.substreams ?: cachedItem?.substreams ?: emptyList()
        allSubstreams.forEach { sub ->
            val subIframe = sub.iframe ?: return@forEach
            val sTag = sub.source_tag?.takeIf { it.isNotBlank() } ?: "Server"
            val sLocale = sub.locale?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
            episodeList.add(
                newEpisode(subIframe) {
                    this.name = "Stream ${episodeList.size + 1} • $sTag$sLocale"
                    this.episode = episodeList.size + 1
                    this.posterUrl = poster
                }
            )
        }

        if (episodeList.isEmpty()) {
            episodeList.add(
                newEpisode("empty:$streamId") {
                    this.name = "Stream Belum Tersedia ($countdown)"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        val plotDesc = buildString {
            appendLine("Kategori: $catName")
            if (tag.isNotBlank()) appendLine("Turnamen/Liga: $tag")
            appendLine("Tanggal: $fullDate")
            appendLine("Waktu Kick-off: $time24 WIB/Lokal")
            appendLine("Status: $countdown")
            val viewers = detail?.viewers ?: cachedItem?.viewers?.toIntOrNull()
            if (viewers != null && viewers > 0) {
                appendLine("Penonton Live: $viewers")
            }
        }

        return newTvSeriesLoadResponse(
            name = "${if (time24 != "--:--") "[$time24] " else ""}$title",
            url = url,
            type = TvType.Live,
            episodes = episodeList
        ) {
            this.posterUrl = poster
            this.plot = plotDesc
        }
    }

    private val extractor = PpvExtractor()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.trim()
        if (cleanData.isBlank() || cleanData.startsWith("empty:")) return false

        // 1. Coba ekstraksi menggunakan PpvExtractor (mendukung WebViewResolver & m3u8)
        if (extractor.resolveStream(cleanData, ref = "$mainUrl/", callback)) {
            return true
        }

        // 2. Cek apakah halaman memiliki iframe
        try {
            val doc = app.get(cleanData, referer = "$mainUrl/").document
            var extractedAny = false
            doc.select("iframe").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (src.isNotBlank()) {
                    if (extractor.resolveStream(src, ref = cleanData, callback)) {
                        extractedAny = true
                    } else {
                        loadExtractor(src, referer = cleanData, subtitleCallback, callback)
                    }
                }
            }
            if (extractedAny) return true
        } catch (_: Exception) {}

        // 3. Fallback ke sistem extractor bawaan Cloudstream
        loadExtractor(cleanData, referer = "$mainUrl/", subtitleCallback, callback)
        return true
    }
}
