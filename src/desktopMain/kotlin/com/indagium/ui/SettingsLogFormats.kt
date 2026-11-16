@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.indagium.model.*
import com.indagium.utils.ParsedLog
import com.indagium.utils.validateLogFormatProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

private val LOG_FORMAT_ROLE_FIELDS = listOf(
    LogFieldRole.TIMESTAMP,
    LogFieldRole.SEVERITY,
    LogFieldRole.COMPONENT,
    LogFieldRole.MESSAGE,
    LogFieldRole.PROCESS_ID,
    LogFieldRole.THREAD_ID,
    LogFieldRole.HOST,
    LogFieldRole.SERVICE,
    LogFieldRole.CONTAINER,
    LogFieldRole.STREAM,
    LogFieldRole.REQUEST_ID,
    LogFieldRole.TRACE_ID,
    LogFieldRole.HTTP_METHOD,
    LogFieldRole.HTTP_PATH,
    LogFieldRole.SOURCE,
)

private data class LogFormatPreview(
    val profile: LogFormatProfile,
    val sample: String,
    val parsed: ParsedLog,
)

private const val DEFAULT_PROFILE_REGEX = "^(\\S+)\\s+(\\w+)\\s+(.*)$"

private fun newLogFormatDraft(): LogFormatProfile = LogFormatProfile(
    id = "profile-${UUID.randomUUID()}",
    name = "New log format",
    description = "",
    engine = LogFormatEngine.PLAIN_TEXT,
)

/** Settings editor for parser profiles, bounded previews, and the profile import/export lifecycle. */
@Composable
internal fun LogFormatSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val profiles = state.availableLogFormatProfiles()
    if (profiles.isEmpty()) return

    var selectedId by remember { mutableStateOf(profiles.first().id) }
    var draft by remember { mutableStateOf(profiles.first()) }
    val selectedProfile = profiles.firstOrNull { it.id == selectedId } ?: draft
    var sample by remember(selectedId) { mutableStateOf(defaultProfileSample(selectedProfile.engine)) }
    var extensionText by remember(selectedId, profiles) {
        mutableStateOf(selectedProfile.detection.extensions.joinToString(", "))
    }
    var headerText by remember(selectedId, profiles) {
        mutableStateOf(selectedProfile.detection.headerContains.joinToString(", "))
    }
    var contentRegex by remember(selectedId, profiles) {
        mutableStateOf(selectedProfile.detection.contentRegex.orEmpty())
    }
    var localError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LogFormatPreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewRequestId by remember { mutableStateOf(0) }
    var samplePreviewJob by remember { mutableStateOf<Job?>(null) }
    var importConflictUpdate by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canEdit = !selectedProfile.readOnly
    val canMapFields = canEdit && supportsProfileFields(draft.engine)
    val canConfigureTime = canEdit && supportsProfileTimestamps(draft.engine)
    val canConfigureSeverity = canEdit && draft.engine !in setOf(
        LogFormatEngine.LOGCAT,
        LogFormatEngine.DLT,
        LogFormatEngine.PLAIN_TEXT,
    )
    val customProfiles = profiles.filterNot { it.readOnly }

    fun clearPreview() {
        previewRequestId += 1
        samplePreviewJob?.cancel()
        samplePreviewJob = null
        previewLoading = false
        preview = null
        localError = null
    }

    fun editDraft(transform: (LogFormatProfile) -> LogFormatProfile) {
        draft = transform(draft)
        clearPreview()
    }

    fun updateAttribute(index: Int, transform: (LogFormatAttribute) -> LogFormatAttribute) {
        editDraft { current ->
            current.copy(attributes = current.attributes.replaceAt(index, transform))
        }
    }

    fun updateIssueRule(index: Int, transform: (CustomIssueRule) -> CustomIssueRule) {
        editDraft { current ->
            current.copy(issueRules = current.issueRules.replaceAt(index, transform))
        }
    }

    fun buildDraft(): LogFormatProfile {
        val extensions = splitList(extensionText).map { it.removePrefix(".").lowercase() }
        val candidate = draft.copy(
            detection = draft.detection.copy(
                extensions = extensions,
                headerContains = splitList(headerText),
                contentRegex = contentRegex.trim().ifBlank { null },
                // The association needs an extension; emptying the list switches it off instead of failing to save.
                alwaysUseForExtensions = draft.detection.alwaysUseForExtensions && extensions.isNotEmpty(),
            ),
            fieldMappings = draft.fieldMappings.filterValues(String::isNotBlank).mapValues { it.value.trim() },
            attributes = draft.attributes.map { attribute ->
                attribute.copy(
                    id = attribute.id.trim(),
                    label = attribute.label.trim(),
                    source = attribute.source.trim(),
                )
            },
            issueRules = draft.issueRules.map {
                it.copy(id = it.id.trim(), name = it.name.trim(), regex = it.regex.trim())
            },
        )
        require(candidate.attributes.all { it.id.isNotBlank() && it.label.isNotBlank() && it.source.isNotBlank() }) {
            "Complete each extra attribute's name, label, and source field."
        }
        require(candidate.issueRules.all { it.id.isNotBlank() && it.name.isNotBlank() && it.regex.isNotBlank() }) {
            "Complete each issue rule's ID, name, and regular expression."
        }
        require(candidate.engine != LogFormatEngine.REGEX || candidate.regexPattern.isNotBlank()) {
            "Enter a record regex for the regular-expression engine."
        }
        validateLogFormatProfile(candidate)
        return candidate
    }

    fun chooseProfile(profile: LogFormatProfile) {
        clearPreview()
        selectedId = profile.id
        draft = profile
        sample = defaultProfileSample(profile.engine)
        extensionText = profile.detection.extensions.joinToString(", ")
        headerText = profile.detection.headerContains.joinToString(", ")
        contentRegex = profile.detection.contentRegex.orEmpty()
    }

    SettingsGroup("Log format profiles", modifier = Modifier.settingsAnchor("Log format profiles")) {
        SettingsStackedRow("Profile", "Built-ins are read-only. Duplicate one to make an editable copy.") {
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsDropdown(
                    options = profiles,
                    selected = selectedProfile,
                    label = LogFormatProfile::name,
                    onSelect = ::chooseProfile,
                    testTag = "settings-log-format-profile",
                    modifier = Modifier.weight(1f),
                    minWidth = 190.dp,
                    optionTag = { "settings-log-format-${it.id}" },
                    optionNote = { if (it.readOnly) "Built-in · read-only" else "Custom · revision ${it.revision}" },
                )
                SettingsButton(
                    "New profile",
                    {
                        val emptyProfile = newLogFormatDraft()
                        selectedId = emptyProfile.id
                        draft = emptyProfile
                        sample = defaultProfileSample(emptyProfile.engine)
                        clearPreview()
                        reclaimFocus()
                    },
                    modifier = Modifier.testTag("settings-log-format-new"),
                )
                SettingsButton(
                    "Duplicate",
                    {
                        val duplicate = state.duplicateLogFormatProfile(selectedId)
                            ?: selectedProfile.copy(
                                id = "profile-${UUID.randomUUID()}",
                                name = "${selectedProfile.name} copy",
                                revision = 1,
                                readOnly = false,
                            )
                        chooseProfile(duplicate)
                        reclaimFocus()
                    },
                    modifier = Modifier.testTag("settings-log-format-duplicate"),
                )
            }
        }
        SettingsStackedRow(
            "Library actions",
            "Import a single profile or bundle. Export the current profile or choose a bundle.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsButton(
                    "Import…",
                    {
                        state.chooseLogFormatProfilesToImport { file, result ->
                            result.fold(
                                onSuccess = {
                                    localError = null
                                    state.clearLogFormatProfileError()
                                },
                                onFailure = { localError = it.message ?: "Could not read ${file.name}." },
                            )
                        }
                        reclaimFocus()
                    },
                    modifier = Modifier.testTag("settings-log-format-import"),
                )
                SettingsButton(
                    "Export current…",
                    { state.exportLogFormatProfiles(listOf(selectedProfile)); reclaimFocus() },
                    modifier = Modifier.testTag("settings-log-format-export-current"),
                )
                SettingsButton(
                    "Export bundle…",
                    { exportDialogOpen = true; reclaimFocus() },
                    modifier = Modifier.testTag("settings-log-format-export-bundle"),
                )
                SettingsButton(
                    "Delete…",
                    { deleteConfirmationOpen = true; reclaimFocus() },
                    isDanger = true,
                    enabled = canEdit && profiles.any { it.id == selectedId },
                    modifier = Modifier.testTag("settings-log-format-delete"),
                )
            }
        }
        val pendingImport = state.pendingLogFormatImport
        if (pendingImport != null) {
            val file = pendingImport.file
            val imported = pendingImport.profiles
            val customById = customProfiles.associateBy { it.id }
            val builtInIds = profiles.filter { it.readOnly }.mapTo(mutableSetOf()) { it.id }
            val plural = if (imported.size == 1) "" else "s"
            val importSummary = "${file.name} · ${imported.size} profile$plural. Review conflicts before importing."
            SettingsStackedRow("Import preview", importSummary) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    imported.take(12).forEach { candidate ->
                        val existing = customById[candidate.id]
                        val outcome = when {
                            candidate.id in builtInIds -> "Built-in ID · always imported as a copy"
                            existing != null && importConflictUpdate ->
                                "Will update ${existing.name} (revision ${existing.revision})"
                            existing != null -> "ID matches ${existing.name} · will import as a copy"
                            else -> "New profile · will be added"
                        }
                        Column(Modifier.fillMaxWidth().background(tc().p2, RoundedCornerShape(5.dp)).padding(8.dp)) {
                            AppText(candidate.name, color = tc().tx, fontSize = 12.sp)
                            AppText("${candidate.engine} · ${candidate.id}", color = tc().ts, fontSize = 10.sp)
                            AppText(
                                outcome,
                                color = if (existing != null && importConflictUpdate) tc().ac else tc().ts,
                                fontSize = 10.sp,
                            )
                        }
                    }
                    if (imported.size > 12) {
                        AppText("${imported.size - 12} more profiles", color = tc().ts, fontSize = 10.sp)
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsDropdown(
                            options = listOf(false, true),
                            selected = importConflictUpdate,
                            label = { if (it) "Update matching custom profiles" else "Import matches as copies" },
                            onSelect = { importConflictUpdate = it; reclaimFocus() },
                            testTag = "settings-log-format-import-conflicts",
                            minWidth = 230.dp,
                        )
                        SettingsButton(
                            "Import profiles",
                            {
                                state.applyImportedLogFormatProfiles(imported, importConflictUpdate).fold(
                                    onSuccess = { applied ->
                                        applied.firstOrNull()?.let(::chooseProfile)
                                        localError = null
                                        state.clearLogFormatProfileError()
                                    },
                                    onFailure = { localError = it.message ?: "Could not import these log formats." },
                                )
                                reclaimFocus()
                            },
                            variant = ButtonVariant.Primary,
                            modifier = Modifier.testTag("settings-log-format-import-confirm"),
                        )
                        SettingsButton(
                            "Cancel",
                            { state.cancelLogFormatProfileImport(); localError = null; reclaimFocus() },
                            modifier = Modifier.testTag("settings-log-format-import-cancel"),
                        )
                    }
                }
            }
        }
        state.logFormatProfileError?.let { message ->
            SettingsStackedRow("Profile error") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AppText(message, color = DANGER_RED, fontSize = 11.sp, maxLines = 3, modifier = Modifier.weight(1f))
                    SettingsButton("Dismiss", state::clearLogFormatProfileError, modifier = Modifier.testTag("settings-log-format-error-dismiss"))
                }
            }
        }
    }

    // Only for a text format the user can edit: it writes the regex engine's rule from a sample line.
    if (canEdit && draft.engine in setOf(LogFormatEngine.REGEX, LogFormatEngine.PLAIN_TEXT)) {
        LogFormatColumnBuilderPanel(selectedId, reclaimFocus) { format, sampleLine ->
            editDraft { current ->
                current.copy(
                    engine = LogFormatEngine.REGEX,
                    regexPattern = format.regexPattern,
                    fieldMappings = format.fieldMappings,
                    attributes = format.attributes,
                    visibleColumns = format.visibleColumns,
                    timestampKind = format.timestampKind,
                    epochUnit = format.epochUnit ?: current.epochUnit,
                )
            }
            sample = sampleLine
        }
    }

    SettingsGroup("Definition") {
        SettingsStackedRow("Name", enabled = canEdit) {
            SettingsTextField(
                draft.name,
                { value -> editDraft { profile -> profile.copy(name = value) } },
                placeholder = "My log format",
                testTag = "settings-log-format-name",
            )
        }
        SettingsStackedRow("Description", enabled = canEdit) {
            SettingsTextField(
                draft.description,
                { value -> editDraft { profile -> profile.copy(description = value) } },
                placeholder = "What this profile parses",
                testTag = "settings-log-format-description",
            )
        }
        SettingsStackedRow("Parser engine", "Selects how records and their fields are read.", enabled = canEdit) {
            SettingsDropdown(
                options = LogFormatEngine.entries,
                selected = draft.engine,
                label = ::engineLabel,
                onSelect = { engine ->
                    val mappings = engineDefaultMappings(engine)
                    editDraft {
                        it.copy(
                            engine = engine,
                            fieldMappings = mappings,
                            regexPattern = if (engine == LogFormatEngine.REGEX && it.regexPattern.isBlank()) {
                                DEFAULT_PROFILE_REGEX
                            } else {
                                it.regexPattern
                            },
                        )
                    }
                    sample = defaultProfileSample(engine)
                },
                testTag = "settings-log-format-engine",
                minWidth = 250.dp,
            )
        }
        SettingsStackedRow("File extensions", "Optional suffix hints, without a leading dot.", enabled = canEdit) {
            SettingsTextField(
                extensionText,
                { extensionText = it; clearPreview() },
                placeholder = "log, jsonl",
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings-log-format-extensions",
            )
        }
        val hasExtensions = splitList(extensionText).isNotEmpty()
        SettingsRow(
            "Always open these extensions with this format",
            "Skips content detection for files with these extensions. Needs at least one extension.",
            enabled = canEdit && hasExtensions,
        ) {
            SettingsSwitch(
                checked = draft.detection.alwaysUseForExtensions && hasExtensions,
                onCheckedChange = { value ->
                    editDraft { it.copy(detection = it.detection.copy(alwaysUseForExtensions = value)) }
                },
                label = "Always open these extensions with this format",
                enabled = canEdit && hasExtensions,
                testTag = "settings-log-format-always-extensions",
            )
        }
        SettingsStackedRow(
            "Header text",
            "All listed strings must occur in the file sample for this profile to be a detection candidate.",
            enabled = canEdit,
        ) {
            SettingsTextField(
                headerText,
                { headerText = it; clearPreview() },
                placeholder = "timestamp, level",
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings-log-format-header",
            )
        }
        SettingsStackedRow(
            "Content detection regex",
            "Optional safe regex checked against a bounded file sample.",
            enabled = canEdit,
        ) {
            SettingsTextField(
                contentRegex,
                { contentRegex = it; clearPreview() },
                placeholder = "^\\{.*\\}\u0024",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings-log-format-content-regex",
            )
        }
        SettingsStackedRow(
            "Record regex",
            "The parsing rule. Each (bracket pair) is one column, numbered from 1; the fields below refer to those numbers. " +
                "\"Set up from a sample line\" above writes it for you.",
            enabled = canEdit && draft.engine == LogFormatEngine.REGEX,
        ) {
            SettingsTextField(
                draft.regexPattern,
                { value -> editDraft { profile -> profile.copy(regexPattern = value) } },
                placeholder = "^(\\S+)\\s+(\\w+)\\s+(.*)$",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings-log-format-regex",
            )
        }
        SettingsStackedRow(
            "Multiline record start",
            "A matching line starts a record; following lines stay with it until the next match.",
            enabled = canEdit && draft.engine == LogFormatEngine.REGEX,
        ) {
            SettingsTextField(
                draft.multilineStartPattern,
                { value -> editDraft { profile -> profile.copy(multilineStartPattern = value) } },
                placeholder = "^\\d{4}-",
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
                testTag = "settings-log-format-multiline",
            )
        }
        SettingsStackedRow(
            "CSV delimiter and header",
            "Use one character as the delimiter and choose whether row one contains field names.",
            enabled = canEdit && draft.engine == LogFormatEngine.CSV,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsTextField(
                    draft.delimiter.toString(),
                    { value ->
                        value.singleOrNull()?.let { delimiter ->
                            editDraft { current -> current.copy(delimiter = delimiter) }
                        }
                    },
                    placeholder = ",",
                    modifier = Modifier.width(80.dp),
                    testTag = "settings-log-format-delimiter",
                )
                SettingsCheckbox(
                    draft.hasHeader,
                    "First row contains field names",
                    { checked ->
                        editDraft { profile -> profile.copy(hasHeader = checked) }
                        reclaimFocus()
                    },
                    enabled = canEdit,
                    testTag = "settings-log-format-has-header",
                )
            }
        }
    }

    SettingsGroup("Fields and time") {
        if (!supportsProfileFields(draft.engine)) {
            SettingsStackedRow(
                "Field mapping",
                "This engine reads its fields directly, so custom field mappings do not apply.",
            ) {
                AppText(nativeFieldDescription(draft.engine), color = tc().ts, fontSize = 11.sp)
            }
        }
        engineFieldRoles(draft.engine).forEach { role ->
            SettingsRow(
                roleLabel(role),
                fieldMappingHint(draft.engine),
                enabled = canMapFields,
            ) {
                SettingsTextField(
                    value = draft.fieldMappings[role].orEmpty(),
                    onValueChange = { value ->
                        editDraft { current ->
                            val mappings = current.fieldMappings.toMutableMap()
                            if (value.isBlank()) mappings.remove(role) else mappings[role] = value.trim()
                            current.copy(fieldMappings = mappings)
                        }
                    },
                    placeholder = automaticFieldHint(draft.engine, role),
                    testTag = "settings-log-format-field-${role.name.lowercase()}",
                )
            }
        }
        SettingsStackedRow(
            "Timestamp interpretation",
            if (supportsProfileTimestamps(draft.engine)) "Choose how timestamp values become source-clock time."
            else "This engine reads its native timestamp format directly.",
            enabled = canConfigureTime,
        ) {
            SettingsDropdown(
                options = ProfileTimestampKind.entries,
                selected = draft.timestampKind,
                label = ::timestampKindLabel,
                onSelect = { kind -> editDraft { profile -> profile.copy(timestampKind = kind) } },
                testTag = "settings-log-format-time-kind",
            )
        }
        SettingsStackedRow(
            "Epoch unit",
            "Used when timestamps are epoch or elapsed-time numbers.",
            enabled = canConfigureTime,
        ) {
            SettingsDropdown(
                options = ProfileEpochUnit.entries,
                selected = draft.epochUnit,
                label = ::epochUnitLabel,
                onSelect = { unit -> editDraft { profile -> profile.copy(epochUnit = unit) } },
                testTag = "settings-log-format-epoch-unit",
            )
        }
        SettingsRow("Timestamp pattern", "Pattern for custom date and time values.", enabled = canConfigureTime) {
            SettingsTextField(
                draft.timestampPattern,
                { value -> editDraft { profile -> profile.copy(timestampPattern = value) } },
                placeholder = "yyyy-MM-dd HH:mm:ss.SSS",
                monospace = true,
                testTag = "settings-log-format-time-pattern",
            )
        }
        SettingsRow(
            "Timezone",
            "A blank value keeps a local source clock without assigning UTC.",
            enabled = canConfigureTime,
        ) {
            SettingsTextField(
                draft.timezone,
                { value -> editDraft { profile -> profile.copy(timezone = value) } },
                placeholder = "Europe/Kyiv",
                testTag = "settings-log-format-timezone",
            )
        }
        SettingsRow(
            "Relative clock ID",
            "Name the elapsed-time clock only when records share a known clock.",
            enabled = canConfigureTime && draft.timestampKind == ProfileTimestampKind.RELATIVE,
        ) {
            SettingsTextField(
                draft.relativeClock,
                { value -> editDraft { profile -> profile.copy(relativeClock = value) } },
                placeholder = "device-boot-123",
                testTag = "settings-log-format-relative-clock",
            )
        }
        SettingsStackedRow(
            "Severity mapping",
            if (canConfigureSeverity) "Map source values to the six normalized severity levels."
            else "This engine reads severity directly and does not use custom severity mappings.",
            enabled = canConfigureSeverity,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                draft.severityMapping.entries.forEachIndexed { index, (source, target) ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SettingsTextField(
                            source,
                            { value ->
                                editDraft { current ->
                                    val mappings = current.severityMapping.toMutableMap()
                                    mappings.remove(source)
                                    if (value.isNotBlank()) mappings[value.trim().uppercase()] = target
                                    current.copy(severityMapping = mappings)
                                }
                            },
                            placeholder = "Source value",
                            modifier = Modifier.width(150.dp),
                            testTag = "settings-log-format-severity-source-$index",
                        )
                        AppText("maps to", color = tc().ts, fontSize = 11.sp)
                        SettingsDropdown(
                            options = LogLevel.entries,
                            selected = LogLevel.from(target),
                            label = { "${it.label} (${it.key})" },
                            onSelect = { level ->
                                editDraft { current ->
                                    current.copy(severityMapping = current.severityMapping + (source to level.key))
                                }
                            },
                            testTag = "settings-log-format-severity-level-$index",
                            minWidth = 140.dp,
                        )
                        SettingsButton(
                            "Remove",
                            {
                                editDraft { current ->
                                    current.copy(severityMapping = current.severityMapping - source)
                                }
                            },
                            modifier = Modifier.testTag("settings-log-format-severity-remove-$index"),
                        )
                    }
                }
                SettingsButton(
                    "Add severity mapping",
                    {
                        val commonSources = listOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR", "FATAL")
                        val nextSource = commonSources.firstOrNull { it !in draft.severityMapping }
                            ?: "LEVEL${draft.severityMapping.size + 1}"
                        editDraft { it.copy(severityMapping = it.severityMapping + (nextSource to LogLevel.I.key)) }
                        reclaimFocus()
                    },
                    enabled = canEdit,
                    modifier = Modifier.testTag("settings-log-format-severity-add"),
                )
            }
        }
        SettingsStackedRow(
            "Extra attributes",
            if (supportsProfileFields(draft.engine)) "Add typed fields such as container ID, request ID, or HTTP path."
            else "This engine does not expose additional source fields.",
            enabled = canMapFields,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                draft.attributes.forEachIndexed { index, attribute ->
                    Column(
                        Modifier.fillMaxWidth().background(tc().p2, RoundedCornerShape(6.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsTextField(
                                attribute.id,
                                { value -> updateAttribute(index) { it.copy(id = value) } },
                                placeholder = "field_id",
                                modifier = Modifier.width(120.dp),
                                testTag = "settings-log-format-attribute-id-$index",
                            )
                            SettingsTextField(
                                attribute.label,
                                { value -> updateAttribute(index) { it.copy(label = value) } },
                                placeholder = "Column label",
                                modifier = Modifier.weight(1f),
                                testTag = "settings-log-format-attribute-label-$index",
                            )
                            SettingsTextField(
                                attribute.source,
                                { value -> updateAttribute(index) { it.copy(source = value) } },
                                placeholder = "source field",
                                modifier = Modifier.weight(1f),
                                testTag = "settings-log-format-attribute-source-$index",
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppText("Value type", color = tc().ts, fontSize = 11.sp)
                            SettingsDropdown(
                                options = LogProfileValueType.entries,
                                selected = attribute.type,
                                label = ::valueTypeLabel,
                                onSelect = { type -> updateAttribute(index) { it.copy(type = type) } },
                                testTag = "settings-log-format-attribute-type-$index",
                                minWidth = 140.dp,
                            )
                            SettingsCheckbox(
                                attribute.visibleByDefault,
                                "Show as a column",
                                { visible ->
                                    updateAttribute(index) { it.copy(visibleByDefault = visible) }
                                    reclaimFocus()
                                },
                                enabled = canEdit,
                                testTag = "settings-log-format-attribute-visible-$index",
                            )
                            Spacer(Modifier.weight(1f))
                            SettingsButton(
                                "Remove attribute",
                                {
                                    editDraft { current ->
                                        current.copy(
                                            attributes = current.attributes.filterIndexed { attrIndex, _ ->
                                                attrIndex != index
                                            },
                                        )
                                    }
                                },
                                isDanger = true,
                                modifier = Modifier.testTag("settings-log-format-attribute-remove-$index"),
                            )
                        }
                    }
                }
                SettingsButton(
                    "Add attribute",
                    {
                        val next = draft.attributes.size + 1
                        editDraft {
                            it.copy(
                                attributes = it.attributes + LogFormatAttribute(
                                    "field_$next",
                                    "Field $next",
                                    "",
                                    visibleByDefault = false,
                                ),
                            )
                        }
                        reclaimFocus()
                    },
                    modifier = Modifier.testTag("settings-log-format-attribute-add"),
                )
            }
        }
        SettingsStackedRow(
            "Default columns",
            "The fields a row shows in the log view, in this order. Hiding a field does not stop it being parsed or filtered.",
            enabled = canEdit,
        ) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LOG_FORMAT_ROLE_FIELDS.forEach { role ->
                    SettingsCheckbox(
                        checked = role in draft.visibleColumns,
                        label = roleLabel(role),
                        onChange = { visible ->
                            editDraft { current ->
                                val columns = if (visible) {
                                    current.visibleColumns + role
                                } else {
                                    current.visibleColumns - role
                                }
                                current.copy(visibleColumns = columns)
                            }
                            reclaimFocus()
                        },
                        enabled = canEdit,
                        testTag = "settings-log-format-column-${role.name.lowercase()}",
                    )
                }
            }
        }
        SettingsStackedRow(
            "Compatible analyzers",
            "Choose analyses that understand the records this profile produces.",
            enabled = canEdit,
        ) {
            SettingsCheckbox(
                checked = "android-crash" in draft.compatibleAnalyzers,
                label = "Android crash analysis",
                onChange = { enabled ->
                    editDraft { current ->
                        val analyzers = current.compatibleAnalyzers.toMutableSet()
                        if (enabled) analyzers += "android-crash" else analyzers -= "android-crash"
                        current.copy(compatibleAnalyzers = analyzers)
                    }
                    reclaimFocus()
                },
                enabled = canEdit,
                testTag = "settings-log-format-analyzer-android-crash",
            )
        }
        SettingsStackedRow(
            "Issue rules",
            "Each rule adds a named safe regex match to this profile's issue analysis.",
            enabled = canEdit,
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                draft.issueRules.forEachIndexed { index, rule ->
                    Column(
                        Modifier.fillMaxWidth().background(tc().p2, RoundedCornerShape(6.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SettingsTextField(
                                rule.id,
                                { value -> updateIssueRule(index) { it.copy(id = value) } },
                                placeholder = "rule_id",
                                modifier = Modifier.width(120.dp),
                                testTag = "settings-log-format-issue-id-$index",
                            )
                            SettingsTextField(
                                rule.name,
                                { value -> updateIssueRule(index) { it.copy(name = value) } },
                                placeholder = "Rule name",
                                modifier = Modifier.weight(1f),
                                testTag = "settings-log-format-issue-name-$index",
                            )
                            SettingsCheckbox(
                                rule.enabled,
                                "Enabled",
                                { enabled ->
                                    updateIssueRule(index) { it.copy(enabled = enabled) }
                                    reclaimFocus()
                                },
                                enabled = canEdit,
                                testTag = "settings-log-format-issue-enabled-$index",
                            )
                            SettingsButton(
                                "Remove",
                                {
                                    editDraft { current ->
                                        current.copy(
                                            issueRules = current.issueRules.filterIndexed { ruleIndex, _ ->
                                                ruleIndex != index
                                            },
                                        )
                                    }
                                },
                                isDanger = true,
                                modifier = Modifier.testTag("settings-log-format-issue-remove-$index"),
                            )
                        }
                        SettingsTextField(
                            rule.regex,
                            { value -> updateIssueRule(index) { it.copy(regex = value) } },
                            placeholder = "(?i)fatal error",
                            monospace = true,
                            modifier = Modifier.fillMaxWidth(),
                            testTag = "settings-log-format-issue-regex-$index",
                        )
                    }
                }
                SettingsButton(
                    "Add issue rule",
                    {
                        val next = draft.issueRules.size + 1
                        editDraft { current ->
                            current.copy(
                                issueRules = current.issueRules + CustomIssueRule("rule_$next", "Rule $next", ""),
                            )
                        }
                        reclaimFocus()
                    },
                    modifier = Modifier.testTag("settings-log-format-issue-add"),
                )
            }
        }
    }

    SettingsGroup("Preview and save") {
        SettingsStackedRow(
            "Sample records",
            if (draft.engine == LogFormatEngine.DLT) {
                "DLT records are binary. Open a DLT tab and preview its source instead."
            } else {
                "Try up to 2,000 lines from the sample. Parsing runs in the background; previews do not modify settings."
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.engine == LogFormatEngine.DLT) {
                    AppText("Text samples cannot represent DLT records.", color = tc().ts, fontSize = 10.sp)
                } else {
                    MultilineSettingsField(
                        sample,
                        { value -> sample = value; clearPreview() },
                        "Paste sample records…",
                        "settings-log-format-sample",
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (draft.engine != LogFormatEngine.DLT) {
                        SettingsButton(
                            if (previewLoading) "Previewing…" else "Preview sample",
                            {
                                if (!previewLoading) {
                                    clearPreview()
                                    val candidate = runCatching(::buildDraft).getOrElse {
                                        localError = it.message ?: "Complete the profile fields before previewing."
                                        return@SettingsButton
                                    }
                                    val sampleText = sample
                                    val requestId = previewRequestId
                                    previewLoading = true
                                    samplePreviewJob = scope.launch {
                                        val result = withContext(Dispatchers.Default) {
                                            runCatching { state.previewLogFormatProfile(candidate, sampleText) }
                                        }
                                        if (requestId == previewRequestId) {
                                            result.fold(
                                                onSuccess = { preview = LogFormatPreview(candidate, sampleText, it) },
                                                onFailure = { localError = it.message ?: "Could not preview this profile." },
                                            )
                                            previewLoading = false
                                            samplePreviewJob = null
                                        }
                                    }
                                }
                                reclaimFocus()
                            },
                            enabled = !previewLoading,
                            modifier = Modifier.testTag("settings-log-format-preview"),
                        )
                    }
                    SettingsButton(
                        "Preview current tab",
                        {
                            clearPreview()
                            val activeTabId = state.activeTabId.takeIf(String::isNotBlank)
                            if (activeTabId == null) {
                                localError = "Open a log tab before previewing its source."
                            } else {
                                val candidate = runCatching(::buildDraft).getOrElse {
                                    localError = it.message ?: "Complete the profile fields before previewing."
                                    return@SettingsButton
                                }
                                val requestId = previewRequestId
                                previewLoading = true
                                state.previewCurrentLogFormatProfile(activeTabId, candidate) { result ->
                                    if (requestId == previewRequestId) {
                                        result.fold(
                                            onSuccess = { preview = LogFormatPreview(candidate, sample, it) },
                                            onFailure = { localError = it.message ?: "Could not preview the active tab." },
                                        )
                                        previewLoading = false
                                    }
                                }
                            }
                            reclaimFocus()
                        },
                        enabled = state.activeTabId.isNotBlank() && !previewLoading,
                        modifier = Modifier.testTag("settings-log-format-preview-current"),
                    )
                    SettingsButton(
                        "Save profile",
                        {
                            runCatching { state.saveLogFormatProfile(buildDraft()).getOrThrow() }
                                .onSuccess { saved ->
                                    chooseProfile(saved)
                                    state.clearLogFormatProfileError()
                                }
                                .onFailure { localError = it.message ?: "Could not save this profile." }
                            reclaimFocus()
                        },
                        variant = ButtonVariant.Primary,
                        enabled = canEdit && !previewLoading,
                        modifier = Modifier.testTag("settings-log-format-save"),
                    )
                }
                localError?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 3) }
                val currentDraft = runCatching(::buildDraft).getOrNull()
                preview?.takeIf { it.profile == currentDraft && it.sample == sample }?.let { current ->
                    ProfilePreviewResults(current.parsed)
                }
            }
        }
    }

    if (exportDialogOpen) {
        LogFormatExportDialog(
            profiles = profiles,
            initiallySelected = setOf(selectedId),
            onDismiss = { exportDialogOpen = false },
            onExport = { selected ->
                state.exportLogFormatProfiles(selected)
                exportDialogOpen = false
            },
        )
    }
    if (deleteConfirmationOpen) {
        SettingsConfirmDialog(
            title = "Delete ${selectedProfile.name}?",
            message = "This removes the profile from Settings. Open tabs keep using the format they were parsed with.",
            onDismissRequest = { deleteConfirmationOpen = false },
        ) {
            SettingsDialogButton("Delete profile", primary = true, danger = true) {
                if (state.deleteLogFormatProfile(selectedId)) {
                    state.availableLogFormatProfiles().firstOrNull()?.let(::chooseProfile)
                }
                deleteConfirmationOpen = false
                reclaimFocus()
            }
            SettingsDialogButton("Cancel") { deleteConfirmationOpen = false; reclaimFocus() }
        }
    }
}

@Composable
private fun ProfilePreviewResults(parsed: ParsedLog) {
    val tc = tc()
    Column(
        Modifier
            .fillMaxWidth()
            .background(tc.p2, RoundedCornerShape(6.dp))
            .border(1.dp, tc.br, RoundedCornerShape(6.dp))
            .padding(9.dp)
            .testTag("settings-log-format-preview-results"),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        val countLabel = if (parsed.entries.size == 1) "record" else "records"
        AppText(
            "${parsed.entries.size} parsed $countLabel · ${parsed.profile?.name.orEmpty()}",
            color = tc.tx,
            fontSize = 12.sp,
        )
        if (parsed.diagnostics.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                parsed.diagnostics.take(4).forEach { AppText(it, color = tc.ts, fontSize = 10.sp, maxLines = 2) }
            }
        }
        parsed.entries.take(4).forEach { entry ->
            Column(
                Modifier.fillMaxWidth().background(tc.p, RoundedCornerShape(4.dp)).padding(6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppText(
                        entry.ts.ifBlank { "No timestamp" },
                        color = tc.ts,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                    )
                    AppText(entry.displaySeverityText, color = tc.ac, fontSize = 10.sp)
                    AppText(entry.tag.ifBlank { "No component" }, color = tc.tx, fontSize = 10.sp)
                }
                AppText(entry.msg.ifBlank { "No message" }, color = tc.tx, fontSize = 11.sp, maxLines = 2)
                if (entry.profileFields.isNotEmpty()) {
                    AppText(
                        entry.profileFields.entries.take(6).joinToString("  ·  ") { "${it.key}: ${it.value}" },
                        color = tc.ts,
                        fontSize = 10.sp,
                        maxLines = 2,
                    )
                }
            }
        }
        if (parsed.capabilities.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                AppText("Available capabilities", color = tc.tx, fontSize = 11.sp)
                parsed.capabilities.toSortedMap(compareBy { it.ordinal }).forEach { (capability, status) ->
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Top) {
                        AppText(capabilityLabel(capability), color = tc.tx, fontSize = 10.sp)
                        AppText("· ${availabilityLabel(status.availability)}", color = tc.tx, fontSize = 10.sp)
                        AppText(status.reason, color = tc.ts, fontSize = 10.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogFormatExportDialog(
    profiles: List<LogFormatProfile>,
    initiallySelected: Set<String>,
    onDismiss: () -> Unit,
    onExport: (List<LogFormatProfile>) -> Unit,
) {
    val tc = tc()
    var selectedIds by remember { mutableStateOf(initiallySelected.intersect(profiles.map { it.id }.toSet())) }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            Modifier.width(520.dp).heightIn(max = 620.dp).background(tc.p, RoundedCornerShape(8.dp))
                .border(1.dp, tc.br, RoundedCornerShape(8.dp)).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            AppText("Export log format profiles", color = tc.tx, fontSize = 14.sp)
            AppText(
                "Choose the built-in and custom profiles to include in one versioned JSON bundle.",
                color = tc.ts,
                fontSize = 11.sp,
                maxLines = 2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsButton(
                    "Select all",
                    { selectedIds = profiles.map { it.id }.toSet() },
                    modifier = Modifier.testTag("settings-log-format-export-all"),
                )
                SettingsButton(
                    "Clear",
                    { selectedIds = emptySet() },
                    modifier = Modifier.testTag("settings-log-format-export-clear"),
                )
            }
            Column(
                Modifier.weight(1f, fill = false).fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                profiles.forEach { profile ->
                    SettingsCheckbox(
                        profile.id in selectedIds,
                        "${profile.name} · ${if (profile.readOnly) "built-in" else "custom"}",
                        { checked ->
                            selectedIds = if (checked) selectedIds + profile.id else selectedIds - profile.id
                        },
                        testTag = "settings-log-format-export-${profile.id}",
                    )
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                SettingsDialogButton("Export selected", primary = true, enabled = selectedIds.isNotEmpty()) {
                    onExport(profiles.filter { it.id in selectedIds })
                }
                SettingsDialogButton("Cancel", onClick = onDismiss)
            }
        }
    }
}

@Composable
private fun MultilineSettingsField(value: String, onChange: (String) -> Unit, placeholder: String, testTag: String) {
    val colors = tc()
    BasicTextField(
        value = value,
        onValueChange = onChange,
        modifier = Modifier.fillMaxWidth().heightIn(min = 86.dp, max = 170.dp)
            .background(colors.p2, RoundedCornerShape(6.dp)).padding(8.dp).testTag(testTag),
        textStyle = TextStyle(color = colors.tx, fontSize = 12.sp, fontFamily = FontFamily.Monospace),
        decorationBox = { inner ->
            if (value.isEmpty()) {
                AppText(placeholder, color = colors.td, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
            }
            inner()
        },
    )
}

private fun defaultProfileSample(engine: LogFormatEngine): String = when (engine) {
    LogFormatEngine.PLAIN_TEXT -> "Application started\nConnection established"
    LogFormatEngine.REGEX -> "2026-10-31T12:00:00Z INFO Network connected"
    LogFormatEngine.LOGFMT -> "time=2026-10-31T12:00:00Z level=INFO logger=app msg=started"
    LogFormatEngine.CRI -> "2026-10-31T12:00:00.123456789Z stdout F Application started"
    LogFormatEngine.JSON_LINES ->
        """{"timestamp":"2026-10-31T12:00:00Z","level":"INFO","logger":"app","message":"started"}""" +
            "\n" +
            """{"timestamp":"2026-10-31T12:00:01Z","level":"WARN","logger":"net","message":"retry"}"""
    LogFormatEngine.CSV -> "timestamp,level,logger,message\n2026-10-31T12:00:00Z,INFO,app,started"
    LogFormatEngine.SYSLOG_RFC5424 -> "<34>1 2026-10-31T12:00:00Z host app 123 ID47 [meta@1 x=\"y\"] started"
    LogFormatEngine.SYSLOG_RFC3164 -> "<34>Oct 31 12:00:00 host app[123]: started"
    LogFormatEngine.LOGCAT -> "10-31 12:00:00.123  123  124 I App: started"
    LogFormatEngine.DLT -> ""
}

private fun engineDefaultMappings(engine: LogFormatEngine): Map<LogFieldRole, String> = when (engine) {
    LogFormatEngine.REGEX -> mapOf(
        LogFieldRole.TIMESTAMP to "1",
        LogFieldRole.SEVERITY to "2",
        LogFieldRole.MESSAGE to "3",
    )
    LogFormatEngine.CSV -> mapOf(
        LogFieldRole.TIMESTAMP to "timestamp",
        LogFieldRole.SEVERITY to "level",
        LogFieldRole.COMPONENT to "logger",
        LogFieldRole.MESSAGE to "message",
    )
    LogFormatEngine.JSON_LINES -> mapOf(
        LogFieldRole.TIMESTAMP to "timestamp",
        LogFieldRole.SEVERITY to "level",
        LogFieldRole.COMPONENT to "logger",
        LogFieldRole.MESSAGE to "message",
    )
    LogFormatEngine.LOGFMT -> mapOf(
        LogFieldRole.TIMESTAMP to "time",
        LogFieldRole.SEVERITY to "level",
        LogFieldRole.COMPONENT to "logger",
        LogFieldRole.MESSAGE to "msg",
        LogFieldRole.PROCESS_ID to "pid",
        LogFieldRole.THREAD_ID to "tid",
        LogFieldRole.HOST to "hostname",
        LogFieldRole.SERVICE to "service",
        LogFieldRole.REQUEST_ID to "request_id",
        LogFieldRole.TRACE_ID to "trace_id",
        LogFieldRole.SOURCE to "source",
    )
    LogFormatEngine.CRI -> mapOf(
        LogFieldRole.TIMESTAMP to "time",
        LogFieldRole.STREAM to "stream",
        LogFieldRole.MESSAGE to "message",
    )
    LogFormatEngine.SYSLOG_RFC5424, LogFormatEngine.SYSLOG_RFC3164 -> mapOf(
        LogFieldRole.TIMESTAMP to "timestamp",
        LogFieldRole.SEVERITY to "severity",
        LogFieldRole.COMPONENT to "app",
        LogFieldRole.MESSAGE to "message",
        LogFieldRole.PROCESS_ID to "pid",
        LogFieldRole.HOST to "host",
        LogFieldRole.SERVICE to "app",
    )
    else -> emptyMap()
}

private fun engineFieldRoles(engine: LogFormatEngine): List<LogFieldRole> = when (engine) {
    LogFormatEngine.PLAIN_TEXT, LogFormatEngine.LOGCAT, LogFormatEngine.DLT -> emptyList()
    LogFormatEngine.SYSLOG_RFC5424, LogFormatEngine.SYSLOG_RFC3164 -> listOf(
        LogFieldRole.TIMESTAMP, LogFieldRole.SEVERITY, LogFieldRole.COMPONENT, LogFieldRole.MESSAGE,
        LogFieldRole.PROCESS_ID, LogFieldRole.HOST, LogFieldRole.SERVICE,
    )
    else -> LOG_FORMAT_ROLE_FIELDS
}

private fun supportsProfileFields(engine: LogFormatEngine): Boolean =
    engine !in setOf(LogFormatEngine.LOGCAT, LogFormatEngine.DLT, LogFormatEngine.PLAIN_TEXT)

private fun supportsProfileTimestamps(engine: LogFormatEngine): Boolean =
    engine !in setOf(LogFormatEngine.LOGCAT, LogFormatEngine.DLT, LogFormatEngine.PLAIN_TEXT)

private fun nativeFieldDescription(engine: LogFormatEngine): String = when (engine) {
    LogFormatEngine.LOGCAT -> "Logcat rows provide their standard timestamp, severity, component, process, " +
        "and thread fields."
    LogFormatEngine.DLT -> "DLT rows provide fields from the DLT record headers and payload."
    LogFormatEngine.PLAIN_TEXT -> "Each non-empty source line becomes one message."
    else -> "This engine reads its standard fields directly."
}

private fun fieldMappingHint(engine: LogFormatEngine): String = when (engine) {
    LogFormatEngine.REGEX -> "The number of the (bracket pair) in the record regex that holds this field."
    LogFormatEngine.CSV -> "Enter a CSV header name or a 0-based column number."
    LogFormatEngine.JSON_LINES -> "Enter a JSON field path. Blank uses common field-name aliases."
    else -> "Enter the source field name for this value."
}

private fun automaticFieldHint(engine: LogFormatEngine, role: LogFieldRole): String = when (engine) {
    LogFormatEngine.REGEX -> when (role) {
        LogFieldRole.TIMESTAMP -> "1"
        LogFieldRole.SEVERITY -> "2"
        LogFieldRole.MESSAGE -> "3"
        else -> "capture number"
    }
    LogFormatEngine.JSON_LINES -> when (role) {
        LogFieldRole.TIMESTAMP -> "timestamp"
        LogFieldRole.SEVERITY -> "level"
        LogFieldRole.COMPONENT -> "logger"
        LogFieldRole.MESSAGE -> "message"
        else -> "JSON path"
    }
    else -> "(automatic)"
}

private fun engineLabel(engine: LogFormatEngine): String = when (engine) {
    LogFormatEngine.LOGCAT -> "Android Logcat"
    LogFormatEngine.DLT -> "Automotive DLT"
    LogFormatEngine.PLAIN_TEXT -> "Plain text"
    LogFormatEngine.REGEX -> "Regular expression"
    LogFormatEngine.JSON_LINES -> "JSON Lines"
    LogFormatEngine.CSV -> "CSV / TSV"
    LogFormatEngine.SYSLOG_RFC5424 -> "Syslog RFC 5424"
    LogFormatEngine.SYSLOG_RFC3164 -> "Syslog RFC 3164"
    LogFormatEngine.LOGFMT -> "logfmt / Go slog"
    LogFormatEngine.CRI -> "Kubernetes CRI"
}

private fun timestampKindLabel(kind: ProfileTimestampKind): String = when (kind) {
    ProfileTimestampKind.AUTO -> "Detect automatically"
    ProfileTimestampKind.ISO_8601 -> "ISO 8601 date and time"
    ProfileTimestampKind.CUSTOM -> "Custom date and time"
    ProfileTimestampKind.EPOCH -> "Unix epoch"
    ProfileTimestampKind.TIME_OF_DAY -> "Time of day"
    ProfileTimestampKind.RELATIVE -> "Elapsed time"
}

private fun epochUnitLabel(unit: ProfileEpochUnit): String = when (unit) {
    ProfileEpochUnit.SECONDS -> "Seconds"
    ProfileEpochUnit.MILLIS -> "Milliseconds"
    ProfileEpochUnit.MICROS -> "Microseconds"
    ProfileEpochUnit.NANOS -> "Nanoseconds"
}

private fun valueTypeLabel(type: LogProfileValueType): String = when (type) {
    LogProfileValueType.TEXT -> "Text"
    LogProfileValueType.NUMBER -> "Number"
    LogProfileValueType.BOOLEAN -> "True / false"
    LogProfileValueType.TIMESTAMP -> "Timestamp"
}

private fun roleLabel(role: LogFieldRole): String = when (role) {
    LogFieldRole.TIMESTAMP -> "Timestamp"
    LogFieldRole.SEVERITY -> "Severity"
    LogFieldRole.COMPONENT -> "Component"
    LogFieldRole.MESSAGE -> "Message"
    LogFieldRole.PROCESS_ID -> "Process ID"
    LogFieldRole.THREAD_ID -> "Thread ID"
    LogFieldRole.HTTP_METHOD -> "HTTP method"
    LogFieldRole.HTTP_PATH -> "HTTP path"
    LogFieldRole.REQUEST_ID -> "Request ID"
    LogFieldRole.TRACE_ID -> "Trace ID"
    else -> role.name.lowercase().replace('_', ' ').replaceFirstChar(Char::uppercase)
}

private fun capabilityLabel(capability: LogCapability): String = capability.name.lowercase()
    .replace('_', ' ').replaceFirstChar(Char::uppercase)

private fun availabilityLabel(availability: CapabilityAvailability): String = availability.name.lowercase()
    .replaceFirstChar(Char::uppercase)

private fun splitList(text: String): List<String> = text.split(',').map(String::trim).filter(String::isNotBlank)

private fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> = mapIndexed { i, item ->
    if (i == index) transform(item) else item
}
