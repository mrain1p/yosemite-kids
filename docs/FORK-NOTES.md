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

### Round five: settings hub, the kid's corner, sort and filter chips, playlist rows

Plan and decisions: `docs/PLAN-round5.md`. Version **0.9.0-fork (33)**.

- **Upstream `1ae4cf1` adopted whole** (cherry-pick; `docs/UPSTREAM-LOG.md`):
  the parent settings are a six-row hub with a page per row and a page per
  kid; edits autosave and push (no Save button); pause is per kid; a per-kid
  "hide videos shorter than N minutes" rule; every family has a kid. The
  fork's own settings — autoplay, channel page layout, channel row order
  (now with *Latest video*), the version line — sit on the hub's Playback
  page and header. The short-video rule also applies to the fork's feed,
  channel row and history. `CLAUDE.md` now asks for an upstream check at
  the start of every round.
- **The kid's corner (`ProfileHub.kt`).** The avatar top-right opens a
  dialog: *Switch who's watching* (shared devices), *Change my look*, and
  *Parent settings* with a lock badge. The gear left the headers; settings
  is reachable only from there (and from the empty-home / who's-watching
  screens as before). *Change my look* is the parent's own picker
  (`LookPicker`, shared with the kid page): colour dots and the avatar
  grid, applied on Done.
- **A look travels back to the phone.** `Profile.lookAt` stamps every
  choice; the newest wins wherever two meet. On a device the phone
  administers (a TV, a kid's tablet) the choice waits in `ProfileLooks`,
  is laid over the config on every `ConfigStore.load()` (so it shows at
  once, and the device's fingerprint moves with it), is served on
  `GET /looks`, adopted by the phone's config sweep (`mergeLooks` before
  the hash compare) and pushed back out like any edit; a pushed config
  that carries the look (or a newer one from the parent) clears the wait.
  A parent phone, or a phone nobody administers, edits the config
  directly and lets the sweep push.
- **You tab** (was Favorites): the kid's avatar, name and time chip, the
  *Change my look* / *Switch* chips, then every shelf they've built as
  rows — Favorites, Watch later, Up next, History, Downloads — with *See
  all* into the grids; Back from a grid returns to You. The tab icon is
  the kid's own avatar. On TV the top menu is *Home · You*, the Explore
  row gets a *You* tile, and the You page is the same rows.
- **Channels tab**: two columns on phones, with sort chips *Most watched ·
  A to Z · Random · Latest video* (`orderChannels`, unit-tested).
  *Latest video* uses the upload date now stored as the cache's seventh
  column (`Video.publishedAt`; older rows read back undated and sort
  last). The home channel row follows the same choice; on TV a single
  chip beside the row title cycles through the four.
- **Filter chips** *New · Random · Popular* under the home feed's title and
  on every channel page (`filterVideos`); the title follows ("Mixed up for
  you", "Most watched"). Popular sorts on the hidden view count; Random is
  a seeded shuffle that holds still until a pull-to-refresh, a reopen, or
  a fresh press of the chip. Choices persist per kid (`KidPrefs`, beside
  their recent searches); the parent's layout/order settings are the
  defaults for a kid who never touched a chip. TV: one cycling chip by the
  row title on home, the chip row under the channel title on a page.
- **Playlist rows**: on the phone's Channels & playlists page each channel
  has a 🎬 button listing the channel's own playlists (the same day-cached
  listing the *By playlist* layout uses); ticked ones are stored on the
  entry (`playlistIds`, config `playlists`, in the fingerprint) and show
  as rows at the top of the channel's page on every device — name with
  *See all* (the playlist as its own page), then its first videos as
  shelf tiles, Shorts (≤ 60 s) and finished videos left out. Rows load one
  by one; a row with nothing to show is absent.
- **Separation and "New for you"**: rules between the home sections; a
  channel page opens with a *New for you* row (its newest unstarted
  videos) above *All videos*; the portrait player's list under the video
  is *New from <channel>*, newest first where the cache has dates.

### Verified on the emulators (round five, both headless, debug build)

Phone (Pixel 6 / API 34): home with the dividers, the avatar-only header
and the filter chips; the hub dialog (look, locked settings); the look
editor — blue + dinosaur applied to the header, the tab icon and the kid
tint, `lookAt` written to config.json (a phone nobody administers edits
the config directly); the You tab with the avatar header, time chip,
*Change my look* and a History row; the Channels tab two-up with the sort
chips (A to Z re-sorted; the choice and the look both survived a
relaunch); a channel page with the chips, *New for you*, the divider and
*All videos*; Popular reordering the grid; the ported settings hub behind
the PIN; the 🎬 picker listing SciShow Kids' playlists (15, live from
YouTube), two ticked and saved, then the channel page showing both as
rows with *See all* above *New for you*.

TV (Android TV / API 34): the header avatar chip focusable from the top
row; the hub and the look editor entirely by D-pad (yellow + frog, Done),
the look showing at once through the overlay while config.json stayed
untouched and `profile_looks.xml` held the pending choice; the *Channels*
row's cycling sort chip (Most watched → A to Z, row reordered); a channel
page with the filter chips and the *Home · You* menu; the You page.

Not verified: the phone-side adoption of a TV's pending look (needs two
paired emulators; the merge rules are unit-tested in `ProfileLooksTest`),
the *Latest video* sort against real dates (the seed's caches predate the
column), Random beyond its unit test, playlist rows on the TV, and any of
this on real devices. The TV channel page's initial focus still lands on
the grid's first tile, which scrolls the rows above it just out of view
until the kid presses Up — worth a look on a real TV.

### Round six: the design pass

Version **0.9.1-fork (34)**. The user's verdict on round five, with real
phone screenshots beside YouTube's: amateurish. The causes were specific
and are gone.

- **An icon set instead of emoji** (`ui/Icons.kt`): the Material Symbols
  the core pack lacks, drawn from their path data — no `material-icons-
  extended` (megabytes of dex). Emoji remain only where they are content:
  the kid's avatar, the end card, the blocked cards, the hold menu's
  rows. Tabs, chips, section titles, the hub and the time chip use icons.
- **One type scale** (`PickwickTypography`): page titles 22 sp, sections
  17, tile titles 15, body 15, captions 13. "Hi, Emma" is a title line,
  not a display face. Applied to both activities.
- **Tonal chips** (`PwChip`): a filled pill, the kid's colour when
  selected, an 18 dp icon, 36 dp tall, no outline. Every sort, filter and
  shelf chip on phone and TV, and the player's action chips.
- **Rounded-square channel art** (`ChannelArt`): circles cropped the
  square logos families add. The NEW marker is a pill (`NewPill`), never a
  dot over the picture. Rows are inset 16 dp.
- **Three tabs, search top-right.** Home · Channels · You; the search
  icon sits in every header (the TV keeps its inline field). The Search
  page gets a back arrow.
- **Home**: the title stays "For you" (it used to change with the sort);
  one full-width card per row on phones like YouTube's feed; the meta line
  reads "Channel · 3 days ago" (`relativeAge`, from the upload date the
  cache now stores).
- **Channel page**: an anchored header — 56 dp art, the name, "30+ videos
  · 29 playlists" — then the channel's own **Playlists strip (always, no
  parent action; a "Shorts" playlist is dropped)**, the first three
  playlists as rows (pinned ones first), *New for you*, then *All videos*
  with the sort chips under its own title. Playlist titles lose the
  channel's stamp (`cleanPlaylistName`: "The World of Insects | SciShow
  Kids" → "The World of Insects").
- **Channels tab**: title with a count, tonal sort chips, two-up tiles.
- **You**: one page. Avatar, name, time chip, the kid's two actions, a
  strip of the four shelves (always present, empty ones say what would
  fill them), then rows; *See all* unfolds a shelf into a grid in place.
  The second layer and its duplicate chip row are gone.
- **The hub** is a bottom sheet on phones, a dialog on TV, with icons and
  a small lock on the settings row.
- **Player** (portrait): channel row with a chevron, *Favorite* / *Stop
  after this* as labelled chips, *Up next* then *More from <channel>*
  with "channel · duration" under each title; the landscape overlay's
  heart and moon are icons.

- **Hold menu**: icon rows instead of emoji, the icon tinted when the row
  would undo a state (already a favourite, already watched).

### Verified on both emulators (round six, headless, debug)

Phone (Pixel 6 / API 34): home — three tabs, search top-right, avatar
chip, tonal filter chips under "For you", rounded-square channel art, one
card per row with the meta line; Channels — title with a count, sort
chips, two-up tiles; a channel page — anchored header ("30+ videos · 29
playlists"), the Playlists strip **with no parent action**, two playlist
rows, *New for you*, *All videos* with the chips; You — one page, four
shelves always present with their empty lines, chips jumping to rows; the
hub as a bottom sheet; the portrait player — channel row with chevron,
*Favorite* / *Stop after this* chips, *More from* rows with duration.

TV (Android TV / API 34): the same home with the search icon and the
cycling chip; a channel page with the header, the Playlists strip (names
de-stamped: "The World of Insects", "Let's Explore Mars!") and its rows;
the *Home · You* menu; the You page with the shelf chips; the hold menu
as icon rows, D-pad focus intact throughout.

Not verified: the phone adopting a TV's pending look (two paired
emulators needed; the merge is unit-tested), *Latest video* against real
dates (the seed's caches predate the column), and real devices.

### Round seven: density, the avatar everywhere, quality and paging

Version **0.9.2-fork (35)**, from the user's notes on round six.

- **Padding halved across the app.** `SectionRow` 14/4 → 6/2, `SectionDivider`
  10/2 → 6/0, row content padding 8 → 4, the home grid's item spacing 10 → 6
  and its top inset 8 → 2, the screen's own padding 16/8 → 12/2. Nothing
  moved; there is just less air between the parts.
- **Chips ride their titles.** `SectionRow` takes a `trailing` slot: the
  New / Random / Popular chips sit on the "For you" and "All videos" lines,
  and the time-left chip sits on the greeting line. Three bands of screen
  went back to the videos.
- **The avatar is on every page** (`HeaderActions`), not just home:
  Channels, You, channel pages, the grids. It opens the kid's corner, which
  is where *Change my look* now lives — the chip left the You page, and the
  player has no header of its own, so it is the one screen without it.
- **Picture quality** (`PLAYBACK_QUALITIES`): Auto (the connection-and-device
  pick, the default) or a ceiling of 1080/720/480/360, set separately for
  TVs and for phones in the parent settings, and changeable for the video in
  hand from a chip in the player. A ceiling only ever caps Auto's choice, so
  a weak connection still steps below it; picking one re-resolves the stream
  and carries on from the same second.
- **Pagination** (`PAGE_SIZES`): All (as before) or 10 / 20 / 30, after which
  a grid stops and offers "Show more (N)". A scroll now has an end.
- **Player list**: rules between the video's details, *Up next* and *More
  from <channel>*.
- **TV banner on the launcher activity too.** The 16:9 card has been right
  since round 3, but some launchers (Fire TV among them) read the banner off
  the leanback activity and fall back to the square icon when it is missing.

### Round eight: home order, the playlists page, and the TV card

Version **0.9.3-fork (36)**.

- **A real layout bug on the channel/playlist header.** The Continue chip
  sat in the header row after the art, the back arrow and two icons; on a
  phone with display scaling the weighted title column collapsed to about
  one character and the name ran down the screen in a vertical stripe.
  Continue/Play moved to their own line, the title column has a 72 dp
  floor, and the meta line is capped at one line.
- **Home order** (both form factors): Channels, then Keep watching, then
  the chip row, then For you. On TV the remote's initial focus moved to
  the channel row, which is now the top one — it used to open the page
  already scrolled past it.
- **Surprise me left the channel row.** It is not a channel, so it is a
  chip alongside Favorites, Most watched and Latest; the last two open
  the Channels tab already in that order.
- **A Playlists page**: the strip's "See all (N)" opens every playlist the
  channel has, one row each with its cover and video count. New for you
  now leads the channel page, then the playlists, then All videos.
- **You**: the page's own big avatar is the door to the kid's corner, and
  the duplicate top-right avatar is gone — three faces on one screen
  (header, corner, tab) was two too many.
- **Pairing screen**: centred and width-capped (it ran off both edges of a
  TV), the QR on a white card so a phone camera can lock on, and a status
  line that names the step — waiting for a scan, a phone is asking,
  paired (with "you can go back"), or no Wi-Fi.
- **TV launcher card**: the leanback entry is now its own
  `activity-alias` carrying the banner, instead of a second intent-filter
  on `MainActivity`. One activity with both LAUNCHER and
  LEANBACK_LAUNCHER reads to some TV launchers as a phone app, and they
  stretch the square icon into the 16:9 card — which is what a real Google
  TV was doing. `cmd package resolve-activity -c LEANBACK_LAUNCHER` now
  answers `.ui.TvLauncherActivity`. Verified at the manifest and resolver
  level; the launcher's own card could not be re-rendered on the emulator
  (its Apps row needs a signed-in Play account), and TV launchers cache
  cards, so an installed device may need a reboot to redraw it.

### Round nine: TV consistency, themes, the focus ring, video age

Version **0.9.4-fork (37)**.

- **The date a video came out** was threaded through the cache and the tiles
  in round six but never actually read off the extractor, so the meta line
  was always just the channel. Mapped now, and behind a parent switch that
  is **off by default** ("Show when a video came out"). A video the
  extractor gave no date for shows the channel alone rather than a guess.
- **"Playlists" appeared twice** on a channel page: `playlistRow` drew the
  heading with its "See all", and the row inside drew its own.
- **TV consistency pass.** The Explore row of square tiles became the same
  chip row the phone has; "All history" became the "Watched lately" row's
  own action instead of a tile of a different shape inside it; the header
  mark is rounded on both form factors (it was a hard square on TV); the
  chip row sits before the feed, matching the phone's order.
- **Room to breathe on the big screen**: 40 dp side margins and 20 dp
  vertical on TV (was 16/10), and 20 dp grid gutters with a 12 dp inset.
- **The focus ring is no longer a white box.** It follows the item's corner
  radius, takes the kid's accent colour, and the focused item scales to
  1.06 on a spring and lifts on a shadow. Motion carries the signal; the
  ring confirms it.
- **Three themes** — Dark, Light, My colour — chosen by the kid in their own
  corner behind the avatar, stored per kid on the device (`KidPrefs`). "My
  colour" tints only the accents, so a restyle never costs legibility. The
  avatar keeps its own colour under every theme.
- `docs/OPEN-QUESTIONS.md` records the parked hub / web app / Apple threads.

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


### Suggestions: "More like what you watch" (0.9.5-fork)

A home row of *older* videos off the family's own channels, ranked against
what this kid has already watched. It exists because the feed above it is
newest-first: a channel's back catalogue is otherwise unreachable without
opening the channel and scrolling, which no kid does.

The whole ranking is `suggestionsFor` in `ui/HomeState.kt`, and it is
deliberately dull:

- Titles are split into words (`titleKeywords`) — Unicode letters and digits,
  three characters or more, lowercased, with a small hand-picked stoplist. The
  stoplist is not a real one on purpose: kids' YouTube titles are wall-to-wall
  "for kids", "full episode", "compilation", and a general stoplist keeps all
  of those, at which point every video matches every other video.
- Each candidate scores the size of its overlap with each watched title,
  weighted `1 / (1 + i * 0.2)` by how recently that title was watched. The
  last thing they watched says more about what they want next than the
  fortieth thing back, but old watches still count.
- Channel affinity adds at most 0.5 — enough to break a tie between two equal
  matches, never enough to promote an unrelated video over a real one. There
  is a unit test that pins exactly this.
- A per-channel cap of 2 stops one prolific channel from owning the row. The
  point is to widen what the kid sees, not to rebuild the channel page.
- Score 0 means not suggested. An empty row is correct and common.

What it deliberately is *not*: no model, no network call, no view counts, no
reach outside the parent's whitelist. Everything it knows is this kid's own
history on this device, which is also why the scoring is pure — the awkward
cases (nothing watched, one channel, all-stopword titles) are eleven unit
tests in `SuggestionsTest` rather than a guess.

Wiring: `UiState.suggested`, built by `MainViewModel.suggestionsRow()` on both
refresh paths — including the one that runs on return from the player, since
that is exactly when a new watch has just landed and the row is stale by
definition. `VideoCache.load` memoizes on (mtime, length), so re-reading every
source's cache to build it costs a stat per channel, not a parse.

Both layouts render it with `KeepWatchingRow`, whose `onDismiss` became
nullable for it: the suggestions row is a *view over* the channels, not a list
the kid owns, so a hold has nothing to take the video off, and the hold-menu
and its confirm dialog are suppressed rather than made into a no-op.

Off is one switch, `suggestSimilar` under Settings → Suggestions, on by
default. It persists append-only-when-false (`;SG:off`, `"suggest"`) so every
existing family's config keeps its fingerprint across the upgrade.

Not done, and worth knowing why: channel *categories*. The obvious way to
suggest across channels is a topic per channel, and the community directory
already carries `topics` — but a channel linked from YouTube by URL or in-app
search has none, and YouTube has no reliable category to read back. That
leaves a parent hand-tagging every channel they add, which was tried and cut
in this round. Watch history needs no tagging and no upkeep, so it is what
this ships on.

### Upstream tracking became a routine

`scripts/upstream.ps1` and the `pickwick-upstream` skill already existed. What
was missing was anything that made them *run*: a fork only finds out that
upstream fixed extraction when a family's playback has already been broken for
a week. Two triggers now:

- A weekly scheduled task (`pickwick-upstream-check`, Mondays) runs the script,
  triages anything new into `docs/UPSTREAM-LOG.md`, and says whether an APK
  rebuild is warranted. Local commits only — it never pushes and never touches
  an attached device.
- Step 0 of `pickwick-release` is the same check, so no release is cut blind.

Both end in the one table in `docs/UPSTREAM-LOG.md`, deliberate skips
included — those are what a later reader would otherwise re-investigate.

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

### Round 12: settings polish, the colour wash, one You header (0.9.6-fork)

**"My colour" now actually changes the room.** It only ever moved the accent
colours before, so a kid who picked pink got a pink Play button on the same
black page as everyone else. Two parts now:

- `kidColorScheme` nudges `background` and `surface` 7% toward the kid's
  colour, and `secondaryContainer` 55% so the selected tab pill and the
  settings chips stop being the one brand-teal object on a pink page. Its
  label picks black or white from the blend's own luminance — a kid picks pale
  yellow as readily as navy.
- `Modifier.kidBackdrop` paints the rest: two soft radial gradients from the
  top corners plus a breath at the bottom. A gradient, not a fill, because a
  saturated fill makes every thumbnail fight the page and every card need an
  outline. A wash that fades reads as *light in the room*, which is what was
  actually asked for.

The carrier is `surfaceTint`, unused elsewhere in this app: transparent on
Dark and Light, the kid's colour on "My colour". That means `kidBackdrop` can
sit unconditionally on the root `Surface` (which is `Color.Transparent`, so it
does not paint over the wash) with no extra plumbing through the composable
tree.

**The You header stopped being the odd one out.** It had a big avatar and the
kid's name at top left and no top-right actions; every other page has the
title left and search plus avatar at top right. So the same face appeared
twice on the page (three times counting the tab) and this was the one screen
with a different header shape. Now: the kid's name is the title, and
`HeaderActions` sits top right exactly as elsewhere.

**Settings, from a real phone rather than from the source.** Booted the
emulator and walked every page; what the screenshots showed:

- The hub's six rows were six emoji at six different optical weights and
  colours — the first thing a parent sees, looking like a sticker sheet.
  `HubRow` now takes an `ImageVector`, tinted `primary`, and three new
  Material Symbols paths (`Shield`, `Devices`, `Save`) joined the set. The
  chevron became a real icon too.
- "Turn off all watching until midnight" wrapped to two lines *and* squeezed
  "Pause for today" against the card edge. Sentence over button now.
- "Download quality" did the same against three quality chips. Label above.
- The search-index refresh button was centre-aligned against a two-line
  paragraph, so it floated in the middle of the text. Top-aligned. And
  "50 channel(s)" is now "50 channels".
- The suggestion explainer's headings sat as far from their own paragraph as
  from the previous one, because the card spaces every child equally. Each
  heading and body is one child now.

Left alone deliberately: `CompactButton`'s 10 dp content padding means a
standalone text button's label sits 10 dp right of the body text above it. It
reads as button padding rather than as misalignment, and changing it would
move every inline use too.
