package com.indagium.testing.store

import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.summary
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.io.IOException

// The run folders of AI test runs:
//   <base>/<runId>/run.json                       the TestRun (frozen suite + results), written atomically
//   <base>/<runId>/lanes/<laneId>/capture/        the lane's recorded logcat (and video)
//   <base>/<runId>/lanes/<laneId>/screens/        step screenshots
//   <base>/<runId>/lanes/<laneId>/transcript.jsonl  the agent transcript, secrets redacted
//   <base>/<runId>/judge.jsonl                    what the judge runs of the whole run said and did, secrets redacted
// [baseDir] is evaluated on every use (the save folder is a user setting that can change); nothing is created until a
// run starts. The store holds no lock: a run is written by one persister at a time (RunPersister), and readers only
// ever see complete files because a write replaces run.json atomically.

const val TEST_RUN_FILE_NAME = "run.json"
const val TEST_RUN_LANES_DIR_NAME = "lanes"
const val TEST_RUN_SCREENS_DIR_NAME = "screens"
const val TEST_RUN_TRANSCRIPT_FILE_NAME = "transcript.jsonl"
const val TEST_RUN_JUDGE_FILE_NAME = "judge.jsonl"
const val MAX_RUN_FILE_BYTES = 64L * 1024L * 1024L
private const val MAX_LISTED_RUNS = 200

class TestRunStore(private val baseDir: () -> File) {
    @Volatile
    var lastError: String? = null
        private set

    /** The folder of [runId]. Not created here. Throws [IllegalArgumentException] for an id that could leave the base folder. */
    fun runDir(runId: String): File {
        require(isSafeId(runId)) { "Unsafe run id" }
        return File(baseDir(), runId)
    }

    fun laneDir(runId: String, laneId: String): File {
        require(isSafeId(laneId)) { "Unsafe lane id" }
        return File(File(runDir(runId), TEST_RUN_LANES_DIR_NAME), laneId)
    }

    /** Writes `run.json` atomically; a failure is returned (and kept in [lastError]), never thrown. */
    fun save(run: TestRun): Result<Unit> = try {
        val file = File(runDir(run.id), TEST_RUN_FILE_NAME)
        val text = encodeRunFile(run)
        writeFileAtomically(file) { it.write(text) }
        lastError = null
        Result.success(Unit)
    } catch (failure: IOException) {
        lastError = "Could not save the test run: ${failure.message}"
        Result.failure(failure)
    }

    /** The stored run, or null when it is missing, unreadable or not a test run. */
    fun load(runId: String): TestRun? {
        if (!isSafeId(runId)) return null
        val file = File(runDir(runId), TEST_RUN_FILE_NAME)
        if (!file.isFile || file.length() > MAX_RUN_FILE_BYTES) return null
        return runCatching { decodeRunFile(file.readText()).getOrNull() }.getOrNull()
    }

    /** Summaries of the stored runs, newest first. A run folder without a readable run.json is skipped. */
    fun list(): List<RunSummary> {
        val folders = baseDir().listFiles { file -> file.isDirectory && isSafeId(file.name) }.orEmpty()
        return folders.sortedByDescending { File(it, TEST_RUN_FILE_NAME).lastModified() }
            .take(MAX_LISTED_RUNS)
            .mapNotNull { load(it.name)?.summary() }
            .sortedByDescending { it.createdAt }
    }
}
