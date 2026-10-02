package com.coin11.taojinbi.task

import com.coin11.taojinbi.observation.IntRect
import com.coin11.taojinbi.observation.Observation

object ExternalLinkDialogPolicy {
    private const val TAOBAO_PACKAGE = "com.taobao.taobao"

    fun findCancelBounds(observation: Observation): IntRect? {
        if (observation.packageName != TAOBAO_PACKAGE) {
            return null
        }

        val texts = observation.nodes
            .map { node ->
                (node.text ?: node.contentDescription ?: "").trim()
            }
            .filter { it.isNotBlank() }

        val hasBrowserOpen = texts.any { text ->
            text.contains("浏览器打开") ||
                text.contains("用浏览器打开") ||
                text.contains("浏览器中打开")
        }
        if (!hasBrowserOpen) {
            return null
        }

        return observation.nodes
            .asSequence()
            .filter { it.enabled }
            .filter { node ->
                val text =
                    (node.text ?: node.contentDescription ?: "").trim()
                text == "取消" || text.endsWith("取消")
            }
            .map { it.bounds }
            .filter { it.right > it.left && it.bottom > it.top }
            .sortedByDescending { it.bottom - it.top }
            .firstOrNull()
    }
}
