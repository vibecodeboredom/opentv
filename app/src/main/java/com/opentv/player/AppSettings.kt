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
     * filtering instead of emptying the guide. */
    fun guideGroupFilter(context: Context): String? =
        prefs(context).getString(KEY_GUIDE_GROUP_FILTER, null)
            ?.takeIf { it.isNotEmpty() }

    fun setGuideGroupFilter(context: Context, group: String?) {
        val edit = prefs(context).edit()
        if (group == null) edit.remove(KEY_GUIDE_GROUP_FILTER)
        else edit.putString(KEY_GUIDE_GROUP_FILTER, group)
        edit.apply()
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
