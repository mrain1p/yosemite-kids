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

*To review when A is built: should the device hold the connection open instead?*
Rather than the hub nudging a device it may not be able to reach, the device
dials the hub and parks the connection (a long poll, or server-sent events),
and the hub answers on it the moment something changes. The device still
initiates, so the hub still holds no credential on it and guard 7 is untouched
— and it *fixes* the unreachable-device case rather than working around it,
which the nudge cannot: the nudge only finds a device whose address the hub
learned from an earlier authenticated call (`HubTokens.noteSeen`), and a
sleeping television has never called.

Weighed for the shared watch-time budget on 2026-09-06 and deliberately left
out of it. A device that is not playing spends no minutes; a device that is
playing is awake and already reporting; and the only moment a stale total does
any harm is session start, when the device fetches anyway. So a parked
connection earns its foreground service here, on reachability, or it does not
earn it at all. The decision when A is built is between the parked connection
and today's nudge plus the 15-minute floor — not both.

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

**G. Version, last sync and role on every device row — done on the phone;
the hub's half remains.** The phone's rows show each device's version, and
**starting a device's update from the phone shipped 2026-09-05**: `POST
/check-updates` makes a device fetch `version.json`, download the APK and put
its installer prompt on its own screen; "Update now" on the device's row and
page calls it and says in plain words what happened (`RemoteUpdate` in
`data/Updater.kt`, `LanClient.checkUpdates`, `DeviceFleet.updateNow`,
`docs/LAN-API.md`). Nothing over the LAN presses the prompt — whoever is at
the device confirms it — and the page says so. Works from the first build
that carries the route; older builds answer 404 and the phone sends the parent
to the device's own Check for updates. What is left is the hub's side of the
same item: `HubTokens` records `host`/`port`/`lastSeenAt` on every
authenticated call and `HubWeb` does not render them, and `Device.address`
has no caller. *Small.*

**H. The crawl in the container — done** (2026-09-05, 1.0.5; design record
`docs/PLAN-crawl.md`, changelog entry "The hub builds the search index" in
`FORK-NOTES.md`). Still owed from the plan's own list: the first full crawl
measured with `docker stats` on the NAS and the number written into
`HUB.md`, and a fleet-scale run of the handover (phone master → hub) watched
end to end; both need the rebuilt image running on the NAS.

**I. The Settings form re-stamps what it did not change — done** (see
`FORK-NOTES.md`, "The settings form adopts what it saved"). Every save now
goes through `saveForm` and the screen's `adopt`, which take the stamped
result into the form's own state as well as the baseline; guard 15 holds it
there and `SettingsFormSaveTest` drives the path three times on an unchanged
form. Still owed: a run through the emulator loop with two phones' worth of
edits under one open form, which this round could not do.

**J. One watch-time budget per child, across every device — parked.** Design
record: `docs/PLAN-hub-parity.md`. Today a child with a television and a
tablet gets the daily budget on each: the rules and the grants are per child
already, but the running tally (`dailyWatchedMs` in `SessionGuard`) is per
device, and nothing documents that. The owner asked for a per-profile choice
between per-device and shared.

The design in `PLAN-hub-parity.md` solves it without a hub, and pays for that
in complexity: a separate grow-only ledger, per-cell `max` joins, and a
careful day boundary — because two devices merging counters can otherwise
ratchet the family's day forward and hand out a second budget that no parent
action reverses.

**Reconsider that scope before building it.** The owner's question was how
YouTube manages this without any of it, and the answer is that Google has one
always-on server every device authenticates to, so there is only ever one
copy of the number and nothing to merge. We have that too, when a hub exists.
So the smaller feature is: **a shared budget REQUIRES a hub.** The hub is the
single copy, devices ask it at session start and report as they spend, and
its clock settles the day. No merge arithmetic, no competing counters, and no
route by which a television with a wrong clock grants a second day. Families
with no hub keep per-device budgets, and the setting says so rather than
being silently absent.

That version is a fraction of the work and loses only the case of a family
who wants a shared budget and refuses to run a hub. Decide between the two
before starting; do not start from the plan's ledger by default. *Medium
either way, and much smaller if the hub is required.*

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
holds the hub to exactly two outbound destinations: YouTube, through
`:crawl`'s shared client with its host allow-list armed, and the devices'
`/sync-now` through `HubNudge.kt`** (rewritten 2026-09-05 for the crawl; four
negative-tested clauses). It still means "the hub announces; it does not
command."

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
  container shipped (§2H); three items in `OPEN-QUESTIONS.md` still are.
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
| §2G device rows on the hub | `val address: String?` | code |
| §4 stats on hub | `outstandingOnHub` | code |
| §4 guard 7 | `hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt` | path |
