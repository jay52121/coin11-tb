package com.coin11.taojinbi.task

object ReturnRecoveryPolicy {
    const val BACKS_BEFORE_COIN_URL_FALLBACK = 4

    fun shouldUseCoinUrlFallback(
        backCount: Int,
        coinMainlineMode: Boolean,
        targetUserId: Int,
    ): Boolean =
        coinMainlineMode &&
            targetUserId >= 0 &&
            backCount >= BACKS_BEFORE_COIN_URL_FALLBACK
}
