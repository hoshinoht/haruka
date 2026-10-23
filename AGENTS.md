# Haruka – fork rules

Haruka is a personal fork of [Mihon](https://github.com/mihonapp/mihon). Everything here exists to keep upstream merges cheap and extensions working. Read this before every change.

## Branches

| Branch | Content |
|---|---|
| `main` | Exact mirror of `mihonapp/mihon` `main`. Never commit to it; the sync workflow fast-forwards it. |
| `dev` | Haruka. Default branch. Upstream arrives through `sync/upstream-*` PRs (merge, never rebase). |
| `feat/*`, `fix/*` | One change each, PR into `dev`. |

## Where code goes

- New code lives in fork modules under `haruka/` or the `haruka.*` package in the app; packages stay `haruka.*` either way:
  - `:haruka:core` (`haruka/core/`): pure logic with no app or upstream module dependencies (chapter dedup and grouping, release versions, titles, swipe geometry) plus the data it bundles (`src/main/assets/haruka/official-scanlators.json`). Its tests live in `haruka/core/src/test`.
  - `:haruka:motion` (`haruka/motion/`): animation profiles and motion drop-ins, depending only on Compose. `presentation-core` re-exports it (`api`), so upstream code in `presentation-core` and the app can call `haruka.motion.*`.
  - `:haruka:sync` (`haruka/sync/`): library sync engine with no app or upstream module dependencies (sync records, codec, merge, base store, remote stores). The wire format is fork-owned; field numbers in `haruka.sync.model` never change meaning, incompatible changes bump `FORMAT_VERSION`.
  - `app/src/main/java/haruka/**`: glue that needs app internals (screens, ViewModels, preferences, DI, adapters from domain models such as `Chapter.toDedupChapter()`).
  - New fork modules are included in the existing FORK block of `settings.gradle.kts`; the app depends on them through `configureHarukaApp`.
- Edits to upstream files are thin hooks wrapped in markers:
  - Kotlin/KTS/SQL: `// FORK -->` … `// FORK <--`
  - XML: `<!-- FORK -->` … `<!-- /FORK -->`
  - Imports are never wrapped: ktlint rejects comments in the import list. Never add imports to upstream files; use fully qualified calls (`haruka.foo.bar(...)`) inside the FORK block.
  - One exception, shadow imports: when every use of a symbol in an upstream file is already hooked, swap that file's import (e.g. `androidx.compose.material3.FilterChip`) for the same-named drop-in in `haruka.motion` and drop the per-call FORK blocks. The `import haruka.…` line is the marker; the drop-in keeps the upstream signature, and its STANDARD path must equal the upstream component. `scripts/haruka/patch-surface.sh` lists these as import hooks.
- Motion hooks: the STANDARD animation profile must behave exactly like upstream; only EXPRESSIVE and REDUCED may differ.
- Build changes go in `haruka.gradle.configureHarukaApp` (`gradle/build-logic/src/main/kotlin/haruka/gradle/HarukaApp.kt`), called from the single FORK block in `app/build.gradle.kts`. It sets the application id, version, `MIHON_VERSION`, fork source dirs and fork module dependencies.
- Version: `app/src/haruka/version.properties` (`versionCode`, `versionName`, `mihonBase`) is the only place to bump it; the build, `scripts/haruka/build-release.sh` and the release workflow read it. Update `mihonBase` on each upstream sync merge.
- Branding and fork resources live in variant overlays under `app/src/haruka/`, never in upstream `res/` folders. Overlays take priority over `src/main` and build-type folders, so they replace upstream resources without touching them:
  - `res/`: every variant (Hoshino theme and colours, font, `ic_mihon`, monochrome icon, splash colour).
  - `res-release/`: release and foss (`app_name`, launcher icon). `res-debug/`: debug and nightly (`app_name`, debug launcher icon). Benchmark builds use `app/src/benchmark/res` instead of the release/debug overlays.
  - Fork assets ship from the fork module that reads them (e.g. `haruka/core/src/main/assets/`); library assets merge into the app at the same path.
- Strings: new strings only in the fork i18n module (`i18n-fork`). Never add or edit strings in `i18n/`; the rebrand `app_name` lives in `app/src/haruka/res-release` and `res-debug`.
- Fork-owned paths outside `haruka.*` packages: `haruka/`, `app/src/haruka/`, `app/src/benchmark/`, `gradle/build-logic/src/main/kotlin/haruka/`, `i18n-fork/`, `scripts/haruka/`, `docs/haruka/`, `.github/workflows/haruka-*`, `.github/assets/haruka-*`, `.github/README.md`, `NOTICE`, this file. `patch-surface.sh` keeps the same list.

## Hard rules

1. `source-api/` and extension loading (`ExtensionLoader`, lib versions, trust) stay byte-identical to upstream. keiyoushi/extensions-source compatibility depends on it.
2. No new `data/**/migrations/*.sqm` and no edits to upstream `.sq` files. Fork state lives in preferences or a fork-owned store.
3. Backups must stay restorable by stock Mihon. Fork protobuf fields, if ever needed, use numbers ≥ 1000.
4. When Mihon ships an equivalent of a fork feature, delete the fork version, migrate its preferences to Mihon's, and keep only still-wanted deltas on top of Mihon's design.
5. Telemetry is off unless `-Pinclude-telemetry` is passed with Haruka's own Firebase config. The updater points at `hoshinoht/haruka`.
6. Haruka's code stays in this repository. Never open pull requests, issues or patches against `mihonapp/mihon` (or other upstreams) with fork code, even for fixes that would shrink the hook surface.

## Checks before a PR

```bash
./gradlew spotlessApply spotlessCheck
./gradlew testDebugUnitTest
./gradlew assembleDebug
scripts/haruka/patch-surface.sh   # upstream files touched outside fork-owned paths
```

Keep the patch surface trending down.

Haruka's project page is `.github/README.md`, which GitHub shows in place of the root `README.md`. The root `README.md` stays byte-identical to upstream.

## Baseline profiles

- `app/src/main/baselineProfiles/*.txt` are upstream's files, unmodified. Haruka's code is covered by wildcard rules in `app/src/haruka/baselineProfiles/haruka-prof.txt`, merged in by `configureHarukaApp`. Measured on a Galaxy S25+ (2026-10-19), upstream's profiles plus the fork rules matched or beat profiles regenerated for Haruka (cold start ~585 ms, jank ≤0.75%), so do not regenerate them.
- If a future measurement regresses, regenerating is possible: temporarily set `useConnectedDevices = true` (and drop `managedDevices`) in `baseline-profile/build.gradle.kts`, run `./gradlew :app:generateBaselineProfile` with a phone attached, and revert that file. It drives `app.haruka`; other installs are untouched.
- `benchmark` builds install as "Haruka Bench" (`app.haruka.benchmark`, aqua icon). They are profileable but not debuggable.

## Performance checks

Run after an upstream sync or a fork change that touches lists, covers or startup, with a real library restored in Haruka Bench:

```bash
./gradlew assembleBenchmark && adb install -r app/build/outputs/apk/benchmark/app-arm64-v8a-benchmark.apk
scripts/haruka/perf/run.sh before <manga-id-with-long-chapter-list>   # cold start + jank on Library, Updates, chapters
scripts/haruka/perf/trace.sh /tmp/scroll.pftrace                       # Perfetto trace of the open screen
```

Compare against the previous run; reference on a Galaxy S25+ with ~950 series: cold start ~590 ms (floored by Mihon's 500 ms splash), jank under 1.5% on all three lists.

## Cleanup at the end of a session

Always clean up when finished for the session or day, after the work is merged and the release (if any) is installed:

- `./gradlew clean` (and `--stop` for the daemons), plus the build output of any extra worktrees.
- Remove worktrees made for lanes or comparisons (`git worktree remove`, then `git worktree prune`) and delete their merged lane branches.
- Delete scratch files outside the repo: APKs copied to `/tmp`, screenshots, pulled databases and backups (they contain the owner's library), throwaway test files.
- Delete test files written to the phone (test backups in Downloads, dummy folders) and put Haruka Dev settings changed for testing back to their defaults.
- Keep `build/haruka-release/` only until its APKs are uploaded or installed; it is gitignored and rebuilt by `scripts/haruka/build-release.sh`.
