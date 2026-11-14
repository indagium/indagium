package com.indagium.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.indagium.diagram3.DiagramExportMode
import com.indagium.model.AnnotationCopyFormat
import com.indagium.model.AnnotationLogBlockStyle
import com.indagium.model.CopyMaskRule
import com.indagium.proofread.normalizeProofreadingVocabulary
import com.indagium.proofread.normalizeProofreadingVocabularyWord

// Settings › Notes & writing and Settings › Copy & export: how notes are written and proofread, what copying includes and masks, and how
// exported notes are named.

private val FORMAT_DROPDOWN_WIDTH = 168.dp
private val PREFIX_FIELD_WIDTH = 168.dp
private val MASK_REMOVE_WIDTH = 68.dp

/** Test tags of the Notes & writing and Copy & export controls that have no tag of their own. */
internal object NotesPageTags {
    const val NUMBER_BLOCKS = "setting-number-blocks"
    const val LOG_BLOCKS = "setting-log-blocks"
    const val INLINE_MARKDOWN = "setting-inline-markdown"
    const val DIAGRAM_NOTE_ACTION = "setting-diagram-note-action"
    const val PROOFREADING = "setting-proofreading"
    const val PROOFREADING_VARIANT = "setting-proofreading-variant"
    const val COPY_FORMAT = "setting-copy-default"
    const val COPY_PID_TID = "setting-copy-pid-tid"
    const val COPY_PID_AS_NAME = "setting-copy-pid-as-name"
    const val COPY_ROW_NUMBER = "setting-copy-row-number"
    const val COPY_TIME_DELTA = "setting-copy-time-delta"
    const val MASK_SWITCH = "setting-mask-word-on-copy"
    const val MASK_ADD = "setting-mask-add-pair"
    const val DIAGRAM_EXPORT = "setting-diagram-export"
    const val PREFIX_FIELD = "setting-annotation-file-prefix"
}

/** Settings › Notes & writing: how note blocks are written, then proofreading with its personal vocabulary. */
@Composable
internal fun NotesWritingSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings
    SettingsGroup("Note blocks") {
        SettingsRow("Number blocks", "Put 1, 2, 3… in front of each block of a note.") {
            SettingsToggle(
                checked = settings.numberAnnotationBlocks,
                label = "Number blocks",
                onChange = { next -> state.updateSettings { it.copy(numberAnnotationBlocks = next) } },
                reclaimFocus = reclaimFocus,
                testTag = NotesPageTags.NUMBER_BLOCKS,
            )
        }
        SettingsRow("Log excerpts", "How log lines are wrapped inside a note: plain indent or a Jira code block.", anchor = "Log blocks") {
            val styles = AnnotationLogBlockStyle.entries
            SegmentedControl(
                options = listOf("Indented", "Jira wiki", "Jira Cloud"),
                selectedIndices = setOf(styles.indexOf(settings.annotationLogBlockStyle)),
                onToggle = { idx -> state.updateSettings { it.copy(annotationLogBlockStyle = styles[idx]) } },
                modifier = Modifier.testTag(NotesPageTags.LOG_BLOCKS),
                segmentHorizontalPadding = 8.dp,
            )
        }
        SettingsRow(
            "Show Markdown formatted",
            "Note and caption text shows rendered in the Notes panel; click it to edit the source.",
            anchor = "Inline Markdown",
        ) {
            SettingsToggle(
                checked = settings.renderAnnotationMarkdownInline,
                label = "Show Markdown formatted",
                onChange = { next -> state.updateSettings { it.copy(renderAnnotationMarkdownInline = next) } },
                reclaimFocus = reclaimFocus,
                testTag = NotesPageTags.INLINE_MARKDOWN,
            )
        }
        SettingsRow(
            "Diagram to note",
            "What the diagram toolbar's quick button adds. The workspace always offers both snapshot and linked notes in its menu.",
            anchor = "Diagram note action",
        ) {
            SegmentedControl(
                options = listOf("Snapshot", "Link"),
                selectedIndices = setOf(if (settings.diagramLinkedNotePrimary) 1 else 0),
                onToggle = { idx -> state.updateSettings { it.copy(diagramLinkedNotePrimary = idx == 1) } },
                modifier = Modifier.testTag(NotesPageTags.DIAGRAM_NOTE_ACTION),
                segmentHorizontalPadding = 8.dp,
            )
        }
    }
    SettingsGroup("Proofreading") {
        SettingsRow(
            "Check English spelling and grammar",
            "In notes and captions. Checked on this computer; nothing changes until you pick a suggestion.",
            anchor = "English proofreading",
        ) {
            SettingsToggle(
                checked = settings.proofreading.enabled,
                label = "Check English spelling and grammar",
                onChange = { next -> state.updateSettings { it.copy(proofreading = it.proofreading.copy(enabled = next)) } },
                reclaimFocus = reclaimFocus,
                testTag = NotesPageTags.PROOFREADING,
            )
        }
        SettingsRow("Spelling", "American or British English rules for the suggestions.", anchor = "English variant", indent = true) {
            SegmentedControl(
                options = listOf("US", "UK"),
                selectedIndices = setOf(if (settings.proofreading.language == "en-GB") 1 else 0),
                onToggle = { idx ->
                    state.updateSettings { it.copy(proofreading = it.proofreading.copy(language = if (idx == 0) "en-US" else "en-GB")) }
                },
                modifier = Modifier.testTag(NotesPageTags.PROOFREADING_VARIANT),
            )
        }
        ProofreadingVocabularyManager(state)
    }
}

/** True when [typed] normalizes to a word that [words] already holds. */
internal fun isVocabularyDuplicate(typed: String, words: List<String>): Boolean {
    val key = normalizeProofreadingVocabularyWord(typed) ?: return false
    return words.any { normalizeProofreadingVocabularyWord(it) == key }
}

/** The one helper line under the vocabulary field: the duplicate warning while the typed word is already saved, else the word count. */
internal fun vocabularyHelperText(typed: String, words: List<String>): String = when {
    isVocabularyDuplicate(typed, words) -> "Already in your vocabulary."
    words.isEmpty() -> "No saved words"
    words.size == 1 -> "1 word"
    else -> "${words.size} words"
}

@Composable
private fun ProofreadingVocabularyManager(state: AppState) {
    var query by remember { mutableStateOf("") }
    val proofreading = state.settings.proofreading
    val words = proofreading.vocabulary
    val queryKey = normalizeProofreadingVocabularyWord(query)
    val duplicate = isVocabularyDuplicate(query, words)
    val visible = remember(words, query) { words.filter { query.isBlank() || it.contains(query.trim(), ignoreCase = true) } }
    val tc = tc()
    val listShape = RoundedCornerShape(6.dp)
    SettingsStackedRow(
        "Personal vocabulary",
        "Words the checker should accept, such as app, class or tag names. Used while English proofreading is on.",
        indent = true,
        // Dimmed while proofreading is off, but still editable: the words are kept for when it is turned back on.
        enabled = proofreading.enabled,
        modifier = Modifier.testTag("proofreading-vocabulary-manager"),
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                SettingsTextField(
                    query,
                    { query = it },
                    Modifier.weight(1f),
                    placeholder = "Search or add a word",
                    onClear = { query = "" },
                    onSubmit = {
                        if (queryKey != null && !duplicate) {
                            addVocabularyWord(state, query)
                            query = ""
                        }
                    },
                    testTag = "proofreading-vocabulary-query",
                )
                AppButton(
                    "Add",
                    onClick = {
                        addVocabularyWord(state, query)
                        query = ""
                    },
                    enabled = queryKey != null && !duplicate,
                    modifier = Modifier.testTag("proofreading-vocabulary-add"),
                )
            }
            if (words.isEmpty()) {
                SettingsEmptyState(vocabularyHelperText(query, words), testTag = "proofreading-vocabulary-helper")
            } else {
                AppText(
                    vocabularyHelperText(query, words),
                    color = if (duplicate) tc.ts else tc.td,
                    fontSize = 10.sp,
                    modifier = Modifier.testTag("proofreading-vocabulary-helper"),
                )
            }
            if (words.isNotEmpty()) {
                val scroll = rememberScrollState()
                Box(
                    Modifier.fillMaxWidth().heightIn(max = 180.dp)
                        .background(tc.p2, listShape).border(1.dp, tc.br, listShape),
                ) {
                    Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(end = (THIN_SCROLLBAR_DP + 2f).dp)) {
                        visible.forEach { word ->
                            Row(
                                Modifier.fillMaxWidth().height(24.dp).padding(start = 8.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppText(word, color = tc.tx, fontSize = 12.sp, modifier = Modifier.weight(1f))
                                SquareIconButton(
                                    "×",
                                    fontSize = 12.sp,
                                    onClick = {
                                        val removedKey = normalizeProofreadingVocabularyWord(word)
                                        state.updateSettings { settings ->
                                            val current = settings.proofreading.vocabulary
                                            settings.copy(
                                                proofreading = settings.proofreading.copy(
                                                    vocabulary = normalizeProofreadingVocabulary(
                                                        current.filterNot { normalizeProofreadingVocabularyWord(it) == removedKey },
                                                    ),
                                                ),
                                            )
                                        }
                                    },
                                    modifier = Modifier.testTag("proofreading-vocabulary-remove-${word.hashCode()}"),
                                    tooltip = "Remove",
                                )
                            }
                        }
                        if (visible.isEmpty()) {
                            AppText("No matching words", color = tc.td, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp))
                        }
                    }
                    Box(Modifier.matchParentSize()) {
                        ThinVerticalScrollbar(scroll, Modifier.align(Alignment.CenterEnd).fillMaxHeight())
                    }
                }
            }
        }
    }
}

private fun addVocabularyWord(state: AppState, raw: String) {
    val word = normalizeProofreadingVocabularyWord(raw) ?: return
    state.updateSettings { settings ->
        val normalized = normalizeProofreadingVocabulary(settings.proofreading.vocabulary + raw.trim())
        val containsWord = normalized.any { normalizeProofreadingVocabularyWord(it) == word }
        if (containsWord) settings.copy(proofreading = settings.proofreading.copy(vocabulary = normalized)) else settings
    }
}

/** Settings › Copy & export: what copying includes and the format, the words masked on copy, and how exported notes are named. */
@Composable
internal fun CopyExportSettingsSection(state: AppState, reclaimFocus: () -> Unit) {
    val settings = state.settings
    SettingsGroup("Copying") {
        SettingsRow(
            "Note format",
            "Used by the main Copy button. A one-time pick from the Copy menu doesn't change it.",
            anchor = "Copy default",
        ) {
            SettingsDropdown(
                options = AnnotationCopyFormat.entries,
                selected = settings.annotationCopyFormat,
                label = { format -> format.label },
                onSelect = { format -> state.updateSettings { it.copy(annotationCopyFormat = format) } },
                testTag = NotesPageTags.COPY_FORMAT,
                minWidth = FORMAT_DROPDOWN_WIDTH,
                onInteraction = reclaimFocus,
            )
        }
    }
    SettingsGroup("Included in copied rows") {
        SettingsRow(
            "PID and TID",
            "Also in filtered exports. New annotations capture their own field choices.",
            anchor = "Pid/Tid copy",
        ) {
            SettingsToggle(
                checked = settings.copyPidTid,
                label = "PID and TID",
                onChange = { next -> state.updateSettings { it.copy(copyPidTid = next) } },
                reclaimFocus = reclaimFocus,
                testTag = NotesPageTags.COPY_PID_TID,
            )
        }
        // The stored choice is untouched while PID and TID is off: the row only stops being editable and dims.
        SettingsRow(
            "Process name instead of PID",
            "Uses names learned from the log. Available only while PID and TID is on.",
            anchor = "Pid copy as name",
            indent = true,
            enabled = settings.copyPidTid,
        ) {
            SettingsToggle(
                checked = settings.copyPidAsName,
                label = "Process name instead of PID",
                onChange = { next -> state.updateSettings { it.copy(copyPidAsName = next) } },
                reclaimFocus = reclaimFocus,
                enabled = settings.copyPidTid,
                testTag = NotesPageTags.COPY_PID_AS_NAME,
            )
        }
        SettingsRow("Row number", "Only while the row-number gutter is visible.", anchor = "Row number copy") {
            SettingsToggle(
                checked = settings.copyRowNumber,
                label = "Row number",
                onChange = { next -> state.updateSettings { it.copy(copyRowNumber = next) } },
                reclaimFocus = reclaimFocus,
                testTag = NotesPageTags.COPY_ROW_NUMBER,
            )
        }
        SettingsRow(
            "Time delta (Δt)",
            "Only while the tab's Δt column is visible and the log view can calculate it.",
            anchor = "Time delta copy",
        ) {
            SettingsToggle(
                checked = settings.copyTimeDelta,
                label = "Time delta",
                onChange = { next -> state.updateSettings { it.copy(copyTimeDelta = next) } },
                reclaimFocus = reclaimFocus,
                testTag = NotesPageTags.COPY_TIME_DELTA,
            )
        }
    }
    SettingsGroup("Masking") { MaskWordsRows(state, reclaimFocus) }
    SettingsGroup("Exported notes") {
        SettingsRow(
            "New diagram notes",
            "Image works anywhere; Source keeps editable Mermaid or PlantUML text. Existing notes keep their own choice.",
            anchor = "Diagram export",
        ) {
            SegmentedControl(
                options = listOf("Image", "Source"),
                selectedIndices = setOf(if (settings.diagramDefaultExportMode == DiagramExportMode.IMAGE) 0 else 1),
                onToggle = { index ->
                    state.updateSettings {
                        it.copy(diagramDefaultExportMode = if (index == 0) DiagramExportMode.IMAGE else DiagramExportMode.SOURCE)
                    }
                },
                modifier = Modifier.testTag(NotesPageTags.DIAGRAM_EXPORT),
            )
        }
        SettingsRow(
            "Source line label",
            "Preview: ${settings.annotationPrefixLabel.trim().ifBlank { "From" }} app.log",
            anchor = "Annotation file prefix",
        ) {
            SettingsTextField(
                settings.annotationPrefixLabel,
                { value -> state.updateSettings { it.copy(annotationPrefixLabel = value) } },
                Modifier.width(PREFIX_FIELD_WIDTH),
                placeholder = "From",
                testTag = NotesPageTags.PREFIX_FIELD,
            )
        }
    }
}

/**
 * The masking group's rows: the switch with its Add pair button, and while it is on the word / replacement pairs under it, as one card
 * row so no divider falls between the switch and its table.
 */
@Composable
private fun MaskWordsRows(state: AppState, reclaimFocus: () -> Unit) {
    val tc = tc()
    val settings = state.settings
    Column(Modifier.fillMaxWidth()) {
        SettingsRow(
            "Mask words when copying a note",
            "Swap names, hosts or IDs for a replacement before they reach the clipboard.",
            anchor = "Mask word on copy",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (settings.maskWordOnCopy) {
                    AppButton(
                        "Add pair",
                        onClick = { state.updateSettings { it.copy(copyMaskRules = it.copyMaskRules + CopyMaskRule()) } },
                        variant = ButtonVariant.Secondary,
                        modifier = Modifier.testTag(NotesPageTags.MASK_ADD),
                    )
                }
                SettingsToggle(
                    checked = settings.maskWordOnCopy,
                    label = "Mask words when copying a note",
                    onChange = { next -> state.updateSettings { it.copy(maskWordOnCopy = next) } },
                    reclaimFocus = reclaimFocus,
                    testTag = NotesPageTags.MASK_SWITCH,
                )
            }
        }
        if (settings.maskWordOnCopy) {
            Column(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppText("Word", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1f))
                    AppText("Replacement", color = tc.td, fontSize = 10.sp, fontFamily = UI, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(MASK_REMOVE_WIDTH))
                }
                if (settings.copyMaskRules.isEmpty()) {
                    SettingsEmptyState("No pairs yet. Add one to mask text when copying a note.")
                } else {
                    SettingsScrollableRows {
                        settings.copyMaskRules.forEachIndexed { index, rule -> MaskRuleRow(state, index, rule) }
                    }
                }
                AppText(
                    "Rules replace case-sensitive whole words in the listed order when copying a note — " +
                        "{code:java} block markers are never touched.",
                    color = tc.td,
                    fontSize = 10.sp,
                    maxLines = 2,
                )
            }
        }
    }
}

@Composable
private fun MaskRuleRow(state: AppState, index: Int, rule: CopyMaskRule) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        SettingsTextField(
            rule.target,
            { value ->
                state.updateSettings { settings ->
                    settings.copy(
                        copyMaskRules = settings.copyMaskRules.mapIndexed { ruleIndex, current ->
                            if (ruleIndex == index) current.copy(target = value) else current
                        },
                    )
                }
            },
            Modifier.weight(1f),
            placeholder = "java",
        )
        SettingsTextField(
            rule.replacement,
            { value ->
                state.updateSettings { settings ->
                    settings.copy(
                        copyMaskRules = settings.copyMaskRules.mapIndexed { ruleIndex, current ->
                            if (ruleIndex == index) current.copy(replacement = value) else current
                        },
                    )
                }
            },
            Modifier.weight(1f),
            placeholder = "j*ava",
        )
        AppButton(
            "Remove",
            onClick = {
                state.updateSettings { settings ->
                    settings.copy(copyMaskRules = settings.copyMaskRules.filterIndexed { ruleIndex, _ -> ruleIndex != index })
                }
            },
            variant = ButtonVariant.Secondary,
            isDanger = true,
        )
    }
}
