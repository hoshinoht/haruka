#!/usr/bin/env bash
# Reports how much of upstream Mihon the fork modifies, in the working tree (uncommitted changes included).
# Usage: scripts/haruka/patch-surface.sh [upstream-ref]   (default: upstream/main)
set -euo pipefail

upstream_ref="${1:-upstream/main}"
base="$(git merge-base HEAD "$upstream_ref")"

# Paths owned by the fork; changes here are not upstream patch surface.
fork_owned='^(haruka/|app/src/main/java/haruka/|app/src/test/java/haruka/|app/src/haruka/|app/src/benchmark/|gradle/build-logic/src/main/kotlin/haruka/|docs/haruka/|i18n-fork/|scripts/haruka/|\.github/workflows/haruka-|\.github/assets/haruka-|\.github/README\.md$|AGENTS\.md$|NOTICE$)'

modified="$(git diff --name-only --diff-filter=MD "$base" | grep -Ev "$fork_owned" || true)"
added_outside="$({ git diff --name-only --diff-filter=A "$base"; git ls-files --others --exclude-standard; } | grep -Ev "$fork_owned" | sort -u || true)"

count() { if [ -z "$1" ]; then echo 0; else printf '%s\n' "$1" | wc -l | tr -d ' '; fi; }
# FORK blocks open with `// FORK -->` (Kotlin/KTS/SQL) or `<!-- FORK -->` (XML).
blocks_in() { grep -c -e '// FORK -->' -e '<!-- FORK -->' "$1" 2>/dev/null || true; }

echo "Base: $(git rev-parse --short "$base") ($upstream_ref)"
echo "Upstream files modified or deleted: $(count "$modified")  (FORK blocks per file)"
for file in $modified; do
    n="$(blocks_in "$file")"
    printf '  %3s  %s\n' "${n:-0}" "$file"
done
echo "Files added outside fork-owned paths: $(count "$added_outside")"
[ -n "$added_outside" ] && printf '  %s\n' $added_outside

markers="$(git grep -c -e '// FORK -->' -e '<!-- FORK -->' -- . ':!AGENTS.md' ':!scripts/haruka' ':!docs/haruka' | awk -F: '{s+=$NF} END {print s+0}')"
echo "FORK hook blocks: $markers"

# Shadow imports: an upstream file swaps an import for a same-named haruka.* drop-in; the import line is the hook.
import_files="$(git grep -l -E '^import haruka\.' -- . | grep -Ev "$fork_owned" || true)"
import_lines=0
for file in $import_files; do
    import_lines=$((import_lines + $(grep -c -E '^import haruka\.' "$file")))
done
echo "Import hooks (import haruka.* in upstream files): $import_lines lines in $(count "$import_files") files"
[ -n "$import_files" ] && printf '  %s\n' $import_files
exit 0
