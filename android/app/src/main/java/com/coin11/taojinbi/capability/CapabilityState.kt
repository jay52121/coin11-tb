package com.coin11.taojinbi.capability

import android.text.format.DateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet

data class CapabilityLog(
    val capturedAtMillis: Long,
    val title: String,
    val detail: String,
)

object CapabilityState {
    private const val MAX_LOGS = 40
    private const val MAX_DETAIL_CHARS = 12000

    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val logs = ArrayDeque<CapabilityLog>()

    @Synchronized
    fun publish(title: String, detail: String) {
        val normalized = if (detail.length <= MAX_DETAIL_CHARS) {
            detail
        } else {
            detail.take(MAX_DETAIL_CHARS) + "\n…[truncated]"
        }

        logs.addFirst(
            CapabilityLog(
                capturedAtMillis = System.currentTimeMillis(),
                title = title,
                detail = normalized,
            ),
        )

        while (logs.size > MAX_LOGS) {
            logs.removeLast()
        }

        listeners.forEach { listener ->
            runCatching(listener)
        }
    }

    @Synchronized
    fun render(): String {
        if (logs.isEmpty()) {
            return "暂无技术验证结果。"
        }

        return buildString {
            logs.forEachIndexed { index, item ->
                if (index > 0) appendLine()
                val at = DateFormat.format("HH:mm:ss", Date(item.capturedAtMillis))
                appendLine("[$at] ${item.title}")
                append(item.detail)
                if (!item.detail.endsWith("\n")) appendLine()
            }
        }.trimEnd()
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}
