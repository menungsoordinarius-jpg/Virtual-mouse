package com.sanny.pointertest

import android.os.IBinder
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.MotionEvent
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.lang.reflect.Method

data class InjectionResult(
    val success: Boolean,
    val details: String,
    val exception: Throwable? = null
)

object EventInjector {
    /**
     * Mode asynchronous: InputManager.INJECT_INPUT_EVENT_MODE_ASYNC = 0
     * Sesuai instruksi: mode inject INJECT_INPUT_EVENT_MODE_ASYNC.
     */
    const val INJECT_INPUT_EVENT_MODE_ASYNC = 0

    private var cachedManager: Any? = null
    private var cachedMethod: Method? = null
    private var channelDescription = "None"

    @Synchronized
    fun reset() {
        cachedManager = null
        cachedMethod = null
        channelDescription = "None"
    }

    @Synchronized
    private fun getOrInitManager(): Pair<Any, Method> {
        cachedManager?.let { mgr ->
            cachedMethod?.let { method ->
                return Pair(mgr, method)
            }
        }

        var lastError: Throwable? = null

        // 1. ShizukuBinderWrapper via SystemServiceHelper("input")
        try {
            val rawBinder = SystemServiceHelper.getSystemService("input")
            if (rawBinder != null) {
                val wrappedBinder = ShizukuBinderWrapper(rawBinder)
                val stubClass = Class.forName("android.hardware.input.IInputManager\$Stub")
                val asInterface = stubClass.getMethod("asInterface", IBinder::class.java)
                val iInputManager = asInterface.invoke(null, wrappedBinder)

                if (iInputManager != null) {
                    val method = findInjectMethod(iInputManager.javaClass)
                    if (method != null) {
                        cachedManager = iInputManager
                        cachedMethod = method
                        channelDescription = "Shizuku IInputManager BinderWrapper"
                        Logger.log("Injector", "Tersambung ke $channelDescription")
                        return Pair(iInputManager, method)
                    }
                }
            }
        } catch (t: Throwable) {
            lastError = t
            Logger.log("Injector", "Gagal inisialisasi via ShizukuBinderWrapper: ${t.message}")
        }

        // 2. Direct Reflection via InputManager.getInstance()
        try {
            val imClass = Class.forName("android.hardware.input.InputManager")
            val getInstance = imClass.getMethod("getInstance")
            val im = getInstance.invoke(null)
            if (im != null) {
                val method = findInjectMethod(im.javaClass)
                if (method != null) {
                    cachedManager = im
                    cachedMethod = method
                    channelDescription = "InputManager.getInstance() (Direct Reflection)"
                    Logger.log("Injector", "Tersambung ke $channelDescription")
                    return Pair(im, method)
                }
            }
        } catch (t: Throwable) {
            lastError = t
            Logger.log("Injector", "Gagal inisialisasi via InputManager.getInstance(): ${t.message}")
        }

        // 3. Fallback via InputManagerGlobal.getInstance() (Android 14+)
        try {
            val imgClass = Class.forName("android.hardware.input.InputManagerGlobal")
            val getInstance = imgClass.getMethod("getInstance")
            val img = getInstance.invoke(null)
            if (img != null) {
                val method = findInjectMethod(img.javaClass)
                if (method != null) {
                    cachedManager = img
                    cachedMethod = method
                    channelDescription = "InputManagerGlobal.getInstance() (Reflection)"
                    Logger.log("Injector", "Tersambung ke $channelDescription")
                    return Pair(img, method)
                }
            }
        } catch (t: Throwable) {
            lastError = t
            Logger.log("Injector", "Gagal inisialisasi via InputManagerGlobal: ${t.message}")
        }

        throw IllegalStateException(
            "Tidak dapat menemukan metode injectInputEvent: ${lastError?.message}",
            lastError
        )
    }

    private fun findInjectMethod(clazz: Class<*>): Method? {
        // Priority 1: injectInputEvent(InputEvent, int)
        try {
            val m = clazz.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            m.isAccessible = true
            return m
        } catch (_: NoSuchMethodException) {}

        // Priority 2: injectInputEvent(InputEvent, int, int)
        try {
            val m = clazz.getMethod(
                "injectInputEvent",
                InputEvent::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType
            )
            m.isAccessible = true
            return m
        } catch (_: NoSuchMethodException) {}

        // Priority 3: iterate methods
        for (m in clazz.methods) {
            if (m.name == "injectInputEvent") {
                m.isAccessible = true
                return m
            }
        }
        return null
    }

    /**
     * Menyuntikkan InputEvent dengan mode INJECT_INPUT_EVENT_MODE_ASYNC.
     * Mengembalikan InjectionResult dan mencatat log detail.
     */
    fun injectEvent(event: InputEvent, mode: Int = INJECT_INPUT_EVENT_MODE_ASYNC): InjectionResult {
        return try {
            val (manager, method) = getOrInitManager()
            val result = when (method.parameterTypes.size) {
                2 -> method.invoke(manager, event, mode)
                3 -> method.invoke(manager, event, mode, 0 /* targetUid */)
                else -> method.invoke(manager, event, mode)
            }
            val isSuccess = (result as? Boolean) ?: (result != null)
            val details = "Sukses=$isSuccess | Mode=$mode | Saluran=$channelDescription"
            Logger.log("Injector", details)
            InjectionResult(success = isSuccess, details = details)
        } catch (t: Throwable) {
            val actual = if (t is java.lang.reflect.InvocationTargetException && t.targetException != null) {
                t.targetException
            } else {
                t
            }
            val details = "GAGAL: ${actual.javaClass.simpleName} - ${actual.message}"
            Logger.log("Injector", details)
            InjectionResult(success = false, details = details, exception = actual)
        }
    }

    // ==========================================
    // Event Factory Helpers
    // ==========================================

    fun createMouseHoverEvent(
        action: Int,
        x: Float,
        y: Float,
        eventTime: Long = SystemClock.uptimeMillis()
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = 0f
            this.size = 1f
        })

        return MotionEvent.obtain(
            eventTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
    }

    fun createMouseButtonEvent(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        actionButton: Int = 0,
        deviceId: Int = 0,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = SystemClock.uptimeMillis()
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            this.size = 1f
        })

        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            buttonState,
            1f,
            1f,
            deviceId,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )

        if (actionButton != 0) {
            try {
                val setActionButton = MotionEvent::class.java.getMethod(
                    "setActionButton",
                    Int::class.javaPrimitiveType
                )
                setActionButton.invoke(event, actionButton)
            } catch (_: Throwable) {}
        }

        return event
    }

    /**
     * Varian A: Murni MotionEvent mouse tanpa memanggil setActionButton sama sekali.
     */
    fun createMouseVariantA(
        action: Int,
        x: Float,
        y: Float,
        buttonState: Int,
        deviceId: Int = 0,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = SystemClock.uptimeMillis()
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            this.size = 1f
        })

        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            buttonState,
            1f,
            1f,
            deviceId,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
    }

    /**
     * Varian D: Panggil MotionEvent.setDisplayId(displayId) lewat reflection.
     */
    fun setDisplayId(event: MotionEvent, displayId: Int = 0): Boolean {
        return try {
            val method = MotionEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
            method.isAccessible = true
            method.invoke(event, displayId)
            true
        } catch (t: Throwable) {
            Logger.log("Injector", "Gagal memanggil setDisplayId: ${t.message}")
            false
        }
    }

    /**
     * Varian E: Jalankan perintah shell lewat Shizuku (Shizuku.newProcess lewat reflection).
     * Mengembalikan exit code dan gabungan output stdout + stderr.
     */
    fun executeShellCommand(command: String): Pair<Int, String> {
        return try {
            val newProcessMethod = Shizuku::class.java.getMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            val process = newProcessMethod.invoke(
                null,
                arrayOf("sh", "-c", command),
                null,
                null
            ) as Process

            val stdout = process.inputStream.bufferedReader().use { it.readText() }
            val stderr = process.errorStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()

            val combined = buildString {
                if (stdout.isNotBlank()) append(stdout.trim())
                if (stderr.isNotBlank()) {
                    if (isNotEmpty()) append("\n")
                    append("STDERR: ").append(stderr.trim())
                }
                if (isEmpty()) append("(tanpa output teks)")
            }
            Pair(exitCode, combined)
        } catch (t: Throwable) {
            val actual = if (t is java.lang.reflect.InvocationTargetException && t.targetException != null) {
                t.targetException
            } else {
                t
            }
            Pair(-1, "Exception: ${actual.javaClass.simpleName} - ${actual.message}")
        }
    }

    fun createMouseScrollEvent(
        x: Float,
        y: Float,
        vScroll: Float,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = SystemClock.uptimeMillis()
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_MOUSE
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = 0f
            this.size = 1f
            setAxisValue(MotionEvent.AXIS_VSCROLL, vScroll)
        })

        return MotionEvent.obtain(
            downTime,
            eventTime,
            MotionEvent.ACTION_SCROLL,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_MOUSE,
            0
        )
    }

    fun createTouchEvent(
        action: Int,
        x: Float,
        y: Float,
        downTime: Long = SystemClock.uptimeMillis(),
        eventTime: Long = SystemClock.uptimeMillis()
    ): MotionEvent {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            this.pressure = if (action == MotionEvent.ACTION_UP) 0f else 1f
            this.size = 1f
        })

        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coords,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
    }
}
