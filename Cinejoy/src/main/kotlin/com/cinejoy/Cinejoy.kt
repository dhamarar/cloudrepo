package com.cinejoy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.json.JSONArray
import java.util.Locale

class Cinejoy : MainAPI() {
    override var mainUrl = "https://cinejoy.to"
    override var name = "Cinejoy"
    override val hasMainPage = true
    override var lang = "en"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    private val tmdbBase = "https://api.themoviedb.org/3"
    private val tmdbKey = "8476a7ab80ad76f0936744df0430e67c"
    private val imageBase = "https://image.tmdb.org/t/p/w500"
    private val backdropBase = "https://image.tmdb.org/t/p/original"

    override val mainPage = mainPageOf(
        "discover/tv?sort_by=popularity.desc&page=%d" to "TV Series",
        "discover/movie?sort_by=popularity.desc&page=%d" to "Movies",
        "discover/movie?with_watch_providers=8&watch_region=US&sort_by=popularity.desc&page=%d" to "Netflix",
        "discover/movie?with_watch_providers=9&watch_region=US&sort_by=popularity.desc&page=%d" to "Amazon Prime Video",
        "discover/movie?with_watch_providers=337&watch_region=US&sort_by=popularity.desc&page=%d" to "Disney+",
        "discover/movie?with_watch_providers=350&watch_region=US&sort_by=popularity.desc&page=%d" to "Apple TV+",
        "discover/movie?with_watch_providers=2&watch_region=US&sort_by=popularity.desc&page=%d" to "Apple TV",
        "discover/movie?with_watch_providers=15&watch_region=US&sort_by=popularity.desc&page=%d" to "Hulu",
        "discover/movie?with_watch_providers=1889&watch_region=US&sort_by=popularity.desc&page=%d" to "HBO Max"
    )

    private fun createSlug(title: String, year: String?): String {
        val cleanTitle = title.lowercase(Locale.ROOT)
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
        return if (!year.isNullOrBlank()) "$cleanTitle-$year" else cleanTitle
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = request.data.format(page)
        val delimiter = if (path.contains("?")) "&" else "?"
        val url = "$tmdbBase/$path${delimiter}api_key=$tmdbKey&include_adult=false"

        val response = app.get(url).text
        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: JSONArray()

        val items = mutableListOf<SearchResponse>()
        val isExplicitTv = request.data.contains("discover/tv")

        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val id = item.optLong("id", -1L)
            if (id <= 0) continue

            val isTv = isExplicitTv || item.has("first_air_date") || !item.has("release_date")
            val title = if (isTv) {
                item.optString("name", item.optString("original_name", "Untitled"))
            } else {
                item.optString("title", item.optString("original_title", "Untitled"))
            }

            val posterPath = item.optString("poster_path")
            val poster = if (posterPath.isNotBlank() && posterPath != "null") "$imageBase$posterPath" else null

            val date = if (isTv) item.optString("first_air_date") else item.optString("release_date")
            val year = date.split("-").firstOrNull()?.takeIf { it.isNotBlank() }
            val slug = createSlug(title, year)

            val voteAvg = item.optDouble("vote_average", 0.0)

            if (isTv) {
                val href = "$mainUrl/series/$id-$slug"
                items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year?.toIntOrNull()
                    if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
                })
            } else {
                val href = "$mainUrl/movie/$id-$slug"
                items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year?.toIntOrNull()
                    if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
                })
            }
        }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim()
        val url = "$tmdbBase/search/multi?query=$encodedQuery&api_key=$tmdbKey&include_adult=false&page=1"

        val response = app.get(url).text
        val json = JSONObject(response)
        val results = json.optJSONArray("results") ?: JSONArray()

        val items = mutableListOf<SearchResponse>()
        for (i in 0 until results.length()) {
            val item = results.optJSONObject(i) ?: continue
            val mediaType = item.optString("media_type")
            if (mediaType != "movie" && mediaType != "tv") continue

            val id = item.optLong("id", -1L)
            if (id <= 0) continue

            val isTv = mediaType == "tv"
            val title = if (isTv) {
                item.optString("name", item.optString("original_name", "Untitled"))
            } else {
                item.optString("title", item.optString("original_title", "Untitled"))
            }

            val posterPath = item.optString("poster_path")
            val poster = if (posterPath.isNotBlank() && posterPath != "null") "$imageBase$posterPath" else null

            val date = if (isTv) item.optString("first_air_date") else item.optString("release_date")
            val year = date.split("-").firstOrNull()?.takeIf { it.isNotBlank() }
            val slug = createSlug(title, year)

            val voteAvg = item.optDouble("vote_average", 0.0)

            if (isTv) {
                val href = "$mainUrl/series/$id-$slug"
                items.add(newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year?.toIntOrNull()
                    if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
                })
            } else {
                val href = "$mainUrl/movie/$id-$slug"
                items.add(newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year?.toIntOrNull()
                    if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
                })
            }
        }

        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val isSeries = url.contains("/series/")
        val idRegex = Regex("""/(?:movie|series)/(\d+)""")
        val id = idRegex.find(url)?.groupValues?.getOrNull(1)
            ?: throw ErrorLoadingException("Invalid content URL: $url")

        if (isSeries) {
            val detailUrl = "$tmdbBase/tv/$id?api_key=$tmdbKey&append_to_response=credits,external_ids,videos"
            val detailRes = app.get(detailUrl).text
            val json = JSONObject(detailRes)

            val title = json.optString("name", json.optString("original_name", "Untitled"))
            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val posterPath = json.optString("poster_path")
            val poster = if (posterPath.isNotBlank() && posterPath != "null") "$imageBase$posterPath" else null
            val backdropPath = json.optString("backdrop_path")
            val backdrop = if (backdropPath.isNotBlank() && backdropPath != "null") "$backdropBase$backdropPath" else null

            val firstAirDate = json.optString("first_air_date")
            val year = firstAirDate.split("-").firstOrNull()?.toIntOrNull()
            val voteAvg = json.optDouble("vote_average", 0.0)

            val genresList = mutableListOf<String>()
            val genresArr = json.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    val g = genresArr.optJSONObject(i)
                    val gName = g?.optString("name")
                    if (!gName.isNullOrBlank()) genresList.add(gName)
                }
            }

            val seasonsArr = json.optJSONArray("seasons") ?: JSONArray()
            val episodesList = mutableListOf<Episode>()

            for (s in 0 until seasonsArr.length()) {
                val seasonObj = seasonsArr.optJSONObject(s) ?: continue
                val seasonNum = seasonObj.optInt("season_number", -1)
                if (seasonNum <= 0) continue

                try {
                    val seasonUrl = "$tmdbBase/tv/$id/season/$seasonNum?api_key=$tmdbKey"
                    val seasonRes = app.get(seasonUrl).text
                    val seasonJson = JSONObject(seasonRes)
                    val epsArr = seasonJson.optJSONArray("episodes") ?: JSONArray()

                    for (e in 0 until epsArr.length()) {
                        val epObj = epsArr.optJSONObject(e) ?: continue
                        val epNum = epObj.optInt("episode_number", e + 1)
                        val epName = epObj.optString("name", "Episode $epNum")
                        val epStill = epObj.optString("still_path")
                        val epPoster = if (epStill.isNotBlank() && epStill != "null") "$imageBase$epStill" else poster
                        val epPlot = epObj.optString("overview").takeIf { it.isNotBlank() }
                        val epRating = epObj.optDouble("vote_average", 0.0)

                        val watchUrl = "$mainUrl/watch/tv/$id/$seasonNum/$epNum"
                        val episode = newEpisode(watchUrl) {
                            this.name = epName
                            this.season = seasonNum
                            this.episode = epNum
                            this.posterUrl = epPoster
                            this.description = epPlot
                            if (epRating > 0.0) this.score = Score.from10(epRating)
                        }
                        episodesList.add(episode)
                    }
                } catch (_: Exception) {
                    val epCount = seasonObj.optInt("episode_count", 0)
                    for (e in 1..epCount) {
                        val watchUrl = "$mainUrl/watch/tv/$id/$seasonNum/$e"
                        episodesList.add(newEpisode(watchUrl) {
                            this.name = "Episode $e"
                            this.season = seasonNum
                            this.episode = e
                            this.posterUrl = poster
                        })
                    }
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodesList) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.tags = genresList
                if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
            }
        } else {
            val detailUrl = "$tmdbBase/movie/$id?api_key=$tmdbKey&append_to_response=credits,external_ids,videos"
            val detailRes = app.get(detailUrl).text
            val json = JSONObject(detailRes)

            val title = json.optString("title", json.optString("original_title", "Untitled"))
            val overview = json.optString("overview").takeIf { it.isNotBlank() }
            val posterPath = json.optString("poster_path")
            val poster = if (posterPath.isNotBlank() && posterPath != "null") "$imageBase$posterPath" else null
            val backdropPath = json.optString("backdrop_path")
            val backdrop = if (backdropPath.isNotBlank() && backdropPath != "null") "$backdropBase$backdropPath" else null

            val releaseDate = json.optString("release_date")
            val year = releaseDate.split("-").firstOrNull()?.toIntOrNull()
            val voteAvg = json.optDouble("vote_average", 0.0)
            val duration = json.optInt("runtime", 0).takeIf { it > 0 }

            val genresList = mutableListOf<String>()
            val genresArr = json.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    val g = genresArr.optJSONObject(i)
                    val gName = g?.optString("name")
                    if (!gName.isNullOrBlank()) genresList.add(gName)
                }
            }

            val watchUrl = "$mainUrl/watch/movie/$id"

            return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = overview
                this.duration = duration
                this.tags = genresList
                if (voteAvg > 0.0) this.score = Score.from10(voteAvg)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val isTv = data.contains("/watch/tv/")
        var tmdbId = ""
        var season = 1
        var episode = 1

        if (isTv) {
            val segments = data.substringAfter("/watch/tv/").split("/")
            tmdbId = segments.getOrNull(0).orEmpty()
            season = segments.getOrNull(1)?.toIntOrNull() ?: 1
            episode = segments.getOrNull(2)?.toIntOrNull() ?: 1
        } else {
            tmdbId = data.substringAfter("/watch/movie/").substringBefore("/").substringBefore("?")
        }

        if (tmdbId.isBlank()) return false

        // 1. Ekstraksi Native Cinejoy Servers via CinejoyExtractor (Lisbon, Nebula, Solara, Canaias, dll.)
        val servers = listOf("lisbon", "nebula", "solara", "canaias", "athens", "joy", "castle", "sakura")
        servers.amap { server ->
            try {
                val json = CinejoyExtractor.queryServer(server, isTv, tmdbId, season, episode) ?: return@amap
                val dataObj = json.optJSONObject("data") ?: return@amap
                val streamArr = dataObj.optJSONArray("stream") ?: JSONArray()

                for (i in 0 until streamArr.length()) {
                    val streamObj = streamArr.optJSONObject(i) ?: continue
                    val type = streamObj.optString("type")
                    val id = streamObj.optString("id", server)
                    val playlist = streamObj.optString("playlist")

                    // Subtitle / Captions bawaan server
                    val captions = streamObj.optJSONArray("captions") ?: JSONArray()
                    for (c in 0 until captions.length()) {
                        val cap = captions.optJSONObject(c) ?: continue
                        val subUrl = cap.optString("url")
                        val subLang = cap.optString("language", cap.optString("id", "en"))
                        if (subUrl.isNotBlank()) {
                            subtitleCallback(
                                newSubtitleFile(
                                    subLang,
                                    fixUrl(subUrl)
                                )
                            )
                        }
                    }

                    if (type == "hls" && playlist.isNotBlank()) {
                        val streamHeaders = mapOf(
                            "Origin" to "https://cinejoy.to",
                            "Referer" to "https://cinejoy.to/",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                        M3u8Helper.generateM3u8(
                            source = "Cinejoy - ${server.replaceFirstChar { it.uppercase() }}",
                            streamUrl = playlist,
                            referer = "https://cinejoy.to/",
                            headers = streamHeaders
                        ).forEach(callback)
                    } else if (type == "file") {
                        val qualities = streamObj.optJSONObject("qualities")
                        if (qualities != null) {
                            val keys = qualities.keys()
                            while (keys.hasNext()) {
                                val qKey = keys.next()
                                val qObj = qualities.optJSONObject(qKey)
                                val qUrl = qObj?.optString("url")
                                if (!qUrl.isNullOrBlank() && qUrl.startsWith("http")) {
                                    callback(
                                        newExtractorLink(
                                            source = "Cinejoy - ${server.replaceFirstChar { it.uppercase() }}",
                                            name = "Cinejoy - ${server.replaceFirstChar { it.uppercase() }} $qKey",
                                            url = qUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.quality = getQualityFromName(qKey)
                                            this.referer = "https://cinejoy.to/"
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // 2. Ekstraksi Subtitle via Stremio OpenSubtitles v3 (Fallback/Tambahan)
        try {
            val extUrl = "$tmdbBase/${if (isTv) "tv" else "movie"}/$tmdbId/external_ids?api_key=$tmdbKey"
            val extRes = app.get(extUrl).text
            val extJson = JSONObject(extRes)
            val imdbId = extJson.optString("imdb_id").takeIf { it.isNotBlank() && it != "null" }

            if (imdbId != null) {
                val subUrl = if (isTv) {
                    "https://opensubtitles-v3.strem.io/subtitles/series/$imdbId:$season:$episode.json"
                } else {
                    "https://opensubtitles-v3.strem.io/subtitles/movie/$imdbId.json"
                }

                val subRes = app.get(subUrl, headers = mapOf("User-Agent" to "Mozilla/5.0")).text
                val subJson = JSONObject(subRes)
                val subsArr = subJson.optJSONArray("subtitles") ?: JSONArray()

                for (s in 0 until subsArr.length()) {
                    val subObj = subsArr.optJSONObject(s) ?: continue
                    val subLink = subObj.optString("url")
                    val subLang = subObj.optString("lang", "en")
                    if (subLink.isNotBlank()) {
                        subtitleCallback(
                            newSubtitleFile(
                                subLang,
                                subLink
                            )
                        )
                    }
                }
            }
        } catch (_: Exception) {}

        return true
    }

}
