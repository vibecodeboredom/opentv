package com.opentv.player

import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import androidx.fragment.app.FragmentActivity

/** Lets the user change the M3U playlist URL, the (optional) XMLTV EPG URL,
 * and the (optional) recording upload URL. */
class SettingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

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
    }
}
