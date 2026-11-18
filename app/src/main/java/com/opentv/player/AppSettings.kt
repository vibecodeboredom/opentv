package com.opentv.player

import android.content.Context
import android.content.SharedPreferences

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
    private const val KEY_NIGHT_DIM = "night_dim_percent"
    private const val KEY_QUALITY_MODE = "quality_mode"
    private const val KEY_GUIDE_GROUP_FILTER = "guide_group_filter"
    private const val KEY_GUIDE_ZOOM_HOURS = "guide_zoom_hours"
    private const val KEY_IDLE_SAVER = "idle_saver"
    private const val KEY_SURF_INTERVAL = "surf_interval_seconds"
    private const val KEY_ACCENT_COLOR = "accent_color"
    private const val KEY_GUIDE_TEXT_SIZE = "guide_text_size"

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
}
