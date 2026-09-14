# Kids Video Player — build handoff

A kid-facing video player for an early reader (5–8) that plays only from channels a parent has
approved. Two form factors ship: **phone** and **TV / laptop**. No parent controls live inside the
kid app; everything configurable is set in a separate parent app and read here.

## Scope

**Ship:** six screens on phone, six screens plus two states on TV.

| Screen | Phone | TV / laptop |
|---|---|---|
| Home | `phone-1-home.png` | `tv-1-home.png` |
| Channels | `phone-2-channels.png` | `tv-2-channels.png` |
| Channel page | `phone-3-channel-page.png` | `tv-4-channel-page.png` |
| You | `phone-4-you.png` | `tv-5-you.png` |
| Search | `phone-5-search.png` | `tv-6-search.png` |
| Player | `phone-6-player.png` | `tv-3-player.png` |
| Player, end of video | — | `tv-3b-up-next.png` |
| Nav rail, collapsed | — | `tv-7-rail-collapsed.png` |

**Explicitly out of scope for this build:** the "simple mode" density (a lower-reading-age variant of
every screen) exists in the design file but is deferred — do not build it, and do not add a density
setting. Also not designed yet: the parent app, including its home-screen builder.

## About the design files

`Kids Player - All Screens.dc.html` is a **design reference written in HTML** — a prototype showing
intended look, structure and behaviour. It is not production code to copy. Recreate these screens in
the target codebase using its existing environment and patterns (React Native, SwiftUI, Compose,
web — whatever the app already is). If no environment exists yet, choose one appropriate for a
phone + TV app and implement the designs there.

The file is a review board. Ignore the board chrome — section headings, `dv-*` classes, id badges
like `b1` / `t1a`. Each phone screen is a 380×812 frame; each TV screen is drawn at 1280×720 for
review and **builds at 1920×1080 by multiplying every value by 1.5**.

Screens whose ids end in a later letter (`b2c`, `b3d`, `b4d`, `b5b`, `b6b`, and the third prototype
frame) are the deferred simple mode. Skip them.

The board's top section holds three **interactive prototypes** — click through the phone and TV ones
to see navigation, toggles and state transitions in motion. They are the behavioural spec; the
static frames are the visual spec.

## Fidelity

**High-fidelity.** Colours, type sizes, weights, radii, spacing and copy are final and should be
matched. Thumbnails are the one exception: they are striped placeholders standing in for real
artwork.

---

## Design tokens (Wave Talk)

| Token | Hex | Use |
|---|---|---|
| pine | `#0b0b0c` | app background |
| granite | `#111113` | cards, fields, inactive chips |
| granite-hi | `#1a1a1d` | icon tiles, active pills, secondary buttons |
| alpenglow | `#f2f0ec` | primary text |
| sage | `#9a9aa2` | secondary text |
| sage-dim | `#6f6f78` | tertiary text, counts, meta |
| coral | `#e0533d` | the single action colour |
| amber | `#d8a13a` | time limits, blocked windows, daily countdown |
| hairline | `#26262a` | section dividers, tab-bar border |
| edge | `#2b2b31` | card and chip borders |
| ok | `#47b877` | watched state, downloads |
| cool | `#4a8b8d` | Watch later, offline |

Placeholder artwork tints (per channel/video, until real images exist): `#7b4fa8` SciShow Kids,
`#2f7a56` Maddie Moate, `#1f3a6b` BBC Earth Science, `#6b5a3a` Coyote Peterson, `#b8452f` The Action
Lab, `#3f7a8c` Math with Mr. J / StoryBots, `#4a8b8d` Crash Course Kids, `#4f7a2f` nature,
`#c2531f` Deep Look, `#8c6a2f` / `#5a3f6b` / `#2f6f72` misc.

Type — system UI sans throughout, system mono for counts and micro-labels:
- `ui-sans-serif, system-ui, -apple-system, "Segoe UI", Roboto, sans-serif`
- `ui-monospace, SFMono-Regular, Menlo, Consolas, monospace`

| Role | Phone | TV (×1.5 at 1080p) |
|---|---|---|
| Page title | 19px / 700 | 30px / 700 |
| Section header | 17px / 700 | 22px / 700 |
| Section link ("See all") | 14.5px / 700 coral | 16px / 700 coral |
| Section count | mono 13px / 700 sage-dim | mono 13px / 700 sage-dim |
| Feed card title | 15.5px / 600 | 20px / 700 |
| Rail card title | 13.5px / 600 | 17px / 600 |
| Row title | 15.5px / 700 | 18px / 700 |
| Meta line | 12.5px sage | 14.5px sage |
| Micro-label (uppercase) | mono 9–9.5px / 700, ls .12–.16em | mono 11.5–13px / 700 |
| Duration badge | mono 10–11.5px / 700 | mono 13–15px / 700 |
| Chip / filter | 12.5–13px / 600 (700 active) | 15–16px / 600 (700 active) |
| Nav label | 10.5px / 600 (tab bar) | 16px / 600 (rail) |

Radii: 26px phone frame · 22px round buttons and pills · 18–22px large tiles · 14–16px cards and
fields · 11–12px chips and icon tiles · 4–6px badges. TV adds 30px channel logos, 22–26px tiles.

Spacing: phone 16px page gutter; **TV 44px gutter at 720p, 64px at 1080p (5% safe area — do not
reduce)**. 8–10px between chips · 12–14px grid gaps · 13px (phone) / 20px (TV) between rail cards ·
14px around dividers · 18–22px between feed items.

Minimum tap target 44px on phone; minimum focusable size 60px on TV. `text-wrap: pretty` on all
multi-line titles.

---

## Shared chrome

### Phone
**Top bar** (12px 16px 14px, 12px gap): 34px coral logo tile with a play triangle → page name
19px/700, `flex: 1` → **time-left pill** (28px tall, radius 9, granite-hi, 7px amber dot + mono 11px
"20m") → 44px search button (stroke magnifier: 15px circle + 2px handle at 45°) → 40px avatar with a
coral inner disc. The avatar is the only way into profiles and the parent area.

**Tab bar** (Home · Channels · You): hairline top border, granite, 9px 8px 14px. Each tab is a flex
column, 19px icon + label; the active tab gets a granite-hi pill (radius 12) and coral icon + label.
The player has no tab bar and no top bar — video starts under the status bar.

### TV / laptop
**Nav rail**, left edge, two states:
- **Expanded 196px** (default): 40px coral logo tile + kid name 16px/700 → four items (Home,
  Channels, You, Search), each 48px tall with a 20px icon and 16px label → spacer → time-left card
  (granite-hi, radius 14, 10px amber dot + mono 14px "20m left") pinned at the bottom.
- **Collapsed 88px**: icons only, 60px round hit areas, no labels; the timer becomes a 56px square
  showing the amber dot over mono "20m". The rail starts expanded and collapses the moment focus
  moves right into content; moving focus back to the left edge expands it again, sliding content
  right.

There is no tab bar and no mobile status bar on TV. The rail carries the kid name, the timer, and
search — the content area's top row is just a page heading, so none of those appear twice.

**Focus** is always a 3px coral border plus a 6px `rgba(224,83,61,.18)` glow — never colour alone,
never a hover state. Every screen is reachable by d-pad in straight lines.

### Both
**Scroll model:** only the rail (TV) or top bar and tab bar (phone) are fixed; everything else is
one scrolling column. On Channels the filter row is `position: sticky; top: 0` inside that column
with an opaque pine background. Horizontal rails hide their scrollbar (`scrollbar-width: none`,
`::-webkit-scrollbar { width: 0; height: 0 }`) but still scroll.

**Watched state:** a finished video renders at 48% opacity with a mono uppercase "watched" tag in
`ok` green beside its meta line. Applies in feeds and rails, not in the hero.

---

## Screens

### Home
Sections in order, separated by 1px hairlines inset to the gutter:

1. **Pinned hero** — parent-chosen channels or playlists, 2–3 items. Phone: 348×176 cards,
   `scroll-snap-type: x mandatory`, dots below (7px, coral active). TV: three equal 344×194 cards in
   a row, the focused one ringed. Each card carries a "Pinned" badge (mono uppercase on
   `rgba(11,11,12,.66)`), a bottom gradient with the name and mono meta ("3 new videos",
   "4 videos · 21 min"), and a coral play circle (52px).
2. **Channels** + See all — round-square logos with names beneath. Phone 68px (74px column),
   TV 124px (130px column). A 14–20px coral dot marks channels with new videos.
3. **Keep watching** + See all — cards with a 5–6px progress bar along the bottom of the thumbnail
   (coral on `rgba(0,0,0,.5)`) and a "2:41 left" badge. Phone 176×99, TV 250×141.
4. **More like what you watch** + See all — same card, no progress bar. This is the only
   suggestion surface in the app.
5. **Videos** — the feed. One merged control row: coral-outlined **🎁 Surprise me** pill, a 1×24px
   divider, then filter chips New / Favorites / Most watched. Default sort is New and is set by the
   parent. Below: phone full-width items (196px 16:9 thumbnail, 40px channel avatar, two-line title,
   "Channel · today", ⋮ overflow); TV a three-across grid of 190px-tall cards.

### Channels
Phone: **Surprise me** card (granite, 52px coral 🎁 tile, "play something from any channel") →
**Pinned** rail (108×72 tiles with CHANNEL / PLAYLIST badges) → sticky filter pills (A–Z active,
New, Favorites, Most watched) → channel rows. Each row is 12px padding with a hairline bottom and
two tap targets 20px apart: the left region (68px logo + name + mono coral "3 NEW" + newest-video
title + release time) opens the channel; a 44px round button using the newest video's artwork
(`rgba(11,11,12,.42)` scrim + white triangle) plays it.

TV: Pinned row (214px cards) → divider → four-across grid of 158px picture tiles, name only, coral
dot for new, with **Surprise me** as the first cell so the d-pad never leaves the row. Sort chips sit
above the grid.

### Channel page
Channel block (76px logo on phone / 108px on TV, name 21–30px/700, mono "30+ VIDEOS · 29 PLAYLISTS")
→ **three square action cards**, equal width, 88px tall (104px TV): **Newest** (coral play triangle),
**Favorite** (coral heart), **Surprise** (🎁) → **New videos** rail → **Playlists** rail (tiles with a
22–36px dark strip and ☰ glyph on the right edge, name + mono count; three visible, scrolls for more)
→ divider → **Videos** header with a mono count and a **Watched 2** button (granite-hi pill) that
swaps the list to that channel's history → filter chips **Newest** · A–Z · Unwatched · Random →
full-width video feed.

### You
1. **Blocked windows** card — mono label "VIDEOS ARE OFF · 40 OF 60 MINUTES USED" and a scrollable
   row of pills (radius 17, granite-hi): colour dot + label + mono range. Any number of windows —
   Bedtime 8:00pm (amber), School 8:30–3:30 (cool), Dinner 5:30–6:15 (sage).
2. **Offline banner** — `rgba(74,139,141,.12)` on a cool border: "You're offline. 3 downloads still
   play."
3. **Request status** — granite on edge border, amber dot: "Your ask for Bluey is waiting".
4. Four rails, each with a header, mono count and See all: **Favorites** · **Watch later** ·
   **History** (70% opacity, meta "channel · Today") · **Downloads** — whose link reads **Manage**,
   not See all, because offline this row *is* the library. Download cards carry a green ↓ SAVED badge
   and "channel · 142 MB"; an in-progress download shows a centred percentage over a scrim with a
   green progress bar and a mono "DOWNLOADING" label.
5. **Make a request** — dashed-border tile, coral +, "a grown-up will see your ask".

### Search
Phone: 56px field (radius 16, granite) — stroke magnifier, query 15.5px/600, a clear **×** in a 44px
touch box, and a 44px coral mic button. Scope line beneath: "Only your channels — nothing else can
turn up." Then chips **All 14** (light fill, pine text — active) · Newest · Unwatched · Most watched,
the full-width results feed, and **You looked for** with a coral **Clear all** and one removable
44px chip per term.

TV: full-width focused field with the clear ×, a "Say it out loud / hold the mic on the remote" card
beside it, one recents row with removable chips and Clear all, a divider, a
"14 videos · 3 channels · only your channels" header, then results four across. **No on-screen
keyboard** — the TV OS raises its own when the field is focused.

### Player
Phone: 214px video with an absolutely positioned overlay — 42px back button; **Autoplay ON**
(coral-tinted border, label + mono "ON") and **CC** as 32px pills; a fullscreen button; centre
52px skip-back / 78px white pause / 52px skip-forward; bottom mono "0:42 / 5:34" and an 8px scrubber
(coral fill, 20px coral handle with a pine ring). Content column: **daily countdown chip** (amber
ring via `conic-gradient(#d8a13a 0 76%, rgba(255,255,255,.14) 76% 100%)` around a pine disc reading
"5m", plus "5 minutes left today" / "This video ends in 4:52") → title 19px/700 → channel card
(44px logo, name, mono meta, chevron) → three 46px action tiles **♥ Favorite** · **▫ Watch later** ·
**☰ Queue** → two underlined tabs, **More from channel** (active) and **Similar**, over a list of
thumbnail + title + release time.

TV playing state (`tv-3-player.png`): video fills the frame. Title, channel and release time sit in
a top scrim; the daily countdown ring sits top-right. A bottom scrim carries 70px skip-back / 96px
white pause / 70px skip-forward, then Autoplay ON, CC, Favorite, Watch later and Queue as 44px
controls, and a 10px scrubber with a 24px handle. The scrim appears on any remote press and fades
after a few seconds; left/right on the d-pad scrubs, OK plays and pauses.

TV end-of-video state (`tv-3b-up-next.png`): the video dims to `rgba(11,11,12,.72)` and **Up next**
takes the centre — a mono uppercase label, a 420×236 thumbnail with a coral focus ring and a 96px
coral play button in its middle, the title 26px/700 and channel below, then a countdown chip
("Playing in 5 seconds", coral ring — coral, not amber, so it is never confused with the daily
limit). OK plays it, Back stops. There are no Play now / Stop buttons.

### Profiles and the parent door
The avatar (phone top bar) / the rail's profile area (TV) opens a profile surface: **"Who's
watching?"** over round profile faces — 88px on phone, 168px on TV — the current one ringed in white
with a coral glow. Beneath a hairline sits a **Grown-ups** card (amber flag icon, "settings, channels
and time limits") which requires a grown-up code. **Creating or removing a profile is not possible
here** — the TV has no Add tile; profiles are managed in the parent app.

---

## Interactions & behaviour

- **Navigation:** phone tab bar switches Home / Channels / You; TV uses the rail. A channel row or
  tile opens the channel page; a thumbnail or play control opens the player; back returns.
- **Two targets per channel row:** left region opens the channel, the round button plays its newest
  video.
- **Sorting and filtering:** one active chip per row; the sort applies only to the list below it.
  Defaults — Home Videos: New (parent-set); Channels: A–Z; Channel page: Newest; Search: All.
- **Surprise me / Surprise:** plays one random video — app-wide on Channels and Home, scoped to the
  channel on the channel page.
- **Watched button (channel page):** toggles the Videos list between everything and that channel's
  history.
- **Autoplay:** on by default; off means playback stops at the end of the current video. It is the
  only control over what plays next.
- **Daily countdown chip:** appears when the remaining allowance drops under ~5 minutes; the ring
  depletes as time runs out. The rail/top-bar pill always shows the remaining minutes.
- **Search:** typing filters within followed channels only; × clears the field; per-chip × removes
  one recent term; Clear all empties the history. Voice is the primary path on TV.
- **Requests:** the kid sends a request, the parent app receives it, and the status line on You
  reports "waiting", then the added channel.
- **Kids cannot delete their own content.** No long-press to unfavourite, no removing downloads, no
  adding or editing profiles. Favorites, Watch later, Queue and Downloads are added by the kid but
  cleared in the parent app. The only kid-side deletion is search history.
- **Empty and error states derive from these components:** an empty rail collapses to its dashed
  instruction tile; "no results" is the search results area with the chip row and scope line only.
  No separate designs.
- **Offline:** the banner appears on You; Downloads keeps playing; other rows are unavailable.

## State

Per kid: `kidName`, followed channels, pinned hero list (2–3 items, ordered), home section order and
enabled flags, default Videos sort, favorites, watch-later, queue, downloads (with progress), watch
history, search history, daily allowance used/total, blocked windows, pending requests.

Parent-controlled values — pinned list, section order, default sort, allowance and blocked windows —
are outputs of the parent app and are read-only here.

## Assets

None shipped. Every thumbnail and channel logo is a striped placeholder (a flat tint plus a
`repeating-linear-gradient` at 135°) sized to the real slot. Replace with channel artwork and video
thumbnails. All icons are drawn with CSS boxes and borders or use text glyphs — no icon font — with
one sanctioned emoji: 🎁 for Surprise.

## Files

- `screens/` — 14 PNGs, one per screen and state, at review scale (phone 382×814, TV 1282×722)
- `Kids Player - All Screens.dc.html` — the full board: interactive prototypes on top, then phone
  screens, then TV screens
- `Kids Player Prototype.dc.html` — the component the prototypes are built from; the board's top
  section is empty without it

## Not designed yet

- **The parent app**, including its home-screen builder: choosing and ordering pinned hero items,
  rearranging and disabling home sections, setting the default Videos sort, and managing profiles,
  allowances and blocked windows.
- **Simple mode** — deferred by decision, not by omission. Revisit after this build ships.
