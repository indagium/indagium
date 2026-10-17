package com.indagium

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.ui.ArtifactOpenMode
import com.indagium.ui.EvidenceAvailability
import com.indagium.ui.IssueEvidenceSection
import com.indagium.ui.isTextLikeFile
import com.indagium.ui.issueAttachmentOpenMode
import com.indagium.ui.issueEvidenceRows
import com.indagium.ui.openFileCommand
import com.indagium.ui.textEditorOpenCommand
import com.indagium.ui.usuallyHasNoDefaultApp
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which issue evidence opens in Indagium and which with the system, the Evidence rows, and the open commands. */
@OptIn(ExperimentalTestApi::class)
class IssueEvidenceTest {
    @get:Rule
    val rule = createComposeRule()

    private fun attachment(kind: IssueAttachmentKind, fileName: String, stored: Boolean = true, label: String = fileName) =
        IssueAttachment(kind, label, fileName, sizeBytes = 2048, storedPath = if (stored) "attachments/$fileName" else null)

    @Test
    fun logsAndArchivesOpenInTheAppAndEverythingElseWithTheSystem() {
        val expected = mapOf(
            IssueAttachmentKind.CAPTURE_ARCHIVE to ("capture-archive.zip" to ArtifactOpenMode.IN_APP),
            IssueAttachmentKind.BUGREPORT to ("android-bugreport-1.zip" to ArtifactOpenMode.IN_APP),
            IssueAttachmentKind.LOG_RANGE to ("log-range.txt" to ArtifactOpenMode.IN_APP),
            IssueAttachmentKind.TRANSCRIPT to ("transcript-slice.jsonl" to ArtifactOpenMode.SYSTEM),
            IssueAttachmentKind.JUDGE_VERDICT to ("judge-verdict.json" to ArtifactOpenMode.SYSTEM),
            IssueAttachmentKind.SCREENSHOT to ("screenshot.png" to ArtifactOpenMode.SYSTEM),
            IssueAttachmentKind.GOLDEN to ("expected.png" to ArtifactOpenMode.SYSTEM),
            IssueAttachmentKind.VIDEO_CLIP to ("clip.mp4" to ArtifactOpenMode.SYSTEM),
        )
        assertEquals(IssueAttachmentKind.entries.toSet(), expected.keys, "every kind has a decision")
        expected.forEach { (kind, nameAndMode) ->
            assertEquals(nameAndMode.second, issueAttachmentOpenMode(kind, File(nameAndMode.first)), kind.name)
        }
    }

    @Test
    fun anInAppKindWhoseFileIsNotALogOrArchiveFallsBackToTheSystem() {
        assertEquals(ArtifactOpenMode.SYSTEM, issueAttachmentOpenMode(IssueAttachmentKind.LOG_RANGE, File("log-range.bin")))
        assertEquals(ArtifactOpenMode.SYSTEM, issueAttachmentOpenMode(IssueAttachmentKind.BUGREPORT, File("report.tar")))
        assertEquals(ArtifactOpenMode.IN_APP, issueAttachmentOpenMode(IssueAttachmentKind.BUGREPORT, File("REPORT.ZIP")))
    }

    @Test
    fun rowsListStoredFilesAndPendingArchivesButNotUnstoredDrafts() {
        val dir = createTempDirectory("issue-evidence").toFile()
        val present = File(dir, "screenshot.png").apply { writeBytes(byteArrayOf(1)) }
        val attachments = listOf(
            IssueAttachment(IssueAttachmentKind.CAPTURE_ARCHIVE, "Capture archive", "capture-archive.zip", include = true),
            attachment(IssueAttachmentKind.SCREENSHOT, "screenshot.png"),
            attachment(IssueAttachmentKind.JUDGE_VERDICT, "judge-verdict.json"),
            attachment(IssueAttachmentKind.GOLDEN, "expected.png", stored = false),
        )

        val rows = issueEvidenceRows(attachments) { if (it.fileName == "screenshot.png") present else null }

        assertEquals(listOf("capture-archive.zip", "screenshot.png", "judge-verdict.json"), rows.map { it.attachment.fileName })
        assertIs<EvidenceAvailability.Pending>(rows[0].availability)
        assertEquals(present, assertIs<EvidenceAvailability.Ready>(rows[1].availability).file)
        assertIs<EvidenceAvailability.Missing>(rows[2].availability)
        dir.deleteRecursively()
    }

    @Test
    fun evidenceSectionListsEveryRowAndDisablesOpenForAMissingFile() {
        val dir = createTempDirectory("issue-evidence-ui").toFile()
        val present = File(dir, "screenshot.png").apply { writeBytes(byteArrayOf(1)) }
        val rows = issueEvidenceRows(
            listOf(
                attachment(IssueAttachmentKind.SCREENSHOT, "screenshot.png", label = "Screenshot at the end of the step"),
                attachment(IssueAttachmentKind.JUDGE_VERDICT, "judge-verdict.json", label = "Judge verdict"),
                IssueAttachment(IssueAttachmentKind.CAPTURE_ARCHIVE, "Capture archive", "capture-archive.zip"),
            ),
        ) { if (it.fileName == "screenshot.png") present else null }
        val opened = mutableListOf<String>()
        val revealed = mutableListOf<File>()

        rule.setContent { IssueEvidenceSection(rows, onOpen = { a, _ -> opened += a.fileName }, onReveal = { revealed += it }) }

        rule.onNodeWithText("Evidence").assertExists()
        rule.onNodeWithText("Screenshot at the end of the step").assertExists()
        rule.onNodeWithText("Screenshot · screenshot.png · 2.0 KB").assertExists()
        rule.onNodeWithText("missing").assertExists()
        rule.onNodeWithText("pending").assertExists()
        rule.onNodeWithTag("evidence-open-0").assertIsEnabled().performClick()
        rule.onNodeWithTag("evidence-reveal-0").performClick()
        rule.onNodeWithTag("evidence-open-1").assertIsNotEnabled()
        rule.onNodeWithTag("evidence-reveal-1").assertIsNotEnabled()
        rule.onNodeWithTag("evidence-open-2").assertDoesNotExist()
        assertEquals(listOf("screenshot.png"), opened)
        assertEquals(listOf(present), revealed)
        dir.deleteRecursively()
    }

    @Test
    fun theOpenCommandIsPerPlatformAndDoesNotRunAShell() {
        val file = File("/tmp/a b/clip.mp4")
        assertEquals(listOf("open", file.absolutePath), openFileCommand(file, "Mac OS X"))
        assertEquals(listOf("xdg-open", file.absolutePath), openFileCommand(file, "Linux"))
        assertTrue(openFileCommand(file, "Windows 11").first() == "rundll32")
    }

    @Test
    fun textFilesOpenInTheSystemTextEditorWhenNothingIsRegisteredForThem() {
        val transcript = File("/tmp/a b/attachments/transcript-slice.jsonl")
        assertEquals(listOf("open", "-t", transcript.absolutePath), textEditorOpenCommand(transcript, "Mac OS X"))
        assertEquals(listOf("notepad.exe", transcript.absolutePath), textEditorOpenCommand(transcript, "Windows 11"))
        // Linux has no text-editor command of its own: xdg-open already resolves one from the MIME type.
        assertNull(textEditorOpenCommand(transcript, "Linux"))
    }

    @Test
    fun onlyPlainTextExtensionsAreTextLikeAndOnlyJsonlStyleOnesSkipTheDefaultApp() {
        for (name in listOf("t.jsonl", "t.NDJSON", "t.json", "t.txt", "t.log", "t.md", "t.csv")) {
            assertTrue(isTextLikeFile(File(name)), name)
        }
        for (name in listOf("clip.mp4", "shot.png", "bugreport.zip", "noextension")) assertFalse(isTextLikeFile(File(name)), name)
        assertTrue(usuallyHasNoDefaultApp(File("transcript-slice.jsonl")))
        assertTrue(usuallyHasNoDefaultApp(File("x.ndjson")))
        assertFalse(usuallyHasNoDefaultApp(File("notes.txt")))
        assertFalse(usuallyHasNoDefaultApp(File("data.json")))
    }
}
