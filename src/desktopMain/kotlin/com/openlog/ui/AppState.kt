package com.openlog.ui

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import com.openlog.ai.AiContextRequest
import com.openlog.ai.AiEvidence
import com.openlog.ai.AiInvestigationContext
import com.openlog.ai.AiPromptRequest
import com.openlog.ai.AiQuickAction
import com.openlog.ai.AiSessionRegistry
import com.openlog.ai.AiSidebarRuntime
import com.openlog.ai.CustomAiCommand
import com.openlog.ai.CustomAiCommandName
import com.openlog.ai.normalizeAiProviderProfiles
import com.openlog.ai.validateAiProviderProfile
import com.openlog.debug.ControlServer
import com.openlog.debug.OpenLogToolOperations
import com.openlog.debug.loadOrCreateControlToken
import com.openlog.model.*
import com.openlog.source.FileMeta
import com.openlog.source.LogCallSite
import com.openlog.source.LogSourceResolver
import com.openlog.source.SOURCE_INDEX_VERSION
import com.openlog.source.SourceCodeView
import com.openlog.source.SourceIndex
import com.openlog.source.SourceIndexBuildOptions
import com.openlog.source.SourceIndexStatus
import com.openlog.source.SourceIndexStore
import com.openlog.source.SourceIndexer
import com.openlog.source.SourceMatch
import com.openlog.source.sourceConfigurationFingerprint
import com.openlog.utils.ArchiveBudgetExceededException
import com.openlog.utils.EntryIdMap
import com.openlog.utils.MAX_ARCHIVE_ENTRY_BYTES
import com.openlog.utils.MergeSourceFile
import com.openlog.utils.SPLIT_PROMPT_BYTES
import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.buildMd
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeItems
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.exportFilteredToFile
import com.openlog.utils.extractCandidate
import com.openlog.utils.invalidateComputeCache
import com.openlog.utils.isLikelyTextFile
import com.openlog.utils.isSupportedArchiveFile
import com.openlog.utils.listArchiveLogCandidates
import com.openlog.utils.mergeLogs
import com.openlog.utils.openArchiveCandidateStream
import com.openlog.utils.parseLogcat
import com.openlog.utils.passesFilter
import com.openlog.utils.planSplitOutputs
import com.openlog.utils.requiresSplitPrompt
import com.openlog.utils.splitFileToFiles
import com.openlog.utils.splitStreamToFiles
import com.openlog.utils.suggestedSplitPartCount
import kotlinx.coroutines.*
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal fun mkRmap(data: List<LogEntry>): Map<Int, LogEntry> = EntryIdMap(data)

// Separators the "Hide/Show messages like this" (and "Add as sequence") flyout truncates a
// message at — the same rough set a human skimming logcat output uses to separate a message's
// stable/templated part from its variable tail, e.g. "Card stack expanded: stackId=stack_home"
// truncates at ':' first ("Card stack expanded") then at '=' ("Card stack expanded: stackId").
private val MESSAGE_RULE_SEPARATORS = charArrayOf('-', '/', '\\', ',', '.', ':', '=')

// Scope + pattern for one "Hide/Show messages like this" choice. Kept outside AppState so the
// message-rule search can offer exactly the same stable-prefix variants as the log-row flyout.
data class MessageRuleVariant(val label: String, val pattern: String, val tag: String?)

internal fun messageRuleVariantsForEntry(entry: LogEntry, selectedText: String? = null): List<MessageRuleVariant> {
    val selected = selectedText?.trim().orEmpty()
    if (selected.isNotBlank()) {
        return listOf(
            MessageRuleVariant("${entry.tag}: $selected", selected, entry.tag),
            MessageRuleVariant(selected, selected, null),
        )
    }

    fun truncateAtSeparator(n: Int): String {
        var count = 0
        for (i in entry.msg.indices) {
            if (entry.msg[i] in MESSAGE_RULE_SEPARATORS) {
                count++
                if (count == n) return entry.msg.substring(0, i).trimEnd()
            }
        }
        return entry.msg
    }
    val toFirst = truncateAtSeparator(1)
    val toSecond = truncateAtSeparator(2)
    return buildList {
        add(MessageRuleVariant("${entry.tag}: $toFirst", toFirst, entry.tag))
        if (toSecond != toFirst) add(MessageRuleVariant("${entry.tag}: $toSecond", toSecond, entry.tag))
        add(MessageRuleVariant(toFirst, toFirst, null))
        if (toSecond != toFirst) add(MessageRuleVariant(toSecond, toSecond, null))
    }.take(4)
}

// Loop safety cap for resolveNoteTarget's "_2", "_3", ... disambiguation walk — effectively
// unreachable in practice (that many genuinely distinct files sharing one display name), just a
// hard stop against an unbounded loop.
private const val MAX_NOTE_TARGET_SUFFIX = 1000

internal fun buildLogAnalysis(data: List<LogEntry>): LogAnalysis {
    val stackGroups = computeStackTraceGroups(data)
    return LogAnalysis(
        tagCounts = data.groupingBy { it.tag }.eachCount(),
        stackTraceGroups = stackGroups,
        crashSites = computeCrashSites(data, stackGroups),
        pending = false,
    )
}

// What a freshly loaded tab carries while the stack/crash analysis still runs in the background:
// tag counts immediately (cheap, the filter panel needs them), folding/crash data deferred.
private fun pendingAnalysis(data: List<LogEntry>): LogAnalysis =
    LogAnalysis(tagCounts = data.groupingBy { it.tag }.eachCount(), pending = true)

internal fun logEntryMarkdownLine(entry: LogEntry): String =
    "**[${entry.ts}] `${entry.level.key}/${entry.tag}`:** ${entry.msg}"

fun mkTab(
    id: String,
    filename: String,
    logData: List<LogEntry>,
    analysis: LogAnalysis = buildLogAnalysis(logData),
) = LogTab(
    id = id, filename = filename, logData = logData, rmap = mkRmap(logData),
    annotations = Annotations(prefix = "From $filename"),
    analysis = analysis,
)

fun emptyWorkspaceTab() = LogTab(
    id = "untitled",
    filename = "Untitled workspace",
    logData = emptyList(),
    rmap = emptyMap(),
    annotations = Annotations(),
    // Explicit rather than relying on LogAnalysis's pending-by-default: logData is always empty
    // here, so a genuinely-computed analysis would be empty too — this is vacuously complete,
    // not "not yet analyzed," and shouldn't show an "Analyzing…" hint that will never resolve.
    analysis = LogAnalysis(pending = false),
)

// AtomicInteger, not a plain var (A-01): openFileInternal/openZipEntry/mergeTabs/addTab/
// loadSplitPartAsTab each capture one id via getAndIncrement() on whichever thread calls them —
// the Compose/AWT UI thread, the single-instance accept thread, a Ktor/ControlServer request
// thread, or an ioScope coroutine — with no coordination between those callers otherwise. A plain
// `tabCounter++` (a non-atomic read-increment-write) let two callers on different threads read the
// same value before either wrote back, allocating the same tab id twice.
private val tabCounter = AtomicInteger(1)

private fun defaultAutosaveFile(): File =
    DesktopStorage.autosaveFile()

internal const val ANNOTATION_PANEL_MIN_WIDTH = 360f
internal const val ANNOTATION_PANEL_MAX_WIDTH = 500f
internal const val FILTER_PANEL_MIN_WIDTH = 140f
internal const val FILTER_PANEL_MAX_WIDTH = 420f
internal const val COMPARE_SPLIT_MIN = 0.2f
internal const val COMPARE_SPLIT_MAX = 0.8f
internal const val RIGHT_SIDEBAR_SPLIT_MIN = 0.2f
internal const val RIGHT_SIDEBAR_SPLIT_MAX = 0.8f
internal const val MIN_PORT = 1
internal const val MAX_PORT = 65535
internal const val DEFAULT_MCP_PORT = 8991

// Auto-detect fallback chain for AppState.openInEditor, tried in order when settings.editorCommand
// is blank — the first one whose CLI is actually on PATH (i.e. ProcessBuilder.start() succeeds)
// wins. Each template supports jumping straight to a line, unlike plain Desktop.open().
private val AUTO_EDITOR_COMMANDS = listOf(
    "code -g {file}:{line}",
    "idea --line {line} {file}",
    "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea --line {line} {file}",
    "cursor -g {file}:{line}",
    "subl {file}:{line}",
)

// Extra places to look for an editor CLI besides $PATH. A GUI app (and the Gradle daemon in dev)
// doesn't inherit the user's interactive shell PATH, so `code`/`idea`/etc. are typically missing
// from System.getenv("PATH") even though they're on the user's normal shell PATH or installed via
// JetBrains Toolbox. Order doesn't matter much here since executableSearchDirs already puts the
// real PATH first; these are just a fallback net.
private val COMMON_EXECUTABLE_DIRS = listOf(
    "/usr/local/bin",
    "/opt/homebrew/bin",
    "/usr/bin",
    "/bin",
)

private fun userHomeExecutableDirs(): List<String> {
    val home = System.getProperty("user.home") ?: return emptyList()
    return listOf(
        "$home/.local/bin",
        "$home/bin",
        // JetBrains Toolbox shell-script launchers (where `idea` usually lives when installed via
        // Toolbox rather than a system package manager).
        "$home/Library/Application Support/JetBrains/Toolbox/scripts",
        "$home/.local/share/JetBrains/Toolbox/scripts",
    )
}

// Directories to search for an editor CLI's executable, in priority order: the inherited PATH
// first (so an explicit user PATH entry still wins), then the common locations above. De-duped
// and filtered to dirs that actually exist.
internal fun executableSearchDirs(): List<File> {
    val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
    val candidates = pathDirs + COMMON_EXECUTABLE_DIRS + userHomeExecutableDirs()
    return candidates.filter { it.isNotBlank() }.distinct().map { File(it) }.filter { it.isDirectory }
}

// Resolves the first token of an editor command template to an absolute, executable file. If
// `command` already contains a path separator it's treated as an explicit path (checked directly,
// not searched for); otherwise each of `dirs` is searched in order for a file named `command`.
// Returns null (never throws) if nothing executable is found, so callers can fall through to the
// next auto-detect candidate instead of letting ProcessBuilder.start() throw an IOException.
// Requires a regular file (not a directory): a directory on the search path or on an explicit
// path can return true from `canExecute()` (traversable), which would otherwise let a directory
// prefix of a space-containing path (see splitEditorCommand below) be wrongly accepted here.
internal fun resolveExecutable(command: String, dirs: List<File> = executableSearchDirs()): File? {
    if (command.contains('/') || command.contains(File.separatorChar)) {
        val file = File(command)
        return file.takeIf { it.exists() && it.isFile && it.canExecute() }
    }
    return dirs.asSequence()
        .map { File(it, command) }
        .firstOrNull { it.exists() && it.isFile && it.canExecute() }
}

// Splits an editor command template into its executable and the remaining raw (not-yet-
// substituted) argument tokens, correctly handling an executable path that itself contains
// spaces (e.g. a JetBrains Toolbox script under "…/Application Support/…"). A naive whitespace
// split would shatter such a path across multiple tokens and never find the real executable.
//
// Tokenizes on whitespace, then greedily grows a prefix of tokens (rejoined with single spaces)
// until [resolveExecutable] resolves it to a real file — the smallest such prefix wins, so a bare
// command (`idea --line {line} {file}`) resolves at the very first token, while a multi-token
// absolute path resolves once enough tokens have been rejoined to name the actual file.
// `resolveExecutable`'s `isFile` requirement is what makes this safe: intermediate directory
// prefixes of a space-containing path (`/…/Application`, `/…/Application Support`) are directories
// and so are correctly skipped rather than being greedily (and wrongly) accepted early.
// Returns null if no prefix resolves to an executable file.
internal fun splitEditorCommand(template: String, dirs: List<File> = executableSearchDirs()): Pair<File, List<String>>? {
    val rawTokens = template.split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (rawTokens.isEmpty()) return null
    // Strip a surrounding quote pair from just the first token, if present — a quoted executable
    // path (`"idea" --line …`) is otherwise indistinguishable from a literal leading quote char.
    val tokens = rawTokens.toMutableList().also { it[0] = it[0].trim('\'', '"') }
    for (k in 1..tokens.size) {
        val candidate = tokens.subList(0, k).joinToString(" ")
        val resolved = resolveExecutable(candidate, dirs) ?: continue
        return resolved to tokens.subList(k, tokens.size)
    }
    return null
}

// Resolves an editor template and substitutes its location placeholders. Keeping this separate
// from [launchEditor] makes the exact process arguments testable, especially for the Toolbox
// launcher path which contains spaces.
internal fun editorCommandArguments(
    template: String,
    file: File,
    line: Int,
    dirs: List<File> = executableSearchDirs(),
): List<String>? {
    val (executable, argTokens) = splitEditorCommand(template, dirs) ?: return null
    val args = argTokens.map { token ->
        token.replace("{file}", file.absolutePath).replace("{line}", line.toString())
    }
    return listOf(executable.absolutePath) + args
}

// Launches the exact command assembled by [editorCommandArguments]. Doesn't waitFor() — editors
// fork and return immediately — and discards the child's output so an editor CLI that writes to
// stdout/stderr can't fill an unread pipe. Returns false (never throws) if the command executable
// can't be resolved or fails to start.
private fun launchEditor(template: String, file: File, line: Int): Boolean = runCatching {
    val command = editorCommandArguments(template, file, line) ?: return false
    ProcessBuilder(command)
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    true
}.getOrDefault(false)

// Above this size, item computation runs on a background dispatcher with a loading line instead
// of synchronously during composition. 64MB (~500k lines) is roughly where a computeItems pass
// starts exceeding a frame budget by orders of magnitude; the old 512MB threshold left files in
// the 100-512MB range freezing the UI on every filter keystroke.
private const val LARGE_FILE_MODE_BYTES = 64L * 1024L * 1024L

data class PendingSequenceStart(val text: String, val tag: String)

data class PendingFilterLoad(val tabId: String, val targetFilterId: String, val currentFilterId: String?)

data class PendingDuplicateFilterSave(val tabId: String, val existingId: String, val existingName: String, val requestedName: String)

data class PendingFilterRename(val id: String, val currentName: String, val isDraft: Boolean, val tabId: String?)

enum class ImportFilterAction { RENAME, REPLACE, SKIP, ADD }

private fun isTransientRegexOnlyChange(before: Filter, after: Filter): Boolean {
    fun Filter.withoutTransientRegexSearch() = copy(
        mode = FilterMode.TAGS,
        kwText = "",
        kwRegex = false,
        kwHighlightEnabled = true,
        kwHighlightColor = DEFAULT_KEYWORD_HIGHLIGHT_COLOR,
    )
    return before.withoutTransientRegexSearch() == after.withoutTransientRegexSearch()
}

enum class ManualCollapseAvailability { AVAILABLE, MISSING_ROW, NOOP_RANGE, OVERLAPS_EXISTING }

private fun manualCollapseRange(tab: LogTab, anchorId: Int, direction: ManualCollapseDirection, endId: Int? = null): IntRange? {
    val dataIds = tab.logData.map { it.id }
    val anchor = dataIds.indexOf(anchorId).takeIf { it >= 0 } ?: return null
    return when (direction) {
        ManualCollapseDirection.TO_START -> 0..anchor
        ManualCollapseDirection.TO_END -> anchor..dataIds.lastIndex
        ManualCollapseDirection.RANGE -> {
            val end = endId?.let { dataIds.indexOf(it) }?.takeIf { it >= 0 } ?: return null
            minOf(anchor, end)..maxOf(anchor, end)
        }
    }
}

private fun rangesOverlap(a: IntRange, b: IntRange): Boolean =
    a.first <= b.last && b.first <= a.last

internal fun manualCollapseAvailability(
    tab: LogTab,
    anchorId: Int,
    direction: ManualCollapseDirection,
    endId: Int? = null,
): ManualCollapseAvailability {
    val range = manualCollapseRange(tab, anchorId, direction, endId)
        ?: return ManualCollapseAvailability.MISSING_ROW
    if (range.first == range.last) return ManualCollapseAvailability.NOOP_RANGE
    // Disabled blocks remain part of the document and may be re-enabled later, so allowing a
    // new overlapping range here would create an invalid block layout on the next toggle.
    val overlapsExisting = tab.manualBlocks
        .mapNotNull { manualCollapseRange(tab, it.anchorId, it.direction, it.endId) }
        .any { existing -> rangesOverlap(existing, range) }
    return if (overlapsExisting) ManualCollapseAvailability.OVERLAPS_EXISTING else ManualCollapseAvailability.AVAILABLE
}

data class ImportFilterReviewRow(
    val rowId: String,
    val incoming: SavedFilter,
    val action: ImportFilterAction,
    val resolvedName: String,
    val targetId: String? = null,
    val skippedReason: String? = null,
)

data class PendingImportReview(val rows: List<ImportFilterReviewRow>, val sourceName: String? = null)

data class OpenFileError(val title: String, val path: String?, val message: String)

data class AnnotationNavigationRequest(val id: Long, val tabId: String, val logIds: List<Int>)

data class PendingZipPicker(val zipFile: File, val candidates: List<ZipLogCandidate>)

enum class SplitMode { SPLIT, OPEN_AS_IS }

sealed class SplitSource {
    abstract val id: String
    abstract val displayName: String
    abstract val sizeBytes: Long
    abstract val sourceFile: File

    data class RealFile(val file: File) : SplitSource() {
        override val id: String = "file:${file.absolutePath}"
        override val displayName: String = file.name
        override val sizeBytes: Long = file.length()
        override val sourceFile: File = file
    }

    data class ArchiveEntry(val archiveFile: File, val candidate: ZipLogCandidate) : SplitSource() {
        override val id: String = "archive:${archiveFile.absolutePath}!${candidate.entryPath}"
        override val displayName: String = candidate.displayName
        override val sizeBytes: Long = candidate.sizeBytes
        override val sourceFile: File = archiveFile
    }
}

data class DeferredArchiveEntry(val archiveFile: File, val candidate: ZipLogCandidate)

data class PendingSplitPrompt(
    val sources: List<SplitSource>,
    val deferredFiles: List<File> = emptyList(),
    val deferredArchiveEntries: List<DeferredArchiveEntry> = emptyList(),
)

// Every parameter beyond the first few is a test seam with a real-default value (autosaveFile,
// archiveEntryByteBudget, sourceIndexFile, etc. — see their own doc comments below), not
// prop-drilled config a caller assembles by hand. Splitting them into a config object would just
// move the same test-injection surface one level down, not reduce it.
@Suppress("LongParameterList")
class AppState(
    private val autosaveFile: File = defaultAutosaveFile(),
    restoreOnCreate: Boolean = false,
    private val parser: (File) -> List<LogEntry> = ::parseLogcat,
    private val notesDir: File = DesktopStorage.notesDir(),
    private val archiveCacheDir: File = DesktopStorage.archiveCacheDir(),
    private val customCommandsDir: File = DesktopStorage.customCommandsDir(),
    private val filterBackupsDir: File? = null,
    private val autoExportNotes: Boolean = true,
    // Test seam for the S-03 archive extraction budget (openZipEntry): production uses the real
    // 500MB default so tests can exercise the ArchiveBudgetExceededException/showOpenError path
    // with small fixtures instead of needing multi-hundred-MB archives.
    private val archiveEntryByteBudget: Long = MAX_ARCHIVE_ENTRY_BYTES,
    // Where the control-server auth token is persisted across restarts (loadOrCreateControlToken)
    // so the user doesn't have to re-copy the MCP client config after every launch. Injectable so
    // tests that actually start a control server via the default factory below don't touch the
    // real ~/.openlog2 — same reasoning as autosaveFile.
    private val controlTokenFile: File = DesktopStorage.controlTokenFile(),
    // Where the persisted source-call-site index (Task 2, source/SourceIndexStore.kt) lives across
    // restarts. Injectable for the same reason as autosaveFile/controlTokenFile — tests shouldn't
    // touch the real ~/.openlog2-equivalent location.
    private val sourceIndexFile: File = DesktopStorage.sourceIndexFile(),
    // Test seam for the S-02 lifecycle race (applyControlServerState): production always
    // constructs-and-starts a real ControlServer synchronously; tests can substitute a slower
    // suspend factory (e.g. one that delays before starting) to exercise enable/disable ordering
    // without needing a fake ControlServer subclass.
    private val controlServerFactory: suspend (AppState, Int) -> ControlServer = { s, p ->
        ControlServer(s, p, token = loadOrCreateControlToken(controlTokenFile)).also { it.start() }
    },
) {
    // ── Settings ────────────────────────────────────────────────────
    var settings by mutableStateOf(AppSettings())

    // Session-only by construction: AppSettings is the only settings object serialized into
    // autosave.cache, so pasted API keys cannot reach an autosave or exported-settings path.
    private val aiProviderApiKeys = ConcurrentHashMap<String, String>()

    // The AI panel owns current-launch conversation state. Keeping the registry here lets tab
    // closure cancel its in-flight request without adding any AI fields to LogTab/autosave.
    internal val aiSessions = AiSessionRegistry()
    internal val aiSidebarRuntime = AiSidebarRuntime(
        sessions = aiSessions,
        toolGatewayFactory = { OpenLogToolOperations(this).toolGateway },
        maxToolRounds = { settings.aiMaxToolRounds },
        accountAgentRunnerFactory = {
            com.openlog.ai.AccountAgentRunner(
                managedMcpServerFactory = { run -> com.openlog.ai.ManagedMcpServerLease.start(this, run) },
                maxToolRounds = settings.aiMaxToolRounds,
            )
        },
    )

    // Lifted out of AiSidebarPanel's own composition (rather than a local `remember`) because
    // toggling aiPanelVisible off unmounts that whole composable, which would otherwise discard
    // this along with every other `remember`ed UI state in it.
    internal var aiSidebarExpandedSection by mutableStateOf<AiSidebarSection?>(null)

    /** Session-only request produced by a log context menu or AI quick action. */
    internal var pendingAiPromptRequest by mutableStateOf<AiPromptRequest?>(null)

    /** Session-only annotation target used by evidence cards; never written into notes/autosave. */
    internal var aiEvidenceNoteTarget by mutableStateOf<AiEvidence.Note?>(null)

    // User-defined prompt templates, one `.md` file per command under customCommandsDir (mirrors
    // the notesDir pattern) — not part of AppSettings/autosave since they're disk-backed, not
    // settings-token-backed. Filename (sans extension) doubles as the /slash-name.
    internal var customAiCommands by mutableStateOf<List<CustomAiCommand>>(emptyList())

    /** Editor dialog target: a blank CustomAiCommand("", "") means "new"; null means closed. */
    internal var customCommandEditorTarget by mutableStateOf<CustomAiCommand?>(null)

    /** Source folder path whose SourceFolderInfoDialog is open; null means closed. */
    internal var sourceFolderInfoEditorTarget by mutableStateOf<String?>(null)

    private fun loadCustomAiCommands() {
        customAiCommands = customCommandsDir.listFiles { f -> f.isFile && f.extension.equals("md", ignoreCase = true) }
            ?.mapNotNull { f -> runCatching { CustomAiCommand(f.nameWithoutExtension, f.readText()) }.getOrNull() }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
    }

    /** Returns an error message on failure, null on success. Renaming = save under the new name, then delete the old file. */
    fun saveCustomAiCommand(name: String, promptTemplate: String, previousName: String? = null): String? {
        if (!CustomAiCommandName.isValid(name)) {
            return "Command name must be non-blank and use only letters, digits, - or _."
        }
        runCatching {
            customCommandsDir.mkdirs()
            File(customCommandsDir, "$name.md").writeText(promptTemplate)
            if (previousName != null && !previousName.equals(name, ignoreCase = true)) {
                File(customCommandsDir, "$previousName.md").delete()
            }
        }.onFailure { return it.message ?: "Failed to save command" }
        loadCustomAiCommands()
        return null
    }

    fun deleteCustomAiCommand(name: String) {
        runCatching { File(customCommandsDir, "$name.md").delete() }
        loadCustomAiCommands()
    }

    fun aiProviderApiKey(profileId: String): String = aiProviderApiKeys[profileId].orEmpty()

    fun setAiProviderApiKey(profileId: String, apiKey: String) {
        if (profileId.isBlank()) return
        if (apiKey.isBlank()) aiProviderApiKeys.remove(profileId) else aiProviderApiKeys[profileId] = apiKey
    }

    fun clearAiProviderApiKey(profileId: String) {
        aiProviderApiKeys.remove(profileId)
    }

    /**
     * Pins an investigation to the concrete tab/line that initiated it and makes that tab own the
     * sidebar.  We deliberately switch the active tab before rendering the AI sidebar in compare
     * mode too: otherwise the sidebar could visually imply that a right-pane request belonged to
     * the left tab.
     */
    internal fun requestAiInvestigation(tabId: String, action: AiQuickAction, lineId: Int? = null): Boolean {
        val tab = tab(tabId) ?: return false
        val resolvedLineId = lineId ?: tab.selected.minOrNull()
        if (action.requiresLine && (resolvedLineId == null || resolvedLineId !in tab.rmap)) return false
        if (resolvedLineId != null && resolvedLineId !in tab.rmap) return false
        activateTab(tabId)
        aiPanelVisible = true
        pendingAiPromptRequest = AiPromptRequest(
            context = AiInvestigationContext(tabId = tabId, lineId = resolvedLineId, action = action),
            prompt = action.prompt,
        )
        ctx = null
        return true
    }

    internal fun requestAiAboutLine(tabId: String, lineId: Int): Boolean =
        requestAiInvestigation(tabId, AiQuickAction.LOG_LINE, lineId)

    /** Same pipeline as requestAiInvestigation but for a user-defined command: always enabled, no line requirement. */
    internal fun requestAiCustomCommand(tabId: String, command: CustomAiCommand): Boolean {
        tab(tabId) ?: return false
        activateTab(tabId)
        aiPanelVisible = true
        pendingAiPromptRequest = AiPromptRequest(
            context = AiInvestigationContext(tabId = tabId, lineId = null, action = null),
            prompt = command.promptTemplate,
        )
        ctx = null
        return true
    }

    internal fun consumeAiPromptRequest(id: String) {
        if (pendingAiPromptRequest?.id == id) pendingAiPromptRequest = null
    }

    /** Session-only request to attach selected lines as an AI context chip, produced by the log context menu. */
    internal var pendingAiContextRequest by mutableStateOf<AiContextRequest?>(null)

    internal fun requestAiContext(tabId: String, lineIds: List<Int>): Boolean {
        val tab = tab(tabId) ?: return false
        val validIds = lineIds.distinct().filter { it in tab.rmap }
        if (validIds.isEmpty()) return false
        activateTab(tabId)
        aiPanelVisible = true
        pendingAiContextRequest = AiContextRequest(tabId = tabId, lineIds = validIds)
        ctx = null
        return true
    }

    internal fun consumeAiContextRequest(id: String) {
        if (pendingAiContextRequest?.id == id) pendingAiContextRequest = null
    }

    /** Navigate a card only through IDs/paths returned by the tool gateway, never model text. */
    internal fun navigateAiEvidence(evidence: AiEvidence) {
        when (evidence) {
            is AiEvidence.LogRows -> requestAiLogNavigation(evidence.tabId, evidence.lineIds)
            is AiEvidence.Source -> {
                sourceCodeView = SourceCodeView(
                    listOf(
                        SourceMatch(
                            site = LogCallSite(
                                filePath = evidence.filePath,
                                tag = evidence.tag,
                                methodName = evidence.methodName,
                                methodStartLine = evidence.methodStartLine,
                                methodEndLine = evidence.methodEndLine,
                                callLine = evidence.callLine,
                                matcher = "",
                                literalLen = 0,
                            ),
                            confidence = evidence.confidence,
                            stale = evidence.stale,
                        ),
                    ),
                )
            }
            is AiEvidence.Note -> {
                val tab = tab(evidence.tabId) ?: return
                if (tab.annotations.blocks.none { it.id == evidence.blockId }) return
                activateTab(evidence.tabId)
                updateAnnotationVisible(true)
                aiEvidenceNoteTarget = evidence
            }
        }
    }

    fun addAiProviderProfile(): AiProviderProfile {
        val profiles = normalizeAiProviderProfiles(settings.aiProviderProfiles)
        val profile = (profiles.firstOrNull { it.selected } ?: profiles.first()).copy(
            id = UUID.randomUUID().toString(),
            displayName = "Provider ${profiles.size + 1}",
            selected = true,
        )
        updateSettings { it.copy(aiProviderProfiles = normalizeAiProviderProfiles(profiles + profile)) }
        return profile
    }

    /**
     * Returns a validation message when a draft is unsafe. A changed endpoint is still persisted
     * with its acknowledgement cleared, so a remote profile cannot retain consent from its old URL.
     */
    fun updateAiProviderProfile(profile: AiProviderProfile): String? {
        val profiles = normalizeAiProviderProfiles(settings.aiProviderProfiles)
        val current = profiles.firstOrNull { it.id == profile.id } ?: return "Provider profile was not found."
        // An acknowledgement applies to one endpoint only. Persist the changed endpoint with the
        // acknowledgement cleared so the UI can explicitly ask again before a remote provider is
        // used; this also prevents a host switch from silently inheriting prior consent.
        val updated = if (current.baseUrl.trim() != profile.baseUrl.trim()) {
            profile.copy(remoteDisclosureAcknowledged = false)
        } else {
            profile
        }
        val validation = validateAiProviderProfile(updated)
        if (!validation.isValid) {
            if (current.baseUrl.trim() != profile.baseUrl.trim() &&
                validation.problem == com.openlog.ai.AiProviderUrlProblem.REMOTE_DISCLOSURE_REQUIRED
            ) {
                updateSettings {
                    it.copy(aiProviderProfiles = normalizeAiProviderProfiles(profiles.map {
                        if (it.id == updated.id) updated else it
                    }))
                }
            }
            return validation.problem!!.message
        }
        updateSettings {
            it.copy(aiProviderProfiles = normalizeAiProviderProfiles(profiles.map {
                if (it.id == updated.id) updated else it
            }))
        }
        return null
    }

    fun selectAiProviderProfile(profileId: String) {
        val profiles = normalizeAiProviderProfiles(settings.aiProviderProfiles)
        if (profiles.none { it.id == profileId }) return
        updateSettings { it.copy(aiProviderProfiles = profiles.map { it.copy(selected = it.id == profileId) }) }
    }

    fun removeAiProviderProfile(profileId: String): Boolean {
        val profiles = normalizeAiProviderProfiles(settings.aiProviderProfiles)
        if (profiles.size <= 1 || profiles.none { it.id == profileId }) return false
        aiProviderApiKeys.remove(profileId)
        updateSettings { it.copy(aiProviderProfiles = normalizeAiProviderProfiles(profiles.filterNot { it.id == profileId })) }
        return true
    }

    // ── Source index (Task 2) ──────────────────────────────────────────
    // sourceIndex/sourceIndexStatus/sourceCodeView are the only pieces observed by Compose;
    // sourceResolver is a plain cache rebuilt alongside sourceIndex (see publishSourceIndex) so a
    // per-row lookup (resolveLogSource) never recompiles every site's matcher regex on the hot path.
    var sourceIndex by mutableStateOf<SourceIndex?>(null)
        private set
    var sourceCodeView by mutableStateOf<SourceCodeView?>(null)

    // Reindexing is per source folder (see reindexSources) — this tracks which folder paths
    // (absolute) currently have a scan in flight, purely so the Settings UI can disable that
    // folder's own "Reindex" button and no other's while a build runs.
    var indexingFolders by mutableStateOf<Set<String>>(emptySet())
        private set
    private var sourceResolver: LogSourceResolver? = null

    // ── Layout ──────────────────────────────────────────────────────
    var filterVisible by mutableStateOf(true)
    var annotationVisible by mutableStateOf(true)

    // Notes and the AI panel are independent visibility toggles sharing one sidebar slot (see
    // RightSidebarPanel): both on splits it vertically, Notes above AI, sized by rightSidebarSplit.
    var aiPanelVisible by mutableStateOf(false)
    var rightSidebarSplit by mutableStateOf(0.5f)
    var filterPanelWidth by mutableStateOf(220f)
    var annotationPanelWidth by mutableStateOf(ANNOTATION_PANEL_MIN_WIDTH)
    var compareSplit by mutableStateOf(0.5f)
    var compareFilterRight by mutableStateOf(true)
    var isLoading by mutableStateOf(false)
    val logViewerScrollStateStore = LogViewerScrollStateStore()

    // Which log panel (by panelKey, e.g. "<tabId>:main") the pointer is currently over, read by
    // the Linux X11 horizontal-scroll AWT bridge (see ui/LinuxHorizontalScroll.kt, installed from
    // Main.kt) to resolve which ScrollState a button-6/7 press should scroll. Deliberately a plain
    // var, NOT mutableStateOf — this updates on every pointer Enter/Exit and must never trigger a
    // recomposition.
    var hoveredLogPanelKey: String? = null

    private val ioJob = SupervisorJob()
    private val ioScope = CoroutineScope(ioJob + Dispatchers.IO)

    // internal, not private: TailCoordinator (Task 12 slice 2) synchronizes on this exact monitor
    // object so its tabs-list writes stay atomic with AppState's own — see upTab's doc comment.
    internal val stateLock = Any()
    private var pendingLoads = 0

    // See ControlServerManager's own doc comment for what it owns and why it still takes `this`.
    private val controlServerManager = ControlServerManager(this, ioScope, controlTokenFile, controlServerFactory)

    // Forwards ControlServerManager's mutableStateOf field — Compose observes it the same way
    // regardless of which class instance actually declares the underlying State object.
    val mcpControlError: String? get() = controlServerManager.mcpControlError

    // See AutosaveScheduler's own doc comment for what it owns (when/how often) vs. what stays on
    // AppState (what actually gets encoded/decoded). The serialize lambda references
    // serializeAutosave() below by name — legal here since member visibility in a Kotlin class
    // doesn't depend on textual declaration order.
    private val autosaveScheduler = AutosaveScheduler(autosaveFile, ioScope, serialize = { serializeAutosave() })

    val autosaveError: String? get() = autosaveScheduler.autosaveError

    // See AnnotationManager's own doc comment for what it owns vs. what stays on AppState.
    private val annotationManager = AnnotationManager(this)

    // App.kt writes this directly (dialog dismiss/confirm), not just reads it, so it needs a
    // full get/set forwarding var rather than a read-only getter like mcpControlError above.
    var addAnnRequest: AddAnnRequest?
        get() = annotationManager.addAnnRequest
        set(value) { annotationManager.addAnnRequest = value }

    // Live tailing (utils/FileTailer.kt) — owned by TailCoordinator (Task 12 slice 2), not
    // AppState itself; ioJob.cancel() in close() still cancels every tailer's Job for free, since
    // each is started on ioScope.
    private val tailCoordinator = TailCoordinator(this, ioScope)

    private data class ActiveLoad(val job: Job, val countsAsLoading: AtomicBoolean = AtomicBoolean(true))

    private val activeLoads = ConcurrentHashMap<String, ActiveLoad>()
    private val pendingRestoredLoads = mutableListOf<RestoredTabShell>()

    // Latest filtered item summary per tab, pushed by LogViewer whenever its (possibly
    // background-computed) item list lands. Selection ops reuse it instead of recomputing the
    // full item list synchronously on the UI thread — on a multi-million-line tab that recompute
    // took seconds per shift-click. May briefly lag the filter during a background recompute,
    // exactly like the on-screen list it mirrors.
    private val visibleItemsByTab = ConcurrentHashMap<String, ItemsSummary>()

    fun noteVisibleItems(tabId: String, summary: ItemsSummary) {
        visibleItemsByTab[tabId] = summary
    }

    // ── Sequences (per-tab, stored in Filter) ────────────────────────
    var sequences: List<SequenceDef>
        get() = activeTab()?.filter?.sequences ?: emptyList()
        set(value) { upFlt(activeTabId) { it.copy(sequences = value) } }

    // ── Tabs ────────────────────────────────────────────────────────
    var tabs by mutableStateOf(emptyList<LogTab>())
    var activeTabId by mutableStateOf("")
    var compareMode by mutableStateOf(false)
    var compareTabId by mutableStateOf("")
    var loadingStatus by mutableStateOf<String?>(null)

    val canCompare: Boolean get() = tabs.size > 1

    // ── Transient UI ─────────────────────────────────────────────────
    // True right after a keyboard-driven panel focus change (F6/Shift+F6, Cmd+1/2/3/F); set
    // false on any pointer press. Panels only draw their accent focus border while this is
    // true — mirrors the CSS :focus-visible pattern so mouse clicks don't outline the panel.
    var keyboardFocusVisible by mutableStateOf(false)
    var ctx by mutableStateOf<CtxMenuState?>(null)
    var tabCtx by mutableStateOf<TabCtxMenuState?>(null)
    var sfDialog by mutableStateOf(false)
    var sfName by mutableStateOf("")
    var sfTabId by mutableStateOf<String?>(null)
    var savedFilters by mutableStateOf<List<SavedFilter>>(emptyList())
    var tagUsage by mutableStateOf<Map<String, Int>>(emptyMap())
    var settingsOpen by mutableStateOf(false)

    /** One-shot Settings destination for contextual controls such as the AI provider picker. */
    internal var requestedSettingsSection by mutableStateOf<SettingsSection?>(null)
    var shortcutsOpen by mutableStateOf(false)
    var mcpInfoOpen by mutableStateOf(false)

    var openError by mutableStateOf<OpenFileError?>(null)
    var recentFiles by mutableStateOf<List<String>>(emptyList())
    var recentMenuOpen by mutableStateOf(false)
    var recentNotes by mutableStateOf<List<String>>(emptyList())
    var recentNotesMenuOpen by mutableStateOf(false)
    var cacheClearConfirmOpen by mutableStateOf(false)
    val archiveCachePath: String = archiveCacheDir.absolutePath
    val appCachePath: String = archiveCacheDir.parentFile?.absolutePath ?: archiveCacheDir.absolutePath
    /** Recursive size of the app-data directory displayed in Settings. */
    var appDataSizeBytes by mutableStateOf(0L)
        private set
    /** Compatibility alias for callers that still use the previous cache-only name. */
    val archiveCacheSizeBytes: Long get() = appDataSizeBytes
    var pendingSequenceStart by mutableStateOf<PendingSequenceStart?>(null)
    var pendingFilterLoad by mutableStateOf<PendingFilterLoad?>(null)
    var updateExistingPickerOpen by mutableStateOf(false)
    var pendingDuplicateFilterSave by mutableStateOf<PendingDuplicateFilterSave?>(null)
    var pendingFilterRename by mutableStateOf<PendingFilterRename?>(null)
    var filterRenameName by mutableStateOf("")
    var filterRenameError by mutableStateOf<String?>(null)
    var pendingImportReview by mutableStateOf<PendingImportReview?>(null)
    var importError by mutableStateOf<String?>(null)
    var filterExportDialogOpen by mutableStateOf(false)
    var filterExportSelectedIds by mutableStateOf<Set<String>>(emptySet())
    var pendingDeleteFilterId by mutableStateOf<String?>(null)
    var pendingClearFilterTabId by mutableStateOf<String?>(null)
    var activeSavedFilterIds by mutableStateOf<Map<String, String>>(emptyMap())
    var filterDraftsByTab by mutableStateOf<Map<String, SavedFilter>>(emptyMap())
    var activeFilterDraftTabIds by mutableStateOf<Set<String>>(emptySet())

    // Regex search is deliberately transient: it keeps the current saved-filter marker visible,
    // without becoming a draft or blocking a later click on a saved preset.
    private var transientRegexSearchTabIds by mutableStateOf<Set<String>>(emptySet())
    var pendingAnnotationNavigation by mutableStateOf<AnnotationNavigationRequest?>(null)
        private set
    var pendingZipPicker by mutableStateOf<PendingZipPicker?>(null)
    var pendingSplitPrompt by mutableStateOf<PendingSplitPrompt?>(null)
        private set
    var mergeTabsDialogOpen by mutableStateOf(false)

    // Read once into the merge dialog's initial selection when opened via a tab's context menu
    // "Merge…" item — not observed reactively, so a plain var is fine.
    var mergeTabsPreselectedId: String? = null

    val fpState = FilterPanelUiState()

    var newHlPat by mutableStateOf("")
    var newHlRx by mutableStateOf(false)
    var newHlColor by mutableStateOf(HL_COLORS[0])

    var newSeqText by mutableStateOf("")
    var newSeqRegex by mutableStateOf(false)
    var newSeqEndText by mutableStateOf("")
    var newSeqEndRegex by mutableStateOf(false)
    var newSeqTag by mutableStateOf("")
    var newSeqEndTag by mutableStateOf("")
    var newSeqColor by mutableStateOf(SEQ_COLORS[0])
    private var annotationNavigationCounter = 0L

    init {
        refreshAppDataSizeInfo()
        if (restoreOnCreate) restoreAutosave()
        loadPersistedSourceIndex()
        loadCustomAiCommands()
    }

    // ── Helpers ─────────────────────────────────────────────────────

    fun close() {
        aiProviderApiKeys.clear()
        aiSidebarRuntime.close()
        aiSessions.clear()
        ioJob.cancel() // also cancels every active FileTailer's Job — each is started on ioScope
        tailCoordinator.clear()
        activeLoads.clear()
        controlServerManager.applyControlServerState(enabled = false, port = 0)
        synchronized(stateLock) {
            pendingRestoredLoads.clear()
            pendingLoads = 0
            isLoading = false
        }
    }

    // Manual escape hatch for the "still loading" prompt the UI shows after a long stretch of
    // isLoading — surfaced so a load that's genuinely stuck (not just slow) doesn't leave the
    // user with no way back into the app short of force-quitting. Each ActiveLoad owns exactly one
    // loading counter slot, and cancelActiveLoad releases that slot immediately instead of waiting
    // for a stuck parser/extractor to observe coroutine cancellation.
    fun cancelAllLoads() {
        activeLoads.keys.toList().forEach { cancelActiveLoad(it) }
        clearLoadingState()
    }

    // Control-server lifecycle, enable/disable, and Connection-info accessors are owned by
    // ControlServerManager (Task 12 slice 1) — see its doc comments for the S-02/A-01 race-guard
    // rationale these forward to unchanged.
    fun setMcpControlEnabled(enabled: Boolean, port: Int) = controlServerManager.setMcpControlEnabled(enabled, port)

    // Main.kt's OPENLOG_DEBUG_CONTROL/-Dopenlog.debugControl path: an ephemeral dev/CI override
    // for this run only — deliberately never touches persisted settings, so a one-off env-var
    // launch doesn't silently turn the server on for every future normal launch.
    fun startControlServerForThisSessionOnly(port: Int) =
        controlServerManager.applyControlServerState(true, port.coerceIn(MIN_PORT, MAX_PORT))

    // Main.kt's shutdown path — stops the server without touching persisted settings, for the
    // same reason: closing the app must not silently flip a user's saved "enabled" preference to
    // false, or it would never auto-start again on the next launch.
    fun stopControlServerForShutdown() = controlServerManager.applyControlServerState(enabled = false, port = 0)

    fun connectedMcpClients() = controlServerManager.connectedMcpClients()

    fun blockMcpClient(id: String) = controlServerManager.blockMcpClient(id)

    fun unblockMcpClient(id: String) = controlServerManager.unblockMcpClient(id)

    fun mcpSessions() = controlServerManager.mcpSessions()

    fun disconnectMcpSession(id: String) = controlServerManager.disconnectMcpSession(id)

    fun controlServerToken(): String? = controlServerManager.controlServerToken()

    // Connection info is useful before the server is enabled too: it lets a user copy the stable
    // client configuration first, then enable the switch. Read the running server's token when
    // available; otherwise load the same persisted token the next server start will use.
    fun connectionInfoToken(): String = controlServerManager.controlServerToken() ?: loadOrCreateControlToken(controlTokenFile)

    fun rotateControlToken() = controlServerManager.rotateControlToken()

    fun startPendingRestoredTabLoads() {
        val loads = synchronized(stateLock) {
            pendingRestoredLoads.toList().also { pendingRestoredLoads.clear() }
        }
        loads.forEach { shell -> scheduleRestoredTabLoad(shell.tab.id, shell.source) }
    }

    private fun beginLoading(status: String = "Loading file...") = synchronized(stateLock) {
        pendingLoads += 1
        isLoading = true
        loadingStatus = status
    }

    private fun finishLoading() = synchronized(stateLock) {
        if (pendingLoads > 0) pendingLoads -= 1
        isLoading = pendingLoads > 0
        if (!isLoading) loadingStatus = null
    }

    private fun clearLoadingState() = synchronized(stateLock) {
        pendingLoads = 0
        isLoading = false
        loadingStatus = null
    }

    private fun finishActiveLoad(load: ActiveLoad?) {
        if (load?.countsAsLoading?.compareAndSet(true, false) == true) finishLoading()
    }

    private fun markActiveLoadFinished(tabId: String) {
        finishActiveLoad(activeLoads[tabId])
    }

    private fun cancelActiveLoad(tabId: String) {
        val load = activeLoads.remove(tabId) ?: return
        finishActiveLoad(load)
        load.job.cancel()
    }

    fun updateFilterVisible(visible: Boolean) {
        if (filterVisible == visible) return
        filterVisible = visible
        autosaveNow()
    }

    fun updateAnnotationVisible(visible: Boolean) {
        if (annotationVisible == visible) return
        annotationVisible = visible
        autosaveNow()
    }

    fun updateAiPanelVisible(visible: Boolean) {
        if (aiPanelVisible == visible) return
        aiPanelVisible = visible
        autosaveNow()
    }

    fun updateRightSidebarSplit(split: Float) {
        val next = split.coerceIn(RIGHT_SIDEBAR_SPLIT_MIN, RIGHT_SIDEBAR_SPLIT_MAX)
        if (rightSidebarSplit == next) return
        rightSidebarSplit = next
        autosaveInBackground()
    }

    fun updateCompareMode(enabled: Boolean) {
        val next = enabled && canCompare
        if (compareMode == next) return
        compareMode = next
        if (next && compareTabId == activeTabId) {
            // Left and right panes must never show the same tab — they'd share one
            // LazyListState (keyed by tab id) and silently fight over scroll ownership.
            compareTabId = tabs.firstOrNull { it.id != activeTabId }?.id ?: ""
        }
        autosaveNow()
    }

    fun updateCompareFilterRight(visible: Boolean) {
        if (compareFilterRight == visible) return
        compareFilterRight = visible
        autosaveNow()
    }

    fun updateFilterPanelWidth(width: Float) {
        val next = width.coerceIn(FILTER_PANEL_MIN_WIDTH, FILTER_PANEL_MAX_WIDTH)
        if (filterPanelWidth == next) return
        filterPanelWidth = next
        autosaveInBackground()
    }

    fun updateAnnotationPanelWidth(width: Float) {
        val next = width.coerceIn(ANNOTATION_PANEL_MIN_WIDTH, ANNOTATION_PANEL_MAX_WIDTH)
        if (annotationPanelWidth == next) return
        annotationPanelWidth = next
        autosaveInBackground()
    }

    fun updateCompareSplit(split: Float) {
        val next = split.coerceIn(COMPARE_SPLIT_MIN, COMPARE_SPLIT_MAX)
        if (compareSplit == next) return
        compareSplit = next
        autosaveInBackground()
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings)
        if (settings == next) return
        settings = next
        autosaveNow()
    }

    fun openAiProviderSettings() {
        requestedSettingsSection = SettingsSection.AiProviders
        settingsOpen = true
    }

    fun updateFilterPanelUiState(update: FilterPanelUiState.() -> Unit) {
        fpState.update()
        autosaveNow()
    }

    // Every read-modify-write of the tabs list goes through stateLock (reentrant, so background
    // callers already holding it are fine): background fills — restored-tab loads, tailing
    // flushes, async opens — write tabs under the lock, and an unsynchronized UI-thread
    // `tabs = tabs + t` racing one of those could lose either update (observed as a flaky
    // "restored tab counter" test once autosave restore became async).
    fun upTab(tabId: String, fn: (LogTab) -> LogTab) = synchronized(stateLock) {
        tabs = tabs.map { if (it.id == tabId) fn(it) else it }
    }

    fun upFlt(tabId: String, fn: (Filter) -> Filter) = upFlt(tabId, trackDraft = true, fn)

    // Debounced FilterPanel fields (kwDisplay, msgRuleInput, ...) re-push their current value
    // through upFlt once their LaunchedEffect settles after every recomposition — including one
    // triggered by nothing more than switching tabs away and back, which re-initializes their
    // remember(tab.id) state from the filter's current value. That re-push is a no-op content-
    // wise, but upFlt used to treat any call as an edit regardless, demoting an untouched tab's
    // active saved preset to an unsaved draft just from revisiting it. Only actual content
    // changes should count as an edit.
    // The before/upTab/after sequence is wrapped in one synchronized(stateLock) block (A-01): read
    // outside a lock, a concurrent tabs mutation landing between the "before" read and upTab's own
    // write (or between that write and the "after" read) could make the before/after comparison
    // below stale, mis-deciding whether this edit should demote an active saved preset to a draft.
    // upTab's own synchronized(stateLock) is safe to nest here (reentrant monitor).
    private fun upFlt(tabId: String, trackDraft: Boolean, fn: (Filter) -> Filter) = synchronized(stateLock) {
        val before = tab(tabId)?.filter
        upTab(tabId) { it.copy(filter = fn(it.filter)) }
        val after = tab(tabId)?.filter
        if (before != null && before != after) {
            val transientOnlyChange =
                tabId in transientRegexSearchTabIds && after != null && isTransientRegexOnlyChange(before, after)
            if (tabId in transientRegexSearchTabIds && !transientOnlyChange) {
                transientRegexSearchTabIds = transientRegexSearchTabIds - tabId
            }
            if (trackDraft && !transientOnlyChange) updateFilterDraftAfterEdit(tabId)
        }
    }

    fun activeTab() = tabs.find { it.id == activeTabId } ?: tabs.firstOrNull()

    fun tab(id: String) = tabs.find { it.id == id }

    // ── Filter ──────────────────────────────────────────────────────
    fun toggleLevel(tabId: String, lvl: LogLevel) = upFlt(tabId) { f ->
        f.copy(levels = if (lvl in f.levels) f.levels - lvl else f.levels + lvl)
    }

    fun setFilterMode(tabId: String, mode: FilterMode) = upFlt(tabId) { it.copy(mode = mode) }

    // Regex is primarily a transient search surface. Entering it from another filter mode keeps
    // the current preset selected and avoids an automatic "unsaved" draft; the user can still
    // explicitly save the resulting regex from Saved filters when it should become a preset.
    fun startRegexSearch(tabId: String) {
        val enteringRegex = tab(tabId)?.filter?.mode != FilterMode.KEYWORD
        upFlt(tabId, trackDraft = false) { it.copy(mode = FilterMode.KEYWORD, kwRegex = true) }
        if (enteringRegex) {
            transientRegexSearchTabIds = transientRegexSearchTabIds + tabId
        }
    }

    fun toggleTag(tabId: String, tag: String) {
        upFlt(tabId) { f ->
            val adding = tag !in f.activeTags
            f.copy(
                activeTags = if (adding) f.activeTags + tag else f.activeTags - tag,
                excludeTags = if (adding) f.excludeTags - tag else f.excludeTags,
            )
        }
        tagUsage = tagUsage + (tag to ((tagUsage[tag] ?: 0) + 1))
    }

    fun clearTags(tabId: String) = upFlt(tabId) { it.copy(activeTags = emptySet()) }

    fun setKw(tabId: String, v: String) = upFlt(tabId) { it.copy(kwText = v) }

    fun toggleKwRx(tabId: String) = upFlt(tabId) { it.copy(kwRegex = !it.kwRegex) }

    fun setKwHighlightEnabled(tabId: String, enabled: Boolean) =
        upFlt(tabId) { it.copy(kwHighlightEnabled = enabled) }

    fun setKwHighlightColor(tabId: String, color: Color) =
        upFlt(tabId) { it.copy(kwHighlightColor = color) }

    fun toggleSeq(tabId: String) = upFlt(tabId) { it.copy(seqOn = !it.seqOn) }

    fun clearFilter(tabId: String) {
        upFlt(tabId, trackDraft = false) { Filter() }
        activeSavedFilterIds = activeSavedFilterIds - tabId
        clearDraftForTab(tabId)
        transientRegexSearchTabIds = transientRegexSearchTabIds - tabId
    }

    fun requestClearFilter(tabId: String) {
        pendingClearFilterTabId = tabId
    }

    fun cancelClearFilter() {
        pendingClearFilterTabId = null
    }

    fun confirmClearFilter() {
        val tabId = pendingClearFilterTabId ?: return
        clearFilter(tabId)
        pendingClearFilterTabId = null
    }

    fun toggleExcludeTag(tabId: String, tag: String) = upFlt(tabId) { f ->
        val adding = tag !in f.excludeTags
        f.copy(
            excludeTags = if (adding) f.excludeTags + tag else f.excludeTags - tag,
            activeTags = if (adding) f.activeTags - tag else f.activeTags,
        )
    }.also { tagUsage = tagUsage + (tag to ((tagUsage[tag] ?: 0) + 1)) }

    fun setExcludeKw(tabId: String, v: String) = upFlt(tabId) { it.copy(excludeKw = v) }

    fun toggleExcludeKwRx(tabId: String) = upFlt(tabId) { it.copy(excludeKwRegex = !it.excludeKwRegex) }

    fun setKwInTag(tabId: String, v: String) = upFlt(tabId) { it.copy(kwInTag = v) }

    fun toggleKwInTagRx(tabId: String) = upFlt(tabId) { it.copy(kwInTagRegex = !it.kwInTagRegex) }

    fun addPkgPrefix(tabId: String, v: String) {
        if (v.isNotBlank()) upFlt(tabId) {
            val prefix = v.trim()
            it.copy(pkgPrefixes = it.pkgPrefixes + prefix, excludePkgPrefixes = it.excludePkgPrefixes - prefix)
        }
    }

    fun removePkgPrefix(tabId: String, v: String) = upFlt(tabId) { it.copy(pkgPrefixes = it.pkgPrefixes - v) }

    fun addExcludePkgPrefix(tabId: String, v: String) {
        if (v.isNotBlank()) upFlt(tabId) {
            val prefix = v.trim()
            it.copy(excludePkgPrefixes = it.excludePkgPrefixes + prefix, pkgPrefixes = it.pkgPrefixes - prefix)
        }
    }

    fun removeExcludePkgPrefix(tabId: String, v: String) =
        upFlt(tabId) { it.copy(excludePkgPrefixes = it.excludePkgPrefixes - v) }

    fun setPidTidFilter(tabId: String, v: String) = upFlt(tabId) { it.copy(pidTidFilter = v) }

    fun addMessageRule(
        tabId: String,
        include: Boolean,
        pattern: String,
        regex: Boolean,
        tag: String?,
        packagePrefix: String?,
        target: RuleTarget = RuleTarget.MESSAGE,
    ) {
        if (pattern.isBlank()) return
        upFlt(tabId) { f ->
            val rule = MessageRule(
                id = "mr${System.currentTimeMillis()}_${pattern.hashCode()}",
                include = include,
                pattern = pattern,
                regex = regex,
                tag = tag?.trim()?.takeIf { it.isNotBlank() },
                packagePrefix = packagePrefix?.trim()?.takeIf { it.isNotBlank() },
                target = target,
                mode = f.mode,
            )
            val sameShape: (MessageRule) -> Boolean = { existing ->
                existing.pattern == rule.pattern && existing.regex == rule.regex &&
                    existing.tag == rule.tag && existing.packagePrefix == rule.packagePrefix &&
                    existing.target == rule.target && existing.mode == rule.mode
            }
            val withoutOpposite = f.messageRules.filterNot { sameShape(it) && it.include != include }
            f.copy(
                messageRules = if (withoutOpposite.any { sameShape(it) && it.include == include }) {
                    withoutOpposite
                } else {
                    withoutOpposite + rule
                },
            )
        }
    }

    fun toggleMessageRule(tabId: String, id: String) = upFlt(tabId) { f ->
        f.copy(messageRules = f.messageRules.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun removeMessageRule(tabId: String, id: String) = upFlt(tabId) { f ->
        f.copy(messageRules = f.messageRules.filterNot { it.id == id })
    }

    // ── Highlighters ────────────────────────────────────────────────
    fun addHl(tabId: String, pat: String, rx: Boolean, color: Color) {
        if (pat.isBlank()) return
        upFlt(tabId) { f ->
            f.copy(
                highlighters = f.highlighters + Highlighter(
                    "hl${System.currentTimeMillis()}",
                    pat,
                    rx,
                    color,
                    true
                )
            )
        }
        newHlPat = ""
        newHlColor = HL_COLORS[(HL_COLORS.indexOf(color) + 1) % HL_COLORS.size]
    }

    fun removeHl(tabId: String, id: String) =
        upFlt(tabId) { f -> f.copy(highlighters = f.highlighters.filter { it.id != id }) }

    // Finds the enabled highlighter that the given selection is an exact instance of — a plain
    // pattern must equal the (trimmed) selection verbatim, a regex pattern must fully match it —
    // so the ctx menu can offer "Remove highlight" only when the user selected a fully highlighted
    // span, not just any text that happens to overlap one.
    private fun highlighterMatchingSelection(highlighters: List<Highlighter>, selText: String): Highlighter? {
        val sel = selText.trim()
        if (sel.isBlank()) return null
        return highlighters.filter { it.on }.firstOrNull { hl ->
            if (hl.regex) {
                runCatching { Regex(hl.pattern, RegexOption.IGNORE_CASE).matches(sel) }.getOrDefault(false)
            } else {
                hl.pattern.equals(sel, ignoreCase = true)
            }
        }
    }

    fun matchingHighlighterId(tabId: String, selText: String): String? =
        highlighterMatchingSelection(tab(tabId)?.filter?.highlighters.orEmpty(), selText)?.id

    fun removeHlFromCtx() {
        val c = ctx ?: return
        val hl = highlighterMatchingSelection(tab(c.tabId)?.filter?.highlighters.orEmpty(), c.selText) ?: return
        removeHl(c.tabId, hl.id)
        ctx = null
    }

    fun toggleHl(tabId: String, id: String) = upFlt(tabId) { f ->
        f.copy(highlighters = f.highlighters.map { if (it.id == id) it.copy(on = !it.on) else it })
    }

    fun setHighlighterColor(tabId: String, id: String, color: Color) = upFlt(tabId) { f ->
        f.copy(highlighters = f.highlighters.map { if (it.id == id) it.copy(color = color) else it })
    }

    // ── Sequences ───────────────────────────────────────────────────
    private fun nextSequenceColor(from: Color, existing: List<SequenceDef> = sequences): Color {
        val start = SEQ_COLORS.indexOf(from).takeIf { it >= 0 } ?: 0
        val used = existing.map { it.color }.toSet()
        for (offset in 0 until SEQ_COLORS.size) {
            val candidate = SEQ_COLORS[(start + offset) % SEQ_COLORS.size]
            if (candidate !in used) return candidate
        }
        return SEQ_COLORS[start]
    }

    private fun colorAfterSequenceColor(color: Color): Color {
        val start = SEQ_COLORS.indexOf(color).takeIf { it >= 0 } ?: 0
        return nextSequenceColor(SEQ_COLORS[(start + 1) % SEQ_COLORS.size])
    }

    fun addSequence(
        text: String,
        isRegex: Boolean,
        color: Color,
        tag: String? = null,
        endText: String? = null,
        endIsRegex: Boolean = false,
        endTag: String? = null,
    ) {
        if (text.isBlank()) return
        upFlt(activeTabId) { f ->
            val maxP = f.sequences.maxOfOrNull { it.priority } ?: 0
            val assignedColor = nextSequenceColor(color, f.sequences)
            newSeqColor = colorAfterSequenceColor(assignedColor)
            f.copy(
                sequences = f.sequences + SequenceDef(
                    "seq${System.currentTimeMillis()}",
                    text,
                    isRegex,
                    maxP + 1,
                    assignedColor,
                    tag = tag?.trim()?.takeIf { it.isNotBlank() },
                    endMatchText = endText?.takeIf { it.isNotBlank() },
                    endIsRegex = endIsRegex,
                    endTag = endTag?.trim()?.takeIf { it.isNotBlank() },
                )
            )
        }
        newSeqText = ""
        newSeqEndText = ""
        newSeqTag = ""
        newSeqEndTag = ""
    }

    fun updateSequence(
        id: String,
        matchText: String,
        isRegex: Boolean,
        tag: String?,
        endMatchText: String?,
        endIsRegex: Boolean,
        endTag: String?,
    ) {
        if (matchText.isBlank()) return
        upFlt(activeTabId) { f ->
            f.copy(
                sequences = f.sequences.map {
                    if (it.id != id) it else it.copy(
                        matchText = matchText,
                        isRegex = isRegex,
                        tag = tag?.trim()?.takeIf { value -> value.isNotBlank() },
                        endMatchText = endMatchText?.takeIf { value -> value.isNotBlank() },
                        endIsRegex = endIsRegex,
                        endTag = endTag?.trim()?.takeIf { value -> value.isNotBlank() },
                    )
                }
            )
        }
    }

    fun removeSequence(id: String) {
        upFlt(activeTabId) { f -> f.copy(sequences = f.sequences.filter { it.id != id }) }
    }

    fun toggleSequence(id: String) {
        upFlt(activeTabId) { f ->
            f.copy(sequences = f.sequences.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
        }
    }

    fun setSequenceColor(id: String, color: Color) {
        upFlt(activeTabId) { f ->
            f.copy(sequences = f.sequences.map { if (it.id == id) it.copy(color = color) else it })
        }
    }

    fun reorderSequence(fromId: String, toIdx: Int) {
        upFlt(activeTabId) { f ->
            val list = f.sequences.toMutableList()
            val fromIdx = list.indexOfFirst { it.id == fromId }.takeIf { it >= 0 } ?: return@upFlt f
            val item = list.removeAt(fromIdx)
            list.add(toIdx.coerceIn(0, list.size), item)
            f.copy(sequences = list.mapIndexed { i, s -> s.copy(priority = i + 1) })
        }
    }

    fun moveSequenceUp(id: String) {
        upFlt(activeTabId) { f ->
            val idx = f.sequences.indexOfFirst { it.id == id }.takeIf { it > 0 } ?: return@upFlt f
            val list = f.sequences.toMutableList()
            val a = list[idx - 1]; val b = list[idx]
            list[idx - 1] = b.copy(priority = a.priority); list[idx] = a.copy(priority = b.priority)
            f.copy(sequences = list)
        }
    }

    fun moveSequenceDown(id: String) {
        upFlt(activeTabId) { f ->
            val idx = f.sequences.indexOfFirst { it.id == id }.takeIf { it < f.sequences.size - 1 } ?: return@upFlt f
            val list = f.sequences.toMutableList()
            val a = list[idx]; val b = list[idx + 1]
            list[idx] = b.copy(priority = a.priority); list[idx + 1] = a.copy(priority = b.priority)
            f.copy(sequences = list)
        }
    }

    // ── Saved filters ────────────────────────────────────────────────
    private fun draftIdForTab(tabId: String) = "draft_$tabId"

    private fun draftNameForTab(tabId: String): String =
        "unsaved_${tab(tabId)?.filename ?: tabId}"

    fun filterDraftForTab(tabId: String): SavedFilter? = filterDraftsByTab[tabId]

    fun savedFiltersForTab(tabId: String): List<SavedFilter> =
        listOfNotNull(filterDraftsByTab[tabId]) + savedFilters

    private fun updateFilterDraftAfterEdit(tabId: String) {
        val hasActiveNormal = tabId in activeSavedFilterIds
        val hasActiveDraft = tabId in activeFilterDraftTabIds
        if (!hasActiveNormal && !hasActiveDraft) return
        val draft = snapshotFilter(tabId, draftIdForTab(tabId), draftNameForTab(tabId)) ?: return
        filterDraftsByTab = filterDraftsByTab + (tabId to draft)
        activeSavedFilterIds = activeSavedFilterIds - tabId
        activeFilterDraftTabIds = activeFilterDraftTabIds + tabId
    }

    private fun clearDraftForTab(tabId: String) {
        filterDraftsByTab = filterDraftsByTab - tabId
        activeFilterDraftTabIds = activeFilterDraftTabIds - tabId
    }

    private fun savedFilterNameExists(name: String, exceptId: String? = null): Boolean {
        val clean = name.trim()
        return savedFilters.any { it.id != exceptId && it.name.trim().equals(clean, ignoreCase = true) }
    }

    fun saveFilter(tabId: String, name: String) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val existing = savedFilters.find { it.name.trim().equals(cleanName, ignoreCase = true) }
        if (existing != null) {
            pendingDuplicateFilterSave = PendingDuplicateFilterSave(tabId, existing.id, existing.name, cleanName)
            return
        }
        val sf = snapshotFilter(tabId, "sf${System.nanoTime()}_${savedFilters.size}", cleanName) ?: return
        savedFilters = savedFilters + sf
        activeSavedFilterIds = activeSavedFilterIds + (tabId to sf.id)
        clearDraftForTab(tabId)
        transientRegexSearchTabIds = transientRegexSearchTabIds - tabId
        sfDialog = false; sfName = ""
        writeFilterBackup()
        loadPendingFilterAfterSaving(tabId)
    }

    fun confirmReplaceDuplicateFilter() {
        val pending = pendingDuplicateFilterSave ?: return
        val updated = snapshotFilter(pending.tabId, pending.existingId, pending.requestedName) ?: return
        savedFilters = savedFilters.map { if (it.id == pending.existingId) updated else it }
        activeSavedFilterIds = activeSavedFilterIds + (pending.tabId to pending.existingId)
        clearDraftForTab(pending.tabId)
        transientRegexSearchTabIds = transientRegexSearchTabIds - pending.tabId
        pendingDuplicateFilterSave = null
        sfDialog = false
        sfName = ""
        writeFilterBackup()
        loadPendingFilterAfterSaving(pending.tabId)
    }

    fun cancelDuplicateFilterSave() {
        pendingDuplicateFilterSave = null
    }

    fun requestLoadFilter(tabId: String, sf: SavedFilter) {
        if (tabId in transientRegexSearchTabIds) {
            loadFilter(tabId, sf)
            return
        }
        if (sf.id == filterDraftsByTab[tabId]?.id) {
            upFlt(tabId, trackDraft = false) { _ -> sf.toFilter() }
            activeSavedFilterIds = activeSavedFilterIds - tabId
            activeFilterDraftTabIds = activeFilterDraftTabIds + tabId
            return
        }
        val currentId = activeSavedFilterIds[tabId]
        val current = currentId?.let { id -> savedFilters.find { it.id == id } }
        if (current != null && !currentFilterMatches(tabId, current)) {
            pendingFilterLoad = PendingFilterLoad(tabId, sf.id, current.id)
            return
        }
        if (current == null && !currentFilterIsEmpty(tabId)) {
            pendingFilterLoad = PendingFilterLoad(tabId, sf.id, null)
            return
        }
        loadFilter(tabId, sf)
    }

    // "Update existing" always opens a picker rather than silently overwriting whatever preset
    // happened to be active — the common case is editing a preset (which immediately demotes the
    // tab to an unsaved draft, see updateFilterDraftAfterEdit), so there usually isn't a single
    // obvious "current" preset left to update by the time this dialog shows.
    fun beginUpdateExistingPick() {
        if (pendingFilterLoad == null) return
        updateExistingPickerOpen = true
    }

    fun cancelUpdateExistingPick() {
        updateExistingPickerOpen = false
    }

    fun confirmUpdateExisting(presetId: String) {
        val pending = pendingFilterLoad ?: return
        val target = savedFilters.find { it.id == presetId } ?: return
        val updated = snapshotFilter(pending.tabId, presetId, target.name) ?: return
        savedFilters = savedFilters.map { if (it.id == presetId) updated else it }
        clearDraftForTab(pending.tabId)
        updateExistingPickerOpen = false
        writeFilterBackup()
        loadPendingFilter(pending)
    }

    fun cancelPendingFilterLoad() {
        pendingFilterLoad = null
        updateExistingPickerOpen = false
    }

    fun discardPendingFilterChangesAndLoad() {
        val pending = pendingFilterLoad ?: return
        loadPendingFilter(pending)
    }

    fun beginSavePendingFilterAsNew() {
        val pending = pendingFilterLoad ?: return
        sfDialog = true
        sfTabId = pending.tabId
        val currentName = pending.currentFilterId?.let { id -> savedFilters.find { it.id == id }?.name }
        sfName = currentName?.let { "$it copy" } ?: ""
    }

    private fun snapshotFilter(tabId: String, id: String, name: String): SavedFilter? {
        val t = tab(tabId) ?: return null
        val f = t.filter
        return SavedFilter(
            id, name, f.levels, f.activeTags, f.kwText, f.kwRegex,
            f.mode, f.excludeTags, f.excludeKw, f.excludeKwRegex, f.highlighters, f.seqOn,
            f.kwInTag, f.kwInTagRegex, f.pkgPrefixes, f.pidTidFilter, f.sequences, f.messageRules,
            f.excludePkgPrefixes, f.kwHighlightEnabled, f.kwHighlightColor,
        )
    }

    private fun currentFilterMatches(tabId: String, sf: SavedFilter): Boolean {
        val current = snapshotFilter(tabId, sf.id, sf.name) ?: return false
        return current == sf
    }

    private fun currentFilterIsEmpty(tabId: String): Boolean {
        val t = tab(tabId) ?: return true
        return t.filter == Filter()
    }

    private fun loadPendingFilterAfterSaving(tabId: String) {
        val pending = pendingFilterLoad?.takeIf { it.tabId == tabId } ?: return
        loadPendingFilter(pending)
    }

    private fun loadPendingFilter(pending: PendingFilterLoad) {
        val target = savedFilters.find { it.id == pending.targetFilterId } ?: run {
            pendingFilterLoad = null
            return
        }
        pendingFilterLoad = null
        loadFilter(pending.tabId, target)
    }

    fun loadFilter(tabId: String, sf: SavedFilter) {
        upFlt(tabId, trackDraft = false) { _ -> sf.toFilter() }
        activeSavedFilterIds = activeSavedFilterIds + (tabId to sf.id)
        // Loading a different preset abandons this tab's in-progress unsaved draft (if any) —
        // otherwise it lingers forever in savedFiltersForTab()'s list, never active but never gone.
        clearDraftForTab(tabId)
        transientRegexSearchTabIds = transientRegexSearchTabIds - tabId
    }

    fun loadFilterById(tabId: String, presetId: String): Boolean {
        val sf = savedFilters.find { it.id == presetId } ?: return false
        if (tab(tabId) == null) return false
        loadFilter(tabId, sf)
        return true
    }

    fun activeSavedFilterId(tabId: String): String? = activeSavedFilterIds[tabId]

    fun activeFilterItemId(tabId: String): String? =
        if (tabId in activeFilterDraftTabIds) filterDraftsByTab[tabId]?.id else activeSavedFilterIds[tabId]

    fun requestDeleteSF(id: String) {
        if (savedFilters.any { it.id == id } || filterDraftsByTab.values.any { it.id == id }) pendingDeleteFilterId = id
    }

    fun cancelDeleteSF() {
        pendingDeleteFilterId = null
    }

    fun confirmDeleteSF() {
        val id = pendingDeleteFilterId ?: return
        deleteSF(id)
        pendingDeleteFilterId = null
    }

    private fun deleteSF(id: String) {
        val draftTabId = filterDraftsByTab.entries.firstOrNull { it.value.id == id }?.key
        if (draftTabId != null) {
            filterDraftsByTab = filterDraftsByTab - draftTabId
            activeFilterDraftTabIds = activeFilterDraftTabIds - draftTabId
            return
        }
        savedFilters = savedFilters.filter { it.id != id }
        activeSavedFilterIds = activeSavedFilterIds.filterValues { it != id }
        writeFilterBackup()
    }

    fun beginRenameFilter(id: String) {
        val draftEntry = filterDraftsByTab.entries.firstOrNull { it.value.id == id }
        val current = draftEntry?.value ?: savedFilters.find { it.id == id } ?: return
        pendingFilterRename = PendingFilterRename(id, current.name, draftEntry != null, draftEntry?.key)
        // Promoting a draft creates a new preset shared across every tab — pre-filling the box
        // with the draft's internal placeholder name ("unsaved_<filename>") makes it too easy to
        // confirm without typing a real name, leaving a permanent global preset that still reads
        // "unsaved" and shows up in every tab. Force a deliberate name instead; a rename of an
        // already-named saved preset keeps its current name pre-filled as before.
        filterRenameName = if (draftEntry != null) "" else current.name
        filterRenameError = null
    }

    fun cancelRenameFilter() {
        pendingFilterRename = null
        filterRenameName = ""
        filterRenameError = null
    }

    fun confirmRenameFilter(name: String = filterRenameName) {
        val pending = pendingFilterRename ?: return
        val cleanName = name.trim()
        filterRenameError = when {
            cleanName.isBlank() -> "Filter name cannot be empty."
            savedFilterNameExists(cleanName, exceptId = if (pending.isDraft) null else pending.id) ->
                "A saved filter named \"$cleanName\" already exists."
            else -> null
        }
        if (filterRenameError != null) return
        if (pending.isDraft) {
            val tabId = pending.tabId ?: return
            val draft = filterDraftsByTab[tabId] ?: return
            val promoted = draft.copy(id = "sf${System.nanoTime()}_${savedFilters.size}", name = cleanName)
            savedFilters = savedFilters + promoted
            activeSavedFilterIds = activeSavedFilterIds + (tabId to promoted.id)
            clearDraftForTab(tabId)
        } else {
            savedFilters = savedFilters.map { if (it.id == pending.id) it.copy(name = cleanName) else it }
        }
        pendingFilterRename = null
        filterRenameName = ""
        filterRenameError = null
        writeFilterBackup()
    }

    fun beginExportFilters() {
        filterExportSelectedIds = savedFilters.map { it.id }.toSet()
        filterExportDialogOpen = true
    }

    fun toggleExportFilterSelection(id: String) {
        filterExportSelectedIds = if (id in filterExportSelectedIds) filterExportSelectedIds - id else filterExportSelectedIds + id
    }

    fun cancelExportFilters() {
        filterExportDialogOpen = false
        filterExportSelectedIds = emptySet()
    }

    fun exportFilters(selectedIds: Set<String>? = null): String {
        val filters = selectedIds?.let { ids -> savedFilters.filter { it.id in ids } } ?: savedFilters
        return exportFiltersList(filters)
    }

    fun importFilters(json: String) {
        val imported = decodeFilters(json).getOrElse { return }
        if (imported.isEmpty()) return
        savedFilters = savedFilters + imported
    }

    fun beginImportFilters(json: String, sourceName: String? = null) {
        val imported = decodeFilters(json).getOrElse {
            importError = "Could not read filter file."
            pendingImportReview = null
            return
        }
        beginImportFilterList(imported, sourceName)
    }

    private fun beginImportFilterList(imported: List<SavedFilter>, sourceName: String? = null) {
        if (imported.isEmpty()) {
            importError = "No saved filters found."
            pendingImportReview = null
            return
        }
        val rows = buildImportRows(savedFilters, imported)
        pendingImportReview = PendingImportReview(rows, sourceName)
        importError = null
    }

    fun cancelImportFilters() {
        pendingImportReview = null
    }

    fun setImportFilterAction(rowId: String, action: ImportFilterAction) {
        val review = pendingImportReview ?: return
        pendingImportReview = review.copy(rows = review.rows.map { row ->
            if (row.rowId != rowId) row else row.withImportAction(savedFilters, action)
        })
    }

    fun setImportFilterRename(rowId: String, name: String) {
        val review = pendingImportReview ?: return
        pendingImportReview = review.copy(rows = review.rows.map { row ->
            if (row.rowId != rowId) row else row.copy(action = ImportFilterAction.RENAME, resolvedName = uniqueFilterName(savedFilters, name, row.targetId))
        })
    }

    fun confirmImportFilters() {
        val review = pendingImportReview ?: return
        var next = savedFilters
        var changed = false
        review.rows.forEach { row ->
            when (row.action) {
                ImportFilterAction.ADD, ImportFilterAction.RENAME -> {
                    next = next + row.incoming.copy(id = freshSavedFilterId(savedFilters), name = uniqueNameAgainst(row.resolvedName, next))
                    changed = true
                }

                ImportFilterAction.REPLACE -> {
                    val targetId = row.targetId ?: return@forEach
                    next = next.map { existing ->
                        if (existing.id == targetId) row.incoming.copy(id = existing.id, name = existing.name) else existing
                    }
                    changed = true
                }

                ImportFilterAction.SKIP -> Unit
            }
        }
        savedFilters = next
        pendingImportReview = null
        if (changed) writeFilterBackup()
    }

    private fun writeFilterBackup() {
        if (!settings.autoSaveFilters) return
        val backupsDir = filterBackupsDir ?: return
        runCatching {
            backupsDir.mkdirs()
            val file = File(backupsDir, "filters-${System.currentTimeMillis()}-${System.nanoTime()}.json")
            file.writeText(exportFilters())
            val backups = backupsDir.listFiles { f -> f.isFile && f.extension.equals("json", ignoreCase = true) }
                .orEmpty()
                .sortedByDescending { it.name }
            backups.drop(20).forEach { it.delete() }
        }
    }

    // ── Row selection ────────────────────────────────────────────────
    fun selRow(tabId: String, id: Int, multi: Boolean, range: Boolean) = upTab(tabId) { t ->
        val n = when {
            multi -> if (id in t.selected) t.selected - id else t.selected + id
            range -> {
                // Use the actual visible items list so shift-click doesn't expand through
                // collapsed blocks (same as drag-select which uses visibleIds from LogViewer).
                // Prefer the summary LogViewer already computed; recompute only when no viewer
                // has reported one yet (e.g. headless/control-server callers).
                val visIds = visibleItemsByTab[tabId]?.allIds
                    ?: computeItems(t, true).let { items -> IntArray(items.size) { logItemEntryId(items[it]) } }
                val last = t.selected.lastOrNull { visIds.contains(it) } ?: t.selected.maxOrNull()
                if (last == null) {
                    setOf(id)
                } else {
                    val a = visIds.indexOf(last)
                    val b = visIds.indexOf(id)
                    if (a >= 0 && b >= 0) (minOf(a, b)..maxOf(a, b)).map { visIds[it] }.toSet() else t.selected + id
                }
            }

            else -> if (t.selected == setOf(id)) emptySet() else setOf(id)
        }
        t.copy(selected = n)
    }

    fun selRowRange(tabId: String, fromId: Int, toId: Int) = upTab(tabId) { t ->
        val ids = t.logData.filter { passesFilter(it, t.filter) }.map { it.id }.ifEmpty { t.logData.map { it.id } }
        val a = ids.indexOf(fromId)
        val b = ids.indexOf(toId)
        if (a < 0 || b < 0) return@upTab t
        t.copy(selected = ids.subList(minOf(a, b), maxOf(a, b) + 1).toSet())
    }

    fun setSelectedRows(tabId: String, ids: List<Int>) = upTab(tabId) { t ->
        t.copy(selected = ids.toSet())
    }

    fun clearSelection(tabId: String) = upTab(tabId) { it.copy(selected = emptySet()) }

    fun selectAll(tabId: String) {
        val t = tab(tabId) ?: return
        val ids = visibleItemsByTab[tabId]?.rowIds?.toList()
            ?: computeItems(t, true).filterIsInstance<LogItem.Row>().map { it.entry.id }
        setSelectedRows(tabId, ids)
    }

    fun requestAnnotationNavigation(ownerTabId: String, block: AnnBlock.LogRef) {
        val targetTabId = block.sourceTabId ?: ownerTabId
        if (block.logIds.isEmpty() || tab(targetTabId) == null) return
        if (compareMode && targetTabId != activeTabId) {
            compareTabId = targetTabId
        } else {
            activateTab(targetTabId)
        }
        setSelectedRows(targetTabId, block.logIds)
        annotationNavigationCounter += 1
        pendingAnnotationNavigation = AnnotationNavigationRequest(annotationNavigationCounter, targetTabId, block.logIds)
    }

    fun consumeAnnotationNavigation(id: Long) {
        if (pendingAnnotationNavigation?.id == id) pendingAnnotationNavigation = null
    }

    // Reuses the same jump-to-log-lines mechanism as annotation navigation — a crash-panel entry
    // is just "select and scroll to a log id", the exact thing pendingAnnotationNavigation already
    // does (including auto-expanding whatever collapsed header, sequence or stack-trace, owns it).
    fun requestCrashNavigation(tabId: String, logId: Int) {
        if (tab(tabId) == null) return
        if (compareMode && tabId != activeTabId) {
            compareTabId = tabId
        } else {
            activateTab(tabId)
        }
        setSelectedRows(tabId, listOf(logId))
        annotationNavigationCounter += 1
        pendingAnnotationNavigation = AnnotationNavigationRequest(annotationNavigationCounter, tabId, listOf(logId))
    }

    /** Evidence cards use the established selection/scroll pathway so collapsed groups expand. */
    private fun requestAiLogNavigation(tabId: String, lineIds: List<Int>) {
        val tab = tab(tabId) ?: return
        val actualIds = lineIds.distinct().filter { it in tab.rmap }
        if (actualIds.isEmpty()) return
        if (compareMode && tabId != activeTabId) {
            compareTabId = tabId
        } else {
            activateTab(tabId)
        }
        setSelectedRows(tabId, actualIds)
        annotationNavigationCounter += 1
        pendingAnnotationNavigation = AnnotationNavigationRequest(annotationNavigationCounter, tabId, actualIds)
    }

    // ── Sequence expand/collapse ─────────────────────────────────────
    fun toggleGroup(tabId: String, gid: String) = upTab(tabId) { t ->
        t.copy(expanded = if (gid in t.expanded) t.expanded - gid else t.expanded + gid)
    }

    private fun visibleExpandableGroupIds(t: LogTab): Set<String> {
        var expanded = t.expanded
        while (true) {
            fun idsFrom(applyFilter: Boolean): Set<String> =
                computeItems(t.copy(expanded = expanded), applyFilter)
                    .mapNotNull { item ->
                        when (item) {
                            is LogItem.SeqHeader -> item.gid
                            is LogItem.ManualHeader -> item.gid
                            is LogItem.StackTraceHeader -> item.gid
                            is LogItem.Row -> null
                        }
                    }
                    .toSet()

            val next = expanded + idsFrom(applyFilter = true) +
                if (t.showUnfiltered) idsFrom(applyFilter = false) else emptySet()
            if (next == expanded) return expanded
            expanded = next
        }
    }

    fun expandAll(tabId: String) {
        val t = tab(tabId) ?: return
        if (!t.largeFileMode) {
            upTab(tabId) { it.copy(expanded = visibleExpandableGroupIds(it)) }
            return
        }
        // The iterative reveal loop runs computeItems repeatedly — seconds of work on a large
        // tab, so it can't stay on the UI thread. Same background-apply pattern (stateLock) as
        // appendTailedLines; if the filter changed mid-computation the result is a harmless
        // superset of gids, exactly as if the user had expanded before filtering.
        ioScope.launch(Dispatchers.Default) {
            val expanded = visibleExpandableGroupIds(t)
            ensureActive()
            synchronized(stateLock) {
                upTab(tabId) { it.copy(expanded = expanded) }
            }
        }
    }

    fun collapseAll(tabId: String) = upTab(tabId) { it.copy(expanded = emptySet()) }

    fun toggleUnfiltered(tabId: String) = upTab(tabId) { it.copy(showUnfiltered = !it.showUnfiltered) }

    /** Reveals Original for the active tab without changing any already-visible panel. */
    fun ensureActiveTabUnfiltered() {
        val tabId = activeTabId
        if (tabId.isBlank()) return
        upTab(tabId) { tab -> if (tab.showUnfiltered) tab else tab.copy(showUnfiltered = true) }
    }

    // ── Annotations (block model) ────────────────────────────────────
    // Zip-backed tabs encode sourcePath as "<absZipPath>!<entryPath>" (see openZipEntry) — the
    // bare entry filename alone doesn't say which archive it came from, so this is qualified
    // unconditionally, regardless of whether it collides with the other tab's name.
    private fun archiveQualifiedLabel(sourcePath: String?): String? {
        val bangIdx = sourcePath?.indexOf('!') ?: return null
        if (bangIdx < 0) return null
        val zipPath = sourcePath.substring(0, bangIdx)
        val entryPath = sourcePath.substring(bangIdx + 1)
        return "${File(zipPath).name}/$entryPath"
    }

    // "From <label>" source display for a cross-tab annotation (compare mode, or referencing
    // another open tab). A plain file only gets its parent folder prefixed when its filename
    // actually collides with otherFilename (e.g. comparing two same-named files from different
    // folders) — otherwise the bare filename stays the simple, unqualified default.
    internal fun displaySourceLabel(sourcePath: String?, filename: String, otherFilename: String?): String {
        archiveQualifiedLabel(sourcePath)?.let { return it }
        if (sourcePath != null && filename == otherFilename) {
            File(sourcePath).parentFile?.name?.let { return "$it/$filename" }
        }
        return filename
    }

    // Annotation block-model mutations are owned by AnnotationManager (Task 12 slice 5) — see
    // its doc comment for the auto-export-on-change rationale these forward to unchanged.
    fun requestAddAnn(sourceTabId: String, logIds: List<Int>) = annotationManager.requestAddAnn(sourceTabId, logIds)

    fun confirmAddAnn(targetTabId: String, sourceTabId: String, logIds: List<Int>, caption: String, sourceFilename: String?) =
        annotationManager.confirmAddAnn(targetTabId, sourceTabId, logIds, caption, sourceFilename)

    fun addNoteBlock(tabId: String, afterId: String? = null) = annotationManager.addNoteBlock(tabId, afterId)

    fun addNoteBlock(tabId: String, text: String, afterId: String? = null): String? =
        annotationManager.addNoteBlock(tabId, text, afterId)

    fun addLogRefBlock(tabId: String, logIds: List<Int>, caption: String = ""): String? =
        annotationManager.addLogRefBlock(tabId, logIds, caption)

    fun updateBlock(tabId: String, blockId: String, newText: String) = annotationManager.updateBlock(tabId, blockId, newText)

    fun removeBlock(tabId: String, blockId: String) = annotationManager.removeBlock(tabId, blockId)

    fun moveBlock(tabId: String, blockId: String, delta: Int) = annotationManager.moveBlock(tabId, blockId, delta)

    fun reorderBlock(tabId: String, blockId: String, toIdx: Int) = annotationManager.reorderBlock(tabId, blockId, toIdx)

    fun setPrefix(tabId: String, v: String) = annotationManager.setPrefix(tabId, v)

    fun setSuffix(tabId: String, v: String) = annotationManager.setSuffix(tabId, v)

    fun setIssueDescription(tabId: String, v: String) = annotationManager.setIssueDescription(tabId, v)

    fun toggleMd(tabId: String) = upTab(tabId) { it.copy(showAnnMd = !it.showAnnMd) }

    // ── Context menu shortcuts ───────────────────────────────────────

    // Given raw selected text (which may span ts/pid/tid/level/tag/msg),
    // extract the message portion suitable for pattern matching in entry.msg.
    private fun extractMsgText(sel: String, entry: LogEntry): String {
        val s = sel.trim()
        if (s.isBlank()) return entry.msg
        // Selection ends with the full message but has leading noise: return just the message
        if (s.endsWith(entry.msg) && s.length > entry.msg.length) return entry.msg
        // Selection starts with "tag: " prefix: strip it
        val prefix = "${entry.tag}: "
        if (s.startsWith(prefix)) {
            val rest = s.removePrefix(prefix)
            if (rest.isNotBlank()) return rest
        }
        return s
    }

    // Re-centers the viewport on `entryId` after a ctx-menu action changes the tab's filter
    // (message rule / tag include-exclude / keyword mode) — those can add or remove rows above
    // the current scroll position, so without this the row the user just acted on visually
    // "jumps" to wherever the now-stale scroll index happens to land. Reuses the same
    // jump-to-log-line mechanism as annotation/crash navigation (pendingAnnotationNavigation,
    // see requestCrashNavigation), which already knows how to auto-expand whatever collapsed
    // group ends up owning the row.
    private fun requestScrollAnchor(tabId: String, entryId: Int) {
        setSelectedRows(tabId, listOf(entryId))
        annotationNavigationCounter += 1
        pendingAnnotationNavigation = AnnotationNavigationRequest(annotationNavigationCounter, tabId, listOf(entryId))
    }

    fun addTagFilterFromCtx() {
        val c = ctx ?: return
        val tag = tab(c.tabId)?.rmap?.get(c.entryId)?.tag ?: return
        toggleTag(c.tabId, tag); requestScrollAnchor(c.tabId, c.entryId); ctx = null
    }

    // No requestScrollAnchor — excluding this row's own tag removes it from the filtered view.
    fun addExcludeTagFromCtx() {
        val c = ctx ?: return
        val tag = tab(c.tabId)?.rmap?.get(c.entryId)?.tag ?: return
        toggleExcludeTag(c.tabId, tag); ctx = null
    }

    fun addHlFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        // Unlike the message-only actions below, highlighter matching runs against the full
        // rendered line (ts/pid/tid/level/tag/msg — see fullLineText/buildFullLineAnnotation in
        // LogViewer.kt), so the raw selection is used verbatim rather than run through
        // extractMsgText — that helper strips a "tag: " prefix on the assumption the pattern only
        // ever matches entry.msg, which would silently change what a cross-boundary selection
        // (e.g. spanning tag + message) actually highlights.
        val text = c.selText.trim().ifBlank { entry.msg }
        addHl(c.tabId, text, false, newHlColor); ctx = null
    }

    fun addHlTagFromCtx() {
        val c = ctx ?: return
        val tag = tab(c.tabId)?.rmap?.get(c.entryId)?.tag ?: return
        addHl(c.tabId, tag, false, newHlColor); ctx = null
    }

    fun addSeqFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addSequence(text, false, newSeqColor, entry.tag); ctx = null
    }

    // Same match-scope choice as the "Hide/Show messages like this" flyout (see
    // messageRuleVariantsFromCtx) — reused as-is since SequenceDef.tag is nullable/scoping exactly
    // like MessageRule.tag.
    fun addSequenceVariant(variant: MessageRuleVariant) {
        if (ctx == null) return
        addSequence(variant.pattern, false, newSeqColor, variant.tag)
        ctx = null
    }

    fun collapseToStartFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        addManualCollapse(c.tabId, entry.id, ManualCollapseDirection.TO_START)
        ctx = null
    }

    fun collapseToEndFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        addManualCollapse(c.tabId, entry.id, ManualCollapseDirection.TO_END)
        ctx = null
    }

    private fun addManualCollapse(tabId: String, anchorId: Int, direction: ManualCollapseDirection, endId: Int? = null): Boolean {
        val tab = tab(tabId) ?: return false
        if (manualCollapseAvailability(tab, anchorId, direction, endId) != ManualCollapseAvailability.AVAILABLE) return false
        upTab(tabId) { t ->
            val id = "mc${System.currentTimeMillis()}_${anchorId}_${direction.name}"
            t.copy(manualBlocks = t.manualBlocks + ManualCollapseBlock(id, anchorId, direction, endId = endId))
        }
        return true
    }

    // Collapses an arbitrary multi-line selection into one block — unlike collapseTo{Start,End}Ctx
    // (anchored to a file edge), this covers exactly [min(ids), max(ids)] regardless of which of
    // the selected rows was right-clicked.
    fun collapseSelectedLinesFromCtx(tabId: String, ids: Set<Int>) {
        if (ids.size < 2) return
        val sorted = ids.sorted()
        addManualCollapse(tabId, sorted.first(), ManualCollapseDirection.RANGE, endId = sorted.last())
        ctx = null
    }

    fun toggleManualCollapse(tabId: String, id: String) = upTab(tabId) { t ->
        t.copy(manualBlocks = t.manualBlocks.map { if (it.id == id) it.copy(enabled = !it.enabled) else it })
    }

    fun removeManualCollapse(tabId: String, id: String) = upTab(tabId) { t ->
        t.copy(manualBlocks = t.manualBlocks.filterNot { it.id == id }, expanded = t.expanded - id)
    }

    fun setManualBlockColor(tabId: String, id: String, color: Color) = upTab(tabId) { t ->
        t.copy(manualBlocks = t.manualBlocks.map { if (it.id == id) it.copy(color = color) else it })
    }

    fun setSequenceStartFromCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        pendingSequenceStart = PendingSequenceStart(text, entry.tag)
        ctx = null
    }

    fun completeSequenceEndFromCtx() {
        val c = ctx ?: return
        val start = pendingSequenceStart ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addSequence(start.text, false, newSeqColor, start.tag, text, false, entry.tag)
        pendingSequenceStart = null
        ctx = null
    }

    // No requestScrollAnchor — this excludes the acted-on row from the filtered view by
    // definition, so there's nothing sensible to re-select/re-center on.
    fun hideMessagesLikeCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addMessageRule(c.tabId, include = false, pattern = text, regex = false, tag = entry.tag, packagePrefix = null)
        ctx = null
    }

    fun showOnlyMessagesLikeCtx() {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        val text = if (c.selText.isBlank()) entry.msg else extractMsgText(c.selText, entry)
        addMessageRule(c.tabId, include = true, pattern = text, regex = false, tag = entry.tag, packagePrefix = null)
        requestScrollAnchor(c.tabId, c.entryId)
        ctx = null
    }

    // Up to 4 match-scope choices for the ▶ flyout on "Hide/Show messages like this":
    //  - with a selection: tag-scoped selection, then unscoped selection (2 choices — there's no
    //    separator truncation to vary once the user already picked the exact text).
    //  - without a selection: tag-scoped message truncated at the 1st separator, then the 2nd,
    //    then the same two unscoped — the same four separators (- / \ , .) the message-rule
    //    itself will later match against.
    fun messageRuleVariantsFromCtx(): List<MessageRuleVariant> {
        val c = ctx ?: return emptyList()
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return emptyList()
        val selectedText = c.selText.takeIf { it.isNotBlank() }?.let { extractMsgText(it, entry) }
        return messageRuleVariantsForEntry(entry, selectedText)
    }

    // No requestScrollAnchor — see hideMessagesLikeCtx.
    fun hideMessagesLikeVariant(variant: MessageRuleVariant) {
        val c = ctx ?: return
        addMessageRule(c.tabId, include = false, pattern = variant.pattern, regex = false, tag = variant.tag, packagePrefix = null)
        ctx = null
    }

    fun showOnlyMessagesLikeVariant(variant: MessageRuleVariant) {
        val c = ctx ?: return
        addMessageRule(c.tabId, include = true, pattern = variant.pattern, regex = false, tag = variant.tag, packagePrefix = null)
        requestScrollAnchor(c.tabId, c.entryId)
        ctx = null
    }

    fun copySelectedLines(tabId: String) {
        copySelectedLines(tabId, null)
    }

    fun copySelectedLines(tabId: String, explicitIds: Set<Int>?) {
        copyToClipboard(selectedLinesText(tabId, explicitIds))
    }

    fun copySelectedLinesAsMarkdown(tabId: String, explicitIds: Set<Int>?) {
        copyToClipboard(selectedLinesMarkdownText(tabId, explicitIds))
    }

    fun selectedLinesText(tabId: String, explicitIds: Set<Int>? = null): String {
        val t = tab(tabId) ?: return ""
        val ids = explicitIds ?: t.selected
        return ids.sorted().mapNotNull { id -> t.rmap[id] }.joinToString("\n") { e ->
            val pid = if (e.pid > 0) "  ${e.pid.toString().padStart(5)} ${e.tid.toString().padStart(5)}" else ""
            "${e.ts}$pid  ${e.level.key}  ${e.tag}: ${e.msg}"
        }
    }

    fun selectedLinesMarkdownText(tabId: String, explicitIds: Set<Int>? = null): String {
        val t = tab(tabId) ?: return ""
        val ids = explicitIds ?: t.selected
        return ids.sorted().mapNotNull { id -> t.rmap[id] }.joinToString("\n", transform = ::logEntryMarkdownLine)
    }

    // ── Tab management ───────────────────────────────────────────────
    // Structural tabs-list edits synchronize on stateLock for the same reason upTab does: a
    // background fill landing in the same instant must not lose (or be lost to) this write.
    fun addTab() {
        val n = tabCounter.getAndIncrement()
        val t = emptyWorkspaceTab().copy(id = "t$n")
        synchronized(stateLock) {
            tabs = tabs + t
            activeTabId = t.id
        }
    }

    fun activateTab(tabId: String) {
        if (tabs.any { it.id == tabId }) activeTabId = tabId
    }

    fun activateOverflowTab(tabId: String): Unit = synchronized(stateLock) {
        val tab = tabs.find { it.id == tabId } ?: return
        tabs = tabs.filter { it.id != tabId } + tab
        activeTabId = tabId
    }

    fun reorderTabs(fromId: String, beforeId: String?): Unit = synchronized(stateLock) {
        val from = tabs.find { it.id == fromId } ?: return
        val without = tabs.filter { it.id != fromId }
        val idx = beforeId?.let { id -> without.indexOfFirst { it.id == id }.takeIf { it >= 0 } } ?: without.size
        tabs = without.take(idx) + from + without.drop(idx)
    }

    fun closeTab(tabId: String) {
        closeTabsById(setOf(tabId), preferredActiveId = null)
    }

    fun closeOtherTabs(tabId: String) {
        if (tabs.none { it.id == tabId }) return
        closeTabsById(tabs.map { it.id }.filter { it != tabId }.toSet(), preferredActiveId = tabId)
    }

    fun closeTabsToRight(tabId: String) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        closeTabsById(tabs.drop(idx + 1).map { it.id }.toSet(), preferredActiveId = tabId)
    }

    fun closeTabsToLeft(tabId: String) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx < 0) return
        closeTabsById(tabs.take(idx).map { it.id }.toSet(), preferredActiveId = tabId)
    }

    fun closeAllTabs() {
        closeTabsById(tabs.map { it.id }.toSet(), preferredActiveId = null)
        cancelAllLoads()
    }

    // Resource cleanup and the tabs-list removal used to be two separate phases — cleanup
    // unguarded, then a synchronized(stateLock) block removing the tab — leaving a window where a
    // concurrent FileTailer flush (appendTailedLines, itself synchronized(stateLock)) could land
    // in between and re-append data to (or leave a stale visibleItemsByTab entry for) a tab that's
    // mid-close (A-01). One synchronized block makes "cancel this tab's resources" and "remove it
    // from tabs" atomic together; cancelActiveLoad's own nested synchronized(stateLock) call is
    // safe here since the monitor is reentrant.
    private fun closeTabsById(tabIds: Set<String>, preferredActiveId: String?) {
        if (tabIds.isEmpty()) return
        synchronized(stateLock) {
            tabIds.forEach { tabId ->
                aiSessions.remove(tabId)
                cancelActiveLoad(tabId)
                tailCoordinator.cancelTailingFor(tabId)
                visibleItemsByTab.remove(tabId)
                invalidateComputeCache(tabId)
                logViewerScrollStateStore.removeTab(tabId)
            }
            pendingRestoredLoads.removeAll { it.tab.id in tabIds }
            val next = tabs.filter { it.id !in tabIds }
            if (activeTabId in tabIds) activeTabId = preferredActiveId?.takeIf { id -> next.any { it.id == id } } ?: next.lastOrNull()?.id ?: ""
            if (compareTabId in tabIds) compareTabId = next.firstOrNull()?.id ?: ""
            if (next.size < 2) compareMode = false
            tabs = next
        }
        activeSavedFilterIds = activeSavedFilterIds - tabIds
        filterDraftsByTab = filterDraftsByTab - tabIds
        activeFilterDraftTabIds = activeFilterDraftTabIds - tabIds
        transientRegexSearchTabIds = transientRegexSearchTabIds - tabIds
    }

    // Ships "merge already-open tabs" (v1) — data's already in memory, no re-parsing needed.
    // Runs on ioScope for consistency with the rest of the codebase's bias toward backgrounding
    // anything touching logData, even though mergeLogs() itself is a pure in-memory sort.
    fun mergeTabs(tabIds: List<String>, newTabName: String = "Merged") {
        val sources = tabIds.mapNotNull { id -> tab(id)?.let { MergeSourceFile(it.filename, it.logData) } }
        if (sources.size < 2) return
        val n = tabCounter.getAndIncrement()
        beginLoading("Merging logs...")
        ioScope.launch {
            var published = false
            try {
                val merged = mergeLogs(sources)
                ensureActive()
                val t = mkTab("t$n", newTabName, merged, analysis = pendingAnalysis(merged))
                synchronized(stateLock) {
                    ensureActive()
                    tabs = tabs + t
                    activeTabId = t.id
                }
                finishLoading()
                published = true
                val full = buildLogAnalysis(merged)
                ensureActive()
                upTab("t$n") { it.copy(analysis = full) }
            } finally {
                if (!published) finishLoading()
            }
        }
    }

    // Live tailing is owned by TailCoordinator (Task 12 slice 2) — see its doc comments for the
    // stateLock/A-01 rationale these forward to unchanged.
    fun startTailing(tabId: String) = tailCoordinator.startTailing(tabId)

    fun stopTailing(tabId: String) = tailCoordinator.stopTailing(tabId)

    fun openFile(file: File): String? = openFileInternal(file, bypassSplitPrompt = false)

    fun openFileAsIs(file: File): String? = openFileInternal(file, bypassSplitPrompt = true)

    fun openPaths(files: List<File>, splitPromptThresholdBytes: Long = SPLIT_PROMPT_BYTES) {
        val openable = files.filter { isOpenableAsLog(it) }
        val oversizedFiles = openable.filter { file ->
            !isSupportedArchiveFile(file) && requiresSplitPrompt(file.length(), splitPromptThresholdBytes)
        }
        if (oversizedFiles.isNotEmpty()) {
            pendingSplitPrompt = PendingSplitPrompt(
                sources = oversizedFiles.map { SplitSource.RealFile(it) },
                deferredFiles = openable - oversizedFiles.toSet(),
            )
            oversizedFiles.forEach { file ->
                rememberRecentFile(file)
                rememberAutoExportedNoteFor(file.name)
            }
            recentMenuOpen = false
            return
        }
        openable.forEach { openPath(it) }
    }

    private fun isLikelyLogPath(file: File): Boolean =
        file.isFile && file.extension.lowercase() in setOf("log", "txt")

    // Same "will this actually open" check drag-and-drop uses (openPaths above), reused by the
    // Open toolbar button in App.kt. Needed there because java.awt.FileDialog.setFilenameFilter
    // is unreliable on macOS — the native NSOpenPanel doesn't consistently invoke it, so a
    // content-sniffed text file with a non-standard extension can be greyed out in the picker
    // even though it would open fine. The dialog shows all files instead, and this validates
    // (with a real error message) after the user actually picks one.
    fun isOpenableAsLog(file: File): Boolean =
        file.exists() && (isSupportedArchiveFile(file) || isLikelyLogPath(file) || isLikelyTextFile(file))

    fun openPathOrShowError(file: File) {
        if (!isOpenableAsLog(file)) {
            showOpenError(
                title = "Could not open file",
                path = file.absolutePath,
                message = "This doesn't look like a log/text file or a supported archive (.zip/.7z).",
            )
            return
        }
        openPath(file)
    }

    private fun openFileInternal(file: File, bypassSplitPrompt: Boolean): String? {
        val path = file.absolutePath
        if (!file.exists() || !file.isFile) {
            removeRecentFile(file)
            recentMenuOpen = false
            showOpenError(
                title = "Could not open file",
                path = path,
                message = "The file does not exist or is not readable.",
            )
            return null
        }
        rememberRecentFile(file)
        recentMenuOpen = false
        rememberAutoExportedNoteFor(file.name)
        // Switch to existing tab if this file is already open
        val existing = tabs.find { it.sourcePath == path }
        if (existing != null) {
            activeTabId = existing.id; return existing.id
        }
        if (!bypassSplitPrompt && requiresSplitPrompt(file.length())) {
            pendingSplitPrompt = PendingSplitPrompt(listOf(SplitSource.RealFile(file)))
            return null
        }
        val n = tabCounter.getAndIncrement() // capture on calling thread before launching
        val tabId = "t$n"
        val largeFile = file.length() >= LARGE_FILE_MODE_BYTES
        beginLoading()
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            var published = false
            try {
                val logData = runCatching { parser(file) }.getOrElse { error ->
                    removeRecentFile(file)
                    showOpenError(
                        title = "Could not open file",
                        path = path,
                        message = error.message ?: error::class.simpleName.orEmpty().ifBlank { "Unknown error" },
                    )
                    return@launch
                }
                ensureActive()
                val prefixLabel = settings.annotationPrefixLabel.trim().ifBlank { "From" }
                // Publish the tab as soon as parsing finishes — the stack/crash analysis costs
                // as much as the parse on multi-GB files and fills in below, in the same job so
                // closing the tab cancels it.
                val t = mkTab(tabId, file.name, logData, analysis = pendingAnalysis(logData))
                    .copy(
                        sourcePath = path,
                        annotations = Annotations(prefix = "$prefixLabel ${file.name}"),
                        largeFileMode = largeFile,
                        showUnfiltered = settings.openNewFilesWithUnfiltered,
                    )
                synchronized(stateLock) {
                    ensureActive()
                    tabs = tabs + t
                    activeTabId = t.id
                }
                markActiveLoadFinished(tabId)
                published = true
                val full = buildLogAnalysis(logData)
                ensureActive()
                upTab(tabId) { it.copy(analysis = full) }
            } finally {
                val load = activeLoads.remove(tabId)
                if (!published) finishActiveLoad(load)
            }
        }
        activeLoads[tabId] = ActiveLoad(job)
        job.start()
        return tabId
    }

    private fun rememberRecentFile(file: File) {
        val path = file.absolutePath
        recentFiles = (listOf(path) + recentFiles.filter { it != path }).take(30)
        autosaveNow()
    }

    private fun removeRecentFile(file: File) {
        val path = file.absolutePath
        val next = recentFiles.filter { it != path }
        if (next == recentFiles) return
        recentFiles = next
        autosaveNow()
    }

    private fun pruneMissingRecentFiles() {
        val next = recentFiles.filter { File(it).exists() }
        if (next == recentFiles) return
        recentFiles = next
        autosaveNow()
    }

    fun toggleRecentFilesMenu() {
        if (!recentMenuOpen) pruneMissingRecentFiles()
        recentMenuOpen = !recentMenuOpen
    }

    fun openPath(file: File): String? {
        return if (isSupportedArchiveFile(file)) {
            openZipFile(file)
            null
        } else {
            openFile(file)
        }
    }

    fun dismissOpenError() {
        openError = null
    }

    private fun showOpenError(title: String, path: String?, message: String) {
        openError = OpenFileError(title = title, path = path, message = message)
    }

    // A single candidate auto-opens (the common case — most bug reports bundle one logcat
    // buffer). 2+ candidates show a picker rather than guessing: Android 11+ bug reports often
    // bundle multiple buffers (main/system/crash/events), and silently picking one wrong is worse
    // than one extra click. 0 candidates reports that no log-like entries were found.
    fun openZipFile(file: File) {
        val path = file.absolutePath
        if (!file.exists() || !file.isFile) {
            removeRecentFile(file)
            recentMenuOpen = false
            showOpenError(
                title = "Could not open archive",
                path = path,
                message = "The archive does not exist or is not readable.",
            )
            return
        }
        val candidates = listArchiveLogCandidates(file)
        when {
            candidates.size == 1 -> {
                rememberRecentFile(file)
                recentMenuOpen = false
                openZipEntries(file, listOf(candidates.first()))
            }
            candidates.size > 1 -> {
                rememberRecentFile(file)
                recentMenuOpen = false
                pendingZipPicker = PendingZipPicker(file, candidates)
            }
            else -> {
                removeRecentFile(file)
                recentMenuOpen = false
                showOpenError(
                    title = "No log files found",
                    path = path,
                    message = "This archive does not contain readable .log, .txt, or ANR trace entries.",
                )
            }
        }
    }

    fun openZipEntries(zipFile: File, selected: List<ZipLogCandidate>) {
        val (oversized, normal) = selected.partition { requiresSplitPrompt(it.sizeBytes) }
        if (oversized.isNotEmpty()) {
            pendingSplitPrompt = PendingSplitPrompt(
                sources = oversized.map { SplitSource.ArchiveEntry(zipFile, it) },
                deferredArchiveEntries = normal.map { DeferredArchiveEntry(zipFile, it) },
            )
            pendingZipPicker = null
            return
        }
        selected.forEach { openZipEntry(zipFile, it, bypassSplitPrompt = false) }
        pendingZipPicker = null
    }

    fun openZipEntryAsIs(zipFile: File, candidate: ZipLogCandidate) {
        openZipEntry(zipFile, candidate, bypassSplitPrompt = true)
    }

    fun cancelZipPicker() {
        pendingZipPicker = null
    }

    // Jar-URL-style "!" separator for the source path — for display/dedup only (a zip-extracted
    // tab can't be re-opened from this path directly), matching openFile()'s sourcePath dedup.
    private fun openZipEntry(zipFile: File, candidate: ZipLogCandidate, bypassSplitPrompt: Boolean) {
        val path = "${zipFile.absolutePath}!${candidate.entryPath}"
        val existing = tabs.find { it.sourcePath == path }
        if (existing != null) {
            activateTab(existing.id); return
        }
        if (!bypassSplitPrompt && requiresSplitPrompt(candidate.sizeBytes)) {
            pendingSplitPrompt = PendingSplitPrompt(listOf(SplitSource.ArchiveEntry(zipFile, candidate)))
            return
        }
        val n = tabCounter.getAndIncrement()
        val tabId = "t$n"
        val largeFile = candidate.sizeBytes >= LARGE_FILE_MODE_BYTES
        beginLoading()
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            var published = false
            try {
                val logData = runCatching { extractCandidate(zipFile, candidate, archiveEntryByteBudget) }.getOrElse { error ->
                    // A budget breach (S-03: bounded archive extraction) is a real, actionable
                    // failure — surface it instead of silently opening an empty tab, which used to
                    // look indistinguishable from "this entry legitimately has nothing in it."
                    // Every other extraction failure keeps the prior silent-empty-tab behavior.
                    if (error is ArchiveBudgetExceededException) {
                        showOpenError(
                            title = "Archive entry too large to open",
                            path = path,
                            message = error.message ?: "This entry exceeded the extraction safety limit.",
                        )
                        return@launch
                    }
                    emptyList()
                }
                ensureActive()
                val prefixLabel = settings.annotationPrefixLabel.trim().ifBlank { "From" }
                // Archive-sourced tabs always get an archive-qualified prefix ("archive.zip/entry/
                // path.log") — the bare display name alone doesn't say which archive it came from.
                val prefixName = archiveQualifiedLabel(path) ?: candidate.displayName
                val t = mkTab(tabId, candidate.displayName, logData, analysis = pendingAnalysis(logData))
                    .copy(
                        sourcePath = path,
                        annotations = Annotations(prefix = "$prefixLabel $prefixName"),
                        largeFileMode = largeFile,
                        showUnfiltered = settings.openNewFilesWithUnfiltered,
                    )
                synchronized(stateLock) {
                    ensureActive()
                    tabs = tabs + t
                    activeTabId = t.id
                }
                markActiveLoadFinished(tabId)
                published = true
                val full = buildLogAnalysis(logData)
                ensureActive()
                upTab(tabId) { it.copy(analysis = full) }
            } finally {
                val load = activeLoads.remove(tabId)
                if (!published) finishActiveLoad(load)
            }
        }
        activeLoads[tabId] = ActiveLoad(job)
        job.start()
    }

    fun requestSplitForFile(file: File) {
        if (!file.isFile) return
        pendingSplitPrompt = PendingSplitPrompt(listOf(SplitSource.RealFile(file)))
    }

    fun requestSplitForTab(tabId: String): Boolean {
        val sourcePath = tab(tabId)?.sourcePath ?: return false
        val source = splitSourceFromPath(sourcePath, entryPath = null) ?: return false
        pendingSplitPrompt = PendingSplitPrompt(listOf(source))
        return true
    }

    fun splitSourceForPath(path: String, entryPath: String? = null): SplitSource? =
        splitSourceFromPath(path, entryPath)

    fun defaultSplitDestination(source: SplitSource): File =
        settings.defaultSaveDir?.let(::File) ?: source.sourceFile.parentFile ?: File(".")

    fun defaultSplitPartCount(source: SplitSource): Int = suggestedSplitPartCount(source.sizeBytes)

    fun cancelSplitPrompt() {
        pendingSplitPrompt = null
    }

    fun confirmSplitPrompt(
        modes: Map<String, SplitMode>,
        destinationDir: File,
        postfix: String,
        partCounts: Map<String, Int>,
        postfixes: Map<String, String> = emptyMap(),
    ) {
        val pending = pendingSplitPrompt ?: return
        pendingSplitPrompt = null
        settings = settings.copy(defaultSaveDir = destinationDir.absolutePath)
        beginLoading("Splitting logs...")
        ioScope.launch {
            try {
                pending.deferredFiles.forEach { file ->
                    openPath(file)
                }
                pending.deferredArchiveEntries.forEach { deferred ->
                    openZipEntry(deferred.archiveFile, deferred.candidate, bypassSplitPrompt = true)
                }
                pending.sources.forEach { source ->
                    when (modes[source.id] ?: SplitMode.SPLIT) {
                        SplitMode.OPEN_AS_IS -> openSplitSourceAsIs(source)
                        SplitMode.SPLIT -> splitSourceAndOpenParts(
                            source = source,
                            destinationDir = destinationDir,
                            postfix = postfixes[source.id] ?: postfix,
                            partCount = partCounts[source.id] ?: defaultSplitPartCount(source),
                        )
                    }
                }
            } finally {
                finishLoading()
            }
        }
    }

    fun splitSourceAndOpen(source: SplitSource, destinationDir: File, postfix: String, partCount: Int): List<File> =
        splitSourceAndOpenParts(source, destinationDir, postfix, partCount)

    private fun splitSourceAndOpenParts(source: SplitSource, destinationDir: File, postfix: String, partCount: Int): List<File> {
        val outputs = planSplitOutputs(source.displayName, destinationDir, postfix, partCount)
        val written = when (source) {
            is SplitSource.RealFile -> splitFileToFiles(source.file, outputs)
            is SplitSource.ArchiveEntry -> {
                val stream = openArchiveCandidateStream(source.archiveFile, source.candidate) ?: return emptyList()
                splitStreamToFiles(stream, outputs, source.sizeBytes)
            }
        }
        written.forEach { part -> loadSplitPartAsTab(part) }
        return written
    }

    private fun loadSplitPartAsTab(file: File) {
        val path = file.absolutePath
        val existing = tabs.find { it.sourcePath == path }
        if (existing != null) {
            activeTabId = existing.id
            return
        }
        rememberRecentFile(file)
        rememberAutoExportedNoteFor(file.name)
        val n = tabCounter.getAndIncrement()
        val tabId = "t$n"
        val logData = runCatching { parser(file) }.getOrElse { error ->
            showOpenError(
                title = "Could not open split file",
                path = path,
                message = error.message ?: error::class.simpleName.orEmpty().ifBlank { "Unknown error" },
            )
            return
        }
        val prefixLabel = settings.annotationPrefixLabel.trim().ifBlank { "From" }
        val t = mkTab(tabId, file.name, logData)
            .copy(
                sourcePath = path,
                annotations = Annotations(prefix = "$prefixLabel ${file.name}"),
                largeFileMode = file.length() >= LARGE_FILE_MODE_BYTES,
                showUnfiltered = settings.openNewFilesWithUnfiltered,
            )
        synchronized(stateLock) {
            tabs = tabs + t
            activeTabId = t.id
        }
    }

    private fun openSplitSourceAsIs(source: SplitSource) {
        when (source) {
            is SplitSource.RealFile -> openFileInternal(source.file, bypassSplitPrompt = true)
            is SplitSource.ArchiveEntry -> openZipEntry(source.archiveFile, source.candidate, bypassSplitPrompt = true)
        }
    }

    private fun splitSourceFromPath(sourcePath: String, entryPath: String?): SplitSource? {
        val effectivePath = if (entryPath != null) "$sourcePath!$entryPath" else sourcePath
        val bang = effectivePath.indexOf('!')
        if (bang < 0) {
            val file = File(effectivePath)
            return file.takeIf { it.isFile }?.let { SplitSource.RealFile(it) }
        }
        val archiveFile = File(effectivePath.substring(0, bang))
        val selectedEntryPath = effectivePath.substring(bang + 1)
        val candidate = listArchiveLogCandidates(archiveFile).firstOrNull { it.entryPath == selectedEntryPath } ?: return null
        return SplitSource.ArchiveEntry(archiveFile, candidate)
    }

    // ── Copy / Save ──────────────────────────────────────────────────
    fun copyToClipboard(text: String) {
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
    }

    fun copyAnn(tabId: String) {
        tab(tabId)?.let { copyToClipboard(maskWordForCopy(buildMd(it, settings), settings)) }
    }

    fun exportAnalysisTo(tabId: String, file: File): Boolean {
        val t = tab(tabId) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(buildMd(t, settings))
        }.isSuccess
    }

    fun exportFilteredTo(tabId: String, file: File, csv: Boolean): Boolean {
        val t = tab(tabId) ?: return false
        return runCatching { exportFilteredToFile(t, file, csv) }.isSuccess
    }

    fun saveAnnotationsTo(tabId: String, file: File): Boolean {
        val t = tab(tabId) ?: return false
        return runCatching {
            file.parentFile?.mkdirs()
            file.writeText(t.annotations.annotationsToken())
        }.isSuccess
    }

    fun loadAnnotationsFrom(tabId: String, file: File): Boolean {
        val annotations = runCatching { file.readText().annotationsFromToken() }.getOrNull() ?: return false
        upAnn(tabId) { t -> t.copy(annotations = annotations) }
        return tab(tabId)?.annotations == annotations
    }

    fun saveAnalysis(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Save Analysis", FileDialog.SAVE).apply {
            file = analysisNoteMarkdownName(t.filename)
            settings.defaultSaveDir?.let { directory = it }
            isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        settings = settings.copy(defaultSaveDir = dir)
        val saved = File(dir, path)
        ioScope.launch {
            runCatching {
                saved.writeText(buildMd(t, settings))
                File(saved.parent, saved.nameWithoutExtension + ".ann").writeText(t.annotations.annotationsToken())
                rememberRecentNote(saved)
            }
        }
    }

    fun exportFilteredTxt(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Export Filtered Log", FileDialog.SAVE).apply {
            file = t.filename.substringBeforeLast('.') + "_filtered.txt"
            settings.defaultSaveDir?.let { directory = it }
            isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        settings = settings.copy(defaultSaveDir = dir)
        val saved = File(dir, path)
        ioScope.launch { runCatching { exportFilteredToFile(t, saved, csv = false) } }
    }

    fun exportFilteredCsv(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Export Filtered Log", FileDialog.SAVE).apply {
            file = t.filename.substringBeforeLast('.') + "_filtered.csv"
            settings.defaultSaveDir?.let { directory = it }
            isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        settings = settings.copy(defaultSaveDir = dir)
        val saved = File(dir, path)
        ioScope.launch { runCatching { exportFilteredToFile(t, saved, csv = true) } }
    }

    fun openNoteFile(tabId: String, file: File) {
        if (!file.exists()) {
            removeRecentNote(file)
            recentNotesMenuOpen = false
            return
        }
        rememberRecentNote(file)
        recentNotesMenuOpen = false
        // Prefer .ann sidecar — restores exact blocks and structure
        val sidecar = File(file.parent, file.nameWithoutExtension + ".ann")
        if (sidecar.exists()) {
            val annotations = runCatching { sidecar.readText().annotationsFromToken() }.getOrNull()
            if (annotations != null) {
                upTab(tabId) { t -> t.copy(annotations = annotations) }
                return
            }
        }
        // Fallback: load raw text as a single note block (e.g. plain .md without sidecar)
        val text = runCatching { file.readText() }.getOrElse { return }
        upTab(tabId) { t ->
            val block = AnnBlock.Note("n${System.currentTimeMillis()}", text)
            t.copy(annotations = t.annotations.copy(blocks = t.annotations.blocks + block))
        }
    }

    fun openNoteFileAsync(tabId: String, file: File) {
        ioScope.launch { openNoteFile(tabId, file) }
    }

    private fun userNotesDir(): File? {
        val configuredDir = settings.defaultSaveDir?.let(::File) ?: return null
        return configuredDir.takeIf { it.exists() && it.isDirectory }
    }

    private fun activeNotesDir(): File = userNotesDir() ?: notesDir

    private fun noteLookupDirs(): List<File> {
        return listOfNotNull(
            userNotesDir(),
            notesDir,
            DesktopStorage.legacyNotesDir(),
        ).distinctBy { it.absolutePath }
    }

    private fun noteNamesForFilename(filename: String): List<String> {
        return listOf(analysisNoteMarkdownName(filename))
    }

    // Two tabs can share a bare filename while being entirely different files (different
    // folders, or one from inside a zip) — matching by name alone would surface one file's notes
    // as a "recent note" suggestion for the other. Excludes only on POSITIVE evidence of a
    // mismatch (both a recorded fingerprint and a known sourcePath for this tab, and they
    // differ); a note saved before this fingerprinting existed (no .src sidecar) or a tab with no
    // sourcePath (e.g. a merged tab) still matches by name as before — see resolveNoteTarget.
    fun recentNotesForTab(tab: LogTab): List<String> {
        val relatedNames = noteNamesForFilename(tab.filename).toSet()
        return recentNotes.filter { path ->
            val f = File(path)
            if (f.name !in relatedNames) return@filter false
            val recorded = readSourceFingerprint(f)
            recorded == null || tab.sourcePath == null || recorded == tab.sourcePath
        }
    }

    private fun rememberAutoExportedNoteFor(filename: String) {
        val noteNames = noteNamesForFilename(filename)
        val noteFile = noteLookupDirs()
            .asSequence()
            .flatMap { dir -> noteNames.asSequence().map { name -> File(dir, name) } }
            .firstOrNull { it.exists() } ?: return
        if (noteFile.exists()) rememberRecentNote(noteFile)
    }

    private fun rememberRecentNote(file: File) {
        val absPath = file.absolutePath
        recentNotes = (listOf(absPath) + recentNotes.filter { it != absPath }).take(30)
        autosaveNow()
    }

    private fun removeRecentNote(file: File) {
        val absPath = file.absolutePath
        val next = recentNotes.filter { it != absPath }
        if (next == recentNotes) return
        recentNotes = next
        autosaveNow()
    }

    private fun pruneMissingRecentNotes() {
        val next = recentNotes.filter { File(it).exists() }
        if (next == recentNotes) return
        recentNotes = next
        autosaveNow()
    }

    fun toggleRecentNotesMenu() {
        if (!recentNotesMenuOpen) pruneMissingRecentNotes()
        recentNotesMenuOpen = !recentNotesMenuOpen
    }

    private fun autoExportAnnotations(tab: LogTab) {
        if (!autoExportNotes || !settings.autoExportNotes || tab.annotations.blocks.isEmpty()) return
        ioScope.launch {
            runCatching {
                val targetDir = activeNotesDir()
                targetDir.mkdirs()
                val mdFile = resolveNoteTarget(targetDir, tab.filename, tab.sourcePath)
                mdFile.writeText(buildMd(tab, settings))
                // Sidecar stores full block state for restoration
                File(targetDir, "${mdFile.nameWithoutExtension}.ann").writeText(tab.annotations.annotationsToken())
                writeSourceFingerprint(mdFile, tab.sourcePath)
                rememberRecentNote(mdFile)
            }
        }
    }

    // Fingerprint sidecar recording which sourcePath a saved note "<name>_analysis.md" actually
    // belongs to — lets resolveNoteTarget/recentNotesForTab tell apart two different files that
    // happen to share a display name, without changing the plain filename in the common
    // (non-colliding) case. Deliberately a tiny separate file rather than a new field in the
    // .ann token format: the .ann/.md pair's own format is shared with the user-facing
    // Save/Open-note flow and stays untouched by this auto-export-only bookkeeping.
    private fun sourceFingerprintFile(mdFile: File): File = File(mdFile.parent, "${mdFile.name}.src")

    private fun readSourceFingerprint(mdFile: File): String? =
        sourceFingerprintFile(mdFile).takeIf { it.exists() }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }

    private fun writeSourceFingerprint(mdFile: File, sourcePath: String?) {
        if (sourcePath == null) return
        runCatching { sourceFingerprintFile(mdFile).writeText(sourcePath) }
    }

    // Picks the .md target for this tab's auto-exported note in targetDir. Reuses the plain
    // "<name>_analysis.md" whenever there's no evidence of a different owner (no fingerprint
    // recorded yet — a legacy or first-time save — or it already matches this tab's sourcePath,
    // or this tab has no sourcePath to compare); otherwise walks "_2", "_3", ... until it finds a
    // slot with no conflicting fingerprint, so a genuine collision no longer silently overwrites
    // an unrelated file's saved notes.
    private fun resolveNoteTarget(targetDir: File, filename: String, sourcePath: String?): File {
        val baseName = analysisNoteMarkdownName(filename)
        if (sourcePath == null) return File(targetDir, baseName)
        var candidateName = baseName
        var suffix = 2
        while (suffix < MAX_NOTE_TARGET_SUFFIX) {
            val candidate = File(targetDir, candidateName)
            val recorded = readSourceFingerprint(candidate)
            if (recorded == null || recorded == sourcePath) return candidate
            candidateName = "${baseName.removeSuffix(".md")}_$suffix.md"
            suffix++
        }
        return File(targetDir, candidateName)
    }

    // Annotation-aware tab updater — auto-exports after any annotation change. internal, not
    // private: AnnotationManager (Task 12 slice 5) routes its block mutations through this too,
    // so auto-export keeps firing regardless of which class made the edit.
    internal fun upAnn(tabId: String, fn: (LogTab) -> LogTab) {
        upTab(tabId, fn)
        tab(tabId)?.let { autoExportAnnotations(it) }
    }

    fun pickSaveFolder() {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dlg = FileDialog(null as Frame?, "Choose Save Folder", FileDialog.LOAD)
            dlg.isVisible = true
            val dir = dlg.directory ?: return
            val file = dlg.file ?: return
            val chosen = java.io.File(dir, file)
            updateSettings { it.copy(defaultSaveDir = chosen.absolutePath) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    fun pickSourceFolder() {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dlg = FileDialog(null as Frame?, "Choose Source Folder", FileDialog.LOAD)
            dlg.isVisible = true
            val dir = dlg.directory ?: return
            val file = dlg.file ?: return
            val chosen = java.io.File(dir, file)
            updateSettings { it.copy(sourceFolders = (it.sourceFolders + chosen.absolutePath).distinct()) }
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }

    fun removeSourceFolder(path: String) {
        updateSettings {
            it.copy(
                sourceFolders = it.sourceFolders - path,
                sourceFolderInfo = it.sourceFolderInfo - path,
                sourceFolderConfigurationIds = it.sourceFolderConfigurationIds - path,
            )
        }
    }

    fun updateSourceFolderInfo(path: String, info: SourceFolderInfo) {
        updateSettings { it.copy(sourceFolderInfo = it.sourceFolderInfo + (path to info)) }
    }

    fun sourceConfigurationsForFolder(folder: String): List<SourceLogConfiguration> {
        val ids = settings.sourceFolderConfigurationIds[File(folder).absolutePath].orEmpty().toSet()
        return settings.sourceLogConfigurations.filter { it.id in ids }
    }

    fun saveSourceLogConfiguration(configuration: SourceLogConfiguration) {
        val normalized = configuration.copy(
            id = configuration.id.ifBlank { UUID.randomUUID().toString() },
            name = configuration.name.trim().ifBlank { "Logging configuration" },
            wrapperRules = configuration.wrapperRules.filter { it.ownerType.isNotBlank() && it.methodName.isNotBlank() },
        )
        updateSettings { current ->
            val replaced = current.sourceLogConfigurations.any { it.id == normalized.id }
            current.copy(
                sourceLogConfigurations = if (replaced) {
                    current.sourceLogConfigurations.map { if (it.id == normalized.id) normalized else it }
                } else {
                    current.sourceLogConfigurations + normalized
                },
            )
        }
    }

    fun deleteSourceLogConfiguration(id: String) {
        updateSettings { current ->
            current.copy(
                sourceLogConfigurations = current.sourceLogConfigurations.filterNot { it.id == id },
                sourceFolderConfigurationIds = current.sourceFolderConfigurationIds.mapValues { (_, ids) ->
                    ids.filterNot { it == id }
                }.filterValues { it.isNotEmpty() },
            )
        }
    }

    fun assignSourceLogConfigurations(folder: String, ids: List<String>) {
        val path = File(folder).absolutePath
        val valid = ids.distinct().filter { id -> settings.sourceLogConfigurations.any { it.id == id } }
        updateSettings { it.copy(sourceFolderConfigurationIds = it.sourceFolderConfigurationIds + (path to valid)) }
    }

    // Plain (non-directory) file picker for a folder's optional README path — same FileDialog
    // mechanism as pickSourceFolder/pickSaveFolder, but without toggling the directory-mode system
    // property, since a README is a single file. Returns the path rather than writing straight to
    // settings: the caller (SourceFolderInfoDialog) needs it in the still-open editor's draft state.
    fun pickReadmeFile(): String? {
        val dlg = FileDialog(null as Frame?, "Choose README file", FileDialog.LOAD)
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val file = dlg.file ?: return null
        return java.io.File(dir, file).absolutePath
    }

    /** Opens a picker for the CLI executable used by a locally authenticated account profile. */
    fun pickAccountCliExecutable(): String? {
        val dlg = FileDialog(null as Frame?, "Choose Codex or Claude Code executable", FileDialog.LOAD)
        dlg.isVisible = true
        val dir = dlg.directory ?: return null
        val file = dlg.file ?: return null
        return File(dir, file).absolutePath
    }

    // ── Source index (Task 2) ───────────────────────────────────────
    // Restores whatever was persisted from a prior run, if any — a missing/stale/corrupt file is
    // "no usable index" (see SourceIndexStore.load), simply leaving sourceIndex null so the
    // Settings UI (Task 4) can prompt for a first index build. Runs on ioScope: computing
    // changedFileCount below stats every indexed file, which shouldn't block the UI thread for a
    // large source tree.
    private fun loadPersistedSourceIndex() {
        ioScope.launch {
            val index = SourceIndexStore.load(sourceIndexFile) ?: return@launch
            publishSourceIndex(index)
        }
    }

    private fun publishSourceIndex(index: SourceIndex) {
        synchronized(stateLock) {
            sourceIndex = index
            sourceResolver = LogSourceResolver(index)
        }
    }

    private fun isUnderSourceRoot(path: String, rootAbs: String): Boolean =
        path == rootAbs || path.startsWith(rootAbs + File.separator)

    // Registered folders may intentionally overlap (for example, a project root plus a module
    // source root with a different logging configuration). A source file belongs to the most
    // specific matching root; using the first configured folder would validate nested-module
    // sites against the wrong configuration fingerprint and make source actions appear disabled.
    private fun sourceRootForPath(path: String): String? = settings.sourceFolders
        .asSequence()
        .map { File(it).absolutePath }
        .filter { root -> isUnderSourceRoot(path, root) }
        .maxByOrNull(String::length)

    private fun belongsToSourceFolder(path: String, rootAbs: String): Boolean {
        val registeredRoots = settings.sourceFolders.map { File(it).absolutePath }
        return if (rootAbs in registeredRoots) {
            sourceRootForPath(path) == rootAbs
        } else {
            // Persisted-index inspection can happen before autosaved settings finish restoring;
            // preserve the status API's historical folder-scoped behavior in that case.
            isUnderSourceRoot(path, rootAbs)
        }
    }

    private fun fileChangedSince(path: String, meta: FileMeta): Boolean {
        val file = File(path)
        return !file.exists() || file.lastModified() != meta.mtime || file.length() != meta.size
    }

    // Derived from the current combined sourceIndex rather than stored separately — every site/
    // fileMeta entry already carries its absolute file path, so a folder's own slice is just a
    // filter. Never indexed yet (no rootBuiltAt entry) reads as the zero-value status.
    fun sourceIndexStatusForFolder(folder: String): SourceIndexStatus {
        val index = sourceIndex ?: return SourceIndexStatus()
        val rootAbs = File(folder).absolutePath
        val builtAt = index.rootBuiltAt[rootAbs] ?: return SourceIndexStatus()
        val metaEntries = index.fileMeta.filterKeys { belongsToSourceFolder(it, rootAbs) }
        val configurationChanged = index.rootConfigFingerprints[rootAbs] !=
            sourceConfigurationFingerprint(
                sourceConfigurationsForFolder(rootAbs),
                settings.sourceAutoDiscoveryEnabled,
            )
        return SourceIndexStatus(
            fileCount = metaEntries.size,
            siteCount = if (configurationChanged) 0 else index.sites.count { belongsToSourceFolder(it.filePath, rootAbs) },
            builtAt = builtAt,
            changedFileCount = metaEntries.count { (path, meta) -> fileChangedSince(path, meta) },
            configurationChanged = configurationChanged,
        )
    }

    // Single-flight per folder: a second call for the same folder while its scan is already
    // running is a no-op rather than queued — a folder's own "Reindex" button can't be
    // double-clicked into two concurrent scans racing to publish/save. Only files under [folder]
    // are rescanned; sites/fileMeta from every other registered folder are carried over unchanged.
    fun reindexSources(folder: String) {
        val rootAbs = File(folder).absolutePath
        val started = synchronized(stateLock) {
            if (rootAbs in indexingFolders) {
                false
            } else {
                indexingFolders = indexingFolders + rootAbs
                true
            }
        }
        if (!started) return
        beginLoading("Indexing source…")
        ioScope.launch {
            try {
                val partial = runCatching {
                    val configs = sourceConfigurationsForFolder(rootAbs)
                    SourceIndexer.build(
                        roots = listOf(File(folder)),
                        progress = { scanned, total -> loadingStatus = "Indexing source… ($scanned/$total)" },
                        options = SourceIndexBuildOptions(
                            wrapperRules = configs.flatMap { it.wrapperRules },
                            autoDiscover = settings.sourceAutoDiscoveryEnabled,
                            configurationFingerprint = sourceConfigurationFingerprint(
                                configs,
                                settings.sourceAutoDiscoveryEnabled,
                            ),
                        ),
                    )
                }.getOrNull()
                if (partial != null) {
                    val mergedAt = System.currentTimeMillis()
                    val merged = synchronized(stateLock) {
                        val current = sourceIndex
                        val keptSites = current?.sites.orEmpty().filterNot { isUnderSourceRoot(it.filePath, rootAbs) }
                        val keptMeta = current?.fileMeta.orEmpty().filterKeys { !isUnderSourceRoot(it, rootAbs) }
                        SourceIndex(
                            version = SOURCE_INDEX_VERSION,
                            roots = settings.sourceFolders,
                            sites = keptSites + partial.sites,
                            fileMeta = keptMeta + partial.fileMeta,
                            builtAt = mergedAt,
                            rootBuiltAt = current?.rootBuiltAt.orEmpty() + (rootAbs to mergedAt),
                            rootConfigFingerprints = current?.rootConfigFingerprints.orEmpty() +
                                (rootAbs to sourceConfigurationFingerprint(
                                    sourceConfigurationsForFolder(rootAbs),
                                    settings.sourceAutoDiscoveryEnabled,
                                )),
                        )
                    }
                    runCatching { SourceIndexStore.save(merged, sourceIndexFile) }
                    publishSourceIndex(merged)
                }
            } finally {
                synchronized(stateLock) { indexingFolders = indexingFolders - rootAbs }
                finishLoading()
            }
        }
    }

    // Delegates to the cached resolver (rebuilt only when sourceIndex changes, see
    // publishSourceIndex) so a per-row lookup never recompiles every site's matcher regex. Matches
    // whose source file has drifted from the FileMeta recorded at index-build time are flagged
    // stale rather than dropped — still useful as an approximate pointer, just not to be trusted
    // at face value.
    //
    // sourceResolver is a plain field (not mutableStateOf, see its declaration) rebuilt alongside
    // sourceIndex in publishSourceIndex — reading the two together here under the same stateLock
    // used by that write is what actually gives a cross-thread caller (a future MCP/Ktor request
    // thread, not just Compose recomposition) a guaranteed-consistent (resolver, index) pair rather
    // than a race where sourceIndex has already published but sourceResolver hasn't yet become
    // visible on this thread.
    fun resolveLogSource(tag: String?, msg: String, limit: Int = 10): List<SourceMatch> {
        val (resolver, index) = synchronized(stateLock) {
            val resolver = sourceResolver ?: return emptyList()
            val index = sourceIndex ?: return emptyList()
            resolver to index
        }
        return resolver.resolve(tag, msg, limit).filter { match ->
            if (!match.site.configurationDependent) return@filter true
            val root = sourceRootForPath(match.site.filePath)
                ?: return@filter false
            index.rootConfigFingerprints[root] ==
                sourceConfigurationFingerprint(
                    sourceConfigurationsForFolder(root),
                    settings.sourceAutoDiscoveryEnabled,
                )
        }.map { match ->
            val meta = index.fileMeta[match.site.filePath]
            val stale = meta == null || fileChangedSince(match.site.filePath, meta)
            if (stale) match.copy(stale = true) else match
        }
    }

    fun resolveForLine(tabId: String, lineId: Int, limit: Int = 10): List<SourceMatch> {
        val entry = tab(tabId)?.rmap?.get(lineId) ?: return emptyList()
        return resolveLogSource(entry.tag, entry.msg, limit)
    }

    // Reads exactly the enclosing method's source lines (methodStartLine..methodEndLine, 1-based
    // inclusive) rather than the whole file — used by both the source popup (Task 4) and the MCP
    // tool (Task 5) that will display/return a call site's code.
    fun readMethodSource(site: LogCallSite): String? {
        val file = File(site.filePath)
        if (!file.isFile) return null
        return runCatching {
            val lines = file.readLines()
            val startIdx = site.methodStartLine - 1
            val endIdx = site.methodEndLine
            if (startIdx < 0 || endIdx > lines.size || startIdx >= endIdx) return@runCatching null
            lines.subList(startIdx, endIdx).joinToString("\n")
        }.getOrNull()
    }

    fun showSourceForLine(tabId: String, lineId: Int) {
        val matches = resolveForLine(tabId, lineId)
        if (matches.isNotEmpty()) sourceCodeView = SourceCodeView(matches)
    }

    // Opens filePath at line in an editor: settings.editorCommand if set, else the first working
    // AUTO_EDITOR_COMMANDS entry. There is deliberately no Desktop.open() fallback: that can open
    // the correct file but cannot honour the requested source line, which is worse than reporting
    // a missing editor launcher. Runs on ioScope so editor process startup never blocks the UI.
    fun openInEditor(filePath: String, line: Int) {
        val file = File(filePath)
        ioScope.launch {
            val custom = settings.editorCommand
            if (custom.isNotBlank()) {
                if (launchEditor(custom, file, line)) return@launch
                showOpenError(
                    title = "Could not open code location",
                    path = filePath,
                    message = "The configured Open command could not be launched. The file was not opened without its line " +
                        "location; check the executable path and the {file} and {line} placeholders in Settings.",
                )
                return@launch
            }
            if (AUTO_EDITOR_COMMANDS.any { launchEditor(it, file, line) }) return@launch
            showOpenError(
                title = "Could not open code location",
                path = filePath,
                message = "No supported editor launcher was found. Configure an Open command with {file} and {line} " +
                    "placeholders in Settings.",
            )
        }
    }

    fun exportFiltersToFile(selectedIds: Set<String>? = null) {
        val dlg = FileDialog(null as Frame?, "Export Filters", FileDialog.SAVE).apply {
            file = "openlog_filters.json"; isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        val file = File(dir, path)
        ioScope.launch { runCatching { file.writeText(exportFilters(selectedIds)) } }
        cancelExportFilters()
    }

    fun importFiltersFromFile() {
        val dlg = FileDialog(null as Frame?, "Import Filters", FileDialog.LOAD).apply {
            setFilenameFilter { _, n -> n.endsWith(".json") }; isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        importFiltersFromFileAsync(File(dir, path))
    }

    fun importFiltersFromFile(file: File) {
        runCatching { beginImportFilters(file.readText(), file.name) }
            .onFailure {
                importError = "Could not read filter file."
                pendingImportReview = null
            }
    }

    fun importFiltersFromFileAsync(file: File) {
        ioScope.launch { importFiltersFromFile(file) }
    }

    fun importFiltersFromFilesAsync(files: List<File>) {
        ioScope.launch {
            val decoded = files.mapNotNull { file -> runCatching { decodeFilters(file.readText()).getOrNull() }.getOrNull() }
            val imported = decoded.flatten()
            beginImportFilterList(imported, files.joinToString(", ") { it.name })
        }
    }

    // ── App data / clearable cache ────────────────────────────────────
    fun refreshAppDataSizeInfo() {
        appDataSizeBytes = File(appCachePath).totalFileSize()
    }

    /** Compatibility entry point; the Settings counter now reports all app data. */
    fun refreshArchiveCacheInfo() {
        refreshAppDataSizeInfo()
    }

    fun clearArchiveCache() {
        archiveCacheDir.listFiles().orEmpty().forEach { file -> runCatching { file.deleteRecursively() } }
        notesDir.listFiles().orEmpty().forEach { file -> runCatching { file.deleteRecursively() } }
        pruneMissingRecentNotes()
        refreshArchiveCacheInfo()
    }

    fun requestClearCache() {
        refreshArchiveCacheInfo()
        cacheClearConfirmOpen = true
    }

    fun cancelClearCache() {
        cacheClearConfirmOpen = false
    }

    fun confirmClearCache() {
        cacheClearConfirmOpen = false
        clearArchiveCache()
    }

    // ── Autosave ─────────────────────────────────────────────────────

    // When/how-often is owned by AutosaveScheduler (Task 12 slice 3) — see its doc comment for
    // the synchronous-by-design rationale (autosaveNow) and the debounce rationale
    // (autosaveInBackground). serializeAutosave below is what it calls back into.
    fun autosaveNow() = autosaveScheduler.saveNow()

    fun autosaveInBackground() = autosaveScheduler.saveInBackground()

    private fun restoreAutosave() {
        if (!autosaveFile.exists()) return
        runCatching {
            val lines = autosaveFile.readLines()
            if (lines.firstOrNull() != "openLog2-cache-v1") return
            val (keyLines, tabLines) = splitAutosaveLines(lines.drop(1))
            keyLines.forEach { line ->
                val key = line.substringBefore('\t')
                val value = line.substringAfter('\t', "")
                restoreAutosaveKey(key, value)
            }
            restoreTabsFromAutosave(tabLines)
            pruneMissingRecentNotes()
        }
    }

    private fun splitAutosaveLines(lines: List<String>): Pair<List<String>, List<String>> {
        val idx = lines.indexOf("tabs")
        return if (idx == -1) Pair(lines, emptyList()) else Pair(lines.take(idx), lines.drop(idx + 1))
    }

    private fun restoreAutosaveKey(key: String, value: String) {
        when (key) {
            "settings" -> settingsFromToken(value.unb64())?.let { settings = it }
            "active" -> activeTabId = value.unb64()
            "compare" -> restoreCompareState(value.unb64())
            "sequences" -> { /* legacy global-sequences key — migrated into tab filter; ignored on load */ }
            "saved" -> importFilters(value.unb64())
            "activeFilters" -> activeSavedFilterIds = activeFilterMapFromToken(value.unb64())
            "drafts" -> restoreDraftsFromToken(value.unb64())
            "transientRegex" -> transientRegexSearchTabIds = value.pathTokenList().toSet()
            "recent" -> recentFiles = value.pathTokenList()
            "recentNotes" -> recentNotes = value.pathTokenList()
            "filterPanel" -> fpState.restoreFilterPanelToken(value.unb64())
        }
    }

    // filterDraftsByTab/activeFilterDraftTabIds are the only pieces of the draft state that ever
    // need saving — a draft's id is always "draft_<tabId>" (see draftIdForTab), so the tab it
    // belongs to round-trips for free without a separate id->tabId token. Any restored draft is
    // by definition currently active for its tab (there's no "inactive draft" state), so
    // activeFilterDraftTabIds is just derived from the restored keys.
    private fun draftsToken(): String = exportFiltersList(filterDraftsByTab.values.toList())

    private fun restoreDraftsFromToken(json: String) {
        val decoded = decodeFilters(json).getOrElse { return }
        filterDraftsByTab = decoded.associateBy { it.id.removePrefix("draft_") }
        activeFilterDraftTabIds = filterDraftsByTab.keys.toSet()
    }

    // Runs during init{}, before this instance is shared with any other thread — the stateLock
    // wrap here is defense-in-depth (A-01), not a fix for an observed race: it removes the one
    // exception to "every read-modify-write of tabs goes through stateLock" documented on upTab
    // above, so that invariant holds unconditionally rather than by construction-order accident.
    private fun restoreTabsFromAutosave(tabLines: List<String>) = synchronized(stateLock) {
        val shells = tabLines.mapNotNull { it.removePrefix("tab\t").tabShellFromToken() }
        tabs = shells.map { it.tab }
        if (tabs.none { it.id == activeTabId }) activeTabId = tabs.firstOrNull()?.id ?: ""
        if (tabs.none { it.id == compareTabId }) compareTabId =
            tabs.getOrNull(1)?.id ?: tabs.firstOrNull()?.id ?: ""
        if (!canCompare) compareMode = false
        tabCounter.set((tabs.mapNotNull { it.id.removePrefix("t").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        // Shells restore synchronously with every user-visible piece of metadata (filter,
        // annotations, expanded/manual blocks). The file contents are queued and started by App()
        // after first composition; starting them inside AppState construction can race Compose's
        // initial snapshot apply and leave the UI stuck on the empty shell/loading state.
        pendingRestoredLoads.clear()
        pendingRestoredLoads += shells
    }

    private fun scheduleRestoredTabLoad(tabId: String, source: RestoredTabSource) {
        beginLoading("Restoring session...")
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            var published = false
            try {
                val logData = runCatching { source.load(parser) }.getOrElse { emptyList() }
                ensureActive()
                val rmap = mkRmap(logData)
                upTab(tabId) { it.copy(logData = logData, rmap = rmap, analysis = pendingAnalysis(logData)) }
                markActiveLoadFinished(tabId)
                published = true
                val full = buildLogAnalysis(logData)
                ensureActive()
                upTab(tabId) { it.copy(analysis = full) }
            } finally {
                val load = activeLoads.remove(tabId)
                if (!published) finishActiveLoad(load)
            }
        }
        activeLoads[tabId] = ActiveLoad(job)
        job.start()
    }

    private fun serializeAutosave(): String = buildString {
        appendLine("openLog2-cache-v1")
        appendLine("settings\t${settings.settingsToken().b64()}")
        appendLine("active\t${activeTabId.b64()}")
        appendLine("compare\t${compareStateToken().b64()}")
        appendLine("saved\t${exportFilters().b64()}")
        appendLine("activeFilters\t${activeFilterMapToken().b64()}")
        appendLine("drafts\t${draftsToken().b64()}")
        appendLine("transientRegex\t${transientRegexSearchTabIds.joinToString(",") { it.b64() }.b64()}")
        appendLine("recent\t${recentFiles.joinToString(",") { it.b64() }.b64()}")
        appendLine("recentNotes\t${recentNotes.joinToString(",") { it.b64() }.b64()}")
        appendLine("filterPanel\t${fpState.filterPanelToken().b64()}")
        appendLine("tabs")
        tabs.forEach { appendLine("tab\t${it.tabToken()}") }
    }
}

// ── JSON helpers (small reader for exported filter files) ─────────────
internal fun String.jsonStr(): String = buildString {
    append('"')
    this@jsonStr.forEach { ch ->
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            '\b' -> append("\\b")
            else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        }
    }
    append('"')
}

internal fun Map<String, Any?>.stringField(key: String): String? = this[key] as? String

internal fun Map<String, Any?>.booleanField(key: String): Boolean = this[key] == true

internal fun Map<String, Any?>.stringArrayField(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

internal fun String.jsonObjectArray(): Result<List<Map<String, Any?>>> =
    runCatching { JsonReader(this).readObjectArray() }

private class JsonReader(private val text: String) {
    private var pos = 0

    fun readObjectArray(): List<Map<String, Any?>> {
        skipWs()
        expect('[')
        val objects = mutableListOf<Map<String, Any?>>()
        skipWs()
        if (peek() == ']') {
            pos += 1
            return objects
        }
        while (true) {
            objects += readObject()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                ']' -> {
                    pos += 1
                    return objects
                }

                else -> error("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        val map = linkedMapOf<String, Any?>()
        skipWs()
        if (peek() == '}') {
            pos += 1
            return map
        }
        while (true) {
            val key = readString()
            skipWs()
            expect(':')
            skipWs()
            map[key] = readValue()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                '}' -> {
                    pos += 1
                    return map
                }

                else -> error("Expected ',' or '}' at $pos")
            }
        }
    }

    private fun readValue(): Any? = when (peek()) {
        '"' -> readString()
        '[' -> readStringArray()
        't' -> {
            expectText("true")
            true
        }

        'f' -> {
            expectText("false")
            false
        }

        else -> error("Unsupported JSON value at $pos")
    }

    private fun readStringArray(): List<String> {
        expect('[')
        val values = mutableListOf<String>()
        skipWs()
        if (peek() == ']') {
            pos += 1
            return values
        }
        while (true) {
            values += readString()
            skipWs()
            when (peek()) {
                ',' -> {
                    pos += 1
                    skipWs()
                }

                ']' -> {
                    pos += 1
                    return values
                }

                else -> error("Expected ',' or ']' at $pos")
            }
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (pos < text.length) {
            val ch = text[pos++]
            when (ch) {
                '"' -> return out.toString()
                '\\' -> {
                    val esc = text.getOrNull(pos++) ?: error("Dangling escape at $pos")
                    out.append(
                        when (esc) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> readUnicode()
                            else -> error("Unsupported escape \\$esc at $pos")
                        },
                    )
                }

                else -> out.append(ch)
            }
        }
        error("Unterminated string")
    }

    private fun readUnicode(): Char {
        if (pos + 4 > text.length) error("Short unicode escape at $pos")
        val hex = text.substring(pos, pos + 4)
        pos += 4
        return hex.toInt(16).toChar()
    }

    private fun expect(ch: Char) {
        skipWs()
        if (peek() != ch) error("Expected '$ch' at $pos")
        pos += 1
    }

    private fun expectText(value: String) {
        if (!text.startsWith(value, pos)) error("Expected '$value' at $pos")
        pos += value.length
    }

    private fun peek(): Char = text.getOrNull(pos) ?: error("Unexpected end of JSON")

    private fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos += 1
    }
}

internal fun SequenceDef.sequenceToken(): String =
    listOf(
        id,
        matchText,
        isRegex.toString(),
        priority.toString(),
        color.value.toString(),
        enabled.toString(),
        tag.orEmpty(),
        endMatchText.orEmpty(),
        endIsRegex.toString(),
        endTag.orEmpty(),
    ).joinToString("|") { it.b64() }

internal fun String.sequenceFromToken(): SequenceDef? = runCatching {
    val parts = split("|").map { it.unb64() }
    if (parts.size < 10) return@runCatching null
    SequenceDef(
        id = parts[0],
        matchText = parts[1],
        isRegex = parts[2].toBoolean(),
        priority = parts[3].toIntOrNull() ?: 0,
        color = Color(parts[4].toULong()),
        enabled = parts[5].toBoolean(),
        tag = parts[6].takeIf { it.isNotBlank() },
        endMatchText = parts[7].takeIf { it.isNotBlank() },
        endIsRegex = parts[8].toBoolean(),
        endTag = parts[9].takeIf { it.isNotBlank() },
    )
}.getOrNull()

internal fun MessageRule.messageRuleToken(): String =
    listOf(
        id,
        include.toString(),
        pattern,
        regex.toString(),
        tag.orEmpty(),
        packagePrefix.orEmpty(),
        enabled.toString(),
        target.name,
        mode.name,
    ).joinToString("|") { it.b64() }

internal fun String.messageRuleFromToken(): MessageRule? = runCatching {
    val parts = split("|").map { it.unb64() }
    if (parts.size < 7) return@runCatching null
    MessageRule(
        id = parts[0],
        include = parts[1].toBoolean(),
        pattern = parts[2],
        regex = parts[3].toBoolean(),
        tag = parts[4].takeIf { it.isNotBlank() },
        packagePrefix = parts[5].takeIf { it.isNotBlank() },
        enabled = parts[6].toBoolean(),
        target = if (parts.size > 7) runCatching { RuleTarget.valueOf(parts[7]) }.getOrElse { RuleTarget.MESSAGE } else RuleTarget.MESSAGE,
        mode = if (parts.size > 8) runCatching { FilterMode.valueOf(parts[8]) }.getOrElse { FilterMode.TAGS } else FilterMode.TAGS,
    )
}.getOrNull()

private fun String.fieldToken(): String = if (isEmpty()) "~" else b64()

private fun String.fieldValue(): String = if (this == "~") "" else unb64()

private fun tokenFields(vararg values: String): String = values.joinToString("|") { it.fieldToken() }

private fun String.tokenFields(): List<String> = split("|", limit = Int.MAX_VALUE).map { it.fieldValue() }

// Some issue trackers (certain Jira instances) reject a comment containing the literal word
// "java" outside a code block. Masks a configurable whole word (default "java") when copying a
// note's Markdown — skips the {code:java}/{code} fence marker lines buildMd() emits for
// AnnotationLogBlockStyle.JIRA_JAVA so the block's type token itself is never mangled.
internal fun maskWordForCopy(text: String, settings: AppSettings): String {
    if (!settings.maskWordOnCopy) return text
    val rules = settings.effectiveCopyMaskRules().filter { it.target.isNotBlank() }
    if (rules.isEmpty()) return text
    return text.lines().joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed == "{code:java}" || trimmed == "{code}") line
        else rules.fold(line) { masked, rule ->
            Regex("\\b${Regex.escape(rule.target)}\\b").replace(masked, rule.replacement)
        }
    }
}

private fun AppSettings.effectiveCopyMaskRules(): List<CopyMaskRule> {
    val defaultRule = CopyMaskRule("java", "j*ava")
    val legacyRule = CopyMaskRule(maskWordTarget, maskWordReplacement)
    // Retain correct behavior for source-compatible callers that still set the previous pair.
    // Autosave restoration always populates copyMaskRules explicitly, so an intentionally empty
    // modern collection remains empty and disables replacements even while the master switch is on.
    return if (copyMaskRules == listOf(defaultRule) && legacyRule != defaultRule) listOf(legacyRule) else copyMaskRules
}

private fun analysisNoteMarkdownName(filename: String): String {
    val base = filename.substringBeforeLast('.', filename)
    val safeBase = base.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "analysis" }
    return "${safeBase}_analysis.md"
}

private fun String.tokenList(): List<String> =
    if (isBlank()) emptyList() else unb64().split(",").filter { it.isNotBlank() }

private fun String.pathTokenList(): List<String> =
    tokenList().map { item -> runCatching { item.unb64() }.getOrElse { item } }

private fun AppSettings.settingsToken(): String = tokenFields(
    theme.name,
    fontSize.toString(),
    fontMono.toString(),
    defaultSaveDir.orEmpty(),
    mostUsedTagLimit.toString(),
    filterListRows.toString(),
    visibleTabLimit.toString(),
    autoExportNotes.toString(),
    autoSaveFilters.toString(),
    annotationLogBlockStyle.name,
    numberAnnotationBlocks.toString(),
    annotationPrefixLabel,
    navScrollMargin.toString(),
    logRowWrapLimitChars.toString(),
    autoLogRowWrap.toString(),
    mcpControlEnabled.toString(),
    mcpControlPort.toString(),
    maskWordOnCopy.toString(),
    maskWordTarget,
    maskWordReplacement,
    highlightEntireCrashGroup.toString(),
    ctrlFTarget.name,
    openNewFilesWithUnfiltered.toString(),
    // List field, not a scalar: reuses the same encoding as recentFiles/recentNotes in the
    // top-level autosave (join each b64-encoded path with a comma, then b64 the whole blob) rather
    // than a plain fieldToken() value — tokenFields()'s own fieldToken() wraps this blob with one
    // more b64 layer, which is exactly the extra layer pathTokenList()/tokenList() expect to peel
    // off on the way back in (see settingsFromToken below).
    sourceFolders.joinToString(",") { it.b64() }.b64(),
    editorCommand,
    aiProviderProfilesToken(),
    aiMaxToolRounds.toString(),
    sourceFolderInfoToken(),
    sourceLogConfigurationsToken(),
    sourceFolderConfigurationIdsToken(),
    sourceAutoDiscoveryEnabled.toString(),
    copyMaskRulesToken(),
    openUnfilteredOnCtrlF.toString(),
)

private fun AppSettings.copyMaskRulesToken(): String = copyMaskRules
    .joinToString(",") { rule -> tokenFields(rule.target, rule.replacement).b64() }
    .b64()

private fun String.copyMaskRulesFromToken(): List<CopyMaskRule> = runCatching {
    if (isBlank()) return@runCatching emptyList()
    unb64().split(',').filter { it.isNotBlank() }.mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 2) return@runCatching null
            CopyMaskRule(target = fields[0], replacement = fields[1])
        }.getOrNull()
    }
}.getOrElse { emptyList() }

private fun AppSettings.aiProviderProfilesToken(): String = normalizeAiProviderProfiles(aiProviderProfiles)
    .joinToString(",") { it.profileToken().b64() }
    .b64()

private fun AiProviderProfile.profileToken(): String = tokenFields(
    id,
    displayName,
    baseUrl,
    model,
    selected.toString(),
    remoteDisclosureAcknowledged.toString(),
    kind.name,
    executablePath,
    reasoningEffort,
)

private fun String.aiProviderProfilesFromToken(): List<AiProviderProfile> = runCatching {
    if (isBlank()) return@runCatching listOf(defaultAiProviderProfile())
    unb64().split(',').mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 6 || fields[0].isBlank()) return@runCatching null
            AiProviderProfile(
                id = fields[0],
                displayName = fields[1].ifBlank { "OpenAI-compatible" },
                baseUrl = fields[2],
                model = fields[3],
                selected = fields[4].toBooleanStrictOrNull() ?: false,
                remoteDisclosureAcknowledged = fields[5].toBooleanStrictOrNull() ?: false,
                kind = fields.getOrNull(6)?.let { raw ->
                    runCatching { AiProviderKind.valueOf(raw) }.getOrNull()
                } ?: AiProviderKind.OPENAI_COMPATIBLE,
                executablePath = fields.getOrNull(7).orEmpty(),
                reasoningEffort = fields.getOrNull(8).orEmpty(),
            )
        }.getOrNull()
    }.let(::normalizeAiProviderProfiles)
}.getOrElse { listOf(defaultAiProviderProfile()) }

private fun AppSettings.sourceFolderInfoToken(): String = sourceFolderInfo.entries
    .joinToString(",") { (path, info) -> tokenFields(path, info.description, info.readmePath.orEmpty()).b64() }
    .b64()

private fun String.sourceFolderInfoFromToken(): Map<String, SourceFolderInfo> = runCatching {
    if (isBlank()) return@runCatching emptyMap()
    unb64().split(',').mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 3 || fields[0].isBlank()) return@runCatching null
            fields[0] to SourceFolderInfo(description = fields[1], readmePath = fields[2].takeIf { it.isNotBlank() })
        }.getOrNull()
    }.toMap()
}.getOrElse { emptyMap() }

private fun SourceWrapperRule.wrapperRuleToken(): String = tokenFields(
    ownerType,
    methodName,
    tagArgumentIndex.toString(),
    messageArgumentIndex.toString(),
    throwableArgumentIndex?.toString().orEmpty(),
)

private fun String.wrapperRuleFromToken(): SourceWrapperRule? = runCatching {
    val fields = tokenFields()
    if (fields.size < 4 || fields[0].isBlank() || fields[1].isBlank()) return@runCatching null
    SourceWrapperRule(
        ownerType = fields[0],
        methodName = fields[1],
        tagArgumentIndex = fields[2].toIntOrNull() ?: return@runCatching null,
        messageArgumentIndex = fields[3].toIntOrNull() ?: return@runCatching null,
        throwableArgumentIndex = fields.getOrNull(4)?.toIntOrNull(),
    )
}.getOrNull()

private fun SourceLogConfiguration.configurationToken(): String = tokenFields(
    id,
    name,
    wrapperRules.joinToString(",") { it.wrapperRuleToken().b64() },
)

private fun String.configurationFromToken(): SourceLogConfiguration? = runCatching {
    val fields = tokenFields()
    if (fields.size < 2 || fields[0].isBlank()) return@runCatching null
    // v1 configurations included a per-configuration auto-discovery flag in field 2. The
    // setting is global now; preserve the rules while intentionally ignoring that legacy value.
    val legacyAutoDiscovery = fields.getOrNull(2)?.toBooleanStrictOrNull() != null
    SourceLogConfiguration(
        id = fields[0],
        name = fields[1].ifBlank { "Logging configuration" },
        wrapperRules = fields.getOrNull(if (legacyAutoDiscovery) 3 else 2).orEmpty().split(',').filter { it.isNotBlank() }
            .mapNotNull { encoded -> runCatching { encoded.unb64().wrapperRuleFromToken() }.getOrNull() },
    )
}.getOrNull()

private fun AppSettings.sourceLogConfigurationsToken(): String = sourceLogConfigurations
    .joinToString(",") { it.configurationToken().b64() }
    .b64()

private fun String.sourceLogConfigurationsFromToken(): List<SourceLogConfiguration> = runCatching {
    if (isBlank()) return@runCatching emptyList()
    unb64().split(',').filter { it.isNotBlank() }.mapNotNull { encoded ->
        runCatching { encoded.unb64().configurationFromToken() }.getOrNull()
    }.distinctBy { it.id }
}.getOrElse { emptyList() }

private fun AppSettings.sourceFolderConfigurationIdsToken(): String = sourceFolderConfigurationIds
    .entries
    .joinToString(",") { (path, ids) ->
        tokenFields(path, ids.joinToString(",") { it.b64() }).b64()
    }
    .b64()

private fun String.sourceFolderConfigurationIdsFromToken(): Map<String, List<String>> = runCatching {
    if (isBlank()) return@runCatching emptyMap()
    unb64().split(',').mapNotNull { encoded ->
        runCatching {
            val fields = encoded.unb64().tokenFields()
            if (fields.size < 2 || fields[0].isBlank()) return@runCatching null
            fields[0] to fields[1].split(',').filter { it.isNotBlank() }.mapNotNull { id ->
                runCatching { id.unb64() }.getOrNull()
            }.distinct()
        }.getOrNull()
    }.filter { it.second.isNotEmpty() }.toMap()
}.getOrElse { emptyMap() }

private fun settingsFromToken(token: String): AppSettings? = runCatching {
    val p = token.tokenFields()
    if (p.size < 5) return@runCatching null
    val hasFilterAutosaveField = p.getOrNull(8)?.toBooleanStrictOrNull() != null
    val tailOffset = if (hasFilterAutosaveField) 1 else 0
    val wrapIndex = 12 + tailOffset
    val hasWrapLimitField = p.getOrNull(wrapIndex)?.toIntOrNull() != null
    val autoWrapIndex = wrapIndex + 1
    val hasAutoWrapField = hasWrapLimitField &&
        p.getOrNull(autoWrapIndex)?.toBooleanStrictOrNull() != null &&
        p.getOrNull(autoWrapIndex + 1)?.toBooleanStrictOrNull() != null
    val mcpIndex = wrapIndex + (if (hasWrapLimitField) 1 else 0) + (if (hasAutoWrapField) 1 else 0)
    val legacyMaskTarget = p.getOrNull(mcpIndex + 3)?.takeIf { it.isNotBlank() } ?: "java"
    val legacyMaskReplacement = p.getOrNull(mcpIndex + 4)?.takeIf { it.isNotBlank() } ?: "j*ava"
    // These are deliberately appended after the pre-existing source settings so every historic
    // token position stays stable. A missing rules field represents the previous single pair.
    val copyMaskRules = p.getOrNull(mcpIndex + 16)?.copyMaskRulesFromToken()
        ?: listOf(CopyMaskRule(legacyMaskTarget, legacyMaskReplacement))
    AppSettings(
        theme = runCatching { ThemePreset.valueOf(p[0]) }.getOrElse { ThemePreset.LIGHT },
        fontSize = p[1].toIntOrNull() ?: 12,
        fontMono = p[2].toBoolean(),
        defaultSaveDir = p[3].takeIf { it.isNotBlank() },
        mostUsedTagLimit = p[4].toIntOrNull() ?: 5,
        filterListRows = p.getOrNull(5)?.toIntOrNull()?.coerceIn(1, 20) ?: 5,
        visibleTabLimit = p.getOrNull(6)?.toIntOrNull()?.coerceIn(2, 20) ?: 8,
        autoExportNotes = p.getOrNull(7)?.toBooleanStrictOrNull() ?: true,
        autoSaveFilters = if (hasFilterAutosaveField) p.getOrNull(8)?.toBooleanStrictOrNull() ?: true else true,
        annotationLogBlockStyle = p.getOrNull(8 + tailOffset)
            ?.let { runCatching { AnnotationLogBlockStyle.valueOf(it) }.getOrNull() }
            ?: AnnotationLogBlockStyle.INDENTED,
        numberAnnotationBlocks = p.getOrNull(9 + tailOffset)?.toBooleanStrictOrNull() ?: false,
        annotationPrefixLabel = p.getOrNull(10 + tailOffset)?.takeIf { it.isNotBlank() } ?: "From",
        navScrollMargin = p.getOrNull(11 + tailOffset)?.toIntOrNull()?.coerceIn(0, 30) ?: 5,
        logRowWrapLimitChars = p.getOrNull(wrapIndex)?.toIntOrNull()?.coerceIn(80, 20_000) ?: 480,
        autoLogRowWrap = if (hasAutoWrapField) {
            p.getOrNull(autoWrapIndex)?.toBooleanStrictOrNull() ?: true
        } else {
            true
        },
        mcpControlEnabled = p.getOrNull(mcpIndex)?.toBooleanStrictOrNull() ?: false,
        mcpControlPort = p.getOrNull(mcpIndex + 1)?.toIntOrNull()?.coerceIn(MIN_PORT, MAX_PORT) ?: DEFAULT_MCP_PORT,
        maskWordOnCopy = p.getOrNull(mcpIndex + 2)?.toBooleanStrictOrNull() ?: false,
        maskWordTarget = legacyMaskTarget,
        maskWordReplacement = legacyMaskReplacement,
        highlightEntireCrashGroup = p.getOrNull(mcpIndex + 5)?.toBooleanStrictOrNull() ?: false,
        ctrlFTarget = p.getOrNull(mcpIndex + 6)
            ?.let { runCatching { CtrlFTarget.valueOf(it) }.getOrNull() }
            ?: CtrlFTarget.KEYWORD_REGEX,
        openNewFilesWithUnfiltered = p.getOrNull(mcpIndex + 7)?.toBooleanStrictOrNull() ?: false,
        // Missing entirely (old token, predates this field) -> emptyList(); present-but-empty
        // (fieldToken's "~" for an empty list) -> also emptyList() via pathTokenList()'s own blank
        // check. See settingsToken above for why this field is pathTokenList()-shaped rather than
        // a plain fieldToken() string.
        sourceFolders = p.getOrNull(mcpIndex + 8)?.pathTokenList() ?: emptyList(),
        editorCommand = p.getOrNull(mcpIndex + 9)?.takeIf { it.isNotBlank() } ?: "",
        aiProviderProfiles = p.getOrNull(mcpIndex + 10)?.aiProviderProfilesFromToken()
            ?: listOf(defaultAiProviderProfile()),
        aiMaxToolRounds = p.getOrNull(mcpIndex + 11)?.toIntOrNull()
            ?.coerceIn(MIN_AI_MAX_TOOL_ROUNDS, MAX_AI_MAX_TOOL_ROUNDS)
            ?: DEFAULT_AI_MAX_TOOL_ROUNDS,
        // Missing entirely (old token, predates this field) -> emptyMap(), same backward-compat
        // pattern as sourceFolders above.
        sourceFolderInfo = p.getOrNull(mcpIndex + 12)?.sourceFolderInfoFromToken() ?: emptyMap(),
        sourceLogConfigurations = p.getOrNull(mcpIndex + 13)?.sourceLogConfigurationsFromToken() ?: emptyList(),
        sourceFolderConfigurationIds = p.getOrNull(mcpIndex + 14)?.sourceFolderConfigurationIdsFromToken() ?: emptyMap(),
        sourceAutoDiscoveryEnabled = p.getOrNull(mcpIndex + 15)?.toBooleanStrictOrNull() ?: true,
        copyMaskRules = copyMaskRules,
        openUnfilteredOnCtrlF = p.getOrNull(mcpIndex + 17)?.toBooleanStrictOrNull() ?: false,
    )
}.getOrNull()

private fun AppState.compareStateToken(): String = tokenFields(
    compareTabId,
    compareMode.toString(),
    compareFilterRight.toString(),
    filterVisible.toString(),
    annotationVisible.toString(),
    filterPanelWidth.toString(),
    annotationPanelWidth.toString(),
    compareSplit.toString(),
    aiPanelVisible.toString(),
    rightSidebarSplit.toString(),
)

private fun AppState.restoreCompareState(token: String) {
    val p = token.tokenFields()
    if (p.size < 8) return
    compareTabId = p[0]
    compareMode = p[1].toBoolean()
    compareFilterRight = p[2].toBoolean()
    filterVisible = p[3].toBoolean()
    annotationVisible = p[4].toBoolean()
    filterPanelWidth = p[5].toFloatOrNull() ?: filterPanelWidth
    annotationPanelWidth = (p[6].toFloatOrNull() ?: annotationPanelWidth).coerceIn(ANNOTATION_PANEL_MIN_WIDTH, ANNOTATION_PANEL_MAX_WIDTH)
    compareSplit = p[7].toFloatOrNull() ?: compareSplit
    // Trailing fields: absent on tokens from before the AI panel became independently toggleable.
    aiPanelVisible = p.getOrNull(8)?.toBooleanStrictOrNull() ?: false
    rightSidebarSplit = (p.getOrNull(9)?.toFloatOrNull() ?: rightSidebarSplit)
        .coerceIn(RIGHT_SIDEBAR_SPLIT_MIN, RIGHT_SIDEBAR_SPLIT_MAX)
}

private fun FilterPanelUiState.filterPanelToken(): String = tokenFields(
    hlListExpanded.toString(),
    lvlExpanded.toString(),
    seqExpanded.toString(),
    sfExpanded.toString(),
    incPillsExpanded.toString(),
    incMsgPillsExpanded.toString(),
    excMsgPillsExpanded.toString(),
    crashExpanded.toString(),
    crashCategory.name,
)

private fun FilterPanelUiState.restoreFilterPanelToken(token: String) {
    val p = token.tokenFields()
    if (p.size < 7) return
    hlListExpanded = p[0].toBoolean()
    lvlExpanded = p[1].toBoolean()
    seqExpanded = p[2].toBoolean()
    sfExpanded = p[3].toBoolean()
    incPillsExpanded = p[4].toBoolean()
    incMsgPillsExpanded = p[5].toBoolean()
    excMsgPillsExpanded = p[6].toBoolean()
    crashExpanded = p.getOrNull(7)?.toBooleanStrictOrNull() ?: crashExpanded
    crashCategory = p.getOrNull(8)?.let { runCatching { CrashCategory.valueOf(it) }.getOrNull() } ?: crashCategory
}

private fun AppState.activeFilterMapToken(): String =
    activeSavedFilterIds.entries.joinToString(",") { tokenFields(it.key, it.value) }

private fun activeFilterMapFromToken(token: String): Map<String, String> =
    if (token.isBlank()) emptyMap()
    else token.split(",").mapNotNull { item ->
        val p = item.tokenFields()
        if (p.size >= 2) p[0] to p[1] else null
    }.toMap()

internal fun Highlighter.highlighterToken(): String = tokenFields(
    id,
    pattern,
    regex.toString(),
    color.value.toString(),
    on.toString(),
)

internal fun String.highlighterFromToken(): Highlighter? = runCatching {
    val p = tokenFields()
    if (p.size < 5) return@runCatching null
    Highlighter(
        id = p[0],
        pattern = p[1],
        regex = p[2].toBoolean(),
        color = Color(p[3].toULong()),
        on = p[4].toBoolean(),
    )
}.getOrNull()

private fun SavedFilter.savedFilterToken(): String = tokenFields(
    id,
    name,
    levels.joinToString("") { it.key.toString() },
    activeTags.joinToString(",") { it.b64() },
    kwText,
    kwRegex.toString(),
    mode.name,
    excludeTags.joinToString(",") { it.b64() },
    excludeKw,
    excludeKwRegex.toString(),
    highlighters.joinToString(",") { it.highlighterToken().b64() },
    seqOn.toString(),
    kwInTag,
    kwInTagRegex.toString(),
    pkgPrefixes.joinToString(",") { it.b64() },
    pidTidFilter,
    sequences.joinToString(",") { it.sequenceToken().b64() },
    messageRules.joinToString(",") { it.messageRuleToken().b64() },
    excludePkgPrefixes.joinToString(",") { it.b64() },
    kwHighlightEnabled.toString(),
    kwHighlightColor.value.toString(),
)

private fun String.savedFilterFromToken(): SavedFilter? = runCatching {
    val p = tokenFields()
    if (p.size < 18) return@runCatching null
    SavedFilter(
        id = p[0],
        name = p[1],
        levels = p[2].mapNotNull { key -> LogLevel.entries.find { it.key == key } }.toSet()
            .ifEmpty { LogLevel.entries.toSet() },
        activeTags = p[3].encodedSet(),
        kwText = p[4],
        kwRegex = p[5].toBoolean(),
        mode = runCatching { FilterMode.valueOf(p[6]) }.getOrElse { FilterMode.TAGS },
        excludeTags = p[7].encodedSet(),
        excludeKw = p[8],
        excludeKwRegex = p[9].toBoolean(),
        highlighters = p[10].encodedList().mapNotNull { it.highlighterFromToken() },
        seqOn = p[11].toBoolean(),
        kwInTag = p[12],
        kwInTagRegex = p[13].toBoolean(),
        pkgPrefixes = p[14].encodedSet(),
        pidTidFilter = p[15],
        sequences = p[16].encodedList().mapNotNull { it.sequenceFromToken() },
        messageRules = p[17].encodedList().mapNotNull { it.messageRuleFromToken() },
        excludePkgPrefixes = p.getOrNull(18)?.encodedSet() ?: emptySet(),
        kwHighlightEnabled = p.getOrNull(19)?.toBooleanStrictOrNull() ?: true,
        kwHighlightColor = p.getOrNull(20)?.toULongOrNull()?.let(::Color) ?: DEFAULT_KEYWORD_HIGHLIGHT_COLOR,
    )
}.getOrNull()

private fun SavedFilter.toFilter(): Filter = Filter(
    levels = levels,
    activeTags = activeTags,
    kwText = kwText,
    kwRegex = kwRegex,
    mode = mode,
    excludeTags = excludeTags,
    excludeKw = excludeKw,
    excludeKwRegex = excludeKwRegex,
    highlighters = highlighters,
    messageRules = messageRules,
    kwHighlightEnabled = kwHighlightEnabled,
    kwHighlightColor = kwHighlightColor,
    seqOn = seqOn,
    kwInTag = kwInTag,
    kwInTagRegex = kwInTagRegex,
    pkgPrefixes = pkgPrefixes,
    excludePkgPrefixes = excludePkgPrefixes,
    pidTidFilter = pidTidFilter,
    sequences = sequences,
)

private fun String.encodedList(): List<String> =
    if (isBlank()) emptyList() else split(",").filter { it.isNotBlank() }.map { it.unb64() }

private fun String.encodedSet(): Set<String> = encodedList().toSet()

private fun LogEntry.toAnnToken(): String =
    listOf(id.toString(), ts, level.key.toString(), tag, msg, pid.toString(), tid.toString())
        .joinToString("|") { it.b64() }

private fun String.toLogEntryFromAnnToken(): LogEntry? = runCatching {
    val p = split("|").map { it.unb64() }
    if (p.size < 5) return@runCatching null
    LogEntry(
        id = p[0].toIntOrNull() ?: 0,
        ts = p[1],
        level = LogLevel.from(p[2].firstOrNull() ?: 'I'),
        tag = p[3],
        msg = p[4],
        pid = p.getOrNull(5)?.toIntOrNull() ?: 0,
        tid = p.getOrNull(6)?.toIntOrNull() ?: 0,
    )
}.getOrNull()

private fun AnnBlock.annBlockToken(): String = when (this) {
    is AnnBlock.Note -> tokenFields("N", id, text)
    is AnnBlock.LogRef -> tokenFields(
        "R", id,
        logIds.joinToString(","),
        caption,
        sourceTabId.orEmpty(),
        sourceFilename.orEmpty(),
        sourceEntries?.joinToString(";") { it.toAnnToken() }.orEmpty(),
    )
}

private fun String.annBlockFromToken(): AnnBlock? = runCatching {
    val p = tokenFields()
    if (p.size < 3) return@runCatching null
    when (p[0]) {
        "N" -> AnnBlock.Note(p[1], p[2])
        "R" -> AnnBlock.LogRef(
            id = p[1],
            logIds = p[2].split(",").mapNotNull { it.toIntOrNull() },
            caption = p.getOrElse(3) { "" },
            sourceTabId = p.getOrElse(4) { "" }.takeIf { it.isNotBlank() },
            sourceFilename = p.getOrElse(5) { "" }.takeIf { it.isNotBlank() },
            sourceEntries = p.getOrElse(6) { "" }.takeIf { it.isNotBlank() }
                ?.split(";")?.mapNotNull { it.toLogEntryFromAnnToken() },
        )

        else -> null
    }
}.getOrNull()

private fun Annotations.annotationsToken(): String = tokenFields(
    prefix,
    suffix,
    blocks.joinToString(",") { it.annBlockToken().b64() },
    issueDescription,
)

private fun String.annotationsFromToken(): Annotations? = runCatching {
    val p = tokenFields()
    if (p.size < 3) return@runCatching null
    Annotations(
        prefix = p[0],
        suffix = p[1],
        blocks = p[2].encodedList().mapNotNull { it.annBlockFromToken() },
        issueDescription = p.getOrNull(3) ?: "",
    )
}.getOrNull()

private fun ManualCollapseBlock.manualBlockToken(): String = tokenFields(
    id,
    anchorId.toString(),
    direction.name,
    color.value.toString(),
    enabled.toString(),
    endId?.toString().orEmpty(),
)

private fun String.manualBlockFromToken(): ManualCollapseBlock? = runCatching {
    val p = tokenFields()
    if (p.size < 5) return@runCatching null
    ManualCollapseBlock(
        id = p[0],
        anchorId = p[1].toIntOrNull() ?: return@runCatching null,
        direction = runCatching { ManualCollapseDirection.valueOf(p[2]) }.getOrElse { ManualCollapseDirection.TO_END },
        color = Color(p[3].toULong()),
        enabled = p[4].toBoolean(),
        endId = p.getOrNull(5)?.toIntOrNull(),
    )
}.getOrNull()

private fun LogTab.tabToken(): String {
    val filter = SavedFilter(
        "tab", "tab", filter.levels, filter.activeTags, filter.kwText, filter.kwRegex,
        filter.mode, filter.excludeTags, filter.excludeKw, filter.excludeKwRegex, filter.highlighters, filter.seqOn,
        filter.kwInTag, filter.kwInTagRegex, filter.pkgPrefixes, filter.pidTidFilter, filter.sequences,
        filter.messageRules, filter.excludePkgPrefixes, filter.kwHighlightEnabled, filter.kwHighlightColor,
    )
    return tokenFields(
        id,
        filename,
        sourcePath.orEmpty(),
        filter.savedFilterToken(),
        annotations.annotationsToken(),
        showAnnMd.toString(),
        showUnfiltered.toString(),
        expanded.joinToString(",") { it.b64() },
        manualBlocks.joinToString(",") { it.manualBlockToken().b64() },
    )
}

// Metadata-only restore: log content is parsed afterwards on ioScope (scheduleRestoredTabLoad),
// so this must stay cheap — it runs synchronously during AppState init. Source-exists checks
// stay here so tabs whose backing file/archive vanished are dropped before they ever appear.
private class RestoredTabShell(val tab: LogTab, val source: RestoredTabSource)

private sealed class RestoredTabSource {
    abstract val largeFileMode: Boolean

    abstract fun load(parser: (File) -> List<LogEntry>): List<LogEntry>

    data class FileSource(val file: File) : RestoredTabSource() {
        override val largeFileMode: Boolean = file.length() >= LARGE_FILE_MODE_BYTES

        override fun load(parser: (File) -> List<LogEntry>): List<LogEntry> = parser(file)
    }

    data class ArchiveSource(val archiveFile: File, val candidate: ZipLogCandidate) : RestoredTabSource() {
        override val largeFileMode: Boolean = candidate.sizeBytes >= LARGE_FILE_MODE_BYTES

        override fun load(parser: (File) -> List<LogEntry>): List<LogEntry> = extractCandidate(archiveFile, candidate)
    }
}

private fun String.tabShellFromToken(): RestoredTabShell? = runCatching {
    val p = tokenFields()
    if (p.size < 9) return@runCatching null
    val sourcePath = p[2].takeIf { it.isNotBlank() }
    val source = sourcePath?.restoredTabSource() ?: return@runCatching null
    RestoredTabShell(
        LogTab(
            id = p[0],
            filename = p[1],
            logData = emptyList(),
            rmap = emptyMap(),
            filter = p[3].savedFilterFromToken()?.toFilter() ?: Filter(),
            showUnfiltered = p[6].toBoolean(),
            expanded = p[7].encodedSet(),
            annotations = p[4].annotationsFromToken() ?: Annotations(),
            showAnnMd = p[5].toBoolean(),
            manualBlocks = p[8].encodedList().mapNotNull { it.manualBlockFromToken() },
            sourcePath = sourcePath,
            largeFileMode = source.largeFileMode,
        ),
        source,
    )
}.getOrNull()

private fun String.restoredTabSource(): RestoredTabSource? {
    val bangIndex = indexOf('!')
    if (bangIndex > 0) {
        val archiveFile = File(substring(0, bangIndex)).takeIf { it.exists() } ?: return null
        val entryPath = substring(bangIndex + 1).takeIf { it.isNotBlank() } ?: return null
        val candidate = listArchiveLogCandidates(archiveFile).firstOrNull { it.entryPath == entryPath } ?: return null
        return RestoredTabSource.ArchiveSource(archiveFile, candidate)
    }
    return File(this).takeIf { it.exists() }?.let { RestoredTabSource.FileSource(it) }
}

private fun File.totalFileSize(): Long =
    if (!exists()) 0L
    else if (isFile) length()
    else listFiles().orEmpty().sumOf { it.totalFileSize() }

private fun String.b64(): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(Charsets.UTF_8))

private fun String.unb64(): String =
    String(Base64.getUrlDecoder().decode(this), Charsets.UTF_8)
