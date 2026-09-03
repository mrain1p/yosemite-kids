# Open questions — hub, web app, Apple

Parked by the user on 2026-09-03 to revisit. Each entry records what was
asked, what we found, and what is still undecided. Nothing here is built.

## 1. A Docker "hub" on the home LAN

**Asked:** would an optional container help sync, get content to screens
when no phone is around, and route things to TVs or the parent app?

**Found:** the shape already exists in the code. `masterDeviceToken` in the
config elects one device to run the search-index crawl, and
`IndexCrawlWorker` no-ops on every other device. Kid devices are barred
from claiming it by one line (`isParent`), with the comment that the crawl
is rate-limit expensive.

**Where it would help, in order:**

- Grants and config pushes land immediately instead of waiting for the
  five-minute reconcile when a device slept through them (backlog item 8).
- AI screening runs once for the family instead of once per device.
- One download cache served over the LAN instead of a copy per device.
- Master indexing off a battery device.
- Hosts the web console (see 2), which is the "no phone around" path.

**Hard constraint if we build it:** the hub is a *relay and worker, never
an authority*. The parent's phone stays the editor of `config.json`; the
hub holds the newest config it has seen and re-serves it. Two writers
fighting over a family's curation is the one failure this project cannot
afford.

**Cheaper thing to try first:** let the TV claim master. It is always
powered, already runs the LAN server and already runs the extractor. That
is a policy change plus a gentler crawl schedule, and it tests the premise
for almost nothing.

**Gap:** there is no LAN route for browse caches. The API carries config,
watch state, verdicts and the search index — nothing that says "here is
channel X's newest page". A hub would need one.

**Also unresolved:** the hub needs NewPipeExtractor, which is JVM, so it
is a JVM container and wants a shared Kotlin module pulled out of the
single Android module. That refactor is the bulk of the work.

## 2. Progressive web app

**Asked:** would a PWA work? Later: it was suggested *because* of the
Apple distribution problem.

**Found:** on its own, no — a browser cannot resolve streams (YouTube
sends no CORS headers and there is no client-side extractor), and a page
cannot listen on a socket the way the device's LAN server does.

**But with the hub in play, both blockers dissolve.** The hub resolves
streams and serves the page itself, so every call is same-origin: no CORS,
no mixed content.

**Remaining catch:** a plain-HTTP LAN origin is not a secure context, so
service workers will not register — no offline, no strict installability.
iOS still runs home-screen web apps chrome-less via the meta tag, which
gives the kiosk benefit without HTTPS. A real certificate for a domain
whose A record points at the private IP (DNS challenge) would restore
service workers.

**User's later direction:** not LAN-only — open web with auth, or enter a
code into the device to pair. That changes the security model
substantially and is the thing to think through first if this is revived:
what is exposed, what authenticates, and whether anything leaves the house.

## 3. Apple devices

**tvOS:** no sideloading at all, and the EU marketplace rules do not cover
it. Xcode deployment expires in 7 days (free) or a year (paid);
TestFlight builds expire at 90 days and external testing needs review. App
Store distribution is not plausible for this app. AVPlayer also has no
DASH support, so quality is capped at muxed progressive unless the hub
remuxes to HLS.

**iPhone / iPad:** same rewrite, softer wall — AltStore or Sideloadly at 7
days free or a year paid, EU marketplaces, or TestFlight's 90 days. iPad
has Guided Access, which is better kid-lock than anything Android gives
without owning the device.

**The useful reframing:** an Apple client cannot resolve streams itself, so
it would talk only to the hub and never to YouTube. That makes it a browse
UI plus AVPlayer plus the existing LAN API — a few thousand lines of
Swift, not a translation of the Kotlin app. And AirPlay from an iPad
covers the television without a tvOS app at all.

**Decision if revived:** web console first (covers Apple parents with no
Apple-specific code), hub second, thin client last, starting with iPad.

## 4. Content freshness (answered, may still want work)

New videos are **not** gated on the parent phone. Every device fetches for
itself: `refresh()` → `warmCaches()` walks every source and saves each
channel's first page. Triggers are app launch, returning to the
foreground, opening a channel, and a five-minute poll that only runs while
the app is on Home.

**So the real staleness cause is that the app must be running.** There is
no background content job; the only WorkManager task is the master-only
index crawl. A TV that has been off shows a stale home until someone opens
Pickwick and leaves it on Home long enough for the sweep.

**Cheapest fix:** a periodic background warm on the device itself, via
WorkManager. No new component. The hub is the answer to a different
problem — the N-times-duplicated fetching and screening across devices.
