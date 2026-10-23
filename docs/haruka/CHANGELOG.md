# Haruka changelog

Haruka's own changes on top of Mihon. Upstream changes are in [CHANGELOG.md](../../CHANGELOG.md).
Release notes for each GitHub release are taken from the matching `## [version]` section below.

## [0.1.0-beta.6]

Unreleased.

### Library
- Series you add without opening them (long-press in Browse, global search or related titles) now load their details and chapter list right away, one at a time in the background, instead of sitting at 0 chapters until the next library update. Their chapters don't show up in Updates as new. Settings › Library › Load chapters when adding turns it off.
- Your own shelves: under Library shelves › Your shelves, add a shelf from a library search (for example `unread>0 source:mangadex -genre:romance`); the editor shows how many series match as you type. "Group library by" adds one shelf per source, publication status, tracker or author with 2 or more series. "See all" works on these shelves too.

### Reader
- Detect long strips: a series still on the default reading mode opens in long strip when its tags say webtoon, long strip, manhwa or manhua; otherwise the first chapter's pages 2–4 are measured from their image headers (no extra download) and tall pages switch it to long strip once. A series with normal pages stays on the default, and a mode you pick yourself is never changed. Settings › Reader › Detect long strips turns it off.

### Downloads
- Settings › Data and storage › Downloads storage shows how much space your downloads use, by source and by series, without opening any files. It can remove downloads of series no longer in your library, and delete read chapters (keeping bookmarked chapters and excluded categories as your download settings say), optionally including copies of chapters you read in another version. Folders it can't match to a series are listed for you to check and are never deleted.

### Backup
- Creating a backup, and picking a file to restore, no longer decodes every chapter and history entry just to check which trackers it uses. On a Galaxy S25+ with about 950 series, creating a backup takes 7–8 s instead of 28–32 s (debug build). The backup files are unchanged and still restore in Mihon.

### From Mihon (main at d001e6f)
- High quality renderer: pages still loading show the reader's usual progress ring instead of a growing grey circle; new image and tile filters (load your own shaders) and a "Disable double tap gestures" option; pages decode at the size they are shown, using less memory.
- The download queue is rebuilt in Compose.
- Covers no longer reload when you remove a series from the library.
- Library updates start with the sources that have the most series.
- The "Split tall images" download option is gone; the long strip reader already decodes tall pages in strips, and splitting made PNG and WebP pages lossy.
- Settings › Advanced can redact sensitive headers in logcat.

## [0.1.0-beta.5]

Library chapter counts that ignore duplicate copies, an Inflated counts shelf, Start reading at the preferred copy, sane missing-chapter counts, and a readability pass on the Hoshino library.

### Performance
- Measured on a Galaxy S25+ with about 950 series (release build): cold start 581–607 ms, library jank 0.26%, Updates jank 0.81%. Flinging the library while every series is recounted stays at 0.9–1.6% jank with the slowest frames at 9–10 ms, the same as with counts settled.

### Library
- With shelves on, the "All series" bar under the toolbar is gone; the toolbar already shows the count. The bar only appears for category chips or a shelf's "See all" chip.
- With shelves on, the toolbar slides away while you scroll down the library and comes back as soon as you scroll up. It stays put while selecting or searching.
- In the Hoshino theme's compact grid, titles sit on a taller plate that fades into the theme's navy (white in light mode), with no text shadow, so they stay readable on pale and busy covers.
- In the Hoshino theme, the unread count on library covers is coral, like the Updates conversation view, and the badge strip (downloaded and unread) has a navy ring, so it stays visible on pink covers.
- In the Hoshino theme, the selected tab in the bottom bar sits in a solid pink pill, so it's clear which tab you're on.
- A new "Inflated counts" shelf (last, can be hidden like the others) lists series whose chapter count is padded: at least 1.5× as many uploads as chapters once duplicate copies are grouped, or a chapter number that is really an id or a year. "See all" filters the library to them, so you can tidy their scanlator ranking. Each series is checked once in the background and again when its chapters change.
- With duplicate chapters grouped, library chapter counts match the chapter list: a series with 405 uploads of 155 chapters shows 155, and a chapter read in any copy counts as read. Unread badges, the Unread and Total chapters sorts, the unread and started filters, and the shelves all use these counts. Only series whose chapters, read state or duplicate settings changed are recounted (a library update recounts just the series that got chapters), and the counts are kept between launches; the first count of a whole library takes about 10 seconds in the background on a Galaxy S25+ with 950 series. The Stats screen and the library updater's skip rules still count uploads.

### Chapters
- With duplicate chapters grouped, Start reading (on the series screen) and the library's continue-reading button now open the next chapter at its preferred copy, the one at the top of its group, instead of whichever upload came first in the list. A chapter read in any copy is skipped.
- Chapter numbers that are really ids, timestamps or years (a "Ch. 1482765166" in a series that ends at 69, or "2019: Grand Cleaning") no longer count in the series screen's missing-chapter total or the "Missing N chapters" dividers, so those show the real gaps instead of billions. The chapters themselves stay in the list as the source sent them.

### Fixes
- With the Expressive animations, tapping between the library's Filter, Sort and Display tabs switches at once and the sheet resizes within about 0.1 s, without bouncing; before, the sheet held its old height for half a second. Swiping between the tabs still slides.
- Progress bars are flat again (the library update, restore and sync banners, sync settings, global search, web view and the migration progress dialog); the Expressive animations no longer swap in Material 3 Expressive's wavy bar.

## [0.1.0-beta.4]

Library shelves and a pinned, sectioned library, plus safer bulk migration with match confidence, a review step and source presets.

### Performance
- Measured on a Galaxy S25+ with about 950 series: cold start 577–596 ms; library jank 0.33% with shelves off and 0.53% with shelves on (0.40% before this release); Updates jank 0.50–0.88%.

### Look and feel
- Hoshino uses Zen Maru Gothic for headings and titles only again; body and label text are back on the system font, and nothing is downloaded from Google Fonts.

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
