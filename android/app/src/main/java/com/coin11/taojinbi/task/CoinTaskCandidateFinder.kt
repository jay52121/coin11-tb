package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import kotlin.math.abs

enum class CoinTaskKind {
    BROWSE,
    REWARD,
}

data class CoinTaskCandidate(
    val key: String,
    val kind: CoinTaskKind,
    val bounds: IntRect,
    val actionText: String,
    val contextText: String,
)

object CoinTaskCandidateFinder {

    private val browseActions = listOf(
        "去逛逛",
        "去浏览",
        "逛一逛",
        "去看看",
        "搜一下",
        "逛一下",
        "点击去逛",
    )

    private val rewardActions = listOf(
        "领取奖励",
        "立即领取",
        "去领取",
        "立即领",
        "点击得",
    )

    private val browseContextWords = listOf(
        "浏览",
        "逛",
        "清单",
        "商品",
        "店铺",
        "会场",
        "频道",
        "农场",
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

    private val doneWords = listOf(
        "已完成",
        "已领取",
        "任务已完成",
        "记得明天再来",
    )

    fun findNext(
        observation: Observation,
        handledKeys: Set<String>,
    ): CoinTaskCandidate? {
        val nodes = observation.nodes
            .filter { it.enabled && hasUsableBounds(it.bounds) }

        return nodes
            .mapNotNull { node -> toCandidate(nodes, node) }
            .filterNot { it.key in handledKeys }
            .sortedWith(
                compareBy<CoinTaskCandidate> { it.bounds.top }
                    .thenBy { it.bounds.left },
            )
            .firstOrNull()
    }

    private fun toCandidate(
        nodes: List<NodeSnapshot>,
        node: NodeSnapshot,
    ): CoinTaskCandidate? {
        val action = nodeText(node)
        if (action.isBlank()) {
            return null
        }

        val context = rowContext(nodes, node)
        if (doneWords.any { context.contains(it) }) {
            return null
        }

        val excluded = excludedContextWords.any { context.contains(it) }

        val kind = when {
            rewardActions.any { action.contains(it) } && !excluded ->
                CoinTaskKind.REWARD

            browseActions.any { action.contains(it) } && !excluded ->
                CoinTaskKind.BROWSE

            action.contains("去完成") &&
                !excluded &&
                browseContextWords.any { context.contains(it) } ->
                CoinTaskKind.BROWSE

            else -> null
        } ?: return null

        val compactContext = context.replace(Regex("\\s+"), "")
        val compactAction = action.replace(Regex("\\s+"), "")
        val key = (compactAction + "|" + compactContext).take(180)

        return CoinTaskCandidate(
            key = key,
            kind = kind,
            bounds = node.bounds,
            actionText = action,
            contextText = context,
        )
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
