package com.coin11.taojinbi.observation

data class IntRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    override fun toString(): String = "[$left,$top][$right,$bottom]"
}

data class NodeSnapshot(
    val index: Int,
    val depth: Int,
    val text: String?,
    val contentDescription: String?,
    val viewId: String?,
    val className: String?,
    val bounds: IntRect,
    val clickable: Boolean,
    val scrollable: Boolean,
    val enabled: Boolean,
    val selected: Boolean,
    val checked: Boolean,
)

data class Observation(
    val id: Long,
    val capturedAtMillis: Long,
    val packageName: String?,
    val windowId: Int?,
    val nodes: List<NodeSnapshot>,
    val truncated: Boolean,
) {
    val interestingNodeCount: Int
        get() = nodes.count {
            !it.text.isNullOrBlank() ||
                !it.contentDescription.isNullOrBlank() ||
                !it.viewId.isNullOrBlank()
        }
}
