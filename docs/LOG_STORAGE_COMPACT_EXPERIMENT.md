# Compact-entry experiment

Status: **test-only, not accepted for production**. The candidates in
`src/desktopTest/kotlin/com/indagium/ram/compact/` are conversion experiments, not changes to
`LogEntry` or the parser. Inputs are generated in the test; no user data is accessed.

## Candidates

* `MetadataCandidate` moves `sourceTag` and all six DLT fields to a nullable extension. A row with
  any one of those fields, including a zero DLT timestamp, receives an extension. Its calendar slot
  stays on the row. Plain logcat rows avoid that extension; DLT and merged rows pay for it.
* `TimestampCandidate` embeds milliseconds of day in an `Int` and an exact nullable fallback
  string. Only valid ASCII `HH:mm:ss.SSS` values are encoded. All other values, including other
  precision, malformed input, dates, Unicode digits and empty strings, retain the original text.
  Rendering an encoded time creates a new string on every call. The current constructor also parses
  canonicality twice; this prototype is not a timing optimization.

The two variants isolate the effects; no combined class or actual model migration has been built.
They preserve all current fields when converted back to `LogEntry`, but conversion does not preserve
`LogEntry`'s data-class API at call sites. Constructor order, `copy`, destructuring, equality/hash,
serialization and persistence must be audited before any production replacement. No code assumes
that a compatibility getter alone preserves these contracts.

## Evidence and limitations

Focused tests cover independently expected canonical numbers, exact fallback text, thousands of
sampled times, plain/merged/DLT/mixed metadata, and midnight/date timeline behavior after conversion.
They do not establish parser integration, persistence, export, video alignment, UI behavior or
allocation/timing parity. They are not a performance benchmark.

No object layout, retained heap or timing results were measured for these actual candidates. The
illustrative numbers in the redesign plan are not measurements of these classes. Before comparing
memory, measure the actual candidate classes and extension/string graphs under a recorded JDK and
compressed-reference configuration; distinguish null, merged, DLT and mixed rates, include boxed
DLT timestamps and the exact fallback-string fraction, and compare the same generated rows with the
existing POC. Metadata-bearing rows can become larger due to the extra object. Also measure repeated
formatting allocations in filtering, summaries, scrolling and export without adding an unbounded
per-row cache. Investigate the double parsing before using this prototype for timing comparisons.

Production adoption remains undecided until exact supported-format behavior, real flat and grouped
view retained-memory savings, and the plan's timing gates have all been demonstrated. No claim about
whole-process footprint or the original user-visible RAM issue follows from this experiment.

## Shallow layout screening

`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/ram-poc-layout.py` measures the actual compiled candidate classes with a temporary JDK Instrumentation agent. On Temurin 21.0.11+10 arm64, compressed ordinary and class references and compact strings enabled, 8-byte object alignment, shallow sizes were: `LogEntry` 72 bytes, `MetadataCandidate` 48, `OptionalMetadata` 40, and `TimestampCandidate` 80. A `String` was 24 bytes and a 12-byte array 32 bytes; a boxed `Long` was 24 bytes. The latter sizes are reported independently; allocation sharing and actual object graphs are workload-dependent.

For a plain metadata-free row this layout removes 24 shallow bytes; an extension-bearing row instead adds 16 shallow bytes (48 + 40 versus 72), before shared references and boxed values. The standalone timestamp candidate is 8 bytes larger than `LogEntry` but can avoid the 56-byte canonical timestamp string graph when exclusively owned. These are layout calculations, not retained-heap or application measurements. No candidate is accepted from this probe, and no combined saving is claimed. The focused `CompactCandidatesTest` passed on the same JDK; real-format, allocation and timing gates remain open.

## Generated object-model screening

Three fresh JVMs per cell generated 1m rows using identical scenario-specific values, retained one representation at a time, forced GC, and checked field checksums. Median used heap (MiB) was:

| Generated workload | Existing row | Optional metadata | Numeric timestamp |
|---|---:|---:|---:|
| Plain | 234.6 | 211.8 | 190.5 |
| Merged | 234.4 | 250.4 | 190.9 |
| DLT | 258.0 | 274.2 | 213.6 |
| Mixed | 239.7 | 232.3 | 195.0 |

The mixed workload is 60% plain, 20% merged, 10% DLT and 10% merged plus DLT. Canonical timestamps account for 99.8% of generated rows. Repeated timestamp access in the numeric candidate allocated a median 95.8 bytes per access on the measured thread, versus approximately zero for existing string access, with identities observed to prevent temporary-allocation elimination. This is a synthetic access pattern, not a measurement of filtering or scrolling. Heap includes generated messages and runtime overhead; it is neither retained log-subsystem memory in the real viewer nor physical footprint.

Run `JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-21.jdk/Contents/Home python3 scripts/ram-poc-compact.py --mode all --scenario all --rows 1000000 --repetitions 3` after compiling desktop tests. The raw per-launch outputs, exact JVM flags and environment are in `build/ram-poc/compact-results/compact-jzbtu7s4`; the earlier 100k smoke run is in `compact-ap16moh_`. Modes ran in fixed order and only three samples were taken. The metadata-heavy regressions and timestamp allocation cost require format-specific investigation; no production acceptance follows from these preliminary results.

## Current decision

Reject both prototypes for production in their present form. Optional metadata increases measured memory for the generated merged and DLT workloads, and the timestamp candidate adds substantial repeated-access allocation without demonstrated real-view savings or timing parity. This is a decision about these implementations, not proof that compact entries are infeasible. A redesigned candidate must repeat the format and real-view gates before adoption. The original `LogEntry` remains the baseline for the initial storage contract.
