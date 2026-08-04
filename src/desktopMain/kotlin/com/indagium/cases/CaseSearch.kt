package com.indagium.cases

import java.io.File
import kotlin.math.abs
import kotlin.math.ln

private const val DEFAULT_SEARCH_LIMIT = 8
private const val MAX_SEARCH_LIMIT = 20
private const val SNIPPET_LEN = 220
private const val TAG_MATCH_WEIGHT = 2.0
private const val STALE_VERSION_PENALTY = 0.3

// Tiered match weights (Task: "Search matching is too literal") — multiplied onto the existing
// idf-lite per-token contribution so an exact hit always outranks a prefix hit, which always
// outranks a fuzzy (typo-tolerant) one, regardless of how rare either vocabulary token is.
private const val EXACT_MATCH_WEIGHT = 1.0
private const val PREFIX_MATCH_WEIGHT = 0.6
private const val FUZZY_MATCH_WEIGHT = 0.35

// A query token shorter than this never auto-prefix-matches (avoids "de" matching half the
// corpus) — an explicit trailing '*' (explicitWildcardTokens) bypasses this floor since the user
// asked for prefix matching outright.
private const val MIN_PREFIX_QUERY_LEN = 3

// Bounds on how much of the token vocabulary a single query token may pull in via prefix/fuzzy
// scanning — exact lookup is O(1) via byToken and needs none of this; these only apply once exact
// lookup has already come up empty for that token (see matchVocabulary).
private const val MAX_PREFIX_MATCHES_PER_TOKEN = 50
private const val MAX_FUZZY_MATCHES_PER_TOKEN = 8

// Extracts words in the raw query with an explicit trailing '*' — e.g. "deviceman*" — before
// tokenize() ever sees the text and silently eats the '*' as an ordinary separator (its
// TOKEN_SPLIT_RE treats any run of non-alphanumerics, including '*', as a token boundary). Each
// captured word is lowercased to match tokenize()'s own normalization, so it lines up with the
// plain token tokenize(query) already produces for the same word (star stripped) — this set only
// marks that token as an intentional prefix query, bypassing MIN_PREFIX_QUERY_LEN.
private val EXPLICIT_WILDCARD_RE = Regex("([A-Za-z0-9]+)\\*")

private fun explicitWildcardTokens(query: String): Set<String> =
    EXPLICIT_WILDCARD_RE.findAll(query)
        .mapNotNull { it.groupValues[1].lowercase().takeIf(String::isNotEmpty) }
        .toSet()

// Distance budget for bounded/typo-tolerant matching, keyed off the QUERY token's length — no
// fuzzy tier at all below length 4 (too easy to accidentally match unrelated short words), a
// single edit for medium-length tokens, two for long ones. Returns null to mean "don't fuzzy
// match this token at all".
private fun fuzzyDistanceBudget(queryTokenLen: Int): Int? = when {
    queryTokenLen < 4 -> null
    queryTokenLen <= 7 -> 1
    else -> 2
}

// OSA (optimal-string-alignment) Damerau-Levenshtein: insert/delete/substitute plus a single
// adjacent-character transposition as one edit (the single most common typo shape — "gr" typed
// for "rg" — costs 1 here instead of 2 under plain Levenshtein). Banded to a width of
// 2*maxDist+1 around the diagonal, with an early exit the moment an entire row's minimum already
// exceeds maxDist. Callers only ever reach this after a cheap length-difference and
// first-character prefilter (see matchVocabulary), and maxDist is always <=2 (fuzzyDistanceBudget),
// so this never approaches computing a full O(len(a)*len(b)) matrix per candidate pair.
private fun withinEditDistance(query: String, candidate: String, maxDist: Int): Boolean {
    if (query == candidate) return true
    val la = query.length
    val lb = candidate.length
    if (abs(la - lb) > maxDist) return false
    val unreachable = maxDist + 1
    // Row i-2 (for the transposition lookback) and row i-1, both banded the same as the row being
    // computed.
    var twoBack = IntArray(lb + 1) { unreachable }
    var prev = IntArray(lb + 1) { j -> if (j <= maxDist) j else unreachable }
    for (i in 1..la) {
        val cur = IntArray(lb + 1) { unreachable }
        val lo = maxOf(1, i - maxDist)
        val hi = minOf(lb, i + maxDist)
        if (i <= maxDist) cur[0] = i
        var rowMin = cur[0]
        for (j in lo..hi) {
            val cost = if (query[i - 1] == candidate[j - 1]) 0 else 1
            // delete from query, insert into query, substitute (or match).
            var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            if (i >= 2 && j >= 2 && query[i - 1] == candidate[j - 2] && query[i - 2] == candidate[j - 1]) {
                v = minOf(v, twoBack[j - 2] + 1) // adjacent transposition
            }
            cur[j] = v
            if (v < rowMin) rowMin = v
        }
        if (rowMin > maxDist) return false // whole row already out of budget — bail early
        twoBack = prev
        prev = cur
    }
    return prev[lb] <= maxDist
}

/** Matches [queryTokens] against the token [vocabulary], tiered exact > prefix > fuzzy, returning
 *  the best (highest) tier weight earned by each matched vocabulary token — a vocabulary token
 *  matched by more than one query token (or matchable at more than one tier) counts once, at its
 *  best tier, so [CaseSearch.score] never double-counts it. Prefix/fuzzy scanning only runs for a
 *  query token once the cheap exact lookup has already come up empty for it, and fuzzy only once
 *  prefix has too — see the file-level doc comment on why this stays bounded even though
 *  [vocabulary] can grow with the notes corpus. */
private fun matchVocabulary(queryTokens: Set<String>, wildcardTokens: Set<String>, vocabulary: Set<String>): Map<String, Double> {
    val weights = HashMap<String, Double>()

    fun consider(vocabToken: String, weight: Double) {
        val current = weights[vocabToken]
        if (current == null || weight > current) weights[vocabToken] = weight
    }
    queryTokens.forEach { q -> matchOneQueryToken(q, wildcardTokens, vocabulary, ::consider) }
    return weights
}

// Exact -> prefix -> fuzzy for a single query token, stopping at the first tier that finds
// anything (see matchVocabulary's doc comment for why: prefix/fuzzy scanning is only worth its
// cost once a cheaper tier has already come up empty).
private fun matchOneQueryToken(q: String, wildcardTokens: Set<String>, vocabulary: Set<String>, consider: (String, Double) -> Unit) {
    if (q in vocabulary) {
        consider(q, EXACT_MATCH_WEIGHT)
        return
    }
    val prefixHits = prefixMatchesFor(q, wildcardTokens, vocabulary)
    if (prefixHits.isNotEmpty()) {
        prefixHits.forEach { consider(it, PREFIX_MATCH_WEIGHT) }
        return
    }
    fuzzyMatchesFor(q, vocabulary).forEach { consider(it, FUZZY_MATCH_WEIGHT) }
}

// Vocabulary tokens q is a prefix of, capped at MAX_PREFIX_MATCHES_PER_TOKEN — never scanned at
// all for a query token below MIN_PREFIX_QUERY_LEN unless the user opted in via an explicit '*'.
private fun prefixMatchesFor(q: String, wildcardTokens: Set<String>, vocabulary: Set<String>): List<String> {
    if (q.length < MIN_PREFIX_QUERY_LEN && q !in wildcardTokens) return emptyList()
    return vocabulary.asSequence().filter { it.startsWith(q) }.take(MAX_PREFIX_MATCHES_PER_TOKEN).toList()
}

// Vocabulary tokens within fuzzyDistanceBudget(q.length) of q, capped at
// MAX_FUZZY_MATCHES_PER_TOKEN. The length-difference/first-character filter runs before the real
// (still bounded) distance computation: a candidate already out of budget on either count can
// never be within maxDist under any alignment worth computing for.
private fun fuzzyMatchesFor(q: String, vocabulary: Set<String>): List<String> {
    val maxDist = fuzzyDistanceBudget(q.length) ?: return emptyList()
    return vocabulary.asSequence()
        .filter { it.isNotEmpty() && it[0] == q[0] && abs(it.length - q.length) <= maxDist }
        .filter { withinEditDistance(q, it, maxDist) }
        .take(MAX_FUZZY_MATCHES_PER_TOKEN)
        .toList()
}

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
 * picked up on the very next search — mirrors [com.indagium.source.LogSourceResolver]'s
 * bucket-narrow-then-score shape, and [com.indagium.source.SourceIndexer]/
 * [com.indagium.source.SourceIndexStore]'s persist/incrementally-refresh split.
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
        val wildcardTokens = explicitWildcardTokens(query)
        val queryTags = tags.map { it.lowercase() }.filter { it.isNotBlank() }.toSet()
        if (queryTokens.isEmpty() && queryTags.isEmpty()) return emptyList()

        // Exact/prefix/fuzzy match against the token vocabulary — see matchVocabulary's doc
        // comment. tokenMatches maps a VOCABULARY token (not necessarily a literal query token) to
        // the best tier weight it earned; score() below uses it to weight each record's own tokens.
        val tokenMatches = matchVocabulary(queryTokens, wildcardTokens, byToken.keys)

        val candidateIds = HashSet<String>()
        queryTags.forEach { t -> byTag[t]?.let(candidateIds::addAll) }
        tokenMatches.keys.forEach { t -> byToken[t]?.let(candidateIds::addAll) }
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
                CaseMatch(record, score(record, tokenMatches, queryTags, newestAppVersion), matchedTags)
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

    /** True when the corpus has no indexed records at all, after an up-to-date rescan — distinct
     *  from [search] returning no matches for one particular query/tag combination. The Case
     *  Library UI uses this to decide between an "Index my notes" prompt (nothing has ever been
     *  indexed, or every previously-indexed note is gone) and an ordinary "no matches" message. */
    fun isEmpty(): Boolean {
        refresh()
        return recordsById.isEmpty()
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

    // [tokenMatches] maps a vocabulary token this query matched to its best tier weight (1.0
    // exact / 0.6 prefix / 0.35 fuzzy — see matchVocabulary). Multiplying it onto the existing
    // idf-lite contribution is what keeps scoring tiered: exact matches always outscore prefix
    // matches of the same rarity, which always outscore fuzzy ones.
    private fun score(record: CaseRecord, tokenMatches: Map<String, Double>, queryTags: Set<String>, newestAppVersion: String?): Double {
        val tokenScore = record.tokens.asSequence()
            .mapNotNull { tok -> tokenMatches[tok]?.let { weight -> tok to weight } }
            .sumOf { (tok, weight) ->
                // idf-lite: a token shared by fewer notes is worth more than a common one.
                val df = (byToken[tok]?.size ?: 1).coerceAtLeast(1)
                weight * (1.0 / (1.0 + ln(1.0 + df)))
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
