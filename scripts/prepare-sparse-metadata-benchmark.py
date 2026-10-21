#!/usr/bin/env python3
"""Generate ignored, deterministic million-row metadata fixtures (requires local ffmpeg)."""

import datetime
import hashlib
import json
import pathlib
import subprocess
import time
import zipfile


def sha256_file(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    root = pathlib.Path(__file__).resolve().parents[1]
    target = root / "build/verification/sparse-metadata/baseline"
    target.mkdir(parents=True, exist_ok=True)
    (target.parent / "benchmark.init.gradle.kts").write_text(
        'allprojects { tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach { maxHeapSize = "4g" } }\n'
    )
    started = time.perf_counter()
    rows = 1_000_000
    clock = datetime.datetime(2026, 10, 21, 12)
    log_path = target / "logcat.log"
    timeline_path = target / "rows.jsonl"
    index_path = target / "capture-index.jsonl"
    with log_path.open("wb") as log, timeline_path.open("wb") as timeline, index_path.open("wb") as index:
        timeline.write(b'{"type":"captureTimeline","baseElapsedMs":0,"durationMs":1000000}\n')
        offset = 0
        for ordinal in range(1, rows + 1):
            elapsed = ordinal - 1
            timestamp = clock + datetime.timedelta(milliseconds=elapsed)
            prefix = timestamp.strftime("%m-%d %H:%M:%S.") + f"{timestamp.microsecond // 1000:03}"
            line = (
                f"{prefix}  1234  5678 I CaptureBench: row={ordinal:07} view={ordinal % 173:03} status=ready "
                "text=Representative scrolling application log message with stable capture timestamps\n"
            ).encode()
            log.write(line)
            index.write(
                f'{{"byteOffset":{offset},"byteLength":{len(line)},"elapsedMs":{elapsed},"rowOrdinal":{ordinal}}}\n'.encode()
            )
            timeline.write(f'{{"row":{ordinal},"elapsedMs":{elapsed}}}\n'.encode())
            offset += len(line)
    generation_seconds = time.perf_counter() - started
    video_path = target / "screen.mkv"
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-f", "lavfi", "-i", "color=c=gray:s=64x64:r=1",
         "-t", "1000", "-c:v", "libx264", "-preset", "ultrafast", "-crf", "28", "-g", "2", str(video_path)],
        check=True,
    )
    assets = {"logcat.log": log_path, "timeline/rows.jsonl": timeline_path, "screen.mkv": video_path}
    descriptor = {
        "format": "indagium-capture", "version": 4, "app": "Indagium benchmark legacy fixture",
        "sessionId": "million-rows-legacy",
        "device": {"model": "Synthetic fixture", "serial": "benchmark", "state": "device", "emulator": True},
        "startedAt": "2026-10-21T12:00:00Z", "exportedAt": "2026-10-21T12:16:40Z", "range": "ALL",
        "log": {"file": "logcat.log", "startMs": 0, "endMs": rows - 1},
        "video": {"file": "screen.mkv", "startMs": 0, "endMs": rows},
        "sync": {"row": 1, "videoMs": 0}, "offsetMs": 0, "notes": None,
        "markers": [], "screenshots": [], "interruptions": [], "timeline": "timeline/rows.jsonl",
        "timelineBaseMs": 0, "timelineDurationMs": rows,
        "availabilityFramesRendered": False, "showUnavailableFrames": True, "availability": [],
        "sha256": {name: sha256_file(path) for name, path in assets.items()},
    }
    descriptor_path = target / "capture.indagium.json"
    descriptor_path.write_text(json.dumps(descriptor, separators=(",", ":")))
    archive_path = target / "legacy-v4.zip"
    started = time.perf_counter()
    with zipfile.ZipFile(archive_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=6) as archive:
        archive.write(descriptor_path, "capture.indagium.json")
        for name, path in assets.items():
            archive.write(path, name, compresslevel=0 if name == "screen.mkv" else 6)
        archive.writestr("captured_with_indagium.txt", "Captured with Indagium (benchmark).\n")
    metrics = {
        "rows": rows, "duration_ms": rows, "log_bytes": log_path.stat().st_size,
        "legacy_index_bytes": index_path.stat().st_size, "legacy_timeline_bytes": timeline_path.stat().st_size,
        "legacy_zip_bytes": archive_path.stat().st_size, "video_bytes": video_path.stat().st_size,
        "fixture_generation_seconds": round(generation_seconds, 3),
        "python_zip_packaging_seconds": round(time.perf_counter() - started, 3),
        "note": "Synthetic stable-clock logs and tiny video: metadata benchmark only; Python ZIP generation is not app export time.",
    }
    (target / "metrics.json").write_text(json.dumps(metrics, indent=2) + "\n")
    print(json.dumps(metrics, indent=2))


if __name__ == "__main__":
    main()
