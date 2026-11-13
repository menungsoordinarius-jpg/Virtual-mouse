package com.sanny.pointertest

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator

/**
 * View kustom untuk menggambar kursor pointer di jendela TYPE_APPLICATION_OVERLAY.
 * - Saat Shizuku siap: kursor panah putih dengan garis tepi hitam (ujung panah tepat di posisi pointer).
 * - Saat Shizuku belum siap: ikon loading berputar (animasi 60 fps tanpa alokasi objek per frame).
 */
class CursorView(context: Context) : View(context) {

    var pointerX: Float = 0f
        private set
    var pointerY: Float = 0f
        private set

    var isShizukuReady: Boolean = false
        private set

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    // Paints & Paths pre-allocated untuk render mulus 60 fps tanpa garbage collection
    private val arrowPath = Path()
    private val arrowFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
    }
    private val arrowStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.BLACK
        strokeWidth = dp(1.8f)
        strokeJoin = Paint.Join.ROUND
    }

    // Loading spinner assets
    private val spinnerRadius = dp(14f)
    private val spinnerRect = RectF(-spinnerRadius, -spinnerRadius, spinnerRadius, spinnerRadius)
    private val spinnerTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        color = Color.argb(120, 15, 23, 42) // Slate gelap transparan
    }
    private val spinnerArcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(3.2f)
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#38BDF8") // Cyan cerah
    }
    private val spinnerDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.RED
    }

    private var currentSpinnerAngle = 0f
    private var spinnerAnimator: ValueAnimator? = null

    init {
        setBackgroundColor(Color.TRANSPARENT)
        buildArrowPath()
    }

    private fun buildArrowPath() {
        arrowPath.reset()
        // Ujung panah berada tepat di (0, 0)
        arrowPath.moveTo(0f, 0f)
        arrowPath.lineTo(0f, dp(22f))
        arrowPath.lineTo(dp(5.5f), dp(17.5f))
        arrowPath.lineTo(dp(10f), dp(26.5f))
        arrowPath.lineTo(dp(13.5f), dp(25f))
        arrowPath.lineTo(dp(9.2f), dp(16f))
        arrowPath.lineTo(dp(16f), dp(16f))
        arrowPath.close()
    }

    fun updatePosition(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        postInvalidateOnAnimation()
    }

    fun setShizukuReady(ready: Boolean) {
        if (isShizukuReady == ready) return
        isShizukuReady = ready
        if (!isShizukuReady) {
            startSpinnerAnimation()
        } else {
            stopSpinnerAnimation()
        }
        postInvalidateOnAnimation()
    }

    private fun startSpinnerAnimation() {
        if (spinnerAnimator == null) {
            spinnerAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1000L
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { anim ->
                    currentSpinnerAngle = anim.animatedValue as Float
                    postInvalidateOnAnimation()
                }
            }
        }
        if (spinnerAnimator?.isRunning != true) {
            spinnerAnimator?.start()
        }
    }

    private fun stopSpinnerAnimation() {
        spinnerAnimator?.cancel()
        currentSpinnerAngle = 0f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        canvas.save()
        canvas.translate(pointerX, pointerY)

        if (isShizukuReady) {
            // Gambar kursor panah putih bergaris tepi hitam
            canvas.drawPath(arrowPath, arrowFillPaint)
            canvas.drawPath(arrowPath, arrowStrokePaint)
        } else {
            // Gambar ikon loading yang berputar saat Shizuku belum siap
            canvas.drawCircle(0f, 0f, spinnerRadius, spinnerTrackPaint)
            canvas.save()
            canvas.rotate(currentSpinnerAngle)
            canvas.drawArc(spinnerRect, 0f, 100f, false, spinnerArcPaint)
            canvas.restore()
            // Titik tengah sebagai penanda koordinat hotspot persis
            canvas.drawCircle(0f, 0f, dp(2.2f), spinnerDotPaint)
        }

        canvas.restore()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopSpinnerAnimation()
    }
}
