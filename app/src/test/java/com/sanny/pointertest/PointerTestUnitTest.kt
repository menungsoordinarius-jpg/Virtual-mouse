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
}
