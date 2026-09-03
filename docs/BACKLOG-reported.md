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

### 2. Release dates still are not on the cards

`showVideoAge` exists, the config carries it, and the filters can order by it —
but the date does not appear on a media card or in a video's information. Either
the setting is not reaching the tiles or the tile is not rendering it. Suspect
`LocalShowVideoAge` is not provided on every path that draws a card (the player's
"More from" list and the shelf rows are likely candidates), or `publishedAt` is
null for the cached rows the kid is actually looking at.

Verify on a device before changing anything: check that a video with a known
date renders it with the setting on, and follow the CompositionLocal through
every card-drawing path rather than only the home grid.

### 3. AI search and AI content screening are wrongly coupled

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
