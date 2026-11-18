package com.opentv.player

import android.app.AlertDialog
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Plays one channel with ExoPlayer. The media controller is fully
 * disabled — OpenTV draws its own UI instead.
 *
 * 4.6.0 (TiviMate-style) key map — documented here because the remote
 * is the whole interface:
 * - OK (short): sidebar up → activate the focused row; info overlay up →
 *   activate the focused tile; nothing up → open the TV Guide. If playback
 *   is paused and nothing is up, OK resumes instead of opening the guide.
 * - OK (long-press, ~600ms, nothing up): open the context menu (the same
 *   options MENU shows: favorite, record). Implemented with a delayed
 *   runnable: the short-press action fires on key-UP, so a hold never
 *   also triggers the short-press action.
 * - LEFT: sidebar up → back (channels → groups, groups → close); overlay
 *   up → move between strip tiles (only when a strip tile has focus, so
 *   the transport buttons keep normal Left/Right focus movement);
 *   nothing up → open the left sidebar (channels/groups); during a
 *   recording, seek back 10 s instead (7.1.0).
 * - RIGHT: sidebar up → activate the focused row; overlay up → move
 *   between strip tiles (same focus caveat as Left); nothing up → show
 *   the channel info overlay; during a recording, seek forward 10 s
 *   instead (7.1.0).
 * - UP/DOWN: move focus between rows/tiles/buttons (framework focus
 *   search; the sidebar's RecyclerView scrolls to the focused row).
 * - BACK: sidebar up → back/close; overlay up → hide it; else the
 *   system default (leave playback).
 * - Play/pause key (remote): toggle pause/resume. Channel up/down keys:
 *   next / previous channel.
 * - MENU opens options (favorite + record + sleep timer + speed).
 *   INFO toggles favorite; during a recording it shows the info
 *   overlay instead (7.1.0 — Left/Right seek there now).
 *
 * The left sidebar (4.6.2, shared ChannelSidebar helper) is a panel
 * over a 60% black scrim (6.1.2: width from the channel-list size
 * setting — 220dp compact default): groups at the top ("All channels",
 * "Favorites", "History", then the playlist groups), channels below
 * with number + logo + name + the current programme. The current
 * channel is highlighted in the accent (#86C739); the focused row gets
 * a rounded 16dp highlight and its text turns accent. Channel rows
 * are capped at 200 (the footer says so); 6.0.0-rc2 made the list a
 * RecyclerView, so only the visible rows are bound.
 *
 * The player is built in onStart and released in onStop, so leaving
 * for the guide and coming straight back resumes the channel instead
 * of a black screen that never loads.
 *
 * Can also play back a local recording (EXTRA_RECORDING_*): the player is
 * built with the download cache as its data source, so it reads from disk.
 */
@OptIn(UnstableApi::class)
class PlaybackActivity : FragmentActivity() {

    companion object {
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_RECORDING_ID = "recording_id"
        const val EXTRA_RECORDING_URI = "recording_uri"
        const val EXTRA_RECORDING_NAME = "recording_name"
        const val EXTRA_RETRY_COUNT = "retry_count"
        /** 5.0.0 — catch-up: the resolved catch-up URL plus the
         * programme it replays (title + window), from the guide. */
        const val EXTRA_CATCHUP_URL = "catchup_url"
        const val EXTRA_CATCHUP_TITLE = "catchup_title"
        const val EXTRA_CATCHUP_START_MS = "catchup_start_ms"
        const val EXTRA_CATCHUP_STOP_MS = "catchup_stop_ms"
        /** Set when this activity was started by a channel up/down press:
         * the info overlay should flash briefly instead of the normal
         * Left/Right duration. */
        const val EXTRA_SHOW_BRIEF_INFO = "show_brief_info"
        /** 7.1.1: the chosen playback speed, carried across a channel
         * change so it survives a zap instead of resetting to 1×. */
        const val EXTRA_PLAYBACK_SPEED = "playback_speed"
        /** 7.3.0: the chosen audio / subtitle languages, carried
         * across a channel change the same way as the speed. A
         * subtitle choice of "off" rides as its own boolean — a
         * null language alone can't tell "never chosen" from
         * "turned off", and a rebuild must not turn captions back
         * on after the user switched them off. */
        const val EXTRA_AUDIO_LANGUAGE = "audio_language"
        const val EXTRA_SUBTITLE_LANGUAGE = "subtitle_language"
        const val EXTRA_SUBTITLES_OFF = "subtitles_off"
        /** Dead streams are common in the playlist — auto-try another random channel this many times. */
        private const val MAX_AUTO_RETRY = 3
        /** Overlay auto-hides after this long with no key press. */
        private const val OVERLAY_TIMEOUT_MS = 10_000L
        /** 6.0.0-rc3: mobile overlay auto-hide — 5s idle, then it
         * fades away. TV keeps the 10s timeout above. */
        private const val MOBILE_OVERLAY_TIMEOUT_MS = 5_000L
        /** 6.0.0-rc3: mobile overlay fade-out duration. */
        private const val OVERLAY_FADE_MS = 200L
        /** 7.0.0: a typed channel number settles after this long
         * without another digit, and playback jumps to it. */
        private const val NUMBER_ENTRY_TIMEOUT_MS = 1500L
        /** Channel up/down flash: half the Left/Right overlay time, so
         * zapping past a channel shows its info only briefly. */
        private const val CHANNEL_SWITCH_OVERLAY_TIMEOUT_MS = 5_000L
        /** Hold OK this long to open the context menu instead of the guide. */
        private const val LONG_PRESS_MS = 600L
    }

    private var player: ExoPlayer? = null
    private lateinit var playerView: PlayerView
    private lateinit var overlay: View
    private lateinit var infoGroup: TextView
    private lateinit var infoDateTime: TextView
    private lateinit var infoLogo: ImageView
    private lateinit var infoChannelName: TextView
    private lateinit var infoNowTitle: TextView
    private lateinit var infoNowMeta: TextView
    private lateinit var infoNext: TextView
    private lateinit var infoProgress: ProgressBar
    private lateinit var infoStrip: LinearLayout
    /** Bottom transport bar: title + icon-only buttons. */
    private lateinit var transportBar: LinearLayout
    private lateinit var transportTitle: TextView
    private lateinit var transportPlayPause: ImageButton
    private lateinit var transportRecord: ImageButton
    private lateinit var transportFavorite: ImageButton
    /** 6.0.0-rc1: "Channels" button, mobile only — opens the sidebar
     * (there is no Left key on a phone). 6.1.6: part of the
     * auto-hiding overlay group — it shows and fades with the
     * overlay (see showMobileOverlayViews / hideOverlay). */
    private lateinit var channelsButton: TextView
    /** 6.0.0-rc3: mobile-only top "Guide" button + centred channel
     * info. Both are part of the auto-hiding overlay group on
     * mobile: they show and fade with the bottom overlay, and never
     * appear on TV. */
    private lateinit var mobileGuideButton: TextView
    private lateinit var mobileCenterInfo: LinearLayout
    private lateinit var mobileCenterLogo: ImageView
    private lateinit var mobileCenterChannel: TextView
    private lateinit var mobileCenterNow: TextView
    private lateinit var mobileCenterNext: TextView
    /** Centred play icon shown while paused. */
    private lateinit var pausedCluster: ImageView
    /** Left sidebar (4.6.2: shared ChannelSidebar helper). */
    private lateinit var channelSidebar: ChannelSidebar
    /** Auto-hide for the overlay. */
    private val infoHandler = Handler(Looper.getMainLooper())
    private val hideInfoRunnable = Runnable { hideOverlay() }
    /** 7.0.0: channel-number entry — digits typed on the remote,
     * shown in the top pill until the entry settles (timeout or
     * Enter) and playback jumps to that channel. Reuses infoHandler
     * (the overlay's handler) for the settle timeout. */
    private val numberEntry = ChannelNumberEntry()
    private lateinit var numberEntryView: TextView
    private val numberJumpRunnable = Runnable { jumpToChannelNumber() }
    /** 7.0.0: Enter confirmed a number jump on key-DOWN — the
     * matching key-UP must be swallowed too, or the normal OK action
     * (open the guide) would also fire. */
    private var numberEnterConsumed = false
    /** 7.1.0: sleep timer — the chosen minutes until playback
     * stops, or null when off. A dedicated handler so the timer
     * survives onStop (leaving for the guide must not cancel it);
     * it is cancelled in onDestroy, when the session really ends. */
    private var sleepTimerMinutes: Int? = null
    private val sleepHandler = Handler(Looper.getMainLooper())
    private val sleepTimerRunnable = Runnable {
        sleepTimerMinutes = null
        // Dismiss any options dialog first — finishing with one
        // up leaks its window (review catch, 7.1.0).
        currentDialog?.dismiss()
        currentDialog = null
        Toast.makeText(this, R.string.sleep_timer_ended, Toast.LENGTH_LONG).show()
        player?.pause()
        finish()
    }
    /** 7.1.0: the options dialog currently on screen (menu, sleep
     * picker or speed picker), if any — the sleep timer dismisses
     * it before finishing the activity. */
    private var currentDialog: AlertDialog? = null
    /** 7.1.0: playback speed for live streams (1× default).
     * Re-applied whenever the player is rebuilt (returning from
     * the guide); a channel change starts a new activity, so it
     * resets to 1× there. */
    private var playbackSpeed: Float = 1f
    /** 7.3.0: the chosen audio / subtitle languages (null = Auto
     * for audio, nothing chosen for subtitles). Remembered so a
     * player rebuild re-applies them when the tracks reload, and
     * carried across a channel change like the speed. */
    private var selectedAudioLanguage: String? = null
    private var selectedSubtitleLanguage: String? = null
    /** 7.3.0: true once the user has turned subtitles off. A null
     * selectedSubtitleLanguage alone can't tell "never chosen"
     * from "turned off" — and a rebuilt player defaults to
     * auto-selecting a subtitle, so the off state needs its own
     * flag or a rebuild would turn captions back on. */
    private var subtitlesOff: Boolean = false
    /** 7.3.0: guard so re-applying a track choice inside
     * onTracksChanged never loops on its own callback. */
    private var reapplyingTracks = false
    /** True while the overlay up is a channel up/down flash (short
     * timeout); the first Left/Right press upgrades it to the normal
     * Left/Right timeout instead. */
    private var overlayBrief = false
    /** Long-press OK: what it does is a setting (9.3.0) — the
     * playback menu, the guide, or nothing at all. */
    private val longPressRunnable = Runnable {
        longPressFired = true
        // 9.3.0: the held-OK action. MENU is today's behaviour;
        // NOTHING swallows the hold (the key-UP still does nothing
        // because longPressFired is set — the short-press action
        // must not fire after a hold that did nothing).
        when (AppSettings.longPressOkAction(this)) {
            AppSettings.LongPressOkAction.GUIDE -> openGuide()
            AppSettings.LongPressOkAction.NOTHING -> Unit
            AppSettings.LongPressOkAction.MENU -> showOptions()
        }
    }
    private var longPressFired = false
    /** The recents the strip was last built with, so an unchanged list
     * doesn't rebuild the whole strip a second time. */
    private var lastStripRecents: List<String> = emptyList()
    /** Whether the strip was last built in catch-up mode (5.0.0): the
     * Go-live tile appears/disappears with it, so a mode change must
     * rebuild even when the recents are unchanged. */
    private var lastStripCatchup: Boolean = false
    /** Channel id -> Channel for the strip's recents (4.9.5): the map
     * used to be rebuilt from the whole playlist on every overlay
     * open. It is rebuilt only when the playlist list itself changes
     * (cachedPlaylist returns the same instance while fresh). */
    private var stripByIdPlaylist: List<Channel>? = null
    private var stripById: Map<String, Channel> = emptyMap()
    /** Sidebar / overlay state captured when OK goes down, so the
     * short-press action on key-UP uses the state at press time. */
    private var okDownSidebarUp = false
    private var okDownOverlayUp = false
    /** Now / next programmes, filled once EPG loads. */
    private var nowProgramme: EpgParser.Programme? = null
    private var nextProgramme: EpgParser.Programme? = null
    /** Live now / next from the EPG, kept separately (5.0.0): a
     * catch-up session shows the replayed programme as "now" and the
     * live programme as "next", and needs the live pair to fall back
     * to when catch-up fails or the user goes live. */
    private var liveNow: EpgParser.Programme? = null
    private var liveNext: EpgParser.Programme? = null
    /** 5.0.0 — catch-up session state (all null = live playback). */
    private var catchupUrl: String? = null
    private var catchupTitle: String? = null
    private var catchupStartMillis: Long = 0L
    private var catchupStopMillis: Long = 0L
    /** True once a failed catch-up has already fallen back to live. */
    private var catchupFallbackTried = false
    /** Cached favorite ids (see GuideActivity — same pattern). The
     * transport icons are drawn from a fresh read inside
     * updateTransportIcons (6.1.8), so the star always shows the
     * live state — the cache alone can go stale when the favorite
     * changes from the guide, the sidebar or mobile home. Still
     * refreshed on resume and after every toggle made here, for
     * the other readers (options menu, INFO key). */
    private var favoritesCache: Set<String> = emptySet()
    /** Last logo URL loaded into the info overlay — showChannelInfo
     * re-runs when the EPG lands, and the old code re-issued a Coil
     * load of the same logo on every one. */
    private var infoLogoUrl: String? = null
    /** The strip tiles, in order, for left/right focus movement. */
    private val stripTiles = mutableListOf<View>()
    private var channel: Channel? = null
    private var recordingId: String? = null
    private var recordingUri: String? = null
    private var recordingName: String? = null
    private var sessionStartMillis: Long = 0L
    /** Guard so a rapid double OK press doesn't stack two guides. */
    private var guideLaunched = false
    /** True once this stream actually started playing. */
    private var everPlayed = false
    private var retryCount: Int = 0
    private var autoRetrying = false

    private val dateTimeFormat = SimpleDateFormat("EEE, MMM d, h:mm a", Locale.US)
    private val timeFormat = SimpleDateFormat("h:mm a", Locale.US)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_playback)
        playerView = findViewById(R.id.player_view)
        overlay = findViewById(R.id.channel_info)
        infoGroup = findViewById(R.id.info_group)
        infoDateTime = findViewById(R.id.info_datetime)
        infoLogo = findViewById(R.id.info_logo)
        infoChannelName = findViewById(R.id.info_channel_name)
        infoNowTitle = findViewById(R.id.info_now_title)
        infoNowMeta = findViewById(R.id.info_now_meta)
        infoNext = findViewById(R.id.info_next)
        infoProgress = findViewById(R.id.info_progress)
        infoStrip = findViewById(R.id.info_strip)
        transportBar = findViewById(R.id.transport_bar)
        transportTitle = findViewById(R.id.transport_title)
        transportPlayPause = findViewById(R.id.transport_play_pause)
        transportRecord = findViewById(R.id.transport_record)
        transportFavorite = findViewById(R.id.transport_favorite)
        pausedCluster = findViewById(R.id.paused_cluster)
        numberEntryView = findViewById(R.id.playback_number_entry)
        channelsButton = findViewById(R.id.mobile_channels_button)
        // 6.0.0-rc3: mobile-only overlay group (top Guide button +
        // centred channel info). Bound here but only ever shown on
        // mobile — TV never touches them.
        mobileGuideButton = findViewById(R.id.mobile_guide_button)
        mobileCenterInfo = findViewById(R.id.mobile_center_info)
        mobileCenterLogo = findViewById(R.id.mobile_center_logo)
        mobileCenterChannel = findViewById(R.id.mobile_center_channel)
        mobileCenterNow = findViewById(R.id.mobile_center_now)
        mobileCenterNext = findViewById(R.id.mobile_center_next)
        // Left sidebar (shared ChannelSidebar helper, 4.6.2).
        channelSidebar = ChannelSidebar(
            activity = this,
            scope = lifecycleScope,
            scrim = findViewById(R.id.sidebar_scrim),
            panel = findViewById(R.id.sidebar),
            title = findViewById(R.id.sidebar_title),
            subtitle = findViewById(R.id.sidebar_subtitle),
            list = findViewById(R.id.sidebar_scroll),
            footer = findViewById(R.id.sidebar_footer),
            currentChannelId = { channel?.id },
            onChannelSelected = { switchToChannel(it) },
            sizing = AppSettings.sidebarSizing(this)
        )
        // No media controller at all — the video just plays. The overlay
        // and sidebar are OpenTV's own UI.
        playerView.useController = false

        val ch = intent.getSerializableExtra(EXTRA_CHANNEL) as? Channel
        recordingId = intent.getStringExtra(EXTRA_RECORDING_ID)
        recordingUri = intent.getStringExtra(EXTRA_RECORDING_URI)
        recordingName = intent.getStringExtra(EXTRA_RECORDING_NAME)
        // 5.0.0 — catch-up: a resolved replay URL + its programme.
        catchupUrl = intent.getStringExtra(EXTRA_CATCHUP_URL)
        catchupTitle = intent.getStringExtra(EXTRA_CATCHUP_TITLE)
        catchupStartMillis = intent.getLongExtra(EXTRA_CATCHUP_START_MS, 0L)
        catchupStopMillis = intent.getLongExtra(EXTRA_CATCHUP_STOP_MS, 0L)
        retryCount = intent.getIntExtra(EXTRA_RETRY_COUNT, 0)
        // 7.1.1: a channel change within a viewing session carries
        // the chosen playback speed along, so it survives a zap
        // instead of resetting to 1×. A fresh launch has no extra
        // and starts at 1×. The value is checked against the offered
        // speeds — a stray float from an old intent is ignored.
        val carriedSpeed = intent.getFloatExtra(EXTRA_PLAYBACK_SPEED, 1f)
        if (carriedSpeed in PlaybackComfort.SPEEDS) playbackSpeed = carriedSpeed
        // 7.3.0: a channel change within a viewing session carries
        // the chosen audio / subtitle languages along, like the
        // speed. A fresh launch has no extras and starts on Auto
        // audio with subtitles unset.
        intent.getStringExtra(EXTRA_AUDIO_LANGUAGE)?.takeIf { it.isNotBlank() }?.let {
            selectedAudioLanguage = it
        }
        intent.getStringExtra(EXTRA_SUBTITLE_LANGUAGE)?.takeIf { it.isNotBlank() }?.let {
            selectedSubtitleLanguage = it
        }
        subtitlesOff = intent.getBooleanExtra(EXTRA_SUBTITLES_OFF, false)
        // Started by a channel up/down press: flash the info overlay
        // briefly once the EPG is in, instead of the now/next toast.
        val briefInfo = intent.getBooleanExtra(EXTRA_SHOW_BRIEF_INFO, false)

        if (ch == null && recordingUri == null) {
            finish()
            return
        }
        channel = ch
        // 7.3.1: per-channel persistence — a stored choice for this
        // channel wins over the carried session choice; a channel
        // with nothing stored keeps the carried choice (or Auto /
        // Off when there is none).
        ch?.id?.let { id ->
            val stored = TrackPrefs.load(this, id)
            if (stored.audioSet) selectedAudioLanguage = stored.audioLanguage
            if (stored.subtitleSet) {
                subtitlesOff = stored.subtitleLanguage == null
                selectedSubtitleLanguage = stored.subtitleLanguage
            }
        }

        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)

        // Transport buttons (bottom bar). Play/pause toggles playback;
        // prev/next switch channel; record and favorite are the same
        // actions the MENU options dialog offers.
        findViewById<ImageButton>(R.id.transport_prev).setOnClickListener {
            switchToAdjacentChannel(-1)
        }
        transportPlayPause.setOnClickListener { togglePlayPause() }
        findViewById<ImageButton>(R.id.transport_next).setOnClickListener {
            switchToAdjacentChannel(1)
        }
        transportRecord.setOnClickListener { toggleRecording() }
        transportFavorite.setOnClickListener {
            toggleFavorite()
            updateTransportIcons()
        }
        pausedCluster.setOnClickListener { togglePlayPause() }
        findViewById<View>(R.id.sidebar_scrim).setOnClickListener { hideSidebar() }
        favoritesCache = PlaylistRepository.favorites(this)
        updateTransportIcons()
        // 6.0.0-rc1: touch controls on mobile (tap to toggle the
        // overlay, swipe to change channel, big transport buttons).
        if (DeviceType.isMobile(this)) setupMobileTouch()

        // Best-effort EPG: show Now / Next for this channel once it loads.
        if (ch != null) {
            lifecycleScope.launch {
                val epg = PlaylistRepository.loadEpg(this@PlaybackActivity)
                val (now, next) = EpgParser.nowAndNext(epg[ch.tvgId])
                liveNow = now
                liveNext = next
                // 5.0.0 — catch-up: the replayed programme is "now" and
                // the live programme is "next" (the way back to live).
                if (catchupUrl != null) {
                    nowProgramme = catchupProgramme()
                    nextProgramme = now
                } else {
                    nowProgramme = now
                    nextProgramme = next
                }
                if (overlay.visibility == View.VISIBLE) {
                    // The overlay was opened before the EPG finished
                    // loading and still shows "No information" — refresh
                    // it with the real now/next data, keeping its
                    // current 5s/10s hide mode.
                    showChannelInfo(brief = overlayBrief)
                } else if (briefInfo) {
                    // Channel up/down: flash the info overlay briefly
                    // with the real now/next data, instead of the toast
                    // (the overlay shows the same info). Only if the
                    // user hasn't already opened something else while
                    // the EPG was loading — a late flash must not yank
                    // the sidebar away or shorten a normal overlay.
                    if (!channelSidebar.isVisible() &&
                        overlay.visibility != View.VISIBLE
                    ) {
                        showChannelInfo(brief = true)
                    }
                } else if (catchupUrl != null) {
                    Toast.makeText(
                        this@PlaybackActivity,
                        getString(R.string.catchup_playing, catchupTitle ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                } else if (now != null || next != null) {
                    val text = buildString {
                        now?.let { append("${getString(R.string.now_playing)}: ${it.title}") }
                        next?.let {
                            if (isNotEmpty()) append("  •  ")
                            append("${getString(R.string.next_up)}: ${it.title}")
                        }
                    }
                    Toast.makeText(this@PlaybackActivity, text, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Touch controls for mobile (6.0.0-rc1). TV is untouched — this
     * only runs on phones/tablets:
     * - The "Channels" button appears with the overlay (there is no
     *   Left key to open the sidebar with) and fades with it.
     * - The transport bar and its buttons grow to finger size.
     * - Tapping the video toggles the info overlay; a horizontal
     *   fling changes channel. The tap/fling detector sits on the
     *   PlayerView, so taps on the overlay or sidebar never reach it.
     * 6.0.0-rc3: the overlay (bottom info + top Guide button +
     * centred channel info) auto-hides 5s after the last touch
     * (see armHideTimer + dispatchTouchEvent); tapping the video
     * shows it again.
     */
    private fun setupMobileTouch() {
        // 6.1.6: the "Channels" button is part of the overlay group —
        // it only shows while the overlay is up (see showChannelInfo /
        // hideOverlay), like the top Guide button. It used to be forced
        // VISIBLE here and never faded, so it sat alone on screen after
        // the 5s auto-hide. Its click opens the sidebar.
        channelsButton.setOnClickListener { showSidebar() }
        // 6.0.0-rc3: the top Guide button is part of the overlay
        // group — it only shows while the overlay is up (see
        // showChannelInfo / hideOverlay). Its click opens the guide.
        mobileGuideButton.setOnClickListener { openGuide() }
        transportBar.layoutParams.height = dp(56)
        for (id in listOf(
            R.id.transport_prev, R.id.transport_play_pause, R.id.transport_next,
            R.id.transport_record, R.id.transport_favorite
        )) {
            val button = findViewById<ImageButton>(id)
            button.layoutParams.width = dp(48)
            button.layoutParams.height = dp(48)
        }
        // Mutating LayoutParams fields does not schedule a layout
        // pass by itself — ask for one (the overlay is hidden until
        // first shown, so this is belt-and-braces).
        transportBar.requestLayout()
        val gestures = GestureDetector(this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    if (channelSidebar.isVisible()) hideSidebar()
                    else if (overlay.visibility == View.VISIBLE) hideOverlay()
                    else showChannelInfo()
                    return true
                }

                override fun onFling(
                    e1: MotionEvent?, e2: MotionEvent,
                    velocityX: Float, velocityY: Float
                ): Boolean {
                    if (e1 == null) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    if (kotlin.math.abs(dx) > kotlin.math.abs(dy) &&
                        kotlin.math.abs(dx) > dp(80)
                    ) {
                        switchToAdjacentChannel(if (dx < 0) 1 else -1)
                        return true
                    }
                    return false
                }
            })
        playerView.setOnTouchListener { _, event ->
            gestures.onTouchEvent(event)
            false
        }
    }

    /** 5.0.0: the replayed programme as a synthetic Programme, so the
     * info panel can show it as "now" with its real time window. */
    private fun catchupProgramme(): EpgParser.Programme? {
        val title = catchupTitle ?: return null
        if (catchupStartMillis <= 0L || catchupStopMillis <= catchupStartMillis) return null
        return EpgParser.Programme(
            channelId = channel?.tvgId ?: channel?.id ?: "",
            startMillis = catchupStartMillis,
            stopMillis = catchupStopMillis,
            title = title
        )
    }

    /**
     * (Re)creates the player and starts playback. Called from onStart so
     * returning from the guide (which stops this activity and releases the
     * player) resumes the channel instead of leaving a black screen that
     * never loads. Guarded by player == null so onCreate -> onStart
     * doesn't build it twice.
     */
    private fun initializePlayer() {
        if (player != null) return
        val ch = channel
        // 5.0.0 — catch-up: the replay URL wins over the live one.
        val uri = catchupUrl ?: recordingUri ?: ch?.url ?: return
        val title = catchupTitle ?: recordingName ?: ch?.name ?: return
        val subtitle = if (catchupUrl != null) {
            getString(R.string.catchup_label)
        } else if (recordingUri != null) {
            getString(R.string.recording_label)
        } else {
            ch?.group ?: ""
        }
        // Start playback after a short buffer (0.5s) instead of the
        // default 2.5s, so switching channels feels instant instead of
        // sitting on a black screen. The min buffer stays generous so
        // a slow stream doesn't rebuffer right after starting; the
        // max buffer is capped (PlaybackBuffers, 9.2.0) so a live
        // stream does not hold 30 s of segments in memory on long
        // viewing sessions.
        val fastStartLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                PlaybackBuffers.MIN_BUFFER_MS,
                PlaybackBuffers.MAX_BUFFER_MS,
                PlaybackBuffers.BUFFER_FOR_PLAYBACK_MS,
                PlaybackBuffers.BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
            )
            .build()
        val player = if (recordingUri != null) {
            // Playback from the local download cache.
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(RecordingManager.cacheDataSourceFactory(this))
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(fastStartLoadControl)
                .build()
        } else {
            ExoPlayer.Builder(this)
                .setLoadControl(fastStartLoadControl)
                .build()
        }
        this.player = player
        playerView.player = player
        // 7.3.1: seed the chosen languages at player build, so the
        // selector picks them from the first frame. Before, the
        // choice only landed after the tracks loaded and
        // onTracksChanged fired — the wrong audio could play and
        // captions could lag or flash the wrong language first.
        // reapplyTrackSelections still pins the exact track once
        // the groups are known; this is the early preference.
        val seedParams = player.trackSelectionParameters.buildUpon()
        selectedAudioLanguage?.let { seedParams.setPreferredAudioLanguage(it) }
        if (subtitlesOff) {
            seedParams.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            selectedSubtitleLanguage?.let { seedParams.setPreferredTextLanguage(it) }
        }
        player.trackSelectionParameters = seedParams.build()
        everPlayed = false
        autoRetrying = false
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
        player.prepare()
        player.play()
        // 7.1.0: restore the chosen playback speed on a rebuilt
        // player (returning from the guide rebuilds it).
        if (playbackSpeed != 1f) player.setPlaybackSpeed(playbackSpeed)
        sessionStartMillis = System.currentTimeMillis()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY && !everPlayed) {
                    everPlayed = true
                    // First real playback: this channel counts as watched,
                    // and it becomes the channel launch resumes next time.
                    channel?.let {
                        PlaylistRepository.recordRecentChannel(this@PlaybackActivity, it.id)
                        PlaylistRepository.setLastChannelId(this@PlaybackActivity, it.id)
                        // 9.3.1: spoken channel names — announce on
                        // first real playback, not in onCreate: the
                        // name is spoken when the channel is actually
                        // on, never for a stream that fails to start,
                        // and never twice for one switch (everPlayed
                        // guards this block; pause/resume doesn't
                        // re-enter it).
                        ChannelAnnouncer.announce(this@PlaybackActivity, it.name)
                    }
                    // 6.1.7: on mobile the overlay shows with the video
                    // on first playback, then fades after 5s (see
                    // armHideTimer). Before this the buttons never
                    // appeared until the video was tapped — and since
                    // 6.1.6 the Channels button fades too, a fresh
                    // start showed nothing at all. Skip when the
                    // overlay is already up, when a channel up/down
                    // brief flash is coming (the EPG callback shows
                    // it), or during catch-up (the EPG callback
                    // toasts instead). TV is untouched.
                    if (DeviceType.isMobile(this@PlaybackActivity) &&
                        overlay.visibility != View.VISIBLE &&
                        !intent.getBooleanExtra(EXTRA_SHOW_BRIEF_INFO, false) &&
                        catchupUrl == null
                    ) {
                        showChannelInfo()
                    }
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Paused: show the centred play cluster; playing: hide it.
                // The transport play/pause icon follows the same state.
                pausedCluster.visibility = if (isPlaying) View.GONE else View.VISIBLE
                // 6.0.0-rc3: while paused the big centre play icon owns
                // the middle of the screen, so the centred info panel
                // steps aside; it returns when playback resumes, but
                // only if the overlay is still up.
                if (DeviceType.isMobile(this@PlaybackActivity)) {
                    if (isPlaying) {
                        if (overlay.visibility == View.VISIBLE) {
                            showMobileView(mobileCenterInfo)
                        }
                    } else {
                        mobileCenterInfo.animate().cancel()
                        mobileCenterInfo.visibility = View.GONE
                        mobileCenterInfo.alpha = 1f
                    }
                }
                updateTransportIcons()
            }

            override fun onTracksChanged(tracks: Tracks) {
                // 7.3.0: the tracks just loaded (or changed) — a
                // player rebuild drops the overrides, so re-apply
                // the chosen audio / subtitle languages here.
                reapplyTrackSelections()
            }

            override fun onPlayerError(error: PlaybackException) {
                // 5.0.0 — catch-up that never played: fall back to the
                // live stream ONCE instead of jumping to a random
                // channel. If the live stream fails too, the normal
                // auto-retry below applies (catchupUrl is null now).
                if (catchupUrl != null && !everPlayed && !catchupFallbackTried) {
                    catchupFallbackTried = true
                    catchupUrl = null
                    catchupTitle = null
                    nowProgramme = liveNow
                    nextProgramme = liveNext
                    this@PlaybackActivity.player?.release()
                    this@PlaybackActivity.player = null
                    Toast.makeText(
                        this@PlaybackActivity,
                        R.string.catchup_failed_live,
                        Toast.LENGTH_LONG
                    ).show()
                    initializePlayer()
                    return
                }
                // Dead stream that never played: quietly try another
                // random channel instead of leaving a black screen.
                // Once a stream has played, or retries are spent, just
                // show the error like before.
                if (recordingUri == null && !everPlayed && !autoRetrying &&
                    retryCount < MAX_AUTO_RETRY
                ) {
                    autoRetrying = true
                    tryAnotherChannel()
                    return
                }
                Toast.makeText(
                    this@PlaybackActivity,
                    getString(R.string.playback_error, error.message ?: "unknown error"),
                    Toast.LENGTH_LONG
                ).show()
            }
        })
    }

    override fun onStart() {
        super.onStart()
        // Rebuild the player if it was released in onStop (e.g. after
        // visiting the guide and coming back).
        initializePlayer()
    }

    override fun onResume() {
        super.onResume()
        guideLaunched = false
        // The user may have favorited this channel from the guide —
        // refresh the cache and redraw the star, so the overlay
        // doesn't keep showing the pre-guide state (6.1.8).
        favoritesCache = PlaylistRepository.favorites(this)
        updateTransportIcons()
    }

    /**
     * Key handling — see the class comment for the full map. OK is handled
     * on key-UP (with a long-press timer armed on key-DOWN) so a hold
     * opens the context menu without also firing the short-press action.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val sidebarUp = channelSidebar.isVisible()
        val overlayUp = overlay.visibility == View.VISIBLE
        // 7.0.0: swallow the key-UP of an Enter that confirmed a
        // number jump — the DOWN already jumped, and the UP must not
        // also run the normal OK action (open the guide).
        if (event.action == KeyEvent.ACTION_UP && numberEnterConsumed &&
            (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                event.keyCode == KeyEvent.KEYCODE_ENTER)
        ) {
            numberEnterConsumed = false
            return true
        }
        // 7.0.0: channel-number entry — same as the guide: digits
        // accumulate in the top pill, Enter jumps immediately, any
        // other key cancels the entry and proceeds normally, and a
        // 1.5 s pause jumps on its own. Not during recording
        // playback (there is no channel list to jump in).
        if (event.action == KeyEvent.ACTION_DOWN && recordingUri == null) {
            val digit = when (event.keyCode) {
                KeyEvent.KEYCODE_0 -> 0
                KeyEvent.KEYCODE_1 -> 1
                KeyEvent.KEYCODE_2 -> 2
                KeyEvent.KEYCODE_3 -> 3
                KeyEvent.KEYCODE_4 -> 4
                KeyEvent.KEYCODE_5 -> 5
                KeyEvent.KEYCODE_6 -> 6
                KeyEvent.KEYCODE_7 -> 7
                KeyEvent.KEYCODE_8 -> 8
                KeyEvent.KEYCODE_9 -> 9
                else -> -1
            }
            if (digit >= 0) {
                if (sidebarUp) hideSidebar()
                onNumberDigit(digit)
                return true
            }
            if (!numberEntry.isEmpty()) {
                if (event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                    event.keyCode == KeyEvent.KEYCODE_ENTER
                ) {
                    numberEnterConsumed = true
                    jumpToChannelNumber()
                    return true
                }
                clearNumberEntry()
            }
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    okDownSidebarUp = sidebarUp
                    okDownOverlayUp = overlayUp
                    // Long-press OK (nothing up) runs the held-OK
                    // action (9.3.0: menu, guide, or nothing).
                    if (!sidebarUp && !overlayUp) {
                        longPressFired = false
                        infoHandler.postDelayed(longPressRunnable, LONG_PRESS_MS)
                    }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val right = event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                    if (sidebarUp) {
                        if (right) sidebarActivate() else sidebarBack()
                        return true
                    }
                    if (overlayUp) {
                        // A channel up/down flash upgrades to the normal
                        // Left/Right timeout the first time the user
                        // steers it — after that it behaves exactly like
                        // a normal overlay (no timer reset on later keys).
                        if (overlayBrief) {
                            overlayBrief = false
                            armHideTimer()
                        }
                        // Only steer the strip when a strip tile has
                        // focus; the transport buttons below it keep
                        // normal Left/Right focus movement.
                        if (stripTiles.any { it.isFocused }) {
                            moveStripFocus(right)
                            return true
                        }
                        // 7.1.1: during a recording, Left/Right seeks
                        // when no strip tile has focus — the transport
                        // buttons do nothing useful while a recording
                        // plays (prev/next/record/favorite all need a
                        // channel), so steering focus between them is
                        // pointless; Up/Down still moves focus. Live
                        // keeps the old framework focus movement.
                        if (recordingUri != null) {
                            seekRecording(
                                if (right) PlaybackComfort.SEEK_SKIP_MS
                                else -PlaybackComfort.SEEK_SKIP_MS
                            )
                            armHideTimer()
                            return true
                        }
                        return super.dispatchKeyEvent(event)
                    }
                    // Nothing up: during a recording Left/Right
                    // skip 10 s (7.1.0) — there is no channel list
                    // to open, and the info overlay has its own
                    // entry point (INFO) there. Live keeps the old
                    // behaviour.
                    if (recordingUri != null) {
                        seekRecording(
                            if (right) PlaybackComfort.SEEK_SKIP_MS
                            else -PlaybackComfort.SEEK_SKIP_MS
                        )
                        return true
                    }
                    if (right) showChannelInfo() else showSidebar()
                    return true
                }
                KeyEvent.KEYCODE_BACK -> {
                    if (sidebarUp) {
                        sidebarBack()
                        return true
                    }
                    if (overlayUp) {
                        hideOverlay()
                        return true
                    }
                }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                KeyEvent.KEYCODE_MEDIA_PLAY,
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    togglePlayPause()
                    return true
                }
                KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                    switchToAdjacentChannel(if (event.keyCode == KeyEvent.KEYCODE_CHANNEL_UP) 1 else -1)
                    return true
                }
            }
        }
        if (event.action == KeyEvent.ACTION_UP) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    infoHandler.removeCallbacks(longPressRunnable)
                    if (longPressFired) {
                        // The long-press already opened the menu.
                        longPressFired = false
                        return true
                    }
                    if (okDownSidebarUp) {
                        sidebarActivate()
                    } else if (okDownOverlayUp) {
                        activateFocusedTile()
                    } else if (player?.isPlaying == false) {
                        // Paused with nothing up: OK resumes.
                        togglePlayPause()
                    } else {
                        openGuide()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * 6.0.0-rc3, mobile only: any touch while the overlay is up
     * restarts the 5s auto-hide timer — tapping a transport button,
     * a strip tile, or the guide button counts as interaction just
     * like tapping the video. TV is untouched (its 10s timer is
     * key-driven). The sidebar/guide paths never reach this with
     * the overlay visible: showSidebar hides the overlay first,
     * and opening the guide stops the activity (onStop removes the
     * callbacks), so the timer can't fire while either is open.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (DeviceType.isMobile(this) && ev.action == MotionEvent.ACTION_DOWN &&
            overlay.visibility == View.VISIBLE
        ) {
            armHideTimer(overlayBrief)
        }
        return super.dispatchTouchEvent(ev)
    }

    /** Pause / resume, and keep the cluster + transport icon in sync. */
    private fun togglePlayPause() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else p.play()
        // onIsPlayingChanged updates the cluster and the icon.
    }

    /** Refresh the transport bar icons for the current state. Uses the
     * modern vector icons (4.6.1) — no more dated system drawables. The
     * record dot turns red while recording instead of dimming.
     * 6.1.8: the favorite star is drawn from a fresh read of the
     * favorites, not the cached set — the cache can go stale when
     * the favorite changes from the guide, the sidebar or mobile
     * home, and the star must show the live state every time the
     * info is shown. */
    private fun updateTransportIcons() {
        val p = player
        transportPlayPause.setImageResource(
            if (p != null && !p.isPlaying) R.drawable.ic_play
            else R.drawable.ic_pause
        )
        val ch = channel
        favoritesCache = PlaylistRepository.favorites(this)
        val isFav = ch != null && ch.id in favoritesCache
        transportFavorite.setImageResource(
            if (isFav) R.drawable.ic_star_filled
            else R.drawable.ic_star
        )
        transportRecord.setImageResource(R.drawable.ic_record)
        val isRecording = ch != null && RecordingManager.isRecording(this, ch.id)
        // Red dot while recording; white when idle. Alpha stays 1.0 —
        // the colour is the state now.
        transportRecord.setColorFilter(
            if (isRecording) 0xFFFF4343.toInt() else 0xFFFFFFFF.toInt()
        )
        transportRecord.alpha = 1.0f
    }

    /** Start / stop recording this channel (same action as MENU offers). */
    private fun toggleRecording() {
        val ch = channel ?: return
        if (RecordingManager.isRecording(this, ch.id)) {
            RecordingManager.stopRecording(this, ch.id)
            Toast.makeText(this, R.string.recording_stopped, Toast.LENGTH_SHORT).show()
        } else {
            RecordingManager.startRecording(this, ch)
            Toast.makeText(this, R.string.recording_started, Toast.LENGTH_SHORT).show()
        }
        updateTransportIcons()
    }

    /** Switch to the channel before / after the current one in the playlist. */
    private fun switchToAdjacentChannel(delta: Int) {
        val currentId = channel?.id ?: return
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val hidden = PlaylistRepository.hiddenChannels(this@PlaybackActivity)
            // 4.9.5: find the adjacent channel without building a
            // filtered copy of the whole playlist (was a ~1500-element
            // list per CH+/CH- press). Two allocation-free passes:
            // count the visible channels and the current channel's
            // visible position, then pick the channel at the target
            // visible position. Falls back to the full list when every
            // channel is hidden, exactly like the old ifEmpty.
            var visibleIndex = -1
            var visibleCount = 0
            for (ch in channels) {
                if (ch.id in hidden) continue
                if (ch.id == currentId) visibleIndex = visibleCount
                visibleCount++
            }
            val next: Channel? = if (visibleCount > 0) {
                if (visibleIndex < 0) return@launch
                val target = (visibleIndex + delta).mod(visibleCount)
                var seen = 0
                var found: Channel? = null
                for (ch in channels) {
                    if (ch.id in hidden) continue
                    if (seen == target) {
                        found = ch
                        break
                    }
                    seen++
                }
                found
            } else {
                val idx = channels.indexOfFirst { it.id == currentId }
                    .takeIf { it >= 0 } ?: return@launch
                channels[(idx + delta).mod(channels.size)]
            }
            val pick = next ?: return@launch
            // Channel up/down restarts playback on the new channel and
            // flashes its info briefly (4.7.3) instead of the normal
            // Left/Right overlay duration.
            switchToChannel(pick, briefInfo = true)
        }
    }

    /** One digit of a channel number: show it in the top pill and
     * re-arm the settle timeout (7.0.0). */
    private fun onNumberDigit(digit: Int) {
        val text = numberEntry.press(digit)
        numberEntryView.text = getString(R.string.guide_number_entry, text)
        numberEntryView.visibility = View.VISIBLE
        infoHandler.removeCallbacks(numberJumpRunnable)
        infoHandler.postDelayed(numberJumpRunnable, NUMBER_ENTRY_TIMEOUT_MS)
    }

    /** Hide the number pill and drop the pending entry. */
    private fun clearNumberEntry() {
        infoHandler.removeCallbacks(numberJumpRunnable)
        numberEntry.clear()
        numberEntryView.visibility = View.GONE
    }

    /** Jump to the typed channel number (7.0.0): switch playback to
     * it, or toast when no (visible) channel has that number. */
    private fun jumpToChannelNumber() {
        val number = numberEntry.value()
        clearNumberEntry()
        if (number == null) return
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val hidden = PlaylistRepository.hiddenChannels(this@PlaybackActivity)
            val target = GuideData.channelByNumber(
                channels.filter { it.id !in hidden }, number
            )
            if (target == null) {
                Toast.makeText(
                    this@PlaybackActivity,
                    getString(R.string.guide_no_channel_number, number),
                    Toast.LENGTH_SHORT
                ).show()
                return@launch
            }
            switchToChannel(target, briefInfo = true)
        }
    }

    // ------------------------------------------------------------------
    // Left sidebar (4.6.2: shared ChannelSidebar helper)
    // ------------------------------------------------------------------

    /** Open the sidebar on the groups list; hides the info overlay. */
    private fun showSidebar() {
        if (recordingUri != null) return // Recordings have no channel list.
        hideOverlay()
        channelSidebar.show()
    }

    private fun hideSidebar() {
        channelSidebar.hide()
    }

    /** Left / BACK inside the sidebar: channels -> groups, groups -> close. */
    private fun sidebarBack() {
        channelSidebar.back()
    }

    /** OK / Right inside the sidebar: open a group, or switch channel. */
    private fun sidebarActivate() {
        channelSidebar.activate()
    }


    // ------------------------------------------------------------------
    // Info overlay (restyled 4.6.0)
    // ------------------------------------------------------------------

    /** Shows the channel info overlay; auto-hides after 10s idle.
     * With [brief] (channel up/down), it hides after the shorter
     * 5s flash timeout instead. 6.0.0-rc3: on mobile the timeout is
     * always 5s, and the top Guide button + centred channel info
     * show with it. */
    private fun showChannelInfo(brief: Boolean = false) {
        hideSidebar()
        val ch = channel
        val name = recordingName ?: ch?.name ?: return
        val now = System.currentTimeMillis()
        infoGroup.text = ch?.group ?: ""
        // DateFormat.format(long) takes the millis directly — the old
        // code wrapped each one in a throwaway Date (3 per overlay open).
        infoDateTime.text = dateTimeFormat.format(now)
        infoChannelName.text = name
        // Skip re-loading the same logo — but only for a real URL: a
        // null logo (recording playback) still loads every time (Coil
        // shows the placeholder), exactly as before. 6.1.0:
        // downsampled to the 60dp info logo.
        val logoUrl = ch?.logo
        if (logoUrl == null || logoUrl != infoLogoUrl) {
            infoLogoUrl = logoUrl
            infoLogo.loadLogo(logoUrl, 60)
        }
        val nowP = nowProgramme
        if (nowP != null) {
            // 5.0.0 — catch-up: the replayed programme gets the ⏪
            // marker, and the meta line says so. The progress bar is
            // wall-clock based, so it hides during catch-up — a full
            // bar under a finished show would be misleading.
            infoNowTitle.text =
                if (catchupUrl != null) "⏪ ${nowP.title}" else nowP.title
            val mins = ((nowP.stopMillis - nowP.startMillis) / 60_000).toInt()
            val num = if (ch != null && ch.number > 0) "${ch.number} " else ""
            infoNowMeta.text = "${timeFormat.format(nowP.startMillis)} – " +
                "${timeFormat.format(nowP.stopMillis)}  •  $mins min  •  $num$name" +
                if (catchupUrl != null) "  •  ⏪ ${getString(R.string.catchup_label)}" else ""
            infoNowMeta.visibility = View.VISIBLE
            val span = (nowP.stopMillis - nowP.startMillis).coerceAtLeast(1L)
            infoProgress.progress = ((now - nowP.startMillis) * 100 / span).toInt().coerceIn(0, 100)
            infoProgress.visibility = if (catchupUrl != null) View.GONE else View.VISIBLE
        } else {
            infoNowTitle.text = getString(R.string.overlay_no_info)
            infoNowMeta.text = ""
            infoNowMeta.visibility = View.GONE
            infoProgress.progress = 0
        }
        val nextP = nextProgramme
        if (nextP != null) {
            infoNext.text = "${timeFormat.format(nextP.startMillis)} – " +
                "${timeFormat.format(nextP.stopMillis)}  •  ${nextP.title}"
            infoNext.visibility = View.VISIBLE
        } else {
            // No next programme: hide the line instead of a second
            // "No information" under the big now-title.
            infoNext.visibility = View.GONE
        }
        // Bottom bar title: the programme on now, or the channel name.
        transportTitle.text = nowP?.title ?: name
        updateTransportIcons()
        buildStrip()
        overlay.visibility = View.VISIBLE
        // 5.0.0 — neat animations: the overlay fades in quickly
        // (150ms) instead of popping in, so channel switches feel
        // smoother. Fast enough to never delay the info itself.
        // Cancel any fade-out in flight first, or its end action
        // would hide the overlay again after this show.
        // 9.1.0: with reduced animations on, the fade is skipped —
        // the overlay just appears at full opacity.
        overlay.animate().cancel()
        if (AppSettings.reducedAnimations(this)) {
            overlay.alpha = 1f
        } else {
            overlay.alpha = 0f
            overlay.animate().alpha(1f).setDuration(150).start()
        }
        // 6.0.0-rc3: on mobile the top Guide button and centred
        // channel info show and fade with the overlay.
        if (DeviceType.isMobile(this)) {
            updateMobileCenterInfo()
            showMobileOverlayViews()
        }
        // Focus the first tile so left/right + OK work straight away.
        // Deferred until after layout, so the focus actually sticks.
        infoStrip.post { stripTiles.firstOrNull()?.requestFocus() }
        overlayBrief = brief
        armHideTimer(brief)
    }

    private fun hideOverlay() {
        overlayBrief = false
        infoHandler.removeCallbacks(hideInfoRunnable)
        infoHandler.removeCallbacks(longPressRunnable)
        if (DeviceType.isMobile(this)) {
            // 6.0.0-rc3: the overlay fades out (200ms) instead of
            // popping off, and the top Guide button + centred info
            // fade with it. 6.1.6: the Channels button fades with
            // them too — it used to stay on screen forever. TV keeps
            // the instant hide.
            fadeOutOverlayView(overlay)
            fadeOutOverlayView(channelsButton)
            fadeOutOverlayView(mobileGuideButton)
            fadeOutOverlayView(mobileCenterInfo)
        } else if (AppSettings.tvInfoFade(this)) {
            // 6.1.2: the TV info overlay fades out (200ms) like the
            // mobile one instead of popping off — the user reported
            // it never faded. Turn it off in Settings to go back to
            // the instant hide.
            fadeOutOverlayView(overlay)
        } else {
            overlay.visibility = View.GONE
        }
    }

    /** 6.0.0-rc3: fade an overlay view out, then hide it (mobile;
     * 6.1.2: the TV info overlay uses it too). Cancelling a fade-out
     * in flight (a re-show does this) never runs its end action, so
     * a re-shown view can't be hidden again by a stale fade. Alpha
     * is reset so the next show starts clean. 9.1.0: with reduced
     * animations on, the view hides at once instead of fading. */
    private fun fadeOutOverlayView(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
        if (AppSettings.reducedAnimations(this)) {
            view.visibility = View.GONE
            view.alpha = 1f
            return
        }
        view.animate().alpha(0f).setDuration(OVERLAY_FADE_MS).withEndAction {
            view.visibility = View.GONE
            view.alpha = 1f
        }.start()
    }

    /** 6.0.0-rc3: show the mobile-only overlay group (top Guide
     * button + centred channel info) with the same 150ms fade-in
     * as the bottom overlay. 6.1.6: the Channels button shows
     * with them too. The centred panel steps aside while
     * paused — the big centre play icon owns the middle of the
     * screen then (see onIsPlayingChanged). */
    private fun showMobileOverlayViews() {
        showMobileView(channelsButton)
        showMobileView(mobileGuideButton)
        if (player?.isPlaying != false) showMobileView(mobileCenterInfo)
    }

    /** 6.0.0-rc3: fade one mobile overlay view in (150ms). 9.1.0:
     * with reduced animations on, the view just appears. */
    private fun showMobileView(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
        if (AppSettings.reducedAnimations(this)) {
            view.alpha = 1f
            return
        }
        view.alpha = 0f
        view.animate().alpha(1f).setDuration(150).start()
    }

    /** 6.0.0-rc3: fill the mobile-only centred info panel from the
     * same data as the bottom overlay (channel + now/next), so the
     * two never disagree. Called on every overlay show, including
     * the EPG-landing refresh. */
    private fun updateMobileCenterInfo() {
        val ch = channel
        val name = recordingName ?: ch?.name ?: return
        val num = if (ch != null && ch.number > 0) "${ch.number}  " else ""
        mobileCenterChannel.text = "$num$name"
        val logoUrl = ch?.logo
        // 6.1.0: downsampled to the 72dp centre logo.
        mobileCenterLogo.loadLogo(logoUrl, 72)
        val nowP = nowProgramme
        if (nowP != null) {
            val title = if (catchupUrl != null) "⏪ ${nowP.title}" else nowP.title
            mobileCenterNow.text = "${getString(R.string.now_playing)}: $title"
            mobileCenterNow.visibility = View.VISIBLE
        } else {
            mobileCenterNow.text = getString(R.string.overlay_no_info)
            mobileCenterNow.visibility = View.VISIBLE
        }
        val nextP = nextProgramme
        if (nextP != null) {
            mobileCenterNext.text = "${getString(R.string.next_up)}: ${nextP.title}"
            mobileCenterNext.visibility = View.VISIBLE
        } else {
            mobileCenterNext.visibility = View.GONE
        }
    }

    private fun armHideTimer(brief: Boolean = false) {
        infoHandler.removeCallbacks(hideInfoRunnable)
        val timeout = when {
            // 6.0.0-rc3: mobile auto-hides after 5s idle.
            DeviceType.isMobile(this) -> MOBILE_OVERLAY_TIMEOUT_MS
            brief -> CHANNEL_SWITCH_OVERLAY_TIMEOUT_MS
            else -> OVERLAY_TIMEOUT_MS
        }
        infoHandler.postDelayed(hideInfoRunnable, timeout)
    }

    /** Display density, cached once — dp() used to read
     * resources.displayMetrics on every call (up to ~90 calls per
     * strip build). */
    private val density: Float by lazy { resources.displayMetrics.density }

    private fun dp(v: Int): Int = (v * density).toInt()

    /** Builds the bottom strip: TV guide, History, 3 most recent channels, Clear.
     * Common case: the playlist is already in memory, so the strip is built
     * once, with recents — instead of building it empty and rebuilding it
     * a frame later. When the playlist isn't cached yet, the fixed tiles
     * show immediately and the recents fill in once it loads (skipped
     * entirely when the recents are unchanged from the last build).
     * 4.9.5: the cached path also skips the rebuild when the recents
     * are unchanged and the strip is already built — every Right press
     * and channel-switch flash used to remove and rebuild all the
     * tiles (views + listeners + logo loads) for the same recents. */
    private fun buildStrip() {
        val noInfo = getString(R.string.overlay_no_info)
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            val recents = stripRecents(cached)
            if (recents.map { it.id } == lastStripRecents &&
                (catchupUrl != null) == lastStripCatchup && stripTiles.isNotEmpty()
            ) return
            buildStripStatic(recents, noInfo)
            return
        }
        buildStripStatic(emptyList(), noInfo)
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val recents = stripRecents(channels)
            if (overlay.visibility != View.VISIBLE) return@launch
            if (recents.map { it.id } == lastStripRecents &&
                (catchupUrl != null) == lastStripCatchup
            ) return@launch
            val keepFocus = stripTiles.indexOfFirst { it.isFocused }
            buildStripStatic(recents, noInfo)
            // Restore focus (or default to the first tile); deferred
            // until after layout so it sticks.
            infoStrip.post {
                if (keepFocus in stripTiles.indices) stripTiles[keepFocus].requestFocus()
                else stripTiles.firstOrNull()?.requestFocus()
            }
        }
    }

    /** The 3 most recent channels (not the current one) from a list. */
    private fun stripRecents(channels: List<Channel>): List<Channel> {
        if (stripByIdPlaylist !== channels) {
            stripById = channels.associateBy { it.id }
            stripByIdPlaylist = channels
        }
        val currentId = channel?.id
        return PlaylistRepository.recentChannelIds(this)
            .filter { it != currentId }
            .mapNotNull { stripById[it] }
            .take(3)
    }

    /** The strip's fixed tiles plus the given recent channels, then Clear.
     * 4.6.1: modern look — rounded tile_bg selector (accent on focus) and
     * fresh vector icons instead of the dated system drawables. The label
     * and icon turn dark on the green focused tile so they stay readable;
     * channel logos are never tinted.
     * 4.6.3: tiles are smaller and more compact (120x84dp, 32dp icons,
     * 13sp labels) so the strip takes up less of the screen.
     * 4.7.1: tiles are shorter still (120x60dp, 24dp icons, 12sp labels)
     * so the whole info overlay sits well below the middle of the screen. */
    private fun buildStripStatic(recents: List<Channel>, noInfo: String) {
        infoStrip.removeAllViews()
        stripTiles.clear()

        fun tile(iconRes: Int?, logoUrl: String?, label: String, onClick: () -> Unit) {
            // 6.0.0-rc1: tiles are bigger on mobile (finger targets).
            val tileWidth = if (DeviceType.isMobile(this)) dp(120) else dp(100)
            val tileHeight = if (DeviceType.isMobile(this)) dp(64) else dp(50)
            val labelSize = if (DeviceType.isMobile(this)) 12f else 10f
            val tileView = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(4), dp(4), dp(4), dp(4))
                layoutParams = LinearLayout.LayoutParams(tileWidth, tileHeight).apply {
                    marginEnd = dp(6)
                }
                setBackgroundResource(R.drawable.tile_bg)
                setOnClickListener { onClick() }
            }
            val icon = ImageView(this).apply {
                layoutParams = LinearLayout.LayoutParams(dp(20), dp(20))
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (logoUrl != null) {
                    // 6.1.0: downsampled to the 20dp tile icon.
                    loadLogo(logoUrl, 20)
                } else if (iconRes != null) {
                    setImageResource(iconRes)
                }
            }
            val labelView = TextView(this).apply {
                text = label
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, labelSize)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(1) }
            }
            tileView.addView(icon)
            tileView.addView(labelView)
            tileView.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                // Focused tile is accent green — dark text/icon on it.
                // Logos keep their own colours.
                val dark = 0xFF121218.toInt()
                labelView.setTextColor(if (hasFocus) dark else 0xFFFFFFFF.toInt())
                if (iconRes != null) {
                    icon.setColorFilter(if (hasFocus) dark else 0xFFFFFFFF.toInt())
                }
            }
            // OK is handled by the activity's dispatchKeyEvent (it calls
            // performClick on the focused tile), so no per-tile key
            // listener is needed here.
            infoStrip.addView(tileView)
            stripTiles += tileView
        }

        tile(R.drawable.ic_guide, null, getString(R.string.tv_guide)) { openGuide() }
        tile(R.drawable.ic_history, null, getString(R.string.overlay_history)) { openGuide() }
        // 5.0.0 — catch-up: a "Go live" tile first in the strip, so the
        // way back to the live stream is one OK press away. (CH+/CH-,
        // the sidebar, and tapping the live programme in the guide all
        // go live too — they start a fresh playback without catch-up.)
        if (catchupUrl != null) {
            tile(R.drawable.ic_play, null, getString(R.string.catchup_go_live)) { goLive() }
        }
        for (recent in recents) {
            tile(null, recent.logo, recent.name) { switchToChannel(recent) }
        }
        tile(R.drawable.ic_clear, null, getString(R.string.overlay_clear)) {
            PlaylistRepository.clearRecentChannels(this)
            Toast.makeText(this, R.string.overlay_recents_cleared, Toast.LENGTH_SHORT).show()
            hideOverlay()
        }
        lastStripRecents = recents.map { it.id }
        lastStripCatchup = catchupUrl != null
    }

    /** Move focus to the next / previous strip tile. */
    private fun moveStripFocus(right: Boolean) {
        if (stripTiles.isEmpty()) return
        val current = stripTiles.indexOfFirst { it.isFocused }.takeIf { it >= 0 } ?: 0
        val next = if (right) (current + 1).coerceAtMost(stripTiles.size - 1)
        else (current - 1).coerceAtLeast(0)
        stripTiles[next].requestFocus()
        armHideTimer()
    }

    /** Activate whichever strip tile currently has focus. */
    private fun activateFocusedTile() {
        stripTiles.firstOrNull { it.isFocused }?.performClick()
    }

    /** Switch playback to the given channel (used by a recent-channel tile). */
    private fun switchToChannel(pick: Channel, briefInfo: Boolean = false) {
        hideOverlay()
        hideSidebar()
        val intent = Intent(this, PlaybackActivity::class.java)
        intent.putExtra(EXTRA_CHANNEL, pick)
        if (briefInfo) intent.putExtra(EXTRA_SHOW_BRIEF_INFO, true)
        carrySessionExtras(intent)
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)
        finish()
    }

    /** 7.1.1: carry the viewing-session state across a channel
     * change — the chosen playback speed, so it survives a zap. A
     * session that ends (BACK, timer fired) carries nothing, and
     * the next launch starts fresh at 1×.
     * 7.3.0: the chosen audio / subtitle languages ride along
     * too, so a zap doesn't reset them either. */
    private fun carrySessionExtras(intent: Intent) {
        if (playbackSpeed != 1f) {
            intent.putExtra(EXTRA_PLAYBACK_SPEED, playbackSpeed)
        }
        selectedAudioLanguage?.let { intent.putExtra(EXTRA_AUDIO_LANGUAGE, it) }
        selectedSubtitleLanguage?.let { intent.putExtra(EXTRA_SUBTITLE_LANGUAGE, it) }
        if (subtitlesOff) intent.putExtra(EXTRA_SUBTITLES_OFF, true)
    }

    /** 5.0.0: leave a catch-up replay and return to the live stream
     * of the same channel (the strip's "Go live" tile). Rebuilds the
     * player on the live URL and shows the live info panel. */
    private fun goLive() {
        if (channel == null) return
        catchupUrl = null
        catchupTitle = null
        nowProgramme = liveNow
        nextProgramme = liveNext
        player?.release()
        player = null
        hideOverlay()
        initializePlayer()
        showChannelInfo()
    }

    private fun openGuide() {
        if (guideLaunched) return
        guideLaunched = true
        val intent = Intent(this, GuideActivity::class.java)
        // The guide highlights the current channel in the accent colour.
        channel?.let { intent.putExtra(GuideActivity.EXTRA_CURRENT_CHANNEL_ID, it.id) }
        // 7.1.1: the guide forwards the chosen speed to whatever
        // channel it starts, so picking there keeps it too.
        carrySessionExtras(intent)
        startActivity(intent)
    }

    /** 10.0.0: enter picture-in-picture and open the guide on top,
     * so the channel keeps playing in a small window while
     * browsing. The options dialog is dismissed first — entering
     * PiP with a dialog up would leave it floating over the small
     * window. When PiP entry fails (the feature is present but the
     * system refuses), the guide stays closed so playback doesn't
     * silently stop for nothing. */
    private fun enterPipAndOpenGuide() {
        currentDialog?.dismiss()
        currentDialog = null
        hideOverlay()
        hideSidebar()
        numberEntryView.visibility = View.GONE
        val entered = enterPictureInPictureMode(PictureInPicture.buildParams(this))
        if (entered) {
            openGuide()
        } else {
            Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** 10.0.0: entering PiP shows video only in the small window —
     * the overlay, sidebar, number pill and any dialog would sit
     * cramped over the picture. Leaving PiP needs nothing: the
     * overlay stays hidden until the next key press, like any
     * other return to playback. */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            currentDialog?.dismiss()
            currentDialog = null
            hideOverlay()
            hideSidebar()
            numberEntryView.visibility = View.GONE
        }
    }

    /** Pick a different random channel and restart playback with it.
     * Allocation-free (same two-pass pattern 4.9.5 used for channel
     * up/down): the old code built a filtered copy of the whole
     * playlist just to pick one random channel from it. */
    private fun tryAnotherChannel() {
        val currentId = channel?.id
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull()
            val hidden = PlaylistRepository.hiddenChannels(this@PlaybackActivity)
            var visibleCount = 0
            if (channels != null) {
                for (ch in channels) {
                    if (ch.id != currentId && ch.id !in hidden) visibleCount++
                }
            }
            val pick: Channel? = if (visibleCount == 0 || channels == null) {
                null
            } else {
                val target = (0 until visibleCount).random()
                var seen = 0
                var found: Channel? = null
                for (ch in channels) {
                    if (ch.id != currentId && ch.id !in hidden) {
                        if (seen == target) {
                            found = ch
                            break
                        }
                        seen++
                    }
                }
                found
            }
            if (pick != null) {
                val intent = Intent(this@PlaybackActivity, PlaybackActivity::class.java)
                intent.putExtra(EXTRA_CHANNEL, pick)
                intent.putExtra(EXTRA_RETRY_COUNT, retryCount + 1)
                carrySessionExtras(intent)
                intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                startActivity(intent)
                finish()
            } else {
                autoRetrying = false
                Toast.makeText(
                    this@PlaybackActivity,
                    R.string.load_failed,
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_MENU -> {
                showOptions()
                return true
            }
            KeyEvent.KEYCODE_INFO -> {
                if (recordingUri != null) {
                    // 7.1.0: INFO opens the info overlay during a
                    // recording — Left/Right seek there now, so the
                    // overlay needs another entry point. Live keeps
                    // the favorite toggle.
                    showChannelInfo()
                } else {
                    toggleFavorite()
                    updateTransportIcons()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun toggleFavorite() {
        channel?.let {
            PlaylistRepository.toggleFavorite(this, it.id)
            favoritesCache = PlaylistRepository.favorites(this)
            val isFav = it.id in favoritesCache
            Toast.makeText(
                this,
                if (isFav) getString(R.string.added_favorite) else getString(R.string.removed_favorite),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 7.1.0: the playback menu is a (label, action) list now, so the
     * entries differ by what is playing — live channels get favorite
     * + record + sleep timer + speed; recordings get the sleep timer
     * (no channel to favorite/record, and speed is live-only).
     * Recordings used to have no menu at all (early return).
     */
    private fun showOptions() {
        val ch = channel
        val entries = mutableListOf<Pair<String, () -> Unit>>()
        if (ch != null) {
            val isFav = ch.id in favoritesCache
            entries += (if (isFav) getString(R.string.remove_favorite)
            else getString(R.string.add_favorite)) to {
                toggleFavorite()
                updateTransportIcons()
            }
            val isRecording = RecordingManager.isRecording(this, ch.id)
            val hasRecording = RecordingManager.hasRecording(this, ch.id)
            entries += when {
                isRecording -> getString(R.string.stop_recording)
                hasRecording -> getString(R.string.record_again)
                else -> getString(R.string.record_channel)
            } to {
                if (isRecording) {
                    RecordingManager.stopRecording(this, ch.id)
                    Toast.makeText(
                        this,
                        R.string.recording_stopped,
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    RecordingManager.startRecording(this, ch)
                    Toast.makeText(
                        this,
                        R.string.recording_started,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                updateTransportIcons()
            }
        }
        entries += getString(
            R.string.sleep_timer_menu,
            PlaybackComfort.sleepLabel(sleepTimerMinutes)
        ) to { showSleepTimerPicker() }
        // Speed is live-only: a recording has no live edge to chase,
        // and a replay has its own seek keys.
        if (ch != null && recordingUri == null && catchupUrl == null) {
            entries += getString(
                R.string.playback_speed_menu,
                PlaybackComfort.speedLabel(playbackSpeed)
            ) to { showSpeedPicker() }
        }
        // 7.3.0: audio track + subtitles — only when the stream
        // actually carries a choice. A single audio track has
        // nothing to pick, and a stream with no subtitles has
        // nothing to toggle.
        val audio = audioChoices()
        if (audio.size > 1) {
            entries += getString(
                R.string.audio_track_menu,
                TrackSelection.audioLabel(currentAudioLanguage())
            ) to { showAudioPicker(audio) }
        }
        val subs = subtitleChoices()
        if (subs.isNotEmpty()) {
            entries += getString(
                R.string.subtitles_menu,
                TrackSelection.subtitleLabel(currentSubtitleLanguage())
            ) to { showSubtitlePicker(subs) }
        }
        // 10.0.0: picture-in-picture — keep watching in a small
        // window while browsing the guide. Only on devices with
        // the PiP feature; a recording replays from disk the same
        // way a live stream does, so it is offered for both.
        if (PictureInPicture.isSupported(this)) {
            entries += getString(R.string.pip_menu) to { enterPipAndOpenGuide() }
        }
        AlertDialog.Builder(this)
            .setTitle(ch?.name ?: recordingName ?: getString(R.string.app_name))
            .setItems(entries.map { it.first }.toTypedArray()) { _, which ->
                entries[which].second()
            }
            .show()
            .also { trackDialog(it) }
    }

    /** 7.1.0: remember the dialog on screen (and clear the field
     * when it goes away) so the sleep timer can dismiss it before
     * finishing the activity. */
    private fun trackDialog(dialog: AlertDialog) {
        currentDialog = dialog
        dialog.setOnDismissListener {
            if (currentDialog === dialog) currentDialog = null
        }
    }

    /** 7.1.0: sleep-timer picker — Off / 15 / 30 / 60 / 90 minutes. */
    private fun showSleepTimerPicker() {
        val labels = mutableListOf(getString(R.string.sleep_timer_off))
        labels += PlaybackComfort.SLEEP_MINUTES.map {
            getString(R.string.sleep_timer_minutes, it)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_timer_title)
            .setItems(labels.toTypedArray()) { _, which ->
                setSleepTimer(
                    if (which == 0) null
                    else PlaybackComfort.SLEEP_MINUTES[which - 1]
                )
            }
            .show()
            .also { trackDialog(it) }
    }

    /** 7.1.0: set (or clear) the sleep timer. The timer counts
     * wall-clock time from when it is set, paused or not, and fires
     * once: pause + leave playback. Setting a new one replaces the
     * old; Off cancels it. */
    private fun setSleepTimer(minutes: Int?) {
        sleepHandler.removeCallbacks(sleepTimerRunnable)
        sleepTimerMinutes = minutes
        if (minutes != null) {
            sleepHandler.postDelayed(
                sleepTimerRunnable,
                PlaybackComfort.sleepDelayMillis(minutes)
            )
            Toast.makeText(
                this,
                getString(R.string.sleep_timer_set, minutes),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /** 7.1.0: playback-speed picker — 0.5× to 2×, live streams only. */
    private fun showSpeedPicker() {
        val labels = PlaybackComfort.SPEEDS.map {
            PlaybackComfort.speedLabel(it)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.playback_speed_title)
            .setItems(labels) { _, which ->
                playbackSpeed = PlaybackComfort.SPEEDS[which]
                player?.setPlaybackSpeed(playbackSpeed)
                Toast.makeText(
                    this,
                    getString(
                        R.string.playback_speed_set,
                        PlaybackComfort.speedLabel(playbackSpeed)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
            .also { trackDialog(it) }
    }

    // ------------------------------------------------------------------
    // 7.3.0 — Audio track + subtitle selection
    // ------------------------------------------------------------------

    /** The audio tracks of the current stream as selectable choices
     * — supported tracks only, in group order. Empty before the
     * tracks have loaded. */
    private fun audioChoices(): List<TrackSelection.TrackChoice> =
        trackChoicesOfType(C.TRACK_TYPE_AUDIO)

    /** The subtitle tracks of the current stream as selectable
     * choices — supported tracks only, in group order. */
    private fun subtitleChoices(): List<TrackSelection.TrackChoice> =
        trackChoicesOfType(C.TRACK_TYPE_TEXT)

    private fun trackChoicesOfType(type: Int): List<TrackSelection.TrackChoice> {
        val p = player ?: return emptyList()
        val choices = mutableListOf<TrackSelection.TrackChoice>()
        var fallback = 0
        p.currentTracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type != type) return@forEachIndexed
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i)) continue
                val format = group.getTrackFormat(i)
                choices += TrackSelection.TrackChoice(
                    groupIndex = groupIndex,
                    trackIndex = i,
                    label = TrackSelection.displayName(
                        format.language, format.label, fallback++
                    ),
                    language = format.language
                )
            }
        }
        return choices
    }

    /** The language of the audio track actually playing now, or
     * null when no audio track is selected yet. The menu shows
     * this — not the stored choice — so Auto shows what the
     * selector picked. */
    private fun currentAudioLanguage(): String? =
        currentSelectedLanguage(C.TRACK_TYPE_AUDIO)

    /** The language of the subtitle track actually showing now, or
     * null when none is selected. */
    private fun currentSubtitleLanguage(): String? =
        currentSelectedLanguage(C.TRACK_TYPE_TEXT)

    private fun currentSelectedLanguage(type: Int): String? {
        val p = player ?: return null
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (group.isTrackSelected(i)) return group.getTrackFormat(i).language
            }
        }
        return null
    }

    /** Apply an audio choice — null is Auto (clear the override and
     * let the selector pick). The language is remembered so a
     * player rebuild re-applies it. */
    private fun applyAudioChoice(choice: TrackSelection.TrackChoice?) {
        val p = player ?: return
        selectedAudioLanguage = choice?.language
        val params = p.trackSelectionParameters.buildUpon()
        if (choice == null) {
            params.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        } else {
            val group = p.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
            params.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.trackIndex))
            )
        }
        p.trackSelectionParameters = params.build()
    }

    /** Apply a subtitle choice — null turns captions off (the text
     * type is disabled); a choice enables the type and overrides
     * it to that track. */
    private fun applySubtitleChoice(choice: TrackSelection.TrackChoice?) {
        val p = player ?: return
        val params = p.trackSelectionParameters.buildUpon()
        if (choice == null) {
            subtitlesOff = true
            selectedSubtitleLanguage = null
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            params.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        } else {
            subtitlesOff = false
            selectedSubtitleLanguage = choice.language
            val group = p.currentTracks.groups.getOrNull(choice.groupIndex) ?: return
            params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            params.setOverrideForType(
                TrackSelectionOverride(group.mediaTrackGroup, listOf(choice.trackIndex))
            )
        }
        p.trackSelectionParameters = params.build()
    }

    /** Re-apply the chosen audio / subtitle languages when the
     * tracks reload — a player rebuild (returning from the guide)
     * or a stream track change drops the overrides. Only applies
     * when the current selection does not already carry the
     * language, so this never loops on its own onTracksChanged;
     * the reapplying flag covers the synchronous case. */
    private fun reapplyTrackSelections() {
        if (reapplyingTracks) return
        val p = player ?: return
        val wantedAudio = selectedAudioLanguage
        if (wantedAudio != null &&
            currentAudioLanguage()?.equals(wantedAudio, ignoreCase = true) != true
        ) {
            TrackSelection.findByLanguage(audioChoices(), wantedAudio)?.let { choice ->
                reapplyingTracks = true
                applyAudioChoice(choice)
                reapplyingTracks = false
            }
        }
        if (subtitlesOff) {
            // The user turned captions off — a rebuilt player
            // defaults to auto-selecting a subtitle, so disable
            // the text type again if it is not already off.
            if (C.TRACK_TYPE_TEXT !in p.trackSelectionParameters.disabledTrackTypes) {
                reapplyingTracks = true
                applySubtitleChoice(null)
                reapplyingTracks = false
            }
            return
        }
        val wantedSub = selectedSubtitleLanguage
        if (wantedSub != null &&
            currentSubtitleLanguage()?.equals(wantedSub, ignoreCase = true) != true
        ) {
            TrackSelection.findByLanguage(subtitleChoices(), wantedSub)?.let { choice ->
                reapplyingTracks = true
                applySubtitleChoice(choice)
                reapplyingTracks = false
            }
        }
    }

    /** 7.3.0: audio-track picker — Auto first, then the stream's
     * tracks. The toast names the choice, not the (possibly
     * still-loading) effective track. */
    private fun showAudioPicker(choices: List<TrackSelection.TrackChoice>) {
        val labels = mutableListOf(getString(R.string.audio_track_auto))
        labels += choices.map { it.label }
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_track_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val choice = if (which == 0) null else choices[which - 1]
                applyAudioChoice(choice)
                // 7.3.1: remember the pick for this channel, so
                // returning to it restores the language.
                channel?.id?.let { TrackPrefs.saveAudio(this, it, choice?.language) }
                Toast.makeText(
                    this,
                    getString(
                        R.string.audio_track_set,
                        TrackSelection.audioLabel(choice?.language)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
            .also { trackDialog(it) }
    }

    /** 7.3.0: subtitle picker — Off first, then the stream's
     * subtitle tracks. PlayerView's built-in subtitle view renders
     * the chosen track; nothing else is needed to show captions. */
    private fun showSubtitlePicker(choices: List<TrackSelection.TrackChoice>) {
        val labels = mutableListOf(getString(R.string.subtitles_off))
        labels += choices.map { it.label }
        AlertDialog.Builder(this)
            .setTitle(R.string.subtitles_title)
            .setItems(labels.toTypedArray()) { _, which ->
                val choice = if (which == 0) null else choices[which - 1]
                applySubtitleChoice(choice)
                // 7.3.1: remember the pick for this channel, so
                // returning to it restores the captions (or Off).
                channel?.id?.let {
                    TrackPrefs.saveSubtitle(this, it, choice?.language, choice == null)
                }
                Toast.makeText(
                    this,
                    getString(
                        R.string.subtitles_set,
                        TrackSelection.subtitleLabel(choice?.language)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .show()
            .also { trackDialog(it) }
    }

    /** 7.1.0: skip a recording forward/back by [deltaMs] (±10 s),
     * clamped to the recording's length. A no-op without a player,
     * and a no-op while the source is still preparing — the
     * position is C.TIME_UNSET then, and seeking from it would
     * jump to 0 (review catch, 7.1.0).
     * 7.1.1: the toast says where the seek landed — the old "+10s"
     * toast said nothing about position, and a recording has no
     * progress bar. */
    private fun seekRecording(deltaMs: Long) {
        val p = player ?: return
        val position = p.currentPosition
        if (position == C.TIME_UNSET) return
        val target = PlaybackComfort.clampSeek(position + deltaMs, p.duration)
        p.seekTo(target)
        val duration = p.duration
        val text = if (duration > 0) {
            getString(
                R.string.recording_seek_position,
                PlaybackComfort.formatSeekTime(target),
                PlaybackComfort.formatSeekTime(duration)
            )
        } else {
            PlaybackComfort.formatSeekTime(target)
        }
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        // 7.1.0: the sleep timer dies with the session — a channel
        // change (new activity) starts fresh, and a finished activity
        // must never fire into a dead player. Dismiss any dialog
        // too, so no window is left attached to this activity.
        currentDialog?.dismiss()
        currentDialog = null
        sleepHandler.removeCallbacks(sleepTimerRunnable)
        super.onDestroy()
    }

    override fun onStop() {
        super.onStop()
        infoHandler.removeCallbacks(hideInfoRunnable)
        infoHandler.removeCallbacks(longPressRunnable)
        overlay.visibility = View.GONE
        // 6.0.0-rc3: the mobile overlay group hides instantly here
        // (no fade — the activity is leaving). Otherwise the Guide
        // button / centre info could sit visible with no timer
        // running when the user comes back from the guide.
        if (DeviceType.isMobile(this)) {
            for (view in listOf(mobileGuideButton, mobileCenterInfo)) {
                view.animate().cancel()
                view.visibility = View.GONE
                view.alpha = 1f
            }
        }
        hideSidebar()
        // 10.0.0: in PiP the activity is paused, not stopped — but
        // if the system does stop it while the window is up (screen
        // off), releasing the player would black out the window,
        // and the session isn't over anyway: it keeps accruing and
        // is logged when playback really ends.
        if (!isInPictureInPictureMode) {
            // Log the watch session (local only, never shared).
            val watchedMillis = System.currentTimeMillis() - sessionStartMillis
            if (sessionStartMillis > 0L && watchedMillis > 0L) {
                val id = channel?.id ?: recordingId ?: "unknown"
                val name = channel?.name ?: recordingName ?: "Unknown"
                StatsManager.logWatchSession(this, id, name, watchedMillis)
            }
            sessionStartMillis = 0L
            player?.release()
            player = null
        }
    }
}
