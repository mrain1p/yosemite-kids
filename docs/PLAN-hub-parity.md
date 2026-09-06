# The hub as the parent app — password, parity per control, and one budget per child

Design record for the round that answers the owner's five asks. Written against the working tree at `efb060e` (1.0.6); every claim below was read out of the files named. Shape follows `docs/PLAN-crawl.md`: what is true today, numbered decisions with the alternative each beat, steps that each end green, risks with the mitigation named. **Re-read the code before acting on any line.**

The five asks, in the owner's words:

1. "The hub should have the same features as the phone's parent settings, plus whatever hub-specific fields are worth having."
2. "It is already an installable web app; it should feel like a parent app."
3. "I want to be able to set a password not use a token."
4. AI key: "ideally they should live on the nas so anyone can use the features."
5. "I'd prefer if the data is consolidated similar to screen time and limits consolidated there. per profile should be global watch time option vs device watch time."
6. (Late, and the one that shapes the most code) "please make it maintainable so that when we need to add or update settings on the phone its easy to ensure its added to the docker and vice versa unless they are specific to each."

---

## 1. What is true today

**The parity machinery.** `core/…/SettingsSurface.kt` lists 21 groups: 13 on the hub (`Where.BOTH, hubReady = true`), 4 correctly phone-only, 3 phone-only with reasons that have gone stale, and `stats` (`BOTH, false`). Guards 1–3 in `scripts/check.sh` / `check.ps1` read it in both directions: every root field `Settings.buildCurrentConfig` writes must be claimed, every `…Section(` composable must be declared, and the two faces' page sets must agree. Guard 11 checks each hub page has a renderer in `index.html`'s `PAGES` map. Guards run to 20 in both scripts, mirrored, with guard 10 comparing the headings and guard 18 syntax-checking the mirror.

**The gap in it.** The table is keyed on **groups**, not on **controls**. `hubReady = true` is permanent. This is not hypothetical — it has already happened twice:

- `screen-time-rules` is `hubReady = true`. The hub renders per-kid session/weekday/weekend/break number fields and **nothing else**: no family-wide `limits` card, no `minVideoMinutes`, no pause.
- `blocked-times` is `hubReady = true`. The hub has **no blocked-windows editor at all**.

**The hub's GUI** is one hand-written file, `hub/src/main/resources/web/index.html`, 823 lines: a login card, a flat pill-tab strip over seven pages, cards built from DOM helpers (`card`, `toggleRow`, `numberField`, `textField`, `chips`, `button`). Every edit is `patch({ <jsonKey>: value })` → `POST /api/config` (root keys allow-listed by `HubWeb.PATCHABLE`) or `/api/channels|devices|versions`, all landing in `HubStore.edit`, which stamps. No build step, nothing fetched from the internet — deliberate.

**Screen time is per device.** `SessionGuard`'s store is `getSharedPreferences("limits$profileSuffix")` — per **child**. The rules are on `Profile.limits`, resolved by `Whitelist.limitsFor(kid)`; a `Grant` carries a `kidId` and is merged config. The **only** per-device things are the running tallies `dailyWatchedMs` and `sittingWatchedMs`. A kid with a TV and a tablet therefore gets the daily budget twice. Nothing documents this.

**`rolloverIfNewDay` rolls on any difference**, including an earlier day: `if (previous != today)`. Winding a device's clock back a day zeroes `dailyWatchedMs` and hands out a fresh budget, today, in shipped device-local mode.

**The hub reaches exactly two things outbound**: YouTube through `:crawl`'s `Http.client` with `Http.HUB_HOSTS` armed in `Main.kt`, and the devices' `POST /sync-now` through `HubNudge.kt`. Guard 7 has four negative-tested clauses over that. The hub holds no credential on any device: `HubTokens.approve` mints its *own* token, which the **phone** stores as `PairedDevice.token`.

**The hub's admin secret** is 24 hex from `devices.json` (or `YOSEMITE_KIDS_ADMIN_TOKEN`), read once in `Main.kt` and passed into `HubServer` **by value**, printed on every boot. Two verification sites: `login(ex)`, throttled by `HubSessions` (8 failures / 15 min, global), and `admin(ex)` — the `X-Admin-Token` header used by `/approve` and `/pending` — **not throttled at all**.

**The AI key already reaches the hub and is thrown away twice.** `ConfigSync.sweep` pushes `store.rawJson()` (= `toJson(loadForPeers())`, secrets included) to every peer including the hub; `ConfigMerge.merge` strips `ai.apiKey` from both sides, and `HubStore.commit` strips it again on the way to disk. `HubStore.merge` calls `ConfigMerge.merge(raw(), incoming)` with `localApiKey` **defaulted to `""`**.

### Four things found by reading, which change the shape of the answer

**(a) Config-carried grants are never applied on the receiving side.** `Whitelist.grantsFor` has **no caller anywhere in `app/src/main`**. `SessionGuard.applyGrants` is called only from `SettingsScreenTime.kt:934` (the granting phone itself) and `MainActivity.kt:125` (the `POST /grant` LAN fast path). The 1.0.5 claim that "a television that was asleep when the parent tapped still finds the minutes when it wakes" is false today. This is a live bug on the phone, and it is the load-bearing prerequisite for a Grant button on the hub.

**(b) The hub's device→kid assignment writes under the wrong key.** `HubWeb.assignDevice` writes `deviceProfiles[device.token]`, where `device.token` is the **hub-issued enrolment token**. Every device reads `config.deviceProfiles[PairingStore(context).deviceToken()]` — its own pairing token (`ConfigSync.kidHere:327`, `Stats.kt:117`). Different keys. "This device is for Noa", set on the hub, does nothing. This is in a group already marked `hubReady = true`, and it is also a hard prerequisite for the usage ledger, which is keyed by device identity.

**(c) The hub's `/` catch-all answers unimplemented device routes with 200 + HTML.** `s.createContext("/")` is registered last and `web()` returns the admin page for any GET. `MainViewModel.syncWatchState` calls `fetchWatchState`, `fetchVerdicts` and `cacheStats` on **every** paired device including the hub; all three get the admin page, parse to nothing, and `StatsCache.save(hubToken, html)` writes `index.html` into `files/stats_cache/` on every sweep, forever.

**(d) The hub GUI mints kid ids in the browser**: `String(Date.now().toString(16)).slice(-8)` — time-derived, colliding, and not `Profile.newId()`'s CSPRNG shape. Ids are merge-key material (`kid|<id>`).

---

## 2. Decisions

### The shared budget

**D1. Adopt design A: the running tally lives in its own small document, `usage.json`, as grow-only cells keyed `(kidId, day, deviceId) → minutes`, joined by per-cell `max`. The *choice* — whether a kid's budget is shared — is an ordinary config field.**

Two things travel, and they are not the same kind of thing. A parent's assertion is policy and belongs in the merged document. A counter has no "who acted later"; it has a join.

*Why not design B (counters inside `config.json`, one slot per (kid, device), date in the value).* B is the cheapest design in the set and its date-in-the-value trick genuinely dodges the tombstone problem. It was not taken for one structural reason: `familyDay = max(localDay, seenDay)` makes the day boundary a **contagious monotone ratchet propagated by the merge**. B's +1-day write clamp is relative to `seenDay`, and a fast device's own freshly-written slot *becomes* the new `seenDay` — so at B's own 60-second cadence one bad RTC walks the whole household's day forward a day a minute, irreversibly, because every other device takes the max. One broken television grants the family an unbounded budget and no parent action reverses it.

*Why not design C (rows in the config keyed by day, top-K prune, read `k >= today`).* C contributed the sharpest single idea in the set — write under your own day, read `k >= today`, never adopt a peer's day — which is adopted below. It was not taken because it puts the day in the merge key and then has to invent `Usage.keep(rows, K)` applied inside **both** `ConfigMerge.merge` and `ConfigStamp.stamped`, with `prevSync.at` pruned by hand or two devices holding identical rows push at each other forever; and because its "disagreement rule" (any peer reporting a different `/status` day within the hour switches this device to summing *all* live days) is a designed unfair lockout with no explanatory surface anywhere.

*The reason both config-carried designs lose, stated once.* `SyncDecision.syncAction` takes the `Merge` arm on any `syncHash` difference, and a usage row's stamp moves `syncHash`. A counter in the config therefore means a full policy fetch + merge + re-push between every pair of peers, plus a `HubStore.onChanged` → `HubNudge` fan-out to every enrolled device, **every minute anyone is watching**, for the life of the feature. `mergeLogs` caps `sync.log` at 30 lines, so a usage line per minute would also erase a family's change history in half an hour.

*Where the judges disagreed, unsmoothed.* Two of three judges picked A; the third picked B and scored A lowest, on the ground that A invents the most — a second synced document, a new hub store, two hub routes on a hub that today holds config and only config. That criticism is correct and this plan pays that cost knowingly, for the two reasons above. The same judge also **falsified one of A's own arguments**: A claimed a config-carried counter would destroy the hub's five-slot restore ring, but `HubVersions.rotate` early-returns on `existing == incomingFingerprint` (`HubVersions.kt:79–82`), and both rival designs correctly kept usage out of `ConfigJson.fingerprint`. The ring was never at risk. That argument is struck from the record; a design record that wins on a false argument teaches the next session the wrong lesson.

**D2. The ledger merge takes no time argument at all. Windowing is a separate local operation.** This is the improvement the merge-laws judge named, and it is not cosmetic. `UsageLedger.merge(a, b)` is pure per-cell max with two parameters and nothing else. `UsageLedger.trim(ledger, today, keepDays)` is applied only to what a device stores for itself, at write time — never to what it merges, never to what it serves.

*Alternative rejected:* A's own `merge(a, b, today)` with the window applied inside, "held to the same law as `ConfigMerge`". `ConfigMerge` takes **no** `now` at all, which is prohibition 2 in the sync skill; `MasterElection` parameterises time to reach a *decision* nobody has to agree with byte-for-byte, which a merged document is not. Worse, the guard would have hidden it: the clockless guard greps for `currentTimeMillis|Instant\.now|System\.nanoTime`, and a `today: String` parameter sails straight through — the guard would report green on exactly the property it exists to protect. And the property tests hold only for a *fixed* `today`, which is never the case across two devices whose windows differ: that is the sync skill's own "a tombstone TTL — devices on different clocks prune different sets and push at each other forever". Nothing is lost by splitting: cells are grow-only with no tombstones, so a long-disconnected peer reintroducing a trimmed day costs a few bytes and is inert (readers only ever sum today), and the next local write trims it again.

**D3. A device writes under its own day and never adopts a peer's; the day never moves backwards.** `FamilyDay.rollover(previous, candidate)` keeps the **later** of the two, which fixes the shipped `rolloverIfNewDay` bug and is a prerequisite for a shared budget rather than an extra. Cells dated more than one day ahead of the reader are dropped on read, so a fast clock cannot pre-spend a day it has not reached.

*Alternative rejected:* B's ratchet, per D1.

**D4. Enforcement stays local and immediate; the network only decides how fresh the number is.** `SessionGuard` gains exactly one summand. A kid pressing play never waits on a round trip. Sittings, break locks, blocked windows, pause and grants are untouched and stay device-local: a sitting is a stretch in front of one screen, and only the *budget* was asked to be shared.

*Alternative rejected:* the hub as the ledger of record ("ask the hub what is left"). The hub is optional, holds no credential on any device, and cannot tell a television to stop — so it needs a device-local number anyway, which is the same counter, plus a second enforcement path that most families would be running.

**D5. Push the fact, pull the data — both directions device-initiated.** Devices **push** what only they know (the minutes just spent). Devices **pull** what they need (the aggregate), at session start. The hub is a peer that never sleeps: it stores, relays and displays. **Guard 7 is untouched** — every new route is inbound.

*Alternative rejected:* the hub pushing a budget or polling `GET /stats`. Either requires the hub to hold a credential on a device, on the box most likely to face the internet one day. `docs/ROADMAP.md` §4 left this open; this closes it as **push**, matching `HubNudge`'s existing argument.

*The genuine weakness is reach, not direction.* The nudge only finds a device whose `host:port` the hub learned from an earlier authenticated call, and a sleeping television has never called. For a shared budget this barely matters, because the consumer of the number is the device about to **start**, and it fetches then.

**D6. Two device-initiated moves, and no third.** Session-start bounded fetch, and a per-minute report during playback. No parked connection, no foreground service, no new trust.

*Alternative rejected, explicitly:* a device dialling the hub and holding the connection open. A device that is not playing spends no minutes; a device that is playing is awake and already reporting; the only moment a stale total does harm is session start, when the device fetches anyway. The parked connection is worth building for a **different** problem — a parent pressing "check every device now" and a sleeping television answering — which is roadmap §2A, where it must be justified by the problem it actually solves. (§2A already carries this note as of 2026-09-06.)

**D7. The 15-minute worker is demoted to a backstop and named as one.** Fifteen minutes is WorkManager's minimum *period* — an Android floor for background polling, not a decision anybody made. Polling is the wrong instrument here because the number only matters at the instant a child starts watching, which a poll is almost never aligned with. It stays because a device that was asleep or off the network needs *some* floor.

### The parity machinery (ask 6)

**D8. Extend `SettingsSurface` to declare CONTROLS, not replace it.** A `SettingsControl(id, label, sub, kind, writes, where, why)` list on each group. `writes` is the config JSON **leaf path** the control patches (`"sponsorSkip"`, `"limits.session"`, `"limits.budgetScope"`, `"ai.model"`), empty for a control that writes no config.

*Alternative rejected:* a guard that parses the phone's composables and the hub's `index.html` and diffs the control sets without a shared declaration. There is no reliable way to recognise "a control" in a Compose tree — the Playback page has no section composable at all, which is precisely the blindness that made this manifest field-keyed in the first place.

**D9. The manifest owns the parent-facing words, and both faces read them.** This is what makes the phone-side guard real rather than a rubber stamp: a control that is not in the manifest has no label to render. The phone calls `SettingsSurface.control("<id>").label`; the hub gets the control list in `/api/state`, which already ships `SettingsSurface`-derived data (`phoneOnly`). It also removes a whole class of drift nobody was watching: the two faces describing the same switch differently.

*Alternative rejected:* leaving the strings in Compose and requiring a `// control: <id>` comment. A comment can be added without the control and deleted without the build noticing.

**D10. The hub renders simple controls generically and bespoke ones by hand — and the "no build step" constraint survives.** Controls whose `kind` is `TOGGLE`, `NUMBER`, `TEXT`, `CHIPS` or `TEXTAREA`, writing a plain root or `limits.` path, are rendered by one ~80-line `renderControl(c)` in `index.html` straight from the manifest. **A new toggle on `playback` then appears on the hub from one declaration, with zero hub edits** — which is literally the owner's ask. Controls whose `kind` is `CUSTOM` (the channel list with its per-kid chips, device rows, the screening queue, grants, backup, version history) stay hand-written and carry a `data-control="<id>"` attribute for the guard.

*Alternative rejected:* generating the whole page from data. Some hub cards are genuinely bespoke and a generative renderer for them would be worse code than the code it replaced. *Alternative rejected:* a build step or a framework from a CDN. `index.html` is one hand-written file served from the jar on a NAS with no internet assumption; guards 19/20 depend on that. Data-driven is not compiled — the constraint is unaffected, and the generic renderer *reduces* per-control markup.

**D11. "Specific to each" is a first-class declaration with a mandatory reason.** A control may be `Where.PHONE` or `Where.HUB`, and then it is **silent** — no warning, no outstanding count — but its `why` must be non-blank. The reason is recorded exactly where the next session meets it.

**D12. Parity is enforced per control (new guard 21, four clauses, both scripts, each negative-tested).**

- **(a)** Every root property of `Whitelist` and every property of `Limits` and `AiConfig` is claimed by exactly one control's `writes`, or is listed in `SettingsSurface.NOT_A_CONTROL` with a reason. *Adding a config field with nothing to set it fails here.*
- **(b)** Every control with `where != PHONE`, in a group with `hubReady`, is either generically rendered (`kind != CUSTOM`) or present in `index.html` as `data-control="<id>"`. *Adding a hub-eligible control and not building it fails here.*
- **(c)** Every control with `where != HUB` is referenced by id somewhere in `app/src/main/java/io/yosemitekids/app/ui/*.kt`. *Adding a phone control and not wiring it fails here — and because the label lives in the manifest, the reference is load-bearing, not ceremonial.*
- **(d)** A control whose `where != BOTH` has a non-blank `why`.

Guards 1–3 stay exactly as they are; this sits one level below them.

**D13. Clause (b) will fail on the day it is written, and that is the point.** `screen-time-rules` and `blocked-times` are marked `hubReady = true` and are materially incomplete on the hub. A harness written to match what already exists proves nothing (`PLAN-hub-gui.md`'s own rule), so the machinery ships **with** the group that needs it: the family-wide rules card and `minVideoMinutes` come free from the generic renderer, the windows editor and pause are `CUSTOM` and get built in the same step.

### Password, AI key, hub parity

**D14. The admin token stays the machine credential; a password becomes the human one.** PBKDF2-HMAC-SHA256, 210 000 iterations, 16-byte salt, 32-byte key, NFC-normalised and not trimmed, compared with `MessageDigest.isEqual` — the same `v1:salt:iterations:hash` shape `SettingsStore` already uses, with the pure half lifted into `:core` so both faces share one verifier. Stored under `password` in `devices.json`. The `X-Admin-Token` **header name and `/approve`'s request and response are unchanged**, so an older phone works with no app change: the parent types the password into the field labelled "Admin token".

*Alternative rejected:* replacing the token everywhere. First run would still need a bootstrap secret, and the phone's join flow would put a human password on the LAN on every device introduction. Two secrets, two jobs.

*Alternative rejected:* Argon2/scrypt/bcrypt — a dependency in an image a NAS pulls over a home connection, for a credential checked a handful of times a day. The JDK ships exactly one credible password KDF and it is present in `eclipse-temurin:17-jre`.

*Why hash it at all when the recovery token sits in plaintext three lines above?* They protect different things. Anyone who can read `devices.json` already holds every device token and the whole config — the hub is over. What the KDF protects is **the parent's password everywhere else**, because a family will reuse one, and this volume lives on a NAS people back up to cloud drives.

**D15. The most important change in the password work is not the hashing — it is one `adminGate()`.** `admin(ex)` is today an unmetered credential oracle four threads wide, and it becomes a CPU denial-of-service the moment a KDF sits behind it. `/login`, `/approve`, `/pending`, `/password` and `/recovery` all go through one gate that checks `mayAttempt()` **before** reading the body and **before** any derivation. The lockout escalates 15 min → 30 → 1 h → 2 h, capped at 6 h, reset by any success; the **recovery token is exempt from the lockout** (a 96-bit secret gains nothing from a rate limit, and it must stay the way back in while the password path is locked, or an attacker locks the family out by failing ten times a window).

**D16. Once a password exists, the token stops approving devices and stops being printed.** It remains valid at `/login`, `/password` and `/recovery`. A leaked log line can then no longer silently enrol a device; it can only take the box over *visibly*, by changing the password. The boot line becomes `Admin token: not shown (a password is set). YOSEMITE_KIDS_PRINT_ADMIN_TOKEN=1 prints it for one boot`. Before a password exists nothing changes, because at that point the token *is* the only credential.

*Flagged:* this is the one decision here I would accept being overruled on. Its cost is a confusing 401 if a parent pastes the recovery token into the phone, mitigated by the field's label following `GET /setup` and by the 401 body naming the regime.

**D17. First run keeps the printed token as the claim ticket. No trust-on-first-use, no claim window.** `GET /setup` (unauthenticated, exactly one key) says whether a password is set; the GUI renders "Set a password" and `POST /password` takes `{current: <token>, next: <password>}`.

*Alternative rejected — and recorded because it will be re-proposed:* a claim window after boot, in `PairingWindow`'s shape. The TV's window is safe *because a human is standing in front of it holding the QR screen open*. Nobody is standing at the NAS, and `restart: unless-stopped`, an image pull, a power cut and a DSM update all reboot that container unattended — each reopening a window in which any LAN peer can claim admin.

**D18. The AI key lives in its own file on the hub's volume and is *served*, never merged.** New `HubSecrets` over `/data/secrets.json`, 0600 best-effort (not fatal — see the DSM ACL trap in `HUB.md`), overlaid in exactly two functions mirroring `ConfigStore.withSecrets`/`rawJson`. `HubStore.commit` and its `stripSecrets` call are untouched, which is what keeps the key out of `versions/`, out of `/api/state`, out of `GET /config` to a kid device and out of any backup.

*Alternative rejected:* letting the key into the hub's `config.json` so it rides the merge. One edit, four leaks — five copies in `versions/`, the admin page, `GET /config` to any enrolled token, and any future "download a backup" — and it would force `PairedDevice.secretless` to become dynamic, which is recorded on the phone at enrolment and guarded.

**D19. Two prerequisites the AI-key work cannot skip.** First, split `PairedDevice.secretless` into `isHub` (identity) and `secretless` (which fingerprint to compare on) **before** anything else; they coincide today and stop coinciding the instant the hub holds a key, and flipping the one flag silently re-enables the /24 subnet sweep for the NAS, makes `syncIndex` push at a hub that answers 405 forever, lets the phone offer to introduce the hub to itself, and breaks `POST /leave-hub`. Second, `HubStore.merge` must pass and store `localApiKey`; without it `pickKey` returns `theirs` unconditionally and **the hub adopts whatever key the last peer sent it, including a TV that slept through a rotation, and hands it back around the fleet.** That is the quietest possible failure: screening keeps working, and the bill shows the old key still being charged.

**D20. Who may receive the key from the hub: parents only, and the approver records it.** `HubTokens.Device` gains `kind` (`PARENT` | `DEVICE`), set by the approver and never claimed by the enrolling device — the same argument the codebase already makes for `secretless`. `HubEnrolment.join` passes PARENT, `tokenFor` passes DEVICE, existing rows migrate to DEVICE (fail closed; a parent re-joins to be upgraded). **The phone → kid-device path is unchanged**: `rawJson()` still carries the key, on-device screening still works, and nothing here removes that. `Screener.isVisible` fails closed, so a kid device that lost the key would show a child an empty home screen with no error.

**D21. Release ordering must survive both ways, additively.** The hub advertises `holdsKey` on `/status` and the phone sets `secretless = !holdsKey`; `hash` stays the keyless fingerprint **forever** and a new `hashWithKey` is what a new phone compares. An old phone therefore keeps agreeing with the hub exactly as it does now. A lying `holdsKey` only makes the phone compare the wrong hash — self-inflicted denial that cannot gain the liar anything, since the phone already pushes the key to the hub regardless. Keep the word `secretless` out of `hub/src` so the existing guard still passes.

**D22. The hub reads no calendar.** Every local-date or local-midnight value the hub writes — a grant's `date`, a "pause until midnight" instant — comes from the parent's browser and is bounds-checked (±1 day for a grant, ±36 h for a pause). This generalises the rule `ConfigStamp.stamped(today = null)` already encodes: a container runs UTC and the family does not.

*Alternative rejected:* a `YOSEMITE_KIDS_TZ` env var — one more thing to get wrong on the NAS, and silently wrong when a family travels.

**D23. The GUI gains a root page and drill-down, not more tabs.** `#/` root → `#/kids/<id>`, `#/devices/<ref>`. That is what converts a tab strip into a parent app, and it is what the flat seven-tab nav cannot survive: the Kids page already renders every kid with every control expanded, and adding grants, pause and per-kid screen time makes it unusable at three kids.

---

## 3. The per-profile watch-time setting

**Name:** `budgetScope`.

**Where it lives:** `core/…/data/Whitelist.kt`, on `Limits`:

```kotlin
/**
 * Whose tally the daily budget is measured against. null (and any value this
 * build does not know) = this device's own minutes, which is how every family
 * works today. BUDGET_SCOPE_SHARED = one budget across every device the kid
 * watches on. A string rather than a boolean so a future third mode falls back
 * to today's behaviour on builds that predate it, rather than needing a field.
 */
val budgetScope: String? = null
```

with `const val BUDGET_SCOPE_SHARED = "shared"` beside it. Per kid through `Profile.limits`, family-wide default through `Whitelist.limits`, resolved by the existing `Whitelist.limitsFor(kid)` — **the rules are already per child and this changes nothing about that.** Nothing in this plan introduces a per-device limit or a per-device grant.

**Merge unit:** none new. It is a limits scalar, so it rides `kid.rules|<id>` for a kid and `lim.rules` for the family default. Touch points, all existing lists: `ConfigStamp.sameRules`, `ConfigMerge.LIMITS_RULES_KEYS` (from which `LIMITS_OWNED` derives), `ConfigMerge.describeLimits`, and `ConfigJson.limitsToJson`/`limitsFromJson`/`limitsCanon`. JSON key `budgetScope`, **omitted when null**; fingerprint tail `;BS:<value>` appended **only when set**.

**Default:** `null` = per device = exactly today's behaviour. A family that never touches it serialises byte-for-byte identically and keeps its fingerprint. That is the whole migration, and it is asserted by the four canonical `ConfigStoreJsonTest` tests, not promised.

**A second, family-wide scalar:** `Whitelist.homeZone: String? = null`, an IANA zone id riding the loose `settings` unit (`SETTINGS_KEYS`, `settingsDiffer`, `settingsChanges`, omitted at null, tail-when-set). It is what makes every device bucket into the same `yyyy-MM-dd`, and it lets the hub compute the family day from a value in the document instead of from a container's UTC locale. Offered automatically, from the editing phone's own zone, the first time a parent turns `budgetScope` on for any kid — with a visible line saying so. Null = each device uses its own zone, which is today.

**The words the parent reads**, on the Screen time card under the existing rules for that kid:

> **One budget across all devices**
> Leo's daily minutes count once, however many screens he watches on. Off, each device has its own budget — which is how it works today.

and, once it is on, a second line:

> Devices compare when they can reach each other — through the hub, or through this phone while you are at home. Away from home a device falls back to its own count, and never gives less time than it does now.

**Turning it on confirms with the number** (the single best explainability feature in the three designs, and neither of the others had it):

> Leo has used 47 minutes today — 32 on the Living Room TV and 15 here. Sharing the budget leaves him 43 minutes for the rest of today. Turn it on?

**And every surface that shows a shared number says where it came from.** This is the improvement the family-harm judge named, and it is the cheapest fix for the one hole the guards cannot cover: a guard catches code drift, not data drift, and under shared scope the hub, the phone and the enforcing TV each sum whatever cells they happen to hold.

- Kid, stopped on a device they have not touched today: *"That's all the watching for today! 🌟 You watched 45 minutes on the Living Room TV."* — instead of today's bare string, which arrives on day one for every family that enables this.
- Kid's chip: *"12 min left today, across all your screens."*
- `KidPage.kt:496` currently hardcodes *"Minutes are what this device played; videos count every synced device."* Under shared scope it becomes *"Minutes count every device you watch on."*
- Parent's Screen time card and the hub's Kids page: *"90 of 90 used — Living Room TV 45, Noa's tablet 45. As of 18:42; 2 of 3 devices have reported."*

---

## 4. The ledger, the wire, and the cadence

**`core/…/data/UsageLedger.kt`** — pure, no disk, no clock:

```
{ "v": 1, "cells": { "<kidId>": { "<yyyy-MM-dd>": { "<deviceId>": { "m": 42, "at": 175… } } } } }
```

- `kidId` — the 8-hex profile id, or `"-"` for a family with no profiles.
- `deviceId` — the writer's **own pairing token** (`PairingStore.deviceToken()`), the same identity `deviceProfiles` uses. Not the hub-issued enrolment token; finding (b) must be fixed first.
- `m` — whole **counted** minutes (post-multiplier, the unit `dailyWatchedMs` is in), rounded **down**, so the ledger is never ahead of what the kid actually spent.
- `at` — the writer's wall clock, display only, never merged on, never used to order anything.

Rules, each stated so it can be a test:

- **Ownership** — a device authors only cells bearing its own id; every other cell is learned.
- **Monotone** — a local write is `max(old, new)`; `m` never decreases.
- **Join** — `merge(a, b)` is per-cell `max` on `m` and on `at`. Two parameters, no time. A missing key is zero, never a delete. Commutative, idempotent and associative by construction; no stamps, no tombstones, no floors.
- **Window** — `trim(ledger, today, KEEP_DAYS = 7)` drops days older than seven and more than one ahead. Local only: applied to what this device stores, never to what it merges or serves (D2).
- **Aggregation** — `othersToday(ledger, meId, kidId, day)` sums cells for devices **≠ me**. That exclusion is the one line whose absence silently halves a kid's day (this device would read its own cell back through a peer and add it to its own live counter), so it gets its own test rather than being an implementation detail.
- **Bound** — kids × devices × 7 days. Three kids, five devices: 105 numbers, a few KB, permanently.

**Enforcement.** `SessionGuard` gains one private accessor and every existing read of the tally routes through it:

```kotlin
private fun spentTodayMs(l: Limits): Long {
    val own = prefs.getLong("dailyWatchedMs", 0)          // the one and only read
    return if (l.budgetScope != BUDGET_SCOPE_SHARED) own
           else own + peerMinutesMirroredForToday() * 60_000L
}
```

This device's own contribution always comes from the live counter, exact to the second. The peers' sum is a **prefs mirror**, refreshed off-main whenever a ledger arrives — never a file read and JSON parse, because `tick` runs on the main thread every five seconds.

**The wire — two routes on each face, no new credential, guard 7 untouched.**

| Route | Where | Auth | Body | Notes |
| --- | --- | --- | --- | --- |
| `GET /usage` | device (`LanServer`) | approved admin token | — | the windowed ledger; mirrors `/watchstate` exactly |
| `POST /usage` | device | approved admin token | same shape | merged; bounded parser, refuses past `MAX_CELLS = 2000` |
| `GET /usage` | hub | enrolled device token | — | the hub's merged ledger |
| `POST /usage` | hub | enrolled device token | same shape | **writer-owns-cell enforced for real** from `HubTokens` + `X-Device-Id` |

The ownership asymmetry is deliberate and must be commented: the **hub** drops foreign cells, because every enrolled device can reach it directly and no relay through it is needed; a **device** accepts them, because a parent's phone relaying the TV's cells to the tablet is the only path a hubless family has, and that caller already holds a token that can rewrite the whole config, so the ledger's blast radius is strictly smaller than what it could already do. In either direction `max` only goes up: a bad actor can cost a kid minutes, never grant them, and a parent's correction downward is a **grant** — which already merges, already appears in the change feed, and already reaches a sleeping device.

Backed on the hub by `HubUsage` over `/data/usage.json` with its own file and its own lock. `HubStore` is untouched: config commits, the fingerprint, `sync.log` and `HubVersions`' five-slot ring are unaffected by watch traffic.

**The cadence, with each interval justified.**

1. **Session start — the case that actually matters.** A kid watches thirty minutes on the television, then picks up the tablet ten minutes later. Nobody is watching two things; the second device is simply working from an old number. So at profile pick, and again before the player's first frame, `UsageSync.refreshBeforeSession(kidId)` fires a parallel `GET /usage` at every reachable peer with a hard **1 000 ms total budget** on `Dispatchers.IO`. On expiry, play starts on what we already hold — **never a spinner in front of a child**. Skipped entirely when the kid's scope is not shared, and skipped when the last refresh is under 20 s old so pressing play straight after picking a profile does not fetch twice.
2. **During playback — every 60 s, and on stop/pause.** One `POST /usage` of our own cells to each reachable peer. On a LAN, beside a video stream, this is free. On a phone it is one small POST a minute while the screen is on and playing — no wakelock, no scheduling, nothing while the app is closed. This is what bounds simultaneous drift.
3. **`ConfigSyncWorker`'s 15 minutes — the backstop only.** Carried inside `ConfigSync.sweep`'s existing per-device loop, for a device that was asleep or off the network. Skipped when no kid is in shared mode.

**Reach, concretely.** A kid television's `paired()` holds the hub and nothing else — the only way an entry gets there is `POST /join-hub`. So:

- **With a hub:** the TV pulls at session start (≈ 1 s) and pushes every minute. Two kid devices converge with no parent present, and a phone can learn the TV's minutes while the TV is off.
- **With no hub:** kid devices exchange nothing directly, and the parent's phone is the sole courier — it holds every device, and one sweep merges the TV's cells and pushes the union to the tablet. Freshness is then the phone's sweep: seconds after any settings save, ≤ 5 min while its app is open, ≤ 15 min from the worker, and **nothing at all while the phone is out of the house**. A TV in that state enforces its own budget, which is exactly today's behaviour: shared scope degrades to device-local and is never worse than what the family has now. The switch's subtitle says so before a parent turns it on, and the "as of / N of M reporting" line on both faces makes it visible afterwards rather than promised.

**Simultaneous play needs a bound, not a solution.** With peers reachable, the worst case is one report interval per extra concurrently-playing device: **about one minute of overspend per extra screen**, against a budget measured in tens of minutes. Two screens at once for a whole evening costs a minute. That is acceptable for a family, and the alternative that would bound it harder — leasing each device a slice of the budget — puts a lease expiry into shared state, which is a clock in the merge, and cannot work at all in a house with no hub to lease from.

**Two real sources of simultaneous burn, named rather than waved away.**

- **A television left autoplaying in an empty room.** The count is of what the device *played*, not of whether anyone watched — that was already true, and a shared budget makes one room's forgotten TV eat another room's time. Nothing here fixes it. What makes it legible is the per-device split on the card ("Living Room TV 45 min" when nobody was in there); `autoplayNext` is already a parent switch, and the sitting cap and break lock still apply per device.
- **Two siblings on one profile.** Same profile id, so their minutes add — already true on one device, now true across devices. The answer is that profiles exist; give the second sibling their own.

---

## 5. Steps, each independently gate-able

Ordered so nothing depends on a later step, the password lands early, and no step widens guard 7.

**1. Fix what is already broken.** (a) `ConfigSync.applyArrived` applies config-carried grants per profile — `SessionGuard(context, suffixFor(id)).applyGrants(after.grantsFor(id, FamilyDay.of(now, zone)))` — and posts the pill; `applyGrants` is idempotent by id so it cannot double-count against the LAN fast path. (b) Devices send `X-Device-Id: <own pairing token>` on every authenticated hub call; `HubTokens.noteSeen` records it first-writer-wins and surfaces a conflict rather than overwriting; `HubWeb.assignDevice` keys `deviceProfiles` by it. (c) The hub's `/` catch-all answers a JSON 404 for `/watchstate`, `/verdicts`, `/stats`, `/looks`, `/grant`, `/play`, `/player`, `/check-updates`. (d) Kid ids minted server-side with `Profile.newId()`.
*Gates:* new `HubDeviceIdTest` proving the id the hub stores is the id the device reads back; a `GrantArrivalTest`; **guard 21 is not this step's** — instead a one-line addition to guard 5's `arrival_owner` list requiring `applyGrants(` to appear in `ConfigSync.kt`, so the arrival paths cannot drift again.

**2. The hub password.** `HubPassword` in `:hub` (pure: derive, verify, NFC, hex, record shape, length floor ≥ 12, fail-closed on an unknown `kdf`), `PbkdfHash` lifted into `:core` and shared with `SettingsStore`; `HubTokens` gains `hasPassword`/`setPassword`/`verifyAdminSecret`/`rotateRecoveryToken` inside the existing lock (and closes `startEnrolment`'s unlocked read-modify-write while in the file, or a concurrent enrolment silently drops a password write); `HubSessions` gains escalation and `closeAll(except)`; `HubServer` gains one `adminGate()`, `GET /setup`, `POST /password`, `POST /recovery`, and verifies through `tokens` on every call so a change takes effect without a restart; `Main.kt` gains the print policy; the GUI gains the first-run card and the change form; the phone reads `/setup` in `HubEnrolment.probe` and labels its field accordingly, adds `KeyboardType.Password`, maps 429 to a new `Failure.Throttled`, and stops looping every TV with a secret it already knows is wrong.
*Gates:* `HubPasswordTest` (**the plaintext appears nowhere in `devices.json`**; the same password hashes differently on two hubs; a low `iter` record verifies then is rewritten at the current count; an unknown `kdf` fails closed; **changing the password leaves every enrolled device alone**); `HubSessionsTest` (escalation, cap, reset, **the recovery token is exempt from the lockout**); `HubServerTest` (**ten wrong `X-Admin-Token` on `/approve` then the correct one gets 429**; a change takes effect in the same process; other sessions close and the caller's survives; `/setup` carries exactly one key; a secret in a query string is never accepted); `HubIntegrationTest` (an older-phone-style raw `X-Admin-Token` POST carrying the password still works). **Guard 21** (renumbered below to **24** so the parity guard keeps 21): the literal `"X-Admin-Token"` occurs exactly once in `hub/src/main`; `mayAttempt()` occurs only inside `adminGate(`; no `println`/`System.err` in `hub/src/main` on a line mentioning `password` or `secret`; no `createContext("/forgot"|"/reset"|"/recover")`. **Skill:** `.claude/skills/yosemite-kids-lan-api/SKILL.md` gains a rule beside its existing "never POST /pair-request while testing" — *never probe a real hub's `/approve` or `/login` with a wrong secret*, because ten guesses now locks the family's actual box out for fifteen minutes and, after the second window, an hour. That is a session-time judgement, not a check.

**3. Controls in the shared table, guard 21, and the generic renderer.** `SettingsControl` and `NOT_A_CONTROL` in `SettingsSurface`; labels for one group moved out of Compose into the manifest and read back through `SettingsSurface.control(id)`; `/api/state` ships the control list; `renderControl(c)` in `index.html`. Built **with** the group that needs it: the family-wide screen-time rules card and `minVideoMinutes` arrive on the hub free from the generic renderer, and the windows editor and pause are declared `CUSTOM` and built here — which is what makes `screen-time-rules` and `blocked-times` stop lying.
*Gates:* guard 21's four clauses (D12), each negative-tested; guards 1–3 and 11 still pass; `SettingsSurfaceTest` in `:core` asserting every control id is unique and every `writes` path resolves to a real property.

**4. `FamilyDay` and the monotone day.** `core/…/data/FamilyDay.kt`: `of(nowMs, zone)`, `rollover(previous, candidate)` = the later of the two, `compact(day)` for the existing `yyyyMMdd` prefs keys so there is **no prefs migration**. `Grants.dateOf` moves here and `Grants` stops formatting dates. `Whitelist.homeZone` with its four canonical tests. `SessionGuard.rolloverIfNewDay` and `Stats.kt` take their day from `FamilyDay`.
*Gates:* `FamilyDayTest` — a clock jumped back a day, a clock jumped forward a year, a first-ever run with no previous day. **Guard 22** (both scripts): `SimpleDateFormat` must not appear in `SessionGuard.kt` or `Stats.kt`, and `FamilyDay.kt` is the only file in `core/src/main` that formats a calendar day, failing with *"a third spelling of a day splits buckets with no visible symptom"*. The nine display-only formatters in `DigestScreen`, `KidStats`, `StatsScreen` and `SettingsImportExport` are deliberately left alone and the guard says so: if they disagree by a day the symptom is a chart, not a lockout.

**5. `UsageLedger` in `:core`.** The record, `merge(a, b)`, `trim`, `othersToday`, `parse`/`toJson` with `MAX_CELLS`.
*Gates:* `UsageLedgerTest` — commutative, idempotent, associative over generated ledgers, plus a round in `MergeConvergenceTest`'s repeated-application harness with the inputs held still, because that is what a Push button and a fifteen-minute worker actually do. **Guard 23** (both scripts, two clauses): `UsageLedger` joins the existing `for clockless in ConfigMerge MasterElection` loop, **and** `UsageLedger.merge(` must take exactly two parameters — because a `today: String` parameter sails straight through the `currentTimeMillis` grep and the guard would otherwise report green on the very property it exists to protect (D2). Negative-test both.

**6. `budgetScope`, the device side, and the read-site sweep.** The field and its four canonical tests; `UsageStore`/`UsageSync` in `:app` (file I/O, the flush edge, the prefs mirror, all off-main); `GET|POST /usage` on `LanServer` with `LanClient.fetchUsage`/`pushUsage`; the `SessionGuard` accessor and every call site routed through it; the session-start fetch at profile pick and before the first frame; the per-minute report; the settings control with its confirm-with-the-number dialog; the conditional kid strings and `KidPage.kt:496`; the version-skew banner against `FIRST_SHARED_BUDGET_VERSION_CODE`.
*Gates:* `SessionGuardSharedBudgetTest` (pure, via the companion, no `Context`) — with scope off every existing assertion holds byte for byte; with it on `remaining = budget − (own + peers)`; a cell for another day contributes zero; **a device never counts its own cell twice**; the fail-open fallback when nothing answered. `GlobalBudgetSupportTest` — a device list containing a `versionCode` below the constant produces the "cannot share a budget" line, because a silent lenient failure is the one thing this feature must not have. **Guard 25**: `grep -cF 'getLong("dailyWatchedMs"' SessionGuard.kt` must equal 1 — copied verbatim from guard 16's shape, which holds the two bonus stores to one reader each for exactly this reason. Without it, a missed read site is a home screen promising 40 minutes while the player stops at 10. **Emulator walk mandatory**, two profiles, two running instances, per CLAUDE.md's rule about changes that look correct in review and do nothing on the device.

**7. The hub's half of the ledger — and `stats` reaches the hub.** `HubUsage`; `GET|POST /usage` with writer-owns-cell from `X-Device-Id`; the screen-time card on `#/kids/<id>` with today's per-device split, the as-of line and "N of M devices reporting"; the same column on `#/devices/<ref>`, which also renders `HubTokens.Device.address`/`lastSeenAt` and closes the hub half of roadmap §2G.
*Gates:* `HubUsageTest` (retention, ownership refusal, sums); the `stats` group flips to `hubReady = true` under guard 21's clause (b), which is what proves it was actually built.

**8. Grants and pause on the hub.** `POST /api/grant` (never `/api/config` — `grants` must not join `PATCHABLE`, because a browser that could set the array could delete entries, which the stamper reads as expiry, and could mint ids colliding with a live merge key). The hub mints `id = Profile.newId()`, takes `date` from the browser per D22, and writes through `HubStore.edit`. Family-wide "Pause everyone" on the root; per-kid pause on the kid page.
*Gates:* `HubGrantTest` — the minted id is 8 lowercase hex; a date more than a day from the hub's UTC day is refused; `grants` is not patchable; two grants on one day both survive the convergence harness. **Guard 26**: no `LocalDate`, `Calendar`, `SimpleDateFormat` or `ZoneId.systemDefault` anywhere in `hub/src`, failing with *"the container's clock is UTC and the family's is not — that is why `HubStore.edit` passes `today = null`"*.
The wording must not borrow the phone's: the hub cannot fire `POST /grant` at a device (that needs a credential guard 7 refuses it), so it writes the config and nudges. *"Reaches every device at its next sync — right away if it is awake and the app is open, when it next opens otherwise."*

**9. The GUI's shape (ask 2).** Root page with the phone's two status tiles and grouped rows whose second lines are live state; hash routing and drill-down to `#/kids/<id>` and `#/devices/<ref>`; browser Back working; **the change feed** — the last ~30 lines of `sync.log` rendered through `ConfigMerge.describe`'s vocabulary, which is the highest value per line on this whole document because the data is already in the config the hub holds and nothing renders it. "This hub" gains identity, health, the crawl block moved from Devices, `GET /api/backup` and `POST /api/restore` (through `HubStore.edit`, never a byte copy — `HubVersions`' own KDoc lists the four ways a byte restore loses an argument with the merge), and version history.
*Gates:* guard 11 still passes; a new clause that every `#/` route resolves to a renderer; `HubBackupTest` — a downloaded backup contains no `apiKey` at any depth, and a restore of a snapshot predating a co-parent's edit does not bulldoze it.

**10. The AI key on the NAS (ask 4).** D19's flag split first, as its own commit; then `HubSecrets`, `forPeers()`, `aiConfig()`, `localApiKey` in `HubStore.merge`; then `/status`'s `holdsKey` and `hashWithKey`, `HubTokens.Device.kind`, `GET /config` splitting by kind, the phone reading `holdsKey`; then a set/replace/clear control in the GUI that never renders the value back (last four only).
*Gates:* **`HubStoreTest`, which does not exist today** and is where "the hub's disk never holds a credential" stops being enforced by `stripSecrets` happening to be called in one function — a push carrying a key leaves `config.json`, `versions/v-*.json`, `/api/state` and `/api/backup` keyless; **a stale peer cannot un-rotate**, proved by running the merge repeatedly with the inputs held still. New guard clause: `secretless` may be read only in `ConfigSync.kt` and `SettingsDevices.kt`; anywhere else, someone meant `isHub` — and guard 3's failure message, which currently *teaches* the conflation, is rewritten. **Docs:** `HUB.md`'s "not editable here and will not be" is replaced by the honest security statement — the NAS has no hardware keystore, the file is readable by root, by anything sharing the bind mount, and by whoever holds a backup of that folder; use a separate provider key with a spending cap, and treat "someone got into the NAS" as "rotate the key".

**11. Verdicts and the review queue on the hub (last, optional).** `ScreeningStore` moves to `:crawl` (it is already `File`-constructed; the `Context` factory stays in `:app`, the pattern `ChannelIndexAndroid.kt` already uses); `GET|POST /verdicts` on the hub; `aiAllowedVideoIds`, `blockedFor`, `allowedFor` join `PATCHABLE`. The queue entry already carries title, channel, thumb, reason and verdict, so **no video cache is needed on the hub** — the part the group's recorded reason got wrong. Thumbnails are fetched by the browser, not the hub, so guard 7 is untouched; `HUB.md` should say the admin page makes third-party requests.
*Gates:* `ScreeningStoreTest` moves with it; `HubVerdictsTest` for the fail-closed unblock path (`blk` and `for` fail closed, so lifting a block needs the lifter to be holding it — the hub merges the config, so it is).

**12. Docs, manifest, skill, roadmap.** `LAN-API.md` gains a row for every new route **in the step that adds it**, plus a new guard mirroring guard 14 that scans `s.createContext("` in `HubServer.kt` against the hub table — guard 14 only reads `Pairing.kt`, so the hub's routes have never been covered. `SettingsSurface`'s `why` text for `ai-connection`, `ai-discovery`, `directory` and `stats` (all four state things that stopped being true in 1.0.5 or stop being true here). `ARCHITECTURE.md`, `HUB.md`, `FORK-NOTES.md`, `ROADMAP.md` §4 closes as "push". **A twelfth prohibition in `.claude/skills/yosemite-kids-sync/SKILL.md`: never put a counter in `config.json`** — with the `syncHash`/nudge-storm arithmetic and the 30-line log cap spelled out as the reason, plus the day rules (*write under your own day, read forward, never adopt a peer's*) and the pull toward "just make it a merge unit like everything else". No script can catch a future session reaching for a `use|<kid>|<day>` unit, because it looks exactly like the other units.

---

## 6. What a future session literally does to add one new setting

Say it is a new family-wide toggle, "Ask before playing a long video".

1. `core/…/data/Whitelist.kt` — add the property with its default set to **today's behaviour**.
2. `core/…/data/ConfigJson.kt` — `toJson`/`fromJson` **omitted at its default**; `fingerprint` tail, append-only-when-set.
3. `core/…/data/ConfigStamp.kt` — add it to the comparison of the unit it belongs to (`settingsDiffer` here; `sameRules` for a limits scalar).
4. `core/…/data/ConfigMerge.kt` — add its JSON key to that unit's key list (`SETTINGS_KEYS` / `LIMITS_RULES_KEYS`) and a line to `settingsChanges` / `describeLimits`.
5. `core/…/data/SettingsSurface.kt` — add one `SettingsControl` to the right group: `id`, `label`, `sub`, `kind = TOGGLE`, `writes = "askBeforeLongVideo"`, `where = BOTH` (or `PHONE`/`HUB` **with a `why`**).
6. `app/…/ui/<the screen>.kt` — render it, taking its words from `SettingsSurface.control("<id>")`.
7. `hub/…/web/index.html` — **nothing at all.** `kind = TOGGLE` on a plain root path is rendered generically from the manifest. Only a `CUSTOM` control needs a hand-written card, and then only one `data-control="<id>"` attribute.
8. `core/src/test/…/ConfigStoreJsonTest.kt` — the four canonical tests: round-trips, omitted at default, keeps the pre-feature fingerprint, moves the fingerprint when set.

**What fails the build if they do half of it:**

- Skip **1–4** in the wrong order and the sync skill's existing failures bite: a field left out of `ConfigStamp` mints no stamp and is dropped by the first peer that merges.
- Skip **5** and guard 1 fails for a root field, or guard 21(a) for a `Limits`/`AiConfig` leaf: *"nothing in SettingsSurface sets this field."*
- Skip **6** and guard 21(c) fails: the control is declared for the phone and no phone file references it — and because the label lives in the manifest, the reference is not ceremonial.
- Declare it `BOTH`, make it `CUSTOM`, and skip **7** and guard 21(b) fails: *"this control is on the hub's list and the hub does not build it."*
- Declare it `PHONE` or `HUB` with a blank `why` and guard 21(d) fails.
- Skip **8** and the fingerprint moves for a family that never touched the setting, which the canonical tests assert against.

---

## 7. Risks named, with the mitigation in the change

- **The `SessionGuard` read-site sweep is the riskiest part of the budget work — not the CRDT.** `max` is trivially correct and the property tests pin it; the danger is seven enforcement call sites plus every screen that shows minutes (`KidStats`, the Settings root, `Stats.build`, the who's-watching tiles, the hub card) all having to agree with the enforcer. One missed site is a screen promising time the player will not give. *Mitigation in the change:* guard 25 ships in the same commit, and the two-profile emulator walk is mandatory rather than optional.
- **The session-start fetch sits in front of the first frame.** *Mitigation:* a hard 1 000 ms parallel budget on `Dispatchers.IO`, "play on what we know" as the timeout behaviour, the constant tested, and the call site gated on shared scope so nothing changes for a family that never turns it on.
- **A `today` parameter would smuggle a clock back into the merge and the obvious guard would not see it.** *Mitigation:* guard 23's second clause counts `merge(`'s parameters, negative-tested.
- **Silent un-rotation of the AI key.** Skip D19 and the hub adopts whatever key the last peer sent, including a TV that slept through a rotation, and hands it back around; screening keeps working so nobody looks, and the bill shows the old key weeks later. *Mitigation:* `localApiKey` in `HubStore.merge`, proved by repeated application with a stale peer, in `HubStoreTest` — a file that does not exist today and is owed regardless.
- **Empty kid home screens.** `Screener.isVisible` fails closed. If the key ever stopped reaching kid devices before verdicts did, a TV would show a child nothing, with no error. *Mitigation:* D20 — the phone → kid-device key path is not touched by any step here, and "stop shipping the key to kid devices" is a separate, version-gated decision that is not in this plan.
- **The hub becomes a credential store** on a bind-mounted volume whose permissions this project has already watched fail. *Mitigation:* separate file, 0600 best-effort, and four *never*s asserted by tests rather than comments; `.dockerignore` is already `*`-first (guard 13), so `secrets.json` cannot enter a build context.
- **Guard 7 eroding by increments.** *Mitigation:* nothing in this plan widens it. The two things that would — hub-side AI discovery and the channel directory — are out of scope and named in §8.
- **A half-upgraded fleet.** A device on an older build ignores `budgetScope` and grants a second budget there. It fails **lenient** (a kid gets more time, never less), which is the right direction for a silent failure but must be visible. *Mitigation:* `FIRST_SHARED_BUDGET_VERSION_CODE`, the "cannot share a budget" line naming the device on the Screen time card, and "Update now" which already exists per device — with a test, because a silent lenient failure is the one thing this must not have.
- **Turning shared scope on mid-evening ends a kid's viewing by a parent's own tap.** *Mitigation:* the confirm-with-the-number dialog, which is the highest-frequency instance of "a parent cannot explain what happened".
- **The escalating lockout as a denial of service against the parent.** *Mitigation:* the recovery token is exempt, and `YOSEMITE_KIDS_ADMIN_TOKEN` in the compose file always works.
- **`index.html` outgrowing one file.** *Mitigation:* stay single-file (no build step on a NAS, nothing from a CDN, guards 19/20 assume it), but the generic renderer *removes* per-control markup, and `PAGES` / `data-control` stay where guards 11 and 21 can read them.
- **The manifest becoming a second place to be wrong.** Moving ~40 user-facing strings out of Compose is the real cost of D9, and it is a one-time sweep. *Mitigation:* it is done group by group, each group's move is its own green step, and the strings had no `stringResource` indirection to lose — the app uses Kotlin literals throughout.

---

## 8. What this does NOT do

- **No parked connection, no foreground service, no server-sent events.** Deferred to roadmap §2A (device reachability), where it must be justified by the problem it actually solves — a parent pressing "check every device now" and a sleeping television answering. It is *not* forgotten and it is *not* needed here: a device that is not playing spends no minutes, a device that is playing is awake and already reporting, and the only moment a stale total does harm is session start, when the device fetches anyway.
- **No hub-side AI screening and no "Discover with AI" on the hub.** Both need `Http.restrictedClient` armed from a parent-typed `ai.baseUrl`, which is the one genuine widening of guard 7 in the whole parity list. It is a separate, deliberate decision with its own record; `HubSecrets` is written so either answer stays reachable.
- **No "Suggested channels" on the hub.** Its recorded reason is stale and it would be cheap, but it browses *upstream's* directory and this fork deliberately removed the community publishing surface. Ask the owner first: if the answer is no, delete the group from the phone rather than porting it.
- **No downloads, no local videos and no media cache on the hub.** The hub may show a read-only "3 videos downloaded, 1.2 GB" line once the usage payload carries it. A download cache on the NAS served over the LAN puts the hub in the media path and is a different project.
- **No per-device limits and no per-device grants.** The rules stay on the child, which is what they already are.
- **No shared sittings or breaks.** A sitting is a stretch in front of one screen. Sharing it needs a per-device `(epoch, minutes)` pair joined lexicographically, because a resettable counter is not monotone; that is the extension if it is ever wanted, and v1 says plainly that breaks are per device, which is what families have today.
- **No clawing back.** The ledger only goes up. A downward correction — "give him back the twenty minutes the broken TV clock ate" — is a **grant**, which already merges, already appears in the change feed, and already reaches a sleeping device.
- **No credential in either direction that does not exist today.** The hub still holds none on any device; a kid device still holds none on a phone; a kid device can raise one number that names itself and can change no rule.
- **No live "now playing" or "⏸ pause on TV" on the hub.** Those need a `GET /stats` and a `POST /player` the hub would have to initiate. They stay on the phone and the hub's page says so.
- **Nothing that makes a hub required.** Shared scope degrades to device-local when nothing is reachable, which is exactly today's behaviour and never worse; every device still works with no hub at all; there are still no accounts and no cloud.
