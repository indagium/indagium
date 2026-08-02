package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.computeLogFingerprint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Covers computeLogFingerprint (utils/LogFingerprint.kt) — the relink-log feature's Change 2a: a
 * cheap, save-time content fingerprint that lets "Locate log…" (AppState.locateLogForCase/
 * locateLogForTab) tell a renamed/moved copy of the SAME capture apart from a genuinely different
 * capture of the same bug, since AnnBlock.LogRef.logIds are positional per file.
 */
class LogFingerprintTest {
    private fun entries(count: Int, seed: String = "run-a"): List<LogEntry> =
        (1..count).map { i -> LogEntry(i, "10:00:00.$i", LogLevel.I, "App", "$seed line $i") }

    @Test
    fun identicalContentProducesTheSameFingerprint() {
        val a = entries(50)
        val b = entries(50)

        assertEquals(computeLogFingerprint(a), computeLogFingerprint(b))
    }

    @Test
    fun aFileDifferingOnlyInNameStillMatches() {
        // computeLogFingerprint only ever sees the parsed entries — the file's name/path never
        // enters the computation, so a plain rename is indistinguishable from the original.
        val original = entries(50)
        val renamedCopy = entries(50)

        assertEquals(computeLogFingerprint(original), computeLogFingerprint(renamedCopy))
    }

    @Test
    fun aGenuinelyDifferentCaptureOfTheSameBugDoesNotMatch() {
        val originalRun = entries(50, seed = "run-a")
        val laterRun = entries(50, seed = "run-b")

        assertNotEquals(computeLogFingerprint(originalRun), computeLogFingerprint(laterRun))
    }

    @Test
    fun differingOnlyInEntryCountDoesNotMatch() {
        val shorter = entries(50)
        val longer = entries(51)

        assertNotEquals(computeLogFingerprint(shorter), computeLogFingerprint(longer))
    }

    @Test
    fun differingOnlyInTheMiddleOfALargeFileStillMatches() {
        // Composition is total count + a hash of the first/last sampled entries — cheap by design
        // (O(sample size), not O(file size); see the function's own doc comment). A change ONLY in
        // the middle of a file larger than twice the sample is, by that design, invisible to this
        // fingerprint — documenting the trade-off explicitly rather than leaving it implicit.
        val base = entries(200)
        val editedMiddle = base.mapIndexed { idx, e -> if (idx == 100) e.copy(msg = "edited") else e }

        assertEquals(computeLogFingerprint(base), computeLogFingerprint(editedMiddle))
    }

    @Test
    fun aChangeNearTheStartOrEndOfALargeFileDoesNotMatch() {
        val base = entries(200)
        val editedNearStart = base.mapIndexed { idx, e -> if (idx == 5) e.copy(msg = "edited") else e }

        assertNotEquals(computeLogFingerprint(base), computeLogFingerprint(editedNearStart))
    }

    @Test
    fun emptyLogDataProducesTheEmptySentinelNotACrash() {
        assertEquals("", computeLogFingerprint(emptyList()))
    }

    @Test
    fun aSingleEntryLogStillProducesAStableNonBlankFingerprint() {
        val fp = computeLogFingerprint(entries(1))

        assertTrue(fp.isNotBlank())
        assertEquals(fp, computeLogFingerprint(entries(1)))
    }
}
