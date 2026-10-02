package com.streamcorner

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import java.util.Base64

open class StreamCornerExtractor : ExtractorApi() {
    override val name = "StreamCorner"
    override val mainUrl = "https://streamcorner.st"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ref = referer ?: "$mainUrl/"
        extractStream(cleanUrl, ref, subtitleCallback, callback)
    }

    private val atobIframePattern = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""")
    private val m3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
    private val mpdRegex = Regex("""["'](https?://[^"']+\.mpd[^"']*)["']""")

    suspend fun extractStream(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return false

        // 1. Direct .m3u8
        if (cleanUrl.contains(".m3u8")) {
            val headersMap = mapOf(
                "Referer" to referer,
                "Origin" to getOrigin(referer),
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
            )
            var generatedAny = false
            try {
                val list = M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = cleanUrl,
                    referer = referer,
                    headers = headersMap
                )
                if (list.isNotEmpty()) {
                    list.forEach(callback)
                    generatedAny = true
                }
            } catch (_: Exception) {}

            if (!generatedAny) {
                callback(
                    newExtractorLink(
                        name = "$name Live HLS",
                        source = name,
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.headers = headersMap
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            return true
        }

        // 2. Direct .mpd
        if (cleanUrl.contains(".mpd")) {
            callback(
                newExtractorLink(
                    name = "$name Live DASH",
                    source = name,
                    url = cleanUrl,
                    type = ExtractorLinkType.DASH
                ) {
                    this.referer = referer
                    this.headers = mapOf(
                        "Referer" to referer,
                        "Origin" to getOrigin(referer)
                    )
                    this.quality = Qualities.P1080.value
                }
            )
            return true
        }

        // 3. RockyStream: decode atob(...) iframe
        if (cleanUrl.contains("rockystream.st")) {
            try {
                val html = app.get(cleanUrl, referer = referer).text
                val b64 = atobIframePattern.find(html)?.groupValues?.get(1)
                if (!b64.isNullOrBlank()) {
                    val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
                    if (decoded.startsWith("http")) {
                        return extractStream(decoded, cleanUrl, subtitleCallback, callback)
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Embed.st atau server sejenis dengan WebViewResolver
        if (cleanUrl.contains("embed.st") || cleanUrl.contains("pandecocogaming.sbs")) {
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mpd)(\?.*)?$"""),
                    additionalUrls = listOf(Regex(""".*\.(m3u8|mpd).*""")),
                    useOkhttp = false
                )
                val response = app.get(cleanUrl, referer = referer, interceptor = resolver)
                val streamUrl = response.url
                if (streamUrl.isNotBlank() && (streamUrl.contains(".m3u8") || streamUrl.contains(".mpd"))) {
                    val isMpd = streamUrl.contains(".mpd")
                    callback(
                        newExtractorLink(
                            name = if (isMpd) "$name Live DASH" else "$name Live HLS",
                            source = name,
                            url = streamUrl,
                            type = if (isMpd) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                        ) {
                            this.referer = cleanUrl
                            this.quality = Qualities.P1080.value
                        }
                    )
                    return true
                }
            } catch (_: Exception) {}
        }

        // 5. General page scraping: check iframes or inline m3u8
        try {
            val doc = app.get(cleanUrl, referer = referer).document
            val pageHtml = doc.html()

            val m3u8Match = m3u8Regex.find(pageHtml)?.groupValues?.get(1)
            if (!m3u8Match.isNullOrBlank()) {
                return extractStream(fixUrl(m3u8Match), cleanUrl, subtitleCallback, callback)
            }

            val mpdMatch = mpdRegex.find(pageHtml)?.groupValues?.get(1)
            if (!mpdMatch.isNullOrBlank()) {
                return extractStream(fixUrl(mpdMatch), cleanUrl, subtitleCallback, callback)
            }

            var anyIframeFound = false
            doc.select("iframe").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (src.isNotBlank() && src != cleanUrl) {
                    anyIframeFound = true
                    loadExtractor(src, referer = cleanUrl, subtitleCallback, callback)
                }
            }
            if (anyIframeFound) return true
        } catch (_: Exception) {}

        // 6. Fallback ke sistem extractor bawaan Cloudstream
        loadExtractor(cleanUrl, referer = referer, subtitleCallback, callback)
        return true
    }

    private fun getOrigin(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            "https://streamcorner.st"
        }
    }
}
