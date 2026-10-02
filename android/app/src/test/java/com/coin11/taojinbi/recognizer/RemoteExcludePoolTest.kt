package com.coin11.taojinbi.recognizer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RemoteExcludePoolTest {

    @Test
    fun cloudPoolOverridesOnlyExclusionFields() {
        val base = RuleSet.DEFAULT.copy(
            doneWords = listOf("keep-me"),
            coinExcludeTags = listOf("old"),
            skipTaskExtraWords = listOf("old-extra"),
        )

        val result = RemoteExcludePool.overlay(
            base = base,
            schemaVersion = 1,
            coinExcludeTags = listOf("下单", "百度", "百度"),
            skipTaskExtraWords = listOf("抢红包", "趣头条"),
        )

        assertEquals(listOf("下单", "百度"), result.coinExcludeTags)
        assertEquals(listOf("抢红包", "趣头条"), result.skipTaskExtraWords)
        assertEquals(listOf("keep-me"), result.doneWords)
    }

    @Test
    fun rejectsWrongSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteExcludePool.overlay(
                base = RuleSet.DEFAULT,
                schemaVersion = 2,
                coinExcludeTags = emptyList(),
                skipTaskExtraWords = emptyList(),
            )
        }
    }
}
