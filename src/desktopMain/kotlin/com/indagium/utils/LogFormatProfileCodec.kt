package com.indagium.utils

import com.indagium.debug.AppLogger
import com.indagium.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private const val LOG_FORMAT_PROFILE_SCHEMA = 1
private const val LOG_FORMAT_BUNDLE_FORMAT = "indagium-log-format-profiles"
private const val LOG_FORMAT_BUNDLE_VERSION = 1
private const val MAX_IMPORTED_PROFILES = 200
internal const val MAX_PROFILE_NAME_CHARS = 120
private const val MAX_PROFILE_DESCRIPTION_CHARS = 1000
private const val MAX_PROFILE_REVISION = 100_000
private const val MAX_PROFILE_ATTRIBUTES = 128
private const val MAX_PROFILE_ATTRIBUTE_LABEL_CHARS = 100
private const val MAX_PROFILE_ATTRIBUTE_SOURCE_CHARS = 256
private const val MAX_PROFILE_ISSUE_RULES = 100
private const val MAX_PROFILE_DETECTION_EXTENSIONS = 40
private const val MAX_PROFILE_HEADER_HINTS = 20
private const val MAX_PROFILE_HEADER_HINT_CHARS = 256
private const val MAX_ISSUE_RULE_NAME_CHARS = 120
private const val MAX_PROFILE_FIELD_SOURCE_CHARS = 256
private const val MAX_PROFILE_TIMEZONE_CHARS = 100
private const val MAX_PROFILE_TIMESTAMP_PATTERN_CHARS = 128
private const val MIN_SAFE_PROFILE_REVISION = 1
private const val MAX_SAFE_PROFILE_REGEX_CHARS = 512
private const val MAX_REGEX_CAPTURE_GROUPS = 512
private const val PROFILE_ID_PATTERN = "^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$"
private val SAFE_PROFILE_ID = Regex(PROFILE_ID_PATTERN)
private val GENERATED_EXPORT_COLUMN_IDS = setOf("row_number", "time_delta", "pid_name")

/** Stable, executable-code-free JSON representation used by settings and profile import/export. */
fun logFormatProfileJson(profile: LogFormatProfile): String {
    validateLogFormatProfile(profile)
    return profile.toJson().toString()
}

fun logFormatProfilesJson(profiles: List<LogFormatProfile>): String {
    require(profiles.size <= MAX_IMPORTED_PROFILES) { "A profile bundle may contain at most $MAX_IMPORTED_PROFILES profiles." }
    profiles.forEach(::validateLogFormatProfile)
    return buildJsonArray { profiles.forEach { add(it.toJson()) } }.toString()
}

/**
 * The file and MCP export form: a versioned envelope around one or more profiles. Settings keep the
 * bare array from [logFormatProfilesJson]; [decodeLogFormatProfiles] reads both, and a bare profile.
 */
fun logFormatProfileBundleJson(profiles: List<LogFormatProfile>): String {
    require(profiles.size <= MAX_IMPORTED_PROFILES) { "A profile bundle may contain at most $MAX_IMPORTED_PROFILES profiles." }
    profiles.forEach(::validateLogFormatProfile)
    return buildJsonObject {
        put("format", LOG_FORMAT_BUNDLE_FORMAT)
        put("bundleVersion", LOG_FORMAT_BUNDLE_VERSION)
        put("profiles", buildJsonArray { profiles.forEach { add(it.toJson()) } })
    }.toString()
}

fun logFormatProfileFromJson(raw: String): LogFormatProfile? = runCatching {
    decodeLogFormatProfiles(raw).singleOrNull()
}.getOrNull()

/**
 * Lenient decoder for the settings library: every array element is decoded and validated on its own, so
 * one bad profile (a hand-edited file, a newer schema, a regex a later build rejects) never drops the
 * valid ones next to it. Invalid elements and repeated ids (the first wins) are skipped.
 */
fun logFormatProfilesFromJson(raw: String): List<LogFormatProfile> {
    val values = runCatching { profileJsonValues(Json.parseToJsonElement(raw)) }.getOrNull() ?: return emptyList()
    val seen = mutableSetOf<String>()
    val result = mutableListOf<LogFormatProfile>()
    for ((index, element) in values.withIndex()) {
        if (result.size >= MAX_IMPORTED_PROFILES) break
        val decoded = runCatching { element.jsonObject.toLogFormatProfile().also(::validateLogFormatProfile) }
        val profile = decoded.getOrNull()
        when {
            profile == null -> runCatching {
                AppLogger.warn("settings", "Skipped invalid log format profile #$index: ${decoded.exceptionOrNull()?.message}")
            }
            seen.add(profile.id) -> result += profile
        }
    }
    return result
}

/** Strict decoder for import/preview flows; malformed profiles are reported before mutation. */
fun decodeLogFormatProfiles(raw: String): List<LogFormatProfile> {
    val values = profileJsonValues(Json.parseToJsonElement(raw))
    return values.map { it.jsonObject.toLogFormatProfile() }.also { profiles ->
        require(profiles.size <= MAX_IMPORTED_PROFILES) { "A profile bundle may contain at most $MAX_IMPORTED_PROFILES profiles." }
        require(profiles.map { it.id }.distinct().size == profiles.size) { "Profile IDs must be unique." }
        profiles.forEach(::validateLogFormatProfile)
    }
}

private fun profileJsonValues(root: JsonElement): JsonArray = when {
    root is JsonArray -> root
    root is JsonObject && root["profiles"] is JsonArray -> {
        root["format"]?.let { format ->
            require(format.stringValue() == LOG_FORMAT_BUNDLE_FORMAT) { "This file is not a log format profile bundle." }
        }
        if ("bundleVersion" in root) {
            require(root.strictIntOrNull("bundleVersion") == LOG_FORMAT_BUNDLE_VERSION) {
                "Unsupported log format bundle version. Update Indagium to import it."
            }
        }
        root["profiles"] as JsonArray
    }
    root is JsonObject && "id" in root -> JsonArray(listOf(root))
    else -> error("Expected a profile object or profile array")
}

private fun LogFormatProfile.toJson() = buildJsonObject {
    put("schemaVersion", LOG_FORMAT_PROFILE_SCHEMA)
    put("id", id)
    put("name", name)
    put("description", description)
    put("revision", revision)
    put("engine", engine.name)
    put("detection", buildJsonObject {
        put("extensions", buildJsonArray { detection.extensions.forEach { add(JsonPrimitive(it)) } })
        put("headerContains", buildJsonArray { detection.headerContains.forEach { add(JsonPrimitive(it)) } })
        detection.contentRegex?.let { put("contentRegex", it) }
        // Only written when set, so existing profiles keep their exact JSON.
        if (detection.alwaysUseForExtensions) put("alwaysUseForExtensions", true)
    })
    put("fieldMappings", buildJsonObject { fieldMappings.forEach { (role, value) -> put(role.name, value) } })
    put("attributes", buildJsonArray {
        attributes.forEach { attribute -> add(buildJsonObject {
            put("id", attribute.id); put("label", attribute.label); put("source", attribute.source)
            put("type", attribute.type.name); put("visibleByDefault", attribute.visibleByDefault)
        }) }
    })
    put("regexPattern", regexPattern)
    put("multilineStartPattern", multilineStartPattern)
    put("timestampPattern", timestampPattern)
    put("timestampKind", timestampKind.name)
    put("epochUnit", epochUnit.name)
    put("relativeClock", relativeClock)
    put("timezone", timezone)
    put("severityMapping", buildJsonObject { severityMapping.forEach { (key, value) -> put(key, value.toString()) } })
    put("delimiter", delimiter.toString())
    put("hasHeader", hasHeader)
    // Written only when switched off, so profiles saved before the option existed stay byte-identical.
    if (!joinUnmatchedLines) put("joinUnmatchedLines", false)
    put("visibleColumns", buildJsonArray { visibleColumns.forEach { add(JsonPrimitive(it.name)) } })
    put("compatibleAnalyzers", buildJsonArray { compatibleAnalyzers.forEach { add(JsonPrimitive(it)) } })
    put("issueRules", buildJsonArray {
        issueRules.forEach { rule -> add(buildJsonObject {
            put("id", rule.id); put("name", rule.name); put("regex", rule.regex); put("enabled", rule.enabled)
        }) }
    })
}

private fun JsonObject.toLogFormatProfile(): LogFormatProfile {
    require(strictIntOrNull("schemaVersion") == LOG_FORMAT_PROFILE_SCHEMA) {
        "Unsupported log format profile schema version."
    }
    val detection = if ("detection" in this) jsonObject("detection") else JsonObject(emptyMap())
    val roles = jsonObject("fieldMappings").map { (key, value) ->
        val role = enumValue<LogFieldRole>(key, "field role")
        role to (value.stringValue() ?: error("Field mapping '$key' must be text."))
    }.toMap()
    val severityObject = jsonObject("severityMapping")
    val severity = severityObject.map { (key, value) ->
        val mapped = value.stringValue()?.singleOrNull()
            ?: error("Severity mapping '$key' must contain exactly one character.")
        key.uppercase(Locale.ROOT) to mapped
    }.toMap().also { mappings ->
        require(mappings.size == severityObject.size) { "Severity keys must be unique after normalization." }
    }
    val rules = jsonArray("issueRules").map { item ->
        val rule = item as? JsonObject ?: error("Each issue rule must be an object.")
        CustomIssueRule(rule.requiredString("id"), rule.requiredString("name"), rule.requiredString("regex"), rule.bool("enabled", true))
    }
    val attributes = jsonArray("attributes").map { item ->
        val value = item as? JsonObject ?: error("Each profile attribute must be an object.")
        LogFormatAttribute(
            id = value.requiredString("id"), label = value.requiredString("label"), source = value.requiredString("source"),
            type = value.stringOrNull("type")?.let { enumValue<LogProfileValueType>(it, "attribute type") } ?: LogProfileValueType.TEXT,
            visibleByDefault = value.bool("visibleByDefault", false),
        )
    }
    return LogFormatProfile(
        id = requiredString("id"), name = requiredString("name"), description = requiredString("description"),
        revision = int("revision", 1), schemaVersion = LOG_FORMAT_PROFILE_SCHEMA,
        engine = enumValue(requiredString("engine"), "engine"),
        detection = LogFormatDetection(
            extensions = detection.stringArray("extensions"),
            headerContains = detection.stringArray("headerContains"),
            contentRegex = detection.stringOrNull("contentRegex"),
            alwaysUseForExtensions = detection.bool("alwaysUseForExtensions", false),
        ),
        fieldMappings = roles,
        attributes = attributes,
        regexPattern = stringOrNull("regexPattern").orEmpty(),
        multilineStartPattern = stringOrNull("multilineStartPattern").orEmpty(),
        timestampPattern = stringOrNull("timestampPattern").orEmpty(),
        timestampKind = stringOrNull("timestampKind")?.let { enumValue<ProfileTimestampKind>(it, "timestamp kind") } ?: ProfileTimestampKind.AUTO,
        epochUnit = stringOrNull("epochUnit")?.let { enumValue<ProfileEpochUnit>(it, "epoch unit") } ?: ProfileEpochUnit.MILLIS,
        relativeClock = stringOrNull("relativeClock").orEmpty(),
        timezone = stringOrNull("timezone").orEmpty(),
        severityMapping = severity,
        delimiter = stringOrNull("delimiter")?.singleOrNull()
            ?: if ("delimiter" in this) error("Delimiter must contain exactly one character.") else ',',
        hasHeader = bool("hasHeader", true),
        joinUnmatchedLines = bool("joinUnmatchedLines", true),
        visibleColumns = visibleColumns(),
        compatibleAnalyzers = stringArray("compatibleAnalyzers").toSet(),
        issueRules = rules,
    )
}

/** Validates all stored profile fields before they can enter settings or the global registry. */
fun validateLogFormatProfile(profile: LogFormatProfile) {
    require(profile.schemaVersion == LOG_FORMAT_PROFILE_SCHEMA) { "Unsupported profile schema version." }
    require(SAFE_PROFILE_ID.matches(profile.id) && !profile.id.contains("..")) {
        "Profile ID must use safe letters, digits, dots, underscores or hyphens and cannot contain '..'."
    }
    require(profile.name.isNotBlank() && profile.name.length <= MAX_PROFILE_NAME_CHARS) {
        "Profile name must be 1–$MAX_PROFILE_NAME_CHARS characters."
    }
    require(profile.description.length <= MAX_PROFILE_DESCRIPTION_CHARS) { "Profile description is too long." }
    require(profile.revision in MIN_SAFE_PROFILE_REVISION..MAX_PROFILE_REVISION) { "Invalid profile revision." }
    require(profile.fieldMappings.size <= LogFieldRole.entries.size) { "Too many field mappings." }
    validateProfileAttributes(profile)
    require(profile.issueRules.size <= MAX_PROFILE_ISSUE_RULES) { "A profile may contain at most $MAX_PROFILE_ISSUE_RULES issue rules." }
    validateProfileDetection(profile)
    if (profile.regexPattern.isNotBlank()) validateSafeProfileRegex(profile.regexPattern)
    if (profile.multilineStartPattern.isNotBlank()) validateSafeProfileRegex(profile.multilineStartPattern)
    profile.issueRules.forEach { validateSafeProfileRegex(it.regex) }
    require(profile.delimiter != '\n' && profile.delimiter != '\r' && profile.delimiter != '"') { "Invalid CSV delimiter." }
    validateProfileTime(profile)
    profile.fieldMappings.values.forEach { source ->
        require(source.isNotBlank() && source.length <= MAX_PROFILE_FIELD_SOURCE_CHARS) { "Invalid field mapping source." }
    }
    validateRegexFieldMappings(profile)
    require(profile.visibleColumns.distinct().size == profile.visibleColumns.size) { "Visible columns must be unique." }
    validateProfileSeverityAndRules(profile)
}

private fun validateProfileAttributes(profile: LogFormatProfile) {
    require(profile.attributes.size <= MAX_PROFILE_ATTRIBUTES) { "A profile may define at most $MAX_PROFILE_ATTRIBUTES extra attributes." }
    require(profile.attributes.map { it.id }.distinct().size == profile.attributes.size) { "Extra attribute IDs must be unique." }
    val reservedColumnIds = LogFieldRole.entries.map { it.name.lowercase(Locale.ROOT) }.toSet() + GENERATED_EXPORT_COLUMN_IDS
    profile.attributes.forEach { attribute ->
        require(attribute.id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$"))) { "Invalid extra attribute ID." }
        require(attribute.id.lowercase(Locale.ROOT) !in reservedColumnIds) {
            "Extra attribute ID '${attribute.id}' conflicts with a canonical or generated export column. Choose another ID."
        }
        require(attribute.label.isNotBlank() && attribute.label.length <= MAX_PROFILE_ATTRIBUTE_LABEL_CHARS &&
            attribute.source.isNotBlank() && attribute.source.length <= MAX_PROFILE_ATTRIBUTE_SOURCE_CHARS
        ) { "Invalid extra attribute." }
    }
}

private fun validateProfileDetection(profile: LogFormatProfile) {
    require(profile.detection.extensions.size <= MAX_PROFILE_DETECTION_EXTENSIONS &&
        profile.detection.headerContains.size <= MAX_PROFILE_HEADER_HINTS
    ) { "Too many detection hints." }
    profile.detection.extensions.forEach { extension ->
        require(extension.matches(Regex("^[A-Za-z0-9._-]{1,32}$"))) { "Invalid filename extension hint." }
    }
    require(!profile.detection.alwaysUseForExtensions || profile.detection.extensions.isNotEmpty()) {
        "Always-open association needs at least one filename extension."
    }
    profile.detection.headerContains.forEach { hint ->
        require(hint.length <= MAX_PROFILE_HEADER_HINT_CHARS) { "A header detection hint is too long." }
    }
    profile.detection.contentRegex?.let { validateSafeProfileRegex(it) }
}

private fun validateProfileTime(profile: LogFormatProfile) {
    require(profile.timezone.length <= MAX_PROFILE_TIMEZONE_CHARS && profile.timestampPattern.length <= MAX_PROFILE_TIMESTAMP_PATTERN_CHARS) {
        "Timestamp configuration is too long."
    }
    if (profile.timezone.isNotBlank()) require(runCatching { ZoneId.of(profile.timezone) }.isSuccess) { "Unknown time zone '${profile.timezone}'." }
    if (profile.timestampPattern.isNotBlank()) {
        require(runCatching { DateTimeFormatter.ofPattern(profile.timestampPattern, Locale.ROOT) }.isSuccess) { "Timestamp pattern is invalid." }
    }
    if (profile.timestampKind == ProfileTimestampKind.CUSTOM) {
        require(profile.timestampPattern.isNotBlank()) { "Custom timestamps need a timestamp pattern." }
    }
}

private fun validateRegexFieldMappings(profile: LogFormatProfile) {
    if (profile.engine == LogFormatEngine.REGEX && profile.regexPattern.isNotBlank()) {
        val captures = runCatching { Regex(profile.regexPattern).toPattern().matcher("").groupCount() }.getOrDefault(0)
        (profile.fieldMappings.values + profile.attributes.map { it.source }).forEach { source ->
            val index = source.toIntOrNull() ?: error("Regex field mappings must use capture group numbers.")
            require(index in 1..captures && index <= MAX_REGEX_CAPTURE_GROUPS) {
                "Regex field mapping '$source' refers to a missing capture group."
            }
        }
    }
}

private fun validateProfileSeverityAndRules(profile: LogFormatProfile) {
    profile.severityMapping.forEach { (key, value) ->
        require(key.isNotBlank() && key.length <= MAX_PROFILE_FIELD_SOURCE_CHARS) { "Invalid severity mapping key." }
        require(value in setOf('V', 'D', 'I', 'W', 'E', 'A')) { "Severity mappings must target V, D, I, W, E or A." }
    }
    profile.compatibleAnalyzers.forEach { analyzer ->
        require(analyzer.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$"))) { "Invalid compatible analyzer ID." }
    }
    profile.issueRules.forEach { rule ->
        require(rule.id.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$"))) { "Invalid issue rule ID." }
        require(rule.name.isNotBlank() && rule.name.length <= MAX_ISSUE_RULE_NAME_CHARS) { "Invalid issue rule name." }
    }
    require(profile.issueRules.map { it.id }.distinct().size == profile.issueRules.size) { "Issue rule IDs must be unique." }
    require(profile.severityMapping.keys.map { it.uppercase(Locale.ROOT) }.distinct().size == profile.severityMapping.size) {
        "Severity keys must be unique after normalization."
    }
}

/** Conservative preflight plus TextMatch's deadline guarded matcher protects user regexes. */
fun validateSafeProfileRegex(pattern: String) {
    require(pattern.isNotBlank() && pattern.length <= MAX_SAFE_PROFILE_REGEX_CHARS) { "Regex must contain 1–$MAX_SAFE_PROFILE_REGEX_CHARS characters." }
    require(!Regex("\\\\[1-9]|\\(\\?<[=!]|\\(\\?P").containsMatchIn(pattern)) { "Backreferences and lookbehind are not supported." }
    require(!Regex("\\([^)]*[+*][^)]*\\)[+*{]").containsMatchIn(pattern)) { "Nested repetition is not supported in profile regexes." }
    require(runCatching { Regex(pattern) }.isSuccess) { "Regex syntax is invalid." }
}

private fun JsonObject.requiredString(name: String): String = stringOrNull(name) ?: error("'$name' must be text.")

private fun JsonObject.stringOrNull(name: String): String? = if (name !in this) {
    null
} else {
    (this[name] as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
        ?: error("'$name' must be text.")
}

private fun JsonObject.int(name: String, default: Int): Int = if (name !in this) {
    default
} else {
    strictIntOrNull(name) ?: error("'$name' must be an integer.")
}

private fun JsonObject.strictIntOrNull(name: String): Int? =
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull

private fun JsonObject.bool(name: String, default: Boolean): Boolean = if (name !in this) {
    default
} else {
    (this[name] as? JsonPrimitive)?.takeIf { !it.isString }?.contentOrNull?.toBooleanStrictOrNull()
        ?: error("'$name' must be a boolean.")
}

private fun JsonObject.stringArray(name: String): List<String> = jsonArray(name).map {
    (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content ?: error("'$name' must contain only strings.")
}

private fun JsonObject.jsonObject(name: String): JsonObject = if (name !in this) {
    JsonObject(emptyMap())
} else {
    this[name] as? JsonObject ?: error("'$name' must be an object.")
}

private fun JsonObject.jsonArray(name: String): JsonArray = if (name !in this) {
    JsonArray(emptyList())
} else {
    this[name] as? JsonArray ?: error("'$name' must be an array.")
}

private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content

private fun JsonObject.visibleColumns(): List<LogFieldRole> = when {
    "visibleColumns" !in this -> listOf(LogFieldRole.TIMESTAMP, LogFieldRole.SEVERITY, LogFieldRole.COMPONENT, LogFieldRole.MESSAGE)
    this["visibleColumns"] !is JsonArray -> error("'visibleColumns' must be an array.")
    else -> (this["visibleColumns"] as JsonArray).map { item ->
        val value = (item as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content
            ?: error("Visible columns must contain role names.")
        enumValue<LogFieldRole>(value, "visible column role")
    }
}

/** Every decode/validation failure is an [IllegalArgumentException]; shadows kotlin.error in this file. */
private fun error(message: String): Nothing = throw IllegalArgumentException(message)

private inline fun <reified T : Enum<T>> enumValue(value: String, label: String): T =
    enumValues<T>().firstOrNull { it.name == value } ?: error("Unknown $label '$value'.")
