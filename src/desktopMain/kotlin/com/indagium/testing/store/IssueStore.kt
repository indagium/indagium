package com.indagium.testing.store

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.newIssueId
import com.indagium.utils.writeFileAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// The issues of AI test runs, one folder each under [issuesDir]:
//   <issueId>/issue.json              the IssueRecord, written atomically (issue-codec: IssueCodec.kt)
//   <issueId>/attachments/<name>      the copied evidence (screenshot, log range, judge verdict, optional video ...)
// [lock] is a LEAF lock: it serialises create/update/delete (a read-modify-write of issue.json and the attachment folder)
// and nothing in here calls out to AppState or anything else that locks while holding it. Readers take no lock: a write
// replaces issue.json atomically, so they only ever see complete files. [revision] changes after every successful
// mutation (published AFTER the lock is released) so a screen can reload its list. Nothing is created until the first issue.
// Operations return a StoreResult instead of throwing for an expected problem; a failed create leaves no folder behind.

const val ISSUES_DIR_NAME = "issues"
const val ISSUE_FILE_NAME = "issue.json"
const val ISSUE_ATTACHMENTS_DIR_NAME = "attachments"
const val MAX_ISSUE_FILE_BYTES = 4L * 1024L * 1024L
const val MAX_ISSUE_ATTACHMENT_BYTES = 1024L * 1024L * 1024L
private const val MAX_LISTED_ISSUES = 500
private const val MAX_ATTACHMENT_NAME_CHARS = 100
private const val DEFAULT_ATTACHMENT_NAME = "attachment"
private const val READ_ONLY_MESSAGE = "This issue was saved by a newer version of Indagium and is read-only here."
private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

/** The result of putting a draft's attachments into the issue folder: what is stored, and what could not be. */
private class Materialised(val attachments: List<IssueAttachment>, val warnings: List<String>)

class IssueStore(private val issuesDir: File, private val clock: () -> Long = System::currentTimeMillis) {
    private val lock = ReentrantLock()
    private val revisionState = MutableStateFlow(0)

    /** Changes after every successful create, update and delete. */
    val revision: StateFlow<Int> = revisionState.asStateFlow()

    /** The folder of [issueId]. Not created here. Throws [IllegalArgumentException] for an id that could leave the issues folder. */
    fun issueDir(issueId: String): File {
        require(isSafeId(issueId)) { "Unsafe issue id" }
        return File(issuesDir, issueId)
    }

    // ── Reading ──────────────────────────────────────────────────────

    /** The stored issue, or null when it is missing, unreadable or not an issue. */
    fun load(issueId: String): IssueRecord? {
        if (!isSafeId(issueId)) return null
        val file = File(issueDir(issueId), ISSUE_FILE_NAME)
        if (!file.isFile || file.length() > MAX_ISSUE_FILE_BYTES) return null
        return runCatching { decodeIssueFile(file.readText()).getOrNull() }.getOrNull()?.takeIf { it.id == issueId }
    }

    /** The stored issues, newest first. A folder without a readable issue.json is skipped. */
    fun list(): List<IssueRecord> {
        val folders = issuesDir.listFiles { file -> file.isDirectory && isSafeId(file.name) }.orEmpty()
        return folders.mapNotNull { load(it.name) }.sortedByDescending { it.createdAt }.take(MAX_LISTED_ISSUES)
    }

    /** The file of [attachment] inside [record]'s folder, or null when it has none, it is gone, or its path would leave the folder. */
    fun attachmentFile(record: IssueRecord, attachment: IssueAttachment): File? {
        val stored = attachment.storedPath ?: return null
        val dir = issueDir(record.id).canonicalFile
        val file = File(dir, stored).canonicalFile
        return file.takeIf { it.isFile && it.path.startsWith(dir.path + File.separator) }
    }

    // ── Writing ──────────────────────────────────────────────────────

    /** Stores a new issue with a fresh id: its included attachments are copied (or written) into the issue's folder. */
    fun create(draft: IssueDraft, source: IssueSource, status: IssueStatus = IssueStatus.DRAFT, linkToCase: Boolean = false): StoreResult<IssueRecord> {
        val id = newIssueId()
        val result = lock.withLock {
            try {
                val stored = materialise(id, draft.attachments)
                val now = clock()
                val record = IssueRecord(
                    id = id, draft = draft.copy(attachments = stored.attachments), source = source, status = status,
                    createdAt = now, updatedAt = now, linkToCase = linkToCase,
                )
                writeRecord(record)
                StoreResult.Ok(record, stored.warnings)
            } catch (failure: IOException) {
                runCatching { issueDir(id).deleteRecursively() }
                StoreResult.Invalid("Could not save the issue: ${failure.message}")
            }
        }
        if (result is StoreResult.Ok) bump()
        return result
    }

    /**
     * Replaces the issue with [transform] of it. The id, creation time and source are kept; attachments that gained a
     * source path or text are copied in and attachments no longer included are removed from the folder.
     */
    fun update(issueId: String, transform: (IssueRecord) -> IssueRecord): StoreResult<IssueRecord> {
        if (!isSafeId(issueId)) return StoreResult.NotFound("issue", issueId)
        val result = lock.withLock {
            val old = load(issueId) ?: return@withLock StoreResult.NotFound("issue", issueId)
            if (old.readOnly) return@withLock StoreResult.Invalid(READ_ONLY_MESSAGE)
            try {
                val changed = transform(old)
                val stored = materialise(issueId, changed.draft.attachments)
                removeUnreferencedFiles(issueId, stored.attachments)
                val record = changed.copy(
                    id = old.id, source = old.source, createdAt = old.createdAt, updatedAt = clock(), readOnly = false,
                    draft = changed.draft.copy(attachments = stored.attachments),
                )
                writeRecord(record)
                StoreResult.Ok(record, stored.warnings)
            } catch (failure: IOException) {
                StoreResult.Invalid("Could not save the issue: ${failure.message}")
            }
        }
        if (result is StoreResult.Ok) bump()
        return result
    }

    /** Deletes the issue and every attachment copied for it. */
    fun delete(issueId: String): StoreResult<Unit> {
        if (!isSafeId(issueId)) return StoreResult.NotFound("issue", issueId)
        val result = lock.withLock {
            val dir = issueDir(issueId)
            if (!dir.isDirectory) return@withLock StoreResult.NotFound("issue", issueId)
            if (dir.deleteRecursively()) StoreResult.Ok(Unit) else StoreResult.Invalid("Could not delete the issue folder.")
        }
        if (result is StoreResult.Ok) bump()
        return result
    }

    private fun bump() {
        revisionState.value = revisionState.value + 1
    }

    // ── Disk ─────────────────────────────────────────────────────────

    private fun writeRecord(record: IssueRecord) {
        val file = File(issueDir(record.id), ISSUE_FILE_NAME)
        writeFileAtomically(file) { it.write(encodeIssueFile(record)) }
    }

    private fun attachmentsDir(issueId: String) = File(issueDir(issueId), ISSUE_ATTACHMENTS_DIR_NAME)

    private fun safeName(raw: String, taken: (String) -> Boolean): String {
        val file = File(raw)
        val extension = file.extension.replace(UNSAFE_NAME_CHARS, "").take(MAX_EXTENSION_CHARS)
        val base = file.nameWithoutExtension.replace(UNSAFE_NAME_CHARS, "_").trim('_', '.').take(MAX_ATTACHMENT_NAME_CHARS)
            .ifBlank { DEFAULT_ATTACHMENT_NAME }
        val suffix = if (extension.isEmpty()) "" else ".$extension"
        var candidate = base + suffix
        var counter = 2
        while (taken(candidate)) candidate = "$base-${counter++}$suffix"
        return candidate
    }

    /** Copies or writes what is not stored yet, keeps what is, and leaves out what the user excluded. */
    private fun materialise(issueId: String, attachments: List<IssueAttachment>): Materialised {
        val dir = attachmentsDir(issueId)
        val kept = ArrayList<IssueAttachment>()
        val warnings = ArrayList<String>()
        val used = HashSet<String>()
        attachments.filter { it.include && it.storedPath != null }.forEach { used += File(it.storedPath.orEmpty()).name }
        for (attachment in attachments) {
            if (!attachment.include) continue
            if (attachment.storedPath != null) {
                kept += attachment
                continue
            }
            Files.createDirectories(dir.toPath())
            val name = safeName(attachment.fileName) { it in used || File(dir, it).exists() }
            val written = writeAttachment(dir, name, attachment)
            if (written == null) {
                warnings += "${attachment.label}: it could not be attached (the file is missing or too large)."
            } else {
                used += name
                kept += attachment.copy(
                    storedPath = "$ISSUE_ATTACHMENTS_DIR_NAME/$name", sourcePath = null, text = null, fileName = name, sizeBytes = written,
                )
            }
        }
        return Materialised(kept, warnings)
    }

    /** Writes one attachment into [dir] as [name]; returns its size, or null when it has no usable content. */
    private fun writeAttachment(dir: File, name: String, attachment: IssueAttachment): Long? {
        val target = File(dir, name)
        val text = attachment.text
        val source = attachment.sourcePath?.let(::File)
        return when {
            text != null -> {
                val bytes = text.toByteArray(Charsets.UTF_8)
                if (bytes.size > MAX_ISSUE_ATTACHMENT_BYTES) return null
                Files.write(target.toPath(), bytes)
                bytes.size.toLong()
            }
            source != null && source.isFile && source.length() <= MAX_ISSUE_ATTACHMENT_BYTES -> {
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                target.length()
            }
            else -> null
        }
    }

    /** Deletes files in the attachment folder that no attachment of the record names any more. */
    private fun removeUnreferencedFiles(issueId: String, attachments: List<IssueAttachment>) {
        val keep = attachments.mapNotNull { it.storedPath }.map { File(issueDir(issueId), it).canonicalPath }.toSet()
        attachmentsDir(issueId).listFiles()?.filter { it.isFile && it.canonicalPath !in keep }?.forEach { runCatching { it.delete() } }
    }

    private companion object {
        const val MAX_EXTENSION_CHARS = 10
    }
}
