#!/usr/bin/env python3
"""Record a reproducible identity for the RAM experiment's checkout and generated fixtures."""
import hashlib
import json
import os
from pathlib import Path
import platform
import subprocess
import sys

ROOT = Path(__file__).resolve().parent.parent
FIXTURES = ROOT / "build" / "ram-poc" / "fixtures"


def run(*args):
    return subprocess.check_output(args, cwd=ROOT).decode("utf-8").strip()


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def main():
    java = Path(os.environ.get("JAVA_HOME", "")) / "bin" / "java" if os.environ.get("JAVA_HOME") else Path("java")
    java_version = subprocess.check_output([str(java), "-version"], stderr=subprocess.STDOUT).decode().strip()
    diff = subprocess.check_output(["git", "diff", "--binary", "HEAD", "--"], cwd=ROOT)
    untracked = run("git", "ls-files", "--others", "--exclude-standard", "-z").split("\0")
    untracked_hashes = {}
    prefixes = ("docs/LOG_STORAGE_", "docs/RAM_POC.md", "scripts/ram-poc-",
                "src/desktopTest/kotlin/com/indagium/ram/",
                "src/desktopMain/kotlin/com/indagium/storage/",
                "src/desktopTest/kotlin/com/indagium/storage/",
                "src/desktopMain/kotlin/com/indagium/utils/FlatRows.kt",
                "src/desktopTest/kotlin/com/indagium/FlatRowsProjectionTest.kt",
                "src/desktopTest/kotlin/com/indagium/debug/VisiblePageTest.kt",
                "src/desktopTest/kotlin/com/indagium/ui/VideoControllerOwnershipTest.kt")
    for name in filter(None, untracked):
        if not name.startswith(prefixes):
            continue
        path = ROOT / name
        if path.is_file() and not path.is_symlink():
            untracked_hashes[name] = digest(path)
    fixtures = {}
    for component in (ROOT / "build", ROOT / "build/ram-poc", FIXTURES):
        if component.is_symlink():
            raise OSError(f"symlinked fixture directory: {component}")
    if FIXTURES.is_dir():
        for path in sorted(FIXTURES.iterdir()):
            if path.is_file() and not path.is_symlink():
                fixtures[path.name] = {"bytes": path.stat().st_size, "sha256": digest(path)}
    print(json.dumps({
        "revision": run("git", "rev-parse", "HEAD"),
        "branch": run("git", "branch", "--show-current"),
        "tracked_diff_sha256": hashlib.sha256(diff).hexdigest(),
        "poc_untracked_sha256": untracked_hashes,
        "java_version": java_version,
        "platform": platform.platform(),
        "machine": platform.machine(),
        "cpu_count": os.cpu_count(),
        "fixtures": fixtures,
    }, indent=2, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except (OSError, subprocess.CalledProcessError) as error:
        print(f"manifest failed: {error}", file=sys.stderr)
        sys.exit(1)
