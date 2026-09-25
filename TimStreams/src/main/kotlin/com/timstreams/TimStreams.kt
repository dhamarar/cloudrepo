package com.timstreams

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@JsonIgnoreProperties(ignoreUnknown = true)
data class LiveUpcomingResponse(
    val events: List<TimEvent>? = null,
    val genres: List<TimGenre>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TimGenre(
    val id: Int = 0,
    val name: String = "",
    val sub_categories: List<TimSubCategory>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TimSubCategory(
    val id: Int = 0,
    val name: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TimEvent(
    val url: String = "",
    val name: String = "",
    val logo: String? = null,
    val genre: Int = 0,
    val sub_genre: Int? = null,
    val time: String? = null,
    val isevent: Boolean? = true,
    val vip: Boolean? = false,
    val featured: Boolean? = false,
    val viewers: Int? = null,
    val streams: List<TimStream>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TimStream(
    val name: String = "",
    val url: String = "",
    val vip: Boolean? = false
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class WatchResponse(
    val genre_name: String? = null,
    val is_vip_user: Boolean? = false,
    val item: TimEvent? = null,
    val type: String? = null
)

enum class SportCategory(
    val genreId: Int,
    val displayName: String,
    val slug: String,
    val keywords: List<String>
) {
    MOTORSPORT(2, "Motorsport", "motorsport", listOf("motorsport", "racing", "speedway", "floracing", "dirtvision")),
    MMA(3, "Mixed Martial Arts", "mma", listOf("mixed martial arts", "mma", "ufc", "bjj", "fight")),
    AMERICAN_FOOTBALL(8, "American Football", "american-football", listOf("american football", "nfl")),
    BASEBALL(9, "Baseball", "baseball", listOf("baseball", "mlb"))
}

class TimStreams : MainAPI() {
    override var mainUrl = "https://timst.cfd"
    override var name = "TimStreams"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "all" to "Semua Pertandingan",
        "motorsport" to "Motorsport",
        "mma" to "Mixed Martial Arts",
        "american-football" to "American Football",
        "baseball" to "Baseball"
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

    private var cachedResponse: LiveUpcomingResponse? = null
    private var lastFetchTime = 0L

    private suspend fun fetchLiveUpcoming(force: Boolean = false): LiveUpcomingResponse {
        val now = System.currentTimeMillis()
        if (!force && cachedResponse != null && (now - lastFetchTime) < 30_000L) {
            return cachedResponse!!
        }

        val domains = listOf(mainUrl, "https://timstreams.st")
        for (domain in domains) {
            try {
                val url = "$domain/api/live-upcoming"
                val text = app.get(url, headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )).text
                val parsed = parseJson<LiveUpcomingResponse>(text)
                if (parsed != null && (!parsed.events.isNullOrEmpty() || !parsed.genres.isNullOrEmpty())) {
                    cachedResponse = parsed
                    lastFetchTime = now
                    mainUrl = domain
                    return parsed
                }
            } catch (_: Exception) {}
        }
        return cachedResponse ?: LiveUpcomingResponse()
    }

    private val timeFormat24 = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun parseEventTimeMs(timeStr: String?): Long {
        if (timeStr.isNullOrBlank()) return 0L
        val clean = timeStr.trim()
        val formats = listOf(
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd'T'HH:mm",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm"
        )
        for (fmt in formats) {
            try {
                val sdf = SimpleDateFormat(fmt, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("America/New_York")
                }
                val d = sdf.parse(clean)
                if (d != null) return d.time
            } catch (_: Exception) {}
        }
        return 0L
    }

    private fun getCountdownOrStatus(dateMs: Long): String {
        if (dateMs <= 0L) return "TBA"
        val now = System.currentTimeMillis()
        val diff = dateMs - now
        return if (diff > 0) {
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
        } else {
            val elapsedHours = (now - dateMs) / (1000 * 3600)
            if (elapsedHours < 5) "LIVE" else "ENDED / REPLAY"
        }
    }

    private fun matchesCategory(event: TimEvent, category: SportCategory, genreMap: Map<Int, String>): Boolean {
        if (event.genre == category.genreId) return true
        val genreName = genreMap[event.genre]?.lowercase() ?: ""
        val eventName = event.name.lowercase()
        return category.keywords.any { genreName.contains(it) || eventName.contains(it) }
    }

    private fun isSupportedSport(event: TimEvent, genreMap: Map<Int, String>): Boolean {
        return SportCategory.values().any { matchesCategory(event, it, genreMap) }
    }

    private fun TimEvent.toSearchResult(genreMap: Map<Int, String>): SearchResponse {
        val dateMs = parseEventTimeMs(time)
        val time24 = if (dateMs > 0L) timeFormat24.format(Date(dateMs)) else "--:--"
        val countdown = getCountdownOrStatus(dateMs)
        val displayTitle = if (time24 != "--:--") "$time24 • $name" else name
        val poster = fixUrlNull(logo)
        val eventUrl = "$mainUrl/watch/$url"

        return newLiveSearchResponse(displayTitle, eventUrl, TvType.Live) {
            this.posterUrl = poster
            addQuality(countdown)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) {
            return newHomePageResponse(
                HomePageList(
                    name = request.name,
                    list = emptyList(),
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        }

        val data = fetchLiveUpcoming()
        val genreMap = data.genres?.associate { it.id to it.name } ?: emptyMap()
        val allEvents = data.events ?: emptyList()

        if (request.data == "all") {
            val lists = mutableListOf<HomePageList>()
            for (cat in SportCategory.values()) {
                val catEvents = allEvents.filter { matchesCategory(it, cat, genreMap) }
                    .distinctBy { it.url }
                    .sortedWith(
                        compareBy<TimEvent> {
                            val ms = parseEventTimeMs(it.time)
                            val now = System.currentTimeMillis()
                            // Prioritaskan yang sedang LIVE (dimulai dalam 5 jam terakhir), kemudian yang akan datang
                            val isLive = ms > 0L && ms <= now && (now - ms) < 5 * 3600 * 1000
                            !isLive
                        }.thenBy {
                            val ms = parseEventTimeMs(it.time)
                            if (ms <= 0L) Long.MAX_VALUE else ms
                        }
                    )
                if (catEvents.isNotEmpty()) {
                    lists.add(
                        HomePageList(
                            name = "${cat.displayName} (${catEvents.size})",
                            list = catEvents.map { it.toSearchResult(genreMap) },
                            isHorizontalImages = true
                        )
                    )
                }
            }
            return newHomePageResponse(lists, hasNext = false)
        }

        val targetCat = SportCategory.values().firstOrNull { it.slug == request.data }
        val filteredEvents = if (targetCat != null) {
            allEvents.filter { matchesCategory(it, targetCat, genreMap) }
        } else {
            allEvents.filter { isSupportedSport(it, genreMap) }
        }.distinctBy { it.url }
        .sortedWith(
            compareBy<TimEvent> {
                val ms = parseEventTimeMs(it.time)
                val now = System.currentTimeMillis()
                val isLive = ms > 0L && ms <= now && (now - ms) < 5 * 3600 * 1000
                !isLive
            }.thenBy {
                val ms = parseEventTimeMs(it.time)
                if (ms <= 0L) Long.MAX_VALUE else ms
            }
        )

        val items = filteredEvents.map { it.toSearchResult(genreMap) }
        return newHomePageResponse(
            HomePageList(
                name = "${request.name} (${items.size})",
                list = items,
                isHorizontalImages = true
            ),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val data = fetchLiveUpcoming()
        val genreMap = data.genres?.associate { it.id to it.name } ?: emptyMap()
        val allEvents = data.events ?: emptyList()

        return allEvents.filter { event ->
            isSupportedSport(event, genreMap) &&
                (event.name.lowercase(Locale.ROOT).contains(cleanQuery) ||
                    event.url.lowercase(Locale.ROOT).contains(cleanQuery))
        }.distinctBy { it.url }.map { it.toSearchResult(genreMap) }
    }

    override suspend fun load(url: String): LoadResponse {
        val slug = url.removePrefix(mainUrl).substringAfter("/watch/").substringBefore("/").substringBefore("?").trim()

        val watchData = try {
            val watchUrl = "$mainUrl/api/watch/$slug"
            val text = app.get(watchUrl, headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )).text
            parseJson<WatchResponse>(text)
        } catch (_: Exception) {
            null
        }

        val allData = fetchLiveUpcoming()
        val genreMap = allData.genres?.associate { it.id to it.name } ?: emptyMap()
        val event = watchData?.item
            ?: allData.events?.firstOrNull { it.url == slug }
            ?: TimEvent(url = slug, name = slug)

        val poster = fixUrlNull(event.logo)
        val dateMs = parseEventTimeMs(event.time)
        val time24 = if (dateMs > 0L) timeFormat24.format(Date(dateMs)) else "--:--"
        val fullDate = if (dateMs > 0L) SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date(dateMs)) else "TBA"
        val countdown = getCountdownOrStatus(dateMs)

        val targetCat = SportCategory.values().firstOrNull { matchesCategory(event, it, genreMap) }
        val catName = watchData?.genre_name ?: targetCat?.displayName ?: genreMap[event.genre] ?: "Live Sports"

        val streams = event.streams ?: emptyList()
        val episodes = if (streams.isNotEmpty()) {
            streams.mapIndexed { index, st ->
                val sName = st.name.ifBlank { "Stream ${index + 1}" }
                val vipTag = if (st.vip == true) " [VIP]" else ""
                newEpisode(st.url) {
                    this.name = "$sName$vipTag"
                    this.episode = index + 1
                    this.posterUrl = poster
                    this.description = "Server: $sName$vipTag\nStatus: $countdown"
                }
            }
        } else {
            listOf(
                newEpisode("empty:$slug") {
                    this.name = "Stream Belum Tersedia ($countdown)"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        val plotDesc = buildString {
            appendLine("Kategori: $catName")
            appendLine("Tanggal: $fullDate")
            appendLine("Waktu Kick-off: $time24 WIB/Lokal")
            appendLine("Status: $countdown")
            if (event.viewers != null && event.viewers > 0) {
                appendLine("Penonton Live: ${event.viewers}")
            }
            if (streams.isNotEmpty()) {
                appendLine("Server Tersedia: ${streams.size} server")
            }
        }

        return newTvSeriesLoadResponse(
            name = "${if (time24 != "--:--") "[$time24] " else ""}${event.name}",
            url = url,
            type = TvType.Live,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = plotDesc
        }
    }

    private val extractor = TimStreamsExtractor()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.trim()
        if (cleanData.isBlank() || cleanData.startsWith("empty:")) return false

        // 1. Coba ekstraksi menggunakan TimStreamsExtractor
        if (extractor.extractStream(cleanData, referer = "$mainUrl/", callback)) {
            return true
        }

        // 2. Cek apakah di dalam halaman ada iframe
        try {
            val doc = app.get(cleanData, referer = "$mainUrl/").document
            var extractedAny = false
            doc.select("iframe").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (src.isNotBlank()) {
                    if (extractor.extractStream(src, referer = cleanData, callback)) {
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
