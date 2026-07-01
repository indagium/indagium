package com.openlog.debug

import com.openlog.model.CrashSite
import com.openlog.model.Filter
import com.openlog.model.FilterMode
import com.openlog.model.LogItem
import com.openlog.model.LogLevel
import com.openlog.ui.AppState
import com.openlog.utils.computeCrashSites
import com.openlog.utils.computeItems
import com.openlog.utils.computeStackTraceGroups
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.net.URLDecoder

private const val GET = "GET"
private const val POST = "POST"
private const val STATUS_OK = 200
private const val STATUS_METHOD_NOT_ALLOWED = 405
private const val STATUS_SERVER_ERROR = 500
private const val DEFAULT_VISIBLE_LIMIT = 200
private const val OPEN_FILE_TIMEOUT_MS = 15_000L
private const val OPEN_FILE_POLL_INTERVAL_MS = 20L

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

    val boundPort: Int get() = server?.address?.port ?: port

    fun start() {
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        s.createContext("/tabs", handler(GET) { _, _ -> listTabs() })
        s.createContext("/open", handler(POST) { _, body -> openLogFile(body.str("path") ?: "") })
        s.createContext("/close", handler(POST) { _, body -> closeTab(body.str("tabId") ?: "") })
        s.createContext("/filter", handler(GET, POST) { ex, body ->
            if (ex.requestMethod == "GET") getFilter(queryParams(ex)["tabId"] ?: "")
            else setFilter(body.str("tabId") ?: "", body)
        })
        s.createContext("/visible", handler(GET) { ex, _ ->
            val q = queryParams(ex)
            getVisibleLines(q["tabId"] ?: "", q["limit"]?.toIntOrNull() ?: DEFAULT_VISIBLE_LIMIT, q["offset"]?.toIntOrNull() ?: 0)
        })
        s.createContext("/toggle", handler(POST) { _, body -> toggleGroupRoute(body.str("tabId") ?: "", body.str("gid") ?: "") })
        s.createContext("/tags", handler(GET) { ex, _ -> getTags(queryParams(ex)["tabId"] ?: "") })
        s.createContext("/crashes", handler(GET) { ex, _ -> getCrashSites(queryParams(ex)["tabId"] ?: "") })
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
                if (exchange.requestMethod !in allowedMethods) {
                    respond(exchange, STATUS_METHOD_NOT_ALLOWED, mapOf("error" to "method not allowed"))
                } else {
                    val body = if (exchange.requestMethod == "POST") readBody(exchange) else emptyMap()
                    respond(exchange, STATUS_OK, block(exchange, body))
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
        )
    }

    private fun openLogFile(path: String): Map<String, Any?> {
        if (path.isBlank()) return mapOf("error" to "missing path")
        val file = File(path)
        if (!file.exists()) return mapOf("error" to "file not found: $path")
        val absPath = file.absolutePath
        appState.openFile(file)
        // openFile is async (launches on ioScope); block this request thread until it settles.
        // If the file was already open, openFile returns synchronously and isLoading never
        // toggles true, so this loop exits immediately.
        val deadline = System.currentTimeMillis() + OPEN_FILE_TIMEOUT_MS
        while (appState.isLoading && System.currentTimeMillis() < deadline) Thread.sleep(OPEN_FILE_POLL_INTERVAL_MS)
        val tab = appState.tabs.find { it.sourcePath == absPath }
            ?: return mapOf("error" to "file did not load: $path")
        return mapOf("tabId" to tab.id, "filename" to tab.filename, "entryCount" to tab.logData.size)
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
            if (body.containsKey("kwText")) {
                body.str("kwText")?.let { result = result.copy(kwText = it) }
            }
            if (body.containsKey("kwRegex")) {
                body.bool("kwRegex")?.let { result = result.copy(kwRegex = it) }
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

    private fun toggleGroupRoute(tabId: String, gid: String): Map<String, Any?> {
        if (tabId.isBlank() || gid.isBlank()) return mapOf("error" to "missing tabId or gid")
        appState.toggleGroup(tabId, gid)
        return mapOf("ok" to true, "expanded" to (appState.tab(tabId)?.expanded?.contains(gid) ?: false))
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
        "kwText" to f.kwText,
        "kwRegex" to f.kwRegex,
        "mode" to f.mode.name,
        "excludeTags" to f.excludeTags.toList(),
        "pkgPrefixes" to f.pkgPrefixes.toList(),
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
