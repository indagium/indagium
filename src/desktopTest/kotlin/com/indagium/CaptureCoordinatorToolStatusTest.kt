package com.indagium

import com.indagium.capture.CaptureToolValidation
import com.indagium.ui.toolStatusLine
import kotlin.test.Test
import kotlin.test.assertEquals

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
