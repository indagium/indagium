package com.indagium

import com.indagium.capture.CaptureExportResult
import com.indagium.ui.CaptureExportStatusView
import com.indagium.ui.CaptureExportWarning
import com.indagium.ui.captureExportStatusFor
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class CaptureExportStatusTest {
    private val result = CaptureExportResult(File("saved.zip"), 10L, null, null, "Capture exported")
    private val hidden = CaptureExportStatusView(busy = false, result = null, error = null)

    @Test
    fun owningSessionSeesBusyResultAndError() {
        assertEquals(
            CaptureExportStatusView(busy = true, result = null, error = null),
            captureExportStatusFor("s1", "s1", busy = true, result = null, error = null),
        )
        assertEquals(
            CaptureExportStatusView(busy = false, result = result, error = null),
            captureExportStatusFor("s1", "s1", busy = false, result = result, error = null),
        )
        assertEquals(
            CaptureExportStatusView(busy = false, result = null, error = "disk full"),
            captureExportStatusFor("s1", "s1", busy = false, result = null, error = "disk full"),
        )
    }

    @Test
    fun warningIsScopedToItsOwnerAndIsNotAnError() {
        val warning = CaptureExportWarning("Already saved: saved.zip", File("saved.zip"))
        assertEquals(
            CaptureExportStatusView(busy = false, result = null, error = null, warning = warning),
            captureExportStatusFor("s1", "s1", busy = false, result = null, error = null, warning = warning),
        )
        assertEquals(hidden, captureExportStatusFor("s2", "s1", busy = false, result = null, error = null, warning = warning))
    }

    @Test
    fun otherSessionsSeeNothing() {
        assertEquals(hidden, captureExportStatusFor("s2", "s1", busy = true, result = result, error = "boom"))
    }

    @Test
    fun snapshotPopoverOwnsOnlyNullOwnerExports() {
        // The popover passes a null session id: it shows live-snapshot runs (null owner)...
        assertEquals(
            CaptureExportStatusView(busy = true, result = result, error = null),
            captureExportStatusFor(null, null, busy = true, result = result, error = null),
        )
        // ...but not a retained-session save, and a stopped tab's strip does not show the snapshot's.
        assertEquals(hidden, captureExportStatusFor(null, "s1", busy = true, result = result, error = null))
        assertEquals(hidden, captureExportStatusFor("s1", null, busy = true, result = result, error = null))
    }
}
