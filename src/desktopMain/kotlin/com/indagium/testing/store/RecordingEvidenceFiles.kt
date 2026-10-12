package com.indagium.testing.store

import com.indagium.testing.model.isSafeId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

// Where the evidence of an applied recording lives ("Keep recording evidence with recorded cases"), inside the Test suites folder, next to
// the golden screenshots of the same suite:
//
//   <Test suites folder>/assets/<suiteId>/recordings/<recordingId>/
//       recording.json            the versioned envelope (testing/authoring/RecordingEvidenceCodec.kt)
//       screens/<hash>.jpg        every screenshot the rows and screen readings point at, by content hash
//       video/timeline-<n>.mkv    the recorded video segments, and
//       video/timeline.jsonl      their packet index (only when no password was typed and the size cap allowed it)
//
// This file is the byte-level part, UI-free and independent of the recording types: paths that cannot leave the folder, sizes,
// deleting, moving a case's recordings with the case and publishing a finished folder in one step. TestLibraryStore uses it to
// remove recordings with the case or suite that owns them.

const val RECORDINGS_DIR_NAME = "recordings"
const val RECORDING_FILE_NAME = "recording.json"
const val RECORDING_SCREENS_DIR_NAME = "screens"
const val RECORDING_VIDEO_DIR_NAME = "video"
const val RECORDING_SCREEN_EXTENSION = ".jpg"

/** Prefix of a recording id; the id is also the folder name. */
const val RECORDING_ID_PREFIX = "rec-"

/** A folder being written; it is renamed to its final name only when complete, and a leftover one is removed on the next cleanup. */
private const val STAGING_PREFIX = ".staging-"

/** The key of `recording.json` that names the case a recording belongs to (a case id is unique across the library and survives a move). */
private const val CASE_ID_KEY = "caseId"

class RecordingEvidenceFiles(private val rootDir: File) {
    /** `<root>/assets/<suiteId>/recordings`, or null when [suiteId] is unsafe or an assets folder on the way is a symbolic link. */
    fun recordingsDir(suiteId: String): File? {
        if (!isSafeId(suiteId)) return null
        val assets = File(rootDir, TEST_ASSETS_DIR_NAME)
        val suiteDir = File(assets, suiteId)
        if (Files.isSymbolicLink(assets.toPath()) || Files.isSymbolicLink(suiteDir.toPath())) return null
        return File(suiteDir, RECORDINGS_DIR_NAME).takeUnless { Files.isSymbolicLink(it.toPath()) }
    }

    /** The folder of one recording, or null for an unsafe id. Not created here. */
    fun recordingDir(suiteId: String, recordingId: String): File? {
        if (!isSafeId(recordingId) || recordingId.startsWith(".")) return null
        return recordingsDir(suiteId)?.let { File(it, recordingId) }?.takeUnless { Files.isSymbolicLink(it.toPath()) }
    }

    fun exists(suiteId: String, recordingId: String): Boolean =
        recordingDir(suiteId, recordingId)?.let { File(it, RECORDING_FILE_NAME).isFile } == true

    /** A fresh staging folder next to the final ones; [publish] makes it the recording. */
    fun newStagingDir(suiteId: String): File? {
        val parent = recordingsDir(suiteId) ?: return null
        Files.createDirectories(parent.toPath())
        return Files.createTempDirectory(parent.toPath(), STAGING_PREFIX).toFile()
    }

    /**
     * Publishes the finished [staging] folder as recording [recordingId] in one rename, replacing a folder of that name (a reopened
     * recording that was saved again). The old folder is removed after the new one is in place.
     */
    fun publish(suiteId: String, recordingId: String, staging: File): Boolean {
        val target = recordingDir(suiteId, recordingId) ?: return false
        val old = if (target.exists()) File(target.parentFile, "$STAGING_PREFIX${target.name}-old").also { deleteTree(it) } else null
        if (old != null) moveDir(target, old)
        try {
            moveDir(staging, target)
        } catch (failure: IOException) {
            if (old != null) runCatching { moveDir(old, target) }
            throw failure
        }
        old?.let(::deleteTree)
        return true
    }

    /** The ids of the recordings stored for [suiteId] (folders with a `recording.json`). */
    fun listIds(suiteId: String): List<String> {
        val dir = recordingsDir(suiteId) ?: return emptyList()
        return dir.listFiles { file -> file.isDirectory && !file.name.startsWith(".") && File(file, RECORDING_FILE_NAME).isFile }
            .orEmpty().map { it.name }.sorted()
    }

    /** Every suite id that has a recordings folder. */
    fun suiteIdsWithRecordings(): List<String> =
        File(rootDir, TEST_ASSETS_DIR_NAME).listFiles { file -> file.isDirectory && File(file, RECORDINGS_DIR_NAME).isDirectory }
            .orEmpty().map { it.name }.filter(::isSafeId).sorted()

    /** The case a stored recording belongs to, read from its `recording.json`; null when it cannot be read. */
    fun caseIdOf(suiteId: String, recordingId: String): String? {
        val file = recordingDir(suiteId, recordingId)?.let { File(it, RECORDING_FILE_NAME) } ?: return null
        if (!file.isFile || file.length() > MAX_RECORDING_FILE_BYTES) return null
        val root = runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull() ?: return null
        return root.stringOrNull(CASE_ID_KEY)
    }

    /** The recordings of [caseId] in [suiteId]: those named in [knownIds] and those whose `recording.json` names the case. */
    fun idsOfCase(suiteId: String, caseId: String, knownIds: Collection<String> = emptyList()): List<String> =
        (knownIds.filter { exists(suiteId, it) } + listIds(suiteId).filter { caseIdOf(suiteId, it) == caseId }).distinct()

    /** Total size in bytes of one recording's folder. */
    fun sizeOf(suiteId: String, recordingId: String): Long = recordingDir(suiteId, recordingId)?.let(::treeSize) ?: 0L

    /** Total size in bytes of all stored recordings under the root folder, and how many there are. */
    fun usage(): RecordingEvidenceUsage {
        var bytes = 0L
        var count = 0
        for (suiteId in suiteIdsWithRecordings()) {
            for (id in listIds(suiteId)) {
                count++
                bytes += sizeOf(suiteId, id)
            }
        }
        return RecordingEvidenceUsage(count, bytes)
    }

    /** Removes one recording; false when it was not there. */
    fun delete(suiteId: String, recordingId: String): Boolean {
        val dir = recordingDir(suiteId, recordingId)?.takeIf { it.exists() } ?: return false
        return deleteTree(dir)
    }

    fun deleteCase(suiteId: String, caseId: String, knownIds: Collection<String> = emptyList()) {
        idsOfCase(suiteId, caseId, knownIds).forEach { delete(suiteId, it) }
    }

    /** Removes every recording of [suiteId] (the golden screenshots beside them stay). */
    fun deleteSuite(suiteId: String) {
        recordingsDir(suiteId)?.takeIf { it.exists() }?.let(::deleteTree)
    }

    /** Removes every stored recording of every suite and returns how many were removed. */
    fun deleteAll(): RecordingEvidenceUsage {
        val before = usage()
        suiteIdsWithRecordings().forEach(::deleteSuite)
        return before
    }

    /** Moves the recordings of [caseId] along with the case into another suite. A recording that cannot be moved stays where it was. */
    fun moveCase(fromSuiteId: String, toSuiteId: String, caseId: String, knownIds: Collection<String> = emptyList()) {
        if (fromSuiteId == toSuiteId) return
        val toDir = recordingsDir(toSuiteId) ?: return
        for (id in idsOfCase(fromSuiteId, caseId, knownIds)) {
            val from = recordingDir(fromSuiteId, id) ?: continue
            val to = File(toDir, id)
            if (to.exists()) continue
            runCatching {
                Files.createDirectories(toDir.toPath())
                moveDir(from, to)
            }
        }
    }

    /** Removes staging folders a crash left behind. */
    fun removeLeftovers(suiteId: String) {
        recordingsDir(suiteId)?.listFiles { file -> file.name.startsWith(STAGING_PREFIX) }.orEmpty().forEach(::deleteTree)
    }
}

/**
 * Where the removal of recordings runs: one background thread, so a delete on the UI thread or an MCP request never waits for a big folder,
 * and removals happen in the order they were asked for. [awaitIdle] is for tests and shutdown.
 */
object RecordingCleanup {
    private const val THREAD_NAME = "recording-evidence-cleanup"
    private val executor = Executors.newSingleThreadExecutor { task -> Thread(task, THREAD_NAME).apply { isDaemon = true } }

    fun run(task: () -> Unit) {
        try {
            executor.execute { runCatching(task) }
        } catch (_: RejectedExecutionException) {
            // The process is shutting down; whatever is left is removed by the next "Delete stored recording evidence".
        }
    }

    /** True once everything asked for before this call has run. */
    fun awaitIdle(timeoutMs: Long): Boolean = runCatching { executor.submit {}.get(timeoutMs, TimeUnit.MILLISECONDS) }.isSuccess
}

/** How many recordings are stored and how much disk they use. */
data class RecordingEvidenceUsage(val recordings: Int, val bytes: Long)

/** The largest `recording.json` that is read back; a bigger file is not a recording this build wrote. */
const val MAX_RECORDING_FILE_BYTES = 64L * 1024L * 1024L

private fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content

private fun moveDir(source: File, target: File) {
    try {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath())
    }
}

/** The total size in bytes of the regular files under [root] (symbolic links are not followed); 0 when it cannot be read. */
internal fun treeSize(root: File): Long {
    var total = 0L
    runCatching {
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    total += attrs.size()
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }
    return total
}

/** Deletes [root] and everything in it without following symbolic links; false when something could not be removed. */
internal fun deleteTree(root: File): Boolean {
    if (!Files.exists(root.toPath(), LinkOption.NOFOLLOW_LINKS)) return true
    return runCatching {
        Files.walkFileTree(
            root.toPath(),
            object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            },
        )
    }.isSuccess
}
