package com.openlog

import com.openlog.model.CrashCategory
import com.openlog.model.CrashKind
import com.openlog.model.CrashSite
import com.openlog.model.CustomIssueRule
import com.openlog.model.IssueCategorySelection
import com.openlog.model.IssueSite
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeCustomIssueSites
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.crashSitesForCategory
import com.openlog.utils.groupIssueSites
import com.openlog.utils.issueSitesForCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashPanelDetectionTest {
    @Test
    fun customIssueRulesMatchEnabledRegexesAgainstTagsAndMessages() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.W, "Net", "timeout after 20ms"),
            LogEntry(2, "10:00:01.000", LogLevel.I, "timeout-tag", "connected"),
        )
        val rules = listOf(
            CustomIssueRule("timeout", "Timeouts", "timeout(?:\\s+after|-tag)", enabled = true),
            CustomIssueRule("disabled", "Disabled", "connected", enabled = false),
        )

        val sites = computeCustomIssueSites(logs, rules)

        assertEquals(listOf(1, 2), sites.map { it.entry.id })
        assertEquals("Timeouts", sites.first().categoryName)
    }

    @Test
    fun allIssuesDeduplicateCustomMatchesAndPreferCrashMetadata() {
        val crashEntry = LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main")
        val otherEntry = LogEntry(2, "10:00:01.000", LogLevel.W, "App", "timeout after 20ms")
        val crash = CrashSite("crash_1", crashEntry, CrashKind.EXCEPTION, "st_1", isFatal = true)
        val custom = computeCustomIssueSites(
            listOf(crashEntry, otherEntry),
            listOf(CustomIssueRule("a", "Alerts", "EXCEPTION|timeout")),
        )

        val all = issueSitesForCategory(listOf(crash), custom, IssueCategorySelection.BuiltIn(CrashCategory.ALL))
        val alerts = issueSitesForCategory(listOf(crash), custom, IssueCategorySelection.Custom("a"))

        assertEquals(listOf(1, 2), all.map { it.entry.id })
        assertEquals(CrashSite::class, all.first()::class)
        assertEquals(listOf(1, 2), alerts.map { it.entry.id })
    }

    @Test
    fun exceptionSiteIsAnchoredAtTheStackTraceHeaderLine() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )
        val groups = computeStackTraceGroups(logs)

        val sites = computeCrashSites(logs, groups)

        assertEquals(1, sites.size)
        assertEquals(CrashKind.EXCEPTION, sites.single().kind)
        assertEquals(1, sites.single().entry.id)
        assertEquals("st_1", sites.single().groupGid)
    }

    @Test
    fun anrLineIsDetectedFromActivityManagerTag() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "ActivityManager", "Displayed com.example.app/.MainActivity", pid = 100),
            LogEntry(2, "10:00:01.000", LogLevel.E, "ActivityManager", "ANR in com.example.app (com.example.app/.MainActivity)", pid = 100),
            LogEntry(3, "10:00:01.100", LogLevel.E, "ActivityManager", "PID: 100", pid = 100),
            LogEntry(4, "10:00:01.200", LogLevel.E, "ActivityManager", "Reason: Input dispatching timed out", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(1, sites.size)
        assertEquals(CrashKind.ANR, sites.single().kind)
        assertEquals(2, sites.single().entry.id)
        assertEquals(null, sites.single().groupGid)
    }

    @Test
    fun anrOnADifferentTagIsNotDetected() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "WindowManager", "ANR in com.example.app", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertTrue(sites.isEmpty())
    }

    @Test
    fun exceptionsAndAnrsAreOrderedByDocumentPosition() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "ActivityManager", "ANR in com.example.app (com.example.app/.MainActivity)", pid = 100),
            LogEntry(2, "10:00:01.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 200),
            LogEntry(3, "10:00:01.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 200),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(listOf(1, 2), sites.map { it.entry.id })
        assertEquals(listOf(CrashKind.ANR, CrashKind.EXCEPTION), sites.map { it.kind })
    }

    @Test
    fun noCrashesOrAnrsProducesEmptyResult() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.I, "Tag", "hello", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertTrue(sites.isEmpty())
    }

    @Test
    fun fatalExceptionSiteIsMarkedFatal() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertTrue(sites.single().isFatal)
    }

    @Test
    fun nonFatalExceptionSiteIsNotMarkedFatal() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.W, "MyApp", "java.lang.IllegalStateException: bad state", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.W, "MyApp", "    at com.app.Repo.load(Repo.java:42)", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(1, sites.size)
        assertEquals(CrashKind.EXCEPTION, sites.single().kind)
        assertFalse(sites.single().isFatal)
    }

    @Test
    fun nativeCrashIsDetectedFromDebugTagFatalSignalLine() {
        val logs = listOf(
            LogEntry(
                1, "10:00:00.000", LogLevel.E, "DEBUG",
                "*** *** *** *** *** *** *** *** *** *** *** *** *** *** *** ***", pid = 100,
            ),
            LogEntry(2, "10:00:00.100", LogLevel.E, "DEBUG", "Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 100", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(1, sites.size)
        assertEquals(CrashKind.NATIVE_CRASH, sites.single().kind)
        assertEquals(2, sites.single().entry.id)
        assertTrue(sites.single().isFatal)
    }

    @Test
    fun nativeCrashOnADifferentTagIsNotDetected() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "MyTag", "Fatal signal 11 (SIGSEGV)", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertTrue(sites.isEmpty())
    }

    @Test
    fun crashSitesForCategoryFiltersToExactlyOneKindOrExceptionSubtype() {
        val anr = CrashSite("a", LogEntry(1, "10:00:00.000", LogLevel.E, "ActivityManager", "ANR"), CrashKind.ANR, null)
        val native = CrashSite("b", LogEntry(2, "10:00:00.000", LogLevel.E, "DEBUG", "Fatal signal 11"), CrashKind.NATIVE_CRASH, null, isFatal = true)
        val fatalEx = CrashSite("c", LogEntry(3, "10:00:00.000", LogLevel.E, "AndroidRuntime", "boom"), CrashKind.EXCEPTION, null, isFatal = true)
        val plainEx = CrashSite("d", LogEntry(4, "10:00:00.000", LogLevel.E, "MyApp", "boom"), CrashKind.EXCEPTION, null, isFatal = false)
        val sites = listOf(anr, native, fatalEx, plainEx)

        assertEquals(sites, crashSitesForCategory(sites, CrashCategory.ALL))
        assertEquals(listOf(native), crashSitesForCategory(sites, CrashCategory.CRASHES))
        assertEquals(listOf(anr), crashSitesForCategory(sites, CrashCategory.ANRS))
        assertEquals(listOf(fatalEx), crashSitesForCategory(sites, CrashCategory.FATAL_EXCEPTIONS))
        assertEquals(listOf(plainEx), crashSitesForCategory(sites, CrashCategory.EXCEPTIONS))
        assertTrue(crashSitesForCategory(sites, CrashCategory.OTHERS).isEmpty())
    }

    // ── Crash-signature grouping ────────────────────────────────────

    @Test
    fun aRetryLoopThrowingTheSameExceptionStaysFlatWithOccurrenceCountStampedOnEverySite() {
        fun dump(startId: Int, ts: String) = listOf(
            LogEntry(startId, ts, LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(startId + 1, ts, LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(startId + 2, ts, LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )
        val logs = dump(1, "10:00:00.000") + dump(4, "10:00:01.000") + dump(7, "10:00:02.000")

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        // Flat, not collapsed: computeCrashSites itself must keep one CrashSite per raw occurrence
        // (the minimap's "mark every occurrence" BitSet and the MCP sites[].logId consumers both
        // read this same list — see CrashSite's doc comment).
        assertEquals(listOf(1, 4, 7), sites.map { it.entry.id })
        assertEquals(1, sites.map { it.signature }.toSet().size)
        assertTrue(sites.all { it.occurrenceCount == 3 })
        assertTrue(sites.all { it.firstLogId == 1 })
    }

    @Test
    fun identicalExceptionsFromDifferentPidsShareTheSameSignatureAndGroupTogether() {
        fun dump(startId: Int, pid: Int) = listOf(
            LogEntry(startId, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = pid),
            LogEntry(startId + 1, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = pid),
            LogEntry(startId + 2, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = pid),
        )
        // Chosen deliberately: signature identifies the code location (exception class + call
        // site), not the process instance that hit it — a crash restarting under a new pid is
        // still "the same crash" for grouping purposes.
        val logs = dump(1, pid = 100) + dump(4, pid = 200)

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(1, sites.map { it.signature }.toSet().size)
        assertTrue(sites.all { it.occurrenceCount == 2 })
    }

    @Test
    fun nativeCrashSignatureNormalizesTheVaryingTidSoRepeatedCrashesCollapse() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "DEBUG", "Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 1234", pid = 100),
            LogEntry(2, "10:00:01.000", LogLevel.E, "DEBUG", "Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 5678", pid = 200),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(2, sites.size)
        assertEquals(1, sites.map { it.signature }.toSet().size)
        assertTrue(sites.all { it.occurrenceCount == 2 })
        assertEquals(1, sites.first().firstLogId)
    }

    @Test
    fun differentFaultingSignalsProduceDifferentNativeCrashSignatures() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "DEBUG", "Fatal signal 11 (SIGSEGV), code 1, fault addr 0x0 in tid 1234", pid = 100),
            LogEntry(2, "10:00:01.000", LogLevel.E, "DEBUG", "Fatal signal 6 (SIGABRT), code -1 in tid 5678", pid = 200),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(2, sites.map { it.signature }.toSet().size)
        assertTrue(sites.all { it.occurrenceCount == 1 })
    }

    @Test
    fun anrSignatureNormalizesToTheNamedProcessIgnoringActivityAndReason() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "ActivityManager", "ANR in com.example.app (com.example.app/.MainActivity)", pid = 100),
            LogEntry(2, "10:00:05.000", LogLevel.E, "ActivityManager", "ANR in com.example.app (com.example.app/.SettingsActivity)", pid = 100),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(2, sites.size)
        assertEquals(1, sites.map { it.signature }.toSet().size)
        assertTrue(sites.all { it.occurrenceCount == 2 })
    }

    @Test
    fun anrsAgainstDifferentProcessesGetDifferentSignatures() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "ActivityManager", "ANR in com.example.app", pid = 100),
            LogEntry(2, "10:00:05.000", LogLevel.E, "ActivityManager", "ANR in com.other.app", pid = 200),
        )

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))

        assertEquals(2, sites.map { it.signature }.toSet().size)
    }

    @Test
    fun fatalAndNonFatalExceptionsWithTheSameClassAndFrameStaySeparateGroupsUnderAll() {
        // Fatal dump: "FATAL EXCEPTION: main" header, class name on the follow-up line, then the
        // frame — a real device crash dump's shape.
        val fatalLogs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
        )
        // Non-fatal: a bare "<Class>Exception: msg" header (no "fatal exception" substring), same
        // class and same top frame, different pid so it opens its own trace.
        val nonFatalLogs = listOf(
            LogEntry(4, "10:00:01.000", LogLevel.W, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 200),
            LogEntry(5, "10:00:01.100", LogLevel.W, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 200),
        )
        val logs = fatalLogs + nonFatalLogs

        val sites = computeCrashSites(logs, computeStackTraceGroups(logs))
        // Confirms the premise: same class + top frame really does produce the same signature, so
        // this is exactly the merge risk groupIssueSites' key must guard against.
        assertEquals(1, sites.map { it.signature }.toSet().size)
        assertEquals(setOf(true, false), sites.map { it.isFatal }.toSet())

        val all = issueSitesForCategory(sites, emptyList(), IssueCategorySelection.BuiltIn(CrashCategory.ALL))
        val groups = groupIssueSites(all)

        assertEquals(2, groups.size)
        val fatalGroup = groups.single { (it.representative as CrashSite).isFatal }
        val nonFatalGroup = groups.single { !(it.representative as CrashSite).isFatal }
        assertEquals(listOf(1), fatalGroup.occurrences.map { it.entry.id })
        assertEquals(listOf(4), nonFatalGroup.occurrences.map { it.entry.id })
        assertTrue((fatalGroup.representative as CrashSite).isFatal)
    }

    @Test
    fun issueCategoryCountsAddUpAndAllDoesNotDoubleCountASite() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
            LogEntry(4, "10:00:01.000", LogLevel.W, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 200),
            LogEntry(5, "10:00:01.100", LogLevel.W, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 200),
            LogEntry(6, "10:00:02.000", LogLevel.E, "ActivityManager", "ANR in com.example.app", pid = 300),
        )
        val crashSites = computeCrashSites(logs, computeStackTraceGroups(logs))

        val all = issueSitesForCategory(crashSites, emptyList(), IssueCategorySelection.BuiltIn(CrashCategory.ALL))
        val fatal = issueSitesForCategory(crashSites, emptyList(), IssueCategorySelection.BuiltIn(CrashCategory.FATAL_EXCEPTIONS))
        val nonFatal = issueSitesForCategory(crashSites, emptyList(), IssueCategorySelection.BuiltIn(CrashCategory.EXCEPTIONS))
        val anrs = issueSitesForCategory(crashSites, emptyList(), IssueCategorySelection.BuiltIn(CrashCategory.ANRS))

        assertEquals(3, all.size)
        assertEquals(1, fatal.size)
        assertEquals(1, nonFatal.size)
        assertEquals(1, anrs.size)
        // ALL isn't a second, independently-counted bucket: every site in it lands in exactly one
        // specific category, so the specific-category counts must sum to ALL's — never double,
        // never short.
        assertEquals(all.size, fatal.size + nonFatal.size + anrs.size)
        assertTrue(fatal.single() in all)
        assertTrue(nonFatal.single() in all)
        assertTrue(anrs.single() in all)
    }

    @Test
    fun stampedOccurrenceCountAndFirstLogIdAgreeWithGroupIssueSitesForEveryGroup() {
        val logs = listOf(
            LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 100),
            LogEntry(2, "10:00:00.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 100),
            LogEntry(3, "10:00:00.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 100),
            // Non-fatal, same class + top frame as above -> same signature, different isFatal.
            LogEntry(4, "10:00:01.000", LogLevel.W, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 200),
            LogEntry(5, "10:00:01.100", LogLevel.W, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 200),
            // A second fatal occurrence of the same signature, so the fatal group has size 2 while
            // the non-fatal group (entry 4) has size 1 — the two groups must stay distinguishable
            // by more than coincidence for this test to actually exercise the merge risk.
            LogEntry(6, "10:00:02.000", LogLevel.E, "AndroidRuntime", "FATAL EXCEPTION: main", pid = 300),
            LogEntry(7, "10:00:02.100", LogLevel.E, "AndroidRuntime", "java.lang.NullPointerException: boom", pid = 300),
            LogEntry(8, "10:00:02.200", LogLevel.E, "AndroidRuntime", "    at com.app.Main.onCreate(Main.java:10)", pid = 300),
        )

        val crashSites = computeCrashSites(logs, computeStackTraceGroups(logs))
        val all = issueSitesForCategory(crashSites, emptyList(), IssueCategorySelection.BuiltIn(CrashCategory.ALL))
        val groups = groupIssueSites(all)

        // Confirms the fixture actually distinguishes the two code paths: one signature, two
        // differently-sized groups, so a divergence between computeCrashSites' stamping and
        // groupIssueSites' grouping shows up as a mismatched count rather than passing by luck.
        assertEquals(1, crashSites.map { it.signature }.toSet().size)
        assertEquals(2, groups.size)

        crashSites.forEach { site ->
            val ownGroup = groups.single { group -> group.occurrences.any { it.entry.id == site.entry.id } }
            assertEquals(
                ownGroup.occurrences.size, site.occurrenceCount,
                "site ${site.entry.id}'s stamped occurrenceCount should equal its groupIssueSites group size",
            )
            assertEquals(
                ownGroup.representative.entry.id, site.firstLogId,
                "site ${site.entry.id}'s stamped firstLogId should equal its groupIssueSites representative",
            )
        }
    }

    @Test
    fun groupIssueSitesCollapsesRepeatsButPreservesDocumentOrderOfFirstOccurrence() {
        val entryA1 = LogEntry(1, "10:00:00.000", LogLevel.E, "AndroidRuntime", "boom")
        val entryB = LogEntry(2, "10:00:01.000", LogLevel.W, "Net", "timeout")
        val entryA2 = LogEntry(3, "10:00:02.000", LogLevel.E, "AndroidRuntime", "boom")
        val siteA1 = CrashSite("crash_1", entryA1, CrashKind.EXCEPTION, "st_1", isFatal = true, signature = "EXC:sig-a", occurrenceCount = 2, firstLogId = 1)
        val siteA2 = CrashSite("crash_3", entryA2, CrashKind.EXCEPTION, "st_3", isFatal = true, signature = "EXC:sig-a", occurrenceCount = 2, firstLogId = 1)
        val custom = computeCustomIssueSites(listOf(entryB), listOf(CustomIssueRule("r", "Alerts", "timeout")))
        val sites: List<IssueSite> = listOf(siteA1, custom.single(), siteA2)

        val groups = groupIssueSites(sites)

        // Two groups, in the order each signature FIRST appeared (siteA1 before the custom site),
        // even though siteA2 (same signature as siteA1) appears later in document order.
        assertEquals(2, groups.size)
        assertEquals(entryA1.id, groups[0].representative.entry.id)
        assertEquals(listOf(1, 3), groups[0].occurrences.map { it.entry.id })
        assertEquals(entryB.id, groups[1].representative.entry.id)
        assertEquals(1, groups[1].occurrences.size)
    }
}
