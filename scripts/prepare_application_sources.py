#!/usr/bin/env python3
"""Verify and bundle pinned Java/JVM sources; record the runtime shipped in an image."""

from __future__ import annotations

import argparse
import fnmatch
import gzip
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import tarfile
import tempfile
import zipfile

from prepare_media_sources import BundleError, ensure_sources, sha256_file, _add_file

ROOT = Path(__file__).resolve().parents[1]
LOCK = "licenses/application/application-source-lock.json"
PLATFORMS = {"linux-x64", "linux-arm64", "macos-arm64", "windows-x64"}
PROJECT_SOURCES = {"languagetool-source", "english-pos-dict-source", "openregex-source", "temurin-build"}


def validate_lock(root: Path = ROOT) -> dict:
    lock = json.loads((root / LOCK).read_text())
    if lock.get("schemaVersion") != 1 or set(lock.get("runtime", {}).get("platformMetadata", {})) != PLATFORMS:
        raise BundleError("Application source lock must cover the four release runtimes")
    inputs = lock.get("sourceArchives", [])
    ids, names = set(), set()
    for item in inputs:
        name = item.get("archive", "")
        if not name or PurePosixPath(name).name != name or "\\" in name or name in names:
            raise BundleError(f"Unsafe or duplicate application source filename: {name}")
        if item.get("id") in ids or not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")):
            raise BundleError("Duplicate application source id or invalid checksum")
        if not item.get("url", "").startswith("https://"):
            raise BundleError("Application sources must use HTTPS")
        ids.add(item["id"]); names.add(name)
    runtime = lock["runtime"]
    if set(runtime.get("binaries", {})) != PLATFORMS:
        raise BundleError("The source lock must pin every release JDK binary")
    for item in runtime["binaries"].values():
        name = item.get("archive", "")
        if not name or PurePosixPath(name).name != name or "\\" in name or name in {".", ".."}:
            raise BundleError("Unsafe JDK binary filename")
        if not item.get("url", "").startswith("https://") or not re.fullmatch(r"[0-9a-f]{64}", item.get("sha256", "")):
            raise BundleError("Invalid JDK binary URL or checksum")
    required = PROJECT_SOURCES | {runtime["sourceId"], runtime["buildSourceId"]}
    if not required <= ids or not set(runtime["platformMetadata"].values()) <= names:
        raise BundleError("Application/JVM source or build closure is incomplete")
    components = json.loads((root / "licenses/application/application-components.json").read_text())["components"]
    patterns = [pattern for c in components if "LGPL" in c["licenseExpression"] or "CDDL" in c["licenseExpression"] for pattern in c["modules"]]
    expected = set()
    for line in (root / "gradle.lockfile").read_text().splitlines():
        if "=" not in line or "desktopRuntimeClasspath" not in line.split("=", 1)[1]:
            continue
        coordinate = line.split("=", 1)[0]
        module = ":".join(coordinate.split(":")[:2])
        if any(fnmatch.fnmatchcase(module, p) for p in patterns):
            expected.add(coordinate)
    binaries = {i["coordinate"] for i in inputs if i.get("kind") == "binary-evidence"}
    poms = {i["coordinate"] for i in inputs if i.get("kind") == "build-pom"}
    sources = {i["coordinate"] for i in inputs if i.get("kind") == "source"}
    if binaries != expected or poms != expected or sources != expected - {"org.languagetool:english-pos-dict:0.6"}:
        raise BundleError("Locked application source coordinates do not match the LGPL/CDDL runtime dependencies")
    return lock


def validate_provenance(files: list, lock: dict) -> None:
    by_name = {item["archive"]: path for path, item in files}
    build_revision = next(item["upstreamRef"] for _, item in files if item["id"] == "temurin-build")
    for name in lock["runtime"]["platformMetadata"].values():
        metadata = json.loads(by_name[name].read_text())
        if not metadata["buildRef"].endswith("/" + build_revision):
            raise BundleError("Temurin build recipe does not match the pinned binary metadata")
        if metadata["version"]["version"] != lock["runtime"]["runtimeVersion"]:
            raise BundleError("Temurin binary metadata has an unexpected runtime version")
        platform = next(p for p, n in lock["runtime"]["platformMetadata"].items() if n == name)
        if metadata["sha256"] != lock["runtime"]["binaries"][platform]["sha256"]:
            raise BundleError("Temurin binary checksum disagrees with its pinned build metadata")
    required_members = {
        lock["runtime"]["sourceId"]: ("src/hotspot/", "make/", "LICENSE"),
        "english-pos-dict-source": ("src-dict/", "build-dicts-from-src.sh", "pom.xml"),
        "languagetool-source": ("pom.xml", "languagetool-core/"),
        "openregex-source": ("pom.xml", "src/"),
        "temurin-build": ("makejdk-any-platform.sh",),
    }
    for path, item in files:
        if item["id"] not in required_members:
            continue
        with tarfile.open(path) as archive:
            names = [member.name.partition("/")[2] for member in archive]
        for member in required_members[item["id"]]:
            if not any(n == member or n.startswith(member) for n in names):
                raise BundleError(f"Preferred source/build material is missing from {item['id']}: {member}")


def create_bundle(version: str, cache: Path, output: Path, fetch: bool, root: Path = ROOT) -> Path:
    if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][\w.-]+)?", version):
        raise BundleError("Invalid application version")
    lock = validate_lock(root)
    files = ensure_sources(lock["sourceArchives"], cache, fetch)
    validate_provenance(files, lock)
    output.mkdir(parents=True, exist_ok=True)
    name = f"Indagium-{version}-application-sources"
    target = output / (name + ".tar.gz")
    with tempfile.TemporaryDirectory(prefix="indagium-app-sources-") as temporary:
        staging = Path(temporary) / name
        (staging / "sources").mkdir(parents=True)
        (staging / "legal").mkdir()
        for path, item in files:
            shutil.copyfile(path, staging / "sources" / item["archive"])
        shutil.copytree(root / "licenses/application", staging / "legal/application")
        inventory = json.loads((root / "licenses/application/application-components.json").read_text())
        for relative in {p for c in inventory["components"] for p in c["licenseFiles"]}:
            source = root / "licenses" / relative if relative.startswith(("application/", "third-party/")) else root / "src/desktopMain/resources/licenses" / relative
            target_text = staging / "legal" / relative
            target_text.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source, target_text)
        shutil.copyfile(root / "THIRD_PARTY_NOTICES.md", staging / "legal/THIRD_PARTY_NOTICES.md")
        shutil.copyfile(root / "licenses/application/SOURCE-BUNDLE.md", staging / "README.md")
        manifest = {
            "schemaVersion": 1, "applicationVersion": version, "runtime": lock["runtime"],
            "sourceLockSha256": sha256_file(root / LOCK),
            "files": [{"path": p.relative_to(staging).as_posix(), "sha256": sha256_file(p), "size": p.stat().st_size}
                      for p in sorted(staging.rglob("*")) if p.is_file()],
        }
        (staging / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
        with target.open("wb") as stream, gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0) as zipped:
            with tarfile.open(fileobj=zipped, mode="w") as archive:
                for path in sorted(staging.rglob("*")):
                    if path.is_file():
                        _add_file(archive, path, name + "/" + path.relative_to(staging).as_posix())
    digest = sha256_file(target)
    Path(str(target) + ".sha256").write_text(f"{digest}  {target.name}\n")
    manifest["archive"] = {"path": target.name, "sha256": digest, "size": target.stat().st_size}
    Path(str(target) + ".manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    return target


def release_properties(path: Path) -> dict:
    return dict((k, v.strip('"')) for line in path.read_text().splitlines() if "=" in line for k, v in [line.split("=", 1)])


def find_runtime(image: Path) -> Path:
    candidates = [p.parent for p in image.rglob("release") if p.is_file() and "MODULES=" in p.read_text(errors="replace")]
    if len(candidates) != 1:
        raise BundleError(f"Expected one packaged JVM release file, found {len(candidates)}")
    return candidates[0]


def runtime_snapshot(runtime: Path) -> dict:
    paths = [runtime / "release", runtime / "lib/modules"] + sorted((runtime / "legal").rglob("*"))
    if not (runtime / "lib/modules").is_file() or not any(p.is_file() for p in paths[2:]):
        raise BundleError("Packaged JVM modules or legal notices are missing")
    return {p.relative_to(runtime).as_posix(): sha256_file(p) for p in paths if p.is_file()}


def record_runtime(image: Path, jdk: Path, platform: str, destination: Path, root: Path = ROOT) -> None:
    lock = validate_lock(root)
    source = release_properties(jdk / "release")
    pinned = lock["runtime"]
    if source.get("IMPLEMENTOR") != pinned["vendor"] or source.get("JAVA_RUNTIME_VERSION") != pinned["runtimeVersion"]:
        raise BundleError("Official EULA packaging requires the exact Temurin build in application-source-lock.json")
    runtime = find_runtime(image)
    props = release_properties(runtime / "release")
    if props.get("JAVA_VERSION") != pinned["javaVersion"]:
        raise BundleError("Packaged JVM does not match the source JDK")
    destination.write_text(json.dumps({
        "schemaVersion": 1, "platform": platform, "runtime": pinned,
        "sourceJdk": source, "modules": props["MODULES"].split(),
        "files": runtime_snapshot(runtime),
    }, indent=2) + "\n")


def validate_runtime(image: Path, root: Path = ROOT) -> dict:
    manifests = list(image.rglob("runtime-license-manifest.json"))
    if len(manifests) != 1:
        raise BundleError("Missing or ambiguous packaged JVM licence manifest")
    manifest = json.loads(manifests[0].read_text())
    lock = validate_lock(root)
    if manifest.get("runtime") != lock["runtime"] or manifest.get("platform") not in PLATFORMS:
        raise BundleError("Packaged JVM source mapping is stale")
    source = manifest.get("sourceJdk", {})
    if source.get("IMPLEMENTOR") != lock["runtime"]["vendor"] or source.get("JAVA_RUNTIME_VERSION") != lock["runtime"]["runtimeVersion"]:
        raise BundleError("Packaged source JDK identity does not match the pinned runtime")
    runtime = find_runtime(image)
    properties = release_properties(runtime / "release")
    if properties.get("JAVA_VERSION") != lock["runtime"]["javaVersion"] or manifest.get("modules") != properties["MODULES"].split():
        raise BundleError("Packaged JVM version or modules do not match the manifest")
    if manifest.get("files") != runtime_snapshot(runtime):
        raise BundleError("Packaged JVM modules or legal notices differ from the inspected image")
    return manifest


def install_jdk(platform: str, output: Path, cache: Path, root: Path = ROOT) -> Path:
    lock = validate_lock(root)
    binary = lock["runtime"]["binaries"][platform]
    [(archive, _)] = ensure_sources([binary], cache, True)
    destination = output / platform
    # Extract only a checksum-verified vendor archive, with path traversal checks.
    with tempfile.TemporaryDirectory(prefix="temurin-", dir=output) as temporary:
        stage = Path(temporary)
        if zipfile.is_zipfile(archive):
            with zipfile.ZipFile(archive) as zipped:
                for member in zipped.namelist():
                    if not (stage / member).resolve().is_relative_to(stage.resolve()):
                        raise BundleError("Unsafe JVM archive member")
                zipped.extractall(stage)
        else:
            with tarfile.open(archive) as tar:
                tar.extractall(stage, filter="data")
        homes = [p.parent for p in stage.rglob("release") if "JAVA_VERSION=" in p.read_text(errors="replace")]
        if len(homes) != 1:
            raise BundleError("Cannot locate the installed vendor JDK")
        props = release_properties(homes[0] / "release")
        if props.get("JAVA_RUNTIME_VERSION") != lock["runtime"]["runtimeVersion"] or props.get("IMPLEMENTOR") != lock["runtime"]["vendor"]:
            raise BundleError("Downloaded JDK release does not match the source lock")
        if destination.exists():
            shutil.rmtree(destination)
        shutil.copytree(homes[0], destination, symlinks=True)
    return destination.resolve()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-version")
    parser.add_argument("--inventory-only", action="store_true")
    parser.add_argument("--fetch-missing", action="store_true")
    parser.add_argument("--source-cache", type=Path, default=ROOT / "build/application-source-cache")
    parser.add_argument("--output-dir", type=Path, default=ROOT / "build")
    parser.add_argument("--record-runtime", type=Path)
    parser.add_argument("--install-jdk", action="store_true")
    parser.add_argument("--validate-runtime", type=Path)
    parser.add_argument("--jdk-home", type=Path)
    parser.add_argument("--platform", choices=sorted(PLATFORMS))
    parser.add_argument("--manifest", type=Path)
    args = parser.parse_args()
    if args.install_jdk:
        if not args.platform:
            parser.error("--install-jdk requires --platform")
        args.output_dir.mkdir(parents=True, exist_ok=True)
        print(install_jdk(args.platform, args.output_dir, args.source_cache))
    elif args.inventory_only:
        validate_lock(); print("Application/JVM source inventory validates.")
    elif args.record_runtime:
        if not all([args.jdk_home, args.platform, args.manifest]):
            parser.error("Runtime recording requires --jdk-home, --platform and --manifest")
        record_runtime(args.record_runtime, args.jdk_home, args.platform, args.manifest)
    elif args.validate_runtime:
        validate_runtime(args.validate_runtime); print("Packaged JVM notices and source mapping validate.")
    else:
        if not args.app_version:
            parser.error("--app-version is required for source assembly")
        print(create_bundle(args.app_version, args.source_cache, args.output_dir, args.fetch_missing))


if __name__ == "__main__":
    main()
