package com.indagium.model

/** Parser selected for a log-format profile. IDs are serialized and must remain stable. */
enum class LogFormatEngine {
    LOGCAT,
    DLT,
    PLAIN_TEXT,
    REGEX,
    JSON_LINES,
    CSV,
    SYSLOG_RFC5424,
    SYSLOG_RFC3164,
    LOGFMT,
    CRI,

    /** JSON records with metadata and optional nested sections. */
    METADATA_JSON,
}

/** Canonical meaning assigned to a parsed source field. */
enum class LogFieldRole {
    TIMESTAMP,
    SEVERITY,
    COMPONENT,
    MESSAGE,
    PROCESS_ID,
    THREAD_ID,
    HOST,
    SERVICE,
    CONTAINER,
    STREAM,
    REQUEST_ID,
    TRACE_ID,
    HTTP_METHOD,
    HTTP_PATH,
    SOURCE,
    SECTION,
}

enum class ProfileTimestampKind { AUTO, ISO_8601, CUSTOM, EPOCH, TIME_OF_DAY, RELATIVE }

enum class ProfileEpochUnit { SECONDS, MILLIS, MICROS, NANOS }

enum class LogProfileValueType { TEXT, NUMBER, BOOLEAN, TIMESTAMP }

data class LogFormatAttribute(
    val id: String,
    val label: String,
    val source: String,
    val type: LogProfileValueType = LogProfileValueType.TEXT,
    val visibleByDefault: Boolean = false,
)

enum class LogCapability {
    TEXT_SEARCH,
    NOTES,
    MANUAL_FOLDING,
    SEVERITY_FILTER,
    COMPONENT_FILTER,
    PID_THREAD_FILTER,
    PROCESS_MAP,
    PROCESS_NAMES,
    TIMELINE,
    DELTA_TIME,
    MERGE,
    VIDEO_LINKING,
    ANDROID_ISSUES,
    SOURCE_LOOKUP,
    INCREMENTAL_TAIL,
    RECORD_SPLIT,
    EXPORT,
}

enum class CapabilityAvailability { AVAILABLE, PARTIAL, UNAVAILABLE }

data class LogCapabilityStatus(
    val availability: CapabilityAvailability,
    val reason: String,
)

data class LogFormatDetection(
    val extensions: List<String> = emptyList(),
    val headerContains: List<String> = emptyList(),
    /** Content regex is only considered after explicit selection and configured associations. */
    val contentRegex: String? = null,
    /** User choice: open files with these extensions with this profile, ahead of content detection. */
    val alwaysUseForExtensions: Boolean = false,
)

/**
 * Immutable, data-only parser definition. No executable code is imported or evaluated.
 * [fieldMappings] maps canonical role names to JSON paths, CSV header names, or 1-based regex
 * capture numbers. Built-ins are read-only; users create editable copies with a new ID.
 */
data class LogFormatProfile(
    val id: String,
    val name: String,
    val description: String = "",
    val revision: Int = 1,
    val schemaVersion: Int = 1,
    val engine: LogFormatEngine,
    val detection: LogFormatDetection = LogFormatDetection(),
    val fieldMappings: Map<LogFieldRole, String> = emptyMap(),
    val attributes: List<LogFormatAttribute> = emptyList(),
    val regexPattern: String = "",
    val multilineStartPattern: String = "",
    val timestampPattern: String = "",
    val timestampKind: ProfileTimestampKind = ProfileTimestampKind.AUTO,
    val epochUnit: ProfileEpochUnit = ProfileEpochUnit.MILLIS,
    val relativeClock: String = "",
    val timezone: String = "",
    val severityMapping: Map<String, Char> = emptyMap(),
    val delimiter: Char = ',',
    val hasHeader: Boolean = true,
    val visibleColumns: List<LogFieldRole> = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.SEVERITY, LogFieldRole.COMPONENT, LogFieldRole.MESSAGE),
    val compatibleAnalyzers: Set<String> = emptySet(),
    val issueRules: List<CustomIssueRule> = emptyList(),
    val readOnly: Boolean = false,
    /**
     * A line that does not match the format continues the record above it (a stack trace under a
     * syslog line). Applies to the syslog engines and to a regex profile without a multiline start.
     */
    val joinUnmatchedLines: Boolean = true,
)

/** Profile-level column headers for tab surfaces that need them before the first row exists. */
fun logFormatDisplayColumnDefinitions(profile: LogFormatProfile): List<LogDisplayField> =
    profile.visibleColumns.map { role ->
        LogDisplayField(role.name.lowercase(), role.displayLabel(), "")
    } + profile.attributes.filter { it.visibleByDefault }.map { attribute ->
        LogDisplayField(attribute.id, attribute.label, "")
    }

/** Snapshot JSON omits read-only origin; compare builtin definitions after restoring that flag. */
internal fun usesCustomLogFormatProjection(profile: LogFormatProfile): Boolean {
    val builtin = BUILTIN_LOG_FORMAT_PROFILES.firstOrNull { it.id == profile.id } ?: return true
    return profile.engine != builtin.engine ||
        profile.fieldMappings != builtin.fieldMappings ||
        profile.attributes != builtin.attributes ||
        profile.regexPattern != builtin.regexPattern ||
        profile.multilineStartPattern != builtin.multilineStartPattern ||
        profile.timestampPattern != builtin.timestampPattern ||
        profile.timestampKind != builtin.timestampKind ||
        profile.epochUnit != builtin.epochUnit ||
        profile.relativeClock != builtin.relativeClock ||
        profile.timezone != builtin.timezone ||
        profile.severityMapping != builtin.severityMapping ||
        profile.delimiter != builtin.delimiter ||
        profile.hasHeader != builtin.hasHeader ||
        profile.visibleColumns != builtin.visibleColumns
}

private fun LogFieldRole.displayLabel(): String = when (this) {
    LogFieldRole.TIMESTAMP -> "timestamp"
    LogFieldRole.SEVERITY -> "level"
    LogFieldRole.COMPONENT -> "tag"
    LogFieldRole.MESSAGE -> "message"
    LogFieldRole.PROCESS_ID -> "pid"
    LogFieldRole.THREAD_ID -> "tid"
    else -> name.lowercase().replace('_', ' ')
}

/** Parsed source-clock and original-text information; absolute UTC is absent unless supplied. */
data class ParsedTime(
    val original: String,
    val epochMillis: Long? = null,
    val localTime: String? = null,
    val clockIdentity: String? = null,
    val relativeMillis: Long? = null,
    /** Wall-clock milliseconds for a date-time with no timezone; comparable only within its source clock. */
    val localWallMillis: Long? = null,
)

/** Capabilities are derived from parser behavior and populated fields, never trusted switches. */
fun deriveLogCapabilities(
    profile: LogFormatProfile,
    entries: List<LogEntry>,
    sourceClockId: String? = null,
    // Every text engine tails: the ones that span lines keep their state in IncrementalProfileParser.
    incremental: Boolean = profile.engine !in setOf(LogFormatEngine.DLT, LogFormatEngine.METADATA_JSON),
    sourceLookupSupported: Boolean = false,
): Map<LogCapability, LogCapabilityStatus> {
    val withTime = countTimedEntries(profile, entries, sourceClockId)
    val withSeverity = entries.count { it.severityKnown }
    val withComponent = entries.count { it.tag.isNotBlank() && it.tag != "RAW" }
    val withPid = entries.count { it.hasProcessOrThreadIdentity() }
    val withProcessAndThread = entries.count { it.pid > 0 && it.tid > 0 }
    val processNamePids = com.indagium.utils.computeProcessNames(entries).keys
    val withResolvedProcessName = entries.count { entry -> entry.pid > 0 && entry.pid in processNamePids }
    val total = entries.size
    val clockCompatibility = clockCompatibility(profile, entries, withTime, sourceClockId)
    val timeCoverage = coverage(withTime, total, "timestamp")
    val comparableTime = if (clockCompatibility.comparable) timeCoverage else unavailable(clockCompatibility.reason)
    val deltaTime = if (withTime < 2) unavailable("At least two comparable timestamps are needed for deltas.") else comparableTime
    val mergeTime = mergeTimeAvailability(entries, withTime, clockCompatibility)
    val result = linkedMapOf(
        LogCapability.TEXT_SEARCH to LogCapabilityStatus(CapabilityAvailability.AVAILABLE, "Parsed records are searchable."),
        LogCapability.NOTES to LogCapabilityStatus(CapabilityAvailability.AVAILABLE, "Notes can reference parsed records."),
        LogCapability.MANUAL_FOLDING to LogCapabilityStatus(CapabilityAvailability.AVAILABLE, "Records can be folded manually."),
        LogCapability.SEVERITY_FILTER to if (profile.engine == LogFormatEngine.METADATA_JSON) {
            unavailable("Record status fields are not log severity levels.")
        } else {
            coverage(withSeverity, total, "severity")
        },
        LogCapability.COMPONENT_FILTER to coverage(withComponent, total, "component"),
        LogCapability.PID_THREAD_FILTER to coverage(withPid, total, "process or thread identity"),
        LogCapability.PROCESS_MAP to coverage(withProcessAndThread, total, "paired numeric process and thread identities"),
        LogCapability.PROCESS_NAMES to coverage(withResolvedProcessName, total, "resolved process name"),
        LogCapability.TIMELINE to comparableTime,
        LogCapability.DELTA_TIME to deltaTime,
        LogCapability.MERGE to mergeTime,
        LogCapability.VIDEO_LINKING to comparableTime,
        LogCapability.ANDROID_ISSUES to androidIssuesStatus(profile),
        LogCapability.SOURCE_LOOKUP to if (sourceLookupSupported) {
            coverage(withComponent, total, "component for source lookup")
        } else {
            unavailable("A configured source resolver and matching source root are required.")
        },
        LogCapability.INCREMENTAL_TAIL to incrementalTailStatus(profile, incremental),
        LogCapability.RECORD_SPLIT to (profile.recordSplitUnavailableReason()?.let(::unavailable)
            ?: available("The splitter preserves this engine's complete record boundaries.")),
        LogCapability.EXPORT to LogCapabilityStatus(CapabilityAvailability.AVAILABLE, "Parsed record text can be exported."),
    )
    return result
}

internal fun LogFormatProfile.recordSplitUnavailableReason(): String? = when (engine) {
    LogFormatEngine.METADATA_JSON -> "$name must be read as a whole to preserve its JSON sections."
    LogFormatEngine.CRI -> "$name can interleave partial records across split boundaries."
    else -> null
}

private fun androidIssuesStatus(profile: LogFormatProfile): LogCapabilityStatus =
    if (profile.engine == LogFormatEngine.LOGCAT || "android-crash" in profile.compatibleAnalyzers) {
        LogCapabilityStatus(CapabilityAvailability.AVAILABLE, "An Android crash analyzer is compatible with this profile.")
    } else {
        LogCapabilityStatus(CapabilityAvailability.UNAVAILABLE, "No compatible Android crash analyzer is configured.")
    }

private fun incrementalTailStatus(profile: LogFormatProfile, incremental: Boolean): LogCapabilityStatus = when {
    incremental -> available("The engine supports complete-record incremental parsing.")
    profile.engine == LogFormatEngine.DLT -> unavailable("DLT is a framed binary format and cannot be tailed as text.")
    profile.engine == LogFormatEngine.METADATA_JSON -> unavailable("Sectioned JSON documents are read completely before ordering records by time.")
    else -> unavailable("This engine needs the complete input before parsing.")
}

private fun countTimedEntries(profile: LogFormatProfile, entries: List<LogEntry>, sourceClockId: String?): Int =
    entries.count { entry ->
        when (profile.engine) {
            LogFormatEngine.LOGCAT -> entry.ts.isNotBlank() && entry.tag != "RAW"
            LogFormatEngine.DLT -> entry.profileTime(profile, sourceClockId).hasUsableTime()
            else -> entry.parsedTime.hasUsableTime()
        }
    }

private data class ClockCompatibility(val comparable: Boolean, val signature: String?, val reason: String)

private fun clockCompatibility(
    profile: LogFormatProfile,
    entries: List<LogEntry>,
    withTime: Int,
    sourceClockId: String? = null,
): ClockCompatibility {
    if (profile.engine == LogFormatEngine.LOGCAT && withTime > 0) return ClockCompatibility(true, "legacy-logcat", "")
    val parsed = entries.mapNotNull { it.profileTime(profile, sourceClockId)?.takeIf(ParsedTime::hasUsableTime) }
    if (parsed.isEmpty()) return ClockCompatibility(false, null, "No parseable timestamp values were found.")
    val domains = parsed.mapNotNull(ParsedTime::domain).distinct()
    if (domains.size != 1) return ClockCompatibility(false, null, "Records mix absolute, local, or relative timestamp kinds.")
    val domain = domains.single()
    val identities = parsed.map { it.clockIdentity }.distinct()
    if (domain != "absolute" && (identities.size != 1 || identities.single().isNullOrBlank())) {
        return ClockCompatibility(false, null, "Local and relative timestamps need one source clock.")
    }
    val signature = if (domain == "absolute") "absolute" else "$domain:${identities.single()}"
    return ClockCompatibility(true, signature, "")
}

internal fun logFormatClockSignature(profile: LogFormatProfile, entries: List<LogEntry>, sourceClockId: String? = null): String? {
    val timed = countTimedEntries(profile, entries, sourceClockId)
    return clockCompatibility(profile, entries, timed, sourceClockId).takeIf { it.comparable }?.signature
}

private fun LogEntry.profileTime(profile: LogFormatProfile, sourceClockId: String?): ParsedTime? {
    if (profile.engine != LogFormatEngine.DLT) return parsedTime
    val time = parsedTime ?: com.indagium.utils.canonicalDltParsedTime(this, sourceClockId) ?: return null
    return if (time.relativeMillis != null && profile.relativeClock.isNotBlank()) {
        time.copy(clockIdentity = "relative:${profile.relativeClock}")
    } else {
        time
    }
}

private fun mergeTimeAvailability(
    entries: List<LogEntry>,
    withTime: Int,
    clock: ClockCompatibility,
): LogCapabilityStatus = when {
    withTime == 0 -> unavailable("No comparable timestamps were parsed.")
    !clock.comparable -> unavailable(clock.reason)
    else -> coverage(withTime, entries.size, "timestamp")
}

private fun coverage(count: Int, total: Int, label: String): LogCapabilityStatus = when {
    total == 0 || count == 0 -> unavailable("No $label values were parsed.")
    count < total -> LogCapabilityStatus(CapabilityAvailability.PARTIAL, "$count of $total records have $label values.")
    else -> available("All records have $label values.")
}

private fun available(reason: String) = LogCapabilityStatus(CapabilityAvailability.AVAILABLE, reason)

private fun unavailable(reason: String) = LogCapabilityStatus(CapabilityAvailability.UNAVAILABLE, reason)

private fun LogEntry.hasProcessOrThreadIdentity(): Boolean =
    pid > 0 || tid > 0 ||
        !processIdentity.isNullOrBlank() || !threadIdentity.isNullOrBlank() ||
        profileFields.any { (key, value) -> value.isNotBlank() && key.lowercase() in PROCESS_IDENTITY_KEYS }

private fun ParsedTime?.hasUsableTime(): Boolean = this?.domain() != null

private fun ParsedTime.domain(): String? = when {
    epochMillis != null -> "absolute"
    localWallMillis != null -> "local-wall"
    relativeMillis != null -> "relative"
    localTime != null -> "local-time"
    else -> null
}

private val PROCESS_IDENTITY_KEYS = setOf("pid", "tid", "process_id", "process", "thread", "thread_id")

val BUILTIN_LOG_FORMAT_PROFILES: List<LogFormatProfile> = listOf(
    LogFormatProfile(
        "android-logcat", "Android Logcat", "Android logcat threadtime, time, brief and raw rows.",
        engine = LogFormatEngine.LOGCAT,
        detection = LogFormatDetection(extensions = listOf("log", "txt")),
        visibleColumns = listOf(
            LogFieldRole.TIMESTAMP,
            LogFieldRole.PROCESS_ID,
            LogFieldRole.THREAD_ID,
            LogFieldRole.SEVERITY,
            LogFieldRole.COMPONENT,
            LogFieldRole.MESSAGE,
        ),
        readOnly = true,
    ),
    LogFormatProfile(
        "automotive-dlt", "Automotive DLT", "Binary DLT, DLT viewer text and CSV exports.",
        engine = LogFormatEngine.DLT,
        detection = LogFormatDetection(extensions = listOf("dlt")),
        fieldMappings = mapOf(
            LogFieldRole.HOST to "ecu",
            LogFieldRole.SERVICE to "apid",
            LogFieldRole.CONTAINER to "ctid",
        ),
        visibleColumns = listOf(
            LogFieldRole.TIMESTAMP,
            LogFieldRole.HOST,
            LogFieldRole.SERVICE,
            LogFieldRole.CONTAINER,
            LogFieldRole.SEVERITY,
            LogFieldRole.COMPONENT,
            LogFieldRole.MESSAGE,
        ),
        readOnly = true,
    ),
    LogFormatProfile(
        "metadata-json", "JSON with metadata", "JSON records with metadata fields, nested sections and expandable details.",
        revision = 2,
        engine = LogFormatEngine.METADATA_JSON,
        detection = LogFormatDetection(extensions = listOf("json")),
        visibleColumns = listOf(LogFieldRole.TIMESTAMP, LogFieldRole.SOURCE, LogFieldRole.MESSAGE),
        joinUnmatchedLines = false,
        readOnly = true,
    ),
    LogFormatProfile("plain-text", "Plain text", "One non-empty line per record.", engine = LogFormatEngine.PLAIN_TEXT, readOnly = true),
    LogFormatProfile(
        "json-lines", "JSON Lines", "JSON objects per record, including pretty-printed objects and objects in root arrays.",
        engine = LogFormatEngine.JSON_LINES, readOnly = true,
    ),
    LogFormatProfile(
        "syslog-rfc5424", "Syslog RFC 5424", "Structured syslog records with RFC 5424 headers.",
        engine = LogFormatEngine.SYSLOG_RFC5424, readOnly = true,
    ),
    LogFormatProfile(
        "syslog-rfc3164", "Syslog RFC 3164", "Traditional syslog records with RFC 3164 headers.",
        engine = LogFormatEngine.SYSLOG_RFC3164, readOnly = true,
    ),
) + ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES
