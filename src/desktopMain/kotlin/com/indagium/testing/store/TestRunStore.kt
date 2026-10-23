package com.indagium.testing.store

import com.indagium.testing.model.RunCaseOutcomes
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.SUITE_HEALTH_RUN_LIMIT
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.caseOutcomes
import com.indagium.testing.model.isSafeId
import com.indagium.testing.model.summary
import com.indagium.utils.writeFileAtomically
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption

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
private const val MAX_SUITE_SUMMARIES = 10_000
private const val RECENT_SUITE_RECORDS = 5

/**
 * [finishedOutcomes]: the compact case outcomes of the suite's newest [SUITE_HEALTH_RUN_LIMIT] finished runs, newest first. Appended
 * last; it holds no full runs, so it stays small however large the run files are.
 */
data class StoredSuiteRunHistory(
    val recentRecords: List<TestRun>,
    val summaries: List<RunSummary>,
    val finishedOutcomes: List<RunCaseOutcomes> = emptyList(),
)

private val FINISHED_OUTCOME_ORDER = compareByDescending<RunCaseOutcomes> { it.createdAt }.thenByDescending { it.runId }

/**
 * The newest finished run of each requested suite ([bySuite]; a suite with none is absent) and the newest finished run of any
 * suite ([latestOverall]), as found by [TestRunStore.latestSummariesBySuite].
 */
data class StoredLatestRuns(val bySuite: Map<String, RunSummary>, val latestOverall: RunSummary?)

private fun RunSummary.isNewerThan(other: RunSummary): Boolean =
    createdAt > other.createdAt || (createdAt == other.createdAt && id > other.id)

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

    /** Full persisted run snapshots, newest first; bounded by the same on-disk listing limit as [list]. */
    fun listRecords(): List<TestRun> {
        val folders = baseDir().listFiles { file -> file.isDirectory && isSafeId(file.name) }.orEmpty()
        return folders.sortedByDescending { File(it, TEST_RUN_FILE_NAME).lastModified() }
            .take(MAX_LISTED_RUNS)
            .mapNotNull { load(it.name) }
            .sortedByDescending { it.createdAt }
    }

    /**
     * Filter a suite before limiting, so unrelated recent runs cannot hide its history. The Runs tab keeps lightweight
     * summaries; only the five most recent full snapshots and the latest terminal snapshot are retained.
     */
    fun listSuiteHistory(suiteId: String): StoredSuiteRunHistory {
        if (!isSafeId(suiteId)) return StoredSuiteRunHistory(emptyList(), emptyList())
        val summaries = ArrayList<RunSummary>()
        val recent = ArrayList<TestRun>()
        val outcomes = ArrayList<RunCaseOutcomes>()
        var latestTerminal: TestRun? = null
        val recordOrder = compareByDescending<TestRun> { it.createdAt }.thenByDescending { it.finishedAt ?: 0L }
        val folders = baseDir().listFiles { file -> file.isDirectory && isSafeId(file.name) }.orEmpty()
            .sortedByDescending { File(it, TEST_RUN_FILE_NAME).lastModified() }
        for (folder in folders) {
            val run = load(folder.name)
            if (run?.config?.suiteId == suiteId) {
                summaries += run.summary()
                recent += run
                recent.sortWith(recordOrder)
                if (recent.size > RECENT_SUITE_RECORDS) recent.removeAt(recent.lastIndex)
                if (run.isFinished) {
                    outcomes += run.caseOutcomes()
                    outcomes.sortWith(FINISHED_OUTCOME_ORDER)
                    if (outcomes.size > SUITE_HEALTH_RUN_LIMIT) outcomes.removeAt(outcomes.lastIndex)
                    val previousTerminal = latestTerminal
                    if (previousTerminal == null || recordOrder.compare(run, previousTerminal) < 0) latestTerminal = run
                }
                if (summaries.size >= MAX_SUITE_SUMMARIES) break
            }
        }
        val summaryOrder = compareByDescending<RunSummary> { it.createdAt }.thenByDescending { it.id }
        val records = (recent + listOfNotNull(latestTerminal)).distinctBy { it.id }.sortedWith(recordOrder)
        return StoredSuiteRunHistory(records, summaries.sortedWith(summaryOrder), outcomes.toList())
    }

    /**
     * The newest FINISHED run of each of [suiteIds] and of any suite, for the New tab's Tests section. Walks the run folders newest-first
     * (by `run.json` modification time) and stops as soon as every requested suite has a finished run and an overall latest is known, so
     * it decodes no more run files than needed (a run file can be up to 64 MB). At most [scanLimit] folders are read; an unfinished run
     * (queued or running) never counts, and a missing base folder gives an empty result.
     */
    fun latestSummariesBySuite(suiteIds: Set<String>, scanLimit: Int = MAX_LISTED_RUNS): StoredLatestRuns {
        val wanted = suiteIds.filterTo(HashSet()) { isSafeId(it) }
        val found = HashMap<String, RunSummary>()
        var overall: RunSummary? = null
        val folders = baseDir().listFiles { file -> file.isDirectory && isSafeId(file.name) }.orEmpty()
            .sortedByDescending { File(it, TEST_RUN_FILE_NAME).lastModified() }
            .take(scanLimit.coerceAtLeast(0))
        for (folder in folders) {
            val run = load(folder.name)?.takeIf { it.isFinished } ?: continue
            val summary = run.summary()
            if (overall == null || summary.isNewerThan(overall)) overall = summary
            val suiteId = run.config.suiteId
            if (suiteId in wanted) {
                val previous = found[suiteId]
                if (previous == null || summary.isNewerThan(previous)) found[suiteId] = summary
            }
            if (found.size == wanted.size) break
        }
        return StoredLatestRuns(found, overall)
    }

    // ── Deleting and sizes ───────────────────────────────────────────

    /** The total size in bytes of one run folder (0 when there is none or the id is unsafe). */
    fun sizeOf(runId: String): Long {
        if (!isSafeId(runId)) return 0L
        return File(baseDir(), runId).takeIf { Files.isDirectory(it.toPath(), LinkOption.NOFOLLOW_LINKS) }?.let(::treeSize) ?: 0L
    }

    /** The size in bytes of every run folder under the base folder, by run id. Walks the disk: call on an IO thread. */
    fun sizes(): Map<String, Long> =
        baseDir().listFiles { file -> file.isDirectory && isSafeId(file.name) }.orEmpty().associate { it.name to sizeOf(it.name) }

    /**
     * Removes the folder of [runId] and everything in it (its `run.json`, evidence, transcripts). Only a folder directly inside the base
     * folder that holds a `run.json` is removed: an unsafe id, a symbolic link, a folder whose real location is elsewhere, or a folder
     * that is not a run folder is refused and nothing is touched. The store cannot tell whether the run is still being written; the
     * caller ([com.indagium.testing.run.TestRunCoordinator.deleteRun]) refuses a queued or running run before calling this.
     */
    fun delete(runId: String): StoreResult<Unit> {
        if (!isSafeId(runId)) return StoreResult.Invalid("That is not a valid run id.")
        val dir = File(baseDir(), runId)
        val path = dir.toPath()
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return StoreResult.NotFound("run", runId)
        if (Files.isSymbolicLink(path) || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
            return StoreResult.Invalid("Run $runId is not a plain run folder, so it was not deleted.")
        }
        val inside = runCatching { path.toRealPath().parent == baseDir().toPath().toRealPath() }.getOrDefault(false)
        if (!inside) return StoreResult.Invalid("Run $runId is outside the test runs folder, so it was not deleted.")
        if (!File(dir, TEST_RUN_FILE_NAME).isFile) return StoreResult.Invalid("Folder $runId has no $TEST_RUN_FILE_NAME, so it is not treated as a run.")
        return if (deleteTree(dir)) {
            lastError = null
            StoreResult.Ok(Unit)
        } else {
            lastError = "Could not delete everything in run $runId."
            StoreResult.Invalid("Could not delete everything in run $runId. Some files may be in use.")
        }
    }

    fun listRecordsForSuite(suiteId: String): List<TestRun> = listSuiteHistory(suiteId).recentRecords

    fun listSummariesForSuite(suiteId: String): List<RunSummary> = listSuiteHistory(suiteId).summaries
}
