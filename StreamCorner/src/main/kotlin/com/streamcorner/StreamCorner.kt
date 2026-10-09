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
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

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

@JsonIgnoreProperties(ignoreUnknown = true)
data class CornerChannelContainer(
    val channels: List<CornerEvent>? = null
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
        "mlb",
        "nfl",
        "nba",
        "alpha",
        "beta",
        "001",
        "003",
        "extra001",
        "extra002",
        "extra003",
        "extra004",
        "channels",
        "slingtv"
    )

    private var cachedEvents: List<CornerEvent>? = null
    private var lastFetchTime = 0L
    private val eventDetailCache = ConcurrentHashMap<String, CornerEvent>()

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
                        "Accept" to "application/octet-stream",
                        "Content-Type" to "text/plain",
                        "Origin" to mainUrl,
                        "Referer" to "$mainUrl/"
                    ),
                    requestBody = payload.body.toRequestBody("text/plain".toMediaTypeOrNull())
                )

                if (res.isSuccessful) {
                    val decrypted = StreamCornerCipher.decryptResponse(
                        res.body.bytes(),
                        payload
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
                            ?: parseJson<CornerChannelContainer>(jsonStr)?.channels
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
        val streamUrl = "$mainUrl/stream/$providerId/${getId()}"

        return newLiveSearchResponse(displayTitle, streamUrl, TvType.Live) {
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

    private fun parseProviderAndId(inputUrl: String): Pair<String, String> {
        val path = try {
            URI(inputUrl).path.trim('/')
        } catch (_: Exception) {
            inputUrl.removePrefix(mainUrl).trim('/')
        }
        val segments = path.split('/').filter { it.isNotBlank() }
        return when {
            segments.size >= 3 && (segments[0].equals("stream", ignoreCase = true) || segments[0].equals("watch", ignoreCase = true)) -> {
                segments[1] to segments[2].substringBefore('?').substringBefore('#')
            }
            segments.size >= 2 -> {
                segments[0] to segments[1].substringBefore('?').substringBefore('#')
            }
            else -> {
                val trimmed = inputUrl.removePrefix(mainUrl).trim('/')
                val p = trimmed.substringBefore('/')
                val sId = trimmed.substringAfter('/').substringBefore('?').substringBefore('#')
                p to sId
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val (providerId, id) = parseProviderAndId(url)

        val detailEvent = if (providerId.isNotBlank() && id.isNotBlank()) {
            try {
                fetchEventDetail(providerId, id)
            } catch (_: Exception) {
                null
            }
        } else null

        val cachedEvent = cachedEvents?.firstOrNull { (providerId.isBlank() || it.providerId == providerId) && it.getId() == id }
        val event = detailEvent ?: cachedEvent ?: CornerEvent(stream_id = id, providerId = providerId)

        if (id.isNotBlank()) {
            eventDetailCache["${providerId}_$id"] = event
            eventDetailCache[id] = event
        }

        val poster = fixUrlNull(event.getPosterUrl())
        val dateMs = event.getTimestampMs()
        val time24 = if (dateMs > 0L) timeFormat24.format(Date(dateMs)) else ""
        val countdown = getCountdownOrStatus(event)
        val fullDate = if (dateMs > 0L) SimpleDateFormat("EEEE, dd MMMM yyyy", Locale("id", "ID")).format(Date(dateMs)) else "TBA"

        val rawStreams = event.streams ?: cachedEvent?.streams ?: emptyList()

        val plotDesc = buildString {
            if (!event.category.isNullOrBlank()) appendLine("Kategori: ${event.category}")
            if (!event.league.isNullOrBlank()) appendLine("Liga: ${event.league}")
            if (dateMs > 0L) appendLine("Tanggal: $fullDate")
            if (time24.isNotBlank()) appendLine("Waktu: $time24 WIB/Lokal")
            appendLine("Status: $countdown")
            if (rawStreams.isNotEmpty()) {
                val serverNames = rawStreams.mapIndexed { idx, st ->
                    st.source_name ?: st.name ?: "Server ${idx + 1}"
                }
                appendLine("Server Tersedia (${rawStreams.size}): ${serverNames.joinToString(", ")}")
            }
        }

        val displayTitle = if (time24.isNotBlank() && countdown != "LIVE") "[$time24] ${event.getDisplayName()}" else event.getDisplayName()

        return newMovieLoadResponse(
            name = displayTitle,
            url = url,
            type = TvType.Live,
            dataUrl = url
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

        var providerId = ""
        var id = ""

        if (cleanData.startsWith("{") && cleanData.endsWith("}")) {
            try {
                val json = JSONObject(cleanData)
                providerId = json.optString("providerId")
                id = json.optString("id").ifBlank { json.optString("stream_id") }
            } catch (_: Exception) {}
        } else if (cleanData.startsWith("detail:")) {
            val parts = cleanData.split(":")
            if (parts.size >= 3) {
                providerId = parts[1]
                id = parts[2]
            }
        } else if (cleanData.contains("streamcorner.st") || cleanData.startsWith("http://") || cleanData.startsWith("https://")) {
            val pair = parseProviderAndId(cleanData)
            providerId = pair.first
            id = pair.second
        }

        val event = (if (id.isNotBlank()) eventDetailCache["${providerId}_$id"] ?: eventDetailCache[id] else null)
            ?: (if (providerId.isNotBlank() && id.isNotBlank()) {
                try {
                    fetchEventDetail(providerId, id)
                } catch (_: Exception) {
                    null
                }
            } else null)
            ?: cachedEvents?.firstOrNull { (providerId.isBlank() || it.providerId == providerId) && it.getId() == id }

        val rawStreams = event?.streams ?: emptyList()

        if (rawStreams.isNotEmpty()) {
            var anyEmitted = false
            rawStreams.amap { st ->
                val sName = st.source_name?.trim()?.takeIf { it.isNotBlank() }
                    ?: st.name?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Stream"
                val streamUrl = st.stream_url?.trim().orEmpty()
                val embedUrl = st.embed_url?.trim().orEmpty()

                // 1. Direct stream_url (.m3u8 / .mpd / direct video)
                if (streamUrl.isNotBlank()) {
                    if (streamUrl.contains(".m3u8")) {
                        val headersMap = mapOf(
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                        var generated = false
                        try {
                            val list = M3u8Helper.generateM3u8(
                                source = sName,
                                streamUrl = streamUrl,
                                referer = "$mainUrl/",
                                headers = headersMap
                            )
                            if (list.isNotEmpty()) {
                                list.forEach(callback)
                                generated = true
                                anyEmitted = true
                            }
                        } catch (_: Exception) {}

                        if (!generated) {
                            callback(
                                newExtractorLink(
                                    name = "$sName Live HLS",
                                    source = sName,
                                    url = streamUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = headersMap
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            anyEmitted = true
                        }
                    } else if (streamUrl.contains(".mpd")) {
                        val keys = st.stream_keys?.trim().orEmpty()
                        val (rawKid, rawKey) = if (keys.contains(":")) {
                            keys.substringBefore(":").trim() to keys.substringAfter(":").trim()
                        } else {
                            "" to ""
                        }

                        if (rawKid.isNotBlank() && rawKey.isNotBlank()) {
                            val b64Kid = StreamCornerCipher.toClearKeyB64(rawKid)
                            val b64Key = StreamCornerCipher.toClearKeyB64(rawKey)
                            callback(
                                newDrmExtractorLink(
                                    source = sName,
                                    name = "$sName Live DASH",
                                    url = streamUrl,
                                    type = ExtractorLinkType.DASH,
                                    uuid = CLEARKEY_UUID
                                ) {
                                    this.kid = b64Kid
                                    this.key = b64Key
                                    this.kty = "oct"
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "Origin" to mainUrl,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                    )
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            anyEmitted = true
                        } else {
                            callback(
                                newExtractorLink(
                                    name = "$sName Live DASH",
                                    source = sName,
                                    url = streamUrl,
                                    type = ExtractorLinkType.DASH
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "Origin" to mainUrl,
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                                    )
                                    this.quality = Qualities.P1080.value
                                }
                            )
                            anyEmitted = true
                        }
                    } else {
                        callback(
                            newExtractorLink(
                                name = "$sName Live",
                                source = sName,
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P1080.value
                            }
                        )
                        anyEmitted = true
                    }
                }

                // 2. Ekstraksi embed_url jika streamUrl kosong atau embed bukan sekedar mirror pandecocogaming
                if (embedUrl.isNotBlank() && (streamUrl.isBlank() || !embedUrl.contains("pandecocogaming.sbs"))) {
                    try {
                        if (extractor.extractStream(
                                url = embedUrl,
                                referer = "$mainUrl/",
                                subtitleCallback = subtitleCallback,
                                callback = callback,
                                customSource = sName
                            )) {
                            anyEmitted = true
                        }
                    } catch (_: Exception) {}
                }
            }
            if (anyEmitted) return true
        }

        // 3. Fallback umum ke StreamCornerExtractor
        // If data is JSON, fallback by constructing the stream URL so WebViewResolver can handle it
        val targetUrl = if (cleanData.startsWith("{") || cleanData.startsWith("detail:")) {
            "$mainUrl/stream/$providerId/$id"
        } else {
            cleanData
        }
        return extractor.extractStream(targetUrl, "$mainUrl/", subtitleCallback, callback)
    }
}
