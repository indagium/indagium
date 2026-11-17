package com.indagium

import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.CapabilityAvailability
import com.indagium.model.Filter
import com.indagium.model.LogCapability
import com.indagium.model.LogCapabilityStatus
import com.indagium.model.LogEntry
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.ParsedTime
import com.indagium.model.UNKNOWN_SEVERITY_METADATA
import com.indagium.model.deriveLogCapabilities
import com.indagium.model.usesCustomLogFormatProjection
import com.indagium.ui.buildLogAnalysis
import com.indagium.ui.normalizeFilterForCapabilities
import com.indagium.utils.deltaMillis
import com.indagium.utils.parseProfileLines
import com.indagium.utils.unrollLogTimeline
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogFormatProfileCoreTest {
    private val jsonProfile = LogFormatProfile(
        id = "core-test-json",
        name = "Core test JSON",
        engine = LogFormatEngine.JSON_LINES,
    )

    @Test
    fun unchangedBuiltinPresentationIgnoresMetadataOnlySnapshotChanges() {
        val builtin = com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES.first { it.id == "automotive-dlt" }
        val metadataChanged = builtin.copy(
            name = "Updated label",
            description = "Release notes changed.",
            revision = builtin.revision + 1,
            detection = builtin.detection.copy(extensions = builtin.detection.extensions + "trace"),
            compatibleAnalyzers = setOf("updated-analyzer"),
        )

        assertEquals(false, usesCustomLogFormatProjection(metadataChanged))
        assertEquals(true, usesCustomLogFormatProjection(metadataChanged.copy(visibleColumns = emptyList())))
    }

    @Test
    fun pidFilteringTidMapAndResolvedNamesHaveSeparateRequirements() {
        val pidOnly = LogEntry(
            id = 1,
            ts = "",
            level = LogLevel.I,
            tag = "worker",
            msg = "ready",
            pid = 77,
            recordMetadata = LogRecordMetadata(
                profileFields = mapOf("process_name" to "com.example.worker"),
                processIdentity = "77",
                usesProfilePresentation = true,
            ),
        )
        val pidOnlyCapabilities = deriveLogCapabilities(jsonProfile, listOf(pidOnly))

        assertEquals(CapabilityAvailability.AVAILABLE, pidOnlyCapabilities.getValue(LogCapability.PID_THREAD_FILTER).availability)
        assertEquals(CapabilityAvailability.UNAVAILABLE, pidOnlyCapabilities.getValue(LogCapability.PROCESS_MAP).availability)
        assertEquals(CapabilityAvailability.AVAILABLE, pidOnlyCapabilities.getValue(LogCapability.PROCESS_NAMES).availability)

        val paired = pidOnly.copy(tid = 78)
        val pairedCapabilities = deriveLogCapabilities(jsonProfile, listOf(paired))
        assertEquals(CapabilityAvailability.AVAILABLE, pairedCapabilities.getValue(LogCapability.PROCESS_MAP).availability)
    }

    @Test
    fun profileProcessNamesPopulateTheSharedAnalysisMap() {
        val entry = LogEntry(
            id = 1,
            ts = "",
            level = LogLevel.I,
            tag = "worker",
            msg = "ready",
            pid = 77,
            recordMetadata = LogRecordMetadata(
                profileFields = mapOf("process_name" to "com.example.worker"),
                processIdentity = "77",
                usesProfilePresentation = true,
            ),
        )

        val analysis = buildLogAnalysis(listOf(entry), logFormat = LogFormat.LOGCAT, profile = jsonProfile)

        assertEquals("com.example.worker", analysis.processNames[77])
    }

    @Test
    fun profileTailParserUsesAbsolutePhysicalSourceLineOffsets() {
        val profile = LogFormatProfile(
            id = "core-test-lines",
            name = "Core test lines",
            engine = LogFormatEngine.PLAIN_TEXT,
        )

        val parsed = parseProfileLines(listOf("", "first", "second"), profile, sourceLineOffset = 100)

        assertEquals(listOf(102L, 103L), parsed.entries.map { it.sourceStartLine })
        assertEquals(103L, parsed.sourceLineCount)
    }

    @Test
    fun unavailableTagAndPidPresetCriteriaAreClearedWithAnExplanation() {
        val filter = Filter(
            activeTags = setOf("worker"),
            excludeTags = setOf("noise"),
            pkgPrefixes = setOf("com.example"),
            pidTidFilter = "77,78",
        )
        val unavailable = LogCapabilityStatus(CapabilityAvailability.UNAVAILABLE, "No values were parsed.")

        val normalized = normalizeFilterForCapabilities(
            filter,
            mapOf(
                LogCapability.COMPONENT_FILTER to unavailable,
                LogCapability.PID_THREAD_FILTER to unavailable,
            ),
            BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.PLAIN_TEXT },
        )

        assertEquals(emptySet(), normalized.filter.activeTags)
        assertEquals(emptySet(), normalized.filter.excludeTags)
        assertEquals(emptySet(), normalized.filter.pkgPrefixes)
        assertEquals("", normalized.filter.pidTidFilter)
        assertEquals(true, normalized.notice?.contains("tag, PID/TID") == true)
    }

    @Test
    fun unavailableSeverityClearsHiddenPresetLevelsSoPacketErrorsRemainVisible() {
        val profile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.METADATA_JSON }
        val entries = listOf(LogEntry(1, "", LogLevel.E, "HCI.Event", "Command Disallowed"))
        val capabilities = deriveLogCapabilities(profile, entries)
        val normalized = normalizeFilterForCapabilities(Filter(levels = setOf(LogLevel.I)), capabilities, profile)
        assertEquals(LogLevel.entries.toSet(), normalized.filter.levels)
        assertTrue(normalized.notice?.contains("log level") == true)
    }

    @Test
    fun identityFilteringRecognizesMappedStringIdentitiesWithoutInventingNumericPids() {
        val entry = LogEntry(
            id = 1,
            ts = "",
            level = LogLevel.I,
            tag = "worker",
            msg = "ready",
            recordMetadata = LogRecordMetadata(
                processIdentity = "worker-a",
                threadIdentity = "main",
                usesProfilePresentation = true,
            ),
        )
        val capabilities = deriveLogCapabilities(jsonProfile, listOf(entry))

        assertEquals(CapabilityAvailability.AVAILABLE, capabilities.getValue(LogCapability.PID_THREAD_FILTER).availability)
        assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.getValue(LogCapability.PROCESS_MAP).availability)
        assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.getValue(LogCapability.PROCESS_NAMES).availability)
    }

    @Test
    fun invalidProfileTimestampBetweenValidRowsDoesNotEraseTimeline() {
        fun row(id: Int, time: ParsedTime) = LogEntry(
            id = id,
            ts = time.localTime.orEmpty(),
            level = LogLevel.I,
            tag = "worker",
            msg = "row $id",
            recordMetadata = LogRecordMetadata(parsedTime = time, usesProfilePresentation = true),
        )
        val rows = listOf(
            row(1, ParsedTime("2026-11-16T10:00:00Z", epochMillis = 1_800_000_000_000L)),
            row(2, ParsedTime("invalid")),
            row(3, ParsedTime("2026-11-16T10:00:02Z", epochMillis = 1_800_000_002_000L)),
        )

        val timeline = unrollLogTimeline(rows)

        assertEquals(0L, timeline.byId[1])
        assertEquals(2_000L, timeline.byId[3])
        assertNull(timeline.byId[2])
        assertNull(deltaMillis(rows[0], rows[1]))
        assertEquals(2_000L, deltaMillis(rows[0], rows[2]))
    }

    @Test
    fun legacyLogcatTimelineStillSkipsRawRowsAndRollsAcrossMidnight() {
        val rows = listOf(
            LogEntry(1, "23:59:59.000", LogLevel.I, "App", "before"),
            LogEntry(2, "", LogLevel.I, "RAW", "raw row", recordMetadata = UNKNOWN_SEVERITY_METADATA),
            LogEntry(3, "00:00:01.000", LogLevel.I, "App", "after"),
        )

        val timeline = unrollLogTimeline(rows)

        assertEquals(2_000L, timeline.byId.getValue(3) - timeline.byId.getValue(1))
    }
}
