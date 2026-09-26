package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BrowseTaskCandidateFinderTest {

    @Test
    fun prefersExplicitBrowseAction() {
        val observation = observation(
            node(0, "浏览商品15秒", 100, 400, 700, 500),
            node(1, "去逛逛", 900, 410, 1200, 500),
            node(2, "去完成", 900, 700, 1200, 790),
        )

        val candidate = BrowseTaskCandidateFinder.findBrowseTask(observation)

        assertNotNull(candidate)
        assertEquals("去逛逛", candidate?.buttonText)
    }

    @Test
    fun acceptsGoCompleteOnlyWithBrowseContext() {
        val observation = observation(
            node(0, "浏览精选商品15秒", 100, 400, 780, 500),
            node(1, "去完成", 900, 410, 1200, 500),
        )

        val candidate = BrowseTaskCandidateFinder.findBrowseTask(observation)

        assertNotNull(candidate)
        assertEquals("去完成", candidate?.buttonText)
    }

    @Test
    fun rejectsUnsafeGoCompleteContext() {
        val observation = observation(
            node(0, "趣味答题赢金币", 100, 400, 780, 500),
            node(1, "去完成", 900, 410, 1200, 500),
        )

        assertNull(BrowseTaskCandidateFinder.findBrowseTask(observation))
    }

    @Test
    fun findsCoinTaskEntry() {
        val observation = observation(
            node(0, "淘金币", 100, 100, 500, 200),
            node(1, "2分钟快速赚", 700, 300, 1200, 420),
        )

        assertEquals(
            "2分钟快速赚",
            BrowseTaskCandidateFinder.findCoinTaskEntry(observation)?.text,
        )
    }

    private fun observation(vararg nodes: NodeSnapshot) = Observation(
        id = 1L,
        capturedAtMillis = 1L,
        packageName = "com.taobao.taobao",
        windowId = 1,
        nodes = nodes.toList(),
        truncated = false,
    )

    private fun node(
        index: Int,
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = NodeSnapshot(
        index = index,
        depth = 1,
        text = text,
        contentDescription = null,
        viewId = null,
        className = "android.widget.TextView",
        bounds = IntRect(left, top, right, bottom),
        clickable = false,
        scrollable = false,
        enabled = true,
        selected = false,
        checked = false,
    )
}
