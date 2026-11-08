package com.opentv.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Loads the M3U playlist (from the network, cached locally) and keeps the
 * user's playlist / EPG URLs and favorites in SharedPreferences.
 */
object PlaylistRepository {

    const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/us.m3u"
    // iptv-org's own hosted guides (epg/guides/us.xml) are gone (404) — the
    // project stopped publishing pre-made guide files. Default to a working
    // free US guide. Separate multiple EPG URLs with commas; they merge.
    const val DEFAULT_EPG_URL = "https://i.mjh.nz/PlutoTV/us.xml"

    private const val PREFS = "opentv"
    private const val KEY_PLAYLIST_URL = "playlist_url"
    private const val KEY_EPG_URL = "epg_url"
    private const val KEY_UPLOAD_URL = "upload_url"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HIDDEN = "hidden_channels"
    private const val KEY_EPG_OVERRIDES = "epg_overrides"
    private const val KEY_RECENT = "recent_channels"
    /** Id of the last channel that actually played (4.6.0: resume it on launch). */
    private const val KEY_LAST_CHANNEL = "last_channel"
    /** The overlay's channel strip shows this many most recent channels. */
    private const val MAX_RECENT = 3
    private const val CACHE_FILE = "playlist.m3u"
    private const val EPG_CACHE_PREFIX = "epg_"
    /** Playlist is re-downloaded at most every 6h; EPG at most every 12h. */
    private const val PLAYLIST_TTL_MS = 6L * 60 * 60 * 1000
    private const val EPG_TTL_MS = 12L * 60 * 60 * 1000

    /** In-memory cache so a playlist / EPG is never re-downloaded or
     * re-parsed twice in one session. @Volatile: replaced atomically,
     * worst case a redundant reload, never corruption. */
    @Volatile private var playlistCache: Pair<Long, List<Channel>>? = null
    @Volatile private var epgCache: Pair<Long, Map<String, List<EpgParser.Programme>>>? = null

    /**
     * Background refresh (4.7.0): when a snapshot is stale, the fresh
     * data is fetched on this scope so the UI never blocks on it. The
     * flags keep two callers from launching the same refresh twice.
     */
    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val playlistRefreshRunning = AtomicBoolean(false)
    private val epgRefreshRunning = AtomicBoolean(false)

    /** The in-memory playlist when it is already loaded and fresh, else
     * null. Lets the overlay / sidebar build synchronously in the common
     * case instead of hopping through a coroutine just to hit the cache. */
    fun cachedPlaylist(): List<Channel>? {
        val cached = playlistCache
        return if (cached != null && System.currentTimeMillis() - cached.first < PLAYLIST_TTL_MS) {
            cached.second
        } else null
    }

    /** The in-memory EPG when it is already loaded and fresh, else null. */
    fun cachedEpg(): Map<String, List<EpgParser.Programme>>? {
        val cached = epgCache
        return if (cached != null && System.currentTimeMillis() - cached.first < EPG_TTL_MS) {
            cached.second
        } else null
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun playlistUrl(context: Context): String =
        prefs(context).getString(KEY_PLAYLIST_URL, DEFAULT_PLAYLIST_URL) ?: DEFAULT_PLAYLIST_URL

    fun epgUrl(context: Context): String =
        prefs(context).getString(KEY_EPG_URL, DEFAULT_EPG_URL) ?: DEFAULT_EPG_URL

    /** Base URL recordings are uploaded to (HTTP PUT). Empty = not configured. */
    fun uploadUrl(context: Context): String =
        prefs(context).getString(KEY_UPLOAD_URL, "") ?: ""

    fun setUrls(context: Context, playlistUrl: String, epgUrl: String) {
        prefs(context).edit()
            .putString(KEY_PLAYLIST_URL, playlistUrl.ifBlank { DEFAULT_PLAYLIST_URL })
            .putString(KEY_EPG_URL, epgUrl.ifBlank { DEFAULT_EPG_URL })
            .apply()
        // New sources: drop the cached data so the next load uses them.
        playlistCache = null
        epgCache = null
    }

    fun setUploadUrl(context: Context, uploadUrl: String) {
        prefs(context).edit().putString(KEY_UPLOAD_URL, uploadUrl.trim()).apply()
    }

    fun resetUrls(context: Context) {
        prefs(context).edit()
            .remove(KEY_PLAYLIST_URL)
            .remove(KEY_EPG_URL)
            .remove(KEY_UPLOAD_URL)
            .apply()
    }

    fun favorites(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_FAVORITES, emptySet()) ?: emptySet()

    fun toggleFavorite(context: Context, channelId: String) {
        val current = favorites(context).toMutableSet()
        if (current.contains(channelId)) current.remove(channelId) else current.add(channelId)
        prefs(context).edit().putStringSet(KEY_FAVORITES, current).apply()
    }

    /** Channels the user hid or blocked (by channel id). Hidden from guide + browser. */
    fun hiddenChannels(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_HIDDEN, emptySet()) ?: emptySet()

    fun hideChannel(context: Context, channelId: String) {
        val current = hiddenChannels(context).toMutableSet()
        current.add(channelId)
        prefs(context).edit().putStringSet(KEY_HIDDEN, current).apply()
    }

    fun unhideAllChannels(context: Context) {
        prefs(context).edit().remove(KEY_HIDDEN).apply()
    }

    /** Most recently watched channel ids, most recent first (max 3). */
    fun recentChannelIds(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_RECENT, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /** Record a channel as most recently watched (deduped, max 3). */
    fun recordRecentChannel(context: Context, channelId: String) {
        val current = recentChannelIds(context).toMutableList()
        current.remove(channelId)
        current.add(0, channelId)
        prefs(context).edit()
            .putString(KEY_RECENT, current.take(MAX_RECENT).joinToString("\n"))
            .apply()
    }

    /** Clear the recent-channels list (the overlay's "Clear" tile). */
    fun clearRecentChannels(context: Context) {
        prefs(context).edit().remove(KEY_RECENT).apply()
    }

    /** Id of the last channel that actually played, or "" when none. */
    fun lastChannelId(context: Context): String =
        prefs(context).getString(KEY_LAST_CHANNEL, "") ?: ""

    /** Remember the last channel that actually played (resumed on launch). */
    fun setLastChannelId(context: Context, channelId: String) {
        prefs(context).edit().putString(KEY_LAST_CHANNEL, channelId).apply()
    }

    /** Per-channel EPG id overrides (channel id -> tvg-id), set via "Assign EPG". */
    fun epgOverrides(context: Context): Map<String, String> {
        val raw = prefs(context).getString(KEY_EPG_OVERRIDES, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return raw.split("\n").mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
    }

    fun setEpgOverride(context: Context, channelId: String, tvgId: String) {
        val current = epgOverrides(context).toMutableMap()
        if (tvgId.isBlank()) current.remove(channelId) else current[channelId] = tvgId.trim()
        val raw = current.entries.joinToString("\n") { "${it.key}=${it.value}" }
        prefs(context).edit().putString(KEY_EPG_OVERRIDES, raw).apply()
    }

    /**
     * Fetch the playlist from the network; fall back to the cached copy on
     * failure. The parsed list is kept in memory (6h TTL) and the raw M3U
     * on disk, so launching, opening the guide, or changing channel does
     * not re-download and re-parse the whole playlist every time.
     *
     * 4.7.0 — instant loading: the parsed list is also stored in the
     * SQLite snapshot (ParsedCache) after every parse. A cold start
     * reads the snapshot in milliseconds instead of re-parsing the whole
     * M3U. When the snapshot is stale (>= 6h), it is still returned
     * immediately and the fresh data is fetched in the background, so
     * the UI never blocks on a download or a full re-parse.
     *
     * The disk copy is used when it is fresh (6h TTL) — a cold start
     * (app killed, or after an update) used to re-download the whole
     * M3U before any channel could play, even though the disk copy was
     * still good. Now it only downloads when the disk copy is missing
     * or stale, and falls back to a stale copy when the fetch fails.
     */
    suspend fun loadPlaylist(context: Context): Result<List<Channel>> = withContext(Dispatchers.IO) {
        val cached = playlistCache
        if (cached != null && System.currentTimeMillis() - cached.first < PLAYLIST_TTL_MS) {
            return@withContext Result.success(cached.second)
        }
        val url = playlistUrl(context)
        // Fast path: the parsed snapshot from the last load. The memory
        // cache keeps the snapshot's own save time, so the TTL stays
        // honest — a stale snapshot still triggers a background refresh
        // on the next load, and the refresh replaces it when it lands.
        val snapshot = ParsedCache.playlistSnapshot(context, url)
        if (snapshot != null) {
            val (savedAt, channels) = snapshot
            playlistCache = savedAt to channels
            if (System.currentTimeMillis() - savedAt >= PLAYLIST_TTL_MS) {
                refreshPlaylistInBackground(context, url)
            }
            return@withContext Result.success(channels)
        }
        // No snapshot yet (first run, or a new playlist URL): download /
        // parse as before, then store the snapshot for next time.
        // Fresh disk copy: parse it instead of downloading.
        val file = context.getFileStreamPath(CACHE_FILE)
        if (file.exists() &&
            System.currentTimeMillis() - file.lastModified() < PLAYLIST_TTL_MS
        ) {
            try {
                val cachedText = file.bufferedReader().use { it.readText() }
                val channels = M3uParser.parse(cachedText)
                if (channels.isNotEmpty()) {
                    storePlaylist(context, url, channels)
                    return@withContext Result.success(channels)
                }
            } catch (ignored: Exception) {
                // Fall through to a fresh download.
            }
        }
        try {
            val text = fetchText(url)
            context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
            val channels = M3uParser.parse(text)
            storePlaylist(context, url, channels)
            Result.success(channels)
        } catch (e: Exception) {
            try {
                val cachedText = context.openFileInput(CACHE_FILE).bufferedReader().use { it.readText() }
                val channels = M3uParser.parse(cachedText)
                if (channels.isNotEmpty()) {
                    storePlaylist(context, url, channels)
                    Result.success(channels)
                } else Result.failure(e)
            } catch (ignored: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Remember a parsed playlist in memory + in the disk snapshot. */
    private fun storePlaylist(context: Context, url: String, channels: List<Channel>) {
        playlistCache = System.currentTimeMillis() to channels
        ParsedCache.writePlaylist(context, url, channels)
    }

    /**
     * Re-download + re-parse the playlist in the background when the
     * snapshot is stale. Updates the in-memory cache and the snapshot
     * when the fresh data lands, so the next guide/playback open picks
     * it up. A failed refresh leaves the stale snapshot in place and
     * the next load retries it.
     */
    private fun refreshPlaylistInBackground(context: Context, url: String) {
        if (!playlistRefreshRunning.compareAndSet(false, true)) return
        refreshScope.launch {
            try {
                val appContext = context.applicationContext
                val text = try {
                    fetchText(url).also { fresh ->
                        try {
                            appContext.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE)
                                .use { it.write(fresh.toByteArray()) }
                        } catch (ignored: Exception) {
                            // Disk cache is best-effort.
                        }
                    }
                } catch (e: Exception) {
                    // Offline: fall back to the stale disk copy, if any.
                    try {
                        appContext.openFileInput(CACHE_FILE).bufferedReader().use { it.readText() }
                    } catch (ignored: Exception) {
                        null
                    }
                } ?: return@launch
                val channels = M3uParser.parse(text)
                if (channels.isNotEmpty()) {
                    storePlaylist(appContext, url, channels)
                }
            } catch (ignored: Exception) {
                // A failed refresh just leaves the stale snapshot in place.
            } finally {
                playlistRefreshRunning.set(false)
            }
        }
    }

    /**
     * Fetch the XMLTV EPG. Accepts several URLs separated by commas (or
     * newlines) and merges them. Returns an empty map on any failure (EPG
     * is optional — the guide fills in "No information" placeholders).
     * Programmes are indexed by channel id AND by lowercased display name,
     * so a playlist channel can match its EPG data by name when the ids
     * don't align across sources.
     *
     * The merged result is kept in memory (12h TTL) and each source's raw
     * XML on disk, so the guide and the player don't re-download and
     * re-parse the whole guide on every open. Each source is parsed in a
     * single pass (programmes + display names together).
     *
     * 4.7.0 — instant loading: the merged map is also stored in the
     * SQLite snapshot (ParsedCache) after every parse. A cold start
     * reads it in milliseconds instead of re-parsing the whole XMLTV.
     * A stale snapshot is still returned immediately and refreshed in
     * the background, so the guide never blocks on the EPG.
     */
    suspend fun loadEpg(context: Context): Map<String, List<EpgParser.Programme>> = withContext(Dispatchers.IO) {
        val cached = epgCache
        if (cached != null && System.currentTimeMillis() - cached.first < EPG_TTL_MS) {
            return@withContext cached.second
        }
        val url = epgUrl(context)
        // Fast path: the parsed snapshot from the last load. Like the
        // playlist, the memory cache keeps the snapshot's own save time
        // so a stale snapshot still triggers a background refresh.
        val snapshot = ParsedCache.epgSnapshot(context, url)
        if (snapshot != null) {
            val (savedAt, merged) = snapshot
            epgCache = savedAt to merged
            if (System.currentTimeMillis() - savedAt >= EPG_TTL_MS) {
                refreshEpgInBackground(context, url)
            }
            return@withContext merged
        }
        // No snapshot yet: fetch + parse as before, then store it.
        val merged = loadEpgFresh(context)
        storeEpg(context, url, merged)
        merged
    }

    /** Remember a parsed EPG in memory + in the disk snapshot. */
    private fun storeEpg(
        context: Context,
        url: String,
        merged: Map<String, List<EpgParser.Programme>>
    ) {
        epgCache = System.currentTimeMillis() to merged
        ParsedCache.writeEpg(context, url, merged)
    }

    /**
     * Re-download + re-parse the EPG in the background when the snapshot
     * is stale. Updates the in-memory cache and the snapshot when the
     * fresh data lands, so the next guide open picks it up.
     */
    private fun refreshEpgInBackground(context: Context, url: String) {
        if (!epgRefreshRunning.compareAndSet(false, true)) return
        refreshScope.launch {
            try {
                val appContext = context.applicationContext
                val merged = loadEpgFresh(appContext)
                storeEpg(appContext, url, merged)
            } catch (ignored: Exception) {
                // A failed refresh just leaves the stale snapshot in place.
            } finally {
                epgRefreshRunning.set(false)
            }
        }
    }

    /** Fetch + parse every EPG source and merge them (the old loadEpg body). */
    private fun loadEpgFresh(context: Context): Map<String, List<EpgParser.Programme>> {
        val merged = mutableMapOf<String, MutableList<EpgParser.Programme>>()
        val urls = epgUrl(context).split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (url in urls) {
            try {
                val text = epgXml(context, url)
                val (parsed, names) = EpgParser.parseWithNames(text)
                for ((id, list) in parsed) {
                    merged.getOrPut(id) { mutableListOf() } += list
                }
                for ((id, name) in names) {
                    val list = parsed[id] ?: continue
                    merged.getOrPut(name.lowercase()) { mutableListOf() } += list
                }
            } catch (ignored: Exception) {
                // One bad source doesn't kill the rest.
            }
        }
        return merged
    }

    /**
     * Raw EPG XML for one source URL: the disk copy is used when it is
     * fresh (12h TTL), otherwise the URL is fetched and the disk copy
     * refreshed. Falls back to a stale disk copy when the fetch fails.
     */
    private fun epgXml(context: Context, url: String): String {
        val fileName = EPG_CACHE_PREFIX + url.hashCode().toString(16) + ".xml"
        val file = context.getFileStreamPath(fileName)
        val fresh = file.exists() &&
            System.currentTimeMillis() - file.lastModified() < EPG_TTL_MS
        if (fresh) {
            try {
                return file.bufferedReader().use { it.readText() }
            } catch (ignored: Exception) {
                // Fall through to a fresh fetch.
            }
        }
        return try {
            val text = fetchText(url)
            try {
                context.openFileOutput(fileName, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
            } catch (ignored: Exception) {
                // Disk cache is best-effort.
            }
            text
        } catch (e: Exception) {
            // Fetch failed: use the stale disk copy if there is one.
            if (file.exists()) file.bufferedReader().use { it.readText() } else throw e
        }
    }

    private fun fetchText(urlString: String): String {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("User-Agent", "OpenTV/0.1.0")
        connection.connect()
        if (connection.responseCode !in 200..299) {
            throw IllegalStateException("HTTP ${connection.responseCode} for $urlString")
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }
}
