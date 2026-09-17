package com.pusatfilm

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.httpsify
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URI

class Pusatfilm : MainAPI() {
    companion object {
        private const val DEFAULT_DOMAIN = "https://v4.pusatfilm21info.net"
        private val CANDIDATE_DOMAINS = listOf(
            "https://v4.pusatfilm21info.net",
            "https://pf21.net",
            "https://pusatfilm21info.net",
            "https://pusatfilm21.vip"
        )
        private var cachedMainUrl: String = DEFAULT_DOMAIN
        private var lastResolvedTime: Long = 0L
        private const val CACHE_TTL_MS: Long = 1000L * 60 * 60 * 3 // 3 hours
    }

    override var mainUrl = DEFAULT_DOMAIN
    private val mainUrlJson = "https://raw.githubusercontent.com/Asm0d3usX/CloudX/builds/Website.json"
    private var directUrl: String? = null
    override var name = "Pusatfilm"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes =
        setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)

    override val mainPage =
        mainPageOf(
            "film-terbaru/page/%d/" to "Terbaru",
            "trending/page/%d/" to "Trending",
            "series-terbaru/page/%d/" to "TV Series",
            "genre/action/page/%d/" to "Action",
            "genre/animation/page/%d/" to "Animation",
            "genre/comedy/page/%d/" to "Comedy",
            "genre/drama/page/%d/" to "Drama",
            "genre/fantasy/page/%d/" to "Fantasy",
            "genre/horror/page/%d/" to "Horror",
            "genre/romance/page/%d/" to "Romance",
            "genre/war/page/%d/" to "War",
            "country/china/page/%d/" to "China",
            "country/japan/page/%d/" to "Japan",
            "country/philippines/page/%d/" to "Philippines",
            "country/thailand/page/%d/" to "Thailand"
        )

    private suspend fun loadMainUrlIfNeeded(force: Boolean = false): String {
        val now = System.currentTimeMillis()
        if (!force && lastResolvedTime > 0 && (now - lastResolvedTime) < CACHE_TTL_MS && directUrl != null) {
            mainUrl = cachedMainUrl
            return cachedMainUrl
        }

        val candidates = mutableListOf<String>()
        candidates.add(cachedMainUrl)

        try {
            val response = app.get(mainUrlJson, timeout = 4).text
            val json = JSONObject(response)
            val array = json.optJSONArray("pusatfilm")
            if (array != null) {
                for (i in 0 until array.length()) {
                    val remoteUrl = array.optString(i)?.removeSuffix("/")
                    if (!remoteUrl.isNullOrBlank() && !candidates.contains(remoteUrl)) {
                        candidates.add(remoteUrl)
                    }
                }
            }
        } catch (_: Exception) { }

        for (domain in CANDIDATE_DOMAINS) {
            if (!candidates.contains(domain)) candidates.add(domain)
        }

        for (candidate in candidates) {
            try {
                val res = app.get(candidate, timeout = 5)
                if (res.isSuccessful || res.code in 200..399) {
                    val finalUrl = res.okhttpResponse.request.url
                    val detectedDomain = "${finalUrl.scheme}://${finalUrl.host}".removeSuffix("/")
                    if (detectedDomain.isNotBlank() && detectedDomain.startsWith("http")) {
                        cachedMainUrl = detectedDomain
                        lastResolvedTime = now
                        mainUrl = detectedDomain
                        directUrl = detectedDomain
                        return detectedDomain
                    }
                }
            } catch (_: Exception) {}
        }

        mainUrl = cachedMainUrl.ifBlank { DEFAULT_DOMAIN }
        directUrl = mainUrl
        return mainUrl
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        loadMainUrlIfNeeded()
        val document = app.get("$mainUrl/${request.data.format(page)}").document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.entry-title > a")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()
        val quality = this.select("div.gmr-qual, div.gmr-quality-item > a").text().trim().replace("-", "")
        val rating = selectFirst(".gmr-rating-item")?.ownText()?.trim()?.toFloatOrNull()
        val epsText = selectFirst(".gmr-quality-item.tag-episode a")?.text()?.trim()
        val eps = Regex("E(\\d+)", RegexOption.IGNORE_CASE).find(epsText ?: "")?.groupValues?.get(1)?.toIntOrNull()

        return if (quality.isEmpty()) {
            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addSub(eps)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (rating != null) this.score = Score.from10(rating)
                addQuality(quality)
            }
        }
    }
    
    private fun Element.toRecommendResult(): SearchResponse? {
        val title = this.selectFirst(".idmuvi-rp-title")?.text()?.trim() ?: return null
        val href = fixUrl(this.selectFirst("a")!!.attr("href"))
        val posterUrl = fixUrlNull(this.selectFirst("a > img")?.getImageAttr()).fixImageQuality()

        return newAnimeSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        loadMainUrlIfNeeded()
        val document = app.get("$mainUrl/?s=$query&post_type[]=post&post_type[]=tv", timeout = 50L).document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        loadMainUrlIfNeeded()
        val fetch = app.get(url)
        val document = fetch.document

        val title = document.selectFirst("h1.entry-title")
            ?.text()
            ?.substringBefore("Season")
            ?.substringBefore("Episode")
            ?.trim()
            .toString()
        val poster = fixUrlNull(document.selectFirst("figure.pull-left > img")?.getImageAttr()?.fixImageQuality())
        val tags = document.select("div.gmr-moviedata a").map { it.text() }
        val year = document.select("div.gmr-moviedata strong:contains(Year:) > a").text().trim().toIntOrNull()
        val tvType = if (url.contains("/tv/")) TvType.TvSeries else TvType.Movie
        val description = document.selectFirst("div[itemprop=description] > p")?.text()?.trim()
        val trailer = document.selectFirst("ul.gmr-player-nav li a.gmr-trailer-popup")?.attr("href")
        val rating = document.selectFirst("div.gmr-meta-rating > span[itemprop=ratingValue]")?.text()?.trim()
        val actors = document.select("div.gmr-moviedata").last()?.select("span[itemprop=actors]")?.map { it.select("a").text() }
        val recommendations = document.select("ul > li").mapNotNull { it.toRecommendResult() }
        val duration = document.selectFirst("div.gmr-moviedata span[property=duration]")
            ?.text()
            ?.replace(Regex("\\D"), "")
            ?.toIntOrNull()

        return if (tvType == TvType.TvSeries) {
            val episodes = document.select("div.vid-episodes a, div.gmr-listseries a")
                .map { eps ->
                    val href = fixUrl(eps.attr("href"))
                    val name = eps.text()
                    val episode = name.split(" ").lastOrNull()?.filter { it.isDigit() }?.toIntOrNull()
                    val season = name.split(" ").firstOrNull()?.filter { it.isDigit() }?.toIntOrNull()
                    newEpisode(href) {
                        this.name = "Episode $episode"
                        this.season = if (name.contains(" ")) season else null
                        this.episode = episode
                        this.posterUrl = poster
                    }
                }
                .filter { it.episode != null }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                addScore(rating)
                this.duration = duration ?: 0
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        loadMainUrlIfNeeded()
        val document = app.get(data).document
        val iframeEl = document.selectFirst("div.gmr-embed-responsive iframe, div.movieplay iframe, iframe")
        val iframe = listOf("src", "data-src", "data-litespeed-src")
            .firstNotNullOfOrNull { key -> iframeEl?.attr(key)?.takeIf { it.isNotBlank() } }
            ?.let { httpsify(it) }

        if (!iframe.isNullOrBlank()) {
            val refererBase = runCatching { getBaseUrl(iframe) }.getOrDefault(mainUrl) + "/"
            loadExtractor(iframe, refererBase, subtitleCallback, callback)
        }
        return true
    }

    private fun Element.getImageAttr(): String {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }

    private fun String?.fixImageQuality(): String? {
        if (this == null) return null
        val regex = Regex("(-\\d*x\\d*)").find(this)?.groupValues?.get(0) ?: return this
        return this.replace(regex, "")
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}