package com.opentv.player

import android.os.Bundle
import androidx.fragment.app.FragmentActivity

class RecordingsActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 6.0.0-rc1: TV stays landscape; mobile is free (portrait).
        DeviceType.applyOrientation(this)
        setContentView(R.layout.activity_main)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.main_frame, RecordingsFragment())
                .commit()
        }
    }
}
