package com.openlog.cases

import com.openlog.model.AnnBlock
import com.openlog.model.Annotations

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
        }
    }
    if (annotations.suffix.isNotBlank()) appendLine(annotations.suffix)
}.trim()

private val TOKEN_SPLIT_RE = Regex("[^A-Za-z0-9]+")
private const val MIN_TOKEN_LEN = 2

/** Shared normalization for both indexed note text and incoming search queries — lowercase,
 *  split on runs of non-alphanumeric characters, drop very short (noise) tokens. Deliberately
 *  simple (no stemming/stopwords): the corpus this feature targets is small, and the inverted
 *  index + tag boost in [CaseSearch] already do the heavy lifting for relevance. */
fun tokenize(text: String): Set<String> =
    TOKEN_SPLIT_RE.split(text.lowercase())
        .asSequence()
        .filter { it.length >= MIN_TOKEN_LEN }
        .toSet()
