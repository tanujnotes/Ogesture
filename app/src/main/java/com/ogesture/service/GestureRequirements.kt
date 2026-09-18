package com.ogesture.service

import android.content.Context
import android.os.PowerManager
import androidx.annotation.StringRes
import com.ogesture.R

/**
 * The two things edge gestures need in order to run at all. Every watcher asks this one
 * question — the in-app poller, the accessibility service's watchdog — so "can gestures run?"
 * cannot drift between them, and a restore is gated on exactly the answer a disable is.
 *
 * There is no overlay permission to check: the gesture zones are accessibility-overlay
 * windows owned by the accessibility service, so they exist exactly while it is bound.
 */
object GestureRequirements {

    /** String res explaining the first unmet requirement, or null when gestures can run. */
    @StringRes
    fun missingReason(context: Context): Int? = when {
        !EdgeGestureAccessibilityService.isBound() -> R.string.toast_gestures_off_accessibility
        !isBatteryUnrestricted(context) -> R.string.toast_gestures_off_battery
        else -> null
    }

    fun isBatteryUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }
}
