# Haruka changelog

Haruka's own changes on top of Mihon. Upstream changes are in [CHANGELOG.md](../../CHANGELOG.md).
Release notes for each GitHub release are taken from the matching `## [version]` section below.

## [0.1.0-beta.2]

In progress.

### Chapters
- Parts named "Chapter 21 -1", "-2", … are recognised as parts of chapter 21 even when the source numbers them all 21.
- A whole chapter is preferred over the same chapter split into parts (unless the parts are more official).
- Tapping a split chapter opens its first unread part, and the reader goes through the parts in order.

### Look and feel
- Hoshino is listed first in the theme picker.

### Internal
- Performance checks kept in `scripts/haruka/perf` (cold start, list jank, Perfetto trace).

## [0.1.0-beta.1]

First beta. Haruka installs beside Mihon (`app.haruka`); restore a Mihon backup to bring your library over.

### Chapters
- Official-first chapter deduplication: when several scanlators or uploads cover the same chapter, the manga screen, reader, downloads and read marking use one preferred copy. Rules, scanlator ranking and per-series overrides are in Settings › Library › Duplicate chapters.
- Other copies and split parts stay one tap away under a "versions" chip on the chapter row.
- Chapter swipe actions can show as a pill that lights up once letting go will apply the action (Settings › Library).

### Library and updates
- Updates can group chapters per entry and day, optionally as conversations.
- Wide covers can show in full instead of cropped (Settings › Appearance).
- Library update and backup restore progress show as in-app banners.
- A screen lists entries that failed the last library update.
- Optional shorter titles that hide trailing lists of alternative names.
- Entry screens can show related titles suggested by the extension.

### Migration
- Range-select entries with long-press, select all or none, and jump to the top or bottom.
- Entries from unavailable sources are flagged and can be migrated all at once.
- Long-press adds or removes an entry from global search results.

### Look and feel
- Hoshino theme in dark and light, with pink unread badges. It is the default theme.
- Selection actions sit in a floating toolbar.
- Animations setting (Settings › Appearance): Standard keeps Mihon's motion; Expressive (default) uses Material 3 Expressive springs, morphing loading indicators, wavy progress, button press morphs and pill swipe actions; Reduced uses short fades and is applied automatically when system animations are off.

### Fixes and performance
- The fast scroller can be grabbed while the list is still moving.
- Covers no longer redraw the "failed to load" placeholder for every item; scrolling uses less main-thread time.
- Haruka code is precompiled on install via baseline profile rules.

### Updates
- The in-app updater follows Haruka releases. Beta builds receive beta updates; turn this off in Settings › About.
- Settings › About shows the Mihon base Haruka is built on (Mihon's development branch).

### Known issues
- Settings › Advanced › Use high quality renderer is Mihon's experimental reader. It is off by default and is used for every reading mode, long strip included, when on.
