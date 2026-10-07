package com.indagium.testing.run

import com.indagium.testing.model.HookItem
import com.indagium.testing.model.RunStatus
import com.indagium.testing.model.RunSummary
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.StepCheck
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestRun
import com.indagium.testing.model.TestScript

enum class ComparedStepPresence { ADDED, REMOVED, BOTH }

enum class ComparedCasePresence { ADDED, REMOVED, BOTH }

data class ComparedRunStep(
    val caseId: String,
    val caseName: String,
    val stepId: String,
    val presence: ComparedStepPresence,
    val definitionChanged: Boolean,
    val previousAction: String?,
    val currentAction: String?,
    val previousExpected: String?,
    val currentExpected: String?,
    val previousStatuses: String,
    val currentStatuses: String,
    val casePresence: ComparedCasePresence = ComparedCasePresence.BOTH,
    val caseDefinitionChanged: Boolean = false,
    val suiteDefinitionChanged: Boolean = false,
)

/** Compares frozen definitions and terminal statuses by stable case/step ids, independent of lane ids. */
internal fun compareTestRuns(previous: TestRun, current: TestRun): List<ComparedRunStep> {
    val previousCases = previous.suite.cases.associateBy { it.id }
    val currentCases = current.suite.cases.associateBy { it.id }
    val caseIds = (previousCases.keys + currentCases.keys).distinct()
    val suiteRow = suiteContextRow(previous, current)
    return suiteRow + caseIds.flatMap { caseId -> compareCase(previous, current, caseId, previousCases[caseId], currentCases[caseId]) }
}

private fun suiteContextRow(previous: TestRun, current: TestRun): List<ComparedRunStep> {
    val changed = previous.suite.contextSignature(previous.sharedSteps, previous.scripts) !=
        current.suite.contextSignature(current.sharedSteps, current.scripts)
    if (!changed) return emptyList()
    return listOf(
        ComparedRunStep(
            caseId = "",
            caseName = "Suite context",
            stepId = "",
            presence = ComparedStepPresence.BOTH,
            definitionChanged = true,
            previousAction = previous.suite.contextSummary(previous.sharedSteps, previous.scripts),
            currentAction = current.suite.contextSummary(current.sharedSteps, current.scripts),
            previousExpected = null,
            currentExpected = null,
            previousStatuses = "Not run",
            currentStatuses = "Not run",
            suiteDefinitionChanged = true,
        ),
    )
}

private fun compareCase(
    previous: TestRun,
    current: TestRun,
    caseId: String,
    oldCase: TestCase?,
    newCase: TestCase?,
): List<ComparedRunStep> {
    val presence = casePresence(oldCase, newCase)
    val contextChanged = oldCase != null && newCase != null &&
        oldCase.contextSignature(previous.sharedSteps, previous.scripts) != newCase.contextSignature(current.sharedSteps, current.scripts)
    val oldStatuses = caseStatuses(previous, caseId)
    val newStatuses = caseStatuses(current, caseId)
    val stepRows = compareCaseSteps(previous, current, caseId, oldCase, newCase, presence)
    if (presence == ComparedCasePresence.BOTH && !contextChanged && oldStatuses == newStatuses) return stepRows
    return listOf(
        ComparedRunStep(
            caseId = caseId,
            caseName = newCase?.name ?: oldCase?.name.orEmpty(),
            stepId = "",
            presence = ComparedStepPresence.BOTH,
            definitionChanged = contextChanged,
            previousAction = oldCase?.contextSummary(previous.sharedSteps, previous.scripts),
            currentAction = newCase?.contextSummary(current.sharedSteps, current.scripts),
            previousExpected = null,
            currentExpected = null,
            previousStatuses = oldStatuses,
            currentStatuses = newStatuses,
            casePresence = presence,
            caseDefinitionChanged = contextChanged,
        ),
    ) + stepRows
}

private fun compareCaseSteps(
    previous: TestRun,
    current: TestRun,
    caseId: String,
    oldCase: TestCase?,
    newCase: TestCase?,
    casePresence: ComparedCasePresence,
): List<ComparedRunStep> {
    val oldSteps = oldCase?.steps.orEmpty().associateBy { it.id }
    val newSteps = newCase?.steps.orEmpty().associateBy { it.id }
    return (oldSteps.keys + newSteps.keys).distinct().mapNotNull { stepId ->
        val oldStep = oldSteps[stepId]
        val newStep = newSteps[stepId]
        val presence = stepPresence(oldStep, newStep)
        val oldStatuses = stepStatuses(previous, caseId, stepId)
        val newStatuses = stepStatuses(current, caseId, stepId)
        val scriptChanged = oldStep != null && newStep != null &&
            oldStep.referencedScriptSignatures(previous.scripts) != newStep.referencedScriptSignatures(current.scripts)
        if (presence == ComparedStepPresence.BOTH && oldStep == newStep && !scriptChanged && oldStatuses == newStatuses) return@mapNotNull null
        ComparedRunStep(
            caseId = caseId,
            caseName = newCase?.name ?: oldCase?.name.orEmpty(),
            stepId = stepId,
            presence = presence,
            definitionChanged = oldStep != null && newStep != null && (oldStep != newStep || scriptChanged),
            previousAction = oldStep?.action,
            currentAction = newStep?.action,
            previousExpected = oldStep?.expected,
            currentExpected = newStep?.expected,
            previousStatuses = oldStatuses,
            currentStatuses = newStatuses,
            casePresence = casePresence,
        )
    }
}

private fun casePresence(oldCase: TestCase?, newCase: TestCase?): ComparedCasePresence = when {
    oldCase == null -> ComparedCasePresence.ADDED
    newCase == null -> ComparedCasePresence.REMOVED
    else -> ComparedCasePresence.BOTH
}

private fun stepPresence(oldStep: com.indagium.testing.model.TestStep?, newStep: com.indagium.testing.model.TestStep?): ComparedStepPresence = when {
    oldStep == null -> ComparedStepPresence.ADDED
    newStep == null -> ComparedStepPresence.REMOVED
    else -> ComparedStepPresence.BOTH
}

/** Selects the immediately prior completed run of the same stable suite ID, even if names were edited. */
internal fun previousTerminalRunOfSameSuite(records: List<TestRun>, current: TestRun): TestRun? = records.asSequence()
    .filter { it.id != current.id && it.suite.id == current.suite.id && it.isFinished && it.createdAt < current.createdAt }
    .maxByOrNull { it.createdAt }

/** The summaries passed here must already be filtered to the stable suite id. */
internal fun previousTerminalRunSummaryOfSameSuite(summaries: List<RunSummary>, current: TestRun): RunSummary? = summaries.asSequence()
    .filter { it.suiteId == current.suite.id && it.id != current.id && it.status !in setOf(RunStatus.QUEUED, RunStatus.RUNNING) }
    .filter { it.createdAt < current.createdAt || (it.createdAt == current.createdAt && it.id < current.id) }
    .maxWithOrNull(compareBy<RunSummary> { it.createdAt }.thenBy { it.id })

private fun stepStatuses(run: TestRun, caseId: String, stepId: String): String = run.lanes.flatMap { lane ->
    lane.cases.filter { it.caseId == caseId }.flatMap { case ->
        case.steps.filter { it.stepId == stepId && !it.setup }.map {
            "${lane.config.kind.name}/${lane.config.profileId.orEmpty()}/${lane.config.deviceSerial}/i${case.iteration}:${it.status.name}"
        }
    }
}.sorted().joinToString(", ").ifBlank { "Not run" }

private fun caseStatuses(run: TestRun, caseId: String): String = run.lanes.flatMap { lane ->
    lane.cases.filter { it.caseId == caseId }.map { case ->
        val laneKey = "${lane.config.kind.name}/${lane.config.profileId.orEmpty()}/${lane.config.deviceSerial}/i${case.iteration}"
        "$laneKey:${case.status?.name ?: "UNKNOWN"}"
    }
}.sorted().joinToString(", ").ifBlank { "Not run" }

private fun TestCase.contextSignature(sharedSteps: List<SharedStep>, scripts: List<TestScript>): List<Any?> = listOf(
    name, description, preconditions, instructions, allowedTools,
    hooksSignature(setup, sharedSteps, scripts),
    hooksSignature(teardown, sharedSteps, scripts),
)

private fun com.indagium.testing.model.TestSuite.contextSignature(sharedSteps: List<SharedStep>, scripts: List<TestScript>): List<Any?> = listOf(
    name,
    description,
    instructions,
    targetPackage,
    deviceProfileHint,
    tags,
    setup,
    teardown,
    variables,
    hooksSignature(setup + teardown, sharedSteps, scripts),
)

private fun com.indagium.testing.model.TestSuite.contextSummary(sharedSteps: List<SharedStep>, scripts: List<TestScript>): String = buildString {
    append("Suite: ").append(name)
    if (description.isNotBlank()) append("\nDescription: ").append(description)
    if (instructions.isNotBlank()) append("\nInstructions: ").append(instructions)
    if (targetPackage.isNotBlank()) append("\nTarget package: ").append(targetPackage)
    if (deviceProfileHint.isNotBlank()) append("\nDevice profile: ").append(deviceProfileHint)
    if (variables.isNotEmpty()) append("\nVariables: ").append(variables.joinToString { "${it.name}=${it.value}" })
    if (setup.isNotEmpty()) append("\nSetup hooks: ").append(hooksSummary(setup, sharedSteps, scripts))
    if (teardown.isNotEmpty()) append("\nTeardown hooks: ").append(hooksSummary(teardown, sharedSteps, scripts))
}

private fun hooksSignature(hooks: List<HookItem>, sharedSteps: List<SharedStep>, scripts: List<TestScript>): List<Any?> = hooks.map { hook ->
    when (hook) {
        is HookItem.Script -> hook to scripts.firstOrNull { it.id == hook.scriptId }
        is HookItem.Shared -> hook.sharedStepId to sharedSteps.firstOrNull { it.id == hook.sharedStepId }?.let { shared ->
            shared to shared.steps.flatMap { it.referencedScriptSignatures(scripts) }
        }
    }
}

private fun hooksSummary(hooks: List<HookItem>, sharedSteps: List<SharedStep>, scripts: List<TestScript>): String = hooks.joinToString { hook ->
    when (hook) {
        is HookItem.Script -> scripts.firstOrNull { it.id == hook.scriptId }?.let { script ->
            "script ${script.toolName}: ${script.description} (${script.target.name}, ${script.permission.name})"
        } ?: "missing script ${hook.scriptId}"
        is HookItem.Shared -> sharedSteps.firstOrNull { it.id == hook.sharedStepId }?.let { shared ->
            buildString {
                append(shared.name)
                if (shared.description.isNotBlank()) append(": ").append(shared.description)
                shared.steps.forEachIndexed { index, step -> append("\n  ").append(index + 1).append(". ").append(step.action)
                    if (step.expected.isNotBlank()) append(" → ").append(step.expected)
                }
            }
        } ?: "missing shared step ${hook.sharedStepId}"
    }
}

private fun TestCase.contextSummary(sharedSteps: List<SharedStep>, scripts: List<TestScript>): String = buildString {
    append("Case: ").append(name)
    if (description.isNotBlank()) append("\nGoal: ").append(description)
    if (preconditions.isNotBlank()) append("\nPreconditions: ").append(preconditions)
    if (instructions.isNotBlank()) append("\nInstructions: ").append(instructions)
    allowedTools?.let { append("\nAllowed tools: ").append(it.sorted().joinToString()) }
    if (setup.isNotEmpty()) append("\nSetup hooks: ").append(hooksSummary(setup, sharedSteps, scripts))
    if (teardown.isNotEmpty()) append("\nTeardown hooks: ").append(hooksSummary(teardown, sharedSteps, scripts))
}

private fun com.indagium.testing.model.TestStep.referencedScriptSignatures(scripts: List<TestScript>): List<Pair<String, TestScript?>> =
    checks.filterIsInstance<StepCheck.ScriptResult>().map { it.scriptId to scripts.firstOrNull { script -> script.id == it.scriptId } }
