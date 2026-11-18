package com.opentv.player

import android.content.Context
import android.content.SharedPreferences
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * User preferences for the 6.1.2 guide / playback behaviours, all
 * defaulted to what the user asked for — turning one off restores
 * the old behaviour. Stored in the same "opentv" SharedPreferences
 * file as the playlist / favorites data, so no new storage is
 * introduced. Read at runtime (no restart needed for the guide and
 * info-fade toggles; the channel-list size applies the next time
 * playback or the guide opens a fresh sidebar).
 */
object AppSettings {

    private const val PREFS = "opentv"
    private const val KEY_GUIDE_OPEN_CURRENT = "guide_open_current_channel"
    private const val KEY_TV_INFO_FADE = "tv_info_fade"
    private const val KEY_GUIDE_SHOW_PAST = "guide_show_past"
    private const val KEY_CHANNEL_LIST_SIZE = "channel_list_size"
    private const val KEY_FAVORITES_ZAP = "favorites_zap"
    private const val KEY_SPEAK_CHANNEL_NAMES = "speak_channel_names"
    private const val KEY_LONG_PRESS_OK_ACTION = "long_press_ok_action"
    private const val KEY_NIGHT_DIM = "night_dim_percent"
    private const val KEY_QUALITY_MODE = "quality_mode"
    private const val KEY_GUIDE_GROUP_FILTER = "guide_group_filter"
    private const val KEY_GUIDE_ZOOM_HOURS = "guide_zoom_hours"
    private const val KEY_IDLE_SAVER = "idle_saver"
    private const val KEY_SURF_INTERVAL = "surf_interval_seconds"
    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_GUIDE_TEXT_SIZE = "guide_text_size"
    private const val KEY_REC_AUTO_DELETE_CAP = "rec_auto_delete_cap_bytes"
    private const val KEY_REC_LAST_AUTO_FREED = "rec_last_auto_freed_bytes"
    private const val KEY_REC_AUTO_DELETE_BLOCKED = "rec_auto_delete_blocked"
    private const val KEY_GUIDE_KEYWORDS = "guide_keywords"

    /** 7.3.0 (Nova): stream quality modes for the playback
     * track selector. AUTO is the player's default adaptive
     * behaviour, HIGH prefers the highest variant, LOW caps the
     * video bitrate so adaptive streams settle on a low variant. */
    const val QUALITY_AUTO = 0
    const val QUALITY_HIGH = 1
    const val QUALITY_LOW = 2

    /** Channel list (sidebar) size. Small is the compact 6.1.2
     * default, Medium is the old 260dp / 56dp layout, Large is
     * roomier still. 6.1.4: the TV guide rows follow the same size —
     * Small gives thin 40dp guide rows so more channels fit on
     * screen, Large restores the old 56dp rows. */
    enum class ChannelListSize { SMALL, MEDIUM, LARGE }

    /** Sidebar dimensions for one size: panel width + row geometry.
     * 6.1.4: guideRowHeightDp is the TV guide row height for the
     * same size — the guide and the sidebar share one size control,
     * so there is no second setting to keep in sync. */
    data class SidebarSizing(
        val panelDp: Int,
        val rowMinHeightDp: Int,
        val rowPaddingDp: Int,
        val groupPaddingDp: Int,
        val numberWidthDp: Int,
        val logoDp: Int,
        val nameSp: Float,
        val programmeSp: Float,
        val guideRowHeightDp: Int
    )

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Guide opens on the channel playing underneath (default ON). */
    fun guideOpensOnCurrentChannel(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUIDE_OPEN_CURRENT, true)

    fun setGuideOpensOnCurrentChannel(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUIDE_OPEN_CURRENT, value).apply()
    }

    /** TV channel info overlay fades out instead of popping off
     * (default ON). */
    fun tvInfoFade(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TV_INFO_FADE, true)

    fun setTvInfoFade(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TV_INFO_FADE, value).apply()
    }

    /** Past programmes show in the guide (default ON). */
    fun guideShowsPast(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUIDE_SHOW_PAST, true)

    fun setGuideShowsPast(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUIDE_SHOW_PAST, value).apply()
    }

    /** 7.2.0 (Nova): favorites zapping — channel up/down cycle
     * through favorite channels only (default OFF). A playback
     * preference, so it lives here with the other playback
     * behaviours and is read when playback starts. */
    fun favoritesZap(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FAVORITES_ZAP, false)

    fun setFavoritesZap(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_FAVORITES_ZAP, value).apply()
    }

    /** Combined port (juniper-v10 9.3.0): spoken channel names —
     * the channel name is announced (TTS) whenever playback
     * switches to a channel. Default OFF — a TV that talks
     * uninvited is a surprise nobody asked for. Same pref key as
     * Juniper's line, so her settings screen drives it unchanged. */
    fun speakChannelNames(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEAK_CHANNEL_NAMES, false)

    fun setSpeakChannelNames(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK_CHANNEL_NAMES, value).apply()
    }

    /** Combined port (juniper-v10 9.3.0): what a long-press of OK
     * (with nothing on screen) does. MENU is the behaviour Nova's
     * line always had — the choice only adds the alternatives. */
    enum class LongPressOkAction { MENU, GUIDE, NOTHING }

    fun longPressOkAction(context: Context): LongPressOkAction =
        parseLongPressOkAction(prefs(context).getString(KEY_LONG_PRESS_OK_ACTION, "menu"))

    fun setLongPressOkAction(context: Context, action: LongPressOkAction) {
        prefs(context).edit().putString(KEY_LONG_PRESS_OK_ACTION, longPressOkActionKey(action)).apply()
    }

    /** Parse a stored long-press-OK key; anything unknown (or null,
     * from an older install) is MENU — today's behaviour. Pure, so
     * it is unit-testable. */
    fun parseLongPressOkAction(raw: String?): LongPressOkAction = when (raw) {
        "guide" -> LongPressOkAction.GUIDE
        "nothing" -> LongPressOkAction.NOTHING
        else -> LongPressOkAction.MENU
    }

    /** The stored key for a long-press-OK action (round-trips
     * through [parseLongPressOkAction]). */
    fun longPressOkActionKey(action: LongPressOkAction): String = when (action) {
        LongPressOkAction.MENU -> "menu"
        LongPressOkAction.GUIDE -> "guide"
        LongPressOkAction.NOTHING -> "nothing"
    }

    /** 7.2.1 (Nova): night dim level in percent (0 = off, else
     * 25 or 50), persisted so a level chosen in one session is
     * still dimming the next — 7.2.0 reset it every session.
     * Anything stored outside the offered levels reads as off. */
    fun nightDimPercent(context: Context): Int {
        val stored = prefs(context).getInt(KEY_NIGHT_DIM, 0)
        return if (stored == 25 || stored == 50) stored else 0
    }

    fun setNightDimPercent(context: Context, percent: Int) {
        prefs(context).edit().putInt(KEY_NIGHT_DIM, percent).apply()
    }

    /** 7.3.0 (Nova): stream quality mode (see the QUALITY_*
     * constants), persisted so the choice holds across sessions
     * and player rebuilds. Anything unrecognised reads as AUTO. */
    fun qualityMode(context: Context): Int {
        val stored = prefs(context).getInt(KEY_QUALITY_MODE, QUALITY_AUTO)
        return if (stored == QUALITY_HIGH || stored == QUALITY_LOW) stored else QUALITY_AUTO
    }

    fun setQualityMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_QUALITY_MODE, mode).apply()
    }

    /** 8.1.0 (Nova): the guide group filter — the M3U group-title
     * the guide grid is restricted to, or null for all groups.
     * Stored raw; GuideData.effectiveGroupFilter validates it
     * against the loaded playlist on every guide build, so a
     * group that disappears from the playlist simply stops
     * filtering instead of emptying the guide.
     *
     * 8.1.1 (Nova): the value is scoped to the active playlist
     * (keyed by its URL, the playlist identity every other
     * playlist operation uses), so switching playlists no longer
     * carries one playlist's group into another. A value stored
     * under the old global key is adopted by the first playlist
     * that reads it, then removed. */
    private fun groupFilterKey(context: Context): String =
        KEY_GUIDE_GROUP_FILTER + "|" + PlaylistRepository.activePlaylist(context).url

    fun guideGroupFilter(context: Context): String? {
        val scoped = prefs(context).getString(groupFilterKey(context), null)
            ?.takeIf { it.isNotEmpty() }
        if (scoped != null) return scoped
        val legacy = prefs(context).getString(KEY_GUIDE_GROUP_FILTER, null)
            ?.takeIf { it.isNotEmpty() } ?: return null
        // Adopt the pre-8.1.1 global value for this playlist.
        prefs(context).edit()
            .putString(groupFilterKey(context), legacy)
            .remove(KEY_GUIDE_GROUP_FILTER)
            .apply()
        return legacy
    }

    fun setGuideGroupFilter(context: Context, group: String?) {
        val edit = prefs(context).edit()
        if (group == null) edit.remove(groupFilterKey(context))
        else edit.putString(groupFilterKey(context), group)
        edit.remove(KEY_GUIDE_GROUP_FILTER)
        edit.apply()
    }

    /** 8.1.1 (Nova): how many hours of the guide grid are visible
     * at once (2, 3 or 4). GuideActivity turns this into a
     * pixels-per-minute scale from the actual grid width, so the
     * same choice means the same span on TV and phone. Default 3 —
     * on a TV that lands within a whisker of the old fixed
     * 4dp/minute scale. */
    fun guideZoomHours(context: Context): Int {
        val stored = prefs(context).getInt(KEY_GUIDE_ZOOM_HOURS, 3)
        return if (stored in 2..4) stored else 3
    }

    fun setGuideZoomHours(context: Context, hours: Int) {
        prefs(context).edit()
            .putInt(KEY_GUIDE_ZOOM_HOURS, hours.coerceIn(2, 4))
            .apply()
    }

    /** 8.3.0 (Nova): the idle-pause screensaver — after five
     * minutes paused on a live channel, playback covers the
     * frozen frame with a calm clock screen (see PlaybackActivity).
     * Default ON; turning it off leaves a paused frame up
     * indefinitely, exactly as before. Read when a pause begins
     * and again when the saver would show, so a change made in
     * Settings applies to the very next pause. */
    fun idleSaverEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_IDLE_SAVER, true)

    fun setIdleSaverEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_IDLE_SAVER, value).apply()
    }

    /** 8.3.1 (Nova): the surf-mode hop interval in seconds —
     * how long each channel stays up while surfing (see
     * PlaybackActivity). One of SURF_INTERVAL_OPTIONS; anything
     * else stored reads as the default. Read by the surf timer
     * on every hop, so a change applies to the very next hop. */
    const val SURF_INTERVAL_DEFAULT = 12
    val SURF_INTERVAL_OPTIONS = intArrayOf(8, 12, 20, 30)

    fun surfIntervalSeconds(context: Context): Int {
        val stored = prefs(context).getInt(KEY_SURF_INTERVAL, SURF_INTERVAL_DEFAULT)
        return if (stored in SURF_INTERVAL_OPTIONS) stored else SURF_INTERVAL_DEFAULT
    }

    fun setSurfIntervalSeconds(context: Context, seconds: Int) {
        prefs(context).edit()
            .putInt(
                KEY_SURF_INTERVAL,
                if (seconds in SURF_INTERVAL_OPTIONS) seconds else SURF_INTERVAL_DEFAULT
            )
            .apply()
    }

    /** 9.0.0 (Nova): the accent colour choice. The app's accent
     * used to be a single hard-coded green (#86C739) repeated
     * across layouts, drawables and code; it now lives here, in
     * one place, and every accent-driven surface reads it through
     * [accentColor] / [accentColorDark]. GREEN is that original
     * colour and stays the default, so an untouched install looks
     * exactly as it always did; AMBER matches the Nova banner's
     * star, VIOLET and TEAL are the alternates. (The banner and
     * app icon are artwork, not accent surfaces — they never
     * change.) */
    const val ACCENT_GREEN = 0
    const val ACCENT_AMBER = 1
    const val ACCENT_VIOLET = 2
    const val ACCENT_TEAL = 3

    fun accentChoice(context: Context): Int {
        val stored = prefs(context).getInt(KEY_ACCENT_COLOR, ACCENT_GREEN)
        return if (stored in ACCENT_GREEN..ACCENT_TEAL) stored else ACCENT_GREEN
    }

    fun setAccentChoice(context: Context, choice: Int) {
        prefs(context).edit()
            .putInt(
                KEY_ACCENT_COLOR,
                if (choice in ACCENT_GREEN..ACCENT_TEAL) choice else ACCENT_GREEN
            )
            .apply()
    }

    /** The accent colour itself (ARGB), for text, fills and
     * progress bars. Read at view-build time: a new choice
     * applies the next time a screen is built (the guide and
     * playback rebuild on open; Settings repaints its own accent
     * label immediately). */
    fun accentColor(context: Context): Int = when (accentChoice(context)) {
        ACCENT_AMBER -> 0xFFFFB020.toInt()
        ACCENT_VIOLET -> 0xFF9B6BFF.toInt()
        ACCENT_TEAL -> 0xFF2EC4B6.toInt()
        else -> 0xFF86C739.toInt()
    }

    /** The accent's darker pressed-state shade (the guide's
     * "now" cell uses it for touch feedback). */
    fun accentColorDark(context: Context): Int = when (accentChoice(context)) {
        ACCENT_AMBER -> 0xFFE09A10.toInt()
        ACCENT_VIOLET -> 0xFF8457E8.toInt()
        ACCENT_TEAL -> 0xFF25A99B.toInt()
        else -> 0xFF79B636.toInt()
    }

    /** 9.0.0 (Nova): guide text size — Standard (the sizes the
     * guide has always used) or Large, which scales the guide's
     * programme text, details header and channel column up by
     * 20% for couch readability. Independent of the guide zoom
     * (8.1.1), which scales the grid's time axis, not its text.
     * GuideActivity reads the scale once when it opens, so the
     * choice applies the next time the guide opens. */
    const val GUIDE_TEXT_STANDARD = 0
    const val GUIDE_TEXT_LARGE = 1

    fun guideTextSize(context: Context): Int {
        val stored = prefs(context).getInt(KEY_GUIDE_TEXT_SIZE, GUIDE_TEXT_STANDARD)
        return if (stored == GUIDE_TEXT_LARGE) GUIDE_TEXT_LARGE else GUIDE_TEXT_STANDARD
    }

    fun setGuideTextSize(context: Context, size: Int) {
        prefs(context).edit()
            .putInt(
                KEY_GUIDE_TEXT_SIZE,
                if (size == GUIDE_TEXT_LARGE) GUIDE_TEXT_LARGE else GUIDE_TEXT_STANDARD
            )
            .apply()
    }

    /** Multiplier for guide text sizes (1.0 Standard, 1.2 Large). */
    fun guideTextScale(context: Context): Float =
        if (guideTextSize(context) == GUIDE_TEXT_LARGE) 1.2f else 1.0f

    /** Channel list size (default Small — the compact 6.1.2 layout). */
    fun channelListSize(context: Context): ChannelListSize {
        return when (prefs(context).getString(KEY_CHANNEL_LIST_SIZE, "small")) {
            "medium" -> ChannelListSize.MEDIUM
            "large" -> ChannelListSize.LARGE
            else -> ChannelListSize.SMALL
        }
    }

    fun setChannelListSize(context: Context, size: ChannelListSize) {
        prefs(context).edit().putString(KEY_CHANNEL_LIST_SIZE, size.name.lowercase()).apply()
    }

    /** Dimensions for the current channel-list size. */
    fun sidebarSizing(context: Context): SidebarSizing = when (channelListSize(context)) {
        ChannelListSize.SMALL -> SidebarSizing(
            panelDp = 220, rowMinHeightDp = 48, rowPaddingDp = 8, groupPaddingDp = 10,
            numberWidthDp = 28, logoDp = 28, nameSp = 13f, programmeSp = 11f,
            guideRowHeightDp = 40
        )
        ChannelListSize.MEDIUM -> SidebarSizing(
            panelDp = 260, rowMinHeightDp = 56, rowPaddingDp = 10, groupPaddingDp = 12,
            numberWidthDp = 32, logoDp = 32, nameSp = 14f, programmeSp = 12f,
            guideRowHeightDp = 48
        )
        ChannelListSize.LARGE -> SidebarSizing(
            panelDp = 300, rowMinHeightDp = 64, rowPaddingDp = 12, groupPaddingDp = 14,
            numberWidthDp = 36, logoDp = 36, nameSp = 15f, programmeSp = 13f,
            guideRowHeightDp = 56
        )
    }

    /** 9.2.0 (Nova): the recordings storage guard's cap, in
     * bytes — 0 (the default) is off; the other offered values
     * are 2 / 5 / 10 GB. When recordings on disk exceed the cap,
     * RecordingManager deletes the oldest completed ones (never
     * an in-progress recording, never the newest one) at app
     * start and as recordings finish. Anything stored outside
     * the offered values reads as off. */
    val REC_CAP_OPTIONS_BYTES = longArrayOf(
        0L, 2L * 1024 * 1024 * 1024, 5L * 1024 * 1024 * 1024, 10L * 1024 * 1024 * 1024
    )

    fun recordingAutoDeleteCapBytes(context: Context): Long {
        val stored = prefs(context).getLong(KEY_REC_AUTO_DELETE_CAP, 0L)
        return if (stored in REC_CAP_OPTIONS_BYTES) stored else 0L
    }

    fun setRecordingAutoDeleteCapBytes(context: Context, bytes: Long) {
        prefs(context).edit()
            .putLong(
                KEY_REC_AUTO_DELETE_CAP,
                if (bytes in REC_CAP_OPTIONS_BYTES) bytes else 0L
            )
            .apply()
    }

    /** 9.2.0 (Nova): bytes freed by the most recent storage-guard
     * run that actually deleted something, waiting to be shown
     * once on the Recordings screen ("Freed X by auto-delete").
     * The recordings screen reads and clears it; 0 = nothing to
     * report. */
    fun lastAutoFreedBytes(context: Context): Long =
        prefs(context).getLong(KEY_REC_LAST_AUTO_FREED, 0L)

    fun setLastAutoFreedBytes(context: Context, bytes: Long) {
        prefs(context).edit().putLong(KEY_REC_LAST_AUTO_FREED, bytes).apply()
    }

    /** 9.2.1 (Nova): the most recent storage-guard pass ended
     * still over the cap because every remaining candidate was
     * protected (a favorite channel's recording). Written by
     * every guard pass — set when blocked, cleared when a pass
     * gets under the cap or the cap is off — and shown once on
     * the Recordings screen, which clears it after reading. */
    fun lastAutoDeleteBlocked(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REC_AUTO_DELETE_BLOCKED, false)

    fun setLastAutoDeleteBlocked(context: Context, blocked: Boolean) {
        prefs(context).edit().putBoolean(KEY_REC_AUTO_DELETE_BLOCKED, blocked).apply()
    }

    /** 9.2.0 (Nova): the guide keyword markers — user-chosen
     * words, one per line in prefs (the reminders' storage
     * pattern). A programme whose title contains any keyword
     * (case-insensitive) gets a ★ marker in the guide. Stored
     * as entered (trimmed, blanks and case-insensitive duplicates
     * dropped); matching lowercases both sides. */
    fun guideKeywords(context: Context): List<String> {
        val raw = prefs(context).getString(KEY_GUIDE_KEYWORDS, "") ?: ""
        if (raw.isBlank()) return emptyList()
        val seen = HashSet<String>()
        return raw.split("\n").mapNotNull { line ->
            val word = line.trim()
            if (word.isEmpty() || !seen.add(word.lowercase())) null else word
        }
    }

    fun setGuideKeywords(context: Context, keywords: List<String>) {
        val seen = HashSet<String>()
        val clean = keywords.mapNotNull { word ->
            val trimmed = word.trim()
            if (trimmed.isEmpty() || !seen.add(trimmed.lowercase())) null else trimmed
        }
        prefs(context).edit()
            .putString(KEY_GUIDE_KEYWORDS, clean.joinToString("\n"))
            .apply()
    }

    /** The keywords as a lowercase set, for guide matching —
     * GuideActivity computes this once per guide build so the
     * per-cell check is a set scan + contains, nothing more. */
    fun guideKeywordSetLower(context: Context): Set<String> =
        guideKeywords(context).map { it.lowercase() }.toSet()

    // ---- 9.3.0 (Nova): Settings PIN --------------------------------
    //
    // An optional 4-digit PIN that gates the Settings screen only
    // (SettingsActivity asks for it before building its UI). The
    // PIN itself is never stored: the record is "saltHex:hashHex",
    // a per-PIN random salt + a PBKDF2 derivation (deliberately
    // expensive — a 4-digit PIN has only 10,000 combinations, so
    // a fast hash would let anyone who extracted the record try
    // them all instantly; the iterations make each guess cost
    // real time). There is no recovery path by design — a
    // forgotten PIN means clearing the app's data, which the
    // Settings caption says out loud.

    private const val KEY_SETTINGS_PIN = "settings_pin"

    /** PBKDF2 iteration count for the Settings PIN — high enough
     * to make offline guessing of a 4-digit PIN slow, low enough
     * that unlocking Settings on a TV's modest CPU still feels
     * instant (one derivation, ~a tenth of a second class). */
    private const val PIN_HASH_ITERATIONS = 100_000

    /** True when a Settings PIN is set. Default: none set. */
    fun settingsPinSet(context: Context): Boolean =
        prefs(context).getString(KEY_SETTINGS_PIN, null) != null

    fun setSettingsPin(context: Context, pin: String) {
        prefs(context).edit()
            .putString(KEY_SETTINGS_PIN, settingsPinRecord(pin))
            .apply()
    }

    fun clearSettingsPin(context: Context) {
        prefs(context).edit().remove(KEY_SETTINGS_PIN).apply()
    }

    /** True when [pin] matches the stored record. A missing or
     * malformed record never matches. */
    fun verifySettingsPin(context: Context, pin: String): Boolean {
        val stored = prefs(context).getString(KEY_SETTINGS_PIN, null) ?: return false
        return settingsPinMatches(stored, pin)
    }

    /** Build a stored record for [pin]: a fresh random salt and
     * the PBKDF2 hash, hex-encoded as "salt:hash". Pure JVM (no
     * Context), so the PIN maths is unit-testable. */
    internal fun settingsPinRecord(pin: String): String {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        val saltHex = salt.toHex()
        return saltHex + ":" + settingsPinHash(saltHex, pin)
    }

    /** PBKDF2-HmacSHA256 of [pin] under the record's salt
     * ([PIN_HASH_ITERATIONS] rounds, 256-bit output), hex-encoded. */
    internal fun settingsPinHash(saltHex: String, pin: String): String {
        val spec = PBEKeySpec(
            pin.toCharArray(),
            saltHex.hexToBytes(),
            PIN_HASH_ITERATIONS,
            256
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec).encoded.toHex()
    }

    /** True when [pin] reproduces the hash in a stored record.
     * MessageDigest.isEqual compares without short-circuiting on
     * the first differing byte. */
    internal fun settingsPinMatches(stored: String, pin: String): Boolean {
        val salt = stored.substringBefore(':', "")
        val hash = stored.substringAfter(':', "")
        if (salt.isEmpty() || hash.isEmpty()) return false
        return MessageDigest.isEqual(
            settingsPinHash(salt, pin).toByteArray(Charsets.UTF_8),
            hash.toByteArray(Charsets.UTF_8)
        )
    }

    private fun ByteArray.toHex(): String {
        val hex = "0123456789abcdef"
        val sb = StringBuilder(size * 2)
        for (b in this) {
            val v = b.toInt() and 0xFF
            sb.append(hex[v shr 4])
            sb.append(hex[v and 0x0F])
        }
        return sb.toString()
    }

    /** Inverse of [toHex] for the stored salt. A malformed salt
     * (odd length, non-hex digits) decodes to garbage bytes
     * rather than throwing — the derived hash then simply never
     * matches, which is the right answer for a corrupt record. */
    private fun String.hexToBytes(): ByteArray {
        val out = ByteArray(length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(this[2 * i], 16) shl 4) +
                Character.digit(this[2 * i + 1], 16)).toByte()
        }
        return out
    }
}
