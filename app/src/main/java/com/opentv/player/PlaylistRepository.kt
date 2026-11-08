package com.opentv.player

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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
    private const val CACHE_FILE = "playlist.m3u"

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

    /** Fetch the playlist from the network; fall back to the cached copy on failure. */
    suspend fun loadPlaylist(context: Context): Result<List<Channel>> = withContext(Dispatchers.IO) {
        val url = playlistUrl(context)
        try {
            val text = fetchText(url)
            context.openFileOutput(CACHE_FILE, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
            Result.success(M3uParser.parse(text))
        } catch (e: Exception) {
            try {
                val cached = context.openFileInput(CACHE_FILE).bufferedReader().use { it.readText() }
                val channels = M3uParser.parse(cached)
                if (channels.isNotEmpty()) Result.success(channels)
                else Result.failure(e)
            } catch (ignored: Exception) {
                Result.failure(e)
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
     */
    suspend fun loadEpg(context: Context): Map<String, List<EpgParser.Programme>> = withContext(Dispatchers.IO) {
        val merged = mutableMapOf<String, MutableList<EpgParser.Programme>>()
        val urls = epgUrl(context).split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (url in urls) {
            try {
                val text = fetchText(url)
                val parsed = EpgParser.parse(text)
                for ((id, list) in parsed) {
                    merged.getOrPut(id) { mutableListOf() } += list
                }
                val names = EpgParser.channelDisplayNames(text)
                for ((id, name) in names) {
                    val list = parsed[id] ?: continue
                    merged.getOrPut(name.lowercase()) { mutableListOf() } += list
                }
            } catch (ignored: Exception) {
                // One bad source doesn't kill the rest.
            }
        }
        merged
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
