# Screen inventory

Every kid-facing screen, what state feeds it, where the code is, and how to
reach it on the emulator (`scripts/emu.ps1`; phone serial `emulator-5554`,
TV `emulator-5556` via `$env:PICKWICK_SERIAL`). Seed first: `seed` (three
channels, 15-minute session) or `seed --real` (the family's whitelist).

| Screen | `Screen` value | State it reads | Composable | Reach it |
| --- | --- | --- | --- | --- |
| Who's watching | (activity-level) | `family.profiles`, per-kid `remainingTodayMin` | `ProfilePicker.kt` `WhosWatchingScreen` | seed a config with 2+ profiles on a shared device |
| Home (phone) | `Home` | `channels`, `keepWatching`, `feed`, `channelAvatars`, `remainingMs`, `blockReason`, `allHeld` | `HomeScreens.kt` `PhoneHome` | `launch` |
| Home (TV) | `Home` | same + `recentHistory` (Watched lately row) | `HomeScreens.kt` `TvHomeRows`, `PlaylistShelves.kt` `ShelfVideoTile` | TV AVD, `launch`; focus starts on the top video row |
| Channels tab | `Channels` | `channels`, `newBadges`, `queued`, `watchLater`, `downloaded` | `HomeScreens.kt` `ChannelsScreen` | tap the Channels tab / "Show all" |
| Channel page | `ChannelVideos(source)` | `videos` (ordered by `channelLayout`), `channelWatched`, `watchedTileAt` (History tile first), `channelPlaylists` (the Playlists row, `PlaylistShelves.kt` `playlistRow`), `held`, `loadingMore` | `PickwickScreen.kt` → `VideoGrid` (+ `header`) | tap a channel chip; hold a poster for the menu; a playlist chip opens the playlist, Back returns (`goBack`); TV: Up from the grid reaches the Home / Favorites / History chips |
| Channel history | `WatchedVideos(source)` | `channelWatched` | same grid | tap the History tile on a channel page |
| Global history | `History` | `videos` = `historyItems(...)` | same grid | Favorites tab → 🕘 History chip |
| Favorites / Watch later / Up next / Downloads | `Watchlist` / `WatchLater` / `Queue` / `Downloads` | the saved-list stores | `VideoGrid`, `QueueList` | Favorites tab chips |
| Surprise | `Surprise` | shuffled cache pool | `VideoGrid` | 🎲 chip in the channel row |
| Search | `Search` → `SearchResults(q)` | `recentSearches`, `searchScreening` | `SearchField`, `VideoGrid` | Search tab; mic needs a real device |
| Player (phone, portrait) | (PlayerActivity) | intent extras, `SessionGuard`, `moreFromChannel` (= `channelCandidates()`) | `PlayerActivity.kt` `PortraitPlayerScaffold` + `PlayerControlsOverlay(compact)` | tap any poster; `wait-stream`; tap the video for controls; the list below scrolls |
| Player (phone landscape / TV) | (PlayerActivity) | as above | `PlayerActivity.kt` `PlayerStage` + `PlayerControlsOverlay` | `rotate`, or ⛶ in the portrait slot; TV always |
| Mini-player (PiP, phone) | (PlayerActivity, `inPip`) | `pipEligible()` | the stage with every overlay hidden | `home` or `back` while a video plays; `dumpsys activity activities` shows `mode=pinned` |
| Up next / end card | (PlayerActivity `EndCard`) | queue + `channelCandidates()` | `EndCardOverlay` | drag the scrubber to the end |
| Blocked / time-up | (PlayerActivity) | `SessionGuard.checkStart/tick` | `BlockedCard` | seed a 2-minute session and keep watching |
| Error | (PlayerActivity) | `errorState` | `ErrorCard` | pull the emulator's network |
| Parent settings | (SettingsFlow) | `ConfigStore` | `Settings*.kt` | gear → PIN 0000 on a fresh install (you set it) |

## Ordering rules in one place

| What | Rule | Setting |
| --- | --- | --- |
| Channel row / Channels tab | most opened (`UsageStore.opens`), A–Z, or shuffled per refresh | *Channel row order* |
| Channel page | upload order, by view count (never shown), or a Playlists row above the grid | *Channel page layout* |
| Home feed | one from each channel in row order, then seconds… (`interleave`) | — |
| History | `lastWatchedAt` descending | — |
| Keep watching | `lastWatchedAt` descending, half-done only | — |
| Surprise | shuffled | — |

## TV specifics

- Focus lives on tiles; the ring is `tvFocusHighlight`. Anything new that is
  tappable on the phone needs a focusable on TV or a key in `onKeyDown`.
- Rows scroll with `TvRowPivot`; grids use `dpadHeldScrollThrottle`.
- The player has no focusables at all — every key is handled in
  `PlayerActivity.onKeyDown` (two-button cards use a cursor).
- Drive it: `dpad right 3`, `dpad ok`, `hold-ok` for the menu, `dpad down`
  in the player for the track toolbar, `dpad back`.
