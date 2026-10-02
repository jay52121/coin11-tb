package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import com.coin11.taojinbi.ocr.OcrLine
import com.coin11.taojinbi.ocr.OcrSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoinTaskCandidateFinderTest {

    private val policy = CoinTaskPolicy(
        actionTextPattern = "去完成|去逛逛|点击去逛|领取奖励",
        rewardButtonPattern = "领取奖励",
        excludeWords = listOf("下单", "快手", "评价", "助力", "头条"),
        doneWords = listOf("已完成", "已领取", "已得"),
        doneExcludeWords = listOf("累计已得", "累积已得"),
    )

    @Test
    fun genericActionUsesMacProgressKeyAndClickLimit() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "看看美瞳日抛混装(1/5)", 100, 400, 760, 500),
                node(1, "去完成", 900, 410, 1200, 500),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertNotNull(candidate)
        assertEquals("看看美瞳日抛混装(1/5)", candidate!!.key)
        assertEquals(6, candidate.clickLimit)
    }

    @Test
    fun progressDoneSkipsCandidate() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "看看美瞳日抛混装(5/5)", 100, 400, 760, 500),
                node(1, "去完成", 900, 410, 1200, 500),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertNull(candidate)
    }

    @Test
    fun clickCountAllowsRetryUntilMacLimit() {
        val observation = observation(
            node(0, "浏览商品(0/1)", 100, 400, 760, 500),
            node(1, "去逛逛", 900, 410, 1200, 500),
        )

        assertNotNull(
            CoinTaskCandidateFinder.findNext(
                observation,
                clickCounts = mapOf("浏览商品(0/1)" to 1),
                invalidClickKeys = emptySet(),
                policy = policy,
            ),
        )
        assertNull(
            CoinTaskCandidateFinder.findNext(
                observation,
                clickCounts = mapOf("浏览商品(0/1)" to 2),
                invalidClickKeys = emptySet(),
                policy = policy,
            ),
        )
    }

    @Test
    fun invalidClickKeySkipsOnlyThatTarget() {
        val observation = observation(
            node(0, "浏览商品(0/1)", 100, 400, 760, 500),
            node(1, "去逛逛", 900, 410, 1200, 500),
        )
        val first = CoinTaskCandidateFinder.findNext(
            observation,
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )!!

        assertNull(
            CoinTaskCandidateFinder.findNext(
                observation,
                clickCounts = emptyMap(),
                invalidClickKeys = setOf(first.clickKey),
                policy = policy,
            ),
        )
    }

    @Test
    fun skipsBaiduTaskByConfiguredExcludeWord() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "去百度App领现金(0/1)", 100, 400, 760, 500),
                node(1, "去完成", 900, 410, 1200, 500),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy.copy(
                excludeWords = policy.excludeWords + "百度",
            ),
        )

        assertNull(candidate)
    }

    @Test
    fun skipsToutiaoBeforeClick() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "头条刷热点领现金(0/1)", 100, 400, 760, 500),
                node(1, "点击去逛", 900, 410, 1200, 500),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertNull(candidate)
    }

    @Test
    fun explicitBrowseStepCanIgnoreOrderExcludeWord() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "下单频道 浏览5秒", 100, 400, 760, 500),
                node(1, "点击去逛", 900, 410, 1200, 500),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertNotNull(candidate)
    }

    @Test
    fun rewardUsesTwoClickLimit() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "今日任务奖励", 100, 400, 760, 500),
                node(1, "领取奖励", 900, 410, 1200, 500),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertEquals(CoinTaskKind.REWARD, candidate?.kind)
        assertEquals(2, candidate?.clickLimit)
    }

    @Test
    fun ocrCandidateUsesSamePolicy() {
        val snapshot = ocrSnapshot(
            ocr("浏览商品(0/1)", 100, 400, 760, 500),
            ocr("去完成", 900, 410, 1200, 500),
        )

        val candidate = CoinTaskCandidateFinder.findNextFromOcr(
            snapshot = snapshot,
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertNotNull(candidate)
        assertEquals("浏览商品(0/1)", candidate!!.key)
        assertEquals("ocr", candidate.source)
    }

    @Test
    fun bottomAndExpandHelpersMatchMacMarkers() {
        val observation = observation(
            node(0, "收起更多任务", 100, 1000, 600, 1080),
            node(1, "展开", 900, 1200, 1100, 1280),
        )
        assertTrue(
            CoinTaskCandidateFinder.isTaskListAtBottom(
                observation,
                listOf("收起更多任务"),
            ),
        )
        assertNotNull(
            CoinTaskCandidateFinder.findExpandEntry(
                observation,
                listOf("展开"),
            ),
        )
    }

    @Test
    fun findsNextTaskHopAndSearchDiscoveryTarget() {
        val next = observation(
            node(0, "下个任务", 20, 700, 180, 780),
        )
        assertNotNull(
            CoinTaskCandidateFinder.findNextTaskHop(
                next,
                listOf("下个任务", "下一任务"),
                1256,
            ),
        )

        val search = observation(
            node(0, "搜索发现", 100, 500, 500, 580),
            node(1, "无线耳机", 100, 620, 600, 700),
        )
        assertNotNull(
            CoinTaskCandidateFinder.findSearchDiscoveryTarget(
                search,
                listOf("搜索发现"),
            ),
        )
    }

    @Test
    fun rejectsTopClippedActionCandidate() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "搜一搜你心仪的宝贝(0/5)", 100, 0, 760, 62),
                node(1, "去完成", 993, 0, 1204, 62),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertNull(candidate)
    }

    @Test
    fun usesSmallestClickableContainerForActionText() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, "浏览商品(0/1)", 100, 400, 760, 500),
                node(1, null, 860, 390, 1220, 520, clickable = true),
                node(2, "去完成", 920, 410, 1160, 500),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertEquals(IntRect(860, 390, 1220, 520), candidate?.bounds)
    }

    @Test
    fun clickableRowFallbackMatchesMacRowCandidate() {
        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation(
                node(0, null, 60, 360, 1210, 620, clickable = true),
                node(1, "浏览会场(0/1)", 100, 400, 700, 490),
                node(2, "+10", 730, 500, 820, 560),
            ),
            clickCounts = emptyMap(),
            invalidClickKeys = emptySet(),
            policy = policy,
        )

        assertNotNull(candidate)
        assertEquals("row", candidate!!.source)
        assertEquals(IntRect(60, 360, 1210, 620), candidate.bounds)
    }

    @Test
    fun expandUsesClickableContainerAndNextTaskUsesLeftEdgeTap() {
        val expand = observation(
            node(0, null, 780, 1100, 1180, 1320, clickable = true),
            node(1, "展开", 900, 1160, 1080, 1230),
        )
        assertEquals(
            IntRect(780, 1100, 1180, 1320),
            CoinTaskCandidateFinder.findExpandEntry(
                expand,
                listOf("展开"),
            )?.bounds,
        )

        val next = observation(
            node(0, "下个任务", 120, 700, 300, 800),
        )
        val tap = CoinTaskCandidateFinder.findNextTaskHop(
            next,
            listOf("下个任务"),
            1256,
        )
        assertNotNull(tap)
        assertTrue(tap!!.right <= 56)
    }

    private fun observation(vararg nodes: NodeSnapshot) = Observation(
        id = 1L,
        capturedAtMillis = 1L,
        packageName = "com.taobao.taobao",
        windowId = 1,
        nodes = nodes.toList(),
        truncated = false,
    )

    private fun ocrSnapshot(vararg lines: OcrLine) = OcrSnapshot(
        observationId = 1L,
        capturedAtMillis = 1L,
        screenshotWidth = 1256,
        screenshotHeight = 2760,
        screenshotElapsedMillis = 10,
        recognitionElapsedMillis = 20,
        lines = lines.toList(),
    )

    private fun ocr(
        text: String,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
    ) = OcrLine(text, IntRect(left, top, right, bottom))

    private fun node(
        index: Int,
        text: String?,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        clickable: Boolean = false,
    ) = NodeSnapshot(
        index = index,
        depth = 1,
        text = text,
        contentDescription = null,
        viewId = null,
        className = "android.widget.TextView",
        bounds = IntRect(left, top, right, bottom),
        clickable = clickable,
        scrollable = false,
        enabled = true,
        selected = false,
        checked = false,
    )
}
