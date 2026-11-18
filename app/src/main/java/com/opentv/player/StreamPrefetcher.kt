package com.opentv.player

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import java.io.ByteArrayOutputStream
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
 *   An HLS playlist spends part of that same budget on the first
 *   URI it lists (7.1.1), so the per-channel total never grows.
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
     * failures are swallowed — see the class comment.
     *
     * 7.1.1 (Nova): if what came back is an HLS playlist, the
     * first URI it lists (a variant playlist from a master, or
     * the first media segment from a media playlist) is warmed
     * too, spending whatever of this call's byte budget the
     * playlist itself did not use — the per-channel total stays
     * capped at [MAX_WARM_BYTES]. */
    private suspend fun warm(url: String, byteBudget: Int = MAX_WARM_BYTES, followPlaylist: Boolean = true) {
        var connection: HttpURLConnection? = null
        var stream: InputStream? = null
        var read = 0
        var followUrl: String? = null
        try {
            connection = URL(url).openConnection() as HttpURLConnection
            activeConnection = connection
            // cancel() unblocks a live read by disconnecting the
            // registered connection, but it can slip in just
            // before this registration, leaving a cancelled job
            // warming a channel nobody wants. Bail out instead —
            // the finally block still closes everything.
            if (!currentCoroutineContext().isActive) return
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            stream = connection.inputStream
            // Playlist bytes are only retained when a follow-up
            // warm is allowed, and never beyond the budget.
            val playlistBytes = if (followPlaylist) ByteArrayOutputStream() else null
            val buffer = ByteArray(4096)
            while (read < byteBudget) {
                val n = stream.read(buffer, 0, minOf(buffer.size, byteBudget - read))
                if (n < 0) break
                playlistBytes?.write(buffer, 0, n)
                read += n
            }
            if (playlistBytes != null && read < byteBudget) {
                followUrl = firstPlaylistUri(url, playlistBytes.toByteArray())
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
        // Follow only once this connection is closed, and only
        // while the job is still wanted.
        val next = followUrl
        if (next != null && currentCoroutineContext().isActive) {
            warm(next, byteBudget - read, followPlaylist = false)
        }
    }

    /** The first URI an HLS playlist lists, resolved against the
     * playlist's own URL — or null when [bytes] are not a playlist
     * or hold no usable HTTP(S) URI. Comment/blank lines and the
     * #EXTM3U header are skipped, so the first hit is the first
     * thing a player would fetch next. */
    private fun firstPlaylistUri(base: String, bytes: ByteArray): String? {
        val text = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
        if (!text.startsWith("#EXTM3U")) return null
        val uri = text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return null
        return try {
            val resolved = URL(URL(base), uri).toString()
            if (resolved.startsWith("http://") || resolved.startsWith("https://")) resolved else null
        } catch (e: Exception) {
            null
        }
    }
}
