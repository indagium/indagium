package com.indagium.testing

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.isPendingCaptureArchive
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.decodeIssueFile
import com.indagium.testing.store.encodeIssueFile
import java.io.File
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The capture archive of an issue in the store: a pending wish on the draft, moved (not copied) into place once exported. */
class IssueCaptureArchiveStoreTest {
    private val dir: File = createTempDirectory("issue-archive-store").toFile()
    private val store = IssueStore(File(dir, "issues"))

    @AfterTest
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val pending = IssueAttachment(
        IssueAttachmentKind.CAPTURE_ARCHIVE, "Capture archive (log + video + audio + notes) .zip", "capture-archive.zip",
        sizeBytes = 123_456, note = "Pending.",
    )

    private fun create(vararg attachments: IssueAttachment) = (
        store.create(
            IssueDraft(title = "Crash", attachments = attachments.toList()),
            IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-1", caseName = "Login", stepNumber = 2),
        ) as StoreResult.Ok
    )

    @Test
    fun aPendingArchiveIsKeptOnTheDraftWithoutAFileOrAWarningAndSurvivesTheCodec() {
        val created = create(pending)
        assertTrue(created.warnings.isEmpty(), created.warnings.toString())
        val kept = created.value.draft.attachments.single()
        assertTrue(kept.isPendingCaptureArchive)
        assertTrue(kept.include, "checked by default")
        assertFalse(File(store.issueDir(created.value.id), "attachments").exists(), "nothing was written")

        val reloaded = assertNotNull(store.load(created.value.id))
        assertTrue(reloaded.draft.attachments.single().isPendingCaptureArchive)
        assertEquals(123_456, reloaded.draft.attachments.single().sizeBytes)
        val decoded = decodeIssueFile(encodeIssueFile(reloaded)).getOrThrow()
        assertEquals(IssueAttachmentKind.CAPTURE_ARCHIVE, decoded.draft.attachments.single().kind)
    }

    @Test
    fun anExportedArchiveReplacesThePendingOneAndIsMovedIntoTheIssueNotCopied() {
        val created = create(pending, IssueAttachment(IssueAttachmentKind.LOG_RANGE, "Log", "log.txt", text = "rows", include = false))
        val staging = File(store.issueDir(created.value.id), ".capture-archive-${UUID.randomUUID()}").apply { mkdirs() }
        val staged = File(staging, "capture-archive.zip").apply { writeBytes(ByteArray(5_000) { it.toByte() }) }

        val result = store.appendAttachmentStrict(
            created.value.id,
            IssueAttachment(IssueAttachmentKind.CAPTURE_ARCHIVE, pending.label, "capture-archive-x.zip", sizeBytes = 5_000, sourcePath = staged.absolutePath),
            replacePending = true,
        )

        val record = assertIs<StoreResult.Ok<com.indagium.testing.model.IssueRecord>>(result).value
        val archives = record.draft.attachments.filter { it.kind == IssueAttachmentKind.CAPTURE_ARCHIVE }
        assertEquals(1, archives.size, "the pending wish is replaced, not duplicated")
        val stored = store.attachmentFile(record, archives.single())
        assertNotNull(stored)
        assertEquals(5_000L, stored.length())
        assertFalse(staged.exists(), "moved: no second copy of a multi-gigabyte file is left behind")
        assertEquals(1, record.draft.attachments.count { it.kind == IssueAttachmentKind.LOG_RANGE }, "other evidence is untouched")
    }

    @Test
    fun anArchiveOutsideTheIssueFolderIsCopiedAndAnUnusableOneIsRefusedWithoutLosingThePendingWish() {
        val created = create(pending)
        val outside = File(dir, "elsewhere.zip").apply { writeBytes(ByteArray(10)) }
        val copied = store.appendAttachmentStrict(
            created.value.id,
            IssueAttachment(IssueAttachmentKind.CAPTURE_ARCHIVE, pending.label, "capture-archive-y.zip", sourcePath = outside.absolutePath),
            replacePending = true,
        )
        assertIs<StoreResult.Ok<*>>(copied)
        assertTrue(outside.exists(), "a file from elsewhere is copied, never taken away")

        val second = create(pending)
        val missing = store.appendAttachmentStrict(
            second.value.id,
            IssueAttachment(IssueAttachmentKind.CAPTURE_ARCHIVE, pending.label, "gone.zip", sourcePath = File(dir, "gone.zip").absolutePath),
            replacePending = true,
        )
        assertIs<StoreResult.Invalid>(missing)
        assertTrue(assertNotNull(store.load(second.value.id)).draft.attachments.single().isPendingCaptureArchive, "nothing was lost")
    }
}
