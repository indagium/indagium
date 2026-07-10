package com.openlog.model

import androidx.compose.ui.graphics.Color

private val MANUAL_COLLAPSE_DEFAULT_COLOR = Color(0xFF06b6d4)
val DEFAULT_KEYWORD_HIGHLIGHT_COLOR = Color(0xFFfacc15)

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
    val tid: Int = 0,
    // Set only by mergeLogs() (utils/LogMerge.kt) to badge which source file a merged-tab row
    // came from. null everywhere else, including LogParser.parseLogcat's own output — appended
    // last (not inserted earlier) so every existing positional LogEntry(...) construction across
    // the test suite keeps compiling unchanged.
    val sourceTag: String? = null,
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

// endExclusive is the index (into the full logData list SeqComputer was run against) one past
// this group's last swallowed entry — exposed so Filter.kt can classify containment against
// manual-collapse-block index ranges without rescanning plain/nested for a max id.
data class NestedSeqGroup(val gid: String, val rid: Int, val ch: List<Int>, val defId: String = "", val endExclusive: Int)

data class SeqGroup(
    val gid: String,
    val rid: Int,
    val plain: List<Int>,
    val nested: List<NestedSeqGroup>,
    val defId: String,
    val endExclusive: Int,
)

data class StackTraceGroup(val gid: String, val rid: Int, val memberIds: List<Int>, val isFatal: Boolean = false)

enum class CrashKind { EXCEPTION, ANR, NATIVE_CRASH }

// isFatal only distinguishes EXCEPTION sites ("FATAL EXCEPTION" dumps vs. a merely-logged
// <Class>Exception/Error that didn't kill the process) — ANR and NATIVE_CRASH are always
// process-ending by definition, so it's left false (unused) for those kinds.
data class CrashSite(val id: String, val entry: LogEntry, val kind: CrashKind, val groupGid: String?, val isFatal: Boolean = false)

// Crash-panel dropdown filter categories. ALL is the default/umbrella; CRASHES/ANRS/
// FATAL_EXCEPTIONS/EXCEPTIONS each narrow to exactly one CrashKind or EXCEPTION subtype (split by
// CrashSite.isFatal). OTHERS is a catch-all safety net — every CrashKind maps to one of the four
// specific categories today, so it's always empty in practice, but it exists so a future CrashKind
// added without an explicit bucket lands somewhere visible instead of silently vanishing from
// every specific filter.
enum class CrashCategory { ALL, CRASHES, ANRS, FATAL_EXCEPTIONS, EXCEPTIONS, OTHERS }

data class LogAnalysis(
    val tagCounts: Map<String, Int> = emptyMap(),
    val stackTraceGroups: List<StackTraceGroup> = emptyList(),
    val crashSites: List<CrashSite> = emptyList(),
    // True while the stack-trace/crash analysis is still computing in the background after a
    // load — it costs as much as the parse itself on multi-GB files, and the initial render
    // doesn't need it. Rows render unfolded and the crash panel shows an "analyzing" hint until
    // the full analysis replaces this instance. tagCounts is always populated regardless:
    // it's cheap and the filter panel needs it immediately.
    //
    // Defaults to true (not false) deliberately: any LogTab built without an explicit `analysis`
    // (e.g. a bare LogTab(...) construction that forgets the field) must read as "not analyzed
    // yet," never as "analyzed, found nothing." With false as the default, that ambiguity was
    // exactly why FilterPanel.kt/Filter.kt/ControlServer.kt each grew a defensive "empty cached
    // result -> recompute anyway" fallback instead of trusting an empty completed result — see
    // P-02 in artifacts/review/. Every real completion path (buildLogAnalysis in AppState.kt)
    // sets this to false explicitly, so flipping the default doesn't change any intentional case.
    val pending: Boolean = true,
)

// RANGE covers exactly [anchorId, endId] (order-independent — Filter.kt takes min/max of the two
// resolved indices), unlike TO_START/TO_END which extend from anchorId to a file edge.
enum class ManualCollapseDirection { TO_START, TO_END, RANGE }

data class ManualCollapseBlock(
    val id: String,
    val anchorId: Int,
    val direction: ManualCollapseDirection,
    val color: Color = MANUAL_COLLAPSE_DEFAULT_COLOR,
    val enabled: Boolean = true,
    // Only set (and only meaningful) for RANGE — the other end of the selected-lines range.
    val endId: Int? = null,
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
    val kwHighlightEnabled: Boolean = true,
    val kwHighlightColor: Color = DEFAULT_KEYWORD_HIGHLIGHT_COLOR,
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
    // Which FilterMode this rule was created in. Only TAGS-mode rules are active; KEYWORD values
    // are preserved for compatibility with older autosave/filter data but are ignored by filtering.
    val mode: FilterMode = FilterMode.TAGS,
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

// issueDescription is persisted (.ann sidecar + autosave) but deliberately excluded from
// buildMd() — a private working note for whoever (human or MCP client) is investigating this
// tab, never meant to leak into the exported/shared Markdown.
data class Annotations(
    val blocks: List<AnnBlock> = emptyList(),
    val prefix: String = "",
    val suffix: String = "",
    val issueDescription: String = "",
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
    val largeFileMode: Boolean = false,
    val analysis: LogAnalysis = LogAnalysis(),
    // Live tailing (utils/FileTailer.kt) is a session-only feature — never persisted to autosave,
    // always resets to false on relaunch. The actual FileTailer/Job lives in a private map on
    // AppState (not here — not data-class-friendly), this field only drives the UI indicator.
    val tailing: Boolean = false,
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
    val kwHighlightEnabled: Boolean = true,
    val kwHighlightColor: Color = DEFAULT_KEYWORD_HIGHLIGHT_COLOR,
)

// ── Settings ───────────────────────────────────────────────────────
enum class AnnotationLogBlockStyle { INDENTED, JIRA_JAVA }

// Which filter input Ctrl/Cmd+F focuses (see FilterPanel's focusSearchRequest LaunchedEffect).
enum class CtrlFTarget { TAGS, MESSAGE_RULE, KEYWORD_REGEX }

data class AppSettings(
    val theme: ThemePreset = ThemePreset.LIGHT,
    val fontSize: Int = 12,
    val fontMono: Boolean = true,
    val defaultSaveDir: String? = null,
    val mostUsedTagLimit: Int = 5,
    val filterListRows: Int = 5,
    val visibleTabLimit: Int = 8,
    val autoExportNotes: Boolean = true,
    val autoSaveFilters: Boolean = true,
    val annotationLogBlockStyle: AnnotationLogBlockStyle = AnnotationLogBlockStyle.JIRA_JAVA,
    val numberAnnotationBlocks: Boolean = false,
    val annotationPrefixLabel: String = "From",
    val navScrollMargin: Int = 5,
    val logRowWrapLimitChars: Int = 480,
    val autoLogRowWrap: Boolean = true,
    // MCP/debug control server (see debug/ControlServer.kt). Settings-driven on/off — separate
    // from the ephemeral OPENLOG_DEBUG_CONTROL env var / -Dopenlog.debugControl override, which
    // never persists into this setting (see AppState.startControlServerForThisSessionOnly).
    val mcpControlEnabled: Boolean = false,
    val mcpControlPort: Int = 8991,
    // Some issue trackers (e.g. certain Jira instances) reject comments containing the literal
    // word "java" outside a code block. This masks a configurable whole word when copying a
    // note's Markdown (copyAnn) — the {code:java}/{code} fence markers are never touched.
    val maskWordOnCopy: Boolean = false,
    val maskWordTarget: String = "java",
    val maskWordReplacement: String = "j*ava",
    // An expanded crash/stack-trace group's member rows only get a thin left-edge stripe by
    // default (see LogRow's groupColor drawBehind) — the header alone gets the full red
    // background+text tint. Enabling this extends that full tint to every row in the group.
    val highlightEntireCrashGroup: Boolean = false,
    val ctrlFTarget: CtrlFTarget = CtrlFTarget.KEYWORD_REGEX,
    val openNewFilesWithUnfiltered: Boolean = false,
)

enum class ThemePreset(val label: String) {
    LIGHT("Light"),
    LIGHT_PRO("Light Pro"),
    LIGHT_CONTRAST("Light Contrast"),
    NORD_LIGHT("Nord Light"),
    WARM_PAPER("Warm Paper"),
    SAGE_PAPER("Sage Paper"),
    ROSE_PAPER("Rose Paper"),
    INK_PAPER("Ink Paper"),
    CATPPUCCIN_LATTE("Catppuccin Latte"),
    AQUARIUM_MIST("Aquarium Mist"),
    TIDEPOOL_LIGHT("Tidepool Light"),
    WAVE_FOAM("Wave Foam"),
    TOKYO_NIGHT("Tokyo Night"),
    GRUVBOX("Gruvbox"),
    DEEP_CURRENT("Deep Current"),
    DARK_GITHUB("Dark (GitHub)"),
    DRACULA("Dracula"),
    SOLARIZED_DARK("Solarized Dark"),
    GRAPHITE_DIM("Graphite Dim"),
    TERMINAL_DARK("Terminal Dark"),
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

// Right-click on a tab (not a log row) — deliberately separate from CtxMenuState, whose
// entryId/selText/panelSelectedIds fields don't apply here.
data class TabCtxMenuState(val tabId: String, val x: Float, val y: Float)

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

    /** Auto-folded exception/stack-trace run (see utils/StackTraceComputer.kt) — always-on,
     *  not user-configured, so unlike SeqHeader there's no backing def/color to look up. */
    data class StackTraceHeader(
        val entry: LogEntry,
        val gid: String,
        val indent: Int,
        val expanded: Boolean,
        val count: Int,
    ) : LogItem()
}
