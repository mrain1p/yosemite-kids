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

The hub answers `GET /status` and `GET|POST /config` like a device, has
`/enrol`, `/approve`, `/pending`, `/health`, `/setup`, `/password`, `/recovery`
and the admin GUI of its own (see `docs/HUB.md`), and serves the search index.
Guard 14 covers the device table above; these are pinned by `HubServerTest` and
`HubIntegrationTest`.

**Everything else in the device table above is a JSON 404 on the hub.**
`HubServer` registers `"/"` last, so an unknown path lands on the admin page
rather than a status code a parent has to interpret — right for a typo, and a
lie to a device. A phone sweeps `/watchstate`, `/verdicts` and `/stats` across
every paired peer including the hub; all three came back 200 with the page's
HTML, the two mergers parsed it to nothing, and `StatsCache` wrote index.html
into the phone's `files/stats_cache/` on every sweep. The refusal list is
`HubServer.DEVICE_ONLY`, and guard 22 holds it to exactly the routes
`LanServer` answers and the hub does not.

| Route | Auth | Body | Reply | Notes |
| --- | --- | --- | --- | --- |
| `GET /status` | device token | — | as a device, plus `token` and `kind: "hub"` | `token` is the hub's self token (`.hub` + 28 hex, minted once): an identity, never a credential. It is what `config.masterDeviceToken` holds while the hub builds the index, and how a phone backfills `PairedDevice.id`. |
| `GET /setup` | none | — | `{"password": true\|false}` | Whether this hub has been claimed, and **exactly one key** — it tells a LAN peer only what they can already infer from the sign-in form, and nothing they can act on without the container log. It is what decides the phone's field label and which card the GUI shows; a hub older than this route answers the admin page's HTML with a 200, so a caller reads the body and not the status. |
| `POST /password` | `current` in the body | `{current, next}` | `{"ok": true, "recovery": <token>\|null}` | Set the first password or change it. `current` is the password or the recovery token, required **even inside a live session** (that session may be a browser on a kitchen counter). `recovery` is non-null on the first set only, is shown once, and retires the token from the log. 400 `{"error":"short"}` under `HubPassword.MIN_LENGTH`. Every other session is closed; the caller's survives. |
| `POST /recovery` | `current` in the body | `{current}` | `{"token": <24 hex>}` | A fresh recovery token, shown once. The previous one stops working immediately. |

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

And the third, the three routes that are not a config patch at all:

| Route | Auth | Body | Reply | Notes |
| --- | --- | --- | --- | --- |
| `POST /api/grant` | session | `{kid: <8 hex>\|"", minutes: 1..240, date: "yyyy-mm-dd"}` | `{"granted": true\|false, "why": "OK"\|"BAD_KID"\|"BAD_MINUTES"\|"BAD_DATE"}` | Bonus minutes for today. `grants` is deliberately **not** in `HubWeb.PATCHABLE`: a patch replaces the array it names, so a browser could leave an entry out — which the stamper reads as expiry and tombstones for the whole fleet — or send an id already live as a `grant\|<id>` merge key. This route only ever appends, and the hub mints the id with `Profile.newId()`. `date` is the **parent's browser's** local day, refused when more than `HubWeb.GRANT_MAX_DAYS_AWAY` from this container's UTC day and never rewritten to it (guard 27). Blank `kid` means everyone. Always 200: `why` names the refusal, because "that date is nowhere near this box's" and "that is not a number of minutes" send a parent to different places. |
| `GET /api/backup` | session | — | the stored config document wrapped in `BackupFile`'s envelope (`kind`, `schema`, `exportedAt`, `app`, `config`), or 404 before anything has ever been written | The same envelope `Backup.export` writes on the phone, so a file taken off the NAS opens on a phone — which is the day it is wanted. The document travels **verbatim**, sync block included: without the tombstones a restore cannot tell a channel that was deleted from one that was never added. Keyless by construction rather than by a strip here (`HubStore.commit` takes the key out on every write), and `HubBackupTest` asserts that from outside at every depth. The 404 is deliberate: an empty config is a valid one meaning "no channels, no kids, no rules", so handing one out is handing a parent a file that wipes their family. The browser names the file — a date in the filename would need a calendar, and guard 27 says the container reads none. |
| `POST /api/restore` | session | a backup file, or a bare config document | `{"restored": true\|false}` | A **stamped edit**, never a byte copy — see `HubVersions`' KDoc for the four arguments with the merge a byte restore loses. Content comes from the file; every stamp, tombstone, floor and change-feed line comes from the live document, so the rollback outstamps a peer that has edited since instead of being undone by it on the next sweep. `BackupFile.configIn` decides what counts as a backup and refuses everything else, because `{}` parses as a perfectly valid empty config. Always 200: `restored: false` is "that file is not a backup", and nothing was touched. |

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
the family locked out simply fails ten times a window. Guard 25 holds the gate
to one door; see `docs/HUB.md` for what a parent does with it.
| `GET /index-status` | device token | — | `{sourceId:{count,complete,hash}}`, byte-for-byte a device's | `X-Index-Pull: 1` says the caller takes its index from this hub. That **arms** the hub to claim the master slot (`HubTokens.armed`, 24 h window); a plain read arms nothing. |
| `GET /index?source=<id>` | device token | — | `{count,newest,complete}\n[…videos]` / 404 | id `[A-Za-z0-9_-]{1,64}`, else 400. Anything but GET is 405: there is deliberately **no `POST /index` on the hub**. It takes nobody's copy, because a device that could push could truncate a source the hub had crawled further. |
