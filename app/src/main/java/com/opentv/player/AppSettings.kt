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
