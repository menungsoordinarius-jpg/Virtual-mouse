package com.sanny.pointertest

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

object OverlayManager {
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var touchCount = 0
    private var lastImeHeight = 0
    var isOverlayShowing: Boolean = false
        private set

    var onOverlayStateChanged: ((Boolean) -> Unit)? = null

    fun canDrawOverlays(context: Context): Boolean {
        return Settings.canDrawOverlays(context)
    }

    fun showOverlay(context: Context) {
        if (isOverlayShowing) return
        if (!canDrawOverlays(context)) {
            Logger.log("Overlay", "Izin SYSTEM_ALERT_WINDOW belum diberikan.")
            return
        }

        val appContext = context.applicationContext
        windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val inflater = LayoutInflater.from(appContext)
        val view = inflater.inflate(R.layout.overlay_test_view, null)
        overlayView = view

        val tvStatus = view.findViewById<TextView>(R.id.tvOverlayStatus)
        val btnClose = view.findViewById<Button>(R.id.btnCloseOverlay)
        val touchIndicator = view.findViewById<View>(R.id.touchIndicator)

        touchCount = 0
        lastImeHeight = 0

        btnClose.setOnClickListener {
            hideOverlay()
        }

        // Monitoring WindowInsets (IME keyboard height)
        ViewCompat.setOnApplyWindowInsetsListener(view) { _, insets ->
            val imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val currentImeHeight = imeInsets.bottom

            if (currentImeHeight != lastImeHeight) {
                lastImeHeight = currentImeHeight
                Logger.log("OverlayIME", "IME Insets berubah: Tinggi=${currentImeHeight}px | Terlihat=$imeVisible")
                tvStatus.text = "[Overlay Tes] Sentuhan: $touchCount | IME: ${currentImeHeight}px"
            }
            insets
        }

        // Monitoring touch events across the transparent overlay
        view.setOnTouchListener { _, event ->
            touchCount++
            val actionName = MotionEvent.actionToString(event.action)
            val sourceName = when (event.source) {
                android.view.InputDevice.SOURCE_MOUSE -> "SOURCE_MOUSE"
                android.view.InputDevice.SOURCE_TOUCHSCREEN -> "SOURCE_TOUCHSCREEN"
                android.view.InputDevice.SOURCE_STYLUS -> "SOURCE_STYLUS"
                else -> "SOURCE(0x${Integer.toHexString(event.source)})"
            }
            val toolTypeName = when (if (event.pointerCount > 0) event.getToolType(0) else -1) {
                MotionEvent.TOOL_TYPE_MOUSE -> "TOOL_MOUSE"
                MotionEvent.TOOL_TYPE_FINGER -> "TOOL_FINGER"
                MotionEvent.TOOL_TYPE_STYLUS -> "TOOL_STYLUS"
                else -> "TOOL_UNKNOWN"
            }

            Logger.log(
                "OverlayTouch",
                "#$touchCount $actionName @ (${event.rawX.toInt()}, ${event.rawY.toInt()}) " +
                        "Src=$sourceName Tool=$toolTypeName Btn=${event.buttonState}"
            )

            tvStatus.text = "[Overlay Tes] Sentuhan: $touchCount | IME: ${lastImeHeight}px"

            // Move visual indicator
            if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_MOVE) {
                touchIndicator.visibility = View.VISIBLE
                touchIndicator.x = event.rawX - (touchIndicator.width / 2f)
                touchIndicator.y = event.rawY - (touchIndicator.height / 2f)
            } else if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                touchIndicator.visibility = View.GONE
            }

            // Return false so we test whether the event falls through or is swallowed by the window
            false
        }

        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        try {
            windowManager?.addView(view, layoutParams)
            isOverlayShowing = true
            Logger.log("Overlay", "Jendela overlay layar penuh transparan berhasil ditampilkan.")
            onOverlayStateChanged?.invoke(true)
        } catch (t: Throwable) {
            Logger.log("Overlay", "Gagal menampilkan overlay: ${t.message}")
        }
    }

    fun hideOverlay() {
        if (!isOverlayShowing || overlayView == null) return
        try {
            windowManager?.removeView(overlayView)
            Logger.log("Overlay", "Jendela overlay layar penuh ditutup.")
        } catch (t: Throwable) {
            Logger.log("Overlay", "Error saat menutup overlay: ${t.message}")
        } finally {
            overlayView = null
            isOverlayShowing = false
            onOverlayStateChanged?.invoke(false)
        }
    }
}
