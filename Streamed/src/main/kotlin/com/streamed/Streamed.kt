package com.streamed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

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
        "football" to "Sepak Bola",
        "motor-sports" to "Balap Motor",
        "live" to "Sedang Live"
    )

    // Data classes untuk Streamed API
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

    data class Teams(
        val home: Team? = null,
        val away: Team? = null
    )

    data class Team(
        val name: String? = null,
        val badge: String? = null
    )

    data class SourceItem(
        val source: String = "",
        val id: String = ""
    )

    data class StreamItem(
        val id: String? = null,
        val streamNo: Int? = null,
        val language: String? = null,
        val hd: Boolean? = false,
        val embedUrl: String? = null,
        val source: String? = null,
        val viewers: Int? = null
    )

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

        return if (match.category == "motor-sports") {
            "https://streamed.pk/api/images/badge/motor-sports.webp"
        } else {
            "https://streamed.pk/api/images/badge/football.webp"
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
            app.get(url).parsedSafe<List<Match>>() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchLiveMatches(): List<Match> {
        return try {
            val url = "$mainUrl/api/matches/live"
            val live = app.get(url).parsedSafe<List<Match>>() ?: emptyList()
            live.filter { it.category == "football" || it.category == "motor-sports" }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val matches = when (request.data) {
            "all" -> {
                val football = fetchMatchesByCategory("football")
                val motor = fetchMatchesByCategory("motor-sports")
                (football + motor).distinctBy { it.id }
            }
            "football" -> fetchMatchesByCategory("football")
            "motor-sports" -> fetchMatchesByCategory("motor-sports")
            "live" -> fetchLiveMatches()
            else -> fetchMatchesByCategory("football")
        }

        // Khusus tab live, jika ada live match tampilkan langsung
        if (request.data == "live") {
            val items = matches.map { it.toSearchResult() }
            return newHomePageResponse(
                HomePageList(
                    name = "Sedang Berlangsung (${items.size})",
                    list = items,
                    isHorizontalImages = true
                )
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

        return newHomePageResponse(homeLists)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().lowercase(Locale.ROOT)
        val football = fetchMatchesByCategory("football")
        val motor = fetchMatchesByCategory("motor-sports")
        val all = (football + motor).distinctBy { it.id }

        return all.filter { match ->
            match.title.lowercase(Locale.ROOT).contains(cleanQuery) ||
                match.teams?.home?.name?.lowercase(Locale.ROOT)?.contains(cleanQuery) == true ||
                match.teams?.away?.name?.lowercase(Locale.ROOT)?.contains(cleanQuery) == true
        }.map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val matchId = url.substringAfterLast("/watch/").substringBefore("?").trim()
        val allMatches = (fetchMatchesByCategory("football") + fetchMatchesByCategory("motor-sports")).distinctBy { it.id }
        val match = allMatches.firstOrNull { it.id == matchId }
            ?: fetchLiveMatches().firstOrNull { it.id == matchId }
            ?: Match(id = matchId, title = matchId)

        val poster = getPosterUrl(match)
        val time24 = if (match.date > 0) timeFormat24.format(Date(match.date)) else "--:--"
        val fullDate = if (match.date > 0) SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date(match.date)) else "TBA"
        val countdown = getCountdownOrStatus(match.date)

        val sources = match.sources ?: emptyList()
        val streams = mutableListOf<StreamItem>()

        sources.forEach { src ->
            try {
                val streamUrl = "$mainUrl/api/stream/${src.source}/${src.id}"
                val res = app.get(streamUrl).parsedSafe<List<StreamItem>>()
                if (res != null) {
                    streams.addAll(res)
                }
            } catch (_: Exception) {}
        }

        val episodes = if (streams.isNotEmpty()) {
            streams.mapIndexed { idx, st ->
                val epTitle = buildString {
                    append("Stream ${st.streamNo ?: (idx + 1)}")
                    st.source?.let { append(" • ${it.replaceFirstChar { c -> c.uppercase() }}") }
                    st.language?.let { append(" • $it") }
                    if (st.hd == true) append(" [HD]")
                }
                newEpisode(st.embedUrl ?: "") {
                    this.name = epTitle
                    this.episode = idx + 1
                    this.posterUrl = poster
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

        val plotDesc = buildString {
            appendLine("⚽ Olahraga: ${if (match.category == "motor-sports") "Motorsports" else "Sepak Bola"}")
            appendLine("📅 Tanggal: $fullDate")
            appendLine("⏰ Waktu Kick-off: $time24 WIB/Lokal")
            appendLine("⏳ Status: $countdown")
            if (sources.isNotEmpty()) {
                appendLine("📡 Server Tersedia: ${sources.joinToString(", ") { it.source.replaceFirstChar { c -> c.uppercase() } }}")
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
        if (data.startsWith("match:")) {
            val matchId = data.removePrefix("match:")
            val allMatches = (fetchMatchesByCategory("football") + fetchMatchesByCategory("motor-sports")).distinctBy { it.id }
            val match = allMatches.firstOrNull { it.id == matchId }
                ?: fetchLiveMatches().firstOrNull { it.id == matchId }

            match?.sources?.forEach { src ->
                try {
                    val streamUrl = "$mainUrl/api/stream/${src.source}/${src.id}"
                    val streams = app.get(streamUrl).parsedSafe<List<StreamItem>>()
                    streams?.forEach { st ->
                        st.embedUrl?.let { embedUrl ->
                            loadExtractor(embedUrl, referer = "$mainUrl/", subtitleCallback, callback)
                        }
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
