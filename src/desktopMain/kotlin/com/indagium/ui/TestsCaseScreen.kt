@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.summaryLabel
import com.indagium.testing.model.StepExample
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult

// The case editor, steps first: breadcrumb, title with Run and ⋯, the goal, summary chips (Preconditions, Instructions, Hooks, Tools,
// Examples; each opens its editor in the inspector) and the step list (the selected step is edited in the inspector). A locked case
// (past the edition's per-suite limit) renders read-only with the lock notice; it can still be deleted from the case's ⋯ menu.

private val CASE_COLUMN_GAP = 12.dp
private val GOAL_LABEL_WIDTH = 34.dp
private val GOAL_MIN_HEIGHT = 30.dp
private val GOAL_MAX_HEIGHT = 96.dp

@Composable
internal fun TestsCaseScreen(suiteId: String, caseId: String) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val library = ui.library
    val suite = library.suite(suiteId) ?: return
    val case = suite.cases.firstOrNull { it.id == caseId } ?: return
    val access = caseEditAccess(library, suite, case.id, limits)
    val ops = caseStepOps(ui, suite, case, access.editable)
    var confirmDelete by remember { mutableStateOf(false) }
    // A chip's editor belongs to the case it was opened on: another case always opens on its steps.
    LaunchedEffect(case.id) { ui.view.caseInspectorField = null }
    TestsScreenScaffold(
        inspector = {
            val field = ui.view.caseInspectorField
            if (field != null) CaseFieldInspector(case, field, access.editable) else SelectedStepInspector(ops)
        },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(CASE_COLUMN_GAP)) {
            CaseHeader(suite, case, access.editable) { confirmDelete = true }
            if (!access.editable) TestsLockedNotice(access.reason.orEmpty())
            CaseGoal(case, access.editable)
            CaseSummaryChips(case)
            Column(Modifier.fillMaxWidth()) {
                StepListSection(ops, header = { CaseStepsHeader(suite.id, case, access.editable) { addBlankStep(ui, ops) } })
            }
        }
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

/** Breadcrumb (the suite, a link back to it), the case name, ▶ Run case and the ⋯ menu (Duplicate, Delete…). */
@Composable
private fun CaseHeader(suite: TestSuite, case: TestCase, editable: Boolean, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val tc = tc()
    val writable = !ui.library.readOnly
    TestsPageHeader(
        title = case.name,
        onTitleCommit = { t -> ui.state.updateTestCase(case.id) { it.copy(name = t.trim()) } },
        titleEditable = editable,
        titleDescription = "Case name",
        titlePlaceholder = "Case name",
        breadcrumb = {
            val suiteName = suite.name.ifBlank { "Suite" }
            TestsBreadcrumbLink(suiteName, onClick = { ui.view.selectedCaseId = null }, contentDescription = "Back to suite $suiteName")
            AppText("›", color = tc.td, fontSize = 11.sp)
        },
        primary = {
            HintedButton(
                "▶ Run case",
                onClick = { ui.view.runDialog = RunDialogTarget(suite.id, case.id) },
                enabled = !limits.isCaseLocked(case.id) && !limits.isSuiteLocked(suite.id),
                disabledHint = limits.hint,
                variant = ButtonVariant.Primary,
            )
        },
        overflowDescription = "More case actions",
        overflowItems = listOf(
            TestsMenuItem(
                "Duplicate",
                onClick = {
                    val result = ui.report(ui.state.duplicateTestCase(case.id))
                    if (result is StoreResult.Ok) ui.view.selectedCaseId = result.value.id
                },
                enabled = writable && limits.canDuplicateCase(case.id),
                disabledHint = if (!writable) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
            ),
            TestsMenuItem(
                "Delete…", onClick = onDelete, danger = true,
                enabled = writable, disabledHint = LIBRARY_READ_ONLY_MESSAGE, dividerBefore = true,
            ),
        ),
    )
}

/**
 * "Goal" and its field. The goal can be several lines (it seeds the AI step drafts), so the field is a compact multi-line one
 * that starts one line tall and grows with its text, rather than a single-line field that would hide the rest.
 */
@Composable
private fun CaseGoal(case: TestCase, editable: Boolean) {
    val ui = LocalTestsUi.current
    val tc = tc()
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            AppText("Goal", color = tc.td, fontSize = 11.sp, modifier = Modifier.width(GOAL_LABEL_WIDTH).padding(top = 6.dp))
            Column(Modifier.weight(1f)) {
                CommitTextField(
                    case.description, { t -> ui.state.updateTestCase(case.id) { it.copy(description = t) } },
                    enabled = editable, multiline = true, minHeight = GOAL_MIN_HEIGHT, maxHeight = GOAL_MAX_HEIGHT,
                    placeholder = "What should this case prove?",
                )
            }
        }
        case.creationUsage?.let { usage ->
            AppText(
                "AI step creation · ${usage.summaryLabel()}", color = tc.td, fontSize = 10.sp,
                modifier = Modifier.padding(start = GOAL_LABEL_WIDTH + 6.dp, top = 4.dp),
            )
        }
    }
}

/** One chip per case-level field; clicking toggles that field's editor in the inspector. */
@Composable
private fun CaseSummaryChips(case: TestCase) {
    val ui = LocalTestsUi.current
    val view = ui.view
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CaseField.entries.forEach { field ->
            val chip = caseChipValue(case, field)
            TestsSummaryChip(
                label = field.label,
                value = chip.text,
                empty = chip.empty,
                selected = view.caseInspectorField == field,
                onClick = {
                    view.caseInspectorField = if (view.caseInspectorField == field) null else field
                    ui.reclaimFocus()
                },
            )
        }
    }
}

// ── Inspector: case-level fields ─────────────────────────────────────

/** The editor of [field], shown in the inspector in place of the step editor until Done, Esc or a step is selected. */
@Composable
private fun CaseFieldInspector(case: TestCase, field: CaseField, editable: Boolean) {
    val ui = LocalTestsUi.current
    val update: ((TestCase) -> TestCase) -> StoreResult<*> = { transform -> ui.state.updateTestCase(case.id, transform) }
    // Keyed by field so a draft typed into one editor can never be committed to another.
    key(case.id, field) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                TestsInspectorHeader("Case · ${case.name.ifBlank { "Untitled case" }}", field.inspectorTitle, Modifier.weight(1f))
                AppButton("Done", onClick = {
                    ui.view.caseInspectorField = null
                    ui.reclaimFocus()
                }, variant = ButtonVariant.Ghost)
            }
            when (field) {
                CaseField.Preconditions -> TestsLabeled("True before the first step") {
                    CommitTextField(
                        case.preconditions, { t -> update { it.copy(preconditions = t) } },
                        enabled = editable, multiline = true, placeholder = "State the app must be in before step 1",
                    )
                }
                CaseField.Instructions -> TestsLabeled("Hints that apply to every step") {
                    CommitTextField(
                        case.instructions, { t -> update { it.copy(instructions = t) } },
                        enabled = editable, multiline = true, placeholder = "For example: prefer what is on screen over recorded coordinates.",
                    )
                }
                CaseField.Hooks -> {
                    HooksEditor("Setup hooks", case.setup, editable) { hooks -> update { it.copy(setup = hooks) } }
                    HooksEditor("Teardown hooks", case.teardown, editable) { hooks -> update { it.copy(teardown = hooks) } }
                    TestsHint("Hooks run a shared step or script before the first step and after the last one.")
                }
                CaseField.Tools -> CaseToolsEditor(case, editable)
                CaseField.Examples -> CaseExamplesList(case)
            }
        }
    }
}

@Composable
private fun CaseToolsEditor(case: TestCase, editable: Boolean) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val scriptNames = ui.library.scripts.filter { script -> case.allowedTools == null || script.toolName in case.allowedTools }
    AllowedToolsEditor(case.allowedTools, editable) { tools -> ui.report(ui.state.updateTestCase(case.id) { it.copy(allowedTools = tools) }) }
    AppText(
        if (case.allowedTools == null) {
            "This case may use the default device tools and permitted library scripts."
        } else {
            "Allowed lane tools: ${case.allowedTools.sorted().joinToString().ifBlank { "none" }}"
        },
        color = tc.ts, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
    )
    if (scriptNames.isNotEmpty()) {
        AppText(
            "Available scripts: ${scriptNames.joinToString { it.toolName }}",
            color = tc.td, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Every example attached to a step of [case]; picking one selects its step (with the Examples section open). */
@Composable
private fun CaseExamplesList(case: TestCase) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val examples = case.steps.flatMap { step -> step.examples.map { step to it } }
    if (examples.isEmpty()) {
        TestsHint("No examples are attached to this case yet. Attach one from a step's Examples section.")
    } else {
        Column(Modifier.fillMaxWidth()) {
            examples.forEach { (step, example) ->
                TestsListRow(
                    selected = false,
                    onClick = {
                        ui.reclaimFocus()
                        ui.view.selectStep(step.id)
                        ui.view.revealStepId = step.id
                        ui.view.stepExamplesExpanded = true
                    },
                ) {
                    AppText(if (example is StepExample.GoldenScreenshot) "Screenshot" else "Reference log", color = tc.ac, fontSize = 10.sp)
                    AppText(
                        example.caption.ifBlank { step.action }, color = tc.tx, fontSize = 10.sp,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                    )
                    AppText("Step ${case.steps.indexOfFirst { it.id == step.id } + 1}", color = tc.td, fontSize = 10.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(2.dp))
    AppButton("See all examples for agents", onClick = { ui.view.nav = TestsNav.AgentExamples })
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
