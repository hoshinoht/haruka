#!/usr/bin/env bash
# On-device performance check for a Haruka Bench build (app.haruka.benchmark) with a real library restored.
#
# Usage: scripts/haruka/perf/run.sh <label> [manga-id] [compile-mode]
#   label         name printed with the results, e.g. before / after
#   manga-id      library entry with a long chapter list to scroll (optional)
#   compile-mode  ART compile mode applied first (default speed-profile, i.e. a store-like install)
#
# Prints cold-start times (6 runs) and gfxinfo jank for 8 flings each way on Library, Updates and the manga screen.
# Build and install first: ./gradlew assembleBenchmark && adb install -r app/build/outputs/apk/benchmark/app-arm64-v8a-benchmark.apk
# For a Perfetto trace of a scroll: scripts/haruka/perf/trace.sh
set -euo pipefail

# PKG=app.haruka measures the installed release build instead (same R8 build as Bench, with the owner's library).
PKG=${PKG:-app.haruka.benchmark}
ACTIVITY=$PKG/eu.kanade.tachiyomi.ui.main.MainActivity
LABEL=${1:?label}
MANGA_ID=${2:-}
MODE=${3:-speed-profile}

tap_text() { # tap the first node whose text is exactly $1
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  local b
  b=$(adb shell cat /sdcard/ui.xml | grep -o "text=\"$1\"[^>]*bounds=\"[^\"]*\"" | head -1 | grep -o '[0-9]\+' | tr '\n' ' ' || true)
  [ -n "$b" ] || { echo "not found: $1" >&2; return 1; }
  set -- $b
  adb shell input tap $(( ($1 + $3) / 2 )) $(( ($2 + $4) / 2 ))
}
fling() {
  for _ in $(seq 1 "$1"); do adb shell input swipe 720 2400 720 700 120; sleep 0.6; done
  for _ in $(seq 1 "$1"); do adb shell input swipe 720 700 720 2400 120; sleep 0.6; done
}
jank() {
  adb shell dumpsys gfxinfo $PKG | awk -v l="$1" '
    /Total frames rendered/{f=$4} /Janky frames:/{j=$3" "$4}
    /50th percentile/{p50=$3} /90th percentile/{p90=$3} /95th percentile/{p95=$3} /99th percentile/{p99=$3}
    END{printf "%-10s frames=%s janky=%s p50=%s p90=%s p95=%s p99=%s\n", l, f, j, p50, p90, p95, p99}'
}
fresh() { adb shell am force-stop $PKG; sleep 1; }

# Install the embedded baseline profile, then compile the way a store install would.
adb shell am start -W -n $ACTIVITY >/dev/null; sleep 3
adb shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  $PKG/androidx.profileinstaller.ProfileInstallReceiver >/dev/null
sleep 2; fresh
adb shell cmd package compile -f -m "$MODE" $PKG >/dev/null

echo "== $LABEL (compile=$MODE)"
times=()
for _ in 1 2 3 4 5 6; do
  fresh
  times+=("$(adb shell am start -W -n $ACTIVITY | awk '/TotalTime/{print $2}')")
  sleep 3
done
echo "cold start ms: ${times[*]}"

for tab in Library Updates; do
  fresh; adb shell am start -n $ACTIVITY >/dev/null; sleep 4
  tap_text "$tab"; sleep 2
  adb shell dumpsys gfxinfo $PKG reset >/dev/null; fling 8; jank "$tab"
done

if [ -n "$MANGA_ID" ]; then
  fresh
  adb shell am start -n $ACTIVITY -a eu.kanade.tachiyomi.SHOW_MANGA --el manga "$MANGA_ID" >/dev/null; sleep 5
  adb shell dumpsys gfxinfo $PKG reset >/dev/null; fling 8; jank chapters
fi
