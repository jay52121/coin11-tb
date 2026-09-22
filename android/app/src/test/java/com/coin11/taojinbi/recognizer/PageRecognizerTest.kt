package com.coin11.taojinbi.recognizer

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PageRecognizerTest {

    private val recognizer = PageRecognizer(RuleSet.DEFAULT)

    @Test
    fun externalPackageWinsImmediately() {
        assertPage(
            PageType.EXTERNAL_APP,
            observation("com.android.chrome", "淘宝", "浏览"),
        )
    }

    @Test
    fun recognizesEnergyTaskList() {
        assertPage(
            PageType.ENERGY_TASK_LIST,
            observation("com.taobao.taobao", "做任务赚体力", "赚体力", "去完成"),
        )
    }

    @Test
    fun recognizesGoodShopBeforeGenericBrowseSignals() {
        assertPage(
            PageType.GOOD_SHOP_PAGE,
            observation("com.taobao.taobao", "今日推荐8家好店", "浏览"),
        )
    }

    @Test
    fun recognizesTaskDoneBeforeGenericBrowse() {
        assertPage(
            PageType.TASK_DONE,
            observation("com.taobao.taobao", "任务已完成", "继续逛逛吧"),
        )
    }

    @Test
    fun cumulativeRewardIsNotTaskDone() {
        val result = recognizer.recognize(
            observation("com.taobao.taobao", "累计已得", "热销"),
        )
        assertNotEquals(PageType.TASK_DONE, result.pageType)
    }

    @Test
    fun recognizesQuiz() {
        assertPage(
            PageType.QUIZ,
            observation("com.taobao.taobao", "淘金币趣味答题", "我选好了"),
        )
    }

    @Test
    fun taskListWinsOverCoinHomeSignals() {
        assertPage(
            PageType.DAILY_TASK_LIST,
            observation(
                "com.taobao.taobao",
                "赚金币抵钱",
                "今日累计奖励",
                "完成进度",
                "去完成",
            ),
        )
    }

    @Test
    fun recognizesCoinHome() {
        assertPage(
            PageType.COIN_HOME,
            observation("com.taobao.taobao", "淘金币首页", "可抵", "购物车"),
        )
    }

    @Test
    fun recognizesTaobaoHome() {
        assertPage(
            PageType.TAOBAO_HOME,
            observation("com.taobao.taobao", "搜索栏", "推荐", "淘宝农场"),
        )
    }

    @Test
    fun activityHintCanRecognizeBrowseTask() {
        val result = recognizer.recognize(
            observation("com.taobao.taobao", "商品详情"),
            activityHint = "com.taobao.android.detail.wrapper.activity.NewDetailActivity",
        )
        assertEquals(PageType.TAOBAO_BROWSE_TASK, result.pageType)
    }

    @Test
    fun recognizesSubscribeTask() {
        assertPage(
            PageType.SHOP_SUBSCRIBE_TASK,
            observation("com.taobao.taobao", "订阅 + 5", "立即领"),
        )
    }

    @Test
    fun unknownTaobaoPageStaysUnknown() {
        assertPage(
            PageType.UNKNOWN_TAOBAO_PAGE,
            observation("com.taobao.taobao", "一个从未见过的页面"),
        )
    }

    private fun assertPage(
        expected: PageType,
        observation: Observation,
    ) {
        assertEquals(expected, recognizer.recognize(observation).pageType)
    }

    private fun observation(
        packageName: String,
        vararg texts: String,
    ): Observation {
        val nodes = texts.mapIndexed { index, text ->
            NodeSnapshot(
                index = index,
                depth = 1,
                text = text,
                contentDescription = null,
                viewId = null,
                className = "android.view.View",
                bounds = IntRect(0, index * 100, 1000, index * 100 + 80),
                clickable = false,
                scrollable = false,
                enabled = true,
                selected = false,
                checked = false,
            )
        }

        return Observation(
            id = 1,
            capturedAtMillis = 1,
            packageName = packageName,
            windowId = 1,
            nodes = nodes,
            truncated = false,
        )
    }
}
