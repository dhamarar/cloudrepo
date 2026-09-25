package com.timstreams

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

open class TimStreamsExtractor : ExtractorApi() {
    override val name = "TimStreams"
    override val mainUrl = "https://exmxbxe.cfd"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ref = referer ?: "https://timst.cfd/"
        extractStream(cleanUrl, ref, callback)
    }

    private val streamPattern = Regex(
        """var\s+([a-zA-Z0-9_$]+)\s*=\s*\[([0-9,\s]+)\],\s*([a-zA-Z0-9_$]+)\s*=\s*(\d+),\s*([a-zA-Z0-9_$]+)\s*=\s*(\d+)"""
    )
    private val signedUrlPattern = Regex("""SIGNED_URL\s*=\s*["']([^"']+\.m3u8[^"']*)["']""")
    private val fileM3u8Pattern = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")

    suspend fun extractStream(
        embedUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val response = app.get(
                embedUrl,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                )
            )
            val pageHtml = response.text

            // 1. Cek pola obfuscated JavaScript
            val match = streamPattern.find(pageHtml)
            if (match != null) {
                val arrStr = match.groupValues[2]
                val key1 = match.groupValues[4].toIntOrNull() ?: return false
                val key2 = match.groupValues[6].toIntOrNull() ?: return false
                val nums = arrStr.split(",").mapNotNull { it.trim().toIntOrNull() }
                if (nums.isEmpty()) return false

                val decrypted = buildString(nums.size) {
                    for (num in nums) {
                        val charCode = (((num xor key1) - key2 + 256) % 256)
                        append(charCode.toChar())
                    }
                }

                val m3u8Url = signedUrlPattern.find(decrypted)?.groupValues?.get(1)
                    ?: fileM3u8Pattern.find(decrypted)?.groupValues?.get(1)

                if (!m3u8Url.isNullOrBlank()) {
                    val fixedM3u8 = fixUrl(m3u8Url)
                    val origin = try {
                        val uri = URI(embedUrl)
                        "${uri.scheme}://${uri.host}"
                    } catch (_: Exception) {
                        embedUrl
                    }

                    val headersMap = mapOf(
                        "Referer" to embedUrl,
                        "Origin" to origin,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
                    )

                    var linkEmitted = false
                    try {
                        val generated = M3u8Helper.generateM3u8(
                            source = "TimStreams",
                            streamUrl = fixedM3u8,
                            referer = embedUrl,
                            headers = headersMap
                        )
                        if (generated.isNotEmpty()) {
                            generated.forEach {
                                callback(it)
                                linkEmitted = true
                            }
                        }
                    } catch (_: Exception) {}

                    // Fallback ke direct ExtractorLink jika generateM3u8 tidak menghasilkan varian
                    if (!linkEmitted) {
                        callback(
                            newExtractorLink(
                                name = "TimStreams Live HLS",
                                source = "TimStreams",
                                url = fixedM3u8,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = embedUrl
                                this.headers = headersMap
                                this.quality = Qualities.P1080.value
                            }
                        )
                    }
                    return true
                }
            }

            // 2. Cek apakah ada direct m3u8 pada HTML
            val directM3u8 = fileM3u8Pattern.find(pageHtml)?.groupValues?.get(1)
            if (!directM3u8.isNullOrBlank()) {
                callback(
                    newExtractorLink(
                        name = "TimStreams HLS",
                        source = "TimStreams",
                        url = fixUrl(directM3u8),
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = embedUrl
                        this.quality = Qualities.P1080.value
                    }
                )
                return true
            }

            false
        } catch (_: Exception) {
            false
        }
    }
}
