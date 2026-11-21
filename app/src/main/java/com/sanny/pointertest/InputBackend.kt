package com.sanny.pointertest

/**
 * Abstraksi backend penyuntik event sentuhan untuk mode biasa.
 * Semua fungsi aman dipanggil secara berurutan dari satu coroutine terserialkan.
 */
interface InputBackend {
    val name: String
    fun isAvailable(): Boolean
    suspend fun tap(x: Float, y: Float)
    suspend fun holdDown(x: Float, y: Float)
    suspend fun holdMove(x: Float, y: Float)
    suspend fun holdUp(x: Float, y: Float)
}
