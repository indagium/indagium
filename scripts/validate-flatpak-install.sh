#!/usr/bin/env bash
# Validates files exposed by a freshly installed Flatpak deployment.
#
# `flatpak info --files` is not a Flatpak CLI option on the Ubuntu runners.  In
# contrast, `--show-location` is part of the long-standing flatpak-info API and
# returns the active deployment directory, whose `files` child is the installed
# application tree.
set -euo pipefail

readonly app_id='com.indagium.desktop'

usage() {
    echo "Usage: $0 [APPLICATION_ID]" >&2
    exit 2
}

[[ $# -le 1 ]] || usage
application_id=${1:-$app_id}
[[ "$application_id" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*$ ]] || usage

command -v flatpak >/dev/null || {
    echo 'flatpak is required to validate an installed Flatpak deployment.' >&2
    exit 1
}

deployment_dir=$(flatpak info --user --show-location "$application_id")
[[ -n "$deployment_dir" && -d "$deployment_dir" ]] || {
    echo "Flatpak deployment location was not found for $application_id." >&2
    exit 1
}

mimeinfo_file="$deployment_dir/files/share/mime/packages/${application_id}-mimeinfo.xml"
icon_file="$deployment_dir/files/share/icons/hicolor/512x512/apps/${application_id}.png"
[[ -f "$mimeinfo_file" ]] || {
    echo "Installed Flatpak is missing MIME metadata: $mimeinfo_file" >&2
    exit 1
}
[[ -f "$icon_file" ]] || {
    echo "Installed Flatpak is missing its 512x512 icon: $icon_file" >&2
    exit 1
}

# Verify the icon's IHDR directly so this stays dependency-free and confirms the
# installed image, rather than trusting only its hicolor directory name.
python3 - "$icon_file" <<'PY'
import struct
import sys

icon_path = sys.argv[1]
with open(icon_path, "rb") as icon:
    header = icon.read(24)

if header[:8] != b"\x89PNG\r\n\x1a\n" or header[12:16] != b"IHDR":
    raise SystemExit(f"{icon_path} is not a PNG with an IHDR header")

width, height = struct.unpack(">II", header[16:24])
if (width, height) != (512, 512):
    raise SystemExit(f"{icon_path} must be 512x512, got {width}x{height}")
PY
