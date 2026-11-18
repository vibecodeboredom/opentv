package com.opentv.player

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.ArrayObjectAdapter
import androidx.leanback.widget.HeaderItem
import androidx.leanback.widget.ListRow
import androidx.leanback.widget.ListRowPresenter
import androidx.leanback.widget.OnItemViewClickedListener
import androidx.lifecycle.lifecycleScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
            // 8.3.0 (Nova): the watchlist rides the same ticker —
            // a reminded programme flipping to "On now" while the
            // home screen sits open updates its card unprompted.
            refreshWatchlistRow()
            // 9.2.0 (Nova): "More of what you watch" rides it
            // too — its programmes roll over the same way.
            refreshMoreRow()
            onNowHandler.postDelayed(this, ON_NOW_TICK_MS)
        }
    }
    // 8.3.0 (Nova): state for the "Watchlist" row, mirroring the
    // "On now" row's — the row currently in the adapter (null
    // while hidden) and the signature of the entries it shows.
    private var watchlistRow: ListRow? = null
    private var watchlistSignature: String = ""
    // 9.2.0 (Nova): state for the "More of what you watch" row,
    // same pattern again.
    private var moreRow: ListRow? = null
    private var moreSignature: String = ""

    // 10.0.0 (Nova): the home clock — a time + date label pinned
    // to the top end of the home screen, opposite the "OpenTV"
    // title. Its ticker re-arms to the next minute boundary, so
    // the minute flips when the wall clock does.
    private var homeClockView: TextView? = null
    private val clockHandler = Handler(Looper.getMainLooper())
    private val clockTicker = object : Runnable {
        override fun run() {
            updateHomeClock()
            clockHandler.postDelayed(this, millisToNextMinute())
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
                // 8.3.0 (Nova): a watchlist card whose programme
                // is on plays its channel.
                // 8.3.1 (Nova): a future one opens the guide
                // landed on its channel instead of the 8.3.0 info
                // toast — the viewer can look at what's around it
                // and set more reminders. It reuses the guide's
                // ordinary open-on-a-channel extra, the same one
                // playback passes for the channel underneath.
                is WatchlistEntry -> {
                    if (item.onNow) {
                        openChannel(item.channel)
                    } else {
                        startActivity(
                            Intent(requireContext(), GuideActivity::class.java)
                                .putExtra(
                                    GuideActivity.EXTRA_CURRENT_CHANNEL_ID,
                                    item.channel.id
                                )
                        )
                    }
                }
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
        // 8.3.0 (Nova): the watchlist refreshes on resume too —
        // a reminder set in the guide a moment ago shows up here
        // without waiting for the ticker.
        refreshWatchlistRow()
        // 9.2.0 (Nova): the discovery row likewise — watch
        // history may have moved on while the user was away.
        refreshMoreRow()
        onNowHandler.removeCallbacks(onNowTicker)
        onNowHandler.postDelayed(onNowTicker, ON_NOW_TICK_MS)
        // 10.0.0 (Nova): the home clock refreshes on resume and
        // then at every minute boundary while the screen is up.
        updateHomeClock()
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.postDelayed(clockTicker, millisToNextMinute())
    }

    override fun onPause() {
        onNowHandler.removeCallbacks(onNowTicker)
        clockHandler.removeCallbacks(clockTicker)
        super.onPause()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHomeClock(view)
        if (rowsAdapter.size() == 0) {
            loadPlaylist()
        }
    }

    /** 10.0.0 (Nova): pin the home clock to the top end of the
     * browse root (a frame, so the label rides on top of it),
     * opposite the title. Not focusable — the D-pad never lands
     * on it — and invisible to accessibility, since the time is
     * decoration here, not content. */
    private fun setupHomeClock(root: View) {
        val parent = root as? ViewGroup ?: return
        val density = resources.displayMetrics.density
        val clock = TextView(requireContext()).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            isFocusable = false
            isFocusableInTouchMode = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.END
            ).apply {
                topMargin = (26 * density).toInt()
                marginEnd = (48 * density).toInt()
            }
        }
        parent.addView(clock)
        homeClockView = clock
        updateHomeClock()
    }

    /** The clock's text: the time in the device's own 12/24-hour
     * preference, then the day and date. */
    private fun updateHomeClock() {
        val clock = homeClockView ?: return
        if (!isAdded) return
        val now = Date()
        val time = android.text.format.DateFormat
            .getTimeFormat(requireContext()).format(now)
        val date = SimpleDateFormat("EEE, MMM d", Locale.getDefault()).format(now)
        clock.text = "$time  •  $date"
    }

    /** Milliseconds until just past the next minute boundary. */
    private fun millisToNextMinute(): Long =
        60_000L - (System.currentTimeMillis() % 60_000L) + 200L

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
                    // 9.2.0 (Nova): the discovery row needs the
                    // EPG too (each card shows what's on now).
                    refreshMoreRow()
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
        // 8.3.0 (Nova): the watchlist row went the same way;
        // refreshWatchlistRow (called at the end of buildRows)
        // re-adds it.
        watchlistRow = null
        watchlistSignature = ""
        // 9.2.0 (Nova): the discovery row likewise.
        moreRow = null
        moreSignature = ""

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
            // 9.1.0 (Nova): the user's custom favorites order,
            // once one is set (playlist order until then).
            PlaylistRepository.applyFavoriteOrder(requireContext(), favoriteChannels)
                .forEach { favAdapter.add(it) }
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

        // 8.3.0 (Nova): the watchlist row goes in last here —
        // refreshWatchlistRow positions it under "On now" (or at
        // the top until that row lands with the EPG).
        refreshWatchlistRow()
        // 9.2.0 (Nova): the discovery row goes under the
        // watchlist (refreshMoreRow positions it; until the EPG
        // lands it has nothing to show and stays hidden).
        refreshMoreRow()
    }

    /**
     * 8.2.0 (Nova): the "On now" entries — every favorite channel
     * (hidden ones skipped, exactly like the Favorites row; in the
     * user's custom favorites order since 9.1.0) paired with the
     * programme on it right now.
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
        // 9.1.0 (Nova): iterate the favorites in the user's
        // custom order (playlist order until one is set) — the
        // per-channel EPG lookup is unchanged, only the walk
        // order follows the reordered favorites list.
        val favoriteChannels = homeChannels.filter {
            it.id !in hidden && favorites.contains(it.id)
        }
        for (channel in PlaylistRepository.applyFavoriteOrder(context, favoriteChannels)) {
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
     * An unchanged programme set never rebuilds the row out from
     * under the focus — instead the visible cards rebind in place
     * (8.2.1), so the progress bar and minutes-left text advance
     * with the ticker while focus stays put.
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
        if (signature == onNowSignature && onNowRow != null) {
            // 8.2.1 (Nova): same programmes, but the minute hand
            // moved — rebind the row's cards in place so their
            // progress bars and ends/remaining labels track the
            // existing 60s ticker. No row rebuild, no new timer.
            val rowAdapter = onNowRow?.adapter as? ArrayObjectAdapter
            if (rowAdapter != null && rowAdapter.size() > 0) {
                rowAdapter.notifyArrayItemRangeChanged(0, rowAdapter.size())
            }
            return
        }
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

    /**
     * 8.3.0 (Nova): the watchlist entries — the pending programme
     * reminders (the 8.0.0 store reads soonest-first and prunes
     * programmes that already ended), each resolved to its
     * channel. A reminder whose channel has left the playlist, or
     * is hidden like in every other home row, drops out — its
     * card could neither show the channel nor play it.
     */
    private fun buildWatchlistEntries(): List<WatchlistEntry> {
        if (homeChannels.isEmpty() || !isAdded) return emptyList()
        val context = requireContext()
        val hidden = PlaylistRepository.hiddenChannels(context)
        val byId = homeChannels.associateBy { it.id }
        val now = System.currentTimeMillis()
        return PlaylistRepository.reminders(context).mapNotNull { reminder ->
            val channel = byId[reminder.channelId] ?: return@mapNotNull null
            if (channel.id in hidden) return@mapNotNull null
            WatchlistEntry(
                reminder = reminder,
                channel = channel,
                onNow = now in reminder.startMillis until reminder.stopMillis
            )
        }
    }

    /**
     * 8.3.0 (Nova): insert, update or hide the "Watchlist" row,
     * directly under "On now" (or at the top while that row is
     * away). Called when the rows are built, on resume, and on
     * the "On now" ticker. A programme starting flips its
     * signature (the ":now" marker), and a reminder added or
     * ended changes the set — both rebuild the row.
     * 9.3.0 (Nova): an unchanged signature now rebinds in place
     * (the 8.2.1 "On now" pattern) instead of leaving the row
     * alone — the cards' countdown text is computed at bind
     * time, so the rebind is what advances it on the ticker.
     * No rebuild, so focus stays put.
     */
    private fun refreshWatchlistRow() {
        if (!isAdded || homeChannels.isEmpty()) return
        val entries = buildWatchlistEntries()
        val signature = entries.joinToString("|") {
            it.reminder.channelId + "@" + it.reminder.startMillis +
                if (it.onNow) ":now" else ""
        }
        if (entries.isEmpty()) {
            watchlistRow?.let { rowsAdapter.remove(it) }
            watchlistRow = null
            watchlistSignature = ""
            return
        }
        if (signature == watchlistSignature && watchlistRow != null) {
            val rowAdapter = watchlistRow?.adapter as? ArrayObjectAdapter
            if (rowAdapter != null && rowAdapter.size() > 0) {
                rowAdapter.notifyArrayItemRangeChanged(0, rowAdapter.size())
            }
            return
        }
        val adapter = ArrayObjectAdapter(WatchlistPresenter())
        entries.forEach { adapter.add(it) }
        val row = ListRow(HeaderItem(getString(R.string.watchlist)), adapter)
        val existing = watchlistRow
        if (existing != null) {
            val index = rowsAdapter.indexOf(existing)
            if (index >= 0) {
                rowsAdapter.remove(existing)
                rowsAdapter.add(index, row)
            } else {
                rowsAdapter.add(watchlistInsertIndex(), row)
            }
        } else {
            rowsAdapter.add(watchlistInsertIndex(), row)
        }
        watchlistRow = row
        watchlistSignature = signature
    }

    /** 8.3.0 (Nova): where a fresh watchlist row belongs —
     * right under the "On now" row when it is showing, else at
     * the very top of the home screen. */
    private fun watchlistInsertIndex(): Int {
        val onNowIndex = onNowRow?.let { rowsAdapter.indexOf(it) } ?: -1
        return if (onNowIndex >= 0) onNowIndex + 1 else 0
    }

    /**
     * 9.2.0 (Nova): the "More of what you watch" entries.
     * 9.2.1 (Nova): the selection itself moved to [Discovery],
     * shared with the mobile home section — this is just the
     * fragment's guard + delegation. See Discovery for the
     * rules (heaviest watched groups, not-recently-watched
     * channels, what's on now).
     */
    private fun buildMoreEntries(): List<OnNowEntry> {
        val epgMap = homeEpg ?: return emptyList()
        if (homeChannels.isEmpty() || !isAdded) return emptyList()
        return Discovery.moreEntries(requireContext(), homeChannels, epgMap)
    }

    /**
     * 9.2.0 (Nova): insert, update or hide the "More of what you
     * watch" row, under the watchlist row (or under "On now", or
     * at the top). Mirrors refreshOnNowRow, including the 8.2.1
     * in-place rebind when the programme set is unchanged, so
     * the cards' progress and minutes-left advance on the ticker
     * without a rebuild under the focus. The cards are plain
     * [OnNowEntry]s, so the existing presenter and click
     * handling (tap plays the channel) apply unchanged.
     */
    private fun refreshMoreRow() {
        if (!isAdded || homeEpg == null) return
        val entries = buildMoreEntries()
        val signature = entries.joinToString("|") {
            it.channel.id + "@" + it.programme.startMillis
        }
        if (entries.isEmpty()) {
            moreRow?.let { rowsAdapter.remove(it) }
            moreRow = null
            moreSignature = ""
            return
        }
        if (signature == moreSignature && moreRow != null) {
            val rowAdapter = moreRow?.adapter as? ArrayObjectAdapter
            if (rowAdapter != null && rowAdapter.size() > 0) {
                rowAdapter.notifyArrayItemRangeChanged(0, rowAdapter.size())
            }
            return
        }
        val adapter = ArrayObjectAdapter(OnNowPresenter())
        entries.forEach { adapter.add(it) }
        val row = ListRow(HeaderItem(getString(R.string.more_of_what_you_watch)), adapter)
        val existing = moreRow
        if (existing != null) {
            val index = rowsAdapter.indexOf(existing)
            if (index >= 0) {
                rowsAdapter.remove(existing)
                rowsAdapter.add(index, row)
            } else {
                rowsAdapter.add(moreInsertIndex(), row)
            }
        } else {
            rowsAdapter.add(moreInsertIndex(), row)
        }
        moreRow = row
        moreSignature = signature
    }

    /** 9.2.0 (Nova): where a fresh discovery row belongs —
     * under the watchlist row when it is showing, else under
     * "On now", else at the top. */
    private fun moreInsertIndex(): Int {
        val watchlistIndex = watchlistRow?.let { rowsAdapter.indexOf(it) } ?: -1
        if (watchlistIndex >= 0) return watchlistIndex + 1
        val onNowIndex = onNowRow?.let { rowsAdapter.indexOf(it) } ?: -1
        return if (onNowIndex >= 0) onNowIndex + 1 else 0
    }

    private companion object {
        /** 8.2.0 (Nova): how often the "On now" row re-checks the
         * EPG while the home screen sits open. */
        const val ON_NOW_TICK_MS = 60_000L

    }
}
