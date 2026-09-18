package com.byayzen

import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import java.math.BigInteger

class EPorner : MainAPI() {
    override var mainUrl = "https://www.eporner.com"
    override var name = "EPorner"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val username get() = EPornerSettings.getUsername(EPornerPlugin.appContext)
    private val password get() = EPornerSettings.getPassword(EPornerPlugin.appContext)

    override val mainPage
        get() = mainPageOf(
            *(try {
                EPornerAyarlar.getOrderedAndEnabledCategories().map { (path, name) ->
                    fixUrl(path) to name
                }.toTypedArray()
            } catch (e: Exception) {
                arrayOf(
                    "$mainUrl/" to "Most recent",
                    "$mainUrl/most-viewed/" to "Most viewed",
                    "$mainUrl/top-rated/" to "Top rated",
                    "$mainUrl/longest/" to "Longest"
                )
            })
        )

    private suspend fun ensureAuth(): String {
        val saved = getEpornerCookie()
        if (saved.isNotEmpty() && saved.contains("PHPSESSID=")) return saved
        return epornerLogin(username, password, forceRefresh = false)
    }

    private suspend fun authedGet(url: String, headers: Map<String, String> = emptyMap()): NiceResponse {
        val cookies = ensureAuth()
        val reqHeaders = if (cookies.isNotEmpty()) {
            headers + mapOf("Cookie" to cookies)
        } else {
            headers
        }
        return app.get(url, headers = reqHeaders)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val target = fixUrl(request.data)
        val url = if (page <= 1) target else "${target.removeSuffix("/")}/$page/"
        val home = authedGet(url).document
            .select("div#vidresults div.mb, div#content div.mb")
            .mapNotNull { it.toSearchResult() }
        return newHomePageResponse(HomePageList(request.name, home, true), true)
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val formattedQuery = query.replace(" ", "-")
        val url = if (page <= 1) "$mainUrl/search/$formattedQuery/" else "$mainUrl/search/$formattedQuery/$page/"
        val results = authedGet(url).document.select("div#vidresults div.mb").mapNotNull { it.toSearchResult() }
        return newSearchResponseList(results, true)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("p.mbtit a") ?: return null
        val img = this.selectFirst("div.mbimg img")

        val poster = fixUrlNull(
            img?.attr("data-src")?.takeIf { it.isNotEmpty() && !it.startsWith("data:") }
                ?: img?.attr("src")
        )

        return newMovieSearchResponse(
            titleElement.text(),
            fixUrl(titleElement.attr("href")),
            TvType.NSFW
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = authedGet(url).document
        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("video#EPvideo")?.attr("poster")
        )
        val tags = document.select("div#video-info-tags ul li.vit-category a").map { it.text() }
        val year = document.selectFirst("span.C a")?.text()?.trim()?.toIntOrNull()
        val duration = document.selectFirst("span.vid-length")?.text()?.replace("min", "")?.trim()
            ?.toIntOrNull()
        val description =
            document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val recommendations =
            document.select("div#relateddiv div.mb").mapNotNull { it.toRecommendationResult() }
        val actors = document.select("span.valor a").map { Actor(it.text()) }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.tags = tags
            this.duration = duration
            this.recommendations = recommendations
            addActors(actors)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val titleElement = this.selectFirst("p.mbtit a") ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("div.mbimg img")?.attr("data-src") ?: this.selectFirst("div.mbimg img")
                ?.attr("src")
        )
        return newMovieSearchResponse(
            titleElement.text(),
            fixUrl(titleElement.attr("href")),
            TvType.NSFW
        ) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val url = fixUrl(data)
        var videolink = false

        try {
            val vidmatch = """/(?:embed|video)-([a-zA-Z0-9]+)""".toRegex().find(url)
            val vid = vidmatch?.groupValues?.get(1) ?: return false

            val embedurl = "$mainUrl/embed/$vid/"
            val embedhtml = authedGet(embedurl).text

            val md5hash = """EP\.video\.player\.hash\s*=\s*'([^']+)'""".toRegex().find(embedhtml)?.groupValues?.get(1)
            if (md5hash == null) {
                Log.d(name, "hash bulunamadi")
                return false
            }
            val convertedhash = md5hash.chunked(8).map { chunk ->
                BigInteger(chunk, 16).toString(36)
            }.joinToString("")
            Log.d(name, "vid: $vid - hash: $convertedhash")
            val xhrurl = "$mainUrl/xhr/video/$vid?hash=$convertedhash&domain=www.eporner.com&pixelRatio=1&playerWidth=0&playerHeight=0&fallback=false&embed=true&supportedFormats=hls,dash,h265,vp9,av1,mp4&_=${System.currentTimeMillis()}"

            val cookies = getEpornerCookie()
            val xhrHeaders = mutableMapOf(
                "Referer" to embedurl,
                "X-Requested-With" to "XMLHttpRequest"
            )
            if (cookies.isNotEmpty()) {
                xhrHeaders["Cookie"] = cookies
            }

            val responsetext = app.get(xhrurl, headers = xhrHeaders).text
            Log.d(name, "xhr yanit uzunlugu: ${responsetext.length}")

            """labelShort"\s*:\s*"(\d{3,4}p)[^"]*"\s*,\s*"src"\s*:\s*"([^"]+)"""".toRegex()
                .findAll(responsetext).forEach { match ->
                    val quality = match.groupValues[1]
                    val videourl = match.groupValues[2]
                    if (!videourl.contains("/dload/")) {
                        Log.d(name, "kalite: $quality - url: $videourl")
                        callback.invoke(
                            newExtractorLink(
                                name = name,
                                source = name,
                                url = videourl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = mainUrl
                                this.quality = getQualityFromName(quality)
                            }
                        )
                        videolink = true
                    }
                }

            val hlsmatch = """"srcFallback"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""".toRegex().find(responsetext)
            if (hlsmatch != null) {
                Log.d(name, "hls: ${hlsmatch.groupValues[1]}")
                callback.invoke(
                    newExtractorLink(
                        name = name,
                        source = "${name}:HLS",
                        url = hlsmatch.groupValues[1],
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = mainUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                videolink = true
            }

            if (!videolink) {
                Log.d(name, "xhr icerisinde video linki bulunmadı")
            }

        } catch (e: Exception) {
            Log.d(name, "hata: ${e.message}")
        }

        return videolink
    }
}