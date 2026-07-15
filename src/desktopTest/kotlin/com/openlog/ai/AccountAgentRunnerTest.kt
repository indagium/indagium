package com.openlog.ai

import com.openlog.model.AiProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AccountAgentRunnerTest {
    // resolveAccountAgentWorkspace covers a real regression: Claude Code's --resume looks up a
    // session by *project directory*, not id alone, so a follow-up in a workspace different from
    // the one the session was created in fails with "No conversation found with session ID: ...".
    // Verified against the real CLI - see the fix's history for the reproduction.

    @Test
    fun claudeCodeReusesTheSameWorkspaceAcrossCallsInOneSession() {
        val session = AiSession(tabId = "t1")
        try {
            val first = resolveAccountAgentWorkspace(session, AiProviderKind.CLAUDE_CODE_ACCOUNT)
            val second = resolveAccountAgentWorkspace(session, AiProviderKind.CLAUDE_CODE_ACCOUNT)

            assertEquals(first, second)
            assertEquals(first, session.claudeCodeWorkspace)
        } finally {
            session.deleteClaudeCodeWorkspace()
        }
    }

    @Test
    fun codexGetsAFreshWorkspaceEveryCallAndNeverStoresOneOnTheSession() {
        val session = AiSession(tabId = "t1")
        val first = resolveAccountAgentWorkspace(session, AiProviderKind.CODEX_ACCOUNT)
        val second = resolveAccountAgentWorkspace(session, AiProviderKind.CODEX_ACCOUNT)

        assertNotEquals(first, second)
        assertEquals(null, session.claudeCodeWorkspace)
    }

    @Test
    fun deletingTheClaudeCodeWorkspaceClearsItFromTheSession() {
        val session = AiSession(tabId = "t1")
        val workspace = resolveAccountAgentWorkspace(session, AiProviderKind.CLAUDE_CODE_ACCOUNT)
        assertTrue(java.nio.file.Files.isDirectory(workspace))

        session.deleteClaudeCodeWorkspace()

        assertEquals(null, session.claudeCodeWorkspace)
        assertTrue(!java.nio.file.Files.exists(workspace))
    }

    @Test
    fun codexManagedMcpConfigUsesTheStaticHeaderAcceptedByAppServer() {
        assertEquals(
            listOf(
                "--config", "mcp_servers.openlog.url=\"http://127.0.0.1:41723/mcp\"",
                "--config", "mcp_servers.openlog.http_headers={ Authorization = \"Bearer temporary-token\" }",
                "--config", "mcp_servers.openlog.approval_mode=\"never\"",
            ),
            codexManagedMcpConfig("http://127.0.0.1:41723/mcp", "temporary-token"),
        )
    }

    // decideCodexElicitation covers the mcpServer/elicitation/request handshake: it is a tool-call
    // approval, not OAuth, and only requests for the managed openlog server should be auto-accepted.

    @Test
    fun openLogToolCallApprovalIsAccepted() {
        val params = Json.parseToJsonElement(
            """
            {"threadId":"t1","turnId":"turn1","serverName":"openlog","mode":"form",
              "_meta":{"codex_approval_kind":"mcp_tool_call","persist":["session","always"],
                "tool_description":"List open tabs","tool_params":{}},
              "message":"Allow the openlog MCP server to run tool \"list_tabs\"?",
              "requestedSchema":{"type":"object","properties":{}}}
            """.trimIndent(),
        ).jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(decision.isOpenLogToolApproval)
        assertEquals("accept", decision.response["action"]?.jsonPrimitive?.content)
        assertTrue(decision.response["content"]!!.jsonObject.isEmpty())
    }

    @Test
    fun openLogApprovalIsRecognizedByApprovalKindAloneWhenServerNameIsMissing() {
        val params = Json.parseToJsonElement(
            """{"_meta":{"codex_approval_kind":"mcp_tool_call"},"message":"Allow tool?"}""",
        ).jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(decision.isOpenLogToolApproval)
        assertEquals("accept", decision.response["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun otherServerElicitationIsDeclinedWithoutAborting() {
        // e.g. an OAuth-backed integration configured in the user's own ~/.codex/config.toml.
        val params = Json.parseToJsonElement(
            """{"threadId":"t1","turnId":"turn1","serverName":"figma","mode":"form","message":"Authorization required"}""",
        ).jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(!decision.isOpenLogToolApproval)
        assertEquals("decline", decision.response["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun elicitationWithNoIdentifyingFieldsIsTreatedAsNotOpenLog() {
        val params = Json.parseToJsonElement("""{"message":"Authorization required"}""").jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(!decision.isOpenLogToolApproval)
        assertEquals("decline", decision.response["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun userMcpServerNamesAreEnumeratedFromConfigIncludingNestedTables() {
        val config = """
            model = "gpt-5.5"

            [mcp_servers.xcode-tools]
            command = "xcode-mcp"

            [mcp_servers.xcode-tools.tools.XcodeRead]
            enabled = true

            [mcp_servers.figma]
            url = "https://figma.example/mcp"

            [mcp_servers.node_repl.env]
            NODE_ENV = "dev"

            mcp_servers.inline_one = { command = "x" }
        """.trimIndent()

        assertEquals(
            setOf("xcode-tools", "figma", "node_repl", "inline_one"),
            codexUserMcpServerNames(config),
        )
    }

    @Test
    fun theManagedOpenLogServerIsNeverDisabled() {
        // A user could conceivably have their own [mcp_servers.openlog]; it must not be turned off.
        assertEquals(emptySet(), codexUserMcpServerNames("[mcp_servers.openlog]\nurl = \"x\""))
    }

    @Test
    fun disableConfigIsEmptyWhenTheCodexConfigIsMissing() {
        assertEquals(
            emptyList(),
            codexDisableUserServersConfig(configFile = java.nio.file.Path.of("/nonexistent/openlog/codex/config.toml")),
        )
    }
}
