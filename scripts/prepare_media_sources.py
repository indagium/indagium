#!/usr/bin/env python3
"""Validate pinned codec sources and assemble the release media-source archive."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import zipfile
from pathlib import Path, PurePosixPath
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
LEGAL_ROOT = REPO_ROOT / "licenses" / "third-party"
LOCK_PATH = LEGAL_ROOT / "media-source-lock.json"
INVENTORY_PATH = LEGAL_ROOT / "media-components.json"
VERSION_PATTERN = re.compile(r"^\d+\.\d+\.\d+(?:[-+][0-9A-Za-z.-]+)?$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
SUPPORTED_RUNTIME_VERSIONS = {
    "ffmpeg": "8.0.1-1.5.13",
    "javacv": "1.5.13",
    "javacpp": "1.5.13",
    "zstdJni": "1.5.7-4",
}
PLATFORM_KEYS = {"macos-arm64", "windows-x64", "linux-x64", "linux-arm64"}
CONFIG_KEYS = {
    "macos-arm64": "ffmpeg-8.0.1-1.5.13-macosx-arm64.jar",
    "windows-x64": "ffmpeg-8.0.1-1.5.13-windows-x86_64.jar",
    "linux-x64": "ffmpeg-8.0.1-1.5.13-linux-x86_64-c7137fb1948714af2be7b888641b7f8.jar",
    "linux-arm64": "ffmpeg-8.0.1-1.5.13-linux-arm64.jar",
}
REQUIRED_COMPONENT_IDS = {
    "javacv", "javacpp", "ffmpeg", "zstd-jni", "zstandard-native", "libaom", "svt-av1", "zlib", "lame",
    "speex", "opencore-amr", "vo-amrwbenc", "openssl", "openh264", "libvpx", "alsa-lib",
    "freetype", "harfbuzz", "opus", "libxml2", "srt", "libwebp", "zimg", "gcc-libatomic",
    "gcc-runtime-windows", "mingw-w64-crt", "winpthreads", "libva", "libva-drm", "libdrm",
    "bcm-host", "vchiq-arm", "vcos", "alsa-runtime", "mfx-dispatch", "nv-codec-headers",
}
EXPECTED_PLATFORM_COMPONENT_IDS = {
    "macos-arm64": {
        "javacv", "javacpp", "ffmpeg", "zstd-jni", "zstandard-native", "libaom", "svt-av1", "zlib", "lame", "speex",
        "opencore-amr", "vo-amrwbenc", "openssl", "openh264", "libvpx", "freetype", "harfbuzz", "opus",
        "libxml2", "srt", "libwebp", "zimg", "gcc-libatomic", "nv-codec-headers",
    },
    "windows-x64": {
        "javacv", "javacpp", "ffmpeg", "zstd-jni", "zstandard-native", "libaom", "svt-av1", "zlib", "lame", "speex",
        "opencore-amr", "vo-amrwbenc", "openssl", "openh264", "libvpx", "freetype", "harfbuzz", "opus",
        "libxml2", "srt", "libwebp", "zimg", "gcc-runtime-windows", "mingw-w64-crt", "winpthreads",
        "mfx-dispatch", "nv-codec-headers",
    },
    "linux-x64": {
        "javacv", "javacpp", "ffmpeg", "zstd-jni", "zstandard-native", "libaom", "svt-av1", "zlib", "lame", "speex",
        "opencore-amr", "vo-amrwbenc", "openssl", "openh264", "libvpx", "freetype", "harfbuzz", "opus",
        "libxml2", "srt", "libwebp", "zimg", "libva", "libva-drm", "libdrm", "mfx-dispatch",
        "nv-codec-headers",
    },
    "linux-arm64": {
        "javacv", "javacpp", "ffmpeg", "zstd-jni", "zstandard-native", "libaom", "svt-av1", "zlib", "lame", "speex",
        "opencore-amr", "vo-amrwbenc", "openssl", "openh264", "libvpx", "alsa-lib", "freetype", "harfbuzz",
        "opus", "libxml2", "srt", "libwebp", "zimg", "alsa-lib", "bcm-host", "vchiq-arm", "vcos", "alsa-runtime",
        "nv-codec-headers",
    },
}
EXPECTED_SOURCE_VERSIONS = {
    "alsa-lib": "1.2.14",
    "bytedeco-recipe": "1.5.13",
    "ffmpeg": "8.0.1",
    "ffmpeg-binding-source": "8.0.1-1.5.13",
    "freetype": "2.14.1",
    "gcc-formula-patch": "patch commit 8bbfd6c920e63f6ed1bf143771bfbfed0a3aaa1d",
    "gcc-runtime": "13.4.0",
    "gcc-runtime-windows": "14.1.0",
    "harfbuzz": "12.3.0",
    "javacpp-source": "1.5.13",
    "javacv-source": "1.5.13",
    "lame": "3.100",
    "libaom": "3.9.1",
    "libdrm-source": "2.4.113-2~ubuntu0.22.04.1",
    "libdrm-source-debian": "2.4.113-2~ubuntu0.22.04.1",
    "libdrm-source-orig": "2.4.113",
    "libva-source": "2.14.0-1",
    "libva-source-debian": "2.14.0-1",
    "libva-source-orig": "2.14.0",
    "libvpx": "1.15.2",
    "libwebp": "1.6.0",
    "libxml2": "2.9.12",
    "mfx-dispatch": "1.35.1",
    "mingw-w64": "commit 90abf784ac344fe558e7e22cb5411d807862888d",
    "msys2-gcc-package": "14.1.0-3",
    "nasm": "2.14",
    "nv-codec-headers": "13.0.19.0",
    "opencore-amr": "0.1.6",
    "openh264": "2.6.0",
    "openssl": "3.5.5",
    "opus": "1.3.1",
    "raspberrypi-userland": "commit a54a0dbb2b8dcf9bafdddfc9a9374fb51d97e976",
    "speex": "1.2.1",
    "srt": "1.5.4",
    "svt-av1": "3.1.2",
    "vo-amrwbenc": "0.1.3",
    "x264": "stable snapshot pinned to commit b35605ace3ddf7c1a5d67a2eb553f034aef41d55",
    "x265": "3.4",
    "zimg": "3.0.6",
    "zlib": "1.3.2",
    "zstd": "1.5.7",
    "zstd-jni": "1.5.7-4",
}
REQUIRED_SOURCE_IDS = set(EXPECTED_SOURCE_VERSIONS)
COMPONENT_REQUIRED_SOURCES = {
    "javacv": {"javacv-source", "bytedeco-recipe"},
    "javacpp": {"javacpp-source", "bytedeco-recipe"},
    "ffmpeg": {"ffmpeg", "bytedeco-recipe"},
    "zstd-jni": {"zstd-jni"}, "zstandard-native": {"zstd"},
    "libaom": {"libaom"},
    "svt-av1": {"svt-av1", "libaom"},
    "zlib": {"zlib"}, "lame": {"lame"}, "speex": {"speex"},
    "opencore-amr": {"opencore-amr"}, "vo-amrwbenc": {"vo-amrwbenc"},
    "openssl": {"openssl"}, "openh264": {"openh264"}, "libvpx": {"libvpx"},
    "alsa-lib": {"alsa-lib"}, "freetype": {"freetype"}, "harfbuzz": {"harfbuzz"},
    "opus": {"opus"}, "libxml2": {"libxml2"}, "srt": {"srt"}, "libwebp": {"libwebp"},
    "zimg": {"zimg"},
    "gcc-libatomic": {"gcc-runtime", "gcc-formula-patch", "bytedeco-recipe"},
    "gcc-runtime-windows": {"gcc-runtime-windows", "msys2-gcc-package", "mingw-w64"},
    "mingw-w64-crt": {"mingw-w64", "msys2-gcc-package"},
    "winpthreads": {"mingw-w64", "msys2-gcc-package"},
    "libva": {"libva-source", "libva-source-orig", "libva-source-debian"},
    "libva-drm": {"libva-source", "libva-source-orig", "libva-source-debian"},
    "libdrm": {"libdrm-source", "libdrm-source-orig", "libdrm-source-debian"},
    "bcm-host": {"raspberrypi-userland"}, "vchiq-arm": {"raspberrypi-userland"},
    "vcos": {"raspberrypi-userland"}, "alsa-runtime": {"alsa-lib"},
    "mfx-dispatch": {"mfx-dispatch"}, "nv-codec-headers": {"nv-codec-headers"},
}
EXPECTED_BINARY_ARTIFACT_IDS = {
    "ffmpeg-base", "ffmpeg-macosx-arm64", "ffmpeg-windows-x86_64", "ffmpeg-linux-x86_64",
    "ffmpeg-linux-arm64", "zstd-jni-macos-arm64", "zstd-jni-windows-x64",
    "zstd-jni-linux-x64", "zstd-jni-linux-arm64",
}
REQUIRED_BUILD_INPUT_PATHS = {
    "licenses/third-party/SOURCE-BUNDLE.md",
    "licenses/third-party/recipe-inputs/classifier-binary-evidence.json",
    "licenses/third-party/recipe-inputs/ffmpeg-classifier-configurations.json",
    "licenses/third-party/recipe-inputs/gcc-13.4.0.diff",
    "licenses/third-party/recipe-inputs/gcc13-homebrew.rb",
    "licenses/third-party/recipe-inputs/indagium-pinned-build-inputs.patch",
    "licenses/third-party/recipe-inputs/msys2-gcc14-PKGBUILD",
}


class BundleError(RuntimeError):
    """Raised when source or legal inputs cannot be verified."""


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BundleError(f"Cannot read valid JSON from {path}: {error}") from error
    if not isinstance(value, dict):
        raise BundleError(f"Expected a JSON object in {path}")
    return value


def validate_inventory(inventory: dict[str, Any], legal_root: Path = LEGAL_ROOT) -> list[dict[str, Any]]:
    components = inventory.get("components")
    hashes = inventory.get("licenseTextHashes")
    if inventory.get("schemaVersion") != 1 or not isinstance(components, list):
        raise BundleError("Unknown media component inventory schema")
    if not isinstance(hashes, dict) or not hashes:
        raise BundleError("Media inventory is missing pinned full-text hashes")

    referenced: set[str] = set()
    ids: set[str] = set()
    for component in components:
        if not isinstance(component, dict):
            raise BundleError("Malformed component entry in media inventory")
        component_id = component.get("id")
        if not isinstance(component_id, str) or component_id in ids:
            raise BundleError(f"Missing or duplicate media component id: {component_id!r}")
        ids.add(component_id)
        if not isinstance(component.get("licenseExpression"), str) or not component["licenseExpression"].strip():
            raise BundleError(f"Component {component_id} has no SPDX/custom license identifier")
        if not isinstance(component.get("platforms"), list):
            raise BundleError(f"Component {component_id} has no applicable platform list")
        if not isinstance(component.get("copyrightAndAttribution"), list) or not component["copyrightAndAttribution"]:
            raise BundleError(f"Component {component_id} has no copyright/attribution record")
        if not isinstance(component.get("sourceIds"), list) or not isinstance(component.get("sourceReferences"), list):
            raise BundleError(f"Component {component_id} has no source reference association")
        files = component.get("licenseFiles")
        if not isinstance(files, list) or not files:
            raise BundleError(f"Component {component_id} has no full licence/notice reference")
        for relative in files:
            if not isinstance(relative, str):
                raise BundleError(f"Invalid licence path for {component_id}")
            path = PurePosixPath(relative)
            if path.is_absolute() or ".." in path.parts:
                raise BundleError(f"Unsafe licence path in component {component_id}: {relative}")
            referenced.add(relative)

    for relative in sorted(referenced):
        expected = hashes.get(relative)
        if not isinstance(expected, str) or not SHA256_PATTERN.fullmatch(expected):
            raise BundleError(f"Missing pinned SHA-256 for legal resource {relative}")
        path = legal_root / relative
        if not path.is_file():
            raise BundleError(f"Required licence or notice is missing: {path}")
        actual = sha256_file(path)
        if actual != expected:
            raise BundleError(f"Licence/notice checksum mismatch for {relative}: expected {expected}, got {actual}")
        if not path.read_bytes().strip():
            raise BundleError(f"Required licence or notice is empty: {path}")

    if referenced != set(hashes):
        missing = sorted(referenced - set(hashes))
        extra = sorted(set(hashes) - referenced)
        raise BundleError(f"Legal resource hash map mismatch; missing={missing}, unreferenced={extra}")

    missing_components = REQUIRED_COMPONENT_IDS - ids
    if missing_components:
        raise BundleError(f"Required native media components are absent from inventory: {sorted(missing_components)}")
    if "source-delivery" not in ids:
        raise BundleError("Media source and rebuild instructions are absent from inventory")
    provenance = inventory.get("legalTextProvenance")
    if not isinstance(provenance, dict) or set(provenance) != referenced:
        raise BundleError("Every full legal text must map to a pinned upstream source/member")
    for relative, record in provenance.items():
        if relative == "SOURCE-BUNDLE.md":
            if record.get("repositoryPath") != "licenses/third-party/SOURCE-BUNDLE.md":
                raise BundleError("Source instructions must identify their repository path")
            continue
        if not all(isinstance(record.get(key), str) and record[key] for key in ("sourceId", "upstreamRef", "archive", "memberPath")):
            raise BundleError(f"Incomplete upstream legal-text provenance: {relative}")
        if not SHA256_PATTERN.fullmatch(record.get("archiveSha256", "")):
            raise BundleError(f"Invalid upstream archive hash for legal text {relative}")
    refs = inventory.get("licenseRefDefinitions")
    if not isinstance(refs, dict) or not refs:
        raise BundleError("Custom license identifiers must be documented")
    for component in components:
        expressions = component["licenseExpression"]
        refs_used = set(re.findall(r"LicenseRef-[A-Za-z0-9.-]+", expressions))
        if not refs_used <= set(refs):
            raise BundleError(f"Undocumented custom license identifiers for {component['id']}: {sorted(refs_used - set(refs))}")

    source_docs = legal_root / "SOURCE-BUNDLE.md"
    if not source_docs.is_file() or not source_docs.read_text(encoding="utf-8").strip():
        raise BundleError("Media source and rebuild instructions are missing")
    return components


def validate_lock(
    lock: dict[str, Any],
    app_version: str,
    runtime_versions: dict[str, str],
    inventory: dict[str, Any],
) -> list[dict[str, Any]]:
    if not VERSION_PATTERN.fullmatch(app_version):
        raise BundleError(f"Unknown or malformed app version: {app_version!r}")
    if lock.get("schemaVersion") != 1:
        raise BundleError("Unknown media source lock schema")
    recipe = lock.get("recipe")
    archives = lock.get("sourceArchives")
    if not isinstance(recipe, dict) or not isinstance(archives, list) or not archives:
        raise BundleError("Media source lock is incomplete")
    if recipe.get("revision") != "60df69b176e3623da1d53db432a5a3a82a13f2ea":
        raise BundleError("Unsupported Bytedeco FFmpeg source recipe revision")

    by_id: dict[str, dict[str, Any]] = {}
    filenames: set[str] = set()
    for source in archives:
        if not isinstance(source, dict):
            raise BundleError("Malformed source archive entry")
        source_id = source.get("id")
        filename = source.get("archive")
        digest = source.get("sha256")
        url = source.get("url")
        ref = source.get("upstreamRef")
        if not isinstance(source_id, str) or source_id in by_id:
            raise BundleError(f"Missing or duplicate source id: {source_id!r}")
        if not isinstance(filename, str) or Path(filename).name != filename or filename in filenames:
            raise BundleError(f"Missing or duplicate archive filename: {filename!r}")
        if not isinstance(digest, str) or not SHA256_PATTERN.fullmatch(digest):
            raise BundleError(f"Missing or invalid source archive hash for {source_id}")
        if not isinstance(url, str) or not url.startswith("https://"):
            raise BundleError(f"Source URL must use verified HTTPS: {source_id}")
        if not isinstance(ref, str) or not ref.strip():
            raise BundleError(f"Source archive has no upstream version or immutable reference: {source_id}")
        filenames.add(filename)
        by_id[source_id] = source

    if set(by_id) != REQUIRED_SOURCE_IDS:
        missing = sorted(REQUIRED_SOURCE_IDS - set(by_id))
        unexpected = sorted(set(by_id) - REQUIRED_SOURCE_IDS)
        raise BundleError(f"Source closure mismatch; missing={missing}, unexpected={unexpected}")
    if lock.get("requiredSourceIds") != sorted(REQUIRED_SOURCE_IDS):
        raise BundleError("Required native source archive list is missing or changed")
    if lock.get("requiredComponentIds") != sorted(REQUIRED_COMPONENT_IDS):
        raise BundleError("Required linked native and wrapper component list is missing or changed")
    for source_id, expected in EXPECTED_SOURCE_VERSIONS.items():
        if by_id[source_id].get("version") != expected:
            raise BundleError(f"Unsupported {source_id} source version: expected {expected}, lock contains {by_id[source_id].get('version')}")

    for key, expected in SUPPORTED_RUNTIME_VERSIONS.items():
        actual = runtime_versions.get(key)
        if actual != expected:
            raise BundleError(f"Unsupported runtime version for {key}: expected {expected}, received {actual}")

    recipe_archive = by_id.get("bytedeco-recipe")
    if recipe_archive is None or any(
        recipe.get(key) != recipe_archive.get(source_key)
        for key, source_key in (("archive", "archive"), ("sha256", "sha256"), ("url", "url"))
    ):
        raise BundleError("Pinned Bytedeco recipe metadata does not resolve to a required source archive")
    if recipe_archive.get("version") != recipe.get("version"):
        raise BundleError("Pinned Bytedeco recipe version does not match its source archive")

    component_versions = {
        item.get("id"): item.get("version")
        for item in inventory.get("components", [])
        if isinstance(item, dict) and item.get("id") != "source-delivery"
    }
    if lock.get("componentSourceVersions") != component_versions:
        raise BundleError("Component versions in the source lock do not match the legal inventory")
    component_sources = lock.get("componentSourceIds")
    if not isinstance(component_sources, dict) or set(component_sources) != REQUIRED_COMPONENT_IDS:
        missing = sorted(REQUIRED_COMPONENT_IDS - set(component_sources or {}))
        extra = sorted(set(component_sources or {}) - REQUIRED_COMPONENT_IDS)
        raise BundleError(f"Component-to-source closure mismatch; missing={missing}, unexpected={extra}")
    for component_id, expected_sources in COMPONENT_REQUIRED_SOURCES.items():
        actual_sources = component_sources.get(component_id)
        if not isinstance(actual_sources, list) or set(actual_sources) != expected_sources:
            raise BundleError(f"Required source inputs changed for component {component_id}")
        if not set(actual_sources) <= set(by_id):
            raise BundleError(f"Source archive missing for component {component_id}")

    component_by_id = {item["id"]: item for item in inventory.get("components", [])}
    platform_membership = {component_id: [] for component_id in component_by_id}
    for platform, platform_data in inventory.get("platforms", {}).items():
        for component_id in platform_data.get("componentIds", []):
            if component_id in platform_membership:
                platform_membership[component_id].append(platform)
    for component_id in REQUIRED_COMPONENT_IDS:
        component = component_by_id[component_id]
        expected_sources = component_sources[component_id]
        if component.get("sourceIds") != expected_sources:
            raise BundleError(f"Inventory source IDs do not match locked source closure for {component_id}")
        expected_refs = [
            {"sourceId": sid, "version": by_id[sid]["version"], "upstreamRef": by_id[sid]["upstreamRef"], "archive": by_id[sid]["archive"], "sha256": by_id[sid]["sha256"]}
            for sid in expected_sources
        ]
        if component.get("sourceReferences") != expected_refs:
            raise BundleError(f"Inventory upstream source references do not match the pinned lock for {component_id}")
        if set(component.get("platforms", [])) != set(platform_membership[component_id]):
            raise BundleError(f"Component platform scope does not match platform mapping for {component_id}")

    expected_platforms = inventory.get("platforms", {})
    platform_sources = lock.get("platformComponentIds")
    if not isinstance(platform_sources, dict) or set(platform_sources) != PLATFORM_KEYS:
        raise BundleError("Source lock must enumerate all four supported native targets")
    if set(expected_platforms) != PLATFORM_KEYS:
        raise BundleError("Legal inventory must enumerate all four supported native targets")
    for platform in sorted(PLATFORM_KEYS):
        expected_ids = EXPECTED_PLATFORM_COMPONENT_IDS[platform]
        source_ids = platform_sources.get(platform)
        inventory_ids = expected_platforms[platform].get("componentIds")
        if not isinstance(source_ids, list) or set(source_ids) != expected_ids:
            raise BundleError(f"Incomplete native component coverage for {platform}")
        if not isinstance(inventory_ids, list) or set(inventory_ids) != expected_ids:
            raise BundleError(f"Legal inventory platform mapping is incomplete for {platform}")

    for relative, record in inventory["legalTextProvenance"].items():
        if relative == "SOURCE-BUNDLE.md":
            continue
        source = by_id.get(record["sourceId"])
        if source is None or source["archive"] != record["archive"] or source["sha256"] != record["archiveSha256"] or source["upstreamRef"] != record["upstreamRef"]:
            raise BundleError(f"Legal text provenance does not resolve to the pinned source archive: {relative}")

    artifacts = lock.get("binaryArtifacts")
    if not isinstance(artifacts, list):
        raise BundleError("Pinned binary classifier artifact checksums are missing")
    artifact_ids = [item.get("id") for item in artifacts if isinstance(item, dict)]
    if set(artifact_ids) != EXPECTED_BINARY_ARTIFACT_IDS or len(artifact_ids) != len(EXPECTED_BINARY_ARTIFACT_IDS):
        raise BundleError("Pinned binary classifier artifact closure is incomplete")
    for artifact in artifacts:
        if not isinstance(artifact.get("sha256"), str) or not SHA256_PATTERN.fullmatch(artifact["sha256"]):
            raise BundleError(f"Binary classifier SHA-256 is invalid: {artifact.get('id')}")
        if not isinstance(artifact.get("url"), str) or not artifact["url"].startswith("https://"):
            raise BundleError(f"Binary artifact URL must use HTTPS: {artifact.get('id')}")
    configured = inventory.get("runtimePackageResources", {})
    expected_coordinates = {
        "javacv": f"org.bytedeco:javacv:{runtime_versions['javacv']}",
        "javacpp": f"org.bytedeco:javacpp:{runtime_versions['javacpp']}",
        "ffmpeg": f"org.bytedeco:ffmpeg:{runtime_versions['ffmpeg']}",
        "zstdJni": f"com.github.luben:zstd-jni:{runtime_versions['zstdJni']}",
    }
    if configured != expected_coordinates:
        raise BundleError("Runtime package coordinates do not match the validated media versions")

    return archives


def verify_build_inputs(lock: dict[str, Any], repo_root: Path = REPO_ROOT) -> list[tuple[Path, str]]:
    items = lock.get("buildInputFiles", [])
    if not isinstance(items, list):
        raise BundleError("Malformed source build-input list")
    paths = [item.get("path") for item in items if isinstance(item, dict)]
    if len(paths) != len(set(paths)) or set(paths) != REQUIRED_BUILD_INPUT_PATHS:
        missing = sorted(REQUIRED_BUILD_INPUT_PATHS - set(paths))
        extra = sorted(set(paths) - REQUIRED_BUILD_INPUT_PATHS)
        raise BundleError(f"Pinned recipe input closure mismatch; missing={missing}, unexpected={extra}")
    verified: list[tuple[Path, str]] = []
    for item in items:
        if not isinstance(item, dict):
            raise BundleError("Malformed pinned build-input file")
        rel = item.get("path")
        expected = item.get("sha256")
        if not isinstance(rel, str) or not isinstance(expected, str) or not SHA256_PATTERN.fullmatch(expected):
            raise BundleError("Build-input file must have a path and pinned SHA-256")
        path = repo_root / rel
        if not path.is_file():
            raise BundleError(f"Required native build input is missing: {path}")
        actual = sha256_file(path)
        if actual != expected:
            raise BundleError(f"Build-input checksum mismatch for {rel}: expected {expected}, got {actual}")
        verified.append((path, rel))
    return verified


def ensure_sources(archives: list[dict[str, Any]], cache_dir: Path, fetch_missing: bool) -> list[tuple[Path, dict[str, Any]]]:
    cache_dir.mkdir(parents=True, exist_ok=True)
    verified: list[tuple[Path, dict[str, Any]]] = []
    for source in archives:
        path = cache_dir / source["archive"]
        if not path.exists():
            if not fetch_missing:
                raise BundleError(f"Pinned source archive is missing from cache: {path}")
            temporary = path.with_suffix(path.suffix + ".download")
            try:
                subprocess.run(
                    ["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2", source["url"], "-o", str(temporary)],
                    check=True,
                )
            except (OSError, subprocess.CalledProcessError) as error:
                temporary.unlink(missing_ok=True)
                raise BundleError(f"Could not download pinned source archive {source['id']}: {error}") from error
            if sha256_file(temporary) != source["sha256"]:
                actual = sha256_file(temporary)
                temporary.unlink(missing_ok=True)
                raise BundleError(f"Downloaded source checksum mismatch for {source['id']}: expected {source['sha256']}, got {actual}")
            temporary.replace(path)
        if not path.is_file():
            raise BundleError(f"Pinned source archive is not a regular file: {path}")
        actual = sha256_file(path)
        if actual != source["sha256"]:
            raise BundleError(f"Cached source checksum mismatch for {source['id']}: expected {source['sha256']}, got {actual}")
        verified.append((path, source))
    return verified


def validate_legal_provenance_members(
    inventory: dict[str, Any], source_files: list[tuple[Path, dict[str, Any]]]
) -> None:
    by_id = {source["id"]: (path, source) for path, source in source_files}
    member_cache: dict[str, set[str]] = {}
    for relative, record in inventory["legalTextProvenance"].items():
        if relative == "SOURCE-BUNDLE.md":
            continue
        path, source = by_id[record["sourceId"]]
        member_path = record["memberPath"].lstrip("/")
        try:
            if source["id"] not in member_cache:
                if zipfile.is_zipfile(path):
                    with zipfile.ZipFile(path) as archive:
                        member_cache[source["id"]] = set(archive.namelist())
                elif tarfile.is_tarfile(path):
                    with tarfile.open(path, "r:*") as archive:
                        member_cache[source["id"]] = {member.name for member in archive.getmembers()}
                else:
                    member_cache[source["id"]] = set()
            members = member_cache[source["id"]]
            found = any(name == member_path or name.endswith("/" + member_path) for name in members)
            if not members and not found:
                # A few pinned Debian source descriptors are standalone text files, not archives.
                found = source["archive"] == member_path
        except (OSError, tarfile.TarError, zipfile.BadZipFile) as error:
            raise BundleError(f"Cannot inspect legal provenance archive {source['archive']}: {error}") from error
        if not found:
            raise BundleError(f"Pinned source archive does not contain the legal text provenance member for {relative}: {member_path}")


def verify_classifier_configurations(repo_root: Path = REPO_ROOT) -> dict[str, str]:
    path = repo_root / "licenses/third-party/recipe-inputs/ffmpeg-classifier-configurations.json"
    try:
        configs = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BundleError(f"Could not read inspected FFmpeg classifier configurations: {error}") from error
    if not isinstance(configs, dict) or set(configs) != set(CONFIG_KEYS.values()):
        raise BundleError("The inspected FFmpeg configuration must cover exactly four supported classifiers")

    joined: dict[str, str] = {}
    for platform, config_key in CONFIG_KEYS.items():
        entries = configs.get(config_key)
        if not isinstance(entries, list) or len(entries) != 1 or not isinstance(entries[0], str):
            raise BundleError(f"Expected one inspected FFmpeg configuration for {platform}")
        command = entries[0]
        if "--enable-shared" not in command or "--enable-version3" not in command:
            raise BundleError(f"FFmpeg is not the inspected shared LGPLv3+ build on {platform}")
        if "--enable-gpl" in command or "--enable-nonfree" in command:
            raise BundleError(f"Unexpected GPL or nonfree FFmpeg configuration on {platform}")
        for required in ("--enable-libaom", "--enable-libsvtav1", "--enable-libopenh264"):
            if required not in command:
                raise BundleError(f"Required linked FFmpeg dependency {required} is absent on {platform}")
        if "--enable-libx264" in command or "--enable-libx265" in command:
            raise BundleError(f"Unexpected GPL encoder linked into FFmpeg on {platform}")
        needs_mfx = platform in {"windows-x64", "linux-x64"}
        if ("--enable-libmfx" in command) != needs_mfx:
            raise BundleError(f"Intel Media SDK dispatcher mapping does not match {platform} FFmpeg configuration")
        if platform == "linux-arm64" and not all(lib in command for lib in ("-lasound", "-lvchiq_arm", "-lvcos")):
            raise BundleError("Linux arm64 runtime sources do not cover all linked audio/Raspberry Pi libraries")
        if platform == "linux-x64" and not all(lib in command for lib in ("--enable-libdrm", "-lva-drm", "-lva")):
            raise BundleError("Linux x64 runtime sources do not cover the inspected VA/DRM libraries")
        if platform == "windows-x64" and not all(lib in command for lib in ("-static-libgcc", "-static-libstdc++", "-lpthread")):
            raise BundleError("Windows runtime sources do not cover the inspected GCC/pthread libraries")
        joined[platform] = command
    return joined


def ensure_binary_artifacts(
    artifacts: list[dict[str, Any]],
    cache_dir: Path,
    fetch_missing: bool,
) -> list[tuple[Path, dict[str, Any]]]:
    binary_dir = cache_dir / "binary-artifacts"
    binary_dir.mkdir(parents=True, exist_ok=True)
    verified: list[tuple[Path, dict[str, Any]]] = []
    for artifact in artifacts:
        path = binary_dir / artifact["archive"]
        if not path.exists():
            if not fetch_missing:
                raise BundleError(f"Pinned Maven binary artifact is missing from cache: {path}")
            temporary = path.with_suffix(path.suffix + ".download")
            try:
                subprocess.run(
                    ["curl", "--fail", "--location", "--silent", "--show-error", "--retry", "2", artifact["url"], "-o", str(temporary)],
                    check=True,
                )
            except (OSError, subprocess.CalledProcessError) as error:
                temporary.unlink(missing_ok=True)
                raise BundleError(f"Could not download pinned Maven artifact {artifact['id']}: {error}") from error
            actual = sha256_file(temporary)
            if actual != artifact["sha256"]:
                temporary.unlink(missing_ok=True)
                raise BundleError(f"Downloaded binary checksum mismatch for {artifact['id']}: expected {artifact['sha256']}, got {actual}")
            temporary.replace(path)
        actual = sha256_file(path)
        if actual != artifact["sha256"]:
            raise BundleError(f"Cached binary checksum mismatch for {artifact['id']}: expected {artifact['sha256']}, got {actual}")
        try:
            with zipfile.ZipFile(path) as jar:
                bad_member = jar.testzip()
        except (OSError, zipfile.BadZipFile) as error:
            raise BundleError(f"Pinned binary artifact is not a valid JAR: {artifact['id']}: {error}") from error
        if bad_member:
            raise BundleError(f"Corrupt JAR entry in binary artifact {artifact['id']}: {bad_member}")
        verified.append((path, artifact))
    return verified


def validate_classifier_evidence(
    binary_files: list[tuple[Path, dict[str, Any]]],
    repo_root: Path = REPO_ROOT,
) -> dict[str, Any]:
    path = repo_root / "licenses/third-party/recipe-inputs/classifier-binary-evidence.json"
    evidence = read_json(path)
    artifact_by_platform = {
        artifact.get("platform"): (file_path, artifact)
        for file_path, artifact in binary_files
        if artifact.get("component") == "org.bytedeco:ffmpeg" and artifact.get("platform")
    }
    evidence_platforms = {
        "macos-arm64": "macosx-arm64",
        "windows-x64": "windows-x86_64",
        "linux-x64": "linux-x86_64",
        "linux-arm64": "linux-arm64",
    }
    if set(artifact_by_platform) != PLATFORM_KEYS or set(evidence) != set(evidence_platforms.values()):
        raise BundleError("Classifier binary evidence does not enumerate all four FFmpeg targets")
    configurations = verify_classifier_configurations(repo_root)
    config_members = {
        "macos-arm64": "libavutil.60.dylib",
        "windows-x64": "avutil-60.dll",
        "linux-x64": "libavutil.so.60",
        "linux-arm64": "libavutil.so.60",
    }
    for platform, (jar_path, artifact) in artifact_by_platform.items():
        entry = evidence[evidence_platforms[platform]]
        if entry.get("sha256") != artifact.get("sha256"):
            raise BundleError(f"Classifier evidence checksum disagrees with the source lock for {platform}")
        if f"org.bytedeco:ffmpeg:{SUPPORTED_RUNTIME_VERSIONS['ffmpeg']}:{artifact.get('classifier')}" != entry.get("coordinate"):
            raise BundleError(f"Classifier evidence coordinate disagrees with the source lock for {platform}")
        expected_entries = entry.get("extraRuntimeEntries")
        if not isinstance(expected_entries, dict):
            raise BundleError(f"Runtime entry evidence is malformed for {platform}")
        with zipfile.ZipFile(jar_path) as jar:
            names = set(jar.namelist())
            configure = configurations[platform].encode("utf-8")
            matching_avutil = [name for name in names if name.endswith(config_members[platform])]
            if len(matching_avutil) != 1 or configure not in jar.read(matching_avutil[0]):
                raise BundleError(f"Inspected FFmpeg configuration does not match the packaged avutil binary for {platform}")
            for name, record in expected_entries.items():
                if name not in names:
                    raise BundleError(f"Expected extra runtime library is missing from {platform}: {name}")
                data = jar.read(name)
                actual = hashlib.sha256(data).hexdigest()
                if actual != record.get("sha256") or len(data) != record.get("size"):
                    raise BundleError(f"Runtime member evidence mismatch for {platform}: {name}")
    return evidence


def verify_runtime_artifacts(
    *,
    platform: str,
    ffmpeg_jar: Path,
    zstd_jar: Path,
    lock: dict[str, Any],
) -> None:
    if platform not in PLATFORM_KEYS:
        raise BundleError(f"Unsupported native platform: {platform}")
    expected = {item["id"]: item for item in lock["binaryArtifacts"]}
    ids = {
        "macos-arm64": ("ffmpeg-macosx-arm64", "zstd-jni-macos-arm64"),
        "windows-x64": ("ffmpeg-windows-x86_64", "zstd-jni-windows-x64"),
        "linux-x64": ("ffmpeg-linux-x86_64", "zstd-jni-linux-x64"),
        "linux-arm64": ("ffmpeg-linux-arm64", "zstd-jni-linux-arm64"),
    }[platform]
    for path, artifact_id in ((ffmpeg_jar, ids[0]), (zstd_jar, ids[1])):
        if not path.is_file():
            raise BundleError(f"Runtime classifier JAR is missing: {path}")
        actual = sha256_file(path)
        wanted = expected[artifact_id]["sha256"]
        if actual != wanted:
            raise BundleError(f"Resolved {artifact_id} checksum mismatch: expected {wanted}, got {actual}")


def _linkedit_vmsize_offset(data: bytes) -> int:
    """Return the bounded __LINKEDIT vmsize field offset in a little-endian Mach-O64 image."""
    if len(data) < 32 or data[:4] != b"\xcf\xfa\xed\xfe":
        raise BundleError("macOS classifier entry is not a little-endian Mach-O64 image")
    ncmds = int.from_bytes(data[16:20], "little")
    sizeofcmds = int.from_bytes(data[20:24], "little")
    commands_start = 32
    commands_end = commands_start + sizeofcmds
    if commands_end > len(data):
        raise BundleError("Mach-O load-command table exceeds the file bounds")
    cursor = commands_start
    offsets: list[int] = []
    for _ in range(ncmds):
        if cursor + 8 > commands_end:
            raise BundleError("Mach-O load command header exceeds the command table")
        command = int.from_bytes(data[cursor:cursor + 4], "little")
        command_size = int.from_bytes(data[cursor + 4:cursor + 8], "little")
        if command_size < 8 or cursor + command_size > commands_end:
            raise BundleError("Mach-O load command has invalid bounds")
        if command == 0x19:  # LC_SEGMENT_64
            if command_size < 72:
                raise BundleError("Mach-O 64-bit segment command is truncated")
            vmaddr = int.from_bytes(data[cursor + 24:cursor + 32], "little")
            vmsize = int.from_bytes(data[cursor + 32:cursor + 40], "little")
            fileoff = int.from_bytes(data[cursor + 40:cursor + 48], "little")
            filesize = int.from_bytes(data[cursor + 48:cursor + 56], "little")
            nsects = int.from_bytes(data[cursor + 64:cursor + 68], "little")
            if 72 + nsects * 80 > command_size:
                raise BundleError("Mach-O segment section table exceeds its load command")
            if fileoff > len(data) or filesize > len(data) - fileoff:
                raise BundleError("Mach-O segment file range exceeds image bounds")
            if vmsize > 0xFFFFFFFFFFFFFFFF - vmaddr:
                raise BundleError("Mach-O segment virtual-memory range overflows")
            segment_name = data[cursor + 8:cursor + 24].split(b"\0", 1)[0]
            if segment_name == b"__LINKEDIT":
                offsets.append(cursor + 32)  # vmaddr (8 bytes), followed by vmsize (8 bytes)
        cursor += command_size
    if cursor != commands_end:
        raise BundleError("Mach-O load-command sizes do not match sizeofcmds")
    if len(offsets) != 1:
        raise BundleError("Mach-O image must have exactly one __LINKEDIT segment")
    return offsets[0]


def normalize_macos_linkedit_vmsize(reference: bytes, candidate: bytes) -> bytes:
    """Normalize only the signing-induced __LINKEDIT vmsize difference, with strict bounds."""
    if len(reference) != len(candidate):
        raise BundleError("Mach-O entry length changed after code-signature removal")
    reference_offset = _linkedit_vmsize_offset(reference)
    candidate_offset = _linkedit_vmsize_offset(candidate)
    if reference_offset != candidate_offset:
        raise BundleError("Mach-O __LINKEDIT load-command position changed")
    normalized = bytearray(candidate)
    normalized[candidate_offset:candidate_offset + 8] = reference[reference_offset:reference_offset + 8]
    if bytes(normalized) != reference:
        raise BundleError("macOS Mach-O differs outside __LINKEDIT vmsize")
    return bytes(normalized)


def _macho_has_code_signature(data: bytes) -> bool:
    if len(data) < 32 or data[:4] != b"\xcf\xfa\xed\xfe":
        raise BundleError("macOS classifier entry is not a little-endian Mach-O64 image")
    ncmds = int.from_bytes(data[16:20], "little")
    sizeofcmds = int.from_bytes(data[20:24], "little")
    commands_end = 32 + sizeofcmds
    if commands_end > len(data):
        raise BundleError("Mach-O load-command table exceeds the file bounds")
    cursor = 32
    has_signature = False
    for _ in range(ncmds):
        if cursor + 8 > commands_end:
            raise BundleError("Mach-O load command header exceeds the command table")
        command = int.from_bytes(data[cursor:cursor + 4], "little")
        command_size = int.from_bytes(data[cursor + 4:cursor + 8], "little")
        if command_size < 8 or cursor + command_size > commands_end:
            raise BundleError("Mach-O load command has invalid bounds")
        has_signature |= command == 0x1D  # LC_CODE_SIGNATURE
        cursor += command_size
    if cursor != commands_end:
        raise BundleError("Mach-O load-command sizes do not match sizeofcmds")
    return has_signature


def _remove_macos_code_signature(data: bytes, name: str) -> bytes:
    """Use the platform signer to strip a copy's ad-hoc signature before strict comparison."""
    if not _macho_has_code_signature(data):
        return data
    with tempfile.TemporaryDirectory(prefix="indagium-media-signature-") as temporary:
        target = Path(temporary) / Path(name).name
        target.write_bytes(data)
        try:
            subprocess.run(["codesign", "--remove-signature", str(target)], check=True, capture_output=True, text=True)
        except (OSError, subprocess.CalledProcessError) as error:
            details = getattr(error, "stderr", "") or str(error)
            raise BundleError(f"Could not remove the ad-hoc code signature from {name}: {details.strip()}") from error
        return target.read_bytes()


def _validate_macos_classifier_jars(packaged_jar: Path, original_jar: Path) -> None:
    if not original_jar.is_file():
        raise BundleError(f"Original resolved macOS classifier JAR is missing: {original_jar}")
    try:
        with zipfile.ZipFile(packaged_jar) as packaged, zipfile.ZipFile(original_jar) as original:
            packaged_names = packaged.namelist()
            original_names = original.namelist()
            if len(packaged_names) != len(set(packaged_names)) or len(original_names) != len(set(original_names)):
                raise BundleError("Classifier JAR contains duplicate ZIP entry names")
            if set(packaged_names) != set(original_names):
                missing = sorted(set(original_names) - set(packaged_names))
                extra = sorted(set(packaged_names) - set(original_names))
                raise BundleError(f"Packaged macOS classifier entry set changed: missing={missing}, extra={extra}")
            for name in original_names:
                original_data = original.read(name)
                packaged_data = packaged.read(name)
                if packaged_data == original_data:
                    continue
                if not name.endswith(".dylib"):
                    raise BundleError(f"Packaged macOS classifier entry changed unexpectedly: {name}")
                unsigned_original = _remove_macos_code_signature(original_data, name)
                unsigned_packaged = _remove_macos_code_signature(packaged_data, name)
                normalize_macos_linkedit_vmsize(unsigned_original, unsigned_packaged)
    except zipfile.BadZipFile as error:
        raise BundleError(f"Invalid macOS classifier JAR: {error}") from error


def validate_packaged_resources(
    package_root: Path, platform: str, inventory: dict[str, Any], lock: dict[str, Any],
    original_ffmpeg_jar: Path | None = None, original_zstd_jar: Path | None = None,
) -> None:
    if platform not in PLATFORM_KEYS:
        raise BundleError(f"Unsupported native platform: {platform}")
    if not package_root.is_dir():
        raise BundleError(f"Packaged application directory does not exist: {package_root}")
    jars = sorted(package_root.rglob("*.jar"))
    if not jars:
        raise BundleError(f"No application JARs found under {package_root}")
    expected_artifacts = {item["id"]: item for item in lock["binaryArtifacts"]}
    ffmpeg_id, zstd_id = {
        "macos-arm64": ("ffmpeg-macosx-arm64", "zstd-jni-macos-arm64"),
        "windows-x64": ("ffmpeg-windows-x86_64", "zstd-jni-windows-x64"),
        "linux-x64": ("ffmpeg-linux-x86_64", "zstd-jni-linux-x64"),
        "linux-arm64": ("ffmpeg-linux-arm64", "zstd-jni-linux-arm64"),
    }[platform]
    classifier_jars = {
        ffmpeg_id: expected_artifacts[ffmpeg_id]["archive"].removesuffix(".jar"),
        zstd_id: expected_artifacts[zstd_id]["archive"].removesuffix(".jar"),
    }
    found_classifier_jars: dict[str, Path] = {}
    for artifact_id, prefix in classifier_jars.items():
        matches = [path for path in jars if path.name == f"{prefix}.jar" or path.name.startswith(f"{prefix}-")]
        if len(matches) != 1:
            raise BundleError(f"Expected one packaged {artifact_id} JAR, found {len(matches)}")
        found_classifier_jars[artifact_id] = matches[0]

    if platform == "macos-arm64":
        if original_ffmpeg_jar is None or original_zstd_jar is None:
            raise BundleError("macOS package validation requires both original resolved classifier JARs")
        verify_runtime_artifacts(
            platform=platform, ffmpeg_jar=original_ffmpeg_jar, zstd_jar=original_zstd_jar, lock=lock,
        )
        _validate_macos_classifier_jars(found_classifier_jars[ffmpeg_id], original_ffmpeg_jar)
        _validate_macos_classifier_jars(found_classifier_jars[zstd_id], original_zstd_jar)
    else:
        for artifact_id, packaged_jar in found_classifier_jars.items():
            actual = sha256_file(packaged_jar)
            if actual != expected_artifacts[artifact_id]["sha256"]:
                raise BundleError(f"Packaged {artifact_id} checksum mismatch: expected {expected_artifacts[artifact_id]['sha256']}, got {actual}")

    expected_resources = {
        "licenses/third-party/media-components.json": sha256_file(LEGAL_ROOT / "media-components.json"),
        "licenses/third-party/media-source-lock.json": sha256_file(LEGAL_ROOT / "media-source-lock.json"),
        "licenses/third-party/SOURCE-BUNDLE.md": sha256_file(LEGAL_ROOT / "SOURCE-BUNDLE.md"),
        "licenses/THIRD_PARTY_NOTICES.md": sha256_file(REPO_ROOT / "THIRD_PARTY_NOTICES.md"),
        "licenses/NOTICE": sha256_file(REPO_ROOT / "NOTICE"),
    }
    for relative, digest in inventory["licenseTextHashes"].items():
        expected_resources[f"licenses/third-party/{relative}"] = digest
    found: set[str] = set()
    for jar_path in jars:
        try:
            with zipfile.ZipFile(jar_path) as jar:
                names = set(jar.namelist())
                for resource in expected_resources.keys() & names:
                    data = jar.read(resource)
                    actual = hashlib.sha256(data).hexdigest()
                    if actual != expected_resources[resource]:
                        raise BundleError(f"Packaged legal resource checksum mismatch: {resource} in {jar_path.name}")
                    found.add(resource)
        except zipfile.BadZipFile as error:
            raise BundleError(f"Invalid packaged JAR {jar_path}: {error}") from error
    missing = sorted(set(expected_resources) - found)
    if missing:
        raise BundleError(f"Packaged application is missing legal resources: {missing}")


def _add_file(archive: tarfile.TarFile, path: Path, archive_name: str) -> dict[str, Any]:
    info = tarfile.TarInfo(archive_name)
    info.size = path.stat().st_size
    info.mode = 0o644
    info.uid = 0
    info.gid = 0
    info.uname = ""
    info.gname = ""
    info.mtime = 0
    with path.open("rb") as stream:
        archive.addfile(info, stream)
    return {"path": archive_name, "size": info.size, "sha256": sha256_file(path)}


def create_bundle(
    *,
    app_version: str,
    runtime_versions: dict[str, str],
    cache_dir: Path,
    output_dir: Path,
    fetch_missing: bool,
    repo_root: Path = REPO_ROOT,
) -> tuple[Path, Path, Path]:
    legal_root = repo_root / "licenses" / "third-party"
    lock = read_json(legal_root / "media-source-lock.json")
    inventory = read_json(legal_root / "media-components.json")
    components = validate_inventory(inventory, legal_root)
    archives = validate_lock(lock, app_version, runtime_versions, inventory)
    source_files = ensure_sources(archives, cache_dir, fetch_missing)
    validate_legal_provenance_members(inventory, source_files)
    binary_files = ensure_binary_artifacts(lock["binaryArtifacts"], cache_dir, fetch_missing)
    evidence = validate_classifier_evidence(binary_files, repo_root)
    configurations = verify_classifier_configurations(repo_root)
    build_files = verify_build_inputs(lock, repo_root)

    output_dir.mkdir(parents=True, exist_ok=True)
    archive_path = output_dir / f"Indagium-{app_version}-media-sources.tar.gz"
    checksum_path = Path(str(archive_path) + ".sha256")
    manifest_path = Path(str(archive_path) + ".manifest.json")

    with tempfile.TemporaryDirectory(prefix="indagium-media-sources-") as temp_name:
        staging = Path(temp_name)
        root = staging / f"Indagium-{app_version}-media-sources"
        (root / "sources").mkdir(parents=True)
        (root / "build-inputs").mkdir(parents=True)
        (root / "legal").mkdir(parents=True)

        for source_path, source in source_files:
            shutil.copyfile(source_path, root / "sources" / source["archive"])
        for source_path, relative in build_files:
            shutil.copyfile(source_path, root / "build-inputs" / Path(relative).name)
        shutil.copyfile(legal_root / "media-components.json", root / "legal" / "media-components.json")
        shutil.copyfile(legal_root / "media-source-lock.json", root / "legal" / "media-source-lock.json")
        shutil.copyfile(legal_root / "SOURCE-BUNDLE.md", root / "README.md")
        shutil.copyfile(repo_root / "THIRD_PARTY_NOTICES.md", root / "legal" / "THIRD_PARTY_NOTICES.md")
        shutil.copyfile(repo_root / "NOTICE", root / "legal" / "NOTICE")
        for relative in sorted(inventory["licenseTextHashes"]):
            source_path = legal_root / relative
            destination = root / "legal" / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(source_path, destination)

        files = []
        content_files = sorted(path for path in root.rglob("*") if path.is_file())
        for path in content_files:
            files.append({
                "path": path.relative_to(root).as_posix(),
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            })
        manifest = {
            "schemaVersion": 1,
            "applicationVersion": app_version,
            "bundleType": "native-media-build-sources-and-legal-notices",
            "matchingClaim": "Source inputs are pinned by upstream reference and SHA-256. The archive is not a bit-for-bit reproducibility claim; see README.md.",
            "runtimeVersions": runtime_versions,
            "sourceRecipe": lock["recipe"],
            "componentCount": len(components),
            "sourceArchiveCount": len(source_files),
            "inspectedBinaryArtifacts": [
                {"id": artifact["id"], "coordinate": artifact.get("coordinate"), "classifier": artifact.get("classifier"), "sha256": artifact["sha256"]}
                for _, artifact in binary_files
            ],
            "classifierConfigurations": {
                platform: {"sha256": sha256_file(repo_root / "licenses/third-party/recipe-inputs/ffmpeg-classifier-configurations.json"), "command": command}
                for platform, command in configurations.items()
            },
            "legalTextProvenanceCount": len(inventory["legalTextProvenance"]),
            "files": files,
        }
        (root / "manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")

        with archive_path.open("wb") as output_stream:
            with gzip.GzipFile(filename="", mode="wb", fileobj=output_stream, mtime=0) as gzip_stream:
                with tarfile.open(fileobj=gzip_stream, mode="w") as tar:
                    for path in sorted(root.rglob("*")):
                        if path.is_file():
                            _add_file(tar, path, f"{root.name}/{path.relative_to(root).as_posix()}")

    archive_sha = sha256_file(archive_path)
    checksum_path.write_text(f"{archive_sha}  {archive_path.name}\n", encoding="ascii")
    manifest["archive"] = {"path": archive_path.name, "size": archive_path.stat().st_size, "sha256": archive_sha}
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return archive_path, checksum_path, manifest_path


def validate_only(
    *,
    app_version: str,
    runtime_versions: dict[str, str],
    cache_dir: Path,
    fetch_missing: bool,
    repo_root: Path = REPO_ROOT,
) -> None:
    legal_root = repo_root / "licenses" / "third-party"
    inventory = read_json(legal_root / "media-components.json")
    lock = read_json(legal_root / "media-source-lock.json")
    validate_inventory(inventory, legal_root)
    archives = validate_lock(lock, app_version, runtime_versions, inventory)
    source_files = ensure_sources(archives, cache_dir, fetch_missing)
    validate_legal_provenance_members(inventory, source_files)
    binary_files = ensure_binary_artifacts(lock["binaryArtifacts"], cache_dir, fetch_missing)
    validate_classifier_evidence(binary_files, repo_root)
    verify_classifier_configurations(repo_root)
    verify_build_inputs(lock, repo_root)


def validate_inventory_only(
    *, app_version: str, runtime_versions: dict[str, str], repo_root: Path = REPO_ROOT
) -> tuple[dict[str, Any], dict[str, Any]]:
    legal_root = repo_root / "licenses" / "third-party"
    inventory = read_json(legal_root / "media-components.json")
    lock = read_json(legal_root / "media-source-lock.json")
    validate_inventory(inventory, legal_root)
    validate_lock(lock, app_version, runtime_versions, inventory)
    verify_build_inputs(lock, repo_root)
    verify_classifier_configurations(repo_root)
    return inventory, lock


def validate_runtime_only(
    *, app_version: str, runtime_versions: dict[str, str], platform: str,
    ffmpeg_jar: Path, zstd_jar: Path, repo_root: Path = REPO_ROOT
) -> None:
    _, lock = validate_inventory_only(app_version=app_version, runtime_versions=runtime_versions, repo_root=repo_root)
    verify_runtime_artifacts(platform=platform, ffmpeg_jar=ffmpeg_jar, zstd_jar=zstd_jar, lock=lock)


def validate_package_only(
    *, app_version: str, runtime_versions: dict[str, str], platform: str,
    package_root: Path, original_ffmpeg_jar: Path | None = None, original_zstd_jar: Path | None = None,
    repo_root: Path = REPO_ROOT
) -> None:
    inventory, lock = validate_inventory_only(app_version=app_version, runtime_versions=runtime_versions, repo_root=repo_root)
    validate_packaged_resources(package_root, platform, inventory, lock, original_ffmpeg_jar, original_zstd_jar)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--app-version", required=True)
    parser.add_argument("--ffmpeg-version", required=True)
    parser.add_argument("--javacv-version", required=True)
    parser.add_argument("--javacpp-version", required=True)
    parser.add_argument("--zstd-jni-version", required=True)
    parser.add_argument("--source-cache", type=Path, default=REPO_ROOT / "build/media-source-cache")
    parser.add_argument("--output-dir", type=Path, default=REPO_ROOT / "build")
    parser.add_argument("--fetch-missing", action="store_true")
    parser.add_argument("--validate-only", action="store_true")
    parser.add_argument("--inventory-only", action="store_true")
    parser.add_argument("--validate-runtime-artifacts", action="store_true")
    parser.add_argument("--validate-package", action="store_true")
    parser.add_argument("--platform", choices=sorted(PLATFORM_KEYS))
    parser.add_argument("--ffmpeg-jar", type=Path)
    parser.add_argument("--zstd-jar", type=Path)
    parser.add_argument("--package-root", type=Path)
    args = parser.parse_args(argv)
    runtime_versions = {
        "ffmpeg": args.ffmpeg_version,
        "javacv": args.javacv_version,
        "javacpp": args.javacpp_version,
        "zstdJni": args.zstd_jni_version,
    }
    try:
        modes = sum((args.validate_only, args.inventory_only, args.validate_runtime_artifacts, args.validate_package))
        if modes > 1:
            raise BundleError("Choose only one validation mode")
        if args.inventory_only:
            validate_inventory_only(app_version=args.app_version, runtime_versions=runtime_versions)
            print("Media inventory, source lock, build inputs, and configuration evidence validate offline.")
        elif args.validate_runtime_artifacts:
            if not args.platform or not args.ffmpeg_jar or not args.zstd_jar:
                raise BundleError("Runtime validation requires --platform, --ffmpeg-jar, and --zstd-jar")
            validate_runtime_only(app_version=args.app_version, runtime_versions=runtime_versions, platform=args.platform, ffmpeg_jar=args.ffmpeg_jar, zstd_jar=args.zstd_jar)
            print("Resolved media runtime artifacts match the pinned classifiers.")
        elif args.validate_package:
            if not args.platform or not args.package_root:
                raise BundleError("Packaged-resource validation requires --platform and --package-root")
            validate_package_only(
                app_version=args.app_version, runtime_versions=runtime_versions, platform=args.platform,
                package_root=args.package_root, original_ffmpeg_jar=args.ffmpeg_jar, original_zstd_jar=args.zstd_jar,
            )
            print("Packaged media classifiers and legal resources validate.")
        elif args.validate_only:
            validate_only(
                app_version=args.app_version,
                runtime_versions=runtime_versions,
                cache_dir=args.source_cache,
                fetch_missing=args.fetch_missing,
            )
            print("Media source inputs and legal resources validate.")
        else:
            archive, checksum, manifest = create_bundle(
                app_version=args.app_version,
                runtime_versions=runtime_versions,
                cache_dir=args.source_cache,
                output_dir=args.output_dir,
                fetch_missing=args.fetch_missing,
            )
            print(f"Prepared {archive}\n{checksum}\n{manifest}")
    except BundleError as error:
        print(f"prepare_media_sources: {error}", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
