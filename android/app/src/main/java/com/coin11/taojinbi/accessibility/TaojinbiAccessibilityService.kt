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
            runPendingActionIfReady(
                observationId = observation.id,
                packageName = observation.packageName,
                pageType = recognition.pageType,
            )
        }.onFailure { error ->
            CapabilityState.publish("Observation 采集失败", error.stackTraceToString())
        }
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

    private fun screenshotAndOcr() {
        val startedAt = SystemClock.elapsedRealtime()
        val currentPackage = rootInActiveWindow?.packageName?.toString()
        if (currentPackage == packageName) {
            CapabilityState.publish(
                "OCR",
                "当前前台是调试 App，未执行截图；请切到目标页面后再触发。",
            )
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
                        CapabilityState.publish(
                            "Screenshot",
                            "系统返回截图，但 Bitmap.wrapHardwareBuffer 失败。",
                        )
                        return
                    }

                    MlKitChineseOcr.recognize(bitmap) { result ->
                        result.onSuccess { recognition ->
                            val snapshot = OcrSnapshot(
                                observationId = observationId,
                                capturedAtMillis = System.currentTimeMillis(),
                                screenshotWidth = bitmap.width,
                                screenshotHeight = bitmap.height,
                                screenshotElapsedMillis = screenshotElapsed,
                                recognitionElapsedMillis = recognition.elapsedMillis,
                                lines = recognition.lines,
                            )
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
                        bitmap.recycle()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    val elapsed = SystemClock.elapsedRealtime() - startedAt
                    CapabilityState.publish(
                        "Screenshot 失败",
                        "errorCode=" + errorCode + ", elapsed=" + elapsed + "ms",
                    )
                }
            },
        )
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
        private const val TAOBAO_PACKAGE = "com.taobao.taobao"
        private const val MIN_CAPTURE_INTERVAL_MS = 350L
        private const val EVENT_SETTLE_MS = 120L

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
            }
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
