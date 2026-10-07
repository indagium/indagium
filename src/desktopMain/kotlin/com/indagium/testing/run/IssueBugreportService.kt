package com.indagium.testing.run

import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueRecord
import com.indagium.testing.store.IssueStore
import com.indagium.testing.store.MAX_ISSUE_ATTACHMENT_BYTES
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.util.UUID

const val ANDROID_BUGREPORT_TIMEOUT_MS = 5 * 60 * 1_000L
private const val BUGREPORT_BYTES_PER_MEGABYTE = 1024 * 1024

/** An injected collector keeps device process behavior testable while UI and MCP share the same issue-owned workflow. */
internal fun interface AndroidBugreportCollector {
    suspend fun collect(deviceSerial: String, destination: File, progress: (String) -> Unit)
}

/** Collects only on explicit invocation, stages below the issue folder, then makes the result a selectable attachment. */
@Suppress("TooGenericExceptionCaught") // Device/adb collector failures are surfaced as an operation result after staging cleanup.
internal suspend fun collectAndroidBugreport(
    store: IssueStore,
    issueId: String,
    collector: AndroidBugreportCollector,
    progress: (String) -> Unit = {},
): Result<IssueRecord> {
    return withContext(Dispatchers.IO) {
        var staging: File? = null
        try {
            val issue = store.load(issueId)
                ?: return@withContext Result.failure(IllegalArgumentException("Issue '$issueId' was not found."))
            require(!issue.readOnly) { "This issue was saved by a newer version and is read-only here." }
            val serial = issue.draft.environment.deviceSerial
            require(serial.isNotBlank()) { "This issue has no source Android device serial." }
            val ownedTemp = File(store.issueDir(issueId), ".bugreport-${UUID.randomUUID()}")
            require(ownedTemp.mkdirs()) { "Could not create an issue-owned temporary folder for the bugreport." }
            staging = ownedTemp
            val archive = File(ownedTemp, "bugreport.zip")
            progress("Collecting Android bugreport from $serial…")
            try {
                withTimeout(ANDROID_BUGREPORT_TIMEOUT_MS) { collector.collect(serial, archive) { progress(it) } }
            } catch (_: TimeoutCancellationException) {
                return@withContext Result.failure(IOException("Android bugreport collection timed out after five minutes."))
            }
            require(archive.isFile && archive.canRead() && archive.length() in 1..MAX_ISSUE_ATTACHMENT_BYTES) {
                "adb did not produce a readable bugreport within the ${MAX_ISSUE_ATTACHMENT_BYTES / BUGREPORT_BYTES_PER_MEGABYTE} MB attachment limit."
            }
            progress("Saving bugreport as an issue attachment…")
            val attachmentName = "android-bugreport-${UUID.randomUUID()}.zip"
            val attachment = IssueAttachment(
                kind = IssueAttachmentKind.BUGREPORT,
                label = "Android bugreport from $serial",
                fileName = attachmentName,
                sizeBytes = archive.length(),
                include = false,
                sourcePath = archive.absolutePath,
                note = "Collected on demand from device $serial.",
            )
            val callerContext = currentCoroutineContext()
            callerContext.ensureActive()
            val updated = store.appendAttachmentStrict(issueId, attachment) { callerContext.ensureActive() }
            when (updated) {
                is StoreResult.Ok -> {
                    val saved = updated.value.draft.attachments.lastOrNull { it.kind == IssueAttachmentKind.BUGREPORT && it.fileName == attachmentName }
                    if (saved?.storedPath == null || store.attachmentFile(updated.value, saved) == null) {
                        Result.failure(IOException("The collected bugreport could not be verified in the issue folder."))
                    } else {
                        Result.success(updated.value)
                    }
                }
                is StoreResult.Invalid -> Result.failure(IOException(updated.reason))
                is StoreResult.NotFound -> Result.failure(IOException(updated.message))
                is StoreResult.LimitReached -> Result.failure(IOException(updated.decision.message))
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            Result.failure(failure)
        } finally {
            staging?.deleteRecursively()
        }
    }
}
