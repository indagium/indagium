package com.indagium.testing.authoring

import com.indagium.model.AiUsageStats
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * What an apply does beyond inserting the reviewed steps at an index (all optional; the default is the plain insert).
 * - [recordingRef] is stamped on every step made ([com.indagium.testing.model.TestStep.recordingRef]).
 * - [replaceStepIds] replaces those steps, in place, instead of inserting (see [planReplacement]).
 * - [afterApplied] runs once the steps are in the library, still on the IO thread and while the reviewed rows are frozen; it saves the
 *   recording's evidence and returns warnings for the person. It must not throw for an expected problem.
 */
internal class RecordingApplyOptions(
    val recordingRef: String? = null,
    val replaceStepIds: List<String>? = null,
    val afterApplied: ((snapshot: TestStepRecordingSnapshot, inserted: List<TestStep>) -> List<String>)? = null,
    val replaceStepBaseline: List<TestStep>? = null,
)

/** Replaces the steps [replacedIds] of a case with [steps] in one write; see TestLibraryStore.replaceSteps. */
internal typealias ReplaceStepsWithUsage = (
    caseId: String,
    replacedIds: List<String>,
    steps: List<TestStep>,
    usage: AiUsageStats?,
    baseline: List<TestStep>?,
) -> StoreResult<List<TestStep>>

/** Shared UI/MCP apply path for a stopped recording. Asset imports are staged and removed if insertion fails. */
internal class TestStepRecordingApplyService(
    private val preflight: (suiteId: String, caseId: String) -> StoreResult<Unit>,
    private val importImage: (suiteId: String, source: File) -> StoreResult<String>,
    private val resolveImage: (suiteId: String, assetPath: String) -> File?,
    private val insertSteps: (caseId: String, steps: List<TestStep>, index: Int?) -> StoreResult<List<TestStep>>,
    private val insertStepsWithUsage: (caseId: String, steps: List<TestStep>, index: Int?, usage: AiUsageStats?) -> StoreResult<List<TestStep>> =
        { targetCaseId, steps, insertion, _ -> insertSteps(targetCaseId, steps, insertion) },
    /** Replaces steps of a case with new ones in one write (TestLibraryStore.replaceSteps); null when the caller cannot replace. */
    private val replaceStepsWithUsage: ReplaceStepsWithUsage? = null,
) {
    private val applyingSessionIds = ConcurrentHashMap.newKeySet<String>()

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught", "LongMethod")
    suspend fun apply(
        session: TestStepRecordingSession,
        suiteId: String,
        caseId: String,
        index: Int? = null,
        options: RecordingApplyOptions = RecordingApplyOptions(),
    ): StoreResult<List<TestStep>> {
        if (!applyingSessionIds.add(session.id)) return StoreResult.Invalid("This recording is already being applied.")
        var frozeSnapshot = false
        return try {
            withContext<StoreResult<List<TestStep>>>(Dispatchers.IO) {
                val snapshot = session.freezeReviewedSnapshotForApply()
                    ?: return@withContext StoreResult.Invalid("Stop recording and finish screen snapshots before applying its draft.")
                frozeSnapshot = true
                if (snapshot.active) return@withContext StoreResult.Invalid("Stop recording before applying its draft.")
                if (snapshot.pendingSnapshots > 0) return@withContext StoreResult.Invalid("Wait for captured screen snapshots to finish before applying.")
                if (snapshot.steps.isEmpty()) {
                    return@withContext StoreResult.Invalid("No steps remain to apply. Restore removed inputs or add a reviewed step first.")
                }
                if (snapshot.steps.any { recordingRowIssues(it).isNotEmpty() }) {
                    return@withContext StoreResult.Invalid("Add an action and expected result to every required step, and a condition to each optional step.")
                }
                when (val allowed = preflight(suiteId, caseId)) {
                    is StoreResult.Ok -> Unit
                    is StoreResult.Invalid -> return@withContext StoreResult.Invalid(allowed.reason)
                    is StoreResult.LimitReached -> return@withContext StoreResult.LimitReached(allowed.decision)
                    is StoreResult.NotFound -> return@withContext StoreResult.NotFound(allowed.kind, allowed.id)
                }
                val copied = mutableListOf<String>()
                try {
                    val steps = session.toTestSteps(snapshot.steps) { bytes ->
                        val temporary = File.createTempFile("indagium-recorded-screen-", ".jpg")
                        try {
                            temporary.writeBytes(bytes)
                            when (val result = importImage(suiteId, temporary)) {
                                is StoreResult.Ok -> result.value.also(copied::add)
                                is StoreResult.Invalid -> throw IllegalArgumentException(result.reason)
                                is StoreResult.LimitReached -> throw IllegalArgumentException(result.decision.message)
                                is StoreResult.NotFound -> throw IllegalArgumentException("${result.kind} '${result.id}' was not found.")
                            }
                        } finally {
                            temporary.delete()
                        }
                    }
                    val stamped = options.recordingRef?.let { ref -> steps.map { it.copy(recordingRef = ref) } } ?: steps
                    val replaced = options.replaceStepIds
                    val outcome = when {
                        replaced == null -> insertStepsWithUsage(caseId, stamped, index, snapshot.rewriteUsagePending)
                        replaceStepsWithUsage == null -> StoreResult.Invalid("Replacing the steps of a recording is not available here.")
                        else -> replaceStepsWithUsage.invoke(caseId, replaced, stamped, snapshot.rewriteUsagePending, options.replaceStepBaseline)
                    }
                    when (val inserted = outcome) {
                        is StoreResult.Ok -> {
                            session.markRewriteUsageApplied(snapshot.rewriteAttemptCount)
                            // The steps are in the library now: whatever the evidence save does, the applied screenshots must stay.
                            val warnings = runCatching { options.afterApplied?.invoke(snapshot, inserted.value).orEmpty() }
                                .getOrElse { listOf("The recording's evidence was not saved: ${it.message ?: it::class.simpleName}") }
                            if (warnings.isEmpty()) inserted else StoreResult.Ok(inserted.value, inserted.warnings + warnings)
                        }
                        else -> {
                            copied.forEach { resolveImage(suiteId, it)?.delete() }
                            inserted
                        }
                    }
                } catch (cancelled: CancellationException) {
                    copied.forEach { resolveImage(suiteId, it)?.delete() }
                    throw cancelled
                } catch (failure: Exception) {
                    copied.forEach { resolveImage(suiteId, it)?.delete() }
                    StoreResult.Invalid(failure.message ?: "Could not apply recorded steps.")
                }
            }
        } finally {
            if (frozeSnapshot) session.releaseApplyReservation()
            applyingSessionIds.remove(session.id)
        }
    }
}
