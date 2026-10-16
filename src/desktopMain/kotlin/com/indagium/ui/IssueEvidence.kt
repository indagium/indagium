package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.isPendingCaptureArchive
import com.indagium.testing.run.label
import kotlinx.coroutines.launch
import java.io.File

// The Evidence section of an issue in the Tests workspace: every stored attachment as a row with an Open and a Reveal button.
// Which attachments open inside Indagium (as a log or capture tab) and which with the system's default application is decided by
// issueAttachmentOpenMode; the rows and that decision are plain functions so tests need no window.

/** Where an attachment opens: in an Indagium tab, or with the application the operating system associates with the file. */
internal enum class ArtifactOpenMode { IN_APP, SYSTEM }

private val LOG_EXTENSIONS = setOf("log", "txt")

/**
 * How [file] (the attachment of [kind]) is opened. IN_APP only for what `AppState.openPath` really turns into a tab: a capture
 * archive or bugreport ZIP, and the log range as a text file. Everything else, including the agent transcript (JSONL events, not
 * logcat lines: openPath would show it as a log of unparsed lines), the judge verdict JSON, screenshots and video clips, opens with
 * the system default. A video must never go through `openPathOrShowError`, which attaches it to the active log tab.
 */
internal fun issueAttachmentOpenMode(kind: IssueAttachmentKind, file: File): ArtifactOpenMode {
    val extension = file.extension.lowercase()
    val inApp = when (kind) {
        IssueAttachmentKind.CAPTURE_ARCHIVE, IssueAttachmentKind.BUGREPORT -> extension == "zip"
        IssueAttachmentKind.LOG_RANGE -> extension in LOG_EXTENSIONS
        IssueAttachmentKind.VIDEO_CLIP, IssueAttachmentKind.SCREENSHOT, IssueAttachmentKind.TRANSCRIPT,
        IssueAttachmentKind.JUDGE_VERDICT, IssueAttachmentKind.GOLDEN,
        -> false
    }
    return if (inApp) ArtifactOpenMode.IN_APP else ArtifactOpenMode.SYSTEM
}

/** What the evidence list can do with one attachment. */
internal sealed interface EvidenceAvailability {
    class Ready(val file: File) : EvidenceAvailability

    /** A capture archive that is exported when the issue is created. */
    data object Pending : EvidenceAvailability

    /** Stored once, but the file is gone (or its path leaves the issue's folder). */
    data object Missing : EvidenceAvailability
}

internal class EvidenceRow(val attachment: IssueAttachment, val availability: EvidenceAvailability)

/**
 * The rows of the Evidence section: what was stored (it has a stored path) plus a pending capture archive. A draft attachment that
 * was never stored (nothing to open) is left out; [resolve] finds the file of a stored one (see IssueStore.attachmentFile).
 */
internal fun issueEvidenceRows(attachments: List<IssueAttachment>, resolve: (IssueAttachment) -> File?): List<EvidenceRow> =
    attachments.mapNotNull { attachment ->
        when {
            attachment.isPendingCaptureArchive -> EvidenceRow(attachment, EvidenceAvailability.Pending)
            attachment.storedPath == null -> null
            else -> EvidenceRow(attachment, resolve(attachment)?.let { EvidenceAvailability.Ready(it) } ?: EvidenceAvailability.Missing)
        }
    }

/** The "Evidence" section of the issue inspector. Shows nothing when the issue kept no evidence. */
@Composable
internal fun IssueEvidenceSection(rows: List<EvidenceRow>, onOpen: (IssueAttachment, File) -> Unit, onReveal: (File) -> Unit) {
    if (rows.isEmpty()) return
    val tc = tc()
    TestsSectionTitle("Evidence")
    Column(Modifier.fillMaxWidth().background(tc.p, CORNER_MD).border(1.dp, tc.br, CORNER_MD).padding(horizontal = 10.dp, vertical = 4.dp)) {
        rows.forEachIndexed { index, row ->
            val attachment = row.attachment
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    AppText(attachment.label, color = tc.tx, fontSize = 12.sp, overflow = TextOverflow.Ellipsis)
                    AppText(
                        "${attachment.kind.label()} · ${attachment.fileName} · ${formatByteSize(attachment.sizeBytes)}",
                        color = tc.td, fontSize = 10.sp, overflow = TextOverflow.Ellipsis,
                    )
                }
                when (val availability = row.availability) {
                    is EvidenceAvailability.Ready -> {
                        AppButton(
                            "Open", onClick = { onOpen(attachment, availability.file) },
                            modifier = Modifier.testTag("evidence-open-$index"),
                        )
                        AppButton(
                            "Reveal", onClick = { onReveal(availability.file) }, variant = ButtonVariant.Ghost,
                            modifier = Modifier.testTag("evidence-reveal-$index"),
                        )
                    }
                    EvidenceAvailability.Pending -> AppText("pending", color = tc.td, fontSize = 11.sp)
                    EvidenceAvailability.Missing -> {
                        AppText("missing", color = DANGER_RED, fontSize = 11.sp)
                        AppButton("Open", onClick = {}, enabled = false, modifier = Modifier.testTag("evidence-open-$index"))
                        AppButton("Reveal", onClick = {}, enabled = false, variant = ButtonVariant.Ghost, modifier = Modifier.testTag("evidence-reveal-$index"))
                    }
                }
            }
        }
    }
}

/** Opens [file] (the file of [attachment]) as [issueAttachmentOpenMode] says; a failure goes to the workspace banner. */
internal fun TestsUi.openIssueAttachment(attachment: IssueAttachment, file: File) {
    when (issueAttachmentOpenMode(attachment.kind, file)) {
        // openPath opens the file as a tab (or the archive picker) and makes that tab the active surface once it is ready,
        // which takes the user out of the Tests tab; a failure shows AppState's own open-error dialog.
        ArtifactOpenMode.IN_APP -> state.openPath(file)
        ArtifactOpenMode.SYSTEM -> scope.launch {
            openWithSystemDefault(file).onFailure { banner = TestsBanner(openFailureMessage(attachment.label, it), isError = true) }
        }
    }
}

/** Shows [file] in the system file manager; a failure goes to the workspace banner. */
internal fun TestsUi.revealIssueFile(file: File) {
    scope.launch {
        revealFile(file).onFailure { banner = TestsBanner("Could not show ${file.name} in the file manager: ${it.message.orEmpty()}", isError = true) }
    }
}

/** Opens [folder] in the file manager; a failure goes to the workspace banner. */
internal fun TestsUi.openFolderReportingFailure(folder: File, what: String) {
    scope.launch { openFolder(folder).onFailure { banner = TestsBanner(openFailureMessage(what, it), isError = true) } }
}

/** Opens [file] with the system default application; a failure goes to the workspace banner. */
internal fun TestsUi.openFileReportingFailure(file: File, what: String) {
    scope.launch { openWithSystemDefault(file).onFailure { banner = TestsBanner(openFailureMessage(what, it), isError = true) } }
}
