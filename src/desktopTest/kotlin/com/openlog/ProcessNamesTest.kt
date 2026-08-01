package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.model.ProcessNameMode
import com.openlog.utils.computeProcessNames
import com.openlog.utils.resolveProcessDisplayName
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProcessNamesTest {
    @Test
    fun activityManagerStartProcLineYieldsThePidAndProcessName() {
        val entries = listOf(
            LogEntry(
                1, "10:00:00.000", LogLevel.I, "ActivityManager",
                "Start proc 12345:com.example.app/u0a123 for activity com.example.app/.MainActivity",
            ),
        )

        val names = computeProcessNames(entries)

        assertEquals(mapOf(12345 to "com.example.app"), names)
    }

    @Test
    fun activityManagerStartProcLineWithASecondaryProcessSuffixKeepsTheColon() {
        val entries = listOf(
            LogEntry(
                1, "10:00:00.000", LogLevel.I, "ActivityManager",
                "Start proc 555:com.example.app:remote/u0a123 for service com.example.app/.MyService",
            ),
        )

        val names = computeProcessNames(entries)

        assertEquals(mapOf(555 to "com.example.app:remote"), names)
    }

    @Test
    fun amProcStartEventBufferLineYieldsThePidAndProcessName() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "am_proc_start", "[0,7777,10123,com.example.other,activity,com.example.other/.MainActivity]"),
        )

        val names = computeProcessNames(entries)

        assertEquals(mapOf(7777 to "com.example.other"), names)
    }

    @Test
    fun anActivityManagerLineThatIsNotAProcStartDoesNotMatch() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "Killing 12345:com.example.app/u0a123 (adj 906): empty for 1800s"),
        )

        val names = computeProcessNames(entries)

        assertEquals(emptyMap(), names)
    }

    @Test
    fun aProcStartLookingMessageUnderADifferentTagDoesNotMatch() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "MyApp", "Start proc 12345:com.example.app/u0a123 for activity com.example.app/.MainActivity"),
        )

        val names = computeProcessNames(entries)

        assertEquals(emptyMap(), names)
    }

    @Test
    fun theOlderPidLessBroadcastStartProcShapeIsDeliberatelySkipped() {
        // "Start proc <name> for broadcast <name>/<component>" has no pid anywhere in the line —
        // PROC_START_MSG_RE's leading "Start proc <digits>:" requirement excludes it without any
        // special-casing (see utils/ProcessNames.kt's own doc).
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "Start proc com.example.app for broadcast com.example.app/.MyReceiver"),
        )

        val names = computeProcessNames(entries)

        assertEquals(emptyMap(), names)
    }

    @Test
    fun anEmptyLogYieldsAnEmptyMap() {
        val names = computeProcessNames(emptyList())

        assertEquals(emptyMap(), names)
    }

    @Test
    fun aLogWithNoProcStartLinesYieldsAnEmptyMap() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hello"),
            LogEntry(2, "10:00:00.001", LogLevel.W, "Binder", "some warning"),
        )

        val names = computeProcessNames(entries)

        assertEquals(emptyMap(), names)
    }

    @Test
    fun pidReuseFollowsLastWriterWins() {
        val entries = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "Start proc 100:com.example.first/u0a1 for activity com.example.first/.Main"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "App", "unrelated line in between"),
            LogEntry(3, "10:00:02.000", LogLevel.I, "ActivityManager", "Start proc 100:com.example.second/u0a2 for activity com.example.second/.Main"),
        )

        val names = computeProcessNames(entries)

        assertEquals(mapOf(100 to "com.example.second"), names)
    }

    // ── resolveProcessDisplayName: the PID cell's OFF/ALL/MANUAL decision ──────────────

    @Test
    fun offNeverShowsAResolvedNameEvenWhenOneIsKnown() {
        assertNull(resolveProcessDisplayName(ProcessNameMode.OFF, mapOf(1234 to "com.example.app"), setOf(1234), 1234))
    }

    @Test
    fun allShowsTheNameForAnyPidWithAKnownOne() {
        assertEquals(
            "com.example.app",
            resolveProcessDisplayName(ProcessNameMode.ALL, mapOf(1234 to "com.example.app"), emptySet(), 1234),
        )
    }

    @Test
    fun allFallsBackToNullWhenThisPidsNameIsntKnown() {
        assertNull(resolveProcessDisplayName(ProcessNameMode.ALL, mapOf(1234 to "com.example.app"), emptySet(), 5678))
    }

    @Test
    fun manualShowsTheNameOnlyForAPickedPid() {
        val processNames = mapOf(1234 to "com.example.app", 5678 to "com.example.other")

        assertEquals("com.example.app", resolveProcessDisplayName(ProcessNameMode.MANUAL, processNames, setOf(1234), 1234))
        assertNull(resolveProcessDisplayName(ProcessNameMode.MANUAL, processNames, setOf(1234), 5678))
    }

    @Test
    fun manualWithAnEmptyPickSetShowsNothingEvenWhenNamesAreKnown() {
        // The known consequence of pid instability across runs (LogTab.manualProcessNamePicks'
        // own doc): a restored MANUAL session starts with no picks, so every row falls back to the
        // bare number until the user picks again — same as if the mode were OFF.
        assertNull(resolveProcessDisplayName(ProcessNameMode.MANUAL, mapOf(1234 to "com.example.app"), emptySet(), 1234))
    }
}
