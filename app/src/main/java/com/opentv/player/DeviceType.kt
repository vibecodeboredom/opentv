package com.opentv.player

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.app.UiModeManager

/**
 * TV vs phone/tablet detection (6.0.0-rc1, first mobile-capable
 * release). The TV experience must stay exactly as it is — this
 * helper is the single place that decides which UI a device gets.
 *
 * A device counts as a television when the system says its UI mode
 * is television, or when it reports the Leanback feature (Android
 * TV devices do both). Everything else — phones, tablets, and
 * plain Android boxes without Leanback — is "mobile": touch-first
 * UI, free orientation. (A plain box driven by a remote still
 * works: the mobile home list is a RecyclerView whose rows are
 * focusable, so DPAD + centre works the same as touch.)
 */
object DeviceType {

    fun isTelevision(context: Context): Boolean {
        val uiModeManager =
            context.getSystemService(Context.UI_MODE_SERVICE) as? UiModeManager
        if (uiModeManager?.currentModeType == Configuration.UI_MODE_TYPE_TELEVISION) {
            return true
        }
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    fun isMobile(context: Context): Boolean = !isTelevision(context)

    /**
     * Lock the activity to landscape on a TV (as the manifest used
     * to); leave orientation free on mobile so portrait works.
     * Call at the top of onCreate, after super.onCreate().
     */
    fun applyOrientation(activity: Activity) {
        if (isTelevision(activity)) {
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }
}
