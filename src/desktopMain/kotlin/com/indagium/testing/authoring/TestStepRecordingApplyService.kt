package com.indagium.testing.authoring

import com.indagium.model.AiUsageStats
import com.indagium.testing.device.replayEligibilityProblem
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.withKind
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
 * - [asReplay] makes every step that can be replayed a REPLAY step (it injects its recorded input itself) instead of an AI step; the
 *   others stay AI steps and the result says how many.
 * - [replayRowIds] selects only those reviewed rows for replay. Invalid or ineligible selections are refused before importing assets.
 * - [afterApplied] runs once the steps are in the library, still on the IO thread and while the reviewed rows are frozen; it saves the
 *   recording's evidence and returns warnings for the person. It must not throw for an expected problem.
 */
internal class RecordingApplyOptions(
    val recordingRef: String? = null,
    val replaceStepIds: List<String>? = null,
    val afterApplied: ((snapshot: TestStepRecordingSnapshot, inserted: List<TestStep>) -> List<String>)? = null,
    val replaceStepBaseline: List<TestStep>? = null,
    val asReplay: Boolean = false,
    val replayRowIds: Set<String> = emptySet(),
) {
    fun withReplay(on: Boolean): RecordingApplyOptions =
        if (on == asReplay) this else RecordingApplyOptions(recordingRef, replaceStepIds, afterApplied, replaceStepBaseline, on, replayRowIds)

    fun withReplayRows(ids: Set<String>): RecordingApplyOptions =
        if (ids == replayRowIds) this else RecordingApplyOptions(recordingRef, replaceStepIds, afterApplied, replaceStepBaseline, asReplay, ids.toSet())
}

/**
 * [step] as a REPLAY step when it can be one: an AI step with a replayable recorded input, no optional condition (a replay cannot decide
 * to skip), no stated precondition (a replay cannot establish one) and the perform-action intent (an ensure-state step must look
 * before it acts). Otherwise [step] unchanged.
 */
internal fun asReplayStepOrSame(step: TestStep): TestStep =
    if (step.canBeReplay()) step.withKind(StepKind.REPLAY) else step

internal fun TestStep.canBeReplay(): Boolean =
    kind == StepKind.AGENT && replayEligibilityProblem() == null

/** Replaces the steps [replacedIds] of a case with [steps] in one write; see TestLibraryStore.replaceSteps. */
internal typealias ReplaceStepsWithUsage = (
    caseId: String,
    replacedIds: List<String>,
    steps: List<TestStep>,
    usage: AiUsageStats?,
    baseline: List<TestStep>?,
) -> StoreResult<List<TestStep>>

private fun replaySummary(steps: List<TestStep>, allEligible: Boolean): List<String> {
    val replayed = steps.count { it.kind == StepKind.REPLAY }
    val kept = steps.size - replayed
    return listOfNotNull(
        ("$replayed step(s) were applied as replay steps and will inject their recorded path, positions and timing where the device " +
            "supports it.").takeIf { replayed > 0 },
        (if (allEligible) {
            "$kept step(s) stay AI steps because they cannot be replayed (typed text, rotation, a condition or precondition, or input " +
                "withheld for a password)."
        } else {
            "$kept step(s) keep their reviewed type; unselected rows are not converted to replay."
        }).takeIf { kept > 0 },
    )
}

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
                val eligibleRows = session.replayableRowIds()
                if (!eligibleRows.containsAll(options.replayRowIds)) {
                    return@withContext StoreResult.Invalid(
                        "A selected replay row is missing or cannot be replayed. Select recorded inputs without optional conditions or preconditions.",
                    )
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
                    val shaped = steps.mapIndexed { rowIndex, step ->
                        if (options.asReplay || snapshot.steps[rowIndex].id in options.replayRowIds) asReplayStepOrSame(step) else step
                    }
                    val stamped = options.recordingRef?.let { ref -> shaped.map { it.copy(recordingRef = ref) } } ?: shaped
                    val replayWarnings = if (options.asReplay || options.replayRowIds.isNotEmpty()) replaySummary(shaped, options.asReplay) else emptyList()
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
                            val warnings = replayWarnings + runCatching { options.afterApplied?.invoke(snapshot, inserted.value).orEmpty() }
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
