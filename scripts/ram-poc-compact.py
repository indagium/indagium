#!/usr/bin/env python3
"""Run generated compact-entry screening modes in isolated fresh JVMs.

This runner does not compile the project or read log fixtures. Compile desktopTest and export its
classpath first; each mode/scenario/repetition below is then a separate JVM process.
"""
import argparse
import json
import os
from pathlib import Path
import platform
import shutil
import statistics
import subprocess
import tempfile
import time

REPO = Path(__file__).resolve().parent.parent
BASE = REPO / "build" / "ram-poc"
CLASSPATH_FILE = BASE / "classpath.json"
RESULTS = BASE / "compact-results"
MAIN_CLASS = "com.indagium.ram.compact.CompactMemoryBenchmark"
MODES = ("original", "metadata", "timestamp")
SCENARIOS = ("plain", "merged", "dlt", "mixed")


def reject_symlinks(path: Path, *, allow_missing_leaf: bool = False) -> None:
    """Reject symlinks in every existing component of a generated-data path."""
    absolute = path.absolute()
    current = Path(absolute.anchor)
    parts = absolute.parts[1:]
    for index, part in enumerate(parts):
        current /= part
        if not current.exists() and allow_missing_leaf and index == len(parts) - 1:
            continue
        if current.is_symlink():
            raise ValueError(f"symlink in benchmark path: {current}")


def load_test_classpath() -> list[str]:
    reject_symlinks(BASE)
    reject_symlinks(CLASSPATH_FILE)
    if not CLASSPATH_FILE.is_file():
        raise FileNotFoundError(
            f"missing {CLASSPATH_FILE}; after the coordinated build, run "
            "./gradlew desktopTestClasses ramPocExportClasspath"
        )
    classpath_data = json.loads(CLASSPATH_FILE.read_text())
    entries = classpath_data.get("test")
    if not isinstance(entries, list) or not entries:
        raise ValueError("classpath.json does not contain a non-empty test classpath")
    classpath = [str(Path(item).resolve()) for item in entries]
    class_file = Path("com/indagium/ram/compact/CompactMemoryBenchmark.class")
    if not any((Path(item) / class_file).is_file() for item in classpath if Path(item).is_dir()):
        raise FileNotFoundError("compiled CompactMemoryBenchmark.class is absent from the test classpath")
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


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=[*MODES, "all"], default="all")
    parser.add_argument("--scenario", choices=[*SCENARIOS, "all"], default="all")
    parser.add_argument("--rows", nargs="+", type=int, default=[100_000], metavar="N",
                        help="one or more generated row counts (default: 100000)")
    parser.add_argument("--repetitions", type=int, default=1,
                        help="fresh JVM launches per mode/scenario/count (default: 1)")
    parser.add_argument("--timestamp-repeats", type=int, default=2,
                        help="full timestamp-access passes per launch (1..100; default: 2)")
    parser.add_argument("--timeout", type=int, default=900, help="per-JVM timeout in seconds")
    parser.add_argument("--java", help="Java executable; defaults to JAVA_HOME/bin/java or PATH")
    args = parser.parse_args()
    if any(count < 1 or count > 2_147_483_639 for count in args.rows):
        parser.error("--rows values must be in 1..2147483639")
    if args.repetitions < 1:
        parser.error("--repetitions must be positive")
    if not 1 <= args.timestamp_repeats <= 100:
        parser.error("--timestamp-repeats must be in 1..100")
    if args.timeout < 1:
        parser.error("--timeout must be positive")

    reject_symlinks(REPO)
    reject_symlinks(BASE)
    classpath = load_test_classpath()
    java = choose_java(args.java)
    RESULTS.mkdir(parents=True, exist_ok=True)
    reject_symlinks(RESULTS)
    java_env = {key: os.environ[key] for key in ("PATH", "LANG", "LC_ALL") if key in os.environ}
    java_env.setdefault("PATH", os.defpath)
    version = subprocess.run([java, "-version"], capture_output=True, text=True,
                             env=java_env, timeout=30, check=True)
    run_dir = Path(tempfile.mkdtemp(prefix="compact-", dir=RESULTS))
    reject_symlinks(run_dir)
    modes = MODES if args.mode == "all" else (args.mode,)
    scenarios = SCENARIOS if args.scenario == "all" else (args.scenario,)
    manifest = {
        "platform": platform.platform(),
        "python": platform.python_version(),
        "java_executable": java,
        "java_version": version.stdout + version.stderr,
        "runner_flags": [
            "-XX:MaxRAMPercentage=50",
            "-XX:G1PeriodicGCInterval=30000",
            "-XX:MaxHeapFreeRatio=30",
            "-XX:MinHeapFreeRatio=10",
            "-Dfile.encoding=UTF-8",
            "isolated -Duser.home and Java preferences roots per launch",
        ],
        "modes": list(modes),
        "scenarios": list(scenarios),
        "rows": args.rows,
        "repetitions": args.repetitions,
        "timestamp_repeats": args.timestamp_repeats,
        "timeout_seconds": args.timeout,
        "class_path": classpath,
        "commands": [],
        "results": [],
    }
    manifest_path = run_dir / "manifest.json"
    manifest_path.write_text(json.dumps(manifest, indent=2))
    run_number = 0
    for count in args.rows:
        for scenario in scenarios:
            for mode in modes:
                for repetition in range(1, args.repetitions + 1):
                    run_number += 1
                    name = f"{count}-{scenario}-{mode}-{repetition}"
                    launch_dir = run_dir / name
                    launch_dir.mkdir()
                    reject_symlinks(launch_dir)
                    with tempfile.TemporaryDirectory(prefix="home-", dir=launch_dir) as temp:
                        home = Path(temp).resolve()
                        temp_dir = home / "tmp"
                        prefs = home / "prefs"
                        prefs_system = home / "prefs-system"
                        for directory in (temp_dir, prefs, prefs_system):
                            directory.mkdir()
                        env = dict(java_env)
                        env.update({"HOME": str(home), "TMPDIR": str(temp_dir),
                                    "TMP": str(temp_dir), "TEMP": str(temp_dir)})
                        command = [
                            java,
                            "-XX:MaxRAMPercentage=50",
                            "-XX:G1PeriodicGCInterval=30000",
                            "-XX:MaxHeapFreeRatio=30",
                            "-XX:MinHeapFreeRatio=10",
                            "-Dfile.encoding=UTF-8",
                            f"-Duser.home={home}",
                            f"-Djava.util.prefs.userRoot={prefs}",
                            f"-Djava.util.prefs.systemRoot={prefs_system}",
                            "-cp", os.pathsep.join(classpath), MAIN_CLASS,
                            mode, scenario, str(count), str(args.timestamp_repeats),
                        ]
                        manifest["commands"].append({"name": name, "argv": command})
                        manifest_path.write_text(json.dumps(manifest, indent=2))
                        started = time.monotonic()
                        try:
                            completed = subprocess.run(command, cwd=REPO, env=env,
                                                       capture_output=True, text=True,
                                                       timeout=args.timeout)
                        except subprocess.TimeoutExpired as error:
                            output = (error.stdout or "") + (error.stderr or "")
                            (launch_dir / "stdout.log").write_text(output)
                            raise TimeoutError(f"{name} timed out; output: {launch_dir / 'stdout.log'}") from error
                        elapsed = time.monotonic() - started
                        (launch_dir / "stdout.log").write_text(completed.stdout + completed.stderr)
                        record = {
                            "name": name,
                            "mode": mode,
                            "scenario": scenario,
                            "rows_requested": count,
                            "repetition": repetition,
                            "elapsed_seconds": elapsed,
                            "exit_code": completed.returncode,
                            "output": completed.stdout.strip().splitlines(),
                        }
                        manifest["results"].append(record)
                        manifest_path.write_text(json.dumps(manifest, indent=2))
                        if completed.returncode:
                            raise RuntimeError(
                                f"{name} failed with {completed.returncode}; "
                                f"see {launch_dir / 'stdout.log'}"
                            )
                        print(f"[{run_number}] {name}: {elapsed:.2f}s", flush=True)

    summaries: dict[str, list[int]] = {}
    for result in manifest["results"]:
        for line in result["output"]:
            if not line.startswith("COMPACT_MEMORY "):
                continue
            fields = dict(part.split("=", 1) for part in line.split()[1:] if "=" in part)
            phase = fields.pop("phase", "unknown")
            key_prefix = f"{result['scenario']} {result['mode']} {result['rows_requested']} {phase}"
            for metric in ("heap_used_bytes", "heap_committed_bytes", "build_ms", "verify_ms",
                           "elapsed_ms", "allocated_bytes", "checksum", "metadata_rows",
                           "source_tag_rows", "dlt_rows", "canonical_timestamps", "fallback_timestamps"):
                value = fields.get(metric)
                if value is None:
                    continue
                try:
                    summaries.setdefault(f"{key_prefix} {metric}", []).append(int(value))
                except ValueError:
                    continue
    manifest["summary_medians"] = {
        key: {"n": len(values), "median": statistics.median(values)}
        for key, values in summaries.items()
    }
    manifest_path.write_text(json.dumps(manifest, indent=2))
    print(f"results: {run_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
