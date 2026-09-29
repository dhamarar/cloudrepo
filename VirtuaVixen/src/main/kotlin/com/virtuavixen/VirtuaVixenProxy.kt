package com.virtuavixen

import com.lagradost.api.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import java.util.LinkedHashMap

/**
 * Server HTTP lokal untuk menyajikan playlist HLS M3U8 dan kunci AES-128
 * yang diperlukan oleh VirtuaVixen / FV Player Pro.
 *
 * Server berjalan pada 127.0.0.1:port acak dan bertindak sebagai penyedia
 * kunci AES-128 statis lokal agar ExoPlayer tidak menerima kunci palsu (anti-rip)
 * setelah token satu kali pakai habis dibaca dari server upstream.
 * Segmen video (.ts) tetap diunduh langsung oleh ExoPlayer dari CDN DigitalOcean.
 */
object VirtuaVixenProxy {
    private const val TAG = "VirtuaVixenProxy"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var port: Int = 0

    private val playlistCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 100
        }
    )

    private val keyCache = Collections.synchronizedMap(
        object : LinkedHashMap<String, ByteArray>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?): Boolean = size > 100
        }
    )

    /**
     * Daftarkan konten subplaylist dan kunci AES-128.
     * Mengembalikan URL lokal http://127.0.0.1:$port/playlist.m3u8?id=$id
     */
    fun register(id: String, subplaylistText: String, keyBytes: ByteArray): String? {
        if (!ensureStarted()) return null
        val rewritten = subplaylistText.replace(
            Regex("""#EXT-X-KEY:METHOD=AES-128,URI=["'][^"']+["']"""),
            """#EXT-X-KEY:METHOD=AES-128,URI="http://127.0.0.1:$port/key.bin?id=$id""""
        )
        playlistCache[id] = rewritten
        keyCache[id] = keyBytes
        return "http://127.0.0.1:$port/playlist.m3u8?id=$id"
    }

    private fun ensureStarted(): Boolean {
        if (port > 0) return true
        synchronized(this) {
            if (port > 0) return true
            return try {
                val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                port = server.localPort
                scope.launch {
                    while (isActive) {
                        val client = try {
                            server.accept()
                        } catch (e: Exception) {
                            Log.e(TAG, "accept stopped: ${e.message}")
                            break
                        }
                        launch { handle(client) }
                    }
                    synchronized(this@VirtuaVixenProxy) { port = 0 }
                }
                Log.d(TAG, "VirtuaVixenProxy active on 127.0.0.1:$port")
                true
            } catch (e: Exception) {
                Log.e(TAG, "failed starting proxy: ${e.message}")
                false
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val target = requestLine.split(" ").getOrNull(1) ?: return

            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
            }

            val out = client.getOutputStream()
            val path = target.substringBefore('?')
            val params = parseQuery(target.substringAfter('?', ""))
            val id = params["id"]

            when {
                path.endsWith("playlist.m3u8") -> {
                    val content = if (id != null) playlistCache[id] else null
                    if (content != null) {
                        writeBody(out, "application/vnd.apple.mpegurl", content.toByteArray(Charsets.UTF_8))
                    } else {
                        writeStatus(out, 404)
                    }
                }
                path.endsWith("key.bin") -> {
                    val key = if (id != null) keyCache[id] else null
                    if (key != null) {
                        writeBody(out, "application/octet-stream", key)
                    } else {
                        writeStatus(out, 404)
                    }
                }
                else -> writeStatus(out, 404)
            }
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "handle failed: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {}
        }
    }

    private fun writeStatus(out: OutputStream, code: Int) {
        val reason = if (code == 404) "Not Found" else "Bad Request"
        out.write("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
    }

    private fun writeBody(out: OutputStream, contentType: String, body: ByteArray) {
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(body)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val builder = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (builder.isEmpty()) null else builder.toString()
            if (b == '\n'.code) {
                if (builder.isNotEmpty() && builder.last() == '\r') builder.deleteCharAt(builder.length - 1)
                return builder.toString()
            }
            builder.append(b.toChar())
        }
    }

    private fun parseQuery(query: String): Map<String, String> {
        if (query.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in query.split('&')) {
            val index = pair.indexOf('=')
            if (index <= 0) continue
            map[pair.substring(0, index)] = pair.substring(index + 1)
        }
        return map
    }
}
