package com.indoav

import android.util.Base64
import java.security.SecureRandom
import java.util.Calendar
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Seluruh rutin kripto IndoAV.
 *
 * Diambil dari bundle player `https://www.indoav.com/dist/js/file.<hash>.js`:
 *
 *  1. Token permintaan stream (`POST /video/load/<token>/`) dibuat dua tahap:
 *     payload -> base64 -> dibalik -> RC4 -> base64 -> dibalik.
 *  2. Balasan server juga berupa base64 yang dibalik lalu didekripsi RC4.
 *  3. Segmen `.ts` dikirim sebagai berkas JPEG (header `FF D8 .. FF D9`) yang isi
 *     setelahnya dienkripsi AES-CBC dengan kunci & IV tetap.
 *
 * Catatan penting soal AES: implementasi milik situs hanya membaca 4 word pertama
 * dari kunci hasil derivasi (lihat `uint8ArrayToUint32Array_`), jadi walaupun seed
 * menghasilkan 32 byte, yang benar-benar dipakai hanya 16 byte pertama (AES-128).
 */
object IndoavCrypto {

    /** Konstanta mentah dari bundle player. */
    private const val TOKEN_SEED =
        "rqpSaEddZ156f342cjwOD8vc4/SYtI0ILIo5UUj45apkqA06FzRKvr92GErrdKGZozMV1L52EueOl7B7yO1efjk8uBhSzLOf"
    private const val SEG_KEY_SEED =
        "7zavSRLyuvK8CqSKj1B511h81JudQwGap+5qNjn1OM4/kLHuQl8EzjYTSaa+88TgRWzS/o3jlLlc89UVc/K/Zw=="
    private const val SEG_IV_SEED =
        "52P6QESp76DtW/WJj1d10Astjp2bT1XL87ZuMWunYM0="

    /**
     * Hasil dari `atob(rc4(atob(TOKEN_SEED), ""))`.
     *
     * Dipatok langsung karena RC4 dengan kunci kosong membuat `i % key.length` bernilai
     * NaN di JavaScript, sehingga tidak bisa direproduksi dengan RC4 biasa.
     *
     * Karena itu [rc4] mengembalikan `input` apa adanya saat kuncinya kosong. Jangan
     * pernah menurunkan kunci ini saat runtime — nilainya sudah diverifikasi
     * byte-per-byte dan harus tetap berupa konstanta.
     */
    private const val TOKEN_KEY = "AD()*@Eak2930:F><AFZxmvnyucnf03-=+!@%(^_#%&)\$*(%akhad"

    private val segmentKey: ByteArray by lazy {
        hexToBytes(rc4(latin1(b64Decode(SEG_KEY_SEED)), "HTTPS")).copyOf(16)
    }

    private val segmentIv: ByteArray by lazy {
        hexToBytes(rc4(latin1(b64Decode(SEG_IV_SEED)), "HTTPS"))
    }

    // ------------------------------------------------------------------ RC4

    /** RC4 klasik; `input` dan `key` diperlakukan sebagai byte Latin-1. */
    fun rc4(input: String, key: String): String {
        if (key.isEmpty()) return input
        val s = IntArray(256) { it }
        var j = 0
        for (i in 0 until 256) {
            j = (j + s[i] + key[i % key.length].code) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
        }
        val out = StringBuilder(input.length)
        var i = 0
        j = 0
        for (c in input) {
            i = (i + 1) and 0xFF
            j = (j + s[i]) and 0xFF
            val tmp = s[i]
            s[i] = s[j]
            s[j] = tmp
            out.append((c.code xor s[(s[i] + s[j]) and 0xFF]).toChar())
        }
        return out.toString()
    }

    // ------------------------------------------------------- Token / balasan

    /** Header yang wajib ikut pada `POST /video/load/<token>/`. */
    const val HEADER_NAME = "X-REQUESTED-WITH"
    const val HEADER_VALUE = "official-app"

    /**
     * Membuat token `POST /video/load/<token>/`.
     *
     * @param identifier `data-filecode` untuk stream `EM`, atau slug video untuk
     *   stream lain (lihat `function m(e,a)` di bundle player).
     * @param label isi `aria-label` pada `a.select-stream-link` setelah awalan
     *   `"Stream "` dibuang — umumnya `"EM"`. Nilai ini BUKAN hiasan: server
     *   menolak token bila bagian `:.s:` tidak sesuai, jadi jangan diganti
     *   dengan `"undefined"`.
     */
    fun buildToken(identifier: String, label: String): String {
        val now = Calendar.getInstance()
        val stamp = ":.e:${now.get(Calendar.DAY_OF_MONTH)}/${now.get(Calendar.MONTH) + 1}/" +
            "${now.get(Calendar.YEAR)}@${now.get(Calendar.HOUR_OF_DAY)}:" +
            "${now.get(Calendar.MINUTE)}:${now.get(Calendar.SECOND)}"
        val payload = randomString(10) + ":.a:" + identifier.reversed() + stamp + ":.s:" + label
        val stage1 = b64Encode(latin1(payload)).reversed()
        return b64Encode(latin1(rc4(stage1, TOKEN_KEY))).reversed()
    }

    /**
     * Body permintaan `/video/load/`.
     *
     * Situs memakai `encodeURI("video=" + encodeURIComponent(d))`, sehingga nilainya
     * di-encode DUA KALI (tanda `%` hasil encode pertama ikut di-escape menjadi
     * `%25`). Kalau hanya di-encode sekali, server membalas token tidak valid.
     */
    fun buildRequestBody(token: String): String {
        val once = java.net.URLEncoder.encode(token, "UTF-8")
        val twice = java.net.URLEncoder.encode(once, "UTF-8")
        return "video=$twice"
    }

    /** Dekripsi balasan `/video/load/` menjadi URL m3u8. */
    fun decodeResponse(body: String): String? = try {
        rc4(latin1(b64Decode(body.reversed())), TOKEN_KEY).trim()
    } catch (_: Exception) {
        null
    }

    // ------------------------------------------------------------ Segmen .ts

    /**
     * Membuka bungkus JPEG dan mendekripsi segmen.
     * Bila segmen ternyata tidak dibungkus, data asli dikembalikan apa adanya.
     */
    fun decryptSegment(raw: ByteArray): ByteArray {
        var i = 0
        var wrapped = false
        val scanLimit = minOf(1000, raw.size - 1)
        while (i < scanLimit) {
            if (raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD8.toByte()) {
                wrapped = true
                break
            }
            i++
        }
        if (!wrapped) return raw

        while (i < raw.size - 1 && !(raw[i] == 0xFF.toByte() && raw[i + 1] == 0xD9.toByte())) i++
        if (i >= raw.size - 1) return raw

        val payload = raw.copyOfRange(i + 2, raw.size)
        if (payload.isEmpty() || payload.size % 16 != 0) return raw

        return try {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(segmentKey, "AES"),
                IvParameterSpec(segmentIv)
            )
            val plain = cipher.doFinal(payload)
            val pad = plain[plain.size - 1].toInt() and 0xFF
            if (pad in 1..16 && pad <= plain.size) plain.copyOf(plain.size - pad) else plain
        } catch (_: Exception) {
            raw
        }
    }

    // ---------------------------------------------------------------- Helper

    private fun randomString(length: Int): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
        val random = SecureRandom()
        return buildString(length) { repeat(length) { append(chars[random.nextInt(62)]) } }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return out
    }

    private fun b64Decode(value: String): ByteArray = Base64.decode(value, Base64.DEFAULT)

    private fun b64Encode(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private fun latin1(value: String): ByteArray = value.toByteArray(Charsets.ISO_8859_1)

    private fun latin1(value: ByteArray): String = String(value, Charsets.ISO_8859_1)
}
