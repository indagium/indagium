package com.openlog.cases

import java.io.File
import kotlin.math.ln

private const val DEFAULT_SEARCH_LIMIT = 8
private const val MAX_SEARCH_LIMIT = 20
private const val SNIPPET_LEN = 220
private const val TAG_MATCH_WEIGHT = 2.0
private const val STALE_VERSION_PENALTY = 0.3

// Splits a "1.5.2"/"2.0.0-beta"-style version string into comparable segments. Best-effort only
// (this whole feature only uses it to down-weight, never to hard-filter, so a version string that
// doesn't fit the common dotted-numeric shape simply falls back to a lexicographic comparison of
// that segment rather than failing).
private fun compareVersions(a: String, b: String): Int {
    val segA = a.split('.', '-', '+')
    val segB = b.split('.', '-', '+')
    val n = maxOf(segA.size, segB.size)
    for (i in 0 until n) {
        val sa = segA.getOrNull(i).orEmpty()
        val sb = segB.getOrNull(i).orEmpty()
        val na = sa.toIntOrNull()
        val nb = sb.toIntOrNull()
        val cmp = if (na != null && nb != null) na.compareTo(nb) else sa.compareTo(sb)
        if (cmp != 0) return cmp
    }
    return 0
}

/**
 * In-memory ranked search over a [CaseIndex], with a cheap per-call auto-rescan so notes added,
 * edited, or removed on disk without going through the app (drag/copy-paste of an `.ann`) are
 * picked up on the very next search — mirrors [com.openlog.source.LogSourceResolver]'s
 * bucket-narrow-then-score shape, and [com.openlog.source.SourceIndexer]/
 * [com.openlog.source.SourceIndexStore]'s persist/incrementally-refresh split.
 *
 * [noteDirs] is a supplier (not a fixed list) so a change to Settings → default save dir is
 * picked up on the next search without reconstructing this class.
 */
class CaseSearch(
    private val noteDirs: () -> List<File>,
    private val indexFile: File,
) {
    private val lock = Any()
    private var cached: CaseIndex? = null
    private var recordsById: Map<String, CaseRecord> = emptyMap()
    private var byTag: Map<String, Set<String>> = emptyMap()
    private var byToken: Map<String, Set<String>> = emptyMap()

    /** Ranked, compact results for [query] (+ optional [tags] to boost). Never scans every
     *  indexed note: candidates come only from the union of the tag/token posting lists, and only
     *  those candidates are scored. */
    fun search(query: String, tags: List<String> = emptyList(), excludeSourcePath: String? = null, limit: Int = DEFAULT_SEARCH_LIMIT): List<CaseSummary> {
        refresh()
        val queryTokens = tokenize(query)
        val queryTags = tags.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
        if (queryTokens.isEmpty() && queryTags.isEmpty()) return emptyList()

        val candidateIds = HashSet<String>()
        queryTags.forEach { t -> byTag[t]?.let(candidateIds::addAll) }
        queryTokens.forEach { t -> byToken[t]?.let(candidateIds::addAll) }
        if (candidateIds.isEmpty()) return emptyList()

        val newestAppVersion = recordsById.values
            .mapNotNull { it.appVersion.takeIf(String::isNotBlank) }
            .maxWithOrNull(::compareVersions)
        val cappedLimit = limit.coerceIn(1, MAX_SEARCH_LIMIT)

        return candidateIds.asSequence()
            .mapNotNull { id -> recordsById[id] }
            .filter { excludeSourcePath.isNullOrBlank() || it.sourcePath != excludeSourcePath }
            .map { record ->
                val matchedTags = record.tags.filter { it.lowercase() in queryTags }
                CaseMatch(record, score(record, queryTokens, queryTags, newestAppVersion), matchedTags)
            }
            .filter { it.score > 0.0 }
            .sortedWith(compareByDescending<CaseMatch> { it.score }.thenBy { it.record.title })
            .take(cappedLimit)
            .map { it.toSummary() }
            .toList()
    }

    /** Full record for a get_case lookup, or null when [id] doesn't (or no longer) resolve. */
    fun getCase(id: String): CaseRecord? {
        refresh()
        return recordsById[id]
    }

    /** Escape hatch: ignores the persisted/cached index entirely and rebuilds from disk. */
    fun reindexAll() {
        synchronized(lock) {
            val fresh = CaseIndexer.build(noteDirs())
            CaseIndexStore.save(fresh, indexFile)
            publish(fresh)
        }
    }

    private fun refresh() {
        synchronized(lock) {
            val base = cached ?: CaseIndexStore.load(indexFile)
                ?: CaseIndex(CASE_INDEX_VERSION, emptyList(), emptyMap(), 0L)
            val rescanned = rescan(base)
            if (rescanned !== base) CaseIndexStore.save(rescanned, indexFile)
            publish(rescanned)
        }
    }

    // Diffs the current on-disk note listing against [base]: added/changed base names are
    // (re)parsed one at a time via CaseIndexer.buildRecord; removed ones are dropped; anything
    // whose backing file's mtime/size hasn't moved is kept as-is with no re-parse. This is what
    // keeps a search fast even as the notes corpus grows — only the delta since the last search
    // is ever touched, never the whole corpus.
    private fun rescan(base: CaseIndex): CaseIndex {
        val dirs = noteDirs().filter { it.exists() && it.isDirectory }.distinctBy { it.absolutePath }
        val recordsById = base.records.associateBy { it.id }.toMutableMap()
        val fileMeta = base.fileMeta.toMutableMap()
        val currentIds = HashSet<String>()
        var changed = false

        dirs.forEach { dir ->
            CaseIndexer.enumerateBaseNames(dir).forEach { baseName ->
                val backing = CaseIndexer.backingFileFor(dir, baseName) ?: return@forEach
                val id = File(dir, "$baseName.md").absolutePath
                currentIds += id
                val backingPath = backing.absolutePath
                val currentMeta = CaseFileMeta(backing.lastModified(), backing.length())
                val existing = recordsById[id]
                val persistedMeta = fileMeta[backingPath]
                val stale = existing == null || existing.backingPath != backingPath ||
                    persistedMeta == null || persistedMeta != currentMeta
                if (!stale) return@forEach

                if (existing != null && existing.backingPath != backingPath) fileMeta.remove(existing.backingPath)
                val built = runCatching { CaseIndexer.buildRecord(dir, baseName) }.getOrNull()
                if (built != null) {
                    recordsById[id] = built.first
                    fileMeta[backingPath] = built.second
                } else {
                    recordsById.remove(id)
                    fileMeta.remove(backingPath)
                }
                changed = true
            }
        }

        val removedIds = recordsById.keys - currentIds
        if (removedIds.isNotEmpty()) {
            removedIds.forEach { id -> recordsById.remove(id)?.let { fileMeta.remove(it.backingPath) } }
            changed = true
        }

        return if (!changed) base else CaseIndex(
            version = CASE_INDEX_VERSION,
            records = recordsById.values.toList(),
            fileMeta = fileMeta,
            builtAt = System.currentTimeMillis(),
        )
    }

    private fun publish(index: CaseIndex) {
        cached = index
        recordsById = index.records.associateBy { it.id }
        val tagMap = HashMap<String, MutableSet<String>>()
        val tokenMap = HashMap<String, MutableSet<String>>()
        index.records.forEach { r ->
            r.tags.forEach { t -> tagMap.getOrPut(t.lowercase()) { mutableSetOf() }.add(r.id) }
            r.tokens.forEach { tok -> tokenMap.getOrPut(tok) { mutableSetOf() }.add(r.id) }
        }
        byTag = tagMap
        byToken = tokenMap
    }

    private fun score(record: CaseRecord, queryTokens: Set<String>, queryTags: Set<String>, newestAppVersion: String?): Double {
        val tokenScore = record.tokens.asSequence()
            .filter { it in queryTokens }
            .sumOf { tok ->
                // idf-lite: a token shared by fewer notes is worth more than a common one.
                val df = (byToken[tok]?.size ?: 1).coerceAtLeast(1)
                1.0 / (1.0 + ln(1.0 + df))
            }
        val tagOverlap = record.tags.count { it.lowercase() in queryTags }
        var total = tokenScore + tagOverlap * TAG_MATCH_WEIGHT
        val isStale = record.appVersion.isNotBlank() && newestAppVersion != null &&
            compareVersions(record.appVersion, newestAppVersion) < 0
        if (isStale) total *= (1.0 - STALE_VERSION_PENALTY)
        return total
    }

    private fun CaseMatch.toSummary(): CaseSummary = CaseSummary(
        id = record.id,
        title = record.title,
        descriptionSnippet = record.issueDescription.take(SNIPPET_LEN)
            .let { if (record.issueDescription.length > SNIPPET_LEN) "$it…" else it },
        matchedTags = matchedTags,
        score = score,
        appVersion = record.appVersion,
    )
}
