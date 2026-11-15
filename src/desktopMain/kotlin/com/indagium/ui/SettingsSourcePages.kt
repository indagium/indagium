@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.indagium.ui

import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.model.CrashCategory
import com.indagium.model.CustomIssueRule
import com.indagium.model.SourceLogConfiguration
import com.indagium.model.SourceWrapperRule
import java.io.File
import java.util.UUID

// Settings › Issue categories and Settings › Source code: the same editors as before (rule drafts with Save, source folders, the editor
// command, logging configurations), laid out as groups of rows. The editors' own inner lists keep their tinted boxes.

private val RULE_ACTIONS_WIDTH = 102.dp

// The row descriptions the search index shares (SettingsSearchIndex.kt), so a result reads exactly like the row it points at.
internal const val ISSUE_CATEGORIES_HINT = "Each enabled regex is matched against a log tag or message and adds a clickable anchor to Issues. " +
    "It does not change stack-trace folding or built-in crash detection."
internal const val SOURCE_REGISTER_HINT =
    "Point Indagium at your project's source folders, then right-click a log line → “Show in code” to see the code that logged it."
internal const val SOURCE_OPEN_COMMAND_HINT = "The editor “Show in code” opens at the logged line: the first installed one, or your own command " +
    "with {file} and {line}."
internal const val SOURCE_OPEN_COMMAND_DETAIL = SOURCE_OPEN_COMMAND_HINT +
    " There is no fallback to the system's default app, which cannot jump to the line."
internal const val SOURCE_DISCOVER_WRAPPERS_HINT =
    "While reindexing, follow simple wrapper methods that delegate to Log.* or Timber, one hop deep."
internal const val SOURCE_DISCOVER_WRAPPERS_DETAIL = "While reindexing, follow simple wrapper methods that delegate to Log.* or Timber, one " +
    "interface or implementation hop deep. Runs for every registered folder; ambiguous or multi-level wrappers are skipped."
internal const val SOURCE_LOG_CONFIGURATIONS_HINT = "Assign module-specific wrapper rules to registered source folders."

/** Test tags of the Issue categories and Source code controls that have no tag of their own. */
internal object SourcePageTags {
    const val DISCOVER_WRAPPERS = "setting-discover-log-wrappers"
    const val EDITOR_RESCAN = "editor-rescan"
    const val REGISTER_SOURCE = "source-register"
    const val ADD_CATEGORY = "issue-category-add"
    const val SAVE_CATEGORIES = "issue-category-save"
    const val CATEGORY_SWITCH_PREFIX = "issue-category-switch-"
}

private fun customIssueRulesValidation(rules: List<CustomIssueRule>): String? {
    val builtInNames = CrashCategory.entries.map { it.name.lowercase() }.toSet() +
        setOf("all", "crashes", "anrs", "fatal exceptions", "exceptions", "others")
    rules.forEachIndexed { index, rule ->
        if (rule.name.isBlank()) return "Category ${index + 1} needs a name."
        if (rule.name.trim().lowercase() in builtInNames) return "Category ${index + 1} conflicts with a built-in Issues category."
        if (rule.regex.isBlank()) return "Category ${index + 1} needs a regex."
        runCatching { Regex(rule.regex) }.exceptionOrNull()?.let { return "Category ${index + 1}: ${it.message ?: "invalid regex"}" }
    }
    if (rules.groupingBy { it.name.trim().lowercase() }.eachCount().any { it.value > 1 }) {
        return "Category names must be unique."
    }
    return null
}

/** Settings › Issue categories: the custom regex categories that add clickable anchors to Issues, edited as drafts and saved together. */
@Composable
internal fun IssueCategoriesSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val tc = tc()
    var drafts by remember { mutableStateOf(state.settings.customIssueRules) }
    val validation = customIssueRulesValidation(drafts)
    val dirty = drafts != state.settings.customIssueRules
    SettingsGroup(
        "Categories",
        trailing = {
            SettingsButton(
                "Add category",
                onClick = { drafts = drafts + CustomIssueRule(UUID.randomUUID().toString(), "", "") },
                variant = ButtonVariant.Secondary,
                modifier = Modifier.testTag(SourcePageTags.ADD_CATEGORY),
            )
        },
    ) {
        SettingsNoteRow(ISSUE_CATEGORIES_HINT, anchor = "Custom issue categories")
        if (drafts.isEmpty()) {
            SettingsEmptyState("No custom issue categories yet.", asRow = true)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // The column names only head a list that has rows.
                // The same inset as the list box under it (its 8dp padding and 1dp border, and the scrollbar's lane), so the names sit over their fields.
                Row(Modifier.fillMaxWidth().padding(start = 9.dp, end = 21.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText("Category", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(0.8f))
                    AppText("Tag or message regex", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1.2f))
                    Spacer(Modifier.width(RULE_ACTIONS_WIDTH))
                }
                SettingsScrollableRows {
                    drafts.forEachIndexed { index, rule ->
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsTextField(
                                rule.name,
                                { value -> drafts = drafts.mapIndexed { i, current -> if (i == index) current.copy(name = value) else current } },
                                Modifier.weight(0.8f),
                                placeholder = "Network timeouts",
                            )
                            SettingsTextField(
                                rule.regex,
                                { value -> drafts = drafts.mapIndexed { i, current -> if (i == index) current.copy(regex = value) else current } },
                                Modifier.weight(1.2f),
                                placeholder = "timeout\\\\s+after\\\\s+\\\\d+ms",
                            )
                            SettingsToggle(
                                checked = rule.enabled,
                                label = "Category ${index + 1} enabled",
                                onChange = { enabled ->
                                    drafts = drafts.mapIndexed { i, current -> if (i == index) current.copy(enabled = enabled) else current }
                                },
                                reclaimFocus = reclaimFocus,
                                testTag = "${SourcePageTags.CATEGORY_SWITCH_PREFIX}$index",
                            )
                            SettingsButton(
                                "Remove",
                                onClick = { drafts = drafts.filterIndexed { i, _ -> i != index } },
                                variant = ButtonVariant.Secondary,
                                isDanger = true,
                            )
                        }
                    }
                }
            }
        }
        validation?.let { SettingsNoteRow(it, error = true) }
        // The form saves only on this button (the page guard and the draft stay as they were); it is the one Primary action of the page.
        SettingsActionRow {
            if (dirty) SettingsButton("Discard", onClick = { drafts = state.settings.customIssueRules }, variant = ButtonVariant.Secondary)
            SettingsButton(
                "Save changes",
                onClick = { state.updateSettings { it.copy(customIssueRules = drafts.map { rule -> rule.copy(name = rule.name.trim()) }) } },
                variant = ButtonVariant.Primary,
                enabled = dirty && validation == null,
                modifier = Modifier.testTag(SourcePageTags.SAVE_CATEGORIES),
            )
        }
    }
}

/** Settings › Source code: the registered source folders, the editor "Show in code" opens, wrapper discovery and logging configurations. */
@Composable
internal fun SourceCodeSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    // The "N files changed — reindex recommended" hint stats every indexed file, so it is computed off the UI thread
    // (AppState.refreshChangedFileCounts) and only re-checked when this page is shown, never per recomposition: on a
    // network-mounted source folder that froze the dialog.
    LaunchedEffect(state.settings.sourceFolders) { state.refreshChangedFileCounts() }
    // Scans lazily, the first time this page is composed (not at AppState construction): a subprocess probe per catalog candidate is
    // wasted work for a session that never opens Settings. Re-entering after a scan is cached (detectedEditors != null) is a no-op;
    // Rescan is the only way to force a fresh probe, e.g. after installing an editor mid-session.
    LaunchedEffect(Unit) {
        if (state.detectedEditors == null) state.rescanEditors()
    }
    SettingsGroup(
        "Source folders",
        modifier = Modifier.settingsAnchor("Register source code"),
        trailing = {
            SettingsButton("Register source code", onClick = { state.pickSourceFolder() }, modifier = Modifier.testTag(SourcePageTags.REGISTER_SOURCE))
        },
    ) {
        SettingsNoteRow(SOURCE_REGISTER_HINT)
        if (state.settings.sourceFolders.isEmpty()) {
            SettingsEmptyState("No folders registered yet.", asRow = true)
        } else {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                SettingsScrollableRows {
                    state.settings.sourceFolders.forEach { path -> SourceFolderRow(state, path) }
                }
            }
        }
    }
    SettingsGroup("Opening code") {
        Column(Modifier.fillMaxWidth()) {
            SettingsRow("Open command", SOURCE_OPEN_COMMAND_HINT, detail = SOURCE_OPEN_COMMAND_DETAIL) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(SETTINGS_FIELD_SHORT)) { EditorChoiceDropdown(state, reclaimFocus) }
                    SettingsButton(
                        "Rescan",
                        onClick = { state.rescanEditors() },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.testTag(SourcePageTags.EDITOR_RESCAN),
                    )
                }
            }
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) { EditorChoiceDetail(state) }
        }
    }
    SettingsGroup("Log wrappers") {
        SettingsRow("Discover simple custom log wrappers", SOURCE_DISCOVER_WRAPPERS_HINT, detail = SOURCE_DISCOVER_WRAPPERS_DETAIL) {
            SettingsToggle(
                checked = state.settings.sourceAutoDiscoveryEnabled,
                label = "Discover simple custom log wrappers",
                onChange = { next -> state.updateSettings { it.copy(sourceAutoDiscoveryEnabled = next) } },
                reclaimFocus = reclaimFocus,
                testTag = SourcePageTags.DISCOVER_WRAPPERS,
            )
        }
    }
    SourceLoggingConfigurations(state)
}

private fun editorChoiceLabel(choice: String): String = when (choice) {
    "", "auto" -> "Automatic"
    "custom" -> "Custom command…"
    else -> EDITOR_CATALOG.find { it.id == choice }?.displayName ?: "Automatic"
}

// The settings.editorChoice dropdown: "auto" (also stored as ""), every detected editor's preset id, then "custom".
@Composable
private fun EditorChoiceDropdown(state: AppState, onInteraction: () -> Unit) {
    val choice = state.settings.editorChoice
    val options = listOf("auto") + (state.detectedEditors?.map { it.first.id } ?: emptyList()) + "custom"
    SettingsDropdown(
        options = options,
        selected = if (choice == "") "auto" else choice,
        label = ::editorChoiceLabel,
        onSelect = { picked -> state.updateSettings { it.copy(editorChoice = picked) } },
        testTag = "editor-choice-dropdown",
        modifier = Modifier.fillMaxWidth(),
        minWidth = 0.dp,
        optionNote = { if (it == "auto") "first installed app" else null },
        onInteraction = onInteraction,
    )
}

// Below the dropdown: the custom-command field when "Custom command…" is chosen, or a read-only
// hint showing what Automatic/a named app resolves to right now (per detectedEditors' latest scan).
@Composable
private fun EditorChoiceDetail(state: AppState) {
    val tc = tc()
    val choice = state.settings.editorChoice
    val detected = state.detectedEditors
    when (choice) {
        "custom" -> SettingsTextField(
            state.settings.editorCommand,
            { value -> state.updateSettings { it.copy(editorCommand = value) } },
            Modifier.fillMaxWidth(),
            placeholder = "idea --line {line} {file}",
        )
        "", "auto" -> when {
            detected == null -> AppText("Detecting installed editors…", color = tc.ts, fontSize = 11.sp)
            detected.isEmpty() -> AppText(
                "No known editor detected on this machine. Install one, or pick Custom command… to type your own.",
                color = tc.ts,
                fontSize = 11.sp,
                maxLines = 2,
            )
            else -> {
                val (preset, template) = detected.first()
                ResolvedCommandLine("Will use ${preset.displayName}:", template)
            }
        }
        else -> when {
            detected == null -> AppText("Detecting installed editors…", color = tc.ts, fontSize = 11.sp)
            else -> {
                val resolved = detected.find { it.first.id == choice }
                if (resolved != null) {
                    ResolvedCommandLine(null, resolved.second)
                } else {
                    val name = EDITOR_CATALOG.find { it.id == choice }?.displayName ?: "This app"
                    AppText(
                        "$name is no longer detected. Pick another option, or Custom command… to type your own.",
                        color = tc.ts,
                        fontSize = 11.sp,
                        maxLines = 2,
                    )
                }
            }
        }
    }
}

/**
 * The command an editor choice resolves to, on one line like every Settings path: an optional [prefix] in the interface font, then the command in
 * mono, cut in the middle when it does not fit (never mid-token across two lines); the whole command shows in a hover hint.
 */
@Composable
private fun ResolvedCommandLine(prefix: String?, command: String) {
    val tc = tc()
    TooltipArea(
        tooltip = { ToolbarTooltip(command, maxLines = 4) },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (prefix != null) AppText(prefix, color = tc.ts, fontSize = 11.sp, maxLines = 1)
            SettingsPathText(command, Modifier.weight(1f))
        }
    }
}

@Composable
private fun SourceLoggingConfigurations(state: AppState) {
    val tc = tc()
    var editingId by remember { mutableStateOf<String?>(null) }
    var editingIsNew by remember { mutableStateOf(false) }
    val configurations = state.settings.sourceLogConfigurations
    val editing = configurations.firstOrNull { it.id == editingId }

    SettingsGroup(
        "Logging configurations",
        trailing = {
            SettingsButton(
                "Add configuration",
                onClick = {
                    val id = UUID.randomUUID().toString()
                    state.saveSourceLogConfiguration(SourceLogConfiguration(id = id, name = "Logging configuration"))
                    editingId = id
                    editingIsNew = true
                },
                variant = ButtonVariant.Secondary,
            )
        },
    ) {
        SettingsNoteRow(SOURCE_LOG_CONFIGURATIONS_HINT, anchor = "Logging configurations")
        if (editing != null) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp)) {
                SourceLoggingConfigurationEditor(
                    state = state,
                    configuration = editing,
                    onClose = {
                        if (editingIsNew) editingId?.let(state::deleteSourceLogConfiguration)
                        editingId = null
                        editingIsNew = false
                    },
                    onSaved = { editingIsNew = false },
                )
            }
        } else if (configurations.isEmpty()) {
            SettingsEmptyState("No custom logging configurations yet.", asRow = true)
        } else {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                configurations.forEach { configuration ->
                    Row(
                        Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).padding(horizontal = 8.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            AppText(configuration.name, color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            AppText(
                                "${configuration.wrapperRules.size} wrapper rules",
                                color = tc.ts,
                                fontSize = 10.sp,
                            )
                        }
                        SettingsButton("Edit", onClick = { editingId = configuration.id; editingIsNew = false })
                        SettingsButton(
                            "Duplicate",
                            onClick = {
                                val id = UUID.randomUUID().toString()
                                state.saveSourceLogConfiguration(configuration.copy(id = id, name = "${configuration.name} copy"))
                                editingId = id
                                editingIsNew = true
                            },
                        )
                        SettingsButton(
                            "Delete",
                            onClick = {
                                state.deleteSourceLogConfiguration(configuration.id)
                                if (editingId == configuration.id) {
                                    editingId = null
                                    editingIsNew = false
                                }
                            },
                            isDanger = true,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceLoggingConfigurationEditor(
    state: AppState,
    configuration: SourceLogConfiguration,
    onClose: () -> Unit,
    onSaved: () -> Unit,
) {
    val tc = tc()
    var draft by remember(configuration.id) { mutableStateOf(configuration) }
    val assignedFolders = state.settings.sourceFolderConfigurationIds
    Column(
        Modifier.fillMaxWidth().background(tc.p2, CORNER_SM).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Edit logging configuration", color = tc.tx, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            SettingsButton("Cancel", onClick = onClose)
        }
        SettingsTextField(
            draft.name,
            { draft = draft.copy(name = it) },
            Modifier.fillMaxWidth(),
            placeholder = "Configuration name",
        )
        AppText("Source folders", color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        if (state.settings.sourceFolders.isEmpty()) {
            AppText("Register source folders above to assign this configuration.", color = tc.ts, fontSize = 11.sp)
        } else {
            state.settings.sourceFolders.forEach { folder ->
                val path = File(folder).absolutePath
                SettingsCheckbox(
                    checked = configuration.id in assignedFolders[path].orEmpty(),
                    label = truncatePathForDisplay(folder),
                    onChange = {
                        val current = assignedFolders[path].orEmpty().toSet()
                        val next = if (configuration.id in current) current - configuration.id else current + configuration.id
                        state.assignSourceLogConfigurations(path, next.toList())
                    },
                    monospace = true,
                    labelColor = tc.ts,
                    fontSize = 10.sp,
                )
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Wrapper rules", color = tc.ts, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            SettingsButton(
                "Add rule",
                onClick = { draft = draft.copy(wrapperRules = draft.wrapperRules + SourceWrapperRule("", "")) },
            )
        }
        draft.wrapperRules.forEachIndexed { index, rule ->
            Column(Modifier.fillMaxWidth().border(0.5.dp, tc.br, CORNER_SM).padding(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    SettingsTextField(
                        rule.ownerType,
                        { value -> draft = draft.replaceWrapperRule(index, rule.copy(ownerType = value)) },
                        Modifier.weight(1f),
                        placeholder = "Owner/type, e.g. com.example.Telemetry or Telemetry",
                    )
                    SettingsTextField(
                        rule.methodName,
                        { value -> draft = draft.replaceWrapperRule(index, rule.copy(methodName = value)) },
                        Modifier.width(100.dp),
                        placeholder = "Method",
                    )
                    SettingsButton(
                        "Remove",
                        onClick = { draft = draft.copy(wrapperRules = draft.wrapperRules.filterIndexed { ruleIndex, _ -> ruleIndex != index }) },
                        isDanger = true,
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SettingsTextField(
                        rule.tagArgumentIndex.toString(),
                        { value -> value.toIntOrNull()?.let { draft = draft.replaceWrapperRule(index, rule.copy(tagArgumentIndex = it)) } },
                        Modifier.weight(1f),
                        placeholder = "Tag argument",
                    )
                    SettingsTextField(
                        rule.messageArgumentIndex.toString(),
                        { value -> value.toIntOrNull()?.let { draft = draft.replaceWrapperRule(index, rule.copy(messageArgumentIndex = it)) } },
                        Modifier.weight(1f),
                        placeholder = "Message argument",
                    )
                    SettingsTextField(
                        rule.throwableArgumentIndex?.toString().orEmpty(),
                        { value -> draft = draft.replaceWrapperRule(index, rule.copy(throwableArgumentIndex = value.toIntOrNull())) },
                        Modifier.weight(1f),
                        placeholder = "Throwable (optional)",
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            SettingsButton(
                "Save configuration",
                onClick = { state.saveSourceLogConfiguration(draft); onSaved() },
                variant = ButtonVariant.Primary,
            )
        }
    }
}

private fun SourceLogConfiguration.replaceWrapperRule(index: Int, rule: SourceWrapperRule): SourceLogConfiguration =
    copy(wrapperRules = wrapperRules.mapIndexed { ruleIndex, current -> if (ruleIndex == index) rule else current })

// Indexing is per folder (AppState.reindexSources/sourceIndexStatusForFolder) — each registered
// folder gets its own status line and its own Reindex button, rather than one aggregate action
// that rescans every folder together.
@Composable
private fun SourceFolderRow(state: AppState, path: String) {
    val tc = tc()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TooltipArea(
                tooltip = {
                    Box(
                        Modifier
                            .background(tc.p2, RoundedCornerShape(4.dp))
                            .border(0.5.dp, tc.br, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        AppText(path, color = tc.tx, fontSize = 11.sp, fontFamily = MONO)
                    }
                },
                modifier = Modifier.weight(1f),
            ) {
                SettingsPathText(path)
            }
            SettingsButton(
                "Reindex",
                onClick = { state.reindexSources(path) },
                variant = ButtonVariant.Secondary,
                enabled = File(path).absolutePath !in state.indexingFolders,
            )
            if (File(path).absolutePath in state.indexingFolders) {
                SettingsButton(
                    "Cancel",
                    onClick = { state.cancelReindexSources(path) },
                    variant = ButtonVariant.Secondary,
                    enabled = File(path).absolutePath !in state.cancelledIndexingFolders,
                )
            }
            SettingsButton(
                "Info",
                onClick = { state.sourceFolderInfoEditorTarget = path },
                variant = ButtonVariant.Secondary,
            )
            SettingsButton("Remove", onClick = { state.removeSourceFolder(path) }, variant = ButtonVariant.Secondary)
        }
        val folderStatus = state.sourceIndexStatusForFolder(path)
        AppText(
            if (folderStatus.builtAt == 0L) {
                "Not indexed yet"
            } else if (folderStatus.configurationChanged) {
                "Configuration changed — reindex required"
            } else {
                "${folderStatus.fileCount} files · ${folderStatus.siteCount} call sites · " +
                    "indexed ${sourceIndexAgeLabel(folderStatus.builtAt)}"
            },
            color = tc.ts,
            fontSize = 11.sp,
            fontFamily = UI,
        )
        val configurationNames = state.sourceConfigurationsForFolder(path).map { it.name }
        if (configurationNames.isNotEmpty()) {
            AppText(
                "Configurations: ${configurationNames.joinToString()}",
                color = tc.ts,
                fontSize = 11.sp,
                fontFamily = UI,
            )
        } else if (state.settings.sourceLogConfigurations.isNotEmpty()) {
            AppText(
                "No logging configuration assigned — assign one below, then reindex",
                color = tc.ac,
                fontSize = 11.sp,
                fontFamily = UI,
            )
        }
        if (folderStatus.changedFileCount > 0) {
            AppText(
                "${folderStatus.changedFileCount} files changed — reindex recommended",
                color = tc.ac,
                fontSize = 11.sp,
                fontFamily = UI,
            )
        }
    }
}
