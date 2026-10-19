# Library sync

Haruka can keep your library the same on every device signed in to the same Google account. Open **Settings › Data and storage › Google Drive**.

## Where your data goes

Haruka stores sync files in a hidden application folder in **your** Google Drive. It asks Google for one permission, `drive.appdata`, which lets it see only that folder; it cannot see any other Drive file. There is no Haruka server. See [PRIVACY.md](PRIVACY.md).

Sync needs Google Play services on the device. Debug builds (`app.haruka.dev`) sync to their own separate files and never touch the release build's library.

## What syncs

| Synced | Not synced |
|---|---|
| Library entries (added, removed) | Downloaded chapters and covers |
| Categories, including renames and order | Extensions, extension repos and source settings |
| Read, bookmark and last page for every chapter | Mihon app settings |
| Reading history | Tracker logins (links sync, passwords don't) |
| Tracker links and progress | |
| Notes, reader settings per series, excluded scanlators | |
| Duplicate-chapter rules, overrides and scanlator ranking | |
| Haruka settings such as the animation profile | |

## How changes combine

Each device remembers the library as it was at its last sync and compares both sides with it, so only real changes count.

- **Read and bookmarks:** a change on either device wins. Reading a chapter on one device and bookmarking it on another keeps both.
- **Last page and reading time:** the furthest page and the longer time win.
- **History:** a re-read beats a cleared history.
- **Settings-like values** (notes, reader settings, tracker score, Haruka settings): when two devices changed the same value, the device that syncs wins.
- **Categories:** matched by identity, not name, so a rename is never a new category. If two devices create categories with the same name, they become one.
- **Removals:** removing an entry from the library syncs. Haruka remembers removals for 180 days so an old device can't bring them back.

## Safety

- Removing more than 25 entries, or 10% of the library, in one sync waits for you on the Sync screen: **Remove everywhere** or **Keep everywhere**.
- Sync never deletes chapters. New entries from another device come in without filling Updates or starting downloads.
- After you restore a backup, the next sync combines the restored library with your other devices instead of treating anything as deleted.
- If the Drive copy was wiped, Haruka combines instead of deleting. If it looks older than this device's last sync, Haruka stops and asks before writing; **Upload this library** starts a fresh copy from this device.

## When it syncs

- About 45 seconds after you finish a chapter, when you open Haruka, and when you leave it after reading.
- On the schedule you pick (off, 1, 3, 6, 12 or 24 hours), optionally only on Wi-Fi or while charging.
- **Sync now** on the Sync screen, at any time.

Progress shows in the in-app banner. If sync needs you (sign in again, enter the passphrase, approve removals) a notification opens the Sync screen.

## Encryption (optional)

Set a passphrase to encrypt everything Haruka stores in Drive. Other devices need the same passphrase once. Changing or turning off encryption rewrites the Drive copy.

> [!WARNING]
> A lost passphrase cannot be recovered. You can still turn encryption off from a device that knows it, or start over with **Upload this library**.

## Turning it off

**Sign out** on the Sync screen stops syncing on that device. To delete the Drive copy, open Google Drive on the web, go to **Settings › Manage apps › Haruka**, and choose **Delete hidden app data**. To revoke access entirely, use <https://myaccount.google.com/permissions>.
