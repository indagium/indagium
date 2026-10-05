package com.indagium.security

import com.indagium.ai.redactDiagnosticSecrets
import com.indagium.testing.script.HostCommandResult
import com.indagium.testing.script.HostCommandRunner
import com.indagium.testing.script.HostCommandSpec
import com.indagium.testing.script.ProcessBuilderHostCommandRunner
import kotlinx.coroutines.CancellationException
import java.io.IOException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

// Where Indagium keeps a secret that must outlive the session (today: the issue tracker's access token). Each backend talks to
// the operating system's own store through a child process (HostCommandRunner), and the SECRET NEVER TRAVELS ON A COMMAND
// LINE: argv is readable by every process of the user (ps, /proc), so the secret goes to the child's stdin only. Service and
// account names are fixed, safe identifiers and may be on argv.
//   macOS    /usr/bin/security -i, one command line on stdin (the interactive parser quotes with "..."; see [quoteForSecurity])
//   Linux    secret-tool store|lookup|clear (libsecret); store reads the secret from stdin
//   Windows  PowerShell reading a script from stdin that calls CredWriteW/CredReadW/CredDeleteW (the secret travels base64 in it)
// FallbackSecretStore keeps the secret in memory for the session when the system store fails, and says so (persistent=false
// plus the reason) so the UI can tell the user it will be gone after a restart. Nothing here logs a secret, and every failure
// text that came from a child process is scrubbed of the secret before it is returned.

const val SECRET_SERVICE_NAME = "Indagium"
const val MAX_SECRET_CHARS = 2_000
private const val SECRET_COMMAND_TIMEOUT_MS = 30_000L
private const val SECRET_OUTPUT_CAP_BYTES = 16 * 1024
private const val FIRST_PRINTABLE = ' '
private const val LAST_PRINTABLE = '~'
private val SAFE_IDENTIFIER = Regex("[A-Za-z0-9._-]+")
private const val REDACTED = "[REDACTED]"
private const val MAX_FAILURE_CHARS = 300

/** Names one secret: the fixed [service] and the [account] (for example `tracker.default`). Both are limited to safe characters. */
data class SecretKey(val account: String, val service: String = SECRET_SERVICE_NAME) {
    init {
        require(SAFE_IDENTIFIER.matches(account)) { "A secret account may only contain letters, digits, dot, underscore and dash." }
        require(SAFE_IDENTIFIER.matches(service)) { "A secret service may only contain letters, digits, dot, underscore and dash." }
    }
}

/** What a store call came to: a value, or why it failed (already free of the secret). */
sealed interface SecretResult<out T> {
    data class Ok<T>(val value: T) : SecretResult<T>

    data class Failed(val reason: String) : SecretResult<Nothing>
}

interface SecretStore {
    /** "macOS Keychain", "Secret Service (libsecret)", "Windows Credential Manager" or "memory". */
    val backendName: String

    /** False when secrets are only kept in memory right now (they are gone after a restart). */
    val persistent: Boolean

    /** Why [persistent] is false (the system store failed), or null while it is true. */
    val degradedReason: String?

    /** The stored secret, null when there is none, or why it could not be read. Suspends on IO. */
    suspend fun read(key: SecretKey): SecretResult<String?>

    suspend fun write(key: SecretKey, value: String): SecretResult<Unit>

    /** Removes the secret; one that is not stored counts as removed. */
    suspend fun delete(key: SecretKey): SecretResult<Unit>
}

/** The line a user reads under the token field: where it is kept, or that it is not kept. */
fun SecretStore.statusLine(): String =
    if (persistent) "Stored in $backendName" else "Kept for this session only: ${degradedReason ?: "no system secret store is available"}"

/** Null when [value] can be stored, else why not. Tokens are printable ASCII (an HTTP header value cannot be anything else). */
fun secretProblem(value: String): String? = when {
    value.isEmpty() -> "The token is empty."
    value.length > MAX_SECRET_CHARS -> "The token is longer than $MAX_SECRET_CHARS characters."
    value.any { it < FIRST_PRINTABLE || it > LAST_PRINTABLE } -> "The token may only contain printable ASCII characters (no line breaks)."
    else -> null
}

/** [text] with the [secret] and bearer/token-looking assignments masked, cut to a short single message. */
internal fun scrubSecret(text: String, secret: String?): String {
    val withoutSecret = if (secret.isNullOrEmpty()) text else text.replace(secret, REDACTED)
    return redactDiagnosticSecrets(withoutSecret).trim().take(MAX_FAILURE_CHARS)
}

private fun HostCommandResult.diagnostic(secret: String?): String {
    val text = stderrText().ifBlank { stdoutText() }.ifBlank { "exit code $exitCode" }
    return scrubSecret(text, secret)
}

private suspend fun HostCommandRunner.runSecretCommand(spec: HostCommandSpec, tool: String, missingHint: String): Result<HostCommandResult> =
    try {
        Result.success(run(spec))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: IOException) {
        Result.failure(IOException("$tool could not be started ($missingHint).", failure))
    }

private fun specOf(command: List<String>, stdin: ByteArray? = null) =
    HostCommandSpec(command, stdin = stdin, timeoutMs = SECRET_COMMAND_TIMEOUT_MS, outputCapBytes = SECRET_OUTPUT_CAP_BYTES)

// ── macOS ────────────────────────────────────────────────────────────

const val MACOS_SECURITY_EXECUTABLE = "/usr/bin/security"
private const val SECURITY_ITEM_NOT_FOUND_EXIT = 44

/**
 * [value] as one `security -i` argument: wrapped in double quotes, with backslash and double quote escaped by a backslash.
 * Every other character, including spaces, `#`, `$`, `;` and single quotes, is literal inside the quotes (verified against
 * the real tool by MacOsKeychainRoundTripTest). The value must have passed [secretProblem], so it has no line break.
 */
internal fun quoteForSecurity(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

class MacOsKeychainSecretStore(
    private val runner: HostCommandRunner = ProcessBuilderHostCommandRunner(),
    private val executable: String = MACOS_SECURITY_EXECUTABLE,
) : SecretStore {
    override val backendName = "macOS Keychain"
    override val persistent = true
    override val degradedReason: String? = null

    override suspend fun read(key: SecretKey): SecretResult<String?> {
        val command = listOf(executable, "find-generic-password", "-s", key.service, "-a", key.account, "-w")
        val result = runner.runSecretCommand(specOf(command), "security", "is this macOS?").getOrElse { return failed(it) }
        return when {
            result.timedOut -> SecretResult.Failed("The keychain did not answer in time.")
            result.exitCode == 0 -> SecretResult.Ok(result.stdoutText().trimEnd('\n', '\r').ifEmpty { null })
            result.exitCode == SECURITY_ITEM_NOT_FOUND_EXIT -> SecretResult.Ok(null)
            else -> SecretResult.Failed(result.diagnostic(null))
        }
    }

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> {
        secretProblem(value)?.let { return SecretResult.Failed(it) }
        // The command, secret included, is read from stdin: it is never part of the process's argument list.
        val line = "add-generic-password -U -s ${quoteForSecurity(key.service)} -a ${quoteForSecurity(key.account)} -w ${quoteForSecurity(value)}\n"
        val spec = specOf(listOf(executable, "-i"), stdin = line.toByteArray(Charsets.UTF_8))
        val result = runner.runSecretCommand(spec, "security", "is this macOS?").getOrElse { return failed(it, value) }
        return when {
            result.timedOut -> SecretResult.Failed("The keychain did not answer in time.")
            result.exitCode == 0 -> SecretResult.Ok(Unit)
            else -> SecretResult.Failed(result.diagnostic(value))
        }
    }

    override suspend fun delete(key: SecretKey): SecretResult<Unit> {
        val command = listOf(executable, "delete-generic-password", "-s", key.service, "-a", key.account)
        val result = runner.runSecretCommand(specOf(command), "security", "is this macOS?").getOrElse { return failed(it) }
        return when {
            result.timedOut -> SecretResult.Failed("The keychain did not answer in time.")
            result.exitCode == 0 || result.exitCode == SECURITY_ITEM_NOT_FOUND_EXIT -> SecretResult.Ok(Unit)
            else -> SecretResult.Failed(result.diagnostic(null))
        }
    }
}

private fun failed(failure: Throwable, secret: String? = null): SecretResult.Failed =
    SecretResult.Failed(scrubSecret(failure.message ?: failure::class.simpleName.orEmpty(), secret))

// ── Linux ────────────────────────────────────────────────────────────

const val LINUX_SECRET_TOOL = "secret-tool"
private const val SECRET_TOOL_NOT_FOUND_EXIT = 1

class LinuxSecretToolStore(
    private val runner: HostCommandRunner = ProcessBuilderHostCommandRunner(),
    private val executable: String = LINUX_SECRET_TOOL,
) : SecretStore {
    override val backendName = "Secret Service (libsecret)"
    override val persistent = true
    override val degradedReason: String? = null

    private fun attributes(key: SecretKey) = listOf("service", key.service, "account", key.account)

    private val missingHint = "install libsecret-tools and run a keyring such as GNOME Keyring or KWallet"

    override suspend fun read(key: SecretKey): SecretResult<String?> {
        val spec = specOf(listOf(executable, "lookup") + attributes(key))
        val result = runner.runSecretCommand(spec, LINUX_SECRET_TOOL, missingHint).getOrElse { return failed(it) }
        return when {
            result.timedOut -> SecretResult.Failed("The keyring did not answer in time.")
            result.exitCode == 0 -> SecretResult.Ok(result.stdoutText().trimEnd('\n', '\r').ifEmpty { null })
            result.exitCode == SECRET_TOOL_NOT_FOUND_EXIT && result.stderr.isEmpty() -> SecretResult.Ok(null)
            else -> SecretResult.Failed(result.diagnostic(null))
        }
    }

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> {
        secretProblem(value)?.let { return SecretResult.Failed(it) }
        val command = listOf(executable, "store", "--label=${key.service} (${key.account})") + attributes(key)
        val result = runner.runSecretCommand(specOf(command, stdin = value.toByteArray(Charsets.UTF_8)), LINUX_SECRET_TOOL, missingHint)
            .getOrElse { return failed(it, value) }
        return when {
            result.timedOut -> SecretResult.Failed("The keyring did not answer in time.")
            result.exitCode == 0 -> SecretResult.Ok(Unit)
            else -> SecretResult.Failed(result.diagnostic(value))
        }
    }

    override suspend fun delete(key: SecretKey): SecretResult<Unit> {
        val spec = specOf(listOf(executable, "clear") + attributes(key))
        val result = runner.runSecretCommand(spec, LINUX_SECRET_TOOL, missingHint).getOrElse { return failed(it) }
        return when {
            result.timedOut -> SecretResult.Failed("The keyring did not answer in time.")
            result.exitCode == 0 || (result.exitCode == SECRET_TOOL_NOT_FOUND_EXIT && result.stderr.isEmpty()) -> SecretResult.Ok(Unit)
            else -> SecretResult.Failed(result.diagnostic(null))
        }
    }
}

// ── Windows ──────────────────────────────────────────────────────────

const val WINDOWS_POWERSHELL = "powershell"
private const val WINDOWS_NOT_FOUND_EXIT = 3
private const val WINDOWS_ERROR_EXIT = 2

// The P/Invoke wrapper, on ONE line (a PowerShell script read from stdin handles single-line statements most reliably). Generic
// credential (type 1), persisted for the local machine (2); ERROR_NOT_FOUND (1168) is "no such credential".
private const val CREDENTIAL_SOURCE =
    "using System;using System.Runtime.InteropServices;public static class IndagiumCred{" +
        "[StructLayout(LayoutKind.Sequential,CharSet=CharSet.Unicode)]public struct CREDENTIAL{public uint Flags;public uint Type;" +
        "public string TargetName;public string Comment;public System.Runtime.InteropServices.ComTypes.FILETIME LastWritten;" +
        "public uint CredentialBlobSize;public IntPtr CredentialBlob;public uint Persist;public uint AttributeCount;" +
        "public IntPtr Attributes;public string TargetAlias;public string UserName;}" +
        "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]static extern bool CredWriteW(ref CREDENTIAL c,uint f);" +
        "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]" +
        "static extern bool CredReadW(string t,uint type,uint f,out IntPtr c);" +
        "[DllImport(\"advapi32.dll\",SetLastError=true,CharSet=CharSet.Unicode)]static extern bool CredDeleteW(string t,uint type,uint f);" +
        "[DllImport(\"advapi32.dll\")]static extern void CredFree(IntPtr b);" +
        "public static void Write(string target,string user,byte[] blob){var c=new CREDENTIAL();c.Type=1;c.TargetName=target;" +
        "c.UserName=user;c.Persist=2;c.CredentialBlobSize=(uint)blob.Length;c.CredentialBlob=Marshal.AllocHGlobal(blob.Length);" +
        "try{Marshal.Copy(blob,0,c.CredentialBlob,blob.Length);if(!CredWriteW(ref c,0))" +
        "throw new System.ComponentModel.Win32Exception(Marshal.GetLastWin32Error());}finally{Marshal.FreeHGlobal(c.CredentialBlob);}}" +
        "public static byte[] Read(string target){IntPtr p;if(!CredReadW(target,1,0,out p)){int e=Marshal.GetLastWin32Error();" +
        "if(e==1168)return null;throw new System.ComponentModel.Win32Exception(e);}" +
        "try{var c=(CREDENTIAL)Marshal.PtrToStructure(p,typeof(CREDENTIAL));var b=new byte[c.CredentialBlobSize];" +
        "Marshal.Copy(c.CredentialBlob,b,0,b.Length);return b;}finally{CredFree(p);}}" +
        "public static bool Delete(string target){if(CredDeleteW(target,1,0))return true;int e=Marshal.GetLastWin32Error();" +
        "if(e==1168)return false;throw new System.ComponentModel.Win32Exception(e);}}"

/** The PowerShell script (statements separated by line breaks, each complete on its own line) for one credential operation. */
internal fun windowsCredentialScript(operation: String): String = listOf(
    "\$ErrorActionPreference = 'Stop'",
    "Add-Type -TypeDefinition '$CREDENTIAL_SOURCE'",
    "try { $operation } catch { [Console]::Error.WriteLine(\$_.Exception.Message); exit $WINDOWS_ERROR_EXIT }",
    "exit 0",
).joinToString("\n", postfix = "\n")

private fun psLiteral(text: String) = "'" + text.replace("'", "''") + "'"

class WindowsCredentialSecretStore(
    private val runner: HostCommandRunner = ProcessBuilderHostCommandRunner(),
    private val executable: String = WINDOWS_POWERSHELL,
) : SecretStore {
    override val backendName = "Windows Credential Manager"
    override val persistent = true
    override val degradedReason: String? = null

    private val missingHint = "is this Windows?"

    private fun target(key: SecretKey) = psLiteral("${key.service}/${key.account}")

    private fun command() = listOf(executable, "-NoProfile", "-NonInteractive", "-Command", "-")

    /** Runs [script] (read from stdin; the secret, when there is one, is only inside it, base64-encoded). */
    private suspend fun run(script: String, secret: String?): SecretResult<HostCommandResult> {
        val spec = specOf(command(), stdin = script.toByteArray(Charsets.UTF_8))
        val result = runner.runSecretCommand(spec, "PowerShell", missingHint).getOrElse { return failed(it, secret) }
        return if (result.timedOut) SecretResult.Failed("The credential store did not answer in time.") else SecretResult.Ok(result)
    }

    override suspend fun read(key: SecretKey): SecretResult<String?> {
        val operation = "\$b = [IndagiumCred]::Read(${target(key)}); if (\$b -eq \$null) { exit $WINDOWS_NOT_FOUND_EXIT } " +
            "else { [Console]::Out.Write([Convert]::ToBase64String(\$b)) }"
        val result = when (val outcome = run(windowsCredentialScript(operation), null)) {
            is SecretResult.Failed -> return outcome
            is SecretResult.Ok -> outcome.value
        }
        return when (result.exitCode) {
            0 -> SecretResult.Ok(decodeBase64(result.stdoutText()))
            WINDOWS_NOT_FOUND_EXIT -> SecretResult.Ok(null)
            else -> SecretResult.Failed(result.diagnostic(null))
        }
    }

    private fun decodeBase64(text: String): String? = runCatching {
        String(Base64.getDecoder().decode(text.trim()), Charsets.UTF_8).ifEmpty { null }
    }.getOrNull()

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> {
        secretProblem(value)?.let { return SecretResult.Failed(it) }
        val encoded = Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val operation = "[IndagiumCred]::Write(${target(key)}, ${psLiteral(key.account)}, [Convert]::FromBase64String('$encoded'))"
        val result = when (val outcome = run(windowsCredentialScript(operation), value)) {
            is SecretResult.Failed -> return outcome
            is SecretResult.Ok -> outcome.value
        }
        return if (result.exitCode == 0) SecretResult.Ok(Unit) else SecretResult.Failed(scrubSecret(result.diagnostic(value), encoded))
    }

    override suspend fun delete(key: SecretKey): SecretResult<Unit> {
        val result = when (val outcome = run(windowsCredentialScript("[void][IndagiumCred]::Delete(${target(key)})"), null)) {
            is SecretResult.Failed -> return outcome
            is SecretResult.Ok -> outcome.value
        }
        return if (result.exitCode == 0) SecretResult.Ok(Unit) else SecretResult.Failed(result.diagnostic(null))
    }
}

// ── Memory and fallback ──────────────────────────────────────────────

/** Keeps secrets for the life of the process only. */
class MemorySecretStore : SecretStore {
    private val values = ConcurrentHashMap<SecretKey, String>()

    override val backendName = "memory"
    override val persistent = false
    override val degradedReason: String = "no system secret store is in use"

    override suspend fun read(key: SecretKey): SecretResult<String?> = SecretResult.Ok(values[key])

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> {
        secretProblem(value)?.let { return SecretResult.Failed(it) }
        values[key] = value
        return SecretResult.Ok(Unit)
    }

    override suspend fun delete(key: SecretKey): SecretResult<Unit> {
        values.remove(key)
        return SecretResult.Ok(Unit)
    }
}

/** A store for an operating system without a supported secret store: every call fails with the reason, so a fallback takes over. */
class UnsupportedSecretStore(private val osName: String) : SecretStore {
    override val backendName = "no system secret store"
    override val persistent = false
    override val degradedReason = "this operating system ($osName) has no supported secret store"

    private fun refusal() = SecretResult.Failed(degradedReason)

    override suspend fun read(key: SecretKey): SecretResult<String?> = refusal()

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> = refusal()

    override suspend fun delete(key: SecretKey): SecretResult<Unit> = refusal()
}

/**
 * [primary] when it works, [memory] when it does not. A write the primary refused is kept in memory and [persistent] turns
 * false with the reason; a read looks in memory first (a secret that never reached the primary), then asks the primary.
 */
class FallbackSecretStore(private val primary: SecretStore, private val memory: SecretStore = MemorySecretStore()) : SecretStore {
    private val degraded = ConcurrentHashMap<SecretKey, String>()

    override val backendName: String get() = if (persistent) primary.backendName else memory.backendName
    override val persistent: Boolean get() = primary.persistent && degraded.isEmpty()
    override val degradedReason: String? get() = degraded.values.firstOrNull() ?: if (primary.persistent) null else primary.degradedReason

    override suspend fun read(key: SecretKey): SecretResult<String?> {
        (memory.read(key) as? SecretResult.Ok)?.value?.let { return SecretResult.Ok(it) }
        return when (val outcome = primary.read(key)) {
            is SecretResult.Ok -> outcome.also { degraded.remove(key) }
            is SecretResult.Failed -> outcome.also { degraded[key] = it.reason }
        }
    }

    override suspend fun write(key: SecretKey, value: String): SecretResult<Unit> {
        secretProblem(value)?.let { return SecretResult.Failed(it) }
        return when (val outcome = primary.write(key, value)) {
            is SecretResult.Ok -> {
                degraded.remove(key)
                memory.delete(key)
                outcome
            }
            is SecretResult.Failed -> {
                degraded[key] = outcome.reason
                memory.write(key, value)
            }
        }
    }

    override suspend fun delete(key: SecretKey): SecretResult<Unit> {
        memory.delete(key)
        return when (val outcome = primary.delete(key)) {
            is SecretResult.Ok -> outcome.also { degraded.remove(key) }
            is SecretResult.Failed -> outcome.also { degraded[key] = it.reason }
        }
    }
}

// ── Selection ────────────────────────────────────────────────────────

/** The system store of [osName] (the `os.name` property), without the memory fallback. */
fun platformSecretStore(osName: String = System.getProperty("os.name").orEmpty(), runner: HostCommandRunner = ProcessBuilderHostCommandRunner()): SecretStore {
    val os = osName.lowercase()
    return when {
        "mac" in os || "darwin" in os -> MacOsKeychainSecretStore(runner)
        "win" in os -> WindowsCredentialSecretStore(runner)
        "nux" in os || "nix" in os || "linux" in os -> LinuxSecretToolStore(runner)
        else -> UnsupportedSecretStore(osName)
    }
}

/** What the app uses: the platform's store, with the session-only fallback. */
fun defaultSecretStore(osName: String = System.getProperty("os.name").orEmpty(), runner: HostCommandRunner = ProcessBuilderHostCommandRunner()): SecretStore =
    FallbackSecretStore(platformSecretStore(osName, runner))
