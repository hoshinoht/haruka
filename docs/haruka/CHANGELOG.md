# Haruka changelog

Haruka's own changes on top of Mihon. Upstream changes are in [CHANGELOG.md](../../CHANGELOG.md).
Release notes for each GitHub release are taken from the matching `## [version]` section below.

## [0.1.0-beta.4]

Library shelves and a pinned, sectioned library, plus safer bulk migration with match confidence, a review step and source presets.

### Performance
- Measured on a Galaxy S25+ with about 950 series: cold start 577–596 ms; library jank 0.33% with shelves off and 0.53% with shelves on (0.40% before this release); Updates jank 0.50–0.88%.

### Library
- Library shelves (Settings › Appearance, off by default): the library becomes one scroll. At the top, a Continue reading carousel shows up to 10 series you've started that have unread chapters, newest read first, with where you stopped ("Ch. 12 · page 5") and a resume button that opens the reader. Below it, New chapters (unread chapters fetched in the last 7 days), Not started (newest added first) and Needs a new source (series whose source is not installed, not loaded or obsolete, each marked "Source unavailable") show up to 15 covers each, with titles under the covers. A series appears on one shelf at most. Shelves cover every category, hide while searching, and hide when the library has fewer than 20 series or nothing to show. Long-press a shelf cover to select it.
- "See all" on a shelf jumps to the full library and shows only that shelf's series, with a chip in the header ("New chapters ×") to show everything again. The filter is temporary: searching or clearing the chip removes it, and it never changes your saved filters.
- Each shelf can be hidden on its own under the Library shelves setting; a hidden shelf's series can then appear on the shelves below it.
- Under the shelves, an "All series" header stays pinned under the toolbar with the count and current sort. Dividers split the grid by the active sort (Today / This week / This month for dates, A–Z for titles, unread and chapter-count ranges). Category chips in the header replace the tabs when you use categories. Your display mode, columns and badges are unchanged. The settings sync across devices.
- With shelves on, tapping the Library tab again scrolls back to the top; tapping it at the top opens the library settings as before.

### Migration
- Bulk migration rates each match High, Check or Low with a coloured chip and a short reason under the target ("Read to Ch. 120 · match has 98", "Title differs", "Different author"), from title similarity, the author and whether the match reaches your furthest read chapter. Before Migrate all or Copy all, Check and Low matches open a review sheet where you accept, search manually or skip each one; with nothing to review, the usual dialog opens straight away.
- Source presets on the migration source screen (list icon in the toolbar): save the current selection and order under a name, tap a preset to select exactly its sources in its order, and rename or delete presets. Sources that aren't installed are shown as "N not installed" and the rest still apply. Presets sync with your other Haruka settings.

## [0.1.0-beta.3]

Roomier grouped Updates rows and Zen Maru Gothic throughout the Hoshino theme.

### Library and updates
- Grouped Updates rows are roomier, in Komikku's style: larger book covers (with panorama covers on, wide covers are fitted inside the same frame so every row lines up), a bigger series title and chapter name, and a "+N" button on the row to show a series' other chapters instead of a separate row.
- Each day in Updates shows how many chapters arrived; the scanlator or note after a chapter name is shown dimmer so the chapter number reads first.

### Look and feel
- The Hoshino theme uses Zen Maru Gothic for all text, not just headings, with body text in Medium weight so it doesn't read thin. The body weights download through Google Fonts on first use; until then body text uses the system font.
- In the Hoshino theme, titles on compact-grid library covers fade into the theme's navy instead of black.

## [0.1.0-beta.2]

Library sync across your devices through your own Google Drive, plus chapter-list fixes and a leaner fork.

### Sync
- Sync your library between devices with Google Drive (Settings › Data and storage › Google Drive). Haruka keeps its data in a hidden app folder in your Drive that other apps can't see; there is no Haruka server. See [sync.md](sync.md).
- Synced: library entries, categories, read and bookmark state, last page, reading history, tracker links, duplicate-chapter rules and Haruka settings.
- Changes from different devices are combined, not overwritten: a chapter read anywhere stays read, the furthest page wins, and for settings the device that syncs wins.
- Removing many entries at once (more than 25, or 10% of the library) waits for your confirmation on the Sync screen.
- Syncs automatically after you finish a chapter, when you open or leave the app, and on a schedule you choose (Wi-Fi only and charging only are optional).
- Optional end-to-end encryption with a passphrase. A lost passphrase cannot be recovered.
- Restoring a backup never deletes anything through sync: the next sync combines the restored library with the other devices.

### Chapters
- Parts named "Chapter 21 -1", "-2", … are recognised as parts of chapter 21 even when the source numbers them all 21.
- A whole chapter is preferred over the same chapter split into parts (unless the parts are more official).
- Tapping a split chapter opens its first unread part, and the reader goes through the parts in order.

### Look and feel
- Hoshino is listed first in the theme picker.
- Warning colours in Hoshino are coral instead of pink, so "18+" and "Mixed" extension labels are easy to tell apart.

### Internal
- Haruka now touches fewer Mihon files (73 before sync, 75 with it, down from 89), and its own code lives in separate `:haruka:core`, `:haruka:motion` and `:haruka:sync` modules.
- Startup profiles are Mihon's own plus Haruka rules; measured no slower.
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
