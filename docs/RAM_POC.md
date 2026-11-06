# Synthetic RAM POC

At 10m rows, the compact Kotlin projection reduced total flat model heap from 2,482.40 to 2,056.20 MiB, approximately 17%, and reduced projection time from 360 to 93 ms, approximately 3.9 times faster.

The change remains a POC: the 100k clean first draw was 6.6% slower and its model filter was 4 ms (about 18%) slower, both missing the 5% regression gates. Retain the existing JVM settings and macOS interop default. The test-only direct-file reader used about 91% less measured model heap at 10m rows but its sequential search was roughly 12 times slower; investigate batching before considering replacement. These experiments used synthetic fixtures and isolated test homes.

Measurements were taken on November 4, 2026, comparing baseline commit `8a926501` from `codex/inactive-tab-memory` with the `codex/ram-poc` worktree. The initial branch was clean before the POC work.

To reproduce, select JDK 21, generate the fixtures and export the Gradle classpath as shown below. Preserve a pristine baseline before modifying main sources. The baseline/current UI commands run three clean and restored launches per initial-heap setting; model, direct-reader and diagnostic commands are listed in their respective sections.

All fixtures are generated data. `scripts/ram-poc-fixtures.py` writes only to the checkout's `build/ram-poc/fixtures`, rejects symlinked path components, and prints row count, byte count and SHA-256. Generate the required sizes with:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
"$JAVA_HOME/bin/java" -version
python3 scripts/ram-poc-fixtures.py 100k 1m 10m
./gradlew -I scripts/ram-poc-classpath.gradle ramPocExportClasspath
```

The rows use deterministic logcat threadtime syntax, variable length UTF-8 messages, a roughly 10% keyword pattern (with sparse exceptions), and sparse synthetic stack messages. A 1,000-row switch fixture is also generated. No existing app logs, home, captures or sessions are inputs. Keep fixture hashes with results. A 10M-row eager parse can require substantial memory.

The comparison needs the pristine main classes and resources saved before applying changes at `build/ram-poc/baseline/classes/kotlin/desktop/main` and `build/ram-poc/baseline/processedResources/desktop/main`. `build/ram-poc/classpath.json` must contain Gradle-resolved `test` and `main` arrays of absolute classpath entries; compile the benchmark test sources against the current app before running. Baseline and current launches use the same benchmark classes and dependency classpath, swapping only main classes/resources. Record the source revisions, Java version, machine, fixture hash and flags when reporting comparisons.

```sh
python3 scripts/ram-poc-run.py --version baseline --fixture 100k
python3 scripts/ram-poc-run.py --version current --fixture 100k
```

Defaults cover clean and synthetic restored state, default and 32/64/128 MiB initial heap, and three launches per cell. Narrow a smoke run with `--heaps default --modes clean --repetitions 1`. The restore setup opens the generated fixture and writes an autosave in a fresh temporary home; the measured restore launches a second process using that home. The process receives a minimal environment and isolated user home, preferences and app storage. Results and raw logs are under a new `build/ram-poc/results/` directory. RSS CSV is sampled once per second with `ps`; this is resident set size, not macOS physical footprint. `medians.json` reports per-cell medians and counts from process output; preserve raw observations and report failures rather than silently dropping them.

The harness is a separate 1440×900 Compose window containing the real `App` and `AppState`; it is **not production Main startup**. `first_draw` is emitted after the first Compose draw callback; `launch_elapsed_ms` uses the parent wall clock just before spawning the JVM and is approximate, while `elapsed_ms` starts in the harness main function. The 10s and 60s samples are scheduled relative to harness main. Heap used/committed and GC counters are JVM MXBean/Runtime values without forced collection. `open_rows_loaded` means the tab has nonempty loaded rows, not that all analysis or rendering is finished. Filter and close samples are explicitly delayed observations, not interaction latency. The harness accepts the license only inside its generated test session to clear the modal; this synthetic acceptance is saved for the restore fixture. It requests a scroll on the real log list, verifies the target first-visible row, and records 60 frame intervals. A second generated tab is opened and the harness switches back; its next-draw timing measures the next window draw, not proof all rendering has completed.

`RamPocBenchmark.modelWorkload` is an opt-in model-only test selected by `-Dindagium.ram.fixture=100k` (or `1m`/`10m`); it measures parse, tab construction and filter computation. It reports current-thread allocation only, not allocations from worker threads. A separate diagnostic run with `-Dindagium.ram.forceGc=true` calls GC before each sample; never combine these values with ordinary runs. NMT and physical-footprint diagnostics are not collected by the ordinary runner. Use `--diagnostic forcegc` for forced-GC samples, or `--diagnostic nmt` for a separate NMT run. The latter records `jcmd VM.native_memory summary` at approximately 10 and 60 seconds and attempts macOS `footprint` for the benchmark PID. Diagnostic overhead and forced collections change behavior: do not pool these results with ordinary runs. The direct-file POC and eager app workload have different semantics; model results alone do not establish application startup or UI savings.

## Measured UI harness matrix

The 100k fixture matrix used the same JDK 21 machine described below, with three fresh JVMs per cell. Values are medians; scroll p95 is the median of the three individual runs’ 60-frame p95 values. First draw is measured approximately from the parent launch clock. RSS at 60 seconds is a process sample near 60 seconds; peak RSS is the maximum sampled over the **whole scenario**, including interactions, rather than a startup measurement. The open timing is reported only for clean runs, which open the fixture after the 60-second startup sample. Restore runs already load their saved synthetic fixture.

| Version | Initial heap | State | First draw (ms) | RSS at 60s (MiB) | Scenario peak RSS (MiB) | Open loaded rows (ms) | Scroll p95 (µs) |
|---|---|---|---:|---:|---:|---:|---:|
| Baseline | Default | Clean | 1,312 | 318.1 | 543.4 | 493 | 18,514 |
| Baseline | Default | Restore | 849 | 529.7 | 529.8 | — | 17,902 |
| Current | Default | Clean | 1,399 | 311.7 | 557.5 | 468 | 18,835 |
| Current | Default | Restore | 821 | 502.6 | 502.6 | — | 17,347 |
| Current | 32 MiB | Clean | 1,401 | 320.2 | 462.5 | 511 | 17,188 |
| Current | 32 MiB | Restore | 859 | 444.0 | 462.6 | — | 17,445 |
| Current | 64 MiB | Clean | 1,452 | 330.2 | 450.0 | 495 | 17,150 |
| Current | 64 MiB | Restore | 842 | 420.8 | 448.6 | — | 16,905 |
| Current | 128 MiB | Clean | 1,366 | 356.3 | 530.5 | 505 | 18,544 |
| Current | 128 MiB | Restore | 861 | 525.7 | 592.3 | — | 18,277 |

**Decision: retain the existing JVM flags.** The initial-heap settings do not show a consistent empty-startup footprint improvement while meeting the 5% performance gate. The 32 and 64 MiB settings reduce scenario peaks but have slower clean startup or open measurements or higher clean idle RSS; 128 MiB retains more clean idle memory. The current default clean first draw is about 6.6% slower than baseline, also missing the startup gate. Whole-scenario peaks must not be described as empty-startup savings. These results do not promote the POC.

One current default-clean run took 19.2 seconds for 60 scroll frames, with a 315,282 µs median and 325,285 µs p95. It remains in the raw data and the table’s three-run median; the other two clean runs and restored runs had frame medians near 16.7 ms. Later diagnostic repeats did not reproduce the outlier; its cause remains unknown.

The baseline and current raw directories are `build/ram-poc/results/baseline-100k-ui-ordinary-sdvs7ma2` and `build/ram-poc/results/current-100k-ui-ordinary-e1xm19t_`. A fresh JVM is not a cold OS filesystem cache: cache state was uncontrolled, and fixture hashing and indexing may warm it. JVM heap used, committed heap, process RSS and macOS physical footprint measure different things and are not interchangeable. The direct-reader and profiling observations below are separate experiments, not conclusions from this UI matrix.

## Measured model results

These results use Temurin 21.0.11+10 (arm64), macOS 26.6.2, 24 GiB RAM and 10 CPUs. Each value is the median of three fresh JVM launches using the default initial heap. Timing comes from ordinary runs; retained heap comes from separate forced-GC diagnostic runs and must not be compared as though collected in the same launches. Total flat heap is the used heap after projection; projection delta is the difference between the median used-heap samples immediately after and before projection. The projection excludes stack folding as described below.

| Rows | Flat projection, baseline → current | Keyword filter, baseline → current | Total flat heap, baseline → current | Projection heap delta, baseline → current |
|---:|---:|---:|---:|---:|
| 100k | 23 → 13 ms | 22 → 26 ms | 29.72 → 25.68 MiB | 4.070 → 0.032 MiB |
| 1m | 50 → 23 ms | 175 → 156 ms | 259.38 → 213.14 MiB | 46.697 → 0.031 MiB |
| 10m | 360 → 93 ms | 1,527 → 1,446 ms | 2,482.40 → 2,056.20 MiB | 425.389 → 0.031 MiB |

The 100k cold keyword-filter median increased by 4 ms, approximately 18%, exceeding the 5% regression gate. These model results do not satisfy all promotion gates; this remains a POC. The 10m forced-GC total includes the rest of the eager model, not only the projection. Three observations per cell are a small sample and the low-duration filter difference should be interpreted with that limitation.

Raw result directories under `build/ram-poc/results/` (ordinary / forced-GC) are:

| Rows | Baseline | Current |
|---:|---|---|
| 100k | `baseline-100k-model-ordinary-u1i3q3n_` / `baseline-100k-model-forcegc-40hdrxeo` | `current-100k-model-ordinary-vogfse2c` / `current-100k-model-forcegc-5x6hug_w` |
| 1m | `baseline-1m-model-ordinary-wa5xohjj` / `baseline-1m-model-forcegc-vkoo4itd` | `current-1m-model-ordinary-zlhaqfy9` / `current-1m-model-forcegc-oo8k6pi3` |
| 10m | `baseline-10m-model-ordinary-pzrlqrsh` / `baseline-10m-model-forcegc-7b4x4a8d` | `current-10m-model-ordinary-6hh6plqj` / `current-10m-model-forcegc-lpia1r60` |

Earlier JDK 17 smoke runs and an interrupted baseline UI run are excluded from these results.

The full test run reported 7,943 tests: 7,933 passed, five skipped, and five failed. All five failures were in `LogViewTilingUiTest` and the same five were reproduced in an 82-test baseline run. The targeted run passed all 507 tests, including the refined sparse-buffer cases; the final harness run passed 12 tests. The reported lint and detekt findings were in unchanged Seq3 files, including 13 detekt findings.

## Model and direct-file experiments

Use the JDK 21 installation explicitly for Gradle and the runner; the shell default on the measured machine was JDK 17:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
"$JAVA_HOME/bin/java" -version
./gradlew -I scripts/ram-poc-classpath.gradle ramPocExportClasspath
```

The last command writes the resolved classpath. Before changing production source, compile the baseline, then copy `build/classes/kotlin/desktop/main` and `build/processedResources/desktop/main` into the corresponding paths under `build/ram-poc/baseline/`; compile the benchmark test sources and optimized app separately afterward. Keep those snapshots intact throughout the comparison.

```sh
python3 scripts/ram-poc-run.py --version baseline --workload model --fixture 100k --heaps default
python3 scripts/ram-poc-run.py --version current --workload model --fixture 100k --heaps default
python3 scripts/ram-poc-run.py --version current --workload direct --fixture 100k --heaps default
```

Repeat for `1m` and `10m` and `--diagnostic forcegc` separately. Each cell defaults to three fresh JVMs. The model experiment intentionally removes stack folding for the flat row projection measurement; the two forced-GC samples bracketing it approximate retained projection heap while the result is live. The after-scope sample invalidates the compute cache before references leave scope. The direct-file experiment measures indexing, two identical viewport passes using fixed-seed uniformly selected positions across the fixture (the first starts with an empty process cache) and sequential keyword matching, then closes the store. Its index, rows, and cache are a test-only experiment with a narrower feature set than the application. Times for that reader are not equivalent to complete application open/filter or rendering times. Cumulative thread-allocation values cover only the benchmark thread. RSS medians at process 10/60 seconds are only provided for processes that remain alive long enough.

For allocation-profile attribution, run the same command with `--diagnostic jfr`. Each process writes its own `.jfr` recording in the result directory using the JDK's `profile` settings; inspect it with Java Mission Control or `jfr summary`/`jfr print`. Allocation events are sampled, so use them to identify likely allocation sites rather than as exact allocation totals. JFR has overhead and its results must remain separate from ordinary, forced-GC and NMT measurements. The metadata records the Java version and exact argument list for each child, including preparation launches. Each benchmark child has a 900-second limit; diagnostic subprocesses have 30-second limits. On failure or timeout the runner terminates only the child it started.

## Supplemental production Main diagnostic

`--workload main` launches the production `MainKt` without a file argument, using the resolved main classpath and a fresh synthetic home and preferences for each process. Normal app storage, migration and save-root paths resolve inside that fresh temporary home; the environment excludes platform data-directory overrides. No debug control server is enabled. No fixture or existing application data is opened. The first-install license dialog is expected. The runner samples RSS, waits 65 seconds, and intentionally terminates only that child; an earlier exit is reported as a failure. It has no first-draw hook and does not report first-frame timing. With `--diagnostic nmt`, it also requests the existing NMT and macOS footprint probes at approximately 10 and 60 seconds.

```sh
python3 scripts/ram-poc-run.py --version current --workload main --heaps default --repetitions 1 --diagnostic nmt
python3 scripts/ram-poc-run.py --version current --workload main --heaps default --repetitions 1 --diagnostic nmt --interop-blending false
```

The second command is a narrowly scoped diagnostic comparison: `--interop-blending false` passes `-Dcompose.interop.blending=false` to Main, while `default` leaves the property unset for Main to configure normally. This is not a proposed application default: the native macOS mirror underlay depends on the enabled setting. The setting is only accepted for the Main diagnostic, is recorded in metadata, and cannot be used to alter the UI harness grid. Forced-GC mode is unavailable for Main because it has no sampling hook. Main and the harness differ in startup setup and visible content, so their measurements are attribution evidence rather than a controlled first-frame comparison.

## Direct-file experiment results

Each cell below is the median of three fresh JVMs. Index, viewport and sequential-filter times come from ordinary runs; heap values come from separate forced-GC runs. Each viewport pass reads 100 fixed-seed, uniformly selected positions, 50 rows per position. The first and repeat passes use identical positions. “Cold” means the first viewport pass in a new process, not a cold operating-system filesystem cache.

| Rows | Index (ms) | First viewport pass (ms) | Repeat viewport pass (ms) | Sequential keyword filter (ms) | Heap after sequential filter (MiB) | Heap after close (MiB) |
|---:|---:|---:|---:|---:|---:|---:|
| 100k | 49 | 59 | 8 | 202 | 22.77 | 5.37 |
| 1m | 324 | 54 | 8 | 1,829 | 38.93 | 5.40 |
| 10m | 3,075 | 50 | 8 | 17,968 | 182.79 | 6.37 |

Raw directories under `build/ram-poc/results/` (ordinary / forced-GC) are `current-100k-direct-ordinary-z0uk99na` / `current-100k-direct-forcegc-tbvifuip`, `current-1m-direct-ordinary-opmwwj6b` / `current-1m-direct-forcegc-5sr_nq66`, and `current-10m-direct-ordinary-_3cgmgs1` / `current-10m-direct-forcegc-a4tu960v`.

At 10m rows, the direct experiment's 182.79 MiB post-filter heap is about 91% below the current eager model's 2,056.20 MiB flat heap, but its 17.97-second sequential filter is roughly 12 times the eager model's 1.446 seconds. These are different workloads with different retained state and feature coverage, so they indicate a tradeoff rather than an application-level saving. The direct reader has a narrower feature set and is not ready as a replacement. Batched validation and I/O are possible next investigations inferred from the implementation and timings; these measurements do not establish them as profiled bottlenecks.

## Startup diagnostics

The separate `current-100k-ui-nmt-ilpu9_y1` diagnostic reported, at approximately 10 seconds, JVM heap used of 22,115,416 bytes and committed heap of 58,720,256 bytes. NMT reported total committed memory of 298,202 KiB, including metaspace 39,515 KiB, thread 90,916 KiB, code 11,722 KiB, GC 53,549 KiB and symbol 21,594 KiB. These categories are not physical footprint. The footprint tool reported 379 MB physical footprint, 97 MB IOSurface, 56 MB owned graphics, 8,208 KiB CG image and 2,928 KiB physical stack, using the tool's own units and classifications. In particular, NMT thread committed and physical stack are different measures. The 60-second footprint probe reported 520 MB but overlaps the open that starts after the 60-second sample; it is not an idle measurement.

For `current-100k-ui-jfr-ti2lh4nw`, the first ten seconds contained 362 sampled allocation events with an estimated total of 159.9 MiB: estimated byte arrays 86.1 MiB, ConcurrentHashMap node arrays 32.3 MiB and ASM SymbolTable entries 22.3 MiB. No startup allocation samples had LanguageTool, Whisper or Bytedeco stacks; sampling cannot prove those components allocate nothing.

An additional ordinary clean repeat, `current-100k-ui-ordinary-354gfws2`, had a 16,661 µs scroll-frame median and 17,528 µs p95 with focus, active and showing all true. The restored NMT repeat had normal frame medians near 16.7 ms and p95 of 18,331 µs with focus and active false and showing true. The earlier slow run was not reproduced in these repeats; its cause remains unknown and it remains in the original matrix.

The restored 100k synthetic session in `current-100k-ui-nmt-e74gevl7` reported physical footprint of 596 MB at 10 seconds and 602 MB at 60 seconds, and RSS of 512.4 and 433.7 MiB respectively. At 10 seconds its heap used was 148,951,272 bytes (142.05 MiB), with 224 MiB committed; at 60 seconds heap used was 56,968,408 bytes (54.33 MiB), with 80 MiB committed. The 10-second observation is the better idle comparison: the 60-second footprint probe overlaps the beginning of the filter interaction, so these samples should not be treated as precisely paired idle values or used to infer an exact heap-to-footprint ratio. At 10 seconds the footprint tool reported 117 MB IOSurface, 70 MB owned graphics and 8,208 KiB CG image; at 60 seconds it reported 158 MB IOSurface and 117 MB owned graphics. MB values here use the footprint tool's units.

The supplemental production Main NMT runs each launched one fresh test home with the first-install license dialog and no MCP server. No fixture was opened and no first-draw time was measured:

| Interop setting | Raw directory | Physical footprint 10s / 60s (tool MB) | RSS 10s / 60s (MiB) | Peak RSS (MiB) | 10s IOSurface / owned graphics / CG image (tool MB) |
|---|---|---:|---:|---:|---:|
| Default | `current-not-opened-main-nmt-hy_5naf0` | 443 / 450 | 320.4 / 285.4 | 328.7 | 117 / 82 / 16 |
| False, diagnostic | `current-not-opened-main-nmt-01dudkk5` | 464 / 443 | 319.1 / 305.2 | 321.5 | 136 / 82 / 16 |

For the default Main run, NMT reported 224 MiB of Java heap committed at both 10 and 60 seconds; this is committed capacity, not heap used. The two single-run comparisons change direction between samples and show no startup benefit from disabling interop blending. Keep the application default: the native macOS mirror underlay depends on it.

For the 100k model workload, three-launch median parse/filter times were 85/26 ms with default initial heap, 88/24 ms with 32 MiB, 88/27 ms with 64 MiB and 83/26 ms with 128 MiB. Together with the UI matrix, these observations support retaining the existing JVM flags; they do not remove the previously reported startup and filter gate misses.


## ZIP close/reopen follow-up

The follow-up fixes a lifecycle gap independent of the flat projection: ordinary file and ZIP tabs already publish releasable row/index facades, but closing a tab scheduled explicit release only for append-only capture/tail stores. A stale tab snapshot could therefore retain a normal file's entire row/index backing. Close now schedules release for both store types after the existing 1.5-second grace, checks remaining owners, and defers release until the last reader pin closes. Duplicate tabs keep their shared store while an owner remains. Reusing the row facade also reuses its release-aware ID map facade. This does not prove that every observed increase in process memory was caused by those snapshots.

Heap-trim accounting now counts rows when the store actually releases them. The decision and counter reset happen under the same lock, so rapid closes and a later pinned release cannot erase each other's counts. Capture/video force-trim behavior is retained. The existing scheduler coalesces trims with a further three-second delay. The fix does not add periodic forced collection or change JVM defaults.

A focused run after this fix passed 580 tests, including ordinary/ZIP stale snapshots, repeated generated ZIP reopen, shared owners, reader pins, rapid trim accounting, exports, control-server operations, and inactive-tab residency. A comment-length finding in the edited AppState file was corrected afterward; the subsequent check also found one blank-line issue in the test-only archive harness, which was corrected. Final repository checks are reported below.

Archive fixtures and runs can be reproduced with:

```sh
export JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home
python3 scripts/ram-poc-fixtures.py 1m
python3 scripts/ram-poc-archive-fixture.py --fixture 1m --grouped
./gradlew -I scripts/ram-poc-classpath.gradle compileTestKotlinDesktop ramPocExportClasspath
python3 scripts/ram-poc-run.py --version current --workload archive --fixture 1m --archive-grouped --heaps default --repetitions 3 --archive-cycles 6
```

The runner accepts only generated ZIP contents with a known SHA-256 inside the synthetic fixture root. The grouped fixture replaces four rows with a real same-PID/TID fatal stack and asserts at least one stack group at runtime. Its total remains one million rows; the original flat fixture's sparse stack-like messages were not sufficient to form a trace group. ZIPs contain one generated log. The cycle harness waits for loaded rows, completed analysis and a window draw before closing; it waits six seconds after close and leaves another 1.5-second idle sampling interval after each close/control observation. RSS samples must fall in that idle interval, rather than the next open.

For a before/after close-fix comparison, preserve the compact-POC main binary before applying the lifecycle fix at `build/ram-poc/archive-pre-fix/classes/kotlin/desktop/main` and its resources at `build/ram-poc/archive-pre-fix/processedResources/desktop/main`; use `--version archive-pre-fix` with the identical command. The older `baseline` snapshot measures the entire POC against its original parent instead. These local binary snapshots are build artifacts, not committed source.

Use `--diagnostic forcegc` only in a separate run. To test retained historical tab snapshots deliberately, add `--archive-hold-snapshots`; this is a controlled retention diagnostic, not a claim that normal UI behavior retains every tab. With `--diagnostic nmt`, macOS physical-footprint reports are collected synchronously at control/closed phases before the next cycle; the additional timed 10/60-second probes are not guaranteed idle. Native diagnostics and forced-GC runs must remain separate from ordinary measurements.


### Cache publication race

The grouped ZIP diagnostic `current-1m-archive-nmt-m5dv2651` exposed a second, independent retention path before the cache fence was applied. Closed cycles 1–3 had zero cache entries and 24–27 MiB used heap. Cycle 4 had one cache entry for the just-closed tab and 267.6 MiB used heap; that entry remained after cycles 5 and 6 (262.8 and 270.1 MiB), even though those later archive tabs themselves had no cache entry. Thus the earlier row-facade fix alone did not fix repeated-close growth.

A computation could finish after `invalidateComputeCache` and publish its result again. Cache publication now holds a short-lived ownership slot acquired at compute entry. Invalidation atomically detaches the slot and its entry; old work cannot publish into the new lifetime or remove a newer result on timeout. Slots retire when no cache entry or in-flight work needs them. The lock protects only map/slot bookkeeping, not filtering or rendering. Two deterministic latch-based regressions both failed against the preserved pre-fix main binary and both passed against the fixed binary; logs are `build/ram-poc/cache-regression-before.log` and `cache-regression-after.log`.

Ordinary grouped runs were variable and should not be pooled selectively. The first three-launch, rows-only-fix matrix (`current-1m-archive-ordinary-v66i3aei`) had sixth-close heaps of 514.3, 278.5 and 25.7 MiB. A later three-launch repeat with cache counters (`current-1m-archive-ordinary-juw70a8g`) did not consistently reproduce this, while the separate NMT diagnostic above caught the stale entry. The earlier pre-close-fix matrix is `archive-pre-fix-1m-archive-ordinary-34xj_m6b`. These pre-fence runs are retained as evidence of an intermittent race, not final improvement measurements.

The controlled historical-snapshot diagnostic is separate: `archive-pre-fix-1m-archive-forcegc-_ibslqqr` deliberately held every closed tab and reached 1,268.47 MiB heap after six closes, with six million rows still exposed by those snapshots. `current-1m-archive-forcegc-8cdfgoi9` held the same six snapshots, which exposed zero rows after release, and reached 30.41 MiB. This verifies the release mechanism under controlled retention; it is not an ordinary whole-app saving or a three-launch median.

### Log and video archive scenario

The user's clarified scenario has two existing log tabs and a ZIP that opens a log with attached video. Generate a separate fixture without touching any existing user media:

```sh
python3 scripts/ram-poc-archive-fixture.py --fixture 1m --video
python3 scripts/ram-poc-run.py --version current --workload archive --fixture 1m --archive-video --heaps default --repetitions 3 --archive-cycles 6
```

The video generator requires `ffmpeg` and uses only its `testsrc2` source: five seconds at 1920×1080 and 30 fps, with no audio. The generated video hash is recorded in the manifest; record the local FFmpeg version when regenerating it. It also writes two distinct generated 100k log copies. The harness keeps these background tabs loaded using reader pins for a constant control, selects the generated ZIP's log and video through the existing picker flow, waits for a decoded frame, allows two seconds of warm-up, and closes that archive tab only. It reports decoder failures instead of treating an unavailable video backend as a successful memory test. Results below concern this synthetic codec/resolution and do not cover every capture format.


### Final verification

The full post-fix `desktopTest` run completed 7,951 tests: 7,941 passed, five skipped, and five failed. All five failures are in `LogViewTilingUiTest`, matching the failures already reproduced on the baseline. The run is recorded in `build/ram-poc/archive-full-checks.log`; XML results were preserved at `build/ram-poc/archive-full-tests`. Both new cache-lifetime regressions also passed when launched directly against the fixed binary, after both failed against the preserved pre-fix binary.

After formatting corrections, `compileTestKotlinDesktop` succeeded. Final ktlint/detekt findings in `build/ram-poc/archive-final-checks.log` concern unchanged Seq3 files, with none in the POC's edited or added Kotlin files. Python syntax checks and `git diff --check` passed. The generated video was made with FFmpeg 9.0.2; its environment record is `build/ram-poc/video-fixture-environment.json`.


### Measured log-plus-video cycles

Three independent ordinary launches per version each kept two generated 100k background tabs loaded and opened/closed the generated 1m log plus 1080p video six times. Values below are per-cycle medians across those three launches. RSS uses only samples in the 1.5-second idle interval following the close sample; heap values are ordinary used heap without additional forced GC. Existing application trim behavior remains enabled in both versions.

| Observation | Before heap (MiB) | Fixed heap (MiB) | Before RSS (MiB) | Fixed RSS (MiB) |
|---|---:|---:|---:|---:|
| Background tabs, before archive | 125.3 | 116.5 | 543.2 | 554.1 |
| After close 1 | 76.2 | 78.4 | 581.7 | 588.6 |
| After close 2 | 79.0 | 76.1 | 601.6 | 602.8 |
| After close 3 | 76.6 | 73.7 | 599.3 | 620.0 |
| After close 4 | 73.7 | 74.7 | 605.6 | 611.3 |
| After close 5 | 76.6 | 76.3 | 616.3 | 613.6 |
| After close 6 | 73.1 | 73.9 | 634.3 | 619.3 |

At the sixth close, committed heap medians were 176 MiB before and 160 MiB fixed. Every post-close observation had zero archive cache entries; the two background entries remained. Decoder readiness was asserted on every open. These runs did not trigger the grouped-log publication race, so they do not establish a large video-path memory benefit. RSS still increases over repeated opens despite stable used heap. Do not describe that remaining increase as solved by the row/cache fixes or as necessarily unavoidable JVM overhead.

Raw directories: `archive-pre-fix-1m-archive-ordinary-42nty5t9` and `current-1m-archive-ordinary-zgzyt4p1` under `build/ram-poc/results`. Per-launch observations and medians are in `build/ram-poc/archive-final-comparison.json`. The video smoke run is `current-1m-archive-ordinary-z2snxu4b`.


The final grouped-log run after the publication fence is `current-1m-archive-ordinary-ni17y_b4`: three independent launches, six closes each, with zero archive cache entries after all 18 closes. Median used heap by closed cycle was 23.4, 27.6, 25.3, 31.3, 31.2 and 34.6 MiB; median RSS was 404.5, 420.2, 426.7, 429.9, 438.5 and 433.4 MiB. Individual sixth-close used heap was 39.0, 23.1 and 34.6 MiB. This supports the deterministic race fix without implying all process memory returns to an empty-startup level.

Separate single-launch native diagnostics used the same generated log/video scenario and collected footprint synchronously after close. The tool's MB classifications differ from RSS and used heap.

| Observation | Before physical footprint (tool MB) | Fixed physical footprint (tool MB) |
|---|---:|---:|
| Two background tabs, control | 630 | 625 |
| After close 1 | 662 | 638 |
| After close 2 | 680 | 684 |
| After close 3 | 685 | 761 |
| After close 4 | 711 | 690 |
| After close 5 | 698 | 715 |
| After close 6 | 713 | 685 |

In the fixed run IOSurface stayed at 117 tool MB. Owned graphics was usually 70–74 MB, briefly 122 MB at close 3 before dropping again. MALLOC_SMALL grew from 139 MB at control to 187 MB by close 3 and 191 MB at closes 5–6; MALLOC_LARGE reached 16 MB at close 2 and stayed there. These categories show allocator/graphics costs outside the Java heap but do not identify each allocation's originating component. The diagnostics are `archive-pre-fix-1m-archive-nmt-tizveyf8` and `current-1m-archive-nmt-cvlh7htn`. Neither is a three-run median or an ordinary latency measurement.


A final longer diagnostic (`current-1m-archive-nmt-tqoilpk6`) repeated the generated log/video scenario 20 times in one JVM. All 20 post-close observations had zero archive cache entries and retained both background tabs. Physical footprint was 624 tool MB at control, 690 MB after close 2, and 704 MB after close 20. From closes 4–20 it fluctuated between 686 and 736 MB, rather than increasing on every cycle. Final used heap was 73.38 MiB with 152 MiB committed. This single diagnostic supports a warm-up/allocator/graphics plateau for this synthetic codec and resolution; it does not establish the behavior of every real capture. Structured observations are in `build/ram-poc/archive-video-long-diagnostic.json`.

The row-release and cache-fence fixes address confirmed retention defects. They do not substantially lower the measured log-plus-video operating footprint, and the original startup/filter performance gates still leave the broader projection work at POC status. Keep JVM and interop defaults. Subsequent storage work should evaluate bounded lazy parsing and batched disk reads before considering production integration; the current direct-file experiment's search cost is too high. Investigating native video memory further should use generated media matching the relevant codec/resolution and attribute native allocations, rather than infer a Java leak from process footprint alone.

## Block-boundary validation screening

The test-only direct reader now also exposes a sequential scan that validates the source before and after each 128-row block instead of validating on every row. It still uses the existing per-row reads and parser setup; it is not an application storage backend. A consumer can observe rows from a block before a subsequent boundary detects a concurrent change, so this experiment does not provide atomic scan results. Metadata validation still cannot detect every same-size rewrite with restored timestamps.

On the previously described machine and JDK, three ordinary 1m-row launches produced per-row-validation times of 2,568, 2,575 and 2,668 ms, followed in the same JVM by block-boundary times of 543, 537 and 598 ms. Both scans returned the same match count in each launch. The generated fixture SHA-256 was `f3b0a133e2042ff1493ddf32080acde46f85d7890bdf6816ac8240dd8566fa28`; raw results are in `build/ram-poc/results/current-1m-direct-ordinary-3s3q6bbo`. Reproduce with `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/ram-poc-run.py --version current --workload direct --fixture 1m --heaps default --repetitions 3` after compiling desktop tests.

This is preliminary attribution evidence, not a promotion result: the batched scan always ran second, after the file and JVM were warmed, and the scans have different validation frequency and failure timing. It does not establish a production speedup or compare complete viewer readiness. A controlled alternating-order comparison and the plan's wider correctness and application gates remain necessary.

## Twenty-cycle video screening after controller ownership fix

A single ordinary launch used two generated background 100k logs and a generated 1m ZIP log with 1080p MPEG-4 video; each of 20 opens reported a decoded frame. After every close the harness reported two background tabs, zero archive tabs and zero archive cache entries. Used heap at closes 1, 5, 10, 15 and 20 was 72.9, 71.8, 74.5, 74.5 and 73.0 MiB respectively; nearest one-second RSS samples were 529.1, 540.0, 597.9, 572.9 and 574.9 MiB. These are one process's observations, not independent replicates, physical footprint, a controlled before/after comparison, or attribution of native allocations. Normal application GC/trim behavior was active; heap values are not forced-GC measurements. The varied RSS does not establish the cause or resolution of native growth.

The raw output and exact child command are in `build/ram-poc/results/current-1m-archive-ordinary-gwmf_f37`; the generated archive hash and JVM details are in its metadata. Run `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/ram-poc-run.py --version current --workload archive --fixture 1m --archive-video --heaps default --repetitions 1 --archive-cycles 20`. An earlier sandboxed attempt (`current-1m-archive-ordinary-p3x1qei1`) exited with status -6 and an empty child log; its cause is unknown and it produced no cycle measurements. The successful rerun used desktop/native-library access outside that sandbox. More independent runs, comparable physical-footprint samples and native allocation/thread attribution remain required.

## Experimental indexed source screening

The internal `IndexedFileSource` and `StorageQuery` were screened with generated ordinary logcat fixtures, using the same keyword predicate and verifying equal match counts. Each cell used three fresh JVMs, with eager and indexed runs alternated; the separate forced-GC runs do not supply the ordinary timing results. Times are medians in milliseconds:

| Rows | Eager parse | Index build | Eager filter count | Indexed filter and match index |
|---:|---:|---:|---:|---:|
| 100k | 97 | 100 | 81 | 171 |
| 1m | 334 | 453 | 165 | 767 |

At 1m, the separately forced-GC sample after filter was 217.1 MiB used heap for the eager mode and 15.3 MiB for the indexed mode. These model workloads retain different results: the indexed query retains match positions, whereas the eager comparison counts matches and does not build the viewer projection. They do not include a viewer, grouping, selection or export, nor do they measure process physical footprint or acceptance gates. Filtering in the indexed mode is substantially slower in this preliminary screen. A fresh JVM is not a cold filesystem cache. Thread-allocation counters in the raw results cover only the benchmark caller and omit worker-thread allocations.

Raw results, exact child arguments, fixture and source hashes, and individual observations are in `build/ram-poc/indexed-results/indexed-b0c_2_73` (100k) and `indexed-eaohegy8` (1m). Reproduce after compiling desktop tests with `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/ram-poc-indexed.py --fixtures 100k 1m --repetitions 3 --force-gc-diagnostic`. The experiment remains internal and is not a promotion decision.

A subsequent page-scoped parser-reuse candidate was checked against parser and storage regressions and screened with the same 1m command. Its indexed ordinary filter times were 738, 710 and 992 ms (median 738 ms), compared with the earlier 767, 755 and 770 ms (median 767 ms). Eager filter medians were 164 and 165 ms respectively. These independent three-run screens include a substantial outlier, and the before/after candidate order was not alternated; the small median difference is inconclusive. It does not establish that parser setup explains the gap or that the timing gate passes. Raw candidate data and source hashes are in `build/ram-poc/indexed-results/indexed-io52d94i`; forced-GC runs remain separate.

### Shared regex context and line-buffer experiment

The earlier indexed screening invoked the public `passesFilter` overload, which constructed a regex evaluation context for each row. Both modes used the same predicate, but that is not how the application performs a whole filter operation. The benchmark now uses one operation-local context per scan in each mode; the earlier results remain recorded above but should not be used as the primary application-like filter comparison.

One separate 1m-row JFR diagnostic, configured with only execution and allocation samples, captured 71 execution samples and 1,100 sampled allocations over roughly one second. The first application frame was `LineScanner.next` in 47 execution samples and 575 allocation samples; attribution weights are estimates, and the recording includes both indexing and filtering plus startup. This suggested a candidate, not proof that this one method caused the whole gap. Raw diagnostic files and event settings are under `build/ram-poc/indexed-profile/`.

A reusable per-scanner line buffer now replaces a new byte accumulator for every line. A record above 256 KiB is still returned intact but its enlarged scratch buffer is dropped afterward; records too large for a JVM array fail explicitly. Boundary, malformed UTF-8, oversized-record and parser tests passed. Three fresh-JVM 1m screens using the corrected shared regex context produced:

| Version | Indexed index ms | Indexed filter ms | Eager parse ms | Eager filter ms |
|---|---:|---:|---:|---:|
| Before reusable buffer | 440 | 716 | 313 | 158 |
| Reusable buffer | 427 | 676 | 286 | 158 |

These are medians, with individual runs and exact source/fixture hashes in `build/ram-poc/indexed-results/indexed-ylcnmbi6` and `indexed-3r_85thy`. The candidate order was not alternated and three launches are only preliminary screening; the differences are not acceptance evidence. The indexed filter remains substantially slower in this model-only comparison, and neither run includes viewer grouping or whole-app physical footprint.

A follow-up changed the test-only route's scanner to fill an 8 KiB input buffer in bulk, while keeping 64 KiB metadata checks and cooperative cancellation. The first focused run found an EOF-state bug; after correcting it, the parser, malformed UTF-8, missing-final-newline, oversized-record, file-mutation and session tests passed, including a new CRLF/Unicode read-buffer-boundary case. In the same preliminary 1m model screen, indexed index times changed from 427/427/434 ms to 238/246/255 ms, and indexed filter times from 669/676/678 ms to 433/488/488 ms. Eager filter medians were 158 and 160 ms respectively. Raw candidate data and source hashes are in `build/ram-poc/indexed-results/indexed-yr0czlgd`.

This is a useful screening signal, not an acceptance measurement: each version had three fresh JVMs, candidate order was not alternated, filesystem-cache state was uncontrolled, and the indexed model still performs a substantially slower filter than the eager count. No real-viewer or whole-app memory claim follows from it.
