package com.indagium.capture

import java.time.Duration

/**
 * Whether the bundled bytedeco/javacv native media stack (in-app mirror, video recording,
 * playback, export — see FfmpegCaptureVideoExporter, StreamingMkvWriter, TimelineOpusAudio,
 * VideoPlayerController) can load on this system. [available] is the one actionable bit;
 * [detectedGlibc] and [reason] are for display only. A non-Linux system and an inconclusive Linux
 * probe both report `available = true` — this only ever blocks on a *known* old glibc, never on
 * doubt. The runtime `UnsatisfiedLinkError` diagnostic in CaptureNativeFailure.kt still covers a
 * real load failure that this up-front probe missed or couldn't run.
 */
data class NativeMediaSupport(val available: Boolean, val detectedGlibc: String? = null, val reason: String? = null)

/**
 * The minimum glibc bytedeco 1.5.13's `linux-x86_64` natives need: `libavutil`/`avcodec`/
 * `avformat.so` want `GLIBC_2.35`, and `GLIBCXX_3.4.29` (even `libjnijavacpp.so` wants `2.34`).
 * Re-check this whenever `javacvVersion` in build.gradle.kts changes — the requirement moves with
 * the bundled natives, not with any Indagium code; bytedeco 1.5.11 needed only glibc 2.14.
 */
internal val REQUIRED_GLIBC: Pair<Int, Int> = 2 to 35

private val GLIBC_VERSION_REGEX = Regex("""glibc\s+(\d+)\.(\d+)""", RegexOption.IGNORE_CASE)
private val GLIBC_PROBE_TIMEOUT: Duration = Duration.ofSeconds(2)

/**
 * Parses `getconf GNU_LIBC_VERSION` output (e.g. `"glibc 2.31"`). Tolerates a trailing patch
 * component (`"glibc 2.31.1"`) and surrounding whitespace; null for anything else — a musl
 * system, a command failure, or garbage.
 */
internal fun parseGlibcVersion(output: String): Pair<Int, Int>? {
    val match = GLIBC_VERSION_REGEX.find(output) ?: return null
    val major = match.groupValues[1].toIntOrNull() ?: return null
    val minor = match.groupValues[2].toIntOrNull() ?: return null
    return major to minor
}

/**
 * Pure decision, no I/O: not Linux -> available; Linux with an inconclusive probe (null [glibc],
 * e.g. musl or a missing/failing `getconf`) -> available (never block a launch on doubt); Linux
 * below [REQUIRED_GLIBC] -> unavailable with a plain-language [NativeMediaSupport.reason].
 */
internal fun nativeMediaSupportFor(osName: String, glibc: Pair<Int, Int>?): NativeMediaSupport {
    if (!osName.contains("linux", ignoreCase = true)) return NativeMediaSupport(available = true)
    if (glibc == null) return NativeMediaSupport(available = true)
    val detected = "${glibc.first}.${glibc.second}"
    val meetsRequirement = glibc.first > REQUIRED_GLIBC.first ||
        (glibc.first == REQUIRED_GLIBC.first && glibc.second >= REQUIRED_GLIBC.second)
    if (meetsRequirement) return NativeMediaSupport(available = true, detectedGlibc = detected)
    return NativeMediaSupport(
        available = false,
        detectedGlibc = detected,
        reason = "In-app video needs glibc ${REQUIRED_GLIBC.first}.${REQUIRED_GLIBC.second} or newer " +
            "(Ubuntu 22.04+); this system has glibc $detected.",
    )
}

/**
 * Probes [nativeMediaSupportFor] once per process and memoizes the result — a wedged or missing
 * `getconf` must never re-probe on every capture start, and a non-Linux system never spawns a
 * process at all. Callers invoke [detect] from a background coroutine (see CaptureService's init
 * block); this never runs on the Compose/UI thread. [runner] is the same [CaptureProcessRunner]
 * seam CaptureProcess.kt already uses for adb/scrcpy, so a test can substitute a fake and assert
 * exactly one probe ran.
 */
internal class NativeMediaSupportProbe(
    private val runner: CaptureProcessRunner = ProcessBuilderCaptureRunner(),
    private val osName: String = System.getProperty("os.name").orEmpty(),
) {
    @Volatile private var cached: NativeMediaSupport? = null

    fun detect(): NativeMediaSupport {
        cached?.let { return it }
        val glibc = if (osName.contains("linux", ignoreCase = true)) probeGlibc() else null
        return nativeMediaSupportFor(osName, glibc).also { cached = it }
    }

    private fun probeGlibc(): Pair<Int, Int>? = runCatching {
        val result = runner.run(CaptureProcessSpec(listOf("getconf", "GNU_LIBC_VERSION")), timeout = GLIBC_PROBE_TIMEOUT)
        if (result.exitCode != 0) null else parseGlibcVersion(result.stdoutText())
    }.getOrNull()
}
