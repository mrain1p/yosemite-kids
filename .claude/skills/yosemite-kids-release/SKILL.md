---
name: yosemite-kids-release
description: Cut a Yosemite Kids release — bump versionCode/versionName, build the signed release APK, publish the GitHub release and point version.json at it. Use only when the user asks to release or tag a version.
---

# Releasing Yosemite Kids (fork)

Self-update in the app reads `version.json` at the repo root of `main`
(`BuildConfig.UPDATE_MANIFEST_URL` in `app/build.gradle.kts`). In a fork,
change that URL and `DIRECTORY_URL` / `SUGGEST_WORKER_URL` to your own repo
before the first release, or installs will update themselves back onto
upstream's builds.

0. **Check upstream first.** Run `scripts/upstream.ps1` (or the
   `yosemite-kids-upstream` skill) and act on anything new before cutting the
   release. An extractor bump shipped a week late is a week of families whose
   playback is broken. If there is nothing new, the script says so and this
   step costs seconds.
1. Bump both `versionCode` (strictly higher) and `versionName` in
   `app/build.gradle.kts`. Forgetting `versionCode` silently ships nothing.
   Then set `val hubVersion` in `hub/build.gradle.kts` to the same
   `versionName` — guard 39 fails the gate until you do. That is not
   bookkeeping: the hub advertises it on `GET /health`, config fields ride a
   two-release gate that is counted against it, and bumping it is what makes
   the release rebuild the hub image (the workflow watches `hub/**`).
2. **Gate now — after the bump, not before it.** `scripts/check.ps1` must print
   the literal `all green`. This step used to be last on this list and said
   "run this first", which is how 1.2.0 shipped with the hub advertising
   1.1.0: the gate was run, *then* the version was bumped, and guard 39 —
   which exists precisely to hold `hubVersion` equal to the app's
   `versionName` — never saw the change that broke it. A version bump is a
   code change like any other. Gate it.

   Read the output for `all green`; do not trust the exit code. A PowerShell
   parse error in `check.ps1` exits 0 while running nothing.

3. Signing: `YOSEMITE_KIDS_KEYSTORE`, `YOSEMITE_KIDS_KEYSTORE_PASSWORD`,
   `YOSEMITE_KIDS_KEY_ALIAS`, `YOSEMITE_KIDS_KEY_PASSWORD` in `local.properties` or the
   environment. On this machine the fork's key is
   `~/.pickwick/pickwick-fork-release.keystore` (alias `pickwickfork`,
   password in the `.password.txt` beside it) and `local.properties` already
   points at it. The build fails without them on purpose. Losing the key means
   every family must uninstall (wiping curation) — back it up off-machine.
   Never print the password into a transcript.
4. `gradlew assembleRelease` -> `app/build/outputs/apk/release/yosemite-kids.apk`.
   Always the release build: debug is ~10 s cold start on a TV.
5. `gh release create vX.Y.Z app/build/outputs/apk/release/yosemite-kids.apk --title vX.Y.Z --notes "..."`
6. Update `version.json` (`versionCode`, `versionName`, `apkUrl`) and push to `main`.

Ask the user before steps 5-6: they publish.

## The gh default-repo trap

This checkout has two remotes, `origin` (the fork) and `upstream`
(itcon-pty-au/pickwick), and no default set for `gh`. Without `-R`, the
`gh release` commands resolved to upstream: `gh release create` failed with
a misleading "workflow scope may be required" (it was a 403 on a repo we
cannot push to) and `gh release view` said "release not found" for a release
that existed. Pass `-R mrain1p/yosemite-kids` on every `gh release` call, or
`gh api repos/mrain1p/yosemite-kids/...`, which is explicit by construction.
