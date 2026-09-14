# Sectioned merge config sync — settled plan (peer-to-peer, no hub)

Produced by an 18-agent design pass: six readers mapped the current code (242 cited facts, 95 hazards), three independent designs were drafted from different convictions, three judges scored them (this one won 3/3), five adversarial agents produced 60 attacks of which 38 were critical or high, and every one is fixed in the design below rather than deferred.

## The decision

**What lands.** One new root key in `config.json` — `sync` — carrying per-unit edit stamps, permanent tombstones, a namespace floor and a 30-line change log. One new pure file, `app/src/main/java/io/yosemitekids/app/data/ConfigMerge.kt`, that merges two config *documents* (JSON in, JSON out, sub-objects moved whole — never through `Whitelist`, so `saveRaw`'s guarantee that a newer build's unknown fields survive is preserved). `ConfigStore.saveRaw` stays a whole-file replace for the two callers that genuinely mean replace (backup restore, the parent-confirmed **Replace**); a new `ConfigStore.mergeIncoming` takes over `POST /config` and holds `FILE_LOCK` across read-merge-write. `MainViewModel.syncConfigState`'s dead third branch ("differs but its copy is newer — leaving for Push/Pull") becomes fetch-merge-write-push-back, which is what makes the TV work as a rendezvous between two parents.

**`ConfigStore.fingerprint` is not touched. Not one byte.** Hashing tombstones is a trap: an upgraded phone would compute a hash an un-upgraded TV can never produce (its `fromJson` ignores `sync`), so after the first channel deletion the pair mismatches *forever* and the reconcile stops pushing to it. Instead `GET /status` gains two additive keys, `syncV` and `syncHash`, and two fork peers count as in-sync only when **both** `hash` and `syncHash` match. A legacy peer is compared on `hash` alone — exactly today. That protects the ~15 fingerprint-equality assertions across `ConfigStoreJsonTest`/`ProfileConfigTest`/`TimeWindowsTest`/`ChannelNoteTest`/`ProfileLooksTest` and avoids a fleet-wide spurious re-push at upgrade.

**The merge is clock-free.** `merge()` takes no `now` parameter — the compiler enforces it. Every clock read lives in `stamp` time (`ConfigMerge.stamped`) or in `load()`'s existing read-time `scrubLapsedPasses`. This kills a whole family of bugs at once: a Chromecast that boots with a bad RTC can no longer drop a parent's active pause into the shared document (and have the kid told "Screen time is back on 🎉"); idempotence is structural rather than a test artifact that only holds while the clock is frozen; and `merge(merge(a,b),c) == merge(a,merge(b,c))` is provable.

**Absence-synthesis for legacy documents is deleted.** The original design gated it on "the incoming legacy doc's `updatedAt` is newer than ours". That gate can never be false: `toJson` stamps `updatedAt = System.currentTimeMillis()` at *serialization* time (ConfigStore.kt:316) and `rawJson() = toJson(load())`, so every document an old build serves or pushes claims to be brand new. A stale phone switched on after a fortnight in a drawer, or a freshly-installed tablet whose `rawJson()` falls back to an empty config, would tombstone the entire family config family-wide and permanently. So: **a document with no `sync` block carries no delete information.** Its members are admitted at positional rank (0,1,2…), never as tombstones. The honest cost — deletes made on a pre-fork build stop propagating to a fork device — is recoverable by re-deleting on a fork phone, and today's behaviour (that same push clobbers everything) is worse. Corollary: a legacy peer is a **push-only destination, never a merge source**. Combine is hidden when `/status` carries no `syncV`; only Replace is offered, with today's exact wording.

**Deletes.** Tombstones are permanent (no TTL — age-based pruning is clock-dependent, so two devices on different clocks prune different sets, their `syncHash`es diverge and they push at each other forever), capped at 1000 newest-first, with a per-namespace `floor` that refuses a one-sided row older than the newest dropped tombstone. **A re-add never clears its tombstone** — it writes `at > gone` and keeps `gone`. That is the opposite of `SavedListStore.add`'s `saveRemoved(removedMap() - url)`, and copying that shape was a live contradiction with the delete-causality rule: clearing the tombstone destroys exactly the evidence causality requires, so a deliberate re-add would vanish again on every sweep, forever, and the collision banner's own "Add it back" button would not work.

**Polarity is per namespace.** PLAN-hub.md:221 decides "the block holds" and "the delete holds", and those pull in opposite directions. For permissive units (`src|`, `kid|`, `dev|`, `allow|`, `afor|`) the safe state is ABSENT: absence wins ties, and asserting *presence* against a tombstone requires causality (the proposing side must itself carry that tombstone). For restrictive units (`blk|`, `for|`) the safe state is PRESENT: presence wins ties and never requires causality, while asserting *absence* (an unblock) does — so Dad unblocking ten minutes after Mum blocked works normally, because his copy carries her block, but a re-block from a phone that never saw his unblock is never silently discarded.

**Parent-facing rule: the merge never interrupts.** No dialog appears because a sync happened. A parent hears about it only when (a) they open Settings and their own edit lost, shown as one dismissible banner headlined with the *current state* and a one-tap "Put 45 back", or (b) they pressed Combine, which shows the diff first. Kid devices render none of it — a child should not learn that their parents disagreed about their bedtime.

**Hub room, not hub design.** Everything here is peer-to-peer and needs no server. The pieces a later Docker hub would reuse — the unit key space, `ConfigMerge.merge`'s pure signature, the change log's `Change` record with `by`/`who`/`code`/`text`, and `syncV` as a capability marker on `/status` — are all in place. Nothing here assumes a hub, and a household that never runs one behaves exactly as described.

## Format

## On-disk shape

One new root key. Every other key stays byte-identical, so an older build parses the file unchanged and `fromJson`'s `opt*()`-with-default discipline swallows `sync` without noticing.

```json
{
  "updatedAt": 1780000099000,
  "entries": [ ... ], "blocked": [ ... ], "limits": { ... },
  "ai": { ... }, "aiAllowed": [ ... ], "profiles": [ ... ],
  "blockedFor": { ... }, "allowedFor": { ... }, "deviceProfiles": { ... },
  "master": "a1b2c3d4e5f60718a9b0c1d2e3f40516",

  "sync": {
    "v": 1,
    "docAt": 1780000017000,

    "at": {
      "lim.rules":            1780000001000,
      "lim.windows":          1780000002000,
      "lim.pause":            1780000003000,
      "lim.brk":              1780000004000,
      "ai":                   1780000005000,
      "settings":             1780000006000,
      "master":               1780000007000,

      "src|UCabc":            3,
      "src|@scishowkids":     1780000020000,

      "kid|e3f1a90c":         0,
      "kid.pin|e3f1a90c":     1780000008000,
      "kid.rules|e3f1a90c":   1780000009000,
      "kid.windows|e3f1a90c": 1780000010000,
      "kid.pause|e3f1a90c":   1780000011000,
      "kid.brk|e3f1a90c":     1780000012000,

      "blk|dQw4w9WgXcQ":              1780000013000,
      "allow|abc12345678":            1780000014000,
      "for|dQw4w9WgXcQ|e3f1a90c":     1780000015000,
      "afor|xyz98765432|e3f1a90c":    1780000016000,
      "dev|a1b2c3d4e5f60718a9b0c1d2e3f40516": 1780000017000
    },

    "gone": { "src|UCxyz": 1780000018000, "kid|9a2b3c4d": 1780000019000 },

    "floor": { "src": 0, "blk": 0 },

    "log": [
      { "id": "9f3a1c02", "at": 1780000020000, "shownAt": 1780000020000,
        "by": "a1b2c3d4", "who": "Dad's phone",
        "code": "src.add", "text": "added SciShow Kids" }
    ]
  }
}
```

`sync` is written only when non-empty, following the file's existing append-only-when-set discipline (`toJson` lines 324/346/371/374). A single-parent family that never edits after upgrading produces a byte-identical file. **`ConfigStore.fingerprint` never sees any of it.**

`docAt` is the newest stamp this document has ever carried, used only as the monotonic floor for the next mint. It is *excluded* from `syncHash` (it moves on every stamp and would make two content-equal devices read as differing).

## The merge units

One flat namespace. Section units are bare names; keyed units carry their key after `|`. Same key space for `at` and `gone`, so a tombstone is always directly comparable to the thing it removes.

| Unit key | Governs (wire fields) | Safe state |
| --- | --- | --- |
| `lim.rules` | `limits.{session,weekdaySessions,weekendSessions,breakMinutes,minVideoMinutes}` | — |
| `lim.windows` | `limits.windows[]` **including each window's `passUntil`**, and the derived `bedtimeStart`/`bedtimeEnd` pair | — |
| `lim.pause` | `limits.pausedUntil` (present *or* explicitly cleared) | — |
| `lim.brk` | `limits.breakPassUntil` | — |
| `ai` | the whole `ai` object, minus `apiKey` | — |
| `settings` | `sponsorSkip`, `autoplay`, `suggest`, `channelLayout`, `channelOrder`, `listen`, `qualityTv`, `qualityPhone`, `pageSize`, `showVideoAge` | — |
| `master` | `master` | — |
| `src\|<entryId>` | one whole object in `entries[]` | ABSENT |
| `kid\|<profileId>` | that `profiles[]` object's `name` and `age` only | ABSENT |
| `kid.pin\|<profileId>` | `profiles[i].pin` | ABSENT |
| `kid.rules\|<profileId>` | `profiles[i].limits` scalars | — |
| `kid.windows\|<profileId>` | `profiles[i].limits.windows[]` incl. passes | — |
| `kid.pause\|<profileId>` | `profiles[i].limits.pausedUntil` | — |
| `kid.brk\|<profileId>` | `profiles[i].limits.breakPassUntil` | — |
| `blk\|<videoId>` | membership in `blocked[]` | **PRESENT** |
| `allow\|<videoId>` | membership in `aiAllowed[]` | ABSENT |
| `for\|<videoId>\|<kidId>` | membership in `blockedFor[videoId]` | **PRESENT** |
| `afor\|<videoId>\|<kidId>` | membership in `allowedFor[videoId]` | ABSENT |
| `dev\|<deviceToken>` | `deviceProfiles[token]` | ABSENT |

Units without a safe state are scalar/group units that cannot be tombstoned — they always have a value, possibly null.

**Not merge units:**

- **`profiles[i].{avatar,color,lookAt}`** — already has a shipped, tested merge (`ProfileLooks.applyLook`: newer `lookAt` wins, equal stamps keep the config's). The merge calls it rather than re-deciding, so a kid's restyle on the TV is not clobbered by a parent renaming that kid on the phone in the same window. That path is genuinely one-directional, which is why `applyLook`'s tie rule is right *there* and wrong as a general merge rule.
- **`ai.apiKey`** — never enters the merge. `ConfigMerge.merge` strips it from both inputs before merging and returns it out-of-band in `Result.apiKey` (the non-blank of the two), so the merge's output can never contain a credential regardless of which side wins.
- **`updatedAt`** — `min(max(L, R), now)`, clamped by the merging device's own clock. Kept only so a pre-merge peer's whole-document comparison still works; `max()` alone is monotone and therefore permanently poisonable by one device with a wrong date.

**Why `lim.windows` swallows the passes.** `newWindowId` mints `w1`, `w2`… against only the windows already on that device (SettingsScreenTime.kt:216), and `windowsFromJson` falls back to a positional `w$i` (ConfigStore.kt:499). Two parents independently adding a window both produce `w2`. Overlaying pass state onto windows *by id* would land Dad's Homework pass on Mum's School-hours window, and `liveWindows` then stops enforcing school hours entirely — precisely what `TimeWindow.passUntilMillis`'s own doc-comment says must not happen. Taking the whole array from one side dodges it. Window ids are additionally switched to `SecureRandom` hex (the `Profile.newId()` shape) so future merges never see two `w2`s.

**Why `lim.pause` / `kid.pause` are their own units.** A pause is the most frequent write in the config and the least like a setting. Under one `limits` stamp, "Dad granted a bedtime pass at 19:45" silently deletes "Mum paused Emma at 19:40" — and `SessionGuard.isPaused` is documented as not waivable by grants or bedtime passes. Because a pause is a unit, *clearing* it (Resume) is a stamped act with an explicit null value, so an unstamped absence on the winning side can never clear a stamped pause.

**Why the ten loose scalars share one `settings` stamp.** They are set-once-and-forget. Ten stamps in every config buys nothing observable. Consequence, stated plainly: two parents changing two different scalars in the same window lose one group, and the change log says which. Splitting later is additive.

## Model changes

`Whitelist` gains exactly one field, at `app/src/main/java/io/yosemitekids/app/data/Whitelist.kt:299` (after `showVideoAge`):

```kotlin
/**
 * Merge bookkeeping, carried inside config.json under one `sync` key.
 * In the model rather than only on the wire because [ConfigStore.save]
 * re-serializes from this class — a stamp that lived only in the JSON
 * would survive a push and die at the parent's next settings tap.
 *
 * Key alphabet: '|' separates a unit's namespace from its key and appears
 * in no id this file keys on (entry ids are YouTube ids, @handles or
 * user/name path forms; video ids are 11 of [A-Za-z0-9_-]; profile ids are
 * 8 hex; device tokens 32 hex). Safe as a JSON key; must never reach a LAN
 * query string, which the server regex-matches raw and never decodes.
 */
val sync: SyncMeta = SyncMeta.EMPTY
```

```kotlin
data class SyncMeta(
    /** Merge unit → when a parent last changed it. Missing = never / legacy. */
    val at: Map<String, Long> = emptyMap(),
    /** Same key space, for units a parent removed. Never cleared by a re-add. */
    val removed: Map<String, Long> = emptyMap(),
    /** Namespace → newest tombstone stamp dropped by the cap. */
    val floor: Map<String, Long> = emptyMap(),
    /** Newest stamp this document has carried; the monotonic mint floor. */
    val docAt: Long = 0L,
    val log: List<ConfigMerge.Change> = emptyList()
) { companion object { val EMPTY = SyncMeta() } }
```

**The default is safe only because `Settings.buildCurrentConfig` is rewritten to `baseline.copy(...)`.** Today it returns a *fresh* `Whitelist(entries.map{…}, blocked, limits, finalAi, aiAllowed, profiles, scrub(blockedFor), scrub(allowedFor), deviceProfiles.filterValues{…}, masterDeviceToken = …, …)` — positional for the first nine arguments, named thereafter, never mentioning `sync`. With a defaulted field it would compile clean, every existing test would pass, and *every ordinary settings edit would push a sync-less document*: the autosave (Settings.kt:626), `close()` (Settings.kt:634) and the Push button's `saveCurrent` (Settings.kt:737) all serialize that object. The primary path would never exercise the merge at all. Rewriting to `baseline.copy(...)` fixes the class of bug, not just the instance — any future `Whitelist` field is inherited automatically. A test pins it.

`Change` and `Collision` live in `ConfigMerge`:

```kotlin
data class Change(
    /** 8 hex, minted at creation. Logs union by this, not by (at, who, code):
     *  two distinct changes by one author in the same millisecond must not
     *  collapse into one. */
    val id: String,
    /** Ordering stamp — the same monotonic value as the unit's `at`. */
    val at: Long,
    /** The minting device's wall clock, for display only. A device with a
     *  wrong date drags `at` forward monotonically; `shownAt` keeps the feed's
     *  clock times honest instead of rendering 2031. */
    val shownAt: Long,
    /** First 8 hex of the minting device's pairing token. Coalescing compares
     *  this, never `who` — two parents with the same phone model are both
     *  "Pixel 7 Pro" and would silently overwrite each other's log lines. */
    val by: String,
    val who: String,
    /** Stable code, so a later build can re-render the sentence better. */
    val code: String,
    /** Pre-rendered sentence (≤120 chars), so a build that has never heard of
     *  [code] shows a readable line rather than a blank row. */
    val text: String
)
```

Codes: `src.add`, `src.edit`, `src.remove`, `blk.add`, `blk.remove`, `allow.add`, `allow.remove`, `for.block`, `for.allow`, `for.clear`, `kid.add`, `kid.edit`, `kid.remove`, `kid.pin`, `kid.rules`, `kid.windows`, `kid.pause`, `lim.rules`, `lim.windows`, `lim.pause`, `ai`, `settings`, `dev`, `orphan.hidden`.

**Nothing derived from the `ai` object may enter `text`, `Collision.mine`, `Collision.theirs` or `Collision.mineJson`.** `withSecrets` puts the live API key back into every in-memory `Whitelist`, and `sync.log` travels in the pushed payload, lands in `config.json` (an `<include>` in *both* backup XML files), and rides `Backup.export`. `stripSecrets` removes exactly `root.ai.apiKey` and can never be taught to walk free text. So the `ai` line is the fixed string `"AI settings changed"` (or `"AI rules changed — videos will be re-checked"` when `rulesVersion` moved), with no value, and the `ai` collision record stores only the fact. `stripSecrets` additionally drops any `sync.log[]` entry whose `code == "ai"` that somehow carries a value field, belt and braces.

## Serialization rules

- `toJson` writes `sync` only when `w.sync != SyncMeta.EMPTY`. Every existing key keeps its position and shape.
- `toJson` emits `blocked`, `aiAllowed` and each overlay's value array **sorted**. Today they are `JSONArray(set.toList())` in iteration order and only `fingerprint` sorts them; unsorted output makes byte-comparison meaningless and produces spurious `merged != null` results. This changes bytes for existing families but not their fingerprint.
- `fromJson` reads `sync` **entirely outside the throwing path**: `root.optJSONObject("sync")`, an unrecognised `v` treated as absent, and every map entry parsed in its own `runCatching` — the `windowsFromJson` pattern (ConfigStore.kt:485), never the `getString("id")` pattern. `fromJson` is all-or-nothing today; one malformed stamp from a newer build becoming a `400 bad config` would leave a parent staring at a permanently out-of-sync device, and there is no downgrade path (`Updater` only offers a strictly higher `versionCode`).
- `stripSecrets` leaves `sync` byte-for-byte alone, extending `ConfigSecretsTest.kt:47`'s existing guarantee.
- `Backup.SCHEMA` stays at **1**. Nothing about the bundle's shape changed, and bumping it would make every new export unreadable on the other parent's not-yet-upgraded phone.

## LAN wire

`GET /status` gains two keys (additive — `LanClient.fullStatus` uses `getString("hash")`, so *added* fields are safe and *renamed* ones are not):

```kotlin
.put("syncV", ConfigMerge.VERSION)
.put("syncHash", configStore.syncHash())
```

`LanClient.DeviceStatus` becomes `(hash, updatedAt, deviceToken, syncV, syncHash, versionCode, versionName)`. **`syncV` must be `if (json.has("syncV")) json.getInt("syncV") else null`** — `optInt("syncV", 0)` returns a non-null `Int`, which would make `syncV != null` a tautology and route every legacy peer into the merge branch on a five-minute unattended loop.

`POST /config` answers a JSON body:

```json
{"result": "merged", "hash": "3f0a91cc", "syncHash": "b71e04d2", "kept": true}
```

`result` ∈ `same` | `merged` | `replaced`. `kept` = this device holds something the pusher lacks. A legacy phone only ever read `isSuccessful`, so a body it cannot parse costs it nothing; `LanClient.pushConfig` returns `PushResult(ok, hash, syncHash, kept)` and a bare `"saved"` parses to `PushResult(true, null, null, false)`.

`docs/LAN-API.md` gains the two `/status` keys, the `POST /config` response body and the merge-on-receive semantics, per its own "Adding a route" checklist.

## Merge rules

- **R0 — the merge is clock-free.** `ConfigMerge.merge(local: String?, incoming: String): Result` takes NO `now` parameter; the compiler enforces it. Every clock read lives in `ConfigMerge.stamped(...)` or in `ConfigStore.load()`'s existing read-time `scrubLapsedPasses`. Consequences: idempotence and associativity are structural, not test artifacts that hold only while the clock is frozen; a device whose RTC came back wrong after a power cut cannot drop a parent's active pause into the shared document; and `merged != null` genuinely means "the peer told me something new" rather than "a pass expired between two runs".

- **R1 — guard and adopt.** `incoming` unparseable, or `ConfigStore.fromJson(incoming)` throws → return `null`; the route answers `400 bad config`, unchanged contract. `local` null/blank/absent → adopt `incoming` verbatim (fresh install). `local` present but not parseable as JSON → adopt `incoming` and log loudly: `load()` turns an unreadable file into a silent empty `Whitelist` which the next `save()` writes over the file and the next reconcile pushes to every device, and adopting a valid peer document is strictly better than propagating that emptiness.

- **R2 — legacy normalisation carries stamps only, never tombstones.** `normaliseLegacy(doc)`: a document with no `sync` block gets synthesised `at` entries and nothing else. Section units get `at = 0`. Keyed members get **positional rank**: the i-th entry in `entries[]` gets `at["src|<id>"] = i`, the i-th profile gets `at["kid|<id>"] = i`, and set members get `at = 0`. Positional rank (a small integer, ~12 orders of magnitude below any real millis stamp) makes the canonical ordering in R6 reproduce the file's existing order exactly for a never-merged config, without reading any clock. **No `gone` entry is ever synthesised from an absence.** `toJson` mints `updatedAt = System.currentTimeMillis()` at serialization time, so a legacy document's timestamp is its handover moment and cannot express staleness; any tombstone derived from it would let a stale phone, or a fresh tablet whose `rawJson()` falls back to an empty config, permanently delete the entire family configuration. Stated cost: deletes made on a pre-fork build no longer propagate to a fork device. Recoverable by re-deleting on a fork phone; today's alternative clobbers everything.

- **R3 — per-unit resolution.** For every key `k` in `L.at ∪ R.at ∪ L.gone ∪ R.gone`:
```
at   = max(L.at[k]   ?: 0, R.at[k]   ?: 0)
gone = max(L.gone[k] ?: 0, R.gone[k] ?: 0)
if (gone == 0) present = at > 0                       // never removed
else if (at == 0) present = false                     // removed, never re-added
else when (safeState(ns(k))) {
  ABSENT  -> present = at > gone && sawTombstone(k, at)
  PRESENT -> present = !(gone > at && sawAdd(k, gone))
  null    -> present = true                           // scalar unit, always has a value
}

sawTombstone(k, at) = ∃ side s : s.at[k] == at && s.gone[k] in 1 until at
sawAdd(k, gone)     = ∃ side s : s.gone[k] == gone && s.at[k] in 1 until gone
```
Every quantity is a `max` over both sides or an existential over sides, so the rule is symmetric and therefore commutative by construction.

- **R4 — a re-add NEVER clears its tombstone.** `stamped()` writes `at[k] = now` and leaves `gone[k]` in place, so `at > gone` on the re-adding side and `sawTombstone` is satisfied. This is deliberately the opposite of `SavedListStore.add`'s `saveRemoved(removedMap() - video.url)` (SavedListStore.kt:61), whose shape is only safe because `SavedListStore.merge` uses the non-causal `addTs > remTs` rule. Copying half that pair would destroy exactly the evidence causality requires: a parent who mistakenly removed a channel and re-added it would watch it reappear and vanish on a five-minute cycle, forever, and the collision banner's own "Add it back" button would not work. Do not port it.

- **R5 — when a unit resolves ABSENT, drop `at[k]` from the merged blob and keep only `gone[k]`.** Without this the merge is not idempotent: the result would carry both `at > gone`, satisfy `sawTombstone` against itself on the next pass, and resurrect the unit. With it, the result asserts nothing and the tombstone holds under repeated merges in any order.

- **R6 — polarity per namespace, and it is not uniform.** `safeState(ns)`: **PRESENT** for `blk|` and `for|`; **ABSENT** for `src|`, `kid|`, `kid.pin|`, `allow|`, `afor|`, `dev|`; **null** (unremovable scalar) for `lim.*`, `kid.rules|`, `kid.windows|`, `kid.pause|`, `kid.brk|`, `ai`, `settings`, `master`. This implements both halves of PLAN-hub.md:221, which pull in opposite directions. For `blk|`/`for|`, presence wins ties and never requires causality — so Mum re-blocking a video on a phone that never saw Dad's unblock is honoured, not silently discarded, which would let a kid watch something a parent explicitly blocked. Absence (the unblock) requires causality, so Dad unblocking ten minutes after Mum blocked still works normally because his copy carries her block. For `allow|`/`afor|` the polarity inverts: revoking a safe-list override never requires causality, granting one does. Each act is dated and deliberate, so the exchange converges rather than ping-ponging.

- **R7 — canonical ordering.** `entries[]` and `profiles[]` are emitted sorted by `(at ?: 0, id)` ascending. Entry order feeds `fingerprint`'s canonical string, so a device-dependent order ("local first, then the incoming's") means two phones hash identical content differently and read "differs" forever after the first concurrent add. Positional rank from R2 makes this reproduce file order exactly for a never-merged config, which is the only case where `ProfileNamespace.register`'s `if (map.isEmpty()) map[profileIds.first()] = ""` (Profiles.kt:105-111) could hand one kid another kid's legacy unsuffixed watch history, resume points and screen-time budget. Sets (`blocked`, `aiAllowed`, overlay values) are emitted sorted too.

- **R8 — rebuild from the LOCAL JSONObject, splice per unit.** Start from `local`'s parsed root so any root key this build has never heard of survives, then replace only what the units govern. Sub-objects are moved **whole**, never round-tripped through `Whitelist` — `saveRaw`'s guarantee that a field a newer phone knows about survives the round trip holds only while nothing re-serializes from the model, and a model-level merge would be the first thing to break it (and would additionally lose unknown fields *nested inside* known objects, which a root-level `extras` map cannot rescue).

- **R9 — profiles are assembled from up to six sources.** For each surviving `kid|<id>`: the `kid|` winner's `name`/`age`; the `kid.pin|` winner's `pin`; the `kid.rules|` winner's limits scalars; the `kid.windows|` winner's `windows[]` including passes; the `kid.pause|` and `kid.brk|` winners' values; and the `avatar`/`color`/`lookAt` triple chosen by `ProfileLooks.applyLook`'s rule verbatim. `pin` is its own unit because it is a credential riding inside an object moved whole — without it, Dad fixing the spelling of Leo's name on a stale copy silently removes the D-pad code Mum set an hour earlier, and `ProfilePicker` (ProfilePicker.kt:154) then lets Leo's five-year-old sister into his profile, his wider channel list and his longer limits.

- **R10 — limits, and the legacy bedtime projection.** `limits` is assembled from the `lim.rules`, `lim.windows`, `lim.pause` and `lim.brk` winners, then `refreshLegacyBedtime(limits)` recomputes the flat `bedtimeStart`/`bedtimeEnd` pair. It is clock-free and shares one predicate with `limitsToJson` — extract `ConfigStore.legacyBedtimeWindow(windows: List<TimeWindow>): TimeWindow?` (`singleOrNull { days == ALL_DAYS && passUntilMillis == null }`) and call it from both, so they cannot drift. Never regenerate by round-tripping through `limitsFromJson`/`limitsToJson`, which would drop unknown keys inside `limits`. Without this, taking windows from one side and pass state from the other leaves an old TV enforcing a bedtime the phone deleted, or skipping one it set — and the flat pair is all an old TV enforces.

- **R11 — `ai`.** The winning side's object, moved whole. `apiKey` is stripped from both inputs before the merge and returned in `Result.apiKey` as the non-blank of the two — the merge output can never contain a credential. `rulesVersion = max(L, R)`, **plus 1 when the two sides' judging inputs (`rules`, `model`, `baseUrl`, `childAge`, per-kid ages) differ**. Picking one side and keeping its N+1 would hand every device rules it has never screened under a version number it already has cached verdicts for, and those verdicts would be reused on a child-safety judgement. This is the one rule that is not strictly associative — three devices holding three rule texts can pay one extra bump depending on meeting order — but every ordering produces a value ≥ every input and forces a re-screen, and once every device holds the same `ai` object the rules are equal so no further bump occurs and `rulesVersion` converges to `max` within one more sweep. Exclude `rulesVersion` from the strict associativity test and pin the convergence separately. Emit the `ai` change-log line so a family is not surprised by a bill.

- **R12 — `master` converges without a clock.** Newer `at["master"]` wins; on an exact tie the **lexicographically smaller token** wins. "Keep local's if set" is non-commutative — `merge(A,B)` yields tokenA and `merge(B,A)` yields tokenB — so two co-parents who both claimed would never converge and would both keep running the rate-limit-expensive index crawl, which is exactly the failure MainViewModel.kt:236 documents as harmless *because* whole-file newest-wins converges it. The master claim itself is written through `ConfigStore.update {}` and stamps `at["master"]`, but mints no log line — it is not a parent's action.

- **R13 — cross-section coherence, run after every merge and shared with the settings form.** `ConfigMerge.scrubReferences(root: JSONObject)` drops references to kids no longer in the config, across `entries[].profiles`, `blockedFor`, `allowedFor` and `deviceProfiles`. `Settings.buildCurrentConfig` (Settings.kt:557-565) is rewritten to call the same function, and a test asserts the two produce identical output for identical input so they cannot drift. **Critical correction to the existing rule:** an entry whose `profileIds` becomes empty *by scrubbing* must NOT fall back to the current `visibleTo` meaning of "everyone" (Whitelist.kt:47) — that fails open, so Dad's Teen Gaming entry restricted to a removed 14-year-old becomes visible to the six-year-old. The scrub substitutes the sentinel `PROFILE_NONE = "-"` (never a valid 8-hex profile id), so `visibleTo` returns false for every kid; a `orphan.hidden` log line and a Settings row tell the parent "Teen Gaming has no kids assigned — hidden until you assign one". Old builds parsing `profiles: ["-"]` reach the same conclusion, so it degrades safely. `scrubReferences` also resolves the one incoherent state the merge can produce — the same `videoId|kidId` in both `blockedFor` and `allowedFor` — by keeping the newer stamp, and on an **exact tie keeping the block and dropping the allow**, never falling through to a generic tie-break that would decide a child-safety question by string comparison.

- **R14 — tombstones are permanent, capped, floored.** No TTL. Age-based pruning is clock-dependent: two devices on different clocks prune different sets, their `syncHash`es diverge and they rewrite and re-exchange forever. `MAX_TOMBSTONES = 1000`, newest-first (mirroring `SavedListStore.saveRemoved`'s `sortedByDescending { it.value }.take(200)` shape at a larger bound). When a tombstone is dropped, `floor[ns] = max(floor[ns], droppedStamp)`. A row only one side holds, whose `at` is **non-zero** and strictly less than `floor[ns]`, is not admitted. **`at == 0` is exempt** — 'no evidence either way' is not something the floor is entitled to resolve as a delete, and without the exemption a restored backup or a never-edited upgraded device loses its entire content on its first merge with any device that has ever evicted a tombstone. Stamps in `at` are pruned only for units whose subject is no longer in the document, so `at` cannot outgrow the config and no stamp is ever degraded (degrading a `blk|` stamp to 0 would make the family's oldest blocks losable to any stale tombstone).

- **R15 — the log merges by id.** Union `L.log ∪ R.log` by `Change.id`, sort by `at`, keep the last `MAX_LOG = 30`. Never in `fingerprint` and never in `syncHash` — a log line is not state, and two devices holding the same rules with different log tails must read as in sync. Stated consequence: a log line only propagates when it rides along with a change that moved `hash` or `syncHash`, which is fine, because a log line exists *because* something changed.

- **R16 — results.** `merged` is `root.toString(2)`, or `null` when the result's canonical form equals `local`'s. `peerBehind` = the result differs from `incoming` in content or in `at`/`gone`/`floor`. `collisions` = units where `la > 0 && ra > 0 && la != ra && the values differ && the loser was local`, further filtered by the *local mint record* (see the guards) rather than by stamp arithmetic. `learned` = log lines the local side did not have. `apiKey` = the out-of-band key.

- **R17 — `stamped(previous, base, next, now, who, by)` is a 3-way diff.** `previous` = the document **on disk, read inside FILE_LOCK at write time**; `base` = what the editor opened with (Settings already tracks `baseline`); `next` = the edit. Rules: a unit differing between `base` and `next` is stamped and logged. A unit present in `previous` but absent from `base` was merged in underneath an open form and is **carried forward, never tombstoned** — without this a co-parent's channel that landed via `POST /config` (which writes from a LAN worker thread, on a pool of up to 8) becomes a durable, propagating tombstone at the form's next 1.5 s autosave. The sync blob is always sourced from `previous.sync`, never from `next.sync` (which is form state and has no opinion about bookkeeping) and never from `base.sync`.

- **R18 — monotonic mint with an honest display clock.** `now = max(System.currentTimeMillis(), previous.sync.docAt + 1)`. A TV that boots after a power cut with a bad RTC otherwise wins every unit it touches until its clock is corrected; this guarantees a parent's fresh edit always beats what the document already holds. The known cost is contagion — a poisoned `docAt` drags every future mint — so `Change` carries both `at` (the minted ordering stamp) and `shownAt` (the minting device's raw wall clock), and the activity feed renders `shownAt`. When `previous.sync.docAt > wallClock + CLOCK_SKEW_MAX (7 days)`, `stamped` records a `SyncNotices` entry naming the offending device from the log ("Living Room TV has the wrong date — it says 3 Sep 2031") and still mints `docAt + 1`, so the parent can always win and can always see why.

- **R19 — log coalescing.** `stamped()` replaces the last log line instead of appending when it has the same `by` **and** the same `code` and `0 <= now - last.at <= LOG_COALESCE_MS (5 min)`. Comparing `by` (this device's own pairing-token prefix) rather than `who` matters: two parents with the same phone model are both `android.os.Build.MODEL` = "Pixel 7 Pro", and coalescing on the display name would have Mum's line silently replace Dad's — destroying the attribution the whole feature exists to provide. The non-negative clamp stops a future-dated peer line from being swallowed by an unrelated local edit.

## Invariants

Each maps to a test or a build guard.

1. `ConfigStore.fingerprint` is byte-identical for `w` and `w.copy(sync = <any populated SyncMeta>)`. One assertion protects ~15 existing fingerprint-equality assertions and the whole fleet from a spurious re-push at upgrade. → `fingerprintIgnoresTheSyncBlobEntirely`
2. `ConfigMerge.merge` reads no clock. Enforced structurally: the function takes no `now`/`Clock` parameter and `ConfigMerge.kt` contains no reference to `System.currentTimeMillis` outside `stamped`. → check.ps1 grep guard + `mergeSignatureTakesNoClock`
3. Commutative: for any A, B, `fingerprint(fromJson(merge(A,B)))` == `fingerprint(fromJson(merge(B,A)))` and `syncHash(merge(A,B))` == `syncHash(merge(B,A))`.
4. Associative: `merge(merge(a,b),c)` == `merge(a,merge(b,c))`, excluding `ai.rulesVersion`, which instead converges to `max` within one further merge.
5. Idempotent: `merge(merge(A,B).merged, B).merged == null`. Follows from R0 (clock-free) and R5 (drop `at` on an absent unit).
6. Absence never deletes. Only a real `gone` entry removes anything, in either direction, from either side, legacy or stamped.
7. A tombstone is never cleared. `gone[k]` is monotone per key under merge (`max`) and under `stamped` (a re-add writes `at`, never removes `gone`); it leaves the document only via the R14 cap, which raises `floor[ns]` in the same act.
8. No unstamped row is ever refused by the floor. `at == 0` means no evidence, not a delete.
9. The merge output never contains `ai.apiKey`, and neither does anything derived from the `ai` object in `sync.log` or in a collision record.
10. Every write to config.json goes through one private `ConfigStore.commit(json)` that stashes a non-blank incoming key in `SecretStore` and then writes `stripSecrets(json)`. No second write path can bypass it.
11. `FILE_LOCK` is held across read-merge-write in `mergeIncoming` and across read-modify-write in `update {}`; the Keystore round trip happens **outside** it.
12. An absence on the winning side never clears a stamped value. Clearing a pause, a pin or a limits field is itself a stamped act with an explicit null.
13. A merged document's `updatedAt` never exceeds the merging device's own wall clock.
14. `entries[]` and `profiles[]` order is a pure function of the merged sync blob, identical on every device, and reproduces file order for a never-merged config.
15. `profiles.first()` is stable for any device that has already called `ProfileNamespace.register` (the map is non-empty, so `getOrPut` pins it), and deterministic family-wide for one that has not.
16. The flat `bedtimeStart`/`bedtimeEnd` pair in a merged document is always consistent with that document's merged `windows[]`, computed through the single shared `legacyBedtimeWindow` predicate.
17. A config that has never been merged and never edited after upgrading serializes with no `sync` key and is byte-identical to what the pre-merge build wrote (modulo the newly-sorted `blocked`/`aiAllowed` arrays, which do not move the fingerprint).
18. A malformed or future-versioned `sync` block never rejects a push and never yields an empty `Whitelist`.
19. `ConfigStore.load()` never returns an empty `Whitelist` for a file that exists but failed to parse; it returns the last-known-good and sets `degraded`.
20. A legacy peer (`/status` carries no `syncV`) is a push-only destination: the reconcile never fetches from it, Combine is not offered for it, and no tombstone is ever derived from a document it produced.
21. Kid devices render no merge UI: no banner, no collision, no activity feed, no clock-skew notice.

## Milestones

### M1 — Testable ConfigStore, honest peer version, structural diff before Pull

The smallest thing that pays for itself with no format change at all, and it unblocks every test the later milestones need.

(a) Give `ConfigStore` a **File-taking primary constructor** with a `Context` secondary, following the `QueueStore(file)` / `ScreeningStore(file)` / `ChannelIndex(dir)` convention already in the repo. No existing test constructs `ConfigStore` — because it takes a `Context` and there is no Robolectric (only junit + org.json in app/build.gradle.kts:189) — which is why `save`/`saveRaw`/`load`/`updatedAt` have never had a test. Without this, the concurrency test that guards the read-modify-write in M3 cannot be written at all.

(b) `load()` stops laundering a parse failure into an empty config. Add `@Volatile private var lastGood: Whitelist?` and `val degraded: Boolean`. When the file exists, is non-empty, and fails to parse, return `lastGood` (or empty on a cold start), log loudly, and set `degraded`. Gate the two callers that *invent* content on it: `Settings`' kid-migration (Settings.kt:435-462, which today would mint a kid, overwrite the real file and push the emptiness to every device) and `syncConfigState`'s master claim.

(c) Make the kid migration's id deterministic: `id = ConfigStore.fingerprint(c)` instead of `Profile.newId()`. Two phones migrating the same kid-less config independently produce the *same* kid, which a union merge later cannot otherwise reconcile — today's whole-file LWW hides this, the merge would not.

(d) Fix `ProfileNamespace.register`: `ConfigStore.registered()` builds a fresh `ProfileNamespace` on every call, so `@Synchronized` locks a throwaway instance and provides no mutual exclusion. Move the lock to a companion-level object and use `commit()` for that one write.

(e) Wire the peer's build through: `LanClient.DeviceStatus` gains `versionCode`/`versionName` (already sent at Pairing.kt:674-675 and thrown away at :950). Surface it in `DeviceSync.Reachable` and on the devices row.

(f) `ConfigMerge.kt` is created with only `describe(a: Whitelist, b: Whitelist): List<Change>` — a pure structural diff. The Pull dialog renders it above today's replace wording, so a parent sees what they are about to overwrite. This is PLAN-hub's step 1, it forces the change-description vocabulary everything later reuses, and it needs no format change.

(g) Exclude `SingleChannelProbeTest` from the offline gate in `scripts/check.ps1`, `scripts/check.sh` and `.github/workflows/build.yml`. It hits live YouTube unguarded (no `Assume`, no `runCatching`) and only `ExtractorSmokeTest` is currently excluded, so every later merge-test run can go red for a bot wall.

**Files:** `app/src/main/java/io/yosemitekids/app/data/ConfigStore.kt`, `app/src/main/java/io/yosemitekids/app/data/ConfigMerge.kt`, `app/src/main/java/io/yosemitekids/app/data/Profiles.kt`, `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`, `app/src/main/java/io/yosemitekids/app/ui/Settings.kt`, `app/src/main/java/io/yosemitekids/app/ui/SettingsDevices.kt`, `app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt`, `app/src/test/java/io/yosemitekids/app/ConfigStoreFileTest.kt`, `app/src/test/java/io/yosemitekids/app/ConfigDiffTest.kt`, `scripts/check.ps1`, `scripts/check.sh`, `.github/workflows/build.yml`

**Tests:** ConfigStoreFileTest.aFileBackedStoreRoundTripsSaveAndLoad; ConfigStoreFileTest.anUnparseableFileDoesNotLoadAsAnEmptyConfig; ConfigStoreFileTest.aDegradedStoreRefusesToMintAKid; ConfigStoreFileTest.twoPhonesMigratingTheSameKidlessConfigProduceOneKid; ConfigDiffTest.describeNamesAddsRemovesAndEdits; ConfigDiffTest.describeIsEmptyForIdenticalConfigs; ConfigDiffTest.describeNeverRendersTheApiKeyOrBaseUrl

**Done when:** `scripts/check.ps1` is green; a `ConfigStore` can be built from a `TemporaryFolder` file in a JVM test; the Pull dialog lists what will be replaced; a device on an older build is labelled as such on the devices screen; the offline gate no longer touches the network.

### M2 — The sync blob — stamps, tombstones, change log, and the activity feed

The format lands and starts travelling. **Nothing merges yet**, so this milestone cannot lose data; its standalone value is that "why did the TV change?" becomes answerable, which today it is not.

(a) `SyncMeta` and `ConfigMerge.Change` in `ConfigMerge.kt`; `Whitelist.sync: SyncMeta = SyncMeta.EMPTY` as the last field.

(b) **Rewrite `Settings.buildCurrentConfig` to return `baseline.copy(...)` instead of a fresh `Whitelist(...)`.** This is the load-bearing change. Today it constructs positionally for nine arguments and would silently drop `sync`, so the autosave, `close()` and Push would all serialize a sync-less document and the merge would never run on the primary path. `baseline.copy` fixes the class of bug — any future `Whitelist` field is inherited too.

(c) `toJson` writes `sync` when non-empty and emits `blocked`/`aiAllowed`/overlay values sorted. `fromJson` reads `sync` outside the throwing path, per entry in `runCatching`, unrecognised `v` treated as absent. `stripSecrets` leaves `sync` alone and additionally drops any `sync.log[]` entry with `code == "ai"` carrying a value field.

(d) `ConfigMerge.stamped(previous, base, next, now, who, by)` — the 3-way diff, monotonic mint, tombstones on removal, re-add keeps the tombstone, `scrubReferences` with the `PROFILE_NONE` sentinel, log coalescing by `by`+`code`, `prune` with the 1000 cap and `floor`.

(e) `ConfigStore.save(w, base: Whitelist? = null, who: String = "", by: String = "")` reads `previous` inside `FILE_LOCK`, stamps, writes through the new private `commit(json)`, and **returns the stamped bytes**. `pushAll`, `saveCurrent` and the kid migration push *those* bytes, so there is exactly one wire shape for a config.

(f) `ConfigStore.update(block: (Whitelist) -> Whitelist)` — a lock-held read-modify-write primitive. Convert the three unlocked callers: the master claim (MainViewModel.kt:238-241), the look adoption (MainActivity.kt:337-340) and the parent-side kid restyle (MainActivity.kt:602-607). These currently feed `save()` a `Whitelist` read minutes earlier; under the new stamper an unlocked one would see a merged-in channel as a fresh *add* and clear its tombstone, resurrecting a deleted channel with no parent action anywhere.

(g) `ConfigMerge.syncHash(sync)` over a **canonical sorted string** (`at`, `gone`, `floor`, tagged and `\n`-joined, log and `docAt` excluded), SHA-256 first four bytes. Never `JSONObject.toString()`: Android's `JSONObject` is `LinkedHashMap`-backed and insertion-ordered, so two devices holding the same map in different insertion orders would hash differently and push at each other forever — and the JVM tests' `org.json` uses a plain `HashMap`, so no test would catch it.

(h) `ConfigStore.syncHash()` (cheap raw peek, same shape as `updatedAt()`); `/status` gains `syncV` + `syncHash`; `DeviceStatus.syncV = if (json.has("syncV")) json.getInt("syncV") else null`.

(i) `PairingStore.myName()` — a stored, parent-editable phone name defaulting to `Build.MODEL`, with a "This phone is called…" field in Settings, plus `by` = the first 8 hex of `deviceToken()`.

(j) `ui/SyncActivityScreen.kt` — the Recent-changes feed, same full-screen takeover pattern as `WeeklyDigestScreen`, rendering `shownAt`. A `Recent changes  ·  Dad's phone added SciShow Kids, 2h ago  ›` row in Settings. No badge, no dot.

(k) Devices list: thread `localSyncHash` alongside `localHash`; the in-sync label and the Push/Pull button gate use both, and Push stays available for a peer with no `syncV` regardless of hash equality. Without this, a fork TV holding a tombstone this phone has never seen shows a green "in sync ✓" and hides both buttons, leaving the parent no control at all.

**Files:** `app/src/main/java/io/yosemitekids/app/data/ConfigMerge.kt`, `app/src/main/java/io/yosemitekids/app/data/ConfigStore.kt`, `app/src/main/java/io/yosemitekids/app/data/Whitelist.kt`, `app/src/main/java/io/yosemitekids/app/data/Profiles.kt`, `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`, `app/src/main/java/io/yosemitekids/app/ui/Settings.kt`, `app/src/main/java/io/yosemitekids/app/ui/SettingsDevices.kt`, `app/src/main/java/io/yosemitekids/app/ui/SettingsScreenTime.kt`, `app/src/main/java/io/yosemitekids/app/ui/SyncActivityScreen.kt`, `app/src/main/java/io/yosemitekids/app/ui/MainActivity.kt`, `app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt`, `app/src/test/java/io/yosemitekids/app/ConfigStampTest.kt`, `app/src/test/java/io/yosemitekids/app/ConfigSyncFormatTest.kt`, `app/src/test/java/io/yosemitekids/app/ConfigSecretsTest.kt`

**Tests:** ConfigSyncFormatTest.fingerprintIgnoresTheSyncBlobEntirely; ConfigSyncFormatTest.aConfigWithNoSyncWritesNoSyncKey; ConfigSyncFormatTest.syncSurvivesTheJsonRoundTripAndStripSecrets; ConfigSyncFormatTest.aMalformedSyncBlobStillLoadsEveryChannel; ConfigSyncFormatTest.anUnknownSyncVersionReadsAsNoSyncBlock; ConfigSyncFormatTest.syncHashIgnoresKeyInsertionOrder; ConfigSyncFormatTest.syncHashIgnoresTheLogButNotTombstones; ConfigStampTest.onlyWhatChangedIsStamped; ConfigStampTest.stampNeverGoesBackwardsFromDocAt; ConfigStampTest.aRemovalWritesATombstoneAndDropsTheStamp; ConfigStampTest.aReAddKeepsItsTombstoneAndOutstampsIt; ConfigStampTest.stampedDoesNotTombstoneWhatItNeverSaw; ConfigStampTest.aBurstCoalescesIntoOneLogLine; ConfigStampTest.twoDevicesWithTheSameModelNameDoNotCoalesce; ConfigStampTest.aFutureDocAtRecordsAClockNoticeAndStillMintsHigher; ConfigStampTest.scrubbingAnEntrysLastKidHidesItRatherThanWideningIt; ConfigStampTest.settingsScrubAndMergeScrubAgreeOnTheSameInput; ConfigSecretsTest.aSyncLogNeverCarriesTheApiKeyThroughExport

**Done when:** A settings edit writes stamps and a log line; a save-then-push carries the blob (asserted, not assumed); `/status` advertises `syncV`/`syncHash`; the Recent-changes feed lists real edits with real names and honest clock times; `fingerprint` is provably unmoved; check.ps1 green.

### M3 — Merge on receive — POST /config stops replacing

The bug the feature exists for is fixed on the receiving side: two parents pushing to the same TV both survive.

(a) `ConfigMerge.merge(local: String?, incoming: String): Result` — the whole algorithm from the merge rules. Pure, Context-free, **no clock parameter**. Plus `normaliseLegacy`, `scrubReferences`, `refreshLegacyBedtime`, `prune`, and `ConfigStore.legacyBedtimeWindow` extracted and shared with `limitsToJson`.

(b) `ConfigStore.mergeIncoming(json: String, from: String?): MergeOutcome?` — holds `FILE_LOCK` across read (`rawFile()`, the raw bytes, **not** `load()`: `load()` scrubs lapsed passes and lays pending kid looks over the profiles, so merging from it would read a clock tick and an un-adopted restyle as parent edits), merge, and write via `commit()`. Returns the `before`/`after` `Whitelist` pair captured **inside** the lock, so `onConfigApplied` cannot report a transition another worker caused. The Keystore round trip for a carried-over API key happens after the lock is released.

(c) `POST /config` calls `mergeIncoming`, attributes via `pairingStore.approvedPhones()[reqToken]` (free, no wire change), and answers the JSON body. `LanClient.pushConfig` returns `PushResult`. `saveRaw` keeps its body and its two remaining callers.

(d) `Backup.restore` stays a whole-file replace — a restore genuinely means replace — but **splices the device's pre-restore `gone` map back in** (`max` per key). A restore is evidence about content, never evidence that a deletion was undone; without this, restoring a six-month-old bundle silently un-deletes everything removed since.

(e) The pairing-adopt path (MainActivity.kt:443-451) tightens its "blank" test to require an empty `sync` blob as well as empty content — a config that has only ever had things *removed* from it looks identical to one that has never had anything added — and routes the non-blank case through `mergeIncoming`.

(f) `rawJson()` stops laundering: return the stored bytes with `ai.apiKey` put back when `aiInUse`, plus the same two read-time transforms `load()` applies, done surgically on the `JSONObject` (drop lapsed `passUntil`/`pausedUntil`/`breakPassUntil`, splice the `ProfileLooks` overlay into `profiles[]`). Both transforms are required or `/status`'s hash and `/config`'s body stop describing the same document, and a kid's restyle on a TV makes that TV permanently, unclearably out of sync with any peer that cannot use `/looks`.

**Files:** `app/src/main/java/io/yosemitekids/app/data/ConfigMerge.kt`, `app/src/main/java/io/yosemitekids/app/data/ConfigStore.kt`, `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`, `app/src/main/java/io/yosemitekids/app/data/Backup.kt`, `app/src/main/java/io/yosemitekids/app/ui/MainActivity.kt`, `app/src/test/java/io/yosemitekids/app/ConfigMergeTest.kt`, `app/src/test/java/io/yosemitekids/app/ConfigMergeCompatTest.kt`, `app/src/test/java/io/yosemitekids/app/ConfigMergeConcurrencyTest.kt`, `app/src/test/java/io/yosemitekids/app/KidNoticesTest.kt`

**Tests:** ConfigMergeTest (the full matrix below); ConfigMergeCompatTest (the full matrix below); ConfigMergeConcurrencyTest.twoThreadsLandingDisjointPushesLoseNothing; ConfigMergeConcurrencyTest.aMergeUnderAnUnlockedUpdateDoesNotResurrectATombstone; KidNoticesTest.aStampOnlyChangeSaysNothing

**Done when:** Two phones pushing disjoint edits to one TV both land; a delete pushed to a TV survives a later push from a phone that never saw it; a legacy push deletes nothing; no config.json anywhere contains an apiKey; the concurrency test passes 50 iterations on two real threads; check.ps1 green.

### M4 — The reconcile becomes two-way

The dead third branch ("differs but its copy is newer — leaving for Push/Pull") becomes the feature. Until now the merge only ran when someone pushed; now the TV genuinely acts as the rendezvous.

(a) `syncConfigState`'s `when` becomes four-way: hashes-and-syncHashes equal → nothing; peer advertises `syncV` → `fetchConfig` → `mergeIncoming` → push back when `merged != null || peerBehind`; peer is legacy and `remoteAt < localAt` → push the whole config (unchanged); else → the existing legacy log line. **The merge arm requires `status.syncV != null && status.syncHash != null`** — a legacy peer is push-only, so no document produced by `toJson(load())` on an old build can ever be treated as a merge source.

(b) **Recompute `localHash`, `localSyncHash` and `localAt` at the top of each loop iteration.** They are hoisted above `devices.forEach` today (MainViewModel.kt:262-264), which is correct only because nothing in the loop writes. With two TVs, iteration 1's merge would land Dad's channel and iteration 2 would then compare TV2 against the *pre-merge* hash, take the do-nothing arm, and leave TV2 stale while the UI reported it in sync.

(c) No `settingsOpen` guard. The 3-way `stamped()` from M2 is the correctness fix and it covers `POST /config` too, which a volatile flag never could; a guard would additionally stop reconciliation for as long as a parent leaves Settings open.

(d) Push reports the truth. Three outcomes replace today's guess: `"Pushed ✓"`; `"Pushed ✓ — Living Room TV also had a change of Dad's, and both are in now."` when the response says `kept` (followed immediately by a fetch-merge and `configEpoch++` so the form reloads holding the union); and `"Living Room TV is on an older version (0.8.1). Update it and this clears."` said **only** when `syncV` is genuinely absent.

(e) The reciprocal caveat: for a paired peer below the merge floor, the fork phone's devices row says so — an old phone will report the *fork* device as out of date, and that message is wrong but cannot be fixed on the build that emits it.

(f) A "no paired device has answered in N days" line on the Devices page. Parent phones never pair with each other (only `TvSettingsScreen` and `KidDeviceScreen` render a QR), so a kid device that is on and reachable *is* the rendezvous, and a household whose TV is unplugged for a fortnight gets no reconciliation at all. Say so rather than showing stale green tiles.

**Files:** `app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt`, `app/src/main/java/io/yosemitekids/app/ui/SettingsDevices.kt`, `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`, `app/src/test/java/io/yosemitekids/app/SyncDecisionTest.kt`

**Tests:** SyncDecisionTest.equalHashesAndSyncHashesDoNothing; SyncDecisionTest.aPeerWithoutSyncVIsNeverAMergeSource; SyncDecisionTest.aPeerWithoutSyncVStillGetsAWholeConfigPushWhenOlder; SyncDecisionTest.contentEqualButBlobsDifferMergesOnceAndThenSettles; SyncDecisionTest.aSecondDeviceIsComparedAgainstThePostMergeHash

**Done when:** Dad pushes to the TV, Mum's phone sweeps and comes back holding both edits without anyone pressing anything; the devices list never lies about which side is out of date; a household with only legacy peers behaves exactly as it does today.

### M5 — Telling the parent — the banner, Combine, and the clock notice

The only user-visible surfaces. Every one is either something the parent pressed or something they find when they next open Settings; nothing interrupts.

(a) `data/SyncNotices.kt` — per-phone collision records in SharedPreferences, holding `code`, `subject`, `mine`, `mineAt`, `mineJson` (the local before-value, so "Put 45 back" is a real one-tap action), `theirs`, `theirsAt`, `theirWho`, and a dismissed flag. **Deliberately not in the config and deliberately not added to `backup_rules.xml` / `data_extraction_rules.xml`** — it is one phone's private UI state, and putting "you were overruled" in the config would push it to the other parent's phone. Nothing else new is persisted, so the three include lists need no edit at all.

(b) `SyncNotices` also records the **local mint moment** of each stamp this device wrote. Collision freshness is judged against that, not against `abs(la - ra)`: stamp arithmetic across two clocks suppresses exactly the collisions a wrong clock causes, which is when a parent most needs telling. Suppressed when the local side won, when the local mint is older than `COLLISION_FRESH_MS = 36h`, when already dismissed, or on a kid device.

(c) The Settings banner: non-modal, dismissible, one at a time. Headlined with the **current state** ("Emma's screen time is now 30 minutes"), body naming both sides in clock time ("You set 45 minutes at 6:12pm. Dad's phone set 30 minutes at 6:31pm"), action restating the value ("Put 45 back", never "Undo"). The author is resolved from the incoming change log by matching `code` and a stamp near the winner's, so it says "Dad's phone" and not "Living Room TV" — the TV was only holding it. If the log does not cover it, the wording softens to "Living Room TV already had 30 minutes". Vocabulary that never appears: *conflict, merge, section, sync, stamp, revision, tombstone, hash, resolve, overwritten, discarded.*

(d) Pull becomes **Combine** (primary) + **Replace** (secondary, behind its own confirm, keeping today's exact wording for the genuine reinstall-recovery case, still `saveRaw`). Combine **flushes the settings debounce first** (`save(current, base = baseline)`) so an in-flight edit is on disk and becomes one side of the merge — otherwise `onConfigReplaced`'s `configEpoch++` reload silently discards it while the dialog says "Nothing on this phone will be lost." Combine renders the peer's log lines this phone lacks (that is where the *who* column comes from; a structural diff cannot produce it) and falls back to `ConfigMerge.describe` from M1. **Combine is not offered at all for a peer with no `syncV`.**

(e) The clock-skew notice from R18: a plain Settings row, "Living Room TV has the wrong date — it says 3 Sep 2031. Fix it there and these settle." A wrong date is the one thing a parent can actually go and fix.

(f) Kid devices render none of it.

**Files:** `app/src/main/java/io/yosemitekids/app/data/SyncNotices.kt`, `app/src/main/java/io/yosemitekids/app/data/ConfigMerge.kt`, `app/src/main/java/io/yosemitekids/app/ui/Settings.kt`, `app/src/main/java/io/yosemitekids/app/ui/SettingsDevices.kt`, `app/src/main/java/io/yosemitekids/app/ui/SyncActivityScreen.kt`, `app/src/test/java/io/yosemitekids/app/ConfigCollisionTest.kt`

**Tests:** ConfigCollisionTest.aCollisionIsRecordedOnlyWhenTheLocalSideLost; ConfigCollisionTest.anEditOlderThanThirtySixHoursIsNotSurfaced; ConfigCollisionTest.freshnessUsesTheLocalMintNotTheStampGap; ConfigCollisionTest.theAuthorComesFromTheIncomingLogNotThePeerName; ConfigCollisionTest.anAiCollisionRecordsNoValue; ConfigCollisionTest.previewReturnsOnlyTheLogLinesTheLocalSideLacks; ConfigCollisionTest.previewFallsBackToAStructuralDiffAgainstALegacyPeer

**Done when:** A parent whose edit lost sees exactly one dismissible banner the next time they open Settings, with a working Put-it-back; Combine shows a real diff with names; Replace still exists and still says what it does; a kid device shows nothing.

### M6 — Docs, skill, upstream log, release

(a) `docs/LAN-API.md`: the two `/status` keys, the `POST /config` response body, merge-on-receive semantics, and the rule that a peer without `syncV` is push-only.

(b) `docs/ARCHITECTURE.md`: a config-sync section — the unit table, tombstone rules, where to change what.

(c) `docs/FORK-NOTES.md`: closes backlog item 10; records the two honest caveats (deletes do not cross a legacy hop, and a household needs one reachable kid device as the rendezvous because parent phones never pair with each other).

(d) `.claude/skills/yosemite-kids-sync/SKILL.md` — see the skill outline.

(e) `docs/UPSTREAM-LOG.md`: record the port-cost decision. `ConfigStore.kt`, `Pairing.kt`, `MainViewModel.kt` and `Settings.kt` are the four heaviest fork-modified files and every upstream commit touching them is a hand port; putting ~450 lines in a new `ConfigMerge.kt` keeps the diff in those four small (a route body, a `when` arm, a save signature, a `.copy`), but the tax is permanent and should be visible to whoever runs the weekly `/yosemite-kids-upstream`.

(f) Bump `versionCode` 39 → 40 and `versionName` to `0.10.0-fork` in `app/build.gradle.kts`, run `/yosemite-kids-upstream` as release step 0, `scripts/check.ps1`, then the normal release flow. Note in the release entry that `version.json` still points at upstream v0.7.8 / versionCode 28 and `UPDATE_MANIFEST_URL` defaults to empty, so self-update is inert — repointing it is a prerequisite for treating a half-upgraded fleet as temporary rather than permanent, and there is no downgrade path if a format decision proves wrong.

**Files:** `docs/LAN-API.md`, `docs/ARCHITECTURE.md`, `docs/FORK-NOTES.md`, `docs/UPSTREAM-LOG.md`, `.claude/skills/yosemite-kids-sync/SKILL.md`, `app/build.gradle.kts`, `version.json`

**Tests:** scripts/check.ps1 (full); manual: two-device emulator + Chromecast pass through scripts/emu.ps1

**Done when:** Docs describe what shipped; the skill exists and states the invariants; UPSTREAM-LOG carries the decision; a signed release APK is built and the release is cut.

## Test matrix

Every scenario below must have a test. 66 in total.

- ConfigMergeTest.disjointEditsBothSurvive — local adds a channel (t1), incoming shortens the family bedtime (t2); both present, collisions empty. The bug the feature exists for, so it is the first test.
- ConfigMergeTest.mergingTwiceEqualsMergingOnce — merge(merge(A,B).merged, B).merged is null.
- ConfigMergeTest.disjointMergeIsCommutative — fingerprint and syncHash equal from both directions, including entry and profile order.
- ConfigMergeTest.mergeIsAssociativeOverThreeDevices — concurrent adds, a delete and a scalar collision; merge(merge(a,b),c) == merge(a,merge(b,c)), excluding ai.rulesVersion.
- ConfigMergeTest.mergeSignatureTakesNoClock — reflection over ConfigMerge::merge asserts no Long/Clock parameter, so a later contributor cannot slip a clock read into the algorithm.
- ConfigMergeTest.equalStampsWithDifferentContentConvergeFromBothDirections — lexicographic tie-break on compact JSON; both sides land on the same value.
- ConfigMergeTest.aTombstoneBeatsAnOlderAdd — local holds UCx at t1, incoming has gone=t2 and no entry; absent, tombstone survives, and re-merging the original local keeps it absent.
- ConfigMergeTest.aDeliberateReAddBeatsATombstone — gone=t2 on one side; the other has at=t3 AND gone=t2 (the re-adder kept it); the entry survives.
- ConfigMergeTest.aReAddSurvivesAPeerThatStillHoldsTheTombstone — three documents (re-adder, tombstone-holder, third party that saw neither), all six orders.
- ConfigMergeTest.aReAddIsIdempotentUnderRepeatedMerges — the entry does not vanish on the second or third sweep.
- ConfigMergeTest.aRelabelOnACopyThatNeverSawTheDeleteDoesNotResurrect — delete-wins-over-edit, PLAN-hub.md:221.
- ConfigMergeTest.absenceAloneNeverDeletes — local holds three channels with stamps, incoming holds one with a newer stamp and no tombstones; all three survive.
- ConfigMergeTest.anUnblockIsATombstone — blocked at t1, gone['blk|vid']=t2 with causality; unblocked, and merging the pre-unblock local back does not re-block.
- ConfigMergeTest.aReBlockAfterAnUnblockAlwaysWins — the re-blocker never saw the unblock; the block holds. Restrictive polarity.
- ConfigMergeTest.anUnblockIsRefusedFromASideThatNeverHeldTheBlock — causality on the unsafe direction only.
- ConfigMergeTest.equalStampsOnABlockAndAnUnblockKeepTheBlock — and the mirror: equal stamps on an allow and a revoke keep the revoke.
- ConfigMergeTest.revokingASafeListOverrideIsNotUndoneByAStaleGrant — allow| polarity inverted from blk|.
- ConfigMergeTest.pauseAndRulesMergeIndependently — local sets Emma's weekday sessions (kid.rules|emma at t1), incoming pauses Emma (kid.pause|emma at t2); both land.
- ConfigMergeTest.aBedtimePassDoesNotLiftAFamilyPause — both arrival orders; the pause survives.
- ConfigMergeTest.anUnstampedAbsenceNeverClearsAStampedPause — the winning side simply has no pausedUntil and no stamp for it.
- ConfigMergeTest.aMergeNeverChangesPausedUntil — merge is clock-free, so no now value can drop a live pause.
- ConfigMergeTest.aMergeNeverProducesAnUncappedKidFromTwoCappedSides — post-condition: if both inputs yield a non-null dailyBudgetMs for a kid, the output does too.
- ConfigMergeTest.windowPassesRideWithTheirOwnWindowArray — two devices that each minted a window with the same positional id do not cross-apply passes.
- ConfigMergeTest.theLegacyBedtimePairIsRecomputed — asserted against the flat bedtimeStart/bedtimeEnd keys directly, including their absence while a pass is active; an old TV enforces those and nothing else.
- ConfigMergeTest.legacyBedtimePredicateIsSharedWithLimitsToJson — the extracted legacyBedtimeWindow drives both.
- ConfigMergeTest.profileOrderIsCanonicalAndIdenticalOnBothSides — local [emma, ravi], incoming [ravi, emma, noor]; both directions produce the same list.
- ConfigMergeTest.aNeverMergedConfigKeepsItsFileOrder — positional rank from normaliseLegacy reproduces entries[] and profiles[] order exactly.
- ConfigMergeTest.aKidsLookIsNotClobberedByARename — kid| at t2 with the new name, incoming carries a newer lookAt; result has both.
- ConfigMergeTest.renamingAKidDoesNotRemoveTheirCode — kid.pin| is its own unit.
- ConfigMergeTest.anUnknownFieldSurvives — inside an entry object AND at root level on the local side. The analogue of ConfigSecretsTest.kt:47 and the property a Whitelist-based merge would destroy.
- ConfigMergeTest.theApiKeyIsCarriedOverOutOfBand — winning ai has no key, loser does; Result.apiKey holds it and merged contains no apiKey, for both stamp orderings.
- ConfigMergeTest.rulesVersionOutrunsBothSidesOnAGenuineCollision — 'be kind' v4 vs 'no pranks' v4 → 5.
- ConfigMergeTest.identicalRulesConvergeToOneVersionWithinTwoMerges — (be kind, 5) and (be kind, 6) → 6, no further bump.
- ConfigMergeTest.twoDevicesThatBothClaimedMasterConvergeWithoutAClock — equal stamps, lexicographically smaller token, both directions.
- ConfigMergeTest.orphanScrubAfterAKidRemoval — blockedFor, allowedFor, deviceProfiles and entry.profiles all cleaned.
- ConfigMergeTest.scrubbingAnEntrysLastKidDoesNotWidenIt — asserted as visibleTo(otherKid) == false, not as a profileIds set.
- ConfigMergeTest.blockedForAndAllowedForCannotBothHoldOneVideoForOneKid — newer wins, tie keeps the block, loser gets a tombstone.
- ConfigMergeTest.updatedAtNeverExceedsTheLocalClock — an incoming doc stamped a year ahead does not poison it.
- ConfigMergeTest.tombstonesArePermanentAndCappedNewestFirst — 1500 removals prune to 1000, oldest dropped, floor raised.
- ConfigMergeTest.theFloorNeverDeletesAnUnstampedRow — at == 0 is exempt.
- ConfigMergeTest.aRestoredBackupSurvivesAMergeWithADeviceThatHasEvictedTombstones
- ConfigMergeTest.aPathologicalConfigStaysUnderSixtyFourKilobytes — 1000 tombstones, 30 log lines, 200 channels, 6 kids. Exceeding the LAN body cap is a 413 that discardBody only drains 256 KB of, so the sender reads it as 'device offline'.
- ConfigMergeTest.setsAreEmittedSorted — blocked, aiAllowed and overlay values, so byte comparison is meaningful.
- ConfigMergeTest.mergeReturnsNullWhenTheDocumentsAreAlreadyIdentical — a steady-state sweep writes nothing and pushes nothing.
- ConfigMergeCompatTest.aStaleLegacyPushDeletesNothing — incoming legacy doc with updatedAt = now but content two weeks old; every locally-held unit survives.
- ConfigMergeCompatTest.anEmptyPeerDocumentDeletesNothing — a freshly-installed tablet's rawJson() fallback must not wipe the family.
- ConfigMergeCompatTest.aLegacyPushDoesNotEraseLocalTombstones — push a no-sync document onto a store holding gone['src|Coco']; the tombstone is still on disk.
- ConfigMergeCompatTest.aLegacyDocumentNeverOutranksARealStamp — positional ranks are 0..n, real stamps are millis.
- ConfigMergeCompatTest.anUnstampedLocalDocumentIsNotDeletedByAStampedPeersAbsences
- ConfigMergeCompatTest.aChannelDeletedThenRoundTrippedThroughALegacyPeerStaysDeleted — the laundering case: the laundered re-add loses to the dated tombstone.
- ConfigMergeCompatTest.restoringAPreStampBackupDoesNotResurrectDeletedChannels
- ConfigMergeCompatTest.aStatusBodyFromZeroEightOneParsesToNullSyncV — fed the literal Pairing.kt:663-676 JSON.
- ConfigStampTest.aSettingsShapedSaveThenPushCarriesTheBlob — remove a channel, save through the buildCurrentConfig shape, assert the pushed bytes contain "sync" and the same tombstone map the file holds.
- ConfigStampTest.configBuiltFromBaselineKeepsSync — the guard on the baseline.copy rewrite.
- ConfigStampTest.aSettingsSaveThatTouchesNothingKeepsEveryTombstoneAndLogLine
- ConfigStampTest.aFormBuiltSaveKeepsTombstonesItNeverSaw — the 3-way carry-forward clause.
- ConfigStampTest.aNonParentWriteMintsNoLogLineButStillStamps — master claim, look adoption.
- ConfigMergeConcurrencyTest.twoThreadsLandingDisjointPushesLoseNothing — two real threads, 50 iterations each through separate file-backed ConfigStore instances, modelled on ScreeningStoreTest.kt:84.
- ConfigMergeConcurrencyTest.aMergeUnderAnUnlockedUpdateDoesNotResurrectATombstone — one thread doing mergeIncoming with a delete, one doing update{} from a pre-read snapshot.
- ConfigMergeConcurrencyTest.fileLockIsNotHeldAcrossTheKeystoreRoundTrip — asserted by timing a merge with a deliberately slow SecretStore stub.
- ConfigSecretsTest.mergeIncomingLeavesNoApiKeyOnDisk — both stamp orderings, including the carry-over branch.
- ConfigSecretsTest.mergeIncomingStillTeachesTheDeviceTheKey — the key reaches SecretStore so the TV can still screen.
- ConfigSecretsTest.aSyncLogNeverCarriesTheApiKeyThroughExport — populated log plus an ai collision; Backup.export output contains no 'sk-' and no baseUrl at any depth.
- KidNoticesTest.aStampOnlyChangeSaysNothing — extends the existing byte-identical-re-push assertion (KidNoticesTest.kt:28).
- SyncDecisionTest (five cases, milestone 4).
- ConfigCollisionTest (seven cases, milestone 5).

## Build guards

1. scripts/check.ps1 / check.sh / .github/workflows/build.yml: add SingleChannelProbeTest to the single-name exclusion beside ExtractorSmokeTest. It calls ChannelInfo.getInfo unguarded (no Assume, no runCatching) so a bot wall fails the offline gate and the PR gate for reasons unrelated to any change.
2. scripts/check.ps1 / check.sh: a grep guard that fails the build if `System.currentTimeMillis` or `Instant.now` appears in ConfigMerge.kt outside the `stamped` function body. The merge being clock-free is the property that makes idempotence and associativity structural rather than test artifacts.
3. scripts/check.ps1 / check.sh: a grep guard that fails the build if `writeAtomically(` appears in ConfigStore.kt anywhere except inside the private `commit(json)` function. Every config write must pass through the one place that stashes the API key in SecretStore and strips it from the bytes.
4. scripts/check.ps1 / check.sh: a grep guard that fails the build if `Whitelist(` (constructor call, not `.copy`) appears in ui/Settings.kt. buildCurrentConfig must stay `baseline.copy(...)` so any future Whitelist field is inherited rather than silently defaulted out of every push.
5. ConfigSyncFormatTest.fingerprintIgnoresTheSyncBlobEntirely — the single assertion that protects the ~15 existing fingerprint-equality assertions and stops a future contributor appending a sync term and permanently wedging every mixed-build household.
6. ConfigMergeTest.mergeSignatureTakesNoClock — reflection assertion that ConfigMerge::merge has no Long or Clock parameter.
7. ConfigStampTest.settingsScrubAndMergeScrubAgreeOnTheSameInput — the shared scrubReferences cannot drift between Settings.buildCurrentConfig and the merge; drift here fails OPEN, widening what a kid sees.
8. ConfigMergeTest.aPathologicalConfigStaysUnderSixtyFourKilobytes — a correctness property, not tidiness: exceeding the 1 MB LAN body cap yields a 413 the sender reads as 'device offline'.
9. A CI assertion that the three backup include lists (backup_rules.xml once, data_extraction_rules.xml twice) are unchanged by this work — everything new lives inside config.json, which all three already carry, and SyncNotices is deliberately excluded. If a future change adds a persisted file, that assertion should fail and force the audit (the pre-existing watchlater.tsv gap proves the lists are easy to miss).
10. New test files must end in exactly `*Test.kt` — check.ps1, check.sh and CI all discover classes by globbing that pattern, and a file named otherwise is silently skipped by all three and looks green.
11. Do NOT mirror the merge rules inside a test file the way ChannelIndexMergeTest.kt:13 does ('Mirror of the merge in ChannelIndex.addVideos, kept in lockstep'). Every test here calls the real ConfigMerge function. The precedent exists in the repo, so a reviewer may accept a mirrored rule; reject it.

## The skill

**`.claude/skills/yosemite-kids-sync/SKILL.md`** — *Config sync invariants: read before touching ConfigStore, ConfigMerge, Whitelist, or the /config and /status routes.*

**Frontmatter description** (this is what makes it trigger): "Rules and invariants for Yosemite Kids' sectioned config merge — the `sync` blob, tombstones, stamps, and the peer-to-peer reconcile. Use whenever changing `ConfigStore`, `ConfigMerge`, `Whitelist`, `Settings.buildCurrentConfig`, the `GET /status` or `POST /config` routes, `Backup`, or anything that writes `config.json`."

**Section 1 — The two clocks, and why one of them is a lie.** `toJson` stamps `updatedAt = System.currentTimeMillis()` at *serialization* time (ConfigStore.kt:316), and `rawJson() = toJson(load())`. So a document's `updatedAt` on the wire is the moment it was handed over, never the moment a parent edited it. Never derive an ordering, a staleness test or a tombstone from it. The edit clock is `sync.at[unit]`, minted once per save in `ConfigMerge.stamped`.

**Section 2 — The nine rules, stated as prohibitions.**
1. Never add a term to `ConfigStore.fingerprint`. Stamps and tombstones live in `syncHash`, advertised separately on `/status`, because an old build can never reproduce a hash over a blob its `fromJson` ignores — and the pair would then read "out of sync" forever after the first channel deletion.
2. Never read a clock inside `ConfigMerge.merge`. It takes no `now` parameter and a grep guard enforces it.
3. Never clear a tombstone on a re-add. That is `SavedListStore.add`'s shape and it is wrong here — it destroys the evidence the causality rule requires.
4. Never derive a tombstone from an absence in a document with no `sync` block. A legacy document carries no delete information.
5. Never merge from `ConfigStore.load()`. Use `rawFile()`. `load()` scrubs lapsed passes and overlays pending kid looks, so merging from it reads a clock tick and an un-adopted restyle as parent edits.
6. Never parse and re-serialize through `Whitelist` inside the merge. `saveRaw`'s forward-compatibility guarantee (pinned by `ConfigSecretsTest.kt:47`) holds only while nothing re-serializes from the model.
7. Never construct a `Whitelist(...)` in `Settings`. Use `baseline.copy(...)`, or the next field added to the class is silently dropped from every push.
8. Never write `config.json` outside `ConfigStore.commit(json)`, and never hold `FILE_LOCK` across a Keystore round trip.
9. Never put anything derived from the `ai` object into `sync.log` or a collision record. `stripSecrets` removes exactly `root.ai.apiKey` and can never be taught to walk free text; `config.json` is in all three backup include lists and rides `Backup.export`.

**Section 3 — The unit table.** The full key list with each namespace's safe state (PRESENT for `blk|`/`for|`; ABSENT for `src|`/`kid|`/`kid.pin|`/`allow|`/`afor|`/`dev|`), and the reason polarity is not uniform (PLAN-hub.md:221 decides both "the block holds" and "the delete holds", and those point opposite ways).

**Section 4 — Adding a field to the config.** A checklist: pick a merge unit or add one; stamp it in `stamped()`; add a change-log `code` and a `text` renderer; decide its safe state if it is removable; add it to `toJson`/`fromJson` **omit-at-default** so an untouched family keeps its bytes and its hash; add it to `fingerprint` only under the existing append-only-when-set rule and only at the tail after `;LN:`; write the four canonical tests (round-trips, omitted at default, keeps the pre-feature fingerprint, moves the fingerprint when set — the `ConfigStoreJsonTest` template).

**Section 5 — What breaks silently, with the symptom.** A table so a future debugging session recognises these fast: entry order changed → two phones hash identical content differently and read "differs" forever; `syncHash` computed over `JSONObject.toString()` → insertion-order-dependent, invisible to JVM tests because `org.json` uses a `HashMap` while Android uses a `LinkedHashMap`; tombstone TTL reintroduced → devices on different clocks prune different sets and push at each other forever; a `Whitelist(...)` in Settings → every ordinary edit pushes a sync-less document and the merge never runs on the primary path; `settingsOpen`-style guards → the reconcile stops while a parent leaves Settings open, and they never covered `POST /config` anyway; scrub emptying `profileIds` → `visibleTo` fails open and a kid sees more.

**Section 6 — The half-upgraded household.** What is guaranteed and what is not. Guaranteed: an old build parses, stores and enforces a `sync`-bearing config unchanged; a legacy peer never loses content to a merge; the fingerprint is unmoved. Not guaranteed: deletes do not cross a legacy hop (an old TV's `GET /config` re-serializes through its own model and launders the blob out); the change log makes that visible, and the devices row now names the version truthfully. `version.json` still points at upstream v0.7.8 / versionCode 28 and `UPDATE_MANIFEST_URL` defaults to empty, so "ship an update to both ends" is not currently available and `Updater` offers no downgrade — plan format changes forward-only.

**Section 7 — Where the rendezvous is.** Parent phones never pair with each other; only `TvSettingsScreen` and `KidDeviceScreen` render a QR. Two parents' edits meet on a kid device that is powered on and on the LAN. A household whose TV is unplugged for a fortnight gets no reconciliation at all, which is why `COLLISION_FRESH_MS` is sized against evenings and tombstones are permanent. This is the seam a later Docker hub fills; anything the hub needs must stay strictly additive to what is described here.

**Section 8 — Run before claiming anything works.** `scripts/check.ps1` (or `/yosemite-kids-check`), and for anything touching the wire, the two-device loop in `scripts/emu.ps1`. Never `POST /pair-request` while testing.

## Open questions

1. Should `version.json` be repointed at the fork before this ships? Self-update is inert today (`UPDATE_MANIFEST_URL` defaults to empty; the manifest still names upstream v0.7.8 / versionCode 28), so a half-upgraded household is the permanent steady state rather than a transition, and there is no downgrade path if a format decision proves wrong. The plan is safe either way, but the honest caveats in FORK-NOTES get much smaller if a family can actually update both ends.
2. Restrictive-wins for blocks: PLAN-hub.md:221 decides 'the block holds', and this plan implements it. But the counter-argument is good — Dad unblocking ten minutes after Mum blocked is usually Dad *responding* to a request, and the plan's causality rule already lets that work because his copy carries her block. Worth confirming with the user that the residual case (a re-block from a phone that never saw the unblock silently wins) is the behaviour they want, since it is the one place the merge can quietly undo a deliberate later action.
3. The `settings` group is deliberately coarse: ten loose scalars share one stamp, so two parents changing two different scalars in the same window lose one group. Splitting is additive and can wait for evidence from the change log that it ever happens. Ship coarse?
4. Entry ids are not canonical — the same channel yields a UC id, an `@handle` or a `user/name` path form depending on how it was added (Whitelist.kt:382-418, SettingsChannels.kt:413), and two parents adding one channel by different routes produce two `src|` units the merge cannot see are one thing. Not made worse by this change and not fixed by it; the real fix is a normalisation pass at `WhitelistParser.entryFor`. Separate piece of work — schedule it or accept the duplicate row?
5. Should `ScreeningStore` eventually key cached verdicts on a content hash of the judging inputs rather than on `ai.rulesVersion`? That would make the one non-associative rule in the merge disappear and remove the (bounded, rare) risk of paying for two family-wide re-screens instead of one. It touches Screening.kt rather than the sync path, so it is deliberately out of scope here.
6. `COLLISION_FRESH_MS = 36h` is sized against 'I set it last night, you changed it this evening'. If the household's rendezvous device is off for days at a time, real collisions will land outside the window and go unsurfaced. Is 36h right, or should it be tied to 'since this phone last successfully reconciled with any peer'?
7. The Combine dialog is hidden for a peer with no `syncV`, leaving only Replace. That is correct and safe, but it removes an affordance a parent may have been using during the upgrade window. Confirm that 'Replace, with today's wording' is an acceptable sole option against a legacy device.
## Decisions taken (2026-09-03)

Both open questions the plan flagged for the user were agreed:

1. **`version.json` should point at the fork before this ships.** Recorded, not
   yet actioned: both git remotes still point at upstream and there is no fork
   repo to point at. `UPDATE_MANIFEST_URL` stays empty until one exists, so a
   half-upgraded household remains the steady state and the FORK-NOTES caveats
   stand. Action this the moment the fork has a home.
2. **Restrictive-wins for blocks is accepted, residual case included.** A
   re-block from a phone that never saw an unblock wins silently. It is the one
   place the merge can quietly undo a deliberate later action, and it is the
   right bias for a parental-controls app.

The other five were taken at the plan's own defaults: ship the `settings`
group coarse, leave entry-id canonicalisation as separate work, leave
`ScreeningStore` verdict keying out of scope, keep `COLLISION_FRESH_MS` at 36h,
and offer Replace alone against a legacy peer.

### M1 progress

Done and green (260 unit tests, gate passing):

- `ConfigStore` takes a `File`, with the `Context` constructor delegating.
  Everything behind the `Context` is null-guarded, so a JVM test drives the
  real serialize and write paths. 9 tests in `ConfigStoreFileTest`.
- `load()` serves the last good copy and sets `degraded` instead of laundering
  a parse failure into an empty config. The kid migration and the master claim
  are both gated on it — each would otherwise mint into the empty read, persist
  it over the real file, and push it to every device.
- The kid migration's id is `ConfigStore.fingerprint(c)`, not `Profile.newId()`.
  Two phones migrating the same kid-less config now mint the same kid; a random
  id gives two, which whole-file last-writer-wins hides by discarding one and a
  merge would not.
- `ProfileNamespace`'s lock moved to a companion-level object. `@Synchronized`
  guarded a throwaway instance, because `ConfigStore.registered()` builds a
  fresh namespace on every load — so the read-modify-write that decides which
  kid inherits the legacy stores had no mutual exclusion at all.
- `ConfigMerge.describe` — the pure structural diff, 15 tests in
  `ConfigDiffTest`, including that it never renders the API key, the endpoint
  or a kid's PIN.
- The Pull dialog fetches before the parent decides and lists what will change,
  capped at 8 lines with an honest "…and N more". Copy then writes the exact
  bytes the diff described rather than re-fetching.
- `SingleChannelProbeTest` excluded from the offline gate; Android stubs return
  default values in unit tests, without which nothing that logs was testable.

Still open in M1: surfacing the peer's build on the devices row (M1e).
