package com.coin11.taojinbi.recognizer

import org.json.JSONObject
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

        val result = RemoteExcludePool.overlayFromJson(
            base,
            JSONObject(
                """
                {
                  "schema_version": 1,
                  "revision": "test",
                  "coin_exclude_tags": ["下单", "百度", "百度"],
                  "skip_task_extra_words": ["抢红包", "趣头条"]
                }
                """.trimIndent(),
            ),
        )

        assertEquals(listOf("下单", "百度"), result.coinExcludeTags)
        assertEquals(listOf("抢红包", "趣头条"), result.skipTaskExtraWords)
        assertEquals(listOf("keep-me"), result.doneWords)
    }

    @Test
    fun rejectsWrongSchema() {
        assertThrows(IllegalArgumentException::class.java) {
            RemoteExcludePool.overlayFromJson(
                RuleSet.DEFAULT,
                JSONObject(
                    """
                    {
                      "schema_version": 2,
                      "coin_exclude_tags": [],
                      "skip_task_extra_words": []
                    }
                    """.trimIndent(),
                ),
            )
        }
    }
}
