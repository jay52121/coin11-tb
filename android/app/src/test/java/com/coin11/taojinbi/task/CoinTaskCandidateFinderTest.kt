package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CoinTaskCandidateFinderTest {

    @Test
    fun findsBrowseTask() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation(
                node(0, "浏览商品15秒", 100, 400, 700, 500),
                node(1, "去逛逛", 900, 410, 1200, 500),
            ),
            handledKeys = emptySet(),
        )

        assertEquals(CoinTaskKind.BROWSE, candidate?.kind)
    }

    @Test
    fun findsRewardTask() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation(
                node(0, "今日任务奖励", 100, 400, 700, 500),
                node(1, "领取奖励", 900, 410, 1200, 500),
            ),
            handledKeys = emptySet(),
        )

        assertEquals(CoinTaskKind.REWARD, candidate?.kind)
    }

    @Test
    fun skipsSpecialTask() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation(
                node(0, "淘金币趣味答题", 100, 400, 700, 500),
                node(1, "去完成", 900, 410, 1200, 500),
            ),
            handledKeys = emptySet(),
        )

        assertNull(candidate)
    }

    @Test
    fun doesNotRepeatHandledCandidate() {
        val observation = observation(
            node(0, "浏览商品15秒", 100, 400, 700, 500),
            node(1, "去逛逛", 900, 410, 1200, 500),
        )
        val first = CoinTaskCandidateFinder.findNext(
            observation,
            handledKeys = emptySet(),
        )

        val second = CoinTaskCandidateFinder.findNext(
            observation,
            handledKeys = setOf(first!!.key),
        )

        assertNull(second)
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
