---
name: yosemite-kids-sync
description: Rules and invariants for Yosemite Kids' sectioned config merge — the sync blob, tombstones, stamps, and the peer-to-peer reconcile. Use whenever changing ConfigStore, ConfigMerge, ConfigStamp, Whitelist, Settings.buildCurrentConfig, the GET /status or POST /config routes, Backup, or anything that writes config.json.
---

# Config sync invariants

Read this before touching `ConfigStore`, `ConfigMerge`, `ConfigStamp`,
`Whitelist`, or the `/config` and `/status` routes. The design and the
reasoning behind each rule are in `docs/PLAN-sync.md`.

## 0. Where this code lives, and why that matters

The merge, the stamper, the sync decision and the config serializers are in
**`:core`** — a plain-JVM Gradle module with no Android on its classpath. The
Android app depends on it, and so will the Docker hub, so there is one
implementation of these rules rather than two that drift apart.

Three things follow, each enforced by a guard in step 0 of `scripts/check.ps1`
and `scripts/check.sh`:

- **`:core` must not import Android**, and must not apply an Android plugin.
  Break either and the hub cannot build the code it depends on — a failure
  that would surface in the hub's build, far from the edit that caused it.
- **The merge's tests live in `core/src/test`.** In `:app` they still pass,
  but they only prove the merge works *on Android*; the hub would be running
  the same logic with nothing covering it. `:core:test` is its own gate step
  for that reason.
- **Anything reachable from another module cannot be `internal`**, and Kotlin
  will not smart-cast a public property across a module boundary. Both bite at
  the boundary, not before it.

## 1. The two clocks, and why one of them is a lie

`toJson` stamps `updatedAt = System.currentTimeMillis()` at **serialization**
time, and `rawJson()` is `toJson(load())`. A document's `updatedAt` on the wire
is therefore the moment it was handed over, never the moment a parent edited
it. A phone out of a drawer claims to be brand new.

**Never derive an ordering, a staleness test, or a tombstone from
`updatedAt`.** The edit clock is `sync.at[unit]`, minted once per save in
`ConfigStamp.stamped`.

## 2. Twelve prohibitions

1. **Never add a term to `ConfigStore.fingerprint`.** Stamps and tombstones
   live in `syncHash`, advertised separately on `/status`. An old build can
   never reproduce a hash taken over a blob its `fromJson` ignores, so the pair
   would read "out of sync" forever after the first channel deletion — and the
   reconcile would stop pushing to it. Pinned by
   `ConfigSyncFormatTest.fingerprintIgnoresTheSyncBlobEntirely`.
2. **Never read a clock inside `ConfigMerge.merge`.** It takes no `now`
   parameter, and `scripts/check.ps1` fails the build if the file references
   one. This is what makes idempotence and associativity structural rather
   than properties that hold only while a test freezes time.
3. **Never clear a tombstone on a re-add.** That is `SavedListStore.add`'s
   shape and it is wrong here: it destroys the evidence the causality rule
   needs, so a deliberately re-added channel vanishes again on the next sweep,
   forever.
4. **Never derive a tombstone from an absence in a document with no sync
   block.** A legacy document carries no delete information at all.
5. **Never merge from `ConfigStore.load()`.** Use the raw bytes. `load` scrubs
   lapsed passes and lays a kid's un-adopted restyle over the profiles, so
   merging from it reads a clock tick and a child's private choice as parent
   edits.
6. **Never parse and re-serialize through `Whitelist` inside the merge.**
   `saveRaw`'s forward-compatibility guarantee holds only while nothing
   re-serializes from the model, and a model round trip additionally loses
   unknown fields *nested inside* known objects.
7. **Never construct a `Whitelist(...)` in `Settings`.** Use
   `baseline.copy(...)`, or the next field added to the class is silently
   defaulted out of every save and every push. Guarded in `check.ps1`.
8. **Never write `config.json` outside `ConfigStore.commit(json)`**, and never
   hold `FILE_LOCK` across a Keystore round trip. Guarded in `check.ps1`.
9. **Never put anything derived from the `ai` object into `sync.log` or a
   collision record.** `stripSecrets` removes exactly `root.ai.apiKey` and can
   never be taught to walk free text, and `config.json` is in all three backup
   include lists. The `Change` record has no value field at all, which is a
   stronger guarantee than filtering one.
10. **Never assemble the sync blob only from the units the loops visit.**
    Every loop in `merge` walks content ids, and a settled delete has none:
    a tombstone whose subject neither side lists was silently dropped, so a
    delete was enforceable for exactly one merge and a stale peer re-added
    the channel as new. The carry pass before `prune` keeps every unvisited
    tombstone (and, in fail-closed namespaces, the block stamp that proves a
    lift); a lift the fail-closed rule rejects keeps *no* tombstone, or the
    merged copy would look like a deliberate unblock on the next round; and
    `rulesVersion` is a function of the two documents, never of how many
    times they met. `MergeConvergenceTest` runs the merge again and again
    with the inputs held still, which is what a Push button and a
    fifteen-minute worker actually do; a rule that passes `ConfigMergeTest`
    once and fails there is not a rule yet.
11. **Never let the settings form's state and its baseline come from
    different documents.** `ConfigStore.save` returns the *stamped* result,
    which is not what the form handed in: the stamper carries every unit a
    co-parent's push landed under the open form, and keeps the disk's copy
    of any section the editor left alone (`section3`, and now the loose
    settings scalars too). Adopt that as the baseline while the form keeps
    its own lists and the next save shows the stamper a unit in `base` and
    not in `next` — a deletion, tombstoned and propagated, of a channel
    nobody removed; and `ai` re-mints on every tap. So the screen's saves
    all run `saveForm` and `adopt`, which take the same document into both,
    in one snapshot. Guard 15 holds `baseline` to that one assignment;
    `SettingsFormSaveTest` drives three saves of an unchanged form. The
    stamper side of the same contract is
    `ConfigStampTest.aSaveThatChangesNothingMintsNothing`: previous, base
    and next all equal must leave the sync block byte-for-byte alone,
    `docAt` included.
12. **Never put a counter in `config.json`.** Not minutes watched, not
    channel opens, not a play count — nothing a device increments while a
    child is sitting in front of it. This is the most natural-looking wrong
    turn left in the design, and the only prohibition here that no script
    can help with: a `use|<kid>|<day>` unit looks exactly like `grant|<id>`,
    it would slot into §3's table without anything looking odd, and every
    guard in `scripts/check.*` would stay green. This paragraph is the whole
    of the enforcement, which is why it spells the arithmetic out.

    **What it costs.** `SyncDecision.syncAction` takes the `Merge` arm on
    **any** `syncHash` difference, and a counter's stamp moves `syncHash`.
    One increment is therefore a `/status`, a `GET /config`, a merge and a
    re-push *between every pair of peers*, plus `HubStore.onChanged` →
    `HubNudge` fanning out to every enrolled device — once a minute, for as
    long as anyone in the house is watching, for the life of the feature.
    And `mergeLogs` keeps the last `SyncMeta.MAX_LOG` (30) lines: a usage
    line a minute wipes a family's change history in half an hour, and that
    history is now the feed on the hub's home page as well as the phone's
    Recent changes. The sync traffic is annoying; erasing the record of who
    changed what is the part a parent would actually be hurt by.

    **The deeper reason, which outlives the numbers.** Every unit in §3
    resolves by *who acted later* — a stamp, and a winner. A counter has no
    winner; it has a **join**. Two devices that each played twenty minutes
    did not disagree with each other, and last-writer-wins throws twenty
    minutes away. A document whose merge is "newest stamp wins" is the wrong
    container for a value whose merge is `max`.

    **Where it goes instead**, if it is ever built: its own small document,
    grow-only cells keyed `(kid, day, device)`, joined per cell by `max`,
    outside `ConfigStore.fingerprint` and outside `syncHash`, with a merge
    that takes no clock at all (prohibition 2 applies to it too, and a
    `today: String` parameter sails straight past the guard that greps for
    `currentTimeMillis`). `docs/PLAN-hub-parity.md` §4 is the design and
    `docs/ROADMAP.md` item J is why it is parked — read J first, because it
    argues the whole thing shrinks to almost nothing if a shared budget is
    allowed to require a hub. And the day rules, whichever design wins:
    **write under your own day, read forward, never adopt a peer's.**
    `max(localDay, seenDay)` turns the day boundary into a ratchet the merge
    itself propagates, so one television with a wrong clock walks the entire
    household's day forward and hands out a second budget that no parent
    action reverses.

## 3. The unit table

A *unit* is the smallest thing two parents can edit independently.

| Namespace | What it covers | Safe state |
| --- | --- | --- |
| `src` | one channel or playlist | ABSENT |
| `kid`, `kid.pin` | one kid, and their PIN separately | ABSENT |
| `kid.rules`, `kid.windows`, `kid.pause`, `kid.brk` | that kid's screen time | scalar |
| `blk` | one blocked video | **PRESENT** |
| `allow` | one safe-listed video | ABSENT |
| `for` | blocked for one kid | **PRESENT** |
| `afor` | allowed for one kid | ABSENT |
| `dev` | one device's kid assignment | ABSENT |
| `grant` | one "Add time" tap: minutes for one kid or everyone, on one day; a day that has passed is tombstoned by the phone's next save, never by the merge | ABSENT |
| `lim.rules`, `lim.windows`, `lim.pause`, `lim.brk` | family screen time | scalar |
| `ai`, `settings` | one blob each | scalar |
| `master` | which peer builds the search index. Its stamp is also the holder's liveness: the holder re-touches it every 6 h through the stamper's `refresh` set (the one stamp allowed to move without a change), a stamp older than 24 h means a vacant slot, and on a tie a hub token (`.hub…`) beats a phone (`MasterToken.preferred`). The rules are `MasterElection.decide`, clock passed in | scalar |

**Polarity is not uniform, and that is deliberate.** For a block the safe
answer is that it *stays*: presence wins ties and needs no proof, while lifting
one does — so a parent unblocking ten minutes later works, because their copy
carries the block and that is the proof, but a re-block from a phone that never
saw the unblock is honoured rather than discarded. For a channel or a kid it
inverts: absence is safe, so asserting presence against a tombstone is what
needs proof.

## 4. Adding a field to the config

1. Pick a merge unit, or add one to the table above.
2. Stamp it in `ConfigStamp.stamped`.
3. Give it a change-log code and a line in `ConfigMerge.describe`.
4. Decide its safe state if it is removable.
5. Add it to `toJson`/`fromJson` **omitted at its default**, so a family that
   never touches it keeps its bytes and its hash.
6. Add it to `fingerprint` only under the existing append-only-when-set rule,
   and only at the tail.
7. Write the four canonical tests, using `ConfigStoreJsonTest` as the
   template: round-trips, omitted at default, keeps the pre-feature
   fingerprint, moves the fingerprint when set.

## 4a. The API key is not in the document, and that has a rule

Two faces now hold the key outside `config.json` — the phone in `SecretStore`
and the hub in `HubSecrets` — because that document is served to every peer,
copied into the hub's `versions/` ring, rendered by the admin page and handed
to a parent as a backup. Every write path strips `ai.apiKey`, and the key is
overlaid back on only where a peer must actually compare or receive it.

What that costs, and what a future session has to remember:

- **The merge is told the key out of band.** `ConfigMerge.merge` takes
  `localApiKey`, because the local document it is handed is always keyless and
  without it `pickKey` sees a blank local side and takes the incoming key
  unconditionally. A peer that slept through a rotation then hands the dead key
  back to the whole household, screening keeps working, and the bill is the only
  symptom. Any new caller of `merge` that stores a key must pass it and must
  store what comes back.
- **A third place that can set the key must move the `ai` stamp itself.** The
  key is resolved between peers by that unit's stamp like every other field. A
  face that keeps the value outside the document changes nothing the stamper can
  see, so it has to ask: `HubStore.setApiKey` passes `refresh = setOf(AI)`.
  Without it a rotation and the old key on a sleeping peer are a **tie**, broken
  lexicographically, and about half of all rotations lose to the key they
  replaced. No script can catch this — it looks exactly like an ordinary write.
- **A blank never clears a real key**, and that is deliberate: "this peer holds
  none" and "clear it" are the same bytes on the wire, and the commonest peer in
  the fleet is keyless. Clearing therefore does not propagate. Do not "fix" it
  without a way to tell those two apart.

## 5. What breaks silently, and its symptom

| Mistake | What you will see |
| --- | --- |
| Entry order becomes device-dependent | Two phones hash identical content differently and read "differs" forever |
| `syncHash` over `JSONObject.toString()` | Insertion-order dependent. Invisible to JVM tests: `org.json` uses a HashMap, Android a LinkedHashMap |
| A tombstone TTL | Devices on different clocks prune different sets and push at each other forever |
| A `Whitelist(...)` in Settings | Every ordinary edit pushes a sync-less document; the merge never runs on the primary path |
| Positional rank starting at 0 | Zero means "no stamp", so a legacy document's first channel silently disappears |
| An empty container written differently from `toJson` | A document merges against itself as "changed", and the sweep pushes forever |
| A settings-open guard | Reconciliation stops while a parent leaves Settings open, and it never covered `POST /config` anyway |
| Scrub emptying `profileIds` | `visibleTo` fails open and a kid sees a channel meant for an older sibling |
| The form adopts a save as its baseline and not as its state | The next tap tombstones whatever the stamper carried in (a co-parent's channel), and "changed screening" appears in the feed on every tap |

## 6. The half-upgraded household

**Guaranteed:** an old build parses, stores and enforces a sync-bearing config
unchanged; a legacy peer never loses content to a merge; the fingerprint is
unmoved.

**Not guaranteed:** deletes do not cross a legacy hop — an old TV's
`GET /config` re-serializes through its own model and launders the blob out.
The change log makes that visible and the devices row names the version.

Since 1.0.3 the fork ships its own `version.json` and every device can pull the
next build — from its own settings, or from "Update now" on the parent's phone
(`POST /check-updates`). What has not changed is the direction: `Updater` will
not install an older `versionCode`, so a format both ends cannot read is not
something a release can walk back out of. **Plan format changes forward-only.**

## 7. Where the rendezvous is

Parent phones never pair with each other — only `TvSettingsScreen` and
`KidDeviceScreen` render a QR. Two parents' edits meet on a kid device that is
powered on and on the LAN. A household whose TV is unplugged for a fortnight
gets no reconciliation at all, which is why tombstones are permanent rather
than aged out.

This is the seam a later Docker hub fills. Anything the hub needs must stay
strictly additive to what is described here.

## 8. Before claiming anything works

`scripts/check.ps1` (or `/yosemite-kids-check`), and for anything touching the wire,
the emulator loop in `scripts/emu.ps1`. **Never POST /pair-request while
testing** — the first requester is auto-approved as admin.
