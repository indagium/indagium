@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.indagium.testing.model.TestCase
import com.indagium.testing.model.TestSuite
import com.indagium.testing.store.StoreResult

// The suite screen: header fields, the action buttons and the case table. Every control is derived from the edition
// limits (LocalTestsLimits) and the suite's edit access; locked suites and cases stay readable, exportable,
// reorderable and deletable, which is exactly what the store allows.

private val CASE_ROW_HEIGHT = 34.dp
private val INDEX_COLUMN = 24.dp
private val STEPS_COLUMN = 56.dp
private val RESULT_COLUMN = 64.dp

@Composable
internal fun TestsSuiteScreen(suiteId: String) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val library = ui.library
    val suite = library.suite(suiteId) ?: return
    val access = suiteEditAccess(library, suite, limits)
    var confirmDelete by remember { mutableStateOf(false) }
    TestsScreenScaffold {
        access.reason?.let {
            TestsLockedNotice(it)
            Spacer(Modifier.height(10.dp))
        }
        SuiteTitleRow(suite, access)
        Spacer(Modifier.height(10.dp))
        SuiteActions(suite, access, onDelete = { confirmDelete = true })
        SuiteDetails(suite, access.editable)
        SuiteCasesTable(suite)
    }
    if (confirmDelete) {
        TestsConfirmDialog(
            title = "Delete suite?",
            message = "\"${suite.name}\" and its ${suite.cases.size} case(s) will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = { deleteSuiteAndSelectNext(ui, suite) },
            onDismiss = { confirmDelete = false },
        )
    }
}

private fun deleteSuiteAndSelectNext(ui: TestsUi, suite: TestSuite) {
    val next = ui.library.suites.firstOrNull { it.id != suite.id }?.id
    if (ui.report(ui.state.deleteTestSuite(suite.id)) is StoreResult.Ok) {
        ui.view.selectedSuiteId = next
        ui.view.selectedCaseId = null
    }
}

@Composable
private fun SuiteTitleRow(suite: TestSuite, access: TestsEditAccess) {
    val ui = LocalTestsUi.current
    TestsLabeled("Suite name") {
        CommitTextField(
            value = suite.name,
            onCommit = { text -> ui.state.updateTestSuite(suite.id) { it.copy(name = text.trim()) } },
            enabled = access.editable,
            placeholder = "Suite name",
        )
    }
}

@Composable
private fun SuiteActions(suite: TestSuite, access: TestsEditAccess, onDelete: () -> Unit) {
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val library = ui.library
    val writable = !library.readOnly && !suite.readOnly
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        HintedButton(
            "New case",
            onClick = { createCaseAndOpen(ui, suite) },
            enabled = writable && limits.canCreateCase(suite.id),
            disabledHint = if (!writable) access.reason else limits.hint,
            variant = ButtonVariant.Primary,
        )
        HintedButton(
            "Duplicate",
            onClick = { duplicateSuiteAndSelect(ui, suite) },
            enabled = !library.readOnly && limits.canDuplicateSuite(suite.id),
            disabledHint = if (library.readOnly) LIBRARY_READ_ONLY_MESSAGE else limits.hint,
        )
        HintedButton("Export…", onClick = { exportSuiteToChosenFile(ui, suite) })
        HintedButton("Import…", onClick = { importSuiteFromChosenFile(ui) }, enabled = !library.readOnly, disabledHint = LIBRARY_READ_ONLY_MESSAGE)
        HintedButton("Delete", onClick = onDelete, enabled = !library.readOnly, disabledHint = LIBRARY_READ_ONLY_MESSAGE, isDanger = true)
        val runnable = suite.cases.any { !limits.isCaseLocked(it.id) }
        HintedButton(
            "Run suite…",
            onClick = { ui.view.runDialog = RunDialogTarget(suite.id) },
            enabled = !limits.isSuiteLocked(suite.id) && runnable,
            disabledHint = if (limits.isSuiteLocked(suite.id)) limits.hint else "Add a case to run",
            variant = ButtonVariant.Primary,
        )
    }
}

private fun createCaseAndOpen(ui: TestsUi, suite: TestSuite) {
    val name = uniqueName("New case", suite.cases.map { it.name })
    val result = ui.report(ui.state.createTestCase(suite.id, TestCase("", name)))
    if (result is StoreResult.Ok) ui.view.selectedCaseId = result.value.id
}

private fun duplicateSuiteAndSelect(ui: TestsUi, suite: TestSuite) {
    val result = ui.report(ui.state.duplicateTestSuite(suite.id))
    if (result is StoreResult.Ok) {
        ui.view.selectedSuiteId = result.value.id
        ui.view.selectedCaseId = null
    }
}

private fun exportSuiteToChosenFile(ui: TestsUi, suite: TestSuite) {
    val file = pickSaveFile("Export test suite", suiteExportFileName(suite.name), null) ?: return
    ui.reclaimFocus()
    val result = ui.report(ui.state.exportTestSuiteToFile(suite.id, file))
    if (result is StoreResult.Ok) ui.info("Exported \"${suite.name}\" to ${file.name}.")
}

// ── Details ──────────────────────────────────────────────────────────

@Composable
private fun SuiteDetails(suite: TestSuite, editable: Boolean) {
    val ui = LocalTestsUi.current
    val update: ((TestSuite) -> TestSuite) -> StoreResult<*> = { transform -> ui.state.updateTestSuite(suite.id, transform) }
    TestsSectionTitle("Details")
    TestsLabeled("Description") {
        CommitTextField(
            suite.description, { t -> update { it.copy(description = t) } },
            enabled = editable, multiline = true, placeholder = "What this suite covers",
        )
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Instructions for the agent") {
        CommitTextField(
            suite.instructions, { t -> update { it.copy(instructions = t) } },
            enabled = editable, multiline = true, placeholder = "Context every case in this suite shares",
        )
    }
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TestsLabeled("Target package", Modifier.weight(1f)) {
            CommitTextField(
                suite.targetPackage, { t -> update { it.copy(targetPackage = t.trim()) } },
                enabled = editable, placeholder = "com.example.app", mono = true,
            )
        }
        TestsLabeled("Device hint", Modifier.weight(1f)) {
            CommitTextField(
                suite.deviceProfileHint, { t -> update { it.copy(deviceProfileHint = t) } },
                enabled = editable, placeholder = "e.g. Pixel 8, Android 15",
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Tags") { TagsEditor(suite.tags, editable) { tags -> update { it.copy(tags = tags) } } }
    HooksEditor("Setup hooks", suite.setup, editable) { hooks -> update { it.copy(setup = hooks) } }
    HooksEditor("Teardown hooks", suite.teardown, editable) { hooks -> update { it.copy(teardown = hooks) } }
    VariablesEditor(suite.variables, editable) { variables -> update { it.copy(variables = variables) } }
}

// ── Case table ───────────────────────────────────────────────────────

@Composable
private fun SuiteCasesTable(suite: TestSuite) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    var caseToDelete by remember { mutableStateOf<TestCase?>(null) }
    TestsSectionTitle("Cases (${suite.cases.size})")
    if (suite.cases.isEmpty()) {
        TestsHint("No cases yet. Use \"New case\" to add the first one.")
        return
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Spacer(Modifier.width(18.dp))
        TableHeader("#", Modifier.width(INDEX_COLUMN))
        TableHeader("NAME", Modifier.weight(1f))
        TableHeader("STEPS", Modifier.width(STEPS_COLUMN))
        TableHeader("RESULT", Modifier.width(RESULT_COLUMN))
        Spacer(Modifier.width(CASE_ACTIONS_WIDTH))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
    ReorderableColumn(
        items = suite.cases,
        idOf = { it.id },
        onMove = { id, to -> ui.report(ui.state.moveTestCase(id, to)) },
        fixedRowHeight = CASE_ROW_HEIGHT,
        reorderEnabled = !ui.library.readOnly && !suite.readOnly,
    ) { case, row ->
        CaseRow(case, row, locked = limits.isCaseLocked(case.id), onDelete = { caseToDelete = case })
    }
    caseToDelete?.let { case ->
        TestsConfirmDialog(
            title = "Delete case?",
            message = "\"${case.name}\" and its ${case.steps.size} step(s) will be deleted. This cannot be undone.",
            confirmLabel = "Delete",
            onConfirm = {
                if (ui.report(ui.state.deleteTestCase(case.id)) is StoreResult.Ok && ui.view.selectedCaseId == case.id) ui.view.selectedCaseId = null
            },
            onDismiss = { caseToDelete = null },
        )
    }
}

private val CASE_ACTIONS_WIDTH = 18.dp * 4 + 12.dp

@Composable
private fun TableHeader(text: String, modifier: Modifier) {
    AppText(text, color = tc().td, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = modifier.padding(vertical = 4.dp))
}

@Composable
private fun CaseRow(case: TestCase, row: ReorderRowScope, locked: Boolean, onDelete: () -> Unit) {
    val tc = tc()
    val ui = LocalTestsUi.current
    val limits = LocalTestsLimits.current
    val canDuplicate = !ui.library.readOnly && limits.canDuplicateCase(case.id)
    Row(
        Modifier.fillMaxSize().padding(horizontal = 4.dp).background(if (row.index % 2 == 1) tc.hv else Color.Transparent),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReorderGrip(row)
        AppText("${row.index + 1}", color = tc.td, fontSize = 10.sp, fontFamily = MONO, modifier = Modifier.width(INDEX_COLUMN))
        Row(
            Modifier.weight(1f).fillMaxSize().clickable { ui.view.selectedCaseId = case.id },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AppText(
                case.name.ifBlank { "Untitled case" },
                color = tc.tx, fontSize = 12.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
            )
            if (locked) LockBadge(limits.hint)
        }
        AppText("${case.steps.size}", color = tc.ts, fontSize = 11.sp, modifier = Modifier.width(STEPS_COLUMN))
        AppText("—", color = tc.td, fontSize = 11.sp, modifier = Modifier.width(RESULT_COLUMN))
        ReorderMoveButtons(row)
        HintWhen(!canDuplicate, limits.hint) {
            SquareIconButton("⧉", fontSize = 12.sp, enabled = canDuplicate, onClick = { duplicateCase(ui, case) })
        }
        SquareIconButton("×", fontSize = 14.sp, enabled = !ui.library.readOnly, onClick = onDelete)
    }
}

private fun duplicateCase(ui: TestsUi, case: TestCase) {
    ui.report(ui.state.duplicateTestCase(case.id))
}
