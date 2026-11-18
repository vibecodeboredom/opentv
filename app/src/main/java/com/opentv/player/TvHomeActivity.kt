package com.opentv.player

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

/**
 * 9.0.0 — TV home screen host. The TV opens here instead of
 * auto-playing (unless the launch-behaviour setting says
 * auto-play); the fragment carries the rails. Back from playback
 * returns here, like the mobile home.
 */
class TvHomeActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_tv_home)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.tv_home_frame, TvHomeFragment())
                .commit()
        }
    }
}
