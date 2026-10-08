package com.indagium.testing.authoring

import com.indagium.debug.parseNewStep
import com.indagium.debug.toPlainMap
import com.indagium.model.AiUsageStats
import com.indagium.model.sumAiUsage
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.validateStep
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.util.UUID

data class TestStepDraft(
    val id: String,
    val suiteId: String,
    val caseId: String,
    val profileId: String,
    val steps: List<TestStep>,
    val creationUsage: AiUsageStats? = null,
    val latestUsage: AiUsageStats? = null,
)

/** Shared, preview-first step authoring logic for UI and MCP. Pending drafts are short-lived and one-use. */
internal class TestStepDraftService(
    private val library: () -> TestLibrary,
    private val preflight: (suiteId: String, caseId: String) -> StoreResult<Unit>,
    private val generate: suspend (profileId: String, prompt: String, model: String?, effort: String?) -> String,
    private val insert: (caseId: String, steps: List<TestStep>, index: Int?) -> StoreResult<List<TestStep>>,
    private val assetExists: (suiteId: String, assetPath: String) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
    private val generateWithUsage: suspend (
        profileId: String,
        prompt: String,
        model: String?,
        effort: String?,
        onUsage: (AiUsageStats) -> Unit,
    ) -> String = { profileId, prompt, model, effort, _ -> generate(profileId, prompt, model, effort) },
    private val insertWithUsage: (caseId: String, steps: List<TestStep>, index: Int?, usage: AiUsageStats?) -> StoreResult<List<TestStep>> =
        { caseId, steps, index, _ -> insert(caseId, steps, index) },
) {
    private data class Pending(
        val draft: TestStepDraft,
        val expiresAt: Long,
        val authoringSessionId: String,
        val usageThroughAttempt: Int,
    )

    private data class UsageAttempt(val generation: Long, val index: Int)

    private data class AuthoringUsageSession(
        val attempts: MutableList<AiUsageStats?> = mutableListOf(),
        var generation: Long = 0,
        var active: Boolean = false,
        var applying: Boolean = false,
        var appliedThrough: Int = 0,
        var touchedAt: Long = 0,
    )

    private data class UsageReservation(
        val sessionId: String,
        val generation: Long,
        val throughAttempt: Int,
        val usage: AiUsageStats?,
    )

    private val pending = LinkedHashMap<String, Pending>()
    private val authoringUsage = LinkedHashMap<String, AuthoringUsageSession>()
    private var nextUsageGeneration = 0L

    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Converts provider failures to typed results after preserving caller cancellation.
    suspend fun create(
        suiteId: String,
        caseId: String,
        profileId: String,
        instruction: String,
        model: String? = null,
        effort: String? = null,
        authoringSessionId: String? = null,
        onUsage: (latest: AiUsageStats, total: AiUsageStats) -> Unit = { _, _ -> },
    ): StoreResult<TestStepDraft> {
        if (instruction.isBlank()) return StoreResult.Invalid("Describe the steps to draft.")
        if (instruction.length > MAX_INSTRUCTION_CHARS) return StoreResult.Invalid("Draft instructions are limited to $MAX_INSTRUCTION_CHARS characters.")
        val eligibility = preflight(suiteId, caseId)
        if (eligibility !is StoreResult.Ok) return eligibility.propagate()
        val snapshot = library()
        val suite = snapshot.suite(suiteId) ?: return StoreResult.NotFound("suite", suiteId)
        val case = suite.cases.firstOrNull { it.id == caseId } ?: return StoreResult.NotFound("case", caseId)
        return try {
            val usageGroupId = authoringSessionId ?: UUID.randomUUID().toString()
            val attempt = synchronized(pending) { beginAuthoringAttempt(usageGroupId) }
                ?: return StoreResult.Invalid("Step drafting is already in progress for this authoring session.")
            var latestUsage: AiUsageStats? = null
            val response = try {
                withTimeout(DRAFT_GENERATION_TIMEOUT_MS) {
                    generateWithUsage(profileId, promptFor(suite, case, instruction), model, effort) { usage ->
                        latestUsage = usage
                        val total = synchronized(pending) { updateAuthoringUsage(usageGroupId, attempt, usage) }
                        if (total != null) onUsage(usage, total)
                    }
                }
            } finally {
                synchronized(pending) { finishAuthoringAttempt(usageGroupId, attempt) }
            }
            val usageSession = synchronized(pending) { authoringUsage[usageGroupId] }
                ?: return StoreResult.Invalid("The step-drafting session was closed before generation finished.")
            val creationUsage = totalUsage(usageSession) ?: latestUsage
            val proposed = parseSteps(response)
            validateProposed(library(), suite.id, proposed)?.let { return StoreResult.Invalid(it) }
            val draft = TestStepDraft(UUID.randomUUID().toString(), suite.id, case.id, profileId, proposed, creationUsage, latestUsage)
            synchronized(pending) {
                pruneExpired()
                while (pending.size >= MAX_PENDING_DRAFTS) pending.remove(pending.keys.first())
                pending[draft.id] = Pending(draft, now() + DRAFT_TTL_MS, usageGroupId, attempt.index + 1)
                pruneAuthoringUsage()
            }
            StoreResult.Ok(draft)
        } catch (timeout: TimeoutCancellationException) {
            val detail = timeout.message?.let { " ($it)" }.orEmpty()
            return StoreResult.Invalid("Step drafting timed out$detail. Try a shorter request or another provider profile.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StoreResult.Invalid(failure.message ?: "Could not generate a step draft.")
        }
    }

    /** Applies the caller's edited preview, revalidating it against the current library immediately before writing. */
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Validation needs precise exits; persistence is an injected boundary.
    fun apply(draftId: String, editedSteps: List<TestStep>, index: Int? = null): StoreResult<List<TestStep>> {
        val held = synchronized(pending) {
            pruneExpired()
            pending.remove(draftId)
        } ?: return StoreResult.Invalid("This step draft expired or was already applied. Generate a new preview.")
        val draft = held.draft
        val eligibility = preflight(draft.suiteId, draft.caseId)
        if (eligibility !is StoreResult.Ok) {
            restore(draftId, held)
            return eligibility.propagate()
        }
        if (editedSteps.isEmpty() || editedSteps.size > MAX_DRAFT_STEPS) {
            restore(draftId, held)
            return StoreResult.Invalid("A draft must contain between 1 and $MAX_DRAFT_STEPS steps.")
        }
        val snapshot = library()
        if (snapshot.suite(draft.suiteId)?.cases?.any { it.id == draft.caseId } != true) {
            restore(draftId, held)
            return StoreResult.Invalid("The target case changed or was deleted. Generate a new preview.")
        }
        val reason = validateProposed(snapshot, draft.suiteId, editedSteps)
        if (reason != null) {
            restore(draftId, held)
            return StoreResult.Invalid(reason)
        }
        val reservation = synchronized(pending) { reserveUsage(held.authoringSessionId, held.usageThroughAttempt, draft.creationUsage) }
        if (reservation == null) {
            restore(draftId, held)
            return StoreResult.Invalid("Another preview from this authoring session is being applied.")
        }
        val result = try {
            insertWithUsage(draft.caseId, editedSteps, index, reservation.usage)
        } catch (failure: Exception) {
            StoreResult.Invalid(failure.message ?: "Could not apply the step draft.")
        }
        if (result !is StoreResult.Ok) {
            restore(draftId, held)
        }
        synchronized(pending) { releaseUsageReservation(reservation, result is StoreResult.Ok) }
        return result
    }

    fun discard(draftId: String) {
        synchronized(pending) {
            pruneExpired()
            pending.remove(draftId)
        }
    }

    fun discardAuthoringSession(authoringSessionId: String) {
        synchronized(pending) {
            authoringUsage.remove(authoringSessionId)
            pending.entries.removeIf { it.value.authoringSessionId == authoringSessionId }
        }
    }

    @Suppress("ReturnCount") // Return the first specific validation issue without mutating the caller's preview.
    private fun validateProposed(snapshot: TestLibrary, suiteId: String, steps: List<TestStep>): String? {
        if (steps.isEmpty() || steps.size > MAX_DRAFT_STEPS) return "A draft must contain between 1 and $MAX_DRAFT_STEPS steps."
        for ((index, step) in steps.withIndex()) {
            if (step.action.isBlank()) return "Step ${index + 1} needs an action."
            validateStep(step)?.let { return "Step ${index + 1}: $it" }
            val dangling = step.checks.filterIsInstance<StepCheck.ScreenJudge>().firstOrNull { check ->
                check.exampleRef != null && step.examples.none { it.id == check.exampleRef }
            }
            if (dangling != null) return "Step ${index + 1}: judge check references an example that is not attached to that step."
            val missingScript = step.checks.filterIsInstance<StepCheck.ScriptResult>().firstOrNull { snapshot.script(it.scriptId) == null }
            if (missingScript != null) return "Step ${index + 1}: script-result check references missing script '${missingScript.scriptId}'."
            val missingAsset = step.examples.filterIsInstance<StepExample.GoldenScreenshot>().firstOrNull { !assetExists(suiteId, it.assetPath) }
            if (missingAsset != null) return "Step ${index + 1}: golden image '${missingAsset.assetPath}' is missing from the target suite."
        }
        return null
    }

    @Suppress("ThrowsCount") // Each rejected provider JSON shape needs a precise path-specific message.
    private fun parseSteps(response: String): List<TestStep> {
        val clean = response.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val element = runCatching { Json.parseToJsonElement(clean) }.getOrElse {
            throw IllegalArgumentException("The provider did not return valid JSON steps: ${it.message ?: "parse error"}")
        }
        val rows = when (element) {
            is JsonArray -> element
            is JsonObject -> element["steps"] as? JsonArray
                ?: throw IllegalArgumentException("The provider response must include a 'steps' array.")
            else -> throw IllegalArgumentException("The provider response must be a JSON object or array of steps.")
        }
        require(rows.isNotEmpty() && rows.size <= MAX_DRAFT_STEPS) {
            "The provider must propose between 1 and $MAX_DRAFT_STEPS steps."
        }
        return rows.mapIndexed { index, row ->
            val obj = row as? JsonObject ?: throw IllegalArgumentException("steps[$index] must be an object.")
            runCatching { parseNewStep(obj.toPlainMap(), "steps[$index]") }.getOrElse {
                throw IllegalArgumentException("steps[$index]: ${it.message ?: "could not be validated"}")
            }
        }
    }

    private fun promptFor(suite: TestSuite, case: com.indagium.testing.model.TestCase, instruction: String): String = buildString {
        appendLine("Draft Android QA test steps as JSON only. Do not call tools, control devices, or change any library.")
        appendLine("Return exactly {\"steps\":[{\"action\":\"...\",\"expected\":\"...\"}]}; no markdown or prose.")
        appendLine("Use only these fields: action, expected, timeoutMs, retries, maxToolCalls, onFailure, checks, examples.")
        appendLine("Do not create golden image references or script checks. Prefer concise, observable actions and expected results.")
        appendLine("Suite: ${suite.name.take(MAX_CONTEXT_NAME_CHARS)}")
        appendLine("Suite instructions: ${suite.instructions.take(MAX_CONTEXT_INSTRUCTIONS_CHARS)}")
        appendLine("Target package: ${suite.targetPackage.take(MAX_CONTEXT_NAME_CHARS)}")
        appendLine("Case: ${case.name.take(MAX_CONTEXT_NAME_CHARS)}")
        appendLine("Goal: ${case.description.take(MAX_CONTEXT_DESCRIPTION_CHARS)}")
        appendLine("Preconditions: ${case.preconditions.take(MAX_CONTEXT_DESCRIPTION_CHARS)}")
        appendLine("Existing steps: ${case.steps.take(MAX_CONTEXT_STEPS).joinToString(" | ") { it.action.take(MAX_CONTEXT_ACTION_CHARS) }}")
        appendLine("Requested addition: ${instruction.take(MAX_INSTRUCTION_CHARS)}")
    }

    private fun restore(id: String, held: Pending) {
        synchronized(pending) {
            pruneExpired()
            if (held.expiresAt > now() && id !in pending && pending.size < MAX_PENDING_DRAFTS) pending[id] = held
        }
    }

    private fun pruneExpired() {
        val time = now()
        pending.entries.removeIf { it.value.expiresAt <= time }
        pruneAuthoringUsage()
    }

    /** Starts one serialized attempt per authoring session; generation tokens fence callbacks after disposal. */
    private fun beginAuthoringAttempt(sessionId: String): UsageAttempt? {
        pruneExpired()
        val session = authoringUsage.getOrPut(sessionId) { AuthoringUsageSession() }
        if (session.active || session.applying) return null
        nextUsageGeneration = if (nextUsageGeneration == Long.MAX_VALUE) 1L else nextUsageGeneration + 1L
        session.generation = nextUsageGeneration
        session.active = true
        session.touchedAt = now()
        val attempt = UsageAttempt(session.generation, session.attempts.size)
        session.attempts += null
        pruneAuthoringUsage()
        return attempt
    }

    private fun updateAuthoringUsage(sessionId: String, attempt: UsageAttempt, usage: AiUsageStats): AiUsageStats? {
        val session = authoringUsage[sessionId]?.takeIf { it.active && it.generation == attempt.generation } ?: return null
        if (attempt.index !in session.attempts.indices) return null
        session.attempts[attempt.index] = usage
        session.touchedAt = now()
        return totalUsage(session)
    }

    private fun finishAuthoringAttempt(sessionId: String, attempt: UsageAttempt) {
        val session = authoringUsage[sessionId]?.takeIf { it.generation == attempt.generation } ?: return
        if (session.attempts.getOrNull(attempt.index) == null) session.attempts[attempt.index] = AiUsageStats(partial = true)
        session.active = false
        session.touchedAt = now()
        pruneAuthoringUsage()
    }

    /** Applies are serialized per session so two pending previews cannot attach overlapping usage to the case. */
    private fun reserveUsage(sessionId: String, throughAttempt: Int, fallback: AiUsageStats?): UsageReservation? {
        val session = authoringUsage[sessionId] ?: return UsageReservation(sessionId, -1L, throughAttempt, fallback)
        if (session.applying) return null
        val end = throughAttempt.coerceAtMost(session.attempts.size)
        val from = session.appliedThrough.coerceAtMost(end)
        session.applying = true
        session.touchedAt = now()
        return UsageReservation(sessionId, session.generation, end, sumAiUsage(session.attempts.subList(from, end)))
    }

    private fun releaseUsageReservation(reservation: UsageReservation, applied: Boolean) {
        val session = authoringUsage[reservation.sessionId]?.takeIf { it.generation == reservation.generation } ?: return
        session.applying = false
        if (applied) session.appliedThrough = maxOf(session.appliedThrough, reservation.throughAttempt)
        session.touchedAt = now()
        pruneAuthoringUsage()
    }

    private fun totalUsage(session: AuthoringUsageSession): AiUsageStats? = sumAiUsage(session.attempts)

    private fun pruneAuthoringUsage() {
        val time = now()
        val pendingSessionIds = pending.values.mapTo(HashSet()) { it.authoringSessionId }
        authoringUsage.entries.removeIf { (id, session) ->
            !session.active && id !in pendingSessionIds && time - session.touchedAt >= DRAFT_TTL_MS
        }
        while (authoringUsage.size > MAX_AUTHORING_SESSIONS) {
            val removable = authoringUsage.entries.firstOrNull { (id, session) -> !session.active && id !in pendingSessionIds }
                ?: break
            authoringUsage.remove(removable.key)
        }
    }

    private companion object {
        const val MAX_INSTRUCTION_CHARS = 2_000
        const val MAX_DRAFT_STEPS = 20
        const val MAX_PENDING_DRAFTS = 32
        const val MAX_AUTHORING_SESSIONS = 64
        const val MAX_CONTEXT_NAME_CHARS = 200
        const val MAX_CONTEXT_INSTRUCTIONS_CHARS = 1_200
        const val MAX_CONTEXT_DESCRIPTION_CHARS = 1_000
        const val MAX_CONTEXT_STEPS = 20
        const val MAX_CONTEXT_ACTION_CHARS = 160
        const val DRAFT_TTL_MS = 30 * 60 * 1_000L
        const val DRAFT_GENERATION_TIMEOUT_MS = 90_000L
    }
}

/** A refusal ([StoreResult.Invalid], limit or not-found) carried over to a result of another type; an Ok has nothing to carry. */
internal fun <T> StoreResult<*>.propagate(): StoreResult<T> = when (this) {
    is StoreResult.Invalid -> StoreResult.Invalid(reason)
    is StoreResult.LimitReached -> StoreResult.LimitReached(decision)
    is StoreResult.NotFound -> StoreResult.NotFound(kind, id)
    is StoreResult.Ok<*> -> StoreResult.Invalid("The operation was refused.")
}
