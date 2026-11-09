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
    /** 5.0.0 — neat animations: guards the hide slide-out's end
     * action, so a show() mid-slide can't be undone by the stale
     * hide finishing after it. */
    private var hideGeneration = 0
    /** Channel id -> "now playing" TextView, so the EPG fill-in can update
     * rows after they are built. */
    private val programmeViews = mutableMapOf<String, TextView>()

    /** Display density, cached once — dp() used to read
     * activity.resources.displayMetrics on every call (up to ~10 per
     * sidebar row, 200 rows per open). */
    private val density: Float by lazy { activity.resources.displayMetrics.density }

    fun isVisible(): Boolean = panel.visibility == View.VISIBLE

    /** Open the sidebar on the groups list. 5.0.0: it slides in from
     * the left (200ms) with the scrim fading in, instead of popping
     * in — fast enough to never delay navigation. */
    fun show() {
        hideGeneration++
        inGroups = true
        group = GROUP_ALL
        scrim.visibility = View.VISIBLE
        panel.visibility = View.VISIBLE
        panel.animate().cancel()
        scrim.animate().cancel()
        val width = panel.width.takeIf { it > 0 } ?: dp(260)
        panel.translationX = -width.toFloat()
        scrim.alpha = 0f
        panel.animate().translationX(0f).setDuration(200).start()
        scrim.animate().alpha(1f).setDuration(200).start()
        buildGroups()
    }

    /** 5.0.0: slides back out to the left (180ms); the views hide
     * when the slide finishes. */
    fun hide() {
        if (panel.visibility != View.VISIBLE) return
        val generation = ++hideGeneration
        panel.animate().cancel()
        scrim.animate().cancel()
        val width = panel.width.takeIf { it > 0 } ?: dp(260)
        panel.animate().translationX(-width.toFloat()).setDuration(180)
            .withEndAction {
                if (generation != hideGeneration) return@withEndAction
                panel.visibility = View.GONE
                scrim.visibility = View.GONE
                panel.translationX = 0f
                scrim.alpha = 1f
            }.start()
        scrim.animate().alpha(0f).setDuration(180).start()
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
        // 4.9.5: plain loop instead of building a throwaway list of all
        // rows on every Up/Down press just to find the focused one.
        var current = -1
        for (i in 0 until list.childCount) {
            if (list.getChildAt(i).isFocused) {
                current = i
                break
            }
        }
        if (current < 0) current = if (down) -1 else list.childCount
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

    private fun dp(v: Int): Int = (v * density).toInt()

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
        val favorites = PlaylistRepository.favorites(activity)
        val recents = PlaylistRepository.recentChannelIds(activity)
        // Single pass for the visible count, the per-group counts and
        // the favorites count — the old code built a filtered copy of
        // the whole playlist, then scanned it once per group (groupingBy)
        // and once more for favorites.
        val groupCounts = mutableMapOf<String, Int>()
        var visibleCount = 0
        var favoriteCount = 0
        for (ch in channels) {
            if (ch.id in hidden) continue
            visibleCount++
            groupCounts[ch.group] = (groupCounts[ch.group] ?: 0) + 1
            if (ch.id in favorites) favoriteCount++
        }
        val items = mutableListOf<SidebarGroup>()
        // 4.8.0: "Playlists" first — it opens the playlist picker,
        // not a channel list. The count is the enabled (switchable)
        // playlists; disabled ones don't show in the picker (4.9.0).
        items += SidebarGroup(
            GROUP_PLAYLISTS, activity.getString(R.string.playlists_title),
            PlaylistRepository.enabledPlaylists(activity).size
        )
        items += SidebarGroup(GROUP_ALL, activity.getString(R.string.sidebar_all_channels), visibleCount)
        items += SidebarGroup(
            GROUP_FAVORITES, activity.getString(R.string.favorites),
            favoriteCount
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
        // Single pass, no filtered copy of the whole playlist — the
        // old code built one, then filtered it a second time for the
        // group. The id map is only built for History, the one group
        // that looks channels up by id (the old code built a ~1500
        // entry map on every group open, even for All channels).
        val channelList: List<Channel> = when (groupKey) {
            GROUP_HISTORY -> {
                val byId = channels.associateBy { it.id }
                val recents = PlaylistRepository.recentChannelIds(activity)
                recents.mapNotNull { byId[it] }.filter { it.id !in hidden }
            }
            GROUP_FAVORITES -> {
                val favorites = PlaylistRepository.favorites(activity)
                val list = mutableListOf<Channel>()
                for (ch in channels) {
                    if (ch.id !in hidden && ch.id in favorites) list += ch
                }
                list
            }
            GROUP_ALL -> {
                val list = mutableListOf<Channel>()
                for (ch in channels) {
                    if (ch.id !in hidden) list += ch
                }
                list
            }
            else -> {
                val list = mutableListOf<Channel>()
                for (ch in channels) {
                    if (ch.id !in hidden && ch.group == groupKey) list += ch
                }
                list
            }
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
            // 4.9.5: plain loops instead of throwaway lists of all rows.
            var target: View? = null
            for (i in 0 until list.childCount) {
                val row = list.getChildAt(i)
                if ((row.tag as? Channel)?.id == currentId) {
                    target = row
                    break
                }
            }
            (target ?: list.getChildAt(0))?.requestFocus()
            // Keep the focused row visible once the list is laid out.
            scroll.post {
                focusedRow()?.let { scroll.scrollTo(0, it.top) }
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
