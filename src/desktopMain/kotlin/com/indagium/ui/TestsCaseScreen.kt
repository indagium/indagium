@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult

// The case editor: goal, preconditions, instructions, hooks, allowed tools and the step list. A locked case (past the
// edition's per-suite limit) renders read-only with the lock notice; it can still be duplicated-out of the way
// (deleted) from the suite table.

@Composable
internal fun TestsCaseScreen(suiteId: String, caseId: String) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val library = ui.library
    val suite = library.suite(suiteId) ?: return
    val case = suite.cases.firstOrNull { it.id == caseId } ?: return
    val access = caseEditAccess(library, suite, case.id, limits)
    var confirmDelete by remember { mutableStateOf(false) }
    TestsScreenScaffold {
        AppButton("‹ ${suite.name.ifBlank { "Suite" }}", onClick = { ui.view.selectedCaseId = null }, variant = ButtonVariant.Ghost)
        Spacer(Modifier.height(6.dp))
        access.reason?.let {
            TestsLockedNotice(it)
            Spacer(Modifier.height(10.dp))
        }
        TestsLabeled("Case name") {
            CommitTextField(
                case.name, { t -> ui.state.updateTestCase(case.id) { it.copy(name = t.trim()) } },
                enabled = access.editable, placeholder = "Case name",
            )
        }
        Spacer(Modifier.height(10.dp))
        CaseActions(case, onDelete = { confirmDelete = true })
        CaseDetails(case, access.editable)
        StepListSection(caseStepOps(ui, suite, case, access.editable))
    }
    if (confirmDelete) {
        TestsConfirmDialog(
            title = "Delete case?",
            message = "\"${case.name}\" and its ${case.steps.size} step(s) will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                if (ui.report(ui.state.deleteTestCase(case.id)) is StoreResult.Ok) ui.view.selectedCaseId = null
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun suiteIdOf(ui: TestsUi, caseId: String): String = ui.library.findCase(caseId)?.suite?.id.orEmpty()

@Composable
private fun CaseActions(case: TestCase, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val writable = !ui.library.readOnly
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HintedButton(
            "Duplicate",
            onClick = {
                val result = ui.report(ui.state.duplicateTestCase(case.id))
                if (result is StoreResult.Ok) ui.view.selectedCaseId = result.value.id
            },
            enabled = writable && limits.canDuplicateCase(case.id),
            disabledHint = if (!writable) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
        )
        HintedButton("Delete", onClick = onDelete, enabled = writable, disabledHint = LIBRARY_READ_ONLY_MESSAGE, isDanger = true)
        HintedButton(
            "Run this case…",
            onClick = { ui.view.runDialog = RunDialogTarget(suiteIdOf(ui, case.id), case.id) },
            enabled = !limits.isCaseLocked(case.id) && !limits.isSuiteLocked(suiteIdOf(ui, case.id)),
            disabledHint = limits.hint,
            variant = ButtonVariant.Primary,
        )
    }
}

@Composable
private fun CaseDetails(case: TestCase, editable: Boolean) {
    val ui = LocalTestsUi.current
    val update: ((TestCase) -> TestCase) -> StoreResult<*> = { transform -> ui.state.updateTestCase(case.id, transform) }
    TestsSectionTitle("Details")
    TestsLabeled("Goal") {
        CommitTextField(
            case.description, { t -> update { it.copy(description = t) } },
            enabled = editable, multiline = true, placeholder = "What this case sets out to verify",
        )
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Preconditions") {
        CommitTextField(
            case.preconditions, { t -> update { it.copy(preconditions = t) } },
            enabled = editable, multiline = true, placeholder = "State the app must be in before step 1",
        )
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Instructions for the agent") {
        CommitTextField(
            case.instructions, { t -> update { it.copy(instructions = t) } },
            enabled = editable, multiline = true, placeholder = "Hints that apply to every step",
        )
    }
    HooksEditor("Setup hooks", case.setup, editable) { hooks -> update { it.copy(setup = hooks) } }
    HooksEditor("Teardown hooks", case.teardown, editable) { hooks -> update { it.copy(teardown = hooks) } }
    AllowedToolsEditor(case.allowedTools, editable) { tools -> update { it.copy(allowedTools = tools) } }
}

private fun caseStepOps(ui: TestsUi, suite: TestSuite, case: TestCase, editable: Boolean): StepListOps = StepListOps(
    steps = case.steps,
    editable = editable,
    assetSuiteId = suite.id,
    update = { stepId, transform -> ui.state.updateTestStep(stepId, transform) },
    add = { step -> ui.state.createTestStep(case.id, step) },
    delete = { stepId -> ui.state.deleteTestStep(stepId) },
    duplicate = { stepId -> ui.state.duplicateTestStep(stepId) },
    move = { stepId, to -> ui.state.moveTestStep(stepId, to) },
)
