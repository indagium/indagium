#!/usr/bin/env bash
# Confirms that a packaged application archive contains the platform JNI presenter resource.
set -euo pipefail

if [[ $# -ne 2 || ! -d "$1" ]]; then
    echo "Usage: $0 APP_ROOT native/{windows,linux}/indagium_mirror.{dll,so}" >&2
    exit 2
fi

app_root=$1
resource=$2
command -v jar >/dev/null || {
    echo 'The JDK jar tool is required to inspect the packaged application.' >&2
    exit 1
}

while IFS= read -r -d '' archive; do
    # Read the complete jar listing: grep -q can close a pipe early and make `jar` report SIGPIPE
    # under pipefail even though the requested entry was present.
    if jar tf "$archive" | grep -Fx "$resource" >/dev/null; then
        echo "Verified $resource in $archive"
        exit 0
    fi
done < <(find "$app_root" -type f -name '*.jar' -print0)

echo "Packaged application under $app_root does not contain $resource" >&2
exit 1
