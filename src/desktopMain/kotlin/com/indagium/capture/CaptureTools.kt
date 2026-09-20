package com.indagium.capture

import java.io.File
import java.time.Duration

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

    fun scrcpySpec(serial: String, settings: CaptureSettings, destination: File): CaptureProcessSpec {
        val executable = requireNotNull(scrcpy) { "scrcpy is not configured" }
        val arguments = buildList {
            addAll(listOf("--serial", serial, "--record=${destination.absolutePath}", "--record-format=mkv"))
            add("--max-size=${settings.maxSize.coerceAtLeast(0)}")
            add("--max-fps=${settings.maxFps.coerceAtLeast(1)}")
            add("--video-bit-rate=${settings.bitrateMbps.coerceAtLeast(1)}M")
            add("--video-codec=h264")
            if (!settings.mirror) add("--no-window")
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

    fun validateAdb(): CaptureToolValidation {
        val version = runner.run(CaptureProcessSpec(adb.command(listOf("version"))))
        if (version.timedOut) return CaptureToolValidation(false, message = "adb version check timed out")
        val versionText = (version.stdoutText() + "\n" + version.stderrText())
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        if (version.exitCode != 0 || versionText?.contains("Android Debug Bridge", ignoreCase = true) != true) {
            return CaptureToolValidation(false, versionText, boundedDiagnostic("adb version failed", version))
        }
        val help = runner.run(CaptureProcessSpec(adb.command(listOf("help"))))
        if (help.timedOut) {
            return CaptureToolValidation(false, versionText, message = "adb capability check timed out")
        }
        val helpText = help.stdoutText() + help.stderrText()
        val missing = listOf("devices", "logcat", "exec-out").filterNot { helpText.contains(it, ignoreCase = true) }
        return if (help.exitCode == 0 && missing.isEmpty()) {
            CaptureToolValidation(true, versionText, "adb is ready")
        } else if (help.exitCode != 0) {
            CaptureToolValidation(false, versionText, boundedDiagnostic("adb capability check failed", help))
        } else {
            CaptureToolValidation(false, versionText, "adb is missing required commands: ${missing.joinToString()}")
        }
    }

    fun validateScrcpy(settings: CaptureSettings? = null): CaptureToolValidation {
        val executable = scrcpy ?: return CaptureToolValidation(false, message = "scrcpy was not found")
        val version = runner.run(CaptureProcessSpec(executable.command(listOf("--version"))))
        val versionText = (version.stdoutText() + "\n" + version.stderrText())
            .lineSequence().firstOrNull { it.isNotBlank() }?.trim()
        if (version.timedOut || version.exitCode != 0 || versionText?.contains("scrcpy", ignoreCase = true) != true) {
            return CaptureToolValidation(false, versionText, boundedDiagnostic("scrcpy version failed", version))
        }
        val help = runner.run(CaptureProcessSpec(executable.command(listOf("--help"))))
        if (help.timedOut) {
            return CaptureToolValidation(false, versionText, message = "scrcpy capability check timed out")
        }
        val helpText = help.stdoutText() + help.stderrText()
        val required = buildList {
            addAll(SCRCPY_ALWAYS_REQUIRED_OPTIONS)
            if (settings?.mirror == false) add("--no-window")
            if (settings?.audio != true) add("--no-audio")
        }
        val missing = required.filterNot(helpText::contains)
        return if (help.exitCode == 0 && missing.isEmpty()) {
            CaptureToolValidation(true, versionText, "scrcpy is ready")
        } else if (help.exitCode != 0) {
            CaptureToolValidation(false, versionText, boundedDiagnostic("scrcpy capability check failed", help))
        } else {
            CaptureToolValidation(false, versionText, "scrcpy is missing required options: ${missing.joinToString()}")
        }
    }

    fun listDevices(): List<CaptureDeviceResult> {
        val result = runner.run(CaptureProcessSpec(adb.command(listOf("devices", "-l"))))
        check(!result.timedOut) { "adb devices timed out" }
        check(result.exitCode == 0) { boundedDiagnostic("adb devices failed", result) }
        return parseAdbDevices(result.stdoutText())
    }
}

class CaptureToolResolver(
    private val runner: CaptureProcessRunner = ProcessBuilderCaptureRunner(),
    private val environment: Map<String, String> = System.getenv(),
    private val executableExists: (String) -> Boolean = { path -> File(path).isFile && File(path).canExecute() },
    private val flatpak: Boolean = File("/.flatpak-info").isFile || environment["FLATPAK_ID"] != null,
) {
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
        val found = fromPath ?: common.firstOrNull(executableExists)
        return found?.let(::CaptureExecutable)
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

private val SCRCPY_ALWAYS_REQUIRED_OPTIONS = listOf(
    "--serial",
    "--record",
    "--record-format",
    "--max-size",
    "--max-fps",
    "--video-bit-rate",
    "--video-codec",
)
