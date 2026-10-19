#!/usr/bin/env bash
# Record a 25 s Perfetto trace (frames, main-thread CPU samples) while flinging the screen that is open in
# Haruka Bench, and pull it to the given path. Open the result at https://ui.perfetto.dev.
#
# Usage: scripts/haruka/perf/trace.sh <out.pftrace>
set -euo pipefail
OUT=${1:?output path}
DIR="$(cd "$(dirname "$0")" && pwd)"
REMOTE=/data/misc/perfetto-traces/haruka-scroll.pftrace

adb push -q "$DIR/scroll-trace.pbtx" /data/local/tmp/haruka-scroll.pbtx
adb shell rm -f "$REMOTE"
adb shell "cat /data/local/tmp/haruka-scroll.pbtx | perfetto --txt -c - -o $REMOTE --background" >/dev/null
sleep 2
for _ in $(seq 1 10); do adb shell input swipe 720 2400 720 700 120; sleep 0.6; done
for _ in $(seq 1 10); do adb shell input swipe 720 700 720 2400 120; sleep 0.6; done
# The trace stops itself at duration_ms; wait for the file to be finalised.
sleep 12
adb pull -q "$REMOTE" "$OUT"
ls -l "$OUT"
