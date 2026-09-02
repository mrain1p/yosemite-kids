---
name: pickwick-upstream
description: Check what upstream itcon-pty-au/pickwick has shipped since this fork branched, flag commits that touch fork-modified files, and recommend cherry-pick / port / skip per commit. Use when asked to sync with upstream, check for upstream changes, or when YouTube extraction breaks (upstream's extractor bump is the fix to take first).
---

# Tracking upstream

The fork branched from upstream `7ce27f9` (v0.7.8). Upstream keeps shipping
extractor bumps, fixes and features; this skill keeps the fork informed
without ever merging on its own.

## Run

```bash
scripts/upstream.sh            # or .\scripts\upstream.ps1 on Windows
```

It fetches `upstream/main`, lists commits not yet logged, and appends a dated
table to `docs/UPSTREAM-LOG.md`: commit, subject, file count, and whether the
commit touches files the fork has changed (`⚠️` + the overlapping paths).
It also records upstream's `version.json` and `newpipeextractor` version.
`docs/.upstream-seen` remembers the last logged commit.

## Then review, commit by commit

For each row fill the **Action** column with one of:

- **cherry-pick** — no fork files touched: `git cherry-pick <sha>`, then
  `scripts/check.sh`. Typical: extractor bumps (`gradle/libs.versions.toml`),
  worker fixes, site/directory data, tests.
- **port by hand** — touches a fork-modified file (PlayerActivity, HomeScreens,
  PickwickScreen, VideoGrid, Tiles, MainViewModel, Pairing, ConfigStore…).
  Read the upstream diff (`git show <sha>`), re-apply the intent in the fork's
  structure, note it in `docs/FORK-NOTES.md`.
- **skip** — superseded by the fork (e.g. upstream tweaks to the stock Media3
  controller the fork removed) or a release chore (`Point version.json at …`).

Priorities: an extractor bump goes first (playback for every family depends
on it); security/LAN fixes second; everything else when convenient.

## Rules

- Never `git merge upstream/main` — the fork rewrote whole files and a merge
  would be a wall of conflicts. Cherry-pick or port.
- After any pick: `scripts/check.sh`, then the emulator loop for anything
  kid-facing.
- If upstream changed `version.json`'s `versionCode` past the fork's, bump
  the fork's `versionCode` above it so self-update ordering stays sane.
