# Abydos Shelves: implementation handoff

Status: design approved 2026-10-19, not started. Build **Phase 1** first (below). Read `AGENTS.md` at the repo root before any change: its fork rules (FORK markers, no upstream imports, `i18n-fork` only, patch surface) apply to everything here.

## Goal

Replace the Hoshino library's wall of 951 covers with one scroll:

1. a Continue reading carousel and a few shelves that answer "what should I read next", then
2. the full library (the **Collection**) under a pinned header, split by dividers that follow the active sort.

All of it sits behind a Haruka setting that is off by default. Off must mean today's library, unchanged.

## Designs

Open these in a browser. They are the visual spec for layout, spacing and colour. This file is the spec for behaviour and code.

| File | What it shows |
| --- | --- |
| `designs/abydos-shelves.html` | Full spec page: the unrolled scroll, framing options, divider and shelf tables, behaviour, build notes |
| `designs/abydos-shelves-scroll.png` | The whole scroll as one image. The dashed frame is the first screen (412 × 915 dp). |
| `designs/legibility-proposals.html` | Audit of the current grid, legibility fixes A–E, layout concepts R1–R3 (Phase 1 doesn't need these) |
| `designs/current-grid.png` | The current grid, with synthetic stress-test covers |

The mockups use synthetic covers. Phone mocks are scaled to about 0.7×. Colours come from `haruka/theme/HoshinoColorScheme.kt` (dark).

Identity, in brief: MomoTalk (chat bubbles with a 4 dp tucked lower-left corner, coral unread dots, centred pills between groups like chat date separators), Hoshino (navy surfaces, pink `primary`, aqua `tertiary`, Zen Maru Gothic, `HoshinoShapes`), and Material 3 Expressive (multi-browse carousel, sticky headers).

## Decisions (made by the owner, do not reopen)

| Topic | Decision |
| --- | --- |
| Framing | **F1: one continuous scroll.** Shelves sit above the Collection in a single `LazyVerticalGrid`. Losing the sideways swipe between categories is accepted. |
| Categories | The owner uses none. Show no chip row. If categories exist (other users), show a chip row in the pinned header that behaves like today's tabs (`onChangeCurrentPage`). |
| Shelves and categories | Shelves are library-wide and never follow a category. |
| Collection grid | The user's current display mode, column count and item look, **unchanged**. No title or badge restyle in this work. |

## Anatomy (top to bottom)

1. **Top bar:** upstream `LibraryToolbar`, untouched (title, count, search, filter, overflow, selection mode).
2. **Continue reading carousel** (Phase 2): M3E `HorizontalMultiBrowseCarousel`. The large item carries a MomoTalk bubble (title, "Ch. N · page X of Y") on `surfaceContainerHigh`, plus a pink resume button wired to `onContinueReadingClicked`.
3. **Shelves** (Phase 2/3): horizontal rows of up to 15 covers with titles **below**, ending in a dashed "See all" tile. Empty shelves hide.
4. **Collection header** (Phase 1): `stickyHeader` with "All series · N" and the current sort as a chip.
5. **Collection grid** (Phase 1): the current display mode, with full-span divider pills between groups.
6. **Fast-scroller label** (Phase 1, stretch): the group label in a bubble next to the existing thumb.

## Phase 1: frame the Collection

Scope: setting, hook, pinned header, sort dividers. No shelves or carousel yet.

### Where the code goes

| Path | Content |
| --- | --- |
| `app/src/main/java/eu/kanade/presentation/library/components/LibraryContent.kt` | **One** FORK hook at the top of the `Column` body. When the setting is on, call `haruka.library.shelves.ShelvesLibrary(...)` with the same parameters, then `return@Column`. Use fully qualified calls and add no imports (AGENTS.md). |
| `app/src/main/java/haruka/library/shelves/` (new) | `ShelvesLibrary` composable, `CollectionHeader`, `SectionPill`, and an adapter `LibraryItem.toShelfInput()` |
| `haruka/core/src/main/java/haruka/library/` (new) | Pure logic, no app dependencies: `SortSections` (bucket keys plus grouping of contiguous runs) over a small fork-owned input type |
| `haruka/core/src/test/java/haruka/library/` (new) | Unit tests for every sort type and every bucket boundary |
| `app/src/main/java/haruka/preference/HarukaPreferences.kt` | `val libraryShelves = preferenceStore.getBoolean("haruka_library_shelves", false)`, following the existing pattern |
| `app/src/main/java/haruka/sync/local/SyncedPrefs.kt` | Add `"haruka_library_shelves" to PrefStorage.BOOLEAN`, next to the other `haruka_*` toggles, so the setting syncs across devices like them |
| Haruka settings screen | The toggle, wherever the other `haruka_*` toggles live |
| `i18n-fork/src/commonMain/moko-resources/base/strings.xml` | Setting label and summary, "All series", and the divider labels. Use plurals where counts appear. |

### What the hook receives

All the inputs already come into `LibraryContent` as parameters: `categories`, `currentPage`, `searchQuery`, `selection`, `contentPadding`, `hasActiveFilters`, `showPageTabs`, `getItemsForCategory`, `getItemCountForCategory`, `getDisplayMode`, `getColumnsForOrientation`, and every callback. Pass them all through.

- **Items:** `getItemsForCategory(categories[currentPage])`. These come already filtered and **already sorted** by `LibraryViewModel`. Don't re-sort them.
- **Sort:** `categories[currentPage].sort` (`LibrarySort`). `LibraryViewModel` sorts by `category.sort`, so this is the effective sort.
- **Display mode and columns:** `getDisplayMode(currentPage)` and `getColumnsForOrientation(isLandscape)`, used the same way as `LibraryPager.kt`.

### Rendering the Collection

- One `FastScrollLazyVerticalGrid` (from `presentation-core`), with the same columns, padding and spacing as `LazyLibraryGrid`. `GridCells.Adaptive(128.dp)` applies when columns is 0.
- `stickyHeader(key = "collection")` holds `CollectionHeader`. `LazyGridScope.stickyHeader(key, contentType, content)` is a member of `LazyGridScope` in foundation 1.13.0-beta01, which is what the app resolves; the class files carry no experimental marker, so no opt-in should be needed (add one only if the compiler asks).
- For each section: a full-span divider item (`span = { GridItemSpan(maxLineSpan) }`, key `"div-<key>"`, contentType `"divider"`), then that section's items (key `it.id`, contentType `"manga"`).
- Render items with the same composables and badge wiring as upstream. Copy the per-item block from `LibraryCompactGrid.kt`, `LibraryComfortableGrid.kt` and `LibraryList.kt`: `MangaCompactGridItem` (with title, or none for `CoverOnlyGrid`), `MangaComfortableGridItem`, and the list item (List mode = `GridCells.Fixed(1)`). The badges (`DownloadsBadge`, `UnreadBadge`, `LanguageBadge`) are `internal` in the same Gradle module, so the `haruka.*` code can call them.
- Keep `globalSearchItem(searchQuery, onGlobalSearchClicked)` at the top when searching, and `PullRefresh` around the grid, with the same refresh logic as `LibraryContent`.
- Click behaviour matches `LibraryContent`: in selection mode a click toggles selection, otherwise it opens the series. Long-press calls `onToggleRangeSelection`.

### Divider rules (`SortSections`)

Assign each item a bucket key from the sort type, then group runs of the same key **in the order given**. Because the list is already sorted, every bucket is one contiguous run, and a descending sort simply reverses the order of the groups. Use the device's local time zone. Pass `now` and the zone in as parameters so tests are deterministic.

| Sort type | Buckets (label) | Field |
| --- | --- | --- |
| LastRead | Today (same local date), This week (< 7 days), This month (< 30 days), Earlier this year (same calendar year), Older, Never read (null) | `libraryManga.lastRead` |
| LastUpdate | Today, This week, This month, Older | `manga.lastUpdate` |
| LatestChapter | same as LastUpdate | `libraryManga.latestUpload` |
| ChapterFetchDate | same as LastUpdate | `libraryManga.chapterFetchedAt` |
| DateAdded | "October 2026" (month and year, localised); `favoriteAt` of 0 or below (old restored backups) goes under "Unknown" | `manga.favoriteAt` |
| Alphabetical | `#`, `A`…`Z`, `Other`. See "Alphabetical buckets" below. | `manga.title` |
| UnreadCount | 10+ unread, 1–9 unread, Caught up | `unreadCount` (upstream's comparator always puts unread first) |
| TotalChapters | 100+, 50–99, 10–49, Under 10 | `totalChapters` |
| TrackerMean | **No dividers in Phase 1.** Tracker scores live inside `LibraryViewModel` and don't reach `LibraryContent`. | — |
| Random | No dividers | — |

A null date sorts at one end under upstream's `compareValues`, so its bucket (Never read, or Older) stays contiguous. Check that this holds in tests.

#### Alphabetical buckets

Upstream sorts by `manga.title` with `compareToWithCollator` (`core/common/.../SortUtil.kt`: `Collator` for the default locale, `PRIMARY` strength, so case and accents are ignored). A "first character" rule doesn't match that order: digits and symbols sort before `A`, but CJK, Hangul and Cyrillic titles sort **after** `Z`, so a single `#` bucket would appear at both ends of the list.

Derive the bucket from the same collator that sorted the list:

- Take a `Comparator<String>` parameter (the app passes the one behind `compareToWithCollator`; tests pass a fixed-locale `Collator` so they don't depend on the device language).
- A title that compares below `"A"` → `#`.
- Otherwise the bucket is the largest letter `L` in `A`…`Z` with `L <= title`, **if** the title starts with that letter at primary strength (so `É…` lands under `E`). A title above every Latin letter that doesn't start with `Z` → `Other`, placed last.
- Use `manga.title`, not the display title. Compact titles only drop a trailing bracketed group, so the first letter is the same.
- Test: no bucket label appears twice in any ascending or descending list, including mixed Latin, accented, digit, symbol, Japanese and Korean titles.

### Pinned header

- Shows "All series" plus the item count (`getItemCountForCategory`, falling back to `items.size`) and the sort as a chip ("Last read", with an arrow for direction). Background `surfaceContainer`, and a subtle bottom divider when it's pinned.
- Tapping the sort chip: `LibraryContent` has no callback that opens the settings sheet. **Either** make the chip label-only in Phase 1, **or** add a second small FORK hook passing `onClickFilter` from `LibraryTab.kt`. Prefer label-only unless the hook is trivial, and say which you chose in the PR.
- Category chips: show them under exactly the condition upstream uses for tabs (`LibraryContent.kt`: `showPageTabs && categories.isNotEmpty() && (categories.size > 1 || !categories.first().isSystemCategory)`), so a single user-made category still gets its chip. A chip calls `onChangeCurrentPage(index)`.

### Fast-scroller label (stretch)

`FastScrollLazyVerticalGrid` lives in upstream `presentation-core` and has no label slot. Don't edit it in Phase 1. If you want the label, draw an overlay in fork code that reads `gridState.firstVisibleItemIndex` → section, and shows it only while scrolling. Otherwise skip it and note that in the PR.

### Phase 1 acceptance

- [ ] Setting off: the library renders exactly as upstream. `scripts/haruka/patch-surface.sh` shows only the new `LibraryContent.kt` hook (and the `LibraryTab.kt` hook, if you chose it).
- [ ] Setting on: the header pins under the toolbar while the grid scrolls, and the dividers match the table for every sort type and direction.
- [ ] All four display modes (compact, comfortable, cover-only, list) and column settings render as they do today, badges included.
- [ ] Search (including the global-search row), selection, range selection, continue-reading buttons and pull-to-refresh all behave as they do today.
- [ ] `SortSections` unit tests cover every sort type, every boundary (midnight, day 7, day 30, year change), null dates, and both directions.
- [ ] New strings only in `i18n-fork`. No edits in `i18n/`.
- [ ] Checks pass: `./gradlew spotlessApply spotlessCheck`, `./gradlew testDebugUnitTest`, `./gradlew assembleDebug`, `scripts/haruka/patch-surface.sh`.
- [ ] Performance: with a real library restored in Haruka Bench, `scripts/haruka/perf/run.sh` before and after. Library jank stays under 1.5% (reference: Galaxy S25+, about 950 series).

## Phase 2: shelves and carousel

Add above the sticky header, inside the same grid as full-span items. Hide all of it while `searchQuery` is non-empty, and when the library has fewer than 20 series or every shelf is empty.

Shelves run top to bottom, and a series placed on one shelf is skipped by the shelves below it. It still always appears in the Collection. Build shelves from **all** categories' items (distinct by id), after filters. In the owner's setup that is just the one default category.

| Shelf | Includes | Order | Cap |
| --- | --- | --- | --- |
| Continue reading (carousel) | `hasStarted` and `unreadCount > 0` | `lastRead`, newest first | 10 |
| New chapters | `unreadCount > 0` and `chapterFetchedAt` within 7 days | `chapterFetchedAt`, newest first | 15 + See all |
| Not started | not `hasStarted` | `manga.favoriteAt`, newest first | 15 + See all |

- Shelf rules go in `haruka/core` as pure functions with tests, the same as `SortSections`.
- Compute them off the main thread (`Dispatchers.Default`, for example `produceState` keyed on the items list).
- The carousel's resume button calls `onContinueReadingClicked`. The bubble's page line needs the last-read chapter and page. If that isn't available from `LibraryItem`, show the title only and say so.
- Unread dot on shelf covers: `colorScheme.error` (coral) with a 1.5 dp `surface` ring, bold `labelSmall`. It's round, matching `UnreadBadge` in `haruka/updates/UpdatesConversation.kt`.
- Shelf items: long-press joins the shared selection. Range selection stays Collection-only.

## Phase 3: polish

- "See all" scrolls to the Collection header and adds a temporary filter chip (for example "New chapters ×"). It's in memory only and never written to filter preferences.
- A "Needs a new source" shelf, from `haruka.stale.StaleSources` (series on unavailable sources), with a "Source unavailable" chip on each cover.
- Per-shelf show/hide in Haruka settings.
- Re-tapping the Library tab scrolls to the top (`onReselect` in `LibraryTab.kt`, which would be a second hook).

## Confirmed on `dev` (2026-10-20)

1. **Sticky header in a grid:** `:app` resolves foundation **1.13.0-beta01**; `LazyGridScope.stickyHeader(key, contentType, content)` exists with no experimental marker.
2. **Carousel API (Phase 2):** material3 **1.5.0-beta01**. `HorizontalMultiBrowseCarousel(state, preferredItemWidth, modifier, itemSpacing, flingBehavior, userScrollEnabled, minSmallItemWidth, maxSmallItemWidth, contentPadding, content)`; no experimental marker found.
3. **Badge visibility:** `DownloadsBadge`, `UnreadBadge` and `LanguageBadge` are `internal` in `:app`, reachable from `haruka.*`.
4. **Per-category sort:** `LibraryViewModel.applySort` sorts each category by `key.sort`, reverses for descending, then breaks ties alphabetically. `categories[currentPage].sort` is the effective sort. Null dates land at one end; unread `0` always lands last.

## Related work already on `dev`

Commit `1903406ce` ("Hoshino: Zen Maru Gothic for all text, navy cover fade") added `haruka/theme/CoverScrim.kt`:

- `LocalHarukaAppTheme`: the active `AppTheme`, provided by `HarukaExpressiveTheme`. Use it for any Hoshino-only check rather than reading preferences.
- `coverScrimColor()`: the compact-grid title fade becomes navy (0xAA111B2B) under Hoshino.

That is a partial version of legibility proposal A (colour only, same 33% height). Leave it alone. This work doesn't change the grid's look.

## Out of scope

- Legibility fixes B–E and layouts R1/R3 from `designs/legibility-proposals.html`. These are deferred, and not part of this handoff.
- Any SQL, migration or backup change (AGENTS.md hard rules 2 and 3). Everything here comes from `LibraryManga` / `LibraryItem` fields already loaded.
- Commits and PRs: follow the owner's instructions for the task. Don't commit unless asked.
