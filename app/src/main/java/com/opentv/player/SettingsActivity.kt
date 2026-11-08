package com.opentv.player

import android.app.AlertDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Lets the user change the M3U playlist URL, the (optional) XMLTV EPG URL,
 * and the (optional) recording upload URL. Also shows an About section
 * with the current app version, and (4.7.1) a "Share playlist" section
 * that temporarily hosts a playlist on this TV's Wi-Fi so other devices
 * on the same network can import it — the server stops itself after
 * 10 minutes. */
class SettingsActivity : FragmentActivity() {

    /** Ticks once a second while sharing, to refresh the countdown. */
    private val shareUiHandler = Handler(Looper.getMainLooper())
    private var shareTicker: Runnable? = null

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<TextView>(R.id.about_version).text =
            getString(R.string.about_version, BuildConfig.VERSION_NAME)

        val playlistUrl = findViewById<EditText>(R.id.playlist_url)
        val epgUrl = findViewById<EditText>(R.id.epg_url)
        val uploadUrl = findViewById<EditText>(R.id.upload_url)
        playlistUrl.setText(PlaylistRepository.playlistUrl(this))
        epgUrl.setText(PlaylistRepository.epgUrl(this))
        uploadUrl.setText(PlaylistRepository.uploadUrl(this))

        findViewById<Button>(R.id.save_button).setOnClickListener {
            PlaylistRepository.setUrls(
                this,
                playlistUrl.text.toString().trim(),
                epgUrl.text.toString().trim()
            )
            PlaylistRepository.setUploadUrl(this, uploadUrl.text.toString().trim())
            finish()
        }

        findViewById<Button>(R.id.reset_button).setOnClickListener {
            PlaylistRepository.resetUrls(this)
            playlistUrl.setText(PlaylistRepository.DEFAULT_PLAYLIST_URL)
            epgUrl.setText(PlaylistRepository.DEFAULT_EPG_URL)
            uploadUrl.setText("")
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
        updateShareUi()
        startShareTicker()
    }

    override fun onPause() {
        super.onPause()
        stopShareTicker()
    }

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

    /** Show the share URL + countdown while running, else "Not sharing". */
    private fun updateShareUi() {
        val status = findViewById<TextView>(R.id.share_status)
        val button = findViewById<Button>(R.id.share_button)
        if (PlaylistShareServer.isRunning) {
            val totalSeconds = (PlaylistShareServer.remainingMillis() / 1000).toInt()
            val countdown = "%d:%02d".format(totalSeconds / 60, totalSeconds % 60)
            status.text = getString(
                R.string.share_running,
                PlaylistShareServer.shareUrl ?: "",
                PlaylistShareServer.downloadUrl ?: "",
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
}
