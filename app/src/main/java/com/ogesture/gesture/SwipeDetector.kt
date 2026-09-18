package com.ogesture.gesture

import android.content.Context
import android.view.MotionEvent
import android.view.View
import com.ogesture.data.SwipeDirection
import kotlin.math.abs

/** One recorded point of a touch, in display coordinates. */
data class TouchSample(val x: Float, val y: Float, val timeMs: Long)

class SwipeDetector(
    context: Context,
    private val direction: SwipeDirection,
    private val onShortSwipe: () -> Unit,
    private val onLongSwipe: (() -> Unit)? = null,
    minDistanceDp: Float = 24f,
    /**
     * When set, an armed swipe disarms once the finger is dragged back to within this
     * distance of where the touch began — the user changed their mind — and lifting there
     * fires nothing. Swiping out past [minDistanceDp] again re-arms it. Must be smaller than
     * [minDistanceDp]; the gap keeps finger jitter from flickering between the two states.
     * Null (the default): once armed, the swipe always fires.
     */
    cancelDistanceDp: Float? = null,
    private val holdMs: Long = 100L,
    private val maxDurationMs: Long = 1000L,
    holdStillnessDp: Float = 12f,
    private val feedback: Feedback? = null,
    /**
     * Called when a touch the zone consumed ends without firing any action (a tap, a
     * long-press, a drag in the wrong direction...), so the caller can replay it to the
     * UI underneath. Not called for cancelled or multi-finger touches, nor for a swipe that
     * armed and was then dragged back: that was a gesture the user abandoned, not a touch
     * meant for the app.
     */
    private val onUnusedTouch: ((List<TouchSample>) -> Unit)? = null,
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
        /** Lifting now fires the action. Called again each time a disarmed swipe re-arms. */
        fun onArmed()
        /** An armed swipe was dragged back to where it began: lifting now fires nothing. */
        fun onDisarmed()
        fun onEnd(fired: Boolean)
    }

    private val density = context.resources.displayMetrics.density
    val minDistancePx = minDistanceDp * density
    private val cancelDistancePx = cancelDistanceDp?.let { it * density }
    private val holdStillnessPx = holdStillnessDp * density

    init {
        require(cancelDistancePx == null || cancelDistancePx < minDistancePx) {
            "cancelDistanceDp must be smaller than minDistanceDp"
        }
    }

    private var startX = 0f
    private var startY = 0f
    private var startTime = 0L
    private var anchorX = 0f
    private var anchorY = 0f
    private var tracking = false
    private var thresholdCrossed = false

    // Stays set once the touch has armed, even if it is disarmed again afterwards.
    private var everArmed = false
    private var longFired = false
    private var anchorView: View? = null
    private val samples = ArrayList<TouchSample>(64)
    private var replayable = false

    // Armed while the finger is stationary after the threshold; any movement beyond
    // holdStillnessPx re-anchors and restarts it, so the hold can happen anywhere
    // along the swipe, not just at the threshold point.
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
                val distance = swipeDistance(event)
                feedback?.onProgress(distance, event.rawX, event.rawY)
                if (!thresholdCrossed) {
                    // The time limit and the angle check tell a swipe from a slow drag or a
                    // scroll along the edge. A touch that already armed once is settled as a
                    // swipe, so re-arming after a cancel goes by distance alone.
                    if (!everArmed && (event.eventTime - startTime) > maxDurationMs) {
                        tracking = false
                        feedback?.onEnd(false)
                        return true
                    }
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    val triggered = if (everArmed) {
                        distance >= minDistancePx
                    } else when (direction) {
                        SwipeDirection.UP -> -dy >= minDistancePx && abs(dx) <= -dy
                        SwipeDirection.RIGHT -> dx >= minDistancePx && abs(dy) <= dx
                        SwipeDirection.LEFT -> -dx >= minDistancePx && abs(dy) <= -dx
                    }
                    if (triggered) {
                        thresholdCrossed = true
                        if (!everArmed) {
                            everArmed = true
                            // This touch is a gesture now, even if it ends up cancelled. It
                            // must never be replayed: a swipe out and back ends where it
                            // began, and would reach the app underneath as a tap.
                            dropSamples()
                        }
                        feedback?.onArmed()
                        if (onLongSwipe != null) {
                            anchorX = event.rawX
                            anchorY = event.rawY
                            v.postDelayed(longRunnable, holdMs)
                        }
                        // The short action fires on ACTION_UP so indicators can show the
                        // armed state (and a long action can still take over, or the user
                        // can drag back to cancel).
                    }
                } else if (!longFired) {
                    if (cancelDistancePx != null && distance < cancelDistancePx) {
                        // Dragged back to where it began: the user changed their mind.
                        thresholdCrossed = false
                        v.removeCallbacks(longRunnable)
                        feedback?.onDisarmed()
                        return true
                    }
                    val moved = abs(event.rawX - anchorX) > holdStillnessPx ||
                        abs(event.rawY - anchorY) > holdStillnessPx
                    if (moved && onLongSwipe != null) {
                        anchorX = event.rawX
                        anchorY = event.rawY
                        v.removeCallbacks(longRunnable)
                        v.postDelayed(longRunnable, holdMs)
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
                addSample(event)
                // A quick drag back can lift inside the cancel range before any MOVE was
                // sampled there, so the lift position gets the same check.
                val liftedInCancelRange =
                    cancelDistancePx != null && swipeDistance(event) < cancelDistancePx
                val crossed = thresholdCrossed && !liftedInCancelRange
                val wasTracking = tracking
                val didLong = longFired
                cancelPending()
                tracking = false
                val fires = wasTracking && crossed && !didLong
                feedback?.onEnd(fires || didLong)
                if (fires) onShortSwipe()
                if (!fires && !didLong && replayable && samples.isNotEmpty()) {
                    onUnusedTouch?.invoke(samples.toList())
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

    /** How far the touch has travelled from its start along the swipe direction. */
    private fun swipeDistance(event: MotionEvent): Float = when (direction) {
        SwipeDirection.UP -> startY - event.rawY
        SwipeDirection.RIGHT -> event.rawX - startX
        SwipeDirection.LEFT -> startX - event.rawX
    }

    private fun cancelPending() {
        anchorView?.removeCallbacks(longRunnable)
    }

    private fun addSample(event: MotionEvent) {
        if (replayable && samples.size < MAX_SAMPLES) {
            samples.add(TouchSample(event.rawX, event.rawY, event.eventTime))
        }
    }

    private fun dropSamples() {
        samples.clear()
        replayable = false
    }

    private fun reset() {
        cancelPending()
        thresholdCrossed = false
        everArmed = false
        longFired = false
        dropSamples()
    }

    private companion object {
        const val MAX_SAMPLES = 400
    }
}
