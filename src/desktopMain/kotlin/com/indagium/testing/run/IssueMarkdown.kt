package com.indagium.testing.run

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource

// An issue as Markdown, for pasting into a ticket, a chat or a note. Pure. Sections without content are left out, except
// the three that make an issue (steps, expected, actual). Evidence is listed by ABSOLUTE path (the caller says where each
// attachment lives): Markdown cannot carry the files themselves. Text that came from an agent or the device was already
// quoted and labelled as untrusted by the draft builder; it is written out unchanged.

private const val BYTES_PER_KB = 1024L
private const val BYTES_PER_MB = BYTES_PER_KB * 1024L
private const val NONE_WRITTEN = "(not written)"

internal fun IssueSeverity.label(): String = name.lowercase().replaceFirstChar { it.titlecase() }

internal fun IssueAttachmentKind.label(): String = when (this) {
    IssueAttachmentKind.VIDEO_CLIP -> "Video"
    IssueAttachmentKind.LOG_RANGE -> "Log"
    IssueAttachmentKind.SCREENSHOT -> "Screenshot"
    IssueAttachmentKind.TRANSCRIPT -> "Agent transcript"
    IssueAttachmentKind.JUDGE_VERDICT -> "Judge verdict"
    IssueAttachmentKind.GOLDEN -> "Expected screenshot"
    IssueAttachmentKind.BUGREPORT -> "Android bugreport"
    IssueAttachmentKind.CAPTURE_ARCHIVE -> "Capture archive"
}

/** "12 B", "3 KB" or "4.5 MB". */
internal fun formatByteSize(bytes: Long): String = when {
    bytes < BYTES_PER_KB -> "$bytes B"
    bytes < BYTES_PER_MB -> "${(bytes + BYTES_PER_KB / 2) / BYTES_PER_KB} KB"
    else -> "%.1f MB".format(java.util.Locale.ROOT, bytes.toDouble() / BYTES_PER_MB)
}

private fun StringBuilder.section(title: String, body: String) {
    append("\n## ").append(title).append("\n\n").append(body.trim()).append('\n')
}

private fun StringBuilder.environment(draft: IssueDraft, source: IssueSource?) {
    val env = draft.environment
    val lines = buildList {
        if (env.appPackage.isNotBlank()) add("- App package: `${env.appPackage}`")
        val device = when {
            env.deviceModel.isBlank() -> env.deviceSerial
            env.deviceSerial.isBlank() -> env.deviceModel
            else -> "${env.deviceModel} (${env.deviceSerial})"
        }
        if (device.isNotBlank()) add("- Device: $device")
        if (env.agent.isNotBlank()) add("- Found by: ${env.agent}")
        if (env.build.isNotBlank()) add("- Build: ${env.build}")
        if (env.runId.isNotBlank()) {
            val where = source?.let { " (case “${it.caseName}”, step ${it.stepNumber}" + if (it.iteration > 1) ", run ${it.iteration})" else ")" }.orEmpty()
            add("- Test run: `${env.runId}`$where")
        }
    }
    if (lines.isNotEmpty()) section("Environment", lines.joinToString("\n"))
}

private fun attachmentLine(attachment: IssueAttachment, path: String?): String {
    val location = path?.let { "`$it`" } ?: "(file not available)"
    val note = attachment.note.takeIf { it.isNotBlank() }?.let { " — $it" }.orEmpty()
    return "- ${attachment.kind.label()}: $location (${formatByteSize(attachment.sizeBytes)})$note"
}

/** [pathOf] gives an attachment's absolute path, or null when the file is not on disk. Attachments with `include` false are left out. */
fun IssueDraft.toMarkdown(source: IssueSource? = null, pathOf: (IssueAttachment) -> String? = { null }): String = buildString {
    append("# ").append(title.trim().ifBlank { "Untitled issue" }).append('\n')
    append("\n**Severity:** ").append(severity.label())
    if (labels.isNotEmpty()) append(" · **Labels:** ").append(labels.joinToString(", "))
    append('\n')
    environment(this@toMarkdown, source)
    section(
        "Steps to reproduce",
        stepsToReproduce.map { it.trim() }.filter { it.isNotEmpty() }.withIndex()
            .joinToString("\n") { (index, line) -> "${index + 1}. $line" }.ifBlank { NONE_WRITTEN },
    )
    section("Expected", expected.ifBlank { NONE_WRITTEN })
    section("Actual", actual.ifBlank { NONE_WRITTEN })
    if (judgeNotes.isNotBlank()) section("Judge notes", judgeNotes)
    val evidence = attachments.filter { it.include }
    if (evidence.isNotEmpty()) section("Evidence", evidence.joinToString("\n") { attachmentLine(it, pathOf(it)) })
}
