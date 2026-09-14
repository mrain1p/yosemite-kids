# Round 5 plan — settings hub, You tab, kid-facing sort and filters, playlists, profile hub

Status: **done** — shipped as 0.9.0-fork (33), 2026-09-02. Decisions below are the user's answers;
what shipped is in `FORK-NOTES.md` ("Round five").

## Decisions

1. **Upstream port, all of it.** Upstream `1ae4cf1` (settings hub, per-kid
   pages, autosave, per-kid pause, short-video rule) is cherry-picked whole.
   Upstream is checked at the start of every round (`/yosemite-kids-upstream`).
2. **A kid's look change travels back to the parent's phone.** A device
   records the change, the phone's reconcile sweep pulls it (`GET /looks`),
   merges it into the config (newest `lookAt` wins) and pushes as usual.
3. **Playlists are the channel's own.** The parent ticks which of a channel's
   playlists show as rows on its page; nothing is mixed across channels.
4. **Sort and filter chips are kid-facing**, remembered per kid
   (`KidPrefs`); the parent's settings are the defaults.
5. **TV and phone both**, with the TV getting the D-pad shape of each
   (rows and focusable chips), never the phone's layout.
6. Random reshuffles only on a refresh or reopening; choices persist per kid.

## Work

| # | Feature | Where |
| --- | --- | --- |
| 1 | Profile chip → hub sheet (switch kid, change my look, parent settings 🔒); gear leaves the headers | `HomeScreens.HomeHeader`, `ProfileHub.kt` (new), `MainActivity` |
| 2 | Kid look editor, shared with the parent's kid page; sync back (`ProfileLooks` store, `GET /looks`, reconcile merge) | `ProfilePicker.kt`, `data/ProfileLooks.kt` (new), `Pairing.kt`, `MainViewModel` sweep |
| 3 | You tab (replaces Favorites): avatar header, rows for Favorites / Watch later / Up next / History / Downloads with See all | `YosemiteScreen`, `YouScreen.kt` (new), `MainViewModel.youShelves` |
| 4 | Channels tab: two columns; sort chips Most watched / A to Z / Random / Latest video | `HomeScreens.ChannelsScreen`, `HomeState.orderChannels` |
| 5 | `publishedAt` on `Video` (7th cache column) so Latest and New are real | `YouTubeRepository`, `VideoCache` |
| 6 | Home feed + channel page filter chips New / Random / Popular | `PhoneHome`, `YosemiteScreen` channel header, `HomeState.filterVideos` |
| 7 | Parent picks a channel's playlists → rows on the channel page (shorts dropped) | `Source.playlistIds`, `ConfigStore`, `SettingsChannels`, `MainViewModel.openChannel`, `PlaylistShelves` |
| 8 | Dividers between home sections; "New for you" row on channel pages; "New from <channel>" in the portrait player | `HomeScreens`, `YosemiteScreen`, `PlayerActivity` |
| 9 | TV: hub dialog, You page (rows), sort/filter chips in row titles, playlist rows | `TvHomeRows`, `TvTopChips`, `YouScreen` |

Version: 0.9.0-fork (33).
