package com.sanny.pointertest

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * AccessibilityService minimal:
 * Hanya untuk mendeteksi batas jendela keyboard IME (TYPE_INPUT_METHOD).
 * TIDAK untuk klik atau membaca isi layar (canRetrieveWindowContent = false).
 */
class KeyboardAccessibilityService : AccessibilityService() {

    private val tempRect = Rect()

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                    AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.notificationTimeout = 100
            serviceInfo = info

            KeyboardDetector.setConnected(true)
            checkKeyboardWindows()
        } catch (t: Throwable) {
            Logger.log("KeyboardDetector", "Gagal inisialisasi onServiceConnected: ${t.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOWS_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                checkKeyboardWindows()
            }
        }
    }

    override fun onInterrupt() {
        Logger.log("KeyboardDetector", "AccessibilityService onInterrupt dipanggil")
    }

    override fun onDestroy() {
        super.onDestroy()
        KeyboardDetector.setConnected(false)
        KeyboardDetector.updateKeyboardHeight(0)
    }

    private fun checkKeyboardWindows() {
        try {
            val windowList = windows
            var detectedImeHeight = 0
            if (windowList != null) {
                for (window in windowList) {
                    if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        window.getBoundsInScreen(tempRect)
                        val height = tempRect.height()
                        if (height > 0) {
                            detectedImeHeight = height
                        }
                    }
                }
            }
            KeyboardDetector.updateKeyboardHeight(detectedImeHeight)
        } catch (t: Throwable) {
            Logger.log("KeyboardDetector", "Error memeriksa jendela IME: ${t.message}")
        }
    }
}
