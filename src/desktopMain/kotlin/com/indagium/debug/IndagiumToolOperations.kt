package com.indagium.debug

import androidx.compose.ui.graphics.Color
import com.indagium.cases.CaseIndexer
import com.indagium.cases.CaseSearch
import com.indagium.cases.CaseSummary
import com.indagium.diagram.ActivationPolicy
import com.indagium.diagram.ArrowMode
import com.indagium.diagram.DiagramActor
import com.indagium.diagram.DiagramAuthoringMode
import com.indagium.diagram.DiagramComponent
import com.indagium.diagram.DiagramDialect
import com.indagium.diagram.DiagramOptions
import com.indagium.diagram.DiagramParticipant
import com.indagium.diagram.DiagramRange
import com.indagium.diagram.DiagramSourceEnrichment
import com.indagium.diagram.DiagramSourceInteraction
import com.indagium.diagram.DiagramSourceSiteOverride
import com.indagium.diagram.DiagramMessageOverride
import com.indagium.diagram.DiagramMessageRule
import com.indagium.diagram.ManualDiagramActivation
import com.indagium.diagram.ManualDiagramDocument
import com.indagium.diagram.ManualDiagramGroup
import com.indagium.diagram.ManualDiagramInteraction
import com.indagium.diagram.ManualDiagramNote
import com.indagium.diagram.DiagramParameter
import com.indagium.diagram.MessageKind
import com.indagium.diagram.MessageOriginKey
import com.indagium.diagram.DiagramRuleCaptureBinding
import com.indagium.diagram.DiagramRuleEndpoint
import com.indagium.diagram.DiagramResolvedTrace
import com.indagium.diagram.MAX_SOURCE_INTERACTIONS_PER_ENTRY
import com.indagium.diagram.MirrorDirection
import com.indagium.diagram.ParticipantKind
import com.indagium.diagram.SeqDiagram
import com.indagium.diagram.SeqDiagramSpec
import com.indagium.diagram.UnmappedTagPolicy
import com.indagium.diagram.buildSequenceDiagram
import com.indagium.diagram.manualDocumentFromDiagram
import com.indagium.diagram.toSource
import com.indagium.model.AnnBlock
import com.indagium.model.CrashSite
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogEntry
import com.indagium.model.LogItem
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.model.MessageCompositionState
import com.indagium.model.MessageRule
import com.indagium.model.MessageTemplate
import com.indagium.model.MessageTemplateHistogram
import com.indagium.model.RuleTarget
import com.indagium.model.SavedFilter
import com.indagium.model.SequenceDef
import com.indagium.model.TemplateGranularity
import com.indagium.source.SourceDeclaration
import com.indagium.source.SourceEnrichmentResolver
import com.indagium.source.SourceFileSnapshot
import com.indagium.source.SourceIndex
import com.indagium.source.SourceMatch
import com.indagium.source.SourceStructureParser
import com.indagium.source.SourceTraceInferenceEngine
import com.indagium.source.SOURCE_INDEX_VERSION
import com.indagium.ui.AppState
import com.indagium.ui.FollowDiagnostics
import com.indagium.ui.HL_COLORS
import com.indagium.ui.SEQ_COLORS
import com.indagium.ui.SplitSource
import com.indagium.ui.imageBytesFromFile
import com.indagium.ui.rotatedFramePng
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.ZipLogCandidate
import com.indagium.utils.computeItems
import com.indagium.utils.computeMessageTemplates
import com.indagium.utils.containsPattern
import com.indagium.utils.foldTemplates
import com.indagium.utils.indexOfEntryId
import com.indagium.utils.isSupportedArchiveFile
import com.indagium.utils.listArchiveLogCandidates
import com.indagium.utils.newId
import com.indagium.utils.viewDefiningKey
import com.indagium.utils.visibleEntries
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.LinkedHashMap
import kotlin.math.roundToInt

// Hex-color parsing constants for set_highlighters (parseHexColor / colorToHex).
private const val OPAQUE_ALPHA_MASK = 0xFF000000L
private const val COLOR_CHANNEL_MAX = 255
private const val COLOR_CHANNEL_MAX_F = 255f
private const val HEX_RGB_LEN = 6
private const val HEX_ARGB_LEN = 8
private const val HEX_RADIX = 16

// Mirrors CaseSearch's own DEFAULT_SEARCH_LIMIT (private to that class) — kept in sync manually
// since it's only reached here when the caller omits `limit` entirely.
// Hard ceiling on the diagram arrow cap a caller may request — past this a sequence diagram is
// not readable by anyone, and the raster clamp in the renderer would shrink it to illegibility.
private const val MAX_DIAGRAM_MESSAGES = 400
private const val MAX_DIAGRAM_COMPONENTS = 64
private const val MAX_DIAGRAM_ACTORS = 64
private const val MAX_DIAGRAM_LEGACY_TAGS = 128
private const val MAX_DIAGRAM_TAGS_PER_COMPONENT = 128
private const val MAX_DIAGRAM_TAG_REFERENCES = 1_024
private const val MAX_DIAGRAM_ID_CHARS = 256
private const val MAX_DIAGRAM_LABEL_CHARS = 512
private const val MAX_DIAGRAM_TITLE_CHARS = 512
private const val MAX_DIAGRAM_WARNINGS = 100
private const val MAX_DIAGRAM_WARNING_CHARS = 1_000
private const val MIN_DIAGRAM_SOURCE_CONFIDENCE = 0.7
internal const val MAX_MCP_DIAGRAM_SOURCE_CACHE_ENTRIES = 256
internal const val DIAGRAM_SOURCE_CACHE_KEY_CHARS = 43
private const val LRU_LOAD_FACTOR = 0.75f

/** Fixed-size per-build cache; keys are digests, so arbitrary log text is never retained here. */
internal class DiagramSourceLruCache<V>(private val maxEntries: Int) :
    LinkedHashMap<String, V>(maxEntries + 1, LRU_LOAD_FACTOR, true) {
    init {
        require(maxEntries > 0)
    }

    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?): Boolean = size > maxEntries
}

internal fun diagramSourceCacheKey(tag: String?, message: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val tagBytes = tag.orEmpty().toByteArray(Charsets.UTF_8)
    digest.update(tagBytes.size.toString().toByteArray(Charsets.US_ASCII))
    digest.update(0.toByte())
    digest.update(tagBytes)
    digest.update(message.toByteArray(Charsets.UTF_8))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest())
}

private const val DEFAULT_CASE_SEARCH_LIMIT = 8
private const val DEFAULT_SEQUENCE_OCCURRENCE_LIMIT = 100
private const val MAX_SEQUENCE_OCCURRENCE_LIMIT = 500
private const val DEFAULT_LOG_COMPOSITION_LIMIT = 50
private const val MAX_LOG_COMPOSITION_LIMIT = 500

/**
 * Transport-neutral AppState operations behind the Indagium MCP catalog.
 *
 * This is deliberately constructible without a server so the in-app AI runner can execute the
 * exact same contract directly. ControlServer is only an HTTP/MCP adapter over [toolGateway].
 */
internal class IndagiumToolOperations(
    private val appState: AppState,
    private val sourceIndexProvider: () -> SourceIndex? = { appState.sourceIndex },
) {
    private val operationHandlers: Map<String, (Map<String, Any?>) -> Any?> = mapOf(
        "list_tabs" to { listTabs() },
        "open_log_file" to { a ->
            openLogFile(
                a.str("path") ?: "", a.str("entryPath"), a.str("splitMode"),
                a.str("destinationDir"), a.str("postfix") ?: "part", a.anyInt("partCount"),
            )
        },
        "preview_split_log_file" to { a -> splitPreviewRoute(a.str("path") ?: "", a.str("entryPath")) },
        "split_log_file" to { a ->
            splitLogRoute(
                a.str("path") ?: "", a.str("entryPath"), a.str("destinationDir"),
                a.str("postfix") ?: "part", a.anyInt("partCount"),
            )
        },
        "close_tab" to { a -> closeTab(a.str("tabId") ?: "") },
        "get_filter" to { a -> getFilter(a.str("tabId") ?: "") },
        "get_sequence_summary" to { a ->
            getSequenceSummary(
                tabId = a.str("tabId") ?: "",
                sequenceId = a.str("sequenceId"),
                offset = a.anyInt("offset") ?: 0,
                limit = a.anyInt("limit") ?: DEFAULT_SEQUENCE_OCCURRENCE_LIMIT,
            )
        },
        "set_filter" to { a -> setFilter(a.str("tabId") ?: "", a) },
        "get_visible_lines" to { a ->
            getVisibleLines(
                a.str("tabId") ?: "", a.anyInt("limit") ?: DEFAULT_VISIBLE_LIMIT,
                a.anyInt("offset") ?: 0, a.strList("fields")?.toSet(), a.anyBool("compact") == true,
            )
        },
        "get_line_context" to { a ->
            getLineContext(
                a.str("tabId") ?: "", a.anyInt("lineId") ?: -1, a.anyInt("before") ?: 10,
                a.anyInt("after") ?: 10, a.strList("fields")?.toSet(), a.anyBool("compact") == true,
            )
        },
        "select_lines" to { a -> setSelection(a.str("tabId") ?: "", a.intList("lineIds") ?: emptyList()) },
        "get_selection" to { a -> getSelection(a.str("tabId") ?: "") },
        "toggle_group" to { a -> toggleGroupRoute(a.str("tabId") ?: "", a.str("gid") ?: "") },
        "expand_all" to { a -> expandAllRoute(a.str("tabId") ?: "") },
        "collapse_all" to { a -> collapseAllRoute(a.str("tabId") ?: "") },
        "get_tags" to { a -> getTags(a.str("tabId") ?: "") },
        "get_packages" to { a -> getPackages(a.str("tabId") ?: "") },
        "get_crash_sites" to { a -> getCrashSites(a.str("tabId") ?: "") },
        "get_issue_description" to { a -> getIssueDescription(a.str("tabId") ?: "") },
        "get_annotation_sections" to { a -> getAnnotationSections(a.str("tabId") ?: "") },
        "get_annotation_blocks" to { a -> getAnnotationBlocks(a.str("tabId") ?: "") },
        "append_annotation_section" to { a ->
            appendAnnotationSection(a.str("tabId") ?: "", a.str("section") ?: "", a.str("text"))
        },
        "set_annotation_section" to { a ->
            setAnnotationSection(a.str("tabId") ?: "", a.str("section") ?: "", a.str("text"))
        },
        "add_text_note" to { a ->
            addTextNoteRoute(a.str("tabId") ?: "", a.str("text") ?: "", a.str("afterId"))
        },
        "add_log_note" to { a ->
            addLogNoteRoute(a.str("tabId") ?: "", a.intList("lineIds") ?: emptyList(), a.str("caption") ?: "")
        },
        "add_image_note" to { a ->
            addImageNoteRoute(
                tabId = a.str("tabId") ?: "",
                imageBase64 = a.str("imageBase64"),
                imagePath = a.str("imagePath"),
                videoMs = a.anyInt("videoMs")?.toLong(),
                caption = a.str("caption") ?: "",
                afterId = a.str("afterId"),
            )
        },
        "update_note_block" to { a ->
            updateAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "", a.str("text") ?: "")
        },
        "move_note_block" to { a ->
            moveAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "", a.anyInt("delta") ?: 0)
        },
        "delete_note_block" to { a -> deleteAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "") },
        "clear_all_notes" to { a -> clearAllNotesRoute(a.str("tabId") ?: "") },
        "export_analysis" to { a -> exportAnalysisRoute(a.str("tabId") ?: "", a.str("path") ?: "") },
        "export_filtered_log" to { a ->
            exportFilteredRoute(a.str("tabId") ?: "", a.str("path") ?: "", a.str("format") ?: "txt")
        },
        "save_annotations" to { a -> saveAnnotationsRoute(a.str("tabId") ?: "", a.str("path") ?: "") },
        "load_annotations" to { a -> loadAnnotationsRoute(a.str("tabId") ?: "", a.str("path") ?: "") },
        "list_filter_presets" to { listFilterPresets() },
        "apply_filter_preset" to { a -> applyFilterPresetRoute(a.str("tabId") ?: "", a.str("presetId") ?: "") },
        "merge_tabs" to { a ->
            mergeTabsRoute(a.strList("tabIds") ?: emptyList(), a.str("newTabName") ?: "Merged")
        },
        "start_tailing" to { a -> startTailingRoute(a.str("tabId") ?: "") },
        "stop_tailing" to { a -> stopTailingRoute(a.str("tabId") ?: "") },
        "resolve_log_source" to { a -> resolveLogSourceRoute(a) },
        "get_source_file" to { a ->
            getSourceFileRoute(
                a.str("filePath") ?: "", a.anyInt("startLine") ?: 1,
                a.anyInt("lineCount") ?: DEFAULT_SOURCE_LINE_COUNT,
            )
        },
        "list_source_declarations" to { a ->
            listSourceDeclarationsRoute(a.str("filePath") ?: "", a.str("parentId"))
        },
        "get_source_declarations" to { a ->
            getSourceDeclarationsRoute(
                a.str("filePath") ?: "", a.strList("declarationIds"), a.str("revision"),
            )
        },
        "get_project_info" to { a -> getProjectInfoRoute(a.anyInt("maxContentChars")) },
        "set_highlighters" to { a -> setHighlightersRoute(a.str("tabId") ?: "", a) },
        "register_source_folder" to { a -> appState.registerSourceFolder(a.str("path") ?: "") },
        "reindex_sources" to { a -> reindexSourcesRoute(a.str("folder")) },
        "add_manual_collapse" to { a ->
            addManualCollapseRoute(a.str("tabId") ?: "", a.anyInt("startLineId"), a.anyInt("endLineId"))
        },
        "add_sequence" to { a -> addSequenceRoute(a.str("tabId") ?: "", a) },
        "save_filter_preset" to { a -> saveFilterPresetRoute(a.str("tabId") ?: "", a.str("name") ?: "") },
        "search_similar_cases" to { a ->
            searchSimilarCasesRoute(a.str("query") ?: "", a.strList("tags") ?: emptyList(), a.str("excludeSourcePath"), a.anyInt("limit"))
        },
        "get_case" to { a -> getCaseRoute(a.str("id") ?: "") },
        "build_sequence_diagram" to { a -> buildSequenceDiagramRoute(a.str("tabId") ?: "", a) },
        "set_case_metadata" to { a ->
            setCaseMetadataRoute(a.str("tabId") ?: "", a.str("appVersion"), a.strList("decisiveTags"))
        },
        "reindex_cases" to { reindexCasesRoute() },
        "get_video_frame" to { a ->
            getVideoFrameRoute(a.str("tabId") ?: "", a.anyInt("lineId"), a.anyInt("videoMs")?.toLong())
        },
        "get_follow_diagnostics" to { a ->
            getFollowDiagnosticsRoute(a.str("tabId") ?: "", a.anyInt("videoMs")?.toLong() ?: 0L)
        },
        "get_log_composition" to { a ->
            getLogComposition(
                tabId = a.str("tabId") ?: "",
                granularityParam = a.str("granularity"),
                orderParam = a.str("order"),
                tag = a.str("tag"),
                search = a.str("search"),
                offset = a.anyInt("offset") ?: 0,
                limit = a.anyInt("limit") ?: DEFAULT_LOG_COMPOSITION_LIMIT,
            )
        },
    )

    // Hoisted onto AppState (ui/AppState.kt's own `caseSearch`) so this MCP/AI tool surface and the
    // Case Library dialog search, get, and reindex the exact same in-memory index/lock over
    // <appDataDir>/case-index — two independent CaseSearch instances would mean two independent
    // locks writing that one file and duplicated parse work. `internal` (not `private`) so tests can
    // assert the two callers share one instance rather than two.
    internal val caseSearch: CaseSearch get() = appState.caseSearch

    // Sequence rendering caches a related result in Filter.kt, but that cache uses the currently
    // filtered data and is intentionally private to rendering.  This tool must always report the
    // full raw log, even when the UI filter or seqOn is off, so it keeps its own identity-keyed
    // read-only cache.  Log data and Filter are replaced wholesale by AppState updates, making
    // reference + value equality a precise invalidation boundary without copying a large log.
    private data class SequenceOccurrence(
        val def: SequenceDef,
        val startIndex: Int,
        val endExclusive: Int,
        val depth: Int,
        val endReason: String,
    )

    private data class SequenceSummaryCache(
        val logData: List<LogEntry>,
        val definitions: List<SequenceDef>,
        val occurrences: List<SequenceOccurrence>,
    )

    private var sequenceSummaryCache: SequenceSummaryCache? = null

    // Direct in-process entry point shared by MCP/REST and the future AI runner.
    internal val toolGateway = IndagiumToolGateway(MCP_TOOLS, operationHandlers)

    internal fun openAiFunctionDefinitions() = toolGateway.openAiFunctions()

    // ── Routes ──────────────────────────────────────────────────────────

    private fun listTabs(): List<Map<String, Any?>> = appState.tabs.map { t ->
        mapOf(
            "id" to t.id,
            "filename" to t.filename,
            "entryCount" to t.logData.size,
            "sourcePath" to t.sourcePath,
            "activeTags" to t.filter.activeTags.toList(),
            "levels" to t.filter.levels.map { it.key.toString() },
            "tailing" to t.tailing,
        )
    }

    @Suppress("ReturnCount") // Mirrors the existing MCP route's explicit error/result contract.
    private fun openLogFile(
        path: String,
        entryPath: String?,
        splitMode: String?,
        destinationDir: String?,
        postfix: String,
        partCount: Int?,
    ): Map<String, Any?> {
        if (invalidPath(path)) return mapOf("error" to "invalid or missing path")
        val file = File(path)
        if (!file.exists()) return mapOf("error" to "file not found: $path")
        if (isSupportedArchiveFile(file)) return openZipRoute(file, entryPath, splitMode, destinationDir, postfix, partCount)
        if (splitMode.equals("split", ignoreCase = true)) {
            return splitLogRoute(path, entryPath = null, destinationDir = destinationDir, postfix = postfix, partCount = partCount)
        }
        val absPath = file.absolutePath
        val tabId = if (splitMode.equals("open_as_is", ignoreCase = true)) appState.openFileAsIs(file) else appState.openFile(file)
        appState.pendingSplitPrompt?.sources?.firstOrNull { source ->
            source is SplitSource.RealFile && source.file.absolutePath == absPath
        }?.let { source ->
            return splitSourceToMap(source)
        }
        // (ARCH-3) openFile/openFileAsIs already return the specific tabId this call triggered (or
        // reused, via the "already open" dedup fast path) — scope the wait to that tab instead of
        // the global isLoading flag, so a concurrent unrelated open on another tab can't make this
        // call wait on, or misreport completion for, a load it didn't start.
        if (tabId == null) return mapOf("error" to "file did not load: $path")
        awaitLoad(tabId)
        val tab = appState.tab(tabId) ?: return mapOf("error" to "file did not load: $path")
        return mapOf("tabId" to tab.id, "filename" to tab.filename, "entryCount" to tab.logData.size)
    }

    // Mirrors the UI's single/multi-candidate split (AppState.openZipFile): a lone candidate
    // auto-opens like a plain file; 2+ candidates need a pick. Since a caller here isn't clicking
    // a picker dialog, it echoes the candidate list so a follow-up call can pass entryPath to
    // pick one directly — same round trip the UI's picker dialog does through openZipEntries().
    @Suppress("ReturnCount") // Mirrors the existing MCP route's explicit archive-selection contract.
    private fun openZipRoute(
        file: File,
        entryPath: String?,
        splitMode: String?,
        destinationDir: String?,
        postfix: String,
        partCount: Int?,
    ): Map<String, Any?> {
        val candidates = listArchiveLogCandidates(file)
        if (candidates.isEmpty()) return mapOf("error" to "no candidate log files found in zip: ${file.path}")
        val target = when {
            entryPath != null -> candidates.find { it.entryPath == entryPath }
                ?: return mapOf("error" to "no such entry in zip: $entryPath")
            candidates.size == 1 -> candidates.first()
            else -> return mapOf("needsSelection" to true, "candidates" to candidates.map { zipCandidateToMap(it) })
        }
        if (splitMode.equals("split", ignoreCase = true)) {
            return splitLogRoute(file.absolutePath, target.entryPath, destinationDir, postfix, partCount)
        }
        val tabId = if (splitMode.equals("open_as_is", ignoreCase = true)) {
            appState.openZipEntryAsIs(file, target)
        } else {
            appState.openZipEntries(file, listOf(target)).firstOrNull()
        }
        appState.pendingSplitPrompt?.sources?.firstOrNull { source ->
            source is SplitSource.ArchiveEntry &&
                source.archiveFile.absolutePath == file.absolutePath &&
                source.candidate.entryPath == target.entryPath
        }?.let { source ->
            return splitSourceToMap(source)
        }
        // (ARCH-3) See openLogFile's matching comment above — scope to the specific tabId
        // openZipEntryAsIs/openZipEntries already hand back, instead of the global isLoading flag.
        if (tabId == null) return mapOf("error" to "entry did not load: ${target.entryPath}")
        awaitLoad(tabId)
        val tab = appState.tab(tabId) ?: return mapOf("error" to "entry did not load: ${target.entryPath}")
        return mapOf("tabId" to tab.id, "filename" to tab.filename, "entryCount" to tab.logData.size)
    }

    private fun splitLogRoute(
        path: String,
        entryPath: String?,
        destinationDir: String?,
        postfix: String,
        partCount: Int?,
    ): Map<String, Any?> {
        if (invalidPath(path)) return mapOf("error" to "invalid or missing path")
        val source = appState.splitSourceForPath(path, entryPath) ?: return mapOf("error" to "source not found: $path")
        val destination = destinationDir?.takeIf { it.isNotBlank() }?.let(::File) ?: appState.defaultSplitDestination(source)
        val count = (partCount ?: appState.defaultSplitPartCount(source)).coerceAtLeast(1)
        if (appState.pendingSplitPrompt?.sources?.any { it.id == source.id } == true) {
            appState.cancelSplitPrompt()
        }
        val before = appState.tabs.size
        val outputs = appState.splitSourceAndOpen(source, destination, postfix, count)
        awaitLoad()
        val openedTabs = appState.tabs.drop(before).map { tab ->
            mapOf("tabId" to tab.id, "filename" to tab.filename, "entryCount" to tab.logData.size, "sourcePath" to tab.sourcePath)
        }
        return mapOf(
            "ok" to true,
            "outputPaths" to outputs.map { it.absolutePath },
            "tabs" to openedTabs,
        )
    }

    private fun splitPreviewRoute(path: String, entryPath: String?): Map<String, Any?> {
        if (invalidPath(path)) return mapOf("error" to "invalid or missing path")
        val source = appState.splitSourceForPath(path, entryPath) ?: return mapOf("error" to "source not found: $path")
        return splitSourceToMap(source)
    }

    // Merge/split routes don't have one concrete tabId to scope to up front the way open_log_file
    // does — mergeTabsRoute only learns the new tab's id after mergeTabs() returns (it doesn't
    // register into AppState.activeLoads at all, just the plain pendingLoads counter), and
    // splitLogRoute's splitSourceAndOpen call is synchronous on this same thread with no async
    // load to await. Kept on the global flag rather than force-fitting a scoping mechanism these
    // callers can't cleanly supply a key for; see awaitLoad(tabId) below for the scoped version
    // used by routes that do have a concrete tabId.
    private fun awaitLoad() {
        val deadline = System.currentTimeMillis() + OPEN_FILE_TIMEOUT_MS
        while (appState.isLoading && System.currentTimeMillis() < deadline) Thread.sleep(OPEN_FILE_POLL_INTERVAL_MS)
    }

    // (ARCH-3) openFile/openZipEntries are async (launched on ioScope); block this request thread
    // until this SPECIFIC tab's load finishes, rather than polling the global isLoading flag above
    // — a concurrent unrelated load on another tab must not make this call wait on, or
    // mis-attribute completion to, a load it didn't trigger. If the target was already open (the
    // dedup fast path), isLoadInFlight is false immediately and this loop exits on the first check.
    private fun awaitLoad(tabId: String) {
        val deadline = System.currentTimeMillis() + OPEN_FILE_TIMEOUT_MS
        while (appState.isLoadInFlight(tabId) && System.currentTimeMillis() < deadline) Thread.sleep(OPEN_FILE_POLL_INTERVAL_MS)
    }

    private fun mergeTabsRoute(tabIds: List<String>, newTabName: String): Map<String, Any?> {
        if (tabIds.size < 2) return mapOf("error" to "need at least 2 tabIds to merge")
        val missing = tabIds.filter { appState.tab(it) == null }
        if (missing.isNotEmpty()) return mapOf("error" to "no such tab(s): ${missing.joinToString(", ")}")
        val beforeCount = appState.tabs.size
        appState.mergeTabs(tabIds, newTabName)
        awaitLoad()
        if (appState.tabs.size <= beforeCount) return mapOf("error" to "merge did not produce a new tab")
        val tab = appState.tabs.last()
        return mapOf("tabId" to tab.id, "filename" to tab.filename, "entryCount" to tab.logData.size)
    }

    // Starting/stopping tailing is a synchronous state update (unlike open/merge, there's no
    // one-shot load to await isLoading for) — the actual line detection happens asynchronously
    // over time on FileTailer's own coroutine. Poll GET /tabs or /visible afterward to observe
    // growth.
    private fun startTailingRoute(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        appState.startTailing(tabId)
        return mapOf("tabId" to tabId, "tailing" to (appState.tab(tabId)?.tailing ?: tab.tailing))
    }

    private fun stopTailingRoute(tabId: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.stopTailing(tabId)
        return mapOf("tabId" to tabId, "tailing" to (appState.tab(tabId)?.tailing ?: false))
    }

    // Resolves a log line back to the source method(s) that could have emitted it. Two input
    // modes: tabId+lineId reads an already-open tab's row (like showSourceForLine); tag+message
    // lets a caller resolve an arbitrary line it only has text for (e.g. pasted from elsewhere).
    // The two "nothing configured yet" states are distinct errors (no folders vs. not indexed)
    // so a client — or a human reading the error — knows exactly which Settings action to take;
    // a resolve that ran but simply found nothing is NOT an error, it's `{ matches: [] }`, so a
    // client can tell "misconfigured" apart from "configured correctly, no match for this line."
    private fun resolveLogSourceRoute(a: Map<String, Any?>): Map<String, Any?> {
        if (appState.settings.sourceFolders.isEmpty()) {
            return mapOf("error" to "no source folders configured — add one in Settings → Source code")
        }
        if (appState.sourceIndex == null) {
            return mapOf("error" to "source folders not indexed yet — run Reindex in Settings → Source code")
        }
        val limit = (a.anyInt("limit") ?: DEFAULT_RESOLVE_LIMIT).coerceIn(1, MAX_RESOLVE_LIMIT)
        val tabId = a.str("tabId")
        val lineId = a.anyInt("lineId")
        val message = a.str("message")
        val matches = when {
            tabId != null && lineId != null -> appState.resolveForLine(tabId, lineId, limit)
            message != null -> appState.resolveLogSource(a.str("tag"), message, limit)
            else -> return mapOf("error" to "provide either tabId+lineId or message (+optional tag)")
        }
        return buildMap {
            put("matches", matches.map { sourceMatchToMap(it) })
            // These are additive navigation fields. They make a source-evidence card point at
            // the exact tab/row that produced this result instead of trusting model prose.
            if (tabId != null && lineId != null) {
                put("tabId", tabId)
                put("lineId", lineId)
            }
        }
    }

    @Suppress("ReturnCount") // The route has explicit, actionable validation responses for each invalid range.
    private fun getSourceFileRoute(filePath: String, startLine: Int, lineCount: Int): Map<String, Any?> {
        val snapshot = authorizedSourceSnapshot(filePath) ?: return sourceAccessError(filePath)
        if (startLine < 1) return mapOf("error" to "startLine must be at least 1")
        if (lineCount !in 1..MAX_SOURCE_LINE_COUNT) {
            return mapOf("error" to "lineCount must be between 1 and $MAX_SOURCE_LINE_COUNT")
        }
        if (snapshot.lineCount == 0) {
            if (startLine != 1) return mapOf("error" to "startLine is outside this empty source file")
            return mapOf(
                "filePath" to snapshot.canonicalPath, "revision" to snapshot.revision,
                "totalLines" to 0, "startLine" to 1, "endLine" to 0, "content" to "", "nextStartLine" to null,
            )
        }
        if (startLine > snapshot.lineCount) return mapOf("error" to "startLine is outside this source file")
        val lines = snapshot.text.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
        val endLine = minOf(snapshot.lineCount, startLine + lineCount - 1)
        return mapOf(
            "filePath" to snapshot.canonicalPath,
            "revision" to snapshot.revision,
            "totalLines" to snapshot.lineCount,
            "startLine" to startLine,
            "endLine" to endLine,
            "content" to lines.subList(startLine - 1, endLine).joinToString("\n"),
            "nextStartLine" to (endLine + 1).takeIf { it <= snapshot.lineCount },
        )
    }

    private fun listSourceDeclarationsRoute(filePath: String, parentId: String?): Map<String, Any?> {
        val snapshot = authorizedSourceSnapshot(filePath) ?: return sourceAccessError(filePath)
        val structure = SourceStructureParser.parse(snapshot.text, snapshot.isJavaFile)
        if (parentId != null && structure.declarations.none { it.id == parentId }) {
            return mapOf("error" to "unknown declaration id; call list_source_declarations again for this file")
        }
        return mapOf(
            "filePath" to snapshot.canonicalPath,
            "revision" to snapshot.revision,
            "parentId" to parentId,
            "declarations" to structure.directChildren(parentId).map(::sourceDeclarationSummary),
        )
    }

    private fun getSourceDeclarationsRoute(
        filePath: String,
        declarationIds: List<String>?,
        revision: String?,
    ): Map<String, Any?> {
        val snapshot = authorizedSourceSnapshot(filePath) ?: return sourceAccessError(filePath)
        if (declarationIds.isNullOrEmpty()) return mapOf("error" to "provide one or more declarationIds")
        if (revision != null && revision != snapshot.revision) {
            return mapOf("error" to "source file changed; call list_source_declarations again before reading declarations")
        }
        val byId = SourceStructureParser.parse(snapshot.text, snapshot.isJavaFile).declarations.associateBy { it.id }
        val missing = declarationIds.filter { it !in byId }
        if (missing.isNotEmpty()) {
            return mapOf("error" to "unknown declaration id; call list_source_declarations again for this file", "unknownIds" to missing)
        }
        return mapOf(
            "filePath" to snapshot.canonicalPath,
            "revision" to snapshot.revision,
            "declarations" to declarationIds.map { declaration ->
                sourceDeclarationSummary(byId.getValue(declaration)) + mapOf(
                    "code" to snapshot.text.substring(
                        byId.getValue(declaration).startOffset,
                        byId.getValue(declaration).endOffsetExclusive,
                    ),
                )
            },
        )
    }

    private fun authorizedSourceSnapshot(filePath: String): SourceFileSnapshot? =
        if (filePath.isBlank()) null else appState.readRegisteredSourceFile(filePath)

    private fun sourceAccessError(filePath: String): Map<String, String> = when {
        appState.settings.sourceFolders.isEmpty() ->
            mapOf("error" to "no source folders configured — add one in Settings → Source code")
        filePath.isBlank() -> mapOf("error" to "missing filePath")
        else -> mapOf("error" to "source file is missing, unsupported, or outside registered source folders")
    }

    private fun sourceDeclarationSummary(declaration: SourceDeclaration): Map<String, Any?> = mapOf(
        "id" to declaration.id,
        "parentId" to declaration.parentId,
        "kind" to declaration.kind,
        "name" to declaration.name,
        "signature" to declaration.signature,
        "startLine" to declaration.startLine,
        "endLine" to declaration.endLine,
        "hasChildren" to declaration.hasChildren,
    )

    private fun getProjectInfoRoute(maxContentChars: Int? = null): Map<String, Any?> {
        if (maxContentChars != null && maxContentChars <= 0) {
            return mapOf("error" to "maxContentChars must be positive when provided")
        }
        var remaining = maxContentChars
        var returnedContentChars = 0
        var truncated = false
        val infoEntries = appState.settings.sourceFolderInfo.entries.let { entries ->
            if (maxContentChars == null) entries else entries.sortedBy { it.key }
        }
        val folders = infoEntries.mapNotNull { (path, info) ->
            if (info.description.isBlank() && info.readmePath.isNullOrBlank()) return@mapNotNull null
            buildMap<String, Any?> {
                put("path", path)
                val description = capProjectInfoContent(info.description, remaining)
                put("description", description.content)
                returnedContentChars += description.content.length
                remaining = remaining?.minus(description.content.length)
                if (maxContentChars != null) {
                    put("descriptionTruncated", description.truncated)
                    truncated = truncated || description.truncated
                }
                put("readmePath", info.readmePath)
                if (info.readmePath != null) {
                    runCatching { File(info.readmePath).readText() }
                        .onSuccess { content ->
                            val readme = capProjectInfoContent(content, remaining)
                            put("readmeContent", readme.content)
                            returnedContentChars += readme.content.length
                            remaining = remaining?.minus(readme.content.length)
                            if (maxContentChars != null) {
                                put("readmeTruncated", readme.truncated)
                                truncated = truncated || readme.truncated
                            }
                        }
                        .onFailure { put("readmeError", it.message ?: "unreadable") }
                }
            }
        }
        return if (maxContentChars == null) {
            // Keep the no-argument response byte-for-byte compatible with the original contract.
            mapOf("folders" to folders)
        } else {
            mapOf(
                "folders" to folders,
                "maxContentChars" to maxContentChars,
                "returnedContentChars" to returnedContentChars,
                "contentTruncated" to truncated,
            )
        }
    }

    private data class CappedProjectInfoContent(val content: String, val truncated: Boolean)

    private fun capProjectInfoContent(content: String, remaining: Int?): CappedProjectInfoContent {
        if (remaining == null) return CappedProjectInfoContent(content, truncated = false)
        val bounded = content.take(remaining.coerceAtLeast(0))
        return CappedProjectInfoContent(bounded, truncated = bounded.length < content.length)
    }

    // ── New tool routes: highlighters / reindex / manual collapse / save preset ──

    // Replace a tab's highlighters wholesale (mirrors set_filter's replace-semantics for sequences).
    // Highlighters only tint matching text — they never hide/fold rows — so this is a view-only
    // mutation, classified AUTOMATIC alongside set_filter rather than confirmation-gated.
    private fun setHighlightersRoute(tabId: String, body: Map<String, Any?>): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        val newList: List<Highlighter> = when {
            body.bool("clearHighlighters") == true -> emptyList()
            body.containsKey("highlighters") ->
                parseHighlighters(body.mapList("highlighters") ?: emptyList())
                    .getOrElse { return mapOf("error" to (it.message ?: "invalid highlighters")) }
            else -> return mapOf("error" to "provide highlighters (a list) or clearHighlighters:true")
        }
        appState.upFlt(tabId) { f -> f.copy(highlighters = newList) }
        return mapOf(
            "ok" to true,
            "highlighters" to (appState.tab(tabId)?.filter?.highlighters ?: newList).map { highlighterToMap(it) },
        )
    }

    // Parse client-supplied highlighters. Colors are optional as hex ("#RRGGBB"/"#AARRGGBB"); a
    // missing color is assigned round-robin from HL_COLORS (the same palette the UI's add form
    // cycles), so a client never has to know the palette to get visually distinct highlighters.
    private fun parseHighlighters(raw: List<Map<String, Any?>>): Result<List<Highlighter>> = runCatching {
        raw.mapIndexed { idx, m ->
            val pattern = m.str("pattern")?.takeIf { it.isNotBlank() }
                ?: error("highlighters[$idx]: missing or blank 'pattern'")
            val color = m.str("color")?.takeIf { it.isNotBlank() }?.let {
                parseHexColor(it) ?: error("highlighters[$idx]: invalid hex color '$it' (expected #RRGGBB or #AARRGGBB)")
            } ?: HL_COLORS[idx % HL_COLORS.size]
            Highlighter(
                id = m.str("id")?.takeIf { it.isNotBlank() } ?: "${newId("hl")}_$idx",
                pattern = pattern,
                regex = m.bool("regex") ?: false,
                color = color,
                on = m.bool("enabled") ?: true,
            )
        }
    }

    private fun highlighterToMap(h: Highlighter): Map<String, Any?> = mapOf(
        "id" to h.id, "pattern" to h.pattern, "regex" to h.regex,
        "color" to colorToHex(h.color), "enabled" to h.on,
    )

    // Kick off a (background) source-index rebuild. Non-destructive but I/O-heavy, so it is
    // classified CONFIRMATION_REQUIRED. reindexSources is async — this returns the folders it
    // started, and the caller polls resolve_log_source / get_project_info until the index lands.
    private fun reindexSourcesRoute(folder: String?): Map<String, Any?> {
        val folders = if (!folder.isNullOrBlank()) listOf(folder) else appState.settings.sourceFolders.toList()
        if (folders.isEmpty()) {
            return mapOf("error" to "no source folders configured — add one in Settings → Source code")
        }
        folders.forEach { appState.reindexSources(it) }
        return mapOf("started" to true, "folders" to folders)
    }

    private fun addManualCollapseRoute(tabId: String, startLineId: Int?, endLineId: Int?): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        if (startLineId == null || endLineId == null) return mapOf("error" to "provide startLineId and endLineId")
        return mapOf("ok" to appState.collapseRange(tabId, startLineId, endLineId))
    }

    private fun saveFilterPresetRoute(tabId: String, name: String): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        val clean = name.trim()
        if (clean.isBlank()) return mapOf("error" to "missing or blank name")
        // Pre-check duplicates and error out rather than invoking saveFilter's interactive
        // duplicate-name dialog path (which would pop a UI dialog and persist nothing here).
        if (appState.savedFilters.any { it.name.trim().equals(clean, ignoreCase = true) }) {
            return mapOf("error" to "preset name already exists: $clean")
        }
        appState.saveFilter(tabId, clean)
        val saved = appState.savedFilters.find { it.name.trim().equals(clean, ignoreCase = true) }
            ?: return mapOf("error" to "preset was not saved")
        return mapOf("ok" to true, "preset" to filterPresetToMap(saved))
    }

    // ── Similar past issues (com.indagium.cases) ─────────────────────────

    private fun searchSimilarCasesRoute(query: String, tags: List<String>, excludeSourcePath: String?, limit: Int?): Map<String, Any?> {
        if (query.isBlank()) return mapOf("error" to "missing or blank query")
        val results = caseSearch.search(
            query = query,
            tags = tags,
            excludeSourcePath = excludeSourcePath?.takeIf { it.isNotBlank() },
            limit = limit ?: DEFAULT_CASE_SEARCH_LIMIT,
        )
        return mapOf("matches" to results.map { caseSummaryToMap(it) })
    }

    /**
     * Builds a sequence diagram and returns its source. Read-only by design: writing it into the
     * notes is a separate, explicit `add_text_note` call, so a model can iterate on participants
     * and range without leaving half-finished diagrams in the user's analysis.
     *
     * Participants are resolved by TAG NAME rather than index because that's what a model actually
     * has — it sees tags in get_tags/get_visible_lines output, never our internal column order.
     */
    private fun buildSequenceDiagramRoute(tabId: String, args: Map<String, Any?>): Map<String, Any?> {
        if (tabId.length > MAX_DIAGRAM_ID_CHARS) {
            return mapOf("error" to "tabId must be at most $MAX_DIAGRAM_ID_CHARS characters")
        }
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val inputError = validateDiagramInput(args)
        if (inputError != null) return mapOf("error" to inputError)

        val components = diagramComponents(args)
        val actorNames = (args["actors"] as? List<*>)
            .orEmpty().filterIsInstance<String>().map(String::trim).filter(String::isNotEmpty)
        val entryActor = args.str("entryActor")
        val exitActor = args.str("exitActor")
        val actors = diagramActors(args, actorNames)
        val tagParticipants = args.strList("tags").orEmpty().filter { it.isNotBlank() }
            .map { DiagramParticipant(id = it, label = it, kind = ParticipantKind.TAG, tag = it) }
        val configurationError = diagramConfigurationError(components, actors, tagParticipants, rawDiagramActorIds(args))
        if (configurationError != null) return mapOf("error" to configurationError)

        val requestedSpec = diagramSpec(
            tab = tab,
            args = args,
            components = components,
            actors = actors,
            actorParticipants = diagramActorParticipants(actorNames, actors, entryActor, exitActor),
            tagParticipants = tagParticipants,
        )
        val manualDocumentError = manualDocumentConfigurationError(requestedSpec, components)
        if (manualDocumentError != null) return mapOf("error" to manualDocumentError)
        val sourceIndex = sourceIndexProvider()
        // MCP has the same contract as the workspace: callers may submit a finished manual
        // document, otherwise the service creates an evidence-assisted manual draft first.
        val seed = args["seed"] as? Map<*, *>
        val seedSourceTrace = (seed?.get("sourceTrace") as? Boolean) ?: args.anyBool("sourceEnrichment") ?: true
        val seedHandoffs = (seed?.get("threadHandoffs") as? Boolean) ?: false
        val seedSpec = requestedSpec.copy(
            authoringMode = DiagramAuthoringMode.INFERRED,
            sourceEnrichment = requestedSpec.sourceEnrichment.copy(enabled = seedSourceTrace),
            options = requestedSpec.options.copy(threadHandoffArrows = seedHandoffs),
        )
        val spec = if (requestedSpec.manualDocument.interactions.isNotEmpty()) {
            requestedSpec.copy(authoringMode = DiagramAuthoringMode.MANUAL)
        } else {
            val inferred = buildSequenceDiagram(
                tab = tab,
                spec = seedSpec,
                resolveTrace = sourceTraceResolver(seedSpec, sourceIndex),
                resolveSourceInteractions = sourceInteractionResolver(seedSpec, sourceIndex),
            )
            requestedSpec.copy(
                authoringMode = DiagramAuthoringMode.MANUAL,
                manualDocument = manualDocumentFromDiagram(inferred),
            )
        }
        val diagram = buildSequenceDiagram(tab = tab, spec = spec)
        val routeWarnings = boundedDiagramWarnings(diagram.warnings + sourceEnrichmentAvailabilityWarnings(spec, sourceIndex))
        return diagramRouteResponse(diagram, spec, components, sourceIndex, routeWarnings)
    }

    private fun diagramConfigurationError(
        components: List<DiagramComponent>,
        actors: List<DiagramActor>,
        tagParticipants: List<DiagramParticipant>,
        rawActorIds: List<String>,
    ): String? {
        val oversized = components.firstOrNull { it.tagIds.size > MAX_DIAGRAM_TAGS_PER_COMPONENT }
        val duplicateComponent = duplicateOf(components.map { it.id })
        val duplicateActor = duplicateOf(rawActorIds)
        val componentIds = components.mapTo(HashSet()) { it.id }
        val actorCollision = actors.firstOrNull { it.id in componentIds }?.id
        val configuredIds = (componentIds + actors.map { it.id }).toHashSet()
        val tagCollision = tagParticipants.firstOrNull { it.id in configuredIds }?.id
        return listOfNotNull(
            if (components.size > MAX_DIAGRAM_COMPONENTS) {
                "components and mergedTags may define at most $MAX_DIAGRAM_COMPONENTS components"
            } else {
                null
            },
            oversized?.let { "component ${it.id} may contain at most $MAX_DIAGRAM_TAGS_PER_COMPONENT tags after merging" },
            duplicateComponent?.let { "duplicate component id: $it" },
            if (components.sumOf { it.tagIds.size } > MAX_DIAGRAM_TAG_REFERENCES) {
                "components may contain at most $MAX_DIAGRAM_TAG_REFERENCES tag references in total"
            } else {
                null
            },
            duplicateActor?.let { "duplicate actor id: $it" },
            actorCollision?.let { "participant id is used by both a component and actor: $it" },
            tagCollision?.let { "participant id is used more than once: $it" },
        ).firstOrNull()
    }

    private fun manualDocumentConfigurationError(spec: SeqDiagramSpec, components: List<DiagramComponent>): String? {
        val participantIds = (spec.participants.map { it.id } + components.map { it.id }).toSet()
        val badRule = spec.rules.firstOrNull { rule ->
            sequenceOf(rule.fromEndpoint, rule.toEndpoint).any { endpoint -> when (endpoint) {
                is DiagramRuleEndpoint.ExistingParticipant -> endpoint.participantId !in participantIds
                is DiagramRuleEndpoint.CapturedValue -> endpoint.bindings.any { it.participantId !in participantIds }
                else -> false
            } }
        }
        if (badRule != null) return "rule ${badRule.id} must reference configured participants"
        val document = spec.manualDocument
        val unknownInteraction = document.interactions.firstOrNull {
            it.fromParticipantId !in participantIds || it.toParticipantId !in participantIds
        }
        if (unknownInteraction != null) return "manual interaction ${unknownInteraction.id} must reference configured participants"
        val unknownNote = document.notes.firstOrNull { it.participantId !in participantIds }
        if (unknownNote != null) return "manual note ${unknownNote.id} must reference a configured participant"
        val unknownActivation = document.activations.firstOrNull { it.participantId !in participantIds }
        return unknownActivation?.let { "manual activation ${it.id} must reference a configured participant" }
    }

    private fun duplicateOf(ids: List<String>): String? =
        ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key

    private fun diagramActorParticipants(
        legacyNames: List<String>,
        actors: List<DiagramActor>,
        entryActor: String?,
        exitActor: String?,
    ): List<DiagramParticipant> = (legacyNames.map { DiagramActor(it, it) } + actors).map { actor ->
        DiagramParticipant(
            id = actor.id,
            label = actor.label,
            kind = ParticipantKind.ACTOR,
            isEntryPoint = actor.id == entryActor,
            isExitPoint = actor.id == exitActor,
        )
    }.distinctBy { it.id }

    private fun diagramSpec(
        tab: LogTab,
        args: Map<String, Any?>,
        components: List<DiagramComponent>,
        actors: List<DiagramActor>,
        actorParticipants: List<DiagramParticipant>,
        tagParticipants: List<DiagramParticipant>,
    ): SeqDiagramSpec {
        val startId = args.int("startLineId")
        val endId = args.int("endLineId")
        return SeqDiagramSpec(
            dialect = if (args.str("dialect").equals("plantuml", ignoreCase = true)) DiagramDialect.PLANTUML else DiagramDialect.MERMAID,
            title = args.str("title") ?: "",
            participants = actorParticipants + tagParticipants,
            range = if (startId != null && endId != null) DiagramRange.Ids(startId, endId) else DiagramRange.VisibleView,
            mode = when (args.str("mode")?.lowercase()) {
                "timeline" -> ArrowMode.LINE_PER_MESSAGE
                "rules" -> ArrowMode.RULES
                // "tagtransition"/"componentflow" are pre-rename aliases (see DiagramModel.kt's
                // ArrowMode doc) — kept so an existing MCP caller's saved "mode" value keeps
                // working; all three (plus the omitted/default case) now resolve to the same
                // evidence-only builder path, not the old tag-change guess.
                else -> ArrowMode.EVIDENCE_FLOW
            },
            options = DiagramOptions(
                collapseRepeats = args.bool("collapseRepeats") ?: true,
                maxMessages = args.int("maxMessages")?.coerceIn(1, MAX_DIAGRAM_MESSAGES) ?: DiagramOptions().maxMessages,
                activationPolicy = if (args.str("activationPolicy").equals("none", true)) {
                    ActivationPolicy.NONE
                } else {
                    ActivationPolicy.EVIDENCE_BACKED
                },
            ),
            sourceFile = File(tab.filename).name,
            components = components,
            actors = actors,
            unmappedTagPolicy = if (args.str("unmappedTagPolicy")?.lowercase() in setOf("groupasother", "group_as_other")) {
                UnmappedTagPolicy.GROUP_AS_OTHER
            } else {
                UnmappedTagPolicy.HIDE
            },
            sourceEnrichment = DiagramSourceEnrichment(enabled = args.anyBool("sourceEnrichment") == true),
            sourceSiteOverrides = diagramSourceSiteOverrides(args),
            rules = diagramRules(args),
            authoringMode = if (args.str("authoringMode").equals("manual", ignoreCase = true)) DiagramAuthoringMode.MANUAL else DiagramAuthoringMode.INFERRED,
            lifelineOrder = args.strList("lifelineOrder").orEmpty(),
            messageOverrides = diagramMessageOverrides(args),
            manualDocument = diagramManualDocument(args),
        )
    }

    private fun diagramRouteResponse(
        diagram: SeqDiagram,
        spec: SeqDiagramSpec,
        components: List<DiagramComponent>,
        sourceIndex: SourceIndex?,
        warnings: List<String>,
    ): Map<String, Any?> {
        if (diagram.messages.isEmpty()) return mapOf(
            "error" to "the selected tags and range produced no arrows — widen the range, or pick tags that " +
                "actually appear in it (get_tags lists them with counts)",
            "warnings" to warnings,
            "scannedEntries" to diagram.scannedEntries,
            "coverage" to diagramCoverageMap(diagram),
            "traceMode" to diagram.traceMode.name.lowercase(),
            "traceDiagnostics" to traceDiagnosticsMap(diagram),
            "trace" to traceMap(diagram),
        )
        return mapOf(
            "source" to diagram.toSource(spec.dialect),
            "dialect" to spec.dialect.name.lowercase(),
            "participants" to diagram.participants.map { participant ->
                mapOf(
                    "id" to participant.id,
                    "label" to participant.label,
                    "kind" to participant.kind.name.lowercase(),
                    "tag" to participant.tag,
                    "componentTagIds" to components.firstOrNull { it.id == participant.id }?.tagIds?.toList(),
                    "sourceOwnerType" to participant.sourceOwnerType,
                    "receiverRole" to participant.receiverRole,
                    "inferred" to participant.inferred,
                )
            },
            "messages" to diagram.messages.map { message ->
                mapOf(
                    "fromParticipantId" to diagram.participants.getOrNull(message.fromIdx)?.id,
                    "toParticipantId" to diagram.participants.getOrNull(message.toIdx)?.id,
                    "label" to message.label,
                    "entryId" to message.entryId,
                    "kind" to message.kind.name.lowercase(),
                    "evidence" to message.evidence.name.lowercase(),
                    "repeatCount" to message.repeatCount,
                    "invocationId" to message.invocationId,
                    "traceStatus" to message.traceStatus?.name?.lowercase(),
                    "invocationKind" to message.invocationKind?.name?.lowercase(),
                    "sourceOperationId" to message.sourceOperationId,
                    "sourceLogSiteId" to message.sourceLogSiteId,
                    "primary" to message.primary,
                    "origins" to message.originKeys.map(::originMap),
                )
            },
            "activationSpans" to diagram.activationSpans.map { span ->
                mapOf(
                    "participantId" to diagram.participants.getOrNull(span.participantIdx)?.id,
                    "startMessage" to span.startMessage,
                    "endMessage" to span.endMessage,
                    "evidence" to span.evidence.name.lowercase(),
                    "invocationId" to span.invocationId,
                    "status" to span.status?.name?.lowercase(),
                    "invocationKind" to span.invocationKind?.name?.lowercase(),
                )
            },
            "messageCount" to diagram.messages.size,
            "truncated" to diagram.truncated,
            "scannedEntries" to diagram.scannedEntries,
            "coverage" to diagramCoverageMap(diagram),
            "traceMode" to diagram.traceMode.name.lowercase(),
            "traceDiagnostics" to traceDiagnosticsMap(diagram),
            "trace" to traceMap(diagram),
            "lifelineOrder" to spec.lifelineOrder,
            "manualDocument" to manualDocumentMap(spec.manualDocument),
            "warnings" to warnings,
        )
    }

    private fun traceDiagnosticsMap(diagram: SeqDiagram): Map<String, Any?> {
        val diagnostics = diagram.resolvedTrace?.diagnostics ?: return emptyMap()
        return mapOf(
            "droppedByReason" to diagnostics.droppedByReason.mapKeys { it.key.name.lowercase() },
            "ambiguousEntryIds" to diagnostics.ambiguousEntryIds,
            "staleEntryIds" to diagnostics.staleEntryIds,
            "truncated" to diagnostics.truncated,
            "entries" to diagnostics.diagnostics.map { item ->
                mapOf(
                    "reason" to item.reason.name.lowercase(),
                    "entryId" to item.entryId,
                    "detail" to item.detail,
                )
            },
        )
    }

    /** Full source-trace evidence for MCP callers; the UI and MCP consume the same model. */
    private fun traceMap(diagram: SeqDiagram): Map<String, Any?> {
        val trace = diagram.resolvedTrace ?: return emptyMap()
        return mapOf(
            "events" to trace.events.map { event ->
                mapOf(
                    "entryId" to event.entryId,
                    "sourceLogSiteId" to event.sourceLogSiteId,
                    "methodId" to event.methodId,
                    "ownerType" to event.ownerType,
                    "methodName" to event.methodName,
                    "sourceFile" to event.sourceFile,
                    "sourceLine" to event.sourceLine,
                    "laneId" to event.laneId,
                    "confidence" to event.confidence,
                )
            },
            "calls" to trace.calls.map { call ->
                mapOf(
                    "invocationId" to call.invocationId,
                    "parentInvocationId" to call.parentInvocationId,
                    "callerOwnerType" to call.callerOwnerType,
                    "calleeOwnerType" to call.calleeOwnerType,
                    "callSiteId" to call.callSiteId,
                    "callEntryId" to call.callEntryId,
                    "returnEntryId" to call.returnEntryId,
                    "status" to call.status.name.lowercase(),
                    "invocationKind" to call.invocationKind.name.lowercase(),
                    "sourceFile" to call.sourceFile,
                    "sourceLine" to call.sourceLine,
                    "receiverRole" to call.receiverRole,
                    "evidence" to call.evidence.map { it.name.lowercase() },
                )
            },
            "operations" to trace.operations.map { operation ->
                mapOf(
                    "id" to operation.id,
                    "kind" to operation.kind.name.lowercase(),
                    "entryId" to operation.entryId,
                    "invocationId" to operation.invocationId,
                    "sourceOperationId" to operation.sourceOperationId,
                    "sourceLogSiteId" to operation.sourceLogSiteId,
                    "methodId" to operation.methodId,
                    "ownerType" to operation.ownerType,
                    "sourceFile" to operation.sourceFile,
                    "sourceLine" to operation.sourceLine,
                )
            },
        )
    }

    private fun validateDiagramInput(args: Map<String, Any?>): String? {
        return listOfNotNull(
            validateOptionalString(args["title"], "title", MAX_DIAGRAM_TITLE_CHARS),
            validateOptionalString(args["entryActor"], "entryActor", MAX_DIAGRAM_ID_CHARS),
            validateOptionalString(args["exitActor"], "exitActor", MAX_DIAGRAM_ID_CHARS),
            validateLegacyDiagramTags(args["tags"]),
            validateDiagramComponents(args["components"]),
            validateMergedDiagramTags(args["mergedTags"]),
            validateDiagramActors(args["actors"]),
            validateSourceSiteOverrides(args["sourceSiteOverrides"]),
            validateDiagramRules(args["rules"]),
            validateStringList(args["lifelineOrder"], "lifelineOrder", MAX_DIAGRAM_COMPONENTS),
            validateMessageOverrides(args["messageOverrides"]),
            validateManualDocument(args["manualDocument"]),
        ).firstOrNull()
    }

    private fun validateDiagramRules(value: Any?): String? {
        val shape = validateListShape(value, "rules", MAX_DIAGRAM_COMPONENTS)
        if (shape != null || value !is List<*>) return shape
        return value.withIndex().firstNotNullOfOrNull { (index, item) ->
            if (item !is Map<*, *>) return@firstNotNullOfOrNull "rules[$index] must be an object"
            validateRequiredString(item["id"], "rules[$index].id", MAX_DIAGRAM_ID_CHARS) ?:
                validateRequiredString(item["pattern"], "rules[$index].pattern", MAX_DIAGRAM_LABEL_CHARS) ?:
                validateOptionalString(item["fromTemplate"], "rules[$index].fromTemplate", MAX_DIAGRAM_LABEL_CHARS) ?:
                validateOptionalString(item["toTemplate"], "rules[$index].toTemplate", MAX_DIAGRAM_LABEL_CHARS) ?:
                validateOptionalString(item["labelTemplate"], "rules[$index].labelTemplate", MAX_DIAGRAM_LABEL_CHARS) ?:
                validateRuleEndpoint(item["fromEndpoint"], "rules[$index].fromEndpoint") ?:
                validateRuleEndpoint(item["toEndpoint"], "rules[$index].toEndpoint")
        }
    }

    private fun validateRuleEndpoint(value: Any?, field: String): String? {
        if (value == null) return null
        if (value !is Map<*, *>) return "$field must be an object"
        return when (value["kind"]?.toString()?.lowercase()) {
            "existing" -> validateRequiredString(value["participantId"], "$field.participantId", MAX_DIAGRAM_ID_CHARS)
            "currententry" -> null
            "actor" -> validateRequiredString(value["id"], "$field.id", MAX_DIAGRAM_ID_CHARS) ?:
                validateRequiredString(value["label"], "$field.label", MAX_DIAGRAM_LABEL_CHARS)
            "captured" -> validateRequiredString(value["captureName"], "$field.captureName", MAX_DIAGRAM_ID_CHARS) ?:
                validateRuleBindings(value["bindings"], "$field.bindings")
            else -> "$field.kind must be existing, currentEntry, captured, or actor"
        }
    }

    private fun validateRuleBindings(value: Any?, field: String): String? {
        val shape = validateListShape(value, field, MAX_DIAGRAM_COMPONENTS)
        if (shape != null || value !is List<*>) return shape
        return value.withIndex().firstNotNullOfOrNull { (index, item) ->
            if (item !is Map<*, *>) "$field[$index] must be an object" else
                validateRequiredString(item["capturedValue"], "$field[$index].capturedValue", MAX_DIAGRAM_LABEL_CHARS) ?:
                    validateRequiredString(item["participantId"], "$field[$index].participantId", MAX_DIAGRAM_ID_CHARS)
        }
    }

    private fun validateSourceSiteOverrides(value: Any?): String? {
        val shape = validateListShape(value, "sourceSiteOverrides", MAX_DIAGRAM_COMPONENTS)
        if (shape != null || value !is List<*>) return shape
        return value.withIndex().firstNotNullOfOrNull { (index, item) ->
            if (item !is Map<*, *>) return@firstNotNullOfOrNull "sourceSiteOverrides[$index] must be an object"
            listOfNotNull(
                validateRequiredString(item["sourceLogSiteId"], "sourceSiteOverrides[$index].sourceLogSiteId", MAX_DIAGRAM_ID_CHARS),
                if ((item["entryId"] as? Number)?.toInt()?.let { it >= 0 } == false) {
                    "sourceSiteOverrides[$index].entryId must be non-negative"
                } else {
                    null
                },
            ).firstOrNull()
        }
    }

    private fun validateMessageOverrides(value: Any?): String? {
        val shape = validateListShape(value, "messageOverrides", MAX_DIAGRAM_COMPONENTS)
        if (shape != null || value !is List<*>) return shape
        return value.withIndex().firstNotNullOfOrNull { (index, item) ->
            if (item !is Map<*, *>) return@firstNotNullOfOrNull "messageOverrides[$index] must be an object"
            val origin = item["origin"] as? Map<*, *> ?: return@firstNotNullOfOrNull "messageOverrides[$index].origin must be an object"
            listOfNotNull(
                if ((origin["entryId"] as? Number)?.toInt()?.let { it >= 0 } == true) null
                else "messageOverrides[$index].origin.entryId must be a non-negative integer",
                validateOptionalString(origin["ruleId"], "messageOverrides[$index].origin.ruleId", MAX_DIAGRAM_ID_CHARS),
                validateOptionalString(origin["sourceOperationId"], "messageOverrides[$index].origin.sourceOperationId", MAX_DIAGRAM_ID_CHARS),
                validateOptionalString(origin["sourceLogSiteId"], "messageOverrides[$index].origin.sourceLogSiteId", MAX_DIAGRAM_ID_CHARS),
                validateOptionalString(origin["invocationId"], "messageOverrides[$index].origin.invocationId", MAX_DIAGRAM_ID_CHARS),
                validateOptionalString(origin["manualInteractionId"], "messageOverrides[$index].origin.manualInteractionId", MAX_DIAGRAM_ID_CHARS),
                validateOptionalString(item["fromParticipantId"], "messageOverrides[$index].fromParticipantId", MAX_DIAGRAM_ID_CHARS),
                validateOptionalString(item["toParticipantId"], "messageOverrides[$index].toParticipantId", MAX_DIAGRAM_ID_CHARS),
                validateOptionalString(item["label"], "messageOverrides[$index].label", MAX_DIAGRAM_LABEL_CHARS),
            ).firstOrNull()
        }
    }

    private fun validateManualDocument(value: Any?): String? {
        if (value == null) return null
        if (value !is Map<*, *>) return "manualDocument must be an object"
        val interactions = value["interactions"] ?: emptyList<Any?>()
        val shape = validateListShape(interactions, "manualDocument.interactions", MAX_DIAGRAM_MESSAGES)
        if (shape != null || interactions !is List<*>) return shape
        val ids = interactions.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
        if (ids.size != interactions.size || duplicateOf(ids) != null) return "manualDocument interactions must have unique ids"
        val interactionError = interactions.withIndex().firstNotNullOfOrNull { (index, item) ->
            if (item !is Map<*, *>) return@firstNotNullOfOrNull "manualDocument.interactions[$index] must be an object"
            listOfNotNull(
                validateRequiredString(item["id"], "manualDocument.interactions[$index].id", MAX_DIAGRAM_ID_CHARS),
                validateRequiredString(item["fromParticipantId"], "manualDocument.interactions[$index].fromParticipantId", MAX_DIAGRAM_ID_CHARS),
                validateRequiredString(item["toParticipantId"], "manualDocument.interactions[$index].toParticipantId", MAX_DIAGRAM_ID_CHARS),
                validateNonNegativeIntList(item["sourceEntryIds"], "manualDocument.interactions[$index].sourceEntryIds", MAX_DIAGRAM_MESSAGES),
            ).firstOrNull()
        }
        if (interactionError != null) return interactionError
        val interactionIds = ids.toSet()
        return validateManualReferences(value, "groups", MAX_DIAGRAM_COMPONENTS) { item, index ->
            val idError = validateRequiredString(item["id"], "manualDocument.groups[$index].id", MAX_DIAGRAM_ID_CHARS)
            val labelError = validateRequiredString(item["label"], "manualDocument.groups[$index].label", MAX_DIAGRAM_LABEL_CHARS)
            val referencesError = validateStringList(item["interactionIds"], "manualDocument.groups[$index].interactionIds", MAX_DIAGRAM_MESSAGES)
            idError ?: labelError ?: referencesError ?: (item["interactionIds"] as? List<*>)
                ?.filterIsInstance<String>()?.firstOrNull { it !in interactionIds }
                ?.let { "manualDocument.groups[$index] references unknown interaction: $it" }
        } ?: validateManualReferences(value, "notes", MAX_DIAGRAM_MESSAGES) { item, index ->
            validateRequiredString(item["id"], "manualDocument.notes[$index].id", MAX_DIAGRAM_ID_CHARS) ?:
                validateRequiredString(item["participantId"], "manualDocument.notes[$index].participantId", MAX_DIAGRAM_ID_CHARS) ?:
                validateRequiredString(item["afterInteractionId"], "manualDocument.notes[$index].afterInteractionId", MAX_DIAGRAM_ID_CHARS) ?:
                validateOptionalString(item["text"], "manualDocument.notes[$index].text", MAX_DIAGRAM_LABEL_CHARS) ?:
                (item["afterInteractionId"] as? String)?.takeIf { it !in interactionIds }
                    ?.let { "manualDocument.notes[$index] references unknown interaction: $it" }
        } ?: validateManualReferences(value, "activations", MAX_DIAGRAM_MESSAGES) { item, index ->
            validateRequiredString(item["id"], "manualDocument.activations[$index].id", MAX_DIAGRAM_ID_CHARS) ?:
                validateRequiredString(item["participantId"], "manualDocument.activations[$index].participantId", MAX_DIAGRAM_ID_CHARS) ?:
                validateRequiredString(item["startInteractionId"], "manualDocument.activations[$index].startInteractionId", MAX_DIAGRAM_ID_CHARS) ?:
                validateRequiredString(item["endInteractionId"], "manualDocument.activations[$index].endInteractionId", MAX_DIAGRAM_ID_CHARS) ?:
                listOf(item["startInteractionId"], item["endInteractionId"]).filterIsInstance<String>().firstOrNull { it !in interactionIds }
                    ?.let { "manualDocument.activations[$index] references unknown interaction: $it" }
        }
    }

    private fun validateManualReferences(
        document: Map<*, *>, field: String, maxItems: Int,
        validate: (Map<*, *>, Int) -> String?,
    ): String? {
        val items = document[field] ?: emptyList<Any?>()
        val shape = validateListShape(items, "manualDocument.$field", maxItems)
        if (shape != null || items !is List<*>) return shape
        val ids = items.mapNotNull { (it as? Map<*, *>)?.get("id") as? String }
        if (ids.size != items.size || duplicateOf(ids) != null) return "manualDocument $field must have unique ids"
        return items.withIndex().firstNotNullOfOrNull { (index, item) ->
            if (item !is Map<*, *>) "manualDocument.$field[$index] must be an object" else validate(item, index)
        }
    }

    private fun validateOptionalString(value: Any?, field: String, maxChars: Int): String? = when {
        value == null -> null
        value !is String -> "$field must be a string"
        value.length > maxChars -> "$field must be at most $maxChars characters"
        else -> null
    }

    private fun validateRequiredString(value: Any?, field: String, maxChars: Int): String? = when {
        value !is String -> "$field must be a string"
        value.isBlank() -> "$field must not be blank"
        value.length > maxChars -> "$field must be at most $maxChars characters"
        else -> null
    }

    private fun validateListShape(value: Any?, field: String, maxItems: Int): String? = when {
        value == null -> null
        value !is List<*> -> "$field must be an array"
        value.size > maxItems -> "$field may contain at most $maxItems items"
        else -> null
    }

    private fun validateStringList(value: Any?, field: String, maxItems: Int): String? {
        val shapeError = validateListShape(value, field, maxItems)
        if (shapeError != null || value !is List<*>) return shapeError
        return value.withIndex().firstNotNullOfOrNull { (index, item) ->
            validateRequiredString(item, "$field[$index]", MAX_DIAGRAM_ID_CHARS)
        }
    }

    private fun validateNonNegativeIntList(value: Any?, field: String, maxItems: Int): String? {
        val shapeError = validateListShape(value, field, maxItems)
        if (shapeError != null || value !is List<*>) return shapeError
        return value.withIndex().firstNotNullOfOrNull { (index, item) ->
            if ((item as? Number)?.toInt()?.let { it >= 0 } == true) null else "$field[$index] must be a non-negative integer"
        }
    }

    private fun validateLegacyDiagramTags(value: Any?): String? {
        val itemError = validateStringList(value, "tags", MAX_DIAGRAM_LEGACY_TAGS)
        if (itemError != null || value !is List<*>) return itemError
        val duplicate = duplicateOf(value.filterIsInstance<String>())
        return duplicate?.let { "duplicate legacy tag id: $it" }
    }

    private fun validateDiagramComponents(value: Any?): String? {
        val shapeError = validateListShape(value, "components", MAX_DIAGRAM_COMPONENTS)
        if (shapeError != null || value !is List<*>) return shapeError
        return value.withIndex().firstNotNullOfOrNull { (index, item) ->
            validateDiagramComponent(item, index)
        }
    }

    private fun validateDiagramComponent(value: Any?, index: Int): String? {
        if (value !is Map<*, *>) return "components[$index] must be an object"
        return listOfNotNull(
            validateRequiredString(value["id"], "components[$index].id", MAX_DIAGRAM_ID_CHARS),
            validateOptionalString(value["displayName"], "components[$index].displayName", MAX_DIAGRAM_LABEL_CHARS),
            validateStringList(value["tagIds"], "components[$index].tagIds", MAX_DIAGRAM_TAGS_PER_COMPONENT),
            validateStringList(value["sourceOwnerTypes"], "components[$index].sourceOwnerTypes", MAX_DIAGRAM_TAGS_PER_COMPONENT),
        ).firstOrNull()
    }

    private fun validateMergedDiagramTags(value: Any?): String? {
        if (value == null) return null
        if (value !is Map<*, *>) return "mergedTags must be an object"
        if (value.size > MAX_DIAGRAM_COMPONENTS) return "mergedTags may contain at most $MAX_DIAGRAM_COMPONENTS entries"
        val entryError = value.entries.firstNotNullOfOrNull { (name, tags) ->
            val field = "mergedTags[${name.toString().take(MAX_DIAGRAM_ID_CHARS)}]"
            listOfNotNull(
                validateRequiredString(name, "mergedTags key", MAX_DIAGRAM_ID_CHARS),
                validateStringList(tags, field, MAX_DIAGRAM_TAGS_PER_COMPONENT),
            ).firstOrNull()
        }
        val total = value.values.filterIsInstance<List<*>>().sumOf { it.size }
        return entryError ?: if (total > MAX_DIAGRAM_TAG_REFERENCES) {
            "mergedTags may contain at most $MAX_DIAGRAM_TAG_REFERENCES tag references in total"
        } else {
            null
        }
    }

    private fun validateDiagramActors(value: Any?): String? {
        val shapeError = validateListShape(value, "actors", MAX_DIAGRAM_ACTORS)
        if (shapeError != null || value !is List<*>) return shapeError
        return value.withIndex().firstNotNullOfOrNull { (index, item) -> validateDiagramActor(item, index) }
    }

    private fun validateDiagramActor(value: Any?, index: Int): String? = when (value) {
        is String -> validateRequiredString(value, "actors[$index]", MAX_DIAGRAM_ID_CHARS)
        is Map<*, *> -> listOfNotNull(
            validateRequiredString(value["id"], "actors[$index].id", MAX_DIAGRAM_ID_CHARS),
            validateOptionalString(value["label"], "actors[$index].label", MAX_DIAGRAM_LABEL_CHARS),
            validateOptionalString(value["mirrorComponentId"], "actors[$index].mirrorComponentId", MAX_DIAGRAM_ID_CHARS),
        ).firstOrNull()
        else -> "actors[$index] must be a string or object"
    }

    /**
     * Decodes the MCP component shape without making component names identity.  IDs are the
     * durable values used by actors and saved/replayed tool calls; displayName is presentation
     * only. `mergedTags` is retained as a deliberately small compatibility shorthand.
     */
    private fun diagramComponents(args: Map<String, Any?>): List<DiagramComponent> {
        val explicit = args.mapList("components").orEmpty().mapNotNull { raw ->
            val id = raw.str("id")?.trim().orEmpty()
            if (id.isEmpty()) return@mapNotNull null
            val tags = raw.strList("tagIds").orEmpty().map(String::trim).filter(String::isNotEmpty).toSet()
            val sourceOwnerTypes = raw.strList("sourceOwnerTypes").orEmpty().map(String::trim).filter(String::isNotEmpty).toSet()
            DiagramComponent(
                id = id,
                displayName = raw.str("displayName")?.trim().takeUnless { it.isNullOrEmpty() } ?: id,
                tagIds = tags,
                enabled = raw.anyBool("enabled") ?: true,
                sourceOwnerTypes = sourceOwnerTypes,
            )
        }
        val merged = (args["mergedTags"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) ->
                val name = key?.toString()?.trim().orEmpty()
                if (name.isEmpty()) null else name to ((value as? List<*>)
                    .orEmpty().mapNotNull { it?.toString()?.trim()?.takeIf(String::isNotEmpty) }.toSet())
            }
            .orEmpty()
        if (merged.isEmpty()) return explicit
        val additions = merged.associate { it.first to it.second }
        val consumed = HashSet<String>()
        val mergedExplicit = explicit.map { component ->
            val extra = additions[component.id] ?: additions[component.displayName]
            if (extra != null) {
                consumed += component.id
                consumed += component.displayName
                component.copy(tagIds = component.tagIds + extra)
            } else {
                component
            }
        }
        return mergedExplicit + merged.filter { (name, _) -> name !in consumed }.map { (name, tags) ->
            DiagramComponent(id = name, displayName = name, tagIds = tags)
        }
    }

    /** Accept old `actors: ["User"]` and new actor objects in the same request. */
    private fun diagramActors(args: Map<String, Any?>, legacyActorNames: List<String>): List<DiagramActor> {
        val raw = args["actors"] as? List<*> ?: return legacyActorNames.map { DiagramActor(it, it) }
        return raw.mapNotNull { item -> when (item) {
            is String -> item.trim().takeIf(String::isNotEmpty)?.let { DiagramActor(it, it) }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = item as? Map<String, Any?> ?: return@mapNotNull null
                val id = map.str("id")?.trim().orEmpty()
                if (id.isEmpty()) return@mapNotNull null
                val direction = when (map.str("mirrorDirection")?.lowercase()) {
                    "inbound" -> MirrorDirection.INBOUND
                    "outbound" -> MirrorDirection.OUTBOUND
                    else -> MirrorDirection.BOTH
                }
                DiagramActor(
                    id = id,
                    label = map.str("label")?.trim().takeUnless { it.isNullOrEmpty() } ?: id,
                    mirrorComponentId = map.str("mirrorComponentId")?.trim()?.takeIf(String::isNotEmpty),
                    mirrorDirection = direction,
                )
            }
            else -> null
        } }.distinctBy { it.id }
    }

    private fun diagramSourceSiteOverrides(args: Map<String, Any?>): List<DiagramSourceSiteOverride> =
        (args["sourceSiteOverrides"] as? List<*>)?.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val entryId = (map["entryId"] as? Number)?.toInt() ?: return@mapNotNull null
            val sourceLogSiteId = map["sourceLogSiteId"]?.toString()?.trim().orEmpty()
            if (sourceLogSiteId.isEmpty()) return@mapNotNull null
            val edgeOrdinal = (map["edgeOrdinal"] as? Number)?.toInt() ?: 0
            DiagramSourceSiteOverride(entryId, sourceLogSiteId, edgeOrdinal)
        }?.take(MAX_DIAGRAM_COMPONENTS).orEmpty()

    private fun diagramRules(args: Map<String, Any?>): List<DiagramMessageRule> =
        args.mapList("rules").orEmpty().mapNotNull { map ->
            val id = map.str("id")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val pattern = map.str("pattern")?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            DiagramMessageRule(
                id = id,
                pattern = pattern,
                enabled = map.anyBool("enabled") ?: true,
                fromTemplate = map.str("fromTemplate").orEmpty(),
                toTemplate = map.str("toTemplate").orEmpty(),
                labelTemplate = map.str("labelTemplate").orEmpty(),
                fromEndpoint = diagramRuleEndpoint(map["fromEndpoint"]),
                toEndpoint = diagramRuleEndpoint(map["toEndpoint"]),
            )
        }.take(MAX_DIAGRAM_COMPONENTS)

    private fun diagramRuleEndpoint(value: Any?): DiagramRuleEndpoint? {
        val map = value as? Map<*, *> ?: return null
        return when (map["kind"]?.toString()?.lowercase()) {
            "existing" -> map["participantId"]?.toString()?.takeIf(String::isNotBlank)
                ?.let(DiagramRuleEndpoint::ExistingParticipant)
            "currententry" -> DiagramRuleEndpoint.CurrentEntry
            "actor" -> {
                val id = map["id"]?.toString()?.takeIf(String::isNotBlank) ?: return null
                val label = map["label"]?.toString()?.takeIf(String::isNotBlank) ?: return null
                DiagramRuleEndpoint.ExplicitActor(id, label)
            }
            "captured" -> {
                val name = map["captureName"]?.toString()?.takeIf(String::isNotBlank) ?: return null
                val bindings = (map["bindings"] as? List<*>).orEmpty().mapNotNull { item ->
                    val binding = item as? Map<*, *> ?: return@mapNotNull null
                    val captured = binding["capturedValue"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    val participant = binding["participantId"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                    DiagramRuleCaptureBinding(captured, participant)
                }
                DiagramRuleEndpoint.CapturedValue(name, bindings)
            }
            else -> null
        }
    }

    private fun diagramMessageOverrides(args: Map<String, Any?>): List<DiagramMessageOverride> =
        args.mapList("messageOverrides").orEmpty().mapNotNull { map ->
            val origin = (map["origin"] as? Map<*, *>) ?: return@mapNotNull null
            val entryId = (origin["entryId"] as? Number)?.toInt() ?: return@mapNotNull null
            val parameters = (map["parameters"] as? List<*>)?.mapNotNull { item ->
                val parameter = item as? Map<*, *> ?: return@mapNotNull null
                DiagramParameter(parameter["name"]?.toString().orEmpty(), parameter["value"]?.toString().orEmpty())
            }
            DiagramMessageOverride(
                origin = MessageOriginKey(
                    entryId = entryId, ruleId = origin["ruleId"]?.toString(),
                    sourceOperationId = origin["sourceOperationId"]?.toString(), sourceLogSiteId = origin["sourceLogSiteId"]?.toString(),
                    invocationId = origin["invocationId"]?.toString(), manualInteractionId = origin["manualInteractionId"]?.toString(),
                    generatedOrdinal = (origin["generatedOrdinal"] as? Number)?.toInt() ?: 0,
                ),
                enabled = map.anyBool("enabled") ?: true, fromParticipantId = map.str("fromParticipantId"),
                toParticipantId = map.str("toParticipantId"), label = map.str("label"),
                kind = map.str("kind")?.uppercase()?.let { token -> MessageKind.entries.firstOrNull { it.name == token } },
                parameters = parameters,
            )
        }.take(MAX_DIAGRAM_COMPONENTS)

    private fun diagramManualDocument(args: Map<String, Any?>): ManualDiagramDocument {
        val document = args["manualDocument"] as? Map<*, *> ?: return ManualDiagramDocument()
        val interactions = (document["interactions"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val from = map["fromParticipantId"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val to = map["toParticipantId"]?.toString()?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val entries = (map["sourceEntryIds"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }?.toSet().orEmpty()
            val parameters = (map["parameters"] as? List<*>).orEmpty().mapNotNull { parameter ->
                val raw = parameter as? Map<*, *> ?: return@mapNotNull null
                DiagramParameter(raw["name"]?.toString().orEmpty(), raw["value"]?.toString().orEmpty())
            }
            ManualDiagramInteraction(id, entries, from, to, map["operation"]?.toString().orEmpty(), parameters,
                map["result"]?.toString(), map["label"]?.toString(),
                map["kind"]?.toString()?.uppercase()?.let { kind -> MessageKind.entries.firstOrNull { it.name == kind } } ?: MessageKind.CALL,
                (map["enabled"] as? Boolean) ?: true, (map["order"] as? Number)?.toLong() ?: 0L)
        }
        val groups = (document["groups"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString() ?: return@mapNotNull null
            ManualDiagramGroup(id, map["label"]?.toString().orEmpty(), (map["interactionIds"] as? List<*>)?.mapNotNull { it as? String }.orEmpty(),
                (map["enabled"] as? Boolean) ?: true)
        }
        val notes = (document["notes"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString() ?: return@mapNotNull null
            val participant = map["participantId"]?.toString() ?: return@mapNotNull null
            val after = map["afterInteractionId"]?.toString() ?: return@mapNotNull null
            ManualDiagramNote(id, participant, after, map["text"]?.toString().orEmpty(), (map["isError"] as? Boolean) ?: false, (map["enabled"] as? Boolean) ?: true)
        }
        val activations = (document["activations"] as? List<*>).orEmpty().mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val id = map["id"]?.toString() ?: return@mapNotNull null
            val participant = map["participantId"]?.toString() ?: return@mapNotNull null
            val start = map["startInteractionId"]?.toString() ?: return@mapNotNull null
            val end = map["endInteractionId"]?.toString() ?: return@mapNotNull null
            ManualDiagramActivation(id, participant, start, end, (map["enabled"] as? Boolean) ?: true)
        }
        return ManualDiagramDocument(interactions, groups, notes, activations)
    }

    private fun originMap(origin: MessageOriginKey): Map<String, Any?> = mapOf(
        "entryId" to origin.entryId, "ruleId" to origin.ruleId, "sourceOperationId" to origin.sourceOperationId,
        "sourceLogSiteId" to origin.sourceLogSiteId, "invocationId" to origin.invocationId,
        "manualInteractionId" to origin.manualInteractionId, "generatedOrdinal" to origin.generatedOrdinal,
    )

    private fun diagramRuleMap(rule: DiagramMessageRule): Map<String, Any?> = mapOf(
        "id" to rule.id, "pattern" to rule.pattern, "enabled" to rule.enabled,
        "fromTemplate" to rule.fromTemplate, "toTemplate" to rule.toTemplate, "labelTemplate" to rule.labelTemplate,
        "fromEndpoint" to rule.fromEndpoint?.let(::diagramRuleEndpointMap),
        "toEndpoint" to rule.toEndpoint?.let(::diagramRuleEndpointMap),
    )

    private fun diagramRuleEndpointMap(endpoint: DiagramRuleEndpoint): Map<String, Any?> = when (endpoint) {
        is DiagramRuleEndpoint.ExistingParticipant -> mapOf("kind" to "existing", "participantId" to endpoint.participantId)
        DiagramRuleEndpoint.CurrentEntry -> mapOf("kind" to "currentEntry")
        is DiagramRuleEndpoint.CapturedValue -> mapOf(
            "kind" to "captured", "captureName" to endpoint.captureName,
            "bindings" to endpoint.bindings.map { mapOf("capturedValue" to it.capturedValue, "participantId" to it.participantId) },
        )
        is DiagramRuleEndpoint.ExplicitActor -> mapOf("kind" to "actor", "id" to endpoint.id, "label" to endpoint.label)
    }

    private fun messageOverrideMap(override: DiagramMessageOverride): Map<String, Any?> = mapOf(
        "origin" to originMap(override.origin), "enabled" to override.enabled,
        "fromParticipantId" to override.fromParticipantId, "toParticipantId" to override.toParticipantId,
        "label" to override.label, "kind" to override.kind?.name?.lowercase(),
        "parameters" to override.parameters?.map { mapOf("name" to it.name, "value" to it.value) },
    )

    private fun manualDocumentMap(document: ManualDiagramDocument): Map<String, Any?> = mapOf(
        "interactions" to document.interactions.map { interaction -> mapOf(
            "id" to interaction.id, "sourceEntryIds" to interaction.sourceEntryIds.sorted(), "fromParticipantId" to interaction.fromParticipantId,
            "toParticipantId" to interaction.toParticipantId, "operation" to interaction.operation,
            "parameters" to interaction.parameters.map { mapOf("name" to it.name, "value" to it.value) },
            "result" to interaction.result, "label" to interaction.label, "kind" to interaction.kind.name.lowercase(),
            "enabled" to interaction.enabled, "order" to interaction.order,
        ) },
        "groups" to document.groups.map { mapOf("id" to it.id, "label" to it.label, "interactionIds" to it.interactionIds, "enabled" to it.enabled) },
        "notes" to document.notes.map { mapOf("id" to it.id, "participantId" to it.participantId, "afterInteractionId" to it.afterInteractionId, "text" to it.text, "isError" to it.isError, "enabled" to it.enabled) },
        "activations" to document.activations.map { mapOf("id" to it.id, "participantId" to it.participantId, "startInteractionId" to it.startInteractionId, "endInteractionId" to it.endInteractionId, "enabled" to it.enabled) },
    )

    private fun rawDiagramActorIds(args: Map<String, Any?>): List<String> =
        (args["actors"] as? List<*>).orEmpty().mapNotNull { item -> when (item) {
            is String -> item.trim().takeIf(String::isNotEmpty)
            is Map<*, *> -> (item["id"] as? String)?.trim()?.takeIf(String::isNotEmpty)
            else -> null
        } }

    private fun boundedDiagramWarnings(warnings: List<String>): List<String> =
        warnings.take(MAX_DIAGRAM_WARNINGS).map { it.take(MAX_DIAGRAM_WARNING_CHARS) }

    /** Same bounded, high-confidence mapping as the interactive workspace coordinator. */
    private fun sourceInteractionResolver(
        spec: SeqDiagramSpec,
        index: SourceIndex?,
    ): (LogEntry) -> List<DiagramSourceInteraction> {
        if (!spec.sourceEnrichment.enabled || spec.components.isEmpty()) return { emptyList() }
        if (index == null) return { emptyList() }
        val resolver = SourceEnrichmentResolver(index)
        val cache = DiagramSourceLruCache<List<DiagramSourceInteraction>>(MAX_MCP_DIAGRAM_SOURCE_CACHE_ENTRIES)

        fun componentId(owner: String?): String? {
            val cleanOwner = owner?.trim().orEmpty()
            if (cleanOwner.isEmpty()) return null
            val normalized = cleanOwner.substringBefore('$')
            val explicit = spec.components.filter { component ->
                component.enabled && (cleanOwner in component.sourceOwnerTypes || normalized in component.sourceOwnerTypes)
            }.distinctBy { it.id }
            if (explicit.size == 1) return explicit.single().id
            if (explicit.size > 1) return null
            val simple = normalized.substringAfterLast('.')
            val heuristic = spec.components.filter { component ->
                component.enabled && (
                    component.id == cleanOwner || component.id == normalized ||
                        component.displayName == cleanOwner || component.displayName == normalized ||
                        component.displayName.substringAfterLast('.') == simple ||
                        component.tagIds.any { tag ->
                            tag == cleanOwner || tag == normalized || tag.substringAfterLast('.') == simple
                        }
                )
            }.distinctBy { it.id }
            return heuristic.singleOrNull()?.id
        }
        data class StackFrame(val ownerType: String, val methodName: String, val fileName: String, val line: Int)
        val stackFramePattern = Regex(
            """^\s*at\s+(com\.[\w$]+(?:\.[\w$]+)*)\.([\w$<>]+)\(([^():]+\.(?:java|kt)):(\d+)\)\s*$""",
        )
        var previousStackFrame: StackFrame? = null
        return { entry ->
            val stackFrame = stackFramePattern.matchEntire(entry.msg)?.let { match ->
                StackFrame(
                    match.groupValues[1], match.groupValues[2], match.groupValues[3],
                    match.groupValues[4].toIntOrNull() ?: return@let null,
                )
            }
            if (stackFrame != null) {
                val callee = previousStackFrame
                previousStackFrame = stackFrame
                if (callee == null) {
                    emptyList()
                } else {
                    val from = componentId(stackFrame.ownerType)
                    val to = componentId(callee.ownerType)
                    if (from == null || to == null) emptyList() else listOf(
                        DiagramSourceInteraction(
                            fromComponentId = from,
                            toComponentId = to,
                            label = "${callee.ownerType}.${callee.methodName}(${callee.fileName}:${callee.line})",
                            allowSelfCall = stackFrame.ownerType == callee.ownerType &&
                                stackFrame.methodName == callee.methodName,
                        ),
                    )
                }
            } else {
                previousStackFrame = null
                val key = diagramSourceCacheKey(entry.tag, entry.msg)
                cache[key] ?: resolver.resolveOneHop(entry, limit = MAX_SOURCE_INTERACTIONS_PER_ENTRY)
                    .mapNotNull { call ->
                        if (call.confidence < MIN_DIAGRAM_SOURCE_CONFIDENCE) return@mapNotNull null
                        val from = componentId(call.sourceOwnerType)
                        val to = componentId(call.targetOwnerType)
                        if (from == null || to == null) null else DiagramSourceInteraction(
                            fromComponentId = from,
                            toComponentId = to,
                            label = "${call.targetOwnerType}.${call.targetMethodSignature}".take(MAX_DIAGRAM_LABEL_CHARS),
                            returnLabel = (call.observedReturnLabel ?: call.declaredReturnType)
                                ?.takeIf { spec.sourceEnrichment.addReturnArrows }
                                ?.take(MAX_DIAGRAM_LABEL_CHARS),
                        )
                    }
                    .distinct()
                    .take(MAX_SOURCE_INTERACTIONS_PER_ENTRY)
                    .also { cache[key] = it }
            }
        }
    }

    private fun sourceTraceResolver(
        spec: SeqDiagramSpec,
        index: SourceIndex?,
    ): ((List<LogEntry>) -> DiagramResolvedTrace)? {
        if (!spec.sourceEnrichment.enabled || index == null || index.version != SOURCE_INDEX_VERSION) return null
        val engine = SourceTraceInferenceEngine(index)
        return { entries -> engine.resolve(entries, sourceSiteOverrides = spec.sourceSiteOverrides) }
    }

    private fun sourceEnrichmentAvailabilityWarnings(spec: SeqDiagramSpec, index: SourceIndex?): List<String> = when {
        !spec.sourceEnrichment.enabled -> emptyList()
        index == null -> listOf("Source enrichment unavailable: no source index is loaded.")
        index.version != SOURCE_INDEX_VERSION -> listOf("Source enrichment unavailable: the loaded source index is stale.")
        else -> emptyList()
    }

    private fun diagramCoverageMap(diagram: SeqDiagram): Map<String, Int> = mapOf(
        "scannedEntries" to diagram.coverage.scannedEntries,
        "shownEntries" to diagram.coverage.shownEntries,
        "groupedEntries" to diagram.coverage.groupedEntries,
        "hiddenEntries" to diagram.coverage.hiddenEntries,
        "representedEntries" to diagram.coverage.representedEntries,
    )

    private fun sourceEnrichmentMap(spec: SeqDiagramSpec, index: SourceIndex?): Map<String, Any> = mapOf(
        "enabled" to spec.sourceEnrichment.enabled,
        // Keep `available` backwards-compatible as "an index is loaded"; expose the stronger
        // compatibility result separately so MCP callers can distinguish a stale cache from no
        // configured source index.
        "available" to (index != null),
        "mode" to "sourceTrace",
        "traceAvailable" to (index?.version == SOURCE_INDEX_VERSION),
        "indexVersion" to (index?.version ?: -1),
        "directCallDepth" to spec.sourceEnrichment.directCallDepth,
        "addReturnArrows" to spec.sourceEnrichment.addReturnArrows,
    )

    private fun getCaseRoute(id: String): Map<String, Any?> {
        if (id.isBlank()) return mapOf("error" to "missing id")
        val record = caseSearch.getCase(id) ?: return mapOf("error" to "no such case: $id")
        val text = CaseIndexer.readCaseText(record) ?: return mapOf("error" to "case note is no longer readable on disk: $id")
        return mapOf(
            "id" to record.id,
            "title" to record.title,
            "text" to text,
            "sourcePath" to record.sourcePath,
            "appVersion" to record.appVersion,
            "decisiveTags" to record.decisiveTags,
        )
    }

    // Writes onto the tab's existing Annotations via the same upAnn path every other annotation
    // mutation uses, so autosave and note re-export pick it up exactly like any other edit — this
    // does not itself write a note (add_text_note is what does that).
    private fun setCaseMetadataRoute(tabId: String, appVersion: String?, decisiveTags: List<String>?): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        if (appVersion == null && decisiveTags == null) {
            return mapOf("error" to "provide appVersion and/or decisiveTags")
        }
        appState.upAnn(tabId) { t ->
            t.copy(
                annotations = t.annotations.copy(
                    appVersion = appVersion?.takeIf { it.isNotBlank() } ?: t.annotations.appVersion,
                    decisiveTags = decisiveTags ?: t.annotations.decisiveTags,
                ),
            )
        }
        val updated = appState.tab(tabId)?.annotations
        return mapOf(
            "ok" to true,
            "tabId" to tabId,
            "appVersion" to updated?.appVersion,
            "decisiveTags" to updated?.decisiveTags,
        )
    }

    private fun reindexCasesRoute(): Map<String, Any?> {
        caseSearch.reindexAll()
        return mapOf("ok" to true)
    }

    private fun caseSummaryToMap(summary: CaseSummary): Map<String, Any?> = mapOf(
        "id" to summary.id,
        "title" to summary.title,
        "description" to summary.descriptionSnippet,
        "matchedTags" to summary.matchedTags,
        "score" to summary.score,
        "appVersion" to summary.appVersion,
    )

    // Hex "#RRGGBB" / "#AARRGGBB" (leading # optional) → Compose Color; null when malformed.
    private fun parseHexColor(hex: String): Color? {
        val h = hex.removePrefix("#")
        val argb = when (h.length) {
            HEX_RGB_LEN -> OPAQUE_ALPHA_MASK or (h.toLongOrNull(HEX_RADIX) ?: return null)
            HEX_ARGB_LEN -> h.toLongOrNull(HEX_RADIX) ?: return null
            else -> return null
        }
        return Color(argb.toInt())
    }

    private fun colorToHex(c: Color): String = "#%02X%02X%02X%02X".format(
        c.alpha.toColorChannel(), c.red.toColorChannel(), c.green.toColorChannel(), c.blue.toColorChannel(),
    )

    private fun Float.toColorChannel(): Int = (this * COLOR_CHANNEL_MAX_F).roundToInt().coerceIn(0, COLOR_CHANNEL_MAX)

    private fun closeTab(tabId: String): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        appState.closeTab(tabId)
        return mapOf("ok" to true)
    }

    private fun getFilter(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        return filterToMap(tab.filter)
    }

    /**
     * Read the sequence detector's raw-log result without changing UI state.  Rendering's
     * computeItems() intentionally runs on filtered data; this route instead scans tab.logData so
     * an agent can reason about every occurrence before deciding whether to alter a filter.
     */
    private fun getSequenceSummary(
        tabId: String,
        sequenceId: String?,
        offset: Int,
        limit: Int,
    ): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val definitions = tab.filter.sequences.filter { it.enabled }.sortedBy { it.priority }
        val prior = sequenceSummaryCache
        val cacheHit = prior != null && prior.logData === tab.logData && prior.definitions == definitions
        val analysis = if (cacheHit) {
            requireNotNull(prior)
        } else {
            SequenceSummaryCache(tab.logData, definitions, computeSequenceOccurrences(tab.logData, definitions)).also {
                sequenceSummaryCache = it
            }
        }
        val counts = analysis.occurrences.groupingBy { it.def.id }.eachCount()
        val summaries = definitions.map { def ->
            sequenceDefToMap(def) + mapOf("occurrenceCount" to (counts[def.id] ?: 0))
        }
        if (sequenceId.isNullOrBlank()) {
            return mapOf(
                "tabId" to tabId,
                "rawLineCount" to tab.logData.size,
                "cacheHit" to cacheHit,
                "sequences" to summaries,
            )
        }
        val selected = definitions.firstOrNull { it.id == sequenceId }
            ?: return mapOf("error" to "no enabled sequence: $sequenceId")
        val occurrences = analysis.occurrences.filter { it.def.id == selected.id }
        val safeOffset = offset.coerceAtLeast(0).coerceAtMost(occurrences.size)
        val safeLimit = limit.coerceIn(1, MAX_SEQUENCE_OCCURRENCE_LIMIT)
        return mapOf(
            "tabId" to tabId,
            "rawLineCount" to tab.logData.size,
            "cacheHit" to cacheHit,
            "sequence" to (sequenceDefToMap(selected) + mapOf("occurrenceCount" to occurrences.size)),
            "totalCount" to occurrences.size,
            "offset" to safeOffset,
            "limit" to safeLimit,
            "occurrences" to occurrences.drop(safeOffset).take(safeLimit).map { occurrenceToMap(it, tab.logData) },
        )
    }

    // This mirrors SeqComputer's start/end semantics, including its important fallback: a
    // missing end closes at the next start of *any* enabled definition, while a literal end is
    // accepted unless a later start of the same definition precedes it.  Keep the compact DTO
    // independent from SeqGroup because SeqGroup is shaped for folded rendering rather than an
    // agent-facing occurrence list with end reasons and arbitrary nesting depth.
    @Suppress("CyclomaticComplexMethod") // Mirrors SeqComputer's matching/end/nesting algorithm in one scan.
    private fun computeSequenceOccurrences(data: List<LogEntry>, definitions: List<SequenceDef>): List<SequenceOccurrence> {
        if (data.isEmpty() || definitions.isEmpty()) return emptyList()

        data class Candidate(val startIndex: Int, val definition: SequenceDef, var endExclusive: Int = 0, var parent: Int = -1)

        val regexContext = RegexEvaluationContext()

        fun matches(entry: LogEntry, text: String, regex: Boolean, tag: String?): Boolean =
            (tag == null || entry.tag == tag) && containsPattern("${entry.tag} ${entry.msg}", text, regex, regexContext = regexContext)

        val candidates = ArrayList<Candidate>()
        val endIndices = HashMap<String, MutableList<Int>>()
        val endingDefinitions = definitions.filter { !it.endMatchText.isNullOrBlank() }
        data.forEachIndexed { index, entry ->
            definitions.firstOrNull { matches(entry, it.matchText, it.isRegex, it.tag) }
                ?.let { candidates += Candidate(index, it) }
            endingDefinitions.forEach { def ->
                if (matches(entry, def.endMatchText!!, def.endIsRegex, def.endTag)) {
                    endIndices.getOrPut(def.id) { mutableListOf() } += index
                }
            }
        }
        if (candidates.isEmpty()) return emptyList()

        fun firstAfter(indices: List<Int>, index: Int): Int? {
            var low = 0
            var high = indices.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (indices[mid] <= index) low = mid + 1 else high = mid
            }
            return indices.getOrNull(low)
        }
        val nextSameDefinition = IntArray(candidates.size) { -1 }
        val nextIndexByDefinition = HashMap<String, Int>()
        for (index in candidates.indices.reversed()) {
            val candidate = candidates[index]
            nextSameDefinition[index] = nextIndexByDefinition[candidate.definition.id] ?: -1
            nextIndexByDefinition[candidate.definition.id] = candidate.startIndex
        }
        val endReasons = ArrayList<String>(candidates.size)
        candidates.forEachIndexed { index, candidate ->
            val fallback = candidates.getOrNull(index + 1)?.startIndex ?: data.size
            val literalEnd = candidate.definition.endMatchText?.takeIf { it.isNotBlank() }
                ?.let { firstAfter(endIndices[candidate.definition.id].orEmpty(), candidate.startIndex) }
                ?.takeIf { nextSameDefinition[index] < 0 || it < nextSameDefinition[index] }
            candidate.endExclusive = (literalEnd?.plus(1) ?: fallback)
            endReasons += when {
                literalEnd != null -> "end_match"
                fallback < data.size -> "next_sequence_start"
                else -> "end_of_log"
            }
        }
        // Same smallest-containing-parent rule as SeqComputer.assignParents.  A stack avoids
        // comparing occurrences that have already ended while preserving its crossing-range tie
        // behavior exactly.
        val stack = ArrayList<Int>()
        candidates.forEachIndexed { index, candidate ->
            while (stack.isNotEmpty() && candidates[stack.last()].endExclusive <= candidate.startIndex) stack.removeAt(stack.lastIndex)
            var shortestLength = Int.MAX_VALUE
            stack.forEach { parentIndex ->
                val parent = candidates[parentIndex]
                if (candidate.endExclusive <= parent.endExclusive) {
                    val length = parent.endExclusive - parent.startIndex
                    if (length < shortestLength) {
                        shortestLength = length
                        candidate.parent = parentIndex
                    }
                }
            }
            stack += index
        }

        fun depth(index: Int): Int {
            var result = 0
            var parent = candidates[index].parent
            while (parent >= 0) {
                result += 1
                parent = candidates[parent].parent
            }
            return result
        }
        return candidates.indices.map { index ->
            val candidate = candidates[index]
            SequenceOccurrence(candidate.definition, candidate.startIndex, candidate.endExclusive, depth(index), endReasons[index])
        }
    }

    private fun occurrenceToMap(occurrence: SequenceOccurrence, data: List<LogEntry>): Map<String, Any?> {
        val start = data[occurrence.startIndex]
        val end = data[(occurrence.endExclusive - 1).coerceAtLeast(occurrence.startIndex)]
        return mapOf(
            "gid" to "sg_${occurrence.def.id}_${start.id}",
            "sequenceId" to occurrence.def.id,
            "startRowNumber" to (occurrence.startIndex + 1),
            "endRowNumber" to occurrence.endExclusive,
            "startIndex" to occurrence.startIndex,
            "endExclusiveIndex" to occurrence.endExclusive,
            "startLineId" to start.id,
            "endLineId" to end.id,
            "startTs" to start.ts,
            "endTs" to end.ts,
            "lineCount" to (occurrence.endExclusive - occurrence.startIndex),
            "nestingDepth" to occurrence.depth,
            "endReason" to occurrence.endReason,
        )
    }

    private fun setFilter(tabId: String, body: Map<String, Any?>): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")

        val parsed = parseFilterUpdate(body)
            .getOrElse { return mapOf("error" to (it.message ?: "invalid filter update")) }

        appState.upFlt(tabId) { f -> applyFilterUpdate(f, body, parsed) }

        return buildMap {
            put("ok", true)
            put("filter", filterToMap(appState.tab(tabId)!!.filter))
            parsed.modeWarning?.let { put("warning", it) }
        }
    }

    // Bundles every value-constrained field set_filter validates up front, so a bad value is a
    // loud error rather than a silent no-op — and specifically so a malformed `levels` can never
    // collapse to an empty set (which would hide every row while reporting {ok:true}).
    private data class ParsedFilterUpdate(
        val levels: Set<LogLevel>?,
        val mode: FilterMode?,
        val modeWarning: String?,
        val messageRules: List<MessageRule>?,
        val sequences: List<SequenceDef>?,
    )

    @Suppress("CyclomaticComplexMethod") // Each branch validates one independent optional filter field.
    private fun parseFilterUpdate(body: Map<String, Any?>): Result<ParsedFilterUpdate> = runCatching {
        val levels: Set<LogLevel>? = if (body.containsKey("levels")) {
            val keys = body.strList("levels") ?: emptyList()
            val unknown = keys.filter { k -> LogLevel.entries.none { it.key.toString() == k } }
            if (unknown.isNotEmpty()) {
                error("unknown level key(s) $unknown; valid single-char keys: ${LEVEL_KEYS.joinToString(",")}")
            }
            keys.mapNotNull { k -> LogLevel.entries.find { it.key.toString() == k } }.toSet()
        } else {
            null
        }

        val explicitMode: FilterMode? = if (body.containsKey("mode")) {
            val m = body.str("mode")
            m?.let { runCatching { FilterMode.valueOf(it) }.getOrNull() }
                ?: error("unknown mode '$m'; valid: ${FilterMode.entries.joinToString(",") { it.name }}")
        } else {
            null
        }

        // TAGS and KEYWORD are mutually exclusive base filters (passesTagOrKeywordFilter only
        // evaluates kwText/kwRegex when mode == KEYWORD) — a client that sets a regex filter
        // without also switching mode gets a silent no-op. Mirror what the UI's own Tags/Regex
        // tabs already do (FilterPanel.kt: clicking a tab sets mode as a side effect) so a plain
        // set_filter call "just works" the way clicking the matching tab would. Only a
        // non-empty/meaningful value counts as a signal, so clearing a field (activeTags: [],
        // kwText: "") never forces an unwanted mode change. An explicit `mode` in the body always
        // wins outright and skips this inference entirely.
        var modeWarning: String? = null
        val inferredMode: FilterMode? = if (explicitMode == null) {
            val tagSignal = (body.strList("activeTags")?.isNotEmpty() == true) ||
                (body.strList("pkgPrefixes")?.isNotEmpty() == true)
            val keywordSignal = (body.str("kwText")?.isNotBlank() == true) || (body.bool("kwRegex") == true)
            when {
                tagSignal && keywordSignal -> {
                    modeWarning = "both tag and keyword filter values were supplied with no explicit mode; " +
                        "mode was left unchanged — pass mode explicitly (TAGS or KEYWORD) to disambiguate"
                    null
                }
                tagSignal -> FilterMode.TAGS
                keywordSignal -> FilterMode.KEYWORD
                else -> null
            }
        } else {
            null
        }

        val rules: List<MessageRule>? = if (body.containsKey("messageRules")) {
            parseMessageRules(body.mapList("messageRules") ?: emptyList()).getOrThrow()
        } else {
            null
        }

        val seqs: List<SequenceDef>? = if (body.containsKey("sequences")) {
            parseSequences(body.mapList("sequences") ?: emptyList()).getOrThrow()
        } else {
            null
        }

        ParsedFilterUpdate(levels, explicitMode ?: inferredMode, modeWarning, rules, seqs)
    }

    private fun applyFilterUpdate(f: Filter, body: Map<String, Any?>, parsed: ParsedFilterUpdate): Filter {
        var result = f
        parsed.levels?.let { result = result.copy(levels = it) }
        STRING_SET_FIELD_SETTERS.forEach { (key, setter) ->
            if (body.containsKey(key)) body.strList(key)?.let { result = result.setter(it.toSet()) }
        }
        STRING_FIELD_SETTERS.forEach { (key, setter) ->
            if (body.containsKey(key)) body.str(key)?.let { result = result.setter(it) }
        }
        BOOL_FIELD_SETTERS.forEach { (key, setter) ->
            if (body.containsKey(key)) body.bool(key)?.let { result = result.setter(it) }
        }
        parsed.mode?.let { result = result.copy(mode = it) }
        // messageRules / sequences: a supplied list replaces the current one wholesale; the
        // clear* booleans remove all of them. This is what lets a client detect (via get_filter)
        // and undo a stale rule/sequence that was silently hiding or folding rows.
        parsed.messageRules?.let { result = result.copy(messageRules = it) }
        if (body.bool("clearMessageRules") == true) result = result.copy(messageRules = emptyList())
        parsed.sequences?.let { result = result.copy(sequences = it) }
        if (body.bool("clearSequences") == true) result = result.copy(sequences = emptyList())
        return result
    }

    // Parse client-supplied message rules; throws (captured as a Result failure) with a
    // descriptive message when a required field is missing or an enum value is unrecognized.
    private fun parseMessageRules(raw: List<Map<String, Any?>>): Result<List<MessageRule>> = runCatching {
        raw.mapIndexed { idx, m ->
            val pattern = m.str("pattern")?.takeIf { it.isNotBlank() }
                ?: error("messageRules[$idx]: missing or blank 'pattern'")
            val target = when (val t = m.str("target")) {
                null -> RuleTarget.MESSAGE
                else -> runCatching { RuleTarget.valueOf(t) }.getOrElse {
                    error("messageRules[$idx]: unknown target '$t'; valid: ${RuleTarget.entries.joinToString(",") { it.name }}")
                }
            }
            MessageRule(
                id = m.str("id")?.takeIf { it.isNotBlank() } ?: "${newId("rule")}_$idx",
                include = m.bool("include") ?: true,
                pattern = pattern,
                regex = m.bool("regex") ?: false,
                tag = m.str("tag")?.takeIf { it.isNotBlank() },
                packagePrefix = m.str("packagePrefix")?.takeIf { it.isNotBlank() },
                enabled = m.bool("enabled") ?: true,
                target = target,
            )
        }
    }

    // Parse client-supplied sequences. Colors are assigned round-robin from SEQ_COLORS (the same
    // palette the UI uses) — clients don't supply colors over MCP. Priority defaults to list order.
    private fun parseSequences(raw: List<Map<String, Any?>>): Result<List<SequenceDef>> = runCatching {
        raw.mapIndexed { idx, m ->
            val matchText = m.str("matchText")?.takeIf { it.isNotBlank() }
                ?: error("sequences[$idx]: missing or blank 'matchText'")
            SequenceDef(
                id = m.str("id")?.takeIf { it.isNotBlank() } ?: "${newId("seq")}_$idx",
                matchText = matchText,
                isRegex = m.bool("isRegex") ?: false,
                priority = m.int("priority") ?: (idx + 1),
                color = SEQ_COLORS[idx % SEQ_COLORS.size],
                enabled = m.bool("enabled") ?: true,
                tag = m.str("tag")?.takeIf { it.isNotBlank() },
                endMatchText = m.str("endMatchText")?.takeIf { it.isNotBlank() },
                endIsRegex = m.bool("endIsRegex") ?: false,
                endTag = m.str("endTag")?.takeIf { it.isNotBlank() },
            )
        }
    }

    // Appends one sequence to a tab's existing list, unlike set_filter's `sequences` field which
    // replaces the whole list wholesale — this is what "add a sequence" over MCP was missing.
    // Reuses parseSequences on a single-element list so field validation, id auto-assignment, and
    // required-field checking stay identical to set_filter's; only color and (unless the caller
    // explicitly supplied one) priority are then overridden from the tab's CURRENT sequence count,
    // so the appended sequence lands after existing ones with its own fresh palette slot instead of
    // parseSequences' single-item default of index 0.
    private fun addSequenceRoute(tabId: String, body: Map<String, Any?>): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("ok" to false, "error" to "missing tabId")
        val tab = appState.tab(tabId) ?: return mapOf("ok" to false, "error" to "no such tab: $tabId")
        val existing = tab.filter.sequences

        val base = parseSequences(listOf(body)).getOrElse {
            return mapOf("ok" to false, "error" to (it.message ?: "invalid sequence"))
        }.single()
        val newSeq = base.copy(
            color = SEQ_COLORS[existing.size % SEQ_COLORS.size],
            priority = if (body.containsKey("priority")) base.priority else existing.size + 1,
        )

        appState.upFlt(tabId) { f -> f.copy(sequences = f.sequences + newSeq) }

        return mapOf(
            "ok" to true,
            "sequence" to sequenceDefToMap(newSeq),
            "sequenceCount" to appState.tab(tabId)!!.filter.sequences.size,
        )
    }

    private fun getVisibleLines(tabId: String, limit: Int, offset: Int, fields: Set<String>?, compact: Boolean): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val items = computeItems(tab, true)
        return mapOf(
            "tabId" to tabId,
            "totalCount" to items.size,
            "items" to items.drop(offset).take(limit).map { projectItem(logItemToMap(it), fields, compact) },
        )
    }

    // Unfiltered context around a single line, read straight from the tab's parsed entries — so it
    // is unaffected by the active filter or any folding, and needs no filter-widen/restore dance.
    private fun getLineContext(tabId: String, lineId: Int, before: Int, after: Int, fields: Set<String>?, compact: Boolean): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val idx = tab.logData.indexOfEntryId(lineId)
        if (idx < 0) return mapOf("error" to "no such line id $lineId in tab $tabId")
        val from = (idx - before.coerceAtLeast(0)).coerceAtLeast(0)
        val to = (idx + after.coerceAtLeast(0)).coerceAtMost(tab.logData.lastIndex)
        return mapOf(
            "tabId" to tabId, "lineId" to lineId,
            "lines" to (from..to).map { rowMap(tab.logData[it], fields, compact) },
        )
    }

    // Distinct dotted tag-prefixes present in the full file, with counts — mirrors get_tags so a
    // client can discover valid values for pkgPrefixes / excludePkgPrefixes instead of guessing.
    private fun getPackages(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val counts = HashMap<String, Int>()
        tab.logData.forEach { e ->
            val dot = e.tag.lastIndexOf('.')
            if (dot > 0) {
                val prefix = e.tag.substring(0, dot)
                counts[prefix] = (counts[prefix] ?: 0) + 1
            }
        }
        val packages = counts.entries.sortedByDescending { it.value }
            .map { mapOf("prefix" to it.key, "count" to it.value) }
        return mapOf("packages" to packages)
    }

    private fun getSelection(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        return mapOf("tabId" to tabId, "selected" to tab.selected.sorted())
    }

    private fun setSelection(tabId: String, lineIds: List<Int>): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val validIds = lineIds.distinct().filter { it in tab.rmap }.sorted()
        appState.setSelectedRows(tabId, validIds)
        return mapOf("ok" to true, "tabId" to tabId, "selected" to validIds)
    }

    private fun toggleGroupRoute(tabId: String, gid: String): Map<String, Any?> {
        if (tabId.isBlank() || gid.isBlank()) return mapOf("error" to "missing tabId or gid")
        appState.toggleGroup(tabId, gid)
        return mapOf("ok" to true, "expanded" to (appState.tab(tabId)?.expanded?.contains(gid) ?: false))
    }

    private fun expandAllRoute(tabId: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.expandAll(tabId)
        return mapOf("ok" to true, "expanded" to (appState.tab(tabId)?.expanded?.toList()?.sorted() ?: emptyList<String>()))
    }

    private fun collapseAllRoute(tabId: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.collapseAll(tabId)
        return mapOf("ok" to true)
    }

    // addNoteBlock/addLogRefBlock return null both for a genuine failure and for the (far more
    // common in practice) deferred-add path: upAnn's overwrite-conflict gate (AppState.upAnn/
    // PendingNoteOverwrite) stashes a first-time-conflict mutation on pendingNoteOverwrite instead
    // of committing it, pending a human decision on the "Existing notes found" prompt — the add
    // isn't lost, it just isn't observable yet (see SeqDiagramCoordinator.confirm()'s comment for
    // the same distinction on the UI side). A bare "note was not created" is misleading in exactly
    // that case: an MCP caller reading it would reasonably retry or give up, when what's actually
    // needed is for the user to resolve the prompt this same call just raised. Message-level fix
    // only — the tool layer's control flow (route → null → generic error map) is unchanged.
    private fun noteNotCreatedMessage(tabId: String, kind: String): String {
        val pending = appState.pendingNoteOverwrite?.takeIf { it.tabId == tabId } ?: return "$kind was not created"
        return "$kind was not created: an \"Existing notes found\" prompt is open for this tab " +
            "(target: ${pending.targetName}) — a person needs to resolve it before more notes can be added."
    }

    private fun addTextNoteRoute(tabId: String, text: String, afterId: String?): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        val id = appState.addNoteBlock(tabId, text, afterId) ?: return mapOf("error" to noteNotCreatedMessage(tabId, "note"))
        return mapOf("ok" to true, "tabId" to tabId, "blockId" to id)
    }

    private fun addLogNoteRoute(tabId: String, lineIds: List<Int>, caption: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        val id = appState.addLogRefBlock(tabId, lineIds, caption) ?: return mapOf("error" to noteNotCreatedMessage(tabId, "log note"))
        return mapOf("ok" to true, "tabId" to tabId, "blockId" to id)
    }

    // The only writer of AnnBlock.Image outside the video panel's own "Add frame to notes" button.
    // Exactly one source must be supplied — silently preferring one over another would make a
    // caller that passed two think the wrong one had been used. The videoMs form deliberately goes
    // through addVideoFrameNote (not addImageBlock) so the block carries a real VideoFrameReference
    // and stays clickable-to-seek, exactly like a frame captured from the UI; rotation is baked in
    // the same way too, so an API capture is oriented the way the user sees the player.
    private fun addImageNoteRoute(
        tabId: String,
        imageBase64: String?,
        imagePath: String?,
        videoMs: Long?,
        caption: String,
        afterId: String?,
    ): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val sources = listOfNotNull(
            imageBase64?.takeIf { it.isNotBlank() }?.let { "imageBase64" },
            imagePath?.takeIf { it.isNotBlank() }?.let { "imagePath" },
            videoMs?.let { "videoMs" },
        )
        if (sources.size != 1) {
            return mapOf("error" to "provide exactly one of imageBase64, imagePath, videoMs (got: ${sources.size})")
        }

        if (videoMs != null) {
            val attachment = tab.attachedVideo ?: return mapOf("error" to "no video attached to tab: $tabId")
            if (!appState.isVideoPositionValid(tab, videoMs)) {
                return mapOf("error" to "video position is outside the attached video's known range")
            }
            val frame = appState.videoController(tabId)?.grabFrameAt(videoMs)
                ?: return mapOf("error" to "could not extract frame at ${videoMs}ms")
            val oriented = rotatedFramePng(frame, appState.videoRotationDegrees(tabId))
                ?: return mapOf("error" to "could not apply the video's rotation to the captured frame")
            val id = appState.addVideoFrameNote(
                tabId = tabId,
                sourceBytes = oriented,
                source = attachment.source,
                sourceLabel = attachment.sourceLabel,
                positionMs = videoMs,
                afterId = afterId,
                caption = caption,
            ) ?: return mapOf("error" to "image note was not created")
            return mapOf("ok" to true, "tabId" to tabId, "blockId" to id, "videoMs" to videoMs)
        }

        val bytes = if (imagePath != null && imagePath.isNotBlank()) {
            if (invalidPath(imagePath)) return mapOf("error" to "invalid path: $imagePath")
            // imageBytesFromFile bounds the source size and verifies it actually decodes, so an
            // archive or a raw multi-hundred-MB capture can't be pulled into the heap by mistake.
            imageBytesFromFile(File(imagePath))
                ?: return mapOf("error" to "not a readable image file (or too large): $imagePath")
        } else {
            runCatching { Base64.getDecoder().decode(imageBase64) }.getOrNull()
                ?: return mapOf("error" to "imageBase64 is not valid base64")
        }
        val id = appState.addImageBlock(tabId, bytes, provenance = "added via API", afterId = afterId, caption = caption)
            ?: return mapOf("error" to "image note was not created (bytes did not decode as an image)")
        return mapOf("ok" to true, "tabId" to tabId, "blockId" to id)
    }

    private fun updateAnnotationRoute(tabId: String, blockId: String, text: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.updateBlock(tabId, blockId, text)
        return mapOf("ok" to true, "tabId" to tabId, "blockId" to blockId)
    }

    private fun moveAnnotationRoute(tabId: String, blockId: String, delta: Int): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.moveBlock(tabId, blockId, delta)
        return mapOf("ok" to true, "tabId" to tabId, "blockId" to blockId)
    }

    private fun deleteAnnotationRoute(tabId: String, blockId: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.removeBlock(tabId, blockId)
        return mapOf("ok" to true)
    }

    private fun clearAllNotesRoute(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val removedBlockCount = tab.annotations.blocks.size
        val clearedPrefix = tab.annotations.prefix.isNotEmpty()
        val clearedSuffix = tab.annotations.suffix.isNotEmpty()
        appState.upAnn(tabId) { current ->
            current.copy(annotations = current.annotations.copy(blocks = emptyList(), prefix = "", suffix = ""))
        }
        return mapOf(
            "ok" to true,
            "tabId" to tabId,
            "removedBlockCount" to removedBlockCount,
            "clearedPrefix" to clearedPrefix,
            "clearedSuffix" to clearedSuffix,
        )
    }

    private fun saveAnnotationsRoute(tabId: String, path: String): Map<String, Any?> {
        if (invalidPath(path)) return mapOf("error" to "invalid or missing path")
        val ok = appState.saveAnnotationsTo(tabId, File(path))
        return if (ok) mapOf("ok" to true, "path" to path) else mapOf("error" to "annotations were not saved")
    }

    private fun loadAnnotationsRoute(tabId: String, path: String): Map<String, Any?> {
        if (invalidPath(path)) return mapOf("error" to "invalid or missing path")
        val ok = appState.loadAnnotationsFrom(tabId, File(path))
        return if (ok) mapOf("ok" to true, "path" to path) else mapOf("error" to "annotations were not loaded")
    }

    private fun exportAnalysisRoute(tabId: String, path: String): Map<String, Any?> {
        if (invalidPath(path)) return mapOf("error" to "invalid or missing path")
        val ok = appState.exportAnalysisTo(tabId, File(path))
        return if (ok) mapOf("ok" to true, "path" to path) else mapOf("error" to "analysis was not exported")
    }

    private fun exportFilteredRoute(tabId: String, path: String, format: String): Map<String, Any?> {
        if (invalidPath(path)) return mapOf("error" to "invalid or missing path")
        val csv = format.equals("csv", ignoreCase = true)
        val ok = appState.exportFilteredTo(tabId, File(path), csv)
        return if (ok) mapOf("ok" to true, "path" to path, "format" to if (csv) "csv" else "txt")
        else mapOf("error" to "filtered log was not exported")
    }

    private fun listFilterPresets(): Map<String, Any?> =
        mapOf("presets" to appState.savedFilters.map { filterPresetToMap(it) })

    private fun applyFilterPresetRoute(tabId: String, presetId: String): Map<String, Any?> {
        if (tabId.isBlank() || presetId.isBlank()) return mapOf("error" to "missing tabId or presetId")
        return if (appState.loadFilterById(tabId, presetId)) mapOf("ok" to true)
        else mapOf("error" to "no such tab or preset")
    }

    private fun getTags(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        return mapOf("tags" to tab.logData.map { it.tag }.distinct().sorted())
    }

    private fun getIssueDescription(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        return mapOf("issueDescription" to tab.annotations.issueDescription)
    }

    private fun getAnnotationSections(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        return mapOf("tabId" to tabId, "prefix" to tab.annotations.prefix, "suffix" to tab.annotations.suffix)
    }

    /**
     * Deliberately excludes image bytes and issueDescription. The former can be huge and the
     * latter is a private working note, while these fields are enough to target every block for
     * update/move/delete without guessing an id.
     */
    private fun getAnnotationBlocks(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        return mapOf("tabId" to tabId, "blocks" to tab.annotations.blocks.map(::annotationBlockToMap))
    }

    private fun appendAnnotationSection(tabId: String, section: String, text: String?): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        if (text.isNullOrBlank()) return mapOf("error" to "missing or blank annotation text")
        when (section) {
            "prefix" -> appState.appendPrefix(tabId, text)
            "suffix" -> appState.appendSuffix(tabId, text)
            else -> return mapOf("error" to "unknown annotation section '$section'; valid: prefix,suffix")
        }
        val content = when (section) {
            "prefix" -> appState.tab(tabId)?.annotations?.prefix
            else -> appState.tab(tabId)?.annotations?.suffix
        } ?: tab.annotations.let { if (section == "prefix") it.prefix else it.suffix }
        return mapOf("ok" to true, "tabId" to tabId, "section" to section, "content" to content)
    }

    private fun setAnnotationSection(tabId: String, section: String, text: String?): Map<String, Any?> {
        appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        // Unlike append, blank is a valid input here — it's the clear path, not an error.
        val resolved = if (text.isNullOrBlank()) "" else text
        when (section) {
            "prefix" -> appState.setPrefix(tabId, resolved)
            "suffix" -> appState.setSuffix(tabId, resolved)
            else -> return mapOf("error" to "unknown annotation section '$section'; valid: prefix,suffix")
        }
        val content = when (section) {
            "prefix" -> appState.tab(tabId)?.annotations?.prefix
            else -> appState.tab(tabId)?.annotations?.suffix
        }
        return mapOf("ok" to true, "tabId" to tabId, "section" to section, "content" to content)
    }

    // Detected on the whole (unfiltered) file, matching CrashPanel's own "complete inventory"
    // behavior — see ui/CrashPanel.kt. Reads the cached tab.analysis.crashSites instead of
    // recomputing on every call (P-02) — analysis costs as much as the parse itself on
    // multi-GB files, and repeated polling reads (a client waiting for analysis to land) must
    // not pay that cost again on each request. While still pending, `pending: true` lets a
    // client distinguish "still analyzing" from "analyzed, found nothing."
    private fun getCrashSites(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        if (tab.analysis.pending) return mapOf("tabId" to tabId, "sites" to emptyList<Map<String, Any?>>(), "pending" to true)
        return mapOf("tabId" to tabId, "sites" to tab.analysis.crashSites.map { crashSiteToMap(it) })
    }

    // Returns a real inline image (see ControlServer.toCallToolResult's imageBase64 special-case),
    // for a log line (via the tab's single anchor) or an explicit video position. videoMs, when
    // supplied, always wins over lineId — mirrors resolve_log_source's "explicit input wins" shape,
    // here with one field rather than a two-mode switch since either alone is enough to resolve a
    // target position.
    private fun getVideoFrameRoute(tabId: String, lineId: Int?, videoMs: Long?): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        if (tab.attachedVideo == null) return mapOf("error" to "no video attached to tab: $tabId")
        val targetMs = when {
            videoMs != null -> videoMs
            lineId != null -> appState.logIdToVideoMs(tab, lineId)
                ?: return mapOf(
                    "error" to "no anchor set (link a log row to a video position first), " +
                        "or line has no parseable timestamp",
                )
            else -> return mapOf("error" to "provide lineId or videoMs")
        }
        if (!appState.isVideoPositionValid(tab, targetMs)) {
            return mapOf("error" to "video position is outside the attached video's known range")
        }
        val bytes = appState.videoController(tabId)?.grabFrameAt(targetMs)
            ?: return mapOf("error" to "could not extract frame at ${targetMs}ms")
        return mapOf(
            "imageBase64" to Base64.getEncoder().encodeToString(bytes),
            "mimeType" to "image/png",
            "videoMs" to targetMs,
        )
    }

    // No tab-existence/anchor check here beyond what AppState.followDiagnostics already does
    // internally (it degrades to status NO_ANCHOR rather than throwing) — unlike get_video_frame,
    // there is nothing more actionable to say for a missing tab/anchor than the dump's own fields
    // already report (hasAnchor: false, status: NO_ANCHOR), so this stays a thin pass-through.
    private fun getFollowDiagnosticsRoute(tabId: String, videoMs: Long): Map<String, Any?> =
        followDiagnosticsToMap(appState.followDiagnostics(tabId, videoMs))

    // ── Log composition (Stage 3 automation tool) ──────────────────────
    //
    // Exposes utils/MessageTemplates.kt's histogram — the distinct masked message "shapes" in a
    // tab's CURRENT FILTERED VIEW, ranked by count — as a read-only tool. This is deliberately
    // NOT a thin wrapper around AppState.requestMessageComposition: that call is fire-and-forget
    // on ioScope, and the only way for a synchronous handler to observe its result would be to
    // poll AppState.tab(tabId).messageComposition in a Thread.sleep loop — exactly the awaitLoad
    // anti-pattern SAAD's R4 already flags as a known risk (a Ktor request thread blocked by
    // polling instead of doing anything). Computing the histogram directly on this thread instead
    // is no faster, but it is honest about the cost and matches the shape getSequenceSummary
    // already uses for its own full-log scan: real work on the calling thread, not a poll loop.
    //
    // A cached MessageCompositionState.Computed is reused when it already matches the tab's
    // current view (see Filter.viewDefiningKey) — free in the common case where the filter panel
    // is open or a previous call already primed it. Otherwise this scans synchronously and writes
    // the result back into the same AppState.messageComposition slot the UI reads, so the two
    // never disagree and a later call (from this tool or the panel) finds it cached. This also
    // answers the "nothing computed yet" question directly rather than reporting an empty/pending
    // result a model could mistake for "no repeated messages" (see MessageCompositionState's own
    // doc on that exact trap) — the first call for a view simply pays the scan cost and returns
    // real data.
    private fun getLogComposition(
        tabId: String,
        granularityParam: String?,
        orderParam: String?,
        tag: String?,
        search: String?,
        offset: Int,
        limit: Int,
    ): Map<String, Any?> {
        val t = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val granularity = parseTemplateGranularity(granularityParam)
            ?: return mapOf("error" to "unknown granularity '$granularityParam'; valid: loose,normal,strict")
        val order = parseCompositionOrder(orderParam)
            ?: return mapOf("error" to "unknown order '$orderParam'; valid: frequent,rare")
        val (histogram, cacheHit) = resolveMessageComposition(t)
        return buildLogCompositionResponse(t.id, histogram, cacheHit, granularity, order, tag, search, offset, limit)
    }

    private fun parseTemplateGranularity(raw: String?): TemplateGranularity? = when (raw?.trim()?.lowercase()) {
        null, "" -> TemplateGranularity.NORMAL
        "loose" -> TemplateGranularity.LOOSE
        "normal" -> TemplateGranularity.NORMAL
        "strict" -> TemplateGranularity.STRICT
        else -> null
    }

    private fun parseCompositionOrder(raw: String?): String? = when (raw?.trim()?.lowercase()) {
        null, "" -> "frequent"
        "frequent", "rare" -> raw.trim().lowercase()
        else -> null
    }

    // Reuses tab.messageComposition when it is already Computed for the tab's current view
    // (identical to how ui/FilterPanel.kt's panel decides whether AppState.requestMessageComposition
    // needs to start a new scan). On a miss, scans visibleEntries(t) — the same source the filter
    // panel scans — directly on this (Ktor request) thread; see this function's caller for why that
    // beats polling. The fresh result is written back through appState.upTab, but only if the tab's
    // filter still matches what was scanned — a concurrent filter change during the scan must not
    // stamp a result that no longer describes the current view.
    private fun resolveMessageComposition(t: LogTab): Pair<MessageTemplateHistogram, Boolean> {
        val wanted = t.filter.viewDefiningKey()
        val cached = t.messageComposition as? MessageCompositionState.Computed
        if (cached != null && cached.forFilter == wanted) return cached.histogram to true
        val histogram = computeMessageTemplates(visibleEntries(t), t.analysis.stackTraceGroups)
        appState.upTab(t.id) { fresh ->
            if (fresh.filter.viewDefiningKey() == wanted) {
                fresh.copy(messageComposition = MessageCompositionState.Computed(histogram, wanted))
            } else {
                fresh
            }
        }
        return histogram to false
    }

    private fun buildLogCompositionResponse(
        tabId: String,
        histogram: MessageTemplateHistogram,
        cacheHit: Boolean,
        granularity: TemplateGranularity,
        order: String,
        tag: String?,
        search: String?,
        offset: Int,
        limit: Int,
    ): Map<String, Any?> {
        val folded = foldTemplates(histogram, granularity)
        val cleanTag = tag?.trim()?.takeIf { it.isNotBlank() }
        val needle = search?.trim().orEmpty()
        var matches = folded
        if (cleanTag != null) matches = matches.filter { it.tag == cleanTag }
        if (needle.isNotEmpty()) matches = matches.filter { it.tag.contains(needle, true) || it.template.contains(needle, true) }
        val ordered = if (order == "rare") matches.sortedBy { it.count } else matches
        val safeOffset = offset.coerceAtLeast(0).coerceAtMost(ordered.size)
        val safeLimit = limit.coerceIn(1, MAX_LOG_COMPOSITION_LIMIT)
        return mapOf(
            "tabId" to tabId,
            "granularity" to granularity.name.lowercase(),
            "order" to order,
            "tag" to cleanTag,
            "search" to needle.ifEmpty { null },
            "offset" to safeOffset,
            "limit" to safeLimit,
            "totalCount" to ordered.size,
            "shapeCount" to folded.size,
            "cacheHit" to cacheHit,
            "overflowed" to histogram.overflowed,
            "totalEntries" to histogram.totalEntries,
            "countedEntries" to histogram.countedEntries,
            "templates" to ordered.drop(safeOffset).take(safeLimit).map { messageTemplateToMap(it) },
        )
    }

    private fun messageTemplateToMap(t: MessageTemplate): Map<String, Any?> = mapOf(
        "tag" to t.tag,
        "template" to t.template,
        "count" to t.count,
        "firstLineId" to t.firstEntryId,
    )

    // ── DTO helpers ───────────────────────────────────────────────────

    private fun annotationBlockToMap(block: AnnBlock): Map<String, Any?> = when (block) {
        is AnnBlock.Note -> mapOf("id" to block.id, "type" to "text", "text" to block.text)
        is AnnBlock.LogRef -> mapOf(
            "id" to block.id,
            "type" to "log",
            "lineIds" to block.logIds,
            "caption" to block.caption,
            "sourceTabId" to block.sourceTabId,
            "sourceFilename" to block.sourceFilename,
        )
        is AnnBlock.Image -> mapOf(
            "id" to block.id,
            "type" to "image",
            "caption" to block.caption,
            "format" to block.format,
            "byteCount" to block.bytes.size,
            "provenance" to block.provenance,
            "video" to block.videoFrame?.let { frame ->
                mapOf(
                    "sourceLabel" to frame.sourceLabel,
                    "positionMs" to frame.positionMs,
                    "displayProvenance" to frame.provenanceLabel,
                )
            },
        )
    }

    private fun filterToMap(f: Filter): Map<String, Any?> = mapOf(
        "levels" to f.levels.map { it.key.toString() },
        "activeTags" to f.activeTags.toList(),
        "excludeTags" to f.excludeTags.toList(),
        "pkgPrefixes" to f.pkgPrefixes.toList(),
        "excludePkgPrefixes" to f.excludePkgPrefixes.toList(),
        "kwText" to f.kwText,
        "kwRegex" to f.kwRegex,
        "excludeKw" to f.excludeKw,
        "excludeKwRegex" to f.excludeKwRegex,
        "kwInTag" to f.kwInTag,
        "kwInTagRegex" to f.kwInTagRegex,
        "pidTidFilter" to f.pidTidFilter,
        "seqOn" to f.seqOn,
        "mode" to f.mode.name,
        // messageRules and sequences also hide/fold rows; exposing them here is what lets a client
        // detect a "looks filtered but tags/levels are empty" state it would otherwise miss.
        "messageRules" to f.messageRules.map { messageRuleToMap(it) },
        "sequences" to f.sequences.map { sequenceDefToMap(it) },
    )

    private fun messageRuleToMap(r: MessageRule): Map<String, Any?> = mapOf(
        "id" to r.id, "include" to r.include, "pattern" to r.pattern, "regex" to r.regex,
        "tag" to r.tag, "packagePrefix" to r.packagePrefix, "enabled" to r.enabled,
        "target" to r.target.name,
    )

    private fun sequenceDefToMap(s: SequenceDef): Map<String, Any?> = mapOf(
        "id" to s.id, "enabled" to s.enabled, "priority" to s.priority,
        "tag" to s.tag, "matchText" to s.matchText, "isRegex" to s.isRegex,
        "endTag" to s.endTag, "endMatchText" to s.endMatchText, "endIsRegex" to s.endIsRegex,
    )

    // Deliberately no `else` branch, so adding a new LogItem variant is a compile error here,
    // not a silently-missing DTO field.
    private fun logItemToMap(item: LogItem): Map<String, Any?> = when (item) {
        is LogItem.Row -> mapOf(
            "type" to "Row", "id" to item.entry.id, "ts" to item.entry.ts,
            "level" to item.entry.level.key.toString(), "tag" to item.entry.tag,
            "msg" to item.entry.msg, "pid" to item.entry.pid, "tid" to item.entry.tid,
            "indent" to item.indent,
        )
        is LogItem.SeqHeader -> mapOf(
            "type" to "SeqHeader", "id" to item.entry.id, "gid" to item.gid,
            "ts" to item.entry.ts, "level" to item.entry.level.key.toString(),
            "tag" to item.entry.tag, "msg" to item.entry.msg,
            "indent" to item.indent, "expanded" to item.expanded, "count" to item.count,
        )
        is LogItem.ManualHeader -> mapOf(
            "type" to "ManualHeader", "id" to item.entry.id, "gid" to item.gid,
            "ts" to item.entry.ts, "level" to item.entry.level.key.toString(),
            "tag" to item.entry.tag, "msg" to item.entry.msg,
            "expanded" to item.expanded, "count" to item.count,
        )
        is LogItem.StackTraceHeader -> mapOf(
            "type" to "StackTraceHeader", "id" to item.entry.id, "gid" to item.gid,
            "ts" to item.entry.ts, "level" to item.entry.level.key.toString(),
            "tag" to item.entry.tag, "msg" to item.entry.msg,
            "indent" to item.indent, "expanded" to item.expanded, "count" to item.count,
        )
    }

    private fun crashSiteToMap(site: CrashSite): Map<String, Any?> = mapOf(
        "id" to site.id, "kind" to site.kind.name, "groupGid" to site.groupGid, "isFatal" to site.isFatal,
        "logId" to site.entry.id, "ts" to site.entry.ts, "level" to site.entry.level.key.toString(),
        "tag" to site.entry.tag, "msg" to site.entry.msg,
        "signature" to site.signature, "occurrenceCount" to site.occurrenceCount, "firstLogId" to site.firstLogId,
    )

    // id/ts/elapsedMs (or id/ts) only, matching FollowDiagnosticRow/RolloverDiagnosticEvent's own
    // confidentiality guarantee — see their doc comments in AppState.kt.
    private fun followDiagnosticsToMap(d: FollowDiagnostics): Map<String, Any?> = mapOf(
        "tabId" to d.tabId,
        "hasAnchor" to d.hasAnchor,
        "anchor" to d.anchor?.let { mapOf("id" to it.id, "ts" to it.ts, "elapsedMs" to it.elapsedMs) },
        "anchorVideoMs" to d.anchorVideoMs,
        "playheadVideoMs" to d.playheadVideoMs,
        "mappedElapsedMs" to d.mappedElapsedMs,
        "mappedElapsedClock" to d.mappedElapsedClock,
        "chosenVisibleFloor" to d.chosenVisibleFloor?.let { mapOf("id" to it.id, "ts" to it.ts, "elapsedMs" to it.elapsedMs) },
        "nextVisibleCandidatesAfterFloor" to d.nextVisibleCandidatesAfterFloor.map { mapOf("id" to it.id, "ts" to it.ts, "elapsedMs" to it.elapsedMs) },
        "visibleCandidateCount" to d.visibleCandidateCount,
        "candidatesFromSummaryFallback" to d.candidatesFromSummaryFallback,
        "fullLogFloor" to d.fullLogFloor?.let { mapOf("id" to it.id, "ts" to it.ts, "elapsedMs" to it.elapsedMs) },
        "status" to d.status.name,
        "rolloverAppliedCount" to d.rolloverAppliedCount,
        "rolloverAppliedSamples" to d.rolloverAppliedSamples.map { mapOf("id" to it.id, "ts" to it.ts) },
        "rolloverSuppressedCount" to d.rolloverSuppressedCount,
        "rolloverSuppressedSamples" to d.rolloverSuppressedSamples.map { mapOf("id" to it.id, "ts" to it.ts) },
        "dayOffsetModelValid" to d.dayOffsetModelValid,
        "showUnfiltered" to d.showUnfiltered,
        "filterActive" to d.filterActive,
        "totalLogDataSize" to d.totalLogDataSize,
        "displayedItemCount" to d.displayedItemCount,
    )

    private fun sourceMatchToMap(match: SourceMatch): Map<String, Any?> = mapOf(
        "filePath" to match.site.filePath,
        "methodName" to match.site.methodName,
        "methodStartLine" to match.site.methodStartLine,
        "methodEndLine" to match.site.methodEndLine,
        "callLine" to match.site.callLine,
        "tag" to match.site.tag,
        "confidence" to match.confidence,
        "stale" to match.stale,
        "code" to appState.readMethodSource(match.site),
    )

    // Opt-in token-saving projection over a full item map. With neither arg the map is returned
    // unchanged (byte-for-byte identical to the pre-existing output). `fields` is a whitelist of
    // entry-derived keys to keep; `compact` drops pid/tid/indent and any empty/zero-valued key.
    // Structural keys (type/id/gid/expanded/count) are always kept so items stay usable.
    private fun projectItem(full: Map<String, Any?>, fields: Set<String>?, compact: Boolean): Map<String, Any?> {
        if (fields == null && !compact) return full
        return buildMap {
            full.forEach { (k, v) ->
                when {
                    k in STRUCTURAL_ITEM_KEYS -> put(k, v)
                    fields != null -> if (k in fields) put(k, v)
                    k in COMPACT_DROP_KEYS -> Unit
                    !isEmptyOrZero(v) -> put(k, v)
                }
            }
        }
    }

    private fun rowMap(entry: LogEntry, fields: Set<String>?, compact: Boolean): Map<String, Any?> =
        projectItem(logItemToMap(LogItem.Row(entry, 0)), fields, compact)

    private fun zipCandidateToMap(candidate: ZipLogCandidate): Map<String, Any?> = mapOf(
        "entryPath" to candidate.entryPath,
        "displayName" to candidate.displayName,
        "sizeBytes" to candidate.sizeBytes,
        "kind" to candidate.kind.name,
    )

    private fun splitSourceToMap(source: SplitSource): Map<String, Any?> = mapOf(
        "needsSplit" to true,
        "id" to source.id,
        "displayName" to source.displayName,
        "sizeBytes" to source.sizeBytes,
        "suggestedPartCount" to appState.defaultSplitPartCount(source),
        "defaultDestinationDir" to appState.defaultSplitDestination(source).absolutePath,
        "defaultPostfix" to "part",
    ) + when (source) {
        is SplitSource.RealFile -> mapOf("path" to source.file.absolutePath)
        is SplitSource.ArchiveEntry -> mapOf(
            "path" to source.archiveFile.absolutePath,
            "entryPath" to source.candidate.entryPath,
        )
    }

    private fun filterPresetToMap(preset: SavedFilter): Map<String, Any?> = mapOf(
        "id" to preset.id,
        "name" to preset.name,
        "levels" to preset.levels.map { it.key.toString() },
        "mode" to preset.mode.name,
        "activeTags" to preset.activeTags.toList(),
        "excludeTags" to preset.excludeTags.toList(),
    )

    // Integer from either a REST query param (String) or a JSON body / MCP argument (Number).
    private fun Map<String, Any?>.anyInt(key: String): Int? = when (val v = this[key]) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }
}
