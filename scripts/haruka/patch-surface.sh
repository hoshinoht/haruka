#!/usr/bin/env bash
# Reports how much of upstream Mihon the fork modifies.
# Usage: scripts/haruka/patch-surface.sh [upstream-ref]   (default: upstream/main)
set -euo pipefail

upstream_ref="${1:-upstream/main}"
base="$(git merge-base HEAD "$upstream_ref")"

# Paths owned by the fork; changes here are not upstream patch surface.
fork_owned='^(app/src/main/java/haruka/|app/src/test/java/haruka/|app/src/haruka/|app/src/benchmark/|docs/haruka/|i18n-fork/|scripts/haruka/|\.github/workflows/haruka-|AGENTS\.md$)'

modified="$(git diff --name-only --diff-filter=MD "$base" HEAD | grep -Ev "$fork_owned" || true)"
added_outside="$(git diff --name-only --diff-filter=A "$base" HEAD | grep -Ev "$fork_owned" || true)"

count() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | wc -l | tr -d ' '; fi; }

echo "Base: $(git rev-parse --short "$base") ($upstream_ref)"
echo "Upstream files modified or deleted: $(count "$modified")"
[ -n "$modified" ] && printf '  %s\n' $modified
echo "Files added outside fork-owned paths: $(count "$added_outside")"
[ -n "$added_outside" ] && printf '  %s\n' $added_outside

markers="$(git grep -c -e '// FORK -->' -e '<!-- FORK -->' -- . ':!AGENTS.md' ':!scripts/haruka' | awk -F: '{s+=$NF} END {print s+0}')"
echo "FORK hook blocks: $markers"
