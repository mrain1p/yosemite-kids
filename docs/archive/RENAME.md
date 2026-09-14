# Renaming the fork — every placement

The name is not chosen yet. This is the map of where the current one lives, so
the change is one scripted pass plus a short list of things only a person can
do, rather than a hunt. `scripts/rename.js` does the text pass; run it with
`--dry-run` first — it prints every form it will derive from the name you give
it, and a bad derivation is caught before a byte is written.

**Decisions already made:** the Android package id changes too (a fresh app to
Android — every device uninstalls and reinstalls); git history stays; the
signing key stays.

## The forms a name takes

Given a display name of two words, `First Second`:

| form | example | used for |
| --- | --- | --- |
| display | `First Second` | app label, every visible string, docs |
| slug | `first-second` | repo, Docker image, APK filename, skill dirs |
| package | `firstsecond` | `io.<package>.app`, URL scheme, container unix user |
| identifier | `FirstSecond` | Kotlin identifiers, log tag, Gradle root project |
| identifier prefix | `First` | `FirstIcons`, `FirstScreen`, … (was `Pickwick*`) |
| env prefix | `FIRST_SECOND_` | `..._PORT`, `..._DATA`, `..._KEYSTORE`, … (was `PICKWICK_`) |

A one-word name collapses these sensibly (`Name`, `name`, `name`, `Name`, `Name`, `NAME_`).

## A. What a person sees

| # | placement | where | count |
| --- | --- | --- | --- |
| 1 | App name on the launcher | `app/src/main/res/values/strings.xml` `app_name` | 1 |
| 2 | Every "Pickwick …" string in the UI — version line, TV pairing screen, settings footer, the hub's stored name | `app/`, `core/` | ~250 Title-case mentions |
| 3 | Hub web GUI title and heading ("Pickwick hub") | `hub/src/main/resources/web/index.html` | 2 |
| 4 | Hub's name as stored on every paired device, and the guard that pins it | `PairedDevice.HUB_NAME`; guard in `scripts/check.*` | 1 + 2 |
| 5 | APK filenames (`pickwick.apk`, `pickwick-<version>.apk`) | `app/build.gradle.kts`, `CLAUDE.md`, release skill | 3 |
| 6 | Exported whitelist header, and the three bundled community whitelists | `core/…/Whitelist.kt`, `whitelists/*.txt` | 4 |
| 7 | README title and description, CONTRIBUTING, the `site/` pages, the `worker/` | root, `site/` (8 files), `worker/` (3) | 13 |
| 8 | Docs: `CLAUDE.md`, ARCHITECTURE, HUB, LAN-API, ROADMAP, FORK-NOTES, DEV, SETUP, SCREENS, the PLAN files | `docs/` | 18 files |

## B. Identity — invisible, structural

| # | placement | where | consequence |
| --- | --- | --- | --- |
| 9 | **Android `applicationId`** `io.pickwick.app` | `app/build.gradle.kts` | A new app to Android. **Every device uninstalls and reinstalls.** Full backup → Restore keeps curation. |
| 10 | Kotlin packages `io.pickwick.app`, `io.pickwick.hub` | 174 files; 7 directories move with `git mv` | Upstream cherry-picks no longer apply by path; each becomes a port. |
| 11 | Deep-link scheme `pickwick://pair` | `AndroidManifest.xml` (`android:scheme`), `MainActivity` (`data.scheme`), the QR generator in `Settings.kt` | All three must agree — the script derives one slug for all of them. A phone on the old build cannot scan a TV on the new one. |
| 12 | Kotlin identifiers `PickwickIcons`, `PickwickScreen`, `PickwickDarkColors`, `PickwickLightColors`, `PickwickTypography`, `PickwickDirectory`, `PickwickApp` | ~100 references; `PickwickApp.kt` and `PickwickScreen.kt` are renamed | — |
| 13 | Log tag `"Pickwick"` | 26 call sites | logcat filters change |
| 14 | Gradle `rootProject.name`; the `PICKWICK_*` property/env prefix | `settings.gradle.kts`; 18 files incl. CI, compose, Dockerfile, entrypoint; **`local.properties` keys** (values untouched) | Release builds fail until `local.properties` keys are renamed — the script does it. |

## C. Docker and the NAS

| # | placement | where |
| --- | --- | --- |
| 15 | compose: service `pickwick-hub`, `container_name`, image `ghcr.io/mrain1p/pickwick-hub`, env `PICKWICK_DATA` / `PICKWICK_PORT`, host path `/volume2/Docker/pickwick/data` | `hub/docker-compose.yml` |
| 16 | Dockerfile: unix user `pickwick`, entrypoint path, `ENV` names | `hub/Dockerfile` |
| 17 | entrypoint: env names, the write-probe filename | `hub/docker-entrypoint.sh` |
| 18 | CI image name → a **new** GHCR package appears on the next push; the old one lingers, harmless | `.github/workflows/hub-image.yml` |
| 19 | hub `Main.kt` / `HubServer.kt` env reads and log lines | `hub/src/main/kotlin` |

## D. Tooling

| # | placement | where |
| --- | --- | --- |
| 20 | Guards: every `io/pickwick/app` path, the `"Pickwick hub"` literal | `scripts/check.sh`, `scripts/check.ps1` |
| 21 | Emulator and upstream scripts (package name, paths) | `scripts/emu.ps1`, `scripts/upstream.sh` |
| 22 | Six skill directories `.claude/skills/pickwick-*` and every `/pickwick-…` reference | `.claude/skills/`, `CLAUDE.md` |
| 23 | CI test-class paths `io.pickwick.app.*` | `.github/workflows/build.yml` |

## E. Outside the repo — yours to do

| # | what | how |
| --- | --- | --- |
| 24 | GitHub repo `mrain1p/pickwick` | `gh repo rename <slug>` — GitHub redirects the old URL; `origin` is updated by the procedure below |
| 25 | GHCR package | nothing — the new one is created by CI; delete the old one whenever |
| 26 | NAS | `mv /volume2/Docker/pickwick /volume2/Docker/<slug>`; `docker compose down` the old container name; `up -d --build` the new. **The data folder moves with it — nothing is lost.** |
| 27 | Every device — phone, both TVs | Full backup on the phone first. Uninstall the old app. Install the new APK. Restore. Re-pair TVs (the QR scheme changed). |
| 28 | Scheduled upstream check `~/.claude/scheduled-tasks/pickwick-upstream-check` | rename or leave — it is a local file, not the product |

## F. Must NOT change

- **The signing key.** `~/.pickwick/pickwick-fork-release.keystore`, alias `pickwickfork`. Renaming it buys nothing and it is the sole trust anchor. Only the *property names* that point at it change.
- **Upstream's URLs** — `github.com/itcon-pty-au/pickwick`, `pickwick.tv`. They are theirs.
- **The GPL attribution.** One line in the README must say the app is based on Pickwick by itcon-pty-au, GPL-3.0, modified by you, with a date. §5(a). The script never touches a line containing `based on Pickwick` or `fork of Pickwick`.
- `docs/UPSTREAM-LOG.md`, `docs/.upstream-seen` — a log *of* Pickwick.
- `docs/design/parent-settings/` — third-party design reference for the old name.
- `version.json` — upstream's; replaced when the release repo exists (roadmap §1).
- Emulator AVD names `pickwick_phone`, `pickwick_tv` — local dev machines, not the product.

## The procedure, once the name is chosen

```
node scripts/rename.js "New Name" --dry-run     # read the derivations; nothing written
node scripts/rename.js "New Name"               # text pass
# then the git mv lines the script prints (package dirs, skills, PickwickApp.kt, PickwickScreen.kt)
bash scripts/check.sh                           # guards, compile, every test
git grep -i pickwick                            # residue: should be only section F
```

Then the README attribution line, a version reset (a new package can start at
1.0.0 / versionCode 1), a release APK, the NAS moves in §E, and one last
sideload to every device.

## Executed 2026-09-05

Done with `node scripts/rename.js "Yosemite Kids"` plus the git mv list, and
these extras the text pass could not reach:

- `scripts/Banner.java` and `site/logo.svg` (both extensions added to the script).
- The TV launcher banner is a PNG with the name rendered into it; regenerated
  with `java -Djava.awt.headless=true scripts/Banner.java app/src/main/res docs/store`.
- Package id `io.yosemitekids.app`; versionCode reset to 1, versionName 1.0.0.
- Repository renamed to `mrain1p/yosemite-kids`; `origin` repointed.
- Removed upstream's publishing and community scaffolding, which this fork
  cannot operate: `deploy-pages.yml` and `site/CNAME` (they published to
  pickwick.tv), `FUNDING.yml`, `suggest-channels.yml`, `screen-submission.yml`,
  `CONTRIBUTING.md`. README replaced with a short personal-fork one.
- Left as upstream's, deliberately: `site/` (a static copy of their site; the
  worker's tests read `site/directory`), `worker/`, the directory and suggest
  URLs in `app/build.gradle.kts` (roadmap section 1), the launcher icon art.
- Outside the repo: `local.properties` keys became `YOSEMITE_KIDS_*`; the NAS
  folder moves to `/volume2/Docker/yosemite-kids` to match the compose file.
- The weekly scheduled task stays `~/.claude/scheduled-tasks/pickwick-upstream-check`
  (a standing automation on this machine, not in the repo); the upstream skill
  names it as it is. Its prompt still says "both remotes point at upstream" and
  lists `PickwickScreen` among fork-modified files; update those two lines, or
  rename the task, when convenient.
