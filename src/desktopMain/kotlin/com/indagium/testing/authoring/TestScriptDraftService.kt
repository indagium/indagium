package com.indagium.testing.authoring

import com.indagium.model.AiUsageStats
import com.indagium.testing.model.DEFAULT_SCRIPT_OUTPUT_CAP_BYTES
import com.indagium.testing.model.DEFAULT_SCRIPT_TIMEOUT_MS
import com.indagium.testing.model.MAX_SCRIPT_TOOL_NAME_CHARS
import com.indagium.testing.model.ScriptPermission
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.store.MAX_SCRIPT_TIMEOUT_MS
import com.indagium.testing.store.StoreResult
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** The environment variables every script run sets; a script parameter may not reuse their names (in any letter case). */
internal val ALWAYS_SET_SCRIPT_ENV: List<String> = listOf("DEVICE", "PACKAGE", "RUN_DIR", "CASE_ID", "STEP_ID")

/**
 * UI-free, preview-first drafting of a library [TestScript] from a plain-language description. The model call is injected so tests (and
 * the app) decide how text is produced. Nothing here writes the library: the caller shows the returned script for review and saves it.
 *
 * Whatever the model proposes is forced to [ScriptPermission.ASK] (a generated command must ask before an agent runs it) and goes
 * through the same strict envelope decode and validation as an imported script file.
 */
internal class TestScriptDraftService(
    private val generate: suspend (
        profileId: String,
        prompt: String,
        model: String?,
        effort: String?,
        onUsage: (AiUsageStats) -> Unit,
    ) -> String,
    private val hostOs: () -> String = { System.getProperty("os.name").orEmpty() },
) {
    /**
     * Asks the model for a script. [existing] is every script already in the library; with [current] (a regeneration) the result keeps
     * that script's id, working directory and output cap, and its own tool name stays available to it.
     */
    @Suppress("TooGenericExceptionCaught") // Provider failures become a user-facing result after cancellation is preserved.
    suspend fun generate(
        profileId: String,
        description: String,
        existing: List<TestScript>,
        model: String? = null,
        effort: String? = null,
        targetHint: ScriptTarget? = null,
        current: TestScript? = null,
        onUsage: (AiUsageStats) -> Unit = {},
    ): StoreResult<TestScript> {
        if (description.isBlank()) return StoreResult.Invalid("Describe what the script should do.")
        if (description.length > MAX_DESCRIPTION_CHARS) {
            return StoreResult.Invalid("The description is limited to $MAX_DESCRIPTION_CHARS characters.")
        }
        val others = existing.filterNot { it.id == current?.id }
        val prompt = promptFor(description, others.map { it.toolName }.toSet(), targetHint, hostOs(), current)
        val response = try {
            generate(profileId, prompt, model, effort, onUsage)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            return StoreResult.Invalid(failure.message ?: "Could not generate a script.")
        }
        val parsed = parse(response, others, targetHint)
        val script = (parsed as? StoreResult.Ok)?.value ?: return parsed
        return StoreResult.Ok(
            if (current == null) {
                script
            } else {
                script.copy(
                    id = current.id,
                    workingDir = current.workingDir,
                    outputCapBytes = current.outputCapBytes,
                    permission = if (current.permission == ScriptPermission.SETUP_TEARDOWN_ONLY) current.permission else ScriptPermission.ASK,
                )
            },
        )
    }

    /** The prompt that asks for one JSON script object; [current] (when given) is shown so the model returns an improved version. */
    fun promptFor(
        description: String,
        existingToolNames: Set<String>,
        targetHint: ScriptTarget?,
        hostOs: String,
        current: TestScript? = null,
    ): String = buildString {
        appendLine("You write one shell script for an Android QA test library. Do not run anything and do not call tools.")
        appendLine("Return ONLY a single JSON object, with no markdown fences and no prose, that has exactly these fields:")
        appendLine(
            "{\"toolName\":\"...\",\"description\":\"...\",\"params\":[{\"name\":\"...\",\"type\":\"STRING|INT|BOOL\"," +
                "\"description\":\"...\",\"required\":true,\"defaultValue\":null}],\"commandTemplate\":\"...\"," +
                "\"target\":\"HOST_SHELL|ADB_SHELL\",\"timeoutMs\":30000}",
        )
        appendLine()
        appendLine("Rules for the fields:")
        appendLine(
            "- toolName: lowercase letters, digits and _ only, starting with a letter, 2-$MAX_SCRIPT_TOOL_NAME_CHARS characters " +
                "(regex ^[a-z][a-z0-9_]{1,40}$), for example clear_app_data. It must describe the action.",
        )
        val taken = existingToolNames.sorted().take(MAX_NAMES_IN_PROMPT)
        if (taken.isNotEmpty()) appendLine("- Do not reuse these tool names: ${taken.joinToString(", ")}.")
        appendLine("- description: one or two sentences, shown to the AI agent that decides when to call the script.")
        appendLine("- params: may be empty. Names are lowercase (regex ^[a-z][a-z0-9_]{0,40}$). type is STRING, INT or BOOL.")
        appendLine(
            "- Parameters reach the command ONLY as environment variables named exactly like the parameter. Reference them as " +
                "\$name in a POSIX shell (or \$env:name in PowerShell). Never write placeholders such as {name} or {{name}} and " +
                "never ask for values to be pasted into the command text. Always quote expansions, e.g. \"\$name\".",
        )
        appendLine(
            "- Parameter names must not be any of ${ALWAYS_SET_SCRIPT_ENV.joinToString(", ")} (in any letter case), nor path, home, " +
                "shell, user, pwd, ifs, tmpdir, lang, serial, run_id, lane_id, suite_id.",
        )
        appendLine("- A BOOL parameter arrives as the text true or false; an INT as a whole number; an optional one may be an empty string.")
        appendLine("- commandTemplate: the shell command or small script itself. Non-destructive unless the request clearly says otherwise.")
        appendLine("  No interactive commands (no prompts, pagers, or waiting for input). Exit 0 on success and non-zero on failure.")
        appendLine("  Print what matters to stdout; it is captured and shown to the agent.")
        appendLine("- timeoutMs: a realistic limit in milliseconds, 1000-$MAX_SCRIPT_TIMEOUT_MS (default $DEFAULT_SCRIPT_TIMEOUT_MS).")
        appendLine()
        appendLine("Environment every run provides (set by the run, not by you):")
        appendLine("- DEVICE: the serial of the Android device under test (may be empty).")
        appendLine("- PACKAGE: the target app's package name (may be empty).")
        appendLine(
            "- RUN_DIR: the folder of the current test run; HOST_SHELL scripts start there and should write files into " +
                "\"\$RUN_DIR\". It is not set for ADB_SHELL.",
        )
        appendLine("- CASE_ID and STEP_ID: ids of the running case and step (may be empty).")
        appendLine()
        appendLine("Where each target runs:")
        appendLine("- HOST_SHELL runs on the user's computer in ${hostShellDescription(hostOs)}.")
        appendLine(
            "- ADB_SHELL runs on the Android device through `adb shell` (toybox sh; no bash features, only what Android ships). Do not " +
                "write the leading `adb shell`; the command is already executed there. Use \$DEVICE only for host commands such as adb.",
        )
        when (targetHint) {
            ScriptTarget.HOST_SHELL -> appendLine("The user chose target HOST_SHELL. Use it.")
            ScriptTarget.ADB_SHELL -> appendLine("The user chose target ADB_SHELL. Use it.")
            null -> appendLine("Choose the target that fits best; prefer ADB_SHELL for commands that only touch the device.")
        }
        if (current != null) {
            appendLine()
            appendLine("This is the existing script. Return an improved version that keeps its purpose and fixes its weaknesses:")
            appendLine(encodeTestScriptEnvelope(current.copy(permission = ScriptPermission.ASK)).take(MAX_CURRENT_CHARS))
            appendLine("Keep the tool name unless the request says to change it.")
        }
        appendLine()
        appendLine("Request: ${description.trim().take(MAX_DESCRIPTION_CHARS)}")
    }

    /**
     * Turns the model's [text] into a validated script with a fresh id, a tool name unique among [existing], and permission ASK.
     * Accepts a bare object, a fenced object, or an object surrounded by prose; missing optional fields get defaults and a
     * [targetHint] overrides the model's target. Every problem is returned as [StoreResult.Invalid] with a message for the person.
     */
    @Suppress("ReturnCount", "TooGenericExceptionCaught") // Each rejection needs its own message; decode failures are user-facing.
    fun parse(text: String, existing: List<TestScript>, targetHint: ScriptTarget? = null): StoreResult<TestScript> {
        val raw = extractJsonObject(text)
            ?: return StoreResult.Invalid("The provider did not return a JSON script object. Try again or use another model.")
        val root = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            ?: return StoreResult.Invalid("The provider returned JSON that could not be read. Try again or use another model.")
        val fields = (root["script"] as? JsonObject) ?: root
        collidingParamName(fields)?.let { name ->
            return StoreResult.Invalid(
                "Parameter '$name' collides with the environment variable ${name.uppercase()} that every run sets; choose another parameter name.",
            )
        }
        val envelope = Json.encodeToString(
            JsonObject.serializer(),
            buildJsonObject {
                put("format", TEST_SCRIPT_FILE_FORMAT)
                put("version", TEST_SCRIPT_FILE_VERSION)
                put("script", normalizedScript(fields, targetHint))
            },
        )
        return try {
            StoreResult.Ok(decodeTestScriptEnvelope(envelope, existing).copy(permission = ScriptPermission.ASK))
        } catch (failure: IllegalArgumentException) {
            StoreResult.Invalid(failure.message ?: "The drafted script is not valid.")
        }
    }

    private fun collidingParamName(fields: JsonObject): String? =
        (fields["params"] as? JsonArray).orEmpty().firstNotNullOfOrNull { item ->
            val name = ((item as? JsonObject)?.get("name") as? JsonPrimitive)?.takeIf { it.isString }?.content
            name?.takeIf { it.uppercase() in ALWAYS_SET_SCRIPT_ENV }
        }

    /** The model's fields with the envelope's required-but-obvious ones defaulted, so a sparse answer is not rejected needlessly. */
    private fun normalizedScript(fields: JsonObject, targetHint: ScriptTarget?): JsonObject = buildJsonObject {
        put("toolName", stringOf(fields["toolName"]).trim())
        put("description", stringOf(fields["description"]).trim())
        put("commandTemplate", stringOf(fields["commandTemplate"]))
        val target = targetHint?.name
            ?: stringOf(fields["target"]).trim().uppercase().takeIf { value -> ScriptTarget.entries.any { it.name == value } }
            ?: ScriptTarget.HOST_SHELL.name
        put("target", target)
        put("permission", ScriptPermission.ASK.name)
        put("timeoutMs", timeoutOf(fields["timeoutMs"]))
        put("outputCapBytes", DEFAULT_SCRIPT_OUTPUT_CAP_BYTES)
        put("params", JsonArray((fields["params"] as? JsonArray).orEmpty().map(::normalizedParam)))
    }

    private fun normalizedParam(item: JsonElement): JsonElement {
        val param = item as? JsonObject ?: return item
        return buildJsonObject {
            put("name", stringOf(param["name"]).trim())
            put("type", stringOf(param["type"]).trim().uppercase().ifEmpty { "STRING" })
            put("description", stringOf(param["description"]).trim())
            put("required", (param["required"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toBooleanStrictOrNull() ?: true)
            val default = param["defaultValue"]
            if (default == null || default is JsonNull) {
                put("defaultValue", JsonNull)
            } else {
                put("defaultValue", (default as? JsonPrimitive)?.content ?: default.toString())
            }
        }
    }

    private fun stringOf(element: JsonElement?): String = (element as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content.orEmpty()

    private fun timeoutOf(element: JsonElement?): Long {
        val value = (element as? JsonPrimitive)?.takeUnless { it.isString || it is JsonNull }?.content?.toLongOrNull()
            ?: return DEFAULT_SCRIPT_TIMEOUT_MS
        return value.coerceIn(1L, MAX_SCRIPT_TIMEOUT_MS)
    }

    private companion object {
        const val MAX_DESCRIPTION_CHARS = 2_000
        const val MAX_NAMES_IN_PROMPT = 60
        const val MAX_CURRENT_CHARS = 6_000
    }
}

/** What runs a HOST_SHELL script on this OS, mirroring the script runner's shell choice. */
internal fun hostShellDescription(hostOs: String): String {
    val os = hostOs.lowercase()
    return when {
        os.contains("win") -> "Windows PowerShell (Windows; reference variables as \$env:NAME, use PowerShell syntax, not cmd or bash)"
        os.contains("mac") -> "zsh on macOS (a POSIX-compatible shell; reference variables as \$NAME)"
        else -> "/bin/sh (a POSIX shell; no bash-only features; reference variables as \$NAME)"
    }
}

/** The first balanced `{...}` object in [text], ignoring braces inside strings, so fences and surrounding prose are dropped. */
@Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements") // A small string-aware brace scanner reads best as one loop.
internal fun extractJsonObject(text: String): String? {
    val start = text.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (index in start until text.length) {
        val char = text[index]
        if (inString) {
            when {
                escaped -> escaped = false
                char == '\\' -> escaped = true
                char == '"' -> inString = false
            }
            continue
        }
        when (char) {
            '"' -> inString = true
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return text.substring(start, index + 1)
            }
        }
    }
    return null
}
