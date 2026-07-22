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
import com.openlog.debug.AppLogger
import com.openlog.debug.ControlServer
import com.openlog.debug.OpenLogToolOperations
import com.openlog.debug.loadOrCreateControlToken
import com.openlog.generated.BuildInfo
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
import com.openlog.update.ReleaseInfo
import com.openlog.update.UpdateCheckResult
import com.openlog.update.UpdateChecker
import com.openlog.update.assetForCurrentOs
import com.openlog.update.revealInFileManager
import com.openlog.utils.ArchiveBudgetExceededException
import com.openlog.utils.EntryIdMap
import com.openlog.utils.MAX_ARCHIVE_ENTRY_BYTES
import com.openlog.utils.MergeSourceFile
import com.openlog.utils.RegexEvaluationContext
import com.openlog.utils.SPLIT_PROMPT_BYTES
import com.openlog.utils.SearchComputeResult
import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.buildMd
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeItems
import com.openlog.utils.computeSearchMatches
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.exportFilteredToFile
import com.openlog.utils.extractAppVersionHeuristic
import com.openlog.utils.extractCandidate
import com.openlog.utils.indexOfEntryId
import com.openlog.utils.invalidateComputeCache
import com.openlog.utils.isLikelyTextFile
import com.openlog.utils.isSupportedArchiveFile
import com.openlog.utils.listArchiveLogCandidates
import com.openlog.utils.mergeLogs
import com.openlog.utils.newId
import com.openlog.utils.openArchiveCandidateStream
import com.openlog.utils.parseLogcat
import com.openlog.utils.passesFilter
import com.openlog.utils.planSplitOutputs
import com.openlog.utils.requiresSplitPrompt
import com.openlog.utils.splitFileToFiles
import com.openlog.utils.splitStreamToFiles
import com.openlog.utils.suggestedSplitPartCount
import kotlinx.coroutines.*
import kotlinx.serialization.json.add
import java.awt.FileDialog
import java.awt.Frame
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
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

// One entry in the editor catalog offered by the Settings → Source code editor-choice dropdown.
// [id] is the stable key persisted in AppSettings.editorChoice; [candidates] are command templates
// tried in order (mac/Windows/Linux variants coexist in one list — resolveExecutable naturally only
// resolves the ones actually installed, so no OS branching is needed here).
data class EditorPreset(
    val id: String,
    val displayName: String,
    val candidates: List<String>,
)

// Recommended-editor catalog: superset of the old flat AUTO_EDITOR_COMMANDS list, now structured so
// the Settings UI can offer a named dropdown choice per app instead of one opaque free-text field.
// Each preset lists a bare CLI candidate first (works when the editor's shell command is on PATH,
// incl. Linux/Windows), then macOS .app bundle absolute paths as a fallback. The bundle fallback
// matters because a GUI app doesn't inherit the interactive shell PATH, and most macOS users never
// run the editor's "Install shell command in PATH" step — so `code`/`cursor`/`subl` aren't on PATH
// even though the CLI ships inside the bundle. splitEditorCommand resolves the space-containing
// bundle paths correctly.
internal val EDITOR_CATALOG = listOf(
    EditorPreset(
        "vscode",
        "VS Code",
        listOf(
            "code -g {file}:{line}",
            "/Applications/Visual Studio Code.app/Contents/Resources/app/bin/code -g {file}:{line}",
        ),
    ),
    EditorPreset(
        "intellij",
        "IntelliJ IDEA",
        listOf(
            "idea --line {line} {file}",
            "/Applications/IntelliJ IDEA.app/Contents/MacOS/idea --line {line} {file}",
            "idea64 --line {line} {file}",
        ),
    ),
    EditorPreset(
        "studio",
        "Android Studio",
        listOf(
            "studio --line {line} {file}",
            "/Applications/Android Studio.app/Contents/MacOS/studio --line {line} {file}",
        ),
    ),
    EditorPreset(
        "cursor",
        "Cursor",
        listOf(
            "cursor -g {file}:{line}",
            "/Applications/Cursor.app/Contents/Resources/app/bin/cursor -g {file}:{line}",
        ),
    ),
    EditorPreset(
        "sublime",
        "Sublime Text",
        listOf(
            "subl {file}:{line}",
            "/Applications/Sublime Text.app/Contents/SharedSupport/bin/subl {file}:{line}",
        ),
    ),
    EditorPreset(
        "zed",
        "Zed",
        listOf(
            "zed {file}:{line}",
            "/Applications/Zed.app/Contents/MacOS/cli {file}:{line}",
        ),
    ),
)

// Auto-detect fallback chain for AppState.openInEditor's "auto"/blank editorChoice path: every
// catalog candidate template, in catalog order — the first one whose CLI is actually on PATH (i.e.
// ProcessBuilder.start() succeeds) wins. Each template supports jumping straight to a line, unlike
// plain Desktop.open().
private val AUTO_EDITOR_COMMANDS = EDITOR_CATALOG.flatMap { it.candidates }

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

// First candidate template in [preset] whose executable actually resolves (i.e. is installed) under
// [dirs], or null if none do. Reuses [splitEditorCommand] — no new process/exec logic; this is just
// "does any candidate resolve", checked in catalog order.
internal fun detectedTemplate(preset: EditorPreset, dirs: List<File> = executableSearchDirs()): String? =
    preset.candidates.firstOrNull { splitEditorCommand(it, dirs) != null }

private fun EditorPreset.commandTemplateForExecutable(executable: File): String = when (id) {
    "vscode", "cursor" -> "${executable.absolutePath} -g {file}:{line}"
    "intellij", "studio" -> "${executable.absolutePath} --line {line} {file}"
    "sublime", "zed" -> "${executable.absolutePath} {file}:{line}"
    else -> "${executable.absolutePath} {file}"
}

private fun desktopEntryValue(lines: List<String>, key: String): String? =
    lines.firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }

private fun desktopEntryExecutable(value: String, dirs: List<File>): File? {
    val trimmed = value.trim()
    val token = if (trimmed.startsWith('"')) {
        trimmed.substringAfter('"').substringBefore('"')
    } else {
        trimmed.split(Regex("\\s+"), limit = 2).first()
    }
    return token.takeIf { it.isNotBlank() }?.let { resolveExecutable(it, dirs) }
}

private fun desktopEntryPresetId(file: File, name: String?): String? {
    val identity = "${file.nameWithoutExtension} ${name.orEmpty()}".lowercase()
    return when {
        "android-studio" in identity || "android studio" in identity -> "studio"
        "intellij" in identity || "jetbrains-idea" in identity || identity.contains(" idea") -> "intellij"
        "visual studio code" in identity || "vscode" in identity || file.nameWithoutExtension == "code" -> "vscode"
        "cursor" in identity -> "cursor"
        "sublime" in identity -> "sublime"
        "zed" in identity -> "zed"
        else -> null
    }
}

private fun linuxDesktopEntryDirs(): List<File> {
    val home = System.getProperty("user.home").orEmpty()
    val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() } ?: "$home/.local/share"
    val dataDirs = System.getenv("XDG_DATA_DIRS")?.split(':').orEmpty()
        .ifEmpty { listOf("/usr/local/share", "/usr/share") }
    return (listOf(dataHome) + dataDirs)
        .map { File(it, "applications") }
        .distinct()
        .filter { it.isDirectory }
}

// Linux desktop packages and JetBrains Toolbox commonly register applications through a .desktop
// file but do not add their CLI to a GUI app's inherited PATH. Read TryExec/Exec directly, validate
// it with the same resolver as normal candidates, then generate the app's line-jump command.
internal fun linuxDesktopEditorTemplates(
    desktopEntryDirs: List<File> = linuxDesktopEntryDirs(),
    executableDirs: List<File> = executableSearchDirs(),
): Map<String, List<String>> {
    val found = LinkedHashMap<String, MutableList<String>>()
    desktopEntryDirs.flatMap { dir -> dir.listFiles { file -> file.isFile && file.extension == "desktop" }.orEmpty().toList() }
        .sortedBy { it.name }
        .forEach { entryFile ->
            val lines = runCatching { entryFile.readLines() }.getOrNull() ?: return@forEach
            val presetId = desktopEntryPresetId(entryFile, desktopEntryValue(lines, "Name")) ?: return@forEach
            val executable = sequenceOf("TryExec", "Exec")
                .mapNotNull { key -> desktopEntryValue(lines, key)?.let { desktopEntryExecutable(it, executableDirs) } }
                .firstOrNull()
                ?: return@forEach
            val preset = EDITOR_CATALOG.firstOrNull { it.id == presetId } ?: return@forEach
            found.getOrPut(presetId) { mutableListOf() }
                .add(preset.commandTemplateForExecutable(executable))
        }
    return found.mapValues { (_, templates) -> templates.distinct() }
}

// EDITOR_CATALOG filtered down to installed editors, each paired with its resolved command
// template (the first matching candidate) — the ordered list of choices the Settings dropdown
// offers, and the read-only "resolved command" shown once one is selected.
internal fun detectInstalledEditors(dirs: List<File> = executableSearchDirs()): List<Pair<EditorPreset, String>> {
    val desktopTemplates = if (System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) {
        linuxDesktopEditorTemplates(executableDirs = dirs)
    } else {
        emptyMap()
    }
    return EDITOR_CATALOG.mapNotNull { preset ->
        (preset.candidates + desktopTemplates[preset.id].orEmpty())
            .firstOrNull { splitEditorCommand(it, dirs) != null }
            ?.let { preset to it }
    }
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
internal const val LARGE_FILE_MODE_BYTES = 64L * 1024L * 1024L

// Debounce for in-view search recompute (AppState.scheduleSearchRecompute) — matches the keyword
// filter's own debounce (see FilterPanel's kwDisplay LaunchedEffect) so typing into the Find bar
// feels the same as typing into the filter's keyword field.
internal const val SEARCH_RECOMPUTE_DEBOUNCE_MS = 150L

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
    val anchor = tab.logData.indexOfEntryId(anchorId).takeIf { it >= 0 } ?: return null
    return when (direction) {
        ManualCollapseDirection.TO_START -> 0..anchor
        ManualCollapseDirection.TO_END -> anchor..tab.logData.lastIndex
        ManualCollapseDirection.RANGE -> {
            val end = endId?.let { tab.logData.indexOfEntryId(it) }?.takeIf { it >= 0 } ?: return null
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
    // Exception: a TO_START/TO_END request is allowed to overlap the existing block of that same
    // direction — there's at most one (two same-direction blocks always overlap each other), and
    // addManualCollapse rewrites it with the new boundary instead of rejecting the request.
    val overlapsExisting = tab.manualBlocks
        .filterNot { direction != ManualCollapseDirection.RANGE && it.direction == direction }
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

data class PendingImportReview(
    val rows: List<ImportFilterReviewRow>,
    val stagedFolders: List<SavedFilterFolder> = emptyList(),
    val sourceName: String? = null,
)

data class OpenFileError(val title: String, val path: String?, val message: String)

data class AnnotationNavigationRequest(val id: Long, val tabId: String, val logIds: List<Int>)

// Search next/prev's own navigation request — deliberately separate from
// AnnotationNavigationRequest above rather than reusing it. Annotation/crash/ctx-anchor jumps are
// infrequent, one-off moves where a full center-the-viewport reorientation is the right feel; Find
// bar Enter/Next/Prev is a frequent, often-repeated action where the same two-phase
// scroll-to-top-then-recenter reads as a jarring flash — including, absurdly, when the match is
// already fully on screen. LogViewer.kt's searchNavigationRequest-handling LaunchedEffect instead
// only scrolls the minimum needed (scrollForCursor's existing margin logic), a no-op when the
// target is already comfortably visible; centering is reserved for when a collapsed group actually
// had to be expanded to reveal the match at all, i.e. a real state change big enough that jumping
// to it deserves recentering the same way expand-all or annotation navigation does.
data class SearchNavigationRequest(val id: Long, val tabId: String, val entryId: Int)

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

// Update-check status shown next to Settings' "Check now" button (AutomationSettingsSection).
// Deliberately has no "an update is available" case of its own — AppState.availableUpdate already
// carries that (and drives the update dialog), so a caller checks availableUpdate first and only
// falls back to this for the plain checked-and-current / still-checking / failed states.
sealed interface UpdateStatus {
    data object Idle : UpdateStatus

    data object Checking : UpdateStatus

    data class UpToDate(val version: String) : UpdateStatus

    /** Only ever set for a manual check (AppState.checkForUpdates) — a silent startup check that
     *  fails leaves this at [Idle] instead, so it never surfaces an error the user didn't ask for. */
    data class Failed(val reason: String) : UpdateStatus
}

/** Download progress for the release asset picked by [assetForCurrentOs], driven by [AppState.downloadUpdate]. */
sealed interface UpdateDownloadState {
    data object Idle : UpdateDownloadState

    data class InProgress(val fraction: Float) : UpdateDownloadState

    data class Done(val file: File) : UpdateDownloadState

    data class Failed(val reason: String) : UpdateDownloadState
}

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
    // Restore-only seams: refreshed archive metadata must be resolved off the UI thread before
    // publishing parsed rows. Keeping the production threshold unchanged while injecting both
    // pieces lets tests cross it with tiny fixtures and prove construction never lists archives.
    private val restoredArchiveLargeFileModeBytes: Long = LARGE_FILE_MODE_BYTES,
    private val restoredArchiveCandidateResolver: (File, String) -> ZipLogCandidate? = { archiveFile, entryPath ->
        listArchiveLogCandidates(archiveFile).firstOrNull { it.entryPath == entryPath }
    },
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
        // Read fresh at factory-invocation time (every start/restart), not captured once at
        // AppState construction — CORS is a Ktor plugin installed once per server instance, so a
        // settings change only takes effect on the next start; ControlServerManager's
        // setMcpAllowBrowserClients forces exactly that restart when the server is already running.
        ControlServer(s, p, token = loadOrCreateControlToken(controlTokenFile), allowBrowserClients = s.settings.mcpAllowBrowserClients)
            .also { it.start() }
    },
    // Directory selection needs a different native implementation than FileDialog's file picker.
    // Keep it injectable so folder-selection flows remain testable without opening a Swing window.
    private val directoryPicker: (String, File?) -> File? = { title, initialDirectory ->
        PlatformDirectoryPicker.pick(title, initialDirectory)
    },
    private val updateChecker: UpdateChecker = UpdateChecker(),
) {
    // ── Settings ────────────────────────────────────────────────────
    var settings by mutableStateOf(AppSettings())

    // Session-only outcome of configuring the diagnostic writer.  The preference remains enabled
    // after a failure so the Settings retry action can repair a transient permission/disk problem
    // without making the user rediscover their chosen destination.
    var debugLoggingError by mutableStateOf<String?>(null)
        private set

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

    // Cache of installed editors for the Settings → Source code editor-choice dropdown, populated
    // by [rescanEditors]. Null means "not scanned yet" (shows a "Detecting…" placeholder in the UI),
    // distinct from an empty list (scanned, nothing found).
    var detectedEditors by mutableStateOf<List<Pair<EditorPreset, String>>?>(null)

    // Runs detectInstalledEditors() off the UI thread and publishes the result. Compose
    // mutableStateOf is snapshot-safe to write from any thread (see CLAUDE.md) so no
    // withContext(Dispatchers.Main) is needed. Safe to call repeatedly (e.g. a Settings "Rescan"
    // button, or first-open of the Source code section) — each call simply re-scans and overwrites.
    fun rescanEditors() {
        ioScope.launch { detectedEditors = detectInstalledEditors() }
    }

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

    // Which tab's log panel most recently held keyboard focus — set from FileView.kt's single
    // panel and both of CompareView.kt's left/right panels' onPanelFocusChanged. Consulted only in
    // compare mode (see App.kt's onFocusFilterSearch): activeTabId always names the left panel
    // there, so this is what lets Ctrl+F open the Find bar on whichever of the two panels the user
    // was actually in, instead of always the left one. Session-only, never persisted; single-tab
    // (non-compare) mode always resolves via activeTab() instead, regardless of this value, since a
    // tab switch there doesn't necessarily re-focus the log panel and would otherwise go stale.
    var searchFocusTabId: String? by mutableStateOf(null)

    private val ioJob = SupervisorJob()
    private val ioScope = CoroutineScope(ioJob + Dispatchers.IO)
    private val closed = AtomicBoolean(false)

    // Debounce jobs backing in-view search recompute (openSearch/setSearchQuery/... below) — keyed
    // by tabId, same cancel-and-relaunch shape as TailCoordinator's tailAnalysisJobs and AppState's
    // own autosave debounce. ConcurrentHashMap since a tab can be closed (removing its job) from the
    // UI thread while a previously-scheduled recompute for that same tabId is still in flight.
    private val searchJobs = ConcurrentHashMap<String, Job>()

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
    var sfFolderId by mutableStateOf<String?>(null)
    var savedFilters by mutableStateOf<List<SavedFilter>>(emptyList())
    var savedFilterFolders by mutableStateOf<List<SavedFilterFolder>>(emptyList())
    var tagUsage by mutableStateOf<Map<String, Int>>(emptyMap())
    var settingsOpen by mutableStateOf(false)
    var licenseAgreementOpen by mutableStateOf(false)

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
    var resetAppDataConfirmOpen by mutableStateOf(false)
    var resetAppDataError by mutableStateOf<String?>(null)
    val archiveCachePath: String = archiveCacheDir.absolutePath
    val appCachePath: String = archiveCacheDir.parentFile?.absolutePath ?: archiveCacheDir.absolutePath

    /** Recursive size of the app-data directory displayed in Settings. */
    var appDataSizeBytes by mutableStateOf(0L)
        private set

    /** Size of the data removed by the user-facing "Clear temporary data" action. */
    var temporaryDataSizeBytes by mutableStateOf(0L)
        private set

    /** Compatibility alias for callers that still use the previous cache-only name. */
    val archiveCacheSizeBytes: Long get() = temporaryDataSizeBytes
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
    var pendingDeleteSavedFilterFolderId by mutableStateOf<String?>(null)
    var pendingClearFilterTabId by mutableStateOf<String?>(null)
    var activeSavedFilterIds by mutableStateOf<Map<String, String>>(emptyMap())
    var filterDraftsByTab by mutableStateOf<Map<String, SavedFilter>>(emptyMap())
    var activeFilterDraftTabIds by mutableStateOf<Set<String>>(emptySet())

    // Regex search is deliberately transient: it keeps the current saved-filter marker visible,
    // without becoming a draft or blocking a later click on a saved preset.
    private var transientRegexSearchTabIds by mutableStateOf<Set<String>>(emptySet())
    var pendingAnnotationNavigation by mutableStateOf<AnnotationNavigationRequest?>(null)
        private set

    // Find bar's own navigation channel — see SearchNavigationRequest's doc comment for why this
    // is separate from pendingAnnotationNavigation above rather than reusing it.
    var pendingSearchNavigation by mutableStateOf<SearchNavigationRequest?>(null)
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
    private var searchNavigationCounter = 0L

    init {
        // PERF-3a: refreshAppDataSizeInfo() recursively walks the whole app-data dir
        // (File.totalFileSize()) — on ioScope so a large archive-cache/notes tree can't add to
        // startup latency before first frame. appDataSizeBytes is mutableStateOf, so this is
        // snapshot-safe to publish from off the UI thread like every other ioScope write in this
        // file. The Settings-triggered path (requestClearCache -> refreshArchiveCacheInfo) stays
        // synchronous — that one is already user-initiated from an explicit click, not startup.
        ioScope.launch { refreshStorageSizeInfo() }
        if (restoreOnCreate) restoreAutosave()
        loadPersistedSourceIndex()
        loadCustomAiCommands()
        AppLogger.setFailureReporter { reason -> debugLoggingError = reason }
        debugLoggingError = AppLogger.configure(settings.debugLoggingEnabled, settings.debugLogFilePath)
        AppLogger.info("app", "openLog started (v${BuildInfo.APP_VERSION})")
    }

    // ── Helpers ─────────────────────────────────────────────────────

    fun close(forAppDataReset: Boolean = false) {
        if (!closed.compareAndSet(false, true)) return
        if (!forAppDataReset) AppLogger.info("app", "openLog shutting down")
        autosaveScheduler.cancelPending()
        aiProviderApiKeys.clear()
        aiSidebarRuntime.close()
        aiSessions.clear()
        controlServerManager.stopControlServer()
        ioJob.cancel() // also cancels every active FileTailer's Job — each is started on ioScope
        tailCoordinator.clear()
        activeLoads.clear()
        synchronized(stateLock) {
            pendingRestoredLoads.clear()
            pendingLoads = 0
            isLoading = false
        }
        AppLogger.close()
        AppLogger.setFailureReporter(null)
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

    // (SEC-1) Settings UI toggle for the control server's CORS block — see
    // ControlServerManager.setMcpAllowBrowserClients for why this needs to force a restart.
    fun setMcpAllowBrowserClients(enabled: Boolean) = controlServerManager.setMcpAllowBrowserClients(enabled)

    // Main.kt's OPENLOG_DEBUG_CONTROL/-Dopenlog.debugControl path: an ephemeral dev/CI override
    // for this run only — deliberately never touches persisted settings, so a one-off env-var
    // launch doesn't silently turn the server on for every future normal launch.
    fun startControlServerForThisSessionOnly(port: Int) =
        controlServerManager.startControlServerForThisSessionOnly(port)

    // Main.kt's shutdown path — stops the server without touching persisted settings, for the
    // same reason: closing the app must not silently flip a user's saved "enabled" preference to
    // false, or it would never auto-start again on the next launch.
    fun stopControlServerForShutdown() = controlServerManager.stopControlServer()

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

    // Settings UI toggle for opt-in diagnostic logging (see debug/AppLogger.kt). Defaults the log
    // location to DesktopStorage.debugLogFile() the first time a user enables it, so "On" always
    // works without forcing a Browse first.
    fun setDebugLoggingEnabled(enabled: Boolean) {
        val path = settings.debugLogFilePath ?: DesktopStorage.debugLogFile().absolutePath
        updateSettings { it.copy(debugLoggingEnabled = enabled, debugLogFilePath = path) }
        // Log "disabled" before configure() closes the file (otherwise the line would target an
        // already-closed writer and silently never be written); "enabled" logs after configure()
        // opens the new one, for the same reason in reverse.
        if (!enabled) AppLogger.info("app", "Debug logging disabled")
        debugLoggingError = AppLogger.configure(enabled, path)
        if (enabled && debugLoggingError == null) AppLogger.info("app", "Diagnostic logging enabled")
    }

    fun retryDebugLoggingConfiguration() {
        val path = settings.debugLogFilePath
        debugLoggingError = AppLogger.configure(settings.debugLoggingEnabled, path)
        if (debugLoggingError == null && settings.debugLoggingEnabled) {
            AppLogger.info("app", "Diagnostic logging configuration recovered")
        }
    }

    /** Opens the active diagnostic file in a normal log tab, never passing its contents to AI. */
    fun openCurrentDebugLog() {
        val path = settings.debugLogFilePath ?: return
        val file = File(path)
        if (!file.isFile) {
            debugLoggingError = "The diagnostic log has not been created yet."
            return
        }
        openFile(file)
    }

    // Same FileDialog.SAVE pattern as saveAnalysis/exportFilteredTxt — seeded with the current
    // path (or the DesktopStorage default) so re-opening the picker starts from wherever logging
    // already points.
    fun pickDebugLogFile() {
        val current = settings.debugLogFilePath?.let { File(it) } ?: DesktopStorage.debugLogFile()
        val dlg = FileDialog(null as Frame?, "Choose Debug Log File", FileDialog.SAVE).apply {
            file = current.name
            directory = current.parent
            isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        val chosen = File(dir, path).absolutePath
        updateSettings { it.copy(debugLogFilePath = chosen) }
        if (settings.debugLoggingEnabled) {
            debugLoggingError = AppLogger.configure(true, chosen)
        }
    }

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

    // (ARCH-3) Scoped alternative to polling the global `isLoading` flag: a caller (e.g.
    // OpenLogToolOperations.awaitLoad) that triggered one specific tab's load must not block on —
    // or worse, mis-attribute completion to — some unrelated concurrent load finishing first.
    // "In flight" tracks the same publish boundary the global isLoading flag does — not the
    // coroutine's full lifetime. A load's countsAsLoading flips false at markActiveLoadFinished,
    // the moment the tab is published and queryable; the coroutine then keeps running the
    // crash/stack analysis pass (as costly as the parse on multi-GB files) before its `finally`
    // removes the activeLoads entry. Keying on countsAsLoading (not mere map presence) lets a
    // scoped awaitLoad(tabId) return as soon as the tab is usable, matching the old global-flag
    // timing instead of needlessly blocking the request thread through analysis. Returns false
    // immediately for a tabId that was never registered (the "already open" fast paths).
    fun isLoadInFlight(tabId: String): Boolean = activeLoads[tabId]?.countsAsLoading?.get() == true

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

    val needsLicenseAcceptance: Boolean
        get() = settings.acceptedLicenseVersion != LICENSE_VERSION

    fun acceptLicenseAgreement() {
        updateSettings { it.copy(acceptedLicenseVersion = LICENSE_VERSION) }
    }

    // ── Update check (see update/UpdateChecker.kt) ─────────────────────
    var availableUpdate by mutableStateOf<ReleaseInfo?>(null)
    var updateCheckStatus by mutableStateOf<UpdateStatus>(UpdateStatus.Idle)
    var updateDownload by mutableStateOf<UpdateDownloadState>(UpdateDownloadState.Idle)

    // skipUpdate() only records the skipped version — it never clears availableUpdate — so this
    // stays the single source of truth for whether the popup should be showing right now, both
    // right after a check and after the app restarts with a still-current availableUpdate.
    val updateDialogVisible: Boolean
        get() = availableUpdate?.let { it.version != settings.skippedUpdateVersion } == true

    // manual distinguishes a user-initiated "Check now" (Settings) from the silent startup check
    // (Main.kt): only a manual check surfaces a failure via updateCheckStatus, so a flaky network
    // at launch never shows the user an error they didn't ask about.
    fun checkForUpdates(manual: Boolean) {
        updateCheckStatus = UpdateStatus.Checking
        ioScope.launch {
            when (val result = updateChecker.fetchLatest()) {
                is UpdateCheckResult.Available -> {
                    availableUpdate = result.release
                    // The "an update exists" fact lives in availableUpdate itself (and drives the
                    // dialog); status returns to Idle rather than a misleading "UpToDate".
                    updateCheckStatus = UpdateStatus.Idle
                    AppLogger.info("update", "Update available: v${result.release.version}")
                }
                UpdateCheckResult.UpToDate -> {
                    availableUpdate = null
                    updateCheckStatus = UpdateStatus.UpToDate(BuildInfo.APP_VERSION)
                    AppLogger.info("update", "Up to date (v${BuildInfo.APP_VERSION})")
                }
                is UpdateCheckResult.Unavailable -> {
                    updateCheckStatus = if (manual) UpdateStatus.Failed(result.reason) else UpdateStatus.Idle
                    AppLogger.error("update", "Update check failed: ${result.reason}")
                }
            }
        }
    }

    fun skipUpdate() {
        updateSettings { it.copy(skippedUpdateVersion = availableUpdate?.version) }
        availableUpdate = null
    }

    fun dismissUpdateForNow() {
        availableUpdate = null
    }

    // No OS-matching asset (assetForCurrentOs) is a no-op here: UpdateDialog shows "View on
    // GitHub" instead of "Download" in that case, so this is never called without one.
    fun downloadUpdate() {
        val release = availableUpdate ?: return
        val asset = assetForCurrentOs(release.assets) ?: return
        val chosenDir = pickDirectory("Choose Download Folder", settings.updateDownloadDir?.let(::File)) ?: return
        updateSettings { it.copy(updateDownloadDir = chosenDir.absolutePath) }
        updateDownload = UpdateDownloadState.InProgress(0f)
        AppLogger.info("update", "Update download started")
        ioScope.launch {
            runCatching {
                updateChecker.downloadAsset(asset, chosenDir) { bytesRead, total ->
                    val fraction = if (total > 0) (bytesRead.toFloat() / total.toFloat()).coerceIn(0f, 1f) else 0f
                    updateDownload = UpdateDownloadState.InProgress(fraction)
                }
            }.onSuccess { file ->
                updateDownload = UpdateDownloadState.Done(file)
                AppLogger.info("update", "Update download completed")
                revealInFileManager(file)
            }.onFailure { error ->
                if (error is CancellationException) throw error
                updateDownload = UpdateDownloadState.Failed(error.message ?: "Download failed.")
                AppLogger.error("update", "Update download failed", error)
            }
        }
    }

    internal fun pickDirectory(title: String, initialDirectory: File? = null): File? =
        directoryPicker(title, initialDirectory)

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
        val before = tab(tabId)
        tabs = tabs.map { if (it.id == tabId) fn(it) else it }
        val after = tab(tabId)
        if (before != null && after != null && searchNeedsRecompute(before, after)) {
            scheduleSearchRecompute(tabId)
        }
    }

    // In-view search matches (LogSearchState.matchIds) go stale whenever the entries a tab's
    // active search was computed over change shape — a filter edit, an expand/collapse, or new
    // (e.g. tailed) logData. Gated on `after.search.active` (not `before`) so the extremely
    // common case — a tab that never opened Find — costs one boolean check, and so opening Find
    // itself (which only touches the `search` field, not filter/expanded/logData) never schedules
    // a redundant recompute of an as-yet-empty query.
    private fun searchNeedsRecompute(before: LogTab, after: LogTab): Boolean {
        if (!after.search.active) return false
        return before.filter != after.filter || before.expanded != after.expanded || before.logData !== after.logData
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
                id = "${newId("mr")}_${pattern.hashCode()}",
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
                    newId("hl"),
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

    /**
     * Context-menu highlights begin at the same palette cursor as the Highlighters add form,
     * but avoid colors already in use in this tab until the complete palette has been exhausted.
     */
    fun nextAvailableHighlighterColor(tabId: String): Color {
        val used = tab(tabId)?.filter?.highlighters?.map { it.color }?.toSet().orEmpty()
        val start = HL_COLORS.indexOf(newHlColor).takeIf { it >= 0 } ?: 0
        for (offset in HL_COLORS.indices) {
            val candidate = HL_COLORS[(start + offset) % HL_COLORS.size]
            if (candidate !in used) return candidate
        }
        return HL_COLORS[start]
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
                    newId("seq"),
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

    fun saveFilter(tabId: String, name: String, folderId: String? = sfFolderId) {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return
        val existing = savedFilters.find { it.name.trim().equals(cleanName, ignoreCase = true) }
        if (existing != null) {
            pendingDuplicateFilterSave = PendingDuplicateFilterSave(tabId, existing.id, existing.name, cleanName)
            return
        }
        val validFolderId = folderId?.takeIf { id -> savedFilterFolders.any { it.id == id } }
        val sf = snapshotFilter(tabId, "sf${System.nanoTime()}_${savedFilters.size}", cleanName)
            ?.copy(folderId = validFolderId) ?: return
        savedFilters = savedFilters + sf
        activeSavedFilterIds = activeSavedFilterIds + (tabId to sf.id)
        clearDraftForTab(tabId)
        transientRegexSearchTabIds = transientRegexSearchTabIds - tabId
        sfDialog = false; sfName = ""; sfFolderId = null
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
        sfFolderId = null
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
        sfFolderId = null
        val currentName = pending.currentFilterId?.let { id -> savedFilters.find { it.id == id }?.name }
        sfName = currentName?.let { "$it copy" } ?: ""
    }

    private fun snapshotFilter(tabId: String, id: String, name: String): SavedFilter? {
        val t = tab(tabId) ?: return null
        val f = t.filter
        val libraryMetadata = savedFilters.firstOrNull { it.id == id }
        return SavedFilter(
            id, name, f.levels, f.activeTags, f.kwText, f.kwRegex,
            f.mode, f.excludeTags, f.excludeKw, f.excludeKwRegex, f.highlighters, f.seqOn,
            f.kwInTag, f.kwInTagRegex, f.pkgPrefixes, f.pidTidFilter, f.sequences, f.messageRules,
            f.excludePkgPrefixes, f.kwHighlightEnabled, f.kwHighlightColor,
            folderId = libraryMetadata?.folderId,
            favorite = libraryMetadata?.favorite ?: false,
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

    fun createSavedFilterFolder(name: String) {
        val clean = name.trim()
        if (clean.isBlank() || savedFilterFolders.any { it.name.equals(clean, ignoreCase = true) }) return
        savedFilterFolders = savedFilterFolders + SavedFilterFolder("sff${System.nanoTime()}", clean)
        autosaveNow()
    }

    fun renameSavedFilterFolder(id: String, name: String) {
        val clean = name.trim()
        if (clean.isBlank() || savedFilterFolders.any { it.id != id && it.name.equals(clean, ignoreCase = true) }) return
        savedFilterFolders = savedFilterFolders.map { if (it.id == id) it.copy(name = clean) else it }
        autosaveNow()
    }

    fun requestDeleteSavedFilterFolder(id: String) {
        if (savedFilterFolders.any { it.id == id }) pendingDeleteSavedFilterFolderId = id
    }

    fun cancelDeleteSavedFilterFolder() { pendingDeleteSavedFilterFolderId = null }

    fun confirmDeleteSavedFilterFolder() {
        val id = pendingDeleteSavedFilterFolderId ?: return
        savedFilters = savedFilters.map { if (it.folderId == id) it.copy(folderId = null) else it }
        savedFilterFolders = savedFilterFolders.filterNot { it.id == id }
        pendingDeleteSavedFilterFolderId = null
        writeFilterBackup()
    }

    fun toggleSavedFilterFavorite(id: String) {
        savedFilters = savedFilters.map { if (it.id == id) it.copy(favorite = !it.favorite) else it }
        writeFilterBackup()
    }

    fun moveSavedFilter(id: String, folderId: String?) {
        val validFolder = folderId?.takeIf { target -> savedFilterFolders.any { it.id == target } }
        if (savedFilters.none { it.id == id }) return
        savedFilters = savedFilters.map { if (it.id == id) it.copy(folderId = validFolder) else it }
        writeFilterBackup()
    }

    fun reorderSavedFilterWithinFolder(id: String, toIndex: Int) {
        val filter = savedFilters.firstOrNull { it.id == id } ?: return
        val siblings = savedFilters.filter { it.folderId == filter.folderId }
        val fromIndex = siblings.indexOfFirst { it.id == id }
        if (fromIndex < 0) return
        val reordered = siblings.toMutableList().apply {
            val moved = removeAt(fromIndex)
            add(toIndex.coerceIn(0, size), moved)
        }
        val iterator = reordered.iterator()
        savedFilters = savedFilters.map { if (it.folderId == filter.folderId) iterator.next() else it }
        writeFilterBackup()
    }

    fun reorderSavedFilterFolder(id: String, toIndex: Int) {
        val fromIndex = savedFilterFolders.indexOfFirst { it.id == id }
        if (fromIndex < 0) return
        val reordered = savedFilterFolders.toMutableList().apply {
            val folder = removeAt(fromIndex)
            add(toIndex.coerceIn(0, size), folder)
        }
        savedFilterFolders = reordered
        autosaveNow()
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
        return exportFiltersList(filters, savedFilterFolders, includeEmptyFolders = selectedIds == null)
    }

    fun importFilters(json: String) {
        val library = decodeFilterLibrary(json).getOrElse { return }
        if (library.filters.isEmpty()) return
        val prepared = prepareImportedLibrary(library)
        savedFilterFolders = savedFilterFolders + prepared.folders
        savedFilters = savedFilters + prepared.filters
    }

    fun beginImportFilters(json: String, sourceName: String? = null) {
        val library = decodeFilterLibrary(json).getOrElse {
            importError = "Could not read filter file."
            pendingImportReview = null
            return
        }
        val prepared = prepareImportedLibrary(library)
        beginImportFilterList(prepared.filters, prepared.folders, sourceName)
    }

    private fun beginImportFilterList(
        imported: List<SavedFilter>,
        stagedFolders: List<SavedFilterFolder> = emptyList(),
        sourceName: String? = null,
    ) {
        if (imported.isEmpty()) {
            importError = "No saved filters found."
            pendingImportReview = null
            return
        }
        val rows = buildImportRows(savedFilters, imported)
        pendingImportReview = PendingImportReview(rows, stagedFolders, sourceName)
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

    /** Bulk-checks/unchecks rows: checking restores each row's natural default action (ADD for a
     * brand-new filter, RENAME for a name conflict); unchecking sets SKIP. Backs both the per-row
     * and per-folder/dialog-level "select all" checkboxes in the import review dialog. */
    fun setImportRowsChecked(rowIds: Set<String>, checked: Boolean) {
        val review = pendingImportReview ?: return
        pendingImportReview = review.copy(rows = review.rows.map { row ->
            if (row.rowId !in rowIds) row
            else if (checked) row.withImportAction(savedFilters, if (row.targetId == null) ImportFilterAction.ADD else ImportFilterAction.RENAME)
            else row.withImportAction(savedFilters, ImportFilterAction.SKIP)
        })
    }

    fun confirmImportFilters() {
        val review = pendingImportReview ?: return
        var next = savedFilters
        var changed = false
        val survivingFolderIds = mutableSetOf<String>()
        review.rows.forEach { row ->
            when (row.action) {
                ImportFilterAction.ADD, ImportFilterAction.RENAME -> {
                    next = next + row.incoming.copy(id = freshSavedFilterId(savedFilters), name = uniqueNameAgainst(row.resolvedName, next))
                    changed = true
                    row.incoming.folderId?.let(survivingFolderIds::add)
                }

                ImportFilterAction.REPLACE -> {
                    val targetId = row.targetId ?: return@forEach
                    next = next.map { existing ->
                        if (existing.id == targetId) row.incoming.copy(id = existing.id, name = existing.name) else existing
                    }
                    changed = true
                    row.incoming.folderId?.let(survivingFolderIds::add)
                }

                ImportFilterAction.SKIP -> Unit
            }
        }
        savedFilters = next
        val foldersToAdd = review.stagedFolders.filter { it.id in survivingFolderIds }
        if (foldersToAdd.isNotEmpty()) {
            savedFilterFolders = savedFilterFolders + foldersToAdd
        }
        pendingImportReview = null
        if (changed) writeFilterBackup()
    }

    /** Maps imported folder ids onto this library by folder name, adding non-conflicting folders. */
    private fun prepareImportedLibrary(library: DecodedFilterLibrary): DecodedFilterLibrary {
        val mappedIds = mutableMapOf<String, String>()
        val additions = mutableListOf<SavedFilterFolder>()
        library.folders.forEach { imported ->
            val local = (savedFilterFolders + additions).firstOrNull { it.name.equals(imported.name, ignoreCase = true) }
            val target = local ?: run {
                val id = imported.id.takeIf { candidate -> savedFilterFolders.none { it.id == candidate } && additions.none { it.id == candidate } }
                    ?: "sff${System.nanoTime()}_${additions.size}"
                SavedFilterFolder(id, imported.name).also(additions::add)
            }
            mappedIds[imported.id] = target.id
        }
        return DecodedFilterLibrary(
            filters = library.filters.map { it.copy(folderId = it.folderId?.let(mappedIds::get)) },
            folders = additions,
        )
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
                val last = t.selected.lastOrNull { visIds.indexOfId(it) >= 0 } ?: t.selected.maxOrNull()
                if (last == null) {
                    setOf(id)
                } else {
                    val a = visIds.indexOfId(last)
                    val b = visIds.indexOfId(id)
                    if (a >= 0 && b >= 0) (minOf(a, b)..maxOf(a, b)).map { visIds[it] }.toSet() else t.selected + id
                }
            }

            else -> if (t.selected == setOf(id)) emptySet() else setOf(id)
        }
        t.copy(selected = n)
    }

    fun selRowRange(tabId: String, fromId: Int, toId: Int) = upTab(tabId) { t ->
        // Prefer the summary LogViewer already computed (same rationale as selRow's range case
        // above) instead of re-filtering the full logData on every shift-click.
        val regexContext = RegexEvaluationContext()
        val ids = visibleItemsByTab[tabId]?.allIds
            ?: t.logData.filter { passesFilter(it, t.filter, regexContext) }
                .map { it.id }
                .ifEmpty { t.logData.map { it.id } }
                .toIntArray()
        val a = ids.indexOfId(fromId)
        val b = ids.indexOfId(toId)
        if (a < 0 || b < 0) return@upTab t
        t.copy(selected = (minOf(a, b)..maxOf(a, b)).map { ids[it] }.toSet())
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

    fun consumeSearchNavigation(id: Long) {
        if (pendingSearchNavigation?.id == id) pendingSearchNavigation = null
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
        val regexContext = RegexEvaluationContext()
        var expanded = t.expanded
        while (true) {
            fun idsFrom(applyFilter: Boolean): Set<String> =
                computeItems(t.copy(expanded = expanded), applyFilter, regexContext)
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

    // Per-tab Δt-column toggle (LogViewer.kt's toolbar button, beside Export) — see LogTab.showTimeDelta's
    // doc comment for why this lives on the tab rather than in AppSettings.
    fun toggleTimeDelta(tabId: String) = upTab(tabId) { it.copy(showTimeDelta = !it.showTimeDelta) }

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

    fun appendPrefix(tabId: String, text: String) = annotationManager.appendPrefix(tabId, text)

    fun appendSuffix(tabId: String, text: String) = annotationManager.appendSuffix(tabId, text)

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

    // ── In-view search ("Find" bar, ui/SearchBar.kt) ──────────────────
    // Non-destructive alternative to the filter's own keyword/regex search: highlights matches in
    // place instead of hiding non-matching rows. See onFocusFilterSearch in App.kt for the
    // Settings.ctrlFTarget == FIND_BAR branch that routes Ctrl/Cmd+F here instead of to a filter
    // field.

    // Reopening an already-open bar (a repeat Ctrl/Cmd+F) deliberately keeps the existing
    // query/matches — only `active` and `focusNonce` change — so ui/SearchBar.kt's
    // LaunchedEffect(focusNonce) can refocus + select-all without losing what was already found.
    fun openSearch(tabId: String) = upTab(tabId) { t ->
        t.copy(search = t.search.copy(active = true, focusNonce = t.search.focusNonce + 1))
    }

    // Deliberately keeps query/matchIds (only flips `active` off) rather than resetting to
    // LogSearchState.EMPTY — reopening the bar later in the same session restores exactly where
    // the user left off instead of forcing them to retype. Session-only either way: LogTab.search
    // is never persisted (see AutosaveCodec.persistedSnapshot), so a relaunch always starts blank.
    fun closeSearch(tabId: String) {
        searchJobs.remove(tabId)?.cancel()
        upTab(tabId) { it.copy(search = it.search.copy(active = false)) }
    }

    fun setSearchQuery(tabId: String, query: String) {
        upTab(tabId) { t -> t.copy(search = t.search.copy(query = query, active = true)) }
        scheduleSearchRecompute(tabId)
    }

    fun toggleSearchCase(tabId: String) {
        upTab(tabId) { t -> t.copy(search = t.search.copy(caseSensitive = !t.search.caseSensitive)) }
        scheduleSearchRecompute(tabId)
    }

    fun searchNext(tabId: String) = moveSearchMatch(tabId, +1)

    fun searchPrev(tabId: String) = moveSearchMatch(tabId, -1)

    private fun moveSearchMatch(tabId: String, delta: Int) {
        val t = tab(tabId) ?: return
        val matches = t.search.matchIds
        if (matches.isEmpty()) return
        val n = matches.size
        // Plain modulo can return negative for delta=-1 at index 0 in Kotlin/Java (remainder, not
        // Python-style floor-mod) — the extra `+ n) % n` wraps it back into [0, n).
        val newIdx = ((t.search.currentIdx + delta) % n + n) % n
        upTab(tabId) { it.copy(search = it.search.copy(currentIdx = newIdx)) }
        // Deliberately NOT requestScrollAnchor/pendingAnnotationNavigation: that path always
        // centers via the two-phase scroll-to-top-then-recenter centerOnItem, which reads as a
        // jarring flash for a frequent, often-repeated action like Enter/Next/Prev — including
        // when the match is already fully on screen (e.g. a single match, currentIdx wrapping
        // back to itself). requestSearchScrollAnchor still selects the row and auto-expands
        // whatever collapsed group owns it, but scrolls only the minimum needed (or not at all)
        // — see SearchNavigationRequest's doc comment and LogViewer.kt's handling LaunchedEffect.
        requestSearchScrollAnchor(tabId, matches[newIdx])
    }

    private fun requestSearchScrollAnchor(tabId: String, entryId: Int) {
        setSelectedRows(tabId, listOf(entryId))
        searchNavigationCounter += 1
        pendingSearchNavigation = SearchNavigationRequest(searchNavigationCounter, tabId, entryId)
    }

    // Compare mode's right panel never renders its own stored `.filter` — CompareView.kt's
    // effectiveRightTab mirrors the left (activeTabId) tab's filter when compareFilterRight is on,
    // or shows everything unfiltered (Filter()) when it's off. Search must match against that same
    // *displayed* filter or its highlights/jumps would target rows the right panel doesn't actually
    // show. Common-case only: mirrors CompareView.kt's own leftTab/rightTab resolution
    // (activeTabId/compareTabId) directly, without also replicating its same-tab-id collision
    // fallback (CompareView reassigns rightTab to some other open tab if compareTabId ever collided
    // with activeTabId) — that fallback only matters in the rare case a caller sets compareTabId
    // equal to activeTabId without going through updateCompareMode (which already prevents it), so
    // duplicating it here was left as a follow-up rather than done speculatively.
    private fun effectiveSearchFilter(tabId: String, stored: LogTab): Filter {
        if (!compareMode) return stored.filter
        return when (tabId) {
            compareTabId -> if (compareFilterRight) tab(activeTabId)?.filter ?: stored.filter else Filter()
            else -> stored.filter
        }
    }

    // CompareView.kt calls this when the right panel's *effective* displayed filter changes —
    // compareFilterRight toggling, the left tab's filter changing while mirrored, or which tab is
    // on the right changing. None of those mutate the right tab's own stored `.filter`, so upTab's
    // searchNeedsRecompute hook (which only watches a tab's own fields) can't see them; this is the
    // explicit trigger for that case. A cheap no-op if that tab's search isn't active.
    fun notifyEffectiveFilterChanged(tabId: String) = scheduleSearchRecompute(tabId)

    // Cancel-and-relaunch on ioScope, same shape as TailCoordinator's tailAnalysisJobs debounce —
    // every call (a keystroke, a case-sensitivity toggle, the upTab hook noticing the tab's
    // filter/expanded/logData changed, or notifyEffectiveFilterChanged above) supersedes whatever
    // recompute was already in flight.
    //
    // Deliberately searches over a *fully expanded* copy of the tab (visibleExpandableGroupIds),
    // not tab.expanded as actually rendered — a match hidden inside a currently-collapsed group
    // must still be found so Enter/Next can jump to it (which is what then actually expands that
    // one group, via requestScrollAnchor above). The real tab.expanded is never mutated here — nor
    // is the real stored `.filter` (effectiveSearchFilter only affects this local computation).
    private fun scheduleSearchRecompute(tabId: String) {
        searchJobs[tabId]?.cancel()
        searchJobs[tabId] = ioScope.launch {
            delay(SEARCH_RECOMPUTE_DEBOUNCE_MS)
            val stored = tab(tabId) ?: return@launch
            if (!stored.search.active) return@launch
            val t = stored.copy(filter = effectiveSearchFilter(tabId, stored))
            val regexContext = RegexEvaluationContext()
            val fullyExpanded = visibleExpandableGroupIds(t)
            ensureActive()
            val searchItems = computeItems(t.copy(expanded = fullyExpanded), applyFilter = true, regexContext)
            ensureActive()
            val result = computeSearchMatches(searchItems, stored.search.query, stored.search.caseSensitive, regexContext)
            ensureActive()
            applySearchResult(tabId, result)
        }
    }

    private fun applySearchResult(tabId: String, result: SearchComputeResult) {
        upTab(tabId) { cur ->
            val old = cur.search
            if (!old.active) return@upTab cur // closed while this recompute was in flight
            // Sticky current match (plan requirement): if the entry the user was parked on is still
            // among the new matches, stay on it (by id, not index — recomputation can shuffle
            // indices even when the set of matches barely changes) instead of snapping back to 0.
            val stickyId = old.matchIds.getOrNull(old.currentIdx)
            val stickyIdx = stickyId?.let { result.matchIds.indexOfId(it) } ?: -1
            val newIdx = when {
                result.matchIds.isEmpty() -> 0
                stickyIdx >= 0 -> stickyIdx
                else -> old.currentIdx.coerceIn(0, result.matchIds.size - 1)
            }
            cur.copy(
                search = old.copy(
                    matchIds = result.matchIds,
                    currentIdx = newIdx,
                    invalidPattern = result.invalidPattern,
                    timedOut = result.timedOut,
                ),
            )
        }
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

    fun addHlFromCtx(color: Color? = null) {
        val c = ctx ?: return
        val entry = tab(c.tabId)?.rmap?.get(c.entryId) ?: return
        // Unlike the message-only actions below, highlighter matching runs against the full
        // rendered line (ts/pid/tid/level/tag/msg — see fullLineText/buildFullLineAnnotation in
        // LogViewer.kt), so the raw selection is used verbatim rather than run through
        // extractMsgText — that helper strips a "tag: " prefix on the assumption the pattern only
        // ever matches entry.msg, which would silently change what a cross-boundary selection
        // (e.g. spanning tag + message) actually highlights.
        val text = c.selText.trim().ifBlank { entry.msg }
        addHl(c.tabId, text, false, color ?: nextAvailableHighlighterColor(c.tabId)); ctx = null
    }

    fun addHlTagFromCtx(color: Color? = null) {
        val c = ctx ?: return
        val tag = tab(c.tabId)?.rmap?.get(c.entryId)?.tag ?: return
        addHl(c.tabId, tag, false, color ?: nextAvailableHighlighterColor(c.tabId)); ctx = null
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
            val id = "${newId("mc")}_${anchorId}_${direction.name}"
            // A repeat TO_START/TO_END rewrites the tab's existing block of that same direction
            // (manualCollapseAvailability let it through despite the overlap for this reason)
            // rather than stacking a second one — carry its color forward so nudging the boundary
            // doesn't reset a color the user picked.
            val replaced = if (direction == ManualCollapseDirection.RANGE) {
                emptyList()
            } else {
                t.manualBlocks.filter { it.direction == direction }
            }
            val replacedIds = replaced.map { it.id }.toSet()
            val newBlock = replaced.firstOrNull()?.color?.let { ManualCollapseBlock(id, anchorId, direction, color = it, endId = endId) }
                ?: ManualCollapseBlock(id, anchorId, direction, endId = endId)
            t.copy(
                manualBlocks = t.manualBlocks.filterNot { it.id in replacedIds } + newBlock,
                expanded = t.expanded - replacedIds,
            )
        }
        return true
    }

    // Public entry for the MCP/AI control surface: fold an arbitrary inclusive line-id range into
    // one manual block, regardless of argument order. Mirrors collapseSelectedLinesFromCtx but
    // takes explicit ids instead of relying on the right-click ctx / current selection.
    fun collapseRange(tabId: String, startId: Int, endId: Int): Boolean =
        addManualCollapse(tabId, minOf(startId, endId), ManualCollapseDirection.RANGE, endId = maxOf(startId, endId))

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
                AppLogger.info("open", "Opened ${file.name} (${logData.size} entries)")
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
        AppLogger.error("open", "$title: $message (path=$path)")
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
        val candidates = runCatching { listArchiveLogCandidates(file) }.getOrElse { error ->
            AppLogger.error("archive", "Archive scan failed", error)
            showOpenError("Could not open archive", path, "The archive could not be scanned.")
            return
        }
        AppLogger.info("archive", "Archive scan found ${candidates.size} candidates")
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

    // Returns the tabId allocated for each selected entry that actually started loading (in the
    // same order as `selected`), so a caller with a concrete tabId to scope awaitLoad-style
    // polling to (ARCH-3: OpenLogToolOperations) doesn't have to re-derive it from sourcePath
    // matching afterward. Empty when every candidate got deferred into a split prompt instead.
    fun openZipEntries(zipFile: File, selected: List<ZipLogCandidate>): List<String> {
        val (oversized, normal) = selected.partition { requiresSplitPrompt(it.sizeBytes) }
        if (oversized.isNotEmpty()) {
            pendingSplitPrompt = PendingSplitPrompt(
                sources = oversized.map { SplitSource.ArchiveEntry(zipFile, it) },
                deferredArchiveEntries = normal.map { DeferredArchiveEntry(zipFile, it) },
            )
            pendingZipPicker = null
            return emptyList()
        }
        val tabIds = selected.mapNotNull { openZipEntry(zipFile, it, bypassSplitPrompt = false) }
        pendingZipPicker = null
        return tabIds
    }

    fun openZipEntryAsIs(zipFile: File, candidate: ZipLogCandidate): String? =
        openZipEntry(zipFile, candidate, bypassSplitPrompt = true)

    fun cancelZipPicker() {
        pendingZipPicker = null
    }

    // Jar-URL-style "!" separator for the source path — for display/dedup only (a zip-extracted
    // tab can't be re-opened from this path directly), matching openFile()'s sourcePath dedup.
    // Returns the tabId that now owns (or will own, once loading finishes) this entry — the
    // existing tab's id on the dedup fast path, the newly allocated id once a load is launched, or
    // null when nothing was launched (deferred into a split prompt instead). See openZipEntries'
    // doc comment for why callers want this rather than re-deriving it from sourcePath.
    private fun openZipEntry(zipFile: File, candidate: ZipLogCandidate, bypassSplitPrompt: Boolean): String? {
        val path = "${zipFile.absolutePath}!${candidate.entryPath}"
        val existing = tabs.find { it.sourcePath == path }
        if (existing != null) {
            activateTab(existing.id); return existing.id
        }
        if (!bypassSplitPrompt && requiresSplitPrompt(candidate.sizeBytes)) {
            pendingSplitPrompt = PendingSplitPrompt(listOf(SplitSource.ArchiveEntry(zipFile, candidate)))
            return null
        }
        val n = tabCounter.getAndIncrement()
        val tabId = "t$n"
        val largeFile = candidate.sizeBytes >= LARGE_FILE_MODE_BYTES
        beginLoading()
        val job = ioScope.launch(start = CoroutineStart.LAZY) {
            var published = false
            try {
                val logData = runCatching { extractCandidate(zipFile, candidate, archiveEntryByteBudget) }.getOrElse { error ->
                    AppLogger.error("archive", "Archive entry extraction failed", error)
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
                        archiveCandidate = candidate,
                    )
                synchronized(stateLock) {
                    ensureActive()
                    tabs = tabs + t
                    activeTabId = t.id
                }
                AppLogger.info("open", "Opened ${candidate.displayName} from archive (${logData.size} entries)")
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
            file.writeText(t.annotations.withDetectedAppVersion(t.logData).annotationsToken())
        }.isSuccess
    }

    // Best-effort app/build version fill-in at save time (com.openlog.cases' "similar past
    // issues" retrieval uses it only to down-weight stale matches — see
    // utils/AppVersionHeuristic.kt). Never overwrites a value already set (e.g. by the user or by
    // the AI via set_case_metadata); a scan that finds nothing simply leaves it "" as before.
    private fun Annotations.withDetectedAppVersion(logData: List<LogEntry>): Annotations =
        if (appVersion.isNotBlank()) this else copy(appVersion = extractAppVersionHeuristic(logData))

    fun loadAnnotationsFrom(tabId: String, file: File): Boolean {
        val annotations = runCatching { file.readText().annotationsFromToken() }.getOrNull() ?: return false
        upAnn(tabId) { t -> t.copy(annotations = annotations) }
        return tab(tabId)?.annotations == annotations
    }

    fun saveAnalysis(tabId: String) {
        val t = tab(tabId) ?: return
        val dlg = FileDialog(null as Frame?, "Save Analysis", FileDialog.SAVE).apply {
            file = analysisNoteMarkdownName(t.filename, t.sourcePath)
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
                File(saved.parent, saved.nameWithoutExtension + ".ann")
                    .writeText(t.annotations.withDetectedAppVersion(t.logData).annotationsToken(t.sourcePath))
                rememberRecentNote(saved)
            }.fold(
                onSuccess = { AppLogger.info("export", "Saved analysis to ${saved.absolutePath}") },
                onFailure = { e -> AppLogger.error("export", "Failed to save analysis to ${saved.absolutePath}", e) },
            )
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
        ioScope.launch {
            runCatching { exportFilteredToFile(t, saved, csv = false) }.fold(
                onSuccess = { AppLogger.info("export", "Exported filtered log to ${saved.absolutePath}") },
                onFailure = { e -> AppLogger.error("export", "Failed to export filtered log to ${saved.absolutePath}", e) },
            )
        }
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
        ioScope.launch {
            runCatching { exportFilteredToFile(t, saved, csv = true) }.fold(
                onSuccess = { AppLogger.info("export", "Exported filtered log to ${saved.absolutePath}") },
                onFailure = { e -> AppLogger.error("export", "Failed to export filtered log to ${saved.absolutePath}", e) },
            )
        }
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
            val block = AnnBlock.Note(newId("n"), text)
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

    // internal (not private): OpenLogToolOperations' CaseSearch instance (com.openlog.cases)
    // reuses this exact directory set for search_similar_cases/reindex_cases, so "similar past
    // issues" retrieval always looks in the same places notes actually get saved/loaded from —
    // including in tests, where notesDir is injected away from the real ~/.openlog2-equivalent.
    internal fun noteLookupDirs(): List<File> {
        return listOfNotNull(
            userNotesDir(),
            notesDir,
            DesktopStorage.legacyNotesDir(),
        ).distinctBy { it.absolutePath }
    }

    // Includes the plain-filename name alongside the (possibly archive-qualified) one so notes
    // exported before the archive-qualified naming — plain "logcat_analysis.md" — still resolve
    // for their tab.
    private fun noteNamesForFilename(filename: String, sourcePath: String? = null): List<String> {
        return listOf(
            analysisNoteMarkdownName(filename, sourcePath),
            analysisNoteMarkdownName(filename),
        ).distinct()
    }

    // Two tabs can share a bare filename while being entirely different files (different
    // folders, or one from inside a zip) — matching by name alone would surface one file's notes
    // as a "recent note" suggestion for the other. Excludes only on POSITIVE evidence of a
    // mismatch (both a recorded fingerprint and a known sourcePath for this tab, and they
    // differ); a note saved before this fingerprinting existed (no .src sidecar) or a tab with no
    // sourcePath (e.g. a merged tab) still matches by name as before — see resolveNoteTarget.
    fun recentNotesForTab(tab: LogTab): List<String> {
        val relatedNames = noteNamesForFilename(tab.filename, tab.sourcePath).toSet()
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
                // Sidecar stores full block state for restoration, plus the sourcePath
                // fingerprint (5th token field) used to disambiguate same-named notes.
                File(targetDir, "${mdFile.nameWithoutExtension}.ann")
                    .writeText(tab.annotations.withDetectedAppVersion(tab.logData).annotationsToken(tab.sourcePath))
                legacySourceFingerprintFile(mdFile).delete()
                rememberRecentNote(mdFile)
            }
        }
    }

    // Fingerprint recording which sourcePath a saved note "<name>_analysis.md" actually belongs
    // to — lets resolveNoteTarget/recentNotesForTab tell apart two different files that happen to
    // share a display name, without changing the plain filename in the common (non-colliding)
    // case. Lives in the .ann sidecar's 5th token field (see Annotations.annotationsToken).
    //
    // Legacy fingerprint sidecar from before the sourcePath was folded into the .ann token. No
    // longer written, but still read as a fallback so notes exported before this change keep
    // disambiguating correctly.
    private fun legacySourceFingerprintFile(mdFile: File): File = File(mdFile.parent, "${mdFile.name}.src")

    private fun readSourceFingerprint(mdFile: File): String? {
        val annFile = File(mdFile.parent, "${mdFile.nameWithoutExtension}.ann")
        val fromAnn = annFile.takeIf { it.exists() }
            ?.let { file ->
                runCatching {
                    file.readText()
                        .tokenFields()
                        .getOrNull(4)
                        ?.takeIf { it.isNotBlank() }
                }.getOrNull()
            }
        if (fromAnn != null) return fromAnn
        return legacySourceFingerprintFile(mdFile).takeIf { it.exists() }
            ?.let { runCatching { it.readText().trim() }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
    }

    // Picks the .md target for this tab's auto-exported note in targetDir. Reuses the plain
    // "<name>_analysis.md" whenever there's no evidence of a different owner (no fingerprint
    // recorded yet — a legacy or first-time save — or it already matches this tab's sourcePath,
    // or this tab has no sourcePath to compare); otherwise walks "_2", "_3", ... until it finds a
    // slot with no conflicting fingerprint, so a genuine collision no longer silently overwrites
    // an unrelated file's saved notes.
    private fun resolveNoteTarget(targetDir: File, filename: String, sourcePath: String?): File {
        val baseName = analysisNoteMarkdownName(filename, sourcePath)
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
        val chosen = pickDirectory("Choose Save Folder", settings.defaultSaveDir?.let(::File)) ?: return
        updateSettings { it.copy(defaultSaveDir = chosen.absolutePath) }
    }

    fun pickSourceFolder() {
        val chosen = pickDirectory("Choose Source Folder") ?: return
        updateSettings { it.copy(sourceFolders = (it.sourceFolders + chosen.absolutePath).distinct()) }
    }

    fun removeSourceFolder(path: String) {
        val rootAbs = File(path).absolutePath
        updateSettings {
            it.copy(
                sourceFolders = it.sourceFolders.filterNot { folder -> File(folder).absolutePath == rootAbs },
                sourceFolderInfo = it.sourceFolderInfo.filterKeys { folder -> File(folder).absolutePath != rootAbs },
                sourceFolderConfigurationIds = it.sourceFolderConfigurationIds.filterKeys { folder -> File(folder).absolutePath != rootAbs },
            )
        }
        pruneSourceIndexForRegisteredFolders()
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

    /** Removes index records that no longer belong to a registered source root. */
    private fun pruneSourceIndexForRegisteredFolders() {
        val pruned = synchronized(stateLock) {
            val current = sourceIndex ?: return
            val registeredRoots = settings.sourceFolders.map { File(it).absolutePath }.toSet()
            SourceIndex(
                version = SOURCE_INDEX_VERSION,
                roots = settings.sourceFolders,
                sites = current.sites.filter { sourceRootForPath(it.filePath) != null },
                fileMeta = current.fileMeta.filterKeys { sourceRootForPath(it) != null },
                builtAt = current.builtAt,
                rootBuiltAt = current.rootBuiltAt.filterKeys { it in registeredRoots },
                rootConfigFingerprints = current.rootConfigFingerprints.filterKeys { it in registeredRoots },
            )
        }
        runCatching { SourceIndexStore.save(pruned, sourceIndexFile) }
            .onFailure { error -> AppLogger.error("source-index", "Failed to save pruned source index", error) }
        publishSourceIndex(pruned)
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
            if (rootAbs !in settings.sourceFolders.map { File(it).absolutePath } || rootAbs in indexingFolders) {
                false
            } else {
                indexingFolders = indexingFolders + rootAbs
                true
            }
        }
        if (!started) return
        AppLogger.info("source-index", "Source reindex started")
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
                }.onFailure { error -> AppLogger.error("source-index", "Source reindex failed", error) }.getOrNull()
                if (partial != null) {
                    val mergedAt = System.currentTimeMillis()
                    val merged = synchronized(stateLock) {
                        val registeredRoots = settings.sourceFolders.map { File(it).absolutePath }.toSet()
                        if (rootAbs !in registeredRoots) return@synchronized null
                        val current = sourceIndex
                        // Preserve sites from another still-registered root, including a nested
                        // root with its own configuration, while dropping stale removed folders.
                        val keptSites = current?.sites.orEmpty().filter {
                            sourceRootForPath(it.filePath)?.let { owner -> owner != rootAbs } == true
                        }
                        val keptMeta = current?.fileMeta.orEmpty().filterKeys {
                            sourceRootForPath(it)?.let { owner -> owner != rootAbs } == true
                        }
                        val rebuiltSites = partial.sites.filter { sourceRootForPath(it.filePath) == rootAbs }
                        val rebuiltMeta = partial.fileMeta.filterKeys { sourceRootForPath(it) == rootAbs }
                        SourceIndex(
                            version = SOURCE_INDEX_VERSION,
                            roots = settings.sourceFolders,
                            sites = keptSites + rebuiltSites,
                            fileMeta = keptMeta + rebuiltMeta,
                            builtAt = mergedAt,
                            rootBuiltAt = current?.rootBuiltAt.orEmpty().filterKeys { it in registeredRoots } + (rootAbs to mergedAt),
                            rootConfigFingerprints = current?.rootConfigFingerprints.orEmpty().filterKeys { it in registeredRoots } +
                                (rootAbs to sourceConfigurationFingerprint(
                                    sourceConfigurationsForFolder(rootAbs),
                                    settings.sourceAutoDiscoveryEnabled,
                                )),
                        )
                    }
                    if (merged != null) {
                        runCatching { SourceIndexStore.save(merged, sourceIndexFile) }
                            .onFailure { error -> AppLogger.error("source-index", "Failed to save source index", error) }
                        publishSourceIndex(merged)
                        AppLogger.info("source-index", "Source reindex completed (${partial.sites.size} call sites)")
                    }
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

    // Opens filePath at line in an editor, dispatched on settings.editorChoice:
    //  - "auto" / "" (default, also the migration default for a never-configured install): tries
    //    every AUTO_EDITOR_COMMANDS entry (every catalog candidate) in order.
    //  - "custom": uses settings.editorCommand verbatim, exactly as the old single-field behavior.
    //  - anything else is an EditorPreset.id: resolved fresh via detectedTemplate (not the cached
    //    detectedEditors snapshot, so an editor installed/uninstalled since the last Settings scan
    //    is still honoured correctly) rather than a stored command, so a Toolbox-relocated or
    //    reinstalled editor keeps resolving without the user re-typing anything.
    // There is deliberately no Desktop.open() fallback in any branch: that can open the correct file
    // but cannot honour the requested source line, which is worse than reporting a missing editor
    // launcher. Runs on ioScope so editor process startup never blocks the UI.
    fun openInEditor(filePath: String, line: Int) {
        val file = File(filePath)
        ioScope.launch {
            when (val choice = settings.editorChoice) {
                "auto", "" -> {
                    if (AUTO_EDITOR_COMMANDS.any { launchEditor(it, file, line) }) return@launch
                    showOpenError(
                        title = "Could not open code location",
                        path = filePath,
                        message = "No supported editor launcher was found. Pick an installed editor or a custom " +
                            "command in Settings.",
                    )
                }
                "custom" -> {
                    val custom = settings.editorCommand
                    if (custom.isNotBlank() && launchEditor(custom, file, line)) return@launch
                    showOpenError(
                        title = "Could not open code location",
                        path = filePath,
                        message = "The configured Open command could not be launched. The file was not opened without its line " +
                            "location; check the executable path and the {file} and {line} placeholders in Settings.",
                    )
                }
                else -> {
                    val preset = EDITOR_CATALOG.find { it.id == choice }
                    val template = preset?.let { detectedTemplate(it) }
                    if (template != null && launchEditor(template, file, line)) return@launch
                    showOpenError(
                        title = "Could not open code location",
                        path = filePath,
                        message = if (preset != null) {
                            "${preset.displayName} is no longer detected — pick another app or a custom command in Settings."
                        } else {
                            "The selected editor is not recognised — pick another app or a custom command in Settings."
                        },
                    )
                }
            }
        }
    }

    fun exportFiltersToFile(selectedIds: Set<String>? = null) {
        val dlg = FileDialog(null as Frame?, "Export Filters", FileDialog.SAVE).apply {
            file = "openlog_filters.json"; isVisible = true
        }
        val path = dlg.file ?: return
        val dir = dlg.directory ?: return
        val file = File(dir, path)
        ioScope.launch {
            runCatching { file.writeText(exportFilters(selectedIds)) }.fold(
                onSuccess = { AppLogger.info("filters", "Exported filters to ${file.absolutePath}") },
                onFailure = { e -> AppLogger.error("filters", "Failed to export filters to ${file.absolutePath}", e) },
            )
        }
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
            .fold(
                onSuccess = { AppLogger.info("filters", "Imported filters from ${file.absolutePath}") },
                onFailure = { e ->
                    importError = "Could not read filter file."
                    pendingImportReview = null
                    AppLogger.error("filters", "Failed to import filters from ${file.absolutePath}", e)
                },
            )
    }

    fun importFiltersFromFileAsync(file: File) {
        ioScope.launch { importFiltersFromFile(file) }
    }

    fun importFiltersFromFilesAsync(files: List<File>) {
        ioScope.launch {
            val decoded = files.mapNotNull { file -> runCatching { decodeFilters(file.readText()).getOrNull() }.getOrNull() }
            val imported = decoded.flatten()
            beginImportFilterList(imported, sourceName = files.joinToString(", ") { it.name })
        }
    }

    // ── App data / clearable cache ────────────────────────────────────
    fun refreshAppDataSizeInfo() {
        appDataSizeBytes = File(appCachePath).totalFileSize()
    }

    fun refreshTemporaryDataSizeInfo() {
        temporaryDataSizeBytes = archiveCacheDir.totalFileSize() + notesDir.totalFileSize()
    }

    fun refreshStorageSizeInfo() {
        refreshTemporaryDataSizeInfo()
        // Publish the total last so callers observing it can rely on the temporary-data figure
        // from the same refresh already being available.
        refreshAppDataSizeInfo()
    }

    /** Compatibility entry point; the Settings counter now reports all app data. */
    fun refreshArchiveCacheInfo() {
        refreshStorageSizeInfo()
    }

    fun clearArchiveCache() {
        archiveCacheDir.listFiles().orEmpty().forEach { file -> runCatching { file.deleteRecursively() } }
        notesDir.listFiles().orEmpty().forEach { file -> runCatching { file.deleteRecursively() } }
        pruneMissingRecentNotes()
        refreshStorageSizeInfo()
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

    fun requestResetAppData() {
        resetAppDataError = null
        refreshStorageSizeInfo()
        resetAppDataConfirmOpen = true
    }

    fun cancelResetAppData() {
        resetAppDataConfirmOpen = false
        resetAppDataError = null
    }

    /** Deletes only openLog's managed app-data root; callers exit immediately after success. */
    fun deleteAllAppData(): Boolean {
        val root = File(appCachePath)
        val deleted = runCatching { !root.exists() || root.deleteRecursively() }.getOrElse { false }
        if (!deleted || root.exists()) {
            resetAppDataError = "Could not remove all app data. Check permissions and try again."
            return false
        }
        resetAppDataConfirmOpen = false
        resetAppDataError = null
        appDataSizeBytes = 0L
        temporaryDataSizeBytes = 0L
        return true
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
            // (ARCH-2) Content-sniffed dispatch: a JSON object (new format, see settingsJson())
            // starts with '{' once decoded; anything else is the legacy positional pipe blob
            // (see settingsFromToken()) from a cache written before this migration.
            "settings" -> {
                val decoded = value.unb64()
                val restored = if (decoded.trimStart().startsWith("{")) {
                    settingsFromJson(decoded)
                } else {
                    settingsFromToken(decoded)
                }
                restored?.let { settings = it }
            }
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
                val result = loadRestoredTab(source)
                if (result is RestoredTabLoadResult.MissingArchiveEntry) {
                    ensureActive()
                    // Remove this job before closeTab() so closing an unavailable shell does not
                    // cancel the coroutine currently performing that cleanup.
                    finishActiveLoad(activeLoads.remove(tabId))
                    closeTab(tabId)
                    showOpenError(
                        title = "Restored archive entry is unavailable",
                        path = "${result.archiveFile.absolutePath}!${result.entryPath}",
                        message = "The saved archive entry no longer exists or is no longer a readable log.",
                    )
                    published = true
                    return@launch
                }
                result as RestoredTabLoadResult.Loaded
                ensureActive()
                val rmap = mkRmap(result.logData)
                // Rows and the metadata that controls their Compose computation become visible in
                // one snapshot update; a regenerated archive cannot expose new large content under
                // the persisted small-file classification.
                upTab(tabId) {
                    it.copy(
                        logData = result.logData,
                        rmap = rmap,
                        analysis = pendingAnalysis(result.logData),
                        archiveCandidate = result.archiveCandidate,
                        largeFileMode = result.largeFileMode,
                    )
                }
                markActiveLoadFinished(tabId)
                published = true
                val full = buildLogAnalysis(result.logData)
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

    private fun loadRestoredTab(source: RestoredTabSource): RestoredTabLoadResult {
        return when (source) {
            is RestoredTabSource.FileSource -> {
                val logData = runCatching { parser(source.file) }.getOrElse { emptyList() }
                RestoredTabLoadResult.Loaded(
                    logData = logData,
                    archiveCandidate = null,
                    largeFileMode = source.file.length() >= LARGE_FILE_MODE_BYTES,
                )
            }
            is RestoredTabSource.ArchiveSource -> {
                val currentCandidate = restoredArchiveCandidateResolver(source.archiveFile, source.entryPath)
                    ?: return RestoredTabLoadResult.MissingArchiveEntry(source.archiveFile, source.entryPath)
                val logData = runCatching {
                    extractCandidate(source.archiveFile, currentCandidate, archiveEntryByteBudget)
                }.getOrElse { emptyList() }
                RestoredTabLoadResult.Loaded(
                    logData = logData,
                    archiveCandidate = currentCandidate,
                    largeFileMode = currentCandidate.sizeBytes >= restoredArchiveLargeFileModeBytes,
                )
            }
        }
    }

    private fun serializeAutosave(): String = buildString {
        appendLine("openLog2-cache-v1")
        // (ARCH-2) settings is the only blob on this line encoded as keyed JSON rather than the
        // positional pipe format every other autosave key still uses — see settingsJson()/
        // settingsFromJson() below for why, and restoreAutosaveKey()'s "settings" branch for the
        // content-sniffed dispatch that keeps old positional caches readable.
        appendLine("settings\t${settings.settingsJson().b64()}")
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
