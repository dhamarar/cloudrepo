package com.streamed

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@JsonIgnoreProperties(ignoreUnknown = true)
data class Match(
    val id: String = "",
    val title: String = "",
    val category: String = "",
    val date: Long = 0L,
    val popular: Boolean? = false,
    val poster: String? = null,
    val teams: Teams? = null,
    val sources: List<SourceItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Teams(
    val home: Team? = null,
    val away: Team? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class Team(
    val name: String? = null,
    val badge: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SourceItem(
    val source: String = "",
    val id: String = ""
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class StreamItem(
    val id: String? = null,
    val streamNo: Int? = null,
    val language: String? = null,
    val hd: Boolean? = false,
    val embedUrl: String? = null,
    val source: String? = null,
    val viewers: Int? = null
)

class Streamed : MainAPI() {
    override var mainUrl = "https://streamed.pk"
    override var name = "Streamed"
    override val hasMainPage = true
    override var lang = "id"
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "all" to "Semua Jadwal",
        "live" to "Sedang Live",
        "football" to "Sepak Bola",
        "motor-sports" to "Balap Motor",
        "american-football" to "American Football (NFL)",
        "basketball" to "Basketball (NBA)",
        "baseball" to "Baseball",
        "fight" to "Fighting / UFC",
        "tennis" to "Tennis",
        "cricket" to "Cricket"
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

    private val timeFormat24 = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
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
            "LIVE"
        }
    }

    private fun getDateLabel(dateMs: Long): String {
        if (dateMs <= 0L) return "Jadwal Segera (TBA)"

        val matchCal = Calendar.getInstance().apply { timeInMillis = dateMs }
        val nowCal = Calendar.getInstance()

        val isSameYear = matchCal.get(Calendar.YEAR) == nowCal.get(Calendar.YEAR)
        val isToday = isSameYear && matchCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

        nowCal.add(Calendar.DAY_OF_YEAR, 1)
        val isTomorrow = isSameYear && matchCal.get(Calendar.DAY_OF_YEAR) == nowCal.get(Calendar.DAY_OF_YEAR)

        val dateStr = SimpleDateFormat("dd MMMM", Locale("id", "ID")).format(Date(dateMs))
        val fullDateStr = SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date(dateMs))

        return when {
            isToday -> "Hari Ini ($dateStr)"
            isTomorrow -> "Besok ($dateStr)"
            else -> fullDateStr
        }
    }

    private fun getPosterUrl(match: Match): String? {
        val p = match.poster
        if (!p.isNullOrBlank()) {
            return if (p.startsWith("http")) p else "$mainUrl$p"
        }

        val homeBadge = match.teams?.home?.badge
        val awayBadge = match.teams?.away?.badge
        if (!homeBadge.isNullOrBlank() && !awayBadge.isNullOrBlank()) {
            return "$mainUrl/api/images/poster/$homeBadge/$awayBadge.webp"
        }
        if (!homeBadge.isNullOrBlank()) {
            return "$mainUrl/api/images/badge/$homeBadge.webp"
        }
        if (!awayBadge.isNullOrBlank()) {
            return "$mainUrl/api/images/badge/$awayBadge.webp"
        }

        return if (!match.category.isNullOrBlank()) {
            "$mainUrl/api/images/badge/${match.category}.webp"
        } else {
            "$mainUrl/api/images/badge/football.webp"
        }
    }

    private fun Match.toSearchResult(): SearchResponse {
        val time24 = if (date > 0) timeFormat24.format(Date(date)) else "--:--"
        val countdown = getCountdownOrStatus(date)
        val displayTitle = if (time24 != "--:--") "$time24 • $title" else title
        val poster = getPosterUrl(this)
        val matchUrl = "$mainUrl/watch/$id"

        return newLiveSearchResponse(displayTitle, matchUrl, TvType.Live) {
            this.posterUrl = poster
            addQuality(countdown)
        }
    }

    private suspend fun fetchMatchesByCategory(category: String): List<Match> {
        return try {
            val url = "$mainUrl/api/matches/$category"
            val text = app.get(url).text
            parseJson<List<Match>>(text)
                ?: parseJson<Array<Match>>(text)?.toList()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchLiveMatches(): List<Match> {
        return try {
            val url = "$mainUrl/api/matches/live"
            val text = app.get(url).text
            parseJson<List<Match>>(text)
                ?: parseJson<Array<Match>>(text)?.toList()
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
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

        val rawMatches = when (request.data) {
            "all" -> fetchMatchesByCategory("all")
            "live" -> fetchLiveMatches()
            else -> fetchMatchesByCategory(request.data)
        }

        val matches = rawMatches.distinctBy { it.id }

        // Khusus tab live, jika ada live match tampilkan langsung
        if (request.data == "live") {
            val items = matches.map { it.toSearchResult() }
            return newHomePageResponse(
                HomePageList(
                    name = "Sedang Berlangsung (${items.size})",
                    list = items,
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        }

        // Group by tanggal
        val sortedMatches = matches.sortedWith(
            compareBy<Match> { it.date <= 0L }
                .thenBy { it.date }
        )

        val groups = sortedMatches.groupBy { getDateLabel(it.date) }
        val homeLists = groups.map { (dateLabel, groupMatches) ->
            HomePageList(
                name = dateLabel,
                list = groupMatches.map { it.toSearchResult() },
                isHorizontalImages = true
            )
        }

        return newHomePageResponse(homeLists, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val all = fetchMatchesByCategory("all")

        return all.distinctBy { it.id }.filter { match ->
            match.title.lowercase(Locale.ROOT).contains(cleanQuery) ||
                match.teams?.home?.name?.lowercase(Locale.ROOT)?.contains(cleanQuery) == true ||
                match.teams?.away?.name?.lowercase(Locale.ROOT)?.contains(cleanQuery) == true
        }.map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.removePrefix(mainUrl).substringAfter("/watch/").substringBefore("/").substringBefore("?").trim()
        val allMatches = fetchMatchesByCategory("all")
        val match = allMatches.firstOrNull { it.id == matchId }
            ?: fetchLiveMatches().firstOrNull { it.id == matchId }
            ?: Match(id = matchId, title = matchId)

        val poster = getPosterUrl(match)
        val time24 = if (match.date > 0) timeFormat24.format(Date(match.date)) else "--:--"
        val fullDate = if (match.date > 0) SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date(match.date)) else "TBA"
        val countdown = getCountdownOrStatus(match.date)

        val sources = match.sources ?: emptyList()
        val allStreams = mutableListOf<StreamItem>()
        for (src in sources) {
            try {
                val streamUrl = "$mainUrl/api/stream/${src.source}/${src.id}"
                val text = app.get(streamUrl).text
                val res = parseJson<List<StreamItem>>(text)
                    ?: parseJson<Array<StreamItem>>(text)?.toList()
                if (res != null) {
                    allStreams.addAll(res.filter { !it.embedUrl.isNullOrBlank() })
                }
            } catch (_: Exception) {}
        }

        val episodes = if (allStreams.isNotEmpty()) {
            val groupedStreams = allStreams.groupBy { it.streamNo ?: 1 }
            val sortedStreamNos = groupedStreams.keys.sorted()

            sortedStreamNos.map { streamNo ->
                val itemsInStream = groupedStreams[streamNo] ?: emptyList()
                val sourceNames = itemsInStream.mapNotNull { it.source?.replaceFirstChar { c -> c.uppercase() } }.distinct()
                val isHd = itemsInStream.any { it.hd == true }
                val hdTag = if (isHd) " [HD]" else ""
                val sourceListStr = sourceNames.joinToString(", ")

                val epTitle = if (sourceListStr.isNotBlank()) {
                    "Stream $streamNo • $sourceListStr$hdTag"
                } else {
                    "Stream $streamNo$hdTag"
                }

                val epDescription = itemsInStream.joinToString("\n") { st ->
                    val sName = st.source?.replaceFirstChar { c -> c.uppercase() } ?: "Source"
                    val sLang = st.language?.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
                    val sHd = if (st.hd == true) " [HD]" else ""
                    "• $sName$sLang$sHd"
                }

                val streamJsonData = jsonMapper.writeValueAsString(itemsInStream)

                newEpisode(streamJsonData) {
                    this.name = epTitle
                    this.episode = streamNo
                    this.posterUrl = poster
                    this.description = epDescription
                }
            }
        } else {
            listOf(
                newEpisode("match:${match.id}") {
                    this.name = "Live Stream (Dimulai pukul $time24 • $countdown)"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        val sportCategory = when (match.category) {
            "football" -> "Sepak Bola"
            "motor-sports" -> "Motorsports"
            "american-football" -> "American Football (NFL)"
            "basketball" -> "Basketball (NBA)"
            "baseball" -> "Baseball"
            "fight" -> "Fighting / UFC"
            "tennis" -> "Tennis"
            "cricket" -> "Cricket"
            else -> match.category.replaceFirstChar { it.uppercase() }
        }

        val plotDesc = buildString {
            appendLine("Olahraga: $sportCategory")
            appendLine("Tanggal: $fullDate")
            appendLine("Waktu Kick-off: $time24 WIB/Lokal")
            appendLine("Status: $countdown")
            if (sources.isNotEmpty()) {
                appendLine("Server Tersedia: ${sources.joinToString(", ") { it.source.replaceFirstChar { c -> c.uppercase() } }}")
            }
        }

        return newTvSeriesLoadResponse(
            name = "${if (time24 != "--:--") "[$time24] " else ""}${match.title}",
            url = url,
            type = TvType.Live,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = plotDesc
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("[") || data.startsWith("{")) {
            val items = parseJson<List<StreamItem>>(data)
                ?: parseJson<Array<StreamItem>>(data)?.toList()
                ?: emptyList()

            for (st in items) {
                val embedUrl = st.embedUrl ?: continue
                try {
                    loadExtractor(embedUrl, referer = "$mainUrl/", subtitleCallback, callback)
                } catch (_: Exception) {}
            }
            return true
        }

        if (data.startsWith("match:")) {
            val matchId = data.removePrefix("match:")
            val allMatches = fetchMatchesByCategory("all")
            val match = allMatches.firstOrNull { it.id == matchId }
                ?: fetchLiveMatches().firstOrNull { it.id == matchId }

            val sources = match?.sources ?: emptyList()
            for (src in sources) {
                try {
                    val streamUrl = "$mainUrl/api/stream/${src.source}/${src.id}"
                    val text = app.get(streamUrl).text
                    val res = parseJson<List<StreamItem>>(text)
                        ?: parseJson<Array<StreamItem>>(text)?.toList()
                    res?.filter { !it.embedUrl.isNullOrBlank() }?.forEach { st ->
                        val embedUrl = st.embedUrl ?: return@forEach
                        try {
                            loadExtractor(embedUrl, referer = "$mainUrl/", subtitleCallback, callback)
                        } catch (_: Exception) {}
                    }
                } catch (_: Exception) {}
            }
            return true
        }

        if (data.isNotBlank() && data.startsWith("http")) {
            loadExtractor(data, referer = "$mainUrl/", subtitleCallback, callback)
        }

        return true
    }
}
