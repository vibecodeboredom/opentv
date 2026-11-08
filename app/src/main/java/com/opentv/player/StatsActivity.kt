package com.opentv.player

import android.os.Bundle
import android.util.TypedValue
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.launch

/**
 * Local-only statistics: total watch time, most-watched channels,
 * recordings, favorites and channel counts. Nothing here is shared or
 * sent anywhere — it all lives on this device.
 */
@OptIn(UnstableApi::class)
class StatsActivity : FragmentActivity() {

    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_stats)
        container = findViewById(R.id.stats_container)

        findViewById<Button>(R.id.clear_stats_button).setOnClickListener {
            StatsManager.clear(this)
            loadStats()
            Toast.makeText(this, R.string.statistics_cleared, Toast.LENGTH_SHORT).show()
        }

        loadStats()
    }

    private fun loadStats() {
        val stats = StatsManager.getStats(this)
        container.removeAllViews()

        // Live counts (playlist + recordings) are computed here, not stored.
        lifecycleScope.launch {
            val playlistResult = PlaylistRepository.loadPlaylist(this@StatsActivity)
            val channels = playlistResult.getOrNull() ?: emptyList()
            val favorites = PlaylistRepository.favorites(this@StatsActivity).size
            val recordings = RecordingManager.listRecordings(this@StatsActivity)
            val recordingsBytes = RecordingManager.totalBytesUsed(this@StatsActivity)

            runOnUiThread {
                addSection(getString(R.string.stats_watching))
                addRow(
                    getString(R.string.stats_total_watch_time),
                    StatsManager.formatDuration(stats.totalWatchMillis)
                )
                addRow(
                    getString(R.string.stats_total_sessions),
                    stats.totalSessions.toString()
                )
                if (stats.topChannels.isNotEmpty()) {
                    addSection(getString(R.string.stats_most_watched))
                    stats.topChannels.take(5).forEachIndexed { index, channel ->
                        addRow(
                            "${index + 1}. ${channel.name}",
                            "${StatsManager.formatDuration(channel.watchMillis)} • ${channel.sessions} ${getString(R.string.stats_sessions)}"
                        )
                    }
                }

                addSection(getString(R.string.stats_library))
                addRow(getString(R.string.stats_channels), channels.size.toString())
                addRow(getString(R.string.stats_favorites), favorites.toString())
                addRow(getString(R.string.stats_recordings), recordings.size.toString())
                addRow(
                    getString(R.string.stats_recordings_size),
                    RecordingManager.formatBytes(recordingsBytes)
                )
            }
        }
    }

    private fun addSection(title: String) {
        container.addView(TextView(this).apply {
            text = title
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(20) }
        })
    }

    private fun addRow(label: String, value: String) {
        container.addView(TextView(this).apply {
            text = "$label:  $value"
            setTextColor(0xFFDDDDDD.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dpToPx(8) }
        })
    }

    private fun dpToPx(dp: Int): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            resources.displayMetrics
        ).toInt()
}
