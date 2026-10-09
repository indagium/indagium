package com.indagium.testing.authoring

import com.indagium.ai.LlmImage
import com.indagium.debug.AppLogger
import com.indagium.debug.IndagiumToolGateway
import com.indagium.model.AiUsageStats
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Base64

private const val REWRITE_TIMING_LOG_COMPONENT = "test-agent-timing"
private const val NANOS_PER_MILLI = 1_000_000L

/**
 * The images that go with a rewrite's first message ([RewriteGeneration.prompt] then introduces them), and what to run instead when
 * the agent cannot take them: [fallbackPrompt] names the tools that fetch what they show, with the full tool budget.
 */
internal class RewriteAttachment(
    val images: List<LlmImage>,
    val fallbackPrompt: String,
    val fallbackToolCallLimit: Int,
    val fallbackMaxTurns: Int,
)

/** Everything one agent run for a rewrite needs; [model] / [effort] replace the profile's own when given. */
internal data class RewriteGeneration(
    val profileId: String,
    val model: String?,
    val effort: String?,
    val prompt: String,
    val systemPrompt: String,
    val gateway: IndagiumToolGateway,
    val toolCallLimit: Int,
    val maxTurns: Int,
    val onUsage: (AiUsageStats) -> Unit = {},
    /** The evidence images that go with [prompt]; null when the prompt stands alone (no evidence, or an agent that takes no images). */
    val attachment: RewriteAttachment? = null,
)

/** What the first message of one rewrite is made of. [video] is the recorded video when it can show frames. */
private class RewriteContext(
    val suite: TestSuite,
    val case: TestCase,
    val note: String,
    val inputs: List<RewriteInput>,
    val summary: RecordedVideoTimelineSummary?,
    val scripts: List<TestScript>,
    val video: RecordedVideoTimeline?,
) {
    fun prompt(evidence: RewriteEvidence, attachImages: Boolean) =
        rewritePrompt(suite, case, note, inputs, summary, scripts, evidence, attachImages)
}

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
    /** Whether the agent of a profile takes images with its first message (see [com.indagium.ai.acceptsImagesUpFront]). */
    private val acceptsImagesUpFront: (profileId: String) -> Boolean = { true },
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
        session.stopAndDrain()
        session.rewriteBlockedReason()?.let { return StoreResult.Invalid(it) }
        if (session.snapshot.value.videoTimeline?.finished == false) {
            return StoreResult.Invalid("The recorded video timeline is still finishing; stop and drain the recording before rewriting it.")
        }
        val eligibility = preflight(suiteId, caseId)
        if (eligibility !is StoreResult.Ok) return eligibility.propagate()
        val suite = library().suite(suiteId) ?: return StoreResult.NotFound("suite", suiteId)
        val case = suite.cases.firstOrNull { it.id == caseId } ?: return StoreResult.NotFound("case", caseId)
        val rows = session.rewriteRows()
        val inputs = rewriteInputsOf(rows, session::screenState)
        val rewriteTools = RecordingRewriteTools(inputs, session.videoTimeline)
        val timeline = session.videoTimeline
        val videoSummary = timeline?.summary()
        val fullLimit = rewriteToolCallLimit(inputs.size, videoSummary?.available == true)
        val plain = RewriteGeneration(
            profileId = profileId,
            model = model,
            effort = effort,
            prompt = rewritePrompt(suite, case, note, inputs, videoSummary, library().scripts),
            systemPrompt = REWRITE_SYSTEM_PROMPT,
            gateway = rewriteTools.gateway,
            toolCallLimit = fullLimit,
            maxTurns = fullLimit + REWRITE_TURN_HEADROOM,
            onUsage = session::updateRewriteUsage,
        )
        val context = RewriteContext(suite, case, note, inputs, videoSummary, library().scripts, timeline?.takeIf { videoSummary?.available == true })
        if (!session.beginRewriteUsageAttempt()) return StoreResult.Invalid("The recording changed while the rewrite was starting.")
        var completed = false
        return try {
            val response = withTimeout(timeoutMs) {
                generate(withEvidence(plain, context))
            }
            val proposal = parseRewriteResponse(response, rewriteExpectations(inputs, library().scripts))
            val rewritten = buildRewrittenRows(proposal, inputs, rewriteTools.videoInspectedInputs())
            if (!session.applyRewrite(rows.map { it.id }, rewritten, proposal.notes)) {
                return StoreResult.Invalid("The recording changed while it was being rewritten; nothing was applied. Rewrite it again.")
            }
            completed = true
            StoreResult.Ok(RecordingRewrite(session.id, profileId, session.snapshot.value.steps, proposal.notes, session.snapshot.value.rewriteUsage))
        } catch (timeout: TimeoutCancellationException) {
            val detail = timeout.message?.let { " ($it)" }.orEmpty()
            StoreResult.Invalid("The rewrite timed out$detail. Try another provider profile or a shorter recording.")
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            StoreResult.Invalid(failure.message ?: "Could not rewrite the recording.")
        } finally {
            session.finishRewriteUsageAttempt(completed)
        }
    }

    /**
     * [plain] and, when the agent takes images, the first message carrying the screenshots and the screen text with the small tool
     * budget of close-ups. The evidence is best effort: whatever fails while it is put together leaves the plain request.
     */
    @Suppress("TooGenericExceptionCaught") // Evidence failures must never stop a rewrite.
    private suspend fun withEvidence(plain: RewriteGeneration, context: RewriteContext): RewriteGeneration {
        val started = System.nanoTime()
        val withImages = acceptsImagesUpFront(plain.profileId)
        val evidence = try {
            withContext(Dispatchers.IO) {
                buildRewriteEvidence(context.inputs, context.video?.takeIf { withImages }?.let { videoSheets(it, context.inputs) }, withImages)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            AppLogger.warn(REWRITE_TIMING_LOG_COMPONENT, "rewrite_brief: the evidence could not be put together", failure)
            null
        }
        val result = if (evidence == null) plain else plain.briefedWith(evidence, context)
        AppLogger.info(
            REWRITE_TIMING_LOG_COMPONENT,
            "rewrite_brief: evidenceUpFront=${result.attachment != null} kind=${evidence?.kind ?: "none"} " +
                "images=${result.attachment?.images?.size ?: 0} notAttached=${evidence?.notAttached?.size ?: 0} " +
                "promptChars=${result.prompt.length} briefMs=${(System.nanoTime() - started) / NANOS_PER_MILLI} " +
                "toolBudget=${result.toolCallLimit} inputs=${context.inputs.size}",
        )
        return result
    }

    private fun RewriteGeneration.briefedWith(evidence: RewriteEvidence, context: RewriteContext): RewriteGeneration {
        val fetchPrompt = context.prompt(evidence, attachImages = false)
        if (!evidence.upFront) return copy(prompt = fetchPrompt)
        // Inputs left without an image (the limits were reached) need the tools: the budget is the full one then.
        val limit = rewriteToolCallLimit(context.inputs.size, context.video != null, evidenceUpFront = evidence.notAttached.isEmpty())
        return copy(
            prompt = context.prompt(evidence, attachImages = true),
            toolCallLimit = limit,
            maxTurns = limit + REWRITE_TURN_HEADROOM,
            attachment = RewriteAttachment(evidence.llmImages(), fetchPrompt, toolCallLimit, maxTurns),
        )
    }

    /** The video as a source of contact sheets: a storyboard of the wall-clock window, clamped to the start of the video. */
    private fun videoSheets(timeline: RecordedVideoTimeline, inputs: List<RewriteInput>) = RewriteVideoSheets { startWallMs, endWallMs ->
        val start = timeline.sessionOffsetMs(startWallMs).coerceAtLeast(0L)
        val end = timeline.sessionOffsetMs(endWallMs)
        timeline.storyboardForRange(start, end, inputs)?.let { sheet ->
            runCatching { RewriteVideoSheet(Base64.getDecoder().decode(sheet.imageBase64), sheet.mimeType) }.getOrNull()
        }
    }

    private companion object {
        const val REWRITE_TIMEOUT_MS = 180_000L
        const val REWRITE_TURN_HEADROOM = 6
    }
}

/** What the answer for [inputs] is checked against: the person's own steps, the external events with their proposed checks, the library's scripts. */
internal fun rewriteExpectations(inputs: List<RewriteInput>, scripts: List<TestScript>) = RewriteExpectations(
    inputCount = inputs.size,
    fixedInputs = inputs.filter { it.fixed }.associate { it.number to it.row },
    externalInputs = inputs.filter { !it.fixed && it.row.isExternalEvent }.associate { it.number to it.row.checks },
    scripts = scripts,
)
