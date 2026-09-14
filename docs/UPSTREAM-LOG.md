
## 2026-09-02 — upstream/main 30ef89f, 4 new commit(s)

Upstream version.json: `{ "versionCode": 29, "versionName": "0.8.0", "apkUrl": "https://github.com/itcon-pty-au/pickwick/releases/download/v0.8.0/pickwick.apk"}`

Upstream extractor: `newpipeextractor = "v0.26.4"
newpipeextractor = { group = "com.github.TeamNewPipe", name = "NewPipeExtractor", version.ref = "newpipeextractor" }`

| Commit | Subject | Files | Touches fork files | Action |
| --- | --- | --- | --- | --- |
| `fd5952e` | Send Anthropic's version header on every call, not just the retry | 2 | no | applied (cherry-pick -n, 2026-09-02) |
| `1ae4cf1` | Give each kid a page, and settings a hub to find it in | 10 | ⚠️ app/build.gradle.kts app/src/main/java/io/pickwick/app/data/ConfigStore.kt app/src/main/java/io/pickwick/app/data/Whitelist.kt app/src/main/java/io/pickwick/app/ui/MainViewModel.kt app/src/main/java/io/pickwick/app/ui/Settings.kt  | applied (cherry-pick, 2026-09-02): hub + kid page + autosave + per-kid pause + min video length; fork additions re-placed on the Playback page, short-video rule extended to the feed/channel row/history |
| `03981b9` | Point version.json at v0.8.0 | 1 | no | skip — upstream release chore; fork versionCode bumped to 30 to stay above 29 |
| `30ef89f` | Bring the docs up to what the app actually does | 4 | no | skip — upstream docs/site |

## 2026-09-03

No new upstream commits. `upstream/main` is still at `30ef89f`, which
`docs/.upstream-seen` already records, and every commit above has been
adopted, ported or consciously skipped.

Re-verified by hand rather than trusting the marker, because the round-5 port
was done by hand and a hand port is exactly where something gets missed:

- `fd5952e` (Anthropic version header) — present. `ANTHROPIC_VERSION` and all
  three call sites are in `AiScreener.kt`, and the `Log.w` that keeps the
  provider's whole reply is in `SettingsAi.kt`.
- `1ae4cf1` (kid page + settings hub) — present as `ui/KidPage.kt`, with the
  fork's own additions re-placed around it.
- `03981b9`, `30ef89f` — upstream release chore and upstream docs. Still skip.

Upstream extractor: `v0.26.4`, same as the fork. Upstream `version.json` is
`versionCode` 29; the fork is at 38, so self-update ordering is safe.

From this round on the check is also a **routine**, not just a skill:

- `scripts/upstream.ps1` / `.sh` unchanged — still the thing that does the work.
- The `pickwick-release` skill now opens with an upstream check as step 0, so
  no release can be cut without one.
- A weekly scheduled task (`pickwick-upstream-check`, Mondays) runs the script,
  triages anything new into this log, and reports whether an APK rebuild is
  warranted. It never pushes and never touches a device.

## Port cost of the sync work (2026-09-03)

The sectioned merge lands in files the fork has already rewritten —
`ConfigStore.kt`, `Pairing.kt`, `MainViewModel.kt`, `Settings.kt` — plus four
new ones (`ConfigMerge.kt`, `ConfigStamp.kt`, `SyncDecision.kt`,
`SyncNotices.kt`) that upstream has no counterpart for.

Consequence for future upstream tracking: any upstream commit touching
`ConfigStore.toJson`/`fromJson`/`fingerprint`, or `POST /config`, is now a
**port by hand**, never a cherry-pick. The parts most likely to collide are the
serializer's append-only-when-set discipline and the `/status` body.

Deliberately kept compatible so the port stays cheap: `fingerprint` is
untouched, the `sync` key is additive and ignorable, and `POST /config` still
answers 400 on an unreadable body. An upstream device and a fork device remain
interoperable in both directions, with the one documented gap that deletes do
not cross a legacy hop.

## 2026-09-14 — upstream/main 9cee4fd, 5 new commit(s)

Upstream version.json: `{ "versionCode": 30, "versionName": "0.8.1", "apkUrl": "https://github.com/itcon-pty-au/pickwick/releases/download/v0.8.1/pickwick.apk"}`

Upstream extractor: `newpipeextractor = "v0.26.4"
newpipeextractor = { group = "com.github.TeamNewPipe", name = "NewPipeExtractor", version.ref = "newpipeextractor" }`

| Commit | Subject | Files | Touches fork files | Action |
| --- | --- | --- | --- | --- |
| `e0ffd7a` | Let repeated grants stack the bedtime pass | 2 | ⚠️ app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt  | ported by hand (a6aeea0, 2026-09-14) — same bug here; `extendPass` in the companion plus `WindowPassTest` |
| `4e0d328` | Let a parent take bonus minutes back, and show today's bar on the kid page | 10 | ⚠️ app/src/main/java/io/yosemitekids/app/data/KidNotices.kt app/src/main/java/io/yosemitekids/app/data/Pairing.kt app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt app/src/main/java/io/yosemitekids/app/data/Stats.kt app/src/main/java/io/yosemitekids/app/ui/MainActivity.kt app/src/main/java/io/yosemitekids/app/ui/SettingsScreenTime.kt app/src/main/java/io/yosemitekids/app/ui/StatsScreen.kt app/src/main/java/io/yosemitekids/app/ui/Theme.kt  | deferred, shape written down (ROADMAP §8E) — the `GET /stats?profile=` half is already in the fork; the take-back half does not port, see below |
| `ef1d8fc` | Say when today's numbers can't be fetched instead of showing nothing | 1 | ⚠️ app/src/main/java/io/yosemitekids/app/ui/SettingsScreenTime.kt  | deferred with `4e0d328` — it is the empty state of that commit's bar, and the fork has no bar to say it about |
| `69f59d6` | Release v0.8.1 with parent control over incomplete screening | 11 | ⚠️ app/build.gradle.kts app/src/main/java/io/yosemitekids/app/data/ConfigStore.kt app/src/main/java/io/yosemitekids/app/data/DeepCheck.kt app/src/main/java/io/yosemitekids/app/ui/PlayerActivity.kt app/src/main/java/io/yosemitekids/app/ui/Settings.kt app/src/main/java/io/yosemitekids/app/ui/SettingsAi.kt app/src/test/java/io/yosemitekids/app/DeepCheckTest.kt docs/SETUP.md  | ported by hand (6aae46e, 2026-09-14) — the feature, not the release chore; three fork-shaped differences recorded in FORK-NOTES |
| `9cee4fd` | Point in-app updates at v0.8.1 | 1 | ⚠️ version.json  | skip — upstream release chore; the fork's `version.json` points at its own releases and its versionCode is far above 30 |

**The extractor is not the reason to hurry this round:** upstream is on
`v0.26.4` and so is the fork. Nothing here is a playback fix.

**Why `4e0d328` is deferred rather than ported.** Upstream's take-back
subtracts minutes over a new `POST /takeback`, which reaches only the devices
that are awake. The fork moved grants into the config in 1.0.x precisely
because a television asleep at the tap must still find the minutes when it
wakes — so a LAN-only undo would be a hole in the one property the fork
rebuilt that path to have. The fork's undo is the removal of a `grant|<id>`
unit, which the merge already understands; what it needs first is four
decisions (which grant a "take back 15" removes, whether partial take-backs
exist, what the hub console offers, and the kid's notice), and those are the
owner's, not a scheduled task's. Written down in ROADMAP §8E with the shape.

The `GET /stats?profile=` half of that commit is already in the fork:
`LanServer` has taken a `profileId` on `/stats` and passed it to
`statsProvider` since the profiles work, so a shared TV showing a sibling
already answers for the kid asked about.
