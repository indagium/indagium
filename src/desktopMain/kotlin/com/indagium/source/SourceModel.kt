package com.indagium.source

import com.indagium.model.LogEntry
import com.indagium.model.SourceLogConfiguration
import java.security.MessageDigest

/** Bumped whenever the shape of [SourceIndex] (or the matcher-building rules that feed it)
 *  changes in a way that makes a previously-persisted index stale. Task 1 doesn't persist
 *  anything yet, but later tasks compare this against a saved value to decide whether a cached
 *  index must be rebuilt rather than merely refreshed. */
const val SOURCE_INDEX_VERSION = 19

enum class SourceSetKind {
    PRODUCTION,
    TEST,
    GENERATED,
    UNKNOWN,
}

/** Executable source operations.  These are deliberately separate from log matches: a log is an
 * observation inside a method, while CALL/RETURN are structural operations that the trace solver
 * can walk without turning a log row into an arrow. */
enum class SourceOperationKind {
    LOG,
    CALL,
    RETURN,
    THROW,
    ASYNC_DISPATCH,
    BRANCH,
    MERGE,
}

enum class ReceiverRole {
    THIS,
    FIELD,
    PROPERTY,
    PARAMETER,
    LOCAL,
    STATIC,
    UNKNOWN,
}

enum class InvocationKind {
    SYNCHRONOUS,
    COROUTINE_LAUNCH,
    CALLBACK_REGISTRATION,
    EXECUTOR_DISPATCH,
    BINDER_OR_RPC,
    UNKNOWN_ASYNC,
    UNKNOWN,
}

// Widens a signed Byte to its unsigned Int value before hex-formatting it.
private const val BYTE_MASK = 0xff

fun sourceStableId(kind: String, filePath: String, offset: Int, discriminator: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "$kind|$filePath|$offset|$discriminator".toByteArray(Charsets.UTF_8),
    )
    return digest.take(12).joinToString("") { (it.toInt() and BYTE_MASK).toString(16).padStart(2, '0') }
}

/** Generic message matchers are capped at 0.3, so this admits only specific tagged resolution. */
const val MIN_SOURCE_ENRICHMENT_CONFIDENCE = 0.4

// A log statement and a source call in the same method are associated only when the call is
// immediately before the statement. Looking at every call in the enclosing method made a log in
// another branch (for example UsbEventMonitor's "detached" branch) inherit bindDeviceProfile().
private const val MAX_SOURCE_CALL_LOG_DISTANCE_LINES = 2

/**
 * A conservative, statically-resolved call made directly by an indexed method.
 *
 * Entries are emitted only when the receiver, target owner, method name and arity identify one
 * method in the indexed source tree.  This intentionally excludes speculative overload and
 * dynamic-dispatch guesses: callers can safely render these as source-inferred edges.
 */
data class SourceDirectCall(
    val targetFilePath: String,
    val targetOwnerType: String,
    val targetMethodName: String,
    val targetMethodSignature: String,
    val targetDeclaredReturnType: String?,
    val callLine: Int,
    /** Variable assigned from this call when the source uses `val result = receiver.call()`. */
    val resultVariable: String? = null,
    /** Lexical owner of the method that made this call. */
    val sourceOwnerType: String? = null,
    /** True when this edge was discovered from a caller of the log-owning method. */
    val isCallback: Boolean = false,
    val callSiteId: String? = null,
    val callerMethodId: String? = null,
    val targetMethodId: String? = null,
    val callOffset: Int = 0,
    val receiverExpression: String? = null,
    val receiverVariable: String? = null,
    val receiverDeclaredType: String? = null,
    val receiverRole: ReceiverRole = ReceiverRole.UNKNOWN,
    val invocationKind: InvocationKind = InvocationKind.UNKNOWN,
    val resolutionConfidence: Double = 1.0,
)

data class IndexedSourceMethod(
    val id: String,
    val filePath: String,
    val ownerType: String?,
    val name: String,
    val signature: String,
    val declaredReturnType: String?,
    val startOffset: Int,
    val endOffsetExclusive: Int,
    val sourceSet: SourceSetKind = SourceSetKind.PRODUCTION,
    /** True for a callback body synthesized by the lightweight source indexer. */
    val synthetic: Boolean = false,
)

data class IndexedSourceCall(
    val id: String,
    val callerMethodId: String,
    val candidateCalleeMethodIds: List<String>,
    val receiverExpression: String?,
    val receiverVariable: String?,
    val receiverDeclaredType: String?,
    val receiverRole: ReceiverRole,
    val callOffset: Int,
    val callLine: Int,
    val resultVariable: String?,
    val invocationKind: InvocationKind,
    val resolutionConfidence: Double,
)

/** Persisted, source-ordered operation used by the bounded interprocedural trace solver. */
data class IndexedSourceOperation(
    val id: String,
    val methodId: String,
    val kind: SourceOperationKind,
    val sourceOrder: Int,
    val sourceLine: Int,
    val callSiteId: String? = null,
    val logSiteId: String? = null,
    val successorIds: List<String> = emptyList(),
)

/** One Android logging call site discovered by [SourceIndexer.build].
 *
 * [tag] is the resolved TAG string, or null when it couldn't be resolved (e.g. a Timber call
 * with no preceding `.tag(...)`, or a `Log.d(someVariable, ...)` call whose first arg isn't a
 * literal and isn't a locally-defined constant).
 *
 * [matcher] is a regex pattern string (not a compiled [Regex] — callers compile it once and
 * cache the result, see [LogSourceResolver]) built from the call's message template: literal
 * segments are `\Q...\E`-quoted and joined with `.*?` for the dynamic "holes" (interpolations,
 * concatenated variables, etc).
 */
data class LogCallSite(
    val filePath: String,
    val tag: String?,
    val methodName: String,
    val methodStartLine: Int,
    val methodEndLine: Int,
    val callLine: Int,
    val matcher: String,
    val literalLen: Int,
    // Direct Log/Timber sites remain valid when custom-wrapper settings change. Wrapper sites
    // must be hidden until their folder is reindexed with the current configuration.
    val configurationDependent: Boolean = false,
    /** Fully-qualified lexical owner (for example `com.example.SyncService`), when known. */
    val owningType: String? = null,
    /** Source declaration header for [methodName], preserved for source-only diagram labels. */
    val methodSignature: String = "",
    /** Declared Kotlin/Java return type; runtime values are deliberately never inferred here. */
    val declaredReturnType: String? = null,
    /** High-confidence direct calls made by the enclosing method. */
    val directCalls: List<SourceDirectCall> = emptyList(),
    /** Identifiers whose values are interpolated/concatenated into this log message. */
    val loggedValueNames: Set<String> = emptySet(),
    val id: String = "",
    val methodId: String? = null,
    /** Absolute source offset of the logging call, used for stable operation ordering. */
    val sourceOffset: Int = 0,
    val sourceSet: SourceSetKind = SourceSetKind.PRODUCTION,
)

/** Snapshot of a source file's on-disk state at index-build time, used by later tasks to detect
 *  when a file has changed since it was scanned without re-reading its contents. */
data class FileMeta(val mtime: Long, val size: Long, val sha256: String? = null)

data class SourceIndex(
    val version: Int,
    val roots: List<String>,
    val sites: List<LogCallSite>,
    val fileMeta: Map<String, FileMeta>,
    val builtAt: Long,
    // Per-root last-reindex timestamp — reindexing is per source folder (AppState.reindexSources),
    // so unlike [builtAt] (stamped whenever any one root is merged in) this is what the Settings UI
    // shows as "indexed N ago" for a given folder.
    val rootBuiltAt: Map<String, Long> = emptyMap(),
    // Fingerprint of the source logging configuration used for each root. A mismatch means
    // configuration-dependent wrapper sites are not current until that root is reindexed.
    val rootConfigFingerprints: Map<String, String> = emptyMap(),
    val methods: List<IndexedSourceMethod> = emptyList(),
    val calls: List<IndexedSourceCall> = emptyList(),
    val operations: List<IndexedSourceOperation> = emptyList(),
    val revision: String = "",
)

/** A candidate source site for a resolved log line. [stale] is always false in Task 1 — later
 *  tasks compute it by comparing the site's file against its recorded [FileMeta]. */
data class SourceMatch(
    val site: LogCallSite,
    val confidence: Double,
    val stale: Boolean = false,
)

data class SourceIndexStatus(
    val fileCount: Int = 0,
    val siteCount: Int = 0,
    val builtAt: Long = 0L,
    val changedFileCount: Int = 0,
    val configurationChanged: Boolean = false,
)

/** A source-only relationship between two indexed log sites. */
data class SourceInferredCall(
    val from: LogCallSite,
    val to: LogCallSite,
    val call: SourceDirectCall,
)

/** A diagram-ready, one-hop source inference rooted in an actual resolved log entry. */
data class SourceOneHopCall(
    val sourceSite: LogCallSite,
    val sourceOwnerType: String?,
    val targetOwnerType: String,
    val targetMethodName: String,
    val targetMethodSignature: String,
    val declaredReturnType: String?,
    val callLine: Int,
    /** The actual caller log message when it prints the value assigned by this call. */
    val observedReturnLabel: String? = null,
    /** Confidence of the log-to-source resolution; the direct source call itself is exact. */
    val confidence: Double,
    val sourceLogSiteId: String? = null,
    val callSiteId: String? = null,
    val callerMethodId: String? = null,
    val calleeMethodId: String? = null,
    val receiverExpression: String? = null,
    val receiverRole: ReceiverRole = ReceiverRole.UNKNOWN,
    val invocationKind: InvocationKind = InvocationKind.UNKNOWN,
    val sourceFile: String? = null,
    val sourceLine: Int? = null,
)

/**
 * Exposes one-hop source enrichment without making the diagram package depend on the indexer.
 *
 * A relationship is returned only when a call stored on [from] targets the exact source method
 * containing [to].  A caller that wants to render a source-only target can instead use
 * [directCalls] and the target metadata it contains.
 */
class SourceEnrichmentResolver(index: SourceIndex) {
    private val sites = index.sites
    private val logResolver = LogSourceResolver(index)

    fun directCalls(site: LogCallSite): List<SourceDirectCall> = site.directCalls

    /** Returns ranked candidates without applying the precision-first uniqueness gate. */
    fun resolveCandidates(entry: LogEntry, limit: Int = 10): List<SourceMatch> =
        logResolver.resolve(entry.tag, entry.msg, limit)

    fun resolveCandidates(tag: String?, message: String, limit: Int = 10): List<SourceMatch> =
        logResolver.resolve(tag, message, limit)

    fun inferOneHop(from: LogCallSite, to: LogCallSite): SourceInferredCall? {
        val call = from.directCalls.firstOrNull { candidate ->
            candidate.targetFilePath == to.filePath &&
                candidate.targetOwnerType == to.owningType &&
                candidate.targetMethodName == to.methodName &&
                candidate.targetMethodSignature == to.methodSignature
        } ?: return null
        return SourceInferredCall(from, to, call)
    }

    fun inferOneHop(from: LogCallSite): List<SourceInferredCall> = sites.mapNotNull { to -> inferOneHop(from, to) }

    /** Resolves [entry] to source and returns only direct calls that were uniquely indexed. */
    fun resolveOneHop(entry: LogEntry, limit: Int = 10): List<SourceOneHopCall> =
        uniquelyResolvedMatches(entry.tag, entry.msg, limit)
            .filter { it.confidence >= MIN_SOURCE_ENRICHMENT_CONFIDENCE && !it.stale }
            .flatMap { match ->
                toOneHopCalls(match, directCallsNearLogSite(match.site), entry.msg)
            }
            .take(limit)

    /** Tag/message variant for callers that intentionally do not hold a [LogEntry]. */
    fun resolveOneHop(tag: String?, message: String, limit: Int = 10): List<SourceOneHopCall> = uniquelyResolvedMatches(tag, message, limit)
        .filter { it.confidence >= MIN_SOURCE_ENRICHMENT_CONFIDENCE && !it.stale }
        .flatMap { match -> toOneHopCalls(match, match.site.directCalls) }
        .take(limit)

    private fun directCallsNearLogSite(site: LogCallSite): List<SourceDirectCall> {
        // A log can mark either side of the call:
        //   val value = service.fetch(); Log.d(..., "value=$value")
        // or:
        //   Log.d(..., "starting"); service.fetch()
        // Restricting this to preceding calls made the second, very common form disappear.
        val localCalls = site.directCalls.filterNot { it.isCallback }
        val incomingCalls = site.directCalls.filter { it.isCallback }
        // A logged assigned value is stronger evidence than line proximity. The caller may do
        // validation, mapping, or another small operation between `service.fetch()` and the log;
        // retain the exact call that produced the identifier instead of attaching the log to the
        // nearest unrelated call. Only calls before the log qualify: a log must observe a value
        // that has already been produced in the caller's execution path.
        val loggedResultCalls = localCalls.filter { call ->
            call.resultVariable != null &&
                call.resultVariable in site.loggedValueNames &&
                call.callLine <= site.callLine
        }
        if (loggedResultCalls.isNotEmpty()) {
            return loggedResultCalls.distinct()
        }
        val nearestLine = localCalls
            .asSequence()
            .minByOrNull { kotlin.math.abs(it.callLine - site.callLine) }
            ?.callLine
        val nearbyLocalCalls = nearestLine
            ?.takeIf { kotlin.math.abs(site.callLine - it) <= MAX_SOURCE_CALL_LOG_DISTANCE_LINES }
            ?.let { line -> localCalls.filter { it.callLine == line } }
            .orEmpty()
        // Incoming calls are method-level possibilities, not runtime evidence. Attaching every
        // caller here made a log inside one callee claim that production, fixture, and test
        // callers all ran. Only a direct, local call (or the range trace's own ordered evidence)
        // may promote an invocation.
        if (nearbyLocalCalls.isNotEmpty()) return nearbyLocalCalls.distinct()
        // Incoming member calls are useful evidence when exactly one indexed caller reaches the
        // logging method. Several candidates remain ambiguous until range-level context can rank
        // them; never expose all of them as simultaneous runtime calls.
        return incomingCalls.singleOrNull()?.let(::listOf).orEmpty()
    }

    private fun toOneHopCalls(
        match: SourceMatch,
        calls: List<SourceDirectCall>,
        message: String? = null,
    ): List<SourceOneHopCall> = calls.map { call ->
        SourceOneHopCall(
            sourceSite = match.site,
            sourceOwnerType = call.sourceOwnerType ?: match.site.owningType,
            targetOwnerType = call.targetOwnerType,
            targetMethodName = call.targetMethodName,
            targetMethodSignature = call.targetMethodSignature,
            declaredReturnType = call.targetDeclaredReturnType,
            callLine = call.callLine,
            observedReturnLabel = call.resultVariable
                ?.takeIf { it in match.site.loggedValueNames }
                ?.let { message },
            confidence = match.confidence,
            sourceLogSiteId = match.site.id.takeIf { it.isNotBlank() },
            callSiteId = call.callSiteId,
            callerMethodId = call.callerMethodId,
            calleeMethodId = call.targetMethodId,
            receiverExpression = call.receiverExpression,
            receiverRole = call.receiverRole,
            invocationKind = call.invocationKind,
            sourceFile = match.site.filePath,
            sourceLine = match.site.callLine,
        )
    }

    /**
     * Source calls are only useful as diagram evidence when the log row identifies one source
     * site. A ranked tie is still ambiguous even when the tied sites have different owners, so
     * do not turn it into a guessed endpoint. A strictly stronger match is safe to use; the
     * existing resolver's tag and literal-length scoring remains the single ranking authority.
     */
    private fun uniquelyResolvedMatches(tag: String?, message: String, limit: Int): List<SourceMatch> {
        // Always fetch one extra candidate for the ambiguity check. A caller asking for one
        // output still must not make a tied second source site invisible.
        val matches = logResolver.resolve(tag, message, maxOf(limit, 2))
        val best = matches.firstOrNull() ?: return emptyList()
        val second = matches.getOrNull(1)
        if (second != null && second.confidence == best.confidence) return emptyList()
        return listOf(best)
    }
}

/** Host state for the source-code popup (Task 4): the resolved candidates for whichever log row
 *  triggered it, and which one is currently shown. [selected] is an index into [matches]. */
data class SourceCodeView(
    val matches: List<SourceMatch>,
    val selected: Int = 0,
)

/** Stable per-root fingerprint for the settings that affect source-call extraction. */
fun sourceConfigurationFingerprint(
    configurations: List<SourceLogConfiguration>,
    autoDiscoveryEnabled: Boolean = true,
): String {
    val canonical = buildString {
        append("autoDiscovery=").append(autoDiscoveryEnabled).append('\n')
        append(configurations.sortedBy { it.id }.joinToString("\n") { config ->
            buildString {
                append(config.id).append('|').append(config.name)
                config.wrapperRules.sortedWith(compareBy({ it.ownerType }, { it.methodName }, { it.tagArgumentIndex }, { it.messageArgumentIndex }))
                    .forEach { rule ->
                        append('|').append(rule.ownerType).append('|').append(rule.methodName)
                            .append('|').append(rule.tagArgumentIndex).append('|').append(rule.messageArgumentIndex)
                            .append('|').append(rule.throwableArgumentIndex ?: -1)
                    }
            }
        })
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(
        "source-index-$SOURCE_INDEX_VERSION\n$canonical".toByteArray(Charsets.UTF_8),
    )
    return digest.joinToString("") { "%02x".format(it) }
}
