package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import kotlin.math.abs

data class BrowseTaskCandidate(
    val bounds: IntRect,
    val buttonText: String,
    val contextText: String,
)

object BrowseTaskCandidateFinder {

    private val directBrowseActions = listOf(
        "去逛逛",
        "去浏览",
        "逛一逛",
        "去看看",
        "逛一下",
        "点击去逛",
    )

    private val browseContextWords = listOf(
        "浏览",
        "逛",
        "清单",
        "商品",
        "店铺",
        "会场",
        "频道",
    )

    private val excludedContextWords = listOf(
        "下单",
        "快手",
        "评价",
        "助力",
        "答题",
        "趣味",
        "订阅",
        "关注",
        "游戏",
        "捐",
    )

    private val signCoinWords = listOf(
        "签到领金币",
    )

    fun findCoinTaskEntry(observation: Observation): NodeSnapshot? =
        observation.nodes
            .asSequence()
            .filter { it.enabled && hasUsableBounds(it.bounds) }
            .mapNotNull { node ->
                val text = nodeText(node).replace(" ", "")
                val rank = when {
                    text.contains("赚更多金币") -> 0
                    text == "赚金币" -> 1
                    else -> -1
                }
                if (rank < 0) null else Triple(rank, node.bounds.top, node)
            }
            .sortedWith(compareBy<Triple<Int, Int, NodeSnapshot>> { it.first }.thenBy { it.second })
            .map { it.third }
            .firstOrNull()

    fun findSignCoinEntry(observation: Observation): NodeSnapshot? =
        findFirstByWords(observation, signCoinWords)

    private fun findFirstByWords(
        observation: Observation,
        words: List<String>,
    ): NodeSnapshot? =
        observation.nodes
            .asSequence()
            .filter { it.enabled && hasUsableBounds(it.bounds) }
            .mapNotNull { node ->
                val text = nodeText(node)
                val rank = words.indexOfFirst { text.contains(it) }
                if (rank < 0) null else Triple(rank, node.bounds.top, node)
            }
            .sortedWith(compareBy<Triple<Int, Int, NodeSnapshot>> { it.first }.thenBy { it.second })
            .map { it.third }
            .firstOrNull()

    fun findBrowseTask(observation: Observation): BrowseTaskCandidate? {
        val nodes = observation.nodes.filter { it.enabled && hasUsableBounds(it.bounds) }

        val direct = nodes
            .mapNotNull { node ->
                val text = nodeText(node)
                val rank = directBrowseActions.indexOfFirst { text.contains(it) }
                if (rank < 0) {
                    null
                } else {
                    BrowseTaskCandidate(
                        bounds = node.bounds,
                        buttonText = text,
                        contextText = rowContext(nodes, node),
                    ) to rank
                }
            }
            .sortedWith(
                compareBy<Pair<BrowseTaskCandidate, Int>> { it.second }
                    .thenBy { it.first.bounds.top }
                    .thenBy { it.first.bounds.left },
            )
            .map { it.first }
            .firstOrNull()

        if (direct != null) {
            return direct
        }

        return nodes
            .asSequence()
            .filter { nodeText(it).contains("去完成") }
            .mapNotNull { node ->
                val context = rowContext(nodes, node)
                val looksBrowse = browseContextWords.any { context.contains(it) }
                val excluded = excludedContextWords.any { context.contains(it) }
                if (!looksBrowse || excluded) {
                    null
                } else {
                    BrowseTaskCandidate(
                        bounds = node.bounds,
                        buttonText = nodeText(node),
                        contextText = context,
                    )
                }
            }
            .sortedWith(compareBy<BrowseTaskCandidate> { it.bounds.top }.thenBy { it.bounds.left })
            .firstOrNull()
    }

    private fun rowContext(
        nodes: List<NodeSnapshot>,
        actionNode: NodeSnapshot,
    ): String {
        val actionCenterY = centerY(actionNode.bounds)
        return nodes
            .asSequence()
            .filter { it.index != actionNode.index }
            .filter { abs(centerY(it.bounds) - actionCenterY) <= ROW_Y_TOLERANCE }
            .filter { it.bounds.left < actionNode.bounds.right }
            .map(::nodeText)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" ")
    }

    private fun nodeText(node: NodeSnapshot): String =
        (node.text ?: node.contentDescription ?: "").trim()

    private fun centerY(bounds: IntRect): Int =
        bounds.top + (bounds.bottom - bounds.top) / 2

    private fun hasUsableBounds(bounds: IntRect): Boolean =
        bounds.right > bounds.left && bounds.bottom > bounds.top

    private const val ROW_Y_TOLERANCE = 130
}
