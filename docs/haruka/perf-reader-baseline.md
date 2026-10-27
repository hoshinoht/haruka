# Reader performance baseline

`scripts/haruka/perf/reader.sh` measures the reader on a real device over adb. It sits next to `run.sh` (cold start and list jank on Haruka Bench) and `trace.sh` (Perfetto trace). It is a separate script, not a `run.sh` mode, because it takes a package, a manga and a chapter, and repeats each measurement and reports medians, while `run.sh` runs a fixed sequence on the Bench build.

```bash
scripts/haruka/perf/reader.sh <package> <manga-id> <chapter-label>
# e.g. scripts/haruka/perf/reader.sh app.haruka 301 "Chapter 83"
# env: REPS=3 SWIPES=8 TIMEOUT=20 SCROLLS=10 COLD=0
```

## What it measures

| Line | Signal | Valid for |
|---|---|---|
| first page ms | `ActivityTaskManager: START` of ReaderActivity to the first `Decoded …` line of the high-quality renderer | High-quality renderer only (Settings › Advanced) |
| displayed ms | `ActivityTaskManager: Displayed` for ReaderActivity: the reader's first frame, not the page | Both renderers |
| scroll jank | `dumpsys gfxinfo` over 8 flings each way on the open chapter, as in `run.sh` | Classic renderer only |
| next chapter | Not measured | Neither |

Each run opens the manga screen with MainActivity's `SHOW_MANGA` intent, taps the chapter row, waits for the signals, flings, and closes the reader. The script prints each run and the median of three.

## How it avoids touching the library

- **Incognito mode.** The script switches incognito on from the More tab before reading, and puts it back as it was on exit. While it is on, the reader writes no progress, read state, history, tracker update or sync trigger. MainActivity turns incognito off on every cold launch, so the script re-checks it before each run. It reads the state from the banner at the top of the screen, because the notification needs a permission the dev build does not have.
- **Online chapters only.** Download-ahead starts only when the open chapter is already downloaded, so an online chapter starts no downloads.
- **No cache wipe by default.** `COLD=1` empties the chapter cache (`cache/chapter_disk_cache` before the e588e04 sync, `cache/chapter_cache` plus its index under `no_backup/chapter_cache` after it) and `cache/network_cache` through `run-as`, so it works only on debuggable builds. `pm clear` is never used, because it wipes the library. Without `COLD`, run 1 may download pages that runs 2 and 3 then read from the chapter cache.

## Finding ids

- **Release and Bench builds.** These are not debuggable, so their database cannot be read. Their ids also differ from the dev build's, even with the same library. To find a release id, open candidate ids with `SHOW_MANGA` and read the title and chapter count from a `uiautomator dump`.
- **Dev build.** Pull the database with `adb exec-out run-as app.haruka.dev cat databases/tachiyomi.db` into a scratch folder. The fork's tables are `manga` and `chapter`, and `user_favorite_at` marks library entries. Delete the copy afterwards.
- **Chapter rows.** The chapter label must be visible near the top of the manga screen's chapter list. The script scrolls at most `SCROLLS` times to find it.

## Limitations

- **No first-page signal on the classic renderer.** ReaderActivity is not exported, so the shell cannot start it with ids. The classic renderer logs nothing per page at release log levels. A `uiautomator` dump takes about 2 s and only shows the "1 / N" page counter, which appears when the page list loads rather than the image. So on the default renderer, only "displayed" is reported, and that is not the page.
- **First decoded page, not first visible page.** On the high-quality renderer, pages decode in parallel, and the first `Decoded` line can be another page. In the dev baseline's first run, page 2 finished at 3375 ms and page 1 at 3877 ms. In runs 2 and 3, page 1 was first. The number is a lower bound for the first visible page.
- **No jank numbers on the high-quality renderer.** It draws outside HWUI, so `gfxinfo` sees only a few menu frames. The script reports n/a when there are fewer than about 10 frames per fling. Use `trace.sh` for that renderer.
- **No chapter transition timing.** The classic renderer logs nothing per page. The high-quality continuous viewer decodes adjacent chapters ahead. In the baseline it decoded the previous chapter's last page about 1 s after opening, and the next chapter's pages once the flings neared the end of the strip. Its log names pages by image URL, not by chapter and index, so no line marks the next chapter's first page being shown. A tap on "Next chapter" timed to the next decode caught decodes that happen anyway, 65 to 117 ms, so that approach was dropped.
- **The phone must be free.** The script drives the UI with taps. Another agent or person using the phone at the same time breaks a run, or skews jank through extra input.

## Baseline

Recorded on 2026-10-27, from 20:39 to 20:41 (+08), on a Galaxy S25+ (SM-S936B) at 1440x3120 over wireless adb. The phone was charging, and no other adb client sent input during the runs. The series is an 85-chapter Lua Scans long strip in the owner's library, and "Chapter 83" is an online, unread chapter of 9 pages. The release and dev builds give that series different ids.

### Release: app.haruka 0.1.0-beta.6

This is the release build installed that day, on the Mihon 007ec9a base, with the classic renderer and the long-strip default reading mode.

```bash
scripts/haruka/perf/reader.sh app.haruka 301 "Chapter 83"
```

```text
== reader app.haruka 0.1.0-beta.6 on SM-S936B, manga 301 "Chapter 83", 3 runs
first page ms:   n/a (no "Decoded" line: classic renderer, which logs nothing per page)
displayed ms:    30 25 27  median 27
  run 1: frames=1037 janky=0.39% p90=17 p95=17 p99=18
  run 2: frames=1533 janky=0.33% p90=17 p95=17 p99=17
  run 3: frames=1524 janky=0.20% p90=17 p95=17 p99=17
scroll jank:     median janky=0.33% p90=17ms p95=17ms p99=17ms
next chapter:    not measured (see the header)
```

Run 1 drew fewer frames than runs 2 and 3. It was the first open of the chapter in that session, so pages may still have been loading.

### Dev: app.haruka.dev 0.1.0-beta.6-8344

This is a debug build, so it is slower than the release and not comparable with it. Its settings have the high-quality renderer on and long strip as the default mode. It is recorded to show the first-page signal working. Run 1 includes downloading the pages, and runs 2 and 3 read them from the chapter cache.

```bash
scripts/haruka/perf/reader.sh app.haruka.dev 521 "Chapter 83"
```

```text
== reader app.haruka.dev 0.1.0-beta.6-8344 on SM-S936B, manga 521 "Chapter 83", 3 runs
first page ms:   3375 366 316  median 366
displayed ms:    42 40 34  median 40
  run 1: only 6 HWUI frames
  run 2: only 6 HWUI frames
  run 3: only 6 HWUI frames
scroll jank:     n/a (too few HWUI frames: the high-quality renderer draws outside HWUI, use trace.sh)
next chapter:    not measured (see the header)
```
