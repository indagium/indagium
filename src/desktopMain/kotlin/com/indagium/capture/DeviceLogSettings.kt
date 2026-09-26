package com.indagium.capture

import java.util.Locale

// "Device logging" (New tab capture launcher): the device's own logd ring-buffer sizes and its
// `log.tag`/`log.tag.<TAG>` filter level, read and changed with plain `adb` commands rather than
// anything capture-session-specific — these are properties of the DEVICE, not of a recording. Every
// function in this file is pure (no adb, no Compose) so the parsing itself is unit-testable against
// real-world sample output; [com.indagium.ui.CaptureService] is what actually runs adb and holds
// [DeviceLogState] per serial.

/** One buffer's stats from one line of `adb logcat -g` output. All three numeric fields are
 *  optional — `adb`'s own wording has drifted across Android/platform-tools versions (capitalized
 *  vs. lowercase unit letters, "Kb" vs "KB" vs "KiB", consumed sometimes omitted), and a line this
 *  build doesn't recognize must not crash the read, only skip that one buffer — see
 *  [parseLogcatBufferSizes]. */
data class LogBufferSize(
    val buffer: String,
    val sizeBytes: Long?,
    val consumedBytes: Long? = null,
    val maxEntryBytes: Long? = null,
)

/** `log.tag`/`log.tag.<TAG>` values. Deliberately NOT `V D I W E F S` (adb's own filter-spec
 *  ordering) — [ASSERT] is only ever parsed, never offered by the level picker, since the task this
 *  feature implements only wires the setter for `V|D|I|W|E|S` (see [selectable]). Devices/tools that
 *  wrote "A" (the `Log.ASSERT` priority name) or "F" (adb's own filter-spec letter for the same
 *  priority) both parse to [ASSERT] so a pre-existing override still displays sensibly instead of
 *  falling back to "unknown". */
enum class LogTagLevel(val propValue: String, val label: String) {
    VERBOSE("V", "Verbose"),
    DEBUG("D", "Debug"),
    INFO("I", "Info"),
    WARN("W", "Warn"),
    ERROR("E", "Error"),
    ASSERT("F", "Assert"),
    SILENT("S", "Silent"),
    ;

    companion object {
        /** The picker's own six choices, in display order; "Device default" (null / empty prop) is
         *  a separate, implicit seventh option every call site adds on its own. */
        val selectable: List<LogTagLevel> = listOf(VERBOSE, DEBUG, INFO, WARN, ERROR, SILENT)

        /** Null for an empty/blank value (device default — the property is unset) or a value no
         *  known priority letter matches, never a thrown exception. */
        fun fromPropValue(raw: String): LogTagLevel? {
            val trimmed = raw.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.equals("A", ignoreCase = true)) return ASSERT
            return entries.firstOrNull { it.propValue.equals(trimmed, ignoreCase = true) }
        }
    }
}

/** The buffer-size SegmentedControl's four choices. `-G` accepts a handful of shorthand suffixes;
 *  these four match what real devices commonly ship as their default main-buffer size (256K on many
 *  low-RAM devices) up through a generous 16M for heavy debugging sessions. */
enum class LogBufferSizeChoice(val label: String, val bytes: Long) {
    SIZE_256K("256 KB", 256L * 1024L),
    SIZE_1M("1 MB", 1024L * 1024L),
    SIZE_4M("4 MB", 4L * 1024L * 1024L),
    SIZE_16M("16 MB", 16L * 1024L * 1024L),
    ;

    /** The literal `-G` argument, e.g. `"256K"`. */
    val logcatArg: String
        get() = when (this) {
            SIZE_256K -> "256K"
            SIZE_1M -> "1M"
            SIZE_4M -> "4M"
            SIZE_16M -> "16M"
        }
}

/** One buffer line, tolerant of the unit-spelling drift across adb/Android versions: `256KB`,
 *  `256Kb`, `256 KiB`, `1.00MB`, and a bare byte count with no letter at all. The three trailing
 *  stats (consumed / max entry / max payload) are each independently optional so a line missing one
 *  of them (or ordering them differently) still yields whatever it does carry rather than failing to
 *  match at all. */
private val BUFFER_SIZE_LINE = Regex(
    """^(\S+):\s*ring buffer is\s*([\d.]+)\s*([A-Za-z]*)""" +
        """(?:\s*\(\s*([\d.]+)\s*([A-Za-z]*)\s*consumed\s*\))?""" +
        """(?:,\s*max entry is\s*(\d+)\s*[A-Za-z]*)?""" +
        """(?:,\s*max payload is\s*(\d+)\s*[A-Za-z]*)?""",
)

/** Parses every recognizable line of `adb logcat -g` (or `logcat -g -b <buffer>`) output. Unknown or
 *  malformed lines (a warning banner, a device-specific diagnostic, blank lines) are silently
 *  skipped rather than aborting the whole read — see this file's own doc for why that tolerance
 *  matters across Android/platform-tools versions. */
fun parseLogcatBufferSizes(output: String): List<LogBufferSize> {
    val results = mutableListOf<LogBufferSize>()
    output.lineSequence().forEach { rawLine ->
        val match = BUFFER_SIZE_LINE.find(rawLine.trim()) ?: return@forEach
        val groups = match.groupValues
        val sizeBytes = parseByteSize(groups[2], groups[3]) ?: return@forEach
        val consumedBytes = if (groups[4].isNotBlank()) parseByteSize(groups[4], groups[5]) else null
        val maxEntryBytes = groups[6].toLongOrNull()
        results += LogBufferSize(buffer = groups[1], sizeBytes = sizeBytes, consumedBytes = consumedBytes, maxEntryBytes = maxEntryBytes)
    }
    return results
}

private const val BYTES_PER_KIB = 1024L
private const val BYTES_PER_MIB = BYTES_PER_KIB * 1024L
private const val BYTES_PER_GIB = BYTES_PER_MIB * 1024L

private fun parseByteSize(numberText: String, unitText: String): Long? {
    val number = numberText.toDoubleOrNull() ?: return null
    val multiplier = when (unitText.trim().take(1).uppercase(Locale.US)) {
        "K" -> BYTES_PER_KIB.toDouble()
        "M" -> BYTES_PER_MIB.toDouble()
        "G" -> BYTES_PER_GIB.toDouble()
        else -> 1.0
    }
    return (number * multiplier).toLong()
}

/** True when every reported buffer's size is known and one [LogBufferSizeChoice] matches all of
 *  them exactly — used to highlight the SegmentedControl's current selection. A device whose sizes
 *  don't all agree, or don't land exactly on one of the four choices, simply shows no highlight
 *  rather than a misleading one. */
fun matchingBufferSizeChoice(sizes: List<LogBufferSize>): LogBufferSizeChoice? {
    val distinctSizes = sizes.mapNotNull { it.sizeBytes }.distinct()
    val onlySize = distinctSizes.singleOrNull() ?: return null
    return LogBufferSizeChoice.entries.firstOrNull { it.bytes == onlySize }
}

/** Small buffers drop lines during bursts (logd overwrites the oldest entry once the ring fills) —
 *  the strip's warning line fires off the `main` buffer specifically, since that's the one almost
 *  every app's own logging lands in. A device that doesn't report `main` at all (an unrecognized
 *  `-g` line, or a device with no such buffer) shows no warning rather than a false one. */
fun mainBufferIsSmall(sizes: List<LogBufferSize>): Boolean =
    sizes.firstOrNull { it.buffer == "main" }?.sizeBytes?.let { it < LogBufferSizeChoice.SIZE_1M.bytes } ?: false

/** Compact one-line summary, e.g. `"main 256 KB · system 256 KB · crash 64 KB"`. */
fun formatBufferSizesCompact(sizes: List<LogBufferSize>): String =
    sizes.joinToString(" · ") { "${it.buffer} ${formatBufferSizeShort(it.sizeBytes)}" }

/** Compact byte formatting for one buffer size — deliberately simpler than
 *  CaptureStrip.kt's [formatCaptureBytes] (KiB/MiB/GiB with one decimal): buffer sizes are almost
 *  always exact powers-of-1024 chosen from [LogBufferSizeChoice] or a device default, so a whole
 *  number of KB/MB reads cleaner here than a decimal that's usually just ".0". */
fun formatBufferSizeShort(bytes: Long?): String {
    if (bytes == null || bytes < 0) return "?"
    return when {
        bytes >= BYTES_PER_MIB -> {
            val mb = bytes.toDouble() / BYTES_PER_MIB.toDouble()
            if (mb == Math.floor(mb)) "${mb.toLong()} MB" else String.format(Locale.US, "%.1f MB", mb)
        }
        bytes >= BYTES_PER_KIB -> "${bytes / BYTES_PER_KIB} KB"
        else -> "$bytes B"
    }
}

/** One `[key]: [value]` line of a plain `adb shell getprop` listing (no name — the full dump). Lines
 *  that don't match this shape (blank lines, a device's stray stderr) are skipped. */
private val GETPROP_LINE = Regex("""^\[(.+?)\]:\s*\[(.*)\]$""")

/** Parses the FULL `adb shell getprop` dump into key/value pairs. Order is preserved (a
 *  [LinkedHashMap]) purely so output stays deterministic for tests; callers needing per-tag
 *  overrides should use [perTagLogLevelOverrides] instead of filtering this map themselves. */
fun parseGetpropListing(output: String): Map<String, String> {
    val result = LinkedHashMap<String, String>()
    output.lineSequence().forEach { rawLine ->
        val match = GETPROP_LINE.find(rawLine.trim()) ?: return@forEach
        result[match.groupValues[1]] = match.groupValues[2]
    }
    return result
}

private const val LOG_TAG_PROPERTY_PREFIX = "log.tag."

/** `log.tag.<TAG>` entries from a full `adb shell getprop` dump, keyed by the bare tag name (prefix
 *  stripped) — the per-tag override list the panel shows read-only rows for. An entry with an empty
 *  value is excluded: `setprop log.tag.<TAG> ""` (this panel's own "remove" action) clears the
 *  property, but it can still show up as `[log.tag.FOO]: []` in the dump until the device actually
 *  drops the key, so treating a blank value as "still overridden" would make Remove look like it
 *  didn't work. */
fun perTagLogLevelOverrides(getpropOutput: String): Map<String, String> =
    parseGetpropListing(getpropOutput)
        .filterKeys { it.startsWith(LOG_TAG_PROPERTY_PREFIX) && it.length > LOG_TAG_PROPERTY_PREFIX.length }
        .mapKeys { (key, _) -> key.removePrefix(LOG_TAG_PROPERTY_PREFIX) }
        .filterValues { it.isNotBlank() }

/** The Buffer size dropdown's own button text (New tab's "Device logging" panel): the matching
 *  [LogBufferSizeChoice]'s label when every buffer currently agrees on one of the four choices,
 *  the literal current size when they agree on a size that ISN'T one of the four (the panel must
 *  always show what's actually on the device, even off-menu — e.g. a device that shipped with a
 *  2 MB main buffer), "Mixed" when buffers disagree, or "Unknown" before any read has completed. */
fun bufferSizeButtonLabel(sizes: List<LogBufferSize>): String {
    matchingBufferSizeChoice(sizes)?.let { return it.label }
    if (sizes.isEmpty()) return "Unknown"
    val distinctSizes = sizes.mapNotNull { it.sizeBytes }.distinct()
    return if (distinctSizes.size == 1) formatBufferSizeShort(distinctSizes.single()) else "Mixed"
}

/** The Log level dropdown's own button text — "Default (device)" for the implicit null/unset
 *  case (the property is absent or blank), otherwise the level's own display label. */
fun logLevelButtonLabel(level: LogTagLevel?): String = level?.label ?: "Default (device)"

/** The "On device: …" status line shown under both dropdowns (New tab's "Device logging" panel),
 *  built from the device's own re-read state — NEVER from whatever the user just picked in a
 *  dropdown — so the line only changes once the apply has actually landed and been confirmed by a
 *  fresh read, which is the whole point of showing it at all. */
fun formatDeviceLogStatusLine(sizes: List<LogBufferSize>, globalLevel: LogTagLevel?): String {
    val bufferPart = if (sizes.isEmpty()) "buffer info unavailable" else formatBufferSizesCompact(sizes)
    val levelPart = if (globalLevel == null) {
        "log.tag not set (device default)"
    } else {
        "log.tag = ${globalLevel.propValue} (${globalLevel.label})"
    }
    return "On device: $bufferPart · $levelPart"
}

/** What a [DeviceLogState] is currently doing off-thread — split out of a single `busy: Boolean` so
 *  the panel can tell a passive background re-read ([REFRESHING]: the device-refresh poll, a device
 *  switch, or the panel's own "Refresh" button — none of them changed anything) apart from an
 *  actual user-requested change ([APPLYING]: a buffer-size/log-level pick or a per-tag override
 *  removal) or an in-flight "Restart adb as root" recovery ([ROOTING]: `adb root`, the device
 *  reconnect wait, and the one retry of the change that failed — see
 *  [com.indagium.ui.CaptureService.restartAdbAsRoot]) — see [deviceLogStatusText]'s own doc for why
 *  that distinction is the whole point. */
enum class DeviceLogActivity { IDLE, REFRESHING, APPLYING, ROOTING }

/** One user-requested device-logging change that can fail and be retried after "Restart adb as
 *  root" — the exact adb command a permission failure was hit on, held on [DeviceLogState.failedChange]
 *  so [com.indagium.ui.CaptureService.restartAdbAsRoot] can re-issue precisely that command rather
 *  than guessing from whatever the dropdowns currently show (which may have moved on by the time
 *  the user clicks the button). Mirrors the three setters on `CaptureService`
 *  (setDeviceLogBufferSize/setDeviceGlobalLogLevel/clearDeviceLogTagOverride) one-for-one. */
sealed class DeviceLogRetryableChange {
    data class BufferSize(val choice: LogBufferSizeChoice) : DeviceLogRetryableChange()

    data class GlobalLevel(val level: LogTagLevel?) : DeviceLogRetryableChange()

    data class ClearTagOverride(val tag: String) : DeviceLogRetryableChange()
}

/** Keyword match for an adb failure that looks like a permission problem (an unrooted userdebug
 *  build refusing `setprop`, SELinux denying it outright, `logcat -G` refused on a locked-down
 *  buffer, …) — the trigger for the "Restart adb as root" button. Deliberately loose (bare
 *  "permission" and "eacces" match on their own) since adb/Android's own wording for this class of
 *  failure has never been consistent across versions or vendors; a false positive here only offers
 *  a button that then does nothing useful (adb root harmlessly no-ops or refuses), while a false
 *  negative hides real recovery from the user entirely. Case-insensitive. */
private val PERMISSION_FAILURE_KEYWORDS = listOf(
    "permission",
    "not permitted",
    "failed to set property",
    "avc: denied",
    "selinux",
    "setprop: failed",
    "unable to set property",
    "insufficient permissions",
    "eacces",
)

/** Text-only classifier so callers can match either a raw adb result (see the [CaptureCommandResult]
 *  overload below) or an already-formatted failure message (e.g. [com.indagium.ui.adbFailureMessage]'s
 *  output) without re-deriving the same keyword list. */
fun isPermissionFailure(text: String): Boolean {
    val lower = text.lowercase()
    return PERMISSION_FAILURE_KEYWORDS.any { lower.contains(it) }
}

/** True for a non-zero-exit adb result whose combined stdout+stderr reads like a permission
 *  failure. A zero exit is never a permission failure even if the text happens to mention one of
 *  the keywords in passing (e.g. a getprop dump that legitimately contains the word "permission"). */
fun isPermissionFailure(result: CaptureCommandResult): Boolean =
    result.exitCode != 0 && isPermissionFailure("${result.stdoutText()}\n${result.stderrText()}")

/** What one `adb -s <serial> root` attempt reported, parsed from its combined stdout+stderr —
 *  see `adb`'s own client source for these exact phrases; they have been stable across
 *  platform-tools releases. [RESTARTING] means adbd is bouncing and the caller must wait for the
 *  device to reappear before doing anything else; [ALREADY_ROOT] means it's safe to proceed
 *  immediately; [PRODUCTION_REFUSAL] is the one outcome that is not a transient failure — a
 *  production build will never allow this, so the caller should stop offering the button for this
 *  serial; [UNKNOWN] covers everything else (an unrecognized message, a genuine adb failure, a
 *  timeout) and is treated the same as [PRODUCTION_REFUSAL] by the caller: don't loop on it. */
enum class AdbRootOutcome { RESTARTING, ALREADY_ROOT, PRODUCTION_REFUSAL, UNKNOWN }

/** Parses `adb root`'s own combined stdout+stderr into an [AdbRootOutcome]. Matched on substrings
 *  rather than an exact-line match since adb has, in the past, prefixed or suffixed these messages
 *  with extra context (a warning banner, a server-starting notice) depending on version. */
fun classifyAdbRootOutput(output: String): AdbRootOutcome {
    val text = output.lowercase()
    return when {
        text.contains("already running as root") -> AdbRootOutcome.ALREADY_ROOT
        text.contains("cannot run as root in production") -> AdbRootOutcome.PRODUCTION_REFUSAL
        text.contains("restarting adbd as root") -> AdbRootOutcome.RESTARTING
        else -> AdbRootOutcome.UNKNOWN
    }
}

/** Read-only snapshot of one device's logging configuration, kept on [com.indagium.ui.CaptureService]
 *  keyed by serial (never in composable `remember` — see the New tab panel's own doc) so it survives
 *  the device-refresh polling and a tab switch alike. [activity] covers both the initial read and any
 *  refresh/apply-then-reread cycle — see [DeviceLogActivity]'s own doc; [busy] is the old "something
 *  is in flight" shorthand every enable/disable check still wants. [error] is the last read/apply
 *  failure's message, cleared on the next successful read. */
data class DeviceLogState(
    val serial: String,
    val bufferSizes: List<LogBufferSize> = emptyList(),
    val globalLevel: LogTagLevel? = null,
    val perTagOverrides: Map<String, String> = emptyMap(),
    val activity: DeviceLogActivity = DeviceLogActivity.IDLE,
    val error: String? = null,
    val loaded: Boolean = false,
    /** The change to retry once "Restart adb as root" succeeds — set only when [error] is the
     *  result of a permission failure (see [isPermissionFailure]) and this serial hasn't already
     *  had a root attempt ([rootAttempted]); null in every other case, which is exactly the
     *  condition the panel uses to decide whether to show the button at all. This and
     *  [rootAttempted] back the "Restart adb as root" recovery (see
     *  [com.indagium.ui.CaptureService.restartAdbAsRoot]) and are deliberately NOT reset by every
     *  failure the way [error] is — they track state across the whole recovery flow, not just the
     *  latest apply. Both go back to their defaults on the next successful read (a plain [error]
     *  is enough context for any failure that isn't a rooting candidate). */
    val failedChange: DeviceLogRetryableChange? = null,
    /** True once "Restart adb as root" has actually been attempted for this serial — whether the
     *  root itself failed (production build, unknown error) or the post-root retry still failed.
     *  Keeps the button from reappearing and looping on the same unresolved permission problem;
     *  cleared only by a fresh, from-scratch read (a device switch or the panel's own "Refresh"). */
    val rootAttempted: Boolean = false,
) {
    val busy: Boolean get() = activity != DeviceLogActivity.IDLE
}

/** The device-logging panel's status line (item 3): prefers the last successfully-read values over
 *  whatever is currently in flight, so a background refresh never blanks a line the panel already
 *  has good data for. [DeviceLogActivity.APPLYING] is the one case that still shows a bare
 *  "Applying…" with no cached line behind it — the user just changed a device setting, and the old
 *  values are about to be wrong, so showing them as current would be misleading. A plain
 *  [DeviceLogActivity.REFRESHING] (the 3s poll, a device switch, the "Refresh" button) keeps the
 *  cached line and appends a small suffix instead, so the row's text — and therefore the panel's
 *  height — never jumps. Before the very first successful read for this serial ([DeviceLogState.loaded]
 *  false), there is nothing cached to fall back to. */
fun deviceLogStatusText(state: DeviceLogState?): String = when {
    state?.activity == DeviceLogActivity.APPLYING -> "Applying…"
    // Same bare-message treatment as APPLYING, for the same reason: adbd is restarting and about
    // to reconnect, so the cached buffer/level line is about to be stale anyway.
    state?.activity == DeviceLogActivity.ROOTING -> "Restarting adb as root…"
    state != null && state.loaded -> {
        val cached = formatDeviceLogStatusLine(state.bufferSizes, state.globalLevel)
        if (state.activity == DeviceLogActivity.REFRESHING) "$cached · Refreshing…" else cached
    }
    state?.activity == DeviceLogActivity.REFRESHING -> "Reading device settings…"
    else -> "Not read yet"
}
