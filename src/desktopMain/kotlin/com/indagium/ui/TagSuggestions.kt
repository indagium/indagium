package com.indagium.ui

import com.indagium.model.ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.BUILTIN_LOG_FORMAT_PROFILES
import com.indagium.model.LogFormat
import com.indagium.model.LogFormatEngine
import com.indagium.model.LogLevel
import com.indagium.model.LogTab
import com.indagium.utils.logFormatProfileFromJson

fun tagCandidates(
    sortedTags: List<String>,
    search: String,
    selectedTags: Set<String>,
    packagePrefixes: Set<String>,
    tagUsage: Map<String, Int>,
    excludeTags: Set<String> = emptySet(),
    mostUsedLimit: Int = 5,
    searchLimit: Int = 50,
): List<String> {
    val rank = sortedTags.withIndex().associate { it.value to it.index }

    fun ordered(tags: List<String>) =
        tags.sortedWith(compareByDescending<String> { tagUsage[it] ?: 0 }.thenBy { rank[it] ?: Int.MAX_VALUE })

    fun available(tags: List<String>) =
        tags.filter { it !in selectedTags && it !in excludeTags }

    val base = when {
        search.isNotBlank() -> available(sortedTags.filter { it.contains(search, ignoreCase = true) })
        packagePrefixes.isNotEmpty() -> ordered(available(sortedTags.filter { tagMatchesAnyPrefix(it, packagePrefixes) }))
            .take(mostUsedLimit.coerceAtLeast(0))
        else -> ordered(available(sortedTags.filter { (tagUsage[it] ?: 0) > 0 }))
            .take(mostUsedLimit.coerceAtLeast(0))
    }

    val limit = if (search.isBlank()) mostUsedLimit else searchLimit
    return base
        .distinct()
        .take(limit.coerceAtLeast(0))
}

// Lifted out of ui/FilterPanel.kt's own `combinedTagCandidates` remember block (Tags-mode unified
// pkg-prefix/tag search field) so ui/FilterBar.kt (the horizontal filter bar) can share the exact
// same candidate ordering without either duplicating the logic or reaching into FilterPanel.kt's
// private composable state — FilterPanel.kt itself keeps its own inline copy untouched (see that
// file's own combinedTagCandidates remember() for why: it is the one composable in this codebase
// explicitly frozen against edits while the horizontal bar is exploratory).
//
// Result shape mirrors the panel's: pkg-prefix matches first (only when [search] has input), then
// tag matches — each entry paired with whether it's a package prefix (true) or a plain tag (false),
// so a caller can render/apply the two differently (add-prefix vs include/exclude-tag) without a
// second lookup.
fun combinedTagCandidates(
    sortedTags: List<String>,
    search: String,
    packagePrefixes: Set<String>,
    tagUsage: Map<String, Int>,
    mostUsedTagLimit: Int,
    // False for a tab whose components are not Android packages (see componentsArePackages): no prefix rows then.
    offerPackagePrefixes: Boolean = true,
): List<Pair<String, Boolean>> {
    val pkgs = if (offerPackagePrefixes) packagePrefixCandidates(sortedTags, search, limit = 4).map { it to true } else emptyList()
    val tags = tagCandidates(
        sortedTags = sortedTags,
        search = search,
        selectedTags = emptySet(),
        packagePrefixes = packagePrefixes,
        tagUsage = tagUsage,
        mostUsedLimit = mostUsedTagLimit,
    ).map { it to false }
    return pkgs + tags
}

/**
 * Whether [this] tab's tags are Logcat tags, whose dotted names are Android packages a prefix can
 * group. A profile's components (a systemd unit like checkout.service, a logger name) and DLT's
 * ECU/APP/CTX contexts are names to match exactly: a dotted one is not a package.
 */
internal fun LogTab.componentsArePackages(): Boolean = !hasProfileRecords() && logFormat != LogFormat.DLT

private fun LogTab.hasProfileRecords(): Boolean = logData.firstOrNull()?.recordMetadata?.usesProfilePresentation == true

/** A DLT tab drawn natively: its tags are ECU/APID/CTID contexts. */
private fun LogTab.isNativeDlt(): Boolean = logFormat == LogFormat.DLT && !hasProfileRecords()

/** The tag filter's name for [this] tab: Tags in Logcat, Contexts in DLT (ECU/APP/CTX), Components in a profile tab. */
internal fun LogTab.componentFilterLabel(): String = if (isNativeDlt()) "Contexts" else componentFilterLabel(componentsArePackages())

/** [componentFilterLabel] short enough for a narrow filter tile header: Logcat's Tags is already short. */
internal fun LogTab.compactComponentFilterLabel(): String = when {
    isNativeDlt() -> "Ctx"
    componentsArePackages() -> "Tags"
    else -> "Comp."
}

internal fun LogTab.componentActionName(): String = when {
    isNativeDlt() -> "Context"
    componentsArePackages() -> "Tag"
    else -> "Component"
}

internal fun com.indagium.model.LogEntry.contextFilterIdentity(format: LogFormat? = null): String? =
    tag.takeIf {
        it.isNotBlank() &&
            (it != "RAW" || recordMetadata?.usesProfilePresentation == true) &&
            (format != LogFormat.DLT || dltEcuId != null || dltAppId != null || dltContextId != null)
    }

/** The tag field's hint for [this] tab. */
internal fun LogTab.tagInputPlaceholder(): String = if (isNativeDlt()) "ECU/APP/CTX…" else tagInputPlaceholder(componentsArePackages())

/** The tag filter's name: Logcat calls its components tags; a profile tab's are components (a logger, a unit, an app). */
internal fun componentFilterLabel(packages: Boolean): String = if (packages) "Tags" else "Components"

/** One level filter pill: its letter and the source level names it covers, for its tooltip. */
internal data class LevelPill(val letter: String, val tooltip: String)

private val GENERIC_LEVEL_NAMES = listOf(
    listOf("TRACE", "VERBOSE"), listOf("DEBUG"), listOf("INFO"), listOf("WARN", "WARNING"), listOf("ERROR"), listOf("FATAL", "CRITICAL"),
)
private val SYSLOG_LEVEL_NAMES = listOf(
    emptyList(), listOf("DEBUG (7)"), listOf("INFO (6)", "NOTICE (5)"), listOf("WARNING (4)"), listOf("ERROR (3)"),
    listOf("CRIT (2)", "ALERT (1)", "EMERG (0)"),
)
private val PINO_LEVEL_NAMES = listOf(
    listOf("TRACE (10)"), listOf("DEBUG (20)"), listOf("INFO (30)"), listOf("WARN (40)"), listOf("ERROR (50)"), listOf("FATAL (60)"),
)
private val SYSLOG_PRIORITY_KEYS = (0..7).map(Int::toString).toSet()
private val PINO_LEVEL_KEYS = setOf("10", "20", "30", "40", "50", "60")

/**
 * The level filter's pills, one per [LogLevel] bucket. Logcat and DLT keep V D I W E A. A profile tab
 * takes them from its profile, so every file of one format shows the same pills whatever levels it
 * happens to hold: T D I W E F for the common names and Pino's scale, T D I W E C for syslog and
 * journald (C for CRIT, ALERT and EMERG). Each tooltip names the levels the pill covers.
 */
internal fun LogTab.levelPills(): List<LevelPill> {
    if (!hasProfileRecords()) return LogLevel.entries.map { LevelPill(it.key.toString(), it.label) }
    val profile = logFormatProfileSnapshot?.let(::logFormatProfileFromJson)
        ?: (BUILTIN_LOG_FORMAT_PROFILES + ADDITIONAL_BUILTIN_LOG_FORMAT_PROFILES).firstOrNull { it.id == logFormatProfileId }
    val keys = profile?.severityMapping?.keys.orEmpty()
    val syslog = profile?.engine in setOf(LogFormatEngine.SYSLOG_RFC5424, LogFormatEngine.SYSLOG_RFC3164) || keys == SYSLOG_PRIORITY_KEYS
    val names = when {
        syslog -> SYSLOG_LEVEL_NAMES
        keys == PINO_LEVEL_KEYS -> PINO_LEVEL_NAMES
        else -> GENERIC_LEVEL_NAMES
    }
    val letters = if (syslog) listOf("T", "D", "I", "W", "E", "C") else listOf("T", "D", "I", "W", "E", "F")
    return LogLevel.entries.mapIndexed { index, level ->
        val covered = names.getOrNull(index).orEmpty()
        LevelPill(letters[index], if (covered.isEmpty()) "${level.label}: none in this format" else covered.joinToString(", "))
    }
}

/** The tag field's hint: Logcat takes package prefixes or tags; a profile tab takes its components. */
internal fun tagInputPlaceholder(packages: Boolean): String = if (packages) "pkg prefix or tag…" else "component…"

/** Whether the typed [input] is added as a package prefix (a dotted name in a Logcat tab) rather than an exact tag. */
internal fun typedTagIsPackagePrefix(input: String, packages: Boolean): Boolean = packages && input.contains('.')

fun packagePrefixCandidates(sortedTags: List<String>, search: String, limit: Int = 8): List<String> {
    if (search.isBlank()) return emptyList()
    val needle = search.trim()
    return sortedTags
        .flatMap { packagePrefixesContaining(it, needle) }
        .distinct()
        .take(limit.coerceAtLeast(0))
}

fun displayTagForPrefix(tag: String, packagePrefixes: Set<String>): Pair<String, String?> {
    val prefix = matchingPrefix(tag, packagePrefixes) ?: return tag to null
    val suffix = tag.removePrefix(prefix).removePrefix(".").ifBlank { tag }
    return suffix to prefix
}

private fun tagMatchesAnyPrefix(tag: String, packagePrefixes: Set<String>) =
    matchingPrefix(tag, packagePrefixes) != null

private fun matchingPrefix(tag: String, packagePrefixes: Set<String>): String? =
    packagePrefixes
        .filter { prefix -> tag == prefix || tag.startsWith("$prefix.") }
        .maxByOrNull { it.length }

private fun packagePrefixesContaining(tag: String, search: String): List<String> {
    val parts = tag.split('.')
    if (parts.size < 2) return emptyList()
    return (1 until parts.size)
        .map { parts.take(it).joinToString(".") }
        .filter { it.contains(search, ignoreCase = true) }
}

/** The deepest dotted prefix shared by every tag in [tags], e.g. `com.example.bt` for
 *  `com.example.bt.Adapter` + `com.example.bt.Scanner`. Never returns a prefix equal to one of
 *  [tags] itself — `"A"` + `"A.B"` share the dotted prefix `"A"`, but `"A"` is one of the tags,
 *  not a package above them, so this returns null rather than a name indistinguishable from the
 *  tag it would be proposed for. Fewer than two tags has no "shared" prefix to speak of. */
fun commonPackagePrefix(tags: Set<String>): String? {
    if (tags.size < 2) return null
    val partsByTag = tags.map { it.split('.') }
    val shortest = partsByTag.minOf { it.size }
    var shared = 0
    while (shared < shortest && partsByTag.all { it[shared] == partsByTag[0][shared] }) shared++
    if (shared == 0) return null
    val prefix = partsByTag[0].take(shared).joinToString(".")
    return prefix.takeUnless { it in tags }
}

/**
 * Proposes a display name for a new multi-tag component. The result is **never applied
 * automatically** — it is surfaced as an explicit "use" action for the user to accept, the same
 * convention the rule-suggestion "Add" buttons follow (SeqDiagramDialog.kt: suggestions are never
 * inferred or injected silently).
 *
 * A single tag proposes nothing (its alias is the user's own words, not an inference to second-
 * guess). Otherwise: the last segment of [tags]' [commonPackagePrefix], if they share one; else,
 * only when every one of [tags]' in-range rows came from exactly one pid, that pid's process name
 * (last segment); else null.
 */
fun proposeComponentName(
    tags: Set<String>,
    pidsByTag: Map<String, Set<Int>>,
    processNames: Map<Int, String>,
): String? {
    if (tags.size < 2) return null
    commonPackagePrefix(tags)?.let { prefix -> return prefix.substringAfterLast('.') }
    val pids = tags.flatMapTo(mutableSetOf()) { pidsByTag[it].orEmpty() }
    val onlyPid = pids.singleOrNull() ?: return null
    return processNames[onlyPid]?.substringAfterLast('.')
}
