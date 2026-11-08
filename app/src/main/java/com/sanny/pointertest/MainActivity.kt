package com.sanny.pointertest

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.sanny.pointertest.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var countdownJob: Job? = null
    private val SHIZUKU_REQ_CODE = 1001

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (OverlayManager.canDrawOverlays(this)) {
            Logger.log("Overlay", "Izin overlay berhasil diberikan.")
            OverlayManager.showOverlay(this)
            updateOverlayButtonText()
        } else {
            Logger.log("Overlay", "Izin overlay ditolak oleh pengguna.")
            Toast.makeText(this, "Izin overlay belum aktif", Toast.LENGTH_SHORT).show()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        Logger.log("Shizuku", "OnBinderReceivedListener terpanggil")
        updateShizukuStatus()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Logger.log("Shizuku", "OnBinderDeadListener: Shizuku service mati")
        EventInjector.reset()
        updateShizukuStatus()
    }

    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQ_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                Logger.log("Shizuku", "Hasil izin Shizuku: ${if (granted) "DIBERIKAN" else "DITOLAK"}")
                updateShizukuStatus()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupShizukuListeners()
        setupDeviceInfo()
        setupCoordinateInputs()
        setupLogConsole()
        setupTestButtons()
        setupOverlayCallback()

        updateShizukuStatus()
    }

    override fun onResume() {
        super.onResume()
        updateShizukuStatus()
        updateOverlayButtonText()
    }

    override fun onDestroy() {
        super.onDestroy()
        countdownJob?.cancel()
        removeShizukuListeners()
        Logger.setListener(null)
        OverlayManager.onOverlayStateChanged = null
    }

    // ==========================================
    // BAGIAN 1: Shizuku Listeners & Status
    // ==========================================

    private fun setupShizukuListeners() {
        try {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        } catch (t: Throwable) {
            Logger.log("Shizuku", "Gagal mendaftarkan listener Shizuku: ${t.message}")
        }

        binding.btnRequestPermission.setOnClickListener {
            requestShizukuPermission()
        }

        binding.btnRefreshStatus.setOnClickListener {
            updateShizukuStatus()
            Toast.makeText(this, "Status diperbarui", Toast.LENGTH_SHORT).show()
        }
    }

    private fun removeShizukuListeners() {
        try {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            Shizuku.removeRequestPermissionResultListener(permissionResultListener)
        } catch (_: Throwable) {}
    }

    private fun updateShizukuStatus() {
        val isPingSuccess = try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }

        if (isPingSuccess) {
            binding.tvShizukuRunning.text = "Berjalan (v${Shizuku.getVersion()})"
            binding.tvShizukuRunning.setBackgroundResource(R.drawable.bg_status_badge_green)
            binding.tvShizukuRunning.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
        } else {
            binding.tvShizukuRunning.text = "Tidak Berjalan"
            binding.tvShizukuRunning.setBackgroundResource(R.drawable.bg_status_badge_red)
            binding.tvShizukuRunning.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
        }

        val hasPermission = try {
            if (isPingSuccess) {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }

        if (hasPermission) {
            binding.tvShizukuPermission.text = "Diberikan"
            binding.tvShizukuPermission.setBackgroundResource(R.drawable.bg_status_badge_green)
            binding.tvShizukuPermission.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            binding.btnRequestPermission.isEnabled = false
            binding.btnRequestPermission.text = "Izin Telah Diberikan"
        } else {
            binding.tvShizukuPermission.text = "Belum Diberikan"
            binding.tvShizukuPermission.setBackgroundResource(R.drawable.bg_status_badge_red)
            binding.tvShizukuPermission.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark))
            binding.btnRequestPermission.isEnabled = isPingSuccess
            binding.btnRequestPermission.text = "Minta Izin Shizuku"
        }

        Logger.log(
            "Status",
            "Shizuku: ${if (isPingSuccess) "Berjalan" else "Mati"} | Izin: ${if (hasPermission) "Diberikan" else "Belum"}"
        )
    }

    private fun requestShizukuPermission() {
        try {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "Layanan Shizuku tidak terdeteksi berjalan!", Toast.LENGTH_LONG).show()
                Logger.log("Shizuku", "Permintaan izin dibatalkan: Shizuku tidak berjalan.")
                return
            }

            Shizuku.requestPermission(SHIZUKU_REQ_CODE)
            Logger.log("Shizuku", "Meminta izin Shizuku (requestCode=$SHIZUKU_REQ_CODE)...")
        } catch (t: Throwable) {
            Logger.log("Shizuku", "Gagal meminta izin: ${t.message}")
            Toast.makeText(this, "Gagal meminta izin: ${t.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // ==========================================
    // Device Info & Coordinate Setup
    // ==========================================

    private fun setupDeviceInfo() {
        val dm = resources.displayMetrics
        val widthPx = dm.widthPixels
        val heightPx = dm.heightPixels
        val density = dm.density
        val densityDpi = dm.densityDpi
        val widthDp = (widthPx / density).toInt()
        val heightDp = (heightPx / density).toInt()

        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }
        val rotationStr = when (rotation) {
            Surface.ROTATION_0 -> "0° (Tegak)"
            Surface.ROTATION_90 -> "90° (Lanskap)"
            Surface.ROTATION_180 -> "180°"
            Surface.ROTATION_270 -> "270° (Lanskap Terbalik)"
            else -> "$rotation"
        }

        val androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
        val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}"

        val infoText = "Layar: ${widthPx}x${heightPx} px (${widthDp}x${heightDp} dp)\n" +
                "Density: ${densityDpi} dpi (x$density) | Rotasi: $rotationStr\n" +
                "Perangkat: $deviceModel | $androidVersion"

        binding.tvDeviceInfo.text = infoText
        Logger.log("DeviceInfo", "Ukuran Layar: ${widthPx}x${heightPx} px | Density: ${densityDpi} dpi | Rotasi: $rotationStr")
        Logger.log("DeviceInfo", "Sistem: $androidVersion | Perangkat: $deviceModel")
    }

    private fun setupCoordinateInputs() {
        val dm = resources.displayMetrics
        val centerX = dm.widthPixels / 2
        val centerY = dm.heightPixels / 2

        binding.etCoordX.setText(centerX.toString())
        binding.etCoordY.setText(centerY.toString())

        binding.btnResetCenter.setOnClickListener {
            val metrics = resources.displayMetrics
            binding.etCoordX.setText((metrics.widthPixels / 2).toString())
            binding.etCoordY.setText((metrics.heightPixels / 2).toString())
            Toast.makeText(this, "Koordinat direset ke tengah (${metrics.widthPixels / 2}, ${metrics.heightPixels / 2})", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getTargetCoordinates(): Pair<Float, Float>? {
        val xStr = binding.etCoordX.text.toString().trim()
        val yStr = binding.etCoordY.text.toString().trim()
        val x = xStr.toFloatOrNull()
        val y = yStr.toFloatOrNull()
        if (x == null || y == null) {
            Toast.makeText(this, "Koordinat X atau Y tidak valid!", Toast.LENGTH_SHORT).show()
            return null
        }
        return Pair(x, y)
    }

    // ==========================================
    // BAGIAN 3: Countdown & Test Buttons
    // ==========================================

    private fun setupTestButtons() {
        binding.btnCancelCountdown.setOnClickListener {
            cancelCountdown("Dibatalkan pengguna")
        }

        binding.btnTestHover.setOnClickListener {
            val coords = getTargetCoordinates() ?: return@setOnClickListener
            startCountdown("Hover Mouse (400px)") {
                executeMouseHover(coords.first, coords.second)
            }
        }

        binding.btnTestLeftClick.setOnClickListener {
            val coords = getTargetCoordinates() ?: return@setOnClickListener
            startCountdown("Klik Kiri Mouse") {
                executeMouseLeftClick(coords.first, coords.second)
            }
        }

        binding.btnTestRightClick.setOnClickListener {
            val coords = getTargetCoordinates() ?: return@setOnClickListener
            startCountdown("Klik Kanan Mouse") {
                executeMouseRightClick(coords.first, coords.second)
            }
        }

        binding.btnTestScrollUp.setOnClickListener {
            val coords = getTargetCoordinates() ?: return@setOnClickListener
            startCountdown("Scroll Mouse Ke Atas (+)") {
                executeMouseScroll(coords.first, coords.second, 1.0f)
            }
        }

        binding.btnTestScrollDown.setOnClickListener {
            val coords = getTargetCoordinates() ?: return@setOnClickListener
            startCountdown("Scroll Mouse Ke Bawah (-)") {
                executeMouseScroll(coords.first, coords.second, -1.0f)
            }
        }

        binding.btnTestTouchTap.setOnClickListener {
            val coords = getTargetCoordinates() ?: return@setOnClickListener
            startCountdown("Tap Jari Touchscreen") {
                executeTouchTap(coords.first, coords.second)
            }
        }

        binding.btnTestTouchLongPress.setOnClickListener {
            val coords = getTargetCoordinates() ?: return@setOnClickListener
            startCountdown("Long Press Jari (600ms)") {
                executeTouchLongPress(coords.first, coords.second)
            }
        }

        binding.btnTestOverlay.setOnClickListener {
            toggleOverlay()
        }
    }

    private fun startCountdown(testName: String, action: suspend () -> Unit) {
        countdownJob?.cancel()

        binding.cardCountdown.visibility = View.VISIBLE
        Logger.log("Countdown", "Memulai hitung mundur 5 detik untuk: $testName")

        countdownJob = lifecycleScope.launch {
            try {
                for (sec in 5 downTo 1) {
                    binding.tvCountdown.text = "Menyuntik [$testName] dalam $sec detik... Beralih ke aplikasi target!"
                    Toast.makeText(
                        applicationContext,
                        "PointerTest: [$testName] dalam $sec detik...",
                        Toast.LENGTH_SHORT
                    ).show()
                    delay(1000L)
                }

                binding.tvCountdown.text = "Menyuntikkan [$testName] sekarang!"
                Toast.makeText(applicationContext, "PointerTest: Menyuntikkan [$testName] sekarang!", Toast.LENGTH_SHORT).show()
                delay(200L)

                binding.cardCountdown.visibility = View.GONE
                Logger.log("TestExec", "Mengeksekusi suntikan: $testName")

                withContext(Dispatchers.IO) {
                    action()
                }

                Toast.makeText(applicationContext, "PointerTest: Selesai [$testName]", Toast.LENGTH_SHORT).show()
            } catch (e: CancellationException) {
                Logger.log("Countdown", "Hitung mundur [$testName] dibatalkan.")
            } catch (t: Throwable) {
                Logger.log("TestExec", "Error eksekusi [$testName]: ${t.message}")
            } finally {
                binding.cardCountdown.visibility = View.GONE
            }
        }
    }

    private fun cancelCountdown(reason: String) {
        countdownJob?.cancel()
        binding.cardCountdown.visibility = View.GONE
        Logger.log("Countdown", "Hitung mundur dihentikan: $reason")
        Toast.makeText(this, "Hitung mundur dibatalkan", Toast.LENGTH_SHORT).show()
    }

    // ==========================================
    // Test Implementations
    // ==========================================

    /**
     * 1. Hover mouse: suntik ACTION_HOVER_MOVE dengan source SOURCE_MOUSE dan tool type TOOL_TYPE_MOUSE,
     * bergerak halus dari titik X,Y sejauh sekitar 400 px ke kanan selama 2 detik.
     */
    private suspend fun executeMouseHover(startX: Float, startY: Float) {
        val totalDistance = 400f
        val durationMs = 2000L
        val steps = 80 // 80 langkah x 25ms = 2000ms
        val stepInterval = durationMs / steps
        val stepDx = totalDistance / steps

        Logger.log("Hover", "Mulai hover mouse dari ($startX, $startY) sejauh +400px selama 2 detik")

        // Hover enter
        val enterEvent = EventInjector.createMouseHoverEvent(MotionEvent.ACTION_HOVER_ENTER, startX, startY)
        EventInjector.injectEvent(enterEvent)
        enterEvent.recycle()

        for (i in 0..steps) {
            val curX = startX + (i * stepDx)
            val moveEvent = EventInjector.createMouseHoverEvent(MotionEvent.ACTION_HOVER_MOVE, curX, startY)
            val result = EventInjector.injectEvent(moveEvent)
            moveEvent.recycle()
            if (!result.success && i == 0) {
                Logger.log("Hover", "Gagal pada suntikan pertama hover: ${result.details}")
            }
            delay(stepInterval)
        }

        // Hover exit
        val exitEvent = EventInjector.createMouseHoverEvent(MotionEvent.ACTION_HOVER_EXIT, startX + totalDistance, startY)
        EventInjector.injectEvent(exitEvent)
        exitEvent.recycle()

        Logger.log("Hover", "Selesai hover mouse sejauh 400px (${steps} frame)")
    }

    /**
     * 2. Klik kiri mouse: ACTION_DOWN lalu ACTION_UP dengan BUTTON_PRIMARY, source mouse, di X,Y.
     */
    private suspend fun executeMouseLeftClick(x: Float, y: Float) {
        Logger.log("LeftClick", "Menyuntik klik kiri mouse di ($x, $y)")

        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent = EventInjector.createMouseButtonEvent(
            action = MotionEvent.ACTION_DOWN,
            x = x,
            y = y,
            buttonState = MotionEvent.BUTTON_PRIMARY,
            actionButton = MotionEvent.BUTTON_PRIMARY,
            downTime = downTime,
            eventTime = downTime
        )
        val resDown = EventInjector.injectEvent(downEvent)
        downEvent.recycle()

        delay(60L)

        val upTime = android.os.SystemClock.uptimeMillis()
        val upEvent = EventInjector.createMouseButtonEvent(
            action = MotionEvent.ACTION_UP,
            x = x,
            y = y,
            buttonState = 0,
            actionButton = 0,
            downTime = downTime,
            eventTime = upTime
        )
        val resUp = EventInjector.injectEvent(upEvent)
        upEvent.recycle()

        Logger.log("LeftClick", "Hasil Klik Kiri: Down=${resDown.success}, Up=${resUp.success}")
    }

    /**
     * 3. Klik kanan mouse: sama, dengan BUTTON_SECONDARY (sertakan ACTION_BUTTON_PRESS/RELEASE).
     */
    private suspend fun executeMouseRightClick(x: Float, y: Float) {
        Logger.log("RightClick", "Menyuntik klik kanan mouse di ($x, $y)")

        val downTime = android.os.SystemClock.uptimeMillis()

        // 1. ACTION_DOWN dengan BUTTON_SECONDARY
        val downEvent = EventInjector.createMouseButtonEvent(
            action = MotionEvent.ACTION_DOWN,
            x = x,
            y = y,
            buttonState = MotionEvent.BUTTON_SECONDARY,
            actionButton = MotionEvent.BUTTON_SECONDARY,
            downTime = downTime,
            eventTime = downTime
        )
        EventInjector.injectEvent(downEvent)
        downEvent.recycle()

        // 2. ACTION_BUTTON_PRESS dengan BUTTON_SECONDARY
        val pressEvent = EventInjector.createMouseButtonEvent(
            action = MotionEvent.ACTION_BUTTON_PRESS,
            x = x,
            y = y,
            buttonState = MotionEvent.BUTTON_SECONDARY,
            actionButton = MotionEvent.BUTTON_SECONDARY,
            downTime = downTime,
            eventTime = downTime + 10
        )
        EventInjector.injectEvent(pressEvent)
        pressEvent.recycle()

        delay(60L)

        val upTime = android.os.SystemClock.uptimeMillis()

        // 3. ACTION_BUTTON_RELEASE dengan BUTTON_SECONDARY
        val releaseEvent = EventInjector.createMouseButtonEvent(
            action = MotionEvent.ACTION_BUTTON_RELEASE,
            x = x,
            y = y,
            buttonState = 0,
            actionButton = MotionEvent.BUTTON_SECONDARY,
            downTime = downTime,
            eventTime = upTime - 5
        )
        EventInjector.injectEvent(releaseEvent)
        releaseEvent.recycle()

        // 4. ACTION_UP
        val upEvent = EventInjector.createMouseButtonEvent(
            action = MotionEvent.ACTION_UP,
            x = x,
            y = y,
            buttonState = 0,
            actionButton = 0,
            downTime = downTime,
            eventTime = upTime
        )
        val resUp = EventInjector.injectEvent(upEvent)
        upEvent.recycle()

        Logger.log("RightClick", "Hasil Klik Kanan lengkap selesai: Up=${resUp.success}")
    }

    /**
     * 4. Scroll mouse: ACTION_SCROLL dengan AXIS_VSCROLL (nilai positif dan negatif sebagai dua tombol).
     */
    private fun executeMouseScroll(x: Float, y: Float, scrollValue: Float) {
        Logger.log("Scroll", "Menyuntik scroll mouse di ($x, $y) dengan nilai AXIS_VSCROLL = $scrollValue")
        val scrollEvent = EventInjector.createMouseScrollEvent(x, y, scrollValue)
        val res = EventInjector.injectEvent(scrollEvent)
        scrollEvent.recycle()
        Logger.log("Scroll", "Hasil Scroll (VScroll=$scrollValue): ${res.details}")
    }

    /**
     * 5. Tap jari: ACTION_DOWN/UP dengan SOURCE_TOUCHSCREEN di X,Y (pembanding).
     */
    private suspend fun executeTouchTap(x: Float, y: Float) {
        Logger.log("TouchTap", "Menyuntik tap jari di ($x, $y)")

        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent = EventInjector.createTouchEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        val resDown = EventInjector.injectEvent(downEvent)
        downEvent.recycle()

        delay(60L)

        val upTime = android.os.SystemClock.uptimeMillis()
        val upEvent = EventInjector.createTouchEvent(MotionEvent.ACTION_UP, x, y, downTime, upTime)
        val resUp = EventInjector.injectEvent(upEvent)
        upEvent.recycle()

        Logger.log("TouchTap", "Hasil Tap Jari: Down=${resDown.success}, Up=${resUp.success}")
    }

    /**
     * 6. Long press jari: tahan 600 ms lalu lepas, source touchscreen.
     */
    private suspend fun executeTouchLongPress(x: Float, y: Float) {
        Logger.log("LongPress", "Menyuntik long press jari di ($x, $y) selama 600ms")

        val downTime = android.os.SystemClock.uptimeMillis()
        val downEvent = EventInjector.createTouchEvent(MotionEvent.ACTION_DOWN, x, y, downTime, downTime)
        val resDown = EventInjector.injectEvent(downEvent)
        downEvent.recycle()

        delay(600L)

        val upTime = android.os.SystemClock.uptimeMillis()
        val upEvent = EventInjector.createTouchEvent(MotionEvent.ACTION_UP, x, y, downTime, upTime)
        val resUp = EventInjector.injectEvent(upEvent)
        upEvent.recycle()

        Logger.log("LongPress", "Hasil Long Press Jari: Down=${resDown.success}, Up=${resUp.success}")
    }

    /**
     * 7. Overlay tes: tampilkan/sembunyikan jendela overlay layar penuh transparan
     * (TYPE_APPLICATION_OVERLAY, FLAG_NOT_FOCUSABLE) yang mencatat setiap sentuhan ke log.
     * Minta izin "tampil di atas aplikasi lain" jika belum ada.
     */
    private fun toggleOverlay() {
        if (OverlayManager.isOverlayShowing) {
            OverlayManager.hideOverlay()
            updateOverlayButtonText()
            Toast.makeText(this, "Overlay ditutup", Toast.LENGTH_SHORT).show()
        } else {
            if (!OverlayManager.canDrawOverlays(this)) {
                Logger.log("Overlay", "Membuka halaman pengaturan izin tampil di atas aplikasi lain...")
                Toast.makeText(this, "Mohon berikan izin 'Tampil di atas aplikasi lain'", Toast.LENGTH_LONG).show()
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                overlayPermissionLauncher.launch(intent)
            } else {
                OverlayManager.showOverlay(this)
                updateOverlayButtonText()
                Toast.makeText(this, "Overlay aktif! Sentuhan & IME akan dicatat.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun setupOverlayCallback() {
        OverlayManager.onOverlayStateChanged = { isShowing ->
            runOnUiThread {
                updateOverlayButtonText()
            }
        }
    }

    private fun updateOverlayButtonText() {
        if (OverlayManager.isOverlayShowing) {
            binding.btnTestOverlay.text = "7. Sembunyikan Overlay Tes"
        } else {
            binding.btnTestOverlay.text = "7. Buka Overlay Tes"
        }
    }

    // ==========================================
    // Log Console Setup
    // ==========================================

    private fun setupLogConsole() {
        Logger.setListener { logs ->
            binding.tvLogContent.text = logs.joinToString("\n")
            binding.scrollLog.post {
                binding.scrollLog.fullScroll(View.FOCUS_DOWN)
            }
        }

        binding.btnCopyLog.setOnClickListener {
            val logs = Logger.getLogs()
            if (logs.isBlank()) {
                Toast.makeText(this, "Log masih kosong", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("PointerTest_Logs", logs)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Log berhasil disalin ke papan klip!", Toast.LENGTH_SHORT).show()
            Logger.log("Console", "Semua baris log berhasil disalin ke clipboard.")
        }

        binding.btnClearLog.setOnClickListener {
            Logger.clear()
            Toast.makeText(this, "Log dibersihkan", Toast.LENGTH_SHORT).show()
        }
    }
}
