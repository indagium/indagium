package com.indagium.testing.run

import com.indagium.testing.device.NO_RECORDED_INPUT_MESSAGE
import com.indagium.testing.device.replayEligibilityProblem
import com.indagium.testing.model.CheckStatus
import com.indagium.testing.model.ManualCompletion
import com.indagium.testing.model.ManualStepsOption
import com.indagium.testing.model.RecordedInput
import com.indagium.testing.model.ScriptCall
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.StepCompletion
import com.indagium.testing.model.StepKind
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.TestStep
import com.indagium.testing.model.completionChecks
import com.indagium.testing.script.ScriptRunOutcome
import com.indagium.testing.script.ScriptRunResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

// Performs the steps the RUN does itself, between the agent's: a SCRIPT step runs a library script; a MANUAL step waits for a tester
// (or runs its automation script, or is skipped, depending on the run's manual-steps option); a REPLAY step injects the input it was
// recorded from (see ReplayPlan.kt) and waits for the screen to settle. It runs the same on a lane with an agent and on an external lane. The lane driver calls [runPending]
// whenever the sequence stands on such a step; it returns once the sequence is on an AGENT step again or is over. No AI agent is
// started for these steps. The outcome is handed to the sequence (finishDriverStep, skipDriverStep, failDriverStep), which records the
// result, runs the step's other checks and applies retries and onFailure like for any step.
//
// SCRIPT: ASK scripts wait for the user's yes (a card in the live view, resolve_test_confirmation); a refusal or no answer BLOCKS the
// step. A non-zero exit, a timeout or a failed exit-code/stdout expectation fails it.
// REPLAY: the recorded input is scaled to the device (a device of other proportions is refused: BLOCKED), injected in order with its
// recorded gaps and gesture timing, and the lane waits a bounded part of the recorded settle time. The claim is PASS when every input
// went in; the step's checks and the run's judge then decide the result exactly like an AI step's. A failed injection is a FAIL, not a
// retry: re-injecting after a partial replay could undo a toggle (a replay step has no automatic retry unless its author sets retries).
// MANUAL (wait): the lane publishes what the tester must do (LaneHandle.beginManualWait) and polls until the tester answers (Done,
// Failed, Skip), the step's completion checks (log lines, device state) are all true, or the step's time allowance is over; the last
// is "not confirmed", a problem of the step or the run, never of the app.

private const val OUTPUT_EXCERPT_CHARS = 400
private const val CHECKS_HINT_CHARS = 300

internal class DriverStepRunner(
    private val seq: StepSequence,
    private val handle: LaneHandle,
    private val manualSteps: ManualStepsOption,
    private val tuning: EngineTuning,
    private val confirmationTimeoutMs: Long,
) {
    private val env = seq.environment
    private val checks = DeterministicChecks(env.session, env.scriptRunner, { id -> env.scripts.firstOrNull { it.id == id } }, seq::scriptContext)

    /** Performs the steps the sequence stands on that the run performs itself, until it reaches an agent step or ends. */
    suspend fun runPending() {
        while (true) {
            val step = seq.driverStep ?: return
            env.pauseGate?.awaitResumed()
            val index = seq.currentIndex
            val attempt = seq.currentAttempt
            when (step.kind) {
                StepKind.SCRIPT -> performScriptStep(step)
                StepKind.MANUAL -> performManualStep(step)
                StepKind.REPLAY -> performReplayStep(step)
                StepKind.AGENT -> return
            }
            // Whatever happened, the step is closed or redone; a sequence that did not move would loop for ever.
            if (!seq.ended && seq.currentIndex == index && seq.currentAttempt == attempt) {
                seq.abort(StepStatus.ERROR, "The step could not be completed.")
                return
            }
        }
    }

    // ── Script steps ─────────────────────────────────────────────────

    private suspend fun performScriptStep(step: TestStep) {
        val call = step.script
        if (call == null) {
            seq.failDriverStep(DriverReport(LaneStepStatus.BLOCKED, "The step has no script.", null, retryable = false), StepStatus.ERROR)
            return
        }
        val ran = runScript(step, call)
        if (ran == null) {
            val message = "The script did not finish within the step's time allowance of ${step.timeoutMs} ms."
            seq.failDriverStep(DriverReport(LaneStepStatus.FAIL, message, null), StepStatus.TIMEOUT)
            return
        }
        seq.finishDriverStep(ran.report(if (step.kind == StepKind.MANUAL) "Automation script" else "Script"))
    }

    /** How a script run ended for the step: [claim] is PASS only when it ran and met every expectation. */
    private class ScriptOutcome(val claim: LaneStepStatus, val summary: String, val retryable: Boolean = true) {
        fun report(label: String): DriverReport {
            val passed = claim == LaneStepStatus.PASS
            // A script that did not do its job leaves nothing to check: skip the checks' waiting windows.
            return DriverReport(claim, "$label: $summary", StepCompletion.SCRIPT, checks = if (passed) null else emptyList(), retryable = retryable)
        }
    }

    /**
     * Runs the step's script and says how it ended; null when it did not finish within the step's time allowance. The allowance
     * starts after the user's permission (a card can take minutes) and covers only the script itself.
     */
    private suspend fun runScript(step: TestStep, call: ScriptCall): ScriptOutcome? {
        val script = env.scripts.firstOrNull { it.id == call.scriptId }
            ?: return ScriptOutcome(LaneStepStatus.BLOCKED, "script '${call.scriptId}' is not in the library.", retryable = false)
        if (script.permission == ScriptPermission.SETUP_TEARDOWN_ONLY) {
            return ScriptOutcome(LaneStepStatus.BLOCKED, "script '${script.toolName}' is limited to setup and teardown hooks.", retryable = false)
        }
        if (script.permission == ScriptPermission.ASK && !confirmed(script)) {
            return ScriptOutcome(LaneStepStatus.BLOCKED, "running '${script.toolName}' was not allowed (declined, or not answered in time).", retryable = false)
        }
        // Consent can take an arbitrary amount of time. Start the step's single action/check
        // allowance immediately before the script; finishDriverStep keeps its remaining time.
        seq.resetStepTimer()
        return withTimeoutOrNull(step.timeoutMs) {
            seq.beforeDeviceMutation()
            execute(step, script, call)
        }
    }

    private suspend fun execute(step: TestStep, script: TestScript, call: ScriptCall): ScriptOutcome {
        val adb = if (script.target == ScriptTarget.ADB_SHELL) env.session.adbTarget() else null
        env.transcript?.append("driver_script_started", mapOf("step" to step.id, "script" to script.toolName))
        return when (val outcome = env.scriptRunner.run(script, call.args, seq.scriptContext(), adb)) {
            is ScriptRunOutcome.Rejected -> ScriptOutcome(LaneStepStatus.FAIL, "'${script.toolName}' could not run: ${outcome.message}", retryable = false)
            is ScriptRunOutcome.Finished -> judgeScript(script, call, outcome.result)
        }
    }

    private suspend fun confirmed(script: TestScript): Boolean {
        val where = if (script.target == ScriptTarget.ADB_SHELL) "on the device" else "on this computer"
        env.listener.waitStateChanged()
        return try {
            handle.awaitScriptConfirmation(script.toolName, "Run script '${script.toolName}' $where (a test step)", confirmationTimeoutMs)
        } finally {
            env.listener.waitStateChanged()
        }
    }

    private fun judgeScript(script: TestScript, call: ScriptCall, result: ScriptRunResult): ScriptOutcome {
        val stdout = result.stdout.trim().replace(Regex("\\s+"), " ").take(OUTPUT_EXCERPT_CHARS)
        val warnings = result.warnings.joinToString("") { " Warning: $it" }
        val summary = "'${script.toolName}' exit code ${result.exitCode}${if (result.timedOut) " (timed out)" else ""}: $stdout$warnings"
        val failure = when {
            result.timedOut -> "the script timed out"
            call.exitCode != null && result.exitCode != call.exitCode -> "expected exit code ${call.exitCode}"
            call.stdoutContains != null && !result.stdout.contains(call.stdoutContains) -> "the output does not contain \"${call.stdoutContains}\""
            else -> null
        }
        return if (failure == null) {
            ScriptOutcome(LaneStepStatus.PASS, summary)
        } else {
            val errorText = result.stderr.trim().replace(Regex("\\s+"), " ").take(OUTPUT_EXCERPT_CHARS)
            ScriptOutcome(LaneStepStatus.FAIL, "$failure; $summary" + if (errorText.isEmpty()) "" else " stderr: $errorText")
        }
    }

    // ── Replay steps ─────────────────────────────────────────────────

    /** How a replay ended: injected (with the line for the observation), not allowed to run, or failed while injecting. */
    private sealed interface ReplayOutcome {
        data class Done(val summary: String) : ReplayOutcome

        data class Refused(val reason: String) : ReplayOutcome

        data class Failed(val reason: String) : ReplayOutcome
    }

    private suspend fun performReplayStep(step: TestStep) {
        step.replayEligibilityProblem()?.let { reason ->
            seq.failDriverStep(DriverReport(LaneStepStatus.BLOCKED, "Replay refused: $reason", null, retryable = false), StepStatus.BLOCKED)
            return
        }
        val input = step.recordedInput
        if (input == null) {
            seq.failDriverStep(DriverReport(LaneStepStatus.BLOCKED, NO_RECORDED_INPUT_MESSAGE, null, retryable = false), StepStatus.ERROR)
            return
        }
        // The allowance covers the injection and the checks after it, like a script's.
        seq.resetStepTimer()
        val outcome = withTimeoutOrNull(step.timeoutMs) {
            seq.beforeDeviceMutation()
            replay(step, input)
        }
        when (outcome) {
            null -> seq.failDriverStep(
                DriverReport(
                    LaneStepStatus.FAIL,
                    "The replay did not finish within the step's time allowance of ${step.timeoutMs} ms.",
                    null,
                    retryable = false,
                ),
                StepStatus.TIMEOUT,
            )
            is ReplayOutcome.Refused -> seq.failDriverStep(
                DriverReport(LaneStepStatus.BLOCKED, "Replay refused: ${outcome.reason}", null, retryable = false),
                StepStatus.BLOCKED,
            )
            is ReplayOutcome.Failed -> seq.finishDriverStep(
                DriverReport(LaneStepStatus.FAIL, "Replay failed: ${outcome.reason}", StepCompletion.REPLAY, checks = emptyList(), retryable = false),
            )
            is ReplayOutcome.Done -> seq.finishDriverStep(DriverReport(LaneStepStatus.PASS, outcome.summary, StepCompletion.REPLAY))
        }
    }

    @Suppress("TooGenericExceptionCaught", "ReturnCount") // Each injection failure has a distinct safe outcome; never crash the lane.
    private suspend fun replay(step: TestStep, input: RecordedInput): ReplayOutcome {
        // A fresh screenshot says what the device looks like now (it may have been rotated since the last one).
        val space = try {
            env.session.screenshot().space
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return ReplayOutcome.Failed("the screen could not be read: ${failure.message ?: failure::class.simpleName}")
        }
        val plan = when (val planned = planReplay(input, space)) {
            is ReplayPlan.Refused -> return ReplayOutcome.Refused(planned.reason)
            is ReplayPlan.Ready -> planned
        }
        if (plan.plannedMs + plan.settleMs > step.timeoutMs) {
            return ReplayOutcome.Refused(
                "injecting the recorded input takes about ${plan.plannedMs + plan.settleMs} ms, " +
                    "more than the step's time allowance of ${step.timeoutMs} ms; raise the timeout.",
            )
        }
        env.transcript?.append(
            "driver_replay_started",
            mapOf("step" to step.id, "inputs" to plan.descriptions, "plannedMs" to plan.plannedMs, "settleMs" to plan.settleMs),
        )
        var failure: String? = null
        val results = try {
            env.session.performReplay(plan.actions) { number, result ->
                seq.reportDriverToolCall(
                    result.action,
                    plan.descriptions[number - 1],
                    result.detail ?: if (result.succeeded) "ok" else "failed",
                    result.succeeded,
                    result.durationMs,
                )
                if (!result.succeeded) failure = "input $number (${plan.descriptions[number - 1]}) failed: ${result.detail ?: "device input failed"}"
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (invalid: IllegalArgumentException) {
            return ReplayOutcome.Refused(invalid.message ?: "the recorded input does not fit this screen.")
        } catch (failed: Exception) {
            return ReplayOutcome.Failed(failed.message ?: failed::class.simpleName.orEmpty())
        }
        env.transcript?.append("driver_replay_finished", mapOf("step" to step.id, "injected" to results.size, "failure" to failure))
        failure?.let { return ReplayOutcome.Failed(it) }
        delay(plan.settleMs)
        val notes = results.mapNotNull { it.detail }.distinct()
        val fallback = if (notes.isEmpty()) "" else " " + notes.joinToString(" ")
        return ReplayOutcome.Done(
            "Replayed ${results.size} recorded input(s) (${plan.descriptions.joinToString("; ")}); " +
                "waited ${plan.settleMs} ms for the screen to settle.$fallback",
        )
    }

    // ── Manual steps ─────────────────────────────────────────────────

    private suspend fun performManualStep(step: TestStep) {
        if (manualSteps == ManualStepsOption.SKIP) {
            seq.skipDriverStep(DriverReport(LaneStepStatus.PASS, "Skipped: this run does not do manual steps.", StepCompletion.SKIPPED))
            return
        }
        val call = step.script
        var hint = ""
        if (manualSteps == ManualStepsOption.USE_SCRIPT_THEN_WAIT && call != null) {
            val ran = runScript(step, call)
            if (ran?.claim == LaneStepStatus.PASS) {
                seq.finishDriverStep(ran.report("Automation script"))
                return
            }
            hint = "The automation script did not do it (${ran?.summary ?: "it did not finish in time"}); do it by hand."
        }
        waitForTester(step, hint)
    }

    private sealed interface Wait {
        data class Answered(val resolution: ManualResolution) : Wait

        data object ChecksPassed : Wait

        data object TimedOut : Wait
    }

    private suspend fun waitForTester(step: TestStep, scriptHint: String) {
        seq.beforeDeviceMutation()
        val completion = step.manualCompletion
        val watched = if (completion == ManualCompletion.TESTER_ONLY) emptyList() else step.completionChecks
        val startedNanos = env.nanoTime()
        val startedAt = env.wallClock()
        val info = ManualWait(
            caseName = seq.spec.caseName,
            stepNumber = seq.currentIndex + 1,
            stepCount = seq.spec.steps.size,
            instructions = step.action,
            expected = step.expected,
            deviceSerial = env.session.serial,
            startedAtMs = startedAt,
            deadlineAtMs = startedAt + step.timeoutMs,
            completion = completion,
            testerCanComplete = completion != ManualCompletion.CHECKS_ONLY,
            hint = listOf(scriptHint, watchedHint(watched)).filter(String::isNotBlank).joinToString(" "),
        )
        val answer = handle.beginManualWait(info)
        env.transcript?.append("manual_wait_started", mapOf("step" to step.id, "completion" to completion.name, "timeoutMs" to step.timeoutMs))
        env.listener.waitStateChanged()
        val outcome = try {
            awaitCompletion(step, answer, watched, startedNanos)
        } finally {
            handle.endManualWait()
            env.listener.waitStateChanged()
        }
        env.transcript?.append("manual_wait_ended", mapOf("step" to step.id, "outcome" to outcome::class.simpleName))
        conclude(step, outcome)
    }

    private suspend fun awaitCompletion(
        step: TestStep,
        answer: CompletableDeferred<ManualResolution>,
        watched: List<StepCheck>,
        startedNanos: Long,
    ): Wait {
        while (true) {
            val remaining = step.timeoutMs - (env.nanoTime() - startedNanos) / NANOS_PER_MILLI
            if (remaining <= 0) return Wait.TimedOut
            val resolved = withTimeoutOrNull(minOf(tuning.manualPollMs, remaining)) { answer.await() }
            if (resolved != null) return Wait.Answered(resolved)
            if (watched.isNotEmpty() && watched.all { passesNow(it) }) return Wait.ChecksPassed
        }
    }

    /** Reads one completion check once, without its waiting window. */
    private suspend fun passesNow(check: StepCheck): Boolean {
        val once = when (check) {
            is StepCheck.DeviceState -> check.copy(withinMs = 0L)
            is StepCheck.LogAppears -> check.copy(withinMs = 0L)
            else -> return false
        }
        return checks.evaluate(once, seq.stepLogOffset).status == CheckStatus.PASS
    }

    private suspend fun conclude(step: TestStep, outcome: Wait) {
        when (outcome) {
            Wait.TimedOut -> seq.failDriverStep(
                DriverReport(
                    LaneStepStatus.FAIL,
                    "Not confirmed: nobody completed this manual step within ${step.timeoutMs / MILLIS_PER_SECOND} s.",
                    null,
                ),
                StepStatus.TIMEOUT,
            )
            Wait.ChecksPassed ->
                seq.finishDriverStep(DriverReport(LaneStepStatus.PASS, "Detected as done: its checks are true.", StepCompletion.CHECKS))
            is Wait.Answered -> concludeAnswered(step, outcome.resolution)
        }
    }

    private suspend fun concludeAnswered(step: TestStep, resolution: ManualResolution) {
        val note = resolution.note.trim().takeIf(String::isNotEmpty)?.let { " Tester's note: $it" }.orEmpty()
        when (resolution.decision) {
            ManualDecision.DONE -> {
                // With "tester or checks" the tester's word replaces the completion checks; "tester only" still verifies every check afterwards.
                val verified = if (step.manualCompletion == ManualCompletion.TESTER_OR_CHECKS) step.checks - step.completionChecks.toSet() else null
                seq.finishDriverStep(DriverReport(LaneStepStatus.PASS, "Done, confirmed by the tester.$note", StepCompletion.TESTER, verified))
            }
            ManualDecision.FAILED -> seq.finishDriverStep(
                DriverReport(LaneStepStatus.FAIL, "The tester reported that the step failed.$note", StepCompletion.TESTER, checks = emptyList()),
            )
            ManualDecision.SKIP ->
                seq.skipDriverStep(DriverReport(LaneStepStatus.PASS, "Skipped by the tester.$note", StepCompletion.SKIPPED))
        }
    }

    private fun watchedHint(watched: List<StepCheck>): String {
        if (watched.isEmpty()) return ""
        val described = watched.joinToString("; ") {
            when (it) {
                is StepCheck.DeviceState -> "${it.key} matches /${it.expectedRegex}/"
                is StepCheck.LogAppears -> "a log line /${it.regex}/ appears"
                else -> ""
            }
        }
        return "Done automatically when: $described".take(CHECKS_HINT_CHARS)
    }
}

private const val NANOS_PER_MILLI = 1_000_000L
private const val MILLIS_PER_SECOND = 1_000L
