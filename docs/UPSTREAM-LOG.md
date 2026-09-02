
## 2026-09-02 — upstream/main 30ef89f, 4 new commit(s)

Upstream version.json: `{ "versionCode": 29, "versionName": "0.8.0", "apkUrl": "https://github.com/itcon-pty-au/pickwick/releases/download/v0.8.0/pickwick.apk"}`

Upstream extractor: `newpipeextractor = "v0.26.4"
newpipeextractor = { group = "com.github.TeamNewPipe", name = "NewPipeExtractor", version.ref = "newpipeextractor" }`

| Commit | Subject | Files | Touches fork files | Action |
| --- | --- | --- | --- | --- |
| `fd5952e` | Send Anthropic's version header on every call, not just the retry | 2 | no | applied (cherry-pick -n, 2026-09-02) |
| `1ae4cf1` | Give each kid a page, and settings a hub to find it in | 10 | ⚠️ app/build.gradle.kts app/src/main/java/io/pickwick/app/data/ConfigStore.kt app/src/main/java/io/pickwick/app/data/Whitelist.kt app/src/main/java/io/pickwick/app/ui/MainViewModel.kt app/src/main/java/io/pickwick/app/ui/Settings.kt  | port by hand: kid page + settings hub (fork rewrote Settings/MainViewModel) |
| `03981b9` | Point version.json at v0.8.0 | 1 | no | skip — upstream release chore; fork versionCode bumped to 30 to stay above 29 |
| `30ef89f` | Bring the docs up to what the app actually does | 4 | no | skip — upstream docs/site |
