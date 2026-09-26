package com.coin11.taojinbi.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.Process
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.coin11.taojinbi.actions.AccessibilityActionExecutor
import com.coin11.taojinbi.actions.ActionResult
import com.coin11.taojinbi.actions.PendingActionState
import com.coin11.taojinbi.actions.PendingActionType
import com.coin11.taojinbi.capability.CapabilityState
import com.coin11.taojinbi.observation.ObservationCollector
import com.coin11.taojinbi.observation.ObserverState
import com.coin11.taojinbi.ocr.MlKitChineseOcr
import com.coin11.taojinbi.ocr.OcrSnapshot
import com.coin11.taojinbi.ocr.OcrState
import com.coin11.taojinbi.recognizer.PageRecognizer
import com.coin11.taojinbi.recognizer.PageType
import com.coin11.taojinbi.recognizer.RecognitionSnapshot
import com.coin11.taojinbi.recognizer.RecognitionState
import com.coin11.taojinbi.recognizer.RulesLoader
import com.coin11.taojinbi.task.BrowseTaskCandidateFinder

class TaojinbiAccessibilityService : AccessibilityService() {

    private val collector = ObservationCollector()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pageRecognizer: PageRecognizer
    private lateinit var actionExecutor: AccessibilityActionExecutor
    private var lastCaptureAt = 0L
    private val serviceInstanceToken = Integer.toHexString(System.identityHashCode(this))

    private val captureRunnable = Runnable {
        captureCurrentWindow()
    }

    private enum class OneBrowseStage {
        IDLE,
        WAITING_TASK_LIST,
        FINDING_TASK,
        WAITING_BROWSE_PAGE,
        BROWSING,
        RETURNING,
        DONE,
        FAILED,
    }

    private var oneBrowseStage = OneBrowseStage.IDLE
    private var oneBrowseStartedAtMillis = 0L
    private var oneBrowseStageDeadlineMillis = 0L
    private var oneBrowseNextSwipeAtMillis = 0L
    private var oneBrowseNextOcrAtMillis = 0L
    private var oneBrowseOcrInFlight = false
    private var oneBrowseEntryOcrInFlight = false
    private var oneBrowseEntryClicked = false
    private var oneBrowseSignClaimed = false
    private var oneBrowseTaskListScrolls = 0
    private var oneBrowseBackCount = 0
    private var oneBrowseLastReturnActionAtMillis = 0L
    private var oneBrowseTaskDescription = ""
    private var oneBrowseReturnShouldSucceed = true
    private var oneBrowseLastMessage = "idle"

    private val oneBrowseTick = object : Runnable {
        override fun run() {
            if (oneBrowseStage != OneBrowseStage.BROWSING) {
                return
            }

            val now = System.currentTimeMillis()
            if (now - oneBrowseStartedAtMillis >= BROWSE_DURATION_MS) {
                startOneBrowseReturn(
                    shouldSucceed = true,
                    reason = "达到30秒浏览时长",
                )
                return
            }

            if (!oneBrowseOcrInFlight && now >= oneBrowseNextOcrAtMillis) {
                oneBrowseNextOcrAtMillis = now + BROWSE_OCR_INTERVAL_MS
                runOneBrowseOcrCheck()
            }

            if (now >= oneBrowseNextSwipeAtMillis) {
                oneBrowseNextSwipeAtMillis = now + BROWSE_SWIPE_INTERVAL_MS
                swipeBrowseForOneTask()
            }

            handler.postDelayed(this, BROWSE_TICK_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo?.apply {
            flags = flags or
                AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }

        val loadedRules = RulesLoader.load(this)
        pageRecognizer = PageRecognizer(loadedRules.rules)
        actionExecutor = AccessibilityActionExecutor(this)
        RecognitionState.configureRules(
            source = loadedRules.source,
            error = loadedRules.error,
        )

        instance = this
        Log.i(
            TAG,
            "onServiceConnected instance=" + serviceInstanceToken +
                " pid=" + Process.myPid() +
                " pending=" + PendingActionState.describe(),
        )
        CapabilityState.publish(
            "Accessibility",
            buildString {
                appendLine("服务已连接。instance=" + serviceInstanceToken + " pid=" + Process.myPid())
                append("Recognizer rules：${loadedRules.source}")
                loadedRules.error?.let { append("；fallback=$it") }
            },
        )
        scheduleCapture()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        val className = event?.className?.toString()

        if (packageName == this.packageName) {
            return
        }

        ObserverState.updateEvent(
            packageName = packageName,
            className = className,
            isWindowStateChange = event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        )
        scheduleCapture()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (instance === this) {
            instance = null
        }
        val detail =
            "instance=" + serviceInstanceToken +
                " pid=" + Process.myPid() +
                " pending=" + PendingActionState.describe()
        Log.w(TAG, "onDestroy " + detail)
        CapabilityState.publish("Accessibility", "服务已销毁。" + detail)
        super.onDestroy()
    }

    private fun scheduleCapture() {
        // Do not debounce forever on pages that continuously emit content-change events.
        if (handler.hasCallbacks(captureRunnable)) {
            return
        }

        val now = System.currentTimeMillis()
        val waitMillis = (MIN_CAPTURE_INTERVAL_MS - (now - lastCaptureAt)).coerceAtLeast(0L)
        handler.postDelayed(captureRunnable, waitMillis + EVENT_SETTLE_MS)
    }

    private fun captureCurrentWindow() {
        val root = rootInActiveWindow ?: return
        val currentPackage = root.packageName?.toString()

        // Opening the capability lab itself must not overwrite the last external snapshot.
        if (currentPackage == packageName) {
            return
        }

        lastCaptureAt = System.currentTimeMillis()
        runCatching {
            collector.collect(root)
        }.onSuccess { observation ->
            val activityHint = ObserverState.latestWindowStateClassName
            val recognition = pageRecognizer.recognize(
                observation = observation,
                activityHint = activityHint,
            )
            RecognitionState.publish(
                RecognitionSnapshot(
                    observationId = observation.id,
                    recognizedAtMillis = System.currentTimeMillis(),
                    activityHint = activityHint,
                    result = recognition,
                ),
            )
            ObserverState.publish(observation)
            Log.i(
                TAG,
                "Observation #" + observation.id +
                    " valid page=" + recognition.pageType.wireName +
                    " package=" + (observation.packageName ?: "(null)"),
            )
            onOneBrowseObservation(
                observation = observation,
                pageType = recognition.pageType,
            )
            runPendingActionIfReady(
                observationId = observation.id,
                packageName = observation.packageName,
                pageType = recognition.pageType,
            )
        }.onFailure { error ->
            CapabilityState.publish("Observation 采集失败", error.stackTraceToString())
        }
    }

    private fun startOneBrowseTask(): String {
        if (
            oneBrowseStage != OneBrowseStage.IDLE &&
            oneBrowseStage != OneBrowseStage.DONE &&
            oneBrowseStage != OneBrowseStage.FAILED
        ) {
            return "rejected busy stage=" + oneBrowseStage
        }

        val observation = ObserverState.latestExternalObservation
            ?: return "rejected no Observation"
        if (!ObserverState.latestObservationValid) {
            return "rejected Observation invalid"
        }

        val recognition = RecognitionState.latest
            ?.takeIf { it.observationId == observation.id }
            ?: return "rejected no matching recognition"

        resetOneBrowseState()
        oneBrowseStartedAtMillis = System.currentTimeMillis()
        oneBrowseLog(
            "start Observation #" + observation.id +
                " page=" + recognition.result.pageType.wireName,
        )

        return when (recognition.result.pageType) {
            PageType.COIN_HOME -> {
                oneBrowseStage = OneBrowseStage.WAITING_TASK_LIST
                oneBrowseStageDeadlineMillis =
                    System.currentTimeMillis() + ENTER_TASK_LIST_TIMEOUT_MS
                enterTaskListForOneBrowse(observation)
                "accepted run_one_browse_task from coin_home"
            }

            PageType.DAILY_TASK_LIST -> {
                oneBrowseStage = OneBrowseStage.FINDING_TASK
                if (findAndClickBrowseTask(observation)) {
                    "accepted run_one_browse_task from daily_task_list"
                } else if (oneBrowseStage == OneBrowseStage.FINDING_TASK) {
                    "accepted run_one_browse_task; searching task list"
                } else {
                    "rejected no browse task candidate"
                }
            }

            else -> {
                oneBrowseStage = OneBrowseStage.FAILED
                oneBrowseLastMessage =
                    "unsupported start page=" + recognition.result.pageType.wireName
                "rejected start page=" + recognition.result.pageType.wireName
            }
        }
    }

    private fun resetOneBrowseState() {
        handler.removeCallbacks(oneBrowseTick)
        oneBrowseStage = OneBrowseStage.IDLE
        oneBrowseStageDeadlineMillis = 0L
        oneBrowseNextSwipeAtMillis = 0L
        oneBrowseNextOcrAtMillis = 0L
        oneBrowseOcrInFlight = false
        oneBrowseEntryOcrInFlight = false
        oneBrowseEntryClicked = false
        oneBrowseSignClaimed = false
        oneBrowseTaskListScrolls = 0
        oneBrowseBackCount = 0
        oneBrowseLastReturnActionAtMillis = 0L
        oneBrowseTaskDescription = ""
        oneBrowseReturnShouldSucceed = true
        oneBrowseLastMessage = "idle"
    }

    private fun onOneBrowseObservation(
        observation: com.coin11.taojinbi.observation.Observation,
        pageType: PageType,
    ) {
        when (oneBrowseStage) {
            OneBrowseStage.IDLE,
            OneBrowseStage.DONE,
            OneBrowseStage.FAILED,
            -> Unit

            OneBrowseStage.WAITING_TASK_LIST -> {
                if (pageType == PageType.DAILY_TASK_LIST) {
                    oneBrowseStage = OneBrowseStage.FINDING_TASK
                    oneBrowseLog("进入 daily_task_list Observation #" + observation.id)
                    findAndClickBrowseTask(observation)
                } else if (System.currentTimeMillis() > oneBrowseStageDeadlineMillis) {
                    failOneBrowse("进入任务列表超时，最后 page=" + pageType.wireName)
                } else if (pageType == PageType.COIN_HOME) {
                    enterTaskListForOneBrowse(observation)
                }
            }

            OneBrowseStage.FINDING_TASK -> {
                if (pageType == PageType.DAILY_TASK_LIST) {
                    findAndClickBrowseTask(observation)
                } else if (pageType == PageType.COIN_HOME) {
                    enterTaskListForOneBrowse(observation)
                }
            }

            OneBrowseStage.WAITING_BROWSE_PAGE -> {
                when (pageType) {
                    PageType.TAOBAO_BROWSE_TASK -> beginOneBrowse()
                    PageType.TASK_DONE -> startOneBrowseReturn(
                        shouldSucceed = true,
                        reason = "点击后直接出现 task_done",
                    )
                    PageType.EXTERNAL_APP,
                    PageType.QUIZ,
                    PageType.SHOP_SUBSCRIBE_TASK,
                    PageType.GOOD_SHOP_PAGE,
                    -> startOneBrowseReturn(
                        shouldSucceed = false,
                        reason = "候选进入非普通浏览页 " + pageType.wireName,
                    )
                    else -> {
                        if (System.currentTimeMillis() > oneBrowseStageDeadlineMillis) {
                            startOneBrowseReturn(
                                shouldSucceed = false,
                                reason = "点击后未进入浏览页，最后 page=" + pageType.wireName,
                            )
                        }
                    }
                }
            }

            OneBrowseStage.BROWSING -> {
                when (pageType) {
                    PageType.TASK_DONE -> startOneBrowseReturn(
                        shouldSucceed = true,
                        reason = "PageType=task_done",
                    )
                    PageType.DAILY_TASK_LIST -> completeOneBrowse(
                        success = true,
                        message = "浏览任务自动回到 daily_task_list",
                    )
                    PageType.EXTERNAL_APP,
                    PageType.QUIZ,
                    PageType.SHOP_SUBSCRIBE_TASK,
                    PageType.GOOD_SHOP_PAGE,
                    -> startOneBrowseReturn(
                        shouldSucceed = false,
                        reason = "浏览中进入非目标页 " + pageType.wireName,
                    )
                    else -> Unit
                }
            }

            OneBrowseStage.RETURNING -> {
                when (pageType) {
                    PageType.DAILY_TASK_LIST -> completeOneBrowse(
                        success = oneBrowseReturnShouldSucceed,
                        message = if (oneBrowseReturnShouldSucceed) {
                            "已返回 daily_task_list"
                        } else {
                            "已恢复 daily_task_list，但本次任务路径失败"
                        },
                    )
                    PageType.COIN_HOME -> {
                        if (canRunOneBrowseReturnAction()) {
                            oneBrowseLastReturnActionAtMillis = System.currentTimeMillis()
                            enterTaskListForOneBrowse(observation, returning = true)
                        }
                    }
                    else -> {
                        scheduleOneBrowseBackIfNeeded()
                    }
                }
            }
        }
    }

    private fun enterTaskListForOneBrowse(
        observation: com.coin11.taojinbi.observation.Observation,
        returning: Boolean = false,
    ): Boolean {
        if (!returning && oneBrowseEntryClicked) {
            return true
        }

        val entry = BrowseTaskCandidateFinder.findCoinTaskEntry(observation)
        if (entry != null) {
            val label = (entry.text ?: entry.contentDescription ?: "赚金币").trim()
            if (!returning) {
                oneBrowseStage = OneBrowseStage.WAITING_TASK_LIST
                oneBrowseStageDeadlineMillis =
                    System.currentTimeMillis() + ENTER_TASK_LIST_TIMEOUT_MS
            }
            oneBrowseLog(
                (if (returning) "返回过程中点击任务入口 " else "点击任务入口 ") +
                    label + " " + entry.bounds,
            )
            if (!returning) {
                oneBrowseEntryClicked = true
            }
            return tapBoundsForOneBrowse(entry.bounds, "one_task_entry")
        }

        if (!oneBrowseSignClaimed) {
            val signEntry = BrowseTaskCandidateFinder.findSignCoinEntry(observation)
            if (signEntry != null) {
                oneBrowseSignClaimed = true
                oneBrowseStageDeadlineMillis =
                    System.currentTimeMillis() + ENTER_TASK_LIST_TIMEOUT_MS
                oneBrowseLog(
                    "点击签到领金币，随后继续查找赚金币入口 " +
                        signEntry.bounds,
                )
                return tapBoundsForOneBrowse(
                    signEntry.bounds,
                    "one_task_sign_coin",
                )
            }
        }

        return startCoinEntryOcrFallback(returning)
    }

    private fun startCoinEntryOcrFallback(returning: Boolean): Boolean {
        if (oneBrowseEntryOcrInFlight) {
            return true
        }

        oneBrowseEntryOcrInFlight = true
        oneBrowseLog("XML未找到赚金币入口，OCR兜底查找")

        captureOcrSnapshot { result ->
            oneBrowseEntryOcrInFlight = false
            if (
                oneBrowseStage != OneBrowseStage.WAITING_TASK_LIST &&
                oneBrowseStage != OneBrowseStage.RETURNING
            ) {
                return@captureOcrSnapshot
            }

            result.onSuccess { snapshot ->
                OcrState.publish(snapshot)

                if (!oneBrowseSignClaimed) {
                    val signLine = snapshot.lines.firstOrNull { line ->
                        line.bounds != null &&
                            line.text.replace(" ", "").contains("签到领金币")
                    }
                    if (signLine?.bounds != null) {
                        oneBrowseSignClaimed = true
                        oneBrowseStageDeadlineMillis =
                            System.currentTimeMillis() + ENTER_TASK_LIST_TIMEOUT_MS
                        oneBrowseLog(
                            "OCR点击签到领金币 " + signLine.bounds,
                        )
                        tapBoundsForOneBrowse(
                            signLine.bounds,
                            "one_task_sign_coin_ocr",
                        )
                        return@onSuccess
                    }
                }

                val entryLine = snapshot.lines
                    .filter { it.bounds != null }
                    .mapNotNull { line ->
                        val compact = line.text.replace(" ", "")
                        val rank = when {
                            compact.contains("赚更多金币") -> 0
                            compact == "赚金币" -> 1
                            else -> -1
                        }
                        if (rank < 0) null else rank to line
                    }
                    .sortedWith(
                        compareBy<Pair<Int, com.coin11.taojinbi.ocr.OcrLine>> { it.first }
                            .thenBy { it.second.bounds?.top ?: Int.MAX_VALUE },
                    )
                    .firstOrNull()
                    ?.second

                if (entryLine?.bounds != null) {
                    if (!returning) {
                        oneBrowseStage = OneBrowseStage.WAITING_TASK_LIST
                        oneBrowseStageDeadlineMillis =
                            System.currentTimeMillis() + ENTER_TASK_LIST_TIMEOUT_MS
                    }
                    oneBrowseLog(
                        "OCR点击任务入口 " + entryLine.text +
                            " " + entryLine.bounds,
                    )
                    if (!returning) {
                        oneBrowseEntryClicked = true
                    }
                    tapBoundsForOneBrowse(
                        entryLine.bounds,
                        "one_task_entry_ocr",
                    )
                    return@onSuccess
                }

                val sample = snapshot.lines
                    .take(12)
                    .joinToString(" | ") { it.text }
                if (returning) {
                    oneBrowseLog(
                        "返回过程中 XML/OCR 均未找到赚金币入口；OCR=" + sample,
                    )
                } else {
                    failOneBrowse(
                        "coin_home XML/OCR均未找到赚更多金币/赚金币；OCR=" + sample,
                    )
                }
            }.onFailure { error ->
                if (returning) {
                    oneBrowseLog(
                        "返回过程中入口OCR失败 " +
                            error.javaClass.simpleName,
                    )
                } else {
                    failOneBrowse(
                        "coin_home 入口OCR失败 " +
                            error.javaClass.simpleName,
                    )
                }
            }
        }
        return true
    }

    private fun findAndClickBrowseTask(
        observation: com.coin11.taojinbi.observation.Observation,
    ): Boolean {
        val candidate = BrowseTaskCandidateFinder.findBrowseTask(observation)
        if (candidate != null) {
            oneBrowseTaskDescription =
                candidate.buttonText + " | " + candidate.contextText.take(120)
            oneBrowseStage = OneBrowseStage.WAITING_BROWSE_PAGE
            oneBrowseStageDeadlineMillis =
                System.currentTimeMillis() + ENTER_BROWSE_TIMEOUT_MS
            oneBrowseLog(
                "点击浏览任务 " + oneBrowseTaskDescription +
                    " bounds=" + candidate.bounds,
            )
            return tapBoundsForOneBrowse(candidate.bounds, "one_task_candidate")
        }

        if (oneBrowseTaskListScrolls >= MAX_TASK_LIST_SCROLLS) {
            failOneBrowse("任务列表连续下翻后仍没有安全的普通浏览任务候选")
            return false
        }

        oneBrowseTaskListScrolls += 1
        oneBrowseLog(
            "当前屏无普通浏览任务候选，下翻 " +
                oneBrowseTaskListScrolls + "/" + MAX_TASK_LIST_SCROLLS,
        )
        swipeTaskListForOneBrowse()
        return false
    }

    private fun beginOneBrowse() {
        if (oneBrowseStage == OneBrowseStage.BROWSING) {
            return
        }
        oneBrowseStage = OneBrowseStage.BROWSING
        oneBrowseStartedAtMillis = System.currentTimeMillis()
        oneBrowseNextSwipeAtMillis = oneBrowseStartedAtMillis
        oneBrowseNextOcrAtMillis =
            oneBrowseStartedAtMillis + BROWSE_FIRST_OCR_DELAY_MS
        oneBrowseOcrInFlight = false
        oneBrowseLog("进入 taobao_browse_task，开始30秒浏览")
        handler.removeCallbacks(oneBrowseTick)
        handler.post(oneBrowseTick)
    }

    private fun swipeTaskListForOneBrowse() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val x = maxOf(30, (width * 0.06f).toInt())
        val queued = actionExecutor.swipe(
            startX = x,
            startY = (height * 0.84f).toInt(),
            endX = x,
            endY = (height * 0.32f).toInt(),
            durationMs = 450L,
        ) { result ->
            publishActionResult("v0.4 TaskList Swipe", result)
        }
        if (queued) {
            invalidateObservationForAction("one_task_list_swipe")
        }
    }

    private fun swipeBrowseForOneTask() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val x = (width * 0.36f).toInt()
        val queued = actionExecutor.swipe(
            startX = x,
            startY = (height * 0.78f).toInt(),
            endX = (width * 0.32f).toInt(),
            endY = (height * 0.34f).toInt(),
            durationMs = 350L,
        ) { result ->
            publishActionResult("v0.4 Browse Swipe", result)
        }
        if (queued) {
            invalidateObservationForAction("one_task_browse_swipe")
        }
    }

    private fun runOneBrowseOcrCheck() {
        if (oneBrowseStage != OneBrowseStage.BROWSING || oneBrowseOcrInFlight) {
            return
        }

        oneBrowseOcrInFlight = true
        captureOcrSnapshot { result ->
            oneBrowseOcrInFlight = false
            if (oneBrowseStage != OneBrowseStage.BROWSING) {
                return@captureOcrSnapshot
            }

            result.onSuccess { snapshot ->
                OcrState.publish(snapshot)
                val done = ocrShowsBrowseDone(snapshot)
                oneBrowseLog(
                    "OCR " + snapshot.recognitionElapsedMillis +
                        "ms / " + snapshot.lines.size +
                        " lines / done=" + done,
                )
                if (done) {
                    startOneBrowseReturn(
                        shouldSucceed = true,
                        reason = "OCR检测到任务完成",
                    )
                }
            }.onFailure { error ->
                oneBrowseLog("OCR检查失败 " + error.javaClass.simpleName)
            }
        }
    }

    private fun ocrShowsBrowseDone(snapshot: OcrSnapshot): Boolean {
        for (line in snapshot.lines) {
            val text = line.text.replace(" ", "")
            if (text.contains("任务已完成") || text.contains("继续逛逛吧")) {
                return true
            }
            if (
                text.contains("已得") &&
                !text.contains("累计已得") &&
                !text.contains("累积已得")
            ) {
                return true
            }
        }
        return false
    }

    private fun startOneBrowseReturn(
        shouldSucceed: Boolean,
        reason: String,
    ) {
        if (
            oneBrowseStage == OneBrowseStage.RETURNING ||
            oneBrowseStage == OneBrowseStage.DONE ||
            oneBrowseStage == OneBrowseStage.FAILED
        ) {
            return
        }

        handler.removeCallbacks(oneBrowseTick)
        oneBrowseReturnShouldSucceed = shouldSucceed
        oneBrowseStage = OneBrowseStage.RETURNING
        oneBrowseStageDeadlineMillis =
            System.currentTimeMillis() + RETURN_TIMEOUT_MS
        oneBrowseBackCount = 0
        oneBrowseLastReturnActionAtMillis = 0L
        oneBrowseLog("开始返回任务列表：" + reason)
        scheduleOneBrowseBackIfNeeded()
    }

    private fun scheduleOneBrowseBackIfNeeded() {
        if (oneBrowseStage != OneBrowseStage.RETURNING) {
            return
        }

        val now = System.currentTimeMillis()
        if (now > oneBrowseStageDeadlineMillis) {
            completeOneBrowse(
                success = false,
                message = "返回任务列表超时",
            )
            return
        }
        if (!canRunOneBrowseReturnAction()) {
            return
        }
        if (oneBrowseBackCount >= MAX_RETURN_BACKS) {
            completeOneBrowse(
                success = false,
                message = "连续 Back 后仍未返回任务列表",
            )
            return
        }

        oneBrowseLastReturnActionAtMillis = now
        oneBrowseBackCount += 1
        handler.postDelayed(
            {
                if (oneBrowseStage == OneBrowseStage.RETURNING) {
                    oneBrowseLog("Back #" + oneBrowseBackCount)
                    globalBack()
                }
            },
            RETURN_BACK_SETTLE_MS,
        )
    }

    private fun canRunOneBrowseReturnAction(): Boolean =
        System.currentTimeMillis() - oneBrowseLastReturnActionAtMillis >=
            RETURN_ACTION_MIN_INTERVAL_MS

    private fun tapBoundsForOneBrowse(
        bounds: com.coin11.taojinbi.observation.IntRect,
        reason: String,
    ): Boolean {
        val queued = actionExecutor.tap(bounds) { result ->
            publishActionResult("v0.4 Tap", result)
        }
        if (queued) {
            invalidateObservationForAction(reason)
        }
        return queued
    }

    private fun invalidateObservationForAction(reason: String) {
        ObserverState.invalidate(reason)
        Log.i(TAG, "Observation invalidated reason=" + reason)
    }

    private fun completeOneBrowse(
        success: Boolean,
        message: String,
    ) {
        handler.removeCallbacks(oneBrowseTick)
        oneBrowseStage =
            if (success) OneBrowseStage.DONE else OneBrowseStage.FAILED
        oneBrowseLastMessage = message
        oneBrowseLog(
            (if (success) "DONE " else "FAILED ") + message,
        )
    }

    private fun failOneBrowse(message: String) {
        completeOneBrowse(success = false, message = message)
    }

    private fun oneBrowseLog(message: String) {
        oneBrowseLastMessage = message
        Log.i(ONE_TASK_TAG, message)
        CapabilityState.publish("v0.4 OneTask", message)
    }

    private fun oneBrowseStatusText(): String =
        buildString {
            append("stage=")
            append(oneBrowseStage)
            append(" message=")
            append(oneBrowseLastMessage)
            if (oneBrowseTaskDescription.isNotBlank()) {
                append(" task=")
                append(oneBrowseTaskDescription)
            }
            append(" listScrolls=")
            append(oneBrowseTaskListScrolls)
            append(" backs=")
            append(oneBrowseBackCount)
        }

    private fun runPendingActionIfReady(
        observationId: Long,
        packageName: String?,
        pageType: PageType,
    ) {
        val request = PendingActionState.consumeIf { pending ->
            pending.targetPackage == packageName &&
                pageType == PageType.COIN_HOME
        } ?: return

        CapabilityState.publish(
            "Action 测试",
            "Observation #" + observationId +
                " / " + pageType.wireName +
                "，执行 #" + request.id + " " + request.type,
        )

        when (request.type) {
            PendingActionType.TAP_CENTER -> tapCenter()
            PendingActionType.SWIPE_UP -> swipeUp()
            PendingActionType.BACK -> globalBack()
        }
    }

    private fun tapCenter() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val x = (width * 0.5f).toInt()
        val y = (height * 0.5f).toInt()

        val queued = actionExecutor.tap(x, y) { result ->
            publishActionResult("Accessibility Tap", result)
        }
        if (queued) {
            val reason = "tap(" + x + "," + y + ")"
            ObserverState.invalidate(reason)
            Log.i(TAG, "Observation invalidated reason=" + reason)
        }
    }

    private fun swipeUp() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val x = (width * 0.5f).toInt()
        val startY = (height * 0.75f).toInt()
        val endY = (height * 0.35f).toInt()

        val queued = actionExecutor.swipe(
            startX = x,
            startY = startY,
            endX = x,
            endY = endY,
        ) { result ->
            publishActionResult("Accessibility Swipe", result)
        }
        if (queued) {
            ObserverState.invalidate("swipe")
            Log.i(TAG, "Observation invalidated reason=swipe")
        }
    }

    private fun globalBack() {
        val result = actionExecutor.back()
        publishActionResult("Accessibility Back", result)
        if (result.success) {
            ObserverState.invalidate("back")
            Log.i(TAG, "Observation invalidated reason=back")
        }
    }

    private fun publishActionResult(label: String, result: ActionResult) {
        val message = if (result.success) {
            "成功：" + result.detail
        } else {
            "失败：" + result.detail
        }
        Log.i(TAG, label + " " + message)
        CapabilityState.publish(label, message)
    }

    private fun captureOcrSnapshot(
        callback: (Result<OcrSnapshot>) -> Unit,
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        if (currentPackage == packageName) {
            callback(Result.failure(IllegalStateException("前台是调试 App")))
            return
        }

        val sourceObservation = ObserverState.latestExternalObservation
            ?.takeIf {
                ObserverState.latestObservationValid &&
                    (currentPackage.isNullOrBlank() || it.packageName == currentPackage)
            }
        val observationId = sourceObservation?.id

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            mainExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    val screenshotElapsed = SystemClock.elapsedRealtime() - startedAt
                    val hardwareBuffer = screenshot.hardwareBuffer
                    val wrapped = Bitmap.wrapHardwareBuffer(
                        hardwareBuffer,
                        screenshot.colorSpace,
                    )
                    val bitmap = wrapped?.copy(Bitmap.Config.ARGB_8888, false)
                    hardwareBuffer.close()

                    if (bitmap == null) {
                        callback(
                            Result.failure(
                                IllegalStateException("Bitmap.wrapHardwareBuffer 失败"),
                            ),
                        )
                        return
                    }

                    MlKitChineseOcr.recognize(bitmap) { result ->
                        val mapped = result.map { recognition ->
                            OcrSnapshot(
                                observationId = observationId,
                                capturedAtMillis = System.currentTimeMillis(),
                                screenshotWidth = bitmap.width,
                                screenshotHeight = bitmap.height,
                                screenshotElapsedMillis = screenshotElapsed,
                                recognitionElapsedMillis = recognition.elapsedMillis,
                                lines = recognition.lines,
                            )
                        }
                        bitmap.recycle()
                        callback(mapped)
                    }
                }

                override fun onFailure(errorCode: Int) {
                    callback(
                        Result.failure(
                            IllegalStateException(
                                "takeScreenshot errorCode=" + errorCode,
                            ),
                        ),
                    )
                }
            },
        )
    }

    private fun screenshotAndOcr() {
        captureOcrSnapshot { result ->
            result.onSuccess { snapshot ->
                OcrState.publish(snapshot)
                CapabilityState.publish(
                    "ML Kit 中文 OCR",
                    snapshot.debugText(maxLines = 80),
                )
            }.onFailure { error ->
                CapabilityState.publish(
                    "ML Kit 中文 OCR 失败",
                    error.stackTraceToString(),
                )
            }
        }
    }

    private fun schedule(label: String, delayMs: Long, action: () -> Unit) {
        CapabilityState.publish(label, "已排队，将在 ${delayMs}ms 后执行。")
        handler.postDelayed(
            {
                runCatching(action)
                    .onFailure { error ->
                        CapabilityState.publish(label, error.stackTraceToString())
                    }
            },
            delayMs,
        )
    }

    companion object {
        private const val TAG = "TaojinbiAccessibility"
        private const val ONE_TASK_TAG = "TaojinbiOneTask"
        private const val TAOBAO_PACKAGE = "com.taobao.taobao"
        private const val MIN_CAPTURE_INTERVAL_MS = 350L
        private const val EVENT_SETTLE_MS = 120L

        private const val ENTER_TASK_LIST_TIMEOUT_MS = 8_000L
        private const val ENTER_BROWSE_TIMEOUT_MS = 6_000L
        private const val BROWSE_DURATION_MS = 30_000L
        private const val BROWSE_FIRST_OCR_DELAY_MS = 8_000L
        private const val BROWSE_OCR_INTERVAL_MS = 2_000L
        private const val BROWSE_SWIPE_INTERVAL_MS = 800L
        private const val BROWSE_TICK_MS = 200L
        private const val RETURN_TIMEOUT_MS = 12_000L
        private const val RETURN_ACTION_MIN_INTERVAL_MS = 900L
        private const val RETURN_BACK_SETTLE_MS = 450L
        private const val MAX_TASK_LIST_SCROLLS = 4
        private const val MAX_RETURN_BACKS = 5

        @Volatile
        private var instance: TaojinbiAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun debugTapCenterNow(): Boolean =
            instance?.let { service ->
                service.tapCenter()
                true
            } ?: false

        fun debugSwipeUpNow(): Boolean =
            instance?.let { service ->
                service.swipeUp()
                true
            } ?: false

        fun debugBackNow(): Boolean =
            instance?.let { service ->
                service.globalBack()
                true
            } ?: false

        fun debugStatusText(): String {
            val observation = ObserverState.latestExternalObservation
            val recognition = RecognitionState.latest
            return buildString {
                append("service=")
                append(if (instance != null) "connected" else "disconnected")
                instance?.let {
                    append(" instance=")
                    append(it.serviceInstanceToken)
                    append(" pid=")
                    append(Process.myPid())
                }
                append(" observation=")
                append(observation?.id?.let { "#" + it } ?: "(none)")
                append(" valid=")
                append(ObserverState.latestObservationValid)
                append(" package=")
                append(observation?.packageName ?: "(null)")
                append(" page=")
                append(recognition?.result?.pageType?.wireName ?: "(none)")
                ObserverState.invalidationReason?.let {
                    append(" invalidation=")
                    append(it)
                }
                append(" oneTask={")
                append(instance?.oneBrowseStatusText() ?: "service unavailable")
                append("}")
            }
        }

        fun debugStartOneBrowseTask(): String =
            instance?.startOneBrowseTask()
                ?: "rejected run_one_browse_task: accessibility service not connected"

        private fun armActionOnNextCoinObservation(type: PendingActionType): Boolean {
            if (instance == null) {
                return false
            }

            val request = PendingActionState.arm(
                type = type,
                targetPackage = TAOBAO_PACKAGE,
            )
            CapabilityState.publish(
                "Action 测试",
                "已等待下一次 coin_home Observation：#" + request.id + " " + request.type,
            )
            return true
        }

        fun armTapCenterOnNextCoinObservation(): Boolean =
            armActionOnNextCoinObservation(PendingActionType.TAP_CENTER)

        fun armSwipeUpOnNextCoinObservation(): Boolean =
            armActionOnNextCoinObservation(PendingActionType.SWIPE_UP)

        fun armBackOnNextCoinObservation(): Boolean =
            armActionOnNextCoinObservation(PendingActionType.BACK)

        fun scheduleScreenshotAndOcr(delayMs: Long = 2200L): Boolean =
            instance?.let { service ->
                service.schedule("Screenshot + OCR 测试", delayMs, service::screenshotAndOcr)
                true
            } ?: false
    }
}
