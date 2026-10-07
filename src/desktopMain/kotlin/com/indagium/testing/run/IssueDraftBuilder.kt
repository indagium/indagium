package com.indagium.testing.run

import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.IssueAttachment
import com.indagium.testing.model.IssueAttachmentKind
import com.indagium.testing.model.IssueDraft
import com.indagium.testing.model.IssueEnvironment
import com.indagium.testing.model.IssueSeverity
import com.indagium.testing.model.IssueSource
import com.indagium.testing.model.JudgeClassification
import com.indagium.testing.model.JudgeVerdict
import com.indagium.testing.model.LaneKind
import com.indagium.testing.model.LaneResult
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.normalizeTags
import com.indagium.testing.store.judgementToJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.io.File
import java.io.RandomAccessFile

// Turns one finished step of a run into an IssueDraft. Everything in the draft comes from the frozen run: the text of the
// case and its steps, the check results, the judge's verdict and the evidence files. Nothing is invented: a part the run
// has no data for stays empty. Reading the evidence touches the disk, so callers run this off the UI thread.
//
// Severity rule ([issueSeverityFor]):
//   judge says APP_DEFECT and the step's log shows a crash (FATAL EXCEPTION, Fatal signal, ANR)  -> CRITICAL
//   judge says APP_DEFECT                                                                         -> HIGH
//   judge says AGENT_OR_STEP_PROBLEM                                                              -> LOW
//   anything else (no judge, UNKNOWN)                                                             -> MEDIUM
// The judge's classification is the step's own judgement, else the comparison judge's one for that step.

const val FOUND_BY_AGENT_LABEL = "found-by-agent"
const val MAX_ISSUE_LOG_BYTES = 256 * 1024
const val MAX_ISSUE_TRANSCRIPT_BYTES = 256 * 1024
const val MAX_ISSUE_TITLE_CHARS = 160

private const val MAX_ACTION_IN_TITLE_CHARS = 80
private const val MAX_ARG_VALUE_CHARS = 40
private val CRASH_MARKERS = Regex("FATAL EXCEPTION|Fatal signal|ANR in ")
private val prettyJson = Json { prettyPrint = true }

/** The issue draft of a step and the reference that ties the issue to it. */
internal class BuiltIssue(val draft: IssueDraft, val source: IssueSource)

/** What building a draft reads: the run, its folder on disk and how to find a golden screenshot file. */
internal class IssueDraftContext(
    val run: TestRun,
    val runDir: File,
    val goldenFile: (suiteId: String, assetPath: String) -> File? = { _, _ -> null },
)

/** [log] is the text of the step's log range, when there is one. */
internal fun issueSeverityFor(classification: JudgeClassification?, log: String?): IssueSeverity = when (classification) {
    JudgeClassification.APP_DEFECT -> if (log != null && CRASH_MARKERS.containsMatchIn(log)) IssueSeverity.CRITICAL else IssueSeverity.HIGH
    JudgeClassification.AGENT_OR_STEP_PROBLEM -> IssueSeverity.LOW
    JudgeClassification.UNKNOWN, null -> IssueSeverity.MEDIUM
}

private fun oneLine(text: String) = text.trim().replace(Regex("\\s+"), " ")

/**
 * The draft for [step] (a result of case [caseId], run [iteration], on lane [laneId]) or why there is none. [step] is passed
 * in because the engine builds the draft the moment the step is recorded, before the run holds it. A step of a setup hook
 * or of the suite's own hooks cannot become an issue: it is not a step of a case.
 */
internal fun buildIssueDraft(context: IssueDraftContext, laneId: String, caseId: String, iteration: Int, step: StepResult): Result<BuiltIssue> {
    val run = context.run
    val lane = run.lane(laneId) ?: return Result.failure(IllegalArgumentException("Run '${run.id}' has no lane '$laneId'."))
    val case = run.suite.cases.firstOrNull { it.id == caseId }
        ?: return Result.failure(IllegalArgumentException("Run '${run.id}' has no case '$caseId'."))
    val definition = case.steps.firstOrNull { it.id == step.stepId }
    if (step.setup || definition == null) {
        return Result.failure(IllegalArgumentException("Step '${step.stepId}' is not a step of case '${case.name}'; only case steps can become issues."))
    }
    val logText = lane.logPath?.let { File(context.runDir, it) }?.let { readByteRangeTail(it, step.logStartOffset, step.logEndOffset, MAX_ISSUE_LOG_BYTES) }
    val classification = step.judge?.classification?.takeIf { step.judge.verdict != JudgeVerdict.INCONCLUSIVE }
        ?: run.comparisons.firstOrNull { it.caseId == caseId && it.iteration == iteration && it.stepId == step.stepId }?.classification
    val draft = IssueDraft(
        title = issueTitle(case, step, classification),
        severity = issueSeverityFor(classification, logText),
        labels = normalizeTags(run.suite.tags + FOUND_BY_AGENT_LABEL),
        stepsToReproduce = reproductionSteps(run, case, definition),
        expected = expectedText(definition, step),
        actual = actualText(step),
        judgeNotes = judgeNotesText(run, caseId, iteration, step),
        environment = IssueEnvironment(
            appPackage = run.suite.targetPackage,
            deviceSerial = lane.config.deviceSerial,
            agent = if (lane.config.kind == LaneKind.EXTERNAL) "an external client over MCP" else "AI agent (profile ${lane.config.profileId.orEmpty()})",
            runId = run.id,
        ),
        attachments = issueAttachments(context, lane, step, definition, logText),
    )
    val source = IssueSource(run.id, laneId, run.suite.id, caseId, step.stepId, iteration, case.name, step.stepNumber)
    return Result.success(BuiltIssue(draft, source))
}

// ── Text ─────────────────────────────────────────────────────────────

private fun StepStatus.verb(): String = when (this) {
    StepStatus.PASS -> "passed"
    StepStatus.FAIL -> "failed"
    StepStatus.BLOCKED -> "was blocked"
    StepStatus.TIMEOUT -> "timed out"
    StepStatus.SKIPPED -> "was skipped"
    StepStatus.ERROR -> "errored"
}

private fun JudgeClassification.phrase(): String = when (this) {
    JudgeClassification.APP_DEFECT -> "app defect"
    JudgeClassification.AGENT_OR_STEP_PROBLEM -> "agent or step problem"
    JudgeClassification.UNKNOWN -> "cause unknown"
}

private fun failedChecksOf(step: StepResult): List<CheckResult> = step.checks.filter { it.status == CheckStatus.FAIL || it.status == CheckStatus.ERROR }

private fun issueTitle(case: TestCase, step: StepResult, classification: JudgeClassification?): String {
    val action = oneLine(step.action).let { if (it.length > MAX_ACTION_IN_TITLE_CHARS) it.take(MAX_ACTION_IN_TITLE_CHARS) + "…" else it }
    val firstFailed = failedChecksOf(step).firstOrNull()
    val summary = when {
        firstFailed != null -> "${step.status.verb()}: ${firstFailed.kind} check failed"
        step.judge?.verdict == JudgeVerdict.FAIL -> "${step.status.verb()}: the judge found a mismatch"
        else -> step.status.verb()
    }
    val suffix = classification?.takeIf { it != JudgeClassification.UNKNOWN }?.let { " (${it.phrase()})" }.orEmpty()
    return "${case.name} · step ${step.stepNumber}: $action — $summary$suffix".take(MAX_ISSUE_TITLE_CHARS)
}

private fun argsText(args: Map<String, String>): String =
    if (args.isEmpty()) "" else args.entries.joinToString(", ", prefix = " (", postfix = ")") { (k, v) -> "$k=${oneLine(v).take(MAX_ARG_VALUE_CHARS)}" }

private fun hookLine(run: TestRun, scope: String, hook: HookItem): String = when (hook) {
    is HookItem.Script -> {
        val script = run.scripts.firstOrNull { it.id == hook.scriptId }
        if (script == null) {
            "$scope setup: run script ${hook.scriptId} (no longer in the library)"
        } else {
            "$scope setup: run script ${script.toolName}${argsText(hook.args)}"
        }
    }
    is HookItem.Shared -> {
        val shared = run.sharedSteps.firstOrNull { it.id == hook.sharedStepId }
        if (shared == null) {
            "$scope setup: run shared step ${hook.sharedStepId} (no longer in the library)"
        } else {
            "$scope setup: run shared step “${shared.name}” (${shared.steps.joinToString("; ") { oneLine(it.action) }})"
        }
    }
}

private fun reproductionSteps(run: TestRun, case: TestCase, failing: TestStep): List<String> = buildList {
    run.suite.setup.forEach { add(hookLine(run, "Suite", it)) }
    case.setup.forEach { add(hookLine(run, "Case", it)) }
    if (case.preconditions.isNotBlank()) add("Precondition: ${oneLine(case.preconditions)}")
    val failingIndex = case.steps.indexOfFirst { it.id == failing.id }
    case.steps.take(failingIndex + 1).forEach { add(oneLine(it.action)) }
}

private fun describeCheck(check: StepCheck): String = when (check) {
    is StepCheck.LogAppears ->
        "A log line matching /${check.regex}/${check.tag?.let { " (tag $it)" }.orEmpty()} appears within ${check.withinMs} ms"
    is StepCheck.LogAbsent ->
        "No log line matches /${check.regex}/${check.tag?.let { " (tag $it)" }.orEmpty()} for ${check.forMs} ms"
    is StepCheck.ScreenJudge -> "The screen shows: ${oneLine(check.text)}"
    is StepCheck.ScriptResult -> "Script ${check.scriptId}${argsText(check.args)} ends with exit code ${check.exitCode ?: "any"}" +
        (check.stdoutContains?.let { " and prints “$it”" } ?: "")
    is StepCheck.AskJudge -> "The judge's question is answered positively: ${oneLine(check.text)}"
}

private fun expectedText(definition: TestStep, step: StepResult): String {
    val written = step.expected.ifBlank { definition.expected }.trim()
    val failedIds = failedChecksOf(step).map { it.checkId }.toSet()
    val failed = definition.checks.filter { it.id in failedIds }
    return buildString {
        append(written)
        if (failed.isNotEmpty()) {
            if (isNotEmpty()) append("\n\n")
            append("Checks that were expected to hold:\n")
            append(failed.joinToString("\n") { "- ${describeCheck(it)}" })
        }
    }
}

private fun quoted(text: String): String = text.trim().lines().joinToString("\n") { "> $it" }

private fun actualText(step: StepResult): String = buildString {
    append("The step ").append(step.status.verb()).append(" after ").append(step.attempts).append(if (step.attempts == 1) " attempt." else " attempts.")
    if (step.checks.isNotEmpty()) {
        append("\n\nCheck results:\n")
        append(step.checks.joinToString("\n") { "- ${it.kind}: ${it.status.name} — ${oneLine(it.detail)}" })
    }
    step.judge?.let { judge ->
        append("\n\nJudge verdict: ").append(judge.verdict.name)
        if (judge.reasoning.isNotBlank()) append(". Reasoning (written by the judge model): ").append(oneLine(judge.reasoning))
        judge.error?.let { append("\nThe judge could not finish: ").append(oneLine(it)) }
    }
    if (step.observation.isNotBlank()) append("\n\nAgent observation (untrusted):\n").append(quoted(step.observation))
    step.note?.takeIf { it.isNotBlank() }?.let { append("\n\nNote: ").append(oneLine(it)) }
}

private fun judgeNotesText(run: TestRun, caseId: String, iteration: Int, step: StepResult): String = buildString {
    step.judge?.let { judge ->
        append("Judge: ").append(judge.verdict.name).append(" (").append(judge.classification.phrase()).append(")")
        judge.suggestedFix?.let { fix ->
            append("\nSuggested step fix:")
            fix.action?.let { append("\n- Action: ").append(oneLine(it)) }
            fix.expected?.let { append("\n- Expected: ").append(oneLine(it)) }
            fix.note?.let { append("\n- Note: ").append(oneLine(it)) }
        }
    }
    run.comparisons.filter { it.caseId == caseId && it.iteration == iteration && it.stepId == step.stepId }.forEach { comparison ->
        if (isNotEmpty()) append("\n\n")
        val verdicts = comparison.verdicts.entries.joinToString(", ") { (laneId, verdict) ->
            "${run.lane(laneId)?.config?.deviceSerial ?: laneId}: ${verdict.name}"
        }
        append("Lanes compared (").append(verdicts).append("; ").append(comparison.classification.phrase()).append(")")
        if (comparison.explanation.isNotBlank()) append(": ").append(oneLine(comparison.explanation))
        comparison.suggestedFix?.note?.let { append("\nSuggested fix note: ").append(oneLine(it)) }
    }
}

// ── Evidence ─────────────────────────────────────────────────────────

/** The text of bytes [start] until [end] of [file], at most [maxBytes] of them: the END is kept (a failure is at the end) and a cut says so. */
internal fun readByteRangeTail(file: File, start: Long?, end: Long?, maxBytes: Int): String? {
    if (start == null || end == null || end <= start || !file.isFile) return null
    val last = minOf(end, file.length())
    if (last <= start) return null
    val available = last - start
    val from = if (available > maxBytes) last - maxBytes else start
    val buffer = ByteArray((last - from).toInt())
    RandomAccessFile(file, "r").use { raf ->
        raf.seek(from)
        raf.readFully(buffer)
    }
    var text = String(buffer, Charsets.UTF_8)
    if (from > start) {
        text = text.substringAfter('\n', text)
        text = "… (${from - start} earlier bytes left out)\n$text"
    }
    return text.takeIf { it.isNotBlank() }
}

private fun textAttachment(kind: IssueAttachmentKind, label: String, fileName: String, text: String) =
    IssueAttachment(kind, label, fileName, sizeBytes = text.toByteArray(Charsets.UTF_8).size.toLong(), text = text)

private fun fileAttachment(kind: IssueAttachmentKind, label: String, fileName: String, file: File, include: Boolean = true, note: String = "") =
    IssueAttachment(kind, label, fileName, sizeBytes = file.length(), include = include, sourcePath = file.absolutePath, note = note)

private fun goldenOf(step: TestStep): StepExample.GoldenScreenshot? {
    val goldens = step.examples.filterIsInstance<StepExample.GoldenScreenshot>()
    val named = step.checks.filterIsInstance<StepCheck.ScreenJudge>().firstNotNullOfOrNull { check -> goldens.firstOrNull { it.id == check.exampleRef } }
    return named ?: goldens.firstOrNull()
}

private fun issueAttachments(context: IssueDraftContext, lane: LaneResult, step: StepResult, definition: TestStep, logText: String?): List<IssueAttachment> {
    val runDir = context.runDir
    return buildList {
        step.screenshotPath?.takeIf { it.isNotBlank() }?.let { File(runDir, it) }?.takeIf { it.isFile }?.let { file ->
            add(fileAttachment(IssueAttachmentKind.SCREENSHOT, "Screenshot at the end of the step", "screenshot.${file.extension.ifBlank { "png" }}", file))
        }
        goldenOf(definition)?.let { golden -> context.goldenFile(context.run.suite.id, golden.assetPath) }?.takeIf { it.isFile }?.let { file ->
            add(fileAttachment(IssueAttachmentKind.GOLDEN, "Expected (golden) screenshot", "expected.${file.extension.ifBlank { "png" }}", file))
        }
        logText?.let { add(textAttachment(IssueAttachmentKind.LOG_RANGE, "Log during the step", "log-range.txt", it)) }
        step.judge?.let { judge ->
            val json = prettyJson.encodeToString(JsonElement.serializer(), judgementToJson(judge))
            add(textAttachment(IssueAttachmentKind.JUDGE_VERDICT, "Judge verdict", "judge-verdict.json", json))
        }
        lane.transcriptPath?.let { File(runDir, it) }
            ?.let { readByteRangeTail(it, step.transcriptStartOffset, step.transcriptEndOffset, MAX_ISSUE_TRANSCRIPT_BYTES) }
            ?.let { add(textAttachment(IssueAttachmentKind.TRANSCRIPT, "Agent transcript during the step", "transcript-slice.jsonl", it)) }
    }
}
