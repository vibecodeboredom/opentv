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
 *
 * 4.8.0 — multiple playlists: the user can keep several M3U playlists
 * (name + URL) and switch between them, TiviMate-style. Only one is
 * active at a time; the active one's URL is what playlistUrl() returns,
 * so every existing caller keeps working unchanged. A playlist URL may
 * also be a local file (scheme "file:") — a playlist received from
 * another device over the local share (4.7.1/4.8.0) is stored on disk
 * and referenced that way.
 */
object PlaylistRepository {

    /** One playlist entry: a display name, its M3U URL (or "file:" URL),
     * and whether it is enabled. A disabled playlist stays in the list
     * but is never active and never shows as a switchable option — it
     * just sits dormant until re-activated (4.9.0). */
    data class Playlist(val name: String, val url: String, val enabled: Boolean = true)

    const val DEFAULT_PLAYLIST_URL = "https://iptv-org.github.io/iptv/countries/us.m3u"
    // iptv-org's own hosted guides (epg/guides/us.xml) are gone (404) — the
    // project stopped publishing pre-made guide files. Default to a working
    // free US guide. Separate multiple EPG URLs with commas; they merge.
    const val DEFAULT_EPG_URL = "https://i.mjh.nz/PlutoTV/us.xml"

    private const val PREFS = "opentv"
    private const val KEY_PLAYLIST_URL = "playlist_url"
    private const val KEY_EPG_URL = "epg_url"
    /** 4.8.0: the playlist list, one "name\u0001url" entry per line.
     * 4.9.0: a third field, "1"/"0" for enabled/disabled, was added.
     * Entries written before 4.9.0 have no third field and default to
     * enabled, so existing lists migrate transparently. */
    private const val KEY_PLAYLISTS = "playlists"
    /** 4.8.0: the URL of the currently active playlist. */
    private const val KEY_ACTIVE_PLAYLIST = "active_playlist"
    private const val KEY_UPLOAD_URL = "upload_url"
    private const val KEY_FAVORITES = "favorites"
    private const val KEY_HIDDEN = "hidden_channels"
    private const val KEY_EPG_OVERRIDES = "epg_overrides"
    private const val KEY_RECENT = "recent_channels"
    /** 4.9.3: guide search queries, one per line, most recent first. */
    private const val KEY_RECENT_SEARCHES = "recent_searches"
    /** Id of the last channel that actually played (4.6.0: resume it on launch). */
    private const val KEY_LAST_CHANNEL = "last_channel"
    /** The overlay's channel strip shows this many most recent channels. */
    private const val MAX_RECENT = 3
    /** Guide search remembers this many past queries. */
    private const val MAX_RECENT_SEARCHES = 8
    private const val CACHE_FILE = "playlist.m3u"
    /**
     * 4.8.0: a playlist stored on this device (received over the local
     * share) is referenced as "file:<name>" — the file lives in the app's
     * private files dir. No network fetch is ever done for these.
     */
    const val LOCAL_SCHEME = "file:"
    /** Prefix for files holding playlists received over the local share. */
    private const val SHARED_FILE_PREFIX = "shared_"

    /** Per-URL disk cache file (4.8.0): the old single "playlist.m3u"
     * would mix playlists up when switching, so each URL gets its own. */
    private fun playlistCacheFileName(url: String): String =
        "playlist_" + url.hashCode().toString(16) + ".m3u"
    private const val EPG_CACHE_PREFIX = "epg_"
    /** Playlist is re-downloaded at most every 6h; EPG at most every 12h. */
    private const val PLAYLIST_TTL_MS = 6L * 60 * 60 * 1000
    private const val EPG_TTL_MS = 12L * 60 * 60 * 1000

    /** In-memory cache so a playlist / EPG is never re-downloaded or
     * re-parsed twice in one session. @Volatile: replaced atomically,
     * worst case a redundant reload, never corruption. */
    @Volatile private var playlistCache: Pair<Long, List<Channel>>? = null
    @Volatile private var epgCache: Pair<Long, Map<String, List<EpgParser.Programme>>>? = null

    /** Parsed playlist list, cached so every activePlaylist() /
     * enabledPlaylists() call doesn't re-split the stored string and
     * rebuild the entries. The list only ever changes through
     * savePlaylists() / resetUrls(), which invalidate it — and every
     * caller copies before mutating, so the cached instance is never
     * modified in place. */
    @Volatile private var playlistsCache: List<Playlist>? = null

    /** Parsed EPG-overrides map, cached the same way. Only
     * setEpgOverride() changes it, and it invalidates the cache. */
    @Volatile private var epgOverridesCache: Map<String, String>? = null

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

    /**
     * All playlists, in order. Migrates the old single playlist URL into
     * a one-entry list on first use, so existing users lose nothing.
     */
    fun playlists(context: Context): List<Playlist> {
        playlistsCache?.let { return it }
        val raw = prefs(context).getString(KEY_PLAYLISTS, null)
        if (raw != null) {
            val list = parsePlaylists(raw)
            if (list.isNotEmpty()) {
                playlistsCache = list
                return list
            }
        }
        // First run after 4.8.0 (or an empty list): migrate the legacy
        // single URL, or the default when there is none.
        val legacy = prefs(context).getString(KEY_PLAYLIST_URL, null)
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_PLAYLIST_URL
        val migrated = listOf(Playlist(defaultPlaylistName(legacy), legacy))
        savePlaylists(context, migrated)
        prefs(context).edit().putString(KEY_ACTIVE_PLAYLIST, legacy).apply()
        playlistsCache = migrated
        return migrated
    }

    /** The currently active playlist (first ENABLED entry when none is
     * marked, or the marked one was disabled). The active playlist is
     * always an enabled one — a disabled playlist can never be active. */
    fun activePlaylist(context: Context): Playlist {
        val list = playlists(context)
        val activeUrl = prefs(context).getString(KEY_ACTIVE_PLAYLIST, null)
        return list.firstOrNull { it.url == activeUrl && it.enabled }
            ?: list.firstOrNull { it.enabled }
            ?: list.first()
    }

    /** Only the enabled playlists — the ones that can be switched to. */
    fun enabledPlaylists(context: Context): List<Playlist> =
        playlists(context).filter { it.enabled }

    /**
     * Enable or disable a playlist (by URL). Disabling the last enabled
     * playlist is refused — the app always needs one active source.
     * Disabling the active playlist activates the first remaining
     * enabled one. Returns false when nothing changed.
     */
    fun setPlaylistEnabled(context: Context, url: String, enabled: Boolean): Boolean {
        val list = playlists(context)
        val entry = list.firstOrNull { it.url == url } ?: return false
        if (entry.enabled == enabled) return false
        if (!enabled && list.count { it.enabled } <= 1) return false
        val wasActive = activePlaylist(context).url == url
        val updated = list.map { if (it.url == url) it.copy(enabled = enabled) else it }
        savePlaylists(context, updated)
        if (!enabled && wasActive) {
            val next = updated.firstOrNull { it.enabled }
            if (next != null) {
                prefs(context).edit().putString(KEY_ACTIVE_PLAYLIST, next.url).apply()
                playlistCache = null
            }
        }
        return true
    }

    /** URL of the active playlist — what every loader already calls. */
    fun playlistUrl(context: Context): String = activePlaylist(context).url

    /** A playlist URL is a local file when it uses the "file:" scheme. */
    fun isLocalPlaylist(url: String): Boolean = url.startsWith(LOCAL_SCHEME)

    /**
     * Add a playlist (name + http/https URL). A URL already in the list
     * just gets its name updated. Returns the entry, or null when the
     * URL is not a usable playlist URL.
     */
    fun addPlaylist(context: Context, name: String, url: String): Playlist? {
        val cleanUrl = url.trim()
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) return null
        val cleanName = cleanPlaylistName(name).ifBlank { defaultPlaylistName(cleanUrl) }
        val list = playlists(context).toMutableList()
        val existing = list.indexOfFirst { it.url == cleanUrl }
        // Re-adding a URL that's already listed only updates its name —
        // its enabled state is kept, so a disabled playlist isn't
        // silently re-enabled by an import or a duplicate add.
        val entry = Playlist(cleanName, cleanUrl, list.getOrNull(existing)?.enabled ?: true)
        if (existing >= 0) list[existing] = entry else list += entry
        savePlaylists(context, list)
        return entry
    }

    /** Rename a playlist (by URL). No-op when the URL is not listed. */
    fun renamePlaylist(context: Context, url: String, newName: String) {
        val cleanName = cleanPlaylistName(newName)
        if (cleanName.isBlank()) return
        val list = playlists(context)
            .map { if (it.url == url) it.copy(name = cleanName) else it }
        savePlaylists(context, list)
    }

    /**
     * Remove a playlist (by URL). Refuses when it is the last one, and
     * when removing it would leave no enabled playlist at all.
     * Removing the active playlist activates the first remaining
     * ENABLED one. Returns false when nothing was removed.
     */
    fun removePlaylist(context: Context, url: String): Boolean {
        val list = playlists(context).toMutableList()
        if (list.size <= 1) return false
        val remaining = list.filter { it.url != url }
        if (remaining.none { it.enabled }) return false
        val wasActive = activePlaylist(context).url == url
        val removed = list.removeAll { it.url == url }
        if (!removed) return false
        savePlaylists(context, list)
        if (wasActive) {
            val next = list.firstOrNull { it.enabled } ?: list.first()
            prefs(context).edit().putString(KEY_ACTIVE_PLAYLIST, next.url).apply()
            playlistCache = null
        }
        return true
    }

    /**
     * Make a listed playlist active. Refuses when the playlist is
     * disabled (it must be re-enabled first). Clears the in-memory
     * playlist cache so the next load fetches the new source (the EPG
     * cache is global and stays — it is keyed by channel id/name
     * either way).
     */
    fun setActivePlaylist(context: Context, url: String): Boolean {
        val entry = playlists(context).firstOrNull { it.url == url } ?: return false
        if (!entry.enabled) return false
        prefs(context).edit().putString(KEY_ACTIVE_PLAYLIST, url).apply()
        playlistCache = null
        return true
    }

    /**
     * Store an M3U text received over the local share as a local file
     * and add it to the playlist list. Returns the new entry, or null
     * when the text is not an M3U playlist. 4.9.1: also returns null
     * when the text parses to zero channels — a header-only file
     * ("#EXTM3U" and nothing else) used to pass the header check, get
     * added to the list, and then fail every load with "couldn't load
     * playlist", because loadPlaylist requires at least one channel
     * and no snapshot ever existed for it. Rejecting it here means an
     * un-loadable entry can never be created.
     */
    fun saveLocalPlaylist(context: Context, name: String, text: String): Playlist? {
        if (!text.trimStart().startsWith("#EXTM3U")) return null
        if (M3uParser.parse(text).isEmpty()) return null
        val fileName = SHARED_FILE_PREFIX + System.currentTimeMillis() + ".m3u"
        return try {
            context.openFileOutput(fileName, Context.MODE_PRIVATE)
                .use { it.write(text.toByteArray(Charsets.UTF_8)) }
            val cleanName = cleanPlaylistName(name).ifBlank { "Shared playlist" }
            val entry = Playlist(cleanName, LOCAL_SCHEME + fileName)
            val list = playlists(context).toMutableList()
            // A distinct file name per upload, so this is always an append.
            list += entry
            savePlaylists(context, list)
            entry
        } catch (ignored: Exception) {
            null
        }
    }

    /** The raw text of a local ("file:") playlist, or null. */
    fun localPlaylistText(context: Context, url: String): String? {
        if (!isLocalPlaylist(url)) return null
        return try {
            val file = context.getFileStreamPath(url.removePrefix(LOCAL_SCHEME))
            if (!file.exists()) null
            else file.bufferedReader().use { it.readText() }.takeIf { it.isNotBlank() }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun savePlaylists(context: Context, list: List<Playlist>) {
        playlistsCache = null
        val raw = list.joinToString("\n") {
            "${it.name}\u0001${it.url}\u0001${if (it.enabled) "1" else "0"}"
        }
        prefs(context).edit().putString(KEY_PLAYLISTS, raw).apply()
    }

    private fun parsePlaylists(raw: String): List<Playlist> =
        raw.split("\n").mapNotNull { line ->
            val parts = line.split("\u0001")
            if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) null
            // Entries written before 4.9.0 have no third field — they
            // default to enabled, so existing lists migrate as-is.
            else Playlist(parts[0], parts[1], parts.getOrNull(2) != "0")
        }.filter { it.url.isNotBlank() }

    /** Names never carry newlines/tabs (they would break the storage format). */
    private fun cleanPlaylistName(name: String): String =
        name.replace("\n", " ").replace("\r", " ").replace("\u0001", " ")
            .replace("\t", " ").trim()

    /** A readable default name for a URL: the host, or "US Playlist"
     * for the bundled iptv-org default. */
    private fun defaultPlaylistName(url: String): String {
        if (url == DEFAULT_PLAYLIST_URL) return "US Playlist"
        return try {
            URL(url).host?.takeIf { it.isNotBlank() } ?: "Playlist"
        } catch (e: Exception) {
            "Playlist"
        }
    }

    fun epgUrl(context: Context): String =
        prefs(context).getString(KEY_EPG_URL, DEFAULT_EPG_URL) ?: DEFAULT_EPG_URL

    /** Base URL recordings are uploaded to (HTTP PUT). Empty = not configured. */
    fun uploadUrl(context: Context): String =
        prefs(context).getString(KEY_UPLOAD_URL, "") ?: ""

    /** Change the EPG URL (the playlist list has its own editor now). */
    fun setEpgUrl(context: Context, epgUrl: String) {
        prefs(context).edit()
            .putString(KEY_EPG_URL, epgUrl.ifBlank { DEFAULT_EPG_URL })
            .apply()
        epgCache = null
    }

    fun setUploadUrl(context: Context, uploadUrl: String) {
        prefs(context).edit().putString(KEY_UPLOAD_URL, uploadUrl.trim()).apply()
    }

    /** Reset to the single default playlist + default EPG (keeps the
     * legacy keys cleared so a later migration starts clean). */
    fun resetUrls(context: Context) {
        playlistsCache = null
        epgOverridesCache = null
        prefs(context).edit()
            .remove(KEY_PLAYLIST_URL)
            .remove(KEY_EPG_URL)
            .remove(KEY_UPLOAD_URL)
            .remove(KEY_PLAYLISTS)
            .remove(KEY_ACTIVE_PLAYLIST)
            .apply()
        playlistCache = null
        epgCache = null
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

    /**
     * Recent guide searches (4.9.3), most recent first. Stored as one
     * query per line — a StringSet would lose the order.
     */
    fun recentSearches(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_RECENT_SEARCHES, "") ?: ""
        return raw.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    /**
     * Remember a guide search query (deduped case-insensitively, most
     * recent first, max 8). Call only when a query is actually
     * submitted (Enter) or re-run from the recent list — not on every
     * keystroke, or half-typed text would fill the list.
     */
    fun saveRecentSearch(context: Context, query: String) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val current = recentSearches(context).toMutableList()
        current.removeAll { it.equals(trimmed, ignoreCase = true) }
        current.add(0, trimmed)
        prefs(context).edit()
            .putString(KEY_RECENT_SEARCHES, current.take(MAX_RECENT_SEARCHES).joinToString("\n"))
            .apply()
    }

    /** Clear the recent-searches list (the menu's "Clear" action). */
    fun clearRecentSearches(context: Context) {
        prefs(context).edit().remove(KEY_RECENT_SEARCHES).apply()
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
        epgOverridesCache?.let { return it }
        val raw = prefs(context).getString(KEY_EPG_OVERRIDES, "") ?: ""
        if (raw.isBlank()) {
            epgOverridesCache = emptyMap()
            return emptyMap()
        }
        val map = raw.split("\n").mapNotNull { line ->
            val idx = line.indexOf('=')
            if (idx <= 0) null else line.substring(0, idx) to line.substring(idx + 1)
        }.toMap()
        epgOverridesCache = map
        return map
    }

    fun setEpgOverride(context: Context, channelId: String, tvgId: String) {
        val current = epgOverrides(context).toMutableMap()
        if (tvgId.isBlank()) current.remove(channelId) else current[channelId] = tvgId.trim()
        val raw = current.entries.joinToString("\n") { "${it.key}=${it.value}" }
        prefs(context).edit().putString(KEY_EPG_OVERRIDES, raw).apply()
        epgOverridesCache = null
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
        // Local playlist (received over the share): the source file IS
        // the cache — read and parse it directly, never a network fetch.
        // 4.9.1: the two failure modes get distinct messages so the
        // error is diagnosable instead of the generic "check your
        // connection" line — a missing file and a file with no channels
        // are different problems. (A snapshot, when one exists, was
        // already returned above, so reaching here means there is
        // nothing to fall back to.)
        if (isLocalPlaylist(url)) {
            val text = localPlaylistText(context, url)
            if (text == null) {
                return@withContext Result.failure(
                    IllegalStateException("Local playlist file is missing: $url")
                )
            }
            val channels = M3uParser.parse(text)
            if (channels.isNotEmpty()) {
                storePlaylist(context, url, channels)
                return@withContext Result.success(channels)
            }
            return@withContext Result.failure(
                IllegalStateException("Local playlist has no channels: $url")
            )
        }
        // No snapshot yet (first run, or a new playlist URL): download /
        // parse as before, then store the snapshot for next time.
        // Fresh disk copy: parse it instead of downloading. The disk
        // copy is per-URL (4.8.0) so switching playlists never mixes
        // them up; the old single file is only a one-time fallback for
        // the cache written before 4.8.0 (it belonged to the then-only
        // playlist, which the migration made active).
        val file = context.getFileStreamPath(playlistCacheFileName(url))
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
        val legacy = context.getFileStreamPath(CACHE_FILE)
        if (legacy.exists() &&
            System.currentTimeMillis() - legacy.lastModified() < PLAYLIST_TTL_MS
        ) {
            try {
                val cachedText = legacy.bufferedReader().use { it.readText() }
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
            context.openFileOutput(playlistCacheFileName(url), Context.MODE_PRIVATE)
                .use { it.write(text.toByteArray()) }
            val channels = M3uParser.parse(text)
            storePlaylist(context, url, channels)
            Result.success(channels)
        } catch (e: Exception) {
            try {
                val cachedText = context.openFileInput(playlistCacheFileName(url))
                    .bufferedReader().use { it.readText() }
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
     * The raw M3U text of the currently configured playlist: the cached
     * disk copy when present, otherwise a fresh fetch of the playlist URL.
     * Used by the temporary local share (4.7.1) so other devices can
     * import exactly what this TV is playing. A local playlist is read
     * straight from its file.
     */
    suspend fun playlistText(context: Context): Result<String> = withContext(Dispatchers.IO) {
        val url = playlistUrl(context)
        if (isLocalPlaylist(url)) {
            val text = localPlaylistText(context, url)
            return@withContext if (text != null) Result.success(text)
            else Result.failure(IllegalStateException("Local playlist file is missing: $url"))
        }
        val file = context.getFileStreamPath(playlistCacheFileName(url))
        if (file.exists()) {
            try {
                val text = file.bufferedReader().use { it.readText() }
                if (text.isNotBlank()) return@withContext Result.success(text)
            } catch (ignored: Exception) {
                // Fall through to a fresh fetch.
            }
        }
        fetchUrlText(url)
    }

    /** Fetch the raw text of any URL (the share's "another URL" source). */
    suspend fun fetchUrlText(url: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            Result.success(fetchText(url))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Re-download + re-parse the playlist in the background when the
     * snapshot is stale. Updates the in-memory cache and the snapshot
     * when the fresh data lands, so the next guide/playback open picks
     * it up. A failed refresh leaves the stale snapshot in place and
     * the next load retries it.
     *
     * 4.9.1: a local playlist is re-read from its file here instead of
     * being skipped — a file replaced on disk (or repaired after a bad
     * upload) gets picked up, and the snapshot is refreshed. It is
     * NEVER fetched over HTTP. Only a non-empty parse replaces the
     * snapshot, so a deleted or emptied file can never clobber the
     * good channels already stored.
     */
    private fun refreshPlaylistInBackground(context: Context, url: String) {
        if (!playlistRefreshRunning.compareAndSet(false, true)) return
        refreshScope.launch {
            try {
                val appContext = context.applicationContext
                if (isLocalPlaylist(url)) {
                    val text = localPlaylistText(appContext, url)
                    if (text != null) {
                        val channels = M3uParser.parse(text)
                        if (channels.isNotEmpty()) {
                            storePlaylist(appContext, url, channels)
                        }
                    }
                    return@launch
                }
                val text = try {
                    fetchText(url).also { fresh ->
                        try {
                            appContext.openFileOutput(playlistCacheFileName(url), Context.MODE_PRIVATE)
                                .use { it.write(fresh.toByteArray()) }
                        } catch (ignored: Exception) {
                            // Disk cache is best-effort.
                        }
                    }
                } catch (e: Exception) {
                    // Offline: fall back to the stale disk copy, if any.
                    try {
                        appContext.openFileInput(playlistCacheFileName(url))
                            .bufferedReader().use { it.readText() }
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
