package com.coin11.taojinbi.accessibility

import java.util.concurrent.atomic.AtomicLong

internal class RunGenerationGate {
    private val generation = AtomicLong(0L)

    fun begin(): Long = generation.incrementAndGet()

    fun invalidate(): Long = generation.incrementAndGet()

    fun current(): Long = generation.get()

    fun isCurrent(token: Long): Boolean =
        generation.get() == token
}
