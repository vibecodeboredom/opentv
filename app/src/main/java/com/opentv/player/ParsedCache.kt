package com.opentv.player

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * Parsed-data snapshot (4.7.0): TiviMate-style instant loading.
 *
 * The playlist and EPG raw files are cached on disk, but a cold start
 * used to re-parse the whole M3U and the whole XMLTV before the guide or
 * playback could show anything — seconds with thousands of channels.
 * This stores the PARSED result in a small local SQLite database
 * (framework API, no new dependency) right after each parse, so a cold
 * start reads channels + programmes from the database in milliseconds
 * and never re-parses. A stale snapshot is still shown immediately;
 * the repository refreshes it in the background.
 *
 * Tables:
 *  - channels: one row per Channel, all fields (no primary key — a
 *    sloppy playlist can repeat a channel id, and the snapshot must
 *    round-trip the list verbatim). 4.8.0: a source_url column, so each
 *    playlist keeps its own snapshot and switching playlists never
 *    mixes them up.
 *  - epg: the merged EPG map verbatim — one row per programme per map
 *    key (a programme appears under both its channel id and its
 *    lowercased display-name key, exactly like the in-memory map), in
 *    write order (seq) so a round-trip preserves list order.
 *  - meta: snapshot provenance — the source URL and the save time, so a
 *    snapshot for a different playlist/EPG URL is never reused. 4.8.0:
 *    the playlist save time is keyed per URL (playlist_saved_at_<hash>)
 *    instead of a single shared key.
 *
 * Everything is best-effort: any failure returns null / does nothing and
 * the caller falls back to parsing. Writes are a single transaction, so
 * a killed write never leaves a half-written snapshot behind.
 */
object ParsedCache {

    private const val DB_NAME = "parsed_cache.db"
    private const val DB_VERSION = 2

    /** Meta key for a playlist's save time, keyed by its URL hash. */
    private fun playlistSavedAtKey(url: String): String =
        "playlist_saved_at_" + url.hashCode().toString(16)
    private const val META_EPG_URL = "epg_url"
    private const val META_EPG_SAVED_AT = "epg_saved_at"

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE channels (" +
                    "id TEXT NOT NULL, name TEXT NOT NULL, url TEXT NOT NULL, " +
                    "group_name TEXT NOT NULL, logo TEXT, tvg_id TEXT, " +
                    "number INTEGER NOT NULL, source_url TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE epg (" +
                    "map_key TEXT NOT NULL, channel_id TEXT NOT NULL, " +
                    "start_ms INTEGER NOT NULL, stop_ms INTEGER NOT NULL, " +
                    "title TEXT NOT NULL, seq INTEGER NOT NULL)"
            )
            db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            db.execSQL("DROP TABLE IF EXISTS channels")
            db.execSQL("DROP TABLE IF EXISTS epg")
            db.execSQL("DROP TABLE IF EXISTS meta")
            onCreate(db)
        }
    }

    /** Open the DB; on corruption, delete it once and retry. Null if unusable. */
    private fun open(context: Context): SQLiteDatabase? {
        val app = context.applicationContext
        return try {
            Helper(app).writableDatabase
        } catch (e: SQLiteException) {
            try {
                app.deleteDatabase(DB_NAME)
                Helper(app).writableDatabase
            } catch (ignored: Exception) {
                null
            }
        } catch (ignored: Exception) {
            null
        }
    }

    private fun closeQuietly(db: SQLiteDatabase) {
        try {
            db.close()
        } catch (ignored: Exception) {
            // Best-effort.
        }
    }

    private fun readMeta(db: SQLiteDatabase): Map<String, String> {
        val out = mutableMapOf<String, String>()
        db.rawQuery("SELECT key, value FROM meta", null).use { c ->
            val keyI = c.getColumnIndexOrThrow("key")
            val valueI = c.getColumnIndexOrThrow("value")
            while (c.moveToNext()) {
                out[c.getString(keyI)] = c.getString(valueI)
            }
        }
        return out
    }

    private fun writeMeta(db: SQLiteDatabase, key: String, value: String) {
        val v = ContentValues()
        v.put("key", key)
        v.put("value", value)
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The stored playlist for this URL: (savedAt, channels), or null. */
    fun playlistSnapshot(context: Context, url: String): Pair<Long, List<Channel>>? {
        val db = open(context) ?: return null
        try {
            val meta = readMeta(db)
            val savedAt = meta[playlistSavedAtKey(url)]?.toLongOrNull() ?: return null
            val channels = ArrayList<Channel>(2048)
            db.rawQuery(
                "SELECT id, name, url, group_name, logo, tvg_id, number " +
                    "FROM channels WHERE source_url = ? ORDER BY number",
                arrayOf(url)
            ).use { c ->
                val idI = c.getColumnIndexOrThrow("id")
                val nameI = c.getColumnIndexOrThrow("name")
                val urlI = c.getColumnIndexOrThrow("url")
                val groupI = c.getColumnIndexOrThrow("group_name")
                val logoI = c.getColumnIndexOrThrow("logo")
                val tvgIdI = c.getColumnIndexOrThrow("tvg_id")
                val numberI = c.getColumnIndexOrThrow("number")
                while (c.moveToNext()) {
                    channels += Channel(
                        id = c.getString(idI),
                        name = c.getString(nameI),
                        url = c.getString(urlI),
                        group = c.getString(groupI),
                        logo = if (c.isNull(logoI)) null else c.getString(logoI),
                        tvgId = if (c.isNull(tvgIdI)) null else c.getString(tvgIdI),
                        number = c.getInt(numberI)
                    )
                }
            }
            return if (channels.isEmpty()) null else savedAt to channels
        } catch (ignored: Exception) {
            return null
        } finally {
            closeQuietly(db)
        }
    }

    /** Replace the stored playlist snapshot for this URL (single transaction).
     * Other playlists' snapshots are left untouched. */
    fun writePlaylist(context: Context, url: String, channels: List<Channel>) {
        val db = open(context) ?: return
        try {
            db.beginTransaction()
            try {
                db.delete("channels", "source_url = ?", arrayOf(url))
                val stmt = db.compileStatement(
                    "INSERT INTO channels " +
                        "(id, name, url, group_name, logo, tvg_id, number, source_url) " +
                        "VALUES (?,?,?,?,?,?,?,?)"
                )
                try {
                    for (ch in channels) {
                        stmt.bindString(1, ch.id)
                        stmt.bindString(2, ch.name)
                        stmt.bindString(3, ch.url)
                        stmt.bindString(4, ch.group)
                        if (ch.logo == null) stmt.bindNull(5) else stmt.bindString(5, ch.logo)
                        if (ch.tvgId == null) stmt.bindNull(6) else stmt.bindString(6, ch.tvgId)
                        stmt.bindLong(7, ch.number.toLong())
                        stmt.bindString(8, url)
                        stmt.executeInsert()
                    }
                } finally {
                    stmt.close()
                }
                writeMeta(db, playlistSavedAtKey(url), System.currentTimeMillis().toString())
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (ignored: Exception) {
            // Best-effort: a failed write just means the next cold start parses.
        } finally {
            closeQuietly(db)
        }
    }

    /**
     * The stored EPG for this URL: (savedAt, merged map), or null. An
     * empty map is a real snapshot (no guide data) — only a missing or
     * mismatched meta row means "no snapshot".
     */
    fun epgSnapshot(
        context: Context,
        url: String
    ): Pair<Long, Map<String, List<EpgParser.Programme>>>? {
        val db = open(context) ?: return null
        try {
            val meta = readMeta(db)
            if (meta[META_EPG_URL] != url) return null
            val savedAt = meta[META_EPG_SAVED_AT]?.toLongOrNull() ?: return null
            val merged = LinkedHashMap<String, MutableList<EpgParser.Programme>>()
            db.rawQuery(
                "SELECT map_key, channel_id, start_ms, stop_ms, title " +
                    "FROM epg ORDER BY seq",
                null
            ).use { c ->
                val keyI = c.getColumnIndexOrThrow("map_key")
                val channelIdI = c.getColumnIndexOrThrow("channel_id")
                val startI = c.getColumnIndexOrThrow("start_ms")
                val stopI = c.getColumnIndexOrThrow("stop_ms")
                val titleI = c.getColumnIndexOrThrow("title")
                while (c.moveToNext()) {
                    val key = c.getString(keyI)
                    merged.getOrPut(key) { ArrayList() } += EpgParser.Programme(
                        channelId = c.getString(channelIdI),
                        startMillis = c.getLong(startI),
                        stopMillis = c.getLong(stopI),
                        title = c.getString(titleI)
                    )
                }
            }
            return savedAt to merged
        } catch (ignored: Exception) {
            return null
        } finally {
            closeQuietly(db)
        }
    }

    /** Replace the stored EPG snapshot (single transaction). */
    fun writeEpg(
        context: Context,
        url: String,
        merged: Map<String, List<EpgParser.Programme>>
    ) {
        val db = open(context) ?: return
        try {
            db.beginTransaction()
            try {
                db.delete("epg", null, null)
                val stmt = db.compileStatement(
                    "INSERT INTO epg " +
                        "(map_key, channel_id, start_ms, stop_ms, title, seq) " +
                        "VALUES (?,?,?,?,?,?)"
                )
                try {
                    var seq = 0L
                    for ((key, programmes) in merged) {
                        for (p in programmes) {
                            stmt.bindString(1, key)
                            stmt.bindString(2, p.channelId)
                            stmt.bindLong(3, p.startMillis)
                            stmt.bindLong(4, p.stopMillis)
                            stmt.bindString(5, p.title)
                            stmt.bindLong(6, seq++)
                            stmt.executeInsert()
                        }
                    }
                } finally {
                    stmt.close()
                }
                writeMeta(db, META_EPG_URL, url)
                writeMeta(db, META_EPG_SAVED_AT, System.currentTimeMillis().toString())
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        } catch (ignored: Exception) {
            // Best-effort: a failed write just means the next cold start parses.
        } finally {
            closeQuietly(db)
        }
    }
}
