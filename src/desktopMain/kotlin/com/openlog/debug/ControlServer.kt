package com.openlog.debug

import com.openlog.model.CrashSite
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.model.SavedFilter
import com.openlog.ui.AppState
import com.openlog.utils.ZipLogCandidate
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeItems
import com.openlog.utils.computeStackTraceGroups
import com.openlog.utils.isZipFile
import com.openlog.utils.listLogcatCandidates
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

private const val GET = "GET"
private const val POST = "POST"
private const val STATUS_OK = 200
private const val STATUS_METHOD_NOT_ALLOWED = 405
private const val STATUS_FORBIDDEN = 403
private const val STATUS_SERVER_ERROR = 500
private const val DEFAULT_VISIBLE_LIMIT = 200
private const val OPEN_FILE_TIMEOUT_MS = 15_000L
private const val OPEN_FILE_POLL_INTERVAL_MS = 20L
private const val CLIENT_STALE_MS = 5 * 60_000L
private const val CLIENT_ID_HEADER = "X-OpenLog-Client-Id"
private const val CLIENT_NAME_HEADER = "X-OpenLog-Client-Name"
private const val DEFAULT_CLIENT_NAME = "MCP client"

// A connecting MCP client (the mcp-server/ stdio bridge, not a raw curl caller) self-identifies
// with a per-process-launch random id + optional human-readable name (see mcp-server's
// openlogClient.ts). Surfaced in the Settings "Connection info" popup so a user can see who's
// talking to their app and, if unwanted, block them.
data class ConnectedClientInfo(val id: String, val name: String, val lastSeenMs: Long, val blocked: Boolean)

/**
 * Localhost-only debug/automation control surface for the running app. Lets an MCP server (or
 * plain curl) drive real app state — open files, change filters, toggle groups — and read back
 * exactly what's rendered, without a screenshot. See Main.kt for startup gating: this must never
 * start in packaged builds, only when OPENLOG_DEBUG_CONTROL / -Dopenlog.debugControl is set.
 *
 * AppState's mutableStateOf fields are documented as snapshot-safe to read/write from any
 * thread, so HttpServer's own request-handling thread can call AppState directly.
 */
class ControlServer(private val appState: AppState, private val port: Int) {
    private var server: HttpServer? = null

    private data class ClientRecord(val name: String, val lastSeenMs: Long)

    private val clients = ConcurrentHashMap<String, ClientRecord>()
    private val blockedIds = ConcurrentHashMap.newKeySet<String>()

    val boundPort: Int get() = server?.address?.port ?: port

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

    fun start() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        s.createContext("/tabs", handler(GET) { _, _ -> listTabs() })
        s.createContext("/open", handler(POST) { _, body -> openLogFile(body.str("path") ?: "", body.str("entryPath")) })
        s.createContext("/close", handler(POST) { _, body -> closeTab(body.str("tabId") ?: "") })
        s.createContext("/filter", handler(GET, POST) { ex, body ->
            if (ex.requestMethod == "GET") getFilter(queryParams(ex)["tabId"] ?: "")
            else setFilter(body.str("tabId") ?: "", body)
        })
        s.createContext("/visible", handler(GET) { ex, _ ->
            val q = queryParams(ex)
            getVisibleLines(q["tabId"] ?: "", q["limit"]?.toIntOrNull() ?: DEFAULT_VISIBLE_LIMIT, q["offset"]?.toIntOrNull() ?: 0)
        })
        s.createContext("/selection", handler(GET, POST) { ex, body ->
            if (ex.requestMethod == "GET") getSelection(queryParams(ex)["tabId"] ?: "")
            else setSelection(body.str("tabId") ?: "", body.intList("lineIds") ?: emptyList())
        })
        s.createContext("/toggle", handler(POST) { _, body -> toggleGroupRoute(body.str("tabId") ?: "", body.str("gid") ?: "") })
        s.createContext("/expand-all", handler(POST) { _, body -> expandAllRoute(body.str("tabId") ?: "") })
        s.createContext("/collapse-all", handler(POST) { _, body -> collapseAllRoute(body.str("tabId") ?: "") })
        s.createContext("/tags", handler(GET) { ex, _ -> getTags(queryParams(ex)["tabId"] ?: "") })
        s.createContext("/crashes", handler(GET) { ex, _ -> getCrashSites(queryParams(ex)["tabId"] ?: "") })
        s.createContext("/annotations/note", handler(POST) { _, body ->
            addTextNoteRoute(body.str("tabId") ?: "", body.str("text") ?: "", body.str("afterId"))
        })
        s.createContext("/annotations/log", handler(POST) { _, body ->
            addLogNoteRoute(body.str("tabId") ?: "", body.intList("lineIds") ?: emptyList(), body.str("caption") ?: "")
        })
        s.createContext("/annotations/update", handler(POST) { _, body ->
            updateAnnotationRoute(body.str("tabId") ?: "", body.str("blockId") ?: "", body.str("text") ?: "")
        })
        s.createContext("/annotations/move", handler(POST) { _, body ->
            moveAnnotationRoute(body.str("tabId") ?: "", body.str("blockId") ?: "", body.int("delta") ?: 0)
        })
        s.createContext("/annotations/delete", handler(POST) { _, body ->
            deleteAnnotationRoute(body.str("tabId") ?: "", body.str("blockId") ?: "")
        })
        s.createContext("/annotations/save", handler(POST) { _, body ->
            saveAnnotationsRoute(body.str("tabId") ?: "", body.str("path") ?: "")
        })
        s.createContext("/annotations/load", handler(POST) { _, body ->
            loadAnnotationsRoute(body.str("tabId") ?: "", body.str("path") ?: "")
        })
        s.createContext("/export/analysis", handler(POST) { _, body -> exportAnalysisRoute(body.str("tabId") ?: "", body.str("path") ?: "") })
        s.createContext("/export/filtered", handler(POST) { _, body ->
            exportFilteredRoute(body.str("tabId") ?: "", body.str("path") ?: "", body.str("format") ?: "txt")
        })
        s.createContext("/filter/presets", handler(GET) { _, _ -> listFilterPresets() })
        s.createContext("/filter/apply-preset", handler(POST) { _, body ->
            applyFilterPresetRoute(body.str("tabId") ?: "", body.str("presetId") ?: "")
        })
        s.createContext("/merge", handler(POST) { _, body ->
            mergeTabsRoute(body.strList("tabIds") ?: emptyList(), body.str("newTabName") ?: "Merged")
        })
        s.createContext("/tail/start", handler(POST) { _, body -> startTailingRoute(body.str("tabId") ?: "") })
        s.createContext("/tail/stop", handler(POST) { _, body -> stopTailingRoute(body.str("tabId") ?: "") })
        // No executor set: HttpServer's default runs requests sequentially on the thread that
        // called start() — fine for a low-throughput, single-client dev tool, and means no
        // extra synchronization is needed around AppState access from this server.
        s.start()
        server = s
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    // Wraps a route body with method-allowlist checking, JSON body parsing, and a catch-all that
    // turns any unexpected failure into a 500 instead of dropping the connection — a control
    // surface used by automated tooling should never hang a caller on an unhandled exception.
    private fun handler(vararg allowedMethods: String, block: (HttpExchange, Map<String, Any?>) -> Any?): HttpHandler =
        HttpHandler { exchange ->
            try {
                val clientId = exchange.requestHeaders.getFirst(CLIENT_ID_HEADER)
                val blocked = clientId != null && clientId in blockedIds
                if (clientId != null && !blocked) {
                    val name = exchange.requestHeaders.getFirst(CLIENT_NAME_HEADER)?.takeIf { it.isNotBlank() } ?: DEFAULT_CLIENT_NAME
                    clients[clientId] = ClientRecord(name, System.currentTimeMillis())
                }
                when {
                    blocked -> respond(exchange, STATUS_FORBIDDEN, mapOf("error" to "this client is blocked"))
                    exchange.requestMethod !in allowedMethods ->
                        respond(exchange, STATUS_METHOD_NOT_ALLOWED, mapOf("error" to "method not allowed"))
                    else -> {
                        val body = if (exchange.requestMethod == "POST") readBody(exchange) else emptyMap()
                        respond(exchange, STATUS_OK, block(exchange, body))
                    }
                }
            } catch (e: Exception) {
                respond(exchange, STATUS_SERVER_ERROR, mapOf("error" to (e.message ?: e.toString())))
            } finally {
                exchange.close()
            }
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

    private fun openLogFile(path: String, entryPath: String?): Map<String, Any?> {
        if (path.isBlank()) return mapOf("error" to "missing path")
        val file = File(path)
        if (!file.exists()) return mapOf("error" to "file not found: $path")
        if (isZipFile(file)) return openZipRoute(file, entryPath)
        val absPath = file.absolutePath
        appState.openFile(file)
        awaitLoad()
        val tab = appState.tabs.find { it.sourcePath == absPath }
            ?: return mapOf("error" to "file did not load: $path")
        return mapOf("tabId" to tab.id, "filename" to tab.filename, "entryCount" to tab.logData.size)
    }

    // Mirrors the UI's single/multi-candidate split (AppState.openZipFile): a lone candidate
    // auto-opens like a plain file; 2+ candidates need a pick. Since a caller here isn't clicking
    // a picker dialog, it echoes the candidate list so a follow-up call can pass entryPath to
    // pick one directly — same round trip the UI's picker dialog does through openZipEntries().
    private fun openZipRoute(file: File, entryPath: String?): Map<String, Any?> {
        val candidates = listLogcatCandidates(file)
        if (candidates.isEmpty()) return mapOf("error" to "no candidate log files found in zip: ${file.path}")
        val target = when {
            entryPath != null -> candidates.find { it.entryPath == entryPath }
                ?: return mapOf("error" to "no such entry in zip: $entryPath")
            candidates.size == 1 -> candidates.first()
            else -> return mapOf("needsSelection" to true, "candidates" to candidates.map { zipCandidateToMap(it) })
        }
        val sourcePath = "${file.absolutePath}!${target.entryPath}"
        appState.openZipEntries(file, listOf(target))
        awaitLoad()
        val tab = appState.tabs.find { it.sourcePath == sourcePath }
            ?: return mapOf("error" to "entry did not load: ${target.entryPath}")
        return mapOf("tabId" to tab.id, "filename" to tab.filename, "entryCount" to tab.logData.size)
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

    private fun filterPresetToMap(preset: SavedFilter): Map<String, Any?> = mapOf(
        "id" to preset.id,
        "name" to preset.name,
        "levels" to preset.levels.map { it.key.toString() },
        "mode" to preset.mode.name,
        "activeTags" to preset.activeTags.toList(),
        "excludeTags" to preset.excludeTags.toList(),
    )

    private fun queryParams(exchange: HttpExchange): Map<String, String> =
        (exchange.requestURI.query ?: "").split("&").filter { it.isNotBlank() }.associate { pair ->
            val idx = pair.indexOf('=')
            if (idx < 0) URLDecoder.decode(pair, "UTF-8") to ""
            else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
        }

    private fun readBody(exchange: HttpExchange): Map<String, Any?> {
        val text = exchange.requestBody.bufferedReader().readText()
        if (text.isBlank()) return emptyMap()
        @Suppress("UNCHECKED_CAST")
        return (Json.decode(text) as? Map<String, Any?>) ?: emptyMap()
    }

    private fun respond(exchange: HttpExchange, status: Int, body: Any?) {
        val bytes = Json.encode(body).toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
