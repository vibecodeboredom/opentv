package com.opentv.player

import android.app.AlertDialog
import android.app.Dialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The TV guide, rebuilt from scratch (v4.0.1) to match the TiviMate
 * reference photo exactly:
 * - Big details header: preview box with the channel logo, programme
 *   title, time range, progress bar with minutes left, favorite star,
 *   and the channel's category. It follows the focused programme.
 * - Grid: channel numbers in blue, logos, and names down the left;
 *   30-minute time columns across the top with the date + current time;
 *   flat programme cells showing just the title. The focused cell turns
 *   white with dark text, and its channel name turns blue. Red
 *   current-time line.
 * - Long-press OK on a programme opens the context menu (search, settings,
 *   external player, record, custom recording, favorites, block/hide,
 *   assign EPG, channel options). Search lives in that menu, not in the
 *   guide itself.
 * - Left sidebar (4.6.2, shared ChannelSidebar helper): pressing Left
 *   when focus is on the first programme card of a row opens the same
 *   groups/channels panel as playback; picking a channel starts
 *   playback of it.
 *
 * 6.1.2: the window reaches 4 hours back from the top of the current
 * hour (10 hours total), so past programmes are visible — with the
 * ⏪ marker and catch-up playback where the playlist supports it.
 * The guide opens on the channel playing underneath (instead of the
 * first row), and the horizontal scroller starts with "now" in view.
 * All three are settings (default on) — see AppSettings.
 */
class GuideActivity : FragmentActivity() {

    private lateinit var container: LinearLayout
    private lateinit var loading: ProgressBar
    private lateinit var scroll: ScrollView
    /** 6.1.2: the horizontal scroller for the programme grid — the
     * guide scrolls it on open so "now" is in view once the window
     * reaches back into the past. */
    private lateinit var hscroll: HorizontalScrollView
    private lateinit var nowLine: View
    private lateinit var emptyView: TextView
    /** The frame the now-line spans — cached (4.9.5) instead of
     * re-finding it on every row append. */
    private lateinit var guideFrame: FrameLayout
    /** 6.0.0-rc1: details header + preview box, resized on mobile so
     * the header fits a phone screen. */
    private lateinit var guideHeader: LinearLayout
    private lateinit var guidePreviewBox: FrameLayout

    private lateinit var detailLogo: ImageView
    private lateinit var detailChannel: TextView
    private lateinit var detailTitle: TextView
    private lateinit var detailStar: TextView
    private lateinit var detailTime: TextView
    private lateinit var detailProgress: ProgressBar
    private lateinit var detailRemaining: TextView
    private lateinit var detailCategory: TextView

    private var allRows: List<GuideData.GuideRow> = emptyList()
    private var allChannels: List<Channel> = emptyList()
    private var epg: Map<String, List<EpgParser.Programme>> = emptyMap()
    private var windowStart: Long = 0L
    /** 6.1.2: the window's total span in hours — WINDOW_HOURS forward
     * plus the past hours when past programming is on (10 by default).
     * Set in onCreate from the setting; every span computation uses
     * this, never the WINDOW_HOURS constant directly. */
    private var windowHours: Int = WINDOW_HOURS
    /** 6.1.2: how many hours of past the window covers (0 when the
     * past-programming setting is off — the old top-of-hour window). */
    private var pastHours: Int = 0
    private var nowMillis: Long = 0L
    private var pxPerMinute: Int = 0
    private var channelColWidth: Int = 0
    /** Pixel values for the dp sizes used in the per-row / per-card
     * loops, computed once in onCreate. dpToPx() runs
     * TypedValue.applyDimension on every call, and a guide build calls
     * it ~5 times per row and twice per programme card (up to 400 rows). */
    private var px3: Int = 0
    private var px4: Int = 0
    private var px12: Int = 0
    private var px16: Int = 0
    private var px40: Int = 0
    /** 6.1.0: row height / margin in px, computed once in onCreate —
     * appendRows and updateRowsWithEpg used to run dpToPx on every
     * call (scroll + focus appends), and TypedValue.applyDimension
     * is not free when it runs dozens of times a second. */
    private var pxCellHeight: Int = 0
    private var pxCellMargin: Int = 0

    /** Pinned channel-name column (4.7.2): sits beside the horizontal
     * scroller, so the names never scroll away sideways. */
    private lateinit var pinnedCol: LinearLayout

    /** Rows currently displayed (allRows, or the search-filtered subset). */
    private var displayRows: List<GuideData.GuideRow> = emptyList()
    /** How many of displayRows have been built so far (one pinned cell
     * + one programme row each). Rows are only built for what's on
     * screen plus a buffer — the grid used to inflate all 400 rows
     * eagerly, which was the open delay and the scroll lag. */
    private var builtRowCount = 0
    /** Programme-row layouts by display index, parallel to channelCells. */
    private val programmeRowLayouts = mutableListOf<LinearLayout>()
    /** Pinned channel-cell views by display index. */
    private val channelCells = mutableListOf<View>()
    /** Current search query ("" = no filter), so the EPG fill-in can
     * respect an active search instead of clobbering it. */
    private var currentSearchQuery: String = ""
    /** Generation counter so a stale background search (an earlier
     * keystroke) never overwrites a newer one. */
    private var searchGeneration = 0
    /** The pending debounced search (4.9.5): cancelled and restarted
     * on every keystroke, so a scan only runs once typing pauses. */
    private var searchJob: Job? = null

    /** First programme on now (and its card), for the details header
     * and the initial focus. Set while rows are built. */
    private var firstNow: Pair<Channel, EpgParser.Programme>? = null
    private var firstNowCard: View? = null
    /** 6.1.2: the current channel's programme on now (and its card).
     * The guide opens on this instead of the first row, so the user
     * lands on the channel they're watching. Set while rows are built,
     * alongside firstNow; null when the current channel has no row
     * (hidden, or not built yet) — callers fall back to firstNow. */
    private var currentChannelNow: Pair<Channel, EpgParser.Programme>? = null
    private var currentChannelNowCard: View? = null

    /** The programme the details header is currently showing. */
    private var detailChannelShown: Channel? = null

    /** Cached favorite ids. updateDetailStar runs on every focus
     * change, and it used to read the favorites set out of
     * SharedPreferences on every one. The set only changes through
     * this activity's own star / menu toggles (which update the
     * cache), so a cached copy is always in sync; it is refreshed
     * on resume as a safety net. */
    private var favoritesCache: Set<String> = emptySet()

    private fun isFavorite(channelId: String): Boolean = channelId in favoritesCache

    /** Id of the channel playing underneath (passed by PlaybackActivity);
     * its number + name are highlighted in the accent colour. */
    private var currentChannelId: String? = null

    /** Left sidebar (4.6.2, shared ChannelSidebar helper) — the same
     * groups/channels panel as playback, over the guide grid. */
    private lateinit var channelSidebar: ChannelSidebar

    /** Channel name views per row, so focus can turn the name blue. */
    private val channelNameViews = mutableMapOf<String, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_guide)
        currentChannelId = intent.getStringExtra(EXTRA_CURRENT_CHANNEL_ID)
        container = findViewById(R.id.guide_container)
        pinnedCol = findViewById(R.id.guide_pinned_col)
        loading = findViewById(R.id.guide_loading)
        scroll = findViewById(R.id.guide_scroll)
        hscroll = findViewById(R.id.guide_hscroll)
        nowLine = findViewById(R.id.guide_now_line)
        guideFrame = findViewById(R.id.guide_frame)
        guideHeader = findViewById(R.id.guide_header)
        guidePreviewBox = findViewById(R.id.guide_preview_box)
        emptyView = findViewById(R.id.guide_empty)

        // Lazy rows (4.7.2): append more when the user scrolls near the
        // bottom of what's built, so the grid never holds more than a
        // screenful + a buffer of live views.
        scroll.setOnScrollChangeListener { _, _, _, _, _ -> maybeAppendRows() }

        detailLogo = findViewById(R.id.guide_detail_logo)
        detailChannel = findViewById(R.id.guide_detail_channel)
        detailTitle = findViewById(R.id.guide_detail_title)
        detailStar = findViewById(R.id.guide_detail_star)
        detailTime = findViewById(R.id.guide_detail_time)
        detailProgress = findViewById(R.id.guide_detail_progress)
        detailRemaining = findViewById(R.id.guide_detail_remaining)
        detailCategory = findViewById(R.id.guide_detail_category)

        // Left sidebar (shared ChannelSidebar helper, 4.6.2). Picking a
        // channel here starts playback of it, like tapping a programme
        // that is on now. The scrim closes the sidebar on tap.
        channelSidebar = ChannelSidebar(
            activity = this,
            scope = lifecycleScope,
            scrim = findViewById(R.id.guide_sidebar_scrim),
            panel = findViewById(R.id.guide_sidebar),
            title = findViewById(R.id.guide_sidebar_title),
            subtitle = findViewById(R.id.guide_sidebar_subtitle),
            list = findViewById(R.id.guide_sidebar_scroll),
            footer = findViewById(R.id.guide_sidebar_footer),
            currentChannelId = { currentChannelId },
            onChannelSelected = { playChannel(it) },
            sizing = AppSettings.sidebarSizing(this)
        )
        findViewById<View>(R.id.guide_sidebar_scrim).setOnClickListener {
            channelSidebar.hide()
        }

        pxPerMinute = dpToPx(PX_PER_MINUTE_DP)
        channelColWidth = dpToPx(CHANNEL_COL_DP)
        px3 = dpToPx(3)
        px4 = dpToPx(4)
        px12 = dpToPx(12)
        px16 = dpToPx(16)
        px40 = dpToPx(40)
        pxCellHeight = dpToPx(CELL_HEIGHT_DP)
        pxCellMargin = dpToPx(CELL_MARGIN_DP)
        favoritesCache = PlaylistRepository.favorites(this)

        // 6.0.0-rc1: the TV grid is built for a wide screen (320dp
        // channel column, 180dp details header with a 300dp preview
        // box) — on a phone that leaves no room for the programmes.
        // Narrow the pinned column to 160dp and shrink the header to
        // 120dp with a 120dp preview box. The grid itself keeps its
        // 4dp/minute scale (a 30-minute show stays readable) and
        // scrolls horizontally by touch, as before. Cards, long-press
        // menu, and the sidebar already work with touch — no other
        // guide change was needed.
        if (DeviceType.isMobile(this)) {
            channelColWidth = dpToPx(MOBILE_CHANNEL_COL_DP)
            pinnedCol.layoutParams.width = channelColWidth
            guideHeader.layoutParams.height = dpToPx(MOBILE_HEADER_HEIGHT_DP)
            guidePreviewBox.layoutParams.width = dpToPx(MOBILE_PREVIEW_WIDTH_DP)
        }

        // The star is a touch target only — it must never steal DPAD
        // focus, or the guide opens with nothing in the grid focused
        // and a long-press OK lands on the star instead of a programme.
        detailStar.isFocusable = false
        detailStar.isFocusableInTouchMode = false

        detailStar.setOnClickListener {
            val ch = detailChannelShown ?: return@setOnClickListener
            PlaylistRepository.toggleFavorite(this, ch.id)
            favoritesCache = PlaylistRepository.favorites(this)
            updateDetailStar(ch)
            val added = isFavorite(ch.id)
            Toast.makeText(
                this,
                if (added) R.string.menu_added_favorite else R.string.menu_removed_favorite,
                Toast.LENGTH_SHORT
            ).show()
        }

        // Playlist and EPG load in parallel, and the guide renders as
        // soon as the playlist is ready — channels show with "No
        // information" placeholders, and the real programme titles fill
        // in when the EPG arrives. Waiting for the (slow) EPG before
        // showing anything was why the guide sat on its spinner.
        lifecycleScope.launch {
            val playlistDeferred = async { PlaylistRepository.loadPlaylist(this@GuideActivity) }
            val epgDeferred = async { PlaylistRepository.loadEpg(this@GuideActivity) }
            val playlistResult = playlistDeferred.await()
            // 4.8.1 — build ONCE when the EPG is already ready. The EPG
            // is usually in memory (playback loaded it at startup), so
            // the old code built the grid with placeholders and then —
            // because the EPG await returned immediately — ran the
            // fill-in in the SAME main-thread slice, before the first
            // frame drew. The guide did two full row computations and
            // built every programme card twice before anything
            // appeared. Wait a beat for the EPG (capped, so a cold
            // start still paints placeholders fast) and build once
            // with real data when it's there.
            val epgReady = withTimeoutOrNull(EPG_WAIT_MS) { epgDeferred.await() }
            if (epgReady != null) epg = epgReady
            loading.visibility = View.GONE
            val channels = playlistResult.getOrNull()
            if (channels == null) {
                Toast.makeText(this@GuideActivity, R.string.load_failed, Toast.LENGTH_LONG).show()
                finish()
                return@launch
            }
            nowMillis = System.currentTimeMillis()
            // 6.1.2: past programming in the guide — the window starts
            // 4 hours before the top of the current hour (10 hours
            // total), so programmes that already aired are visible
            // with the ⏪ marker and catch-up playback. The default
            // EPG reaches ~4.5 hours back, so the 4-hour lookback is
            // real data, not empty cells. Turn it off in Settings to
            // go back to the old top-of-hour window.
            pastHours = if (AppSettings.guideShowsPast(this@GuideActivity)) {
                WINDOW_PAST_HOURS
            } else {
                0
            }
            windowHours = WINDOW_HOURS + pastHours
            windowStart = GuideData.windowStartMillis(nowMillis) -
                pastHours * 60L * 60 * 1000
            allChannels = channels
            rebuildRows()
            if (allRows.isEmpty()) {
                Toast.makeText(
                    this@GuideActivity,
                    R.string.no_guide_data,
                    Toast.LENGTH_LONG
                ).show()
                finish()
                return@launch
            }
            buildGuide(allRows)
            scroll.visibility = View.VISIBLE
            // Don't auto-scroll: the channel column (numbers + names)
            // stays pinned at the left, like the reference photo.
            // The red now-line marks the current time; the user scrolls
            // right to see later programmes.
            // EPG not in hand yet: fill it in when it lands — posted
            // to the next frame, so the first paint (placeholders)
            // happens BEFORE the fill-in work, not after it.
            if (epg.isEmpty()) {
                lifecycleScope.launch {
                    val arrived = epgDeferred.await()
                    if (arrived.isNotEmpty() && !isDestroyed && !isFinishing) {
                        epg = arrived
                        scroll.post {
                            if (!isDestroyed && !isFinishing) {
                                lifecycleScope.launch { updateRowsWithEpg() }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Safety net for the favorites cache (see the field comment).
        favoritesCache = PlaylistRepository.favorites(this)
    }

    /**
     * Key handling for the left sidebar (4.6.2). Everything else uses
     * the default focus navigation so programme-cell clicks and the
     * long-press context menu keep working untouched:
     * - Sidebar up: Left → back (channels → groups → close); Right / OK
     *   → activate; Up/Down → move within the sidebar (the grid behind
     *   it is also focusable, so framework focus search could jump out);
     *   BACK → back/close. OK is handled on key-UP, mirroring playback,
     *   and the key-DOWN is consumed so the row's click doesn't also fire.
     * - Sidebar down + Left: opens the sidebar, but only when focus is
     *   on the first programme card of its row (the left edge) — anywhere
     *   else, Left keeps moving between programme cells as before.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sidebarUp = channelSidebar.isVisible()
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    // Consume the press so the focused sidebar row's
                    // click doesn't also fire; activate() runs on key-UP.
                    if (sidebarUp) return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (sidebarUp) {
                        channelSidebar.back()
                        return true
                    }
                    val focused = currentFocus
                    // A channel cell is tagged with its row index (an
                    // Int); programme cards are tagged with their
                    // Programme — so the tag alone says which one has
                    // focus, without a linear scan of channelCells on
                    // every Left press.
                    if (focused != null && focused.tag is Int) {
                        // Left from the channel name cell opens the
                        // sidebar (4.7.2).
                        channelSidebar.show()
                        return true
                    }
                    if (focused != null && isFirstCardInRow(focused)) {
                        // Left from the first programme card moves focus
                        // to the channel name cell (4.7.2) instead of
                        // the sidebar — the sidebar opens on the next
                        // Left, from the name cell.
                        val rowLayout = focused.parent as? LinearLayout
                        val idx = rowLayout?.tag as? Int
                        val cell = idx?.let { channelCells.getOrNull(it) }
                        if (cell != null) {
                            cell.requestFocus()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    if (sidebarUp) {
                        channelSidebar.activate()
                        return true
                    }
                    val focused = currentFocus
                    // Channel cell = Int tag (see the Left branch).
                    if (focused != null && focused.tag is Int) {
                        // Right from the channel name moves focus to the
                        // row's first programme card (4.7.2).
                        val idx = focused.tag as? Int
                        val card = idx?.let { firstCardOfRow(it) }
                        if (card != null) {
                            card.requestFocus()
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                    if (sidebarUp) {
                        if (channelSidebar.moveFocus(event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
                            return true
                        }
                    }
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (sidebarUp) {
                        channelSidebar.back()
                        return true
                    }
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (sidebarUp) {
                        channelSidebar.activate()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** True when this view is the first programme card of its guide row
     * (the left edge) — pressing Left there opens the sidebar. Cards
     * are tagged with their programme in populateProgrammeRow; the channel
     * cell and spacers carry no tag. */
    private fun isFirstCardInRow(view: View): Boolean {
        val row = view.parent as? LinearLayout ?: return false
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.tag is EpgParser.Programme) {
                return child === view
            }
        }
        return false
    }

    /** Start playback of a channel picked in the sidebar (same as
     * tapping a programme that is on now). */
    private fun playChannel(ch: Channel) {
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, ch)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    /**
     * Recompute the guide rows for the current channels + EPG.
     * Pure computation (GuideData is Android-free), so it runs on
     * Dispatchers.Default — the open path used to do this on the main
     * thread, twice per open (placeholders, then real EPG), before the
     * first paint. The prefs reads are captured on the main thread;
     * the filtering/sorting runs in the background.
     */
    private suspend fun rebuildRows() {
        val channels = allChannels
        val epgMap = epg
        val hidden = PlaylistRepository.hiddenChannels(this)
        val overrides = PlaylistRepository.epgOverrides(this)
        allRows = withContext(Dispatchers.Default) {
            GuideData.buildRows(
                channels, epgMap, windowStart, windowHours, hidden, overrides
            )
        }
    }

    /**
     * Search the guide. Empty query clears the filter. Otherwise the
     * whole playlist + full EPG is searched (4.9.0, GuideData.searchRows):
     * channel-name matches first, then programme-title matches, with
     * forgiving word/partial matching. The old version only filtered
     * the rows already on screen (capped at 400 channels, 6-hour
     * window), so a channel past the first 400 — or a show airing
     * later — never appeared even though it was in the playlist.
     * Results are built as normal guide rows, so matches beyond the
     * displayed set show up in the grid. The scan runs on a background
     * thread (a full EPG can be tens of thousands of programmes) and
     * a generation counter drops stale results when the user keeps
     * typing.
     *
     * 4.9.5 — debounced: the scan used to run on EVERY keystroke, so
     * typing a 10-character query queued up to 10 full playlist + EPG
     * scans (the generation counter only dropped the results, the
     * scans still ran to completion). Now the pending scan is
     * cancelled on each keystroke and only runs once typing pauses
     * for SEARCH_DEBOUNCE_MS. Clearing the search is still instant.
     */
    private fun applySearch(query: String) {
        currentSearchQuery = query
        searchJob?.cancel()
        if (query.trim().isEmpty()) {
            buildGuide(allRows)
            emptyView.visibility = View.GONE
            scroll.visibility = View.VISIBLE
            return
        }
        val channels = allChannels
        val epgMap = epg
        val hidden = PlaylistRepository.hiddenChannels(this)
        val overrides = PlaylistRepository.epgOverrides(this)
        val start = windowStart
        searchJob = lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            val generation = ++searchGeneration
            val filtered = withContext(Dispatchers.Default) {
                GuideData.searchRows(
                    channels, epgMap, start, windowHours, hidden, overrides, query
                )
            }
            if (generation != searchGeneration || currentSearchQuery != query) return@launch
            if (filtered.isEmpty()) {
                container.removeAllViews()
                pinnedCol.removeAllViews()
                displayRows = emptyList()
                builtRowCount = 0
                scroll.visibility = View.GONE
                emptyView.visibility = View.VISIBLE
            } else {
                emptyView.visibility = View.GONE
                scroll.visibility = View.VISIBLE
                buildGuide(filtered)
            }
        }
    }

    /** Title colour for a programme cell (5.0.0): "now" is dark on the
     * accent background, plain past cells are muted grey, catch-up
     * cells are brighter than past ones — they can be played — and
     * future cells are white. */
    private fun programmeTitleColor(isNow: Boolean, isPast: Boolean, isCatchup: Boolean): Int =
        when {
            isNow -> 0xFF080808.toInt()
            isCatchup -> 0xFFC9C9D1.toInt()
            isPast -> 0xFF6B6B75.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    /** Show a channel + programme in the details header. The
     * channel-specific fields (name, category, logo) are only set
     * when the channel actually changed — focus moves between
     * programmes of the SAME channel far more often than between
     * channels, and the old code re-set those views and re-issued a
     * Coil logo load on every single focus move. */
    private var detailLogoUrl: String? = null

    private fun showDetails(channel: Channel, programme: EpgParser.Programme) {
        val channelChanged = detailChannelShown?.id != channel.id
        detailChannelShown = channel
        // 5.0.0: a catch-up programme gets the ⏪ marker here too.
        val isCatchup = programme.stopMillis <= nowMillis &&
            CatchUp.isAvailable(channel, programme, nowMillis)
        detailTitle.text =
            if (isCatchup) "⏪ ${programme.title}" else programme.title
        detailTime.text = GuideData.formatTimeRange(programme.startMillis, programme.stopMillis)
        if (channelChanged) {
            detailChannel.text = if (channel.number > 0) {
                "${channel.number}  ${channel.name}"
            } else {
                channel.name
            }
            detailCategory.text = channel.group
            // Skip re-loading the same logo — but only for a real URL:
            // a null logo still loads every time (Coil shows the
            // placeholder), exactly as before. 6.1.0: downsampled to
            // the 96dp preview box.
            val logoUrl = channel.logo
            if (logoUrl == null || logoUrl != detailLogoUrl) {
                detailLogoUrl = logoUrl
                detailLogo.loadLogo(
                    logoUrl, 96, android.R.drawable.ic_media_play
                )
            }
        }
        val isNow = nowMillis in programme.startMillis until programme.stopMillis
        if (isNow) {
            detailProgress.progress =
                (GuideData.progressFraction(programme, nowMillis) * 100).toInt()
            detailRemaining.text = getString(
                R.string.guide_min_left,
                GuideData.remainingMinutes(programme, nowMillis)
            )
            detailProgress.visibility = View.VISIBLE
            detailRemaining.visibility = View.VISIBLE
        } else {
            detailProgress.visibility = View.INVISIBLE
            detailRemaining.visibility = View.INVISIBLE
        }
        updateDetailStar(channel)
    }

    private fun updateDetailStar(channel: Channel) {
        val isFav = isFavorite(channel.id)
        detailStar.text = if (isFav) "★" else "☆"
        detailStar.setTextColor(
            if (isFav) 0xFFFFC107.toInt() else 0xFF7A7A85.toInt()
        )
    }

    /**
     * Builds the guide grid — lazily (4.7.2). Only the first screenful
     * of rows is built synchronously, so the guide appears and is usable
     * almost immediately after pressing OK; more rows are appended as
     * the user scrolls down (maybeAppendRows) or focus nears the end.
     * The grid used to inflate all 400 rows eagerly in 40-row chunks,
     * which was the open delay — and it did it twice per open (once for
     * the placeholders, once when the EPG arrived). A generation counter
     * drops stale now-line refreshes when a newer build starts (search,
     * block/hide, EPG fill-in).
     */
    private var guideBuildGeneration = 0

    private fun buildGuide(rows: List<GuideData.GuideRow>) {
        guideBuildGeneration++
        displayRows = rows
        builtRowCount = 0
        programmeRowLayouts.clear()
        channelCells.clear()
        firstNow = null
        firstNowCard = null
        currentChannelNow = null
        currentChannelNowCard = null
        container.removeAllViews()
        pinnedCol.removeAllViews()
        channelNameViews.clear()
        nowLine.visibility = View.GONE

        buildGuideHeader(pxCellHeight)
        // 4.8.1: build only the first screenful synchronously — the
        // first paint was blocked by inflating all 24 rows, and only
        // ~6 fit on screen at once. The rest of the initial rows, plus
        // the details header + focus, follow on the next frame.
        appendRows(GUIDE_FIRST_PAINT_ROWS)
        val generation = guideBuildGeneration
        container.post {
            if (generation != guideBuildGeneration) return@post
            appendRows(GUIDE_INITIAL_ROWS - GUIDE_FIRST_PAINT_ROWS)
            finishGuideBuild()
        }
    }

    /** Build the next chunk of display rows (one pinned channel cell +
     * one programme row each) and append them. */
    private fun appendRows(count: Int = GUIDE_APPEND_ROWS) {
        if (builtRowCount >= displayRows.size) return
        val windowEnd = windowStart + windowHours * 60L * 60 * 1000
        val cellHeight = pxCellHeight
        val cellMargin = pxCellMargin
        val end = minOf(builtRowCount + count, displayRows.size)
        while (builtRowCount < end) {
            buildRowViews(
                displayRows[builtRowCount], builtRowCount,
                windowEnd, cellHeight, cellMargin
            )
            builtRowCount++
        }
        refreshNowLine()
    }

    /** Append more rows when the user scrolls near the bottom of what's
     * built, so focus never runs out of rows below. */
    private fun maybeAppendRows() {
        if (builtRowCount >= displayRows.size) return
        val contentHeight = scroll.getChildAt(0)?.height ?: return
        if (contentHeight <= 0) return
        if (scroll.scrollY + scroll.height >= contentHeight - 2 * scroll.height) {
            appendRows()
        }
    }

    /** The time header, split in two (4.7.2): the date cell is pinned
     * at the top of the channel column, the 30-minute labels scroll
     * horizontally with the programme grid. Both are one cell tall so
     * the columns stay aligned. */
    private fun buildGuideHeader(cellHeight: Int) {
        pinnedCol.addView(TextView(this).apply {
            text = GuideData.formatDateTime(nowMillis)
            setBackgroundColor(0xFF161616.toInt())
            setTextColor(0xFFC9C9D1.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px12, 0, px12, 0)
            layoutParams = LinearLayout.LayoutParams(channelColWidth, cellHeight)
        })
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(0xFF161616.toInt())
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        for (m in 0 until windowHours * 60 step 30) {
            val label = TextView(this).apply {
                text = GuideData.formatHourLabel(windowStart + m * 60L * 1000)
                setTextColor(0xFF9A9AA3.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    (pxPerMinute * 30).toInt(), cellHeight
                )
            }
            header.addView(label)
        }
        container.addView(header)
    }

    /** One channel row (4.7.2): the channel cell goes in the pinned
     * column, the programme cards go in the scrolling grid. The two
     * stay aligned because both get the same height + top margin and
     * both live in the same vertical ScrollView. */
    private fun buildRowViews(
        row: GuideData.GuideRow,
        index: Int,
        windowEnd: Long,
        cellHeight: Int,
        cellMargin: Int
    ) {
        // Channel cell: number + logo + name (no group line, like the photo).
        // 4.6.0 palette: #161616 panel; the current channel's number and
        // name are accent (#86C739), the rest 70% white / white.
        val isCurrentChannel = row.channel.id == currentChannelId
        val channelCell = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF161616.toInt())
            setPadding(px12, 0, px12, 0)
            // Focusable (4.7.2): Left from the first programme card
            // lands here, and Left from here opens the sidebar — so
            // the channel name is always reachable from the grid.
            isFocusable = true
            isFocusableInTouchMode = true
            // Tagged with its row index, so Left/Right can find the
            // row's programme cards and vice versa.
            tag = index
            layoutParams = LinearLayout.LayoutParams(channelColWidth, cellHeight).apply {
                topMargin = cellMargin
            }
        }
        if (row.channel.number > 0) {
            channelCell.addView(TextView(this).apply {
                text = row.channel.number.toString()
                setTextColor(
                    if (isCurrentChannel) 0xFF86C739.toInt() else 0xB3FFFFFF.toInt()
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    px40,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
            })
        }
        val logo = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(px40, px40).apply {
                marginEnd = px12
            }
            // 6.1.0: downsampled to the 40dp cell instead of the
            // provider's full-resolution logo.
            loadLogo(row.channel.logo, 40, android.R.drawable.ic_media_play)
        }
        channelCell.addView(logo)
        val nameView = TextView(this).apply {
            text = row.channel.name
            setTextColor(if (isCurrentChannel) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        channelCell.addView(nameView)
        channelNameViews[row.channel.id] = nameView
        channelCell.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                // Focused name turns accent, with a subtle panel
                // highlight, and the details header shows this row's
                // programme that's on now.
                nameView.setTextColor(0xFF86C739.toInt())
                channelCell.setBackgroundColor(0xFF232629.toInt())
                val current = displayRows.getOrNull(index)
                val nowProg = current?.programmes?.firstOrNull {
                    nowMillis in it.startMillis until it.stopMillis
                } ?: current?.programmes?.firstOrNull()
                if (current != null && nowProg != null) showDetails(current.channel, nowProg)
            } else {
                nameView.setTextColor(
                    if (isCurrentChannel) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt()
                )
                channelCell.setBackgroundColor(0xFF161616.toInt())
            }
        }
        pinnedCol.addView(channelCell)
        channelCells.add(channelCell)

        // Programme row: cards only (the channel cell lives in the
        // pinned column now). Tagged with its row index.
        val rowLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            tag = index
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = cellMargin }
        }
        populateProgrammeRow(rowLayout, row, windowEnd, cellHeight, cellMargin, nameView)
        container.addView(rowLayout)
        programmeRowLayouts.add(rowLayout)
    }

    /** Fill (or re-fill) a programme row with its spacer + card views.
     * Used for the initial build and for the in-place EPG fill-in
     * (updateRowsWithEpg), which swaps the placeholder cards for the
     * real ones without touching the channel cell or the row itself. */
    private fun populateProgrammeRow(
        rowLayout: LinearLayout,
        row: GuideData.GuideRow,
        windowEnd: Long,
        cellHeight: Int,
        cellMargin: Int,
        nameView: TextView
    ) {
        rowLayout.removeAllViews()
        val rowIndex = rowLayout.tag as? Int ?: -1
        val isCurrentChannel = row.channel.id == currentChannelId
        // Programme cells (title only, like the photo), with spacers for gaps.
        var posMinutes = 0L
        for (programme in row.programmes) {
            val layout = GuideData.layoutCell(programme, windowStart, windowEnd)
            if (layout.startOffsetMinutes > posMinutes) {
                rowLayout.addView(View(this).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        (pxPerMinute * (layout.startOffsetMinutes - posMinutes)).toInt(),
                        cellHeight
                    )
                })
            }
            val isNow = nowMillis in programme.startMillis until programme.stopMillis
            val isPast = programme.stopMillis <= nowMillis
            // 5.0.0 — catch-up: a past programme on a catch-up channel
            // can be replayed. It gets a ⏪ prefix and a brighter title
            // than a plain past cell, so it's obvious it's playable.
            val isCatchup = isPast &&
                CatchUp.isAvailable(row.channel, programme, nowMillis)
            if (isNow && firstNow == null) firstNow = row.channel to programme
            // 6.1.2: the current channel's now programme — the guide
            // opens on this row instead of the first one. Set here
            // alongside firstNow, so it exists whenever the row is
            // built (including placeholder "No information" rows).
            if (isNow && isCurrentChannel && currentChannelNow == null) {
                currentChannelNow = row.channel to programme
            }
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px12, px4, px12, px4)
                isFocusable = true
                isFocusableInTouchMode = true
                // Tagged with its programme so the sidebar's left-edge
                // check (isFirstCardInRow) can find the first card.
                tag = programme
                setBackgroundResource(
                    when {
                        isNow -> R.drawable.guide_cell_now_bg
                        isPast -> R.drawable.guide_cell_past_bg
                        else -> R.drawable.guide_cell_bg
                    }
                )
                layoutParams = LinearLayout.LayoutParams(
                    (pxPerMinute * layout.widthMinutes).toInt() - cellMargin * 2,
                    cellHeight
                ).apply {
                    leftMargin = cellMargin
                    rightMargin = cellMargin
                }
            }
            val titleView = TextView(this).apply {
                text = if (isCatchup) "⏪ ${programme.title}" else programme.title
                // "Now" cells sit on the accent background, so their text
                // is dark for contrast; past cells are muted grey, except
                // catch-up ones, which are brighter — they can be played.
                setTextColor(programmeTitleColor(isNow, isPast, isCatchup))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            card.addView(titleView)
            card.setOnClickListener { onProgrammeClicked(row.channel, programme) }
            card.setOnLongClickListener {
                showContextMenu(row.channel, programme)
                true
            }
            card.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    // Focused cell turns white with dark text; its
                    // channel name turns accent (4.6.0 palette).
                    titleView.setTextColor(0xFF121218.toInt())
                    nameView.setTextColor(0xFF86C739.toInt())
                    showDetails(row.channel, programme)
                    // Focus nearing the last built row: append more
                    // rows so Down never runs out of grid below.
                    appendRowsIfNearEnd(rowIndex)
                } else {
                    titleView.setTextColor(programmeTitleColor(isNow, isPast, isCatchup))
                    // The current channel stays accent; the rest go white.
                    nameView.setTextColor(
                        if (isCurrentChannel) 0xFF86C739.toInt() else 0xFFFFFFFF.toInt()
                    )
                }
                // 5.0.0 — neat animations: the focused cell pops
                // slightly (150ms, transform only, so the grid layout
                // never moves). Quick enough to never feel slow.
                card.animate().scaleX(if (hasFocus) 1.03f else 1f)
                    .scaleY(if (hasFocus) 1.03f else 1f)
                    .setDuration(150).start()
            }
            rowLayout.addView(card)
            if (isNow && firstNowCard == null) firstNowCard = card
            if (isNow && isCurrentChannel && currentChannelNowCard == null) {
                currentChannelNowCard = card
            }
            posMinutes = layout.startOffsetMinutes + layout.widthMinutes
        }
    }

    /** Append the next chunk when focus reaches the last few built
     * rows, so DPAD Down never runs out of grid below (the scroll
     * listener usually appends first; this is the safety net). */
    private fun appendRowsIfNearEnd(rowIndex: Int) {
        if (rowIndex >= 0 && rowIndex >= builtRowCount - GUIDE_FOCUS_BUFFER) {
            appendRows()
        }
    }

    /**
     * Fill in the real EPG data without tearing the grid down (4.7.2).
     * Only the programme cards of the rows already built are swapped
     * in place; the channel cells, focus, and scroll position are
     * untouched. Falls back to a full (now lazy, fast) rebuild when a
     * search is active or the channel list itself changed.
     */
    private suspend fun updateRowsWithEpg() {
        rebuildRows()
        val sameChannels = displayRows.size == allRows.size &&
            displayRows.indices.all { displayRows[it].channel.id == allRows[it].channel.id }
        if (currentSearchQuery.isNotEmpty() || !sameChannels) {
            applySearch(currentSearchQuery)
            return
        }
        // Remember the focused programme so focus survives the swap.
        // (A focused channel cell isn't touched, so it needs nothing.)
        val focused = currentFocus
        val focusedProg = focused?.tag as? EpgParser.Programme
        val focusedChannelId = if (focusedProg != null) {
            val rowLayout = focused.parent as? LinearLayout
            (rowLayout?.tag as? Int)?.let { displayRows.getOrNull(it)?.channel?.id }
        } else null
        firstNow = null
        firstNowCard = null
        currentChannelNow = null
        currentChannelNowCard = null
        val windowEnd = windowStart + windowHours * 60L * 60 * 1000
        val cellHeight = pxCellHeight
        val cellMargin = pxCellMargin
        for (i in 0 until builtRowCount) {
            val row = allRows[i]
            val nameView = channelNameViews[row.channel.id] ?: continue
            populateProgrammeRow(
                programmeRowLayouts[i], row, windowEnd, cellHeight, cellMargin, nameView
            )
        }
        displayRows = allRows
        // Restore focus: the programme airing at the same time on the
        // same channel (the old card was a placeholder, so an exact
        // start-time match usually won't exist), else the current
        // channel's now card (6.1.2), else the first now card.
        var restored = false
        if (focusedProg != null && focusedChannelId != null) {
            restored = focusProgrammeAt(focusedChannelId, focusedProg.startMillis)
        }
        if (!restored) {
            val fallbackCard = if (AppSettings.guideOpensOnCurrentChannel(this)) {
                currentChannelNowCard ?: firstNowCard
            } else {
                firstNowCard
            }
            fallbackCard?.let { it.requestFocus() }
        }
        refreshNowLine()
    }

    /** Focus the card airing at [timeMillis] on the given channel, if
     * that row is built. Returns false when nothing matched. */
    private fun focusProgrammeAt(channelId: String, timeMillis: Long): Boolean {
        val idx = displayRows.indexOfFirst { it.channel.id == channelId }
        if (idx < 0 || idx >= builtRowCount) return false
        val rowLayout = programmeRowLayouts[idx]
        for (i in 0 until rowLayout.childCount) {
            val card = rowLayout.getChildAt(i)
            val prog = card.tag as? EpgParser.Programme ?: continue
            if (timeMillis in prog.startMillis until prog.stopMillis) {
                card.requestFocus()
                return true
            }
        }
        return false
    }

    /** First programme card of a built row, or null. */
    private fun firstCardOfRow(index: Int): View? {
        val rowLayout = programmeRowLayouts.getOrNull(index) ?: return null
        for (i in 0 until rowLayout.childCount) {
            val child = rowLayout.getChildAt(i)
            if (child.tag is EpgParser.Programme) return child
        }
        return null
    }

    /** After the first rows are in: details header, focus, and the now-line. */
    private fun finishGuideBuild() {
        // 6.1.2: land on the channel playing underneath instead of the
        // first row — the user was tired of scrolling to find it. The
        // guide only builds the first screenful eagerly, so if the
        // current channel sits further down, build rows up to its row
        // first (same work scrolling would do, once, on open). Falls
        // back to the first row when the setting is off, the channel
        // has no row (hidden), or the guide was opened with no
        // current channel.
        if (AppSettings.guideOpensOnCurrentChannel(this)) {
            val idx = displayRows.indexOfFirst { it.channel.id == currentChannelId }
            if (idx >= 0 && idx >= builtRowCount) {
                appendRows(idx + 1 - builtRowCount)
            }
        }
        // Show the current channel's now programme in the details
        // header when we have it, else the first programme on now.
        (currentChannelNow ?: firstNow)?.let { (ch, prog) -> showDetails(ch, prog) }

        // Focus the current channel's now card when we have it, else
        // the first now card — so the guide opens with a cell focused
        // and a long-press OK lands on a programme (the side menu),
        // not on the header star.
        val focusCard = if (AppSettings.guideOpensOnCurrentChannel(this)) {
            currentChannelNowCard ?: firstNowCard
        } else {
            firstNowCard
        }
        focusCard?.let { card ->
            container.post {
                card.requestFocus()
                // 6.1.2: with past programming in the window, "now"
                // sits hours into the grid — scroll the horizontal
                // scroller so the now-line is in view with ~30 minutes
                // of past to its left (scroll left for more). Posted
                // after the focus so it wins over the framework's
                // minimal focus scroll.
                if (pastHours > 0) {
                    val nowX = ((nowMillis - windowStart) / 60_000L * pxPerMinute).toInt()
                    val targetX = maxOf(0, nowX - PAST_VISIBLE_MINUTES * pxPerMinute)
                    hscroll.post { hscroll.scrollTo(targetX, 0) }
                }
            }
        }

        // Position the red now-line at the current time, spanning all rows.
        // (The frame now starts at the programme area, so there's no
        // channel-column offset any more — 4.7.2.)
        val nowOffsetMinutes = (nowMillis - windowStart) / 60_000L
        nowLine.translationX = ((nowOffsetMinutes * pxPerMinute).toInt()).toFloat()
        nowLine.visibility = View.VISIBLE
        refreshNowLine()
    }

    /** Keep the red now-line spanning the whole grid. The frame grows as
     * rows are appended, so the line's height is re-set after each
     * append (and once when the guide first opens). Stale posts from an
     * older build are dropped via the generation counter. The height is
     * only re-set when it actually changed — the old code built a new
     * LayoutParams and forced a layout pass on every append even when
     * the frame hadn't grown. */
    private var nowLineHeight = 0

    private fun refreshNowLine(attempts: Int = 0) {
        val generation = guideBuildGeneration
        container.post {
            if (generation != guideBuildGeneration) return@post
            if (nowLine.visibility != View.VISIBLE) return@post
            val h = guideFrame.height
            if (h > 0) {
                if (h != nowLineHeight) {
                    nowLineHeight = h
                    nowLine.layoutParams = FrameLayout.LayoutParams(px3, h)
                    nowLine.requestLayout()
                }
            } else if (attempts < 3) {
                // Not laid out yet — try again after the next pass.
                refreshNowLine(attempts + 1)
            }
        }
    }

    private fun onProgrammeClicked(channel: Channel, programme: EpgParser.Programme) {
        if (nowMillis in programme.startMillis until programme.stopMillis) {
            // CLEAR_TOP replaces the playback underneath instead of
            // stacking a second player on top of it.
            val intent = Intent(this, PlaybackActivity::class.java)
            intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, channel)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
        } else if (programme.stopMillis <= nowMillis) {
            // 5.0.0 — catch-up: a past programme on a catch-up channel
            // replays from the guide. Anything else past just says so.
            val url = CatchUp.catchupUrl(channel, programme, nowMillis)
            if (url != null) {
                playCatchup(channel, programme, url)
            } else {
                val whenText = GuideData.formatHourLabel(programme.startMillis)
                Toast.makeText(
                    this,
                    "${programme.title} — ${getString(R.string.starts_at, whenText)}",
                    Toast.LENGTH_SHORT
                ).show()
            }
        } else {
            val whenText = GuideData.formatHourLabel(programme.startMillis)
            Toast.makeText(
                this,
                "${programme.title} — ${getString(R.string.starts_at, whenText)}",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 5.0.0: start playback of a past programme's catch-up stream. */
    private fun playCatchup(channel: Channel, programme: EpgParser.Programme, url: String) {
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, channel)
        intent.putExtra(PlaybackActivity.EXTRA_CATCHUP_URL, url)
        intent.putExtra(PlaybackActivity.EXTRA_CATCHUP_TITLE, programme.title)
        intent.putExtra(PlaybackActivity.EXTRA_CATCHUP_START_MS, programme.startMillis)
        intent.putExtra(PlaybackActivity.EXTRA_CATCHUP_STOP_MS, programme.stopMillis)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
    }

    /**
     * The context menu (long-press OK on a programme), TiviMate style:
     * a rounded panel with search, settings, and programme/channel actions.
     */
    private fun showContextMenu(channel: Channel, programme: EpgParser.Programme) {
        val dialog = Dialog(this)
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        // Don't pop the keyboard up on open — the search field is there
        // when the user wants it, but it shouldn't cover the menu.
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        // Side panel, not a centered popup: dock to the right edge,
        // full height, like TiviMate's menu. 5.0.0: it slides in from
        // the right edge (and back out) instead of the default dialog
        // fade / slide-up.
        dialog.window?.setGravity(Gravity.END)
        dialog.window?.setWindowAnimations(R.style.GuideMenuAnimation)
        val view = layoutInflater.inflate(R.layout.dialog_guide_menu, null)
        dialog.setContentView(view)
        // 6.0.0-rc1: the menu content is 480dp wide — wider than a
        // phone screen. On mobile it spans the full width instead.
        dialog.window?.setLayout(
            if (DeviceType.isMobile(this)) ViewGroup.LayoutParams.MATCH_PARENT
            else ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val menuSearch = view.findViewById<EditText>(R.id.menu_search)
        val recentContainer = view.findViewById<LinearLayout>(R.id.menu_recent_container)
        val recentList = view.findViewById<LinearLayout>(R.id.menu_recent_list)
        // Recent searches (4.9.3): shown while the field is empty, so a
        // past query can be re-run without typing. Hidden as soon as the
        // user starts typing, so the menu doesn't jump around. Loaded
        // once for this menu — the old code re-read the list out of
        // SharedPreferences on every keystroke (the visibility check
        // runs in afterTextChanged).
        val recents = PlaylistRepository.recentSearches(this).toMutableList()
        val updateRecentVisibility = {
            recentContainer.visibility =
                if (menuSearch.text.isEmpty() && recents.isNotEmpty()
                ) View.VISIBLE else View.GONE
        }
        menuSearch.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                applySearch(s?.toString().orEmpty())
                updateRecentVisibility()
            }
        })
        menuSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // Submitted (Enter) — this is the moment a query is
                // remembered, not on every keystroke.
                PlaylistRepository.saveRecentSearch(this, menuSearch.text.toString())
                dialog.dismiss()
                true
            } else false
        }
        populateRecentSearches(recents, recentList, menuSearch, dialog, updateRecentVisibility)
        view.findViewById<TextView>(R.id.menu_clear_recent).setOnClickListener {
            PlaylistRepository.clearRecentSearches(this)
            recents.clear()
            recentList.removeAllViews()
            updateRecentVisibility()
        }
        updateRecentVisibility()

        view.findViewById<TextView>(R.id.menu_settings).setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        view.findViewById<TextView>(R.id.menu_programme_title).text = programme.title

        view.findViewById<TextView>(R.id.menu_external_player).setOnClickListener {
            dialog.dismiss()
            openExternalPlayer(channel)
        }

        val recordRow = view.findViewById<TextView>(R.id.menu_record)
        val isRecording = RecordingManager.isRecording(this, channel.id)
        recordRow.text = getString(
            if (isRecording) R.string.menu_stop_recording else R.string.menu_record
        )
        recordRow.setOnClickListener {
            dialog.dismiss()
            if (RecordingManager.isRecording(this, channel.id)) {
                RecordingManager.stopRecording(this, channel.id)
                Toast.makeText(this, R.string.recording_stopped, Toast.LENGTH_SHORT).show()
            } else {
                RecordingManager.startRecording(this, channel)
                Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
            }
        }

        view.findViewById<TextView>(R.id.menu_custom_recording).setOnClickListener {
            dialog.dismiss()
            showCustomRecordingPicker(channel)
        }

        view.findViewById<TextView>(R.id.menu_channel_name).text =
            if (channel.number > 0) "${channel.number}  ${channel.name}" else channel.name

        val favoriteRow = view.findViewById<TextView>(R.id.menu_favorite)
        val isFav = isFavorite(channel.id)
        favoriteRow.text = getString(
            if (isFav) R.string.menu_remove_favorite else R.string.menu_add_favorite
        )
        favoriteRow.setOnClickListener {
            dialog.dismiss()
            PlaylistRepository.toggleFavorite(this, channel.id)
            favoritesCache = PlaylistRepository.favorites(this)
            val added = isFavorite(channel.id)
            Toast.makeText(
                this,
                if (added) R.string.menu_added_favorite else R.string.menu_removed_favorite,
                Toast.LENGTH_SHORT
            ).show()
            detailChannelShown?.let { updateDetailStar(it) }
        }

        view.findViewById<TextView>(R.id.menu_block).setOnClickListener {
            dialog.dismiss()
            PlaylistRepository.hideChannel(this, channel.id)
            Toast.makeText(this, R.string.menu_channel_blocked, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                rebuildRows()
                buildGuide(allRows)
            }
        }

        view.findViewById<TextView>(R.id.menu_hide).setOnClickListener {
            dialog.dismiss()
            PlaylistRepository.hideChannel(this, channel.id)
            Toast.makeText(this, R.string.menu_channel_hidden, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                rebuildRows()
                buildGuide(allRows)
            }
        }

        view.findViewById<TextView>(R.id.menu_assign_epg).setOnClickListener {
            dialog.dismiss()
            showAssignEpgDialog(channel)
        }

        view.findViewById<TextView>(R.id.menu_channel_options).setOnClickListener {
            dialog.dismiss()
            showChannelOptions(channel)
        }

        dialog.show()
    }

    /**
     * Fill the recent-searches list under the menu's search field
     * (4.9.3). One row per past query (most recent first, at most
     * MAX_RECENT_SEARCHES_SHOWN, so the full-height menu never
     * overflows the screen). Rows use the same focusable menu-row
     * background as the other actions (accent when focused). Tapping
     * one runs that search immediately — setText fires the TextWatcher,
     * so applySearch runs — moves it to the top of the list, and
     * closes the menu onto the results.
     */
    private fun populateRecentSearches(
        recents: List<String>,
        recentList: LinearLayout,
        menuSearch: EditText,
        dialog: Dialog,
        updateRecentVisibility: () -> Unit
    ) {
        recentList.removeAllViews()
        val shown = recents.take(MAX_RECENT_SEARCHES_SHOWN)
        for (query in shown) {
            recentList.addView(TextView(this).apply {
                text = query
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(px16, px12, px16, px12)
                setBackgroundResource(R.drawable.guide_menu_item_bg)
                isFocusable = true
                isFocusableInTouchMode = true
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setOnClickListener {
                    menuSearch.setText(query)
                    PlaylistRepository.saveRecentSearch(this@GuideActivity, query)
                    dialog.dismiss()
                }
            })
        }
        updateRecentVisibility()
    }

    private fun openExternalPlayer(channel: Channel) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(channel.url), "video/*")
            }
            startActivity(Intent.createChooser(intent, channel.name))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.menu_no_external_player, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showCustomRecordingPicker(channel: Channel) {
        val minutes = intArrayOf(15, 30, 60, 120)
        val labels = minutes.map { "$it min" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.menu_recording_duration)
            .setItems(labels) { _, which ->
                val duration = minutes[which]
                RecordingManager.startRecording(this, channel)
                RecordingManager.scheduleStopRecording(
                    this, channel.id,
                    System.currentTimeMillis() + duration * 60_000L
                )
                Toast.makeText(
                    this,
                    getString(R.string.menu_recording_for, duration),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showAssignEpgDialog(channel: Channel) {
        val input = EditText(this).apply {
            setText(PlaylistRepository.epgOverrides(this@GuideActivity)[channel.id]
                ?: channel.tvgId.orEmpty())
            hint = getString(R.string.menu_assign_epg_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(px16, px12, px16, px12)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_assign_epg_title, channel.name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlaylistRepository.setEpgOverride(this, channel.id, input.text.toString())
                Toast.makeText(this, R.string.menu_epg_assigned, Toast.LENGTH_SHORT).show()
                lifecycleScope.launch {
                    rebuildRows()
                    buildGuide(allRows)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showChannelOptions(channel: Channel) {
        val details = getString(
            R.string.menu_channel_details,
            channel.number,
            channel.name,
            channel.group,
            channel.tvgId ?: "—"
        )
        AlertDialog.Builder(this)
            .setTitle(channel.name)
            .setMessage(details)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()

    companion object {
        /** Intent extra: id of the channel playing underneath. */
        const val EXTRA_CURRENT_CHANNEL_ID = "current_channel_id"
        private const val WINDOW_HOURS = 6
        /** 6.1.2: hours of past programming the guide window reaches
         * back (10 hours total). The default EPG reaches ~4.5 hours
         * back, so the 4-hour lookback is real data. */
        private const val WINDOW_PAST_HOURS = 4
        /** 6.1.2: minutes of past left visible beside "now" when the
         * guide opens with the past window — scroll left for more. */
        private const val PAST_VISIBLE_MINUTES = 30
        private const val PX_PER_MINUTE_DP = 4
        private const val CHANNEL_COL_DP = 320
        /** Thinner rows (was 72) so more channels fit on one TV screen. */
        private const val CELL_HEIGHT_DP = 56
        private const val CELL_MARGIN_DP = 3
        /** Rows built when the guide first opens (enough for ~3
         * screens), and rows appended per lazy chunk as the user
         * scrolls. The grid used to inflate all 400 rows eagerly. */
        private const val GUIDE_INITIAL_ROWS = 24
        /** Rows built synchronously for the first paint (4.8.1) — only
         * ~6 fit on screen at once, so 12 covers ~2 screens; the rest
         * of the initial rows follow on the next frame. */
        private const val GUIDE_FIRST_PAINT_ROWS = 12
        /** How long the guide waits for the EPG before painting with
         * placeholders (4.8.1). The EPG is usually in memory, so the
         * wait is ~0ms and the guide builds once with real data. */
        private const val EPG_WAIT_MS = 150L
        private const val GUIDE_APPEND_ROWS = 24
        /** Focus reaching this close to the last built row triggers
         * an append, so DPAD Down never runs out of grid below. */
        private const val GUIDE_FOCUS_BUFFER = 6
        /** Recent searches shown in the menu (4.9.3) — fewer than the
         * 8 stored, so the full-height menu never overflows the screen. */
        private const val MAX_RECENT_SEARCHES_SHOWN = 5
        /** Search waits this long after the last keystroke before
         * scanning (4.9.5) — typing used to launch a full
         * playlist + EPG scan on every keystroke. */
        private const val SEARCH_DEBOUNCE_MS = 250L
        /** 6.0.0-rc1: mobile guide sizes — the pinned channel column
         * and details header shrink so the programme grid fits a
         * phone screen (TV keeps CHANNEL_COL_DP / 180dp / 300dp). */
        private const val MOBILE_CHANNEL_COL_DP = 160
        private const val MOBILE_HEADER_HEIGHT_DP = 120
        private const val MOBILE_PREVIEW_WIDTH_DP = 120
    }
}
