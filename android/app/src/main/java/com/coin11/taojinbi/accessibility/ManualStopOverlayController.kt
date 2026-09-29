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
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
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
        val display = service.resources.displayMetrics
        val defaultX = (display.widthPixels - size - margin).coerceAtLeast(0)
        val defaultY = (display.heightPixels / 3).coerceAtLeast(0)

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
            x = prefs.getInt(PREF_X, defaultX)
                .coerceIn(0, (display.widthPixels - size).coerceAtLeast(0))
            y = prefs.getInt(PREF_Y, defaultY)
                .coerceIn(0, (display.heightPixels - size).coerceAtLeast(0))
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
                            if (
                                abs(dx) > touchSlop ||
                                abs(dy) > touchSlop
                            ) {
                                dragging = true
                            }

                            if (dragging) {
                                val maxX =
                                    (display.widthPixels - size).coerceAtLeast(0)
                                val maxY =
                                    (display.heightPixels - size).coerceAtLeast(0)
                                params.x = (startX + dx).coerceIn(0, maxX)
                                params.y = (startY + dy).coerceIn(0, maxY)
                                runCatching {
                                    windowManager.updateViewLayout(v, params)
                                }.onFailure { error ->
                                    Log.w(TAG, "update overlay position failed", error)
                                }
                            }
                            return true
                        }

                        MotionEvent.ACTION_UP -> {
                            if (dragging) {
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
            Log.i(TAG, "manual stop overlay shown")
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

    private fun persistPosition(params: WindowManager.LayoutParams) {
        prefs.edit()
            .putInt(PREF_X, params.x)
            .putInt(PREF_Y, params.y)
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
        private const val STOPPED_VISIBLE_MS = 650L
        private const val RUNNING_COLOR = 0xD9D32F2F.toInt()
        private const val STOPPED_COLOR = 0xB86B7280.toInt()
    }
}
