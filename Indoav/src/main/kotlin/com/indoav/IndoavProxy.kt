package com.indoav

import android.util.Base64
import com.lagradost.api.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * Server HTTP lokal untuk memutar HLS IndoAV.
 *
 * Segmen `imgN.jpeg` yang dikirim CDN sebenarnya adalah berkas MPEG-TS yang
 * dibungkus header JPEG lalu dienkripsi AES-CBC, sehingga tidak bisa diputar
 * langsung oleh ExoPlayer. Proxy ini:
 *
 *   - `GET /playlist.m3u8?s=<m3u8>&r=<referer>` -> playlist ditulis ulang agar
 *     tiap segmen menunjuk ke endpoint lokal di bawah.
 *   - `GET /seg.ts?s=<segmen>&r=<referer>` -> unduh segmen, buka bungkus JPEG,
 *     dekripsi AES, lalu balas sebagai `video/mp2t`.
 *
 * Server hanya mendengarkan di 127.0.0.1 dengan port acak, dan dipakai bersama
 * selama proses CloudStream hidup.
 */
object IndoavProxy {

    private const val TAG = "IndoavProxy"
    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile
    private var port: Int = 0

    /** URL playlist lokal untuk `m3u8Url`, atau null bila server gagal dijalankan. */
    fun playlistUrl(m3u8Url: String, referer: String): String? {
        if (!ensureStarted()) return null
        return "http://127.0.0.1:$port/playlist.m3u8?s=${encode(m3u8Url)}&r=${encode(referer)}"
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
                            Log.e(TAG, "accept berhenti: ${e.message}")
                            break
                        }
                        launch { handle(client) }
                    }
                    // Socket sudah mati: lupakan port-nya supaya permintaan berikutnya
                    // menjalankan server baru, bukan mengembalikan URL yang tidak ada isinya.
                    synchronized(this@IndoavProxy) { port = 0 }
                }
                Log.d(TAG, "proxy aktif di 127.0.0.1:$port")
                true
            } catch (e: Exception) {
                Log.e(TAG, "gagal menjalankan proxy: ${e.message}")
                false
            }
        }
    }

    // ------------------------------------------------------------- Handling

    private fun handle(client: Socket) {
        try {
            client.soTimeout = 20_000
            val input = BufferedInputStream(client.getInputStream())
            val requestLine = readLine(input) ?: return
            val target = requestLine.split(" ").getOrNull(1) ?: return

            var rangeHeader: String? = null
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Range:", ignoreCase = true)) {
                    rangeHeader = line.substringAfter(':').trim()
                }
            }

            val out = client.getOutputStream()
            val path = target.substringBefore('?')
            val params = parseQuery(target.substringAfter('?', ""))

            val source = params["s"]?.let { decode(it) }
            val referer = params["r"]?.let { decode(it) } ?: INDOAV_REFERER

            val body: ByteArray? = when {
                source == null -> null
                path.endsWith("playlist.m3u8") -> buildPlaylist(source, referer)
                path.endsWith(".ts") -> buildSegment(source, referer)
                else -> null
            }

            when {
                body == null -> writeStatus(out, 502)
                path.endsWith("playlist.m3u8") ->
                    writeBody(out, "application/vnd.apple.mpegurl", body, rangeHeader)
                else -> writeBody(out, "video/mp2t", body, rangeHeader)
            }
            out.flush()
        } catch (e: Exception) {
            Log.e(TAG, "handle gagal: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun buildPlaylist(m3u8Url: String, referer: String): ByteArray? {
        val text = httpGetText(m3u8Url, referer) ?: return null
        val base = m3u8Url.substringBeforeLast('/', "") + "/"
        val builder = StringBuilder()
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.isEmpty() -> builder.append('\n')
                line.startsWith("#") -> builder.append(rewriteTagUris(line, base)).append('\n')
                line.contains(".m3u8") -> {
                    val absolute = absolutize(line, base)
                    builder.append("http://127.0.0.1:$port/playlist.m3u8?s=")
                        .append(encode(absolute)).append("&r=").append(encode(referer)).append('\n')
                }
                else -> {
                    val absolute = absolutize(line, base)
                    builder.append("http://127.0.0.1:$port/seg.ts?s=")
                        .append(encode(absolute)).append("&r=").append(encode(referer)).append('\n')
                }
            }
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    private fun buildSegment(segmentUrl: String, referer: String): ByteArray? {
        val raw = httpGetBytes(segmentUrl, referer) ?: return null
        return IndoavCrypto.decryptSegment(raw)
    }

    /** Menjadikan `URI="..."` di dalam tag HLS (EXT-X-KEY / EXT-X-MAP) absolut. */
    private fun rewriteTagUris(line: String, base: String): String {
        if (!line.contains("URI=\"")) return line
        return Regex("""URI="([^"]+)"""").replace(line) { match ->
            val uri = match.groupValues[1]
            if (uri.startsWith("http")) match.value
            else """URI="${absolutize(uri, base)}""""
        }
    }

    private fun absolutize(url: String, base: String): String {
        if (url.startsWith("http://") || url.startsWith("https://")) return url
        if (url.startsWith("/")) {
            val scheme = base.substringBefore("://")
            val host = base.substringAfter("://").substringBefore('/')
            return "$scheme://$host$url"
        }
        return base + url
    }

    // ------------------------------------------------------------- HTTP out

    private fun open(url: String, referer: String?): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "*/*")
        // Minta body apa adanya: playlist dan segmen diolah sebagai byte mentah,
        // jadi body yang ter-gzip akan merusak hasilnya.
        connection.setRequestProperty("Accept-Encoding", "identity")
        if (!referer.isNullOrBlank()) connection.setRequestProperty("Referer", referer)
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        connection.instanceFollowRedirects = true
        return connection
    }

    private fun httpGetBytes(url: String, referer: String?): ByteArray? = try {
        open(url, referer).inputStream.use { it.readBytes() }
    } catch (e: Exception) {
        Log.e(TAG, "GET gagal $url: ${e.message}")
        null
    }

    private fun httpGetText(url: String, referer: String?): String? = try {
        open(url, referer).inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
    } catch (e: Exception) {
        Log.e(TAG, "GET gagal $url: ${e.message}")
        null
    }

    // --------------------------------------------------------- HTTP in/out

    private fun writeStatus(out: OutputStream, code: Int) {
        val reason = when (code) {
            404 -> "Not Found"
            else -> "Bad Gateway"
        }
        out.write(
            ("HTTP/1.1 $code $reason\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .toByteArray(Charsets.US_ASCII)
        )
    }

    private fun writeBody(
        out: OutputStream,
        contentType: String,
        body: ByteArray,
        rangeHeader: String?
    ) {
        var code = 200
        var payload = body
        var contentRange: String? = null

        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            val spec = rangeHeader.removePrefix("bytes=").split("-")
            val start = spec.getOrNull(0)?.toIntOrNull() ?: 0
            val end = spec.getOrNull(1)?.toIntOrNull()?.coerceAtMost(body.size - 1)
                ?: (body.size - 1)
            if (start in 0 until body.size && end >= start) {
                payload = body.copyOfRange(start, end + 1)
                contentRange = "bytes $start-$end/${body.size}"
                code = 206
            }
        }

        val header = buildString {
            append("HTTP/1.1 ").append(code)
                .append(if (code == 206) " Partial Content" else " OK").append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(payload.size).append("\r\n")
            if (contentRange != null) append("Content-Range: ").append(contentRange).append("\r\n")
            append("Accept-Ranges: bytes\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Cache-Control: no-store\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.US_ASCII))
        out.write(payload)
        out.flush()
    }

    // ---------------------------------------------------------------- Utils

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

    private fun encode(value: String): String =
        Base64.encodeToString(value.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .replace('+', '-')
            .replace('/', '_')
            .trimEnd('=')

    private fun decode(value: String): String? = try {
        val normalized = value.replace('-', '+').replace('_', '/')
        val padding = (4 - normalized.length % 4) % 4
        String(
            Base64.decode(normalized + "=".repeat(padding), Base64.NO_WRAP),
            Charsets.UTF_8
        )
    } catch (_: Exception) {
        null
    }

    private const val INDOAV_REFERER = "https://www.indoav.com/"
}
