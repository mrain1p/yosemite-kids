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
`docs/archive/PLAN-crawl.md`, changelog entry "The hub builds the search index" in
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
has landed; the switch has not.** Design record: `docs/archive/PLAN-hub-parity.md`.
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
1. ~~**Cache resolved streams.**~~ **Done in 1.9.0** (`PlaybackCache` in `:crawl`,
   in front of `resolvePlayback`; §8C.3 has the shape).
2. ~~**The crawl stands aside while a child is watching.**~~ **Done in 1.10.0**
   on both boxes: the hub off `HubWatchMeter.anyoneWatching`, the phone off
   `NowPlaying`; the tick is skipped and said, never counted as a failure.
3. **A switch to stop crawling**, so a parent who suspects trouble can
   remove the cause without editing a compose file.
4. **Say what to do when it fails.** The search-index card shows a red dot;
   it should say "check for an extractor update" rather than leaving a
   parent guessing between a ban, an outage and a broken build.
5. **Never route the hub through a VPN — written down 2026-09-08**, in
   `HUB.md` under "Never route the hub through a VPN". `gluetun` is on this
   NAS and putting the hub behind it is a two-line change that looks like a
   tidy-up, so the warning names it, says why a data-centre exit is treated
   worse than a residential one, and says to look there first when
   extraction breaks after a networking change. Nothing enforces it — the
   hub cannot see its own egress path — so it stays a rule for whoever edits
   the compose file.

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
- ~~**The home ROW ORDER editor is not started.**~~ **Done 2026-09-14**, the
  way the 09-07 note said it had to be: a whole config-field cycle first
  (`Whitelist.homeRows`, one `HomeRow` per shelf per home, the `home.row`
  unit, absent-safe, omitted-at-default, the fingerprint tail, the canonical
  tests in `HomeRowsConfigTest`, its loop in `ConfigMerge.merge`), read by
  every face through `homeRowsFor`, edited only through
  `HomeRows.withOrder` (guard 70), with the console's editor (`cardRows`) and
  the phone's Listing page on top. "Reset to default" stores no rows at all.
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
  ~~**The gap, and why:** "most recent" and anything popularity-shaped are *not*
  offered, because `ChannelIndex.IndexedVideo` stores neither an upload date
  nor a view count.~~ **Closed in 1.9.0** (§2M): the index keeps both, "Newest"
  is a chip (`SearchOrder.RECENT`, undated rows last), and the one order still
  not offered is a search ranked by views — deliberately, not for lack of data.

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

### ~~2M. The search index throws the upload date away~~ — done in 1.9.0

**Done 2026-09-15, 1.9.0**, exactly as written below and one step further:
the index keeps the **view count** as well as the date (`IndexedVideo.viewCount`
and `publishedAt`, keys `v` and `p`, absent on an old row), a known row
*learns* both from the next crawl of its page rather than waiting for a
re-crawl (`ChannelIndex.addVideos`; `ChannelIndexDateTest` is the gate), and
the browser now honours **Show when a video came out** (the hub composes the
phone's own `metaLine`), **Channel page layout** ("Popular first"), and
**Latest video** on the Channels grid. "Newest" is a search chip on the
phone (`SearchOrder.RECENT`); undated rows sort last and are never hidden,
as recommended below. What is still off the table is a search *ranked* by
views — a popularity contest, on purpose.

Asked for directly (2026-09-07) after "most recent" turned out to be
unofferable on the search screen. It is a smaller job than it sounds, because
**nothing needs fetching — the date is already in hand and is being dropped.**

`Video.publishedAt` (epoch ms) exists and `YouTubeRepository` already converts
the extractor's `uploadDate` into it. `IndexCrawler.toIndexed` then builds a
`ChannelIndex.IndexedVideo` without it, and `ChannelIndex.saveSource`
persists exactly five keys — `id`, `t`, `c`, `th`, `d`. So the crawl computes
a date every time and discards it on the way to disk.

**What it takes**

- `IndexedVideo` gains `publishedAt: Long?`; `toIndexed` carries it; `saveSource`
  writes a sixth key and `parseSource` reads it back as null when absent.
- `toVideo()` populates `Video.publishedAt`, which is what makes a date
  reachable from a search result at all.
- Old index files stay valid and simply have no date — the same "null keeps
  the feed's own newest-first order" rule `Video.publishedAt` already
  documents. **No index version bump, no forced re-crawl**; a source picks up
  dates the next time it is crawled. Cover that with a parse test on a
  five-key file.

**What it unlocks, in order of value**

1. **"Most recent" on search.** Today `SearchRank` cannot offer it and the
   chips deliberately do not fake it with a proxy.
2. **A real recency term in the ranking** — a genuinely new video from a
   channel she loves should beat a five-year-old one, which today it cannot,
   because relevance has no notion of when.
3. **"Newly added" as a video ordering** rather than only a channel one, which
   is half of §2L's discovery complaint.
4. The meta line on search results stops being channel-only for families who
   have `showVideoAge` on.

**Not free, and worth saying:** dates only appear for sources crawled after
this ships, so the first week looks partial — every source has *some* dated
videos and some undated ones. Decide whether an undated video sorts last under
"most recent" (recommended: it is honest) or is hidden from that ordering
(it is not — hiding a video because we lack a date about it is worse than
showing it late).

### 2N. The web player — all four steps landed, 1.4.0

A child can open a browser on the LAN, type a code a parent minted, and watch.
`GET /media` proxies a video's bytes (step 1); the kid had an **origin of its
own** on a second port and a claim code traded for the cookie that gets in
(steps 2–3) — both retired in the one-origin round of 2026-09-14: the kid app
is `/kid` on the one port, the wall is built from credentials, and a child
gets in by a parent's QR or their own password; and the page itself is a home screen with the television's
shelves, a channel page, search, and a player that resumes, counts minutes and
says why when it will not play (step 4). See `docs/LAN-API.md` "The kid's
routes" for the wire and the argument, and `docs/HUB.md` "Letting a browser
watch" for what a parent does.

**The anti-drift shape, because it is the part that has to survive.** Nothing
about what a child sees is decided in the browser or in the hub. The shelf
catalogue and order are `homeSections()` in `:core`; their contents are
`KidHome` in `:crawl`, which `MainViewModel` now calls too, so the phone and
the browser run one implementation and `SuggestionsTest` /
`HistoryAndLayoutTest` still prove it is the phone's; the hero is
`resolvePins()`; search order is `SearchRank`; what may be seen at all is
`HubPolicy.catalogueFor`, which walks the same predicates `mayPlay` does — and
`HubKidHomeTest.the browse filter is the play filter` asserts that equivalence
video by video. The colours are `kidTinted()` in `:core`, which `Theme.kt` now
calls as well, held to Compose byte for byte by `KidTokensParityTest`. Guard 61
fails if the page grows a filter, a sort, a cap or a colour of its own.

**Verified in a real browser, not only in tests**: the claim screen, the home
with its shelves, a channel, search, and a video that played, resumed at the
position the hub remembered, and credited a minute to the ledger — in all three
looks (My colour, Dark, Light) at tablet and phone widths.

**What step 4 changed elsewhere.** `HubWatchMeter.beat()` finally has a route:
`POST /progress`, every 20 s while a video plays, which is *the* mechanism by
which a browser's minutes reach a budget. `HubKidHistory` is new — the
browser's device store, since a browser has no other place to remember where it
got to, and every other face keeps its own watch history locally.

**Still to do here, small and stated rather than left to be discovered.**

- **The hub indexes videos, not channel avatars**, so a channel card wears its
  newest video instead of the channel's picture. The app has the avatar because
  it resolves the channel; the crawl throws it away. Worth carrying in
  `ChannelIndex.SourceState` the next time that file is open.
- **No favourites in the browser.** `SearchRank.Signals` takes them and the
  page sends an empty set, so a hearted video does not yet rank higher there.
  It waits on the same store the app-side favourite/subscribe work needs
  (§2L).
- **No "new since you looked" badge.** That is a per-device fact and this
  device has no memory of previous visits yet; `pinMeta` therefore says "12
  videos" rather than "3 new".

**What step 1 measured**, kept because it is the reason to build the rest:
9–11.6 MB/s (73–93 Mbit/s) through the proxy on a development machine, against
the 0.06–0.125 MB/s a 360p stream needs, with the control plane answering in
under 3 ms with three streams in flight.

**Two things it found, both now dealt with.**

1. **The allow-list had to grow by one host.** NewPipe's player request goes to
   `youtubei.googleapis.com`, which was not one of `Http.HUB_HOSTS`, so
   `GET /media` answered `502 {"error":"resolve-failed"}`. It is now named in
   full — not `googleapis.com`, which would admit every Google API there is —
   in `Http.HUB_HOSTS` and in guard 7's list in both gate scripts.
2. **Muxed URLs often carry no `clen`.** Measured against a real itag-18 URL:
   `ratebypass` and `dur` were there, `clen` was not. `HubStream` therefore
   falls back to a `HEAD`, which answered `Content-Length` and
   `Accept-Ranges: bytes`. Worth knowing on the app side too — the
   television's `ChunkedStreamDataSource` gives up and reads progressively in
   exactly this case, and nobody has measured what that costs.

**The ceiling is about 360p** and will stay there until someone builds MSE or
HLS: HD on YouTube is separate video and audio tracks merged at playback, which
ExoPlayer does and a plain `<video>` cannot.

**Known gaps, stated rather than left to be discovered.**

- A browser that stops beating stops being credited, which is the right
  direction — the meter counts time this hub has *seen* pass, and
  `HubWatchMeter.MAX_GAP_MS` bounds what a closed lid can cost. It is still not
  an enforcer: what stops a child mid-video is `/media` re-asking `HubPolicy`
  on the next chunk, exactly as before.
- The kid origin is plain HTTP on the LAN, like everything else here. A cookie
  that lives six months is worth more than a session one, and the day this box
  faces anything but a home network that is the first thing to change.

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
## 7. Web/app parity — the six rounds (R1-R5 done, R6 partly; 1.7.0)

The browser is a third face and must reach feature and look parity with the
app: *"it can essentially function as the app for ios so should feel like it
and look like it. I want to avoid the looks diverging."* Fifteen agents specced
every Android kid surface against the code; the rounds below are the result.

**R1 — the mechanism. DONE.** `KidSurface` (the surface manifest, sibling of
`SettingsSurface`) and `KidGeometry` (shape tokens), guards 62 and 63. The gate
now prints `kid surfaces still to reach the browser: …` on every run. Also
collapsed `FINISHED_FRACTION` from eight spellings to one, and fixed guard 57's
route regex, which had no digits and was failing **open**.

**R2 — the You tab and the shelf chrome. DONE.** Favorites, Watch later, Up
next and History, in `:core`'s order with `:core`'s words. History live; the
other three declared and empty, saying what would fill them. The rule, the bold
title, the mono count and See-all-in-place are the furniture R3 and R5 land
into.

**R3 — writes: the hold menu and the saved lists. DONE.** The extraction that
matters: `SavedListStore`'s pure half and `QueueStore` into `:crawl`, **with the
prune-then-merge convergence test they have never had** — prove it fails against
the unfixed code first (`.claude/skills/yosemite-kids-sync`). Then
`HubSavedLists` keyed by the credential, `POST /list`, and the long-press
dialog. Flips favorites / watch-later / up-next to `webReady`.

**R4 — it becomes an app on the iPad. DONE.** A kid manifest generated from `:core`
beside `kid-tokens.css`, four icons from one generator, the
`apple-mobile-web-app` meta tags, and `theme-color` refilled from the live
tokens when the look changes. **Deliberately no content-caching service
worker**: Cache Storage outlives the claim cookie, so a cached page keeps a
revoked or blocked child looking at a working app — the exact failure
`/media`'s per-chunk gate exists to prevent.

**R5 — the ordering surfaces. DONE (channels, sorts, seeded Surprise).** `orderChannels`, `filterVideos`,
`orderByPopularity`, `orderByWatched`, `defaultFilterFor` and `VideoItem` to
`:crawl` (they are typed on `Source` and `Video`, which live there). A seeded
mix beside `SearchOrder.order` — `:app` calls bare `shuffled()` today, which by
definition cannot be reproduced on another face. Query parameters, never new
routes. Channels grid, Surprise, playlists, watched-videos.

**R6 — the player. PARTLY DONE** — Back, kid-facing refusals, Up next hand-off and one countdown vocabulary all shipped. The custom scrubber, the double-tap seek and the second-precision countdown did NOT: the first two need a real finger on a real iPad, and the third needs HubWatchMeter to interpolate because UsageLedger counts whole minutes. Original note follows. Own controls over `v.controls = false`; `PlayerDismiss`
and the countdown's pure half to `:core`. `HubPolicy.clock` stops collapsing to
whole minutes so the amber ring stops being a lie in the last sixty seconds.

**Out of scope in every round, with reasons:** downloads and offline in any
form (a cached video keeps playing after a parent blocks it); HD and any player
transport work; sponsor segments (`sponsor.ajay.app` is not in `Http.HUB_HOSTS`
and adding a host is its own decision); and the three dead sort chips, which are
blocked on §2M's upload date rather than on the browser.

---

## 8. Everything else — the 2026-09-09 survey, revised 2026-09-14

116 agents read the roadmap and the docs against the actual code, and every
candidate was then handed to a skeptic who tried to prove it was already done.
**109 items stood; 4 were struck as finished.**

**Revised after 1.7.0 shipped.** Items this document described as pending and
which have since landed are struck below rather than left standing — a roadmap
entry outliving its work is the failure that made this survey necessary, and it
would be a poor joke to let the survey's own output rot the same way.

### 8A. The biggest risk, and it is not code

**The release keystore still exists on exactly one disk.**
`~/.pickwick/pickwick-fork-release.keystore` (2708 bytes) and its
`.password.txt` (24 bytes), both dated 2026-09-02. That key is the sole trust
anchor for self-update and there is deliberately no debug fallback. If the disk
dies, every installed family must uninstall to take another build, and
uninstalling wipes their curation — channels, kids, rules, history, resume
points. No recovery, no re-signing, no remote fix.

**1.7.0 raised the stakes rather than lowering them.** It is the first build
since 1.3.0 that devices are actually offered, so the fleet is now upgrading
in place against that signature. Every family that takes it is one more
household that must wipe and start again if the key is lost. It remains the
cheapest fix on this whole list: copy two small files somewhere else, password
stored apart from the keystore, never in the repo and never in the
OneDrive-synced tree.

~~The runner-up: publishing `version.json` before the GitHub release exists.~~
**Survived contact.** 1.7.0 was cut release-first, with
`releases/latest/download/yosemite-kids.apk` checked to resolve before
`version.json` moved. The trap is real and the order is now proven; it belongs
in the release skill, not on a risk list.

### 8B. Owner-only, not code

| Do | Why |
| --- | --- |
| **Copy the keystore and its password off this machine** | §8A. The one item here that cannot be undone. |
| Decide about "Submit list to directory" | That row posts the family's whole channel list to **upstream's** worker and opens a public PR on `itcon-pty-au/pickwick`, while the consent copy calls it "the shared Yosemite Kids directory". |
| `gh repo set-default mrain1p/yosemite-kids` | Removes a trap the release skill documents in prose. Then note that upstream queries need an explicit `-R`. |
| When the Streamer is to hand: `wm size`, `wm density`, three `am start -S -W` | The ×0.75 `tvUnits` factor and CLAUDE.md's cold-start table are inherited numbers nobody has taken on this hardware. |
| **Ten minutes with the iPad** | The browser work is verified on desktop Chrome at a phone viewport. The layout, claim, lists, sorts and hand-off are proven; the hold gesture, the install and the Back swipe are iOS behaviours reasoned about rather than watched. |

~~Publish the GitHub release~~ — done at 1.7.0, and `version.json` with it.
~~Add the 8766 mapping on the NAS~~ — done; the kid origin is on **8767**,
because `curatorr-analyzer` holds 8766 (`0.0.0.0:8766->8765`). Both sides of
that mapping are 8767 deliberately: the console prints the port the process
binds *inside* the container, so an asymmetric publish would tell a parent the
wrong number. **Retired 2026-09-14:** the kid app is `/kid` on 8765 now; the 8767
mapping and its environment line come out of the NAS compose file.

### 8C. Ship next

1. ~~The channel page still jumps.~~ **Done in 1.7.0**, both paths, with guard
   64 — the sort-chip half was already fixed, and the second half was the
   playlist rows arriving after the paint. Gated on `DragInteraction.Start`,
   which is the only signal separating the child scrolling from the app
   scrolling.
2. ~~**"Something is missing" on a channel page.**~~ **Done in 1.9.0**: the
   New-for-you slot is always drawn (a short row, a line saying there is
   nothing new, or a skeleton while loading) and the playlist strip has a
   skeleton in its own keys until the listing answers; guard 71 holds both.
   Was: the other half of the same week-one report and still open. `YosemiteScreen.kt:900` draws New-for-you
   only `if (fresh.size >= 3)` and `:906` draws Playlists only when non-empty,
   so a channel with two unwatched videos renders as block-then-Videos and
   looks half-built. A rail that is sometimes there reads as breakage. Any
   placeholder must occupy the slot and keys the real row will take, or it
   changes item count mid-scroll — the same failure class guard 64 just fixed.
3. ~~**Cache resolved streams on the app/TV path.**~~ **Done in 1.9.0**, exactly
   this shape: `PlaybackCache` in `:crawl` (20 minutes, 64 entries, keyed on
   page URL and ceiling, forgotten whole on a playback failure, bypassed by
   downloads; `PlaybackCacheTest`). As written: every play, replay and
   quality change paid a full `StreamInfo` extraction — the request bot
   detection watches, and a multi-second wait in front of a child. `HubStream`
   solved it for the browser in 1.4.0 (20-minute TTL, 64 entries) and the app
   never got it. Key on `(videoPageUrl, maxHeight)`, not the id alone, or the
   quality picker silently becomes a no-op. Evict on playback failure, or a
   stale URL becomes a video that cannot play and `onPlaybackFailed` walks it
   through the queue. Downloads must bypass it.
4. ~~**The kid surfaces the gate still names.**~~ All three reached the
   browser in 1.9.0; the gate names none now.
   - ~~`watched-videos`~~ — done: `/channel?watched=1`, the hub's finished rows
     in the phone's `orderByWatched`, and a "Watched · N" link on the page.
   - ~~`search-page`~~ — done: recents per kid on the hub (`HubKidSearches`,
     rules in `RecentSearches` in `:core`), remembered only on Enter or a
     chip, and the order chips through `SearchOrder` with one set of words.
   - ~~`playlists`~~ — done, crawler work first as written: `PlaylistCrawlRun`
     indexes a channel's playlists (twenty, first page each, daily) and
     `/kid/playlists` + `/kid/playlist` draw the strip, See all and a
     playlist page from the index.

### 8D. Then

- ~~**A circuit breaker on extraction refusals.**~~ **Done in 1.9.0**:
  `PlaybackBreaker` — one failure is the video's and is skipped as before; the
  second in a row stops the walk and the card says "YouTube isn't answering
  right now" until a finger presses Try again. Was: `PlayerActivity` skipped to
  the next video when a resolve failed — which resolved again — so the moment
  YouTube started refusing, the player sprinted through the whole queue.
- ~~**Guard the crawl pacing constants.**~~ **Done in 1.10.0**: `CrawlPacingTest`
  pins the values (four seconds, sixty pages, the playlist pass's twelve) and
  guard 72 refuses any production caller that passes a pace of its own -
  `HubCrawl.real()` and the worker take the defaults, and only the loops in
  `:crawl` and `HubCrawl` (forwarding its constructor value) may name them.
- ~~**The crawl stands aside while a child is watching**~~ **Done in 1.10.0**:
  `HubWatchMeter.anyoneWatching` (a beat inside the gap) makes `HubCrawl`
  skip its tick with "a child is watching" and no backoff; the phone's
  `IndexCrawlWorker` does the same off `NowPlaying`.
- ~~**The browser player's three honesty bugs.**~~ Two done in 1.6.0: Back now
  means back (the page pushes one history entry per navigation *into*
  something, and comes out of it), and `KidWords` in `:core` gives a child
  their own sentence where `HubPolicy.Decision.detail` — written for a parent's
  log — was being shown to them verbatim. **Still open:** a failed `/home`
  shows the claim screen to a child who is already claimed, which reads as
  being logged out rather than as the hub being briefly unreachable.
- **The three R6 player pieces deliberately not built, and why.**
  - *The custom scrubber and the double-tap seek.* These are pure feel, and
    getting them right needs a real finger on a real iPad. Building them from a
    desktop browser would be guessing at the one part of the player that cannot
    be reasoned about.
  - *The second-precision countdown.* `UsageLedger` counts **whole minutes**,
    so seconds in the page would be inventing precision the box does not have —
    a child told "1 minute left" watches it say that for sixty seconds and then
    stop mid-sentence. Making it real means interpolating from
    `HubWatchMeter`'s accrual and returning `Remaining[{ms, kind}]` from
    `HubPolicy.clock` on both `/home.time` and `/progress`. Real work, and
    worth it — a fake seconds countdown would be worse than honest minutes.
- ~~**Keep the LAN server alive while the app is closed**~~ **Done in 1.10.0,
  untested on the television**: `LanService`, a foreground service the activity
  starts on a TV only, holds the process (and the server in it) and rebuilds
  the server from `buildLanServer` if the system restarts it. The wiring moved
  out of `MainActivity` into `LanServers.kt` for that. Wants a real evening on
  the Chromecast before anyone calls it finished.
- **`FORK-NOTES.md` is a release behind**, and three §2L bullets describe work
  that has shipped.

### 8E. Deliberately deferred, with the reason

- **The shared-budget chain.** Carried, stamped, merged and enforced end to end;
  only the control is missing, and no family has asked.
- **`publishedAt` through `ChannelIndex`** (§2M) and the Most-recent chip that
  needs it. The edit is small; the delivered feature is not — `addVideos` never
  rewrites a known row, so dates would land only on new uploads.
- **Favourite / subscribe to a channel.** Real value, but it is new per-kid
  cross-device state and therefore a full sectioned-merge cycle through the most
  convergence-sensitive code in the repo.
- ~~**Home shelf order as a config field, and the parent's shelf editor.**~~
  **Done 2026-09-14** (§2K). The fleet condition was met the way the pinned
  hero met it: an empty list writes nothing and hashes as nothing, and every
  device in the house is past 1.1.0. A device older than this build drops a
  saved arrangement on its own round trip; update every device before
  arranging a home.
- **Delete `TvTopChips`; measure the real Chromecast.** Both need a remote in
  front of the real television, and both fail silently from an emulator.
- **MSE/HLS above 360p.** The largest remaining web-player piece, and 360p on a
  tablet held close is a smaller problem than a tablet that cannot go Back.
- **Kid → parent requests.** Worth building; a new cross-device store and a new
  kid-writable LAN route.
- **Take back a grant** (upstream 4e0d328, 2026-09-14 sync). A tap too many on
  Grant still has no undo, and upstream's shape does not port: it subtracts
  minutes over `POST /takeback`, which by construction misses the television
  that was asleep when the parent tapped — the exact failure config-carried
  grants exist to prevent. The fork's undo is *removing the grant unit*:
  tombstone `grant|<id>` (the merge already deletes those — `ConfigStamp`
  tombstones expired ones on every save), fast-path the awake devices, and give
  `SessionGuard` the reverse of `applyGrants` — drop the id from its prefs copy
  and shrink `windowPassUntil` by the same minutes, never below now. Four
  decisions to make first, which is why this is deferred rather than ported:
  which grant "take back 15" removes when the day holds a 5 and a 15; whether a
  partial take-back exists at all; what the hub's console offers beside its own
  grant editor; and the kid's notice, which is parent-attributed like the pause
  (`KidNotices.takeBack`) or the countdown just shrinks and reads as the device
  miscounting.
- **Today's screen time as one bar** (upstream 4e0d328, same sync). Base
  minutes, bonus, watched and what is left, on the parent's kid page and in
  Stats. The fork's kid page already carries a Today section and "N of M min"
  in words, so this is a legibility upgrade, not a missing feature — and it is
  a drawing a child may also meet, so it needs a `KidSurface` row and its
  numbers out of `KidGeometry` before either face draws it, not after.

### 8F. A house style, and who checks the checkers

Two threads the owner opened on 2026-09-09 and asked to come back to.

**The house style is the real gap.** `CLAUDE.md` covers how to *work*; nothing
covers what the product *is*. The palette, the type scale and now the geometry
are shared and enforced, and `KidSurface` owns the words of a shelf — but the
**voice** is unwritten and lives only in whoever last touched a composable: what
a refusal sounds like to a five-year-old versus in a parent's log, when the
amber warning colour is allowed and when it is not, what an empty state must
always do (say the gesture that fills it), how long a sentence a child reads may
be. Every one of those is a judgement rather than a value, so by this project's
own ordering it belongs in a **skill**, not a guard. Write it once the parity
rounds settle and there is a full product to describe.

**`scripts/guard-canary.sh` is the answer to "who checks the checkers".**
Sixty-odd guards, and until it existed nothing had ever verified that any of
them could still fail — each was negative-tested once, by hand, by its author,
and then never again. Three had since gone blind. The canary breaks the tree
on purpose, one mutation at a time, and asserts the gate notices.

**Wired in on 2026-09-14 (the housekeeping round).** Guard 65 is the
meta-check: every guard from 56 upward must have a case, so a new guard
cannot land without one. CI runs the canary after the guards on every push —
on Linux, where the bash gate takes seconds; on a Windows bash it is five
minutes a run, which is why the canary never had a clean full run by hand.
The same round generated `docs/GUARDS.md` from the headings (guard 67 keeps it
current) and moved the finished `PLAN-*.md` records to `docs/archive/` (guard
66 keeps them there). **Still open:** cases for guards 1–55, which the
meta-check deliberately does not demand yet.

Two harness bugs are fixed already and are worth not re-learning:

- **It must assert its own MUTATION landed.** A pattern that silently matched
  nothing — a CRLF working tree defeating a `\n` in a slurped regex — reads
  exactly like a blind guard, which is the one conclusion this script exists to
  draw correctly. A canary that cannot tell a blind guard from a missed edit
  will one day retire a working guard.
- **It takes a lock.** Two instances mutate and restore the same files, so one
  instance's gate run sees a tree the other just cleaned and reports a working
  guard as dead. That happened to guards 56 and 57, and it is the worst failure
  this script can have: "your guard does not work" is the sentence nobody
  double-checks.

---


## Anchors

Each row names code an item above depends on. **`scripts/check.*` fails if one
stops resolving** — because that is the signal an item was quietly finished, which
is exactly how this document went stale twice before it existed. When a guard
fires: confirm the work is done, then delete the item and its row.

| Item | Anchor | Kind |
| --- | --- | --- |
| §2A reachability | `buildLanServer(` | code |
| §2C key in backup | `app/src/main/res/xml/backup_rules.xml` | path |
| §3 hub pages not derived | `HubPage("kids"` | code |
| §4 stats on hub | `outstandingOnHub` | code |
| §4 guard 7 | `hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt` | path |
| §2K top chips | `fun TvTopChips(` | code |
| §2K provisional TV dp | `fun tvUnits(` | code |
| §2M index has no date | `val durationSeconds: Long,` | code |
