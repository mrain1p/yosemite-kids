# Pickwick fork — what to do next

**This is the only forward-looking document.** `FORK-NOTES.md` is a changelog:
it records what happened. The `PLAN-*.md` files are finished history from single
rounds. When those disagree with this file, this file is wrong and should be
fixed — but check the code before believing any of them.

Last audited **2026-09-04** against commit `170b7e3` (0.12.3-fork), by reading
the code rather than the docs. That audit found 12 doc claims that were simply
untrue, including one that had been stale since the round that closed it.

> **Verification status.** The audit's adversarial re-check pass was cut short by
> a rate limit, so most findings below are single-source. Of the ten claims that
> *were* re-checked, three were overturned. Treat effort estimates and "already
> done" verdicts as unconfirmed, and re-read the cited code before acting on one.

---

## 0. Before anything else: the work exists in one place

Not a feature. This is the only item here that is about losing everything.

- **58 fork commits live only on this disk.** `git remote -v` returns two
  remotes and both are upstream's URL (`itcon-pty-au/pickwick`). `git branch -vv`
  reports `main [origin/main: ahead 58, behind 4]`. There is no fork repository
  anywhere. Every round of work in this file's history is one disk failure from
  gone.
- **The release keystore is the sole trust anchor**, and `CLAUDE.md:54-56` names
  a file that does not exist (`pickwick-release.keystore`; the real one is
  `~/.pickwick/pickwick-fork-release.keystore`, alias `pickwickfork`). Those
  lines were inherited verbatim from upstream and describe upstream's machine.
  Anyone following the "back both up off-machine" instruction literally backs up
  nothing. Losing that key means **every installed family must uninstall**, which
  wipes their curation.

**Do:** create the fork repo, push, and back up the keystore off-machine. Fix
`CLAUDE.md:28` and `:54-56` in the same pass — a session reading line 28 today
concludes it cannot cut a release at all.

---

## 1. The release chain

These are ordered because each depends on the one before it. Nothing here works
in isolation, which is why the pre-release checklist in `FORK-NOTES.md` reads as
four independent one-line edits and is not.

1. **A fork repo exists** (see §0). Everything else needs somewhere to publish.
2. **Repoint the outbound URLs.** `PICKWICK_UPDATE_URL`, `PICKWICK_DIRECTORY_URL`,
   `PICKWICK_SUGGEST_URL` in `app/build.gradle.kts`. `SUGGEST_WORKER_URL` is an
   outbound **POST** to upstream's worker — worth knowing before a family runs it.
3. **`version.json` is the last step, not the first.** It still reads
   `versionCode 28 / 0.7.8` and points at upstream's release asset — untouched
   since the branch point. It must name an `apkUrl` that resolves, so it comes
   after the release exists.
4. **Tag and publish**, then check `install -r` across the version boundary.

**Two things the checklist does not say:**

- **Self-update is off in every build.** `UPDATE_MANIFEST_URL` bakes in as `""`
  (`Updater.kt:36-37` bails on blank). Good news — families cannot be pushed onto
  upstream builds. Bad news — **the entire upstream-tracking routine has zero
  payoff today**, because an adopted extractor bump cannot reach anyone.
- **The fork shares upstream's `applicationId` with a different signing key.** Any
  family on an upstream install must uninstall — wiping curation, pairing and
  history — and cannot go back. Large, structural, no cheap fix; decide it
  deliberately rather than discovering it during a release.

**Also unproven: this fork has never run on a Google TV or a real phone.** Every
"verified" note in `FORK-NOTES.md` is an emulator run, and the Chromecast
cold-start table in `CLAUDE.md:34-40` is upstream's measurement on upstream's
hardware, inherited by the fork. If a device run happened and went unrecorded,
record it — right now the standing claim is the honest one.

---

## 2. Highest value for a family, ranked

**A. A device is not reachable while Pickwick is closed.** `LanServer` is built
in `MainActivity` and dies with the process. A sleeping TV reads as unreachable
on the parent's phone, "Play on TV" cannot wake it, and the hub's nudge does not
land. Both layers underneath are built (`POST /sync-now` for awake devices,
`ConfigSyncWorker`'s 15-minute floor for sleeping ones) — this is the middle.
Needs a foreground service: `dataSync` is the honest type and is already declared
for downloads. Gate on form factor; a persistent notification is defensible on a
mains-powered TV and a real cost on a phone. *Medium.*

**B. Background content warm.** From `OPEN-QUESTIONS.md` §4, tracked nowhere else
and the highest kid-facing value found in the audit: the difference between a TV
showing last week's videos and one showing this week's. Needs no hub. *Medium.*

**C. The API key can reach cloud backup.** Stated three times in prose, enforced
nowhere. The failure is a real credential with a balance riding to Google's cloud
backup because someone added a plausible line to a backup rule without reading
the comment. This project's own rule says a "never" in a comment owes a guard.
**Five lines in both check scripts.** *Small — do this one now.*

**D. mDNS/NSD advertisement.** A new router or a DHCP reshuffle is the single most
likely way a working install breaks for a non-technical parent, and today's answer
is a subnet sweep that cannot see a /16 or an IPv6 segment. NSD on the device plus
a listener on the phone; the sweep stays as fallback. *Medium.*

**E. Kid → parent requests.** The only item that changes what a *kid* can do
rather than how reliably things happen — today a kid who wants a channel has to
physically find a parent. Needs a LAN route, a durable queue surviving both
devices sleeping, and a notification channel. *Large.*

**F. Grants are fire-and-forget.** A sleeping TV misses bonus minutes entirely.
The receipt is at least honest now ("Granted N min here + K device(s)", so K=0 is
visible), but the fix — carry bonus minutes in the config — now rides merge
machinery that exists. Drop "pause" from the old wording; that half is done.
*Medium.*

**G. Version, last sync and role on every device row.** Smaller than it was: the
hub already records `host`/`port`/`lastSeenAt` on every authenticated call and
`HubWeb` simply does not render them, and `Device.address` has no caller at all.
On the phone, `/status` serves `versionCode` and `versionName` but `DeviceStatus`
parses only the name and `DeviceSync.Reachable` carries neither. Answers the first
question anyone asks about a sideloaded fleet. *Small.*

---

## 3. Known-wrong docs

Cheap, and they actively mislead:

- **`HUB.md` tells parents their TVs only sync while a kid is watching.** The
  background worker and the nudge both made that false.
- **`ARCHITECTURE.md` describes a one-module app.** `:core` and `:hub` are
  invisible in it and the sync section predates `ConfigSync`.
- **`LAN-API.md`** is missing two live routes and has stale response shapes.
- **The `stats` entry in `SettingsSurface`** blames "the hub never initiates" —
  which stopped being true in `e5239f3`. See §4.
- **`HubWeb.pages` claims to be "derived from SettingsSurface"** and is a
  hardcoded literal list.

---

## 4. Stats on the hub — decide before building

The last `Where.BOTH` group not on the hub. Its recorded reasoning is now stale:
the hub *does* initiate. But a genuinely new constraint replaced it — **guard 7
fails the build on any `openConnection`/`HttpClient`/`Socket(` in `hub/src`
outside `HubNudge.kt`**, written deliberately to mean "the hub announces; it does
not command."

So the two options are no longer equivalent:

- **Devices push a stats digest** on their existing sync — leaves the guard
  intact, consistent with the direction-of-trust argument.
- **The hub polls devices** — requires amending a guard that exists to forbid
  exactly that shape.

The push is the smaller change and the one that fits. Fix the stale `why` text
either way.

---

## 5. Long tail

`FORK-NOTES.md` holds the full backlog. Notable corrections from the audit:

- **Multi-admin conflicts** are no longer newest-wins wholesale — the sectioned
  merge closed it. The backlog entry is stale.
- **Skeleton tiles, sleep timer, kid-scale search** were each marked "not done"
  and are partly built; a verifier overturned all three, so re-read before
  trusting either verdict.
- **`PLAN-hub.md` has live commitments nobody tracks** — notably moving the crawl
  into the container and retiring master election, which that plan calls its
  highest day-to-day value item and which is the real fix for "new videos don't
  show up". Same for three items in `OPEN-QUESTIONS.md`.
- **`PLAN-round3/4/5.md` are finished history** and can be archived.

---

## 6. The gate

- **CI runs neither `check.sh` nor `check.ps1`** — roughly 25 source guards are
  enforced only when someone runs them by hand. `check.sh` was additionally dead
  from line 78 onward until 2026-09-04.
- **The upstream "touches fork files" flag went blind to 8 files** when `:core`
  was extracted — it still runs and still prints, covering less than it says. The
  weekly scheduled agent is told "no overlap means cherry-pick", and
  `Whitelist.kt` is among the files it can no longer see.
- **The extractor watch mirrors upstream's pin only.** Extraction breaks because
  YouTube changes and the fix ships from **TeamNewPipe** — watching upstream makes
  the fork's playback freshness depend on a middleman, which is the one dependency
  the fork exists to escape. Both are pinned at v0.26.4 today; nothing outstanding.

---

## Anchors

Each row names code an item above depends on. **`scripts/check.*` fails if one
stops resolving** — because that is the signal an item was quietly finished, which
is exactly how this document went stale twice before it existed. When a guard
fires: confirm the work is done, then delete the item and its row.

| Item | Anchor | Kind |
| --- | --- | --- |
| §2A reachability | `LanServerHolder.server = LanServer(` | code |
| §2C key in backup | `app/src/main/res/xml/backup_rules.xml` | path |
| §2G device rows | `val address: String?` | code |
| §4 stats on hub | `outstandingOnHub` | code |
| §4 guard 7 | `hub/src/main/kotlin/io/pickwick/hub/HubNudge.kt` | path |
| §1 update off | `UPDATE_MANIFEST_URL` | code |
| §1 version.json | `version.json` | path |
