package com.leakgallery

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class LeakGallery : MainAPI() {
    override var mainUrl = "https://leakgallery.com"
    override var name = "LeakGallery"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val apiUrl = "https://api.leakgallery.com"
    private val cdnUrl = "https://cdn.leakgallery.com"
    private val jsonMapper = jacksonObjectMapper()

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "popular/media/Last-Hour/%d?fake=false" to "Newest",
        "popular/media/All-Time/%d?fake=false" to "Trending All Time",
        "popular/media/Week/%d?fake=false" to "Trending This Week",
        "tag/curvy/%d" to "Curvy",
        "tag/lingerie/%d" to "Lingerie",
        "tag/nude/%d" to "Nude",
        "tag/tattooed/%d" to "Tattooed",
        "tag/long-hair/%d" to "Long Hair",
        "tag/big-ass/%d" to "Big Ass",
        "tag/big-boobs/%d" to "Big Boobs",
        "tag/blonde/%d" to "Blonde",
        "tag/brunette/%d" to "Brunette",
        "tag/topless/%d" to "Topless",
        "tag/black-hair/%d" to "Black Hair",
        "tag/slim/%d" to "Slim",
        "tag/white/%d" to "White",
        "tag/natural-boobs/%d" to "Natural Boobs",
        "tag/teasing/%d" to "Teasing",
        "tag/pierced/%d" to "Pierced",
        "tag/masturbation/%d" to "Masturbation",
        "tag/mirror/%d" to "Mirror",
        "tag/behind-the-scenes/%d" to "Behind The Scenes",
        "tag/shaved/%d" to "Shaved",
        "tag/stockings/%d" to "Stockings",
        "tag/couple/%d" to "Couple",
        "tag/thick/%d" to "Thick",
        "tag/fake-boobs/%d" to "Fake Boobs",
        "tag/outdoor/%d" to "Outdoor",
        "tag/doggystyle/%d" to "Doggystyle",
        "tag/cosplay/%d" to "Cosplay",
        "tag/bikini/%d" to "Bikini",
        "tag/hairy/%d" to "Hairy",
        "tag/blowjob/%d" to "Blowjob",
        "tag/redhead/%d" to "Redhead",
        "tag/professional/%d" to "Professional",
        "tag/shower/%d" to "Shower",
        "tag/asian/%d" to "Asian",
        "tag/bathroom/%d" to "Bathroom",
        "tag/toys/%d" to "Toys",
        "tag/indian/%d" to "Indian",
        "tag/short-hair/%d" to "Short Hair",
        "tag/twerking/%d" to "Twerking",
        "tag/lesbian/%d" to "Lesbian",
        "tag/night/%d" to "Night",
        "tag/heels/%d" to "Heels",
        "tag/fishnet/%d" to "Fishnet",
        "tag/pov/%d" to "POV",
        "tag/hotel/%d" to "Hotel",
        "tag/kitchen/%d" to "Kitchen",
        "tag/petite/%d" to "Petite",
        "tag/dildo/%d" to "Dildo",
        "tag/latex/%d" to "Latex",
        "tag/ebony/%d" to "Ebony",
        "tag/car/%d" to "Car",
        "tag/dress/%d" to "Dress",
        "tag/dancing/%d" to "Dancing",
        "tag/livestream/%d" to "Livestream",
        "tag/yoga-pants/%d" to "Yoga Pants",
        "tag/handjob/%d" to "Handjob",
        "tag/latina/%d" to "Latina",
        "tag/fingering/%d" to "Fingering",
        "tag/nurse/%d" to "Nurse",
        "tag/pool/%d" to "Pool",
        "tag/bathtub/%d" to "Bathtub",
        "tag/skirt/%d" to "Skirt",
        "tag/wet/%d" to "Wet",
        "tag/roleplay/%d" to "Roleplay",
        "tag/athletic/%d" to "Athletic",
        "tag/balcony/%d" to "Balcony",
        "tag/maid/%d" to "Maid",
        "tag/group-sex/%d" to "Group Sex",
        "tag/gym/%d" to "Gym",
        "tag/teen/%d" to "Teen",
        "tag/uniform/%d" to "Uniform",
        "tag/beach/%d" to "Beach",
        "tag/footjob/%d" to "Footjob",
        "tag/facial/%d" to "Facial",
        "tag/bbw/%d" to "BBW",
        "tag/threesome/%d" to "Threesome",
        "tag/deepthroat/%d" to "Deepthroat",
        "tag/small-boobs/%d" to "Small Boobs",
        "tag/office/%d" to "Office",
        "tag/titjob/%d" to "Titjob",
        "tag/asmr/%d" to "ASMR",
        "tag/try-on/%d" to "Try On",
        "tag/anal/%d" to "Anal",
        "tag/creampie/%d" to "Creampie",
        "tag/cowgirl/%d" to "Cowgirl",
        "tag/squirting/%d" to "Squirting",
        "tag/joi/%d" to "JOI",
        "tag/public/%d" to "Public",
        "tag/orgy/%d" to "Orgy",
        "tag/striptease/%d" to "Striptease",
        "tag/arab/%d" to "Arab",
        "tag/69/%d" to "69",
        "tag/cumshot/%d" to "Cumshot"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$apiUrl/${request.data.format(page)}"
        val response = app.get(url, headers = defaultHeaders).text
        val items = try {
            if (request.data.startsWith("tag/")) {
                jsonMapper.readValue<TagDetailResponse>(response).items ?: emptyList()
            } else {
                jsonMapper.readValue<List<MediaItem>>(response)
            }
        } catch (_: Exception) {
            emptyList()
        }

        val homeItems = items.filter { it.is_video != false }.mapNotNull { item ->
            val id = item.id ?: return@mapNotNull null
            val username = item.profile?.username ?: "user"
            val title = item.caption_title?.trim().takeUnless { it.isNullOrBlank() } ?: "Video #$id"
            val poster = item.thumbnail_path?.let { fixUrlNull("$cdnUrl/$it") }
            val itemUrl = "$mainUrl/$username/$id"

            newMovieSearchResponse(title, itemUrl, TvType.NSFW) {
                this.posterUrl = poster
                this.posterHeaders = defaultHeaders
            }
        }

        return newHomePageResponse(
            HomePageList(
                name = request.name,
                list = homeItems,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        // Hanya panggil API search saat page == 1 karena endpoint POST /search mengembalikan semua profil yang cocok
        if (page > 1) return newSearchResponseList(emptyList(), hasNext = false)

        val cleanQuery = query.trim()
        val searchJson = jsonMapper.writeValueAsString(mapOf("search" to cleanQuery))
        val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
        val requestBody = searchJson.toRequestBody(mediaType)
        val response = app.post(
            "$apiUrl/search",
            headers = defaultHeaders,
            requestBody = requestBody
        ).text

        val profiles = try {
            jsonMapper.readValue<List<ProfileSearchResult>>(response)
        } catch (_: Exception) {
            emptyList()
        }

        val results = profiles.mapNotNull { prof ->
            val username = prof.username?.trim() ?: return@mapNotNull null
            val url = "$mainUrl/$username/Videos"
            val poster = prof.profile_pic?.let { fixUrlNull(it) }

            newTvSeriesSearchResponse(username, url, TvType.NSFW) {
                this.posterUrl = poster
                this.posterHeaders = defaultHeaders
            }
        }

        return newSearchResponseList(results, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1).items
    }

    override suspend fun load(url: String): LoadResponse {
        // Penanganan URL Tag
        if (url.contains("/tag/")) {
            val tagSlug = url.substringAfter("/tag/").substringBefore("/").substringBefore("?").trim()
            val responseText = app.get("$apiUrl/tag/$tagSlug/1", headers = defaultHeaders).text
            val tagData = jsonMapper.readValue<TagDetailResponse>(responseText)
            val allTagItems = mutableListOf<MediaItem>()
            tagData.items?.let { allTagItems.addAll(it) }

            // Ambil halaman berikutnya secara paralel jika ada banyak item
            if (allTagItems.size >= 40) {
                val additionalPages = coroutineScope {
                    (2..10).map { page ->
                        async {
                            try {
                                val nextPageText = app.get("$apiUrl/tag/$tagSlug/$page", headers = defaultHeaders).text
                                jsonMapper.readValue<TagDetailResponse>(nextPageText).items ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                for (pageList in additionalPages) {
                    if (pageList.isNotEmpty()) {
                        allTagItems.addAll(pageList)
                    }
                }
            }

            val perSeason = 50
            val episodes = allTagItems.filter { it.is_video != false }.mapIndexedNotNull { index, media ->
                val mediaId = media.id ?: return@mapIndexedNotNull null
                val title = media.caption_title?.trim().takeUnless { it.isNullOrBlank() } ?: "Video #${index + 1}"
                val poster = media.thumbnail_path?.let { fixUrlNull("$cdnUrl/$it") }
                val streamUrl = media.file_path?.let { "$cdnUrl/$it" } ?: "$mainUrl/${media.profile?.username ?: "user"}/$mediaId"

                val seasonNum = (index / perSeason) + 1
                val episodeNum = (index % perSeason) + 1

                newEpisode(streamUrl) {
                    this.name = title
                    this.season = seasonNum
                    this.episode = episodeNum
                    this.posterUrl = poster
                    media.duration?.let { dur ->
                        this.description = "Duration: ${dur}s"
                    }
                }
            }

            val maxSeason = if (episodes.isEmpty()) 1 else (episodes.size - 1) / perSeason + 1
            val seasonNamesList = (1..maxSeason).map { s ->
                val start = (s - 1) * perSeason + 1
                val end = minOf(s * perSeason, episodes.size)
                SeasonData(s, "Season $s ($start-$end)")
            }

            val tagTitle = tagSlug.replace("-", " ").split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }

            return newTvSeriesLoadResponse(tagTitle, url, TvType.NSFW, episodes) {
                this.posterHeaders = defaultHeaders
                this.seasonNames = seasonNamesList
                this.plot = "Tag: $tagTitle (${tagData.media_count ?: episodes.size} videos)"
            }
        }

        val isProfileUrl = url.contains("/Videos", ignoreCase = true) ||
                url.contains("/Photos", ignoreCase = true) ||
                url.contains("/All", ignoreCase = true) ||
                (!url.matches(Regex(""".*-\d+$|.*/\d+$""")) && !url.contains("/media/"))

        if (isProfileUrl) {
            val username = url.removeSuffix("/")
                .substringBefore("/Videos")
                .substringBefore("/Photos")
                .substringBefore("/All")
                .substringAfterLast("/")
                .trim()

            val firstPageText = app.get(
                "$apiUrl/profile/$username?type=Videos&sort=MostRecent&fake=false",
                headers = defaultHeaders
            ).text

            val firstPage = jsonMapper.readValue<ProfileDetailResponse>(firstPageText)
            val allMedias = mutableListOf<MediaItem>()
            firstPage.medias?.let { allMedias.addAll(it) }

            // Ambil hingga 25 halaman (maksimal ~1.000 video) secara paralel menggunakan coroutines
            if ((firstPage.medias?.size ?: 0) >= 40) {
                val additionalPages = coroutineScope {
                    (2..25).map { page ->
                        async {
                            try {
                                val nextPageText = app.get(
                                    "$apiUrl/profile/$username/$page?type=Videos&sort=MostRecent&fake=false",
                                    headers = defaultHeaders
                                ).text
                                jsonMapper.readValue<ProfileDetailNextPage>(nextPageText).medias ?: emptyList()
                            } catch (_: Exception) {
                                emptyList()
                            }
                        }
                    }.awaitAll()
                }
                for (pageList in additionalPages) {
                    if (pageList.isNotEmpty()) {
                        allMedias.addAll(pageList)
                    }
                }
            }

            // Kelompokkan 50 video per season untuk navigasi Season dan Next Episode di player
            val perSeason = 50
            val episodes = allMedias.filter { it.is_video != false }.mapIndexedNotNull { index, media ->
                val mediaId = media.id ?: return@mapIndexedNotNull null
                val title = media.caption_title?.trim().takeUnless { it.isNullOrBlank() } ?: "Video #${index + 1}"
                val poster = media.thumbnail_path?.let { fixUrlNull("$cdnUrl/$it") }
                val streamUrl = media.file_path?.let { "$cdnUrl/$it" } ?: "$mainUrl/$username/$mediaId"

                val seasonNum = (index / perSeason) + 1
                val episodeNum = (index % perSeason) + 1

                newEpisode(streamUrl) {
                    this.name = title
                    this.season = seasonNum
                    this.episode = episodeNum
                    this.posterUrl = poster
                    media.duration?.let { dur ->
                        this.description = "Duration: ${dur}s"
                    }
                }
            }

            val maxSeason = if (episodes.isEmpty()) 1 else (episodes.size - 1) / perSeason + 1
            val seasonNamesList = (1..maxSeason).map { s ->
                val start = (s - 1) * perSeason + 1
                val end = minOf(s * perSeason, episodes.size)
                SeasonData(s, "Season $s ($start-$end)")
            }

            val prof = firstPage.profile
            val title = prof?.username ?: username
            val poster = prof?.profile_pic?.let { fixUrlNull(it) }
            val banner = prof?.banner_pic?.let { fixUrlNull(it) }

            return newTvSeriesLoadResponse(title, url, TvType.NSFW, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = banner
                this.posterHeaders = defaultHeaders
                this.seasonNames = seasonNamesList
                this.plot = "Total videos: ${episodes.size}${prof?.subscribers?.let { ", Subscribers: $it" } ?: ""}"
            }
        }

        // Penanganan Video Tunggal (MovieLoadResponse)
        val mediaId = Regex("""(?:-|\/)(\d+)(?:[/?#]|$)""").find(url)?.groupValues?.get(1)?.toLongOrNull()
        if (mediaId != null) {
            try {
                val mediaText = app.get("$apiUrl/media/$mediaId", headers = defaultHeaders).text
                val media = jsonMapper.readValue<MediaDetail>(mediaText)
                val title = media.caption_title?.trim().takeUnless { it.isNullOrBlank() } ?: "Video #$mediaId"
                val streamUrl = media.file_path?.let { "$cdnUrl/$it" } ?: url
                val poster = media.thumbnail_path?.let { fixUrlNull("$cdnUrl/$it") }

                val recs = media.suggested_media?.filter { it.is_video != false }?.mapNotNull { sm ->
                    val smId = sm.id ?: return@mapNotNull null
                    val smTitle = sm.caption_title?.trim().takeUnless { it.isNullOrBlank() } ?: "Video #$smId"
                    val smPoster = sm.thumbnail_path?.let { fixUrlNull("$cdnUrl/$it") }
                    newMovieSearchResponse(smTitle, "$mainUrl/${media.profile?.username ?: "user"}/$smId", TvType.NSFW) {
                        this.posterUrl = smPoster
                        this.posterHeaders = defaultHeaders
                    }
                }

                return newMovieLoadResponse(title, url, TvType.NSFW, streamUrl) {
                    this.posterUrl = poster
                    this.posterHeaders = defaultHeaders
                    this.plot = media.caption_description
                    this.duration = media.duration
                    this.tags = media.tags?.mapNotNull { it.slug }
                    this.recommendations = recs
                    media.profile?.username?.let { author ->
                        addActors(listOf(Actor(author, media.profile.profile_pic)))
                    }
                }
            } catch (_: Exception) {
                // Lanjut ke fallback scraping Jsoup di bawah jika terjadi kegagalan API
            }
        }

        // Fallback Scraping Jsoup untuk Video Tunggal
        val document = app.get(url, headers = defaultHeaders).document
        val rawTitle = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: "Video"
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val plot = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()

        return newMovieLoadResponse(rawTitle, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = defaultHeaders
            this.plot = plot
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Pola 1: Data sudah berupa direct MP4 stream dari CDN
        if (data.contains("cdn.leakgallery.com") && data.endsWith(".mp4")) {
            callback.invoke(
                newExtractorLink(
                    name = this.name,
                    source = this.name,
                    url = data,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.headers = defaultHeaders
                }
            )
            return true
        }

        // Pola 2: Data adalah URL halaman video leakgallery.com
        val mediaId = Regex("""(?:-|\/)(\d+)(?:[/?#]|$)""").find(data)?.groupValues?.get(1)?.toLongOrNull()
        if (mediaId != null) {
            try {
                val mediaText = app.get("$apiUrl/media/$mediaId", headers = defaultHeaders).text
                val media = jsonMapper.readValue<MediaDetail>(mediaText)
                val filePath = media.file_path
                if (!filePath.isNullOrBlank()) {
                    val streamUrl = "$cdnUrl/$filePath"
                    callback.invoke(
                        newExtractorLink(
                            name = this.name,
                            source = this.name,
                            url = streamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = defaultHeaders
                        }
                    )
                    return true
                }
            } catch (_: Exception) {
                // Lanjut ke ekstraksi DOM Jsoup di bawah jika API gagal
            }
        }

        // Pola 3: Ekstraksi tag <video> atau <source> dari DOM Jsoup
        try {
            val doc = app.get(data, headers = defaultHeaders).document
            val videoSrc = doc.selectFirst("video#video-element source, video source")?.attr("src")
                ?: doc.selectFirst("video#video-element")?.attr("src")

            if (!videoSrc.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = fixUrl(videoSrc),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = defaultHeaders
                    }
                )
                return true
            }
        } catch (_: Exception) {}

        return false
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaItem(
    val id: Long? = null,
    val file_path: String? = null,
    val is_video: Boolean? = null,
    val caption_title: String? = null,
    val duration: Int? = null,
    val thumbnail_path: String? = null,
    val profile: ProfileShort? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProfileShort(
    val username: String? = null,
    val profile_pic: String? = null,
    val banner_pic: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProfileDetailResponse(
    val profile: ProfileFull? = null,
    val medias: List<MediaItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProfileDetailNextPage(
    val medias: List<MediaItem>? = null,
    val page: Int? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class TagDetailResponse(
    val slug: String? = null,
    val family: String? = null,
    val media_count: Long? = null,
    val items: List<MediaItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProfileFull(
    val id: Long? = null,
    val username: String? = null,
    val profile_pic: String? = null,
    val banner_pic: String? = null,
    val subscribers: Long? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaDetail(
    val id: Long? = null,
    val caption_title: String? = null,
    val caption_description: String? = null,
    val file_path: String? = null,
    val thumbnail_path: String? = null,
    val is_video: Boolean? = null,
    val duration: Int? = null,
    val profile: ProfileShort? = null,
    val tags: List<MediaTag>? = null,
    val suggested_media: List<MediaItem>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class MediaTag(
    val slug: String? = null,
    val family: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class ProfileSearchResult(
    val id: Long? = null,
    val username: String? = null,
    val profile_pic: String? = null,
    val banner_pic: String? = null,
    val all_time_views: Long? = null
)
