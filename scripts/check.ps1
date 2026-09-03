# Pickwick pre-commit check: compile, offline unit tests, worker tests.
# Usage: .\scripts\check.ps1 [-Quick]
param([switch]$Quick)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not (Test-Path "local.properties")) {
    Write-Host "local.properties is missing — create it with sdk.dir=<path to Android SDK>" -ForegroundColor Red
    exit 1
}

# --- 0/4 invariants a test cannot state ------------------------------------
# Each of these is a property that has to hold across a whole file, so no
# assertion can pin it. See docs/PLAN-sync.md.
Write-Host "== 0/5 source invariants" -ForegroundColor Cyan

function Fail-Guard($message) {
    Write-Host "guard FAILED: $message" -ForegroundColor Red
    exit 1
}

# The merge must read no clock. That is what makes idempotence and
# associativity structural rather than artifacts that hold only while a test
# freezes the clock, and it is why a TV with a bad RTC cannot drop a parent's
# active pause into the shared document.
$mergeSrc = Get-Content "core\src\main\kotlin\io\pickwick\app\data\ConfigMerge.kt" -Raw
if ($mergeSrc -match "currentTimeMillis|Instant\.now|System\.nanoTime") {
    Fail-Guard "ConfigMerge.kt reads a clock. Take the time as a parameter (see ConfigStamp.stamped)."
}

# Every config write must pass through commit(), which stashes the API key in
# SecretStore and strips it from the bytes. Two write paths means the next one
# added forgets.
$storeLines = Get-Content "app\src\main\java\io\pickwick\app\data\ConfigStore.kt"
$writers = ($storeLines | Select-String -Pattern "writeAtomically\(" -SimpleMatch).Count
if ($writers -gt 2) {
    Fail-Guard "ConfigStore.kt calls writeAtomically outside commit(). Every write goes through commit."
}

# buildCurrentConfig must copy the baseline, never construct a Whitelist: a
# positional constructor silently defaults out any field the form does not
# name, which would erase the sync blob from every save and every push.
$settingsSrc = Get-Content "app\src\main\java\io\pickwick\app\ui\Settings.kt" -Raw
if ($settingsSrc -match "return Whitelist\(") {
    Fail-Guard "Settings.kt constructs a Whitelist. Use baseline.copy(...) so new fields are inherited."
}

# :core is the code the Android app and the Docker hub both run. The moment it
# imports Android the hub cannot build it — and that failure would surface in
# the hub's build, a long way from the edit that caused it.
$androidInCore = Select-String -Path "core\src\main\kotlin\io\pickwick\app\data\*.kt" `
    -Pattern '^import (android|androidx)\.' -List | ForEach-Object { $_.Path }
if ($androidInCore) {
    Fail-Guard ":core imports Android ($androidInCore). It must stay plain JVM so the hub can use it."
}

# The same rule one level up: an Android plugin here would let the above in
# without tripping the import check.
if (Select-String -Path "core\build.gradle.kts" -Pattern 'com\.android|kotlin-android' -Quiet) {
    Fail-Guard ":core applies an Android plugin. It must stay a plain JVM module."
}

# The merge's tests belong in :core. In :app they still pass, but they prove
# the merge works *on Android* — and the hub, the other consumer of the exact
# same code, would be running it with nothing covering it.
foreach ($t in @("ConfigMergeTest", "ConfigStampTest", "ConfigSyncFormatTest", "SyncDecisionTest")) {
    if (-not (Test-Path "core\src\test\kotlin\io\pickwick\app\$t.kt")) {
        Fail-Guard "$t.kt must live in core\src\test — there it covers the hub too, in :app it does not."
    }
}

# The hub must not depend on :app. :app is Android, and the whole reason the
# hub can share this logic is that the shared half was lifted into :core. A
# dependency here would drag the Android SDK into a container build.
if (Select-String -Path "hub\build.gradle.kts" -Pattern 'project\(":app"\)' -Quiet) {
    Fail-Guard ":hub depends on :app. Anything it needs belongs in :core."
}

# The hub answers /status with the keys LanClient.fullStatus parses. The hub
# cannot depend on :app to check that, so the contract lives in a test — and
# this makes sure the test is still there to check it.
if (-not (Test-Path "hub\src\test\kotlin\io\pickwick\hub\HubServerTest.kt")) {
    Fail-Guard "HubServerTest.kt is missing — it pins the /status wire contract with :app."
}

# The gate discovers tests by globbing *Test.kt, in this script, check.sh and
# CI alike. A file named anything else is skipped by all three and looks green.
$misnamed = Get-ChildItem app\src\test\java\io\pickwick\app, core\src\test\kotlin\io\pickwick\app -File |
    Where-Object { $_.Name -notlike "*Test.kt" }
if ($misnamed) {
    Fail-Guard "these test files will never run: $($misnamed.Name -join ', ') — rename to *Test.kt"
}

Write-Host "== 1/5 compile (assembleDebug)" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Host "compile FAILED" -ForegroundColor Red; exit 1 }

if ($Quick) { Write-Host "compile OK (quick mode)" -ForegroundColor Green; exit 0 }

Write-Host "== 2/5 core tests (no Android — the hub runs this exact code)" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q :core:test
if ($LASTEXITCODE -ne 0) { Write-Host "core tests FAILED" -ForegroundColor Red; exit 1 }

Write-Host "== 3/5 hub tests" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q :hub:test
if ($LASTEXITCODE -ne 0) { Write-Host "hub tests FAILED" -ForegroundColor Red; exit 1 }

Write-Host "== 4/5 app unit tests (offline)" -ForegroundColor Cyan
# Every test class except the live-YouTube canaries. Gradle's --tests takes
# patterns, not exclusions, so the list is built from the source tree.
# SingleChannelProbeTest calls ChannelInfo.getInfo with no runCatching and no
# Assume, so a bot wall fails this gate for reasons unrelated to the change
# being checked — the same reason ExtractorSmokeTest has always been out.
$live = @("ExtractorSmokeTest", "SingleChannelProbeTest")
$tests = Get-ChildItem app\src\test\java\io\pickwick\app -Filter *Test.kt |
    Where-Object { $live -notcontains $_.BaseName } |
    ForEach-Object { "--tests"; "io.pickwick.app.$($_.BaseName)" }
& .\gradlew.bat --no-daemon -q :app:testDebugUnitTest @tests
if ($LASTEXITCODE -ne 0) { Write-Host "unit tests FAILED — see app\build\reports\tests\testDebugUnitTest\index.html" -ForegroundColor Red; exit 1 }

Write-Host "== 5/5 worker tests" -ForegroundColor Cyan
if (Get-Command node -ErrorAction SilentlyContinue) {
    & node --test (Get-ChildItem "worker\test\*.test.mjs" | ForEach-Object { $_.FullName })
    if ($LASTEXITCODE -ne 0) { Write-Host "worker tests FAILED" -ForegroundColor Red; exit 1 }
} else {
    Write-Host "node not found — skipping worker tests" -ForegroundColor Yellow
}

Write-Host "all green" -ForegroundColor Green
