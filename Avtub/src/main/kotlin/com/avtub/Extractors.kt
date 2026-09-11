package com.avtub

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

// Data classes untuk YStream API
data class PowChallenge(
    @JsonProperty("pow_nonce") val powNonce: String,
    @JsonProperty("pow_difficulty") val powDifficulty: Int,
    @JsonProperty("pow_token") val powToken: String,
    @JsonProperty("algorithm") val algorithm: String? = null
)

data class VerifyResponse(
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("token") val token: String? = null,
    @JsonProperty("reason") val reason: String? = null
)

data class PlaybackData(
    @JsonProperty("algorithm") val algorithm: String? = null,
    @JsonProperty("iv") val iv: String? = null,
    @JsonProperty("payload") val payload: String? = null,
    @JsonProperty("key_parts") val keyParts: List<String>? = null,
    @JsonProperty("version") val version: String? = null
)

data class PlaybackResponse(
    @JsonProperty("playback") val playback: PlaybackData? = null
)

/**
 * Extractor untuk host Morencius (Vidhide Clone)
 */
open class MorenciusExtractor : ExtractorApi() {
    override val name = "Morencius"
    override val mainUrl = "https://morencius.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()
        val ref = referer ?: "https://avtub.cx/"
        val res = app.get(cleanUrl, referer = ref).text

        val unpacked = getAndUnpack(res)
        val m3u8Url = Regex("""(?:hls2|file)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(?:hls2|file)\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""").find(res)?.groupValues?.get(1)

        if (m3u8Url != null) {
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = fixUrl(m3u8Url),
                referer = "$mainUrl/",
                headers = mapOf("Origin" to mainUrl)
            ).forEach(callback)
        }
    }
}

/**
 * Extractor untuk host YStream (Byse Platform)
 * Menggunakan solver PoW native dan dekripsi AES-256-GCM
 */
open class YStreamExtractor : ExtractorApi() {
    override val name = "YStream"
    override val mainUrl = "https://ystream.id"
    override val requiresReferer = false

    companion object {
        private const val BE = 512
        private const val LT = BE - 1
        private const val DR = 2
        private const val LR = 2654435761L
        private const val HR = 2246822519L
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private fun rotl(t: Long, e: Int): Long {
            val t32 = t and 0xFFFFFFFFL
            return ((t32 shl e) or (t32 ushr (32 - e))) and 0xFFFFFFFFL
        }

        private fun imul(t: Long, e: Long): Long {
            return ((t.toInt() * e.toInt()).toLong()) and 0xFFFFFFFFL
        }

        private fun ye(t: LongArray) {
            t[0] = (t[0] + t[1]) and 0xFFFFFFFFL
            t[3] = rotl(t[3] xor t[0], 16)
            t[2] = (t[2] + t[3]) and 0xFFFFFFFFL
            t[1] = rotl(t[1] xor t[2], 12)
            t[0] = (t[0] + t[1]) and 0xFFFFFFFFL
            t[3] = rotl(t[3] xor t[0], 8)
            t[2] = (t[2] + t[3]) and 0xFFFFFFFFL
            t[1] = rotl(t[1] xor t[2], 7)
        }

        private fun gr(t: ByteArray): LongArray {
            val e = longArrayOf(1779033703L, 3144134277L, 1013904242L, 2773480762L)
            for (b in t) {
                val bVal = (b.toInt() and 0xFF).toLong()
                e[0] = (e[0] + bVal) and 0xFFFFFFFFL
                e[0] = rotl(e[0], 7)
                ye(e)
            }
            for (i in 0 until 8) {
                ye(e)
            }
            val r = LongArray(BE)
            for (i in 0 until BE) {
                ye(e)
                r[i] = (e[0] xor e[2]) and 0xFFFFFFFFL
            }
            for (i in 0 until DR) {
                for (s in 0 until BE) {
                    val a = (r[s].toInt() and LT)
                    var c = (r[s] + r[a]) and 0xFFFFFFFFL
                    c = rotl(c, 13)
                    c = (c xor imul(r[(s + 1) and LT], LR)) and 0xFFFFFFFFL
                    r[s] = c
                    e[0] = (e[0] xor c) and 0xFFFFFFFFL
                    ye(e)
                }
            }
            val n = LongArray(8)
            val o = BE / 8
            for (i in 0 until 8) {
                ye(e)
                var s = e[0]
                val a = i * o
                for (c in 0 until o) {
                    val d = r[a + c]
                    s = (s + d) and 0xFFFFFFFFL
                    s = rotl(s, 5)
                    s = (s xor imul(d, HR)) and 0xFFFFFFFFL
                }
                n[i] = (s xor e[2]) and 0xFFFFFFFFL
            }
            return n
        }

        private fun wr(t: LongArray): Int {
            var count = 0
            for (item in t) {
                val n = item.toInt()
                if (n == 0) {
                    count += 32
                    continue
                }
                return count + Integer.numberOfLeadingZeros(n)
            }
            return count
        }

        fun solvePow(nonce: String, difficulty: Int): String {
            if (difficulty <= 0) return "0"
            val prefix = "$nonce:"
            var s = 0
            while (true) {
                val input = (prefix + s).toByteArray(Charsets.ISO_8859_1)
                val d = gr(input)
                if (wr(d) >= difficulty) {
                    return s.toString()
                }
                s++
            }
        }

        private fun base64UrlDecode(str: String): ByteArray {
            var normalized = str.replace('-', '+').replace('_', '/')
            val remainder = normalized.length % 4
            if (remainder > 0) {
                normalized += "=".repeat(4 - remainder)
            }
            return android.util.Base64.decode(normalized, android.util.Base64.DEFAULT)
        }

        private fun getQa(): Map<String, Pair<Int, Int>> {
            val map = mutableMapOf<String, Pair<Int, Int>>()
            for (n in 1..20) {
                val o = n xor 0
                val a = (31 - n) xor 0
                map[n.toString()] = Pair(o, a)
            }
            return map
        }

        private fun getEa(version: String?, totalParts: Int): List<Int> {
            val ver = version?.trim().orEmpty()
            val pair = getQa()[ver] ?: return emptyList()
            val (a, i) = pair
            return if (a < 1 || i < 1 || a > totalParts || i > totalParts) emptyList() else listOf(a, i)
        }

        private fun getKeyParts(keyParts: List<String>?, version: String?): List<String> {
            val parts = keyParts ?: return emptyList()
            val indices = getEa(version, parts.size)
            if (indices.isEmpty()) return parts
            val selected = indices.mapNotNull { idx ->
                parts.getOrNull(idx - 1)?.takeIf { it.isNotEmpty() }
            }
            return if (selected.isNotEmpty()) selected else parts
        }

        fun decryptPlayback(playback: PlaybackData): String? {
            return try {
                val keyParts = getKeyParts(playback.keyParts, playback.version)
                val decodedParts = keyParts.map { base64UrlDecode(it) }
                val totalKeyLen = decodedParts.sumOf { it.size }
                val keyBytes = ByteArray(totalKeyLen)
                var offset = 0
                for (part in decodedParts) {
                    System.arraycopy(part, 0, keyBytes, offset, part.size)
                    offset += part.size
                }

                val ivBytes = base64UrlDecode(playback.iv ?: return null)
                val cipherWithTag = base64UrlDecode(playback.payload ?: return null)

                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val keySpec = SecretKeySpec(keyBytes, "AES")
                val gcmSpec = GCMParameterSpec(128, ivBytes)
                cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec)
                val decryptedBytes = cipher.doFinal(cipherWithTag)
                String(decryptedBytes, Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val code = Regex("""/(?:e|v|d|download)/([a-zA-Z0-9]+)""").find(url)?.groupValues?.get(1) ?: return

        try {
            // 1. Minta challenge PoW
            val capUrl = "$mainUrl/api/videos/$code/captcha"
            val capRes = app.post(
                capUrl,
                json = emptyMap<String, String>(),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/v/$code",
                    "Origin" to mainUrl,
                    "Content-Type" to "application/json"
                )
            ).parsedSafe<PowChallenge>() ?: return

            // 2. Selesaikan PoW
            val solution = solvePow(capRes.powNonce, capRes.powDifficulty)

            // 3. Verifikasi token
            val verifyUrl = "$mainUrl/api/videos/$code/captcha/verify"
            val verifyRes = app.post(
                verifyUrl,
                json = mapOf("pow_token" to capRes.powToken, "solution" to solution),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/v/$code",
                    "Origin" to mainUrl,
                    "Content-Type" to "application/json"
                )
            ).parsedSafe<VerifyResponse>() ?: return

            val token = verifyRes.token ?: return

            // 4. Ambil playback config terenkripsi
            val playUrl = "$mainUrl/api/videos/$code/playback"
            val playRes = app.post(
                playUrl,
                json = mapOf("fingerprint" to emptyMap<String, Any>()),
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to "$mainUrl/v/$code",
                    "Origin" to mainUrl,
                    "Content-Type" to "application/json",
                    "X-Captcha-Token" to token
                )
            ).parsedSafe<PlaybackResponse>() ?: return

            val playback = playRes.playback ?: return
            val decryptedJson = decryptPlayback(playback) ?: return

            val json = JSONObject(decryptedJson)
            val sources = json.optJSONArray("sources") ?: return
            for (i in 0 until sources.length()) {
                val s = sources.optJSONObject(i) ?: continue
                val streamUrl = s.optString("url")
                if (streamUrl.isNotBlank()) {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = streamUrl,
                        referer = "$mainUrl/",
                        headers = mapOf("Origin" to mainUrl)
                    ).forEach(callback)
                }
            }
        } catch (_: Exception) {
            // Ignore error
        }
    }
}
