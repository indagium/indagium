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
import androidx.compose.ui.text.style.TextOverflow
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

private val EDITOR_DROPDOWN_WIDTH = 220.dp
private val RULE_ACTIONS_WIDTH = 102.dp

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
    SettingsGroup("Categories") {
        SettingsStackedRow(
            "Custom issue categories",
            "Each enabled regex is matched against a log tag or message and adds a clickable anchor to Issues. " +
                "It does not change stack-trace folding or built-in crash detection.",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppText("Category", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(0.8f))
                    AppText("Tag or message regex", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1.2f))
                    Spacer(Modifier.width(RULE_ACTIONS_WIDTH))
                }
                if (drafts.isEmpty()) {
                    SettingsEmptyState("No custom issue categories yet.")
                } else {
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
                                AppButton(
                                    "Remove",
                                    onClick = { drafts = drafts.filterIndexed { i, _ -> i != index } },
                                    variant = ButtonVariant.Secondary,
                                    isDanger = true,
                                )
                            }
                        }
                    }
                }
                validation?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 2) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppButton(
                        "Add category",
                        onClick = { drafts = drafts + CustomIssueRule(UUID.randomUUID().toString(), "", "") },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.testTag(SourcePageTags.ADD_CATEGORY),
                    )
                    AppButton(
                        "Save changes",
                        onClick = { state.updateSettings { it.copy(customIssueRules = drafts.map { rule -> rule.copy(name = rule.name.trim()) }) } },
                        enabled = dirty && validation == null,
                        modifier = Modifier.testTag(SourcePageTags.SAVE_CATEGORIES),
                    )
                    if (dirty) {
                        AppButton("Discard", onClick = { drafts = state.settings.customIssueRules }, variant = ButtonVariant.Secondary)
                    }
                }
            }
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
    SettingsGroup("Source folders") {
        Column(Modifier.fillMaxWidth()) {
            SettingsRow(
                "Registered folders",
                "Point Indagium at your project's source folder(s), then right-click a log line → \"Show in code\" to see the code that logged it.",
                anchor = "Register source code",
            ) {
                AppButton("Register source code", onClick = { state.pickSourceFolder() }, modifier = Modifier.testTag(SourcePageTags.REGISTER_SOURCE))
            }
            Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                if (state.settings.sourceFolders.isEmpty()) {
                    SettingsEmptyState("No folders registered yet.")
                } else {
                    SettingsScrollableRows {
                        state.settings.sourceFolders.forEach { path -> SourceFolderRow(state, path) }
                    }
                }
            }
        }
    }
    SettingsGroup("Opening code") {
        Column(Modifier.fillMaxWidth()) {
            SettingsRow(
                "Open command",
                "The editor \"Show in code\" opens a file in at the logged line. Automatic uses the first installed app; Custom command… takes " +
                    "a raw command with {file} and {line}. There is no fallback to the system's default app, which cannot jump to the line.",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(EDITOR_DROPDOWN_WIDTH)) { EditorChoiceDropdown(state, reclaimFocus) }
                    AppButton(
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
        SettingsRow(
            "Discover simple custom log wrappers",
            "While reindexing, follow simple wrapper methods that delegate to Log.* or Timber, one interface/implementation hop deep. " +
                "Runs for every registered folder; ambiguous or multi-level wrappers are skipped.",
        ) {
            SettingsToggle(
                checked = state.settings.sourceAutoDiscoveryEnabled,
                label = "Discover simple custom log wrappers",
                onChange = { next -> state.updateSettings { it.copy(sourceAutoDiscoveryEnabled = next) } },
                reclaimFocus = reclaimFocus,
                testTag = SourcePageTags.DISCOVER_WRAPPERS,
            )
        }
        SourceLoggingConfigurations(state)
    }
}

private fun editorChoiceLabel(choice: String): String = when (choice) {
    "", "auto" -> "Automatic (first installed)"
    "custom" -> "Custom command…"
    else -> EDITOR_CATALOG.find { it.id == choice }?.displayName ?: "Automatic (first installed)"
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
            detected == null -> AppText("Detecting installed editors…", color = tc.td, fontSize = 10.sp)
            detected.isEmpty() -> AppText(
                "No known editor detected on this machine — install one above, or pick Custom " +
                    "command… to type your own.",
                color = tc.td,
                fontSize = 10.sp,
                maxLines = 2,
            )
            else -> {
                val (preset, template) = detected.first()
                AppText(
                    "Will use ${preset.displayName}: $template",
                    color = tc.td,
                    fontSize = 10.sp,
                    fontFamily = MONO,
                )
            }
        }
        else -> when {
            detected == null -> AppText("Detecting installed editors…", color = tc.td, fontSize = 10.sp)
            else -> {
                val resolved = detected.find { it.first.id == choice }
                if (resolved != null) {
                    AppText(resolved.second, color = tc.td, fontSize = 11.sp, fontFamily = MONO)
                } else {
                    val name = EDITOR_CATALOG.find { it.id == choice }?.displayName ?: "This app"
                    AppText(
                        "$name is no longer detected — pick another option above, or Custom command… " +
                            "to type your own.",
                        color = tc.td,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
            }
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

    Column(Modifier.fillMaxWidth()) {
        SettingsRow("Logging configurations", "Assign module-specific wrapper rules to registered source folders.") {
            AppButton(
                "Add configuration",
                onClick = {
                    val id = UUID.randomUUID().toString()
                    state.saveSourceLogConfiguration(SourceLogConfiguration(id = id, name = "Logging configuration"))
                    editingId = id
                    editingIsNew = true
                },
                variant = ButtonVariant.Secondary,
            )
        }
        Column(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            if (editing != null) {
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
            } else if (configurations.isEmpty()) {
                SettingsEmptyState("No custom logging configurations.")
            } else {
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
                                color = tc.td,
                                fontSize = 10.sp,
                            )
                        }
                        AppButton("Edit", onClick = { editingId = configuration.id; editingIsNew = false })
                        AppButton(
                            "Duplicate",
                            onClick = {
                                val id = UUID.randomUUID().toString()
                                state.saveSourceLogConfiguration(configuration.copy(id = id, name = "${configuration.name} copy"))
                                editingId = id
                                editingIsNew = true
                            },
                        )
                        AppButton(
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
            AppButton("Cancel", onClick = onClose)
        }
        SettingsTextField(
            draft.name,
            { draft = draft.copy(name = it) },
            Modifier.fillMaxWidth(),
            placeholder = "Configuration name",
        )
        AppText("Source folders", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        if (state.settings.sourceFolders.isEmpty()) {
            AppText("Register source folders above to assign this configuration.", color = tc.td, fontSize = 10.sp)
        } else {
            state.settings.sourceFolders.forEach { folder ->
                val path = File(folder).absolutePath
                CheckRow(
                    checked = configuration.id in assignedFolders[path].orEmpty(),
                    onToggle = {
                        val current = assignedFolders[path].orEmpty().toSet()
                        val next = if (configuration.id in current) current - configuration.id else current + configuration.id
                        state.assignSourceLogConfigurations(path, next.toList())
                    },
                ) {
                    AppText(truncatePathForDisplay(folder), color = tc.ts, fontSize = 10.sp, fontFamily = MONO, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            AppText("Wrapper rules", color = tc.td, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            AppButton(
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
                    AppButton(
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
            AppButton(
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
                AppText(
                    truncatePathForDisplay(path),
                    color = tc.ts,
                    fontSize = 11.sp,
                    fontFamily = MONO,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            AppButton(
                "Reindex",
                onClick = { state.reindexSources(path) },
                variant = ButtonVariant.Secondary,
                enabled = File(path).absolutePath !in state.indexingFolders,
            )
            if (File(path).absolutePath in state.indexingFolders) {
                AppButton(
                    "Cancel",
                    onClick = { state.cancelReindexSources(path) },
                    variant = ButtonVariant.Secondary,
                    enabled = File(path).absolutePath !in state.cancelledIndexingFolders,
                )
            }
            AppButton(
                "Info",
                onClick = { state.sourceFolderInfoEditorTarget = path },
                variant = ButtonVariant.Secondary,
            )
            AppButton("Remove", onClick = { state.removeSourceFolder(path) }, variant = ButtonVariant.Secondary)
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
            color = tc.td,
            fontSize = 10.sp,
            fontFamily = UI,
        )
        val configurationNames = state.sourceConfigurationsForFolder(path).map { it.name }
        if (configurationNames.isNotEmpty()) {
            AppText(
                "Configurations: ${configurationNames.joinToString()}",
                color = tc.td,
                fontSize = 10.sp,
                fontFamily = UI,
            )
        } else if (state.settings.sourceLogConfigurations.isNotEmpty()) {
            AppText(
                "No logging configuration assigned — assign one below, then reindex",
                color = tc.ac,
                fontSize = 10.sp,
                fontFamily = UI,
            )
        }
        if (folderStatus.changedFileCount > 0) {
            AppText(
                "${folderStatus.changedFileCount} files changed — reindex recommended",
                color = tc.ac,
                fontSize = 10.sp,
                fontFamily = UI,
            )
        }
    }
}
