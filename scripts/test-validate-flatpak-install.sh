#!/usr/bin/env bash
# Runs on any host: a fake Flatpak CLI verifies the supported invocation and a
# temporary deployment exercises the installed-file checks without Flatpak.
set -euo pipefail

readonly project_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
temp_dir=$(mktemp -d)
trap 'rm -rf "$temp_dir"' EXIT

deployment_dir="$temp_dir/deployment"
mkdir -p "$deployment_dir/files/share/mime/packages"
mkdir -p "$deployment_dir/files/share/icons/hicolor/512x512/apps"
cp "$project_dir/packaging/linux/indagium-mimeinfo.xml" \
    "$deployment_dir/files/share/mime/packages/com.indagium.Indagium-mimeinfo.xml"
cp "$project_dir/packaging/linux/com.indagium.Indagium.png" \
    "$deployment_dir/files/share/icons/hicolor/512x512/apps/com.indagium.Indagium.png"

mkdir -p "$temp_dir/bin"
printf '%s\n' \
    '#!/usr/bin/env bash' \
    'set -euo pipefail' \
    'if [[ "$*" == "info --user --show-location com.indagium.Indagium" ]]; then' \
    '  printf "%s\\n" "$FAKE_FLATPAK_LOCATION"' \
    '  exit 0' \
    'fi' \
    'echo "unexpected Flatpak invocation: $*" >&2' \
    'exit 64' > "$temp_dir/bin/flatpak"
chmod +x "$temp_dir/bin/flatpak"

PATH="$temp_dir/bin:$PATH" FAKE_FLATPAK_LOCATION="$deployment_dir" \
    bash "$project_dir/scripts/validate-flatpak-install.sh"
