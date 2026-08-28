package com.ogesture.data

enum class GestureAction { BACK, HOME, RECENTS }

enum class ZoneId { BOTTOM, LEFT_EDGE, RIGHT_EDGE }

enum class SwipeDirection { UP, RIGHT, LEFT }

data class ZoneConfig(
    val id: ZoneId,
    val action: GestureAction,
    val longAction: GestureAction?,
    val lengthPercent: Int,
    val thicknessDp: Int,
) {
    val swipeDirection: SwipeDirection get() = when (id) {
        ZoneId.BOTTOM -> SwipeDirection.UP
        ZoneId.LEFT_EDGE -> SwipeDirection.RIGHT
        ZoneId.RIGHT_EDGE -> SwipeDirection.LEFT
    }
}

/**
 * The user-tunable geometry of the gesture activation zones. Only physical dimensions are
 * configurable; the action/direction mapping (Back/Home/Recents, swipe directions) is fixed
 * and never derived from these settings.
 *
 * - [backActivationHeightPercent] / [bottomActivationWidthPercent] describe how much of the
 *   relevant edge can *start* a gesture (10%..100%).
 * - [backEdgeSensitivity] / [bottomEdgeSensitivity] are multipliers on the *base* zone
 *   thickness (how far inward from the physical edge a touch may begin). They do NOT change
 *   the swipe-distance threshold — only the starting hit-region depth.
 *
 * Side Back zones are anchored toward the bottom of the screen (measured upward from just
 * above the reserved bottom gesture band), not vertically centered.
 */
data class GestureZoneSettings(
    val backActivationHeightPercent: Int,
    val bottomActivationWidthPercent: Int,
    val backEdgeSensitivity: Float,
    val bottomEdgeSensitivity: Float,
) {
    /** Effective Back zone thickness in dp, before the nav-bar inset is added. */
    val backThicknessDp: Int get() = (BASE_BACK_THICKNESS_DP * backEdgeSensitivity).toInt().coerceAtLeast(1)

    /** Effective bottom zone thickness in dp, before the nav-bar inset is added. */
    val bottomThicknessDp: Int get() = (BASE_BOTTOM_THICKNESS_DP * bottomEdgeSensitivity).toInt().coerceAtLeast(1)

    companion object {
        // Base (1.0×) gesture-zone thicknesses. Sensitivity multiplies these; the nav-bar
        // inset is added afterward and is never multiplied.
        const val BASE_BACK_THICKNESS_DP = 16
        const val BASE_BOTTOM_THICKNESS_DP = 12

        // Activation-area percentage bounds (10%..100%, 10% step).
        const val PERCENT_MIN = 10
        const val PERCENT_MAX = 100
        const val PERCENT_STEP = 10
        const val DEFAULT_BACK_HEIGHT_PERCENT = 80
        const val DEFAULT_BOTTOM_WIDTH_PERCENT = 80

        // Edge-sensitivity multiplier bounds (0.5×..4.0×, 0.25× step).
        const val SENSITIVITY_MIN = 0.5f
        const val SENSITIVITY_MAX = 4.0f
        const val SENSITIVITY_STEP = 0.25f
        const val DEFAULT_BACK_SENSITIVITY = 1.0f
        const val DEFAULT_BOTTOM_SENSITIVITY = 1.0f

        val DEFAULT = GestureZoneSettings(
            backActivationHeightPercent = DEFAULT_BACK_HEIGHT_PERCENT,
            bottomActivationWidthPercent = DEFAULT_BOTTOM_WIDTH_PERCENT,
            backEdgeSensitivity = DEFAULT_BACK_SENSITIVITY,
            bottomEdgeSensitivity = DEFAULT_BOTTOM_SENSITIVITY,
        )

        /**
         * Normalizes a raw persisted percentage: snaps to the nearest PERCENT_STEP step, then
         * clamps to [PERCENT_MIN]..[PERCENT_MAX]. Midpoints (e.g. 15) round half-up to the next
         * step (→ 20). A valid persisted/runtime value is therefore always one of
         * 10, 20, 30, 40, 50, 60, 70, 80, 90, 100.
         *
         * Use this on both DataStore reads and writes so a corrupted/out-of-range preference
         * never produces an absurd value.
         */
        fun clampPercent(value: Int): Int {
            val steps = Math.round(value.toFloat() / PERCENT_STEP)
            return (steps * PERCENT_STEP).coerceIn(PERCENT_MIN, PERCENT_MAX)
        }

        /** Rounds a raw persisted sensitivity to the nearest 0.25 step and clamps it. */
        fun clampSensitivity(value: Float): Float {
            val stepped = (value / SENSITIVITY_STEP).toInt().let { steps ->
                (value - steps * SENSITIVITY_STEP).let { remainder ->
                    if (remainder >= SENSITIVITY_STEP / 2f) steps + 1 else steps
                }
            }
            return (stepped * SENSITIVITY_STEP).coerceIn(SENSITIVITY_MIN, SENSITIVITY_MAX)
        }
    }
}

data class GestureCancellationSettings(val cancelBack: Boolean = false, val cancelHome: Boolean = false)

/**
 * Builds the runtime [ZoneConfig] list from the user's [settings]. Action/direction mapping
 * is static; only geometry (length percent, thickness) comes from the settings.
 */
fun buildGestureZones(settings: GestureZoneSettings): List<ZoneConfig> = listOf(
    ZoneConfig(
        id = ZoneId.BOTTOM,
        action = GestureAction.HOME,
        longAction = GestureAction.RECENTS,
        lengthPercent = settings.bottomActivationWidthPercent,
        thicknessDp = settings.bottomThicknessDp,
    ),
    ZoneConfig(
        id = ZoneId.LEFT_EDGE,
        action = GestureAction.BACK,
        longAction = null,
        lengthPercent = settings.backActivationHeightPercent,
        thicknessDp = settings.backThicknessDp,
    ),
    ZoneConfig(
        id = ZoneId.RIGHT_EDGE,
        action = GestureAction.BACK,
        longAction = null,
        lengthPercent = settings.backActivationHeightPercent,
        thicknessDp = settings.backThicknessDp,
    ),
)

/**
 * The fixed default gesture layout: swipe up from the bottom for Home (hold for Recents),
 * swipe in from either side edge for Back. Kept as the default-configuration source and for
 * tests that assert the static action/direction mapping.
 */
val GESTURE_ZONES: List<ZoneConfig> = buildGestureZones(GestureZoneSettings.DEFAULT)

/**
 * Display geometry the gesture-zone layout depends on: full display size, density, and the
 * navigation-bar insets per edge (zero for bar-free edges). Android-independent so the
 * layout math can be unit-tested without a WindowManager.
 */
data class ScreenGeometry(
    val width: Int,
    val height: Int,
    val density: Float,
    val navLeft: Int,
    val navRight: Int,
    val navBottom: Int,
)

/**
 * Resolved on-screen geometry of one gesture touch zone: the absolute screen-coordinate
 * touch [rect] (in display pixels) plus the [widthPx]/[heightPx]/[yOffset] the controller
 * needs to place the window with BOTTOM/START/END gravity. The layout test uses [rect] to
 * prove the side and bottom zones never overlap and never leave a gap; the controller uses
 * [widthPx]/[heightPx]/[yOffset] to build the actual
 * [android.view.WindowManager.LayoutParams] (it maps the zone id to the Gravity constant
 * itself, since Gravity is an Android type).
 */
data class ZoneLayout(
    val zoneId: ZoneId,
    val rect: IntArray,
    val widthPx: Int,
    val heightPx: Int,
    val yOffset: Int,
) {
    /** Absolute left of the touch region in display pixels. */
    val left: Int get() = rect[0]
    /** Absolute top of the touch region in display pixels. */
    val top: Int get() = rect[1]
    /** Absolute right of the touch region in display pixels. */
    val right: Int get() = rect[2]
    /** Absolute bottom of the touch region in display pixels. */
    val bottom: Int get() = rect[3]
}

/**
 * The single source of truth for gesture-zone window geometry, shared by
 * [com.ogesture.service.EdgeOverlayController] (production WindowManager placement) and the
 * layout unit tests. Pure — no Android types.
 *
 * Invariants (the corner-precedence contract):
 * - `effectiveBottomTouchDepthPx = settings.bottomThicknessDp * density`
 * - `reservedBottomBandPx   = effectiveBottomTouchDepthPx + navBottom`  (navBottom is NEVER multiplied by sensitivity)
 * - `effectiveBackDepthPx    = settings.backThicknessDp    * density`
 * - side Back window width  = `effectiveBackDepthPx + navSide`  (navSide never multiplied)
 * - the bottom Home/Recents zone owns the full reserved bottom band (screen y in
 *   `[height - reservedBottomBandPx, height]`)
 * - the side Back zones are anchored to the BOTTOM and offset upward by exactly
 *   `reservedBottomBandPx`, so their bottom edge sits immediately above the reserved band:
 *   no vertical overlap, no dead gap. Their height is `backActivationHeightPercent` of the
 *   usable side span *above* the band (`height - reservedBottomBandPx`).
 */
fun computeGestureZoneLayout(
    settings: GestureZoneSettings,
    geometry: ScreenGeometry,
): Map<ZoneId, ZoneLayout> {
    val density = geometry.density
    val effectiveBottomDepthPx = (settings.bottomThicknessDp * density).toInt().coerceAtLeast(1)
    val reservedBottomBandPx = effectiveBottomDepthPx + geometry.navBottom
    val effectiveBackDepthPx = (settings.backThicknessDp * density).toInt().coerceAtLeast(1)

    val usableSideHeightPx = (geometry.height - reservedBottomBandPx).coerceAtLeast(1)
    val bottomWidthPx = (geometry.width * settings.bottomActivationWidthPercent / 100).coerceAtLeast(1)
    val sideHeightPx = (usableSideHeightPx * settings.backActivationHeightPercent / 100).coerceAtLeast(1)

    // Bottom zone: centered horizontally, flush with the bottom (spanning its nav inset).
    val bottomLeft = ((geometry.width - bottomWidthPx) / 2).coerceAtLeast(0)
    val bottomRight = (bottomLeft + bottomWidthPx).coerceAtMost(geometry.width)
    val bottomTop = geometry.height - reservedBottomBandPx
    val bottom = ZoneLayout(
        zoneId = ZoneId.BOTTOM,
        rect = intArrayOf(bottomLeft, bottomTop, bottomRight, geometry.height),
        widthPx = bottomRight - bottomLeft,
        heightPx = reservedBottomBandPx,
        yOffset = 0,
    )

    // Left Back zone: anchored to the bottom, offset up by the reserved bottom band so it sits
    // immediately above it (no overlap, no gap). Spans its left nav inset.
    val leftWidthPx = effectiveBackDepthPx + geometry.navLeft
    val leftBottom = bottomTop // immediately above the reserved band
    val leftTop = leftBottom - sideHeightPx
    val left = ZoneLayout(
        zoneId = ZoneId.LEFT_EDGE,
        rect = intArrayOf(0, leftTop, leftWidthPx, leftBottom),
        widthPx = leftWidthPx,
        heightPx = sideHeightPx,
        yOffset = reservedBottomBandPx,
    )

    // Right Back zone: mirrored.
    val rightWidthPx = effectiveBackDepthPx + geometry.navRight
    val rightLeft = geometry.width - rightWidthPx
    val rightBottom = bottomTop
    val rightTop = rightBottom - sideHeightPx
    val right = ZoneLayout(
        zoneId = ZoneId.RIGHT_EDGE,
        rect = intArrayOf(rightLeft, rightTop, geometry.width, rightBottom),
        widthPx = rightWidthPx,
        heightPx = sideHeightPx,
        yOffset = reservedBottomBandPx,
    )

    return mapOf(ZoneId.BOTTOM to bottom, ZoneId.LEFT_EDGE to left, ZoneId.RIGHT_EDGE to right)
}

/**
 * The set of gesture zones that must all be attached for the runtime to be considered "ready".
 * The system-nav watchdog only hides the OEM navbar when ALL of these are working — a partial
 * set (e.g. BOTTOM failed but LEFT/RIGHT succeeded) is NOT sufficient.
 */
val REQUIRED_GESTURE_ZONES: Set<ZoneId> = setOf(ZoneId.BOTTOM, ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE)

/**
 * True iff all required gesture zones are present in [active]. Used by [EdgeOverlayController]
 * to decide runtime readiness for the system-nav watchdog.
 */
fun areRequiredGestureZonesAttached(active: Set<ZoneId>): Boolean =
    active == REQUIRED_GESTURE_ZONES

/**
 * True iff Ogesture's gesture navigation is actually available to replace the system navbar.
 * Requires all 3 zones attached AND pass-through is NOT active — in a pass-through app Ogesture
 * deliberately ignores all touches, so it cannot replace the system navbar there.
 * Does NOT include zonesHeld/replaying (those are transient during a gesture and must not
 * trigger a system-nav restore).
 */
fun isGestureNavigationAvailable(active: Set<ZoneId>, passThrough: Boolean): Boolean =
    areRequiredGestureZonesAttached(active) && !passThrough
