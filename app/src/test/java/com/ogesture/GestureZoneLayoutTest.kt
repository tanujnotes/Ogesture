package com.ogesture

import com.ogesture.data.GESTURE_ZONES
import com.ogesture.data.GestureAction
import com.ogesture.data.SwipeDirection
import com.ogesture.data.ZoneConfig
import com.ogesture.data.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression guards for the gesture-zone layout and the action/direction mapping that the
 * accessibility-overlay migration must preserve. These cover the pure data model that
 * drives both the touch-zone windows and the indicator feedback, so a silent change to the
 * layout or the action wiring would fail here.
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
}
