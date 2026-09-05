# Plan — the hub's admin GUI

The hub today merges, stores and serves. It has no face, so it is a rendezvous
point rather than an administration centre: everything a parent can *do* still
has to be done from a phone. The next iteration gives it the same surface the
phone has.

## The requirement

Everything doable in the phone's Settings should be doable in the hub's web UI.
The two mirror each other, and diverge only where they must — with the
divergence stated deliberately rather than discovered later.

That last clause is the hard part. Two UIs meant to mirror each other drift the
moment one of them gains a feature, and nothing about a Compose screen tells
you that a browser page across the repo was supposed to grow the same control.
The drift is silent and it is found by a parent who cannot do on the NAS the
thing they just did on their phone.

So parity is enforced, not intended. See "The parity harness" below.

## What already exists

| Piece | Where |
| --- | --- |
| HTTP server | `HubServer` — `com.sun.net.httpserver`, no dependencies |
| Config read/write with real merge semantics | `HubStore.commit` into `ConfigMerge` |
| Devices, pending enrolments, approval | `HubTokens`, `/pending`, `/approve` |
| The config model and every validation rule | `:core` — shared verbatim with the app |

## What is new

- **A browser session.** The admin token is a header today, which a browser
  cannot send by typing a URL. Needs a login form that exchanges the admin
  token for a session cookie, and the cookie must be `HttpOnly`, `SameSite`,
  and expiring. The LAN-facing bounds already applied to `LanServer` reads
  apply here too: this faces the whole LAN before any token is checked.
- **Static asset serving.** `HubServer` speaks JSON only. Assets ship inside
  the jar, so there is nothing to mount and nothing to keep in step with the
  container's volume.
- **The pages themselves.**

## The rule that matters most

Every edit the GUI makes goes through `HubStore.commit()`. Never a direct write
to `config.json`.

That single path is what makes the hub a peer rather than a device that
clobbers: `commit()` merges against what is already there, so an edit made in
the browser while the other parent edits on their phone combines instead of one
winning. `scripts/check.*` already pins the single-write-path invariant, so a
GUI that bypassed it fails the gate rather than quietly losing a parent's work.

## The parity harness

A manifest in `:core` is the single source of truth for the settings surface:
every section, with an id, a title, and where it is expected to appear —
`PHONE`, `HUB`, or `BOTH`.

Three guards in `scripts/check.ps1` and `scripts/check.sh`:

1. Every `internal fun *Section(` under `app/src/main/java/io/yosemitekids/app/ui/Settings*.kt`
   has an entry in the manifest. Adding a phone section without a decision
   about the hub fails the build.
2. Every manifest entry marked `BOTH` is implemented in the hub GUI — matched
   by the section id appearing in the hub's page registry.
3. Every hub GUI section appears in the manifest. No drift in that direction
   either.

The manifest is therefore not documentation. It is the thing that must be
edited to make the build pass, and editing it is the moment the "does this
belong on the hub?" decision gets made.

A skill, `yosemite-kids-settings-parity`, carries the judgement a script cannot: how
to decide `PHONE` vs `HUB` vs `BOTH` for a new section. See "Where they
diverge" for the reasoning it encodes.

## Where they diverge, and why

Divergence is expected. What is not acceptable is undeclared divergence.

| Section | Where | Why |
| --- | --- | --- |
| `ChannelsSection` | BOTH | The curation itself. The whole point. |
| `RulesSection`, `BlockedTimesSection`, `GrantTimeSection` | BOTH | Screen time is family policy, not device state. |
| `AiScreeningSection`, `AiReviewSection`, `AiDiscoverySection`, `DirectorySection` | BOTH | Policy and suggestions, both merge-carried. |
| `ExportSection` | BOTH | A backup taken from the always-on box is the more useful one. |
| `AiConnectionSection` | BOTH, **partially** | Model and base URL yes; the API key no. The hub strips secrets before writing and has no `SecretStore` to hold one, so a key entered there could not survive a restart. The field is phone-only and the hub page says so. |
| `PhoneDevicesSection` | BOTH, **differently** | The phone lists devices it has paired. The hub lists everything enrolled with it, approves pending codes, and can revoke — administration the phone has no equivalent of. |
| `DownloadsSection`, `LocalVideosSection` | PHONE | Android storage and the platform download manager. Nothing on the hub to point them at. |
| `HubSection` | PHONE | It is how a phone joins a hub. Meaningless on the hub itself. |
| `UpdateSection` | PHONE | The app self-updates from a release APK. The hub updates by rebuilding its image, which is a different page with different words. |
| Container health, admin token, log tail | HUB | No phone equivalent, and none wanted. |

## Order of work

1. Session auth and static serving — nothing else can be reached without them.
2. Channels, then screen time. The two a parent actually opens.
3. The manifest and its three guards, **with** the first `BOTH` page rather
   than after the set is complete. A harness built last is a harness written to
   match what was already done, which proves nothing.
4. The remaining `BOTH` sections.
5. The hub-only administration page.

## Open questions

- Does the GUI need per-parent identity, or is one admin session enough? The
  merge records *who* made a change (`Recent changes` on the phone reads it),
  and a shared browser session would attribute everything to "the hub".
- Does anything here justify exposing the hub outside the LAN? Currently no,
  and that answer should be revisited deliberately rather than drifted into.
