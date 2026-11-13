package com.sanny.pointertest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PointerTestUnitTest {

    @Test
    fun testLoggerOutput() {
        Logger.clear()
        Logger.log("TestTag", "Pesan uji kelayakan Shizuku")
        val logs = Logger.getLogs()
        assertTrue(logs.contains("[TestTag] Pesan uji kelayakan Shizuku"))
    }

    @Test
    fun testEventInjectorConstants() {
        assertEquals(0, EventInjector.INJECT_INPUT_EVENT_MODE_ASYNC)
    }

    @Test
    fun testInjectionResultModel() {
        val result = InjectionResult(success = true, details = "Test OK")
        assertTrue(result.success)
        assertEquals("Test OK", result.details)
    }

    @Test
    fun testPointerConfigAcceleration() {
        // Kecepatan di bawah v0 harus mengembalikan baseSensitivity (1.0)
        val lowSpeedGain = PointerConfig.calculateGain(0.1f)
        assertEquals(1.0f, lowSpeedGain, 0.001f)

        // Kecepatan sangat tinggi harus dibatasi ke gainMax (3.5)
        val highSpeedGain = PointerConfig.calculateGain(10.0f)
        assertEquals(3.5f, highSpeedGain, 0.001f)
    }

    @Test
    fun testPointerEngineScreenBounds() {
        val engine = PointerEngine()
        engine.initializePosition(1080, 2400)
        assertEquals(540f, engine.pointerX, 0.001f)
        assertEquals(1200f, engine.pointerY, 0.001f)

        engine.onScreenSizeChanged(800, 1200)
        assertTrue(engine.pointerX <= 799f)
        assertTrue(engine.pointerY <= 1199f)
    }
}
