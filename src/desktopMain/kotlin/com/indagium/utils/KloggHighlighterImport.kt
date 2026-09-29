package com.indagium.utils

import androidx.compose.ui.graphics.Color
import com.indagium.model.FilterMode
import com.indagium.model.Highlighter
import com.indagium.model.LogLevel
import com.indagium.model.SavedFilter

// Imports the highlighter sets of a klogg settings export (a QSettings INI, see QSettingsIni.kt)
// as saved filters. The user picks or drops the file themselves; nothing here looks for an installed
// klogg. Each set becomes one SavedFilter carrying only highlighters, and every rule keeps klogg's own
// behaviour through the Highlighter fields Phase 1 added (textColor != null marks it klogg-style):
//
//   klogg regexp / use_regex      -> pattern / regex          (use_regex=false: plain text, klogg escapes it)
//   klogg ignore_case             -> caseSensitive = !ignore_case
//   klogg match_only              -> wholeLine = !match_only  (a whole-line rule tints the row)
//   klogg back_colour/fore_colour -> color / textColor
//   (always)                      -> captureGroupsOnly = true (klogg colours only capture groups)
//   klogg variate_colors          -> colorVariance = color_variance, only together with match_only
//
// Missing keys take klogg's defaults (use_regex=true, ignore_case=false, match_only=false,
// variate_colors=false, color_variance=15). The "quick\..." entries are colour presets, not rules.

private const val COLLECTION = "HighlighterSetCollection"
private const val LEGACY_SET = "FilterSet"
private const val MAX_ARRAY = 10_000
private const val MAX_TOTAL_HIGHLIGHTERS = 5_000
private const val MAX_NOTES = 200
private const val KLOGG_DEFAULT_VARIANCE = 15
private const val PATTERN_PREVIEW = 40

/** One klogg set as a saved filter, with the notes and skip reason the review dialog shows for it. */
internal data class KloggImportedSet(
    val filter: SavedFilter,
    val notes: List<String>,
    /** Non-null when the set cannot be imported at all (no usable highlighter). */
    val skippedReason: String?,
)

internal data class KloggImport(val sets: List<KloggImportedSet>, val notes: List<String>)

private val KLOGG_SECTION = Regex("""(?m)^\s*\[(HighlighterSetCollection|FilterSet)]\s*$""")

/** Whether [text] carries a klogg highlighter-set section (routing decides on content, not extension). */
internal fun looksLikeKloggSettings(text: String): Boolean = KLOGG_SECTION.containsMatchIn(text)

/** Reads a klogg export; null when the file has no highlighter-set section at all. */
internal fun importKloggHighlighters(text: String, fileName: String): KloggImport? {
    val ini = QSettingsIni.parse(text)
    val hasCollection = ini.hasGroup(COLLECTION)
    val hasLegacy = ini.hasGroup(LEGACY_SET)
    if (!hasCollection && !hasLegacy) return null
    val active = ini.stringList("$COLLECTION/active_sets").orEmpty().map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    val sets = mutableListOf<KloggImportedSet>()
    val budget = ImportBudget()
    for (n in arrayIndices(ini, "$COLLECTION/sets")) {
        if (budget.truncated) break
        val base = "$COLLECTION/sets/$n/HighlighterSet"
        val setId = ini.string("$base/id")?.trim().orEmpty()
        val name = ini.string("$base/name")?.trim().orEmpty().ifEmpty { "klogg set $n" }
        val key = setId.ifEmpty { "n$n" }
        sets += budget.limitNotes(
            convertSet(ini, "klogg:$key", name, "$base/highlighters", isActive = setId.isNotEmpty() && setId in active, budget),
        )
    }
    if (!budget.truncated && hasLegacy && ini.hasGroup("$LEGACY_SET/filters")) {
        val name = fileName.substringBeforeLast('.').trim().ifEmpty { "klogg filters" }
        sets += budget.limitNotes(convertSet(ini, "klogg:legacy", name, "$LEGACY_SET/filters", isActive = false, budget))
    }
    val notes = mutableListOf<String>()
    if (ini.undecodedKeys.isNotEmpty()) {
        notes += "${ini.undecodedKeys.size} setting(s) are stored as binary values (@Variant/@ByteArray), which are not read; highlighter rules are unaffected."
    }
    if (budget.truncated) notes += "The file has more than $MAX_TOTAL_HIGHLIGHTERS highlighters; the rest were not imported."
    if (budget.droppedNotes > 0) notes += "…and ${budget.droppedNotes} more note(s) not shown."
    return KloggImport(sets, notes)
}

/** Caps what one import may produce, so a crafted file cannot exhaust memory or the review dialog. */
private class ImportBudget {
    var highlighters = 0
    var truncated = false
    private var shownNotes = 0
    var droppedNotes = 0
        private set

    /** [set] with its notes cut to what is left of the shared [MAX_NOTES] allowance. */
    fun limitNotes(set: KloggImportedSet): KloggImportedSet {
        val room = (MAX_NOTES - shownNotes).coerceAtLeast(0)
        val kept = set.notes.take(room)
        shownNotes += kept.size
        droppedNotes += set.notes.size - kept.size
        return if (kept.size == set.notes.size) set else set.copy(notes = kept)
    }
}

// The array slots that actually hold keys, in order. klogg always writes "<prefix>/size", which stays
// an upper bound; without it every present slot counts. Empty slots are never visited.
private fun arrayIndices(ini: QSettingsIni, prefix: String): List<Int> {
    val limit = ini.int("$prefix/size")?.coerceIn(0, MAX_ARRAY) ?: MAX_ARRAY
    return ini.arrayIndices(prefix).filter { it <= limit }.take(MAX_ARRAY)
}

private fun convertSet(
    ini: QSettingsIni,
    filterId: String,
    name: String,
    hlPrefix: String,
    isActive: Boolean,
    budget: ImportBudget,
): KloggImportedSet {
    val notes = mutableListOf<String>()
    val layoutNotes = mutableListOf<String>()
    val highlighters = mutableListOf<Highlighter>()
    for (m in arrayIndices(ini, hlPrefix)) {
        if (budget.highlighters >= MAX_TOTAL_HIGHLIGHTERS) {
            budget.truncated = true
            break
        }
        val hl = convertHighlighter(ini, "$hlPrefix/$m", "$filterId:$m", m, notes, layoutNotes) ?: continue
        highlighters += hl
        budget.highlighters++
    }
    notes += layoutNotes
    if (isActive) notes += "active in klogg"
    val filter = SavedFilter(
        id = filterId,
        name = name,
        levels = LogLevel.entries.toSet(),
        activeTags = emptySet(),
        kwText = "",
        kwRegex = false,
        mode = FilterMode.TAGS,
        excludeTags = emptySet(),
        excludeKw = "",
        excludeKwRegex = false,
        highlighters = highlighters,
        seqOn = true,
    )
    return KloggImportedSet(filter, notes, if (highlighters.isEmpty()) "no valid highlighters" else null)
}

@Suppress("LongParameterList")
private fun convertHighlighter(
    ini: QSettingsIni,
    p: String,
    id: String,
    index: Int,
    notes: MutableList<String>,
    layoutNotes: MutableList<String>,
): Highlighter? {
    val pattern = ini.string("$p/regexp").orEmpty()
    val label = "Highlighter $index (${pattern.take(PATTERN_PREVIEW)}${if (pattern.length > PATTERN_PREVIEW) "…" else ""})"
    if (pattern.isEmpty()) {
        notes += "Highlighter $index: skipped, it has an empty pattern."
        return null
    }
    val useRegex = ini.bool("$p/use_regex") ?: true
    val ignoreCase = ini.bool("$p/ignore_case") ?: false
    val matchOnly = ini.bool("$p/match_only") ?: false
    if (useRegex && !isValidRegexPattern(pattern, ignoreCase)) {
        notes += "$label: skipped, not a valid Java regex (PCRE-only syntax such as (?P<n>...), \\K or (?| is not supported)."
        return null
    }
    val fore = colorOrDefault(ini, "$p/fore_colour", Color.Black, "$label: fore colour", notes)
    val back = colorOrDefault(ini, "$p/back_colour", Color.White, "$label: back colour", notes)
    val variate = ini.bool("$p/variate_colors") ?: false
    val variance = ini.int("$p/color_variance") ?: KLOGG_DEFAULT_VARIANCE
    layoutNote(label, pattern, useRegex)?.let { layoutNotes += it }
    return Highlighter(
        id = id,
        pattern = pattern,
        regex = useRegex,
        color = back,
        on = true,
        wholeLine = !matchOnly,
        caseSensitive = !ignoreCase,
        textColor = fore,
        captureGroupsOnly = true,
        colorVariance = if (variate && matchOnly) variance.coerceIn(0, 100) else 0,
    )
}

private fun colorOrDefault(ini: QSettingsIni, key: String, default: Color, what: String, notes: MutableList<String>): Color {
    val raw = ini.string(key) ?: return default
    return parseQtColor(raw) ?: default.also { notes += "$what \"$raw\" is not a colour klogg understands; using the default." }
}

// Indagium matches against its own rendering of a row (no date, different field spacing), so klogg
// patterns written against the raw logcat text can only be flagged, not translated.
private val LEADING_ANCHOR = Regex("""^(\(\?[a-zA-Z]+\)|\((\?:)?)*\^""")
private val DATE_SHAPE = Regex(
    """\d{4}-\d{2}-\d{2}|\b\d{2}-\d{2}\b|\\d(\{2}|\\d)-\\d(\{2}|\\d)|\[0-9](\{2})?-\[0-9]""",
)
private val LEVEL_TAG_SHAPE = Regex("""(?<![A-Za-z0-9])[VDIWEFA]/[A-Za-z_(\[\\]""")
private val POSIX_CLASS = Regex("""\[:\w+:]""")

private fun layoutNote(label: String, pattern: String, useRegex: Boolean): String? = when {
    useRegex && LEADING_ANCHOR.containsMatchIn(pattern) ->
        "$label: anchored with ^ to the start of the raw log line, which Indagium lays out differently, so it may not match exactly."
    DATE_SHAPE.containsMatchIn(pattern) ->
        "$label: mentions a date, but Indagium's line text has no date, so it may not match."
    LEVEL_TAG_SHAPE.containsMatchIn(pattern) ->
        "$label: uses the raw \"L/Tag\" logcat shape, which Indagium's line text does not have, so it may not match."
    useRegex && POSIX_CLASS.containsMatchIn(pattern) ->
        "$label: POSIX classes such as [[:alpha:]] mean something else in Java regex, so it may match differently."
    else -> null
}
