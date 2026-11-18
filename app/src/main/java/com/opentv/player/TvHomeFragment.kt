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
 * 9.0.0 — TV home screen: the browse-first home the TV opens on
 * instead of auto-playing. Favorites rail, Recent channels rail,
 * one rail per group, then the OpenTV actions row (Guide /
 * Recordings / Statistics / Settings / Refresh) — the same rows
 * the mobile home shows, built by the shared HomeRails builder so
 * the two stay in sync.
 *
 * Combine: the rails shell is Juniper's; Nova's home rows ride on
 * top of it — "On now" (favorites + what's on), "Watchlist"
 * (programme reminders), "More of what you watch" (discovery) and
 * "Continue watching" (recordings with a resume point) — plus her
 * home clock. The row logic mirrors BrowseFragment's (same
 * builders, same tickers, same insert order); BrowseFragment
 * itself is untouched, it stays Nova's.
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

    // Combine: the playlist and EPG the home screen last loaded,
    // for Nova's rows (same state BrowseFragment keeps).
    private var homeChannels: List<Channel> = emptyList()
    private var homeEpg: Map<String, List<EpgParser.Programme>>? = null
    private var onNowRow: ListRow? = null
    private var onNowSignature: String = ""
    private var watchlistRow: ListRow? = null
    private var watchlistSignature: String = ""
    private var moreRow: ListRow? = null
    private var moreSignature: String = ""
    private var continueRow: ListRow? = null
    private var continueSignature: String = ""
    private val onNowHandler = Handler(Looper.getMainLooper())
    private val onNowTicker = object : Runnable {
        override fun run() {
            refreshOnNowRow()
            refreshWatchlistRow()
            refreshMoreRow()
            onNowHandler.postDelayed(this, ON_NOW_TICK_MS)
        }
    }

    // Combine (Nova 10.0.0): the home clock — a time + date label
    // pinned to the top end of the home screen, opposite the
    // "OpenTV" title. Its ticker re-arms to the next minute
    // boundary, so the minute flips when the wall clock does.
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
                // Combine (Nova 8.3.0/8.3.1): a watchlist card whose
                // programme is on plays its channel; a future one
                // opens the guide landed on its channel.
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
                // Combine (Nova 9.0.0): a Continue watching card
                // plays its recording (PlaybackActivity resumes it).
                is ContinueWatchingEntry -> playRecording(item.recording)
                is MenuAction -> onMenuAction(item)
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupHomeClock(view)
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
        // Combine: the smart rows refresh on resume (a reminder
        // set in the guide a moment ago shows up here without
        // waiting for the ticker), and the tickers keep them honest
        // while the screen sits open.
        refreshOnNowRow()
        refreshWatchlistRow()
        refreshMoreRow()
        refreshContinueRow()
        onNowHandler.removeCallbacks(onNowTicker)
        onNowHandler.postDelayed(onNowTicker, ON_NOW_TICK_MS)
        updateHomeClock()
        clockHandler.removeCallbacks(clockTicker)
        clockHandler.postDelayed(clockTicker, millisToNextMinute())
    }

    override fun onPause() {
        onNowHandler.removeCallbacks(onNowTicker)
        clockHandler.removeCallbacks(clockTicker)
        super.onPause()
    }

    /** Combine (Nova 10.0.0): pin the home clock to the top end of
     * the browse root (a frame, so the label rides on top of it),
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

    private fun playRecording(recording: Recording) {
        val intent = Intent(requireContext(), PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_RECORDING_ID, recording.id)
        intent.putExtra(PlaybackActivity.EXTRA_RECORDING_URI, recording.uri)
        intent.putExtra(PlaybackActivity.EXTRA_RECORDING_NAME, recording.channelName)
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
                homeChannels = channels
                buildRows(channels)
                title = "OpenTV"
                // Combine (Nova 8.2.0): the EPG loads behind the
                // rows — the home screen paints as fast as it
                // always did, and the "On now" row fills in when
                // the guide data lands (loadEpg is single-flight
                // with the copy playback and the guide use, so this
                // costs no second download).
                val epgMap = runCatching {
                    PlaylistRepository.loadEpg(requireContext())
                }.getOrNull()
                if (epgMap != null) {
                    homeEpg = epgMap
                    refreshOnNowRow()
                    // Combine (Nova 9.2.0): the discovery row needs
                    // the EPG too (each card shows what's on now).
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
        hasContent = channels.isNotEmpty()
        homeChannels = channels
        // Combine: the adapter was just emptied — the tracked smart
        // rows went with it. The refresh calls at the end of this
        // function re-add the ones that have anything to show
        // (the "On now" row waits for the EPG instead).
        onNowRow = null
        onNowSignature = ""
        watchlistRow = null
        watchlistSignature = ""
        moreRow = null
        moreSignature = ""
        continueRow = null
        continueSignature = ""

        val context = requireContext()
        // 9.1.1: the high-contrast focus setting reaches the home
        // rails here too.
        val highContrast = AppSettings.highContrastFocus(context)
        val favoritesTitle = getString(R.string.favorites)
        val rails = HomeRails.buildRails(
            channels = channels,
            favorites = PlaylistRepository.favorites(context),
            recentIds = PlaylistRepository.recentChannelIds(context),
            hidden = PlaylistRepository.hiddenChannels(context),
            overrides = PlaylistRepository.groupOverrides(context),
            favoritesTitle = favoritesTitle,
            recentsTitle = getString(R.string.home_recent_channels)
        )
        for (rail in rails) {
            // Combine (Nova 9.1.0): the user's custom favorites
            // order, once one is set (playlist order until then).
            val railChannels = if (rail.title == favoritesTitle) {
                PlaylistRepository.applyFavoriteOrder(context, rail.channels)
            } else {
                rail.channels
            }
            val railAdapter = ArrayObjectAdapter(ChannelPresenter(highContrast))
            railChannels.forEach { railAdapter.add(it) }
            rowsAdapter.add(ListRow(HeaderItem(rail.title), railAdapter))
        }

        val actionsAdapter = ArrayObjectAdapter(ActionPresenter(highContrast))
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

        // Combine: the smart rows go in at the top, in order —
        // each positions itself under the one above it.
        refreshWatchlistRow()
        refreshMoreRow()
        refreshContinueRow()
    }

    /**
     * Combine (Nova 8.2.0): the "On now" entries — every favorite
     * channel (hidden ones skipped, exactly like the Favorites
     * rail; in the user's custom favorites order since 9.1.0)
     * paired with the programme on it right now. Channels whose
     * EPG has nothing real on at the moment drop out; if none
     * survive, the row hides.
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
     * Combine (Nova 8.2.0/8.2.1): insert, update or hide the "On
     * now" row at the top of the home screen. An unchanged
     * programme set never rebuilds the row out from under the
     * focus — instead the visible cards rebind in place, so the
     * progress bar and minutes-left text advance with the ticker
     * while focus stays put.
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
     * Combine (Nova 8.3.0): the watchlist entries — the pending
     * programme reminders (the 8.0.0 store reads soonest-first and
     * prunes programmes that already ended), each resolved to its
     * channel. A reminder whose channel has left the playlist, or
     * is hidden like in every other home row, drops out.
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
     * Combine (Nova 8.3.0/9.3.0): insert, update or hide the
     * "Watchlist" row, directly under "On now" (or at the top
     * while that row is away). A programme starting flips its
     * signature (the ":now" marker), and a reminder added or
     * ended changes the set — both rebuild the row. An unchanged
     * signature rebinds in place so the countdown text advances
     * on the ticker without a rebuild under the focus.
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

    /** Combine (Nova 8.3.0): where a fresh watchlist row belongs —
     * right under the "On now" row when it is showing, else at the
     * very top of the home screen. */
    private fun watchlistInsertIndex(): Int {
        val onNowIndex = onNowRow?.let { rowsAdapter.indexOf(it) } ?: -1
        return if (onNowIndex >= 0) onNowIndex + 1 else 0
    }

    /**
     * Combine (Nova 9.2.0/9.2.1): the "More of what you watch"
     * entries — the selection itself lives in [Discovery], shared
     * with the mobile home section (heaviest watched groups,
     * not-recently-watched channels, what's on now).
     */
    private fun buildMoreEntries(): List<OnNowEntry> {
        val epgMap = homeEpg ?: return emptyList()
        if (homeChannels.isEmpty() || !isAdded) return emptyList()
        return Discovery.moreEntries(requireContext(), homeChannels, epgMap)
    }

    /**
     * Combine (Nova 9.2.0): insert, update or hide the "More of
     * what you watch" row, under the watchlist row (or under "On
     * now", or at the top). Mirrors refreshOnNowRow, including the
     * in-place rebind when the programme set is unchanged.
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

    /** Combine (Nova 9.2.0): where a fresh discovery row belongs —
     * under the watchlist row when it is showing, else under "On
     * now", else at the top. */
    private fun moreInsertIndex(): Int {
        val watchlistIndex = watchlistRow?.let { rowsAdapter.indexOf(it) } ?: -1
        if (watchlistIndex >= 0) return watchlistIndex + 1
        val onNowIndex = onNowRow?.let { rowsAdapter.indexOf(it) } ?: -1
        return if (onNowIndex >= 0) onNowIndex + 1 else 0
    }

    /**
     * Combine (Nova 9.0.0): the "Continue watching" entries —
     * recordings whose last viewing stopped inside the resume
     * window, most recently watched first (the store's own order).
     * Joining from the progress side means a deleted recording's
     * leftover entry simply finds no recording and drops out of
     * the row on its own. (On the recordings screen this row
     * already exists; here it is the same data on the home.)
     */
    private fun buildContinueEntries(): List<ContinueWatchingEntry> {
        if (!isAdded) return emptyList()
        val context = requireContext()
        val byId = RecordingManager.listRecordings(context).associateBy { it.id }
        return PlaylistRepository.recordingProgress(context)
            .filter { it.isResumable() }
            .mapNotNull { progress ->
                byId[progress.recordingId]?.let { ContinueWatchingEntry(it, progress) }
            }
    }

    /**
     * Combine (Nova 9.0.0): insert, update or hide the "Continue
     * watching" row, under the discovery row (or the watchlist,
     * or "On now", or at the top). Unlike the EPG rows it has no
     * ticker — recording progress only changes while a recording
     * plays, so resume + rebuild cover it.
     */
    private fun refreshContinueRow() {
        if (!isAdded) return
        val entries = buildContinueEntries()
        val signature = entries.joinToString("|") {
            it.recording.id + "@" + it.progress.positionMs
        }
        if (entries.isEmpty()) {
            continueRow?.let { rowsAdapter.remove(it) }
            continueRow = null
            continueSignature = ""
            return
        }
        if (signature == continueSignature && continueRow != null) {
            val rowAdapter = continueRow?.adapter as? ArrayObjectAdapter
            if (rowAdapter != null && rowAdapter.size() > 0) {
                rowAdapter.notifyArrayItemRangeChanged(0, rowAdapter.size())
            }
            return
        }
        val adapter = ArrayObjectAdapter(ContinueWatchingPresenter())
        entries.forEach { adapter.add(it) }
        val row = ListRow(HeaderItem(getString(R.string.continue_watching)), adapter)
        val existing = continueRow
        if (existing != null) {
            val index = rowsAdapter.indexOf(existing)
            if (index >= 0) {
                rowsAdapter.remove(existing)
                rowsAdapter.add(index, row)
            } else {
                rowsAdapter.add(continueInsertIndex(), row)
            }
        } else {
            rowsAdapter.add(continueInsertIndex(), row)
        }
        continueRow = row
        continueSignature = signature
    }

    /** Combine: where a fresh Continue watching row belongs —
     * under the discovery row when it is showing, else under the
     * watchlist, else under "On now", else at the top. */
    private fun continueInsertIndex(): Int {
        val moreIndex = moreRow?.let { rowsAdapter.indexOf(it) } ?: -1
        if (moreIndex >= 0) return moreIndex + 1
        val watchlistIndex = watchlistRow?.let { rowsAdapter.indexOf(it) } ?: -1
        if (watchlistIndex >= 0) return watchlistIndex + 1
        val onNowIndex = onNowRow?.let { rowsAdapter.indexOf(it) } ?: -1
        return if (onNowIndex >= 0) onNowIndex + 1 else 0
    }

    private companion object {
        /** Combine (Nova 8.2.0): how often the "On now" row
         * re-checks the EPG while the home screen sits open. */
        const val ON_NOW_TICK_MS = 60_000L
    }
}
