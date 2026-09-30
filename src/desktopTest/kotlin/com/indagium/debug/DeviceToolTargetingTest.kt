package com.indagium.debug

import com.indagium.capture.DeviceLogRetryableChange
import com.indagium.capture.LogBufferSizeChoice
import com.indagium.capture.LogTagLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure pieces behind the device tools' targeting (C1) and set_device_log_settings validation (C2). */
class DeviceToolTargetingTest {
    @Test
    fun effectiveDeviceSerialPrefersTheOneGivenAndAcceptsAgreeingValues() {
        assertNull(effectiveDeviceSerial(null, null))
        assertNull(effectiveDeviceSerial("  ", ""))
        assertEquals("SERIAL-A", effectiveDeviceSerial("SERIAL-A", null))
        assertEquals("SERIAL-B", effectiveDeviceSerial(null, " SERIAL-B "))
        assertEquals("SERIAL-A", effectiveDeviceSerial("SERIAL-A", "SERIAL-A"))
    }

    @Test
    fun effectiveDeviceSerialRefusesATabAndASerialThatNameDifferentDevices() {
        val failure = assertFailsWith<IllegalArgumentException> { effectiveDeviceSerial("SERIAL-A", "SERIAL-B") }
        assertEquals("tabId's device SERIAL-A does not match deviceSerial SERIAL-B", failure.message)
    }

    @Test
    fun parseDeviceLogChangesReturnsBothChangesInApplyOrder() {
        assertEquals(
            listOf(
                DeviceLogRetryableChange.BufferSize(LogBufferSizeChoice.SIZE_4M),
                DeviceLogRetryableChange.GlobalLevel(LogTagLevel.selectable.first { it.propValue == "W" }),
            ),
            parseDeviceLogChanges("4m", "w"),
        )
        assertEquals(listOf(DeviceLogRetryableChange.GlobalLevel(null)), parseDeviceLogChanges(null, "default"))
    }

    @Test
    fun anInvalidLogLevelFailsTheWholeRequestBeforeAnyChangeCanBeApplied() {
        // A valid bufferSize next to a bad logLevel must yield no change list at all, so the route
        // never reaches the first applyDeviceLogChangeNow.
        val failure = assertFailsWith<IllegalStateException> { parseDeviceLogChanges("4M", "LOUD") }
        assertTrue(failure.message.orEmpty().startsWith("logLevel must be one of"))
        assertFailsWith<IllegalStateException> { parseDeviceLogChanges("3M", "W") }
        assertFailsWith<IllegalArgumentException> { parseDeviceLogChanges(null, null) }
    }
}
