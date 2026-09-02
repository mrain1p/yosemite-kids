# Pickwick LAN API

Every device runs `LanServer` (`data/Pairing.kt`) on the first free port in
**8765–8775**, plain HTTP on the home network. A phone finds a device by
scanning the QR its settings screen shows (`pickwick://pair?name=…&host=…&port=…`)
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
| `POST /pair-approve?token=` | admin | — | `approved` | |
| `POST /pair-deny?token=` | admin | — | `denied` | |
| `GET /admins` | admin | — | `[{token,name}]` | Raw tokens: all admins are equal, so this exposes nothing an admin couldn't already do. |
| `POST /admin-revoke?token=` | admin | — | `revoked` | Never yourself. |
| `POST /admin-leave` | admin | — | `left` / `409 last admin` | The calling phone drops its own approval (Unpair). |
| `GET /status` | admin | — | `{hash, updatedAt, token, versionCode, versionName}` | `hash` = config fingerprint; equal hashes = in sync. |
| `GET /config` | admin | — | full config JSON, **including** the AI key | Disaster recovery for a reinstalled phone. |
| `POST /config` | admin | full config JSON | `saved` / `400 bad config` | Validated by `ConfigStore.fromJson`; key stripped before it hits disk; fires `onConfigApplied` (kid notices). |
| `GET /stats[?profile=<8 hex>]` | admin | — | see `Stats.build` | Per-kid when `profile` is given; else the kid on screen. |
| `GET /watchstate` | admin | — | `WatchSync.exportJson` | history, favourites, watch-later, per-kid blocks |
| `POST /watchstate` | admin | same shape | `merged` / `400` | LWW merge per video/url. |
| `GET /verdicts` | admin | — | `ScreeningStore.exportJson` | AI verdicts for the current rules version |
| `POST /verdicts` | admin | same shape | `merged` / `400` | Add-only; deep beats shallow. |
| `GET /index-status` | admin | — | `{sourceId:{count,complete,hash}}` | |
| `GET /index?source=<id>` | admin | — | `{count,newest,complete}\n[…videos]` / 404 | id ≤ 64 chars |
| `POST /index?source=<id>` | admin | same wire format, ≤ 8 MB | `merged` / `400` | Replaces the source file wholesale. |
| `POST /player?cmd=pause\|play` | admin | — | `ok` / `409 nothing playing` | Parent's "come to dinner". |
| `POST /play` | admin | JSON `{url,title,channel,thumb,timePercent}` | `playing` / `409 refused` / `400` | "Play this on the TV". Refused when the video is blocked for the kid on screen. |
| `POST /grant?minutes=1..240[&profile=<8 hex>]` | admin | — | `granted` | Bonus minutes for today; `profile` unvalidated on purpose (may precede the push that introduces the kid). |

## Testing against a real device without breaking pairing

From CLAUDE.md, worth repeating: **never `POST /pair-request` to a device that
has no approved phones while its QR is showing** — you would take the admin
slot. Safe probes:

```bash
curl -s "http://<tv-ip>:8765/pair-status?me=00000000000000000000000000000000"
curl -s -H "X-Token: <approved token>" "http://<tv-ip>:8765/status"
```

The approved token is the *phone's* `device_token` in its `pairing.xml`; on a
debug build read it with `adb shell run-as io.pickwick.app cat shared_prefs/pairing.xml`.

## Adding a route

1. Add the branch in `LanServer.handle` **below** the auth gate unless it must
   be open. Read the body with `readBody()` only after deciding the request is
   worth it, and answer failures with a real status code.
2. Add the matching `LanClient` function (use the LAN client: 1.5 s connect,
   no retries).
3. Document it here and, if the phone UI uses it, in `docs/ARCHITECTURE.md`.
