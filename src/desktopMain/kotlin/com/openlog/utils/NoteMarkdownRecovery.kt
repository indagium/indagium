package com.openlog.utils

import com.openlog.model.AnnBlock
import com.openlog.model.LogEntry
import com.openlog.model.LogLevel

// Four leading spaces, matching appendLogRefBlock's AnnotationLogBlockStyle.INDENTED row prefix
// ("    ${r.ts}  ${r.level.key}/${r.tag}  ${r.msg}" — Filter.kt).
private const val ROW_INDENT = "    "
private const val JIRA_CODE_OPEN = "{code:java}"
private const val JIRA_CODE_CLOSE = "{code}"

// A contiguous run of raw row lines found in the .md, in document order, before any attempt is
// made to parse them into LogEntry values. Lines are already de-indented (INDENTED style) or
// already unindented (JIRA_JAVA style, taken straight from between the fences) — see
// extractCandidateRowBlocks.
private class CandidateRowBlock(val lines: List<String>)

/**
 * Recovers the log rows for [blocks] (a tab's LogRef blocks, in document order) by re-parsing the
 * plain-text .md this note was exported alongside. This is a fallback of last resort: it exists
 * only for .ann sidecars saved before Annotations.materializeLogRefs (AppState.kt) started baking
 * sourceEntries in, where the note's own LogRef.sourceEntries is null and the loading tab has no
 * log attached (or a different one) to resolve logIds against via rmap.
 *
 * This inverts appendLogRefBlock/buildMd (Filter.kt:737-758) — read that function first, it is
 * the exact format being parsed back here. Two on-disk shapes are accepted regardless of the
 * caller's current AnnotationLogBlockStyle setting, since the note may have been written under
 * the other one:
 *  - INDENTED: each row is `    <ts>  <LVL>/<tag>  <msg>` (four leading spaces).
 *  - JIRA_JAVA: rows sit unindented between a lone `{code:java}` line and a lone `{code}` line.
 *
 * Matching is strictly positional and all-or-nothing at the block level: the .md's log blocks are
 * extracted in document order and zipped index-for-index against [blocks]. A block-count mismatch
 * (a hand-edited note, or blocks added/removed since the .md was written) means the document isn't
 * trustworthy enough to align at all — this returns an empty map rather than guess. Within a
 * matched pair, the recovered row count must equal that block's logIds.size or the pair is simply
 * dropped (not the whole map): a multi-line msg embeds a raw newline when written, so its
 * continuation line lands unindented and ends the INDENTED run early — undercounting that block's
 * rows is the fail-safe outcome, not a misaligned entry N holding row N+1's text.
 *
 * pid/tid are left at their LogEntry defaults (0): the .md never recorded them, so nothing here is
 * invented.
 */
fun recoverLogRefRows(markdown: String, blocks: List<AnnBlock.LogRef>): Map<String, List<LogEntry>> {
    val candidates = extractCandidateRowBlocks(markdown)
    if (candidates.size != blocks.size) return emptyMap()
    val result = mutableMapOf<String, List<LogEntry>>()
    blocks.zip(candidates).forEach { (block, candidate) ->
        val rows = candidate.lines.mapIndexedNotNull { i, line -> parseRowLine(line, block.logIds.getOrNull(i)) }
        if (rows.size == block.logIds.size) result[block.id] = rows
    }
    return result
}

// Single linear pass over the document's lines, picking out every JIRA_JAVA fenced block and
// every maximal run of four-space-indented lines, in the order they appear. Everything else
// (captions, "From <file>" lines, blank lines, plain Note blocks) is skipped — it isn't row data
// under either style, and a Note block that happens to start with four spaces just produces a
// spurious candidate, which recoverLogRefRows already handles safely via the block-count check.
private fun extractCandidateRowBlocks(markdown: String): List<CandidateRowBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<CandidateRowBlock>()
    var i = 0
    while (i < lines.size) {
        when {
            lines[i] == JIRA_CODE_OPEN -> {
                val close = ((i + 1) until lines.size).firstOrNull { lines[it] == JIRA_CODE_CLOSE } ?: lines.size
                blocks += CandidateRowBlock(lines.subList(i + 1, close))
                i = close + 1
            }
            lines[i].startsWith(ROW_INDENT) -> {
                var end = i
                while (end < lines.size && lines[end].startsWith(ROW_INDENT)) end++
                blocks += CandidateRowBlock(lines.subList(i, end).map { it.removePrefix(ROW_INDENT) })
                i = end
            }
            else -> i++
        }
    }
    return blocks
}

// Inverts "${ts}  ${level.key}/${tag}  ${msg}". ts is guaranteed space-free (LogParser always
// strips/derives it that way), so the FIRST "  " in the line is unambiguously the ts/level
// boundary; the level char is exactly one character wide, so a slash not immediately after it
// (index != 1) means this line isn't shaped like a row at all. id is looked up by the caller from
// the owning block's logIds by position — a null here (more raw lines than logIds) fails the line
// rather than inventing an id.
private fun parseRowLine(line: String, id: Int?): LogEntry? {
    if (id == null) return null
    val tsEnd = line.indexOf("  ")
    if (tsEnd < 0) return null
    val ts = line.substring(0, tsEnd)
    val afterTs = line.substring(tsEnd + 2)
    val slashIdx = afterTs.indexOf('/')
    if (slashIdx != 1) return null
    val levelChar = afterTs[0]
    val afterSlash = afterTs.substring(slashIdx + 1)
    val tagEnd = afterSlash.indexOf("  ")
    if (tagEnd < 0) return null
    val tag = afterSlash.substring(0, tagEnd)
    val msg = afterSlash.substring(tagEnd + 2)
    return LogEntry(id = id, ts = ts, level = LogLevel.from(levelChar), tag = tag, msg = msg)
}
