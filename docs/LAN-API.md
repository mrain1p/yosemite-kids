# Yosemite Kids LAN API

Every device runs `LanServer` (`data/Pairing.kt`) on the first free port in
**8765–8775**, plain HTTP on the home network. A phone finds a device by
scanning the QR its settings screen shows (`yosemitekids://pair?name=…&host=…&port=…`)
and, if the device later changes address, by sweeping its own /24
(`LanClient.rediscover`).

## Conventions

- Every response is `text/plain; charset=utf-8` with `X-Content-Type-Options:
  nosniff` and `Connection: close`. Clients parse by shape, not content type.
- Auth: `X-Token: <32 hex>` — the *calling phone's* device token, which must be
  in the device's approved list. Only the two pairing routes are open.
- Bounds (unauthenticated callers hit these before any body is read): 8 KB
  request line/header, 50 headers, 1 MB body (8 MB for `/index`), 10 s socket
  timeout, 2–8 worker threads with a 16-deep queue. Refused requests have their
  body drained (up to 256 KB) so the client sees the status, not a reset.
- Query values are matched by regex on the raw target; nothing is URL-decoded.

## Routes

| Method & path | Auth | Body / query | Response | Notes |
| --- | --- | --- | --- | --- |
| `POST /pair-request` | none | JSON `{token, name}`; must be `application/json` and carry **no** `Origin` header | `{"status":"approved"\|"pending"\|"closed"}` | First phone is auto-approved only while the QR is on screen (`PairingWindow`). One request per address every 3 s (429 otherwise). Pending slots: 5, oldest evicted, 10-minute expiry. |
| `GET /pair-status?me=<token>` | none | — | `{"status":"approved"\|"pending"\|"unknown"}` | Polled by the waiting phone. |
| `GET /pair-pending` | admin | — | `[{token,name}]` | |
| `POST /sync-now` | none | `{}` (ignored) | `ok` / `403` / `429` | "My copy moved, come and look." Carries no data and grants nothing — the device then runs its ordinary reconcile. Refuses any `Origin` or non-JSON body, and one per caller per 10 s. |
| `POST /pair-approve?token=` | admin | — | `approved` | |
| `POST /pair-deny?token=` | admin | — | `denied` | |
| `GET /admins` | admin | — | `[{token,name}]` | Raw tokens: all admins are equal, so this exposes nothing an admin couldn't already do. |
| `POST /admin-revoke?token=` | admin | — | `revoked` | Never yourself. |
| `POST /admin-leave` | admin | — | `left` / `409 last admin` | The calling phone drops its own approval (Unpair). |
| `GET /status` | admin | — | `{hash, updatedAt, token, versionCode, versionName, kind}` | `hash` = config fingerprint; equal hashes = in sync. `kind` is `tv`, `tablet` or `phone` (absent on the hub and on older builds); the admin phone badges devices by it. |
| `GET /config` | admin | — | full config JSON, **including** the AI key | Disaster recovery for a reinstalled phone. |
| `POST /config` | admin | full config JSON | `saved` / `400 bad config` | Validated by `ConfigStore.fromJson`; key stripped before it hits disk; fires `onConfigApplied` (kid notices). |
| `GET /stats[?profile=<8 hex>]` | admin | — | see `Stats.build` | Per-kid when `profile` is given; else the kid on screen. |
| `GET /looks` | admin | — | `ProfileLooks.exportJson`: `{profileId:{avatar,color,at}}` | A kid's own restyle waiting for the phone; the sweep adopts the newer `at` and pushes. |
| `GET /watchstate` | admin | — | `WatchSync.exportJson` | history, favourites, watch-later, per-kid blocks |
| `POST /watchstate` | admin | same shape | `merged` / `400` | LWW merge per video/url. |
| `GET /verdicts` | admin | — | `ScreeningStore.exportJson` | AI verdicts for the current rules version |
| `POST /verdicts` | admin | same shape | `merged` / `400` | Add-only; deep beats shallow. |
| `GET /usage` | admin | — | `UsageLedger`'s wire form: `{v, cells:{kid:{day:{device:{m, at}}}}}` | The family's watch ledger — minutes spent, per kid, per day, per device. This device authors its own cells at the moment it is asked (every kid's `SessionGuard` tally, not just the one on screen) and serves them alongside whatever it has learned from peers. A **counter**, deliberately not in `config.json`: a stamped value that moved once a minute would put every pair of peers through a status-fetch-merge-push and wipe a family's 30-line change log in half an hour (prohibition 12 in the sync skill). |
| `POST /usage` | admin | same shape | `merged` / `400` | Per-cell `max`, and cells dated more than a day ahead of this device's own day are dropped on read so a peer with a fast clock cannot pre-spend. A device accepts a peer's cells where the hub keeps only the caller's own — a parent's phone relaying the television's minutes to the tablet is the only path a family with no hub has, and that caller already holds a token that can rewrite the whole config. `max` only ever goes up, so the worst a bad actor can do is cost a kid minutes; the correction downward is a grant. Bodies over `UsageLedger.MAX_CELLS` (2000) are refused outright rather than truncated. |
| `POST /report` | device token | `{version, kind, entries: [{at, level, msg}]}` | `{"kept": n}` | A device draining its diagnostic ring (`Diag` in the app): every warning and error it logged since the hub last heard, sent on the one path that already talks to the hub — the sweep — and never on a timer of its own. Named by the **enrolment the token belongs to**, never by the body, so a device cannot file a line under a sibling's name. Each entry is printed to stdout as it arrives (`docker logs`) and kept in memory for the console's Devices page (`HubReports`, newest first, capped at 300; a post is capped at 50 rows and 500 characters a row). Always 200 with the count kept: a malformed row is skipped rather than the whole trail refused. It stops nothing and changes nothing on the box. |
| `GET /index-status` | admin | — | `{sourceId:{count,complete,hash}}` | |
| `GET /index?source=<id>` | admin | — | `{count,newest,complete}\n[…videos]` / 404 | id ≤ 64 chars |
| `POST /index?source=<id>` | admin | same wire format, ≤ 8 MB | `merged` / `400` | Replaces the source file wholesale. |
| `POST /player?cmd=pause\|play` | admin | — | `ok` / `409 nothing playing` | Parent's "come to dinner". |
| `POST /play` | admin | JSON `{url,title,channel,thumb,timePercent}` | `playing` / `409 refused` / `400` | "Play this on the TV". Refused when the video is blocked for the kid on screen. |
| `POST /grant?minutes=1..240[&profile=<8 hex>][&id=<8 hex>&date=<yyyy-mm-dd>&at=<ms>]` | admin | — | `granted` | Bonus minutes for today; `profile` unvalidated on purpose (may precede the push that introduces the kid). Since 1.0.5 the phone also writes the tap into the config as a `grant|<id>` unit and sends the same `id` here, so an awake device gets it now and counts it once when the config lands; a device asleep at the tap finds it at its next sync. Without `id` (an older phone) the minutes are applied the legacy way. |
| `POST /join-hub` | admin | JSON `{host, port, token}` | `joined` / `400 bad hub` | The phone hands this device a hub to sync with (a TV has nowhere to type one). Stored as an ordinary `secretless` peer. |
| `POST /leave-hub` | admin | — | `left` | Drops every hub entry, so removing the hub on the phone undoes `/join-hub`. |
| `POST /check-updates` | admin | `{}` (ignored) | `{"status": "offered"\|"up-to-date"\|"off"\|"failed"\|"busy"\|"not-on-screen", versionName, versionCode}` | "Update now" from the phone — see below. Always 200; the status is about the device, not the request. One in flight per device; a second ask during a download answers `busy`. A 404 means a build older than the route. |

## `POST /check-updates` — starting a device's update from the phone

The device runs its own update check (`Updater.checkDetailed`: fetch
`version.json`, compare `versionCode`), and if a newer build is offered it
downloads the APK and hands it to the system installer, whose prompt comes up
on the device's screen. **The prompt is confirmed by whoever is at the
device.** Nothing over the LAN can press it — Android hands the APK to its
own installer, and an admin token gets to ask, not to change what is
installed on the kids' TV without a person in front of it agreeing.

The answer comes back only once the download is done, so `LanClient.checkUpdates`
waits with a read timeout sized for a download (`UPDATE_READ_TIMEOUT_S`, 3 min)
rather than the LAN client's 10 s — a device that gave up at 10 s would still
finish and put the prompt up, and the phone would report "failed" about an
install that was waiting on the TV. The route holds a LAN worker for that long;
`RemoteUpdate`'s gate keeps it to one at a time.

`versionName`/`versionCode` name the build the installer is about to install
when the status is `offered`, and the running build for every other status.

| Status | Meaning | The phone says |
| --- | --- | --- |
| `offered` | Downloaded; the install prompt is on the device's screen. | "The install prompt for X is on *name*. Confirm it there with the remote." |
| `up-to-date` | The manifest names nothing newer than the running build. Can contradict the phone's own "behind" when the phone runs a build newer than the release. | explains that, then the row is re-read |
| `off` | The build carries no manifest URL (`Updater.canCheck()` false — builds before `FIRST_SELF_UPDATING_VERSION_CODE`). Nothing was checked. | install by hand once |
| `failed` | The manifest or the APK could not be fetched. | try again in a minute |
| `busy` | Another ask is already checking or downloading. | give it a minute |
| `not-on-screen` | An update exists, but the app has no visible window, and Android 10+ drops the installer start silently. Nothing was downloaded. Asked before the download and again after it. | open the app on the device first |
| *404* | A build older than the route. Its own settings screen still offers the install from 1.0.3 on. | use Check for updates on the device, or install by hand once |

The phone only offers "Update now" for a device that is answering, is behind,
and reports a `versionCode` at or above `FIRST_SELF_UPDATING_VERSION_CODE`;
older builds get the by-hand wording without a button that could only ever
answer `off`.

## `X-Device-Port`, `X-Device-Id`, and why the hub only ever nudges

Every outbound LAN call a device makes carries two headers about itself, both
stamped in `LanClient.raw`:

| Header | Value | What it is for |
| --- | --- | --- |
| `X-Device-Port` | the port its own `LanServer` bound, omitted before it has | where to nudge it |
| `X-Device-Id` | its own **pairing token** (`PairingStore.deviceToken()`) | who it is |

A peer's address can be observed from the socket; its *listening* port cannot,
because an inbound connection's source port is ephemeral. So the device states
it and the hub records it against the token (`HubTokens.noteSeen`), which is
how the hub learns where to call back.

The identity is there for a sharper reason. The hub authenticates a device by
the **enrolment token it minted itself** at `/approve` — a token no device has
ever seen. Every device resolves `config.deviceProfiles` by its own pairing
token. Without `X-Device-Id` those two never meet, and the hub's "this device
is for Emma" was written under a key nothing would ever look up: it saved, it
synced, and every device ignored it. `noteSeen` records the announced identity
**first-writer-wins** — a pairing token is minted once per install and a
reinstall loses the enrolment too, so one enrolment maps to one identity for
its whole life, and a second one is a restored backup or a lie. Overwriting
would re-point an assignment a parent already made at a different device, so
the first is kept and the row is flagged for the GUI. A device that has never
called cannot be assigned at all, and the hub's Devices page says so rather
than offering chips that would do nothing.

Both are claims, not credentials. The only thing ever sent to the port is a
nudge carrying no data, so the worst a lie achieves is that the liar is told
"something changed" and the real device is not — and the real device's own
reconcile still catches up on its next tick. A lie about the identity buys
nothing either: the liar is already holding an enrolment token, which can
rewrite the whole config.

**The hub announces; it never commands.** It could not push config even if it
wanted to: it holds no token any device would accept, and giving it one would
mean a device minting an admin credential for the component most exposed by
design — the box on the NAS, the one intended to be reachable from outside.
So the hub posts `/sync-now` and the device pulls, merges and authenticates
exactly as it does on its own timer. The direction of trust is unchanged:
devices authenticate to the hub, never the reverse.

A consequence worth knowing: a nudge is **pure latency**. Losing one costs
nothing. That is what makes it safe to send fire-and-forget, and why the hub
does not retry — `ConfigSyncWorker` is the floor underneath it.
## Testing against a real device without breaking pairing

From CLAUDE.md, worth repeating: **never `POST /pair-request` to a device that
has no approved phones while its QR is showing** — you would take the admin
slot. Safe probes:

```bash
curl -s "http://<tv-ip>:8765/pair-status?me=00000000000000000000000000000000"
curl -s -H "X-Token: <approved token>" "http://<tv-ip>:8765/status"
```

The approved token is the *phone's* `device_token` in its `pairing.xml`; on a
debug build read it with `adb shell run-as io.yosemitekids.app cat shared_prefs/pairing.xml`.

## Adding a route

1. Add the branch in `LanServer.handle` **below** the auth gate unless it must
   be open. Read the body with `readBody()` only after deciding the request is
   worth it, and answer failures with a real status code.
2. Add the matching `LanClient` function (use the LAN client: 1.5 s connect,
   no retries).
3. Document it here and, if the phone UI uses it, in `docs/ARCHITECTURE.md`.

## Config sync (0.9.7-fork and later)

### `GET /status` — two additive keys

```json
{ "hash": "a1b2c3d4", "updatedAt": 1780000000000, "token": "...",
  "syncV": 1, "syncHash": "9f3a1c02" }
```

`syncV` is the sync-format version and `syncHash` fingerprints the
bookkeeping — stamps, tombstones and namespace floors, never the change log.

Two peers count as in sync only when **both** `hash` and `syncHash` match. A
peer holding a tombstone the other has never seen matches on content and
differs on history; if that read as in sync, the deletion would never travel,
because the reconcile never fetches a body when it believes the two agree.

**The absence of `syncV` is the signal.** A peer that does not send it predates
the merge and is a **push-only destination, never a merge source**: its
document restamps `updatedAt` at serialization time and so always claims to be
brand new. Merging from one would let a phone that spent a fortnight in a
drawer speak with authority about a config it has not seen.

### `POST /config` — merged, not replaced

The body is a full config as before. It is now **merged** into what the device
holds rather than overwriting it, so two parents pushing to the same TV both
survive. Read, merge and write happen under one lock; two pushes on two LAN
worker threads cannot interleave.

The response is JSON rather than `saved`:

```json
{ "changed": true, "peerBehind": false, "hash": "a1b2c3d4", "syncHash": "9f3a1c02" }
```

- `changed` — the device learned something and wrote.
- `peerBehind` — the device holds something the pusher does not, so the pusher
  should fetch and merge in turn.
- `hash` / `syncHash` — where the device ended up, so a caller need not
  re-`GET /status` to find out.

A body that cannot be parsed as a config still answers `400 bad config`, and
nothing is written. Old clients that only check the status code are unaffected.

Attribution costs nothing on the wire: the pushing phone already authenticated
with a token the device can name, so the change log records who without any
new field.

## The hub's routes

The hub answers `GET /status`, `GET|POST /config`, `GET|POST /verdicts`,
`GET|POST /usage` and the two index reads like a device, and has enrolment,
the password routes and an admin GUI of its own (see `docs/HUB.md`). Every one of them is a row below.
The one place it does not behave like a device is the API key: the hub holds
one in a file of its own and serves it only to a parent.

`GET /media` is **not** in this table any more. It, and everything else a
child's browser touches, moved to a second listener on a port of its own — see
"The kid's routes" at the end of this file for the routes and for why a
separate origin is a security boundary rather than a layout choice.

Guard 14 keeps the device table above honest by reading `LanServer`; **guard 30
does the same for this one by reading `HubServer.start()`** — every literal
`createContext("…")` there needs a row here. That half had never been covered
by anything: `/enrol`, `/pending`, `/health`, `/login`, `/logout` and `/` had
all been live for rounds, five of them named in a sentence of prose and one not
mentioned at all. The wire shapes themselves are pinned by `HubServerTest` and
`HubIntegrationTest`.

**Everything else in the device table above is a JSON 404 on the hub.**
`HubServer` registers `"/"` last, so an unknown path lands on the admin page
rather than a status code a parent has to interpret — right for a typo, and a
lie to a device. A phone sweeps `/watchstate`, `/verdicts` and `/stats` across
every paired peer including the hub; all three came back 200 with the page's
HTML, the two mergers parsed it to nothing, and `StatsCache` wrote index.html
into the phone's `files/stats_cache/` on every sweep. `/verdicts` has since
moved out of the refusal list and into the table below, which is the shape that
list is meant to have: a route sits there until the hub really answers it. The
refusal list is `HubServer.DEVICE_ONLY`, and guard 22 holds it to exactly the
routes `LanServer` answers and the hub does not — while guard 29 holds the
other half, that every device route the hub *does* answer calls
`authorised(ex)` first.

| Route | Auth | Body | Reply | Notes |
| --- | --- | --- | --- | --- |
| `GET /status` | device token | — | as a device, plus `token`, `kind: "hub"`, `hubVersion`, `holdsKey` and (when that is true) `hashWithKey` | `hubVersion` is the same string `GET /health` carries, said inside the sweep a paired device already makes, so a phone can one day refuse to push a field an old image would drop rather than watching it disappear; a peer that does not read it is unaffected. `token` is the hub's self token (`.hub` + 28 hex, minted once): an identity, never a credential. It is what `config.masterDeviceToken` holds while the hub builds the index, and how a phone backfills `PairedDevice.id`. `hash` is the **keyless** fingerprint for ever — a phone from before the hub could hold an API key has it recorded as keyless and compares its own keyless form against this one, so moving `hash` would put every such phone permanently out of sync with a hub it agrees with completely. `holdsKey` means "this hub holds the family's API key **and serves it to you**", so it is per caller and false to a kid device even when the box does hold one: a peer that will never be given the key must keep being judged on the keyless hash, or it reads as out of sync for ever and takes the merge arm on every sweep. The same predicate decides this and `GET /config`'s body, so the two cannot disagree. |
| `GET /config` | device token | — | the stored document, 404 before the first write | The API key is put back **only for a `PARENT` enrolment** (`HubTokens.Kind`); a kid device is served the bytes on disk, which are keyless. A television is handed its key by a parent's phone — `ConfigSync` pushes `rawJson()`, secrets included — and has no reason to be handed one by a box on the network as well; this is the machine most likely to face the internet one day. |
| `GET\|POST /verdicts` | device token | on POST, `ScreeningStore`'s verdict map | GET: this hub's verdicts for the family's current rules version. POST: `{"merged": <count>}`, or 400 when the body is not a verdict map | The same wire a device speaks, so the phone's existing sweep needed no change — and it is what makes the review queue on the admin page possible, because a queue entry carries its own title, channel, thumbnail and reason and needs no video cache on this box. The rules version is read from the config the hub already holds, so a device pushing under older rules is ignored rather than half-adopted. Import never overwrites a verdict already held (a peer's *deep* pre-play verdict over a title-only one is the single one-way exception), which is what makes a pull-then-push exchange settle instead of ping-ponging. This is the first hub route whose body is about the family's viewing rather than their settings, which is why guard 29 exists. |
| `GET\|POST /usage` | device token | on POST, `UsageLedger`'s wire form | GET: the ledger this hub has joined. POST: `{"cells": <count>}`, or 400 when the body is not a ledger, is over `UsageLedger.MAX_CELLS`, or carries no `X-Device-Id` | **Writer-owns-cell is enforced here**: the hub keeps only the cells the caller authored, identified by the `X-Device-Id` it presented with its token — the same id `Whitelist.deviceProfiles` is keyed by. Every enrolled device can reach this box directly, so it needs no relay through it. Both directions are device-initiated (a device pushes what only it knows, and pulls what it needs), so the hub holds no credential on anything and guard 7 is untouched. Backed by `HubUsage` over `/data/usage.json`, with its own file and its own lock: `HubStore`, the fingerprint, `sync.log` and the five-slot version ring are entirely unaffected by watch traffic. The hub windows the ledger by the family's own day when `homeZone` is set and by a plain cell cap when it is not — it never guesses a calendar of its own (guard 27). Nothing here blocks playback; the hub is a scoreboard, and enforcement stays where the child and the screen are. |
| `POST /approve` | admin secret | `{code, kind: "parent"\|"device"}` | `{token}` / `409 {refused}` | `kind` is recorded by the **approver** — whoever presented the admin secret — and never claimed by the thing joining, because `/enrol` is unauthenticated by necessity and nothing it says about itself is worth anything. `HubEnrolment.join` sends `parent` (a phone administering the family); `tokenFor`, which introduces a television, sends `device`. Absent or unrecognised is `device`: rows written before this existed fail closed, so a parent re-joins to be upgraded rather than a credential going somewhere nobody chose. Once a password is set the recovery token signs in but no longer approves. |
| `GET /setup` | none | — | `{"password": true\|false}` | Whether this hub has been claimed, and **exactly one key** — it tells a LAN peer only what they can already infer from the sign-in form, and nothing they can act on without the container log. It is what decides the phone's field label and which card the GUI shows; a hub older than this route answers the admin page's HTML with a 200, so a caller reads the body and not the status. |
| `POST /password` | `current` in the body | `{current, next}` | `{"ok": true, "recovery": <token>\|null}` | Set the first password or change it. `current` is the password or the recovery token, required **even inside a live session** (that session may be a browser on a kitchen counter). `recovery` is non-null on the first set only, is shown once, and retires the token from the log. 400 `{"error":"short"}` under `HubPassword.MIN_LENGTH`. Every other session is closed; the caller's survives. |
| `POST /recovery` | `current` in the body | `{current}` | `{"token": <24 hex>}` | A fresh recovery token, shown once. The previous one stops working immediately. |
| `GET /index-status` | device token | — | `{sourceId:{count,complete,hash}}`, byte-for-byte a device's | `X-Index-Pull: 1` says the caller takes its index from this hub. That **arms** the hub to claim the master slot (`HubTokens.armed`, 24 h window); a plain read arms nothing. |
| `GET /index?source=<id>` | device token | — | `{count,newest,complete}\n[…videos]` / 404 | id `[A-Za-z0-9_-]{1,64}`, else 400. Anything but GET is 405: there is deliberately **no `POST /index` on the hub**. It takes nobody's copy, because a device that could push could truncate a source the hub had crawled further. |
| `POST /enrol` | none | `{name}` | `{"code": "<8 chars>"}` / `429 {retryAfter}` | A device asking to join, and the only hub route gated by neither a token nor a session. It hands back a code to show on the joining device's own screen; the code proves someone is standing in front of it and `POST /approve` proves whoever approves holds the admin secret. Neither alone enrols anything. Codes are 8 characters from an alphabet with no O/0/I/1, expire in ten minutes, and expired ones are dropped on the next call so abandoned attempts cannot grow the file. It also **writes** on every call, so it is bounded twice: fifteen calls a minute (`HubServer.MAX_ENROLMENTS_PER_WINDOW`, deliberately wider than the admin lockout — see its KDoc) and `HubTokens.MAX_PENDING` (20) codes outstanding at once, both far above any real household — a 429 past either. At the cap the hub refuses rather than evicting the oldest, because eviction silently invalidates a code somebody is reading off a television at that moment. Note that `HubEnrolment.mint` reads **any** non-200 here as "that is not a Yosemite Kids hub", so a parent meeting one of these limits is told the wrong thing; teaching the phone to read the 429 is app-side work. |
| `GET /pending` | admin secret | — | `{"pending": [{code, name, createdAt}]}` | What is waiting to be approved. Through `adminGate()` like every other presentation of the secret, so it is throttled — it used to be an unmetered oracle, which behind a password KDF is also a way to keep all four worker threads busy. |
| `GET /health` | none | — | `{"ok": true, "version": "<x.y.z>"}` | A liveness probe for the container runtime, answered without reading the data directory. It says nothing about the family: a hub that is up and holds no config still answers this, which is what a restart policy wants to know. `version` is the build the image was made from (`HubBuild.VERSION`, generated by `hub/build.gradle.kts` and held equal to the app's `versionName` by guard 39). It is here, on the one route anything can reach before it holds a credential, because an image left behind does not merely lack a new control: `HubStore.edit` round-trips through `ConfigJson.toJson` on every admin save, so an old hub **drops** the config key behind that control the first time a parent saves anything on the NAS. Config fields ride a two-release gate for exactly that window, and nothing could check which side of it a hub was on. The same string is on `GET /status` as `hubVersion` and on the admin page's "This hub" card. The reply used to be the bare word `ok`; it is now the JSON this route's `Content-Type` always claimed. |

The GUI's front door sits outside `/api`, because on a hub nobody has claimed
there is no session yet to gate it with — and because `/` has to answer a
browser that has never signed in with something a parent can read.

| Route | Auth | Body | Reply | Notes |
| --- | --- | --- | --- | --- |
| `POST /login` | the admin secret in the body | `{secret}`, or `{token}` from a page an older service worker cached | `{"ok": true}` and a `HttpOnly; SameSite=Strict` session cookie | Both field names are read on purpose: `sw.js` caches `/`, so a browser can be posting a stale shell's body shape at a rebuilt container, and accepting both is cheaper than telling a parent to reinstall the app. No `Secure` flag — this is plain HTTP on a home LAN and the flag would stop the cookie being sent at all. Refusals and lockouts are `adminGate()`'s, below. |
| `POST /logout` | session cookie | — | `{"ok": true}` and an expired cookie | Closes this one session. `POST /password` is what closes all the others. |
| `GET /` | none | — | the admin page | Registered **last**, so it answers every path nothing above claimed — right for a parent who mistyped, a lie to a device, which is why `HubServer.DEVICE_ONLY` is checked first and those paths answer a JSON 404 (guard 22). The page itself carries no family data; every byte of that arrives later over `/api`, behind a session. Anything but GET is 405. |
| `GET /kid-tokens.css` | none | — | the kid palette and type scale as CSS custom properties | One of `HubServer.assets`, straight from the jar, like the manifest and the icons — unauthenticated because it carries no family data and a stylesheet behind a session renders the page unstyled until somebody signs in, which on a television is indistinguishable from broken. It is **generated at build time** by `:hub:generateKidTokensCss` from `core/.../ui/DesignTokens.kt`, the same table the Android theme is built from, and exists nowhere in the repository: guard 48 fails if a copy appears, so the browser cannot drift from the app. Names are `--yk-*`; `:root` is the dark look and `[data-yk-theme="light"]` the light one. |

The admin GUI's own routes are under `/api` and are session-gated, not token
gated; they are not in this table because no device speaks them. Three of their
rules are worth stating here because they are contracts, not implementation:
`GET /api/state` carries a `controls` array — exactly
`SettingsSurface.hubControls()`, the id, words, kind, range and JSON key of
every control this face is expected to render — and in `POST /api/config` a
key sent as JSON **null is removed, not set**. "Off", "Auto" and "All" are the
key's absence in this config (`ConfigJson` asks `has()` before `getInt` in half
a dozen places), so removing it is how a rule is cleared; a literal null would
throw where absence means "no rule". `HubWeb.PATCHABLE` still gates which root
keys a browser may name at all.

And the third, the five routes that are not a config patch at all:

| Route | Auth | Body | Reply | Notes |
| --- | --- | --- | --- | --- |
| `POST /api/grant` | session | `{kid: <8 hex>\|"", minutes: 1..240, date: "yyyy-mm-dd"}` | `{"granted": true\|false, "why": "OK"\|"BAD_KID"\|"BAD_MINUTES"\|"BAD_DATE"}` | Bonus minutes for today. `grants` is deliberately **not** in `HubWeb.PATCHABLE`: a patch replaces the array it names, so a browser could leave an entry out — which the stamper reads as expiry and tombstones for the whole fleet — or send an id already live as a `grant\|<id>` merge key. This route only ever appends, and the hub mints the id with `Profile.newId()`. `date` is the **parent's browser's** local day, refused when more than `HubWeb.GRANT_MAX_DAYS_AWAY` from this container's UTC day and never rewritten to it (guard 27). Blank `kid` means everyone. Always 200: `why` names the refusal, because "that date is nowhere near this box's" and "that is not a number of minutes" send a parent to different places. |
| `GET /api/backup` | session | — | the stored config document wrapped in `BackupFile`'s envelope (`kind`, `schema`, `exportedAt`, `app`, `config`), or 404 before anything has ever been written | The same envelope `Backup.export` writes on the phone, so a file taken off the NAS opens on a phone — which is the day it is wanted. The document travels **verbatim**, sync block included: without the tombstones a restore cannot tell a channel that was deleted from one that was never added. Keyless by construction rather than by a strip here (`HubStore.commit` takes the key out on every write), and `HubBackupTest` asserts that from outside at every depth. The 404 is deliberate: an empty config is a valid one meaning "no channels, no kids, no rules", so handing one out is handing a parent a file that wipes their family. The browser names the file — a date in the filename would need a calendar, and guard 27 says the container reads none. |
| `POST /api/restore` | session | a backup file, or a bare config document | `{"restored": true\|false}` | A **stamped edit**, never a byte copy — see `HubVersions`' KDoc for the four arguments with the merge a byte restore loses. Content comes from the file; every stamp, tombstone, floor and change-feed line comes from the live document, so the rollback outstamps a peer that has edited since instead of being undone by it on the next sweep. `BackupFile.configIn` decides what counts as a backup and refuses everything else, because `{}` parses as a perfectly valid empty config. Always 200: `restored: false` is "that file is not a backup", and nothing was touched. |
| `POST /api/ai-key` | session | `{key: "<value>"}` | `{"saved": true, "tail": "<last 4>"}` | Set, replace or (with a blank `key`) clear the AI key. A route of its own for the same reason `ai.apiKey` is scrubbed out of every `/api/config` patch: the key is not in the config document on this box and must not be put there — it lands in `HubSecrets` (`/data/secrets.json`), and `HubStore.setApiKey` moves the `ai` unit's stamp so `ConfigMerge.pickKey` can tell this rotation from the copy a sleeping television still holds. Without that stamp the two are a **tie**, broken lexicographically, and half of all rotations would silently lose. The reply carries the last four characters and nothing else, which is also all `/api/state` ever shows (`hub.holdsKey`, `hub.keyTail`): the value leaves this hub only through `GET /config` to a parent. Bounded to 512 characters. A body naming no `key` is a 400 rather than a successful no-op. |
| `POST /api/browsers` | session | `{claim: "<kid id>"}` or `{revoke: "<ref>"}` | on a mint, `{"minted": true\|false, "code": "<6 chars>", "why": "OK"\|"BAD_KID"\|"TOO_MANY_CODES", "ttlSeconds": 600, "url": "http://<host>/kid?c=<code>", "qr": "<svg …>"}`; on a revoke, `{"revoked": true\|false}` | The parent's half of letting a browser watch — the mint lives here, on the console's origin, because **only a parent may say that a browser is allowed and which child it is for**. Same split as `/enrol` and `/approve`: the kid origin can redeem a code and can mint nothing at all, so a tablet on the LAN cannot introduce itself to the family. The kid **must already exist** (unlike `/api/grant`, where a grant may legitimately precede the push that introduces a child): a credential bound to a profile nobody has heard of would watch under the family default, which is the loosest set of rules in the house. The code is 6 characters from `HubTokens.CODE_ALPHABET` — the same no-vowel, no-look-alike alphabet a device's enrolment code uses, and the same one object (guard 57) — single use, ten minutes, and it comes back **once, in this reply**: a code left on `/api/state` would be re-fetched by every signed-in browser in the house for as long as it lived. `revoke` takes the short reference `/api/state` lists under `browsers`, never the cookie value; it takes effect on that browser's next request. **`qr` and `url`** are the scan path: the television has drawn a pairing QR since the beginning, and a code read off one screen and typed on another is a code that gets mistyped — by a parent holding an iPad, which is this route's whole audience. The SVG is built on the box by `HubQr`, using the same `com.google.zxing:core` at the same version the app rasterises with, so the two faces share an encoder and differ only in the surface it lands on. The **host comes from the parent's own `Host` header**, not from anything the hub believes it is called: a box may have several addresses and know none of them, but the browser that just asked reached it somehow, and that route is the one a tablet on the same network can use too. The kid page redeems `?c=` on arrival and strips it from the address bar before trying, so a spent code is not left where a child can screenshot it. There is no longer a code box on the kid page: the QR (or its URL, typed) is the way in, beside the child's own password. |
| `POST /api/kid-password` | session | `{kid: "<8 hex>", password: "<4..64 chars>" \| ""}` | `{"saved": true, "set": true\|false}` or `{"saved": false, "why": "TOO_SHORT"\|"BAD_KID"}` | Set, or with a blank password clear, a child's own password for the kid app. A route of its own rather than a `profiles` patch for the reason `/api/ai-key` is: what lands in the config is the **derived record** (`Profile.webPassword`, a PBKDF2 record in the same shape as the hub's own password), and deriving it is the hub's job — a browser that could patch a record in could patch any record in, and a password the page hashed would be a second KDF to keep equal to the first. The record is one field of the profile and syncs like the rest, its own merge unit (`kid.web|<id>`) so a stale phone correcting the kid's name cannot take it away; the phone verifies what the hub set and the hub verifies what the phone set (`KidPassword` in `:core`). Four characters is the floor (`KidPassword.MIN_LENGTH`): the threat is a sibling, the door is one child's own shelves, and `HubKidLock` throttles guesses per kid. Bounded off the wire. |

The hub cannot fire `POST /grant` at a device the way a phone does — that
needs a credential on the device, which the hub holds for nothing and which
guard 7 refuses it. It writes the tap into the config and nudges, so the
minutes arrive by the same path every rule does: right away on a device that
is awake with the app open, at its next sync otherwise. `ConfigSync.applyArrived`
is the receiving half (guard 21).

`/login`, `/approve`, `/pending`, `/password` and `/recovery` all pass through
one `adminGate()`, which consults the throttle **before** reading a body and
before deriving anything: ten wrong secrets and every attempt is refused for
fifteen minutes, then thirty, then an hour, doubling to six, reset by any
success. `/approve` used not to be throttled at all, which behind a KDF is both
a guessing oracle and a processor-exhaustion attack. Refusals answer `401
{"error":"password"|"secret"}` — the regime, never what was submitted — and a
lockout answers `429 {"retryAfter": <seconds>}` with a `Retry-After` header.
The recovery token is exempt from the lockout, or an attacker who only wants
the family locked out simply fails ten times a window. That exemption was
written down here and in `HubTokens.verifyAdminSecret` long before it worked:
the gate answered 429 before it looked at the secret at all, so the one
credential meant to survive a lockout was never compared, and the way back in
was the container log. What the lockout suppresses is the **password**
(`allowPassword = false`), not the gate — a password is refused under lockout
whether it is right or wrong, and the recovery token, which a rate limit does
nothing for at 96 bits, still signs in. Guard 25 holds the gate to one door;
see `docs/HUB.md` for what a parent does with it.

`/enrol`, `/approve` and `/pending` also refuse any request carrying an
`Origin` header that is not this hub's — the same refusal `/login`,
`/password` and `/recovery` already made. No real caller is affected: the
admin GUI approves through `/api/devices`, and a device enrols over plain HTTP
with no browser in the loop, so the phone's OkHttp requests carry no `Origin`
at all, which is read as "not a browser" and allowed.

Every hub reply — the page, an asset, a JSON body, a refusal — carries
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY` and
`Referrer-Policy: no-referrer`; guard 41 fails the build if a response path is
added that does not. And a request that stops half-way is dropped after ten
seconds, the same patience `LanServer` gives an accepted socket. The JDK's
server exposes no socket to set a timeout on, so that goes on through
`sun.net.httpserver.maxReqTime`, which `HubServer.start` sets before the first
`HttpServer.create`. The response side is deliberately left unbounded: a
device pulling a whole crawled source out of `/index` over a tired wifi link
is legitimately slow, and a response timer would cut exactly the transfer that
most needs to finish.

## The kid's routes

The kid app is **`/kid` on the hub's one origin** — the page at `/kid`, every
route beneath `/kid/`, on the same port as the console and every device's
sync. One address for a family to know, one line for a compose file to
publish. It used to be a second listener on a second port, so that the two
were two browser origins; that design and this one answer the same danger, in
different places.

**The danger.** A page a child opened, on the same origin as the console, can
`fetch("/api/config", {method: "POST", …})` — and if the parent's session were
an ambient cookie, that request would pass every gate this hub has.
`sameOrigin()` compares the `Origin` host to `Host` and on one origin they
match. The cookie rides along, because cookie `Path` is matched against the
**request URI** and not against the page that made the request.
`SameSite=Strict` is satisfied, because it genuinely is the same site.
`HttpOnly` is irrelevant, because the page never reads the cookie — it only
sends it. On a shared family iPad with a parent signed in, that is a page a
child opened holding a rewrite of the family's whole configuration.

**The rule, on one origin: no credential is ambient across the wall.** The
parents' session is a **header** (`X-Session`, `HubServer.SESSION_HEADER`),
handed back in `/login`'s body, kept by the console's script in the tab that
signed in and attached on purpose to each call — the hub sets no cookie for
it and reads none, and guard 59 fails the build if `HubServer` ever sets or
reads a cookie at all. The kid's credential is a cookie, because a `<video>`
and an `<img>` can send no header — and it is scoped to **`Path=/kid/`**, so
the browser never attaches it to `/api`, `/login` or the page at `/`; the
console never reads it either way. Every `/kid/` route that says anything
about the family resolves that cookie first and fails closed (guard 60).
`sameOrigin()` still refuses a request that arrived with a foreign `Origin`,
and **nothing ever sends a CORS header** — guard 58 greps the module for
`Access-Control-` and fails if one appears. Guard 57 pins the kid route list
and holds it all under `/kid`, with none of it registered from the console's
side; `HubKidBoundaryTest` asks the hub over sockets, in the shapes a browser
produces, that a kid cookie is nothing on an admin route and a session is
nothing on a kid route.

**Getting in.** There is no code box. A parent's console mints a one-shot
code and shows it as a QR whose URL is `/kid?c=<code>`; the page redeems it on
arrival. Or the child taps their avatar on "Who's watching?" and types the
password their parent set on their profile (`Profile.webPassword`,
`KidPassword` in `:core`) — one field of the family config, so the phone and
the hub agree on it and either can set it. Both go through `POST /kid/claim`.

The kid routes answer **exactly the paths below and 404 everything else under
`/kid`**. That asymmetry with the console — where `/` is a catch-all and an
unknown path gets the page — is deliberate: under `/kid` an unknown path is
never a parent's typo, and a 200 carrying a page would be the answer that says
"keep looking". `/kid-tokens.css` stays at the root, served once by the
console's asset map for both pages: it carries no family data.

| Route | Auth | Body | Reply | Notes |
| --- | --- | --- | --- | --- |
| `GET /kid/channels?sort=&seed=` | kid cookie | — | `{sort, seed, channels[], sorts[{id,label}]}` | Every channel this kid may see, ordered by `orderChannels` from `:crawl` — the app's own function, so "A to Z" means the same thing on a television and a tablet. **Two of the app's six sorts are honestly absent**, and `KidSurface` records why rather than drawing controls that do nothing: Most watched needs per-channel open counts the hub does not keep, and Just added needs the phone's first-seen ledger. Latest video is offered since 1.9.0, read from the dates the index keeps (roadmap §2M) the way the phone reads its cache - the newest ten rows of each channel. `SearchOrder` set the precedent - a control that sorts by an all-equal key is worse than no control. `sorts` is sent rather than hard-coded in the page, so the two cannot disagree about which exist. |
| `GET /kid/surprise?seed=` | kid cookie | — | `{seed, videos[]}` | A seeded mix across every CHANNEL-kind source, `surpriseMix` from `:crawl`. **The seed is the whole point**: `MainViewModel` called a bare `shuffled()`, which is the one ordering two faces cannot agree on by construction — and a child who reloaded got a third. The page sends the seed back so a mix holds still for a sitting, and omits it to ask for a new one. Finished videos drop, for the same reason they leave the feed: the point is something new. |
| `POST /kid/list` | kid cookie | `{list: "favorites"\|"watchlater"\|"queue", v: "<11-char id>", on: bool}` | `{list, v, on, count}` | **The only route on this origin that stores something a child chose**, and the one worth reading slowly. Four checks, in order: POST and same-origin (a GET that writes is a link a sibling can send); the kid comes from the **credential**, never the body, exactly as `/media` does and guard 60 keeps; the video must be one this child **may actually see** — `HubPolicy`'s catalogue half, so a blocked video, a sibling's channel or something never indexed cannot be *saved* even though it could never be played; and `on` in the reply is read back **from the store**, because the queue is capped and an add at the cap is refused, so a page that flipped its own heart on the tap would show a video as queued that is not. The row's title and poster come from the family's own index rather than the request, which stops a saved shelf becoming a place to write text a child then reads. The stores are `SavedListStore` and `QueueStore` from `:crawl` — the same classes the phone uses, including the tombstone merge and its floor; there is deliberately no hub-shaped copy of that merge. Hub-local, like the browser's history: a phone's favourites reach the television because both run `WatchSync`, and the browser has no such worker. That gap is recorded in `KidSurface`, and closing it means moving `/watchstate` off `HubServer.DEVICE_ONLY`, not a bespoke kid-origin write. |
| `GET /kid/manifest.webmanifest` | none | — | the web app manifest | What makes the page installable: added to an iPad's home screen it opens full-screen with its own icon and no address bar, where without a manifest it is a Safari tab with a URL a child can edit. Built in Kotlin rather than generated into a file like `kid-tokens.css` — it is fifteen lines whose only two colours come from `KID_DARK`, so `:core` stays the single source without a Gradle task and a guard to police the pair. **Deliberately no service worker**: `Cache Storage` outlives the claim cookie, so a cached shell would keep a revoked or blocked child looking at a working app — the exact failure `/media`'s per-chunk `mayPlay` gate and its `no-store` exist to prevent. Unauthenticated, because a browser fetches a manifest while installing and often without credentials; it says nothing about the family. |
| `GET /kid/icon?s=192\|512\|maskable\|apple` | none | — | `image/png` | The home-screen icon, and the same artwork the console uses from the same files in the jar — one product, one mark, and a second icon set would be a second thing to redraw when the mark changes. One route with a parameter rather than four paths, because guard 57 pins this origin's route list and four near-identical rows are four things to read past when asking what that list exists to answer. An unrecognised `s` falls back to 192 rather than 404ing: a manifest asking for a size this build lacks should still install. Cached a week — unlike the stylesheet there is no per-release drift here. |
| `POST /kid/claim` | none | `{code}` or `{kid, password}` | `{"ok": true, "kid": "<name>"}` and a `HttpOnly; SameSite=Strict; Path=/kid/` cookie / `409 {refused}` / `429 {retryAfter}` | A browser trading a credential for the cookie it then carries. **Two doors, one cookie.** `code` is a parent's one-shot from `POST /api/browsers` — it arrives in the QR's URL and the page redeems it on arrival; single use, ten minutes, and a wrong one burns a try on **every** outstanding code like `HubTokens.approve`, so five guesses is the whole budget. `kid` + `password` is the child's own: checked against the record on their profile (`Profile.webPassword`, `KidPassword.verify`), and only after `HubKidLock` says that kid may still try — five wrong answers close *that kid's* door for a minute, doubling to fifteen, and a right answer clears it; a kid with no password and a wrong password are one answer, so the LAN learns neither. Unauthenticated by necessity — a browser that has never been here holds nothing to present — and it mints nothing. `Path=/kid/` is the half of the wall this route owns: the browser attaches the cookie to the kid routes and to nothing on the console's side. `Max-Age` is six months (`HubBrowsers.CLAIM_TTL_MS`), because a child signing in every Saturday is a failure nobody would put up with; a browser that is actually lost is cut off with `revoke`, which takes effect on its next request. `refused` is `UNKNOWN_CODE`, `TOO_MANY_TRIES`, `TOO_MANY_BROWSERS` or `WRONG_PASSWORD`, and never an echo of what was typed. **Throttled in a bucket of its own** — `HubKidServer.MAX_CLAIMS_PER_WINDOW` (10) a minute, `HubRate`, no escalation, and the per-kid lock above it: neither is `HubSessions`, so a child guessing all afternoon does nothing at all to a parent's sign-in (guard 59). Refuses any request whose `Origin` is not this one. Bodies are capped at 4 KB — nothing here posts more than a password. |
| `GET /kid/kids` | none | — | `{kids: [{id, name, avatar, color}]}` | Who a browser may sign in as: the children **with a password set**, as a name and a face and nothing else, for the "Who's watching?" screen. Unauthenticated, and that is a decision: a browser on the LAN learns from this that the family has children called Leo and Mia who watch here — the same thing anyone holding the television's remote sees on its own picker — and learns no rule, no video, and nothing about a kid without a password. A family that wants the LAN to see no names sets no passwords and signs in by QR alone; this then answers an empty list and the page shows one sentence. |
| `GET /kid/whoami` | kid cookie | — | `{"kid": "<id>", "name": "<name>"}` / `401 {"error":"claim"}` | Who this browser watches as. **Fails closed**: a browser that has not claimed learns nothing at all — not a child's name, not whether this hub has any children. The page itself opens on `/home`, which fails closed the same way; this is the narrower question, for a caller that wants the identity without the catalogue. |
| `GET /kid/home` | kid cookie | — | `{kid, theme, time, sections, pinned, channels, keepWatching, suggested, videos, history}` / `401 {"error":"claim"}` | **The whole home screen in one answer**, and the page's first call. One request rather than one per shelf: six round trips on a tablet over house wifi is six chances to half-draw a five-year-old's page. Every part of it is decided by shared code and none of it by the hub — `sections` is `homeSections()` in `:core` (the same shelf catalogue and order the phone and the television draw, ready for the home-screen editor when it lands), `pinned` is `resolvePins()`, the four video shelves are `KidHome` in `:crawl`, and what the kid may see at all is `HubPolicy.catalogueFor`, which walks the identical predicates `mayPlay` does — `visibleTo`, `isBlockedFor`, the minimum length and `Screening.isVisible`. A shelf built from a looser filter would be a child tapping a card and being told no. **The clock is deliberately not applied**: bedtime stops playing, not browsing, and a home screen that emptied itself at seven would read as broken rather than as bedtime. `time` is `HubPolicy.timeFor` — the countdown, from the same numbers `/media` will refuse on — or `null` for a kid with no daily budget. `theme` is that kid's own tinted tokens as CSS custom properties, computed by the same `kidTinted()` the Android app themes itself with; a build-time stylesheet cannot carry a runtime choice, so this is how "My colour" reaches a browser without a second palette. The kid is the cookie's, never a parameter (guard 60's rule). |
| `GET /kid/you` | kid cookie | — | `{kid, theme, time, shelves:[{id,title,icon,emptyText,count,preview,videos}]}` | The kid's own shelves — Favorites, Watch later, Up next, History — in `KidSurface.YOU_SHELVES`' order, with `KidSurface`'s words. **Every shelf is declared even when it is empty**, which is the app's shape rather than an oversight: the page has one form and an empty row tells a child the gesture that would fill it. `preview` is the first `ROW_PREVIEW` rows and `videos` the whole shelf at `YOU_PAGE_MAX`, both capped here because guard 61 forbids the page slicing — which is also what keeps the browser's glance the same length as the phone's rather than whatever a stylesheet happened to fit. Today only History has a store behind it; the three saved lists arrive with `HubSavedLists`. |
| `GET /kid/channel?id=<source>[&from=<n>][&watched=1]` | kid cookie | — | `{id, name, count, from, more, watched, watchedCount, playlistCount, playlists[{id, name, thumb, count}], playlistRows[{id, name, videos}], videos}` / `404 {"error":"not here"}` | One channel's page, filtered exactly as `/home` is. `playlistRows` are the rows above the grid, the phone's playlistShelves: the parent's picks (`WhitelistEntry.playlistIds`) first, then the channel's own first playlists to make three, each the playlist's unfinished videos with Shorts dropped, twelve at most. With `watched=1` it is the channel's **finished** videos instead, newest-watched first through the phone's own `orderByWatched` - the browser's Watched screen - and `watchedCount` on the ordinary page is what the link says. Otherwise — and ordered through the phone's own `defaultFilterFor` and `filterVideos` (the parent's **Channel page layout**, "Popular first" included since 1.9.0, when the index started keeping the view count) one **page size** at a time: the parent's "Videos before Show more" is applied here, never in the page (guard 61); `more` says whether to draw the button, and `from=` asks for the next page. The page size is declared honoured by the browser in `SettingsSurface`, and guard 69 holds `HubKidHome` to reading it. Each video carries `meta`, the "Channel · 3 days ago" line composed by the hub with the phone's own `metaLine` and `relativeAge` from `:core`; the age half is present only when the parent's **Show when a video came out** is on, and only for a row the index has a date for (rows an older build indexed have none until their source is crawled again). Search rows carry the same. A channel this kid may not see and a channel that does not exist are **the same 404** on purpose: a child's browser must not be able to tell a sibling's channel from a missing one. |
| `GET /kid/playlists?id=<source>` | kid cookie | â | `{id, name, count, playlists[{id, name, thumb, count}]}` / `404 {"error":"not here"}` | Every indexed playlist of one channel with at least one video this kid may see - the phone's Playlists page, "See all" from the strip. The crawl indexes a channel's playlists (`PlaylistCrawlRun` in `:crawl`: the first twenty, the first page of each, refreshed once a day, paced like the index crawl) and `count` is how many of a playlist's videos THIS kid may see, matched against the same rows the channel page is drawn from - a playlist is never a way around a block. A channel that is not this kid's is the same 404 as one that does not exist. |
| `GET /kid/playlist?id=<playlist>[&from=<n>]` | kid cookie | â | `{id, name, channel, channelId, count, from, more, videos}` / `404 {"error":"not here"}` | One playlist as a page: its videos this kid may see, in playlist order, paged by the parent's page size like a channel. Found by walking the kid's own channels, so a playlist of a channel they may not see, an empty one and one that does not exist are the same 404. |
| `GET /kid/search?q=[&from=<n>][&order=<SearchOrder>][&remember=1]` | kid cookie | — | `{query, count, from, more, order, orders[{id,label}], recent[], videos}` | Paged like `/kid/channel`, by the same page size. `order` is the kid's chip - Best match, Newest, Shortest, Mix it up - applied by `SearchOrder` in `:crawl` (the phone's own; an unknown one falls back to best match), and `orders` carries the chips in the hub's words so the page lists none of its own. `recent` is this kid's last eight searches (`RecentSearches` in `:core`, kept per kid on the hub in `HubKidSearches`); a search joins it only with `remember=1`, which the page sends on Enter or a chip, never on the keystrokes of a search-as-you-type box. An empty `q` is the page before a query: no videos, the recents and the chips. Search within what this kid may see, ordered by `SearchRank` in `:crawl` — the same ranking the phone's search page uses, weighted by what this browser has actually watched. Queries over `HubKidServer.MAX_QUERY_CHARS` (100) are refused rather than tokenized against every candidate. |
| `POST /kid/search` | kid cookie | `{forget: "<query>"}` or `{clear: true}` | `{recent[]}` | The × on a recent-search chip, or Clear all: the one thing a kid may delete on their own. Same-origin only, like every write on this path. |
| `POST /kid/progress` | kid cookie | `{v, positionMs, durationMs}` | `{minutes, allowed, reason, spentMinutes?, budgetMinutes?}` | "Still watching, and this far in", every `HubKidServer.BEAT_SECONDS` (20) while a video plays. **This is how a browser's minutes reach a budget at all**: a device charges its own `SessionGuard`, and a page cannot be trusted to charge anything, so the hub credits the time *it* has observed passing between beats (`HubWatchMeter.beat`) under a cell derived from the credential. The position is remembered alongside (`HubKidHistory`), which is what makes Keep watching and resume work on a tablet — the browser's device store, since a browser has no other. The body names a video and a position and **nothing else**; the kid is the cookie's. Beating grants nothing: `/media` re-asks `HubPolicy` on every chunk, so a browser that keeps beating past a bedtime is a browser whose next chunk stops. The countdown comes back on every beat, so a page open since breakfast is never showing this morning's number. Refuses a foreign `Origin`. |
| `POST /kid/report` | kid cookie | `{entries: [{at, level, msg}]}` | `{"kept": n}` | The page saying what broke: an uncaught script error, an unhandled promise, or a video the hub answered with a 5xx (a 403 is a rule doing its job and is not reported). Filed under the child this browser watches as — from the cookie, never the body (guard 60) — so a parent reading the console knows which tablet. The page sends at most five per load and then stops itself, so a loop of errors is a handful of lines and not a flood. Same store and same container-log line as `POST /report` above. Nothing about what was watched travels here. |
| `GET /kid/thumb?u=<url>` | kid cookie | — | the image bytes, `Cache-Control: private, max-age=86400` | Video posters, fetched by the hub. **The page loads no image from Google**: a thumbnail URL is public and a browser would fetch it happily, but that is every child's tablet announcing its IP, its user agent and the timing of every poster on the shelf to Google's CDN, from a product whose proposition is that a family's watching is nobody else's business. The URL is checked against the crawler's own allow-list (`Http.HUB_HOSTS`, guard 7) and must be `https`, before anything is opened — a route that takes a URL from a request is the exact shape of a server-side request forgery. Capped at `MAX_THUMB_BYTES` (512 KB), and a reply whose `Content-Type` is not an image is refused rather than relabelled. **Its own pool** (`THUMB_THREADS`), not the media one: a grid of forty posters would otherwise fill the three stream slots and a child pressing play would be told the hub was busy by their own home screen. |
| `GET /kid/media?v=<id>` | kid cookie | — | the video's bytes, `206` with `Content-Range` when a `Range:` header was sent, `200` otherwise | Moved here from the console's origin, where it was gated on a parent's session as a placeholder: the thing that plays video is a child's browser, and a child's browser must not be on the origin that holds the family's configuration. **There is no `kid=` parameter any more, and there is no parser for one** (guard 60): whose rules apply is a property of the credential, bound when the parent minted the code. A child who could name the kid could name their older sibling and watch on their bedtime, their budget and their block list. Everything else about this route is unchanged and still load-bearing. **The hub carries the bytes; it never redirects.** (1) googlevideo throttles a plain progressive GET — and an RFC-7233 `Range:` header on an un-parameterised URL — to roughly playback speed, while the `range=<start>-<end>` **query** parameter with `rn=` numbering is served at link speed. A browser's `<video>` can only emit the header, so a redirected browser gets the slow path; the hub reads the header and asks upstream in the query form (`StreamChunker` in `:crawl`, shared verbatim with the television's `ChunkedStreamDataSource`, guard 56). The browser's own `Range` header is never forwarded. (2) A redirect is a one-way door: once a child's browser holds a googlevideo URL, Google serves it for hours and nothing a parent does afterwards can reach it. Proxying is what lets `HubPolicy.mayPlay` be asked **again on every 2 MB chunk**, so a block, a pause or a budget hitting zero stops a video that is already playing. `mayPlay` is asked **before anything is resolved** — a refused child must not be able to make this box fetch from YouTube — and its refusal comes back as `403 {error, detail}` carrying the policy's own reason code (`blocked`, `window`, `out-of-time`, `needs-home-zone`, …), because "bedtime" and "a parent blocked this" are different sentences. Serves the **muxed progressive** stream only, which YouTube caps around 360p: HD needs separate video and audio tracks merged at playback, which a plain `<video>` cannot do without MSE or HLS. A video with no muxed stream is `502 {error}` with a reason of its own — `no-muxed-stream`, `no-stream-length`, `age-restricted`, `resolve-failed` — never something unplayable. `Accept-Ranges: bytes` and `Cache-Control: no-store` on every reply; an unparseable `Range` is ignored per RFC 7233 §3.1 and one that cannot be satisfied is a `416` with `bytes */<len>`. **Its own executor**, not the two threads this origin answers everything else on: a stream holds its thread for the length of a video. `HubKidServer.MAX_CONCURRENT_STREAMS` (3) in flight, and past it `503 {error:"busy"}` with `Retry-After` rather than a queue — a queued video never starts and tells the child nothing. The viewer's minutes are filed under a cell derived from the cookie this hub minted (`HubWatchMeter.ledgerId`), never from anything the page sends, so a tablet cannot claim a fresh viewer every morning to reset a budget. |
| `GET /kid` | none | — | the kid page | Served to anyone who asks, because it contains nothing: no name, no video, no configuration, and no rule. Everything a child sees arrives afterwards from `/kid/home`, behind the cookie (the names on the sign-in screen come from `/kid/kids`, above). The page decides nothing — no filter, no sort, no cap lives in it (guard 61) — and draws no colour of its own: every hue and size is a `--yk-*` property from the generated stylesheet or from `/kid/home`'s `theme`. **Only `/kid`** — every other path under it that is not a route above is a JSON 404, including `/kid/index.html`. Anything but GET is 405. The stylesheet it links is `/kid-tokens.css` at the root, one of `HubServer.assets`. |

Every kid reply carries the same three headers every hub reply does —
`X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy: no-referrer` — and these routes face the whole house before
any credential is checked, so they read the way `LanServer` does: the
listener's fixed worker pool, the ten-second request timeout
(`HubServer.applyRequestTimeout`), a 4 KB body cap, and two further pools that
are separate again — one for video and one for posters, because a shelf of
thumbnails must not be able to fill the slots a child's video needs.
