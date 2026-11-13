package com.sanny.pointertest

/**
 * Objek Config terpusat untuk parameter pergerakan dan akselerasi pointer.
 * Sesuai instruksi:
 * - Sensitivitas dasar: 1.0
 * - v0: ambang batas kecepatan jari (px per ms)
 * - k: koefisien kenaikan linear akselerasi
 * - gainMax: gain maksimum (3.5)
 */
object PointerConfig {
    /** Sensitivitas dasar (skala pengali pergerakan saat di bawah ambang kecepatan) */
    var baseSensitivity: Float = 1.0f

    /** Ambang kecepatan jari (px per ms). Di bawah v0, gain = 1.0 (baseSensitivity) */
    var v0: Float = 0.25f

    /** Kemiringan akselerasi: gain = baseSensitivity + k * (speed - v0) */
    var k: Float = 1.5f

    /** Gain maksimum akselerasi */
    var gainMax: Float = 3.5f

    /**
     * Menghitung faktor pengali pergerakan (gain) berdasarkan kecepatan sentuhan jari (px/ms).
     * Jika speed <= v0, gain = baseSensitivity (1.0).
     * Jika speed > v0, gain = baseSensitivity + k * (speed - v0), dibatasi maksimal gainMax (3.5).
     */
    fun calculateGain(speedPxPerMs: Float): Float {
        return if (speedPxPerMs <= v0) {
            baseSensitivity
        } else {
            val accelerated = baseSensitivity + k * (speedPxPerMs - v0)
            accelerated.coerceAtMost(gainMax)
        }
    }
}
