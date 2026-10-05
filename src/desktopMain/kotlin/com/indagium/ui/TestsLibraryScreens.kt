@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.DEFAULT_SCRIPT_TIMEOUT_MS
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.withFreshIds
import com.indagium.testing.store.MAX_SCRIPT_OUTPUT_CAP_BYTES
import com.indagium.testing.store.MAX_SCRIPT_TIMEOUT_MS
import com.indagium.testing.store.StoreResult

// The two library screens: scripts (shell commands exposed to agents as tools) and shared steps (reusable step
// sequences that suite and case hooks run). Both are a list on the left and an editor on the right, stacked on
// a narrow window.

private val MASTER_DETAIL_BREAKPOINT = 640.dp
private val MASTER_LIST_WIDTH = 250.dp
private val LIBRARY_ROW_HEIGHT = 34.dp
private val LIBRARY_ROW_SHAPE = RoundedCornerShape(6.dp)
private val SMALL_FIELD_WIDTH = 150.dp
private val TRY_PANEL_PADDING = 10.dp
private const val NEW_SCRIPT_BASE = "new_script"
private const val NEW_SCRIPT_COMMAND = "echo hello"
private const val NEW_SHARED_STEP_BASE = "New shared step"
private const val TRY_IT_LATER_HINT = "Runs in a later phase"
private const val SCRIPT_COMMAND_MIN_HEIGHT_DP = 90
private const val SCRIPT_COMMAND_MAX_HEIGHT_DP = 280

@Composable
private fun TestsMasterDetail(list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    val tc = tc()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < MASTER_DETAIL_BREAKPOINT) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                list()
                Spacer(Modifier.height(12.dp))
                Spacer(Modifier.fillMaxWidth().height(1.dp).background(tc.br))
                Spacer(Modifier.height(12.dp))
                detail()
            }
        } else {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.width(MASTER_LIST_WIDTH).fillMaxHeight().verticalScroll(rememberScrollState()).padding(12.dp)) { list() }
                Spacer(Modifier.width(1.dp).fillMaxHeight().background(tc.br))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp)) {
                    Column(Modifier.fillMaxWidth().widthIn(max = TESTS_CONTENT_MAX_WIDTH)) { detail() }
                }
            }
        }
    }
}

@Composable
private fun LibraryListRow(title: String, badge: String?, mono: Boolean, selected: Boolean, row: ReorderRowScope, onClick: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxSize().clip(LIBRARY_ROW_SHAPE).background(if (selected) tc.ac.copy(alpha = .16f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ReorderGrip(row)
        AppText(
            title,
            color = if (selected) tc.ac else tc.tx,
            fontSize = 12.sp,
            fontFamily = if (mono) MONO else LocalUiFontFamily.current,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        badge?.let { TestsBadge(it) }
        ReorderMoveButtons(row)
    }
}

// ── Scripts ──────────────────────────────────────────────────────────

private val TARGET_LABELS = mapOf(ScriptTarget.HOST_SHELL to "Computer shell", ScriptTarget.ADB_SHELL to "Device shell (adb)")
private val PERMISSION_LABELS = mapOf(
    ScriptPermission.AUTO to "Agent may run it",
    ScriptPermission.ASK to "Ask me first",
    ScriptPermission.SETUP_TEARDOWN_ONLY to "Setup and teardown only",
)

@Composable
internal fun TestsScriptsScreen() {
    val ui = LocalTestsUi.current
    val library = ui.library
    val access = libraryEditAccess(library)
    val selected = library.scripts.firstOrNull { it.id == ui.view.selectedScriptId } ?: library.scripts.firstOrNull()
    TestsMasterDetail(
        list = {
            HintedButton(
                "New script",
                onClick = { createScriptAndSelect(ui) },
                enabled = access.editable,
                disabledHint = access.reason,
                variant = ButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (library.scripts.isEmpty()) TestsHint("No scripts yet. A script is a shell command an agent can call as a tool.")
            ReorderableColumn(
                items = library.scripts,
                idOf = { it.id },
                onMove = { id, to -> ui.report(ui.state.moveTestScript(id, to)) },
                fixedRowHeight = LIBRARY_ROW_HEIGHT,
                reorderEnabled = access.editable,
            ) { script, row ->
                LibraryListRow(script.toolName, null, mono = true, selected = script.id == selected?.id, row = row) {
                    ui.view.selectedScriptId = script.id
                }
            }
        },
        detail = {
            access.reason?.let {
                TestsLockedNotice(it)
                Spacer(Modifier.height(10.dp))
            }
            if (selected == null) TestsHint("Create a script to edit it here.") else key(selected.id) { ScriptEditor(selected, access.editable) }
        },
    )
}

private fun createScriptAndSelect(ui: TestsUi) {
    val name = uniqueIdentifier(NEW_SCRIPT_BASE, ui.library.scripts.map { it.toolName })
    val result = ui.report(ui.state.createTestScript(TestScript(id = "", toolName = name, commandTemplate = NEW_SCRIPT_COMMAND)))
    if (result is StoreResult.Ok) ui.view.selectedScriptId = result.value.id
}

@Composable
private fun ScriptEditor(script: TestScript, editable: Boolean) {
    val ui = LocalTestsUi.current
    var confirmDelete by remember { mutableStateOf(false) }
    val update: ((TestScript) -> TestScript) -> StoreResult<*> = { transform -> ui.state.updateTestScript(script.id, transform) }
    TestsLabeled("Tool name") {
        CommitTextField(script.toolName, { t -> update { it.copy(toolName = t.trim()) } }, enabled = editable, placeholder = "reset_app", mono = true)
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Description (shown to the agent)") {
        CommitTextField(
            script.description, { t -> update { it.copy(description = t) } },
            enabled = editable, multiline = true, placeholder = "What the script does",
        )
    }
    ParamsEditor(script.params, editable) { params -> update { it.copy(params = params) } }
    TestsSectionTitle("Command")
    CommitTextField(
        script.commandTemplate, { t -> update { it.copy(commandTemplate = t) } },
        enabled = editable, multiline = true, mono = true, placeholder = "adb shell pm clear \"\$package_name\"",
        minHeight = SCRIPT_COMMAND_MIN_HEIGHT_DP.dp, maxHeight = SCRIPT_COMMAND_MAX_HEIGHT_DP.dp,
    )
    TestsHint("Parameters are passed as environment variables; they are never pasted into the command text.", Modifier.padding(top = 4.dp))
    ScriptSettings(script, editable, update)
    TryItPanel()
    Spacer(Modifier.height(12.dp))
    HintedButton("Delete script", onClick = { confirmDelete = true }, enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE, isDanger = true)
    if (confirmDelete) {
        val uses = scriptUsageCount(ui.library, script.id)
        TestsConfirmDialog(
            title = "Delete script?",
            message = "\"${script.toolName}\" will be deleted." +
                if (uses > 0) " $uses hook(s) and check(s) that use it will keep pointing at it until you change them." else "",
            confirmLabel = "Delete",
            onConfirm = {
                if (ui.report(ui.state.deleteTestScript(script.id)) is StoreResult.Ok) ui.view.selectedScriptId = null
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

@Composable
private fun ScriptSettings(script: TestScript, editable: Boolean, update: ((TestScript) -> TestScript) -> StoreResult<*>) {
    TestsSectionTitle("Settings")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TestsLabeled("Runs on", Modifier.width(SMALL_FIELD_WIDTH + 30.dp)) {
            TestsDropdown(
                selectedLabel = TARGET_LABELS.getValue(script.target),
                options = ScriptTarget.entries,
                optionLabel = { TARGET_LABELS.getValue(it) },
                onSelect = { target -> update { it.copy(target = target) } },
                enabled = editable,
                isSelected = { it == script.target },
            )
        }
        TestsLabeled("Who may start it", Modifier.width(SMALL_FIELD_WIDTH + 50.dp)) {
            TestsDropdown(
                selectedLabel = PERMISSION_LABELS.getValue(script.permission),
                options = ScriptPermission.entries,
                optionLabel = { PERMISSION_LABELS.getValue(it) },
                onSelect = { permission -> update { it.copy(permission = permission) } },
                enabled = editable,
                isSelected = { it == script.permission },
            )
        }
        TestsLabeled("Timeout (ms)", Modifier.width(SMALL_FIELD_WIDTH)) {
            CommitNumberField(script.timeoutMs, 1L, MAX_SCRIPT_TIMEOUT_MS, { n -> update { it.copy(timeoutMs = n) } }, enabled = editable)
        }
        TestsLabeled("Output cap (bytes)", Modifier.width(SMALL_FIELD_WIDTH)) {
            CommitNumberField(
                script.outputCapBytes.toLong(), 1L, MAX_SCRIPT_OUTPUT_CAP_BYTES.toLong(),
                { n -> update { it.copy(outputCapBytes = n.toInt()) } }, enabled = editable,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Working directory (optional)") {
        CommitTextField(
            script.workingDir.orEmpty(), { t -> update { it.copy(workingDir = t.trim().ifEmpty { null }) } },
            enabled = editable, placeholder = "defaults to the run folder", mono = true,
        )
    }
    if (script.timeoutMs != DEFAULT_SCRIPT_TIMEOUT_MS) TestsHint("Default timeout is $DEFAULT_SCRIPT_TIMEOUT_MS ms.", Modifier.padding(top = 2.dp))
}

/** The console that will run a script with sample arguments; present now so the screen's shape is final, disabled until runs exist. */
@Composable
private fun TryItPanel() {
    val tc = tc()
    TestsSectionTitle("Try it")
    Column(
        Modifier.fillMaxWidth().border(1.dp, tc.br, CORNER_MD).background(tc.p2, CORNER_MD).padding(TRY_PANEL_PADDING),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TestsHint(TRY_IT_LATER_HINT)
        HintedButton("Run with sample values", onClick = {}, enabled = false, disabledHint = TRY_IT_LATER_HINT)
    }
}

// ── Shared steps ─────────────────────────────────────────────────────

@Composable
internal fun TestsSharedStepsScreen() {
    val ui = LocalTestsUi.current
    val library = ui.library
    val access = libraryEditAccess(library)
    val selected = library.sharedSteps.firstOrNull { it.id == ui.view.selectedSharedStepId } ?: library.sharedSteps.firstOrNull()
    TestsMasterDetail(
        list = {
            HintedButton(
                "New shared step",
                onClick = { createSharedStepAndSelect(ui) },
                enabled = access.editable,
                disabledHint = access.reason,
                variant = ButtonVariant.Primary,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            if (library.sharedSteps.isEmpty()) TestsHint("No shared steps yet. A shared step is a reusable sequence of steps for suite and case hooks.")
            ReorderableColumn(
                items = library.sharedSteps,
                idOf = { it.id },
                onMove = { id, to -> ui.report(ui.state.moveSharedStep(id, to)) },
                fixedRowHeight = LIBRARY_ROW_HEIGHT,
                reorderEnabled = access.editable,
            ) { shared, row ->
                LibraryListRow(shared.name.ifBlank { "Untitled" }, "${shared.steps.size}", mono = false, selected = shared.id == selected?.id, row = row) {
                    ui.view.selectedSharedStepId = shared.id
                }
            }
        },
        detail = {
            access.reason?.let {
                TestsLockedNotice(it)
                Spacer(Modifier.height(10.dp))
            }
            if (selected == null) TestsHint("Create a shared step to edit it here.") else key(selected.id) { SharedStepEditor(selected, access.editable) }
        },
    )
}

private fun createSharedStepAndSelect(ui: TestsUi) {
    val name = uniqueName(NEW_SHARED_STEP_BASE, ui.library.sharedSteps.map { it.name })
    val result = ui.report(ui.state.createSharedStep(SharedStep(id = "", name = name)))
    if (result is StoreResult.Ok) ui.view.selectedSharedStepId = result.value.id
}

@Composable
private fun SharedStepEditor(shared: SharedStep, editable: Boolean) {
    val ui = LocalTestsUi.current
    var confirmDelete by remember { mutableStateOf(false) }
    val update: ((SharedStep) -> SharedStep) -> StoreResult<*> = { transform -> ui.state.updateSharedStep(shared.id, transform) }
    TestsLabeled("Name") {
        CommitTextField(shared.name, { t -> update { it.copy(name = t.trim()) } }, enabled = editable, placeholder = "Log in")
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Description") {
        CommitTextField(
            shared.description, { t -> update { it.copy(description = t) } },
            enabled = editable, multiline = true, placeholder = "What this sequence does",
        )
    }
    StepListSection(sharedStepOps(shared, editable, update))
    Spacer(Modifier.height(12.dp))
    HintedButton("Delete shared step", onClick = { confirmDelete = true }, enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE, isDanger = true)
    if (confirmDelete) {
        val uses = sharedStepUsageCount(ui.library, shared.id)
        TestsConfirmDialog(
            title = "Delete shared step?",
            message = "\"${shared.name}\" and its ${shared.steps.size} step(s) will be deleted." +
                if (uses > 0) " $uses hook(s) that run it will keep pointing at it until you change them." else "",
            confirmLabel = "Delete",
            onConfirm = {
                if (ui.report(ui.state.deleteSharedStep(shared.id)) is StoreResult.Ok) ui.view.selectedSharedStepId = null
            },
            onDismiss = { confirmDelete = false },
        )
    }
}

/** A shared step keeps its steps inside itself, so every step operation is an update of the shared step's step list. */
private fun sharedStepOps(shared: SharedStep, editable: Boolean, update: ((SharedStep) -> SharedStep) -> StoreResult<*>) = StepListOps(
    steps = shared.steps,
    editable = editable,
    assetSuiteId = null,
    update = { stepId, transform -> update { sh -> sh.copy(steps = sh.steps.map { if (it.id == stepId) transform(it) else it }) } },
    add = { step -> update { sh -> sh.copy(steps = sh.steps + step.copy(id = step.id.ifBlank { newStepId() })) } },
    delete = { stepId -> update { sh -> sh.copy(steps = sh.steps.filterNot { it.id == stepId }) } },
    duplicate = { stepId ->
        update { sh ->
            val index = sh.steps.indexOfFirst { it.id == stepId }
            if (index < 0) sh else sh.copy(steps = sh.steps.toMutableList().apply { add(index + 1, sh.steps[index].withFreshIds()) })
        }
    },
    move = { stepId, to -> update { sh -> sh.copy(steps = sh.steps.moveById(stepId, to) { it.id }) } },
)
