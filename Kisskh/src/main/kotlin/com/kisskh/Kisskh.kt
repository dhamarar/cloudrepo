package com.kisskh

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class Kisskh : MainAPI() {
    override var mainUrl = "https://kisskh.co"
    override var name = "Kisskh"
    override val hasMainPage = true
    override var lang = "id"

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "api/DramaList/List?page=%d&type=0&sub=3&country=0&status=0&order=2" to "Sedang Hangat (Sub Indo)",
        "api/DramaList/List?page=%d&type=1&sub=3&country=0&status=0&order=2" to "TV Series",
        "api/DramaList/List?page=%d&type=2&sub=3&country=0&status=0&order=2" to "Movies",
        "api/DramaList/List?page=%d&type=3&sub=3&country=0&status=0&order=2" to "Anime",
        "api/DramaList/List?page=%d&type=4&sub=3&country=0&status=0&order=2" to "Hollywood"
    )

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data.format(page)}"
        val response = app.get(
            url,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).text

        val listResp = tryParseJson<DramaListResponse>(response)
        val items = listResp?.data?.mapNotNull { it.toSearchResult() } ?: emptyList()
        val hasMore = listResp?.totalCount?.let { total ->
            val size = listResp.pageSize ?: 10
            page * size < total
        } ?: true

        return newHomePageResponse(request.name, items, hasNext = hasMore)
    }

    private fun DramaItem.toSearchResult(): SearchResponse? {
        val dramaId = id ?: return null
        val dramaTitle = title?.trim() ?: return null
        val href = "$mainUrl/Drama/$dramaId"
        val poster = fixUrlNull(thumbnail)
        val isSeries = (episodesCount ?: 0) > 1

        return if (isSeries) {
            newTvSeriesSearchResponse(dramaTitle, href, TvType.AsianDrama) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(dramaTitle, href, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = query.trim().replace(" ", "+")
        val searchUrl = "$mainUrl/api/DramaList/Search?q=$encodedQuery"
        val response = app.get(
            searchUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).text

        val items = tryParseJson<List<DramaItem>>(response) ?: return emptyList()
        return items.mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val dramaId = Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: Regex("/(?:Drama|Anime)/(\\d+)(?:/|$)").find(url)?.groupValues?.get(1)?.toLongOrNull()
            ?: url.substringAfterLast("/").substringBefore("?").toLongOrNull()
            ?: throw ErrorLoadingException("ID drama tidak ditemukan dari URL: $url")

        val detailUrl = "$mainUrl/api/DramaList/Drama/$dramaId?isq=false"
        val response = app.get(
            detailUrl,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to "$mainUrl/"
            )
        ).text

        val drama = tryParseJson<DramaDetail>(response)
            ?: throw ErrorLoadingException("Gagal memuat detail drama $dramaId")

        val title = drama.title?.trim().orEmpty().ifEmpty { "Kisskh Drama" }
        val poster = fixUrlNull(drama.thumbnail)
        val plot = drama.description?.trim()
        val year = drama.releaseDate?.take(4)?.toIntOrNull()
        val status = when (drama.status?.lowercase()) {
            "completed" -> ShowStatus.Completed
            "ongoing" -> ShowStatus.Ongoing
            else -> null
        }

        val rawEpisodes = drama.episodes ?: emptyList()
        val isMovie = drama.type?.equals("Movie", ignoreCase = true) == true ||
                ((drama.episodesCount ?: 0) <= 1 && rawEpisodes.size <= 1)

        return if (isMovie) {
            val firstEp = rawEpisodes.firstOrNull()
            val epData = if (firstEp?.id != null) {
                KisskhEpisodeData(
                    dramaId = dramaId,
                    episodeId = firstEp.id,
                    number = firstEp.number ?: 1.0
                ).toJson()
            } else {
                url
            }

            newMovieLoadResponse(title, url, TvType.Movie, epData) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            val sortedEpisodes = rawEpisodes.sortedBy { it.number ?: 0.0 }
            val episodes = sortedEpisodes.mapNotNull { ep ->
                val epId = ep.id ?: return@mapNotNull null
                val epNum = ep.number ?: 1.0
                val epData = KisskhEpisodeData(
                    dramaId = dramaId,
                    episodeId = epId,
                    number = epNum
                ).toJson()

                newEpisode(epData) {
                    val formattedNum = if (epNum % 1.0 == 0.0) epNum.toInt().toString() else epNum.toString()
                    this.name = "Episode $formattedNum"
                    this.episode = epNum.toInt()
                    this.posterUrl = poster
                }
            }

            val tvType = if (drama.type?.equals("Anime", ignoreCase = true) == true) {
                TvType.Anime
            } else {
                TvType.AsianDrama
            }

            newTvSeriesLoadResponse(title, url, tvType, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.showStatus = status
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = tryParseJson<KisskhEpisodeData>(data)
        val episodeId = epData?.episodeId
            ?: Regex("[?&]ep=(\\d+)").find(data)?.groupValues?.get(1)?.toLongOrNull()
            ?: data.toLongOrNull()
            ?: return false

        val videoKkey = KisskhHelper.getKkey(episodeId, isSub = false)
        val subKkey = KisskhHelper.getKkey(episodeId, isSub = true)

        if (!videoKkey.isNullOrBlank()) {
            try {
                val streamApiUrl =
                    "$mainUrl/api/DramaList/Episode/$episodeId.png?err=false&ts=null&time=null&kkey=$videoKkey"
                val streamRes = app.get(
                    streamApiUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/",
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text

                val videoObj = tryParseJson<VideoResponse>(streamRes)
                val videoUrl = videoObj?.Video

                if (!videoUrl.isNullOrBlank()) {
                    if (videoUrl.contains(".m3u8")) {
                        val m3u8Links = try {
                            M3u8Helper.generateM3u8(
                                source = name,
                                streamUrl = videoUrl,
                                referer = "$mainUrl/",
                                headers = mapOf("Referer" to "$mainUrl/")
                            )
                        } catch (_: Throwable) {
                            emptyList()
                        }

                        if (m3u8Links.isNotEmpty()) {
                            m3u8Links.forEach(callback)
                        } else {
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name - Auto",
                                    url = videoUrl,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.quality = Qualities.Unknown.value
                                }
                            )
                        }
                    } else {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = name,
                                url = videoUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }

                val thirdParty = videoObj?.ThirdParty
                if (!thirdParty.isNullOrBlank()) {
                    loadExtractor(thirdParty, referer = "$mainUrl/", subtitleCallback, callback)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        if (!subKkey.isNullOrBlank()) {
            try {
                val subApiUrl = "$mainUrl/api/Sub/$episodeId?kkey=$subKkey"
                val subRes = app.get(
                    subApiUrl,
                    headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Referer" to "$mainUrl/",
                        "Accept" to "application/json, text/plain, */*"
                    )
                ).text

                val subs = tryParseJson<List<SubtitleItem>>(subRes)
                subs?.forEach { sub ->
                    val src = sub.src
                    if (!src.isNullOrBlank()) {
                        val label = sub.label?.trim()?.ifEmpty { null }
                            ?: sub.land?.trim()?.ifEmpty { null }
                            ?: "Subtitle"
                        subtitleCallback(
                            SubtitleFile(
                                lang = label,
                                url = src
                            )
                        )
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        return true
    }
}
