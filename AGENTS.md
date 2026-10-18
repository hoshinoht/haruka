# Haruka – fork rules

Haruka is a personal fork of [Mihon](https://github.com/mihonapp/mihon). Everything here exists to keep upstream merges cheap and extensions working. Read this before every change.

## Branches

| Branch | Content |
|---|---|
| `main` | Exact mirror of `mihonapp/mihon` `main`. Never commit to it; the sync workflow fast-forwards it. |
| `develop` | Haruka. Default branch. Upstream arrives through `sync/upstream-*` PRs (merge, never rebase). |
| `feat/*`, `fix/*` | One change each, PR into `develop`. |

## Where code goes

- New code lives under the `haruka.*` package (`app/src/main/java/haruka/**`) or in new fork modules.
- Edits to upstream files are thin hooks wrapped in markers:
  - Kotlin/KTS/SQL: `// FORK -->` … `// FORK <--`
  - XML: `<!-- FORK -->` … `<!-- /FORK -->`
  - Imports are never wrapped: ktlint rejects comments in the import list. Prefer fully qualified calls for one-off hooks.
- Strings: new strings only in the fork i18n module (`i18n-fork`, created with the first feature that needs strings). Never add or edit strings in `i18n/` except the rebrand `app_name`.

## Hard rules

1. `source-api/` and extension loading (`ExtensionLoader`, lib versions, trust) stay byte-identical to upstream. keiyoushi/extensions-source compatibility depends on it.
2. No new `data/**/migrations/*.sqm` and no edits to upstream `.sq` files. Fork state lives in preferences or a fork-owned store.
3. Backups must stay restorable by stock Mihon. Fork protobuf fields, if ever needed, use numbers ≥ 1000.
4. When Mihon ships an equivalent of a fork feature, delete the fork version, migrate its preferences to Mihon's, and keep only still-wanted deltas on top of Mihon's design.
5. Telemetry is off unless `-Pinclude-telemetry` is passed with Haruka's own Firebase config. The updater points at `hoshinoht/haruka`.

## Checks before a PR

```bash
./gradlew spotlessApply spotlessCheck
./gradlew testDebugUnitTest
./gradlew assembleDebug
scripts/haruka/patch-surface.sh   # upstream files touched outside fork-owned paths
```

Keep the patch surface trending down.
