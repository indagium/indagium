# Large-file (>1GB) performance: investigation & fix plan

Date: 2026-07-03 · Branch: `feat/fixes_02_07_26`

## Problem

Opening and filtering Android logcat files larger than ~1GB freezes and stalls openLog badly;
klogg handles the same files smoothly.

## What the code actually does today (verified)

A 1GB threadtime logcat is roughly 8–12M lines. The pipeline materializes everything:

1. **Parse** (`LogParser.parseLogcatLines`): streams lines but `.toList()`s ~10M `LogEntry`
   objects (3 String fields each, 4 regex `matchEntire` attempts per line). Runs on
   `Dispatchers.IO` — slow (tens of seconds) but not a freeze.
2. **`mkTab`** builds `rmap: Map<Int, LogEntry>` — a `HashMap` duplicating the whole file as
   boxed-`Int` → entry pairs (~64–90 bytes/entry of pure overhead ≈ **0.7–1GB wasted** at 10M
   lines), plus `buildLogAnalysis` (tag counts, stack-trace groups, crash sites).
3. **`computeItems`** re-derives the full `List<LogItem>` on every filter/expand/collapse change.

### Why it still freezes despite `largeFileMode`

`LogTab.largeFileMode` (files ≥ 512MB) already routes `LogViewer`'s `computeItems` through
`LaunchedEffect` + `Dispatchers.Default` with a loading line (`rememberComputedLogItems`,
LogViewer.kt:72). So the headline "computeItems runs synchronously in `remember`" is only true
for files *under* 512MB. The >1GB freezes come from what's left:

- **F1 — Startup freeze (worst single offender).** `AppState.init` → `restoreAutosave()` →
  `tabFromToken()` calls `parseLogcat(sourceFile)` **synchronously** for every saved tab
  (AppState.kt:2183). `AppState` is constructed inside `remember { }` during the first
  composition (Main.kt:40) — with a 1GB tab open at last quit, the window doesn't even appear
  for minutes, on every launch.
- **F2 — Per-recomposition O(n) set build.** LogViewer.kt:357:
  `SideEffect { boundsMap.keys.retainAll(visibleIds.toSet()) }` builds a ~10M boxed-Integer
  `HashSet` on **every recomposition** of the list panel (every click, selection change,
  hover) — hundreds of ms each, on the UI thread, constantly.
- **F3 — UI-thread O(n) list builds when items change.** `visibleIds = items.map(...)`
  (10M-element boxed list) and `visCnt = items.count {...}` run in `remember` on the UI thread.
- **F4 — Synchronous `computeItems` callers not covered by largeFileMode:**
  `AppState.selRow(range=true)` (shift-click), `selectAll`, and `expandAll` →
  `visibleExpandableGroupIds` which loops `computeItems` repeatedly (including
  `applyFilter=false` full-file passes) — all on the UI thread.
- **F5 — GC pressure making *everything* stall.** Data at rest: entries + `rmap` +
  analysis ≈ 3.5–5GB at 10M lines. Per `computeItems` run (every debounced keystroke):
  `dataIds` boxed `HashSet` of all ids, `seqChildIds`/`stackClaimedIds`/`skipIds` boxed sets,
  a fresh 10M-element `List<LogItem.Row>`, then `visibleIds` again. Hundreds of MB of garbage
  per keystroke stalls all threads, including the UI thread — this is why the app feels frozen
  even though the compute itself runs on `Dispatchers.Default`.
- **F6 — Accidental O(n·g).** `seqGroups.find { it.rid == entry.id }` and `stackGroups.find`
  run per entry inside `computeItems`' main loop; a crash-heavy 10M-line log with a few hundred
  stack groups → billions of comparisons per filter change.
- **F7 — 512MB threshold.** Files between ~100MB and 512MB take the fully-synchronous
  `remember` path: seconds-long UI freeze per keystroke.

## klogg comparison — what we deliberately do NOT port

klogg never materializes parsed line objects: it indexes byte offsets in the background, keeps
the file on disk, realizes text only for the visible viewport/search, and its "filter" is a
single regex. openLog's model is fundamentally richer: id-based selection/annotations,
tag/level/PID parsed-field filters, user sequences, stack-trace folding, manual collapse —
all assuming random access to parsed `LogEntry`s. Porting klogg's index model means rewriting
the data model, every filter, autosave and annotations. **Not worth the risk for the 1–2GB
target**: after the fixes below, a 1.5GB file fits comfortably in heap and every heavy pass is
off the UI thread. Revisit offset-indexing only if 4GB+ files become a requirement.

Also **not** changing: the `mutableStateOf`-writable-from-any-thread convention (kept; background
apply uses the existing `stateLock` precedent from `appendTailedLines`), filtering semantics,
or any feature behavior.

## Fix plan (sized to the bottlenecks above)

**A. Responsiveness**
1. Async autosave restore (fixes F1): restore tab *shells* (filename/filter/annotations)
   synchronously — tests only assert metadata — and parse file content on `ioScope` per tab
   with the existing loading indicator + `activeLoads` cancellation.
2. Move `visibleIds`/row-count into the background computation result (`ComputedLogItems`);
   prune `boundsMap` only when items actually change (fixes F2, F3).
3. Selection/expand ops reuse the already-computed visible ids (passed down from LogViewer)
   instead of recomputing; `expandAll` runs on `Dispatchers.Default` for large tabs (fixes F4).
4. `LARGE_FILE_MODE_BYTES`: 512MB → 64MB (fixes F7).

**B. Memory (fixes F5, F6)**
5. Replace `rmap` `HashMap` with a `Map<Int, LogEntry>` view backed by binary search over
   `logData` — valid because ids are strictly increasing in every construction path (parser,
   `mergeLogs` re-ids sequentially, tailing appends max+1). Saves ~1GB at 10M lines; all
   `rmap[...]` call sites keep working unchanged.
6. Intern tag strings during parse (a handful of distinct tags across millions of lines).
7. `computeItems`: `java.util.BitSet` id-sets instead of boxed `HashSet`s; `rid → group`
   HashMaps instead of per-entry linear `find`; skip building id-sets when unused.

**C. Time-to-open**
8. Fast hand-rolled threadtime line parser with regex fallback (threadtime is the only format
   realistically seen at >1GB); other formats keep the regex path.
9. Raise packaged/dev-run heap ceiling (`-XX:MaxRAMPercentage`) so a 1.5GB file doesn't run at
   the edge of the default 25%-of-RAM cap on smaller machines.

**D. Measurement**
- Synthetic ~1.5GB threadtime fixture (realistic tags/levels/PIDs + periodic exception blocks),
  generated by script — not committed.
- A system-property-gated perf harness (skipped in normal `desktopTest` runs) measuring:
  parse time, tab-build time, heap after load, `computeItems` latency for a keyword-filter
  change and for unfiltered pass. Before/after numbers reported below.

## Results

Fixture: synthetic threadtime logcat, 1492MB / 10,606,636 lines, ~48 tags, 265 exception
blocks + 11 ANRs. Harness: `LargeFilePerfHarness` —

```bash
./gradlew desktopTest --tests "com.openlog.LargeFilePerfHarness" \
    -Dopenlog.perf.file=/path/to/fixture.log -Dopenlog.perf.dense=1
```

| Measurement | Before | After |
|---|---|---|
| `parseLogcat` (10.6M lines) | 7.3s | 3.7–4.1s |
| `mkTab` (rmap + analysis) | 12.6s | 13.5s (rmap now free; all analysis) |
| Retained heap after load | **3757MB** | **2539MB** (−1.2GB) |
| `computeItems`, no filter | **8.4s** | **0.8–1.0s** |
| `computeItems`, keyword filter | 3.9s | 3.9–4.1s (but ~2GB/keystroke less GC churn) |
| `computeItems`, expand one group | 8.0s | 1.0s |
| `computeItems`, rare sequence def | 18.1s | 6.3s |
| `computeItems`, dense sequence def (~530k matches) | **never completes** (O(c²)) | **6.2s** |

Equally important, the passes that used to run on the UI thread no longer do:
- app launch with a large tab restored: window appears immediately, content fills in
  (previously: full synchronous re-parse inside the first composition);
- the per-recomposition 10M-element `visibleIds.toSet()` in LogViewer is gone;
- shift-click range select / select-all / expand-all no longer recompute the item list
  synchronously;
- files ≥64MB (was ≥512MB) get the async computeItems path.

End-to-end smoke test: app launched via `./gradlew desktopRun -Dopenlog.debugControl=8991
-Dopenlog.run.home=<tmp>`, fixture opened through the MCP control server — 10.6M entries
loaded, keyword filter returned 529,635 rows, 276 crash sites detected, tab closed cleanly.

## Round 2: load speed + expand/collapse latency (2026-07-03)

Per-phase profile showed the ~19s load was: `computeStackTraceGroups` **14.0s**, parse 5.0s,
crash sites + tag counts 0.7s; and that an expand/collapse click re-ran the full filter pass
(4.0s with a keyword filter) and the sequence scan (6.6s with a def enabled) even though only
`tab.expanded` changed. Fixes:

1. **StackTraceComputer `MsgScanner`**: one left-to-right pass per message computing every
   substring gate (was up to six separate contains() scans, several case-insensitive, per
   line), plus a verb gate on the prelude regex. 14.0s → **6.2–6.7s**.
2. **computeItems memoization** (`TabComputeCache`, one slot per tab × applyFilter): the
   filtered entry list, sequence groups, filtered stack groups, swallowed-id owner map and
   sequence-child BitSet are all invariant under `expanded` and now survive expand/collapse
   recomputes. Measured per-click recompute after the filter-establishing pass:
   keyword filter **4018ms → 25–65ms**; sequence def enabled **~6.6s → 0.45s**; no filter
   1.0s → 0.4–0.7s (pure item-materialization floor at 10.6M rows).
3. **Pointer-walk** over rid-sorted group lists in the item loop instead of two HashMap
   lookups per entry.
4. **Loading-line grace (250ms)** in `rememberComputedLogItems`: sub-grace recomputes swap in
   without ever flashing the loading indicator; the previous items stay on screen either way.
5. Tabs-list writes (upTab + structural edits) now all synchronize on `stateLock` — an
   unsynchronized UI-thread `tabs = tabs + t` racing a background restore/tailing fill could
   lose either update (surfaced as a flaky restore test once autosave restore became async).

### Negative result: parallel parsing (tried, measured, removed)

A byte-range-chunked parallel file parse and a batch-pipeline variant for archive streams
(workers parse, sequential id fix-up after) were implemented and benchmarked same-JVM against
the sequential parser: **parallel was ~1.7× slower** (8.2s vs 4.2s file; archive pipeline
similarly worse). Parsing is allocation/GC-bound, not CPU-bound — extra threads contend on the
collector and the id-rewrite pass doubles allocations. Don't re-attempt without first reducing
per-entry allocation (e.g. flyweight/off-heap entries); the archive path shares
`parseLogcatLines`, so it inherits whatever the sequential parser gains instead.

Load totals for a 1.5GB file/zip entry: ~19s → **~10.5s** (parse 3.3–5.0s variance-bound +
analysis 6.2–7.1s; zip adds <1s of decompression).

## Round 3: crash-block splice + deferred analysis (2026-07-04)

1. **Stack-group toggle splice** (`spliceStackToggle`, Filter.kt): the round-2 memoization left
   one cost on every expand/collapse — re-materializing the full item list (~0.4–0.7s at 10.6M
   unfiltered rows). A stack-trace ("crash") block's rendered footprint is strictly local, so
   toggling exactly one stack gid now copies the cached item list and splices the member rows
   in/out (reference arraycopy, no per-item allocation). Any condition the splice can't prove —
   sequence/manual toggles, multi-gid changes, header not visible — falls back to the full
   rebuild, i.e. exactly the previous behavior. Equivalence against a cache-invalidated fresh
   compute is covered by `ComputeItemsSpliceTest` (top-level, filtered, and
   nested-inside-expanded-sequence cases). Measured: crash-block expand on 10.6M unfiltered
   rows **~650ms → 38ms** (sequence-gid toggles still take the full-rebuild fallback, ~0.6s).
2. **Deferred stack/crash analysis** (`LogAnalysis.pending`): tabs now publish as soon as the
   parse finishes; the ~6s stack/crash analysis runs afterwards in the same background job
   (cancelled with the tab) and fills in via `upTab`. Time-to-visible-tab for a 1.5GB file drops
   from ~10.5s to the parse time (~3.5–5s); folding and the crash panel appear when the
   analysis lands (`tab.analysis` is a compute key, so the item list refreshes itself). Rows
   render unfolded in the interim; the crash panel shows "Analyzing crashes…". Applies to plain
   files, archive entries, session restore, and merges. Guarded traps: `computeItems` and
   FilterPanel's crash-sites fallback must not run the full scan synchronously while pending —
   both check the flag (the FilterPanel one would have been a ~6s freeze during composition).
   Tag counts are computed with the parse (cheap) so the filter panel is complete immediately.

## Future work (explicitly out of scope here)

- klogg-style byte-offset indexing with on-demand line realization — only worth the data-model
  rewrite if 4GB+ files become a target.
- Keyboard navigation helpers (`handleNavKey`/`handleSelKey`) still do O(n) index scans per
  keypress on the item list; noticeable but not freeze-grade.
- Tailing recomputes the full analysis per flush (`appendTailedLines`); fine for growing files,
  costly if someone tails a pre-existing multi-GB file.
