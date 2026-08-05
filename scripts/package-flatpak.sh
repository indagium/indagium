#!/usr/bin/env bash
# Produces a direct-install Flatpak bundle from Compose Desktop's createDistributable output.
set -euo pipefail

readonly APP_ID='com.indagium.desktop'
readonly RUNTIME='org.freedesktop.Platform//24.08'
readonly SDK='org.freedesktop.Sdk//24.08'
readonly FLATHUB_REPO='https://dl.flathub.org/repo/flathub.flatpakrepo'

usage() {
    echo "Usage: $0 --input COMPOSE_APP_DIR --output OUTPUT.flatpak --version VERSION --arch x86_64|aarch64" >&2
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

[[ -d "$input" && -x "$input/bin/Indagium" && -n "$output" && "$version" =~ ^[[:alnum:].+-]+$ ]] || usage
[[ "$arch" == 'x86_64' || "$arch" == 'aarch64' ]] || {
    echo "Unsupported Flatpak architecture: $arch" >&2
    exit 2
}
for command in flatpak flatpak-builder; do
    command -v "$command" >/dev/null || {
        echo "$command is required. On Ubuntu: sudo apt-get install flatpak flatpak-builder" >&2
        exit 1
    }
done

if ! flatpak remote-info --user --arch="$arch" flathub "$RUNTIME" >/dev/null 2>&1; then
    echo "Missing $RUNTIME for $arch. Add Flathub and install the runtime before packaging." >&2
    exit 1
fi
if ! flatpak remote-info --user --arch="$arch" flathub "$SDK" >/dev/null 2>&1; then
    echo "Missing $SDK for $arch. Add Flathub and install the SDK before packaging." >&2
    exit 1
fi

readonly script_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
readonly project_dir=$(cd -- "$script_dir/.." && pwd)
readonly metadata_dir="$project_dir/packaging/linux"
mkdir -p "$(dirname -- "$output")"
readonly output_dir=$(cd -- "$(dirname -- "$output")" && pwd)
[[ -f "$metadata_dir/$APP_ID.yml" && -f "$metadata_dir/$APP_ID.desktop" && -f "$metadata_dir/$APP_ID.metainfo.xml" && -f "$metadata_dir/indagium-mimeinfo.xml" ]] || {
    echo "Flatpak metadata is missing from packaging/linux/." >&2
    exit 1
}

staging=$(mktemp -d "$output_dir/.indagium-flatpak.XXXXXX")
builder_dir=$(mktemp -d "${TMPDIR:-/tmp}/indagium-flatpak-builder.XXXXXX")
repo_dir=$(mktemp -d "${TMPDIR:-/tmp}/indagium-flatpak-repo.XXXXXX")
trap 'rm -rf "$staging" "$builder_dir" "$repo_dir"' EXIT

cp -a "$input" "$staging/app"
mkdir -p "$staging/packaging/linux" "$staging/icons"
cp "$metadata_dir/$APP_ID.yml" "$staging/$APP_ID.yml"
cp "$metadata_dir/$APP_ID.desktop" "$staging/packaging/linux/$APP_ID.desktop"
cp "$metadata_dir/$APP_ID.metainfo.xml" "$staging/packaging/linux/$APP_ID.metainfo.xml"
cp "$metadata_dir/flatpak-launcher.sh" "$staging/packaging/linux/flatpak-launcher.sh"
cp "$metadata_dir/indagium-mimeinfo.xml" "$staging/packaging/linux/indagium-mimeinfo.xml"
cp "$project_dir/icons/indagium.png" "$staging/icons/indagium.png"
sed "s/@VERSION@/$version/g" "$staging/packaging/linux/$APP_ID.metainfo.xml" > "$staging/packaging/linux/$APP_ID.metainfo.xml.tmp"
mv "$staging/packaging/linux/$APP_ID.metainfo.xml.tmp" "$staging/packaging/linux/$APP_ID.metainfo.xml"

flatpak-builder --force-clean --default-branch=stable --arch="$arch" --repo="$repo_dir" "$builder_dir" "$staging/$APP_ID.yml"
rm -f "$output"
flatpak build-bundle --arch="$arch" --runtime-repo="$FLATHUB_REPO" "$repo_dir" "$output" "$APP_ID" stable
