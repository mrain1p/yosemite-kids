# Reported from real use

Things the family hit while actually using the app, newest batch first. These
are ahead of anything in `docs/PLAN-sync.md` past the milestone in flight —
they are all real-use bugs or gaps, not speculative work.

## 2026-09-03

### 1. "Recently added" is missing, and it is not the same as "new"

A kid asked for some channels and playlists to be approved. The parent
approved them, and they were then **lost in the interface** — there is nowhere
that says "here is what just got added for you".

The distinction the app currently cannot express:

- **New** — the *channel* published something recently. Already modelled
  (`UiState.newBadges`, `computeNewBadges`, the `VIDEO_FILTER_NEW` chip).
- **Recently added** — the *parent* just approved this source. Nothing tracks
  it, so a freshly approved channel lands wherever the channel sort happens to
  put it, often far down the list.

Wanted: a way to see what was recently added or approved, distinct from what is
newly uploaded. Options to weigh — a kid-facing "Just added" row or chip, a
`CHANNEL_ORDER_ADDED` sort, and a badge on the channel tile that reads
differently from the NEW pill. Needs an `addedAt` on `WhitelistEntry` (absent
today), which is a config-format change and so should ride the sync work rather
than land beside it.

### 2. Release dates still are not on the cards — FIXED 2026-09-03

`showVideoAge` exists, the config carries it, and the filters can order by it —
but the date does not appear on a media card or in a video's information. Either
the setting is not reaching the tiles or the tile is not rendering it. Suspect
`LocalShowVideoAge` is not provided on every path that draws a card (the player's
"More from" list and the shelf rows are likely candidates), or `publishedAt` is
null for the cached rows the kid is actually looking at.

Verify on a device before changing anything: check that a video with a known
date renders it with the setting on, and follow the CompositionLocal through
every card-drawing path rather than only the home grid.

### 3. AI search and AI content screening are wrongly coupled — FIXED 2026-09-03

To use AI channel discovery, a parent has to turn on AI content screening.
These are independent features and should be independently usable, and
configuring or testing the AI connection should be independent of both.

Today `AiDiscoverySection` gates on `ai.model.isNotBlank() && (apiKey…)`, which
is nearly right, but the surrounding settings flow ties the credentials to the
screening switch. Wanted: one "AI connection" section (endpoint, model, key,
Test), then screening and discovery as two independent switches that each just
require a working connection.

### 4. "More like this video" on the player page

Under a playing video there is "More from [channel]". A second tab or row —
**more like *this* video**, by keywords rather than by channel — would reuse the
mechanism `suggestionsFor` already implements: instead of scoring candidates
against the kid's watch history, score them against the one video's title.

Cheap to build because the scoring is already pure and already tested. Should
be an optional setting, alongside the existing suggestions switch.

### Sequencing

(2) and (3) are bugs and small; do them first and independently — neither
touches the config format. (1) needs `addedAt` in the config, so it belongs
with the sync work. (4) is additive and can land any time.

## What the fixes turned out to be

### (2) Release dates — two causes, both now fixed

Verified on the emulator with the setting on, rather than reasoned about. The
mechanism was never broken: the For-you grid rendered "StonerBang · 3 years
ago" correctly, and the player's "More from" rows already showed
"1 year ago · 8:22". Two separate things made it invisible:

- **The setting was on the wrong page.** It sat on **Playback**, in a section
  called "Kid's shelves", together with page size and channel layout — none of
  which are about playback. A parent looking for "show release dates" goes to
  Channels & playlists. All three moved there, and the hub summaries now name
  what each page actually holds ("how videos are listed", and Playback's line
  no longer omits autoplay, quality and suggestions).
- **The shelf rows never rendered the meta line at all.** Keep watching and
  More like what you watch drew the title alone — no channel, no date — while
  the grid tiles used `videoMeta`. So the top of the home screen, which is
  where a parent looks first, showed no dates even with the setting on and
  dates present. `KeepWatchingRow` now carries the same line, verified:
  "SciShow Kids · 1 year ago".

Worth keeping in mind: rows cached by a build before `publishedAt` existed read
back with no date (the cache row is append-only, seventh cell) and only gain
one when that channel is next crawled. So a family upgrading sees dates appear
gradually rather than all at once. Nothing to fix — but it explains a partial
result.

### (3) AI connection is now its own section

The coupling was one line: `if (!ai.enabled) return`, halfway through
`AiScreeningSection`, with the provider presets, endpoint, key, model picker
and Test button all below it. The model-list fetch was gated on `ai.enabled`
too. So the only way to reach the key field was to switch content screening on.

Split into `AiConnectionSection` (endpoint, key, model, Test — gated on
nothing) and `AiScreeningSection` (the switch, rules and per-kid ages). The
screening switch is now disabled until a model is set, with a line saying why,
because a switch that turns on and silently does nothing reads as a broken
feature rather than a missing step. Discovery's own not-ready message points at
"AI connection" instead of at the screening section.

### 5. Review the settings and connections pages as a whole — DONE 2026-09-03

Raised 2026-09-03, after the shelf settings turned out to be filed under
Playback where nobody would look for them. That was one symptom; the ask is to
go through the settings hub and the pairing/connection screen properly and
check that each page holds what its name implies, and that related things link
to each other rather than sitting in separate corners.

Specifically worth checking:

- **Every hub row's summary names what is actually on that page.** Two were
  wrong or incomplete before this was raised, so the others deserve a look.
- **Pairing and Devices.** The QR/pairing flow and the Devices page are the
  same subject seen twice; a parent who lands on one and needs the other has
  no link between them.
- **The connection screen's step feedback** — waiting to scan, loading,
  succeeded, failed, and when it is safe to go back — which was asked for
  earlier and has not been visually verified since.
- **AI connection**, newly split out, should be reachable from wherever a
  parent first hits a feature that needs it, rather than only by knowing to
  open Content screening.

Do this after the sync milestones, and do it on a device rather than from the
source — the last two settings problems were both invisible in the code and
obvious in a screenshot.

## The settings and connections review (2026-09-03)

Walked every settings page and the pairing flow on a device. What it found:

- **Two hub summaries were still wrong.** "Playback — Autoplay, quality,
  sponsor skipping, suggestions" truncated to "…suggestio…", so a longer list
  named *fewer* things than a short one; it now reads "Autoplay, quality,
  listening". And "Devices — Kid devices, downloads, search index" never said
  **pairing**, which is the one thing a parent setting up for the first time is
  hunting for and which nothing else on the screen hinted at.
- **The pairing empty state was one sentence describing a three-step procedure
  across two devices.** It is now numbered, and it says what the TV will show
  at each stage, so a parent knows the phone is not the thing to watch.
- **There is deliberately no in-app QR scanner, and that is right.** The QR is a
  `pickwick://pair` deep link, so the phone's ordinary camera opens it and the
  app needs no camera permission at all. The old copy left a parent hunting for
  a scan button that should not exist; the new copy says to use the normal
  camera.

Checked and found already correct, so left alone:

- **The connection screen's step feedback**, asked for earlier and never
  verified since: `PairStatus` covers no-network, waiting for a scan, a phone
  is asking, and paired — each with a line saying what to do, and the paired
  state explicitly says you can go back.
- **AI connection** is reachable without turning screening on, verified on
  screen.

Left as known cosmetics: a standalone `CompactButton`'s label sits 10 dp right
of the body text above it, which reads as button padding rather than
misalignment, and "This phone" defaults to the raw model name
(`sdk_gphone64_x86_64` on an emulator, "Pixel 7 Pro" on a real device) until a
parent renames it.
