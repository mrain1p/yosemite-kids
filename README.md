# Yosemite Kids

A parent-curated YouTube front-end for Google TV and phones, kept for my own
family and a few friends. No accounts, no cloud, nothing to sign up for: a
parent's phone administers the TVs over the home LAN, and an optional Docker
"hub" on a NAS keeps every device in sync while the phone is away.

Based on [Pickwick](https://github.com/itcon-pty-au/pickwick) by itcon-pty-au,
released under the GPL-3.0; modified by mrain1p, 2026. The licence is unchanged
(see `LICENSE`) and this repository is the corresponding source.

## What is in here

- **TV / phone app** (`app/`): the kid-facing player and the parent settings
  screens. Kids see only the channels a parent approved; screen-time limits,
  AI screening of new uploads and the review queue live on the parent's side.
- **Shared rules** (`core/`): the config format, the sectioned merge and the
  sync decision, used verbatim by the app and the hub so there is one
  implementation of the rules, not two.
- **Hub** (`hub/`): a small JVM service in Docker for the NAS. Holds the
  family's config, serves a web copy of the settings pages, and nudges devices
  when something changes. Deploying it: `docs/HUB.md`.

## Installing (family)

1. Sideload `yosemite-kids.apk` from this repository's Releases page on each
   TV and phone (Downloader on the TV, or `adb install -r`).
2. Open it on the TV: a pairing QR appears.
3. Open it on the phone, go to Settings > Devices and scan that QR. The first
   phone to pair becomes the admin; later phones wait for that one to approve
   them.
4. Curate from the phone: channels, kids, limits, AI screening. Everything
   pushes to the TV over the LAN.

The app is sideloaded only; it is not in a store and will not be.

## Developing

- `docs/DEV.md` for the toolchain; `CLAUDE.md` for the working rules.
- `scripts/check.ps1` (Windows) or `scripts/check.sh` runs the whole gate:
  source guards, compile, unit tests. Nothing is done until it passes.
- `gradlew assembleRelease` builds the signed APK. Release builds need the
  real keystore (`YOSEMITE_KIDS_KEYSTORE` and friends in `local.properties`);
  there is deliberately no debug-key fallback.
- `docs/ROADMAP.md` says what is next; `docs/FORK-NOTES.md` what changed and
  why; `docs/ARCHITECTURE.md` where things live.

## When YouTube extraction breaks

YouTube changes and NewPipeExtractor follows within days. Bump
`newpipeextractor` in `gradle/libs.versions.toml`, run the gate, cut a
release. The `extractor-smoke` workflow exercises extraction on a schedule and
reports when it stops working.
