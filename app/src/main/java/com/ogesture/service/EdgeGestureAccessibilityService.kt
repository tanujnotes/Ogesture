package com.ogesture.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
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

class EdgeGestureAccessibilityService : AccessibilityService(), GestureDispatcher {

    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())
    private val repo by lazy { SettingsRepository.get(this) }
    @Volatile private var masterEnabled = false

    // Per-binding job: created fresh on every onServiceConnected, cancelled on unbind/destroy so
    // old controllers and their Flow collectors can't survive a reconnect and stack.
    private var connectionJob: kotlinx.coroutines.Job? = null
    private val connectionScope get() = CoroutineScope(scope.coroutineContext + (connectionJob ?: scope.coroutineContext[kotlinx.coroutines.Job]!!))

    // Owns the gesture-zone and indicator windows. Created when the service binds and
    // destroyed when it unbinds, so the windows follow the accessibility-service lifecycle
    // exactly: Android rebinds the service after process death and the controller comes
    // back with it, re-attaching zones if the master switch is still on — no foreground
    // service or boot receiver is needed for that revival.
    private var controller: EdgeOverlayController? = null

    // Optional HyperOS system-navigation watchdog. This is a SERVICE-LIFETIME singleton (not
    // per-binding): it owns one enforcer, one mutex, one baseline state across all bind/unbind
    // cycles. Creating it per-binding caused old↔new controller races (each had its own mutex).
    // The `bound` flag is toggled by onServiceBound/onServiceUnbound; the enforcer runs only while
    // bound, but its baseline/restore state survives unbind so the system nav buttons come back.
    private val sysNavController by lazy { SystemNavigationController(this, repo, scope) }

    // The gesture zones are accessibility-overlay windows, which the system does not hide on
    // secure screens and does not tie to a foreground service. The only requirement this
    // service can observe besides its own binding is unrestricted battery (the system and
    // OEM battery managers can still kill the process if it is restricted), so the watchdog
    // now only re-checks that, and re-asserts the controller in case the master flow hasn't
    // attached the zones yet (e.g. right after a rebind racing the datastore read).
    private val watchdog = object : Runnable {
        override fun run() {
            disableIfBroken()
            // Retry a pending system-nav restore (failed earlier due to missing permission).
            // No-op (no SettingsProvider work) when there's no pending restore.
            sysNavController?.retryPendingRestoreIfNeeded()
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
        // Defensive: if a previous binding left overlay state alive (Android can call
        // onServiceConnected again without a clean onUnbind), tear it down before creating a
        // fresh overlay controller so collectors/observers/windows never stack.
        tearDownOverlayConnection()
        connectionJob = kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
        instance = this
        bound.value = true
        refreshImePackages()
        controller = EdgeOverlayController(
            context = this,
            windowManager = getSystemService(WINDOW_SERVICE) as android.view.WindowManager,
            repo = repo,
            dispatcher = this,
            scope = connectionScope,
            onRuntimeReadyChange = { ready -> sysNavController.onGestureRuntimeReady(ready) },
        ).also { it.start() }
        // Service-lifetime watchdog: start once (idempotent), then just toggle bound. The
        // controller's own state (baseline/restore/mutex) survives across bind/unbind cycles.
        sysNavController.start()
        sysNavController.onServiceBound()
        connectionScope.launch {
            repo.masterEnabled.collect { enabled ->
                masterEnabled = enabled
                // The controller's own master flow attaches/detaches the zones; this just
                // keeps the local flag in sync for the watchdog.
            }
        }
        handler.removeCallbacks(watchdog)
        handler.postDelayed(watchdog, WATCHDOG_INTERVAL_MS)
    }

    /**
     * Tears down the per-binding OVERLAY state (controller + connectionJob + watchdog). The
     * service-lifetime [sysNavController] is left intact (it owns the system-nav baseline across
     * unbind/rebind). Used by [onServiceConnected] (defensive) and [onUnbind].
     */
    private fun tearDownOverlayConnection() {
        handler.removeCallbacks(watchdog)
        sysNavController.onServiceUnbound() // toggle bound=false; restore if needed (serialized)
        controller?.stop()
        controller = null
        connectionJob?.cancel()
        connectionJob = null
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        controller?.onConfigurationChanged(newConfig)
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        bound.value = false
        tearDownOverlayConnection()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        bound.value = false
        handler.removeCallbacks(watchdog)
        // Stop the overlay controller directly (NOT tearDownOverlayConnection — that would call
        // sysNavController.onServiceUnbound(), launching a restore that scope.cancel() below would
        // cancel). The sysNavController.shutdown() below does the single fail-safe restore.
        controller?.stop()
        controller = null
        connectionJob?.cancel()
        connectionJob = null
        // Full shutdown of the service-lifetime watchdog. This uses the controller's own
        // restoreScope (independent of the service scope) so scope.cancel() can't cancel it.
        sysNavController.shutdown()
        scope.cancel()
        super.onDestroy()
    }

    /**
     * Background safety net: if gestures are enabled but unrestricted battery has been
     * revoked, turn the switch off and tell the user. The accessibility service being bound
     * is implied — this only runs from its own watchdog. The in-app screen covers the same
     * cases (plus the accessibility-unbound case) faster while it is open.
     */
    private fun disableIfBroken() {
        if (!masterEnabled) return
        if (isBatteryUnrestricted()) return
        scope.launch { repo.setMasterEnabled(false) }
        Toast.makeText(this, R.string.toast_gestures_off_battery, Toast.LENGTH_LONG).show()
    }

    private fun isBatteryUnrestricted(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
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

    override fun trigger(action: GestureAction) {
        when (action) {
            GestureAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureAction.RECENTS -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    /**
     * Re-injects a touch the overlay consumed but didn't use, so it reaches the UI
     * underneath. Returns false if the gesture could not be dispatched; onDone always
     * runs otherwise, with whether the gesture played to completion.
     */
    override fun replay(gesture: GestureDescription, onDone: (completed: Boolean) -> Unit): Boolean =
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
        private const val WATCHDOG_INTERVAL_MS = 30_000L

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

        /**
         * Backed StateFlow of whether the service is currently bound — the UI collects this
         * instead of polling. Set true in onServiceConnected, false in onUnbind/onDestroy.
         */
        val bound = MutableStateFlow(false)
    }
}
