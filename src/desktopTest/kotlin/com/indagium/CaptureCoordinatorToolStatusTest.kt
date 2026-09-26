package com.indagium

import com.indagium.capture.CaptureToolValidation
import com.indagium.capture.DeviceLogActivity
import com.indagium.capture.DeviceLogRetryableChange
import com.indagium.capture.DeviceLogState
import com.indagium.capture.LogBufferSizeChoice
import com.indagium.ui.deviceLogFailedChangeState
import com.indagium.ui.deviceLogRootAttemptFailedState
import com.indagium.ui.toolStatusLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `CaptureCoordinator` itself (ui/CaptureCoordinator.kt) is not reasonably unit-testable in
 * isolation: it is constructed with a live `AppState` (a ~6k-line Compose state holder) and a
 * `CoroutineScope`, and its `init` block immediately launches coroutines against a real
 * `CaptureRecorder`. Rather than contort a harness around that, the B2 fix (see CLAUDE.md's
 * commit-message-ready description of the bug) was extracted into `toolStatusLine`, a small pure
 * function that can be tested directly.
 */
class CaptureCoordinatorToolStatusTest {
    @Test
    fun neverHidesTheFailureMessageBehindANonNullVersion() {
        // Before B2: `"${adb.version ?: adb.message}"`. When validation fails AFTER the version
        // string was already captured, `version` is still non-null, so that expression showed a
        // green-looking version line and hid the real failure reason -- the user just saw
        // "No devices discovered" with no clue why.
        val failedAfterVersion = CaptureToolValidation(
            available = false,
            version = "Android Debug Bridge version 1.0.41",
            message = "adb capability check failed: permission denied",
        )
        assertEquals("adb capability check failed: permission denied", toolStatusLine(failedAfterVersion))
    }

    @Test
    fun showsTheVersionOnSuccess() {
        val succeeded = CaptureToolValidation(
            available = true,
            version = "Android Debug Bridge version 1.0.41",
            message = "adb is ready",
        )
        assertEquals("Android Debug Bridge version 1.0.41", toolStatusLine(succeeded))
    }

    @Test
    fun showsTheMessageWhenNoVersionWasEverCaptured() {
        val failedBeforeVersion = CaptureToolValidation(available = false, version = null, message = "adb version check timed out")
        assertEquals("adb version check timed out", toolStatusLine(failedBeforeVersion))
    }
}

/** [com.indagium.ui.adbFailureMessage] is the device-logging panel's equivalent of
 *  CaptureTools.kt's own private `boundedDiagnostic` — same "prefer stderr, fall back to stdout,
 *  bound the length" contract, pulled out as a pure function for the same reason toolStatusLine was
 *  (see this file's own top-level doc). */
class AdbFailureMessageTest {
    @Test
    fun prefersStderrOverStdoutWhenBothArePresent() {
        val result = com.indagium.capture.CaptureCommandResult(
            exitCode = 1,
            stdout = "some stdout noise".toByteArray(),
            stderr = "Permission denied".toByteArray(),
        )
        assertEquals("Could not set log level: Permission denied", com.indagium.ui.adbFailureMessage("Could not set log level", result))
    }

    @Test
    fun fallsBackToStdoutWhenStderrIsBlank() {
        val result = com.indagium.capture.CaptureCommandResult(exitCode = 1, stdout = "device unauthorized".toByteArray(), stderr = ByteArray(0))
        assertEquals("Could not read buffer sizes: device unauthorized", com.indagium.ui.adbFailureMessage("Could not read buffer sizes", result))
    }

    @Test
    fun fallsBackToTheExitCodeWhenThereIsNoOutputAtAll() {
        val result = com.indagium.capture.CaptureCommandResult(exitCode = 137, stdout = ByteArray(0), stderr = ByteArray(0))
        assertEquals("Could not set buffer size (exit 137)", com.indagium.ui.adbFailureMessage("Could not set buffer size", result))
    }
}

/** [deviceLogFailedChangeState]/[deviceLogRootAttemptFailedState] are the "Restart adb as root"
 *  recovery's own decision logic, pulled out of `CaptureService` for the same reason
 *  [toolStatusLine]/`adbFailureMessage` were (see this file's own top-level doc) — `CaptureService`
 *  itself needs a live `AppState` + `CoroutineScope` and a real, non-injectable `adb`, so its
 *  `applyDeviceLogChange`/`restartAdbAsRoot` flows aren't reasonably unit-testable end to end, but
 *  these two state transitions have none of that dependency. */
class DeviceLogRootRecoveryStateTest {
    private val base = DeviceLogState(serial = "SERIAL", loaded = true)

    @Test
    fun recordsTheFailedChangeOnlyForAFreshPermissionFailure() {
        val change = DeviceLogRetryableChange.BufferSize(LogBufferSizeChoice.SIZE_4M)

        val result = deviceLogFailedChangeState(base, change, "Could not set buffer size: Permission denied", permissionFailure = true)

        assertEquals(DeviceLogActivity.IDLE, result.activity)
        assertEquals("Could not set buffer size: Permission denied", result.error)
        assertEquals(change, result.failedChange)
    }

    @Test
    fun doesNotRecordAChangeForANonPermissionFailure() {
        val change = DeviceLogRetryableChange.GlobalLevel(null)

        val result = deviceLogFailedChangeState(base, change, "device offline", permissionFailure = false)

        assertNull(result.failedChange)
        assertEquals("device offline", result.error)
    }

    @Test
    fun neverOffersTheButtonAgainOnceThisSerialHasAlreadyHadARootAttempt() {
        val alreadyAttempted = base.copy(rootAttempted = true)
        val change = DeviceLogRetryableChange.ClearTagOverride("MyTag")

        val result = deviceLogFailedChangeState(alreadyAttempted, change, "Permission denied", permissionFailure = true)

        assertNull(result.failedChange)
        assertTrue(result.rootAttempted)
    }

    @Test
    fun rootAttemptFailedStateAlwaysClearsTheChangeAndMarksRootAttempted() {
        val pending = base.copy(failedChange = DeviceLogRetryableChange.GlobalLevel(null))

        val result = deviceLogRootAttemptFailedState(pending, "adbd cannot run as root in production builds")

        assertEquals(DeviceLogActivity.IDLE, result.activity)
        assertEquals("adbd cannot run as root in production builds", result.error)
        assertNull(result.failedChange)
        assertTrue(result.rootAttempted)
    }

    @Test
    fun rootAttemptFailedStatePreservesEverythingElseOnTheBaseState() {
        val loadedState = base.copy(perTagOverrides = mapOf("Foo" to "D"))

        val result = deviceLogRootAttemptFailedState(loadedState, "some failure")

        assertEquals(loadedState.perTagOverrides, result.perTagOverrides)
        assertTrue(result.loaded)
    }
}
