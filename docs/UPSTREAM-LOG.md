
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
