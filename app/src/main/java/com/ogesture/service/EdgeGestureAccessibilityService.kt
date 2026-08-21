package com.ogesture.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import com.ogesture.R
import com.ogesture.data.GestureAction
import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class EdgeGestureAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val repo by lazy { SettingsRepository.get(this) }
    @Volatile private var masterEnabled = false

    // The overlay foreground service is the fragile half of the gesture pipeline: Android — and
    // Samsung battery management in particular — can kill it without killing this accessibility
    // service, which the framework keeps bound and rebinds automatically. This watchdog re-asserts
    // the overlay service periodically so the gesture zones come back on their own, instead of the
    // user having to open the app and toggle the switch.
    private val watchdog = object : Runnable {
        override fun run() {
            disableIfBroken()
            ensureOverlayRunning()
            refreshImePackages()
            handler.postDelayed(this, WATCHDOG_INTERVAL_MS)
        }
    }

    // Keyboards fire window-state events with their own package when they open, but there is
    // no matching event when they close — treating one as "the foreground app" would silently
    // end pass-through while the user is still inside an excluded app. So IME packages are
    // ignored for foreground tracking. Manifest <queries> grants visibility of IMEs only.
    @Volatile private var imePackages: Set<String> = emptySet()

    private fun refreshImePackages() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imePackages = try {
            imm.inputMethodList.mapTo(mutableSetOf()) { it.packageName }
        } catch (_: Throwable) {
            imePackages
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        refreshImePackages()
        scope.launch {
            repo.masterEnabled.collect { enabled ->
                masterEnabled = enabled
                // Revive immediately when enabled — covers the common case where the whole
                // process was killed and the framework has just rebound us.
                if (enabled) ensureOverlayRunning()
            }
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        handler.removeCallbacks(watchdog)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacks(watchdog)
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Background safety net: if gestures are enabled but a requirement this service can
     * observe has gone away (overlay permission or unrestricted battery), turn the switch
     * off and tell the user. The accessibility service being bound is implied — this only
     * runs from its own watchdog. The in-app screen covers the same cases (plus the
     * accessibility-unbound case) faster while it is open.
     */
    private fun disableIfBroken() {
        if (!masterEnabled) return
        val reason = when {
            !Settings.canDrawOverlays(this) -> R.string.toast_gestures_off_overlay
            !isBatteryUnrestricted() -> R.string.toast_gestures_off_battery
            else -> return
        }
        scope.launch { repo.setMasterEnabled(false) }
        Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
    }

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun ensureOverlayRunning() {
        if (!masterEnabled || EdgeOverlayService.isRunning) return
        if (!Settings.canDrawOverlays(this)) return
        try {
            // Apps holding SYSTEM_ALERT_WINDOW are exempt from background foreground-service
            // start restrictions, so this is allowed from here whenever overlays are permitted.
            EdgeOverlayService.start(this)
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to revive overlay service", t)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Only the foreground package name is read — never window content. It drives the
        // per-app pass-through: zones go untouchable while an excluded app is in front.
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName || pkg in imePackages) return
        foregroundPackage.value = pkg
    }

    override fun onInterrupt() { /* no-op */ }

    fun trigger(action: GestureAction) {
        when (action) {
            GestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            GestureAction.NONE -> {}
        }
    }

    /**
     * Re-injects a touch the overlay consumed but didn't use, so it reaches the UI
     * underneath. Returns false if the gesture could not be dispatched; onDone always
     * runs otherwise, with whether the gesture played to completion.
     */
    fun replay(gesture: GestureDescription, onDone: (completed: Boolean) -> Unit): Boolean =
        dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = onDone(true)
                override fun onCancelled(gestureDescription: GestureDescription?) = onDone(false)
            },
            handler,
        )

    companion object {
        private const val TAG = "EdgeGestureA11y"
        private const val WATCHDOG_INTERVAL_MS = 10_000L

        @Volatile
        var instance: EdgeGestureAccessibilityService? = null
            private set

        /**
         * Package of the app currently in front, from window-state-changed events (never
         * window content). Dialogs and keyboards can briefly report their own package;
         * the overlay only compares it against the user's excluded list, so that noise
         * at worst flips pass-through for a moment.
         */
        val foregroundPackage = MutableStateFlow<String?>(null)

        /** True iff the service appears in the system's enabled-accessibility-services setting. */
        fun isEnabledInSettings(context: Context): Boolean {
            val expected = "${context.packageName}/${EdgeGestureAccessibilityService::class.java.name}"
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
            while (splitter.hasNext()) {
                if (splitter.next().equals(expected, ignoreCase = true)) return true
            }
            return false
        }

        /** True iff the system has actually bound this service in the current process. */
        fun isBound(): Boolean = instance != null
    }
}
