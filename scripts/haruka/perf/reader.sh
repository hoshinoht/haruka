#!/usr/bin/env bash
# On-device reader check for one chapter: time to first page and long-strip scroll jank.
#
# Usage: scripts/haruka/perf/reader.sh <package> <manga-id> <chapter-label>
#   package        app.haruka, app.haruka.dev or app.haruka.benchmark
#   manga-id       library entry id in that package's database (ids differ between packages)
#   chapter-label  the chapter row's name exactly as the manga screen shows it, e.g. "Chapter 83"; it must sit
#                  near the top of the list (the script scrolls at most SCROLLS times)
# Env: REPS=3 runs (medians reported), SWIPES=8 flings each way, TIMEOUT=20 s wait for a page, SCROLLS=10,
#      COLD=1 empties the chapter and network caches and restarts the app before every run (debuggable builds
#      only, it needs run-as; release builds have no shell access to their cache).
#
# A chapter label and not a chapter id: ReaderActivity is not exported, so `am start` from the shell is refused
# (also through run-as). The script opens the manga screen with MainActivity's SHOW_MANGA intent and taps the row.
# Runs read in incognito mode, switched on in the More tab and put back as it was on exit, so they write no
# progress, history or tracker updates. Pick online chapters: downloaded ones can start download-ahead.
#
# Signals, all from logcat timestamps (no app instrumentation):
#   first page    ActivityTaskManager "START" of ReaderActivity to the first "Decoded ..." line the high-quality
#                 renderer (Settings > Advanced) logs at INFO. The classic renderer logs nothing per page in a
#                 release build, so then the first page is n/a and only "displayed" is meaningful.
#   displayed     ActivityTaskManager "Displayed" for ReaderActivity: the reader's first frame, not the page.
#   jank          dumpsys gfxinfo over SWIPES flings each way on the open chapter, like run.sh. The high-quality
#                 renderer draws outside HWUI, so gfxinfo sees no frames: n/a, use trace.sh for it.
#   next chapter  not measured. The classic renderer logs nothing per page. The high-quality one decodes the
#                 adjacent chapters' pages ahead (the previous chapter's last page about 1 s after opening, the
#                 next chapter's once the strip nears its end) and names pages by image URL, not chapter and
#                 index, so no log line marks the next chapter's first page being shown.
# Baseline and notes: docs/haruka/perf-reader-baseline.md
set -euo pipefail
# Pipelines read their input to the end: an early exit (grep -q, awk exit) would fail them under pipefail.

PKG=${1:?package}
MANGA_ID=${2:?manga-id}
CHAPTER=${3:?chapter label}
REPS=${REPS:-3}
SWIPES=${SWIPES:-8}
TIMEOUT=${TIMEOUT:-20}
SCROLLS=${SCROLLS:-10}
COLD=${COLD:-0}
MAIN=$PKG/eu.kanade.tachiyomi.ui.main.MainActivity
READER=$PKG/eu.kanade.tachiyomi.ui.reader.ReaderActivity
UI=/sdcard/haruka-reader-ui.xml
MARKER=' WebGpuViewer[A-Za-z]*: Decoded '

ash() { adb shell "$@" | tr -d '\r'; }
now() { ash date +%s.%N | cut -c1-14; }
die() { echo "reader.sh: $*" >&2; exit 1; }

size=$(ash wm size | awk -F': ' 'END{print $2}')
W=${size%x*}; H=${size#*x}
X=$((W / 2)); LO=$((H * 77 / 100)); HI=$((H * 22 / 100))

fling() { # run.sh's fling, scaled to the screen
  for _ in $(seq 1 "$1"); do ash input swipe $X $LO $X $HI 120; sleep 0.6; done
  for _ in $(seq 1 "$1"); do ash input swipe $X $HI $X $LO 120; sleep 0.6; done
}
center_of() { # center of the first node whose attribute $1 is exactly $2, with its center y in [$3, $4)
  local v
  v=$(printf '%s' "$2" | sed 's/[][\.*^$/]/\\&/g')
  ash uiautomator dump $UI >/dev/null 2>&1 || return 1
  adb exec-out cat $UI | grep -o "$1=\"$v\"[^>]*bounds=\"[^\"]*\"" | sed 's/.*bounds="//; s/[^0-9]/ /g' |
    awk -v min="${3:-0}" -v max="${4:-99999}" '!f && ($2 + $4) / 2 >= min && ($2 + $4) / 2 < max { printf "%d %d\n", ($1 + $3) / 2, ($2 + $4) / 2; f = 1 } END { exit !f }'
}
tap_node() { local c; c=$(center_of "$@") || return 1; ash input tap $c; }
top() { ash dumpsys activity activities | grep -m1 topResumedActivity || true; }
# The banner MainActivity shows at the top while incognito is on (the notification needs a permission).
incognito() { if center_of text "Incognito mode" 0 $((H / 10)) >/dev/null; then echo 1; else echo 0; fi; }
set_incognito() { # 1 or 0, through the More tab of the running app
  [ "$(incognito)" = "$1" ] && return 0
  # SHOW_DOWNLOADS pops to the home screen, selects More and opens the download queue; Back leaves it on More
  ash am start -n $MAIN -a eu.kanade.tachiyomi.SHOW_DOWNLOADS >/dev/null 2>&1; sleep 2
  ash input keyevent KEYCODE_BACK; sleep 1.5
  # The More row, not the banner shown at the top while incognito is on
  tap_node text "Incognito mode" $((H / 10)) || die "incognito switch not found"
  sleep 1.5
  [ "$(incognito)" = "$1" ] || die "could not set incognito mode to $1"
}
close_reader() {
  local i
  for i in 1 2 3; do
    case "$(top)" in *ReaderActivity*) ;; *) return 0 ;; esac
    ash input keyevent KEYCODE_BACK; sleep 1.5
  done
}
cleanup() {
  close_reader || true
  [ -n "${INITIAL:-}" ] && { set_incognito "$INITIAL" || echo "reader.sh: restore incognito by hand" >&2; }
  ash rm -f $UI || true
}
logs_since() { adb logcat -d -v epoch -T "$1" 2>/dev/null | tr -d '\r'; }
launch_times() { # since $1: "<start epoch> <displayed ms> <first marker epoch>", "-" when missing
  logs_since "$1" | awk -v act="$READER" -v mk="$MARKER" '
    s == "" && index($0, "START u0") && index($0, "cmp=" act) { s = $1 }
    s != "" && d == "" && index($0, "Displayed " act) {
      match($0, /\+[0-9a-z]+ms/); v = substr($0, RSTART + 1, RLENGTH - 3)
      n = split(v, a, "s"); d = n == 2 ? a[1] * 1000 + a[2] : a[1]
    }
    s != "" && m == "" && $0 ~ mk { m = $1 }
    END { print (s == "" ? "-" : s), (d == "" ? "-" : d), (m == "" ? "-" : m) }'
}
ms() { awk -v a="$1" -v b="$2" 'BEGIN{ printf "%d", (b - a) * 1000 }'; }
jank() { # "<janky %> <p90> <p95> <p99> <frames>"
  ash dumpsys gfxinfo $PKG | awk '
    /Total frames rendered/{f=$4} /Janky frames:/{j=$4; gsub(/[(%)]/, "", j)}
    /90th percentile/{p90=$3} /95th percentile/{p95=$3} /99th percentile/{p99=$3}
    END{gsub(/ms/, "", p90); gsub(/ms/, "", p95); gsub(/ms/, "", p99); print j, p90, p95, p99, f}'
}
median() {
  printf '%s\n' "$@" | grep -v '^n/a$' | sort -n |
    awk '{a[NR]=$1} END{if (!NR) print "n/a"; else if (NR % 2) print a[(NR+1)/2]; else printf "%g\n", (a[NR/2]+a[NR/2+1])/2}'
}

[ "$(ash pm path $PKG)" ] || die "$PKG is not installed"
if [ "$COLD" = 1 ]; then
  case "$(ash dumpsys package $PKG | grep pkgFlags=)" in
    *DEBUGGABLE*) ;;
    *) die "COLD=1 needs a debuggable build (run-as)" ;;
  esac
fi
VERSION=$(ash dumpsys package $PKG | awk -F= '/versionName/ && !v {v = $2} END {print v}')
MODEL=$(ash getprop ro.product.model)

case "$(top)" in *" $PKG/"*) ;; *) ash am start -n $MAIN >/dev/null 2>&1; sleep 5 ;; esac
close_reader
INITIAL=$(incognito)
trap cleanup EXIT

first=(); shown=(); janky=(); p90=(); p95=(); p99=(); runs=()
has_marker=1
for rep in $(seq 1 "$REPS"); do
  if [ "$COLD" = 1 ]; then
    ash am force-stop $PKG
    adb shell run-as $PKG sh -c "'rm -rf cache/chapter_disk_cache/* cache/chapter_cache/* no_backup/chapter_cache/* cache/network_cache/*'"
    ash am start -n $MAIN >/dev/null 2>&1; sleep 5
  fi
  set_incognito 1

  ash am start -n $MAIN -a eu.kanade.tachiyomi.SHOW_MANGA --el manga "$MANGA_ID" >/dev/null 2>&1; sleep 3
  c=""
  for _ in $(seq 0 "$SCROLLS"); do
    c=$(center_of text "$CHAPTER") && break
    ash input swipe $X $LO $X $HI 600; sleep 1
  done
  [ -n "$c" ] || die "chapter row \"$CHAPTER\" not found on manga $MANGA_ID"

  # a. time to first page
  t=$(now); ash input tap $c
  deadline=$(( $(date +%s) + TIMEOUT ))
  while :; do
    set -- $(launch_times "$t")
    [ "$3" != - ] && break
    [ "$has_marker" = 0 ] && [ "$2" != - ] && break
    [ "$(date +%s)" -ge "$deadline" ] && break
    sleep 0.5
  done
  [ "$1" != - ] || die "ReaderActivity did not start"
  shown+=("$2")
  if [ "$3" != - ]; then first+=("$(ms "$1" "$3")"); else first+=(n/a); has_marker=0; fi

  # b. long-strip scroll jank
  sleep 2
  ash dumpsys gfxinfo $PKG reset >/dev/null
  fling "$SWIPES"
  set -- $(jank)
  if [ "${5:-0}" -ge $((SWIPES * 20)) ]; then # under ~10 frames a fling means HWUI did not draw the strip
    janky+=("$1"); p90+=("$2"); p95+=("$3"); p99+=("$4")
    runs+=("run $rep: frames=$5 janky=$1% p90=$2 p95=$3 p99=$4")
  else # the high-quality renderer draws outside HWUI, so gfxinfo sees no frames
    janky+=(n/a); p90+=(n/a); p95+=(n/a); p99+=(n/a)
    runs+=("run $rep: only ${5:-0} HWUI frames")
  fi
  close_reader
done

echo "== reader $PKG $VERSION on $MODEL, manga $MANGA_ID \"$CHAPTER\", $REPS runs$([ "$COLD" = 1 ] && echo ", cold caches")"
if [ "$has_marker" = 1 ]; then
  echo "first page ms:   ${first[*]}  median $(median "${first[@]}")"
else
  echo "first page ms:   n/a (no \"Decoded\" line: classic renderer, which logs nothing per page)"
fi
echo "displayed ms:    ${shown[*]}  median $(median "${shown[@]}")"
printf '  %s\n' "${runs[@]}"
if [ "$(median "${janky[@]}")" != n/a ]; then
  echo "scroll jank:     median janky=$(median "${janky[@]}")% p90=$(median "${p90[@]}")ms p95=$(median "${p95[@]}")ms p99=$(median "${p99[@]}")ms"
else
  echo "scroll jank:     n/a (too few HWUI frames: the high-quality renderer draws outside HWUI, use trace.sh)"
fi
echo "next chapter:    not measured (see the header)"
