package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogCapabilityStatus
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.LogViewSectionId
import com.indagium.model.ProfileTimestampKind
import com.indagium.ui.AppState
import com.indagium.ui.FileView
import com.indagium.ui.hideLogViewTile
import com.indagium.ui.logCapabilitiesForTab
import com.indagium.ui.stripVisualWrapBreaks
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val LOAD_TIMEOUT_MS = 10_000L
private const val WIDE_CELL_CHARS = 38
private const val SQUEEZE_CELL_CHARS = 14
private const val MAX_NARROWING_STEPS = 25
private const val FIELD_TOLERANCE_CHARS = 1.5f
private const val SQUEEZE_MESSAGE_START = 3 * (SQUEEZE_CELL_CHARS + 2)
private const val SQUEEZE_MESSAGE = "Configuration loaded successfully"

@OptIn(ExperimentalTestApi::class)
class LogProfileFeatureAvailabilityUiTest {
    @get:Rule
    val rule = createComposeRule()

    private var tempDir: File? = null
    private var appState: AppState? = null

    @After
    fun tearDown() {
        appState?.close()
        tempDir?.deleteRecursively()
    }

    private fun newState(): AppState {
        val directory = Files.createTempDirectory("profile-capability-ui-").toFile()
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
        ).also { appState = it }
    }

    private fun openProfileLog(state: AppState, profile: LogFormatProfile, contents: String): String {
        val directory = requireNotNull(tempDir)
        assertTrue(state.saveLogFormatProfile(profile).isSuccess)
        val source = File(directory, "${profile.id}.jsonl").apply { writeText(contents) }
        val tabId = requireNotNull(state.openFileWithLogFormatProfile(source, profile.id))
        rule.waitUntil(LOAD_TIMEOUT_MS) {
            state.tab(tabId)?.contentState == com.indagium.model.TabContentState.LOADED
        }
        return tabId
    }

    private fun showFileView(state: AppState, tabId: String, width: Dp = 1180.dp, notes: Boolean = true) {
        state.annotationVisible = notes
        rule.setContent {
            Box(Modifier.size(width, 760.dp)) {
                state.tab(tabId)?.let { FileView(state, it) }
            }
        }
        rule.waitForIdle()
    }

    private fun recordScreenshot(name: String) {
        val image = rule.onRoot().captureToImage()
        val pixels = image.toPixelMap()
        val rendered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) rendered.setRGB(x, y, pixels[x, y].toArgb())
        }
        val directory = File("build/log-format-qa").apply { mkdirs() }
        assertTrue(ImageIO.write(rendered, "png", File(directory, "$name.png")))
    }

    @Test
    fun profileHeadersScrollWithTheirOwnRowsInFilteredAndOriginalPanes() {
        val state = newState()
        // Manual wrap mode keeps a fixed-width grid that scrolls sideways; auto-wrap mode wraps it instead (tests below).
        state.settings = state.settings.copy(autoLogRowWrap = false)
        val (profile, contents) = wideProfile()
        val tabId = openProfileLog(state, profile, contents)
        state.upTab(tabId) { it.copy(showUnfiltered = true) }
        showFileView(state, tabId)

        val entryId = requireNotNull(state.tab(tabId)).logData.single().id
        val main = state.logViewerScrollStateStore.scrollState("$tabId:main")
        val original = state.logViewerScrollStateStore.scrollState("$tabId:original")
        rule.waitUntil(LOAD_TIMEOUT_MS) { main.maxValue in 1 until Int.MAX_VALUE && original.maxValue in 1 until Int.MAX_VALUE }

        fun headerLeft(pane: String): Float = rule.onNode(
            hasTestTag("colheader-profile-text") and hasAnyAncestor(hasTestTag("log-column-header-$tabId:$pane")),
            useUnmergedTree = true,
        ).getUnclippedBoundsInRoot().left.value

        fun rowLeft(pane: String): Float =
            rule.onNodeWithTag("log-row-$tabId:$pane-$entryId").getUnclippedBoundsInRoot().left.value

        val mainHeaderBefore = headerLeft("main")
        val originalHeaderBefore = headerLeft("original")
        val mainRowBefore = rowLeft("main")
        val originalRowBefore = rowLeft("original")

        rule.onNodeWithTag("log-row-$tabId:main-$entryId").performClick()
        rule.waitForIdle()
        assertEquals(setOf(entryId), state.tab(tabId)?.selected)
        assertEquals(0, main.value, "focusing the visible part of a wide row keeps the columns in place")

        runBlocking { main.scrollTo(main.maxValue / 2) }
        rule.waitForIdle()
        val mainShift = mainHeaderBefore - headerLeft("main")
        assertTrue(mainShift > 0f)
        assertEquals(mainShift, mainRowBefore - rowLeft("main"), 1f)
        assertEquals(originalHeaderBefore, headerLeft("original"), 1f)

        runBlocking { original.scrollTo(original.maxValue / 2) }
        rule.waitForIdle()
        val originalShift = originalHeaderBefore - headerLeft("original")
        assertTrue(originalShift > 0f)
        assertEquals(originalShift, originalRowBefore - rowLeft("original"), 1f)
        assertEquals(mainShift, mainHeaderBefore - headerLeft("main"), 1f)
    }

    private fun wideProfile(): Pair<LogFormatProfile, String> {
        val attributes = List(6) { index ->
            LogFormatAttribute(id = "field_$index", label = "Field $index", source = "field_$index", visibleByDefault = true)
        }
        val profile = LogFormatProfile(
            id = "wide-columns", name = "Wide columns", engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "message"),
            visibleColumns = listOf(LogFieldRole.MESSAGE),
            attributes = attributes,
        )
        val header = (attributes.map { it.source } + "message").joinToString(",")
        val data = (attributes.map { "a".repeat(WIDE_CELL_CHARS) } + "log message").joinToString(",")
        return profile to "$header\n$data"
    }

    private fun pidTidProfile(): Pair<LogFormatProfile, String> {
        val profile = LogFormatProfile(
            id = "pid-tid", name = "Pid tid", engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "time", LogFieldRole.PROCESS_ID to "pid",
                LogFieldRole.THREAD_ID to "tid", LogFieldRole.MESSAGE to "message",
            ),
            visibleColumns = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.PROCESS_ID, LogFieldRole.THREAD_ID, LogFieldRole.MESSAGE),
        )
        return profile to "time,pid,tid,message\n2026-11-20T10:00:00Z,4100,4200,worker started"
    }

    private fun rowTextLayout(tabId: String, pane: String, entryId: Int, text: String): TextLayoutResult {
        val node = rule.onNode(
            hasText(text, substring = true) and hasAnyAncestor(hasTestTag("log-row-$tabId:$pane-$entryId")),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun headerTextLayout(tabId: String, pane: String): TextLayoutResult {
        val node = rule.onNode(
            hasTestTag("colheader-profile-text") and hasAnyAncestor(hasTestTag("log-column-header-$tabId:$pane")),
            useUnmergedTree = true,
        ).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    private fun headerText(tabId: String, pane: String): String = rule.onNode(
        hasTestTag("colheader-profile-text") and hasAnyAncestor(hasTestTag("log-column-header-$tabId:$pane")),
        useUnmergedTree = true,
    ).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }

    @Test
    fun aWideProfileGridWrapsInsteadOfScrollingSidewaysInAutoWrapMode() {
        val state = newState()
        state.settings = state.settings.copy(autoLogRowWrap = true)
        val (profile, data) = wideProfile()
        val tabId = openProfileLog(state, profile, data)
        state.upTab(tabId) { it.copy(showUnfiltered = true) }
        showFileView(state, tabId, width = 600.dp, notes = false)

        val entryId = requireNotNull(state.tab(tabId)).logData.single().id
        for (pane in listOf("main", "original")) {
            rule.waitUntil(LOAD_TIMEOUT_MS) {
                rule.onAllNodesWithTag("log-row-$tabId:$pane-$entryId").fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()
            // No horizontalScroll is attached in auto-wrap mode, so the state keeps its never-measured maxValue (Int.MAX_VALUE), not a
            // real scroll range; a pane that scrolled sideways would report a range in 1 until Int.MAX_VALUE.
            val scroll = state.logViewerScrollStateStore.scrollState("$tabId:$pane")
            assertTrue(scroll.maxValue !in 1 until Int.MAX_VALUE, "$pane pane scrolls sideways (range ${scroll.maxValue})")
            assertEquals(0, scroll.value)

            val row = rule.onNodeWithTag("log-row-$tabId:$pane-$entryId").getUnclippedBoundsInRoot()
            assertTrue(row.left.value >= 0f && row.right.value <= 600f, "$pane row $row sticks out of the 600 dp tile")
            val layout = rowTextLayout(tabId, pane, entryId, "log message")
            assertTrue(layout.lineCount > 1, "$pane row is one line, so it did not wrap")
            val message = layout.layoutInput.text.text.indexOf("log message")
            assertTrue(message >= 0)
            val box = layout.getBoundingBox(message)
            assertTrue(box.right <= layout.size.width, "$pane message ends at ${box.right} past the text width ${layout.size.width}")
            assertTrue(layout.getLineLeft(layout.lineCount - 1) > 0f, "$pane continuation lines hang at the left edge")

            val header = rule.onNode(
                hasTestTag("colheader-profile-text") and hasAnyAncestor(hasTestTag("log-column-header-$tabId:$pane")),
                useUnmergedTree = true,
            ).getUnclippedBoundsInRoot()
            assertTrue(header.right.value <= 600f, "$pane header $header sticks out of the 600 dp tile")
            assertTrue((header.bottom - header.top).value > 2 * 12f, "$pane header is one line, so it did not wrap")

            // The header hangs under the same column as its rows and gets the same text width. (Its lines need not break where the
            // rows' do: its labels are shorter than the padded columns under them.)
            val headerLayout = headerTextLayout(tabId, pane)
            assertEquals(layout.size.width, headerLayout.size.width, "$pane header and rows have different text widths")
            assertEquals(headerLayout.getLineLeft(1), layout.getLineLeft(1), 0.5f, "$pane header and row hang at different indents")
        }
    }

    /** Three 14-character columns, then the message: the message starts [SQUEEZE_MESSAGE_START] characters into the row. */
    private fun squeezeProfile(): Pair<LogFormatProfile, String> {
        val attributes = List(3) { index ->
            LogFormatAttribute(id = "field_$index", label = "Field $index", source = "field_$index", visibleByDefault = true)
        }
        val profile = LogFormatProfile(
            id = "squeeze-columns", name = "Squeeze columns", engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "message"),
            visibleColumns = listOf(LogFieldRole.MESSAGE),
            attributes = attributes,
        )
        val header = (attributes.map { it.source } + "message").joinToString(",")
        val data = (listOf("a", "b", "c").map { it.repeat(SQUEEZE_CELL_CHARS) } + SQUEEZE_MESSAGE).joinToString(",")
        return profile to "$header\n$data"
    }

    @Test
    fun aMessageSqueezedIntoTheLastCharactersMovesToItsOwnLineAndCopiesUnchanged() {
        val state = newState()
        state.settings = state.settings.copy(autoLogRowWrap = true)
        val (profile, data) = squeezeProfile()
        val tabId = openProfileLog(state, profile, data)
        var tileWidth by mutableStateOf(1000.dp)
        state.annotationVisible = false
        rule.setContent {
            Box(Modifier.size(tileWidth, 760.dp)) { state.tab(tabId)?.let { FileView(state, it) } }
        }
        rule.waitForIdle()
        val entryId = requireNotNull(state.tab(tabId)).logData.single().id
        rule.waitUntil(LOAD_TIMEOUT_MS) {
            rule.onAllNodesWithTag("log-row-$tabId:main-$entryId").fetchSemanticsNodes().isNotEmpty()
        }

        // A wide tile holds the whole row on one line, with no break in it.
        val wide = rowTextLayout(tabId, "main", entryId, "Configuration")
        val expected = wide.layoutInput.text.text
        assertEquals(1, wide.lineCount, "text=[$expected] width=${wide.size.width}")
        assertTrue('\n' !in expected, "a row that fits has no break: $expected")
        assertEquals(SQUEEZE_MESSAGE_START, expected.indexOf(SQUEEZE_MESSAGE))
        val advancePx = wide.getBoundingBox(0).let { it.right - it.left }

        // Narrow the tile until about 12 characters remain after the message column.
        walkToFieldChars(
            target = (SQUEEZE_MESSAGE_START + 12).toFloat(),
            fieldChars = { rowTextLayout(tabId, "main", entryId, "Configuration").size.width / advancePx },
            advancePx = advancePx,
            shrink = { tileWidth -= it },
        )
        rule.waitUntil(LOAD_TIMEOUT_MS) { rowTextLayout(tabId, "main", entryId, "Configuration").layoutInput.text.text.contains('\n') }
        val narrow = rowTextLayout(tabId, "main", entryId, "Configuration")
        val shown = narrow.layoutInput.text.text
        val messageStart = shown.indexOf(SQUEEZE_MESSAGE)
        assertEquals('\n', shown[messageStart - 1], "the message starts a line: $shown")
        assertEquals(1, narrow.getLineForOffset(messageStart), "the message is on the second line")
        assertEquals(messageStart, narrow.getLineStart(1))
        assertEquals(expected, stripVisualWrapBreaks(shown), "copying gives the row text as before")
        // Continuation lines, the message line included, hang after the first column (the room left after the message's own is 12).
        val secondColumn = shown.indexOf("b".repeat(SQUEEZE_CELL_CHARS))
        assertEquals(narrow.getBoundingBox(secondColumn).left, narrow.getBoundingBox(messageStart).left, 1f)
        assertEquals(0f, narrow.getLineLeft(0), 0.5f)

        // The header's MESSAGE label moves with the message: its own line, over the message.
        val header = headerTextLayout(tabId, "main")
        val headerShown = header.layoutInput.text.text
        val label = headerShown.indexOf("MESSAGE")
        assertEquals('\n', headerShown[label - 1], "MESSAGE starts a line of the header: $headerShown")
        assertEquals(1, header.getLineForOffset(label))
        assertEquals(narrow.getBoundingBox(messageStart).left, header.getBoundingBox(label).left, 1f)
        assertEquals(header.getBoundingBox(headerShown.indexOf("FIELD\u00A01")).left, narrow.getBoundingBox(secondColumn).left, 1f)
    }

    /**
     * Changes the tile width by [shrink] (positive narrows) until [fieldChars] reads about [target] characters. The text field's share
     * of the tile is not one to one, so it walks there by what the row or header itself measures.
     */
    private fun walkToFieldChars(target: Float, fieldChars: () -> Float, advancePx: Float, shrink: (androidx.compose.ui.unit.Dp) -> Unit) {
        repeat(MAX_NARROWING_STEPS) {
            val chars = fieldChars()
            if (kotlin.math.abs(chars - target) > FIELD_TOLERANCE_CHARS) {
                shrink(with(rule.density) { ((chars - target) * advancePx).toDp() })
                rule.waitForIdle()
            }
        }
        assertTrue(kotlin.math.abs(fieldChars() - target) <= FIELD_TOLERANCE_CHARS, "the field is ${fieldChars()} characters wide, not about $target")
    }

    /** An access log: no message, so the last column (Bytes sent) is as wide as its values and its label is wider. */
    private fun httpProfile(): Pair<LogFormatProfile, String> {
        val labels = listOf("Host" to "host", "HTTP method" to "method", "HTTP status" to "status", "Bytes sent" to "bytes")
        val profile = LogFormatProfile(
            id = "http-columns", name = "HTTP columns", engine = LogFormatEngine.CSV,
            fieldMappings = mapOf(LogFieldRole.TIMESTAMP to "time"),
            visibleColumns = listOf(LogFieldRole.TIMESTAMP),
            attributes = labels.map { (label, source) -> LogFormatAttribute(id = "x_$source", label = label, source = source, visibleByDefault = true) },
        )
        return profile to "time,host,method,status,bytes\n2026-11-20T10:00:00Z,10.0.0.7,GET,200,512"
    }

    private fun openHttpLog(state: AppState, tileWidth: () -> androidx.compose.ui.unit.Dp): Triple<String, Int, Float> {
        state.settings = state.settings.copy(autoLogRowWrap = true)
        val (profile, data) = httpProfile()
        val tabId = openProfileLog(state, profile, data)
        state.annotationVisible = false
        rule.setContent { Box(Modifier.size(tileWidth(), 760.dp)) { state.tab(tabId)?.let { FileView(state, it) } } }
        rule.waitForIdle()
        val entryId = requireNotNull(state.tab(tabId)).logData.single().id
        rule.waitUntil(LOAD_TIMEOUT_MS) {
            rule.onAllNodesWithTag("log-row-$tabId:main-$entryId").fetchSemanticsNodes().isNotEmpty()
        }
        val advancePx = headerTextLayout(tabId, "main").getBoundingBox(0).let { it.right - it.left }
        return Triple(tabId, entryId, advancePx)
    }

    @Test
    fun aNarrowHttpHeaderBreaksOnlyBetweenColumnsSoEachLabelStaysWhole() {
        val state = newState()
        var tileWidth by mutableStateOf(1000.dp)
        val (tabId, entryId, advancePx) = openHttpLog(state) { tileWidth }
        val header = headerText(tabId, "main")
        assertTrue("HTTP\u00A0METHOD" in header && "BYTES\u00A0SENT" in header, "the labels' words are joined: $header")

        // A tile about 45 characters wide: the five columns (68 characters) cannot share one line.
        walkToFieldChars(
            target = 45f,
            fieldChars = { headerTextLayout(tabId, "main").size.width / advancePx },
            advancePx = advancePx,
            shrink = { tileWidth -= it },
        )
        val layout = headerTextLayout(tabId, "main")
        val shown = layout.layoutInput.text.text
        assertTrue(layout.lineCount > 1, "the header wraps: $shown")
        for (label in listOf("HTTP\u00A0METHOD", "HTTP\u00A0STATUS", "BYTES\u00A0SENT")) {
            val start = shown.indexOf(label)
            assertTrue(start >= 0, "$label is in the header: $shown")
            assertEquals(layout.getLineForOffset(start), layout.getLineForOffset(start + label.length - 1), "$label is split between lines")
        }
        // Every label starts on the same line and at the same x as the values of its column in the row.
        val row = rowTextLayout(tabId, "main", entryId, "GET")
        val rowText = row.layoutInput.text.text
        assertEquals(row.lineCount, layout.lineCount, "header and row break into different line counts: [$shown] / [$rowText]")
        var rowFrom = 0
        val columns = listOf(
            "TIMESTAMP" to "", "HOST" to "10.0.0.7", "HTTP\u00A0METHOD" to "GET", "HTTP\u00A0STATUS" to "200", "BYTES\u00A0SENT" to "512",
        )
        for ((label, value) in columns) {
            val labelAt = shown.indexOf(label)
            val valueAt = if (value.isEmpty()) 0 else rowText.indexOf(value, rowFrom).also { rowFrom = it }
            assertEquals(
                row.getLineForOffset(valueAt), layout.getLineForOffset(labelAt),
                "$label is on another line than its value: [$shown] / [$rowText]",
            )
            assertEquals(row.getBoundingBox(valueAt).left, layout.getBoundingBox(labelAt).left, 1f, "$label is not over its value: [$shown] / [$rowText]")
        }
        // The no-break space is drawn at the same advance as every other character, so columns still line up.
        val gap = shown.indexOf('\u00A0')
        assertEquals(advancePx, layout.getBoundingBox(gap).let { it.right - it.left }, 0.5f)
        // The rows keep the text the user sees and copies.
        assertTrue(rowTextLayout(tabId, "main", entryId, "GET").layoutInput.text.text.contains("GET"))
    }

    /** The built-in HTTP Common/Combined format on sample lines: the real column set (host, method, path, status, bytes) and widths. */
    @Test
    fun theBuiltInHttpHeaderLinesUpWithItsRowsOnEveryLineInANarrowTile() {
        val state = newState()
        state.settings = state.settings.copy(autoLogRowWrap = true)
        val lines = listOf(
            "192.0.2.10 - sample [16/Nov/2026:12:00:00 +0000] \"GET /checkout?request=req-001 HTTP/1.1\" 200 100",
            "192.0.2.11 - sample [16/Nov/2026:12:00:01 +0000] \"POST /checkout?request=req-002 HTTP/1.1\" 200 117",
        )
        val source = File(requireNotNull(tempDir), "http.access_log").apply { writeText(lines.joinToString("\n") + "\n") }
        val tabId = requireNotNull(state.openFileWithLogFormatProfile(source, "http-common-combined"))
        rule.waitUntil(LOAD_TIMEOUT_MS) { state.tab(tabId)?.contentState == com.indagium.model.TabContentState.LOADED }
        var tileWidth by mutableStateOf(1000.dp)
        state.annotationVisible = false
        // The Message details tile would take a share of the width (it does in the app); the test window is smaller than the review one.
        state.hideLogViewTile(LogViewSectionId.MESSAGE_DETAILS)
        rule.setContent { Box(Modifier.size(tileWidth, 760.dp)) { state.tab(tabId)?.let { FileView(state, it) } } }
        rule.waitForIdle()
        val entryId = requireNotNull(state.tab(tabId)).logData.first().id
        rule.waitUntil(LOAD_TIMEOUT_MS) {
            rule.onAllNodesWithTag("log-row-$tabId:main-$entryId").fetchSemanticsNodes().isNotEmpty()
        }
        val advancePx = headerTextLayout(tabId, "main").getBoundingBox(0).let { it.right - it.left }
        // About 50 characters, as the 1115 px review window gives the log tile.
        walkToFieldChars(
            target = 50f,
            fieldChars = { headerTextLayout(tabId, "main").size.width / advancePx },
            advancePx = advancePx,
            shrink = { tileWidth -= it },
        )
        val header = headerTextLayout(tabId, "main")
        val shown = header.layoutInput.text.text
        val row = rowTextLayout(tabId, "main", entryId, "GET")
        val rowText = row.layoutInput.text.text
        // HTTP METHOD would end at column 51 of a 50-character field (GET fits, its 11-character label does not): the one label
        // that is cut, at the field edge, and every other label is whole.
        assertTrue(shown.lines().first().endsWith("HTTP\u00A0METH…"), "the overrunning label ends in an ellipsis: [$shown]")
        assertEquals(2, shown.lines().size, "[$shown]")
        assertEquals(2, row.lineCount, "the row wraps once: [$rowText]")
        assertEquals(row.lineCount, header.lineCount, "header and row break into different line counts: [$shown] / [$rowText]")
        val columns = listOf(
            "TIMESTAMP" to "16/Nov/2026", "HOST" to "192.0.2.10", "HTTP\u00A0METH" to "GET", "HTTP\u00A0PATH" to "/checkout?request=req-001",
            "STATUS" to "200", "BYTES\u00A0SENT" to "100",
        )
        var rowFrom = 0
        for ((label, value) in columns) {
            val labelAt = shown.indexOf(label)
            assertTrue(labelAt >= 0, "$label is in the header: [$shown]")
            val valueAt = rowText.indexOf(value, rowFrom).also { rowFrom = it }
            assertEquals(
                row.getLineForOffset(valueAt), header.getLineForOffset(labelAt),
                "$label is on another line than its value: [$shown] / [$rowText]",
            )
            assertEquals(row.getBoundingBox(valueAt).left, header.getBoundingBox(labelAt).left, 1f, "$label is not over $value: [$shown] / [$rowText]")
        }
    }

    @Test
    fun aHeaderWhoseRowsFitStaysOnOneLineAndEndsItsLongLastLabelWithAnEllipsis() {
        val state = newState()
        var tileWidth by mutableStateOf(1000.dp)
        val (tabId, entryId, advancePx) = openHttpLog(state) { tileWidth }

        // Rows are 61 characters wide (the last column holds "512"), the header 68 (its last label is "BYTES SENT").
        walkToFieldChars(
            target = 64f,
            fieldChars = { headerTextLayout(tabId, "main").size.width / advancePx },
            advancePx = advancePx,
            shrink = { tileWidth -= it },
        )
        val row = rowTextLayout(tabId, "main", entryId, "GET")
        assertEquals(1, row.lineCount, "the row fits: ${row.layoutInput.text.text}")
        val layout = headerTextLayout(tabId, "main")
        assertEquals(1, layout.lineCount, "the header stays on one line: ${layout.layoutInput.text.text}")
        val text = layout.layoutInput.text.text
        // Skia does not report an ellipsis through isLineEllipsized; the line simply stops short of the full text, at the overrun.
        val visibleEnd = layout.getLineEnd(0, visibleEnd = true)
        assertTrue(visibleEnd < text.length, "its last label is cut short: visible $visibleEnd of ${text.length}")
        assertTrue(visibleEnd > text.indexOf("BYTES"), "only the last label is cut: visible $visibleEnd, label at ${text.indexOf("BYTES")}")
    }

    @Test
    fun aNarrowTileDropsPidAndTidFromBothTheHeaderAndTheRows() {
        val state = newState()
        val (profile, data) = pidTidProfile()
        val tabId = openProfileLog(state, profile, data)
        state.upTab(tabId) { it.copy(showUnfiltered = true) }
        showFileView(state, tabId, width = 400.dp, notes = false)

        val entryId = requireNotNull(state.tab(tabId)).logData.single().id
        for (pane in listOf("main", "original")) {
            rule.waitUntil(LOAD_TIMEOUT_MS) {
                rule.onAllNodesWithTag("log-row-$tabId:$pane-$entryId").fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()
            val header = headerText(tabId, pane)
            assertTrue("PID" !in header && "TID" !in header, "$pane header names PID/TID in a narrow tile: $header")
            assertTrue("TIMESTAMP" in header && "MESSAGE" in header, header)
            val row = rowTextLayout(tabId, pane, entryId, "worker started").layoutInput.text.text
            assertTrue("4100" !in row && "4200" !in row, "$pane row shows PID/TID in a narrow tile: $row")
        }
    }

    @Test
    fun aWideTileKeepsPidAndTidInBothTheHeaderAndTheRows() {
        val state = newState()
        val (profile, data) = pidTidProfile()
        val tabId = openProfileLog(state, profile, data)
        state.upTab(tabId) { it.copy(showUnfiltered = true) }
        showFileView(state, tabId, width = 1400.dp, notes = false)

        val entryId = requireNotNull(state.tab(tabId)).logData.single().id
        for (pane in listOf("main", "original")) {
            rule.waitUntil(LOAD_TIMEOUT_MS) {
                rule.onAllNodesWithTag("log-row-$tabId:$pane-$entryId").fetchSemanticsNodes().isNotEmpty()
            }
            rule.waitForIdle()
            val header = headerText(tabId, pane)
            assertTrue("PID" in header && "TID" in header, "$pane header lost PID/TID in a wide tile: $header")
            val row = rowTextLayout(tabId, pane, entryId, "worker started").layoutInput.text.text
            assertTrue("4100" in row && "4200" in row, "$pane row lost PID/TID in a wide tile: $row")
            // The header and the rows were padded to the same column widths, so the message label sits over the message.
            assertEquals(header.indexOf("MESSAGE"), row.indexOf("worker started"), "$pane header and row columns drifted apart")
        }
    }

    @Test
    fun unsupportedFieldsHideTheirControlsWhileSearchNotesAndExportStayAvailable() {
        val state = newState()
        val profile = LogFormatProfile(
            id = "no-optional-fields",
            name = "No optional fields",
            engine = LogFormatEngine.JSON_LINES,
            fieldMappings = mapOf(
                LogFieldRole.COMPONENT to "logger",
                LogFieldRole.MESSAGE to "message",
            ),
            visibleColumns = listOf(LogFieldRole.MESSAGE),
        )
        val tabId = openProfileLog(
            state,
            profile,
            """{"logger":"api","message":"ready"}""" + "\n" +
                """{"logger":"api","message":"waiting"}""",
        )
        val tab = requireNotNull(state.tab(tabId))
        val capabilities = state.logCapabilitiesForTab(tab)
        assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.getValue(LogCapability.SEVERITY_FILTER).availability)
        assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.getValue(LogCapability.DELTA_TIME).availability)
        assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.getValue(LogCapability.PID_THREAD_FILTER).availability)
        assertEquals(CapabilityAvailability.AVAILABLE, capabilities.getValue(LogCapability.TEXT_SEARCH).availability)
        assertEquals(CapabilityAvailability.AVAILABLE, capabilities.getValue(LogCapability.NOTES).availability)
        assertEquals(CapabilityAvailability.AVAILABLE, capabilities.getValue(LogCapability.EXPORT).availability)

        showFileView(state, tabId)

        assertTrue(rule.onAllNodes(hasText("Log level", substring = true)).fetchSemanticsNodes().isEmpty())
        // No process banner inside the component filter, which still works: Format details lists the process filter as unavailable.
        rule.onAllNodes(hasText("Process filtering unavailable", substring = true)).fetchSemanticsNodes()
            .also { assertTrue(it.isEmpty()) }
        rule.onNodeWithTag("filter-panel-tags-input").performTextInput("api")
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasTestTag("filter-panel-tags-tag-candidate-0")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            rule.onAllNodes(hasTestTag("filter-panel-tags-tag-candidate-0-pid"))
                .fetchSemanticsNodes().isEmpty(),
        )
        rule.onNodeWithTag("log-toolbar-time-delta").assertIsNotEnabled()
        rule.onNodeWithTag("log-toolbar-search").assertIsEnabled()
        rule.onNodeWithTag("log-toolbar-export").assertIsEnabled()
        rule.onNodeWithTag("notes-toolbar").assertExists()
        recordScreenshot("capabilities-unavailable")
    }

    @Test
    fun partialSeverityRemainsUsableAndHiddenTimestampStillEnablesTimeDelta() {
        val state = newState()
        val profile = LogFormatProfile(
            id = "partial-severity-hidden-time",
            name = "Partial severity with hidden time",
            engine = LogFormatEngine.JSON_LINES,
            timestampKind = ProfileTimestampKind.ISO_8601,
            fieldMappings = mapOf(
                LogFieldRole.TIMESTAMP to "timestamp",
                LogFieldRole.SEVERITY to "level",
                LogFieldRole.MESSAGE to "message",
            ),
            visibleColumns = listOf(LogFieldRole.MESSAGE),
        )
        val tabId = openProfileLog(
            state,
            profile,
            """{"timestamp":"2026-11-16T10:00:00Z","level":"ERROR","message":"failed"}""" + "\n" +
                """{"timestamp":"2026-11-16T10:00:01Z","message":"continued"}""",
        )
        val tab = requireNotNull(state.tab(tabId))
        val capabilities = state.logCapabilitiesForTab(tab)
        assertEquals(CapabilityAvailability.PARTIAL, capabilities.getValue(LogCapability.SEVERITY_FILTER).availability)
        assertEquals(CapabilityAvailability.AVAILABLE, capabilities.getValue(LogCapability.DELTA_TIME).availability)
        assertEquals(CapabilityAvailability.UNAVAILABLE, capabilities.getValue(LogCapability.PROCESS_MAP).availability)

        showFileView(state, tabId)

        assertTrue(rule.onAllNodes(hasText("Log level", substring = true)).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            rule.onAllNodes(hasText("1 of 2 records have severity values.", substring = true))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        rule.onNodeWithTag("log-toolbar-time-delta").assertIsEnabled()
        recordScreenshot("capabilities-partial-time")
    }

    @Test
    fun unavailableComponentFilteringKeepsMessageSearchAndOnlyOffersUnscopedRulesInFilterBar() {
        val state = newState()
        val profile = LogFormatProfile(
            id = "message-without-components",
            name = "Message without components",
            engine = LogFormatEngine.JSON_LINES,
            fieldMappings = mapOf(LogFieldRole.MESSAGE to "message"),
            visibleColumns = listOf(LogFieldRole.MESSAGE),
        )
        val tabId = openProfileLog(state, profile, """{"message":"service ready"}""")
        state.upTab(tabId) {
            it.copy(filterNormalizationNotice = "Unsupported component criteria were removed from the saved filter.")
        }
        state.filterBarVisible = true

        showFileView(state, tabId)

        assertTrue(rule.onAllNodes(hasTestTag("filter-bar-tags-input")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag("filter-bar-component-unavailable")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(
            rule.onAllNodes(hasText("Unsupported component criteria were removed", substring = true))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        rule.onNodeWithTag("filter-bar-message-input").assertExists()
        rule.onNodeWithTag("filter-bar-message-input").performTextInput("ready")
        rule.onNodeWithTag("filter-bar-message-input").performKeyInput { pressKey(Key.Enter) }
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasTestTag("filter-bar-message-scope-chooser")).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(rule.onAllNodes(hasTestTag("filter-bar-message-scope-all")).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(hasTestTag("filter-bar-message-scope-input")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag("filter-bar-message-scope-unavailable")).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun unavailablePidFilteringHidesCandidateActionsWhileTagAndMessageSearchStayUsable() {
        val state = newState()
        val profile = LogFormatProfile(
            id = "component-without-pid-filter",
            name = "Component without PID filter",
            engine = LogFormatEngine.JSON_LINES,
            fieldMappings = mapOf(
                LogFieldRole.COMPONENT to "logger",
                LogFieldRole.MESSAGE to "message",
                LogFieldRole.PROCESS_ID to "pid",
            ),
            visibleColumns = listOf(LogFieldRole.COMPONENT, LogFieldRole.MESSAGE),
        )
        val tabId = openProfileLog(
            state,
            profile,
            """{"logger":"api","message":"ready","pid":120}""" + "\n" +
                """{"logger":"api","message":"waiting","pid":121}""",
        )
        val unavailable = LogCapabilityStatus(CapabilityAvailability.UNAVAILABLE, "PID filtering is disabled for this log.")
        state.upTab(tabId) { tab ->
            tab.copy(logCapabilities = tab.logCapabilities + (LogCapability.PID_THREAD_FILTER to unavailable))
        }
        state.filterBarVisible = true

        showFileView(state, tabId)

        rule.onNodeWithTag("filter-bar-tags-input").performTextInput("api")
        rule.waitUntil(5_000) {
            rule.onAllNodes(hasTestTag("filter-bar-tags-candidate-0")).fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithTag("filter-bar-tags-input").performKeyInput { pressKey(Key.DirectionDown) }
        rule.waitForIdle()
        assertTrue(rule.onAllNodes(hasTestTag("filter-bar-tags-candidate-0-pid")).fetchSemanticsNodes().isEmpty())
        assertTrue(rule.onAllNodes(hasTestTag("filter-bar-pid-capability")).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithTag("filter-bar-message-input").assertExists()
    }
}
