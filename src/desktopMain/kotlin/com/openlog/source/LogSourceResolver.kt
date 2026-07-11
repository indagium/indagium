package com.openlog.source

// Sites whose matcher literal content is shorter than this are "generic" (e.g. "done", "start")
// — on their own they're too likely to coincidentally match unrelated log lines, so a specific
// (non-generic) match always outranks them, and if only generic matches exist their confidence
// is capped low rather than trusted at face value. See resolve() step 4.
const val GENERIC_LITERAL_THRESHOLD = 8

private const val CONFIDENCE_LITERAL_DIVISOR = 40.0
private const val TAG_MATCH_BOOST = 0.2
private const val GENERIC_CONFIDENCE_CAP = 0.3
private const val MAX_CONFIDENCE = 1.0

// Log parser's marker tag for lines it couldn't parse into a structured entry (see
// utils/LogParser.kt) — queried the same as a genuinely absent tag: search every bucket.
private const val RAW_TAG = "RAW"

/** Cheap indexed lookup from a log line's (tag, message) back to the source call site(s) that
 *  could have emitted it. Sites are bucketed by tag at construction time and each site's regex
 *  matcher is compiled once, so [resolve] is safe to call per-visible-row. */
class LogSourceResolver(index: SourceIndex) {
    private data class CompiledSite(val site: LogCallSite, val regex: Regex)

    private val byTag: Map<String?, List<CompiledSite>> = index.sites
        .mapNotNull { site -> runCatching { CompiledSite(site, Regex(site.matcher)) }.getOrNull() }
        .groupBy { it.site.tag }

    fun resolve(tag: String?, msg: String, limit: Int = 10): List<SourceMatch> {
        val normalizedTag = tag?.takeIf { it.isNotBlank() && it != RAW_TAG }
        val candidates = if (normalizedTag == null) {
            byTag.values.flatten()
        } else {
            byTag[normalizedTag].orEmpty() + byTag[null].orEmpty()
        }
        val matched = candidates.filter { it.regex.containsMatchIn(msg) }
        if (matched.isEmpty()) return emptyList()

        // A known tag is a hard discriminator. Keep every occurrence under that tag, but never
        // surface same-message calls from other classes with a different tag. Null-tag sites are
        // only a fallback for code whose tag could not be indexed.
        val exactTagMatched = if (normalizedTag != null) matched.filter { it.site.tag == normalizedTag } else emptyList()
        val narrowed = exactTagMatched.ifEmpty { matched }
        val scored = narrowed.map { c -> c to score(c.site, normalizedTag) }
        val hasSpecific = scored.any { (c, _) -> c.site.literalLen >= GENERIC_LITERAL_THRESHOLD }
        val kept = if (hasSpecific) scored.filter { (c, _) -> c.site.literalLen >= GENERIC_LITERAL_THRESHOLD } else scored

        return kept
            .map { (c, raw) ->
                val generic = c.site.literalLen < GENERIC_LITERAL_THRESHOLD
                SourceMatch(c.site, if (generic) raw.coerceAtMost(GENERIC_CONFIDENCE_CAP) else raw)
            }
            .sortedWith(
                compareByDescending<SourceMatch> { it.confidence }
                    .thenByDescending { it.site.literalLen }
                    .thenBy { it.site.filePath }
                    .thenBy { it.site.callLine },
            )
            .take(limit)
    }

    private fun score(site: LogCallSite, queryTag: String?): Double {
        val base = (site.literalLen.toDouble() / CONFIDENCE_LITERAL_DIVISOR).coerceAtMost(MAX_CONFIDENCE)
        val tagBoost = if (queryTag != null && site.tag == queryTag) TAG_MATCH_BOOST else 0.0
        return (base + tagBoost).coerceAtMost(MAX_CONFIDENCE)
    }
}
