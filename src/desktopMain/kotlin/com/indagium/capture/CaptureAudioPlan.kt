package com.indagium.capture

// "Keep sound on the device" (CaptureSettings.keepDeviceAudio): everything here is pure (no adb, no
// Compose) so the SDK gate and argument selection can be unit-tested directly, the same way
// DeviceLogSettings.kt's own parsers are — the classes that actually run adb (CaptureRecorder,
// CaptureTools) are not reasonably unit-testable end to end (see CaptureCoordinatorToolStatusTest's
// own top-level doc for the same reasoning applied to a different file).

private const val MIN_SDK_FOR_KEEP_DEVICE_AUDIO = 33

/** `ro.build.version.sdk` -> the Android marketing version named in the "needs Android 13+"
 *  capture diagnostic (see [captureAudioPlan]), e.g. `30 -> "11"`. Only entries near and below this
 *  feature's own SDK 33 floor need to be right for that message to read sensibly; an SDK level
 *  outside this table still shows something concrete (its own number) instead of failing. */
private val SDK_TO_ANDROID_VERSION = mapOf(
    21 to "5.0", 22 to "5.1", 23 to "6", 24 to "7.0", 25 to "7.1", 26 to "8.0", 27 to "8.1",
    28 to "9", 29 to "10", 30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16",
)

internal fun androidVersionLabel(sdk: Int): String = SDK_TO_ANDROID_VERSION[sdk] ?: "SDK $sdk"

/** Parses a plain `adb shell getprop ro.build.version.sdk` reply (the bare API level, one line,
 *  nothing else) into an integer. Null for anything that doesn't parse — a device this property
 *  lookup failed on, or unexpected/empty output — so a caller can fall back instead of crashing a
 *  capture-start path on a single probe. */
fun parseAndroidSdkLevel(output: String): Int? = output.trim().toIntOrNull()

/**
 * The scrcpy-server / scrcpy-CLI arguments that additionally forward device audio for one capture,
 * plus an optional diagnostic to surface when what was actually asked for could not be honored on
 * this device. [serverArgs]/[cliArgs] are always either both empty or both the two-argument
 * "keep device audio" pair — they are a DELTA on top of the base `audio=true`/`audio_codec=opus`
 * (embedded) or the absence of `--no-audio` (external scrcpy window) that the caller already
 * applies whenever [CaptureSettings.audio] is on, not a replacement for it.
 */
data class CaptureAudioPlan(
    val serverArgs: List<String>,
    val cliArgs: List<String>,
    val diagnostic: String? = null,
)

private val NO_EXTRA_AUDIO_ARGS = CaptureAudioPlan(serverArgs = emptyList(), cliArgs = emptyList())

/**
 * Decides whether to ask the bundled scrcpy server (and the external scrcpy window's CLI) to keep
 * playing device audio out the device's own speaker while it is also captured, instead of the
 * default `audio_source=output` (REMOTE_SUBMIX) behavior that silences device playback for the
 * duration.
 *
 * `audio_source=playback` + `audio_dup=true` (scrcpy-server) / `--audio-source=playback
 * --audio-dup` (scrcpy CLI) are only accepted on Android 13+ (verified against the bundled v4.1
 * server's own option parsing — see this feature's task doc) — a device below that requirement, or
 * one whose SDK level could not be read at all ([deviceSdk] null), falls back to the plain
 * "audio captured, device muted" behavior (no extra args) and gets [CaptureAudioPlan.diagnostic]
 * instead, so the muting is never a silent surprise.
 */
fun captureAudioPlan(audio: Boolean, keepDeviceAudio: Boolean, deviceSdk: Int?): CaptureAudioPlan {
    if (!audio || !keepDeviceAudio) return NO_EXTRA_AUDIO_ARGS
    if (deviceSdk != null && deviceSdk >= MIN_SDK_FOR_KEEP_DEVICE_AUDIO) {
        return CaptureAudioPlan(
            serverArgs = listOf("audio_source=playback", "audio_dup=true"),
            cliArgs = listOf("--audio-source=playback", "--audio-dup"),
        )
    }
    val runningVersion = deviceSdk?.let(::androidVersionLabel) ?: "an unknown version"
    return NO_EXTRA_AUDIO_ARGS.copy(
        diagnostic = "Keep sound on the device needs Android 13+; this device runs Android $runningVersion, " +
            "so its speaker is muted while audio is captured.",
    )
}
