package com.coin11.taojinbi.task

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnRecoveryPolicyTest {
    @Test
    fun fallsBackAfterFourBacksOnlyForMainlineWithUser() {
        assertFalse(
            ReturnRecoveryPolicy.shouldUseCoinUrlFallback(
                backCount = 3,
                coinMainlineMode = true,
                targetUserId = 999,
            ),
        )
        assertTrue(
            ReturnRecoveryPolicy.shouldUseCoinUrlFallback(
                backCount = 4,
                coinMainlineMode = true,
                targetUserId = 999,
            ),
        )
        assertFalse(
            ReturnRecoveryPolicy.shouldUseCoinUrlFallback(
                backCount = 5,
                coinMainlineMode = false,
                targetUserId = 999,
            ),
        )
    }
}
