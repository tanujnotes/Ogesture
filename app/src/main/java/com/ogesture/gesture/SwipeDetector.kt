package com.ogesture.gesture

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.ogesture.data.SwipeDirection
import kotlin.math.abs

/**
 * Returns true after an already-armed gesture is pulled back to less than half of its activation
 * distance. The half-distance hysteresis prevents a tiny reverse jitter around the activation
 * threshold from cancelling a deliberate gesture. This is pure so its contract is unit-tested.
 */
fun shouldCancelActivatedGesture(enabled: Boolean, distancePx: Float, activationDistancePx: Float): Boolean =
    enabled && distancePx < activationDistancePx * CANCELLATION_HYSTERESIS

private const val CANCELLATION_HYSTERESIS = 0.5f

/**
 * One recorded point of a touch, in display coordinates. Re-used as a lightweight read-only view
 * over the primitive sample buffer when replay is actually needed — allocated once per replay,
 * not once per ACTION_MOVE.
 */
data class TouchSample(val x: Float, val y: Float, val timeMs: Long)

/**
 * A read-only view over the recorded samples, built only when a touch turns out to be unused and
 * needs replaying. Backed by a snapshot taken at that moment so the buffer can be reused
 * immediately.
 */
class SampleView(private val xs: FloatArray, private val ys: FloatArray, private val times: LongArray, private val count: Int) {
    val size: Int get() = count
    fun x(i: Int) = xs[i]
    fun y(i: Int) = ys[i]
    fun timeMs(i: Int) = times[i]
    fun first() = if (count > 0) TouchSample(xs[0], ys[0], times[0]) else TouchSample(0f, 0f, 0L)
    fun last() = if (count > 0) TouchSample(xs[count - 1], ys[count - 1], times[count - 1]) else TouchSample(0f, 0f, 0L)
    /** Materialize a list only when a caller genuinely needs List<TouchSample>. */
    fun toList(): List<TouchSample> = ArrayList<TouchSample>(count).apply {
        for (i in 0 until count) add(TouchSample(xs[i], ys[i], times[i]))
    }
}

class SwipeDetector(
    context: Context,
    private val direction: SwipeDirection,
    private val onShortSwipe: () -> Unit,
    private val onLongSwipe: (() -> Unit)? = null,
    minDistanceDp: Float = 24f,
    /** Allows an armed Back/Home gesture to be cancelled by reversing toward its origin. */
    private val cancellationEnabled: Boolean = false,
    private val holdMs: Long = 100L,
    private val maxDurationMs: Long = 1000L,
    holdStillnessDp: Float = 12f,
    private val feedback: Feedback? = null,
    /**
     * Called when a touch the zone consumed ends without firing any action (a tap, a
     * long-press, a drag in the wrong direction...), so the caller can replay it to the
     * UI underneath. Not called for cancelled or multi-finger touches. Receives a
     * preallocated [SampleView] backed by the detector's primitive buffers — no object is
     * allocated per ACTION_MOVE.
     */
    private val onUnusedTouch: ((SampleView) -> Unit)? = null,
    /** Called at ACTION_DOWN, before anything else: this zone now owns the touch stream. */
    private val onStreamStart: (() -> Unit)? = null,
    /**
     * Called when the stream ends (ACTION_UP or ACTION_CANCEL), after any onShortSwipe /
     * onUnusedTouch callback for it has been dispatched.
     */
    private val onStreamEnd: (() -> Unit)? = null,
) : View.OnTouchListener {

    /** Progress hooks for drawing gesture indicators. All calls happen on the UI thread. */
    interface Feedback {
        fun onStart(rawX: Float, rawY: Float)
        fun onProgress(distancePx: Float, rawX: Float, rawY: Float)
        fun onArmed()
        fun onEnd(fired: Boolean)
    }

    private val density = context.resources.displayMetrics.density
    val minDistancePx = minDistanceDp * density
    private val holdStillnessPx = holdStillnessDp * density

    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var anchorX = 0f
    private var anchorY = 0f
    private var tracking = false
    private var thresholdCrossed = false
    // Cancellation is terminal for this touch stream: once the user pulls an armed gesture back,
    // lifting the finger must not re-arm and accidentally navigate.
    private var gestureCancelled = false
    private var longFired = false
    private var anchorView: View? = null

    // Preallocated primitive buffers — zero per-MOVE allocation. Recording stops the moment a
    // swipe crosses the activation threshold (it is definitely becoming a navigation gesture, so
    // replay history is no longer needed); normal successful gestures record only the handful of
    // samples before the threshold.
    private val sampleXs = FloatArray(MAX_SAMPLES)
    private val sampleYs = FloatArray(MAX_SAMPLES)
    private val sampleTimes = LongArray(MAX_SAMPLES)
    private var sampleCount = 0
    private var replayable = false

    private val longRunnable = Runnable {
        if (!tracking || !thresholdCrossed || longFired) return@Runnable
        longFired = true
        onLongSwipe?.invoke()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        anchorView = v
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onStreamStart?.invoke()
                reset()
                startX = event.rawX
                startY = event.rawY
                startTime = event.eventTime
                tracking = true
                replayable = onUnusedTouch != null
                addSample(event)
                feedback?.onStart(event.rawX, event.rawY)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                addSample(event)
                if (!tracking) return true
                val distance = when (direction) {
                    SwipeDirection.UP -> startY - event.rawY
                    SwipeDirection.RIGHT -> event.rawX - startX
                    SwipeDirection.LEFT -> startX - event.rawX
                }
                feedback?.onProgress(distance, event.rawX, event.rawY)
                if (!thresholdCrossed) {
                    if ((event.eventTime - startTime) > maxDurationMs) {
                        tracking = false
                        feedback?.onEnd(false)
                        return true
                    }
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val triggered = when (direction) {
                        SwipeDirection.UP -> -dy >= minDistancePx && abs(dx) <= -dy
                        SwipeDirection.RIGHT -> dx >= minDistancePx && abs(dy) <= dx
                        SwipeDirection.LEFT -> -dx >= minDistancePx && abs(dy) <= -dx
                    }
                    if (triggered) {
                        thresholdCrossed = true
                        // The swipe is now definitely a navigation gesture — drop the replay
                        // history immediately and stop recording. No further MOVE allocates or
                        // records anything; the gesture finishes with zero retained samples.
                        dropSamples()
                        feedback?.onArmed()
                        if (onLongSwipe != null) {
                            anchorX = event.rawX
                            anchorY = event.rawY
                            v.postDelayed(longRunnable, holdMs)
                        }
                    }
                } else if (!longFired && !gestureCancelled) {
                    if (shouldCancelActivatedGesture(cancellationEnabled, distance, minDistancePx)) {
                        gestureCancelled = true
                        cancelPending()
                        feedback?.onEnd(false)
                    } else {
                        val moved = abs(event.rawX - anchorX) > holdStillnessPx || abs(event.rawY - anchorY) > holdStillnessPx
                        if (moved && onLongSwipe != null) {
                            anchorX = event.rawX; anchorY = event.rawY
                            v.removeCallbacks(longRunnable); v.postDelayed(longRunnable, holdMs)
                        }
                    }
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                cancelPending()
                if (tracking) feedback?.onEnd(false)
                tracking = false
                dropSamples()
                return true
            }
            MotionEvent.ACTION_UP -> {
                // Record the UP point too so the replay captures the full path + real duration.
                // Without it, a long-press (DOWN→UP, no MOVE) would replay as a 0-duration tap
                // instead of mirroring the hold, and a wrong-direction drag would lose its endpoint.
                addSample(event)
                val crossed = thresholdCrossed
                val wasTracking = tracking
                val didLong = longFired
                val didCancel = gestureCancelled
                cancelPending()
                tracking = false
                val fires = wasTracking && crossed && !didLong && !didCancel
                if (!didCancel) feedback?.onEnd(fires || didLong)
                if (fires) onShortSwipe()
                if (!fires && !didLong && !didCancel && replayable && sampleCount > 0) {
                    onUnusedTouch?.invoke(SampleView(sampleXs, sampleYs, sampleTimes, sampleCount))
                }
                dropSamples()
                onStreamEnd?.invoke()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                if (tracking) feedback?.onEnd(false)
                tracking = false
                dropSamples()
                onStreamEnd?.invoke()
                return true
            }
        }
        return false
    }

    private fun cancelPending() {
        anchorView?.removeCallbacks(longRunnable)
    }

    private fun addSample(event: MotionEvent) {
        if (replayable && sampleCount < MAX_SAMPLES) {
            sampleXs[sampleCount] = event.rawX
            sampleYs[sampleCount] = event.rawY
            sampleTimes[sampleCount] = event.eventTime
            sampleCount++
        }
    }

    private fun dropSamples() {
        sampleCount = 0
        replayable = false
    }

    private fun reset() {
        cancelPending()
        thresholdCrossed = false
        gestureCancelled = false
        longFired = false
        dropSamples()
    }

    /**
     * Lifecycle cleanup: remove any pending hold callback and drop the retained [View] reference
     * so a detached detector cannot fire navigation later and cannot leak its host view. After
     * [dispose] the detector will not invoke any callback.
     */
    fun dispose() {
        cancelPending()
        tracking = false
        thresholdCrossed = false
        longFired = false
        dropSamples()
        anchorView = null
    }

    private companion object {
        const val MAX_SAMPLES = 400
    }
}
