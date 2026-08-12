package com.indagium.source

import com.indagium.diagram.DiagramResolvedTrace
import com.indagium.diagram.DiagramSourceSiteOverride
import com.indagium.diagram.DiagramTraceCall
import com.indagium.diagram.DiagramTraceDiagnostics
import com.indagium.diagram.DiagramTraceEvent
import com.indagium.diagram.DiagramTraceEvidence
import com.indagium.diagram.DiagramTraceOperation
import com.indagium.diagram.TraceCallStatus
import com.indagium.diagram.TraceDiagnosticReason
import com.indagium.diagram.TraceInvocationKind
import com.indagium.diagram.TraceOperationKind
import com.indagium.model.LogEntry
import com.indagium.utils.CancellationCheck

/**
 * Source-first interprocedural trace reconstruction.
 *
 * Log rows are observations.  They are resolved to candidate source log sites first, then the
 * candidate sequence is searched as one constrained problem.  Every search state owns a source
 * position, a receiver-independent invocation stack, and the calls/returns produced so far.  A
 * locally attractive log match is therefore discarded when it cannot participate in a legal path
 * through the remaining selected rows.
 */
class SourceTraceInferenceEngine(
    index: SourceIndex,
    private val maxPathDepth: Int = 16,
    private val maxSearchStates: Int = 64,
) {
    private val resolver = SourceEnrichmentResolver(index)
    private val methodsById = index.methods.associateBy { it.id }
    private val indexedCalls = normalizeCalls(index)
    private val callsByCaller = indexedCalls.groupBy { it.callerMethodId }
    private val operationsByMethod = index.operations.groupBy { it.methodId }
    private val operationsById = index.operations.associateBy { it.id }
    private val operationByCallId = index.operations
        .filter { it.kind == SourceOperationKind.CALL || it.kind == SourceOperationKind.ASYNC_DISPATCH }
        .mapNotNull { operation -> operation.callSiteId?.let { it to operation } }
        .toMap()
    private val operationByLogSiteId = index.operations
        .filter { it.kind == SourceOperationKind.LOG }
        .mapNotNull { operation -> operation.logSiteId?.let { it to operation } }
        .toMap()

    fun resolve(
        entries: List<LogEntry>,
        cancellationCheck: CancellationCheck = CancellationCheck {},
        sourceSiteOverrides: List<DiagramSourceSiteOverride> = emptyList(),
    ): DiagramResolvedTrace = reconstruct(entries, cancellationCheck, sourceSiteOverrides).toDiagramTrace()

    fun reconstruct(
        entries: List<LogEntry>,
        cancellationCheck: CancellationCheck = CancellationCheck {},
        sourceSiteOverrides: List<DiagramSourceSiteOverride> = emptyList(),
    ): ExecutionTrace {
        if (entries.isEmpty()) return ExecutionTrace.EMPTY
        val diagnostics = Diagnostics()
        val overrides = sourceSiteOverrides.associateBy { it.entryId }
        val candidatesByEntry = entries.mapIndexed { index, entry ->
            if (index % CANCELLATION_INTERVAL == 0) cancellationCheck()
            resolveAnchorCandidates(entry, overrides[entry.id], diagnostics)
        }
        if (candidatesByEntry.any { it.isEmpty() }) return ExecutionTrace(diagnostics = diagnostics.value)

        var states = initialStates(candidatesByEntry.first())
        if (states.isEmpty()) return ExecutionTrace(diagnostics = diagnostics.value)

        for (index in 1 until entries.size) {
            if (index % CANCELLATION_INTERVAL == 0) cancellationCheck()
            val nextStates = states.flatMap { state ->
                candidatesByEntry[index].flatMap { candidate ->
                    advance(state, candidate, entries[index - 1], diagnostics)
                }
            }
            states = pruneStates(nextStates)
            if (states.isEmpty()) {
                diagnostics.add(
                    TraceDiagnosticReason.BRANCH_INCOMPATIBLE,
                    entries[index].id,
                    "no invocation-stack path reaches the selected source log",
                )
                return ExecutionTrace(diagnostics = diagnostics.value)
            }
        }

        val ranked = states.map { state ->
            val completed = state.copyMutable()
            completed.calls.filter {
                it.returnEntryId == null && it.invocationKind == TraceInvocationKind.SYNCHRONOUS
            }.forEach { it.status = TraceCallStatus.INCOMPLETE_WINDOW }
            completed
        }.sortedByDescending { it.score }
        val best = ranked.first()
        // Candidate sites are allowed to survive until the whole ordered path has been tested.
        // Only a tie between different complete paths is ambiguous; rejecting a repeated log
        // template before later anchors are considered throws away valid source traces.
        val tied = ranked.drop(1).filter { kotlin.math.abs(it.score - best.score) < 0.000_001 }
        val differentTie = tied.firstOrNull { other ->
            other.anchors.map { it.site.id } != best.anchors.map { it.site.id }
        }
        if (differentTie != null) {
            val differing = best.anchors.zip(differentTie.anchors)
                .firstOrNull { (left, right) -> left.site.id != right.site.id }?.first
            diagnostics.add(
                TraceDiagnosticReason.AMBIGUOUS_SOURCE_SITE,
                differing?.entry?.id,
                "multiple compatible source paths remain",
            )
            return ExecutionTrace(diagnostics = diagnostics.value)
        }

        val events = best.anchors.map { anchor ->
            ExecutionTraceEvent(
                entryId = anchor.entry.id,
                sourceLogSiteId = anchor.site.id.takeIf(String::isNotBlank),
                methodId = anchor.method.id,
                ownerType = anchor.site.owningType ?: anchor.method.ownerType,
                methodName = anchor.site.methodName,
                sourceFile = anchor.site.filePath,
                sourceLine = anchor.site.callLine,
                laneId = lane(anchor.entry),
                pid = anchor.entry.pid,
                tid = anchor.entry.tid,
                confidence = anchor.match.confidence,
            )
        }
        return ExecutionTrace(
            events = events,
            invocations = best.calls.map(MutableInvocation::freeze),
            diagnostics = diagnostics.value,
        )
    }

    private fun resolveAnchorCandidates(
        entry: LogEntry,
        override: DiagramSourceSiteOverride?,
        diagnostics: Diagnostics,
    ): List<AnchorCandidate> {
        val raw = resolver.resolveCandidates(entry, MAX_ANCHOR_CANDIDATES)
        val fresh = raw.filterNot { it.stale }
        if (raw.isNotEmpty() && fresh.isEmpty()) {
            diagnostics.add(TraceDiagnosticReason.STALE_SOURCE_SITE, entry.id, raw.first().site.filePath)
            return emptyList()
        }
        val selected = if (override == null) fresh else fresh.filter { it.site.id == override.sourceLogSiteId }
        if (override != null && selected.isEmpty()) {
            diagnostics.add(TraceDiagnosticReason.STALE_SOURCE_SITE, entry.id, override.sourceLogSiteId)
            return emptyList()
        }
        val candidates = selected.mapNotNull { match ->
            if (match.confidence < MIN_SOURCE_ENRICHMENT_CONFIDENCE) return@mapNotNull null
            val methodId = match.site.methodId ?: return@mapNotNull null
            val method = methodsById[methodId] ?: return@mapNotNull null
            AnchorCandidate(entry, match, match.site, method)
        }
        if (candidates.isEmpty()) {
            val reason = if (selected.any { it.confidence < MIN_SOURCE_ENRICHMENT_CONFIDENCE }) {
                TraceDiagnosticReason.LOW_CONFIDENCE
            } else {
                TraceDiagnosticReason.CALL_GRAPH_GAP
            }
            diagnostics.add(reason, entry.id, "source log site has no usable indexed method")
            return emptyList()
        }
        // Defer ambiguity to the interprocedural search. Later ordered anchors often make one
        // candidate the only legal path; a surviving tie is reported after the complete search.
        return candidates
    }

    private fun initialStates(firstCandidates: List<AnchorCandidate>): List<PathState> = firstCandidates.map { first ->
        val initialLane = lane(first.entry)
        PathState(
            anchors = mutableListOf(first),
            stacksByLane = mutableMapOf(initialLane to mutableListOf(first.frame())),
            calls = mutableListOf(),
            score = first.match.confidence + laneEvidence(first.entry),
        )
    }

    private fun advance(
        original: PathState,
        next: AnchorCandidate,
        previousEntry: LogEntry,
        diagnostics: Diagnostics,
    ): List<PathState> {
        val base = original.copyMutable()
        val nextLane = lane(next.entry)
        val stack = base.stacksByLane[nextLane]
        // A different thread has a distinct execution stack.  The source index cannot prove an
        // arbitrary handoff from row order alone.  It may, however, render one explicitly indexed
        // async dispatch whose target has an observed first log on this new lane.  That edge never
        // joins either synchronous stack and therefore cannot create a blocking return/activation.
        if (stack == null) {
            val previousStack = base.stacksByLane[lane(previousEntry)]
            val handoffs = previousStack?.lastOrNull()?.let { findAsyncHandoffs(it, next) }.orEmpty()
            if (handoffs.size == 1) {
                val call = handoffs.single()
                val parent = previousStack?.lastOrNull()?.invocationIndex?.let { base.calls[it].invocationId }
                base.calls += invocationFor(call, next.entry, base.calls.size, parent)
            }
            base.stacksByLane[nextLane] = mutableListOf(next.frame())
            base.anchors += next
            base.score += next.match.confidence
            return listOf(base)
        }
        val current = stack.lastOrNull() ?: return emptyList()
        val targetMethodId = next.method.id
        val continuity = continuityScore(previousEntry, next.entry, base.anchors.last())

        if (targetMethodId == current.methodId) {
            val resultCall = callsProducingLog(current, next, base.calls)
            resultCall?.let { call ->
                val parent = current.invocationIndex?.let { base.calls[it].invocationId }
                val invocation = invocationFor(call, previousEntry, base.calls.size, parent)
                invocation.returnEntryId = next.entry.id
                invocation.returnLabel = next.entry.msg
                invocation.status = TraceCallStatus.RETURNED
                invocation.evidence += DiagramTraceEvidence.RUNTIME_RETURN_VALUE
                base.calls += invocation
            }
            stack.last().moveTo(next)
            base.anchors += next
            base.score += next.match.confidence + continuity
            return listOf(base)
        }

        val targetInStack = stack.indexOfLast { it.methodId == targetMethodId }
        if (targetInStack >= 0) {
            while (stack.lastIndex > targetInStack) {
                val frame = stack.removeLast()
                frame.invocationIndex?.let { closeInvocation(base.calls[it], next) }
            }
            stack.last().moveTo(next)
            base.anchors += next
            base.score += next.match.confidence + continuity
            return listOf(base)
        }

        val paths = findCallPaths(current, next)
        if (paths.isEmpty()) return emptyList()
        if (paths.size != 1) {
            diagnostics.add(
                TraceDiagnosticReason.BRANCH_INCOMPATIBLE,
                next.entry.id,
                "multiple source call paths reach the selected log",
            )
            return emptyList()
        }
        return paths.mapNotNull { path ->
            if (path.isEmpty()) return@mapNotNull null
            val state = original.copyMutable()
            val stateStack = state.stacksByLane[nextLane] ?: return@mapNotNull null
            val parent = stateStack.lastOrNull()?.invocationIndex?.let { state.calls[it].invocationId }
            path.forEachIndexed { index, call ->
                val invocation = invocationFor(call, next.entry, state.calls.size, parentForPath(state, parent, index))
                state.calls += invocation
                val target = call.candidateCalleeMethodIds.singleOrNull()
                    ?: return@mapNotNull null
                stateStack += StackFrame(target, 0, null, state.calls.lastIndex)
            }
            stateStack.last().moveTo(next)
            state.anchors += next
            state.score += next.match.confidence + continuity - (path.size - 1) * PATH_LENGTH_PENALTY
            state
        }.ifEmpty {
            diagnostics.add(TraceDiagnosticReason.UNMAPPED_CALLEE, next.entry.id, "ambiguous callee path")
            emptyList()
        }
    }

    private fun parentForPath(state: PathState, parent: String?, index: Int): String? {
        if (index == 0) return parent
        return state.calls.lastOrNull()?.invocationId
    }

    private fun closeInvocation(invocation: MutableInvocation, anchor: AnchorCandidate) {
        val observedResult = invocation.resultVariable != null && invocation.resultVariable in anchor.site.loggedValueNames
        if (invocation.invocationKind != TraceInvocationKind.SYNCHRONOUS || !observedResult) {
            invocation.status = TraceCallStatus.INCOMPLETE_WINDOW
            return
        }
        invocation.returnEntryId = anchor.entry.id
        invocation.returnLabel = anchor.entry.msg
        invocation.status = TraceCallStatus.RETURNED
        invocation.evidence += DiagramTraceEvidence.RUNTIME_RETURN_VALUE
    }

    private fun callsProducingLog(
        current: StackFrame,
        next: AnchorCandidate,
        existing: List<MutableInvocation>,
    ): IndexedSourceCall? = callsByCaller[current.methodId].orEmpty()
        .filter { it.resultVariable != null && it.resultVariable in next.site.loggedValueNames }
        .filterNot { call -> existing.any { it.callSiteId == call.id && it.returnEntryId == null } }
        .filter { call -> verifiedWithinMethod(current, call, next.site.id) }
        .singleOrNull()

    /**
     * Finds only source paths backed by the persisted operation graph.  The index's operation
     * successors are deliberately conservative: reaching a branch, return, or throw before the
     * requested operation means the lightweight scanner cannot prove the path, so no edge is
     * emitted.  Legacy synthetic indexes without operations retain the old line-order fallback.
     */
    private fun findCallPaths(from: StackFrame, target: AnchorCandidate): List<List<IndexedSourceCall>> {
        data class SearchState(
            val methodId: String,
            val fromLogSiteId: String?,
            val path: List<IndexedSourceCall>,
            val seen: Set<String>,
        )
        val queue = ArrayDeque<SearchState>()
        queue += SearchState(from.methodId, from.sourceLogSiteId, emptyList(), setOf(from.methodId))
        val matches = mutableListOf<List<IndexedSourceCall>>()
        while (queue.isNotEmpty() && matches.size < MAX_PATH_MATCHES) {
            val state = queue.removeFirst()
            if (state.path.size >= maxPathDepth) continue
            val outgoing = callsByCaller[state.methodId].orEmpty()
                .filter { call -> verifiedCallAfter(state.methodId, state.fromLogSiteId, call) }
                .filter { it.invocationKind == InvocationKind.SYNCHRONOUS }
                .sortedBy { it.callLine }
            outgoing.forEach { call ->
                call.candidateCalleeMethodIds.forEach { callee ->
                    val path = state.path + call.copy(candidateCalleeMethodIds = listOf(callee))
                    when {
                        callee == target.method.id && verifiedMethodEntryToLog(callee, target.site.id) -> matches += path
                        callee !in state.seen -> queue += SearchState(callee, null, path, state.seen + callee)
                    }
                }
            }
        }
        return matches.distinctBy { it.joinToString("/") { call -> "${call.id}:${call.candidateCalleeMethodIds.singleOrNull()}" } }
    }

    private fun findAsyncHandoffs(from: StackFrame, target: AnchorCandidate): List<IndexedSourceCall> =
        callsByCaller[from.methodId].orEmpty()
            .filter { it.invocationKind != InvocationKind.SYNCHRONOUS }
            .filter { call -> verifiedCallAfter(from.methodId, from.sourceLogSiteId, call) }
            .filter { call ->
                call.candidateCalleeMethodIds.singleOrNull() == target.method.id &&
                    verifiedMethodEntryToLog(target.method.id, target.site.id)
            }
            .distinctBy { it.id }

    private fun verifiedWithinMethod(current: StackFrame, call: IndexedSourceCall, nextLogSiteId: String): Boolean {
        val operations = operationsByMethod[current.methodId].orEmpty()
        if (operations.isEmpty()) return call.callLine in current.sourceLine..Int.MAX_VALUE
        val from = current.sourceLogSiteId ?: return false
        return operationPathIsStraight(operationByLogSiteId[from]?.id, operationByCallId[call.id]?.id) &&
            operationPathIsStraight(operationByCallId[call.id]?.id, operationByLogSiteId[nextLogSiteId]?.id)
    }

    private fun verifiedCallAfter(methodId: String, fromLogSiteId: String?, call: IndexedSourceCall): Boolean {
        val operations = operationsByMethod[methodId].orEmpty()
        if (operations.isEmpty()) return call.callLine >= 0
        val callOperation = operationByCallId[call.id]?.takeIf { it.methodId == methodId } ?: return false
        return if (fromLogSiteId == null) {
            operationPathFromMethodEntryIsStraight(methodId, callOperation.id)
        } else {
            operationPathIsStraight(operationByLogSiteId[fromLogSiteId]?.id, callOperation.id)
        }
    }

    private fun verifiedMethodEntryToLog(methodId: String, logSiteId: String): Boolean {
        val operations = operationsByMethod[methodId].orEmpty()
        if (operations.isEmpty()) return true
        val log = operationByLogSiteId[logSiteId]?.takeIf { it.methodId == methodId } ?: return false
        return operationPathFromMethodEntryIsStraight(methodId, log.id)
    }

    private fun operationPathFromMethodEntryIsStraight(methodId: String, targetOperationId: String): Boolean {
        val first = operationsByMethod[methodId].orEmpty().minByOrNull { it.sourceOrder } ?: return false
        return walkStraightSuccessors(first.id, targetOperationId, includeStart = true)
    }

    private fun operationPathIsStraight(fromOperationId: String?, targetOperationId: String?): Boolean =
        fromOperationId != null && targetOperationId != null && walkStraightSuccessors(fromOperationId, targetOperationId, includeStart = false)

    private fun walkStraightSuccessors(startId: String, targetId: String, includeStart: Boolean): Boolean {
        var currentId: String? = if (includeStart) startId else operationsById[startId]?.successorIds?.singleOrNull()
        val visited = HashSet<String>()
        while (currentId != null && visited.add(currentId)) {
            if (currentId == targetId) return true
            val operation = operationsById[currentId] ?: return false
            if (operation.kind in setOf(SourceOperationKind.BRANCH, SourceOperationKind.RETURN, SourceOperationKind.THROW)) return false
            currentId = operation.successorIds.singleOrNull()
        }
        return false
    }

    private fun pruneStates(states: List<PathState>): List<PathState> = states
        .distinctBy { it.fingerprint() }
        .sortedByDescending { it.score }
        .take(maxSearchStates)

    private fun invocationFor(
        call: IndexedSourceCall,
        anchor: LogEntry,
        ordinal: Int,
        parentInvocationId: String?,
    ): MutableInvocation {
        val calleeId = call.candidateCalleeMethodIds.singleOrNull() ?: "unknown"
        val caller = methodsById[call.callerMethodId]
        val callee = methodsById[calleeId]
        return MutableInvocation(
            invocationId = "${call.id}-${anchor.id}-$ordinal",
            callerOwnerType = caller?.ownerType ?: "UnknownCaller",
            calleeOwnerType = callee?.ownerType ?: call.receiverDeclaredType ?: "UnknownCallee",
            callerMethodId = call.callerMethodId,
            calleeMethodId = calleeId,
            callSiteId = call.id,
            callEntryId = anchor.id,
            invocationKind = call.invocationKind.toTraceKind(),
            callLabel = "${callee?.ownerType ?: call.receiverDeclaredType ?: "callee"}.${callee?.signature ?: "call()"}",
            returnLabel = callee?.declaredReturnType,
            confidence = call.resolutionConfidence,
            laneId = lane(anchor),
            sourceFile = callee?.filePath,
            sourceLine = call.callLine,
            receiverRole = call.receiverRole.name,
            resultVariable = call.resultVariable,
            parentInvocationId = parentInvocationId,
        )
    }

    private fun continuityScore(previous: LogEntry, next: LogEntry, previousAnchor: AnchorCandidate): Double {
        if (lane(previous) != lane(next)) return 0.0
        var score = 0.0
        if (previous.pid != 0 && next.pid != 0 && previous.pid == next.pid) score += PID_TID_BONUS
        if (previous.tid != 0 && next.tid != 0 && previous.tid == next.tid) score += PID_TID_BONUS
        if (previousAnchor.site.owningType == previousAnchor.method.ownerType) score += OWNER_BONUS
        return score
    }

    private fun lane(entry: LogEntry): String =
        // Logs without process/thread metadata cannot establish a *different* lane.  Keep those
        // rows in one explicitly unknown lane so source-order reconstruction remains possible;
        // a row with known metadata is still isolated from it (and therefore cannot borrow its
        // synchronous stack).
        if (entry.pid != 0 && entry.tid != 0) "${entry.pid}:${entry.tid}" else "unknown"

    private fun laneEvidence(entry: LogEntry): Double =
        if (entry.pid != 0 && entry.tid != 0) PID_TID_BONUS else 0.0

    private fun InvocationKind.toTraceKind() = when (this) {
        InvocationKind.SYNCHRONOUS -> TraceInvocationKind.SYNCHRONOUS
        InvocationKind.COROUTINE_LAUNCH -> TraceInvocationKind.COROUTINE_LAUNCH
        InvocationKind.CALLBACK_REGISTRATION -> TraceInvocationKind.CALLBACK_REGISTRATION
        InvocationKind.EXECUTOR_DISPATCH -> TraceInvocationKind.EXECUTOR_DISPATCH
        InvocationKind.BINDER_OR_RPC -> TraceInvocationKind.BINDER_OR_RPC
        InvocationKind.UNKNOWN_ASYNC -> TraceInvocationKind.UNKNOWN_ASYNC
        InvocationKind.UNKNOWN -> TraceInvocationKind.UNKNOWN
    }

    private data class AnchorCandidate(
        val entry: LogEntry,
        val match: SourceMatch,
        val site: LogCallSite,
        val method: IndexedSourceMethod,
    )

    private data class StackFrame(
        val methodId: String,
        var sourceLine: Int,
        var sourceLogSiteId: String?,
        val invocationIndex: Int?,
    ) {
        fun moveTo(anchor: AnchorCandidate) {
            sourceLine = anchor.site.callLine
            sourceLogSiteId = anchor.site.id.takeIf(String::isNotBlank)
        }
    }

    private fun AnchorCandidate.frame(): StackFrame = StackFrame(
        methodId = method.id,
        sourceLine = site.callLine,
        sourceLogSiteId = site.id.takeIf(String::isNotBlank),
        invocationIndex = null,
    )

    private data class PathState(
        val anchors: MutableList<AnchorCandidate>,
        val stacksByLane: MutableMap<String, MutableList<StackFrame>>,
        val calls: MutableList<MutableInvocation>,
        var score: Double,
    ) {
        fun copyMutable() = PathState(
            anchors.toMutableList(),
            stacksByLane.mapValuesTo(LinkedHashMap()) { (_, stack) -> stack.map { it.copy() }.toMutableList() },
            calls.map { it.copyMutable() }.toMutableList(),
            score,
        )

        fun fingerprint(): String = buildString {
            anchors.forEach { append(it.site.id).append('|') }
            append('#')
            calls.forEach { append(it.callSiteId).append(':').append(it.calleeMethodId).append('|') }
            append('#')
            stacksByLane.toSortedMap().forEach { (lane, stack) ->
                append(lane).append(':')
                stack.forEach { append(it.methodId).append('|') }
                append(';')
            }
        }
    }

    private data class MutableInvocation(
        val invocationId: String,
        val callerOwnerType: String,
        val calleeOwnerType: String,
        val callerMethodId: String,
        val calleeMethodId: String,
        val callSiteId: String,
        val callEntryId: Int,
        var returnEntryId: Int? = null,
        var status: TraceCallStatus = TraceCallStatus.UNKNOWN,
        val invocationKind: TraceInvocationKind,
        val callLabel: String,
        var returnLabel: String?,
        val confidence: Double,
        val laneId: String,
        val sourceFile: String?,
        val sourceLine: Int?,
        val receiverRole: String?,
        val resultVariable: String?,
        val parentInvocationId: String?,
        val evidence: MutableSet<DiagramTraceEvidence> = mutableSetOf(DiagramTraceEvidence.SOURCE_CALL_GRAPH),
    ) {
        fun copyMutable() = copy(
            evidence = evidence.toMutableSet(),
        )

        fun freeze() = ExecutionInvocation(
            invocationId, callerOwnerType, calleeOwnerType, callerMethodId, calleeMethodId,
            callSiteId, callEntryId, returnEntryId, status, invocationKind, callLabel, returnLabel,
            confidence, laneId, sourceFile, sourceLine, receiverRole, parentInvocationId, evidence.toSet(),
        )
    }

    private class Diagnostics {
        var value = DiagramTraceDiagnostics(); private set
        fun add(reason: TraceDiagnosticReason, entryId: Int? = null, detail: String? = null) {
            value = value.plus(reason, entryId, detail)
        }
    }

    private companion object {
        const val CANCELLATION_INTERVAL = 32
        const val MAX_ANCHOR_CANDIDATES = 32
        const val MAX_PATH_MATCHES = 8
        const val PID_TID_BONUS = 0.03
        const val OWNER_BONUS = 0.01
        const val PATH_LENGTH_PENALTY = 0.01
    }

    private fun normalizeCalls(index: SourceIndex): List<IndexedSourceCall> {
        val persisted = index.calls
        val compatible = index.sites.flatMap { site ->
            site.directCalls.mapNotNull { call ->
                val caller = call.callerMethodId ?: site.methodId ?: return@mapNotNull null
                val callee = call.targetMethodId ?: return@mapNotNull null
                IndexedSourceCall(
                    id = call.callSiteId ?: sourceStableId("compat-call", site.filePath, call.callOffset, caller),
                    callerMethodId = caller,
                    candidateCalleeMethodIds = listOf(callee),
                    receiverExpression = call.receiverExpression,
                    receiverVariable = call.receiverVariable,
                    receiverDeclaredType = call.receiverDeclaredType,
                    receiverRole = call.receiverRole,
                    callOffset = call.callOffset,
                    callLine = call.callLine,
                    resultVariable = call.resultVariable,
                    invocationKind = call.invocationKind,
                    resolutionConfidence = call.resolutionConfidence,
                )
            }
        }
        return (persisted + compatible)
            .filter { it.candidateCalleeMethodIds.size == 1 }
            .groupBy { it.id }
            .values
            .map { equivalents ->
                equivalents.maxWith(compareBy<IndexedSourceCall> { it.resultVariable != null }.thenBy { it.resolutionConfidence })
            }
    }
}

/** Source-owned reconstruction result. Diagram conversion is an adapter, not a solver concern. */
data class ExecutionTrace(
    val events: List<ExecutionTraceEvent> = emptyList(),
    val invocations: List<ExecutionInvocation> = emptyList(),
    val diagnostics: DiagramTraceDiagnostics = DiagramTraceDiagnostics(),
) {
    @Suppress("unused")
    val isComplete: Boolean get() = events.isNotEmpty() && diagnostics.diagnostics.none {
        it.reason in setOf(
            TraceDiagnosticReason.AMBIGUOUS_SOURCE_SITE,
            TraceDiagnosticReason.LOW_CONFIDENCE,
            TraceDiagnosticReason.STALE_SOURCE_SITE,
            TraceDiagnosticReason.BRANCH_INCOMPATIBLE,
            TraceDiagnosticReason.CALL_GRAPH_GAP,
            TraceDiagnosticReason.UNMAPPED_CALLER,
            TraceDiagnosticReason.UNMAPPED_CALLEE,
        )
    }

    fun toDiagramTrace(): DiagramResolvedTrace {
        val events = events.map { it.toDiagram() }
        val calls = invocations.map { it.toDiagram() }
        val operations = buildDiagramOperations(events, calls)
        return DiagramResolvedTrace(events = events, calls = calls, operations = operations, diagnostics = diagnostics)
    }

    companion object { val EMPTY = ExecutionTrace() }
}

private fun buildDiagramOperations(
    events: List<DiagramTraceEvent>,
    calls: List<DiagramTraceCall>,
): List<DiagramTraceOperation> {
    val order = events.mapIndexed { index, event -> event.entryId to index }.toMap()
    val result = mutableListOf<DiagramTraceOperation>()
    calls.forEach { call ->
        val entry = call.callEntryId
        val kind = if (call.invocationKind in setOf(
                TraceInvocationKind.COROUTINE_LAUNCH,
                TraceInvocationKind.CALLBACK_REGISTRATION,
                TraceInvocationKind.EXECUTOR_DISPATCH,
                TraceInvocationKind.BINDER_OR_RPC,
                TraceInvocationKind.UNKNOWN_ASYNC,
            )) TraceOperationKind.ASYNC_HANDOFF else TraceOperationKind.SOURCE_CALL
        result += DiagramTraceOperation(
            id = "${call.invocationId}:enter",
            kind = TraceOperationKind.ENTER_METHOD,
            entryId = entry,
            invocationId = call.invocationId,
            methodId = call.calleeMethodId,
            ownerType = call.calleeOwnerType,
            sourceFile = call.sourceFile,
            sourceLine = call.sourceLine,
        )
        result += DiagramTraceOperation(
            id = "${call.invocationId}:call",
            kind = kind,
            entryId = entry,
            invocationId = call.invocationId,
            sourceOperationId = call.callSiteId,
            ownerType = call.calleeOwnerType,
            sourceFile = call.sourceFile,
            sourceLine = call.sourceLine,
        )
        call.returnEntryId?.let { returnEntry ->
            result += DiagramTraceOperation(
                id = "${call.invocationId}:return",
                kind = if (call.status == TraceCallStatus.THREW) TraceOperationKind.THROW else TraceOperationKind.SOURCE_RETURN,
                entryId = returnEntry,
                invocationId = call.invocationId,
                sourceOperationId = call.callSiteId,
                ownerType = call.callerOwnerType,
                sourceFile = call.sourceFile,
                sourceLine = call.sourceLine,
            )
        }
    }
    events.forEach { event ->
        result += DiagramTraceOperation(
            id = "${event.entryId}:log",
            kind = TraceOperationKind.LOG_EVENT,
            entryId = event.entryId,
            sourceLogSiteId = event.sourceLogSiteId,
            methodId = event.methodId,
            ownerType = event.ownerType,
            sourceFile = event.sourceFile,
            sourceLine = event.sourceLine,
        )
    }
    return result.sortedWith(
        compareBy<DiagramTraceOperation> { order[it.entryId] ?: Int.MAX_VALUE }
            .thenBy { operationPhase(it.kind) }
            .thenBy { it.id },
    )
}

private fun operationPhase(kind: TraceOperationKind): Int = when (kind) {
    TraceOperationKind.ENTER_METHOD -> 0
    TraceOperationKind.SOURCE_CALL, TraceOperationKind.ASYNC_HANDOFF -> 1
    TraceOperationKind.SOURCE_RETURN, TraceOperationKind.THROW -> 2
    TraceOperationKind.LOG_EVENT -> 3
}

data class ExecutionTraceEvent(
    val entryId: Int,
    val sourceLogSiteId: String?,
    val methodId: String,
    val ownerType: String?,
    val methodName: String,
    val sourceFile: String,
    val sourceLine: Int,
    val laneId: String,
    val pid: Int,
    val tid: Int,
    val confidence: Double,
) {
    fun toDiagram() = DiagramTraceEvent(
        entryId, sourceLogSiteId, methodId, ownerType, methodName, sourceFile, sourceLine,
        laneId, pid, tid, confidence, setOf(DiagramTraceEvidence.EXACT_SOURCE_SITE),
    )
}

data class ExecutionInvocation(
    val invocationId: String,
    val callerOwnerType: String,
    val calleeOwnerType: String,
    val callerMethodId: String,
    val calleeMethodId: String,
    val callSiteId: String,
    val callEntryId: Int,
    val returnEntryId: Int?,
    val status: TraceCallStatus,
    val invocationKind: TraceInvocationKind,
    val callLabel: String,
    val returnLabel: String?,
    val confidence: Double,
    val laneId: String,
    val sourceFile: String?,
    val sourceLine: Int?,
    val receiverRole: String?,
    val parentInvocationId: String?,
    val evidence: Set<DiagramTraceEvidence>,
) {
    fun toDiagram() = DiagramTraceCall(
        invocationId, callerOwnerType, calleeOwnerType, callerMethodId, calleeMethodId,
        callSiteId, callEntryId, returnEntryId, status, invocationKind, callLabel, returnLabel,
        confidence, evidence, laneId, sourceFile, sourceLine, receiverRole, parentInvocationId,
    )
}
