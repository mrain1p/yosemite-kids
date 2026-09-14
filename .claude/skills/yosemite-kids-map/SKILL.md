---
name: yosemite-kids-map
description: Where to start reading in the Yosemite Kids repo for a given kind of change, and how to read it cheaply. Use at the start of any task before opening large files, when asked "where does X live", or when a change touches more than one of the three faces (phone, TV, browser).
---

# Reading the repo without reading all of it

The repo is ~83k lines, and five files carry a third of it. Open a file
only after this page has said which one, and open a slice, not the whole.

## The three faces, four modules

| Module | What | Rule |
| --- | --- | --- |
| `:core` | The config model, the merge, the manifests, the design tokens. No Android, no disk, no clock. | Anything two faces both draw or both decide goes here first. |
| `:crawl` | Network, the YouTube extractor, the search index, the visibility predicate. Plain JVM. | One crawler, one "may this child see this". |
| `:app` | The Android app: phone and TV are one APK, `FormFactor` decides. | TV keeps its QR-only settings and its left rail. Kid-facing things adapt, they do not fork. |
| `:hub` | The Docker container: config store, routes, the parent console and the kid page. | A parent's console and a child's page: never let one reach the other's credential. |

## Start here, by kind of change

| Change | Open first | Then |
| --- | --- | --- |
| A parent setting | `core/.../data/SettingsSurface.kt` (the manifest: id, label, kind, which face) | `app/.../ui/Settings*.kt` for the phone form, `hub/.../HubWeb.kt` + `index.html` for the console |
| A kid-facing screen or shelf | `core/.../ui/KidSurface.kt` for the declaration, `core/.../ui/HomeSections.kt` for the home rows | `app/.../ui/HomeShelves.kt` (Android) and `hub/.../HubKidHome.kt` + `kid.html` (browser) |
| A colour, size or word a child sees | `core/.../ui/DesignTokens.kt`, `KidWords.kt` | Never a literal in `:app` or `kid.html` (guards 36, 48, 61, 63) |
| A config field | `.claude/skills/yosemite-kids-sync` §4, then `core/.../data/Whitelist.kt`, `ConfigJson.kt`, `ConfigStamp.kt`, `ConfigMerge.kt` | The four canonical tests in `core/src/test` |
| A LAN or hub route | `docs/LAN-API.md` (the contract) | `app/.../data/Pairing.kt` `LanServer.handle`, `hub/.../HubServer.kt`; guards 14, 22, 29, 30, 41 |
| Screen time | `docs/ARCHITECTURE.md` "Screen time in one table" | `app/.../data/SessionGuard.kt`, `core/.../data/UsageLedger.kt` |
| The player | `app/.../ui/PlayerActivity.kt` (4,300 lines: grep for the function, read that) | `hub/.../HubStream.kt` + the player half of `kid.html` for the browser |
| A guard | `docs/GUARDS.md` (the index: number, rule, files it reads) | `scripts/check.sh` at `# <n>.` for the paragraph; mirror it in `check.ps1`; a case in `scripts/guard-canary.sh` |
| Docs | `docs/ROADMAP.md` is the only forward-looking doc; `docs/ARCHITECTURE.md` the map; `docs/archive/` is finished history and is never updated | |

## Reading cheaply

- The five largest files are `PlayerActivity.kt`, `Settings.kt`, `index.html`, `MainViewModel.kt` and `Pairing.kt`. Grep for the symbol first and read the enclosing function with an offset and limit; do not read any of them end to end for one question.
- `index.html` is 91% inline script and `kid.html` two thirds. Grep for the `data-control`, the route string or the function name.
- `docs/ARCHITECTURE.md` has a "Where to change what" table near the end. Read that section, not the file.
- `docs/FORK-NOTES.md` is a 114 KB changelog. Grep it for a version or a word; never read it whole.
- `docs/GUARDS.md` answers "which guard will this trip" before the gate does.

## Before claiming anything works

`scripts/check.ps1 -Guards` runs the source guards in under a minute on
Windows (`check.sh --guards` is five minutes there, seconds on Linux/CI).
The full gate is `scripts/check.ps1`. Anything a child sees is verified on
the emulator (`yosemite-kids-emulator`) or on a real device before it is done.
