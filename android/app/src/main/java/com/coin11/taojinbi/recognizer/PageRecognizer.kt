package com.coin11.taojinbi.recognizer

import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation

data class RecognitionEvidence(
    val signal: String,
    val detail: String,
)

data class RecognitionResult(
    val pageType: PageType,
    val evidence: List<RecognitionEvidence>,
) {
    fun debugText(): String = buildString {
        appendLine("Page：${pageType.wireName}")
        appendLine("Evidence：")
        evidence.forEach { item ->
            appendLine("✓ ${item.signal}：${item.detail}")
        }
    }.trimEnd()
}

class PageRecognizer(
    private val rules: RuleSet,
    private val taobaoPackage: String = "com.taobao.taobao",
) {
    private val actionRegex = Regex(rules.actionTextPattern)
    private val progressRegex = Regex("[（(]\\d+/\\d+[）)]")
    private val subscribeRegex = Regex("订阅\\s*\\+\\s*\\d+")
    private val goodShopRegex = Regex("今日推荐\\d+家好店")

    fun recognize(
        observation: Observation,
        activityHint: String? = null,
    ): RecognitionResult {
        val packageName = observation.packageName.orEmpty()
        val texts = observation.nodes
            .flatMap { node -> listOfNotNull(node.text, node.contentDescription) }
            .map(::normalize)
            .filter { it.isNotEmpty() }

        fun result(
            pageType: PageType,
            vararg evidence: RecognitionEvidence,
        ): RecognitionResult = RecognitionResult(pageType, evidence.toList())

        if (packageName.isNotEmpty() && packageName != taobaoPackage) {
            return result(
                PageType.EXTERNAL_APP,
                evidence("package", packageName),
            )
        }

        if (looksLikeEnergyTaskList(texts) || looksLikeEnergyPanel(observation, texts)) {
            return result(
                PageType.ENERGY_TASK_LIST,
                evidence("energy", matched(texts, listOf("做任务赚体力", "赚体力", "体力"))),
            )
        }

        val goodShopHit = texts.firstOrNull { goodShopRegex.containsMatchIn(it) }
        if (goodShopHit != null) {
            return result(
                PageType.GOOD_SHOP_PAGE,
                evidence("good-shop", goodShopHit),
            )
        }

        if (looksLikeTaskFloatBrowsePage(texts)) {
            return result(
                PageType.TAOBAO_BROWSE_TASK,
                evidence("task-float", matched(texts, listOf("任务浮标源码正式页", "返回图标"))),
                evidence("timer", matched(texts, BROWSE_TIMERS)),
            )
        }

        val doneHit = taskDoneHit(texts)
        if (doneHit != null) {
            return result(
                PageType.TASK_DONE,
                evidence("task-done", doneHit),
            )
        }

        val quizHit = firstMatched(texts, rules.quizWords)
        if (quizHit != null) {
            return result(
                PageType.QUIZ,
                evidence("quiz", quizHit),
            )
        }

        if (looksLikeTaskListPage(texts) || looksLikeCoinTaskPanel(texts)) {
            return result(
                PageType.DAILY_TASK_LIST,
                evidence("task-list", taskListEvidence(texts)),
            )
        }

        if (looksLikeCoinHomeShell(texts)) {
            return result(
                PageType.COIN_HOME,
                evidence("coin-shell", matched(texts, listOf("淘金币首页", "app", "eva-canvas", "ice-container"))),
            )
        }

        if (looksLikeCoinHomePage(texts)) {
            return result(
                PageType.COIN_HOME,
                evidence("coin-home", matched(texts, rules.coinHomeWords)),
            )
        }

        if (looksLikeTaobaoHomePage(texts)) {
            return result(
                PageType.TAOBAO_HOME,
                evidence("taobao-home", taobaoHomeEvidence(texts)),
            )
        }

        if (looksLikeBrowseTaskPage(texts, activityHint)) {
            return result(
                PageType.TAOBAO_BROWSE_TASK,
                evidence(
                    "browse",
                    activityHint?.takeIf { isBrowseActivity(it) }
                        ?: firstMatched(texts, rules.searchBrowseWords + rules.browsePageWords)
                        ?: "browse signals",
                ),
            )
        }

        if (looksLikeMoreCoinExpandSection(texts)) {
            return result(
                PageType.DAILY_TASK_LIST,
                evidence("expanded-task-list", "更多金币等你赚 + 展开 + task marker"),
            )
        }

        if (looksLikeShopSubscribeTask(texts)) {
            return result(
                PageType.SHOP_SUBSCRIBE_TASK,
                evidence("shop-subscribe", firstMatched(texts, rules.shopSubscribeWords) ?: "subscribe signals"),
            )
        }

        return result(
            PageType.UNKNOWN_TAOBAO_PAGE,
            evidence(
                "fallback",
                if (packageName == taobaoPackage) {
                    "淘宝包内未命中已知规则"
                } else {
                    "package 未知/空，且未命中已知规则"
                },
            ),
        )
    }

    private fun looksLikeEnergyTaskList(texts: List<String>): Boolean =
        hasAny(texts, listOf("做任务赚体力")) &&
            hasAny(texts, listOf("赚体力", "体力"))

    private fun looksLikeEnergyPanel(
        observation: Observation,
        texts: List<String>,
    ): Boolean {
        val width = observation.nodes.maxOfOrNull { it.bounds.right } ?: 0
        val height = observation.nodes.maxOfOrNull { it.bounds.bottom } ?: 0
        if (width <= 0 || height <= 0) return false

        val hasTitle = observation.nodes.any { node ->
            nodeCombinedText(node).contains("做任务赚体力") &&
                node.bounds.top < (height * 0.45f)
        }

        val hasTaskAction = observation.nodes.any { node ->
            val text = nodeCombinedText(node)
            actionRegex.containsMatchIn(text) &&
                node.bounds.left >= (width * 0.68f)
        }

        val hasTaskRow = hasAny(
            texts,
            listOf("天猫积分换体力", "每300天猫积分兑换", "最高得", "体力("),
        )

        return hasTitle && (hasTaskAction || hasTaskRow)
    }

    private fun looksLikeTaskFloatBrowsePage(texts: List<String>): Boolean {
        val shell = hasAny(texts, listOf("任务浮标源码正式页", "返回图标"))
        val timer = hasAny(texts, BROWSE_TIMERS)
        val reward = hasAny(texts, listOf("得", "+", "已得"))
        return shell && timer && reward
    }

    private fun taskDoneHit(texts: List<String>): String? {
        val doneWords = if (hasAny(texts, listOf("淘宝购物清单"))) {
            rules.taskDonePageWords.filterNot { it == "已得" }
        } else {
            rules.taskDonePageWords
        }

        return texts.firstOrNull { text ->
            doneWords.any(text::contains) &&
                rules.taskDoneExcludeWords.none(text::contains)
        }
    }

    private fun looksLikeTaskListPage(texts: List<String>): Boolean {
        if (looksLikeSearchBrowsePage(texts)) return false
        return looksLikeDailyTaskListByNodes(texts)
    }

    private fun looksLikeDailyTaskListByNodes(texts: List<String>): Boolean {
        if (looksLikeSearchBrowsePage(texts)) return false

        val hasFast = hasAny(texts, rules.dailyFastWords)
        val hasTaskArea = hasAny(texts, rules.dailyTaskAreaWords)
        val hasBottom = hasAny(texts, rules.taskListBottomWords)
        val hasProgress = texts.any(progressRegex::containsMatchIn)
        val actionCount = texts.count(actionRegex::containsMatchIn)

        return (hasFast && hasTaskArea) ||
            (hasTaskArea && actionCount > 0) ||
            (hasBottom && (hasTaskArea || hasProgress))
    }

    private fun looksLikeCoinTaskPanel(texts: List<String>): Boolean =
        hasAny(texts, listOf("赚金币抵钱", "今日累计奖励", "完成进度")) &&
            hasAny(texts, listOf("领取奖励", "去完成", "去逛逛", "逛一逛"))

    private fun looksLikeCoinHomeShell(texts: List<String>): Boolean =
        hasAny(texts, listOf("淘金币首页")) &&
            hasAny(texts, listOf("app")) &&
            hasAny(texts, listOf("eva-canvas", "ice-container"))

    private fun looksLikeCoinHomePage(texts: List<String>): Boolean {
        if (looksLikeCoinTaskPanel(texts)) return false

        if (looksLikeDailyTaskListByNodes(texts) || looksLikeMoreCoinExpandSection(texts)) {
            return false
        }

        val hasCoinHomeAnchor = hasAny(
            texts,
            listOf("淘金币首页", "淘金币标题", "赚更多金币", "赚金币抵钱"),
        )
        val hasCoinHomeSupport = hasAny(texts, rules.coinHomeWords)

        return hasCoinHomeAnchor &&
            hasCoinHomeSupport &&
            !looksLikeSearchBrowsePage(texts)
    }

    private fun looksLikeTaobaoHomePage(texts: List<String>): Boolean {
        if (looksLikeCoinHomeShell(texts)) return false
        if (hasAny(texts, listOf("领淘金币"))) return true

        val hasTopChannel = hasAny(
            texts,
            listOf("推荐", "关注", "闪购", "国补", "穿搭", "飞猪", "618"),
        )
        val hasSearch = hasAny(texts, listOf("搜索栏", "扫一扫", "拍立淘", "搜索"))
        val hasHomeGrid = hasAny(
            texts,
            listOf("淘宝农场", "天猫新品", "试用领取", "红包签到", "天猫超市"),
        )

        return hasSearch && (hasTopChannel || hasHomeGrid)
    }

    private fun looksLikeBrowseTaskPage(
        texts: List<String>,
        activityHint: String?,
    ): Boolean {
        if (!activityHint.isNullOrBlank() && isBrowseActivity(activityHint)) {
            return true
        }
        if (looksLikeTaskFloatBrowsePage(texts)) return true
        if (looksLikeCoinHomePage(texts)) return false
        if (looksLikeSearchBrowsePage(texts)) return true
        if (looksLikeTaskListPage(texts)) return false
        return hasAny(texts, rules.browsePageWords)
    }

    private fun isBrowseActivity(activity: String): Boolean =
        activity.contains("NewDetailActivity") ||
            activity.contains("ShopActivity")

    private fun looksLikeSearchBrowsePage(texts: List<String>): Boolean =
        hasAny(texts, rules.searchBrowseWords)

    private fun looksLikeMoreCoinExpandSection(texts: List<String>): Boolean {
        val hasExpand = hasAny(texts, listOf("更多金币等你赚")) &&
            hasAny(texts, listOf("展开"))

        val hasTaskMarker = texts.any(progressRegex::containsMatchIn) ||
            texts.any(actionRegex::containsMatchIn) ||
            hasAny(
                texts,
                listOf("完成下方任务", "今日速赚", "任务到访得金币", "每日来任务面板"),
            )

        return hasExpand && hasTaskMarker
    }

    private fun looksLikeShopSubscribeTask(texts: List<String>): Boolean {
        if (hasAny(texts, listOf("淘金币首页", "淘金币标题", "购物车", "可抵"))) {
            return false
        }

        if (!hasAny(texts, rules.shopSubscribeWords)) return false

        return texts.any(subscribeRegex::containsMatchIn) ||
            hasAny(texts, listOf("取消关注", "最多还可以领", "立即领"))
    }

    private fun taskListEvidence(texts: List<String>): String =
        firstMatched(
            texts,
            rules.taskListWords +
                rules.dailyTaskAreaWords +
                listOf("赚金币抵钱", "今日累计奖励", "完成进度"),
        ) ?: "task-list structural signals"

    private fun taobaoHomeEvidence(texts: List<String>): String =
        firstMatched(
            texts,
            listOf(
                "领淘金币",
                "搜索栏",
                "扫一扫",
                "拍立淘",
                "推荐",
                "关注",
                "淘宝农场",
                "天猫超市",
            ),
        ) ?: "home signals"

    private fun evidence(signal: String, detail: String) =
        RecognitionEvidence(signal, detail)

    private fun firstMatched(
        texts: List<String>,
        words: List<String>,
    ): String? = texts.firstOrNull { text ->
        words.any(text::contains)
    }

    private fun matched(
        texts: List<String>,
        words: List<String>,
    ): String = buildList {
        words.forEach { word ->
            if (texts.any { it.contains(word) }) add(word)
        }
    }.distinct().take(6).joinToString("、").ifBlank { "(structural match)" }

    private fun hasAny(
        texts: List<String>,
        words: List<String>,
    ): Boolean = words.any { word ->
        texts.any { it.contains(word) }
    }

    private fun nodeCombinedText(node: NodeSnapshot): String =
        listOfNotNull(node.text, node.contentDescription)
            .joinToString(" ")
            .let(::normalize)

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    companion object {
        private val BROWSE_TIMERS = listOf(
            "浏览5秒",
            "浏览10秒",
            "浏览15秒",
            "浏览25秒",
            "浏览30秒",
        )
    }
}
