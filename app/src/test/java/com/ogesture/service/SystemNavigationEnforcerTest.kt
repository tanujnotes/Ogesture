package com.ogesture.service

import com.ogesture.data.SettingsRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure enforcement logic of the system-navigation watchdog
 * ([SystemNavigationEnforcer]) and device-support detection. A [FakeGateway] stands in for
 * Settings.Global and a [FakeBaselineStore] for the persisted crash-recovery snapshot.
 */
class SystemNavigationEnforcerTest {

    private class FakeGateway : SecureSettingsGateway {
        val store = mutableMapOf<String, Int>()
        val writes = mutableListOf<Pair<String, Int>>()
        val deletes = mutableListOf<String>()
        var denyWrites = false
        var readFailures: Set<String> = emptySet()

        override fun read(key: String): SettingReadResult =
            if (key in readFailures) SettingReadResult.Failure(RuntimeException("provider error"))
            else if (key in store) SettingReadResult.Present(store[key]!!)
            else SettingReadResult.Absent

        override fun putInt(key: String, value: Int) {
            if (denyWrites) throw SecurityException("no permission")
            store[key] = value
            writes += key to value
        }
        override fun delete(key: String) {
            if (denyWrites) throw SecurityException("no permission")
            store.remove(key)
            deletes += key
        }
    }

    private class FakeBaselineStore : BaselineStore {
        var current = SettingsRepository.NavBaseline(false, false, 0, false, 0)
        val writes = mutableListOf<SettingsRepository.NavBaseline>()
        var cleared = 0
        override suspend fun read() = current
        override suspend fun write(b: SettingsRepository.NavBaseline) { current = b; writes += b }
        override suspend fun clear() { current = SettingsRepository.NavBaseline(false, false, 0, false, 0); cleared++ }
    }

    private fun enforcer(
        fake: FakeGateway,
        baseline: FakeBaselineStore = FakeBaselineStore(),
        supported: Boolean = true,
        granted: Boolean = true,
    ) = SystemNavigationEnforcer(fake, baseline, { supported }, { granted })

    private val FORCE = SystemNavigationController.KEY_FORCE_FSG_NAV_BAR
    private val HIDE = SystemNavigationController.KEY_HIDE_GESTURE_LINE

    @Test fun deviceSupport_recognizesXiaomiRedmiPoco() {
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice("Xiaomi", "xiaomi"))
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice("redmi", "redmi"))
        assertTrue(SystemNavigationController.isXiaomiEcosystemDevice("poco", "POCO"))
        assertFalse(SystemNavigationController.isXiaomiEcosystemDevice("Samsung", "samsung"))
        assertFalse(SystemNavigationController.isXiaomiEcosystemDevice("Google", "google"))
    }

    @Test fun unsupportedDevice_noWrites() = runBlocking {
        val fake = FakeGateway()
        enforcer(fake, supported = false).startEnforcing()
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun permissionMissing_noWrites_noCrash() = runBlocking {
        val fake = FakeGateway().apply { denyWrites = true }
        enforcer(fake, granted = false).startEnforcing()
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun startEnforcing_writesKeysToOne() = runBlocking {
        val fake = FakeGateway()
        val bs = FakeBaselineStore()
        enforcer(fake, bs).startEnforcing()
        assertEquals(1, fake.store[FORCE])
        assertEquals(1, fake.store[HIDE])
        assertTrue(bs.writes.isNotEmpty()) // baseline persisted before enforcement
    }

    @Test fun alreadyOne_noRedundantWrites() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        enforcer(fake).startEnforcing()
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun forceChangesToZero_restoredToOne() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val e = enforcer(fake); e.startEnforcing()
        fake.writes.clear()
        fake.store[FORCE] = 0
        e.reassert("observer")
        assertEquals(1, fake.store[FORCE])
    }

    @Test fun hideChangesToZero_restoredToOne() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val e = enforcer(fake); e.startEnforcing()
        fake.writes.clear()
        fake.store[HIDE] = 0
        e.reassert("observer")
        assertEquals(1, fake.store[HIDE])
    }

    @Test fun bothChangeToZero_bothRestored() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val e = enforcer(fake); e.startEnforcing()
        fake.writes.clear()
        fake.store[FORCE] = 0; fake.store[HIDE] = 0
        e.reassert("observer")
        assertEquals(1, fake.store[FORCE]); assertEquals(1, fake.store[HIDE])
    }

    @Test fun baselineCapturedOncePerEnable() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val e = enforcer(fake); e.startEnforcing()
        fake.store[FORCE] = 0; fake.store[HIDE] = 0
        e.reassert("observer")
        e.stopEnforcing()
        assertEquals(0, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
    }

    @Test fun disablingRestoresOriginalNonZeroValues() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 0 }
        val e = enforcer(fake); e.startEnforcing()
        e.stopEnforcing()
        assertEquals(2, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
    }

    @Test fun nullOriginalValues_handledByDeleting() = runBlocking {
        val fake = FakeGateway()
        val e = enforcer(fake); e.startEnforcing()
        assertEquals(1, fake.store[FORCE]); assertEquals(1, fake.store[HIDE])
        e.stopEnforcing()
        assertNull(fake.store[FORCE]); assertNull(fake.store[HIDE])
        assertTrue(fake.deletes.contains(FORCE)); assertTrue(fake.deletes.contains(HIDE))
    }

    @Test fun reEnforceAfterStop_capturesFreshBaseline() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val e = enforcer(fake); e.startEnforcing(); e.stopEnforcing()
        fake.store[FORCE] = 3; fake.store[HIDE] = 4
        e.startEnforcing()
        assertEquals(1, fake.store[FORCE]); assertEquals(1, fake.store[HIDE])
        e.stopEnforcing()
        assertEquals(3, fake.store[FORCE]); assertEquals(4, fake.store[HIDE])
    }

    @Test fun stopEnforcing_isNoOpWhenNotEnforcing() = runBlocking {
        val fake = FakeGateway()
        enforcer(fake).stopEnforcing()
        assertTrue(fake.writes.isEmpty())
    }

    @Test fun startEnforcing_isIdempotent_noRecapture() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        val writesAfterStart = bs.writes.size
        e.startEnforcing()
        assertEquals(writesAfterStart, bs.writes.size)
    }

    @Test fun permissionRevokedMidEnforcement_writesFailSafely() = runBlocking {
        val fake = FakeGateway()
        val e = enforcer(fake, granted = true); e.startEnforcing()
        assertEquals(1, fake.store[FORCE])
        fake.store[FORCE] = 0; fake.denyWrites = true
        e.reassert("observer")
        assertEquals(0, fake.store[FORCE])
    }

    @Test fun readFailure_blocksEnforcement() = runBlocking {
        val fake = FakeGateway().apply { readFailures = setOf(FORCE) }
        val e = enforcer(fake); assertFalse(e.startEnforcing())
        assertFalse(e.isActive)
    }

    @Test fun readFailure_neverBecomesAbsent_onRestore() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2 }
        val e = enforcer(fake); e.startEnforcing()
        // Simulate a read failure during restore: the key must NOT be deleted.
        fake.readFailures = setOf(FORCE)
        e.stopEnforcing()
        assertEquals(2, fake.store[FORCE]) // not deleted despite read failure
    }

    @Test fun baselinePersistsAcrossControllerRecreation() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val bs = FakeBaselineStore()
        val e1 = enforcer(fake, bs); e1.startEnforcing()
        // Process dies; new controller created.
        val e2 = enforcer(fake, bs)
        e2.loadPersistedBaseline()
        assertTrue(bs.current.captured)
        fake.store[FORCE] = 1; fake.store[HIDE] = 1 // HyperOS reset while dead
        e2.startEnforcing()
        e2.stopEnforcing()
        // Original baseline (0/0) restored, not the 1/1 seen on restart.
        assertEquals(0, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
    }

    @Test fun successfulRestore_clearsPersistedBaseline() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing(); e.stopEnforcing()
        assertFalse(bs.current.captured)
        assertTrue(bs.cleared > 0)
    }

    @Test fun failedRestore_keepsPendingBaseline() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        fake.denyWrites = true // permission revoked before restore
        e.stopEnforcing()
        // Restore failed (SecurityException) -> baseline stays persisted for retry.
        assertTrue(bs.current.captured)
    }

    @Test fun loadPersistedBaseline_doesNotOverwriteExisting() = runBlocking {
        val fake = FakeGateway()
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 5, true, 7)
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline() // loads the 5/7 baseline
        e.startEnforcing() // should NOT recapture (already loaded)
        // Enforce wrote 1/1; stop and restore to the loaded 5/7 baseline.
        e.stopEnforcing()
        assertEquals(5, fake.store[FORCE])
        assertEquals(7, fake.store[HIDE])
    }

    // --- Follow-up hardening regression tests ---

    @Test fun attemptPendingRestore_restoresWhenPermissionReturns() = runBlocking {
        // Feature was ON, baseline captured, then permission revoked -> restore failed, baseline pending.
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 3 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        // System now has 1/1 (enforced).
        assertEquals(1, fake.store[FORCE]); assertEquals(1, fake.store[HIDE])
        // Permission revoked -> restore fails, baseline stays pending.
        fake.denyWrites = true
        e.stopEnforcing()
        assertTrue(bs.current.captured) // pending
        // Permission returns -> attemptPendingRestore should restore 2/3 and clear baseline.
        fake.denyWrites = false
        e.loadPersistedBaseline()
        e.attemptPendingRestore()
        assertEquals(2, fake.store[FORCE]); assertEquals(3, fake.store[HIDE])
        assertFalse(bs.current.captured) // cleared after successful restore
    }

    @Test fun attemptPendingRestore_noBaseline_isNoOp() = runBlocking {
        val fake = FakeGateway()
        val e = enforcer(fake)
        e.attemptPendingRestore() // no baseline loaded -> no-op
        assertTrue(fake.writes.isEmpty())
        assertTrue(fake.deletes.isEmpty())
    }

    @Test fun attemptPendingRestore_noPermission_staysPending() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        fake.denyWrites = true
        e.stopEnforcing() // restore fails, baseline pending
        // attemptPendingRestore with permission still missing -> stays pending.
        e.attemptPendingRestore()
        assertTrue(bs.current.captured) // still pending
        assertEquals(1, fake.store[FORCE]) // unchanged
    }

    @Test fun pendingRestoreAfterRestart_loadsAndRestoresOriginalBaseline() = runBlocking {
        // Simulate: previous process captured baseline 0/0, enforced 1/1, died.
        // New process: preference is OFF, baseline persisted, service reconnects.
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 } // what the new process sees
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0) // original 0/0
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        // Preference is OFF -> attemptPendingRestore should restore 0/0 and clear baseline.
        e.attemptPendingRestore()
        assertEquals(0, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
        assertFalse(bs.current.captured)
    }

    @Test fun restartWithEnforcementOn_doesNotRecaptureEnforcedValues() = runBlocking {
        // Process died mid-enforcement; system shows 1/1. New process has preference ON.
        // The enforcer should load the persisted baseline (original 0/0) and NOT treat 1/1 as original.
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0) // original 0/0
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        e.startEnforcing() // enforces 1/1 (already 1, no writes)
        e.stopEnforcing() // restores 0/0 from loaded baseline, NOT 1/1
        assertEquals(0, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
    }

    // --- Second follow-up: serialized state machine + pending restore ---

    @Test fun restartWithEnforcementOn_attemptPendingRestore_isNoOp() = runBlocking {
        // Process died mid-enforcement; new process has preference ON + persisted baseline 0/0.
        // attemptPendingRestore must NOT run (it would restore 0/0, causing a nav-bar flicker,
        // then the DataStore collector re-enforces 1/1). It should only run when preference is OFF.
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0) // original 0/0
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        // Simulate: preference is ON -> startEnforcing, NOT attemptPendingRestore.
        e.startEnforcing() // enforces 1/1 (already 1)
        // attemptPendingRestore should be a no-op because enforcing=true.
        e.attemptPendingRestore()
        assertEquals(1, fake.store[FORCE]) // NOT restored to 0
        assertEquals(1, fake.store[HIDE]) // NOT restored to 0
        assertTrue(e.isActive)
        assertTrue(bs.current.captured) // baseline NOT cleared
    }

    @Test fun stopEnforcing_failedRestore_setsPendingRestore() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 3 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        // Permission revoked -> restore fails.
        fake.denyWrites = true
        e.stopEnforcing()
        assertTrue(e.hasPendingRestore) // pending flag set
        // Baseline stays in RAM + persisted.
        assertTrue(bs.current.captured)
    }

    @Test fun retryPendingRestoreIfNeeded_restoresWhenPermissionReturns() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 3 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        fake.denyWrites = true
        e.stopEnforcing() // restore fails, pendingRestore=true
        assertTrue(e.hasPendingRestore)
        // Permission returns.
        fake.denyWrites = false
        // Watchdog calls retryPendingRestoreIfNeeded.
        e.retryPendingRestoreIfNeeded()
        assertEquals(2, fake.store[FORCE]); assertEquals(3, fake.store[HIDE])
        assertFalse(e.hasPendingRestore) // cleared
        assertFalse(bs.current.captured) // baseline cleared
    }

    @Test fun retryPendingRestoreIfNeeded_noPending_isNoOp() = runBlocking {
        val fake = FakeGateway()
        val e = enforcer(fake)
        e.retryPendingRestoreIfNeeded() // no pending -> no-op
        assertTrue(fake.writes.isEmpty())
        assertTrue(fake.deletes.isEmpty())
        assertFalse(e.hasPendingRestore)
    }

    @Test fun retryPendingRestoreIfNeeded_stillNoPermission_staysPending() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 2 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        fake.denyWrites = true
        e.stopEnforcing() // pending
        // Watchdog retries but permission still missing.
        e.retryPendingRestoreIfNeeded()
        assertTrue(e.hasPendingRestore) // still pending
        assertEquals(1, fake.store[FORCE]) // unchanged
        assertTrue(bs.current.captured) // baseline still persisted
    }

    @Test fun startEnforcing_afterPendingRestore_capturesFreshBaseline() = runBlocking {
        // After a pending restore succeeded, re-enabling should capture a fresh baseline.
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 3 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        fake.denyWrites = true
        e.stopEnforcing() // pending
        fake.denyWrites = false
        e.retryPendingRestoreIfNeeded() // restores 2/3, clears baseline
        // Now re-enable: should capture the CURRENT values (2/3) as a fresh baseline.
        e.startEnforcing() // enforces 1/1
        assertEquals(1, fake.store[FORCE]); assertEquals(1, fake.store[HIDE])
        e.stopEnforcing() // restores 2/3
        assertEquals(2, fake.store[FORCE]); assertEquals(3, fake.store[HIDE])
    }

    // --- Third follow-up: fail-safe restore + service-lifetime controller ---

    @Test fun deactivateAndRestoreIfNeeded_restoresWhenEnforcing() = runBlocking {
        // Normal disable: enforcing=true, baseline 2/3, shouldEnforce becomes false.
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 3 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        assertEquals(1, fake.store[FORCE]); assertEquals(1, fake.store[HIDE])
        e.deactivateAndRestoreIfNeeded()
        assertEquals(2, fake.store[FORCE]); assertEquals(3, fake.store[HIDE])
        assertFalse(e.isActive)
        assertFalse(bs.current.captured)
    }

    @Test fun deactivateAndRestoreIfNeeded_restoresWhenNotEnforcingButBaselineExists() = runBlocking {
        // Process restart: preference ON, baseline 0/0 persisted, system 1/1, but shouldEnforce=false
        // (master off). enforcing=false but capturedBaseline=true -> must restore 0/0 so the system
        // nav buttons come back (Ogesture can't navigate without master).
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0) // original 0/0
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline() // loads baseline, enforcing stays false
        e.deactivateAndRestoreIfNeeded()
        assertEquals(0, fake.store[FORCE]) // restored!
        assertEquals(0, fake.store[HIDE]) // restored!
        assertFalse(e.isActive)
        assertFalse(bs.current.captured)
    }

    @Test fun deactivateAndRestoreIfNeeded_noBaseline_isNoOp() = runBlocking {
        val fake = FakeGateway()
        val e = enforcer(fake)
        e.deactivateAndRestoreIfNeeded() // no baseline -> no-op
        assertTrue(fake.writes.isEmpty())
        assertTrue(fake.deletes.isEmpty())
        assertFalse(e.isActive)
    }

    @Test fun deactivateAndRestoreIfNeeded_noPermission_setsPending() = runBlocking {
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0)
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        // No permission -> can't restore, sets pending.
        fake.denyWrites = true
        e.deactivateAndRestoreIfNeeded()
        assertTrue(e.hasPendingRestore)
        assertEquals(1, fake.store[FORCE]) // unchanged
        assertTrue(bs.current.captured) // baseline stays
    }

    @Test fun restartWithEnforcementOn_masterOff_restoresBaseline() = runBlocking {
        // The critical P0: process died mid-enforcement, restart, preference ON but master OFF.
        // shouldEnforce=false -> recomputeEnforcement else branch -> deactivateAndRestoreIfNeeded.
        // System nav must come back.
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0)
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        // recomputeEnforcement else branch:
        e.deactivateAndRestoreIfNeeded(tag = "disable:settings")
        assertEquals(0, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
        assertFalse(e.isActive)
    }

    @Test fun attemptPendingRestore_noPermission_setsPendingRestore() = runBlocking {
        // Restart with preference OFF, baseline persisted, but permission missing.
        // attemptPendingRestore must set pendingRestore=true (not just return) so the watchdog
        // retries later when permission returns.
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0)
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        fake.denyWrites = true
        e.attemptPendingRestore()
        assertTrue(e.hasPendingRestore) // MUST be set, not just return
        // Later, watchdog retries when permission returns.
        fake.denyWrites = false
        e.retryPendingRestoreIfNeeded()
        assertEquals(0, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
        assertFalse(e.hasPendingRestore)
    }

    @Test fun loadPersistedBaseline_thenRestartWithEnforcementOn_enforcesAndDoesNotRestore() = runBlocking {
        // Restart with preference ON + master ON + bound + permission: shouldEnforce=true.
        // Enforces 1/1, does NOT restore 0/0.
        val fake = FakeGateway().apply { store[FORCE] = 0; store[HIDE] = 0 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0)
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        e.startEnforcing() // shouldEnforce=true path
        assertEquals(1, fake.store[FORCE]); assertEquals(1, fake.store[HIDE])
        assertTrue(e.isActive)
        assertTrue(bs.current.captured) // baseline preserved (still enforcing)
    }

    // --- Follow-up 4: shutdown fail-safe, gesture readiness, init barrier ---

    @Test fun deactivateAndRestoreIfNeeded_onShutdown_restoresEvenIfNotEnforcing() = runBlocking {
        // shutdown() calls deactivateAndRestoreIfNeeded. Even if the enforcer was never active
        // in this instance (fresh process), if a baseline exists it must restore.
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0)
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        // shutdown path: not enforcing, but baseline exists -> restore
        e.deactivateAndRestoreIfNeeded(tag = "shutdown")
        assertEquals(0, fake.store[FORCE])
        assertEquals(0, fake.store[HIDE])
        assertFalse(e.isActive)
        assertFalse(bs.current.captured)
    }

    @Test fun deactivateAndRestoreIfNeeded_repeatedCallsAreSafe() = runBlocking {
        // onDestroy might call shutdown() which calls deactivateAndRestoreIfNeeded.
        // If called twice (e.g. onServiceUnbound + shutdown), the second must be a no-op
        // (baseline already cleared).
        val fake = FakeGateway().apply { store[FORCE] = 2; store[HIDE] = 3 }
        val bs = FakeBaselineStore()
        val e = enforcer(fake, bs); e.startEnforcing()
        e.deactivateAndRestoreIfNeeded("first")
        assertEquals(2, fake.store[FORCE]); assertEquals(3, fake.store[HIDE])
        // Second call — baseline cleared, no-op
        e.deactivateAndRestoreIfNeeded("second")
        assertEquals(2, fake.store[FORCE]); assertEquals(3, fake.store[HIDE])
        assertFalse(e.isActive)
    }

    @Test fun startEnforcing_failsIfNoGestureRuntimeReady_impliedByShouldEnforceFalse() = runBlocking {
        // The controller gates shouldEnforce on gestureRuntimeReady. The enforcer itself
        // doesn't know about it, but the controller's recomputeEnforcement would call
        // deactivateAndRestoreIfNeeded if shouldEnforce is false. This test verifies the
        // enforcer's deactivateAndRestoreIfNeeded works correctly when called from that path.
        val fake = FakeGateway().apply { store[FORCE] = 1; store[HIDE] = 1 }
        val bs = FakeBaselineStore()
        bs.current = SettingsRepository.NavBaseline(true, true, 0, true, 0)
        val e = enforcer(fake, bs)
        e.loadPersistedBaseline()
        // Simulate: shouldEnforce=false (gestureRuntimeReady=false) -> deactivate
        e.deactivateAndRestoreIfNeeded(tag = "disable:overlay-down")
        assertEquals(0, fake.store[FORCE]); assertEquals(0, fake.store[HIDE])
    }
}
