package com.opentv.player

import android.app.AlertDialog
import android.content.Intent
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The left sidebar (TiviMate-style): a panel over a 60% black scrim
 * (6.1.2: width from AppSettings.sidebarSizing — 220dp compact
 * default, 260dp Medium, 300dp Large), listing groups at the top
 * ("All channels", "Favorites", "History", then the playlist groups)
 * and channels below with number + logo + name + the current
 * programme. The current channel is highlighted in the accent
 * (#86C739); the focused row gets a rounded 16dp highlight and its
 * text turns accent. Channel rows are capped at 200 (the footer says
 * so) so opening the sidebar never has to build thousands of views.
 *
 * 6.0.0-rc2: the list is a RecyclerView (SidebarAdapter). It used to
 * be a LinearLayout inside a ScrollView with up to 200 channel rows
 * (~1,200 views) inflated on every open and re-measured on every
 * focus move, plus 200 logo fetches fired at once — that was the lag
 * on the Onn 4K box. Only the ~10 visible rows bind now.
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
    private val list: RecyclerView,
    private val footer: TextView,
    private val currentChannelId: () -> String?,
    private val onChannelSelected: (Channel) -> Unit,
    private val sizing: AppSettings.SidebarSizing
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

    private val adapter = SidebarAdapter(
        onGroupClick = { item ->
            // 4.8.0: "Playlists" opens the picker, not a channel list.
            if (item.key == GROUP_PLAYLISTS) {
                showPlaylistPicker()
            } else {
                inGroups = false
                group = item.key
                buildChannels(item.key)
            }
        },
        onChannelClick = onChannelSelected,
        currentChannelId = currentChannelId,
        sizing = sizing
    )

    init {
        // 6.1.2: panel width comes from the size setting (the XML
        // default is the compact 220dp; a Medium/Large choice widens
        // it). Set before first layout so the slide animation reads
        // the right width.
        panel.layoutParams = panel.layoutParams.apply { width = dp(sizing.panelDp) }
        list.layoutManager = LinearLayoutManager(activity)
        list.adapter = adapter
        // Rows are a fixed height, so the list's own size never
        // depends on its contents; and no change animations, so a
        // rebind never flickers or steals focus.
        list.setHasFixedSize(true)
        list.itemAnimator = null
    }

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
        val width = panel.width.takeIf { it > 0 } ?: dp(sizing.panelDp)
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
        val width = panel.width.takeIf { it > 0 } ?: dp(sizing.panelDp)
        panel.animate().translationX(-width.toFloat()).setDuration(180)
            .withEndAction {
                if (generation != hideGeneration) return@withEndAction
                panel.visibility = View.GONE
                scrim.visibility = View.GONE
                panel.translationX = 0f
                scrim.alpha = 1f
            }.start()
        scrim.animate().alpha(0f).setDuration(180).start()
        adapter.clearTitles()
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
        val pos = list.getChildAdapterPosition(focused)
        when (val item = adapter.itemAt(pos)) {
            is SidebarAdapter.Item.GroupItem -> {
                if (item.group.key == GROUP_PLAYLISTS) {
                    showPlaylistPicker()
                    return
                }
                inGroups = false
                group = item.group.key
                buildChannels(item.group.key)
            }
            is SidebarAdapter.Item.ChannelItem -> onChannelSelected(item.channel)
            null -> return
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
        val count = adapter.itemCount
        if (count == 0) return false
        // The focused row is one of the visible children; its adapter
        // position is the index to step from.
        var current = -1
        for (i in 0 until list.childCount) {
            val child = list.getChildAt(i)
            if (child.isFocused) {
                current = list.getChildAdapterPosition(child)
                break
            }
        }
        if (current < 0) current = if (down) -1 else count
        val next = if (down) (current + 1).coerceAtMost(count - 1)
        else (current - 1).coerceAtLeast(0)
        list.scrollToPosition(next)
        // The row may not be laid out until the scroll lands; ask for
        // focus after the next layout pass.
        list.post { list.layoutManager?.findViewByPosition(next)?.requestFocus() }
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

    /** Scroll a position into view and give it focus once laid out. */
    private fun focusPosition(pos: Int) {
        if (pos < 0 || pos >= adapter.itemCount) return
        list.scrollToPosition(pos)
        list.post { list.layoutManager?.findViewByPosition(pos)?.requestFocus() }
    }

    private fun dp(v: Int): Int =
        (v * activity.resources.displayMetrics.density).toInt()

    /** The groups list: All channels, Favorites, History, then playlist groups.
     * Builds synchronously when the playlist is already in memory. */
    private fun buildGroups() {
        title.setText(R.string.sidebar_groups)
        subtitle.text = ""
        footer.text = ""
        adapter.submitList(emptyList(), emptyMap(), "")
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
        val items = mutableListOf<SidebarAdapter.Item>()
        // 4.8.0: "Playlists" first — it opens the playlist picker,
        // not a channel list. The count is the enabled (switchable)
        // playlists; disabled ones don't show in the picker (4.9.0).
        items += SidebarAdapter.Item.GroupItem(
            SidebarGroup(
                GROUP_PLAYLISTS, activity.getString(R.string.playlists_title),
                PlaylistRepository.enabledPlaylists(activity).size
            )
        )
        items += SidebarAdapter.Item.GroupItem(
            SidebarGroup(GROUP_ALL, activity.getString(R.string.sidebar_all_channels), visibleCount)
        )
        items += SidebarAdapter.Item.GroupItem(
            SidebarGroup(
                GROUP_FAVORITES, activity.getString(R.string.favorites),
                favoriteCount
            )
        )
        items += SidebarAdapter.Item.GroupItem(
            SidebarGroup(
                GROUP_HISTORY, activity.getString(R.string.sidebar_history), recents.size
            )
        )
        for ((g, count) in groupCounts) {
            items += SidebarAdapter.Item.GroupItem(SidebarGroup(g, g, count))
        }
        adapter.submitList(items, emptyMap(), activity.getString(R.string.overlay_no_info))
        if (items.isEmpty()) {
            footer.setText(R.string.sidebar_no_groups)
        }
        // Focus the "All channels" row so Up/Down works straight away
        // (it is the second row now — "Playlists" sits above it).
        list.post {
            focusPosition(adapter.positionOfGroup(GROUP_ALL))
        }
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
        adapter.submitList(emptyList(), emptyMap(), "")
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
        val items = shown.map { SidebarAdapter.Item.ChannelItem(it) }
        // The cached EPG lets the first bind already show now-playing
        // titles; without it, fillNowPlaying updates rows as it arrives.
        val cachedEpg = PlaylistRepository.cachedEpg()
        val titles = if (cachedEpg != null) programmeTitlesFor(cachedEpg, shown) else emptyMap()
        adapter.submitList(items, titles, activity.getString(R.string.overlay_no_info))
        if (channelList.isEmpty()) {
            footer.setText(R.string.sidebar_no_channels)
        } else if (channelList.size > SIDEBAR_MAX_CHANNELS) {
            footer.text = activity.getString(R.string.sidebar_first_n, SIDEBAR_MAX_CHANNELS)
        }
        // Focus the current channel's row (or the first row) so
        // Up/Down works straight away.
        val currentId = currentChannelId()
        list.post {
            val pos = currentId?.let { adapter.positionOfChannel(it) }?.takeIf { it >= 0 } ?: 0
            focusPosition(pos)
        }
        if (cachedEpg == null) fillNowPlaying(shown)
    }

    /** Fill in each shown row's "now playing" programme once the EPG
     * loads. Only runs when the cached EPG wasn't already in memory
     * (renderChannels covers that case inline). */
    private fun fillNowPlaying(shown: List<Channel>) {
        scope.launch {
            val epg = PlaylistRepository.loadEpg(activity)
            if (!isVisible() || inGroups) return@launch
            adapter.updateProgrammeTitles(programmeTitlesFor(epg, shown))
        }
    }

    /** Channel id -> now-playing title for the shown rows. */
    private fun programmeTitlesFor(
        epg: Map<String, List<EpgParser.Programme>>,
        shown: List<Channel>
    ): Map<String, String> {
        val now = System.currentTimeMillis()
        val noInfo = activity.getString(R.string.overlay_no_info)
        val titles = mutableMapOf<String, String>()
        for (ch in shown) {
            val programmes = epg[ch.tvgId] ?: epg[ch.name.lowercase()]
            val current = EpgParser.nowAndNext(programmes, now).first
            titles[ch.id] = current?.title ?: noInfo
        }
        return titles
    }
}
