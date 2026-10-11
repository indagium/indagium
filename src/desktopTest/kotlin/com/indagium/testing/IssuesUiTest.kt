package com.indagium.testing

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
import androidx.compose.ui.unit.dp
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.IssueDialogTarget
import com.indagium.ui.TestsNav
import com.indagium.ui.TestsWorkspace
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val WAIT_MS = 10_000L
private const val MENU_REOPEN_WAIT_MS = 300L

/** The Issues screen and the issue dialog composed against a real AppState. */
@OptIn(ExperimentalTestApi::class)
class IssuesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("issues-ui").toFile()
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

    private val runUuid = "7c0f2f3e-5a4b-4d36-9a21-0d5b7e1c9a44"

    private fun storeIssue(title: String, runId: String = runUuid) = (
        state.issueStore.create(
            IssueDraft(title, IssueSeverity.HIGH, listOf("smoke"), listOf("Open the app"), "Home", "Crash"),
            com.indagium.testing.model.IssueSource(runId, "lane-1", "suite-1", "case-1", "step-1", caseName = "Login", stepNumber = 2),
        ) as StoreResult.Ok
    ).value

    /**
     * The default test window (1024 dp) is too narrow for the side-by-side layout, so the inspector stacks under the list;
     * [wide] is a 1300 dp window (look, do not click).
     */
    private fun openIssues(wide: Boolean = false) {
        state.testsView.nav = TestsNav.Issues
        if (wide) {
            rule.setContent { Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(1300.dp, 800.dp)) { TestsWorkspace(state) } }
        } else {
            rule.setContent { TestsWorkspace(state) }
        }
        rule.waitForIdle()
    }

    /** The nodes with [text] right of the nav (the nav tree does not list issues, but be explicit). */
    private fun inList(text: String) = rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().filter {
        it.boundsInRoot.left >= state.testsView.navWidthDp
    }

    private fun openIssueMenu() {
        Thread.sleep(MENU_REOPEN_WAIT_MS)
        rule.onNodeWithContentDescription("More actions").performClick()
        rule.waitForIdle()
    }

    @Test
    fun theListShowsAReadableOriginInsteadOfTheRunIdAndPreselectsTheFirstIssue() {
        val issue = storeIssue("Login crashes")
        openIssues()

        rule.waitUntilAtLeastOneExists(hasText("Login crashes"), WAIT_MS)
        assertTrue(inList("Login · step 2 · run").isNotEmpty(), "the origin line names the case, the step and the run's moment")
        assertTrue(rule.onAllNodesWithText(runUuid, substring = true).fetchSemanticsNodes().isEmpty(), "the raw run id is never shown")
        rule.waitUntil(WAIT_MS) { state.testsView.selectedIssueId == issue.id }
        // Preselected: its detail is already in the inspector beside the list.
        rule.waitUntilAtLeastOneExists(hasText("Edit…"), WAIT_MS)
        rule.onNodeWithText("Markdown").assertExists()
    }

    @Test
    fun inAWideWindowTheInspectorSitsBesideTheListAndFitsItsWidth() {
        storeIssue("Login crashes")
        state.testsView.inspectorWidthDp = 300f
        openIssues(wide = true)
        rule.waitUntilAtLeastOneExists(hasText("Edit…"), WAIT_MS)

        val edit = rule.onNodeWithText("Edit…").fetchSemanticsNode().boundsInRoot
        val row = rule.onAllNodesWithText("Login crashes")[0].fetchSemanticsNode().boundsInRoot
        assertTrue(edit.left >= 1300f - 300f - 1f && edit.right <= 1300f, "the Edit… button sits inside the 300dp inspector column: $edit")
        assertTrue(row.right <= edit.left + 1f, "the list row ends where the inspector starts: $row vs $edit")
    }

    @Test
    fun selectingARowShowsItsDetailInTheInspectorWhileTheListStaysVisible() {
        val first = storeIssue("Login crashes")
        val second = storeIssue("Checkout hangs")
        openIssues()
        rule.waitUntilAtLeastOneExists(hasText("Edit…"), WAIT_MS)

        val other = if (state.testsView.selectedIssueId == first.id) second else first
        val otherTitle = if (other.id == first.id) "Login crashes" else "Checkout hangs"
        assertTrue(inList(otherTitle).isNotEmpty())
        rule.onAllNodesWithText(otherTitle)[0].performClick()
        rule.waitForIdle()

        assertEquals(other.id, state.testsView.selectedIssueId)
        // The title is now in the list row and in the inspector header; the list is still there.
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText(otherTitle).fetchSemanticsNodes().size >= 2 }
        assertTrue(inList("Login crashes").isNotEmpty() && inList("Checkout hangs").isNotEmpty())
        rule.onNodeWithText("‹ Issues").assertDoesNotExist()
    }

    @Test
    fun anIssueSelectedElsewhereIsTheOneTheInspectorShows() {
        storeIssue("Login crashes")
        val wanted = storeIssue("Checkout hangs")
        state.testsView.selectedIssueId = wanted.id
        openIssues()

        rule.waitUntilAtLeastOneExists(hasText("Edit…"), WAIT_MS)
        assertEquals(wanted.id, state.testsView.selectedIssueId)
        rule.waitUntil(WAIT_MS) { rule.onAllNodesWithText("Checkout hangs").fetchSemanticsNodes().size >= 2 }
    }

    @Test
    fun editOpensTheIssueDialogAndTheOverflowMenuHoldsTheOtherActions() {
        val issue = storeIssue("Login crashes")
        openIssues()
        rule.waitUntilAtLeastOneExists(hasText("Edit…"), WAIT_MS)

        openIssueMenu()
        listOf("Copy Markdown", "Add to notes", "Open folder", "Delete…").forEach { rule.onNodeWithText(it).assertExists() }
        rule.onNodeWithText("Edit…").assertExists()

        rule.onNodeWithText("Edit…").performClick()
        rule.waitForIdle()
        assertEquals(IssueDialogTarget.Existing(issue.id), state.testsView.issueDialog)
    }

    @Test
    fun deleteAsksForConfirmationAndRemovesTheIssueAndSelectsTheNextOne() {
        val keep = storeIssue("Login crashes")
        val doomed = storeIssue("Checkout hangs")
        state.testsView.selectedIssueId = doomed.id
        openIssues()
        rule.waitUntilAtLeastOneExists(hasText("Edit…"), WAIT_MS)

        openIssueMenu()
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete this issue?").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(state.issueStore.load(doomed.id) != null, "nothing is deleted before the confirmation")

        openIssueMenu()
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitUntil(WAIT_MS) { state.issueStore.load(doomed.id) == null }
        rule.waitUntil(WAIT_MS) { state.testsView.selectedIssueId == keep.id }
        rule.waitUntilAtLeastOneExists(hasText("Edit…"), WAIT_MS)
    }

    @Test
    fun theIssueDialogShowsTheTrackerDestinationDisabled() {
        val issue = storeIssue("Dialog issue")
        rule.setContent { TestsWorkspace(state) }
        state.testsView.issueDialog = IssueDialogTarget.Existing(issue.id)

        rule.waitUntilAtLeastOneExists(hasText("Send to"), WAIT_MS)
        rule.onNodeWithText("Save draft").assertExists()
        rule.onNodeWithText("Link to case and re-check on next run").assertExists()
        assertTrue(
            rule.onAllNodesDisabledWith("Tracker"),
            "the Tracker destination is shown but cannot be chosen until a tracker can be configured",
        )
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesDisabledWith(label: String): Boolean =
        onAllNodes(isNotEnabled() and hasAnyDescendant(hasText(label))).fetchSemanticsNodes().isNotEmpty()
}
