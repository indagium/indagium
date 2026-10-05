package com.indagium.testing.run

import com.indagium.model.LogEntry
import com.indagium.testing.device.LogAbsentResult
import com.indagium.testing.device.LogWaitResult
import com.indagium.testing.device.TestDeviceSession
import com.indagium.testing.model.CheckResult
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestScript
import com.indagium.testing.script.AdbScriptTarget
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.ScriptRunOutcome
import com.indagium.testing.script.ScriptRunResult
import com.indagium.testing.script.TestScriptRunner
import kotlinx.coroutines.CancellationException
import java.io.IOException

// The checks of a step that need no judge: they read the lane's recorded log or run a library script. Each answers with
// a CheckResult and never throws for an expected problem (an invalid regular expression, a missing script, adb
// failing) — that is an ERROR result the report shows. The two judge checks are NOT_EVALUATED in this version.
//
// Timing, all measured from the moment finish_step starts evaluating:
//   LogAppears — rows written since the step began count at once; otherwise waits up to withinMs for one.
//   LogAbsent  — watches for forMs (a matching row ends it early as a failure), then flushes the recorder once more
//                and reads the tail, so a row still buffered cannot slip through.

private const val MAX_CHECK_WAIT_MS = 10 * 60 * 1000L
private const val ROW_EXCERPT_CHARS = 200
private const val OUTPUT_EXCERPT_CHARS = 300
private const val NANOS_PER_MILLI = 1_000_000L
private const val JUDGE_PENDING_DETAIL = "Needs the judge, which does not run in this version."

internal fun StepCheck.kindName(): String = when (this) {
    is StepCheck.LogAppears -> "logAppears"
    is StepCheck.LogAbsent -> "logAbsent"
    is StepCheck.ScreenJudge -> "screenJudge"
    is StepCheck.ScriptResult -> "scriptResult"
    is StepCheck.AskJudge -> "askJudge"
}

internal class DeterministicChecks(
    private val session: TestDeviceSession,
    private val scriptRunner: TestScriptRunner,
    private val findScript: (String) -> TestScript?,
    private val scriptContext: () -> ScriptRunContext,
) {
    /** Evaluates [checks] in order. [stepLogOffset] is the log marker taken when the step (attempt) began. */
    suspend fun evaluateAll(checks: List<StepCheck>, stepLogOffset: Long): List<CheckResult> = checks.map { evaluate(it, stepLogOffset) }

    suspend fun evaluate(check: StepCheck, stepLogOffset: Long): CheckResult {
        val startedNanos = System.nanoTime()
        val outcome = try {
            when (check) {
                is StepCheck.LogAppears -> logAppears(check, stepLogOffset)
                is StepCheck.LogAbsent -> logAbsent(check, stepLogOffset)
                is StepCheck.ScriptResult -> scriptResult(check)
                is StepCheck.ScreenJudge, is StepCheck.AskJudge -> Outcome(CheckStatus.NOT_EVALUATED, JUDGE_PENDING_DETAIL)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            Outcome(CheckStatus.ERROR, invalid.message ?: "The check could not be evaluated.")
        } catch (failed: IllegalStateException) {
            Outcome(CheckStatus.ERROR, failed.message ?: "The check could not be evaluated.")
        } catch (failed: IOException) {
            Outcome(CheckStatus.ERROR, failed.message ?: "The check could not be evaluated.")
        }
        return CheckResult(check.id, check.kindName(), outcome.status, outcome.detail, (System.nanoTime() - startedNanos) / NANOS_PER_MILLI)
    }

    private class Outcome(val status: CheckStatus, val detail: String)

    private suspend fun logAppears(check: StepCheck.LogAppears, stepLogOffset: Long): Outcome {
        val result = session.waitForLog(check.regex, check.tag, stepLogOffset, check.withinMs.coerceIn(0L, MAX_CHECK_WAIT_MS))
        return when (result) {
            is LogWaitResult.Matched -> Outcome(CheckStatus.PASS, "Found after ${result.waitedMs} ms: ${rowExcerpt(result.entry)}")
            is LogWaitResult.TimedOut -> Outcome(
                CheckStatus.FAIL,
                "No log row matching /${check.regex}/${tagPhrase(check.tag)} appeared within ${check.withinMs} ms" +
                    if (result.recording) "." else " (the log recording had stopped).",
            )
        }
    }

    private suspend fun logAbsent(check: StepCheck.LogAbsent, stepLogOffset: Long): Outcome {
        val result = session.ensureAbsent(check.regex, check.tag, stepLogOffset, check.forMs.coerceIn(0L, MAX_CHECK_WAIT_MS))
        return when (result) {
            is LogAbsentResult.Absent -> Outcome(CheckStatus.PASS, "No row matching /${check.regex}/${tagPhrase(check.tag)} for ${check.forMs} ms.")
            is LogAbsentResult.Found -> Outcome(CheckStatus.FAIL, "A row matching /${check.regex}/ appeared: ${rowExcerpt(result.entry)}")
        }
    }

    private suspend fun scriptResult(check: StepCheck.ScriptResult): Outcome {
        val script = findScript(check.scriptId) ?: return Outcome(CheckStatus.ERROR, "Script '${check.scriptId}' is not in the library.")
        val adb: AdbScriptTarget? = if (script.target == ScriptTarget.ADB_SHELL) session.adbTarget() else null
        return when (val outcome = scriptRunner.run(script, check.args, scriptContext().copy(deviceSerial = session.serial), adb)) {
            is ScriptRunOutcome.Rejected -> Outcome(CheckStatus.ERROR, outcome.message)
            is ScriptRunOutcome.Finished -> judgeScript(check, outcome.result)
        }
    }

    private fun judgeScript(check: StepCheck.ScriptResult, result: ScriptRunResult): Outcome {
        val summary = "exit code ${result.exitCode}${if (result.timedOut) " (timed out)" else ""}, output: ${excerpt(result.stdout)}"
        return when {
            result.timedOut -> Outcome(CheckStatus.FAIL, "The script timed out; $summary")
            check.exitCode != null && result.exitCode != check.exitCode ->
                Outcome(CheckStatus.FAIL, "Expected exit code ${check.exitCode}; $summary")
            check.stdoutContains != null && !result.stdout.contains(check.stdoutContains) ->
                Outcome(CheckStatus.FAIL, "The output does not contain \"${check.stdoutContains}\"; $summary")
            else -> Outcome(CheckStatus.PASS, summary)
        }
    }

    private fun tagPhrase(tag: String?): String = if (tag == null) "" else " with tag $tag"

    private fun rowExcerpt(entry: LogEntry): String = excerpt("${entry.level.key} ${entry.tag}: ${entry.msg}", ROW_EXCERPT_CHARS)

    private fun excerpt(text: String, max: Int = OUTPUT_EXCERPT_CHARS): String {
        val oneLine = text.trim().replace(Regex("\\s+"), " ")
        return if (oneLine.length <= max) oneLine else oneLine.take(max) + "…"
    }
}
