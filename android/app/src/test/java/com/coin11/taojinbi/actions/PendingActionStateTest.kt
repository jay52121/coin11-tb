package com.coin11.taojinbi.actions

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PendingActionStateTest {

    @After
    fun tearDown() {
        PendingActionState.clear()
    }

    @Test
    fun keepsRequestUntilPredicateMatchesThenConsumesOnce() {
        val request = PendingActionState.arm(
            type = PendingActionType.SWIPE_UP,
            targetPackage = "com.taobao.taobao",
            nowMillis = 100L,
            ttlMillis = 1_000L,
        )

        assertNull(
            PendingActionState.consumeIf(nowMillis = 200L) {
                it.targetPackage == "other"
            },
        )

        val consumed = PendingActionState.consumeIf(nowMillis = 300L) {
            it.targetPackage == "com.taobao.taobao"
        }

        assertEquals(request.id, consumed?.id)
        assertEquals(PendingActionType.SWIPE_UP, consumed?.type)
        assertNull(PendingActionState.consumeIf(nowMillis = 400L) { true })
    }

    @Test
    fun expiredRequestDoesNotExecuteLater() {
        PendingActionState.arm(
            type = PendingActionType.TAP_CENTER,
            targetPackage = "com.taobao.taobao",
            nowMillis = 100L,
            ttlMillis = 50L,
        )

        assertNull(PendingActionState.consumeIf(nowMillis = 151L) { true })
        assertTrue(PendingActionState.describe(nowMillis = 151L) in setOf("(none)", "(expired)"))
    }
}
