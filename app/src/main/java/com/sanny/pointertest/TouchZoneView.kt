package com.sanny.pointertest

import android.content.Context
import android.graphics.Color
import android.view.MotionEvent
import android.view.View

/**
 * View transparan untuk zona sentuh overlay (TYPE_APPLICATION_OVERLAY).
 * Menangkap seluruh sentuhan dan meneruskannya ke PointerEngine.
 */
class TouchZoneView(
    context: Context,
    private val engine: PointerEngine
) : View(context) {

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        return engine.onTouchEvent(event)
    }
}
