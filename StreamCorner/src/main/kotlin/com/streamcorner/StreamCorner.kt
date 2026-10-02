package com.streamcorner

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@JsonIgnoreProperties(ignoreUnknown = true)
data class CornerEvent(
    val stream_id: String? = null,
    val channel_id: String? = null,
    val event_name: String? = null,
    val channel_name: String? = null,
    val name: String? = null,
    val category: String? = null,
    val category_logo: String? = null,
    val league: String? = null,
    val league_logo: String? = null,
    val home_team: String? = null,
    val home_team_logo: String? = null,
    val away_team: String? = null,
    val away_team_logo: String? = null,
    val poster: String? = null,
    val channel_logo: String? = null,
    val timestamp: Long? = null,
    val time_utc: String? = null,
    val time_et: String? = null,
    val streams: List<CornerStream>? = null,
    var providerId: String = ""
) {
    fun getId(): String = stream_id ?: channel_id ?: ""
    fun getDisplayName(): String = event_name ?: channel_name ?: name ?: "Live Event"
    fun getPosterUrl(): String? = poster ?: channel_logo ?: category_logo ?: home_team_logo ?: away_team_logo

    fun isLive(): Boolean {
        if (time_et?.equals("LIVE", ignoreCase = true) == true) return true
        val ts = getTimestampMs()
        if (ts > 0) {
            val now = System.currentTimeMillis()
            return (now >= ts && (now - ts) < 5 * 3600 * 1000)
        }
        return false
    }

    fun getTimestampMs(): Long {
        if (timestamp != null && timestamp > 0) {
            return if (timestamp < 10_000_000_000L) timestamp * 1000 else timestamp
        }
        return 0L
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CornerStream(
    val source_name: String? = null,
    val name: String? = null,
    val stream_url: String? = null,
    val stream_keys: String? = null,
    val embed_url: String? = null
)

class StreamCorner : MainAPI() {
    override var mainUrl = "https://streamcorner.st"
    override var name = "StreamCorner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false

    override val supportedTypes = setOf(
        TvType.Live
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

    private val workers = listOf(
        "data.gigav.workers.dev",
        "data.yedmzoa.workers.dev",
        "data.ngagzipx.workers.dev",
        "data.miopks.workers.dev",
        "data.jccldjshj8sw.workers.dev",
        "data.l0o1afmju0.workers.dev",
        "data.nibflolsi9.workers.dev",
        "data.5j181.workers.dev",
        "data.rim1043.workers.dev",
        "data.kuig2.workers.dev",
        "data.senbon001.workers.dev",
        "data.senbon001-2.workers.dev",
        "data.senbon002.workers.dev",
        "data.senbon003.workers.dev",
        "data.kageyoshi001.workers.dev",
        "data.silentbyte125.workers.dev",
        "data.stealthwolf798-69b.workers.dev",
        "data.redjoy256.workers.dev",
        "data.anonfox144.workers.dev",
        "data.cripw4lk000.workers.dev",
        "data.phamviet444.workers.dev",
        "data.kanghaerin444.workers.dev",
        "data.minjikim444.workers.dev",
        "data.leehyein444.workers.dev",
        "data.daniellemarsh444.workers.dev"
    )

    private val providers = listOf(
        "admin",
        "unl",
        "alpha",
        "beta",
        "001",
        "003",
        "extra003",
        "extra004",
        "channels",
        "slingtv"
    )

    private var cachedEvents: List<CornerEvent>? = null
    private var lastFetchTime = 0L

    private suspend fun postWorker(endpointParam: String, queryId: String? = null): String? {
        val payload = StreamCornerCipher.encryptRequest(endpointParam)
        val shuffledWorkers = workers.shuffled()

        for (worker in shuffledWorkers.take(5)) {
            val url = if (queryId.isNullOrBlank()) {
                "https://$worker/corner?p=$endpointParam"
            } else {
                "https://$worker/corner?p=$endpointParam&id=$queryId"
            }

            try {
                val res = app.post(
                    url,
                    data = mapOf(),
                    headers = mapOf(
                        "Accept" to "application/json",
                        "Content-Type" to "text/plain",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    ),
                    requestBody = payload.body.toRequestBody("text/plain".toMediaTypeOrNull())
                )

                if (res.isSuccessful) {
                    val decrypted = StreamCornerCipher.decryptResponse(
                        res.text,
                        payload.nonce,
                        payload.paramBytes
                    )
                    if (decrypted.isNotBlank()) {
                        return decrypted
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    private suspend fun fetchAllEvents(force: Boolean = false): List<CornerEvent> {
        val now = System.currentTimeMillis()
        if (!force && cachedEvents != null && (now - lastFetchTime) < 45_000L) {
            return cachedEvents!!
        }

        val allList = mutableListOf<CornerEvent>()
        coroutineScope {
            val tasks = providers.map { prov ->
                async {
                    val pName = if (prov == "slingtv") "slingtv_channels" else prov
                    val jsonStr = postWorker(pName)
                    if (!jsonStr.isNullOrBlank()) {
                        val parsed = parseJson<List<CornerEvent>>(jsonStr)
                        parsed?.forEach { ev ->
                            ev.providerId = prov
                        }
                        parsed ?: emptyList()
                    } else {
                        emptyList()
                    }
                }
            }
            tasks.forEach {
                try {
                    allList.addAll(it.await())
                } catch (_: Exception) {}
            }
        }

        val distinctList = allList.filter { it.getId().isNotBlank() }.distinctBy { "${it.providerId}_${it.getId()}" }
        cachedEvents = distinctList
        lastFetchTime = now
        return distinctList
    }

    private suspend fun fetchEventDetail(providerId: String, id: String): CornerEvent? {
        val pName = if (providerId == "slingtv") "slingtv_channels" else providerId
        val jsonStr = postWorker(pName, id)
        if (!jsonStr.isNullOrBlank()) {
            return parseJson<CornerEvent>(jsonStr)
        }
        return null
    }

    private val timeFormat24 = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }

    private fun getCountdownOrStatus(event: CornerEvent): String {
        if (event.isLive()) return "LIVE"
        val dateMs = event.getTimestampMs()
        if (dateMs <= 0L) {
            return event.time_et ?: "TBA"
        }
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
            if (elapsedHours < 5) "LIVE" else "ENDED"
        }
    }

    private fun CornerEvent.toSearchResult(): SearchResponse {
        val dateMs = getTimestampMs()
        val time24 = if (dateMs > 0L) timeFormat24.format(Date(dateMs)) else ""
        val countdown = getCountdownOrStatus(this)
        val name = getDisplayName()
        val displayTitle = if (time24.isNotBlank() && countdown != "LIVE") "[$time24] $name" else name
        val poster = fixUrlNull(getPosterUrl())
        val watchUrl = "$mainUrl/watch/$providerId/${getId()}"

        return newLiveSearchResponse(displayTitle, watchUrl, TvType.Live) {
            this.posterUrl = poster
            addQuality(countdown)
        }
    }

    override val mainPage = mainPageOf(
        "ALL" to "ALL",
        "LIVE" to "LIVE",
        "TODAY" to "TODAY",
        "24/7 STREAMS" to "24/7 STREAMS",
        "AFCON QUALIFIERS" to "AFCON QUALIFIERS",
        "AMERICAN FOOTBALL" to "AMERICAN FOOTBALL",
        "ATP 1000" to "ATP 1000",
        "ATP 500" to "ATP 500",
        "AUSTRALIAN FOOTBALL" to "AUSTRALIAN FOOTBALL",
        "AUSTRALIAN NATIONAL RUGBY LEAGUE" to "AUSTRALIAN NATIONAL RUGBY LEAGUE",
        "AUSTRALIAN NBL" to "AUSTRALIAN NBL",
        "BASEBALL" to "BASEBALL",
        "BASKETBALL" to "BASKETBALL",
        "BSB" to "BSB",
        "CANADIAN PREMIER LEAGUE" to "CANADIAN PREMIER LEAGUE",
        "CFL" to "CFL",
        "CONCACAF NATIONS LEAGUE" to "CONCACAF NATIONS LEAGUE",
        "CRICKET" to "CRICKET",
        "CYCLING" to "CYCLING",
        "DARTS" to "DARTS",
        "EFL LEAGUE TWO" to "EFL LEAGUE TWO",
        "F1" to "F1",
        "FIGHTING" to "FIGHTING",
        "FOOTBALL" to "FOOTBALL",
        "FORMULA 1" to "FORMULA 1",
        "ICE HOCKEY" to "ICE HOCKEY",
        "INTERNATIONAL FRIENDLY" to "INTERNATIONAL FRIENDLY",
        "LALIGA" to "LALIGA",
        "MLB" to "MLB",
        "MLS" to "MLS",
        "MOTOCROSS" to "MOTOCROSS",
        "MOTOGP" to "MOTOGP",
        "MOTORSPORT" to "MOTORSPORT",
        "NASCAR" to "NASCAR",
        "NBA" to "NBA",
        "NCAA DIVISION 1 FOOTBALL" to "NCAA DIVISION 1 FOOTBALL",
        "NCAAF" to "NCAAF",
        "NFL" to "NFL",
        "NHL" to "NHL",
        "NORTHERN SUPER LEAGUE" to "NORTHERN SUPER LEAGUE",
        "NWSL" to "NWSL",
        "OTHERS" to "OTHERS",
        "RUGBY" to "RUGBY",
        "SHOOTING" to "SHOOTING",
        "SNOOKER" to "SNOOKER",
        "SOCCER" to "SOCCER",
        "TENNIS" to "TENNIS",
        "UCL WOMENS" to "UCL WOMENS",
        "UEFA" to "UEFA",
        "UEFA NATIONS" to "UEFA NATIONS",
        "UEFA NATIONS LEAGUE" to "UEFA NATIONS LEAGUE",
        "UEFA NATIONS LEAGUE POST SHOW" to "UEFA NATIONS LEAGUE POST SHOW",
        "UEFA NATIONS LEAGUE PRE SHOW" to "UEFA NATIONS LEAGUE PRE SHOW",
        "UFC" to "UFC",
        "WNBA" to "WNBA",
        "WRC" to "WRC",
        "WRESTLING" to "WRESTLING",
        "WTA 1000" to "WTA 1000"
    )

    private fun matchesCategory(event: CornerEvent, cat: String): Boolean {
        val catText = (event.category ?: "").uppercase(Locale.ROOT)
        val leagueText = (event.league ?: "").uppercase(Locale.ROOT)
        val nameText = event.getDisplayName().uppercase(Locale.ROOT)
        val fullText = "$catText $leagueText $nameText"

        return when (cat) {
            "ALL" -> true
            "LIVE" -> event.isLive()
            "TODAY" -> {
                val ms = event.getTimestampMs()
                if (ms > 0L) {
                    val now = System.currentTimeMillis()
                    // Dalam rentang 24 jam ke depan atau live
                    (ms in (now - 5 * 3600 * 1000)..(now + 24 * 3600 * 1000))
                } else {
                    event.isLive()
                }
            }
            "24/7 STREAMS" -> catText.contains("24/7") || event.providerId == "channels" || event.providerId == "slingtv"
            "AFCON QUALIFIERS" -> fullText.contains("AFCON")
            "AMERICAN FOOTBALL" -> catText.contains("AMERICAN FOOTBALL") || leagueText.contains("NFL") || fullText.contains("AMERICAN FOOTBALL")
            "ATP 1000" -> fullText.contains("ATP 1000")
            "ATP 500" -> fullText.contains("ATP 500")
            "AUSTRALIAN FOOTBALL" -> catText.contains("AUSTRALIAN FOOTBALL") || leagueText.contains("AFL") || fullText.contains("AFL")
            "AUSTRALIAN NATIONAL RUGBY LEAGUE" -> fullText.contains("NRL") || fullText.contains("AUSTRALIAN NATIONAL RUGBY")
            "AUSTRALIAN NBL" -> fullText.contains("NBL")
            "BASEBALL" -> catText.contains("BASEBALL") || leagueText.contains("MLB") || fullText.contains("BASEBALL")
            "BASKETBALL" -> catText.contains("BASKETBALL") || leagueText.contains("NBA") || leagueText.contains("WNBA") || fullText.contains("BASKETBALL")
            "BSB" -> fullText.contains("BSB") || fullText.contains("BRITISH SUPERBIKE")
            "CANADIAN PREMIER LEAGUE" -> fullText.contains("CANADIAN PREMIER") || fullText.contains("CPL")
            "CFL" -> fullText.contains("CFL")
            "CONCACAF NATIONS LEAGUE" -> fullText.contains("CONCACAF")
            "CRICKET" -> catText.contains("CRICKET") || fullText.contains("CRICKET")
            "CYCLING" -> catText.contains("CYCLING") || fullText.contains("CYCLING")
            "DARTS" -> catText.contains("DARTS") || fullText.contains("DARTS")
            "EFL LEAGUE TWO" -> fullText.contains("LEAGUE TWO") || fullText.contains("EFL")
            "F1" -> catText == "F1" || fullText.contains("FORMULA 1") || fullText.contains("F1")
            "FIGHTING" -> catText.contains("FIGHT") || catText.contains("BOXING") || catText.contains("MMA") || catText.contains("UFC")
            "FOOTBALL" -> catText.contains("FOOTBALL") || catText.contains("SOCCER")
            "FORMULA 1" -> catText.contains("FORMULA 1") || fullText.contains("FORMULA 1") || fullText.contains("F1")
            "ICE HOCKEY" -> catText.contains("ICE HOCKEY") || leagueText.contains("NHL") || fullText.contains("HOCKEY")
            "INTERNATIONAL FRIENDLY" -> fullText.contains("FRIENDLY")
            "LALIGA" -> fullText.contains("LALIGA") || fullText.contains("LA LIGA")
            "MLB" -> leagueText.contains("MLB") || fullText.contains("MLB")
            "MLS" -> leagueText.contains("MLS") || fullText.contains("MLS")
            "MOTOCROSS" -> fullText.contains("MOTOCROSS") || fullText.contains("MX")
            "MOTOGP" -> fullText.contains("MOTOGP")
            "MOTORSPORT" -> catText.contains("MOTORSPORT") || catText.contains("RACING") || fullText.contains("MOTORSPORT")
            "NASCAR" -> fullText.contains("NASCAR")
            "NBA" -> leagueText.contains("NBA") || fullText.contains("NBA")
            "NCAA DIVISION 1 FOOTBALL" -> fullText.contains("NCAA") && (fullText.contains("FOOTBALL") || fullText.contains("DIVISION 1"))
            "NCAAF" -> catText.contains("NCAAF") || catText.contains("CFB") || fullText.contains("NCAAF") || fullText.contains("CFB")
            "NFL" -> leagueText.contains("NFL") || fullText.contains("NFL")
            "NHL" -> leagueText.contains("NHL") || fullText.contains("NHL")
            "NORTHERN SUPER LEAGUE" -> fullText.contains("NORTHERN SUPER LEAGUE") || fullText.contains("NSL")
            "NWSL" -> fullText.contains("NWSL")
            "OTHERS" -> catText.contains("OTHER") || catText.contains("ENTERTAINMENT") || catText.contains("REALITY")
            "RUGBY" -> catText.contains("RUGBY") || fullText.contains("RUGBY")
            "SHOOTING" -> catText.contains("SHOOTING") || fullText.contains("SHOOTING")
            "SNOOKER" -> catText.contains("SNOOKER") || fullText.contains("SNOOKER")
            "SOCCER" -> catText.contains("SOCCER") || catText.contains("FOOTBALL")
            "TENNIS" -> catText.contains("TENNIS") || fullText.contains("TENNIS") || fullText.contains("ATP") || fullText.contains("WTA")
            "UCL WOMENS" -> fullText.contains("UCL WOMEN") || fullText.contains("CHAMPIONS LEAGUE WOMEN")
            "UEFA" -> leagueText.contains("UEFA") || fullText.contains("UEFA")
            "UEFA NATIONS" -> fullText.contains("UEFA NATIONS")
            "UEFA NATIONS LEAGUE" -> fullText.contains("UEFA NATIONS LEAGUE")
            "UEFA NATIONS LEAGUE POST SHOW" -> fullText.contains("POST SHOW")
            "UEFA NATIONS LEAGUE PRE SHOW" -> fullText.contains("PRE SHOW")
            "UFC" -> fullText.contains("UFC") || fullText.contains("MMA")
            "WNBA" -> fullText.contains("WNBA")
            "WRC" -> fullText.contains("WRC")
            "WRESTLING" -> catText.contains("WRESTLING") || fullText.contains("WRESTLING") || fullText.contains("WWE") || fullText.contains("AEW")
            "WTA 1000" -> fullText.contains("WTA 1000")
            else -> fullText.contains(cat.uppercase(Locale.ROOT))
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

        val allEvents = fetchAllEvents()
        val catKey = request.data.trim()

        val filtered = allEvents.filter { matchesCategory(it, catKey) }
            .sortedWith(
                compareBy<CornerEvent> {
                    !it.isLive()
                }.thenBy {
                    val ms = it.getTimestampMs()
                    if (ms <= 0L) Long.MAX_VALUE else ms
                }
            )

        val items = filtered.map { it.toSearchResult() }
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
        val cleanQuery = query.trim().uppercase(Locale.ROOT)
        if (cleanQuery.isBlank()) return emptyList()

        val allEvents = fetchAllEvents()
        return allEvents.filter { ev ->
            ev.getDisplayName().uppercase(Locale.ROOT).contains(cleanQuery) ||
                    (ev.category ?: "").uppercase(Locale.ROOT).contains(cleanQuery) ||
                    (ev.league ?: "").uppercase(Locale.ROOT).contains(cleanQuery) ||
                    (ev.home_team ?: "").uppercase(Locale.ROOT).contains(cleanQuery) ||
                    (ev.away_team ?: "").uppercase(Locale.ROOT).contains(cleanQuery)
        }.map { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        // Format url: https://streamcorner.st/watch/{providerId}/{id}
        val cleanUrl = url.removePrefix(mainUrl).substringAfter("/watch/")
        val providerId = cleanUrl.substringBefore("/").trim()
        val id = cleanUrl.substringAfter("/").substringBefore("?").trim()

        val detailEvent = try {
            fetchEventDetail(providerId, id)
        } catch (_: Exception) {
            null
        }

        val cachedEvent = cachedEvents?.firstOrNull { it.providerId == providerId && it.getId() == id }
        val event = detailEvent ?: cachedEvent ?: CornerEvent(stream_id = id, providerId = providerId)

        val poster = fixUrlNull(event.getPosterUrl())
        val dateMs = event.getTimestampMs()
        val time24 = if (dateMs > 0L) timeFormat24.format(Date(dateMs)) else ""
        val countdown = getCountdownOrStatus(event)
        val fullDate = if (dateMs > 0L) SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date(dateMs)) else "TBA"

        val rawStreams = event.streams ?: cachedEvent?.streams ?: emptyList()
        val episodes = if (rawStreams.isNotEmpty()) {
            rawStreams.mapIndexed { idx, st ->
                val sName = st.source_name ?: st.name ?: "Stream ${idx + 1}"
                val streamTarget = st.stream_url ?: st.embed_url ?: ""
                val streamData = JSONObject().apply {
                    put("providerId", providerId)
                    put("id", id)
                    put("source_name", sName)
                    put("stream_url", st.stream_url ?: "")
                    put("embed_url", st.embed_url ?: "")
                    put("stream_keys", st.stream_keys ?: "")
                }.toString()

                newEpisode(streamData) {
                    this.name = sName
                    this.episode = idx + 1
                    this.posterUrl = poster
                    this.description = "Server: $sName\nStatus: $countdown\nTarget: $streamTarget"
                }
            }
        } else {
            listOf(
                newEpisode("detail:$providerId:$id") {
                    this.name = "Direct Live Stream ($countdown)"
                    this.episode = 1
                    this.posterUrl = poster
                }
            )
        }

        val plotDesc = buildString {
            if (!event.category.isNullOrBlank()) appendLine("Kategori: ${event.category}")
            if (!event.league.isNullOrBlank()) appendLine("Liga: ${event.league}")
            if (dateMs > 0L) appendLine("Tanggal: $fullDate")
            if (time24.isNotBlank()) appendLine("Kick-off: $time24 WIB/Lokal")
            appendLine("Status: $countdown")
            if (rawStreams.isNotEmpty()) appendLine("Server Tersedia: ${rawStreams.size} Server")
        }

        return newTvSeriesLoadResponse(
            name = if (time24.isNotBlank() && countdown != "LIVE") "[$time24] ${event.getDisplayName()}" else event.getDisplayName(),
            url = url,
            type = TvType.Live,
            episodes = episodes
        ) {
            this.posterUrl = poster
            this.plot = plotDesc
        }
    }

    private val extractor = StreamCornerExtractor()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanData = data.trim()
        if (cleanData.isBlank()) return false

        // 1. Cek apakah format JSON payload dari load()
        if (cleanData.startsWith("{") && cleanData.endsWith("}")) {
            try {
                val json = JSONObject(cleanData)
                val streamUrl = json.optString("stream_url").trim()
                val embedUrl = json.optString("embed_url").trim()
                val sourceNameRaw = json.optString("source_name")
                val sourceName = if (sourceNameRaw.isNullOrBlank()) name else sourceNameRaw
                val streamKeys = json.optString("stream_keys").trim()

                // A. Direct stream_url (.m3u8 / .mpd)
                if (streamUrl.isNotBlank()) {
                    if (streamUrl.contains(".m3u8")) {
                        val headersMap = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl
                        )
                        var generated = false
                        try {
                            val list = M3u8Helper.generateM3u8(
                                source = sourceName,
                                streamUrl = streamUrl,
                                referer = "$mainUrl/",
                                headers = headersMap
                            )
                            if (list.isNotEmpty()) {
                                list.forEach(callback)
                                generated = true
                            }
                        } catch (_: Exception) {}

                        if (!generated) {
                            callback(
                                newExtractorLink(
                                    name = "$sourceName Live HLS",
                                    source = sourceName,
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.P1080.value
                                }
                            )
                        }
                        return true
                    } else if (streamUrl.contains(".mpd")) {
                        callback(
                            newExtractorLink(
                                name = "$sourceName Live DASH",
                                source = sourceName,
                                url = streamUrl,
                                type = ExtractorLinkType.DASH
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        return true
                    }
                }

                // B. Ekstraksi embed_url
                if (embedUrl.isNotBlank()) {
                    if (extractor.extractStream(embedUrl, "$mainUrl/", subtitleCallback, callback)) {
                        return true
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Format fallback "detail:providerId:id"
        if (cleanData.startsWith("detail:")) {
            val parts = cleanData.split(":")
            if (parts.size >= 3) {
                val prov = parts[1]
                val id = parts[2]
                val detail = fetchEventDetail(prov, id)
                val streams = detail?.streams ?: emptyList()
                var extractedAny = false
                for (st in streams) {
                    val stUrl = st.stream_url ?: ""
                    val ebUrl = st.embed_url ?: ""
                    if (stUrl.isNotBlank() && (stUrl.contains(".m3u8") || stUrl.contains(".mpd"))) {
                        if (extractor.extractStream(stUrl, "$mainUrl/", subtitleCallback, callback)) {
                            extractedAny = true
                        }
                    } else if (ebUrl.isNotBlank()) {
                        if (extractor.extractStream(ebUrl, "$mainUrl/", subtitleCallback, callback)) {
                            extractedAny = true
                        }
                    }
                }
                if (extractedAny) return true
            }
        }

        // 3. Fallback umum ke StreamCornerExtractor
        return extractor.extractStream(cleanData, "$mainUrl/", subtitleCallback, callback)
    }
}
