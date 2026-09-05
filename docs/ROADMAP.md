# Yosemite Kids fork — what to do next

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

- **The fork has a repository** — `github.com/mrain1p/yosemite-kids`, private,
  since 2026-09-04; `origin` points there and CI runs the guards, the tests and
  the hub image on every push. Before that, 58 fork commits lived only on this
  disk.
- **The release keystore is the sole trust anchor** and is still backed up
  nowhere but this disk (`~/.pickwick/pickwick-fork-release.keystore`, alias
  `pickwickfork`; `CLAUDE.md` now names the right file). Losing that key means
  **every installed family must uninstall**, which wipes their curation.

**Do:** back up the keystore and its password file off-machine — a password
manager attachment or an encrypted drive, never the repo.

---

## 1. The release chain

These are ordered because each depends on the one before it. Nothing here works
in isolation, which is why the pre-release checklist in `FORK-NOTES.md` reads as
four independent one-line edits and is not.

Done 2026-09-05, in this order: the repo went public; 1.0.3 was built with
`YOSEMITE_KIDS_UPDATE_URL` pointing at this repo's raw `version.json`;
`gh release create v1.0.3` published `yosemite-kids.apk` under the constant
asset name; `version.json` was written last, once the asset it names resolved.
From 1.0.3 on, "Check for updates" in the app delivers every later build, and
the upstream-tracking routine finally has a payoff: an adopted extractor bump
reaches every TV. `YOSEMITE_KIDS_DIRECTORY_URL` and `YOSEMITE_KIDS_SUGGEST_URL`
still point at upstream's community directory on purpose. Each release is
the release skill: bump both version numbers, gate, build, `gh release
create`, then `version.json`.

**One thing the checklist did not say:**
- **The package id changed with the name** (`io.yosemitekids.app`, versionCode
  reset to 1 in 1.0.0). To Android that is a different app: an upstream install
  keeps working beside it and nothing has to be uninstalled — but nothing
  migrates either. A family moving over takes a full backup in the old app,
  restores it in the new one, re-pairs the TVs (the pairing scheme is now
  `yosemitekids://`), then removes the old app so two LAN servers do not
  answer on the same TV.

**Also unproven: this fork has never run on a Google TV or a real phone.** Every
"verified" note in `FORK-NOTES.md` is an emulator run, and the Chromecast
cold-start table in `CLAUDE.md:34-40` is upstream's measurement on upstream's
hardware, inherited by the fork. If a device run happened and went unrecorded,
record it — right now the standing claim is the honest one.

---

## 2. Highest value for a family, ranked

**A. A device is not reachable while Yosemite Kids is closed.** `LanServer` is built
in `MainActivity` and dies with the process. A sleeping TV reads as unreachable
on the parent's phone, "Play on TV" cannot wake it, and the hub's nudge does not
land. Both layers underneath are built (`POST /sync-now` for awake devices,
`ConfigSyncWorker`'s 15-minute floor for sleeping ones) — this is the middle.
Needs a foreground service: `dataSync` is the honest type and is already declared
for downloads. Gate on form factor; a persistent notification is defensible on a
mains-powered TV and a real cost on a phone. *Medium.*

**B. Background content warm — done.** `ContentWarmWorker` refreshes the stalest
twelve channels every six hours on unmetered power, so a TV switched on after a
week is already current instead of showing last week's uploads until someone
leaves it sitting on Home. It deliberately does **not** screen: handing new
videos to the AI unattended spends a parent's API balance on a timer, and the
failure mode of a bug there is a bill. Background screening is the follow-on if
held videos on first open turn out to annoy.

**C. The API key can reach cloud backup — done.** Guard 9 covers both the
encrypted store and the unencrypted Keystore-failure fallback, enforces the
"keep the two files in lockstep" rule those XMLs ask for, and fails if
`SecretStore` is renamed out from under it. It had been stated three times in
prose and enforced nowhere — one plausible-looking line added by someone who
never read the comment would have sent a live credential with a balance to
Google's cloud backup.

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
question anyone asks about a sideloaded fleet. *Small.* The rows show the
version now; what is left is **starting the update from the phone**: a
`POST /check-updates` route that makes a TV fetch `version.json`, download
the APK and show its installer prompt, which the parent confirms with the
remote. Nothing can press that button over the LAN, but bringing the prompt
up saves a walk through the TV's settings. Works from the first build that
carries the route on the TV. *Small-medium.*

**H. The crawl in the container; retire master election.** Decided
2026-09-05, next after the sync bug below. Today the search index is built
only by whichever device holds `masterDeviceToken`, and that crawl advances
only while that phone has the app open; the hub, the one machine in the
house that is never in a pocket or asleep, holds settings and nothing else.
`PLAN-hub.md` called this its highest day-to-day-value item and nothing
tracked it. It also dissolves a migration hazard found today: a master token
that no live device holds (the old package's phone, after the rename) is
never vacated, so nobody ever crawls again. Pieces: NewPipeExtractor in
`:hub` (plain Java, runs in the JVM container as is); guard 7 rewritten from
"no outbound connection" to "YouTube and the devices' `/sync-now`, nothing
else", so the boundary stays enforced; the hub serving per-channel index files
in the format devices already exchange over `/index`, devices pulling from the
hub instead of the master; then, as a separate decision, screening in the
container, which means the API key living on the NAS. *Medium-large.*

The fallback rule, decided 2026-09-05: a hub that is paired and answering
holds the master slot, and the phone's crawl worker already goes quiet when
the token is not its own. A master that has not answered in a day is
vacant, so with no hub, or a hub that is off for a while, a parent phone
claims it through the existing election; when the hub returns it reclaims,
which needs one line in the merge's master rule ("a hub outranks a phone")
in place of today's tie-break by token order. The same vacancy rule cures
the dead-master case the rename exposed.

**I. The Settings form re-stamps what it did not change.** Found with the
convergence bug (fixed 2026-09-05, see `FORK-NOTES.md`) and left open: a
save carries units the merge brought in from disk into `baseline` without
stamping them while the form's own lists are never refreshed, so the *next*
tap reads them as deletions and mints tombstones nobody asked for, and
`section3` puts the disk's `ai` into the baseline while the form keeps its
own, so every tap re-mints the AI unit ("changed screening" in the feed
with nobody touching screening). A co-parent's channel that lands under an
open form is deleted by this phone's second tap. Fix: after
`baseline = saved?.config`, fold what the stamper carried into the form's
list state, and re-read `configStore.syncHash()` inside the Push coroutine
instead of the composition-time value. Needs the emulator loop. *Medium.*

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
- **`PLAN-hub.md` has live commitments nobody tracks** — the crawl in the
  container is now §2H above; three items in `OPEN-QUESTIONS.md` still are.
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
| §2H crawl in the container | `config.masterDeviceToken != me` | code |
| §4 stats on hub | `outstandingOnHub` | code |
| §4 guard 7 | `hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt` | path |
