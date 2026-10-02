package com.coin11.taojinbi.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OverlayGesturePolicyTest {
    @Test
    fun finalUpDisplacementCanClassifyDragWithoutMoveEvent() {
        assertTrue(OverlayGesturePolicy.isDrag(180, 0, 12))
        assertFalse(OverlayGesturePolicy.isDrag(4, 5, 12))
    }
}
