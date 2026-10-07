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
import com.indagium.testing.authoring.readTestScriptEnvelope
import com.indagium.testing.authoring.testScriptUsageReferences
import com.indagium.testing.authoring.writeTestScriptEnvelope
import com.indagium.testing.model.DEFAULT_SCRIPT_TIMEOUT_MS
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.SharedStep
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newStepId
import com.indagium.testing.model.withFreshIds
import com.indagium.testing.run.scriptToolDescriptor
import com.indagium.testing.store.MAX_SCRIPT_OUTPUT_CAP_BYTES
import com.indagium.testing.store.MAX_SCRIPT_TIMEOUT_MS
import com.indagium.testing.store.StoreResult
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// The two library screens: scripts (shell commands exposed to agents as tools) and shared steps (reusable step
// sequences that suite and case hooks run). Both are a list on the left and an editor on the right, stacked on
// a narrow window.

private val MASTER_DETAIL_BREAKPOINT = 640.dp
private val MASTER_LIST_WIDTH = 250.dp
private val LIBRARY_ROW_HEIGHT = 34.dp
private val LIBRARY_ROW_SHAPE = RoundedCornerShape(6.dp)
private val SMALL_FIELD_WIDTH = 150.dp
private const val NEW_SCRIPT_BASE = "new_script"
private const val NEW_SCRIPT_COMMAND = "echo hello"
private const val NEW_SHARED_STEP_BASE = "New shared step"
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
    var importError by remember { mutableStateOf<String?>(null) }
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
            HintedButton(
                "Import JSON",
                onClick = {
                    importError = null
                    importTestScriptJson(ui, onImported = { ui.view.selectedScriptId = it.id }, onError = { importError = it })
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = access.editable,
                disabledHint = access.reason,
            )
            importError?.let { TestsErrorText(it) }
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

private fun importTestScriptJson(ui: TestsUi, onImported: (TestScript) -> Unit, onError: (String) -> Unit) {
    val selected = pickOpenFile("Import test script JSON") ?: return
    ui.reclaimFocus()
    ui.scope.launch(Dispatchers.IO) {
        runCatching { readTestScriptEnvelope(selected) }.fold(
            { source ->
                when (val imported = ui.state.importTestScriptEnvelope(source)) {
                    is StoreResult.Ok -> { onImported(imported.value); ui.report(imported) }
                    else -> ui.report(imported)
                }
            },
            { onError(it.message ?: "Could not read script JSON.") },
        )
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ScriptEditor(script: TestScript, editable: Boolean) {
    val ui = LocalTestsUi.current
    var confirmDelete by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    val update: ((TestScript) -> TestScript) -> StoreResult<*> = { transform -> ui.state.updateTestScript(script.id, transform) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HintedButton("Duplicate", onClick = { ui.report(ui.state.duplicateTestScript(script.id).also { result -> if (result is StoreResult.Ok) ui.view.selectedScriptId = result.value.id }) }, enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE)
        HintedButton("Import JSON", onClick = {
            importTestScriptJson(ui, onImported = { ui.view.selectedScriptId = it.id }, onError = { actionError = it })
        }, enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE)
        HintedButton("Export JSON", onClick = {
            val destination = pickSaveScriptFile(suiteExportFileName(script.toolName.replace(' ', '_'))) ?: return@HintedButton
            ui.reclaimFocus()
            ui.scope.launch(Dispatchers.IO) {
                when (val source = ui.state.exportTestScriptEnvelope(script.id)) {
                    is StoreResult.Ok -> runCatching { writeTestScriptEnvelope(destination, source.value, overwrite = destination.exists()) }
                        .fold({ ui.info("Exported ${script.toolName} to ${destination.name}.") }, { actionError = it.message ?: "Could not write script JSON." })
                    else -> ui.report(source)
                }
            }
        }, enabled = true)
    }
    actionError?.let { TestsErrorText(it) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 700.dp) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) { ScriptFields(script, editable, update) }
                Column(Modifier.width(290.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TryItPanel(script)
                    ScriptSchemaPanel(script)
                    ScriptUsagePanel(script)
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                ScriptFields(script, editable, update)
                TryItPanel(script)
                ScriptSchemaPanel(script)
                ScriptUsagePanel(script)
            }
        }
    }
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

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ScriptFields(script: TestScript, editable: Boolean, update: ((TestScript) -> TestScript) -> StoreResult<*>) {
    TestsLabeled("Tool name") {
        CommitTextField(script.toolName, { t -> update { it.copy(toolName = t.trim()) } }, enabled = editable, placeholder = "reset_app", mono = true)
    }
    Spacer(Modifier.height(8.dp))
    TestsLabeled("Description (shown to the agent)") {
        CommitTextField(script.description, { t -> update { it.copy(description = t) } }, enabled = editable, multiline = true, placeholder = "What the script does")
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
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ScriptSchemaPanel(script: TestScript) {
    val tc = tc()
    val descriptor = scriptToolDescriptor(script)
    val schema = Json.encodeToJsonElement(ToolSchema.serializer(), descriptor.schema).toString()
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(10.dp)) {
        TestsSectionTitle("Tool schema preview")
        AppText(script.toolName, color = tc.ac, fontSize = 10.sp, fontFamily = MONO)
        AppText(descriptor.description, color = tc.ts, fontSize = 9.sp)
        AppText(schema, color = tc.td, fontSize = 8.sp, fontFamily = MONO, maxLines = 18, overflow = TextOverflow.Ellipsis)
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ScriptUsagePanel(script: TestScript) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val references = testScriptUsageReferences(ui.library, script.id)
    var expanded by remember(script.id) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(10.dp)) {
        AppButton("Used in · ${references.size}", onClick = { expanded = !expanded }, variant = ButtonVariant.Ghost)
        if (expanded) references.forEach { ref ->
            val owner = ref.sharedStepName ?: listOfNotNull(ref.suiteName, ref.caseName).joinToString(" · ")
            Column(Modifier.fillMaxWidth().clickable {
                if (ref.sharedStepId != null) {
                    ui.view.nav = TestsNav.SharedSteps
                    ui.view.selectedSharedStepId = ref.sharedStepId
                } else if (ref.suiteId != null) {
                    ui.view.nav = TestsNav.Suites
                    ui.view.selectedSuiteId = ref.suiteId
                    ui.view.selectedCaseId = ref.caseId
                    ui.view.expandedStepId = ref.stepId
                    if (ref.kind == "hook") ui.view.selectedSuiteTab = SuiteTab.SetupTeardown
                }
            }.padding(vertical = 4.dp)) {
                AppText(listOf(owner, ref.label).filter(String::isNotBlank).joinToString(" — "), color = tc.ac, fontSize = 9.sp)
            }
        }
    }
}

private fun pickSaveScriptFile(defaultName: String): File? {
    val dialog = FileDialog(null as Frame?, "Export test script JSON", FileDialog.SAVE).apply { file = defaultName; isVisible = true }
    val name = dialog.file ?: return null
    val directory = dialog.directory ?: return null
    return File(directory, name)
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
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

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
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
