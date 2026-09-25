package com.kraptor

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.json.JSONArray
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class WatchPorn(context: Context) : MainAPI() {
    override var mainUrl = "https://watchporn.to"
    override var name = "WatchPorn"
    override val hasMainPage = true
    override var lang = "en"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.NSFW)

    private val context = context

    override val mainPage
        get() = mainPageOf(
            *(try {
                WatchPornSettings.getEnabledMainPageItems(mainUrl).map { (url, name) ->
                    fixUrl(url) to name
                }.toTypedArray()
            } catch (e: Exception) {
                arrayOf(
                    "$mainUrl/top-rated/" to "Top Rated",
                    "$mainUrl/most-popular/" to "Most Popular"
                )
            })
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val target = fixUrl(request.data)
        val url = if (page <= 1) target else "${target.removeSuffix("/")}/$page/"
        val document = app.get(url).document
        val home = document.select("div.thumb.item").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        val hasNext = document.selectFirst("li.next, a.next, a[rel=next], a.pagination__next") != null || home.size >= 24

        return newHomePageResponse(
            list = listOf(
                HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                )
            ),
            hasNext = hasNext
        )
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("span.thumb__title")?.text()?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-webp") ?: this.selectFirst("img")?.attr("src"))
        val rating = this.select("span.thumb__meta-item").lastOrNull()?.text()?.replace("%", "")?.trim()

        return newMovieSearchResponse(title, "$href|$posterUrl", TvType.NSFW) {
            this.posterUrl = posterUrl
            this.score = Score.from100(rating)
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList {
        val url = "${mainUrl}/search/?q=$query&mode=async&function=get_block&block_id=list_videos_videos_list_search_result&category_ids=&sort_by=&from_videos=$page"
        val document = app.get(url).document

        val aramaCevap = document.select("div.thumb.item").mapNotNull { it.toMainPageResult() }.distinctBy { it.url }
        val hasNext = document.selectFirst("li.next") != null

        return newSearchResponseList(aramaCevap, hasNext)
    }

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun load(data: String): LoadResponse? {
        val (url, storedPoster) = data.split("|").let {
            it[0] to it.getOrNull(1)
        }

        val response = app.get(url)
        val document = response.document
        val cookies = response.cookies.toString()

        val title = document.selectFirst("h1.single__content-title")?.text()?.trim() ?: return null
        val poster = storedPoster ?: document.selectFirst("meta[property=og:image]")?.attr("content")

        val tags = document.select("div.single__info-row:contains(Tags:) a").map { it.text().trim() }
        val actors = document.select("div.single__info-row:contains(Models:) a").map { Actor(it.text().trim()) }

        val durationText = document.selectFirst("div.fp-time-duration")?.text()?.trim()
        val parts = durationText?.split(":")?.mapNotNull { it.toIntOrNull() }
        val totalMinutes = when (parts?.size) {
            3 -> (parts[0] * 60) + parts[1]
            2 -> parts[0]
            else -> null
        }

        val recommendations = document.select("div.related-videos div.thumb.item").mapNotNull {
            it.toMainPageResult()
        }.distinctBy { it.url }

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.posterHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/",
                "Cookie" to cookies
            )
            this.tags = tags
            this.duration = totalMinutes
            this.recommendations = recommendations
            addActors(actors)

            Log.d("Cloudstream", "Loaded Video: $title")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun extractVideoUrls(
        context: Context,
        html: String
    ): List<String> = suspendCoroutine { continuation ->
        Handler(Looper.getMainLooper()).post {
            val wv = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Handler(Looper.getMainLooper()).postDelayed({
                            view?.evaluateJavascript("""
                            (function() {
                                var videos = [];
                                
                                if (typeof flashvars !== 'undefined') {
                                    if (flashvars.video_url && flashvars.video_url.indexOf('https://') !== -1) {
                                        videos.push(flashvars.video_url);
                                    }
                                    if (flashvars.video_alt_url && flashvars.video_alt_url.indexOf('https://') !== -1) {
                                        videos.push(flashvars.video_alt_url);
                                    }
                                }
                                
                                if (videos.length === 0) {
                                    var scripts = document.getElementsByTagName('script');
                                    for (var i = 0; i < scripts.length; i++) {
                                        var text = scripts[i].textContent;
                                        var matches = text.match(/https:\/\/watchporn\.to\/get_file\/[^\s'"]+\.mp4[^\s'"]*/g);
                                        if (matches) {
                                            for (var j = 0; j < matches.length; j++) {
                                                videos.push(matches[j]);
                                            }
                                        }
                                    }
                                }
                                
                                videos = videos.filter(function(item, pos) {
                                    return videos.indexOf(item) === pos;
                                });
                                
                                return JSON.stringify(videos);
                            })();
                        """) { result ->
                                try {
                                    val cleanResult = result.trim('"').replace("\\", "")
                                    val videoUrls = JSONArray(cleanResult)

                                    val urls = mutableListOf<String>()
                                    for (i in 0 until videoUrls.length()) {
                                        urls.add(videoUrls.getString(i))
                                    }

                                    continuation.resume(urls)

                                    Handler(Looper.getMainLooper()).post {
                                        try {
                                            this@apply.stopLoading()
                                            this@apply.clearHistory()
                                            this@apply.destroy()
                                        } catch (ignored: Throwable) {}
                                    }
                                } catch (e: Exception) {
                                    Log.e("VideoExtractor", "Error: ${e.message}")
                                    continuation.resume(emptyList())
                                }
                            }
                        }, 100)
                    }
                }

                loadDataWithBaseURL(mainUrl, html, "text/html", "UTF-8", null)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("kraptor_$name", "data = $data")
        val documentText = app.get(data).text

        // Fast Regex extraction first
        val fastUrls = mutableListOf<String>()
        val videoUrl720 = Regex("""video_url\s*:\s*['"](https?://[^'"]+)['"]""").find(documentText)?.groupValues?.get(1)
        val videoUrl1080 = Regex("""video_alt_url\s*:\s*['"](https?://[^'"]+)['"]""").find(documentText)?.groupValues?.get(1)

        if (!videoUrl1080.isNullOrBlank()) fastUrls.add(videoUrl1080)
        if (!videoUrl720.isNullOrBlank()) fastUrls.add(videoUrl720)

        val finalUrls = if (fastUrls.isNotEmpty()) {
            fastUrls
        } else {
            // Fallback to WebView extraction
            extractVideoUrls(context, documentText)
        }

        finalUrls.distinct().forEach { url ->
            Log.d("kraptor_$name", "url = $url")
            val quality = url.substringBeforeLast("/").substringAfterLast("/").substringBefore(".").substringAfter("_")
            callback.invoke(
                newExtractorLink(
                    name,
                    name,
                    url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(quality)
                }
            )
        }

        return finalUrls.isNotEmpty()
    }
}
