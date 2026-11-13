package com.sanny.pointertest

import android.view.MotionEvent
import kotlin.math.hypot

/**
 * Logika penanganan koordinat pointer, pergerakan relatif, akselerasi,
 * pembatasan layar, dan deteksi ketukan kalibrasi sementara.
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

    var onPositionChanged: ((Float, Float) -> Unit)? = null
    var onCalibrationTapTriggered: ((Float, Float) -> Unit)? = null

    // Tracking sentuhan untuk pergerakan relatif & akselerasi
    private var prevTouchX: Float = 0f
    private var prevTouchY: Float = 0f
    private var prevTouchTime: Long = 0L

    // Tracking untuk deteksi tap kalibrasi sementara (durasi < 200ms, jarak < 10px)
    private var downTouchX: Float = 0f
    private var downTouchY: Float = 0f
    private var downTouchTime: Long = 0L
    private var totalMovementDuringTouch: Float = 0f

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

    /**
     * Memproses MotionEvent dari zona sentuh layar.
     * Mengembalikan true untuk menangkap seluruh sentuhan.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                prevTouchX = event.rawX
                prevTouchY = event.rawY
                prevTouchTime = event.eventTime

                downTouchX = event.rawX
                downTouchY = event.rawY
                downTouchTime = event.eventTime
                totalMovementDuringTouch = 0f
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

                // Hitung kecepatan jari (px/ms)
                val speedPxPerMs = distance / dt.toFloat()

                // Hitung faktor pengali akselerasi berdasarkan objek Config
                val gain = PointerConfig.calculateGain(speedPxPerMs)

                // Pergerakan RELATIF dengan akselerasi, dibatasi di dalam batas layar
                pointerX = (pointerX + dx * gain).coerceIn(0f, (screenWidth - 1).toFloat())
                pointerY = (pointerY + dy * gain).coerceIn(0f, (screenHeight - 1).toFloat())

                prevTouchX = curX
                prevTouchY = curY
                prevTouchTime = curTime

                onPositionChanged?.invoke(pointerX, pointerY)
            }

            MotionEvent.ACTION_UP -> {
                val curTime = event.eventTime
                val duration = curTime - downTouchTime
                val netDistance = hypot(
                    (event.rawX - downTouchX).toDouble(),
                    (event.rawY - downTouchY).toDouble()
                ).toFloat()

                // UJI KALIBRASI SEMENTARA:
                // Ketukan singkat di bawah 200 ms dan pergerakan di bawah 10 px.
                // Bertujuan memverifikasi bahwa posisi kursor visual sejajar dengan titik
                // koordinat sentuhan fisik yang diterima sistem (termasuk pada mode landscape).
                // CATATAN: Ini adalah uji kalibrasi sementara dan akan diganti di tahap berikutnya.
                if (duration < 200 && netDistance < 10f && totalMovementDuringTouch < 15f) {
                    onCalibrationTapTriggered?.invoke(pointerX, pointerY)
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                totalMovementDuringTouch = 0f
            }
        }
        return true
    }
}
