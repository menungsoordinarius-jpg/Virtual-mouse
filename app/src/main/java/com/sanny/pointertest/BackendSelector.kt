package com.sanny.pointertest

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Memilih backend penyuntik input aktif secara otomatis berdasarkan ketersediaan
 * Shizuku, AccessibilityService, dan preferensi "Paksa pakai aksesibilitas (tes)".
 * Penggantian backend ditunda selama HOLD berlangsung dan hanya terjadi di antara gestur.
 */
object BackendSelector {

    private const val PREFS_NAME = "pointer_backend_prefs"
    private const val KEY_FORCE_ACCESSIBILITY = "force_accessibility"

    val shizukuBackend = ShizukuBackend()
    val accessibilityBackend = AccessibilityBackend()

    private val _forceAccessibility = MutableStateFlow(false)
    val forceAccessibility: StateFlow<Boolean> = _forceAccessibility.asStateFlow()

    private val _activeBackend = MutableStateFlow<InputBackend?>(null)
    val activeBackend: StateFlow<InputBackend?> = _activeBackend.asStateFlow()

    private var initialized = false
    private var hasLoggedInitial = false
    private var isHoldInProgress = false

    @Synchronized
    fun init(context: Context) {
        if (!initialized) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _forceAccessibility.value = prefs.getBoolean(KEY_FORCE_ACCESSIBILITY, false)
            initialized = true
        }
        evaluate()
    }

    @Synchronized
    fun setForceAccessibility(context: Context, enabled: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_FORCE_ACCESSIBILITY, enabled).apply()
        initialized = true
        if (_forceAccessibility.value != enabled) {
            _forceAccessibility.value = enabled
            Logger.log(
                "Backend",
                "Paksa pakai aksesibilitas (tes): ${if (enabled) "AKTIF" else "NONAKTIF"}"
            )
        }
        evaluate()
    }

    @Synchronized
    fun setHoldInProgress(inProgress: Boolean) {
        if (isHoldInProgress == inProgress) return
        isHoldInProgress = inProgress
        if (!inProgress) {
            evaluate()
        }
    }

    @Synchronized
    fun evaluate(): InputBackend? {
        val forceAcc = _forceAccessibility.value
        val shizukuReady = shizukuBackend.isAvailable()
        val accessibilityReady = accessibilityBackend.isAvailable()

        val desired: InputBackend? = when {
            forceAcc && accessibilityReady -> accessibilityBackend
            shizukuReady -> shizukuBackend
            accessibilityReady -> accessibilityBackend
            else -> null
        }

        // Jangan pernah mengganti backend di tengah HOLD
        if (isHoldInProgress) {
            return _activeBackend.value
        }

        val current = _activeBackend.value
        if (!hasLoggedInitial || current !== desired) {
            hasLoggedInitial = true
            _activeBackend.value = desired
            val backendName = desired?.name ?: "Tidak ada"
            Logger.log("Backend", "Backend aktif: $backendName")
        }

        return _activeBackend.value
    }

    fun getActiveBackendLabel(): String {
        return _activeBackend.value?.name ?: "Tidak ada"
    }
}
