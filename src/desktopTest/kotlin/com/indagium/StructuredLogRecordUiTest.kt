package com.indagium

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.TidMapState
import com.indagium.model.TidMapTarget
import com.indagium.ui.AppState
import com.indagium.ui.AppText
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FileView
import com.indagium.ui.LocalTheme
import com.indagium.ui.StructuredRecordBody
import com.indagium.ui.StructuredRecordDisclosure
import com.indagium.ui.compactRecordSummary
import com.indagium.ui.expandableRecordSource
import com.indagium.ui.tc
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.computeSearchMatches
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class StructuredLogRecordUiTest {
    @get:Rule
    val rule = createComposeRule()
    private var state: AppState? = null
    private var tempDir: File? = null

    private val structuredJson = """{
        "timestamp":"2026-11-16T10:00:00Z",
        "level":"INFO",
        "message":"Request accepted",
        "device": {"name":"sensor-7", "serial":"S-700"},
        "readings": [{"temperature":21.5, "valid":true}, null],
        "enabled":false
    }""".trimIndent()
    private val multilineJsonMessage = buildString {
        append("{\"message\":\"")
        repeat(21) { index ->
            if (index > 0) append("\\n")
            append("payload line ${index + 1} with readable text")
        }
        append("\"}")
    }

    @After
    fun tearDown() {
        state?.close()
        tempDir?.deleteRecursively()
    }

    @Test
    fun structuredAndMultilineRecordsRemainOneCollapsedParentAndExpandInline() {
        val csv = """"event","details"
            "import started","first line
            second line""".trimIndent()
        val dltMessage = """Frame 12: payload
            0000  7b 22 6f 6b 22 3a 74 72 75 65
            decoded: {"ok":true}""".trimIndent()
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                val colors = tc()
                Column(
                    Modifier.width(980.dp).background(colors.p).padding(10.dp)
                        .testTag("structured-record-qa-root"),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PreviewRecordRow(
                        id = 101,
                        summary = "10:00:00 INFO api: Request accepted · device=sensor-7",
                        source = structuredJson,
                    )
                    PreviewRecordRow(
                        id = 102,
                        summary = "10:00:01 WARN importer: import started · 2 physical lines",
                        source = csv,
                    )
                    PreviewRecordRow(
                        id = 103,
                        summary = "10:00:02 DLT: Frame 12: payload · 3 lines",
                        source = dltMessage,
                    )
                    PreviewRecordRow(
                        id = 104,
                        summary = "10:00:03 INFO api: batch response · message has 21 lines",
                        source = multilineJsonMessage,
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag("logical-record-row-101").assertExists()
        rule.onNodeWithTag("logical-record-row-102").assertExists()
        rule.onNodeWithTag("logical-record-row-103").assertExists()
        rule.onNodeWithTag("logical-record-row-104").assertExists()
        rule.onAllNodesWithTag("logical-record-item-101").assertCountEquals(1)
        rule.onAllNodesWithTag("logical-record-item-102").assertCountEquals(1)
        rule.onAllNodesWithTag("logical-record-item-103").assertCountEquals(1)
        rule.onAllNodesWithTag("logical-record-item-104").assertCountEquals(1)
        rule.onNodeWithTag("structured-record-body-101").assertDoesNotExist()
        rule.onNodeWithTag("structured-record-body-102").assertDoesNotExist()
        rule.onNodeWithTag("structured-record-body-103").assertDoesNotExist()
        rule.onNodeWithTag("structured-record-body-104").assertDoesNotExist()
        saveScreenshot("structured-json-collapsed")

        rule.onNodeWithTag("structured-record-disclosure-101").performClick()
        rule.onNodeWithTag("structured-record-body-101").assertExists()
        rule.onNodeWithTag("structured-record-node-101-root-device").assertTextContains("{2 fields}", substring = true)
        rule.onNodeWithTag("structured-record-node-101-root-readings").assertTextContains("[2 items]", substring = true)
        rule.onNodeWithTag("structured-record-node-101-root-enabled").assertTextContains("false", substring = true)
        rule.onNodeWithTag("structured-record-node-101-root-readings-0-temperature").assertDoesNotExist()
        saveScreenshot("structured-json-expanded")

        rule.onNodeWithTag("structured-record-node-toggle-101-root-device").performClick()
        rule.onNodeWithTag("structured-record-node-101-root-device-name").assertTextContains("sensor-7", substring = true)
        rule.onNodeWithTag("structured-record-node-101-root-device-serial").assertTextContains("S-700", substring = true)
        rule.onNodeWithTag("structured-record-node-toggle-101-root-readings").performClick()
        rule.onNodeWithTag("structured-record-node-toggle-101-root-readings-0").performClick()
        rule.onNodeWithTag("structured-record-node-101-root-readings-0-temperature").assertTextContains("21.5", substring = true)
        rule.onNodeWithTag("structured-record-node-101-root-readings-0-valid").assertTextContains("true", substring = true)
        rule.onNodeWithTag("structured-record-node-101-root-readings-1").assertTextContains("null", substring = true)
        saveScreenshot("structured-json-nested-expanded")

        rule.onNodeWithTag("structured-record-disclosure-104").performClick()
        rule.onNodeWithTag("structured-record-node-104-root-message").assertTextContains("21 lines", substring = true)
        rule.onNodeWithTag("structured-record-node-toggle-104-root-message").performClick()
        rule.onNodeWithTag("structured-record-string-line-104-104-root-message-0").assertTextContains("payload line 1", substring = true)
        rule.onNodeWithTag("structured-record-string-line-104-104-root-message-20").assertTextContains("payload line 21", substring = true)
        saveScreenshot("structured-json-multiline-expanded")

        rule.onNodeWithTag("structured-record-disclosure-102").performClick()
        rule.onNodeWithTag("structured-record-raw-line-102-2").assertTextContains("second line", substring = true)
        rule.onNodeWithTag("structured-record-disclosure-103").performClick()
        rule.onNodeWithTag("structured-record-raw-line-103-2").assertTextContains("decoded:", substring = true)
        rule.onNodeWithTag("logical-record-row-101").assertExists()
        rule.onNodeWithTag("logical-record-row-102").assertExists()
        rule.onNodeWithTag("logical-record-row-103").assertExists()
        rule.onNodeWithTag("logical-record-row-104").assertExists()
        assertEquals(4, listOf(101, 102, 103, 104).size, "expansion does not create new parent IDs")
        saveScreenshot("structured-multiline-expanded")
    }

    @Test
    fun findMatchesNestedRawFieldsOnTheLogicalParentWithoutChangingCopyProjection() {
        val entry = profileEntry(
            id = 77,
            message = "Request accepted",
            raw = structuredJson,
        )
        val result = computeSearchMatches(
            listOf(LogItem.Row(entry = entry, indent = 0)),
            "S-700",
            caseSensitive = false,
            ctx = RegexEvaluationContext(),
        )
        assertContentEquals(intArrayOf(77), result.matchIds)
        assertEquals("Request accepted", entry.recordMetadata?.displayColumns?.last()?.value)
        assertTrue(expandableRecordSource(entry, LogFormat.LOGCAT) == structuredJson)
        val compact = compactRecordSummary(buildAnnotatedString { append(("physical line\n".repeat(20))) })
        assertEquals(-1, compact.text.indexOf('\n'))
        assertTrue(compact.text.length <= 182)
        assertTrue(
            expandableRecordSource(
                LogEntry(1, "10:00", LogLevel.I, "tag", "frame\n  data"),
                LogFormat.DLT,
            ) == "frame\n  data",
        )
        assertEquals(null, expandableRecordSource(LogEntry(1, "10:00", LogLevel.I, "tag", "native\nmessage"), LogFormat.LOGCAT))
    }

    @Test
    fun realFileViewKeepsTwoLogicalEventsAndDisclosureDoesNotChangeSelectionOrGutters() {
        val app = newAppState()
        val profile = profile()
        assertTrue(app.saveLogFormatProfile(profile).isSuccess)
        val input = File(requireNotNull(tempDir), "inline-records.jsonl").apply {
            writeText(
                listOf(
                    """{"timestamp":"2026-11-16T10:00:00Z","level":"INFO","logger":"api","message":"first event",""" +
                        """"context":{"device":"sensor-7","serial":"S-700"},"enabled":true}""",
                    """{"timestamp":"2026-11-16T10:00:01Z","level":"WARN","logger":"api","message":"second event","context":{"retry":2,"accepted":false}}""",
                ).joinToString("\n"),
            )
        }
        val tabId = requireNotNull(app.openFileWithLogFormatProfile(input, profile.id))
        rule.waitUntil(15_000L) { app.tab(tabId)?.contentState == com.indagium.model.TabContentState.LOADED }
        val loaded = requireNotNull(app.tab(tabId))
        assertEquals(2, loaded.logData.size, "two JSON records should remain two LogEntry IDs")
        val firstId = loaded.logData[0].id
        val secondId = loaded.logData[1].id
        app.updateSettings { it.copy(showRowNumbers = true) }
        app.upTab(tabId) {
            it.copy(
                selected = setOf(secondId),
                showTimeDelta = true,
                tidMap = TidMapState(TidMapTarget(pid = 123, tid = 45)),
            )
        }
        app.annotationVisible = true

        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Box(
                    Modifier.fillMaxSize().testTag("structured-record-integration-root"),
                ) {
                    app.tab(tabId)?.let { FileView(app, it) }
                }
            }
        }
        rule.waitForIdle()

        rule.onAllNodes(hasTestTag("log-row-$tabId:main-$firstId")).assertCountEquals(1)
        rule.onAllNodes(hasTestTag("log-row-$tabId:main-$secondId")).assertCountEquals(1)
        rule.onNodeWithTag("log-row-$tabId:main-$secondId").assertIsSelected()
        saveIntegrationScreenshot("viewer-two-events-collapsed")

        rule.onNodeWithTag("structured-record-disclosure-$firstId").performClick()
        rule.onNodeWithTag("structured-record-node-$firstId-root-context").assertTextContains("{2 fields}", substring = true)
        rule.onNodeWithTag("structured-record-node-toggle-$firstId-root-context").performClick()
        rule.onNodeWithTag("structured-record-node-$firstId-root-context-device").assertTextContains("sensor-7", substring = true)
        rule.onNodeWithTag("log-row-$tabId:main-$firstId").assertExists()
        rule.onNodeWithTag("log-row-$tabId:main-$secondId").assertExists()
        assertEquals(2, app.tab(tabId)?.logData?.size, "expanded tree rows stay under their parent LogEntry")
        assertEquals(setOf(secondId), app.tab(tabId)?.selected, "the disclosure must not select its parent row")
        saveIntegrationScreenshot("viewer-two-events-expanded")

        rule.onNodeWithTag("log-row-$tabId:main-$firstId").performClick()
        assertEquals(setOf(firstId), app.tab(tabId)?.selected, "ordinary row selection still targets the parent ID")
    }

    @Test
    fun realFileViewRendersOnePrettyJsonObjectAsOneExpandableParent() {
        val app = newAppState()
        val profile = profile()
        assertTrue(app.saveLogFormatProfile(profile).isSuccess)
        val pretty = buildString {
            appendLine("{")
            appendLine("  \"timestamp\": \"2026-11-16T10:00:00Z\",")
            appendLine("  \"level\": \"INFO\",")
            appendLine("  \"logger\": \"api\",")
            appendLine("  \"message\": \"pretty object\",")
            appendLine("  \"context\": {")
            repeat(27) { index -> appendLine("    \"field$index\": $index,") }
            appendLine("    \"device\": {\"serial\": \"S-700\"}")
            appendLine("  }")
            append("}")
        }
        val input = File(requireNotNull(tempDir), "pretty-object.json").apply { writeText(pretty) }
        val tabId = requireNotNull(app.openFileWithLogFormatProfile(input, profile.id))
        rule.waitUntil(15_000L) { app.tab(tabId)?.contentState == com.indagium.model.TabContentState.LOADED }
        val loaded = requireNotNull(app.tab(tabId))
        assertEquals(1, loaded.logData.size, "all 33 physical lines belong to one logical JSON entry")
        val entryId = loaded.logData.single().id
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Box(Modifier.fillMaxSize().testTag("structured-record-integration-root")) {
                    app.tab(tabId)?.let { FileView(app, it) }
                }
            }
        }
        rule.waitForIdle()
        rule.onAllNodes(hasTestTag("log-row-$tabId:main-$entryId")).assertCountEquals(1)
        rule.onNodeWithTag("structured-record-body-$entryId").assertDoesNotExist()
        saveIntegrationScreenshot("viewer-pretty-object-collapsed")
        rule.onNodeWithTag("structured-record-disclosure-$entryId").performClick()
        rule.onNodeWithTag("structured-record-node-$entryId-root-context").assertTextContains("{28 fields}", substring = true)
        saveIntegrationScreenshot("viewer-pretty-object-expanded")
    }

    private fun profileEntry(id: Int, message: String, raw: String): LogEntry = LogEntry(
        id = id,
        ts = "10:00:00",
        level = LogLevel.I,
        tag = "api",
        msg = message,
        recordMetadata = LogRecordMetadata(
            displayColumns = listOf(LogDisplayField("message", "message", message)),
            rawRecord = raw,
            sourceStartLine = 1,
            sourceEndLine = 10,
            usesProfilePresentation = true,
        ),
    )

    private fun newAppState(): AppState {
        val directory = Files.createTempDirectory("structured-log-record-ui-").toFile()
        tempDir = directory
        return AppState(
            autosaveFile = File(directory, "state.cache"),
            restoreOnCreate = false,
            notesDir = File(directory, "notes"),
            archiveCacheDir = File(directory, "archive-cache"),
            customCommandsDir = File(directory, "commands"),
            filterBackupsDir = File(directory, "filter-backups"),
            autoExportNotes = false,
            controlTokenFile = File(directory, "control-token"),
            sourceIndexFile = File(directory, "source-index"),
            testingDir = File(directory, "testing"),
        ).also { state = it }
    }

    private fun profile() = LogFormatProfile(
        id = "structured-inline-ui",
        name = "Structured inline UI",
        engine = LogFormatEngine.JSON_LINES,
        fieldMappings = mapOf(
            LogFieldRole.TIMESTAMP to "timestamp",
            LogFieldRole.SEVERITY to "level",
            LogFieldRole.COMPONENT to "logger",
            LogFieldRole.MESSAGE to "message",
        ),
        visibleColumns = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.SEVERITY, LogFieldRole.COMPONENT, LogFieldRole.MESSAGE),
    )

    private fun saveIntegrationScreenshot(name: String) {
        val image = rule.onNodeWithTag("structured-record-integration-root").captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/structured-record-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }

    @androidx.compose.runtime.Composable
    private fun PreviewRecordRow(id: Int, summary: String, source: String) {
        val colors = tc()
        var expanded by remember(id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().testTag("logical-record-item-$id")) {
            Row(
                Modifier.fillMaxWidth().background(colors.p2).padding(horizontal = 4.dp, vertical = 2.dp)
                    .testTag("logical-record-row-$id"),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                StructuredRecordDisclosure(
                    entryId = id,
                    expanded = expanded,
                    color = colors.ts,
                    onClick = { expanded = !expanded },
                )
                AppText(
                    compactRecordSummary(buildAnnotatedString { append(summary) }).text,
                    color = colors.tx,
                    fontSize = 12.sp,
                    maxLines = 1,
                )
            }
            if (expanded) {
                StructuredRecordBody(entryId = id, source = source, mono = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }

    private fun saveScreenshot(name: String) {
        val image = rule.onNodeWithTag("structured-record-qa-root").captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) for (x in 0 until image.width) {
            rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/structured-record-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }
}
