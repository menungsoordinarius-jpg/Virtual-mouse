package com.sanny.pointertest

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Logika penanganan koordinat pointer, pergerakan relatif, akselerasi,
 * pembatasan layar, serta gestur mode biasa (Tap, HOLD, Tahan lalu Geser).
 */
class PointerEngine {

    var pointerX: Float = 0f
        private set
    var pointerY: Float = 0f
        private set

    var screenWidth: Int = 1080
        private set
    var screenHeight: Int = 2400
        private set

    var isHolding: Boolean = false
        private set

    var onPositionChanged: ((Float, Float) -> Unit)? = null
    var onTapTriggered: ((Float, Float) -> Unit)? = null
    var onHoldStarted: ((Float, Float) -> Unit)? = null
    var onHoldMoved: ((Float, Float) -> Unit)? = null
    var onHoldEnded: ((x: Float, y: Float, totalDx: Float, totalDy: Float, isCancel: Boolean) -> Unit)? = null

    private val mainHandler: Handler? = try {
        Looper.getMainLooper()?.let { Handler(it) }
    } catch (_: Throwable) {
        null
    }

    // Tracking sentuhan untuk pergerakan relatif & akselerasi
    private var prevTouchX: Float = 0f
    private var prevTouchY: Float = 0f
    private var prevTouchTime: Long = 0L

    // Tracking gestur Tap dan HOLD
    private var isFingerDown: Boolean = false
    private var downTouchX: Float = 0f
    private var downTouchY: Float = 0f
    private var downTouchTime: Long = 0L
    private var totalMovementDuringTouch: Float = 0f

    // Posisi pointer saat HOLD dimulai untuk menghitung total gerak (dx, dy)
    private var holdStartPointerX: Float = 0f
    private var holdStartPointerY: Float = 0f

    private val holdRunnable = Runnable {
        if (isFingerDown && !isHolding) {
            isHolding = true
            holdStartPointerX = pointerX
            holdStartPointerY = pointerY
            onHoldStarted?.invoke(pointerX, pointerY)
        }
    }

    fun initializePosition(width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        pointerX = screenWidth / 2f
        pointerY = screenHeight / 2f
        onPositionChanged?.invoke(pointerX, pointerY)
    }

    fun onScreenSizeChanged(width: Int, height: Int) {
        screenWidth = width.coerceAtLeast(1)
        screenHeight = height.coerceAtLeast(1)
        pointerX = pointerX.coerceIn(0f, (screenWidth - 1).toFloat())
        pointerY = pointerY.coerceIn(0f, (screenHeight - 1).toFloat())
        onPositionChanged?.invoke(pointerX, pointerY)
    }

    fun cancelAll() {
        cancelPendingHold()
        isFingerDown = false
        isHolding = false
        totalMovementDuringTouch = 0f
    }

    private fun cancelPendingHold() {
        mainHandler?.removeCallbacks(holdRunnable)
    }

    /**
     * Memproses MotionEvent dari zona sentuh layar.
     * Mengembalikan true untuk menangkap seluruh sentuhan.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingHold()
                isFingerDown = true
                isHolding = false

                prevTouchX = event.rawX
                prevTouchY = event.rawY
                prevTouchTime = event.eventTime

                downTouchX = event.rawX
                downTouchY = event.rawY
                downTouchTime = event.eventTime
                totalMovementDuringTouch = 0f

                mainHandler?.postDelayed(holdRunnable, PointerConfig.holdThresholdMs)
            }

            MotionEvent.ACTION_MOVE -> {
                val curX = event.rawX
                val curY = event.rawY
                val curTime = event.eventTime

                val dx = curX - prevTouchX
                val dy = curY - prevTouchY
                val dt = (curTime - prevTouchTime).coerceAtLeast(1L)

                val distance = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                totalMovementDuringTouch += distance

                val netDistance = hypot(
                    (curX - downTouchX).toDouble(),
                    (curY - downTouchY).toDouble()
                ).toFloat()

                // Jika jari bergerak melebihi batas slop sebelum 300 ms, batalkan pemicu HOLD
                if (!isHolding &&
                    (netDistance >= PointerConfig.tapSlopPx || totalMovementDuringTouch >= PointerConfig.tapSlopPx)
                ) {
                    cancelPendingHold()
                }

                // Hitung kecepatan jari (px/ms)
                val speedPxPerMs = distance / dt.toFloat()

                // Hitung faktor pengali akselerasi berdasarkan objek Config
                val gain = PointerConfig.calculateGain(speedPxPerMs)

                val oldX = pointerX
                val oldY = pointerY

                // Pergerakan RELATIF dengan akselerasi, dibatasi di dalam batas layar
                pointerX = (pointerX + dx * gain).coerceIn(0f, (screenWidth - 1).toFloat())
                pointerY = (pointerY + dy * gain).coerceIn(0f, (screenHeight - 1).toFloat())

                prevTouchX = curX
                prevTouchY = curY
                prevTouchTime = curTime

                if (pointerX != oldX || pointerY != oldY) {
                    onPositionChanged?.invoke(pointerX, pointerY)
                    if (isHolding) {
                        onHoldMoved?.invoke(pointerX, pointerY)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelPendingHold()
                val wasFingerDown = isFingerDown
                isFingerDown = false

                if (isHolding) {
                    isHolding = false
                    val totalDx = pointerX - holdStartPointerX
                    val totalDy = pointerY - holdStartPointerY
                    onHoldEnded?.invoke(pointerX, pointerY, totalDx, totalDy, false)
                } else if (wasFingerDown) {
                    val curTime = event.eventTime
                    val duration = curTime - downTouchTime
                    val netDistance = hypot(
                        (event.rawX - downTouchX).toDouble(),
                        (event.rawY - downTouchY).toDouble()
                    ).toFloat()

                    // Gestur Tap: sentuhan singkat (< 200 ms, gerak < 10 px)
                    if (duration < PointerConfig.tapMaxDurationMs &&
                        netDistance < PointerConfig.tapSlopPx &&
                        totalMovementDuringTouch < PointerConfig.tapSlopPx
                    ) {
                        onTapTriggered?.invoke(pointerX, pointerY)
                    }
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingHold()
                isFingerDown = false

                if (isHolding) {
                    isHolding = false
                    val totalDx = pointerX - holdStartPointerX
                    val totalDy = pointerY - holdStartPointerY
                    onHoldEnded?.invoke(pointerX, pointerY, totalDx, totalDy, true)
                } else {
                    totalMovementDuringTouch = 0f
                }
            }
        }
        return true
    }
}
