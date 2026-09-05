---
name: yosemite-kids-check
description: Build the Yosemite Kids Android app and run its offline test suite (Kotlin unit tests + Cloudflare Worker tests). Use before committing, after any Kotlin/worker change, or when asked "does it build / do the tests pass".
---

# Yosemite Kids check

Run from the repo root (`yosemite-kids/`). Windows PowerShell:

```powershell
.\scripts\check.ps1            # full: compile + unit tests + worker tests
.\scripts\check.ps1 -Quick     # compile only (~1-2 min warm)
```

POSIX shell: `scripts/check.sh [--quick]`.

## What it does

1. `gradlew --no-daemon -q assembleDebug` — needs JDK 17 and `local.properties`
   with `sdk.dir`. Never `assembleRelease` here: it needs the real keystore.
2. `gradlew :app:testDebugUnitTest` for every test except
   `io.yosemitekids.app.ExtractorSmokeTest` (that one hits live YouTube and belongs
   to the scheduled canary, not the PR gate).
3. `node --test worker/test/*.test.mjs` if Node is installed.

Results: `app/build/test-results/testDebugUnitTest/*.xml`; a failing class has
a `<failure` element. HTML report in `app/build/reports/tests/`.

## Reading a failure

- Kotlin compile errors print as `e: file:///…:line:col message`. Warnings
  (`w:`) are noise unless asked about.
- A test failure names the class; open `app/src/test/java/io/yosemitekids/app/<Class>.kt`.
- `Task 'x' not found` = a wrong Gradle flag, not a code problem.

## Rules

- Do not edit sources while a Gradle build is running — Kotlin compiles a
  snapshot and the errors will be confusing.
- A unit test must stay pure JVM: no `android.content.Context`, no
  SharedPreferences. Pure helpers live in companions/`internal fun`s so they
  are reachable (see `PairingStore.prunePending`, `Backup.parse`).
