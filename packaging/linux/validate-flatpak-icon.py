#!/usr/bin/env python3
"""Fail early when the Flatpak hicolor icon does not match its declared size."""

from pathlib import Path
import struct
import sys


ROOT = Path(__file__).resolve().parents[2]
ICON_RELATIVE_PATH = Path("packaging/linux/com.indagium.desktop.png")
MANIFEST_RELATIVE_PATH = Path("packaging/linux/com.indagium.desktop.yml")
INSTALL_COMMAND = (
    "install -Dm644 packaging/linux/com.indagium.desktop.png "
    "/app/share/icons/hicolor/512x512/apps/com.indagium.desktop.png"
)
PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"
EXPECTED_DIMENSIONS = (512, 512)


def fail(message: str) -> None:
    print(f"Flatpak icon validation failed: {message}", file=sys.stderr)
    raise SystemExit(1)


def main() -> None:
    icon_path = ROOT / ICON_RELATIVE_PATH
    manifest_path = ROOT / MANIFEST_RELATIVE_PATH

    if not icon_path.is_file():
        fail(f"missing icon asset: {ICON_RELATIVE_PATH}")
    if INSTALL_COMMAND not in manifest_path.read_text(encoding="utf-8"):
        fail("manifest does not install the 512px packaging icon into hicolor/512x512")

    image = icon_path.read_bytes()
    if len(image) < 24 or image[:8] != PNG_SIGNATURE or image[12:16] != b"IHDR":
        fail(f"{ICON_RELATIVE_PATH} is not a valid PNG with an IHDR header")

    dimensions = struct.unpack(">II", image[16:24])
    if dimensions != EXPECTED_DIMENSIONS:
        fail(
            f"{ICON_RELATIVE_PATH} is {dimensions[0]}x{dimensions[1]}, "
            "but hicolor/512x512 requires 512x512"
        )

    print(f"Validated Flatpak icon: {ICON_RELATIVE_PATH} is 512x512 PNG")


if __name__ == "__main__":
    main()
