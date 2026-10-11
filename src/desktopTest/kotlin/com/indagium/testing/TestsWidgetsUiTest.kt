package com.indagium.testing

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.LocalTestsUi
import com.indagium.ui.TestsBanner
import com.indagium.ui.TestsBannerView
import com.indagium.ui.TestsConfirmDialog
import com.indagium.ui.TestsListRow
import com.indagium.ui.TestsMenuItem
import com.indagium.ui.TestsOverflowMenu
import com.indagium.ui.TestsSplitButton
import com.indagium.ui.TestsSummaryChip
import com.indagium.ui.TestsTitleField
import com.indagium.ui.TestsUi
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The Phase-1 building blocks of the Tests workspace (menus, split button, title field, chip, row, dialog frame) in isolation. */
@OptIn(ExperimentalTestApi::class)
class TestsWidgetsUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-widgets-ui").toFile()
    private val state = AppState(
        autosaveFile = File(dir, "state.cache"),
        autoExportNotes = false,
        notesDir = File(dir, "notes"),
        archiveCacheDir = File(dir, "archive"),
        customCommandsDir = File(dir, "commands"),
        controlTokenFile = File(dir, "token"),
        sourceIndexFile = File(dir, "source-index"),
        testingDir = File(dir, "testing"),
    )

    @After
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun setWidgetContent(content: @Composable () -> Unit) {
        rule.setContent {
            val scope = rememberCoroutineScope()
            val ui = remember { TestsUi(state, state.testsView, FocusRequester(), scope) }
            CompositionLocalProvider(LocalTestsUi provides ui) { content() }
        }
    }

    @Test
    fun theOverflowMenuOpensRunsAnItemAndClosesAgain() {
        val clicked = mutableListOf<String>()
        setWidgetContent {
            TestsOverflowMenu(
                listOf(
                    TestsMenuItem("Duplicate", { clicked += "duplicate" }, kbd = "⌘D"),
                    TestsMenuItem("Delete…", { clicked += "delete" }, danger = true, dividerBefore = true),
                    TestsMenuItem("Export…", { clicked += "export" }, enabled = false, disabledHint = "Nothing to export"),
                ),
            )
        }

        rule.onNodeWithText("Duplicate").assertDoesNotExist()
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.onNodeWithText("Duplicate").assertExists()
        rule.onNodeWithText("⌘D").assertExists()
        rule.onNodeWithText("Export…").performClick()
        assertTrue(clicked.isEmpty(), "a disabled item must not run")

        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        assertEquals(listOf("delete"), clicked)
        rule.onNodeWithText("Delete…").assertDoesNotExist()
    }

    @Test
    fun theSplitButtonRunsTheLabelActionOrTheMenuEntry() {
        val clicked = mutableListOf<String>()
        setWidgetContent {
            TestsSplitButton("+ Add step", { clicked += "add" }, listOf(TestsMenuItem("Record from device…", { clicked += "record" })))
        }

        rule.onNodeWithText("+ Add step").performClick()
        assertEquals(listOf("add"), clicked)

        rule.onNodeWithContentDescription("+ Add step options").performClick()
        rule.onNodeWithText("Record from device…").performClick()
        rule.waitForIdle()
        assertEquals(listOf("add", "record"), clicked)
    }

    @Test
    fun theTitleFieldCommitsAnEditAfterTheDebounce() {
        var committed: String? = null
        setWidgetContent {
            TestsTitleField("Smoke", { value ->
                committed = value
                StoreResult.Ok(Unit)
            }, contentDescription = "Suite name")
        }

        rule.onNode(hasSetTextAction()).performTextReplacement("Smoke tests")
        rule.waitUntil(timeoutMillis = 5_000) { committed != null }
        assertEquals("Smoke tests", committed)
    }

    @Test
    fun aListRowAndASummaryChipReportClicks() {
        var rowClicks = 0
        var chipClicks = 0
        setWidgetContent {
            TestsListRow(selected = true, onClick = { rowClicks++ }) { Text("Login flow") }
            TestsSummaryChip("Preconditions", "Device unlocked", empty = false, selected = false, onClick = { chipClicks++ })
        }

        rule.onNodeWithText("Login flow").performClick()
        rule.onNodeWithText("Device unlocked").performClick()
        assertEquals(1, rowClicks)
        assertEquals(1, chipClicks)
    }

    @Test
    fun aNonDismissibleBannerHasNoCloseButton() {
        setWidgetContent {
            TestsBannerView(TestsBanner("Library is read-only", isError = true), dismissible = false) { }
            TestsBannerView(TestsBanner("Imported with a warning", isError = false)) { }
        }

        rule.onNodeWithText("Library is read-only").assertExists()
        assertEquals(1, rule.onAllNodesWithText("×").fetchSemanticsNodes().size, "only the dismissible banner shows a ×")
    }

    @Test
    fun theConfirmDialogShowsItsMessageAndRunsTheConfirmAction() {
        var confirmed = 0
        var dismissed = 0
        setWidgetContent {
            TestsConfirmDialog("Delete suite?", "This removes every case in it.", "Delete", { confirmed++ }, { dismissed++ })
        }

        rule.waitUntilAtLeastOneExists(hasText("Delete suite?"))
        rule.onNodeWithText("This removes every case in it.").assertExists()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        assertEquals(1, confirmed)
        assertEquals(1, dismissed)
    }
}
