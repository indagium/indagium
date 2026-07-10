package com.openlog

import com.openlog.ui.mcpConfigSnippet
import com.openlog.ui.mcpUrl
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The connection-info dialog now hands out a URL, not a file path: openLog serves MCP natively at
// http://127.0.0.1:<port>/mcp, so any client connects with nothing to install. This is the whole
// point of the native-server rework — no Node bridge, no npm, no repo checkout, no per-machine
// path that could be wrong.
class McpConfigSnippetTest {
    @Test
    fun urlPointsAtTheMcpEndpoint() {
        assertEquals("http://127.0.0.1:8991/mcp", mcpUrl(8991))
    }

    @Test
    fun snippetIsUrlBasedWithNoCommandOrPath() {
        val snippet = mcpConfigSnippet(8991, "test-token")
        assertTrue(snippet.contains(""""url": "http://127.0.0.1:8991/mcp""""), "snippet should carry the /mcp URL:\n$snippet")
        // The whole reason for the rework: no subprocess command, no filesystem path to get wrong.
        assertTrue(!snippet.contains("command"), "URL-based config must not spawn a command:\n$snippet")
        assertTrue(!snippet.contains("index.ts"), "URL-based config must not reference a source path:\n$snippet")
    }

    @Test
    fun snippetCarriesTheBearerTokenHeader() {
        // Every request must be authenticated (see debug/ControlServer.kt) — the copied config must
        // carry the token, or a pasted-in client can reach the URL but never a real route.
        val snippet = mcpConfigSnippet(8991, "abc123token")
        assertTrue(snippet.contains(""""Authorization": "Bearer abc123token""""), "snippet should carry the auth header:\n$snippet")
    }
}
