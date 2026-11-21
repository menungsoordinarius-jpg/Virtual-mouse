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
 * - Saat ada backend aktif: kursor panah putih dengan garis tepi hitam (ujung panah tepat di posisi pointer).
 * - Saat HOLD aktif: lingkaran tipis di sekitar panah sebagai penanda "tertekan".
 * - Saat tidak ada backend sama sekali: ikon loading berputar (animasi 60 fps tanpa alokasi objek per frame).
 */
class CursorView(context: Context) : View(context) {

    var pointerX: Float = 0f
        private set
    var pointerY: Float = 0f
        private set

    var isBackendReady: Boolean = false
        private set

    var isHolding: Boolean = false
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
        strokeWidth = dp(1.3f)
        strokeJoin = Paint.Join.ROUND
    }

    // Penanda HOLD aktif: lingkaran tipis di sekitar panah
    private val holdCenterX = dp(7f)
    private val holdCenterY = dp(13f)
    private val holdRingRadius = dp(18f)
    private val holdRingOuterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.4f)
        color = Color.argb(170, 0, 0, 0)
    }
    private val holdRingInnerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1.4f)
        color = Color.parseColor("#38BDF8")
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
        val s = dp(1.15f)
        arrowPath.reset()
        // Ujung panah berada tepat di (0, 0)
        arrowPath.moveTo(0f, 0f)
        arrowPath.lineTo(0f, 17f * s)
        arrowPath.lineTo(4.5f * s, 13f * s)
        arrowPath.lineTo(7.5f * s, 20f * s)
        arrowPath.lineTo(10f * s, 19f * s)
        arrowPath.lineTo(7f * s, 12f * s)
        arrowPath.lineTo(13f * s, 12f * s)
        arrowPath.close()
    }

    fun updatePosition(x: Float, y: Float) {
        pointerX = x
        pointerY = y
        postInvalidateOnAnimation()
    }

    fun setBackendReady(ready: Boolean) {
        val changed = isBackendReady != ready
        isBackendReady = ready
        if (!isBackendReady) {
            startSpinnerAnimation()
        } else {
            stopSpinnerAnimation()
        }
        if (changed) {
            postInvalidateOnAnimation()
        }
    }

    fun setHolding(holding: Boolean) {
        if (isHolding == holding) return
        isHolding = holding
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

        if (isBackendReady) {
            if (isHolding) {
                // Lingkaran tipis di sekitar panah sebagai penanda HOLD (tertekan)
                canvas.drawCircle(holdCenterX, holdCenterY, holdRingRadius, holdRingOuterPaint)
                canvas.drawCircle(holdCenterX, holdCenterY, holdRingRadius, holdRingInnerPaint)
            }
            // Gambar kursor panah putih bergaris tepi hitam
            canvas.drawPath(arrowPath, arrowFillPaint)
            canvas.drawPath(arrowPath, arrowStrokePaint)
        } else {
            // Gambar ikon loading yang berputar hanya saat tidak ada backend sama sekali
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
