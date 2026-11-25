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

    var onMouseTouchWindowTouchableRequest: ((Boolean) -> Unit)? = null

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
    private var mouseTouchDisabledZone: Boolean = false

    private val holeRect = Rect()

    private var computeInsetsProxy: Any? = null
    private var removeListenerMethod: Method? = null
    private var setTouchableInsetsMethod: Method? = null
    private var touchableRegionField: Field? = null

    private val trailingLayoutRunnable = Runnable {
        performHoleLayoutRequest()
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

            Logger.log("TouchZone", "Mode lubang AKTIF")
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
            performHoleLayoutRequest(now)
        } else if (!pendingTrailingUpdate) {
            pendingTrailingUpdate = true
            mainHandler.postDelayed(trailingLayoutRunnable, LAYOUT_THROTTLE_MS - elapsed)
        }
    }

    private fun performHoleLayoutRequest(now: Long = SystemClock.uptimeMillis()) {
        lastHoleUpdateTimeMs = now
        pendingTrailingUpdate = false
        if (!isHoleModeActive) return
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

    private fun checkMouseLeak(event: MotionEvent) {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            val now = SystemClock.uptimeMillis()
            if (now - lastMouseLeakLogTimeMs >= MOUSE_LEAK_LOG_THROTTLE_MS) {
                lastMouseLeakLogTimeMs = now
                Logger.log("TouchZone", "Zona menerima event mouse (bocor)")
            }
        }
    }

    override fun onDetachedFromWindow() {
        mainHandler.removeCallbacks(trailingLayoutRunnable)
        pendingTrailingUpdate = false
        if (mouseTouchDisabledZone) {
            mouseTouchDisabledZone = false
            onMouseTouchWindowTouchableRequest?.invoke(true)
        }
        isTrackingGesture = false
        gestureMoveCount = 0
        unregisterHoleListener()
        super.onDetachedFromWindow()
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        checkMouseLeak(event)
        return super.onGenericMotionEvent(event)
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        checkMouseLeak(event)
        return super.onHoverEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked
        if (actionMasked == MotionEvent.ACTION_CANCEL) {
            Logger.log(
                "TouchZone",
                "ACTION_CANCEL diterima di zona (mode=${engine.mode.name}, isHolding=${engine.isHolding})"
            )
        }

        return try {
            when (actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    gestureDownTimeMs = event.eventTime
                    gestureMoveCount = 0
                    isTrackingGesture = true
                    if (engine.mode == PointerMode.MOUSE) {
                        mouseTouchDisabledZone = true
                        onMouseTouchWindowTouchableRequest?.invoke(false)
                    }
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
                }
            }
            engine.onTouchEvent(event)
        } finally {
            if (actionMasked == MotionEvent.ACTION_UP || actionMasked == MotionEvent.ACTION_CANCEL) {
                if (mouseTouchDisabledZone) {
                    mouseTouchDisabledZone = false
                    onMouseTouchWindowTouchableRequest?.invoke(true)
                }
            }
        }
    }
}
