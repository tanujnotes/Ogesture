package com.ogesture

import com.ogesture.gesture.shouldCancelActivatedGesture
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureCancellationTest {
    @Test
    fun enabledCancellation_cancelsWhenReversedBelowHalfTheActivationDistance() {
        assertTrue(shouldCancelActivatedGesture(enabled = true, distancePx = 11f, activationDistancePx = 24f))
    }

    @Test
    fun enabledCancellation_keepsGestureWhenItRemainsAtOrBeyondHalfTheActivationDistance() {
        assertFalse(shouldCancelActivatedGesture(enabled = true, distancePx = 12f, activationDistancePx = 24f))
    }

    @Test
    fun disabledCancellation_neverCancelsAnActivatedGesture() {
        assertFalse(shouldCancelActivatedGesture(enabled = false, distancePx = 0f, activationDistancePx = 24f))
    }
}
