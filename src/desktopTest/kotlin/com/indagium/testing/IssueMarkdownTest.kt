package com.indagium.testing

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueEnvironment
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.run.formatByteSize
import com.indagium.testing.run.toMarkdown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IssueMarkdownTest {
    private val screenshot = IssueAttachment(IssueAttachmentKind.SCREENSHOT, "Screenshot at the end of the step", "screenshot.png", sizeBytes = 3_072L)
    private val log = IssueAttachment(IssueAttachmentKind.LOG_RANGE, "Log during the step", "log-range.txt", sizeBytes = 12L, note = "last 12 bytes")
    private val skipped = IssueAttachment(IssueAttachmentKind.TRANSCRIPT, "Transcript", "t.jsonl", sizeBytes = 99L, include = false)

    private val draft = IssueDraft(
        title = "Login crashes",
        severity = IssueSeverity.HIGH,
        labels = listOf("smoke", "found-by-agent"),
        stepsToReproduce = listOf("Open the app", "  Tap login  ", ""),
        expected = "The home screen is shown",
        actual = "A crash dialog appears.",
        judgeNotes = "Judge: FAIL (app defect)",
        environment = IssueEnvironment(appPackage = "com.example.app", deviceSerial = "SER-1", agent = "an external client over MCP", runId = "run-1"),
        attachments = listOf(screenshot, log, skipped),
    )
    private val source = IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-2", caseName = "Login", stepNumber = 2)

    @Test
    fun anIssueRendersInTheFixedSectionOrderWithAbsolutePathsForItsEvidence() {
        val markdown = draft.toMarkdown(source) { "/issues/issue-1/attachments/${it.fileName}" }

        val expected = """
            # Login crashes

            **Severity:** High · **Labels:** smoke, found-by-agent

            ## Environment

            - App package: `com.example.app`
            - Device: SER-1
            - Found by: an external client over MCP
            - Test run: `run-1` (case “Login”, step 2)

            ## Steps to reproduce

            1. Open the app
            2. Tap login

            ## Expected

            The home screen is shown

            ## Actual

            A crash dialog appears.

            ## Judge notes

            Judge: FAIL (app defect)

            ## Evidence

            - Screenshot: `/issues/issue-1/attachments/screenshot.png` (3 KB)
            - Log: `/issues/issue-1/attachments/log-range.txt` (12 B) — last 12 bytes
        """.trimIndent() + "\n"
        assertEquals(expected, markdown)
        assertFalse(markdown.contains("Transcript"), "evidence the user left out is not listed")
    }

    @Test
    fun emptySectionsAreLeftOutExceptTheThreeThatMakeAnIssue() {
        val markdown = IssueDraft(title = "  ").toMarkdown()

        assertTrue(markdown.startsWith("# Untitled issue\n"), markdown)
        assertTrue(markdown.contains("## Steps to reproduce\n\n(not written)"), markdown)
        assertTrue(markdown.contains("## Expected\n\n(not written)"), markdown)
        assertTrue(markdown.contains("## Actual\n\n(not written)"), markdown)
        assertFalse(markdown.contains("## Environment") || markdown.contains("## Judge notes") || markdown.contains("## Evidence"), markdown)
        assertFalse(markdown.contains("**Labels:**"))
    }

    @Test
    fun anAttachmentWithoutAFileOnDiskSaysSo() {
        val markdown = draft.copy(attachments = listOf(screenshot)).toMarkdown(source) { null }

        assertTrue(markdown.contains("- Screenshot: (file not available) (3 KB)"), markdown)
    }

    @Test
    fun sizesAreShownInBytesKilobytesAndMegabytes() {
        assertEquals("0 B", formatByteSize(0))
        assertEquals("1023 B", formatByteSize(1_023))
        assertEquals("1 KB", formatByteSize(1_024))
        assertEquals("4.5 MB", formatByteSize(4_718_592))
    }
}
