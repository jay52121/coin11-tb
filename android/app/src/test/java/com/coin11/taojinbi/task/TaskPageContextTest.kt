package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import com.coin11.taojinbi.recognizer.PageType
import org.junit.Assert.assertEquals
import org.junit.Test

class TaskPageContextTest {

    @Test
    fun waitingEntryNeedsBrowseSignalToReinterpretHome() {
        assertEquals(
            PageType.TAOBAO_BROWSE_TASK,
            TaskPageContext.effectiveBrowsePageType(
                rawPageType = PageType.TAOBAO_HOME,
                observation = observation("浏览5秒"),
                phase = BrowseContextPhase.WAITING_ENTRY,
            ),
        )

        assertEquals(
            PageType.TAOBAO_HOME,
            TaskPageContext.effectiveBrowsePageType(
                rawPageType = PageType.TAOBAO_HOME,
                observation = observation("搜索栏", "淘宝农场"),
                phase = BrowseContextPhase.WAITING_ENTRY,
            ),
        )
    }

    @Test
    fun confirmedBrowseContextOwnsHomeLikeRawPage() {
        assertEquals(
            PageType.TAOBAO_BROWSE_TASK,
            TaskPageContext.effectiveBrowsePageType(
                rawPageType = PageType.TAOBAO_HOME,
                observation = observation("搜索栏", "淘宝农场"),
                phase = BrowseContextPhase.ACTIVE,
            ),
        )
    }

    @Test
    fun doesNotOverrideTerminalTaskList() {
        assertEquals(
            PageType.DAILY_TASK_LIST,
            TaskPageContext.effectiveBrowsePageType(
                rawPageType = PageType.DAILY_TASK_LIST,
                observation = observation("浏览15秒"),
                phase = BrowseContextPhase.ACTIVE,
            ),
        )
    }

    private fun observation(vararg texts: String) = Observation(
        id = 1L,
        capturedAtMillis = 1L,
        packageName = "com.taobao.taobao",
        windowId = 1,
        nodes = texts.mapIndexed { index, text ->
            NodeSnapshot(
                index = index,
                depth = 1,
                text = text,
                contentDescription = null,
                viewId = null,
                className = "android.widget.TextView",
                bounds = IntRect(0, index * 100, 500, index * 100 + 80),
                clickable = false,
                scrollable = false,
                enabled = true,
                selected = false,
                checked = false,
            )
        },
        truncated = false,
    )
}
