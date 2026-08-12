package com.indagium.diagram

/** Runtime outcome of a source-backed invocation. */
enum class TraceCallStatus {
    RETURNED,
    THREW,
    INCOMPLETE_WINDOW,
    TERMINAL_FAILURE,
    UNKNOWN,
}

/** Invocation semantics used to decide whether a blocking activation is valid. */
enum class TraceInvocationKind {
    SYNCHRONOUS,
    COROUTINE_LAUNCH,
    CALLBACK_REGISTRATION,
    EXECUTOR_DISPATCH,
    BINDER_OR_RPC,
    UNKNOWN_ASYNC,
    UNKNOWN,
}

/** Ordered semantic operations emitted by the source execution walk. */
enum class TraceOperationKind {
    ENTER_METHOD,
    SOURCE_CALL,
    LOG_EVENT,
    SOURCE_RETURN,
    THROW,
    ASYNC_HANDOFF,
}

/** What supplied the structural semantics of the displayed diagram. */
enum class SourceTraceMode {
    DISABLED,
    SOURCE_TRACE,
    /** Reserved for a future per-lane trace projection; never fabricated from a global fallback. */
    PARTIAL_VERIFIED,
    FALLBACK,
}

/** Evidence attached to a source-trace decision. */
enum class DiagramTraceEvidence {
    EXACT_SOURCE_SITE,
    CONTEXT_INFERRED,
    STACK_TRACE,
    PID_TID_CONTINUITY,
    SOURCE_CALL_GRAPH,
    RUNTIME_RETURN_VALUE,
    EXCEPTION_MARKER,
    MANUAL_OVERRIDE,
    ASYNC_DISPATCH,
}

/** Reasons an otherwise useful candidate was not promoted to a generated call. */
enum class TraceDiagnosticReason {
    AMBIGUOUS_SOURCE_SITE,
    LOW_CONFIDENCE,
    STALE_SOURCE_SITE,
    BRANCH_INCOMPATIBLE,
    UNMAPPED_CALLER,
    UNMAPPED_CALLEE,
    CALL_GRAPH_GAP,
    THREAD_CONFLICT,
    ASYNC_BOUNDARY,
    MESSAGE_BUDGET,
    MALFORMED_STACK,
    CANCELLATION,
}

data class DiagramTraceEvent(
    val entryId: Int,
    val sourceLogSiteId: String? = null,
    val methodId: String? = null,
    val ownerType: String? = null,
    val methodName: String? = null,
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
    val laneId: String = "unknown",
    val pid: Int = 0,
    val tid: Int = 0,
    val confidence: Double = 0.0,
    val evidence: Set<DiagramTraceEvidence> = emptySet(),
    val stale: Boolean = false,
)

data class DiagramTraceCall(
    val invocationId: String,
    val callerOwnerType: String,
    val calleeOwnerType: String,
    val callerMethodId: String? = null,
    val calleeMethodId: String? = null,
    val callSiteId: String? = null,
    val callEntryId: Int,
    val returnEntryId: Int? = null,
    val status: TraceCallStatus = TraceCallStatus.UNKNOWN,
    val invocationKind: TraceInvocationKind = TraceInvocationKind.UNKNOWN,
    val callLabel: String,
    val returnLabel: String? = null,
    val confidence: Double,
    val evidence: Set<DiagramTraceEvidence> = emptySet(),
    val laneId: String = "unknown",
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
    val receiverRole: String? = null,
    val parentInvocationId: String? = null,
)

data class DiagramTraceOperation(
    val id: String,
    val kind: TraceOperationKind,
    val entryId: Int,
    val invocationId: String? = null,
    val sourceOperationId: String? = null,
    val sourceLogSiteId: String? = null,
    val methodId: String? = null,
    val ownerType: String? = null,
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
)

data class DiagramTraceDiagnostic(
    val reason: TraceDiagnosticReason,
    val entryId: Int? = null,
    val detail: String? = null,
)

data class DiagramTraceDiagnostics(
    val droppedByReason: Map<TraceDiagnosticReason, Int> = emptyMap(),
    val ambiguousEntryIds: List<Int> = emptyList(),
    val staleEntryIds: List<Int> = emptyList(),
    val diagnostics: List<DiagramTraceDiagnostic> = emptyList(),
    val truncated: Boolean = false,
) {
    fun plus(reason: TraceDiagnosticReason, entryId: Int? = null, detail: String? = null): DiagramTraceDiagnostics {
        val counts = droppedByReason.toMutableMap()
        counts[reason] = (counts[reason] ?: 0) + 1
        val ambiguity = if (reason == TraceDiagnosticReason.AMBIGUOUS_SOURCE_SITE && entryId != null) {
            (ambiguousEntryIds + entryId).distinct().take(MAX_TRACE_DIAGNOSTIC_ENTRIES)
        } else {
            ambiguousEntryIds
        }
        val stale = if (reason == TraceDiagnosticReason.STALE_SOURCE_SITE && entryId != null) {
            (staleEntryIds + entryId).distinct().take(MAX_TRACE_DIAGNOSTIC_ENTRIES)
        } else {
            staleEntryIds
        }
        val next = if (diagnostics.size < MAX_TRACE_DIAGNOSTIC_ENTRIES) {
            diagnostics + DiagramTraceDiagnostic(reason, entryId, detail?.take(MAX_TRACE_DIAGNOSTIC_CHARS))
        } else {
            diagnostics
        }
        return copy(
            droppedByReason = counts,
            ambiguousEntryIds = ambiguity,
            staleEntryIds = stale,
            diagnostics = next,
            truncated = truncated || diagnostics.size >= MAX_TRACE_DIAGNOSTIC_ENTRIES,
        )
    }

    companion object {
        const val MAX_TRACE_DIAGNOSTIC_ENTRIES = 128
        const val MAX_TRACE_DIAGNOSTIC_CHARS = 240
    }
}

/** Complete, range-level source result consumed by the diagram builder. */
data class DiagramResolvedTrace(
    val events: List<DiagramTraceEvent> = emptyList(),
    val calls: List<DiagramTraceCall> = emptyList(),
    val operations: List<DiagramTraceOperation> = emptyList(),
    val diagnostics: DiagramTraceDiagnostics = DiagramTraceDiagnostics(),
) {
    companion object {
        val EMPTY = DiagramResolvedTrace()
    }
}
