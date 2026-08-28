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
 * A persistent home handle: a low, wide trapezoid with 45-degree sloped sides (like a
 * flat mountain) and softly rounded top corners, sitting flush against the bottom edge
 * of the screen. It is always visible while gestures are enabled and lifts a few dp on
 * the bottom (home/recents) gesture to reveal a little more of itself.
 */
class HomeIndicator(
    context: Context,
    private val windowManager: WindowManager,
    /**
     * Resolved visual width of the home handle in *display pixels*. This is the SAME resolved
     * width the production geometry helper ([com.ogesture.data.computeGestureZoneLayout])
     * produces for the bottom gesture touch zone, so the visible bar matches the actual
     * horizontal activation region 1:1 (10% → central 10% of the display, … 100% → full
     * configured bottom activation width). Only the horizontal width follows the activation
     * width; the invisible touch depth (bottom edge sensitivity) does NOT affect the bar —
     * it only widens the touch zone vertically.
     */
    private val barWidthPx: Int = (BAR_WIDTH_DP * context.resources.displayMetrics.density).toInt(),
    /**
     * WindowManager layout type for the indicator window. Defaults to an accessibility
     * overlay so the indicator survives on secure system screens (Settings, SubSettings)
     * the way the touch zones do.
     */
    private val windowType: Int = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
) : OverlayIndicator {

    private val density = context.resources.displayMetrics.density
    private val barHeightPx = BAR_HEIGHT_DP * density
    private val radiusPx = ROUND_DP * density
    private val revealPx = REVEAL_DP * density

    private val root = FrameLayout(context)
    private val bar = MountainBar(context, barHeightPx, radiusPx).apply {
        layoutParams = FrameLayout.LayoutParams(barWidthPx, barHeightPx.toInt()).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        }
    }
    private var attached = false
    private var windowHidden = false

    init {
        root.clipChildren = false
        root.addView(bar)
    }

    override fun attach() {
        if (attached) return
        val params = WindowManager.LayoutParams(
            barWidthPx,
            (barHeightPx + revealPx).toInt(),
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
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

    /** Lift the handle slightly to reveal more of it while the bottom gesture is active. */
    fun onGestureStart() {
        bar.animate().translationY(-revealPx).setDuration(120L).start()
    }

    /** Settle the handle back flush with the bottom edge. */
    fun onGestureEnd() {
        bar.animate().translationY(0f).setDuration(160L).start()
    }

    private companion object {
        // Default handle width; matches the pre-configurability look at 80% activation width.
        const val BAR_WIDTH_DP = 108
        const val BAR_HEIGHT_DP = 4f
        const val ROUND_DP = 1.5f
        const val REVEAL_DP = 4f
    }
}

/**
 * Draws the flat-topped, 45-degree-sloped trapezoid with rounded top corners, its base
 * flush with the bottom edge.
 */
private class MountainBar(
    context: Context,
    private val heightPx: Float,
    private val radiusPx: Float,
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BAR_COLOR
        style = Paint.Style.FILL
    }
    private val shape = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 45 degrees => the top edge is inset from each side by the height.
        val inset = heightPx
        val r = radiusPx
        val diag = r * 0.7071f // where a rounded corner meets the 45-degree slope
        shape.reset()
        shape.moveTo(0f, h.toFloat())                           // bottom-left
        shape.lineTo(inset - diag, diag)                        // up the left slope
        shape.quadTo(inset, 0f, inset + r, 0f)                  // rounded top-left corner
        shape.lineTo(w - inset - r, 0f)                         // across the flat top
        shape.quadTo(w - inset, 0f, w - inset + diag, diag)     // rounded top-right corner
        shape.lineTo(w.toFloat(), h.toFloat())                  // down the right slope
        shape.close()                                           // base, back to bottom-left
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawPath(shape, paint)
    }

    private companion object {
        const val BAR_COLOR = 0x90666666.toInt() // grey transparent
    }
}
