package com.sanny.pointertest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.Surface
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * Foreground Service untuk mengelola jendela kursor virtual dan zona sentuh layar penuh.
 */
class PointerService : Service() {

    companion object {
        const val CHANNEL_ID = "pointer_service_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP_POINTER = "com.sanny.pointertest.ACTION_STOP_POINTER"
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null

    private val engine = PointerEngine()
    private var cursorView: CursorView? = null
    private var touchZoneView: TouchZoneView? = null

    private var cursorLayoutParams: WindowManager.LayoutParams? = null
    private var touchZoneLayoutParams: WindowManager.LayoutParams? = null

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var lastRotation: Int = -1

    // Listener Shizuku untuk memperbarui tampilan kursor (panah vs loading)
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        updateShizukuStatus()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        updateShizukuStatus()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            updateShizukuStatus()
        }

    // Listener rotasi dan perubahan ukuran display
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            handleDisplayOrConfigurationChanged()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        displayManager?.registerDisplayListener(displayListener, mainHandler)

        setupShizukuListeners()
        calculateScreenDimensions()
        engine.initializePosition(screenWidth, screenHeight)

        // Catat posisi pointer saat mulai (KOMPONEN 7)
        Logger.log(
            "Pointer",
            "Pointer dimulai di posisi tengah: (${engine.pointerX.toInt()}, ${engine.pointerY.toInt()}) px (Ukuran layar: ${screenWidth}x${screenHeight})"
        )

        setupPointerEngineCallbacks()
        createCursorWindow()
        createTouchZoneWindow()

        // Pantau status AccessibilityService & tinggi keyboard IME (KOMPONEN 6 & 7)
        observeKeyboardHeight()

        updateShizukuStatus()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_POINTER) {
            Logger.log("Pointer", "Perintah berhenti diterima dari notifikasi.")
            stopSelf()
            return START_NOT_STICKY
        }

        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        PointerServiceController.setRunning(true)
        Logger.log("Pointer", "PointerService berhasil berjalan sebagai Foreground Service.")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        PointerServiceController.setRunning(false)
        removeShizukuListeners()
        displayManager?.unregisterDisplayListener(displayListener)

        removeCursorWindow()
        removeTouchZoneWindow()

        serviceScope.cancel()
        Logger.log("Pointer", "PointerService dihentikan, semua jendela overlay dilepas.")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        handleDisplayOrConfigurationChanged()
    }

    // ==========================================
    // Notification & Channel Setup
    // ==========================================

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Layanan Pointer",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Menampilkan status layanan pointer aktif"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, PointerService::class.java).apply {
            action = ACTION_STOP_POINTER
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pointer aktif")
            .setContentText("Kursor virtual dan zona sentuh berjalan di atas layar")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    // ==========================================
    // Display & Orientation Handling
    // ==========================================

    private fun calculateScreenDimensions() {
        val wm = windowManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.maximumWindowMetrics.bounds
            screenWidth = bounds.width().coerceAtLeast(1)
            screenHeight = bounds.height().coerceAtLeast(1)
            lastRotation = display?.rotation ?: Surface.ROTATION_0
        } else {
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(dm)
            screenWidth = dm.widthPixels.coerceAtLeast(1)
            screenHeight = dm.heightPixels.coerceAtLeast(1)
            @Suppress("DEPRECATION")
            lastRotation = wm.defaultDisplay.rotation
        }
    }

    private fun handleDisplayOrConfigurationChanged() {
        val prevRotation = lastRotation
        calculateScreenDimensions()

        engine.onScreenSizeChanged(screenWidth, screenHeight)

        if (lastRotation != prevRotation) {
            val rotationStr = when (lastRotation) {
                Surface.ROTATION_0 -> "0° (Tegak)"
                Surface.ROTATION_90 -> "90° (Lanskap)"
                Surface.ROTATION_180 -> "180°"
                Surface.ROTATION_270 -> "270° (Lanskap Terbalik)"
                else -> "$lastRotation"
            }
            // Catat saat rotasi berubah (KOMPONEN 7)
            Logger.log(
                "Pointer",
                "Rotasi layar berubah: $rotationStr (${screenWidth}x${screenHeight} px) | Posisi pointer: (${engine.pointerX.toInt()}, ${engine.pointerY.toInt()})"
            )
        }

        updateWindowLayouts()
    }

    // ==========================================
    // Overlay Windows Management
    // ==========================================

    private fun createCursorWindow() {
        val view = CursorView(this)
        view.updatePosition(engine.pointerX, engine.pointerY)
        cursorView = view

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        // FLAG_NOT_TOUCHABLE dan FLAG_NOT_FOCUSABLE agar kursor tidak mengganggu interaksi apa pun
        val lp = WindowManager.LayoutParams(
            screenWidth,
            screenHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        cursorLayoutParams = lp
        try {
            windowManager?.addView(view, lp)
            Logger.log("Pointer", "Jendela kursor berhasil dipasang.")
        } catch (t: Throwable) {
            Logger.log("Pointer", "Gagal memasang jendela kursor: ${t.message}")
        }
    }

    private fun removeCursorWindow() {
        cursorView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {}
        }
        cursorView = null
        cursorLayoutParams = null
    }

    private fun createTouchZoneWindow() {
        val view = TouchZoneView(this, engine)
        touchZoneView = view

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val currentKeyboardHeight = KeyboardDetector.keyboardHeight.value
        val initialHeight = (screenHeight - currentKeyboardHeight).coerceAtLeast(1)

        // FLAG_NOT_FOCUSABLE, transparan, menangkap semua sentuhan
        val lp = WindowManager.LayoutParams(
            screenWidth,
            initialHeight,
            layoutFlag,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        touchZoneLayoutParams = lp
        try {
            windowManager?.addView(view, lp)
            Logger.log("Pointer", "Jendela zona sentuh berhasil dipasang (Tinggi: ${initialHeight}px).")
        } catch (t: Throwable) {
            Logger.log("Pointer", "Gagal memasang jendela zona sentuh: ${t.message}")
        }
    }

    private fun removeTouchZoneWindow() {
        touchZoneView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (_: Throwable) {}
        }
        touchZoneView = null
        touchZoneLayoutParams = null
    }

    private fun updateWindowLayouts() {
        try {
            cursorView?.let { view ->
                cursorLayoutParams?.let { lp ->
                    lp.width = screenWidth
                    lp.height = screenHeight
                    windowManager?.updateViewLayout(view, lp)
                }
            }

            touchZoneView?.let { view ->
                touchZoneLayoutParams?.let { lp ->
                    val kbHeight = KeyboardDetector.keyboardHeight.value
                    val targetHeight = (screenHeight - kbHeight).coerceAtLeast(1)
                    lp.width = screenWidth
                    lp.height = targetHeight
                    windowManager?.updateViewLayout(view, lp)
                }
            }
        } catch (t: Throwable) {
            Logger.log("Pointer", "Error updateViewLayout: ${t.message}")
        }
    }

    // ==========================================
    // Engine Callbacks & Calibration Tap
    // ==========================================

    private fun setupPointerEngineCallbacks() {
        // Gerakan mulus 60 fps ke CursorView
        engine.onPositionChanged = { x, y ->
            cursorView?.updatePosition(x, y)
        }

        // UJI KALIBRASI SEMENTARA:
        // Ketukan singkat di zona sentuh (< 200ms, < 10px gerak) menyuntik tap jari (SOURCE_TOUCHSCREEN)
        // di posisi pointer saat ini untuk memverifikasi keselarasan posisi visual kursor dengan titik input sistem.
        // CATATAN: Fitur ini adalah uji kalibrasi sementara dan akan digantikan pada tahap berikutnya.
        engine.onCalibrationTapTriggered = { targetX, targetY ->
            serviceScope.launch(Dispatchers.IO) {
                Logger.log(
                    "Kalibrasi",
                    "Uji Tap Kalibrasi di posisi pointer: (${targetX.toInt()}, ${targetY.toInt()})"
                )
                val downTime = SystemClock.uptimeMillis()
                val downEvent = EventInjector.createTouchEvent(
                    action = MotionEvent.ACTION_DOWN,
                    x = targetX,
                    y = targetY,
                    downTime = downTime,
                    eventTime = downTime
                )
                val resDown = EventInjector.injectEvent(downEvent)
                downEvent.recycle()

                delay(50L)

                val upTime = SystemClock.uptimeMillis()
                val upEvent = EventInjector.createTouchEvent(
                    action = MotionEvent.ACTION_UP,
                    x = targetX,
                    y = targetY,
                    downTime = downTime,
                    eventTime = upTime
                )
                val resUp = EventInjector.injectEvent(upEvent)
                upEvent.recycle()

                // Catat setiap tap kalibrasi (KOMPONEN 7)
                Logger.log(
                    "Kalibrasi",
                    "Hasil Tap Kalibrasi (${targetX.toInt()}, ${targetY.toInt()}): Down=${resDown.success}, Up=${resUp.success}"
                )
            }
        }
    }

    // ==========================================
    // Keyboard Height Observation
    // ==========================================

    private fun observeKeyboardHeight() {
        if (!KeyboardDetector.isServiceConnected.value) {
            Logger.log(
                "Pointer",
                "PERINGATAN: AccessibilityService belum aktif. Zona sentuh tetap layar penuh sampai izin aksesibilitas diaktifkan."
            )
        }

        serviceScope.launch {
            KeyboardDetector.keyboardHeight.collect { kbHeight ->
                val targetHeight = (screenHeight - kbHeight).coerceAtLeast(1)
                touchZoneView?.let { view ->
                    touchZoneLayoutParams?.let { lp ->
                        if (lp.height != targetHeight) {
                            lp.height = targetHeight
                            try {
                                windowManager?.updateViewLayout(view, lp)
                                Logger.log(
                                    "Pointer",
                                    "Tinggi zona sentuh disesuaikan: ${targetHeight}px (Keyboard: ${kbHeight}px)"
                                )
                            } catch (t: Throwable) {
                                Logger.log("Pointer", "Gagal update tinggi zona sentuh: ${t.message}")
                            }
                        }
                    }
                }
            }
        }
    }

    // ==========================================
    // Shizuku Status Monitoring
    // ==========================================

    private fun setupShizukuListeners() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Logger.log("Pointer", "Gagal mendaftarkan listener Shizuku: ${t.message}")
        }
    }

    private fun removeShizukuListeners() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}
    }

    private fun updateShizukuStatus() {
        val isReady = try {
            Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
        cursorView?.setShizukuReady(isReady)
    }
}
