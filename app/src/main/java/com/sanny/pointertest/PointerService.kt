package com.sanny.pointertest

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

/**
 * Foreground Service untuk mengelola jendela kursor virtual dan zona sentuh layar penuh.
 */
class PointerService : Service() {

    companion object {
        const val CHANNEL_ID = "pointer_service_channel"
        const val NOTIFICATION_ID = 2001
        const val ACTION_STOP_POINTER = "com.sanny.pointertest.ACTION_STOP_POINTER"
        const val ACTION_TOGGLE_MODE = "com.sanny.pointertest.ACTION_TOGGLE_MODE"

        private const val ZONE_NOT_TOUCHABLE_TIMEOUT_MS = 30_000L
        private const val POST_RESTORE_INJECT_DELAY_MS = 40L

        private val _windowTypeStatus = MutableStateFlow("Tidak aktif")
        val windowTypeStatus: StateFlow<String> = _windowTypeStatus.asStateFlow()
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var cursorWindowManager: WindowManager? = null
    private var touchZoneWindowManager: WindowManager? = null
    private var displayManager: DisplayManager? = null

    private val engine = PointerEngine()
    private var cursorView: CursorView? = null
    private var touchZoneView: TouchZoneView? = null

    private var cursorLayoutParams: WindowManager.LayoutParams? = null
    private var touchZoneLayoutParams: WindowManager.LayoutParams? = null

    private var currentUsingAccessibilityOverlay: Boolean? = null
    private var pendingWindowRecreate: Boolean = false
    private var isHoldActive: Boolean = false
    private var isMouseButtonHeldInQueue: Boolean = false
    private var pendingReturnToNormalOnShizukuDead: Boolean = false
    private var isServiceDestroyed: Boolean = false

    @Volatile
    private var isZoneTouchable: Boolean = true
    @Volatile
    private var lastZoneRestoredTimeMs: Long = 0L
    @Volatile
    private var lastTouchDownTimeMs: Long = 0L
    @Volatile
    private var lastCursorUpdateViewLayoutMs: Long = 0L
    @Volatile
    private var lastZoneUpdateViewLayoutMs: Long = 0L
    @Volatile
    private var lastMouseInjectTimeMs: Long = 0L
    @Volatile
    private var lastMouseInjectAction: String = "belum ada"
    private var zoneDisabledByNoHole: Boolean = false
    private var zoneDisabledByMouseTouch: Boolean = false

    private fun formatSinceDownMs(nowMs: Long = SystemClock.uptimeMillis()): String {
        val downMs = lastTouchDownTimeMs
        return if (downMs > 0L) "${(nowMs - downMs).coerceAtLeast(0L)} ms" else "belum ada DOWN"
    }

    private fun recordAndLogUpdateViewLayout(windowName: String, callerName: String) {
        val now = SystemClock.uptimeMillis()
        when (windowName) {
            "kursor" -> lastCursorUpdateViewLayoutMs = now
            "zona" -> lastZoneUpdateViewLayoutMs = now
        }
        Logger.log("Pointer", "updateViewLayout jendela=$windowName alasan=$callerName ts=$now")
    }

    private fun recordMouseInjection(actionLabel: String, timestampMs: Long = SystemClock.uptimeMillis()) {
        lastMouseInjectAction = actionLabel
        lastMouseInjectTimeMs = timestampMs
    }

    private val zoneTouchableTimeoutRunnable = Runnable {
        if (!isZoneTouchable) {
            Logger.log(
                "TouchZone",
                "Pengaman 30 detik: zona NOT_TOUCHABLE terlalu lama, memulihkan touchable otomatis."
            )
            val wasMouseTouchDisabled = zoneDisabledByMouseTouch
            zoneDisabledByNoHole = false
            zoneDisabledByMouseTouch = false
            applyZoneTouchableOnMain(
                touchable = true,
                logTag = if (wasMouseTouchDisabled) "Mouse" else "Pointer",
                logMessage = if (wasMouseTouchDisabled) "Mouse: flag NOT_TOUCHABLE dihapus (timeout 30d)" else "Zona sentuh aktif kembali"
            )
        }
    }

    private var screenWidth: Int = 1080
    private var screenHeight: Int = 2400
    private var lastRotation: Int = -1

    private sealed class GestureCommand {
        // Mode NORMAL
        data class Tap(val x: Float, val y: Float) : GestureCommand()
        data class HoldStart(val x: Float, val y: Float) : GestureCommand()
        data class HoldMove(val x: Float, val y: Float) : GestureCommand()
        data class HoldEnd(
            val x: Float,
            val y: Float,
            val totalDx: Float,
            val totalDy: Float,
            val isCancel: Boolean
        ) : GestureCommand()

        // Mode MOUSE
        data class MouseHoverMove(val x: Float, val y: Float) : GestureCommand()
        data class MouseClick(val x: Float, val y: Float, val button: Int) : GestureCommand()
        data class MouseDragStart(val x: Float, val y: Float, val button: Int) : GestureCommand()
        data class MouseDragMove(val x: Float, val y: Float, val button: Int) : GestureCommand()
        data class MouseDragEnd(
            val x: Float,
            val y: Float,
            val totalDx: Float,
            val totalDy: Float,
            val button: Int,
            val isCancel: Boolean
        ) : GestureCommand()
        data class MouseScroll(val x: Float, val y: Float, val vScroll: Float) : GestureCommand()
    }

    private val injectionChannel = Channel<GestureCommand>(Channel.UNLIMITED)

    // Listener Shizuku untuk memicu evaluasi ulang backend aktif & keselamatan mode mouse
    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        BackendSelector.evaluate()
        mainHandler.post {
            updateCursorBackendReady()
        }
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        EventInjector.reset()
        BackendSelector.evaluate()
        mainHandler.post {
            handleShizukuUnavailableInMouseMode()
            updateCursorBackendReady()
        }
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { _, _ ->
            BackendSelector.evaluate()
            mainHandler.post {
                handleShizukuUnavailableInMouseMode()
                updateCursorBackendReady()
            }
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

        BackendSelector.init(applicationContext)
        if (PointerModeController.mode.value == PointerMode.MOUSE && !PointerModeController.isShizukuReady()) {
            PointerModeController.forceReturnToNormalFromShizukuLoss()
        }
        engine.setMode(PointerModeController.mode.value)
        setupPointerEngineCallbacks()
        serviceScope.launch(Dispatchers.IO) {
            processInjectionQueue()
        }
        attachOverlayWindows()

        // Pantau status AccessibilityService, tinggi keyboard IME, backend, & mode pointer
        observeKeyboardHeight()
        observeActiveBackend()
        observeAccessibilityConnection()
        observePointerMode()

        BackendSelector.evaluate()
        updateCursorBackendReady()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_POINTER -> {
                Logger.log("Pointer", "Perintah berhenti diterima dari notifikasi.")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_MODE -> {
                val target = if (PointerModeController.mode.value == PointerMode.MOUSE) {
                    PointerMode.NORMAL
                } else {
                    PointerMode.MOUSE
                }
                PointerModeController.setMode(this, target)
                updateNotification()
                return START_STICKY
            }
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
        isServiceDestroyed = true
        try {
            PointerServiceController.setRunning(false)
            _windowTypeStatus.value = "Tidak aktif"
            engine.cancelAll()
            injectionChannel.close()
            removeShizukuListeners()
            displayManager?.unregisterDisplayListener(displayListener)
        } finally {
            restoreZoneTouchableOnCleanup(logIfMouseWasDisabled = true)
            mainHandler.removeCallbacksAndMessages(null)
            serviceScope.cancel()
            removeCursorWindow()
            removeTouchZoneWindow()
            currentUsingAccessibilityOverlay = null
        }

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

        val isMouseMode = PointerModeController.mode.value == PointerMode.MOUSE
        val modeLabel = if (isMouseMode) "Mode Mouse" else "Mode Biasa"
        val toggleActionLabel = if (isMouseMode) "Mode Biasa" else "Mode Mouse"

        val toggleModeIntent = Intent(this, PointerService::class.java).apply {
            action = ACTION_TOGGLE_MODE
        }
        val toggleModePendingIntent = PendingIntent.getService(
            this,
            2,
            toggleModeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pointer aktif ($modeLabel)")
            .setContentText(
                if (isMouseMode) {
                    "Mode aktif: Mode Mouse (Shizuku)"
                } else {
                    "Mode aktif: Mode Biasa"
                }
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(openPendingIntent)
            .addAction(android.R.drawable.ic_menu_manage, toggleActionLabel, toggleModePendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Hentikan", stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification() {
        if (isServiceDestroyed) return
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm?.notify(NOTIFICATION_ID, createNotification())
        } catch (_: Throwable) {}
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
            lastRotation = displayManager?.getDisplay(android.view.Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
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

    private fun resolveOverlayTarget(): Pair<WindowManager?, Boolean> {
        val accService = if (KeyboardAccessibilityService.isConnected.value) {
            KeyboardAccessibilityService.instance
        } else {
            null
        }
        if (accService != null) {
            val accWm = accService.getSystemService(WindowManager::class.java)
            if (accWm != null) {
                return Pair(accWm, true)
            }
        }
        return Pair(windowManager, false)
    }

    private fun attachOverlayWindows() {
        if (isServiceDestroyed) return
        val (targetWm, useAccessibilityOverlay) = resolveOverlayTarget()
        currentUsingAccessibilityOverlay = useAccessibilityOverlay

        val windowTypeLabel = if (useAccessibilityOverlay) {
            "ACCESSIBILITY_OVERLAY (tepercaya, alpha 1.0)"
        } else {
            "APPLICATION_OVERLAY (cadangan, alpha 0.5/0.5)"
        }
        Logger.log("Pointer", "Jendela: $windowTypeLabel")
        _windowTypeStatus.value = windowTypeLabel

        createCursorWindow(targetWm, useAccessibilityOverlay)
        createTouchZoneWindow(targetWm, useAccessibilityOverlay)

        if (cursorView == null && touchZoneView == null) {
            _windowTypeStatus.value = "Gagal dipasang (butuh izin overlay)"
        }
    }

    private fun recreateOverlayWindows() {
        if (isServiceDestroyed) return
        pendingWindowRecreate = false
        removeCursorWindow()
        removeTouchZoneWindow()
        attachOverlayWindows()
    }

    private fun createCursorWindow(targetWm: WindowManager?, useAccessibilityOverlay: Boolean) {
        val view = CursorView(this)
        view.updatePosition(engine.pointerX, engine.pointerY)
        view.setBackendReady(BackendSelector.activeBackend.value != null)
        view.setHolding(engine.isHolding)
        cursorView = view

        val layoutFlag = if (useAccessibilityOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            alpha = if (useAccessibilityOverlay) 1.0f else 0.5f
        }

        cursorLayoutParams = lp
        try {
            val wm = targetWm ?: throw IllegalStateException("WindowManager tidak tersedia")
            wm.addView(view, lp)
            cursorWindowManager = wm
            Logger.log("Pointer", "Jendela kursor berhasil dipasang.")
        } catch (t: Throwable) {
            cursorView = null
            cursorLayoutParams = null
            cursorWindowManager = null
            Logger.log("Pointer", "Gagal memasang jendela kursor: ${t.message}")
        }
    }

    private fun removeCursorWindow() {
        val wm = cursorWindowManager ?: windowManager
        cursorView?.let { view ->
            try {
                wm?.removeView(view)
            } catch (_: Throwable) {}
        }
        cursorView = null
        cursorLayoutParams = null
        cursorWindowManager = null
    }

    private fun createTouchZoneWindow(targetWm: WindowManager?, useAccessibilityOverlay: Boolean) {
        val view = TouchZoneView(this, engine).apply {
            onMouseTouchRestoreRequest = {
                handleMouseTouchZoneTouchable(touchable = true, reason = "jari terangkat")
            }
            isZoneNotTouchableProvider = {
                !isZoneTouchable ||
                    (((touchZoneLayoutParams?.flags ?: 0) and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0)
            }
            zoneLayoutFlagsProvider = {
                touchZoneLayoutParams?.flags ?: 0
            }
            onTouchDownTimestamp = { downMs ->
                lastTouchDownTimeMs = downMs
            }
            cancelDiagnosticsProvider = { nowUptime ->
                val sinceCursorLayout = if (lastCursorUpdateViewLayoutMs > 0L) {
                    "${(nowUptime - lastCursorUpdateViewLayoutMs).coerceAtLeast(0L)} ms"
                } else {
                    "belum"
                }
                val sinceZoneLayout = if (lastZoneUpdateViewLayoutMs > 0L) {
                    "${(nowUptime - lastZoneUpdateViewLayoutMs).coerceAtLeast(0L)} ms"
                } else {
                    "belum"
                }
                val sinceMouseInject = if (lastMouseInjectTimeMs > 0L) {
                    "${(nowUptime - lastMouseInjectTimeMs).coerceAtLeast(0L)} ms ($lastMouseInjectAction)"
                } else {
                    "belum ada"
                }
                "sejakUpdateKursor=$sinceCursorLayout | sejakUpdateZona=$sinceZoneLayout | sejakSuntikMouse=$sinceMouseInject"
            }
        }
        touchZoneView = view

        val layoutFlag = if (useAccessibilityOverlay) {
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            alpha = if (useAccessibilityOverlay) 1.0f else 0.5f
        }

        touchZoneLayoutParams = lp
        mainHandler.removeCallbacks(zoneTouchableTimeoutRunnable)
        isZoneTouchable = true
        lastZoneRestoredTimeMs = 0L
        zoneDisabledByNoHole = false
        zoneDisabledByMouseTouch = false

        try {
            val wm = targetWm ?: throw IllegalStateException("WindowManager tidak tersedia")
            wm.addView(view, lp)
            touchZoneWindowManager = wm
            Logger.log("Pointer", "Jendela zona sentuh berhasil dipasang (Tinggi: ${initialHeight}px).")
            view.updatePointerHole(engine.pointerX, engine.pointerY)
            view.setupHoleMode()
        } catch (t: Throwable) {
            touchZoneView = null
            touchZoneLayoutParams = null
            touchZoneWindowManager = null
            Logger.log("Pointer", "Gagal memasang jendela zona sentuh: ${t.message}")
        }
    }

    private fun removeTouchZoneWindow() {
        mainHandler.removeCallbacks(zoneTouchableTimeoutRunnable)
        isZoneTouchable = true
        zoneDisabledByNoHole = false
        zoneDisabledByMouseTouch = false
        val wm = touchZoneWindowManager ?: windowManager
        touchZoneView?.let { view ->
            view.onMouseTouchRestoreRequest = null
            view.isZoneNotTouchableProvider = null
            view.zoneLayoutFlagsProvider = null
            view.onTouchDownTimestamp = null
            view.cancelDiagnosticsProvider = null
            try {
                wm?.removeView(view)
            } catch (_: Throwable) {}
        }
        touchZoneView = null
        touchZoneLayoutParams = null
        touchZoneWindowManager = null
    }

    private fun updateWindowLayouts() {
        try {
            cursorView?.let { view ->
                cursorLayoutParams?.let { lp ->
                    lp.width = screenWidth
                    lp.height = screenHeight
                    (cursorWindowManager ?: windowManager)?.let { wm ->
                        recordAndLogUpdateViewLayout("kursor", "updateWindowLayouts")
                        wm.updateViewLayout(view, lp)
                    }
                }
            }

            touchZoneView?.let { view ->
                touchZoneLayoutParams?.let { lp ->
                    val kbHeight = KeyboardDetector.keyboardHeight.value
                    val targetHeight = (screenHeight - kbHeight).coerceAtLeast(1)
                    lp.width = screenWidth
                    lp.height = targetHeight
                    (touchZoneWindowManager ?: windowManager)?.let { wm ->
                        recordAndLogUpdateViewLayout("zona", "updateWindowLayouts")
                        wm.updateViewLayout(view, lp)
                    }
                }
            }
        } catch (t: Throwable) {
            Logger.log("Pointer", "Error updateViewLayout: ${t.message}")
        }
    }

    // ==========================================
    // Engine Callbacks & Serialized Gesture Injection
    // ==========================================

    private fun setupPointerEngineCallbacks() {
        // Gerakan mulus 60 fps ke CursorView dan pembaruan lubang TouchZoneView
        engine.onPositionChanged = { x, y ->
            cursorView?.updatePosition(x, y)
            touchZoneView?.updatePointerHole(x, y)
        }

        // Gestur mode biasa: Tap, HOLD, Tahan lalu Geser (diserialkan via Channel UNLIMITED)
        engine.onTapTriggered = { targetX, targetY ->
            injectionChannel.trySend(GestureCommand.Tap(targetX, targetY))
        }

        engine.onHoldStarted = { targetX, targetY ->
            isHoldActive = true
            cursorView?.setHolding(true)
            injectionChannel.trySend(GestureCommand.HoldStart(targetX, targetY))
        }

        engine.onHoldMoved = { targetX, targetY ->
            injectionChannel.trySend(GestureCommand.HoldMove(targetX, targetY))
        }

        engine.onHoldEnded = { targetX, targetY, totalDx, totalDy, isCancel ->
            cursorView?.setHolding(false)
            injectionChannel.trySend(
                GestureCommand.HoldEnd(
                    x = targetX,
                    y = targetY,
                    totalDx = totalDx,
                    totalDy = totalDy,
                    isCancel = isCancel
                )
            )
        }

        // Gestur mode mouse: Hover, Klik Kiri/Kanan, Drag Kiri/Kanan, Scroll 2 Jari
        engine.onMouseHoverMoved = { targetX, targetY ->
            injectionChannel.trySend(GestureCommand.MouseHoverMove(targetX, targetY))
        }

        engine.onMouseLeftClick = { targetX, targetY ->
            injectionChannel.trySend(
                GestureCommand.MouseClick(targetX, targetY, MotionEvent.BUTTON_PRIMARY)
            )
        }

        engine.onMouseRightClick = { targetX, targetY ->
            injectionChannel.trySend(
                GestureCommand.MouseClick(targetX, targetY, MotionEvent.BUTTON_SECONDARY)
            )
        }

        engine.onMouseDragStarted = { targetX, targetY, button ->
            isMouseButtonHeldInQueue = true
            cursorView?.setHolding(true)
            injectionChannel.trySend(GestureCommand.MouseDragStart(targetX, targetY, button))
        }

        engine.onMouseDragMoved = { targetX, targetY, button ->
            injectionChannel.trySend(GestureCommand.MouseDragMove(targetX, targetY, button))
        }

        engine.onMouseDragEnded = { targetX, targetY, totalDx, totalDy, button, isCancel ->
            cursorView?.setHolding(false)
            injectionChannel.trySend(
                GestureCommand.MouseDragEnd(
                    x = targetX,
                    y = targetY,
                    totalDx = totalDx,
                    totalDy = totalDy,
                    button = button,
                    isCancel = isCancel
                )
            )
        }

        engine.onMouseScroll = { targetX, targetY, vScroll ->
            injectionChannel.trySend(GestureCommand.MouseScroll(targetX, targetY, vScroll))
        }

        engine.onMouseNotTouchableRequired = { reason ->
            handleMouseTouchZoneTouchable(touchable = false, reason = reason)
        }

        engine.onTouchGestureFinished = {
            checkPendingActionsBetweenGestures()
        }
    }

    private suspend fun processInjectionQueue() {
        var activeHoldBackend: InputBackend? = null
        var holdDownDispatched = false
        var lastHoldX = 0f
        var lastHoldY = 0f
        var lastMoveInjectTimeMs = 0L
        var zoneDisabled = false

        // State mode MOUSE di coroutine antrean terserialkan
        var activeMouseButton = 0
        var mouseDownTime = 0L
        var mouseDragStartX = 0f
        var mouseDragStartY = 0f
        var lastMouseDragX = 0f
        var lastMouseDragY = 0f
        var lastMouseMoveInjectTimeMs = 0L
        var lastHoverInjectTimeMs = 0L
        var hoverEventCount = 0

        fun injectMouseButtonEvent(
            action: Int,
            x: Float,
            y: Float,
            buttonState: Int,
            actionButton: Int,
            downTime: Long,
            eventTime: Long,
            logInjector: Boolean = false
        ): InjectionResult {
            val event = EventInjector.createMouseButtonEvent(
                action = action,
                x = x,
                y = y,
                buttonState = buttonState,
                actionButton = actionButton,
                deviceId = 0,
                downTime = downTime,
                eventTime = eventTime
            )
            val result = EventInjector.injectEvent(event, logResult = logInjector)
            event.recycle()
            val injectNow = SystemClock.uptimeMillis()
            val actionName = MotionEvent.actionToString(action)
            recordMouseInjection(actionName, injectNow)
            Logger.log(
                "Mouse",
                "Mouse: suntik $actionName (${x.toInt()}, ${y.toInt()}) -> Sukses=${result.success} | " +
                    "sejak DOWN=${formatSinceDownMs(injectNow)}"
            )
            return result
        }

        suspend fun finishHold(endCmd: GestureCommand.HoldEnd) {
            try {
                lastHoldX = endCmd.x
                lastHoldY = endCmd.y
                val holdBackend = activeHoldBackend
                val shouldSendUp = holdDownDispatched
                activeHoldBackend = null
                holdDownDispatched = false

                if (holdBackend != null && shouldSendUp) {
                    if (endCmd.isCancel) {
                        Logger.log("HOLD", "HOLD dibatalkan oleh zona sentuh [CANCEL]")
                    }
                    holdBackend.holdUp(lastHoldX, lastHoldY)
                } else {
                    Logger.log("HOLD", "HOLD selesai tanpa backend aktif")
                }

                if (zoneDisabled) {
                    delay(80L)
                }
            } finally {
                withContext(NonCancellable) {
                    BackendSelector.setHoldInProgress(false)
                    if (zoneDisabled) {
                        setZoneTouchable(true)
                        zoneDisabled = false
                    }
                    mainHandler.post {
                        isHoldActive = false
                        checkPendingActionsBetweenGestures()
                    }
                }
            }
        }

        suspend fun waitIfZoneRestoredOrHoleUpdated() {
            while (isZoneTouchable && !isServiceDestroyed) {
                val now = SystemClock.uptimeMillis()
                val readyAfterRestore = if (lastZoneRestoredTimeMs > 0L) {
                    lastZoneRestoredTimeMs + POST_RESTORE_INJECT_DELAY_MS
                } else {
                    0L
                }
                val holeTime = touchZoneView?.lastHoleUpdatedTimeMs ?: 0L
                val readyAfterHole = if (holeTime > 0L) {
                    holeTime + POST_RESTORE_INJECT_DELAY_MS
                } else {
                    0L
                }
                val targetTime = maxOf(readyAfterRestore, readyAfterHole)
                if (now < targetTime) {
                    delay(targetTime - now)
                } else {
                    break
                }
            }
        }

        suspend fun finishMouseDrag(endCmd: GestureCommand.MouseDragEnd?) {
            try {
                val btn = activeMouseButton
                if (btn != 0) {
                    val targetX = endCmd?.x ?: lastMouseDragX
                    val targetY = endCmd?.y ?: lastMouseDragY
                    val totalDx = endCmd?.totalDx ?: (targetX - mouseDragStartX)
                    val totalDy = endCmd?.totalDy ?: (targetY - mouseDragStartY)
                    val btnLabel = if (btn == MotionEvent.BUTTON_SECONDARY) "kanan" else "kiri"

                    if (endCmd?.isCancel == true) {
                        Logger.log("Mouse", "Mouse: drag $btnLabel dibatalkan [CANCEL]")
                    }

                    if (!isServiceDestroyed) {
                        waitIfZoneRestoredOrHoleUpdated()
                    }

                    withContext(NonCancellable) {
                        val upTime = SystemClock.uptimeMillis()
                        val resRelease = injectMouseButtonEvent(
                            action = MotionEvent.ACTION_BUTTON_RELEASE,
                            x = targetX,
                            y = targetY,
                            buttonState = 0,
                            actionButton = btn,
                            downTime = mouseDownTime,
                            eventTime = upTime
                        )
                        val resUp = injectMouseButtonEvent(
                            action = MotionEvent.ACTION_UP,
                            x = targetX,
                            y = targetY,
                            buttonState = 0,
                            actionButton = 0,
                            downTime = mouseDownTime,
                            eventTime = upTime + 1
                        )
                        Logger.log(
                            "Mouse",
                            "Mouse: drag $btnLabel selesai (${totalDx.toInt()},${totalDy.toInt()}) | " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_BUTTON_RELEASE)}=${resRelease.success}, " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_UP)}=${resUp.success}"
                        )
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    activeMouseButton = 0
                    mainHandler.post {
                        isMouseButtonHeldInQueue = false
                        checkPendingActionsBetweenGestures()
                    }
                }
            }
        }

        try {
            var nextCmd: GestureCommand? = null
            while (true) {
                val cmd = nextCmd ?: injectionChannel.receiveCatching().getOrNull() ?: break
                nextCmd = null

                when (cmd) {
                    is GestureCommand.Tap -> {
                        if (activeHoldBackend != null) {
                            try {
                                if (holdDownDispatched) {
                                    activeHoldBackend?.holdUp(lastHoldX, lastHoldY)
                                }
                            } finally {
                                activeHoldBackend = null
                                holdDownDispatched = false
                                BackendSelector.setHoldInProgress(false)
                            }
                        }
                        if (zoneDisabled) {
                            withContext(NonCancellable) {
                                setZoneTouchable(true)
                                zoneDisabled = false
                            }
                        }

                        val backend = BackendSelector.activeBackend.value
                        if (backend == null) {
                            Logger.log("Pointer", "Tap diabaikan: tidak ada backend aktif")
                            continue
                        }

                        val useHole = touchZoneView?.isHoleModeActive == true
                        try {
                            if (!useHole) {
                                zoneDisabled = true
                                setZoneTouchable(false)
                                delay(60L)
                            }

                            backend.tap(cmd.x, cmd.y)

                            if (!useHole) {
                                delay(80L)
                            }
                        } finally {
                            withContext(NonCancellable) {
                                if (zoneDisabled) {
                                    setZoneTouchable(true)
                                    zoneDisabled = false
                                }
                            }
                        }
                    }

                    is GestureCommand.HoldStart -> {
                        if (activeHoldBackend != null) {
                            try {
                                if (holdDownDispatched) {
                                    activeHoldBackend?.holdUp(lastHoldX, lastHoldY)
                                }
                            } finally {
                                activeHoldBackend = null
                                holdDownDispatched = false
                                BackendSelector.setHoldInProgress(false)
                            }
                        }
                        if (zoneDisabled) {
                            withContext(NonCancellable) {
                                setZoneTouchable(true)
                                zoneDisabled = false
                            }
                        }

                        val backend = BackendSelector.activeBackend.value
                        if (backend == null) {
                            Logger.log("HOLD", "HOLD diabaikan: tidak ada backend aktif")
                            continue
                        }

                        BackendSelector.setHoldInProgress(true)
                        activeHoldBackend = backend
                        holdDownDispatched = false

                        val useHole = touchZoneView?.isHoleModeActive == true
                        if (!useHole) {
                            zoneDisabled = true
                            setZoneTouchable(false)
                            delay(60L)
                        }

                        lastHoldX = cmd.x
                        lastHoldY = cmd.y
                        holdDownDispatched = true
                        backend.holdDown(cmd.x, cmd.y)
                        lastMoveInjectTimeMs = SystemClock.uptimeMillis()
                    }

                    is GestureCommand.HoldMove -> {
                        val holdBackend = activeHoldBackend ?: continue
                        if (!holdDownDispatched) continue

                        var latestMove = cmd
                        var pendingEnd: GestureCommand.HoldEnd? = null

                        // Gabungkan MOVE yang menumpuk di channel
                        while (true) {
                            val queued = injectionChannel.tryReceive().getOrNull() ?: break
                            when (queued) {
                                is GestureCommand.HoldMove -> latestMove = queued
                                is GestureCommand.HoldEnd -> {
                                    pendingEnd = queued
                                    break
                                }
                                else -> {
                                    nextCmd = queued
                                    break
                                }
                            }
                        }

                        // Pertahankan batas 8 ms untuk ShizukuBackend agar perilaku identik
                        if (holdBackend is ShizukuBackend) {
                            val elapsed = SystemClock.uptimeMillis() - lastMoveInjectTimeMs
                            if (elapsed < 8L) {
                                delay(8L - elapsed)
                                while (pendingEnd == null && nextCmd == null) {
                                    val queued = injectionChannel.tryReceive().getOrNull() ?: break
                                    when (queued) {
                                        is GestureCommand.HoldMove -> latestMove = queued
                                        is GestureCommand.HoldEnd -> {
                                            pendingEnd = queued
                                            break
                                        }
                                        else -> {
                                            nextCmd = queued
                                            break
                                        }
                                    }
                                }
                            }
                        }

                        lastHoldX = latestMove.x
                        lastHoldY = latestMove.y
                        holdBackend.holdMove(lastHoldX, lastHoldY)
                        lastMoveInjectTimeMs = SystemClock.uptimeMillis()

                        if (pendingEnd != null) {
                            finishHold(pendingEnd)
                        }
                    }

                    is GestureCommand.HoldEnd -> {
                        finishHold(cmd)
                    }

                    // ==========================================
                    // Eksekusi Event Mode MOUSE (Shizuku)
                    // ==========================================

                    is GestureCommand.MouseHoverMove -> {
                        var latestHover = cmd
                        while (true) {
                            val queued = injectionChannel.tryReceive().getOrNull() ?: break
                            if (queued is GestureCommand.MouseHoverMove) {
                                latestHover = queued
                            } else {
                                nextCmd = queued
                                break
                            }
                        }

                        val elapsed = SystemClock.uptimeMillis() - lastHoverInjectTimeMs
                        if (elapsed < 16L) {
                            delay(16L - elapsed)
                            while (nextCmd == null) {
                                val queued = injectionChannel.tryReceive().getOrNull() ?: break
                                if (queued is GestureCommand.MouseHoverMove) {
                                    latestHover = queued
                                } else {
                                    nextCmd = queued
                                    break
                                }
                            }
                        }

                        if (isZoneTouchable && lastZoneRestoredTimeMs > 0L) {
                            val now = SystemClock.uptimeMillis()
                            val readyAfterRestore = lastZoneRestoredTimeMs + POST_RESTORE_INJECT_DELAY_MS
                            if (now < readyAfterRestore && !isServiceDestroyed) {
                                delay(readyAfterRestore - now)
                                while (nextCmd == null) {
                                    val queued = injectionChannel.tryReceive().getOrNull() ?: break
                                    if (queued is GestureCommand.MouseHoverMove) {
                                        latestHover = queued
                                    } else {
                                        nextCmd = queued
                                        break
                                    }
                                }
                            }
                        }

                        val event = EventInjector.createMouseHoverEvent(
                            action = MotionEvent.ACTION_HOVER_MOVE,
                            x = latestHover.x,
                            y = latestHover.y
                        )
                        val res = EventInjector.injectEvent(event, logResult = false)
                        event.recycle()
                        val hoverNow = SystemClock.uptimeMillis()
                        lastHoverInjectTimeMs = hoverNow

                        hoverEventCount++
                        recordMouseInjection(MotionEvent.actionToString(MotionEvent.ACTION_HOVER_MOVE), hoverNow)
                        Logger.log(
                            "Mouse",
                            "Mouse: ${MotionEvent.actionToString(MotionEvent.ACTION_HOVER_MOVE)} #$hoverEventCount " +
                                "(${latestHover.x.toInt()}, ${latestHover.y.toInt()}) -> Sukses=${res.success} | " +
                                "sejak DOWN=${formatSinceDownMs(hoverNow)}"
                        )
                    }

                    is GestureCommand.MouseClick -> {
                        if (activeMouseButton != 0) {
                            finishMouseDrag(null)
                        }

                        waitIfZoneRestoredOrHoleUpdated()

                        val clickLabel = if (cmd.button == MotionEvent.BUTTON_SECONDARY) "klik kanan" else "klik kiri"
                        val clickDownTime = SystemClock.uptimeMillis()
                        var pressed = false
                        var resDown = InjectionResult(false, "Belum disuntik")
                        var resPress = InjectionResult(false, "Belum disuntik")
                        var resRelease = InjectionResult(false, "Belum disuntik")
                        var resUp = InjectionResult(false, "Belum disuntik")

                        try {
                            resDown = injectMouseButtonEvent(
                                action = MotionEvent.ACTION_DOWN,
                                x = cmd.x,
                                y = cmd.y,
                                buttonState = cmd.button,
                                actionButton = 0,
                                downTime = clickDownTime,
                                eventTime = clickDownTime
                            )
                            resPress = injectMouseButtonEvent(
                                action = MotionEvent.ACTION_BUTTON_PRESS,
                                x = cmd.x,
                                y = cmd.y,
                                buttonState = cmd.button,
                                actionButton = cmd.button,
                                downTime = clickDownTime,
                                eventTime = clickDownTime + 10
                            )
                            pressed = true
                            delay(50L)
                        } finally {
                            if (pressed) {
                                withContext(NonCancellable) {
                                    val upTime = SystemClock.uptimeMillis()
                                    resRelease = injectMouseButtonEvent(
                                        action = MotionEvent.ACTION_BUTTON_RELEASE,
                                        x = cmd.x,
                                        y = cmd.y,
                                        buttonState = 0,
                                        actionButton = cmd.button,
                                        downTime = clickDownTime,
                                        eventTime = upTime
                                    )
                                    resUp = injectMouseButtonEvent(
                                        action = MotionEvent.ACTION_UP,
                                        x = cmd.x,
                                        y = cmd.y,
                                        buttonState = 0,
                                        actionButton = 0,
                                        downTime = clickDownTime,
                                        eventTime = upTime + 1
                                    )
                                }
                            }
                        }

                        Logger.log(
                            "Mouse",
                            "Mouse: $clickLabel (${cmd.x.toInt()}, ${cmd.y.toInt()}) | " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_DOWN)}=${resDown.success}, " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_BUTTON_PRESS)}=${resPress.success}, " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_BUTTON_RELEASE)}=${resRelease.success}, " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_UP)}=${resUp.success}"
                        )
                    }

                    is GestureCommand.MouseDragStart -> {
                        if (activeMouseButton != 0) {
                            finishMouseDrag(null)
                        }

                        waitIfZoneRestoredOrHoleUpdated()

                        val btnLabel = if (cmd.button == MotionEvent.BUTTON_SECONDARY) "kanan" else "kiri"
                        mouseDownTime = SystemClock.uptimeMillis()
                        mouseDragStartX = cmd.x
                        mouseDragStartY = cmd.y
                        lastMouseDragX = cmd.x
                        lastMouseDragY = cmd.y
                        activeMouseButton = cmd.button

                        val resDown = injectMouseButtonEvent(
                            action = MotionEvent.ACTION_DOWN,
                            x = cmd.x,
                            y = cmd.y,
                            buttonState = cmd.button,
                            actionButton = 0,
                            downTime = mouseDownTime,
                            eventTime = mouseDownTime
                        )
                        val resPress = injectMouseButtonEvent(
                            action = MotionEvent.ACTION_BUTTON_PRESS,
                            x = cmd.x,
                            y = cmd.y,
                            buttonState = cmd.button,
                            actionButton = cmd.button,
                            downTime = mouseDownTime,
                            eventTime = mouseDownTime + 10
                        )
                        lastMouseMoveInjectTimeMs = SystemClock.uptimeMillis()

                        Logger.log(
                            "Mouse",
                            "Mouse: drag $btnLabel mulai (${cmd.x.toInt()}, ${cmd.y.toInt()}) | " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_DOWN)}=${resDown.success}, " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_BUTTON_PRESS)}=${resPress.success}"
                        )
                    }

                    is GestureCommand.MouseDragMove -> {
                        val btn = activeMouseButton
                        if (btn == 0) continue

                        var latestMove = cmd
                        var pendingEnd: GestureCommand.MouseDragEnd? = null

                        while (true) {
                            val queued = injectionChannel.tryReceive().getOrNull() ?: break
                            when (queued) {
                                is GestureCommand.MouseDragMove -> latestMove = queued
                                is GestureCommand.MouseDragEnd -> {
                                    pendingEnd = queued
                                    break
                                }
                                else -> {
                                    nextCmd = queued
                                    break
                                }
                            }
                        }

                        val elapsed = SystemClock.uptimeMillis() - lastMouseMoveInjectTimeMs
                        if (elapsed < 8L) {
                            delay(8L - elapsed)
                            while (pendingEnd == null && nextCmd == null) {
                                val queued = injectionChannel.tryReceive().getOrNull() ?: break
                                when (queued) {
                                    is GestureCommand.MouseDragMove -> latestMove = queued
                                    is GestureCommand.MouseDragEnd -> {
                                        pendingEnd = queued
                                        break
                                    }
                                    else -> {
                                        nextCmd = queued
                                        break
                                    }
                                }
                            }
                        }

                        waitIfZoneRestoredOrHoleUpdated()

                        lastMouseDragX = latestMove.x
                        lastMouseDragY = latestMove.y
                        val resMove = injectMouseButtonEvent(
                            action = MotionEvent.ACTION_MOVE,
                            x = lastMouseDragX,
                            y = lastMouseDragY,
                            buttonState = btn,
                            actionButton = 0,
                            downTime = mouseDownTime,
                            eventTime = SystemClock.uptimeMillis()
                        )
                        lastMouseMoveInjectTimeMs = SystemClock.uptimeMillis()

                        if (!resMove.success) {
                            Logger.log(
                                "Mouse",
                                "Mouse: ${MotionEvent.actionToString(MotionEvent.ACTION_MOVE)}=${resMove.success}"
                            )
                        }

                        if (pendingEnd != null) {
                            finishMouseDrag(pendingEnd)
                        }
                    }

                    is GestureCommand.MouseDragEnd -> {
                        finishMouseDrag(cmd)
                    }

                    is GestureCommand.MouseScroll -> {
                        var totalVScroll = cmd.vScroll
                        var targetX = cmd.x
                        var targetY = cmd.y
                        while (true) {
                            val queued = injectionChannel.tryReceive().getOrNull() ?: break
                            if (queued is GestureCommand.MouseScroll) {
                                totalVScroll += queued.vScroll
                                targetX = queued.x
                                targetY = queued.y
                            } else {
                                nextCmd = queued
                                break
                            }
                        }

                        waitIfZoneRestoredOrHoleUpdated()

                        val scrollEvent = EventInjector.createMouseScrollEvent(
                            x = targetX,
                            y = targetY,
                            vScroll = totalVScroll
                        )
                        val res = EventInjector.injectEvent(scrollEvent, logResult = false)
                        scrollEvent.recycle()
                        val scrollNow = SystemClock.uptimeMillis()
                        recordMouseInjection(MotionEvent.actionToString(MotionEvent.ACTION_SCROLL), scrollNow)

                        Logger.log(
                            "Mouse",
                            "Mouse: scroll nilai=${"%.2f".format(totalVScroll)} (${targetX.toInt()}, ${targetY.toInt()}) | " +
                                "${MotionEvent.actionToString(MotionEvent.ACTION_SCROLL)}=${res.success} | " +
                                "sejak DOWN=${formatSinceDownMs(scrollNow)}"
                        )
                    }
                }
            }
        } finally {
            withContext(NonCancellable) {
                cursorView?.post {
                    cursorView?.setHolding(false)
                }
                val holdBackend = activeHoldBackend
                if (holdBackend != null && holdDownDispatched) {
                    holdBackend.holdUp(lastHoldX, lastHoldY)
                }
                activeHoldBackend = null
                holdDownDispatched = false
                BackendSelector.setHoldInProgress(false)
                if (zoneDisabled) {
                    setZoneTouchable(true)
                    zoneDisabled = false
                }
                withContext(Dispatchers.Main) {
                    restoreZoneTouchableOnCleanup(logIfMouseWasDisabled = true)
                }

                // Selalu lepaskan tombol mouse yang tertekan saat coroutine/service berhenti
                if (activeMouseButton != 0) {
                    finishMouseDrag(null)
                }
            }
        }
    }

    private fun applyZoneTouchableOnMain(
        touchable: Boolean,
        logTag: String = "Pointer",
        logMessage: String? = null
    ) {
        val view = touchZoneView ?: return
        val lp = touchZoneLayoutParams ?: return
        val wm = touchZoneWindowManager ?: windowManager ?: return

        if (!touchable) {
            mainHandler.removeCallbacks(zoneTouchableTimeoutRunnable)
            mainHandler.postDelayed(zoneTouchableTimeoutRunnable, ZONE_NOT_TOUCHABLE_TIMEOUT_MS)
        } else {
            mainHandler.removeCallbacks(zoneTouchableTimeoutRunnable)
        }

        val currentlyTouchable = isZoneTouchable &&
            ((lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0)
        if (currentlyTouchable == touchable) {
            return
        }

        if (touchable) {
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        } else {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }

        try {
            recordAndLogUpdateViewLayout("zona", "applyZoneTouchableOnMain(touchable=$touchable)")
            wm.updateViewLayout(view, lp)
            isZoneTouchable = touchable
            if (touchable) {
                lastZoneRestoredTimeMs = SystemClock.uptimeMillis()
            }
            if (logMessage != null) {
                Logger.log(logTag, logMessage)
            }
        } catch (t: Throwable) {
            Logger.log(logTag, "Gagal mengubah status sentuh zona: ${t.message}")
        }
    }

    private fun handleMouseTouchZoneTouchable(touchable: Boolean, reason: String = "") {
        if (!touchable) {
            if (PointerModeController.mode.value != PointerMode.MOUSE) return
            if (!zoneDisabledByMouseTouch) {
                zoneDisabledByMouseTouch = true
                applyZoneTouchableOnMain(
                    touchable = false,
                    logTag = "Mouse",
                    logMessage = if (reason.isNotEmpty()) {
                        "Mouse: flag NOT_TOUCHABLE aktif ($reason)"
                    } else {
                        "Mouse: flag NOT_TOUCHABLE aktif"
                    }
                )
            }
        } else {
            if (zoneDisabledByMouseTouch) {
                zoneDisabledByMouseTouch = false
                if (!zoneDisabledByNoHole) {
                    applyZoneTouchableOnMain(
                        touchable = true,
                        logTag = "Mouse",
                        logMessage = "Mouse: flag NOT_TOUCHABLE dihapus"
                    )
                }
            }
        }
    }

    private fun restoreZoneTouchableOnCleanup(logIfMouseWasDisabled: Boolean) {
        val wasMouseDisabled = zoneDisabledByMouseTouch
        zoneDisabledByNoHole = false
        zoneDisabledByMouseTouch = false
        if (!isZoneTouchable) {
            applyZoneTouchableOnMain(
                touchable = true,
                logTag = if (wasMouseDisabled && logIfMouseWasDisabled) "Mouse" else "Pointer",
                logMessage = when {
                    wasMouseDisabled && logIfMouseWasDisabled -> "Mouse: flag NOT_TOUCHABLE dihapus"
                    !wasMouseDisabled -> "Zona sentuh aktif kembali"
                    else -> null
                }
            )
        } else {
            mainHandler.removeCallbacks(zoneTouchableTimeoutRunnable)
        }
    }

    private suspend fun setZoneTouchable(touchable: Boolean) {
        withContext(Dispatchers.Main) {
            zoneDisabledByNoHole = !touchable
            if (!touchable) {
                applyZoneTouchableOnMain(
                    touchable = false,
                    logTag = "Pointer",
                    logMessage = "Zona sentuh dinonaktifkan sementara untuk suntikan"
                )
            } else if (!zoneDisabledByMouseTouch) {
                applyZoneTouchableOnMain(
                    touchable = true,
                    logTag = "Pointer",
                    logMessage = "Zona sentuh aktif kembali"
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
                                (touchZoneWindowManager ?: windowManager)?.let { wm ->
                                    recordAndLogUpdateViewLayout("zona", "observeKeyboardHeight(kb=${kbHeight}px)")
                                    wm.updateViewLayout(view, lp)
                                }
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

    private fun updateCursorBackendReady() {
        val ready = if (PointerModeController.mode.value == PointerMode.MOUSE) {
            PointerModeController.isShizukuReady()
        } else {
            BackendSelector.activeBackend.value != null
        }
        cursorView?.setBackendReady(ready)
    }

    private fun handleShizukuUnavailableInMouseMode() {
        if (PointerModeController.mode.value == PointerMode.MOUSE && !PointerModeController.isShizukuReady()) {
            try {
                if (isMouseButtonHeldInQueue || engine.isGestureActive) {
                    pendingReturnToNormalOnShizukuDead = true
                    Logger.log(
                        "Mode",
                        "Shizuku mati saat tombol/gestur mouse aktif; kembali ke NORMAL ditunda hingga dilepas."
                    )
                } else {
                    pendingReturnToNormalOnShizukuDead = false
                    PointerModeController.forceReturnToNormalFromShizukuLoss()
                }
            } finally {
                if (!engine.isGestureActive) {
                    handleMouseTouchZoneTouchable(true)
                }
            }
        }
    }

    private fun checkPendingActionsBetweenGestures() {
        if (isServiceDestroyed) return

        if (pendingReturnToNormalOnShizukuDead &&
            !isMouseButtonHeldInQueue &&
            !engine.isGestureActive
        ) {
            pendingReturnToNormalOnShizukuDead = false
            PointerModeController.forceReturnToNormalFromShizukuLoss()
        }

        if (pendingWindowRecreate &&
            !isHoldActive &&
            !isMouseButtonHeldInQueue &&
            !engine.isHolding
        ) {
            recreateOverlayWindows()
        }
    }

    private fun observeActiveBackend() {
        serviceScope.launch {
            BackendSelector.activeBackend.collect { backend ->
                try {
                    updateCursorBackendReady()
                } finally {
                    if (backend == null) {
                        restoreZoneTouchableOnCleanup(logIfMouseWasDisabled = true)
                    }
                }
            }
        }
    }

    private fun observePointerMode() {
        serviceScope.launch {
            PointerModeController.mode.collect { newMode ->
                try {
                    if (engine.mode != newMode) {
                        engine.setMode(newMode)
                        cursorView?.setHolding(false)
                    }
                    if (newMode == PointerMode.NORMAL) {
                        pendingReturnToNormalOnShizukuDead = false
                    }
                } finally {
                    if (newMode == PointerMode.NORMAL && zoneDisabledByMouseTouch) {
                        handleMouseTouchZoneTouchable(true)
                    }
                }
                updateNotification()
                updateCursorBackendReady()
            }
        }
    }

    private fun observeAccessibilityConnection() {
        serviceScope.launch {
            KeyboardAccessibilityService.isConnected.collect { connected ->
                val targetUseAcc = connected &&
                    (KeyboardAccessibilityService.instance?.getSystemService(WindowManager::class.java) != null)
                if (currentUsingAccessibilityOverlay != targetUseAcc) {
                    if (isHoldActive || isMouseButtonHeldInQueue || engine.isHolding) {
                        pendingWindowRecreate = true
                        Logger.log(
                            "Pointer",
                            "Status aksesibilitas berubah saat HOLD/drag aktif; pemasangan ulang jendela ditunda."
                        )
                    } else {
                        recreateOverlayWindows()
                    }
                }
            }
        }
    }
}
