package com.opentv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * The main screen: channel rows grouped by category, a Favorites row on top,
 * and an OpenTV row with playlist settings + refresh.
 */
class BrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

    // 8.2.0 (Nova): state for the "On now" row — the playlist and
    // EPG the home screen last loaded, the row currently in the
    // adapter (null while hidden), and the signature of the entries
    // it shows, so the ticker can leave an unchanged row alone
    // instead of rebuilding the row out from under the focus.
    private var homeChannels: List<Channel> = emptyList()
    private var homeEpg: Map<String, List<EpgParser.Programme>>? = null
    private var onNowRow: ListRow? = null
    private var onNowSignature: String = ""
    private val onNowHandler = Handler(Looper.getMainLooper())
    private val onNowTicker = object : Runnable {
        override fun run() {
            refreshOnNowRow()
            onNowHandler.postDelayed(this, ON_NOW_TICK_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OpenTV"
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Channel -> openChannel(item)
                is OnNowEntry -> openChannel(item.channel)
                is MenuAction -> onMenuAction(item)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 8.2.0 (Nova): coming back to the home screen (from
        // playback, settings, anywhere) refreshes what's on now,
        // and the ticker keeps it honest while the screen sits open.
        refreshOnNowRow()
        onNowHandler.removeCallbacks(onNowTicker)
        onNowHandler.postDelayed(onNowTicker, ON_NOW_TICK_MS)
    }

    override fun onPause() {
        onNowHandler.removeCallbacks(onNowTicker)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (rowsAdapter.size() == 0) {
            loadPlaylist()
        }
    }

    private fun openChannel(channel: Channel) {
        val intent = Intent(requireContext(), PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, channel)
        startActivity(intent)
    }

    private fun onMenuAction(action: MenuAction) {
        when (action.id) {
            "settings" -> startActivity(Intent(requireContext(), SettingsActivity::class.java))
            "refresh" -> loadPlaylist()
            "guide" -> startActivity(Intent(requireContext(), GuideActivity::class.java))
            "recordings" -> startActivity(Intent(requireContext(), RecordingsActivity::class.java))
            "statistics" -> startActivity(Intent(requireContext(), StatsActivity::class.java))
        }
    }

    private fun loadPlaylist() {
        title = getString(R.string.loading)
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(requireContext())
            result.onSuccess { channels ->
                homeChannels = channels
                buildRows(channels)
                title = "OpenTV"
                // 8.2.0 (Nova): the EPG loads behind the rows —
                // the home screen paints as fast as it always did,
                // and the "On now" row fills in when the guide
                // data lands (loadEpg is single-flight with the
                // copy playback and the guide use, so this costs
                // no second download).
                val epgMap = runCatching {
                    PlaylistRepository.loadEpg(requireContext())
                }.getOrNull()
                if (epgMap != null) {
                    homeEpg = epgMap
                    refreshOnNowRow()
                }
            }.onFailure {
                title = "OpenTV"
                Toast.makeText(requireContext(), R.string.load_failed, Toast.LENGTH_LONG).show()
                buildRows(emptyList())
            }
        }
    }

    private fun buildRows(channels: List<Channel>) {
        rowsAdapter.clear()
        // 8.2.0 (Nova): the adapter was just emptied — the tracked
        // "On now" row went with it. refreshOnNowRow re-adds it
        // (loadPlaylist calls it once the EPG is in).
        onNowRow = null
        onNowSignature = ""

        val hidden = PlaylistRepository.hiddenChannels(requireContext())
        val favorites = PlaylistRepository.favorites(requireContext())
        // Single pass for the favorites row and the group rows — the
        // old code scanned the whole playlist three times (two filters
        // + groupBy) to build the same rows. 7.0.0 (Nova): the same
        // pass also fills an id lookup for the Recently watched row,
        // so it costs no extra scan either.
        val favoriteChannels = mutableListOf<Channel>()
        val grouped = linkedMapOf<String, MutableList<Channel>>()
        val visibleById = HashMap<String, Channel>()
        for (ch in channels) {
            if (hidden.contains(ch.id)) continue
            visibleById[ch.id] = ch
            if (favorites.contains(ch.id)) favoriteChannels += ch
            (grouped.getOrPut(ch.group) { mutableListOf() }) += ch
        }
        if (favoriteChannels.isNotEmpty()) {
            val favAdapter = ArrayObjectAdapter(ChannelPresenter())
            favoriteChannels.forEach { favAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.favorites)), favAdapter))
        }

        // 7.0.0 (Nova): Recently watched, right under Favorites —
        // the history was already tracked for the playback strip and
        // the sidebar, but the home screen never showed it. Hidden
        // channels drop out via the visible-only lookup above.
        val recentChannels = PlaylistRepository.recentChannelIds(requireContext())
            .mapNotNull { visibleById[it] }
        if (recentChannels.isNotEmpty()) {
            val recentAdapter = ArrayObjectAdapter(ChannelPresenter())
            recentChannels.forEach { recentAdapter.add(it) }
            rowsAdapter.add(
                ListRow(HeaderItem(getString(R.string.recently_watched)), recentAdapter)
            )
        }

        for ((group, groupChannels) in grouped) {
            val groupAdapter = ArrayObjectAdapter(ChannelPresenter())
            groupChannels.forEach { groupAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(group), groupAdapter))
        }

        val actionsAdapter = ArrayObjectAdapter(ActionPresenter())
        actionsAdapter.add(
            MenuAction(
                "guide",
                getString(R.string.tv_guide),
                getString(R.string.tv_guide_desc)
            )
        )
        actionsAdapter.add(
            MenuAction(
                "recordings",
                getString(R.string.recordings),
                getString(R.string.recordings_desc)
            )
        )
        actionsAdapter.add(
            MenuAction(
                "statistics",
                getString(R.string.statistics),
                getString(R.string.statistics_desc)
            )
        )
        actionsAdapter.add(
            MenuAction(
                "settings",
                getString(R.string.change_playlist),
                getString(R.string.change_playlist_desc)
            )
        )
        actionsAdapter.add(
            MenuAction(
                "refresh",
                getString(R.string.refresh_playlist),
                getString(R.string.refresh_playlist_desc)
            )
        )
        rowsAdapter.add(ListRow(HeaderItem(getString(R.string.settings_row)), actionsAdapter))

        if (channels.isEmpty()) {
            Toast.makeText(requireContext(), R.string.no_channels, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 8.2.0 (Nova): the "On now" entries — every favorite channel
     * (playlist order, hidden ones skipped, exactly like the
     * Favorites row) paired with the programme on it right now.
     * Channels whose EPG has nothing real on at the moment drop
     * out; if none survive, the row hides.
     */
    private fun buildOnNowEntries(): List<OnNowEntry> {
        val epgMap = homeEpg ?: return emptyList()
        if (homeChannels.isEmpty() || !isAdded) return emptyList()
        val context = requireContext()
        val favorites = PlaylistRepository.favorites(context)
        if (favorites.isEmpty()) return emptyList()
        val hidden = PlaylistRepository.hiddenChannels(context)
        val overrides = PlaylistRepository.epgOverrides(context)
        val now = System.currentTimeMillis()
        val entries = mutableListOf<OnNowEntry>()
        for (channel in homeChannels) {
            if (hidden.contains(channel.id) || !favorites.contains(channel.id)) continue
            val current = GuideData.programmesForChannel(channel, epgMap, overrides)
                .firstOrNull { now in it.startMillis until it.stopMillis }
            if (current != null && current.title != GuideData.NO_INFORMATION) {
                entries += OnNowEntry(channel, current)
            }
        }
        return entries
    }

    /**
     * 8.2.0 (Nova): insert, update or hide the "On now" row at the
     * top of the home screen. Called when the EPG lands, when the
     * screen regains focus, and once a minute while it sits open.
     * An unchanged programme set leaves the row untouched — the
     * ticker must never rebuild a row out from under the focus;
     * the minutes-left text refreshes at programme boundaries and
     * on resume instead.
     */
    private fun refreshOnNowRow() {
        if (!isAdded || homeEpg == null) return
        val entries = buildOnNowEntries()
        val signature = entries.joinToString("|") {
            it.channel.id + "@" + it.programme.startMillis
        }
        if (entries.isEmpty()) {
            onNowRow?.let { rowsAdapter.remove(it) }
            onNowRow = null
            onNowSignature = ""
            return
        }
        if (signature == onNowSignature && onNowRow != null) return
        val adapter = ArrayObjectAdapter(OnNowPresenter())
        entries.forEach { adapter.add(it) }
        val row = ListRow(HeaderItem(getString(R.string.on_now)), adapter)
        val existing = onNowRow
        if (existing != null) {
            val index = rowsAdapter.indexOf(existing)
            if (index >= 0) {
                rowsAdapter.remove(existing)
                rowsAdapter.add(index, row)
            } else {
                rowsAdapter.add(0, row)
            }
        } else {
            rowsAdapter.add(0, row)
        }
        onNowRow = row
        onNowSignature = signature
    }

    private companion object {
        /** 8.2.0 (Nova): how often the "On now" row re-checks the
         * EPG while the home screen sits open. */
        const val ON_NOW_TICK_MS = 60_000L
    }
}
