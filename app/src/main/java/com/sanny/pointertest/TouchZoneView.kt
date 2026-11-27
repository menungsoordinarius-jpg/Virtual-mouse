package com.sanny.pointertest

import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Region
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * View transparan untuk zona sentuh overlay.
 * Menangkap seluruh sentuhan dan meneruskannya ke PointerEngine.
 * Menggunakan ViewTreeObserver.OnComputeInternalInsetsListener (mode lubang)
 * agar suntikan di posisi pointer menembus zona tanpa menonaktifkan touchable seluruh zona.
 */
class TouchZoneView(
    context: Context,
    private val engine: PointerEngine
) : View(context) {

    companion object {
        private const val TOUCHABLE_INSETS_REGION = 3
        private const val HOLE_HALF_SIZE_PX = 4
        private const val LAYOUT_THROTTLE_MS = 16L
        private const val MOUSE_LEAK_LOG_THROTTLE_MS = 2000L
    }

    var isHoleModeActive: Boolean = false
        private set

    @Volatile
    var lastHoleUpdatedTimeMs: Long = 0L
        private set

    var onMouseTouchRestoreRequest: (() -> Unit)? = null
    var isZoneNotTouchableProvider: (() -> Boolean)? = null
    var zoneLayoutFlagsProvider: (() -> Int)? = null
    var onTouchDownTimestamp: ((Long) -> Unit)? = null
    var cancelDiagnosticsProvider: ((Long) -> String)? = null

    private var holePointerX: Float = engine.pointerX
    private var holePointerY: Float = engine.pointerY

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastHoleUpdateTimeMs: Long = 0L
    private var pendingTrailingUpdate: Boolean = false
    private var holeUpdateCount: Int = 0
    private var lastMouseLeakLogTimeMs: Long = 0L

    private var gestureDownTimeMs: Long = 0L
    private var gestureMoveCount: Int = 0
    private var isTrackingGesture: Boolean = false
    private var postDownEventLogCount: Int = 0
    private var downZoneLayoutFlags: Int = 0

    private val holeRect = Rect()

    private var computeInsetsProxy: Any? = null
    private var removeListenerMethod: Method? = null
    private var setTouchableInsetsMethod: Method? = null
    private var touchableRegionField: Field? = null

    private val trailingLayoutRunnable = Runnable {
        performHoleLayoutRequest(callerName = "trailingLayoutRunnable")
    }

    init {
        setBackgroundColor(Color.TRANSPARENT)
        isClickable = true
        isFocusable = false
    }

    fun setupHoleMode(): Boolean {
        if (isHoleModeActive) return true

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/view/ViewTreeObserver")
            }

            val listenerInterface =
                Class.forName("android.view.ViewTreeObserver\$OnComputeInternalInsetsListener")
            val insetsClass =
                Class.forName("android.view.ViewTreeObserver\$InternalInsetsInfo")

            val setInsetsMethod =
                insetsClass.getMethod("setTouchableInsets", Int::class.javaPrimitiveType)
            val regionField = insetsClass.getField("touchableRegion")
            val addMethod = ViewTreeObserver::class.java.getMethod(
                "addOnComputeInternalInsetsListener",
                listenerInterface
            )
            val removeMethod = try {
                ViewTreeObserver::class.java.getMethod(
                    "removeOnComputeInternalInsetsListener",
                    listenerInterface
                )
            } catch (_: Throwable) {
                null
            }

            setInsetsMethod.isAccessible = true
            regionField.isAccessible = true
            addMethod.isAccessible = true
            removeMethod?.isAccessible = true

            setTouchableInsetsMethod = setInsetsMethod
            touchableRegionField = regionField
            removeListenerMethod = removeMethod

            val proxy = Proxy.newProxyInstance(
                listenerInterface.classLoader,
                arrayOf(listenerInterface)
            ) { proxyObj, method, args ->
                when (method.name) {
                    "onComputeInternalInsets" -> {
                        val insets = args?.firstOrNull()
                        if (insets != null) {
                            computeHoleInsets(insets)
                        }
                        null
                    }
                    "equals" -> proxyObj === args?.firstOrNull()
                    "hashCode" -> System.identityHashCode(proxyObj)
                    "toString" -> "TouchZoneHoleListener@${Integer.toHexString(System.identityHashCode(proxyObj))}"
                    else -> null
                }
            }

            addMethod.invoke(viewTreeObserver, proxy)
            computeInsetsProxy = proxy
            isHoleModeActive = true
            holeUpdateCount = 0

            val setupNow = SystemClock.uptimeMillis()
            Logger.log("TouchZone", "Mode lubang AKTIF")
            Logger.log("TouchZone", "updateViewLayout jendela=lubang alasan=setupHoleMode ts=$setupNow")
            requestLayout()
            true
        } catch (t: Throwable) {
            val actual = (t as? InvocationTargetException)?.targetException ?: t
            isHoleModeActive = false
            computeInsetsProxy = null
            Logger.log(
                "TouchZone",
                "Mode lubang GAGAL: ${actual.javaClass.simpleName} - ${actual.message}"
            )
            false
        }
    }

    fun updatePointerHole(x: Float, y: Float) {
        holePointerX = x
        holePointerY = y
        if (!isHoleModeActive) return

        val now = SystemClock.uptimeMillis()
        val elapsed = now - lastHoleUpdateTimeMs
        if (elapsed >= LAYOUT_THROTTLE_MS) {
            mainHandler.removeCallbacks(trailingLayoutRunnable)
            pendingTrailingUpdate = false
            performHoleLayoutRequest(now, "updatePointerHole")
        } else if (!pendingTrailingUpdate) {
            pendingTrailingUpdate = true
            mainHandler.postDelayed(trailingLayoutRunnable, LAYOUT_THROTTLE_MS - elapsed)
        }
    }

    fun flushPendingHoleUpdate() {
        if (isHoleModeActive && pendingTrailingUpdate) {
            mainHandler.removeCallbacks(trailingLayoutRunnable)
            performHoleLayoutRequest(callerName = "flushPendingHoleUpdate")
        }
    }

    private fun performHoleLayoutRequest(
        now: Long = SystemClock.uptimeMillis(),
        callerName: String = "performHoleLayoutRequest"
    ) {
        lastHoleUpdateTimeMs = now
        lastHoleUpdatedTimeMs = now
        pendingTrailingUpdate = false
        if (!isHoleModeActive) return
        Logger.log("TouchZone", "updateViewLayout jendela=lubang alasan=$callerName ts=$now")
        requestLayout()
    }

    private fun computeHoleInsets(insets: Any) {
        try {
            val zoneWidth = width
            val zoneHeight = height
            if (zoneWidth <= 0 || zoneHeight <= 0) {
                return
            }

            val region = touchableRegionField?.get(insets) as? Region ?: return
            region.set(0, 0, zoneWidth, zoneHeight)

            val cx = holePointerX.toInt()
            val cy = holePointerY.toInt()
            // Kotak 8x8 px berpusat di (pointerX, pointerY)
            holeRect.set(
                cx - HOLE_HALF_SIZE_PX,
                cy - HOLE_HALF_SIZE_PX,
                cx + HOLE_HALF_SIZE_PX,
                cy + HOLE_HALF_SIZE_PX
            )
            region.op(holeRect, Region.Op.DIFFERENCE)

            setTouchableInsetsMethod?.invoke(insets, TOUCHABLE_INSETS_REGION)
            lastHoleUpdatedTimeMs = SystemClock.uptimeMillis()

            holeUpdateCount++
            if (holeUpdateCount % 20 == 0) {
                Logger.log(
                    "TouchZone",
                    "Pembaruan lubang #$holeUpdateCount di ($cx, $cy) [Zona ${zoneWidth}x${zoneHeight}]"
                )
            }
        } catch (t: Throwable) {
            val actual = (t as? InvocationTargetException)?.targetException ?: t
            Logger.log(
                "TouchZone",
                "Mode lubang GAGAL: ${actual.javaClass.simpleName} - ${actual.message}"
            )
            isHoleModeActive = false
            unregisterHoleListener()
        }
    }

    private fun unregisterHoleListener() {
        val proxy = computeInsetsProxy ?: return
        computeInsetsProxy = null
        try {
            val vto = viewTreeObserver
            if (vto != null && vto.isAlive) {
                removeListenerMethod?.invoke(vto, proxy)
            }
        } catch (_: Throwable) {}
    }

    private fun toolTypeToString(toolType: Int): String = when (toolType) {
        MotionEvent.TOOL_TYPE_FINGER -> "FINGER"
        MotionEvent.TOOL_TYPE_MOUSE -> "MOUSE"
        MotionEvent.TOOL_TYPE_STYLUS -> "STYLUS"
        MotionEvent.TOOL_TYPE_ERASER -> "ERASER"
        MotionEvent.TOOL_TYPE_UNKNOWN -> "UNKNOWN"
        else -> "TYPE_$toolType"
    }

    private fun sourceToString(source: Int): String {
        val label = when {
            (source and InputDevice.SOURCE_TOUCHSCREEN) == InputDevice.SOURCE_TOUCHSCREEN -> "TOUCHSCREEN"
            (source and InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE -> "MOUSE"
            else -> "OTHER"
        }
        return "$label(0x${Integer.toHexString(source)})"
    }

    private fun isMouseEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        for (i in 0 until event.pointerCount) {
            if (event.getToolType(i) == MotionEvent.TOOL_TYPE_MOUSE) return true
        }
        return false
    }

    private fun checkMouseLeak(event: MotionEvent) {
        if (isMouseEvent(event)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastMouseLeakLogTimeMs >= MOUSE_LEAK_LOG_THROTTLE_MS) {
                lastMouseLeakLogTimeMs = now
                Logger.log(
                    "TouchZone",
                    "Zona menerima event mouse (bocor) [${MotionEvent.actionToString(event.actionMasked)}, " +
                        "pos=(${event.x.toInt()},${event.y.toInt()})]"
                )
            }
        }
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(trailingLayoutRunnable)
        pendingTrailingUpdate = false
        onMouseTouchRestoreRequest?.invoke()
        isTrackingGesture = false
        gestureMoveCount = 0
        unregisterHoleListener()
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (isMouseEvent(event)) {
            checkMouseLeak(event)
            return false
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (isMouseEvent(event)) {
            checkMouseLeak(event)
            return false
        }
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        if (isMouseEvent(event)) {
            checkMouseLeak(event)
            return false
        }
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isMouseEvent(event)) {
            checkMouseLeak(event)
            return false
        }
        val actionMasked = event.actionMasked
        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            val nowUptime = SystemClock.uptimeMillis()
            val elapsedFromDownMs = if (isTrackingGesture) {
                (event.eventTime - gestureDownTimeMs).coerceAtLeast(0L)
            } else {
                0L
            }
            val notTouchableActive = isZoneNotTouchableProvider?.invoke() ?: false
            Logger.log(
                "TouchZone",
                "CANCEL setelah $elapsedFromDownMs ms dari DOWN " +
                    "(flag NOT_TOUCHABLE aktif: ${if (notTouchableActive) "ya" else "tidak"}) " +
                    "[mode=${engine.mode.name}, isHolding=${engine.isHolding}]"
            )
            val currentFlags = zoneLayoutFlagsProvider?.invoke() ?: 0
            val cancelIdx = event.actionIndex
            val cancelToolType = toolTypeToString(event.getToolType(cancelIdx))
            val cancelSource = sourceToString(event.source)
            val fingerX = event.getX(cancelIdx).toInt()
            val fingerY = event.getY(cancelIdx).toInt()
            val activeHoleRect = if (!holeRect.isEmpty) {
                holeRect
            } else {
                val cx = holePointerX.toInt()
                val cy = holePointerY.toInt()
                Rect(
                    cx - HOLE_HALF_SIZE_PX,
                    cy - HOLE_HALF_SIZE_PX,
                    cx + HOLE_HALF_SIZE_PX,
                    cy + HOLE_HALF_SIZE_PX
                )
            }
            val holePositionLabel = if (activeHoleRect.contains(fingerX, fingerY)) "di dalam" else "di luar"
            val sinceHoleMs = if (lastHoleUpdatedTimeMs > 0L) {
                "${(nowUptime - lastHoleUpdatedTimeMs).coerceAtLeast(0L)} ms"
            } else {
                "belum"
            }
            val serviceDiag = cancelDiagnosticsProvider?.invoke(nowUptime) ?: "diag=tidak_tersedia"
            Logger.log(
                "TouchZone",
                "CANCEL detail: flagsNow=0x${Integer.toHexString(currentFlags)} flagsDown=0x${Integer.toHexString(downZoneLayoutFlags)} | " +
                    "tool=$cancelToolType src=$cancelSource | " +
                    "jari=($fingerX,$fingerY) $holePositionLabel lubang=[${activeHoleRect.left},${activeHoleRect.top}-${activeHoleRect.right},${activeHoleRect.bottom}] | " +
                    "sejakLubang=$sinceHoleMs | $serviceDiag"
            )
        }

        return try {
            when (actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    val downDelayMs = SystemClock.uptimeMillis() - event.eventTime
                    Logger.log("TouchZone", "Keterlambatan DOWN: $downDelayMs ms")
                    gestureDownTimeMs = event.eventTime
                    downZoneLayoutFlags = zoneLayoutFlagsProvider?.invoke() ?: 0
                    onTouchDownTimestamp?.invoke(event.eventTime)
                    gestureMoveCount = 0
                    postDownEventLogCount = 0
                    isTrackingGesture = true
                    Logger.log(
                        "TouchZone",
                        "Event #0 (DOWN): action=${MotionEvent.actionToString(actionMasked)} " +
                            "tool=${toolTypeToString(event.getToolType(0))} src=${sourceToString(event.source)} " +
                            "x=${event.getX(0).toInt()} y=${event.getY(0).toInt()} +0ms"
                    )
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isTrackingGesture) {
                        gestureMoveCount++
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isTrackingGesture) {
                        val durationMs = (event.eventTime - gestureDownTimeMs).coerceAtLeast(0L)
                        if (gestureMoveCount > 0) {
                            Logger.log(
                                "TouchZone",
                                "Gerakan jari: $gestureMoveCount event dalam $durationMs ms"
                            )
                        }
                        gestureMoveCount = 0
                        isTrackingGesture = false
                    }
                    flushPendingHoleUpdate()
                    onMouseTouchRestoreRequest?.invoke()
                }
            }
            if (actionMasked != MotionEvent.ACTION_DOWN && postDownEventLogCount < 10) {
                postDownEventLogCount++
                val evIdx = event.actionIndex
                val deltaFromDownMs = (event.eventTime - gestureDownTimeMs).coerceAtLeast(0L)
                Logger.log(
                    "TouchZone",
                    "Event #$postDownEventLogCount/10: action=${MotionEvent.actionToString(actionMasked)} " +
                        "tool=${toolTypeToString(event.getToolType(evIdx))} src=${sourceToString(event.source)} " +
                        "x=${event.getX(evIdx).toInt()} y=${event.getY(evIdx).toInt()} +${deltaFromDownMs}ms"
                )
            }
            engine.onTouchEvent(event)
        } finally {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                onMouseTouchRestoreRequest?.invoke()
            }
        }
    }
}
