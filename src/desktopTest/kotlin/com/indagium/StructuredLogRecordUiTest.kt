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
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import com.indagium.ui.structuredDisclosureVisible
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
    fun everyValueOfOneTreeStartsAtTheSameXAtEveryDepthAndStaysPutWhenANodeToggles() {
        val json = """{"alpha":{"n":42,"nested":{"aVeryLongKeyName":true,"t":null}},"x":null,"enabled":false,"list":[1,2]}"""
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Column(Modifier.width(700.dp).background(tc().p).padding(10.dp)) {
                    PreviewRecordRow(id = 201, summary = "10:00:00 INFO api: tree", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-201").performClick()
        val collapsedEnabledX = valueLeft("structured-record-node-201-root-enabled", "false")
        val collapsedSummaryX = valueLeft("structured-record-node-201-root-alpha", "{2 fields}")
        assertEquals(collapsedEnabledX, collapsedSummaryX, 0.5f, "depth 0 values share a column")

        rule.onNodeWithTag("structured-record-node-toggle-201-root-alpha").performClick()
        rule.onNodeWithTag("structured-record-node-toggle-201-root-alpha-nested").performClick()
        rule.onNodeWithTag("structured-record-node-toggle-201-root-list").performClick()
        val depthOne = valueLeft("structured-record-node-201-root-alpha-n", "42")
        val depthTwo = valueLeft("structured-record-node-201-root-alpha-nested-aVeryLongKeyName", "true")
        val arrayItem = valueLeft("structured-record-node-201-root-list-0", "1")
        assertEquals(collapsedEnabledX, depthOne, 0.5f, "depth 1 value starts where depth 0 values do")
        assertEquals(collapsedEnabledX, depthTwo, 0.5f, "depth 2 value starts where depth 0 values do")
        assertEquals(collapsedEnabledX, arrayItem, 0.5f, "array items count as keys of the same column")
        assertEquals(collapsedEnabledX, valueLeft("structured-record-node-201-root-enabled", "false"), 0.5f, "expanding moves nothing")
    }

    @Test
    fun fieldTreeKeysAreSecondaryTextAndValuesNeverUseTheAccentOrWarningColour() {
        val json = """{"name":"sensor","count":42,"enabled":false,"gone":null,"device":{"a":1}}"""
        lateinit var colors: com.indagium.ui.ThemeColors
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                colors = tc()
                Column(Modifier.width(700.dp).background(colors.p).padding(10.dp)) {
                    PreviewRecordRow(id = 202, summary = "10:00:00 INFO api: tree", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-202").performClick()

        fun colorOf(rowTag: String, text: String) = textColor(rowTag, text)

        val row = "structured-record-node-202-root"
        listOf("name", "count", "enabled", "gone", "device").forEach { key ->
            assertEquals(colors.ts, colorOf("$row-$key", key), "key $key is text-secondary")
        }
        assertEquals(colors.tx, colorOf("$row-name", "\"sensor\""))
        assertEquals(colors.tx, colorOf("$row-count", "42"))
        assertEquals(colors.tx, colorOf("$row-enabled", "false"))
        assertEquals(colors.td, colorOf("$row-gone", "null"))
        assertEquals(colors.td, colorOf("$row-device", "{1 fields}"))
        val used = listOf("name", "count", "enabled", "gone", "device").flatMap { listOf(colorOf("$row-$it", it)) }
        assertTrue(colors.ac !in used && colors.warn !in used, "no accent or warning colour in the tree")
    }

    @Test
    fun theDisclosureChevronIsShownOnlyWhileHoveredSelectedOrExpanded() {
        assertEquals(false, structuredDisclosureVisible(hovered = false, selected = false, expanded = false))
        assertEquals(true, structuredDisclosureVisible(hovered = true, selected = false, expanded = false))
        assertEquals(true, structuredDisclosureVisible(hovered = false, selected = true, expanded = false))
        assertEquals(true, structuredDisclosureVisible(hovered = false, selected = false, expanded = true))
        assertEquals(true, structuredDisclosureVisible(hovered = true, selected = true, expanded = true))
    }

    @Test
    fun aHiddenDisclosureChevronKeepsItsSlotItsSemanticsAndItsClick() {
        var clicks = 0
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Row {
                    StructuredRecordDisclosure(
                        entryId = 301, expanded = false, color = tc().ts, visible = false, onClick = { clicks++ },
                    )
                    StructuredRecordDisclosure(
                        entryId = 302, expanded = false, color = tc().ts, visible = true, onClick = {},
                    )
                }
            }
        }
        val hidden = rule.onNodeWithTag("structured-record-disclosure-301")
        val shown = rule.onNodeWithTag("structured-record-disclosure-302")
        hidden.assertExists()
        val shownBounds = shown.getBoundsInRoot()
        val hiddenBounds = hidden.getBoundsInRoot()
        assertEquals(shownBounds.right.value - shownBounds.left.value, hiddenBounds.right.value - hiddenBounds.left.value, 0.01f, "same slot width")
        hidden.assertContentDescriptionEquals("Expand record")
        hidden.performClick()
        assertEquals(1, clicks)
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

    private fun valueLeft(rowTag: String, valueText: String): Float =
        rule.onNode(hasText(valueText) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true)
            .getBoundsInRoot().left.value

    private fun textColor(rowTag: String, text: String): androidx.compose.ui.graphics.Color {
        val node = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

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
