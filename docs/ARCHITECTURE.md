# Yosemite Kids — architecture map

A reading guide for the codebase: what lives where, how data flows, and where
to make which kind of change. Line-level detail is in the code comments; this
is the map.

## One paragraph

A single Android app (Kotlin + Jetpack Compose, `minSdk 26`) installed on both
the **parent's phone** and every **kid device** (Google TV, tablet, phone).
The kid side is a whitelist-only YouTube player: channels come from a
`config.json` the parent edits on their phone and pushes over the home LAN;
streams are resolved with NewPipeExtractor and played by Media3/ExoPlayer.
There is no cloud. The only network services are YouTube itself, SponsorBlock
(optional), the optional AI screening endpoint the parent configures, and a
tiny Cloudflare Worker that turns website/app suggestions into GitHub PRs for
the community channel directory.

Optionally there is a **hub**: the same config, the same merge, in a Docker
container on something that stays powered. It is a peer, not a server — a
family that never runs one sees no difference — and it is administered from a
browser rather than from the phone, which is why `SettingsSurface` exists to
keep the two faces saying the same thing. It holds no credential on any device
by design: it answers, and it nudges. `docs/HUB.md` is the deployment and
parent-facing half.

## Source layout

Four Gradle modules. `:app` is the Android app. `:core` is the pure config
model and merge (no disk, no clock, no Android; guarded). `:crawl` is the
network layer, the YouTube repository, the search index and its crawler, plain
JVM (no Android; guarded), so the phone's worker and the hub's scheduler run
one crawler. `:hub` is the Docker container: config store, LAN routes, the
admin GUI, and since 1.0.5 the crawl itself (`HubCrawl`) and the election
(`HubMaster`). `:app` and `:hub` depend on `:crawl` and `:core`; `:crawl`
on `:core`; nothing depends on `:app`.

```
app/src/main/java/io/yosemitekids/app/
├── YosemiteKidsApp.kt            Application: OkHttp/NewPipe wiring, Coil image loader
├── data/                     Everything that isn't a screen
│   ├── Whitelist.kt          Domain model: WhitelistEntry, Source, Limits, TimeWindow,
│   │                         Profile visibility, AiConfig. Whitelist = the family config.
│   ├── ConfigStore.kt        config.json on disk (atomic writes, secret stripping,
│   │                         fingerprint for sync). Pure JSON (de)serializers live in
│   │                         its companion — unit-testable without Android.
│   ├── Profiles.kt           Kid profiles, ProfileNamespace (per-kid store suffix),
│   │                         ActiveProfileStore (who's watching on this device)
│   ├── SessionGuard.kt       Screen-time enforcement (budget, sittings, breaks,
│   │                         blocked windows, grants). Prefs-backed, per kid.
│   ├── Pairing.kt            PairingStore (tokens, paired devices), LanServer (the
│   │                         token-gated HTTP listener), LanClient (the phone side),
│   │                         re-discovery sweep. See docs/LAN-API.md.
│   ├── WatchSync.kt          Cross-device merge of history + saved lists (LWW)
│   ├── WatchHistory.kt       Resume points per video, per kid
│   ├── SavedListStore.kt     Favorites + Watch later (TSV, tombstones)
│   ├── QueueStore.kt         "Up next" lineup (device-local)
│   ├── UsageStore.kt / ChannelUsage.kt   Per-channel opens/minutes (sorting, stats)
│   ├── Stats.kt              The /stats payload for the parent's dashboard
│   ├── Digest.kt             Weekly digest baselines
│   ├── YouTubeRepository.kt  NewPipeExtractor wrapper: sources, feeds, stream
│   │                         resolution, retry policy
│   ├── ChunkedStreamDataSource.kt  Media3 data source that fetches googlevideo in
│   │                         ranged chunks (defeats throttling)
│   ├── SourceCache.kt / VideoCache.kt   Last-known channel tiles and feed pages
│   ├── ChannelIndexAndroid.kt  ChannelIndex(context): the app's factory for :crawl's index
│   ├── IndexCrawlWorker.kt   The 15-minute shell around :crawl's IndexCrawlRun,
│   │                         master-only; every device runs its drop pass
│   ├── IndexPull (in :crawl) Pulling the hub's index, called from ConfigSync.sweep
│   ├── AiScreener.kt / Screening.kt / DeepCheck.kt / Captions.kt
│   │                         Optional AI content screening (title pass, deep check)
│   ├── Downloads.kt / DownloadService.kt / DownloadChecker.kt / LocalLibrary.kt
│   │                         Offline copies with parent approval; sideloaded files
│   ├── Backup.kt             Full backup/restore bundle (config + watch state + verdicts)
│   ├── KidNotices.kt         In-app pills the kid sees (grants, rule changes)
│   ├── NowPlaying.kt         What's playing (for /stats) + RemotePlayerControl bridge
│   ├── SponsorBlock.kt       Segment lookup by hashed video id
│   ├── Http.kt               The shared internet OkHttpClient (resilient DNS)
│   ├── Updater.kt            Self-update from version.json + GitHub releases
│   ├── Directory.kt / DirectorySubmitter.kt   Community channel directory
│   ├── SecretStore.kt        Keystore-encrypted AI API key
│   └── TimeWindows.kt / Tsv.kt   Pure helpers
└── ui/
    ├── MainActivity.kt       Host: config preload, LanServer start, profile
    │                         resolution, pairing flow, launches PlayerActivity
    ├── MainViewModel.kt      Home/channel/list state, refresh, LAN sync loops,
    │                         hold-menu actions, "Play on TV"
    ├── HomeState.kt          Screen sealed interface + UiState
    ├── YosemiteScreen.kt     Screen container: transitions, titles, back, errors
    ├── HomeScreens.kt        Phone grid + TV rows, header (time chip, banner)
    ├── VideoGrid.kt          Poster grid, hold menu, queue list, watched shelf
    ├── Tiles.kt              Shared tile pieces: PosterImage, pressScale, chips
    ├── FocusHighlight.kt     TV focus ring + D-pad helpers (hold, throttle)
    ├── ProfilePicker.kt      "Who's watching?" + direction-PIN entry
    ├── PlayerActivity.kt     The player: gate, resolve, ExoPlayer, kid controls
    │                         overlay, end card, listen mode, remote keys
    ├── ListenService.kt      Foreground service for screen-off audio
    ├── Settings*.kt          Parent settings (PIN/biometric gate, sections)
    ├── KidsSettings.kt       Kid profile editor
    ├── StatsScreen.kt / DigestScreen.kt   Parent dashboards
    └── Theme.kt              Colors, formatClock, remainingLabel
```

The three modules the app shares with the container. `:core` and `:crawl` keep
the app's own package name (`io.yosemitekids.app.data`) because the classes
moved out of it and a package rename would have been a merge conflict in every
file that imports one.

```
core/src/main/kotlin/io/yosemitekids/app/data/     the pure rules: no disk, no clock, no Android
├── Whitelist.kt        The family config model: WhitelistEntry, Source, Limits,
│                       TimeWindow, Profile, AiConfig
├── ConfigJson.kt       (de)serialization + the fingerprint both faces compare
├── ConfigMerge.kt      Two documents in, one out. Unit by unit, at the JSON
│                       level, and it reads no clock (guard, and the sync skill)
├── ConfigStamp.kt      What a save records: per-unit stamp, tombstone, log line
├── SyncDecision.kt     What the sweep does about one peer
├── SettingsSurface.kt  The settings manifest: groups, controls, words, and
│                       which face each belongs to. Guards 1-3, 11 and 26 read it
├── Grants.kt / KidChoices.kt / Profile.kt / TimeWindows.kt / Tsv.kt
├── MasterElection.kt / MasterToken.kt   Who builds the search index (clock passed in)
└── BackupFile.kt       The backup envelope, so the phone and the hub write one shape

crawl/src/main/kotlin/io/yosemitekids/app/data/    network, disk, clock — plain JVM
├── Http.kt             The one OkHttpClient, and restrictTo() — the hub arms it
│                       at startup so the crawler can reach YouTube and nothing else
├── YouTubeRepository.kt / Extractor.kt / OkHttpDownloader.kt   NewPipeExtractor
├── ChannelIndex.kt / IndexCrawler.kt / IndexCrawlRun.kt / IndexPull.kt
├── AiScreener.kt / ScreeningStore.kt   The screener and the verdict store
└── QualityTargets.kt / PlaylistRef.kt / LocalUrls.kt / CrawlModule.kt

hub/src/main/kotlin/io/yosemitekids/hub/          the Docker container
├── Main.kt             Boot: data dir, arm the host allow-list, wire the store
│                       to the nudge, print (or withhold) the admin token
├── HubServer.kt        Every route — see docs/LAN-API.md, and guard 30
├── HubStore.kt         config.json on the volume. Stamped writes, key stripped
├── HubSecrets.kt       secrets.json: the AI key, served to a parent, never merged
├── HubTokens.kt        devices.json: enrolments, their kind, address, last seen
├── HubPassword.kt      PBKDF2 verify/derive, shared shape with the phone's PIN
├── HubSessions.kt      Browser sessions and the escalating sign-in lockout
├── HubWeb.kt           The GUI's data layer: /api/state, the patch allow-list
├── HubVersions.kt      The five-snapshot restore ring
├── HubNudge.kt         POST /sync-now — the only outbound call in this module
├── HubCrawl.kt / HubMaster.kt   The crawl on a timer, and claiming the master slot
└── DataDir.kt
hub/src/main/resources/web/index.html              the whole GUI: one file, no
                                                   build step, nothing from a CDN
```

Other top-level directories:

| Path | What |
| --- | --- |
| `app/src/test/` | JVM unit tests (pure logic, org.json real impl). `ExtractorSmokeTest` hits live YouTube — excluded from the PR gate. |
| `core/src/test/`, `crawl/src/test/`, `hub/src/test/` | The shared rules' tests, each its own gate step. The merge's tests live in `:core` on purpose: in `:app` they would prove it works *on Android* and leave the container's copy uncovered. |
| `worker/` | Cloudflare Worker: suggestion form → PR, contact form → issue/discussion, app whole-list submission. `worker/test/` is `node --test`. |
| `site/` | pickwick.tv static site + `site/directory/*.json` (the community directory the app reads). |
| `whitelists/` | Importable themed channel lists. |
| `scripts/` | Developer harness: `check.ps1`/`check.sh` (build + tests), `emu.ps1` (emulator loop). |
| `.claude/skills/` | Claude Code skills for this repo (build/test, emulator, LAN API, release). |
| `docs/` | This file, `LAN-API.md`, `DEV.md`, `FORK-NOTES.md`, `SETUP.md` (end-user). |

## The two roles

Role is stored in `pairing.xml` (`PairingStore.role()`):

- **PARENT** — administers kid devices. Has the full settings editor, pushes
  `config.json`, pulls `/stats`, is the courier for the watch-state and
  verdict sync between devices, and (if elected master) crawls the search
  index. ("Courier", not "hub" — that word now names the container.)
- **KID** — a TV, or a phone/tablet the parent dedicated. Shows only a pairing
  QR in settings; everything else is pushed to it.

Both roles run the `LanServer`. A parent phone also has a kid-style home (it is
a full player); the hold menu there gains "Play on <device>".

## Kid-facing flow

```
MainActivity.onCreate
  ├─ ConfigStore.load()  (preloaded on a thread)  → family: Whitelist
  ├─ LanServer.start()   (every device)
  ├─ resolveActive()     dedicated kid | single kid | remembered pick | picker
  ├─ WhosWatchingScreen  (2+ kids on a shared device)
  └─ YosemiteScreen(vm)  keyed per kid: MainViewModel over that kid's stores
        ├─ Phone: bottom tabs Home / Channels / Favorites / Search
        │    Home = PhoneHome: greeting header (TimeChip / BlockedBanner),
        │           Keep watching, channel chips + Show all, "New for you"
        │           feed (UiState.feed = interleave of per-channel caches)
        │    Channels = ChannelsScreen (rounded tiles + shelves)
        │    Favorites = Watchlist with ShelfChips (Watch later / Up next / Downloads)
        │    Search = Screen.Search (field, mic, recents) → SearchResults
        ├─ TV: TvHomeRows (Keep watching / Channels / Explore rows)
        ├─ ChannelVideos / Surprise / WatchLater / Queue / Downloads /
        │  SearchResults / WatchedVideos — VideoGrid (cards on phone, tiles on TV)
        └─ onPlay → Intent(PlayerActivity) with EXTRA_QUEUE(+titles/thumbs/
           durations), EXTRA_CHANNEL(+avatar), EXTRA_PROFILE_SUFFIX/ID,
           EXTRA_TIME_PERCENT

PlayerActivity.onCreate
  ├─ SessionGuard.checkStart(timePercent)  → BlockedCard, or listen-only
  ├─ ExoPlayer(loadControl: 5-min read-ahead, 48 MB cap)
  ├─ playIndex(i): local file? → downloads? → repo.resolvePlayback()
  │     └─ deepCheckBlocks() (AI, once per video per rules version)
  ├─ attachSources(): MergingMediaSource(video, audio) + subtitle configs
  ├─ 5-second tick: saveProgress, NowPlaying.update, SessionGuard.tick,
  │     remaining-time chip, 5/1-minute warnings
  ├─ STATE_ENDED → showEndCard(): same-channel autoplay (config.autoplayNext,
  │     VideoCache of the channel, minus watched/blocked) appends to the
  │     lineup → Up next countdown + "More from <channel>"; last/stop-after →
  │     Watch again / All done
  ├─ Layout (phones): portraitLayout ← configuration; PortraitPlayerScaffold
  │     (16:9 slot + title, channel row, Up next, More from <channel>) or the
  │     full-screen stage; PlayerStage is movableContentOf so a turn keeps
  │     the SurfaceView. ⛶ = forceOrientation(); TV: stage only.
  ├─ Picture-in-picture (phones): Back / minimise / Home → enterPip()
  │     (pipEligible: playing, no card); inPip hides every overlay; the end
  │     card is skipped (advance or finish); ✕ finishes; a second launch
  │     finishes the PiP one (companion `live`)
  └─ Overlay: PlayerControlsOverlay (phone: avatar→channel, heart→Favorites,
        moon = stop after this, transport + scrubber, PiP + ⛶ buttons;
        compact = the portrait slot; TV: state glyph)
```

### Screen time in one table

| Rule | Where set | Where enforced | Where shown |
| --- | --- | --- | --- |
| Session length × sessions/day | `Limits` in config, per kid | `SessionGuard.tick` | TimeChip, "N min left" picker line, player chip |
| Break between sittings | `Limits.breakMinutes` | `SessionGuard.checkStart/tick` (lockUntil) | BlockedBanner, BlockedCard |
| Blocked windows (bedtime…) | `Limits.windows` | `SessionGuard.activeWindow` | BlockedBanner, BlockedCard, listen-only card |
| Grants (+15/30/60) | `/grant` from phone | `grantExtraMinutes` (bonusMs, windowPassUntil) | KidNotices pill |
| Pause for today | `Limits.pausedUntilMillis` | `isPaused` | BlockedBanner |
| Per-channel multiplier | `WhitelistEntry.timeMultiplierPercent` | `tick(deltaMs * percent/100)` | Price tag on tiles |

`SessionGuard.checkStart` is a *play attempt* (may spend a break pass, lifts
lapsed locks, logs); `blockReason()` is its read-only twin for screens that
only look.

## Parent-side flow

```
Settings.kt SettingsFlow
  ├─ gate: biometric → PIN (PBKDF2 in SettingsStore)
  └─ AdminScreen: sections in order — Kids, Screen time, Listening, Screen time
     today (grants/pause), Playback, Offline downloads, Videos from this phone,
     Devices & sync (the fleet, one page per device), AI screening, Waiting for your OK, Discover with
     AI, Suggested channels, Channels & playlists, Import/export/backup, App
     Save & close → ConfigStore.save → LanClient.pushConfig to every device
```

Sync loops in `MainViewModel` (parent role, every 5 minutes while home is up):

- `syncConfigState` — hash compare, push if ours is newer, re-discover a
  device that moved (`LanClient.rediscover`, per-device cooldown).
- `syncWatchState` — pull+merge each device's watch state and verdicts, push
  the merged result back, cache stats.
- `syncIndex` — master only: diff per-source index hashes, push changed sources.

## Data on disk (per device)

| File / prefs | Contents | Per kid? |
| --- | --- | --- |
| `files/config.json` | The family config (see `ConfigStore.toJson`) | no (kids inside) |
| `files/screening.json` | AI verdict cache | no (per-kid verdicts inside) |
| `files/watchlist{sfx}.tsv`, `watchlater{sfx}.tsv` (+`_removed`) | Saved lists with tombstones | yes |
| `files/queue{sfx}.tsv` | Up next | yes, device-local |
| `files/video_cache/<source>.tsv`, `search-index/` | Feed pages, search index | no |
| `prefs: limits{sfx}` | Rules + today's counters + 60-day history | yes |
| `prefs: watch_history{sfx}` | url → position/duration/lastWatchedAt | yes |
| `prefs: usage{sfx}`, `channel_usage{sfx}` | Opens, minutes per channel | yes |
| `prefs: pairing` | role, device_token, approved/pending phones, paired devices | no |
| `prefs: profile_ns`, `active_profile` | Kid → store suffix; who's on screen | no |
| `prefs: settings` | Parent PIN hash | no |
| `EncryptedSharedPreferences: secrets` | AI API key (never backed up) | no |

`{sfx}` is `""` for the first kid a device ever registered (it inherits the
pre-profile stores) and `"_<profileId>"` for the rest — see `ProfileNamespace`.

## Where to change what

| I want to… | Start in |
| --- | --- |
| Change how a tile looks | `Tiles.kt` (shared pieces), `HomeScreens.kt` / `VideoGrid.kt` |
| Add a row to the hold menu | `VideoGrid.kt` (`menuFor` dialog) → `MainViewModel` action |
| Change the player controls | `PlayerActivity.kt` → `PlayerControlsOverlay` (phone + TV), `onKeyDown` (TV) |
| Change what happens when a video ends | `PlayerActivity.showEndCard` / `EndCardOverlay` |
| Change the portrait player (what sits under the video) | `PlayerActivity.PortraitPlayerScaffold`; the slot's chrome is `PlayerControlsOverlay(compact = true)` |
| Change an icon, the type scale, a chip or the channel art | `ui/Icons.kt` (the drawn Material Symbols), `Theme.kt` (`YosemiteTypography`, `relativeAge`), `ui/Components.kt` (`YosemiteChip`, `ChannelArt`, `NewPill`, `HeaderIconButton`, `metaLine`) — emoji are content (avatars, cards), never chrome |
| Change playback quality (Auto or a ceiling) | `NetworkQuality.kt` `QualityTargets` (`userMaxHeight`, `effectiveMaxHeight`), `Whitelist.qualityTv/qualityPhone`, the Playback settings page, `PlayerActivity.setQuality` |
| Change how many videos a grid shows before "Show more" | `Whitelist.pageSize` → `UiState.pageSize` → `VideoGrid(pageSize = …)` |
| Change the kid's sort/filter chips or their defaults | `HomeState.orderChannels` / `filterVideos` (pure), `KidPrefs` (per-kid persistence), `MainViewModel.setChannelSort` / `setHomeFilter` / `setChannelFilter`; chips in `HomeScreens.kt` (`ChannelSortChips`, `VideoFilterChips`, TV `CycleChip`) |
| Change the You tab | `YouScreen.kt`, `MainViewModel.youShelves` / `openYou` |
| Change what "More like what you watch" suggests | `HomeState.suggestionsFor` / `titleKeywords` (pure — `SuggestionsTest` covers it), fed by `MainViewModel.suggestionsRow`, switched by `Whitelist.suggestSimilar` |
| Change the profile hub or the look editor | `ProfileHub.kt`; the sync-back is `data/ProfileLooks.kt` + `GET /looks` + `MainViewModel.syncConfigState` (`mergeLooks`) + `MainActivity.onChangeLook` |
| Change what rows sit above a channel's grid | `PlaylistShelves.kt` (`playlistShelves`, `newForYouRow`, `playlistRow`), assembled in `YosemiteScreen` (`header`); the data is `MainViewModel.loadPlaylistShelves` / `loadPlaylistRow` |
| Let the parent pick a channel's playlists | `SettingsChannels.kt` (`PinnedPlaylistsDialog`, from the source's own page) → `WhitelistEntry.playlistIds` |
| Change when the phone shrinks to PiP, or what the window does | `PlayerActivity.pipEligible` / `enterPip` / `onPictureInPictureModeChanged` |
| Add a screen-time rule | `Whitelist.Limits` + `ConfigStore` (de)serializers + `SessionGuard` + settings section |
| Add a LAN route | `LanServer.handle` (bound every read!) + `LanClient` + `docs/LAN-API.md` (guard 14 checks the row is there) |
| Add a route to the **hub** | `HubServer.start` + a `private fun <name>(ex)` beside the others + `docs/LAN-API.md`'s hub table (guard 30). If it is a route a device also answers, `authorised(ex)` first (guard 29) and take it off `DEVICE_ONLY` (guard 22) |
| Change what the hub's page shows | `HubWeb.state` (what `GET /api/state` carries) then `hub/src/main/resources/web/index.html` — one file, no build step, nothing fetched from a CDN, because a NAS may have no outbound access. Pages are the `ROUTES` map (guard 11); a control drawn from the manifest needs no markup at all |
| Change how the hub is signed in to | `HubPassword` (the KDF), `HubTokens.hasPassword`/`setPassword`/`verifyAdminSecret`, `HubSessions` (the escalating lockout), and the one `HubServer.adminGate()` every presentation of the secret goes through (guard 25) |
| Change where the hub keeps the AI key | `HubSecrets` (`/data/secrets.json`) and the two functions that overlay it, `HubStore.forPeers`/`fingerprintWithKey`. It is deliberately *not* in the config document: `HubStore.commit` strips it on every write, which is what keeps it out of `versions/`, `/api/state` and a downloaded backup |
| Change what "Update now" does to a device, or says on the phone | `RemoteUpdate` in `data/Updater.kt` (the device's decision, over lambdas) + `POST /check-updates` + `LanClient.checkUpdates`; on the phone `DeviceFleet.updateNow` and `updateOutcomeText` in `SettingsDevices.kt` |
| Add a parent setting | See "Adding one setting" below — eight steps, and the build fails on any of them you skip |
| Change kid-facing wording | grep the string; every kid string is inline (no `strings.xml` yet) |
| Touch the extractor | `YouTubeRepository.kt`; bump `newpipeextractor` in `gradle/libs.versions.toml` |

## Adding one setting

Say it is a new family-wide toggle, "Ask before playing a long video".

1. `core/…/data/Whitelist.kt` — the property, defaulting to **today's
   behaviour**.
2. `core/…/data/ConfigJson.kt` — `toJson`/`fromJson`, **omitted at its
   default**; the fingerprint tail appended only when set.
3. `core/…/data/ConfigStamp.kt` — add it to the comparison of the unit it
   belongs to (`settingsDiffer` here; `sameRules` for a limits scalar).
4. `core/…/data/ConfigMerge.kt` — its JSON key into that unit's key list
   (`SETTINGS_KEYS` / `LIMITS_RULES_KEYS`) and a line in `settingsChanges` /
   `describeLimits`.
5. `core/…/data/SettingsSurface.kt` — one `SettingsControl` in the right
   group: `id`, `label`, `sub`, `kind`, `writes` (the Kotlin property path),
   `json` only when `ConfigJson` spells it differently, and `where = BOTH` —
   or `PHONE`/`HUB` **with a `why`**.
6. `app/…/ui/<the screen>.kt` — render it, taking its words from
   `ctl("<id>")`.
7. `hub/…/web/index.html` — **nothing at all.** A `TOGGLE`, `NUMBER`, `TEXT`,
   `TEXTAREA` or `CHIPS` control on a root or one-level path is drawn from the
   manifest by `renderControl`. Only a `CUSTOM` control needs a hand-written
   card, and then only a `data-control="<id>"` on it.
8. `core/src/test/…/ConfigStoreJsonTest.kt` — the four canonical tests:
   round-trips, omitted at default, keeps the pre-feature fingerprint, moves
   the fingerprint when set.

What fails if you do half of it:

- Skip **1–4** in the wrong order and the sync skill's existing failures bite:
  a field left out of `ConfigStamp` mints no stamp and is dropped by the first
  peer that merges.
- Skip **5** and guard 1 fails for a root field, or guard 26(a) for a
  `Limits`/`AiConfig` leaf — and `SettingsSurfaceTest` fails too, by
  reflection rather than by regex.
- Skip **6** and guard 26(c) fails. The reference is load-bearing, not
  ceremonial: the label lives in the manifest, so a screen that does not ask
  for the control has nothing to draw.
- Declare it `BOTH`, make it `CUSTOM`, skip **7**, and guard 26(b) fails.
- Declare it `PHONE` or `HUB` with a blank `why` and guard 26(d) fails.
- Skip **8** and the fingerprint moves for a family that never touched the
  setting, which those tests assert against.

## Threading rules (from CLAUDE.md, restated)

- Composable bodies and `LaunchedEffect` run on Main. Disk, prefs and JSON go
  through `withContext(Dispatchers.IO)`.
- `LanServer.handle` runs on a bounded worker pool; every read from the socket
  is capped.
- `PlayerActivity` hoists queue/countdown state out of composition so listen
  mode (screen off, no frames) keeps advancing.

## Config sync

Two parents used to lose each other's edits: a device receiving a push replaced
its whole config, so whoever pushed second discarded everything the other had
changed, silently and unattributably. The fix is a **sectioned merge**, peer to
peer, needing no server. `docs/PLAN-sync.md` has the design;
`.claude/skills/yosemite-kids-sync` has the invariants to obey before touching any
of it.

The shape:

- **`ConfigStamp`** mints the bookkeeping when a config is saved — a per-unit
  edit stamp, a tombstone on removal, and a change-log line. A genuine
  three-way diff (`previous` on disk, `base` the editor opened with, `next` the
  form), because a co-parent's push can land while a parent has Settings open
  and that must not read as a deletion.
- **`ui/SettingsForm`** is the settings form's Compose-free half: the twenty
  fields as one value, `toConfig` (the rules-version bump and the removed-kid
  scrub), and `saveForm`, which every save on the screen runs. What comes
  back is the *stamped* document, and the screen's `adopt` takes it as both
  the baseline and the form's own state — a form that kept its own lists
  showed the stamper a carried unit as a deletion on the very next tap.
  Guard 15 keeps `baseline` assigned there and nowhere else.
- **`ConfigMerge`** merges two config *documents*, unit by unit, at the JSON
  level. Pure, and takes no clock — which is what makes idempotence and
  associativity structural rather than test artifacts.
- **`SyncMeta`** is the `sync` block inside `config.json`: `at`, `gone`,
  `floor`, and a capped `log`. Invisible to `ConfigStore.fingerprint`, and
  advertised separately as `syncHash` on `/status`.
- **`syncAction`** decides what the sweep does about one peer: nothing, merge,
  push whole, or leave it to the parent.
- **`SyncNotices`** records "your change lost" per phone, outside the config,
  never on a kid device.

| I want to… | Start in |
| --- | --- |
| Change how two configs are reconciled | `ConfigMerge.merge` (pure; `ConfigMergeTest` is the matrix) |
| Change what a save records | `ConfigStamp.stamped` (`ConfigStampTest`) |
| Change what the settings form saves, or what it does with the result | `ui/SettingsForm.kt` (`SettingsFormSaveTest`), then `adopt` in `AdminScreen` |
| Add a field that two parents could edit independently | The checklist in `.claude/skills/yosemite-kids-sync`, section 4 |
| Change what the sweep does about a peer | `data/SyncDecision.kt` (`SyncDecisionTest`) |
| Change what a parent is told after a collision | `data/SyncNotices.kt` and the banner at the top of `AdminScreen` |
| Change the activity feed | `ui/SyncActivityScreen.kt` (`ChangeFeedTest`) |
