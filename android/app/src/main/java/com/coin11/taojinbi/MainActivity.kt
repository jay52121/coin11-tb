package com.coin11.taojinbi

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.coin11.taojinbi.accessibility.TaojinbiAccessibilityService
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import com.coin11.taojinbi.observation.ObserverState
import java.util.Date

class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var snapshotSummary: TextView
    private lateinit var nodeDump: TextView

    private val observationListener: (Observation?) -> Unit = { observation ->
        runOnUiThread {
            render(observation)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "淘金币 Android Observer"
        setContentView(buildContent())
    }

    override fun onResume() {
        super.onResume()
        ObserverState.addListener(observationListener)
        render(ObserverState.latestExternalObservation)
    }

    override fun onPause() {
        ObserverState.removeListener(observationListener)
        super.onPause()
    }

    private fun buildContent(): ScrollView {
        val scrollView = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }

        content.addView(TextView(this).apply {
            text = "淘金币 Android Observer · v0.1"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        content.addView(TextView(this).apply {
            text = "只观察，不点击。先切到淘宝浏览几个页面，再回到这里查看最后一次外部页面快照。"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(14))
        })

        serviceStatus = TextView(this).apply {
            textSize = 16f
        }
        content.addView(serviceStatus)

        content.addView(Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        })

        content.addView(TextView(this).apply {
            text = "最后一次外部页面观察"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })

        snapshotSummary = TextView(this).apply {
            textSize = 15f
            setTextIsSelectable(true)
        }
        content.addView(snapshotSummary)

        content.addView(TextView(this).apply {
            text = "节点（优先显示有 text / description / viewId 的节点）"
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })

        nodeDump = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            gravity = Gravity.START
        }
        content.addView(
            nodeDump,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        scrollView.addView(content)
        return scrollView
    }

    private fun render(observation: Observation?) {
        serviceStatus.text = if (TaojinbiAccessibilityService.isRunning()) {
            "Accessibility：已连接 ✓"
        } else {
            "Accessibility：未连接。请先在系统设置里开启“淘金币页面观察器”。"
        }

        if (observation == null) {
            snapshotSummary.text = "暂无外部页面快照。开启无障碍后切到淘宝，再回到本 App。"
            nodeDump.text = ""
            return
        }

        val capturedAt = DateFormat.format(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Date(observation.capturedAtMillis),
        )

        snapshotSummary.text = buildString {
            appendLine("Observation #${observation.id}")
            appendLine("时间：$capturedAt")
            appendLine("package：${observation.packageName ?: "(null)"}")
            appendLine("windowId：${observation.windowId ?: -1}")
            appendLine("节点总数：${observation.nodes.size}")
            appendLine("有效信息节点：${observation.interestingNodeCount}")
            append("是否截断：${if (observation.truncated) "是（达到节点上限）" else "否"}")
        }

        val interesting = observation.nodes.filter(::isInteresting)
        val shown = interesting.take(MAX_DISPLAY_NODES)

        nodeDump.text = buildString {
            shown.forEach { node ->
                appendNode(node)
                appendLine()
            }
            if (interesting.size > shown.size) {
                appendLine()
                append("……还有 ${interesting.size - shown.size} 个有效信息节点未显示")
            }
        }
    }

    private fun isInteresting(node: NodeSnapshot): Boolean =
        !node.text.isNullOrBlank() ||
            !node.contentDescription.isNullOrBlank() ||
            !node.viewId.isNullOrBlank()

    private fun StringBuilder.appendNode(node: NodeSnapshot) {
        append("#${node.index} d=${node.depth} ${node.bounds}")
        if (node.clickable) append(" C")
        if (node.scrollable) append(" S")
        if (!node.enabled) append(" DISABLED")
        appendLine()

        if (!node.text.isNullOrBlank()) {
            appendLine("  text=${node.text}")
        }
        if (!node.contentDescription.isNullOrBlank()) {
            appendLine("  desc=${node.contentDescription}")
        }
        if (!node.viewId.isNullOrBlank()) {
            appendLine("  id=${node.viewId}")
        }
        if (!node.className.isNullOrBlank()) {
            append("  class=${node.className}")
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_DISPLAY_NODES = 180
    }
}
