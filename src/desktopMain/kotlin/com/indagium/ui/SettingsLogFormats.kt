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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
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

private const val READ_DIRECTLY_BY_ENGINE = "Read directly by this engine."
private const val NO_EXTRA_SOURCE_FIELDS = "This engine does not expose additional source fields."

/** The engines whose "Lines that do not match" choice means something: they read records that can span lines. */
private val JOIN_LINES_ENGINES = setOf(
    LogFormatEngine.REGEX,
    LogFormatEngine.SYSLOG_RFC5424,
    LogFormatEngine.SYSLOG_RFC3164,
)

/** The one corner of the cards nested inside this page's rows: an import candidate, an attribute, an issue rule, the preview results. */
private val NESTED_CARD_SHAPE = RoundedCornerShape(5.dp)

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
    // Errors from Save, Import and Revert show under the profile header, where the button was pressed; errors from the
    // preview buttons show in the Preview group next to them.
    var localError by remember { mutableStateOf<String?>(null) }
    var previewError by remember { mutableStateOf<String?>(null) }
    var preview by remember { mutableStateOf<LogFormatPreview?>(null) }
    var previewLoading by remember { mutableStateOf(false) }
    var previewRequestId by remember { mutableStateOf(0) }
    var samplePreviewJob by remember { mutableStateOf<Job?>(null) }
    var importConflictUpdate by remember { mutableStateOf(false) }
    var exportDialogOpen by remember { mutableStateOf(false) }
    var deleteConfirmationOpen by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val canEdit = !selectedProfile.readOnly
    val customProfiles = profiles.filterNot { it.readOnly }

    fun clearPreview() {
        previewRequestId += 1
        samplePreviewJob?.cancel()
        samplePreviewJob = null
        previewLoading = false
        preview = null
        localError = null
        previewError = null
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

    // The stored profile behind the editor; null for a new profile that has not been saved yet.
    val savedProfile = profiles.firstOrNull { it.id == selectedId && !it.readOnly }
    // Compared field by field with what selecting the profile loads, so a freshly selected profile is never dirty.
    val dirty = savedProfile != null && (
        draft != savedProfile || detectionTextEdited(savedProfile, extensionText, headerText, contentRegex)
    )

    fun saveDraft() {
        runCatching { state.saveLogFormatProfile(buildDraft()).getOrThrow() }
            .onSuccess { saved ->
                chooseProfile(saved)
                state.clearLogFormatProfileError()
            }
            .onFailure { localError = it.message ?: "Could not save this profile." }
        reclaimFocus()
    }

    SettingsGroup("Log format profiles", modifier = Modifier.settingsAnchor("Log format profiles")) {
        SettingsStackedRow("Profile", "Built-ins are read-only. Duplicate one to edit it. Import and export are under ⋯.") {
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
                SettingsOverflowMenu(
                    items = listOf(
                        SettingsMenuItem("Import…", "settings-log-format-import") {
                            state.chooseLogFormatProfilesToImport { file, result ->
                                result.fold(
                                    onSuccess = {
                                        localError = null
                                        state.clearLogFormatProfileError()
                                    },
                                    onFailure = { localError = it.message ?: "Could not read ${file.name}." },
                                )
                            }
                        },
                        SettingsMenuItem("Export current…", "settings-log-format-export-current") {
                            state.exportLogFormatProfiles(listOf(selectedProfile))
                        },
                        SettingsMenuItem("Export bundle…", "settings-log-format-export-bundle") { exportDialogOpen = true },
                    ),
                    contentDescription = "Import and export",
                    testTag = "settings-log-format-library-menu",
                    onInteraction = reclaimFocus,
                )
            }
        }
        if (!canEdit) {
            BuiltInProfileHeader(selectedProfile) {
                state.duplicateLogFormatProfile(selectedId)?.let(::chooseProfile)
                reclaimFocus()
            }
        } else {
            CustomProfileHeader(
                name = draft.name,
                revision = savedProfile?.revision,
                dirty = dirty,
                saveEnabled = (savedProfile == null || dirty) && !previewLoading,
                onSave = ::saveDraft,
                onRevert = {
                    chooseProfile(savedProfile ?: profiles.first())
                    reclaimFocus()
                },
                menu = savedProfile?.let {
                    listOf(
                        SettingsMenuItem("Duplicate", "settings-log-format-duplicate") {
                            state.duplicateLogFormatProfile(selectedId)?.let(::chooseProfile)
                        },
                        SettingsMenuItem("Delete…", "settings-log-format-delete", danger = true) { deleteConfirmationOpen = true },
                    )
                }.orEmpty(),
                reclaimFocus = reclaimFocus,
            )
        }
        localError?.let { message ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                AppText(
                    message,
                    color = DANGER_RED,
                    fontSize = 11.sp,
                    maxLines = 3,
                    modifier = Modifier.testTag("settings-log-format-error"),
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
                        Column(Modifier.fillMaxWidth().background(tc().p2, NESTED_CARD_SHAPE).padding(8.dp)) {
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

    if (!canEdit) {
        BuiltInProfileSummary(selectedProfile)
    } else {
        SettingsGroup("Definition") {
            SettingsStackedRow("Name") {
                SettingsTextField(
                    draft.name,
                    { value -> editDraft { profile -> profile.copy(name = value) } },
                    placeholder = "My log format",
                    testTag = "settings-log-format-name",
                )
            }
            SettingsStackedRow("Description") {
                SettingsTextField(
                    draft.description,
                    { value -> editDraft { profile -> profile.copy(description = value) } },
                    placeholder = "What this profile parses",
                    testTag = "settings-log-format-description",
                )
            }
            SettingsStackedRow("Parser engine", "Selects how records and their fields are read.") {
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
            SettingsStackedRow("File extensions", "Optional suffix hints, without a leading dot.") {
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
                enabled = hasExtensions,
            ) {
                SettingsSwitch(
                    checked = draft.detection.alwaysUseForExtensions && hasExtensions,
                    onCheckedChange = { value ->
                        editDraft { it.copy(detection = it.detection.copy(alwaysUseForExtensions = value)) }
                    },
                    label = "Always open these extensions with this format",
                    enabled = hasExtensions,
                    testTag = "settings-log-format-always-extensions",
                )
            }
            SettingsStackedRow(
                "Header text",
                "All listed strings must occur in the file sample for this profile to be a detection candidate.",
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
            if (draft.engine == LogFormatEngine.REGEX) {
                SettingsStackedRow(
                    "Record regex",
                    "The parsing rule. Each (bracket pair) is one column, numbered from 1; the fields below refer to those numbers. " +
                        "\"Set up from a sample line\" above writes it for you.",
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
            }
            if (draft.engine in JOIN_LINES_ENGINES) {
                // A multiline record start already decides where a regex record ends, so the choice does not apply then.
                val canJoinLines = draft.engine != LogFormatEngine.REGEX || draft.multilineStartPattern.isBlank()
                SettingsRow(
                    "Lines that do not match",
                    "On: a line that does not fit the format continues the record above it, so a stack trace under a log line stays " +
                        "one record you can expand. Off: every such line is its own raw row.",
                    enabled = canJoinLines,
                ) {
                    SettingsToggle(
                        checked = draft.joinUnmatchedLines,
                        label = "Add lines that do not match to the record above",
                        onChange = { checked -> editDraft { profile -> profile.copy(joinUnmatchedLines = checked) } },
                        reclaimFocus = reclaimFocus,
                        enabled = canJoinLines,
                        testTag = "settings-log-format-join-lines",
                    )
                }
            }
            if (draft.engine == LogFormatEngine.CSV) {
                SettingsStackedRow("CSV delimiter", "Use one character as the delimiter.") {
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
                }
                SettingsRow(
                    "First row contains field names",
                    "Turn this off when row one is a record, not a header. Fields are then found by their 0-based column number.",
                ) {
                    SettingsToggle(
                        checked = draft.hasHeader,
                        label = "First row contains field names",
                        onChange = { checked -> editDraft { profile -> profile.copy(hasHeader = checked) } },
                        reclaimFocus = reclaimFocus,
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
            engineFieldRoles(draft.engine).forEachIndexed { index, role ->
                SettingsStackedRow(
                    roleLabel(role),
                    // The hint is the same for every field, so it is said once, on the first one.
                    fieldMappingHint(draft.engine).takeIf { index == 0 },
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
            if (supportsProfileTimestamps(draft.engine)) {
                SettingsStackedRow(
                    "Timestamp interpretation",
                    "Choose how timestamp values become source-clock time.",
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
                ) {
                    SettingsDropdown(
                        options = ProfileEpochUnit.entries,
                        selected = draft.epochUnit,
                        label = ::epochUnitLabel,
                        onSelect = { unit -> editDraft { profile -> profile.copy(epochUnit = unit) } },
                        testTag = "settings-log-format-epoch-unit",
                    )
                }
                SettingsStackedRow("Timestamp pattern", "Pattern for custom date and time values.") {
                    SettingsTextField(
                        draft.timestampPattern,
                        { value -> editDraft { profile -> profile.copy(timestampPattern = value) } },
                        placeholder = "yyyy-MM-dd HH:mm:ss.SSS",
                        monospace = true,
                        modifier = Modifier.width(settingsFieldWidth(SETTINGS_FIELD_LONG)),
                        testTag = "settings-log-format-time-pattern",
                    )
                }
                SettingsStackedRow(
                    "Timezone",
                    "A blank value keeps a local source clock without assigning UTC.",
                ) {
                    SettingsTextField(
                        draft.timezone,
                        { value -> editDraft { profile -> profile.copy(timezone = value) } },
                        placeholder = "Europe/Kyiv",
                        testTag = "settings-log-format-timezone",
                    )
                }
                SettingsStackedRow(
                    "Relative clock ID",
                    "Name the elapsed-time clock only when records share a known clock.",
                    enabled = draft.timestampKind == ProfileTimestampKind.RELATIVE,
                ) {
                    SettingsTextField(
                        draft.relativeClock,
                        { value -> editDraft { profile -> profile.copy(relativeClock = value) } },
                        placeholder = "device-boot-123",
                        testTag = "settings-log-format-relative-clock",
                    )
                }
            } else {
                ProfileSummaryRow("Timestamp interpretation", READ_DIRECTLY_BY_ENGINE)
            }
            if (supportsSeverityMapping(draft.engine)) {
                SettingsStackedRow(
                    "Severity mapping",
                    "Map source values to the six normalized severity levels.",
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
                                RemoveItemButton(
                                    "Remove mapping $source",
                                    "settings-log-format-severity-remove-$index",
                                ) {
                                    editDraft { current ->
                                        current.copy(severityMapping = current.severityMapping - source)
                                    }
                                }
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
                            modifier = Modifier.testTag("settings-log-format-severity-add"),
                        )
                    }
                }
            } else {
                ProfileSummaryRow("Severity mapping", READ_DIRECTLY_BY_ENGINE)
            }
            if (supportsProfileFields(draft.engine)) {
                SettingsStackedRow(
                    "Extra attributes",
                    "Add typed fields such as container ID, request ID, or HTTP path.",
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        draft.attributes.forEachIndexed { index, attribute ->
                            Column(
                                Modifier.fillMaxWidth().background(tc().p2, NESTED_CARD_SHAPE).padding(8.dp),
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
                                        testTag = "settings-log-format-attribute-visible-$index",
                                    )
                                    Spacer(Modifier.weight(1f))
                                    RemoveItemButton(
                                        "Remove attribute ${attribute.id.ifBlank { (index + 1).toString() }}",
                                        "settings-log-format-attribute-remove-$index",
                                    ) {
                                        editDraft { current ->
                                            current.copy(
                                                attributes = current.attributes.filterIndexed { attrIndex, _ -> attrIndex != index },
                                            )
                                        }
                                    }
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
            } else {
                ProfileSummaryRow("Extra attributes", NO_EXTRA_SOURCE_FIELDS)
            }
            SettingsStackedRow(
                "Default columns",
                "The fields a row shows in the log view, in this order. Hiding a field does not stop it being parsed or filtered.",
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
                            testTag = "settings-log-format-column-${role.name.lowercase()}",
                        )
                    }
                }
            }
            SettingsStackedRow(
                "Compatible analyzers",
                "Choose analyses that understand the records this profile produces.",
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
                    testTag = "settings-log-format-analyzer-android-crash",
                )
            }
            SettingsStackedRow(
                "Issue rules",
                "Each rule adds a named safe regex match to this profile's issue analysis.",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    draft.issueRules.forEachIndexed { index, rule ->
                        Column(
                            Modifier.fillMaxWidth().background(tc().p2, NESTED_CARD_SHAPE).padding(8.dp),
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
                                    testTag = "settings-log-format-issue-enabled-$index",
                                )
                                RemoveItemButton(
                                    "Remove rule ${rule.id.ifBlank { (index + 1).toString() }}",
                                    "settings-log-format-issue-remove-$index",
                                ) {
                                    editDraft { current ->
                                        current.copy(
                                            issueRules = current.issueRules.filterIndexed { ruleIndex, _ -> ruleIndex != index },
                                        )
                                    }
                                }
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
    }

    SettingsGroup("Preview") {
        SettingsStackedRow(
            "Sample records",
            if (isBinaryEngine(draft.engine)) {
                "DLT records are binary. Open a DLT tab and preview its source instead."
            } else {
                "Try up to 2,000 lines from the sample. Parsing runs in the background; previews do not modify settings."
            },
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isBinaryEngine(draft.engine)) {
                    AppText(
                        if (draft.engine == LogFormatEngine.DLT) {
                            "Text samples cannot represent DLT records."
                        } else {
                            "Text samples cannot represent captured packets."
                        },
                        color = tc().ts,
                        fontSize = 10.sp,
                    )
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
                    if (!isBinaryEngine(draft.engine)) {
                        SettingsButton(
                            if (previewLoading) "Previewing…" else "Preview sample",
                            {
                                if (!previewLoading) {
                                    clearPreview()
                                    val candidate = runCatching(::buildDraft).getOrElse {
                                        previewError = it.message ?: "Complete the profile fields before previewing."
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
                                                onFailure = { previewError = it.message ?: "Could not preview this profile." },
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
                                previewError = "Open a log tab before previewing its source."
                            } else {
                                val candidate = runCatching(::buildDraft).getOrElse {
                                    previewError = it.message ?: "Complete the profile fields before previewing."
                                    return@SettingsButton
                                }
                                val requestId = previewRequestId
                                previewLoading = true
                                state.previewCurrentLogFormatProfile(activeTabId, candidate) { result ->
                                    if (requestId == previewRequestId) {
                                        result.fold(
                                            onSuccess = { preview = LogFormatPreview(candidate, sample, it) },
                                            onFailure = { previewError = it.message ?: "Could not preview the active tab." },
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
                }
                previewError?.let { AppText(it, color = DANGER_RED, fontSize = 11.sp, maxLines = 3) }
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

/** The × that removes one item of a list: [description] is its accessible name and tooltip, [tag] its test tag. */
@Composable
private fun RemoveItemButton(description: String, tag: String, onRemove: () -> Unit) {
    SquareIconButton(
        "×",
        fontSize = 14.sp,
        onClick = onRemove,
        modifier = Modifier.semantics { contentDescription = description }.testTag(tag),
        size = REMOVE_BUTTON_SIZE,
        tooltip = description,
    )
}

private val REMOVE_BUTTON_SIZE = 22.dp

/** The built-in profile's name, a read-only badge, its description and the one way forward: an editable copy. */
@Composable
private fun BuiltInProfileHeader(profile: LogFormatProfile, onDuplicate: () -> Unit) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp).testTag("settings-log-format-builtin-header"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    profile.name,
                    color = tc.tx,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f, fill = false),
                )
                SettingsBadge("Built-in · read-only")
            }
            if (profile.description.isNotBlank()) {
                AppText(profile.description, color = tc.ts, fontSize = 11.sp, maxLines = Int.MAX_VALUE)
            }
        }
        SettingsButton(
            "Duplicate to edit",
            onDuplicate,
            variant = ButtonVariant.Primary,
            modifier = Modifier.testTag("settings-log-format-duplicate-to-edit"),
        )
    }
}

/** Whether the detection text fields differ from what selecting [saved] loads into them. */
private fun detectionTextEdited(saved: LogFormatProfile, extensions: String, header: String, contentRegex: String): Boolean =
    extensions != saved.detection.extensions.joinToString(", ") ||
        header != saved.detection.headerContains.joinToString(", ") ||
        contentRegex != saved.detection.contentRegex.orEmpty()

/**
 * The custom profile's name and state badges with its Revert (Discard for a new profile), Save profile and, for a saved
 * profile, a "⋯" menu of the rare actions in [menu]. It sits where the built-in header does, at the top.
 */
@Composable
private fun CustomProfileHeader(
    name: String,
    revision: Int?,
    dirty: Boolean,
    saveEnabled: Boolean,
    onSave: () -> Unit,
    onRevert: () -> Unit,
    menu: List<SettingsMenuItem>,
    reclaimFocus: () -> Unit,
) {
    val tc = tc()
    val isNew = revision == null
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp).testTag("settings-log-format-custom-header"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AppText(
                name.ifBlank { "Untitled profile" },
                color = tc.tx,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SettingsBadge(if (revision == null) "New · not saved" else "Custom · revision $revision")
                if (dirty) SettingsBadge("Unsaved changes", dot = true)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalAlignment = Alignment.CenterVertically) {
            SettingsButton(
                if (isNew) "Discard" else "Revert",
                onRevert,
                enabled = isNew || dirty,
                modifier = Modifier.testTag("settings-log-format-revert"),
            )
            SettingsButton(
                "Save profile",
                onSave,
                variant = ButtonVariant.Primary,
                enabled = saveEnabled,
                modifier = Modifier.testTag("settings-log-format-save"),
            )
            if (menu.isNotEmpty()) {
                SettingsOverflowMenu(
                    items = menu,
                    contentDescription = "More profile actions",
                    testTag = "settings-log-format-profile-menu",
                    onInteraction = reclaimFocus,
                )
            }
        }
    }
}

/** A built-in profile as plain label and value rows: nothing here looks editable, because nothing is. */
@Composable
private fun BuiltInProfileSummary(profile: LogFormatProfile) {
    val engine = profile.engine
    val detection = profile.detection
    SettingsGroup("Definition", modifier = Modifier.testTag("settings-log-format-summary")) {
        ProfileSummaryRow("Parser engine", engineLabel(engine))
        ProfileSummaryRow("File extensions", detection.extensions.joinToString(", ").ifBlank { "None" })
        ProfileSummaryRow(
            "Always open these extensions with this format",
            if (detection.alwaysUseForExtensions && detection.extensions.isNotEmpty()) "On" else "Off",
        )
        ProfileSummaryRow("Header text", detection.headerContains.joinToString(", ").ifBlank { "None" })
        ProfileSummaryRow("Content detection regex", detection.contentRegex?.ifBlank { null } ?: "None", monospace = true)
        ProfileSummaryRow("Fields", summaryFields(profile))
        ProfileSummaryRow("Timestamp interpretation", summaryTimestamp(profile))
        ProfileSummaryRow("Severity mapping", summarySeverity(profile))
        ProfileSummaryRow(
            "Extra attributes",
            profile.attributes.joinToString(", ") { "${it.label} (${it.id})" }
                .ifBlank { if (supportsProfileFields(engine)) "None" else NO_EXTRA_SOURCE_FIELDS },
        )
        val columns = withAttributeColumns(
            profile,
            profile.visibleColumns.map(::roleLabel),
            profile.attributes.filter { it.visibleByDefault }.map { it.label },
        )
        ProfileSummaryRow("Default columns", columns.joinToString(", ").ifBlank { "None" })
        ProfileSummaryRow(
            "Compatible analyzers",
            profile.compatibleAnalyzers.sorted().joinToString(", ") { analyzerLabel(it) }.ifBlank { "None" },
        )
        ProfileSummaryRow(
            "Issue rules",
            profile.issueRules.joinToString(", ") { if (it.enabled) it.name else "${it.name} (disabled)" }.ifBlank { "None" },
        )
    }
}

/** One label and its value, both at full strength; a long value wraps. Anchored by [label] so Settings search finds it. */
@Composable
private fun ProfileSummaryRow(label: String, value: String, monospace: Boolean = false) {
    val tc = tc()
    Row(
        Modifier.fillMaxWidth()
            .settingsAnchor(label)
            .padding(horizontal = 12.dp, vertical = 9.dp)
            .testTag("settings-log-format-summary-${label.lowercase().replace(' ', '-')}"),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        AppText(
            label,
            color = tc.tx,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.width(SUMMARY_LABEL_WIDTH),
        )
        AppText(
            value,
            color = tc.tx,
            fontSize = 12.sp,
            fontFamily = if (monospace) FontFamily.Monospace else LocalUiFontFamily.current,
            maxLines = Int.MAX_VALUE,
            modifier = Modifier.weight(1f),
        )
    }
}

private val SUMMARY_LABEL_WIDTH = 210.dp

private fun summaryFields(profile: LogFormatProfile): String {
    if (!supportsProfileFields(profile.engine)) return nativeFieldDescription(profile.engine)
    val mapped = profile.fieldMappings.entries.sortedBy { it.key.ordinal }.filter { it.value.isNotBlank() }
    return if (mapped.isEmpty()) {
        "Detected automatically"
    } else {
        mapped.joinToString("\n") { (role, source) -> "${roleLabel(role)} → $source" }
    }
}

private fun summaryTimestamp(profile: LogFormatProfile): String {
    if (!supportsProfileTimestamps(profile.engine)) return READ_DIRECTLY_BY_ENGINE
    val parts = mutableListOf(timestampKindLabel(profile.timestampKind))
    if (profile.timestampKind == ProfileTimestampKind.EPOCH || profile.timestampKind == ProfileTimestampKind.RELATIVE) {
        parts += epochUnitLabel(profile.epochUnit)
    }
    if (profile.timestampPattern.isNotBlank()) parts += "pattern ${profile.timestampPattern}"
    if (profile.timestampKind == ProfileTimestampKind.RELATIVE && profile.relativeClock.isNotBlank()) {
        parts += "clock ${profile.relativeClock}"
    }
    if (profile.timezone.isNotBlank()) parts += "timezone ${profile.timezone}"
    return parts.joinToString(" · ")
}

private fun summarySeverity(profile: LogFormatProfile): String = when {
    !supportsSeverityMapping(profile.engine) -> READ_DIRECTLY_BY_ENGINE
    profile.severityMapping.isEmpty() -> "None"
    else -> profile.severityMapping.entries.joinToString(", ") { (source, level) -> "$source → ${LogLevel.from(level).label}" }
}

private fun analyzerLabel(id: String): String = if (id == "android-crash") "Android crash analysis" else id

@Composable
private fun ProfilePreviewResults(parsed: ParsedLog) {
    val tc = tc()
    Column(
        Modifier
            .fillMaxWidth()
            .background(tc.p2, NESTED_CARD_SHAPE)
            .border(1.dp, tc.br, NESTED_CARD_SHAPE)
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
                parsed.diagnostics.take(4).forEach { AppText(it, color = tc.ts, fontSize = 11.sp, maxLines = 2) }
            }
        }
        parsed.entries.take(4).forEach { entry -> LogFormatPreviewRecord(entry) }
        if (parsed.capabilities.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                AppText("Capabilities", color = tc.tx, fontSize = 12.sp)
                LogFormatCapabilityList(parsed.capabilities)
            }
        }
    }
}

/** The most extra profile fields a previewed record lists under its line. */
private const val PREVIEW_MAX_FIELDS = 6

/**
 * The colour of a previewed record's severity text, as the log view draws it: the level's own colour, or [unknown] when the source
 * severity could not be read.
 */
internal fun previewSeverityColor(entry: LogEntry, unknown: Color): Color =
    if (entry.severityKnown) entry.level.defaultColor else unknown

/**
 * One previewed record drawn like a log row, in the log font: timestamp, severity (bold, in its level colour), component and message,
 * then each extra profile field on its own line as "key  value". Placeholders for what the profile did not produce are dim. [fill] is the
 * row's background, the page fill unless the container around it already is.
 */
@Composable
internal fun LogFormatPreviewRecord(entry: LogEntry, modifier: Modifier = Modifier, fill: Color? = null) {
    val tc = tc()
    val severity = entry.displaySeverityText
    Column(
        modifier.fillMaxWidth().background(fill ?: tc.p, CORNER_MD).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
            AppText(
                entry.ts.ifBlank { "No timestamp" },
                color = tc.td,
                fontSize = PREVIEW_LOG_FONT_SIZE,
                fontFamily = FontFamily.Monospace,
            )
            if (severity.isNotBlank()) {
                AppText(
                    severity,
                    color = previewSeverityColor(entry, Color(UNKNOWN_SEVERITY_TEXT_COLOR_ARGB)),
                    fontSize = PREVIEW_LOG_FONT_SIZE,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }
            AppText(
                entry.tag.ifBlank { "No component" },
                color = if (entry.tag.isBlank()) tc.td else tc.ts,
                fontSize = PREVIEW_LOG_FONT_SIZE,
                fontFamily = FontFamily.Monospace,
            )
            AppText(
                entry.msg.ifBlank { "No message" },
                color = if (entry.msg.isBlank()) tc.td else tc.tx,
                fontSize = PREVIEW_LOG_FONT_SIZE,
                fontFamily = FontFamily.Monospace,
                maxLines = 2,
                modifier = Modifier.weight(1f),
            )
        }
        entry.profileFields.entries.take(PREVIEW_MAX_FIELDS).forEach { (key, value) ->
            Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppText(key, color = tc.ts, fontSize = PREVIEW_FIELD_FONT_SIZE, fontFamily = FontFamily.Monospace)
                AppText(
                    value,
                    color = tc.tx,
                    fontSize = PREVIEW_FIELD_FONT_SIZE,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private val PREVIEW_LOG_FONT_SIZE = 12.sp
private val PREVIEW_FIELD_FONT_SIZE = 11.sp

/**
 * The capabilities worth listing: an Android-only one (crash issues) is left out where it is unavailable, since for a
 * syslog or Docker log it is not missing, it just does not apply. Where it is available it is listed like the rest.
 */
internal fun shownLogCapabilities(capabilities: Map<LogCapability, LogCapabilityStatus>): Map<LogCapability, LogCapabilityStatus> =
    capabilities.filterNot { (capability, status) ->
        capability == LogCapability.ANDROID_ISSUES && status.availability == CapabilityAvailability.UNAVAILABLE
    }

/** A tinted chip per capability in words, "Text search · available". The reason for one that is partial or unavailable follows its chip. */
@Composable
internal fun LogFormatCapabilityList(capabilities: Map<LogCapability, LogCapabilityStatus>) {
    val tc = tc()
    val ordered = shownLogCapabilities(capabilities).toSortedMap(compareBy { it.ordinal })
    val (available, limited) = ordered.entries.partition { it.value.availability == CapabilityAvailability.AVAILABLE }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (available.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                available.forEach { (capability, status) -> CapabilityChip(capability, status.availability) }
            }
        }
        limitedCapabilityRows(limited.associate { it.key to it.value }).forEach { row ->
            if (row.capabilities.size == 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    row.capabilities.forEach { (capability, status) -> CapabilityChip(capability, status.availability) }
                    AppText(row.reason, color = tc.ts, fontSize = 11.sp, maxLines = 2, modifier = Modifier.weight(1f))
                }
            } else {
                // Several chips sharing a reason: they wrap as a group and the reason sits under them, so none is cut short.
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.capabilities.forEach { (capability, status) -> CapabilityChip(capability, status.availability) }
                    }
                    AppText(row.reason, color = tc.ts, fontSize = 11.sp, maxLines = 2)
                }
            }
        }
    }
}

/** One row of the partial or unavailable capabilities: one or more chips and the reason they share. */
internal data class CapabilityRow(val capabilities: List<Pair<LogCapability, LogCapabilityStatus>>, val reason: String)

private val PROCESS_CAPABILITIES = listOf(LogCapability.PID_THREAD_FILTER, LogCapability.PROCESS_MAP, LogCapability.PROCESS_NAMES)

/** The reason the process capabilities share when they are off together. */
internal const val NO_PROCESS_IDS_REASON = "No process IDs were parsed, so there is no process filter, process map or process names."

/**
 * The partial and unavailable capabilities as rows. The process ones (filter, map, names) all come down to
 * the same missing process IDs: when two or more are unavailable they share one row and one reason
 * instead of three rows saying it in three ways. Every other capability keeps its own row.
 */
internal fun limitedCapabilityRows(limited: Map<LogCapability, LogCapabilityStatus>): List<CapabilityRow> {
    val ordered = limited.entries.sortedBy { it.key.ordinal }.map { it.key to it.value }
    val offProcess = ordered.filter { (capability, status) ->
        capability in PROCESS_CAPABILITIES && status.availability == CapabilityAvailability.UNAVAILABLE
    }
    if (offProcess.size < 2) return ordered.map { CapabilityRow(listOf(it), it.second.reason) }
    val rows = mutableListOf<CapabilityRow>()
    ordered.forEach { entry ->
        when {
            entry == offProcess.first() -> rows += CapabilityRow(offProcess, NO_PROCESS_IDS_REASON)
            entry in offProcess -> Unit
            else -> rows += CapabilityRow(listOf(entry), entry.second.reason)
        }
    }
    return rows
}

@Composable
private fun CapabilityChip(capability: LogCapability, availability: CapabilityAvailability) {
    val tc = tc()
    SettingsBadge(
        capabilityChipText(capability, availability),
        color = when (availability) {
            CapabilityAvailability.AVAILABLE -> tc.success
            CapabilityAvailability.PARTIAL -> tc.warn
            CapabilityAvailability.UNAVAILABLE -> tc.ts
        },
        modifier = Modifier.testTag("log-format-capability-${capability.name.lowercase()}"),
    )
}

/** What a capability chip says: the capability and its availability, both in words. */
internal fun capabilityChipText(capability: LogCapability, availability: CapabilityAvailability): String =
    "${capabilityLabel(capability)} · ${availabilityLabel(availability)}"

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
    LogFormatEngine.METADATA_JSON ->
        """[{"metadata":{"identity":"Service.Event","source":"demo-host","system_time":1790000000000000000},"Summary":{"Note":"Started"}}]"""
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
    LogFormatEngine.PLAIN_TEXT, LogFormatEngine.LOGCAT, LogFormatEngine.DLT, LogFormatEngine.METADATA_JSON -> emptyList()
    LogFormatEngine.SYSLOG_RFC5424, LogFormatEngine.SYSLOG_RFC3164 -> listOf(
        LogFieldRole.TIMESTAMP, LogFieldRole.SEVERITY, LogFieldRole.COMPONENT, LogFieldRole.MESSAGE,
        LogFieldRole.PROCESS_ID, LogFieldRole.HOST, LogFieldRole.SERVICE,
    )
    else -> LOG_FORMAT_ROLE_FIELDS
}

/** Engines that read a binary file: there is no text sample to paste and nothing to map. */
private fun isBinaryEngine(engine: LogFormatEngine): Boolean = engine == LogFormatEngine.DLT

private fun supportsProfileFields(engine: LogFormatEngine): Boolean =
    engine !in setOf(LogFormatEngine.LOGCAT, LogFormatEngine.DLT, LogFormatEngine.METADATA_JSON, LogFormatEngine.PLAIN_TEXT)

/** Severity mapping applies to the same engines as field mapping: the others read severity themselves. */
private fun supportsSeverityMapping(engine: LogFormatEngine): Boolean = supportsProfileFields(engine)

private fun supportsProfileTimestamps(engine: LogFormatEngine): Boolean =
    engine !in setOf(LogFormatEngine.LOGCAT, LogFormatEngine.DLT, LogFormatEngine.METADATA_JSON, LogFormatEngine.PLAIN_TEXT)

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
    LogFormatEngine.METADATA_JSON -> "JSON with metadata"
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

/** The sentence-case name of a capability. Exhaustive on purpose: a new [LogCapability] must be named here to compile. */
internal fun capabilityLabel(capability: LogCapability): String = when (capability) {
    LogCapability.TEXT_SEARCH -> "Text search"
    LogCapability.NOTES -> "Notes"
    LogCapability.MANUAL_FOLDING -> "Manual folding"
    LogCapability.SEVERITY_FILTER -> "Severity filter"
    LogCapability.COMPONENT_FILTER -> "Component filter"
    LogCapability.PID_THREAD_FILTER -> "Process and thread filter"
    LogCapability.PROCESS_MAP -> "Process map"
    LogCapability.PROCESS_NAMES -> "Process names"
    LogCapability.TIMELINE -> "Timeline"
    LogCapability.DELTA_TIME -> "Time delta"
    LogCapability.MERGE -> "Merge"
    LogCapability.VIDEO_LINKING -> "Video linking"
    LogCapability.ANDROID_ISSUES -> "Android issues"
    LogCapability.SOURCE_LOOKUP -> "Source lookup"
    LogCapability.INCREMENTAL_TAIL -> "Live tail"
    LogCapability.RECORD_SPLIT -> "Record split"
    LogCapability.EXPORT -> "Export"
}

private fun availabilityLabel(availability: CapabilityAvailability): String = when (availability) {
    CapabilityAvailability.AVAILABLE -> "available"
    CapabilityAvailability.PARTIAL -> "partial"
    CapabilityAvailability.UNAVAILABLE -> "unavailable"
}

private fun splitList(text: String): List<String> = text.split(',').map(String::trim).filter(String::isNotBlank)

private fun <T> List<T>.replaceAt(index: Int, transform: (T) -> T): List<T> = mapIndexed { i, item ->
    if (i == index) transform(item) else item
}
