package com.indagium

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.dp
import com.indagium.model.CapabilityAvailability
import com.indagium.model.LogCapability
import com.indagium.model.LogCapabilityStatus
import com.indagium.model.LogFieldRole
import com.indagium.model.LogFormatAttribute
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogFormatProfile
import com.indagium.model.ProfileTimestampKind
import com.indagium.ui.AppState
import com.indagium.ui.FileView
import com.indagium.ui.logCapabilitiesForTab
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

    private fun showFileView(state: AppState, tabId: String) {
        state.annotationVisible = true
        rule.setContent {
            Box(Modifier.size(1180.dp, 760.dp)) {
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
        val data = (attributes.map { "a".repeat(38) } + "log message").joinToString(",")
        val tabId = openProfileLog(state, profile, "$header\n$data")
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
