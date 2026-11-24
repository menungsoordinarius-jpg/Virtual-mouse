package com.sanny.pointertest

/**
 * Objek Config terpusat untuk parameter pergerakan, akselerasi, dan gestur pointer.
 * Sesuai instruksi:
 * - Sensitivitas dasar: 1.0
 * - v0: ambang batas kecepatan jari (px per ms)
 * - k: koefisien kenaikan linear akselerasi
 * - gainMax: gain maksimum (3.5)
 * - tapMaxDurationMs: 200 ms
 * - tapSlopPx: 10 px
 * - holdThresholdMs: 300 ms
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

    /** Durasi maksimum sentuhan agar dianggap Tap (ms) */
    const val tapMaxDurationMs: Long = 200L

    /** Jarak gerak maksimum (px) untuk Tap maupun syarat diam sebelum HOLD */
    const val tapSlopPx: Float = 10f

    /** Durasi jari diam (ms) untuk masuk status HOLD */
    const val holdThresholdMs: Long = 300L

    /** Jendela waktu ketukan kedua untuk double tap / klik kanan (ms) */
    const val doubleTapWindowMs: Long = 250L

    /** Jarak geser vertikal (px) per 1 notch scroll */
    const val scrollPxPerNotch: Float = 40f

    /** Arah scroll natural (true: jari naik = konten naik) */
    var naturalScroll: Boolean = true

    /** Gain maksimum akselerasi scroll dua jari */
    var scrollGainMax: Float = 3.0f

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

    /**
     * Menghitung faktor pengali scroll dua jari (gain 1.0 sampai scrollGainMax 3.0).
     */
    fun calculateScrollGain(speedPxPerMs: Float): Float {
        return if (speedPxPerMs <= v0) {
            baseSensitivity
        } else {
            val accelerated = baseSensitivity + k * (speedPxPerMs - v0)
            accelerated.coerceAtMost(scrollGainMax)
        }
    }
}
