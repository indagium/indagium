package com.openlog

import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.computeStackTraceGroups
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
}
