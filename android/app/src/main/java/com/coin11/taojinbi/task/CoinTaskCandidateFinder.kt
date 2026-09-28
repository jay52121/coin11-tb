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

    private val intrinsicExcludedContextWords = listOf(
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
        excludeWords: List<String> = DEFAULT_EXCLUDE_WORDS,
    ): CoinTaskCandidate? {
        val nodes = observation.nodes
            .filter { it.enabled && hasUsableBounds(it.bounds) }

        return nodes
            .mapNotNull { node -> toCandidate(nodes, node, excludeWords) }
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
        excludeWords: List<String>,
    ): CoinTaskCandidate? {
        val action = nodeText(node)
        if (action.isBlank()) {
            return null
        }

        val context = rowContext(nodes, node)
        if (doneWords.any { context.contains(it) }) {
            return null
        }

        val excluded =
            intrinsicExcludedContextWords.any { context.contains(it) } ||
                excludedByRuleWords(context, excludeWords)

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
        val progressKey = TASK_PROGRESS_REGEX
            .findAll(context.replace(Regex("\\s+"), " "))
            .map { it.groupValues[1].replace(Regex("\\s+"), "") }
            .toList()
            .lastOrNull()
        val key = progressKey?.let { "progress:" + it }
            ?: (compactAction + "|" + compactContext).take(180)

        return CoinTaskCandidate(
            key = key,
            kind = kind,
            bounds = node.bounds,
            actionText = action,
            contextText = context,
        )
    }

    private fun excludedByRuleWords(
        context: String,
        excludeWords: List<String>,
    ): Boolean {
        val compactTask = normalizeForRule(context)
        val compactWords = excludeWords
            .map(::normalizeForRule)
            .filter { it.isNotBlank() }

        if (
            "uc" in compactWords &&
            Regex("去逛0[6g]送红包福利").containsMatchIn(compactTask)
        ) {
            return true
        }

        val hasBrowseStep =
            Regex("浏览\\d{1,3}秒|点击去逛").containsMatchIn(compactTask)

        for (word in compactWords) {
            if (!compactTask.contains(word)) {
                continue
            }
            if (word.contains("下单") && hasBrowseStep) {
                continue
            }
            return true
        }
        return false
    }

    private fun normalizeForRule(text: String): String =
        text.replace(Regex("\\s+"), "").lowercase()

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
    private val DEFAULT_EXCLUDE_WORDS =
        listOf("下单", "快手", "评价", "助力", "头条")
    private val TASK_PROGRESS_REGEX =
        Regex("([^\\s，。；;（）()]{2,40}[（(]\\d+/\\d+[）)])")
}
