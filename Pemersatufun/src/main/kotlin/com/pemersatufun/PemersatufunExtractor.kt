package com.pemersatufun

import com.lagradost.cloudstream3.app

/**
 * Sumber video Pemersatufun ada di luluvdo.com, dan URL HLS-nya tidak ditulis
 * langsung di HTML melainkan diselipkan pada konfigurasi JW Player yang
 * diobfuscate memakai packer Dean Edwards:
 *
 *     eval(function(p,a,c,k,e,d){...}('jwplayer("vplayer").setup({sources:[{file:"..."}]...',36,292,'...'.split('|')))
 *
 * Jadi langkahnya: ambil halaman embed -> unpack payload -> baca field `file`.
 */
object PemersatufunExtractor {

    /** Payload packer: p, a, c, k (dipisah `|`) lalu `.split('|')`. */
    private val PACKER_REGEX = Regex(
        """eval\(function\(p,a,c,k,e,d\)\{[\s\S]*?\}\('([\s\S]*?)',(\d+),(\d+),'([\s\S]*?)'\.split\('\|'\)\)\)"""
    )

    /** Token packer selalu alfanumerik (indeks basis-36). */
    private val TOKEN_REGEX = Regex("[0-9a-zA-Z]+")

    private val M3U8_IN_CONFIG = Regex("""file\s*:\s*["'](https?://[^"']+\.m3u8[^"']*)["']""")

    /** Cadangan kalau struktur konfigurasi berubah tapi URL-nya masih ada di halaman. */
    private val M3U8_ANYWHERE = Regex("""https?://[^"'\s\\]+?\.m3u8[^"'\s\\]*""")

    suspend fun getStream(embedUrl: String, headers: Map<String, String>): String? {
        val html = runCatching {
            app.get(embedUrl, headers = headers, timeout = 25).text
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null

        val unpacked = unpackPacker(html)
        if (unpacked != null) {
            M3U8_IN_CONFIG.find(unpacked)?.groupValues?.get(1)?.let { return it }
        }
        return M3U8_ANYWHERE.find(unpacked ?: html)?.value
    }

    /**
     * Implementasi packer Dean Edwards: setiap token alfanumerik pada payload
     * adalah indeks basis-`base` ke dalam kamus `k`. Token yang indeksnya di luar
     * kamus (atau entri kamusnya kosong) dibiarkan apa adanya, sama seperti JS aslinya.
     */
    fun unpackPacker(source: String): String? {
        val match = PACKER_REGEX.find(source) ?: return null

        // Di dalam HTML, string payload masih ter-escape sebagai literal JS.
        val payload = match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
        val base = match.groupValues[2].toIntOrNull() ?: 36
        if (base !in 2..36) return payload

        val dictionary = match.groupValues[4].split("|")

        return TOKEN_REGEX.replace(payload) { token ->
            val index = token.value.toIntOrNull(base) ?: return@replace token.value
            dictionary.getOrNull(index)?.takeIf { it.isNotEmpty() } ?: token.value
        }
    }
}
