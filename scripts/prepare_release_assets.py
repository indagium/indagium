#!/usr/bin/env python3
"""Stage a complete new EULA release; verify uploaded assets before publication."""

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]


def digest(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def stage_assets(artifacts: Path, output: Path, version: str) -> dict:
    required = {f"Indagium-{version}-{kind}.tar.gz{suffix}"
                for kind in ("media-sources", "application-sources")
                for suffix in ("", ".sha256", ".manifest.json")}
    packages = [f"Indagium-{version}{suffix}" for suffix in (".dmg", ".msi", "-x86_64.AppImage", "-aarch64.AppImage", "-x86_64.flatpak", "-aarch64.flatpak")]
    packages += [f"indagium_{version}_{arch}.deb" for arch in ("amd64", "arm64")]
    required.update(packages)
    required.update(("EULA.md", "NOTICE", "THIRD_PARTY_NOTICES.md", "dependency-report.txt"))
    found = {}
    for path in artifacts.rglob("*"):
        if path.is_file() and path.name in required:
            if path.name in found and digest(path) != digest(found[path.name]):
                raise ValueError(f"Conflicting copies of release asset: {path.name}")
            found[path.name] = path
    missing = required - set(found)
    if missing:
        raise ValueError(f"Incomplete release assets: {sorted(missing)}")
    for name in ("EULA.md", "NOTICE", "THIRD_PARTY_NOTICES.md"):
        if found[name].read_bytes() != (ROOT / name).read_bytes():
            raise ValueError(f"Release legal document differs from the tagged source: {name}")
    for kind in ("media-sources", "application-sources"):
        name = f"Indagium-{version}-{kind}.tar.gz"
        value = digest(found[name])
        checksum = found[name + ".sha256"].read_text().split()
        manifest = json.loads(found[name + ".manifest.json"].read_text())
        if checksum != [value, name] or manifest.get("applicationVersion") != version or manifest.get("archive", {}).get("sha256") != value:
            raise ValueError(f"Source archive checksum, manifest or version mismatch: {name}")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("Release staging destination must be empty")
    result = {name: {"sha256": digest(path), "size": path.stat().st_size} for name, path in sorted(found.items())}
    for name, path in found.items():
        shutil.copyfile(path, output / name)
    (output / "release-assets.json").write_text(json.dumps(result, indent=2) + "\n")
    return result


def api(path: str) -> dict:
    return json.loads(subprocess.check_output(["gh", "api", path]))


def verify_uploaded(repository: str, release_id: str, staged: Path) -> None:
    release = api(f"repos/{repository}/releases/{release_id}")
    if not release.get("draft"):
        raise ValueError("Refusing to change a published historical release")
    expected = json.loads((staged / "release-assets.json").read_text())
    expected["release-assets.json"] = {"sha256": digest(staged / "release-assets.json"), "size": (staged / "release-assets.json").stat().st_size}
    actual = {a["name"]: a for a in release["assets"]}
    if set(actual) != set(expected):
        raise ValueError("Uploaded release assets do not match the staged set")
    for name, record in expected.items():
        asset = actual[name]
        if asset["size"] != record["size"]:
            raise ValueError(f"Uploaded asset size mismatch: {name}")
        remote_digest = asset.get("digest")
        if remote_digest:
            if remote_digest != "sha256:" + record["sha256"]:
                raise ValueError(f"Uploaded asset checksum mismatch: {name}")
        else:
            with tempfile.TemporaryFile() as stream:
                subprocess.run(["gh", "api", f"repos/{repository}/releases/assets/{asset['id']}", "-H", "Accept: application/octet-stream"], stdout=stream, check=True)
                stream.seek(0)
                h = hashlib.file_digest(stream, "sha256").hexdigest()
                if h != record["sha256"]:
                    raise ValueError(f"Uploaded asset checksum mismatch: {name}")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", type=Path)
    parser.add_argument("--output", type=Path, default=Path("release-assets"))
    parser.add_argument("--tag", required=True)
    parser.add_argument("--repository")
    parser.add_argument("--release-id")
    parser.add_argument("--check-existing", action="store_true")
    args = parser.parse_args()
    version = args.tag.removeprefix("v")
    if args.check_existing:
        result = subprocess.run(["gh", "api", f"repos/{args.repository}/releases/tags/{args.tag}"], capture_output=True, text=True)
        if result.returncode:
            if '"status":"404"' not in result.stdout.replace(" ", ""):
                raise ValueError("Cannot determine whether the release already exists")
        elif not json.loads(result.stdout).get("draft"):
            raise ValueError("Refusing to modify a published release; use a new version/tag")
    elif args.release_id:
        verify_uploaded(args.repository, args.release_id, args.output)
    else:
        properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines() if "=" in line and not line.startswith("#"))
        if version != properties["app.version"]:
            raise ValueError("Release tag and application version differ")
        stage_assets(args.artifacts, args.output, version)


if __name__ == "__main__":
    main()
