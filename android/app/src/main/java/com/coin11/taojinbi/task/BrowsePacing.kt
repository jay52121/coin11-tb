package com.coin11.taojinbi.task

import kotlin.random.Random

data class BrowseSwipePlan(
    val startX: Int,
    val startY: Int,
    val endX: Int,
    val endY: Int,
    val durationMs: Long,
)

object BrowsePacing {

    const val FIRST_SWIPE_MIN_MS = 2_500L
    const val FIRST_SWIPE_MAX_MS = 3_000L
    const val SWIPE_INTERVAL_MIN_MS = 1_500L
    const val SWIPE_INTERVAL_MAX_MS = 2_500L
    const val SWIPE_DURATION_MIN_MS = 600L
    const val SWIPE_DURATION_MAX_MS = 900L
    const val TRAVEL_MIN_RATIO = 0.10f
    const val TRAVEL_MAX_RATIO = 0.16f

    fun nextFirstSwipeDelayMs(random: Random = Random.Default): Long =
        random.nextLong(FIRST_SWIPE_MIN_MS, FIRST_SWIPE_MAX_MS + 1)

    fun nextSwipeDelayMs(random: Random = Random.Default): Long =
        random.nextLong(SWIPE_INTERVAL_MIN_MS, SWIPE_INTERVAL_MAX_MS + 1)

    fun shouldSwipe(
        nowMillis: Long,
        nextSwipeAtMillis: Long,
        ocrInFlight: Boolean,
        pageTransitionPending: Boolean,
    ): Boolean =
        nowMillis >= nextSwipeAtMillis &&
            !ocrInFlight &&
            !pageTransitionPending

    fun theoreticalMaxSwipes(durationMs: Long): Int {
        if (durationMs < FIRST_SWIPE_MIN_MS) {
            return 0
        }
        return 1 +
            ((durationMs - FIRST_SWIPE_MIN_MS) / SWIPE_INTERVAL_MIN_MS)
                .toInt()
    }

    fun createSwipePlan(
        screenWidth: Int,
        screenHeight: Int,
        random: Random = Random.Default,
    ): BrowseSwipePlan {
        val width = screenWidth.coerceAtLeast(2)
        val height = screenHeight.coerceAtLeast(2)

        val startXMin = (width * 0.30f).toInt().coerceIn(1, width - 1)
        val startXMax = (width * 0.50f).toInt()
            .coerceIn(startXMin + 1, width)
        val startX = random.nextInt(startXMin, startXMax)

        val startYMin = (height * 0.62f).toInt().coerceIn(1, height - 1)
        val startYMax = (height * 0.72f).toInt()
            .coerceIn(startYMin + 1, height)
        val startY = random.nextInt(startYMin, startYMax)

        val travelMin = (height * TRAVEL_MIN_RATIO).toInt().coerceAtLeast(1)
        val travelMax = (height * TRAVEL_MAX_RATIO).toInt()
            .coerceAtLeast(travelMin + 1)
        val travel = random.nextInt(travelMin, travelMax)
        val endY = (startY - travel).coerceAtLeast(1)

        val drift = random.nextInt(-24, 25)
        val endX = (startX + drift).coerceIn(1, width - 1)

        val durationMs =
            random.nextLong(SWIPE_DURATION_MIN_MS, SWIPE_DURATION_MAX_MS + 1)

        return BrowseSwipePlan(
            startX = startX,
            startY = startY,
            endX = endX,
            endY = endY,
            durationMs = durationMs,
        )
    }
}
