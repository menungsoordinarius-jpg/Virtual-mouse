package com.sanny.pointertest

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pengontrol status layanan PointerService.
 */
object PointerServiceController {
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    fun setRunning(running: Boolean) {
        _isServiceRunning.value = running
    }
}
