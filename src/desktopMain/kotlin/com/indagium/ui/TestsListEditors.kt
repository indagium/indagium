@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.testing.model.HookItem
import com.indagium.testing.model.RESERVED_SCRIPT_TOOL_NAMES
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.TestLibrary
import com.indagium.testing.model.TestVariable
import com.indagium.testing.model.moveById
import com.indagium.testing.model.newHookId
import com.indagium.testing.model.newVariableId
import com.indagium.testing.store.StoreResult

// Small list editors shared by the suite, case and script screens. Each takes the CURRENT list and an `onChange`
// that persists a whole replacement list (through the AppState delegates) and returns the store's result, so a
// rejected change (for example a duplicate parameter name) shows inline and leaves the stored list alone. Reordering
// uses ReorderableColumn: a move is just `list.moveById(id, toIndex)` handed to `onChange`.

private val TAG_FIELD_WIDTH = 130.dp
private val TYPE_MENU_WIDTH = 120.dp
private val MIN_ARGS_HEIGHT = 36.dp
private const val ADD_HOOK_LABEL = "+ Add"
private const val ADD_ITEM_LABEL = "+ Add"
private const val NEW_VARIABLE_BASE = "variable"
private const val NEW_PARAM_BASE = "param"

// ── Tags ─────────────────────────────────────────────────────────────

/** Chips with a × each, plus a field that adds tags (comma-separated) on Enter. */
@Composable
internal fun TagsEditor(tags: List<String>, enabled: Boolean, onChange: (List<String>) -> StoreResult<*>) {
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    fun addDraft() {
        val added = draft.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (added.isEmpty()) return
        val result = onChange(tags + added)
        error = result.userMessage()
        if (result is StoreResult.Ok) draft = ""
    }
    Column(Modifier.fillMaxWidth()) {
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            tags.forEach { tag ->
                TestsChip(tag, onRemove = if (enabled) ({ error = onChange(tags - tag).userMessage() }) else null)
            }
            if (enabled) {
                InlineField(
                    value = draft,
                    onValue = { draft = it },
                    placeholder = "add tag",
                    modifier = Modifier.width(TAG_FIELD_WIDTH),
                    fontSize = 11.sp,
                    onSubmit = ::addDraft,
                    onCancel = { draft = "" },
                )
            } else if (tags.isEmpty()) {
                TestsHint("No tags")
            }
        }
        error?.let { TestsErrorText(it) }
    }
}

// ── key=value text ───────────────────────────────────────────────────

/** A multi-line `key=value` editor for a string map (hook and check script arguments). */
@Composable
internal fun KeyValueField(
    value: Map<String, String>,
    enabled: Boolean,
    onCommit: (Map<String, String>) -> StoreResult<*>,
    modifier: Modifier = Modifier,
    placeholder: String = "key=value, one per line",
) {
    CommitTextField(
        value = formatKeyValueLines(value),
        onCommit = { text ->
            val (map, bad) = parseKeyValueLines(text)
            if (bad.isNotEmpty()) StoreResult.Invalid("Not in key=value form: ${bad.first()}") else onCommit(map)
        },
        modifier = modifier,
        enabled = enabled,
        placeholder = placeholder,
        multiline = true,
        mono = true,
        minHeight = MIN_ARGS_HEIGHT,
    )
}

// ── Hooks ────────────────────────────────────────────────────────────

private enum class HookKind(val label: String) { SCRIPT("Script"), SHARED("Shared step") }

/** Setup / teardown hooks: an ordered list of "run this script" and "run this shared step" entries. */
@Composable
internal fun HooksEditor(title: String, hooks: List<HookItem>, enabled: Boolean, onChange: (List<HookItem>) -> StoreResult<*>) {
    val library = LocalTestsUi.current.library
    var error by remember { mutableStateOf<String?>(null) }
    val apply: (List<HookItem>) -> Unit = { error = onChange(it).userMessage() }
    val addKinds = buildList {
        if (library.scripts.isNotEmpty()) add(HookKind.SCRIPT)
        if (library.sharedSteps.isNotEmpty()) add(HookKind.SHARED)
    }
    TestsSectionTitle(title) {
        if (enabled) {
            TestsDropdown(
                selectedLabel = ADD_HOOK_LABEL,
                options = addKinds,
                optionLabel = { it.label },
                onSelect = { kind ->
                    val hook = when (kind) {
                        HookKind.SCRIPT -> HookItem.Script(newHookId(), library.scripts.first().id)
                        HookKind.SHARED -> HookItem.Shared(newHookId(), library.sharedSteps.first().id)
                    }
                    apply(hooks + hook)
                },
                emptyText = "Create a script or a shared step first (Library)",
            )
        }
    }
    if (hooks.isEmpty()) TestsHint("None")
    ReorderableColumn(
        items = hooks,
        idOf = { it.id },
        onMove = { id, to -> apply(hooks.moveById(id, to) { it.id }) },
        reorderEnabled = enabled,
        rowGap = 4.dp,
    ) { hook, row ->
        ReorderRowCard(row, enabled, onRemove = { apply(hooks.filterNot { it.id == hook.id }) }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                HookRowBody(hook, enabled, library) { replacement ->
                    onChange(hooks.map { if (it.id == hook.id) replacement else it }).also { error = it.userMessage() }
                }
            }
        }
    }
    error?.let { TestsErrorText(it) }
}

@Composable
private fun HookRowBody(
    hook: HookItem,
    enabled: Boolean,
    library: TestLibrary,
    onReplace: (HookItem) -> StoreResult<*>,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        when (hook) {
            is HookItem.Script -> {
                TestsBadge(HookKind.SCRIPT.label)
                TestsDropdown(
                    selectedLabel = library.script(hook.scriptId)?.toolName ?: "(missing script)",
                    options = library.scripts,
                    optionLabel = { it.toolName },
                    onSelect = { onReplace(hook.copy(scriptId = it.id)) },
                    enabled = enabled,
                    isSelected = { it.id == hook.scriptId },
                )
            }
            is HookItem.Shared -> {
                TestsBadge(HookKind.SHARED.label)
                TestsDropdown(
                    selectedLabel = library.sharedStep(hook.sharedStepId)?.name ?: "(missing shared step)",
                    options = library.sharedSteps,
                    optionLabel = { it.name },
                    onSelect = { onReplace(hook.copy(sharedStepId = it.id)) },
                    enabled = enabled,
                    isSelected = { it.id == hook.sharedStepId },
                )
            }
        }
    }
    if (hook is HookItem.Script) {
        KeyValueField(hook.args, enabled, onCommit = { onReplace(hook.copy(args = it)) }, placeholder = "Arguments, key=value per line")
    }
}

// ── Variables ────────────────────────────────────────────────────────

/** The suite's named values: name, value and an optional description per row. */
@Composable
internal fun VariablesEditor(variables: List<TestVariable>, enabled: Boolean, onChange: (List<TestVariable>) -> StoreResult<*>) {
    var error by remember { mutableStateOf<String?>(null) }
    val apply: (List<TestVariable>) -> Unit = { error = onChange(it).userMessage() }
    TestsSectionTitle("Variables") {
        if (enabled) {
            AppButton(ADD_ITEM_LABEL, onClick = {
                val name = uniqueIdentifier(NEW_VARIABLE_BASE, variables.map { it.name })
                apply(variables + TestVariable(newVariableId(), name))
            }, variant = ButtonVariant.Ghost)
        }
    }
    if (variables.isEmpty()) TestsHint("None")
    ReorderableColumn(
        items = variables,
        idOf = { it.id },
        onMove = { id, to -> apply(variables.moveById(id, to) { it.id }) },
        reorderEnabled = enabled,
        rowGap = 4.dp,
    ) { variable, row ->
        val replace: (TestVariable) -> StoreResult<*> = { replacement -> onChange(variables.map { if (it.id == variable.id) replacement else it }) }
        ReorderRowCard(row, enabled, onRemove = { apply(variables.filterNot { it.id == variable.id }) }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    CommitTextField(variable.name, { replace(variable.copy(name = it.trim())) }, Modifier.weight(1f), enabled, "name", mono = true)
                    CommitTextField(variable.value, { replace(variable.copy(value = it)) }, Modifier.weight(1.4f), enabled, "value")
                }
                CommitTextField(variable.description, { replace(variable.copy(description = it)) }, enabled = enabled, placeholder = "description")
            }
        }
    }
    error?.let { TestsErrorText(it) }
}

// ── Script parameters ────────────────────────────────────────────────

private val PARAM_TYPE_LABELS = mapOf(ScriptParamType.STRING to "Text", ScriptParamType.INT to "Number", ScriptParamType.BOOL to "Yes/No")

/** The parameters of a script. A parameter is identified by its (unique) name, so renaming goes through the store's validation. */
@Composable
internal fun ParamsEditor(params: List<ScriptParam>, enabled: Boolean, onChange: (List<ScriptParam>) -> StoreResult<*>) {
    var error by remember { mutableStateOf<String?>(null) }
    val apply: (List<ScriptParam>) -> Unit = { error = onChange(it).userMessage() }
    TestsSectionTitle("Parameters") {
        if (enabled) {
            AppButton(
                ADD_ITEM_LABEL,
                onClick = { apply(params + ScriptParam(uniqueIdentifier(NEW_PARAM_BASE, params.map { it.name }))) }, variant = ButtonVariant.Ghost,
            )
        }
    }
    if (params.isEmpty()) TestsHint("None. Parameters reach the command as environment variables of the same name.")
    ReorderableColumn(
        items = params,
        idOf = { it.name },
        onMove = { name, to -> apply(params.moveById(name, to) { it.name }) },
        reorderEnabled = enabled,
        rowGap = 4.dp,
    ) { param, row ->
        val replace: (ScriptParam) -> StoreResult<*> = { replacement -> onChange(params.map { if (it.name == param.name) replacement else it }) }
        ReorderRowCard(row, enabled, onRemove = { apply(params.filterNot { it.name == param.name }) }) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    CommitTextField(param.name, { replace(param.copy(name = it.trim())) }, Modifier.weight(1f), enabled, "name", mono = true)
                    TestsDropdown(
                        selectedLabel = PARAM_TYPE_LABELS.getValue(param.type),
                        options = ScriptParamType.entries,
                        optionLabel = { PARAM_TYPE_LABELS.getValue(it) },
                        onSelect = { replace(param.copy(type = it)) },
                        enabled = enabled,
                        menuWidth = TYPE_MENU_WIDTH,
                        isSelected = { it == param.type },
                    )
                    Row(
                        Modifier.clickable(enabled = enabled) { replace(param.copy(required = !param.required)) },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        CompactCheckBox(param.required, onToggle = { replace(param.copy(required = !param.required)) }, enabled = enabled)
                        AppText("Required", color = tc().ts, fontSize = 11.sp)
                    }
                    CommitTextField(
                        param.defaultValue.orEmpty(),
                        { replace(param.copy(defaultValue = it.ifBlank { null })) },
                        Modifier.weight(1f),
                        enabled,
                        "default",
                    )
                }
                CommitTextField(param.description, { replace(param.copy(description = it)) }, enabled = enabled, placeholder = "description")
            }
        }
    }
    error?.let { TestsErrorText(it) }
}

// ── Allowed tools ────────────────────────────────────────────────────

/** The lane tools an agent may use in a case: all of them (null) or an explicit checklist. */
@Composable
internal fun AllowedToolsEditor(allowed: Set<String>?, enabled: Boolean, onChange: (Set<String>?) -> StoreResult<*>) {
    val tc = tc()
    val library = LocalTestsUi.current.library
    var error by remember { mutableStateOf<String?>(null) }
    val scriptTools = library.scripts.filter { it.permission != ScriptPermission.SETUP_TEARDOWN_ONLY }.map { it.toolName }
    val tools = RESERVED_SCRIPT_TOOL_NAMES.toList() + scriptTools
    val apply: (Set<String>?) -> Unit = { error = onChange(it).userMessage() }
    TestsSectionTitle("Allowed tools")
    CheckRow(checked = allowed == null, onToggle = { apply(if (allowed == null) tools.toSet() else null) }, enabled = enabled) {
        AppText("All tools", color = tc.tx, fontSize = 12.sp)
    }
    if (allowed != null) {
        val extra = allowed.filterNot { it in tools }.sorted()
        FlowRow(
            Modifier.padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            (tools + extra).forEach { name ->
                val on = name in allowed
                Row(
                    Modifier.clickable(enabled = enabled) { apply(if (on) allowed - name else allowed + name) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    CompactCheckBox(on, onToggle = { apply(if (on) allowed - name else allowed + name) }, enabled = enabled)
                    AppText(if (name in extra) "$name (missing)" else name, color = tc.ts, fontSize = 11.sp, fontFamily = MONO)
                }
            }
        }
        if (allowed.isEmpty()) TestsHint("No tools: the agent can only observe.", Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
    }
    error?.let { TestsErrorText(it, Modifier.padding(horizontal = 12.dp)) }
}
