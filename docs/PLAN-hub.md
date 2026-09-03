# The hub: multi-parent, source of truth, push/pull

Design outline, written before any code. The question it answers: with a
self-hosted container in the picture, who owns the config, and what happens
when two parents change things at once.

## What is actually broken today

Two parent phones never talk to each other. Each holds its own `config.json`
with an `updatedAt` and a fingerprint hash, and each talks only to the paired
kid devices. `MainViewModel.syncConfigState` asks a device for
`{hash, updatedAt}` and then:

- same hash → nothing
- device older → push the **whole** config over it
- device newer → do nothing, log *"differs but newer — leaving for Push/Pull"*

Three consequences, in order of how much they hurt:

1. **Whole-file last-writer-wins.** If two parents change different settings
   in the same window, one set of changes is silently gone. Nothing detects
   it, because the unit of comparison is the entire document.
2. **Manual, blind pull.** The second parent has to press Pull, with no way to
   see what they are about to accept or lose.
3. **Convergence goes through the TV.** Co-parents only ever agree by way of a
   kid device that happened to be reachable. If the TV is off, they diverge
   silently for as long as it stays off.

The hub fixes (3) by existing. It only fixes (1) and (2) if the *granularity*
changes at the same time. A hub that stores one blob and takes whole-document
PUTs is just a third phone that is always awake — the second writer still
clobbers the first.

**So the central decision is not "who is authoritative". It is "what is the
unit of change".** Get that right and authority mostly follows.

## The model

### 1. Clients send changes, not documents

Today a client sends its entire config and the receiver adopts it wholesale.
Instead, a client sends a **patch**: one addressed change, carrying the
revision it was based on.

```
PATCH /channels/UC_x_kABnXeHc   If-Match: 41   {"label": "SciShow Kids"}
DELETE /channels/UC_y           If-Match: 41
PATCH /profiles/emma/limits     If-Match: 41   {"weekdayMinutes": 30}
PATCH /settings                 If-Match: 41   {"autoplayNext": false}
```

The hub holds a monotonic `rev` per record. A patch based on a stale rev is
rejected with `409` **and the current value**, so the client can show the
parent exactly what changed underneath them and let them choose. A patch based
on a current rev is applied, bumps the rev, and is appended to the change log.

Two parents editing different channels never collide. Two parents editing the
same kid's bedtime is a genuine disagreement, and it should be *surfaced*,
not resolved by a coin flip on the clock.

### 2. Granularity: per key where the data is keyed, per section where it is not

Not per field — that is more machinery than a family needs. The config already
has natural seams (`Whitelist`):

| Records, merged by key | Sections, one rev each |
| --- | --- |
| `sources` (by channel id) | `limits` (family-wide screen time) |
| `profiles` (by kid id) | `ai` (model, endpoint, key) |
| `blockedVideoIds` (a set — add/remove, never replace) | `settings` (the scalars: quality, autoplay, pageSize, layout, suggestions, …) |
| `blockedFor` / `allowedFor` (by kid) | |

Adding a channel while a co-parent edits bedtime: no conflict, no prompt,
both land. That covers nearly every real simultaneous edit.

### 3. Three classes of data, three different rules

Sync is not one problem. Conflating these is what makes sync projects fail.

**a. Parent-authored config** — channels, blocks, profiles, rules, settings.
The hub is the source of truth. Concurrency is checked. Conflicts are shown to
a human. This is the table above.

**b. Device-generated state** — watch history, favourites, watch later, queue,
downloads, per-kid looks, screen-time usage. The *device* is the author; the
hub aggregates. Per-key last-write-wins, verdicts add-only. These never
conflict and must never prompt: if a kid watches something on the tablet and
something else on the TV, both are true. `syncWatchState` already works this
way against paired devices — the hub just becomes another peer in the same
merge.

**c. Device-local, never synced** — the video caches, the active-profile pick,
the search index on disk. Not the hub's business.

Writing this split down is the point. Anything new gets classified before it
gets a sync path.

### 4. Push/pull disappears from the normal path

With per-record revs and a long-poll channel:

1. Parent edits → phone sends the patch → hub applies and bumps the rev.
2. Hub wakes every long-polling device and the co-parent's phone.
3. Each fetches the records that changed. Not the whole config.

Nobody presses Push or Pull. Those buttons survive only as the LAN fallback,
where they belong.

### 5. Knowing what changed, before and after

The hub appends every accepted patch to a change log: `{rev, at, who, record,
before, after}`. It is small JSON and it buys three things at once:

- **A diff before accepting anything.** Any conflict prompt, and any manual
  pull in the LAN fallback, renders the actual before/after — "Dad set Emma's
  weekday limit to 30 min (was 45)". This is the thing that is missing today.
- **An activity feed.** "Why did the TV change?" becomes answerable. Today it
  is not answerable at all.
- **Undo.** A parent who fat-fingers a screen-time rule can put it back
  without reconstructing it from memory.

**This one is worth doing even without the hub.** In the LAN path the phone
already holds both configs at compare time; rendering a diff before Push/Pull
is a self-contained fix to the "blind pull" complaint and does not wait on
any of the above.

### 6. Offline, and the LAN fallback, are the same mechanism

Every client keeps its last-known config plus a **queue of its own pending
patches**, applied optimistically to what the parent sees.

- Hub reachable → send the queue, drain it.
- Hub unreachable → the queue holds. The parent keeps working; the UI says
  "not synced yet" rather than pretending.
- Hub unreachable *and* on the LAN → the phone applies its queue straight to
  the device over the existing LAN routes, exactly as today.
- Hub returns → replay the queue against it. Stale patches come back as
  conflicts with a diff.

One patch format, three transports. The LAN push is not a special case that
can silently diverge; it is the same change, delivered a different way, and it
still has to answer to the hub afterwards.

## What the container buys beyond sync

This is what makes it worth running, and most of it is not about conflicts.

- **Always on.** Today a TV only learns about a config change when a paired
  parent phone happens to run the app on the same LAN. That is the single
  biggest practical limitation of the current design.
- **Setup without a QR or a LAN.** A device enrols by typing a short code from
  the hub. No same-network requirement, no first-requester-wins bootstrap
  window to defend.
- **The crawl.** The search index is currently built by an elected master
  phone, is rate-limit expensive, and only advances while that phone has the
  app open. A container crawls on a schedule and serves the index to
  everything. This removes master election entirely and is the real fix for
  "new videos don't show up until someone opens the app".
- **Screening once.** One place holds the AI key and screens a video once,
  instead of every device screening the same video with the same key.
- **Real backup.** A volume holding the config *and its history* is a backup.
  Today that is a manual export a parent has to remember.
- **Remote control.** "Play this on the living room TV", from anywhere.

## Consequences worth accepting deliberately

- **The AI key moves.** It is currently Keystore-encrypted, kept out of
  `config.json`, and excluded from cloud backup. On the hub it becomes a value
  in a container volume: encrypted at rest with a key derived from the hub
  password, excluded from anything the hub exports. Note it already travels to
  kid devices in the pushed payload, so this is a new *location*, not a new
  kind of exposure.
- **`ConfigStore` and the LAN protocol are fork-modified files.** The patch
  path is additive: a household that never runs Docker keeps today's
  whole-document LAN sync untouched, including the append-only-when-set
  fingerprint scheme.
- **Self-hosted, one household per container.** No shared database, so no
  tenant isolation to get wrong. Multi-household later is more containers, not
  a rewrite.

## Build order

1. **Diff before Push/Pull, in the app, on the LAN path.** Immediately useful,
   no hub required, and it forces the change-description format that
   everything below reuses.
2. **Patch model in `ConfigStore`** — records, revs, apply-patch, change log.
   Pure logic, so it is unit-testable without a `Context`, per the project's
   own convention.
3. **The container** — store, enrolment by code, patch endpoints, long-poll,
   change log.
4. **Android client** — patch queue, long-poll, LAN fallback.
5. **Crawl and screening in the container**, retiring master election.
6. **Device control.**

## The hard case: hub down, both parents edit

Both phones queue patches locally against the last rev they saw (say 41). The
hub comes back. What happens depends entirely on two invariants, and they are
the reason the rest of the design is shaped this way.

**Invariant 1 — revs are per record, never global.** With one global rev, a
parent adding a channel makes the other parent's unrelated bedtime edit
"stale", and every reconnect becomes a conflict storm about nothing. Per
record, unrelated edits are simply unrelated.

**Invariant 2 — order comes from the hub's sequence, not from clocks.** The
first patch to arrive takes the next rev. Timestamps are recorded for display
in the change log and are never used to decide a winner. Today's newest-wins
is literally a comparison of two phones' clocks, which is a bug waiting for a
timezone change or a device that booted with a bad RTC.

With those, the cases resolve like this:

| What happened | Result |
| --- | --- |
| Different records (a channel vs a kid's limits) | Both apply. No prompt. |
| Same record, different fields (her bedtime vs her weekday minutes) | Field-wise merge, both apply. No prompt. |
| Same record, same field (45 min vs 60 min) | Second one is refused with the current value. Parent sees both and picks. |
| Both add to a set (two different videos blocked) | Both apply. Sets take add/remove operations, never a replacement. |
| Block vs unblock of the same video | The block holds. The parent who unblocked is told why. |
| Delete vs edit (channel removed vs relabelled) | The delete holds, and the editor is told, with "restore with my change" offered. |

Two of those are opinions rather than mechanics, and they are deliberate:

- **Restrictive wins, for permissions only.** When two parents disagree about
  whether a kid may see something, the closed answer stands until a human
  settles it. Being wrong costs a kid an hour of a show; being wrong the other
  way shows them something a parent had ruled out. This applies to blocks and
  to per-kid allow lists — **not** to screen-time numbers, where "restrictive"
  is not obviously the safer guess and the parents should just be asked.
- **Delete wins over edit.** Removing a channel is usually a considered safety
  call, and a label edit should not quietly undo it. The restore option exists
  because the delete might have hit the wrong row.

### A LAN push while the hub is down is still durable

A parent at home can push over the LAN with the hub unreachable. The device
keeps **the patches it received**, with their author, not just the resulting
document. On reconnect it offers them to the hub like any other client.

That matters for the nasty case: a parent pushes to the TV over the LAN, then
loses their phone before it ever reaches the hub. Because the TV holds the
patch rather than a blob, the change survives and lands. It also means a
device's config is never treated as authoritative just because it took a
push — it is a cache of whatever last reached it, plus a small outbox.

### What the parent actually sees

Offline, patches apply optimistically to their own screen, with a visible
"not synced" state and a count. It never pretends to be saved.

On reconnect, one summary rather than a stream of dialogs: *"3 of your 4
changes applied. Emma's weekday limit needs you: you set 60 min, Dad set 30
min."* Only genuine same-field disagreements interrupt anyone.

### The guarantee

Nothing is silently lost. Every queued patch either applies, merges, or is put
in front of a human with both values shown. That is the property today's
design cannot offer at all, and it is the reason to build the hub rather than
to add a fourth device to the existing scheme.

## Red team, before committing to any of it

Run against the design above: *it is a year on and this was the wrong build —
why?*

**1. This may be a distributed-systems answer to a two-parent problem.**
Two adults, three to five devices, a handful of edits a week. The odds of a
genuine same-field edit *while the hub is down* are close to nil. Per-record
revs, patch queues and conflict UI are what you would build for a product with
thousands of concurrent editors.

The counter is real but narrower than it first looks: the cost today is not
how often collisions happen, it is that a lost edit is **silent and
unattributable**. One "I set her bedtime and it reverted" is enough to stop
trusting the app. But that is an argument for *attribution*, not for
optimistic concurrency control.

**A change log plus a diff delivers the attribution. Sectioned
last-writer-wins delivers the merge.** Together they cover the realistic cases
at a fraction of the size, and if two parents do collide on one section, the
log says exactly what happened and undo puts it back. The patch/rev machinery
should wait for evidence it is needed.

**2. The hub must never be on the critical path.** What has to be right at
8pm is what the TV enforces. A device must hold a complete, self-sufficient
config and keep enforcing bedtime with the container off, the internet down,
and no phone in the house. That makes the hub a *distribution and
reconciliation point*, not a source of truth in the runtime sense. Worth
stating plainly, because "source of truth" invites exactly the wrong
implementation.

**3. The hub's disk is a new single point of failure.** Self-hosted means a
NAS dies and takes the config with it. Today every device holds a full copy,
which is accidentally an excellent backup — and the design must keep that
property. **"Adopt the config from this device" has to be a first-class hub
operation**, not a recovery script.

**4. The enrolment code is the one internet-facing credential.** A short code
typed into a TV is a bearer token. It needs single use, short expiry, and
lockout after a few attempts — six digits with unlimited retries is guessable
from anywhere on earth. This is the piece to get right; everything else is
behind an already-authenticated session.

**5. The AI key decision inverts.** The instinct was to sync it to devices via
the hub. But if the container screens centrally, the key never has to reach a
device at all — devices ask the hub for verdicts. That is strictly better than
today, where the key travels to every kid device in the pushed payload. Take
the key *off* the devices rather than routing it through a new place.

**6. Most families will never run the container.** Every decision has to
degrade to exactly today's behaviour. The trap is two config code paths that
drift apart. If the patch model happens at all, LAN sync must be
*reimplemented* on it rather than kept alongside it.

**7. The stated need was not conflict resolution.** The original motivation
was getting content to the screens when no phone is around. That is the crawl
and always-on distribution. Conflict resolution is a problem this document
introduced on the way past. Build the thing that was asked for first.

## Revised build order

1. **Diff before Push/Pull, on the LAN, no hub.** Fixes the blind pull now.
2. **Change log in `ConfigStore`** — who changed what, when. Attribution is
   most of the value and it is local, pure and testable.
3. **The container: enrolment, always-on config distribution, fan-out.**
   Sectioned last-writer-wins, not per-record revs.
4. **The crawl in the container**, retiring master election. Probably the
   feature with the most day-to-day value in the whole plan.
5. **Central screening**, key on the hub only, removed from devices.
6. **Device control.**
7. **Per-record revs and conflict UI — only if the change log shows real
   collisions happening.**

## The decision: one editor, two kinds of action

Everything above circles a tension — "all editing happens on the hub" is by
far the most intuitive model, but it strands a parent when the hub is down.
The tension dissolves once you notice that parents do two quite different
things, and only one of them is ever urgent.

**Configuration** — add a channel, change a bedtime, add a kid, pick a quality
ceiling, turn suggestions off. Rare, deliberate, done sitting down. Nobody has
ever needed to add a channel at 8pm from a dead network.

**Control** — give her fifteen more minutes, stop everything now, block that
video, let this one through. Reactive, urgent, happens on the sofa with the
thing already playing.

So:

- **Configuration is hub-only, when a hub exists.** One editor, one copy, no
  merge, no revisions, no conflict UI. This is the whole of the simplification
  the earlier sections were working around.
- **Control always works, hub or no hub**, phone straight to the device over
  the LAN, exactly as it does today.

The reason this is safe rather than a fudge: **control actions cannot
conflict.** "Add 15 minutes" is an increment. "Pause" is a state flip.
"Block this video" is an add to a set. Two parents doing them at once is
fine in any order. Configuration edits are precisely the ones that *do*
collide — and those are the ones nobody needs during an outage.

### "Hub-only" does not mean "not from your phone"

The hub serves a web UI. The phone's settings screen opens it. A parent adding
a channel from the sofa is still doing it on their phone — they are just
looking at the one editor instead of a second copy of the settings. This is
how a router or a NAS already works, and it is the part that makes the model
easy to explain: **there is one place the setup lives, and everything else
shows it.**

It also means the co-parent problem stops existing rather than being managed.
Two parents on the same page see the same page.

### What each state looks like

| | Configure | Control | Kids watching |
| --- | --- | --- | --- |
| Hub up | On the hub, fans out to every device at once | Works | Works |
| Hub down | Unavailable, and the app says so plainly | Works, over the LAN | Works — devices hold a full config and keep enforcing |
| No hub at all | On the phone, as today | Works | Works |
| Hub lost for good | Adopt the config from any device and carry on | Works | Works |

The invariant underneath the whole table: **nothing about the hub is on the
playback or enforcement path.** A device holds a complete config and enforces
bedtime with the container off, the internet down and no phone in the house.
The hub distributes and reconciles; it is never a runtime dependency.

### What this deletes

Against the earlier sections of this document: no patch queue, no per-record
revisions, no `If-Match`, no conflict resolution UI, no restrictive-wins or
delete-wins rules to explain to anyone. Those existed only to referee two
config editors, and there is now one.

Kept, because they earn their place independently:

- **The change log** — who changed what, when, with undo. Attribution is
  most of what was wanted, and it is worth having on the hub and on the phone.
- **The diff before Push/Pull** in the no-hub LAN path, which is still today's
  reality for anyone who does not run a container.
- **Fan-out** — one write, every device told at once, instead of a phone
  walking a device list while it happens to be on the right Wi-Fi.

## No container at all

Everything works. The hub is strictly additive — a household that never runs
Docker keeps today's app: LAN pairing, config push, screen-time enforcement,
playback, downloads, search. That is a hard requirement, not an aspiration; if
a change cannot degrade to today's behaviour it does not go in.

What is missing without one:

- A config change reaches a TV only when a paired parent phone runs the app on
  the same network.
- The crawl only advances while the elected master phone has the app open, so
  new videos appear late.
- No control from outside the house, no central screening, no off-device
  backup.

So: fully functional, but phone-mediated and LAN-bound. Which puts the weight
on the next question.

## Multiple parents without a container

This is the case worth fixing first, because it is most families and it is
where the current design actually loses data.

**The fix is a sectioned merge, peer to peer, no server involved.** Today a
device receiving a push replaces its whole config (`ConfigStore.saveRaw`).
Instead, give each section its own `updatedAt` and merge section by section,
taking the newer side of each:

- Dad adds a channel; Mum shortens a bedtime. Different sections, both
  survive, nobody is prompted. **Today one of these is silently lost.**
- Both edit the same section. Genuinely ambiguous, so prompt with the diff and
  let a parent choose. Rare.

The TV then becomes the rendezvous point that actually works: Dad pushes,
Mum pushes later, the TV holds both, and each phone picks up the merged result
next time it syncs. Convergence is delayed while everyone is out of the house,
but it is no longer lossy.

Two additions make it comfortable:

- **A change tail in the config** — the last N changes with who and when. It
  travels with the config, so a phone that has been away can say "Dad added
  SciShow Kids and blocked two videos" instead of "this differs".
- **Phone-to-phone sync on the same LAN.** Parent phones currently never talk
  to each other, only to kid devices. Pairing them removes the dependency on a
  TV being switched on at the right moment.

### The catch, stated honestly

**Deletes.** A naive section merge resurrects them: Dad removes a channel,
Mum's copy still has it, Mum's section is newer, the channel comes back. For a
parental-controls app that is the worst possible bug — a channel a parent
removed reappearing.

That needs tombstones: a small `removed: {id → when}` map, where a removal
beats any older addition of the same id, pruned after a few months. It is well
understood, but it is the reason this is more than a weekend's work, and it is
where the tests need to be pointed.

### Why this goes before the container

The sectioned merge is the same rule the hub would use to distribute config.
Building it peer-to-peer first means multi-parent stops losing edits for
*every* household including the ones that never run Docker, and the container
then becomes what the red team said it always was: always-on distribution, the
crawl, remote control, central screening, backup — rather than a new model to
learn.
