package com.opentv.player

import android.content.Context
import java.io.File

/**
 * 10.0.0 (Nova): backup & restore of the user's Nova data — one
 * JSON file in the app's external files directory, written by
 * Settings' Export and read back by Import.
 *
 * What round-trips (everything the stores hold as plain user
 * data): the playlist list + the active playlist, the EPG and
 * upload URLs, favorites and the custom favorite order, hidden
 * channels, programme reminders (the watchlist reads the same
 * store), scheduled recordings, per-channel EPG overrides, the
 * guide keywords, and the Nova preferences (accent, guide text
 * size, channel-list size, favorites zap, night dim, quality
 * mode, guide zoom, idle saver, surf interval, the guide/info
 * toggles and the recordings auto-delete cap).
 *
 * What deliberately does NOT: the Settings PIN (a backup must
 * never carry a credential, even a hashed one), recording and
 * catch-up resume points (they name local recording files and
 * expire on their own), recent channels / searches, last and
 * previous channel, guide memory and the per-playlist group
 * filter (session state, meaningless on a fresh install), and
 * the storage guard's one-shot status notes. Local ("file:")
 * playlists restore as entries — their M3U text lives in a
 * separate internal file the backup does not carry, so on a
 * different device such an entry has nothing to load until the
 * file is shared over again.
 *
 * The serialisation is a small self-contained JSON codec (pure
 * JVM, no Android types) so the round-trip is unit-testable.
 * Import is strict: [parseBackup] validates the WHOLE file into
 * a [BackupData] first — a malformed file parses to null and
 * nothing is applied, so a bad backup can never half-write the
 * stores. A section absent from the file leaves the current
 * store untouched; a present section replaces it. Individual
 * preference values are still range-checked by their own
 * setters on apply, exactly as if the user had set them.
 */
object NovaBackup {

    const val FORMAT_MARKER = "opentv-nova-backup"
    const val FORMAT_VERSION = 1
    const val FILE_NAME = "opentv-nova-backup.json"

    data class PlaylistEntry(val name: String, val url: String, val enabled: Boolean)

    data class ReminderEntry(
        val channelId: String,
        val channelName: String,
        val startMillis: Long,
        val stopMillis: Long,
        val title: String
    )

    data class ScheduledEntry(
        val channelId: String,
        val channelName: String,
        val channelUrl: String,
        val startMillis: Long,
        val stopMillis: Long,
        val title: String
    )

    /** The Nova preferences that round-trip. Every field is
     * nullable: null means "not in the file — leave it alone". */
    data class SettingsData(
        val accentChoice: Int? = null,
        val guideTextSize: Int? = null,
        val channelListSize: String? = null,
        val favoritesZap: Boolean? = null,
        val nightDimPercent: Int? = null,
        val qualityMode: Int? = null,
        val guideZoomHours: Int? = null,
        val idleSaverEnabled: Boolean? = null,
        val surfIntervalSeconds: Int? = null,
        val guideOpensOnCurrentChannel: Boolean? = null,
        val tvInfoFade: Boolean? = null,
        val guideShowsPast: Boolean? = null,
        val recordingAutoDeleteCapBytes: Long? = null
    )

    /** One backup file's contents. Every section is nullable:
     * null means the file has no such section and the matching
     * store stays as it is on import. */
    data class BackupData(
        val playlists: List<PlaylistEntry>? = null,
        val activePlaylistUrl: String? = null,
        val epgUrl: String? = null,
        val uploadUrl: String? = null,
        val favorites: List<String>? = null,
        val favoriteOrder: List<String>? = null,
        val hiddenChannels: List<String>? = null,
        val reminders: List<ReminderEntry>? = null,
        val scheduledRecordings: List<ScheduledEntry>? = null,
        val epgOverrides: Map<String, String>? = null,
        val keywords: List<String>? = null,
        val settings: SettingsData? = null
    )

    // ---- Serialise ---------------------------------------------------

    /** Render [data] as the backup file's JSON text. */
    fun encode(data: BackupData): String {
        val root = LinkedHashMap<String, Any?>()
        root["format"] = FORMAT_MARKER
        root["version"] = FORMAT_VERSION
        data.playlists?.let { list ->
            root["playlists"] = list.map { p ->
                linkedMapOf<String, Any?>(
                    "name" to p.name, "url" to p.url, "enabled" to p.enabled
                )
            }
        }
        data.activePlaylistUrl?.let { root["activePlaylistUrl"] = it }
        data.epgUrl?.let { root["epgUrl"] = it }
        data.uploadUrl?.let { root["uploadUrl"] = it }
        data.favorites?.let { root["favorites"] = it }
        data.favoriteOrder?.let { root["favoriteOrder"] = it }
        data.hiddenChannels?.let { root["hiddenChannels"] = it }
        data.reminders?.let { list ->
            root["reminders"] = list.map { r ->
                linkedMapOf<String, Any?>(
                    "channelId" to r.channelId,
                    "channelName" to r.channelName,
                    "startMillis" to r.startMillis,
                    "stopMillis" to r.stopMillis,
                    "title" to r.title
                )
            }
        }
        data.scheduledRecordings?.let { list ->
            root["scheduledRecordings"] = list.map { r ->
                linkedMapOf<String, Any?>(
                    "channelId" to r.channelId,
                    "channelName" to r.channelName,
                    "channelUrl" to r.channelUrl,
                    "startMillis" to r.startMillis,
                    "stopMillis" to r.stopMillis,
                    "title" to r.title
                )
            }
        }
        data.epgOverrides?.let { root["epgOverrides"] = LinkedHashMap(it) }
        data.keywords?.let { root["keywords"] = it }
        data.settings?.let { s ->
            val m = LinkedHashMap<String, Any?>()
            s.accentChoice?.let { m["accentChoice"] = it }
            s.guideTextSize?.let { m["guideTextSize"] = it }
            s.channelListSize?.let { m["channelListSize"] = it }
            s.favoritesZap?.let { m["favoritesZap"] = it }
            s.nightDimPercent?.let { m["nightDimPercent"] = it }
            s.qualityMode?.let { m["qualityMode"] = it }
            s.guideZoomHours?.let { m["guideZoomHours"] = it }
            s.idleSaverEnabled?.let { m["idleSaverEnabled"] = it }
            s.surfIntervalSeconds?.let { m["surfIntervalSeconds"] = it }
            s.guideOpensOnCurrentChannel?.let { m["guideOpensOnCurrentChannel"] = it }
            s.tvInfoFade?.let { m["tvInfoFade"] = it }
            s.guideShowsPast?.let { m["guideShowsPast"] = it }
            s.recordingAutoDeleteCapBytes?.let { m["recordingAutoDeleteCapBytes"] = it }
            root["settings"] = m
        }
        val sb = StringBuilder()
        writeValue(sb, root, 0)
        sb.append('\n')
        return sb.toString()
    }

    // ---- Parse & validate ---------------------------------------------

    /**
     * Parse and fully validate a backup file's text. Returns the
     * data, or null when the file is not a readable Nova backup
     * (bad JSON, wrong marker, a newer format version, or any
     * section with the wrong shape) — the caller then applies
     * nothing at all.
     */
    fun parseBackup(text: String): BackupData? {
        val root = try {
            JsonParser(text).parseDocument()
        } catch (e: Exception) {
            return null
        }
        val obj = root as? Map<*, *> ?: return null
        if (obj["format"] != FORMAT_MARKER) return null
        val version = (obj["version"] as? Number)?.toLong() ?: return null
        if (version < 1 || version > FORMAT_VERSION) return null

        val playlists = if (!obj.containsKey("playlists")) null
            else parsePlaylists(obj["playlists"]) ?: return null
        val activePlaylistUrl = when (val v = obj["activePlaylistUrl"]) {
            null -> null
            is String -> v
            else -> return null
        }
        val epgUrl = when (val v = obj["epgUrl"]) {
            null -> null
            is String -> v
            else -> return null
        }
        val uploadUrl = when (val v = obj["uploadUrl"]) {
            null -> null
            is String -> v
            else -> return null
        }
        val favorites = if (!obj.containsKey("favorites")) null
            else stringListOrNull(obj["favorites"]) ?: return null
        val favoriteOrder = if (!obj.containsKey("favoriteOrder")) null
            else stringListOrNull(obj["favoriteOrder"]) ?: return null
        val hiddenChannels = if (!obj.containsKey("hiddenChannels")) null
            else stringListOrNull(obj["hiddenChannels"]) ?: return null
        val reminders = if (!obj.containsKey("reminders")) null
            else parseReminders(obj["reminders"]) ?: return null
        val scheduled = if (!obj.containsKey("scheduledRecordings")) null
            else parseScheduled(obj["scheduledRecordings"]) ?: return null
        val epgOverrides = if (!obj.containsKey("epgOverrides")) null
            else parseStringMap(obj["epgOverrides"]) ?: return null
        val keywords = if (!obj.containsKey("keywords")) null
            else stringListOrNull(obj["keywords"]) ?: return null
        val settings = if (!obj.containsKey("settings")) null
            else parseSettings(obj["settings"]) ?: return null

        return BackupData(
            playlists = playlists,
            activePlaylistUrl = activePlaylistUrl,
            epgUrl = epgUrl,
            uploadUrl = uploadUrl,
            favorites = favorites,
            favoriteOrder = favoriteOrder,
            hiddenChannels = hiddenChannels,
            reminders = reminders,
            scheduledRecordings = scheduled,
            epgOverrides = epgOverrides,
            keywords = keywords,
            settings = settings
        )
    }

    private fun stringListOrNull(value: Any?): List<String>? {
        val list = value as? List<*> ?: return null
        val out = ArrayList<String>(list.size)
        for (item in list) out.add(item as? String ?: return null)
        return out
    }

    private fun parseStringMap(value: Any?): Map<String, String>? {
        val map = value as? Map<*, *> ?: return null
        val out = LinkedHashMap<String, String>()
        for ((k, v) in map) {
            val key = k as? String ?: return null
            val entry = v as? String ?: return null
            out[key] = entry
        }
        return out
    }

    private fun parsePlaylists(value: Any?): List<PlaylistEntry>? {
        val list = value as? List<*> ?: return null
        // A real backup always names at least one playlist; an
        // empty list would wipe the user's playlists on apply.
        if (list.isEmpty()) return null
        val out = ArrayList<PlaylistEntry>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: return null
            val name = m["name"] as? String ?: return null
            val url = m["url"] as? String ?: return null
            if (url.isBlank()) return null
            // Missing "enabled" reads as enabled, mirroring the
            // store's own pre-4.9.0 migration.
            val enabled = when (val e = m["enabled"]) {
                null -> true
                is Boolean -> e
                else -> return null
            }
            out.add(PlaylistEntry(name, url, enabled))
        }
        return out
    }

    private fun parseReminders(value: Any?): List<ReminderEntry>? {
        val list = value as? List<*> ?: return null
        val out = ArrayList<ReminderEntry>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: return null
            out.add(
                ReminderEntry(
                    channelId = m["channelId"] as? String ?: return null,
                    channelName = m["channelName"] as? String ?: return null,
                    startMillis = (m["startMillis"] as? Number)?.toLong() ?: return null,
                    stopMillis = (m["stopMillis"] as? Number)?.toLong() ?: return null,
                    title = m["title"] as? String ?: return null
                )
            )
        }
        return out
    }

    private fun parseScheduled(value: Any?): List<ScheduledEntry>? {
        val list = value as? List<*> ?: return null
        val out = ArrayList<ScheduledEntry>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: return null
            out.add(
                ScheduledEntry(
                    channelId = m["channelId"] as? String ?: return null,
                    channelName = m["channelName"] as? String ?: return null,
                    channelUrl = m["channelUrl"] as? String ?: return null,
                    startMillis = (m["startMillis"] as? Number)?.toLong() ?: return null,
                    stopMillis = (m["stopMillis"] as? Number)?.toLong() ?: return null,
                    title = m["title"] as? String ?: return null
                )
            )
        }
        return out
    }

    private fun parseSettings(value: Any?): SettingsData? {
        val m = value as? Map<*, *> ?: return null
        // A field present with the wrong type rejects the whole
        // file — silently dropping it would restore something
        // other than what the backup says. Unknown keys are
        // ignored, so a file from a later 10.x patch still reads.
        for ((key, v) in m) {
            when (key) {
                "accentChoice", "guideTextSize", "nightDimPercent", "qualityMode",
                "guideZoomHours", "surfIntervalSeconds", "recordingAutoDeleteCapBytes" ->
                    if (v !is Number) return null
                "favoritesZap", "idleSaverEnabled", "guideOpensOnCurrentChannel",
                "tvInfoFade", "guideShowsPast" ->
                    if (v !is Boolean) return null
                "channelListSize" ->
                    if (v !is String) return null
            }
        }
        fun int(key: String): Int? = (m[key] as? Number)?.toInt()
        fun bool(key: String): Boolean? = m[key] as? Boolean
        return SettingsData(
            accentChoice = int("accentChoice"),
            guideTextSize = int("guideTextSize"),
            channelListSize = m["channelListSize"] as? String,
            favoritesZap = bool("favoritesZap"),
            nightDimPercent = int("nightDimPercent"),
            qualityMode = int("qualityMode"),
            guideZoomHours = int("guideZoomHours"),
            idleSaverEnabled = bool("idleSaverEnabled"),
            surfIntervalSeconds = int("surfIntervalSeconds"),
            guideOpensOnCurrentChannel = bool("guideOpensOnCurrentChannel"),
            tvInfoFade = bool("tvInfoFade"),
            guideShowsPast = bool("guideShowsPast"),
            recordingAutoDeleteCapBytes =
                (m["recordingAutoDeleteCapBytes"] as? Number)?.toLong()
        )
    }

    // ---- Collect & apply (Android side) --------------------------------

    /** The backup file: a fixed name in the app's external files
     * directory (the recordings' parent), so export and import
     * always meet at the same path. Falls back to internal
     * storage on a device with no external files dir. */
    fun backupFile(context: Context): File {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        return File(dir, FILE_NAME)
    }

    /** Snapshot every covered store into a [BackupData]. Reading
     * reminders / scheduled recordings prunes past ones first
     * (their getters do), so a backup never carries dead entries. */
    fun collect(context: Context): BackupData = BackupData(
        playlists = PlaylistRepository.playlists(context).map {
            PlaylistEntry(it.name, it.url, it.enabled)
        },
        activePlaylistUrl = PlaylistRepository.activePlaylist(context).url,
        epgUrl = PlaylistRepository.epgUrl(context),
        uploadUrl = PlaylistRepository.uploadUrl(context),
        favorites = PlaylistRepository.favorites(context).sorted(),
        favoriteOrder = PlaylistRepository.favoriteOrder(context),
        hiddenChannels = PlaylistRepository.hiddenChannels(context).sorted(),
        reminders = PlaylistRepository.reminders(context).map {
            ReminderEntry(it.channelId, it.channelName, it.startMillis, it.stopMillis, it.title)
        },
        scheduledRecordings = PlaylistRepository.scheduledRecordings(context).map {
            ScheduledEntry(
                it.channelId, it.channelName, it.channelUrl,
                it.startMillis, it.stopMillis, it.title
            )
        },
        epgOverrides = PlaylistRepository.epgOverrides(context),
        keywords = AppSettings.guideKeywords(context),
        settings = SettingsData(
            accentChoice = AppSettings.accentChoice(context),
            guideTextSize = AppSettings.guideTextSize(context),
            channelListSize = AppSettings.channelListSize(context).name.lowercase(),
            favoritesZap = AppSettings.favoritesZap(context),
            nightDimPercent = AppSettings.nightDimPercent(context),
            qualityMode = AppSettings.qualityMode(context),
            guideZoomHours = AppSettings.guideZoomHours(context),
            idleSaverEnabled = AppSettings.idleSaverEnabled(context),
            surfIntervalSeconds = AppSettings.surfIntervalSeconds(context),
            guideOpensOnCurrentChannel = AppSettings.guideOpensOnCurrentChannel(context),
            tvInfoFade = AppSettings.tvInfoFade(context),
            guideShowsPast = AppSettings.guideShowsPast(context),
            recordingAutoDeleteCapBytes = AppSettings.recordingAutoDeleteCapBytes(context)
        )
    )

    /**
     * Write a validated [BackupData] back into the stores. Only
     * call this with the result of [parseBackup] — the data is
     * already structurally sound, and the stores' own setters
     * range-check the preference values as usual. Scheduled
     * recordings get their start alarms re-armed (the alarms of
     * the recordings they replace are cancelled first), so an
     * imported schedule actually fires.
     */
    fun applyBackup(context: Context, data: BackupData) {
        data.playlists?.let { entries ->
            PlaylistRepository.replacePlaylists(
                context,
                entries.map {
                    PlaylistRepository.Playlist(it.name, it.url, it.enabled)
                },
                data.activePlaylistUrl
            )
        }
        data.epgUrl?.let { PlaylistRepository.setEpgUrl(context, it) }
        data.uploadUrl?.let { PlaylistRepository.setUploadUrl(context, it) }
        data.favorites?.let { PlaylistRepository.setFavorites(context, it.toSet()) }
        data.favoriteOrder?.let { PlaylistRepository.setFavoriteOrder(context, it) }
        data.hiddenChannels?.let {
            PlaylistRepository.setHiddenChannels(context, it.toSet())
        }
        data.reminders?.let { list ->
            PlaylistRepository.replaceReminders(
                context,
                list.map {
                    PlaylistRepository.Reminder(
                        it.channelId, it.channelName, it.startMillis, it.stopMillis, it.title
                    )
                }
            )
        }
        data.scheduledRecordings?.let { list ->
            for (old in PlaylistRepository.scheduledRecordings(context)) {
                RecordingManager.cancelRecordingStart(context, old)
            }
            val mapped = list.map {
                PlaylistRepository.ScheduledRecording(
                    it.channelId, it.channelName, it.channelUrl,
                    it.startMillis, it.stopMillis, it.title
                )
            }
            PlaylistRepository.replaceScheduledRecordings(context, mapped)
            val now = System.currentTimeMillis()
            for (entry in mapped) {
                if (entry.startMillis > now) {
                    RecordingManager.scheduleRecordingStart(context, entry)
                }
            }
        }
        data.epgOverrides?.let { PlaylistRepository.replaceEpgOverrides(context, it) }
        data.keywords?.let { AppSettings.setGuideKeywords(context, it) }
        data.settings?.let { s ->
            s.accentChoice?.let { AppSettings.setAccentChoice(context, it) }
            s.guideTextSize?.let { AppSettings.setGuideTextSize(context, it) }
            s.channelListSize?.let { name ->
                val size = AppSettings.ChannelListSize.values()
                    .firstOrNull { it.name.equals(name, ignoreCase = true) }
                if (size != null) AppSettings.setChannelListSize(context, size)
            }
            s.favoritesZap?.let { AppSettings.setFavoritesZap(context, it) }
            s.nightDimPercent?.let { AppSettings.setNightDimPercent(context, it) }
            s.qualityMode?.let { AppSettings.setQualityMode(context, it) }
            s.guideZoomHours?.let { AppSettings.setGuideZoomHours(context, it) }
            s.idleSaverEnabled?.let { AppSettings.setIdleSaverEnabled(context, it) }
            s.surfIntervalSeconds?.let { AppSettings.setSurfIntervalSeconds(context, it) }
            s.guideOpensOnCurrentChannel?.let {
                AppSettings.setGuideOpensOnCurrentChannel(context, it)
            }
            s.tvInfoFade?.let { AppSettings.setTvInfoFade(context, it) }
            s.guideShowsPast?.let { AppSettings.setGuideShowsPast(context, it) }
            s.recordingAutoDeleteCapBytes?.let {
                AppSettings.setRecordingAutoDeleteCapBytes(context, it)
            }
        }
    }

    // ---- Minimal JSON codec (pure JVM) ---------------------------------
    //
    // Just enough JSON for this file format: objects, arrays,
    // strings (full escape set), numbers (Long, or Double when
    // the literal is fractional), booleans and null. The writer
    // pretty-prints — a backup a person can open and read.

    private fun StringBuilder.appendJsonString(value: String) {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                else -> if (c < ' ') {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else {
                    append(c)
                }
            }
        }
        append('"')
    }

    private fun writeValue(sb: StringBuilder, value: Any?, indent: Int) {
        when (value) {
            null -> sb.append("null")
            is String -> sb.appendJsonString(value)
            is Boolean -> sb.append(value.toString())
            is Number -> sb.append(value.toString())
            is Map<*, *> -> {
                if (value.isEmpty()) {
                    sb.append("{}")
                    return
                }
                sb.append("{\n")
                val pad = "  ".repeat(indent + 1)
                val entries = value.entries.toList()
                entries.forEachIndexed { i, e ->
                    sb.append(pad)
                    sb.appendJsonString(e.key as String)
                    sb.append(": ")
                    writeValue(sb, e.value, indent + 1)
                    if (i < entries.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append("  ".repeat(indent)).append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    sb.append("[]")
                    return
                }
                // Scalar lists stay on one line; object lists
                // break one entry per line.
                if (value.all { it is String || it is Number || it is Boolean }) {
                    sb.append('[')
                    value.forEachIndexed { i, item ->
                        if (i > 0) sb.append(", ")
                        writeValue(sb, item, indent)
                    }
                    sb.append(']')
                    return
                }
                sb.append("[\n")
                val pad = "  ".repeat(indent + 1)
                value.forEachIndexed { i, item ->
                    sb.append(pad)
                    writeValue(sb, item, indent + 1)
                    if (i < value.size - 1) sb.append(',')
                    sb.append('\n')
                }
                sb.append("  ".repeat(indent)).append(']')
            }
            else -> throw IllegalArgumentException("not JSON-encodable: $value")
        }
    }

    /** Recursive-descent parser producing Map / List / String /
     * Long / Double / Boolean / null. Throws on anything
     * malformed; [parseBackup] turns that into a null. */
    private class JsonParser(private val text: String) {
        private var pos = 0

        fun parseDocument(): Any? {
            val value = readValue(0)
            skipWhitespace()
            if (pos != text.length) {
                throw IllegalArgumentException("trailing content at $pos")
            }
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length) {
                when (text[pos]) {
                    ' ', '\t', '\n', '\r' -> pos++
                    else -> return
                }
            }
        }

        private fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw IllegalArgumentException("nesting too deep")
            skipWhitespace()
            if (pos >= text.length) throw IllegalArgumentException("unexpected end")
            return when (text[pos]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> {
                    readLiteral("true")
                    true
                }
                'f' -> {
                    readLiteral("false")
                    false
                }
                'n' -> {
                    readLiteral("null")
                    null
                }
                else -> readNumber()
            }
        }

        private fun readLiteral(literal: String) {
            if (!text.startsWith(literal, pos)) {
                throw IllegalArgumentException("bad literal at $pos")
            }
            pos += literal.length
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            pos++ // '{'
            val out = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == '}') {
                pos++
                return out
            }
            while (true) {
                skipWhitespace()
                if (pos >= text.length || text[pos] != '"') {
                    throw IllegalArgumentException("expected object key at $pos")
                }
                val key = readString()
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') {
                    throw IllegalArgumentException("expected ':' at $pos")
                }
                pos++
                out[key] = readValue(depth + 1)
                skipWhitespace()
                if (pos >= text.length) {
                    throw IllegalArgumentException("unterminated object")
                }
                when (text[pos]) {
                    ',' -> pos++
                    '}' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or '}' at $pos")
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            pos++ // '['
            val out = ArrayList<Any?>()
            skipWhitespace()
            if (pos < text.length && text[pos] == ']') {
                pos++
                return out
            }
            while (true) {
                out.add(readValue(depth + 1))
                skipWhitespace()
                if (pos >= text.length) {
                    throw IllegalArgumentException("unterminated array")
                }
                when (text[pos]) {
                    ',' -> pos++
                    ']' -> {
                        pos++
                        return out
                    }
                    else -> throw IllegalArgumentException("expected ',' or ']' at $pos")
                }
            }
        }

        private fun readString(): String {
            pos++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (pos >= text.length) {
                    throw IllegalArgumentException("unterminated string")
                }
                val c = text[pos]
                when {
                    c == '"' -> {
                        pos++
                        return sb.toString()
                    }
                    c == '\\' -> {
                        pos++
                        if (pos >= text.length) {
                            throw IllegalArgumentException("unterminated escape")
                        }
                        when (text[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 5 > text.length) {
                                    throw IllegalArgumentException("bad unicode escape")
                                }
                                val hex = text.substring(pos + 1, pos + 5)
                                val code = hex.toIntOrNull(16)
                                    ?: throw IllegalArgumentException("bad unicode escape")
                                sb.append(code.toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape at $pos")
                        }
                        pos++
                    }
                    else -> {
                        sb.append(c)
                        pos++
                    }
                }
            }
        }

        private fun readNumber(): Any {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            var digits = 0
            while (pos < text.length && text[pos].isDigit()) {
                pos++
                digits++
            }
            var floating = false
            if (pos < text.length && text[pos] == '.') {
                floating = true
                pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                floating = true
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                while (pos < text.length && text[pos].isDigit()) pos++
            }
            if (digits == 0) throw IllegalArgumentException("bad number at $start")
            val raw = text.substring(start, pos)
            return if (floating) {
                raw.toDoubleOrNull()
                    ?: throw IllegalArgumentException("bad number: $raw")
            } else {
                raw.toLongOrNull()
                    ?: throw IllegalArgumentException("bad number: $raw")
            }
        }

        private companion object {
            const val MAX_DEPTH = 32
        }
    }
}
