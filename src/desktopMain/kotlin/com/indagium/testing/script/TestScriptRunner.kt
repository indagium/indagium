package com.indagium.testing.script

import com.indagium.capture.CaptureProcessSpec
import com.indagium.capture.CaptureTools
import com.indagium.testing.model.ScriptParam
import com.indagium.testing.model.ScriptParamType
import com.indagium.testing.model.ScriptTarget
import com.indagium.testing.model.TestScript
import com.indagium.testing.model.scriptParamNameError
import kotlinx.coroutines.CancellationException
import java.io.File
import java.io.IOException

// Builds and runs the command of a TestScript. THE SAFETY RULE of this file: a parameter value is never part of the
// command text. On the computer it travels as an environment variable of the child process; on the device it is
// POSIX-single-quoted into an `export name='value'` prefix that is a separate statement before the template. The
// template itself is the script author's own text and is passed to the shell untouched.

const val MAX_SCRIPT_STRING_ARG_BYTES = 4 * 1024
private const val MAX_INT_ARG_DIGITS = 18
private const val ENV_DEVICE = "DEVICE"
private const val ENV_PACKAGE = "PACKAGE"
private const val ENV_RUN_DIR = "RUN_DIR"
private const val ENV_CASE_ID = "CASE_ID"
private const val ENV_STEP_ID = "STEP_ID"
private const val SH_SHELL = "/bin/sh"
private const val ZSH_SHELL = "/bin/zsh"
private const val POWERSHELL = "powershell"
private const val BOOL_TRUE = "true"
private const val BOOL_FALSE = "false"
private val INT_ARG_REGEX = Regex("^-?\\d{1,$MAX_INT_ARG_DIGITS}$")

/** What the run itself tells a script, as the environment variables DEVICE, PACKAGE, RUN_DIR, CASE_ID and STEP_ID. */
data class ScriptRunContext(
    val deviceSerial: String = "",
    val packageName: String = "",
    val runDir: File,
    val caseId: String = "",
    val stepId: String = "",
)

/** The adb to use for an ADB_SHELL script: [tools] builds the (possibly flatpak-wrapped) command line for [serial]. */
data class AdbScriptTarget(val tools: CaptureTools, val serial: String)

data class ScriptRunResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean,
    val truncated: Boolean,
    val durationMs: Long,
    /** Notes about the run itself, e.g. that the script left background processes running. */
    val warnings: List<String> = emptyList(),
)

/** Either the script ran ([Finished], whatever its exit code) or it could not be run at all ([Rejected]). */
sealed interface ScriptRunOutcome {
    data class Finished(val result: ScriptRunResult) : ScriptRunOutcome

    data class Rejected(val message: String) : ScriptRunOutcome
}

/** Argument validation result: the final name -> value map, or why the arguments are unusable. */
sealed interface ScriptArgsResult {
    data class Valid(val values: Map<String, String>) : ScriptArgsResult

    data class Invalid(val message: String) : ScriptArgsResult
}

/**
 * [hostShell] is the argv prefix that receives the template as its last argument, e.g. `["/bin/sh", "-c"]`;
 * [defaultHostShell] picks zsh on macOS when present, `/bin/sh` elsewhere and PowerShell on Windows.
 */
class TestScriptRunner(
    private val commandRunner: HostCommandRunner = ProcessBuilderHostCommandRunner(),
    private val hostShell: List<String> = defaultHostShell(),
) {
    /**
     * Validates [rawArgs] against the script's parameters and runs it. HOST_SHELL scripts run on the computer in
     * [TestScript.workingDir] (default: the run folder); ADB_SHELL scripts need [adb] and run on the device.
     * Cancelling the calling coroutine kills the process.
     */
    suspend fun run(
        script: TestScript,
        rawArgs: Map<String, String>,
        context: ScriptRunContext,
        adb: AdbScriptTarget? = null,
    ): ScriptRunOutcome {
        val values = when (val checked = validateScriptArgs(script, rawArgs)) {
            is ScriptArgsResult.Invalid -> return ScriptRunOutcome.Rejected(checked.message)
            is ScriptArgsResult.Valid -> checked.values
        }
        val spec = when (script.target) {
            ScriptTarget.HOST_SHELL -> hostSpec(script, values, context)
            ScriptTarget.ADB_SHELL -> {
                if (adb == null) return ScriptRunOutcome.Rejected("This script runs on a device; choose a device to run it on.")
                adbSpec(script, values, context, adb)
            }
        } ?: return ScriptRunOutcome.Rejected("Working directory '${script.workingDir}' is not an existing folder.")
        return try {
            val result = commandRunner.run(spec)
            ScriptRunOutcome.Finished(
                ScriptRunResult(
                    exitCode = result.exitCode,
                    stdout = result.stdoutText(),
                    stderr = result.stderrText(),
                    timedOut = result.timedOut,
                    truncated = result.truncated,
                    durationMs = result.durationMs,
                    warnings = result.warnings,
                ),
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: IOException) {
            ScriptRunOutcome.Rejected("Could not start the script: ${failure.message ?: failure::class.simpleName}")
        }
    }

    private fun hostSpec(script: TestScript, values: Map<String, String>, context: ScriptRunContext): HostCommandSpec? {
        val workingDir = script.workingDir?.takeIf(String::isNotBlank)?.let(::File) ?: context.runDir
        if (!workingDir.isDirectory) return null
        val environment = LinkedHashMap<String, String>()
        environment.putAll(values)
        environment.putAll(contextVariables(context, includeRunDir = true))
        return HostCommandSpec(
            command = hostShell + script.commandTemplate,
            workingDir = workingDir,
            environment = environment,
            timeoutMs = script.timeoutMs,
            outputCapBytes = script.outputCapBytes,
        )
    }

    private fun adbSpec(
        script: TestScript,
        values: Map<String, String>,
        context: ScriptRunContext,
        adb: AdbScriptTarget,
    ): HostCommandSpec {
        val remote = buildAdbRemoteCommand(script.commandTemplate, values, contextVariables(context, includeRunDir = false))
        val spec: CaptureProcessSpec = adb.tools.adbSpec(adb.serial, listOf("shell", remote))
        return HostCommandSpec(
            command = spec.command,
            workingDir = context.runDir.takeIf(File::isDirectory),
            environment = spec.environment,
            timeoutMs = script.timeoutMs,
            outputCapBytes = script.outputCapBytes,
        )
    }

    private fun contextVariables(context: ScriptRunContext, includeRunDir: Boolean): Map<String, String> = buildMap {
        put(ENV_DEVICE, context.deviceSerial)
        put(ENV_PACKAGE, context.packageName)
        if (includeRunDir) put(ENV_RUN_DIR, context.runDir.absolutePath)
        put(ENV_CASE_ID, context.caseId)
        put(ENV_STEP_ID, context.stepId)
    }
}

/**
 * The one remote command string an ADB_SHELL script sends: `export name='value' other='value'; <template>`.
 * Every value is POSIX-single-quoted, so whatever it contains stays a literal; adb receives this as a SINGLE
 * argument after `shell` and the device shell evaluates it. [params] keys must already be valid parameter names.
 */
internal fun buildAdbRemoteCommand(template: String, params: Map<String, String>, extra: Map<String, String> = emptyMap()): String {
    val assignments = (params + extra).entries.joinToString(" ") { (name, value) ->
        require(isSafeVariableName(name)) { "Unsafe variable name '$name'" }
        "$name=${posixSingleQuote(value)}"
    }
    return if (assignments.isEmpty()) template else "export $assignments; $template"
}

/** `it's` becomes `'it'\''s'`: the value inside single quotes, with every embedded quote closed, escaped and reopened. */
internal fun posixSingleQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

private val SAFE_VARIABLE_NAME = Regex("^[A-Za-z_][A-Za-z0-9_]*$")

private fun isSafeVariableName(name: String): Boolean = SAFE_VARIABLE_NAME.matches(name)

/**
 * Checks [rawArgs] against [script]'s parameters: unknown names are rejected, required parameters must be present,
 * defaults fill the gaps, and a parameter that is neither given nor defaulted is passed as an empty string.
 * INT is `-?\d{1,18}`, BOOL is `true` or `false`, STRING is at most 4 KB with no NUL character.
 */
fun validateScriptArgs(script: TestScript, rawArgs: Map<String, String>): ScriptArgsResult {
    val known = script.params.map { it.name }.toSet()
    val unknown = rawArgs.keys.filterNot { it in known }
    if (unknown.isNotEmpty()) return ScriptArgsResult.Invalid("Unknown argument(s): ${unknown.sorted().joinToString(", ")}.")
    val values = LinkedHashMap<String, String>()
    for (param in script.params) {
        scriptParamNameError(param.name)?.let { return ScriptArgsResult.Invalid(it) }
        val given = rawArgs[param.name] ?: param.defaultValue
        if (given == null) {
            if (param.required) return ScriptArgsResult.Invalid("Missing required argument '${param.name}'.")
            values[param.name] = ""
            continue
        }
        argumentError(param, given)?.let { return ScriptArgsResult.Invalid(it) }
        values[param.name] = given
    }
    return ScriptArgsResult.Valid(values)
}

private fun argumentError(param: ScriptParam, value: String): String? = when (param.type) {
    ScriptParamType.INT ->
        if (INT_ARG_REGEX.matches(value)) null else "Argument '${param.name}' must be a whole number (up to $MAX_INT_ARG_DIGITS digits)."
    ScriptParamType.BOOL ->
        if (value == BOOL_TRUE || value == BOOL_FALSE) null else "Argument '${param.name}' must be true or false."
    ScriptParamType.STRING -> when {
        value.indexOf('\u0000') >= 0 -> "Argument '${param.name}' must not contain a NUL character."
        value.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_STRING_ARG_BYTES ->
            "Argument '${param.name}' is longer than $MAX_SCRIPT_STRING_ARG_BYTES bytes."
        else -> null
    }
}

/** zsh on macOS when installed, `/bin/sh` on other Unix systems, `powershell` on Windows. */
fun defaultHostShell(): List<String> {
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    return when {
        osName.contains("win") -> listOf(POWERSHELL, "-NoProfile", "-NonInteractive", "-Command")
        osName.contains("mac") && File(ZSH_SHELL).canExecute() -> listOf(ZSH_SHELL, "-c")
        else -> listOf(SH_SHELL, "-c")
    }
}
