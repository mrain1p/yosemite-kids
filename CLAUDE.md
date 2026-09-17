# Yosemite Kids — working notes for Claude

A parent-curated YouTube front-end for Google TV and phones. Kotlin + Jetpack
Compose, Media3/ExoPlayer for playback, NewPipeExtractor for extraction. No
accounts, no cloud: a phone administers a TV over the LAN with a token-gated
HTTP server (`data/Pairing.kt`). Sideloaded only — never shipped to a store.

## This fork — start here

- **What to do next:** `docs/ROADMAP.md` — the only forward-looking doc.
  `FORK-NOTES.md` is a changelog and `docs/archive/` is finished history;
  when they disagree with the roadmap, check the code.
- **Read cheaply:** the `yosemite-kids-map` skill says which file to open for
  each kind of change and which files never to read end to end; `docs/GUARDS.md`
  is the index of every source guard (number, rule, files it reads).
- **Map first:** `docs/ARCHITECTURE.md` (what lives where, data flow, "where
  to change what"), `docs/LAN-API.md` (every LAN route), `docs/HUB.md`
  (deploying the Docker hub, and the volume-permission trap that will
  otherwise cost you an evening), `docs/FORK-NOTES.md`
  (what this fork changed, why, and the backlog), `docs/DEV.md` (toolchain).
- **Check before you claim anything works:** `scripts/check.ps1` (or
  `/yosemite-kids-check`) — compile + offline unit tests + worker tests. Never edit
  Kotlin while Gradle is running.
- **Upstream first, every round:** `/yosemite-kids-upstream` (or
  `scripts/upstream.ps1`) at the start of any round of work, and whenever
  extraction breaks. Cherry-pick what is clean, port what touches fork files,
  log the decision in `docs/UPSTREAM-LOG.md`. The user wants anything
  upstream ships that fits the fork adopted or adapted, not just noted.
- **See it:** the emulator loop in `scripts/emu.ps1` (or `/yosemite-kids-emulator`):
  boot → install → seed → launch → shot. Always `adb -s emulator-5554`; other
  Android devices may be plugged into this PC and must not be touched.
- **Toolchain on this machine:** JDK 17 at `JAVA_HOME`; SDK at
  `%LOCALAPPDATA%\Android\Sdk` (command-line tools only, no Android Studio);
  `local.properties` carries `sdk.dir` and the release-keystore properties,
  so release builds work here. Python is not installed; use PowerShell/bash
  for scripts.
- **Skills:** `.claude/skills/yosemite-kids-{map,check,emulator,lan-api,release,sync,upstream}`.
- Pure logic goes in companions / `internal fun`s so JVM unit tests can reach
  it without a `Context` (see `PairingStore.prunePending`, `Backup.parse`).

## Building and installing — read this before any install

**Always build and install `assembleRelease` on real devices.** This is a
performance requirement, not a preference.

```
gradlew assembleRelease
```

APK lands at `app/build/outputs/apk/release/yosemite-kids.apk` (renamed in Gradle;
keep the asset name constant so `releases/latest/download/yosemite-kids.apk` — the
Downloader-code URL — never goes stale), with a versioned copy beside it,
`yosemite-kids-<versionName>.apk`, which is the one to hand out for sideloading
(the release asset keeps the constant name). Release builds are signed with the
**real release keystore** — `YOSEMITE_KIDS_KEYSTORE` (plus `_PASSWORD`,
`YOSEMITE_KIDS_KEY_ALIAS`, `YOSEMITE_KIDS_KEY_PASSWORD`) in `local.properties` or the
environment. There is deliberately no debug-key fallback: release packaging
fails if the key is absent, because that key is the sole trust anchor for
self-update and Android refuses in-place upgrades across a signature change.

On this machine the keystore lives at
`~/.pickwick/pickwick-fork-release.keystore` (alias `pickwickfork`, password
alongside in `pickwick-fork-release.keystore.password.txt`; both paths are in
`local.properties`). **Back both up off-machine** —
losing them means every installed family must uninstall, which wipes their
curation.

A debug build is *debuggable*, which enables `-Xcheck:jni` and skips
ahead-of-time compilation, leaving the Compose runtime interpreted on first
launch. Measured cold start to first frame on a Chromecast with Google TV:

| Build | Cold start |
| --- | --- |
| `assembleDebug` | 10.2 s |
| `assembleRelease` | ~1.9 s |
| `assembleRelease` + forced AOT | ~1.5 s |

Reach for `assembleDebug` only when something genuinely needs a debuggable
process — `run-as` to read `/data/data/...`, breakpoints, a debugger. Never
leave a debug build installed on a device someone actually uses; reinstall the
release build afterwards.

After installing, optionally AOT-compile to recover the last ~0.4 s. It is a
device-local setting that does **not** survive a reinstall, so re-run it every
time:

```
adb shell cmd package compile -m speed -f io.yosemitekids.app
```

If a change is supposed to make startup faster or slower, measure it rather than
asserting it. `-S` forces a genuine cold start; without it you will silently
measure a warm launch and get `TotalTime: 0`:

```
adb shell am start -S -W -n io.yosemitekids.app/.ui.MainActivity
```

These numbers are inherited from upstream's hardware and have never been
re-taken on this fork's television; §9F is where that happens. Take three
samples and read `TotalTime` (milliseconds to first frame). Watch
logcat for `Choreographer: Skipped N frames` and `Displayed ... +Xs` too.

## Releasing (self-update)

The app polls `version.json` at the repo root of `main` (URL is baked into
`BuildConfig.UPDATE_MANIFEST_URL`).

**Use `/yosemite-kids-release`**, not this outline: the skill carries the two
steps whose omission has already shipped a broken release — `hubVersion` in
`hub/build.gradle.kts` moves with the app's version (guard 39 fails the gate
otherwise, and 1.2.0 shipped a hub advertising 1.1.0), and the gate is run
*after* the bump rather than before it.

1. Upstream first (`/yosemite-kids-upstream`), then bump **all three**:
   `versionCode` and `versionName` in `app/build.gradle.kts`, and `hubVersion`
   in `hub/build.gradle.kts`. A device only offers an update when `versionCode`
   is strictly higher than the installed one — forgetting it ships nothing.
2. Gate (read the output for `all green`; the exit code is not the check),
   commit, fast-forward `main`, push, and let CI publish the hub image.
3. `gradlew assembleRelease`, attach the APK to a GitHub Release tagged
   `vX.Y.Z` **with `-R mrain1p/yosemite-kids`** — without it the command
   resolves to upstream and fails with a misleading 403 — then point
   `version.json` at that asset.

Always publish the release APK. Self-updating a family's TV onto a debug build
would hand them the 10-second cold start.

## Devices and adb

- `adb` is usually **not on PATH**; the SDK location is in `local.properties`
  (`platform-tools/adb.exe` under it).
- The Google TV connects via **wireless debugging over mDNS**, not `adb connect
  <ip>:5555` — that port is refused. It appears in `adb devices -l` on its own
  once Developer options → debugging is enabled on the TV. That toggle often
  resets after a reboot or system update.
- Transport ids from `adb devices -l` change between sessions. Re-read them; do
  not hardcode — and re-read the models too, which have changed once already.
- `install -r` preserves app data, so pairing and curation survive an upgrade.
  A signature mismatch means the installed build was signed with a different
  key (e.g. an old debug-keystore install from before the release key existed);
  that needs an uninstall, which **wipes the family's config**.

## Verifying pairing without breaking it

The TV's LAN server binds the first free port in 8765..8775. Check it is alive
with an unauthenticated read, and check an approved token with `/status`:

```
GET /pair-status?me=<32 hex>     -> {"status":"unknown"}
GET /status   (header X-Token: <approved token>)  -> {"hash":...,"updatedAt":...}
```

**Never send `POST /pair-request` while testing.** When the TV has no approved
phones yet and its pairing QR is on screen, the first requester is auto-approved
as the admin — a stray test request would seize that slot and force the real
phone into `pending`, where only an approved phone could rescue it.

That bootstrap is gated on `PairingWindow`, which the QR screen holds open and
which lapses ~15 s after that screen goes away; off-window requests get
`{"status":"closed"}`. `/pair-request` also refuses anything carrying an
`Origin` header or a non-JSON content type, so a page in a browser on the LAN
can't take the slot with a no-preflight cross-site POST.

## A change that creates a rule ships the thing that enforces it

Every *never*, *always*, *must* or *only ever* in a comment or a commit
message is an invariant, and prose enforces nothing. Before calling a change
done, ask what is now true that was not before, and what would catch it
becoming false — then build that, in the same commit. A refactor that passes
its tests feels finished, but the tests were written for the old shape; the
new shape's rules are precisely the ones with no coverage. In order of
preference:

1. **A test**, when the property is about values, placed where it has to
   hold (a merge test in `:app` proves nothing about the hub).
2. **A source guard** in step 0 of `scripts/check.ps1` *and* `scripts/check.sh`
   (mirrored; guard 10 checks), when the property is about code shape. Every
   guard is one row in `docs/GUARDS.md` (generated, guard 67) and has a case
   in `scripts/guard-canary.sh` that proves it can fail (guard 65).
3. **A skill**, when the property is a judgement a future session has to
   make rather than a check a script can run.

A mistake made twice is the signal to build a check, not to try harder.

## Three faces, one product

Phone, television and the browser the hub serves are one product, and a
child moves between them in an afternoon. Before building anything a child
sees, ask which faces draw it and what they read it from — **at design time,
not at review time**. Anything two faces both draw or both decide belongs in
`:core` or `:crawl` before the second face is written:

- **`KidSurface`** declares every screen, shelf and dialog, which faces draw
  it, `webReady` where the browser does not yet, and `onTv`/`tvWhy` for how
  the television adapts it or why it skips it (guard 62; the gate prints
  `kid surfaces still to reach the browser: …` and what the TV skips on every
  run).
- **`SettingsSurface.honouredBy`** says which kid faces *obey* a parent's
  setting, as `where` says who can set it; a setting the browser honours must
  be read by the hub's kid routes (guard 69), and the gate prints what the
  browser does not honour yet.
- **`DesignTokens`**, `KidGeometry` and `KidType` hold every colour and
  number a card is drawn from; the browser's stylesheet is generated from
  them (guards 48, 63).
- **`SettingsSurface`** does the same for the parent console.

A difference between faces is a decision and goes in the manifest's `why`
with the reason; a difference with no reason recorded is indistinguishable
from an omission. The television keeps its QR-only settings screen and its
rail: consistent and adapted, never forked.

## Conventions

- Comments explain constraints and *why*, not what the line does. Match the
  density already in the file; several non-obvious decisions are documented
  in-place and are worth preserving.
- Kid-facing colour lives in `:core`'s `DesignTokens`, which the browser's
  stylesheet is generated from and which guard 48 holds `:app` to; `ui/Theme.kt`
  binds those and owns the parent-facing palette. Watched/played progress is
  `WatchedProgressRed` (YouTube convention) — deliberately not the brand teal.
- `LaunchedEffect` and composable bodies run on the **main thread**. Disk I/O,
  SharedPreferences and JSON parsing must go through
  `withContext(Dispatchers.IO)`; `ConfigStore`/`PairingStore` calls are all
  synchronous. Long operations need visible progress, not a frozen dialog.
- `gradlew test` runs the JVM unit tests.
- The AI API key is **not** in `config.json`. It lives in `SecretStore`
  (Keystore-encrypted, unlisted in the backup rules so it never reaches cloud
  backup) and is overlaid onto `AiConfig` by `ConfigStore.load()`. It still
  travels to paired devices in the pushed payload — they need it to screen —
  and `saveRaw` strips it before the copy hits disk. Keep it out of the backup
  include lists, and keep `stripSecrets` surgical so unknown fields from newer
  builds survive the round trip.
- The `LanServer` faces the whole LAN before any token is checked, so every
  read there is bounded (line, header count, body, worker threads). Anything
  new that allocates from request data needs the same treatment.
