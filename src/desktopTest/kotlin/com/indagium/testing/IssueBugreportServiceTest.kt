package com.indagium.testing

import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueEnvironment
import com.indagium.testing.model.IssueSource
import com.indagium.testing.run.AndroidBugreportCollector
import com.indagium.testing.run.collectAndroidBugreport
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.MAX_ISSUE_ATTACHMENT_BYTES
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class IssueBugreportServiceTest {
    @Test
    fun explicitCollectionPublishesUncheckedIssueOwnedAttachmentAndRemovesStaging() = runBlocking {
        val root = createTempDirectory("bugreport-service").toFile()
        try {
            val store = IssueStore(File(root, "issues"))
            val issue = createIssue(store)
            val result = collectAndroidBugreport(store, issue.id, AndroidBugreportCollector { serial, output, _ ->
                assertEquals("SERIAL-1", serial)
                output.writeBytes(byteArrayOf(1, 2, 3, 4))
            }).getOrThrow()
            val attachment = result.draft.attachments.single()
            assertEquals(IssueAttachmentKind.BUGREPORT, attachment.kind)
            assertFalse(attachment.include)
            assertTrue(store.attachmentFile(result, attachment)!!.isFile)
            assertTrue(File(store.issueDir(issue.id).path).listFiles().orEmpty().none { it.name.startsWith(".bugreport-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun invalidOrOversizedCollectorOutputIsNotPublished() = runBlocking {
        val root = createTempDirectory("bugreport-invalid").toFile()
        try {
            val store = IssueStore(File(root, "issues"))
            val issue = createIssue(store)
            val empty = collectAndroidBugreport(store, issue.id, AndroidBugreportCollector { _, output, _ -> output.createNewFile() })
            assertTrue(empty.isFailure)
            val huge = collectAndroidBugreport(store, issue.id, AndroidBugreportCollector { _, output, _ ->
                RandomAccessFile(output, "rw").use { it.setLength(MAX_ISSUE_ATTACHMENT_BYTES + 1) }
            })
            assertTrue(huge.isFailure)
            assertTrue(store.load(issue.id)!!.draft.attachments.isEmpty())
            assertTrue(File(store.issueDir(issue.id).path).listFiles().orEmpty().none { it.name.startsWith(".bugreport-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationBeforePublicationCleansTemporaryFolderAndLeavesIssueUnchanged() = runBlocking {
        val root = createTempDirectory("bugreport-cancel").toFile()
        try {
            val store = IssueStore(File(root, "issues"))
            val issue = createIssue(store)
            val started = CompletableDeferred<Unit>()
            val job = launch {
                collectAndroidBugreport(store, issue.id, AndroidBugreportCollector { _, _, _ ->
                    started.complete(Unit)
                    awaitCancellation()
                })
            }
            started.await()
            job.cancelAndJoin()
            assertTrue(store.load(issue.id)!!.draft.attachments.isEmpty())
            assertTrue(File(store.issueDir(issue.id).path).listFiles().orEmpty().none { it.name.startsWith(".bugreport-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun cancellationAfterCollectionButBeforePublicationDoesNotAddTheAttachment() = runBlocking {
        val root = createTempDirectory("bugreport-cancel-publish").toFile()
        try {
            val store = IssueStore(File(root, "issues"))
            val issue = createIssue(store)
            lateinit var job: Job
            job = launch(start = CoroutineStart.LAZY) {
                collectAndroidBugreport(
                    store,
                    issue.id,
                    AndroidBugreportCollector { _, output, _ -> output.writeBytes(byteArrayOf(4, 5, 6)) },
                ) { message -> if (message.startsWith("Saving bugreport")) job.cancel() }
            }
            job.start()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(store.load(issue.id)!!.draft.attachments.isEmpty())
            assertTrue(File(store.issueDir(issue.id).path).listFiles().orEmpty().none { it.name.startsWith(".bugreport-") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aCopyFailureDoesNotLeaveASelectableBrokenAttachment() = runBlocking {
        val root = createTempDirectory("bugreport-copy-failure").toFile()
        try {
            val store = IssueStore(
                File(root, "issues"),
                attachmentWriter = { _, _, _ -> null },
            )
            val issue = createIssue(store)
            val result = collectAndroidBugreport(store, issue.id, AndroidBugreportCollector { _, output, _ -> output.writeBytes(byteArrayOf(9)) })
            assertTrue(result.isFailure)
            assertTrue(store.load(issue.id)!!.draft.attachments.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun callerCancellationDuringAttachmentCopyRemovesStagedBytesBeforePublication() = runBlocking {
        val root = createTempDirectory("bugreport-copy-cancel").toFile()
        val enteredCopy = CountDownLatch(1)
        val releaseCopy = CountDownLatch(1)
        try {
            val store = IssueStore(
                File(root, "issues"),
                attachmentWriter = { dir, name, attachment ->
                    val target = File(dir, name)
                    target.writeBytes(File(attachment.sourcePath!!).readBytes())
                    enteredCopy.countDown()
                    check(releaseCopy.await(5, TimeUnit.SECONDS))
                    target.length()
                },
            )
            val issue = createIssue(store)
            val completed = CompletableDeferred<Result<com.indagium.testing.model.IssueRecord>>()
            val job = launch(Dispatchers.IO) {
                completed.complete(collectAndroidBugreport(store, issue.id, AndroidBugreportCollector { _, output, _ -> output.writeBytes(byteArrayOf(1, 2)) }))
            }
            val reached = withContext(Dispatchers.IO) { enteredCopy.await(5, TimeUnit.SECONDS) }
            if (!reached) {
                job.join()
                assertTrue(reached, "the store reached the staged-copy boundary; service result: ${completed.await().exceptionOrNull()}")
            }
            job.cancel()
            releaseCopy.countDown()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue(store.load(issue.id)!!.draft.attachments.isEmpty())
            assertTrue(File(store.issueDir(issue.id), "attachments").listFiles().orEmpty().isEmpty())
            assertTrue(File(store.issueDir(issue.id).path).listFiles().orEmpty().none { it.name.startsWith(".bugreport-") })
        } finally {
            releaseCopy.countDown()
            root.deleteRecursively()
        }
    }

    private fun createIssue(store: IssueStore) = assertIs<StoreResult.Ok<com.indagium.testing.model.IssueRecord>>(
        store.create(
            IssueDraft(title = "Synthetic issue", environment = IssueEnvironment(deviceSerial = "SERIAL-1")),
            IssueSource("run-1", "lane-1", "suite-1", "case-1", "step-1"),
        ),
    ).value
}
