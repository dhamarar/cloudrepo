package com.streamed

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver

open class EmbedStExtractor : ExtractorApi() {
    override val name = "EmbedSt"
    override val mainUrl = "https://embed.st"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ref = referer ?: "https://streamed.pk/"

        // 1. Cek apakah ini stream golf (dapat diekstrak langsung tanpa WebView)
        if (cleanUrl.contains("/golf/")) {
            val resolved = extractGolfStream(cleanUrl, ref)
            if (resolved != null) {
                callback(
                    newExtractorLink(
                        name = "$name - Golf HLS",
                        source = name,
                        url = resolved,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://exposestrat.com/"
                        this.headers = mapOf(
                            "Referer" to "https://exposestrat.com/",
                            "Origin" to "https://exposestrat.com",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                        this.quality = Qualities.P720.value
                    }
                )
                return
            }
        }

        // 2. Gunakan WebViewResolver untuk menangkap manifest .m3u8 / .mpd dari lock.wasm / player JS
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex(""".*\.(m3u8|mpd)(\?.*)?$"""),
                additionalUrls = listOf(Regex(""".*\.(m3u8|mpd).*""")),
                useOkhttp = false
            )
            val response = app.get(cleanUrl, referer = ref, interceptor = resolver)
            val streamUrl = response.url
            if (streamUrl.isNotBlank() && (streamUrl.contains(".m3u8") || streamUrl.contains(".mpd"))) {
                val isMpd = streamUrl.contains(".mpd")
                callback(
                    newExtractorLink(
                        name = if (isMpd) "$name - DASH" else "$name - HLS",
                        source = name,
                        url = streamUrl,
                        type = if (isMpd) ExtractorLinkType.DASH else ExtractorLinkType.M3U8
                    ) {
                        this.referer = cleanUrl
                        this.headers = mapOf(
                            "Referer" to cleanUrl,
                            "Origin" to "https://embed.st",
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
            }
        } catch (_: Exception) {
            // Fallback or ignore
        }
    }

    private suspend fun extractGolfStream(embedUrl: String, referer: String): String? {
        return try {
            val pageHtml = app.get(embedUrl, referer = referer).text
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(pageHtml)?.groupValues?.get(1)
                ?: return null
            val fixedIframe = fixUrl(iframeSrc)

            val iframeHtml = app.get(fixedIframe, referer = embedUrl).text
            val fid = Regex("""fid=["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                ?: return null

            val maestroUrl = "https://exposestrat.com/maestrohd1.php?player=desktop&live=$fid"
            val maestroJs = app.get(maestroUrl, referer = fixedIframe).text

            val arrayMatch = Regex("""return\(\[([^\]]+)\]\.join\(['"]['"]\)\)""").find(maestroJs)
            if (arrayMatch != null) {
                val arrayContent = arrayMatch.groupValues[1]
                val parts = Regex("""["']([^"']+)["']""").findAll(arrayContent).map { it.groupValues[1] }.joinToString("")
                if (parts.startsWith("http")) parts else null
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
