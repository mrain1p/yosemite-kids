---
name: yosemite-kids-lan-api
description: Reference and safe-probing rules for Yosemite Kids' phone-to-device LAN HTTP API (LanServer/LanClient in data/Pairing.kt). Use when adding a route, debugging sync/pairing, or testing a device over the network.
---

# Yosemite Kids LAN API

Full route table: `docs/LAN-API.md`. Code: `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`
(`LanServer.handle` is the router, `LanClient` the phone side).

## Non-negotiables

- Never `POST /pair-request` at a device with no admins while its QR is
  showing — the first requester becomes the admin and locks the family out.
- Every read from the socket is bounded (line, headers, body, workers). A new
  route that allocates from request data must keep that: read the body only
  after auth, via `readBody()`, and cap ids/params with a bounded regex.
- Answer with a real status (400/403/404/409/413/429). A "200 merged" for a
  refused body is what made sync bugs invisible.
- Mutating routes are `POST`. Auth is `X-Token` only — no cookies, ever
  (that is what keeps a browser on the LAN from driving it).

## Adding a route (checklist)

1. Branch in `LanServer.handle` below the auth gate.
2. `LanClient` function using the LAN client (1.5 s connect, no retry).
3. Row in `docs/LAN-API.md`; note in `docs/ARCHITECTURE.md` if the UI uses it.
4. If the route touches a store, the store call must be thread-safe: the
   server runs on a worker pool while the UI reads the same files.

## Safe probes

```bash
curl -s "http://IP:8765/pair-status?me=00000000000000000000000000000000"   # {"status":"unknown"}
curl -s -H "X-Token: $TOKEN" "http://IP:8765/status"                          # hash/updatedAt/version
curl -s -H "X-Token: $TOKEN" -X POST "http://IP:8765/player?cmd=pause"
curl -s -H "X-Token: $TOKEN" -X POST -H "Content-Type: application/json" \
  -d '{"url":"https://www.youtube.com/watch?v=ID","title":"t","channel":"c","timePercent":100}' \
  "http://IP:8765/play"
```

`$TOKEN` = the admin phone's `device_token` (`shared_prefs/pairing.xml`).
On the emulator the server is reachable from the host at `127.0.0.1` only
after `adb -s emulator-5554 forward tcp:8765 tcp:8765` (`scripts/emu.ps1 forward`).
