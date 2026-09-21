package com.coin11.taojinbi.observation

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong

class ObservationCollector(
    private val maxNodes: Int = 1800,
) {
    private val nextId = AtomicLong(1)

    private data class PendingNode(
        val node: AccessibilityNodeInfo,
        val depth: Int,
    )

    fun collect(root: AccessibilityNodeInfo): Observation {
        val queue = ArrayDeque<PendingNode>()
        val nodes = ArrayList<NodeSnapshot>()
        queue.add(PendingNode(root, 0))

        var truncated = false

        while (queue.isNotEmpty()) {
            if (nodes.size >= maxNodes) {
                truncated = true
                break
            }

            val current = queue.removeFirst()
            val node = current.node
            val rect = Rect()
            node.getBoundsInScreen(rect)

            nodes += NodeSnapshot(
                index = nodes.size,
                depth = current.depth,
                text = node.text?.toString()?.takeIf { it.isNotBlank() },
                contentDescription = node.contentDescription?.toString()?.takeIf { it.isNotBlank() },
                viewId = node.viewIdResourceName?.takeIf { it.isNotBlank() },
                className = node.className?.toString()?.takeIf { it.isNotBlank() },
                bounds = IntRect(rect.left, rect.top, rect.right, rect.bottom),
                clickable = node.isClickable,
                scrollable = node.isScrollable,
                enabled = node.isEnabled,
                selected = node.isSelected,
                checked = node.isChecked,
            )

            for (childIndex in 0 until node.childCount) {
                val child = node.getChild(childIndex) ?: continue
                queue.addLast(PendingNode(child, current.depth + 1))
            }
        }

        return Observation(
            id = nextId.getAndIncrement(),
            capturedAtMillis = System.currentTimeMillis(),
            packageName = root.packageName?.toString(),
            windowId = root.windowId,
            nodes = nodes,
            truncated = truncated,
        )
    }
}
