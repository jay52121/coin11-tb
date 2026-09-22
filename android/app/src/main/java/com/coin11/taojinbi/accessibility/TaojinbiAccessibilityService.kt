package com.coin11.taojinbi.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import com.coin11.taojinbi.actions.AccessibilityActionExecutor
import com.coin11.taojinbi.actions.ActionResult
import com.coin11.taojinbi.capability.CapabilityState
import com.coin11.taojinbi.observation.ObservationCollector
import com.coin11.taojinbi.observation.ObserverState
import com.coin11.taojinbi.ocr.MlKitChineseOcr
import com.coin11.taojinbi.ocr.OcrSnapshot
import com.coin11.taojinbi.ocr.OcrState
import com.coin11.taojinbi.recognizer.PageRecognizer
import com.coin11.taojinbi.recognizer.RecognitionSnapshot
import com.coin11.taojinbi.recognizer.RecognitionState
import com.coin11.taojinbi.recognizer.RulesLoader

class TaojinbiAccessibilityService : AccessibilityService() {

    private val collector = ObservationCollector()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var pageRecognizer: PageRecognizer
    private lateinit var actionExecutor: AccessibilityActionExecutor
    private var lastCaptureAt = 0L

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
        CapabilityState.publish(
            "Accessibility",
            buildString {
                appendLine("服务已连接。")
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
        CapabilityState.publish("Accessibility", "服务已销毁。")
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
        }.onFailure { error ->
            CapabilityState.publish("Observation 采集失败", error.stackTraceToString())
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
            ObserverState.invalidate("tap(" + x + "," + y + ")")
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
        }
    }

    private fun globalBack() {
        val result = actionExecutor.back()
        publishActionResult("Accessibility Back", result)
        if (result.success) {
            ObserverState.invalidate("back")
        }
    }

    private fun publishActionResult(label: String, result: ActionResult) {
        CapabilityState.publish(
            label,
            if (result.success) {
                "成功：" + result.detail
            } else {
                "失败：" + result.detail
            },
        )
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
        private const val MIN_CAPTURE_INTERVAL_MS = 350L
        private const val EVENT_SETTLE_MS = 120L

        @Volatile
        private var instance: TaojinbiAccessibilityService? = null

        fun isRunning(): Boolean = instance != null

        fun scheduleTapCenter(delayMs: Long = 1800L): Boolean =
            instance?.let { service ->
                service.schedule("Tap 测试", delayMs, service::tapCenter)
                true
            } ?: false

        fun scheduleSwipeUp(delayMs: Long = 1800L): Boolean =
            instance?.let { service ->
                service.schedule("Swipe 测试", delayMs, service::swipeUp)
                true
            } ?: false

        fun scheduleBack(delayMs: Long = 1800L): Boolean =
            instance?.let { service ->
                service.schedule("Back 测试", delayMs, service::globalBack)
                true
            } ?: false

        fun scheduleScreenshotAndOcr(delayMs: Long = 2200L): Boolean =
            instance?.let { service ->
                service.schedule("Screenshot + OCR 测试", delayMs, service::screenshotAndOcr)
                true
            } ?: false
    }
}
