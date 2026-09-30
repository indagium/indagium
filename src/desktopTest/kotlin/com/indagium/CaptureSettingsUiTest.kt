package com.indagium

import com.indagium.capture.CaptureBufferMode
import com.indagium.capture.CaptureSettings
import com.indagium.ui.invalidCaptureSettings
import com.indagium.ui.visibleCaptureBuffers
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CaptureSettingsUiTest {
    @Test
    fun defaultSettingsAreValidAndHideConfiguredBuffers() {
        val settings = CaptureSettings()

        assertTrue(invalidCaptureSettings(settings).isEmpty())
        assertEquals(emptyList(), visibleCaptureBuffers(settings))
    }

    @Test
    fun customModeShowsOnlyDistinctConfiguredBuffers() {
        val settings = CaptureSettings(bufferMode = CaptureBufferMode.CUSTOM, buffers = listOf("main", "main", "radio"))

        assertEquals(listOf("main", "main", "radio"), visibleCaptureBuffers(settings))
        assertTrue(invalidCaptureSettings(settings).isEmpty())
    }

    @Test
    fun emptyCustomModeIsInvalidButEmptyDefaultModeIsValid() {
        val empty = CaptureSettings(bufferMode = CaptureBufferMode.CUSTOM, buffers = emptyList())
        val default = empty.copy(bufferMode = CaptureBufferMode.DEFAULT)

        assertTrue("buffers" in invalidCaptureSettings(empty))
        assertTrue("buffers" !in invalidCaptureSettings(default))
    }

    @Test
    fun invalidNumericAndTemplateFieldsAreReported() {
        val settings = CaptureSettings(maxFps = 0, bitrateMbps = 0, sessionLimitBytes = 0, filenameTemplate = "{unknown}")

        assertEquals(setOf("maxFps", "bitrateMbps", "sessionLimit", "filenameTemplate"), invalidCaptureSettings(settings))
    }
}
