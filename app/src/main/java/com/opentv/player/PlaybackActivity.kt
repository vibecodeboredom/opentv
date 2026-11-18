package com.opentv.player

import android.app.AlertDialog
import android.content.res.Configuration
import android.content.Intent
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
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
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
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
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
 *   nothing up → open the left sidebar (channels/groups).
 * - RIGHT: sidebar up → activate the focused row; overlay up → move
 *   between strip tiles (same focus caveat as Left); nothing up → show
 *   the channel info overlay.
 * - LEFT/RIGHT during a recording (combined port, juniper-v10 7.1.0):
 *   seek ∓/± 10 s instead — overlay up with no strip tile focused, or
 *   nothing up. There is no channel list to open mid-recording, and
 *   INFO still opens the overlay there.
 * - UP/DOWN: move focus between rows/tiles/buttons (framework focus
 *   search; the sidebar's RecyclerView scrolls to the focused row).
 * - BACK: sidebar up → back/close; overlay up → hide it; else the
 *   system default (leave playback).
 * - Play/pause key (remote): toggle pause/resume. Channel up/down keys:
 *   next / previous channel.
 * - MEDIA_PREVIOUS (7.2.0): swap back to the channel watched before
 *   this one; pressing it again swaps back. The options menu carries
 *   the same swap for remotes without the key. With favorites
 *   zapping on (options menu), channel up/down cycle favorites only.
 * - MENU opens options (favorite + record). INFO toggles favorite.
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
        /** Dead streams are common in the playlist — auto-try another random channel this many times. */
        private const val MAX_AUTO_RETRY = 3
        /** 7.3.0 (Nova): a live stream that already played gets
         * this many same-stream reconnect attempts before the
         * error is shown — separate from MAX_AUTO_RETRY, which
         * hops channels for streams that never played. */
        private const val MAX_RECONNECT_ATTEMPTS = 3
        /** 7.3.0 (Nova): a live stream stuck in BUFFERING this
         * long counts as stalled and earns a reconnect attempt. */
        private const val STALL_TIMEOUT_MS = 12_000L
        /** 7.3.0 (Nova): backoff between reconnect attempts. */
        private val RECONNECT_BACKOFF_MS = longArrayOf(2_000L, 5_000L, 10_000L)
        /** 7.3.0 (Nova): sleepTimerMinutes sentinel — pause when
         * the current programme ends instead of after N minutes. */
        private const val SLEEP_END_OF_PROGRAMME = -1
        /** 7.3.0 (Nova): the LOW stream quality cap — adaptive
         * selection picks the best variant at or under it (or the
         * lowest available when every variant is above it). */
        private const val LOW_QUALITY_MAX_BITRATE = 400_000
        /** 7.3.1 (Nova): how long the audio-only hint stays up
         * on the black cover before it fades out. */
        private const val AUDIO_ONLY_HINT_MS = 4_000L
        /** 8.0.0 (Nova): the sleep fade-out window — in the last
         * minute before a sleep deadline the dim veil rises to
         * black, so playback pauses out of a fade, not a cut. */
        private const val SLEEP_FADE_MS = 60_000L
        /** Overlay auto-hides after this long with no key press. */
        private const val OVERLAY_TIMEOUT_MS = 10_000L
        /** 6.0.0-rc3: mobile overlay auto-hide — 5s idle, then it
         * fades away. TV keeps the 10s timeout above. */
        private const val MOBILE_OVERLAY_TIMEOUT_MS = 5_000L
        /** 6.0.0-rc3: mobile overlay fade-out duration. */
        private const val OVERLAY_FADE_MS = 200L
        /** Channel up/down flash: half the Left/Right overlay time, so
         * zapping past a channel shows its info only briefly. */
        private const val CHANNEL_SWITCH_OVERLAY_TIMEOUT_MS = 5_000L
        /** Hold OK this long to open the context menu instead of the guide. */
        private const val LONG_PRESS_MS = 600L
        /** 7.2.0 (Nova): stream info overlay refresh cadence. */
        private const val STREAM_INFO_UPDATE_MS = 1_000L
        /** 8.3.0 (Nova): live playback paused this long earns the
         * idle screensaver — long enough that a viewer who paused
         * to make tea never sees it, short enough that a forgotten
         * pause doesn't hold a frozen frame all evening. */
        private const val IDLE_SAVER_MS = 300_000L
        /** 8.3.0 (Nova): the screensaver clock re-reads the time
         * this often while up, so the minute it shows is never
         * stale by more than a few seconds. */
        private const val SCREENSAVER_CLOCK_MS = 20_000L
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
    private lateinit var infoNowDesc: TextView
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
    /** True while the overlay up is a channel up/down flash (short
     * timeout); the first Left/Right press upgrades it to the normal
     * Left/Right timeout instead. */
    private var overlayBrief = false
    /** Long-press OK: opens the context menu. Combined port
     * (juniper-v10 9.3.0): the action is a setting now — MENU is
     * the behaviour this always had; GUIDE and NOTHING are the
     * alternatives her accessibility pass added. */
    private val longPressRunnable = Runnable {
        longPressFired = true
        when (AppSettings.longPressOkAction(this)) {
            AppSettings.LongPressOkAction.GUIDE -> openGuide()
            AppSettings.LongPressOkAction.NOTHING -> Unit
            AppSettings.LongPressOkAction.MENU -> showOptions()
        }
    }
    private var longPressFired = false
    /** 7.0.0 (Nova): sleep timer — the minutes chosen (0 = off), the
     * wall-clock deadline it fires at, and the runnable that pauses
     * playback. The deadline survives a guide visit: onStop removes
     * the callback, onStart re-arms from the deadline. */
    private var sleepTimerMinutes: Int = 0
    private var sleepDeadlineMillis: Long = 0L
    /** Combined port (juniper-v10 7.1.0): playback speed for live
     * streams (1× default). Nova zaps in place, so the running
     * player keeps the speed across a zap by itself; a rebuilt
     * player (guide visit, Go live) gets it re-applied in
     * initializePlayer. Session-only, like Juniper's — her intent
     * carrying has no analogue here because nothing relaunches
     * this activity to change channel. */
    private var playbackSpeed: Float = 1f
    /** Combined port (juniper-v10 10.0.0): the options dialog
     * currently on screen, if any — entering PiP dismisses it
     * first, so it never floats over the small window. */
    private var optionsDialog: AlertDialog? = null
    private val sleepRunnable = Runnable { onSleepTimerFired() }
    /** 8.0.0 (Nova): the sleep fade-out — a runnable that starts
     * the dim veil's rise to black SLEEP_FADE_MS before the sleep
     * deadline, and a flag for "the veil is currently raised by
     * the fade" (above whatever night dim says), so a cancel or a
     * resume can put the veil back exactly where night dim had it.
     * The fade drives dimView's alpha directly — never setDim —
     * so the fade levels are never persisted as a dim choice. */
    private val sleepFadeRunnable = Runnable { startSleepFade() }
    private var sleepFaded: Boolean = false
    /** 8.0.0 (Nova): programme reminder prompts while watching.
     * Watch now zaps to the reminded channel in place, like a
     * channel picked in the sidebar. */
    private val reminderWatcher = ReminderWatcher(this) { reminder ->
        watchReminderChannel(reminder)
    }
    /** 7.1.0 (Nova): warms the previous / next channels' streams
     * while this one plays, so a zap to them starts faster. */
    private val streamPrefetcher = StreamPrefetcher()
    /** 7.2.0 (Nova): the channel watched before the current one —
     * the target of the previous-channel swap. Recorded by
     * switchToChannel only when the outgoing channel actually
     * played, so a dead stream the auto-retry abandons never
     * becomes the swap target. 7.2.1 (Nova): also persisted (see
     * PlaylistRepository.previousChannelId) and restored on
     * create, so the swap survives an app restart. */
    private var previousChannel: Channel? = null
    /** 7.2.0 (Nova): favorites zapping — channel up/down cycle
     * favorites only while on. Persisted in AppSettings (a
     * playback preference), loaded in onCreate. */
    private var favoritesZapMode: Boolean = false
    /** 7.2.0 (Nova): stream info overlay + night dim veil, bound
     * in onCreate. */
    private lateinit var streamInfo: TextView
    private lateinit var dimView: View
    /** 7.2.0 (Nova): stream info visibility. While on, the
     * updater re-reads the player's formats once a second — no
     * faster; it is a calm readout, not a scope. */
    private var streamInfoOn: Boolean = false
    private val streamInfoRunnable = object : Runnable {
        override fun run() {
            updateStreamInfo()
            if (streamInfoOn) infoHandler.postDelayed(this, STREAM_INFO_UPDATE_MS)
        }
    }
    /** 7.2.0 (Nova): night dim level in percent (0 = off).
     * 7.2.1 (Nova): persisted in AppSettings and restored on
     * create — 7.2.0 forgot it between sessions. */
    private var dimPercent: Int = 0
    /** 7.3.0 (Nova): audio-only mode — the picture is covered by
     * a black view while the player (and its sound) runs on.
     * Session-only: it exists for falling asleep, and any key
     * press ends it (see dispatchKeyEvent). */
    private lateinit var audioOnlyView: View
    private var audioOnlyOn: Boolean = false
    /** 7.3.0 (Nova): the key-UP matching a key-DOWN that restored
     * the picture is swallowed too, so the press that wakes the
     * screen does not also fire its normal action. */
    private var audioRestoreSwallowUp: Boolean = false
    /** 7.3.1 (Nova): the hint centred on the audio-only cover,
     * and a generation counter that keeps a fade scheduled by
     * one wake/sleep cycle from hiding the hint a later cycle
     * has just re-shown. */
    private lateinit var audioOnlyHint: TextView
    private var audioOnlyHintGeneration: Int = 0
    private val audioOnlyHintRunnable = Runnable {
        fadeAudioOnlyHint(audioOnlyHintGeneration)
    }
    /** 7.3.0 (Nova): live auto-reconnect — attempts spent on the
     * current stream, the "Reconnecting…" pill, the pending retry
     * and the stall watchdog. Reset whenever the stream reaches
     * READY again, and cancelled by a channel switch, a pause or
     * onStop, so retries never outlive the viewer's intent. */
    private lateinit var reconnectView: TextView
    private var reconnectAttempts: Int = 0
    private val reconnectRunnable = Runnable { restartCurrentStream() }
    private val stallWatchdogRunnable = Runnable { onStreamStalled() }
    /** 7.3.0 (Nova): stream quality selector — the activity owns
     * the player's track selector so the persisted mode (see
     * AppSettings.QUALITY_*) can be applied at build time and
     * changed live from the options menu. Only meaningful for
     * adaptive streams; a single-variant stream has nothing to
     * select and simply plays as before. */
    private var trackSelector: DefaultTrackSelector? = null
    private var qualityMode: Int = AppSettings.QUALITY_AUTO
    /** 8.3.0 (Nova): idle-pause screensaver — the black cover,
     * its clock / programme / channel lines, and whether it is
     * currently up. Armed only by a deliberate pause on a live
     * channel (see onIsPlayingChanged); any key press takes it
     * down and is swallowed whole, like the audio-only wake. */
    private lateinit var idleSaverView: View
    private lateinit var idleSaverClock: TextView
    private lateinit var idleSaverTitle: TextView
    private lateinit var idleSaverChannel: TextView
    private lateinit var idleSaverNext: TextView
    private var idleSaverShowing: Boolean = false
    private var saverRestoreSwallowUp: Boolean = false
    private val idleSaverRunnable = Runnable { showIdleSaver() }
    private val saverClockRunnable = object : Runnable {
        override fun run() {
            if (!idleSaverShowing) return
            updateSaverClock()
            updateSaverNext()
            infoHandler.postDelayed(this, SCREENSAVER_CLOCK_MS)
        }
    }
    /** 8.3.0 (Nova): surf mode — while on, a hop to the next
     * zap-eligible channel lands every surf interval through
     * the ordinary zap path. Any key press ends it (the press is
     * swallowed, like the other wakes); the sleep timer, a
     * reminder zap, a pause and onStop end it too. */
    private lateinit var surfHint: TextView
    private var surfMode: Boolean = false
    private var surfStopSwallowUp: Boolean = false
    private val surfRunnable = object : Runnable {
        override fun run() {
            if (!surfMode) return
            switchToAdjacentChannel(1)
            infoHandler.postDelayed(this, surfIntervalMs())
        }
    }

    /** 8.3.1 (Nova): the current surf hop interval in
     * milliseconds — the persisted choice (see
     * AppSettings.surfIntervalSeconds), re-read on every hop so
     * a new choice takes effect on the very next one. */
    private fun surfIntervalMs(): Long =
        AppSettings.surfIntervalSeconds(this) * 1_000L
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
        infoNowDesc = findViewById(R.id.info_now_desc)
        infoNext = findViewById(R.id.info_next)
        infoProgress = findViewById(R.id.info_progress)
        // 9.0.0 (Nova): the info overlay's programme progress
        // bar takes the chosen accent (see AccentDrawables).
        infoProgress.progressDrawable = AccentDrawables.progressDrawable(this)
        infoStrip = findViewById(R.id.info_strip)
        transportBar = findViewById(R.id.transport_bar)
        transportTitle = findViewById(R.id.transport_title)
        transportPlayPause = findViewById(R.id.transport_play_pause)
        transportRecord = findViewById(R.id.transport_record)
        transportFavorite = findViewById(R.id.transport_favorite)
        pausedCluster = findViewById(R.id.paused_cluster)
        streamInfo = findViewById(R.id.stream_info)
        dimView = findViewById(R.id.dim_view)
        audioOnlyView = findViewById(R.id.audio_only_view)
        audioOnlyHint = findViewById(R.id.audio_only_hint)
        reconnectView = findViewById(R.id.reconnect_view)
        // 8.3.0 (Nova): surf hint pill + idle screensaver views.
        surfHint = findViewById(R.id.surf_hint)
        idleSaverView = findViewById(R.id.idle_saver_view)
        idleSaverClock = findViewById(R.id.idle_saver_clock)
        idleSaverTitle = findViewById(R.id.idle_saver_title)
        idleSaverChannel = findViewById(R.id.idle_saver_channel)
        idleSaverNext = findViewById(R.id.idle_saver_next)
        // 7.2.0 (Nova): on mobile the top-end corner belongs to the
        // Guide button — drop the stream info panel below it.
        if (DeviceType.isMobile(this)) {
            val lp = streamInfo.layoutParams as FrameLayout.LayoutParams
            lp.topMargin = dp(72)
            streamInfo.layoutParams = lp
        }
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
            onChannelSelected = {
                // 8.3.0 (Nova): a channel picked by hand ends
                // surf mode — surfing on from a deliberate choice
                // would zap away from it at the next hop.
                stopSurfMode()
                switchToChannel(it)
            },
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
        // Started by a channel up/down press: flash the info overlay
        // briefly once the EPG is in, instead of the now/next toast.
        val briefInfo = intent.getBooleanExtra(EXTRA_SHOW_BRIEF_INFO, false)

        if (ch == null && recordingUri == null) {
            finish()
            return
        }
        channel = ch

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
        // 7.2.0 (Nova): favorites zapping is a persisted playback
        // preference — restore it for this session.
        favoritesZapMode = AppSettings.favoritesZap(this)
        // 7.2.1 (Nova): night dim is a persisted playback
        // preference too — reapply the remembered level instead
        // of starting every session undimmed.
        setDim(AppSettings.nightDimPercent(this))
        // 7.3.0 (Nova): stream quality is persisted too — the
        // track selector built in initializePlayer reads it.
        qualityMode = AppSettings.qualityMode(this)
        // 7.2.1 (Nova): restore the previous-channel swap target
        // recorded by the last session. The saved id is resolved
        // against the current playlist — a channel that has left
        // the playlist simply never resolves, leaving the swap
        // exactly as unavailable as it was in 7.2.0. A switch
        // made while the playlist loads wins: it records (and
        // persists) its own previous channel, and both guards
        // below then drop this restore.
        if (recordingUri == null && ch != null) {
            val startChannelId = ch.id
            lifecycleScope.launch {
                val savedId = PlaylistRepository.previousChannelId(this@PlaybackActivity)
                if (savedId.isEmpty() || savedId == startChannelId) return@launch
                val channels = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
                    .getOrNull() ?: return@launch
                if (previousChannel != null || channel?.id != startChannelId) return@launch
                previousChannel = channels.firstOrNull { it.id == savedId }
            }
        }
        updateTransportIcons()
        // 6.0.0-rc1: touch controls on mobile (tap to toggle the
        // overlay, swipe to change channel, big transport buttons).
        if (DeviceType.isMobile(this)) setupMobileTouch()

        // Best-effort EPG: show Now / Next for this channel once it loads.
        if (ch != null) {
            refreshNowNext(briefInfo)
        }
    }

    /**
     * Load the EPG and fill in Now / Next for the current channel:
     * refresh the info overlay if it's up, flash it briefly on a
     * channel up/down switch, or toast the now/next otherwise.
     * Extracted from onCreate in 7.0.0 (Nova) — an in-place channel
     * switch needs the exact same fill-in for the new channel.
     */
    private fun refreshNowNext(briefInfo: Boolean) {
        val ch = channel ?: return
        val targetId = ch.id
        lifecycleScope.launch {
            val epg = PlaylistRepository.loadEpg(this@PlaybackActivity)
            // 7.0.0 (Nova): dropped when the user has already zapped
            // on — the newer switch started its own fill-in, and a
            // stale one must not overwrite the new channel's info.
            if (channel?.id != targetId) return@launch
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
        // sitting on a black screen. The min/max buffers stay generous
        // so a slow stream doesn't rebuffer right after starting.
        val fastStartLoadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs */ 10_000,
                /* maxBufferMs */ 30_000,
                /* bufferForPlaybackMs */ 500,
                /* bufferForPlaybackAfterRebufferMs */ 1_500
            )
            .build()
        // 7.3.0 (Nova): the activity builds the track selector
        // itself (instead of letting the builder default one) so
        // the persisted quality mode can be applied now and
        // changed live later.
        val selector = DefaultTrackSelector(this)
        trackSelector = selector
        applyQualityMode(selector)
        val player = if (recordingUri != null) {
            // Playback from the local download cache.
            val mediaSourceFactory = DefaultMediaSourceFactory(this)
                .setDataSourceFactory(RecordingManager.cacheDataSourceFactory(this))
            ExoPlayer.Builder(this)
                .setMediaSourceFactory(mediaSourceFactory)
                .setLoadControl(fastStartLoadControl)
                .setTrackSelector(selector)
                .build()
        } else {
            ExoPlayer.Builder(this)
                .setLoadControl(fastStartLoadControl)
                .setTrackSelector(selector)
                .build()
        }
        this.player = player
        playerView.player = player
        everPlayed = false
        autoRetrying = false
        // 7.3.0 (Nova): a rebuilt player starts with a clean
        // reconnect slate — the old player's pending retries and
        // stall watchdog must not fire into the new one.
        cancelReconnect()
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .build()
        player.setMediaItem(MediaItem.Builder().setUri(uri).setMediaMetadata(metadata).build())
        // 9.0.0 (Nova): recording resume — if the last viewing
        // of this recording stopped inside the resume window
        // (see PlaylistRepository.RecordingProgress), start
        // there instead of at the beginning. The seek lands
        // before prepare, so playback opens on the resumed
        // frame directly; the toast names the position.
        val resumeId = recordingId
        if (resumeId != null) {
            val saved = PlaylistRepository.recordingProgressFor(this, resumeId)
            if (saved != null && saved.isResumable()) {
                player.seekTo(saved.positionMs)
                Toast.makeText(
                    this,
                    getString(
                        R.string.recording_resumed_from,
                        GuideData.formatElapsedClock(saved.positionMs)
                    ),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
        // 9.1.0 (Nova): catch-up resume — the recording idea
        // carried to replays. The saved point is keyed by the
        // channel + the programme's start, so it only ever
        // applies to this exact replay. The archive window is
        // checked against the real channel (CatchUp knows its
        // catch-up days): a programme whose window has passed
        // loses its point here, pruned on the way out.
        if (catchupUrl != null) {
            val resumeChannel = ch
            val saved = resumeChannel?.let {
                PlaylistRepository.catchupProgressFor(
                    this, it.id, catchupStartMillis
                )
            }
            if (saved != null && resumeChannel != null) {
                val prog = catchupProgramme()
                if (prog != null && CatchUp.isAvailable(resumeChannel, prog)) {
                    if (saved.isResumable()) {
                        player.seekTo(saved.positionMs)
                        Toast.makeText(
                            this,
                            getString(
                                R.string.recording_resumed_from,
                                GuideData.formatElapsedClock(saved.positionMs)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } else {
                    PlaylistRepository.clearCatchupProgress(
                        this, resumeChannel.id, catchupStartMillis
                    )
                }
            }
        }
        player.prepare()
        player.play()
        // Combined port (juniper-v10 7.1.0): restore the chosen
        // playback speed on a rebuilt player (returning from the
        // guide rebuilds it; an in-place zap never does, so the
        // speed simply stays on the running player there).
        if (playbackSpeed != 1f) player.setPlaybackSpeed(playbackSpeed)
        sessionStartMillis = System.currentTimeMillis()
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                // 9.0.0 (Nova): a recording played to the end is
                // finished — retire its resume point, so the
                // Continue watching row drops it and a later play
                // starts from the beginning.
                if (playbackState == Player.STATE_ENDED) {
                    recordingId?.let {
                        PlaylistRepository.clearRecordingProgress(
                            this@PlaybackActivity, it
                        )
                    }
                    // 9.1.0 (Nova): a replay watched to the end
                    // is finished too — retire its resume point.
                    if (catchupUrl != null) {
                        channel?.id?.let {
                            PlaylistRepository.clearCatchupProgress(
                                this@PlaybackActivity, it, catchupStartMillis
                            )
                        }
                    }
                }
                // 7.3.0 (Nova): auto-reconnect bookkeeping. READY
                // means the stream is healthy again — clear the
                // attempts and the indicator. BUFFERING on a live
                // stream that already played arms the stall
                // watchdog; leaving BUFFERING disarms it.
                if (playbackState == Player.STATE_READY) {
                    infoHandler.removeCallbacks(stallWatchdogRunnable)
                    if (reconnectAttempts > 0 || reconnectView.visibility == View.VISIBLE) {
                        cancelReconnect()
                    }
                } else if (playbackState == Player.STATE_BUFFERING) {
                    if (everPlayed && recordingUri == null && catchupUrl == null) {
                        infoHandler.removeCallbacks(stallWatchdogRunnable)
                        infoHandler.postDelayed(stallWatchdogRunnable, STALL_TIMEOUT_MS)
                    }
                } else {
                    infoHandler.removeCallbacks(stallWatchdogRunnable)
                }
                if (playbackState == Player.STATE_READY && !everPlayed) {
                    everPlayed = true
                    // First real playback: this channel counts as watched,
                    // and it becomes the channel launch resumes next time.
                    channel?.let {
                        PlaylistRepository.recordRecentChannel(this@PlaybackActivity, it.id)
                        PlaylistRepository.setLastChannelId(this@PlaybackActivity, it.id)
                        // Combined port (juniper-v10 9.3.0/9.3.1):
                        // spoken channel names — announce on first
                        // real playback, never for a stream that
                        // fails to start, and never twice for one
                        // switch (everPlayed guards this block).
                        // No-ops unless the setting is on.
                        ChannelAnnouncer.announce(this@PlaybackActivity, it.name)
                    }
                    // 7.1.0 (Nova): this channel is playing — warm
                    // its neighbours' streams for a faster zap.
                    prefetchAdjacentChannels()
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
                // 8.0.0 (Nova): playback resuming after a sleep
                // fade-out puts the veil back to its night-dim
                // level — the fade belonged to the timer that
                // paused, not to the show now playing.
                if (isPlaying && sleepFaded) setDim(dimPercent)
                // 8.3.0 (Nova): idle-pause screensaver bookkeeping.
                // Playing again cancels a pending saver and takes
                // a shown one down; a deliberate pause (playWhenReady
                // off — buffering keeps it on, so a struggling
                // stream never arms the saver) starts the countdown.
                if (isPlaying) {
                    infoHandler.removeCallbacks(idleSaverRunnable)
                    if (idleSaverShowing) hideIdleSaver()
                } else if (!player.playWhenReady) {
                    armIdleSaver()
                }
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
                // 7.3.0 (Nova): a live stream that DID play and then
                // failed gets reconnected to the SAME stream with
                // backoff — a dropped connection mid-show should not
                // cost the viewer their channel. Recordings and
                // catch-up keep the plain error below.
                if (everPlayed && recordingUri == null && catchupUrl == null &&
                    reconnectAttempts < MAX_RECONNECT_ATTEMPTS
                ) {
                    scheduleReconnect()
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
        // 7.0.0 (Nova): re-arm the sleep timer across a guide visit —
        // onStop removed the callback, but the deadline survives, so
        // a timer set before opening the guide still fires on time
        // (or immediately, if it came due while away).
        if (sleepDeadlineMillis > 0L) {
            val remaining = sleepDeadlineMillis - System.currentTimeMillis()
            infoHandler.removeCallbacks(sleepRunnable)
            if (remaining > 0L) {
                infoHandler.postDelayed(sleepRunnable, remaining)
                // 8.0.0 (Nova): the fade re-arms with the timer —
                // a deadline that fell inside the fade window while
                // the viewer was in the guide starts its fade now.
                scheduleSleepFade()
            } else {
                onSleepTimerFired()
            }
        }
        // 8.0.0 (Nova): reminder prompts run while watching (see
        // ReminderWatcher); onStop stops them.
        reminderWatcher.start()
        // 7.2.0 (Nova): the stream info updater stops in onStop —
        // restart it if the overlay is still toggled on.
        if (streamInfoOn) {
            streamInfo.visibility = View.VISIBLE
            infoHandler.removeCallbacks(streamInfoRunnable)
            streamInfoRunnable.run()
        }
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
        // 8.3.0 (Nova): the idle screensaver is up — ANY key
        // press takes it down and is consumed whole (down and
        // its matching up), exactly like the audio-only wake
        // below: the press that dismisses the saver must not
        // also resume playback, zap or open the guide. Checked
        // first because the saver is the topmost layer.
        if (idleSaverShowing) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                hideIdleSaver()
                saverRestoreSwallowUp = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && saverRestoreSwallowUp) {
                saverRestoreSwallowUp = false
                return true
            }
        }
        // 7.3.0 (Nova): audio-only mode — ANY key press brings the
        // picture back and ends the mode. The press is consumed
        // whole (down and its matching up), so waking the screen
        // never also zaps, pauses or opens the guide.
        if (audioOnlyOn) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                setAudioOnly(false)
                audioRestoreSwallowUp = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && audioRestoreSwallowUp) {
                audioRestoreSwallowUp = false
                return true
            }
        }
        // 8.3.0 (Nova): surf mode — ANY key press ends surfing
        // and stays on the current channel. The press is consumed
        // whole like the wakes above: the key that stops the surf
        // doesn't also zap, pause or open the menu (a long-press
        // OK included — its timer never arms).
        if (surfMode) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                stopSurfMode()
                surfStopSwallowUp = true
                return true
            }
            if (event.action == KeyEvent.ACTION_UP && surfStopSwallowUp) {
                surfStopSwallowUp = false
                return true
            }
        }
        val sidebarUp = channelSidebar.isVisible()
        val overlayUp = overlay.visibility == View.VISIBLE
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    okDownSidebarUp = sidebarUp
                    okDownOverlayUp = overlayUp
                    // Long-press OK (nothing up) opens the context menu.
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
                        // Combined port (juniper-v10 7.1.1): during
                        // a recording, Left/Right seeks ±10s when no
                        // strip tile has focus — the transport
                        // buttons do little while a recording plays
                        // (prev/next/favorite need a channel), so
                        // steering focus between them is pointless;
                        // Up/Down still moves focus. Live keeps the
                        // framework focus movement below.
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
                    // skip ±10s (combined port, juniper-v10 7.1.0) —
                    // there is no channel list to open, and the info
                    // overlay has its own entry point (INFO) there.
                    // Live: Left opens the sidebar, Right shows the
                    // channel info overlay.
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
        // 8.3.0 (Nova): a tap dismisses the idle screensaver on
        // mobile, the same any-key wake the remote gets — and is
        // consumed, so the tap doesn't land on the transport bar
        // underneath and resume playback in the same gesture.
        if (idleSaverShowing && ev.action == MotionEvent.ACTION_DOWN) {
            hideIdleSaver()
            return true
        }
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
        // 8.3.0 (Nova): pausing by hand ends surf mode — keys
        // already end it in dispatchKeyEvent, but on mobile this
        // is reachable by touch with surf running, and a hop
        // landing after the pause would restart playback the
        // viewer just stopped.
        stopSurfMode()
        // 7.3.0 (Nova): pausing on purpose ends any reconnect in
        // progress — retries only run while the viewer wants
        // playback.
        if (p.isPlaying) cancelReconnect()
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
        // 9.2.1 (Nova): amber while a favorite, white when not —
        // the same state colours as the guide's and mobile's
        // stars. Filled-vs-outline alone, both in white at 30dp,
        // was too subtle to read as a state across a room; the
        // record button below already works exactly this way
        // (colour is the state).
        transportFavorite.setColorFilter(
            if (isFav) 0xFFFFC107.toInt() else 0xFFFFFFFF.toInt()
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

    /**
     * 7.2.0 (Nova): is [ch] in the set channel up/down cycles
     * through? Hidden channels are always out; with favorites
     * zapping on, only favorites are in. Shared by the zap itself
     * and the neighbour prefetch, so the warm-up always matches
     * where a zap can actually land.
     */
    private fun zapEligible(ch: Channel, hidden: Set<String>, favorites: Set<String>): Boolean =
        ch.id !in hidden && (!favoritesZapMode || ch.id in favorites)

    /**
     * 9.1.0 (Nova): the favorites zap cycle — the zap-eligible
     * favorites in the user's custom order (playlist order until
     * one is set). Shared by the zap itself and the neighbour
     * prefetch, so the warm-up always matches where a zap can
     * land. Only meaningful with favorites zapping on; the list
     * is materialised because the cycle order is no longer the
     * playlist's — the favorites are few, unlike the playlist
     * the allocation-free walk below exists for.
     */
    private fun favoritesZapCycle(
        channels: List<Channel>,
        hidden: Set<String>,
        favorites: Set<String>
    ): List<Channel> = PlaylistRepository.applyFavoriteOrder(
        this,
        channels.filter { zapEligible(it, hidden, favorites) }
    )

    /** Switch to the channel before / after the current one in the playlist. */
    private fun switchToAdjacentChannel(delta: Int) {
        val currentId = channel?.id ?: return
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val hidden = PlaylistRepository.hiddenChannels(this@PlaybackActivity)
            val favorites = PlaylistRepository.favorites(this@PlaybackActivity)
            // 9.1.0 (Nova): with favorites zapping on, the cycle
            // is the favorites in the user's custom order — a
            // small materialised list, indexed directly (see
            // favoritesZapCycle). The allocation-free walk below
            // only still runs for the full-playlist cycle, where
            // the ~1500-element list makes it matter.
            val next: Channel? = if (favoritesZapMode) {
                val cycle = favoritesZapCycle(channels, hidden, favorites)
                if (cycle.isEmpty()) {
                    // 7.2.0 (Nova): zapping with no favorites at
                    // all — say so instead of falling back to the
                    // full list, which would zap straight out of
                    // the mode.
                    Toast.makeText(
                        this@PlaybackActivity,
                        R.string.favorites_zap_empty,
                        Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                }
                // 7.2.0 (Nova): the current channel may sit
                // outside the cycle (the mode was just switched
                // on, or the favorite was removed from the
                // guide) — a zap jumps into the set, first
                // favorite going up, last going down.
                val idx = cycle.indexOfFirst { it.id == currentId }
                val target = when {
                    idx >= 0 -> (idx + delta).mod(cycle.size)
                    delta < 0 -> cycle.size - 1
                    else -> 0
                }
                cycle[target]
            } else {
                // 4.9.5: find the adjacent channel without building a
                // filtered copy of the whole playlist (was a ~1500-element
                // list per CH+/CH- press). Two allocation-free passes:
                // count the visible channels and the current channel's
                // visible position, then pick the channel at the target
                // visible position. Falls back to the full list when every
                // channel is hidden, exactly like the old ifEmpty.
                // 7.2.0 (Nova): "visible" now goes through zapEligible.
                var visibleIndex = -1
                var visibleCount = 0
                for (ch in channels) {
                    if (!zapEligible(ch, hidden, favorites)) continue
                    if (ch.id == currentId) visibleIndex = visibleCount
                    visibleCount++
                }
                if (visibleCount > 0) {
                    if (visibleIndex < 0) return@launch
                    val target = (visibleIndex + delta).mod(visibleCount)
                    var seen = 0
                    var found: Channel? = null
                    for (ch in channels) {
                        if (!zapEligible(ch, hidden, favorites)) continue
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
            }
            val pick = next ?: return@launch
            if (favoritesZapMode && pick.id == currentId) {
                // 7.2.0 (Nova): the only favorite is the channel
                // already playing — restarting it on every CH press
                // would be noise. Just flash its info.
                showChannelInfo(brief = true)
                return@launch
            }
            // Channel up/down restarts playback on the new channel and
            // flashes its info briefly (4.7.3) instead of the normal
            // Left/Right overlay duration.
            switchToChannel(pick, briefInfo = true)
        }
    }

    /**
     * 7.2.0 (Nova): swap back to the channel watched before this
     * one (the remote's MEDIA_PREVIOUS key, or the options menu).
     * The swap is an ordinary in-place switch, and switchToChannel
     * records the channel being left — so pressing it again swaps
     * back, toggling between the two channels.
     */
    private fun swapToPreviousChannel() {
        if (recordingUri != null) return
        val prev = previousChannel
        if (prev == null || prev.id == channel?.id) {
            Toast.makeText(this, R.string.previous_channel_none, Toast.LENGTH_SHORT).show()
            return
        }
        switchToChannel(prev, briefInfo = true)
    }

    /**
     * 7.1.0 (Nova): warm the streams of the channels either side of
     * the current one (the same visible order channel up/down uses),
     * so zapping to them starts faster. Live channels only — a
     * recording reads from disk. The warm-up itself is capped and
     * silent (see StreamPrefetcher); this only picks the URLs,
     * next channel first — CH+ is the direction viewers zap most,
     * so it gets the head start.
     *
     * 7.2.1 (Nova): catch-up sessions warm their neighbours too.
     * 7.2.0 skipped them on the theory that a replay's neighbours
     * are not zap targets — but a zap out of catch-up goes live on
     * the neighbouring channel (switchToChannel clears the catch-up
     * state), so they are exactly the zap targets. This runs once
     * the catch-up stream is playing (the READY callback below),
     * and the capped, one-at-a-time warm-up cannot disturb the
     * replay it runs beside.
     */
    private fun prefetchAdjacentChannels() {
        if (recordingUri != null) return
        val currentId = channel?.id ?: return
        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
            val channels = result.getOrNull() ?: return@launch
            val hidden = PlaylistRepository.hiddenChannels(this@PlaybackActivity)
            val favorites = PlaylistRepository.favorites(this@PlaybackActivity)
            val neighbours: List<Channel> = if (favoritesZapMode) {
                // 9.1.0 (Nova): with favorites zapping on, the
                // cycle is the favorites in the user's custom
                // order — the neighbours come from the same
                // materialised list the zap indexes (see
                // favoritesZapCycle). A current channel outside
                // the cycle has no favourites neighbours to warm,
                // and a cycle of one has none either.
                val cycle = favoritesZapCycle(channels, hidden, favorites)
                val idx = cycle.indexOfFirst { it.id == currentId }
                if (cycle.size > 1 && idx >= 0) {
                    listOf(
                        cycle[(idx + 1).mod(cycle.size)],
                        cycle[(idx - 1).mod(cycle.size)]
                    ).distinctBy { it.id }
                } else {
                    emptyList()
                }
            } else {
                // Same two-pass visible-order math as
                // switchToAdjacentChannel, extended to both neighbours:
                // count the visible channels and the current channel's
                // visible position, then pick the channels at the
                // neighbouring visible positions. 7.2.0 (Nova): the
                // order is zapEligible's.
                var visibleIndex = -1
                var visibleCount = 0
                for (ch in channels) {
                    if (!zapEligible(ch, hidden, favorites)) continue
                    if (ch.id == currentId) visibleIndex = visibleCount
                    visibleCount++
                }
                if (visibleCount > 1 && visibleIndex >= 0) {
                    val prevTarget = (visibleIndex - 1).mod(visibleCount)
                    val nextTarget = (visibleIndex + 1).mod(visibleCount)
                    var prev: Channel? = null
                    var next: Channel? = null
                    var seen = 0
                    for (ch in channels) {
                        if (!zapEligible(ch, hidden, favorites)) continue
                        if (seen == prevTarget) prev = ch
                        if (seen == nextTarget) next = ch
                        seen++
                    }
                    listOfNotNull(next, prev).distinctBy { it.id }
                } else if (visibleCount == 0 && channels.size > 1) {
                    // Every channel hidden: the full-list fallback,
                    // exactly like switchToAdjacentChannel.
                    val idx = channels.indexOfFirst { it.id == currentId }
                    if (idx < 0) {
                        emptyList()
                    } else {
                        listOf(
                            channels[(idx + 1).mod(channels.size)],
                            channels[(idx - 1).mod(channels.size)]
                        ).distinctBy { it.id }
                    }
                } else {
                    // Zero or one visible channel: no neighbours to warm.
                    emptyList()
                }
            }
            // A slow playlist load must not warm the neighbours of
            // a channel the viewer has already zapped away from.
            if (channel?.id != currentId) return@launch
            streamPrefetcher.prefetch(neighbours.map { it.url })
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
        // 7.2.0 (Nova): favorites zapping rides along on the group
        // line, so every zap flash and overlay shows the mode is
        // on — a star and the word, nothing louder.
        val group = ch?.group ?: ""
        infoGroup.text = if (favoritesZapMode && recordingUri == null) {
            listOf(group, "★ ${getString(R.string.favorites)}")
                .filter { it.isNotEmpty() }
                .joinToString("  •  ")
        } else {
            group
        }
        // DateFormat.format(long) takes the millis directly — the old
        // code wrapped each one in a throwaway Date (3 per overlay open).
        infoDateTime.text = dateTimeFormat.format(now)
        // 7.1.0 (Nova): the channel number rides along on the name
        // line (the mobile centre panel already formats it this
        // way). Before, the number only appeared inside the now
        // meta line, so an overlay with no EPG yet — a fresh zap
        // flash, say — showed no number at all.
        val numPrefix = if (ch != null && ch.number > 0) "${ch.number}  " else ""
        infoChannelName.text = "$numPrefix$name"
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
            // 9.3.1 (Nova): the current programme's description,
            // when the guide carries one (9.3.0 data) — same
            // treatment as the guide's details header: shown
            // verbatim, hidden when empty so the overlay keeps
            // its old shape.
            if (nowP.desc.isNotEmpty()) {
                infoNowDesc.text = nowP.desc
                infoNowDesc.visibility = View.VISIBLE
            } else {
                infoNowDesc.visibility = View.GONE
            }
            val span = (nowP.stopMillis - nowP.startMillis).coerceAtLeast(1L)
            infoProgress.progress = ((now - nowP.startMillis) * 100 / span).toInt().coerceIn(0, 100)
            infoProgress.visibility = if (catchupUrl != null) View.GONE else View.VISIBLE
        } else {
            infoNowTitle.text = getString(R.string.overlay_no_info)
            infoNowMeta.text = ""
            infoNowMeta.visibility = View.GONE
            infoNowDesc.visibility = View.GONE
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
        // 8.3.0 (Nova): while surfing, every flash carries the
        // how-to-stop hint; a normal flash never shows it. The
        // hint hides with the overlay (see hideOverlay).
        surfHint.visibility = if (surfMode) View.VISIBLE else View.GONE
        // 5.0.0 — neat animations: the overlay fades in quickly
        // (150ms) instead of popping in, so channel switches feel
        // smoother. Fast enough to never delay the info itself.
        // Cancel any fade-out in flight first, or its end action
        // would hide the overlay again after this show.
        overlay.animate().cancel()
        overlay.alpha = 0f
        overlay.animate().alpha(1f).setDuration(150).start()
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
        // 8.3.0 (Nova): the surf hint belongs to the flash — it
        // comes down with it, instantly (the pill is a caption,
        // not part of the overlay's fade).
        surfHint.visibility = View.GONE
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
     * is reset so the next show starts clean. */
    private fun fadeOutOverlayView(view: View) {
        if (view.visibility != View.VISIBLE) return
        view.animate().cancel()
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

    /** 6.0.0-rc3: fade one mobile overlay view in (150ms). */
    private fun showMobileView(view: View) {
        view.animate().cancel()
        view.visibility = View.VISIBLE
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
        stripTiles.firstOrNull { it.isFocused }?.let {
            it.performClick()
            return
        }
        // 9.2.1 (Nova): the transport buttons below the strip
        // (play/pause, prev/next, record, favorite) are focusable
        // too, and this dispatcher owns OK while the overlay is
        // up — the ACTION_DOWN was consumed above, so a focused
        // transport button never sees the key and its click
        // listener never fires. On a TV remote the buttons were
        // dead (the favorite star never toggled, so it never
        // changed appearance either); touch was unaffected,
        // which is how it survived. Give the focused clickable
        // view its click, like a normal dispatch would.
        val focused = currentFocus
        if (focused != null && focused.isClickable) {
            focused.performClick()
        }
    }

    /**
     * Switch playback to the given channel (sidebar, strip recents,
     * channel up/down, dead-stream auto-retry).
     *
     * 7.0.0 (Nova): the switch happens IN PLACE. The old code started
     * a fresh PlaybackActivity and finished this one, so every zap
     * paid a full activity create, a from-scratch ExoPlayer build and
     * an EPG reload before the first frame. Now the running player is
     * re-pointed at the new channel and only the per-channel state
     * (now/next, catch-up, retry count, session clock) resets — the
     * same trick goLive() already used for catch-up. Recording
     * playback keeps the restart path: it has no channel list, and
     * leaving it for a live channel is a mode change, not a zap.
     */
    private fun switchToChannel(
        pick: Channel,
        briefInfo: Boolean = false,
        retryCount: Int = 0
    ) {
        hideOverlay()
        hideSidebar()
        // 7.1.0 (Nova): the old channel's stream warm-ups are stale
        // the moment a switch starts — drop them; the new channel's
        // first READY starts fresh ones for its own neighbours.
        streamPrefetcher.cancel()
        // 7.3.0 (Nova): a zap ends any reconnect in progress — the
        // retries belong to the old channel's stream.
        cancelReconnect()
        if (recordingUri != null) {
            val intent = Intent(this, PlaybackActivity::class.java)
            intent.putExtra(EXTRA_CHANNEL, pick)
            if (briefInfo) intent.putExtra(EXTRA_SHOW_BRIEF_INFO, true)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            finish()
            return
        }
        // Log the outgoing channel's watch session — onStop does this
        // when the activity leaves, but an in-place switch never stops.
        logWatchSession()
        // 7.2.0 (Nova): remember the outgoing channel for the
        // previous-channel swap — but only if it actually played.
        // A stream that never reached READY (the dead channel the
        // auto-retry is abandoning, or a zap straight through) is
        // not a channel the viewer was watching.
        if (everPlayed) {
            channel?.let {
                if (it.id != pick.id) {
                    previousChannel = it
                    // 7.2.1 (Nova): persist alongside the swap
                    // target itself, so a restart restores it.
                    PlaylistRepository.setPreviousChannelId(this, it.id)
                }
            }
        }
        channel = pick
        // 9.1.0 (Nova): leaving a replay mid-watch — remember
        // where it got to before the catch-up state is cleared,
        // while the player still reports the replay's position.
        saveCatchupProgress()
        // A zap always goes live: catch-up state belongs to the old
        // channel's replay, never to the new channel.
        catchupUrl = null
        catchupTitle = null
        catchupStartMillis = 0L
        catchupStopMillis = 0L
        catchupFallbackTried = false
        nowProgramme = null
        nextProgramme = null
        liveNow = null
        liveNext = null
        everPlayed = false
        autoRetrying = false
        this.retryCount = retryCount
        guideLaunched = false
        // Keep the intent honest: the player listener reads
        // EXTRA_SHOW_BRIEF_INFO from it when playback starts.
        val newIntent = Intent(this, PlaybackActivity::class.java)
        newIntent.putExtra(EXTRA_CHANNEL, pick)
        if (briefInfo) newIntent.putExtra(EXTRA_SHOW_BRIEF_INFO, true)
        if (retryCount > 0) newIntent.putExtra(EXTRA_RETRY_COUNT, retryCount)
        setIntent(newIntent)
        val p = player
        if (p != null) {
            val metadata = MediaMetadata.Builder()
                .setTitle(pick.name)
                .setSubtitle(pick.group)
                .build()
            p.setMediaItem(
                MediaItem.Builder().setUri(pick.url).setMediaMetadata(metadata).build()
            )
            p.prepare()
            p.play()
            sessionStartMillis = System.currentTimeMillis()
        } else {
            initializePlayer()
        }
        favoritesCache = PlaylistRepository.favorites(this)
        updateTransportIcons()
        refreshNowNext(briefInfo)
        // 7.1.0 (Nova): a channel up/down zap flashes the info
        // overlay NOW — new channel's number, name and logo — instead
        // of waiting for refreshNowNext's EPG load to land. On a slow
        // guide load the zap used to show nothing but the buffering
        // spinner until then. When the EPG does land, it finds the
        // overlay visible and refreshes it in place with the
        // now/next titles (see refreshNowNext), keeping the brief
        // flash timing.
        if (briefInfo) showChannelInfo(brief = true)
    }

    /** 8.0.0 (Nova): a reminder prompt's Watch now — zap to the
     * reminded channel in place. The playlist cache is warm in
     * every normal flow (playback was launched from it); when it
     * somehow isn't, load it rather than failing the tap. A channel
     * that has left the playlist gets the plain toast. */
    private fun watchReminderChannel(reminder: PlaylistRepository.Reminder) {
        // 8.3.0 (Nova): a reminder zap is a deliberate switch —
        // it ends surf mode, or the next hop would carry the
        // viewer away from the programme they asked to be
        // reminded about.
        stopSurfMode()
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            val ch = cached.firstOrNull { it.id == reminder.channelId }
            if (ch != null) {
                if (ch.id != channel?.id) switchToChannel(ch, briefInfo = true)
            } else {
                Toast.makeText(
                    this, R.string.reminder_channel_gone, Toast.LENGTH_SHORT
                ).show()
            }
            return
        }
        lifecycleScope.launch {
            val loaded = PlaylistRepository.loadPlaylist(this@PlaybackActivity)
                .getOrNull()
            val ch = loaded?.firstOrNull { it.id == reminder.channelId }
            if (ch != null) {
                if (ch.id != channel?.id) switchToChannel(ch, briefInfo = true)
            } else {
                Toast.makeText(
                    this@PlaybackActivity,
                    R.string.reminder_channel_gone,
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    /** 5.0.0: leave a catch-up replay and return to the live stream
     * of the same channel (the strip's "Go live" tile). Rebuilds the
     * player on the live URL and shows the live info panel. */
    private fun goLive() {
        if (channel == null) return
        // 9.1.0 (Nova): going live leaves the replay — remember
        // where it got to before the player is released.
        saveCatchupProgress()
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
        startActivity(intent)
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
                // 7.0.0 (Nova): in-place switch with the retry count
                // carried over (was a full activity restart).
                switchToChannel(pick, retryCount = retryCount + 1)
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
                toggleFavorite()
                updateTransportIcons()
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                // 7.2.0 (Nova): the remote's previous-channel key.
                swapToPreviousChannel()
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

    private fun showOptions() {
        // 8.3.0 (Nova): defensive — opening the menu ends surf
        // mode. In practice the long-press that opens it is
        // already consumed as surf's any-key stop, so this only
        // matters if a future entry point reaches here directly.
        stopSurfMode()
        val ch = channel
        if (ch == null) {
            // 7.1.0 (Nova): a recording has no channel options, but
            // the sleep timer is not a channel option — it only
            // pauses the player — so recordings get a one-item menu
            // carrying it instead of no menu at all. 7.0.0 skipped
            // them because this function returned here; the timer
            // machinery itself (arm, deadline, fire) never depended
            // on a channel.
            if (recordingUri == null) return
            // 7.2.0 (Nova): stream info and night dim are not
            // channel options either — a recording has a stream
            // worth describing and a picture worth dimming — so
            // they join the sleep timer in this menu. 7.3.1
            // (Nova): the menu is built as paired item/action
            // lists now, like the channel menu below — the track
            // pickers are conditional, and a positional when is
            // exactly how an index quietly lands on the wrong
            // action.
            val items = mutableListOf<String>()
            val actions = mutableListOf<() -> Unit>()
            items += sleepTimerMenuLabel()
            actions += { showSleepTimerDialog() }
            items += streamInfoMenuLabel()
            actions += { setStreamInfo(!streamInfoOn) }
            items += nightDimMenuLabel()
            actions += { showDimDialog() }
            addTrackPickerItems(items, actions)
            // 9.1.1 (Nova): start from beginning — only when this
            // recording has a saved point it resumed from.
            if (hasResumePoint()) {
                items += getString(R.string.start_from_beginning)
                actions += { startFromBeginning() }
            }
            // Combined port (juniper-v10 10.0.0): picture-in-picture
            // — a recording replays from disk the same way a live
            // stream does, so it is offered here too.
            if (PictureInPicture.isSupported(this)) {
                items += getString(R.string.pip_menu)
                actions += { enterPipAndOpenGuide() }
            }
            AlertDialog.Builder(this)
                .setTitle(recordingName ?: getString(R.string.recording_label))
                .setItems(items.toTypedArray()) { _, which -> actions[which]() }
                .show()
                .also { trackOptionsDialog(it) }
            return
        }
        val isFav = ch.id in favoritesCache
        val isRecording = RecordingManager.isRecording(this, ch.id)
        val hasRecording = RecordingManager.hasRecording(this, ch.id)
        // 7.2.0 (Nova): items and their actions are built as a
        // pair of lists — the menu has grown past the point where
        // a positional when stays readable, and one entry (the
        // previous-channel swap) is conditional.
        val items = mutableListOf<String>()
        val actions = mutableListOf<() -> Unit>()
        items += if (isFav) getString(R.string.remove_favorite) else getString(R.string.add_favorite)
        actions += {
            toggleFavorite()
            updateTransportIcons()
        }
        items += when {
            isRecording -> getString(R.string.stop_recording)
            hasRecording -> getString(R.string.record_again)
            else -> getString(R.string.record_channel)
        }
        actions += {
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
        // 7.2.0 (Nova): previous-channel swap, for remotes without
        // a MEDIA_PREVIOUS key. Only offered once there is a
        // channel to swap back to.
        previousChannel?.let { prev ->
            items += getString(R.string.previous_channel_item, prev.name)
            actions += { swapToPreviousChannel() }
        }
        // 7.2.0 (Nova): favorites zapping, stream info and night
        // dim — each label carries its state, like the sleep
        // timer's below.
        items += favoritesZapMenuLabel()
        actions += { toggleFavoritesZap() }
        // 8.3.0 (Nova): surf mode — hands-free zapping through
        // the zap-eligible list (favorites only while favorites
        // zapping is on), a hop every surf interval until any
        // key press.
        items += getString(R.string.surf_mode)
        actions += { startSurfMode() }
        // 8.3.1 (Nova): how long each surfed channel stays up.
        items += surfIntervalMenuLabel()
        actions += { showSurfIntervalDialog() }
        items += streamInfoMenuLabel()
        actions += { setStreamInfo(!streamInfoOn) }
        items += nightDimMenuLabel()
        actions += { showDimDialog() }
        // 7.3.0 (Nova): audio-only mode — picture off, sound on.
        items += audioOnlyMenuLabel()
        actions += { setAudioOnly(!audioOnlyOn) }
        // 7.3.0 (Nova): stream quality — steers adaptive track
        // selection (HLS/DASH variants); a single-variant stream
        // simply has nothing to steer.
        items += qualityMenuLabel()
        actions += { showQualityDialog() }
        // Combined port (juniper-v10 7.1.0): playback speed — live
        // only: a recording has no live edge to chase, and a
        // catch-up replay keeps its own resume/seek behaviour.
        if (recordingUri == null && catchupUrl == null) {
            items += speedMenuLabel()
            actions += { showSpeedPicker() }
        }
        // 7.3.1 (Nova): subtitle / audio track pickers — only
        // for track types the running stream actually carries.
        addTrackPickerItems(items, actions)
        // 7.3.1 (Nova): reload stream — the manual twin of
        // auto-reconnect, for a stream that is misbehaving but
        // has not errored. Live only: restarting a catch-up
        // stream would lose the viewer's place in it.
        if (catchupUrl == null) {
            items += getString(R.string.reload_stream)
            actions += { reloadStream() }
        }
        // 9.1.1 (Nova): start from beginning — the catch-up twin
        // of the recording menu's item, offered when this replay
        // resumed from a saved point. Live streams never resume,
        // so they never see it.
        if (catchupUrl != null && hasResumePoint()) {
            items += getString(R.string.start_from_beginning)
            actions += { startFromBeginning() }
        }
        // 7.0.0 (Nova): sleep timer — while one is running the menu
        // doubles as its status (7.1.1: counting down, see
        // sleepTimerMenuLabel).
        items += sleepTimerMenuLabel()
        actions += { showSleepTimerDialog() }
        // Combined port (juniper-v10 10.0.0): picture-in-picture —
        // keep watching in a small window while browsing the guide.
        // Only on devices with the PiP feature.
        if (PictureInPicture.isSupported(this)) {
            items += getString(R.string.pip_menu)
            actions += { enterPipAndOpenGuide() }
        }
        AlertDialog.Builder(this)
            .setTitle(ch.name)
            .setItems(items.toTypedArray()) { _, which -> actions[which]() }
            .show()
            .also { trackOptionsDialog(it) }
    }

    /** Combined port (juniper-v10 7.1.0): remember the options
     * dialog on screen (and clear the field when it goes away) so
     * entering PiP can dismiss it first — a dialog left up would
     * float over the small window. */
    private fun trackOptionsDialog(dialog: AlertDialog) {
        optionsDialog = dialog
        dialog.setOnDismissListener {
            if (optionsDialog === dialog) optionsDialog = null
        }
    }

    /** Combined port (juniper-v10 7.1.0): the options-menu label
     * for playback speed, carrying its current value. */
    private fun speedMenuLabel(): String = getString(
        R.string.playback_speed_menu,
        PlaybackComfort.speedLabel(playbackSpeed)
    )

    /** Combined port (juniper-v10 7.1.0): playback-speed picker —
     * 0.5× to 2×, live streams only (see showOptions). Applies to
     * the running player at once; Nova's in-place zap keeps it,
     * and initializePlayer restores it after a rebuild. */
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
    }

    /** Combined port (juniper-v10 7.1.0): skip a recording
     * forward/back by [deltaMs] (±10 s), clamped to the recording's
     * length. A no-op without a player, and a no-op while the
     * source is still preparing — the position is C.TIME_UNSET
     * then, and seeking from it would jump to 0.
     * 7.1.1 (hers): the toast says where the seek landed — a bare
     * "+10s" says nothing about position, and a recording's
     * overlay is not always up to show the progress bar. */
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

    /** Combined port (juniper-v10 10.0.0): enter picture-in-picture
     * and open the guide on top, so the channel keeps playing in a
     * small window while browsing. The options dialog is dismissed
     * first — entering PiP with a dialog up would leave it floating
     * over the small window. When PiP entry fails (the feature is
     * present but the system refuses), the guide stays closed so
     * playback doesn't silently stop for nothing.
     * Nova-side adaptations: surf mode is stood down (its hops
     * would flip channels inside the small window and flash
     * overlays it cannot show), and reminder prompts pause (a
     * Watch-now dialog cannot present over PiP; the watcher
     * re-arms when PiP exits — see onPictureInPictureModeChanged). */
    private fun enterPipAndOpenGuide() {
        optionsDialog?.dismiss()
        optionsDialog = null
        hideOverlay()
        hideSidebar()
        stopSurfMode()
        reminderWatcher.stop()
        if (player == null) {
            reminderWatcher.start()
            Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        val entered = enterPictureInPictureMode(PictureInPicture.buildParams(this))
        if (entered) {
            openGuide()
        } else {
            reminderWatcher.start()
            Toast.makeText(this, R.string.pip_unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    /** Combined port (juniper-v10 10.0.0): entering PiP shows video
     * only in the small window — the overlay and sidebar would sit
     * cramped over the picture. Leaving PiP needs nothing for the
     * picture: the overlay stays hidden until the next key press,
     * like any other return to playback. The reminder watcher,
     * stood down on entry, re-arms here because a plain PiP exit
     * never passes through onStart. */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            optionsDialog?.dismiss()
            optionsDialog = null
            hideOverlay()
            hideSidebar()
        } else {
            reminderWatcher.start()
        }
    }

    /** 7.2.0 (Nova): the options-menu label for favorites
     * zapping, carrying its state like the sleep timer's does. */
    private fun favoritesZapMenuLabel(): String = getString(
        R.string.favorites_zap_state,
        if (favoritesZapMode) getString(R.string.state_on)
        else getString(R.string.sleep_timer_off)
    )

    /** 7.2.0 (Nova): flip favorites zapping and persist it. The
     * zap and the neighbour prefetch read the flag on every press,
     * so the mode changes under the playing channel with no
     * restart — and the new mode's neighbours get warmed now,
     * since nothing has prefetched them yet. */
    private fun toggleFavoritesZap() {
        favoritesZapMode = !favoritesZapMode
        AppSettings.setFavoritesZap(this, favoritesZapMode)
        prefetchAdjacentChannels()
        if (favoritesZapMode) {
            Toast.makeText(
                this,
                if (favoritesCache.isEmpty()) R.string.favorites_zap_on_empty_toast
                else R.string.favorites_zap_on_toast,
                Toast.LENGTH_SHORT
            ).show()
        } else {
            Toast.makeText(this, R.string.favorites_zap_off_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** 7.2.0 (Nova): the options-menu label for the stream info
     * overlay, carrying its state. */
    private fun streamInfoMenuLabel(): String = getString(
        R.string.stream_info_state,
        if (streamInfoOn) getString(R.string.state_on)
        else getString(R.string.sleep_timer_off)
    )

    /** 7.2.0 (Nova): show / hide the stream info overlay. While
     * shown, the updater re-reads the player's formats once a
     * second; hidden, its callbacks stop with it. */
    private fun setStreamInfo(on: Boolean) {
        streamInfoOn = on
        infoHandler.removeCallbacks(streamInfoRunnable)
        if (on) {
            streamInfo.visibility = View.VISIBLE
            streamInfoRunnable.run()
        } else {
            streamInfo.visibility = View.GONE
        }
    }

    /** 7.2.0 (Nova): fill the stream info overlay from what the
     * player already reports — the video / audio formats, the
     * stream kind and the buffer headroom. Nothing is probed;
     * pieces the format does not carry are simply left out. */
    private fun updateStreamInfo() {
        if (!streamInfoOn) return
        val p = player ?: return
        val lines = mutableListOf<String>()
        p.videoFormat?.let { vf ->
            val parts = mutableListOf<String>()
            if (vf.width > 0 && vf.height > 0) parts += "${vf.width}×${vf.height}"
            if (vf.frameRate > 0f) parts += "${Math.round(vf.frameRate)} fps"
            vf.sampleMimeType?.substringAfter('/')?.let { parts += it.uppercase() }
            if (parts.isNotEmpty()) lines += parts.joinToString("  •  ")
            // Format.bitrate is the stream's average bitrate —
            // an approximation on adaptive streams, hence the ≈.
            if (vf.bitrate > 0) {
                lines += "≈ " + String.format(Locale.US, "%.1f Mbps", vf.bitrate / 1_000_000.0)
            }
        }
        p.audioFormat?.let { af ->
            val parts = mutableListOf<String>()
            af.sampleMimeType?.substringAfter('/')?.let { parts += it.uppercase() }
            if (af.channelCount > 0) parts += "${af.channelCount} ch"
            if (af.sampleRate > 0) parts += "${af.sampleRate / 1000} kHz"
            if (parts.isNotEmpty()) lines += parts.joinToString("  •  ")
        }
        val bufferedSec = ((p.bufferedPosition - p.currentPosition) / 1000L).coerceAtLeast(0L)
        lines += "${streamTypeLabel()}  •  ${getString(R.string.stream_info_buffer, bufferedSec)}"
        streamInfo.text = lines.joinToString("\n")
    }

    /** 7.2.0 (Nova): HLS / DASH / progressive, from the URL —
     * the player does not name the stream kind directly. */
    private fun streamTypeLabel(): String {
        if (recordingUri != null) return getString(R.string.stream_type_local)
        val url = catchupUrl ?: channel?.url
            ?: return getString(R.string.stream_type_stream)
        return when {
            url.contains(".m3u8") -> "HLS"
            url.contains(".mpd") -> "DASH"
            else -> getString(R.string.stream_type_stream)
        }
    }

    /** 7.2.0 (Nova): the options-menu label for night dim —
     * plain when off, the level when on. */
    private fun nightDimMenuLabel(): String =
        if (dimPercent > 0) getString(R.string.night_dim_state, dimPercent)
        else getString(R.string.night_dim)

    /** 7.2.0 (Nova): pick a night dim level (or turn it off).
     * 7.2.1 (Nova): single-choice with the current level checked —
     * the menu label carries the state, and 7.2.0's plain item
     * list left the picker itself silent about it. Picking a
     * level applies it and dismisses, as the item list did. */
    private fun showDimDialog() {
        val options = intArrayOf(0, 25, 50)
        val labels = options.map { percent ->
            if (percent == 0) getString(R.string.sleep_timer_off)
            else getString(R.string.night_dim_percent, percent)
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.night_dim)
            .setSingleChoiceItems(labels, options.indexOf(dimPercent)) { dialog, which ->
                setDim(options[which])
                dialog.dismiss()
            }
            .show()
    }

    /** 7.2.0 (Nova): apply a night dim level — a black veil over
     * the video only (see the layout), so the picture calms down
     * while every menu and overlay stays fully readable.
     * 7.2.1 (Nova): the choice is persisted as it is applied, so
     * the next session restores it (see onCreate). */
    private fun setDim(percent: Int) {
        // 8.0.0 (Nova): an explicit dim level supersedes the sleep
        // fade — retire its starter and any in-flight rise before
        // applying, or the animator would keep fighting the alpha
        // set below. The fade's own restore path comes through here
        // too (setDim(dimPercent) from onStop / a resume / a timer
        // cancel): cancelling first is what makes it a restore.
        infoHandler.removeCallbacks(sleepFadeRunnable)
        if (::dimView.isInitialized) dimView.animate().cancel()
        sleepFaded = false
        dimPercent = percent
        AppSettings.setNightDimPercent(this, percent)
        if (percent > 0) {
            dimView.alpha = percent / 100f
            dimView.visibility = View.VISIBLE
        } else {
            dimView.visibility = View.GONE
        }
    }

    /** 7.1.1 (Nova): the options-menu label for the sleep timer.
     * Unarmed it is the plain "Sleep timer"; armed it counts down
     * from the stored deadline ("Sleep timer: 23 min left",
     * rounded up so it never claims 0 while still armed) instead
     * of repeating the duration originally set. If the deadline
     * has somehow already passed without the callback firing, it
     * falls back to the set-duration label — the callback is due
     * immediately anyway. */
    private fun sleepTimerMenuLabel(): String {
        if (sleepTimerMinutes == 0) return getString(R.string.sleep_timer)
        val remainingMs = sleepDeadlineMillis - System.currentTimeMillis()
        // 7.3.0 (Nova): end-of-programme has no minute count to
        // fall back on — its overdue label names the mode instead.
        if (remainingMs <= 0L) {
            return if (sleepTimerMinutes == SLEEP_END_OF_PROGRAMME) {
                getString(R.string.sleep_timer_eop_state)
            } else {
                getString(R.string.sleep_timer_on, sleepTimerMinutes)
            }
        }
        val remainingMin = ((remainingMs + 59_999L) / 60_000L).toInt()
        return getString(R.string.sleep_timer_remaining, remainingMin)
    }

    /** 7.0.0 (Nova): pick a sleep-timer duration (or turn it off).
     * 7.2.1 (Nova): single-choice with the current duration
     * checked, like the night dim picker — the menu label counts
     * the timer down, and now the picker shows the setting it is
     * counting from. Picking applies and dismisses, as before.
     * 7.3.0 (Nova): an "End of programme" choice joins the fixed
     * durations (the SLEEP_END_OF_PROGRAMME sentinel in the same
     * int list, so the checked-index lookup works unchanged). */
    private fun showSleepTimerDialog() {
        val options = intArrayOf(0, 15, 30, 60, 90, SLEEP_END_OF_PROGRAMME)
        val labels = options.map { minutes ->
            when {
                minutes == 0 -> getString(R.string.sleep_timer_off)
                minutes == SLEEP_END_OF_PROGRAMME ->
                    getString(R.string.sleep_timer_end_of_programme)
                else -> getString(R.string.sleep_timer_minutes, minutes)
            }
        }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.sleep_timer)
            .setSingleChoiceItems(labels, options.indexOf(sleepTimerMinutes)) { dialog, which ->
                setSleepTimer(options[which])
                dialog.dismiss()
            }
            .show()
    }

    /** 7.0.0 (Nova): arm (or clear) the sleep timer. When it fires,
     * playback pauses — the calm option: the TV stays on, the show
     * stops, and one OK press resumes if the viewer is still awake.
     * 7.3.0 (Nova): SLEEP_END_OF_PROGRAMME arms the same deadline
     * machinery at the current programme's EPG end time, so it
     * survives guide visits exactly like a fixed duration. With
     * no programme info (or a programme already over — a catch-up
     * replay's programme is in the past) it falls back to a plain
     * 30 minutes and says so, rather than firing instantly. */
    private fun setSleepTimer(minutes: Int) {
        // 8.0.0 (Nova): re-arming or clearing retires any fade from
        // the old deadline first — a veil caught mid-fade returns
        // to its night-dim level before the new timer is set.
        infoHandler.removeCallbacks(sleepFadeRunnable)
        if (sleepFaded) setDim(dimPercent)
        if (minutes == SLEEP_END_OF_PROGRAMME) {
            val stopMillis = nowProgramme?.stopMillis ?: 0L
            val remaining = stopMillis - System.currentTimeMillis()
            if (remaining <= 0L) {
                setSleepTimer(30)
                Toast.makeText(
                    this,
                    R.string.sleep_timer_eop_fallback,
                    Toast.LENGTH_LONG
                ).show()
                return
            }
            infoHandler.removeCallbacks(sleepRunnable)
            sleepTimerMinutes = SLEEP_END_OF_PROGRAMME
            sleepDeadlineMillis = stopMillis
            infoHandler.postDelayed(sleepRunnable, remaining)
            scheduleSleepFade()
            Toast.makeText(this, R.string.sleep_timer_eop_set, Toast.LENGTH_SHORT).show()
            return
        }
        infoHandler.removeCallbacks(sleepRunnable)
        sleepTimerMinutes = minutes
        if (minutes > 0) {
            sleepDeadlineMillis = System.currentTimeMillis() + minutes * 60_000L
            infoHandler.postDelayed(sleepRunnable, minutes * 60_000L)
            scheduleSleepFade()
            Toast.makeText(
                this,
                getString(R.string.sleep_timer_set, minutes),
                Toast.LENGTH_SHORT
            ).show()
        } else {
            sleepDeadlineMillis = 0L
            Toast.makeText(this, R.string.sleep_timer_off_toast, Toast.LENGTH_SHORT).show()
        }
    }

    /** 7.0.0 (Nova): the sleep timer came due — pause and say so.
     * 8.0.0 (Nova): when the fade brought the veil up, it stays at
     * black behind the pause — the room is dark, the show stopped.
     * The sleepFaded flag stays set, so the resume that follows
     * (one OK press) restores the plain night-dim level (see
     * onIsPlayingChanged). */
    private fun onSleepTimerFired() {
        sleepTimerMinutes = 0
        sleepDeadlineMillis = 0L
        // 8.3.0 (Nova): the sleep pause ends surf mode too — a
        // surf hop after the deadline would switch channel and
        // start playing again, undoing the timer outright.
        stopSurfMode()
        infoHandler.removeCallbacks(sleepFadeRunnable)
        if (sleepFaded) {
            dimView.animate().cancel()
            dimView.visibility = View.VISIBLE
            dimView.alpha = 1f
        }
        player?.pause()
        Toast.makeText(this, R.string.sleep_timer_fired, Toast.LENGTH_LONG).show()
    }

    /** 8.0.0 (Nova): (re)schedule the fade for the current sleep
     * deadline. More than a minute out, the starter is posted for
     * deadline − SLEEP_FADE_MS; already inside the window, the fade
     * starts now, part-way up, so the veil still reaches black
     * exactly at the deadline. No deadline, no fade. */
    private fun scheduleSleepFade() {
        infoHandler.removeCallbacks(sleepFadeRunnable)
        if (sleepDeadlineMillis <= 0L) return
        val remaining = sleepDeadlineMillis - System.currentTimeMillis()
        when {
            remaining > SLEEP_FADE_MS ->
                infoHandler.postDelayed(sleepFadeRunnable, remaining - SLEEP_FADE_MS)
            remaining > 0L -> startSleepFade()
        }
    }

    /** 8.0.0 (Nova): start the dim veil's rise to black over the
     * time left until the sleep deadline. The rise is linear and
     * begins from wherever the fade should have reached by now, so
     * a fade (re)started late — back from the guide mid-window —
     * neither jumps nor overruns the deadline. Only the veil's
     * alpha moves; night dim's stored level is never touched, so
     * cancelling returns to exactly it via setDim(dimPercent). */
    private fun startSleepFade() {
        val remaining = sleepDeadlineMillis - System.currentTimeMillis()
        if (remaining <= 0L) return
        val base = dimPercent / 100f
        val elapsedFraction =
            1f - remaining.coerceAtMost(SLEEP_FADE_MS).toFloat() / SLEEP_FADE_MS
        sleepFaded = true
        dimView.animate().cancel()
        dimView.visibility = View.VISIBLE
        dimView.alpha = base + (1f - base) * elapsedFraction
        dimView.animate()
            .alpha(1f)
            .setDuration(remaining)
            .setInterpolator(LinearInterpolator())
            .start()
    }

    /** 7.3.0 (Nova): queue the next reconnect attempt for a live
     * stream that failed or stalled mid-playback. Attempts are
     * capped and spaced by a growing backoff, the indicator shows
     * while waiting, and reaching READY clears the slate (see
     * onPlaybackStateChanged). */
    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) return
        val delay = RECONNECT_BACKOFF_MS[reconnectAttempts]
        reconnectAttempts++
        // 7.3.1 (Nova): the pill counts the attempt, so a
        // viewer watching a stream struggle can see how much
        // patience the player has left.
        reconnectView.text = getString(
            R.string.reconnecting_attempt, reconnectAttempts, MAX_RECONNECT_ATTEMPTS
        )
        reconnectView.visibility = View.VISIBLE
        infoHandler.removeCallbacks(reconnectRunnable)
        infoHandler.postDelayed(reconnectRunnable, delay)
    }

    /** 7.3.0 (Nova): the stall watchdog fired — the stream has
     * sat in BUFFERING past the timeout. Counts as a failed
     * attempt, same as a load error. */
    private fun onStreamStalled() {
        val p = player ?: return
        if (p.playbackState != Player.STATE_BUFFERING) return
        if (!everPlayed || recordingUri != null || catchupUrl != null) return
        if (reconnectAttempts < MAX_RECONNECT_ATTEMPTS) {
            scheduleReconnect()
        }
    }

    /** 7.3.0 (Nova): a reconnect attempt comes due — re-prepare
     * the SAME stream. Aborts quietly if the viewer has moved on:
     * the player is gone, playback was paused on purpose, or the
     * stream already recovered on its own. stop() keeps the media
     * item, so prepare() reloads exactly what was playing. */
    private fun restartCurrentStream() {
        val p = player ?: return
        if (!p.playWhenReady) {
            cancelReconnect()
            return
        }
        if (p.playbackState == Player.STATE_READY) {
            cancelReconnect()
            return
        }
        p.stop()
        p.prepare()
        p.play()
    }

    /** 7.3.0 (Nova): forget all reconnect state — pending retry,
     * stall watchdog, attempt count and the indicator. Called on
     * recovery, channel switch, pause and onStop. */
    private fun cancelReconnect() {
        infoHandler.removeCallbacks(reconnectRunnable)
        infoHandler.removeCallbacks(stallWatchdogRunnable)
        reconnectAttempts = 0
        if (::reconnectView.isInitialized) reconnectView.visibility = View.GONE
    }

    /** 7.3.0 (Nova): the options-menu label for audio-only mode,
     * carrying its state like the other toggles. */
    private fun audioOnlyMenuLabel(): String = getString(
        R.string.audio_only_state,
        if (audioOnlyOn) getString(R.string.state_on)
        else getString(R.string.sleep_timer_off)
    )

    /** 7.3.0 (Nova): blank / restore the picture for audio-only
     * mode. Only the black cover changes visibility — the player
     * is never paused, stopped or rebuilt, so the sound runs on
     * uninterrupted. 7.3.1 (Nova): turning the mode on also
     * shows the hint centred on the cover; it fades after a few
     * seconds (see fadeAudioOnlyHint) so the room stays dark,
     * and turning the mode off takes it down at once. */
    private fun setAudioOnly(on: Boolean) {
        audioOnlyOn = on
        audioOnlyView.visibility = if (on) View.VISIBLE else View.GONE
        // 7.3.1 (Nova): every transition retires the previous
        // cycle's pending fade before setting up the new state.
        audioOnlyHintGeneration++
        infoHandler.removeCallbacks(audioOnlyHintRunnable)
        audioOnlyHint.animate().cancel()
        if (on) {
            audioOnlyHint.alpha = 1f
            audioOnlyHint.visibility = View.VISIBLE
            infoHandler.postDelayed(audioOnlyHintRunnable, AUDIO_ONLY_HINT_MS)
            Toast.makeText(this, R.string.audio_only_on_toast, Toast.LENGTH_LONG).show()
        } else {
            audioOnlyHint.visibility = View.GONE
            audioOnlyHint.alpha = 1f
        }
    }

    /** 7.3.1 (Nova): fade the audio-only hint out. The
     * generation check makes this a no-op when the cycle that
     * scheduled it has already ended — a stale fade must never
     * hide a hint the viewer just got back. */
    private fun fadeAudioOnlyHint(generation: Int) {
        if (!audioOnlyOn || generation != audioOnlyHintGeneration) return
        audioOnlyHint.animate()
            .alpha(0f)
            .setDuration(400)
            .withEndAction {
                if (generation == audioOnlyHintGeneration) {
                    audioOnlyHint.visibility = View.GONE
                    audioOnlyHint.alpha = 1f
                }
            }
            .start()
    }

    /**
     * 8.3.0 (Nova): start the idle-saver countdown for a pause
     * that just began. Live channels only — a recording's pause
     * keeps its frozen frame (the viewer parked a file, not a
     * broadcast), and catch-up keeps its place marker. The
     * Settings toggle is read here and again when the saver
     * would show, so turning it off mid-pause still wins.
     */
    private fun armIdleSaver() {
        infoHandler.removeCallbacks(idleSaverRunnable)
        if (recordingUri != null || catchupUrl != null) return
        if (!AppSettings.idleSaverEnabled(this)) return
        infoHandler.postDelayed(idleSaverRunnable, IDLE_SAVER_MS)
    }

    /** 8.3.0 (Nova): the idle countdown elapsed — cover the
     * frozen frame with the clock screen. Re-checks everything
     * that could have changed while the timer ran: playback
     * resumed, the pause turned into a channel switch, the
     * toggle was switched off in Settings. */
    private fun showIdleSaver() {
        val p = player ?: return
        if (p.isPlaying || p.playWhenReady) return
        if (recordingUri != null || catchupUrl != null) return
        if (!AppSettings.idleSaverEnabled(this)) return
        hideOverlay()
        hideSidebar()
        idleSaverTitle.text = nowProgramme?.title ?: ""
        idleSaverTitle.visibility =
            if (nowProgramme != null) View.VISIBLE else View.GONE
        idleSaverChannel.text = channel?.name ?: ""
        updateSaverClock()
        updateSaverNext()
        idleSaverShowing = true
        idleSaverView.visibility = View.VISIBLE
        infoHandler.removeCallbacks(saverClockRunnable)
        infoHandler.postDelayed(saverClockRunnable, SCREENSAVER_CLOCK_MS)
    }

    /** 8.3.0 (Nova): take the screensaver down and stop its
     * clock. Playback stays exactly as paused as it was — only
     * a real resume (OK / play key) starts it again. */
    private fun hideIdleSaver() {
        infoHandler.removeCallbacks(saverClockRunnable)
        idleSaverShowing = false
        if (::idleSaverView.isInitialized) idleSaverView.visibility = View.GONE
    }

    /** 8.3.0 (Nova): the screensaver's big clock line, in the
     * same "8:00 PM" shape the rest of the app uses. */
    private fun updateSaverClock() {
        idleSaverClock.text = GuideData.formatClock(System.currentTimeMillis())
    }

    /** 8.3.1 (Nova): the screensaver's up-next line — the
     * programme after the paused one on the same channel, from
     * the same nextProgramme field the zap flash fills. Hidden
     * when the guide data has nothing after the current
     * programme. Refreshed with the clock tick so a long pause
     * never shows a next programme that has already started. */
    private fun updateSaverNext() {
        val next = nextProgramme
        if (next != null) {
            idleSaverNext.text = getString(
                R.string.saver_up_next,
                next.title,
                GuideData.formatClock(next.startMillis)
            )
            idleSaverNext.visibility = View.VISIBLE
        } else {
            idleSaverNext.visibility = View.GONE
        }
    }

    /** 8.3.0 (Nova): begin surfing — the first hop lands at
     * once (picking the menu item and staying put for twelve
     * seconds would read as nothing happening), then a hop
     * every surf interval through switchToAdjacentChannel,
     * so the eligible list, the favorites-zap mode and the zap
     * flash are exactly the manual ones. Live only in effect:
     * the menu that offers it never appears for recordings. */
    private fun startSurfMode() {
        if (surfMode) return
        surfMode = true
        surfRunnable.run()
    }

    /** 8.3.0 (Nova): end surfing — no more hops, the hint comes
     * down, playback stays on the current channel. Safe to call
     * when not surfing. Deliberately does NOT touch the
     * reconnect state: a stream that stumbles mid-surf keeps
     * its own retry rules, and the next hop (or the viewer's
     * key press) moves on as usual. */
    private fun stopSurfMode() {
        surfMode = false
        infoHandler.removeCallbacks(surfRunnable)
        if (::surfHint.isInitialized) surfHint.visibility = View.GONE
    }

    /** 8.3.1 (Nova): the options-menu label for the surf
     * interval, carrying the current choice like the other
     * state labels in this menu. */
    private fun surfIntervalMenuLabel(): String = getString(
        R.string.surf_interval_state,
        AppSettings.surfIntervalSeconds(this)
    )

    /** 8.3.1 (Nova): pick the surf hop interval. Single-choice
     * with the current value checked, like the other pickers. */
    private fun showSurfIntervalDialog() {
        val options = AppSettings.SURF_INTERVAL_OPTIONS
        val labels = options.map {
            getString(R.string.surf_interval_option, it)
        }.toTypedArray()
        val current = AppSettings.surfIntervalSeconds(this)
        AlertDialog.Builder(this)
            .setTitle(R.string.surf_interval)
            .setSingleChoiceItems(labels, options.indexOf(current)) { dialog, which ->
                setSurfIntervalSeconds(options[which])
                dialog.dismiss()
            }
            .show()
    }

    /** 8.3.1 (Nova): persist a surf interval. If a surf session
     * is somehow running when the value changes, its pending hop
     * is re-timed to the new interval at once. (In practice the
     * menu that offers this picker can only be opened while NOT
     * surfing — any key press, the menu's included, ends a surf
     * first — so the re-time is a belt-and-braces hook; the
     * runnable also re-reads the persisted value on every hop.) */
    private fun setSurfIntervalSeconds(seconds: Int) {
        AppSettings.setSurfIntervalSeconds(this, seconds)
        if (surfMode) {
            infoHandler.removeCallbacks(surfRunnable)
            infoHandler.postDelayed(surfRunnable, surfIntervalMs())
        }
    }

    /** 7.3.0 (Nova): the options-menu label for stream quality,
     * carrying the current mode. The label names the mode only —
     * on a single-variant stream every mode plays the same track,
     * and the label must not pretend otherwise. */
    private fun qualityMenuLabel(): String = getString(
        R.string.stream_quality_state,
        qualityModeName(qualityMode)
    )

    private fun qualityModeName(mode: Int): String = getString(
        when (mode) {
            AppSettings.QUALITY_HIGH -> R.string.quality_high
            AppSettings.QUALITY_LOW -> R.string.quality_low
            else -> R.string.quality_auto
        }
    )

    /** 7.3.0 (Nova): pick a stream quality mode. Single-choice
     * with the current mode checked, like the other pickers.
     * Picking persists the mode and re-points the live track
     * selector — no restart, the player re-selects tracks under
     * the new constraints on its own. */
    private fun showQualityDialog() {
        val options = intArrayOf(
            AppSettings.QUALITY_AUTO,
            AppSettings.QUALITY_HIGH,
            AppSettings.QUALITY_LOW
        )
        val labels = options.map { qualityModeName(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.stream_quality)
            .setSingleChoiceItems(labels, options.indexOf(qualityMode)) { dialog, which ->
                setQualityMode(options[which])
                dialog.dismiss()
            }
            .show()
    }

    /** 7.3.0 (Nova): persist a quality mode and apply it to the
     * running player's selector (if any). */
    private fun setQualityMode(mode: Int) {
        qualityMode = mode
        AppSettings.setQualityMode(this, mode)
        trackSelector?.let { applyQualityMode(it) }
    }

    /** 7.3.0 (Nova): translate a quality mode into track
     * selection parameters. AUTO restores the player's default
     * adaptive behaviour outright; HIGH forces the highest
     * supported variant; LOW caps the video bitrate so adaptive
     * selection settles on a low variant. Applied to a fresh
     * selector in initializePlayer and live on a mode change. */
    private fun applyQualityMode(selector: DefaultTrackSelector) {
        val builder = selector.parameters.buildUpon()
        when (qualityMode) {
            AppSettings.QUALITY_HIGH -> builder
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(true)
            AppSettings.QUALITY_LOW -> builder
                .setMaxVideoBitrate(LOW_QUALITY_MAX_BITRATE)
                .setForceHighestSupportedBitrate(false)
            else -> builder
                .setMaxVideoBitrate(Int.MAX_VALUE)
                .setForceHighestSupportedBitrate(false)
        }
        selector.setParameters(builder.build())
    }

    /** 7.3.1 (Nova): one selectable track of the running
     * stream, flattened out of its Tracks.Group for the picker
     * dialogs. The group is kept whole because a selection
     * override names the group, not the flattened position. */
    private class TrackChoice(val group: Tracks.Group, val index: Int, val label: String)

    /** 7.3.1 (Nova): the running stream's supported tracks of
     * one type, in group order. Empty when the stream carries
     * none — the pickers are only offered for types that
     * exist, so there is no empty menu to land on. */
    private fun trackChoices(type: Int): List<TrackChoice> {
        val p = player ?: return emptyList()
        val choices = mutableListOf<TrackChoice>()
        var number = 0
        for (group in p.currentTracks.groups) {
            if (group.type != type) continue
            for (i in 0 until group.length) {
                if (!group.isTrackSupported(i, false)) continue
                number++
                choices += TrackChoice(group, i, trackLabel(group.getTrackFormat(i), type, number))
            }
        }
        return choices
    }

    /** 7.3.1 (Nova): a human name for one track — its own
     * label when the stream provides one, else its language,
     * else a numbered fallback. SDH / dub / descriptive role
     * flags become suffixes, since two tracks often share a
     * language and differ only in that role. */
    private fun trackLabel(format: Format, type: Int, number: Int): String {
        var label = format.label?.takeIf { it.isNotBlank() }
        if (label == null) {
            val language = format.language
            label = if (!language.isNullOrBlank() && language != C.LANGUAGE_UNDETERMINED) {
                Locale(language).getDisplayLanguage(Locale.getDefault())
                    .ifBlank { language }
                    .replaceFirstChar { it.uppercase() }
            } else {
                getString(
                    if (type == C.TRACK_TYPE_TEXT) R.string.subtitle_track_number
                    else R.string.audio_track_number,
                    number
                )
            }
        }
        return when {
            type == C.TRACK_TYPE_TEXT &&
                format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 ->
                getString(R.string.track_label_sdh, label)
            type == C.TRACK_TYPE_AUDIO &&
                format.roleFlags and C.ROLE_FLAG_DESCRIBES_VIDEO != 0 ->
                getString(R.string.track_label_descriptive, label)
            type == C.TRACK_TYPE_AUDIO &&
                format.roleFlags and C.ROLE_FLAG_DUB != 0 ->
                getString(R.string.track_label_dub, label)
            else -> label
        }
    }

    /** 7.3.1 (Nova): add the subtitle / audio picker entries
     * to an options menu under construction — each only when
     * the running stream carries that track type. Shared by
     * the channel and recording menus. */
    private fun addTrackPickerItems(
        items: MutableList<String>,
        actions: MutableList<() -> Unit>
    ) {
        if (trackChoices(C.TRACK_TYPE_TEXT).isNotEmpty()) {
            items += getString(R.string.subtitles_item)
            actions += { showSubtitleDialog() }
        }
        if (trackChoices(C.TRACK_TYPE_AUDIO).isNotEmpty()) {
            items += getString(R.string.audio_track_item)
            actions += { showAudioDialog() }
        }
    }

    /** 7.3.1 (Nova): pick a subtitle track, or turn subtitles
     * off. The current selection is checked; "off" is checked
     * whenever no text track is selected, which is the
     * player's own default for most streams. */
    private fun showSubtitleDialog() {
        val choices = trackChoices(C.TRACK_TYPE_TEXT)
        if (choices.isEmpty()) return
        val selected = choices.indexOfFirst { it.group.isTrackSelected(it.index) }
        val labels = Array(choices.size + 1) { i ->
            if (i == 0) getString(R.string.track_off) else choices[i - 1].label
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.subtitles_item)
            .setSingleChoiceItems(labels, selected + 1) { dialog, which ->
                if (which == 0) clearTrackChoice(C.TRACK_TYPE_TEXT)
                else applyTrackChoice(choices[which - 1])
                dialog.dismiss()
            }
            .show()
    }

    /** 7.3.1 (Nova): pick an audio track (language). No "off"
     * — a stream with audio keeps playing one; the current
     * selection is checked. */
    private fun showAudioDialog() {
        val choices = trackChoices(C.TRACK_TYPE_AUDIO)
        if (choices.isEmpty()) return
        val selected = choices.indexOfFirst { it.group.isTrackSelected(it.index) }
        val labels = choices.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.audio_track_item)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                applyTrackChoice(choices[which])
                dialog.dismiss()
            }
            .show()
    }

    /** 7.3.1 (Nova): pin one track of its type on the running
     * player. Goes through the shared track selector's
     * parameters — the same live re-pointing the quality mode
     * uses — so playback never restarts, and the quality
     * constraints stay in force alongside the pin. Pins are
     * per-stream: a zap rebuilds the selector, and an override
     * naming the old stream's groups could never match the
     * new one anyway. */
    private fun applyTrackChoice(choice: TrackChoice) {
        val selector = trackSelector ?: return
        val builder = selector.parameters.buildUpon()
        builder.setTrackTypeDisabled(choice.group.type, false)
        builder.setOverrideForType(
            TrackSelectionOverride(choice.group.mediaTrackGroup, listOf(choice.index))
        )
        selector.setParameters(builder.build())
    }

    /** 7.3.1 (Nova): subtitles off — disable the track type
     * outright and drop any pin of that type, so turning
     * subtitles back on later starts from the player's own
     * selection instead of resurrecting a stale pin. */
    private fun clearTrackChoice(type: Int) {
        val selector = trackSelector ?: return
        val builder = selector.parameters.buildUpon()
        builder.clearOverridesOfType(type)
        builder.setTrackTypeDisabled(type, true)
        selector.setParameters(builder.build())
    }

    /** 7.3.1 (Nova): restart the live stream from the menu —
     * the manual twin of auto-reconnect's restartCurrentStream,
     * minus its "already healthy" abort: the viewer asked, so
     * it reloads even from READY. stop() keeps the media item,
     * so prepare() reloads exactly this channel, and a stream
     * paused on purpose stays paused. */
    private fun reloadStream() {
        val p = player ?: return
        cancelReconnect()
        p.stop()
        p.prepare()
        if (p.playWhenReady) p.play()
    }

    override fun onStop() {
        super.onStop()
        infoHandler.removeCallbacks(hideInfoRunnable)
        infoHandler.removeCallbacks(longPressRunnable)
        // 7.0.0 (Nova): the sleep timer's callback goes too — its
        // deadline is kept, and onStart re-arms it (see there).
        infoHandler.removeCallbacks(sleepRunnable)
        // 8.0.0 (Nova): the sleep fade's starter goes too, and a
        // veil caught mid-fade resets to the plain night-dim level —
        // onStart's re-arm restarts the fade from the deadline, so
        // no half-faded state leaks across a guide visit.
        infoHandler.removeCallbacks(sleepFadeRunnable)
        if (sleepFaded) setDim(dimPercent)
        // 8.0.0 (Nova): reminder prompts stop with the activity.
        reminderWatcher.stop()
        // 7.2.0 (Nova): the stream info updater stops too; onStart
        // restarts it if the overlay is still toggled on.
        infoHandler.removeCallbacks(streamInfoRunnable)
        // 7.3.0 (Nova): reconnect callbacks stop too — a pending
        // retry must not fire into a stopped activity. The attempt
        // count itself is left alone; the rebuilt player's first
        // READY or error sorts it out.
        infoHandler.removeCallbacks(reconnectRunnable)
        infoHandler.removeCallbacks(stallWatchdogRunnable)
        // 8.3.0 (Nova): surf mode and the idle screensaver stop
        // with the activity too — a hop must never land in a
        // stopped activity, and a return visit never finds the
        // saver (or a stale clock) still up. The saver's pending
        // countdown goes as well; a pause that survives the stop
        // re-arms from the rebuilt player's own state callbacks.
        stopSurfMode()
        infoHandler.removeCallbacks(idleSaverRunnable)
        saverRestoreSwallowUp = false
        surfStopSwallowUp = false
        hideIdleSaver()
        // 7.3.1 (Nova): the audio-only hint's fade stops too —
        // and the hint itself comes down, so a return visit
        // never finds a stale caption floating over the video.
        infoHandler.removeCallbacks(audioOnlyHintRunnable)
        if (::audioOnlyHint.isInitialized) {
            audioOnlyHint.animate().cancel()
            audioOnlyHint.visibility = View.GONE
            audioOnlyHint.alpha = 1f
        }
        // 7.1.0 (Nova): stream warm-ups stop with playback.
        streamPrefetcher.cancel()
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
        // Combined port (juniper-v10 10.0.0): in PiP the activity
        // is paused, not stopped — but if the system does stop it
        // while the window is up (screen off), everything below
        // must NOT run: releasing the player would black out the
        // PiP window, and the session isn't over — it keeps
        // accruing and is logged when playback really ends. The
        // cleanups above are safe in PiP (their owners re-arm in
        // onStart), so the guard sits exactly here: nothing above
        // ends the session, everything below does. Nova's in-place
        // zap composes with this by construction — switchToChannel
        // re-points the running player with setMediaItem and never
        // releases it, so no zap path can kill the window either.
        if (isInPictureInPictureMode) return
        // Log the watch session (local only, never shared).
        logWatchSession()
        // 9.0.0 (Nova): remember where a recording's playback
        // stopped, so the next play resumes there and the
        // recordings screen lists it under Continue watching.
        // Only an in-window position is kept (see
        // RecordingProgress.isResumable) — barely started or
        // effectively finished clears any older entry instead,
        // so the saved state always matches what a resume
        // would do. Read before release: a released player
        // reports no position.
        val endingRecId = recordingId
        val endingPlayer = player
        if (endingRecId != null && endingPlayer != null) {
            val pos = endingPlayer.currentPosition
            val dur = endingPlayer.duration
            if (dur > 0 &&
                pos > PlaylistRepository.RecordingProgress.RESUME_MIN_POSITION_MS &&
                pos * 100 < dur * PlaylistRepository.RecordingProgress.RESUME_MAX_PERCENT
            ) {
                PlaylistRepository.setRecordingProgress(this, endingRecId, pos, dur)
            } else {
                PlaylistRepository.clearRecordingProgress(this, endingRecId)
            }
        }
        // 9.1.0 (Nova): the same for a catch-up replay — its
        // save lives in a helper because a replay can also be
        // left without stopping (zap, Go live).
        saveCatchupProgress()
        player?.release()
        player = null
    }

    /** 9.1.1 (Nova): does the current recording / catch-up replay
     * have a saved point it resumed from? The stores are only
     * written when playback stops or a replay is left, so while
     * something plays, a stored in-window point is exactly the
     * one playback resumed from — and the only one "Start from
     * beginning" needs to retire. */
    private fun hasResumePoint(): Boolean {
        recordingId?.let {
            return PlaylistRepository.recordingProgressFor(this, it)
                ?.isResumable() == true
        }
        if (catchupUrl != null) {
            val chId = channel?.id ?: return false
            return PlaylistRepository.catchupProgressFor(
                this, chId, catchupStartMillis
            )?.isResumable() == true
        }
        return false
    }

    /** 9.1.1 (Nova): restart the current recording / catch-up
     * replay from the beginning — retire its saved resume point
     * (so leaving and reopening doesn't land back on it) and seek
     * the running player to zero. No rebuild: the same stream
     * keeps playing, just from the top. If the viewer watches on
     * and stops mid-way, the usual stop-time save writes a fresh
     * point for where they actually got to. */
    private fun startFromBeginning() {
        recordingId?.let {
            PlaylistRepository.clearRecordingProgress(this, it)
        }
        if (catchupUrl != null) {
            channel?.id?.let {
                PlaylistRepository.clearCatchupProgress(
                    this, it, catchupStartMillis
                )
            }
        }
        player?.seekTo(0)
        player?.play()
    }

    /**
     * 9.1.0 (Nova): remember where the current catch-up replay
     * stopped, so reopening the same programme resumes there.
     * Mirrors the recording save in onStop: only an in-window
     * position is kept — barely started or effectively finished
     * clears any older entry instead, so the saved state always
     * matches what a resume would do. A no-op outside catch-up
     * playback. Must be called while the player still reports
     * the replay's position (before release / re-prepare).
     */
    private fun saveCatchupProgress() {
        if (catchupUrl == null) return
        val chId = channel?.id ?: return
        val p = player ?: return
        val pos = p.currentPosition
        val dur = p.duration
        if (dur > 0 &&
            pos > PlaylistRepository.RecordingProgress.RESUME_MIN_POSITION_MS &&
            pos * 100 < dur * PlaylistRepository.RecordingProgress.RESUME_MAX_PERCENT
        ) {
            PlaylistRepository.setCatchupProgress(
                this, chId, catchupStartMillis, catchupStopMillis, pos, dur
            )
        } else {
            PlaylistRepository.clearCatchupProgress(this, chId, catchupStartMillis)
        }
    }

    override fun onDestroy() {
        // 7.1.0 (Nova): the prefetcher owns a coroutine scope that
        // nothing else cancels — tear it down with the activity.
        streamPrefetcher.shutdown()
        super.onDestroy()
    }

    /** Log the current watch session to the local statistics and
     * reset the session clock. Called from onStop, and from an
     * in-place channel switch (7.0.0, Nova) — the switch never
     * stops the activity, so without this the outgoing channel's
     * session would be lost (or credited to the new channel). */
    private fun logWatchSession() {
        val watchedMillis = System.currentTimeMillis() - sessionStartMillis
        if (sessionStartMillis > 0L && watchedMillis > 0L) {
            val id = channel?.id ?: recordingId ?: "unknown"
            val name = channel?.name ?: recordingName ?: "Unknown"
            StatsManager.logWatchSession(this, id, name, watchedMillis)
        }
        sessionStartMillis = 0L
    }
}
