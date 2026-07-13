#!/usr/bin/env bash
# Pushes master + tags to the GitLab mirror (gitlab.com/rarnaut-dev-group/openlog2).
# Not automatic — run this after pushing to origin whenever you want the mirror in sync.
# Requires GITLAB_TOKEN=<personal access token, write_repository scope> in local.properties.
set -euo pipefail
cd "$(dirname "$0")/.."

token=$(grep '^GITLAB_TOKEN=' local.properties | cut -d= -f2)
if [ -z "$token" ]; then
  echo "GITLAB_TOKEN not found in local.properties" >&2
  exit 1
fi

# Feeds the token to git via GIT_ASKPASS instead of embedding it in the remote URL, so it
# never ends up persisted in .git/config (visible via `git remote -v`).
askpass=$(mktemp)
trap 'rm -f "$askpass"' EXIT
cat > "$askpass" <<'EOF'
#!/bin/sh
case "$1" in
  Username*) echo "oauth2" ;;
  Password*) echo "$GITLAB_PUSH_TOKEN" ;;
esac
EOF
chmod +x "$askpass"

GITLAB_PUSH_TOKEN="$token" GIT_ASKPASS="$askpass" git push gitlab master
GITLAB_PUSH_TOKEN="$token" GIT_ASKPASS="$askpass" git push gitlab --tags
