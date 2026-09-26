package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.Observation
import com.coin11.taojinbi.recognizer.PageType

enum class BrowseContextPhase {
    NONE,
    WAITING_ENTRY,
    ACTIVE,
}

object TaskPageContext {

    private val browseSignals = listOf(
        "浏览5秒",
        "浏览10秒",
        "浏览15秒",
        "浏览25秒",
        "浏览30秒",
        "任务浮标源码正式页",
        "返回图标",
        "继续逛逛吧",
    )

    fun effectiveBrowsePageType(
        rawPageType: PageType,
        observation: Observation,
        phase: BrowseContextPhase,
    ): PageType {
        if (phase == BrowseContextPhase.NONE || rawPageType == PageType.TAOBAO_BROWSE_TASK) {
            return rawPageType
        }

        if (
            rawPageType != PageType.TAOBAO_HOME &&
            rawPageType != PageType.UNKNOWN_TAOBAO_PAGE
        ) {
            return rawPageType
        }

        if (observation.packageName != TAOBAO_PACKAGE) {
            return rawPageType
        }

        if (phase == BrowseContextPhase.ACTIVE) {
            return PageType.TAOBAO_BROWSE_TASK
        }

        val texts = observation.nodes
            .flatMap { node -> listOfNotNull(node.text, node.contentDescription) }
            .map { it.replace(Regex("\\s+"), "").trim() }
            .filter { it.isNotEmpty() }

        return if (browseSignals.any { signal -> texts.any { it.contains(signal) } }) {
            PageType.TAOBAO_BROWSE_TASK
        } else {
            rawPageType
        }
    }

    private const val TAOBAO_PACKAGE = "com.taobao.taobao"
}
