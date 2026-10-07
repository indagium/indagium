package com.indagium.testing.run

import com.indagium.testing.model.HookItem
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.StepResult
import com.indagium.testing.model.StepStatus
import com.indagium.testing.model.TestRun
import com.indagium.testing.script.AdbScriptTarget
import com.indagium.testing.script.ScriptRunContext
import com.indagium.testing.script.ScriptRunOutcome

// Runs the setup and teardown hooks of a suite or a case. A SCRIPT hook runs directly (no agent, no confirmation: the
// suite's author wrote it and whoever started the run accepted it). A SHARED hook runs the shared step's steps as a
// StepSequence flagged `setup`, driven like any other sequence (the agent, or an external client). Every hook leaves a
// step result flagged `setup`; the answer is whether every hook passed.

private const val OUTPUT_EXCERPT_CHARS = 400

internal class HookRunner(
    private val snapshot: TestRun,
    private val env: SequenceEnv,
    private val driver: LaneDriver,
    private val record: (StepResult) -> Unit,
) {
    /** Runs [hooks] in order; with [stopOnFailure] the first failing hook ends the list. [scriptsOnly] skips shared hooks (a cancelled run). */
    @Suppress("LongParameterList")
    suspend fun run(
        hooks: List<HookItem>,
        caseId: String,
        iteration: Int,
        evidencePrefix: String,
        budget: CaseBudget,
        stopOnFailure: Boolean,
        scriptsOnly: Boolean = false,
    ): Boolean {
        var allPassed = true
        for ((position, hook) in hooks.withIndex()) {
            val passed = when (hook) {
                is HookItem.Script -> runScript(hook, position + 1, caseId)
                is HookItem.Shared ->
                    if (scriptsOnly) {
                        skipShared(hook, position + 1)
                    } else {
                        runShared(hook, position + 1, caseId, iteration, "$evidencePrefix-h${position + 1}", budget)
                    }
            }
            if (!passed) {
                allPassed = false
                if (stopOnFailure) break
            }
        }
        return allPassed
    }

    private fun result(hookId: String, number: Int, action: String, status: StepStatus, observation: String, startedAt: Long): StepResult =
        StepResult(
            stepId = hookId, stepNumber = number, action = action, setup = true, status = status, attempts = 1,
            observation = observation, startedAt = startedAt, durationMs = env.wallClock() - startedAt,
        )

    private suspend fun runScript(hook: HookItem.Script, number: Int, caseId: String): Boolean {
        val startedAt = env.wallClock()
        val script = snapshot.scripts.firstOrNull { it.id == hook.scriptId }
        if (script == null) {
            record(result(hook.id, number, "Run script ${hook.scriptId}", StepStatus.FAIL, "The script is not in the library.", startedAt))
            return false
        }
        val action = "Run script ${script.toolName}"
        val adb: AdbScriptTarget? = if (script.target == ScriptTarget.ADB_SHELL) env.session.adbTarget() else null
        val context = ScriptRunContext(env.session.serial, env.packageName, env.laneDir, caseId, hook.id)
        return when (val outcome = env.scriptRunner.run(script, hook.args, context, adb)) {
            is ScriptRunOutcome.Rejected -> {
                record(result(hook.id, number, action, StepStatus.FAIL, outcome.message, startedAt))
                false
            }
            is ScriptRunOutcome.Finished -> {
                val finished = outcome.result
                val passed = finished.exitCode == 0 && !finished.timedOut
                val timedOut = if (finished.timedOut) " (timed out)" else ""
                val summary = "exit code ${finished.exitCode}$timedOut: ${finished.stdout.trim().take(OUTPUT_EXCERPT_CHARS)}"
                record(result(hook.id, number, action, if (passed) StepStatus.PASS else StepStatus.FAIL, summary, startedAt))
                passed
            }
        }
    }

    private fun skipShared(hook: HookItem.Shared, number: Int): Boolean {
        record(result(hook.id, number, "Run shared step ${hook.sharedStepId}", StepStatus.SKIPPED, "Skipped because the run was cancelled.", env.wallClock()))
        return true
    }

    @Suppress("LongParameterList")
    private suspend fun runShared(hook: HookItem.Shared, number: Int, caseId: String, iteration: Int, prefix: String, budget: CaseBudget): Boolean {
        val shared = snapshot.sharedSteps.firstOrNull { it.id == hook.sharedStepId }
        if (shared == null) {
            record(result(hook.id, number, "Run shared step ${hook.sharedStepId}", StepStatus.FAIL, "The shared step is not in the library.", env.wallClock()))
            return false
        }
        if (shared.steps.isEmpty()) return true
        val sequence = StepSequence(
            env,
            SequenceSpec(
                caseId,
                shared.name,
                shared.steps,
                setup = true,
                allowedTools = null,
                evidencePrefix = prefix,
                caseBudget = budget,
                iteration = iteration,
            ),
        )
        driver.drive(sequence, null, iteration, budget)
        val results = sequence.results()
        return results.size == shared.steps.size && results.all { it.status == StepStatus.PASS }
    }
}
