package com.indagium.testing

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.pressKey
import com.indagium.edition.Edition
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.newStepId
import com.indagium.testing.store.StoreResult
import com.indagium.ui.AppState
import com.indagium.ui.CASE_CHIP_EMPTY
import com.indagium.ui.CaseChipValue
import com.indagium.ui.CaseField
import com.indagium.ui.RunDialogTarget
import com.indagium.ui.TestsWorkspace
import com.indagium.ui.caseChipValue
import org.junit.After
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The summary chips' text, derived from a case without any UI. */
class CaseChipValueTest {
    private fun chip(case: TestCase, field: CaseField) = caseChipValue(case, field)

    @Test
    fun anEmptyCaseShowsADashOnEveryChipExceptToolsWhichSaysAll() {
        val case = TestCase("c", "Empty")
        assertEquals(CaseChipValue(CASE_CHIP_EMPTY, empty = true), chip(case, CaseField.Preconditions))
        assertEquals(CaseChipValue(CASE_CHIP_EMPTY, empty = true), chip(case, CaseField.Instructions))
        assertEquals(CaseChipValue(CASE_CHIP_EMPTY, empty = true), chip(case, CaseField.Hooks))
        assertEquals(CaseChipValue(CASE_CHIP_EMPTY, empty = true), chip(case, CaseField.Examples))
        assertEquals(CaseChipValue("All", empty = false), chip(case, CaseField.Tools))
    }

    @Test
    fun textChipsShowTheFirstNonBlankLine() {
        val case = TestCase("c", "Text", preconditions = "\n  Device unlocked  \nWi-Fi on", instructions = "   ")
        assertEquals(CaseChipValue("Device unlocked", empty = false), chip(case, CaseField.Preconditions))
        assertEquals(CaseChipValue(CASE_CHIP_EMPTY, empty = true), chip(case, CaseField.Instructions))
    }

    @Test
    fun hooksShowSetupAndTeardownCountsAndToolsTheNumberAllowed() {
        val case = TestCase(
            "c", "Hooks",
            setup = listOf(HookItem.Shared("h1", "s1"), HookItem.Shared("h2", "s2")),
            teardown = listOf(HookItem.Shared("h3", "s3")),
            allowedTools = setOf("tap", "swipe", "screenshot"),
        )
        assertEquals(CaseChipValue("2 · 1", empty = false), chip(case, CaseField.Hooks))
        assertEquals(CaseChipValue("3 allowed", empty = false), chip(case, CaseField.Tools))
        assertEquals(CaseChipValue("0 · 1", empty = false), chip(case.copy(setup = emptyList()), CaseField.Hooks))
    }

    @Test
    fun examplesAreCountedAcrossAllSteps() {
        val case = TestCase(
            "c", "Examples",
            steps = listOf(
                TestStep("a", "One", examples = listOf(StepExample.ReferenceLog("e1", "line"), StepExample.GoldenScreenshot("e2", "x.png"))),
                TestStep("b", "Two"),
                TestStep("c", "Three", examples = listOf(StepExample.ReferenceLog("e3", "line"))),
            ),
        )
        assertEquals(CaseChipValue("3", empty = false), chip(case, CaseField.Examples))
    }
}

/** The case screen against a real AppState: header, Goal, summary chips with their inspector editors, and the Steps header menus. */
@OptIn(ExperimentalTestApi::class)
class TestsCaseScreenUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val dir: File = createTempDirectory("tests-case-screen-ui").toFile()
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

    private fun steps() = listOf(
        TestStep(newStepId(), "Open the app", examples = listOf(StepExample.ReferenceLog("ex1", "boot done", caption = "Boot log"))),
        TestStep(newStepId(), "Tap search"),
    )

    private fun seed(case: TestCase = TestCase("", "Search", steps = steps())): Pair<String, String> {
        val suiteId = (state.createTestSuite("Shop") as StoreResult.Ok).value.id
        val caseId = (state.createTestCase(suiteId, case) as StoreResult.Ok).value.id
        return suiteId to caseId
    }

    private fun open(suiteId: String, caseId: String) {
        rule.setContent { TestsWorkspace(state) }
        state.testsView.selectedSuiteId = suiteId
        state.testsView.selectedCaseId = caseId
        rule.waitForIdle()
    }

    private fun chip(label: String) = rule.onNode(hasText(label) and hasClickAction())

    private fun pressKey(key: Key) {
        rule.onRoot().performKeyInput { pressKey(key) }
        rule.waitForIdle()
    }

    @Test
    fun theBreadcrumbLinksBackToTheSuite() {
        val (suiteId, caseId) = seed()
        open(suiteId, caseId)

        rule.onNodeWithContentDescription("Back to suite Shop").performClick()
        rule.waitForIdle()

        assertNull(state.testsView.selectedCaseId)
        assertEquals(suiteId, state.testsView.selectedSuiteId)
        rule.onNodeWithText("Cases (1)").assertExists()
    }

    @Test
    fun theCaseNameGoalAndRunAreOnTheHeader() {
        val (suiteId, caseId) = seed(TestCase("", "Search", description = "Finds a product", steps = steps()))
        open(suiteId, caseId)

        rule.onNodeWithContentDescription("Case name").assertExists()
        rule.onNodeWithText("Goal").assertExists()
        rule.onNodeWithText("Finds a product").assertExists()

        rule.onNodeWithText("▶ Run case").performClick()
        rule.waitForIdle()
        assertEquals(RunDialogTarget(suiteId, caseId), state.testsView.runDialog)
    }

    @Test
    fun aLockedCaseShowsTheNoticeAndCannotBeRunOrDuplicated() {
        state.createTestSuite("First")
        val lockedSuiteId = (state.createTestSuite("Second") as StoreResult.Ok).value.id
        val caseId = (state.createTestCase(lockedSuiteId, TestCase("", "Locked", steps = steps())) as StoreResult.Ok).value.id
        assertTrue(state.editionService.setForDev(Edition.FREE))
        open(lockedSuiteId, caseId)

        assertTrue(rule.onAllNodesWithText("This suite is locked.", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(isNotEnabled() and hasAnyDescendant(hasText("▶ Run case"))).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithContentDescription("More case actions").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete…").assertExists()
        rule.onNodeWithText("Duplicate").performClick()
        rule.waitForIdle()
        assertEquals(1, state.testLibrary.suite(lockedSuiteId)!!.cases.size, "a locked case cannot be duplicated")
    }

    @Test
    fun theHeaderMenuDuplicatesTheCaseAndOpensTheCopy() {
        val (suiteId, caseId) = seed()
        open(suiteId, caseId)

        rule.onNodeWithContentDescription("More case actions").performClick()
        rule.onNodeWithText("Duplicate").performClick()
        rule.waitForIdle()

        val cases = state.testLibrary.suite(suiteId)!!.cases
        assertEquals(2, cases.size)
        assertEquals(cases.last().id, state.testsView.selectedCaseId, "the copy opens")
    }

    @Test
    fun theHeaderMenuDeletesTheCaseAfterConfirmation() {
        val (suiteId, caseId) = seed()
        open(suiteId, caseId)

        rule.onNodeWithContentDescription("More case actions").performClick()
        rule.onNodeWithText("Delete…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete case?").assertExists()
        assertEquals(1, state.testLibrary.suite(suiteId)!!.cases.size, "nothing is deleted before the confirmation")

        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()

        assertTrue(state.testLibrary.suite(suiteId)!!.cases.isEmpty())
        assertNull(state.testsView.selectedCaseId)
    }

    @Test
    fun theChipsSummariseTheCaseFields() {
        val (suiteId, caseId) = seed(
            TestCase(
                "", "Search", preconditions = "Device unlocked", steps = steps(),
                setup = listOf(HookItem.Shared("h1", "s1")), allowedTools = setOf("tap", "swipe"),
            ),
        )
        open(suiteId, caseId)

        rule.onNode(hasText("Preconditions") and hasText("Device unlocked") and hasClickAction()).assertExists()
        rule.onNode(hasText("Instructions") and hasText(CASE_CHIP_EMPTY) and hasClickAction()).assertExists()
        rule.onNode(hasText("Hooks") and hasText("1 · 0") and hasClickAction()).assertExists()
        rule.onNode(hasText("Tools") and hasText("2 allowed") and hasClickAction()).assertExists()
        rule.onNode(hasText("Examples") and hasText("1") and hasClickAction()).assertExists()
    }

    @Test
    fun eachChipOpensItsEditorInTheInspectorAndTheSecondClickClosesIt() {
        val (suiteId, caseId) = seed()
        open(suiteId, caseId)
        rule.onNodeWithText("Step 1 of 2").assertExists()

        chip("Preconditions").performClick()
        rule.waitForIdle()
        assertEquals(CaseField.Preconditions, state.testsView.caseInspectorField)
        rule.onNodeWithText("True before the first step").assertExists()
        rule.onNodeWithText("Step 1 of 2").assertDoesNotExist()
        rule.onNodeWithText("Done").assertExists()

        chip("Instructions").performClick()
        rule.waitForIdle()
        assertEquals(CaseField.Instructions, state.testsView.caseInspectorField)
        rule.onNodeWithText("Instructions for the agent").assertExists()
        rule.onNodeWithText("Hints that apply to every step").assertExists()

        chip("Hooks").performClick()
        rule.waitForIdle()
        assertEquals(CaseField.Hooks, state.testsView.caseInspectorField)
        rule.onNodeWithText("Setup hooks").assertExists()
        rule.onNodeWithText("Teardown hooks").assertExists()

        chip("Tools").performClick()
        rule.waitForIdle()
        assertEquals(CaseField.Tools, state.testsView.caseInspectorField)
        rule.onNodeWithText("Allowed tools").assertExists()
        rule.onNodeWithText("This case may use the default device tools and permitted library scripts.").assertExists()

        chip("Examples").performClick()
        rule.waitForIdle()
        assertEquals(CaseField.Examples, state.testsView.caseInspectorField)
        rule.onNodeWithText("Boot log").assertExists()

        // The same chip again closes its editor and the step comes back.
        rule.onAllNodes(hasText("Examples") and hasClickAction())[0].performClick()
        rule.waitForIdle()
        assertNull(state.testsView.caseInspectorField)
        rule.onNodeWithText("Step 1 of 2").assertExists()
    }

    @Test
    fun doneEscAndSelectingAStepEachCloseTheChipEditor() {
        val (suiteId, caseId) = seed()
        val ids = state.testLibrary.findCase(caseId)!!.case.steps.map { it.id }
        open(suiteId, caseId)

        chip("Hooks").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Done").performClick()
        rule.waitForIdle()
        assertNull(state.testsView.caseInspectorField, "Done")
        rule.onNodeWithText("Step 1 of 2").assertExists()

        chip("Hooks").performClick()
        rule.waitForIdle()
        pressKey(Key.Escape)
        assertNull(state.testsView.caseInspectorField, "Esc")

        chip("Hooks").performClick()
        rule.waitForIdle()
        pressKey(Key.DirectionDown)
        assertNull(state.testsView.caseInspectorField, "the arrow keys select a step")
        assertEquals(ids[1], state.testsView.selectedStepId)

        chip("Hooks").performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Tap search", substring = true) and hasClickAction()).performScrollTo().performClick()
        rule.waitForIdle()
        assertNull(state.testsView.caseInspectorField, "clicking a row")
        rule.onNodeWithText("Step 2 of 2").assertExists()
    }

    @Test
    fun anExampleInTheExamplesEditorSelectsItsStepAndOpensItsExamplesSection() {
        val (suiteId, caseId) = seed()
        val ids = state.testLibrary.findCase(caseId)!!.case.steps.map { it.id }
        open(suiteId, caseId)
        state.testsView.selectedStepId = ids[1]
        rule.waitForIdle()

        chip("Examples").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Boot log").performClick()
        rule.waitForIdle()

        assertNull(state.testsView.caseInspectorField)
        assertEquals(ids[0], state.testsView.selectedStepId)
        assertTrue(state.testsView.examplesExpanded(ids[0], hasExamples = false), "an explicit open wins over the default")
        rule.onNodeWithText("Step 1 of 2").assertExists()
    }

    @Test
    fun anotherCaseOpensOnItsStepsNotOnTheLastChipEditor() {
        val (suiteId, caseId) = seed()
        val other = (state.createTestCase(suiteId, TestCase("", "Checkout", steps = steps())) as StoreResult.Ok).value.id
        open(suiteId, caseId)
        chip("Hooks").performClick()
        rule.waitForIdle()

        state.testsView.selectedCaseId = other
        rule.waitForIdle()

        assertNull(state.testsView.caseInspectorField)
        rule.onNodeWithText("Step 1 of 2").assertExists()
    }

    @Test
    fun addStepAddsABlankStepAndEveryAuthoringActionIsAVisibleButton() {
        val (suiteId, caseId) = seed()
        open(suiteId, caseId)

        rule.onNodeWithText("+ Add step").performClick()
        rule.waitForIdle()
        assertEquals(3, state.testLibrary.findCase(caseId)!!.case.steps.size)
        rule.onNodeWithText("Steps (3)").assertExists()

        // No split-button chevron and no ⋯ menu: the actions are on screen without opening anything.
        rule.onNodeWithContentDescription("+ Add step options").assertDoesNotExist()
        rule.onNodeWithContentDescription("More step actions").assertDoesNotExist()
        rule.onNodeWithText("Draft steps with AI…").assertExists()
        rule.onNodeWithText("Record from device…").assertExists()
        rule.onNodeWithText("Paste log lines as checks…").assertExists()
        rule.onNodeWithText("Improve steps with AI…").assertExists()
        rule.onNodeWithText("Insert shared sequence…").assertDoesNotExist()
        rule.onNodeWithText("Rewrite with AI from saved recording").assertDoesNotExist()
    }

    @Test
    fun theDraftButtonOpensTheDraftDialogAndSharedSequencesAreOfferedOnlyWhenTheLibraryHasThem() {
        val (suiteId, caseId) = seed()
        open(suiteId, caseId)
        rule.onNodeWithText("Insert shared sequence…").assertDoesNotExist()
        rule.onNodeWithText("Draft steps with AI…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Draft steps with AI").assertExists()
    }

    @Test
    fun aSharedSequenceCanBeInsertedFromItsButton() {
        val sequence = SharedStep(id = "", name = "Log in", steps = listOf(TestStep(newStepId(), "Enter the PIN")))
        check(state.createSharedStep(sequence) is StoreResult.Ok)
        val (suiteId, caseId) = seed()
        open(suiteId, caseId)

        rule.onNodeWithText("Insert shared sequence…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Insert shared sequence").assertExists()
        rule.onNodeWithText("Insert").performClick()
        rule.waitForIdle()

        val actions = state.testLibrary.findCase(caseId)!!.case.steps.map { it.action }
        assertTrue("Enter the PIN" in actions, "the shared sequence's steps were inserted: $actions")
    }

    @Test
    fun aCaseWithoutStepsDisablesTheStepDependentButtonsAndKeepsTheirHints() {
        val suiteId = (state.createTestSuite("Smoke") as StoreResult.Ok).value.id
        val case = (state.createTestCase(suiteId, TestCase("", "Empty")) as StoreResult.Ok).value
        open(suiteId, case.id)

        // Paste-log and Improve need a step; they stay visible, disabled, and say so in a tooltip.
        assertTrue(rule.onAllNodes(isNotEnabled() and hasAnyDescendant(hasText("Paste log lines as checks…"))).fetchSemanticsNodes().isNotEmpty())
        assertTrue(rule.onAllNodes(isNotEnabled() and hasAnyDescendant(hasText("Improve steps with AI…"))).fetchSemanticsNodes().isNotEmpty())
        rule.onNodeWithText("Draft steps with AI…").assertExists()
        rule.onNodeWithText("Record from device…").assertExists()
    }

    @Test
    fun aReadOnlyCaseDisablesTheStepAuthoringButtons() {
        state.createTestSuite("First")
        val lockedSuiteId = (state.createTestSuite("Second") as StoreResult.Ok).value.id
        val caseId = (state.createTestCase(lockedSuiteId, TestCase("", "Locked", steps = steps())) as StoreResult.Ok).value.id
        assertTrue(state.editionService.setForDev(Edition.FREE))
        open(lockedSuiteId, caseId)

        listOf("+ Add step", "Draft steps with AI…", "Record from device…", "Paste log lines as checks…", "Improve steps with AI…").forEach { label ->
            assertTrue(
                rule.onAllNodes(isNotEnabled() and hasAnyDescendant(hasText(label))).fetchSemanticsNodes().isNotEmpty(),
                "$label should be disabled for a read-only case",
            )
        }
        rule.onNodeWithText("Draft steps with AI…").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Draft steps with AI").assertDoesNotExist()
        assertEquals(2, state.testLibrary.findCase(caseId)!!.case.steps.size)
    }
}
