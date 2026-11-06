#!/usr/bin/env python3
"""Screen experimental indexed-file filtering against eager parsing on generated fixtures.

This runner never compiles the project. It reads only build/ram-poc/fixtures and the exported
test classpath, launches every case in a fresh JVM with an isolated home, and stores each exact
command and process output under build/ram-poc/indexed-results. Results describe model work only;
they do not measure a viewer, scroll rendering, or justify promotion to application use.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import time

REPO = Path(__file__).resolve().parent.parent
BASE = REPO / "build" / "ram-poc"
FIXTURE_ROOT = BASE / "fixtures"
CLASSPATH_FILE = BASE / "classpath.json"
RESULTS = BASE / "indexed-results"
MAIN_CLASS = "com.indagium.ram.IndexedStoreBenchmark"
FIXTURE_ROWS = {"100k": 100_000, "1m": 1_000_000, "10m": 10_000_000}
EXPECTED_FIXTURE_SHA256 = {
    "100k": "f4f5b48a518d3e2556536ae3f4e236a19233e07986b4cde068563f81af65ff6c",
    "1m": "f3b0a133e2042ff1493ddf32080acde46f85d7890bdf6816ac8240dd8566fa28",
    "10m": "2ef4686cc31db83e8d3d7fc79938c99eda1f64812e092074b2b6bfde735eda7e",
}
MODES = ("eager", "indexed")
SOURCE_FILES = (
    "scripts/ram-poc-fixtures.py",
    "scripts/ram-poc-indexed.py",
    "src/desktopMain/kotlin/com/indagium/model/Model.kt",
    "src/desktopMain/kotlin/com/indagium/storage/IndexedFileSource.kt",
    "src/desktopMain/kotlin/com/indagium/storage/LegacyInMemorySource.kt",
    "src/desktopMain/kotlin/com/indagium/storage/StorageFilter.kt",
    "src/desktopMain/kotlin/com/indagium/storage/StorageQuery.kt",
    "src/desktopMain/kotlin/com/indagium/utils/Filter.kt",
    "src/desktopMain/kotlin/com/indagium/utils/LogParser.kt",
    "src/desktopTest/kotlin/com/indagium/ram/IndexedStoreBenchmark.kt",
)
BASE_JVM_FLAGS = (
    "-XX:MaxRAMPercentage=50",
    "-XX:G1PeriodicGCInterval=30000",
    "-XX:MaxHeapFreeRatio=30",
    "-XX:MinHeapFreeRatio=10",
    "-Dfile.encoding=UTF-8",
)


def reject_symlinks(path: Path) -> None:
    """Reject symlinks in every existing component of a generated-data path."""
    absolute = path.absolute()
    current = Path(absolute.anchor)
    for part in absolute.parts[1:]:
        current /= part
        if current.exists() and current.is_symlink():
            raise ValueError(f"symlink in benchmark path: {current}")


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def source_fingerprint() -> tuple[dict[str, str], str]:
    hashes = {}
    aggregate = hashlib.sha256()
    for relative in SOURCE_FILES:
        source = REPO / relative
        reject_symlinks(source)
        if not source.is_file() or source.resolve().parent != source.parent.resolve():
            raise ValueError(f"missing or unsafe benchmark source: {relative}")
        digest = sha256_file(source)
        hashes[relative] = digest
        aggregate.update(relative.encode("utf-8"))
        aggregate.update(b"\0")
        aggregate.update(digest.encode("ascii"))
        aggregate.update(b"\n")
    return hashes, aggregate.hexdigest()


def validate_fixture(label: str) -> tuple[Path, str]:
    fixture = FIXTURE_ROOT / f"logcat-{label}.log"
    for path in (REPO / "build", BASE, FIXTURE_ROOT, fixture):
        reject_symlinks(path)
    if not FIXTURE_ROOT.is_dir() or not fixture.is_file():
        raise FileNotFoundError(
            f"missing generated fixture {fixture}; generate it with scripts/ram-poc-fixtures.py"
        )
    if fixture.resolve(strict=True).parent != FIXTURE_ROOT.resolve(strict=True):
        raise ValueError("fixture escaped the generated-data directory")
    digest = sha256_file(fixture)
    if digest != EXPECTED_FIXTURE_SHA256[label]:
        raise ValueError(f"fixture is not the expected generated {label} fixture: {digest}")
    return fixture, digest


def load_test_classpath() -> list[str]:
    reject_symlinks(BASE)
    reject_symlinks(CLASSPATH_FILE)
    if not CLASSPATH_FILE.is_file():
        raise FileNotFoundError(
            f"missing {CLASSPATH_FILE}; after the coordinated build, run "
            "./gradlew desktopTestClasses ramPocExportClasspath"
        )
    data = json.loads(CLASSPATH_FILE.read_text())
    entries = data.get("test")
    if not isinstance(entries, list) or not entries:
        raise ValueError("classpath.json does not contain a non-empty test classpath")
    classpath = [str(Path(item).resolve()) for item in entries]
    class_file = Path("com/indagium/ram/IndexedStoreBenchmark.class")
    if not any((Path(item) / class_file).is_file() for item in classpath if Path(item).is_dir()):
        raise FileNotFoundError("compiled IndexedStoreBenchmark.class is absent from the test classpath")
    return classpath


def choose_java(java_arg: str | None) -> str:
    candidate = java_arg
    if candidate is None and os.environ.get("JAVA_HOME"):
        candidate = str(Path(os.environ["JAVA_HOME"]) / "bin" / "java")
    if candidate is None:
        candidate = shutil.which("java")
    if not candidate:
        raise FileNotFoundError("could not locate java; pass --java or set JAVA_HOME")
    java = str(Path(candidate).expanduser().resolve()) if "/" in candidate else shutil.which(candidate)
    if not java or not Path(java).is_file():
        raise FileNotFoundError(f"Java executable does not exist: {candidate}")
    return java


def parse_summary(output: str) -> dict[str, str]:
    lines = [line for line in output.splitlines() if line.startswith("RAM_INDEXED_BENCH phase=complete ")]
    if len(lines) != 1:
        raise ValueError("benchmark output must contain exactly one completion record")
    return dict(part.split("=", 1) for part in lines[0].split()[2:] if "=" in part)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", nargs="+", choices=FIXTURE_ROWS, default=["100k"],
                        help="generated log sizes to compare (default: 100k)")
    parser.add_argument("--repetitions", type=int, default=1,
                        help="fresh JVM launches per mode and fixture (default: 1)")
    parser.add_argument("--timeout", type=int, default=900, help="per-JVM timeout in seconds")
    parser.add_argument("--java", help="Java executable; defaults to JAVA_HOME/bin/java or PATH")
    parser.add_argument("--force-gc-diagnostic", action="store_true",
                        help="after ordinary runs, repeat both modes in separate JVMs with forced GC at samples")
    args = parser.parse_args()
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    if args.timeout < 1:
        parser.error("--timeout must be positive")

    reject_symlinks(REPO)
    reject_symlinks(BASE)
    fixtures = {label: validate_fixture(label) for label in dict.fromkeys(args.fixtures)}
    source_hashes, source_hash = source_fingerprint()
    classpath = load_test_classpath()
    java = choose_java(args.java)
    RESULTS.mkdir(parents=True, exist_ok=True)
    reject_symlinks(RESULTS)
    java_env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL") if key in os.environ}
    java_env.setdefault("PATH", os.defpath)
    version = subprocess.run([java, "-version"], capture_output=True, text=True,
                             env=java_env, timeout=30, check=True)
    run_dir = Path(tempfile.mkdtemp(prefix="indexed-", dir=RESULTS))
    reject_symlinks(run_dir)
    manifest = {
        "scope": "model-only",
        "viewer_connected": False,
        "promotion": "none",
        "platform": platform.platform(),
        "python": platform.python_version(),
        "java_executable": java,
        "java_version": version.stdout + version.stderr,
        "runner_jvm_flags": list(BASE_JVM_FLAGS),
        "isolated_home_and_java_preferences_per_launch": True,
        "modes": list(MODES),
        "fixtures": {label: {"path": str(path), "rows": FIXTURE_ROWS[label], "sha256": digest}
                     for label, (path, digest) in fixtures.items()},
        "source_files": source_hashes,
        "source_sha256": source_hash,
        "repetitions": args.repetitions,
        "timeout_seconds": args.timeout,
        "force_gc_diagnostic_requested": args.force_gc_diagnostic,
        "classpath": classpath,
        "commands": [],
        "results": [],
    }
    manifest_path = run_dir / "manifest.json"

    def save_manifest() -> None:
        manifest_path.write_text(json.dumps(manifest, indent=2))

    save_manifest()
    run_number = 0
    diagnostics = [False, True] if args.force_gc_diagnostic else [False]
    for label, (fixture, fixture_hash) in fixtures.items():
        for diagnostic in diagnostics:
            for repetition in range(1, args.repetitions + 1):
                pair = {}
                for mode in MODES:
                    run_number += 1
                    name = f"{label}-{mode}-{'forcegc' if diagnostic else 'ordinary'}-{repetition}"
                    launch_dir = run_dir / name
                    launch_dir.mkdir()
                    reject_symlinks(launch_dir)
                    with tempfile.TemporaryDirectory(prefix="home-", dir=launch_dir) as temporary_home:
                        home = Path(temporary_home).resolve()
                        temp_dir = home / "tmp"
                        prefs = home / "prefs"
                        prefs_system = home / "prefs-system"
                        for directory in (temp_dir, prefs, prefs_system):
                            directory.mkdir()
                        env = dict(java_env)
                        env.update({"HOME": str(home), "TMPDIR": str(temp_dir),
                                    "TMP": str(temp_dir), "TEMP": str(temp_dir)})
                        jvm_flags = [
                            *BASE_JVM_FLAGS,
                            f"-Duser.home={home}",
                            f"-Djava.util.prefs.userRoot={prefs}",
                            f"-Djava.util.prefs.systemRoot={prefs_system}",
                            f"-Dindagium.ram.fixtureSha256={fixture_hash}",
                            f"-Dindagium.ram.sourceSha256={source_hash}",
                        ]
                        if diagnostic:
                            jvm_flags.append("-Dindagium.ram.forceGc=true")
                        command = [java, *jvm_flags, "-cp", os.pathsep.join(classpath),
                                   MAIN_CLASS, mode, fixture.name]
                        manifest["commands"].append({"name": name, "argv": command,
                                                     "jvm_flags": jvm_flags})
                        save_manifest()
                        started = time.monotonic()
                        try:
                            completed = subprocess.run(command, cwd=REPO, env=env,
                                                       capture_output=True, text=True,
                                                       timeout=args.timeout)
                        except subprocess.TimeoutExpired as error:
                            output = (error.stdout or "") + (error.stderr or "")
                            (launch_dir / "stdout.log").write_text(output)
                            raise TimeoutError(f"{name} timed out; see {launch_dir / 'stdout.log'}") from error
                        elapsed = time.monotonic() - started
                        output = completed.stdout + completed.stderr
                        (launch_dir / "stdout.log").write_text(output)
                        record = {
                            "name": name,
                            "mode": mode,
                            "fixture": label,
                            "fixture_sha256": fixture_hash,
                            "source_sha256": source_hash,
                            "diagnostic_force_gc": diagnostic,
                            "repetition": repetition,
                            "elapsed_seconds": elapsed,
                            "exit_code": completed.returncode,
                            "output": output.strip().splitlines(),
                        }
                        if completed.returncode == 0:
                            summary = parse_summary(output)
                            if summary.get("mode") != mode or summary.get("fixture_sha256") != fixture_hash or \
                                    summary.get("source_sha256") != source_hash:
                                raise ValueError(f"{name} completion record does not match its launch metadata")
                            record["summary"] = summary
                            pair[mode] = summary
                        manifest["results"].append(record)
                        save_manifest()
                        if completed.returncode:
                            raise RuntimeError(f"{name} failed with {completed.returncode}; see {launch_dir / 'stdout.log'}")
                        print(f"[{run_number}] {name}: {elapsed:.2f}s", flush=True)
                if pair["eager"].get("rows") != pair["indexed"].get("rows") or \
                        pair["eager"].get("match_count") != pair["indexed"].get("match_count"):
                    raise RuntimeError(f"filter/count mismatch for {label}, diagnostic={diagnostic}, repetition={repetition}")

    # Confirm the generated inputs were stable across all fresh-JVM runs.
    for label, (fixture, initial_hash) in fixtures.items():
        if sha256_file(fixture) != initial_hash:
            raise RuntimeError(f"fixture changed while benchmark was running: {label}")
    manifest["fixture_hashes_rechecked_after_runs"] = True
    save_manifest()
    print(f"scope=model-only viewer_connected=false promotion=none")
    print(f"results: {run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
