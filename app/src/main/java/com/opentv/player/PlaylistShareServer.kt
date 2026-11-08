package com.opentv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import kotlinx.coroutines.runBlocking

/**
 * Temporary local playlist share (4.7.1): a tiny HTTP server on the TV's
 * local network that lets other devices on the same Wi-Fi import an M3U
 * playlist. No new dependency — plain java.net.ServerSocket.
 *
 * Share OUT (this TV -> other devices):
 * - GET /playlist.m3u -> the playlist, served inline (add it by URL on
 *   the other device).
 * - GET /download     -> the same playlist as a downloadable .m3u file
 *   (Content-Disposition: attachment), so the other device can save the
 *   file and import it.
 *
 * Share IN (4.8.0, other devices -> this TV): the server also accepts
 * playlists from the local network and adds them to the TV's playlist
 * list, so another device can send an M3U to this TV:
 * - GET /            -> a small web page: download links for the shared
 *   playlist, a file-upload form, and an import-by-URL form.
 * - POST /upload    -> an M3U file upload (multipart/form-data from a
 *   browser, or a raw M3U body with curl). Validated (#EXTM3U), saved
 *   on the TV, and added to the playlist list.
 * - GET /import?url=<m3u-url> -> the TV fetches that URL, validates it,
 *   and adds it to the playlist list.
 * - Anything else -> 404.
 *
 * The server only listens on the local network (it binds 0.0.0.0 and the
 * URL shown is the TV's LAN address — nothing is exposed to the
 * internet). It stops itself after 10 minutes and can be stopped
 * manually at any time. If the TV has no LAN address (no Wi-Fi or
 * Ethernet), start() returns false and nothing is served.
 */
object PlaylistShareServer {

    /** Sharing lasts 10 minutes, then the server stops itself. */
    const val SHARE_DURATION_MS = 10L * 60 * 1000

    /** Preferred port; falls back to an ephemeral port if it is taken. */
    private const val PREFERRED_PORT = 8080

    /** Uploads larger than this are refused (an M3U is normally < 5 MB). */
    private const val MAX_UPLOAD_BYTES = 10 * 1024 * 1024

    @Volatile var isRunning = false
        private set
    /** http://<tv-ip>:<port>/playlist.m3u — add this URL on the other device. */
    @Volatile var shareUrl: String? = null
        private set
    /** http://<tv-ip>:<port>/download — downloads playlist.m3u as a file. */
    @Volatile var downloadUrl: String? = null
        private set
    /** http://<tv-ip>:<port>/ — send a playlist to this TV (upload form). */
    @Volatile var importUrl: String? = null
        private set
    /** Uptime deadline (System.currentTimeMillis) — 0 when not running. */
    @Volatile var endsAtMillis: Long = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var content: ByteArray = byteArrayOf()
    @Volatile private var appContext: Context? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    /** Milliseconds left before the server stops itself (0 when stopped). */
    fun remainingMillis(): Long =
        if (!isRunning) 0 else (endsAtMillis - System.currentTimeMillis()).coerceAtLeast(0)

    /**
     * Start serving [playlistText] on the LAN. Returns false when the TV
     * has no LAN address (no Wi-Fi/Ethernet) or the socket can't bind.
     */
    @Synchronized
    fun start(context: Context, playlistText: String): Boolean {
        stop()
        val ip = localIpAddress() ?: return false
        val socket = try {
            try {
                ServerSocket(PREFERRED_PORT, 50, InetAddress.getByName("0.0.0.0"))
            } catch (e: Exception) {
                ServerSocket(0, 50, InetAddress.getByName("0.0.0.0"))
            }
        } catch (e: Exception) {
            return false
        }
        serverSocket = socket
        content = playlistText.toByteArray(Charsets.UTF_8)
        appContext = context.applicationContext
        val port = socket.localPort
        shareUrl = "http://$ip:$port/playlist.m3u"
        downloadUrl = "http://$ip:$port/download"
        importUrl = "http://$ip:$port/"
        endsAtMillis = System.currentTimeMillis() + SHARE_DURATION_MS
        isRunning = true
        acceptThread = Thread({ acceptLoop(socket) }, "playlist-share").apply {
            isDaemon = true
            start()
        }
        mainHandler.postDelayed(autoStop, SHARE_DURATION_MS)
        return true
    }

    /** Stop the server now (manual stop, or the 10-minute auto-stop). */
    @Synchronized
    fun stop() {
        mainHandler.removeCallbacks(autoStop)
        isRunning = false
        shareUrl = null
        downloadUrl = null
        importUrl = null
        endsAtMillis = 0
        appContext = null
        try {
            serverSocket?.close()
        } catch (ignored: Exception) {
        }
        serverSocket = null
        acceptThread = null
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client: Socket = try {
                socket.accept()
            } catch (e: SocketException) {
                break // Socket closed by stop().
            } catch (e: Exception) {
                break
            }
            // One thread per request: a slow client must not block the rest.
            Thread({ handleClient(client) }, "playlist-share-client").apply {
                isDaemon = true
                start()
            }
        }
    }

    /** One parsed HTTP request: method, path, query, headers, body. */
    private data class Request(
        val method: String,
        val path: String,
        val query: Map<String, String>,
        val headers: Map<String, String>,
        val body: ByteArray
    )

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 15_000
            val input = client.getInputStream()
            val request = readRequest(input) ?: return
            val out = client.getOutputStream()
            when {
                request.method == "GET" && request.path == "/" -> {
                    sendHtml(out, 200, indexPage())
                }
                request.method == "GET" &&
                    (request.path == "/playlist.m3u") -> {
                    val body = content
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/x-mpegurl\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(body)
                }
                request.method == "GET" && request.path == "/download" -> {
                    val body = content
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/x-mpegurl\r\n" +
                        "Content-Disposition: attachment; filename=\"playlist.m3u\"\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(body)
                }
                request.method == "GET" && request.path == "/import" -> {
                    handleImportByUrl(out, request)
                }
                request.method == "POST" && request.path == "/upload" -> {
                    handleUpload(out, request)
                }
                else -> {
                    val notFound = "Not found. Use /playlist.m3u, /download, or / to send a playlist to this TV."
                    val head = "HTTP/1.1 404 Not Found\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: ${notFound.length}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(notFound.toByteArray(Charsets.UTF_8))
                }
            }
            out.flush()
        } catch (ignored: Exception) {
            // A dropped client must not kill the server.
        } finally {
            try {
                client.close()
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Read the request line + headers as raw bytes (a BufferedReader
     * would buffer body bytes ahead), then exactly Content-Length body
     * bytes. Returns null on a malformed request or an oversized body.
     */
    private fun readRequest(input: InputStream): Request? {
        // Headers: read until the blank line ("\r\n\r\n"), cap at 16 KB.
        val headBytes = ByteArray(16 * 1024)
        var headLen = 0
        while (headLen < headBytes.size) {
            val b = try {
                input.read()
            } catch (e: Exception) {
                return null
            }
            if (b < 0) return null
            headBytes[headLen++] = b.toByte()
            if (headLen >= 4 &&
                headBytes[headLen - 4] == '\r'.code.toByte() &&
                headBytes[headLen - 3] == '\n'.code.toByte() &&
                headBytes[headLen - 2] == '\r'.code.toByte() &&
                headBytes[headLen - 1] == '\n'.code.toByte()
            ) break
        }
        val headText = String(headBytes, 0, headLen, Charsets.UTF_8)
        val lines = headText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null
        val parts = lines[0].split(" ")
        if (parts.size < 2) return null
        val method = parts[0].uppercase()
        val rawTarget = parts[1]
        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }
        val contentLength = headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLength < 0 || contentLength > MAX_UPLOAD_BYTES) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = try {
                input.read(body, read, contentLength - read)
            } catch (e: Exception) {
                return null
            }
            if (n < 0) return null
            read += n
        }
        val pathAndQuery = rawTarget.split("?", limit = 2)
        val path = pathAndQuery[0]
        val query = mutableMapOf<String, String>()
        if (pathAndQuery.size > 1) {
            for (pair in pathAndQuery[1].split("&")) {
                val idx = pair.indexOf('=')
                if (idx > 0) {
                    query[urlDecode(pair.substring(0, idx))] =
                        urlDecode(pair.substring(idx + 1))
                }
            }
        }
        return Request(method, path, query, headers, body)
    }

    private fun urlDecode(s: String): String = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        s
    }

    /** GET /import?url=<m3u-url>: fetch it, validate it, add it. */
    private fun handleImportByUrl(out: java.io.OutputStream, request: Request) {
        val url = request.query["url"]?.trim().orEmpty()
        if (url.isBlank()) {
            sendHtml(out, 400, messagePage("No URL given", "Add ?url= with an M3U playlist URL."))
            return
        }
        val result = try {
            runBlocking { PlaylistRepository.fetchUrlText(url) }
        } catch (e: Exception) {
            Result.failure<String>(e)
        }
        val text = result.getOrNull()
        if (text.isNullOrBlank() || !text.trimStart().startsWith("#EXTM3U")) {
            sendHtml(out, 400, messagePage("Could not import", "That URL did not return an M3U playlist."))
            return
        }
        val context = appContext
        if (context == null) {
            sendHtml(out, 500, messagePage("TV busy", "Try again in a moment."))
            return
        }
        val entry = PlaylistRepository.addPlaylist(context, defaultNameForUrl(url), url)
        if (entry == null) {
            sendHtml(out, 400, messagePage("Could not import", "That is not a valid playlist URL."))
            return
        }
        toastReceived(entry.name)
        sendHtml(out, 200, messagePage("Playlist added", "\"${entry.name}\" was added to this TV's playlists."))
    }

    /** POST /upload: an M3U file (multipart form, or a raw M3U body). */
    private fun handleUpload(out: java.io.OutputStream, request: Request) {
        val contentType = request.headers["content-type"] ?: ""
        val (fileName, bytes) = if (contentType.startsWith("multipart/form-data")) {
            parseMultipart(request, contentType)
        } else {
            // Raw body (curl --data-binary @playlist.m3u): the whole
            // body is the playlist.
            null to request.body
        }
        if (bytes.isEmpty()) {
            sendHtml(out, 400, messagePage("No file received", "Choose an .m3u file and try again."))
            return
        }
        val text = try {
            String(bytes, Charsets.UTF_8)
        } catch (e: Exception) {
            sendHtml(out, 400, messagePage("Could not import", "That file is not a readable playlist."))
            return
        }
        if (!text.trimStart().startsWith("#EXTM3U")) {
            sendHtml(out, 400, messagePage("Not an M3U playlist", "That file does not start with #EXTM3U."))
            return
        }
        val context = appContext
        if (context == null) {
            sendHtml(out, 500, messagePage("TV busy", "Try again in a moment."))
            return
        }
        val name = fileName
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.removeSuffix(".m3u")
            ?.removeSuffix(".M3U")
            ?.trim()
            .orEmpty()
        val entry = PlaylistRepository.saveLocalPlaylist(
            context, name.ifBlank { "Shared playlist" }, text
        )
        if (entry == null) {
            sendHtml(out, 500, messagePage("Could not import", "The TV could not save that playlist."))
            return
        }
        toastReceived(entry.name)
        sendHtml(out, 200, messagePage("Playlist added", "\"${entry.name}\" was added to this TV's playlists."))
    }

    /**
     * Pull the file part out of a multipart body: (file name, file
     * bytes). Prefers a part that carries a file name; falls back to
     * the first non-empty part's content. Returns ("", empty) when
     * nothing usable is in the body.
     */
    private fun parseMultipart(request: Request, contentType: String): Pair<String?, ByteArray> {
        val boundary = contentType.substringAfter("boundary=", "")
            .trim().trim('"').trim()
        if (boundary.isBlank()) return null to ByteArray(0)
        val body = request.body
        val delimiter = ("--" + boundary).toByteArray(Charsets.UTF_8)
        var pos = indexOf(body, delimiter, 0)
        if (pos < 0) return null to ByteArray(0)
        var firstContent: ByteArray? = null
        while (pos >= 0) {
            var cursor = pos + delimiter.size
            // The final boundary ends with "--" — stop there.
            if (body.getOrNull(cursor) == '-'.code.toByte() &&
                body.getOrNull(cursor + 1) == '-'.code.toByte()
            ) break
            // Skip the line ending after the boundary.
            if (body.getOrNull(cursor) == '\r'.code.toByte() &&
                body.getOrNull(cursor + 1) == '\n'.code.toByte()
            ) cursor += 2
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(Charsets.UTF_8), cursor)
            if (headerEnd < 0) break
            val partHeaders = String(body, cursor, headerEnd - cursor, Charsets.UTF_8)
            val contentStart = headerEnd + 4
            // The part ends at the next "\r\n--boundary".
            val endMarker = ("\r\n--" + boundary).toByteArray(Charsets.UTF_8)
            val contentEnd = indexOf(body, endMarker, contentStart)
            if (contentEnd < 0) break
            val fileName = partHeaders.lines()
                .firstOrNull { it.trim().startsWith("Content-Disposition:", ignoreCase = true) }
                ?.let { header ->
                    Regex("filename=\"([^\"]*)\"").find(header)?.groupValues?.getOrNull(1)
                }
            val partBytes = body.copyOfRange(contentStart, contentEnd)
            if (!fileName.isNullOrBlank()) return fileName to partBytes
            if (firstContent == null && partBytes.isNotEmpty()) firstContent = partBytes
            pos = indexOf(body, delimiter, contentEnd + endMarker.size)
        }
        return null to (firstContent ?: ByteArray(0))
    }

    /** Byte-array indexOf (Kotlin's ByteArray has no built-in one). */
    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        if (needle.isEmpty() || haystack.size < needle.size) return -1
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return -1
    }

    /** A readable default name for an imported URL: its host. */
    private fun defaultNameForUrl(url: String): String = try {
        java.net.URL(url).host?.takeIf { it.isNotBlank() } ?: "Shared playlist"
    } catch (e: Exception) {
        "Shared playlist"
    }

    /** Tell the TV a playlist just arrived (toast on the main thread). */
    private fun toastReceived(name: String) {
        val context = appContext ?: return
        mainHandler.post {
            try {
                Toast.makeText(
                    context,
                    context.getString(R.string.playlist_received, name),
                    Toast.LENGTH_LONG
                ).show()
            } catch (ignored: Exception) {
            }
        }
    }

    private fun sendHtml(out: java.io.OutputStream, code: Int, html: String) {
        val body = html.toByteArray(Charsets.UTF_8)
        val status = if (code == 200) "200 OK" else "$code"
        val head = "HTTP/1.1 $status\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.UTF_8))
        out.write(body)
    }

    /** The landing page: download this TV's playlist, or send one in. */
    private fun indexPage(): String {
        val share = shareUrl ?: ""
        val download = downloadUrl ?: ""
        return "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>OpenTV — Share playlist</title></head><body>" +
            "<h1>OpenTV</h1>" +
            "<h2>Get this TV's playlist</h2>" +
            "<p><a href=\"/playlist.m3u\">Add by URL: $share</a></p>" +
            "<p><a href=\"/download\">Download playlist.m3u</a></p>" +
            "<h2>Send a playlist to this TV</h2>" +
            "<form action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\">" +
            "<input type=\"file\" name=\"playlist\" accept=\".m3u\">" +
            "<input type=\"submit\" value=\"Send to TV\"></form>" +
            "<h2>Or import by URL</h2>" +
            "<form action=\"/import\" method=\"get\">" +
            "<input type=\"text\" name=\"url\" placeholder=\"https://example.com/playlist.m3u\" size=\"40\">" +
            "<input type=\"submit\" value=\"Import URL\"></form>" +
            "<p>Sharing stops itself after 10 minutes.</p>" +
            "</body></html>"
    }

    /** A tiny result page after an upload / import. */
    private fun messagePage(title: String, message: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<title>OpenTV — $title</title></head><body>" +
            "<h1>$title</h1><p>$message</p>" +
            "<p><a href=\"/\">Back</a></p></body></html>"

    /**
     * The TV's LAN IPv4 address, preferring a site-local (192.168 / 10 /
     * 172.16) address. Null when there is no usable LAN address (no
     * Wi-Fi or Ethernet).
     */
    fun localIpAddress(): String? {
        var fallback: String? = null
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        val host = addr.hostAddress ?: continue
                        if (addr.isSiteLocalAddress) return host
                        if (fallback == null) fallback = host
                    }
                }
            }
        } catch (ignored: Exception) {
            return null
        }
        return fallback
    }
}
