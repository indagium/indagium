# Draft ADR: log storage boundary and read lifetimes

Status: **draft, not approved or frozen**. Date: 2026-11-04. This is a proposed contract for review after the compact-entry experiment and its acceptance decision; types and representation may change. See [the redesign plan](LOG_STORAGE_REDESIGN_PLAN.md) for sequencing, scope and gates. No new route is enabled by this document.

## Context and decision under review

Use a Kotlin-owned asynchronous storage boundary with a Kotlin implementation first, keeping the legacy route available for unsupported workflows. A tab owns a handle and presentation state, while operations hold explicitly released snapshots. Queries and projections retain compact references rather than all decoded rows. This separates storage from Compose and leaves an eventual native implementation possible without requiring one now.

Current code matters to the contract: `parseLogcatLines` skips empty/separator lines and assigns `Int` IDs to emitted entries, supports a starting ID, and preserves unparsed input as RAW. `visibleEntries` filters before folding; filtered export uses that same set. `computeItems` supports sequence, stack and manual headers, including nesting, crossing and thread-scoped membership. `LogViewer` keys rows by tab plus entry ID and headers by group ID, and selects ranges in displayed order. `RowsPin` currently keeps a closed tab's backing rows readable until its last reader releases them. `LogEntry` also carries exact timestamp text, day slot, source and DLT metadata used beyond rendering. These behaviors are migration requirements, not evidence that IDs equal file line numbers or positions.

## Proposed Kotlin surface

Illustrative declarations below specify boundaries and semantics, not final names, serialization or the compact-entry representation. All I/O and scans execute off the Compose thread; suspend functions are cancellation-cooperative. Counts and ordinals are nonnegative `Long`; file byte offsets are `Long`. Existing persisted entry IDs remain `Int` until a separately reviewed migration. Overflow or a source too large for the supported ID domain returns `LimitExceeded`; never wrap or narrow silently. A UI toolkit index that cannot represent a requested position must produce an explicit unsupported/limit result or use a separately designed windowing scheme.

```kotlin
@JvmInline value class SourceId(val value: String)  // logical source, not a path alone
@JvmInline value class EntryId(val value: Int)      // existing persisted identity
@JvmInline value class RowOrdinal(val value: Long)  // emitted row in source order, zero-based
@JvmInline value class DisplayOrdinal(val value: Long)
data class Version(val lifetime: String, val revision: Long)
data class RowRef(val source: SourceId, val id: EntryId)
data class Page<T>(val version: Version, val start: Long, val items: List<T>, val endReached: Boolean)

interface SourceHandle : AutoCloseable {
    val id: SourceId
    suspend fun snapshot(): ReadSnapshot
    override fun close() // idempotent; rejects new snapshots immediately
}
interface ReadSnapshot : AutoCloseable {
    val version: Version
    val rowCount: Long
    suspend fun rows(start: RowOrdinal, limit: Int): Page<DecodedRow>
    suspend fun resolve(id: EntryId): RowOrdinal? // null only for absent ID
    suspend fun findTime(request: TimeLookup): TimeLookupResult
    suspend fun query(spec: QuerySpec): QueryHandle
    override fun close() // idempotent; releases this lease
}
interface QueryHandle : AutoCloseable {
    val version: Version
    val queryId: String
    val progress: StateFlow<QueryProgress> // immutable snapshots, includes terminal outcome
    suspend fun awaitReady()
    suspend fun matches(start: Long, limit: Int): Page<RowRef>
    suspend fun ordinalOf(id: EntryId): Long?
    override fun close() // cancels query and releases its owned resources
}
interface ViewProjection : AutoCloseable {
    val version: Version
    val projectionId: String
    suspend fun page(start: DisplayOrdinal, limit: Int): Page<DisplayItem>
    suspend fun locate(id: EntryId): DisplayLocation?
    override fun close()
}
```

`DecodedRow` contains a row reference and immutable, owned decoded fields with the accepted `LogEntry` semantics; it need not retain a `LogEntry` or expose its data-class API. `DisplayItem` distinguishes a row from sequence, manual and stack headers and carries stable group identity, anchor, indentation, counts, expansion and styling/membership metadata needed by the viewer. `TimeLookup` must specify nearest/before/after, ties, missing dates and timeline mapping according to existing video behavior; its exact rule is a parity-test prerequisite, not an assumed timestamp sort. A row can be represented by a header when folded; `locate` reports that location and any required expansion rather than silently treating it as absent.

A page request requires `1 <= limit <= configuredMaxPageRows`; invalid arguments fail explicitly. It returns at most that limit, in order, and an empty page at/past end with `endReached=true`. Decoded pages are immutable and own their data, so closing a snapshot does not invalidate an already returned page; the caller drops it when obsolete. Peak bytes are not bounded by row count alone: a single oversized record is returned intact, bypasses the normal cache, and its transient allocation is separately accounted and concurrency-limited. Implementations enforce a shared experimental decoded-cache budget of 32 MiB and bounded scans/page builds; index and scratch memory are separate. Streaming export uses bounded batches and a backpressured suspend sink, consumes the query's filtered rows before folding, and reports write failures; it does not feed the viewport cache or silently truncate output.

Selection is an immutable value scoped to source, version and projection/query identity: explicit `RowRef`s, a displayed range with anchor and endpoint, or all matching rows plus compact exclusions. Resolution for copy/export/annotation is a cancellable stream under an appropriate lease. Preserve each action's current treatment of headers and collapsed members; do not assume every action expands a collapsed group. Query replacement or version mismatch invalidates a selection until explicitly resolved/rebased by the application, never silently applies it to a different result.

## Ownership, versions and cancellation

- Opening produces a new handle lifetime even for the same path. A logical `SourceId` is distinct from that lifetime and must only be reused when the application can establish the existing persisted-note/source relationship. Row IDs remain stable within the same logical source as required by persistence; a replacement or reparse is not presumed identical. Merged sources need an explicit ID mapping and source badge parity, not IDs derived from file offsets.
- A successful `snapshot()` atomically pins a published source revision and its resources, including an append snapshot's maximum byte length and emitted row count. The handle owns its source reference; a snapshot owns one lease; query/projection and export jobs own their own leases or a documented retained child lease. Borrowed leases cannot outlive their owner. Shared tabs hold independent handles/references: closing one does not close another.
- Handle close atomically prevents new snapshots, invalidates that handle's publication token and cancels its owned jobs. In-flight work either observes cancellation or finishes while its lease remains valid. Final lease release closes the reader and removes an owned extraction only after all owners/readers release it. `close()` is nonblocking and idempotent; tests or shutdown may await a separate termination signal. An acquired lease may finish after its originating tab closes, but cannot publish into that tab. An operation started after snapshot close fails `Closed`.
- Coroutine cancellation propagates as `CancellationException` rather than a normal empty page or success; cleanup runs in `finally`. Query replacement closes the old query and increments an application request token. Every page/progress/result is associated with source lifetime, revision, query/projection ID and request token; publication checks all applicable tokens and tab ownership on the state owner immediately before committing. Cancellation alone is not a publication guard.
- A query has running, complete, failed and cancelled terminal states. Until complete, progress can expose counts explicitly marked provisional; random access and select-all use a completed result unless a separately specified incremental behavior is implemented. A failed/timed-out query never publishes partial matches as a complete result. Closing a projection releases its owned result/lease without closing a query shared by another owner.
- Immutable files are checked at open and at defined read/scan boundaries (including after a scan), using file identity and metadata where available. Replacement, truncation or a detected modification invalidates the snapshot and fails `SourceChanged`; discard partial results. Metadata checks cannot guarantee detection of a same-size, same-timestamp rewrite, or prevent every mid-read race: document this limitation and do not promise a byte-perfect snapshot without copying or stronger validation. For append-only capture, an existing snapshot cannot read past its published boundary; a later committed revision can extend it. Truncation/rotation invalidates the relevant lifetime; continuation must validate the prefix and parser state before reusing prior results. Appending alone does not mutate an older published query.

## Errors, routing and migration

Expected errors should be typed at the boundary: `Closed`, `SourceChanged`, `IoFailure` (including permissions), `InvalidQuery`/regex timeout, `Unsupported`, `LimitExceeded`, and `DecodeFailure` only where the legacy parser cannot produce its normal replacement/RAW result. Preserve the distinction between absent ID (`null`) and a failed read. Carry safe diagnostic context and underlying causes without exposing a successful partial result. Invalid regex and timeout must retain current user-visible semantics; cancellation is control flow, not `IoFailure`.

Routing is decided before a workflow starts, records the selected backend and reason, and defaults to legacy until its route passes the plan's gates. Unsupported formats, capture modes and operations use an explicit legacy route; do not silently switch backend after a source changes or a query fails. An intentional reopen/retry can choose a route anew, with a new lifetime and visible failure handling. ZIP uses the existing safe extraction lifecycle or one owned extraction, with path/size validation and last-reader cleanup; video stays under its controller and accesses log timing through the snapshot. Legacy benchmark runs and fallback cases are identified as such.

Migration risks requiring explicit owners and parity tests before promotion:

1. Existing consumers accept `List<LogEntry>`, `rmap`, `Int` IDs and data-class operations, including analysis, merge, notes, compare, persistence and video. An eager compatibility adapter can hide full materialization and erase the memory benefit; limit adapters to already-loaded pages or route the whole unsupported workflow to legacy.
2. Parser equivalence includes skipped lines, BOM/CRLF, invalid UTF-8, RAW fallback, IDs, DLT and exact timestamp/day handling. Do not infer row identity from line number or normalized timestamp. The compact-entry experiment may change the decoded type or conversion cost, so freeze this API only after its result.
3. Group composition includes sparse thread membership, nested/crossing groups and manual overlap rules. Filtering happens before presentation folding; navigation, selection and filtered export need independent expected results, not only a comparison sharing the same implementation.
4. Closing, duplicate tabs, inactive eviction, restoration, cancellation during export and late cache publication must be exercised with deterministic races. Track actual jobs, leases, extraction files and cache retention; a stale page cannot revive a closed tab.
5. Index memory grows with input and broad queries may retain large primitive results. Measure index, cache, transient peaks, complete readiness, filtering and real grouped views against both accepted compact-entry and original baselines. Do not call a bounded viewport constant total memory.

Before approval, settle timestamp lookup/tie behavior against current tests, ID persistence and source-identity rules for each format/merge, grouped selection behavior per action, partial-query UX, large-position UI policy, and implementation-specific mutation checks. Add independent expected-value tests plus legacy parity tests for each approved route. The storage boundary is not a promotion decision; the plan's correctness and performance gates remain authoritative.

## Semantic audit to resolve before freezing

The existing application has several distinct time lookups. Ordinary video-to-log lookup scans the unrolled whole-log timeline and keeps the first source-order row on equal distance; video follow floors within displayed IDs (including headers), uses the last duplicate and clamps before the first visible timestamp. Its nonascending fallback is source-order linear, not a maximum-time query. Capture segment/gap mapping has its own tie and duplicate rules, and compare synchronization uses a separate approximate time-of-day lookup. The API needs action-specific requests and independent tests for each rule; a single generic nearest-time operation is insufficient. See `AppState.videoMsToNearestLogId`, video follow in `AppState.kt`, `CaptureTimelineIndex.kt`, and `CompareFilters.kt`.

Logcat IDs count emitted rows rather than physical lines and accept a starting ID; merge reassigns IDs after its stable ordering. Annotations persist tab and entry IDs. Source identity and physical position must therefore remain separate, with explicit merge mappings and no claim of stable pre-merge IDs.

Selection also depends on the action: select-all takes visible ordinary rows, shift selection traverses visible IDs including headers, copy uses selected IDs without expanding collapsed groups, and Seq3 expands collapsed group membership subject to existing bounds. Filtered export uses all filter matches before folding and atomically replaces the destination. Selection resolution and exports must specify the consuming action rather than applying a single generic query selection to all workflows.

### Grouped projection constraint

The current renderer resolves crossing sequence hosts, manual nesting and stack preemption before traversing groups. A compact projection should preserve that logic as a pure event emitter: coalesce ordinary displayed rows into ordinal runs and retain sparse group-header descriptors, with prefix display counts for paging. Expanded stack members can be displayed immediately after a header even when they are not source-adjacent, so a source range alone cannot represent every page. Keep declared group ends distinct from effective hosted extents, and retain sparse membership for thread-scoped sequences. Nested manual anchors, collapsed sequence escape behavior, stack preemption and crossing chains require independent expected-value tests as well as parity with the existing renderer. Some group patterns can produce O(n) runs; account for that memory. Existing group construction itself currently consumes eager data, so a compact renderer alone is not the storage vertical slice.

Existing grouping tests also encode cases in which the same entry appears at multiple displayed positions. Location APIs must specify which occurrence an action wants; a projection must not silently deduplicate these until behavior changes are separately reviewed.

### Selection distinction

Filtered-pane select-all currently selects displayed ordinary-row IDs and excludes headers and folded members. The Original pane's independent select-all includes displayed header IDs. Range selection traverses displayed IDs, including headers, then deduplicates to ID membership; duplicates on screen therefore share selection state. Preserve the insertion-ordered anchor and define how a clicked display occurrence is resolved, rather than assuming entry IDs uniquely identify display positions. A future compact selection needs source, query, projection/fold and pane generations, an explicit header policy, and ID-level inclusions/exclusions. `AllMatchingQuery` is not equivalent to filtered-pane select-all because it includes folded members. Current selections can persist across changes, so rebasing or rejection needs explicit UI behavior and parity tests.

Copy resolves selected IDs in numeric order without expanding groups; diagram generation applies separate group expansion and its existing manual-range fallback. Filtered export ignores selection as a row filter, but selection can still determine the Δt formatting anchor. Annotation capture has another rule for multi-selection versus the clicked row. A streaming exporter must preserve these distinctions, process-name/DLT columns, CSV escaping and atomic replacement.

A streaming export can retain the previous filtered row and, when applicable, the resolved lowest selected ID as its delta anchor. It must snapshot presentation settings and process-name metadata, resolve the anchor from the unfiltered source, and preserve date-aware delta and predecessor behavior across page boundaries. It writes to a sibling temporary file, checks cancellation and source/query validity before replacing the destination, and removes the temporary file on pre-commit failure. The existing move helper can fall back to a non-atomic replace on unsupported filesystems. Cancellation after commit needs a distinct outcome: it cannot promise that the destination stayed unchanged.
