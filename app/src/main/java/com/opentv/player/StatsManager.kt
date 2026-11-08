package com.opentv.player

import android.content.Context
import org.json.JSONObject

/**
 * Local-only viewing statistics. Everything is stored in SharedPreferences
 * on this device and never leaves it — no account, no network, no sharing.
 *
 * Tracks, per channel: total watch time, number of sessions, last watched.
 * PlaybackActivity logs a session when playback ends (sessions under 5s
 * are ignored so accidental opens don't count).
 */
object StatsManager {

    private const val PREFS = "opentv_stats"
    private const val KEY_DATA = "stats_json"
    private const val MIN_SESSION_MS = 5_000L

    data class ChannelStat(
        val id: String,
        val name: String,
        val watchMillis: Long,
        val sessions: Int,
        val lastWatchedMillis: Long
    )

    data class Stats(
        val totalWatchMillis: Long,
        val totalSessions: Int,
        val topChannels: List<ChannelStat>
    )

    /** Log a finished watch session. */
    @Synchronized
    fun logWatchSession(context: Context, id: String, name: String, durationMillis: Long) {
        if (durationMillis < MIN_SESSION_MS) return
        val data = load(context)
        val now = System.currentTimeMillis()
        val channels = data.optJSONObject("channels") ?: JSONObject()
        val existing = channels.optJSONObject(id)
        val updated = JSONObject().apply {
            put("name", name)
            put("ms", (existing?.optLong("ms") ?: 0L) + durationMillis)
            put("sessions", (existing?.optInt("sessions") ?: 0) + 1)
            put("last", now)
        }
        channels.put(id, updated)
        data.put("channels", channels)
        data.put("totalMs", data.optLong("totalMs") + durationMillis)
        data.put("totalSessions", data.optInt("totalSessions") + 1)
        save(context, data)
    }

    /** Read the current statistics. */
    fun getStats(context: Context): Stats {
        val data = load(context)
        val channelsObj = data.optJSONObject("channels") ?: JSONObject()
        val channels = mutableListOf<ChannelStat>()
        val keys = channelsObj.keys()
        while (keys.hasNext()) {
            val id = keys.next()
            val c = channelsObj.optJSONObject(id) ?: continue
            channels += ChannelStat(
                id = id,
                name = c.optString("name", id),
                watchMillis = c.optLong("ms"),
                sessions = c.optInt("sessions"),
                lastWatchedMillis = c.optLong("last")
            )
        }
        channels.sortByDescending { it.watchMillis }
        return Stats(
            totalWatchMillis = data.optLong("totalMs"),
            totalSessions = data.optInt("totalSessions"),
            topChannels = channels
        )
    }

    /** Erase all statistics. */
    @Synchronized
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_DATA).apply()
    }

    /** Human-readable duration: "2h 15m" / "45m" / "30s". */
    fun formatDuration(millis: Long): String {
        val totalSeconds = millis / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            else -> "${seconds}s"
        }
    }

    private fun load(context: Context): JSONObject {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DATA, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (ignored: Exception) {
            JSONObject()
        }
    }

    private fun save(context: Context, data: JSONObject) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_DATA, data.toString()).apply()
    }
}
