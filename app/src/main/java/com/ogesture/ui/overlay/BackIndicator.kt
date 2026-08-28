package com.ogesture.ui.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * A gesture-navigation style back arrow that peeks out from a side edge while the user
 * drags, mirroring the system back indicator: it slides out with the drag, pulses when
 * the gesture arms, and retracts (or fades out) when the finger lifts.
 *
 * Lives in its own non-touchable full-height overlay window so it can be drawn without
 * affecting the touch zones. The window type is supplied by the owner so the same
 * indicator can be added as an accessibility overlay (trusted, works on secure screens)
 * or — for any future caller — an application overlay.
 */
class BackIndicator(
    context: Context,
    private val windowManager: WindowManager,
    private val fromLeftEdge: Boolean,
    private val armDistancePx: Float,
    /**
     * How far in from the physical edge the arrow should peek — the nav-bar inset when
     * the 3-button bar occupies this edge (landscape), else 0. Without it the arrow
     * would slide out underneath the opaque bar and never be seen.
     */
    private val edgeOffsetPx: Int = 0,
    /**
     * WindowManager layout type for the indicator window. Defaults to an accessibility
     * overlay so the indicator survives on secure system screens (Settings, SubSettings)
     * the way the touch zones do.
     */
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
) : OverlayIndicator {
    private val density = context.resources.displayMetrics.density
    private val pillSizePx = (PILL_SIZE_DP * density)
    private val peekPx = (PEEK_DP * density)

    private val root = FrameLayout(context)
    private val arrow = BackArrowView(context).apply {
        val size = pillSizePx.toInt()
        layoutParams = FrameLayout.LayoutParams(size, size).apply {
            gravity = (if (fromLeftEdge) Gravity.START else Gravity.END) or Gravity.TOP
        }
        alpha = 0f
    }
    private var attached = false
    private var windowHidden = false
    private val windowLocation = IntArray(2)
    private var anchorRawY = 0f
    // Cached window top in display coordinates, captured once per gesture start so the per-MOVE
    // pillY() path never calls getLocationOnScreen().
    private var cachedWindowY = 0

    init {
        root.addView(arrow)
    }

    override fun attach() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            (pillSizePx + peekPx).toInt(),
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = (if (fromLeftEdge) Gravity.START else Gravity.END) or Gravity.TOP
            // With START/END gravity, x offsets away from that edge — clear of the nav bar.
            x = edgeOffsetPx
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                fitInsetsTypes = 0
            }
        }
        try {
            windowManager.addView(root, params)
            attached = true
        } catch (_: Throwable) {
            // Indicator is cosmetic; the gesture keeps working without it.
        }
    }

    override fun detach() {
        if (!attached) return
        try {
            windowManager.removeView(root)
        } catch (_: Throwable) {
        }
        attached = false
    }

    override fun setWindowHidden(hidden: Boolean) {
        if (!attached) return
        windowHidden = hidden
        val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
        val newAlpha = if (hidden) 0f else 1f
        if (lp.alpha == newAlpha) return
        lp.alpha = newAlpha
        try {
            windowManager.updateViewLayout(root, lp)
        } catch (_: Throwable) {
        }
    }

    override fun windowBounds(): Rect? {
        if (!attached || windowHidden) return null
        val loc = IntArray(2)
        root.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + root.width, loc[1] + root.height)
    }

    fun onGestureStart(rawY: Float) {
        arrow.animate().cancel()
        arrow.scaleX = 1f
        arrow.scaleY = 1f
        arrow.alpha = 1f
        anchorRawY = rawY
        // Capture the window's display Y once per gesture; reused by every onGestureProgress.
        root.getLocationOnScreen(windowLocation)
        cachedWindowY = windowLocation[1]
        arrow.translationY = pillY(rawY)
        applyProgress(0f)
    }

    fun onGestureProgress(distancePx: Float, rawY: Float) {
        arrow.translationY = pillY(followedRawY(rawY))
        applyProgress((distancePx / armDistancePx).coerceIn(0f, 1f))
    }

    /**
     * The pill anchors where the gesture began and only drifts a fraction of the finger's
     * vertical travel, so it nods toward the drag without chasing the finger.
     */
    private fun followedRawY(rawY: Float): Float =
        anchorRawY + (rawY - anchorRawY) * FOLLOW_FRACTION

    /** rawY is in display coordinates; the window may not start at display y=0. Uses the
     *  cached [cachedWindowY] captured at gesture start — no getLocationOnScreen per MOVE. */
    private fun pillY(rawY: Float): Float {
        return rawY - cachedWindowY - pillSizePx / 2f
    }

    fun onArmed() {
        applyProgress(1f)
    }

    fun onGestureEnd(fired: Boolean) {
        val retractX = if (fromLeftEdge) -pillSizePx else pillSizePx
        arrow.animate()
            .translationX(retractX)
            .alpha(0f)
            .setDuration(if (fired) 120L else 180L)
            .start()
    }

    /** 0 = fully hidden behind the edge, 1 = fully peeked out. */
    private fun applyProgress(fraction: Float) {
        val hidden = if (fromLeftEdge) -pillSizePx else pillSizePx
        val shown = if (fromLeftEdge) peekPx else -peekPx
        arrow.translationX = hidden + (shown - hidden) * fraction
    }

    private companion object {
        const val PILL_SIZE_DP = 36f
        const val PEEK_DP = 10f

        // Vertical follow: the pill moves this fraction of the finger's vertical travel,
        // so it hints at the drag direction without tracking it.
        const val FOLLOW_FRACTION = 0.25f
    }
}

/** A round dark pill with a left-pointing "back" chevron, whichever edge it comes from. */
private class BackArrowView(context: Context) : View(context) {

    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xE0202124.toInt()
        style = Paint.Style.FILL
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2.2f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chevron = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val cx = w / 2f
        val cy = h / 2f
        val arm = w * 0.13f
        val tip = cx - arm * 0.7f
        val tail = cx + arm * 0.7f
        chevron.reset()
        chevron.moveTo(tail, cy - arm * 1.4f)
        chevron.lineTo(tip, cy)
        chevron.lineTo(tail, cy + arm * 1.4f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(width / 2f, height / 2f, width / 2f, pillPaint)
        canvas.drawPath(chevron, arrowPaint)
    }
}
