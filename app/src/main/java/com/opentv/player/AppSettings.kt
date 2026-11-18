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
    private const val KEY_GUIDE_FAVORITES_ONLY = "guide_favorites_only"
    private const val KEY_CHANNEL_LIST_SIZE = "channel_list_size"
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
}
