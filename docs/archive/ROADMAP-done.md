# Roadmap sections that are finished

Moved out of `docs/ROADMAP.md` on 2026-09-17. Each of these described work that
has shipped, and each was still sitting in the middle of the live plan, where
the roadmap's own rule says a finished item should not be: *confirm the work is
done, then delete the item.* They are kept here rather than deleted because the
reasoning in them - why the index had no dates, why the web player was built in
four steps, which parity round decided what - is worth having when somebody
asks why a thing is shaped the way it is.

What shipped is recorded in `docs/FORK-NOTES.md`. What is next is in
`docs/ROADMAP.md`, which is the only forward-looking document.

### ~~2M. The search index throws the upload date away~~ — done in 1.9.0

**Done 2026-09-15, 1.9.0**, exactly as written below and one step further:
the index keeps the **view count** as well as the date (`IndexedVideo.viewCount`
and `publishedAt`, keys `v` and `p`, absent on an old row), a known row
*learns* both from the next crawl of its page rather than waiting for a
re-crawl (`ChannelIndex.addVideos`; `ChannelIndexDateTest` is the gate), and
the browser now honours **Show when a video came out** (the hub composes the
phone's own `metaLine`), **Channel page layout** ("Popular first"), and
**Latest video** on the Channels grid. "Newest" is a search chip on the
phone (`SearchOrder.RECENT`); undated rows sort last and are never hidden,
as recommended below. What is still off the table is a search *ranked* by
views — a popularity contest, on purpose.

Asked for directly (2026-09-07) after "most recent" turned out to be
unofferable on the search screen. It is a smaller job than it sounds, because
**nothing needs fetching — the date is already in hand and is being dropped.**

`Video.publishedAt` (epoch ms) exists and `YouTubeRepository` already converts
the extractor's `uploadDate` into it. `IndexCrawler.toIndexed` then builds a
`ChannelIndex.IndexedVideo` without it, and `ChannelIndex.saveSource`
persists exactly five keys — `id`, `t`, `c`, `th`, `d`. So the crawl computes
a date every time and discards it on the way to disk.

**What it takes**

- `IndexedVideo` gains `publishedAt: Long?`; `toIndexed` carries it; `saveSource`
  writes a sixth key and `parseSource` reads it back as null when absent.
- `toVideo()` populates `Video.publishedAt`, which is what makes a date
  reachable from a search result at all.
- Old index files stay valid and simply have no date — the same "null keeps
  the feed's own newest-first order" rule `Video.publishedAt` already
  documents. **No index version bump, no forced re-crawl**; a source picks up
  dates the next time it is crawled. Cover that with a parse test on a
  five-key file.

**What it unlocks, in order of value**

1. **"Most recent" on search.** Today `SearchRank` cannot offer it and the
   chips deliberately do not fake it with a proxy.
2. **A real recency term in the ranking** — a genuinely new video from a
   channel she loves should beat a five-year-old one, which today it cannot,
   because relevance has no notion of when.
3. **"Newly added" as a video ordering** rather than only a channel one, which
   is half of §2L's discovery complaint.
4. The meta line on search results stops being channel-only for families who
   have `showVideoAge` on.

**Not free, and worth saying:** dates only appear for sources crawled after
this ships, so the first week looks partial — every source has *some* dated
videos and some undated ones. Decide whether an undated video sorts last under
"most recent" (recommended: it is honest) or is hidden from that ordering
(it is not — hiding a video because we lack a date about it is worse than
showing it late).


## 3. Known-wrong docs — cleared 2026-09-06

All five went out with 1.0.7 and are recorded here rather than deleted,
because "documentation drifts and nobody is watching" is the standing risk and
the list is the evidence for it. `HUB.md` had told parents their TVs only sync
while a kid is watching; `ARCHITECTURE.md` still showed a one-module app;
`LAN-API.md` was missing routes, and the hub's whole half of it was covered by
no guard at all until guard 30; the `stats` entry in `SettingsSurface` blamed
"the hub never initiates"; and `HubWeb.pages` claimed to be derived from
`SettingsSurface` while being a literal list.

The last one is only half fixed: the KDoc now says what the code does and
names guard 3 as what holds it equal. **Deriving the list would be strictly
better** — a list that cannot drift beats a list a guard watches — but guard 3
finds its page ids by grepping the `HubPage("…")` literals, so the two have to
move together, in both scripts, with the negative test. *Small, and worth
doing the next time anything else in that file is open.*

The general lesson, which is why a doc list keeps reappearing here: every one
of these was a sentence explaining why something could not be done, written
when it was true and left standing after the constraint moved. A stale reason
is worse than none, because it is what the next session reads before deciding
not to build something.

---


## 7. Web/app parity — the six rounds (R1-R5 done, R6 partly; 1.7.0)

The browser is a third face and must reach feature and look parity with the
app: *"it can essentially function as the app for ios so should feel like it
and look like it. I want to avoid the looks diverging."* Fifteen agents specced
every Android kid surface against the code; the rounds below are the result.

**R1 — the mechanism. DONE.** `KidSurface` (the surface manifest, sibling of
`SettingsSurface`) and `KidGeometry` (shape tokens), guards 62 and 63. The gate
now prints `kid surfaces still to reach the browser: …` on every run. Also
collapsed `FINISHED_FRACTION` from eight spellings to one, and fixed guard 57's
route regex, which had no digits and was failing **open**.

**R2 — the You tab and the shelf chrome. DONE.** Favorites, Watch later, Up
next and History, in `:core`'s order with `:core`'s words. History live; the
other three declared and empty, saying what would fill them. The rule, the bold
title, the mono count and See-all-in-place are the furniture R3 and R5 land
into.

**R3 — writes: the hold menu and the saved lists. DONE.** The extraction that
matters: `SavedListStore`'s pure half and `QueueStore` into `:crawl`, **with the
prune-then-merge convergence test they have never had** — prove it fails against
the unfixed code first (`.claude/skills/yosemite-kids-sync`). Then
`HubSavedLists` keyed by the credential, `POST /list`, and the long-press
dialog. Flips favorites / watch-later / up-next to `webReady`.

**R4 — it becomes an app on the iPad. DONE.** A kid manifest generated from `:core`
beside `kid-tokens.css`, four icons from one generator, the
`apple-mobile-web-app` meta tags, and `theme-color` refilled from the live
tokens when the look changes. **Deliberately no content-caching service
worker**: Cache Storage outlives the claim cookie, so a cached page keeps a
revoked or blocked child looking at a working app — the exact failure
`/media`'s per-chunk gate exists to prevent.

**R5 — the ordering surfaces. DONE (channels, sorts, seeded Surprise).** `orderChannels`, `filterVideos`,
`orderByPopularity`, `orderByWatched`, `defaultFilterFor` and `VideoItem` to
`:crawl` (they are typed on `Source` and `Video`, which live there). A seeded
mix beside `SearchOrder.order` — `:app` calls bare `shuffled()` today, which by
definition cannot be reproduced on another face. Query parameters, never new
routes. Channels grid, Surprise, playlists, watched-videos.

**R6 — the player. PARTLY DONE** — Back, kid-facing refusals, Up next hand-off and one countdown vocabulary all shipped. The custom scrubber, the double-tap seek and the second-precision countdown did NOT: the first two need a real finger on a real iPad, and the third needs HubWatchMeter to interpolate because UsageLedger counts whole minutes. Original note follows. Own controls over `v.controls = false`; `PlayerDismiss`
and the countdown's pure half to `:core`. `HubPolicy.clock` stops collapsing to
whole minutes so the amber ring stops being a lie in the last sixty seconds.

**Out of scope in every round, with reasons:** downloads and offline in any
form (a cached video keeps playing after a parent blocks it); HD and any player
transport work; sponsor segments (`sponsor.ajay.app` is not in `Http.HUB_HOSTS`
and adding a host is its own decision); and the three dead sort chips, which are
blocked on §2M's upload date rather than on the browser.

---

