@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.summaryLabel
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
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 760.dp) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CaseNavigationRail(suite, case, limits, Modifier.width(138.dp))
                    Column(Modifier.weight(1f)) {
                        CaseEditorContents(suite, case, access.editable) { confirmDelete = true }
                    }
                }
            } else {
                Column(Modifier.fillMaxWidth()) {
                    CaseNavigationCompact(suite, case, limits)
                    CaseEditorContents(suite, case, access.editable) { confirmDelete = true }
                }
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

@Composable
private fun CaseEditorContents(suite: TestSuite, case: TestCase, editable: Boolean, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    AppButton("‹ ${suite.name.ifBlank { "Suite" }}", onClick = { ui.view.selectedCaseId = null }, variant = ButtonVariant.Ghost)
    Spacer(Modifier.height(6.dp))
    if (!editable) {
        TestsLockedNotice(caseEditAccess(ui.library, suite, case.id, LocalTestsLimits.current).reason.orEmpty())
        Spacer(Modifier.height(10.dp))
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 700.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    CaseMetadata(case, editable, onDelete)
                    case.creationUsage?.let { usage ->
                        Spacer(Modifier.height(4.dp))
                        AppText("AI step creation · ${usage.summaryLabel()}", color = tc().td, fontSize = 9.sp)
                    }
                    CaseDetails(case, editable)
                    CaseAuthoringActions(suite.id, case, editable)
                    StepListSection(caseStepOps(ui, suite, case, editable))
                }
                Column(Modifier.width(255.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CaseContextPanel(case, editable)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                CaseMetadata(case, editable, onDelete)
                case.creationUsage?.let { usage ->
                    Spacer(Modifier.height(4.dp))
                    AppText("AI step creation · ${usage.summaryLabel()}", color = tc().td, fontSize = 9.sp)
                }
                CaseDetails(case, editable)
                CaseContextPanel(case, editable)
                CaseAuthoringActions(suite.id, case, editable)
                StepListSection(caseStepOps(ui, suite, case, editable))
            }
        }
    }
}

@Composable
private fun CaseMetadata(case: TestCase, editable: Boolean, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    TestsLabeled("Case name") {
        CommitTextField(
            case.name, { t -> ui.state.updateTestCase(case.id) { it.copy(name = t.trim()) } },
            enabled = editable, placeholder = "Case name",
        )
    }
    Spacer(Modifier.height(10.dp))
    CaseActions(case, onDelete = onDelete)
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CaseNavigationRail(suite: TestSuite, selected: TestCase, limits: TestsLimitsUiState, modifier: Modifier) {
    val ui = LocalTestsUi.current
    val tc = tc()
    Column(modifier.background(tc.p2, CORNER_MD).padding(8.dp)) {
        AppText("CASES", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(5.dp))
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 700.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            items(suite.cases, key = { it.id }) { item ->
                Row(
                    Modifier.fillMaxWidth().background(if (item.id == selected.id) tc.ac.copy(alpha = .16f) else Color.Transparent, CORNER_SM)
                        .clickable { ui.view.selectedCaseId = item.id }.padding(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    AppText("${suite.cases.indexOf(item) + 1}", color = tc.td, fontSize = 9.sp, fontFamily = MONO)
                    AppText(item.name.ifBlank { "Untitled case" }, color = if (item.id == selected.id) tc.ac else tc.tx, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    if (limits.isCaseLocked(item.id)) LockBadge(limits.hint)
                }
            }
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CaseNavigationCompact(suite: TestSuite, selected: TestCase, limits: TestsLimitsUiState) {
    val ui = LocalTestsUi.current
    val tc = tc()
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        AppText("CASES IN ${suite.name.uppercase()}", color = tc.td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            items(suite.cases, key = { it.id }) { item ->
                Row(
                    Modifier.background(if (item.id == selected.id) tc.ac.copy(alpha = .16f) else tc.p2, CORNER_SM)
                        .clickable { ui.view.selectedCaseId = item.id }.padding(horizontal = 9.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    AppText(item.name.ifBlank { "Untitled case" }, color = if (item.id == selected.id) tc.ac else tc.tx, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (limits.isCaseLocked(item.id)) LockBadge(limits.hint)
                }
            }
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun CaseContextPanel(case: TestCase, editable: Boolean) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val scriptNames = ui.library.scripts.filter { script -> case.allowedTools == null || script.toolName in case.allowedTools }
    val examples = case.steps.flatMap { step -> step.examples.map { step to it } }
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(10.dp)) {
        TestsSectionTitle("Tools & examples")
        AllowedToolsEditor(case.allowedTools, editable) { tools -> ui.report(ui.state.updateTestCase(case.id) { it.copy(allowedTools = tools) }) }
        Spacer(Modifier.height(6.dp))
        AppText(
            if (case.allowedTools == null) "This case may use the default device tools and permitted library scripts."
            else "Allowed lane tools: ${case.allowedTools.sorted().joinToString().ifBlank { "none" }}",
            color = tc.ts, fontSize = 10.sp,
        )
        if (scriptNames.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            AppText("Available scripts: ${scriptNames.joinToString { it.toolName }}", color = tc.td, fontSize = 9.sp)
        }
        Spacer(Modifier.height(4.dp))
        if (examples.isEmpty()) {
            TestsHint("No examples are attached to this case yet.")
        } else {
            examples.forEach { (step, example) ->
                Row(
                    Modifier.fillMaxWidth().clickable { ui.view.expandedStepId = step.id }.padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    AppText(if (example is com.indagium.testing.model.StepExample.GoldenScreenshot) "Screenshot" else "Reference log", color = tc.ac, fontSize = 9.sp)
                    AppText(example.caption.ifBlank { step.action }, color = tc.tx, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    AppText("Step ${case.steps.indexOfFirst { it.id == step.id } + 1}", color = tc.td, fontSize = 9.sp)
                }
            }
        }
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
