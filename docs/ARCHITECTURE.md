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
│   ├── KidPrefs.kt           What one kid chose on the chips (sort, order).
│   │                         Device-local and per kid — a choice, not a rule
│   ├── SettingsStore.kt      The parent gate: the PIN, stretched by :core's Pbkdf2,
│   │                         never plaintext. Both legacy formats still verify
│   ├── WhitelistRepository.kt  The form-managed config as the one source of truth
│   ├── SessionGuard.kt       Screen-time enforcement (budget, sittings, breaks,
│   │                         blocked windows, grants). Prefs-backed, per kid.
│   ├── Pairing.kt            PairingStore (tokens, paired devices), LanServer (the
│   │                         token-gated HTTP listener, bounded reads and a whole-
│   │                         request deadline), LanClient (the phone side, replies
│   │                         read through peekBody), re-discovery sweep.
│   │                         See docs/LAN-API.md.
│   ├── ConfigSync.kt         The config reconcile, off the ViewModel: the backstop
│   │                         behind Settings.pushAll
│   ├── ConfigSyncWorker.kt   The same sweep on a schedule, so a device nobody opens
│   │                         still converges
│   ├── HubEnrolment.kt       Joining a Docker hub: the device asks, then a human
│   │                         with the admin secret approves the code. docs/HUB.md
│   ├── UsageSync.kt          The half of a shared budget that moves — minutes out
│   │                         to peers, minutes in, folded into what the player
│   │                         enforces. Empty for a family on the default scope
│   ├── WatchSync.kt          Cross-device merge of history + saved lists (LWW)
│   ├── WatchHistory.kt       Resume points per video, per kid
│   ├── SavedListStore.kt     Favorites + Watch later (TSV, tombstones)
│   ├── QueueStore.kt         "Up next" lineup (device-local)
│   ├── UsageStore.kt / ChannelUsage.kt   Per-channel opens/minutes (sorting, stats)
│   ├── WatchLedgerStore.kt   The watch ledger on disk: this device's own cells and
│   │                         what it has learned from peers. Own file, own lock —
│   │                         never config.json (guard 44). Served at GET|POST /usage.
│   ├── Stats.kt              The /stats payload for the parent's dashboard
│   ├── Digest.kt             Weekly digest baselines
│   ├── YouTubeRepository.kt  NewPipeExtractor wrapper: sources, feeds, stream
│   │                         resolution, retry policy
│   ├── ChunkedStreamDataSource.kt  Media3 data source that fetches googlevideo in
│   │                         ranged chunks (defeats throttling)
│   ├── SourceCache.kt / VideoCache.kt   Last-known channel tiles and feed pages
│   ├── ChannelPlaylistsCache.kt  Which playlists a channel has, one fetch a day.
│   │                         A saved empty list is a real answer
│   ├── SourceFirstSeen.kt    When each source first turned up on THIS device, so a
│   │                         channel added minutes ago reads as new, not missing
│   ├── ContentWarm.kt        Refreshing the video caches with the app closed, and
│   ├── ContentWarmWorker.kt  the worker that runs it
│   ├── ChannelIndexAndroid.kt  ChannelIndex(context): the app's factory for :crawl's index
│   ├── IndexCrawlWorker.kt   The 15-minute shell around :crawl's IndexCrawlRun,
│   │                         master-only; every device runs its drop pass
│   ├── IndexPull (in :crawl) Pulling the hub's index, called from ConfigSync.sweep
│   ├── AiScreener.kt / Screening.kt / DeepCheck.kt / Captions.kt
│   │                         Optional AI content screening (title pass, deep check)
│   ├── ScreeningStoreAndroid.kt  The device's verdicts at filesDir/screening.json —
│   │                         a function named like the constructor it replaced
│   ├── Downloads.kt / DownloadService.kt / DownloadChecker.kt / LocalLibrary.kt
│   │                         Offline copies with parent approval; sideloaded files
│   ├── Backup.kt             Full backup/restore bundle (config + watch state + verdicts)
│   ├── Diag.kt               The diagnostic ring: every Log.w/Log.e (guard 68)
│   │                         plus the crash handler, drained into the hub's
│   │                         POST /report by the sweep. Its own file, never config
│   ├── PlaybackBreaker.kt    One failed video is skipped; two in a row is YouTube
│   │                         refusing, and the player stops walking the queue
│   ├── SearchHistoryStore.kt A kid's recent searches on this device; the rules
│   │                         are RecentSearches in :core, shared with the hub
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
    ├── FormFactor.kt         Which of the two shapes this app takes. Almost every
    │                         layout decision downstream is really this one
    ├── ChannelShelves.kt     The ten-foot design unit in dp (tvUnits): 960/1280 of
    │                         the handoff's TV frames
    ├── YosemiteScreen.kt     Screen container: transitions, titles, back, errors
    ├── TvNavRail.kt          The television's chrome: the rail, its two widths
    ├── HomeShelfCounts.kt    What each shelf has to show. All that stayed when
    │                         the shelf model moved to :core — it reads UiState
    ├── HomeShelves.kt        One home, both shapes: the shelf walk, the hero
    ├── HomeScreens.kt        Shared home pieces: rails, header, Channels tab
    ├── VideoGrid.kt          Poster grid, hold menu, queue list, watched shelf
    ├── SearchField.kt        The kid-facing search field, whitelist-scoped: there is
    │                         no "whole of YouTube" for it to leak into
    ├── TimeLeft.kt           rememberTimeLeftMs, and the pill that draws it
    ├── Tiles.kt              Shared tile pieces: PosterImage, pressScale, chips
    ├── FocusHighlight.kt     TV focus ring + D-pad helpers (hold, throttle)
    ├── ProfilePicker.kt      "Who's watching?" + direction-PIN entry, and the kid's
    │                         browser password as a typed way past it (pickerGate)
    ├── PlayerActivity.kt     The player: gate, resolve, ExoPlayer, kid controls
    │                         overlay, end card, listen mode, remote keys
    ├── PlayerCountdown.kt    PlayClock and the last-five-minutes ring: when the
    │                         countdown shows, and how full it is
    ├── PlayerGestures.kt     How a downward drag becomes "put it in the little
    │                         window" — travel and velocity in dp
    ├── ListenService.kt      Foreground service for screen-off audio
    ├── LanService.kt         Televisions only: a foreground service that keeps the
    │                         process, and the LAN server in it, alive after the app
    │                         is closed; rebuilds the server if the system restarts it
    ├── LanServers.kt         buildLanServer: the LAN server wired to this device's
    │                         stores, called by MainActivity and by LanService
    ├── Settings.kt           Parent settings: the PIN/biometric gate, the page
    │                         router, the Devices page and its "what went wrong"
    ├── SettingsRows.kt       The two rows every settings page is built from
    ├── SettingsAi.kt         The Screening page's AI connection row and its presets
    ├── SettingsDiscovery.kt  AI discovery, and the community directory section
    ├── SettingsDownloads.kt  What offline downloads are, for the page's "?"
    ├── SettingsHomeRows.kt   The home-row editor: which shelves, in what order —
    │                         every edit through HomeRows.withOrder
    ├── SettingsHub.kt        Connecting this household to a self-hosted hub
    ├── SettingsImportExport.kt  whitelist.txt in and out, and the directory offer
    ├── SettingsScreenTime.kt The kid's recurring rules, in one card
    ├── KidsSettings.kt       Kid profile editor
    ├── KidStats.kt           The ranges the stats chips offer — only what
    │                         SessionGuard.history can honestly answer
    ├── StatsScreen.kt / DigestScreen.kt   Parent dashboards
    ├── Theme.kt              The Compose binding of :core's DesignTokens (the two
    │                         kid schemes, the type scale), the parent-facing
    │                         palette, formatClock, remainingLabel
    └── KidTokens.kt          action / timeWarning / watched / offline, derived
                              per ground from :core's canonical hues
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
├── FamilyDay.kt        One spelling of "what day is it", for everything that
│                       buckets by day — and the ratchet that stops a device's
│                       own day moving backwards (guard 43)
├── UsageLedger.kt      The watch ledger's laws: grow-only cells keyed
│                       (kid, day, device), joined by per-cell max, no clock.
│                       Deliberately NOT part of the config (guard 44)
├── SafeText.kt         What a child may read of stranger-written prose: a
│                       channel's description with every link, bare domain,
│                       @handle and e-mail address taken out. Applied at the
│                       extractor boundary, never at the draw site (guard 52)
├── RecentSearches.kt   Newest first, no repeats, eight, and the × forgets one:
│                       the phone's store and the hub's per-kid list both call it
├── Grants.kt / KidChoices.kt / Profile.kt / TimeWindows.kt / Tsv.kt
├── MasterElection.kt / MasterToken.kt   Who builds the search index (clock passed in)
├── Budget.kt           How many ms a kid's rules allow in a day. Three lines of
│                       arithmetic, shared because two faces now answer it
├── HomeRows.kt         One row of a kid's home as the parent arranged it; the
│                       shelves themselves are HOME_SHELVES in ui/
├── Pins.kt             The pinned hero's ranks: withRow is the ONLY place a
│                       rank is minted, the cap applied, or an invisible
│                       source refused (guard 42). Both editors go through it
├── PasswordRecord.kt   A stored password: never the password, only what verifies
│                       one. Pbkdf2 is here, and the hub's admin password and a
│                       kid's web password both derive through it
└── BackupFile.kt       The backup envelope, so the phone and the hub write one shape

core/src/main/kotlin/io/yosemitekids/app/ui/       what both faces draw, as data
├── HomeSections.kt     The home as data: shelf ids, the catalogue, the saved
│                       order, pinMeta and the fail-closed resolvePins, the
│                       television's opening focus. Guard 47; no Compose in it
├── VideoMeta.kt        "Channel · 3 days ago": relativeAge and metaLine, one
│                       spelling for the phone, the TV and the hub's kid page
├── DesignTokens.kt     The one palette and type scale, as ARGB ints and sp
│                       numbers: KidHues, KID_DARK, KID_LIGHT, KidType, and the
│                       colour maths that derives a signal colour for a ground
├── KidSurface.kt       Every kid-facing surface: which faces draw it, what decides
│                       its contents, where a child reaches it. NOT documentation —
│                       guard 62 reads it and fails the build in both directions
├── KidWords.kt         What a CHILD reads when something will not play, beside
│                       HubPolicy.Decision's detail, which is what a PARENT reads
└── KidTokensCss.kt     The same table as CSS custom properties. Run by
                        :hub:generateKidTokensCss; never checked in (guard 48)

crawl/src/main/kotlin/io/yosemitekids/app/data/    network, disk, clock — plain JVM
├── Http.kt             The one OkHttpClient, and restrictTo() — the hub arms it
│                       at startup so the crawler can reach YouTube and nothing else
├── YouTubeRepository.kt / Extractor.kt / OkHttpDownloader.kt   NewPipeExtractor
├── SavedListStore.kt / QueueStore.kt   the kid's lists and lineup, here rather
│                                       than in :app since R3 so the hub shares
│                                       the merge (AndroidStores.kt is the
│                                       phone's factory)
├── PlaylistCrawlRun.kt   a channel's playlists, indexed daily after the index pass
├── PlaybackCache.kt / PlaybackBreaker.kt   resolved streams held 20 min, and the
│                                           stop after two failures in a row
├── AtomicWrite.kt        writeAtomically - temp file, fsync, atomic rename: the
│                         one way every store in the product writes a document
├── ChannelIndex.kt / IndexCrawler.kt / IndexCrawlRun.kt / IndexPull.kt
│                       The index keeps each video's date and view count (roadmap
│                       2M); a source YouTube refuses is marked gone, skipped for
│                       a day and never a failed run
├── PlaybackCache.kt    Resolved streams for twenty minutes, keyed on page URL and
│                       ceiling, forgotten whole on a playback failure
├── AiScreener.kt / ScreeningStore.kt   The screener and the verdict store
├── Screening.kt        "May this child see this video?" — the one predicate,
│                       beside the verdicts it reads, so the app and the hub
│                       answer with the same function (guard 46)
├── SearchRank.kt / SearchOrder.kt   How good a hit is for this child, and the
│                       orders the results screen can honestly offer, with their
│                       words (label); "Newest" since the index kept its dates
├── KidHome.kt          What a kid's home is made of, for both faces: keep
│                       watching, history, suggestions, interleave, and
│                       FINISHED_FRACTION - the line between "watched" and "in
│                       progress" that the shelves and the end card agree on
├── StreamChunker.kt    The range arithmetic googlevideo serves at speed, in one
│                       place for everything that fetches a stream
├── ui/KidOrder.kt      orderByPopularity / orderByWatched / orderChannels /
│                       filterVideos: how a shelf is sorted, on every face
└── QualityTargets.kt / PlaylistRef.kt / LocalUrls.kt / CrawlModule.kt

hub/src/main/kotlin/io/yosemitekids/hub/          the Docker container
├── Main.kt             Boot: data dir, arm the host allow-list, wire the store
│                       to the nudge, print (or withhold) the admin token
├── HubServer.kt        The one listener: every console and device route —
│                       see docs/LAN-API.md, and guard 30. The parents'
│                       session is a header, never a cookie (guard 59). It
│                       builds HubKidServer and registers its routes on itself
├── HubDash.kt          The DASH manifest a browser plays HD through
├── HubHttp.kt          The headers (and the CSP) every reply on either face carries
├── HubKidHome.kt       What every kid route answers: home, channels, search, You
├── HubKidHistory.kt    Where a browser got to in each video
├── HubMedia.kt         Ranges, content types, the stream and segment slots
├── HubPolicy.kt        The one answer to "may this child play this video"
├── HubQr.kt            The claim QR the console draws
├── HubSavedLists.kt    The kid's four lists, over :crawl's store
├── HubStream.kt        Resolved URLs, the DASH renditions, and the chunk pump
├── HubWatchMeter.kt    Minutes for a browser, and whether anyone is watching
├── HubKidServer.kt     The kid app: the page at /kid and every route under
│                       /kid/, a JSON 404 for everything else there, and no
│                       CORS header anywhere. The wall between it and the
│                       console is built from credentials — the kid cookie is
│                       scoped to /kid/ and the console never reads it — and
│                       a browser gets in by a parent's QR or the kid's own
│                       password (HubKidLock throttles that per kid).
│                       Guards 57-60
├── HubKidLock.kt       Five wrong passwords close one kid's door for a
│                       minute, doubling to fifteen. Never HubSessions
├── HubReports.kt       What the clients said went wrong: POST /report and
│                       POST /kid/report land here, printed to stdout as they
│                       arrive and kept in memory for the console's Device log
├── HubKidSearches.kt   Each kid's recent searches for the browser's search page,
│                       through RecentSearches in :core; the × and Clear all
│                       are POST /kid/search
├── HubBrowsers.kt      browsers.json: the one-shot codes a parent mints (the
│                       QR's payload) and the browsers that got in. A
│                       credential that can do nothing but play video, bound
│                       to one kid when it was minted
├── HubStore.kt         config.json on the volume. Stamped writes, key stripped
├── HubSecrets.kt       secrets.json: the AI key, served to a parent, never merged
├── HubUsage.kt         usage.json: the watch ledger. Its own file and its own
│                       lock — HubStore, the fingerprint, sync.log and the
│                       version ring are untouched by watch traffic (guard 44).
│                       The one calendar this box reads is the family's own
│                       (Whitelist.homeZone), never the container's (guard 27)
├── HubTokens.kt        devices.json: enrolments, their kind, address, last seen
├── HubPassword.kt      PBKDF2 verify/derive, shared shape with the phone's PIN
├── HubSessions.kt      Browser sessions and the escalating sign-in lockout
├── HubRate.kt          The window in front of /enrol, which checks no credential
├── HubWeb.kt           The GUI's data layer: /api/state, the patch allow-list
├── HubVersions.kt      The five-snapshot restore ring
├── HubNudge.kt         POST /sync-now — the only outbound call in this module
├── HubCrawl.kt / HubMaster.kt   The crawl on a timer, and claiming the master slot
└── DataDir.kt
hub/src/main/resources/web/index.html              the whole GUI: one file, no
                                                   build step, nothing from a CDN
hub/src/main/resources/web/kid.html                the kid's page, on the other
                                                   origin — a placeholder until
                                                   the web player's step 4
```

Other top-level directories:

| Path | What |
| --- | --- |
| `app/src/test/` | JVM unit tests (pure logic, org.json real impl). `ExtractorSmokeTest` hits live YouTube — excluded from the PR gate. |
| `core/src/test/`, `crawl/src/test/`, `hub/src/test/` | The shared rules' tests, each its own gate step. The merge's tests live in `:core` on purpose: in `:app` they would prove it works *on Android* and leave the container's copy uncovered. |
| `worker/` | Cloudflare Worker: suggestion form → PR, contact form → issue/discussion, app whole-list submission. `worker/test/` is `node --test`. |
| `site/` | pickwick.tv static site + `site/directory/*.json` (the community directory the app reads). |
| `whitelists/` | Importable themed channel lists. |
| `scripts/` | Developer harness: `check.ps1`/`check.sh` (the gate: source guards, build, tests), `guard-canary.sh` (proves each guard can fail; CI runs it), `guard-index.sh` (writes `docs/GUARDS.md`), `emu.ps1` (emulator loop), `upstream.*` (what upstream shipped). |
| `.claude/skills/` | Claude Code skills for this repo: `map` (where to start reading), `check`, `emulator`, `lan-api`, `release`, `sync` (config-field invariants), `upstream`. |
| `docs/` | This file, `ROADMAP.md` (the only forward-looking doc), `GUARDS.md` (generated guard index), `LAN-API.md`, `HUB.md`, `DEV.md`, `FORK-NOTES.md` (changelog), `SETUP.md` (end-user), `SCREENS.md`. `docs/archive/` is finished history (never updated; guard 66 keeps plans out of the top level); `docs/design/` the design handoffs for the parent settings and the kid player. |

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
        ├─ Home = KidHome (both shapes): greeting header (TimeChip /
        │    BlockedBanner), then UiState.homeSections in order — pinned
        │    hero, Channels, Keep watching, More like what you watch,
        │    Videos (control row + feed), Watched lately. Empty shelves
        │    collapse. Phone draws it as a LazyVerticalGrid whose cells are
        │    the feed; TV as a LazyColumn with the feed three across.
        ├─ Phone: bottom tabs Home / Channels / Favorites / Search
        │    Channels = ChannelsScreen (rounded tiles + shelves)
        │    Favorites = Watchlist with ShelfChips (Watch later / Up next / Downloads)
        │    Search = Screen.Search (field, mic, recents) → SearchResults
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

Everything above works from **one number**: `SessionGuard.spentTodayMs()` —
this device's own tally plus whatever peers have reported for the same kid on
the same day. The raw counter has exactly one reader (`ownWatchedMs`) and guard
45 holds it there, because seven enforcement sites and half a dozen screens all
deriving from it is precisely how a home screen ends up promising forty minutes
in front of a player that stops at ten. The peers' half is zero for every family
today unless a parent has shared a budget: `Limits.budgetScope` is the switch
that does it, it has shipped since 1.3.0, and `UsageSync` is what carries the
peers' halves between devices (`SessionGuard.peerMs`).

The **day** those counters bucket into is `FamilyDay`'s, and only ever moves
forward: `FamilyDay.rollover` keeps the later of the stored day and the clock's,
so a device whose clock steps backwards no longer zeroes the tally and hands out
a second budget. Guard 43 keeps one spelling of a day in `:core` and in the two
stores that enforce with it.

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
| `files/usage.json` (+ `usage-day.txt`) | The watch ledger: `(kid, day, device) → minutes`, this device's own cells and what it has learned from peers. Its own file and its own lock — a counter is **not** curation and never enters `config.json` (guard 44) | no (kids inside) |
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
| Change an icon, a chip or the channel art | `ui/Icons.kt` (the drawn Material Symbols), `ui/Components.kt` (`YosemiteChip`, `ChannelArt`, `NewPill`, `HeaderIconButton`); the "Channel · 3 days ago" line is `metaLine`/`relativeAge` in `:core` (`ui/VideoMeta.kt`), because the hub composes it for the browser too — emoji are content (avatars, cards), never chrome |
| Change a kid-facing colour or the type scale | `core/.../ui/DesignTokens.kt` — `KidHues` (the four signals), `KID_DARK` / `KID_LIGHT` (the two looks), `KidType` (the ladder). `Theme.kt` and `KidTokens.kt` only bind them to Compose, and `:hub:generateKidTokensCss` writes the same table out as `/kid-tokens.css` for the browser. Guard 48 fails if either face states a value of its own; `KidTokensParityTest` fails if the two derivations disagree. The parent-facing palette (`AdminDarkColors`, `Settings*`) is still Theme.kt's own |
| Change playback quality (Auto or a ceiling) | `NetworkQuality.kt` `QualityTargets` (`userMaxHeight`, `effectiveMaxHeight`), `Whitelist.qualityTv/qualityPhone`, the Playback settings page, `PlayerActivity.setQuality` |
| Change how many videos a grid shows before "Show more" | `Whitelist.pageSize` → `UiState.pageSize` → `VideoGrid(pageSize = …)` |
| Change the kid's sort/filter chips or their defaults | `HomeState.orderChannels` / `filterVideos` (pure), `KidPrefs` (per-kid persistence), `MainViewModel.setChannelSort` / `setHomeFilter` / `setChannelFilter`; chips in `HomeScreens.kt` (`ChannelSortChips`, `VideoFilterChips`) and `HomeShelves.kt` (`FeedControlRow`) |
| Add, reorder or restyle a home shelf | `core/.../ui/HomeSections.kt` (the id, the catalogue and its title in `homeShelfTitle` — one list, so a browser home gets the shelf too), then its branch in `HomeShelves.kt` `drawShelves`, its branch in `kid.html`, and its count in `HomeShelfCounts.kt` — guard 33 fails if you do only one, guard 47 if the model comes back into `:app`. A parent's arrangement is `Whitelist.homeRows` (one `HomeRow` per shelf per home, edited only through `HomeRows.withOrder`, guard 70), read by every face through `homeRowsFor`; the editors are `cardRows` in the console and the phone's Listing page Sizes are `homeMetrics`; the hero is `PinnedHeroCarousel` (phone) / `PinnedHeroRow` (TV) |
| Change what the pinned hero shows | Read: `Whitelist.pinsFor` → `MainViewModel.pinnedUrls` / `pinnedRow` → `resolvePins` in `:core` (fail-closed, generic over `PinnableSource` so the hub can call it, `core/src/test/HomeSectionsTest`). Write: `Pins.withRow` in `:core` — the only place a rank is minted, the cap applied or an invisible source refused (`PinsEditorTest`, guard 42) — reached from `SettingsPins.kt` on the phone and `cardPins()` in the hub's `index.html`, both declared as `listing-pins` in `SettingsSurface` |
| Change what a kid is allowed to see | `Screening.isVisible` / `needsScreening` in `:crawl` — the one predicate, so the app and the hub agree (`ScreeningVisibilityTest`, guard 46). `:app`'s `Screener` owns only the mutable state, the batching and the retries |
| Change the You tab | `YouScreen.kt`, `MainViewModel.youShelves` / `openYou` |
| Change the television's rail (a stop, its widths, the time card) | `TvNavRail.kt` (`RailStop`, `railStopFor`, the two widths — `TvNavRailTest` pins what still fits beside them), wired in `YosemiteScreen.kt` (`railShown`, the asymmetric gutter, the page's focus group). Guard 35 holds the focus-modifier order the collapse depends on |
| Change what "More like what you watch" suggests | `HomeState.suggestionsFor` / `titleKeywords` (pure — `SuggestionsTest` covers it), fed by `MainViewModel.suggestionsRow`, switched by `Whitelist.suggestSimilar` |
| Change the profile hub or the look editor | `ProfileHub.kt`; the sync-back is `data/ProfileLooks.kt` + `GET /looks` + `MainViewModel.syncConfigState` (`mergeLooks`) + `MainActivity.onChangeLook` |
| Change what rows sit above a channel's grid | `PlaylistShelves.kt` (`playlistShelves`, `newForYouRow`, `playlistRow`), assembled in `YosemiteScreen` (`header`); the data is `MainViewModel.loadPlaylistShelves` / `loadPlaylistRow` |
| Let the parent pick a channel's playlists | `SettingsChannels.kt` (`PinnedPlaylistsDialog`, from the source's own page) → `WhitelistEntry.playlistIds` |
| Change when the phone shrinks to PiP, or what the window does | `PlayerActivity.pipEligible` / `enterPip` / `onPictureInPictureModeChanged` |
| Add a screen-time rule | `Whitelist.Limits` + `ConfigStore` (de)serializers + `SessionGuard` + settings section |
| Change what a kid's minutes are measured against | `SessionGuard.spentTodayMs()` — the only place the own tally and the peers' figure are added, and guard 45 keeps it the only reader of `dailyWatchedMs` |
| Change how a day is decided anywhere a counter buckets by one | `FamilyDay` in `:core` (guard 43 makes it the only such place); the hub gets its zone from `Whitelist.homeZone` through `FamilyDay.zoneOrNull` and reads no calendar of its own (guard 27) |
| Carry watch minutes between devices | `UsageLedger` in `:core` (the join: grow-only cells, per-cell `max`, no clock), `WatchLedgerStore` on a device, `HubUsage` on the hub, `GET\|POST /usage` on both faces |
| Add a LAN route | `LanServer.handle` (bound every read!) + `LanClient` + `docs/LAN-API.md` (guard 14 checks the row is there) |
| Add a route to the **hub** | `HubServer.start` + a `private fun <name>(ex)` beside the others + `docs/LAN-API.md`'s hub table (guard 30). If it is a route a device also answers, `authorised(ex)` first (guard 29) and take it off `DEVICE_ONLY` (guard 22). Answer through `respond()`; a route that writes its own headers must call `securityHeaders(ex)` itself, and guard 41 counts |
| Add a route a **child's browser** calls | `HubKidServer.register` + a `private fun <name>(ex)` beside the others + a row in `docs/LAN-API.md`'s **kid** table + the path in guard 57(a)'s expected set. It lives under `/kid/`, must call `watching(ex)` and fail closed to the sign-in screen (guard 60), answer through `respond()` so it carries the security headers, and take the child from the credential — never from a query. It is registered from `HubKidServer` *only*: a kid path registered from `HubServer` is one that skipped the gate (guard 57) |
| Change how a child signs in to the kid app | `HubKidServer.claim` (the two doors: a parent's code, the kid's password), `HubKidLock` (the per-kid throttle), `KidPassword` in `:core` (the record both faces derive and verify), `Profile.webPassword` and its `kid.web` merge unit; the phone's row is in `KidPage.kt`, the console's card in `index.html` under `kid-web-password`, both declared in `SettingsSurface` |
| See what went wrong on a device, or make something new report | `Diag.w` / `Diag.e` in the app (guard 68 refuses a raw `Log.w`) and `report()` in `kid.html`; the ring drains in `ConfigSync.sweep` through `LanClient.report`; `HubReports` prints and keeps it, `/api/state` carries `reports`, the console's `reportsCard` draws it. `docker logs yosemite-kids-hub` has the same lines |
| Offer a search order, or change a chip's words | `SearchOrder` in `:crawl` (`ALL`, `label`, `order` - the phone's `SearchOrderChips` and the hub's `HubKidHome.search` both read it; the page draws `orders` from the reply). "Newest" needs `publishedAt`, which the index keeps since 1.9.0; anything ranked by views is deliberately not offered |
| A channel YouTube says is gone | `IndexCrawlRun.goneReason` decides (a `ContentNotAvailableException` in the cause chain), `ChannelIndex.markGone` remembers it with YouTube's words and `GONE_RETRY_MS` says when to try again; it shows on the console's Channels row and Devices index card (`state.index.gone`) and in the phone's index list. A page that arrives clears it |
| Change how long the player keeps a resolved stream | `PlaybackCache` in `:crawl` (`TTL_MS`, `MAX_ENTRIES`), in front of `YouTubeRepository.resolvePlayback`; `HubStream` keeps its own for the browser with the same numbers. `PlaybackBreaker.MAX_CONSECUTIVE` is the other knob on that path |
| The recent-search chips, on any face | `RecentSearches` in `:core` is the rule; the phone's `SearchHistoryStore` and the hub's `HubKidSearches` hold the lists; the page's `renderSearchPage` draws the hub's and posts the × to `/kid/search` |
| A playlist in the browser, or the playlist crawl's pace | `PlaylistCrawlRun` (`PLAYLISTS_PER_SOURCE`, `FETCHES_PER_RUN`, `REFRESH_MS`), run by `HubCrawl` after each successful index run; `ChannelIndex.loadPlaylists`/`savePlaylists`; `HubKidHome.playlists`/`playlist` answer `/kid/playlists` and `/kid/playlist` with the rows the kid may see; the page's `playlistCard`, `renderPlaylists`, `renderPlaylist`. The phone lists playlists live and is untouched |
| HD in a browser, or the most a browser is offered | `HubDash` (`MAX_HEIGHT`, `mpd`) writes the manifest from `YouTubeRepository.dashStreams`; `HubStream.dash` and `stream` hold and resolve the renditions; `HubKidServer.dash` serves it and `media` takes the rendition's `s=`; the page's `attachStream` plays it through dash.js (`/kid/dash.js`, vendored) and falls back to the muxed stream on any error. The gate is `HubPolicy.mayPlay` in both, per chunk |
| Record how the television draws a kid surface, or that it skips one | `KidSurface`: `tvWhy` for an adaptation (the rail, the remote, ten-foot tiles), `onTv = false` + `tvWhy` for a skip; guard 62(f) requires the reason and the gate prints the skips. The TV keeps its QR-only settings and its rail on purpose |
| Make a parent's setting reach every kid face | `SettingsSurface.honouredBy` (`phone`, `tv`, `web`) on the control; a control honoured by `web` must be read in `HubKidHome`/`HubKidServer` through the phone's own `:crawl` function (guard 69), and one the browser cannot honour says why in `honourWhy` - the gate prints those. Page size is honoured by `HubKidHome.page`; the browser asks for the next page with `from=` and caps nothing itself (guard 61) |
| Bump the **hub's version** | `val hubVersion` in `hub/build.gradle.kts`, kept equal to the app's `versionName` by guard 39. It rides `GET /health`, `GET /status` and the admin page, and it is the only way to tell whether a container is old enough to drop config keys it does not model on the next save |
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
peer, needing no server. `docs/archive/PLAN-sync.md` has the design;
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
