# Fork notes

This fork of [itcon-pty-au/pickwick](https://github.com/itcon-pty-au/pickwick)
(forked at upstream `v0.7.8`, commit `7ce27f9`) aims at one thing: make the
kid-facing side feel like a real, polished player a young child wants to use,
and harden the phone↔device backend underneath it. Upstream's model — explicit
allow-list, no cloud, parent's phone as the only admin — is unchanged.

Everything below was built and unit-tested on the JVM and exercised on the
Android 14 emulator (phone). It has **not yet** been run on a Google TV or a
real phone; see "Before the first release".

## What changed

### Player (`ui/PlayerActivity.kt`)

- **Kid-sized controls on phones/tablets.** The stock Media3 controller (tiny
  buttons, a playback-speed menu) is gone. In its place: a big white
  play/pause, previous/next (when there is a lineup), a draggable scrubber
  with a fat thumb, title and channel, a back arrow, a CC toggle when the
  video has subtitles, and a **"⏳ 12 min left" chip** that turns amber in the
  last five minutes. Single tap shows/hides; **double-tap left/right hops
  ±10 s** with the YouTube-style ripple; double-tap centre toggles play.
  Controls stay up while paused (a frozen frame with nothing on it reads as
  broken). Light haptics on the gestures.
- **Immersive landscape.** Phones enter sensor-landscape with the system bars
  hidden and the cutout used; before, the player inherited portrait and kept
  the status bar.
- **End-of-video cards.** Mid-lineup: an **Up next** card with the next
  video's poster and title counts down from 6 and auto-plays, with *Play now*
  / *Not now*. Last video: *That's the end! 🎉* with **Watch again** / **All
  done**, back to the shelf after 12 s. Titles and posters now travel in the
  intent (`EXTRA_QUEUE_TITLES/THUMBS`). The countdown runs on the activity's
  scope so listen mode (screen off) still advances.
- **Friendly failure.** A playback error shows *"Hmm, this video won't play
  right now"* with **Try again** / **Go back** instead of a raw extractor
  message with no exit. The message goes to logcat.
- **Time-up / bedtime / blocked screens** are cards: a big emoji that pops in,
  the message, and an **Okay 👍** button (any remote key on TV), on top of the
  existing auto-close.
- **TV remote:** media next/previous and channel up/down step the lineup;
  held ◀/▶ is paced (4 hops/s, 30 s hops after a second) instead of one hop
  per 50 ms key repeat; the overlay fades, shows title/channel, a play glyph
  while paused and a *Next: …* hint; controls re-appear on resume. The TV
  track sheet (audio/subtitles) is unchanged.
- The phone CC toggle now writes the sticky captions preference (it used to
  be reset on every stream swap).
- Notice pills slide in/out instead of appearing.

### Home (`ui/HomeScreens.kt`, `PickwickScreen.kt`, `VideoGrid.kt`, `Tiles.kt`)

- **Screen time on the home screen:** a *⏳ N min left* chip under the header,
  and a **banner** (*It's bedtime — you can watch again at 7:00 🌙*) whenever a
  rule blocks watching, so the kid doesn't discover it tile by tile.
  `SessionGuard.blockReason()` is a new read-only twin of `checkStart()` (which
  spends passes and lifts locks, so screens must not call it).
- **Transitions:** screens fade/lift in; the leaving screen keeps its own
  state so it doesn't flash the next screen's empty grid.
- **Thumbnails crossfade** over a placeholder block (`PosterImage`), tiles
  **squish on press**, the TV focus ring eases in, channel names are a step
  larger, the profile chip is a 48 dp target.
- **Back arrow** on every sub-screen (phones). **Friendly error card** with
  *Try again* (`vm.retryCurrent()`), the raw detail in small print "for the
  grown-ups". Empty states have a picture. Held-by-screening copy speaks to
  the kid first.
- **Keep watching hold asks first** (*Done with this one?*) instead of
  silently marking the video watched on every device.
- **All-held home** (channels exist but screening holds all of them) says so
  instead of "No channels yet — add channels".
- Splash: logo on black before the first frame instead of a bare black window.
- Fixes: picker "N min left" stale after a sitting; block-time strings honour
  the device's 12/24-hour setting.

### Backend (`data/Pairing.kt`, `ConfigStore.kt`, `Stats.kt`, `Backup.kt`, worker)

- **Atomic config writes** (temp + rename, one process lock): a torn read of
  `config.json` parsed as an *empty* whitelist — blank TV home, and a phone
  could push it onward.
- **LAN server:** the accept loop survives a failed `accept()` (it used to
  die for the process lifetime with the QR still advertising the port);
  `stop()`; refused requests drain their body so clients see 403/413 rather
  than a reset; `/pair-request` is rate-limited per address (429) and pending
  slots expire after 10 minutes / evict oldest; `/index` ids are length-capped;
  `/watchstate`, `/verdicts`, `/index` answer **400** for garbage instead of
  "merged"; `/status` reports `versionCode`/`versionName`.
- **New routes:** `POST /play` ("Play this on the TV" — the device applies its
  own blocks), `POST /admin-leave` (Unpair now revokes the phone on the
  device), `GET /stats?profile=` (per-kid stats on a shared TV).
- **Phone side:** a dedicated LAN OkHttp client (1.5 s connect, no retries —
  an offline TV cost ~10 s per call before); re-discovery cooldown is
  per-device and clears on success; **"📺 Play on <device>"** in the hold
  menu on parent phones.
- `/stats` builds *recent* from history first (it was a prefs lookup per
  cached video on every 5-second poll).
- **Full backup / restore** in settings → Import, export & backup: one JSON
  with config (no API key), every kid's watch state, and the verdict cache.
  Restore confirms with a summary, replaces config, merges the rest.
- Worker: the 32 KB body cap now applies to bytes read (chunked bodies had no
  `Content-Length` to check); pure helpers exported and unit-tested.

### Round two: the YouTube feel (phones/tablets)

- **Bottom tabs** — Home, Channels, Favorites, Search — the same mental model
  as YouTube, four thumb-sized targets always on screen. TV keeps its rows.
- **Home feed** ("New for you"): the newest cached videos from every
  channel, interleaved most-watched-channel-first (`interleave` in
  `HomeState.kt`, unit-tested), finished ones dropped, as **YouTube-style
  cards**: rounded poster, duration badge, red watched bar, round channel
  avatar, two-line title, channel name, two columns portrait. Skeleton cards
  while the caches warm.
- **Channel row** of round avatars (Surprise first) with **Show all** → the
  Channels tab (rounded tiles; Up next / Watch later / Downloads lead it).
  Favorites tab carries chips for Favorites / Watch later / Up next / Downloads.
- **Search tab**: rounded field, clear button, **microphone** (system speech
  recognizer, no permission needed) and recent-search chips per kid
  (`SearchHistoryStore`). Results keep the field on top.
- **Player**: the channel avatar and name are a button that closes the player
  and opens that channel; a **heart** saves/unsaves to Favorites with a big
  ❤️ pop; a **moon** means "stop after this one".
- **Same-channel autoplay** (parent setting *Autoplay the next video*, on by
  default, in config as `autoplay:false` when off): when a video the kid
  picked ends, the channel's next unwatched cached video joins the lineup
  behind the Up next countdown; the end card also shows **More from
  <channel>** (three tappable posters). Screen time applies as ever.
- **Hers**: "Hi, Emma! 👋" greeting; the profile colour tints buttons, chips
  and banners (`kidColorScheme`).
- Every outbound URL is a build property (`PICKWICK_UPDATE_URL`,
  `PICKWICK_DIRECTORY_URL`, `PICKWICK_SUGGEST_URL`); the update check is
  **off** until you point it at your own fork. Version 0.8.0-fork (29).

### Harness

`docs/ARCHITECTURE.md`, `docs/LAN-API.md`, `docs/DEV.md`; `scripts/check.*`,
`scripts/emu.ps1`, `scripts/seed-config.json`; `.claude/skills/pickwick-*`;
CI runs every offline test class plus the worker tests; new tests
`BackupBundleTest`, `PendingRequestExpiryTest`, `RemainingLabelTest`,
`worker/test/helpers.test.mjs`.

### Verified on the emulator (Pixel 6 / API 34, debug build)

Seen working: bottom tabs, greeting and colour tint, time chip, channel row +
Show all, feed cards with duration badges and avatars, Channels / Favorites /
Search tabs, channel page with avatar title, player with avatar + heart +
moon + CC, heart pop and Favorites file written, "Stopping after this one"
pill and the All-done card it produces, same-channel autoplay card with
countdown and the auto-advance itself, avatar → channel page, friendly error
card (a real YouTube "Source error"), break card, home banner path.

Not verified here: voice search (the emulator image has no speech
recognizer; the button only launches the system intent), the side-by-side
"More from <channel>" row after its layout fix (the vertical layout clipped
in landscape and was rearranged), anything on a TV, and the release build's
cold start. The emulator's software video decode is slow (10–20 s to first
frame) — a real phone is far faster.

### Round three: history, layouts, TV parity, upstream

- **Channel row on a card opens the channel** (bug): the avatar and channel
  name on every card are their own target (`VideoCard.onOpenChannel` →
  `openChannelByName`); the poster and title still play.
- **History**: a channel page leads with a **History (N)** tile when anything
  there has been watched, and watched videos stay in the grid, dimmed with
  the full red bar. A **🕘 History** chip on the Favorites tab lists
  everything watched, newest first (`historyItems`: the watch history joined
  to the caches and the saved lists, capped at 120). TV gets a **Watched
  lately** row with an **All history** tile.
- **Channel page layout** — parent setting in the Playback section, saved
  as `channelLayout` only when not the default, part of the fingerprint:
  *Newest first* (as before); *Popular first* (YouTube's view count, kept as
  a sixth cache column and never shown); *By playlist* (the channel's own
  Playlists tab, listed once a day into `ChannelPlaylistsCache`, shown as
  a **Playlists row** of chips — cover, count, name — at the top of the
  channel page, the channel bar's idea one level down, then **All videos**
  and the grid; a chip opens the playlist as its own page through the
  normal playlist path and Back returns to the channel via `goBack`).
  Nothing else is fetched until a chip is pressed. Channels without
  playlists keep the plain grid.
- **Channel row order** — parent setting: *Most watched* / *A to Z* /
  *Random* (`channelOrder`), applied to the home row and the Channels tab.
- **Scale**: `VideoCache` memoises each parse on the file's (mtime, length),
  so a home refresh on fifty channels re-reads only what changed;
  `HistoryAndLayoutTest.largeLibraryStaysFast` runs 50 × 500 videos through
  the feed, history and ordering in well under a second.
- **TV**: home rows are Keep watching → **New for you** (the feed as card
  tiles: 16:9 poster, duration, avatar, channel name) → Channels (round
  chips with the NEW dot and the screen-time tag) → Watched lately →
  Explore; focus starts on the top video row. The player's ▼ toolbar gains
  **❤️** and the **channel avatar**, with the focused action named ("Add to
  Favorites", "More from TED-Ed"). Every page below home has a header: the
  channel's avatar and name at headline size, and **top menu chips** —
  🏠 Home, ❤️ Favorites, 🕘 History — that Up from the grid reaches
  (`TvTopChips`). Channel pages carry the same History tile and the
  Playlists row, D-pad row by row.
- **Fire TV crash fixed** (`NoSuchMethodError: URLDecoder.decode(String,
  Charset)`): the extractor uses a Java 10 API that Android gained only in
  13, so anything on Android 9–12 — Fire TV Sticks, older phones — died on
  the first fetch. Core library desugaring is now on, as in the NewPipe
  app. The version shows in Settings and on the error card
  (`Pickwick 0.8.2-fork (31)`), so "which build is this TV on?" has an
  answer without adb.
- **TV banner**: the manifest's `android:banner` pointed at the square
  adaptive icon, which Fire TV and Google TV stretch into their 16:9 app
  rows. `res/drawable-*/tv_banner.png` (160×90 dp at every density: mark +
  wordmark on brand teal) replaces it. `scripts/Banner.java` renders those
  and the store assets in `docs/store/` (512×512 icon, 1280×720 feature
  graphic and TV banner) with plain JDK 17 — no Python, no design tool —
  so a logo change is one command.
- **Upstream tracking**: `scripts/upstream.sh` / `.ps1` and the
  `/pickwick-upstream` skill keep `docs/UPSTREAM-LOG.md`; upstream's AI
  screener fix (fd5952e) is cherry-picked, its kid-page change (1ae4cf1) is
  logged "port by hand". Version **0.8.1-fork (30)** — upstream's 0.8.0 took
  code 29, so the fork had to step past it.
- Harness: the TV AVD (`emu.ps1 tv` on emulator-5556, `dpad` verbs,
  `hold-ok`), `seed --real`, `docs/SCREENS.md`, tests `FeedInterleaveTest`,
  `HistoryAndLayoutTest`, `ChannelPlaylistsCacheTest`.

### Verified on the emulators (round three)

Phone (Pixel 6 / API 34): a card's channel row opens the channel; the
channel page shows History (1) first with the watched video dimmed in
place; Favorites chips including History; Popular first reorders the page;
By playlist paints the Playlists row (The World of Insects · 4, Exploring
Australia · 2, Let's Explore Mars, …) above "All videos", from disk on a
revisit, with the log line `playlist row for …: 30 playlists`.

Phone, also: the A to Z row order (SciShow Kids, Sesame Street, TED-Ed
after Surprise).

TV (Android TV / API 34, 1080p): the new home rows with focus on the first
feed tile; the ▼ toolbar's Audio / CC / ❤️ / avatar with their labels;
hearting shows the burst and "Added to Favorites"; the avatar action lands
on the channel page; Watched lately with All history after a play, and the
All history tile opens the TV History page, whose header carries the new
Home / Favorites / History chips with the focus ring on them; By playlist
on TED-Ed listed its playlists ("All things Odyssey", "Let's reimagine
play!", "Think Like A Songwriter", …) and the D-pad walks the row. The
Fire TV desugaring fix was checked in the built dex (the extractor's
URLDecoder reference now resolves to the desugared `j$.net` class) rather
than on a device, since both emulators run API 34.

Not verified: the Random row order beyond its unit test; the family's real
51-channel seed on the emulator (its CPU starves and the ANRs are the
emulator's, not the app's — check it on the phone); pressing the "All N"
tile and coming back on TV. The emulator lesson worth keeping: the Android
TV launcher steals the foreground with promos, so every key sequence needs
a foreground check (see the emulator skill).

### Review sweep before the round-three commit

A multi-agent review of the whole fork diff (five dimensions — API level,
TV D-pad, view-model state, data formats, player — each finding then
challenged by two skeptical verifiers) turned up these, all fixed:

- A playlist page's Back target (`playlistParent`) leaked into every later
  channel page and survived the TV Home chip — now set only by
  `openPlaylist` and cleared by every other open and by `goHome`.
- The TV home's initial-focus request re-ran whenever a row appeared
  (Keep watching fills after the first play) and yanked focus — now once
  per visit.
- The History shelf lost every finished video on each return from the
  player (`reapplyScreening` filtered finished ones out) — History now
  counts as a finished-videos screen; `openHistory` also guards against
  the kid having navigated away while the caches were read.
- Same-channel autoplay honoured only family-wide blocks, not the kid's
  own (`blockedFor`) — it now asks `Whitelist.isBlockedFor` with the
  profile id.
- A playlist opened from a channel drained screen time at 100% instead of
  the channel's rate — the synthetic source inherits the multiplier.
- Random channel order reshuffled on every refresh, resume and verdict —
  one shuffle per app session.
- `ChannelPlaylistsCache`: ids with `/` (`user/x`, `c/x`) could never be
  written (a slash is a directory); the save was a truncating write, and an
  empty file is a real answer for a day; `\r` survived the row sanitiser.
  Now sanitised names, write-then-rename, and `\r` stripped.
- A replacement player's LAN handler was nulled by the old player's
  `onDestroy` — the handler now has an owner token.
- `SessionGuard.tick` skipped two rules its siblings apply: the
  break-length gap (a long pause inside the player now starts a fresh
  sitting) and the FREE-source exemption from the daily budget (a 0%
  channel could be stopped by "that's all for today").
- Restoring a backup, or a push without a key, wrote an empty AI API key
  over the device's real one — `saveRaw` now only stores a key it was given.
- The TV toolbar's channel slot finished the player even when the uploader
  was not a whitelisted channel (a playlist's uploader usually isn't) — it
  now says "That channel isn't on your list" and stays.
- Transport keys reaching an ended player under the end card restarted it
  and re-armed the countdown — swallowed while a card is up.
- "Play on TV" from the phone while the TV sat on its launcher: Android
  10+ drops the activity start silently and the phone was told "playing" —
  the device now refuses when no Pickwick activity is started
  (`AppVisibility`), so the phone shows the honest failure.
- The SponsorBlock lookup was a child of the resolve job, which kept the
  job "active" for as long as that server took and made the listen-mode
  swap guards skip — it has its own job now.

Not every candidate survived the verifiers (11 of 24 were refuted), and a
few refuted ones were still cheap enough to harden (the cache-file name
and atomic write, the `\r` in rows, the once-only focus request, the
`openHistory` guard, per-kid blocks in autoplay).

Left in the backlog (below): Fire TV remotes have no Next/Previous keys
(add ⏮ ⏭ to the ▼ toolbar), autoplay candidates are not run through the AI
screener's verdicts (only matters with screening on), and an upstream-0.8.0
TV answers `GET /config` without the new keys, so a reinstall-recovery Pull
from such a TV silently resets `autoplay` / `channelLayout` /
`channelOrder` (both sides must run the fork).

### Round four: portrait player and the mini-player (phones)

The plan is `docs/PLAN-round4.md`; everything in it is built. Version
**0.8.3-fork (32)**. The TV is untouched by all of this.

- **Portrait player.** The player no longer forces landscape. Held upright
  it is YouTube's shape: the video in a 16:9 slot at the top, under a
  visible status bar, then the title, the channel row (avatar + name opens
  the channel; the heart and the moon moved down here), then **Up next**
  (the rest of the lineup, tappable) and **More from <channel>** (the same
  candidates autoplay draws from, computed when the video resolves).
  Sideways — by turning the phone with auto-rotate on, or by **⛶** in the
  slot — it is the full-screen, edge-to-edge stage from before, with ⛶
  again to come back. ⛶ overrides the phone's rotation lock only until the
  phone is physically held that way; with auto-rotate off it holds until
  pressed again (`forceOrientation`). The slot's chrome is
  `PlayerControlsOverlay(compact = true)`: back, time-left chip, CC, the
  PiP button, a smaller transport, time and ⛶ above the scrubber; the end
  card in the slot drops its poster and "More from" column (both are in
  the list below). The stage and the slot share one `PlayerStage` through
  `movableContentOf`, so a turn moves the `PlayerView` rather than
  rebuilding it — playback carries straight through a rotation.
- **Mini-player = system picture-in-picture** (plan option A). Back, the
  new ⧉ button, or Home while a video plays shrink it into the floating
  window (Android 12+ auto-enters on the home gesture with the smooth
  animation; 8–11 enter from `onUserLeaveHint`); the window carries a
  play/pause action and takes the video's own aspect ratio. Only a playing
  video with no card over it is eligible (`pipEligible`) — paused, ended,
  blocked or erroring, Back still just leaves. In the window every overlay,
  gesture and notice is hidden. A video ending in the window skips the end
  card: it moves on (same-channel autoplay included) or closes the window
  when the lineup is done. The window's ✕ finishes the player properly.
  Screen-off in the window pauses (listen mode is deliberately not entered
  from a visible window — the audio-only swap would blank it), and screen
  time keeps ticking. Picking another video on the shelf under the window
  **replaces** the floating one (`PlayerActivity.live`) instead of playing
  two at once.
- `AndroidManifest`: `supportsPictureInPicture`, `smallestScreenSize` in
  the player's `configChanges` (the PiP resize is a config change).
- Harness: `scripts/check.ps1` gained a UTF-8 BOM (Windows PowerShell 5.1
  read its em-dashes as cp1252 and a smart quote ended a string, so the
  script would not parse); `emu.ps1 boot headless`, `shot` pulls the PNG
  instead of redirecting (the redirect re-encoded it), `wait-stream` also
  matches the `stream[]` line. The emulator skill records the phantom-touch
  lesson below.

### Verified on the phone emulator (round four, Pixel 6 / API 34, debug)

Seen working, headless: the portrait player (slot, title, channel row,
More from SciShow Kids with six-plus rows); the compact controls; a
rotation to the full-screen stage with playback continuing (9:32 → 9:49,
no re-resolve in the log) and the landscape overlay with ❤️ 🌙 CC ⧉ ⛶;
rotating back; Home → the PiP window over the launcher (16:9 pinned task);
Back → PiP; tapping a poster under the window replaces the player (one
`PlayerActivity`, no pinned task left); seeking to 23 s before the end and
shrinking: the next video resolved inside the window, no card, window kept.

Not verified: the ✕ on the window (the emulator has no pointer for it —
`onPictureInPictureModeChanged` + lifecycle `CREATED` is the documented
signal), the PiP play/pause action, time-up while pinned (the card shows in
the window and the existing 6-second auto-finish closes it), ⛶ with
auto-rotate on (the emulator's sensor is fixed; `forceOrientation`'s release
path needs a real phone), Android 8–11's `onUserLeaveHint` path, and any
of this on a real phone or a tablet.

The emulator lesson of this round: with the emulator window on screen, the
host mouse resting over it generates phantom touches (`TaplEvents`
down/up pairs, dozens a second). Two runs "left the player on their own"
for the channel page — a phantom tap on the channel row — before the
cause was found in the full logcat. `emu.ps1 boot headless` is the fix for
unattended runs.

### Two form factors, one codebase — how it is kept maintainable

The phone and the TV are deliberately two *layouts*, not two apps. What is
shared, and must stay shared, is everything below the layout: `UiState`
and `MainViewModel` (one state, one set of actions for both), `VideoGrid`
(one grid; `cards` picks the tile), the row pieces in `PlaylistShelves.kt`
(`playlistRow`, `ShelfVideoTile`), `SectionRow`, `SpecialTile`,
`WatchedShelfTile`, the player overlay (`isTv` toggles input handling, not
content), and every setting. What differs is only the *arrangement* of
those pieces: `PhoneHome` (tabs, vertical feed) versus `TvHomeRows`
(horizontal rows), the phone's back arrow versus `TvTopChips`, and D-pad
focus plumbing (`tvFocusHighlight`, `TvRowPivot`) that is inert on touch.
Rule of thumb for future work: add a feature once in the state and in a
shared tile/row, then place it in each layout — never fork a tile per
device. `docs/SCREENS.md` lists which composable each screen uses on each
form factor, so a change can be checked on both with the emulator loop.
Backlog: the two homes could become one `HomeRows` driven by a row list
plus a density token (tile width, focus on/off), which would remove the
last duplicated arrangement.

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

## Before the first release of the fork

- Change `UPDATE_MANIFEST_URL`, `DIRECTORY_URL`, `SUGGEST_WORKER_URL` in
  `app/build.gradle.kts` to this fork's repo/worker, or installs will
  self-update back onto upstream builds. Set your own `version.json`.
- Create and back up a release keystore (see `.claude/skills/pickwick-release`).
- Test on a real Google TV: D-pad on the end card / error card / blocked card
  (cursor-driven, no focusables), held-seek pacing, and that the overlay's
  fade does not cost frames on a Chromecast. Test listen mode on a phone with
  the end card (countdown with the screen off).
- Both sides of a family must run the fork: the phone's Unpair, per-kid
  stats and Play-on-TV expect the new routes (an upstream device answers 404,
  which the phone treats as "didn't take it").
