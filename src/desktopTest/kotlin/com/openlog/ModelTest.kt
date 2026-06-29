package com.openlog

import com.openlog.model.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    @Test
    fun logLevelFromValidCharsAllSix() {
        assertEquals(LogLevel.V, LogLevel.from('V'))
        assertEquals(LogLevel.D, LogLevel.from('D'))
        assertEquals(LogLevel.I, LogLevel.from('I'))
        assertEquals(LogLevel.W, LogLevel.from('W'))
        assertEquals(LogLevel.E, LogLevel.from('E'))
        assertEquals(LogLevel.A, LogLevel.from('A'))
    }

    @Test
    fun logLevelFromInvalidCharDefaultsToV() {
        assertEquals(LogLevel.V, LogLevel.from('Z'))
        assertEquals(LogLevel.V, LogLevel.from('0'))
        assertEquals(LogLevel.V, LogLevel.from(' '))
    }
}
