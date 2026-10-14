#!/usr/bin/env bash
# Creates an AppImage from Compose Desktop's createDistributable output. The appimagetool
# binaries are pinned to upstream release 1.9.1 by GitHub asset ID and SHA-256 before execution.
set -euo pipefail

readonly APP_ID='com.indagium.Indagium'
readonly APPIMAGETOOL_X86_64_ASSET_ID='324406736'
readonly APPIMAGETOOL_X86_64_SHA256='ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0'
readonly APPIMAGETOOL_AARCH64_ASSET_ID='324406707'
readonly APPIMAGETOOL_AARCH64_SHA256='f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158'

usage() {
    echo "Usage: $0 --input COMPOSE_APP_DIR --output OUTPUT.AppImage --version VERSION --arch x86_64|aarch64" >&2
    exit 2
}

input=''
output=''
version=''
arch=''
while [[ $# -gt 0 ]]; do
    case "$1" in
        --input) input=${2:-}; shift 2 ;;
        --output) output=${2:-}; shift 2 ;;
        --version) version=${2:-}; shift 2 ;;
        --arch) arch=${2:-}; shift 2 ;;
        *) usage ;;
    esac
done

[[ -d "$input" && -x "$input/bin/Indagium" && -n "$output" && -n "$version" ]] || usage
[[ "$arch" == 'x86_64' || "$arch" == 'aarch64' ]] || {
    echo "Unsupported AppImage architecture: $arch" >&2
    exit 2
}

readonly script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
readonly project_dir=$(cd -- "$script_dir/.." && pwd)
mkdir -p "$(dirname -- "$output")"
readonly output_dir=$(cd -- "$(dirname -- "$output")" && pwd)
readonly desktop_file="$project_dir/packaging/linux/$APP_ID.desktop"
readonly mimeinfo_file="$project_dir/packaging/linux/indagium-mimeinfo.xml"
readonly icon_file="$project_dir/icons/indagium.png"
[[ -f "$desktop_file" && -f "$mimeinfo_file" && -f "$icon_file" ]] || {
    echo "AppImage metadata is missing from packaging/linux or icons/." >&2
    exit 1
}

case "$arch" in
    x86_64)
        asset_id=$APPIMAGETOOL_X86_64_ASSET_ID
        checksum=$APPIMAGETOOL_X86_64_SHA256
        tool_name='appimagetool-x86_64.AppImage'
        ;;
    aarch64)
        asset_id=$APPIMAGETOOL_AARCH64_ASSET_ID
        checksum=$APPIMAGETOOL_AARCH64_SHA256
        tool_name='appimagetool-aarch64.AppImage'
        ;;
esac

tool_cache="$project_dir/build/tools/appimagetool/$asset_id"
tool_path=${INDAGIUM_APPIMAGETOOL_PATH:-"$tool_cache/$tool_name"}
if [[ -z ${INDAGIUM_APPIMAGETOOL_PATH:-} && ! -f "$tool_path" ]]; then
    mkdir -p "$tool_cache"
    curl --fail --location --retry 3 --retry-delay 2 \
        -H 'Accept: application/octet-stream' \
        "https://api.github.com/repos/AppImage/appimagetool/releases/assets/$asset_id" \
        --output "$tool_path"
fi
[[ -f "$tool_path" ]] || {
    echo "appimagetool was not found at $tool_path" >&2
    exit 1
}
if [[ -z ${INDAGIUM_APPIMAGETOOL_PATH:-} ]]; then
    if command -v sha256sum >/dev/null; then
        printf '%s  %s\n' "$checksum" "$tool_path" | sha256sum --check --status -
    else
        printf '%s  %s\n' "$checksum" "$tool_path" | shasum -a 256 --check --status -
    fi
fi
chmod +x "$tool_path"

staging=$(mktemp -d "$output_dir/.indagium-appimage.XXXXXX")
trap 'rm -rf "$staging"' EXIT
app_dir="$staging/AppDir"
mkdir -p "$app_dir/usr"
cp -a "$input/." "$app_dir/usr/"
install -Dm644 "$desktop_file" "$app_dir/usr/share/applications/$APP_ID.desktop"
install -Dm644 "$mimeinfo_file" "$app_dir/usr/share/mime/packages/$APP_ID-mimeinfo.xml"
install -Dm644 "$icon_file" "$app_dir/usr/share/icons/hicolor/1024x1024/apps/$APP_ID.png"
cp "$desktop_file" "$app_dir/$APP_ID.desktop"
cp "$icon_file" "$app_dir/$APP_ID.png"
cat > "$app_dir/AppRun" <<'EOF'
#!/bin/sh
set -eu
exec "$APPDIR/usr/bin/Indagium" "$@"
EOF
chmod +x "$app_dir/AppRun"

rm -f "$output"
ARCH="$arch" VERSION="$version" APPIMAGE_EXTRACT_AND_RUN=1 "$tool_path" "$app_dir" "$output"
