#!/usr/bin/env python3
"""Canonical agreement identity, offline package checks, and EULA website generation."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EULA_VERSION = "1.0.0"
SOURCE_VERSION = "2026-07-19"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def agreement(kind: str, root: Path = ROOT) -> tuple[str, dict]:
    if kind not in {"polyform", "eula"}:
        raise ValueError(f"Unknown distribution license: {kind}")
    document = "EULA.md" if kind == "eula" else "LICENSE"
    body = (root / document).read_text(encoding="utf-8").rstrip()
    version = EULA_VERSION if kind == "eula" else SOURCE_VERSION
    if kind == "eula" and f"\nVersion: {version}\n" not in body:
        raise ValueError("EULA version does not match its canonical document")
    if kind == "polyform":
        body += "\n\n---\n\n" + (root / "NOTICE").read_text(encoding="utf-8").rstrip()
    digest = sha256(body.encode())
    identity = f"{kind}-{version}:{digest}"
    title = "Indagium End User License Agreement" if kind == "eula" else "Indagium Source License Agreement"
    text = f"# {title}\n\nTerms version: {identity}\n\n---\n\n{body}\n"
    return text, {
        "schemaVersion": 1, "kind": kind, "document": document,
        "documentVersion": version, "documentSha256": digest,
        "identity": identity, "agreementSha256": sha256(text.encode()),
    }


def source_acquisition(root: Path = ROOT) -> dict:
    version = next(line.split("=", 1)[1] for line in (root / "gradle.properties").read_text().splitlines() if line.startswith("app.version="))
    base = "https://github.com/indagium/indagium/releases"
    archives = []
    for kind in ("media", "application"):
        name = f"Indagium-{version}-{kind}-sources.tar.gz"
        url = f"{base}/download/v{version}/{name}"
        archives.append({"name": name, "url": url, "checksumUrl": url + ".sha256", "manifestUrl": url + ".manifest.json"})
    return {"schemaVersion": 1, "applicationVersion": version, "releaseUrl": f"{base}/tag/v{version}", "archives": archives}


def validate_package(package: Path, kind: str, root: Path = ROOT) -> dict:
    text, expected = agreement(kind, root)
    required = {
        "licenses/indagium-license-agreement.md": text.encode(),
        "licenses/distribution-license.json": None,
        "licenses/source-acquisition.json": None,
        f"licenses/{expected['document']}": (root / expected["document"]).read_bytes(),
    }
    found = set()
    for jar in package.rglob("*.jar"):
        with zipfile.ZipFile(jar) as archive:
            names = set(archive.namelist())
            for name, data in required.items():
                if name not in names:
                    continue
                actual = archive.read(name)
                expected_json = source_acquisition(root) if name.endswith("source-acquisition.json") else expected
                if (data is not None and actual != data) or (data is None and json.loads(actual) != expected_json):
                    raise ValueError(f"Wrong governing agreement or metadata in {jar.name}: {name}")
                found.add(name)
    if found != set(required):
        raise ValueError(f"Missing governing legal resources: {sorted(set(required) - found)}")
    copies = list(package.rglob("indagium-license-agreement.txt"))
    if not copies or any(p.read_bytes() != text.encode() for p in copies):
        raise ValueError("Missing or incorrect readable installed agreement")
    metadata = list(package.rglob("distribution-license.json"))
    if not metadata or any(json.loads(p.read_bytes()) != expected for p in metadata):
        raise ValueError("Missing or incorrect installed agreement metadata")
    acquisition = list(package.rglob("source-acquisition.json"))
    if not acquisition or any(json.loads(p.read_bytes()) != source_acquisition(root) for p in acquisition):
        raise ValueError("Missing or incorrect version-specific source acquisition links")
    if kind == "eula":
        from prepare_application_sources import validate_runtime
        validate_runtime(package, root)
    return expected


def render_eula(root: Path = ROOT) -> str:
    body = (root / "EULA.md").read_text(encoding="utf-8").strip()
    paragraphs = []
    for block in body.split("\n\n"):
        if block.startswith("# "):
            paragraphs.append("<h1>" + html.escape(block[2:]) + "</h1>")
        elif block.startswith("## "):
            paragraphs.append("<h2>" + html.escape(block[3:]) + "</h2>")
        else:
            escaped = html.escape(block)
            escaped = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", escaped)
            paragraphs.append("<p>" + escaped + "</p>")
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Indagium EULA {EULA_VERSION}</title><link rel="canonical" href="https://indagium.com/eula/{EULA_VERSION}/">
<link rel="stylesheet" href="/styles.css"></head><body>
<header class="site-header"><a class="wordmark" href="/">◆ indagium</a><nav><a href="/licenses/">Third-party licences</a><a href="https://github.com/indagium/indagium/blob/master/LICENSE">Source licence</a></nav></header>
<main id="main" class="section"><article class="eula-document">
""" + "\n".join(paragraphs) + "\n</article></main></body></html>\n"


def site(root: Path, check: bool) -> None:
    expected = render_eula(root)
    for relative in ("site/eula/index.html", f"site/eula/{EULA_VERSION}/index.html"):
        path = root / relative
        if check:
            if not path.is_file() or path.read_text(encoding="utf-8") != expected:
                raise ValueError(f"EULA website is stale: {relative}")
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(expected, encoding="utf-8", newline="\n")


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--kind", choices=["polyform", "eula"], default="polyform")
    parser.add_argument("--package-root", type=Path)
    parser.add_argument("--render-site", action="store_true")
    parser.add_argument("--check-site", action="store_true")
    args = parser.parse_args()
    if args.render_site or args.check_site:
        site(args.root, args.check_site)
    elif args.package_root:
        validate_package(args.package_root, args.kind, args.root)
        print(f"Packaged {args.kind} governing agreement and readable copies validate.")
    else:
        print(json.dumps(agreement(args.kind, args.root)[1], indent=2))


if __name__ == "__main__":
    main()
