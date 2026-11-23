package com.semprot

import android.webkit.CookieManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Extractor untuk host Vidara (vidara.to / vidara.so / bnvcq8s.com)
 */
open class VidaraExtractor : ExtractorApi() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.to"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithSource(url, referer, name, subtitleCallback, callback)
    }

    suspend fun extractWithSource(
        url: String,
        referer: String?,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.trim()
        val embedOrigin = Regex("""^(https?://[^/]+)""").find(cleanUrl)?.groupValues?.get(1) ?: mainUrl
        val filecode = Regex("""/(?:e|v|d)/([a-zA-Z0-9]+)""").find(cleanUrl)?.groupValues?.get(1) ?: return false

        return try {
            val resText = app.post(
                "$embedOrigin/api/stream",
                json = mapOf(
                    "filecode" to filecode,
                    "device" to "desktop",
                    "codecs" to emptyList<String>()
                ),
                headers = mapOf(
                    "Referer" to cleanUrl,
                    "Origin" to embedOrigin,
                    "Content-Type" to "application/json",
                    "User-Agent" to SemprotSettings.DEFAULT_USER_AGENT
                )
            ).text

            val json = JSONObject(resText)
            val streamUrl = json.optString("streaming_url")
            if (streamUrl.isNotBlank()) {
                val links = M3u8Helper.generateM3u8(
                    source = sourceLabel,
                    streamUrl = streamUrl,
                    referer = "$embedOrigin/",
                    headers = mapOf("Origin" to embedOrigin)
                )
                links.forEach(callback)
                links.isNotEmpty()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e("VidaraExtractor", "Failed for $cleanUrl: ${e.message}")
            false
        }
    }
}

class VidaraSoExtractor : VidaraExtractor() {
    override val name = "Vidara"
    override val mainUrl = "https://vidara.so"
}

/**
 * Extractor untuk host Playmogo (playmogo.com - Doodstream clone di balik Cloudflare)
 */
open class PlaymogoExtractor : ExtractorApi() {
    override val name = "Playmogo"
    override val mainUrl = "https://playmogo.com"
    override val requiresReferer = true

    companion object {
        private const val TAG = "PlaymogoExtractor"
        private val PASS_MD5_REGEX = Regex("""(/pass_md5/[^'"\s\\]+)""")
        private val TOKEN_REGEX = Regex("""[?&]token=([a-zA-Z0-9]+)""")
        private val MEDIA_URL_REGEX = Regex("""\.(?:mp4|m3u8|mkv|webm)(?:[?#].*)?$""", RegexOption.IGNORE_CASE)
        private val ANY_URL = Regex(".*")
        private val NEVER = Regex("^a^")

        private val PROBE_JS = """
            (function() {
                try {
                    var html = document.documentElement ? document.documentElement.innerHTML : '';
                    var m = html.match(/\/pass_md5\/[^'"\s\\]+/);
                    var vid = document.querySelector('video source, video');
                    var vsrc = vid ? (vid.currentSrc || vid.src || '') : '';
                    return JSON.stringify({ passMd5: m ? m[0] : '', videoSrc: vsrc });
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()

        private fun randomToken(len: Int = 10): String {
            val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
            return buildString(len) {
                repeat(len) { append(chars.random()) }
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extractWithSource(url, referer, name, subtitleCallback, callback)
    }

    private fun normalizeEmbedUrl(rawUrl: String): String {
        val clean = rawUrl.trim()
        val id = Regex("""/(?:e|d|f)/([a-zA-Z0-9]+)""").find(clean)?.groupValues?.get(1)
        val origin = Regex("""^(https?://[^/]+)""").find(clean)?.groupValues?.get(1) ?: mainUrl
        return if (id != null && !clean.contains("/f/")) {
            "$origin/e/$id"
        } else {
            clean
        }
    }

    private fun getStoredPlaymogoCookie(origin: String): String? = runCatching {
        CookieManager.getInstance().getCookie(origin)?.takeIf { it.isNotBlank() }
    }.getOrNull()

    suspend fun extractWithSource(
        url: String,
        referer: String?,
        sourceLabel: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = normalizeEmbedUrl(url)
        val embedOrigin = Regex("""^(https?://[^/]+)""").find(targetUrl)?.groupValues?.get(1) ?: mainUrl
        val ua = WebViewResolver.webViewUserAgent ?: SemprotSettings.DEFAULT_USER_AGENT
        val savedCookie = getStoredPlaymogoCookie(embedOrigin)

        // 1. Jalur cepat HTTP langsung (jika tidak kena challenge atau sudah punya cf_clearance di CookieManager)
        val directHeaders = buildMap {
            put("User-Agent", ua)
            put("Referer", referer ?: "$embedOrigin/")
            if (!savedCookie.isNullOrBlank()) put("Cookie", savedCookie)
        }

        val directSuccess = runCatching {
            val res = app.get(targetUrl, headers = directHeaders)
            if (res.code in 200..299 && !res.text.contains("Just a moment", ignoreCase = true)) {
                val html = res.text
                // Jika halaman /f/<id>, cari tautan /e/<id> di dalamnya
                if (targetUrl.contains("/f/")) {
                    val innerEmbed = Regex("""https?://[^"'\s<>]+/e/[a-zA-Z0-9]+""").find(html)?.value
                    if (innerEmbed != null) {
                        return@runCatching extractWithSource(innerEmbed, targetUrl, sourceLabel, subtitleCallback, callback)
                    }
                }
                resolveFromHtml(html, targetUrl, embedOrigin, ua, savedCookie, sourceLabel, callback)
            } else {
                false
            }
        }.getOrDefault(false)

        if (directSuccess) return true

        // 2. Fallback WebViewResolver untuk melewati Cloudflare JS challenge & menangkap /pass_md5/ atau media URL
        Log.d(TAG, "Falling back to WebViewResolver for $targetUrl")
        val passMd5Hit = AtomicReference<String?>(null)
        val videoSrcHit = AtomicReference<String?>(null)
        val mediaHits = CopyOnWriteArrayList<Request>()

        val resolver = WebViewResolver(
            interceptUrl = NEVER,
            additionalUrls = listOf(ANY_URL),
            userAgent = null,
            useOkhttp = false,
            script = PROBE_JS,
            scriptCallback = { payload ->
                val trimmed = payload?.trim().orEmpty()
                if (trimmed.isNotEmpty() && trimmed != "null") {
                    val unquoted = if (trimmed.startsWith("\"")) {
                        runCatching { org.json.JSONTokener(trimmed).nextValue() as? String }.getOrNull() ?: trimmed
                    } else {
                        trimmed
                    }
                    runCatching {
                        val json = JSONObject(unquoted)
                        val p = json.optString("passMd5")
                        val v = json.optString("videoSrc")
                        if (p.isNotBlank()) passMd5Hit.compareAndSet(null, p)
                        if (v.isNotBlank() && v.startsWith("http")) videoSrcHit.compareAndSet(null, v)
                    }
                }
            },
            timeout = 22_000L
        )

        val (_, seen) = runCatching {
            resolver.resolveUsingWebView(
                url = targetUrl,
                referer = referer ?: "$embedOrigin/",
                headers = directHeaders,
                requestCallBack = { req ->
                    val reqUrl = req.url.toString()
                    if (reqUrl.contains("/pass_md5/")) {
                        passMd5Hit.compareAndSet(null, reqUrl)
                    }
                    if (MEDIA_URL_REGEX.containsMatchIn(reqUrl) || reqUrl.contains("videoplayback")) {
                        mediaHits.add(req)
                    }
                    mediaHits.isNotEmpty() || !videoSrcHit.get().isNullOrBlank()
                }
            )
        }.getOrElse {
            Log.e(TAG, "WebViewResolver failed: ${it.message}")
            return false
        }

        val effectiveUa = WebViewResolver.webViewUserAgent ?: ua
        val webViewCookie = getStoredPlaymogoCookie(embedOrigin) ?: savedCookie

        // Prioritas A: URL media langsung tertangkap oleh WebView
        val directMediaUrl = videoSrcHit.get()
            ?: mediaHits.firstOrNull()?.url?.toString()
            ?: seen.firstOrNull { MEDIA_URL_REGEX.containsMatchIn(it.url.toString()) }?.url?.toString()

        if (!directMediaUrl.isNullOrBlank()) {
            val linkType = if (directMediaUrl.contains(".m3u8", ignoreCase = true)) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
            val hdrs = buildMap {
                put("User-Agent", effectiveUa)
                put("Referer", "$embedOrigin/")
                if (!webViewCookie.isNullOrBlank()) put("Cookie", webViewCookie)
            }
            if (linkType == ExtractorLinkType.M3U8) {
                M3u8Helper.generateM3u8(
                    source = sourceLabel,
                    streamUrl = directMediaUrl,
                    referer = "$embedOrigin/",
                    headers = hdrs
                ).forEach(callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = sourceLabel,
                        name = sourceLabel,
                        url = directMediaUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "$embedOrigin/"
                        this.headers = hdrs
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }

        // Prioritas B: Jika pass_md5 path ditemukan oleh JS probe / network intercept
        val passMd5Raw = passMd5Hit.get()
        if (!passMd5Raw.isNullOrBlank()) {
            val passMd5Url = if (passMd5Raw.startsWith("http")) passMd5Raw else "$embedOrigin$passMd5Raw"
            return resolvePassMd5(
                passMd5Url = passMd5Url,
                token = TOKEN_REGEX.find(passMd5Url)?.groupValues?.get(1) ?: passMd5Url.substringAfterLast("/"),
                embedUrl = targetUrl,
                embedOrigin = embedOrigin,
                ua = effectiveUa,
                cookie = webViewCookie,
                sourceLabel = sourceLabel,
                callback = callback
            )
        }

        return false
    }

    private suspend fun resolveFromHtml(
        html: String,
        embedUrl: String,
        embedOrigin: String,
        ua: String,
        cookie: String?,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val unpacked = runCatching { getAndUnpack(html) }.getOrDefault("")
        val combined = "$html\n$unpacked"

        // Pola standar Doodstream /pass_md5/<hash>/<token>
        val passMd5Path = PASS_MD5_REGEX.find(combined)?.groupValues?.get(1)
        if (!passMd5Path.isNullOrBlank()) {
            val passMd5Url = if (passMd5Path.startsWith("http")) passMd5Path else "$embedOrigin$passMd5Path"
            val token = Regex("""makePlay[^}]*?([a-zA-Z0-9]{10,})""").find(combined)?.groupValues?.get(1)
                ?: passMd5Path.substringAfterLast("/")
            return resolvePassMd5(passMd5Url, token, embedUrl, embedOrigin, ua, cookie, sourceLabel, callback)
        }

        // Fallback: cari langsung URL .m3u8 atau .mp4 di HTML / unpacked script
        val m3u8Url = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(combined)?.groupValues?.get(1)
        if (!m3u8Url.isNullOrBlank()) {
            M3u8Helper.generateM3u8(
                source = sourceLabel,
                streamUrl = m3u8Url,
                referer = "$embedOrigin/",
                headers = buildMap {
                    put("User-Agent", ua)
                    put("Origin", embedOrigin)
                    if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                }
            ).forEach(callback)
            return true
        }

        val mp4Url = Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(combined)?.groupValues?.get(1)
        if (!mp4Url.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = sourceLabel,
                    name = sourceLabel,
                    url = mp4Url,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$embedOrigin/"
                    this.headers = buildMap {
                        put("User-Agent", ua)
                        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
                    }
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        return false
    }

    private suspend fun resolvePassMd5(
        passMd5Url: String,
        token: String,
        embedUrl: String,
        embedOrigin: String,
        ua: String,
        cookie: String?,
        sourceLabel: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val headers = buildMap {
                put("User-Agent", ua)
                put("Referer", embedUrl)
                if (!cookie.isNullOrBlank()) put("Cookie", cookie)
            }
            val basePrefix = app.get(passMd5Url, headers = headers).text.trim()
            if (!basePrefix.startsWith("http")) return false

            val finalUrl = "$basePrefix${randomToken(10)}?token=$token&expiry=${System.currentTimeMillis()}"
            val mediaHeaders = buildMap {
                put("User-Agent", ua)
                put("Referer", "$embedOrigin/")
                if (!cookie.isNullOrBlank()) put("Cookie", cookie)
            }

            callback.invoke(
                newExtractorLink(
                    source = sourceLabel,
                    name = sourceLabel,
                    url = finalUrl,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = "$embedOrigin/"
                    this.headers = mediaHeaders
                    this.quality = Qualities.Unknown.value
                }
            )
            true
        } catch (e: Exception) {
            Log.e(TAG, "resolvePassMd5 error: ${e.message}")
            false
        }
    }
}
