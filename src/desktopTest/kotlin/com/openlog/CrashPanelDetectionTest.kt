package com.openlog

import com.openlog.model.CrashCategory
import com.openlog.model.CrashKind
import com.openlog.model.CrashSite
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.crashSitesForCategory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CrashPanelDetectionTest {
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
}
