package com.sanny.pointertest

import android.content.pm.PackageManager
import android.os.SystemClock
import android.view.MotionEvent
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku

/**
 * Backend penyuntik input via Shizuku + EventInjector (SOURCE_TOUCHSCREEN).
 * Membungkus logika suntikan yang sudah ada tanpa mengubah perilakunya.
 */
class ShizukuBackend : InputBackend {

    override val name: String = "Shizuku"

    private var holdActive: Boolean = false
    private var holdDownTime: Long = 0L
    private var holdStartX: Float = 0f
    private var holdStartY: Float = 0f
    private var lastHoldX: Float = 0f
    private var lastHoldY: Float = 0f

    override fun isAvailable(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    override suspend fun tap(x: Float, y: Float) {
        if (holdActive) {
            injectTouchEvent(MotionEvent.ACTION_UP, lastHoldX, lastHoldY, holdDownTime)
            holdActive = false
        }

        val downTime = SystemClock.uptimeMillis()
        val resDown = injectTouchEvent(MotionEvent.ACTION_DOWN, x, y, downTime)

        delay(50L)

        val resUp = injectTouchEvent(MotionEvent.ACTION_UP, x, y, downTime)

        Logger.log(
            "Tap",
            "Tap di (${x.toInt()}, ${y.toInt()}): Down=${resDown.success}, Up=${resUp.success}"
        )
    }

    override suspend fun holdDown(x: Float, y: Float) {
        if (holdActive) {
            injectTouchEvent(MotionEvent.ACTION_UP, lastHoldX, lastHoldY, holdDownTime)
            holdActive = false
        }

        val downTime = SystemClock.uptimeMillis()
        val resDown = injectTouchEvent(MotionEvent.ACTION_DOWN, x, y, downTime)

        holdActive = true
        holdDownTime = downTime
        holdStartX = x
        holdStartY = y
        lastHoldX = x
        lastHoldY = y

        Logger.log(
            "HOLD",
            "HOLD dimulai di (${x.toInt()}, ${y.toInt()}): Down=${resDown.success}"
        )
    }

    override suspend fun holdMove(x: Float, y: Float) {
        if (!holdActive) return
        lastHoldX = x
        lastHoldY = y
        injectTouchEvent(MotionEvent.ACTION_MOVE, lastHoldX, lastHoldY, holdDownTime)
    }

    override suspend fun holdUp(x: Float, y: Float) {
        lastHoldX = x
        lastHoldY = y
        val resUp = if (holdActive) {
            injectTouchEvent(MotionEvent.ACTION_UP, lastHoldX, lastHoldY, holdDownTime)
        } else {
            InjectionResult(success = false, details = "HOLD belum menyuntik DOWN")
        }
        val totalDx = (lastHoldX - holdStartX).toInt()
        val totalDy = (lastHoldY - holdStartY).toInt()
        holdActive = false
        Logger.log(
            "HOLD",
            "HOLD selesai (total gerak $totalDx,$totalDy): Up=${resUp.success}"
        )
    }

    private fun injectTouchEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long
    ): InjectionResult {
        val eventTime = if (action == MotionEvent.ACTION_DOWN) downTime else SystemClock.uptimeMillis()
        val event = EventInjector.createTouchEvent(
            action = action,
            x = x,
            y = y,
            downTime = downTime,
            eventTime = eventTime
        )
        val result = EventInjector.injectEvent(event)
        event.recycle()
        return result
    }
}
