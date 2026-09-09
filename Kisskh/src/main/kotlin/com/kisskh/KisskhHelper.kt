package com.kisskh

import com.fasterxml.jackson.annotation.JsonProperty
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable

data class DramaListResponse(
    @JsonProperty("page") val page: Int? = null,
    @JsonProperty("pageSize") val pageSize: Int? = null,
    @JsonProperty("totalCount") val totalCount: Int? = null,
    @JsonProperty("data") val data: List<DramaItem>? = null
)

data class DramaItem(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("episodesCount") val episodesCount: Int? = null,
    @JsonProperty("label") val label: String? = null
)

data class DramaDetail(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("releaseDate") val releaseDate: String? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("country") val country: String? = null,
    @JsonProperty("type") val type: String? = null,
    @JsonProperty("episodes") val episodes: List<EpisodeItem>? = null,
    @JsonProperty("episodesCount") val episodesCount: Int? = null
)

data class EpisodeItem(
    @JsonProperty("id") val id: Long? = null,
    @JsonProperty("number") val number: Double? = null,
    @JsonProperty("sub") val sub: Int? = null
)

data class VideoResponse(
    @JsonProperty("Video") val Video: String? = null,
    @JsonProperty("Video_tmp") val Video_tmp: String? = null,
    @JsonProperty("ThirdParty") val ThirdParty: String? = null,
    @JsonProperty("Type") val Type: Int? = null
)

data class SubtitleItem(
    @JsonProperty("src") val src: String? = null,
    @JsonProperty("label") val label: String? = null,
    @JsonProperty("land") val land: String? = null,
    @JsonProperty("default") val default: Boolean? = null
)

data class KisskhEpisodeData(
    @JsonProperty("dramaId") val dramaId: Long,
    @JsonProperty("episodeId") val episodeId: Long,
    @JsonProperty("number") val number: Double
)

object KisskhHelper {

    private const val JS_CODE = """
if (!String.prototype.padStart) {
    String.prototype.padStart = function(len, pad) {
        pad = pad || ' ';
        var s = String(this);
        while (s.length < len) s = pad + s;
        return s;
    };
}
function stringToWords(s) {
    var len = s.length;
    var words = [];
    for (var i = 0; i < len; i++) {
        words[i >>> 2] |= (0xff & s.charCodeAt(i)) << (24 - (i % 4) * 8);
    }
    return [words, len];
}
function wordsToHex(words, byteLength) {
    var hex = [];
    for (var i = 0; i < byteLength; i++) {
        var b = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 0xff;
        hex.push(b.toString(16).padStart(2, '0'));
    }
    return hex.join('');
}
function trim48(s) {
    return (s || '').substring(0, 48);
}
function hashString(s) {
    var hash = 0;
    for (var i = 0; i < s.length; i++) {
        hash = (hash << 5) - hash + s.charCodeAt(i);
    }
    return hash;
}
function padString(s) {
    var padLen = 16 - (s.length % 16);
    for (var i = 0; i < padLen; i++) {
        s += String.fromCharCode(padLen);
    }
    return s;
}
var keySchedule = [
  0x4f6bdaa3, -0x61d07350, 0x7f5e722d, -0x61210cec,
  0x536620a8, -0x32b653e8, -0x4de821cb, 0x2cc92d21,
  -0x73412227, 0x41f771c1, -0xc1f500c, -0x20d67d2b,
  0x2dadde47, 0x6c5aaf86, -0x6045ff8e, 0x409382a7,
  -0x6417db2, -0x6a1bd238, 0xa5e2dba, 0x4acdaf1d,
  0x54c72698, -0x3edcf4b0, -0x3482d916, -0x7e4f7609,
  -0x6c9fb16c, 0x524345c4, -0x66c19cd2, 0x188eead9,
  -0x351884c7, -0x675bc103, 0x19a5dd3, 0x1914b70a,
  -0x4fb1e313, 0x28ea2210, 0x29707fc3, 0x3064c8c9,
  -0x17593e17, -0x3fb31c07, -0x16c363c6, -0x26a7ab0d,
  -0x4b793324, 0x74ca2f25, -0x62094ce1, 0x44aee7ec
];
var T0 = [], T1 = [], T2 = [], T3 = [], SBox = [], d = [];
for (var i = 0; i < 256; i++) d[i] = i < 128 ? i << 1 : (i << 1) ^ 0x11b;
var p = 0, q = 0;
for (var i = 0; i < 256; i++) {
    var s = q ^ (q << 1) ^ (q << 2) ^ (q << 3) ^ (q << 4);
    s = (s >>> 8) ^ (0xff & s) ^ 0x63;
    SBox[p] = s;
    var x = d[p], y = d[d[x]], z = 0x101 * d[s] ^ 0x1010100 * s;
    T0[p] = (z << 24) | (z >>> 8);
    T1[p] = (z << 16) | (z >>> 16);
    T2[p] = (z << 8) | (z >>> 24);
    T3[p] = z;
    p ? (p = x ^ d[d[d[y ^ x]]], q ^= d[d[q]]) : (p = q = 1);
}
function encryptBlock(words, offset) {
    var iv;
    if (offset === 0) {
        iv = [0x1504af3, 0x56e619cf, 0x2e42bba6, -0x73c08f07];
    } else {
        iv = words.slice(offset - 4, offset);
    }
    for (var i = 0; i < 4; i++) words[offset + i] ^= iv[i];
    var s0 = words[offset] ^ keySchedule[0];
    var s1 = words[offset + 1] ^ keySchedule[1];
    var s2 = words[offset + 2] ^ keySchedule[2];
    var s3 = words[offset + 3] ^ keySchedule[3];
    var k = 4;
    for (var round = 1; round < 10; round++) {
        var t0 = T0[s0 >>> 24] ^ T1[(s1 >>> 16) & 0xff] ^ T2[(s2 >>> 8) & 0xff] ^ T3[s3 & 0xff] ^ keySchedule[k++];
        var t1 = T0[s1 >>> 24] ^ T1[(s2 >>> 16) & 0xff] ^ T2[(s3 >>> 8) & 0xff] ^ T3[s0 & 0xff] ^ keySchedule[k++];
        var t2 = T0[s2 >>> 24] ^ T1[(s3 >>> 16) & 0xff] ^ T2[(s0 >>> 8) & 0xff] ^ T3[s1 & 0xff] ^ keySchedule[k++];
        s3 = T0[s3 >>> 24] ^ T1[(s0 >>> 16) & 0xff] ^ T2[(s1 >>> 8) & 0xff] ^ T3[s2 & 0xff] ^ keySchedule[k++];
        s0 = t0; s1 = t1; s2 = t2;
    }
    var t0 = ((SBox[s0 >>> 24] << 24) | (SBox[(s1 >>> 16) & 0xff] << 16) | (SBox[(s2 >>> 8) & 0xff] << 8) | SBox[s3 & 0xff]) ^ keySchedule[k++];
    var t1 = ((SBox[s1 >>> 24] << 24) | (SBox[(s2 >>> 16) & 0xff] << 16) | (SBox[(s3 >>> 8) & 0xff] << 8) | SBox[s0 & 0xff]) ^ keySchedule[k++];
    var t2 = ((SBox[s2 >>> 24] << 24) | (SBox[(s3 >>> 16) & 0xff] << 16) | (SBox[(s0 >>> 8) & 0xff] << 8) | SBox[s1 & 0xff]) ^ keySchedule[k++];
    s3 = ((SBox[s3 >>> 24] << 24) | (SBox[(s0 >>> 16) & 0xff] << 16) | (SBox[(s1 >>> 8) & 0xff] << 8) | SBox[s2 & 0xff]) ^ keySchedule[k++];
    words[offset] = t0;
    words[offset + 1] = t1;
    words[offset + 2] = t2;
    words[offset + 3] = s3;
}
function getKey(episodeId, isSub) {
    var guid = isSub ? 'VgV52sWhwvBSf8BsM3BRY9weWiiCbtGp' : '62f176f3bb1b5b8e70e39932ad34a0c7';
    var appVer = '2.8.10';
    var platformVer = 4830201;
    var appName = 'kisskh';
    var parts = [
        '', episodeId, null, 'mg3c3b04ba', appVer, guid, platformVer,
        trim48(appName), trim48((appName || '').toLowerCase()), trim48(appName),
        appName, appName, appName, '00', ''
    ];
    var hash = hashString(parts.join('|'));
    parts.splice(1, 0, hash);
    var padded = padString(parts.join('|'));
    var r = stringToWords(padded);
    var words = r[0];
    var byteLen = r[1];
    for (var i = 0; i < words.length; i += 4) {
        encryptBlock(words, i);
    }
    return wordsToHex(words, byteLen).toUpperCase();
}
"""

    private var cachedScope: Scriptable? = null

    @Synchronized
    private fun getScope(): Scriptable {
        cachedScope?.let { return it }
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            val scope: Scriptable = cx.initStandardObjects()
            cx.evaluateString(scope, JS_CODE, "kisskh_crypto", 1, null)
            cachedScope = scope
            scope
        } finally {
            Context.exit()
        }
    }

    fun getKkey(episodeId: Long, isSub: Boolean = false): String? {
        val cx = Context.enter()
        return try {
            cx.optimizationLevel = -1
            val scope = getScope()
            val res = cx.evaluateString(scope, "getKey($episodeId, $isSub);", "get_key", 1, null)
            res?.toString()
        } catch (e: Throwable) {
            e.printStackTrace()
            null
        } finally {
            Context.exit()
        }
    }
}
