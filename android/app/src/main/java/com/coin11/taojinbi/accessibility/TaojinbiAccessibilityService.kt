package com.coin11.taojinbi.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
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
import com.coin11.taojinbi.recognizer.RuleSet
import com.coin11.taojinbi.recognizer.RulesLoader
import com.coin11.taojinbi.task.BrowseContextPhase
import com.coin11.taojinbi.task.BrowseTaskCandidateFinder
import com.coin11.taojinbi.task.CoinTaskCandidateFinder
import com.coin11.taojinbi.task.CoinTaskKind
import com.coin11.taojinbi.task.TaskPageContext
import com.coin11.taojinbi.shizuku.ShizukuBridge

class TaojinbiAccessibilityService : AccessibilityService() {

    private val collector = ObservationCollector()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pageRecognizer: PageRecognizer
    private lateinit var actionExecutor: AccessibilityActionExecutor
    private lateinit var rules: RuleSet
    private var lastCaptureAt = 0L
    private val serviceInstanceToken = Integer.toHexString(System.identityHashCode(this))

    private val captureRunnable = Runnable {
        captureCurrentWindow()
    }

    private enum class OneBrowseStage {
        IDLE,
        FINDING_COIN_ENTRY,
        WAITING_SIGN_ENTRY,
        WAITING_TASK_LIST,
        WAITING_DAILY_ENTRY,
        FINDING_TASK,
        WAITING_BROWSE_PAGE,
        WAITING_REWARD_RESULT,
        BROWSING,
        EXTERNAL_TASK,
        EXTERNAL_RECOVERING,
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
    private enum class CoinEntryKind {
        EARN_MORE,
        EARN,
    }

    private var oneBrowseEntryOcrInFlight = false
    private var oneBrowseEntryClicked = false
    private var oneBrowseSignClaimed = false
    private var oneBrowseDailyFallbackClicked = false
    private var oneBrowseEntryRetryCount = 0
    private var oneBrowseDailyReadyAtMillis = 0L
    private var oneBrowseLastEntryKind: CoinEntryKind? = null
    private var oneBrowseTaskListScrolls = 0
    private var oneBrowseBackCount = 0
    private var oneBrowseLastReturnActionAtMillis = 0L
    private var oneBrowseReturnLastObservationId: Long? = null
    private var oneBrowseReturnSameObservationTicks = 0
    private var oneBrowseTaskDescription = ""
    private var oneBrowseReturnShouldSucceed = true
    private var oneBrowseLastMessage = "idle"
    private var coinMainlineMode = false
    private val handledCoinTaskKeys = linkedSetOf<String>()
    private var currentCoinTaskKey = ""
    private var supportedCoinTasksCompleted = 0
    private var skippedCoinTasks = 0
    private var coinMainlineTargetUserId = -1

    private data class ExternalTaskSession(
        val packageName: String,
        val taskKey: String,
        val taskDescription: String,
        val startedAtMillis: Long,
        val userId: Int,
    )

    private var externalTaskSession: ExternalTaskSession? = null
    private var externalTaskSwipeCount = 0
    private var externalRecoveryBackCount = 0
    private var externalRecoveryFallbackLaunched = false
    private var externalRecoveryPendingCompletion = false

    private val coinEntryWaitRunnable = Runnable {
        runCoinEntryWaitTick()
    }

    private val returnWatchdog = object : Runnable {
        override fun run() {
            if (oneBrowseStage != OneBrowseStage.RETURNING) {
                return
            }
            runReturnWatchdogTick()
            if (oneBrowseStage == OneBrowseStage.RETURNING) {
                handler.postDelayed(this, RETURN_WATCHDOG_INTERVAL_MS)
            }
        }
    }

    private val externalTaskWatchdog = object : Runnable {
        override fun run() {
            when (oneBrowseStage) {
                OneBrowseStage.EXTERNAL_TASK -> runExternalTaskTick()
                OneBrowseStage.EXTERNAL_RECOVERING -> runExternalRecoveryTick()
                else -> return
            }
            if (
                oneBrowseStage == OneBrowseStage.EXTERNAL_TASK ||
                oneBrowseStage == OneBrowseStage.EXTERNAL_RECOVERING
            ) {
                handler.postDelayed(this, EXTERNAL_WATCHDOG_INTERVAL_MS)
            }
        }
    }

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
        rules = loadedRules.rules
        pageRecognizer = PageRecognizer(rules)
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
            runQueuedCoinMainlineIfReady(
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
        resetOneBrowseTrace("run_one_browse_task")
        coinMainlineMode = false
        oneBrowseStartedAtMillis = System.currentTimeMillis()
        oneBrowseLog(
            "start Observation #" + observation.id +
                " page=" + recognition.result.pageType.wireName,
        )

        return when (recognition.result.pageType) {
            PageType.COIN_HOME -> {
                oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
                enterTaskListForOneBrowse(observation)
                "accepted run_one_browse_task from coin_home"
            }

            PageType.DAILY_TASK_LIST -> {
                oneBrowseStage = OneBrowseStage.FINDING_TASK
                if (findAndClickNextTask(observation)) {
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

    private fun startCoinMainline(targetUserId: Int): String {
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
        resetOneBrowseTrace("run_coin_mainline")
        coinMainlineMode = true
        coinMainlineTargetUserId = targetUserId
        oneBrowseStartedAtMillis = System.currentTimeMillis()
        oneBrowseLog(
            "v0.5 mainline start Observation #" + observation.id +
                " page=" + recognition.result.pageType.wireName +
                " user=" + targetUserId,
        )

        return when (recognition.result.pageType) {
            PageType.COIN_HOME -> {
                oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
                enterTaskListForOneBrowse(observation)
                "accepted run_coin_mainline from coin_home"
            }

            PageType.DAILY_TASK_LIST -> {
                oneBrowseStage = OneBrowseStage.FINDING_TASK
                findAndClickNextTask(observation)
                "accepted run_coin_mainline from daily_task_list"
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
        handler.removeCallbacks(coinEntryWaitRunnable)
        handler.removeCallbacks(returnWatchdog)
        handler.removeCallbacks(externalTaskWatchdog)
        handler.removeCallbacks(externalTaskWatchdog)
        oneBrowseStage = OneBrowseStage.IDLE
        oneBrowseStageDeadlineMillis = 0L
        oneBrowseNextSwipeAtMillis = 0L
        oneBrowseNextOcrAtMillis = 0L
        oneBrowseOcrInFlight = false
        oneBrowseEntryOcrInFlight = false
        oneBrowseEntryClicked = false
        oneBrowseSignClaimed = false
        oneBrowseDailyFallbackClicked = false
        oneBrowseEntryRetryCount = 0
        oneBrowseDailyReadyAtMillis = 0L
        oneBrowseLastEntryKind = null
        oneBrowseTaskListScrolls = 0
        oneBrowseBackCount = 0
        oneBrowseLastReturnActionAtMillis = 0L
        oneBrowseReturnLastObservationId = null
        oneBrowseReturnSameObservationTicks = 0
        oneBrowseTaskDescription = ""
        oneBrowseReturnShouldSucceed = true
        oneBrowseLastMessage = "idle"
        coinMainlineMode = false
        handledCoinTaskKeys.clear()
        currentCoinTaskKey = ""
        supportedCoinTasksCompleted = 0
        skippedCoinTasks = 0
        coinMainlineTargetUserId = -1
        externalTaskSession = null
        externalTaskSwipeCount = 0
        externalRecoveryBackCount = 0
        externalRecoveryFallbackLaunched = false
        externalRecoveryPendingCompletion = false
    }

    private fun onOneBrowseObservation(
        observation: com.coin11.taojinbi.observation.Observation,
        pageType: PageType,
    ) {
        val browseContextPhase = when (oneBrowseStage) {
            OneBrowseStage.WAITING_BROWSE_PAGE -> BrowseContextPhase.WAITING_ENTRY
            OneBrowseStage.BROWSING -> BrowseContextPhase.ACTIVE
            else -> BrowseContextPhase.NONE
        }
        val effectivePageType = TaskPageContext.effectiveBrowsePageType(
            rawPageType = pageType,
            observation = observation,
            phase = browseContextPhase,
        )
        if (effectivePageType != pageType) {
            oneBrowseLog(
                "业务上下文 page=" + effectivePageType.wireName +
                    " rawPageType=" + pageType.wireName +
                    " Observation #" + observation.id,
            )
        }

        when (oneBrowseStage) {
            OneBrowseStage.IDLE,
            OneBrowseStage.DONE,
            OneBrowseStage.FAILED,
            -> Unit

            OneBrowseStage.FINDING_COIN_ENTRY,
            OneBrowseStage.WAITING_SIGN_ENTRY,
            OneBrowseStage.WAITING_TASK_LIST,
            OneBrowseStage.WAITING_DAILY_ENTRY,
            -> {
                if (pageType == PageType.DAILY_TASK_LIST) {
                    onCoinTaskListReady(observation)
                }
            }

            OneBrowseStage.FINDING_TASK -> {
                if (pageType == PageType.DAILY_TASK_LIST) {
                    findAndClickNextTask(observation)
                } else if (pageType == PageType.COIN_HOME) {
                    oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
                    enterTaskListForOneBrowse(observation)
                }
            }

            OneBrowseStage.WAITING_BROWSE_PAGE -> {
                when (effectivePageType) {
                    PageType.TAOBAO_BROWSE_TASK -> beginOneBrowse()
                    PageType.TASK_DONE -> startOneBrowseReturn(
                        shouldSucceed = true,
                        reason = "点击后直接出现 task_done",
                    )
                    PageType.EXTERNAL_APP -> {
                        if (!startExternalTaskFlow(observation)) {
                            startOneBrowseReturn(
                                shouldSucceed = false,
                                reason = "外部任务未能建立安全会话",
                            )
                        }
                    }
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
                                reason = "点击后未进入浏览页，最后 raw=" + pageType.wireName,
                            )
                        }
                    }
                }
            }

            OneBrowseStage.WAITING_REWARD_RESULT -> {
                when (pageType) {
                    PageType.DAILY_TASK_LIST -> finishCurrentTaskOnDailyList(
                        observation = observation,
                        success = true,
                        message = "奖励动作完成",
                    )
                    PageType.COIN_HOME -> {
                        oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
                        enterTaskListForOneBrowse(observation)
                    }
                    PageType.EXTERNAL_APP -> {
                        if (!startExternalTaskFlow(observation)) {
                            startOneBrowseReturn(
                                shouldSucceed = false,
                                reason = "奖励动作进入外部App但未能建立安全会话",
                            )
                        }
                    }
                    PageType.QUIZ,
                    PageType.SHOP_SUBSCRIBE_TASK,
                    PageType.GOOD_SHOP_PAGE,
                    -> startOneBrowseReturn(
                        shouldSucceed = false,
                        reason = "奖励动作进入非目标页 " + pageType.wireName,
                    )
                    else -> {
                        if (System.currentTimeMillis() > oneBrowseStageDeadlineMillis) {
                            startOneBrowseReturn(
                                shouldSucceed = false,
                                reason = "奖励动作结果超时 raw=" + pageType.wireName,
                            )
                        }
                    }
                }
            }

            OneBrowseStage.BROWSING -> {
                when (effectivePageType) {
                    PageType.TASK_DONE -> startOneBrowseReturn(
                        shouldSucceed = true,
                        reason = "PageType=task_done",
                    )
                    PageType.DAILY_TASK_LIST -> finishCurrentTaskOnDailyList(
                        observation = observation,
                        success = true,
                        message = "浏览任务自动回到 daily_task_list",
                    )
                    PageType.EXTERNAL_APP -> {
                        if (!startExternalTaskFlow(observation)) {
                            startOneBrowseReturn(
                                shouldSucceed = false,
                                reason = "浏览中进入外部App但未能建立安全会话",
                            )
                        }
                    }
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

            OneBrowseStage.EXTERNAL_TASK -> {
                if (pageType != PageType.EXTERNAL_APP) {
                    handleExternalRecoveryObservation(observation, pageType)
                }
            }

            OneBrowseStage.EXTERNAL_RECOVERING -> {
                handleExternalRecoveryObservation(observation, pageType)
            }

            OneBrowseStage.RETURNING -> {
                oneBrowseReturnLastObservationId = observation.id
                oneBrowseReturnSameObservationTicks = 0
                when (pageType) {
                    PageType.DAILY_TASK_LIST -> finishCurrentTaskOnDailyList(
                        observation = observation,
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
                    else -> Unit
                }
            }
        }
    }

    private fun enterTaskListForOneBrowse(
        observation: com.coin11.taojinbi.observation.Observation,
        returning: Boolean = false,
    ): Boolean {
        if (returning) {
            return tryReturnCoinEntry(observation)
        }

        if (oneBrowseStage != OneBrowseStage.FINDING_COIN_ENTRY) {
            oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
        }

        if (!oneBrowseSignClaimed) {
            val signEntry = BrowseTaskCandidateFinder.findSignCoinEntry(observation)
            if (signEntry != null) {
                return clickSignAndWait(
                    bounds = signEntry.bounds,
                    source = "XML",
                )
            }

            return startInitialSignOcrCheck()
        }

        return startOriginalCoinEntryFallback(observation)
    }

    private fun startInitialSignOcrCheck(): Boolean {
        if (oneBrowseEntryOcrInFlight) {
            return true
        }

        oneBrowseEntryOcrInFlight = true
        oneBrowseLog("XML未发现签到领金币，OCR检查签到入口")

        captureOcrSnapshot { result ->
            oneBrowseEntryOcrInFlight = false
            if (oneBrowseStage != OneBrowseStage.FINDING_COIN_ENTRY) {
                return@captureOcrSnapshot
            }

            result.onSuccess { snapshot ->
                OcrState.publish(snapshot)
                val signLine = snapshot.lines.firstOrNull { line ->
                    line.bounds != null &&
                        compactEntryText(line.text).contains("签到领金币")
                }

                if (signLine?.bounds != null) {
                    clickSignAndWait(
                        bounds = signLine.bounds,
                        source = "OCR",
                    )
                } else {
                    val observation = latestValidTaobaoObservation()
                    if (observation != null) {
                        startOriginalCoinEntryFallback(observation)
                    } else {
                        oneBrowseLog("签到OCR未命中，等待最新淘宝 Observation 后继续原入口")
                        scheduleEntryRetry(ENTRY_OBSERVATION_RETRY_MS)
                    }
                }
            }.onFailure { error ->
                oneBrowseLog(
                    "签到OCR失败 " + error.javaClass.simpleName +
                        "，继续原入口逻辑",
                )
                val observation = latestValidTaobaoObservation()
                if (observation != null) {
                    startOriginalCoinEntryFallback(observation)
                } else {
                    scheduleEntryRetry(ENTRY_OBSERVATION_RETRY_MS)
                }
            }
        }
        return true
    }

    private fun clickSignAndWait(
        bounds: com.coin11.taojinbi.observation.IntRect,
        source: String,
    ): Boolean {
        oneBrowseSignClaimed = true
        oneBrowseStage = OneBrowseStage.WAITING_SIGN_ENTRY
        oneBrowseStageDeadlineMillis =
            System.currentTimeMillis() + SIGN_ENTRY_WAIT_MS
        oneBrowseEntryRetryCount = 0

        oneBrowseLog(
            source + "点击签到领金币 " + bounds +
                "；进入等待签到后入口状态，最多8秒",
        )

        val queued = tapBoundsForOneBrowse(
            bounds,
            "one_task_sign_coin_" + source.lowercase(),
        )

        if (queued) {
            scheduleEntryRetry(SIGN_ENTRY_RETRY_MS)
        } else {
            oneBrowseLog("签到点击未排队，继续原入口逻辑")
            oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
            val observation = latestValidTaobaoObservation()
            if (observation != null) {
                startOriginalCoinEntryFallback(observation)
            }
        }
        return queued
    }

    private fun runCoinEntryWaitTick() {
        when (oneBrowseStage) {
            OneBrowseStage.FINDING_COIN_ENTRY -> {
                val observation = latestValidTaobaoObservation()
                if (observation != null) {
                    startOriginalCoinEntryFallback(observation)
                } else {
                    scheduleCapture()
                    scheduleEntryRetry(ENTRY_OBSERVATION_RETRY_MS)
                }
            }

            OneBrowseStage.WAITING_SIGN_ENTRY -> runSignEntryRetryTick()
            OneBrowseStage.WAITING_TASK_LIST -> runTaskListWaitTick()
            OneBrowseStage.WAITING_DAILY_ENTRY -> runDailyEntryRetryTick()
            else -> Unit
        }
    }

    private fun runSignEntryRetryTick() {
        val now = System.currentTimeMillis()
        if (now >= oneBrowseStageDeadlineMillis) {
            oneBrowseLog(
                "签到后入口等待超时；进入 Mac 原入口兜底顺序",
            )
            oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
            oneBrowseEntryRetryCount = 0
            val observation = latestValidTaobaoObservation()
            if (observation != null) {
                startOriginalCoinEntryFallback(observation)
            } else {
                scheduleCapture()
                scheduleEntryRetry(ENTRY_OBSERVATION_RETRY_MS)
            }
            return
        }

        oneBrowseEntryRetryCount += 1
        oneBrowseLog(
            "签到后入口等待重试 #" + oneBrowseEntryRetryCount,
        )
        scheduleCapture()

        val observation = latestValidTaobaoObservation()
        if (observation == null) {
            scheduleEntryRetry(SIGN_ENTRY_RETRY_MS)
            return
        }

        if (tryClickCoinEntryFromNodes(observation, "签到后")) {
            return
        }

        startCoinEntryOcrAttempt(
            expectedStage = OneBrowseStage.WAITING_SIGN_ENTRY,
            contextLabel = "签到后",
            onMiss = {
                if (oneBrowseStage == OneBrowseStage.WAITING_SIGN_ENTRY) {
                    scheduleEntryRetry(SIGN_ENTRY_RETRY_MS)
                }
            },
        )
    }

    private fun runTaskListWaitTick() {
        scheduleCapture()
        if (oneBrowseStage != OneBrowseStage.WAITING_TASK_LIST) {
            return
        }

        if (System.currentTimeMillis() < oneBrowseStageDeadlineMillis) {
            scheduleEntryRetry(TASK_LIST_CHECK_INTERVAL_MS)
            return
        }

        oneBrowseLog(
            "入口点击后3秒仍未进入 daily_task_list；继续 Mac 原入口兜底",
        )
        oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY

        val observation = latestValidTaobaoObservation()
        when (oneBrowseLastEntryKind) {
            CoinEntryKind.EARN_MORE -> {
                if (observation != null) {
                    startOriginalCoinEntryFallback(
                        observation = observation,
                        skipEarnMoreXml = true,
                        allowEntryOcr = true,
                    )
                } else {
                    scheduleEntryRetry(ENTRY_OBSERVATION_RETRY_MS)
                }
            }

            CoinEntryKind.EARN -> {
                if (observation != null) {
                    startDailyVersionOrFinalTaskListCheck(observation)
                } else {
                    scheduleEntryRetry(ENTRY_OBSERVATION_RETRY_MS)
                }
            }

            null -> {
                if (observation != null) {
                    startOriginalCoinEntryFallback(observation)
                } else {
                    scheduleEntryRetry(ENTRY_OBSERVATION_RETRY_MS)
                }
            }
        }
    }

    private fun runDailyEntryRetryTick() {
        val now = System.currentTimeMillis()
        if (now < oneBrowseDailyReadyAtMillis) {
            scheduleEntryRetry(oneBrowseDailyReadyAtMillis - now)
            return
        }

        if (now >= oneBrowseStageDeadlineMillis) {
            oneBrowseLog(
                "回日常版后8秒仍未找到可点击赚金币入口；执行最终OCR任务列表确认",
            )
            startFinalTaskListOcrCheck()
            return
        }

        oneBrowseEntryRetryCount += 1
        oneBrowseLog(
            "回日常版后入口等待重试 #" + oneBrowseEntryRetryCount,
        )
        scheduleCapture()

        val observation = latestValidTaobaoObservation()
        if (observation == null) {
            scheduleEntryRetry(DAILY_ENTRY_RETRY_MS)
            return
        }

        if (tryClickCoinEntryFromNodes(observation, "回日常版后")) {
            return
        }

        startCoinEntryOcrAttempt(
            expectedStage = OneBrowseStage.WAITING_DAILY_ENTRY,
            contextLabel = "回日常版后",
            onMiss = {
                if (oneBrowseStage == OneBrowseStage.WAITING_DAILY_ENTRY) {
                    scheduleEntryRetry(DAILY_ENTRY_RETRY_MS)
                }
            },
        )
    }

    private fun startOriginalCoinEntryFallback(
        observation: com.coin11.taojinbi.observation.Observation,
        skipEarnMoreXml: Boolean = false,
        allowEntryOcr: Boolean = true,
    ): Boolean {
        if (oneBrowseStage != OneBrowseStage.FINDING_COIN_ENTRY) {
            oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
        }

        if (!skipEarnMoreXml) {
            val earnMore = BrowseTaskCandidateFinder.findEarnMoreEntry(
                observation,
                rules.earnMoreWords,
            )
            if (earnMore != null) {
                return clickCoinEntry(
                    bounds = earnMore.bounds,
                    label = earnMore.text ?: earnMore.contentDescription ?: "赚更多金币",
                    kind = CoinEntryKind.EARN_MORE,
                    contextLabel = "原入口",
                )
            }
        }

        val earn = BrowseTaskCandidateFinder.findEarnEntry(
            observation,
            rules.earnWords,
        )
        if (earn != null) {
            return clickCoinEntry(
                bounds = earn.bounds,
                label = earn.text ?: earn.contentDescription ?: "赚金币",
                kind = CoinEntryKind.EARN,
                contextLabel = "原入口",
            )
        }

        if (!allowEntryOcr) {
            return startDailyVersionOrFinalTaskListCheck(observation)
        }

        return startCoinEntryOcrAttempt(
            expectedStage = OneBrowseStage.FINDING_COIN_ENTRY,
            contextLabel = "原入口",
            onMiss = {
                val latest = latestValidTaobaoObservation() ?: observation
                startDailyVersionOrFinalTaskListCheck(latest)
            },
        )
    }

    private fun tryClickCoinEntryFromNodes(
        observation: com.coin11.taojinbi.observation.Observation,
        contextLabel: String,
    ): Boolean {
        val earnMore = BrowseTaskCandidateFinder.findEarnMoreEntry(
            observation,
            rules.earnMoreWords,
        )
        if (earnMore != null) {
            return clickCoinEntry(
                bounds = earnMore.bounds,
                label = earnMore.text ?: earnMore.contentDescription ?: "赚更多金币",
                kind = CoinEntryKind.EARN_MORE,
                contextLabel = contextLabel,
            )
        }

        val earn = BrowseTaskCandidateFinder.findEarnEntry(
            observation,
            rules.earnWords,
        )
        if (earn != null) {
            return clickCoinEntry(
                bounds = earn.bounds,
                label = earn.text ?: earn.contentDescription ?: "赚金币",
                kind = CoinEntryKind.EARN,
                contextLabel = contextLabel,
            )
        }

        return false
    }

    private fun startCoinEntryOcrAttempt(
        expectedStage: OneBrowseStage,
        contextLabel: String,
        onMiss: () -> Unit,
    ): Boolean {
        if (oneBrowseEntryOcrInFlight) {
            return true
        }

        oneBrowseEntryOcrInFlight = true
        oneBrowseLog(contextLabel + " XML未命中赚金币入口，OCR重查")

        captureOcrSnapshot { result ->
            oneBrowseEntryOcrInFlight = false
            if (oneBrowseStage != expectedStage) {
                return@captureOcrSnapshot
            }

            result.onSuccess { snapshot ->
                OcrState.publish(snapshot)
                val entry = findOcrCoinEntry(snapshot)
                if (entry != null && entry.second.bounds != null) {
                    clickCoinEntry(
                        bounds = entry.second.bounds!!,
                        label = entry.second.text,
                        kind = entry.first,
                        contextLabel = contextLabel + " OCR",
                    )
                } else {
                    oneBrowseLog(
                        contextLabel + " OCR未命中赚更多金币/赚金币",
                    )
                    onMiss()
                }
            }.onFailure { error ->
                oneBrowseLog(
                    contextLabel + " OCR失败 " + error.javaClass.simpleName,
                )
                onMiss()
            }
        }
        return true
    }

    private fun findOcrCoinEntry(
        snapshot: OcrSnapshot,
    ): Pair<CoinEntryKind, com.coin11.taojinbi.ocr.OcrLine>? {
        return snapshot.lines
            .asSequence()
            .filter { it.bounds != null }
            .mapNotNull { line ->
                val compact = compactEntryText(line.text)
                val earnMoreRank = rules.earnMoreWords.indexOfFirst { word ->
                    compact.contains(compactEntryText(word))
                }
                if (earnMoreRank >= 0) {
                    return@mapNotNull Triple(
                        earnMoreRank,
                        0,
                        CoinEntryKind.EARN_MORE to line,
                    )
                }

                val earnRank = rules.earnWords.indexOfFirst { word ->
                    compact == compactEntryText(word)
                }
                if (earnRank >= 0) {
                    Triple(
                        earnRank,
                        1,
                        CoinEntryKind.EARN to line,
                    )
                } else {
                    null
                }
            }
            .sortedWith(
                compareBy<Triple<Int, Int, Pair<CoinEntryKind, com.coin11.taojinbi.ocr.OcrLine>>> {
                    it.second
                }.thenBy {
                    it.first
                }.thenBy {
                    it.third.second.bounds?.top ?: Int.MAX_VALUE
                },
            )
            .map { it.third }
            .firstOrNull()
    }

    private fun clickCoinEntry(
        bounds: com.coin11.taojinbi.observation.IntRect,
        label: String,
        kind: CoinEntryKind,
        contextLabel: String,
    ): Boolean {
        oneBrowseEntryClicked = true
        oneBrowseLastEntryKind = kind
        oneBrowseStage = OneBrowseStage.WAITING_TASK_LIST
        oneBrowseStageDeadlineMillis =
            System.currentTimeMillis() + ENTRY_CLICK_TASK_LIST_WAIT_MS

        oneBrowseLog(
            contextLabel + "入口命中：" + label + " " + bounds +
                "；等待 daily_task_list",
        )

        val queued = tapBoundsForOneBrowse(
            bounds,
            "one_task_entry_" + kind.name.lowercase(),
        )
        if (queued) {
            scheduleEntryRetry(TASK_LIST_CHECK_INTERVAL_MS)
        } else {
            oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
            oneBrowseLog("入口点击未排队，继续原入口兜底")
            val observation = latestValidTaobaoObservation()
            if (observation != null) {
                startDailyVersionOrFinalTaskListCheck(observation)
            }
        }
        return queued
    }

    private fun startDailyVersionOrFinalTaskListCheck(
        observation: com.coin11.taojinbi.observation.Observation,
    ): Boolean {
        if (!rules.allowDailyVersionFallback) {
            oneBrowseLog("回日常版兜底关闭，跳过")
            return startFinalTaskListOcrCheck()
        }

        if (!oneBrowseDailyFallbackClicked) {
            val daily = BrowseTaskCandidateFinder.findDailyVersionEntry(
                observation,
                rules.dailyVersionWords,
            )
            if (daily != null) {
                oneBrowseDailyFallbackClicked = true
                oneBrowseStage = OneBrowseStage.WAITING_DAILY_ENTRY
                oneBrowseDailyReadyAtMillis =
                    System.currentTimeMillis() + DAILY_VERSION_ANIMATION_MS
                oneBrowseStageDeadlineMillis =
                    oneBrowseDailyReadyAtMillis + DAILY_ENTRY_WAIT_MS
                oneBrowseEntryRetryCount = 0

                oneBrowseLog(
                    "原入口未命中；点击回日常版 " + daily.bounds +
                        "，先等3秒动画，再最多等待8秒赚金币入口",
                )

                val queued = tapBoundsForOneBrowse(
                    daily.bounds,
                    "one_task_daily_version",
                )
                if (queued) {
                    scheduleEntryRetry(DAILY_VERSION_ANIMATION_MS)
                    return true
                }

                oneBrowseLog("回日常版点击未排队，直接执行最终OCR任务列表确认")
            }
        }

        return startFinalTaskListOcrCheck()
    }

    private fun startFinalTaskListOcrCheck(): Boolean {
        if (oneBrowseEntryOcrInFlight) {
            return true
        }

        oneBrowseEntryOcrInFlight = true
        oneBrowseLog("入口阶段执行最终OCR任务列表确认")

        captureOcrSnapshot { result ->
            oneBrowseEntryOcrInFlight = false
            if (
                oneBrowseStage != OneBrowseStage.FINDING_COIN_ENTRY &&
                oneBrowseStage != OneBrowseStage.WAITING_DAILY_ENTRY
            ) {
                return@captureOcrSnapshot
            }

            result.onSuccess { snapshot ->
                OcrState.publish(snapshot)
                if (ocrLooksLikeTaskList(snapshot)) {
                    val observation = latestValidTaobaoObservation()
                    if (observation != null) {
                        oneBrowseLog(
                            "最终OCR确认已在任务列表；继续主线",
                        )
                        onCoinTaskListReady(observation)
                    } else {
                        failOneBrowse("OCR确认任务列表，但没有有效淘宝 Observation")
                    }
                } else {
                    val sample = snapshot.lines
                        .take(12)
                        .joinToString(" | ") { it.text }
                    failOneBrowse(
                        "未找到赚金币入口；签到等待/原入口/回日常版/OCR任务列表均未命中；OCR=" +
                            sample,
                    )
                }
            }.onFailure { error ->
                failOneBrowse(
                    "入口最终OCR失败 " + error.javaClass.simpleName,
                )
            }
        }
        return true
    }

    private fun ocrLooksLikeTaskList(snapshot: OcrSnapshot): Boolean {
        val texts = snapshot.lines
            .map { compactEntryText(it.text) }
            .filter { it.isNotBlank() }

        val hasFast = texts.any { text ->
            rules.dailyFastWords.any { text.contains(compactEntryText(it)) }
        }
        val hasTaskArea = texts.any { text ->
            rules.dailyTaskAreaWords.any { text.contains(compactEntryText(it)) }
        }
        val hasBottom = texts.any { text ->
            rules.taskListBottomWords.any { text.contains(compactEntryText(it)) }
        }
        val hasProgress = texts.any { ENTRY_PROGRESS_REGEX.containsMatchIn(it) }
        val actionRegex = runCatching { Regex(rules.actionTextPattern) }
            .getOrElse { Regex("去完成|去逛逛|去浏览|领取奖励|立即领取") }
        val actionCount = texts.count { actionRegex.containsMatchIn(it) }

        return (
            hasFast && (hasTaskArea || actionCount > 0 || hasProgress)
        ) || (
            hasTaskArea && (actionCount > 0 || hasProgress)
        ) || (
            hasBottom && (hasTaskArea || actionCount > 0 || hasProgress)
        )
    }

    private fun onCoinTaskListReady(
        observation: com.coin11.taojinbi.observation.Observation,
    ) {
        handler.removeCallbacks(coinEntryWaitRunnable)
        if (externalRecoveryPendingCompletion) {
            externalRecoveryPendingCompletion = false
            finishCurrentTaskOnDailyList(
                observation = observation,
                success = true,
                message = "外部任务App已关闭并恢复任务列表",
            )
            return
        }
        oneBrowseStage = OneBrowseStage.FINDING_TASK
        oneBrowseLog(
            "进入 daily_task_list Observation #" + observation.id,
        )
        findAndClickNextTask(observation)
    }

    private fun latestValidTaobaoObservation():
        com.coin11.taojinbi.observation.Observation? {
        val observation = ObserverState.latestExternalObservation ?: return null
        if (!ObserverState.latestObservationValid) {
            return null
        }
        if (observation.packageName != TAOBAO_PACKAGE) {
            return null
        }
        return observation
    }

    private fun scheduleEntryRetry(delayMs: Long) {
        handler.removeCallbacks(coinEntryWaitRunnable)
        handler.postDelayed(
            coinEntryWaitRunnable,
            delayMs.coerceAtLeast(1L),
        )
    }

    private fun tryReturnCoinEntry(
        observation: com.coin11.taojinbi.observation.Observation,
    ): Boolean {
        val entry = BrowseTaskCandidateFinder.findCoinTaskEntry(
            observation,
            rules.earnMoreWords,
            rules.earnWords,
        )
        if (entry != null) {
            val label =
                (entry.text ?: entry.contentDescription ?: "赚金币").trim()
            oneBrowseLog(
                "返回过程中点击任务入口 " + label + " " + entry.bounds,
            )
            return tapBoundsForOneBrowse(
                entry.bounds,
                "one_task_return_entry",
            )
        }

        if (oneBrowseEntryOcrInFlight) {
            return true
        }

        oneBrowseEntryOcrInFlight = true
        oneBrowseLog("返回过程中 XML未找到赚金币入口，OCR兜底查找")
        captureOcrSnapshot { result ->
            oneBrowseEntryOcrInFlight = false
            if (oneBrowseStage != OneBrowseStage.RETURNING) {
                return@captureOcrSnapshot
            }
            result.onSuccess { snapshot ->
                OcrState.publish(snapshot)
                val entryLine = findOcrCoinEntry(snapshot)
                if (entryLine != null && entryLine.second.bounds != null) {
                    oneBrowseLog(
                        "返回过程中 OCR点击任务入口 " +
                            entryLine.second.text + " " +
                            entryLine.second.bounds,
                    )
                    tapBoundsForOneBrowse(
                        entryLine.second.bounds!!,
                        "one_task_return_entry_ocr",
                    )
                } else {
                    oneBrowseLog(
                        "返回过程中 XML/OCR 均未找到赚金币入口",
                    )
                }
            }.onFailure { error ->
                oneBrowseLog(
                    "返回过程中入口OCR失败 " +
                        error.javaClass.simpleName,
                )
            }
        }
        return true
    }

    private fun compactEntryText(text: String): String =
        text.replace(Regex("\\s+"), "").trim()

    private fun findAndClickNextTask(
        observation: com.coin11.taojinbi.observation.Observation,
    ): Boolean {
        if (!coinMainlineMode) {
            val candidate = BrowseTaskCandidateFinder.findBrowseTask(observation)
            if (candidate != null) {
                currentCoinTaskKey =
                    (candidate.buttonText + "|" + candidate.contextText)
                        .replace(Regex("\\s+"), "")
                        .take(180)
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

        val candidate = CoinTaskCandidateFinder.findNext(
            observation = observation,
            handledKeys = handledCoinTaskKeys,
        )

        if (candidate != null) {
            currentCoinTaskKey = candidate.key
            oneBrowseTaskDescription =
                candidate.actionText + " | " + candidate.contextText.take(120)
            oneBrowseTaskListScrolls = 0

            when (candidate.kind) {
                CoinTaskKind.BROWSE -> {
                    oneBrowseStage = OneBrowseStage.WAITING_BROWSE_PAGE
                    oneBrowseStageDeadlineMillis =
                        System.currentTimeMillis() + ENTER_BROWSE_TIMEOUT_MS
                    oneBrowseLog(
                        "主线点击浏览任务 " + oneBrowseTaskDescription +
                            " bounds=" + candidate.bounds,
                    )
                }

                CoinTaskKind.REWARD -> {
                    oneBrowseStage = OneBrowseStage.WAITING_REWARD_RESULT
                    oneBrowseStageDeadlineMillis =
                        System.currentTimeMillis() + REWARD_RESULT_TIMEOUT_MS
                    oneBrowseLog(
                        "主线点击奖励 " + oneBrowseTaskDescription +
                            " bounds=" + candidate.bounds,
                    )
                }
            }

            return tapBoundsForOneBrowse(candidate.bounds, "coin_mainline_candidate")
        }

        if (oneBrowseTaskListScrolls >= MAX_MAINLINE_TASK_LIST_SCROLLS) {
            completeOneBrowse(
                success = true,
                message =
                    "主线安全任务扫描完成 completed=" +
                        supportedCoinTasksCompleted +
                        " skipped=" + skippedCoinTasks,
            )
            return false
        }

        oneBrowseTaskListScrolls += 1
        oneBrowseLog(
            "主线当前屏无新的安全任务，下翻 " +
                oneBrowseTaskListScrolls + "/" + MAX_MAINLINE_TASK_LIST_SCROLLS,
        )
        swipeTaskListForOneBrowse()
        return false
    }

    private fun finishCurrentTaskOnDailyList(
        observation: com.coin11.taojinbi.observation.Observation,
        success: Boolean,
        message: String,
    ) {
        handler.removeCallbacks(externalTaskWatchdog)
        externalTaskSession = null
        externalRecoveryPendingCompletion = false
        if (currentCoinTaskKey.isNotBlank()) {
            handledCoinTaskKeys += currentCoinTaskKey
        }

        if (!coinMainlineMode) {
            completeOneBrowse(success = success, message = message)
            return
        }

        if (success) {
            supportedCoinTasksCompleted += 1
        } else {
            skippedCoinTasks += 1
        }

        oneBrowseTaskListScrolls = 0
        oneBrowseBackCount = 0
        oneBrowseTaskDescription = ""
        currentCoinTaskKey = ""
        oneBrowseStage = OneBrowseStage.FINDING_TASK
        oneBrowseLog(
            (if (success) "主线任务完成：" else "主线任务跳过：") +
                message +
                " completed=" + supportedCoinTasksCompleted +
                " skipped=" + skippedCoinTasks,
        )
        findAndClickNextTask(observation)
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

    private fun startExternalTaskFlow(
        observation: com.coin11.taojinbi.observation.Observation,
    ): Boolean {
        if (!coinMainlineMode || coinMainlineTargetUserId < 0) {
            oneBrowseLog("外部任务未启动：没有主线 user 上下文")
            return false
        }

        val packageName = observation.packageName ?: return false
        if (
            packageName == TAOBAO_PACKAGE ||
            packageName == LAUNCHER_PACKAGE ||
            packageName in TRANSIENT_EXTERNAL_PACKAGES
        ) {
            oneBrowseLog(
                "外部页不建立 kill 会话 package=" + packageName,
            )
            return false
        }

        val now = System.currentTimeMillis()
        externalTaskSession = ExternalTaskSession(
            packageName = packageName,
            taskKey = currentCoinTaskKey,
            taskDescription = oneBrowseTaskDescription,
            startedAtMillis = now,
            userId = coinMainlineTargetUserId,
        )
        externalTaskSwipeCount = 0
        externalRecoveryBackCount = 0
        externalRecoveryFallbackLaunched = false
        externalRecoveryPendingCompletion = false
        oneBrowseStage = OneBrowseStage.EXTERNAL_TASK
        oneBrowseStageDeadlineMillis = now + EXTERNAL_TASK_MAX_MS

        oneBrowseLog(
            "记录外部任务会话 package=" + packageName +
                " user=" + coinMainlineTargetUserId +
                " task=" + oneBrowseTaskDescription,
        )
        handler.removeCallbacks(externalTaskWatchdog)
        handler.postDelayed(
            externalTaskWatchdog,
            EXTERNAL_INITIAL_SETTLE_MS,
        )
        return true
    }

    private fun runExternalTaskTick() {
        val session = externalTaskSession
        if (session == null) {
            startOneBrowseReturn(
                shouldSucceed = false,
                reason = "外部任务会话丢失",
            )
            return
        }

        val now = System.currentTimeMillis()
        if (now - session.startedAtMillis > EXTERNAL_SESSION_MAX_AGE_MS) {
            startOneBrowseReturn(
                shouldSucceed = false,
                reason = "外部任务会话超过180秒",
            )
            return
        }

        scheduleCapture()
        val observation = ObserverState.latestExternalObservation
        val pageType = RecognitionState.latest
            ?.takeIf { it.observationId == observation?.id }
            ?.result
            ?.pageType

        if (
            observation != null &&
            ObserverState.latestObservationValid &&
            observation.packageName != session.packageName
        ) {
            if (pageType != null) {
                handleExternalRecoveryObservation(observation, pageType)
                if (oneBrowseStage != OneBrowseStage.EXTERNAL_TASK) {
                    return
                }
            }

            oneBrowseLog(
                "外部任务已离开目标包 " + session.packageName +
                    "，转入恢复流程；current=" +
                    (observation.packageName ?: "(null)") +
                    " rawPage=" + (pageType?.wireName ?: "(none)"),
            )
            oneBrowseStage = OneBrowseStage.EXTERNAL_RECOVERING
            oneBrowseStageDeadlineMillis =
                System.currentTimeMillis() + EXTERNAL_RECOVERY_TIMEOUT_MS
            externalRecoveryBackCount = 0
            return
        }

        if (externalTaskSwipeCount < EXTERNAL_SWIPE_COUNT) {
            externalTaskSwipeCount += 1
            oneBrowseLog(
                "外部任务滚动 " + externalTaskSwipeCount +
                    "/" + EXTERNAL_SWIPE_COUNT +
                    " package=" + session.packageName,
            )
            swipeExternalTaskOnce()
            return
        }

        val submitted = ShizukuBridge.forceStopPackage(
            session.userId,
            session.packageName,
        )
        if (!submitted) {
            oneBrowseLog(
                "Shizuku force-stop 提交失败 package=" +
                    session.packageName,
            )
            startOneBrowseReturn(
                shouldSucceed = false,
                reason = "外部任务 force-stop 提交失败",
            )
            return
        }

        oneBrowseLog(
            "外部任务 force-stop 已提交 package=" +
                session.packageName +
                " user=" + session.userId,
        )
        oneBrowseStage = OneBrowseStage.EXTERNAL_RECOVERING
        oneBrowseStageDeadlineMillis =
            System.currentTimeMillis() + EXTERNAL_RECOVERY_TIMEOUT_MS
        externalRecoveryBackCount = 0
        externalRecoveryFallbackLaunched = false
        scheduleCapture()
    }

    private fun swipeExternalTaskOnce() {
        val width = resources.displayMetrics.widthPixels
        val height = resources.displayMetrics.heightPixels
        val queued = actionExecutor.swipe(
            startX = width / 2,
            startY = (height * 0.78f).toInt(),
            endX = width / 2,
            endY = (height * 0.38f).toInt(),
            durationMs = 350L,
        ) { result ->
            publishActionResult("v0.5 External Swipe", result)
        }
        if (queued) {
            invalidateObservationForAction("external_task_swipe")
            handler.postDelayed(
                { scheduleCapture() },
                RETURN_CAPTURE_AFTER_BACK_MS,
            )
        }
    }

    private fun handleExternalRecoveryObservation(
        observation: com.coin11.taojinbi.observation.Observation,
        pageType: PageType,
    ) {
        if (
            oneBrowseStage != OneBrowseStage.EXTERNAL_TASK &&
            oneBrowseStage != OneBrowseStage.EXTERNAL_RECOVERING
        ) {
            return
        }

        when (pageType) {
            PageType.DAILY_TASK_LIST -> {
                oneBrowseLog(
                    "外部任务恢复到 daily_task_list Observation #" +
                        observation.id,
                )
                finishCurrentTaskOnDailyList(
                    observation = observation,
                    success = true,
                    message = "外部任务App已关闭并回到 daily_task_list",
                )
            }

            PageType.COIN_HOME -> {
                oneBrowseLog(
                    "外部任务恢复到 coin_home，重新进入任务列表",
                )
                externalRecoveryPendingCompletion = true
                oneBrowseStage = OneBrowseStage.FINDING_COIN_ENTRY
                enterTaskListForOneBrowse(observation)
            }

            else -> Unit
        }
    }

    private fun runExternalRecoveryTick() {
        val session = externalTaskSession
        if (session == null) {
            startOneBrowseReturn(
                shouldSucceed = false,
                reason = "外部恢复会话丢失",
            )
            return
        }

        val now = System.currentTimeMillis()
        scheduleCapture()

        val observation = ObserverState.latestExternalObservation
        val pageType = RecognitionState.latest
            ?.takeIf { it.observationId == observation?.id }
            ?.result
            ?.pageType

        if (
            observation != null &&
            ObserverState.latestObservationValid &&
            pageType != null
        ) {
            if (
                pageType == PageType.DAILY_TASK_LIST ||
                pageType == PageType.COIN_HOME
            ) {
                handleExternalRecoveryObservation(observation, pageType)
                return
            }

            val packageName = observation.packageName
            if (packageName == TAOBAO_PACKAGE) {
                if (externalRecoveryBackCount < EXTERNAL_RECOVERY_BACK_LIMIT) {
                    externalRecoveryBackCount += 1
                    oneBrowseLog(
                        "关闭外部App后仍在淘宝承接页，Back #" +
                            externalRecoveryBackCount +
                            " rawPage=" + pageType.wireName,
                    )
                    globalBack()
                    handler.postDelayed(
                        { scheduleCapture() },
                        RETURN_CAPTURE_AFTER_BACK_MS,
                    )
                    return
                }
            } else if (
                packageName != null &&
                packageName != session.packageName &&
                packageName != LAUNCHER_PACKAGE &&
                packageName !in TRANSIENT_EXTERNAL_PACKAGES
            ) {
                oneBrowseLog(
                    "关闭目标App后停在其他外部App " + packageName +
                        "；不杀无关App，改走淘金币入口恢复",
                )
                launchCoinHomeForExternalRecovery()
                return
            }

            if (packageName == LAUNCHER_PACKAGE) {
                oneBrowseLog("关闭外部App后到桌面，启动当前 user 淘金币入口")
                launchCoinHomeForExternalRecovery()
                return
            }
        }

        if (now >= oneBrowseStageDeadlineMillis) {
            if (!externalRecoveryFallbackLaunched) {
                oneBrowseLog(
                    "关闭外部App后恢复超时，使用淘金币入口恢复",
                )
                launchCoinHomeForExternalRecovery()
                return
            }

            startOneBrowseReturn(
                shouldSucceed = false,
                reason =
                    "外部任务 force-stop 后仍未恢复任务列表 package=" +
                        session.packageName,
            )
        }
    }

    private fun launchCoinHomeForExternalRecovery() {
        val session = externalTaskSession
        if (session == null) {
            startOneBrowseReturn(
                shouldSucceed = false,
                reason = "外部恢复时会话丢失",
            )
            return
        }
        if (externalRecoveryFallbackLaunched) {
            return
        }

        externalRecoveryFallbackLaunched = true
        externalRecoveryPendingCompletion = true
        oneBrowseStage = OneBrowseStage.EXTERNAL_RECOVERING
        oneBrowseStageDeadlineMillis =
            System.currentTimeMillis() + EXTERNAL_RECOVERY_FALLBACK_TIMEOUT_MS

        val submitted = ShizukuBridge.openCoinAsUser(
            session.userId,
            COIN_HOME_URL,
        )
        oneBrowseLog(
            "外部恢复启动淘金币 user=" + session.userId +
                " submitted=" + submitted,
        )
        if (!submitted) {
            startOneBrowseReturn(
                shouldSucceed = false,
                reason = "外部恢复启动淘金币失败",
            )
        }
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
        handler.removeCallbacks(returnWatchdog)
        handler.removeCallbacks(externalTaskWatchdog)
        oneBrowseReturnShouldSucceed = shouldSucceed
        oneBrowseStage = OneBrowseStage.RETURNING
        oneBrowseStageDeadlineMillis =
            System.currentTimeMillis() + RETURN_TIMEOUT_MS
        oneBrowseBackCount = 0
        oneBrowseLastReturnActionAtMillis = 0L
        oneBrowseReturnLastObservationId =
            ObserverState.latestExternalObservation?.id
        oneBrowseReturnSameObservationTicks = 0
        oneBrowseLog(
            "开始返回任务列表：" + reason +
                "；watchdog=" + RETURN_TIMEOUT_MS + "ms",
        )
        handler.postDelayed(returnWatchdog, RETURN_BACK_SETTLE_MS)
    }

    private fun runReturnWatchdogTick() {
        if (oneBrowseStage != OneBrowseStage.RETURNING) {
            return
        }

        val now = System.currentTimeMillis()
        val latestObservation = ObserverState.latestExternalObservation
        val latestId = latestObservation?.id
        if (latestId == oneBrowseReturnLastObservationId) {
            oneBrowseReturnSameObservationTicks += 1
        } else {
            oneBrowseReturnLastObservationId = latestId
            oneBrowseReturnSameObservationTicks = 0
        }

        if (now >= oneBrowseStageDeadlineMillis) {
            completeOneBrowse(
                success = false,
                message =
                    "返回任务列表超时；backs=" + oneBrowseBackCount +
                        " lastObservation=" +
                        (latestId?.let { "#" + it } ?: "(none)"),
            )
            return
        }

        if (oneBrowseBackCount >= MAX_RETURN_BACKS) {
            completeOneBrowse(
                success = false,
                message =
                    "连续 Back " + oneBrowseBackCount +
                        " 次仍未返回任务列表；lastObservation=" +
                        (latestId?.let { "#" + it } ?: "(none)"),
            )
            return
        }

        scheduleCapture()

        if (!canRunOneBrowseReturnAction()) {
            return
        }

        oneBrowseLastReturnActionAtMillis = now
        oneBrowseBackCount += 1
        oneBrowseLog(
            "RETURN watchdog Back #" + oneBrowseBackCount +
                " lastObservation=" +
                (latestId?.let { "#" + it } ?: "(none)") +
                " sameTicks=" + oneBrowseReturnSameObservationTicks,
        )
        globalBack()

        handler.postDelayed(
            {
                if (oneBrowseStage == OneBrowseStage.RETURNING) {
                    scheduleCapture()
                }
            },
            RETURN_CAPTURE_AFTER_BACK_MS,
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
        handler.removeCallbacks(coinEntryWaitRunnable)
        handler.removeCallbacks(returnWatchdog)
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

    private fun resetOneBrowseTrace(label: String) {
        runCatching {
            openFileOutput(ONE_TASK_TRACE_FILE, Context.MODE_PRIVATE)
                .bufferedWriter()
                .use { writer ->
                    writer.append(System.currentTimeMillis().toString())
                    writer.append(" TRACE_START ")
                    writer.append(label)
                    writer.newLine()
                }
        }.onFailure { error ->
            Log.w(ONE_TASK_TAG, "trace reset failed", error)
        }
    }

    private fun appendOneBrowseTrace(message: String) {
        runCatching {
            openFileOutput(ONE_TASK_TRACE_FILE, Context.MODE_APPEND)
                .bufferedWriter()
                .use { writer ->
                    writer.append(System.currentTimeMillis().toString())
                    writer.append(" ")
                    writer.append(message)
                    writer.newLine()
                }
        }.onFailure { error ->
            Log.w(ONE_TASK_TAG, "trace append failed", error)
        }
    }

    private fun oneBrowseLog(message: String) {
        oneBrowseLastMessage = message
        Log.i(ONE_TASK_TAG, message)
        appendOneBrowseTrace(message)
        CapabilityState.publish("v0.5 OneTask", message)
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
            if (oneBrowseStage == OneBrowseStage.RETURNING) {
                append(" returnLastObservation=")
                append(oneBrowseReturnLastObservationId?.let { "#" + it } ?: "(none)")
                append(" returnSameTicks=")
                append(oneBrowseReturnSameObservationTicks)
            }
            append(" mode=")
            append(if (coinMainlineMode) "coin_mainline" else "one_browse")
            append(" completed=")
            append(supportedCoinTasksCompleted)
            append(" skipped=")
            append(skippedCoinTasks)
            append(" user=")
            append(coinMainlineTargetUserId)
            externalTaskSession?.let {
                append(" external=")
                append(it.packageName)
            }
        }

    private fun runQueuedCoinMainlineIfReady(
        observation: com.coin11.taojinbi.observation.Observation,
        pageType: PageType,
    ) {
        val prefs = getSharedPreferences(DEBUG_REQUEST_PREFS, Context.MODE_PRIVATE)
        val until = prefs.getLong(DEBUG_COIN_MAINLINE_UNTIL, 0L)
        if (until <= 0L) {
            return
        }

        val now = System.currentTimeMillis()
        if (now > until) {
            prefs.edit()
                .remove(DEBUG_COIN_MAINLINE_UNTIL)
                .remove(DEBUG_COIN_MAINLINE_USER_ID)
                .apply()
            Log.w(ONE_TASK_TAG, "queued coin mainline expired")
            return
        }

        if (
            !ObserverState.latestObservationValid ||
            (pageType != PageType.COIN_HOME &&
                pageType != PageType.DAILY_TASK_LIST)
        ) {
            return
        }

        val targetUserId = prefs.getInt(
            DEBUG_COIN_MAINLINE_USER_ID,
            DEFAULT_DEBUG_TARGET_USER_ID,
        )
        prefs.edit()
            .remove(DEBUG_COIN_MAINLINE_UNTIL)
            .remove(DEBUG_COIN_MAINLINE_USER_ID)
            .apply()
        val result = startCoinMainline(targetUserId)
        Log.i(
            ONE_TASK_TAG,
            "queued coin mainline start on Observation #" +
                observation.id +
                " page=" + pageType.wireName +
                " -> " + result,
        )
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

        private const val SIGN_ENTRY_WAIT_MS = 8_000L
        private const val SIGN_ENTRY_RETRY_MS = 800L
        private const val ENTRY_CLICK_TASK_LIST_WAIT_MS = 3_000L
        private const val TASK_LIST_CHECK_INTERVAL_MS = 600L
        private const val DAILY_VERSION_ANIMATION_MS = 3_000L
        private const val DAILY_ENTRY_WAIT_MS = 8_000L
        private const val DAILY_ENTRY_RETRY_MS = 1_000L
        private const val ENTRY_OBSERVATION_RETRY_MS = 500L
        private const val ENTER_BROWSE_TIMEOUT_MS = 6_000L
        private const val REWARD_RESULT_TIMEOUT_MS = 5_000L
        private const val BROWSE_DURATION_MS = 30_000L
        private const val BROWSE_FIRST_OCR_DELAY_MS = 8_000L
        private const val BROWSE_OCR_INTERVAL_MS = 2_000L
        private const val BROWSE_SWIPE_INTERVAL_MS = 800L
        private const val BROWSE_TICK_MS = 200L
        private const val RETURN_TIMEOUT_MS = 12_000L
        private const val RETURN_ACTION_MIN_INTERVAL_MS = 900L
        private const val RETURN_BACK_SETTLE_MS = 450L
        private const val RETURN_WATCHDOG_INTERVAL_MS = 850L
        private const val RETURN_CAPTURE_AFTER_BACK_MS = 250L
        private const val MAX_TASK_LIST_SCROLLS = 4
        private const val MAX_MAINLINE_TASK_LIST_SCROLLS = 8
        private const val MAX_RETURN_BACKS = 5
        private const val EXTERNAL_INITIAL_SETTLE_MS = 1_000L
        private const val EXTERNAL_WATCHDOG_INTERVAL_MS = 1_000L
        private const val EXTERNAL_TASK_MAX_MS = 12_000L
        private const val EXTERNAL_SESSION_MAX_AGE_MS = 180_000L
        private const val EXTERNAL_SWIPE_COUNT = 3
        private const val EXTERNAL_RECOVERY_TIMEOUT_MS = 8_000L
        private const val EXTERNAL_RECOVERY_FALLBACK_TIMEOUT_MS = 12_000L
        private const val EXTERNAL_RECOVERY_BACK_LIMIT = 4
        private const val DEBUG_MAINLINE_QUEUE_TTL_MS = 30_000L
        private const val ONE_TASK_TRACE_FILE = "coin_mainline_trace.log"
        private const val DEBUG_REQUEST_PREFS = "debug_run_requests"
        private const val DEBUG_COIN_MAINLINE_UNTIL = "coin_mainline_until"
        private const val DEBUG_COIN_MAINLINE_USER_ID = "coin_mainline_user_id"
        private const val DEFAULT_DEBUG_TARGET_USER_ID = 999
        private const val LAUNCHER_PACKAGE = "com.android.launcher"
        private val TRANSIENT_EXTERNAL_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.lbe.security.miui",
        )
        private const val COIN_HOME_URL =
            "https://pages-fast.m.taobao.com/wow/z/tmtjb/town/home?utparam=%7B%22ranger_buckets_native%22%3A%22tsp6443_32421_standardVersion%22%7D&spm=a2141.1.iconsv5.5&miniappSourceChannel=homepage&scm=1007.home_icon.lingjb.d&x-ssr=true&disableNav=YES&x-sec=wua&pha_h5=true&pha_nav=true&uniapp_id=1011525&uniapp_page=home&hd_from=tbHome"
        private val ENTRY_PROGRESS_REGEX = Regex("[（(]\\d+/\\d+[）)]")

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

        fun debugQueueCoinMainline(
            context: Context,
            targetUserId: Int,
        ): String {
            val service = instance
            val observation = ObserverState.latestExternalObservation
            val recognition = RecognitionState.latest
            if (
                service != null &&
                observation != null &&
                ObserverState.latestObservationValid &&
                recognition?.observationId == observation.id &&
                (
                    recognition.result.pageType == PageType.COIN_HOME ||
                        recognition.result.pageType == PageType.DAILY_TASK_LIST
                )
            ) {
                return service.startCoinMainline(targetUserId)
            }

            context.getSharedPreferences(
                DEBUG_REQUEST_PREFS,
                Context.MODE_PRIVATE,
            ).edit()
                .putLong(
                    DEBUG_COIN_MAINLINE_UNTIL,
                    System.currentTimeMillis() + DEBUG_MAINLINE_QUEUE_TTL_MS,
                )
                .putInt(
                    DEBUG_COIN_MAINLINE_USER_ID,
                    targetUserId,
                )
                .apply()
            return "accepted run_coin_mainline queued awaiting service/coin page"
        }

        fun debugClearQueuedCoinMainline(context: Context) {
            context.getSharedPreferences(
                DEBUG_REQUEST_PREFS,
                Context.MODE_PRIVATE,
            ).edit()
                .remove(DEBUG_COIN_MAINLINE_UNTIL)
                .remove(DEBUG_COIN_MAINLINE_USER_ID)
                .apply()
        }

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
