package com.ogesture.service

import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Persisted crash-recovery snapshot of the two navigation settings, captured before the watchdog
 * first enforces. Separated as an interface so the pure [SystemNavigationEnforcer] logic can be
 * unit-tested without a real DataStore.
 */
interface BaselineStore {
    /** The currently-persisted baseline (captured=false if none). */
    suspend fun read(): SettingsRepository.NavBaseline
    /** Persist the baseline BEFORE any enforcement write. */
    suspend fun write(baseline: SettingsRepository.NavBaseline)
    /** Clear after a successful restore. */
    suspend fun clear()
}

/**
 * Pure, Android-Context-free enforcement logic for the system-navigation watchdog. Separated
 * from [SystemNavigationController] so it can be unit-tested without a real SettingsProvider.
 *
 * Responsibilities:
 *  - capture the prior values of `force_fsg_nav_bar` / `hide_gesture_line` once per enable,
 *    **persisted before the first enforcement write** so a process death mid-enforcement can
 *    still restore the real original state;
 *  - distinguish an absent key from a read failure (a temporary provider exception never becomes
 *    "absent" and never triggers a delete during restore);
 *  - enforce both to `1`, writing only keys not already `1` (no redundant writes / observer loop);
 *  - restore the persisted baseline when enforcement ends (handling a previously-absent key by
 *    deleting it);
 *  - never write when the device is unsupported or the permission is missing.
 */
class SystemNavigationEnforcer(
    private val gateway: SecureSettingsGateway,
    private val baselineStore: BaselineStore,
    private val deviceSupported: () -> Boolean,
    private val permissionGranted: () -> Boolean,
) {
    @Volatile private var enforcing = false
    private var baselineForce: Int? = null
    private var baselineForcePresent = false
    private var baselineHide: Int? = null
    private var baselineHidePresent = false
    private var capturedBaseline = false
    // True after a restore failed (e.g. permission revoked) and the baseline is still pending.
    // The controller polls this from the 30s watchdog + ON_RESUME to retry the restore once the
    // permission returns, without any SettingsProvider polling.
    @Volatile private var pendingRestore = false

    /** Whether enforcement is currently active. */
    val isActive: Boolean get() = enforcing
    /** Whether a pending restore is waiting for permission to return. */
    val hasPendingRestore: Boolean get() = pendingRestore

    /**
     * Begin enforcing the desired state. Captures + persists the baseline values once (before any
     * write). Returns true if enforcement started, false if a precondition fails (unsupported
     * device, no permission, or a baseline read failure). Idempotent.
     */
    suspend fun startEnforcing(tag: String = "enable"): Boolean {
        if (!deviceSupported() || !permissionGranted()) return false
        if (!enforcing) {
            if (!captureBaseline()) return false // fail safe: read failure -> no enforcement
        }
        enforcing = true
        enforceNow(tag)
        return true
    }

    /**
     * Stop enforcing and restore the captured baseline values (or delete keys that were absent
     * before enforcement). Clears the persisted baseline only after a successful restore.
     */
    suspend fun stopEnforcing(tag: String = "disable") {
        if (!enforcing) return
        val restored = restoreBaseline(tag)
        enforcing = false
        if (restored) {
            clearInMemoryBaseline()
        } else {
            // Restore failed (e.g. permission revoked) — keep the baseline in RAM + persisted
            // for a later retry once permission returns.
            pendingRestore = true
        }
    }

    /**
     * Deactivate enforcement AND restore the baseline if one exists — even if enforcement was
     * never active in *this* enforcer instance (e.g. a process restart where the preference is
     * ON but `shouldEnforce=false` because master is off or permission is missing). This is the
     * fail-safe path: if Ogesture cannot provide navigation, the system nav buttons must come back.
     *
     * - If `enforcing == true`: stop enforcing and restore.
     * - If `enforcing == false` but a baseline exists (captured or persisted): restore it.
     * - If permission is missing: set `pendingRestore=true` (baseline stays for a later retry).
     * - If no baseline exists: no-op.
     */
    suspend fun deactivateAndRestoreIfNeeded(tag: String = "deactivate") {
        if (enforcing) {
            val restored = restoreBaseline(tag)
            enforcing = false
            if (restored) {
                clearInMemoryBaseline()
            } else {
                pendingRestore = true
            }
        } else if (capturedBaseline) {
            // Not enforcing, but a baseline exists (e.g. process restart with the preference ON
            // but master off / permission missing). Restore it so the system nav comes back.
            if (!deviceSupported() || !permissionGranted()) {
                pendingRestore = true // can't restore yet
                return
            }
            val restored = restoreBaseline(tag)
            if (restored) {
                clearInMemoryBaseline()
            } else {
                pendingRestore = true
            }
        }
    }

    /**
     * Load a persisted baseline (from a previous process that died mid-enforcement, or a pending
     * restore after permission loss) WITHOUT recapturing. Called on startup. Does not overwrite
     * an existing in-memory baseline.
     */
    suspend fun loadPersistedBaseline() {
        if (capturedBaseline) return
        val b = baselineStore.read()
        if (b.captured) {
            baselineForcePresent = b.forcePresent
            baselineForce = if (b.forcePresent) b.forceValue else null
            baselineHidePresent = b.hidePresent
            baselineHide = if (b.hidePresent) b.hideValue else null
            capturedBaseline = true
        }
    }

    /**
     * If a persisted baseline exists (captured=true) and we are not currently enforcing, attempt
     * to restore it now. This handles: (a) the preference is OFF but a restore failed earlier due
     * to missing permission, then the service reconnected; (b) a process restart where the
     * preference is OFF but the baseline was left pending. Only restores if device+permission
     * allow; otherwise leaves the baseline pending for a later retry. No-op if no baseline.
     */
    suspend fun attemptPendingRestore() {
        if (!capturedBaseline && !pendingRestore) return
        if (enforcing) return // already enforcing — restore would be wrong
        if (!deviceSupported() || !permissionGranted()) {
            // Can't restore yet — set pendingRestore so the 30s watchdog retries when permission returns.
            pendingRestore = true
            return
        }
        val restored = restoreBaseline(tag = "pending-restore")
        if (restored) {
            clearInMemoryBaseline()
        } else {
            pendingRestore = true
        }
    }

    /**
     * Called by the controller's 30s watchdog + ON_RESUME. If a restore is pending (a previous
     * restore failed due to missing permission) and permission has returned, retry it now.
     * No-op (no SettingsProvider work) when there's no pending restore — so the 30s watchdog pays
     * nothing 99.99% of the time.
     */
    suspend fun retryPendingRestoreIfNeeded() {
        if (!pendingRestore) return
        if (enforcing) { pendingRestore = false; return } // got re-enabled; no restore needed
        if (!deviceSupported() || !permissionGranted()) return
        val restored = restoreBaseline(tag = "watchdog-restore")
        if (restored) {
            clearInMemoryBaseline()
        }
    }

    /**
     * Capture the prior values of both keys exactly once per enforcement lifecycle, persisting
     * them BEFORE any write. Returns false if a read failed (fail safe: do not enforce).
     */
    private fun clearInMemoryBaseline() {
        capturedBaseline = false
        pendingRestore = false
        baselineForce = null
        baselineForcePresent = false
        baselineHide = null
        baselineHidePresent = false
    }

    private suspend fun captureBaseline(): Boolean {
        if (capturedBaseline) return true
        val force = withContext(Dispatchers.IO) { read(KEY_FORCE_FSG_NAV_BAR) }
        val hide = withContext(Dispatchers.IO) { read(KEY_HIDE_GESTURE_LINE) }
        // A read failure must block enforcement — never conflate with absence.
        if (force is SettingReadResult.Failure || hide is SettingReadResult.Failure) return false
        baselineForcePresent = force is SettingReadResult.Present
        baselineForce = (force as? SettingReadResult.Present)?.value
        baselineHidePresent = hide is SettingReadResult.Present
        baselineHide = (hide as? SettingReadResult.Present)?.value
        capturedBaseline = true
        // Persist before enforcing so a process death can restore the real original state.
        baselineStore.write(
            SettingsRepository.NavBaseline(
                captured = true,
                forcePresent = baselineForcePresent,
                forceValue = baselineForce ?: 0,
                hidePresent = baselineHidePresent,
                hideValue = baselineHide ?: 0,
            ),
        )
        return true
    }

    private suspend fun enforceNow(tag: String) {
        withContext(Dispatchers.IO) {
            writeIfNotOne(KEY_FORCE_FSG_NAV_BAR, "$tag:force")
            writeIfNotOne(KEY_HIDE_GESTURE_LINE, "$tag:hide")
        }
    }

    private suspend fun restoreBaseline(tag: String): Boolean {
        val (okForce, okHide) = withContext(Dispatchers.IO) {
            restoreKey(KEY_FORCE_FSG_NAV_BAR, baselineForcePresent, baselineForce, "$tag:force") to
                restoreKey(KEY_HIDE_GESTURE_LINE, baselineHidePresent, baselineHide, "$tag:hide")
        }
        // Only clear the persisted baseline after a successful restore; a failed restore
        // (e.g. permission revoked) keeps the baseline pending for a later retry.
        if (okForce && okHide) baselineStore.clear()
        return okForce && okHide
    }

    private suspend fun writeIfNotOne(key: String, tag: String) {
        when (val current = read(key)) {
            is SettingReadResult.Present -> if (current.value == 1) return
            is SettingReadResult.Failure -> return // can't read -> don't write blindly
            is SettingReadResult.Absent -> { } // absent -> enforce
        }
        try {
            gateway.putInt(key, 1)
        } catch (_: SecurityException) {
            // No WRITE_SECURE_SETTINGS — leave the system value as-is.
        } catch (_: Throwable) {
            // Other failure — leave the system value as-is.
        }
    }

    private suspend fun restoreKey(key: String, present: Boolean, baseline: Int?, tag: String): Boolean {
        return try {
            if (!present) gateway.delete(key)
            else if (baseline != null) gateway.putInt(key, baseline)
            true
        } catch (_: SecurityException) {
            false // No WRITE_SECURE_SETTINGS — baseline stays pending.
        } catch (_: Throwable) {
            false // Other failure — baseline stays pending.
        }
    }

    private fun read(key: String): SettingReadResult = try {
        gateway.read(key)
    } catch (t: Throwable) {
        SettingReadResult.Failure(t)
    }

    /** Re-assert is non-suspend at the call site (observer/timeout); run reads/writes on IO. */
    suspend fun reassert(tag: String) {
        if (!enforcing) return
        withContext(Dispatchers.IO) {
            writeIfNotOne(KEY_FORCE_FSG_NAV_BAR, "$tag:force")
            writeIfNotOne(KEY_HIDE_GESTURE_LINE, "$tag:hide")
        }
    }

    companion object {
        const val KEY_FORCE_FSG_NAV_BAR = "force_fsg_nav_bar"
        const val KEY_HIDE_GESTURE_LINE = "hide_gesture_line"
    }
}
