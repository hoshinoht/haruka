#!/usr/bin/env bash
# Reports what the fork depends on that an upstream ref no longer has, before merging it: strings, symbols
# and the upstream files that host fork hooks. Reads the working tree (uncommitted changes included).
# Usage: scripts/haruka/upstream-drift.sh [upstream-ref]   (default: upstream/main)
# Exit status: 1 when anything is missing, 0 when clean.
set -euo pipefail

ref="${1:-upstream/main}"
git rev-parse --verify --quiet "${ref}^{commit}" > /dev/null || { echo "Unknown ref: $ref" >&2; exit 2; }
base="$(git merge-base HEAD "$ref")"
cd "$(git rev-parse --show-toplevel)"

# Paths owned by the fork. Must match `fork_owned` in scripts/haruka/patch-surface.sh.
fork_owned='^(haruka/|app/src/main/java/haruka/|app/src/test/java/haruka/|app/src/haruka/|app/src/benchmark/|gradle/build-logic/src/main/kotlin/haruka/|docs/haruka/|i18n-fork/|scripts/haruka/|\.github/workflows/haruka-|\.github/assets/haruka-|\.github/README\.md$|AGENTS\.md$|NOTICE$)'

# Opening markers: `// FORK -->`, `// FORK(tag) -->`, `<!-- FORK -->`, `<!-- FORK(tag) -->`.
# Closing markers: `// FORK <--`, `<!-- /FORK -->`.
fork_open='FORK(\([a-z-]+\))? -->'
fork_close='FORK <--|<!-- /FORK -->'

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

# Fork-owned Kotlin, tracked or new.
git ls-files --cached --others --exclude-standard -- '*.kt' | grep -E "$fork_owned" > "$tmp/fork_kt" || true
# Non-fork-owned files that host hooks: FORK markers or shadow imports (`import haruka.…`).
git grep -l -E -e "$fork_open" -e '^import haruka\.' -- . | grep -Ev "$fork_owned" > "$tmp/hosts" || true

# Prints "path:line:text" for lines inside FORK blocks of the given files.
in_fork_blocks() {
    [ -s "$1" ] || return 0
    # Regexes go through the environment: `awk -v` would eat their backslashes.
    FORK_OPEN="$fork_open" FORK_CLOSE="$fork_close" xargs awk '
        FNR == 1 { inside = 0 }
        $0 ~ ENVIRON["FORK_CLOSE"] { inside = 0; next }
        $0 ~ ENVIRON["FORK_OPEN"] { inside = 1; next }
        inside { print FILENAME ":" FNR ":" $0 }
    ' < "$1"
}

# --- a. Strings ----------------------------------------------------------------------------------------------
# Uses: "kind name path:line" for MR.strings.* / MR.plurals.* in fork-owned Kotlin and inside FORK blocks.
{
    [ -s "$tmp/fork_kt" ] && xargs grep -nH -E '(^|[^A-Za-z0-9_])MR\.(strings|plurals)\.' < "$tmp/fork_kt"
    in_fork_blocks "$tmp/hosts" | grep -E '(^|[^A-Za-z0-9_])MR\.(strings|plurals)\.'
} 2> /dev/null | awk '{
    split($0, part, ":"); where = part[1] ":" part[2]
    line = $0
    while (match(line, /(^|[^A-Za-z0-9_])MR\.(strings|plurals)\.[A-Za-z0-9_]+/)) {
        token = substr(line, RSTART, RLENGTH); line = substr(line, RSTART + RLENGTH)
        sub(/^[^M]*MR\./, "", token); split(token, t, ".")
        print t[1], t[2], where
    }
}' | sort -u > "$tmp/string_uses" || true

# Moko exposes a dotted name (`migrationConfigScreen.continueButtonText`) with underscores in Kotlin.
i18n_base='i18n/src/commonMain/moko-resources/base'
{
    git show "$ref:$i18n_base/strings.xml" 2> /dev/null | sed -n 's/.*<string name="\([^"]*\)".*/strings \1/p'
    git show "$ref:$i18n_base/plurals.xml" 2> /dev/null | sed -n 's/.*<plurals name="\([^"]*\)".*/plurals \1/p'
} | tr '.' '_' > "$tmp/declared"

awk 'NR == FNR { have[$1 " " $2] = 1; next } !(($1 " " $2) in have) { printf "  MR.%s.%s  %s\n", $1, $2, $3 }' \
    "$tmp/declared" "$tmp/string_uses" > "$tmp/missing_strings"

# --- b. Symbols ----------------------------------------------------------------------------------------------
# Explicit upstream imports in fork-owned Kotlin: "fqname path:line". Wildcard imports are skipped.
[ -s "$tmp/fork_kt" ] && xargs grep -nH -E '^import (eu\.kanade|tachiyomi|mihon)\.' < "$tmp/fork_kt" |
    awk -F: '{ sub(/^import[ \t]+/, "", $3); sub(/[ \t]+as[ \t].*$/, "", $3); sub(/[ \t;]+$/, "", $3)
               if ($3 !~ /\*$/) print $3, $1 ":" $2 }' > "$tmp/imports" || true

# Index of what the ref declares in non-fork-owned Kotlin: "package name" per declaration. Lenient on purpose:
# any `class|object|interface|fun|val|var|typealias` followed by a name counts (locals and members included),
# extension receivers and type parameters are stripped (`fun <T> List<T>.foo(` declares foo).
git grep -I -E -e '^package ' -e '(^|[^A-Za-z0-9_])(class|object|interface|fun|val|var|typealias)[[:space:]]' \
    "$ref" -- '*.kt' 2> /dev/null | REF="$ref" FORK_OWNED="$fork_owned" awk '
    {
        rest = substr($0, length(ENVIRON["REF"]) + 2); i = index(rest, ":")
        path = substr(rest, 1, i - 1); line = substr(rest, i + 1)
        if (path ~ ENVIRON["FORK_OWNED"]) next
        if (path != last) { last = path; pkg = "" }
        if (line ~ /^package /) { pkg = line; sub(/^package[ \t]+/, "", pkg); sub(/[ \t;].*$/, "", pkg); next }
        while (match(line, /(^|[^A-Za-z0-9_])(class|object|interface|fun|val|var|typealias)[ \t]+/)) {
            s = substr(line, RSTART + RLENGTH); line = s
            if (substr(s, 1, 1) == "<") {   # type parameters of a generic function
                d = 0
                for (k = 1; k <= length(s); k++) { c = substr(s, k, 1); if (c == "<") d++; else if (c == ">" && --d == 0) break }
                s = substr(s, k + 1); sub(/^[ \t]+/, "", s)
            }
            d = 0; name = ""
            for (k = 1; k <= length(s); k++) {
                c = substr(s, k, 1)
                if (c == "<") d++; else if (c == ">") d--
                else if (d == 0 && c ~ /[ \t(:={,]/) break
                name = name c
            }
            while (gsub(/<[^<>]*>/, "", name)) {}
            gsub(/[?`]/, "", name); sub(/^.*\./, "", name)
            if (name ~ /^[A-Za-z_][A-Za-z0-9_]*$/) print pkg, name
        }
    }' | sort -u > "$tmp/declared_symbols"

# Valkyrie generates mihon.icons.materialsymbols.<set>.<Name> from icons/*/src/main/valkyrieResources/<set>/<name>.svg,
# so icons are checked against the SVGs the ref ships ("<set lowercased>.<name without _>").
git ls-tree -r --name-only "$ref" -- icons | sed -n 's#^icons/[^/]*/src/main/valkyrieResources/\([^/]*\)/\(.*\)\.svg$#\1.\2#p' |
    tr -d '_' | tr 'A-Z' 'a-z' | sort -u > "$tmp/icons"

while read -r fq where; do
    name="${fq##*.}"
    case "$fq" in
        # Build-generated, never declared in Kotlin sources: Android R/BuildConfig, moko-resources MR (i18n),
        # SQLDelight's Database and query classes (package tachiyomi.data; .sq files are rule 2 anyway),
        # Apollo GraphQL clients (mihon.graphql.*) and the Valkyrie icon pack object.
        *.R | *.BuildConfig | tachiyomi.i18n.MR | mihon.graphql.* | mihon.icons.materialsymbols.MaterialSymbols) continue ;;
        tachiyomi.data.*) [ "${fq%.*}" = tachiyomi.data ] && continue ;;
        mihon.icons.materialsymbols.*.* | mihon.icons.custommaterialsymbols.*.*)
            key="$(printf '%s' "${fq#mihon.icons.*materialsymbols.}" | tr 'A-Z' 'a-z')"
            grep -qxF "$key" "$tmp/icons" || echo "  $fq  $where"
            continue ;;
    esac
    # Split the import into package, top-level declaration and an optional member (Outer.Inner, Enum.ENTRY):
    # the package is everything before the first capitalised segment.
    pkg=""; top=""; member=""
    IFS=. read -r -a seg <<< "$fq"
    for s in "${seg[@]}"; do
        if [ -n "$top" ]; then member="$s"
        elif [ "$s" = "$name" ] || case "$s" in [A-Z]*) true ;; *) false ;; esac; then top="$s"
        else pkg="${pkg:+$pkg.}$s"; fi
    done
    if ! grep -qxF "$pkg $top" "$tmp/declared_symbols"; then
        echo "  $fq  $where"
    elif [ -n "$member" ] && ! grep -qxF "$pkg $member" "$tmp/declared_symbols"; then
        # Enum entries and other members are not declarations the index sees; accept the name anywhere in the package.
        git grep -q -w -e "$member" "$ref" -- "*/$(printf '%s' "$pkg" | tr . /)/*.kt" 2> /dev/null ||
            echo "  $fq  $where"
    fi
done < "$tmp/imports" > "$tmp/missing_symbols"

# --- c. Hook hosts -------------------------------------------------------------------------------------------
# Upstream files with fork hooks that the ref deletes or renames (the hook would vanish or need moving).
git diff --name-status --diff-filter=DR "$base" "$ref" | awk -F'\t' '
    NR == FNR { host[$1] = 1; next }
    ($2 in host) { if ($1 == "D") print "  deleted  " $2; else print "  renamed  " $2 " to " $3 }
' "$tmp/hosts" - > "$tmp/missing_hosts"

# --- Report --------------------------------------------------------------------------------------------------
count() { wc -l < "$1" | tr -d ' '; }
strings="$(count "$tmp/missing_strings")"
symbols="$(count "$tmp/missing_symbols")"
hosts="$(count "$tmp/missing_hosts")"

echo "Upstream drift: $ref ($(git rev-parse --short "$ref")), merge base $(git rev-parse --short "$base")"
echo "Strings used by the fork but not declared upstream: $strings"
cat "$tmp/missing_strings"
echo "Upstream symbols imported by fork code but not declared upstream: $symbols"
cat "$tmp/missing_symbols"
echo "Hook hosts deleted or renamed upstream: $hosts"
cat "$tmp/missing_hosts"
total=$((strings + symbols + hosts))
echo "Summary: $strings strings, $symbols symbols, $hosts hook hosts ($(count "$tmp/string_uses") string uses, $(count "$tmp/imports") imports, $(count "$tmp/hosts") hook hosts checked)"
[ "$total" -eq 0 ]
