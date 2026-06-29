package com.openlog.model

import androidx.compose.ui.graphics.Color

private val MANUAL_COLLAPSE_DEFAULT_COLOR = Color(0xFF06b6d4)

enum class LogLevel(val key: Char, val label: String, val defaultColor: Color) {
    V('V', "Verbose", Color(0xFF6e7681)),
    D('D', "Debug", Color(0xFF79c0ff)),
    I('I', "Info", Color(0xFF3fb950)),
    W('W', "Warn", Color(0xFFd29922)),
    E('E', "Error", Color(0xFFf85149)),
    A('A', "Assert", Color(0xFFff7b72));

    companion object {
        fun from(c: Char) = entries.find { it.key == c } ?: V
    }
}

data class LogEntry(
    val id: Int,
    val ts: String,
    val level: LogLevel,
    val tag: String,
    val msg: String,
    val pid: Int = 0,
    val tid: Int = 0
)

// ── Sequences ──────────────────────────────────────────────────────
data class SequenceDef(
    val id: String,
    val matchText: String,
    val isRegex: Boolean = false,
    val priority: Int,
    val color: Color,
    val enabled: Boolean = true,
    val tag: String? = null,
    val endMatchText: String? = null,
    val endIsRegex: Boolean = false,
    val endTag: String? = null,
)

data class NestedSeqGroup(val gid: String, val rid: Int, val ch: List<Int>, val defId: String = "")

data class SeqGroup(
    val gid: String,
    val rid: Int,
    val plain: List<Int>,
    val nested: List<NestedSeqGroup>,
    val defId: String
)

enum class ManualCollapseDirection { TO_START, TO_END }

data class ManualCollapseBlock(
    val id: String,
    val anchorId: Int,
    val direction: ManualCollapseDirection,
    val color: Color = MANUAL_COLLAPSE_DEFAULT_COLOR,
    val enabled: Boolean = true,
)

// ── Filter ─────────────────────────────────────────────────────────
enum class FilterMode { TAGS, KEYWORD }

data class Filter(
    val levels: Set<LogLevel> = LogLevel.entries.toSet(),
    val activeTags: Set<String> = emptySet(),
    val kwText: String = "",
    val kwRegex: Boolean = false,
    val mode: FilterMode = FilterMode.TAGS,
    val excludeTags: Set<String> = emptySet(),
    val excludeKw: String = "",
    val excludeKwRegex: Boolean = false,
    val highlighters: List<Highlighter> = emptyList(),
    val messageRules: List<MessageRule> = emptyList(),
    val seqOn: Boolean = true,
    // TAGS-mode secondary filters
    // message text filter applied within tag result set
    val kwInTag: String = "",
    val kwInTagRegex: Boolean = false,
    // tag prefixes — each matches com.foo.* tags
    val pkgPrefixes: Set<String> = emptySet(),
    // excluded tag prefixes — each matches com.foo.* tags
    val excludePkgPrefixes: Set<String> = emptySet(),
    // PID / TID
    // comma-separated PIDs/TIDs to include
    val pidTidFilter: String = "",
    val sequences: List<SequenceDef> = emptyList(),
)

data class Highlighter(val id: String, val pattern: String, val regex: Boolean, val color: Color, val on: Boolean)

enum class RuleTarget { MESSAGE, PID_TID }

data class MessageRule(
    val id: String,
    val include: Boolean,
    val pattern: String,
    val regex: Boolean = false,
    val tag: String? = null,
    val packagePrefix: String? = null,
    val enabled: Boolean = true,
    val target: RuleTarget = RuleTarget.MESSAGE,
)

// ── Annotations (block model) ──────────────────────────────────────
sealed class AnnBlock {
    abstract val id: String

    /** Pure text / commentary block */
    data class Note(override val id: String, val text: String) : AnnBlock()

    /** One or more log lines with an optional caption.
     *  sourceTabId / sourceFilename are set when the lines come from a different tab (compare mode).
     *  sourceEntries caches the actual rows so the annotation survives tab switches. */
    data class LogRef(
        override val id: String,
        val logIds: List<Int>,
        val caption: String,
        val sourceTabId: String? = null,
        val sourceFilename: String? = null,
        val sourceEntries: List<LogEntry>? = null,
    ) : AnnBlock()
}

data class Annotations(
    val blocks: List<AnnBlock> = emptyList(),
    val prefix: String = "",
    val suffix: String = "",
)

// ── Tab ────────────────────────────────────────────────────────────
data class LogTab(
    val id: String,
    val filename: String,
    val logData: List<LogEntry>,
    val rmap: Map<Int, LogEntry>,
    val filter: Filter = Filter(),
    val showUnfiltered: Boolean = false,
    val expanded: Set<String> = emptySet(),
    val selected: Set<Int> = emptySet(),
    val annotations: Annotations = Annotations(),
    val showAnnMd: Boolean = false,
    val manualBlocks: List<ManualCollapseBlock> = emptyList(),
    val sourcePath: String? = null,
)

data class SavedFilter(
    val id: String, val name: String,
    val levels: Set<LogLevel>, val activeTags: Set<String>,
    val kwText: String, val kwRegex: Boolean, val mode: FilterMode,
    val excludeTags: Set<String>, val excludeKw: String, val excludeKwRegex: Boolean,
    val highlighters: List<Highlighter>, val seqOn: Boolean,
    val kwInTag: String = "", val kwInTagRegex: Boolean = false,
    val pkgPrefixes: Set<String> = emptySet(), val pidTidFilter: String = "",
    val sequences: List<SequenceDef> = emptyList(),
    val messageRules: List<MessageRule> = emptyList(),
    val excludePkgPrefixes: Set<String> = emptySet(),
)

// ── Settings ───────────────────────────────────────────────────────
enum class AnnotationLogBlockStyle { INDENTED, JIRA_JAVA }

data class AppSettings(
    val theme: ThemePreset = ThemePreset.LIGHT,
    val fontSize: Int = 12,
    val fontMono: Boolean = true,
    val defaultSaveDir: String? = null,
    val mostUsedTagLimit: Int = 5,
    val filterListRows: Int = 5,
    val visibleTabLimit: Int = 8,
    val autoExportNotes: Boolean = true,
    val annotationLogBlockStyle: AnnotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA,
    val numberAnnotationBlocks: Boolean = false,
    val annotationPrefixLabel: String = "From",
)

enum class ThemePreset(val label: String) {
    DARK_GITHUB("Dark (GitHub)"),
    LIGHT("Light"),
    DRACULA("Dracula"),
    SOLARIZED_DARK("Solarized Dark"),
}

// ── Misc ───────────────────────────────────────────────────────────
data class CtxMenuState(
    val tabId: String,
    val entryId: Int,
    val x: Float,
    val y: Float,
    val selText: String,
    /** Non-empty when the right-click came from a panel with its own local selection
     *  (e.g. the "Original" panel in split/unfiltered view). Preferred over tab.selected. */
    val panelSelectedIds: Set<Int> = emptySet(),
)

// Request to open the add-annotation dialog
data class AddAnnRequest(
    val targetTabId: String,
    val sourceTabId: String,
    val logIds: List<Int>,
    val sourceFilename: String? = null,
)

sealed class LogItem {
    data class Row(val entry: LogEntry, val indent: Int, val groupColor: Color? = null) : LogItem()

    data class SeqHeader(
        val entry: LogEntry,
        val gid: String,
        val indent: Int,
        val expanded: Boolean,
        val count: Int,
        val color: Color
    ) : LogItem()

    data class ManualHeader(
        val entry: LogEntry,
        val gid: String,
        val direction: ManualCollapseDirection,
        val expanded: Boolean,
        val count: Int,
        val color: Color
    ) : LogItem()
}
