package com.sanny.pointertest

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Logika penanganan koordinat pointer, pergerakan relatif, akselerasi,
 * pembatasan layar, gestur mode biasa (Tap, HOLD, Tahan lalu Geser),
 * serta gestur mode mouse (Hover, Klik Kiri/Kanan, Drag Kiri/Kanan, Scroll 2 Jari).
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

    var mode: PointerMode = PointerMode.NORMAL
        private set

    val isGestureActive: Boolean
        get() = isFingerDown || isHolding || isTwoFingerScrolling

    // Callbacks umum & mode NORMAL (tidak diubah)
    var onPositionChanged: ((Float, Float) -> Unit)? = null
    var onTapTriggered: ((Float, Float) -> Unit)? = null
    var onHoldStarted: ((Float, Float) -> Unit)? = null
    var onHoldMoved: ((Float, Float) -> Unit)? = null
    var onHoldEnded: ((x: Float, y: Float, totalDx: Float, totalDy: Float, isCancel: Boolean) -> Unit)? = null

    // Callbacks mode MOUSE
    var onMouseHoverMoved: ((Float, Float) -> Unit)? = null
    var onMouseLeftClick: ((Float, Float) -> Unit)? = null
    var onMouseRightClick: ((Float, Float) -> Unit)? = null
    var onMouseDragStarted: ((x: Float, y: Float, button: Int) -> Unit)? = null
    var onMouseDragMoved: ((x: Float, y: Float, button: Int) -> Unit)? = null
    var onMouseDragEnded: ((x: Float, y: Float, totalDx: Float, totalDy: Float, button: Int, isCancel: Boolean) -> Unit)? = null
    var onMouseScroll: ((x: Float, y: Float, vScroll: Float) -> Unit)? = null
    var onMouseNotTouchableRequired: ((reason: String) -> Unit)? = null
    var onTouchGestureFinished: (() -> Unit)? = null

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

    // Posisi pointer saat HOLD/Drag dimulai untuk menghitung total gerak (dx, dy)
    private var holdStartPointerX: Float = 0f
    private var holdStartPointerY: Float = 0f

    // State khusus mode MOUSE
    private var activeFingerPointerId: Int = MotionEvent.INVALID_POINTER_ID
    private var isSecondTap: Boolean = false
    private var hasCrossedSlopInMouseTouch: Boolean = false
    private var activeMouseButton: Int = 0
    private var pendingSingleTapRunnable: Runnable? = null

    // State scroll 2 jari mode MOUSE
    private var isTwoFingerScrolling: Boolean = false
    private var ignoreUntilAllUp: Boolean = false
    private var scrollPointerId1: Int = MotionEvent.INVALID_POINTER_ID
    private var scrollPointerId2: Int = MotionEvent.INVALID_POINTER_ID
    private var prevScrollAvgY: Float = 0f
    private var prevScrollTime: Long = 0L

    private val holdRunnable = Runnable {
        if (mode == PointerMode.MOUSE) {
            if (isFingerDown && !isHolding && !isTwoFingerScrolling) {
                isHolding = true
                val btn = if (isSecondTap) MotionEvent.BUTTON_SECONDARY else MotionEvent.BUTTON_PRIMARY
                activeMouseButton = btn
                isSecondTap = false
                holdStartPointerX = pointerX
                holdStartPointerY = pointerY
                onMouseNotTouchableRequired?.invoke("HOLD")
                onMouseDragStarted?.invoke(pointerX, pointerY, btn)
            }
        } else {
            if (isFingerDown && !isHolding) {
                isHolding = true
                holdStartPointerX = pointerX
                holdStartPointerY = pointerY
                onHoldStarted?.invoke(pointerX, pointerY)
            }
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

    fun setMode(newMode: PointerMode) {
        if (mode == newMode) return
        forceReleaseActiveGesture(isCancel = true)
        mode = newMode
    }

    fun forceReleaseActiveGesture(isCancel: Boolean = true) {
        cancelPendingHold()
        cancelPendingSingleTap()

        val wasHolding = isHolding
        val prevMode = mode
        val prevButton = activeMouseButton

        resetTouchState()

        if (wasHolding) {
            val totalDx = pointerX - holdStartPointerX
            val totalDy = pointerY - holdStartPointerY
            if (prevMode == PointerMode.MOUSE && prevButton != 0) {
                onMouseDragEnded?.invoke(pointerX, pointerY, totalDx, totalDy, prevButton, isCancel)
            } else if (prevMode == PointerMode.NORMAL) {
                onHoldEnded?.invoke(pointerX, pointerY, totalDx, totalDy, isCancel)
            }
        }
    }

    fun cancelAll() {
        cancelPendingHold()
        cancelPendingSingleTap()
        resetTouchState()
    }

    private fun resetTouchState() {
        isFingerDown = false
        isHolding = false
        isSecondTap = false
        hasCrossedSlopInMouseTouch = false
        activeMouseButton = 0
        isTwoFingerScrolling = false
        ignoreUntilAllUp = false
        activeFingerPointerId = MotionEvent.INVALID_POINTER_ID
        scrollPointerId1 = MotionEvent.INVALID_POINTER_ID
        scrollPointerId2 = MotionEvent.INVALID_POINTER_ID
        totalMovementDuringTouch = 0f
    }

    private fun cancelPendingHold() {
        mainHandler?.removeCallbacks(holdRunnable)
    }

    private fun cancelPendingSingleTap() {
        pendingSingleTapRunnable?.let { runnable ->
            mainHandler?.removeCallbacks(runnable)
        }
        pendingSingleTapRunnable = null
    }

    /**
     * Memproses MotionEvent dari zona sentuh layar sesuai mode aktif.
     * Mengembalikan true untuk menangkap seluruh sentuhan.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        return if (mode == PointerMode.MOUSE) {
            onTouchEventMouse(event)
        } else {
            onTouchEventNormal(event)
        }
    }

    private fun onTouchEventNormal(event: MotionEvent): Boolean {
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

    private fun onTouchEventMouse(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancelPendingHold()
                val hasPendingTap = (pendingSingleTapRunnable != null)
                cancelPendingSingleTap()

                isSecondTap = hasPendingTap
                hasCrossedSlopInMouseTouch = false
                isFingerDown = true
                isHolding = false
                activeMouseButton = 0
                isTwoFingerScrolling = false
                ignoreUntilAllUp = false
                activeFingerPointerId = event.getPointerId(0)

                val x0 = event.getX(0)
                val y0 = event.getY(0)

                prevTouchX = x0
                prevTouchY = y0
                prevTouchTime = event.eventTime

                downTouchX = x0
                downTouchY = y0
                downTouchTime = event.eventTime
                totalMovementDuringTouch = 0f

                mainHandler?.postDelayed(holdRunnable, PointerConfig.holdThresholdMs)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Saat dua jari menyentuh, batalkan ketukan yang menunggu
                cancelPendingSingleTap()

                if (!isHolding && event.pointerCount >= 2) {
                    cancelPendingHold()
                    isFingerDown = false
                    isSecondTap = false
                    ignoreUntilAllUp = false
                    isTwoFingerScrolling = true

                    scrollPointerId1 = event.getPointerId(0)
                    scrollPointerId2 = event.getPointerId(1)
                    prevScrollAvgY = (event.getY(0) + event.getY(1)) / 2f
                    prevScrollTime = event.eventTime
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTwoFingerScrolling) {
                    val idx1 = event.findPointerIndex(scrollPointerId1)
                    val idx2 = event.findPointerIndex(scrollPointerId2)
                    if (idx1 >= 0 && idx2 >= 0) {
                        val avgY = (event.getY(idx1) + event.getY(idx2)) / 2f
                        val dy = avgY - prevScrollAvgY
                        val curTime = event.eventTime
                        val dt = (curTime - prevScrollTime).coerceAtLeast(1L)

                        prevScrollAvgY = avgY
                        prevScrollTime = curTime

                        if (abs(dy) > 0.01f) {
                            val speedPxPerMs = abs(dy) / dt.toFloat()
                            val gain = PointerConfig.calculateScrollGain(speedPxPerMs)
                            val rawVScroll = (dy * gain) / PointerConfig.scrollPxPerNotch
                            val vScroll = if (PointerConfig.naturalScroll) rawVScroll else -rawVScroll
                            onMouseScroll?.invoke(pointerX, pointerY, vScroll)
                        }
                    }
                } else if (!ignoreUntilAllUp) {
                    val idx = event.findPointerIndex(activeFingerPointerId)
                    if (idx < 0) return true

                    val curX = event.getX(idx)
                    val curY = event.getY(idx)
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

                    if (!isHolding &&
                        (netDistance >= PointerConfig.tapSlopPx || totalMovementDuringTouch >= PointerConfig.tapSlopPx)
                    ) {
                        cancelPendingHold()
                        isSecondTap = false
                        hasCrossedSlopInMouseTouch = true
                    }

                    val speedPxPerMs = distance / dt.toFloat()
                    val gain = PointerConfig.calculateGain(speedPxPerMs)

                    val oldX = pointerX
                    val oldY = pointerY

                    pointerX = (pointerX + dx * gain).coerceIn(0f, (screenWidth - 1).toFloat())
                    pointerY = (pointerY + dy * gain).coerceIn(0f, (screenHeight - 1).toFloat())

                    prevTouchX = curX
                    prevTouchY = curY
                    prevTouchTime = curTime

                    if (pointerX != oldX || pointerY != oldY) {
                        onPositionChanged?.invoke(pointerX, pointerY)
                        if (isHolding && activeMouseButton != 0) {
                            onMouseDragMoved?.invoke(pointerX, pointerY, activeMouseButton)
                        } else if (hasCrossedSlopInMouseTouch) {
                            onMouseHoverMoved?.invoke(pointerX, pointerY)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val liftedId = event.getPointerId(event.actionIndex)
                if (isTwoFingerScrolling) {
                    if (liftedId == scrollPointerId1 || liftedId == scrollPointerId2) {
                        isTwoFingerScrolling = false
                        ignoreUntilAllUp = true
                        scrollPointerId1 = MotionEvent.INVALID_POINTER_ID
                        scrollPointerId2 = MotionEvent.INVALID_POINTER_ID
                    }
                } else if (isHolding && liftedId == activeFingerPointerId) {
                    isHolding = false
                    isFingerDown = false
                    ignoreUntilAllUp = true
                    val btn = activeMouseButton
                    activeMouseButton = 0
                    activeFingerPointerId = MotionEvent.INVALID_POINTER_ID
                    val totalDx = pointerX - holdStartPointerX
                    val totalDy = pointerY - holdStartPointerY
                    if (btn != 0) {
                        onMouseDragEnded?.invoke(pointerX, pointerY, totalDx, totalDy, btn, false)
                    }
                }
            }

            MotionEvent.ACTION_UP -> {
                cancelPendingHold()
                if (isTwoFingerScrolling || ignoreUntilAllUp) {
                    isTwoFingerScrolling = false
                    ignoreUntilAllUp = false
                    isFingerDown = false
                    isSecondTap = false
                    activeFingerPointerId = MotionEvent.INVALID_POINTER_ID
                    scrollPointerId1 = MotionEvent.INVALID_POINTER_ID
                    scrollPointerId2 = MotionEvent.INVALID_POINTER_ID
                    onTouchGestureFinished?.invoke()
                    return true
                }

                val wasFingerDown = isFingerDown
                val wasSecondTap = isSecondTap
                isFingerDown = false
                isSecondTap = false
                activeFingerPointerId = MotionEvent.INVALID_POINTER_ID

                if (isHolding) {
                    isHolding = false
                    val btn = activeMouseButton
                    activeMouseButton = 0
                    val totalDx = pointerX - holdStartPointerX
                    val totalDy = pointerY - holdStartPointerY
                    if (btn != 0) {
                        onMouseDragEnded?.invoke(pointerX, pointerY, totalDx, totalDy, btn, false)
                    }
                } else if (wasFingerDown) {
                    val idx = event.actionIndex
                    val upX = event.getX(idx)
                    val upY = event.getY(idx)
                    val duration = event.eventTime - downTouchTime
                    val netDistance = hypot(
                        (upX - downTouchX).toDouble(),
                        (upY - downTouchY).toDouble()
                    ).toFloat()

                    if (wasSecondTap &&
                        duration < PointerConfig.holdThresholdMs &&
                        netDistance < PointerConfig.tapSlopPx &&
                        totalMovementDuringTouch < PointerConfig.tapSlopPx
                    ) {
                        onMouseRightClick?.invoke(pointerX, pointerY)
                    } else if (!wasSecondTap &&
                        duration < PointerConfig.tapMaxDurationMs &&
                        netDistance < PointerConfig.tapSlopPx &&
                        totalMovementDuringTouch < PointerConfig.tapSlopPx
                    ) {
                        val tapX = pointerX
                        val tapY = pointerY
                        val runnable = Runnable {
                            pendingSingleTapRunnable = null
                            if (mode == PointerMode.MOUSE && !isFingerDown && !isHolding && !isTwoFingerScrolling) {
                                onMouseLeftClick?.invoke(tapX, tapY)
                            }
                        }
                        pendingSingleTapRunnable = runnable
                        if (mainHandler != null) {
                            mainHandler.postDelayed(runnable, PointerConfig.doubleTapWindowMs)
                        } else {
                            runnable.run()
                        }
                    }
                }
                onTouchGestureFinished?.invoke()
            }

            MotionEvent.ACTION_CANCEL -> {
                cancelPendingHold()
                cancelPendingSingleTap()
                val wasHolding = isHolding
                val btn = activeMouseButton
                resetTouchState()

                if (wasHolding && btn != 0) {
                    val totalDx = pointerX - holdStartPointerX
                    val totalDy = pointerY - holdStartPointerY
                    onMouseDragEnded?.invoke(pointerX, pointerY, totalDx, totalDy, btn, true)
                }
                onTouchGestureFinished?.invoke()
            }
        }
        return true
    }
}
