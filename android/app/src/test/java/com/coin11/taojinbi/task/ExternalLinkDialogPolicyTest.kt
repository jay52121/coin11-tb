package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExternalLinkDialogPolicyTest {
    @Test
    fun findsCancelOnTaobaoBrowserOpenDialog() {
        val observation = observation(
            "com.taobao.taobao",
            node(0, "activity.baidu.com", 100, 800, 900, 900),
            node(1, "取消", 120, 1100, 480, 1220),
            node(2, "浏览器打开", 600, 1100, 1100, 1220),
        )

        assertEquals(
            IntRect(120, 1100, 480, 1220),
            ExternalLinkDialogPolicy.findCancelBounds(observation),
        )
    }

    @Test
    fun ignoresOrdinaryPageAndOtherPackages() {
        assertNull(
            ExternalLinkDialogPolicy.findCancelBounds(
                observation(
                    "com.taobao.taobao",
                    node(0, "取消", 120, 1100, 480, 1220),
                ),
            ),
        )
        assertNull(
            ExternalLinkDialogPolicy.findCancelBounds(
                observation(
                    "com.android.chrome",
                    node(0, "取消", 120, 1100, 480, 1220),
                    node(1, "浏览器打开", 600, 1100, 1100, 1220),
                ),
            ),
        )
    }

    private fun observation(
        packageName: String,
        vararg nodes: NodeSnapshot,
    ) = Observation(
        id = 1L,
        capturedAtMillis = 1L,
        packageName = packageName,
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
