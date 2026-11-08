package com.opentv.player

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import coil.load
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The left sidebar (TiviMate-style): a 260dp panel over a 60% black
 * scrim, listing groups at the top ("All channels", "Favorites",
 * "History", then the playlist groups) and channels below with number
 * + logo + name + the current programme. The current channel is
 * highlighted in the accent (#86C739); the focused row gets a rounded
 * 16dp highlight and its text turns accent. Channel rows are capped
 * at 200 (the footer says so) so opening the sidebar never has to
 * build thousands of views.
 *
 * Shared by PlaybackActivity and GuideActivity (4.6.2). The two callers
 * differ only in which channel counts as "current" and what happens
 * when a channel is picked, so those are constructor callbacks:
 * - Playback: current = the channel playing; pick = restart playback.
 * - Guide: current = the channel playing underneath (passed in);
 *   pick = start playback of that channel.
 *
 * Data loading reuses the in-memory playlist/EPG cache
 * (PlaylistRepository.cachedPlaylist/cachedEpg), so the sidebar builds
 * synchronously in the common case instead of hopping through a
 * coroutine just to hit the cache.
 */
class ChannelSidebar(
    private val activity: FragmentActivity,
    private val scope: CoroutineScope,
    private val scrim: View,
    private val panel: View,
    private val title: TextView,
    private val subtitle: TextView,
    private val scroll: ScrollView,
    private val list: LinearLayout,
    private val footer: TextView,
    private val currentChannelId: () -> String?,
    private val onChannelSelected: (Channel) -> Unit
) {

    companion object {
        /** Sidebar channel rows are capped at this many (see the footer). */
        private const val SIDEBAR_MAX_CHANNELS = 200
        /** Sidebar group keys (pseudo-groups first, then playlist groups). */
        private const val GROUP_ALL = "ALL"
        private const val GROUP_FAVORITES = "FAV"
        private const val GROUP_HISTORY = "HIST"
        /** 4.8.0: pseudo-group that opens the playlist picker instead
         * of a channel list. */
        private const val GROUP_PLAYLISTS = "PLAYLISTS"
    }

    /** Are we showing the groups list, and which group
     * (GROUP_ALL / GROUP_FAVORITES / GROUP_HISTORY / a group name). */
    private var inGroups = true
    private var group = GROUP_ALL
    /** Channel id -> "now playing" TextView, so the EPG fill-in can update
     * rows after they are built. */
    private val programmeViews = mutableMapOf<String, TextView>()

    fun isVisible(): Boolean = panel.visibility == View.VISIBLE

    /** Open the sidebar on the groups list. */
    fun show() {
        inGroups = true
        group = GROUP_ALL
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        buildGroups()
    }

    fun hide() {
        panel.visibility = View.GONE
        scrim.visibility = View.GONE
        programmeViews.clear()
    }

    /** Left / BACK inside the sidebar: channels -> groups, groups -> close. */
    fun back() {
        if (!inGroups) {
            inGroups = true
            buildGroups()
        } else {
            hide()
        }
    }

    /** OK / Right inside the sidebar: open a group, or pick a channel.
     * The "Playlists" row opens the playlist picker instead. */
    fun activate() {
        val focused = focusedRow() ?: return
        when (val tag = focused.tag) {
            is SidebarGroup -> {
                if (tag.key == GROUP_PLAYLISTS) {
                    showPlaylistPicker()
                    return
                }
                inGroups = false
                group = tag.key
                buildChannels(tag.key)
            }
            is Channel -> onChannelSelected(tag)
        }
    }

    /** Restart the app's loading flow with the newly active playlist. */
    private fun restartToMain() {
        val intent = Intent(activity, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        activity.startActivity(intent)
        activity.finish()
    }

    /** Playlist picker (4.8.0): the active playlist is marked; picking
     * another one switches to it and reloads the app with it.
     * 4.9.0: only ENABLED playlists are listed — a deactivated one is
     * not a switchable option. */
    private fun showPlaylistPicker() {
        val playlists = PlaylistRepository.enabledPlaylists(activity)
        val active = PlaylistRepository.activePlaylist(activity)
        val names = playlists.map { p ->
            if (p.url == active.url) "● ${p.name}" else p.name
        }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.playlists_title)
            .setItems(names) { _, which ->
                val picked = playlists[which]
                if (picked.url == active.url) return@setItems
                if (PlaylistRepository.setActivePlaylist(activity, picked.url)) {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.playlist_switched, picked.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartToMain()
                }
            }
            .show()
    }

    /** Move focus to the next / previous row (Up / Down). Used by the
     * guide, where the grid behind the sidebar is also focusable, so
     * framework focus search could jump out of the sidebar. Returns
     * true when focus moved (the key is consumed). */
    fun moveFocus(down: Boolean): Boolean {
        if (list.childCount == 0) return false
        val current = (0 until list.childCount)
            .map { list.getChildAt(it) }
            .indexOfFirst { it.isFocused }
            .takeIf { it >= 0 } ?: if (down) -1 else list.childCount
        val next = if (down) (current + 1).coerceAtMost(list.childCount - 1)
        else (current - 1).coerceAtLeast(0)
        val target = list.getChildAt(next) ?: return false
        target.requestFocus()
        // Keep the newly focused row visible.
        scroll.post { scroll.scrollTo(0, target.top) }
        return true
    }

    /** The sidebar row (group or channel) that currently has focus. */
    private fun focusedRow(): View? {
        for (i in 0 until list.childCount) {
            val row = list.getChildAt(i)
            if (row.isFocused) return row
        }
        return null
    }

    private data class SidebarGroup(val key: String, val label: String, val count: Int)

    private fun dp(v: Int): Int = (v * activity.resources.displayMetrics.density).toInt()

    /** The groups list: All channels, Favorites, History, then playlist groups.
     * Builds synchronously when the playlist is already in memory. */
    private fun buildGroups() {
        title.setText(R.string.sidebar_groups)
        subtitle.text = ""
        footer.text = ""
        list.removeAllViews()
        programmeViews.clear()
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            renderGroups(cached)
            return
        }
        scope.launch {
            val result = PlaylistRepository.loadPlaylist(activity)
            val channels = result.getOrNull() ?: emptyList()
            if (!isVisible() || !inGroups) return@launch
            renderGroups(channels)
        }
    }

    /** Render the groups list from a channel list. Runs on the main thread. */
    private fun renderGroups(channels: List<Channel>) {
        val hidden = PlaylistRepository.hiddenChannels(activity)
        val visible = channels.filter { it.id !in hidden }
        val favorites = PlaylistRepository.favorites(activity)
        val recents = PlaylistRepository.recentChannelIds(activity)
        // Single pass for the per-group counts (was one full scan per group).
        val groupCounts = visible.groupingBy { it.group }.eachCount()
        val items = mutableListOf<SidebarGroup>()
        // 4.8.0: "Playlists" first — it opens the playlist picker,
        // not a channel list. The count is the enabled (switchable)
        // playlists; disabled ones don't show in the picker (4.9.0).
        items += SidebarGroup(
            GROUP_PLAYLISTS, activity.getString(R.string.playlists_title),
            PlaylistRepository.enabledPlaylists(activity).size
        )
        items += SidebarGroup(GROUP_ALL, activity.getString(R.string.sidebar_all_channels), visible.size)
        items += SidebarGroup(
            GROUP_FAVORITES, activity.getString(R.string.favorites),
            visible.count { it.id in favorites }
        )
        items += SidebarGroup(
            GROUP_HISTORY, activity.getString(R.string.sidebar_history), recents.size
        )
        for ((g, count) in groupCounts) {
            items += SidebarGroup(g, g, count)
        }
        for (item in items) {
            list.addView(buildGroupRow(item))
        }
        if (items.isEmpty()) {
            footer.setText(R.string.sidebar_no_groups)
        }
        // Focus the "All channels" row so Up/Down works straight away
        // (it is the second row now — "Playlists" sits above it).
        list.post {
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i)
                if ((row.tag as? SidebarGroup)?.key == GROUP_ALL) {
                    row.requestFocus()
                    break
                }
            }
        }
    }

    /** One group row: name + channel count, accent text when focused. */
    private fun buildGroupRow(item: SidebarGroup): View {
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            minimumHeight = dp(56)
            setBackgroundResource(R.drawable.sidebar_row_bg)
            tag = item
            setOnClickListener {
                // 4.8.0: "Playlists" opens the picker, not a channel list.
                if (item.key == GROUP_PLAYLISTS) {
                    showPlaylistPicker()
                    return@setOnClickListener
                }
                inGroups = false
                group = item.key
                buildChannels(item.key)
            }
        }
        val nameView = TextView(activity).apply {
            text = item.label
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val countView = TextView(activity).apply {
            text = item.count.toString()
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        row.addView(nameView)
        row.addView(countView)
        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            nameView.setTextColor(if (hasFocus) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt())
        }
        return row
    }

    /** The channels of one group (or a pseudo-group), capped at 200 rows.
     * Builds synchronously when the playlist is already in memory. */
    private fun buildChannels(groupKey: String) {
        val groupLabel = when (groupKey) {
            GROUP_ALL -> activity.getString(R.string.sidebar_all_channels)
            GROUP_FAVORITES -> activity.getString(R.string.favorites)
            GROUP_HISTORY -> activity.getString(R.string.sidebar_history)
            else -> groupKey
        }
        title.text = groupLabel
        list.removeAllViews()
        programmeViews.clear()
        footer.text = ""
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            renderChannels(groupKey, cached)
            return
        }
        scope.launch {
            val result = PlaylistRepository.loadPlaylist(activity)
            val channels = result.getOrNull() ?: emptyList()
            if (!isVisible() || inGroups) return@launch
            renderChannels(groupKey, channels)
        }
    }

    /** Render one group's channels from a channel list. Main thread. */
    private fun renderChannels(groupKey: String, channels: List<Channel>) {
        val hidden = PlaylistRepository.hiddenChannels(activity)
        val visible = channels.filter { it.id !in hidden }
        val favorites = PlaylistRepository.favorites(activity)
        val recents = PlaylistRepository.recentChannelIds(activity)
        val byId = channels.associateBy { it.id }
        val channelList = when (groupKey) {
            GROUP_ALL -> visible
            GROUP_FAVORITES -> visible.filter { it.id in favorites }
            GROUP_HISTORY -> recents.mapNotNull { byId[it] }.filter { it.id !in hidden }
            else -> visible.filter { it.group == groupKey }
        }
        subtitle.text = activity.getString(R.string.sidebar_channels_count, channelList.size)
        val shown = channelList.take(SIDEBAR_MAX_CHANNELS)
        for (ch in shown) {
            list.addView(buildChannelRow(ch))
        }
        if (channelList.isEmpty()) {
            footer.setText(R.string.sidebar_no_channels)
        } else if (channelList.size > SIDEBAR_MAX_CHANNELS) {
            footer.text = activity.getString(R.string.sidebar_first_n, SIDEBAR_MAX_CHANNELS)
        }
        // Focus the current channel's row (or the first row) so
        // Up/Down works straight away.
        val currentId = currentChannelId()
        list.post {
            val target = (0 until list.childCount)
                .map { list.getChildAt(it) }
                .firstOrNull { (it.tag as? Channel)?.id == currentId }
                ?: list.getChildAt(0)
            target?.requestFocus()
            // Keep the focused row visible once the list is laid out.
            scroll.post {
                (0 until list.childCount)
                    .map { list.getChildAt(it) }
                    .firstOrNull { it.isFocused }
                    ?.let { scroll.scrollTo(0, it.top) }
            }
        }
        fillNowPlaying(shown)
    }

    /** Fill in each shown row's "now playing" programme. Uses the cached
     * EPG when it is already in memory; otherwise loads it once and
     * fills in when it arrives. */
    private fun fillNowPlaying(shown: List<Channel>) {
        val cached = PlaylistRepository.cachedEpg()
        if (cached != null) {
            applyEpg(cached, shown)
            return
        }
        scope.launch {
            val epg = PlaylistRepository.loadEpg(activity)
            if (!isVisible() || inGroups) return@launch
            applyEpg(epg, shown)
        }
    }

    private fun applyEpg(
        epg: Map<String, List<EpgParser.Programme>>,
        shown: List<Channel>
    ) {
        val now = System.currentTimeMillis()
        val noInfo = activity.getString(R.string.overlay_no_info)
        for (ch in shown) {
            val view = programmeViews[ch.id] ?: continue
            val programmes = epg[ch.tvgId] ?: epg[ch.name.lowercase()]
            val current = EpgParser.nowAndNext(programmes, now).first
            view.text = current?.title ?: noInfo
        }
    }

    /** One channel row: number + logo + name + now-playing programme. */
    private fun buildChannelRow(ch: Channel): View {
        val isCurrent = ch.id == currentChannelId()
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(10), dp(10), dp(10), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2); bottomMargin = dp(2) }
            minimumHeight = dp(56)
            setBackgroundResource(R.drawable.sidebar_row_bg)
            tag = ch
            setOnClickListener { onChannelSelected(ch) }
        }
        val numberView = TextView(activity).apply {
            text = if (ch.number > 0) ch.number.toString() else ""
            // The current channel's number is accent; the rest are 70% white.
            setTextColor(if (isCurrent) 0xFF86C739.toInt() else 0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(dp(32), LinearLayout.LayoutParams.MATCH_PARENT)
        }
        val logoView = ImageView(activity).apply {
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32)).apply {
                marginEnd = dp(8)
            }
            scaleType = ImageView.ScaleType.FIT_CENTER
            load(ch.logo) {
                placeholder(R.drawable.ic_play)
                error(R.drawable.ic_play)
            }
        }
        val textBlock = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val nameView = TextView(activity).apply {
            text = ch.name
            // Current channel in accent, the rest white.
            setTextColor(if (isCurrent) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        val programmeView = TextView(activity).apply {
            text = activity.getString(R.string.overlay_no_info)
            setTextColor(0xB3FFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        textBlock.addView(nameView)
        textBlock.addView(programmeView)
        row.addView(numberView)
        row.addView(logoView)
        row.addView(textBlock)
        programmeViews[ch.id] = programmeView
        row.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            // Focused row: name (and number, unless it is the current
            // channel, which is already accent) turns accent.
            nameView.setTextColor(
                if (hasFocus || isCurrent) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt()
            )
            if (!isCurrent) {
                numberView.setTextColor(if (hasFocus) 0xFF86C739.toInt() else 0xB3FFFFFF.toInt())
            }
        }
        return row
    }
}
