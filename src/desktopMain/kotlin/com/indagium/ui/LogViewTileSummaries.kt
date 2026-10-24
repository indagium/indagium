package com.indagium.ui

import com.indagium.model.AnnBlock
import com.indagium.model.Annotations
import com.indagium.model.CrashKind
import com.indagium.model.CrashSite
import com.indagium.model.Filter
import com.indagium.model.FilterMode
import com.indagium.model.LogAnalysis
import com.indagium.model.LogLevel
import com.indagium.model.ManualCollapseBlock
import com.indagium.utils.groupIssueSites

// The one-line summaries a log-view tile shows in its header ("2 active", "1 excluded", "1 ANR"), derived from the tab's
// model data. Pure; each returns null when there is nothing to say, so the header shows no summary. Sentence case, "·"
// between parts.

// At most this many hidden levels are listed by name; beyond that the levels still shown are listed instead.
private const val MAX_LEVELS_LISTED = 3

private fun plural(count: Int, one: String, many: String = "${one}s"): String = "$count ${if (count == 1) one else many}"

private fun joined(vararg parts: String?): String? = parts.filterNotNull().takeIf { it.isNotEmpty() }?.joinToString(" · ")

/** Tags (and package prefixes) the filter keeps or drops: "2 active", "1 excluded", "2 active · 1 excluded". */
internal fun tagsSummary(filter: Filter): String? {
    val active = filter.activeTags.size + filter.pkgPrefixes.size
    val excluded = filter.excludeTags.size + filter.excludePkgPrefixes.size
    return joined(active.takeIf { it > 0 }?.let { "$it active" }, excluded.takeIf { it > 0 }?.let { "$it excluded" })
}

/** The enabled Tags-mode message rules: "1 excluded", "2 included", "1 excluded · 2 included". */
internal fun messageRulesSummary(filter: Filter): String? {
    val live = filter.messageRules.filter { it.enabled && it.mode == FilterMode.TAGS }
    val excluded = live.count { !it.include }
    val included = live.count { it.include }
    return joined(excluded.takeIf { it > 0 }?.let { "$it excluded" }, included.takeIf { it > 0 }?.let { "$it included" })
}

/** The highlighters that are switched on: "2 active". */
internal fun highlightersSummary(filter: Filter): String? =
    filter.highlighters.count { it.on }.takeIf { it > 0 }?.let { "$it active" }

/** Null while every level shows; "V hidden", "V, D hidden", or, once more than three are hidden, "only E, A" / "none shown". */
internal fun logLevelSummary(filter: Filter): String? {
    val hidden = LogLevel.entries.filter { it !in filter.levels }
    return when {
        hidden.isEmpty() -> null
        filter.levels.isEmpty() -> "none shown"
        hidden.size > MAX_LEVELS_LISTED -> "only " + LogLevel.entries.filter { it in filter.levels }.joinToString(", ") { it.key.toString() }
        else -> hidden.joinToString(", ") { it.key.toString() } + " hidden"
    }
}

/**
 * Enabled sequence definitions and enabled manual ranges: "2", "2 · 1 range", "1 range"; "off" when sequences exist but
 * sequence grouping is switched off.
 */
internal fun sequencesSummary(filter: Filter, manualBlocks: List<ManualCollapseBlock>): String? {
    val defs = filter.sequences.count { it.enabled }
    val ranges = manualBlocks.count { it.enabled }
    if (!filter.seqOn && defs > 0) return joined("off", ranges.takeIf { it > 0 }?.let { plural(it, "range") })
    return joined(defs.takeIf { it > 0 }?.toString(), ranges.takeIf { it > 0 }?.let { plural(it, "range") })
}

/**
 * The issues found in the log, grouped the way the Issues panel groups them: "1 ANR", "2 crashes", "1 crash · 1 ANR".
 * Native crashes and fatal exceptions count as crashes, merely logged exceptions as exceptions, user rules as custom.
 * Null while the analysis is still running or found nothing.
 */
internal fun issuesSummary(analysis: LogAnalysis): String? {
    if (analysis.pending) return null
    val groups = groupIssueSites(analysis.crashSites + analysis.customIssueSites)
    val sites = groups.map { it.representative }
    val crashes = sites.count { it is CrashSite && (it.kind == CrashKind.NATIVE_CRASH || (it.kind == CrashKind.EXCEPTION && it.isFatal)) }
    val anrs = sites.count { it is CrashSite && it.kind == CrashKind.ANR }
    val exceptions = sites.count { it is CrashSite && it.kind == CrashKind.EXCEPTION && !it.isFatal }
    val custom = sites.count { it !is CrashSite }
    return joined(
        crashes.takeIf { it > 0 }?.let { plural(it, "crash", "crashes") },
        anrs.takeIf { it > 0 }?.let { plural(it, "ANR") },
        exceptions.takeIf { it > 0 }?.let { plural(it, "exception") },
        custom.takeIf { it > 0 }?.let { "$it custom" },
    )
}

/** How many saved filters exist: "3 saved". */
internal fun savedFiltersSummary(count: Int): String? = count.takeIf { it > 0 }?.let { "$it saved" }

/** The note blocks that have text: "1 note", "2 notes". */
internal fun notesSummary(annotations: Annotations): String? =
    annotations.blocks.count { it is AnnBlock.Note && it.text.isNotBlank() }.takeIf { it > 0 }?.let { plural(it, "note") }

/** The unfiltered log's size: "1999 lines" (no thousands separator). */
internal fun originalSummary(totalLines: Int): String? = totalLines.takeIf { it > 0 }?.let { plural(it, "line") }
