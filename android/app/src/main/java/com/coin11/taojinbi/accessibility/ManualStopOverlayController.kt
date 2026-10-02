package com.coin11.taojinbi.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.TextView
import com.coin11.taojinbi.observation.IntRect
import kotlin.math.roundToInt

internal class ManualStopOverlayController(
    private val service: AccessibilityService,
    private val onStopRequested: () -> Unit,
) {
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val prefs = service.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE,
    )

    private var button: TextView? = null
    private var layoutParams: WindowManager.LayoutParams? = null
    private val hideRunnable = Runnable { hide() }

    fun isShowing(): Boolean = button != null

    fun show() {
        val existing = button
        if (existing != null) {
            existing.removeCallbacks(hideRunnable)
            applyRunningVisual(existing)
            existing.isEnabled = true
            return
        }

        val size = dp(48)
        val margin = dp(12)
        val metrics = windowManager.currentWindowMetrics
        val displayWidth = metrics.bounds.width()
        val displayHeight = metrics.bounds.height()
        val systemInsets = metrics.windowInsets.getInsetsIgnoringVisibility(
            WindowInsets.Type.systemBars(),
        )
        val defaultX = (systemInsets.left + margin)
            .coerceIn(0, (displayWidth - size).coerceAtLeast(0))
        val usableTop = systemInsets.top + margin
        val usableBottom =
            (displayHeight - systemInsets.bottom - size - margin)
                .coerceAtLeast(usableTop)
        val defaultY = (
            usableTop +
                ((usableBottom - usableTop) * 0.45f).roundToInt()
        ).coerceIn(usableTop, usableBottom)
        val savedLayoutVersion = prefs.getInt(PREF_LAYOUT_VERSION, 0)
        val useSavedPosition = savedLayoutVersion >= LAYOUT_VERSION

        val params = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (
                if (useSavedPosition) prefs.getInt(PREF_X, defaultX)
                else defaultX
            ).coerceIn(0, (displayWidth - size).coerceAtLeast(0))
            y = (
                if (useSavedPosition) prefs.getInt(PREF_Y, defaultY)
                else defaultY
            ).coerceIn(0, (displayHeight - size).coerceAtLeast(0))
        }

        val view = TextView(service).apply {
            text = "■"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            contentDescription = "停止淘金币自动化"
            applyRunningVisual(this)
            setOnClickListener {
                isEnabled = false
                onStopRequested()
            }
        }

        val touchSlop = ViewConfiguration.get(service).scaledTouchSlop
        view.setOnTouchListener(
            object : View.OnTouchListener {
                private var downRawX = 0f
                private var downRawY = 0f
                private var startX = 0
                private var startY = 0
                private var dragging = false

                override fun onTouch(v: View, event: MotionEvent): Boolean {
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            downRawX = event.rawX
                            downRawY = event.rawY
                            startX = params.x
                            startY = params.y
                            dragging = false
                            return true
                        }

                        MotionEvent.ACTION_MOVE -> {
                            val dx = (event.rawX - downRawX).roundToInt()
                            val dy = (event.rawY - downRawY).roundToInt()
                            if (OverlayGesturePolicy.isDrag(dx, dy, touchSlop)) {
                                dragging = true
                            }

                            if (dragging) {
                                moveOverlay(
                                    view = v,
                                    params = params,
                                    x = startX + dx,
                                    y = startY + dy,
                                    displayWidth = displayWidth,
                                    displayHeight = displayHeight,
                                    size = size,
                                )
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            val finalDx = (event.rawX - downRawX).roundToInt()
                            val finalDy = (event.rawY - downRawY).roundToInt()
                            val finalIsDrag =
                                dragging ||
                                    OverlayGesturePolicy.isDrag(
                                        finalDx,
                                        finalDy,
                                        touchSlop,
                                    )
                            if (finalIsDrag) {
                                moveOverlay(
                                    view = v,
                                    params = params,
                                    x = startX + finalDx,
                                    y = startY + finalDy,
                                    displayWidth = displayWidth,
                                    displayHeight = displayHeight,
                                    size = size,
                                )
                                persistPosition(params)
                            } else {
                                v.performClick()
                            }
                            return true
                        }

                        MotionEvent.ACTION_CANCEL -> {
                            if (dragging) {
                                persistPosition(params)
                            }
                            return true
                        }

                        else -> return false
                    }
                }
            },
        )

        runCatching {
            windowManager.addView(view, params)
        }.onSuccess {
            button = view
            layoutParams = params
            if (!useSavedPosition) {
                persistPosition(params)
            }
            Log.i(
                TAG,
                "manual stop overlay shown x=" + params.x + " y=" + params.y,
            )
        }.onFailure { error ->
            Log.w(TAG, "show manual stop overlay failed", error)
        }
    }

    fun showStoppedBriefly() {
        val view = button ?: return
        view.removeCallbacks(hideRunnable)
        view.isEnabled = false
        applyStoppedVisual(view)
        view.postDelayed(hideRunnable, STOPPED_VISIBLE_MS)
    }

    fun intersects(bounds: IntRect): Boolean {
        val params = layoutParams ?: return false
        val left = params.x
        val top = params.y
        val right = left + params.width.coerceAtLeast(1)
        val bottom = top + params.height.coerceAtLeast(1)
        return bounds.left < right &&
            bounds.right > left &&
            bounds.top < bottom &&
            bounds.bottom > top
    }

    fun hide() {
        val view = button ?: return
        view.removeCallbacks(hideRunnable)
        runCatching {
            windowManager.removeViewImmediate(view)
        }.onFailure { error ->
            Log.w(TAG, "hide manual stop overlay failed", error)
        }
        button = null
        layoutParams = null
    }

    private fun moveOverlay(
        view: View,
        params: WindowManager.LayoutParams,
        x: Int,
        y: Int,
        displayWidth: Int,
        displayHeight: Int,
        size: Int,
    ) {
        params.x = x.coerceIn(0, (displayWidth - size).coerceAtLeast(0))
        params.y = y.coerceIn(0, (displayHeight - size).coerceAtLeast(0))
        runCatching {
            windowManager.updateViewLayout(view, params)
        }.onFailure { error ->
            Log.w(TAG, "update overlay position failed", error)
        }
    }

    private fun persistPosition(params: WindowManager.LayoutParams) {
        prefs.edit()
            .putInt(PREF_X, params.x)
            .putInt(PREF_Y, params.y)
            .putInt(PREF_LAYOUT_VERSION, LAYOUT_VERSION)
            .apply()
    }

    private fun applyRunningVisual(view: TextView) {
        view.background = circle(RUNNING_COLOR)
        view.alpha = 0.92f
    }

    private fun applyStoppedVisual(view: TextView) {
        view.background = circle(STOPPED_COLOR)
        view.alpha = 0.78f
    }

    private fun circle(color: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density)
            .roundToInt()
            .coerceAtLeast(1)

    companion object {
        private const val TAG = "TaojinbiStopOverlay"
        private const val PREFS_NAME = "manual_stop_overlay"
        private const val PREF_X = "x"
        private const val PREF_Y = "y"
        private const val PREF_LAYOUT_VERSION = "layout_version"
        private const val LAYOUT_VERSION = 3
        private const val STOPPED_VISIBLE_MS = 650L
        private const val RUNNING_COLOR = 0xD9D32F2F.toInt()
        private const val STOPPED_COLOR = 0xB86B7280.toInt()
    }
}
