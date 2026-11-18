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
    private const val KEY_GUIDE_FAVORITES_ONLY = "guide_favorites_only"
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
    private const val KEY_GUIDE_WINDOW_SIZE = "guide_window_size"
    /** 8.3.0: auto-delete old recordings — days to keep (0 = off). */
    private const val KEY_AUTO_DELETE_RECORDINGS_DAYS = "auto_delete_recordings_days"
    /** 8.3.0: recordings list sort + filter (persist across opens). */
    private const val KEY_RECORDINGS_SORT = "recordings_sort"
    private const val KEY_RECORDINGS_FILTER = "recordings_filter"
    /** 9.0.0: what the TV opens on — the home screen, or straight
     * into the last channel. Mobile ignores it (it always opens
     * the touch home screen). */
    private const val KEY_LAUNCH_BEHAVIOUR = "launch_behaviour"
    /** 9.1.0: appearance — guide row density, guide/sidebar font
     * size, high-contrast focus, reduced animations. */
    private const val KEY_GUIDE_DENSITY = "guide_density"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_HIGH_CONTRAST_FOCUS = "high_contrast_focus"
    private const val KEY_REDUCED_ANIMATIONS = "reduced_animations"
    /** 9.3.0: accessibility & remote — spoken channel names (TTS),
     * larger-text mode, and what a held OK does in playback. */
    private const val KEY_SPEAK_CHANNEL_NAMES = "speak_channel_names"
    private const val KEY_LARGER_TEXT = "larger_text"
    private const val KEY_LONG_PRESS_OK_ACTION = "long_press_ok_action"

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

    /** Guide shows favorites only (default OFF). Combined: ported
     * from Juniper 7.0.0 — toggled from the guide menu; the guide
     * also turns it on implicitly in the Tonight quick view. */
    fun guideFavoritesOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUIDE_FAVORITES_ONLY, false)

    fun setGuideFavoritesOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUIDE_FAVORITES_ONLY, value).apply()
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

    /** Guide shows favorites only (default OFF). 7.0.0: toggled from
     * the guide menu or Settings → Guide & playback. */
    fun guideFavoritesOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_GUIDE_FAVORITES_ONLY, false)

    fun setGuideFavoritesOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_GUIDE_FAVORITES_ONLY, value).apply()
    }

    /** Guide window size (7.2.0): how many hours forward the guide
     * shows from its anchor. 6 h is the old behaviour; the past
     * hours from the show-past setting are added on top. */
    enum class GuideWindowSize(val hours: Int) { SIX(6), TWELVE(12), TWENTY_FOUR(24) }

    fun guideWindowSize(context: Context): GuideWindowSize {
        return when (prefs(context).getString(KEY_GUIDE_WINDOW_SIZE, "6")) {
            "12" -> GuideWindowSize.TWELVE
            "24" -> GuideWindowSize.TWENTY_FOUR
            else -> GuideWindowSize.SIX
        }
    }

    /** Forward hours of the guide window (6/12/24). */
    fun guideWindowHours(context: Context): Int = guideWindowSize(context).hours

    fun setGuideWindowSize(context: Context, size: GuideWindowSize) {
        prefs(context).edit().putString(KEY_GUIDE_WINDOW_SIZE, size.hours.toString()).apply()
    }

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

    /** 8.3.0 — Auto-delete old recordings: how many days to keep
     * (0 = off, keep everything). Enforced when the recordings
     * screen opens and when a scheduled recording starts — the
     * check never builds the DownloadManager just to prune, so it
     * only runs where the recording infra is already awake. */
    fun autoDeleteRecordingsDays(context: Context): Int =
        prefs(context).getInt(KEY_AUTO_DELETE_RECORDINGS_DAYS, 0)

    fun setAutoDeleteRecordingsDays(context: Context, days: Int) {
        prefs(context).edit().putInt(KEY_AUTO_DELETE_RECORDINGS_DAYS, days.coerceAtLeast(0)).apply()
    }

    /** 8.3.0 — Recordings list sort (default Newest first). */
    fun recordingsSort(context: Context): RecordingLibrary.Sort {
        return runCatching {
            RecordingLibrary.Sort.valueOf(
                prefs(context).getString(KEY_RECORDINGS_SORT, "NEWEST") ?: "NEWEST"
            )
        }.getOrDefault(RecordingLibrary.Sort.NEWEST)
    }

    fun setRecordingsSort(context: Context, sort: RecordingLibrary.Sort) {
        prefs(context).edit().putString(KEY_RECORDINGS_SORT, sort.name).apply()
    }

    /** 8.3.0 — Recordings list filter (default All). */
    fun recordingsFilter(context: Context): RecordingLibrary.Filter {
        return runCatching {
            RecordingLibrary.Filter.valueOf(
                prefs(context).getString(KEY_RECORDINGS_FILTER, "ALL") ?: "ALL"
            )
        }.getOrDefault(RecordingLibrary.Filter.ALL)
    }

    fun setRecordingsFilter(context: Context, filter: RecordingLibrary.Filter) {
        prefs(context).edit().putString(KEY_RECORDINGS_FILTER, filter.name).apply()
    }

    /** 9.0.0 — What the TV opens on (default Home screen, the
     * browse-first launch). AUTOPLAY restores the old behaviour:
     * straight into the last channel that actually played. Takes
     * effect on the next launch. */
    enum class LaunchBehaviour { HOME, AUTOPLAY }

    fun launchBehaviour(context: Context): LaunchBehaviour {
        return when (prefs(context).getString(KEY_LAUNCH_BEHAVIOUR, "home")) {
            "autoplay" -> LaunchBehaviour.AUTOPLAY
            else -> LaunchBehaviour.HOME
        }
    }

    fun setLaunchBehaviour(context: Context, behaviour: LaunchBehaviour) {
        prefs(context).edit().putString(
            KEY_LAUNCH_BEHAVIOUR,
            when (behaviour) {
                LaunchBehaviour.AUTOPLAY -> "autoplay"
                LaunchBehaviour.HOME -> "home"
            }
        ).apply()
    }

    /** 9.1.0 — Guide row density (default Comfortable = today's row
     * heights; Compact thins them so more channels fit). Applies
     * the next time the guide opens, like the channel-list size. */
    fun guideDensity(context: Context): Appearance.GuideDensity =
        Appearance.parseGuideDensity(prefs(context).getString(KEY_GUIDE_DENSITY, "comfortable"))

    fun setGuideDensity(context: Context, density: Appearance.GuideDensity) {
        prefs(context).edit().putString(
            KEY_GUIDE_DENSITY, Appearance.guideDensityKey(density)
        ).apply()
    }

    /** 9.1.0 — Font size for the guide + sidebar text (default
     * Medium = today's size). Applies the next time the guide or a
     * fresh sidebar opens, like the channel-list size. */
    fun fontSize(context: Context): Appearance.FontSize =
        Appearance.parseFontSize(prefs(context).getString(KEY_FONT_SIZE, "medium"))

    fun setFontSize(context: Context, size: Appearance.FontSize) {
        prefs(context).edit().putString(KEY_FONT_SIZE, Appearance.fontSizeKey(size)).apply()
    }

    /** 9.1.0 — High-contrast focus: focused guide cells and sidebar
     * rows get a thick accent outline instead of the subtle one, so
     * focus is unmistakable at a glance. Applies the next time the
     * guide or a fresh sidebar opens. */
    fun highContrastFocus(context: Context): Boolean =
        prefs(context).getBoolean(KEY_HIGH_CONTRAST_FOCUS, false)

    fun setHighContrastFocus(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_HIGH_CONTRAST_FOCUS, value).apply()
    }

    /** 9.1.0 — Reduced animations: the guide's focus pop, the
     * sidebar slide, the playback overlay fades and the guide menu's
     * slide-in all jump to their final state instead of animating.
     * Read at animation time, so it takes effect immediately. */
    fun reducedAnimations(context: Context): Boolean =
        prefs(context).getBoolean(KEY_REDUCED_ANIMATIONS, false)

    fun setReducedAnimations(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_REDUCED_ANIMATIONS, value).apply()
    }

    /** 9.3.0 — Spoken channel names: the channel name is announced
     * (TTS) whenever playback switches to a channel. Default OFF —
     * a TV that talks uninvited is a surprise nobody asked for. */
    fun speakChannelNames(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SPEAK_CHANNEL_NAMES, false)

    fun setSpeakChannelNames(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SPEAK_CHANNEL_NAMES, value).apply()
    }

    /** 9.3.0 — Larger-text mode: guide + sidebar text get an extra
     * 1.25x on top of the font-size setting, for readability from
     * the couch. Default OFF. Applies the next time the guide or a
     * fresh sidebar opens, like the font size. */
    fun largerText(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LARGER_TEXT, false)

    fun setLargerText(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LARGER_TEXT, value).apply()
    }

    /** 9.3.0 — What a held OK does in playback (nothing up): MENU
     * opens the playback menu (today's behaviour, the default),
     * GUIDE opens the guide, NOTHING does nothing at all — the
     * hold is swallowed so the short-press action doesn't fire
     * on key-up either. */
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

    /** Dimensions for the current channel-list size. 9.1.0: the font
     * size setting scales the text sizes here, so the sidebar's
     * channel rows grow/shrink with it; the guide scales its own
     * text at the call site (same helper). 9.3.0: larger-text mode
     * folds in too, through the effective scale. */
    fun sidebarSizing(context: Context): SidebarSizing {
        val fontSize = fontSize(context)
        val larger = largerText(context)
        val fontScale = Appearance.effectiveFontScale(fontSize, larger)
        return when (channelListSize(context)) {
            ChannelListSize.SMALL -> SidebarSizing(
                panelDp = 220, rowMinHeightDp = 48, rowPaddingDp = 8, groupPaddingDp = 10,
                numberWidthDp = Appearance.numberColumnWidthDp(28, fontSize, larger),
                logoDp = 28,
                nameSp = 13f * fontScale, programmeSp = 11f * fontScale,
                guideRowHeightDp = 40
            )
            ChannelListSize.MEDIUM -> SidebarSizing(
                panelDp = 260, rowMinHeightDp = 56, rowPaddingDp = 10, groupPaddingDp = 12,
                numberWidthDp = Appearance.numberColumnWidthDp(32, fontSize, larger),
                logoDp = 32,
                nameSp = 14f * fontScale, programmeSp = 12f * fontScale,
                guideRowHeightDp = 48
            )
            ChannelListSize.LARGE -> SidebarSizing(
                panelDp = 300, rowMinHeightDp = 64, rowPaddingDp = 12, groupPaddingDp = 14,
                numberWidthDp = Appearance.numberColumnWidthDp(36, fontSize, larger),
                logoDp = 36,
                nameSp = 15f * fontScale, programmeSp = 13f * fontScale,
                guideRowHeightDp = 56
            )
        }
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
