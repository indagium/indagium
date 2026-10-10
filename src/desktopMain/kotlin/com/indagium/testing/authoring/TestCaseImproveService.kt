package com.indagium.testing.authoring

import com.indagium.debug.AppLogger
import com.indagium.model.AiUsageStats
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestStep
import com.indagium.testing.store.StepChange
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

private const val IMPROVE_TIMING_LOG_COMPONENT = "test-agent-timing"
private const val NANOS_PER_MILLI = 1_000_000L

/** Everything one agent run for an improvement needs; [model] / [effort] replace the profile's own when given. There are no tools. */
internal data class ImproveGeneration(
    val profileId: String,
    val model: String?,
    val effort: String?,
    val prompt: String,
    val systemPrompt: String,
    val onUsage: (AiUsageStats) -> Unit = {},
    /** The evidence images that go with [prompt]; null when the prompt stands alone (no images, or an agent that takes none). */
    val attachment: RewriteAttachment? = null,
)

/**
 * Shared, preview-first improvement of the saved steps of a case for the UI and MCP. It reads the case, its latest run and its golden
 * screenshots, asks an AI profile for one improved entry per existing step and keeps the validated answer as a short-lived, one-use
 * proposal. Applying writes only the accepted, changed steps through the library store in one write, and refuses a proposal whose case
 * changed since it was made. Generating changes nothing and drives no device.
 */
internal class TestCaseImproveService(
    private val library: () -> TestLibrary,
    private val preflight: (suiteId: String, caseId: String) -> StoreResult<Unit>,
    private val generate: suspend (ImproveGeneration) -> String,
    private val runs: ImproveRunSource,
    private val goldenFile: (suiteId: String, assetPath: String) -> File?,
    private val applyChanges: (caseId: String, changes: List<StepChange>, usage: AiUsageStats?) -> StoreResult<List<TestStep>>,
    /** Whether the agent of a profile takes images with its first message (see [com.indagium.ai.acceptsImagesUpFront]). */
    private val acceptsImagesUpFront: (profileId: String) -> Boolean = { true },
    private val now: () -> Long = System::currentTimeMillis,
    private val timeoutMs: Long = IMPROVE_TIMEOUT_MS,
) {
    private class Pending(val proposal: TestCaseImproveProposal, val baseline: List<TestStep>, val expiresAt: Long)

    private val pending = LinkedHashMap<String, Pending>()
    private val generating = HashSet<String>()

    /** What would be sent for [caseId] (and [runId], or its latest run): the label the dialog shows before anything is generated. */
    suspend fun describeEvidence(caseId: String, runId: String? = null): StoreResult<ImproveEvidenceSummary> {
        val located = library().findCase(caseId) ?: return StoreResult.NotFound("case", caseId)
        val occurrence = when (val picked = pickImproveRun(runs, located.suite.id, caseId, runId)) {
            is StoreResult.Ok -> picked.value
            else -> return picked.propagate()
        }
        val plan = withContext(Dispatchers.IO) { planFor(located.suite.id, located.case, occurrence) }
        return StoreResult.Ok(plan.summary)
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught", "LongMethod") // Typed refusals first; provider failures become typed results.
    suspend fun create(
        caseId: String,
        profileId: String,
        note: String = "",
        model: String? = null,
        effort: String? = null,
        runId: String? = null,
        onUsage: (AiUsageStats) -> Unit = {},
    ): StoreResult<TestCaseImproveProposal> {
        if (note.length > MAX_IMPROVE_NOTE_CHARS) return StoreResult.Invalid("The note is limited to $MAX_IMPROVE_NOTE_CHARS characters.")
        val located = library().findCase(caseId) ?: return StoreResult.NotFound("case", caseId)
        val suite = located.suite
        val case = located.case
        sizeProblem(case.steps)?.let { return StoreResult.Invalid(it) }
        val eligibility = preflight(suite.id, caseId)
        if (eligibility !is StoreResult.Ok) return eligibility.propagate()
        if (!synchronized(pending) { generating.add(caseId) }) {
            return StoreResult.Invalid("An improvement for this case is already being generated.")
        }
        var latestUsage: AiUsageStats? = null
        return try {
            val occurrence = when (val picked = pickImproveRun(runs, suite.id, caseId, runId)) {
                is StoreResult.Ok -> picked.value
                else -> return picked.propagate()
            }
            val started = System.nanoTime()
            val withImages = acceptsImagesUpFront(profileId)
            val (plan, images) = withContext(Dispatchers.IO) {
                val built = planFor(suite.id, case, occurrence)
                built to if (withImages) loadImproveImages(built) else ImproveImages.NONE
            }
            val attached = images.images.isNotEmpty()
            val prompt = improvePrompt(suite, case, note, plan, images, attachImages = attached)
            val fallback = if (attached) improvePrompt(suite, case, note, plan, ImproveImages.NONE, attachImages = false) else prompt
            val attachment = if (attached) RewriteAttachment(images.llmImages(), fallback, NO_TOOL_CALLS, NO_TOOL_TURNS) else null
            val evidence = sentSummary(plan.summary, images, withImages)
            logBrief(attachment, evidence, prompt, images, case.steps.size, started)
            val request = ImproveGeneration(
                profileId, model, effort, prompt, IMPROVE_SYSTEM_PROMPT, { usage -> latestUsage = usage; onUsage(usage) }, attachment,
            )
            val response = withTimeout(timeoutMs) { generate(request) }
            val proposed = parseImproveResponse(response, case.steps, library().scripts)
            if (library().findCase(caseId)?.case?.steps != case.steps) {
                return StoreResult.Invalid("The case's steps changed while the improvement was being generated. Generate it again.")
            }
            val proposal = TestCaseImproveProposal(
                UUID.randomUUID().toString(), suite.id, caseId, profileId, occurrence?.run?.id, proposed, evidence, latestUsage,
            )
            synchronized(pending) {
                pruneExpired()
                while (pending.size >= MAX_PENDING_PROPOSALS) pending.remove(pending.keys.first())
                pending[proposal.id] = Pending(proposal, case.steps, now() + PROPOSAL_TTL_MS)
            }
            StoreResult.Ok(proposal)
        } catch (timeout: TimeoutCancellationException) {
            val detail = timeout.message?.let { " ($it)" }.orEmpty()
            StoreResult.Invalid("Improving the steps timed out$detail. Try another provider profile.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StoreResult.Invalid(failure.message ?: "Could not improve the steps.")
        } finally {
            synchronized(pending) { generating.remove(caseId) }
        }
    }

    /**
     * Applies the reviewed [edits] (one per step of the proposal, in its order) of [proposalId]: the steps in [accept] (default: every step
     * the edits change) that the edits really change are written in one library write. The proposal is one-use; a refusal that the person
     * can fix keeps it, a stale one drops it.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught", "CyclomaticComplexMethod") // One decision per refusal, each with its own message.
    fun apply(proposalId: String, edits: List<ImproveStepEdit>, accept: Set<String>? = null): StoreResult<List<TestStep>> {
        val held = synchronized(pending) {
            pruneExpired()
            pending.remove(proposalId)
        } ?: return StoreResult.Invalid("This improvement expired or was already applied. Generate a new one.")
        val proposal = held.proposal
        val eligibility = preflight(proposal.suiteId, proposal.caseId)
        if (eligibility !is StoreResult.Ok) return restore(proposalId, held, eligibility.propagate())
        val current = library().findCase(proposal.caseId)?.case
            ?: return StoreResult.Invalid("The case was deleted since this improvement was generated. Generate a new one.")
        if (current.steps != held.baseline) {
            return StoreResult.Invalid("The case's steps changed since this improvement was generated, so it was discarded. Generate a new one.")
        }
        if (edits.map { it.stepId } != proposal.steps.map { it.stepId }) {
            return restore(proposalId, held, StoreResult.Invalid("Send one entry for each step of the proposal, in its order."))
        }
        val unknown = accept?.filterNot { id -> proposal.steps.any { it.stepId == id } }.orEmpty()
        if (unknown.isNotEmpty()) return restore(proposalId, held, StoreResult.Invalid("Unknown step id(s) to accept: ${unknown.joinToString(", ")}."))
        val changes = ArrayList<StepChange>()
        proposal.steps.forEachIndexed { index, step ->
            if (accept != null && step.stepId !in accept) return@forEachIndexed
            val edit = edits[index]
            val improved = try {
                improvedStepOf(step.previous, edit.action, edit.expected, edit.optional, edit.condition, step.checks, library().scripts)
            } catch (invalid: IllegalArgumentException) {
                return restore(proposalId, held, StoreResult.Invalid("Step ${step.number}: ${invalid.message}"))
            }
            if (improved != step.previous) changes += StepChange(step.previous, improved)
        }
        if (changes.isEmpty()) {
            return restore(proposalId, held, StoreResult.Invalid("None of the selected steps changes anything, so there is nothing to apply."))
        }
        val result = try {
            applyChanges(proposal.caseId, changes, proposal.usage)
        } catch (failure: Exception) {
            StoreResult.Invalid(failure.message ?: "Could not apply the improved steps.")
        }
        return if (result is StoreResult.Ok) result else restore(proposalId, held, result)
    }

    /** The pending proposal, without using it up; null when it expired, was applied or discarded. */
    fun peek(proposalId: String): TestCaseImproveProposal? = synchronized(pending) {
        pruneExpired()
        pending[proposalId]?.proposal
    }

    fun discard(proposalId: String) {
        synchronized(pending) {
            pruneExpired()
            pending.remove(proposalId)
        }
    }

    private fun <T> restore(id: String, held: Pending, result: StoreResult<T>): StoreResult<T> {
        synchronized(pending) {
            pruneExpired()
            if (held.expiresAt > now() && id !in pending && pending.size < MAX_PENDING_PROPOSALS) pending[id] = held
        }
        return result
    }

    private fun pruneExpired() {
        val time = now()
        pending.entries.removeIf { it.value.expiresAt <= time }
    }

    private fun planFor(suiteId: String, case: com.indagium.testing.model.TestCase, occurrence: ImproveRunOccurrence?): ImproveEvidencePlan =
        buildImproveEvidencePlan(case, occurrence, occurrence?.let { runs.runDir(it.run.id) }) { assetPath -> goldenFile(suiteId, assetPath) }

    private fun sizeProblem(steps: List<TestStep>): String? = when {
        steps.isEmpty() -> "This case has no steps to improve."
        steps.size > MAX_IMPROVE_STEPS -> "Only cases of up to $MAX_IMPROVE_STEPS steps can be improved at once; this one has ${steps.size}."
        else -> steps.withIndex().firstOrNull { (_, step) -> maxOf(step.action.length, step.expected.length) > MAX_FIXED_STEP_CHARS }
            ?.let { "Step ${it.index + 1} has more than $MAX_FIXED_STEP_CHARS characters of text; shorten it before improving the case." }
    }

    /** What was really sent: only the images that were attached count, and an agent that takes none was sent none. */
    private fun sentSummary(planned: ImproveEvidenceSummary, images: ImproveImages, withImages: Boolean): ImproveEvidenceSummary =
        if (!withImages) {
            planned.copy(runScreenshots = 0, goldenScreenshots = 0, leftOut = planned.screenshots + planned.leftOut)
        } else {
            planned.copy(
                runScreenshots = images.images.count { image -> image.refs.none { it.role == ImageRole.REFERENCE } },
                goldenScreenshots = images.images.count { image -> image.refs.any { it.role == ImageRole.REFERENCE } },
                leftOut = images.notAttached.size + images.unavailable.size,
            )
        }

    private fun logBrief(attachment: RewriteAttachment?, evidence: ImproveEvidenceSummary, prompt: String, images: ImproveImages, steps: Int, started: Long) {
        AppLogger.info(
            IMPROVE_TIMING_LOG_COMPONENT,
            "improve_brief: evidenceUpFront=${attachment != null} run=${evidence.runId ?: "none"} " +
                "steps=$steps stepResults=${evidence.stepsWithResults} problemSteps=${evidence.problemSteps} " +
                "images=${images.images.size} golden=${evidence.goldenScreenshots} notAttached=${images.notAttached.size} " +
                "unavailable=${images.unavailable.size} judgeSuggestions=${evidence.judgeSuggestions} " +
                "promptChars=${prompt.length} briefMs=${(System.nanoTime() - started) / NANOS_PER_MILLI}",
        )
    }

    private companion object {
        const val IMPROVE_TIMEOUT_MS = 180_000L
        const val PROPOSAL_TTL_MS = 30 * 60 * 1_000L
        const val MAX_PENDING_PROPOSALS = 16
        const val NO_TOOL_CALLS = 1
        const val NO_TOOL_TURNS = 2
    }
}
