package com.sanny.pointertest

import android.app.Application
import android.os.Build
import org.lsposed.hiddenapibypass.HiddenApiBypass

class PointerTestApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                HiddenApiBypass.addHiddenApiExemptions("")
                Logger.log("App", "HiddenApiBypass berhasil diaktifkan")
            } catch (t: Throwable) {
                Logger.log("App", "Gagal inisialisasi HiddenApiBypass: ${t.message}")
            }
        }
    }
}
