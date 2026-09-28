package com.coin11.taojinbi.task

import kotlin.random.Random
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class BrowsePacingTest {

    @Test
    fun swipeIntervalHasModerateLowerBound() {
        assertTrue(BrowsePacing.SWIPE_INTERVAL_MIN_MS >= 1_500L)
        assertTrue(
            BrowsePacing.FIRST_SWIPE_MIN_MS >=
                BrowsePacing.SWIPE_INTERVAL_MIN_MS,
        )
    }

    @Test
    fun thirtySecondBrowseHasBoundedSwipeCount() {
        assertEquals(
            19,
            BrowsePacing.theoreticalMaxSwipes(30_000L),
        )
    }

    @Test
    fun ocrOrPageTransitionSuppressesSwipe() {
        assertFalse(
            BrowsePacing.shouldSwipe(
                nowMillis = 5_000L,
                nextSwipeAtMillis = 4_000L,
                ocrInFlight = true,
                pageTransitionPending = false,
            ),
        )
        assertFalse(
            BrowsePacing.shouldSwipe(
                nowMillis = 5_000L,
                nextSwipeAtMillis = 4_000L,
                ocrInFlight = false,
                pageTransitionPending = true,
            ),
        )
        assertTrue(
            BrowsePacing.shouldSwipe(
                nowMillis = 5_000L,
                nextSwipeAtMillis = 4_000L,
                ocrInFlight = false,
                pageTransitionPending = false,
            ),
        )
    }

    @Test
    fun swipePlanUsesShortSlowVerticalMovement() {
        val height = 2_760
        repeat(200) { seed ->
            val plan = BrowsePacing.createSwipePlan(
                screenWidth = 1_256,
                screenHeight = height,
                random = Random(seed),
            )
            val travel = plan.startY - plan.endY
            assertTrue(travel >= (height * BrowsePacing.TRAVEL_MIN_RATIO).toInt())
            assertTrue(travel < (height * BrowsePacing.TRAVEL_MAX_RATIO).toInt())
            assertTrue(plan.durationMs >= BrowsePacing.SWIPE_DURATION_MIN_MS)
            assertTrue(plan.durationMs <= BrowsePacing.SWIPE_DURATION_MAX_MS)
        }
    }
}
