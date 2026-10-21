# Capture metadata benchmark

This benchmark checks the version-5 synchronization/block layout with one million log rows.
It uses a stable device clock, one row per millisecond, and 166-byte threadtime log lines with
changing row/view identifiers. It compares both unpacked metadata and complete archive sizes.

The fixture includes a tiny 64×64, 1 fps H.264 video with the same 1,000-second duration. A test
video exporter copies those bytes unchanged so the results measure metadata work rather than
video encoding. These results do not measure real-device video quality, decoding, or UI rendering.
Validation runs on macOS 26.6.2, arm64, with a 4 GiB test-JVM heap.

## Inputs

| Input | Bytes |
|---|---:|
| Raw log, 1,000,000 rows | 166,000,000 |
| Legacy retained `capture-index.jsonl` | 80,108,437 |
| Legacy `timeline/rows.jsonl` | 33,777,852 |
| Combined legacy JSON metadata | 113,886,289 |
| Legacy v4 ZIP | 12,931,663 |

The combined metadata comparison represents a finalized retained session, which holds both old
indexes. The portable v4 ZIP includes `rows.jsonl` and does not include `capture-index.jsonl`.

## Measurements

Measured on 2026-10-21. The one million rows produced **1,000 blocks and one synchronization
segment**. Metadata occupied **0.152% of the raw log**, exceeding both targets: below 1% of the log
and at least 95% smaller than the two legacy retained-session JSON indexes.

| Size | Legacy | v5 | Reduction |
|---|---:|---:|---:|
| Combined retained-session metadata | 113,886,289 B | 252,707 B | 99.78% |
| Archive metadata, unpacked | 33,777,852 B | 252,707 B | 99.25% |
| Archive metadata, compressed | 4,599,767 B | 62,808 B | 98.63% |
| Complete ZIP | 12,931,663 B | 8,395,155 B | 35.08% |
| All archive contents, unpacked | 199,816,797 B | 166,291,856 B | 16.78% |

| Operation | Time |
|---|---:|
| Build sparse block index | 470 ms |
| Full v5 export | 2.50 s |
| Exact 100-row export | 13.6 ms |
| v4 reader: extract and validate | 1.25 s |
| v5 reader: extract and validate | 476 ms |
| v5 log parsing and timing/navigation index preparation | 701 ms |
| v4 import: prepare compact snapshot cache | 1.80 s |
| Re-export imported v4 as v5 | 2.15 s |
| 200,000 warmed row/video navigation lookups | 94.8 ms (0.474 µs/query) |

The 100-row ZIP was 2,807 bytes and contained precisely rows 500,000–500,099. Re-exporting the v4
fixture produced an 8,395,198-byte v5 ZIP and preserved the middle row's 499,999 ms video position.
The v5 export contains no `timeline/rows.jsonl`, and the legacy snapshot adapter writes only blocks
and synchronization metadata. The video and raw log bytes are unchanged.

These results describe this stable-clock fixture, not every recording. More clock segments,
shorter blocks, log entropy, and video size change the savings. Lookup timings measure in-memory
index calls rather than user-visible rendering or seeking. There is no old-application export-time
measurement in this run; the legacy ZIP baseline was generated directly with Python's ZIP writer.
The test writes the full metrics to `build/verification/sparse-metadata/measured/metrics.json`,
with the IDEA-driven run status and XML under `build/verification/sparse-metadata/benchmark-run/`.

The final focused capture/export/import, navigation, restore/autosave, and lane suite reported
809 tests with zero failures and five opt-in device/emulator smoke tests skipped. IDEA's project
build, Detekt, and ktlint passed. Native media checks ran on macOS; physical-device reboot/codec
smoke tests and other operating systems were not exercised in this run.

## Reproduce

Requires Python 3 and a local FFmpeg executable with `libx264`. From the repository root:

```sh
python3 scripts/prepare-sparse-metadata-benchmark.py
INDAGIUM_SPARSE_METADATA_BENCHMARK=1 ./gradlew \
  --init-script build/verification/sparse-metadata/benchmark.init.gradle.kts \
  desktopTest --tests com.indagium.capture.SparseMetadataBenchmarkTest --rerun-tasks --console=plain
```

Run the Gradle command through the IDEA terminal/IDEA MCP for this project. The generated init
script grants the test JVM 4 GiB of heap; large fixtures and results remain under ignored `build/`.

The test also checks an exact 100-row selection and a v4 import/re-export to v5, including the
retained timing of a row halfway through the capture. Navigation measurements cover both
row-to-video and video-to-row queries after index preparation. Reader-open times include fresh
extraction and checksum validation, and exclude log parsing. The operating-system file cache is
warm; timings are single-run measurements rather than statistical throughput claims.
