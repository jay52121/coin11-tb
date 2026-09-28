package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import com.coin11.taojinbi.ocr.OcrSnapshot
import kotlin.math.abs

enum class CoinTaskKind {
    BROWSE,
    REWARD,
}

data class CoinTaskPolicy(
    val actionTextPattern: String,
    val rewardButtonPattern: String,
    val excludeWords: List<String>,
    val doneWords: List<String>,
    val doneExcludeWords: List<String>,
)

data class CoinTaskCandidate(
    val key: String,
    val clickKey: String,
    val clickLimit: Int,
    val kind: CoinTaskKind,
    val bounds: IntRect,
    val actionText: String,
    val contextText: String,
    val source: String = "xml",
)

object CoinTaskCandidateFinder {

    private val intrinsicExcludedContextWords = listOf(
        "答题",
        "趣味",
        "订阅",
        "关注",
        "游戏",
        "捐",
    )

    fun findNext(
        observation: Observation,
        handledKeys: Set<String>,
        excludeWords: List<String> = DEFAULT_EXCLUDE_WORDS,
    ): CoinTaskCandidate? {
        val policy = DEFAULT_POLICY.copy(excludeWords = excludeWords)
        val clickCounts = handledKeys.associateWith { Int.MAX_VALUE }
        return findNext(
            observation = observation,
            clickCounts = clickCounts,
            invalidClickKeys = emptySet(),
            policy = policy,
        )
    }

    fun findNext(
        observation: Observation,
        clickCounts: Map<String, Int>,
        invalidClickKeys: Set<String>,
        policy: CoinTaskPolicy,
    ): CoinTaskCandidate? {
        val nodes = observation.nodes
            .filter { it.enabled && hasUsableBounds(it.bounds) }

        val actionCandidate = nodes
            .mapNotNull { node -> toCandidate(nodes, node, policy, "xml") }
            .filter { candidate ->
                candidate.clickKey !in invalidClickKeys &&
                    (clickCounts[candidate.key] ?: 0) < candidate.clickLimit
            }
            .sortedWith(
                compareBy<CoinTaskCandidate> { it.bounds.top }
                    .thenBy { it.bounds.left },
            )
            .firstOrNull()

        if (actionCandidate != null) {
            return actionCandidate
        }

        return findClickableRowCandidate(
            nodes = nodes,
            clickCounts = clickCounts,
            invalidClickKeys = invalidClickKeys,
            policy = policy,
        )
    }

    fun findNextFromOcr(
        snapshot: OcrSnapshot,
        clickCounts: Map<String, Int>,
        invalidClickKeys: Set<String>,
        policy: CoinTaskPolicy,
    ): CoinTaskCandidate? {
        val actionRegex = safeRegex(policy.actionTextPattern)
        val rewardRegex = safeRegex(policy.rewardButtonPattern)
        val width = snapshot.screenshotWidth.coerceAtLeast(1)

        return snapshot.lines
            .asSequence()
            .filter { it.bounds != null }
            .filter { line ->
                val bounds = line.bounds!!
                val xCenter = bounds.left + (bounds.right - bounds.left) / 2
                xCenter >= (width * 0.65f).toInt() &&
                    actionRegex.containsMatchIn(line.text)
            }
            .mapNotNull { line ->
                val bounds = line.bounds ?: return@mapNotNull null
                val actionCenterY = centerY(bounds)
                val context = snapshot.lines
                    .asSequence()
                    .filter { it.bounds != null }
                    .filter { other ->
                        val otherBounds = other.bounds!!
                        val otherCenterX =
                            otherBounds.left + (otherBounds.right - otherBounds.left) / 2
                        abs(centerY(otherBounds) - actionCenterY) <= OCR_ROW_Y_TOLERANCE &&
                            otherCenterX < (width * 0.78f).toInt()
                    }
                    .map { it.text.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("O1CN") }
                    .distinct()
                    .joinToString(" ")

                candidateFromText(
                    action = line.text,
                    context = context.ifBlank { line.text },
                    bounds = bounds,
                    policy = policy,
                    rewardRegex = rewardRegex,
                    source = "ocr",
                )
            }
            .filter { candidate ->
                candidate.clickKey !in invalidClickKeys &&
                    (clickCounts[candidate.key] ?: 0) < candidate.clickLimit
            }
            .sortedWith(
                compareBy<CoinTaskCandidate> { it.bounds.top }
                    .thenBy { it.bounds.left },
            )
            .firstOrNull()
    }

    fun taskIsDone(
        context: String,
        doneWords: List<String>,
        doneExcludeWords: List<String>,
    ): Boolean {
        val count = PROGRESS_COUNT_REGEX.find(context)
        if (count != null) {
            val current = count.groupValues[1].toIntOrNull()
            val total = count.groupValues[2].toIntOrNull()
            if (current != null && total != null && current >= total) {
                return true
            }
        }

        return doneWords.any { context.contains(it) } &&
            doneExcludeWords.none { context.contains(it) }
    }

    fun taskClickKey(context: String): String {
        val source = context.replace(Regex("\\s+"), " ").trim()
        val matches = TASK_PROGRESS_REGEX.findAll(source).toList()
        if (matches.isNotEmpty()) {
            return matches.last().groupValues[1].replace(Regex("\\s+"), "")
        }
        return source.replace(Regex("\\s+"), "").take(80)
    }

    fun taskClickLimit(context: String): Int {
        val match = PROGRESS_COUNT_REGEX.find(context) ?: return 2
        val total = match.groupValues[2].toIntOrNull() ?: return 2
        return (total + 1).coerceIn(2, 12)
    }

    fun isTaskListAtBottom(
        observation: Observation,
        bottomWords: List<String>,
    ): Boolean =
        observation.nodes.any { node ->
            val text = nodeText(node)
            bottomWords.any { word -> text.contains(word) }
        }

    fun ocrIsTaskListAtBottom(
        snapshot: OcrSnapshot,
        bottomWords: List<String>,
    ): Boolean {
        val words = bottomWords + OCR_BOTTOM_EXTRA_WORDS
        return snapshot.lines.any { line ->
            words.any { word -> compact(line.text).contains(compact(word)) }
        }
    }

    fun findExpandEntry(
        observation: Observation,
        expandWords: List<String>,
    ): NodeSnapshot? {
        val nodes = observation.nodes
            .filter { it.enabled && hasUsableBounds(it.bounds) }
        val textNode = nodes
            .asSequence()
            .filter { node ->
                val text = nodeText(node)
                expandWords.any { word -> text.contains(word) }
            }
            .sortedWith(compareBy<NodeSnapshot> { it.bounds.top }.thenBy { it.bounds.left })
            .firstOrNull()
            ?: return null

        return smallestClickableContainerNode(nodes, textNode.bounds) ?: textNode
    }

    fun findExpandOcr(
        snapshot: OcrSnapshot,
        expandWords: List<String>,
    ): IntRect? =
        snapshot.lines
            .asSequence()
            .filter { it.bounds != null }
            .filter { line ->
                expandWords.any { word -> compact(line.text).contains(compact(word)) }
            }
            .sortedWith(compareBy({ it.bounds!!.top }, { it.bounds!!.left }))
            .mapNotNull { it.bounds }
            .firstOrNull()

    fun findNextTaskHop(
        observation: Observation,
        nextTaskWords: List<String>,
        screenWidth: Int,
    ): IntRect? {
        val bounds = observation.nodes
            .asSequence()
            .filter { it.enabled && hasUsableBounds(it.bounds) }
            .filter { node ->
                val text = nodeText(node)
                nextTaskWords.any { word -> text.contains(word) } &&
                    node.bounds.left <= (screenWidth * 0.25f).toInt()
            }
            .sortedWith(compareBy<NodeSnapshot> { it.bounds.top }.thenBy { it.bounds.left })
            .map { it.bounds }
            .firstOrNull()
            ?: return null

        val x = (bounds.left + 15).coerceIn(20, 55)
        val y = centerY(bounds)
        return IntRect(x - 1, y - 1, x + 1, y + 1)
    }

    fun findNextTaskHopOcr(
        snapshot: OcrSnapshot,
        nextTaskWords: List<String>,
    ): IntRect? {
        val hits = snapshot.lines
            .filter { line ->
                line.bounds != null &&
                    nextTaskWords.any { word ->
                        compact(line.text).contains(compact(word))
                    }
            }
        if (hits.isEmpty()) {
            return null
        }
        val preferred = hits.filter {
            it.bounds!!.left <= (snapshot.screenshotWidth * 0.35f).toInt()
        }
        return (preferred.ifEmpty { hits })
            .sortedWith(compareBy({ it.bounds!!.top }, { it.bounds!!.left }))
            .firstOrNull()
            ?.bounds
    }

    fun findSearchDiscoveryTarget(
        observation: Observation,
        searchBrowseWords: List<String>,
    ): IntRect? {
        val textNodes = observation.nodes
            .filter { it.enabled && hasUsableBounds(it.bounds) }
            .filter { nodeText(it).isNotBlank() }

        val pageLooksSearch = textNodes.any { node ->
            val text = nodeText(node)
            searchBrowseWords.any { word -> text.contains(word) }
        }
        if (!pageLooksSearch) {
            return null
        }

        val history = textNodes.firstOrNull { nodeText(it).contains("历史搜索") }
        if (history != null) {
            return textNodes
                .asSequence()
                .filter { it.bounds.top >= history.bounds.bottom }
                .filter { node ->
                    val text = nodeText(node)
                    text.isNotBlank() &&
                        !text.contains("历史搜索") &&
                        !text.contains("搜索发现")
                }
                .sortedWith(compareBy<NodeSnapshot> { it.bounds.top }.thenBy { it.bounds.left })
                .map { node ->
                    smallestClickableContainerNode(
                        observation.nodes,
                        node.bounds,
                    )?.bounds ?: node.bounds
                }
                .firstOrNull()
        }

        val discovery = textNodes.firstOrNull { nodeText(it).contains("搜索发现") }
            ?: return null
        return textNodes
            .asSequence()
            .filter { it.bounds.top >= discovery.bounds.bottom }
            .filter { it.bounds.right - it.bounds.left > 80 }
            .filter { it.bounds.bottom - it.bounds.top > 40 }
            .sortedWith(compareBy<NodeSnapshot> { it.bounds.top }.thenBy { it.bounds.left })
            .map { node ->
                smallestClickableContainerNode(
                    observation.nodes,
                    node.bounds,
                )?.bounds ?: node.bounds
            }
            .firstOrNull()
    }

    private fun toCandidate(
        nodes: List<NodeSnapshot>,
        node: NodeSnapshot,
        policy: CoinTaskPolicy,
        source: String,
    ): CoinTaskCandidate? {
        val action = nodeText(node)
        if (action.isBlank()) {
            return null
        }

        val actionRegex = safeRegex(policy.actionTextPattern)
        if (!actionRegex.containsMatchIn(action)) {
            return null
        }

        val context = rowContext(nodes, node).ifBlank { action }
        val targetBounds = smallestClickableContainer(
            nodes = nodes,
            childBounds = node.bounds,
        ) ?: node.bounds
        return candidateFromText(
            action = action,
            context = context,
            bounds = targetBounds,
            policy = policy,
            rewardRegex = safeRegex(policy.rewardButtonPattern),
            source = source,
        )
    }

    private fun candidateFromText(
        action: String,
        context: String,
        bounds: IntRect,
        policy: CoinTaskPolicy,
        rewardRegex: Regex,
        source: String,
    ): CoinTaskCandidate? {
        if (
            intrinsicExcludedContextWords.any { context.contains(it) } ||
            excludedByRuleWords(context, policy.excludeWords) ||
            taskIsDone(context, policy.doneWords, policy.doneExcludeWords)
        ) {
            return null
        }

        val kind = if (rewardRegex.containsMatchIn(action)) {
            CoinTaskKind.REWARD
        } else {
            CoinTaskKind.BROWSE
        }

        val key = taskClickKey(context)
        val clickKey = source + ":" + key + ":" + bounds
        return CoinTaskCandidate(
            key = key,
            clickKey = clickKey,
            clickLimit = if (kind == CoinTaskKind.REWARD) 2 else taskClickLimit(context),
            kind = kind,
            bounds = bounds,
            actionText = action,
            contextText = context,
            source = source,
        )
    }

    private fun findClickableRowCandidate(
        nodes: List<NodeSnapshot>,
        clickCounts: Map<String, Int>,
        invalidClickKeys: Set<String>,
        policy: CoinTaskPolicy,
    ): CoinTaskCandidate? {
        val screenWidth = nodes.maxOfOrNull { it.bounds.right }
            ?.coerceAtLeast(1)
            ?: return null
        val actionRegex = safeRegex(policy.actionTextPattern)
        val rewardRegex = safeRegex(policy.rewardButtonPattern)

        return nodes
            .asSequence()
            .filter { it.clickable && it.enabled && hasUsableBounds(it.bounds) }
            .filter { row ->
                val width = row.bounds.right - row.bounds.left
                val height = row.bounds.bottom - row.bounds.top
                row.bounds.top >= ROW_MIN_TOP &&
                    width >= (screenWidth * ROW_MIN_WIDTH_RATIO).toInt() &&
                    height in ROW_MIN_HEIGHT..ROW_MAX_HEIGHT
            }
            .mapNotNull { row ->
                val rowNodes = nodes
                    .asSequence()
                    .filter { it.index != row.index }
                    .filter { contains(row.bounds, it.bounds) }
                    .toList()
                val texts = rowNodes
                    .map(::nodeText)
                    .filter { it.isNotBlank() }
                    .filterNot { it.startsWith("O1CN") }
                if (texts.isEmpty()) {
                    return@mapNotNull null
                }

                val combined = texts.joinToString(" ")
                val actionNode = rowNodes
                    .filter { nodeText(it).isNotBlank() }
                    .filter { node ->
                        node.bounds.left >= (screenWidth * ACTION_RIGHT_RATIO).toInt() &&
                            actionRegex.containsMatchIn(nodeText(node))
                    }
                    .minByOrNull { it.bounds.top }

                val hasRewardMarker = texts.any { REWARD_MARKER_REGEX.matches(it.trim()) }
                if (actionNode == null && !hasRewardMarker) {
                    return@mapNotNull null
                }

                val action = actionNode?.let(::nodeText) ?: "任务行"
                candidateFromText(
                    action = action,
                    context = combined,
                    bounds = row.bounds,
                    policy = policy,
                    rewardRegex = rewardRegex,
                    source = "row",
                )
            }
            .filter { candidate ->
                candidate.clickKey !in invalidClickKeys &&
                    (clickCounts[candidate.key] ?: 0) < candidate.clickLimit
            }
            .sortedWith(
                compareBy<CoinTaskCandidate> { it.bounds.top }
                    .thenBy { it.bounds.left },
            )
            .firstOrNull()
    }

    private fun smallestClickableContainer(
        nodes: List<NodeSnapshot>,
        childBounds: IntRect,
    ): IntRect? =
        smallestClickableContainerNode(nodes, childBounds)?.bounds

    private fun smallestClickableContainerNode(
        nodes: List<NodeSnapshot>,
        childBounds: IntRect,
    ): NodeSnapshot? =
        nodes
            .asSequence()
            .filter { it.clickable && it.enabled && hasUsableBounds(it.bounds) }
            .filter { contains(it.bounds, childBounds) }
            .filter { container ->
                val width = container.bounds.right - container.bounds.left
                val height = container.bounds.bottom - container.bounds.top
                width >= childBounds.right - childBounds.left &&
                    height >= childBounds.bottom - childBounds.top &&
                    height <= CLICKABLE_CONTAINER_MAX_HEIGHT
            }
            .minWithOrNull(
                compareBy<NodeSnapshot> {
                    (it.bounds.right - it.bounds.left) *
                        (it.bounds.bottom - it.bounds.top)
                }.thenBy { it.depth },
            )

    private fun contains(
        outer: IntRect,
        inner: IntRect,
    ): Boolean =
        outer.left <= inner.left &&
            outer.top <= inner.top &&
            outer.right >= inner.right &&
            outer.bottom >= inner.bottom

    private fun excludedByRuleWords(
        context: String,
        excludeWords: List<String>,
    ): Boolean {
        val skipSource = SHARE_BONUS_NOISE_REGEX.replace(context, "")
        val compactTask = normalizeForRule(skipSource)
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

    private fun safeRegex(pattern: String): Regex =
        runCatching { Regex(pattern) }
            .getOrElse { Regex(DEFAULT_ACTION_PATTERN) }

    private fun normalizeForRule(text: String): String =
        compact(text).lowercase()

    private fun compact(text: String): String =
        text.replace(Regex("\\s+"), "")

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
            .filterNot { it.startsWith("O1CN") }
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
    private const val OCR_ROW_Y_TOLERANCE = 115
    private const val ROW_MIN_TOP = 180
    private const val ROW_MIN_HEIGHT = 55
    private const val ROW_MAX_HEIGHT = 560
    private const val ROW_MIN_WIDTH_RATIO = 0.45f
    private const val ACTION_RIGHT_RATIO = 0.68f
    private const val CLICKABLE_CONTAINER_MAX_HEIGHT = 560
    private const val DEFAULT_ACTION_PATTERN =
        "去完成|去逛逛|去浏览|逛一逛|立即领|去领取|去看看|搜一下|玩一把|捐一笔|逛一下|点击去逛|领取奖励|立即领取|点击得|爱心捐"
    private val DEFAULT_EXCLUDE_WORDS =
        listOf("下单", "快手", "评价", "助力", "头条")
    private val DEFAULT_POLICY = CoinTaskPolicy(
        actionTextPattern = DEFAULT_ACTION_PATTERN,
        rewardButtonPattern = "领取奖励|立即领取|点击得",
        excludeWords = DEFAULT_EXCLUDE_WORDS,
        doneWords = listOf("已完成", "已领取", "已得", "任务已完成", "记得明天再来"),
        doneExcludeWords = listOf("累计已得", "累积已得"),
    )
    private val TASK_PROGRESS_REGEX =
        Regex("([^\\s，。；;（）()]{2,40}[（(]\\d+/\\d+[）)])")
    private val PROGRESS_COUNT_REGEX =
        Regex("[（(](\\d+)/(\\d+)[）)]")
    private val SHARE_BONUS_NOISE_REGEX =
        Regex("第\\d+笔[\\d.]+%?\\s*分享助力\\s*hd_bonus_progress_bar_text_target")
    private val REWARD_MARKER_REGEX = Regex("^\\+\\d+$")
    private val OCR_BOTTOM_EXTRA_WORDS = listOf(
        "注：以上金币额",
        "以上奖励均为最高奖励",
        "实际获得奖励为准",
    )
}
