package com.coin11.taojinbi

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
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
import com.coin11.taojinbi.capability.CapabilityState
import com.coin11.taojinbi.observation.NodeSnapshot
import com.coin11.taojinbi.observation.Observation
import com.coin11.taojinbi.observation.ObserverState
import com.coin11.taojinbi.recognizer.PageRecognizer
import com.coin11.taojinbi.recognizer.RulesLoader
import com.coin11.taojinbi.shizuku.ShizukuBridge
import rikka.shizuku.Shizuku
import java.util.Date

class MainActivity : Activity() {

    private lateinit var serviceStatus: TextView
    private lateinit var shizukuStatus: TextView
    private lateinit var capabilityOutput: TextView
    private lateinit var recognitionOutput: TextView
    private lateinit var snapshotSummary: TextView

    private lateinit var pageRecognizer: PageRecognizer
    private lateinit var rulesSource: String
    private var rulesError: String? = null
    private lateinit var nodeDump: TextView

    private val observationListener: (Observation?) -> Unit = { observation ->
        runOnUiThread {
            render(observation)
        }
    }

    private val capabilityListener: () -> Unit = {
        runOnUiThread {
            render(ObserverState.latestExternalObservation)
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        CapabilityState.publish("Shizuku", "binder 已连接。")
        render(ObserverState.latestExternalObservation)
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        CapabilityState.publish("Shizuku", "binder 已断开。")
        render(ObserverState.latestExternalObservation)
    }

    private val shizukuPermissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == ShizukuBridge.REQUEST_CODE) {
                CapabilityState.publish(
                    "Shizuku 授权结果",
                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        "GRANTED"
                    } else {
                        "DENIED"
                    },
                )
                render(ObserverState.latestExternalObservation)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "淘金币 Android Page Recognizer"

        val loadedRules = RulesLoader.load(this)
        pageRecognizer = PageRecognizer(loadedRules.rules)
        rulesSource = loadedRules.source
        rulesError = loadedRules.error

        setContentView(buildContent())

        Shizuku.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        Shizuku.addBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)
    }

    override fun onResume() {
        super.onResume()
        ObserverState.addListener(observationListener)
        CapabilityState.addListener(capabilityListener)
        render(ObserverState.latestExternalObservation)
    }

    override fun onPause() {
        CapabilityState.removeListener(capabilityListener)
        ObserverState.removeListener(observationListener)
        super.onPause()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        Shizuku.removeBinderDeadListener(shizukuBinderDeadListener)
        Shizuku.removeBinderReceivedListener(shizukuBinderReceivedListener)
        super.onDestroy()
    }

    private fun buildContent(): ScrollView {
        val scrollView = ScrollView(this)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(18), dp(16), dp(28))
        }

        content.addView(TextView(this).apply {
            text = "淘金币 Android · 0.2 页面识别"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })

        content.addView(TextView(this).apply {
            text = "当前只做 Observation → PageType，不运行任务、不自动操作。下方保留 0.1T 手动技术工具。"
            textSize = 15f
            setPadding(0, dp(8), 0, dp(14))
        })

        serviceStatus = TextView(this).apply { textSize = 15f }
        content.addView(serviceStatus)

        shizukuStatus = TextView(this).apply {
            textSize = 15f
            setPadding(0, dp(6), 0, dp(10))
        }
        content.addView(shizukuStatus)

        addButton(content, "打开无障碍设置") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        addSectionTitle(content, "页面识别（v0.2）")

        recognitionOutput = TextView(this).apply {
            textSize = 15f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        content.addView(recognitionOutput)

        addSectionTitle(content, "0.1T 手动技术工具")

        addSectionTitle(content, "标准 Android / Accessibility")

        addButton(content, "打开淘宝首页") {
            openTaobaoHome()
        }

        addButton(content, "打开淘金币 Deep Link") {
            openCoinHome()
        }

        addButton(content, "淘金币 → 2 秒后截图 + 中文 OCR") {
            scheduleAccessibilityTest(
                scheduler = TaojinbiAccessibilityService::scheduleScreenshotAndOcr,
            )
        }

        addButton(content, "淘金币 → 2 秒后 Swipe Up") {
            scheduleAccessibilityTest(
                scheduler = TaojinbiAccessibilityService::scheduleSwipeUp,
            )
        }

        addButton(content, "淘金币 → 2 秒后 Tap 屏幕中心") {
            scheduleAccessibilityTest(
                scheduler = TaojinbiAccessibilityService::scheduleTapCenter,
            )
        }

        addButton(content, "淘金币 → 2 秒后 Global Back") {
            scheduleAccessibilityTest(
                scheduler = TaojinbiAccessibilityService::scheduleBack,
            )
        }

        addSectionTitle(content, "Shizuku / 高权限能力")

        addButton(content, "刷新 Shizuku 状态") {
            CapabilityState.publish("Shizuku 状态", ShizukuBridge.statusText())
        }

        addButton(content, "请求 Shizuku 授权") {
            ShizukuBridge.requestPermission()
        }

        addButton(content, "Shizuku 基础检查：id / users / 前台 Activity") {
            ShizukuBridge.runProbeSuite()
        }

        addButton(content, "只查询前台 Activity") {
            ShizukuBridge.queryForegroundActivity()
        }

        addButton(content, "force-stop 淘宝 · user 0") {
            ShizukuBridge.forceStopTaobao(0)
        }

        addButton(content, "启动淘金币 · user 999") {
            ShizukuBridge.openCoinAsUser(999, COIN_HOME_URL)
        }

        addButton(content, "force-stop 淘宝 · user 999") {
            ShizukuBridge.forceStopTaobao(999)
        }

        addSectionTitle(content, "技术验证结果")

        capabilityOutput = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        content.addView(capabilityOutput)

        addSectionTitle(content, "最后一次外部页面 Observation")

        snapshotSummary = TextView(this).apply {
            textSize = 14f
            setTextIsSelectable(true)
        }
        content.addView(snapshotSummary)

        addSectionTitle(content, "节点样本")

        nodeDump = TextView(this).apply {
            textSize = 11f
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

    private fun addSectionTitle(content: LinearLayout, title: String) {
        content.addView(TextView(this).apply {
            text = title
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(18), 0, dp(6))
        })
    }

    private fun addButton(
        content: LinearLayout,
        label: String,
        onClick: () -> Unit,
    ) {
        content.addView(Button(this).apply {
            text = label
            setOnClickListener {
                runCatching(onClick)
                    .onFailure { error ->
                        CapabilityState.publish(label, error.stackTraceToString())
                    }
            }
        })
    }

    private fun scheduleAccessibilityTest(
        scheduler: (Long) -> Boolean,
    ) {
        if (!TaojinbiAccessibilityService.isRunning()) {
            CapabilityState.publish(
                "Accessibility 测试",
                "无障碍服务未连接，无法执行。",
            )
            return
        }

        val scheduled = scheduler(2200L)
        if (!scheduled) {
            CapabilityState.publish(
                "Accessibility 测试",
                "动作没有成功排队。",
            )
            return
        }

        openCoinHome()
    }

    private fun openTaobaoHome() {
        val intent = packageManager.getLaunchIntentForPackage(TAOBAO_PACKAGE)
        if (intent == null) {
            CapabilityState.publish("启动淘宝", "找不到淘宝启动 Intent。")
            return
        }

        startActivity(intent)
        CapabilityState.publish("启动淘宝", "已发送淘宝启动 Intent。")
    }

    private fun openCoinHome() {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(COIN_HOME_URL),
        ).apply {
            setPackage(TAOBAO_PACKAGE)
        }

        runCatching {
            startActivity(intent)
        }.onSuccess {
            CapabilityState.publish("淘金币 Deep Link", "已发送 ACTION_VIEW。")
        }.onFailure { error ->
            CapabilityState.publish(
                "淘金币 Deep Link 失败",
                error.stackTraceToString(),
            )
        }
    }

    private fun render(observation: Observation?) {
        serviceStatus.text = if (TaojinbiAccessibilityService.isRunning()) {
            "Accessibility：已连接 ✓"
        } else {
            "Accessibility：未连接"
        }

        shizukuStatus.text = "Shizuku：\n${ShizukuBridge.statusText()}"
        capabilityOutput.text = CapabilityState.render()

        if (observation == null) {
            recognitionOutput.text = buildString {
                appendLine("Page：等待 Observation")
                appendLine("Rules：$rulesSource")
                rulesError?.let { append("Rules fallback：$it") }
            }.trimEnd()
            snapshotSummary.text = "暂无外部页面快照。"
            nodeDump.text = ""
            return
        }

        val recognition = pageRecognizer.recognize(
            observation = observation,
            activityHint = ObserverState.latestWindowStateClassName,
        )
        recognitionOutput.text = buildString {
            appendLine(recognition.debugText())
            appendLine("Activity hint：${ObserverState.latestWindowStateClassName ?: "(none)"}")
            appendLine("Rules：$rulesSource")
            rulesError?.let { append("Rules fallback：$it") }
        }.trimEnd()

        val capturedAt = DateFormat.format(
            "yyyy-MM-dd HH:mm:ss.SSS",
            Date(observation.capturedAtMillis),
        )

        snapshotSummary.text = buildString {
            appendLine("Observation #${observation.id}")
            appendLine("时间：$capturedAt")
            appendLine("package：${observation.packageName ?: "(null)"}")
            appendLine("windowId：${observation.windowId ?: -1}")
            appendLine("最后 event package：${ObserverState.latestEventPackageName ?: "(null)"}")
            appendLine("最后 event class：${ObserverState.latestEventClassName ?: "(null)"}")
            appendLine("window-state class：${ObserverState.latestWindowStateClassName ?: "(null)"}")
            appendLine("节点总数：${observation.nodes.size}")
            appendLine("有效信息节点：${observation.interestingNodeCount}")
            append("是否截断：${if (observation.truncated) "是" else "否"}")
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

        if (!node.text.isNullOrBlank()) appendLine("  text=${node.text}")
        if (!node.contentDescription.isNullOrBlank()) appendLine("  desc=${node.contentDescription}")
        if (!node.viewId.isNullOrBlank()) appendLine("  id=${node.viewId}")
        if (!node.className.isNullOrBlank()) append("  class=${node.className}")
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val MAX_DISPLAY_NODES = 120
        private const val TAOBAO_PACKAGE = "com.taobao.taobao"

        private const val COIN_HOME_URL =
            "https://pages-fast.m.taobao.com/wow/z/tmtjb/town/home?utparam=%7B%22ranger_buckets_native%22%3A%22tsp6443_32421_standardVersion%22%7D&spm=a2141.1.iconsv5.5&miniappSourceChannel=homepage&scm=1007.home_icon.lingjb.d&x-ssr=true&disableNav=YES&x-sec=wua&pha_h5=true&pha_nav=true&uniapp_id=1011525&uniapp_page=home&hd_from=tbHome"
    }
}
