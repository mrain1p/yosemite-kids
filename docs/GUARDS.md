# The guards

Every rule `scripts/check.sh` and `scripts/check.ps1` enforce on the source,
one row each. **Generated** by `scripts/guard-index.sh` from the numbered
headings in `check.sh`; guard 67 fails the gate when this file is behind them.
Regenerate with:

```bash
bash scripts/guard-index.sh > docs/GUARDS.md
```

The paragraph explaining each rule is beside the guard itself: open
`scripts/check.sh` and search for `# <number>.`. *Reads* lists the files the
guard inspects, so a change to one of them tells you which guards to expect.
*Canary* says whether `scripts/guard-canary.sh` has a case proving the guard
can still fail (guard 65 requires one from 56 upward).

| # | Rule | Reads | Canary |
| --- | --- | --- | --- |
| 1 | Every field the settings form writes is claimed by some group. | - | - |
| 2 | Every settings composable is declared. | `app/src/main/java/io/yosemitekids/app/ui/*.kt` | - |
| 3 | The hub serves exactly the pages the phone navigates. | - | - |
| 4 | The reconcile stays runnable without a UI. | `app/src/main/java/io/yosemitekids/app/data/ConfigSync.kt` | - |
| 5 | One copy of what happens when a config arrives. | `app/src/main/java` | - |
| 7 | The hub may reach exactly two things: YouTube, for the crawl, and the devices' /sync-now, for the nudge. | `hub/src`, `hub/src/main/kotlin`, `hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt`, `crawl/src/main/kotlin`, `hub/src/main/kotlin/io/yosemitekids/hub/Main.kt`, `crawl/src/main/kotlin/io/yosemitekids/app/data/Http.kt` | yes |
| 6 | A worker that nothing schedules is dead code that reads as shipped. | `app/src/main/java/io/yosemitekids/app/ui/MainActivity.kt` | - |
| 8 | The roadmap must not outlive the code it points at. | `docs/ROADMAP.md`, `app/src`, `core/src`, `crawl/src`, `hub/src` | - |
| 9 | The AI API key must never reach cloud backup. | `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`, `app/src/main/java/io/yosemitekids/app/data/SecretStore.kt` | yes |
| 10 | The two gate scripts must declare the same guards. | `scripts/check.sh`, `scripts/check.ps1` | - |
| 11 | Every page the hub serves must have something to render. | `hub/src/main/resources/web/index.html`, `app/src/test/java/io/yosemitekids/app`, `core/src/test/kotlin/io/yosemitekids/app`, `crawl/src/test/kotlin/io/yosemitekids/app` | - |
| 12 | MainViewModel's working init block sits below every property. | `app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt` | - |
| 13 | The hub's build context is an allow-list. | `hub/Dockerfile` | - |
| 14 | Every route LanServer answers has a row in docs/LAN-API.md. | `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`, `docs/LAN-API.md` | - |
| 15 | The settings form adopts the whole of what it saved. | `app/src/main/java/io/yosemitekids/app/ui/Settings.kt` | - |
| 16 | Today's bonus minutes come from two stores, each read in exactly one place. | `app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt` | - |
| 17 | One crawler, one version stamp. | `app/build.gradle.kts`, `app/src/main/java` | - |
| 18 | The mirror must at least parse. | `scripts/check.ps1` | - |
| 19 | The hub's service worker caches the shell and never the family. | `hub/src/main/resources/web/sw.js` | - |
| 20 | Every asset the GUI names is actually served. | `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt`, `hub/src/main/resources/web/manifest.webmanifest`, `hub/src/main/resources/web/index.html` | - |
| 21 | A grant that arrives in the config has to be applied by the device that receives it. | `app/src/main/java/io/yosemitekids/app/data/ConfigSync.kt`, `app/src/main/java` | yes |
| 22 | The hub answers a device's routes, or refuses them by name. | `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt`, `app/src/main/java/io/yosemitekids/app/data/Pairing.kt` | - |
| 23 | The browser mints no identifiers. | `hub/src/main/resources/web/index.html`, `hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt` | - |
| 24 | A device tells the hub who it is, and the hub reads the same header. | `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt` | - |
| 25 | One door to the hub's admin secret, and nothing that prints it. | `hub/src/main/kotlin` | - |
| 26 | Parity per CONTROL, not per group. | `core/src/main/kotlin/io/yosemitekids/app/data/Whitelist.kt`, `app/src/main/java/io/yosemitekids/app/ui` | - |
| 27 | The hub reads the family's calendar, and never its own. | `hub/src`, `hub/src/main` | - |
| 28 | One backup envelope, because two faces write it and two faces read it. | `core/src/main/kotlin/io/yosemitekids/app/data/BackupFile.kt`, `app/src/main`, `core/src/main`, `crawl/src/main`, `hub/src/main` | - |
| 29 | A device route the hub answers is authenticated, every time. | `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt`, `app/src/main/java/io/yosemitekids/app/data/Pairing.kt` | yes |
| 30 | Every route the HUB registers has a row in docs/LAN-API.md too. | `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt`, `docs/LAN-API.md` | - |
| 31 | The form factor is decided in exactly one place. | `app/src/main`, `app/src/main/java/io/yosemitekids/app/data/Pairing.kt`, `app/src/main/java/io/yosemitekids/app/ui/FormFactor.kt` | - |
| 32 | Containers read LocalFormFactor; leaves take a parameter. | `app/src/main` | - |
| 33 | Every shelf the home screen names is a shelf the home can draw. | `core/src/main/kotlin/io/yosemitekids/app/ui/HomeSections.kt`, `app/src/main/java/io/yosemitekids/app/ui/HomeShelves.kt` | - |
| 34 | The chrome's time-left ticks by interpolation, never by re-reading. | `app/src/main/java/io/yosemitekids/app/ui/TimeLeft.kt` | - |
| 35 | A focus modifier only sees what comes AFTER it in the chain. | `app/src/main`, `app/src/main/java/io/yosemitekids/app/ui/TvNavRail.kt` | - |
| 36 | No colour literal on a kid-facing screen. | `app/src/main/java/io/yosemitekids/app/ui/*.kt` | - |
| 37 | The player has no focusables. | `docs/SCREENS.md`, `app/src/main/java/io/yosemitekids/app/ui/Player*.kt` | - |
| 38 | Every unit ConfigStamp can mint is a unit ConfigMerge.merge decides. | `core/src/test`, `core/src/main/kotlin/io/yosemitekids/app/data/ConfigStamp.kt`, `core/src/main/kotlin/io/yosemitekids/app/data/ConfigMerge.kt` | - |
| 39 | The hub advertises the project's version, not one of its own. | `.github/workflows/hub-image.yml`, `hub/build.gradle.kts`, `app/build.gradle.kts` | - |
| 40 | The hub's service worker evicts only the caches it owns. | `hub/src/main/resources/web/sw.js` | - |
| 41 | Every response the hub makes carries the baseline security headers. | `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt` | yes |
| 42 | One place mints a pinned card, and one number is the row's ceiling. | `app/src/main`, `hub/src/main`, `core/src/main/kotlin/io/yosemitekids/app/ui/HomeSections.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt` | - |
| 43 | One spelling of a family day. | `core/src/main/kotlin/io/yosemitekids/app/data/FamilyDay.kt`, `core/src/main`, `app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt`, `app/src/main/java/io/yosemitekids/app/data/Stats.kt` | - |
| 44 | The watch ledger is a counter, and a counter is not curation. | `.claude/skills/yosemite-kids-sync`, `core/src/main/kotlin/io/yosemitekids/app/data`, `core/src/main/kotlin/io/yosemitekids/app/data/UsageLedger.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubStore.kt` | - |
| 45 | One reader of the daily tally. | `app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt` | - |
| 46 | One answer to "may this child see this video". | `crawl/src/main/kotlin/io/yosemitekids/app/data/Screening.kt`, `app/src/main` | - |
| 47 | One home, one shelf model. | `core/src/main/kotlin/io/yosemitekids/app/ui/HomeSections.kt`, `app/src/main`, `core/src/main/kotlin/io/yosemitekids/app/ui/*.kt`, `app/src/main/java/io/yosemitekids/app/ui`, `core/.../ui`, `app/.../ui` | - |
| 48 | The kid's colours and type scale are generated for the browser, never retyped for it. | `core/src/main/kotlin/io/yosemitekids/app/ui/DesignTokens.kt`, `hub/src`, `hub/build.gradle.kts`, `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt`, `app/src/main/java/io/yosemitekids/app/ui/KidTokens.kt`, `app/src/main/java/io/yosemitekids/app/ui/Theme.kt` | - |
| 49 | One answer to "does this kid share a budget", and the ledger never authors the shared number. | `core/src/main/kotlin/io/yosemitekids/app/data/Whitelist.kt`, `app/src/main`, `core/src/main`, `crawl/src/main`, `hub/src/main`, `app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt` | - |
| 50 | The day's allowance is computed in exactly one place. | `core/src/main/kotlin/io/yosemitekids/app/data/Budget.kt`, `app/src/main`, `core/src/main`, `crawl/src/main`, `hub/src/main` | yes |
| 51 | A child meets one vocabulary, not one per face. | `app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt`, `core/.../ui/KidWords.kt` | yes |
| 52 | A channel's own words reach a child with every way out already gone. | `crawl/src/main/kotlin/io/yosemitekids/app/data/YouTubeRepository.kt`, `app/src/main`, `crawl/src/main` | - |
| 53 | One key derivation in the product, at one cost. | `core/src/main/kotlin/io/yosemitekids/app/data/PasswordRecord.kt`, `app/src/main`, `core/src/main`, `crawl/src/main`, `hub/src/main`, `app/src/main/java/io/yosemitekids/app/data/SettingsStore.kt`, `core/.../data/PasswordRecord.kt` | yes |
| 55 | Every way into the little window asks pipEligible() first, and the gesture that opens it can be argued with on a laptop. | `app/src/main/java/io/yosemitekids/app/ui/PlayerActivity.kt`, `app/src/main/java/io/yosemitekids/app/ui/PlayerGestures.kt`, `app/src/test/java/io/yosemitekids/app/PlayerDismissTest.kt` | - |
| 56 | One implementation of the range arithmetic, and the browser's header stops at the hub. | `crawl/src/main/kotlin/io/yosemitekids/app/data/StreamChunker.kt`, `app/src/main`, `core/src/main`, `crawl/src/main`, `hub/src/main`, `crawl/src/test/kotlin/io/yosemitekids/app/StreamChunkerTest.kt`, `crawl/src/test/.../StreamChunkerTest.kt` | yes |
| 57 | The kid app lives under /kid on the hub's one origin, and nothing else answers there. | `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubKidServer.kt`, `docs/LAN-API.md`, `hub/Dockerfile`, `hub/docker-compose.yml`, `hub/src/main/kotlin`, `docs/HUB.md`, `docs/SETUP.md`, `hub/src/main`, `hub/src/main/kotlin/io/yosemitekids/hub/HubTokens.kt` | yes |
| 58 | Neither origin ever tells a browser it may read the other. | `hub/src/main` | yes |
| 59 | No credential is ambient across the wall, and a child's wrong password cannot lock their parent out. | - | yes |
| 60 | A kid route fails closed, and the child it plays as comes from the credential. | `hub/src/main` | yes |
| 61 | The kid page draws; it does not decide. | `hub/src/main/resources/web/kid.html`, `hub/src/main/kotlin/io/yosemitekids/hub/HubKidHome.kt`, `core/src/main/kotlin/io/yosemitekids/app/ui/HomeSections.kt` | yes |
| 62 | Every kid-facing surface is declared, and the browser's gaps are named. | `core/src/main/kotlin/io/yosemitekids/app/ui/KidSurface.kt`, `app/src/main/java/io/yosemitekids/app/ui/HomeState.kt`, `hub/src/main/resources/web/kid.html`, `core/src/main`, `crawl/src/main`, `app/src/main` | yes |
| 63 | The two faces draw a card from ONE set of numbers. | `core/src/main/kotlin/io/yosemitekids/app/ui/DesignTokens.kt`, `app/src/main/java/io/yosemitekids/app/ui/Tiles.kt`, `core/src/main/kotlin/io/yosemitekids/app/ui/KidTokensCss.kt`, `app/src/main`, `hub/src/main`, `crawl/src/main`, `core/src/main` | yes |
| 64 | The page does not move under the child's thumb. | `app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt`, `app/src/main/java/io/yosemitekids/app/ui/VideoGrid.kt` | yes |
| 65 | A guard is not finished until something proves it can fail. | `scripts/guard-canary.sh`, `scripts/check.sh` | yes |
| 66 | Finished plans live in docs/archive, and docs/ holds only what is live. | `docs/archive`, `docs/ROADMAP.md`, `docs/PLAN-*.md` | yes |
| 67 | The guard index is generated, and it is current. | `docs/GUARDS.md`, `scripts/guard-index.sh` | yes |
| 68 | What goes wrong on a device reaches the hub, and nothing goes wrong silently. | `app/src/main/java/io/yosemitekids/app/data/Diag.kt`, `app/src/main/java`, `app/.../data/Diag.kt`, `app/src/main/java/io/yosemitekids/app/YosemiteKidsApp.kt`, `app/src/main/java/io/yosemitekids/app/data/ConfigSync.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubReports.kt`, `app/src/main/java/io/yosemitekids/app/ui`, `app/.../ui` | yes |
| 69 | A setting a child's screen obeys is obeyed on every face that draws it, or says why not. | `core/src/main/kotlin/io/yosemitekids/app/data/SettingsSurface.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubKidHome.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubKidServer.kt` | yes |
| 70 | One place arranges a home screen's rows, and every face draws the one list. | `core/src/main/kotlin/io/yosemitekids/app/data/HomeRows.kt`, `app/src/main`, `hub/src/main`, `hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt`, `hub/src/main/kotlin/io/yosemitekids/hub/HubKidHome.kt`, `app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt` | yes |
| 71 | The channel page's rails hold their slot. | `app/src/main/java/io/yosemitekids/app/ui/PlaylistShelves.kt`, `app/src/main/java/io/yosemitekids/app/ui/YosemiteScreen.kt` | yes |
| 72 | The crawl runs at the pace the numbers say, on every box. | `crawl/src/main/kotlin/io/yosemitekids/app/data/IndexCrawlRun.kt`, `crawl/src/main/kotlin/io/yosemitekids/app/data/PlaylistCrawlRun.kt`, `crawl/src/test/kotlin/io/yosemitekids/app/CrawlPacingTest.kt`, `hub/src/main`, `app/src/main`, `hub/src/main/kotlin/io/yosemitekids/hub/HubCrawl.kt` | yes |
| 73 | A heart pressed on the You tab redraws the You tab, on every face. | `app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt`, `hub/src/main/resources/web/kid.html` | yes |
| 74 | Both pages a family touches declare the same floor under a tap target. | `hub/src/main/resources/web/kid.html`, `hub/src/main/resources/web/index.html` | yes |
| 75 | A reply from somewhere else is read with a limit on it. | `app/src/main`, `crawl/src/main`, `core/src/main`, `hub/src/main` | yes |
| 76 | The map names every file it is a map of. | `docs/ARCHITECTURE.md`, `app/src/main`, `core/src/main`, `crawl/src/main`, `hub/src/main` | yes |
| 77 | A test that reaches real YouTube says so in its own first lines. | `app/src/test/java` | yes |
