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
import com.ogesture.data.GestureCancellationSettings
import com.ogesture.data.GestureZoneSettings
import com.ogesture.data.ScreenGeometry
import com.ogesture.data.SettingsRepository
import com.ogesture.data.ZoneConfig
import com.ogesture.data.ZoneId
import com.ogesture.data.ZoneLayout
import com.ogesture.data.areRequiredGestureZonesAttached
import com.ogesture.data.buildGestureZones
import com.ogesture.data.isGestureNavigationAvailable
import com.ogesture.data.computeGestureZoneLayout
import com.ogesture.gesture.SampleView
import com.ogesture.gesture.SwipeDetector
import com.ogesture.ui.overlay.BackIndicator
import com.ogesture.ui.overlay.HomeIndicator
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
    /** Called when the runtime readiness of all gesture zones changes (true = all attached, false = detached). */
    private val onRuntimeReadyChange: (Boolean) -> Unit = {},
) {
    private val activeViews = mutableMapOf<ZoneId, View>()
    private val indicators = mutableMapOf<ZoneId, OverlayIndicator>()
    // The SwipeDetector for each zone, retained so it can be disposed on detach/rebuild (cancels
    // any pending Recents hold callback and drops the View reference so it can't fire or leak).
    private val detectors = mutableMapOf<ZoneId, SwipeDetector>()
    private var attached = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var replaying = false

    // Monotonic generation token bumped on every detach/rebuild. A replay dispatched in
    // generation N carries a snapshot of this value; its completion callback checks it before
    // mutating state, so a stale callback from a torn-down controller can never affect a rebuilt
    // one.
    private var generation = 0L

    // Cached haptic effect + vibrator, created once so the gesture-trigger path is a direct call
    // with no allocation or service lookup per trigger.
    private val hapticEffect = VibrationEffect.createOneShot(12L, VibrationEffect.DEFAULT_AMPLITUDE)
    private val vibrator: Any? = run {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
    }

    // Job that owns every Flow collector started by this controller. Cancelled in [stop] so
    // after teardown no DataStore emission can call back into a stale controller (no ghost
    // rebuilds, no duplicate observers after a service reconnect).
    private var controllerJob: kotlinx.coroutines.Job? = null

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

    // Set for the rare replay whose tap point lies under a visible indicator window (the
    // back arrow's edge strip, or the home handle when the nav bar is hidden): the
    // indicator must be hidden first — two stacked overlay windows exceed Android's 0.8
    // obscuring-opacity cap for injected touches — and that hide is issued only at
    // replay time, so those replays keep the INJECT_DELAY_MS grace for it to apply.
    private var hideIndicatorsForReplay = false

    // Named runnables for the current replay's start + safety-timeout, retained so [stop]/
    // [detachAll] can cancel them and a stale callback can't mutate a rebuilt controller.
    private var replayStartRunnable: Runnable? = null
    private var replayFinishRunnable: Runnable? = null

    /** Display geometry the attached zones were laid out for. Null while nothing is attached. */
    private var lastGeometry: ScreenGeometry? = null

    /** The settings the currently-attached zones were built from. Null while nothing is attached. */
    @Volatile private var lastSettings: GestureZoneSettings? = null
    @Volatile private var lastCancellationSettings: GestureCancellationSettings? = null

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
        // Idempotent: if a previous start left a controllerJob alive (e.g. a reconnect that
        // didn't go through stop), cancel it before starting fresh so collectors don't stack.
        controllerJob?.cancel()
        controllerJob = kotlinx.coroutines.SupervisorJob(scope.coroutineContext[kotlinx.coroutines.Job])
        val cs = kotlinx.coroutines.CoroutineScope(scope.coroutineContext + controllerJob!!)

        (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
            .registerDisplayListener(displayListener, mainHandler)

        // One combined runtime configuration: master switch + the four geometry settings.
        // A change to any geometry field rebuilds the zones exactly once (distinctUntilChanged
        // on the whole settings record), so dragging a slider produces one rebuild per commit,
        // not one per field. Pass-through is observed separately because it changes
        // interactivity, not geometry.
        cs.launch {
            combine(repo.masterEnabled, repo.gestureZoneSettings, repo.gestureCancellationSettings) { enabled, settings, cancellation -> Triple(enabled, settings, cancellation) }.distinctUntilChanged().collect { (enabled, settings, cancellation) ->
                if (!enabled) {
                    detachAll()
                    return@collect
                }
                rebuild(settings, cancellation)
            }
        }

        cs.launch {
            combine(
                EdgeGestureAccessibilityService.foregroundPackage,
                repo.excludedApps,
            ) { pkg, excluded -> pkg != null && pkg in excluded }
                .distinctUntilChanged()
                .collect { excluded ->
                    passThrough = excluded
                    applyZoneInteractivity()
                    reportNavigationAvailability()
                }
        }
    }

    /**
     * Tears down every window, unregisters listeners, cancels every collector this controller
     * started, and disposes every detector. After [stop] no DataStore emission can call back
     * into this controller. Idempotent.
     */
    fun stop() {
        controllerJob?.cancel()
        controllerJob = null
        try {
            (context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .unregisterDisplayListener(displayListener)
        } catch (_: Throwable) { /* never registered */ }
        cancelReplayCallbacks()
        detachAll()
    }

    /** Cancels any pending replay-start/safety-timeout callback so a stale one can't mutate state. */
    private fun cancelReplayCallbacks() {
        replayFinishRunnable?.let { mainHandler.removeCallbacks(it) }
        replayStartRunnable?.let { mainHandler.removeCallbacks(it) }
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
        // Re-lay out for the new display geometry using the settings the attached zones were
        // built from. A setting change arrives through the combined flow, which rebuilds with
        // the new settings; a rotation only re-lays out the existing geometry for the same
        // settings, so the two never race on which snapshot wins.
        rebuild(lastSettings ?: GestureZoneSettings.DEFAULT, lastCancellationSettings ?: GestureCancellationSettings())
    }

    /**
     * Full display size, density, and nav-bar insets the zone layout depends on. The insets
     * are part of the comparison because a 90°→270° flip keeps the same width and height
     * while the 3-button bar jumps to the opposite side — the zones must re-lay out for
     * that even though the display size alone looks unchanged.
     */
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

    private fun rebuild(settings: GestureZoneSettings, cancellation: GestureCancellationSettings) {
        val zones = buildGestureZones(settings)
        // Don't emit a transient false during rebuild — the system-nav controller would briefly
        // restore the system navbar on every rotation/geometry change. emit one final readiness
        // notification after the rebuild is complete.
        detachAll(notifyRuntime = false)
        // Detaching kills any in-flight stream (e.g. a rotation mid-touch), so its UP will
        // never arrive to release the hold — clear it or the fresh windows start dead.
        zonesHeld = false
        var success = false
        try {
        // One snapshot for the whole pass, so every zone agrees on the screen it is sizing to
        // even if a rotation lands mid-rebuild; the listener re-runs us if it changed again.
        val geometry = currentGeometry()
        lastGeometry = geometry
        // Cache the settings these zones are built from so a display-geometry change (rotation)
        // re-lays out with the same settings rather than racing the combined flow.
        lastSettings = settings
        lastCancellationSettings = cancellation
        // The Home handle's visible width is the SAME resolved horizontal width as the bottom
        // gesture touch zone, taken from the single production geometry resolver so the bar can
        // never drift from the actual activation region. Only horizontal width is shared;
        // bottom edge sensitivity widens the invisible touch zone vertically, never the bar.
        val resolvedLayout = computeGestureZoneLayout(settings, geometry)
        val homeHandleWidthPx = resolvedLayout.getValue(ZoneId.BOTTOM).widthPx
        for (zone in zones) {
            val view = View(context).apply {
                // DEBUG_SHOW_ZONES tints the touch areas so they can be seen while testing.
                setBackgroundColor(if (DEBUG_SHOW_ZONES) ZONE_DEBUG_COLOR else android.graphics.Color.TRANSPARENT)
            }
            val minDistanceDp = if (zone.id == ZoneId.BOTTOM) BOTTOM_MIN_DISTANCE_DP else SIDE_MIN_DISTANCE_DP
            val armDistancePx = minDistanceDp * context.resources.displayMetrics.density
            val indicator: OverlayIndicator
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
                    val ind = HomeIndicator(
                        context = context,
                        windowManager = windowManager,
                        barWidthPx = homeHandleWidthPx,
                        windowType = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    )
                    indicator = ind
                    // The bar is static; it only lifts slightly while a bottom gesture is
                    // in progress, then settles back flush with the edge.
                    feedback = object : SwipeDetector.Feedback {
                        override fun onStart(rawX: Float, rawY: Float) = ind.onGestureStart()
                        override fun onProgress(distancePx: Float, rawX: Float, rawY: Float) = Unit
                        override fun onArmed() = Unit
                        override fun onEnd(fired: Boolean) = ind.onGestureEnd()
                    }
                }
            }
            val detector = SwipeDetector(
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
                cancellationEnabled = if (zone.id == ZoneId.BOTTOM) cancellation.cancelHome else cancellation.cancelBack,
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
            view.setOnTouchListener(detector)
            detectors[zone.id] = detector
            val params = layoutParamsFor(zone, settings, geometry)
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
                indicator.attach()
                indicators[zone.id] = indicator
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to add overlay for ${zone.id}", t)
            }
        }
        // Runtime readiness requires ALL required zones attached (all-or-nothing). If any zone
        // failed to addView, remove the ones that did attach so the system-nav controller never
        // sees a partial 1/3 or 2/3 state — Ogesture can't replace the system bar without all
        // three gesture zones.
        // Runtime attachment success = all 3 required zones physically attached, regardless of
        // pass-through. Pass-through affects navigation availability (reported to the watchdog)
        // but NOT whether the windows exist — removing them would break gesture recovery when
        // leaving an excluded app after a rotation/settings change.
        success = areRequiredGestureZonesAttached(activeViews.keys)
        } catch (t: Throwable) {
            Log.e(TAG, "Gesture-zone rebuild failed", t)
        } finally {
            // Cleanup partial runtime if not all zones attached (or if an exception occurred).
            if (!success) {
                for ((_, v) in activeViews) {
                    try { windowManager.removeView(v) } catch (_: Throwable) { }
                }
                activeViews.clear()
                for ((_, ind) in indicators) ind.detach()
                indicators.clear()
                for ((_, d) in detectors) d.dispose()
                detectors.clear()
            }
            attached = success
            // One final readiness notification — guaranteed on EVERY exit from rebuild(), including
            // exceptions. This reports navigation availability (3/3 && !passThrough), not just
            // physical attachment, so the system navbar comes back in pass-through apps.
            reportNavigationAvailability()
            if (success) {
                // Fresh windows come up touchable; re-apply in case an excluded app is in front.
                applyZoneInteractivity()
            }
        }
    }

    private fun detachAll(notifyRuntime: Boolean = true) {
        // Cancel any pending replay callbacks and reset replay state so a rebuild during/after a
        // replay can't leave the fresh zones permanently FLAG_NOT_TOUCHABLE (the generation guard
        // makes stale callbacks no-ops, but nothing else would reset `replaying`).
        cancelReplayCallbacks()
        replaying = false
        hideIndicatorsForReplay = false
        zonesHeld = false
        // Dispose every detector so a pending Recents-hold callback can't fire into a torn-down
        // window, and the View reference is released (no leak across rebuild/reconnect).
        for ((_, d) in detectors) d.dispose()
        detectors.clear()
        for ((_, ind) in indicators) {
            ind.detach()
        }
        indicators.clear()
        if (activeViews.isEmpty()) {
            if (notifyRuntime && attached) onRuntimeReadyChange(false)
            attached = false
            generation++
            return
        }
        for ((_, v) in activeViews) {
            try {
                windowManager.removeView(v)
            } catch (_: Throwable) { /* ignore */ }
        }
        activeViews.clear()
        if (notifyRuntime && attached) onRuntimeReadyChange(false)
        attached = false
        generation++
    }

    /**
     * A consumed touch turned out not to be a gesture (tap, long-press, wrong-direction
     * drag...). Re-inject it through the accessibility service so it reaches the UI the
     * zone covered. The zone windows are already untouchable (held since the touch's
     * ACTION_DOWN), so the injected events cannot land back on us.
     */
    private fun replayUnusedTouch(samples: SampleView) {
        if (replaying || passThrough || samples.size == 0) return
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
                for (i in 1 until samples.size) lineTo(samples.x(i), samples.y(i))
            }
        }
        val rawDuration = last.timeMs - first.timeMs
        val duration = if (movedPx < TAP_SLOP_PX) {
            if (rawDuration >= android.view.ViewConfiguration.getLongPressTimeout()) {
                // A deliberate hold: mirror it so the target's long-press fires too.
                rawDuration.coerceAtMost(MAX_REPLAY_MS)
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
        val underIndicator = indicators.values.any {
            it.windowBounds()?.contains(first.x.toInt(), first.y.toInt()) == true
        }
        val injectDelay = if (underIndicator) {
            INJECT_DELAY_MS
        } else {
            (INJECT_DELAY_MS - rawDuration).coerceAtLeast(0L)
        }
        if (com.ogesture.BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "Replaying unused touch: ${samples.size} samples over ${duration}ms" +
                    if (underIndicator) " (under indicator, +${injectDelay}ms)" else "",
            )
        }
        replaying = true
        hideIndicatorsForReplay = underIndicator
        applyZoneInteractivity()
        // Capture the current generation so a completion/timeout callback from this replay can
        // only mutate state if the controller hasn't been torn down + rebuilt since.
        val replayGen = generation
        replayFinishRunnable = Runnable {
            if (replayGen == generation && replaying) {
                replaying = false
                hideIndicatorsForReplay = false
                applyZoneInteractivity()
            }
        }
        val finish = replayFinishRunnable ?: return
        // Safety net in case the result callback never arrives.
        mainHandler.postDelayed(finish, duration + injectDelay + 1_000L)
        replayStartRunnable = Runnable {
            if (replayGen != generation) return@Runnable
            val dispatched = dispatcher.replay(gesture) { completed ->
                if (com.ogesture.BuildConfig.DEBUG) Log.d(TAG, "Replay finished, completed=$completed")
                mainHandler.removeCallbacks(finish)
                if (replayGen == generation) finish.run()
            }
            if (!dispatched) {
                Log.w(TAG, "dispatchGesture refused the replay")
                mainHandler.removeCallbacks(finish)
                if (replayGen == generation) finish.run()
            }
        }
        val start = replayStartRunnable ?: return
        mainHandler.postDelayed(start, injectDelay)
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
     * feedback — except the rare replay under an indicator window, and pass-through,
     * where hiding doubles as the "gestures off here" cue.
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
        val indicatorsHidden = passThrough || hideIndicatorsForReplay
        for ((_, indicator) in indicators) {
            indicator.setWindowHidden(indicatorsHidden)
        }
    }

    private fun onZoneTriggered(zone: ZoneConfig, long: Boolean) {
        val action = (if (long) zone.longAction else zone.action) ?: return
        // Side zones already ticked when their indicator armed.
        if (zone.id == ZoneId.BOTTOM) hapticTick()
        dispatcher.trigger(action)
    }

    /**
     * Reports whether Ogesture's gesture navigation is actually available to replace the
     * system navbar: all 3 zones attached AND not in pass-through mode. Called after rebuild,
     * after pass-through changes, and after teardown. The system-nav watchdog only hides the
     * OEM navbar when this is true — in a pass-through app Ogesture ignores all touches, so
     * the system navbar must come back.
     */
    private fun reportNavigationAvailability() {
        val available = isGestureNavigationAvailable(activeViews.keys, passThrough)
        onRuntimeReadyChange(available)
    }

    private fun hapticTick() {
        // Cached effect + vibrator — no allocation or service lookup per trigger.
        val v = vibrator ?: return
        if (v is Vibrator) v.vibrate(hapticEffect)
    }

    private fun layoutParamsFor(
        zone: ZoneConfig,
        settings: GestureZoneSettings,
        geometry: ScreenGeometry,
    ): WindowManager.LayoutParams {
        // The window geometry is computed once for all zones by the shared pure helper
        // [computeGestureZoneLayout] — the same logic the layout unit tests exercise — so the
        // corner-precedence invariant (bottom band has priority; side zones sit immediately
        // above it with no overlap and no gap) is guaranteed by construction here, not by a
        // second copy of the math. See computeGestureZoneLayout for the invariant formula.
        val layout: ZoneLayout = computeGestureZoneLayout(settings, geometry).getValue(zone.id)
        val gravity = when (zone.id) {
            ZoneId.BOTTOM -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ZoneId.LEFT_EDGE -> Gravity.START or Gravity.BOTTOM
            ZoneId.RIGHT_EDGE -> Gravity.END or Gravity.BOTTOM
        }
        return WindowManager.LayoutParams(
            layout.widthPx,
            layout.heightPx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            this.gravity = gravity
            // With BOTTOM gravity, a positive y offset moves the window upward — clear of the
            // reserved bottom gesture band so the side and bottom zones don't stack in the
            // corner. FLAG_LAYOUT_NO_LIMITS lets the offset place it above the band precisely.
            y = layout.yOffset
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
        // perceived latency removed. Holds at or past the long-press timeout are
        // mirrored instead, so the target's own long-press still fires.
        private const val TAP_STROKE_CAP_MS = 60L
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
