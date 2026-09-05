# Round 4 plan — phone mini-player, portrait player, device reach

Status: **done** — all three items shipped in 0.8.3-fork (32), 2026-09-02.
Item 1 went with option A (system picture-in-picture), item 2 as written,
item 3 with round 3. What was built and what was verified is in
`FORK-NOTES.md` ("Round four"); the plan below is kept as the record of
the options weighed.

## 1. Mini-player on phones ("keep watching while I browse")

What the kid gets: pressing the player's minimise button (or Back) shrinks
the video into a small card pinned above the bottom tabs — poster-sized,
still playing, with play/pause and ✕ — while the home, channel and search
screens work as normal underneath. Tapping the card brings the full player
back where it was. Choosing another video replaces what is playing.

Two ways to build it; pick one before starting:

| Option | How | Pros | Cons |
| --- | --- | --- | --- |
| **A. System picture-in-picture** | `PlayerActivity.enterPictureInPictureMode()` on minimise/Back (API 26+, already the min); the OS floats the video over `MainActivity` | ~1 day; the OS handles the window, drag, expand; works over other apps too | window is the system's (no brand chrome, no tab-bar pinning); some launchers/Fire OS phones disable PiP; the kid can drag it anywhere |
| **B. In-app mini bar** | Move playback into a foreground `MediaSessionService` (the `ListenService` already does audio-only); `MainActivity` hosts a `MiniPlayerBar` composable that binds to the service's `ExoPlayer` and shows a `PlayerView`; `PlayerActivity` becomes the "expanded" state (or a full-screen composable in the same activity) | YouTube-like, pinned above the tabs, brand-consistent, no OS quirks | 3–4 days: one `ExoPlayer` shared across two surfaces, session-time ticking and end cards have to move into the service, Back/queue/autoplay state moves with it |

Recommendation: **A first** (it is cheap and honest), **B** only if the
family finds the floating window annoying. Either way the TV is untouched.

- Files (A): `ui/PlayerActivity.kt` (minimise button on the overlay,
  `onUserLeaveHint` → PiP with the video's aspect ratio, hide the overlay
  in PiP via `onPictureInPictureModeChanged`, keep `SessionGuard` ticking),
  `AndroidManifest.xml` (`android:supportsPictureInPicture="true"`,
  `configChanges` for the PiP resize), `ui/YosemiteScreen.kt` (nothing —
  the app keeps running under the window).
- Tests: emulator screenshot of the PiP window over the home; the
  countdown/end card while in PiP (should auto-expand or skip the card);
  session time still drains in PiP; blocked/time-up ends PiP.

## 2. Portrait player with rotate-to-fullscreen

Today the player forces landscape. Change to YouTube's shape on phones:
open in portrait with the video at the top (16:9), the title, the channel
row, the heart, then **"More from <channel>"** / Up next as a list below;
rotate the phone (or tap ⛶) for full-screen landscape; rotate back to
return. TV keeps full-screen always.

- Files: `ui/PlayerActivity.kt` (drop `screenOrientation=landscape` on
  phones; `configChanges` so rotation does not restart playback; a
  `PortraitPlayerScaffold` composable; the existing overlay becomes the
  landscape/full-screen mode), `AndroidManifest.xml`.
- The list below the video reuses `ShelfVideoTile`/`VideoCard` and the
  channel candidates the end card already computes (`channelCandidates()`).
- Tests: rotate on the emulator (`emu.ps1 rotate`) both ways without a
  reload; the mini-player (item 1) shrinks from either orientation.

## 3. Device reach and versioning (done in 0.8.2-fork)

- Fire TV crash `NoSuchMethodError: URLDecoder.decode(String, Charset)`
  — the extractor uses a Java 10 API that Android only gained in 13 (API
  33). Fixed by enabling **core library desugaring** (the NewPipe app does
  the same to run on old Android). Any device on Android 9–12 was affected:
  Fire TV Stick (Fire OS 7/8), older phones.
- The version is now visible: **Settings → top line** and on the error
  card's grown-ups line, e.g. `Yosemite Kids 0.8.2-fork (31)`. Every build bumps
  both `versionCode` and `versionName` (see `/yosemite-kids-release`).

## 4. Order of work

1. Item 3 shipped with round 3's commit.
2. Item 1 option A (one day), verified on the phone emulator.
3. Item 2 (two to three days), then re-verify item 1 on top of it.
4. Consider B only after the family has lived with A.
