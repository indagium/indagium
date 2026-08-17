package com.indagium

import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.utils.computeStackTraceGroups
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StackTraceComputerTest {
    @Test
    fun foldsAFatalExceptionAndItsFrames() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "unrelated line", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
            LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:1)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(2), groups.map { it.rid })
        assertEquals(listOf(3, 4, 5), groups.single().memberIds)
    }

    @Test
    fun realAndroidRuntimeDumpWithProcessLineFoldsUnderFatalExceptionHeader() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "Process: com.example.app, PID: 100", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.example.app.MainActivity.onCreate(MainActivity.java:25)", pid = 100),
            LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:8000)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2, 3, 4, 5), groups.single().memberIds)
    }

    @Test
    fun genericClassExceptionHeaderTriggersWithoutFatalExceptionPrefix() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "MyTag", "java.io.IOException: disk full", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "MyTag", "    at com.app.Io.write(Io.java:5)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2), groups.single().memberIds)
    }

    @Test
    fun exceptionPreludeBecomesGroupRootWhenFollowedByExceptionHeader() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.W, "Binder", "heartbeat before", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.W, "Binder", "Caught a RuntimeException from the binder stub implementation.", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.W, "Binder", "java.lang.ArrayIndexOutOfBoundsException: length=2; index=3", pid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.W, "Binder", "    at com.android.server.BinderStub.call(BinderStub.java:10)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(2), groups.map { it.rid })
        assertEquals(listOf(3, 4), groups.single().memberIds)
    }

    @Test
    fun unrelatedWarningBeforeExceptionIsNotPromoted() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.W, "Binder", "slow binder transaction", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.W, "Binder", "java.lang.IllegalStateException: boom", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.W, "Binder", "    at com.android.server.BinderStub.call(BinderStub.java:10)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(2), groups.map { it.rid })
        assertEquals(listOf(3), groups.single().memberIds)
    }

    @Test
    fun fatalExceptionMatchIsCaseInsensitive() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "fatal exception: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2), groups.single().memberIds)
    }

    @Test
    fun causedByAndMoreFramesAreTreatedAsContinuation() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.RuntimeException: outer", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "Caused by: java.lang.NullPointerException", pid = 100),
            LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at com.app.Helper.get(Helper.java:20)", pid = 100),
            LogEntry(6, "10:00:00.500", LogLevel.E, "AndroidRuntime", "    ... 12 more", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2, 3, 4, 5, 6), groups.single().memberIds)
    }

    @Test
    fun nonContinuationSamePidLineBreaksTheGroup() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.I, "AndroidRuntime", "unrelated log line from same process", pid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Ignored.method(Ignored.java:1)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2), groups.single().memberIds)
    }

    @Test
    fun differentPidInterleavedLinesDoNotBreakTheGroup() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.I, "OtherProcess", "some totally unrelated line", pid = 200),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:1)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2, 4), groups.single().memberIds)
    }

    @Test
    fun backToBackSeparateExceptionsProduceTwoGroups() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.First.a(First.java:1)", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "java.lang.IllegalStateException: second", pid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Second.b(Second.java:2)", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1, 3), groups.map { it.rid })
        assertEquals(listOf(2), groups.first { it.rid == 1 }.memberIds)
        assertEquals(listOf(4), groups.first { it.rid == 3 }.memberIds)
    }

    @Test
    fun triggerWithNoFollowingContinuationProducesNoGroup() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "java.lang.RuntimeException: alone", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.I, "AndroidRuntime", "next unrelated line", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertTrue(groups.isEmpty())
    }

    @Test
    fun noExceptionPresentReturnsEmptyResult() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "hello", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.D, "Tag", "world", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertTrue(groups.isEmpty())
    }

    // ── Crash-signature capture ───────────────────────────────────────

    @Test
    fun signatureIsExtractedFromTheFollowUpClassNameLineNotTheFatalExceptionTriggerLine() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )

        val signature = computeStackTraceGroups(logs).single().signature

        // "FATAL EXCEPTION: main" itself never matches the class-name shape, so if the signature
        // were (wrongly) built from the trigger line alone it could never contain the class name.
        assertTrue(signature.contains("java.lang.NullPointerException"))
        assertTrue(signature.contains("com.app.Main.onCreate"))
    }

    @Test
    fun signaturePrefersTheFirstNonFrameworkFrameOverAnEarlierFrameworkFrame() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at java.lang.reflect.Method.invoke(Native Method)", pid = 100),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )

        val signature = computeStackTraceGroups(logs).single().signature

        assertTrue(signature.contains("com.app.Main.onCreate"))
        assertTrue(!signature.contains("java.lang.reflect.Method"))
    }

    @Test
    fun identicalRetriesFromDifferentPidsProduceTheSameSignature() {
        fun dumpFor(pid: Int) = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = pid),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = pid),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = pid),
        )

        val signatureA = computeStackTraceGroups(dumpFor(pid = 100)).single().signature
        val signatureB = computeStackTraceGroups(dumpFor(pid = 200)).single().signature

        // Chosen deliberately: a crash signature identifies the code location, not the process
        // instance that hit it, matching how crash-grouping tools (e.g. Crashlytics) group by
        // stack trace regardless of session/pid. See CrashPanelDetectionTest for the
        // computeCrashSites-level grouping test.
        assertEquals(signatureA, signatureB)
    }

    @Test
    fun aHeaderWithNoClassNameOrFrameGetsAUniquePerRidSignatureRatherThanCollidingWithAnother() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "Process: com.example.app, PID: 100", pid = 100),
            LogEntry(3, "10:00:01.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 200),
            LogEntry(4, "10:00:01.100", LogLevel.E, "AndroidRuntime", "Process: com.example.app, PID: 200", pid = 200),
        )

        val signatures = computeStackTraceGroups(logs).map { it.signature }

        assertEquals(2, signatures.toSet().size)
    }

    // ── Rules A/B/C (per-thread scoping, reach bound, unconditional-member requirement) ─────────

    @Test
    fun standaloneExceptionShapedWarningRepeatedWithNoRealFrameProducesNoGroup() {
        // The shape that motivated Rule C: a periodic one-line status warning that happens to match
        // the exception-header shape (EXCEPTION_HEADER_RE), repeated later by the same pid but
        // interleaved with other-pid lines and never followed by a real "at" frame. Rule C must
        // refuse to fold the repeats into a group — this is the same one-line message logged three
        // times, not a stack dump. Folding them hides the later repeats from their true position.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.W, "ClockSync", "java.time.DateTimeException: no time source", pid = 300, tid = 31),
            LogEntry(2, "10:00:00.100", LogLevel.I, "OtherTag", "unrelated line from a different process", pid = 900, tid = 1),
            LogEntry(3, "10:00:00.200", LogLevel.I, "OtherTag", "another unrelated line", pid = 901, tid = 1),
            LogEntry(4, "10:00:00.300", LogLevel.W, "ClockSync", "java.time.DateTimeException: no time source", pid = 300, tid = 31),
            LogEntry(5, "10:00:00.400", LogLevel.W, "ClockSync", "java.time.DateTimeException: no time source", pid = 300, tid = 32),
        )

        val groups = computeStackTraceGroups(logs)

        assertTrue(groups.isEmpty())
        val claimed = groups.flatMap { it.memberIds }.toSet()
        assertTrue((1..5).none { it in claimed })
    }

    @Test
    fun continuationFarPastMaxTraceInterleaveDoesNotExtendTheTrace() {
        // Rule B: an open trace's reach is capped at MAX_TRACE_INTERLEAVE (64) entries past its
        // last claimed line. The 65 filler lines below belong to a different pid, so they neither
        // extend nor break the open trace by themselves (Rule A) — but once the next same-pid frame
        // arrives 66 lines after the trace's last claimed member, it must NOT extend the trace.
        val filler = (1..65).map { n ->
            LogEntry(n + 2, "10:00:01.000", LogLevel.I, "OtherTag", "unrelated filler line $n", pid = 200, tid = 1)
        }
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100, tid = 1),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100, tid = 1),
        ) + filler + listOf(
            LogEntry(68, "10:00:02.000", LogLevel.E, "AndroidRuntime", "    at com.app.Second.method(Second.java:2)", pid = 100, tid = 1),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2), groups.single().memberIds)
        val claimed = groups.flatMap { it.memberIds }.toSet()
        assertTrue(68 !in claimed)
    }

    @Test
    fun interleavedDumpsFromSamePidButDifferentTidsAreGroupedSeparately() {
        // Rule A: a Java stack dump is written by one thread, so the open trace is scoped to
        // (pid, tid), not pid alone. Two threads of the SAME process dumping interleaved exceptions
        // must produce two independent groups, not one merged group with frames from both threads.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: thread-1", pid = 100, tid = 11),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: thread-2", pid = 100, tid = 22),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.One.a(One.java:1)", pid = 100, tid = 11),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at com.app.Two.b(Two.java:2)", pid = 100, tid = 22),
            LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "    at com.app.One.c(One.java:3)", pid = 100, tid = 11),
            LogEntry(6, "10:00:00.500", LogLevel.E, "AndroidRuntime", "    at com.app.Two.d(Two.java:4)", pid = 100, tid = 22),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1, 2), groups.map { it.rid }.sorted())
        assertEquals(listOf(3, 5), groups.first { it.rid == 1 }.memberIds)
        assertEquals(listOf(4, 6), groups.first { it.rid == 2 }.memberIds)
    }

    @Test
    fun dumpInterleavedWithOtherPidLinesIsStillGroupedCorrectlyUnderRulesAAndB() {
        // The pre-existing differentPidInterleavedLinesDoNotBreakTheGroup behaviour must survive
        // Rules A (pid+tid keying) and B (reach bound) — a foreign pid's lines pass through an open
        // trace untouched, well within the MAX_TRACE_INTERLEAVE bound.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100, tid = 1),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100, tid = 1),
            LogEntry(3, "10:00:00.200", LogLevel.I, "OtherProcess", "some totally unrelated line", pid = 200, tid = 1),
            LogEntry(4, "10:00:00.300", LogLevel.E, "AndroidRuntime", "    at android.app.Activity.performCreate(Activity.java:1)", pid = 100, tid = 1),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2, 4), groups.single().memberIds)
    }

    @Test
    fun traceWithOnlyAProcessLineMemberIsStillGrouped() {
        // Rule C's boundary: isUnconditionalContinuation accepts a bare "Process: <pkg>, PID: <n>"
        // line, so a truncated dump whose only member is that line must still produce a group —
        // Rule C must not be weakened to "must contain an at frame".
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "Process: com.example.app, PID: 100", pid = 100),
        )

        val groups = computeStackTraceGroups(logs)

        assertEquals(listOf(1), groups.map { it.rid })
        assertEquals(listOf(2), groups.single().memberIds)
    }

    @Test
    fun everyProducedGroupIsContiguousApartFromForeignPidTidInterleaving() {
        // Invariant: for every group, no entry between the header index and the last member index
        // is left unclaimed unless it belongs to a different (pid, tid) than the trace — i.e.
        // groups don't have foreign-key holes larger than what Rule B already bounds, and same-key
        // lines in that span are never skipped over.
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100, tid = 1),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100, tid = 1),
            LogEntry(3, "10:00:00.200", LogLevel.I, "OtherProcess", "unrelated a", pid = 200, tid = 1),
            LogEntry(4, "10:00:00.300", LogLevel.I, "OtherProcess", "unrelated b", pid = 200, tid = 2),
            LogEntry(5, "10:00:00.400", LogLevel.E, "AndroidRuntime", "Caused by: java.lang.NullPointerException", pid = 100, tid = 1),
            LogEntry(6, "10:00:00.500", LogLevel.E, "AndroidRuntime", "    at com.app.Helper.get(Helper.java:20)", pid = 100, tid = 1),
        )
        val idToIndex = logs.withIndex().associate { (idx, entry) -> entry.id to idx }
        val idToPidTid = logs.associate { it.id to (it.pid to it.tid) }

        val groups = computeStackTraceGroups(logs)

        groups.forEach { g ->
            val headerIdx = idToIndex.getValue(g.rid)
            val lastMemberIdx = g.memberIds.maxOf { idToIndex.getValue(it) }
            val claimedIndices = g.memberIds.map { idToIndex.getValue(it) }.toSet()
            val ownerKey = idToPidTid.getValue(g.rid)
            for (idx in (headerIdx + 1)..lastMemberIdx) {
                val entry = logs[idx]
                val sameOwner = (entry.pid to entry.tid) == ownerKey
                if (sameOwner) assertTrue(idx in claimedIndices, "same-key entry at index $idx must be claimed by its group")
            }
        }
        assertTrue(groups.isNotEmpty())
    }
}
