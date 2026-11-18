package com.opentv.player

import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.FragmentActivity

/**
 * 10.0.0 (Nova): "What's new in Nova" — the release-notes home
 * for the whole Nova line, one honest line per release from
 * 7.0.0 to today, compressed from the branch's own commit
 * history (headlines only; the commits carry the detail). The
 * version lines take the chosen accent colour, like every other
 * Nova surface since 9.0.0.
 */
class WhatsNewActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_whats_new)

        val list = findViewById<LinearLayout>(R.id.whats_new_list)
        val density = resources.displayMetrics.density
        fun dp(v: Int): Int = (v * density).toInt()
        val accent = AppSettings.accentColor(this)
        for ((version, summary) in RELEASES) {
            val versionView = TextView(this).apply {
                text = version
                setTextColor(accent)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                typeface = Typeface.DEFAULT_BOLD
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(18) }
            }
            val summaryView = TextView(this).apply {
                text = summary
                setTextColor(0xFFFFFFFF.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(4) }
            }
            list.addView(versionView)
            list.addView(summaryView)
        }
    }

    private companion object {
        /** Newest first. One line per release — what the release
         * was FOR, in the words its commit used, kept honest:
         * nothing here claims more than the release shipped. */
        val RELEASES = listOf(
            "10.0.0" to "The finale: this What's new screen, backup & restore of your Nova data, and a clock on the home screen.",
            "9.3.1" to "Programme descriptions in the playback overlay, keyword hits match descriptions too, and a Starts in countdown in the guide details.",
            "9.3.0" to "Programme descriptions in the guide and search, a countdown on watchlist cards, and an optional PIN lock for Settings.",
            "9.2.1" to "A keyword hits list in the guide menu, favorite channels' recordings protected from auto-delete, the discovery row on mobile, and the channel-info favorite button fixed.",
            "9.2.0" to "A 'More of what you watch' discovery row, a recordings storage cap with auto-delete, and ★ keyword markers in the guide.",
            "9.1.1" to "Start from beginning for resumed playback, a day banner in the guide, and catch-up expiry times in the details.",
            "9.1.0" to "Your own favorites order (honoured by the rows, the sidebar and favorites-zap), catch-up resume, and a Tonight jump in the guide.",
            "9.0.0" to "Recording resume with a Continue watching row, your choice of accent colour, and a comfort text size for the guide.",
            "8.3.1" to "A surf interval choice, watchlist cards open the guide, and 'up next' on the screensaver.",
            "8.3.0" to "An idle-pause screensaver with a clock, Surf mode channel-hopping, and a Watchlist row on the home screen.",
            "8.2.1" to "Recording overlap warnings, a combined Remind + record action, and progress bars on the On now cards.",
            "8.2.0" to "An 'On now' favorites row on the home screen, scheduled recordings from the guide, and day-jump browsing.",
            "8.1.1" to "Unhide all channels, guide zoom (2h / 3h / 4h), and the group filter scoped per playlist.",
            "8.1.0" to "A guide filter by channel group, the channel logo on the now-playing bar, and jump to now.",
            "8.0.0" to "Programme reminders with a reminders list, a now-playing bar in the guide, and sleep fade-out.",
            "7.3.1" to "Subtitle and audio track pickers, a Reload stream action, an audio-only hint, and the reconnect attempt count.",
            "7.3.0" to "The Nova banner, sleep at the end of a programme, audio-only mode, auto-reconnect for live streams, and a stream quality selector.",
            "7.2.1" to "The previous channel remembered across sessions, remembered night dim, prefetch during catch-up, and single-choice picker dialogs.",
            "7.2.0" to "Previous-channel swap, favorites-only zapping, a stream info overlay, and night dim.",
            "7.1.1" to "The sleep countdown in the menu, a prefetch race fix, and HLS warm-up.",
            "7.1.0" to "Adjacent-channel prefetch for faster zaps, a sleep timer for recordings, an instant zap flash, and the guide now-line creep.",
            "7.0.0" to "OpenTV Nova begins — its own app beside the original: in-place channel zapping, a sleep timer, a Recently watched row, guide memory, and the first Nova banner."
        )
    }
}
