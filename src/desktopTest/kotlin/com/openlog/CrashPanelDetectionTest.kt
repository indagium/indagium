package com.openlog

import com.openlog.model.CrashKind
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeStackTraceGroups
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
