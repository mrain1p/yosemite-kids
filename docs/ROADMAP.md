# Yosemite Kids fork — what to do next

**This is the only forward-looking document.** `FORK-NOTES.md` is a changelog:
it records what happened. The `PLAN-*.md` files are finished history from single
rounds. When those disagree with this file, this file is wrong and should be
fixed — but check the code before believing any of them.

Last fully audited **2026-09-04** against commit `170b7e3` (0.12.3-fork), by
reading the code rather than the docs. That audit found 12 doc claims that were
simply untrue, including one that had been stale since the round that closed it.
The hub-parity round (1.0.7, 2026-09-06) closed §2G and every bullet of §3 and
re-checked what it touched; the rest is still on the 09-04 reading.

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

**Since 2026-09-05 the fork runs on a real fleet** — a Samsung phone, a Google
TV Streamer and the hub on a Synology NAS — and the sync-convergence and
Settings findings in `FORK-NOTES.md` came from it. What is *still* unproven is
performance: the Chromecast cold-start table in `CLAUDE.md` is upstream's
measurement on upstream's hardware, inherited by the fork and never re-taken
here. Measure it on the Streamer before quoting it (`am start -S -W`, three
samples), and until then treat those numbers as inherited rather than observed.

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

**J. One watch-time budget per child, across every device — the foundation
has landed; the switch has not.** Design record: `docs/PLAN-hub-parity.md`.
Today a child with a television and a tablet still gets the daily budget on
each: the rules and the grants are per child already, and the running tally
(`dailyWatchedMs` in `SessionGuard`) is still per device. The owner asked for
a per-profile choice between per-device and shared.

**What is built.** The plan's ledger, taken as written: `UsageLedger` in
`:core` (grow-only cells keyed `(kid, day, device)`, joined by per-cell `max`,
no clock in the join — `trim` is separate and local), `FamilyDay` and its
forward-only rollover, `Whitelist.homeZone`, `GET|POST /usage` on both faces,
`WatchLedgerStore` on a device and `HubUsage` on the hub, and the one summand
in `SessionGuard.spentTodayMs()`. Guards 43, 44 and 45 hold the day to one
spelling, the ledger out of the config, and the tally to one reader; guard 27
now says what it enforces — the container's own calendar is still unreachable,
and a zone may only come from the family's config.

**What is left, and it is the whole user-facing half.** `Limits.budgetScope`,
the confirm-with-the-number dialog, the "where the number came from" copy on
every surface that shows a shared figure, the session-start fetch and the
per-minute report, and `FIRST_SHARED_BUDGET_VERSION_CODE` for a half-upgraded
fleet. Until `budgetScope` exists nothing writes the peers' mirror, so the
summand is arithmetically today's behaviour and no family's budget changes.

**The scope question below was NOT settled before building, and is worth
re-reading.** The owner's question was how YouTube manages this without any of
it, and the answer is that Google has one always-on server every device
authenticates to, so there is only ever one copy of the number and nothing to
merge. We have that too, when a hub exists — so the smaller feature would have
been: **a shared budget REQUIRES a hub**, the hub as the single copy, its day
settling the boundary, no merge arithmetic at all. The round that built the
foundation took the plan's hubless ledger instead. That is now sunk cost and
works with or without a hub, but the *policy* on top of it could still be
scoped either way, and "requires a hub" remains the cheaper answer for the
remaining work. *Small-to-medium, and smaller still if the hub is required.*

**K. Staying under YouTube's bot detection — review, and finish the
protections.** Raised 2026-09-06 while weighing a hub-served player for
Apple devices. The concern is real but it is not new, and it is not the
player: fetching video BYTES comes from Google's content servers, which are
not where bot detection lives. The exposure is *extraction* — the watch page
and player code — and the crawler already does far more of that than any
amount of watching would.

Already in place, and worth not rebuilding:
- `HubCrawl` backs off exponentially on consecutive failed runs, 15 min
  doubling to a 6 h cap, and one good run resets it.
- `HubCrawl.probeYouTube` runs before the hub will claim the index at all,
  so a hub that cannot reach YouTube never takes the job from a phone.
- The crawl is paced at `IndexCrawler.CRAWL_DELAY_MS` (4 s) per fetch
  attempt, in bounded batches of `IndexCrawlRun.PAGES_PER_RUN` (60), which
  averages about four requests a minute.
- Guard 7 confines the hub to YouTube's hosts, so nothing else on that box
  can widen the footprint by accident.
- `scripts/upstream.*` and the weekly scheduled check exist because the
  most common cause of "extraction stopped working" is an extractor that
  needs updating, NOT a ban — and the two look identical from here.

Not built, and what this item is for:
1. **Cache resolved streams.** A media address stays valid for hours, so a
   replay should cost no new extraction. Nothing caches them today.
2. **The crawl stands aside while a child is watching.** Finishing the index
   a few hours later is worth nothing next to a video that will not start.
3. **A switch to stop crawling**, so a parent who suspects trouble can
   remove the cause without editing a compose file.
4. **Say what to do when it fails.** The search-index card shows a red dot;
   it should say "check for an extractor update" rather than leaving a
   parent guessing between a ban, an outage and a broken build.
5. **Never route the hub through a VPN.** Worth writing into `HUB.md` as a
   warning rather than leaving to instinct: `gluetun` is on this NAS, and
   putting the hub behind it swaps a residential address, which is treated
   leniently, for a data-centre one, which is treated far worse. This is the
   single easiest way to make the problem real.

Named honestly: none of this changes the structural bet. If Google closes
third-party extraction the app stops, and that was true the day this was
forked. These reduce the odds of provoking it and shorten the recovery, and
that is all they do. *Small, and mostly independent of each other.*

---

### 2K. Front-end revamp — what 1.1.0 left open

The kid-facing screens were rebuilt against a design handoff in 1.1.0 (one
home as a section list, the pinned hero, the TV rail with Search, the
restyled player, the live time number). Four things were deliberately left
for the next round rather than rushed:

- **`TvTopChips` still draws beside the rail.** Its UP-from-first-row
  behaviour is documented in place; it comes out once a real-TV pass has
  proven the rail's focus model. Deleting it finishes this bullet.
- **Every TV dp is provisional.** Derived as design-units × 0.75 through
  `tvUnits`, never measured. Read `adb shell wm size` and `wm density` off
  the real Chromecast (and once with the display-size setting stepped) and
  either confirm the factor or fix it in one place.
- ~~**Pins have a container but no editor**~~ — **done.** The editor ships on
  both faces: `PinnedHeroEditor` on the phone's "How videos are listed" page
  and `cardPins()` on the hub's, declared once as `listing-pins` in
  `SettingsSurface`. Every add, move and remove on either face goes through
  `Pins.withRow`, which mints the ranks, holds the row to `Pins.MAX` and drops
  anything the kid cannot see; guard 42 keeps it the only place that does.
  **The release condition still stands:** an older build drops `home` on the
  round trip, so a household is only safe to pin on once every device *and*
  the hub image are on ≥1.1.0.
- **The home ROW ORDER editor is not started, and is further off than it
  looks.** Verified 2026-09-07 while building the pin editor: `HomeShelf` and
  `HOME_SHELVES` read like data, but `UiState.homeSections` is initialised to
  `homeSections(emptyList())` and **assigned nowhere in the repo**; `Whitelist`
  has no row-order property; and `ConfigJson` knows exactly one key under
  `home` (`pins`). So the shelf order is a hardcoded catalogue wearing a data
  shape, and an editor for it is not UI work — it is a whole config-field
  cycle first (a merge unit, a stamp, a change code, a safe state, omitted-at-
  default serialisation, the fingerprint tail, the four canonical tests, and a
  loop in `ConfigMerge.merge` so guard 38 is satisfied). §4 of the sync skill
  is the checklist. Do that before drawing anything.
- **The hero uses the channel avatar as artwork**, upscaled. Real channel
  banners would fix the weakest thing on the home screen.

Also out of scope by decision: the kid-to-parent request flow (§2E), the
"simple mode" density, and the per-device watch budget (§2J) — which the
always-visible time pill now makes more noticeable, not less.

### 2L. From the first real week on 1.1.0 — reported by the family, 2026-09-07

The owner ran 1.1.0 on their own phone with their daughter. Everything below
came out of that, in their words where the wording matters. **The first two
are regressions from the revamp and outrank the rest.**

**Regressions — fix first.**

- **Changing the sort on a channel page throws the view back to the top.**
  Scroll down, tap New / Random / Popular, and the list jumps to the header.
  The sort applies to the list below it; the scroll position should not move.
- **"When looking at a station it looks like something is missing."** Not yet
  reproduced, but narrowed to two candidates by reading `YosemiteScreen.kt`'s
  channel-page block. Both rails are conditional and both fail silently:
  New-for-you draws only `if (fresh.size >= 3)`, where `fresh` counts videos
  with no watch progress, and the Playlists rail draws only
  `if (s.channelPlaylists.isNotEmpty())` — which is also false for the first
  moments after opening a channel, because playlists are fetched after the
  page paints. So a channel with two unwatched videos, or one whose playlists
  have not landed yet, legitimately renders as block-then-Videos and looks
  half-built. The reported screenshot is consistent with being scrolled past
  the block rather than the block being absent.
  Decide whether an empty rail should collapse (today) or show the dashed
  instruction tile the design specifies for empty rails — the latter is
  probably right, because a rail that is *sometimes* there reads as breakage.
  Reproduce on a real phone before changing anything.

**Discovery — the biggest complaint, and the deepest.**

- ~~**A newly added channel takes minutes to appear**~~ — **mostly done.** The
  diagnosis in this entry was right and the fix was not the shelf. A new entry
  is *appended* to the whitelist, so it was resolved last by the refresh's
  slow pass and warmed last by `warmCaches`, behind every channel the family
  already had, one at a time in the background lane — which is the several
  minutes. Now: `MainViewModel.warmNew` fetches a newcomer's page one in the
  **interactive** lane the moment it is seen (`SourceFirstSeen.sync` reports
  it), saves it to the video cache, screens it and harvests it into the search
  index; and `warmCaches` walks `ContentWarm.stalest` order rather than list
  order, so even the fallback path takes the newcomer first. A "Just added"
  chip on the Channels page orders by when this device first saw the source.
  **What is still slow:** the *back catalogue*. Search finds the newcomer's
  newest page in seconds; older uploads appear as `IndexCrawlWorker` reaches
  them, which is still master-only and still minutes-to-hours. That is §2B and
  is unchanged.
- **Search is static and unranked** — **done, with one gap.** "My daughter
  loves Mario videos but every time I search it just starts with the same
  static list and I have to scroll down past 70 to find the ones she likes."
  Relevance shipped in 1.2.0 (`SearchRank` in `:crawl`) and is the default;
  the chips shipped with `SearchOrder`: best match, shortest, mix it up.
  **The gap, and why:** "most recent" and anything popularity-shaped are *not*
  offered, because `ChannelIndex.IndexedVideo` stores neither an upload date
  nor a view count — `toVideo()` hands back nulls for both, so either chip
  would draw and do nothing, which is worse than a missing chip. Closing it is
  a crawl change: add `publishedAt` (and, if wanted, `viewCount`) to the
  indexed row, write them in `IndexCrawler`, and re-crawl to backfill.
  `SearchOrderTest` asserts both fields are still absent, so the day they
  arrive is the day the test says to add the chips.

**Channels and the channel page.**

- ~~**Sort the Channels page A–Z and reverse.**~~ Done: `CHANNEL_ORDER_ALPHA_DESC`,
  one more value in the existing vocabulary rather than a direction toggle
  beside it, so the parent's default and the hub's manifest get it for free.
- ~~**Show the channel's description**~~ Done. The extractor does return one
  (`ChannelInfo.getDescription`, and `PlaylistInfo.getDescription().content`).
  It is stripped at the boundary by `SafeText.forKids` in `:core` — links,
  bare domains, @handles, e-mail addresses — and lands in `Source.about`
  already safe, so no screen can render the raw text by accident; guard 52
  holds that. Drawn collapsed to three lines under the channel's name with a
  More/Less toggle. Not built: a parent switch for it, because a switch whose
  "off" position lets links through is not a switch a family should have.
- **Favourite (subscribe to) a *channel*, not just a video.** Possibly a
  parent-set thing rather than a kid-set one. New per-kid state either way, so
  it rides the sectioned merge like pins do.

**Chrome.**

- **The chip row clips its last item.** On You, "Up next" is cut off mid-word;
  the owner reports the same on Search, wanting all four visible.
- **Swipe the now-playing video down into the floating player**, the way
  YouTube does. PiP already exists and the button is on the overlay; this is
  the gesture, not the feature.

## 3. Known-wrong docs — cleared 2026-09-06

All five went out with 1.0.7 and are recorded here rather than deleted,
because "documentation drifts and nobody is watching" is the standing risk and
the list is the evidence for it. `HUB.md` had told parents their TVs only sync
while a kid is watching; `ARCHITECTURE.md` still showed a one-module app;
`LAN-API.md` was missing routes, and the hub's whole half of it was covered by
no guard at all until guard 30; the `stats` entry in `SettingsSurface` blamed
"the hub never initiates"; and `HubWeb.pages` claimed to be derived from
`SettingsSurface` while being a literal list.

The last one is only half fixed: the KDoc now says what the code does and
names guard 3 as what holds it equal. **Deriving the list would be strictly
better** — a list that cannot drift beats a list a guard watches — but guard 3
finds its page ids by grepping the `HubPage("…")` literals, so the two have to
move together, in both scripts, with the negative test. *Small, and worth
doing the next time anything else in that file is open.*

The general lesson, which is why a doc list keeps reappearing here: every one
of these was a sentence explaining why something could not be done, written
when it was true and left standing after the constraint moved. A stale reason
is worse than none, because it is what the next session reads before deciding
not to build something.

---

## 4. Stats on the hub — decided (push), not scheduled

The last `Where.BOTH` group not on the hub. **The decision is made: devices
push a digest on their existing sync.** Recorded here so it is not re-argued.

The reasoning that used to sit in `SettingsSurface` — that the hub never
initiates — stopped being true when it started crawling and nudging. What
replaced it is sharper and is why the poll is not an option: **guard 7 holds
the hub to exactly two outbound destinations**, YouTube through `:crawl`'s
shared client with its host allow-list armed, and the devices' `/sync-now`
through `HubNudge.kt` (four negative-tested clauses). Polling `GET /stats`
would need the hub to hold a credential on each device — the exact shape that
guard exists to refuse, on the box most likely to face the internet one day.
So: the hub announces; it does not command.

What is missing is only the work. The round that would have carried it was the
shared budget (§J), which the owner tabled, so nobody owns the push today. The
`why` text in the manifest now says that rather than blaming the old reason.

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

- **CI runs `bash scripts/check.sh --guards`** as its first step, before the
  toolchain, since 2026-09-04 (`35df387`). Thirty guards, on every push and
  every PR. That entry read "CI runs neither script" until 1.0.7, which is the
  same failure it describes: the audit found the gap, the next round closed it,
  and nobody came back for the sentence. `check.sh` was additionally dead from
  line 78 onward until the same date — a `grep` with no match ending the script
  under `pipefail`, silently, from inside a guard that had *passed*. Guard on
  guard: the pipefail trap is itself checked now.
- **`check.ps1` is still run only by hand**, and it is the half the author of
  this project actually types. Guard 10 compares the two scripts' numbered
  headings and guard 18 makes each syntax-check the other, so a guard added to
  one and not the other fails — but a guard that is present in both and *wrong*
  in the PowerShell one would be found by nobody until someone ran it.
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
| §3 hub pages not derived | `HubPage("kids"` | code |
| §4 stats on hub | `outstandingOnHub` | code |
| §4 guard 7 | `hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt` | path |
| §2K top chips | `fun TvTopChips(` | code |
| §2K provisional TV dp | `fun tvUnits(` | code |
