package com.indagium

import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogCapabilityStatus
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.ui.combinedTagCandidates
import com.indagium.ui.componentFilterLabel
import com.indagium.ui.componentsArePackages
import com.indagium.ui.levelPillLabels
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

/** A profile tab's level pills use the first letter of the level names its rows use. */
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

    @Test
    fun aProfileTabNamesItsLevelsAndLogcatKeepsTheAppLetters() {
        val json = mkTab(
            "json", "a.jsonl",
            listOf(
                row(1, LogLevel.V, "TRACE"), row(2, LogLevel.I, "INFO"), row(3, LogLevel.A, "FATAL"),
                row(4, LogLevel.A, "CRIT"), row(5, LogLevel.A, "CRIT"),
            ),
        )
        assertEquals(listOf("T", "D", "I", "W", "E", "C"), json.levelPillLabels())
        val logcat = mkTab("logcat", "a.log", listOf(LogEntry(1, "12:00:00.000", LogLevel.I, "App", "hi")))
        assertEquals(listOf("V", "D", "I", "W", "E", "A"), logcat.levelPillLabels())
        assertEquals("Components", componentFilterLabel(packages = false))
        assertEquals("Tags", componentFilterLabel(packages = true))
    }
}
