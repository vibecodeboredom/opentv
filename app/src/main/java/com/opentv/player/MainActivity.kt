package com.opentv.player

import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Launcher (v4.1.0): no menu. OpenTV starts straight into a random
 * channel, like turning on a TV. OK while watching opens the TV Guide;
 * in the guide, holding OK opens the side menu.
 *
 * If the playlist can't load (or is empty), falls back to the browse
 * menu so the app is never a dead screen.
 */
class MainActivity : FragmentActivity() {

    private var spinner: ProgressBar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // A spinner while the playlist loads, so launch never looks dead.
        val frame = findViewById<FrameLayout>(R.id.main_frame)
        spinner = ProgressBar(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        frame.addView(spinner)

        lifecycleScope.launch {
            val result = PlaylistRepository.loadPlaylist(this@MainActivity)
            result.onSuccess { channels ->
                val hidden = PlaylistRepository.hiddenChannels(this@MainActivity)
                val visible = channels.filter { it.id !in hidden }
                val pick = (visible.ifEmpty { channels }).randomOrNull()
                if (pick != null) {
                    val intent = Intent(this@MainActivity, PlaybackActivity::class.java)
                    intent.putExtra(PlaybackActivity.EXTRA_CHANNEL, pick)
                    startActivity(intent)
                    finish()
                } else {
                    showBrowse()
                }
            }.onFailure {
                Toast.makeText(
                    this@MainActivity,
                    R.string.load_failed,
                    Toast.LENGTH_LONG
                ).show()
                showBrowse()
            }
        }
    }

    private fun showBrowse() {
        spinner?.let { findViewById<FrameLayout>(R.id.main_frame).removeView(it) }
        spinner = null
        supportFragmentManager.beginTransaction()
            .replace(R.id.main_frame, BrowseFragment())
            .commit()
    }
}
