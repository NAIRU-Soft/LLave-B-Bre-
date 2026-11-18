package com.nairusoft.bbre.ui

import android.app.Activity
import android.os.Build

/**
 * Applies a fade transition after starting/finishing an Activity using modern API 34+
 * transitions with a fallback for older Android versions.
 */
fun Activity.applyFadeCloseTransition() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        overrideActivityTransition(
            Activity.OVERRIDE_TRANSITION_CLOSE,
            android.R.anim.fade_in,
            android.R.anim.fade_out
        )
    } else {
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
