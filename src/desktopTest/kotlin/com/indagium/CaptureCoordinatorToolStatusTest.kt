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
