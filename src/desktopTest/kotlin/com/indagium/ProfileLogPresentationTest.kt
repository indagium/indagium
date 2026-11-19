package com.indagium

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.AppSettings
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.CapabilityAvailability
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogCapability
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.LogTab
import com.indagium.model.ProcessNameMode
import com.indagium.model.UNKNOWN_SEVERITY_METADATA
import com.indagium.model.usesCustomLogFormatProjection
import com.indagium.model.withAttributeColumns
import com.indagium.ui.UNKNOWN_SEVERITY_TEXT_COLOR_ARGB
import com.indagium.ui.annotationsFromToken
import com.indagium.ui.annotationsToken
import com.indagium.ui.buildLogLineRender
import com.indagium.ui.mkTab
import com.indagium.utils.LogLinePresentationContext
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.buildFilteredCsv
import com.indagium.utils.buildFilteredTxt
import com.indagium.utils.captureAnnotationLogRef
import com.indagium.utils.computeProfileColumnLayout
import com.indagium.utils.filteredCsvHeader
import com.indagium.utils.filteredCsvValues
import com.indagium.utils.passesFilter
import com.indagium.utils.presentAnnotationLogLine
import com.indagium.utils.presentLogLine
import com.indagium.utils.presentLogLineMarkdown
import com.indagium.utils.profileDisplayColumns
import com.indagium.utils.profileDisplayTextProjection
import com.indagium.utils.remapOffset
import com.indagium.utils.visibleLogLineText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val PROFILE_ROW_ID = 1

/** Presentation acceptance checks across the viewer, search, copy/export, and annotation paths. */
class ProfileLogPresentationTest {
    @Test
    fun profileSeverityFieldsKeepKnownAndUnknownSemanticColors() {
        val knownError = profileEntry(
            ts = "10:00:00",
            level = LogLevel.E,
            tag = "Network",
            msg = "request failed",
            fields = emptyMap(),
            columns = listOf(LogDisplayField("severity", "level", "E")),
        )
        val unknownSeverity = profileEntry(
            ts = "10:00:01",
            level = LogLevel.V,
            tag = "Network",
            msg = "custom event",
            fields = emptyMap(),
            columns = listOf(LogDisplayField("severity", "level", "NOTICE")),
        ).copy(
            recordMetadata = profileEntryMetadata(emptyMap(), listOf(LogDisplayField("severity", "level", "NOTICE")), "NOTICE")
                .copy(severityKnown = false, originalSeverity = "NOTICE"),
        )

        fun render(entry: LogEntry, highlighters: List<Highlighter> = emptyList()) = buildLogLineRender(
            entry = entry,
            highlighters = highlighters,
            tsColor = Color.Gray,
            pidColor = Color.Gray,
            tagColor = Color.DarkGray,
            msgColor = Color.Black,
            keywordRegexFilter = null,
            regexContext = RegexEvaluationContext(),
        ).text

        val knownText = render(knownError)
        val knownSeverityStart = knownText.text.indexOf("E")
        assertEquals("E", knownText.text)
        assertEquals(LogLevel.E.defaultColor, knownText.foregroundAt(knownSeverityStart))

        val unknownText = render(unknownSeverity)
        val unknownSeverityStart = unknownText.text.indexOf("NOTICE")
        val neutralSeverityColor = Color(UNKNOWN_SEVERITY_TEXT_COLOR_ARGB)
        assertEquals("NOTICE", unknownText.text)
        assertEquals(neutralSeverityColor, unknownText.foregroundAt(unknownSeverityStart))
        assertTrue(unknownText.foregroundAt(unknownSeverityStart) != unknownSeverity.level.defaultColor)

        val wholeLineOverride = Highlighter(
            id = "whole-line-color",
            pattern = "NOTICE",
            regex = false,
            color = Color.Yellow,
            on = true,
            wholeLine = true,
            textColor = Color.Magenta,
            kloggStyle = true,
            backgroundEnabled = false,
        )
        val overriddenText = render(unknownSeverity, listOf(wholeLineOverride))
        assertEquals(Color.Magenta, overriddenText.foregroundAt(overriddenText.text.indexOf("NOTICE")))
    }

    @Test
    fun visibleColumnsAndDefaultAttributesDriveSearchCopyCsvAndAnnotationProjection() {
        val profile = LogFormatProfile(
            id = "custom-json-presentation",
            name = "Custom JSON",
            description = "Presentation fixture.",
            engine = LogFormatEngine.JSON_LINES,
            visibleColumns = listOf(
                LogFieldRole.TIMESTAMP,
                LogFieldRole.SEVERITY,
                LogFieldRole.COMPONENT,
                LogFieldRole.MESSAGE,
            ),
            attributes = listOf(
                LogFormatAttribute("trace_extra", "Trace ID", "trace_id", visibleByDefault = true),
                LogFormatAttribute("secret", "Secret", "secret"),
            ),
        )
        val entry = profileEntry(
            ts = "09:15:00.001",
            level = LogLevel.W,
            tag = "PaymentService",
            msg = "request completed",
            fields = mapOf("trace_id" to "trace-17", "secret" to "hidden-value"),
            columns = listOf(
                LogDisplayField("timestamp", "timestamp", "09:15:00.001"),
                LogDisplayField("severity", "level", ""),
                LogDisplayField("component", "tag", ""),
                LogDisplayField("trace_extra", "Trace ID", "trace-17"),
                LogDisplayField("message", "message", "request completed"),
            ),
        )
        val tab = profileTab(profile, entry)
        val settings = AppSettings()
        // A visible attribute goes before the trailing message; it keeps its label where no header names it.
        val displayed = "09:15:00.001  Trace ID=trace-17  request completed"

        assertEquals(displayed, visibleLogLineText(entry))
        assertEquals(displayed, presentLogLine(tab, entry, settings))
        assertTrue(presentLogLineMarkdown(tab, entry, settings).contains(displayed))
        assertTrue(passesFilter(entry, Filter(mode = FilterMode.KEYWORD, kwText = "trace-17")))
        assertFalse(passesFilter(entry, Filter(mode = FilterMode.KEYWORD, kwText = "hidden-value")))
        assertEquals(CapabilityAvailability.AVAILABLE, tab.logCapabilities.getValue(LogCapability.TEXT_SEARCH).availability)
        assertEquals(setOf("trace_id", "secret"), tab.profileFieldNames)
        assertEquals("hidden-value", entry.profileFields.getValue("secret"))
        assertEquals("{\"trace\":\"trace-17\",\"secret\":\"hidden-value\"}", entry.rawRecord)

        val context = LogLinePresentationContext(tab, settings, listOf(entry))
        assertEquals(
            listOf("timestamp", "level", "tag", "Trace ID", "message"),
            filteredCsvHeader(tab, settings),
        )
        assertEquals(
            listOf("09:15:00.001", "", "", "trace-17", "request completed"),
            filteredCsvValues(tab, entry, settings, context),
        )
        assertEquals(
            listOf(
                "timestamp,level,tag,Trace ID,message",
                "09:15:00.001,,,trace-17,request completed",
            ),
            buildFilteredCsv(tab, settings).trimEnd().lines(),
        )
        assertEquals(displayed, buildFilteredTxt(tab, settings).trimEnd())

        val capture = captureAnnotationLogRef(
            tab = tab,
            targetIds = listOf(PROFILE_ROW_ID),
            selection = emptySet(),
            displayedEntries = listOf(entry),
            settings = settings,
        )
        val rowPresentation = capture.presentation.row(PROFILE_ROW_ID)
        assertTrue(rowPresentation?.profileProjectionPresent == true)
        assertEquals(entry.recordMetadata?.displayColumns, rowPresentation?.displayColumns)
        assertEquals(displayed, presentAnnotationLogLine(entry, capture.presentation))

        val ref = AnnBlock.LogRef(
            id = "profile-ref",
            logIds = capture.rows.map { it.id },
            caption = "profile row",
            sourceEntries = capture.rows,
            presentation = capture.presentation,
        )
        val restored = assertNotNull(Annotations(blocks = listOf(ref)).annotationsToken().annotationsFromToken())
        val restoredPresentation = (restored.blocks.single() as AnnBlock.LogRef).presentation
        assertEquals(capture.presentation, restoredPresentation)
        assertEquals(displayed, presentAnnotationLogLine(entry, assertNotNull(restoredPresentation)))
    }

    @Test
    fun emptyProjectionStaysEmptyAcrossCopyCsvAndAnnotationRoundTrip() {
        val profile = LogFormatProfile(
            id = "empty-projection",
            name = "Empty projection",
            description = "No visible columns.",
            engine = LogFormatEngine.PLAIN_TEXT,
            visibleColumns = emptyList(),
        )
        val entry = profileEntry(
            ts = "11:00:00.000",
            level = LogLevel.V,
            tag = "RAW",
            msg = "raw input",
            fields = mapOf("private" to "still-retained"),
            columns = emptyList(),
        )
        val tab = profileTab(profile, entry)
        val settings = AppSettings()
        val context = LogLinePresentationContext(tab, settings, listOf(entry))

        assertEquals("", visibleLogLineText(entry))
        assertEquals("", presentLogLine(tab, entry, settings))
        assertEquals("", presentLogLineMarkdown(tab, entry, settings))
        assertTrue(filteredCsvHeader(tab, settings).isEmpty())
        assertTrue(filteredCsvValues(tab, entry, settings, context).isEmpty())
        assertEquals(System.lineSeparator().repeat(2), buildFilteredCsv(tab, settings))
        assertEquals("", buildFilteredTxt(tab, settings).trimEnd())

        val capture = captureAnnotationLogRef(
            tab = tab,
            targetIds = listOf(PROFILE_ROW_ID),
            selection = emptySet(),
            displayedEntries = listOf(entry),
            settings = settings,
        )
        assertTrue(capture.presentation.row(PROFILE_ROW_ID)?.profileProjectionPresent == true)
        assertTrue(capture.presentation.row(PROFILE_ROW_ID)?.displayColumns.orEmpty().isEmpty())
        assertEquals("", presentAnnotationLogLine(entry, capture.presentation))

        val ref = AnnBlock.LogRef(
            id = "empty-profile-ref",
            logIds = listOf(PROFILE_ROW_ID),
            caption = "empty projection",
            sourceEntries = capture.rows,
            presentation = capture.presentation,
        )
        val restored = assertNotNull(Annotations(blocks = listOf(ref)).annotationsToken().annotationsFromToken())
        val restoredPresentation = assertNotNull((restored.blocks.single() as AnnBlock.LogRef).presentation)
        assertTrue(restoredPresentation.row(PROFILE_ROW_ID)?.profileProjectionPresent == true)
        assertEquals("", presentAnnotationLogLine(entry, restoredPresentation))
        assertEquals("still-retained", entry.profileFields.getValue("private"))
    }

    @Test
    fun profileHighlightOffsetsApplyToRenderedProjectionWithoutLegacyPidRemapping() {
        val entry = profileEntry(
            ts = "",
            level = LogLevel.I,
            tag = "",
            msg = "",
            fields = emptyMap(),
            columns = listOf(
                LogDisplayField("prefix", "x", "1234"),
                LogDisplayField("trace", "trace_id", "trace-a"),
            ),
            pid = 123,
        )
        val projection = visibleLogLineText(entry)
        val render = buildLogLineRender(
            entry = entry,
            highlighters = listOf(Highlighter("trace", "trace-a", false, Color.Yellow, true)),
            tsColor = Color.Gray,
            pidColor = Color.Gray,
            tagColor = Color.DarkGray,
            msgColor = Color.Black,
            keywordRegexFilter = null,
            regexContext = RegexEvaluationContext(),
            pidFieldWidth = 10,
        ).text
        val matchStart = projection.indexOf("trace-a")

        assertEquals(projection, render.text)
        assertTrue(matchStart >= 0)
        assertTrue(render.backgroundAt(matchStart) != Color.Unspecified)
        assertEquals(Color.Unspecified, render.backgroundAt(matchStart + "trace-a".length))
    }

    @Test
    fun profileProcessNamesRemapHighlightsAndHonorCopyAndAnnotationIdentityOptions() {
        val profile = LogFormatProfile(
            id = "profile-process-copy",
            name = "Process columns",
            description = "Visible process identity columns.",
            engine = LogFormatEngine.JSON_LINES,
            visibleColumns = listOf(
                LogFieldRole.PROCESS_ID,
                LogFieldRole.THREAD_ID,
                LogFieldRole.TIMESTAMP,
                LogFieldRole.MESSAGE,
            ),
        )
        val entry = profileEntry(
            ts = "10:00:00",
            level = LogLevel.I,
            tag = "Worker",
            msg = "finished",
            fields = emptyMap(),
            columns = listOf(
                LogDisplayField("process_id", "pid", "42"),
                LogDisplayField("thread_id", "tid", "43"),
                LogDisplayField("timestamp", "timestamp", "10:00:00"),
                LogDisplayField("message", "message", "finished"),
            ),
            pid = 42,
        )
        val baseTab = profileTab(profile, entry)
        val tab = baseTab.copy(analysis = baseTab.analysis.copy(processNames = mapOf(42 to "worker-service")))
        val canonicalText = "42  43  10:00:00  finished"
        val renderedText = "worker-service  43  10:00:00  finished"
        val render = buildLogLineRender(
            entry = entry,
            highlighters = listOf(Highlighter("message", "finished", false, Color.Yellow, true)),
            tsColor = Color.Gray,
            pidColor = Color.Gray,
            tagColor = Color.DarkGray,
            msgColor = Color.Black,
            keywordRegexFilter = null,
            regexContext = RegexEvaluationContext(),
            processDisplay = "worker-service",
        ).text
        val sourceMatchStart = canonicalText.indexOf("finished")
        val renderedMatchStart = renderedText.indexOf("finished")

        assertEquals(canonicalText, visibleLogLineText(entry))
        assertEquals(renderedText, render.text)
        assertTrue(renderedMatchStart > sourceMatchStart)
        assertTrue(render.backgroundAt(renderedMatchStart) != Color.Unspecified)
        assertEquals(Color.Unspecified, render.backgroundAt(sourceMatchStart))

        val identityHidden = buildLogLineRender(
            entry = entry,
            highlighters = listOf(Highlighter("message", "finished", false, Color.Yellow, true)),
            tsColor = Color.Gray,
            pidColor = Color.Gray,
            tagColor = Color.DarkGray,
            msgColor = Color.Black,
            keywordRegexFilter = null,
            regexContext = RegexEvaluationContext(),
            processDisplay = "worker-service",
            showPidTid = false,
        ).text
        val identityHiddenMatchStart = identityHidden.text.indexOf("finished")
        assertEquals("10:00:00  finished", identityHidden.text)
        assertTrue(identityHidden.backgroundAt(identityHiddenMatchStart) != Color.Unspecified)

        val omitIdentity = AppSettings(copyPidTid = false, copyPidAsName = true)
        assertEquals("10:00:00  finished", presentLogLine(tab, entry, omitIdentity))
        assertEquals(listOf("timestamp", "message"), filteredCsvHeader(tab, omitIdentity))
        assertEquals(
            listOf("10:00:00", "finished"),
            filteredCsvValues(tab, entry, omitIdentity, LogLinePresentationContext(tab, omitIdentity, listOf(entry))),
        )

        val copyName = AppSettings(copyPidTid = true, copyPidAsName = true)
        assertEquals(renderedText, presentLogLine(tab, entry, copyName))
        assertTrue(presentLogLineMarkdown(tab, entry, copyName).contains("worker-service"))
        assertEquals(listOf("pid", "pid_name", "tid", "timestamp", "message"), filteredCsvHeader(tab, copyName))
        assertEquals(
            listOf("42", "worker-service", "43", "10:00:00", "finished"),
            filteredCsvValues(tab, entry, copyName, LogLinePresentationContext(tab, copyName, listOf(entry))),
        )

        val annotationTab = tab.copy(processNameMode = ProcessNameMode.ALL)
        val capture = captureAnnotationLogRef(
            tab = annotationTab,
            targetIds = listOf(PROFILE_ROW_ID),
            selection = emptySet(),
            displayedEntries = listOf(entry),
            settings = AppSettings(),
        )
        val pidHidden = capture.presentation.copy(
            options = AnnBlock.LogRefPresentationOptions(pidTid = false, processName = true),
        )
        val processNameVisible = capture.presentation.copy(
            options = AnnBlock.LogRefPresentationOptions(pidTid = true, processName = true),
        )
        assertEquals("10:00:00  finished", presentAnnotationLogLine(entry, pidHidden))
        assertEquals(renderedText, presentAnnotationLogLine(entry, processNameVisible))
    }

    @Test
    fun customLogcatAndDltProfilesUseTheirOrderedColumnsAndKeepNativeMetadata() {
        val logcatProfile = LogFormatProfile(
            id = "custom-logcat-columns",
            name = "Custom Logcat",
            description = "Selected logcat fields.",
            engine = LogFormatEngine.LOGCAT,
            visibleColumns = listOf(LogFieldRole.PROCESS_ID, LogFieldRole.THREAD_ID, LogFieldRole.COMPONENT, LogFieldRole.MESSAGE),
            attributes = listOf(LogFormatAttribute("trace_extra", "Trace ID", "trace_id", visibleByDefault = true)),
        )
        val logcat = profileEntry(
            ts = "12:00:00.100",
            level = LogLevel.D,
            tag = "Worker",
            msg = "ready",
            fields = mapOf("trace_id" to "trace-lc", "hidden" to "kept"),
            columns = listOf(
                LogDisplayField("process_id", "pid", "42"),
                LogDisplayField("thread_id", "tid", "43"),
                LogDisplayField("component", "tag", "Worker"),
                LogDisplayField("trace_extra", "Trace ID", "trace-lc"),
                LogDisplayField("message", "message", "ready"),
            ),
            pid = 42,
        ).copy(recordMetadata = profileEntryMetadata(
            fields = mapOf("trace_id" to "trace-lc", "hidden" to "kept"),
            columns = listOf(
                LogDisplayField("process_id", "pid", "42"),
                LogDisplayField("thread_id", "tid", "43"),
                LogDisplayField("component", "tag", "Worker"),
                LogDisplayField("trace_extra", "Trace ID", "trace-lc"),
                LogDisplayField("message", "message", "ready"),
            ),
            raw = "12-01 12:00:00.100 42 43 D Worker: ready",
            legacyClock = true,
        ))
        val logcatTab = profileTab(logcatProfile, logcat)
        val logcatText = "42  43  Worker  Trace ID=trace-lc  ready"
        val withIdentity = AppSettings(copyPidTid = true)
        assertEquals(logcatText, presentLogLine(logcatTab, logcat, withIdentity))
        assertEquals(listOf("pid", "tid", "tag", "Trace ID", "message"), filteredCsvHeader(logcatTab, withIdentity))
        assertEquals("kept", logcat.profileFields.getValue("hidden"))
        assertEquals("12-01 12:00:00.100 42 43 D Worker: ready", logcat.rawRecord)

        val dltProfile = LogFormatProfile(
            id = "custom-dlt-columns",
            name = "Custom DLT",
            description = "Selected DLT fields.",
            engine = LogFormatEngine.DLT,
            visibleColumns = listOf(LogFieldRole.MESSAGE, LogFieldRole.TIMESTAMP, LogFieldRole.COMPONENT),
            attributes = listOf(LogFormatAttribute("ecu_id", "ECU", "ecu", visibleByDefault = true)),
        )
        val dlt = profileEntry(
            ts = "12:00:01",
            level = LogLevel.W,
            tag = "CanStack",
            msg = "frame received",
            fields = mapOf("ecu_id" to "ECU42", "hidden" to "native-retained"),
            columns = listOf(
                LogDisplayField("message", "message", "frame received"),
                LogDisplayField("timestamp", "timestamp", "12:00:01"),
                LogDisplayField("component", "tag", "CanStack"),
                LogDisplayField("ecu_id", "ECU", "ECU42"),
            ),
        ).copy(
            dltEcuId = "ECU42",
            dltAppId = "APP",
            dltContextId = "CTX",
            recordMetadata = profileEntryMetadata(
                fields = mapOf("ecu_id" to "ECU42", "hidden" to "native-retained"),
                columns = listOf(
                    LogDisplayField("message", "message", "frame received"),
                    LogDisplayField("timestamp", "timestamp", "12:00:01"),
                    LogDisplayField("component", "tag", "CanStack"),
                    LogDisplayField("ecu_id", "ECU", "ECU42"),
                ),
                raw = "native DLT source record",
                legacyClock = true,
            ),
        )
        val dltTab = profileTab(dltProfile, dlt, LogFormat.DLT)
        val dltText = "frame received  12:00:01  CanStack  ECU=ECU42"
        assertEquals(dltText, presentLogLine(dltTab, dlt, AppSettings()))
        assertEquals(listOf("message", "timestamp", "tag", "ECU"), filteredCsvHeader(dltTab, AppSettings()))
        assertEquals(
            listOf("frame received", "12:00:01", "CanStack", "ECU42"),
            filteredCsvValues(
                dltTab,
                dlt,
                AppSettings(),
                LogLinePresentationContext(dltTab, AppSettings(), listOf(dlt)),
            ),
        )
        assertEquals("ECU42", dlt.dltEcuId)
        assertEquals("native-retained", dlt.profileFields.getValue("hidden"))
    }

    @Test
    fun readOnlyBuiltinProfileIdsKeepNativeLogcatAndDltCsvSchemas() {
        val logcatProfile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.LOGCAT }
        val logcat = LogEntry(PROFILE_ROW_ID, "12:00:00.001", LogLevel.D, "Demo", "started", pid = 123, tid = 456)
        val logcatTab = mkTab(
            "builtin-logcat",
            "native.log",
            listOf(logcat),
            logFormat = LogFormat.LOGCAT,
            profile = logcatProfile,
        )
        val logcatCsv = buildFilteredCsv(logcatTab, AppSettings(copyPidTid = true)).trimEnd().lines()

        assertEquals(logcatProfile.id, logcatTab.logFormatProfileId)
        assertFalse(logcatTab.profileProjectionSchemaPresent)
        assertFalse(usesCustomLogFormatProjection(logcatProfile.copy(readOnly = false)))
        assertEquals(
            listOf("ts,pid,tid,level,tag,msg", "12:00:00.001,123,456,D,Demo,started"),
            logcatCsv,
        )

        val dltProfile = BUILTIN_LOG_FORMAT_PROFILES.first { it.engine == LogFormatEngine.DLT }
        val dlt = LogEntry(
            id = PROFILE_ROW_ID,
            ts = "12:00:01",
            level = LogLevel.W,
            tag = "ECU42/APP/CTX",
            msg = "frame received",
            dltEcuId = "ECU42",
            dltAppId = "APP",
            dltContextId = "CTX",
            dltMessageType = "LOG",
            dltTimestamp = 1200,
            dltTimestampSource = "device",
        )
        val dltTab = mkTab(
            "builtin-dlt",
            "native.dlt",
            listOf(dlt),
            logFormat = LogFormat.DLT,
            profile = dltProfile,
        )
        val dltCsv = buildFilteredCsv(dltTab, AppSettings()).trimEnd().lines()

        assertEquals(dltProfile.id, dltTab.logFormatProfileId)
        assertFalse(dltTab.profileProjectionSchemaPresent)
        assertFalse(usesCustomLogFormatProjection(dltProfile.copy(readOnly = false)))
        assertEquals(
            listOf(
                "ts,level,tag,dlt_ecu_id,dlt_app_id,dlt_context_id,dlt_message_type,dlt_timestamp,dlt_timestamp_source,msg",
                "12:00:01,W,ECU42/APP/CTX,ECU42,APP,CTX,LOG,1200,device,frame received",
            ),
            dltCsv,
        )
    }

    @Test
    fun mixedProfileMergeCsvUsesTheOrderedUnionSchemaForEverySourceRow() {
        val profiled = profileEntry(
            ts = "10:00:00",
            level = LogLevel.I,
            tag = "ProfileTag",
            msg = "profile message",
            fields = mapOf("trace_id" to "trace-1"),
            columns = listOf(
                LogDisplayField("timestamp", "timestamp", "10:00:00"),
                LogDisplayField("message", "message", "profile message"),
                LogDisplayField("trace_extra", "Trace ID", "trace-1"),
            ),
        )
        val legacy = LogEntry(2, "10:01:00", LogLevel.W, "LegacyTag", "legacy message")
        val schema = listOf(
            LogDisplayField("timestamp", "timestamp", ""),
            LogDisplayField("component", "tag", ""),
            LogDisplayField("message", "message", ""),
            LogDisplayField("trace_extra", "Trace ID", ""),
        )
        val merged = mkTab("mixed", "mixed.log", listOf(profiled, legacy)).copy(
            profileProjectionSchemaPresent = true,
            profileDisplayColumns = schema,
        )

        assertEquals(
            listOf(
                "timestamp,tag,message,Trace ID",
                "10:00:00,,profile message,trace-1",
                "10:01:00,LegacyTag,legacy message,",
            ),
            buildFilteredCsv(merged, AppSettings()).trimEnd().lines(),
        )
    }

    @Test
    fun mixedUnionCsvProjectsNativeDltMetadataFromRowsWithoutProfileFields() {
        val dlt = LogEntry(
            id = PROFILE_ROW_ID,
            ts = "12:00:01",
            level = LogLevel.W,
            tag = "ECU42/APP/CTX",
            msg = "frame received",
            dltEcuId = "ECU42",
            dltAppId = "APP",
            dltContextId = "CTX",
            dltMessageType = "LOG",
            dltTimestamp = 1200,
            dltTimestampSource = "device",
        )
        val text = LogEntry(2, "12:00:02", LogLevel.I, "Other", "plain message")
        val schema = listOf(
            LogDisplayField("timestamp", "timestamp", ""),
            LogDisplayField("severity", "level", ""),
            LogDisplayField("component", "tag", ""),
            LogDisplayField("message", "message", ""),
            LogDisplayField("dlt_ecu_id", "dlt_ecu_id", ""),
            LogDisplayField("dlt_app_id", "dlt_app_id", ""),
            LogDisplayField("dlt_context_id", "dlt_context_id", ""),
            LogDisplayField("dlt_message_type", "dlt_message_type", ""),
            LogDisplayField("dlt_timestamp", "dlt_timestamp", ""),
            LogDisplayField("dlt_timestamp_source", "dlt_timestamp_source", ""),
        )
        val mixed = mkTab("mixed-dlt", "mixed.log", listOf(dlt, text)).copy(
            profileProjectionSchemaPresent = true,
            profileDisplayColumns = schema,
        )

        assertEquals(
            listOf(
                "timestamp,level,tag,message,dlt_ecu_id,dlt_app_id,dlt_context_id,dlt_message_type,dlt_timestamp,dlt_timestamp_source",
                "12:00:01,W,ECU42/APP/CTX,frame received,ECU42,APP,CTX,LOG,1200,device",
                "12:00:02,I,Other,plain message,,,,,,",
            ),
            buildFilteredCsv(mixed, AppSettings()).trimEnd().lines(),
        )
    }

    @Test
    fun legacyLogcatAndDltRowsRetainTheirExistingTextAndCsvShape() {
        val logcat = LogEntry(PROFILE_ROW_ID, "12:00:00.001", LogLevel.D, "Demo", "started", pid = 123, tid = 456)
        val logcatTab = legacyTab(logcat)
        val line = buildLogLineRender(
            entry = logcat,
            highlighters = emptyList(),
            tsColor = Color.Gray,
            pidColor = Color.Gray,
            tagColor = Color.DarkGray,
            msgColor = Color.Black,
            keywordRegexFilter = null,
            regexContext = RegexEvaluationContext(),
        ).text
        assertEquals("12:00:00.001    123   456  D  Demo: started", line.text)
        assertEquals("12:00:00.001  D/Demo  started", presentLogLine(logcatTab, logcat, AppSettings()))

        val dlt = LogEntry(
            id = PROFILE_ROW_ID,
            ts = "12:00:01",
            level = LogLevel.W,
            tag = "CanStack",
            msg = "frame received",
            dltEcuId = "ECU42",
            dltAppId = "APP",
            dltContextId = "CTX",
            dltMessageType = "LOG",
            dltTimestamp = 1200,
            dltTimestampSource = "device",
        )
        val dltTab = legacyTab(dlt, LogFormat.DLT)
        assertEquals(
            listOf(
                "ts", "pid", "tid", "pid_name", "level", "tag", "dlt_ecu_id", "dlt_app_id", "dlt_context_id",
                "dlt_message_type", "dlt_timestamp", "dlt_timestamp_source", "msg",
            ),
            filteredCsvHeader(dltTab, AppSettings(copyPidTid = true, copyPidAsName = true)),
        )
        val dltCopySettings = AppSettings(copyPidTid = true, copyPidAsName = true)
        assertEquals(
            listOf(
                "12:00:01", "", "", "", "W", "CanStack", "ECU42", "APP", "CTX", "LOG", "1200", "device", "frame received",
            ),
            filteredCsvValues(
                dltTab,
                dlt,
                dltCopySettings,
                LogLinePresentationContext(dltTab, dltCopySettings, listOf(dlt)),
            ),
        )
        val unknownSeverity = LogEntry(
            2,
            "raw text",
            LogLevel.V,
            "RAW",
            "raw text",
            recordMetadata = UNKNOWN_SEVERITY_METADATA,
        )
        assertEquals(
            "raw text  RAW  raw text",
            presentLogLine(legacyTab(unknownSeverity), unknownSeverity, AppSettings()),
        )
    }

    @Test
    fun standardColumnsArePaddedToTheTabsWidthsAndTheHeaderUsesTheSameWidths() {
        val header = listOf(
            LogDisplayField("timestamp", "timestamp", ""),
            LogDisplayField("severity", "level", ""),
            LogDisplayField("component", "tag", ""),
            LogDisplayField("message", "message", ""),
        )

        fun row(id: Int, level: String, tag: String, message: String) = profileEntry(
            ts = "10:00:0$id",
            level = LogLevel.I,
            tag = tag,
            msg = message,
            fields = emptyMap(),
            columns = listOf(
                LogDisplayField("timestamp", "timestamp", "10:00:0$id"),
                LogDisplayField("severity", "level", level),
                LogDisplayField("component", "tag", tag),
                LogDisplayField("message", "message", message),
            ),
        )
        val rows = listOf(row(1, "INFO", "api", "started"), row(2, "WARNING", "", "slow"), row(3, "E", "storage", "failed"))
        val layout = requireNotNull(computeProfileColumnLayout(header, rows))

        assertEquals(mapOf("timestamp" to 9, "severity" to 7, "component" to 7), layout.widths)
        assertEquals("TIMESTAMP  LVL      TAG      MESSAGE", layout.header)
        val rendered = rows.map { profileDisplayTextProjection(profileDisplayColumns(it)!!, columnWidths = layout.widths).text }
        assertEquals(
            listOf(
                "10:00:01   INFO     api      started",
                "10:00:02   WARNING" + " ".repeat(11) + "slow",
                "10:00:03   E        storage  failed",
            ),
            rendered,
        )
        // Every message starts in the header's MESSAGE column.
        assertEquals(setOf(layout.header.indexOf("MESSAGE")), rendered.map { it.indexOfAny(listOf("started", "slow", "failed")) }.toSet())

        // What filters and Find match is unpadded, and a match there maps onto the padded text.
        assertEquals("10:00:01  INFO  api  started", visibleLogLineText(rows[0]))
        val projection = profileDisplayTextProjection(profileDisplayColumns(rows[0])!!, columnWidths = layout.widths)
        val canonicalStart = visibleLogLineText(rows[0]).indexOf("api")
        assertEquals(rendered[0].indexOf("api"), projection.remapOffset(canonicalStart))
        assertEquals(rendered[0].indexOf("api") + 3, projection.remapOffset(canonicalStart + 3))

        // A Logcat tab has no profile layout.
        assertNull(computeProfileColumnLayout(header, listOf(LogEntry(1, "10:00:00.000", LogLevel.I, "App", "hi"))))
    }

    @Test
    fun extraColumnsShowTheirValueUnderTheHeaderAndLineUpBeforeTheMessage() {
        val header = listOf(
            LogDisplayField("timestamp", "timestamp", ""),
            LogDisplayField("process_id", "process id", ""),
            LogDisplayField("request_id", "Request ID", ""),
            LogDisplayField("duration_ms", "Duration (ms)", ""),
            LogDisplayField("message", "message", ""),
        )

        fun row(id: Int, request: String, message: String, duration: String) = profileEntry(
            ts = "10:00:0$id",
            level = LogLevel.I,
            tag = "",
            msg = message,
            fields = emptyMap(),
            columns = listOf(
                LogDisplayField("timestamp", "timestamp", "10:00:0$id"),
                LogDisplayField("process_id", "process id", "41$id"),
                LogDisplayField("request_id", "Request ID", request),
                LogDisplayField("duration_ms", "Duration (ms)", duration),
                LogDisplayField("message", "message", message),
            ),
        )
        val rows = listOf(row(1, "req-1", "Configuration loaded", "20"), row(2, "req-0002", "slow", "1234"))
        val layout = requireNotNull(computeProfileColumnLayout(header, rows))

        // Every column but the last (the message) is padded to the longer of its label and its values.
        assertEquals(mapOf("timestamp" to 9, "process_id" to 3, "request_id" to 10, "duration_ms" to 13), layout.widths)
        assertEquals("TIMESTAMP  PID  REQUEST ID  DURATION (MS)  MESSAGE", layout.header)
        val rendered = rows.map { profileDisplayTextProjection(profileDisplayColumns(it)!!, columnWidths = layout.widths).text }
        assertEquals(
            listOf(
                "10:00:01   411  req-1       20             Configuration loaded",
                "10:00:02   412  req-0002    1234           slow",
            ),
            rendered,
        )
        // Each value starts under its own header label: no "label=" in front of it.
        val valuesUnder = mapOf(
            "REQUEST ID" to listOf("req-1", "req-0002"),
            "DURATION (MS)" to listOf("20", "1234"),
            "MESSAGE" to listOf("Configuration", "slow"),
        )
        valuesUnder.forEach { (label, values) ->
            val column = layout.header.indexOf(label)
            assertEquals(listOf(column, column), rendered.zip(values).map { (line, value) -> line.indexOf(value) }, label)
        }

        // Copy, export, notes and filters have no header, so the extra fields keep their labels there.
        assertEquals("10:00:01  411  Request ID=req-1  Duration (ms)=20  Configuration loaded", visibleLogLineText(rows[0]))
        // A Find match on a labelled value lands on the value drawn under the header.
        val projection = profileDisplayTextProjection(profileDisplayColumns(rows[0])!!, columnWidths = layout.widths)
        val canonicalStart = visibleLogLineText(rows[0]).indexOf("req-1")
        assertEquals(rendered[0].indexOf("req-1"), projection.remapOffset(canonicalStart))
    }

    @Test
    fun visibleAttributesGoBeforeATrailingMessageAndAfterAnyOtherOrder() {
        fun field(id: String) = LogDisplayField(id, id, id)

        fun profile(vararg columns: LogFieldRole) =
            LogFormatProfile(id = "order", name = "Order", engine = LogFormatEngine.CSV, visibleColumns = columns.toList())
        val attributes = listOf(field("duration_ms"), field("region"))

        assertEquals(
            listOf("timestamp", "request_id", "duration_ms", "region", "message"),
            withAttributeColumns(
                profile(LogFieldRole.TIMESTAMP, LogFieldRole.REQUEST_ID, LogFieldRole.MESSAGE),
                listOf(field("timestamp"), field("request_id"), field("message")),
                attributes,
            ).map { it.id },
        )
        // A profile that puts the message elsewhere keeps its own order.
        assertEquals(
            listOf("message", "timestamp", "duration_ms", "region"),
            withAttributeColumns(
                profile(LogFieldRole.MESSAGE, LogFieldRole.TIMESTAMP),
                listOf(field("message"), field("timestamp")),
                attributes,
            ).map { it.id },
        )
    }

    @Test
    fun aColumnNoRowHasAValueForLeavesTheHeaderAndTheRows() {
        val header = listOf(
            LogDisplayField("timestamp", "timestamp", ""),
            LogDisplayField("severity", "level", ""),
            LogDisplayField("component", "tag", ""),
            LogDisplayField("message", "message", ""),
        )
        val rows = (1..2).map { id ->
            profileEntry(
                ts = "",
                level = LogLevel.I,
                tag = "",
                msg = "Sample $id",
                fields = emptyMap(),
                columns = header.map { if (it.id == "message") it.copy(value = "Sample $id") else it },
            )
        }
        val layout = requireNotNull(computeProfileColumnLayout(header, rows))

        assertEquals("MESSAGE", layout.header)
        assertEquals(emptyMap(), layout.widths)
        assertEquals("Sample 1", profileDisplayTextProjection(profileDisplayColumns(rows[0])!!, columnWidths = layout.widths).text)
    }

    @Test
    fun aNarrowTileDropsPidAndTidFromTheHeaderAsFromTheRows() {
        val header = listOf(
            LogDisplayField("timestamp", "timestamp", ""),
            LogDisplayField("process_id", "process id", ""),
            LogDisplayField("thread_id", "thread id", ""),
            LogDisplayField("request_id", "Request ID", ""),
            LogDisplayField("message", "message", ""),
        )
        val entry = profileEntry(
            ts = "10:00:01",
            level = LogLevel.I,
            tag = "",
            msg = "started",
            fields = emptyMap(),
            columns = listOf(
                LogDisplayField("timestamp", "timestamp", "10:00:01"),
                LogDisplayField("process_id", "process id", "4100"),
                LogDisplayField("thread_id", "thread id", "4200"),
                LogDisplayField("request_id", "Request ID", "req-001"),
                LogDisplayField("message", "message", "started"),
            ),
        )
        val layout = requireNotNull(computeProfileColumnLayout(header, listOf(entry)))
        val narrow = profileDisplayTextProjection(profileDisplayColumns(entry)!!, includeProcessThread = false, columnWidths = layout.widths).text
        val narrowHeader = layout.headerFor(includeProcessThread = false)

        assertEquals("TIMESTAMP  REQUEST ID  MESSAGE", narrowHeader)
        assertEquals("10:00:01   req-001     started", narrow)
        assertEquals(narrowHeader.indexOf("REQUEST ID"), narrow.indexOf("req-001"))
        assertEquals(narrowHeader.indexOf("MESSAGE"), narrow.indexOf("started"))
        assertEquals(layout.header, layout.headerFor(includeProcessThread = true))
        assertTrue("PID" in layout.header && "TID" in layout.header)
    }

    private fun profileEntry(
        ts: String,
        level: LogLevel,
        tag: String,
        msg: String,
        fields: Map<String, String>,
        columns: List<LogDisplayField>,
        pid: Int = 0,
    ): LogEntry = LogEntry(
        id = PROFILE_ROW_ID,
        ts = ts,
        level = level,
        tag = tag,
        msg = msg,
        pid = pid,
        tid = if (pid > 0) 43 else 0,
        recordMetadata = profileEntryMetadata(fields, columns, "{\"trace\":\"trace-17\",\"secret\":\"hidden-value\"}"),
    )

    private fun profileEntryMetadata(
        fields: Map<String, String>,
        columns: List<LogDisplayField>,
        raw: String,
        legacyClock: Boolean = false,
    ) = LogRecordMetadata(
        profileFields = fields,
        displayColumns = columns,
        rawRecord = raw,
        usesProfilePresentation = true,
        usesLegacyClockSemantics = legacyClock,
    )

    private fun profileTab(profile: LogFormatProfile, entry: LogEntry, format: LogFormat = LogFormat.LOGCAT): LogTab =
        mkTab("profile-tab", "profile.log", listOf(entry), logFormat = format, profile = profile)

    private fun legacyTab(entry: LogEntry, format: LogFormat = LogFormat.LOGCAT): LogTab =
        mkTab("legacy-tab", "legacy.log", listOf(entry), logFormat = format)

    private fun AnnotatedString.backgroundAt(offset: Int): Color =
        spanStyles.filter { offset >= it.start && offset < it.end }
            .fold(SpanStyle()) { style, range -> style.merge(range.item) }
            .background

    private fun AnnotatedString.foregroundAt(offset: Int): Color =
        spanStyles.filter { offset >= it.start && offset < it.end }
            .fold(SpanStyle()) { style, range -> style.merge(range.item) }
            .color
}
