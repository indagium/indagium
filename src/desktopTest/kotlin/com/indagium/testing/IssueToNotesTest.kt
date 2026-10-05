package com.indagium.testing

import com.indagium.model.AnnBlock
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDestination
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueStatus
import com.indagium.ui.AppState
import com.indagium.ui.IssueActionResult
import com.indagium.ui.buildIssueSeed
import com.indagium.ui.deliverIssue
import com.indagium.ui.saveIssue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private const val AWAIT_MS = 15_000L
private const val POLL_MS = 20L

/** The notes destination: the issue's Markdown lands as a note (then the screenshot) in the log tab of the lane. */
class IssueToNotesTest {
    private lateinit var dir: File
    private lateinit var state: AppState
    private lateinit var fixture: IssueRunFixture

    @BeforeTest
    fun setUp() {
        dir = createTempDirectory("issue-notes").toFile()
        state = AppState(
            autosaveFile = File(dir, "state.cache"),
            autoExportNotes = false,
            notesDir = File(dir, "notes"),
            archiveCacheDir = File(dir, "archive"),
            customCommandsDir = File(dir, "commands"),
            controlTokenFile = File(dir, "token"),
            sourceIndexFile = File(dir, "source-index"),
            testingDir = File(dir, "testing"),
        )
        fixture = issueRunFixture(File(dir, "fixture"))
        installRun(File(dir, "testing"), fixture)
    }

    @AfterTest
    fun tearDown() {
        state.close()
        dir.deleteRecursively()
    }

    private fun laneLog(): File = File(File(dir, "testing/runs/${fixture.run.id}"), checkNotNull(fixture.run.lanes.single().logPath))

    private fun storedIssue(): IssueRecord = runBlocking {
        val seed = state.buildIssueSeed(fixture.run.id, fixture.laneId, fixture.caseId, 1, fixture.failingStep.stepId).getOrThrow()
        (state.saveIssue(null, seed.source, seed.draft, linkToCase = false) as IssueActionResult.Done).record
    }

    private fun openAndAwait(file: File): String {
        val id = assertNotNull(state.openFileAsIs(file))
        runBlocking { withTimeout(AWAIT_MS) { while (state.isLoadInFlight(id) || state.tab(id) == null) delay(POLL_MS) } }
        return id
    }

    private fun deliver(issue: IssueRecord, destination: IssueDestination = IssueDestination.NOTES, tabId: String? = null, openLaneLog: Boolean = false) =
        runBlocking { state.deliverIssue(issue.id, destination, copyMarkdown = false, tabId = tabId, openLaneLog = openLaneLog) }

    @Test
    fun theIssueIsAddedAsANoteThenItsScreenshotToTheTabThatShowsTheLanesLog() {
        val issue = storedIssue()
        val tabId = openAndAwait(laneLog())

        val result = assertIs<IssueActionResult.Done>(deliver(issue))

        assertEquals(tabId, result.tabId)
        val blocks = checkNotNull(state.tab(tabId)).annotations.blocks
        val note = assertIs<AnnBlock.Note>(blocks.first())
        assertTrue(note.text.startsWith("# ${issue.draft.title}\n"), note.text)
        assertTrue(note.text.contains("## Steps to reproduce"), note.text)
        assertTrue(note.text.contains("Tap Settings in the toolbar"), note.text)
        assertTrue(note.text.contains("The screenshot of the step follows as an image."), note.text)
        val image = assertIs<AnnBlock.Image>(blocks[1])
        assertTrue(image.caption.startsWith("Step 2:"), image.caption)
        assertTrue(image.bytes.isNotEmpty())
        assertEquals(2, blocks.size)

        val after = assertNotNull(state.issueStore.load(issue.id))
        assertEquals(IssueStatus.SENT, after.status)
        assertEquals(IssueDestination.NOTES, after.destinationResults.single().destination)
        assertEquals(tabId, after.destinationResults.single().reference)
    }

    @Test
    fun withoutATabForTheLaneLogTheCallerIsOfferedToOpenItAndThenDoes() {
        val issue = storedIssue()
        assertTrue(state.tabs.isEmpty())

        val offer = assertIs<IssueActionResult.NeedsLogTab>(deliver(issue))

        assertEquals(laneLog().canonicalFile, offer.logFile.canonicalFile)
        assertTrue(state.tabs.isEmpty(), "nothing is opened until the caller says so")
        assertEquals(IssueStatus.DRAFT, state.issueStore.load(issue.id)?.status, "an offer changes nothing")

        val done = assertIs<IssueActionResult.Done>(deliver(issue, openLaneLog = true))

        val tab = checkNotNull(state.tab(checkNotNull(done.tabId)))
        assertEquals(laneLog().canonicalFile, File(checkNotNull(tab.sourcePath)).canonicalFile)
        assertTrue(tab.annotations.blocks.first() is AnnBlock.Note)
        assertEquals(IssueStatus.SENT, state.issueStore.load(issue.id)?.status)
    }

    @Test
    fun anExplicitTabIsUsedInsteadOfTheLaneLogTab() {
        val issue = storedIssue()
        openAndAwait(laneLog())
        val other = File(dir, "other.log").apply { writeText(FIXTURE_LOG_BEFORE) }
        val otherTab = openAndAwait(other)

        val done = assertIs<IssueActionResult.Done>(deliver(issue, tabId = otherTab))

        assertEquals(otherTab, done.tabId)
        assertTrue(checkNotNull(state.tab(otherTab)).annotations.blocks.first() is AnnBlock.Note)
        val unknown = assertIs<IssueActionResult.Failed>(deliver(issue, tabId = "t-nope"))
        assertTrue(unknown.message.contains("No tab with id 't-nope'"), unknown.message)
    }

    @Test
    fun whenTheRunOrItsLogIsGoneTheNotesDestinationSaysSoAndChangesNothing() {
        val issue = storedIssue()
        File(dir, "testing/runs/${fixture.run.id}").deleteRecursively()

        val failed = assertIs<IssueActionResult.Failed>(deliver(issue, openLaneLog = true))

        assertTrue(failed.message.contains("lane's log is not available"), failed.message)
        assertEquals(IssueStatus.DRAFT, state.issueStore.load(issue.id)?.status)
        assertTrue(state.tabs.isEmpty())
    }

    @Test
    fun markdownAndLocalDestinationsRecordTheirDeliveryAndTheTrackerIsRefused() {
        val issue = storedIssue()
        val screenshot = issue.draft.attachments.first { it.kind == IssueAttachmentKind.SCREENSHOT }
        val screenshotPath = checkNotNull(state.issueStore.attachmentFile(issue, screenshot)).absolutePath

        val markdown = assertIs<IssueActionResult.Done>(deliver(issue, IssueDestination.MARKDOWN))
        assertTrue(checkNotNull(markdown.markdown).contains("# ${issue.draft.title}"))
        assertTrue(markdown.markdown!!.contains(screenshotPath), "evidence is listed by absolute path")
        assertEquals(IssueStatus.SENT, markdown.record.status)

        val saved = assertIs<IssueActionResult.Done>(deliver(storedIssue(), IssueDestination.LOCAL))
        assertEquals(IssueStatus.SAVED, saved.record.status)
        assertEquals(IssueDestination.LOCAL, saved.record.destinationResults.single().destination)

        val tracker = assertIs<IssueActionResult.Failed>(deliver(issue, IssueDestination.TRACKER))
        assertTrue(tracker.message.contains("issue tracker"), tracker.message)
        assertFalse(state.issueStore.load(issue.id)!!.destinationResults.any { it.destination == IssueDestination.TRACKER })
    }

    @Test
    fun deliveringALocalIssueNeverMovesAnAlreadySentIssueBack() {
        val issue = storedIssue()
        deliver(issue, IssueDestination.MARKDOWN)

        val again = assertIs<IssueActionResult.Done>(deliver(issue, IssueDestination.LOCAL))

        assertEquals(IssueStatus.SENT, again.record.status)
        assertEquals(2, again.record.destinationResults.size)
    }
}
