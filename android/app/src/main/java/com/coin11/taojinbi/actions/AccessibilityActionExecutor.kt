package com.coin11.taojinbi.actions

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import com.coin11.taojinbi.observation.IntRect

enum class ActionKind {
    TAP,
    SWIPE,
    BACK,
}

data class ActionResult(
    val kind: ActionKind,
    val success: Boolean,
    val detail: String,
)

class AccessibilityActionExecutor(
    private val service: AccessibilityService,
) {
    fun tap(
        x: Int,
        y: Int,
        callback: (ActionResult) -> Unit = {},
    ): Boolean {
        val path = Path().apply {
            moveTo(x.toFloat(), y.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS))
            .build()

        val queued = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(ActionResult(ActionKind.TAP, true, "x=" + x + ", y=" + y))
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(ActionResult(ActionKind.TAP, false, "gesture cancelled"))
                }
            },
            null,
        )

        if (!queued) {
            callback(ActionResult(ActionKind.TAP, false, "dispatchGesture rejected"))
        }
        return queued
    }

    fun tap(
        bounds: IntRect,
        callback: (ActionResult) -> Unit = {},
    ): Boolean = tap(
        x = bounds.left + (bounds.right - bounds.left) / 2,
        y = bounds.top + (bounds.bottom - bounds.top) / 2,
        callback = callback,
    )

    fun swipe(
        startX: Int,
        startY: Int,
        endX: Int,
        endY: Int,
        durationMs: Long = DEFAULT_SWIPE_DURATION_MS,
        callback: (ActionResult) -> Unit = {},
    ): Boolean {
        val path = Path().apply {
            moveTo(startX.toFloat(), startY.toFloat())
            lineTo(endX.toFloat(), endY.toFloat())
        }
        val gesture = GestureDescription.Builder()
            .addStroke(
                GestureDescription.StrokeDescription(
                    path,
                    0,
                    durationMs.coerceAtLeast(1L),
                ),
            )
            .build()

        val queued = service.dispatchGesture(
            gesture,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    callback(
                        ActionResult(
                            ActionKind.SWIPE,
                            true,
                            "(" + startX + "," + startY + ") -> (" + endX + "," + endY + "), " + durationMs + "ms",
                        ),
                    )
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    callback(ActionResult(ActionKind.SWIPE, false, "gesture cancelled"))
                }
            },
            null,
        )

        if (!queued) {
            callback(ActionResult(ActionKind.SWIPE, false, "dispatchGesture rejected"))
        }
        return queued
    }

    fun back(): ActionResult {
        val accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        return ActionResult(
            kind = ActionKind.BACK,
            success = accepted,
            detail = "performGlobalAction=" + accepted,
        )
    }

    companion object {
        private const val TAP_DURATION_MS = 80L
        private const val DEFAULT_SWIPE_DURATION_MS = 500L
    }
}
