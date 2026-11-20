// ! Bu araç @ByAyzen tarafından | @Cs-GizliKeyif için yazılmıştır.

package com.byayzen

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap

// Site Bunny Shield arkasinda: sadece UA+Referer ile gelen istekleri PoW challenge'ina (403, CDN-Challenge)
// dusuruyor, tarayici gibi basliklar tasiyan istegi gecirir. WebView/PoW cozumu gerekmiyor.
class BunnyShieldKiller : Interceptor {
    companion object {
        // Sayfa, poster ve video istekleri ayni masaustu UA ile gider.
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/149.0.0.0 Safari/537.36"
    }

    // Site cerezleri (kt_acctoken, PHPSESSID, bunny_shield_id_...). Video URL'sindeki v-acctoken bunlara bagli.
    private val jar = ConcurrentHashMap<String, String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(withBrowserHeaders(chain.request()))
        response.headers("Set-Cookie").forEach { line ->
            val pair = line.substringBefore(";")
            val name = pair.substringBefore("=").trim()
            val value = pair.substringAfter("=", "").trim()
            if (name.isEmpty()) return@forEach
            if (value.isEmpty()) jar.remove(name) else jar[name] = value
        }
        return response
    }

    private fun withBrowserHeaders(request: Request): Request {
        val isMedia = request.url.encodedPath.contains("/get_file/") ||
            request.header("Sec-Fetch-Dest").equals("video", ignoreCase = true)

        val b = request.newBuilder()
            .header("User-Agent", USER_AGENT)
            .header("Accept-Language", "en-US,en;q=0.9")

        if (isMedia) {
            b.header("Accept", "*/*")
                .header("Sec-Fetch-Dest", "video")
                .header("Sec-Fetch-Mode", "no-cors")
                .header("Sec-Fetch-Site", "same-origin")
                .removeHeader("Upgrade-Insecure-Requests")
                .removeHeader("Sec-Fetch-User")
        } else {
            b.header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Sec-Fetch-User", "?1")
        }

        mergedCookieHeader(request.header("Cookie"))?.let { b.header("Cookie", it) }
        return b.build()
    }

    private fun mergedCookieHeader(existing: String?): String? {
        if (existing.isNullOrBlank() && jar.isEmpty()) return null
        val merged = LinkedHashMap<String, String>()
        existing?.split(";")?.forEach { part ->
            val name = part.substringBefore("=").trim()
            val value = part.substringAfter("=", "").trim()
            if (name.isNotEmpty() && value.isNotEmpty()) merged[name] = value
        }
        jar.forEach { (k, v) -> merged[k] = v }
        return merged.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { it.isNotEmpty() }
    }

    private fun cookies(): String? =
        jar.entries.joinToString("; ") { "${it.key}=${it.value}" }.takeIf { it.isNotEmpty() }

    /** Oynatici (get_file) icin basliklar: medya istegi gibi gorunmeli, yoksa Shield 403 verir. */
    fun mediaHeaders(referer: String): Map<String, String> = buildMap {
        put("Referer", referer)
        put("User-Agent", USER_AGENT)
        put("Accept", "*/*")
        put("Accept-Language", "en-US,en;q=0.9")
        put("Sec-Fetch-Dest", "video")
        put("Sec-Fetch-Mode", "no-cors")
        put("Sec-Fetch-Site", "same-origin")
        cookies()?.let { put("Cookie", it) }
    }
}
