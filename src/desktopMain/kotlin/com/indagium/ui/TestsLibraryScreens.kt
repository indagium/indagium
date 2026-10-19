@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
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
// sequences that suite and case hooks run). Both are a TestsSplitScreen: the centre column holds the list at the top and the
// selected item's editor under it; the inspector holds the secondary panel (a script's Try it / Tool schema / Used in, a
// shared step's selected-step editor). A narrow window stacks the inspector under the centre column.

private val LIBRARY_ROW_HEIGHT = 34.dp
private val LIBRARY_ROW_MENU_SLOT = 26.dp
private val HEADER_GAP = 10.dp
private val REFERENCE_ROW_HEIGHT = 26.dp
private val SETTINGS_GRID_TWO_COLUMNS_FROM = 460.dp
private val SCHEMA_MAX_HEIGHT = 220.dp
private const val SCHEMA_MAX_LINES = 400
private const val NEW_SCRIPT_BASE = "new_script"
private const val NEW_SCRIPT_COMMAND = "echo hello"
private const val NEW_SHARED_STEP_BASE = "New shared step"
private const val SCRIPT_COMMAND_MIN_HEIGHT_DP = 90
private const val SCRIPT_COMMAND_MAX_HEIGHT_DP = 280

/**
 * One row of a library list: the reorder grip, the [title], an optional [badge] and a ⋯ menu with Move up /
 * Move down and item-specific [actions] (shown on hover or while selected), keeping column widths stable.
 */
@Composable
private fun LibraryListRow(
    title: String,
    badge: String?,
    mono: Boolean,
    selected: Boolean,
    row: ReorderRowScope,
    menuDescription: String,
    actions: List<TestsMenuItem> = emptyList(),
    onClick: () -> Unit,
) {
    val tc = tc()
    val ui = LocalTestsUi.current
    var hovered by remember { mutableStateOf(false) }
    TestsListRow(
        selected = selected,
        onClick = {
            ui.reclaimFocus()
            onClick()
        },
        modifier = Modifier.onPointerEvent(PointerEventType.Enter) { hovered = true }.onPointerEvent(PointerEventType.Exit) { hovered = false },
        minHeight = LIBRARY_ROW_HEIGHT,
        startPadding = 4.dp,
        spacing = 6.dp,
    ) {
        ReorderGrip(row)
        AppText(
            title,
            color = tc.tx,
            fontSize = 12.sp,
            fontFamily = if (mono) MONO else LocalUiFontFamily.current,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        badge?.let { TestsBadge(it) }
        Box(Modifier.size(LIBRARY_ROW_MENU_SLOT).alpha(if (hovered || selected) 1f else 0f)) {
            val readOnlyHint = LIBRARY_READ_ONLY_MESSAGE.takeIf { !row.enabled }
            TestsOverflowMenu(
                listOf(
                    TestsMenuItem("Move up", onClick = row.moveUp, enabled = row.canMoveUp, disabledHint = readOnlyHint),
                    TestsMenuItem("Move down", onClick = row.moveDown, enabled = row.canMoveDown, disabledHint = readOnlyHint),
                ) + actions,
                contentDescription = menuDescription,
            )
        }
    }
}

/** The section title of a library list ("Scripts (3)") with its trailing actions, right aligned. */
@Composable
private fun LibraryListHeader(title: String, trailing: @Composable () -> Unit) {
    TestsSectionTitle(title) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

/** [cells] laid out two per row when the column is wide enough, one per row when it is narrow. */
@Composable
private fun TestsFieldGrid(cells: List<@Composable () -> Unit>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = if (maxWidth >= SETTINGS_GRID_TWO_COLUMNS_FROM) 2 else 1
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cells.chunked(columns).forEach { rowCells ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowCells.forEach { cell -> Box(Modifier.weight(1f)) { cell() } }
                    repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
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
    var deleteScriptId by remember { mutableStateOf<String?>(null) }
    var generateOpen by remember { mutableStateOf(false) }
    var regenerateScriptId by remember { mutableStateOf<String?>(null) }
    TestsScreenScaffold(inspector = selected?.let { script -> { key(script.id) { ScriptInspector(script) } } }) {
        access.reason?.let {
            TestsLockedNotice(it)
            Spacer(Modifier.height(HEADER_GAP))
        }
        if (ui.view.scriptReturn != null) {
            ScriptReturnBar(ui)
            Spacer(Modifier.height(HEADER_GAP))
        }
        LibraryListHeader("Scripts (${library.scripts.size})") {
            HintedButton(
                "+ New script",
                onClick = { createScriptAndSelect(ui) },
                enabled = access.editable,
                disabledHint = access.reason,
                variant = ButtonVariant.Ghost,
            )
            HintedButton(
                "Generate with AI…",
                onClick = { generateOpen = true },
                enabled = access.editable,
                disabledHint = access.reason,
                variant = ButtonVariant.Ghost,
            )
            TestsOverflowMenu(
                listOf(
                    TestsMenuItem(
                        "Import JSON…",
                        onClick = {
                            importError = null
                            importTestScriptJson(ui, onImported = { ui.view.selectedScriptId = it.id }, onError = { importError = it })
                        },
                        enabled = access.editable,
                        disabledHint = access.reason,
                    ),
                ),
                contentDescription = "Script list actions",
            )
        }
        importError?.let { TestsErrorText(it) }
        if (library.scripts.isEmpty()) TestsHint("No scripts yet. A script is a shell command an agent can call as a tool.")
        ReorderableColumn(
            items = library.scripts,
            idOf = { it.id },
            onMove = { id, to -> ui.report(ui.state.moveTestScript(id, to)) },
            fixedRowHeight = LIBRARY_ROW_HEIGHT,
            reorderEnabled = access.editable,
        ) { script, row ->
            val uses = scriptUsageCount(library, script.id)
            LibraryListRow(
                script.toolName, "used in $uses".takeIf { uses > 0 }, mono = true, selected = script.id == selected?.id, row = row,
                menuDescription = "${script.toolName} actions",
                actions = listOf(
                    TestsMenuItem(
                        "Delete…", onClick = { deleteScriptId = script.id }, danger = true,
                        enabled = access.editable, disabledHint = access.reason, dividerBefore = true,
                    ),
                ),
            ) { ui.view.selectedScriptId = script.id }
        }
        if (selected == null) {
            TestsHint("Create a script to edit it here.", Modifier.padding(top = 12.dp))
        } else {
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            key(selected.id) {
                ScriptEditor(
                    selected, access.editable,
                    onDelete = { deleteScriptId = selected.id },
                    onRegenerate = { regenerateScriptId = selected.id },
                )
            }
        }
    }
    library.scripts.firstOrNull { it.id == deleteScriptId }?.let { script ->
        ScriptDeleteDialog(script, onDismiss = { deleteScriptId = null })
    }
    if (generateOpen) {
        TestScriptDraftDialog(
            existing = null,
            onApplied = { ui.view.selectedScriptId = it.id },
            onDismiss = { generateOpen = false; ui.reclaimFocus() },
        )
    }
    library.scripts.firstOrNull { it.id == regenerateScriptId }?.let { script ->
        TestScriptDraftDialog(
            existing = script,
            onApplied = { ui.view.selectedScriptId = it.id },
            onDismiss = { regenerateScriptId = null; ui.reclaimFocus() },
        )
    }
}

@Composable
private fun ScriptDeleteDialog(script: TestScript, onDismiss: () -> Unit) {
    val ui = LocalTestsUi.current
    val uses = scriptUsageCount(ui.library, script.id)
    TestsConfirmDialog(
        title = "Delete script?",
        message = "\"${script.toolName}\" will be deleted." +
            if (uses > 0) " $uses step(s), hook(s) and check(s) that use it will keep pointing at it until you change them." else "",
        confirmLabel = "Delete",
        onConfirm = {
            if (ui.report(ui.state.deleteTestScript(script.id)) is StoreResult.Ok && ui.view.selectedScriptId == script.id) {
                ui.view.selectedScriptId = null
            }
        },
        onDismiss = onDismiss,
    )
}

private fun createScriptAndSelect(ui: TestsUi) {
    createPlaceholderScript(ui)?.let { ui.view.selectedScriptId = it.id }
}

/** A new library script with a placeholder command for the user to edit; null when the library refused it (the banner says why). */
internal fun createPlaceholderScript(ui: TestsUi): TestScript? {
    val name = uniqueIdentifier(NEW_SCRIPT_BASE, ui.library.scripts.map { it.toolName })
    val result = ui.report(ui.state.createTestScript(TestScript(id = "", toolName = name, commandTemplate = NEW_SCRIPT_COMMAND)))
    return (result as? StoreResult.Ok)?.value
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

/** Shown while the script editor was opened from a step: the script is already that step's; this leads back to it. */
@Composable
private fun ScriptReturnBar(ui: TestsUi) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        TestsHint("This script was added to a step. Write its command, then return to the step.", Modifier.weight(1f))
        AppButton("Back to the step", onClick = { ui.view.returnFromScriptEditor() }, variant = ButtonVariant.Primary)
        AppButton("Dismiss", onClick = { ui.view.scriptReturn = null }, variant = ButtonVariant.Ghost)
    }
}

/**
 * The selected script's editor: the tool name as the title with the ⋯ menu (Duplicate, Import JSON…, Regenerate with AI…, Export JSON…, Delete…), then
 * its description, parameters, command and settings.
 */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ScriptEditor(script: TestScript, editable: Boolean, onDelete: () -> Unit, onRegenerate: () -> Unit) {
    val ui = LocalTestsUi.current
    var actionError by remember { mutableStateOf<String?>(null) }
    val update: ((TestScript) -> TestScript) -> StoreResult<*> = { transform -> ui.state.updateTestScript(script.id, transform) }
    TestsPageHeader(
        title = script.toolName,
        onTitleCommit = { t -> update { it.copy(toolName = t.trim()) } },
        titleEditable = editable,
        titleDescription = "Tool name",
        titlePlaceholder = "reset_app",
        overflowDescription = "More script actions",
        overflowItems = listOf(
            TestsMenuItem(
                "Duplicate",
                onClick = { ui.report(ui.state.duplicateTestScript(script.id).also { result -> if (result is StoreResult.Ok) ui.view.selectedScriptId = result.value.id }) },
                enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE,
            ),
            TestsMenuItem(
                "Import JSON…",
                onClick = { importTestScriptJson(ui, onImported = { ui.view.selectedScriptId = it.id }, onError = { actionError = it }) },
                enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE,
            ),
            TestsMenuItem(
                "Regenerate with AI…", onClick = onRegenerate,
                enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE,
            ),
            TestsMenuItem("Export JSON…", onClick = { exportScriptJson(ui, script) { actionError = it } }),
            TestsMenuItem("Delete…", onClick = onDelete, danger = true, enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE, dividerBefore = true),
        ),
    )
    actionError?.let { TestsErrorText(it) }
    Spacer(Modifier.height(HEADER_GAP))
    ScriptFields(script, editable, update)
}

private fun exportScriptJson(ui: TestsUi, script: TestScript, onError: (String) -> Unit) {
    val destination = pickSaveScriptFile(suiteExportFileName(script.toolName.replace(' ', '_'))) ?: return
    ui.reclaimFocus()
    ui.scope.launch(Dispatchers.IO) {
        when (val source = ui.state.exportTestScriptEnvelope(script.id)) {
            is StoreResult.Ok -> runCatching { writeTestScriptEnvelope(destination, source.value, overwrite = destination.exists()) }
                .fold({ ui.info("Exported ${script.toolName} to ${destination.name}.") }, { onError(it.message ?: "Could not write script JSON.") })
            else -> ui.report(source)
        }
    }
}

@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun ScriptFields(script: TestScript, editable: Boolean, update: ((TestScript) -> TestScript) -> StoreResult<*>) {
    TestsLabeled("Description (shown to the agent)") {
        CommitTextField(
            script.description,
            { t -> update { it.copy(description = t) } },
            enabled = editable,
            multiline = true,
            placeholder = "What the script does",
            proseTargetId = "test-script:${script.id}:description",
        )
    }
    ParamsEditor(script.id, script.params, editable) { params -> update { it.copy(params = params) } }
    TestsSectionTitle("Command")
    CommitTextField(
        script.commandTemplate, { t -> update { it.copy(commandTemplate = t) } },
        enabled = editable, multiline = true, mono = true, placeholder = "adb shell pm clear \"\$package_name\"",
        minHeight = SCRIPT_COMMAND_MIN_HEIGHT_DP.dp, maxHeight = SCRIPT_COMMAND_MAX_HEIGHT_DP.dp,
    )
    TestsHint("Parameters are passed as environment variables; they are never pasted into the command text.", Modifier.padding(top = 4.dp))
    ScriptSettings(script, editable, update)
}

// ── Script inspector ─────────────────────────────────────────────────

/** The selected script's secondary panels: Try it, the Tool schema the agent sees, and where the script is used. */
@Composable
private fun ScriptInspector(script: TestScript) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HEADER_GAP)) {
        TryItPanel(script)
        ScriptSchemaPanel(script)
        ScriptUsagePanel(script)
    }
}

/** A card of the inspector: [title] inside it, a Show / Hide link at the right when [expandable], then [content] while [open]. */
@Composable
private fun InspectorCard(title: String, open: Boolean, expandable: Boolean, onToggle: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val tc = tc()
    Column(Modifier.fillMaxWidth().background(tc.p2, CORNER_MD).padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TestsPanelTitle(title, Modifier.weight(1f))
            if (expandable) TestsBreadcrumbLink(if (open) "Hide" else "Show ▸", onToggle, contentDescription = "${if (open) "Hide" else "Show"} $title")
        }
        if (open || !expandable) content()
    }
}

@Composable
private fun ScriptSchemaPanel(script: TestScript) {
    val tc = tc()
    var open by remember(script.id) { mutableStateOf(false) }
    InspectorCard("Tool schema", open, expandable = true, onToggle = { open = !open }) {
        val descriptor = scriptToolDescriptor(script)
        val schema = Json.encodeToJsonElement(ToolSchema.serializer(), descriptor.schema).toString()
        AppText(script.toolName, color = tc.ac, fontSize = 10.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
        AppText(descriptor.description, color = tc.ts, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        TestsScrollColumn(Modifier.fillMaxWidth().heightIn(max = SCHEMA_MAX_HEIGHT)) {
            AppText(schema, color = tc.td, fontSize = 10.sp, fontFamily = MONO, maxLines = SCHEMA_MAX_LINES)
        }
    }
}

@Composable
private fun ScriptUsagePanel(script: TestScript) {
    val ui = LocalTestsUi.current
    val tc = tc()
    val references = testScriptUsageReferences(ui.library, script.id)
    var open by remember(script.id) { mutableStateOf(false) }
    InspectorCard("Used in ${references.size}", open, expandable = references.isNotEmpty(), onToggle = { open = !open }) {
        if (references.isEmpty()) TestsHint("No step, hook or check uses this script yet.")
        references.forEach { ref ->
            val owner = ref.sharedStepName ?: listOfNotNull(ref.suiteName, ref.caseName).joinToString(" · ")
            TestsListRow(
                selected = false,
                onClick = {
                    ui.reclaimFocus()
                    if (ref.sharedStepId != null) {
                        ui.view.nav = TestsNav.SharedSteps
                        ui.view.selectedSharedStepId = ref.sharedStepId
                    } else if (ref.suiteId != null) {
                        ui.view.nav = TestsNav.Suites
                        ui.view.selectedSuiteId = ref.suiteId
                        ui.view.selectedCaseId = ref.caseId
                        ui.view.selectedStepId = ref.stepId
                        if (ref.kind == "hook") ui.view.selectedSuiteTab = SuiteTab.Settings
                    }
                },
                minHeight = REFERENCE_ROW_HEIGHT,
            ) {
                AppText(
                    listOf(owner, ref.label).filter(String::isNotBlank).joinToString(" — "),
                    color = tc.ac, fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
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
    TestsFieldGrid(
        listOf(
            {
                TestsLabeled("Runs on") {
                    TestsDropdown(
                        selectedLabel = TARGET_LABELS.getValue(script.target),
                        options = ScriptTarget.entries,
                        optionLabel = { TARGET_LABELS.getValue(it) },
                        onSelect = { target -> update { it.copy(target = target) } },
                        enabled = editable,
                        isSelected = { it == script.target },
                    )
                }
            },
            {
                TestsLabeled("Who may start it") {
                    TestsDropdown(
                        selectedLabel = PERMISSION_LABELS.getValue(script.permission),
                        options = ScriptPermission.entries,
                        optionLabel = { PERMISSION_LABELS.getValue(it) },
                        onSelect = { permission -> update { it.copy(permission = permission) } },
                        enabled = editable,
                        isSelected = { it == script.permission },
                    )
                }
            },
            {
                TestsLabeled("Timeout (ms)") {
                    CommitNumberField(script.timeoutMs, 1L, MAX_SCRIPT_TIMEOUT_MS, { n -> update { it.copy(timeoutMs = n) } }, enabled = editable)
                }
            },
            {
                TestsLabeled("Output cap (bytes)") {
                    CommitNumberField(
                        script.outputCapBytes.toLong(), 1L, MAX_SCRIPT_OUTPUT_CAP_BYTES.toLong(),
                        { n -> update { it.copy(outputCapBytes = n.toInt()) } }, enabled = editable,
                    )
                }
            },
        ),
    )
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
    if (selected == null) {
        TestsEmptyState(
            glyph = "⧉",
            title = "No shared steps yet",
            text = "A shared step is a reusable sequence of steps. Suite and case hooks can run it before or after a case.",
            modifier = Modifier.fillMaxSize(),
        ) {
            HintedButton(
                "+ New shared step",
                onClick = { createSharedStepAndSelect(ui) },
                enabled = access.editable,
                disabledHint = access.reason,
                variant = ButtonVariant.Primary,
            )
        }
        return
    }
    val update: ((SharedStep) -> SharedStep) -> StoreResult<*> = { transform -> ui.state.updateSharedStep(selected.id, transform) }
    val stepOps = sharedStepOps(selected, access.editable, update)
    TestsScreenScaffold(inspector = { key(selected.id) { SelectedStepInspector(stepOps) } }) {
        access.reason?.let {
            TestsLockedNotice(it)
            Spacer(Modifier.height(HEADER_GAP))
        }
        LibraryListHeader("Shared steps (${library.sharedSteps.size})") {
            HintedButton(
                "+ New shared step",
                onClick = { createSharedStepAndSelect(ui) },
                enabled = access.editable,
                disabledHint = access.reason,
                variant = ButtonVariant.Ghost,
            )
        }
        ReorderableColumn(
            items = library.sharedSteps,
            idOf = { it.id },
            onMove = { id, to -> ui.report(ui.state.moveSharedStep(id, to)) },
            fixedRowHeight = LIBRARY_ROW_HEIGHT,
            reorderEnabled = access.editable,
        ) { shared, row ->
            LibraryListRow(
                shared.name.ifBlank { "Untitled" }, "${shared.steps.size}", mono = false, selected = shared.id == selected.id, row = row,
                menuDescription = "${shared.name.ifBlank { "Untitled" }} actions",
            ) { ui.view.selectedSharedStepId = shared.id }
        }
        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))
        key(selected.id) { SharedStepEditor(selected, access.editable, stepOps, update) }
    }
}

private fun createSharedStepAndSelect(ui: TestsUi) {
    val name = uniqueName(NEW_SHARED_STEP_BASE, ui.library.sharedSteps.map { it.name })
    val result = ui.report(ui.state.createSharedStep(SharedStep(id = "", name = name)))
    if (result is StoreResult.Ok) ui.view.selectedSharedStepId = result.value.id
}

/**
 * The selected shared step: its name as the title with a ⋯ menu (Delete…), the description and the step list; the selected
 * step's editor is the screen's inspector.
 */
@Suppress("ktlint:standard:max-line-length", "MaxLineLength")
@Composable
private fun SharedStepEditor(
    shared: SharedStep,
    editable: Boolean,
    stepOps: StepListOps,
    update: ((SharedStep) -> SharedStep) -> StoreResult<*>,
) {
    val ui = LocalTestsUi.current
    var confirmDelete by remember { mutableStateOf(false) }
    TestsPageHeader(
        title = shared.name,
        onTitleCommit = { t -> update { it.copy(name = t.trim()) } },
        titleEditable = editable,
        titleDescription = "Shared step name",
        proseTargetId = "shared-step:${shared.id}:title",
        titlePlaceholder = "Log in",
        overflowDescription = "More shared step actions",
        overflowItems = listOf(
            TestsMenuItem("Delete…", onClick = { confirmDelete = true }, danger = true, enabled = editable, disabledHint = LIBRARY_READ_ONLY_MESSAGE),
        ),
    )
    Spacer(Modifier.height(HEADER_GAP))
    TestsLabeled("Description") {
        CommitTextField(
            shared.description, { t -> update { it.copy(description = t) } },
            enabled = editable, multiline = true, placeholder = "What this sequence does",
            proseTargetId = "shared-step:${shared.id}:description",
        )
    }
    StepListSection(stepOps)
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
