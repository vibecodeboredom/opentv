package com.opentv.player

import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputFilter
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Lets the user manage M3U playlists (4.8.0: several playlists, one
 * active at a time), change the (optional) XMLTV EPG URL and the
 * (optional) recording upload URL. Also shows an About section with the
 * current app version, and (4.7.1) a "Share playlist" section that
 * temporarily hosts a playlist on this TV's Wi-Fi so other devices on
 * the same network can import it — the server stops itself after
 * 10 minutes. (4.8.0) While sharing is on, other devices can also send
 * a playlist TO this TV, which is added to the playlist list.
 * (4.9.0) Playlists can be deactivated (long-press a row) without
 * deleting them, and the screen got a modern card-style look. */
class SettingsActivity : FragmentActivity() {

    /** Ticks once a second while sharing, to refresh the countdown. */
    private val shareUiHandler = Handler(Looper.getMainLooper())
    private var shareTicker: Runnable? = null

    /** Share views, cached once — updateShareUi runs once a second
     * while sharing, and the old code did two findViewById walks per
     * tick. */
    private lateinit var shareStatusView: TextView
    private lateinit var shareButtonView: Button

    /** Local M3U file picker for the share's "local file" source. */
    private val pickM3uFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SettingsActivity, R.string.share_file_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                startSharing(text)
            }
        }

    /** 9.3.0 (Nova): the Settings PIN gate — true once no PIN is
     * set or the PIN has been entered this visit. Until then the
     * settings UI is never built, so nothing behind the PIN
     * prompt can be seen or reached. */
    private var pinUnlocked = false

    /** Backup file picker (8.0.0): pick an opentv-backup.json to
     * restore from. */
    private val pickBackupFile =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri == null) return@registerForActivityResult
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    } catch (e: Exception) {
                        null
                    }
                }
                if (text.isNullOrBlank()) {
                    Toast.makeText(this@SettingsActivity, R.string.backup_import_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val data = BackupManager.decode(text).getOrElse {
                    Toast.makeText(this@SettingsActivity, R.string.backup_import_failed, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                val report = withContext(Dispatchers.IO) {
                    BackupManager.restore(this@SettingsActivity, data)
                }
                Toast.makeText(
                    this@SettingsActivity,
                    getString(
                        R.string.backup_imported,
                        report.playlists, report.favorites, report.epgSources
                    ),
                    Toast.LENGTH_LONG
                ).show()
                restartToMain()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
        if (AppSettings.settingsPinSet(this)) {
            // Locked: no content view at all until the PIN checks
            // out — the screen stays blank behind the prompt, and
            // cancelling leaves Settings entirely.
            showPinEntryDialog(
                title = getString(R.string.pin_enter_title),
                onCancelled = { finish() },
                onUnlocked = { unlockSettings() }
            )
        } else {
            pinUnlocked = true
            buildSettingsUi()
        }
    }

    /** 9.3.0 (Nova): the PIN checked out — build the UI and do
     * the onResume work that ran (gated) while the prompt was up. */
    private fun unlockSettings() {
        pinUnlocked = true
        buildSettingsUi()
        updateShareUi()
        startShareTicker()
    }

    /** Everything onCreate always built, run either immediately
     * (no PIN set) or after the PIN gate opens. */
    private fun buildSettingsUi() {
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)

        val uploadUrl = findViewById<EditText>(R.id.upload_url)
        uploadUrl.setText(PlaylistRepository.uploadUrl(this))

        buildPlaylistsUi()
        buildEpgSourcesUi()
        buildMissingGuideUi()
        buildChannelGroupsUi()
        buildRecordingsUi()

        // 6.1.2: guide & playback preferences — toggles save
        // immediately; the channel-list size applies the next time
        // playback or the guide opens a fresh sidebar. 6.1.4: the
        // same size also sets the TV guide row height (thin rows on
        // Small), applied the next time the guide opens.
        findViewById<Switch>(R.id.pref_guide_current_channel).apply {
            isChecked = AppSettings.guideOpensOnCurrentChannel(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setGuideOpensOnCurrentChannel(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_tv_info_fade).apply {
            isChecked = AppSettings.tvInfoFade(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setTvInfoFade(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_guide_show_past).apply {
            isChecked = AppSettings.guideShowsPast(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setGuideShowsPast(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_guide_favorites_only).apply {
            isChecked = AppSettings.guideFavoritesOnly(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setGuideFavoritesOnly(this@SettingsActivity, checked)
            }
        }
        // 8.3.0 (Nova): the idle-pause screensaver toggle, with
        // the other guide & playback preferences.
        findViewById<Switch>(R.id.pref_idle_saver).apply {
            isChecked = AppSettings.idleSaverEnabled(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setIdleSaverEnabled(this@SettingsActivity, checked)
            }
        }
        findViewById<RadioGroup>(R.id.pref_channel_list_size).apply {
            check(
                when (AppSettings.channelListSize(this@SettingsActivity)) {
                    AppSettings.ChannelListSize.SMALL -> R.id.pref_size_small
                    AppSettings.ChannelListSize.MEDIUM -> R.id.pref_size_medium
                    AppSettings.ChannelListSize.LARGE -> R.id.pref_size_large
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setChannelListSize(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_size_medium -> AppSettings.ChannelListSize.MEDIUM
                        R.id.pref_size_large -> AppSettings.ChannelListSize.LARGE
                        else -> AppSettings.ChannelListSize.SMALL
                    }
                )
            }
        }
        // 7.2.0: guide window size (6/12/24 h forward). The guide
        // picks it up when it opens, or on return when it is open.
        findViewById<RadioGroup>(R.id.pref_guide_window_size).apply {
            check(
                when (AppSettings.guideWindowSize(this@SettingsActivity)) {
                    AppSettings.GuideWindowSize.TWELVE -> R.id.pref_window_12h
                    AppSettings.GuideWindowSize.TWENTY_FOUR -> R.id.pref_window_24h
                    else -> R.id.pref_window_6h
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setGuideWindowSize(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_window_12h -> AppSettings.GuideWindowSize.TWELVE
                        R.id.pref_window_24h -> AppSettings.GuideWindowSize.TWENTY_FOUR
                        else -> AppSettings.GuideWindowSize.SIX
                    }
                )
            }
        }
        // 9.0.0: what the TV opens on — home screen or auto-play
        // the last channel. TV only: mobile always opens the touch
        // home screen, so the row hides there. Applies on the next
        // launch.
        findViewById<LinearLayout>(R.id.pref_launch_behaviour_container).apply {
            visibility = if (DeviceType.isMobile(this@SettingsActivity)) {
                android.view.View.GONE
            } else {
                android.view.View.VISIBLE
            }
        }
        findViewById<RadioGroup>(R.id.pref_launch_behaviour).apply {
            check(
                when (AppSettings.launchBehaviour(this@SettingsActivity)) {
                    AppSettings.LaunchBehaviour.AUTOPLAY -> R.id.pref_launch_autoplay
                    else -> R.id.pref_launch_home
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setLaunchBehaviour(
                    this@SettingsActivity,
                    if (checkedId == R.id.pref_launch_autoplay) {
                        AppSettings.LaunchBehaviour.AUTOPLAY
                    } else {
                        AppSettings.LaunchBehaviour.HOME
                    }
                )
            }
        }
        // 9.0.0 (Nova): the accent colour choice, same pattern
        // as the list size above. Changing it repaints the one
        // accent surface on this screen (the active playlist
        // label, via buildPlaylistsUi); every other screen
        // reads the choice when it next builds.
        findViewById<RadioGroup>(R.id.pref_accent_color).apply {
            check(
                when (AppSettings.accentChoice(this@SettingsActivity)) {
                    AppSettings.ACCENT_AMBER -> R.id.pref_accent_amber
                    AppSettings.ACCENT_VIOLET -> R.id.pref_accent_violet
                    AppSettings.ACCENT_TEAL -> R.id.pref_accent_teal
                    else -> R.id.pref_accent_green
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setAccentChoice(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_accent_amber -> AppSettings.ACCENT_AMBER
                        R.id.pref_accent_violet -> AppSettings.ACCENT_VIOLET
                        R.id.pref_accent_teal -> AppSettings.ACCENT_TEAL
                        else -> AppSettings.ACCENT_GREEN
                    }
                )
                buildPlaylistsUi()
            }
        }
        // 9.0.0 (Nova): guide text size (Standard / Large).
        findViewById<RadioGroup>(R.id.pref_guide_text).apply {
            check(
                if (AppSettings.guideTextSize(this@SettingsActivity) ==
                    AppSettings.GUIDE_TEXT_LARGE
                ) {
                    R.id.pref_text_large
                } else {
                    R.id.pref_text_standard
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setGuideTextSize(
                    this@SettingsActivity,
                    if (checkedId == R.id.pref_text_large) {
                        AppSettings.GUIDE_TEXT_LARGE
                    } else {
                        AppSettings.GUIDE_TEXT_STANDARD
                    }
                )
            }
        }
        // 9.2.0 (Nova): the recordings storage guard cap
        // (Off / 2 / 5 / 10 GB), same RadioGroup pattern. The
        // guard itself runs at app start, on recording stop and
        // on the Recordings screen — this only stores the cap.
        findViewById<RadioGroup>(R.id.pref_rec_cap).apply {
            check(
                when (AppSettings.recordingAutoDeleteCapBytes(this@SettingsActivity)) {
                    AppSettings.REC_CAP_OPTIONS_BYTES[1] -> R.id.pref_cap_2gb
                    AppSettings.REC_CAP_OPTIONS_BYTES[2] -> R.id.pref_cap_5gb
                    AppSettings.REC_CAP_OPTIONS_BYTES[3] -> R.id.pref_cap_10gb
                    else -> R.id.pref_cap_off
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setRecordingAutoDeleteCapBytes(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_cap_2gb -> AppSettings.REC_CAP_OPTIONS_BYTES[1]
                        R.id.pref_cap_5gb -> AppSettings.REC_CAP_OPTIONS_BYTES[2]
                        R.id.pref_cap_10gb -> AppSettings.REC_CAP_OPTIONS_BYTES[3]
                        else -> 0L
                    }
                )
            }
        }
        // 9.2.0 (Nova): guide keyword markers — the button
        // shows the count and opens the add/remove dialog.
        updateKeywordsButton()
        findViewById<Button>(R.id.pref_keywords_button).setOnClickListener {
            showKeywordsDialog()
        }
        // 9.3.0 (Nova): the Settings PIN — set / change / remove.
        updatePinButton()
        findViewById<Button>(R.id.pref_pin_button).setOnClickListener {
            showPinManageDialog()
        }
        // 9.1.0: appearance — guide row density, font size, and the
        // two toggles. Density + font size apply the next time the
        // guide opens (like the channel-list size); the focus style
        // applies the next time the guide or a fresh sidebar opens;
        // reduced animations takes effect immediately.
        findViewById<RadioGroup>(R.id.pref_guide_density).apply {
            check(
                when (AppSettings.guideDensity(this@SettingsActivity)) {
                    Appearance.GuideDensity.COMPACT -> R.id.pref_density_compact
                    else -> R.id.pref_density_comfortable
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setGuideDensity(
                    this@SettingsActivity,
                    if (checkedId == R.id.pref_density_compact) {
                        Appearance.GuideDensity.COMPACT
                    } else {
                        Appearance.GuideDensity.COMFORTABLE
                    }
                )
            }
        }
        findViewById<RadioGroup>(R.id.pref_font_size).apply {
            check(
                when (AppSettings.fontSize(this@SettingsActivity)) {
                    Appearance.FontSize.SMALL -> R.id.pref_font_small
                    Appearance.FontSize.LARGE -> R.id.pref_font_large
                    else -> R.id.pref_font_medium
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setFontSize(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_font_small -> Appearance.FontSize.SMALL
                        R.id.pref_font_large -> Appearance.FontSize.LARGE
                        else -> Appearance.FontSize.MEDIUM
                    }
                )
            }
        }
        findViewById<Switch>(R.id.pref_larger_text).apply {
            isChecked = AppSettings.largerText(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setLargerText(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_high_contrast_focus).apply {
            isChecked = AppSettings.highContrastFocus(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setHighContrastFocus(this@SettingsActivity, checked)
            }
        }
        findViewById<Switch>(R.id.pref_reduced_animations).apply {
            isChecked = AppSettings.reducedAnimations(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setReducedAnimations(this@SettingsActivity, checked)
            }
        }
        // 9.3.0: accessibility & remote — spoken channel names and
        // what a held OK does in playback. The long-press choice
        // takes effect immediately (it is read on every hold).
        findViewById<Switch>(R.id.pref_speak_channel_names).apply {
            isChecked = AppSettings.speakChannelNames(this@SettingsActivity)
            setOnCheckedChangeListener { _, checked ->
                AppSettings.setSpeakChannelNames(this@SettingsActivity, checked)
            }
        }
        findViewById<RadioGroup>(R.id.pref_long_press_ok).apply {
            check(
                when (AppSettings.longPressOkAction(this@SettingsActivity)) {
                    AppSettings.LongPressOkAction.GUIDE -> R.id.pref_long_press_guide
                    AppSettings.LongPressOkAction.NOTHING -> R.id.pref_long_press_nothing
                    else -> R.id.pref_long_press_menu
                }
            )
            setOnCheckedChangeListener { _, checkedId ->
                AppSettings.setLongPressOkAction(
                    this@SettingsActivity,
                    when (checkedId) {
                        R.id.pref_long_press_guide -> AppSettings.LongPressOkAction.GUIDE
                        R.id.pref_long_press_nothing -> AppSettings.LongPressOkAction.NOTHING
                        else -> AppSettings.LongPressOkAction.MENU
                    }
                )
            }
        }
        // 10.0.0 (Nova): What's new.
        findViewById<Button>(R.id.whats_new_button).setOnClickListener {
            startActivity(Intent(this, WhatsNewActivity::class.java))
        }

        shareStatusView = findViewById(R.id.share_status)
        shareButtonView = findViewById(R.id.share_button)

        findViewById<Button>(R.id.add_playlist_button).setOnClickListener {
            showAddPlaylistDialog()
        }

        findViewById<Button>(R.id.add_epg_source_button).setOnClickListener {
            showAddEpgSourceDialog()
        }

        // 8.2.0: re-download + re-parse every EPG source now, then
        // show the fresh per-source status and the missing list.
        findViewById<Button>(R.id.refresh_epg_button).setOnClickListener {
            Toast.makeText(this, R.string.epg_source_refreshing, Toast.LENGTH_SHORT).show()
            lifecycleScope.launch {
                PlaylistRepository.refreshEpg(this@SettingsActivity)
                buildEpgSourcesUi()
                buildMissingGuideUi()
                Toast.makeText(this@SettingsActivity, R.string.epg_source_refreshed, Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<Button>(R.id.export_backup_button).setOnClickListener {
            exportBackup()
        }

        findViewById<Button>(R.id.import_backup_button).setOnClickListener {
            try {
                pickBackupFile.launch("*/*")
            } catch (e: Exception) {
                // Some TVs have no document picker at all.
                Toast.makeText(this, R.string.backup_no_picker, Toast.LENGTH_SHORT).show()
            }
        }

        // 8.0.0: the EPG sources save immediately when added or
        // removed (like the playlists), so Save only writes the
        // recording upload URL now.
        findViewById<Button>(R.id.save_button).setOnClickListener {
            PlaylistRepository.setUploadUrl(this, uploadUrl.text.toString().trim())
            finish()
        }

        findViewById<Button>(R.id.reset_button).setOnClickListener {
            PlaylistRepository.resetUrls(this)
            uploadUrl.setText("")
            buildPlaylistsUi()
            buildEpgSourcesUi()
            buildMissingGuideUi()
            buildChannelGroupsUi()
            buildRecordingsUi()
        }

        findViewById<Button>(R.id.unhide_button).setOnClickListener {
            PlaylistRepository.unhideAllChannels(this)
        }

        findViewById<Button>(R.id.share_button).setOnClickListener {
            if (PlaylistShareServer.isRunning) {
                PlaylistShareServer.stop()
                updateShareUi()
            } else {
                showShareSourceDialog()
            }
        }
        updateShareUi()
    }

    override fun onResume() {
        super.onResume()
        // 9.3.0 (Nova): while the PIN gate is up the settings UI
        // doesn't exist yet — the share views included.
        if (!pinUnlocked) return
        updateShareUi()
        startShareTicker()
        // 8.3.0: a schedule may have been added or cancelled from
        // the guide while Settings was open.
        buildRecordingsUi()
    }

    override fun onPause() {
        super.onPause()
        stopShareTicker()
    }

    /** Restart the app's loading flow with the newly active playlist. */
    private fun restartToMain() {
        val intent = Intent(this, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        finish()
    }

    // ---- Playlists (4.8.0) -------------------------------------------------

    /** One row per playlist: name + URL, the active one marked in green.
     * Tap to activate (and reload); long-press for Rename / Activate or
     * Deactivate / Remove. A disabled playlist (4.9.0) is dimmed with a
     * "Disabled" label and can't be activated by tapping — long-press
     * to re-enable it. */
    private fun buildPlaylistsUi() {
        val container = findViewById<LinearLayout>(R.id.playlists_list)
        container.removeAllViews()
        val active = PlaylistRepository.activePlaylist(this)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        for (playlist in PlaylistRepository.playlists(this)) {
            val isActive = playlist.url == active.url
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setBackgroundResource(R.drawable.settings_row_bg)
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val nameView = TextView(this).apply {
                text = playlist.name
                // Disabled playlists are dimmed; the active name is white.
                setTextColor(if (playlist.enabled) 0xFFFFFFFF.toInt() else 0xFF7A7A85.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val urlView = TextView(this).apply {
                text = playlist.url
                setTextColor(0xFFAAAAAA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            texts.addView(nameView)
            texts.addView(urlView)
            row.addView(texts)
            if (isActive) {
                val activeView = TextView(this).apply {
                    text = getString(R.string.playlist_active)
                    // 9.0.0 (Nova): the chosen accent (this was the
                    // old hard-coded green).
                    setTextColor(AppSettings.accentColor(this@SettingsActivity))
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), 0, 0, 0)
                }
                row.addView(activeView)
            } else if (!playlist.enabled) {
                val disabledView = TextView(this).apply {
                    text = getString(R.string.playlist_disabled)
                    setTextColor(0xFF7A7A85.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(dp(8), 0, 0, 0)
                }
                row.addView(disabledView)
            }
            row.setOnClickListener {
                if (isActive) return@setOnClickListener
                if (!playlist.enabled) {
                    Toast.makeText(
                        this, R.string.playlist_disabled_hint, Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (PlaylistRepository.setActivePlaylist(this, playlist.url)) {
                    Toast.makeText(
                        this, getString(R.string.playlist_switched, playlist.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    restartToMain()
                }
            }
            row.setOnLongClickListener {
                showPlaylistOptionsDialog(playlist)
                true
            }
            container.addView(row)
        }
    }

    /** Rename / Activate or Deactivate / Remove for one playlist
     * (long-press on its row). Deactivating the active playlist
     * switches to the first remaining enabled one and reloads. */
    private fun showPlaylistOptionsDialog(playlist: PlaylistRepository.Playlist) {
        val toggleLabel = if (playlist.enabled) {
            getString(R.string.playlist_deactivate)
        } else {
            getString(R.string.playlist_activate)
        }
        val items = arrayOf(
            getString(R.string.playlist_rename),
            toggleLabel,
            getString(R.string.playlist_remove)
        )
        AlertDialog.Builder(this)
            .setTitle(playlist.name)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> showRenamePlaylistDialog(playlist)
                    1 -> {
                        val wasActive =
                            PlaylistRepository.activePlaylist(this).url == playlist.url
                        val newEnabled = !playlist.enabled
                        if (PlaylistRepository.setPlaylistEnabled(this, playlist.url, newEnabled)) {
                            Toast.makeText(
                                this,
                                getString(
                                    if (newEnabled) R.string.playlist_activated
                                    else R.string.playlist_deactivated,
                                    playlist.name
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                            // Deactivating the active playlist switched
                            // the active one — reload with it.
                            if (!newEnabled && wasActive) restartToMain()
                            else buildPlaylistsUi()
                        } else {
                            Toast.makeText(
                                this, R.string.playlist_cannot_deactivate_last,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    2 -> {
                        AlertDialog.Builder(this)
                            .setMessage(getString(R.string.playlist_remove_confirm, playlist.name))
                            .setPositiveButton(R.string.playlist_remove) { _, _ ->
                                val wasActive =
                                    PlaylistRepository.activePlaylist(this).url == playlist.url
                                if (PlaylistRepository.removePlaylist(this, playlist.url)) {
                                    Toast.makeText(
                                        this, getString(R.string.playlist_removed, playlist.name),
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    // Removing the active playlist already
                                    // activated the first remaining one.
                                    if (wasActive) restartToMain() else buildPlaylistsUi()
                                } else {
                                    Toast.makeText(
                                        this, R.string.playlist_cannot_remove_last,
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            .setNegativeButton(android.R.string.cancel, null)
                            .show()
                    }
                }
            }
            .show()
    }

    /** 9.2.0 (Nova): the keywords button's label carries the
     * current count, so the setting reads at a glance. */
    private fun updateKeywordsButton() {
        val count = AppSettings.guideKeywords(this).size
        findViewById<Button>(R.id.pref_keywords_button).text = if (count == 0) {
            getString(R.string.prefs_keywords_manage)
        } else {
            getString(R.string.prefs_keywords_manage_count, count)
        }
    }

    /** 9.2.0 (Nova): manage the guide keyword markers — the
     * dialog lists the keywords (tap one to remove it) plus an
     * "Add keyword…" row. After every change the dialog reopens
     * on the new list, the same reopen pattern the guide's
     * reminders list uses. */
    private fun showKeywordsDialog() {
        val keywords = AppSettings.guideKeywords(this)
        val items = keywords + getString(R.string.keywords_add)
        AlertDialog.Builder(this)
            .setTitle(R.string.prefs_guide_keywords)
            .setMessage(
                if (keywords.isEmpty()) R.string.keywords_empty else R.string.keywords_hint
            )
            .setItems(items.toTypedArray()) { _, which ->
                if (which == keywords.size) {
                    showAddKeywordDialog()
                } else {
                    val removed = keywords[which]
                    AppSettings.setGuideKeywords(this, keywords - removed)
                    Toast.makeText(
                        this,
                        getString(R.string.keyword_removed, removed),
                        Toast.LENGTH_SHORT
                    ).show()
                    updateKeywordsButton()
                    showKeywordsDialog()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 9.2.0 (Nova): add one keyword (duplicates and blanks are
     * dropped by the store; the toast says when that happened). */
    private fun showAddKeywordDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.keywords_add_title)
            .setView(input)
            .setPositiveButton(R.string.keywords_add) { _, _ ->
                val word = input.text.toString().trim()
                if (word.isNotEmpty()) {
                    val before = AppSettings.guideKeywords(this)
                    AppSettings.setGuideKeywords(this, before + word)
                    val added = AppSettings.guideKeywords(this).size > before.size
                    Toast.makeText(
                        this,
                        if (added) {
                            getString(R.string.keyword_added, word)
                        } else {
                            getString(R.string.keyword_duplicate, word)
                        },
                        Toast.LENGTH_SHORT
                    ).show()
                    updateKeywordsButton()
                }
                showKeywordsDialog()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> showKeywordsDialog() }
            .show()
    }

    // ---- Settings PIN (9.3.0, Nova) ------------------------------------

    /** The PIN button's label follows the state: set one, or
     * change/remove the one that's set. */
    private fun updatePinButton() {
        findViewById<Button>(R.id.pref_pin_button).text = getString(
            if (AppSettings.settingsPinSet(this)) {
                R.string.prefs_pin_change_remove
            } else {
                R.string.prefs_pin_set
            }
        )
    }

    /** A 4-digit numeric password field — the shape every PIN
     * prompt on this screen shares. */
    private fun pinInput(): EditText = EditText(this).apply {
        inputType = InputType.TYPE_CLASS_NUMBER or
            InputType.TYPE_NUMBER_VARIATION_PASSWORD
        filters = arrayOf(InputFilter.LengthFilter(4))
        setSingleLine(true)
    }

    /** A PIN prompt that only lets the caller through when the
     * entered PIN verifies against the stored record. A wrong PIN
     * gets a toast and a fresh prompt; cancelling runs
     * [onCancelled] (the entry gate finishes the activity, the
     * in-Settings prompts just give up). The entered PIN is never
     * logged, shown or stored anywhere. */
    private fun showPinEntryDialog(
        title: String,
        onCancelled: () -> Unit = {},
        onUnlocked: () -> Unit
    ) {
        val input = pinInput()
        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                if (AppSettings.verifySettingsPin(this, input.text.toString())) {
                    onUnlocked()
                } else {
                    Toast.makeText(this, R.string.pin_wrong, Toast.LENGTH_SHORT).show()
                    showPinEntryDialog(title, onCancelled, onUnlocked)
                }
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> onCancelled() }
            .setOnCancelListener { onCancelled() }
            .show()
    }

    /** The PIN button's dialog: with no PIN set it goes straight
     * to setting one; with one set, Change and Remove both first
     * prove the current PIN — a TV left unlocked on the couch
     * must not be enough to strip the lock off. */
    private fun showPinManageDialog() {
        if (!AppSettings.settingsPinSet(this)) {
            showSetPinDialog()
            return
        }
        val items = arrayOf(
            getString(R.string.pin_change),
            getString(R.string.pin_remove)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.prefs_settings_pin)
            .setItems(items) { _, which ->
                showPinEntryDialog(
                    title = getString(R.string.pin_current_title),
                    onUnlocked = {
                        if (which == 0) {
                            showSetPinDialog()
                        } else {
                            AppSettings.clearSettingsPin(this)
                            Toast.makeText(
                                this, R.string.pin_removed, Toast.LENGTH_SHORT
                            ).show()
                            updatePinButton()
                        }
                    }
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Set (or replace) the PIN: enter it, then enter it again —
     * one mistyped digit would otherwise lock the user out of
     * their own settings with no recovery short of clearing the
     * app's data. */
    private fun showSetPinDialog() {
        val input = pinInput()
        AlertDialog.Builder(this)
            .setTitle(R.string.pin_new_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val pin = input.text.toString()
                if (pin.length != 4) {
                    Toast.makeText(this, R.string.pin_must_be_4, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val confirm = pinInput()
                AlertDialog.Builder(this)
                    .setTitle(R.string.pin_confirm_title)
                    .setView(confirm)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        if (confirm.text.toString() == pin) {
                            AppSettings.setSettingsPin(this, pin)
                            Toast.makeText(
                                this, R.string.pin_set_toast, Toast.LENGTH_SHORT
                            ).show()
                            updatePinButton()
                        } else {
                            Toast.makeText(
                                this, R.string.pin_mismatch, Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenamePlaylistDialog(playlist: PlaylistRepository.Playlist) {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setText(playlist.name)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_rename)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlaylistRepository.renamePlaylist(this, playlist.url, input.text.toString())
                buildPlaylistsUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Add a playlist: a name (optional — the host is used when blank)
     * and an http/https M3U URL. */
    private fun showAddPlaylistDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        val nameInput = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.playlist_name_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        val urlInput = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.playlist_url_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        container.addView(nameInput)
        container.addView(urlInput)
        AlertDialog.Builder(this)
            .setTitle(R.string.playlist_add_title)
            .setView(container)
            .setPositiveButton(R.string.playlist_add) { _, _ ->
                val entry = PlaylistRepository.addPlaylist(
                    this, nameInput.text.toString(), urlInput.text.toString()
                )
                if (entry == null) {
                    Toast.makeText(this, R.string.playlist_invalid_url, Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(
                        this, getString(R.string.playlist_added, entry.name),
                        Toast.LENGTH_SHORT
                    ).show()
                    buildPlaylistsUi()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Share (4.7.1, 4.8.0) ----------------------------------------------

    /** Refresh the countdown once a second while the server is running. */
    private fun startShareTicker() {
        stopShareTicker()
        val tick = object : Runnable {
            override fun run() {
                updateShareUi()
                if (PlaylistShareServer.isRunning) {
                    shareUiHandler.postDelayed(this, 1000)
                }
            }
        }
        shareTicker = tick
        shareUiHandler.post(tick)
    }

    private fun stopShareTicker() {
        shareTicker?.let { shareUiHandler.removeCallbacks(it) }
        shareTicker = null
    }

    /** Show the share URL + download URL + import URL + countdown while
     * running, else "Not sharing". */
    private fun updateShareUi() {
        val status = shareStatusView
        val button = shareButtonView
        if (PlaylistShareServer.isRunning) {
            val totalSeconds = (PlaylistShareServer.remainingMillis() / 1000).toInt()
            // Manual "m:ss" — String.format on every 1-second tick.
            val seconds = totalSeconds % 60
            val countdown = "${totalSeconds / 60}:" +
                if (seconds < 10) "0$seconds" else "$seconds"
            status.text = getString(
                R.string.share_running,
                PlaylistShareServer.shareUrl ?: "",
                PlaylistShareServer.downloadUrl ?: "",
                PlaylistShareServer.importUrl ?: "",
                countdown
            )
            button.setText(R.string.share_stop)
        } else {
            status.setText(R.string.share_not_running)
            button.setText(R.string.share_playlist)
        }
    }

    /** Let the user pick what to share: the current playlist, another
     * M3U URL, or a local M3U file. */
    private fun showShareSourceDialog() {
        val items = arrayOf(
            getString(R.string.share_source_current),
            getString(R.string.share_source_url),
            getString(R.string.share_source_file)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.share_source_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> shareCurrentPlaylist()
                    1 -> showShareUrlDialog()
                    2 -> {
                        try {
                            pickM3uFile.launch("*/*")
                        } catch (e: Exception) {
                            // Some TVs have no document picker at all.
                            Toast.makeText(this, R.string.share_no_picker, Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            }
            .show()
    }

    private fun shareCurrentPlaylist() {
        lifecycleScope.launch {
            val result = PlaylistRepository.playlistText(this@SettingsActivity)
            result.onSuccess { text -> startSharing(text) }
                .onFailure {
                    Toast.makeText(this@SettingsActivity, R.string.share_load_failed, Toast.LENGTH_SHORT).show()
                }
        }
    }

    private fun showShareUrlDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://example.com/playlist.m3u"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.share_source_url)
            .setView(input)
            .setPositiveButton(R.string.share_start) { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) return@setPositiveButton
                lifecycleScope.launch {
                    val result = PlaylistRepository.fetchUrlText(url)
                    result.onSuccess { text -> startSharing(text) }
                        .onFailure {
                            Toast.makeText(this@SettingsActivity, R.string.share_load_failed, Toast.LENGTH_SHORT).show()
                        }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Validate the text looks like an M3U playlist, then start the
     * temporary server (10 minutes, stops itself). */
    private fun startSharing(text: String) {
        if (!text.trimStart().startsWith("#EXTM3U")) {
            Toast.makeText(this, R.string.share_not_m3u, Toast.LENGTH_SHORT).show()
            return
        }
        if (!PlaylistShareServer.start(this, text)) {
            Toast.makeText(this, R.string.share_no_wifi, Toast.LENGTH_SHORT).show()
            return
        }
        updateShareUi()
        startShareTicker()
    }

    // ---- EPG sources (8.0.0) --------------------------------------------

    /** One row per EPG source URL, with its last fetch status
     * underneath (8.2.0: which source loaded, which failed, and
     * when). Long-press to remove it (the last source can't be
     * removed — the app always needs one). Sources save
     * immediately, like the playlists. */
    private fun buildEpgSourcesUi() {
        val container = findViewById<LinearLayout>(R.id.epg_sources_list)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val statuses = PlaylistRepository.epgSourceStatuses(this)
            .associateBy { it.url }
        val now = System.currentTimeMillis()
        for (url in PlaylistRepository.epgUrls(this)) {
            val status = statuses[url]
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setBackgroundResource(R.drawable.settings_row_bg)
            }
            val urlView = TextView(this).apply {
                text = url
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }
            row.addView(urlView)
            val statusView = TextView(this).apply {
                text = EpgQuality.statusLine(status, now)
                // A failed source reads red; anything else is muted.
                setTextColor(
                    if (status != null && !status.lastOk) 0xFFFF9E80.toInt()
                    else 0xB3FFFFFF.toInt()
                )
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(2) }
            }
            row.addView(statusView)
            row.setOnLongClickListener {
                showRemoveEpgSourceDialog(url)
                true
            }
            container.addView(row)
        }
    }

    /** Add an EPG source: one http/https XMLTV URL. */
    private fun showAddEpgSourceDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.epg_source_url_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.epg_source_add_title)
            .setView(input)
            .setPositiveButton(R.string.epg_source_add) { _, _ ->
                val url = input.text.toString().trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    Toast.makeText(this, R.string.epg_source_invalid_url, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val current = PlaylistRepository.epgUrls(this)
                if (current.contains(url)) {
                    Toast.makeText(this, R.string.epg_source_added, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                PlaylistRepository.setEpgUrls(this, current + url)
                Toast.makeText(this, R.string.epg_source_added, Toast.LENGTH_SHORT).show()
                buildEpgSourcesUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRemoveEpgSourceDialog(url: String) {
        val current = PlaylistRepository.epgUrls(this)
        if (current.size <= 1) {
            Toast.makeText(this, R.string.epg_source_cannot_remove_last, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.epg_source_remove_confirm)
            .setPositiveButton(R.string.epg_source_remove) { _, _ ->
                PlaylistRepository.setEpgUrls(this, current - url)
                Toast.makeText(this, R.string.epg_source_removed, Toast.LENGTH_SHORT).show()
                buildEpgSourcesUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Channels without guide data (8.2.0) -------------------------
    //
    // Channels with no programmes in the merged guide. Tap one to
    // pick a suggested EPG id (the common mismatches: case,
    // punctuation, country/HD suffix, display-name spelling) or
    // type one in. Assigning sets the per-channel EPG override, so
    // the channel drops off this list on the next build. The list
    // builds asynchronously once the playlist + guide have loaded.

    private fun buildMissingGuideUi() {
        val container = findViewById<LinearLayout>(R.id.missing_guide_list)
        container.removeAllViews()
        lifecycleScope.launch {
            val channels = PlaylistRepository.loadPlaylist(this@SettingsActivity)
                .getOrNull() ?: emptyList()
            val epg = PlaylistRepository.loadEpg(this@SettingsActivity)
            renderMissingGuide(channels, epg)
        }
    }

    private fun renderMissingGuide(
        channels: List<Channel>,
        epg: Map<String, List<EpgParser.Programme>>
    ) {
        val container = findViewById<LinearLayout>(R.id.missing_guide_list)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val missing = EpgQuality.channelsWithoutGuide(
            channels, epg,
            PlaylistRepository.epgOverrides(this),
            PlaylistRepository.hiddenChannels(this)
        )
        if (missing.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.missing_guide_none)
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
            return
        }
        for (channel in missing) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setBackgroundResource(R.drawable.settings_row_bg)
            }
            row.addView(TextView(this).apply {
                text = channel.name
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(TextView(this).apply {
                text = getString(R.string.missing_guide_no_data)
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            })
            // Tap (not long-press): a remote's OK button opens the
            // assign dialog — long-press-only rows are dead on TV.
            row.setOnClickListener {
                showAssignMissingEpgDialog(channel, epg)
            }
            container.addView(row)
        }
    }

    /** One-tap EPG assign: the suggested ids first, then "Enter
     * manually…" for the rest. With no suggestions at all, go
     * straight to the manual field. */
    private fun showAssignMissingEpgDialog(
        channel: Channel,
        epg: Map<String, List<EpgParser.Programme>>
    ) {
        val suggestions = EpgQuality.suggestEpgIds(channel, epg.keys)
        if (suggestions.isEmpty()) {
            showManualAssignDialog(channel)
            return
        }
        val items = (suggestions + getString(R.string.missing_guide_enter_manually))
            .toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.missing_guide_assign_title, channel.name))
            .setItems(items) { _, which ->
                if (which == suggestions.size) {
                    showManualAssignDialog(channel)
                } else {
                    PlaylistRepository.setEpgOverride(this, channel.id, suggestions[which])
                    Toast.makeText(this, R.string.menu_epg_assigned, Toast.LENGTH_SHORT).show()
                    buildMissingGuideUi()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Type an EPG id in by hand (prefilled with the current
     * override or the playlist's tvg-id, blank to clear). */
    private fun showManualAssignDialog(channel: Channel) {
        val input = EditText(this).apply {
            setText(
                PlaylistRepository.epgOverrides(this@SettingsActivity)[channel.id]
                    ?: channel.tvgId.orEmpty()
            )
            hint = getString(R.string.menu_assign_epg_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.menu_assign_epg_title, channel.name))
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlaylistRepository.setEpgOverride(this, channel.id, input.text.toString())
                Toast.makeText(this, R.string.menu_epg_assigned, Toast.LENGTH_SHORT).show()
                buildMissingGuideUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Channel groups (8.1.0) ------------------------------------------
    //
    // One row per playlist group: display name + visible channel
    // count. Long-press a row for Rename / Move up / Move down /
    // Reset. Channels are moved between groups from the guide's
    // long-press menu, not here — this screen manages the groups
    // themselves. The rows build synchronously when the playlist is
    // already in memory, else once the load lands.

    /** All playlist groups (raw names, playlist order) with their
     * effective visible counts — including groups whose channels
     * were all moved away (0 channels), so they can still be
     * renamed, reordered, or moved back into. */
    private fun groupSummaries(
        channels: List<Channel>
    ): List<Triple<String, String, Int>> {
        val overrides = PlaylistRepository.groupOverrides(this)
        val hidden = PlaylistRepository.hiddenChannels(this)
        val visible = channels.filter { it.id !in hidden }
        val counts = GroupOverrides.effectiveGroups(visible, overrides)
            .associate { it.key to it.channels.size }
        return channels.map { it.group }.distinct().map { key ->
            Triple(key, GroupOverrides.displayGroup(key, overrides), counts[key] ?: 0)
        }
    }

    private fun buildChannelGroupsUi() {
        val container = findViewById<LinearLayout>(R.id.channel_groups_list)
        container.removeAllViews()
        val cached = PlaylistRepository.cachedPlaylist()
        if (cached != null) {
            renderChannelGroups(cached)
            return
        }
        lifecycleScope.launch {
            val channels = PlaylistRepository.loadPlaylist(this@SettingsActivity)
                .getOrNull() ?: emptyList()
            renderChannelGroups(channels)
        }
    }

    private fun renderChannelGroups(channels: List<Channel>) {
        val container = findViewById<LinearLayout>(R.id.channel_groups_list)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val summaries = groupSummaries(channels)
        if (summaries.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.channel_groups_empty)
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
            return
        }
        for ((key, displayName, count) in summaries) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setBackgroundResource(R.drawable.settings_row_bg)
            }
            val nameView = TextView(this).apply {
                text = displayName
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val countView = TextView(this).apply {
                text = getString(R.string.channel_groups_count, count)
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            row.addView(nameView)
            row.addView(countView)
            // Tap opens the options too — on a TV remote OK fires a
            // click, not a long-press, so long-press alone would
            // leave the row dead for remote users.
            row.setOnClickListener {
                showGroupOptionsDialog(key, displayName)
            }
            row.setOnLongClickListener {
                showGroupOptionsDialog(key, displayName)
                true
            }
            container.addView(row)
        }
    }

    /** Long-press options for one group: rename, move up/down in
     * the sidebar order, or reset it to its playlist name + order. */
    private fun showGroupOptionsDialog(groupKey: String, displayName: String) {
        val labels = arrayOf(
            getString(R.string.group_rename),
            getString(R.string.group_move_up),
            getString(R.string.group_move_down),
            getString(R.string.group_reset)
        )
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.group_options_title, displayName))
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showRenameGroupDialog(groupKey, displayName)
                    1 -> moveGroup(groupKey, up = true)
                    2 -> moveGroup(groupKey, up = false)
                    3 -> resetGroup(groupKey)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showRenameGroupDialog(groupKey: String, displayName: String) {
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val input = EditText(this).apply {
            setText(displayName)
            hint = getString(R.string.group_rename_hint)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.group_rename_title)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PlaylistRepository.setGroupRename(this, groupKey, input.text.toString())
                Toast.makeText(this, R.string.group_renamed, Toast.LENGTH_SHORT).show()
                buildChannelGroupsUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Move a group one step up or down in the custom order. */
    private fun moveGroup(groupKey: String, up: Boolean) {
        val channels = PlaylistRepository.cachedPlaylist() ?: return
        val allGroups = channels.map { it.group }.distinct()
        val order = PlaylistRepository.groupOverrides(this).order
        val full = order.filter { it in allGroups } +
            allGroups.filter { it !in order }
        val updated = GroupOverrides.reorderedOrder(order, allGroups, groupKey, up)
        // No-op when the group is already first (up) or last (down).
        if (updated == full) return
        PlaylistRepository.setGroupOrder(this, updated)
        Toast.makeText(this, R.string.group_moved, Toast.LENGTH_SHORT).show()
        buildChannelGroupsUi()
    }

    /** Clear a group's rename and drop it from the custom order —
     * it goes back to its playlist name and playlist position.
     * Channels moved into it stay moved. */
    private fun resetGroup(groupKey: String) {
        PlaylistRepository.setGroupRename(this, groupKey, "")
        val order = PlaylistRepository.groupOverrides(this).order - groupKey
        PlaylistRepository.setGroupOrder(this, order)
        Toast.makeText(this, R.string.group_reset_done, Toast.LENGTH_SHORT).show()
        buildChannelGroupsUi()
    }

    // ---- Recordings (8.3.0) ----------------------------------------------
    //
    // Auto-delete setting (a row that opens a picker) and the
    // scheduled recordings picked in the guide (tap one to cancel
    // it — the alarm is cancelled too).

    private fun buildRecordingsUi() {
        val keepDays = AppSettings.autoDeleteRecordingsDays(this)
        findViewById<TextView>(R.id.recordings_auto_delete_row).apply {
            text = getString(R.string.recordings_auto_delete_title) + ": " +
                if (keepDays <= 0) getString(R.string.auto_delete_off)
                else getString(R.string.auto_delete_days, keepDays)
            setOnClickListener { showAutoDeleteDialog(keepDays) }
        }
        val container = findViewById<LinearLayout>(R.id.scheduled_recordings_list)
        container.removeAllViews()
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val scheduled = PlaylistRepository.scheduledRecordings(this)
        if (scheduled.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.scheduled_recordings_empty)
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(16), dp(12), dp(16), dp(12))
            })
            return
        }
        for (rec in scheduled) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isFocusableInTouchMode = true
                setPadding(dp(16), dp(12), dp(16), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4); bottomMargin = dp(4) }
                setBackgroundResource(R.drawable.settings_row_bg)
            }
            val nameView = TextView(this).apply {
                text = rec.title
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val timeView = TextView(this).apply {
                text = "${rec.channelName} • ${GuideData.formatDate(rec.startMillis)}, " +
                    GuideData.formatClock(rec.startMillis)
                setTextColor(0xB3FFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
            row.addView(nameView)
            row.addView(timeView)
            row.setOnClickListener { showCancelScheduledDialog(rec) }
            row.setOnLongClickListener {
                showCancelScheduledDialog(rec)
                true
            }
            container.addView(row)
        }
    }

    /** Pick how many days of finished recordings to keep (0 = off). */
    private fun showAutoDeleteDialog(currentDays: Int) {
        val options = intArrayOf(0, 7, 14, 30, 90)
        val labels = options.map { days ->
            if (days <= 0) getString(R.string.auto_delete_off)
            else getString(R.string.auto_delete_days, days)
        }.toTypedArray()
        val checked = options.indexOf(currentDays).takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_delete_choose_title)
            .setSingleChoiceItems(labels, checked) { dialog, which ->
                AppSettings.setAutoDeleteRecordingsDays(this, options[which])
                dialog.dismiss()
                buildRecordingsUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Confirm, then cancel the alarm and drop the schedule. */
    private fun showCancelScheduledDialog(rec: PlaylistRepository.ScheduledRecording) {
        AlertDialog.Builder(this)
            .setTitle(rec.title)
            .setMessage(
                "${rec.channelName} • ${GuideData.formatDate(rec.startMillis)}, " +
                    GuideData.formatTimeRange(rec.startMillis, rec.stopMillis)
            )
            .setPositiveButton(R.string.delete) { _, _ ->
                RecordingManager.cancelRecordingStart(this, rec)
                PlaylistRepository.removeScheduledRecording(this, rec.channelId, rec.startMillis)
                Toast.makeText(
                    this, R.string.recording_schedule_cancelled, Toast.LENGTH_SHORT
                ).show()
                buildRecordingsUi()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ---- Backup & restore (8.0.0) ----------------------------------------

    /** Export the backup: write opentv-backup.json to the cache dir
     * and to Downloads (so it can be pulled off the TV), then offer
     * the Android share sheet. On a TV there may be no app to share
     * with — the Downloads copy is the fallback, and the toast says
     * so. */
    private fun exportBackup() {
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                BackupManager.encode(BackupManager.export(this@SettingsActivity))
            }
            val file = withContext(Dispatchers.IO) {
                try {
                    File(cacheDir, "opentv-backup.json").apply {
                        writeText(json, Charsets.UTF_8)
                    }
                } catch (e: Exception) {
                    null
                }
            }
            if (file == null) {
                Toast.makeText(this@SettingsActivity, R.string.backup_export_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val inDownloads = withContext(Dispatchers.IO) { saveBackupToDownloads(json) }
            if (inDownloads) {
                Toast.makeText(this@SettingsActivity, R.string.backup_exported, Toast.LENGTH_SHORT).show()
            }
            val uri = FileProvider.getUriForFile(
                this@SettingsActivity, "${packageName}.fileprovider", file
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "application/json"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "OpenTV backup")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            try {
                startActivity(Intent.createChooser(send, getString(R.string.backup_export)))
            } catch (e: ActivityNotFoundException) {
                if (inDownloads) {
                    Toast.makeText(this@SettingsActivity, R.string.backup_share_failed, Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.backup_export_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /** Copy the backup JSON into the public Downloads folder. Best
     * effort — returns false when the platform refuses (e.g. no
     * storage permission on an old Android), and the cache-dir file
     * + share sheet still work. */
    private fun saveBackupToDownloads(json: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, "opentv-backup.json")
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = contentResolver.insert(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                ) ?: return false
                contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: return false
                true
            } else {
                val downloads = Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOWNLOADS
                )
                File(downloads, "opentv-backup.json").apply {
                    parentFile?.mkdirs()
                    writeText(json, Charsets.UTF_8)
                }
                true
            }
        } catch (e: Exception) {
            false
        }
    }
}
