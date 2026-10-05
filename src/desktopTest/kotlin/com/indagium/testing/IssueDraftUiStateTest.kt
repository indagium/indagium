package com.indagium.testing

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueEnvironment
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.run.withIssueId
import com.indagium.ui.IssueFormModel
import com.indagium.ui.IssueOverrides
import com.indagium.ui.LiveIssueLine
import com.indagium.ui.TRACKER_DISABLED_HINT
import com.indagium.ui.checklistLabel
import com.indagium.ui.createLabel
import com.indagium.ui.destinationChoices
import com.indagium.ui.filterIssues
import com.indagium.ui.liveColumns
import com.indagium.ui.originLine
import com.indagium.ui.withOverrides
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IssueDraftUiStateTest {
    private val draft = IssueDraft(
        title = "Crash on login", severity = IssueSeverity.HIGH, labels = listOf("smoke", "found-by-agent"),
        stepsToReproduce = listOf("Open the app", "Tap login"), expected = "Home", actual = "Crash", judgeNotes = "n",
        environment = IssueEnvironment(appPackage = "com.example.app"),
        attachments = listOf(
            IssueAttachment(IssueAttachmentKind.SCREENSHOT, "Screenshot", "s.png", sizeBytes = 2_048L),
            IssueAttachment(IssueAttachmentKind.LOG_RANGE, "Log", "l.txt", sizeBytes = 10L),
        ),
    )

    @Test
    fun theFormRoundTripsADraftAndKeepsWhatItDoesNotEdit() {
        val form = IssueFormModel.from(draft, linkToCase = true)

        assertEquals("smoke, found-by-agent", form.labelsText)
        assertEquals("Open the app\nTap login", form.stepsText)
        assertTrue(form.linkToCase)
        assertEquals(draft, form.toDraft(draft))
    }

    @Test
    fun editedTextBecomesListsAgainWithBlanksAndDuplicatesDropped() {
        val form = IssueFormModel.from(draft, false).copy(
            title = "  New title  ", labelsText = "a, B ,, b ,c", stepsText = "  one \n\n two\n   ", severity = IssueSeverity.LOW,
        )

        val edited = form.toDraft(draft)

        assertEquals("New title", edited.title)
        assertEquals(listOf("a", "B", "c"), edited.labels)
        assertEquals(listOf("one", "two"), edited.stepsToReproduce)
        assertEquals(IssueSeverity.LOW, edited.severity)
        assertEquals("com.example.app", edited.environment.appPackage)
    }

    @Test
    fun togglingAnAttachmentFlipsOnlyThatOne() {
        val form = IssueFormModel.from(draft, false).toggleAttachment(1)

        assertEquals(listOf(true, false), form.attachments.map { it.include })
        assertEquals(listOf(true, true), form.toggleAttachment(1).attachments.map { it.include })
        assertEquals("Screenshot · 2 KB", draft.attachments[0].checklistLabel())
    }

    @Test
    fun aBlankTitleIsAProblem() {
        assertEquals("Give the issue a title.", IssueFormModel.from(draft.copy(title = "  "), false).problem)
        assertNull(IssueFormModel.from(draft, false).problem)
    }

    @Test
    fun theTrackerDestinationIsShownButDisabledWithItsHint() {
        val choices = destinationChoices()

        assertEquals(listOf("Local", "Notes", "Markdown", "Tracker"), choices.map { it.label })
        assertEquals(listOf(true, true, true, false), choices.map { it.enabled })
        assertEquals(TRACKER_DISABLED_HINT, choices.last().hint)
        assertEquals("Configure an issue tracker in Settings", TRACKER_DISABLED_HINT)
        assertEquals("Create", createLabel(IssueDestination.LOCAL))
        assertTrue(createLabel(IssueDestination.NOTES).contains("notes"))
        assertTrue(createLabel(IssueDestination.MARKDOWN).contains("Markdown"))
    }

    @Test
    fun overridesChangeOnlyWhatTheyNameAndNormaliseLists() {
        val changed = draft.withOverrides(
            IssueOverrides(title = " Better title ", severity = IssueSeverity.CRITICAL, labels = listOf("x", "X", " y "), stepsToReproduce = listOf(" a ", "")),
        )

        assertEquals("Better title", changed.title)
        assertEquals(IssueSeverity.CRITICAL, changed.severity)
        assertEquals(listOf("x", "y"), changed.labels)
        assertEquals(listOf("a"), changed.stepsToReproduce)
        assertEquals(draft.expected, changed.expected)
        assertEquals(draft, draft.withOverrides(IssueOverrides()))
        assertEquals(draft.title, draft.withOverrides(IssueOverrides(title = "  ")).title, "a blank title is ignored")
    }

    @Test
    fun theListFiltersByTitleCaseAndLabelAndNamesTheOrigin() {
        val source = IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-1", caseName = "Login", stepNumber = 2)
        val one = IssueRecord("issue-1", draft, source)
        val two = IssueRecord("issue-2", draft.copy(title = "Other", labels = listOf("ui")), source.copy(caseName = "Settings"))

        assertEquals(listOf(one, two), filterIssues(listOf(one, two), "  "))
        assertEquals(listOf(one), filterIssues(listOf(one, two), "CRASH"))
        assertEquals(listOf(two), filterIssues(listOf(one, two), "settings"))
        assertEquals(listOf(two), filterIssues(listOf(one, two), "ui"))
        assertFalse(filterIssues(listOf(one, two), "nothing").isNotEmpty())
        assertEquals("Login · step 2 · run-1", one.originLine())
    }

    @Test
    fun theLiveViewListsTheDraftIssuesTheEngineMadeOnALane() {
        val root = createTempDirectory("issue-live").toFile()
        try {
            val fixture = issueRunFixture(root)
            val without = liveColumns(fixture.run, emptyList(), { emptyList() }, emptyList(), emptyList()).single()
            assertEquals(emptyList(), without.issues)

            val run = fixture.run.withIssueId(fixture.laneId, fixture.caseId, 1, fixture.failingStep.stepId, "issue-xyz")
            val column = liveColumns(run, emptyList(), { emptyList() }, emptyList(), emptyList()).single()

            assertEquals(listOf(LiveIssueLine("issue-xyz", "Settings", 2, fixture.failingStep.action)), column.issues)
        } finally {
            root.deleteRecursively()
        }
    }
}
