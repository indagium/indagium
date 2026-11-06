package com.indagium.storage

import com.indagium.model.SequenceDef
import com.indagium.utils.CancellationCheck
import com.indagium.utils.RegexEvaluationContext
import com.indagium.utils.SeqCandidate
import com.indagium.utils.assignParents
import com.indagium.utils.matchesSeqText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.BitSet
import java.util.Collections

/** Count limits, not a hard byte/RSS budget. Temporary array copies are separately allocated. */
internal data class StorageSequenceLimits(
    val maxRows: Int = 10_000_000,
    val maxDefinitions: Int = 1_024,
    val maxCandidates: Int = 1_000_000,
    val maxEndOccurrences: Int = 4_000_000,
    val maxMembership: Int = 20_000_000,
)

internal class StorageSequenceLimitExceeded(val resource: String) :
    StorageFailure("Sequence grouping exceeded the $resource limit")

/** Immutable primitive IDs. Returning an array makes a defensive copy. */
internal class StorageSequenceIds internal constructor(ids: IntArray) {
    private val ids = ids.copyOf()
    val size: Int get() = ids.size

    operator fun get(index: Int): Int = ids[index]

    fun toIntArray(): IntArray = ids.copyOf()
}

internal data class StorageNestedSequence(
    val gid: String,
    val rootId: EntryId,
    val members: StorageSequenceIds,
    val defId: String,
    val endExclusive: Int,
)

internal data class StorageSequenceGroup(
    val gid: String,
    val rootId: EntryId,
    val plain: StorageSequenceIds,
    val nested: List<StorageNestedSequence>,
    val defId: String,
    val endExclusive: Int,
)

internal data class StorageSequenceResult(
    val version: SourceVersion,
    val queryId: String,
    val regexTimedOut: Boolean,
    val groups: List<StorageSequenceGroup>,
)

/**
 * Experimental grouping over a completed *filtered* query. The regex context must be the same
 * operation-local context used during filtering to match computeItems timeout behavior; it must
 * not be shared with concurrent operations. No decoded row survives its page. Count limits bound
 * retained metadata and memberships, not object overhead or peak memory from copying arrays.
 * The caller must fence publication using the query ID and its own request/generation token.
 */
@Suppress("LongMethod", "CyclomaticComplexMethod")
internal suspend fun computeStorageSequenceGroups(
    query: StorageQuery,
    definitions: List<SequenceDef>,
    pageSize: Int,
    regexContext: RegexEvaluationContext,
    limits: StorageSequenceLimits = StorageSequenceLimits(),
): StorageSequenceResult = withContext(Dispatchers.Default) { StorageScanLimiter.withScan scan@{
    require(pageSize > 0) { "Page size must be positive" }
    require(
        limits.maxRows >= 0 && limits.maxDefinitions >= 0 && limits.maxCandidates >= 0 &&
            limits.maxEndOccurrences >= 0 && limits.maxMembership >= 0,
    ) { "Sequence limits must be nonnegative" }
    val context = currentCoroutineContext()
    context.ensureActive()
    query.validate()
    val enabled = definitions.filter { it.enabled }.sortedBy { it.priority }
    if (enabled.size > limits.maxDefinitions) throw StorageSequenceLimitExceeded("definitions")
    if (enabled.isEmpty()) {
        return@scan StorageSequenceResult(query.version, query.queryId, regexContext.hasTimedOut, emptyList())
    }

    val endDefs = enabled.filter { !it.endMatchText.isNullOrBlank() }
    val ids = SequenceIntBuffer()
    val tids = SequenceIntBuffer()
    val candidates = ArrayList<SeqCandidate>()
    val ends = HashMap<String, SequenceIntBuffer>()
    var endCount = 0
    var start = 0L
    while (true) {
        context.ensureActive()
        val page = query.rows(start, pageSize)
        check(page.version == query.version && page.start == start && page.items.size <= pageSize) {
            "Inconsistent query page"
        }
        for (row in page.items) {
            context.ensureActive()
            if (ids.size >= limits.maxRows || ids.size == Int.MAX_VALUE) throw StorageSequenceLimitExceeded("rows")
            val entry = row.entry
            val index = ids.size
            ids.add(entry.id)
            tids.add(entry.tid)
            val hay = "${entry.tag} ${entry.msg}"
            val first = enabled.firstOrNull {
                matchesSeqText(entry, hay, it.matchText, it.isRegex, it.tag, it.scopeTid, regexContext)
            }
            if (first != null) {
                if (candidates.size >= limits.maxCandidates) throw StorageSequenceLimitExceeded("candidates")
                candidates.add(SeqCandidate(index, first))
            }
            for (definition in endDefs) {
                val endText = definition.endMatchText ?: continue
                if (matchesSeqText(entry, hay, endText, definition.endIsRegex, definition.endTag, definition.scopeTid, regexContext)) {
                    if (endCount >= limits.maxEndOccurrences) throw StorageSequenceLimitExceeded("end occurrences")
                    ends.getOrPut(definition.id) { SequenceIntBuffer() }.add(index)
                    endCount++
                }
            }
        }
        start += page.items.size
        if (page.endReached) break
        check(page.items.isNotEmpty()) { "Query returned an empty nonterminal page" }
    }

    val sameDef = IntArray(candidates.size) { -1 }
    val nextByDefinition = HashMap<String, Int>()
    for (index in candidates.indices.reversed()) {
        context.ensureActive()
        val candidate = candidates[index]
        sameDef[index] = nextByDefinition[candidate.def.id] ?: -1
        nextByDefinition[candidate.def.id] = candidate.idx
    }
    candidates.forEachIndexed { index, candidate ->
        context.ensureActive()
        val fallback = candidates.getOrNull(index + 1)?.idx ?: ids.size
        val rawEnd = if (candidate.def.endMatchText.isNullOrBlank()) null else ends[candidate.def.id]?.firstAfter(candidate.idx)
        val end = rawEnd?.takeIf { sameDef[index] < 0 || it < sameDef[index] }
        candidate.endExclusive = if (end == null) fallback else end + 1
    }
    assignParents(candidates, CancellationCheck { context.ensureActive() })

    // Primitive linked child lists preserve candidate insertion order, just as legacy grouping.
    val heads = IntArray(candidates.size) { -1 }
    val tails = IntArray(candidates.size) { -1 }
    val next = IntArray(candidates.size) { -1 }
    candidates.forEachIndexed { index, candidate ->
        context.ensureActive()
        val parent = candidate.parent
        if (parent >= 0) {
            if (heads[parent] == -1) heads[parent] = index else next[tails[parent]] = index
            tails[parent] = index
        }
    }
    var membershipCount = 0

    fun members(index: Int): StorageSequenceIds {
        val candidate = candidates[index]
        val covered = BitSet(candidate.endExclusive - candidate.idx)
        var child = heads[index]
        while (child >= 0) {
            context.ensureActive()
            val nested = candidates[child]
            covered.set(nested.idx - candidate.idx, nested.endExclusive - candidate.idx)
            child = next[child]
        }
        val result = SequenceIntBuffer()
        for (position in candidate.idx + 1 until candidate.endExclusive) {
            context.ensureActive()
            if (!covered.get(position - candidate.idx) && (candidate.def.scopeTid == null || tids[position] == candidate.def.scopeTid)) {
                if (membershipCount >= limits.maxMembership) throw StorageSequenceLimitExceeded("memberships")
                result.add(ids[position])
                membershipCount++
            }
        }
        return StorageSequenceIds(result.toArray())
    }
    val groups = ArrayList<StorageSequenceGroup>()
    candidates.forEachIndexed { index, candidate ->
        context.ensureActive()
        if (candidate.parent < 0) {
            val plain = members(index)
            val nested = ArrayList<StorageNestedSequence>()
            var child = heads[index]
            while (child >= 0) {
                context.ensureActive()
                val member = candidates[child]
                val id = ids[member.idx]
                nested.add(StorageNestedSequence("sg_${member.def.id}_$id", EntryId(id), members(child), member.def.id, member.endExclusive))
                child = next[child]
            }
            val rootId = ids[candidate.idx]
            groups.add(StorageSequenceGroup("sg_${candidate.def.id}_$rootId", EntryId(rootId), plain,
                Collections.unmodifiableList(nested), candidate.def.id, candidate.endExclusive))
        }
    }
    query.validate()
    context.ensureActive()
    StorageSequenceResult(query.version, query.queryId, regexContext.hasTimedOut, Collections.unmodifiableList(groups))
} }

/** Segmented primitive vector avoids boxed per-row IDs and repeated whole-array growth copies. */
private class SequenceIntBuffer {
    private val chunks = ArrayList<IntArray>()
    var size: Int = 0
        private set

    fun add(value: Int) {
        if (size % CHUNK_SIZE == 0) chunks.add(IntArray(CHUNK_SIZE))
        chunks[size / CHUNK_SIZE][size % CHUNK_SIZE] = value
        size++
    }

    operator fun get(index: Int): Int = chunks[index / CHUNK_SIZE][index % CHUNK_SIZE]

    fun firstAfter(value: Int): Int? {
        var low = 0
        var high = size
        while (low < high) {
            val middle = low + (high - low) / 2
            if (get(middle) <= value) low = middle + 1 else high = middle
        }
        return if (low == size) null else get(low)
    }

    fun toArray(): IntArray = IntArray(size) { get(it) }

    private companion object { const val CHUNK_SIZE = 1_024 }
}
