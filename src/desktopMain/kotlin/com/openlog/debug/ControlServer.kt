package com.openlog.debug

import com.openlog.model.CrashSite
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.SavedFilter
import com.openlog.ui.AppState
import com.openlog.ui.SplitSource
import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeItems
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.isSupportedArchiveFile
import com.openlog.utils.listArchiveLogCandidates
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.ConcurrentHashMap

private const val DEFAULT_VISIBLE_LIMIT = 200

// Long enough for a 1.5GB logcat (~20s parse + analysis, measured); at 15s open_log_file
// reported "file did not load" for a load that then completed successfully seconds later.
private const val OPEN_FILE_TIMEOUT_MS = 120_000L
private const val OPEN_FILE_POLL_INTERVAL_MS = 20L
private const val CLIENT_STALE_MS = 5 * 60_000L
private const val CLIENT_ID_HEADER = "X-OpenLog-Client-Id"
private const val CLIENT_NAME_HEADER = "X-OpenLog-Client-Name"
private const val DEFAULT_CLIENT_NAME = "MCP client"

// A REST caller can self-identify with a per-process-launch random id + optional human-readable
// name (see the plain-curl escape hatch). Surfaced in the Settings "Connection info" popup so a
// user can see who's talking to their app and, if unwanted, block them by that id.
data class ConnectedClientInfo(val id: String, val name: String, val lastSeenMs: Long, val blocked: Boolean)

// A native MCP client (LM Studio, Claude Code, Codex) connected over /mcp. The SDK assigns
// sessionId per-connection (it changes on reconnect), so unlike ConnectedClientInfo there's no
// stable id to persist a "blocked" flag against — the only available action is to kick the
// current session via Server.sessions[id].close(), which the client can reconnect after.
data class McpSessionInfo(val id: String, val name: String, val version: String?)

/**
 * Localhost-only debug/automation control surface for the running app. Two ways in, one shared
 * set of operations:
 *  - the MCP Streamable HTTP endpoint at `/mcp` (io.modelcontextprotocol kotlin-sdk), which any
 *    MCP client — LM Studio, Claude Code, Codex — connects to by URL with nothing to install;
 *  - the legacy REST routes (GET /tabs, POST /open, …) kept for the `curl` escape hatch and the
 *    ControlServerTest suite.
 * Both dispatch into the same `runOp` handlers, which read/mutate real app state. See Main.kt for
 * startup gating: this must never start in packaged builds unless the user enables it in Settings
 * (or OPENLOG_DEBUG_CONTROL / -Dopenlog.debugControl is set).
 *
 * AppState's mutableStateOf fields are documented as snapshot-safe to read/write from any thread,
 * so Ktor's request-handling coroutines can call AppState directly. Public API
 * (constructor/start/stop/boundPort/connectedClients/blockClient/unblockClient) is unchanged from
 * the previous JDK-HttpServer implementation so callers and tests are unaffected.
 */
class ControlServer(private val appState: AppState, private val port: Int) {
    private var engine: EmbeddedServer<*, *>? = null
    private var mcpServer: Server? = null

    private data class ClientRecord(val name: String, val lastSeenMs: Long)

    private val clients = ConcurrentHashMap<String, ClientRecord>()
    private val blockedIds = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var resolvedPort: Int = port
    val boundPort: Int get() = resolvedPort

    // Read by the Settings/Connection-info UI (in-process, no HTTP hop needed — Compose and this
    // server share the same JVM). Prunes anything not seen in the last CLIENT_STALE_MS so a
    // closed-and-reopened tool doesn't linger in the list forever.
    fun connectedClients(): List<ConnectedClientInfo> {
        val now = System.currentTimeMillis()
        return clients.entries
            .filter { now - it.value.lastSeenMs <= CLIENT_STALE_MS }
            .map { (id, rec) -> ConnectedClientInfo(id, rec.name, rec.lastSeenMs, id in blockedIds) }
            .sortedByDescending { it.lastSeenMs }
    }

    fun blockClient(id: String) {
        blockedIds.add(id)
    }

    fun unblockClient(id: String) {
        blockedIds.remove(id)
    }

    // Server.sessions is a live snapshot keyed by sessionId; clientVersion is populated once the
    // MCP initialize handshake completes (null very briefly before that).
    fun mcpSessions(): List<McpSessionInfo> = mcpServer?.sessions?.values
        ?.map { s -> McpSessionInfo(s.sessionId, s.clientVersion?.name ?: "MCP client", s.clientVersion?.version) }
        ?.sortedBy { it.name }
        ?: emptyList()

    // ServerSession.close() (inherited from Protocol) closes the underlying transport, which
    // actually drops the client's HTTP/SSE connection rather than just forgetting our own state.
    fun disconnectMcpSession(id: String) {
        val session = mcpServer?.sessions?.get(id) ?: return
        runBlocking { session.close() }
    }

    fun start() {
        val mcp = buildMcpServer()
        mcpServer = mcp
        val server = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            // Browser-based MCP inspectors need CORS with the MCP-specific session/version headers.
            install(CORS) {
                anyHost()
                allowHeader("Mcp-Session-Id")
                allowHeader("Mcp-Protocol-Version")
                allowHeader("Content-Type")
            }
            // Native MCP over Streamable HTTP at /mcp — what LM Studio, Claude Code, and Codex
            // connect to by URL with nothing to install. Same shared server for every connection;
            // its tools are stateless (they read live appState).
            mcpStreamableHttp { mcp }
            routing { registerRestRoutes() }
        }
        server.start(wait = false)
        resolvedPort = runBlocking { server.engine.resolvedConnectors().first().port }
        engine = server
    }

    fun stop() {
        engine?.stop()
        engine = null
        mcpServer = null
    }

    // Single source of truth for every operation, keyed by MCP tool name. Both the native MCP
    // tools and the legacy REST routes shape their inputs into this Map<String, Any?> and call
    // here — so the actual behavior lives in exactly one place (the private route methods below,
    // unchanged from the JDK-HttpServer version). Returns a Map/List that Json.encode serializes.
    @Suppress("CyclomaticComplexMethod")
    private fun runOp(name: String, a: Map<String, Any?>): Any? = when (name) {
        "list_tabs" -> listTabs()
        "open_log_file" -> openLogFile(
            a.str("path") ?: "", a.str("entryPath"), a.str("splitMode"),
            a.str("destinationDir"), a.str("postfix") ?: "part", a.anyInt("partCount"),
        )
        "preview_split_log_file" -> splitPreviewRoute(a.str("path") ?: "", a.str("entryPath"))
        "split_log_file" -> splitLogRoute(
            a.str("path") ?: "", a.str("entryPath"), a.str("destinationDir"), a.str("postfix") ?: "part", a.anyInt("partCount"),
        )
        "close_tab" -> closeTab(a.str("tabId") ?: "")
        "get_filter" -> getFilter(a.str("tabId") ?: "")
        "set_filter" -> setFilter(a.str("tabId") ?: "", a)
        "get_visible_lines" -> getVisibleLines(a.str("tabId") ?: "", a.anyInt("limit") ?: DEFAULT_VISIBLE_LIMIT, a.anyInt("offset") ?: 0)
        "select_lines" -> setSelection(a.str("tabId") ?: "", a.intList("lineIds") ?: emptyList())
        "get_selection" -> getSelection(a.str("tabId") ?: "")
        "toggle_group" -> toggleGroupRoute(a.str("tabId") ?: "", a.str("gid") ?: "")
        "expand_all" -> expandAllRoute(a.str("tabId") ?: "")
        "collapse_all" -> collapseAllRoute(a.str("tabId") ?: "")
        "get_tags" -> getTags(a.str("tabId") ?: "")
        "get_crash_sites" -> getCrashSites(a.str("tabId") ?: "")
        "add_text_note" -> addTextNoteRoute(a.str("tabId") ?: "", a.str("text") ?: "", a.str("afterId"))
        "add_log_note" -> addLogNoteRoute(a.str("tabId") ?: "", a.intList("lineIds") ?: emptyList(), a.str("caption") ?: "")
        "update_note_block" -> updateAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "", a.str("text") ?: "")
        "move_note_block" -> moveAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "", a.anyInt("delta") ?: 0)
        "delete_note_block" -> deleteAnnotationRoute(a.str("tabId") ?: "", a.str("blockId") ?: "")
        "export_analysis" -> exportAnalysisRoute(a.str("tabId") ?: "", a.str("path") ?: "")
        "export_filtered_log" -> exportFilteredRoute(a.str("tabId") ?: "", a.str("path") ?: "", a.str("format") ?: "txt")
        "save_annotations" -> saveAnnotationsRoute(a.str("tabId") ?: "", a.str("path") ?: "")
        "load_annotations" -> loadAnnotationsRoute(a.str("tabId") ?: "", a.str("path") ?: "")
        "list_filter_presets" -> listFilterPresets()
        "apply_filter_preset" -> applyFilterPresetRoute(a.str("tabId") ?: "", a.str("presetId") ?: "")
        "merge_tabs" -> mergeTabsRoute(a.strList("tabIds") ?: emptyList(), a.str("newTabName") ?: "Merged")
        "start_tailing" -> startTailingRoute(a.str("tabId") ?: "")
        "stop_tailing" -> stopTailingRoute(a.str("tabId") ?: "")
        else -> mapOf("error" to "unknown operation: $name")
    }

    // ── REST transport (curl escape hatch + ControlServerTest) ─────────────
    // Each op is registered on its original path/method; GET reads query params, POST reads a
    // JSON body — merged into the same Map<String, Any?> runOp expects. Client-identity tracking
    // and blocking (X-OpenLog-Client-* headers) apply here exactly as before; native MCP callers
    // go through /mcp and are tracked separately via mcpSessions()/disconnectMcpSession().
    private fun io.ktor.server.routing.Routing.registerRestRoutes() {
        REST_ROUTES.forEach { (method, path, op) ->
            when (method) {
                HttpMethod.Get -> get(path) { handleRest(op) }
                else -> post(path) { handleRest(op) }
            }
        }
    }

    private suspend fun RoutingContext.handleRest(op: String) {
        val headers = call.request.headers
        val clientId = headers[CLIENT_ID_HEADER]
        if (clientId != null && clientId in blockedIds) {
            respondJson(HttpStatusCode.Forbidden, mapOf("error" to "this client is blocked"))
            return
        }
        if (clientId != null) {
            clients[clientId] = ClientRecord(headers[CLIENT_NAME_HEADER]?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_NAME, System.currentTimeMillis())
        }
        val args = buildMap<String, Any?> {
            call.request.queryParameters.entries().forEach { (k, v) -> put(k, v.firstOrNull()) }
            if (call.request.local.method == HttpMethod.Post) {
                val text = call.receiveText()
                if (text.isNotBlank()) {
                    @Suppress("UNCHECKED_CAST")
                    (Json.decode(text) as? Map<String, Any?>)?.forEach { (k, v) -> put(k, v) }
                }
            }
        }
        val result = runCatching { runOp(op, args) }
            .getOrElse { e -> return respondJson(HttpStatusCode.InternalServerError, mapOf("error" to (e.message ?: e.toString()))) }
        respondJson(HttpStatusCode.OK, result)
    }

    private suspend fun RoutingContext.respondJson(status: HttpStatusCode, body: Any?) {
        call.respondText(Json.encode(body), io.ktor.http.ContentType.Application.Json, status)
    }

    // ── Native MCP server ──────────────────────────────────────────────────
    private fun buildMcpServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "openlog-control", version = "1.0.0"),
            options = ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
        )
        MCP_TOOLS.forEach { tool ->
            server.addTool(name = tool.name, description = tool.description, inputSchema = tool.schema) { request ->
                val result = runCatching { runOp(tool.name, request.arguments?.toArgMap() ?: emptyMap()) }
                    .getOrElse { e -> mapOf("error" to (e.message ?: e.toString())) }
                CallToolResult(content = listOf(TextContent(Json.encode(result))))
            }
        }
        return server
    }

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

    private fun openLogFile(
        path: String,
        entryPath: String?,
        splitMode: String?,
        destinationDir: String?,
        postfix: String,
        partCount: Int?,
    ): Map<String, Any?> {
        if (path.isBlank()) return mapOf("error" to "missing path")
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
        if (path.isBlank()) return mapOf("error" to "missing path")
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
        if (path.isBlank()) return mapOf("error" to "missing path")
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
        appState.upFlt(tabId) { f ->
            var result = f
            if (body.containsKey("levels")) {
                body.strList("levels")?.let { keys ->
                    result = result.copy(levels = keys.mapNotNull { k -> LogLevel.entries.find { it.key.toString() == k } }.toSet())
                }
            }
            if (body.containsKey("activeTags")) {
                body.strList("activeTags")?.let { result = result.copy(activeTags = it.toSet()) }
            }
            if (body.containsKey("excludeTags")) {
                body.strList("excludeTags")?.let { result = result.copy(excludeTags = it.toSet()) }
            }
            if (body.containsKey("pkgPrefixes")) {
                body.strList("pkgPrefixes")?.let { result = result.copy(pkgPrefixes = it.toSet()) }
            }
            if (body.containsKey("excludePkgPrefixes")) {
                body.strList("excludePkgPrefixes")?.let { result = result.copy(excludePkgPrefixes = it.toSet()) }
            }
            if (body.containsKey("kwText")) {
                body.str("kwText")?.let { result = result.copy(kwText = it) }
            }
            if (body.containsKey("kwRegex")) {
                body.bool("kwRegex")?.let { result = result.copy(kwRegex = it) }
            }
            if (body.containsKey("excludeKw")) {
                body.str("excludeKw")?.let { result = result.copy(excludeKw = it) }
            }
            if (body.containsKey("excludeKwRegex")) {
                body.bool("excludeKwRegex")?.let { result = result.copy(excludeKwRegex = it) }
            }
            if (body.containsKey("kwInTag")) {
                body.str("kwInTag")?.let { result = result.copy(kwInTag = it) }
            }
            if (body.containsKey("kwInTagRegex")) {
                body.bool("kwInTagRegex")?.let { result = result.copy(kwInTagRegex = it) }
            }
            if (body.containsKey("pidTidFilter")) {
                body.str("pidTidFilter")?.let { result = result.copy(pidTidFilter = it) }
            }
            if (body.containsKey("seqOn")) {
                body.bool("seqOn")?.let { result = result.copy(seqOn = it) }
            }
            if (body.containsKey("mode")) {
                body.str("mode")?.let { m -> runCatching { FilterMode.valueOf(m) }.getOrNull()?.let { result = result.copy(mode = it) } }
            }
            result
        }
        return mapOf("ok" to true, "filter" to filterToMap(appState.tab(tabId)!!.filter))
    }

    private fun getVisibleLines(tabId: String, limit: Int, offset: Int): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val items = computeItems(tab, true)
        return mapOf("totalCount" to items.size, "items" to items.drop(offset).take(limit).map { logItemToMap(it) })
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
        return mapOf("ok" to true, "blockId" to id)
    }

    private fun addLogNoteRoute(tabId: String, lineIds: List<Int>, caption: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        val id = appState.addLogRefBlock(tabId, lineIds, caption) ?: return mapOf("error" to "log note was not created")
        return mapOf("ok" to true, "blockId" to id)
    }

    private fun updateAnnotationRoute(tabId: String, blockId: String, text: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.updateBlock(tabId, blockId, text)
        return mapOf("ok" to true)
    }

    private fun moveAnnotationRoute(tabId: String, blockId: String, delta: Int): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.moveBlock(tabId, blockId, delta)
        return mapOf("ok" to true)
    }

    private fun deleteAnnotationRoute(tabId: String, blockId: String): Map<String, Any?> {
        if (appState.tab(tabId) == null) return mapOf("error" to "no such tab: $tabId")
        appState.removeBlock(tabId, blockId)
        return mapOf("ok" to true)
    }

    private fun saveAnnotationsRoute(tabId: String, path: String): Map<String, Any?> {
        if (path.isBlank()) return mapOf("error" to "missing path")
        val ok = appState.saveAnnotationsTo(tabId, File(path))
        return if (ok) mapOf("ok" to true, "path" to path) else mapOf("error" to "annotations were not saved")
    }

    private fun loadAnnotationsRoute(tabId: String, path: String): Map<String, Any?> {
        if (path.isBlank()) return mapOf("error" to "missing path")
        val ok = appState.loadAnnotationsFrom(tabId, File(path))
        return if (ok) mapOf("ok" to true, "path" to path) else mapOf("error" to "annotations were not loaded")
    }

    private fun exportAnalysisRoute(tabId: String, path: String): Map<String, Any?> {
        if (path.isBlank()) return mapOf("error" to "missing path")
        val ok = appState.exportAnalysisTo(tabId, File(path))
        return if (ok) mapOf("ok" to true, "path" to path) else mapOf("error" to "analysis was not exported")
    }

    private fun exportFilteredRoute(tabId: String, path: String, format: String): Map<String, Any?> {
        if (path.isBlank()) return mapOf("error" to "missing path")
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

    // Detected on the whole (unfiltered) file, matching CrashPanel's own "complete inventory"
    // behavior — see ui/CrashPanel.kt.
    private fun getCrashSites(tabId: String): Map<String, Any?> {
        val tab = appState.tab(tabId) ?: return mapOf("error" to "no such tab: $tabId")
        val sites = computeCrashSites(tab.logData, computeStackTraceGroups(tab.logData))
        return mapOf("sites" to sites.map { crashSiteToMap(it) })
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

// MCP tool argument objects arrive as kotlinx JsonObject; flatten to the same String/Int/Boolean/
// List/Map shapes Json.decode produces for REST bodies, so runOp's accessors work identically.
private fun JsonObject.toArgMap(): Map<String, Any?> = entries.associate { (k, v) -> k to v.toAny() }

private fun JsonElement.toAny(): Any? = when (this) {
    is JsonNull -> null
    is JsonArray -> map { it.toAny() }
    is JsonObject -> entries.associate { (k, v) -> k to v.toAny() }
    is JsonPrimitive -> when {
        isString -> content
        booleanOrNull != null -> booleanOrNull
        longOrNull != null -> longOrNull!!.let { if (it in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) it.toInt() else it }
        doubleOrNull != null -> doubleOrNull
        else -> content
    }
}

// One declaration per operation, shared by the MCP tool registry and the REST route table so the
// two transports can never drift on tool name or path.
private class McpTool(val name: String, val description: String, val schema: ToolSchema)

// "array" means array-of-string (tag lists, tab ids, ...); "array<integer>" means array-of-number
// (line ids). Getting this right in the schema matters beyond cosmetics: a client model sees this
// JSON Schema at tools/list time, and a schema that (wrongly) says "array of strings" measurably
// nudges models toward quoting line ids as "73" instead of emitting bare 73 — observed with a
// local Gemma build mangling its own tool-call syntax on a quoted lineIds element.
private fun schema(vararg props: Pair<String, String>, required: List<String> = emptyList()): ToolSchema =
    ToolSchema(
        properties = buildJsonObject {
            props.forEach { (name, type) ->
                put(
                    name,
                    buildJsonObject {
                        when (type) {
                            "array" -> arrayOfItems("string")
                            "array<integer>" -> arrayOfItems("integer")
                            else -> put("type", type)
                        }
                    },
                )
            }
        },
        required = required.ifEmpty { null },
    )

private fun kotlinx.serialization.json.JsonObjectBuilder.arrayOfItems(itemType: String) {
    put("type", "array")
    put("items", buildJsonObject { put("type", itemType) })
}

private val MCP_TOOLS: List<McpTool> = listOf(
    McpTool("list_tabs", "List every tab currently open in the running openLog app.", schema()),
    McpTool(
        "open_log_file",
        "Open a plain logcat file, or a bug-report .zip, at the given absolute path. For a plain " +
            "file, blocks until parsing completes and returns the new tab's id. For a .zip: if it " +
            "contains exactly one candidate log, that one opens automatically; if it contains none, " +
            "returns an error; if it contains several, returns { needsSelection: true, candidates: " +
            "[...] } without opening anything — call again with the same path plus entryPath set to " +
            "one candidate's entryPath to open that one. Oversized sources return { needsSplit: true, " +
            "... } instead of opening; use split_log_file to split and open parts.",
        schema(
            "path" to "string", "entryPath" to "string", "splitMode" to "string",
            "destinationDir" to "string", "postfix" to "string", "partCount" to "integer",
            required = listOf("path"),
        ),
    ),
    McpTool(
        "preview_split_log_file",
        "Return split metadata for a real log file or archive entry without opening it: size, " +
            "suggested part count, default destination, default postfix, and source identifiers.",
        schema("path" to "string", "entryPath" to "string", required = listOf("path")),
    ),
    McpTool(
        "split_log_file",
        "Split a real log file or archive entry into line-preserving plain log files, save them to " +
            "the destination directory, and open the generated parts as tabs. Existing outputs are " +
            "not overwritten.",
        schema(
            "path" to "string", "entryPath" to "string", "destinationDir" to "string",
            "postfix" to "string", "partCount" to "integer", required = listOf("path"),
        ),
    ),
    McpTool("close_tab", "Close the tab with the given id.", schema("tabId" to "string", required = listOf("tabId"))),
    McpTool("get_filter", "Read the current filter (levels, tags, keyword, mode) for a tab.", schema("tabId" to "string", required = listOf("tabId"))),
    McpTool(
        "set_filter",
        "Partially update a tab's filter — only the fields you provide are changed.",
        schema(
            "tabId" to "string", "levels" to "array", "activeTags" to "array",
            "kwText" to "string", "kwRegex" to "boolean", "mode" to "string", required = listOf("tabId"),
        ),
    ),
    McpTool(
        "get_visible_lines",
        "Read the currently rendered log items for a tab — after filtering and sequence/manual-" +
            "collapse/stack-trace folding, i.e. what a user would actually see. Each item has a " +
            "`type` (Row, SeqHeader, ManualHeader, or StackTraceHeader).",
        schema("tabId" to "string", "limit" to "integer", "offset" to "integer", required = listOf("tabId")),
    ),
    McpTool(
        "select_lines", "Replace a tab's current selected log line ids.",
        schema("tabId" to "string", "lineIds" to "array<integer>", required = listOf("tabId", "lineIds")),
    ),
    McpTool("get_selection", "Return the selected log line ids for a tab.", schema("tabId" to "string", required = listOf("tabId"))),
    McpTool(
        "toggle_group",
        "Expand or collapse a sequence/manual-collapse group by its gid (from get_visible_lines).",
        schema("tabId" to "string", "gid" to "string", required = listOf("tabId", "gid")),
    ),
    McpTool(
        "expand_all", "Expand every currently visible collapsible sequence/manual/stack-trace group in a tab.",
        schema("tabId" to "string", required = listOf("tabId")),
    ),
    McpTool(
        "collapse_all", "Collapse every expanded sequence/manual/stack-trace group in a tab.",
        schema("tabId" to "string", required = listOf("tabId")),
    ),
    McpTool(
        "get_tags", "List every distinct tag present in a tab's full log file (not just the currently filtered set).",
        schema("tabId" to "string", required = listOf("tabId")),
    ),
    McpTool(
        "get_crash_sites",
        "List every detected exception (FATAL EXCEPTION / bare exception header) and ANR in a tab's " +
            "full log file, each with the log id to jump to. Detected on the whole file regardless " +
            "of the active filter.",
        schema("tabId" to "string", required = listOf("tabId")),
    ),
    McpTool(
        "add_text_note",
        "Append a plain text analysis note block, optionally after an existing block id.",
        schema("tabId" to "string", "text" to "string", "afterId" to "string", required = listOf("tabId", "text")),
    ),
    McpTool(
        "add_log_note",
        "Append an annotation block referencing one or more log line ids. lineIds must be bare " +
            "integers from get_visible_lines, never quoted strings or descriptive text — for a " +
            "large multi-line block (e.g. a stack trace), pass just its first and last line id as " +
            "anchors rather than enumerating every line, and put the full description in caption.",
        schema("tabId" to "string", "lineIds" to "array<integer>", "caption" to "string", required = listOf("tabId", "lineIds")),
    ),
    McpTool(
        "update_note_block", "Update a text note's text or a log note's caption.",
        schema("tabId" to "string", "blockId" to "string", "text" to "string", required = listOf("tabId", "blockId", "text")),
    ),
    McpTool(
        "move_note_block", "Move an annotation block up or down by delta positions.",
        schema("tabId" to "string", "blockId" to "string", "delta" to "integer", required = listOf("tabId", "blockId", "delta")),
    ),
    McpTool(
        "delete_note_block", "Delete an annotation block by id.",
        schema("tabId" to "string", "blockId" to "string", required = listOf("tabId", "blockId")),
    ),
    McpTool(
        "export_analysis", "Write the tab's Markdown analysis to an absolute path.",
        schema("tabId" to "string", "path" to "string", required = listOf("tabId", "path")),
    ),
    McpTool(
        "export_filtered_log", "Write the tab's current filtered log to an absolute path as txt or csv.",
        schema("tabId" to "string", "path" to "string", "format" to "string", required = listOf("tabId", "path")),
    ),
    McpTool(
        "save_annotations", "Write the tab's .ann sidecar data to an absolute path.",
        schema("tabId" to "string", "path" to "string", required = listOf("tabId", "path")),
    ),
    McpTool(
        "load_annotations", "Load .ann sidecar data from an absolute path into a tab.",
        schema("tabId" to "string", "path" to "string", required = listOf("tabId", "path")),
    ),
    McpTool("list_filter_presets", "List saved filter presets available in the running app.", schema()),
    McpTool(
        "apply_filter_preset", "Apply a saved filter preset to a tab by preset id.",
        schema("tabId" to "string", "presetId" to "string", required = listOf("tabId", "presetId")),
    ),
    McpTool(
        "merge_tabs",
        "Merge 2 or more already-open tabs into one new tab, interleaved by time-of-day (not " +
            "calendar-aware — entries are compared purely by HH:mm:ss.SSS, so this is only correct " +
            "when the sources span a single day). Each merged row is tagged with which source tab's " +
            "filename it came from.",
        schema("tabIds" to "array", "newTabName" to "string", required = listOf("tabIds")),
    ),
    McpTool(
        "start_tailing",
        "Start watching a tab's backing file for external growth (e.g. `adb logcat > out.log` run " +
            "outside the app) and appending new lines as they arrive. Only works for a tab backed by " +
            "a real, currently-existing file (not a zip-extracted or merged tab). Session-only: " +
            "resets to off on app relaunch. Poll list_tabs or get_visible_lines afterward to observe growth.",
        schema("tabId" to "string", required = listOf("tabId")),
    ),
    McpTool("stop_tailing", "Stop watching a tab's backing file for growth.", schema("tabId" to "string", required = listOf("tabId"))),
)

// REST path/method per operation — the exact paths the JDK-HttpServer version served, so the curl
// escape hatch and ControlServerTest are unaffected. Keyed to the same op names as MCP_TOOLS.
private val REST_ROUTES: List<Triple<HttpMethod, String, String>> = listOf(
    Triple(HttpMethod.Get, "/tabs", "list_tabs"),
    Triple(HttpMethod.Post, "/open", "open_log_file"),
    Triple(HttpMethod.Post, "/split", "split_log_file"),
    Triple(HttpMethod.Post, "/split/preview", "preview_split_log_file"),
    Triple(HttpMethod.Post, "/close", "close_tab"),
    Triple(HttpMethod.Get, "/filter", "get_filter"),
    Triple(HttpMethod.Post, "/filter", "set_filter"),
    Triple(HttpMethod.Get, "/visible", "get_visible_lines"),
    Triple(HttpMethod.Get, "/selection", "get_selection"),
    Triple(HttpMethod.Post, "/selection", "select_lines"),
    Triple(HttpMethod.Post, "/toggle", "toggle_group"),
    Triple(HttpMethod.Post, "/expand-all", "expand_all"),
    Triple(HttpMethod.Post, "/collapse-all", "collapse_all"),
    Triple(HttpMethod.Get, "/tags", "get_tags"),
    Triple(HttpMethod.Get, "/crashes", "get_crash_sites"),
    Triple(HttpMethod.Post, "/annotations/note", "add_text_note"),
    Triple(HttpMethod.Post, "/annotations/log", "add_log_note"),
    Triple(HttpMethod.Post, "/annotations/update", "update_note_block"),
    Triple(HttpMethod.Post, "/annotations/move", "move_note_block"),
    Triple(HttpMethod.Post, "/annotations/delete", "delete_note_block"),
    Triple(HttpMethod.Post, "/annotations/save", "save_annotations"),
    Triple(HttpMethod.Post, "/annotations/load", "load_annotations"),
    Triple(HttpMethod.Post, "/export/analysis", "export_analysis"),
    Triple(HttpMethod.Post, "/export/filtered", "export_filtered_log"),
    Triple(HttpMethod.Get, "/filter/presets", "list_filter_presets"),
    Triple(HttpMethod.Post, "/filter/apply-preset", "apply_filter_preset"),
    Triple(HttpMethod.Post, "/merge", "merge_tabs"),
    Triple(HttpMethod.Post, "/tail/start", "start_tailing"),
    Triple(HttpMethod.Post, "/tail/stop", "stop_tailing"),
)
