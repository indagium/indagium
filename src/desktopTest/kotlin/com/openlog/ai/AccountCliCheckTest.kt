package com.openlog.ai

import com.openlog.model.AiProviderKind
import com.openlog.model.defaultClaudeCodeAccountProfile
import com.openlog.model.defaultCodexAccountProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccountCliCheckTest {
    @Test
    fun codexCheckAcceptsChatGptLoginStatus() {
        var command: List<String>? = null
        val result = checkAccountCli(AiProviderKind.CODEX_ACCOUNT) {
            command = it
            LocalCliCommandResult(0, "Logged in using ChatGPT")
        }

        assertEquals(LocalAccountCli.codexCommand("login", "status"), command)
        assertTrue(result.isReady)
    }

    @Test
    fun codexCheckReportsUnauthenticatedOrMissingCli() {
        val unauthenticated = checkAccountCli(AiProviderKind.CODEX_ACCOUNT) {
            LocalCliCommandResult(1, "Not logged in")
        }
        val missing = checkAccountCli(AiProviderKind.CODEX_ACCOUNT) {
            throw IllegalStateException("No such file or directory")
        }

        assertFalse(unauthenticated.isReady)
        assertTrue(unauthenticated.message.contains("Not logged in"))
        assertFalse(missing.isReady)
        assertTrue(missing.message.contains("Could not start Codex CLI"))
    }

    @Test
    fun claudeCheckOnlyProbesTheInstalledCliWithoutSendingARequest() {
        // Pin an explicit executable so the probed command is deterministic regardless of whether a
        // `claude` binary happens to be installed on the machine running the test (auto-detection
        // would otherwise resolve to its absolute path here).
        val profile = defaultClaudeCodeAccountProfile().copy(executablePath = "claude")
        var command: List<String>? = null
        val result = checkAccountCli(profile) {
            command = it
            LocalCliCommandResult(0, "1.2.3")
        }

        assertEquals(listOf("claude", "--version"), command)
        assertTrue(result.isReady)
        assertTrue(result.message.contains("Account sign-in is checked when you send a request"))
    }

    @Test
    fun codexDiscoveryParsesTheLocalCatalogAndIgnoresLeadingDiagnostics() {
        var command: List<String>? = null
        val result = discoverAccountModels(AiProviderKind.CODEX_ACCOUNT) {
            command = it
            LocalCliCommandResult(
                0,
                "warning\n{\"models\":[{\"slug\":\"gpt-5.6-sol\",\"display_name\":\"GPT-5.6 Sol\"," +
                    "\"supported_reasoning_levels\":[{\"effort\":\"low\"},{\"effort\":\"high\"}]}," +
                    "{\"slug\":\"gpt-5.6-mini\"}]}",
            )
        }

        assertEquals(LocalAccountCli.codexCommand("debug", "models"), command)
        assertEquals(
            ModelDiscoveryResult.Available(
                listOf(
                    LlmModel("gpt-5.6-sol", "GPT-5.6 Sol", listOf("low", "high")),
                    LlmModel("gpt-5.6-mini"),
                ),
            ),
            result,
        )
    }

    @Test
    fun configuredExecutableIsUsedForChecksAndModelDiscovery() {
        val profile = defaultCodexAccountProfile().copy(executablePath = "/custom/bin/codex")
        var checkCommand: List<String>? = null
        val check = checkAccountCli(profile) {
            checkCommand = it
            LocalCliCommandResult(0, "Logged in using ChatGPT")
        }
        var modelsCommand: List<String>? = null
        val models = discoverAccountModels(profile) {
            modelsCommand = it
            LocalCliCommandResult(0, "{\"models\":[{\"slug\":\"gpt-5.6\"}]}")
        }

        assertTrue(check.isReady)
        assertEquals(listOf("/custom/bin/codex", "login", "status"), checkCommand)
        assertEquals(listOf("/custom/bin/codex", "debug", "models"), modelsCommand)
        assertEquals(ModelDiscoveryResult.Available(listOf(LlmModel("gpt-5.6"))), models)
    }

    @Test
    fun claudeDiscoveryOffersDocumentedAliasesWithoutClaimingAccountEntitlement() {
        val result = discoverAccountModels(AiProviderKind.CLAUDE_CODE_ACCOUNT) {
            error("Claude aliases must not run a model or CLI discovery command")
        }

        val claudeCodeReasoningEfforts = listOf("low", "medium", "high", "xhigh", "max")
        assertEquals(
            ModelDiscoveryResult.Available(
                listOf(
                    LlmModel("sonnet", "Sonnet (Claude Code alias)", claudeCodeReasoningEfforts),
                    LlmModel("opus", "Opus (Claude Code alias)", claudeCodeReasoningEfforts),
                    LlmModel("haiku", "Haiku (Claude Code alias)", claudeCodeReasoningEfforts),
                ),
            ),
            result,
        )
    }
}
