package com.openlog

import com.openlog.ui.jsonEscape
import com.openlog.ui.mcpConfigSnippet
import kotlin.test.Test
import kotlin.test.assertTrue

// The control server only ever runs via `./gradlew desktopRun` (never in a packaged build — see
// ControlServer's kdoc), so user.dir is reliably the project root there, and the "Copy config for
// your MCP client" snippet in Settings can — and must — embed an absolute path: a client like
// LM Studio spawns MCP servers from its own working directory, not the project's, so the relative
// path the repo's own .mcp.json gets away with (because Claude Code spawns with cwd = project
// root) would silently fail to resolve there.
class McpConfigSnippetTest {
    @Test
    fun snippetEmbedsAbsoluteServerPath() {
        val snippet = mcpConfigSnippet(8991)
        val expected = java.io.File(System.getProperty("user.dir"), "mcp-server/src/index.ts").absolutePath
        assertTrue(snippet.contains(expected.jsonEscape()), "expected snippet to contain absolute path $expected:\n$snippet")
        assertTrue(snippet.contains("\"http://127.0.0.1:8991\""))
    }

    @Test
    fun jsonEscapeDoublesBackslashesForWindowsPaths() {
        assertTrue("C:\\\\Users\\\\me\\\\mcp-server" == "C:\\Users\\me\\mcp-server".jsonEscape())
    }
}
