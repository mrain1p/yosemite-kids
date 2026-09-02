# Round 3 plan — scale, history, TV parity, upstream tracking

Status: **done (2026-09-02)** — every item below shipped in 0.8.1-fork; see FORK-NOTES "Round three" for what changed and what was verified. Originally: Each item lists what changes, where,
how it is tested, and a size (S ≤ half a day, M ≈ a day, L = 2+ days).

The family list that drives this: 50 sources, ~15.5k indexed videos, a mix
of big channels (Peppa Pig 3.8k, Numberblocks 2.4k, TED-Ed 2.4k) and
playlist seasons (StoryBots). That is the scale every screen must stay fast at.

## 1. Bug: channel name on a card opens the video

Today the whole `VideoCard` is one click target. The avatar + channel name
row becomes its own target: tap → that channel's page; the poster and title
still play. Same on the Keep watching cards and the TV tile.

- Files: `ui/Tiles.kt` (`VideoCard` gets `onOpenChannel`), `ui/VideoGrid.kt`,
  `ui/HomeScreens.kt`, `ui/PickwickScreen.kt` (wire `vm.openChannelByName`).
- Test: emulator tap on a card's channel row lands on the channel page.
- Size: S.

## 2. Watched videos: a History tile first, nothing hidden

Today a channel's watched videos sink into a "Watched (N)" tile pinned at the
end of the first page and disappear from the grid. Change to:

- A **History** tile is the *first* item on the channel page (when anything
  has been watched there), opening the channel's watched videos newest-first.
- Watched videos **stay in the grid** in their normal position, dimmed with
  the full red bar (the sort no longer moves them).
- A global **History** shelf: a chip on the Favorites tab ("🕘 History")
  listing everything the kid has watched, newest first, across channels —
  joined from `WatchHistoryStore` + the per-channel caches the way `/stats`
  already does. Hold-menu on any item still offers *Move back to not watched*.
- Files: `ui/MainViewModel.kt` (`publishChannel` / `splitWatched` no longer
  removes; new `openHistory()`), `ui/HomeState.kt` (`Screen.History`),
  `ui/VideoGrid.kt` (`HistoryTile` replaces `WatchedShelfTile`),
  `ui/PickwickScreen.kt` (chip + title), TV rows get a History row.
- Test: unit test for the history join (pure function over history map +
  cache lists); emulator: channel page shows History tile first, watched
  video still listed; Favorites → History lists both channels' videos.
- Size: M.

## 3. Channel page: sort and group, without clutter

A parent setting, **Channel page layout**, applied per kid device:

| Option | What the kid sees on a channel page |
| --- | --- |
| Newest first (default, today's behaviour) | the upload feed |
| Popular first | the same videos ordered by YouTube view count, **no counts shown anywhere** |
| By playlist | the channel's own playlists as rows ("Season 1", "Songs"…), then "All videos" |

Implementation:

- **View counts**: add `viewCount: Long?` to `Video`, filled from the
  extractor (`StreamInfoItem.viewCount`) and stored in the TSV cache as an
  extra column (old caches read as null → falls back to newest-first, no
  migration needed). Never rendered.
- **Playlists**: fetch a channel's Playlists tab (NewPipe `ChannelTabs.PLAYLISTS`)
  once per day into a new `ChannelPlaylistsCache` (id, title, thumb, count).
  The channel page shows them as horizontal rows of posters; each row's
  videos load on demand through the existing playlist path (so they play
  in order with the Up next card). Channels with no playlists fall back to
  the feed silently.
- **Scale guards** (this is where 50 channels bites):
  - `VideoCache` gets an in-memory layer keyed on file mtime — today every
    home refresh re-parses 50 TSV files (Keep watching, badges, feed,
    all-held check each walk them). One parse per file per change.
  - The feed caps per-channel contribution (already 12) and total (80);
    the History shelf pages at 60.
  - A `LargeLibraryTest` builds 50 synthetic channels × 500 videos and
    asserts feed/history/interleave finish in well under 100 ms on the JVM.
- Files: `data/YouTubeRepository.kt` (Video field, tab fetch), `data/Tsv.kt`
  / `VideoCache.kt` (column + memo), new `data/ChannelPlaylists.kt`,
  `Whitelist.kt` + `ConfigStore.kt` (`channelLayout` setting, in the
  fingerprint), `ui/Settings.kt` (Playback section), `ui/MainViewModel.kt`,
  `ui/PickwickScreen.kt` / `HomeScreens.kt` (playlist rows), tests.
- Test: `VideoCacheTest` round-trips the new column and reads old rows;
  `ChannelLayoutTest` sorts by popularity with nulls last; emulator with
  the real 50-channel list as the seed; `ExtractorSmokeTest` gains a
  playlists-tab probe (network, canary only).
- Size: L (playlists are the bulk; popular-first alone is S).

## 4. TV parity

The TV home still has three rows and the old square tiles. Bring it level
with the phone without losing the remote ergonomics:

- Rows: Keep watching, **New for you** (the feed as 16:9 posters with
  duration badge and channel name — the card style, focus ring kept),
  Channels (round chips), History (once anything is watched), Explore.
- Channel page on TV: the same History-first tile, and playlist rows when
  the layout setting says so (D-pad moves row to row like the home).
- Player on TV: the avatar/name row shows; the remote gets a **Channel**
  action on the track toolbar (▼) next to Audio/Subtitles, and ⏺/red
  button = heart where the remote has one.
- Voice search on TV: the Google TV keyboard's own mic already feeds the
  field; nothing to add.
- Files: `ui/HomeScreens.kt` (`TvHomeRows`), `ui/Tiles.kt` (TV variant of
  `VideoCard`), `ui/PlayerActivity.kt` (toolbar item), `ui/PickwickScreen.kt`.
- Test: an **Android TV AVD** (`pickwick_tv`, 1080p) added to `scripts/emu.ps1`
  with D-pad helpers; screenshots of home, channel page, player overlay, end
  card driven by key events. This is the first time the fork's TV layout will
  be seen at all, so expect a fix-up pass.
- Size: M (+ the AVD download).

## 5. Upstream tracking (skill + script)

`scripts/upstream.ps1` (and `.sh`):

1. Adds the `upstream` remote (itcon-pty-au/pickwick) if missing, fetches.
2. Lists commits on `upstream/main` since the fork point (`7ce27f9`), with
   the files each touched, and marks which of those files the fork has also
   changed (merge risk) versus files the fork left alone (safe to cherry-pick).
3. Writes `docs/UPSTREAM-LOG.md`: a dated section per run with "new upstream
   commits", "touches fork-modified files", "recommended action" left blank
   for review, plus upstream's `version.json` (extractor bumps matter most —
   a NewPipe bump is the one thing to take promptly).

Skill `.claude/skills/pickwick-upstream`: run the script, read the log,
propose per-commit "cherry-pick / port by hand / skip" with reasons, and
never merge automatically. Optionally a `/loop`-able weekly check.

- Size: S.

## 6. Guards and harness additions

- `scripts/emu.ps1`: `tv` verb finished (AVD creation + D-pad helpers
  `dpad left|right|up|down|ok|back`, `hold-ok`), `seed --real` to push
  `scripts/seed-config.real.json` built from your whitelist (kept out of
  git via `.gitignore`, the file is family data).
- Tests: `LargeLibraryTest`, `HistoryJoinTest`, `ChannelLayoutTest`,
  `VideoCacheTest`, worker unchanged.
- `docs/SCREENS.md`: one page listing every screen, what state feeds it, and
  the emulator command that shows it — the "traverse" aid for UI work.
- CI: unchanged in shape; the new tests are picked up automatically.

## Order of work

1. Item 1 (channel row tap) and item 5 (upstream script) — quick, independent.
2. Item 2 (History) — changes the channel-page model the rest builds on.
3. Item 3a: view counts + Popular first + the cache memo + LargeLibraryTest.
4. Item 4: TV AVD, TV rows/cards, TV player additions; screenshot pass.
5. Item 3b: playlists tab + rows (phone and TV).
6. Full check, emulator passes (phone with your real list, TV), signed APK,
   docs and FORK-NOTES update.

## Decisions to confirm (defaults in bold)

- Channel page layout is **one family-wide setting** (simplest); per-kid or
  per-channel is possible later.
- Popular-first uses **lifetime view count** from YouTube (no dates needed);
  "trending this month" would need upload dates too — skip for now.
- History shelf shows **everything ever watched, newest first, paged** (not
  just finished videos); Keep watching stays the "half-done" row.
- On TV the heart lives on the ▼ toolbar; **no dedicated remote key**.
