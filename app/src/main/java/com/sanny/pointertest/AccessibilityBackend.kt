package com.sanny.pointertest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Backend penyuntik gestur menggunakan AccessibilityService.dispatchGesture().
 */
class AccessibilityBackend : InputBackend {

    companion object {
        private const val TAP_DURATION_MS = 50L
        private const val HOLD_END_SEGMENT_DURATION_MS = 10L
        private const val MOVE_MIN_DURATION_MS = 16L
        private const val MOVE_MAX_DURATION_MS = 120L
    }

    override val name: String = "Aksesibilitas"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val gestureScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val stateLock = Any()

    @Volatile
    private var isHoldActive: Boolean = false

    @Volatile
    private var holdCancelled: Boolean = false

    private var lastStroke: GestureDescription.StrokeDescription? = null
    private var lastX: Float = 0f
    private var lastY: Float = 0f
    private var holdStartX: Float = 0f
    private var holdStartY: Float = 0f
    private var lastSegmentDispatchMs: Long = 0L

    private var hasPendingTarget: Boolean = false
    private var pendingTargetX: Float = 0f
    private var pendingTargetY: Float = 0f
    private var moveLoopStopping: Boolean = false
    private var moveSignal: CompletableDeferred<Unit>? = null
    private var moveLoopJob: Job? = null

    override fun isAvailable(): Boolean {
        return KeyboardAccessibilityService.instance != null
    }

    override suspend fun tap(x: Float, y: Float) {
        val service = KeyboardAccessibilityService.instance
        if (service == null) {
            Logger.log("Aksesibilitas", "Aksesibilitas tap di (${x.toInt()}, ${y.toInt()}): dibatalkan")
            return
        }

        val safeX = x.coerceAtLeast(0f)
        val safeY = y.coerceAtLeast(0f)
        val path = Path().apply {
            moveTo(safeX, safeY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0L, TAP_DURATION_MS, false)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()

        val completed = dispatchAndAwait(service, gesture)
        if (completed) {
            Logger.log("Aksesibilitas", "Aksesibilitas tap di (${x.toInt()}, ${y.toInt()}): selesai")
        } else {
            Logger.log("Aksesibilitas", "Aksesibilitas tap di (${x.toInt()}, ${y.toInt()}): dibatalkan")
        }
    }

    override suspend fun holdDown(x: Float, y: Float) {
        cleanupPriorHoldIfNeeded()

        val service = KeyboardAccessibilityService.instance
        if (service == null) {
            Logger.log("Aksesibilitas", "Aksesibilitas: gesture DIBATALKAN")
            resetHoldState()
            return
        }

        var started = false
        try {
            val safeX = x.coerceAtLeast(0f)
            val safeY = y.coerceAtLeast(0f)
            val path = Path().apply {
                moveTo(safeX, safeY)
            }
            val initialStroke = GestureDescription.StrokeDescription(
                path,
                0L,
                HOLD_END_SEGMENT_DURATION_MS,
                true
            )
            val gesture = GestureDescription.Builder().addStroke(initialStroke).build()

            val dispatchTime = SystemClock.uptimeMillis()
            val completed = dispatchAndAwait(service, gesture)
            if (!completed) {
                Logger.log("Aksesibilitas", "Aksesibilitas: gesture DIBATALKAN")
                return
            }

            synchronized(stateLock) {
                isHoldActive = true
                holdCancelled = false
                lastStroke = initialStroke
                lastX = safeX
                lastY = safeY
                holdStartX = x
                holdStartY = y
                lastSegmentDispatchMs = dispatchTime
                hasPendingTarget = false
                moveLoopStopping = false
                moveSignal = null
            }

            startMoveLoop()
            started = true
            Logger.log("Aksesibilitas", "Aksesibilitas HOLD dimulai di (${x.toInt()}, ${y.toInt()})")
        } finally {
            if (!started) {
                withContext(NonCancellable) {
                    resetHoldState()
                }
            }
        }
    }

    override suspend fun holdMove(x: Float, y: Float) {
        val safeX = x.coerceAtLeast(0f)
        val safeY = y.coerceAtLeast(0f)
        synchronized(stateLock) {
            if (!isHoldActive || holdCancelled || moveLoopStopping) return
            pendingTargetX = safeX
            pendingTargetY = safeY
            hasPendingTarget = true
            moveSignal?.complete(Unit)
        }
    }

    override suspend fun holdUp(x: Float, y: Float) {
        val jobToJoin: Job?
        synchronized(stateLock) {
            moveLoopStopping = true
            moveSignal?.complete(Unit)
            jobToJoin = moveLoopJob
        }

        try {
            jobToJoin?.join()

            val stroke: GestureDescription.StrokeDescription?
            val active: Boolean
            val cancelled: Boolean
            val startX: Float
            val startY: Float
            val currentX: Float
            val currentY: Float

            synchronized(stateLock) {
                stroke = lastStroke
                active = isHoldActive
                cancelled = holdCancelled
                startX = holdStartX
                startY = holdStartY
                currentX = lastX
                currentY = lastY
            }

            val totalDx = (x - startX).toInt()
            val totalDy = (y - startY).toInt()

            if (!active || cancelled || stroke == null) {
                if (cancelled) {
                    Logger.log(
                        "Aksesibilitas",
                        "Aksesibilitas HOLD selesai (total gerak $totalDx,$totalDy) [dibatalkan]"
                    )
                }
                return
            }

            val service = KeyboardAccessibilityService.instance
            if (service == null) {
                Logger.log("Aksesibilitas", "Aksesibilitas: gesture DIBATALKAN")
                Logger.log(
                    "Aksesibilitas",
                    "Aksesibilitas HOLD selesai (total gerak $totalDx,$totalDy) [dibatalkan]"
                )
                return
            }

            val safeX = x.coerceAtLeast(0f)
            val safeY = y.coerceAtLeast(0f)
            val path = buildSegmentPath(currentX, currentY, safeX, safeY)
            val endStroke = stroke.continueStroke(
                path,
                0L,
                HOLD_END_SEGMENT_DURATION_MS,
                false
            )
            val gesture = GestureDescription.Builder().addStroke(endStroke).build()

            val completed = dispatchAndAwait(service, gesture)
            if (!completed) {
                Logger.log("Aksesibilitas", "Aksesibilitas: gesture DIBATALKAN")
            }
            Logger.log(
                "Aksesibilitas",
                "Aksesibilitas HOLD selesai (total gerak $totalDx,$totalDy)"
            )
        } finally {
            withContext(NonCancellable) {
                resetHoldState()
            }
        }
    }

    private fun startMoveLoop() {
        val job = gestureScope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    var targetX = 0f
                    var targetY = 0f
                    var currentX = 0f
                    var currentY = 0f
                    var stroke: GestureDescription.StrokeDescription? = null
                    var prevDispatchMs = 0L
                    var signalToAwait: CompletableDeferred<Unit>? = null
                    var shouldStop = false
                    var hasWork = false

                    synchronized(stateLock) {
                        if (moveLoopStopping || !isHoldActive || holdCancelled || lastStroke == null) {
                            shouldStop = true
                        } else if (hasPendingTarget) {
                            targetX = pendingTargetX
                            targetY = pendingTargetY
                            hasPendingTarget = false
                            currentX = lastX
                            currentY = lastY
                            stroke = lastStroke
                            prevDispatchMs = lastSegmentDispatchMs
                            hasWork = true
                        } else {
                            val deferred = CompletableDeferred<Unit>()
                            moveSignal = deferred
                            signalToAwait = deferred
                        }
                    }

                    if (shouldStop) break

                    if (!hasWork) {
                        signalToAwait?.await()
                        continue
                    }

                    val activeStroke = stroke ?: break
                    if (targetX == currentX && targetY == currentY) {
                        continue
                    }

                    val service = KeyboardAccessibilityService.instance
                    if (service == null) {
                        Logger.log("Aksesibilitas", "Aksesibilitas: gesture DIBATALKAN")
                        synchronized(stateLock) {
                            holdCancelled = true
                        }
                        resetHoldState()
                        break
                    }

                    val now = SystemClock.uptimeMillis()
                    val duration = (now - prevDispatchMs).coerceIn(
                        MOVE_MIN_DURATION_MS,
                        MOVE_MAX_DURATION_MS
                    )
                    val path = buildSegmentPath(currentX, currentY, targetX, targetY)
                    val nextStroke = activeStroke.continueStroke(path, 0L, duration, true)
                    val gesture = GestureDescription.Builder().addStroke(nextStroke).build()

                    val dispatchTime = SystemClock.uptimeMillis()
                    val completed = dispatchAndAwait(service, gesture)
                    if (!completed) {
                        Logger.log("Aksesibilitas", "Aksesibilitas: gesture DIBATALKAN")
                        synchronized(stateLock) {
                            holdCancelled = true
                        }
                        resetHoldState()
                        break
                    }

                    synchronized(stateLock) {
                        lastStroke = nextStroke
                        lastX = targetX
                        lastY = targetY
                        lastSegmentDispatchMs = dispatchTime
                    }
                }
            } finally {
                synchronized(stateLock) {
                    moveSignal?.complete(Unit)
                    moveSignal = null
                }
            }
        }

        synchronized(stateLock) {
            moveLoopJob = job
        }
        job.start()
    }

    private suspend fun cleanupPriorHoldIfNeeded() {
        val jobToJoin: Job?
        synchronized(stateLock) {
            moveLoopStopping = true
            moveSignal?.complete(Unit)
            jobToJoin = moveLoopJob
        }
        jobToJoin?.join()
        resetHoldState()
    }

    private fun resetHoldState() {
        synchronized(stateLock) {
            isHoldActive = false
            holdCancelled = false
            lastStroke = null
            lastX = 0f
            lastY = 0f
            holdStartX = 0f
            holdStartY = 0f
            lastSegmentDispatchMs = 0L
            hasPendingTarget = false
            pendingTargetX = 0f
            pendingTargetY = 0f
            moveLoopStopping = false
            moveSignal?.complete(Unit)
            moveSignal = null
            moveLoopJob = null
        }
    }

    private fun buildSegmentPath(fromX: Float, fromY: Float, toX: Float, toY: Float): Path {
        return Path().apply {
            moveTo(fromX, fromY)
            if (toX != fromX || toY != fromY) {
                lineTo(toX, toY)
            }
        }
    }

    private suspend fun dispatchAndAwait(
        service: AccessibilityService,
        gesture: GestureDescription
    ): Boolean = suspendCancellableCoroutine { cont ->
        val resumed = AtomicBoolean(false)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                if (resumed.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(true)
                }
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                if (resumed.compareAndSet(false, true) && cont.isActive) {
                    cont.resume(false)
                }
            }
        }

        try {
            val dispatched = service.dispatchGesture(gesture, callback, mainHandler)
            if (!dispatched && resumed.compareAndSet(false, true) && cont.isActive) {
                cont.resume(false)
            }
        } catch (t: Throwable) {
            Logger.log("Aksesibilitas", "Error dispatchGesture: ${t.message}")
            if (resumed.compareAndSet(false, true) && cont.isActive) {
                cont.resume(false)
            }
        }
    }
}
