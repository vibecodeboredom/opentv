package com.opentv.player

import android.content.Intent
import android.os.Bundle
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
 * 9.0.0 — TV home screen: the browse-first home the TV opens on
 * instead of auto-playing. Favorites rail, Recent channels rail,
 * one rail per group, then the OpenTV actions row (Guide /
 * Recordings / Statistics / Settings / Refresh) — the same rows
 * the mobile home shows, built by the shared HomeRails builder so
 * the two stay in sync.
 *
 * Rows reload on resume: a favorite, a recent or a group override
 * may have changed while in playback, the guide or Settings. A
 * channel tap plays it (no CLEAR_TOP, so Back from playback comes
 * back here, like the mobile home).
 */
class TvHomeFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())
    /** True once the rows carry real channel rails (not just the
     * actions row after a failed/empty load). */
    private var hasContent = false
    /** True while a playlist load is in flight, so onResume never
     * starts a second one. */
    private var loadInFlight = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "OpenTV"
        adapter = rowsAdapter
        onItemViewClickedListener = OnItemViewClickedListener { _, item, _, _ ->
            when (item) {
                is Channel -> openChannel(item)
                is MenuAction -> onMenuAction(item)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (rowsAdapter.size() == 0) {
            loadPlaylist()
        }
    }

    override fun onResume() {
        super.onResume()
        // Favorites / recents / group overrides may have changed
        // while in playback, the guide or Settings — rebuild from
        // the cached playlist (no network) when there is one. And
        // when the home never got its content (the first load
        // failed), retry the load here instead of sitting on the
        // actions row forever — a load already in flight is not
        // started twice.
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null && hasContent) {
            buildRows(cached)
        } else if (!hasContent && !loadInFlight) {
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
        loadInFlight = true
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(requireContext())
            loadInFlight = false
            result.onSuccess { channels ->
                buildRows(channels)
                title = "OpenTV"
            }.onFailure {
                title = "OpenTV"
                Toast.makeText(requireContext(), R.string.load_failed, Toast.LENGTH_LONG).show()
                buildRows(emptyList())
            }
        }
    }

    private fun buildRows(channels: List<Channel>) {
        rowsAdapter.clear()
        hasContent = channels.isNotEmpty()

        val context = requireContext()
        val rails = HomeRails.buildRails(
            channels = channels,
            favorites = PlaylistRepository.favorites(context),
            recentIds = PlaylistRepository.recentChannelIds(context),
            hidden = PlaylistRepository.hiddenChannels(context),
            overrides = PlaylistRepository.groupOverrides(context),
            favoritesTitle = getString(R.string.favorites),
            recentsTitle = getString(R.string.home_recent_channels)
        )
        for (rail in rails) {
            val railAdapter = ArrayObjectAdapter(ChannelPresenter())
            rail.channels.forEach { railAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rail.title), railAdapter))
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
}
