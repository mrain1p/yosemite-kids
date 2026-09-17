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

~~**A. A device is not reachable while Yosemite Kids is closed.**~~ **Done in
1.10.0**: `LanService`, a foreground service the activity starts on a television
only, holds the process and rebuilds the server from `buildLanServer` if the
system restarts it; the wiring moved out of `MainActivity` into `LanServers.kt`.
**Untested on a real television** - that is §9F's evening, not an open design.

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
3. ~~**A switch to stop crawling**~~ **Done in 1.11.0**: Pause the crawl on the
   console's Devices page (`POST /api/crawl`, a file on the volume so it holds
   across restarts).
4. ~~**Say what to do when it fails.**~~ **Done in 1.11.0**: the index card on
   the console and the phone say, under a failed run, that it is usually the
   extractor needing an update, and what to do if the newest build still fails.
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

- ~~**`TvTopChips` still draws beside the rail.**~~ **Deleted in 1.11.0**, the
  rail having run a season on the family's television as the only menu.
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
- ~~**The hero uses the channel avatar as artwork**, upscaled.~~ **Done in
  1.11.0**: `Source.bannerUrl` from the channel's own banner, drawn by the
  phone's hero and, through the index (`SourceState.bannerUrl`), the browser's.

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
- ~~**Favourite (subscribe to) a *channel*, not just a video.**~~ **Done in
  1.11.0**: the hold menu on a channel tile on every face, one row, and the
  hearts float to the front of every channel order (`orderChannels`). Was: possibly a
  parent-set thing rather than a kid-set one. New per-kid state either way, so
  it rides the sectioned merge like pins do.

**Chrome.**

- ~~**The chip row clips its last item.**~~ Done, and confirmed by reading both:
  the You strip and the search order chips are `FlowRow`s that wrap, with the
  reason written beside the You one — a fixed, small set of the kid's own
  shelves must not hide its last member off an edge with nothing to say it is
  there. The blocked-window pills still scroll, deliberately: that row can grow
  without bound and wrapping it would push the page down.
- **Swipe the now-playing video down into the floating player**, the way
  YouTube does. PiP already exists and the button is on the overlay; this is
  the gesture, not the feature.

### ~~2M. The index's upload dates, 2N. The web player~~ — done

Both shipped (1.9.0 and 1.4.0) and moved to
[`docs/archive/ROADMAP-done.md`](archive/ROADMAP-done.md) with their reasoning.

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

- ~~**The hub indexes videos, not channel avatars**~~ **Done in 1.11.0**:
  `ChannelIndex.setArt` keeps the avatar and banner from the first page of a
  crawl (`SourceState.avatarUrl`/`bannerUrl`, on the wire too), and the
  browser's channel cards and hero wear them.
- ~~**No favourites in the browser.**~~ **Done in 1.11.0**: the hub passes the
  kid's hearted videos to `SearchRank.Signals`. Was: it takes them and the
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

~~**The ceiling is about 360p** and will stay there until someone builds MSE or
HLS.~~ Done in 1.11.0: `HubDash` writes a DASH manifest by hand from the
renditions' byte ranges, `GET /kid/dash` serves it behind the same gate, and
the kid page plays it through vendored dash.js with every segment fetched
through `/kid/media?s=<itag>` — the proxy and its per-chunk gate, never a
redirect. mp4 up to 1080p; the muxed stream is the fallback on any error.

**Known gaps, stated rather than left to be discovered.**

- A browser that stops beating stops being credited, which is the right
  direction — the meter counts time this hub has *seen* pass, and
  `HubWatchMeter.MAX_GAP_MS` bounds what a closed lid can cost. It is still not
  an enforcer: what stops a child mid-video is `/media` re-asking `HubPolicy`
  on the next chunk, exactly as before.
- The kid origin is plain HTTP on the LAN, like everything else here. A cookie
  that lives six months is worth more than a session one, and the day this box
  faces anything but a home network that is the first thing to change.

## 3, 7. ~~Known-wrong docs; web/app parity~~ — done

Cleared 2026-09-06 and 1.7.0 respectively; both moved to
[`docs/archive/ROADMAP-done.md`](archive/ROADMAP-done.md).

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
  toolchain, since 2026-09-04 (`35df387`). Seventy-odd guards (`docs/GUARDS.md`
is the index, generated), on every push and
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
- ~~**Favourite / subscribe to a channel.**~~ **Done in 1.11.0** through the
  saved-list store the hearts on videos already converge through
  (`SavedListStore.CHANNELS`), so no new merge. Was: real value, but it is new per-kid
  cross-device state and therefore a full sectioned-merge cycle through the most
  convergence-sensitive code in the repo.
- ~~**Home shelf order as a config field, and the parent's shelf editor.**~~
  **Done 2026-09-14** (§2K). The fleet condition was met the way the pinned
  hero met it: an empty list writes nothing and hashes as nothing, and every
  device in the house is past 1.1.0. A device older than this build drops a
  saved arrangement on its own round trip; update every device before
  arranging a home.
- **~~Delete `TvTopChips`~~; measure the real Chromecast.** The chips are
  gone (1.11.0). The measurement still needs a remote in front of the real
  television, and fails silently from an emulator.
- ~~**MSE/HLS above 360p.**~~ Done in 1.11.0 (`HubDash`, `/kid/dash`, dash.js in
  `kid.html`). What is left is measuring it on the family's iPad: `video.videoHeight`
  above 360 on a 1080p upload, and what a block mid-segment looks like there.
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


## 9. The measure-and-harden round — planned 2026-09-17, after 1.11.0

Three releases in a fortnight added playlists, HD in a browser, rows a parent
composes, favourites and a TV service — and almost none of it has been touched
on the family's own devices. Every "verified" this month was a desktop browser
against a throwaway hub, or the emulator. Two page-level defects slipped past
every guard and route test in that time (the strip that never drew in 1.9.0;
dash.js sitting at 144p with a full buffer). So the next round measures and
hardens before it adds. The owner asked for all of it, in this order, with the
on-device phase last because it needs an evening and a remote; the phases are
carried out with full autonomy, the NAS and an emulator both available.

### 9A. Phase 0 — a comprehensive review, no code changes

One session of reading, fanned out by module and by dimension, every finding
verified against the code before it is written down, and the console page in
scope as well as the kid's. What it asks:

- **Structure.** Where the module boundaries (`:core`, `:crawl`, `:hub`,
  `:app`) still let two faces decide the same thing separately; the files that
  have grown past what one person can hold (`kid.html` and `index.html` are
  single files; `HubKidServer.kt` and `MainViewModel.kt` are each well past a
  thousand lines); dead code and duplicated helpers.
- **Tests.** What each module covers against what the gate and CI run. Holes
  already on record: `LanServer.handle` has no unit tests; `ConfigStore.fromJson`
  is all-or-nothing; `LanService` has never run on a television; both web pages
  are exercised only by hand.
- **Guards and the canary.** Overlaps between the seventy-odd guards; which of
  1–55 could go blind unnoticed (no canary case); the five-minutes-a-case cost
  on a Windows bash.
- **Diagnosability.** What a parent sees when each face fails; what each of hub,
  phone and TV logs and where it goes; whether every refusal and error carries a
  stable reason code (`HubPolicy.Decision`, `KidWords`, `HubStream.Unplayable`);
  whether the twice-daily extractor canary (`extractor-smoke.yml`) reaches the
  owner when it fires.
- **Robustness.** Error handling on the hot paths — the media pump, the crawl,
  the sync merge and its two-TV race (`FORK-NOTES` next-up item 3) — bounded
  reads on every LAN-facing edge, the hub's pools and every TTL.
- **Skills and docs.** Whether the seven skills and `ARCHITECTURE.md`'s
  "where to change what" table still describe the code after three releases;
  where the docs sprawl.

**Output:** a findings table under §9G — severity, evidence, the fix, its
size — and the phases below reordered where a finding demands it.

### 9B. Phase 2 — the safety net and the hardening

(Phase 1, the devices, is last; see §9F.)

- **A browser smoke test in CI.** A headless browser against the hub jar with a
  seeded index, in `build.yml` where Node already is: sign in with the kid's
  password, the home draws its rails, a channel page shows its strip, the You
  tab, a search, and a play request that reaches the gate. Guard 61 lints the
  page; nothing runs it. This is the check that would have caught both
  page-level defects above, and the project's own rule — a mistake made twice
  is the signal to build a check — names it.
- **A Content-Security-Policy on the kid origin** (`securityHeaders(ex)` in
  `HubKidServer`), now that the page loads a script: same origin only, `blob:`
  for the media source, nothing else. With a guard 60-style count so a reply
  cannot ship without it.
- **Home-load failure honesty.** `loadHome()` in `kid.html` sends a signed-in
  child to the sign-in screen when the hub does not answer; only a 401 should.
  The page should say the hub did not answer and try again.
- ~~**The chip row clips its last item** on You and Search (§2L).~~ Already done:
  both are wrapping `FlowRow`s. The roadmap had not caught up.
- **Upstream sync first**, per the house rule; the last was 2026-09-14.
- A release when this lands, because the fixes are kid-visible.

### 9C. Phase 3 — consistency and hygiene

Added by the round itself, found while clearing the review's list:

- **A kid removed from the family keeps their searches.** `HubKidSearches`
  has a `forgetKid` written for exactly this and nothing calls it, so the
  recent-search chips of a child who is gone sit on the volume until somebody
  edits the file. Small, and the sort of thing a parent would assume already
  happened.

- **The house-style skill** (§8F): the product's voice and its rules of drawing,
  written once now that there is a full product to describe.
- **Docs compaction.** The changelog's sections before 1.8.0 move to
  `docs/archive/`; the roadmap's struck items move to a done appendix so the
  live half reads as a plan again.
- **Canary cases for guards 1–55**, in batches across rounds, so the
  meta-check can one day demand them.
- **Ops, the owner's:** the release keystore and its password file backed up
  off-machine; a nightly copy of the hub's data volume on the NAS through DSM's
  task scheduler (one manual copy from 2026-09-14 exists).

### 9D. Phase 4 — features, in this order

1. Honest seconds on the countdown (§8D): `Remaining[{ms, kind}]` from the
   hub's clock on `/home.time` and `/progress`, interpolated from
   `HubWatchMeter`'s accrual, never invented in the page.
2. Skeleton tiles instead of one spinner while home and a channel load.
3. Today's screen time as one bar (§8E), with its `KidSurface` row and its
   numbers in `KidGeometry` first.
4. A kid-scale search page on the phone, to match what the browser has.
5. The swipe-down-into-the-floating-player gesture.
6. Kid → parent requests, and take back a grant — after the four decisions
   §8E lists are made, and written there first.

### 9E. Sequencing

Phase 0 first, because it can reorder everything after it. Then 2, 3, 4, each
ending with the full gate, and a release only when something kid-visible
shipped. The device phase closes the round.

### 9F. Phase 1 — the real devices, last

Needs the owner, the iPad, the Chromecast and a remote; an emulator stands in
for none of it.

- **iPad.** Whether HD reaches 1080p on the family's Wi‑Fi
  (`video.videoHeight`); what a block mid-video looks like there; the scrubber
  and the double-tap seek, which are feel and cannot be judged from a desk.
- **Chromecast.** Whether the television stays reachable overnight with
  `LanService`; `wm size` and `wm density` off the real set to settle the
  provisional factor in `tvUnits`; the D-pad through the end, error and blocked
  cards.
- **Phone.** The clipped chip row, before and after §9B's fix.

Output is a fix list, which becomes the next round.

### 9G. Findings of the review

Seven readers, one per dimension, 2026-09-17: structure and duplication, tests,
the guards and the canary, diagnosability, robustness and concurrency, the docs
and skills, and the parent console. Every finding below was verified against
the code before it was written down, and where a document and the code
disagreed the code was taken as right. About 190 findings in all; what follows
is every **high** one, the **medium** ones in a line each, and a count of the
rest. ✅ marks what this round has already closed.

**The shape of it.** Nothing is structurally wrong. The hard sharing — the play
gate, the home assembly, the orders, the token tables — is done and done well,
and the hub is the best-tested module in the repo. What the review found is a
pattern rather than a scatter: **a discipline applied on one side of a pair and
not the other.** The hub reads `KidSurface`; the phone hard-codes the same
shelves. The hub renders refusals through `KidWords`; the phone writes its own
sentences. Eleven stores write atomically; the index does not. The kid page
reports its own errors; the console reports nothing. Guard 57 learned that a
route regex must allow digits; guards 14, 22, 29 and 30 did not. Almost every
row below is one half of something already right.

#### The eleven that can lose or hide a family's data

1. ✅ **The index had no lock and no atomic write** — and so could lose a
   channel's back catalogue permanently. Every mutator did `states = states +
   …` then wrote its whole in-memory view, while the crawl worker holds one
   instance for the length of a run and `POST /index` builds a fresh one per
   request: the later write erased the other's source. A torn source file then
   read as an empty channel, `addVideos` wrote the new page back *as* the whole
   source, and the cursor still pointed past the pages that were gone. Fixed:
   one process-wide lock, `writeAtomically` in `:crawl` (temp file, `fsync`,
   `ATOMIC_MOVE`), disk-truth read-modify-write, and a torn file quarantined as
   `.corrupt` with its cursor dropped so the crawl rebuilds. `IndexDurabilityTest`.
2. ✅ **`PlaybackBreaker` could never trip for the failure it exists for.** The
   counter reset when a resolve *succeeded*, before anything played, so a run of
   decode failures — an expired link, a throttled one, YouTube's bot wall — walked
   the whole queue at full speed extracting as it went. It now resets on frames.
3. ✅ **The home timezone was a dead end nobody could leave.** The hub fails
   closed for any kid with a bedtime or a budget while `homeZone` is null, and
   **nothing in the product ever wrote the field**: no editor on any face, no
   default. The first parent to set either rule would have turned the browser
   into "Something is not set up. Ask a grown-up," with no switch anywhere to
   fix it, and no log line naming it. The phone now stamps its own zone the
   moment a rule needs one (`Budget.dayMatters`, `HomeZoneTest`).
4. ✅ **Every console save could fail in silence.** `button()` swallowed every
   rejection (~25 actions), sixteen save paths dropped the promise entirely, and
   a toggle that failed left the control disabled and flipped to the value that
   was *not* written. The console also registered no `window.onerror` and no
   `unhandledrejection` — unlike the kid page — so none of it reached a log
   either. Fixed: one `saving()` wrapper that restores the control and names the
   reason, a banner every failure reaches, and both global handlers.
5. ✅ **"Download a backup" could never work.** The one `/api` call that
   bypassed the fetch wrapper sent no session header, so the hub answered 401
   and the handler signed the parent out — every time they pressed it.
6. ✅ **An unreachable hub told a parent their hub was unclaimed**, inviting
   them to set a second password on a hub that already had one; and it told a
   signed-in child they were signed out, which reads as being thrown out of
   their own app and which a child cannot fix. Both now distinguish a transport
   failure from a refusal, and the kid page offers Try again.
7. ✅ **A refusal reached no log and no parent.** `Decision.detail` says in its
   own KDoc that it is written for a parent's log; it was placed in the 403 body,
   the page read only the child's half, and nobody could answer "why will this
   video not play on the tablet". Now recorded in the report ring, one line per
   kid and reason per ten minutes.
8. **A corrupt `config.json` shows the console an empty family.** `HubStore.load`
   correctly refuses to serve emptiness and rethrows — and five callers swallow
   it into plausible-but-wrong answers: zero kids, "idle: no config yet", a
   fingerprint of `""` published on `/status` so peers sync against a hub that
   cannot read its own document, an election on an empty config, and kid routes
   that drop every parent setting. The phone already has the right shape
   (`ConfigStore.degraded`); the hub needs it, plus a red banner.
9. **`ConfigJson.fromJson` is all-or-nothing per entry.** One malformed source
   or profile throws and the whole document falls back to last-good or empty.
   The same file already drops a bad *pin* or *grant* alone; sources and profiles
   should too. No test anywhere covers a valid-JSON, bad-element config.
10. **The extractor canary can close its own breakage issue after testing
    nothing.** A run whose tests all skip on a bot wall *succeeds*, and the
    `if: success()` step then comments "Canary is green again". It also tests a
    path the hub does not use: it asserts muxed **or** adaptive streams while
    `HubStream.resolve` requires muxed, so YouTube dropping itag 18 leaves the
    canary green while every browser video 502s. And it notifies nobody by name.
11. **`LanClient` discards every push and pull failure.** Seven methods are
    `runCatching { … }.getOrDefault(false)`, so a 403 (approval dropped, and
    re-pairing the only fix) is indistinguishable from a sleeping TV, and
    nothing reaches `Diag` — which is itself never read on a device, because
    `Diag.entries()` has no caller and the ring drains only to a hub.

#### The gate's own blind spots

12. **`check.ps1` has never been negative-tested.** Every canary case shells out
    to the bash gate, and guard 65 reads bash's headings — so the only gate that
    runs on Windows, and the one the author runs before committing, is the half
    with no canary.
13. ✅ **Guards 14, 22, 29 and 30 cannot see a route with a digit or a dot.** This
    is the exact regex class that made guard 57 fail open once; the lesson was
    applied to 57 alone. Guard 30 additionally misses every asset route,
    including the stylesheet another guard requires.
14. **Guard 60's handler list has drifted** — two routes that answer with the
    family's catalogue are unchecked — and its query-parameter clause is
    mistyped identically in both scripts, so it caught `&kid=` and missed
    `?kid=`, which is how a first parameter is written — ✅ fixed; a shared typo
    is invisible to the mirror check and to the canary, which is the reason the
    handler list should be derived from guard 57's rather than hand-kept.
15. **Guard 62(d) enforces nothing**: twenty-four recursive greps whose result is
    discarded, reading in the file as a clause.
16. **~18 checks carry no number**, so they are in no index, exempt from the
    mirror check and exempt from the canary — several of them the security
    family (the clockless merge rule, the Android-in-`:core` ban, the secretless
    export, the atomic-write count).
17. **Forty-five guards have no canary case** (7, 9, 21, 29, 41, 50, 51 and 53 now have one): most of the
    security surface, the whole settings-parity family, the entire sync merge.
    The review names the ten worth doing first and the one-line mutation each.
18. **Step 0's cost is fork overhead, not greps**: two per-file loops spawn ~150
    processes; one full-tree grep is 0.63 s. That is the five minutes.
19. **Under WSL the bash gate fails open on eight guards**, because the awk
    terminators assume LF and the working tree is CRLF.

#### The rules each face decides for itself

20. ✅ **`:app` never reads `KidSurface`** — zero references — so the phone's You
    tab hard-codes the ids, titles, emoji and order the hub takes from the
    manifest, and adds a fifth shelf the manifest does not know.
21. ✅ **Every kid-facing refusal sentence exists twice**, as literals in
    `SessionGuard` and as `KidWords.refusal`, and they say different things
    about the same rule. Only the hub calls `KidWords`.
22. ✅ **Two PBKDF2 costs.** The parent PIN carries its own at 120,000 iterations
    in its own record format, beside `:core`'s shared one at 210,000 that the hub
    and the kid password use — three lines below a KDoc asserting there is one.
23. ✅ **The browser's duration clock is wrong over an hour**: a re-implementation
    in JavaScript drops the seconds, so 1:05:30 reads 1:05 beside a phone
    showing it correctly.
24. ✅ **`SessionGuard` re-implements `Budget`'s three functions**, and two comments
    cite a guard number that does not exist to claim it does not.
25. ✅ **Both hub listeners kept byte-identical copies of the security-header
    writer**, three lines below a KDoc saying a security check must not be
    copied. Now one `HubHttp`, with each server keeping its one-line call so the
    gate's per-file counts still read what they read.

#### Everything else, by theme

- **Medium, structure (16):** five files past what one person holds
  (`PlayerActivity` 4285, `index.html` 3235, `Settings` 3058, `MainViewModel`
  2445, `Pairing` 1888), each with its seams named; `HubStream` keeps a second
  copy of `PlaybackCache`; the `/status` contract authored twice; the console
  re-derives `homeSections` in JavaScript; the hold menu typed twice in
  different orders; the kid's look decided twice with different defaults;
  eleven atomic-write implementations in three variants ✅ (one now shared);
  the phone's ledger file and the hub's written twice; two spellings of "time
  left"; two constants re-typed from `KidCap`; two different megabytes; 17 dead
  declarations ✅ (thirteen deleted; two kept, with reasons).
- **Medium, tests (11):** `-Quick` does not compile unit tests; the `:app` test
  list is a non-recursive glob in three places, so a test in a subpackage is
  never run and nothing says so; the live-YouTube exclusion list is duplicated
  in three files and already wrong; one test runs nowhere at all; four tests
  turn on wall-clock sleeps or mtime granularity; the fixture that seeds a hub
  asserts nothing and runs on every gate.
- **Medium, robustness (12):** the per-chunk gate re-parses the whole config
  twice and every channel's index before every 2 MB; no single-flight on stream
  resolution, so an expired URL mid-video means eight simultaneous extractions;
  `/kid/thumb` is an unmetered YouTube fetcher for any claimed browser; the
  device's HTTP server has no overall request deadline; `LanClient` reads peer
  responses unbounded; the two-TV `peerBehind` race; the playlist pass has no
  gone-handling and refetches a dead playlist every fifteen minutes for ever.
- **Medium, console (14):** removing a channel has no confirmation though every
  other destructive action does; two error messages are written onto nodes the
  re-render throws away; per-kid rulings are one-way but drawn as toggles; the
  page never refreshes itself; `/api/state` ships every kid's plaintext PIN and
  password record to the browser; the manifest's declared ranges are enforced
  only by HTML attributes; ✅ no `Content-Security-Policy` and ✅ no `no-store`
  on `/api`; touch targets below the 44px floor the kid page enforces.
- **Medium, docs (18):** `budgetScope` is documented as unbuilt in four places
  and shipped eight releases ago; §2A describes work that shipped in 1.10.0 and
  its anchor can never fire; `CLAUDE.md`'s release steps omit the two that broke
  1.2.0; the architecture tree is missing nine hub files, eleven others, and puts
  two stores in the wrong module; a session with only `CLAUDE.md` and the map
  skill can find the entry point for almost nothing added in 1.9.0–1.11.0.
- **Low, all dimensions (about 40):** listed in the per-dimension notes; none
  changes what a family sees.

#### What this changed in the plan

The safety net in §9B stands, and four things moved ahead of it (done: the
index, the breaker, the timezone, the console's silence). Two new items joined
§9C: the gate's blind spots (12–19) and the one-sided disciplines (20–24).
Nothing found argued for a change of direction.

**As of 1.12.0 and the round after it**, everything in the first two groups is
closed, along with the one-sided disciplines and the dead code. What remains,
in rough order of what it costs:

- **The gate's own reach** (12, 15, 16, 18, 19): the Windows gate has still
  never been negative-tested, ~18 checks carry no number and so are invisible
  to every meta-guard, guard 62(d) still asserts nothing, step 0 still pays
  ~150 process spawns in two per-file loops, and the bash gate still fails
  open on eight guards under a CRLF checkout. Forty-five guards still have no
  canary case, though the eight that protect the most now do.
- **The hub's degraded read** (8) and **the per-entry config parse** (9): both
  are "a damaged document reads as an empty family", which the phone already
  solves and the hub does not.
- **The extractor canary's honesty** (10): a run that tested nothing can close
  its own breakage issue, and it tests a path the hub does not use.
- **`LanClient`'s silence** (11) and **the unread `Diag` ring**: a device
  cannot say why a push failed, and nothing on a device ever shows what it
  recorded.
- **The console's medium list** (14 rows), of which the rule-deciding and the
  split into files are the two with any size to them.
- **The robustness list** (12 rows): the per-chunk gate re-parsing the whole
  config, no single-flight on stream resolution, `/kid/thumb` unmetered, the
  device server's missing request deadline, the playlist pass with no
  gone-handling.
- **The test list** (11 rows), led by `LanServer.handle` having no test at all.

## Anchors

Each row names code an item above depends on. **`scripts/check.*` fails if one
stops resolving** — because that is the signal an item was quietly finished, which
is exactly how this document went stale twice before it existed. When a guard
fires: confirm the work is done, then delete the item and its row.

| Item | Anchor | Kind |
| --- | --- | --- |
| §2C key in backup | `app/src/main/res/xml/backup_rules.xml` | path |
| §3 hub pages not derived | `HubPage("kids"` | code |
| §4 stats on hub | `outstandingOnHub` | code |
| §4 guard 7 | `hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt` | path |
| §2K provisional TV dp | `fun tvUnits(` | code |
| §9B CSP on the kid origin | `securityHeaders(ex)` | code |
| §9B home-load honesty | `function loadHome()` | code |
| §9A extractor canary reaches the owner | `.github/workflows/extractor-smoke.yml` | path |
| §9F TV service on a real TV | `class LanService` | code |
