package com.openlog

import com.openlog.ui.codexMcpConfigSnippet
import kotlin.test.Test
import kotlin.test.assertTrue

class CodexMcpConfigSnippetTest {
    @Test
    fun usesTomlWithAStaticBearerHeader() {
        val snippet = codexMcpConfigSnippet(8991, "abc123token")

        assertTrue(snippet.contains("[mcp_servers.indagium]"), "Codex config must define the indagium server:\n$snippet")
        assertTrue(snippet.contains("url = \"http://127.0.0.1:8991/mcp\""), "Codex config must use the MCP URL:\n$snippet")
        assertTrue(snippet.contains("http_headers = { Authorization = \"Bearer abc123token\" }"), "Codex config must carry the bearer header:\n$snippet")
    }

    @Test
    fun approvesOnlyListTabs() {
        val snippet = codexMcpConfigSnippet(8991, "test-token")

        assertTrue(snippet.contains("[mcp_servers.indagium.tools.list_tabs]"), "Only list_tabs should be pre-approved:\n$snippet")
        assertTrue(snippet.contains("approval_mode = \"approve\""), "list_tabs should run in non-interactive Codex calls:\n$snippet")
        assertTrue(!snippet.contains("default_tools_approval_mode"), "Other indagium tools must keep Codex's normal approval flow:\n$snippet")
    }
}
