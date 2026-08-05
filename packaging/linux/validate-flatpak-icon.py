#!/usr/bin/env python3
"""Validate the complete staged Flatpak source tree before invoking flatpak-builder."""

import argparse
from pathlib import Path
import re
import shlex
import struct
import sys


ICON_RELATIVE_PATH = Path("packaging/linux/com.indagium.desktop.png")
MANIFEST_RELATIVE_PATH = Path("packaging/linux/com.indagium.desktop.yml")
HICOLOR_ICON_PATH = "/app/share/icons/hicolor/512x512/apps/com.indagium.desktop.png"
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
EXPECTED_DIMENSIONS = (512, 512)


def fail(message: str) -> None:
    print(f"Flatpak icon validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def manifest_build_commands(manifest_path: Path) -> list[str]:
    """Read simple YAML build-command scalars without a non-standard YAML dependency."""
    commands: list[str] = []
    in_build_commands = False
    build_commands_indent = 0

    for line in manifest_path.read_text(encoding="utf-8").splitlines():
        if not in_build_commands:
            match = re.match(r"^(\s*)build-commands:\s*$", line)
            if match:
                in_build_commands = True
                build_commands_indent = len(match.group(1))
            continue

        if line.strip() and len(line) - len(line.lstrip()) <= build_commands_indent:
            break
        match = re.match(r"^\s*-\s+(.*)$", line)
        if match:
            commands.append(match.group(1))

    if not commands:
        fail(f"{MANIFEST_RELATIVE_PATH} has no build commands to validate")
    return commands


def source_operands(command: str) -> list[str]:
    """Return staging-relative input paths for the shell commands used by the manifest."""
    try:
        arguments = shlex.split(command)
    except ValueError as error:
        fail(f"cannot parse manifest build command {command!r}: {error}")

    if not arguments:
        return []

    executable, *arguments = arguments
    non_options = [argument for argument in arguments if not argument.startswith("-")]
    if executable == "install" and len(non_options) >= 2:
        return [non_options[-2]]
    if executable == "cp" and len(non_options) >= 2:
        return non_options[:-1]
    return []


def staged_path(staging_dir: Path, operand: str) -> Path:
    # A trailing '/.' denotes the contents of a directory, not a separate path.
    normalized = operand.removesuffix("/.")
    return staging_dir / normalized


def validate_manifest_inputs(staging_dir: Path, manifest_path: Path) -> None:
    missing: list[str] = []
    for command in manifest_build_commands(manifest_path):
        for operand in source_operands(command):
            if operand.startswith("/"):
                continue
            path = staged_path(staging_dir, operand)
            if not path.exists():
                missing.append(operand)

    if missing:
        listed_paths = ", ".join(dict.fromkeys(missing))
        fail(f"manifest-referenced source is missing from staging: {listed_paths}")


def validate_icon_install(manifest_path: Path) -> None:
    """Ensure the validated icon is the one the manifest installs into hicolor."""
    for command in manifest_build_commands(manifest_path):
        arguments = shlex.split(command)
        if not arguments or arguments[0] != "install":
            continue
        non_options = [argument for argument in arguments[1:] if not argument.startswith("-")]
        if len(non_options) >= 2 and non_options[-2] == str(ICON_RELATIVE_PATH):
            if non_options[-1] == HICOLOR_ICON_PATH:
                return
            fail(
                f"manifest installs {ICON_RELATIVE_PATH} outside the expected hicolor path: "
                f"{non_options[-1]}"
            )
    fail(f"manifest does not install {ICON_RELATIVE_PATH} into hicolor/512x512")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Validate a staged Flatpak recipe and its manifest-referenced input files."
    )
    parser.add_argument(
        "--staging",
        type=Path,
        required=True,
        help="assembled Flatpak source-tree root passed to flatpak-builder",
    )
    args = parser.parse_args()

    staging_dir = args.staging.resolve()
    manifest_path = staging_dir / MANIFEST_RELATIVE_PATH
    icon_path = staging_dir / ICON_RELATIVE_PATH

    if not staging_dir.is_dir():
        fail(f"staging directory does not exist: {staging_dir}")
    if not manifest_path.is_file():
        fail(f"missing staged manifest: {MANIFEST_RELATIVE_PATH}")
    if not icon_path.is_file():
        fail(f"manifest-referenced icon is missing from staging: {ICON_RELATIVE_PATH}")

    validate_manifest_inputs(staging_dir, manifest_path)
    validate_icon_install(manifest_path)

    image = icon_path.read_bytes()
    if len(image) < 24 or image[:8] != PNG_SIGNATURE or image[12:16] != b"IHDR":
        fail(f"{ICON_RELATIVE_PATH} is not a valid PNG with an IHDR header")

    dimensions = struct.unpack(">II", image[16:24])
    if dimensions != EXPECTED_DIMENSIONS:
        fail(
            f"{ICON_RELATIVE_PATH} is {dimensions[0]}x{dimensions[1]}, "
            "but hicolor/512x512 requires 512x512"
        )

    print(
        "Validated Flatpak staging: all manifest-referenced inputs exist; "
        f"{ICON_RELATIVE_PATH} is 512x512 PNG"
    )


if __name__ == "__main__":
    main()
