# The crawl in the hub, the phone as fallback

Design record for roadmap section 2H, decided 2026-09-05 from a three-way
design pass (minimal / cleanest / resilience-first; the last two completed,
judged by hand). The cleanest design is the base; the resilience design's
arming rule, backoff and hardening are grafted in. Every claim below was
checked against the code at commit 36fc774; re-read before acting on a line.

**Status, 2026-09-05:** built, in eight commits ("Crawl step 1" … "Crawl step
8"), shipped as 1.0.5. Two things the plan asked for are not yet done and are
listed in `ROADMAP.md` §2H: the `docker stats` measurement of a full crawl,
and the handover watched on the real fleet. What follows is the design as it
was decided, kept as the record of *why*.

## What was true before this (2026-09-05, morning)

- One device holds `config.masterDeviceToken` (Whitelist.kt; JSON key
  `master`; fingerprint term `;MS:`; stamp unit `master`). ConfigSync.sweep
  claims it for the first parent phone that runs a sweep with the slot empty.
  The merge's tie rule is `minOf(mine, theirs)` on the token string.
- IndexCrawlWorker (WorkManager, 15 min, CONNECTED, master-only past a drop
  pass every device runs) crawls 60 pages a run through IndexCrawler into
  ChannelIndex (one JSON per source under filesDir/search-index, plus
  manifest, cursors, probes, last-run). MainViewModel.syncIndex pushes changed
  sources to every paired device over the app's /index routes, only while the
  master phone has the app on screen. Nobody pulls.
- The hub holds config only: no /index routes, no identity of its own (its
  /status carries no token), and guard 7 forbids any outbound connection
  from hub/src outside HubNudge.kt.

## Decisions

1. **A fourth plain-JVM module, `:crawl`**, Android-free and guarded exactly
   like `:core`, holding the ONE crawler: Http, OkHttpDownloader,
   YouTubeRepository, ChannelIndex (File constructor; the Context factory
   stays in :app), IndexCrawler, and the run loop lifted out of the worker
   (`IndexCrawlRun`). Package unchanged (`io.yosemitekids.app.data`, as :core
   already does) so nothing in :app needs an import edit. Not :core: its
   contract is "no disk, no clock" and the crawl is both. Not a copy in :hub:
   a second crawler is the drift CLAUDE.md warns about.
2. **The hub has an identity, and hub-ness is in the token.** `HubTokens`
   mints and persists a self token under `self` in devices.json: `.hub` +
   28 lowercase hex, 32 characters like a device token so nothing that takes
   eight or assumes the length breaks, never used as a credential. /status
   gains `token`. The pure merge can then prefer a hub on a tie without a
   lookup: `MasterToken.preferred(mine, theirs)` = the hub if exactly one is
   a hub, else `minOf` (unchanged for two phones).
3. **Liveness is the master stamp itself.** The holder re-touches
   `sync.at["master"]` every 6 h without changing the token (`ConfigStamp`
   gains a `refresh` set; `ConfigStore.update` / `HubStore.edit` pass it and
   stop short-circuiting on an unchanged value). A slot whose stamp is older
   than 24 h is vacant. No new field, no fingerprint term, no per-peer
   last-seen store; the merge stays pure and reads stamps only. Clocks are
   read where they are allowed: ConfigSync.sweep on devices, HubMaster on
   the hub, always passed in so tests drive them. This also vacates a token
   no live device holds (the dead-master case the rename exposed).
4. **Election rules** (`:core` `MasterElection.decide(config, me, isHub, now)`
   -> claim / heartbeat / nothing): a hub claims a vacant slot, or one held
   by a phone, but only when *armed* (a device asked it for the index within
   24 h: `GET /index-status` carrying `X-Index-Pull: 1`, recorded in
   devices.json). A phone claims only a vacant slot and never one held by a
   live hub. Whoever holds the slot heartbeats. The arming rule makes the
   rollout order-independent: a hub nobody's devices can pull from never
   takes the slot away from the phone that is still doing the work.
5. **Devices pull; the hub never pushes.** The hub keeps no credential on any
   device (guard 7's reason survives the rewrite). Hub routes, authorised
   like the rest: `GET /index-status` (byte-for-byte ChannelIndex.statusJson)
   and `GET /index?source=<id>` (exportSourceWithState; id regex
   `[A-Za-z0-9_-]{1,64}`; GET only). On the device, `IndexPull.pull(hub)`
   runs after the config arm of every sweep: compare hashes and the complete
   flag, fetch what differs, import by union so a source the device crawled
   further is not truncated. The master phone's push relay stays for TVs on
   builds older than the first pulling build.
6. **Guard 7 becomes an allow-list, in both scripts, negative-tested.** The
   hub may reach exactly two things: YouTube (the crawl, through Http with a
   host allow-list armed at startup; DoH bypassed so 1.1.1.1 is not a silent
   third host) and the devices' `/sync-now` (HubNudge). The scan covers
   hub/src and crawl/src; `OkHttpClient` matching `HttpClient` is why the
   old substring scan cannot simply be extended.
7. **The hub crawls** on a scheduler thread of its own (HubCrawl): the same
   IndexCrawlRun, same 60 pages, same 4 s pacing, plus exponential backoff
   on consecutive YouTube failures (a bot-walled NAS IP must not hammer),
   and a probe-before-claim so a hub that cannot reach YouTube never takes
   the slot. `mem_limit: 384m` in compose; measure with `docker stats` on
   the first full crawl and write the number into HUB.md.
8. **Explained where it shows.** The Search index card names who builds the
   index and why ("The hub builds the index because it is always on; this
   phone takes over if the hub is off for a day"), shows the last pull, and
   the hub row carries a badge; "Make master" hides while a token-advertising
   hub is paired. The hub GUI's Devices page shows sources / complete /
   videos / last run / master.

## Steps, each independently gate-able

1. Module skeleton and gate plumbing: `:crawl` with an empty source set;
   settings.gradle.kts; crawl/build.gradle.kts (kotlin.jvm 17, :core, api
   newpipeextractor + okhttp + coroutines, compileOnly json, junit; the
   EXTRACTOR_VERSION stamp generated from libs.versions.toml into a
   source set so the app's BuildConfig field can go); app and hub depend on
   it; guards extended (Android-import and plugin scans cover crawl; `:crawl`
   must not depend on `:app`; `:core` must not depend on `:crawl`; test
   glob, doc-path haystack, guard 8 haystack, a `:crawl:test` gate step);
   build.yml, hub-image.yml paths, Dockerfile COPY lines, .dockerignore
   allow-list (guard 13). Gate green with nothing moved.
2. Move the network layer and the repository (git mv, package unchanged);
   `Extractor.init()` shared by app, tests and hub; Http host allow-list;
   rewrite guard 7; negative-test every clause.
3. Move the index and the crawler; lift the loop into IndexCrawlRun; the
   worker becomes a shell around it; move IndexCompletenessTest and
   ChannelIndexMergeTest; add IndexCrawlRunTest. Emulator: the master phone
   still crawls.
4. The pure rules in :core: MasterToken, MasterElection (now passed in;
   6 h / 24 h consts), the merge's tie rule, `refresh` in ConfigStamp,
   ConfigStore.update and HubStore.edit. Tests for each, plus reclaim and
   heartbeat sequences in MergeConvergenceTest.
5. Hub identity and the serving routes; `token` on /status;
   LanClient.fetchIndexSource; HubServerTest and HubIntegrationTest rounds;
   LAN-API.md rows.
6. The hub crawls and claims: HubMaster (ticker), HubCrawl (scheduler,
   backoff, probe), arming via the pull header, wiring in Main, HubWeb
   state and the GUI page, SettingsSurface's search-index group to
   Where.BOTH with new why-text, compose mem_limit. HubMasterTest,
   HubCrawlTest. Build the image once and watch docker stats.
7. The device side: ConfigSync.sweep runs MasterElection.decide and the
   pull; ConfigSyncWorker, MainActivity and MainViewModel pass a
   ChannelIndex; the legacy relay branch in syncIndex. Tests; emulator TV
   pulling from a hub run on this PC from hub/build/install.
8. UI, docs, roadmap: the wording in decision 8; HUB.md, ARCHITECTURE.md,
   FORK-NOTES.md, DEV.md; delete roadmap 2H and its anchor row; bump both
   version numbers; release; rebuild the hub.

## Risks named, with the mitigation in the change

- Hub heap: ChannelIndex rewrites a whole source file per page; a 30k-video
  source is ~8 MB of JSON. mem_limit 384m and a measured first crawl;
  fallback is a streaming append for the history path.
- Clock skew over 24 h between a phone and the hub flips the slot daily:
  self-healing (two merges, one phone run) but visible; ConfigStamp.clockOff
  already names the condition for a warning line.
- YouTube bot-walling the NAS IP: backoff holds, the Devices page shows the
  red last-run line, and the hub keeps the slot while it answers /status.
  HUB.md says what to do (stop the hub crawl; the phone resumes within a
  day) and the hub GUI's index block shows the failure.
