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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.AppSettings
import com.indagium.model.LogDisplayField
import com.indagium.model.LogEntry
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.LogRecordMetadata
import com.indagium.model.RecordFieldNameColor
import com.indagium.model.RecordValueAlignment
import com.indagium.model.TidMapState
import com.indagium.model.TidMapTarget
import com.indagium.ui.AppState
import com.indagium.ui.AppText
import com.indagium.ui.DARK_GITHUB
import com.indagium.ui.FileView
import com.indagium.ui.LocalRecordFieldNameStyle
import com.indagium.ui.LocalRecordValueAlignment
import com.indagium.ui.LocalTheme
import com.indagium.ui.RecordFieldNameStyle
import com.indagium.ui.StructuredRecordBody
import com.indagium.ui.StructuredRecordDisclosure
import com.indagium.ui.compactRecordSummary
import com.indagium.ui.expandableRecordSource
import com.indagium.ui.recordFieldNameColor
import com.indagium.ui.recordFieldNameStyle
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
    fun doubleClickingAFieldRowOpensAndClosesIt() {
        val json = """{"metadata":{"identity":"Bluetooth.L2CAP","source":"Watch"},"status":null}"""
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Column(Modifier.width(700.dp).background(tc().p).padding(10.dp)) {
                    PreviewRecordRow(id = 301, summary = "10:00:00 INFO watch: record", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-301").performClick()
        val row = "structured-record-node-301-root-metadata"
        val child = "structured-record-node-301-root-metadata-identity"
        rule.onAllNodesWithTag(child).assertCountEquals(0)

        // On the field's name, away from its chevron.
        rule.onNodeWithTag(row).performMouseInput { doubleClick(Offset(width * 0.3f, centerY)) }
        rule.waitForIdle()
        rule.onAllNodesWithTag(child).assertCountEquals(1)

        rule.onNodeWithTag(row).performMouseInput { doubleClick(Offset(width * 0.3f, centerY)) }
        rule.waitForIdle()
        rule.onAllNodesWithTag(child).assertCountEquals(0)

        // A field with nothing to open ignores it.
        rule.onNodeWithTag("structured-record-node-301-root-status").performMouseInput { doubleClick(Offset(width * 0.3f, centerY)) }
        rule.waitForIdle()
        rule.onAllNodesWithTag(child).assertCountEquals(0)
    }

    @Test
    fun everyValueOfOneTreeStartsAtTheSameXAtEveryDepthAndStaysPutWhenANodeToggles() {
        val json = """{"alpha":{"n":42,"nested":{"aVeryLongKeyName":true,"t":null}},"x":null,"enabled":false,"list":[1,2]}"""
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB, LocalRecordValueAlignment provides RecordValueAlignment.COLUMN) {
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
    fun fieldTreeKeysAreAccentByDefaultAndValuesNeverUseTheAccentOrWarningColour() {
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
        val keys = listOf("name", "count", "enabled", "gone", "device")
        keys.forEach { key -> assertEquals(colors.ac, colorOf("$row-$key", key), "key $key is the accent by default") }
        assertEquals(colors.tx, colorOf("$row-name", "\"sensor\""))
        assertEquals(colors.tx, colorOf("$row-count", "42"))
        assertEquals(colors.tx, colorOf("$row-enabled", "false"))
        assertEquals(colors.td, colorOf("$row-gone", "null"))
        assertEquals(colors.td, colorOf("$row-device", "{1 fields}"))
        val values = listOf(
            colorOf("$row-name", "\"sensor\""), colorOf("$row-count", "42"), colorOf("$row-enabled", "false"),
            colorOf("$row-gone", "null"), colorOf("$row-device", "{1 fields}"),
        )
        assertTrue(colors.ac !in values && colors.warn !in values, "no accent or warning colour in the values")
    }

    @Test
    fun theFieldNameSettingPicksAccentPlainOrTheCustomColourForTheTreeAndFallsBackToAccent() {
        val json = """{"name":"sensor","device":{"a":1}}"""
        val custom = androidx.compose.ui.graphics.Color(0xFF06B6D4)
        var style by mutableStateOf(RecordFieldNameStyle())
        lateinit var colors: com.indagium.ui.ThemeColors
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB, LocalRecordFieldNameStyle provides style) {
                colors = tc()
                Column(Modifier.width(700.dp).background(colors.p).padding(10.dp)) {
                    PreviewRecordRow(id = 203, summary = "10:00:00 INFO api: tree", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-203").performClick()
        rule.onNodeWithTag("structured-record-node-toggle-203-root-device").performClick()
        val row = "structured-record-node-203-root"
        val keyTags = listOf("$row-name" to "name", "$row-device" to "device", "$row-device-a" to "a")

        fun assertKeys(expected: androidx.compose.ui.graphics.Color, why: String) =
            keyTags.forEach { (tag, key) -> assertEquals(expected, textColor(tag, key), "$why: $key") }

        assertKeys(colors.ac, "accent")
        style = RecordFieldNameStyle(RecordFieldNameColor.PLAIN)
        rule.waitForIdle()
        assertKeys(colors.ts, "plain")
        style = RecordFieldNameStyle(RecordFieldNameColor.CUSTOM, custom)
        rule.waitForIdle()
        assertKeys(custom, "custom")
        assertEquals(androidx.compose.ui.text.font.FontWeight.Medium, textWeight("$row-name", "name"), "keys stay medium weight")
        style = RecordFieldNameStyle(RecordFieldNameColor.CUSTOM, null)
        rule.waitForIdle()
        assertKeys(colors.ac, "custom without a stored colour")
        assertEquals(colors.tx, textColor("$row-name", "\"sensor\""), "values keep their colour")
    }

    @Test
    fun appSettingsMapToTheFieldNameStyleTheViewReads() {
        assertEquals(RecordFieldNameStyle(), AppSettings().recordFieldNameStyle())
        val custom = AppSettings(recordFieldNameColor = RecordFieldNameColor.CUSTOM, recordFieldNameCustomArgb = 0xFF06B6D4L)
        assertEquals(
            RecordFieldNameStyle(RecordFieldNameColor.CUSTOM, androidx.compose.ui.graphics.Color(0xFF06B6D4)),
            custom.recordFieldNameStyle(),
        )
        val tc = DARK_GITHUB
        assertEquals(tc.ts, recordFieldNameColor(RecordFieldNameStyle(RecordFieldNameColor.PLAIN), tc))
    }

    @Test
    fun withoutASettingAValueStartsOneSpaceAfterItsKeyAndLongerKeysPushTheirValuesFurtherRight() {
        assertEquals(RecordValueAlignment.AFTER_NAME, AppSettings().recordValueAlignment)
        val json = """{"source":"a","status":"ok","system_time_formatted":"x","device":{"id":7}}"""
        var spaceDp = 0f
        var density = 1f
        var defaultAlignment: RecordValueAlignment? = null
        rule.setContent {
            defaultAlignment = LocalRecordValueAlignment.current
            val measurer = androidx.compose.ui.text.rememberTextMeasurer()
            density = androidx.compose.ui.platform.LocalDensity.current.density
            val style = androidx.compose.ui.text.TextStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, fontSize = 11.sp)
            spaceDp = measurer.measure("aa", style).size.width / 2f / density
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Column(Modifier.width(700.dp).background(tc().p).padding(10.dp)) {
                    PreviewRecordRow(id = 205, summary = "10:00:00 INFO api: tree", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-205").performClick()
        rule.onNodeWithTag("structured-record-node-toggle-205-root-device").performClick()
        assertEquals(RecordValueAlignment.AFTER_NAME, defaultAlignment, "no provider means after the name")
        val row = "structured-record-node-205-root"
        val sourceGap = valueLeft("$row-source", "\"a\"") - keyRight("$row-source", "source")
        val statusGap = valueLeft("$row-status", "\"ok\"") - keyRight("$row-status", "status")
        val longGap = valueLeft("$row-system-time-formatted", "\"x\"") - keyRight("$row-system-time-formatted", "system_time_formatted")
        val nestedGap = valueLeft("$row-device-id", "7") - keyRight("$row-device-id", "id")
        listOf(sourceGap, statusGap, longGap, nestedGap).forEach { assertEquals(spaceDp, it, 2f / density, "one space after the key") }
        assertTrue(spaceDp in 4f..9f, "a space of an 11sp mono font is a few dp wide, was $spaceDp")
        assertTrue(
            valueLeft("$row-system-time-formatted", "\"x\"") > valueLeft("$row-source", "\"a\"") + 20f,
            "a longer key's value starts further right: values are not aligned in a column",
        )
    }

    @Test
    fun aWrappedValueStaysUnderItselfNotUnderItsKey() {
        val longValue = (1..40).joinToString(" ") { "word$it" }
        val json = """{"msg":"$longValue"}"""
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Column(Modifier.width(300.dp).background(tc().p).padding(10.dp)) {
                    PreviewRecordRow(id = 206, summary = "10:00:00 INFO api: tree", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-206").performClick()
        val row = "structured-record-node-206-root-msg"
        val key = rule.onNode(hasText("msg") and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).getBoundsInRoot()
        val value = rule.onNode(hasText("\"word1 ", substring = true) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).getBoundsInRoot()
        val rowBounds = rule.onNodeWithTag(row).getBoundsInRoot()
        assertTrue(value.left > key.right, "the value starts right of the key")
        assertTrue(value.right <= rowBounds.right + 0.5.dp, "the value stays inside the row")
        assertTrue(value.bottom - value.top > (key.bottom - key.top) * 1.5f, "the value wraps onto more lines than the key has")
        val spaceLeft = rowBounds.right - rowBounds.left - 20.dp - (key.right - key.left)
        assertTrue(value.right - value.left < spaceLeft, "the value is as wide as the space left of it, not the whole row")
    }

    @Test
    fun anOverlongKeyIsEllipsizedAndItsValueStillShows() {
        val key = "k".repeat(70)
        val json = """{"$key":"visible-value"}"""
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Column(Modifier.width(500.dp).background(tc().p).padding(10.dp)) {
                    PreviewRecordRow(id = 207, summary = "10:00:00 INFO api: tree", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-207").performClick()
        val row = "structured-record-node-207-root-$key"
        val rowBounds = rule.onNodeWithTag(row).getBoundsInRoot()
        val keyBounds = rule.onNode(hasText(key) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).getBoundsInRoot()
        assertTrue(keyBounds.right - keyBounds.left <= (rowBounds.right - rowBounds.left - 20.dp) * 0.45f + 1.dp, "the key takes at most about 45% of the row")
        val node = rule.onNode(hasText(key) and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        assertTrue(layouts.single().hasVisualOverflow, "the long key is cut with an ellipsis")
        rule.onNode(hasText("\"visible-value\"") and hasAnyAncestor(hasTestTag(row)), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            valueLeft(row, "\"visible-value\"") >= keyBounds.right.value,
            "the value starts after the key and has room",
        )
    }

    @Test
    fun treeTogglesAreTheAppsRoundedHoverChevronInASlotThatKeepsKeysAndValuesAligned() {
        val json = """{"device":{"name":"sensor-7"},"enabled":false,"list":[1]}"""
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB, LocalRecordValueAlignment provides RecordValueAlignment.COLUMN) {
                Column(Modifier.width(700.dp).background(tc().p).padding(10.dp)) {
                    PreviewRecordRow(id = 204, summary = "10:00:00 INFO api: tree", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-204").performClick()
        val device = rule.onNodeWithTag("structured-record-node-toggle-204-root-device")
        device.assertContentDescriptionEquals("Expand device")
        val bounds = device.getBoundsInRoot()
        assertEquals(20f, bounds.right.value - bounds.left.value, 0.01f, "toggle slot width")
        assertEquals(16f, bounds.bottom.value - bounds.top.value, 0.01f, "toggle slot height keeps rows from growing")
        val rowHeights = listOf("device", "enabled", "list").map { key ->
            rule.onNodeWithTag("structured-record-node-204-root-$key").getBoundsInRoot().let { it.bottom.value - it.top.value }
        }
        rowHeights.forEach { assertEquals(rowHeights.first(), it, 0.01f, "rows with and without a toggle are as tall as each other") }

        device.performClick()
        rule.onNodeWithTag("structured-record-node-toggle-204-root-device").assertContentDescriptionEquals("Collapse device")
        val valueX = valueLeft("structured-record-node-204-root-enabled", "false")
        assertEquals(valueX, valueLeft("structured-record-node-204-root-device-name", "\"sensor-7\""), 0.5f, "values stay aligned at depth 1")
    }

    @Test
    fun aHexFieldShowsDecodedTextAndByteCountAndExpandsToADump() {
        val text = "Software Version 1"
        val spaced = text.toByteArray().joinToString(" ") { "%02x".format(it) }
        val json = """{"name":"x","payload":"$spaced","id":"1794860790349975040","blob":"ffeeddccbbaa00112233"}"""
        lateinit var colors: com.indagium.ui.ThemeColors
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                colors = tc()
                Column(Modifier.width(900.dp).background(colors.p).padding(10.dp).testTag("structured-record-qa-root")) {
                    PreviewRecordRow(id = 301, summary = "10:00:00 INFO api: hex", source = json)
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-301").performClick()
        val row = "structured-record-node-301-root"
        rule.onNodeWithTag("$row-payload").assertTextContains("\"$text\"", substring = true)
        rule.onNodeWithTag("$row-payload").assertTextContains("hex · 18 bytes", substring = true)
        assertEquals(colors.td, textColor("$row-payload", "hex · 18 bytes"), "the byte count is dim")
        assertEquals(colors.tx, textColor("$row-payload", "\"$text\""), "the decoded text is the value colour")
        rule.onNodeWithTag("$row-blob").assertTextContains("hex · 10 bytes", substring = true)
        rule.onNodeWithTag("$row-id").assertTextContains("1794860790349975040", substring = true)
        rule.onNodeWithTag("structured-record-node-toggle-301-root-blob").assertExists()
        rule.onNodeWithTag("structured-record-node-toggle-301-root-id").assertDoesNotExist()
        rule.onNodeWithTag("structured-record-node-toggle-301-root-name").assertDoesNotExist()
        rule.onNodeWithTag("structured-record-hex-line-301-301-root-payload-0").assertDoesNotExist()

        rule.onNodeWithTag("structured-record-node-toggle-301-root-payload").performClick()
        rule.onNodeWithTag("structured-record-hex-line-301-301-root-payload-0")
            .assertTextContains("0000  53 6f 66 74 77 61 72 65 20 56 65 72 73 69 6f 6e")
        rule.onNodeWithTag("structured-record-hex-line-301-301-root-payload-1").assertTextContains("0010  20 31")
        rule.onNodeWithTag("structured-record-hex-line-301-301-root-payload-2").assertDoesNotExist()
        rule.onNodeWithTag("structured-record-node-toggle-301-root-blob").performClick()
        rule.onNodeWithTag("structured-record-hex-line-301-301-root-blob-0")
            .assertTextContains("0000  ff ee dd cc bb aa 00 11 22 33")
        saveScreenshot("structured-json-hex-expanded")
    }

    @Test
    fun aLongHexDumpIsCappedWithTheMoreLinesNotice() {
        val spaced = (0 until 1400).joinToString(" ") { "%02x".format(it and 0xff) }
        rule.setContent {
            CompositionLocalProvider(LocalTheme provides DARK_GITHUB) {
                Column(Modifier.width(900.dp).background(tc().p).padding(10.dp)) {
                    PreviewRecordRow(id = 302, summary = "10:00:00 INFO api: big hex", source = """{"blob":"$spaced"}""")
                }
            }
        }
        rule.onNodeWithTag("structured-record-disclosure-302").performClick()
        rule.onNodeWithTag("structured-record-node-toggle-302-root-blob").performClick()
        rule.onNodeWithTag("structured-record-hex-line-302-302-root-blob-79").assertExists()
        rule.onNodeWithTag("structured-record-hex-line-302-302-root-blob-80").assertDoesNotExist()
        rule.onNodeWithText("… 8 more lines omitted").assertExists()
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

    private fun keyRight(rowTag: String, keyText: String): Float =
        rule.onNode(hasText(keyText) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true)
            .getBoundsInRoot().right.value

    private fun textColor(rowTag: String, text: String): androidx.compose.ui.graphics.Color {
        val node = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.color
    }

    private fun textWeight(rowTag: String, text: String): androidx.compose.ui.text.font.FontWeight? {
        val node = rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(rowTag)), useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().layoutInput.style.fontWeight
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
