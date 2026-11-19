package com.indagium

import com.indagium.model.ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogCapabilityStatus
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.ui.combinedTagCandidates
import com.indagium.ui.componentFilterLabel
import com.indagium.ui.componentsArePackages
import com.indagium.ui.levelPills
import com.indagium.ui.mkTab
import com.indagium.ui.shownLogCapabilities
import com.indagium.ui.tagInputPlaceholder
import com.indagium.ui.typedTagIsPackagePrefix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Logcat tags group by Android package; a profile's components (a systemd unit, a logger) are matched exactly. */
class ComponentTagInputTest {
    private val logcat = mkTab("logcat", "logcat.log", listOf(LogEntry(1, "12:00:00.000", LogLevel.I, "com.example.app", "hi")))
    private val journal = mkTab(
        "journal",
        "journal.jsonl",
        listOf(
            LogEntry(
                1, "12:00:00.000", LogLevel.I, "checkout.service", "hi",
                recordMetadata = LogRecordMetadata(
                    displayColumns = listOf(LogDisplayField("component", "tag", "checkout.service")),
                    usesProfilePresentation = true,
                ),
            ),
        ),
    )

    @Test
    fun onlyLogcatTagsAreTreatedAsPackages() {
        assertTrue(logcat.componentsArePackages())
        assertFalse(journal.componentsArePackages())
        assertEquals("pkg prefix or tag…", tagInputPlaceholder(packages = true))
        assertEquals("component…", tagInputPlaceholder(packages = false))
    }

    @Test
    fun aDltTabFiltersItsContextsInItsOwnWords() {
        val dlt = mkTab(
            "dlt", "a.dlt",
            listOf(LogEntry(1, "12:00:00.000", LogLevel.I, "ECU1/APP1/CTX1", "hi", dltEcuId = "ECU1")),
            logFormat = LogFormat.DLT,
        )
        assertFalse(dlt.componentsArePackages())
        assertEquals("Contexts", dlt.componentFilterLabel())
        assertEquals("ECU/APP/CTX…", dlt.tagInputPlaceholder())
        assertEquals(listOf("V", "D", "I", "W", "E", "A"), dlt.levelPills().map { it.letter })
        assertEquals("Tags", logcat.componentFilterLabel())
        assertEquals("Components", journal.componentFilterLabel())
    }

    @Test
    fun aDottedComponentIsAddedExactlyInAProfileTab() {
        assertTrue(typedTagIsPackagePrefix("com.example", packages = true))
        assertFalse(typedTagIsPackagePrefix("checkout.service", packages = false))
        assertFalse(typedTagIsPackagePrefix("Worker", packages = true))
    }

    @Test
    fun aProfileTabOffersNoPackagePrefixRows() {
        val tags = listOf("checkout.service", "payment.service")
        val withPackages = combinedTagCandidates(tags, "checkout", emptySet(), emptyMap(), 0)
        val withoutPackages = combinedTagCandidates(tags, "checkout", emptySet(), emptyMap(), 0, offerPackagePrefixes = false)
        assertTrue(withPackages.any { it.second })
        assertTrue(withoutPackages.none { it.second })
        assertTrue(withoutPackages.any { it.first == "checkout.service" })
    }
}

/** An Android-only capability is listed only where it applies. */
class ShownLogCapabilitiesTest {
    @Test
    fun androidIssuesAreLeftOutWhereTheyDoNotApply() {
        val search = LogCapabilityStatus(CapabilityAvailability.AVAILABLE, "ok")
        val off = LogCapabilityStatus(CapabilityAvailability.UNAVAILABLE, "No Android crash analyzer.")
        val on = LogCapabilityStatus(CapabilityAvailability.AVAILABLE, "An Android crash analyzer is compatible.")
        val syslog = mapOf(LogCapability.TEXT_SEARCH to search, LogCapability.ANDROID_ISSUES to off)
        val logcat = mapOf(LogCapability.TEXT_SEARCH to search, LogCapability.ANDROID_ISSUES to on)

        assertEquals(setOf(LogCapability.TEXT_SEARCH), shownLogCapabilities(syslog).keys)
        assertEquals(logcat, shownLogCapabilities(logcat))
    }
}

/** A profile tab's level pills come from its profile, the same for every file of the format, each with a tooltip. */
class LevelPillLabelsTest {
    private fun row(id: Int, level: LogLevel, name: String) = LogEntry(
        id, "12:00:00", level, "c", "m",
        recordMetadata = LogRecordMetadata(
            displayColumns = listOf(LogDisplayField("severity", "level", name)),
            originalSeverity = name,
            severityKnown = true,
            usesProfilePresentation = true,
        ),
    )

    private fun profile(id: String) = (BUILTIN_LOG_FORMAT_PROFILES + ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES).single { it.id == id }

    @Test
    fun theProfileNamesThePillsWhateverLevelsTheFileHolds() {
        val full = mkTab("a", "a.jsonl", listOf(row(1, LogLevel.V, "TRACE"), row(2, LogLevel.A, "FATAL")), profile = profile("json-lines"))
        val oneError = mkTab("b", "b.json", listOf(row(1, LogLevel.E, "ERROR")), profile = profile("json-lines"))
        assertEquals(listOf("T", "D", "I", "W", "E", "F"), full.levelPills().map { it.letter })
        assertEquals(full.levelPills(), oneError.levelPills())

        val syslog = mkTab("s", "s.log", listOf(row(1, LogLevel.A, "CRIT")), profile = profile("syslog-rfc5424")).levelPills()
        assertEquals(listOf("T", "D", "I", "W", "E", "C"), syslog.map { it.letter })
        assertEquals("CRIT (2), ALERT (1), EMERG (0)", syslog.last().tooltip)
        assertEquals("Verbose: none in this format", syslog.first().tooltip)

        val logcat = mkTab("logcat", "a.log", listOf(LogEntry(1, "12:00:00.000", LogLevel.I, "App", "hi"))).levelPills()
        assertEquals(listOf("V", "D", "I", "W", "E", "A"), logcat.map { it.letter })
        assertEquals("Components", componentFilterLabel(packages = false))
        assertEquals("Tags", componentFilterLabel(packages = true))
    }
}

/** Format details says "no process IDs" once, not three times. */
class LimitedCapabilityRowsTest {
    @Test
    fun theProcessCapabilitiesShareOneRowWhenTheyAreOffTogether() {
        fun off(reason: String) = LogCapabilityStatus(CapabilityAvailability.UNAVAILABLE, reason)
        val limited = mapOf(
            LogCapability.SEVERITY_FILTER to off("No severity values were parsed."),
            LogCapability.PID_THREAD_FILTER to off("No process or thread identity values were parsed."),
            LogCapability.PROCESS_MAP to off("No paired numeric process and thread identities values were parsed."),
            LogCapability.PROCESS_NAMES to off("No resolved process name values were parsed."),
            LogCapability.SOURCE_LOOKUP to off("Configure a source folder."),
        )
        val rows = com.indagium.ui.limitedCapabilityRows(limited)

        assertEquals(3, rows.size)
        assertEquals(
            listOf(LogCapability.PID_THREAD_FILTER, LogCapability.PROCESS_MAP, LogCapability.PROCESS_NAMES),
            rows[1].capabilities.map { it.first },
        )
        assertEquals(com.indagium.ui.NO_PROCESS_IDS_REASON, rows[1].reason)
        // One process capability off on its own keeps its own reason.
        val single = com.indagium.ui.limitedCapabilityRows(mapOf(LogCapability.PROCESS_NAMES to off("No resolved process name values were parsed.")))
        assertEquals("No resolved process name values were parsed.", single.single().reason)
    }
}
