package com.opentv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 7.1.0 (Nova): adjacent-channel stream warm-up.
 *
 * While one channel plays, the previous / next channels' stream
 * URLs are fetched just far enough to prove the path — DNS, TCP,
 * TLS and the origin server all see a real request, and the first
 * bytes of the stream (for HLS, the playlist itself) come back —
 * then the connection is closed. When the viewer zaps to a warmed
 * channel moments later, ExoPlayer skips most of that setup.
 *
 * The player stack has no OkHttp (live playback uses media3's
 * default HttpURLConnection data source, and PlaylistRepository
 * fetches with HttpURLConnection too), so the warm-up uses
 * HttpURLConnection with the app's usual user agent. It is NOT a
 * shared cache and never feeds the player — it only warms the
 * path, which keeps it safe to run beside a playing stream.
 *
 * Safety bounds, deliberately tight:
 * - At most 2 URLs per call, fetched one at a time.
 * - At most [MAX_WARM_BYTES] read per URL, then closed — a
 *   connection/first-bytes warm-up, never a stream download.
 * - 5s connect / read timeouts, so a dead neighbour costs seconds
 *   of background time at most and never blocks anything.
 * - Every failure is swallowed. A warm-up that fails costs
 *   nothing and must never surface to the user.
 * - cancel() drops the in-flight job AND disconnects the live
 *   connection, so a blocking connect/read gives up at once
 *   instead of waiting out its timeout.
 */
class StreamPrefetcher {

    companion object {
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val READ_TIMEOUT_MS = 5_000
        /** Read at most this many bytes of a warmed stream, then
         * let go — enough to pull an HLS playlist or the opening
         * of a TS stream, never a download. */
        private const val MAX_WARM_BYTES = 16 * 1024
        /** Same user agent PlaylistRepository fetches with. */
        private const val USER_AGENT = "OpenTV/0.1.0"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** The connection being warmed right now, so cancel() can
     * force a blocking connect/read to give up immediately. */
    @Volatile
    private var activeConnection: HttpURLConnection? = null

    /** Warm the given stream URLs in order, replacing any warm-up
     * already in flight. Non-HTTP(S) URLs are ignored. */
    fun prefetch(urls: List<String>) {
        cancel()
        val targets = urls
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
            .take(2)
        if (targets.isEmpty()) return
        job = scope.launch {
            for (url in targets) {
                if (!isActive) break
                warm(url)
            }
        }
    }

    /** Stop any in-flight warm-up (channel changed, playback
     * stopped). The scope stays usable for the next prefetch. */
    fun cancel() {
        job?.cancel()
        job = null
        activeConnection?.disconnect()
        activeConnection = null
    }

    /** Cancel and tear the scope down for good (owner destroyed).
     * The instance must not be used afterwards. */
    fun shutdown() {
        cancel()
        scope.cancel()
    }

    /** Open one stream URL, read a capped prefix, close. All
     * failures are swallowed — see the class comment. */
    private fun warm(url: String) {
        var connection: HttpURLConnection? = null
        var stream: InputStream? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            activeConnection = connection
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            stream = connection.inputStream
            val buffer = ByteArray(4096)
            var read = 0
            while (read < MAX_WARM_BYTES) {
                val n = stream.read(buffer, 0, minOf(buffer.size, MAX_WARM_BYTES - read))
                if (n < 0) break
                read += n
            }
        } catch (e: Exception) {
            // Deliberately ignored — a failed warm-up is free.
        } finally {
            try {
                stream?.close()
            } catch (e: Exception) {
                // Ignored, same reason as above.
            }
            // A newer warm-up may have registered its connection
            // while this one was closing — only clear our own.
            if (activeConnection === connection) activeConnection = null
            connection?.disconnect()
        }
    }
}
