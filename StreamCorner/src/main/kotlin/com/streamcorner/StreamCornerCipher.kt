package com.streamcorner

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object StreamCornerCipher {
    private val b = "ninjacorner.api.f6b96ddb9345|74e5977897d017e3fd2a2371dfbbdb0c5084ad088d3afac6c2ee0a077540539f".toByteArray(Charsets.UTF_8)
    private val et = "ninjacorner.transport.eaeb682355".toByteArray(Charsets.UTF_8)
    private const val Pt = "ninjacorner.token.2026|28d84e4103bc3a15bb1db2db98cfbd69677947bb186311cbfd4878b9c30af7884cdb4d6479fe977aa51d130bd1f3d3d0"
    private val tokenBytes = Pt.toByteArray(Charsets.UTF_8)
    private val rt = "ninjacorner:req:outer:v4".toByteArray(Charsets.UTF_8)
    private val nt = "ninjacorner:req:guard:v4".toByteArray(Charsets.UTF_8)
    private val Tt = "ninjacorner:guard:v2".toByteArray(Charsets.UTF_8)
    private val Et = "ninjacorner:outer:v3".toByteArray(Charsets.UTF_8)

    private val random = SecureRandom()

    fun sha256(vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        for (p in parts) md.update(p)
        return md.digest()
    }

    fun base64UrlEncode(bytes: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun base64UrlDecode(str: String): ByteArray {
        var s = str.replace('-', '+').replace('_', '/')
        while (s.length % 4 != 0) s += "="
        return Base64.getDecoder().decode(s)
    }

    fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim().replace("-", "")
        val len = clean.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            val h = Character.digit(clean[i], 16)
            val l = Character.digit(clean[i + 1], 16)
            data[i / 2] = ((h shl 4) + l).toByte()
            i += 2
        }
        return data
    }

    fun toClearKeyB64(value: String): String {
        val trimmed = value.trim()
        val cleanHex = trimmed.replace("-", "")
        return if (cleanHex.length == 32 && cleanHex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
            base64UrlEncode(hexToBytes(cleanHex))
        } else {
            trimmed.replace('+', '-').replace('/', '_').trimEnd('=')
        }
    }

    private fun rotl(v: Int, n: Int): Int {
        return (v shl n) or (v ushr (32 - n))
    }

    private fun getUInt32LE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 3].toInt() and 0xFF) shl 24)
    }

    private fun setUInt32LE(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        bytes[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        bytes[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun generateBlock(key: ByteArray, nonce: ByteArray, counter: Long): ByteArray {
        val o = IntArray(16)
        o[0] = 1634760805
        o[5] = 857760878
        o[10] = 2036477234
        o[15] = 1797285236

        for (f in 0 until 4) {
            o[1 + f] = getUInt32LE(key, 4 * f)
            o[11 + f] = getUInt32LE(key, 16 + 4 * f)
        }
        o[6] = getUInt32LE(nonce, 0)
        o[7] = getUInt32LE(nonce, 4)
        o[8] = (counter and 0xFFFFFFFFL).toInt()
        o[9] = (counter ushr 32).toInt()

        val t = o.clone()
        for (f in 0 until 6) {
            t[4] = t[4] xor rotl(t[0] + t[12], 7)
            t[8] = t[8] xor rotl(t[4] + t[0], 9)
            t[12] = t[12] xor rotl(t[8] + t[4], 13)
            t[0] = t[0] xor rotl(t[12] + t[8], 18)

            t[9] = t[9] xor rotl(t[5] + t[1], 7)
            t[13] = t[13] xor rotl(t[9] + t[5], 9)
            t[1] = t[1] xor rotl(t[13] + t[9], 13)
            t[5] = t[5] xor rotl(t[1] + t[13], 18)

            t[14] = t[14] xor rotl(t[10] + t[6], 7)
            t[2] = t[2] xor rotl(t[14] + t[10], 9)
            t[6] = t[6] xor rotl(t[2] + t[14], 13)
            t[10] = t[10] xor rotl(t[6] + t[2], 18)

            t[3] = t[3] xor rotl(t[15] + t[11], 7)
            t[7] = t[7] xor rotl(t[3] + t[15], 9)
            t[11] = t[11] xor rotl(t[7] + t[3], 13)
            t[15] = t[15] xor rotl(t[11] + t[7], 18)

            t[1] = t[1] xor rotl(t[0] + t[3], 7)
            t[2] = t[2] xor rotl(t[1] + t[0], 9)
            t[3] = t[3] xor rotl(t[2] + t[1], 13)
            t[0] = t[0] xor rotl(t[3] + t[2], 18)

            t[6] = t[6] xor rotl(t[5] + t[4], 7)
            t[7] = t[7] xor rotl(t[6] + t[5], 9)
            t[4] = t[4] xor rotl(t[7] + t[6], 13)
            t[5] = t[5] xor rotl(t[4] + t[7], 18)

            t[11] = t[11] xor rotl(t[10] + t[9], 7)
            t[8] = t[8] xor rotl(t[11] + t[10], 9)
            t[9] = t[9] xor rotl(t[8] + t[11], 13)
            t[10] = t[10] xor rotl(t[9] + t[8], 18)

            t[12] = t[12] xor rotl(t[15] + t[14], 7)
            t[13] = t[13] xor rotl(t[12] + t[15], 9)
            t[14] = t[14] xor rotl(t[13] + t[12], 13)
            t[15] = t[15] xor rotl(t[14] + t[13], 18)
        }

        val res = ByteArray(64)
        for (f in 0 until 16) {
            setUInt32LE(res, 4 * f, t[f] + o[f])
        }
        return res
    }

    private fun salsaCipher(data: ByteArray, key: ByteArray, nonce: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        var blockIdx = 0L
        while (i < data.size) {
            val blk = generateBlock(key, nonce, blockIdx)
            var f = 0
            while (f < 64 && i + f < data.size) {
                out[i + f] = (data[i + f].toInt() xor blk[f].toInt()).toByte()
                f++
            }
            i += 64
            blockIdx++
        }
        return out
    }

    data class EncryptedPayload(
        val body: ByteArray,
        val nonce: ByteArray,
        val paramBytes: ByteArray,
        val uBytes: ByteArray
    )

    fun encryptRequest(param: String): EncryptedPayload {
        val cleanParam = param.trim().lowercase()
        val paramBytes = cleanParam.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(8)
        random.nextBytes(nonce)

        val reqKey = sha256(b, et, rt)
        val timeWindow = System.currentTimeMillis() / 300_000L
        val plaintext = """["$Pt","$cleanParam",$timeWindow]""".toByteArray(Charsets.UTF_8)
        val ciphertext = salsaCipher(plaintext, reqKey, nonce)
        val tag = sha256(reqKey, nonce, ciphertext, nt).copyOfRange(0, 12)

        val uBytes = ByteArray(4)
        uBytes[0] = ((timeWindow ushr 24) and 0xFF).toByte()
        uBytes[1] = ((timeWindow ushr 16) and 0xFF).toByte()
        uBytes[2] = ((timeWindow ushr 8) and 0xFF).toByte()
        uBytes[3] = (timeWindow and 0xFF).toByte()

        val body = ByteArray(1 + 8 + 12 + ciphertext.size)
        body[0] = 10.toByte() // 0x0A
        System.arraycopy(nonce, 0, body, 1, 8)
        System.arraycopy(tag, 0, body, 9, 12)
        System.arraycopy(ciphertext, 0, body, 21, ciphertext.size)

        return EncryptedPayload(body, nonce, paramBytes, uBytes)
    }

    fun decryptResponse(respBytes: ByteArray, payload: EncryptedPayload): String {
        return decryptResponse(respBytes, payload.nonce, payload.paramBytes, payload.uBytes)
    }

    fun decryptResponse(
        respBytes: ByteArray,
        reqNonce: ByteArray,
        reqParamBytes: ByteArray,
        reqUBytes: ByteArray
    ): String {
        if (respBytes.size <= 21 || respBytes[0] != 4.toByte()) {
            throw IllegalStateException("Invalid response format or marker: ${respBytes.getOrNull(0)}")
        }

        val respNonce = respBytes.copyOfRange(1, 9)
        val respTag = respBytes.copyOfRange(9, 21)
        val respCiphertext = respBytes.copyOfRange(21, respBytes.size)

        val respKey = sha256(b, et, tokenBytes, reqNonce, reqParamBytes, reqUBytes, Et)
        val expectedTag = sha256(respKey, respNonce, respCiphertext, Tt).copyOfRange(0, 12)

        if (!MessageDigest.isEqual(respTag, expectedTag)) {
            throw SecurityException("StreamCorner guard tag mismatch")
        }

        val decrypted = salsaCipher(respCiphertext, respKey, respNonce)
        return String(decrypted, Charsets.UTF_8)
    }
}
