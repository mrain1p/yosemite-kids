# Handoff: Parent settings redesign (Pickwick fork)

## Overview
A rebuild of Pickwick's **parent settings** area: the hub root plus every page under it,
restructured from four thin sections into two plainly-named ones, with the daily
screen-time errand pulled up to the root and warnings surfaced from two levels deep.
The prototype is a navigable phone-sized flow at **344dp** (Galaxy Fold 5 cover width)
with a real push/pop back stack, so navigation — not a static picture of it — is what
is being handed off.

Target: the existing Android app in this repo (Kotlin + Jetpack Compose, Material 3).

## About the Design Files
`Pickwick Settings.dc.html` in this bundle is a **design reference created in HTML** —
a prototype showing intended structure, copy, state and behaviour. It is **not production
code to port**. The task is to recreate it in the app's existing Compose environment,
using the app's established patterns: `SettingsFlow`, `SubPage`, `SettingsCard`,
`SettingsDivider`, `HubRow`, `CompactButton`, `PickwickIcons`, and the existing
section composables in `ui/Settings*.kt`. Reuse the real stores (`ConfigStore`,
`SettingsStore`, `Profiles`, `WhitelistRepository`, `AiScreener`, `Pairing`,
`Backup`) — the prototype's data is seeded fixtures standing in for them.

Open the file directly in a browser (`support.js` must sit beside it). Click any row to
push; the header chevron pops. `screens/contact-sheet.png` shows all 14 screens side by
side for the visual direction, each expanded to its full scroll height. Only the states
that need a tap are absent from the stills: sheets, confirm dialogs, select mode, the
async button labels and the push/pop transition — those are in the prototype.

## Fidelity
**High fidelity.** Colours, type, spacing, radii, hit targets, pressed states and copy are
final and should be matched. Two caveats:
- The prototype is CSS px at a 344px frame; read every px as **dp**.
- Switches, sheets, dialogs and the app bar are hand-built approximations of Material 3.
  Use the real M3 components (`Switch`, `ModalBottomSheet`, `AlertDialog`, `TopAppBar`)
  themed to the tokens below rather than reproducing the HTML mechanics.

## Removals — must be deleted, not hidden
### Support Pickwick (removed entirely)
The "Support Pickwick" entry point and its page are **gone from this design**. Remove
from front end **and** back end. Do not hide behind a flag; do not leave unreachable.

Front end:
- `app/src/main/java/io/pickwick/app/ui/Settings.kt` — the `ElevatedCard` at
  **lines ~1202–1225**: heart glyph, "Support Pickwick",
  "Free forever — but not maintenance-free", and its `uriHandler.openUri(...)` click.
  Delete the surrounding comment about donations funding the re-shipping treadmill and
  the now-unused `val uriHandler` if nothing else on the page uses it.
- Any Support Pickwick screen/route and its `Screen`/`HubPage` destination, the
  "Contribute" button and any donation copy, links or intents it fired.
- Any string resources, icons or drawables used only by it (check
  `res/values/strings.xml` and `res/drawable*`).

Back end / shared:
- Any config, remote flag, URL constant, analytics event or build config referring to
  donations / contributions / sponsorship. The only in-app hit at time of writing is the
  hard-coded `https://pickwick.tv/donate.html` in `Settings.kt`; grep
  `donat|sponsor|contribut` across `app/`, `core/`, `hub/` and `gradle/` before
  declaring done.
- Any related entry in `ConfigStore` or settings serialisation, **plus a migration** so
  existing installs drop the stored value rather than keeping a dead key. (No such key was
  found in the current source — confirm, and if none exists, no migration is needed.)
- Repo-level surfaces that are not the app (`site/donate.html`, `site/index.html`,
  `site/directory.html`, `README.md` badge and "Support Pickwick" section) are **out of
  scope for this design change** — ask before touching them.

Do not reintroduce it. If a future request implies a donate/support surface, ask first.

## Screens / Views
Fourteen destinations, one back stack. Titles are the app-bar strings.

| key | Title | Reached from |
|---|---|---|
| `root` | Parent settings | entry |
| `kids` | Kids | root → Kids |
| `kid` | Amelia | root kid card, or Kids → kid row |
| `channels` | Channels & playlists | root |
| `listing` | How videos are listed | root, and bottom of Channels |
| `screening` | Content screening | root |
| `devices` | Devices & sync | root, Devices tile |
| `phone` | This phone | Devices → this phone's card |
| `playback` | Playback | root |
| `backup` | App, hub & backup | root, Backup tile, hub row |
| `ytsearch` | Add from YouTube | Channels → + → Search YouTube |
| `review` | Waiting for your OK | root amber banner, Screening |
| `blocked` | Blocked videos | Screening |
| `digest` | Weekly digest | Devices |

App-bar actions: `root` → "Done"; `channels` → "Select" / "Done" + "+";
`review` → "Clear filter" when a filter is active; others none. Back chevron shows on
every layer except the first (44×44dp target).

### root — Parent settings
Scroll, 14dp horizontal padding.
1. **Kid card** (`#1E1D24`, 1px `#3A3744`, radius 10). Row: 40dp avatar circle with
   gradient `160deg #F0407F → #C41358` and the kid's emoji, a 12dp status dot
   (`#7FC8A9` watching / `#8B8794` idle) ringed 2px in the card colour; name
   (500 15.5/1.3) + status; second line, now-playing, ellipsised (400 12.5/1.4 `#8B8794`).
   Below: time-left label (500 13.5) + used label, a 5dp progress bar (track `#101014`),
   an optional "+N min bonus granted today" line in `#7FC8A9`, then two 32dp buttons —
   **Add time** (filled teal, opens the time sheet) and **Pause / Resume** (outlined).
   *This is change #3: the daily errand never requires the 16-control detail page.*
2. **"Waiting for your OK" banner** when the review queue is non-empty: amber surface
   `#221D14`, border `#5A4A30`, count chip `#E0B77E` on `#231B10`, title in `#E0B77E`,
   sub "Videos the AI held back until you decide". Pushes `review`.
3. **Two status tiles**, 2-col grid, gap 8: name (12/1.35 `#8B8794`) over state
   (500 13.5, tone = neutral / warn / danger). These carry the offline TV and
   "never backed up" up to the root (change #6).
4. **Two nav sections** — labels *Kids & content* and *App & devices*, sentence case,
   500 12.5 `#8B8794`; rows 60dp min, 22dp neutral icon, name + live state line,
   chevron. Rows: Kids · Channels & playlists · Content screening / Playback ·
   How videos are listed · Devices & sync · App, hub & backup.
   Every state line is derived from live values (source counts, held count, autoplay,
   quality, paired/offline counts, version + last backup).
5. Version + build line, "Changes apply as you make them", and a single
   **pause-everyone** switch (the old "Turn off all watching" + "Pause for today" merged),
   with its explainer behind a **?** disclosure.

### kids — Kids
Explainer, then one row per kid (name, age, avatar, live state), then **Add a kid**.

### kid — Amelia
Identity: name, age as a value row (was −/+), colour from 8 swatches, avatar from 22
options, both inline; profile lock / set code.
Rules as value rows stepping through fixed option sets:
Time per session (Off/15/30/45/60 min) · Sessions on weekdays (Off/1/2/3) ·
Sessions on weekends (Off/1/2/3/4) · Break between sessions (Off/10/20/30 min) ·
Hide videos shorter than (Off/60 sec/2/5 min, Shorts note behind ?).
A live "no limits set" summary line reflects the current combination.
Then blocked times (list + Add), bonus watch time + Grant, pause-this-kid switch,
and **Remove Amelia** (destructive).

### channels — Channels & playlists
Search field over ~56 source rows (name, kind meta "Playlist", per-kid assignment, NEW
badge when `new > 0`). Sort control. **Select** mode turns rows into a bulk-select with
a delete affordance and an undo snackbar. **+** opens a sheet: *Search YouTube* ·
*Paste a channel or playlist link* · *Suggested channels / directory*.
Per-row per-kid switch and screening controls moved to the individual source page.
Bottom of the page links to **How videos are listed**. Help paragraphs stay in place.

### listing — How videos are listed
The four shelf defaults that used to live on Channels (change #2): channel row order,
channel page layout, show when a video came out, videos before "Show more".

### screening — Content screening
Ordered so the dependency comes first (change #4): provider chips (5), API base URL, API
key, model picker, full-width **Test connection** (Testing… → "Reached the model ✓" or
"Test again" + a red failure line). *Then* the two switches — **Screen new videos with
AI** and **Find channels with AI** — with an amber notice when no connection is set.
Rows to **Blocked videos** and **Waiting for your OK**. Discovery: suggest field +
button, verified count, result rows with **Add / Added ✓**, labelled YouTube inspect link.

### review — Waiting for your OK
Filter chips (All / held / no verdict). Each item: title, channel, the model's reason.
Actions allow / block, **Trust <channel>** behind a confirm dialog, and an undo affordance
after a decision. Empty state when the queue clears.

### blocked — Blocked videos
Filter (All / you / AI). Rows show title, channel, and who blocked it and when; unblock
returns the video.

### devices — Devices & sync
Subhead: settings id + edited time, "checks every few minutes". **Check now** is an app-bar
action (Checking… → Check now). Collapsed **hub row** (change #5) expanding to hub IP,
admin token and "Connect my TVs". Device cards, one per paired device: name, status,
44dp rename pencil, and compact action buttons that vary by group —
hub: Hub setup / Remove; this phone: This phone's settings; other parent: Remove parent;
kid device: Stats / Unpair; all get Rename. Destructive actions confirm with a dialog that
states the consequence. Then **Weekly digest** and **Recent changes + See all**.

### phone — This phone
Everything device-local that used to sit on Devices: this phone's name, search index +
rebuild (Working… → Rebuild index) + read more, offline downloads + download quality,
videos from this phone.

### playback
Four switches, each with a **?** explainer: Skip sponsors & intros · Keep playing when the
phone locks (was a pill) · Autoplay the next video · More like what you watch. Then picture
quality on TVs and on phones & tablets.

### backup — App, hub & backup
Version + **Check for updates** (Checking… → Up to date), hub setup, then rows: Share… ·
Save to file… · Import from file… · Submit list to directory… · Full backup… (with
last-backup state) · Restore backup…. Both explainers behind ?.

### ytsearch — Add from YouTube
Query field, kind filter, result rows (channel/playlist, subscriber or video count,
blurb) with **Add / Added ✓**.

### digest — Weekly digest
Period summary of what was watched; maps to the existing `WeeklyDigestScreen`.

## Interactions & Behavior
- **Navigation:** a stack of screen keys. Push slides the new layer in from
  `translateX(100%)` with shadow `-12px 0 30px rgba(0,0,0,.45)`; the layer behind goes to
  `translateX(-22%)` at `opacity .35`. Pop reverses. Transition
  **280ms `cubic-bezier(.2,.85,.25,1)`** on transform and opacity. In Compose this is the
  standard M3 push/pop; match the duration and easing.
- **Pressed states:** every row and button has one — rows `#1E1D24` (or `#25242C` on the
  elevated card), icon buttons `#1E1D24`, primary button `#7EC8B2`.
- **Hit targets:** every ?, pencil, chip, chevron and switch sits in a **44dp** target
  (change #9). Nothing below that.
- **Async labels:** buttons swap to a progress label and back —
  Test connection → "Testing…" → "Reached the model ✓" / "Test again" (~700ms);
  Check now → "Checking…" (~900ms); Rebuild index → "Working…" (~900ms);
  Check for updates → "Checking…" → "Up to date" (~800ms). Real implementations should
  reflect real request state, keeping the same label vocabulary.
- **Disclosures:** `?` toggles an inline help paragraph in place; state is per-key.
- **Sheets:** Add time offers +15/+30/+45/+60 and a custom stepper (5–180 min, 5-min
  steps); confirm reads "Add N minutes". Add source sheet as listed under Channels.
- **Destructive confirms:** dialog with a title question and a body that states the
  consequence plainly (e.g. unpairing deletes that device's downloads).
- **Undo:** bulk channel delete and review decisions both offer undo.
- **Progress bar** width animates `width .25s`.
- **Responsive:** designed at 344dp; layout is a single column and should stretch. The
  2-col tile grid may go 1-col below ~320dp.

## State Management
Prototype state, and the real source each should bind to:
- `stack`, `entering`, `leaving` — navigation only.
- Switches: `pauseAll, pauseAmelia, schoolNights, showDate, screenAi, discoverAi,
  skipSponsors, keepPlaying, autoplay, moreLike` → `SettingsStore` / `ConfigStore`.
- Segmented values: `bonus, batch, layout, rowOrder, provider, tvQuality, phoneQuality,
  dlQuality`.
- `rules` — index into the fixed option lists above → per-profile limits.
- Kid identity: `age, colour (0–7), avatar (0–21)`, `watching, usedMin, bonusMin` →
  `Profiles` / `UsageStore`.
- Content: `blocked` times, `removed`/`selected`/`selectMode` for bulk edit,
  `extra`/`added` for newly added sources, `chQuery/chFilter/chSort`,
  `ytQuery/ytFilter`.
- Screening: `houseRules` (multi-line text, default = the four house rules),
  `rulesDirty`, `reviewFilter`, `decided`, `reviewUndo`, `screeningPending`,
  `trusted`, `connFail`, `unblocked`, `blockedFilter` → `AiScreener`, `Screening`.
- Devices: `updated, updating, updatingAll, devOpen, connOpen, connected, hubName,
  conflict, statPeriod` → `Pairing`, `HubEnrolment`, `ConfigSync`.
- Transient: `sheet, sheetPick, customMin, confirm, undo`, and the four async button
  labels.
- `help` — one boolean per ? disclosure.

## Design Tokens
Colours (the app's own dark Material identity — **do not apply an outside design system**):

| role | value |
|---|---|
| page / screen background | `#101014` |
| canvas behind the phone | `#0B0B0F` |
| card surface | `#17161C` |
| elevated card (kid card) | `#1E1D24` |
| pressed row | `#1E1D24` / `#25242C` |
| divider | `#252430` |
| border | `#2E2B36` |
| strong border | `#3A3744` |
| primary text | `#EDEBF0` |
| secondary text | `#A5A1AD` |
| tertiary / labels | `#8B8794` |
| placeholder | `#6D6979` |
| accent (teal) | `#8FCFBE` |
| accent pressed | `#7EC8B2` |
| on-accent | `#0F2A24` |
| accent tint fill | `rgba(143,207,190,0.16)` |
| destructive | `#E38C7E` |
| warning | `#E0B77E` |
| warning surface / border / on | `#221D14` / `#5A4A30` / `#231B10` |
| success | `#7FC8A9` |
| status bar | `#000` |

**Accent discipline (change #7):** teal is *only* interactive text and the primary button.
Nav icons are neutral `#8B8794`. Destructive is red, warning amber, success green — so
"2 of 3 in sync" is amber, not green.

Kid colour swatches: `#E14B3A #EE7A1E #F0C23C #3FA84E #149A88 #2B7FE0 #8E3FC4 #E01B62`.

Typography — **Roboto** 400/500/700 throughout:
| use | style |
|---|---|
| app-bar title | 500 17/1.25 |
| row title | 400 14/1.35 |
| card name | 500 15.5/1.3 |
| value / state | 500 13.5/1.35 |
| secondary line | 400 12.5/1.4 |
| section label | 500 12.5/1 |
| meta / help | 400 12/1.4 |
| status bar | 500 10.5/1 |
| button | 500 13/1 |

Spacing: 2 · 4 · 7 · 8 · 10 · 11 · 12 · 14 · 20dp. Page padding 14dp horizontal;
section gap 20dp; rows 60dp min height, app bar 52dp min.
Radii (change #8, pills pulled back): 6 chips/badges · 8 buttons and icon targets ·
10 cards and rows · 24 the device frame only. Progress bar 3.
Shadows: pushed layer `-12px 0 30px rgba(0,0,0,.45)`; device frame
`0 26px 64px rgba(0,0,0,.62)` (frame only, not app UI).

## Assets
No new assets. Icons come from the existing `PickwickIcons` object
(`ui/Icons.kt`) — the prototype's inline SVGs stand in for People, Channels, Shield,
Play, List, Devices and Disk. Avatars are the existing 22 `res/drawable-nodpi/avatar_*.png`
(the prototype substitutes emoji). Delete any drawable or string used only by the removed
Support Pickwick card.

## Screen map — design screen → repo files
| design screen | implement in |
|---|---|
| root | `ui/Settings.kt` (`SettingsFlow`, root column, `HubPage`) |
| kids, kid | `ui/KidsSettings.kt`, `ui/KidPage.kt`, `ui/SettingsScreenTime.kt` (`RulesSection`, `BlockedTimesSection`, `GrantTimeSection`) |
| channels, ytsearch | `ui/SettingsChannels.kt`, `ui/SettingsDiscovery.kt` |
| listing | new section, moved out of `ui/SettingsChannels.kt` |
| screening, review, blocked | `ui/SettingsAi.kt` (`AiConnectionSection`, `AiScreeningSection`, `AiReviewSection`) |
| devices, phone | `ui/SettingsDevices.kt`, `ui/SettingsHub.kt`, `ui/SettingsDownloads.kt`, `Settings.kt` `SearchIndexSection` |
| playback | `ui/Settings.kt` `HubPage.Playback` branch |
| backup | `ui/SettingsImportExport.kt`, `ui/SettingsDevices.kt` `UpdateSection` |
| digest | `ui/DigestScreen.kt` |
| tokens | `ui/Theme.kt`, `res/values/colors.xml` |
| shared row/card primitives | `ui/Components.kt`, `Settings.kt` (`SettingsCard`, `SettingsDivider`, `SubPage`, `HubRow`) |

Note the page set changes: `HubPage` gains **Listing** and **Phone**, `Devices` is
retitled *Devices & sync*, and `Backup` becomes *App, hub & backup*.

## What changed and why
1. **Two sections, plainly named.** Seven rows in two sections instead of four thin ones.
   No settings-wide search; the channel list keeps its own, where volume justifies it.
2. **Listing settings left Channels.** The four kid-shelf defaults are about how videos
   appear, not which sources you allow.
3. **The kid errand escaped the kid page.** Grant and Pause on the root card.
4. **Screening reordered.** Connection before the switches that depend on it, with an
   amber notice when unset — the switches were previously dead with no explanation.
5. **Hub collapsed.** One expandable row instead of the heaviest group on Devices.
6. **Warnings reach the root.** Offline TV and "never backed up" were buried two levels
   deep.
7. **Accent discipline.** See tokens.
8. **Squared off.** Radii 7–10dp, sentence-case section labels, compact device action
   buttons instead of full-width slabs.
9. **Targets and states.** 44dp targets, pressed states everywhere, a real back stack.

A per-control coverage audit — every control from the eight original screenshots and
whether it was kept, moved, merged or removed — is rendered in the prototype itself
(option **2b**, right of the phone). Nothing was cut except Support Pickwick.

## Files in this bundle
- `screens/contact-sheet.png` — all 14 screens side by side, labelled, full length (start here)
- `screens/full-NN-*.png` — the same screens individually at 2x, each expanded to its full
  scroll height so nothing is cut at the fold
- `Pickwick Settings.dc.html` — the prototype (open in a browser)
- `support.js` — runtime the prototype needs; keep beside it
- `README.md` — this document
