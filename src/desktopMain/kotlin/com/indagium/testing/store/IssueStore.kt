package com.indagium.testing.store

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.IssueStatus
import com.indagium.testing.model.isPendingCaptureArchive
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.newIssueId
import com.indagium.utils.writeFileAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

// The issues of AI test runs, one folder each under the Issues folder ([issuesDirProvider], evaluated on every use):
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

/** A lane's whole capture archive (video and audio included) is far bigger than any other evidence; it has its own, higher cap. */
const val MAX_ISSUE_CAPTURE_ARCHIVE_BYTES = 16L * 1024L * 1024L * 1024L
private const val MAX_LISTED_ISSUES = 500
private const val MAX_ATTACHMENT_NAME_CHARS = 100
private const val ATTACHMENT_COPY_BUFFER_BYTES = 64 * 1024
private const val DEFAULT_ATTACHMENT_NAME = "attachment"
private const val READ_ONLY_MESSAGE = "This issue was saved by a newer version of Indagium and is read-only here."
private val UNSAFE_NAME_CHARS = Regex("[^A-Za-z0-9._-]")

/** The result of putting a draft's attachments into the issue folder: what is stored, and what could not be. */
private class Materialised(val attachments: List<IssueAttachment>, val warnings: List<String>, val createdFiles: List<File>)

class IssueStore(
    /** Evaluated on every use: the Issues folder is a user setting that can change while the app runs. */
    private val issuesDirProvider: () -> File,
    private val recordWriter: ((File, IssueRecord) -> Unit)? = null,
    private val attachmentWriter: ((File, String, IssueAttachment) -> Long?)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    constructor(
        issuesDir: File,
        recordWriter: ((File, IssueRecord) -> Unit)? = null,
        attachmentWriter: ((File, String, IssueAttachment) -> Long?)? = null,
        clock: () -> Long = System::currentTimeMillis,
    ) : this({ issuesDir }, recordWriter, attachmentWriter, clock)

    private val issuesDir: File get() = issuesDirProvider()

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
            var materialised: Materialised? = null
            try {
                val changed = transform(old)
                val stored = materialise(issueId, changed.draft.attachments)
                materialised = stored
                val record = changed.copy(
                    id = old.id, source = old.source, createdAt = old.createdAt, updatedAt = clock(), readOnly = false,
                    draft = changed.draft.copy(attachments = stored.attachments),
                )
                writeRecord(record)
                // Publish the replacement record before removing old assets. If the atomic record write fails,
                // the previous record and every file it names remain usable.
                runCatching { removeUnreferencedFiles(issueId, stored.attachments) }
                StoreResult.Ok(record, stored.warnings)
            } catch (failure: IOException) {
                materialised?.createdFiles?.forEach { runCatching { it.delete() } }
                StoreResult.Invalid("Could not save the issue: ${failure.message}")
            }
        }
        if (result is StoreResult.Ok) bump()
        return result
    }

    /**
     * Adds one newly-created evidence file atomically. Unlike ordinary draft updates, failure to materialise this
     * attachment aborts before issue.json is replaced, so temporary collection sources can be deleted safely.
     */
    fun appendAttachmentStrict(
        issueId: String,
        attachment: IssueAttachment,
        /** True: a pending attachment of the same kind (see [isPendingCaptureArchive]) is replaced by [attachment] instead of kept. */
        replacePending: Boolean = false,
        cancellationCheck: () -> Unit = {},
    ): StoreResult<IssueRecord> {
        if (!isSafeId(issueId)) return StoreResult.NotFound("issue", issueId)
        val result = lock.withLock {
            val old = load(issueId) ?: return@withLock StoreResult.NotFound("issue", issueId)
            if (old.readOnly) return@withLock StoreResult.Invalid(READ_ONLY_MESSAGE)
            var materialised: Materialised? = null
            try {
                cancellationCheck()
                val existing = old.draft.attachments.filterNot { replacePending && it.isPendingCaptureArchive && it.kind == attachment.kind }
                val stored = materialise(issueId, existing + attachment, cancellationCheck)
                materialised = stored
                val added = stored.attachments.lastOrNull()
                if (stored.warnings.isNotEmpty() || added?.storedPath == null) {
                    stored.createdFiles.forEach { runCatching { it.delete() } }
                    return@withLock StoreResult.Invalid(
                        stored.warnings.firstOrNull() ?: "${attachment.label}: the attachment could not be stored.",
                    )
                }
                val updated = old.copy(
                    updatedAt = clock(),
                    draft = old.draft.copy(attachments = stored.attachments),
                )
                cancellationCheck()
                writeRecord(updated)
                runCatching { removeUnreferencedFiles(issueId, stored.attachments) }
                StoreResult.Ok(updated)
            } catch (cancelled: CancellationException) {
                materialised?.createdFiles?.forEach { runCatching { it.delete() } }
                throw cancelled
            } catch (failure: IOException) {
                materialised?.createdFiles?.forEach { runCatching { it.delete() } }
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

    /** Tells a screen that lists the issues to reload because [issuesDirProvider] now points at another folder. */
    fun folderChanged() = bump()

    // ── Disk ─────────────────────────────────────────────────────────

    private fun writeRecord(record: IssueRecord) {
        val file = File(issueDir(record.id), ISSUE_FILE_NAME)
        val writer = recordWriter
        if (writer != null) writer(file, record) else writeFileAtomically(file) { it.write(encodeIssueFile(record)) }
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

    /** Copies or writes the available inventory, including unchecked attachments, and keeps what is already stored. */
    @Suppress("TooGenericExceptionCaught") // Any copy/writer failure must roll back all newly staged issue attachments.
    private fun materialise(issueId: String, attachments: List<IssueAttachment>, cancellationCheck: () -> Unit = {}): Materialised {
        val dir = attachmentsDir(issueId)
        val kept = ArrayList<IssueAttachment>()
        val warnings = ArrayList<String>()
        val created = ArrayList<File>()
        val used = HashSet<String>()
        attachments.filter { it.storedPath != null }.forEach { used += File(it.storedPath.orEmpty()).name }
        try {
            for (attachment in attachments) {
                cancellationCheck()
                // A pending capture archive has nothing to copy yet; it stays a wish on the draft until it is exported.
                if (attachment.storedPath != null || attachment.isPendingCaptureArchive) {
                    kept += attachment
                    continue
                }
                Files.createDirectories(dir.toPath())
                val name = safeName(attachment.fileName) { it in used || File(dir, it).exists() }
                val target = File(dir, name)
                // safeName chose an unused path, so it is safe to remove even if a later write/copy fails partway.
                created += target
                val written = writeAttachment(dir, name, attachment, cancellationCheck)
                if (written == null) {
                    target.delete()
                    created.remove(target)
                    warnings += "${attachment.label}: it could not be attached (the file is missing or too large)."
                    kept += attachment
                } else {
                    used += name
                    kept += attachment.copy(
                        storedPath = "$ISSUE_ATTACHMENTS_DIR_NAME/$name", sourcePath = null, text = null, fileName = name, sizeBytes = written,
                    )
                }
            }
        } catch (failure: Exception) {
            created.forEach { runCatching { it.delete() } }
            throw failure
        }
        return Materialised(kept, warnings, created)
    }

    /** Writes one attachment into [dir] as [name]; returns its size, or null when it has no usable content. */
    private fun writeAttachment(dir: File, name: String, attachment: IssueAttachment, cancellationCheck: () -> Unit = {}): Long? {
        cancellationCheck()
        attachmentWriter?.let { return it(dir, name, attachment).also { cancellationCheck() } }
        val target = File(dir, name)
        val text = attachment.text
        val source = attachment.sourcePath?.let(::File)
        val limit = if (attachment.kind == IssueAttachmentKind.CAPTURE_ARCHIVE) MAX_ISSUE_CAPTURE_ARCHIVE_BYTES else MAX_ISSUE_ATTACHMENT_BYTES
        return when {
            text != null -> writeText(target, text, cancellationCheck)
            source == null || !source.isFile || source.length() > limit -> null
            // A multi-gigabyte archive staged inside this very issue folder is moved into place, not copied: no second copy of it.
            attachment.kind == IssueAttachmentKind.CAPTURE_ARCHIVE && isInside(source, dir.parentFile) -> {
                cancellationCheck()
                moveInto(source, target)
            }
            else -> copyBounded(source, target, limit, cancellationCheck)
        }
    }

    private fun writeText(target: File, text: String, cancellationCheck: () -> Unit): Long? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_ISSUE_ATTACHMENT_BYTES) return null
        cancellationCheck()
        Files.write(target.toPath(), bytes)
        return bytes.size.toLong()
    }

    private fun moveInto(source: File, target: File): Long {
        val size = source.length()
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath())
        }
        return size
    }

    /** Copies [source] to [target]; null (the caller removes the partial file) when it grows past [limit]. */
    private fun copyBounded(source: File, target: File, limit: Long, cancellationCheck: () -> Unit): Long? {
        var copied = 0L
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output ->
                val buffer = ByteArray(ATTACHMENT_COPY_BUFFER_BYTES)
                while (true) {
                    cancellationCheck()
                    val read = input.read(buffer)
                    if (read < 0) break
                    copied += read
                    if (copied > limit) return null
                    output.write(buffer, 0, read)
                }
            }
        }
        return copied
    }

    private fun isInside(file: File, folder: File?): Boolean {
        if (folder == null) return false
        val root = runCatching { folder.canonicalFile }.getOrNull() ?: return false
        val candidate = runCatching { file.canonicalFile }.getOrNull() ?: return false
        return candidate.path.startsWith(root.path + File.separator)
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
