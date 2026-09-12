package com.indagium

import com.indagium.model.CrashKind
import com.indagium.model.CrashSite
import com.indagium.model.LogLevel
import com.indagium.ui.mkTab
import com.indagium.ui.retraceGroupGidFor
import com.indagium.ui.retraceInputForGroup
import com.indagium.ui.tabShellFromToken
import com.indagium.ui.tabToken
import com.indagium.utils.RetraceOutcome
import com.indagium.utils.RetraceService
import kotlin.io.path.createTempDirectory
import kotlin.io.path.createTempFile
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class RetraceServiceTest {
    private val service = RetraceService()

    @Test
    fun retracesVersionedMappingAndPreservesUnmappedFrameworkFrames() {
        val mapping = createTempFile("mapping", ".txt")
        mapping.toFile().writeText(
            """
            # compiler: R8
            # pg_map_id: 1234567890abcdef
            com.example.Original -> a.b:
                1:1:void run():42:42 -> c
            """.trimIndent() + "\n",
        )

        val outcome = service.retrace(
            mapping,
            listOf(
                "a.b: boom",
                "    at a.b.c(SourceFile:1)",
                "    at java.lang.Thread.run(Thread.java:840)",
            ),
        )

        val success = assertIs<RetraceOutcome.Success>(outcome)
        assertContains(success.text, "com.example.Original: boom")
        assertContains(success.text, "at com.example.Original.run(Original.java:42)")
        assertContains(success.text, "at java.lang.Thread.run(Thread.java:840)")
    }

    @Test
    fun handlesInlineAndUnknownFramesWithoutCustomParsing() {
        val mapping = createTempFile("mapping-inline", ".txt")
        mapping.toFile().writeText(
            """
            com.example.Inlined -> x.y:
                1:1:void first():10:10 -> a
                2:2:void second():20:20 -> a
            """.trimIndent() + "\n",
        )

        val outcome = service.retrace(
            mapping,
            listOf("x.y: failure", "    at x.y.a(SourceFile:2)", "    at missing.Type.method(Unknown Source)"),
        )

        val success = assertIs<RetraceOutcome.Success>(outcome)
        assertContains(success.text, "com.example.Inlined.second(Inlined.java:20)")
        assertContains(success.text, "missing.Type.method(Unknown Source)")
    }

    @Test
    fun reportsMissingEmptyAndMalformedMappingsAsSafeFailures() {
        val missing = service.retrace("/path/that/does/not/exist/mapping.txt", listOf("a.B: boom"))
        assertContains(assertIs<RetraceOutcome.Failure>(missing).message, "missing")

        val empty = service.retrace(createTempFile("mapping-empty", ".txt"), emptyList())
        assertContains(assertIs<RetraceOutcome.Failure>(empty).message, "no stack trace")

        val malformed = createTempFile("mapping-bad", ".txt")
        malformed.toFile().writeText("this is not a ProGuard mapping\n")
        val failure = assertIs<RetraceOutcome.Failure>(service.retrace(malformed, listOf("a.B: boom")))
        assertContains(failure.message, "Could not retrace")
        // The service intentionally keeps diagnostics short and user-safe, rather than exposing
        // the R8 internal stack trace or replacing the original log content.
        kotlin.test.assertTrue(failure.message.length < 500)
    }

    @Test
    fun reconstructsExceptionGroupHeaderAndOrderedMembers() {
        val tab = mkTab(
            "t1",
            "app.log",
            listOf(
                com.indagium.model.LogEntry(10, "10:00:00.000", LogLevel.E, "AndroidRuntime", "a.bException: boom"),
                com.indagium.model.LogEntry(11, "10:00:00.001", LogLevel.E, "AndroidRuntime", "    at a.b.c(SourceFile:1)"),
                com.indagium.model.LogEntry(12, "10:00:00.002", LogLevel.E, "AndroidRuntime", "    at java.lang.Thread.run(Thread.java:840)"),
            ),
        )
        val group = tab.analysis.stackTraceGroups.single()
        assertEquals(
            listOf("a.bException: boom", "    at a.b.c(SourceFile:1)", "    at java.lang.Thread.run(Thread.java:840)"),
            retraceInputForGroup(tab, group),
        )
    }

    @Test
    fun persistsMappingPathAsAppendedFieldAndReadsLegacyTokenWithoutIt() {
        val dir = createTempDirectory("retrace-token")
        val logFile = dir.resolve("app.log").toFile().apply { writeText("hello\n") }
        val mapping = dir.resolve("mapping.txt").toAbsolutePath().toString()
        val original = mkTab("t1", logFile.name, emptyList()).copy(
            sourcePath = logFile.absolutePath,
            retraceMappingPath = mapping,
        )
        val restored = original.tabToken().tabShellFromToken()?.tab
        assertEquals(mapping, restored?.retraceMappingPath)

        val legacy = original.tabToken().split("|").take(13).joinToString("|")
        assertNull(legacy.tabShellFromToken()?.tab?.retraceMappingPath)
    }

    @Test
    fun offersRetraceOnlyForExceptionGroupsWithAResolvedStackTrace() {
        val entry = com.indagium.model.LogEntry(1, "10:00:00.000", LogLevel.E, "DEBUG", "Fatal signal 11")
        val exception = CrashSite("exception", entry, CrashKind.EXCEPTION, groupGid = "st_1")
        val unresolvedException = exception.copy(groupGid = null)
        val native = exception.copy(kind = CrashKind.NATIVE_CRASH, groupGid = null)

        assertEquals("st_1", retraceGroupGidFor(exception))
        assertNull(retraceGroupGidFor(unresolvedException))
        assertNull(retraceGroupGidFor(native))
    }
}
