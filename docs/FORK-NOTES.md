# Fork notes

This fork of [itcon-pty-au/pickwick](https://github.com/itcon-pty-au/pickwick)
(forked at upstream `v0.7.8`, commit `7ce27f9`) aims at one thing: make the
kid-facing side feel like a real, polished player a young child wants to use,
and harden the phone↔device backend underneath it. Upstream's model — explicit
allow-list, no cloud, parent's phone as the only admin — is unchanged.

Everything below was built and unit-tested on the JVM and exercised on the
Android 14 emulator (phone). Since 2026-09-05 it also runs on a real fleet: a
Samsung phone, a Google TV Streamer and the Docker hub on a Synology NAS,
which is where the sync-convergence and Settings findings recorded below
came from.


The rounds before 1.1.0 - the player, the settings, the first sync and the
pairing, as they were first built - are in
[`docs/archive/FORK-NOTES-pre-1.1.md`](archive/FORK-NOTES-pre-1.1.md).

## What changed

### The hub's LAN-facing edges, and a version that says which build has them (1.1.0)

Groundwork for a kid-facing web player the hub will serve, but each of these
stands on its own.

- **The lockout locked the parent out too.** `adminGate` answered 429 *before*
  it looked at the secret, so the recovery token's exemption — spelled out in
  `HubTokens.verifyAdminSecret`'s `allowPassword` parameter and in
  `docs/LAN-API.md` — had never once been reachable. Someone who only wanted a
  family shut out of their own hub had merely to fail ten times a window, for
  ever, and the way back in was the container log. The lockout now suppresses
  the **password**, not the gate: a password is refused under lockout whether
  it is right or wrong, so the throttle is exactly as strong, and a refused
  attempt still derives no key — with `allowPassword = false` the only work is
  one constant-time compare against 96 bits of hex.
- **`POST /enrol` had no bottom and no top.** It is unauthenticated by
  necessity and it writes `devices.json` on every call, so anything on the LAN
  could append pending rows for as long as it liked. The ten-minute expiry
  sweep bounded the file over time and not at any one moment, and the moment is
  what matters when the volume is a NAS share. Now: fifteen calls a minute, twenty
  codes outstanding (`HubTokens.MAX_PENDING`), and a foreign `Origin` refused.
  At the cap it refuses rather than evicting the oldest — eviction silently
  invalidates a code somebody is reading off a television. Both limits sit far
  above any real household on purpose, because `HubEnrolment.mint` reads any
  non-200 from that route as "not a Yosemite Kids hub"; teaching the phone to
  read the 429 is app-side work still to do.
- **Ten seconds' patience, the same as a device's.** `LanServer` gives every
  accepted socket `soTimeout = 10_000`; the hub gave none, so half a request
  line held one of four worker threads for as long as the caller liked — and
  the caller never got far enough to need a token. The JDK's server exposes no
  socket, so it goes on through `sun.net.httpserver.maxReqTime` before the
  first `HttpServer.create`. The response side is deliberately still unbounded:
  a device pulling a crawled source out of `/index` over tired wifi is
  legitimately slow. `HubServerTest` drives a stalled socket for the real ten
  seconds rather than asserting the property is set.
- **A service worker that owns only its own caches (guard 40).** `activate`
  deleted every Cache Storage key that was not its own — correct exactly while
  this is the only app on the origin, and a mutual wipe on every activation the
  moment it is not, which is the plan. It now evicts only keys carrying its own
  `PREFIX`, and `SHELL`'s `"/"` entry, which is the admin page today only
  because `HubServer` registers `"/"` last, carries a note about what to
  revisit when a second app arrives.
- **The hub says which build it is (guard 39).** It is a relay and never an
  authority, but `HubStore.edit` round-trips the document through
  `ConfigJson.toJson` on every admin save — so an image left behind does not
  merely lack a new control, it **drops the config key behind it** the first
  time a parent saves anything on the NAS. Config fields ride a two-release
  gate for exactly that window, and nothing could check which side of it a hub
  was on. `GET /health`, `GET /status` (`hubVersion`) and the admin page's
  "This hub" card now carry a version generated from `hub/build.gradle.kts`,
  the way `:crawl` generates `ExtractorVersion`. Guard 39 holds it equal to the
  app's `versionName`, which also means a release is a change under `hub/` and
  therefore rebuilds the image instead of leaving it advertising the version
  before it.
- **The security headers were on the page alone, which is backwards (guard
  41).** The admin page is the one reply that is plainly ours; a JSON error a
  browser was steered into fetching is the one a sniffed content type or a
  frame has something to work with. All three now ride every reply, and
  `/approve` and `/pending` join `/login`, `/password` and `/recovery` in
  refusing a foreign `Origin` — no real caller sends one at all.

### The pinned hero gets its editor (1.1.x, release N+1)

1.1.0 shipped the container and nothing that wrote to it, deliberately: an
older build drops `home` on a round trip, so the whole fleet had to be
carrying, stamping and merging the list before anything could change it.
`Pins.RANK_STEP` had zero call sites for a release. It has one now.

- **One declaration, both faces.** `listing-pins` in `SettingsSurface`
  (CUSTOM, `writes = "pins"`, in `kid-shelves` on "How videos are listed"),
  and the `NOT_A_CONTROL["pins"]` line that asked for exactly this is gone.
  The phone draws `PinnedHeroEditor` (`SettingsPins.kt`), the hub draws
  `cardPins()` — pick a kid, see their cards in order, add one, reorder,
  remove.
- **One writer: `Pins.withRow`.** Add, move and remove are the same edit — a
  new membership and a new order for one row — so they are one function, in
  `:core`, and it is the only place a rank is minted, the cap applied, or a
  source the kid cannot see refused. It mints as few ranks as it can (the
  longest run already in the requested order keeps its ranks; the rest land
  in the gap `RANK_STEP` left for them), because a rank is a value inside its
  own merge unit: dragging the third card to the front must stamp one unit,
  not three.
- **No new route on either face.** The phone writes through the settings form
  (`pins` is a `SettingsForm` field, so the auto-save, the stamper and the
  push carry it with no special case); the hub writes through `home`, already
  in `HubWeb.PATCHABLE` and already stamped by `HubStore.edit`. Both paths
  already carried it.
- **The browser sends order, the hub mints ranks.** `HubWeb.normalisedPins`
  ignores whatever ranks arrive and re-derives the row through `Pins.withRow`
  against what is stored, so the page acquires no opinion about spacing, the
  cap or visibility — the failure mode of a second copy is not a crash but a
  home screen whose order differs between the television and the NAS.
- **The hazard that is handled:** a `home` patch replaces the whole object, so
  a card missing from a stale browser copy is an unpin. The page therefore
  re-reads `/api/state` immediately before every pin edit and rebuilds the
  array from what the hub holds *now*.
- **The hazard that is not:** two parents moving the same card resolve by the
  later stamp, silently, per card, with nothing to put it back. A browser
  makes that race three-way rather than two-way. Named in `Pin`'s KDoc since
  the container shipped, named again here, not solved.
- **Fail closed at the moment of pinning**, not only at the draw:
  `Pins.candidates` is the same `visibleTo` predicate `resolvePins` applies,
  and `withRow` filters through it, so no face has to remember to. A card
  whose channel is restricted *later* stays listed, greyed, with the reason.
- **The cap is the editor's, never the parser's.** `HOME_PINS_MAX` is now
  `Pins.MAX` — one number, not two that can disagree — and `withRow` refuses
  to grow a row past it while never truncating one that arrived longer.
- **Guard 42**, both scripts, negative-tested in both: nothing outside
  `:core` builds a `Pin`, `HOME_PINS_MAX` is `Pins.MAX`, and `HubWeb` runs an
  incoming `home` patch through `Pins.withRow`. Guard 26(a) could not have
  caught any of it — it reads the declared properties of `Whitelist`,
  `Limits` and `AiConfig`, and every field of a `Pin` is a level below that.
  The value rules are `PinsEditorTest` in `core/src/test` (the hub runs that
  exact code) and five new cases in `HubWebTest`.
- **A live hub bug found on the way:** the per-kid "Visible to" chips read
  `e.kids`, which is the *request* body's word for the field the wire calls
  `profiles`. Every chip drew as "Everyone" however a channel was restricted,
  so a parent could not see a restriction on the hub and could undo one with
  a tap. The pins editor reads the same field, which is what turned it up.

Still not built, and ROADMAP §2K now says why rather than implying otherwise:
the home **row order** editor. `UiState.homeSections` is initialised to
`homeSections(emptyList())` and assigned nowhere in the repo, `Whitelist` has
no row-order property, and `ConfigJson` knows exactly one key under `home`.
The shelf order is a hardcoded catalogue wearing a data shape; it needs a
whole config-field cycle (§4 of the sync skill) before any screen.

### One day, one counter, kept out of the document (PLAN-hub-parity §3–4, steps 4–5, 7)

The enforcement foundation for a shared watch-time budget. The *switch* — the
per-profile choice between per-device and shared — is **not** here; nothing a
family sees changes. What is here is the two things such a switch cannot be
built on top of, and one bug that had shipped without them.

- **`FamilyDay` in `:core`, and a day that only goes forward.** Four things put
  a value in a day-shaped bucket (a grant's `date`, `SessionGuard`'s daily
  tally, the digest's channel totals, and now the ledger) and each spelled the
  day itself. `Grants` stops formatting dates entirely; `SessionGuard` and
  `Stats` take theirs from `FamilyDay`, in the compact `yyyyMMdd` spelling
  their prefs have always used, so **no install needs a migration**.
- **The shipped bug it fixes.** `rolloverIfNewDay` rolled on *any* difference
  (`if (previous != today)`), so a clock stepped backwards a day zeroed
  `dailyWatchedMs` and handed a child a whole second budget — today, in the
  device-local mode every family is on. `FamilyDay.rollover` keeps the later of
  the two, so the day moves forward or not at all. Only a device's **own** day
  ratchets: `max(localDay, seenDay)` propagated through a merge is how one
  television with a wrong RTC walks a whole household's day forward with no way
  back, which is the design `PLAN-hub-parity` D1 rejects.
- **`Whitelist.homeZone`** — an IANA id on the loose `settings` unit, omitted
  when null, in the fingerprint only when set. A family that never names one
  keeps its bytes and its hash, asserted by the four canonical
  `ConfigStoreJsonTest` tests rather than promised. Nothing on the phone reads
  it yet; the hub does, which is what lets a container say what day it is in
  the family's house instead of in UTC.
- **`UsageLedger` in `:core`: a counter, and therefore not in `config.json`.**
  Grow-only cells keyed `(kid, day, device)`, joined by per-cell `max`.
  `merge(a, b)` takes two ledgers and **no clock** — a `today` parameter would
  sail past the clock grep while being exactly the clock that grep exists to
  keep out, and the laws only hold for a *fixed* today, which is never the case
  across two devices whose windows differ. The window is `trim()`, it is local,
  and it applies only to what a device stores for itself. A reader drops cells
  dated more than a day ahead of its own, so a fast clock cannot pre-spend.
- **Its own file and its own lock on both faces.** `WatchLedgerStore` over
  `files/usage.json`, `HubUsage` over `/data/usage.json`. `HubStore` is
  untouched, and `HubUsageTest` asserts that config commits, the fingerprint,
  `sync.log` and the five-slot version ring are unmoved by twenty rounds of
  watch traffic — rather than a comment claiming it.
- **`GET|POST /usage` on both faces, both directions device-initiated**, so the
  hub still holds no credential on anything and **guard 7 is untouched**. A
  device authors its own cells at the moment it is asked (every kid's tally,
  not just the one on screen), which needs no scheduler and no wakelock. The
  hub keeps only the cells the caller authored, from the `X-Device-Id` it
  presented; a device accepts a peer's, because a parent's phone relaying the
  TV's minutes to the tablet is the only path a hubless family has, and that
  caller can already rewrite the whole config. Either way `max` only goes up: a
  bad actor can cost a kid minutes, never grant them, and the correction
  downward is a **grant**.
- **`SessionGuard` gains exactly one summand.** `spentTodayMs()` is own +
  peers, and it is now the only answer in the file to "what has this kid
  spent". The peers' half is zero for every family, because `budgetScope` does
  not exist yet — the arithmetic is what it has always been.
- **Guard 43** (one spelling of a day: `LocalDate`/`SimpleDateFormat` only in
  `FamilyDay` inside `:core`, and no `"yyyyMMdd"` literal in the two stores
  that enforce with one — the nine display-only formatters are deliberately
  left alone), **guard 44** (the ledger is named nowhere in `ConfigMerge`,
  `ConfigStamp`, `ConfigJson`, `Whitelist`, `SyncDecision` or `HubStore`, and
  `merge()`'s signature is pinned), **guard 45** (`dailyWatchedMs` has exactly
  one reader — copied from guard 16's shape, because seven enforcement sites
  and half a dozen screens deriving from it is how a home screen ends up
  promising forty minutes in front of a player that stops at ten). All three in
  both scripts, negative-tested in both.
- **Guard 27 amended rather than extended.** It said "the hub reads no
  calendar" while its code would have passed a `ZoneId.of(cfg.homeZone)` call —
  the prose was ahead of the check. It now argues what it enforces: (a) the
  container's own calendar stays unreachable, and (b) a zone reaches the box
  from the family's config or not at all.

Still not built, and ROADMAP §J now says so: `Limits.budgetScope`, the
confirm-with-the-number dialog, the "where the number came from" copy, the
session-start fetch and per-minute report, and the version-skew line for a
half-upgraded fleet.

### The kid gets an origin of their own (web player, steps 2–3)

Step 1 proved a NAS can carry video bytes. These two steps are about **who is
allowed to ask for them**, and the answer starts with a second listener.

- **A second port, not a path** (`HubKidServer`, `YOSEMITE_KIDS_KID_PORT`,
  8766). This is the load-bearing decision and it was made against a specific
  attack rather than on principle: put the kid's page at `/kid/` on the
  console's origin and a script on it can `fetch("/api/config", {method:
  "POST"})` and pass **every gate this hub has**. `sameOrigin()` compares
  Origin's host to Host and they match; the parent's session cookie rides
  along, because cookie `Path` is matched against the request URI and not
  against the page that made the request; `SameSite=Strict` is satisfied,
  because it genuinely is the same site; and `HttpOnly` is irrelevant, because
  the page never reads the cookie — it only sends it. On a shared family iPad
  with a parent signed in, that is a page a child opened rewriting the
  family's blocks, limits and AI settings, and minting itself bonus minutes.
  Two ports make them two origins and every one of those gates starts working
  for the family instead of against them. **No CORS header is sent anywhere**,
  so a reply that is not refused still cannot be read.
- **The kid listener answers exactly five paths** — `/claim`, `/whoami`,
  `/media`, `/`, `/kid-tokens.css` — and 404s everything else. Deliberately not
  a catch-all: on the console an unknown path is a parent's typo and the page
  is the kindest answer; here it is somebody looking for the console, and a
  200 carrying a page is the answer that says keep looking.
- **`GET /media` moved to the kid origin**, where it was always going to
  belong, and **the `kid=` parameter is gone with the parser for it**. Whose
  rules apply is a property of the credential now, bound when the parent
  minted the code — a child who could name the kid could name their older
  sibling and watch on their bedtime, their budget and their block list.
- **Claim codes** (`HubBrowsers`, `/data/browsers.json`). A parent mints, in
  their own session, and says which child it is for; the tablet redeems on the
  kid origin and is handed a six-month `HttpOnly; SameSite=Strict` cookie.
  Same two halves as `/enrol` and `/approve`, same no-vowel alphabet (one
  copy, in `HubTokens.CODE_ALPHABET`), single use, ten minutes, five wrong
  guesses burn every live code. The hub mints every identifier; the browser
  mints nothing.
- **A throttle of its own**, and this is not tidiness: on one shared counter a
  six-year-old mistyping ten times locks their parent out of the console for
  fifteen minutes, then thirty, then an hour — a throttle causing the exact
  failure it exists to prevent. `HubRate`, ten a minute, no escalation, and
  nothing on the kid origin can reach `HubSessions` at all.
- **Revoke**, beside the device revoke: Devices → Watch in a browser →
  Remove, effective on that browser's next request. And **deleting a kid takes
  their browsers with them** — `limitsFor` an unknown kid is the family
  default, so a deleted child's tablet would otherwise carry on under the
  loosest rules in the house, with nothing thrown and nothing on screen.
- **A placeholder page** on the kid origin: a code box, the child's name, one
  video. No browse UI, no shelves, no styling beyond the generated stylesheet
  — step 4 builds the real one. It exists so the whole path can be proved in a
  browser rather than only in a test, and it was: code typed in, cookie set,
  "Hello Ada", video playing through `/media`.
- **Guards 57–60**, both scripts, negative-tested in both: the kid origin
  serves exactly its five paths and nothing the console serves (bar the
  stylesheet and each origin's own root); every kid route has a row in
  `docs/LAN-API.md`; the compose file publishes the kid port through the same
  variable the process reads; one code alphabet; **no `Access-Control-` header
  anywhere in the module**; the kid throttle and the kid cookie are separate
  objects with separate names from the admin's; and every kid route resolves
  the claim cookie first, takes the child from it rather than from a query,
  and carries the three security headers.

### What an unfinished pre-play check means is the parent's to say (from upstream 69f59d6)

Upstream's v0.8.1 made the deep check's fail-open behaviour a setting, and
the fork adopts it: `ai.reviewIncompleteChecks`, off by default, so nothing
changes for a family that never opens it. Turned on, a check that cannot
finish — no description, no English subtitles, a provider that errors, an
answer past the 20 s bound — stores a plain `REVIEW` instead of allowing the
attempt, and the video waits in *Waiting for your OK*.

A plain `REVIEW` and not a verdict kind of its own, which is the whole reason
the port is small: the parent queue, the per-kid rulings, the LAN
verdict-sharing and the hub's `/verdicts` all already know what to do with
one.

Three places the fork does it differently from upstream, each for a reason
the fork already had:

- **One toggle, not two radio buttons.** The manifest (`SettingsSurface`)
  owns the words, the hub's generic renderer draws a `TOGGLE` with no hub
  code at all, and guard 26 then holds both faces to it. Upstream's radio
  pair would have been a `CUSTOM` control and hand-written twice.
- **The field is a judging input** — added to `SettingsForm.toConfig`'s
  `judgingChanged` (as upstream does) *and* to `ConfigMerge.judgingInputs`
  (which upstream has no counterpart for). Devices trade verdicts keyed on
  `rulesVersion`, so two devices answering this differently must not share a
  number; and without the bump, videos held while it was on stay held after
  a parent turns it off, one queue tap at a time.
- **Append-only-when-set** in both the JSON and the fingerprint
  (`;AI_HOLD_INCOMPLETE:true`), the discipline every field added after a
  family's config already existed follows here, so a household that never
  turns it on keeps a byte-identical `ai` object and the hash it had.

### One origin, the kid's own password, a trail back to the hub, and rows a parent arranges (1.8.0)

Seven phases against four goals the owner set in one sitting: the three faces
consistent, the tree cheaper to work in, the home screen modular, and a way
to find out what went wrong on a device *after* it went wrong. Two
corrections shaped it: the television keeps its QR-only settings screen and
its rail — consistent and adapted, never given a settings page of its own —
and the hub's two faces had to become **one origin**, with the code box gone.

- **Housekeeping first.** Finished plans moved to `docs/archive/` (with a
  README saying what each was), the kid-player design files to
  `docs/design/kid-player/`, and CLAUDE.md lost the paragraphs that were
  history rather than instruction. `docs/GUARDS.md` is now generated from the
  guard headings by `scripts/guard-index.sh` (guard 67 fails the gate when it
  is stale), and `scripts/guard-canary.sh` mutates a detached worktree to
  prove every guard from 56 on can actually fail (guard 65 holds each new
  guard to having a case; CI runs the canary after the guards). The
  `yosemite-kids-map` skill says which file to open for each kind of change
  and which never to read end to end.
- **The manifests say what the television does and what the browser obeys.**
  `KidSurfaceDef.onTv`/`tvWhy` records how the TV adapts each kid surface or
  why it skips it (guard 62); `SettingsControl.honouredBy`/`honourWhy` says
  which kid faces *obey* a setting, and a setting the browser claims to
  honour must be read by the hub's kid routes (guard 69). The gate prints
  both lists on every run. Two settings the browser cannot honour are
  declared with the reason instead of faked: *Show when a video came out*
  (the index carries no publish date) and *Channel page layout* (no view
  counts to sort "popular" by).
- **One origin.** The kid page moved from a port of its own to `/kid` on the
  console's port; the second port is gone from the Dockerfile, the compose
  file and every doc, and the compose file says to drop the old line. The
  attack the second port was built against (a kid-page script posting to
  `/api/config` with the parent's cookie riding along) is answered without
  it: the parent's session is an `X-Session` **header**, kept in the
  console's `sessionStorage` and sent only by requests the console makes, so
  there is no parent cookie for a kid-path request to carry; the kid's
  credential is a cookie scoped to `Path=/kid/` that the console's routes
  never read (`HubServer` never names it); the kid routes keep their own
  throttle (`HubRate`) and never reach `HubSessions`; and still no
  `Access-Control-` header anywhere. Guards 57–60 were rewritten for the new
  boundary and `HubKidBoundaryTest` walks it.
- **The kid signs in with a QR or their own password; the code box is gone.**
  The console's *Watch in a browser* card shows a QR whose link carries the
  one-time code, so a phone camera signs a tablet in with nothing typed. Or
  the kid opens `/kid`, sees *Who's watching?* — the kids who have a
  password, avatar and colour — picks themself and types it. The password is
  the parent's to set on the kid's page (`/api/kid-password`), 4–64
  characters, distinct from the parent's, stored as PBKDF2-HMAC-SHA256 at
  210 000 iterations by the one `Pbkdf2` in `:core` the parent's password now
  uses too, and pushed to devices as a record, never as text. Five wrong
  guesses lock that kid for a minute, doubling to fifteen (`HubKidLock`),
  per kid, so a sibling's typing never locks the parent or the other kid out.
  **Every browser signs in again after this upgrade**; the old cookie was
  bound to the old origin.
- **A trail back to the hub.** `Diag` in `:app` keeps the last sixty
  warnings, errors and the uncaught exception that killed the process, in
  SharedPreferences, and no code outside it may call `Log.w`/`Log.e` (guard
  68). Nothing is sent while nothing goes wrong: the ring is drained on the
  device's next contact with a hub (`ConfigSync.sweep` → `POST /report`, up
  to fifty entries, each cut at 500 characters), and the browser reports
  `window.error`, `unhandledrejection` and a `/kid/media` 5xx over
  `POST /kid/report`. `HubReports` keeps the last three hundred in memory,
  prints each as `report <from> <who> (<kind> <version>) [<level>] <msg>` so
  `docker logs` has it, and the Devices page shows a *Device log* card.
- **Consistency sweep on the web.** Channel and search rows are paged by the
  family's page size with the same *Show more* the phone has; the channel
  row order (*A to Z*, *Most watched*, …) is honoured; every shelf title is
  `homeShelfTitle` in `:core`, one spelling for three faces.
- **The rows are the parent's to arrange.** `Whitelist.homeRows` holds one
  `HomeRow` per shelf per home (a kid's, or the family's), in the pinned
  hero's shape for the pinned hero's reasons: a merge unit each
  (`home.row|<kid>|<shelf>`, absent-safe, tombstoned with its kid), the
  position a rank so a move stamps only what moved, on/off a flag beside it,
  nothing written and nothing hashed while the family never arranges (the
  hash a 1.7.0 device computes stays equal), and a shelf this build has not
  got carried but not drawn. One function arranges a home —
  `HomeRows.withOrder` — and guard 70 holds both editors to it: *Rows on the
  home screen* on the console's Listing page and `HomeRowsEditor` on the
  phone's, both declared once as `listing-rows`, both with move, hide and
  *Reset to default*. Every face reads the result through
  `Whitelist.homeRowsFor`. Update every device before arranging: a 1.7.0
  device carries the rows but draws the default.

Verified: the JVM suites in `:core`, `:hub` and `:app` (new:
`KidWebPasswordTest`, `HomeRowsConfigTest`, `DiagRingTest`,
`HubKidBoundaryTest`, `HubReportsTest`, `HubKidListingTest`); guards in both
scripts; the canary on every case it covers; and in a browser against a
throwaway hub: the password sign-in with a wrong guess refused, the QR link,
the *Device log* card receiving a browser report, and the rows editor —
Videos moved above Suggested and Watched lately hidden on the console, the
kid's home served in that order. The one-origin hub image has run on the
owner's NAS since the third phase. Device-side reporting is proven by unit
test only until the 1.8.0 APK is on the fleet.

Seen on the NAS and left for the owner: the crawl keeps retrying one channel
YouTube answers "The playlist does not exist" for, backing off each time; it
wants removing from the list or a crawl-side "gone" verdict.

### The index keeps its dates, the player keeps its streams, and the last two web surfaces (1.9.0)

The round after the four goals, started from one question the owner asked
about the release before it: *why can't the browser show a video's age when
the phone can — don't they read the same cached data?* They should have. Both
faces run the same extractor from `:crawl`, and it handed the crawl an upload
date and a view count for every video; the crawl wrote five keys to disk and
dropped both. Fixing that (roadmap §2M, written up a week earlier as "a
smaller job than it sounds") is the first item, and most of the rest of the
round is what it unlocked or what the same week's NAS log turned up.

- **The index keeps the date and the count** (`IndexedVideo.viewCount`,
  `publishedAt`; keys `v` and `p`, absent on a row an older build wrote, so
  a five-key file still parses and no re-crawl is ordered). A row the index
  already holds *learns* both from the next crawl of its page — the count
  every time, the date once — because page 1 is re-read on every delta
  crawl and the harvest walks the rest. `ChannelIndexDateTest` is the gate.
- **Every face honours the two settings that needed them.** *Show when a
  video came out* and *Channel page layout* were declared unhonoured by the
  browser with the reason; both are `honouredBy` every kid face now, and
  guard 69 holds the hub's kid routes to reading them. The hub composes the
  "Channel · 3 days ago" line itself, with `metaLine` and `relativeAge`
  moved from `:app` to `:core` (`ui/VideoMeta.kt`), so the page draws it
  verbatim and three faces say it one way. "Latest video" joined the
  browser's Channels grid, read the way the phone reads its cache; "Newest"
  joined the phone's search chips (`SearchOrder.RECENT`), with an undated
  row sorting last and never hidden. The gate's "kid-facing settings the
  browser does not honour" line is empty for the first time.
- **A channel YouTube says is gone is marked, not the reason every crawl
  fails.** The NAS log showed it: one channel answered "The playlist does not
  exist" on every run, and with the other fifty-three complete it was the
  only thing a run ever attempted, so every run was a failed run and the hub
  backed the whole crawl off — fifteen minutes doubling to six hours — for a
  verdict that was never going to change. `IndexCrawlRun` now tells
  YouTube's verdict (a `ContentNotAvailableException` anywhere in the cause
  chain) from a failure that might be this box's, marks the source gone with
  YouTube's own words, leaves it alone for a day and counts it as neither a
  page nor a failure; a page that later arrives clears the mark. The console
  tags the row *Not on YouTube* with the reason and the choice (remove it,
  or keep the videos already listed), the Devices page lists it under the
  index, the phone's index list says the same, and the hub logs one line.
- **The player keeps the streams it resolved** (`PlaybackCache` in `:crawl`,
  in front of `resolvePlayback`): twenty minutes, sixty-four entries, the
  hub's own numbers from 1.4.0. Keyed on the page URL *and* the ceiling, or
  the quality picker would be handed the old streams; forgotten whole on a
  playback failure, or a stale URL would be a video that cannot play handed
  back on every retry; bypassed by downloads. A replay, "Up next" back to a
  video just watched, or a quality step back to a ceiling already resolved
  now costs no extraction — a request YouTube's bot detection watches and a
  multi-second wait in front of a child.
- **The player stops walking the queue when YouTube stops answering**
  (`PlaybackBreaker`). One failure is the video's and is skipped as before;
  the second in a row is YouTube's, and the player stops rather than
  sprinting through the lineup one refused extraction after another — the
  burst a bot wall looks for. The card says so in a second sentence, *Try
  again* resets the count because a person chose to, and a video that plays
  resets it too.
- **The channel page's rails hold their slot** (roadmap 8C.2, the other half
  of the family's week-one report). New for you appeared only with three or
  more new videos, so a channel with two looked half-built; the playlist
  strip appeared a second after the paint and pushed the grid down. The
  New-for-you slot is always drawn — a short row, a line saying there is
  nothing new, or a skeleton — and the strip has a skeleton in its own keys
  until the listing answers either way (`HomeState.channelPlaylistsPending`).
  Guard 71 holds both, in both gates.
- **Two of the three kid surfaces the gate still named reached the
  browser.** *Watched* — a channel's finished videos, newest-watched first
  through the phone's own `orderByWatched`, at `/kid/channel?watched=1`,
  with a "Watched · N" link on the page. *The search page* — the page before
  a query: recent searches as chips with the × that forgets one, kept per kid
  on the hub (`HubKidSearches`) where the phone keeps them on the device,
  both through `RecentSearches` in `:core`; and the order chips — Best match
  · Newest · Shortest · Mix it up — through `SearchOrder` with one set of
  words (`SearchOrder.label`) on every face. A search-as-you-type page must
  not remember every prefix, so a search joins the recents only when Enter
  or a chip says the child meant it (`remember=1`).
- **Playlists in the browser, and the crawl that makes them possible.** The
  phone lists a channel's playlists live when its page opens; the hub carries
  no live extraction on the kid's path and the page may not decide anything,
  so the surface waited on the crawler. `PlaylistCrawlRun` in `:crawl` now
  indexes each channel's playlists — the first twenty, the first page of
  each, refreshed once a day, paced like the index crawl with its own budget
  of twelve fetches a run, carried across runs when it runs out mid-channel,
  never while the index crawl is backing off, and never for a channel
  YouTube says is gone. The kid page draws the strip on a channel, "See all",
  and a playlist's own page from `/kid/playlists` and `/kid/playlist`; a
  playlist shows a kid exactly the rows the channel page would, matched
  across their whole catalogue, so it is never a way around a block. The
  gate names no kid surface the browser lacks any more. Parent-picked
  playlists as rows above the grid stay the phone's.
- **The phone's Who's watching? accepts the kid's browser password.** Beside
  the four-press PIN, never instead of it: the PIN is the phone's lock and a
  kid with a password and no PIN opens with a tap as before. *Use the
  password instead* appears only when there is one to use (`pickerGate`,
  `PickerGateTest`), and it is verified against the same PBKDF2 record the
  hub verifies.
- **Three canaries could not fire** and were caught by the first Linux run
  of the canary in CI, where it takes a minute rather than the hour it takes
  on Windows: guard 56 counted files rather than declarations, guard 70 was
  satisfied by a function existing rather than being called, and guard 42
  had the same shape. All three tightened. The hand-trip habit that tripped
  guards locally also cost one commit its edits (`git checkout --` restores
  from the index, not from the working copy); the canary in a worktree is
  the tool for that, and the lesson is in the working notes.

Verified: the JVM suites in `:core`, `:crawl`, `:hub` and `:app` (new:
`ChannelIndexDateTest`, `PlaybackCacheTest`, `HubKidSearchTest`,
`RecentSearchesTest`, `PlaybackBreakerTest`, `PickerGateTest`,
`PlaylistCrawlRunTest`, `HubKidPlaylistTest`, and the
gone-source and Newest cases in `IndexCrawlRunTest` and `SearchOrderTest`);
both gates; the canary in CI. The channel-page rails, the breaker's card and
the password screen are Compose and were compiled, not driven: they want a
finger on the phone before anyone calls them finished.

### The crawl minds its manners, the playlist strip actually draws, and the TV stays reachable (1.10.0)

The housekeeping round after 1.9.0, from the list the owner agreed to.

- **The playlist strip was never drawn.** 1.9.0's kid page defined the
  playlist card, the See-all page and the playlist page, and the routes were
  driven on a throwaway hub — but the edit that puts the strip on a channel
  page had silently missed its anchor, so a browser's channel page showed no
  playlists at all. It draws now, and above it the **parent-picked rows**
  (`playlistRows` on `/kid/channel`): the parent's picks first, then the
  channel's own first playlists to make three, each the playlist's
  unfinished videos with Shorts dropped — the phone's `playlistShelves`,
  answered by the hub with the same three rules in the same order.
- **The crawl stands aside while a child is watching** (roadmap K.2). The
  hub asks `HubWatchMeter.anyoneWatching` — a beat inside the last two
  minutes — and skips its tick with "a child is watching", never counted as
  a failure and never backing off; the phone's worker does the same off
  `NowPlaying`. A video that stalls is worse than an index finished a
  quarter of an hour later.
- **The pacing numbers are held, not merely written.** `CrawlPacingTest`
  pins four seconds between fetches, sixty pages a run and the playlist
  pass's twelve; guard 72 refuses any production caller that passes a pace
  of its own, because every crawl test runs with `delayMs = 0` to be fast
  and a real caller doing the same would have passed the suite.
- **A television keeps answering the phone after the app is closed**
  (`LanService`). The LAN server was always process-wide and outlived its
  screen; what it did not outlive was the process, which a Chromecast
  reclaims from a cached app within minutes, so a push, a grant or "Play on
  TV" sent to a television on its launcher went unanswered until the app
  was next opened. A foreground service — televisions only, `specialUse`
  from Android 14 because a data-sync service is capped at six hours a day
  from Android 15 — keeps the process off that list and rebuilds the server
  from `buildLanServer` if the system restarts it. The server's wiring moved
  out of `MainActivity` into `LanServers.kt` for that, unchanged. **Untested
  on the television**: it compiled and the phone path is the old path, but
  the service itself wants a real evening on the Chromecast.
- **The NAS follows CI now.** The running container turned out to be the
  `latest` image CI publishes from `main`, recreated by the Synology side
  rather than by the hand-built tags this fork had been deploying; the two
  were the same tree. The old tags are gone from the NAS and the compose file
  is left saying `latest`, which is what it should say.

Verified: the JVM suites in `:core`, `:crawl`, `:hub` and `:app` (new:
`CrawlPacingTest`, `HubKidPlaylistTest`'s rows case, the watching cases in
`HubCrawlTest` and `HubWatchMeterTest`); both gates; the canary in CI. The
strip and rows were driven on a throwaway hub against a seeded index, this
time as the page, not only the routes.

### HD in a browser, a home the parent composes, and a channel to keep (1.11.0)

The round after 1.10.0, from the list the owner agreed to ("do all except
3 and 5" — kid-to-parent requests and taking back a grant stay on the
backlog).

- **HD in a browser.** The kid page played the muxed progressive stream,
  which YouTube caps around 360p, because HD on YouTube is a video-only
  track and an audio-only track merged at playback — ExoPlayer's job in the
  app, and beyond a plain `<video>`. Media Source Extensions can do it given
  a manifest, so the hub writes one by hand (`HubDash`, `GET /kid/dash`)
  from the renditions' init and index byte ranges — every mp4 rendition up
  to 1080p, the best original-language mp4 audio, mp4 only because Safari
  plays no WebM and an iPad is the browser this exists for — and the page
  plays it through dash.js 4.7.4, vendored into the jar and served from the
  hub's own origin (`/kid/dash.js`), never a CDN. **Every URL in the
  manifest is `/kid/media?v=…&s=<itag>`**: the same proxy, the same
  per-chunk `mayPlay` gate, so a block still lands mid-video at 1080p and a
  browser never holds a googlevideo URL. A rendition reply carries a whole
  segment (`HubMedia.MAX_SEGMENT_BYTES`) rather than one chunk, because a
  media-source player appends what it gets as the segment it asked for. A
  browser without media sources, a video with no mp4 pair, or any error at
  all falls back to the muxed stream — the same video, smaller, never
  nothing, with the same refusal words. `HubDashTest` holds the manifest's
  shape; `HubMediaRouteTest` holds the gate on the new route and on `s=`.
  Two things the first drive taught: a segment request runs on a pool and
  slots of its own (`MAX_CONCURRENT_SEGMENTS`), because dash.js opens every
  rendition's index at once and three stream slots meant four `503`s before
  the first frame; and dash.js discards any video response faster than
  50 ms as a browser-cache hit, which through a LAN hub is every 144p
  segment, so it had played 144p for a minute with fifteen seconds buffered
  — the page sets that threshold to zero and starts at a watchable bitrate.
- **Rows a parent adds to the home.** The rows editor — console and phone —
  could only reorder and hide the fixed shelves. It can now **add a row**:
  a channel, or a playlist the crawl has listed, as `HomeRowKind` ids
  (`playlist:<id>`, `channel:<id>`) in the same `HomeRow` list, with a
  Remove where a shelf only has Hide. The hub answers them in `/kid/home`'s
  `custom` map (the playlist's or channel's unfinished videos, `PLAYLIST_ROW_VIDEOS`
  each) and the page draws them as rails; the phone fetches its own
  (`MainViewModel.customRows`, `HomeState.customRows`) and draws them among
  the shelves. `ConfigJson.rowsFromJson` accepts the new ids (it was
  dropping them at the door, which `HomeRowsConfigTest`'s new case caught)
  and the fingerprint sees them, so a row added on the phone reaches the TV.
- **A heart on a channel, on every face.** The hold menu on a channel tile —
  a long press, or OK held on the remote — favourites it; favourites float
  to the front of every channel order through one rule in `:crawl`
  (`orderChannels`, favourites first) and wear a heart on the tile. Stored as
  the hearts on videos are (`SavedListStore.CHANNELS`, `POST /kid/list` with
  list `channels`), so it converges across devices through the payload that
  already exists. The hub also feeds hearted videos to `SearchRank`, so they
  rank higher in the browser's search as they do on the phone.
- **The You tab redraws on a heart.** A heart pressed on the You tab's own
  shelf now redraws the shelf, on the phone and in the browser; the lists
  always persisted and every visit re-read them, which is what hid the gap.
  Guard 73, with a canary.
- **The console can pause the crawl**, and says what a failure means. A
  Pause-the-crawl switch on the Devices page (`POST /api/crawl`, a file on
  the volume so it holds across restarts; the playlist pass stops with it),
  and under a failed run both the console and the phone say what a red dot
  usually means — the extractor needs an update — and what to do if the
  newest build still fails.
- **Channels wear their own art.** The crawl keeps a channel's picture and
  banner from the first page it fetches (`ChannelIndex.setArt`); a browser's
  channel cards wear the channel, its hero wears the banner, and the phone's
  hero does too. `ChannelIndexArtTest`.
- **TV polish.** `TvTopChips` is deleted — the rail has been the
  television's only menu for a season. The Chromecast start-up measurement
  still needs a remote in front of the real set.
- **Housekeeping.** Two em-dashes on `LAN-API.md`'s playlist rows had been
  double-encoded since 1.9.0; fixed.

### The review, and what it found (1.12.0)

A measure-and-harden round rather than a feature one. Three releases in a
fortnight had added playlists, HD in a browser, rows a parent composes,
favourites and a television service, and almost none of it had been touched on
the family's own devices — while two page-level defects had shipped that
nothing in the repo could have caught. So seven readers went through the
codebase by dimension (structure, tests, the guards, diagnosability,
robustness, the docs, the parent console), every finding verified against the
code. `docs/ROADMAP.md` §9 is the plan and §9G the findings; what follows is
what was fixed.

The pattern the review found is worth stating, because it is not a scatter of
unrelated bugs: **a discipline applied on one side of a pair and not the
other.** The hub reads `KidSurface`; the phone hard-codes the same shelves. The
hub renders refusals through `KidWords`; the phone writes its own sentences.
Eleven stores write atomically; the index did not. The kid page reports its own
errors; the console reported nothing. Guard 57 learned that a route pattern
must allow digits; guards 14, 22, 29 and 30 did not.

- **The index could lose a channel's back catalogue, permanently.** It had no
  lock and no atomic write: every mutator built a new map from its own
  in-memory copy and wrote that whole view to disk, while the crawl worker
  holds one instance for the length of a run and `POST /index` builds a fresh
  one per request — so the later write erased the other's source. Then a torn
  source file read as an empty channel, `addVideos` wrote the new page back
  *as* the whole source, and the cursor still pointed past the pages that were
  gone. All of it silent: a source that reads as never-crawled looks exactly
  like one the crawl has not reached yet. Now one process-wide lock, a
  read-modify-write that goes to disk for the truth, `writeAtomically` in
  `:crawl` (temp file, `fsync`, atomic rename, and no delete-first fallback —
  four stores had grown one, and it takes the only good copy when the second
  rename fails too), and a file that will not parse is kept as `.corrupt` with
  its cursor dropped so the crawl rebuilds it from page one. `IndexDurabilityTest`.
- **The circuit breaker could never trip for the failure it exists for.**
  `PlaybackBreaker` counts consecutive failures, and the counter was reset
  when a *resolve* succeeded — before anything played. So a run of decode
  failures, which is the shape an expired link, a throttled one and YouTube's
  bot wall all take, walked the whole queue at full speed with a fresh
  extraction per video: precisely the burst the class was written to stop. It
  resets on frames now.
- **The home timezone was a dead end nobody could leave.** The hub fails closed
  for any kid with a bedtime or a budget while `homeZone` is null, and nothing
  in the product ever wrote the field — no editor on any face, no default. The
  first parent to set either rule would have turned the browser into "Something
  is not set up. Ask a grown-up", with no switch anywhere to fix it and no log
  line naming it. The phone now stamps its own zone the moment a rule needs one
  (`Budget.dayMatters`, `HomeZoneTest`), which is what `SettingsSurface` said
  the field was for all along.
- **The console could fail in silence, and often did.** `button()` swallowed
  every rejection — approve, revoke, restore, add a channel, mint a code, pause
  the crawl — sixteen save paths dropped the promise entirely, and a toggle
  whose save failed was left disabled and flipped to the value that had *not*
  been written. The page registered neither `window.onerror` nor
  `unhandledrejection`, unlike the kid page, so none of it reached a log either.
  There is one `saving()` wrapper now that restores the control and names the
  hub's own reason, a banner every failure reaches, and both handlers.
- **"Download a backup" could never work.** It was the one `/api` call that
  bypassed the fetch wrapper, so it sent no session header, the hub answered
  401, and the handler signed the parent out — every time they pressed it.
- **A hub that did not answer lied to both faces.** The console told a parent
  their hub was unclaimed, which invites setting a second password on a hub
  that has one; the kid page showed a signed-in child the sign-in screen, which
  reads as being thrown out of their own app and which a child cannot fix. Both
  now distinguish a transport failure from a refusal, and the child's says
  "Just a moment…" with a Try again.
- **A refusal reaches a parent.** `HubPolicy.Decision.detail` says in its own
  KDoc that it is written for a parent's log, and it reached no log at all — so
  "why will this video not play on the tablet" was answerable by nobody. One
  line per kid and reason per ten minutes, into the report ring the console
  already draws.
- **The kid page's duration was wrong past an hour**: a 1:05:30 video read
  "1:05" in a browser and "1:05:30" on the phone beside it.
- **The browser is tested now.** `scripts/smoke/smoke.mjs` drives the kid page
  in a real browser on every push: it signs in with a child's password, checks
  the home, the channels tab, a channel page, You and search all draw cards,
  and then asks for a video that child may not see — which must come back 403
  with a policy reason, because a 502 would mean the gate let it through and
  the hub went looking for a stream. The hub it drives is `SmokeHub`, the same
  `HubServer` with the election and the crawl left null, so the check never
  touches the network. This is the class of defect that shipped twice: 1.9.0's
  playlist strip, whose one insert missed its anchor, and 1.11.0's player,
  which sat at 144p with a full buffer.
- **A Content-Security-Policy on both origins**, now that the kid page loads a
  script: `default-src 'none'` and then only what each page uses. Both
  listeners had kept byte-identical copies of the security-header writer three
  lines below a KDoc saying a security check must not be copied; there is one
  `HubHttp` now, which is where the policy went. `/api` replies carry
  `no-store` as the kid origin's always have — the console's had no cache
  directive at all, `/api/backup` included, and that is the whole family
  config.
- **The gate could not see a route with a digit or a dot.** Four guards read
  the route lists with a pattern that allowed neither, which is the exact class
  that made guard 57 fail open once; the lesson had reached 57 alone. Guard 30
  was additionally blind to every asset route. And the clause that stops a
  child naming their older sibling in a URL was mistyped identically in both
  gate scripts, so it caught `&kid=` and missed `?kid=` — the spelling a first
  query parameter takes. A typo shared by both mirrors is invisible to the
  mirror check and to the canary, which is the argument for deriving that list
  rather than keeping it by hand.
- **Docs.** `budgetScope` was documented as unbuilt in four places and shipped
  in 1.3.0; §2A described a foreground service that shipped in 1.10.0; the
  release steps in `CLAUDE.md` omitted the two whose omission shipped a broken
  1.2.0; the architecture tree was missing twenty files and had two stores in
  the wrong module; and the map skill could not lead a reader to anything added
  since 1.9.0. All corrected.

Verified: the JVM suites in `:core`, `:crawl`, `:hub` and `:app` (new:
`IndexDurabilityTest`, `HomeZoneTest`), both gates, the canary in CI, and the
new browser walk against a seeded hub.

### The review's list, worked down (1.13.0)

The round after 1.12.0, continuing through `docs/ROADMAP.md` §9G rather than
adding features. Eleven of the review's findings closed, four of them with a
guard so they cannot come back, and two of Phase 4's features landed on the
way.

**What a child or a parent will notice**

- **The countdown's last minute stopped being a lie.** A child told "1 minute
  left" watched it say so for sixty seconds and then stopped mid-sentence — the
  ending happening *to* them rather than one they could see coming. It was
  deferred for a good reason: `UsageLedger` counts whole minutes, so seconds in
  the page would have been invented precision. The sub-minute time was in
  `HubWatchMeter` all along — it accrues in milliseconds and credits only whole
  minutes, so up to 59 seconds are watched, known, and not yet written down.
  `unsettledMs` exposes that remainder read-only, and `KidWords.timeLeft`,
  which could always count seconds under the last minute, finally has something
  to count with. `/kid/progress` had built its own copy of these six fields
  three lines under a comment warning that "two shapes for one pill is how a
  countdown comes to say different things on the same screen a minute apart";
  there were two shapes, and when the seconds arrived only one of them got
  them. Both routes call one builder now.
- **A child meets one vocabulary.** `KidWords` in `:core` exists so the
  television, the phone and the tablet say the same thing about the same rule,
  and only the hub ever called it. A child paused at teatime read "A parent
  paused screen time for today" on the television and "A grown-up paused
  watching" on the tablet, about one rule, in one afternoon. They cannot tell
  those are the same thing; they can only tell that one device is wrong. Guard
  51 holds it, with a canary case.
- **The spinner is gone.** A spinner says "something is happening somewhere"; a
  skeleton says what is coming and where it will be, so nothing moves under a
  finger when it arrives. The home gets its own shape — a hero, then rails —
  rather than a grid of cards, and the television, which had a spinner in the
  middle of a 55-inch screen, gets both skeletons.
- **A hub that cannot read its settings says so**, and refuses every save until
  it is restored. See below; this is the one a family would have met as data
  loss.
- **Removing a channel asks first**, like every other destructive action on the
  console. And two messages a parent needed — "nothing there was recognised as
  a channel" and "too many codes are already out" — used to be written onto a
  node that the same click's refresh threw away, so both arrived as a blank
  card.

**Things that could have lost or hidden data**

- **A config the hub cannot read stopped being a family with nobody in it.**
  `HubStore.load()` has always refused to serve an unparseable `config.json` as
  an empty family, because that emptiness is what every device merges next —
  and then every caller swallowed the refusal into a plausible answer. One of
  them was dangerous: `edit()` read the config with a
  `getOrElse { Whitelist(emptyList(), emptySet()) }`, so a parent looking at an
  empty console and adding their channels back would have written that
  emptiness over the file. That fallback is gone, every console save is refused
  with a reason (except restoring, which is the way out), the console draws a
  banner before a parent tries, and `GET /health` carries `configOk`.
- **One unreadable channel costs that channel, not the family.** Every "bad
  config" test in the repo used whole-file garbage; the case nobody held was
  valid JSON with one element this build cannot read, which threw out of the
  whole parse. The pins, the grants and the home rows have always been lenient
  this way — the channels and the kids, the two that matter most, were not.
- **The parent PIN uses the product's one key derivation.** `HubPassword`'s
  KDoc has always said there is one KDF "rather than a hub copy and a phone
  copy that drift apart in cost". There were two, and they had drifted: the PIN
  guarding every setting in the house ran at 120,000 iterations beside the
  shared 210,000. Both older formats still verify and are re-stored at the
  shared cost on the spot, because a family locked out of the settings screen
  cannot use the settings screen to fix it.

**The alarm that could sleep through the fire**

- **The extractor canary can no longer give a green light for a run that tested
  nothing.** Every test in it skips itself when YouTube answers a datacenter IP
  with a bot wall, which is correct — but a job whose tests all skip *succeeds*,
  and the recovery step closed the breakage issue saying the canary was green
  again. It now requires a marker the test prints only after asserting
  something. It was also testing the app's requirements rather than the hub's:
  YouTube dropping the muxed stream format would have left it green while every
  video in every browser in the house returned 502. And the issue is assigned to
  the owner by name.

**Being a quieter neighbour**

- **The poster route is metered and narrowed.** It was credential-gated and
  host-checked, which stops a stranger and stops it being pointed at the house
  network — but not a claimed tablet asking as fast as it can, and nothing
  counted them. Its allow-list is the poster hosts now rather than the
  crawler's whole list.
- **Stream resolution has a single-flight.** dash.js opens every rendition's
  index at once, so an expired twenty-minute URL mid-video meant up to eight
  simultaneous extractions for one child pressing nothing.
- **A deleted playlist is parked until the daily refresh** instead of being
  asked for every fifteen minutes for ever.

**Saying what went wrong**

- **A device can say why it could not reach the television.** Six calls in
  `LanClient` were `runCatching { … }.getOrDefault(false)`, which makes a 403 —
  an approval dropped, where the only fix is re-pairing — indistinguishable
  from a television that is asleep, where the fix is nothing.
- **The diagnostic ring is readable on the device itself.** `Diag.entries()`
  had no caller anywhere: the ring filled, capped itself, drained to a hub, and
  a family without a hub — the documented default — could see none of it short
  of `adb logcat`. The phone's Devices page shows the last few, beside Recent
  changes: that card answers "did my edit stick", this one answers "and if it
  did not, why not".

**Housekeeping.** `SessionGuard` kept its own copy of `Budget`'s three
functions beside the shared ones, which is a home screen promising forty
minutes in front of a player that stops at ten; `:app` had *no* reference to
`KidSurface` and spelled its four You shelves out by hand; thirteen
declarations nothing called were deleted. Guards 50, 51 and 53 now exist —
two files cited "guard 50" while no such guard did — each in both gates with a
canary case, and eight of the oldest guards got their first cases.

## Next up, in order

> Superseded in part by `docs/ROADMAP.md` §9, which carries the 2026-09-17
> review and the phases it feeds. What is below is the older backlog, kept
> because several of its items are still open and are not repeated there.


These are decided, not merely noticed. The numbered lists below are the
older backlog and stay in their original order.

1. **Stats is the last group the hub cannot show.** Shipped hub-side in
   877170d, with no app change. The hub serves all six of the phone’s
   pages, and `SettingsSurface` is now keyed on config *fields* rather than
   section composables — which is what made Playback visible to the guard at
   all, since that page has no `*Section(` composable. Nine outstanding
   groups became one.

   Stats stays outstanding for a structural reason, not an unfinished one.
   The reason written here was "the hub never initiates", and that stopped
   being true when it started crawling and nudging; the one that replaced it
   is sharper. **Guard 7 holds the hub to two outbound destinations** —
   YouTube's hosts for the crawl, and the devices' `/sync-now` — in four
   negative-tested clauses. Polling `GET /stats` would need a credential on
   each device, which is the exact shape that guard exists to refuse. So the
   direction was settled as **push**: devices carry a digest on their
   existing sync (`ROADMAP.md` §4). Only the work is missing, and the round
   that would have carried it is the tabled budget (§J).

2. **A device is not reachable while Yosemite Kids is closed.** `LanServer` is
   constructed in `MainActivity` and dies with the process, so a sleeping TV
   answers nothing: a parent's phone shows it unreachable, "Play on TV"
   cannot wake it, and the hub's nudge does not land. Closing this needs a
   foreground service, which on Android 14 means a service type and a
   persistent notification — defensible on a mains-powered TV, a real cost on
   a phone, so it should be form-factor gated.

   This is now the only thing left between a hub edit and a device, because
   the two layers under it are built. `POST /sync-now` lands a hub edit on an
   awake device in about a second: the hub records where each device calls
   from (`HubTokens.noteSeen`, plus the `X-Device-Port` the device states,
   since an inbound source port says nothing about where it listens) and
   announces a real fingerprint change. `ConfigSyncWorker` catches a sleeping
   device within WorkManager's 15-minute floor, with the reconcile lifted to
   `data/ConfigSync.kt` and `rediscover` backing off per device to a six-hour
   cap, never sweeping for a hub at all.

   What is missing is the middle: a device that is off right now and will not
   tick for another ten minutes.

3. **Two TVs and a hub introduce races the single-initiator design did not
   have.** Config still converges — the merge takes no clock and the hub holds
   one lock across read-merge-write — but `peerBehind` can be computed against
   a document another TV has already changed, producing pushes that only the
   next sweep tidies. The hub also serves on a fixed pool of four threads with
   no backoff anywhere.

4. ~~**Show build version, last sync and role on every device row**, on both
   faces.~~ **Done.** The phone's rows and pages have carried version and
   status since 1.0.4, "Update now" since 1.0.5, and the hub's device page
   renders `HubTokens`' address and last-seen time from 1.0.7 — the half that
   had been recorded and never displayed.

5. **"Recently added" vs "new".** Needs an `addedAt` on channels; today a
   channel added months ago with a fresh upload reads like one added
   yesterday.

6. **"More like this" on the player page.** The suggestion engine already
   produces the rows; the player has nowhere to show them.

7. **A key cleared on one device does not propagate.** Deliberate: a blank
   incoming key cannot be told apart from a peer that holds none, and treating
   blank as an instruction would let a hub wipe the key on first contact.
   Fixing it properly needs absent and empty to travel differently.

8. **An updatedAt-only merge still writes.** `changedLocally` compares the
   whole document including `updatedAt`, so merging with a peer whose clock is
   ahead produces a write and a push carrying no information.
## Review findings not acted on (backlog, roughly in order)

Kid side:

1. **Skeleton tiles** instead of one spinner while home/channel loads (the
   debug build on the emulator sat on a spinner for ~13 s; release is faster,
   but a shimmer grid reads as progress).
2. **Brightness/volume swipe** in the player. (Picture-in-picture shipped in
   round four; the listen-mode decision was: a visible PiP window never
   enters listen mode, screen-off in it pauses.)
3. **Sleep timer / "stop after this one"**.
4. **Profile picker → home transition** (avatar zooms into the header chip).
5. A kid-scale **search page** (big recent-search chips); today the field
   opens inline with no clear button.
6. Move kid-facing strings into `strings.xml` for translation.

Backend:

7. **mDNS/NSD advertisement** of the LAN server so a moved TV is found by
   identity instead of a /24 sweep (also covers /16 and IPv6).
8. **Grants and pause pushes are fire-and-forget**: a sleeping TV misses them
   until the 5-minute reconcile. Carry bonus minutes in the config, or keep a
   phone-side outbox.
9. **Deleted kids' watch state ping-pongs** between devices forever
   (`WatchSync.mergeJson` auto-creates namespaces for unknown ids).
10. **Multi-admin conflicts** are newest-wins wholesale; a per-section merge
    (union of blocks/allows with tombstones) would be safer.
11. **Backup rules** only cover the unsuffixed (first kid's) stores — second
    and later kids' history is not in Android backup.
12. **Kid → parent requests** ("can I have this channel?") and **parent
    notifications** (time up, request arrived) via a WorkManager poll.
13. Stats cache / digest files are keyed on `device.key`, which flips when a
    legacy entry learns its id — up to 14 days of digest baselines vanish.
14. `ConfigStore.fromJson` is all-or-nothing: one malformed entry rejects a
    whole push with "out of sync" and no visible reason.
15. `LanServer.handle` has no unit tests; extracting a pure
    `route(method, path, headers, body)` would make the whole LAN surface
    testable.

Considered and rejected: hashing admin tokens in `/admins`. Every admin can
already do everything (push config, revoke others), so impersonation gains
nothing; the change would only have complicated master election.

