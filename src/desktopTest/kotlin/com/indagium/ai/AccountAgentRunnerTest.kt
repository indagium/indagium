package com.indagium.ai

import com.indagium.model.AiProviderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
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
    fun codexManagedMcpConfigKeepsTheTemporaryBearerTokenOutOfCommandArguments() {
        assertEquals(
            listOf(
                "--config", "mcp_servers.indagium.url=\"http://127.0.0.1:41723/mcp\"",
                "--config", "mcp_servers.indagium.bearer_token_env_var=\"INDAGIUM_MCP_TOKEN\"",
            ),
            codexManagedMcpConfig("http://127.0.0.1:41723/mcp"),
        )
        assertEquals(mapOf("INDAGIUM_MCP_TOKEN" to "temporary-token"), codexManagedMcpEnvironment("temporary-token"))
    }

    @Test
    fun codexManagedMcpApprovalPolicyAllowsOnlyMcpElicitations() {
        val policy = codexManagedMcpApprovalPolicy().getValue("granular").jsonObject

        assertEquals(true, policy["mcp_elicitations"]?.jsonPrimitive?.boolean)
        assertEquals(false, policy["rules"]?.jsonPrimitive?.boolean)
        assertEquals(false, policy["sandbox_approval"]?.jsonPrimitive?.boolean)
        assertEquals(false, policy["request_permissions"]?.jsonPrimitive?.boolean)
        assertEquals(false, policy["skill_approval"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun codexManagedMcpCapabilitiesOptIntoExperimentalAppServerFeatures() {
        assertEquals(true, codexManagedMcpCapabilities()["experimentalApi"]?.jsonPrimitive?.boolean)
    }

    // Regression guard for the "one shared constant" invariant described on MANAGED_MCP_SERVER_NAME
    // and decideCodexElicitation: this derives the server name from the actual config the runner
    // builds for Codex, rather than hardcoding "indagium" on both sides, so an edit that renames
    // one call site without the other fails here instead of silently hiding behind the
    // `|| approvalKind == "mcp_tool_call"` fallback (that's why _meta is omitted below).
    @Test
    fun theServerNameConfiguredForCodexIsTheSameOneDecideCodexElicitationAccepts() {
        val config = codexManagedMcpConfig("http://127.0.0.1:41723/mcp")
        val configuredServerName = Regex("""mcp_servers\.([A-Za-z0-9_-]+)\.url=""")
            .find(config.joinToString(" "))
            ?.groupValues
            ?.get(1)
            ?: error("codexManagedMcpConfig did not produce an mcp_servers.<name>.url entry:\n$config")

        val params = Json.parseToJsonElement("""{"serverName":"$configuredServerName","mode":"form"}""").jsonObject
        val decision = decideCodexElicitation(params)

        assertTrue(decision.isManagedServerApproval)
        assertEquals("accept", decision.response["action"]?.jsonPrimitive?.content)
    }

    // decideCodexElicitation covers the mcpServer/elicitation/request handshake: it is a tool-call
    // approval, not OAuth, and only requests for the managed indagium server should be auto-accepted.

    @Test
    fun managedServerToolCallApprovalIsAccepted() {
        val params = Json.parseToJsonElement(
            """
            {"threadId":"t1","turnId":"turn1","serverName":"indagium","mode":"form",
              "_meta":{"codex_approval_kind":"mcp_tool_call","persist":["session","always"],
                "tool_description":"List open tabs","tool_params":{}},
              "message":"Allow the indagium MCP server to run tool \"list_tabs\"?",
              "requestedSchema":{"type":"object","properties":{}}}
            """.trimIndent(),
        ).jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(decision.isManagedServerApproval)
        assertEquals("accept", decision.response["action"]?.jsonPrimitive?.content)
        assertTrue(decision.response["content"]!!.jsonObject.isEmpty())
    }

    @Test
    fun managedServerApprovalIsRecognizedByApprovalKindAloneWhenServerNameIsMissing() {
        val params = Json.parseToJsonElement(
            """{"_meta":{"codex_approval_kind":"mcp_tool_call"},"message":"Allow tool?"}""",
        ).jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(decision.isManagedServerApproval)
        assertEquals("accept", decision.response["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun otherServerElicitationIsDeclinedWithoutAborting() {
        // e.g. an OAuth-backed integration configured in the user's own ~/.codex/config.toml.
        val params = Json.parseToJsonElement(
            """{"threadId":"t1","turnId":"turn1","serverName":"figma","mode":"form","message":"Authorization required"}""",
        ).jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(!decision.isManagedServerApproval)
        assertEquals("decline", decision.response["action"]?.jsonPrimitive?.content)
    }

    @Test
    fun elicitationWithNoIdentifyingFieldsIsTreatedAsNotManaged() {
        val params = Json.parseToJsonElement("""{"message":"Authorization required"}""").jsonObject

        val decision = decideCodexElicitation(params)

        assertTrue(!decision.isManagedServerApproval)
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
    fun theManagedServerIsNeverDisabled() {
        // A user could conceivably have their own [mcp_servers.indagium]; it must not be turned off.
        assertEquals(emptySet(), codexUserMcpServerNames("[mcp_servers.indagium]\nurl = \"x\""))
    }

    @Test
    fun disableConfigIsEmptyWhenTheCodexConfigIsMissing() {
        assertEquals(
            emptyList(),
            codexDisableUserServersConfig(configFile = java.nio.file.Path.of("/nonexistent/openlog/codex/config.toml")),
        )
    }
}
