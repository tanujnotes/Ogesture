package com.ogesture.service

import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.res.Configuration
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import com.ogesture.data.GESTURE_ZONES
import com.ogesture.data.SettingsRepository
import com.ogesture.data.ZoneConfig
import com.ogesture.data.ZoneId
import com.ogesture.gesture.SwipeDetector
import com.ogesture.gesture.TouchSample
import com.ogesture.ui.overlay.BackIndicator
import com.ogesture.ui.overlay.OverlayIndicator
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope

/**
 * Owns the gesture-zone and indicator windows. Extracted from the former `EdgeOverlayService`
 * so the same logic can run under the active [EdgeGestureAccessibilityService] using trusted
 * `TYPE_ACCESSIBILITY_OVERLAY` windows instead of `TYPE_APPLICATION_OVERLAY` + a foreground
 * service. Accessibility overlays are not hidden by `HIDE_NON_SYSTEM_OVERLAY_WINDOWS` on secure
 * screens (Settings, SubSettings), so gestures keep working there, and the system no longer
 * shows the persistent "displaying over other apps" notification for the app.
 *
 * The controller is created and destroyed by the accessibility service and must only be
 * touched from the main thread (window operations are main-thread only). [scope] is supplied
 * by the owner so its cancellation is tied to the service lifecycle.
 */
class EdgeOverlayController(
    private val context: Context,
    private val windowManager: WindowManager,
    private val repo: SettingsRepository,
    private val dispatcher: GestureDispatcher,
    private val scope: CoroutineScope,
) {
    private val activeViews = mutableMapOf<ZoneId, View>()
    private val indicators = mutableMapOf<ZoneId, OverlayIndicator>()
    private var attached = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var replaying = false

    // True while the foreground app is on the user's excluded list: zones stay untouchable
    // so every touch reaches the app natively, at the cost of gestures in that app.
    @Volatile
    private var passThrough = false

    // True while a finger holds a zone. The zone windows go untouchable + alpha 0 the
    // moment a stream starts: window-state changes only affect the targeting of NEW
    // touches (the held stream stays with the window that received ACTION_DOWN), and
    // applying them at ACTION_DOWN gives the asynchronous update the whole touch duration
    // to reach the input pipeline — so an unused touch can be re-injected the instant it
    // ends instead of stalling behind a fixed grace delay. A second finger landing
    // mid-hold passes to the app natively, which beats today's abort-and-drop.
    private var zonesHeld = false

    // The zone whose indicator covers the tap point of a replay in flight, or null. That
    // one indicator (the back arrow's edge strip) has to be hidden first — two stacked
    // overlay windows exceed Android's 0.8 obscuring-opacity cap for injected touches — and
    // the hide is issued only at replay time, so those replays keep the INJECT_DELAY_MS
    // grace for it to apply. Only the covering indicator is hidden: the rule is about what
    // sits over the tap point, so blanking the opposite edge cost a window update per
    // replay and bought nothing.
    private var indicatorHiddenForReplay: ZoneId? = null

    /** Display geometry the attached zones were laid out for. Null while nothing is attached. */
    private var lastGeometry: ScreenGeometry? = null

    // Rotation is applied to the display after the configuration change lands, so listen for
    // the display change itself rather than racing it: onDisplayChanged fires once the new
    // size is in effect. Configuration changes cover the rest (display-size/density settings,
    // multi-window, folding); both funnel into the same idempotent check.
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) rebuildIfGeometryChanged()
        }
    }

    /**
     * Starts the controller: observes the master gesture switch and the per-app pass-through
     * flow, and registers for display-geometry changes. Idempotent. Must be called once the
     * owning accessibility service is connected.
     */
    fun start() {
        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, mainHandler)

        scope.launch {
            repo.masterEnabled.distinctUntilChanged().collect { enabled ->
                if (!enabled) {
                    detachAll()
                    return@collect
                }
                rebuild(GESTURE_ZONES)
            }
        }

        scope.launch {
            combine(
                EdgeGestureAccessibilityService.foregroundPackage,
                repo.excludedApps,
            ) { pkg, excluded -> pkg != null && pkg in excluded }
                .distinctUntilChanged()
                .collect { excluded ->
                    passThrough = excluded
                    applyZoneInteractivity()
                }
        }
    }

    /** Tears down every window and unregisters listeners. Safe to call more than once. */
    fun stop() {
        try {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (_: Throwable) { /* never registered */ }
        detachAll()
    }

    /**
     * Called by the owner on configuration changes so the zones re-lay out for the new
     * display geometry (rotation, density, multi-window, folding).
     */
    fun onConfigurationChanged(newConfig: Configuration) {
        rebuildIfGeometryChanged()
    }

    /**
     * Zone windows are sized in pixels from the display's dimensions, and the system keeps
     * those pixel sizes across a rotation: a side zone measured as 90% of portrait height
     * would run off the bottom of a landscape screen, and the bottom zone would span less
     * than half its width. Re-lay them out whenever the geometry they were computed from
     * changes. Configuration changes that don't affect it (locale, theme, font scale) fall
     * out here, so this stays cheap to call from every signal.
     */
    private fun rebuildIfGeometryChanged() {
        if (activeViews.isEmpty()) return
        if (currentGeometry() == lastGeometry) return
        rebuild(GESTURE_ZONES)
    }

    /**
     * Full display size, density, and nav-bar insets the zone layout depends on. The insets
     * are part of the comparison because a 90°→270° flip keeps the same width and height
     * while the 3-button bar jumps to the opposite side — the zones must re-lay out for
     * that even though the display size alone looks unchanged.
     */
    private data class ScreenGeometry(
        val width: Int,
        val height: Int,
        val density: Float,
        val navLeft: Int,
        val navRight: Int,
        val navBottom: Int,
    )

    private fun currentGeometry(): ScreenGeometry {
        val density = context.resources.displayMetrics.density
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val nav = metrics.windowInsets.getInsets(WindowInsets.Type.navigationBars())
            ScreenGeometry(
                metrics.bounds.width(), metrics.bounds.height(), density,
                nav.left, nav.right, nav.bottom,
            )
        } else {
            @Suppress("DEPRECATION")
            val metrics = context.resources.displayMetrics
            ScreenGeometry(metrics.widthPixels, metrics.heightPixels, density, 0, 0, 0)
        }
    }

    private fun rebuild(zones: List<ZoneConfig>) {
        detachAll()
        // Detaching kills any in-flight stream (e.g. a rotation mid-touch), so its UP will
        // never arrive to release the hold — clear it or the fresh windows start dead.
        zonesHeld = false
        // One snapshot for the whole pass, so every zone agrees on the screen it is sizing to
        // even if a rotation lands mid-rebuild; the listener re-runs us if it changed again.
        val geometry = currentGeometry()
        lastGeometry = geometry
        for (zone in zones) {
            val view = View(context).apply {
                // DEBUG_SHOW_ZONES tints the touch areas so they can be seen while testing.
                setBackgroundColor(if (DEBUG_SHOW_ZONES) ZONE_DEBUG_COLOR else android.graphics.Color.TRANSPARENT)
            }
            val minDistanceDp = if (zone.id == ZoneId.BOTTOM) BOTTOM_MIN_DISTANCE_DP else SIDE_MIN_DISTANCE_DP
            val armDistancePx = minDistanceDp * context.resources.displayMetrics.density
            val indicator: OverlayIndicator?
            val feedback: SwipeDetector.Feedback?
            when (zone.id) {
                ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE -> {
                    val ind = BackIndicator(
                        context = context,
                        windowManager = windowManager,
                        fromLeftEdge = zone.id == ZoneId.LEFT_EDGE,
                        armDistancePx = armDistancePx,
                        edgeOffsetPx = if (zone.id == ZoneId.LEFT_EDGE) geometry.navLeft else geometry.navRight,
                        windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    )
                    indicator = ind
                    feedback = object : SwipeDetector.Feedback {
                        override fun onStart(rawX: Float, rawY: Float) = ind.onGestureStart(rawY)
                        override fun onProgress(distancePx: Float, rawX: Float, rawY: Float) =
                            ind.onGestureProgress(distancePx, rawY)
                        override fun onArmed() {
                            ind.onArmed()
                            hapticTick()
                        }
                        override fun onEnd(fired: Boolean) = ind.onGestureEnd(fired)
                    }
                }
                ZoneId.BOTTOM -> {
                    // No indicator for the bottom zone. As an accessibility overlay it would
                    // stack above the 3-button bar — either drawn across the buttons or as a
                    // second handle hovering just above them — and gesture-nav devices
                    // already draw their own pill. The haptic on trigger is the feedback.
                    indicator = null
                    feedback = null
                }
            }
            view.setOnTouchListener(
                SwipeDetector(
                    context = context,
                    direction = zone.swipeDirection,
                    onShortSwipe = { onZoneTriggered(zone, long = false) },
                    onLongSwipe = if (zone.longAction != null) {
                        { onZoneTriggered(zone, long = true) }
                    } else null,
                    // Swipes over the nav bar reach the bottom zone via the bar's slippery
                    // handoff, so the finger has already travelled the bar's height before
                    // we get ACTION_DOWN — require only a short confirmation, not a full swipe.
                    minDistanceDp = minDistanceDp,
                    feedback = feedback,
                    onUnusedTouch = { samples -> replayUnusedTouch(samples) },
                    onStreamStart = {
                        zonesHeld = true
                        applyZoneInteractivity()
                    },
                    // Runs after onUnusedTouch, so a started replay has already set
                    // `replaying` and the zones stay untouchable through it.
                    onStreamEnd = {
                        zonesHeld = false
                        applyZoneInteractivity()
                    },
                )
            )
            val params = layoutParamsFor(zone, geometry)
            try {
                windowManager.addView(view, params)
                view.post {
                    val w = view.width
                    val h = view.height
                    if (w > 0 && h > 0) {
                        view.systemGestureExclusionRects = listOf(Rect(0, 0, w, h))
                    }
                }
                activeViews[zone.id] = view
                if (indicator != null) {
                    indicator.attach()
                    indicators[zone.id] = indicator
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add overlay for ${zone.id}", t)
            }
        }
        attached = activeViews.isNotEmpty()
        // Fresh windows come up touchable; re-apply in case an excluded app is in front.
        applyZoneInteractivity()
    }

    private fun detachAll() {
        for ((_, ind) in indicators) {
            ind.detach()
        }
        indicators.clear()
        if (activeViews.isEmpty()) {
            attached = false
            return
        }
        for ((_, v) in activeViews) {
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) { /* ignore */ }
        }
        activeViews.clear()
        attached = false
    }

    /**
     * A consumed touch turned out not to be a gesture (tap, long-press, wrong-direction
     * drag...). Re-inject it through the accessibility service so it reaches the UI the
     * zone covered. The zone windows are already untouchable (held since the touch's
     * ACTION_DOWN), so the injected events cannot land back on us.
     */
    private fun replayUnusedTouch(samples: List<TouchSample>) {
        if (replaying || passThrough || samples.isEmpty()) return
        val first = samples.first()
        val last = samples.last()
        val movedPx = kotlin.math.hypot(last.x - first.x, last.y - first.y)
        val path = Path().apply {
            moveTo(first.x, first.y)
            if (movedPx < TAP_SLOP_PX) {
                // A tap: the recorded path is a single point. dispatchGesture needs a
                // non-zero path, and the target's click detector needs a realistic press
                // duration — a 1 ms same-point stroke registers as "completed" but never
                // clicks. Nudge the end by a sub-slop pixel so it's still a tap, not a drag.
                lineTo(first.x + 1f, first.y + 1f)
            } else {
                for (i in 1 until samples.size) lineTo(samples[i].x, samples[i].y)
            }
        }
        val rawDuration = last.timeMs - first.timeMs
        val longPressTimeout = android.view.ViewConfiguration.getLongPressTimeout().toLong()
        val duration = if (movedPx < TAP_SLOP_PX) {
            if (rawDuration >= longPressTimeout) {
                // A deliberate hold. The stroke has to outlast the long-press timeout so the
                // target's own long-press fires, but it does not have to re-enact the whole
                // hold — and the zones stay untouchable for the length of the stroke, so
                // mirroring a three-second press left the gesture edges dead for three
                // seconds afterwards. The floor keeps the cap clear of a raised "touch and
                // hold delay", where a flat 600ms would fall under the timeout and demote
                // the hold to a click.
                rawDuration.coerceAtMost(
                    maxOf(HOLD_STROKE_CAP_MS, longPressTimeout + HOLD_TIMEOUT_MARGIN_MS),
                )
            } else {
                // A click intent: the press already physically happened, so the replay
                // only needs a believable press, not a re-enactment — capping it is a
                // direct cut in perceived click latency (the click fires at stroke end).
                rawDuration.coerceIn(MIN_TAP_MS, TAP_STROKE_CAP_MS)
            }
        } else {
            rawDuration.coerceIn(1L, MAX_REPLAY_MS)
        }
        val gesture = try {
            GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, duration))
                .build()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not build replay gesture", t)
            return
        }
        // The zones have been going untouchable + alpha 0 since this touch's ACTION_DOWN,
        // so the touch's own duration already counts toward the (asynchronous) settle
        // grace — only the remainder still has to be waited out. Real taps run ~80ms+,
        // which usually leaves nothing to wait for; a faster tap waits just the few ms
        // difference. A tap point under a still-visible indicator window is the
        // exception: that window is only hidden right here, so those replays need the
        // full grace for the hide to apply.
        val coveringIndicator = indicators.entries.firstOrNull {
            it.value.windowBounds()?.contains(first.x.toInt(), first.y.toInt()) == true
        }?.key
        val underIndicator = coveringIndicator != null
        val injectDelay = if (underIndicator) {
            INJECT_DELAY_MS
        } else {
            (INJECT_DELAY_MS - rawDuration).coerceAtLeast(0L)
        }
        Log.d(
            TAG,
            "Replaying unused touch: ${samples.size} samples over ${duration}ms" +
                if (underIndicator) " (under indicator, +${injectDelay}ms)" else "",
        )
        replaying = true
        indicatorHiddenForReplay = coveringIndicator
        applyZoneInteractivity()
        val finish = Runnable {
            if (replaying) {
                replaying = false
                indicatorHiddenForReplay = null
                applyZoneInteractivity()
            }
        }
        // Safety net in case the result callback never arrives.
        mainHandler.postDelayed(finish, duration + injectDelay + 1_000L)
        mainHandler.postDelayed({
            val dispatched = dispatcher.replay(gesture) { completed ->
                Log.d(TAG, "Replay finished, completed=$completed")
                mainHandler.removeCallbacks(finish)
                finish.run()
            }
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture refused the replay")
                mainHandler.removeCallbacks(finish)
                finish.run()
            }
        }, injectDelay)
    }

    /**
     * Zone windows accept new touches only when no finger already holds one, no replay is
     * in flight, and the foreground app isn't excluded. In every other state they are
     * untouchable AND alpha 0: each non-touchable overlay window counts as 0.8 "obscuring
     * opacity", and where two of ours stack (side zone + back indicator) the combined
     * 0.96 exceeds Android's 0.8 cap, so the system would drop a replayed touch as
     * untrusted — and apps with their own tapjacking filters would drop real touches
     * flagged as obscured. Alpha-0 windows are exempt from both. Nothing is drawn in the
     * zones, so hiding them costs nothing visually (the debug tint blinks — debug only).
     *
     * Indicators stay visible through held touches and replays — they ARE the gesture
     * feedback — except the one indicator a replay is injecting underneath, and
     * pass-through, where hiding doubles as the "gestures off here" cue.
     */
    private fun applyZoneInteractivity() {
        val zonesInteractive = !zonesHeld && !replaying && !passThrough
        for ((_, view) in activeViews) {
            val lp = view.layoutParams as? WindowManager.LayoutParams ?: continue
            val newFlags = if (zonesInteractive) {
                lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            } else {
                lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            val newAlpha = if (zonesInteractive) 1f else 0f
            if (newFlags == lp.flags && lp.alpha == newAlpha) continue
            lp.flags = newFlags
            lp.alpha = newAlpha
            try {
                windowManager.updateViewLayout(view, lp)
            } catch (_: Throwable) { /* window may be mid-detach */ }
        }
        for ((id, indicator) in indicators) {
            indicator.setWindowHidden(passThrough || id == indicatorHiddenForReplay)
        }
    }

    private fun onZoneTriggered(zone: ZoneConfig, long: Boolean) {
        val action = (if (long) zone.longAction else zone.action) ?: return
        // Side zones already ticked when their indicator armed.
        if (zone.id == ZoneId.BOTTOM) hapticTick()
        dispatcher.trigger(action)
    }

    private fun hapticTick() {
        val effect = VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val mgr = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
            mgr?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val v = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            v?.vibrate(effect)
        }
    }

    private fun layoutParamsFor(
        zone: ZoneConfig,
        geometry: ScreenGeometry,
    ): WindowManager.LayoutParams {
        val thicknessPx = (zone.thicknessDp * geometry.density).toInt().coerceAtLeast(1)
        // Each zone is pinned to its physical edge (insets are not fitted) and then pushed
        // in by that edge's nav-bar inset (zero for bar-free edges), so it sits just past
        // the bar — the bar sits at the bottom in portrait and moves to a side in landscape
        // with 3-button nav. The zones are accessibility overlays, which the system stacks
        // ABOVE the navigation bar, so a zone that reached across the inset would swallow
        // every Back/Home/Recents button tap and hand it back only through the replay
        // path. Keeping the zone off the bar costs no gesture: the bar is a "slippery"
        // window, so a swipe that starts on it slips to whatever is under the finger the
        // moment it leaves the bar — and that is the zone.
        val (widthPx, heightPx, gravity) = when (zone.id) {
            ZoneId.BOTTOM -> Triple(
                (geometry.width * zone.lengthPercent / 100).coerceAtLeast(1),
                thicknessPx,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            )
            ZoneId.LEFT_EDGE -> Triple(
                thicknessPx,
                (geometry.height * zone.lengthPercent / 100).coerceAtLeast(1),
                Gravity.START or Gravity.CENTER_VERTICAL,
            )
            ZoneId.RIGHT_EDGE -> Triple(
                thicknessPx,
                (geometry.height * zone.lengthPercent / 100).coerceAtLeast(1),
                Gravity.END or Gravity.CENTER_VERTICAL,
            )
        }
        return WindowManager.LayoutParams(
            widthPx,
            heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            // x/y offset away from the gravity edge, i.e. clear of that edge's bar.
            when (zone.id) {
                ZoneId.BOTTOM -> y = geometry.navBottom
                ZoneId.LEFT_EDGE -> x = geometry.navLeft
                ZoneId.RIGHT_EDGE -> x = geometry.navRight
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
    }

    companion object {
        private const val TAG = "EdgeOverlayController"
        private const val SIDE_MIN_DISTANCE_DP = 24f
        private const val BOTTOM_MIN_DISTANCE_DP = 10f
        private const val MAX_REPLAY_MS = 3_000L

        // Grace for hiding an indicator window that covers the replay's tap point (side
        // edges: the back arrow's strip spans the whole edge) before injecting — window
        // state changes are asynchronous, and measured on device (Galaxy F16, Android 16)
        // a 50ms grace loses that race ~25% of the time, silently dropping the tap; 65ms
        // ran clean. Replays not under an indicator skip this entirely: the zones
        // themselves have been untouchable + alpha 0 since the touch's ACTION_DOWN.
        private const val INJECT_DELAY_MS = 65L

        // Floor for the synthetic press: a 1ms same-point stroke "completes" but never
        // clicks; 50ms clicks reliably (soak-tested).
        private const val MIN_TAP_MS = 50L

        // Cap for the synthetic press of a click-intent tap (held shorter than the system
        // long-press timeout). The click fires at stroke end, so every capped ms is
        // perceived latency removed. Holds at or past the long-press timeout get
        // HOLD_STROKE_CAP_MS instead, which still outlasts the timeout.
        private const val TAP_STROKE_CAP_MS = 60L

        // Cap for the synthetic press of a held tap. Long enough for the target's long-press
        // to fire, short enough that the zones — untouchable until the stroke ends — are not
        // dead for as long as the user happened to hold. Targets that measure the hold
        // itself (drag pickup, press-to-record) see the capped press instead of the real one.
        private const val HOLD_STROKE_CAP_MS = 600L
        private const val HOLD_TIMEOUT_MARGIN_MS = 100L
        private const val TAP_SLOP_PX = 12f

        // Set true to tint the gesture zones so their touch areas are visible while testing.
        private const val DEBUG_SHOW_ZONES = false
        private const val ZONE_DEBUG_COLOR = 0x552196F3 // translucent blue
    }
}

/**
 * Abstraction over the gesture-dispatch capabilities the controller needs from its owning
 * accessibility service: firing navigation actions and re-injecting unused touches. Keeps
 * the controller decoupled from the concrete service (and testable).
 */
interface GestureDispatcher {
    /** Fires a navigation action (Back/Home/Recents). */
    fun trigger(action: com.ogesture.data.GestureAction)

    /**
     * Re-injects a touch the overlay consumed but didn't use, so it reaches the UI
     * underneath. Returns false if the gesture could not be dispatched; [onDone] always
     * runs otherwise, with whether the gesture played to completion.
     */
    fun replay(gesture: GestureDescription, onDone: (completed: Boolean) -> Unit): Boolean
}
