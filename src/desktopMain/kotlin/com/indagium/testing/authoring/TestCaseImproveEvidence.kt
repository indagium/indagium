package com.indagium.testing.authoring

import com.indagium.ai.LlmImage
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepFix
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.run.boundedJudgeImage
import com.indagium.testing.run.deterministicOnly
import com.indagium.testing.run.readBoundedRunArtifact
import com.indagium.testing.run.resolveRunArtifact
import com.indagium.testing.store.StoreResult
import com.indagium.testing.store.readBoundedTestAsset
import java.io.File
import java.util.Base64

// The evidence an improvement of a saved case is handed with: what the latest run of the case recorded for each step (its status, the
// judge's verdict, reasoning and suggested fix, the screen the step started from and the one it ended on) and the golden screenshots.
// BLIND like the judge: the classes below have no field for what the agent claimed or observed, so nothing built from them can carry it.
// Everything that came from the device, the app, a script or a model is untrusted data (the brief fences it).

/** At most this many images go with the first message: problem steps' screens first, then golden screenshots, then the other end screens. */
internal const val IMPROVE_MAX_ATTACHED_IMAGES = 12

/** At most this many golden screenshots are among them. */
internal const val IMPROVE_MAX_GOLDEN_IMAGES = 4

/** The images of one first message add up to at most this many bytes (before base64); more are named as left out. */
internal const val IMPROVE_MAX_ATTACHED_IMAGE_BYTES = 8 * 1024 * 1024

/** Without a run named, the newest finished runs of the suite are looked through for the case, at most this many. */
internal const val IMPROVE_MAX_RUNS_SCANNED = 30

private const val MAX_RUN_SCREENSHOT_BYTES = 16 * 1024 * 1024
private val PROBLEM_STATUSES = setOf(StepStatus.FAIL, StepStatus.BLOCKED, StepStatus.TIMEOUT, StepStatus.ERROR)

/** The results of one case in one run, for one lane and one repeat of it. */
internal class ImproveRunOccurrence(val run: TestRun, val laneNumber: Int, val iteration: Int, val results: List<StepResult>)

/** A step the run could not pass or could not judge: what a person wants improved, and what the evidence shows first. */
internal fun StepResult.isProblemStep(): Boolean =
    !setup && (status in PROBLEM_STATUSES || judgeInconclusive || judge?.verdict == JudgeVerdict.INCONCLUSIVE || judge?.verdict == JudgeVerdict.FAIL)

/**
 * The results of [caseId] in [this] run: of every lane and repeat that ran it, the one with the most problem steps (so the evidence is
 * about what went wrong), the earlier lane and then the later repeat among equals. Null when the run has no step result of the case.
 */
internal fun TestRun.occurrenceOf(caseId: String): ImproveRunOccurrence? {
    var best: ImproveRunOccurrence? = null
    var bestProblems = -1
    lanes.forEachIndexed { laneIndex, lane ->
        for (result in lane.cases.filter { it.caseId == caseId && it.steps.any { step -> !step.setup } }) {
            val problems = result.steps.count { it.isProblemStep() }
            val current = best
            val better = current == null || problems > bestProblems ||
                (problems == bestProblems && current.laneNumber == laneIndex + 1 && result.iteration > current.iteration)
            if (better) {
                best = ImproveRunOccurrence(this, laneIndex + 1, result.iteration, result.steps)
                bestProblems = problems
            }
        }
    }
    return best
}

/** Where the runs of a suite come from: the listing is cheap (summaries), a run is loaded only when it is looked at. */
internal interface ImproveRunSource {
    suspend fun summaries(suiteId: String): List<RunSummary>

    suspend fun load(runId: String): TestRun?

    fun runDir(runId: String): File
}

/**
 * The run whose results of [caseId] are the evidence. With [runId] that run (it must be finished and have results of the case);
 * without it the newest finished run of the suite that has them, among the newest [IMPROVE_MAX_RUNS_SCANNED]. An Ok null means no run
 * has results of the case: the improvement then works from the saved steps alone.
 */
internal suspend fun pickImproveRun(source: ImproveRunSource, suiteId: String, caseId: String, runId: String?): StoreResult<ImproveRunOccurrence?> {
    if (runId != null) {
        val run = source.load(runId) ?: return StoreResult.NotFound("run", runId)
        if (!run.isFinished) return StoreResult.Invalid("Run '$runId' has not finished; wait for it or choose another run.")
        val occurrence = run.occurrenceOf(caseId) ?: return StoreResult.Invalid("Run '$runId' has no step results for this case.")
        return StoreResult.Ok(occurrence)
    }
    val finished = source.summaries(suiteId).filter { it.finishedAt != null }.sortedByDescending { it.createdAt }
    for (summary in finished.take(IMPROVE_MAX_RUNS_SCANNED)) {
        source.load(summary.id)?.takeIf { it.isFinished }?.occurrenceOf(caseId)?.let { return StoreResult.Ok(it) }
    }
    return StoreResult.Ok(null)
}

/**
 * What one run recorded about one step, as the improvement may see it. There is deliberately no agent claim and no agent observation.
 * [startShot] / [endShot] are run-folder-relative paths of screenshots that exist. [textAtRun] is set when the step was edited since.
 */
@Suppress("LongParameterList") // A plain record of the allowed fields of one result; nothing else may be added to it.
internal class ImproveStepEvidence(
    val stepId: String,
    val number: Int,
    val status: StepStatus,
    val attempts: Int,
    val verdict: JudgeVerdict?,
    val reasoning: String,
    val classification: JudgeClassification?,
    val suggestedFix: StepFix?,
    val judgeError: String?,
    val inconclusive: Boolean,
    val automaticChecks: List<CheckResult>,
    val personNote: String?,
    val textAtRun: String?,
    val startShot: String?,
    val endShot: String?,
    val problem: Boolean,
)

/** Which screen of which step an image shows: [REFERENCE] is a golden screenshot attached to the step. */
internal enum class ImageRole(val label: String) {
    START("start screen"),
    END("end screen"),
    REFERENCE("expected screenshot"),
    RECORDED_BEFORE("recorded before reference"),
    RECORDED_AFTER("recorded after reference"),
}

internal class ImproveImageRef(val stepId: String, val stepNumber: Int, val role: ImageRole, val caption: String = "") {
    fun describe(): String = "step $stepNumber ${role.label}" + caption.takeIf { it.isNotBlank() }?.let { " ($it)" }.orEmpty()
}

/** One image to attach: every [refs] entry shows it (the end of a step is the start of the next); [read] returns its file's bytes. */
internal class ImproveImageSlot(val golden: Boolean, val refs: MutableList<ImproveImageRef>, val read: () -> ByteArray?) {
    fun label(): String = refs.joinToString(" = ") { it.describe() }
}

/** What the person is told will be sent, and what the model is told it was given. */
internal data class ImproveEvidenceSummary(
    val runId: String? = null,
    val runCreatedAt: Long? = null,
    val runStatus: String? = null,
    val laneNumber: Int? = null,
    val iteration: Int? = null,
    val stepsWithResults: Int = 0,
    val problemSteps: Int = 0,
    /** Screenshots of the run that go with the request (after the limits). */
    val runScreenshots: Int = 0,
    /** Golden screenshots that go with the request (after the limits). */
    val goldenScreenshots: Int = 0,
    val judgeSuggestions: Int = 0,
    /** Images that exist but are left out by the limits. */
    val leftOut: Int = 0,
) {
    val screenshots: Int get() = runScreenshots + goldenScreenshots
}

internal class ImproveEvidencePlan(
    val occurrence: ImproveRunOccurrence?,
    val steps: Map<String, ImproveStepEvidence>,
    val slots: List<ImproveImageSlot>,
    val leftOut: List<String>,
    val summary: ImproveEvidenceSummary,
)

/**
 * The evidence for [case] from [occurrence] (null: no run) and its golden screenshots, with the images in the order they are attached and
 * the limits applied by count. [goldenFile] returns an example's image file; [runDir] is the folder of the run. No image is read here.
 */
@Suppress("LongMethod") // One pass over the steps builds the text evidence and the prioritised image candidates together.
internal fun buildImproveEvidencePlan(
    case: TestCase,
    occurrence: ImproveRunOccurrence?,
    runDir: File?,
    goldenFile: (assetPath: String) -> File?,
): ImproveEvidencePlan {
    val evidence = LinkedHashMap<String, ImproveStepEvidence>()
    if (occurrence != null && runDir != null) {
        val results = occurrence.results
        case.steps.forEachIndexed { index, step ->
            val position = results.indexOfFirst { it.stepId == step.id && !it.setup }
            val result = results.getOrNull(position) ?: return@forEachIndexed
            val previous = results.getOrNull(position - 1)
            evidence[step.id] = stepEvidence(index + 1, step.action, step.expected, step.id, result, previous?.screenshotPath, runDir)
        }
    }
    val candidates = imageCandidates(case, evidence, occurrence, runDir, goldenFile)
    val slots = LinkedHashMap<String, ImproveImageSlot>()
    val leftOut = ArrayList<String>()
    var goldenCount = 0
    for (candidate in candidates) {
        val existing = slots[candidate.key]
        when {
            existing != null -> existing.refs += candidate.ref
            candidate.golden && goldenCount >= IMPROVE_MAX_GOLDEN_IMAGES -> leftOut += candidate.ref.describe()
            slots.size >= IMPROVE_MAX_ATTACHED_IMAGES -> leftOut += candidate.ref.describe()
            else -> {
                if (candidate.golden) goldenCount++
                slots[candidate.key] = ImproveImageSlot(candidate.golden, mutableListOf(candidate.ref), candidate.read)
            }
        }
    }
    val summary = ImproveEvidenceSummary(
        runId = occurrence?.run?.id,
        runCreatedAt = occurrence?.run?.createdAt,
        runStatus = occurrence?.run?.status?.name,
        laneNumber = occurrence?.laneNumber,
        iteration = occurrence?.iteration,
        stepsWithResults = evidence.size,
        problemSteps = evidence.values.count { it.problem },
        runScreenshots = slots.values.count { !it.golden },
        goldenScreenshots = slots.values.count { it.golden },
        judgeSuggestions = evidence.values.count { it.suggestedFix != null },
        leftOut = leftOut.size,
    )
    return ImproveEvidencePlan(occurrence, evidence, slots.values.toList(), leftOut, summary)
}

@Suppress("LongParameterList") // Copies exactly the allowed fields of one result; the arguments are those fields' sources.
private fun stepEvidence(
    number: Int,
    action: String,
    expected: String,
    stepId: String,
    result: StepResult,
    startPath: String?,
    runDir: File,
): ImproveStepEvidence {
    fun keptShot(path: String?) = path?.takeIf { resolveRunArtifact(runDir, it) != null }
    val judge = result.judge
    return ImproveStepEvidence(
        stepId = stepId,
        number = number,
        status = result.status,
        attempts = result.attempts,
        verdict = judge?.verdict,
        reasoning = judge?.reasoning.orEmpty(),
        classification = judge?.classification ?: result.classification,
        suggestedFix = judge?.suggestedFix,
        judgeError = judge?.error,
        inconclusive = result.judgeInconclusive,
        automaticChecks = deterministicOnly(result.checks),
        personNote = result.agentError,
        textAtRun = if (result.action != action || result.expected != expected) "action: \"${result.action}\" | expected: \"${result.expected}\"" else null,
        startShot = keptShot(startPath),
        endShot = keptShot(result.screenshotPath),
        problem = result.isProblemStep(),
    )
}

private class ImageCandidate(val key: String, val golden: Boolean, val ref: ImproveImageRef, val read: () -> ByteArray?)

/** Every image that could go with the request, most useful first: the screens of problem steps, then golden screenshots, then other end screens. */
private fun imageCandidates(
    case: TestCase,
    evidence: Map<String, ImproveStepEvidence>,
    occurrence: ImproveRunOccurrence?,
    runDir: File?,
    goldenFile: (String) -> File?,
): List<ImageCandidate> {
    fun runShot(path: String, ref: ImproveImageRef) = ImageCandidate(
        "run:$path", false, ref,
    ) { runDir?.let { readBoundedRunArtifact(it, path, MAX_RUN_SCREENSHOT_BYTES) } }

    val problems = evidence.values.filter { it.problem }
    val result = ArrayList<ImageCandidate>()
    if (occurrence != null) {
        for (item in problems) {
            item.startShot?.let { result += runShot(it, ImproveImageRef(item.stepId, item.number, ImageRole.START)) }
            item.endShot?.let { result += runShot(it, ImproveImageRef(item.stepId, item.number, ImageRole.END)) }
        }
    }
    case.steps.forEachIndexed { index, step ->
        for (example in step.examples.filterIsInstance<StepExample.GoldenScreenshot>()) {
            val file = goldenFile(example.assetPath)?.takeIf { it.isFile } ?: continue
            val role = when (example.role) {
                com.indagium.testing.model.ScreenshotRole.EXPECTED -> ImageRole.REFERENCE
                com.indagium.testing.model.ScreenshotRole.RECORDED_BEFORE -> ImageRole.RECORDED_BEFORE
                com.indagium.testing.model.ScreenshotRole.RECORDED_AFTER -> ImageRole.RECORDED_AFTER
            }
            result += ImageCandidate("golden:${file.absolutePath}", true, ImproveImageRef(step.id, index + 1, role, example.caption)) {
                readBoundedTestAsset(file)
            }
        }
    }
    for (item in evidence.values.filterNot { it.problem }) {
        item.endShot?.let { result += runShot(it, ImproveImageRef(item.stepId, item.number, ImageRole.END)) }
    }
    return result
}

/** An image ready to attach: [refs] say which screens of which steps it shows. */
internal class ImproveImage(val refs: List<ImproveImageRef>, val bytes: ByteArray, val mimeType: String) {
    fun label(): String = refs.joinToString(" = ") { it.describe() }
}

/** The images loaded for a plan; [unavailable] are labels of images that could not be read or decoded, [notAttached] those past the byte limit. */
internal class ImproveImages(val images: List<ImproveImage>, val notAttached: List<String>, val unavailable: List<String>) {
    /** The images as the provider layer takes them. */
    fun llmImages(): List<LlmImage> = images.map { LlmImage(Base64.getEncoder().encodeToString(it.bytes), it.mimeType) }

    /** "Image 3" etc. for the first image that shows [stepId]'s [role]; empty when none does. */
    fun refsOf(stepId: String): List<Pair<Int, ImproveImageRef>> =
        images.flatMapIndexed { index, image -> image.refs.filter { it.stepId == stepId }.map { index + 1 to it } }

    companion object {
        val NONE = ImproveImages(emptyList(), emptyList(), emptyList())
    }
}

/** Reads, decodes, scales and compresses the planned images within [IMPROVE_MAX_ATTACHED_IMAGE_BYTES]. Blocking: call it on an IO thread. */
internal fun loadImproveImages(plan: ImproveEvidencePlan): ImproveImages {
    val images = ArrayList<ImproveImage>()
    val notAttached = ArrayList<String>(plan.leftOut)
    val unavailable = ArrayList<String>()
    var bytes = 0L
    for (slot in plan.slots) {
        val image = runCatching { slot.read()?.let(::boundedJudgeImage) }.getOrNull()
        when {
            image == null -> unavailable += slot.label()
            bytes + image.bytes.size > IMPROVE_MAX_ATTACHED_IMAGE_BYTES -> notAttached += slot.label()
            else -> {
                bytes += image.bytes.size
                images += ImproveImage(slot.refs.toList(), image.bytes, image.mimeType)
            }
        }
    }
    return ImproveImages(images, notAttached, unavailable)
}
