package com.opentv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Temporary local playlist share (4.7.1): a tiny HTTP server on the TV's
 * local network that lets other devices on the same Wi-Fi import an M3U
 * playlist. No new dependency — plain java.net.ServerSocket.
 *
 * - GET /playlist.m3u -> the playlist, served inline (add it by URL on
 *   the other device).
 * - GET /download     -> the same playlist as a downloadable .m3u file
 *   (Content-Disposition: attachment), so the other device can save the
 *   file and import it.
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

    @Volatile var isRunning = false
        private set
    /** http://<tv-ip>:<port>/playlist.m3u — add this URL on the other device. */
    @Volatile var shareUrl: String? = null
        private set
    /** http://<tv-ip>:<port>/download — downloads playlist.m3u as a file. */
    @Volatile var downloadUrl: String? = null
        private set
    /** Uptime deadline (System.currentTimeMillis) — 0 when not running. */
    @Volatile var endsAtMillis: Long = 0
        private set

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var content: ByteArray = byteArrayOf()

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
        val port = socket.localPort
        shareUrl = "http://$ip:$port/playlist.m3u"
        downloadUrl = "http://$ip:$port/download"
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
        endsAtMillis = 0
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

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 10_000
            val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
            val requestLine = reader.readLine() ?: return
            // Consume the headers; only the request line matters here.
            var line: String?
            do {
                line = reader.readLine()
            } while (line != null && line.isNotEmpty())
            val path = requestLine.split(" ").getOrNull(1) ?: ""
            val out = client.getOutputStream()
            val body = content
            when {
                path == "/playlist.m3u" || path.startsWith("/playlist.m3u?") -> {
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/x-mpegurl\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(body)
                }
                path == "/download" || path.startsWith("/download?") -> {
                    val head = "HTTP/1.1 200 OK\r\n" +
                        "Content-Type: audio/x-mpegurl\r\n" +
                        "Content-Disposition: attachment; filename=\"playlist.m3u\"\r\n" +
                        "Content-Length: ${body.size}\r\n" +
                        "Connection: close\r\n\r\n"
                    out.write(head.toByteArray(Charsets.UTF_8))
                    out.write(body)
                }
                else -> {
                    val notFound = "Not found. Use /playlist.m3u or /download."
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
