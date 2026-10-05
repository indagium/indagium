package com.indagium.testing

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.waitUntilAtLeastOneExists
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

    private fun storeIssue(title: String) = (
        state.issueStore.create(
            IssueDraft(title, IssueSeverity.HIGH, listOf("smoke"), listOf("Open the app"), "Home", "Crash"),
            com.indagium.testing.model.IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-1", caseName = "Login", stepNumber = 2),
        ) as StoreResult.Ok
    ).value

    @Test
    fun theIssuesScreenListsStoredIssuesAndOpensOneWithItsMarkdown() {
        val issue = storeIssue("Login crashes")
        rule.setContent { TestsWorkspace(state) }
        state.testsView.nav = TestsNav.Issues

        rule.waitUntilAtLeastOneExists(hasText("Login crashes"), WAIT_MS)
        rule.onNodeWithText("Login · step 2 · run-1").assertExists()
        rule.onNodeWithText("Login crashes").performClick()
        rule.waitForIdle()

        assertEquals(issue.id, state.testsView.selectedIssueId)
        rule.waitUntilAtLeastOneExists(hasText("Copy Markdown"), WAIT_MS)
        rule.onNodeWithText("Add to notes").assertExists()
        rule.onNodeWithText("Open folder").assertExists()
        rule.onNodeWithText("Delete…").assertExists()
    }

    @Test
    fun theIssueDialogShowsTheTrackerDestinationDisabled() {
        val issue = storeIssue("Dialog issue")
        rule.setContent { TestsWorkspace(state) }
        state.testsView.issueDialog = IssueDialogTarget.Existing(issue.id)

        rule.waitUntilAtLeastOneExists(hasText("Send to".uppercase()).or(hasText("Send to")), WAIT_MS)
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
