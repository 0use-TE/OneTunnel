package dev.dsh.onetunnel

import org.junit.Assert.*
import org.junit.Test

class RuleDragGeometryTest {
    @Test fun choosesRowsWithDifferentHeightsAndScrolledOffsets() {
        val slots = listOf(RuleDragSlot(8, -25, 70), RuleDragSlot(9, 51, 120), RuleDragSlot(10, 177, 64))
        assertEquals(8, RuleDragGeometry.closestIndex(0f, slots))
        assertEquals(9, RuleDragGeometry.closestIndex(110f, slots))
        assertEquals(10, RuleDragGeometry.closestIndex(220f, slots))
        assertEquals(10, RuleDragGeometry.closestIndex(500f, slots))
        assertEquals(8, RuleDragGeometry.closestIndex(-200f, slots))
    }
    @Test fun spacingBetweenRowsAndEmptyViewportAreHandled() {
        val slots = listOf(RuleDragSlot(0, 0, 64), RuleDragSlot(1, 70, 64))
        assertEquals(0, RuleDragGeometry.closestIndex(65f, slots))
        assertEquals(1, RuleDragGeometry.closestIndex(69f, slots))
        assertNull(RuleDragGeometry.closestIndex(10f, emptyList()))
        assertNull(RuleDragGeometry.closestIndex(Float.NaN, slots))
    }
    @Test fun edgeScrollWorksWithStationaryPointerAndIsSpeedLimited() {
        assertEquals(-700f, RuleDragGeometry.scrollVelocity(-100f, 0, 400, 56f, 700f), .01f)
        assertEquals(-350f, RuleDragGeometry.scrollVelocity(28f, 0, 400, 56f, 700f), .01f)
        assertEquals(0f, RuleDragGeometry.scrollVelocity(200f, 0, 400, 56f, 700f), .01f)
        assertEquals(350f, RuleDragGeometry.scrollVelocity(372f, 0, 400, 56f, 700f), .01f)
        assertEquals(700f, RuleDragGeometry.scrollVelocity(500f, 0, 400, 56f, 700f), .01f)
        assertEquals(0f, RuleDragGeometry.scrollVelocity(400f, 0, 400, 56f, 0f), .01f)
    }
    @Test fun smallViewportsDoNotOverlapEdgeZones() {
        assertEquals(0f, RuleDragGeometry.scrollVelocity(20f, 0, 40, 56f, 700f), .01f)
        assertTrue(RuleDragGeometry.scrollVelocity(10f, 0, 40, 56f, 700f) < 0)
        assertTrue(RuleDragGeometry.scrollVelocity(30f, 0, 40, 56f, 700f) > 0)
        assertEquals(0f, RuleDragGeometry.scrollVelocity(Float.NaN, 0, 400, 56f, 700f), .01f)
    }
}
