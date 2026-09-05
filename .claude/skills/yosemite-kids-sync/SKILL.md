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

## 2. Nine prohibitions

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
| `lim.rules`, `lim.windows`, `lim.pause`, `lim.brk` | family screen time | scalar |
| `ai`, `settings`, `master` | one blob each | scalar |

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

## 6. The half-upgraded household

**Guaranteed:** an old build parses, stores and enforces a sync-bearing config
unchanged; a legacy peer never loses content to a merge; the fingerprint is
unmoved.

**Not guaranteed:** deletes do not cross a legacy hop — an old TV's
`GET /config` re-serializes through its own model and launders the blob out.
The change log makes that visible and the devices row names the version.

`version.json` still points at upstream and `UPDATE_MANIFEST_URL` defaults to
empty, so shipping an update to both ends is not currently available, and
`Updater` offers no downgrade. **Plan format changes forward-only.**

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
