package com.coin11.taojinbi.accessibility

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RunGenerationGateTest {

    @Test
    fun newRunInvalidatesCallbacksFromPreviousRun() {
        val gate = RunGenerationGate()
        val first = gate.begin()

        assertTrue(gate.isCurrent(first))

        val second = gate.begin()

        assertFalse(gate.isCurrent(first))
        assertTrue(gate.isCurrent(second))
    }

    @Test
    fun manualStopInvalidatesCurrentRun() {
        val gate = RunGenerationGate()
        val running = gate.begin()

        gate.invalidate()

        assertFalse(gate.isCurrent(running))
    }
}
