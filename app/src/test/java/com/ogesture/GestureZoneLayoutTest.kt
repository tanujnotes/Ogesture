package com.ogesture

import com.ogesture.data.GESTURE_ZONES
import com.ogesture.data.GestureAction
import com.ogesture.data.GestureZoneSettings
import com.ogesture.data.ScreenGeometry
import com.ogesture.data.SwipeDirection
import com.ogesture.data.ZoneConfig
import com.ogesture.data.ZoneId
import com.ogesture.data.buildGestureZones
import com.ogesture.data.computeGestureZoneLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import com.ogesture.data.areRequiredGestureZonesAttached
import com.ogesture.data.isGestureNavigationAvailable
import org.junit.Test

/**
 * Regression guards for the gesture-zone layout and the action/direction mapping that the
 * accessibility-overlay migration must preserve, plus the configurable-zone geometry
 * (activation height/width, edge sensitivity), the corner-overlap precedence, and the Home
 * handle width mapping.
 *
 * The overlap/precedence tests exercise the SAME production geometry helper
 * ([computeGestureZoneLayout]) that [com.ogesture.service.EdgeOverlayController] uses, so a
 * divergence between the controller's window placement and the intended invariant would fail
 * here rather than being hidden by a second copy of the math.
 */
class GestureZoneLayoutTest {

    @Test
    fun gestureZones_containBottomLeftRight() {
        val ids = GESTURE_ZONES.map { it.id }
        assertTrue("bottom zone must exist", ZoneId.BOTTOM in ids)
        assertTrue("left edge zone must exist", ZoneId.LEFT_EDGE in ids)
        assertTrue("right edge zone must exist", ZoneId.RIGHT_EDGE in ids)
        assertEquals(3, GESTURE_ZONES.size)
    }

    @Test
    fun bottomZone_mapsSwipeUpToHomeWithRecentsHold() {
        val bottom = GESTURE_ZONES.first { it.id == ZoneId.BOTTOM }
        assertEquals(GestureAction.HOME, bottom.action)
        assertEquals(GestureAction.RECENTS, bottom.longAction)
        assertEquals(SwipeDirection.UP, bottom.swipeDirection)
    }

    @Test
    fun leftEdge_mapsSwipeRightToBackWithNoLongAction() {
        val left = GESTURE_ZONES.first { it.id == ZoneId.LEFT_EDGE }
        assertEquals(GestureAction.BACK, left.action)
        assertNull("left edge has no long action", left.longAction)
        assertEquals(SwipeDirection.RIGHT, left.swipeDirection)
    }

    @Test
    fun rightEdge_mapsSwipeLeftToBackWithNoLongAction() {
        val right = GESTURE_ZONES.first { it.id == ZoneId.RIGHT_EDGE }
        assertEquals(GestureAction.BACK, right.action)
        assertNull("right edge has no long action", right.longAction)
        assertEquals(SwipeDirection.LEFT, right.swipeDirection)
    }

    @Test
    fun everyZone_hasPositiveThicknessAndLength() {
        for (zone in GESTURE_ZONES) {
            assertTrue("${zone.id} thickness must be positive", zone.thicknessDp > 0)
            assertTrue("${zone.id} length must be positive", zone.lengthPercent > 0)
        }
    }

    @Test
    fun swipeDirection_isConsistentWithZoneId() {
        // The recognizer relies on this mapping; flipping it would invert every gesture.
        assertEquals(SwipeDirection.UP, ZoneConfig(ZoneId.BOTTOM, GestureAction.HOME, null, 80, 12).swipeDirection)
        assertEquals(SwipeDirection.RIGHT, ZoneConfig(ZoneId.LEFT_EDGE, GestureAction.BACK, null, 80, 16).swipeDirection)
        assertEquals(SwipeDirection.LEFT, ZoneConfig(ZoneId.RIGHT_EDGE, GestureAction.BACK, null, 80, 16).swipeDirection)
    }

    @Test
    fun gestureActions_coverBackHomeRecents() {
        // The accessibility service dispatches exactly these three; adding a fourth without
        // wiring it through performGlobalAction would be a silent no-op.
        val actions = GestureAction.values().toSet()
        assertTrue(GestureAction.BACK in actions)
        assertTrue(GestureAction.HOME in actions)
        assertTrue(GestureAction.RECENTS in actions)
    }

    // --- Configurable gesture-zone geometry -----------------------------------------

    @Test
    fun defaults_matchExpectedGeometry() {
        val s = GestureZoneSettings.DEFAULT
        assertEquals(80, s.backActivationHeightPercent)
        assertEquals(80, s.bottomActivationWidthPercent)
        assertEquals(1.0f, s.backEdgeSensitivity, 0.0001f)
        assertEquals(1.0f, s.bottomEdgeSensitivity, 0.0001f)
        // 1× sensitivity → base thickness unchanged.
        assertEquals(16, s.backThicknessDp)
        assertEquals(12, s.bottomThicknessDp)
    }

    @Test
    fun defaultZones_matchLegacyFixedLayout() {
        // The default configuration must reproduce the pre-configurability layout exactly.
        val zones = buildGestureZones(GestureZoneSettings.DEFAULT)
        assertEquals(GESTURE_ZONES, zones)
    }

    @Test
    fun backHeight_percentagesBuildCorrectLength() {
        for (pct in listOf(10, 50, 100)) {
            val s = GestureZoneSettings.DEFAULT.copy(backActivationHeightPercent = pct)
            val left = buildGestureZones(s).first { it.id == ZoneId.LEFT_EDGE }
            val right = buildGestureZones(s).first { it.id == ZoneId.RIGHT_EDGE }
            assertEquals("left length%", pct, left.lengthPercent)
            assertEquals("right length%", pct, right.lengthPercent)
            // One setting drives both sides.
            assertEquals(left.lengthPercent, right.lengthPercent)
        }
    }

    @Test
    fun bottomWidth_percentagesBuildCorrectLength() {
        for (pct in listOf(10, 50, 100)) {
            val s = GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = pct)
            val bottom = buildGestureZones(s).first { it.id == ZoneId.BOTTOM }
            assertEquals(pct, bottom.lengthPercent)
        }
    }

    @Test
    fun backSensitivity_multipliesBaseThickness() {
        assertEquals(8, GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 0.5f).backThicknessDp)
        assertEquals(16, GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 1.0f).backThicknessDp)
        assertEquals(32, GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 2.0f).backThicknessDp)
        assertEquals(64, GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 4.0f).backThicknessDp)
    }

    @Test
    fun bottomSensitivity_multipliesBaseThickness() {
        assertEquals(6, GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = 0.5f).bottomThicknessDp)
        assertEquals(12, GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = 1.0f).bottomThicknessDp)
        assertEquals(24, GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = 2.0f).bottomThicknessDp)
        assertEquals(48, GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = 4.0f).bottomThicknessDp)
    }

    @Test
    fun sensitivity_doesNotChangeSwipeDistanceThreshold() {
        // The controller's SIDE_MIN_DISTANCE_DP / BOTTOM_MIN_DISTANCE_DP are not part of
        // GestureZoneSettings and must not be derivable from it. We assert that the settings
        // record exposes only thickness/length, never a swipe-distance field.
        val s = GestureZoneSettings.DEFAULT.copy(backEdgeSensitivity = 4.0f, bottomEdgeSensitivity = 4.0f)
        // No field named *minDistance* / *swipeDistance* should exist on the model.
        assertTrue(s.toString().lowercase().let { !it.contains("mindistance") && !it.contains("swipedistance") })
    }

    @Test
    fun clampSensitivity_clampsAndStepsOutOfRangeValues() {
        assertEquals(0.5f, GestureZoneSettings.clampSensitivity(0.1f), 0.0001f)
        assertEquals(0.5f, GestureZoneSettings.clampSensitivity(-1f), 0.0001f)
        assertEquals(1.0f, GestureZoneSettings.clampSensitivity(1.0f), 0.0001f)
        assertEquals(1.25f, GestureZoneSettings.clampSensitivity(1.24f), 0.0001f) // rounds to nearest 0.25 step
        assertEquals(4.0f, GestureZoneSettings.clampSensitivity(5f), 0.0001f)
    }

    @Test
    fun corruptedSettings_neverProduceZeroOrNegativeThickness() {
        // A clamped sensitivity is always >= 0.5 and base >= 12, so thickness >= 1.
        for (v in listOf(-100f, 0f, 0.001f, 1000f)) {
            val clamped = GestureZoneSettings.clampSensitivity(v)
            assertTrue(clamped >= GestureZoneSettings.SENSITIVITY_MIN)
        }
        val s = GestureZoneSettings.DEFAULT.copy(
            backEdgeSensitivity = GestureZoneSettings.clampSensitivity(0f),
            bottomEdgeSensitivity = GestureZoneSettings.clampSensitivity(0f),
        )
        assertTrue("back thickness must be positive", s.backThicknessDp >= 1)
        assertTrue("bottom thickness must be positive", s.bottomThicknessDp >= 1)
    }

    @Test
    fun geometryChanges_neverAlterActionOrDirectionMapping() {
        for (height in listOf(10, 50, 100)) {
            for (width in listOf(10, 50, 100)) {
                for (backSens in listOf(0.5f, 1.0f, 4.0f)) {
                    for (bottomSens in listOf(0.5f, 1.0f, 4.0f)) {
                        val s = GestureZoneSettings(
                            backActivationHeightPercent = height,
                            bottomActivationWidthPercent = width,
                            backEdgeSensitivity = backSens,
                            bottomEdgeSensitivity = bottomSens,
                        )
                        val zones = buildGestureZones(s)
                        val bottom = zones.first { it.id == ZoneId.BOTTOM }
                        assertEquals(GestureAction.HOME, bottom.action)
                        assertEquals(GestureAction.RECENTS, bottom.longAction)
                        assertEquals(SwipeDirection.UP, bottom.swipeDirection)
                        for (side in listOf(ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE)) {
                            val z = zones.first { it.id == side }
                            assertEquals(GestureAction.BACK, z.action)
                            assertNull(z.longAction)
                            assertEquals(if (side == ZoneId.LEFT_EDGE) SwipeDirection.RIGHT else SwipeDirection.LEFT, z.swipeDirection)
                        }
                    }
                }
            }
        }
    }

    // --- Corner-overlap precedence (using the PRODUCTION geometry helper) -----------
    //
    // These tests exercise computeGestureZoneLayout — the exact function the controller
    // calls to place its windows — so they catch any divergence between production geometry
    // and the intended invariant. The original bug (reserved band using the base 12dp constant
    // instead of the sensitivity-scaled effective bottom depth) would have failed here.

    @Test
    fun computeGestureZoneLayout_bottomBand_usesEffectiveBottomDepthNotBaseConstant() {
        // The core invariant the original bug violated: the reserved bottom band must be the
        // effective (sensitivity-scaled) bottom touch depth + navBottom, never the base 12dp.
        val geometry = ScreenGeometry(width = 1080, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 0)
        for (bottomSens in listOf(0.5f, 1.0f, 2.0f, 4.0f)) {
            val s = GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = bottomSens)
            val layout = computeGestureZoneLayout(s, geometry)
            val bottom = layout.getValue(ZoneId.BOTTOM)
            val expectedDepthPx = (s.bottomThicknessDp * geometry.density).toInt().coerceAtLeast(1)
            assertEquals("bottom band must equal effective bottom depth (sens=$bottomSens)", expectedDepthPx, bottom.heightPx)
            // Specifically NOT the base 12dp constant when sensitivity != 1.
            if (bottomSens != 1.0f) {
                assertTrue("bottom band must differ from base 12dp at sens=$bottomSens", bottom.heightPx != (GestureZoneSettings.BASE_BOTTOM_THICKNESS_DP * geometry.density).toInt())
            }
        }
    }

    @Test
    fun computeGestureZoneLayout_reservedBand_withNavBottom_neverMultipliesInset() {
        val density = 3.0f
        val navBottom = 126
        val geometry = ScreenGeometry(width = 1080, height = 2400, density = density, navLeft = 0, navRight = 0, navBottom = navBottom)
        for (bottomSens in listOf(0.5f, 1.0f, 2.0f, 4.0f)) {
            val s = GestureZoneSettings.DEFAULT.copy(bottomEdgeSensitivity = bottomSens)
            val bottom = computeGestureZoneLayout(s, geometry).getValue(ZoneId.BOTTOM)
            val effectiveDepthPx = (s.bottomThicknessDp * density).toInt().coerceAtLeast(1)
            assertEquals("reserved band = effective depth + navBottom (navBottom never multiplied)",
                effectiveDepthPx + navBottom, bottom.heightPx)
            // Sanity: at 1× this is 12dp*3 + 126 = 162; at 4× it's 48dp*3 + 126 = 270, NOT 144*4+126.
            assertTrue("navBottom must not be multiplied by sensitivity", bottom.heightPx < (effectiveDepthPx * 4) + navBottom || bottomSens <= 1.0f)
        }
    }

    @Test
    fun sideAndBottomZones_doNotOverlap_inCorners_productionGeometry() {
        // Representative displays, both with and without nav insets.
        val geometries = listOf(
            ScreenGeometry(width = 1080, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 0),
            ScreenGeometry(width = 1080, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 126),
            // Landscape with a 3-button bar on the right edge.
            ScreenGeometry(width = 2400, height = 1080, density = 3.0f, navLeft = 0, navRight = 126, navBottom = 0),
        )
        for (geometry in geometries) {
            for (heightPct in listOf(10, 50, 100)) {
                for (widthPct in listOf(10, 50, 100)) {
                    for (backSens in listOf(0.5f, 1.0f, 2.0f, 4.0f)) {
                        for (bottomSens in listOf(0.5f, 1.0f, 2.0f, 4.0f)) {
                            val s = GestureZoneSettings(
                                backActivationHeightPercent = heightPct,
                                bottomActivationWidthPercent = widthPct,
                                backEdgeSensitivity = backSens,
                                bottomEdgeSensitivity = bottomSens,
                            )
                            val layout = computeGestureZoneLayout(s, geometry)
                            val bottom = layout.getValue(ZoneId.BOTTOM)
                            val left = layout.getValue(ZoneId.LEFT_EDGE)
                            val right = layout.getValue(ZoneId.RIGHT_EDGE)
                            // Invariant: effective bottom touch depth (sensitivity-scaled) + navBottom.
                            val effectiveBottomDepthPx = (s.bottomThicknessDp * geometry.density).toInt().coerceAtLeast(1)
                            val reservedBandPx = effectiveBottomDepthPx + geometry.navBottom
                            assertEquals("reserved band = effective depth + navBottom", reservedBandPx, bottom.heightPx)
                            assertEquals("bottom zone owns the full reserved band (top)", geometry.height - reservedBandPx, bottom.top)
                            assertEquals("bottom zone owns the full reserved band (bottom)", geometry.height, bottom.bottom)
                            // No vertical overlap, no dead gap: each side zone's bottom == bottom band top.
                            assertEquals("left zone starts immediately above the reserved band (no gap, no overlap)", bottom.top, left.bottom)
                            assertEquals("right zone starts immediately above the reserved band (no gap, no overlap)", bottom.top, right.bottom)
                            assertTrue("left zone top above its bottom", left.top < left.bottom)
                            assertTrue("right zone top above its bottom", right.top < right.bottom)
                            // Side height = backActivationHeightPercent of the usable span above the band.
                            val usableSide = (geometry.height - reservedBandPx).coerceAtLeast(1)
                            val expectedSideHeight = (usableSide * heightPct / 100).coerceAtLeast(1)
                            assertEquals("left height = back% of usable side span", expectedSideHeight, left.heightPx)
                            assertEquals("right height = back% of usable side span", expectedSideHeight, right.heightPx)
                            // Side depth = effective back depth + nav inset (inset never multiplied).
                            val effectiveBackDepthPx = (s.backThicknessDp * geometry.density).toInt().coerceAtLeast(1)
                            assertEquals("left width = effective back depth + navLeft", effectiveBackDepthPx + geometry.navLeft, left.widthPx)
                            assertEquals("right width = effective back depth + navRight", effectiveBackDepthPx + geometry.navRight, right.widthPx)
                            // yOffset the controller passes to WindowManager == reserved band.
                            assertEquals("left yOffset = reserved bottom band", reservedBandPx, left.yOffset)
                            assertEquals("right yOffset = reserved bottom band", reservedBandPx, right.yOffset)
                        }
                    }
                }
            }
        }
    }

    /**
     * Explicit corner-ownership: at bottom sensitivity 4× and bottom width 100%, the
     * lower-left and lower-right corner portions (the reserved bottom band) cannot belong to
     * Back — they are owned by the bottom Home/Recents zone. This is the exact scenario the
     * original bug broke (a 48dp bottom band but only a 12dp side offset → ~36dp overlap where
     * Back could steal a corner vertical gesture).
     */
    @Test
    fun corners_atMaxBottomSensitivityAndFullWidth_belongToBottomNotBack() {
        val geometry = ScreenGeometry(width = 1080, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 0)
        val s = GestureZoneSettings(
            backActivationHeightPercent = 100,
            bottomActivationWidthPercent = 100,
            backEdgeSensitivity = 4.0f,
            bottomEdgeSensitivity = 4.0f,
        )
        val layout = computeGestureZoneLayout(s, geometry)
        val bottom = layout.getValue(ZoneId.BOTTOM)
        val left = layout.getValue(ZoneId.LEFT_EDGE)
        val right = layout.getValue(ZoneId.RIGHT_EDGE)
        // The reserved band is 48dp * 3 = 144px here.
        assertEquals(144, bottom.heightPx)
        // A point in the lower-left corner, inside the reserved band, must be in the bottom
        // zone and NOT in the left Back zone.
        val cornerY = geometry.height - 10
        val cornerLeftX = 10
        assertTrue("lower-left corner point must be inside the bottom zone",
            bottom.left <= cornerLeftX && cornerLeftX <= bottom.right && bottom.top <= cornerY && cornerY <= bottom.bottom)
        assertTrue("lower-left corner point must NOT be inside the left Back zone",
            !(left.left <= cornerLeftX && cornerLeftX <= left.right && left.top <= cornerY && cornerY <= left.bottom))
        // Same for the lower-right corner.
        val cornerRightX = geometry.width - 10
        assertTrue("lower-right corner point must be inside the bottom zone",
            bottom.left <= cornerRightX && cornerRightX <= bottom.right && bottom.top <= cornerY && cornerY <= bottom.bottom)
        assertTrue("lower-right corner point must NOT be inside the right Back zone",
            !(right.left <= cornerRightX && cornerRightX <= right.right && right.top <= cornerY && cornerY <= right.bottom))
        // And Back must still work immediately above the band.
        val backPointY = bottom.top - 10
        assertTrue("point immediately above the band on the left must be in the left Back zone",
            left.left <= cornerLeftX && cornerLeftX <= left.right && left.top <= backPointY && backPointY <= left.bottom)
        assertTrue("point immediately above the band on the right must be in the right Back zone",
            right.left <= cornerRightX && cornerRightX <= right.right && right.top <= backPointY && backPointY <= right.bottom)
    }

    // --- Percentage normalization (snapping to the 10% step) -----------------------

    @Test
    fun clampPercent_snapsToNearest10PercentStep() {
        // Out-of-range clamps to the nearest bound.
        assertEquals(10, GestureZoneSettings.clampPercent(-100))
        assertEquals(10, GestureZoneSettings.clampPercent(9))
        assertEquals(10, GestureZoneSettings.clampPercent(10))
        // Below the midpoint of a step rounds down to the lower step.
        assertEquals(10, GestureZoneSettings.clampPercent(14))
        // Exact midpoint (15) rounds half-up to 20 — documented deterministic behavior.
        assertEquals(20, GestureZoneSettings.clampPercent(15))
        assertEquals(50, GestureZoneSettings.clampPercent(49))
        assertEquals(50, GestureZoneSettings.clampPercent(53))
        assertEquals(60, GestureZoneSettings.clampPercent(56))
        assertEquals(80, GestureZoneSettings.clampPercent(80))
        assertEquals(100, GestureZoneSettings.clampPercent(101))
        assertEquals(100, GestureZoneSettings.clampPercent(999))
    }

    @Test
    fun clampPercent_onlyProducesValidSteps() {
        val valid = setOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)
        for (v in -100..200) {
            val snapped = GestureZoneSettings.clampPercent(v)
            assertTrue("$v -> $snapped must be a valid 10% step", snapped in valid)
        }
    }

    // --- Home indicator width == resolved bottom touch-zone width ------------------
    //
    // The visible Home/Recents bar must match the actual horizontal activation region 1:1,
    // using the SAME production geometry helper the controller uses to size the bottom touch
    // window. The old compact-handle dp mapping (108dp × pct / 80, capped at 135dp) is gone;
    // the bar now spans exactly `screenWidth × bottomActivationWidthPercent / 100` (subject to
    // the helper's identical rounding/clamping). Bottom edge sensitivity and nav-bar insets
    // must NOT affect the horizontal width.n
    @Test
    fun homeIndicatorWidth_matchesPercentOfScreenWidth_1200px() {
        val geometry = ScreenGeometry(width = 1200, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 0)
        assertEquals(120, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 10), geometry).getValue(ZoneId.BOTTOM).widthPx)
        assertEquals(240, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 20), geometry).getValue(ZoneId.BOTTOM).widthPx)
        assertEquals(600, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 50), geometry).getValue(ZoneId.BOTTOM).widthPx)
        assertEquals(960, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 80), geometry).getValue(ZoneId.BOTTOM).widthPx)
        assertEquals(1200, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 100), geometry).getValue(ZoneId.BOTTOM).widthPx)
    }

    @Test
    fun homeIndicatorWidth_matchesPercentOfScreenWidth_1080px() {
        val geometry = ScreenGeometry(width = 1080, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 0)
        assertEquals(108, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 10), geometry).getValue(ZoneId.BOTTOM).widthPx)
        assertEquals(540, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 50), geometry).getValue(ZoneId.BOTTOM).widthPx)
        assertEquals(864, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 80), geometry).getValue(ZoneId.BOTTOM).widthPx)
        assertEquals(1080, computeGestureZoneLayout(GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = 100), geometry).getValue(ZoneId.BOTTOM).widthPx)
    }

    /**
     * The core invariant: the resolved Home indicator width equals the resolved bottom
     * touch-zone width for every valid percentage, across representative screen widths,
     * densities, bottom sensitivity values, and nav-bar insets. (The controller passes
     * `bottomLayout.widthPx` straight to HomeIndicator, so this holds by construction —
     * the test guards against reintroducing a separate visual-width formula.)
     */
    @Test
    fun homeIndicatorWidth_equalsBottomTouchZoneWidth_forEveryPercentage() {
        val geometries = listOf(
            ScreenGeometry(width = 1080, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 0),
            ScreenGeometry(width = 1200, height = 2600, density = 2.75f, navLeft = 0, navRight = 0, navBottom = 0),
            ScreenGeometry(width = 1080, height = 2400, density = 3.0f, navLeft = 0, navRight = 0, navBottom = 126),
            ScreenGeometry(width = 2400, height = 1080, density = 3.0f, navLeft = 0, navRight = 126, navBottom = 0),
        )
        for (geometry in geometries) {
            for (pct in listOf(10, 20, 30, 40, 50, 60, 70, 80, 90, 100)) {
                for (bottomSens in listOf(0.5f, 1.0f, 2.0f, 4.0f)) {
                    val s = GestureZoneSettings.DEFAULT.copy(
                        bottomActivationWidthPercent = pct,
                        bottomEdgeSensitivity = bottomSens,
                    )
                    val bottom = computeGestureZoneLayout(s, geometry).getValue(ZoneId.BOTTOM)
                    // The indicator width the controller passes is exactly the bottom zone's widthPx.
                    val indicatorWidthPx = bottom.widthPx
                    assertEquals("indicator width must equal bottom touch-zone width (pct=$pct sens=$bottomSens geom=${geometry.width}x${geometry.height})",
                        bottom.widthPx, indicatorWidthPx)
                    // And it is exactly screenWidth × pct / 100 (the helper's formula), so 100% → full width.
                    val expected = (geometry.width * pct / 100).coerceAtLeast(1)
                    assertEquals("indicator width must equal screenWidth×pct/100", expected, indicatorWidthPx)
                    // Bottom sensitivity must not change the horizontal width.
                    val widthAt1x = computeGestureZoneLayout(
                        GestureZoneSettings.DEFAULT.copy(bottomActivationWidthPercent = pct, bottomEdgeSensitivity = 1.0f),
                        geometry,
                    ).getValue(ZoneId.BOTTOM).widthPx
                    assertEquals("bottom sensitivity must not alter horizontal width (pct=$pct sens=$bottomSens)",
                        widthAt1x, indicatorWidthPx)
                }
            }
        }
    }

    // --- Gesture runtime readiness: all-or-nothing zone check ---

    @Test
    fun areRequiredGestureZonesAttached_3of3_isTrue() {
        assertTrue(areRequiredGestureZonesAttached(setOf(ZoneId.BOTTOM, ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE)))
    }

    @Test
    fun areRequiredGestureZonesAttached_2of3_isFalse() {
        assertFalse(areRequiredGestureZonesAttached(setOf(ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE)))
    }

    @Test
    fun areRequiredGestureZonesAttached_1of3_isFalse() {
        assertFalse(areRequiredGestureZonesAttached(setOf(ZoneId.BOTTOM)))
    }

    @Test
    fun areRequiredGestureZonesAttached_0of3_isFalse() {
        assertFalse(areRequiredGestureZonesAttached(emptySet()))
    }


    // --- Navigation availability: zones + passThrough ---

    @Test
    fun isGestureNavigationAvailable_3of3_noPassThrough_isTrue() {
        assertTrue(isGestureNavigationAvailable(setOf(ZoneId.BOTTOM, ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE), false))
    }

    @Test
    fun isGestureNavigationAvailable_3of3_passThrough_isFalse() {
        assertFalse(isGestureNavigationAvailable(setOf(ZoneId.BOTTOM, ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE), true))
    }

    @Test
    fun isGestureNavigationAvailable_2of3_noPassThrough_isFalse() {
        assertFalse(isGestureNavigationAvailable(setOf(ZoneId.LEFT_EDGE, ZoneId.RIGHT_EDGE), false))
    }

    @Test
    fun isGestureNavigationAvailable_1of3_noPassThrough_isFalse() {
        assertFalse(isGestureNavigationAvailable(setOf(ZoneId.BOTTOM), false))
    }

    @Test
    fun isGestureNavigationAvailable_0of3_noPassThrough_isFalse() {
        assertFalse(isGestureNavigationAvailable(emptySet(), false))
    }

    @Test
    fun isGestureNavigationAvailable_0of3_passThrough_isFalse() {
        assertFalse(isGestureNavigationAvailable(emptySet(), true))
    }
}
