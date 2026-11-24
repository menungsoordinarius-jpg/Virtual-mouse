package com.sanny.pointertest

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import rikka.shizuku.Shizuku

enum class PointerMode {
    NORMAL,
    MOUSE
}

/**
 * Singleton pengelola status mode pointer (NORMAL atau MOUSE) dengan StateFlow.
 * Mode MOUSE hanya diizinkan jika Shizuku berjalan dan izin telah diberikan.
 */
object PointerModeController {

    private val _mode = MutableStateFlow(PointerMode.NORMAL)
    val mode: StateFlow<PointerMode> = _mode.asStateFlow()

    private val mainHandler = Handler(Looper.getMainLooper())

    fun isShizukuReady(): Boolean {
        return try {
            Shizuku.pingBinder() &&
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun setMode(context: Context?, targetMode: PointerMode): Boolean {
        if (targetMode == PointerMode.MOUSE && !isShizukuReady()) {
            Logger.log(
                "Mode",
                "Pindah ke Mode Mouse ditolak: Shizuku tidak berjalan atau izin belum diberikan."
            )
            _mode.value = PointerMode.NORMAL
            context?.applicationContext?.let { appCtx ->
                mainHandler.post {
                    Toast.makeText(appCtx, "Mode mouse butuh Shizuku", Toast.LENGTH_SHORT).show()
                }
            }
            return false
        }

        if (_mode.value != targetMode) {
            _mode.value = targetMode
            Logger.log("Mode", "Mode pointer aktif: ${targetMode.name}")
        }
        return true
    }

    fun forceReturnToNormalFromShizukuLoss() {
        if (_mode.value != PointerMode.NORMAL) {
            _mode.value = PointerMode.NORMAL
            Logger.log("Mode", "Shizuku mati saat mode MOUSE: otomatis kembali ke NORMAL.")
        }
    }
}
