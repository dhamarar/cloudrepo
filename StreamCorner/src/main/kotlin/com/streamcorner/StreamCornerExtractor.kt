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
        callback: (ExtractorLink) -> Unit,
        customSource: String? = null
    ): Boolean {
        val cleanUrl = url.trim()
        if (cleanUrl.isBlank()) return false
        val actualSource = customSource?.takeIf { it.isNotBlank() } ?: name

        val defaultHeaders = mapOf(
            "Referer" to referer,
            "Origin" to getOrigin(referer),
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "en-US,en;q=0.5",
            "Connection" to "keep-alive"
        )

        // 1. Direct .m3u8
        if (cleanUrl.contains(".m3u8")) {
            var generatedAny = false
            try {
                val list = M3u8Helper.generateM3u8(
                    source = actualSource,
                    streamUrl = cleanUrl,
                    referer = referer,
                    headers = defaultHeaders
                )
                if (list.isNotEmpty()) {
                    list.forEach(callback)
                    generatedAny = true
                }
            } catch (_: Exception) {}

            if (!generatedAny) {
                callback(
                    newExtractorLink(
                        name = "$actualSource Live HLS",
                        source = actualSource,
                        url = cleanUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.headers = defaultHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
            }
            return true
        }

        // 2. Direct .mpd
        if (cleanUrl.contains(".mpd")) {
            val keyParam = Regex("""(?:keys|key|drm)=([a-fA-F0-9]{32}:[a-fA-F0-9]{32})""").find(cleanUrl)?.groupValues?.get(1)
            val (rawKid, rawKey) = if (keyParam?.contains(":") == true) {
                keyParam.substringBefore(":").trim() to keyParam.substringAfter(":").trim()
            } else "" to ""

            if (rawKid.isNotBlank() && rawKey.isNotBlank()) {
                val b64Kid = StreamCornerCipher.toClearKeyB64(rawKid)
                val b64Key = StreamCornerCipher.toClearKeyB64(rawKey)
                val cleanMpdUrl = cleanUrl.substringBefore("#").substringBefore("?keys=").substringBefore("&keys=")
                callback(
                    newDrmExtractorLink(
                        source = actualSource,
                        name = "$actualSource Live DASH",
                        url = cleanMpdUrl,
                        type = ExtractorLinkType.DASH,
                        uuid = CLEARKEY_UUID
                    ) {
                        this.kid = b64Kid
                        this.key = b64Key
                        this.kty = "oct"
                        this.referer = referer
                        this.headers = defaultHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            } else if (cleanUrl.contains("cenc.mpd") || cleanUrl.contains("/enc/")) {
                // CENC encrypted stream without decryption keys produces a black/blank screen in ExoPlayer.
                // Do not emit without valid keys.
                return false
            } else {
                callback(
                    newExtractorLink(
                        name = "$actualSource Live DASH",
                        source = actualSource,
                        url = cleanUrl,
                        type = ExtractorLinkType.DASH
                    ) {
                        this.referer = referer
                        this.headers = defaultHeaders
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }
        }

        // 3. RockyStream: decode atob(...) iframe
        if (cleanUrl.contains("rockystream.st")) {
            try {
                val html = app.get(cleanUrl, referer = referer).text
                val b64 = atobIframePattern.find(html)?.groupValues?.get(1)
                if (!b64.isNullOrBlank()) {
                    val decoded = String(Base64.getDecoder().decode(b64), Charsets.UTF_8)
                    if (decoded.startsWith("http")) {
                        return extractStream(decoded, cleanUrl, subtitleCallback, callback, customSource = actualSource)
                    }
                }
            } catch (_: Exception) {}
        }

        // 4. Embed.st atau server sejenis dengan WebViewResolver
        if (cleanUrl.contains("embed.st") || cleanUrl.contains("pandecocogaming.sbs")) {
            try {
                val jsHook = """
                    (function() {
                        var origParse = JSON.parse;
                        var capturedKeys = null;
                        JSON.parse = function(text, reviver) {
                            var res = origParse.apply(this, arguments);
                            try {
                                if (res && typeof res === 'object') {
                                    var k = res.keys || res.stream_keys || res.key;
                                    if (k) capturedKeys = k;
                                    if (res.stream_url && k) {
                                        var u = res.stream_url + (res.stream_url.indexOf('?') >= 0 ? '&' : '?') + 'keys=' + encodeURIComponent(k);
                                        fetch(u).catch(function(){});
                                    }
                                }
                            } catch(e) {}
                            return res;
                        };
                        var origFetch = window.fetch;
                        if (origFetch) {
                            window.fetch = function(input, init) {
                                try {
                                    var url = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                                    if (url && url.indexOf('.mpd') >= 0 && capturedKeys && url.indexOf('keys=') < 0) {
                                        var newUrl = url + (url.indexOf('?') >= 0 ? '&' : '?') + 'keys=' + encodeURIComponent(capturedKeys);
                                        if (typeof input === 'string') input = newUrl;
                                        else input = new Request(newUrl, init);
                                    }
                                } catch(e) {}
                                return origFetch.apply(this, arguments);
                            };
                        }
                    })();
                """.trimIndent()

                val resolver = WebViewResolver(
                    interceptUrl = Regex(""".*\.(m3u8|mpd)(\?.*)?$"""),
                    additionalUrls = listOf(Regex(""".*\.(m3u8|mpd).*""")),
                    useOkhttp = false,
                    script = jsHook
                )
                val response = app.get(cleanUrl, referer = referer, interceptor = resolver)
                val streamUrl = response.url
                if (streamUrl.isNotBlank() && (streamUrl.contains(".m3u8") || streamUrl.contains(".mpd"))) {
                    return extractStream(
                        url = streamUrl,
                        referer = cleanUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                        customSource = actualSource
                    )
                }
            } catch (_: Exception) {}
        }

        // 4.5. StreamCorner bypass via WebViewResolver (for Cloudflare)
        if (cleanUrl.contains("streamcorner.st")) {
            try {
                val resolver = WebViewResolver(
                    interceptUrl = Regex(".*\\.(m3u8|mpd)(\\?.*)?$"),
                    additionalUrls = listOf(Regex(".*\\.(m3u8|mpd).*")),
                    useOkhttp = false
                )
                val response = app.get(cleanUrl, referer = referer, interceptor = resolver)
                val streamUrl = response.url
                if (streamUrl.isNotBlank() && (streamUrl.contains(".m3u8") || streamUrl.contains(".mpd"))) {
                    return extractStream(
                        url = streamUrl,
                        referer = cleanUrl,
                        subtitleCallback = subtitleCallback,
                        callback = callback,
                        customSource = actualSource
                    )
                }
            } catch (_: Exception) {}
        }

        // 5. General page scraping: check iframes or inline m3u8
        try {
            val doc = app.get(cleanUrl, referer = referer).document
            val pageHtml = doc.html()

            val m3u8Match = m3u8Regex.find(pageHtml)?.groupValues?.get(1)
            if (!m3u8Match.isNullOrBlank()) {
                return extractStream(fixUrl(m3u8Match), cleanUrl, subtitleCallback, callback, customSource = actualSource)
            }

            val mpdMatch = mpdRegex.find(pageHtml)?.groupValues?.get(1)
            if (!mpdMatch.isNullOrBlank()) {
                return extractStream(fixUrl(mpdMatch), cleanUrl, subtitleCallback, callback, customSource = actualSource)
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
