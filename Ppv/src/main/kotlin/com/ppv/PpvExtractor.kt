package com.ppv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URI

open class PpvExtractor : ExtractorApi() {
    override val name = "PPV Embed"
    override val mainUrl = "https://embedindia.st"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ref = referer ?: "https://ppv.st/"
        resolveStream(cleanUrl, ref, callback)
    }

    suspend fun resolveStream(
        cleanUrl: String,
        ref: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (cleanUrl.contains(".m3u8")) {
            val gen = M3u8Helper.generateM3u8(name, cleanUrl, ref)
            if (gen.isNotEmpty()) {
                gen.forEach(callback)
                return true
            }
        }

        return try {
            val resolver = WebViewResolver(
                interceptUrl = Regex(""".*\.(m3u8|mpd)(\?.*)?$"""),
                additionalUrls = listOf(Regex(""".*\.(m3u8|mpd).*""")),
                useOkhttp = false
            )
            val response = app.get(cleanUrl, referer = ref, interceptor = resolver)
            val streamUrl = response.url
            if (streamUrl.isNotBlank() && (streamUrl.contains(".m3u8") || streamUrl.contains(".mpd"))) {
                val isMpd = streamUrl.contains(".mpd")
                val origin = try {
                    val uri = URI(cleanUrl)
                    "${uri.scheme}://${uri.host}"
                } catch (_: Exception) {
                    cleanUrl
                }
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
                            "Origin" to origin,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                        )
                        this.quality = Qualities.P1080.value
                    }
                )
                true
            } else {
                false
            }
        } catch (_: Exception) {
            false
        }
    }
}
