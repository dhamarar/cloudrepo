package com.streamcorner

import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object StreamCornerCipher {
    private val J = "ninjacorner.api.a07d402e812e|c85028399122b8f6e2df5869aa1b0981d172cf587b62fcde2562fbb12a3a9fdd".toByteArray(Charsets.UTF_8)
    private val it = "ninjacorner.transport.83af8c6260".toByteArray(Charsets.UTF_8)
    private val Gt = "ninjacorner.token.2026|28d84e4103bc3a15bb1db2db98cfbd69677947bb186311cbfd4878b9c30af7884cdb4d6479fe977aa51d130bd1f3d3d0".toByteArray(Charsets.UTF_8)
    private val et = "ninjacorner:req:outer:v4".toByteArray(Charsets.UTF_8)
    private val ot = "ninjacorner:req:guard:v4".toByteArray(Charsets.UTF_8)
    private val bt = "ninjacorner:outer:v3".toByteArray(Charsets.UTF_8)
    private val Mt = "ninjacorner:guard:v2".toByteArray(Charsets.UTF_8)

    private val A = arrayOf("cWsI", "7kYa", "gicW", "Ez-2", "Is27", "0OA1", "9Poj", "pfOv")

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
        val body: String,
        val nonce: ByteArray,
        val paramBytes: ByteArray
    )

    fun encryptRequest(param: String): EncryptedPayload {
        val cleanParam = param.trim().lowercase()
        val o = cleanParam.toByteArray(Charsets.UTF_8)
        val nonce = ByteArray(8)
        random.nextBytes(nonce)

        val reqKey = sha256(J, it, et)
        val gtStr = String(Gt, Charsets.UTF_8)
        val plaintext = "[\"$gtStr\",\"$cleanParam\"]".toByteArray(Charsets.UTF_8)
        val ciphertext = salsaCipher(plaintext, reqKey, nonce)
        val tag = sha256(reqKey, nonce, ciphertext, ot).copyOfRange(0, 12)

        val obj = JSONObject().apply {
            put(A[0], 9)
            put(A[1], base64UrlEncode(nonce))
            put(A[2], base64UrlEncode(ciphertext))
            put(A[3], base64UrlEncode(tag))
        }

        return EncryptedPayload(obj.toString(), nonce, o)
    }

    fun decryptResponse(responseText: String, reqNonce: ByteArray, reqParamBytes: ByteArray): String {
        val q = JSONObject(responseText)
        if (q.optInt(A[4]) != 3) throw IllegalStateException("Invalid response marker")

        val respNonce = base64UrlDecode(q.getString(A[5]))
        val respCiphertext = base64UrlDecode(q.getString(A[6]))
        val respTag = base64UrlDecode(q.getString(A[7]))

        val respKey = sha256(J, it, Gt, reqNonce, reqParamBytes, bt)
        val expectedTag = sha256(respKey, respNonce, respCiphertext, Mt).copyOfRange(0, 12)

        if (!expectedTag.contentEquals(respTag)) {
            throw SecurityException("StreamCorner guard tag mismatch")
        }

        val decrypted = salsaCipher(respCiphertext, respKey, respNonce)
        return String(decrypted, Charsets.UTF_8)
    }
}
