# Log storage redesign: implementation plan for Terra and Luna

Status: proposed implementation plan; creating this document does not enable a new backend.
Date: 2026-11-04.
Starting point: the existing `codex/ram-poc` work, including its uncommitted changes.
Related evidence: [RAM POC measurements](RAM_POC.md).
Revision: incorporates the follow-up review of compact entries, native memory and benchmark uncertainty.

## 1. Outcome and architectural decision

Reduce memory retained after opening real log views, including grouped views. Preserve filtering, navigation, selection, exports, video alignment and close/reopen behavior. Measure the complete application as well as the log subsystem.

Use a **Kotlin storage interface and a Kotlin implementation first**. Keep Compose. Make the storage implementation replaceable so that a Rust implementation can later be compared behind the same contract. C++ is an alternative only if a concrete reusable engine or project constraint justifies it.

The architectural change is that a tab owns a source handle, queries and small viewport pages instead of a fully decoded `List<LogEntry>` and materialized `List<LogItem>` for the entire file.

Deliver independently reviewable improvements in this order: confirmed lifecycle fixes, native-memory attribution, compact entries, then the larger storage boundary. Do not deliver another parser-only benchmark as the completed storage feature. Its first integration milestone must open a generated file in the real viewer through the new boundary.

## 2. Model assignment

Recommended worker allocation:

| Work | Worker | Reasoning | Responsibility |
|---|---|---|---|
| Contracts and migration design | Primary agent, with Terra design input | Primary's configured effort | Freeze invariants and review integration decisions |
| Storage, indexing, leases and cancellation | GPT-6.1 Terra (`gpt-6.1-terra`) | high | Implement bounded, separately reviewed work packets |
| Queries and grouped projections | GPT-6.1 Terra | high | Preserve semantics across storage and rendering |
| Compose and application integration | GPT-6.1 Terra | high | Own shared integration files serially |
| Synthetic fixtures and benchmark reporting | GPT-6 Luna (`gpt-6-luna`) | xhigh | Implement explicit scenarios and machine-readable reports |
| Bounded regression tests and documentation | GPT-6 Luna | xhigh | Work against approved contracts and expected results |
| Final correctness and measurement review | Primary agent | Primary's configured effort | Independently inspect changes and verify claims |

If using one worker model, choose Terra at high for continuity across the core implementation. This is an engineering assignment recommendation, **not a demonstrated model-quality ranking**. Luna can implement core work only after it is reduced to a small, explicit contract with independent review. Higher reasoning effort alone is not proof that either model will solve a broad refactor correctly.

Official guidance describes Terra as suitable for scoped code fixes and Luna for scoped, efficient work; it does not establish a winner for this repository. Both requested effort settings are supported. Sources checked on the date above: [model selection](https://developers.openai.com/api/docs/guides/model-selection), [Terra](https://developers.openai.com/api/docs/models/gpt-6.1-terra), [Luna](https://developers.openai.com/api/docs/models/gpt-6-luna).

### Working rules for every subagent

- Read applicable repository instructions before editing. Inspect the current diff; never discard existing POC or user changes.
- You are not alone in the codebase. Modify only your assigned files. Request an integration change from its owner instead of editing shared files concurrently.
- Use only generated fixtures and isolated temporary app homes/preferences. Never inspect personal logs, captures, sessions, settings, indexes, credentials or a user's running app.
- Restrict benchmark paths to the generated fixture directory; reject escapes and symlink redirection. Create restored sessions through the existing persistence flow inside the isolated home.
- Do not run benchmarks while another worker is compiling or testing. The primary agent owns the benchmark schedule and identifies only processes it launched.
- Report incomplete features, fallback paths, failed tests and failed performance gates explicitly.
- Do not replace the UI framework, change heap limits, migrate user data, publish externally or introduce a native dependency as part of the initial Kotlin implementation.

## 3. Preserve the useful work; correct the remaining limitations

Keep the verified closed-row release, reader-pin and cache-publication fixes, plus the existing inactive-tab residency behavior. Review these lifetimes again when ownership changes.

Prepare the confirmed lifecycle/cache fixes as a separate reviewable change from experimental projection/storage work. Preserve all existing edits; do not use a blanket reset or include unrelated changes. Re-run the deterministic close/publication regressions before treating these fixes as ready.

Current limitations that this work must address:

1. `Filter.kt` uses `FlatRows` only when there are no relevant stack, sequence or manual groups. One group can bypass the wrapper saving across the whole view.
2. `LogParser.kt` still decodes every entry into a resident list. The POC leaves this underlying memory almost unchanged.
3. `LogViewer.kt` retains full ID arrays and a bitset; append summaries copy arrays proportional to visible row count.
4. The direct-file experiment is test-only and its sequential filter was about 12 times slower than the eager model at 10m rows. Its per-row metadata checks, reads, buffer allocation and parser setup are concrete profiling candidates. Measure each change before attributing the gap to disk access, UTF-8 decoding or Kotlin. Preserve changed-file detection when batching validation.
5. Startup and small-file performance gates previously failed. Existing benchmark numbers do not approve the new backend or demonstrate a general startup improvement.
6. Native/graphics/video memory is a separate part of the footprint. Storage savings must not be presented as a fix for all video memory growth.

### Near-term compact-entry experiment

Implement this before the broad storage migration, as two separately measurable changes: optional metadata extraction, then timestamp representation. Use Kotlin and retain the current parsing semantics.

An isolated JDK 21 object-layout probe during review measured the current `LogEntry` at 72 bytes and a representative `12:34:56.789` string plus backing array at 56 bytes. Illustrative layouts measured 48 bytes with an extension reference and 56 bytes with a numeric timestamp plus fallback reference. These prototype layouts are not implemented production results. Reproduce the probe with the actual candidate classes and record JDK/compressed-reference settings.

For rows with no extension and a canonical timestamp, the combined layout suggests approximately 72 bytes saved per row: 69 MiB at 1m or 687 MiB at 10m. Do not promise a universal 35–40% heap reduction or extrapolate this to process footprint.

- Compare moving `sourceTag` and the six DLT fields into optional metadata against alternatives. DLT and merged rows use these fields: the illustrative 40-byte extension can make metadata-bearing rows larger. Include plain logcat, DLT, merged and mixed workloads in the decision.
- Encode supported timestamps compactly, with exact fallback for other formats. Preserve text, precision, date/day rollover and video alignment. Do not normalize timestamp strings silently.
- Keep formatting out of hot loops where possible. A computed `ts` getter can allocate a string on every access; measure allocations during filtering, summaries, scrolling and export. Do not solve this by restoring an unbounded per-row string cache.
- Audit constructors, positional arguments, `copy`, destructuring, equality/hash behavior and persistence/serialization. A compatibility getter alone does not preserve the data-class API.
- Test each variant against independently expected parsing and formatting results, including metadata-heavy rows. Retain only variants with measured memory savings and passing correctness/timing gates; reject or redesign variants that worsen supported formats.
- Report this phase against the existing POC baseline. For the subsequent storage redesign, report both incremental savings against the accepted compact-entry phase and cumulative savings against the original baseline, without double counting.

### Native-memory investigation and optional JVM experiments

Use only isolated generated-data app processes. Investigate the two-background-log-tabs plus ZIP/log/video scenario before claiming its close-time growth is fixed.

- Distinguish used heap, committed heap, RSS and physical footprint. The earlier roughly 90 MiB NMT thread commitment accompanied about 3 MiB of resident stacks; it is not evidence that removing threads will recover 90 MiB of physical RAM. Similarly, 25–75 MiB heap observations from idle/post-close states do not describe every loaded 1m-row view.
- Capture thread counts, owners, resident stacks and native allocation stacks at comparable lifecycle points. Identify surviving allocations and post-warm-up trends; do not attribute `MALLOC_SMALL` growth to FFmpeg without evidence. Avoid summing overlapping NMT and OS categories.
- Audit frames, frame clones, converters, buffers, decoder queues, audio resources and cancellation paths. Existing grabber release, queue cleanup and decoder-thread bounds must be preserved. Add explicit release only where ownership and API semantics justify it; do not close shared/borrowed frames prematurely.
- Cap worker concurrency only after identifying unnecessary pools or excessive concurrency. Measure latency and throughput after any cap.
- Do not treat JavaCPP `maxPhysicalBytes` as a hard FFmpeg budget. In the pinned JavaCPP 1.5.13 implementation it triggers GC/retries and can throw `OutOfMemoryError`; it does not enforce a universal native-allocation cap. Any experiment must report pauses and failures, and must not replace correct resource ownership. [Pinned implementation](https://github.com/bytedeco/javacpp/blob/1.5.13/src/main/java/org/bytedeco/javacpp/Pointer.java).
- Separately benchmark G1 string deduplication on generated high- and low-repetition messages. Measure backing-storage savings, GC/CPU overhead and operation latency; do not assume every `String` object disappears. [G1 documentation](https://docs.oracle.com/en/java/javase/21/gctuning/garbage-first-g1-garbage-collector1.html).
- Treat AppCDS as a later startup experiment with reproducible archive generation for the packaged JDK/build. Distinguish default CDS from application-class sharing and measure the incremental benefit. [CDS documentation](https://docs.oracle.com/en/java/javase/21/vm/class-data-sharing.html).
- Retain the current heap configuration and collector in the main comparison. Do not bundle flag experiments with representation changes or promise a fixed percentage penalty/benefit from switching collectors.

## 4. Storage contract and invariants

Introduce proposed modules under `src/desktopMain/kotlin/com/indagium/storage/`. Exact names can change during contract review; freeze the public surface before parallel implementation.

| Component | Contract |
|---|---|
| `LogSource` / `SourceHandle` | Source identity, format, lifecycle, metadata and a generation/version |
| `ReadLease` / `ReadSnapshot` | Pins the resources needed by one operation and defines which source version it reads |
| `LogStore` | Reads bounded row batches, scans, resolves IDs/timestamps and owns compact indexes |
| `QueryHandle` | Owns compact match positions, counts and progress; supports paged reads and cancellation |
| `ViewProjection` | Maps displayed positions to row references or group headers without retaining all decoded rows |
| `ViewportPage` | Contains only a bounded number of decoded entries for rendering |
| `RowSelection` | Represents ranges, explicit IDs or a query-based selection without boxed IDs for every selected row |

Required operations: acquire/release a read snapshot; read a bounded page; start/cancel a query; page query results; find an entry or timestamp; stream an export; close a source.

Contract rules:

- Keep source identity, entry ID, physical row position and displayed position distinct. Filtering/folding must not change an entry's identity. Preserve sparse IDs and existing persisted-note semantics.
- Use wide file offsets. Define position overflow behavior explicitly; do not silently narrow offsets to `Int`.
- Never implement file I/O inside `List.get()` on the Compose thread. A temporary compatibility adapter may expose an already loaded viewport page, never a secretly materialized entire file.
- Background work returns immutable bounded pages. Every published result carries its source/query generation so obsolete work cannot repopulate closed or replaced state.
- Closing cancels owned jobs, detaches results and stops new reads. Existing leases finish or cancel before handles are released. Repeated close is safe.
- Reopening creates a fresh lifetime. Shared tabs may share storage, but closing one cannot invalidate another reader.
- UI objects and Compose state must not be held by the storage layer.
- Define source-change detection and error behavior. Test mutation during a scan, not just before open. Do not claim metadata checks detect every possible same-size/same-timestamp rewrite.
- Treat immutable-file reading and append-only capture snapshots as separate modes. Appends must not make an existing snapshot read beyond its published length.

Write a short architecture decision record with concrete types, ownership transitions, error results and cancellation rules before implementation.

## 5. Storage and memory design

### Original files and compact indexes

- Read ordinary logs from their original files. Build only the metadata required for lookup, filtering and grouping.
- Compare a sparse block index plus within-block positions with per-row primitive offsets. Choose using measured memory and lookup cost.
- Use primitive/segmented structures for timestamps, levels, tag IDs and other metadata where useful. Intern repeated values in a source-scoped dictionary when appropriate; inspect existing interning before duplicating it.
- Record index memory explicitly. It grows with the data; a bounded decoded cache does not make total memory constant.
- Start with a 32 MiB shared decoded-cache budget for the experimental backend, not 32 MiB per tab or per query. Account for oversized records, concurrent page construction and scan scratch buffers separately.
- Define an oversized-line policy that preserves content without silently retaining arbitrarily large blocks in the cache. Enforce a bounded number of concurrent scans and decode requests.
- Report estimated cache accounting separately from measured retained heap. Document cases that exceed the normal viewport budget.

### Queries and grouping

- Scan sequential blocks and reuse buffers where safe. Avoid a native/file call and file-stat query for every row. Validate at defined operation boundaries while preserving the source-change contract.
- Preserve current regex, Unicode, timestamp, tag, PID/TID, cancellation and timeout semantics. Do not substitute a regex engine silently.
- Store matching positions in primitive segments, compressed bitmaps or a temporary result store. Broad matches must not allocate millions of boxed numbers or decoded entries.
- Represent stack/manual/sequence groups as ranges and compact membership metadata. Handle nesting, overlaps and sparse membership explicitly; ranges alone cannot represent every group.
- Maintain counts and navigation summaries without two complete ID arrays per displayed pane. Select-all should be a compact selection over a versioned result, not eager enumeration.
- Page both grouped and flat results. Expanding a large group must not decode all its members at once.
- Keep streaming exports/analysis separate from viewport caching so a scan does not evict the entire visible working set or publish every row to Compose.

### ZIP, capture and video sources

- A compressed ZIP entry is not directly seekable like an ordinary log file. Reuse the existing safe extraction lifecycle or extract the selected entry once to an owned temporary source. Do not create an additional parsed-text copy.
- Validate archive paths and extraction limits; close archive streams and clean temporary sources after the last reader. Account for extraction disk use and latency.
- Exercise the user's scenario: two background log tabs, followed by a ZIP containing a log and attached video; close/reopen only the archive tab.
- Keep video decoding under its existing controller ownership. Adapt timestamp/entry lookup through `LogStore` so video alignment does not require a fully decoded log.
- Initially route unsupported formats/capture modes through an explicit legacy backend. Declare the route in diagnostics and coverage reports. Do not count a legacy fallback as a successful new-backend benchmark.

## 6. Work packets, ownership and dependencies

The primary agent assigns actual files before each packet. New paths below are proposed. One integration owner controls `AppState.kt`, `LogViewer.kt`, `BoundLogViewer.kt`, `Filter.kt` and model changes; workers request changes to those files through that owner.

| Packet | Model | Owned scope | Dependency | Exit evidence |
|---|---|---|---|---|
| A: baseline and fixture coverage | Luna xhigh | Dedicated new benchmark scripts/tests; fixture manifests | None | Repeatable generated workloads and complete baseline report |
| A1: lifecycle fixes and native attribution | Terra high; Luna xhigh for isolated harness | Assigned lifecycle/video files and dedicated diagnostic tests; primary separates review changes | A baseline; preserve existing fixes | Close/publication regressions pass; native growth attributed or explicitly unresolved |
| A2: compact entries | Terra high | Model/parser and assigned consumer files; regression tests | A baseline, A1 investigation report | Separate metadata/timestamp measurements, exact semantics and per-format acceptance |
| A3: optional JVM experiments | Luna xhigh; primary reviews | Dedicated launch/measurement scripts only | A baseline; scheduled separately from A2 | Independent deduplication/AppCDS results; no unmeasured default flag changes |
| B: contracts and reference adapter | Terra high | Storage interfaces, ADR, legacy adapter | A2 decision and primary-approved contracts | Source/lifetime contracts and equivalence tests |
| C: compact Kotlin file store | Terra high | File store, indexes, cache, reader leases and unit tests | B | Correct bounded reads; explicit close/mutation behavior |
| D: query and result engine | Terra high | Query/result modules and their tests | B; C for integration | Streaming filters and compact matches equivalent to legacy |
| E: grouped view projection | Terra high | New projection module and tests | B, D | Flat and grouped navigation/selection equivalence |
| F: real application integration | Terra high | Shared integration files listed above | C, D, E | One generated file opened through the real viewer end to end |
| G: archive, restoration and capture adapters | Terra high | Assigned adapter files; shared changes through F owner | F | ZIP/video scenario and persistence/lifecycle parity |
| H: independent regressions and reporting | Luna xhigh; primary reviews | Dedicated test/report files | Approved contracts; final integration for runs | Reproducible before/after report with failures retained |
| I: optional Rust engine comparison | Terra high | Isolated native module and Kotlin bridge | Kotlin path works; bottleneck evidence | Same-contract comparison; explicit adoption decision |

Begin with A, then A1 and A2; A1 need not prove all native growth fixed before A2, but must report the evidence and remaining uncertainty. Contract design may proceed during A1/A2, but freeze B after the compact-entry decision. A3 is optional and must not delay the main work. After B is frozen, C and D can work against contracts/test doubles in separate files. Integrate C+D before E+F. Do not give several agents simultaneous ownership of the large existing UI/state files. Run measurements serially after relevant builds and tests finish.

### Milestones

1. **Baseline:** preserve existing source/binary state without losing uncommitted work. Record revision, diff identity, artifacts, JVM and fixture hashes. If creating a new branch, branch from the actual current state and follow repository/workspace rules.
   Before the storage vertical slice, complete A1's lifecycle review/native report and A2's compact-entry acceptance decision. Deliver accepted changes separately; rejected variants remain documented experiments.
2. **Vertical slice:** ordinary generated log opens through `LogStore`, renders paged rows and groups, filters, selects, exports and closes correctly. Use an internal opt-in switch; default behavior remains legacy until gates pass.
3. **Workflow parity:** add ZIP/log/video, restored sessions and inactive residency. Complete the support matrix for other formats, merges, search, notes, analysis, compare panes and live append. Unimplemented cases must be explicit.
4. **Optimization and proof:** profile remaining CPU/allocation costs, fix measured bottlenecks and run the acceptance matrix. An interface-only refactor or a lower model heap is not completion.
5. **Promotion decision:** enable only supported, passing routes. Remove accidental eager adapters from those routes. Leave failing routes explicitly experimental and state why.

## 7. Required tests

Use the legacy behavior as an oracle plus independent expected outcomes; comparing two implementations that share the same new bug is insufficient.

- Parsing: Unicode, BOM, CRLF, skipped lines, malformed UTF-8, long records and missing final newline.
- Compact entries: exact timestamp round-trips/fallbacks, precision/date rollover, video alignment, metadata-heavy DLT/merged rows, constructor/copy/equality compatibility, persistence and allocation behavior of repeated timestamp access.
- Reading: block boundaries, random pages, end-of-file, sparse IDs, changed/replaced/truncated sources and cancellation during reads.
- Queries: all/partial/zero matches, regex errors/timeouts, rapid replacement, random access into large match sets, cancellation and version changes.
- Grouping: one stack group inside a large file, dense stacks, nested sequences/manual groups, collapse/expand, jump-to-entry and large expanded groups.
- Interaction: range/select-all, copy/export, notes references, search next/previous, minimap and filtered/unfiltered panes. Verify no hidden full-file materialization.
- Lifecycle: close while querying/exporting, duplicate readers, final unpin, close/reopen, late result publication, background unloading/restoration and bounded handles/jobs/caches.
- Archives/video: multiple entries, extraction cleanup, generated codecs/resolutions, actual decoded frame readiness and two background logs remaining open.
- Appends: immutable reader snapshots, all/partial/zero passing batches, truncation/rotation, query continuation and cancellation under load.

Retain the existing cache-invalidation race tests and closed-row pin tests. Run focused checks after each functional packet; run `desktopTest` and repository checks after integration. Reproduce and list baseline failures separately. Do not repeatedly run the full suite for documentation-only changes.

## 8. Measurement and acceptance

These are **proposed targets, not promised gains**. Preserve the original 5% timing gate unless the user explicitly changes the tradeoff.

### Controlled scenarios

- Generated 100k, 1m and 10m rows with realistic variable message lengths, repeated tags, timestamps and Unicode.
- Separate flat, one-stack-among-many-rows, dense-group and broad-filter scenarios. Never erase group metadata for an application-level result.
- Clean startup and synthetic restored startup at first frame, 10 seconds and 60 seconds.
- Open to usable viewport, open to complete query/index readiness, cold and warm filters, random scroll, group expansion, tab switching and close.
- The two-background-logs plus ZIP/log/video scenario, including at least 20 repeated cycles for retention investigation.

Use three independent launches only for preliminary screening. Before promotion, run at least seven independent launches per baseline/candidate acceptance scenario, report medians, individual values and dispersion, and alternate baseline/candidate order. Predefine any additional run count before inspecting its results; do not stop when a favorable median appears. Small regressions from three samples are neither proven harmless noise nor definitive evidence of a persistent slowdown. If uncertainty overlaps an acceptance boundary, report the result as inconclusive and investigate before promotion. Match JVM, flags, build mode, window size, settings, fixture and workload semantics. Record CPU, RAM, OS, JDK, codec and source/build identifiers. A fresh JVM is not a cold filesystem cache; report the actual cache conditions.

Measure used and committed heap, RSS, macOS physical footprint where available, allocation/GC activity, operation latency, frame times, index/cache sizes, open handles, active readers and surviving query entries. Attribute the app PID only. Keep ordinary, forced-GC, NMT and allocation-profile runs separate.

### Go/no-go gates

| Area | Target / rule |
|---|---|
| Correctness | Required route behaves equivalently and passes focused regressions; no new unexplained suite failures |
| Compact-entry phase | Measurable retained-memory saving in real flat/grouped views, exact timestamp/metadata semantics and no supported-format regression left unexplained; the 50% storage-redesign target below is not an acceptance requirement for this smaller phase |
| Large-log subsystem memory | At least 50% less retained log-subsystem memory at 1m and 10m rows, including grouped cases |
| Whole-app usefulness | Target at least 25% lower loaded-app physical footprint for the 10m log-only scenario; report 1m and video separately. Do not substitute a subsystem result for this target |
| Startup/open/filter | No more than 5% median slowdown on comparable milestones; count complete readiness as well as first viewport |
| Scroll | Investigate p95 regressions over 10%; do not promote an unresolved interaction regression |
| Close/reopen | No stale closed-source/query entries; bounded handles/readers; document post-warm-up heap and physical-footprint trends over 20 cycles |
| Cache and transient memory | Enforced cache limits, bounded concurrency and explicit oversized-record behavior; measure peaks, not only post-GC heap |
| Native backend | Must beat the compact Kotlin implementation meaningfully on a measured bottleneck without failing correctness or the agreed timing gates |

If a gate fails, investigate and retain the experimental route. Do not hide losses through smaller inputs, disabled grouping, early timing endpoints, different heap flags or selecting only favorable launches. If the targets conflict, document the measured tradeoff and obtain an explicit scope/acceptance decision before promotion.

Do not assume storage work fixes idle/video footprint. If loaded-log savings succeed but native graphics/video remains high, report that as a distinct remaining problem with its own evidence.

## 9. Optional Rust phase

Start only when profiling and the working Kotlin route establish what Rust is intended to improve. Do not build a second parser solely because the Kotlin prototype's first implementation was slow.

Keep the Kotlin-facing `LogStore` contract. The native engine should own its indexes, scans and bounded decoded cache. Return bounded batches or lifetime-controlled buffers, never millions of Java strings alongside a second native copy of the same data.

Design and test a versioned C-compatible boundary: source/query handles, primitive metadata, UTF-8 payloads with explicit lengths, cancellation, typed errors and buffer release. Kotlin coroutines must cancel native jobs safely. Prevent exceptions/panics from crossing the boundary and guard against callbacks after close. Avoid one FFI call per row.

Require lifecycle tests, supported-platform builds, native memory/handle diagnostics and packaging checks. Compare total process memory, including mapped pages and native allocations, against compact Kotlin under identical semantics. A lower Java heap alone does not establish a native win.

Choose C++ instead only with a written rationale such as a suitable existing C++ component and maintained bindings. Do not pursue Rust and C++ implementations in parallel without a concrete comparison question.

## 10. Subagent prompt template

```text
Implement packet <ID> from docs/LOG_STORAGE_REDESIGN_PLAN.md.
Model: <gpt-6.1-terra / gpt-6-luna>; effort: <high / xhigh>.

Objective: <one concrete result>.
Own only: <explicit files/modules>.
Dependencies and approved contracts: <paths/revisions>.
Out of scope: <adjacent changes owned by others>.

You are not alone in this codebase. Preserve existing POC and user edits.
Coordinate shared-file changes with the integration owner.
Use only generated data and isolated app homes; never inspect user data.
Do not compile/test during a scheduled benchmark without coordination.

Implement the behavior, run the agreed focused checks, and report:
1. Changed files and behavior.
2. Ownership, cancellation and memory bounds.
3. Tests actually run and exact failures.
4. Unsupported cases and fallback behavior.
5. Evidence for any performance claim; otherwise mark it unmeasured.

Do not declare the overall RAM objective complete from this packet alone.
```

## 11. Completion report

Deliver a runnable branch, architecture/API documentation, a support matrix, generated fixture commands and manifests, exact benchmark commands, machine/JDK details, raw per-launch results and a concise before/after table. Include lifecycle/behavior tests, repository check status, remaining limitations and the default/experimental routing decision.

The primary agent must review the final diff and independently verify the memory claims. State whether the original user-visible problem improved, by how much, and which scenarios still show little or no improvement. Preserve that distinction even when individual optimizations or unit tests succeed.
