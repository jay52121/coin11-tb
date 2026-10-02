package com.coin11.taojinbi.accessibility

import kotlin.math.abs

internal object OverlayGesturePolicy {
    fun isDrag(dx: Int, dy: Int, touchSlop: Int): Boolean =
        abs(dx) > touchSlop || abs(dy) > touchSlop
}
