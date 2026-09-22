package com.indagium.capture

import java.io.File
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

data class CaptureExecutable(
    val path: String,
    val runOnHost: Boolean = false,
) {
    fun command(arguments: List<String>): List<String> =
        if (runOnHost) listOf("flatpak-spawn", "--host", "--watch-bus", path) + arguments else listOf(path) + arguments
}

data class CaptureToolValidation(
    val available: Boolean,
    val version: String? = null,
    val message: String,
)

data class CaptureDeviceResult(
    val device: CaptureDevice,
    val guidance: String? = null,
)

class CaptureTools(
    val adb: CaptureExecutable,
    val scrcpy: CaptureExecutable?,
    private val runner: CaptureProcessRunner,
) {
    fun adbSpec(serial: String?, vararg arguments: String): CaptureProcessSpec {
        return adbSpec(serial, arguments.toList())
    }

    fun adbSpec(serial: String?, arguments: List<String>): CaptureProcessSpec {
        require(serial == null || serial.isNotBlank()) { "Device serial cannot be blank" }
        val args = buildList {
            if (serial != null) addAll(listOf("-s", serial))
            addAll(arguments)
        }
        return CaptureProcessSpec(adb.command(args))
    }

    /** Starts a visible, non-recording scrcpy window for an active capture. */
    fun scrcpyMirrorSpec(serial: String, settings: CaptureSettings): CaptureProcessSpec {
        val executable = requireNotNull(scrcpy) { "scrcpy is not configured" }
        val arguments = scrcpyVideoArguments(serial, settings).toMutableList().apply {
            // An auxiliary mirror is explicitly visible even when recording was configured with
            // --no-window. It deliberately carries no --record flag, so it cannot overwrite or
            // race the canonical session MKV.
            if (!settings.audio) add("--no-audio")
        }
        return if (executable.runOnHost) {
            CaptureProcessSpec(
                listOf("flatpak-spawn", "--host", "--watch-bus", "--env=ADB=${adb.path}", executable.path) + arguments,
            )
        } else {
            CaptureProcessSpec(executable.command(arguments), environment = mapOf("ADB" to adb.path))
        }
    }

    private fun scrcpyVideoArguments(serial: String, settings: CaptureSettings): List<String> = listOf(
        "--serial", serial,
        "--max-size=${settings.maxSize.coerceAtLeast(0)}",
        "--max-fps=${settings.maxFps.coerceAtLeast(1)}",
        "--video-bit-rate=${settings.bitrateMbps.coerceAtLeast(1)}M",
        "--video-codec=h264",
        // Android's MediaCodec KEY_I_FRAME_INTERVAL is nominally seconds, but hardware/software
        // encoders schedule it against their configured KEY_FRAME_RATE (commonly 60fps) rather than
        // the real frame rate scrcpy delivers. On a mostly-static screen scrcpy repeats frames at a
        // much lower real rate (observed ~10fps on the Android emulator's software encoder), so a
        // "10s" interval measured in encoder-clock frames landed a keyframe every ~60s of wall time
        // in practice — one keyframe for an entire recording. Since-last-save exports were therefore
        // re-remuxed from the very start of the session every time (see CaptureArchiveExporter's
        // checkpoint split). "float" here matches the type Android's software AVC encoder expects
        // for this key; scrcpy's own --video-codec-options docs point at MediaFormat's
        // KEY_I_FRAME_INTERVAL for the full key/type table.
        "--video-codec-options=i-frame-interval:float=1",
    )

    // Identity only: `adb version` exits 0 and prints "Android Debug Bridge ..." for every
    // platform-tools release we care about, so that's the whole check. This function used to also
    // run `adb help` and grep its TEXT for the literal substrings "devices", "logcat" and
    // "exec-out" -- but help text is not an API. On platform-tools 35.0.2, `adb help` never prints
    // the string "exec-out" anywhere in its ~8.8 KB of output (verified: 0 occurrences), even
    // though `adb exec-out` itself works fine, so that grep rejected every modern adb with "adb is
    // missing required commands: exec-out" and capture could never start. Do not resurrect a
    // help-text grep here; if a specific subcommand's *availability* ever needs checking, probe it
    // functionally (see supportsScreenshots() below for the pattern) rather than parsing --help.
    fun validateAdb(): CaptureToolValidation {
        val version = runner.run(CaptureProcessSpec(adb.command(listOf("version"))))
        if (version.timedOut) return CaptureToolValidation(false, message = "adb version check timed out")
        val versionText = (version.stdoutText() + "\n" + version.stderrText())
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return if (version.exitCode == 0 && versionText?.contains("Android Debug Bridge", ignoreCase = true) == true) {
            CaptureToolValidation(true, versionText, "adb is ready")
        } else {
            CaptureToolValidation(false, versionText, boundedDiagnostic("adb version failed", version))
        }
    }

    /**
     * Whether `adb exec-out` works against this device, probed by actually running it rather than
     * grepping `adb help` for the flag name -- the same class of bug fixed in validateAdb() above.
     * This needs a live, connected device, so it is deliberately NOT part of validateAdb() or
     * device discovery, both of which must stay fast and device-independent; callers invoke this
     * lazily (e.g. to enable/disable a screenshot action for a specific serial) once a device is
     * selected. Cached per serial so repeated screenshots don't repeat the round trip. A probe that
     * can't reach a conclusion (exception, timeout, unexpected output) defaults to "available" --
     * an inconclusive probe must never disable a feature that would in fact have worked; a real
     * failure at screenshot time still surfaces its own error (see CaptureRecorder.screenshot()).
     */
    fun supportsScreenshots(serial: String): Boolean = screenshotSupport.getOrPut(serial) {
        runCatching {
            val probe = runner.run(
                adbSpec(serial, "exec-out", "echo", SCREENSHOT_PROBE_TOKEN),
                timeout = Duration.ofSeconds(SCREENSHOT_PROBE_TIMEOUT_SECONDS),
            )
            !probe.timedOut && probe.exitCode == 0 && probe.stdoutText().contains(SCREENSHOT_PROBE_TOKEN)
        }.getOrDefault(true)
    }

    // Identity only, same rationale as validateAdb() above: `scrcpy --help` was grepped for CLI
    // flag names (--serial, --record, --max-fps, ...) that are reliably present in every scrcpy
    // build we support, so that grep was never going to catch a real incompatibility -- and it
    // added a second subprocess round-trip for no protective value. A genuinely unsupported option
    // now surfaces as a runtime failure when scrcpy is actually launched (CaptureRecorder.kt already
    // treats that as a diagnostic, not a reason to stop log capture -- see startCapture()).
    fun validateScrcpy(): CaptureToolValidation {
        val executable = scrcpy ?: return CaptureToolValidation(false, message = "scrcpy was not found")
        val version = runner.run(CaptureProcessSpec(executable.command(listOf("--version"))))
        val versionText = (version.stdoutText() + "\n" + version.stderrText())
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        return if (!version.timedOut && version.exitCode == 0 && versionText?.contains("scrcpy", ignoreCase = true) == true) {
            CaptureToolValidation(true, versionText, "scrcpy is ready")
        } else {
            CaptureToolValidation(false, versionText, boundedDiagnostic("scrcpy version failed", version))
        }
    }

    fun listDevices(): List<CaptureDeviceResult> {
        val result = runner.run(CaptureProcessSpec(adb.command(listOf("devices", "-l"))))
        check(!result.timedOut) { "adb devices timed out" }
        check(result.exitCode == 0) { boundedDiagnostic("adb devices failed", result) }
        return parseAdbDevices(result.stdoutText())
    }

    private val screenshotSupport = ConcurrentHashMap<String, Boolean>()
}

class CaptureToolResolver(
    private val runner: CaptureProcessRunner = ProcessBuilderCaptureRunner(),
    private val environment: Map<String, String> = System.getenv(),
    private val executableExists: (String) -> Boolean = { path -> File(path).isFile && File(path).canExecute() },
    private val flatpak: Boolean = File("/.flatpak-info").isFile || environment["FLATPAK_ID"] != null,
    // Injectable (rather than read live via System.getProperty) so B3's login-shell fallback below
    // is deterministic in tests regardless of the host the test suite happens to run on.
    private val isMacOs: Boolean = System.getProperty("os.name").lowercase().contains("mac"),
) {
    private val loginShellPathCache = mutableMapOf<String, String?>()

    fun resolve(settings: CaptureSettings): CaptureTools {
        val adb = resolveExecutable("adb", settings.adbPath, adbCandidates())
            ?: error("adb was not found. Configure its path or install Android platform-tools.")
        val scrcpy = resolveExecutable("scrcpy", settings.scrcpyPath, scrcpyCandidates())
        return CaptureTools(adb, scrcpy, runner)
    }

    private fun resolveExecutable(name: String, configured: String, common: List<String>): CaptureExecutable? {
        if (configured.isNotBlank()) {
            if (!flatpak && executableExists(configured)) return CaptureExecutable(configured)
            if (flatpak && hostExecutableExists(configured)) return CaptureExecutable(configured, runOnHost = true)
            error("Configured $name executable does not exist or is not executable: $configured")
        }
        if (flatpak) {
            val host = runner.run(
                CaptureProcessSpec(listOf("flatpak-spawn", "--host", "--watch-bus", "which", name)),
                timeout = Duration.ofSeconds(3),
                outputLimitBytes = 16 * 1024,
            )
            if (host.exitCode == 0) {
                val hostPaths = host.stdoutText().lineSequence()
                    .map(String::trim)
                    .filter { it.startsWith('/') }
                val path = hostPaths.firstOrNull(::hostExecutableExists)
                if (path != null) return CaptureExecutable(path, runOnHost = true)
            }
            val commonHostPath = common.firstOrNull { candidate ->
                hostExecutableExists(candidate)
            }
            return commonHostPath?.let { CaptureExecutable(it, runOnHost = true) }
        }
        val fromPath = environment["PATH"]
            .orEmpty()
            .split(File.pathSeparatorChar)
            .asSequence()
            .filter(String::isNotBlank)
            .map { File(it, platformExecutableName(name)).absolutePath }
            .firstOrNull(executableExists)
        // A macOS .app launched from Finder inherits launchd's minimal PATH
        // (/usr/bin:/bin:/usr/sbin:/sbin) rather than the user's shell PATH, so the scan above
        // misses Homebrew, nix, asdf, sdkman, ~/bin, etc. Before falling back to the hardcoded
        // candidate directories (which only cover Homebrew's default prefix), ask the user's own
        // login shell what it resolves `name` to -- the same PATH `./gradlew desktopRun` already
        // inherits from an interactive terminal, which is exactly why this gap is invisible in dev
        // and only bites a packaged build. Skipped once `fromPath` already found something, and
        // never attempted on non-macOS or under Flatpak (handled above).
        val fromLoginShell = if (fromPath == null && isMacOs) {
            loginShellPath(name)?.takeIf(executableExists)
        } else {
            null
        }
        val found = fromPath ?: fromLoginShell ?: common.firstOrNull(executableExists)
        return found?.let(::CaptureExecutable)
    }

    /**
     * Resolves `name` via the user's login shell: `$SHELL -lc 'command -v name'`, falling back to
     * /bin/zsh when $SHELL is unset or not a sane executable (macOS's default since Catalina).
     * Runs through the same CaptureProcessRunner/CaptureProcessSpec seam as every other capture
     * subprocess, so it is bounded by a real timeout and cannot hang app startup, and it is
     * testable through FakeCaptureRunner like everything else in this file. Cached per tool name so
     * a hanging or slow shell is paid for at most once per resolver instance, not once per
     * resolution (resolve() can be called repeatedly, e.g. on every "Recheck tools" click).
     */
    private fun loginShellPath(name: String): String? = synchronized(loginShellPathCache) {
        // NOT `getOrPut`: a "not found" result caches as a `null` value, and `getOrPut` treats a
        // stored `null` the same as a missing key (it calls `get(key) == null`, not
        // `containsKey`), so it would re-probe forever whenever the shell doesn't have the tool.
        // `containsKey` is the only correct way to distinguish "cached miss" from "never probed".
        if (loginShellPathCache.containsKey(name)) return@synchronized loginShellPathCache.getValue(name)
        val configuredShell = environment["SHELL"]
        val shell = if (!configuredShell.isNullOrBlank() && executableExists(configuredShell)) {
            configuredShell
        } else {
            DEFAULT_LOGIN_SHELL
        }
        val result = runner.run(
            CaptureProcessSpec(listOf(shell, "-lc", "command -v $name")),
            timeout = Duration.ofSeconds(LOGIN_SHELL_TIMEOUT_SECONDS),
            outputLimitBytes = LOGIN_SHELL_OUTPUT_LIMIT_BYTES,
        )
        val resolved = if (result.timedOut || result.exitCode != 0) {
            null
        } else {
            result.stdoutText().lineSequence().map(String::trim).firstOrNull { it.startsWith('/') }
        }
        loginShellPathCache[name] = resolved
        resolved
    }

    private fun hostExecutableExists(path: String): Boolean {
        val probe = runner.run(
            CaptureProcessSpec(
                listOf("flatpak-spawn", "--host", "--watch-bus", "test", "-x", path),
            ),
            timeout = Duration.ofSeconds(3),
            outputLimitBytes = HOST_PROBE_OUTPUT_LIMIT_BYTES,
        )
        return !probe.timedOut && probe.exitCode == 0
    }

    private fun adbCandidates(): List<String> {
        val sdkRoots = listOfNotNull(environment["ANDROID_SDK_ROOT"], environment["ANDROID_HOME"])
        return (sdkRoots.map { File(it, "platform-tools/${platformExecutableName("adb")}").absolutePath } +
            commonExecutableDirectories().map { File(it, platformExecutableName("adb")).absolutePath }).distinct()
    }

    private fun scrcpyCandidates(): List<String> =
        commonExecutableDirectories().map { File(it, platformExecutableName("scrcpy")).absolutePath }.distinct()

    private fun commonExecutableDirectories(): List<String> {
        val userHome = System.getProperty("user.home").orEmpty()
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> listOf(
                "C:\\Program Files\\scrcpy",
                "C:\\Program Files\\Android\\platform-tools",
                "$userHome\\AppData\\Local\\Android\\Sdk\\platform-tools",
            )
            os.contains("mac") -> listOf("/opt/homebrew/bin", "/usr/local/bin", "$userHome/Library/Android/sdk/platform-tools")
            else -> listOf("/usr/bin", "/usr/local/bin", "/snap/bin", "$userHome/Android/Sdk/platform-tools")
        }
    }
}

fun parseAdbDevices(output: String): List<CaptureDeviceResult> = output.lineSequence()
    .map(String::trim)
    .filter { it.isNotEmpty() && !it.startsWith("List of devices") && !it.startsWith('*') }
    .mapNotNull { line ->
        val fields = line.split(Regex("\\s+"))
        if (fields.size < MIN_ADB_DEVICE_FIELDS) return@mapNotNull null
        val serial = fields[0]
        val state = when {
            fields[1] == "no" && fields.getOrNull(NO_PERMISSIONS_FIELD_INDEX) == "permissions" -> "no permissions"
            else -> fields[1]
        }
        val attributes = fields.drop(2).mapNotNull { field ->
            val separator = field.indexOf(':')
            if (separator <= 0) null else field.substring(0, separator) to field.substring(separator + 1)
        }.toMap()
        val model = attributes["model"]?.replace('_', ' ') ?: serial
        val device = CaptureDevice(serial = serial, state = state, model = model)
        CaptureDeviceResult(device, deviceStateGuidance(state))
    }
    .toList()

fun deviceStateGuidance(state: String): String? = when (state.lowercase()) {
    "device" -> null
    "unauthorized" -> "Unlock the device and accept its USB debugging authorization prompt."
    "offline" -> "Reconnect the device, then toggle USB debugging if it remains offline."
    "no permissions" -> "Grant this user USB access (usually with an Android udev rule), then reconnect the device."
    "recovery", "sideload", "bootloader" -> "Boot Android normally before starting log capture."
    else -> "The device is not ready for capture (adb state: $state)."
}

private fun platformExecutableName(name: String): String =
    if (System.getProperty("os.name").lowercase().contains("win")) "$name.exe" else name

private fun boundedDiagnostic(prefix: String, result: CaptureCommandResult): String {
    val detail = (result.stderrText().ifBlank { result.stdoutText() }).trim().take(MAX_TOOL_DIAGNOSTIC_CHARS)
    return if (detail.isEmpty()) "$prefix (exit ${result.exitCode})" else "$prefix: $detail"
}

private const val HOST_PROBE_OUTPUT_LIMIT_BYTES = 4 * 1024
private const val MAX_TOOL_DIAGNOSTIC_CHARS = 4_096
private const val MIN_ADB_DEVICE_FIELDS = 2
private const val NO_PERMISSIONS_FIELD_INDEX = 2
private const val SCREENSHOT_PROBE_TOKEN = "indagium-exec-out-probe"
private const val SCREENSHOT_PROBE_TIMEOUT_SECONDS = 3L
private const val LOGIN_SHELL_TIMEOUT_SECONDS = 3L
private const val LOGIN_SHELL_OUTPUT_LIMIT_BYTES = 4 * 1024
private const val DEFAULT_LOGIN_SHELL = "/bin/zsh"
