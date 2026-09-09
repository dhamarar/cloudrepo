// ! Bu araç @Kraptor123 tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.kraptor

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import kotlin.math.sqrt

class PornHub : MainAPI() {
    override var mainUrl              = "https://www.pornhub.com"
    override var name                 = "PornHub"
    override val hasMainPage          = true
    override var lang                 = "en"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)

    private val sessionMutex = Mutex()
    private val sessionCookies = mutableMapOf(
        "hasVisited" to "1",
        "accessAgeDisclaimerPH" to "1"
    )

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.5"
    )

    private fun leastFactor(n: Long): Long {
        if (n == 0L) return 0L
        if (n * n < 2L) return 1L
        if (n % 2L == 0L) return 2L
        if (n % 3L == 0L) return 3L
        if (n % 5L == 0L) return 5L
        val m = sqrt(n.toDouble()).toLong()
        var i = 7L
        while (i <= m) {
            if (n % i == 0L) return i
            if (n % (i + 4L) == 0L) return i + 4L
            if (n % (i + 6L) == 0L) return i + 6L
            if (n % (i + 10L) == 0L) return i + 10L
            if (n % (i + 12L) == 0L) return i + 12L
            if (n % (i + 16L) == 0L) return i + 16L
            if (n % (i + 22L) == 0L) return i + 22L
            if (n % (i + 24L) == 0L) return i + 24L
            i += 30L
        }
        return n
    }

    private fun solveChallengePure(jsCode: String): Pair<String, String>? {
        return try {
            val cleaned = jsCode.replace(Regex("""/\*[\s\S]*?\*/"""), "")
            val pMatch = Regex("""var\s+p\s*=\s*(\d+)""").find(cleaned) ?: return null
            val sMatch = Regex("""var\s+s\s*=\s*(\d+)""").find(cleaned) ?: return null
            var p = pMatch.groupValues[1].toLong()
            val s = sMatch.groupValues[1].toLong()

            val ifPattern = Regex(
                """if\s*\(\s*\(\s*s\s*>>\s*(\d+)\s*\)\s*&\s*1\s*\)\s*p\s*([+-])=\s*(-?\d+)\s*\*\s*(-?\d+)\s*;\s*else\s*p\s*([+-])=\s*(-?\d+)\s*\*\s*(-?\d+)\s*;"""
            )
            var lastEnd = 0
            for (match in ifPattern.findAll(cleaned)) {
                val shift = match.groupValues[1].toInt()
                val op1 = match.groupValues[2]
                val a1 = match.groupValues[3].toLong()
                val b1 = match.groupValues[4].toLong()
                val op2 = match.groupValues[5]
                val a2 = match.groupValues[6].toLong()
                val b2 = match.groupValues[7].toLong()

                val cond = ((s ushr shift) and 1L) != 0L
                if (cond) {
                    val v = a1 * b1
                    if (op1 == "+") p += v else p -= v
                } else {
                    val v = a2 * b2
                    if (op2 == "+") p += v else p -= v
                }
                lastEnd = match.range.last + 1
            }

            val tail = cleaned.substring(lastEnd)
            val tailMatch = Regex("""p\s*([+-])=\s*(\d+);""").find(tail)
            if (tailMatch != null) {
                val op = tailMatch.groupValues[1]
                val v = tailMatch.groupValues[2].toLong()
                if (op == "+") p += v else p -= v
            }

            val cookieMatch = Regex("""document\.cookie\s*=\s*"KEY="\s*\+\s*n\s*\+\s*"\*"\s*\+\s*p/n\s*\+\s*":(?:\s*\+\s*s\s*\+\s*")?(\d+):1;path=/;""").find(tail)
                ?: Regex(""":(\d+):1;path=/;""").find(tail)
            val extraId = cookieMatch?.groupValues?.getOrNull(1).orEmpty()

            val n = leastFactor(p)
            val cookieVal = "$n*${p / n}:$s:$extraId:1"
            Pair("KEY", cookieVal)
        } catch (e: Exception) {
            null
        }
    }

    private fun solveChallengeRhino(jsCode: String): Pair<String, String>? {
        val cleanJs = """
            var module = undefined;
            var exports = undefined;
            var phantom = undefined;
            var document = {
                cookie: '',
                location: { reload: function() {} }
            };
            $jsCode
            go();
            document.cookie;
        """.trimIndent()
        val rhino = Context.enter()
        return try {
            rhino.initSafeStandardObjects()
            rhino.optimizationLevel = -1
            val scope: Scriptable = rhino.initSafeStandardObjects()
            val result = rhino.evaluateString(scope, cleanJs, "PornHubChallenge", 1, null)
            val cookieStr = Context.toString(result)
            val keyPart = cookieStr.substringBefore(";").trim()
            val k = keyPart.substringBefore("=")
            val v = keyPart.substringAfter("=")
            if (k.isNotBlank() && v.isNotBlank()) Pair(k, v) else null
        } catch (e: Exception) {
            Log.e("PornHub", "Rhino solver error: ${e.message}")
            null
        } finally {
            Context.exit()
        }
    }

    private fun solveChallenge(html: String): Pair<String, String>? {
        val scriptMatch = Regex("""<script[^>]*><!--([\s\S]*?)//--></script>""").find(html)
            ?: Regex("""<script[^>]*>([\s\S]*?function\s+leastFactor[\s\S]*?)</script>""").find(html)
            ?: return null
        val jsCode = scriptMatch.groupValues[1]
        return solveChallengePure(jsCode) ?: solveChallengeRhino(jsCode)
    }

    private suspend fun fetch(url: String, referer: String? = "$mainUrl/"): NiceResponse {
        val currentHeaders = if (referer != null) defaultHeaders + ("Referer" to referer) else defaultHeaders
        var response = app.get(url, headers = currentHeaders, cookies = sessionCookies)

        var html = response.text
        if (html.contains("leastFactor") || html.contains("onload=\"go()\"")) {
            sessionMutex.withLock {
                response = app.get(url, headers = currentHeaders, cookies = sessionCookies)
                html = response.text
                if (html.contains("leastFactor") || html.contains("onload=\"go()\"")) {
                    response.cookies.let { sessionCookies.putAll(it) }
                    val solved = solveChallenge(html)
                    if (solved != null) {
                        sessionCookies[solved.first] = solved.second
                        response = app.get(url, headers = currentHeaders, cookies = sessionCookies)
                        response.cookies.let { sessionCookies.putAll(it) }
                    }
                }
            }
        } else {
            response.cookies.let { sessionCookies.putAll(it) }
        }
        return response
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/video"                  to "Featured",
        "${mainUrl}/categories/teen"                  to "18-25",
        "${mainUrl}/video?c=105"                      to "60FPS",
        "${mainUrl}/video?c=3"                        to "Amateur",
        "${mainUrl}/video?c=35"                       to "Anal",
        "${mainUrl}/video?c=98"                       to "Arab",
        "${mainUrl}/video?c=1"                        to "Asian",
        "${mainUrl}/categories/babe"                  to "Babe",
//        "${mainUrl}/video?c=89"                       to "Babysitter (18+)",
//        "${mainUrl}/video?c=6"                        to "BBW",
//        "${mainUrl}/video?c=141"                      to "Behind The Scenes",
        "${mainUrl}/video?c=4"                        to "Big Ass",
//        "${mainUrl}/video?c=7"                        to "Big Dick",
//        "${mainUrl}/video?c=8"                        to "Big Tits",
//        "${mainUrl}/video?c=76"                       to "Bisexual Male",
        "${mainUrl}/video?c=9"                        to "Blonde",
//        "${mainUrl}/video?c=13"                       to "Blowjob",
//        "${mainUrl}/video?c=10"                       to "Bondage",
//        "${mainUrl}/video?c=102"                      to "Brazilian",
//        "${mainUrl}/video?c=96"                       to "British",
        "${mainUrl}/video?c=11"                       to "Brunette",
        "${mainUrl}/video?c=14"                       to "Bukkake",
//        "${mainUrl}/video?c=86"                       to "Cartoon",
//        "${mainUrl}/video?c=90"                       to "Casting",
//        "${mainUrl}/video?c=12"                       to "Celebrity",
//        "${mainUrl}/video?c=732"                      to "Closed Captions",
//        "${mainUrl}/categories/college"               to "College (18+)",
//        "${mainUrl}/video?c=57"                       to "Compilation",
        "${mainUrl}/video?c=241"                      to "Cosplay",
//        "${mainUrl}/video?c=15"                       to "Creampie",
//        "${mainUrl}/video?c=242"                      to "Cuckold",
//        "${mainUrl}/video?c=16"                       to "Cumshot",
//        "${mainUrl}/video?c=100"                      to "Czech",
//        "${mainUrl}/video/search?search=deepthroat"   to "Deepthroat",
//        "${mainUrl}/described-video"                  to "Described Video",
//        "${mainUrl}/video?c=72"                       to "Double Penetration",
        "${mainUrl}/video?c=17"                       to "Ebony",
//        "${mainUrl}/video?c=55"                       to "Euro",
//        "${mainUrl}/video?c=115"                      to "Exclusive",
//        "${mainUrl}/video?c=93"                       to "Feet",
//        "${mainUrl}/video?c=502"                      to "Female Orgasm",
//        "${mainUrl}/video?c=18"                       to "Fetish",
//        "${mainUrl}/video?c=592"                      to "Fingering",
//        "${mainUrl}/video?c=19"                       to "Fisting",
//        "${mainUrl}/video?c=94"                       to "French",
//        "${mainUrl}/video?c=32"                       to "Funny",
//        "${mainUrl}/video?c=881"                      to "Gaming",
//        "${mainUrl}/video?c=80"                       to "Gangbang",
//        "${mainUrl}/video?c=95"                       to "German",
//        "${mainUrl}/video?c=20"                       to "Handjob",
//        "${mainUrl}/video?c=21"                       to "Hardcore",
        "${mainUrl}/hd"                               to "HD Porn",
//        "${mainUrl}/categories/hentai"                to "Hentai",
//        "${mainUrl}/video?c=101"                      to "Indian",
//        "${mainUrl}/interactive"                      to "Interactive",
//        "${mainUrl}/video?c=25"                       to "Interracial",
//        "${mainUrl}/video?c=97"                       to "Italian",
//        "${mainUrl}/video?c=111"                      to "Japanese",
//        "${mainUrl}/video?c=103"                      to "Korean",
//        "${mainUrl}/video?c=26"                       to "Latina",
//        "${mainUrl}/video?c=27"                       to "Lesbian",
//        "${mainUrl}/video?c=78"                       to "Massage",
//        "${mainUrl}/video?c=22"                       to "Masturbation",
        "${mainUrl}/video?c=28"                       to "Mature",
        "${mainUrl}/video?c=29"                       to "MILF",
//        "${mainUrl}/video?c=512"                      to "Muscular Men",
//        "${mainUrl}/video?c=121"                      to "Music",
//        "${mainUrl}/video?c=181"                      to "Old/Young (18+)",
//        "${mainUrl}/video?c=2"                        to "Orgy",
//        "${mainUrl}/video?c=201"                      to "Parody",
//        "${mainUrl}/video?c=53"                       to "Party",
//        "${mainUrl}/video?c=211"                      to "Pissing",
//        "${mainUrl}/video?c=891"                      to "Podcast",
//        "${mainUrl}/popularwithwomen"                 to "Popular With Women",
//        "${mainUrl}/categories/pornstar"              to "Pornstar",
//        "${mainUrl}/video?c=41"                       to "POV",
//        "${mainUrl}/video?c=24"                       to "Public",
//        "${mainUrl}/video?c=131"                      to "Pussy Licking",
//        "${mainUrl}/video?c=31"                       to "Reality",
//        "${mainUrl}/video?c=42"                       to "Red Head",
//        "${mainUrl}/video?c=81"                       to "Role Play",
//        "${mainUrl}/video?c=522"                      to "Romantic",
//        "${mainUrl}/video?c=67"                       to "Rough Sex",
//        "${mainUrl}/video?c=99"                       to "Russian",
//        "${mainUrl}/video?c=88"                       to "School (18+)",
//        "${mainUrl}/sfw"                              to "SFW",
//        "${mainUrl}/video?c=59"                       to "Small Tits",
//        "${mainUrl}/video?c=91"                       to "Smoking",
//        "${mainUrl}/video?c=492"                      to "Solo Female",
//        "${mainUrl}/video?c=92"                       to "Solo Male",
//        "${mainUrl}/video?c=69"                       to "Squirt",
//        "${mainUrl}/video?c=444"                      to "Step Fantasy",
//        "${mainUrl}/video?c=542"                      to "Strap On",
//        "${mainUrl}/video?c=33"                       to "Striptease",
//        "${mainUrl}/video?c=562"                      to "Tattooed Women",
//        "${mainUrl}/video?c=65"                       to "Threesome",
//        "${mainUrl}/video?c=23"                       to "Toys",
//        "${mainUrl}/transgender"                      to "Transgender",
//        "${mainUrl}/video?c=138"                      to "Verified Amateurs",
//        "${mainUrl}/video?c=482"                      to "Verified Couples",
//        "${mainUrl}/video?c=139"                      to "Verified Models",
//        "${mainUrl}/video?c=43"                       to "Vintage",
//        "${mainUrl}/vr"                               to "Virtual Reality",
//        "${mainUrl}/video?c=61"                       to "Webcam",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val requestUrl = if (request.data.contains("?")) {
            "${request.data}&page=$page"
        } else {
            "${request.data}?page=$page"
        }
        val document = fetch(requestUrl).document
        val home = document.select("div.gridWrapper li.pcVideoListItem, li.pcVideoListItem")
            .mapNotNull { it.toMainPageResult() }

        return newHomePageResponse(HomePageList(request.name, home, true))
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val link = this.selectFirst("a[href*='view_video.php']") ?: this.selectFirst("a") ?: return null
        val href = fixUrlNull(link.attr("href")) ?: return null

        val rawTitle = link.attr("title").ifBlank { null }
            ?: this.selectFirst("span.title a")?.text()?.ifBlank { null }
            ?: this.selectFirst("img")?.attr("alt")?.ifBlank { null }
            ?: return null
        val title = Jsoup.parse(rawTitle).text().trim()
        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrlNull(
            img?.attr("data-src")?.ifBlank { null }
                ?: img?.attr("src")?.ifBlank { null }
                ?: img?.attr("data-thumb_url")?.ifBlank { null }
                ?: img?.attr("data-mediumthumb")?.ifBlank { null }
        )

        return newMovieSearchResponse(title, href, TvType.NSFW) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to defaultHeaders["User-Agent"]!!
            )
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val document = fetch("${mainUrl}/video/search?search=$query&page=$page").document
        val aramaCevap = document.select("div.gridWrapper li.pcVideoListItem, li.pcVideoListItem")
            .mapNotNull { it.toMainPageResult() }
        return newSearchResponseList(aramaCevap, hasNext = true)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query, 1).items

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("kraptor")
        val videourl = if (parts.isNotEmpty()) parts[0] else url
        val trailerurl = if (parts.size >= 2) "https://${parts[1]}" else null
        val document = fetch(videourl, referer = videourl).document
        val rawTitle = document.selectFirst("h1")?.text()?.trim() ?: return null
        val baslik = Jsoup.parse(rawTitle).text().trim()

        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("noscript:has(img.videoElementPoster)")?.let {
                Jsoup.parse(it.html()).selectFirst("img")?.attr("src")
            })
            ?: fixUrlNull(document.selectFirst("img.videoElementPoster")?.attr("src"))

        val rawDesc = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val aciklama = rawDesc?.let { Jsoup.parse(it).text() }
        val etiketler = document.select("div.tagsWrapper a").map { it.text().trim() }.filter { it.isNotBlank() }
        val sure = document.selectFirst("var.duration")?.text()?.split(":")?.first()?.trim()?.toIntOrNull()

        val recommendations = document.select("li.pcVideoListItem").mapNotNull { oge ->
            val link = oge.selectFirst("a.thumbnailTitle") ?: oge.selectFirst("a[href*='view_video.php']")
            val href = link?.attr("href") ?: return@mapNotNull null

            val resim = oge.selectFirst("img")
            val rawRecTitle = link.attr("title").ifBlank { null }
                ?: link.attr("data-title").ifBlank { null }
                ?: resim?.attr("alt")?.ifBlank { null }
                ?: return@mapNotNull null

            if (rawRecTitle.matches(Regex("""\d+:\d+"""))) return@mapNotNull null

            val recposter = resim?.attr("data-mediumthumb")?.takeIf { it.isNotBlank() }
                ?: resim?.attr("data-thumb_url")?.takeIf { it.isNotBlank() }
                ?: resim?.attr("src")

            if (recposter.isNullOrBlank() || recposter.contains("data:image")) return@mapNotNull null

            val recTitle = Jsoup.parse(rawRecTitle).text().trim()
            newMovieSearchResponse(recTitle, fixUrl(href), TvType.NSFW) {
                this.posterUrl = fixUrlNull(recposter)
                this.posterHeaders = mapOf(
                    "Referer" to "$mainUrl/",
                    "User-Agent" to defaultHeaders["User-Agent"]!!
                )
            }
        }

        val aktorler = document.select("a.pstar-list-btn").map {
            Actor(it.text().trim(), it.selectFirst("img.avatar")?.attr("src"))
        }

        return newMovieLoadResponse(baslik, videourl, TvType.NSFW, videourl) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "User-Agent" to defaultHeaders["User-Agent"]!!
            )
            this.plot = aciklama
            this.tags = etiketler
            this.duration = sure
            this.recommendations = recommendations
            addActors(aktorler)
            if (trailerurl != null) {
                addTrailer(trailerurl, "$mainUrl/", true)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = fetch(data, referer = "$mainUrl/")
        val html = response.text

        val flashvarsMatch = Regex("""var\s+flashvars_\d+\s*=\s*(\{.+?\});""").find(html)
        val flashvarsJson = flashvarsMatch?.groupValues?.get(1)
            ?: response.document.selectFirst("script:containsData(flashvars)")?.data()
                ?.let { s ->
                    Regex("""flashvars\w*\s*=\s*(\{.+?\});""").find(s)?.groupValues?.get(1)
                }
            ?: return false

        val mapperData = try {
            mapper.readValue<Phub>(flashvarsJson)
        } catch (e: Exception) {
            Log.e("PornHub", "Failed to parse flashvars JSON: ${e.message}")
            return false
        }

        mapperData.mediaDefinitions?.forEach { media ->
            val videoUrl = media.videoUrl ?: return@forEach
            val format = media.format?.lowercase().orEmpty()
            val qualityStr = media.quality?.toString().orEmpty()

            if (format == "hls") {
                callback.invoke(
                    newExtractorLink(
                        name = this.name,
                        source = this.name,
                        url = videoUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$mainUrl/"
                        this.headers = mapOf(
                            "Referer" to "$mainUrl/",
                            "User-Agent" to defaultHeaders["User-Agent"]!!
                        )
                        this.quality = getQualityFromName(qualityStr)
                    }
                )
            } else if (format == "mp4") {
                if (videoUrl.contains("get_media")) {
                    try {
                        val mp4Resp = fetch(videoUrl, referer = data).text
                        val mp4List = mapper.readValue<List<PhubMp4Item>>(mp4Resp)
                        mp4List.forEach { mp4Item ->
                            val directUrl = mp4Item.videoUrl ?: return@forEach
                            val h = mp4Item.height ?: 0
                            callback.invoke(
                                newExtractorLink(
                                    name = "${this.name} MP4",
                                    source = this.name,
                                    url = directUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.referer = "$mainUrl/"
                                    this.headers = mapOf(
                                        "Referer" to "$mainUrl/",
                                        "User-Agent" to defaultHeaders["User-Agent"]!!
                                    )
                                    this.quality = getQualityFromName("${h}p")
                                }
                            )
                        }
                    } catch (e: Exception) {
                        Log.e("PornHub", "Failed to parse get_media: ${e.message}")
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            name = "${this.name} MP4",
                            source = this.name,
                            url = videoUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = "$mainUrl/"
                            this.headers = mapOf(
                                "Referer" to "$mainUrl/",
                                "User-Agent" to defaultHeaders["User-Agent"]!!
                            )
                            this.quality = getQualityFromName(qualityStr)
                        }
                    )
                }
            }
        }

        return true
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class Phub(
    val mediaDefinitions: List<PhubVideo>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PhubVideo(
    val format: String? = null,
    val videoUrl: String? = null,
    val quality: Any? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class PhubMp4Item(
    val format: String? = null,
    val videoUrl: String? = null,
    val height: Int? = null,
    val quality: Any? = null
)