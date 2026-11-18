package com.opentv.player

import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.util.Rational

/**
 * 10.0.0 — Picture-in-picture. Keep watching the current channel
 * in a small window while browsing the guide (or the home screen):
 * the playback menu's "Watch in small window" enters PiP and opens
 * the guide on top; the PiP window's "Full screen" action brings
 * playback back to full screen.
 *
 * Why this shape:
 * - The player is built in onStart and released in onStop, and
 *   entering PiP pauses the activity WITHOUT stopping it — so the
 *   player keeps playing untouched while the window is small. The
 *   onStop release is guarded by [isInPictureInPictureMode] anyway,
 *   for the edge case where the system does stop the activity
 *   while the window is up (screen off): releasing the player
 *   there would black out the window.
 * - The activity declares supportsPictureInPicture + configChanges
 *   in the manifest, so entering PiP never recreates it (a
 *   recreate would release + rebuild the player mid-transition).
 * - Picking a channel in the guide starts PlaybackActivity with
 *   CLEAR_TOP, which brings the PiP instance to the front
 *   fullscreen — the same flags the expand action uses, so both
 *   paths out of the small window behave the same.
 * - A device without the PiP feature simply never shows the menu
 *   entry ([isSupported]); entering PiP is never attempted there.
 *
 * Combined port (from juniper-v10, onto Nova's playback): Nova's
 * zapping is in-place — switchToChannel re-points the running
 * player with setMediaItem/prepare/play and never releases it —
 * so a zap can never black out the PiP window. The one release
 * that could, PlaybackActivity.onStop's, is guarded by
 * isInPictureInPictureMode (see there). Picking a channel in the
 * guide relaunches PlaybackActivity with CLEAR_TOP, which exits
 * PiP and starts the picked channel fullscreen, as above.
 */
object PictureInPicture {

    /** The small window is 16:9, like the stream. */
    const val ASPECT_WIDTH = 16
    const val ASPECT_HEIGHT = 9

    /** This device can do picture-in-picture at all. */
    fun isSupported(context: Context): Boolean =
        context.packageManager.hasSystemFeature(
            PackageManager.FEATURE_PICTURE_IN_PICTURE
        )

    /**
     * Whether entering PiP makes sense right now. Pure, so it is
     * unit-testable: no feature → no entry; already in PiP → no
     * re-entry; no player → nothing to watch in the window.
     */
    fun canEnterPip(hasFeature: Boolean, isInPip: Boolean, hasPlayer: Boolean): Boolean =
        hasFeature && !isInPip && hasPlayer

    /** The intent the PiP window's "Full screen" action fires:
     * bring the existing playback activity to the front fullscreen
     * (CLEAR_TOP finishes anything above it, like the guide;
     * SINGLE_TOP reuses the instance instead of recreating it, so
     * the player keeps playing instead of restarting). */
    fun expandIntent(context: Context): Intent =
        Intent(context, PlaybackActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

    /** PiP params for this playback: 16:9 window + the one action
     * that matters, "Full screen". */
    fun buildParams(context: Context): PictureInPictureParams {
        val expand = PendingIntent.getActivity(
            context,
            0,
            expandIntent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val fullScreen = RemoteAction(
            Icon.createWithResource(context, R.drawable.ic_fullscreen),
            context.getString(R.string.pip_fullscreen),
            context.getString(R.string.pip_fullscreen),
            expand
        )
        return PictureInPictureParams.Builder()
            .setAspectRatio(Rational(ASPECT_WIDTH, ASPECT_HEIGHT))
            .setActions(listOf(fullScreen))
            .build()
    }
}
