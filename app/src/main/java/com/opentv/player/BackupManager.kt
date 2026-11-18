package com.opentv.player

import android.content.Context

/**
 * 8.0.0 — Backup & restore. Exports everything that makes this
 * install "yours" — playlists (including the text of local "file:"
 * playlists, so they survive the move), the active playlist, EPG
 * sources, the recording upload URL, favorites, hidden channels,
 * per-channel EPG overrides, per-channel track picks, custom channel
 * groups (8.1.0), and the guide & playback settings — into one JSON
 * file the user can share or save, and restores it on another device
 * so a new TV starts with the same setup instead of from scratch.
 *
 * The encode / decode is pure (no Context, no Android framework) so
 * it is unit-testable on the JVM: org.json is a framework stub that
 * throws in local unit tests, so the file carries a minimal JSON
 * writer + parser of its own. Only export() / restore() touch
 * SharedPreferences and files.
 */
object BackupManager {

    const val FORMAT = "opentv-backup"
    const val VERSION = 1

    /** One playlist entry in a backup. localText is only set for
     * "file:" playlists — the M3U text itself, so the other device
     * can recreate the file. */
    data class BackupPlaylist(
        val name: String,
        val url: String,
        val enabled: Boolean,
        val localText: String? = null
    )

    /** Everything a backup carries. */
    data class BackupData(
        val playlists: List<BackupPlaylist>,
        val activePlaylistUrl: String?,
        val epgUrls: List<String>,
        val uploadUrl: String,
        val favorites: Set<String>,
        val hiddenChannels: Set<String>,
        val epgOverrides: Map<String, String>,
        val trackPrefs: Map<String, String>,
        /** 8.1.0 — custom channel groups (renames, order, moves). */
        val groupOverrides: GroupOverrides = GroupOverrides(),
        /**
         * 8.1.1 — whether the backup actually carried group
         * overrides. A backup from before 8.1.0 has no
         * "groupOverrides" key and decodes to empty overrides — but
         * "absent" is not "empty": restoring one must NOT wipe the
         * overrides the user has now. Restore only applies them
         * when this is true.
         */
        val hasGroupOverrides: Boolean = true,
        val guideOpensOnCurrentChannel: Boolean,
        val tvInfoFade: Boolean,
        val guideShowsPast: Boolean,
        val guideFavoritesOnly: Boolean,
        val channelListSize: String,
        val guideWindowSize: String,
        /** 8.3.0 — auto-delete old recordings, days to keep (0 = off). */
        val autoDeleteRecordingsDays: Int = 0
    )

    /** What a restore did, for the toast. */
    data class RestoreReport(
        val playlists: Int,
        val favorites: Int,
        val hiddenChannels: Int,
        val epgSources: Int,
        val skippedLocalPlaylists: Int
    )

    // ---- Pure encode / decode ------------------------------------------

    /** Encode a backup as one JSON string. */
    fun encode(data: BackupData): String {
        val root = linkedMapOf<String, Any?>(
            "format" to FORMAT,
            "version" to VERSION,
            "appVersion" to BuildConfig.VERSION_NAME,
            "playlists" to data.playlists.map { p ->
                val entry = linkedMapOf<String, Any?>(
                    "name" to p.name,
                    "url" to p.url,
                    "enabled" to p.enabled
                )
                if (p.localText != null) entry["localText"] = p.localText
                entry
            },
            "activePlaylist" to data.activePlaylistUrl,
            "epgUrls" to data.epgUrls,
            "uploadUrl" to data.uploadUrl,
            "favorites" to data.favorites.sorted(),
            "hiddenChannels" to data.hiddenChannels.sorted(),
            "epgOverrides" to data.epgOverrides,
            "trackPrefs" to data.trackPrefs,
            "groupOverrides" to linkedMapOf<String, Any?>(
                "renames" to data.groupOverrides.renames,
                "order" to data.groupOverrides.order,
                "moves" to data.groupOverrides.moves
            ),
            "settings" to linkedMapOf<String, Any?>(
                "guideOpensOnCurrentChannel" to data.guideOpensOnCurrentChannel,
                "tvInfoFade" to data.tvInfoFade,
                "guideShowsPast" to data.guideShowsPast,
                "guideFavoritesOnly" to data.guideFavoritesOnly,
                "channelListSize" to data.channelListSize,
                "guideWindowSize" to data.guideWindowSize,
                "autoDeleteRecordingsDays" to data.autoDeleteRecordingsDays
            )
        )
        return Json.stringify(root)
    }

    /** Decode a backup file's text. Fails when the text is not a
     * backup at all (bad JSON, wrong format, newer version than this
     * build understands). Missing fields fall back to the defaults,
     * so a backup from an older 8.x still restores. */
    fun decode(text: String): Result<BackupData> {
        val parsed = try {
            Json.parse(text)
        } catch (e: Exception) {
            return Result.failure(IllegalArgumentException("Not valid JSON: ${e.message}"))
        }
        val root = parsed as? Map<*, *> ?: return Result.failure(
            IllegalArgumentException("Backup is not a JSON object")
        )
        if (root["format"] != FORMAT) {
            return Result.failure(IllegalArgumentException("Not an OpenTV backup file"))
        }
        val version = (root["version"] as? Number)?.toInt() ?: 0
        if (version < 1 || version > VERSION) {
            return Result.failure(
                IllegalArgumentException("Backup version $version is not supported")
            )
        }
        val settings = root["settings"] as? Map<*, *>
        // 8.1.0: group overrides are optional — a backup from an
        // older 8.x has no "groupOverrides" key and decodes to empty.
        // 8.1.1: remember whether the key was there at all, so a
        // restore can tell "no overrides in this backup" from "the
        // backup says overrides are empty".
        val groupOverridesRaw = root["groupOverrides"] as? Map<*, *>
        val hasGroupOverrides = root.containsKey("groupOverrides")
        val groupOverrides = GroupOverrides(
            renames = stringMap(groupOverridesRaw?.get("renames")),
            order = stringList(groupOverridesRaw?.get("order")),
            moves = stringMap(groupOverridesRaw?.get("moves"))
        )
        val playlists = (root["playlists"] as? List<*>)?.mapNotNull { item ->
            val entry = item as? Map<*, *> ?: return@mapNotNull null
            val name = entry["name"] as? String ?: return@mapNotNull null
            val url = entry["url"] as? String ?: return@mapNotNull null
            BackupPlaylist(
                name = name,
                url = url,
                enabled = entry["enabled"] as? Boolean ?: true,
                localText = entry["localText"] as? String
            )
        } ?: emptyList()
        return Result.success(
            BackupData(
                playlists = playlists,
                activePlaylistUrl = root["activePlaylist"] as? String,
                epgUrls = stringList(root["epgUrls"]),
                uploadUrl = root["uploadUrl"] as? String ?: "",
                favorites = stringList(root["favorites"]).toSet(),
                hiddenChannels = stringList(root["hiddenChannels"]).toSet(),
                epgOverrides = stringMap(root["epgOverrides"]),
                trackPrefs = stringMap(root["trackPrefs"]),
                groupOverrides = groupOverrides,
                hasGroupOverrides = hasGroupOverrides,
                guideOpensOnCurrentChannel = settings?.get("guideOpensOnCurrentChannel") as? Boolean ?: true,
                tvInfoFade = settings?.get("tvInfoFade") as? Boolean ?: true,
                guideShowsPast = settings?.get("guideShowsPast") as? Boolean ?: true,
                guideFavoritesOnly = settings?.get("guideFavoritesOnly") as? Boolean ?: false,
                channelListSize = settings?.get("channelListSize") as? String ?: "small",
                guideWindowSize = settings?.get("guideWindowSize") as? String ?: "6",
                autoDeleteRecordingsDays = (settings?.get("autoDeleteRecordingsDays") as? Number)
                    ?.toInt()?.coerceAtLeast(0) ?: 0
            )
        )
    }

    private fun stringList(value: Any?): List<String> =
        (value as? List<*>)?.mapNotNull { it as? String } ?: emptyList()

    private fun stringMap(value: Any?): Map<String, String> {
        val map = value as? Map<*, *> ?: return emptyMap()
        return map.entries.mapNotNull { (k, v) ->
            val key = k as? String ?: return@mapNotNull null
            val str = v as? String ?: return@mapNotNull null
            key to str
        }.toMap()
    }

    // ---- Export / restore (Context) ------------------------------------

    /** Read the current setup into a backup. Local ("file:")
     * playlists carry their M3U text so the other device can
     * recreate them. */
    fun export(context: Context): BackupData {
        val playlists = PlaylistRepository.playlists(context).map { p ->
            BackupPlaylist(
                name = p.name,
                url = p.url,
                enabled = p.enabled,
                localText = if (PlaylistRepository.isLocalPlaylist(p.url)) {
                    PlaylistRepository.localPlaylistText(context, p.url)
                } else null
            )
        }
        return BackupData(
            playlists = playlists,
            activePlaylistUrl = PlaylistRepository.activePlaylist(context).url,
            epgUrls = PlaylistRepository.epgUrls(context),
            uploadUrl = PlaylistRepository.uploadUrl(context),
            favorites = PlaylistRepository.favorites(context),
            hiddenChannels = PlaylistRepository.hiddenChannels(context),
            epgOverrides = PlaylistRepository.epgOverrides(context),
            trackPrefs = TrackPrefs.allEntries(context),
            groupOverrides = PlaylistRepository.groupOverrides(context),
            guideOpensOnCurrentChannel = AppSettings.guideOpensOnCurrentChannel(context),
            tvInfoFade = AppSettings.tvInfoFade(context),
            guideShowsPast = AppSettings.guideShowsPast(context),
            guideFavoritesOnly = AppSettings.guideFavoritesOnly(context),
            channelListSize = AppSettings.channelListSize(context).name.lowercase(),
            guideWindowSize = AppSettings.guideWindowSize(context).hours.toString(),
            autoDeleteRecordingsDays = AppSettings.autoDeleteRecordingsDays(context)
        )
    }

    /**
     * Apply a backup. Playlists replace the current list wholesale
     * (a backup with no usable playlist leaves the current list
     * alone); favorites, hidden channels, EPG overrides and track
     * picks replace theirs; EPG sources fall back to the default when
     * the backup has none usable. Returns what was restored, so the
     * caller can report it. Local playlists whose text does not parse
     * are skipped and counted.
     */
    fun restore(context: Context, data: BackupData): RestoreReport {
        val restored = mutableListOf<PlaylistRepository.Playlist>()
        var skippedLocal = 0
        for (entry in data.playlists) {
            if (PlaylistRepository.isLocalPlaylist(entry.url)) {
                val text = entry.localText
                val saved = if (text != null) {
                    PlaylistRepository.storeLocalPlaylistFile(context, entry.name, text)
                } else null
                if (saved != null) {
                    restored += saved.copy(enabled = entry.enabled)
                } else {
                    skippedLocal++
                }
            } else if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) {
                restored += PlaylistRepository.Playlist(entry.name, entry.url, entry.enabled)
            }
        }
        var playlistCount = 0
        if (restored.isNotEmpty() && restored.any { it.enabled }) {
            // The local entries were written as files only — the
            // stored list is replaced here in one go, so the result
            // is exactly the backup's list, in order.
            val active = data.activePlaylistUrl
                ?.takeIf { url -> restored.any { it.url == url && it.enabled } }
                ?: restored.first { it.enabled }.url
            PlaylistRepository.restorePlaylists(context, restored, active)
            playlistCount = restored.size
        }

        val epgSources = data.epgUrls
            .map { it.trim() }
            .filter { it.startsWith("http://") || it.startsWith("https://") }
            .distinct()
        PlaylistRepository.setEpgUrls(
            context,
            epgSources.ifEmpty { listOf(PlaylistRepository.DEFAULT_EPG_URL) }
        )
        PlaylistRepository.setUploadUrl(context, data.uploadUrl)
        PlaylistRepository.setFavorites(context, data.favorites)
        PlaylistRepository.setHiddenChannels(context, data.hiddenChannels)
        PlaylistRepository.setEpgOverrides(context, data.epgOverrides)
        // 8.1.1: only replace the group overrides when the backup
        // actually carried them — a pre-8.1.0 backup has no
        // groupOverrides key, and applying its empty decode would
        // wipe the renames, order and moves the user has now.
        if (data.hasGroupOverrides) {
            PlaylistRepository.setGroupOverrides(context, data.groupOverrides)
        }
        TrackPrefs.restoreAll(context, data.trackPrefs)
        AppSettings.setGuideOpensOnCurrentChannel(context, data.guideOpensOnCurrentChannel)
        AppSettings.setTvInfoFade(context, data.tvInfoFade)
        AppSettings.setGuideShowsPast(context, data.guideShowsPast)
        AppSettings.setGuideFavoritesOnly(context, data.guideFavoritesOnly)
        AppSettings.setChannelListSize(
            context,
            when (data.channelListSize) {
                "medium" -> AppSettings.ChannelListSize.MEDIUM
                "large" -> AppSettings.ChannelListSize.LARGE
                else -> AppSettings.ChannelListSize.SMALL
            }
        )
        AppSettings.setGuideWindowSize(
            context,
            when (data.guideWindowSize) {
                "12" -> AppSettings.GuideWindowSize.TWELVE
                "24" -> AppSettings.GuideWindowSize.TWENTY_FOUR
                else -> AppSettings.GuideWindowSize.SIX
            }
        )
        AppSettings.setAutoDeleteRecordingsDays(context, data.autoDeleteRecordingsDays)
        return RestoreReport(
            playlists = playlistCount,
            favorites = data.favorites.size,
            hiddenChannels = data.hiddenChannels.size,
            epgSources = epgSources.size,
            skippedLocalPlaylists = skippedLocal
        )
    }

    // ---- Minimal JSON (pure Kotlin) --------------------------------------
    //
    // org.json is an Android framework class whose methods are stubs
    // that throw in local unit tests, so the backup carries its own
    // writer + parser. It supports objects, arrays, strings,
    // booleans, numbers and null — everything the backup schema uses.

    private object Json {

        fun stringify(value: Any?): String = buildString { appendValue(value) }

        private fun StringBuilder.appendValue(value: Any?) {
            when (value) {
                null -> append("null")
                is Boolean -> append(if (value) "true" else "false")
                is Number -> append(value.toString())
                is String -> appendQuoted(value)
                is Map<*, *> -> {
                    append('{')
                    var first = true
                    for ((k, v) in value) {
                        if (!first) append(',')
                        first = false
                        appendQuoted(k.toString())
                        append(':')
                        appendValue(v)
                    }
                    append('}')
                }
                is List<*> -> {
                    append('[')
                    var first = true
                    for (item in value) {
                        if (!first) append(',')
                        first = false
                        appendValue(item)
                    }
                    append(']')
                }
                else -> appendQuoted(value.toString())
            }
        }

        private fun StringBuilder.appendQuoted(text: String) {
            append('"')
            for (ch in text) {
                when (ch) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
                }
            }
            append('"')
        }

        fun parse(text: String): Any? {
            val parser = Parser(text)
            val value = parser.value()
            parser.skipWs()
            if (parser.pos != text.length) {
                throw IllegalArgumentException("Trailing data after JSON value")
            }
            return value
        }

        private class Parser(private val text: String) {
            var pos = 0

            fun skipWs() {
                while (pos < text.length && text[pos].isWhitespace()) pos++
            }

            fun value(): Any? {
                skipWs()
                if (pos >= text.length) throw IllegalArgumentException("Unexpected end of JSON")
                return when (val ch = text[pos]) {
                    '{' -> obj()
                    '[' -> array()
                    '"' -> string()
                    't' -> literal("true", true)
                    'f' -> literal("false", false)
                    'n' -> literal("null", null)
                    else -> number()
                }
            }

            private fun literal(word: String, value: Any?): Any? {
                if (!text.startsWith(word, pos)) {
                    throw IllegalArgumentException("Bad literal at $pos")
                }
                pos += word.length
                return value
            }

            private fun obj(): Map<String, Any?> {
                pos++ // {
                val map = linkedMapOf<String, Any?>()
                skipWs()
                if (pos < text.length && text[pos] == '}') {
                    pos++
                    return map
                }
                while (true) {
                    skipWs()
                    val key = string()
                    skipWs()
                    if (pos >= text.length || text[pos] != ':') {
                        throw IllegalArgumentException("Expected ':' at $pos")
                    }
                    pos++
                    map[key] = value()
                    skipWs()
                    if (pos >= text.length) throw IllegalArgumentException("Unterminated object")
                    when (text[pos]) {
                        ',' -> pos++
                        '}' -> {
                            pos++
                            return map
                        }
                        else -> throw IllegalArgumentException("Expected ',' or '}' at $pos")
                    }
                }
            }

            private fun array(): List<Any?> {
                pos++ // [
                val list = mutableListOf<Any?>()
                skipWs()
                if (pos < text.length && text[pos] == ']') {
                    pos++
                    return list
                }
                while (true) {
                    list += value()
                    skipWs()
                    if (pos >= text.length) throw IllegalArgumentException("Unterminated array")
                    when (text[pos]) {
                        ',' -> pos++
                        ']' -> {
                            pos++
                            return list
                        }
                        else -> throw IllegalArgumentException("Expected ',' or ']' at $pos")
                    }
                }
            }

            private fun string(): String {
                if (pos >= text.length || text[pos] != '"') {
                    throw IllegalArgumentException("Expected string at $pos")
                }
                pos++
                val out = StringBuilder()
                while (pos < text.length) {
                    val ch = text[pos++]
                    when (ch) {
                        '"' -> return out.toString()
                        '\\' -> {
                            if (pos >= text.length) break
                            when (val esc = text[pos++]) {
                                '"' -> out.append('"')
                                '\\' -> out.append('\\')
                                '/' -> out.append('/')
                                'b' -> out.append('\b')
                                'f' -> out.append('\u000C')
                                'n' -> out.append('\n')
                                'r' -> out.append('\r')
                                't' -> out.append('\t')
                                'u' -> {
                                    if (pos + 4 > text.length) {
                                        throw IllegalArgumentException("Bad unicode escape")
                                    }
                                    val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                        ?: throw IllegalArgumentException("Bad unicode escape")
                                    pos += 4
                                    out.append(code.toChar())
                                }
                                else -> throw IllegalArgumentException("Bad escape \\$esc")
                            }
                        }
                        else -> out.append(ch)
                    }
                }
                throw IllegalArgumentException("Unterminated string")
            }

            private fun number(): Number {
                val start = pos
                while (pos < text.length && text[pos] in "-+0123456789.eE") pos++
                if (start == pos) throw IllegalArgumentException("Bad number at $pos")
                val raw = text.substring(start, pos)
                return if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
                    raw.toDoubleOrNull()
                        ?: throw IllegalArgumentException("Bad number $raw")
                } else {
                    raw.toLongOrNull()
                        ?: throw IllegalArgumentException("Bad number $raw")
                }
            }
        }
    }
}
