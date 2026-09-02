---
name: pickwick-release
description: Cut a Pickwick release — bump versionCode/versionName, build the signed release APK, publish the GitHub release and point version.json at it. Use only when the user asks to release or tag a version.
---

# Releasing Pickwick (fork)

Self-update in the app reads `version.json` at the repo root of `main`
(`BuildConfig.UPDATE_MANIFEST_URL` in `app/build.gradle.kts`). In a fork,
change that URL and `DIRECTORY_URL` / `SUGGEST_WORKER_URL` to your own repo
before the first release, or installs will update themselves back onto
upstream's builds.

1. Bump both `versionCode` (strictly higher) and `versionName` in
   `app/build.gradle.kts`. Forgetting `versionCode` silently ships nothing.
2. Signing: `PICKWICK_KEYSTORE`, `PICKWICK_KEYSTORE_PASSWORD`,
   `PICKWICK_KEY_ALIAS`, `PICKWICK_KEY_PASSWORD` in `local.properties` or the
   environment. On this machine the fork's key is
   `~/.pickwick/pickwick-fork-release.keystore` (alias `pickwickfork`,
   password in the `.password.txt` beside it) and `local.properties` already
   points at it. The build fails without them on purpose. Losing the key means
   every family must uninstall (wiping curation) — back it up off-machine.
   Never print the password into a transcript.
3. `gradlew assembleRelease` -> `app/build/outputs/apk/release/pickwick.apk`.
   Always the release build: debug is ~10 s cold start on a TV.
4. `gh release create vX.Y.Z app/build/outputs/apk/release/pickwick.apk --title vX.Y.Z --notes "..."`
5. Update `version.json` (`versionCode`, `versionName`, `apkUrl`) and push to `main`.
6. Run `scripts/check.ps1` first; never release with a red check.

Ask the user before steps 4-5: they publish.
