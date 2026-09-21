package com.coin11.taojinbi.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.coin11.taojinbi.observation.ObservationCollector
import com.coin11.taojinbi.observation.ObserverState

class TaojinbiAccessibilityService : AccessibilityService() {

    private val collector = ObservationCollector()
    private val handler = Handler(Looper.getMainLooper())
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

        instance = this
        scheduleCapture()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val packageName = event?.packageName?.toString()
        if (packageName == this.packageName) {
            return
        }
        scheduleCapture()
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        handler.removeCallbacks(captureRunnable)
        if (instance === this) {
            instance = null
        }
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

        // Opening the observer itself must not overwrite the last Taobao/external snapshot.
        if (currentPackage == packageName) {
            return
        }

        lastCaptureAt = System.currentTimeMillis()
        runCatching {
            collector.collect(root)
        }.onSuccess { observation ->
            ObserverState.publish(observation)
        }
    }

    companion object {
        private const val MIN_CAPTURE_INTERVAL_MS = 350L
        private const val EVENT_SETTLE_MS = 120L

        @Volatile
        private var instance: TaojinbiAccessibilityService? = null

        fun isRunning(): Boolean = instance != null
    }
}
