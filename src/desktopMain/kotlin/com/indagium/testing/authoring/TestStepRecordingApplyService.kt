package com.indagium.testing.authoring

import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Shared UI/MCP apply path for a stopped recording. Asset imports are staged and removed if insertion fails. */
internal class TestStepRecordingApplyService(
    private val preflight: (suiteId: String, caseId: String) -> StoreResult<Unit>,
    private val importImage: (suiteId: String, source: File) -> StoreResult<String>,
    private val resolveImage: (suiteId: String, assetPath: String) -> File?,
    private val insertSteps: (caseId: String, steps: List<TestStep>, index: Int?) -> StoreResult<List<TestStep>>,
) {
    private val applyingSessionIds = ConcurrentHashMap.newKeySet<String>()

    @Suppress("CyclomaticComplexMethod", "ThrowsCount", "TooGenericExceptionCaught")
    suspend fun apply(
        session: TestStepRecordingSession,
        suiteId: String,
        caseId: String,
        index: Int? = null,
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
                if (snapshot.steps.isEmpty() || snapshot.steps.any { it.action.isBlank() || it.expected.isBlank() }) {
                    return@withContext StoreResult.Invalid("Add an action and expected result to every recorded step before applying.")
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
                    when (val inserted = insertSteps(caseId, steps, index)) {
                        is StoreResult.Ok -> inserted
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
