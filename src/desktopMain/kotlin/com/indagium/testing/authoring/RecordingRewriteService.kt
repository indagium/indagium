package com.indagium.testing.authoring

import com.indagium.debug.IndagiumToolGateway
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/** Everything one agent run for a rewrite needs; [model] / [effort] replace the profile's own when given. */
internal class RewriteGeneration(
    val profileId: String,
    val model: String?,
    val effort: String?,
    val prompt: String,
    val systemPrompt: String,
    val gateway: IndagiumToolGateway,
    val toolCallLimit: Int,
    val maxTurns: Int,
)

/**
 * Rewrites a stopped recording into readable steps through an AI profile (UI and MCP share this path). It reads the recording
 * and the library, never changes the library, never touches a device, and either swaps the session's rows for the validated
 * answer or changes nothing. The caller ([com.indagium.ui.AppState]) keeps rewrite and apply of one session from overlapping;
 * the session refuses both at the moment of the swap as well.
 */
internal class RecordingRewriteService(
    private val library: () -> TestLibrary,
    private val preflight: (suiteId: String, caseId: String) -> StoreResult<Unit>,
    private val generate: suspend (RewriteGeneration) -> String,
    private val timeoutMs: Long = REWRITE_TIMEOUT_MS,
) {
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Converts provider failures to typed results after preserving caller cancellation.
    suspend fun rewrite(
        session: TestStepRecordingSession,
        suiteId: String,
        caseId: String,
        profileId: String,
        model: String?,
        effort: String?,
        note: String,
    ): StoreResult<RecordingRewrite> {
        if (note.length > MAX_REWRITE_NOTE_CHARS) return StoreResult.Invalid("The note is limited to $MAX_REWRITE_NOTE_CHARS characters.")
        session.rewriteBlockedReason()?.let { return StoreResult.Invalid(it) }
        val eligibility = preflight(suiteId, caseId)
        if (eligibility !is StoreResult.Ok) return eligibility.propagate()
        val suite = library().suite(suiteId) ?: return StoreResult.NotFound("suite", suiteId)
        val case = suite.cases.firstOrNull { it.id == caseId } ?: return StoreResult.NotFound("case", caseId)
        val rows = session.rewriteRows()
        val inputs = rewriteInputsOf(rows, session::screenState)
        val toolLimit = rewriteToolCallLimit(inputs.size)
        val request = RewriteGeneration(
            profileId = profileId,
            model = model,
            effort = effort,
            prompt = rewritePrompt(suite, case, note, inputs),
            systemPrompt = REWRITE_SYSTEM_PROMPT,
            gateway = RecordingRewriteTools(inputs).gateway,
            toolCallLimit = toolLimit,
            maxTurns = toolLimit + REWRITE_TURN_HEADROOM,
        )
        return try {
            val response = withTimeout(timeoutMs) { generate(request) }
            val proposal = parseRewriteResponse(response, inputs.size)
            val rewritten = buildRewrittenRows(proposal, inputs)
            if (!session.applyRewrite(rows.map { it.id }, rewritten, proposal.notes)) {
                return StoreResult.Invalid("The recording changed while it was being rewritten; nothing was applied. Rewrite it again.")
            }
            StoreResult.Ok(RecordingRewrite(session.id, profileId, session.snapshot.value.steps, proposal.notes))
        } catch (timeout: TimeoutCancellationException) {
            val detail = timeout.message?.let { " ($it)" }.orEmpty()
            StoreResult.Invalid("The rewrite timed out$detail. Try another provider profile or a shorter recording.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StoreResult.Invalid(failure.message ?: "Could not rewrite the recording.")
        }
    }

    private companion object {
        const val REWRITE_TIMEOUT_MS = 180_000L
        const val REWRITE_TURN_HEADROOM = 6
    }
}
