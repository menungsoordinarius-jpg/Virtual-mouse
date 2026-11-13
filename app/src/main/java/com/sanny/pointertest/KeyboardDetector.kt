package com.sanny.pointertest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton untuk berbagi data tinggi keyboard IME dari AccessibilityService ke PointerService.
 */
object KeyboardDetector {
    private val _keyboardHeight = MutableStateFlow(0)
    val keyboardHeight: StateFlow<Int> = _keyboardHeight.asStateFlow()

    private val _isServiceConnected = MutableStateFlow(false)
    val isServiceConnected: StateFlow<Boolean> = _isServiceConnected.asStateFlow()

    fun updateKeyboardHeight(heightPx: Int) {
        val sanitized = heightPx.coerceAtLeast(0)
        if (_keyboardHeight.value != sanitized) {
            _keyboardHeight.value = sanitized
            Logger.log("KeyboardDetector", "Tinggi keyboard terbaca: ${sanitized}px")
        }
    }

    fun setConnected(connected: Boolean) {
        _isServiceConnected.value = connected
        Logger.log(
            "KeyboardDetector",
            "AccessibilityService status: ${if (connected) "AKTIF" else "NONAKTIF"}"
        )
    }
}
