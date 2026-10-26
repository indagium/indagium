package com.indagium.capture

/** Video codec requested from the bundled scrcpy server for a new capture. H.264 remains the
 * persisted default so existing settings and devices keep their previous behavior. H.265 is no
 * longer offered (every Android device encodes H.264); a saved `H265` decodes to the default, and
 * HEVC recordings still play through `ScrcpyCodecIds.H265`. */
enum class CaptureVideoCodec(val label: String, val scrcpyName: String, val scrcpyId: Int) {
    H264("H.264", "h264", 0x68_32_36_34),
    AV1("AV1", "av1", 0x00_61_76_31),
}

enum class CaptureEncoderAcceleration { HARDWARE, SOFTWARE, HYBRID, UNKNOWN }

data class CaptureVideoEncoder(
    val codec: CaptureVideoCodec,
    val name: String,
    val acceleration: CaptureEncoderAcceleration,
)

/** Device-specific results from the pinned, bundled scrcpy server's `list_encoders` command. */
data class CaptureVideoEncoderCapabilities(
    val encoders: List<CaptureVideoEncoder>,
    val androidSdk: Int? = null,
) {
    fun encoders(codec: CaptureVideoCodec): List<CaptureVideoEncoder> = encoders.filter { it.codec == codec }

    /** Advanced codecs are enabled only when Android explicitly reports a hardware encoder.
     * H.264 keeps scrcpy's existing automatic encoder choice. */
    fun preferredHardwareEncoder(codec: CaptureVideoCodec): CaptureVideoEncoder? =
        encoders(codec).firstOrNull { it.acceleration == CaptureEncoderAcceleration.HARDWARE }
}

sealed interface CaptureVideoEncoderProbeState {
    data object Loading : CaptureVideoEncoderProbeState

    data class Ready(val capabilities: CaptureVideoEncoderCapabilities) : CaptureVideoEncoderProbeState

    data class Failed(val message: String) : CaptureVideoEncoderProbeState
}

/** Parses the human-readable `--list-encoders` output from the bundled scrcpy v4.1 server.
 * API levels below 29 omit acceleration tags; those entries intentionally stay UNKNOWN. */
fun parseCaptureVideoEncoders(output: String): List<CaptureVideoEncoder> {
    val entries = mutableListOf<CaptureVideoEncoder>()
    output.lineSequence().forEach { line ->
        val match = VIDEO_ENCODER_LINE.find(line) ?: return@forEach
        // The server still lists its h265 encoders; codecs not offered for capture are skipped.
        val codec = CaptureVideoCodec.entries.firstOrNull { it.scrcpyName == match.groupValues[1] }
            ?: return@forEach
        val acceleration = when (match.groupValues[3].lowercase()) {
            "hw" -> CaptureEncoderAcceleration.HARDWARE
            "sw" -> CaptureEncoderAcceleration.SOFTWARE
            "hybrid" -> CaptureEncoderAcceleration.HYBRID
            else -> CaptureEncoderAcceleration.UNKNOWN
        }
        entries += CaptureVideoEncoder(codec, match.groupValues[2], acceleration)
    }
    return entries.distinctBy { it.codec to it.name }
}

private val VIDEO_ENCODER_LINE = Regex(
    "--video-codec=(h264|h265|av1)\\s+--video-encoder=([^\\s]+)(?:\\s+\\((hw|sw|hybrid)\\))?",
    RegexOption.IGNORE_CASE,
)

internal fun advancedCodecFallbackReason(
    codec: CaptureVideoCodec,
    capabilities: CaptureVideoEncoderCapabilities?,
): String? {
    if (codec == CaptureVideoCodec.H264) return null
    if (capabilities == null) return "${codec.label} encoder support is unknown; using H.264."
    if (capabilities.preferredHardwareEncoder(codec) != null) return null
    val listed = capabilities.encoders(codec)
    return when {
        listed.isEmpty() -> "This device does not list a ${codec.label} encoder; using H.264."
        listed.all { it.acceleration == CaptureEncoderAcceleration.SOFTWARE } ->
            "This device lists only software ${codec.label} encoders; using H.264 to preserve capture performance."
        else -> "${codec.label} hardware acceleration could not be confirmed; using H.264."
    }
}

/**
 * The encoder support [CaptureRecorder] decides an advanced-codec capture with: nothing for H.264 or
 * a video-less capture (no probe, no adb traffic), the caller's [known] result when it already has
 * one for this device (the launcher's Check / auto-probe in this app session), otherwise a fresh
 * [probe] (its failures are the caller's: a null result means "unknown", which falls back to H.264).
 */
internal fun resolveVideoEncoderCapabilities(
    settings: CaptureSettings,
    known: CaptureVideoEncoderCapabilities?,
    probe: () -> CaptureVideoEncoderCapabilities?,
): CaptureVideoEncoderCapabilities? {
    if (!settings.recordVideo || settings.videoCodec == CaptureVideoCodec.H264) return null
    return known ?: probe()
}

/** The capabilities of a finished probe, or null while loading/failed/never run: only `Ready` is reusable. */
internal fun CaptureVideoEncoderProbeState?.readyCapabilities(): CaptureVideoEncoderCapabilities? =
    (this as? CaptureVideoEncoderProbeState.Ready)?.capabilities
