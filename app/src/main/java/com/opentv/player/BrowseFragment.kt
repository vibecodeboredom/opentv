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
 * The main screen: channel rows grouped by category, a Favorites row on top,
 * and an OpenTV row with playlist settings + refresh.
 */
class BrowseFragment : BrowseSupportFragment() {

    private val rowsAdapter = ArrayObjectAdapter(ListRowPresenter())

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

        val favorites = PlaylistRepository.favorites(requireContext())
        val favoriteChannels = channels.filter { favorites.contains(it.id) }
        if (favoriteChannels.isNotEmpty()) {
            val favAdapter = ArrayObjectAdapter(ChannelPresenter())
            favoriteChannels.forEach { favAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(getString(R.string.favorites)), favAdapter))
        }

        val grouped = channels.groupBy { it.group }
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
}
