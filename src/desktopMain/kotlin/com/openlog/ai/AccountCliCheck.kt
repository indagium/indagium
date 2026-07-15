package com.openlog.ai

import com.openlog.model.AiProviderKind
import com.openlog.model.AiProviderProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Result of a non-billed local account-agent prerequisite check. */
internal data class AccountCliCheck(val isReady: Boolean, val message: String)

internal data class LocalCliCommandResult(val exitCode: Int, val output: String)

internal fun interface LocalCliCommandRunner {
    fun run(command: List<String>): LocalCliCommandResult
}

/**
 * Finder for locally installed account CLIs. ChatGPT desktop bundles `codex` on macOS, but GUI
 * applications often do not inherit the shell PATH that exposes that bundle as a bare command.
 */
internal object LocalAccountCli {
    private const val MACOS_BUNDLED_CODEX = "/Applications/ChatGPT.app/Contents/Resources/codex"

    fun codexCommand(vararg arguments: String): List<String> = listOf(codexExecutable()) + arguments

    fun command(profile: AiProviderProfile, vararg arguments: String): List<String> =
        listOf(executable(profile.kind, profile.executablePath)) + arguments

    fun executable(kind: AiProviderKind, configuredPath: String = ""): String {
        val configured = configuredPath.trim()
        if (configured.isNotBlank()) return configured
        return detectExecutable(kind) ?: commandName(kind)
    }

    fun detectExecutable(kind: AiProviderKind): String? = candidatePaths(kind)
        .firstOrNull { it.isFile && it.canExecute() }
        ?.absolutePath

    /** A desktop app is useful diagnostics, but only Codex's app bundle carries a usable CLI. */
    fun detectedDesktopApp(kind: AiProviderKind): String? {
        val appName = when (kind) {
            AiProviderKind.CODEX_ACCOUNT -> "ChatGPT.app"
            AiProviderKind.CLAUDE_CODE_ACCOUNT -> "Claude.app"
            else -> return null
        }
        val home = System.getProperty("user.home").orEmpty()
        return listOf(File("/Applications", appName), File(home, "Applications/$appName"))
            .firstOrNull(File::isDirectory)
            ?.absolutePath
    }

    private fun codexExecutable(): String = executable(AiProviderKind.CODEX_ACCOUNT)

    private fun commandName(kind: AiProviderKind): String = when (kind) {
        AiProviderKind.CODEX_ACCOUNT -> "codex"
        AiProviderKind.CLAUDE_CODE_ACCOUNT -> "claude"
        else -> error("$kind does not use an account CLI")
    }

    private fun candidatePaths(kind: AiProviderKind): List<File> {
        val commandNames = when (kind) {
            AiProviderKind.CODEX_ACCOUNT -> listOf("codex", "codex.exe", "codex.cmd", "codex.bat")
            AiProviderKind.CLAUDE_CODE_ACCOUNT -> listOf("claude", "claude.exe", "claude.cmd", "claude.bat")
            else -> return emptyList()
        }
        val home = System.getProperty("user.home").orEmpty()
        val pathEntries = System.getenv("PATH").orEmpty().split(File.pathSeparator).filter(String::isNotBlank)
        val commonDirectories = buildList {
            addAll(pathEntries)
            add("/opt/homebrew/bin")
            add("/usr/local/bin")
            add("/usr/bin")
            if (home.isNotBlank()) add(File(home, ".local/bin").path)
            if (kind == AiProviderKind.CODEX_ACCOUNT) {
                add(File(MACOS_BUNDLED_CODEX).parent.orEmpty())
                if (home.isNotBlank()) add(File(home, "Applications/ChatGPT.app/Contents/Resources").path)
            }
            System.getenv("APPDATA")?.let { add(File(it, "npm").path) }
            System.getenv("LOCALAPPDATA")?.let {
                add(File(it, "Programs/Codex").path)
                add(File(it, "Programs/Claude").path)
            }
        }
        return commonDirectories.distinct().flatMap { directory -> commandNames.map { File(directory, it) } }
    }
}

/**
 * Account agents are CLI-backed. This intentionally checks only local prerequisites: it never
 * starts a model turn, sends log data, or consumes subscription usage.
 */
internal fun checkAccountCli(
    kind: AiProviderKind,
    commandRunner: LocalCliCommandRunner = SystemLocalCliCommandRunner,
): AccountCliCheck = checkAccountCli(defaultProfileFor(kind), commandRunner)

internal fun checkAccountCli(
    profile: AiProviderProfile,
    commandRunner: LocalCliCommandRunner = SystemLocalCliCommandRunner,
): AccountCliCheck = when (profile.kind) {
    AiProviderKind.CODEX_ACCOUNT -> checkCodex(profile, commandRunner)
    AiProviderKind.CLAUDE_CODE_ACCOUNT -> checkClaudeCode(profile, commandRunner)
    else -> AccountCliCheck(false, "This provider does not use a local account CLI.")
}

private fun checkCodex(profile: AiProviderProfile, commandRunner: LocalCliCommandRunner): AccountCliCheck = try {
    val result = commandRunner.run(LocalAccountCli.command(profile, "login", "status"))
    val output = result.output.trim()
    when {
        result.exitCode == 0 && output.contains("logged in", ignoreCase = true) ->
            AccountCliCheck(true, output.ifBlank { "Codex CLI is signed in." })
        result.exitCode == 0 -> AccountCliCheck(false, output.ifBlank { "Codex CLI is not signed in." })
        else -> AccountCliCheck(false, output.ifBlank { "Codex CLI login check failed (exit ${result.exitCode})." })
    }
} catch (error: Exception) {
    AccountCliCheck(false, "Could not start Codex CLI: ${error.message ?: "unknown error"}")
}

private fun checkClaudeCode(profile: AiProviderProfile, commandRunner: LocalCliCommandRunner): AccountCliCheck = try {
    if (
        commandRunner === SystemLocalCliCommandRunner &&
        profile.executablePath.isBlank() &&
        LocalAccountCli.detectExecutable(AiProviderKind.CLAUDE_CODE_ACCOUNT) == null
    ) {
        LocalAccountCli.detectedDesktopApp(AiProviderKind.CLAUDE_CODE_ACCOUNT)?.let { appPath ->
            return AccountCliCheck(
                false,
                "Claude desktop app was found at $appPath, but Claude Code CLI was not found. The desktop app cannot run panel requests.",
            )
        }
    }
    val result = commandRunner.run(LocalAccountCli.command(profile, "--version"))
    val output = result.output.trim()
    if (result.exitCode == 0) {
        AccountCliCheck(
            true,
            "Claude Code${output.takeIf(String::isNotBlank)?.let { " $it" }.orEmpty()} is ready. " +
                "Account sign-in is checked when you send a request.",
        )
    } else {
        AccountCliCheck(false, output.ifBlank { "Claude Code CLI check failed (exit ${result.exitCode})." })
    }
} catch (error: Exception) {
    AccountCliCheck(false, "Could not start Claude Code CLI: ${error.message ?: "unknown error"}")
}

/** Returns models exposed by the signed-in local Codex catalog without running a model turn. */
internal fun discoverAccountModels(
    kind: AiProviderKind,
    commandRunner: LocalCliCommandRunner = SystemLocalCliCommandRunner,
): ModelDiscoveryResult = discoverAccountModels(defaultProfileFor(kind), commandRunner)

internal fun discoverAccountModels(
    profile: AiProviderProfile,
    commandRunner: LocalCliCommandRunner = SystemLocalCliCommandRunner,
): ModelDiscoveryResult {
    if (profile.kind != AiProviderKind.CODEX_ACCOUNT) {
        // Claude Code exposes documented session aliases but no entitlement-aware model-list
        // command. These are useful choices, not a claim that either is enabled for this account.
        return ModelDiscoveryResult.Available(
            listOf(
                LlmModel("sonnet", "Sonnet (Claude Code alias)", CLAUDE_CODE_REASONING_EFFORTS),
                LlmModel("opus", "Opus (Claude Code alias)", CLAUDE_CODE_REASONING_EFFORTS),
                LlmModel("haiku", "Haiku (Claude Code alias)", CLAUDE_CODE_REASONING_EFFORTS),
            ),
        )
    }
    return try {
        val result = commandRunner.run(LocalAccountCli.command(profile, "debug", "models"))
        if (result.exitCode != 0) {
            return ModelDiscoveryResult.Unavailable(result.output.trim().ifBlank { "Codex model catalog check failed (exit ${result.exitCode})." })
        }
        val jsonStart = result.output.indexOf('{')
        if (jsonStart < 0) return ModelDiscoveryResult.Unavailable("Codex returned no readable model catalog.")
        val models = json.parseToJsonElement(result.output.substring(jsonStart)).jsonObject["models"]
            ?.jsonArray
            ?.mapNotNull { entry ->
                val model = entry.jsonObject
                val id = model["slug"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val displayName = model["display_name"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) ?: id
                val reasoningEfforts = model["supported_reasoning_levels"]
                    ?.jsonArray
                    ?.mapNotNull { level -> level.jsonObject["effort"]?.jsonPrimitive?.content?.takeIf(String::isNotBlank) }
                    .orEmpty()
                LlmModel(id, displayName, reasoningEfforts)
            }
            ?.distinctBy { it.id }
            .orEmpty()
        if (models.isEmpty()) ModelDiscoveryResult.Unavailable("Codex returned an empty model catalog.")
        else ModelDiscoveryResult.Available(models)
    } catch (error: Exception) {
        ModelDiscoveryResult.Unavailable("Could not read Codex models: ${error.message ?: "unknown error"}")
    }
}

private object SystemLocalCliCommandRunner : LocalCliCommandRunner {
    private const val COMMAND_TIMEOUT_SECONDS = 10L

    override fun run(command: List<String>): LocalCliCommandResult {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        // `codex debug models` emits a large JSON catalog. Drain stdout while it is running so
        // the child cannot fill its pipe and then appear to time out before it exits.
        val output = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor()
            output.get()
            return LocalCliCommandResult(-1, "Command timed out after $COMMAND_TIMEOUT_SECONDS seconds.")
        }
        return LocalCliCommandResult(process.exitValue(), output.get())
    }
}

private fun defaultProfileFor(kind: AiProviderKind): AiProviderProfile = AiProviderProfile(
    id = "account-cli-check",
    displayName = kind.label,
    baseUrl = "",
    model = "",
    kind = kind,
)

private val json = Json { ignoreUnknownKeys = true }

/** Claude Code CLI `--effort` accepts exactly these documented levels. */
private val CLAUDE_CODE_REASONING_EFFORTS = listOf("low", "medium", "high", "xhigh", "max")
