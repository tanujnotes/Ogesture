package com.ogesture.service

import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Optional Xiaomi/HyperOS system-navigation watchdog. When the user opts in, and all
 * prerequisites hold (supported device, [android.Manifest.permission.WRITE_SECURE_SETTINGS]
 * granted, master gestures on, service bound), it keeps the OEM three-button navigation bar
 * hidden by enforcing two `Settings.Global` keys:
 *
 *  - `force_fsg_nav_bar = 1`
 *  - `hide_gesture_line = 1`
 *
 * The design is **observer-driven, not polling-driven**: [ContentObserver]s on those two keys
 * fire when HyperOS resets them (e.g. when the system resolver/chooser appears), and the
 * controller re-enforces the desired state. It does not try to predict which UI transition
 * caused the reset — it observes the actual settings and maintains the user-selected state.
 *
 * The pure enforcement state machine lives in [SystemNavigationEnforcer] (testable without
 * Android); this class owns the service lifetime, the DataStore flow reaction, the
 * ContentObserver registration, and the delayed HyperOS-retry scheduling.
 *
 * **Lifecycle**: this is a SERVICE-LIFETIME singleton (created once, not per-binding). The
 * `bound` flag is toggled by [onServiceBound]/[onServiceUnbound]; the enforcer runs only while
 * bound, but its baseline/restore state survives unbind so the system nav buttons come back.
 * This avoids old↔new-controller races (each per-binding controller had its own mutex).
 */
class SystemNavigationController(
    private val context: Context,
    private val repo: SettingsRepository,
    private val scope: CoroutineScope,
    private val gateway: SecureSettingsGateway = AndroidSecureSettingsGateway(context),
    private val handler: Handler = Handler(Looper.getMainLooper()),
    /** Test seam for device support. Production default: [isXiaomiEcosystemDevice]. */
    private val deviceSupported: () -> Boolean = { isXiaomiEcosystemDevice() },
    /** Test seam for the WRITE_SECURE_SETTINGS permission check. Production default checks it. */
    private val permissionGranted: () -> Boolean = {
        context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    },
) {
    private var enabled = false
    private var masterOn = false
    private var bound = false
    private var observerRegistered = false
    private var started = false
    // Initialization barrier: stays false until the first DataStore snapshot (hideSystemNavigation +
    // masterEnabled) has been read, so onServiceBound doesn't recompute with default false/false and
    // briefly restore 0/0 then re-enforce 1/1 (navbar flash on process restart).
    private var initialized = false
    // Runtime readiness of the gesture overlay zones. Set by [onGestureRuntimeReady]; when false,
    // the system nav buttons are NOT hidden even if all other preconditions hold — Ogesture can't
    // replace the system bar unless its own gesture zones are actually attached.
    @Volatile private var gestureRuntimeReady = false

    private val enforcer = SystemNavigationEnforcer(
        gateway = gateway,
        baselineStore = object : BaselineStore {
            override suspend fun read() = repo.navBaseline.first()
            override suspend fun write(baseline: SettingsRepository.NavBaseline) = repo.setNavBaseline(baseline)
            override suspend fun clear() = repo.clearNavBaseline()
        },
        deviceSupported = deviceSupported,
        permissionGranted = permissionGranted,
    )

    // Single-writer serialization: EVERY enforcer call goes through this mutex — no exceptions.
    // Observer, retry, settings change, bind/unbind, shutdown: all acquire it.
    private val mutex = Mutex()

    // Independent scope for fail-safe restore that must survive scope.cancel() in onDestroy.
    // Without this, the service's scope.cancel() could cancel the restore coroutine before it
    // completes, leaving the system navbar hidden.
    private val restoreScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) {
            scope.launch { mutex.withLock { enforcer.reassert(tag = "observer") } }
        }
    }

    /**
     * Starts the controller (idempotent — launches the DataStore collector once). The controller
     * lives for the service lifetime, so this is called on the first [onServiceConnected] and
     * subsequent binds just call [onServiceBound].
     */
    fun start() {
        if (started) return
        started = true
        scope.launch {
            mutex.withLock { enforcer.loadPersistedBaseline() }
            // Read the first snapshot synchronously before allowing bind/unbind to recompute,
            // so onServiceBound doesn't see default false/false and briefly restore 0/0.
            val firstHide = repo.hideSystemNavigation.first()
            val firstMaster = repo.masterEnabled.first()
            mutex.withLock {
                enabled = firstHide
                masterOn = firstMaster
                initialized = true
            }
            // Now process bind/unbind if it arrived while we were reading.
            if (bound) recomputeEnforcement(cause = "init-bind")
            // Then observe future changes.
            combine(repo.hideSystemNavigation, repo.masterEnabled) { hide, master -> hide to master }
                .distinctUntilChanged()
                .collect { (hide, master) -> onSettingsChanged(hide = hide, master = master) }
        }
    }

    /** Called when the opt-in setting or the master gesture switch changes. Public for tests. */
    fun onSettingsChanged(hide: Boolean, master: Boolean) {
        enabled = hide
        masterOn = master
        scope.launch { recomputeEnforcement(cause = "settings") }
    }

    fun onServiceBound() {
        bound = true
        // Don't recompute until the first DataStore snapshot is loaded (initialization barrier).
        // The start() coroutine will recompute after init.
        if (initialized) restoreScope.launch { recomputeEnforcement(cause = "bound") }
    }

    fun onServiceUnbound() {
        bound = false
        gestureRuntimeReady = false // overlay is being torn down
        if (initialized) restoreScope.launch { recomputeEnforcement(cause = "unbind") }
    }

    /**
     * Called by [EdgeOverlayController] when all gesture zones are attached (or detached).
     * The system nav is only hidden while Ogesture's own navigation is actually working —
     * if addView fails for any zone, the system bar stays visible.
     */
    fun onGestureRuntimeReady(ready: Boolean) {
        gestureRuntimeReady = ready
        if (initialized) restoreScope.launch { recomputeEnforcement(cause = if (ready) "overlay-ready" else "overlay-down") }
    }

    /**
     * Called by the service's 30s watchdog. Retries a pending restore (one that failed earlier
     * due to missing permission) if permission has returned. No SettingsProvider work when there
     * is no pending restore, so the watchdog pays nothing 99.99% of the time.
     */
    fun retryPendingRestoreIfNeeded() {
        scope.launch { mutex.withLock { enforcer.retryPendingRestoreIfNeeded() } }
    }

    /**
     * Full shutdown for [onDestroy]: deactivate + restore (if a baseline exists), unregister the
     * observer, cancel retry work. The service is being destroyed, so the system nav must come
     * back if it was hidden.
     */
    fun shutdown() {
        handler.removeCallbacks(retryRunnable)
        bound = false
        enabled = false
        masterOn = false
        gestureRuntimeReady = false
        // Use restoreScope (independent of the service scope) so scope.cancel() in onDestroy
        // cannot cancel this restore — the system nav buttons must come back even as the service
        // is being destroyed.
        restoreScope.launch {
            mutex.withLock {
                enforcer.deactivateAndRestoreIfNeeded(tag = "shutdown")
                unregisterObserver()
            }
        }
    }

    /**
     * Recompute whether enforcement should be active and act on the transition. Called from any
     * state change (setting toggle, master switch, bind/unbind). Serialized through [mutex].
     *
     * The `else` branch (shouldEnforce=false) ALWAYS calls [deactivateAndRestoreIfNeeded]: if
     * enforcement was active, it stops + restores; if a baseline exists but enforcement was never
     * active in this instance (e.g. process restart with the preference ON but master off), it
     * still restores — so the system nav buttons come back whenever Ogesture can't navigate.
     */
    private suspend fun recomputeEnforcement(cause: String) {
        mutex.withLock {
            val shouldEnforce = enabled && masterOn && bound && gestureRuntimeReady && deviceSupported() && permissionGranted()
            if (shouldEnforce) {
                enforcer.startEnforcing(tag = "enable:$cause")
                registerObserver()
                // HyperOS may rewrite its nav setting several times during one UI transition. A
                // couple of delayed re-checks catch a reset that lands slightly after enforcement.
                handler.removeCallbacks(retryRunnable)
                handler.postDelayed(retryRunnable, RETRY_1_MS)
                handler.postDelayed(retryRunnable, RETRY_2_MS)
            } else {
                // Fail-safe: if Ogesture cannot provide navigation, the system nav buttons must
                // come back — whether or not enforcement was active in this instance.
                enforcer.deactivateAndRestoreIfNeeded(tag = "disable:$cause")
                unregisterObserver()
            }
        }
    }

    private val retryRunnable = Runnable {
        scope.launch {
            mutex.withLock {
                enforcer.reassert(tag = "retry")
                enforcer.retryPendingRestoreIfNeeded()
            }
        }
    }

    private fun registerObserver() {
        if (observerRegistered) return
        val cr = context.contentResolver
        try {
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_FORCE_FSG_NAV_BAR), false, observer)
            cr.registerContentObserver(Settings.Global.getUriFor(KEY_HIDE_GESTURE_LINE), false, observer)
            observerRegistered = true
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to register observers", t)
        }
    }

    private fun unregisterObserver() {
        if (!observerRegistered) return
        try {
            context.contentResolver.unregisterContentObserver(observer)
        } catch (_: Throwable) { /* never registered or already unregistered */ }
        observerRegistered = false
    }

    companion object {
        private const val TAG = "SysNavWatchdog"
        const val KEY_FORCE_FSG_NAV_BAR = "force_fsg_nav_bar"
        const val KEY_HIDE_GESTURE_LINE = "hide_gesture_line"
        private const val RETRY_1_MS = 250L
        private const val RETRY_2_MS = 1000L

        /** Xiaomi/Redmi/POCO ecosystem detection (manufacturer + brand, case-insensitive). */
        fun isXiaomiEcosystemDevice(
            manufacturer: String = Build.MANUFACTURER,
            brand: String = Build.BRAND,
        ): Boolean {
            val m = manufacturer.lowercase()
            val b = brand.lowercase()
            return m == "xiaomi" || b == "xiaomi" ||
                m == "redmi" || b == "redmi" ||
                m == "poco" || b == "poco"
        }
    }
}

/**
 * Abstraction over [Settings.Global] so the enforcement logic can be unit-tested without a real
 * Android ContentResolver/SettingsProvider. The real implementation is
 * [AndroidSecureSettingsGateway]; tests supply a fake that records reads/writes.
 */
interface SecureSettingsGateway {
    /**
     * Reads a Settings.Global integer, distinguishing three outcomes: the key is present with
     * a value, the key is absent, or the read itself failed (a temporary provider exception).
     * A failure must NEVER be conflated with absence.
     */
    fun read(key: String): SettingReadResult
    /** Writes an integer value. Throws [SecurityException] if the permission is missing. */
    fun putInt(key: String, value: Int)
    /** Deletes the setting (restores an absent value). */
    fun delete(key: String)
}

sealed interface SettingReadResult {
    data class Present(val value: Int) : SettingReadResult
    data object Absent : SettingReadResult
    data class Failure(val cause: Throwable) : SettingReadResult
}

/** Real [Settings.Global]-backed implementation. */
private class AndroidSecureSettingsGateway(private val context: Context) : SecureSettingsGateway {
    private val resolver: ContentResolver get() = context.contentResolver

    override fun read(key: String): SettingReadResult = try {
        val v = Settings.Global.getInt(resolver, key, ABSENT_SENTINEL)
        if (v == ABSENT_SENTINEL) SettingReadResult.Absent else SettingReadResult.Present(v)
    } catch (t: Throwable) {
        SettingReadResult.Failure(t)
    }

    override fun putInt(key: String, value: Int) {
        Settings.Global.putInt(resolver, key, value)
    }

    override fun delete(key: String) {
        Settings.Global.putString(resolver, key, null)
    }

    private companion object {
        // Settings.Global.getInt(resolver, key, def) returns `def` when the key is absent. Use a
        // sentinel unlikely to collide with a real nav-bar setting value.
        private const val ABSENT_SENTINEL = Int.MIN_VALUE + 7
    }
}
