package com.openlog.debug

import com.openlog.model.CrashSite
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogEntry
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.MessageRule
import com.openlog.model.RuleTarget
import com.openlog.model.SavedFilter
import com.openlog.model.SequenceDef
import com.openlog.source.SourceMatch
import com.openlog.ui.AppState
import com.openlog.ui.SEQ_COLORS
import com.openlog.ui.SplitSource
import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.computeItems
import com.openlog.utils.isSupportedArchiveFile
import com.openlog.utils.listArchiveLogCandidates
import java.io.File

/**
 * Transport-neutral AppState operations behind the openLog MCP catalog.
 *
 * This is deliberately constructible without a server so the in-app AI runner can execute the
 * exact same contract directly. ControlServer is only an HTTP/MCP adapter over [toolGateway].
 */
internal class OpenLogToolOperations(
    private val appState: AppState,
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
        "add_text_note" to { a ->
            addTextNoteRoute(a.str("tabId") ?: "", a.str("text") ?: "", a.str("afterId"))
        },
        "add_log_note" to { a ->
            addLogNoteRoute(a.str("tabId") ?: "", a.intList("lineIds") ?: emptyList(), a.str("caption") ?: "")
        },
        "update_note_block" to { a ->
            updateAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "", a.str("text") ?: "")
        },
        "move_note_block" to { a ->
            moveAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "", a.anyInt("delta") ?: 0)
        },
        "delete_note_block" to { a -> deleteAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "") },
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
        "get_project_info" to { getProjectInfoRoute() },
    )

    // Direct in-process entry point shared by MCP/REST and the future AI runner.
    internal val toolGateway = OpenLogToolGateway(MCP_TOOLS, operationHandlers)

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
        if (splitMode.equals("open_as_is", ignoreCase = true)) appState.openFileAsIs(file) else appState.openFile(file)
        appState.pendingSplitPrompt?.sources?.firstOrNull { source ->
            source is SplitSource.RealFile && source.file.absolutePath == absPath
        }?.let { source ->
            return splitSourceToMap(source)
        }
        awaitLoad()
        val tab = appState.tabs.find { it.sourcePath == absPath }
            ?: return mapOf("error" to "file did not load: $path")
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
        val sourcePath = "${file.absolutePath}!${target.entryPath}"
        if (splitMode.equals("open_as_is", ignoreCase = true)) {
            appState.openZipEntryAsIs(file, target)
        } else {
            appState.openZipEntries(file, listOf(target))
        }
        appState.pendingSplitPrompt?.sources?.firstOrNull { source ->
            source is SplitSource.ArchiveEntry &&
                source.archiveFile.absolutePath == file.absolutePath &&
                source.candidate.entryPath == target.entryPath
        }?.let { source ->
            return splitSourceToMap(source)
        }
        awaitLoad()
        val tab = appState.tabs.find { it.sourcePath == sourcePath }
            ?: return mapOf("error" to "entry did not load: ${target.entryPath}")
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

    // openFile/openZipEntries are async (launched on ioScope); block this request thread until
    // isLoading settles back to false. If the target was already open, the call returns
    // synchronously and isLoading never toggles true, so this loop exits immediately.
    private fun awaitLoad() {
        val deadline = System.currentTimeMillis() + OPEN_FILE_TIMEOUT_MS
        while (appState.isLoading && System.currentTimeMillis() < deadline) Thread.sleep(OPEN_FILE_POLL_INTERVAL_MS)
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

    private fun getProjectInfoRoute(): Map<String, Any?> {
        val folders = appState.settings.sourceFolderInfo.mapNotNull { (path, info) ->
            if (info.description.isBlank() && info.readmePath.isNullOrBlank()) return@mapNotNull null
            buildMap<String, Any?> {
                put("path", path)
                put("description", info.description)
                put("readmePath", info.readmePath)
                if (info.readmePath != null) {
                    runCatching { File(info.readmePath).readText() }
                        .onSuccess { put("readmeContent", it) }
                        .onFailure { put("readmeError", it.message ?: "unreadable") }
                }
            }
        }
        return mapOf("folders" to folders)
    }

    private fun closeTab(tabId: String): Map<String, Any?> {
        if (tabId.isBlank()) return mapOf("error" to "missing tabId")
        appState.closeTab(tabId)
        return mapOf("ok" to true)
    }

    private fun getFilter(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        return filterToMap(tab.filter)
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
                id = m.str("id")?.takeIf { it.isNotBlank() } ?: "rule${System.currentTimeMillis()}_$idx",
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
                id = m.str("id")?.takeIf { it.isNotBlank() } ?: "seq${System.currentTimeMillis()}_$idx",
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
        val idx = tab.logData.indexOfFirst { it.id == lineId }
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

    private fun addTextNoteRoute(tabId: String, text: String, afterId: String?): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        val id = appState.addNoteBlock(tabId, text, afterId) ?: return mapOf("error" to "note was not created")
        return mapOf("ok" to true, "tabId" to tabId, "blockId" to id)
    }

    private fun addLogNoteRoute(tabId: String, lineIds: List<Int>, caption: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        val id = appState.addLogRefBlock(tabId, lineIds, caption) ?: return mapOf("error" to "log note was not created")
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

    // ── DTO helpers ───────────────────────────────────────────────────

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
        "id" to site.id, "kind" to site.kind.name, "groupGid" to site.groupGid,
        "logId" to site.entry.id, "ts" to site.entry.ts, "level" to site.entry.level.key.toString(),
        "tag" to site.entry.tag, "msg" to site.entry.msg,
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
