package com.openlog.cases

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations
import com.openlog.model.Filter
import com.openlog.model.LogLevel

/** Bumped whenever [CaseRecord]'s shape changes in a way that makes a previously-persisted
 *  [CaseIndex] stale — mirrors [com.openlog.source.SOURCE_INDEX_VERSION]'s role for the source
 *  index. A version mismatch on load means "no usable index", triggering a full rebuild rather
 *  than a partial/garbled restore. */
const val CASE_INDEX_VERSION = 1

/** Snapshot of the file whose on-disk state a [CaseRecord] was last parsed from, used to detect
 *  staleness without re-reading/re-parsing every note on every search (mirrors
 *  [com.openlog.source.FileMeta]). */
data class CaseFileMeta(val mtime: Long, val size: Long)

/**
 * One indexed "case" — a past saved analysis note (`<base>_analysis.md` + optional `.ann`
 * sidecar) under one of the note lookup directories. Corpus = existing notes, unchanged; this is
 * a read-only view built by [CaseIndexer] from files the app (or a human) already wrote.
 *
 * [id] is the absolute path of the note's `.md` file — used as a stable key even when no `.md`
 * actually exists yet (a hand-copied lone `.ann`), since the dir+baseName naming convention is
 * itself stable across rescans.
 */
data class CaseRecord(
    val id: String,
    val title: String,
    val issueDescription: String,
    val sourcePath: String?,
    // Best-effort app/build version recorded on the note (Annotations.appVersion, `.ann` field
    // index 5) — empty when never detected/set. See com.openlog.utils.extractAppVersionHeuristic.
    val appVersion: String,
    // Tags/filters explicitly marked decisive for this issue's root cause (Annotations
    // .decisiveTags, `.ann` field index 6), written via the set_case_metadata MCP tool.
    val decisiveTags: List<String>,
    // Union of decisiveTags + every tag harvested from this note's LogRef blocks' sourceEntries —
    // the full set of tags CaseSearch buckets this record under.
    val tags: Set<String>,
    // Normalized search terms from title + issueDescription + note text + referenced-line
    // messages — see CaseIndexer.tokenize.
    val tokens: Set<String>,
    val mdPath: String?,
    val annPath: String?,
    // The single file (.ann preferred, else .md) whose mtime/size drives staleness detection —
    // matches CaseIndex.fileMeta's key for this record.
    val backingPath: String,
    // Human-readable rendering (see describeFilter) of the Filter active when this note was saved
    // — Annotations.annotationsToken's field index 8, decoded via ui/AutosaveCodec.kt's
    // filterFromAnnotationsToken (never through Annotations itself; mirrors sourcePath's own
    // field-4 handling). Null means "no filter recorded" (absent field, or a note written before
    // this field existed) — the Case Library preview must show that explicitly rather than a blank
    // or a fabricated summary. Distinct from describeFilter's own "No filter constraints" string,
    // which means a filter WAS recorded and it simply has no active constraints.
    val filterSummary: String? = null,
    // Annotations.fingerprint, straight off the parsed .ann (`.ann` field index 9) — the content
    // fingerprint of the log this note was saved against (utils/LogFingerprint.kt). Drives the
    // Case Library preview's "Locate log…" verify-before-attach flow (AppState.locateLogForCase):
    // null means "no fingerprint recorded" (a note saved before this existed), reported as
    // unverifiable rather than a silent pass or a false mismatch.
    val fingerprint: String? = null,
)

data class CaseIndex(
    val version: Int,
    val records: List<CaseRecord>,
    // Keyed by CaseRecord.backingPath.
    val fileMeta: Map<String, CaseFileMeta>,
    val builtAt: Long,
)

/** A scored candidate from [CaseSearch.search]'s narrowed candidate set, before compaction into
 *  a token-cheap [CaseSummary] (mirrors [com.openlog.source.SourceMatch]). */
data class CaseMatch(
    val record: CaseRecord,
    val score: Double,
    val matchedTags: List<String> = emptyList(),
)

/** Compact, token-cheap result surfaced to the AI by search_similar_cases — full note text is
 *  only fetched afterward, via get_case, for the 1-3 matches actually worth reading. */
data class CaseSummary(
    val id: String,
    val title: String,
    val descriptionSnippet: String,
    val matchedTags: List<String>,
    val score: Double,
    val appVersion: String,
)

/** Reconstructs readable Markdown-ish text from an `.ann`-only note (no paired `.md`) — used both
 *  for tokenizing a hand-copied `.ann` during indexing and by get_case to return readable text
 *  when there is no `.md` to read verbatim. Mirrors buildMd()'s block order (prefix, blocks,
 *  suffix) but, unlike buildMd(), intentionally has no access to (and no need for) AppSettings
 *  formatting — this is for search/AI consumption, not export fidelity. */
fun reconstructAnnotationsText(annotations: Annotations): String = buildString {
    if (annotations.prefix.isNotBlank()) {
        appendLine(annotations.prefix)
        appendLine()
    }
    annotations.blocks.forEach { block ->
        when (block) {
            is AnnBlock.Note -> {
                appendLine(block.text)
                appendLine()
            }
            is AnnBlock.LogRef -> {
                if (block.caption.isNotBlank()) appendLine(block.caption)
                block.sourceEntries?.forEach { e -> appendLine("${e.ts} ${e.level.key}/${e.tag}: ${e.msg}") }
                appendLine()
            }
            is AnnBlock.Image -> {
                appendLine("[screenshot]")
                if (block.caption.isNotBlank()) appendLine(block.caption)
                block.displayProvenance?.let { appendLine(it) }
                appendLine()
            }
        }
    }
    if (annotations.suffix.isNotBlank()) appendLine(annotations.suffix)
}.trim()

private val TOKEN_SPLIT_RE = Regex("[^A-Za-z0-9]+")
private const val MIN_TOKEN_LEN = 2

// Splits one alphanumeric run on camelCase/acronym and letter<->digit boundaries — applied on the
// ORIGINAL (pre-lowercase) run, since case is exactly the signal these boundaries key off of:
//   "DeviceManager" -> "Device" | "Manager"        (lower/digit -> upper)
//   "HTTPServer"    -> "HTTP" | "Server"            (acronym run -> capitalized word)
//   "Log2Cache"     -> "Log" | "2" | "Cache"         (letter <-> digit)
// Each alternative is a zero-width lookaround, so String.split() drops nothing and inserts no
// characters — it only chooses where to cut.
private val SUBWORD_SPLIT_RE = Regex(
    "(?<=[a-z0-9])(?=[A-Z])" +
        "|(?<=[A-Z])(?=[A-Z][a-z])" +
        "|(?<=[A-Za-z])(?=[0-9])" +
        "|(?<=[0-9])(?=[A-Za-z])",
)

/** Shared normalization for both indexed note text and incoming search queries — lowercase, split
 *  on runs of non-alphanumeric characters, then further split each run on camelCase/digit
 *  boundaries. `"DeviceManager"` indexes as `"devicemanager"`, `"device"`, AND `"manager"` — the
 *  whole (lowercased) token is always kept alongside its sub-tokens, never replaced, so a query for
 *  either the compound word or either half still hits. Tokens shorter than [MIN_TOKEN_LEN] (noise)
 *  are dropped either way. Deliberately simple otherwise (no stemming/stopwords): the corpus this
 *  feature targets is small, and the inverted index + tiered exact/prefix/fuzzy matching + tag
 *  boost in [CaseSearch] already do the heavy lifting for relevance. Applying this same function to
 *  both indexed text and incoming queries is what makes a query like "device" (which alone never
 *  appears verbatim in a note that only ever writes "DeviceManager") still match. */
fun tokenize(text: String): Set<String> {
    val out = HashSet<String>()
    TOKEN_SPLIT_RE.split(text).forEach { run ->
        if (run.isEmpty()) return@forEach
        val whole = run.lowercase()
        if (whole.length >= MIN_TOKEN_LEN) out += whole
        if (run.length > MIN_TOKEN_LEN) {
            SUBWORD_SPLIT_RE.split(run).forEach { sub ->
                val s = sub.lowercase()
                if (s.length >= MIN_TOKEN_LEN) out += s
            }
        }
    }
    return out
}

/** Human-readable one-line summary of a [Filter] for the Case Library preview's metadata header —
 *  e.g. `"tag=DeviceManager, level≥W, 2 message rules"`. Pure/testable and display-only: CaseSearch
 *  never filters by this, it only exists so a saved case's preview can show what was actually being
 *  looked at when the note was written (see `Annotations.annotationsToken`'s field-8 doc comment in
 *  ui/AutosaveCodec.kt for how/where this gets recorded). Never returns a blank string — an empty
 *  [Filter] (every level, no tags/keywords/rules) reads as "No filter constraints", which callers
 *  must tell apart from a genuinely unrecorded filter (null) themselves. */
fun describeFilter(filter: Filter): String {
    val parts = mutableListOf<String>()
    if (filter.activeTags.isNotEmpty()) parts += "tag=" + filter.activeTags.sorted().joinToString(",")
    if (filter.excludeTags.isNotEmpty()) parts += "excl-tag=" + filter.excludeTags.sorted().joinToString(",")
    if (filter.levels.isNotEmpty() && filter.levels != LogLevel.entries.toSet()) {
        val minOrdinal = filter.levels.minOf { it.ordinal }
        val contiguousFromMin = LogLevel.entries.filter { it.ordinal >= minOrdinal }.toSet()
        parts += if (filter.levels == contiguousFromMin) {
            "level≥${LogLevel.entries[minOrdinal].key}"
        } else {
            "levels=" + filter.levels.sortedBy { it.ordinal }.joinToString(",") { it.key.toString() }
        }
    }
    if (filter.kwText.isNotBlank()) parts += "kw=\"${filter.kwText}\""
    if (filter.excludeKw.isNotBlank()) parts += "excl-kw=\"${filter.excludeKw}\""
    if (filter.messageRules.isNotEmpty()) {
        parts += "${filter.messageRules.size} message rule${if (filter.messageRules.size == 1) "" else "s"}"
    }
    if (filter.pidTidFilter.isNotBlank()) parts += "pid/tid=${filter.pidTidFilter}"
    if (filter.sequences.isNotEmpty()) parts += "${filter.sequences.size} sequence${if (filter.sequences.size == 1) "" else "s"}"
    return if (parts.isEmpty()) "No filter constraints" else parts.joinToString(", ")
}
