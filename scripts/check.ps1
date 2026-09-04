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

# Shell scripts must have LF endings. A CRLF script in a container has a
# shebang of "#!/bin/sh<CR>", which the kernel cannot resolve, and the error
# is "not found" for a file that is visibly present. This cost half an hour
# on gradlew during the hub's first container build — and this check runs on
# Windows, which is where such a file is created in the first place.
$crlf = @(git ls-files '*.sh' gradlew) | Where-Object { Test-Path $_ } | Where-Object {
    [System.IO.File]::ReadAllBytes($_) -contains 13
}
if ($crlf) {
    Fail-Guard "CRLF line endings in: $($crlf -join ', ') — a container cannot run these. See .gitattributes."
}

# The hub's container must be able to take ownership of its bind-mounted
# /data. A USER instruction would start it unprivileged, the chown would be
# impossible, and the container would crash-loop on its first write with a
# permission error and no admin token in the log. Privilege is dropped in
# docker-entrypoint.sh instead, after the volume is repaired.
if (Select-String -Path hub/Dockerfile -Pattern '^USER ' -Quiet) {
    Fail-Guard "hub/Dockerfile has a USER line. It must drop privileges in docker-entrypoint.sh, after chowning /data."
}
if (-not (Select-String -Path hub/Dockerfile -Pattern 'docker-entrypoint.sh' -SimpleMatch -Quiet)) {
    Fail-Guard "hub/Dockerfile no longer installs docker-entrypoint.sh — nothing will fix /data ownership or drop root."
}
# The entrypoint must prove the volume is writable, not infer it. Its first
# version chowned and exec'd, assuming a successful chown meant a writable
# directory — on a NAS share of mode 000 with an ACL, it does not, and the
# hub died on its first write in a restart loop. can_write() actually
# creates a file; nothing else is an answer.
if (-not (Select-String -Path hub/docker-entrypoint.sh -Pattern 'can_write' -SimpleMatch -Quiet)) {
    Fail-Guard "hub/docker-entrypoint.sh no longer tests writability. A chown that succeeds does not mean the volume is writable."
}

# A doc path named in source is a promise. Renaming the doc leaves the
# pointer behind, and the place it is read is a container log at 3am.
$docRefs = Get-ChildItem -Recurse -File app/src, core/src, hub/src, scripts |
    Select-String -Pattern 'docs/[A-Za-z0-9_.-]+[.]md' -AllMatches |
    ForEach-Object { $_.Matches.Value } | Sort-Object -Unique
foreach ($d in $docRefs) {
    if (-not (Test-Path $d)) { Fail-Guard "source points at $d, which does not exist." }
}

# includeSecrets must default to true. The settings form autosaves on a
# fingerprint change and nothing else, so a key edit that stopped moving the
# default fingerprint would never be saved at all — the key lost on the phone
# itself, not merely unpropagated.
if (-not (Select-String -Path core/src/main/kotlin/io/pickwick/app/data/ConfigJson.kt -Pattern 'includeSecrets: Boolean = true' -SimpleMatch -Quiet)) {
    Fail-Guard "ConfigJson.fingerprint must keep includeSecrets defaulting to true. Only a secretless peer passes false."
}

# Whether a peer holds no secrets is this phone's record, made at enrolment —
# never the peer's claim. A peer that could assert it would switch off the
# only content-level check on the API key, and a TV holding a revoked key
# would read "in sync" while its screening was dead. So the hub does not
# advertise it and the app does not look for it.
# Counted, not -Quiet: with pipeline input Select-String -Quiet emits one
# boolean PER FILE, and a non-empty array is truthy, so the guard would fire
# on every build no matter what the files contained.
if (@(Get-ChildItem -Recurse -File hub/src/main | Select-String -Pattern 'secretless' -SimpleMatch).Count -gt 0) {
    Fail-Guard ":hub must not advertise secretless. The flag is recorded on the phone at enrolment, not asserted by the peer."
}

# One comparison rule, in matches(). A hand-rolled copy in the push-result
# message was missed when the secretless case was added, so the tile said
# "in sync" while the note underneath blamed an old version.
if (Select-String -Path app/src/main/java/io/pickwick/app/ui/SettingsDevices.kt -Pattern '[.]hash == local' -Quiet) {
    Fail-Guard "SettingsDevices.kt compares a peer hash outside matches(). Route it through matches(expectedHash(...), ...)."
}

# The hub's name is load-bearing twice over: the settings screen finds the
# hub by it, and pre-flag entries are migrated by it. Two copies drift.
$hubName = @(Get-ChildItem -Recurse -File app/src/main/java, core/src/main/kotlin |
    Select-String -Pattern '"Pickwick hub"' -SimpleMatch | ForEach-Object { $_.Path } | Sort-Object -Unique)
if ($hubName.Count -ne 1) {
    Fail-Guard "the literal `"Pickwick hub`" must appear only in PairedDevice.HUB_NAME (found in $($hubName.Count) files)."
}

# --- phone/hub settings parity ---------------------------------------------
#
# SettingsSurface is the single list of settings groups, read in both
# directions. Keyed on FIELDS rather than composables: the Playback page has
# no section composable at all, so a composable-counting guard was blind to a
# whole page.
$manifest = Get-Content core/src/main/kotlin/io/pickwick/app/data/SettingsSurface.kt -Raw
$settingsSrc = Get-Content app/src/main/java/io/pickwick/app/ui/Settings.kt -Raw
$hubWeb = Get-Content hub/src/main/kotlin/io/pickwick/hub/HubWeb.kt -Raw

# 1. Every field the settings form writes is claimed by some group.
$build = [regex]::Match($settingsSrc, 'fun buildCurrentConfig[\s\S]*?\n    \}').Value
$written = @([regex]::Matches($build, '(?m)^\s+([a-zA-Z]+) = ') | ForEach-Object { $_.Groups[1].Value }) | Sort-Object -Unique
foreach ($f in $written) {
    if ($manifest -notlike "*`"$f`"*") {
        Fail-Guard "Settings writes $f and no SettingsSurface group claims it. Add it to a group and say whether the hub gets it."
    }
}

# 2. Every settings composable is declared, both spellings, every ui file.
$composables = @(Get-ChildItem app/src/main/java/io/pickwick/app/ui/*.kt |
    Select-String -Pattern 'fun ([A-Za-z]+Section)\(' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value }) | Sort-Object -Unique
foreach ($fn in $composables) {
    if ($manifest -notlike "*`"$fn`"*") {
        Fail-Guard "$fn is not in SettingsSurface. Add it and say whether it belongs on the hub."
    }
}

# 3. The hub serves exactly the pages the phone navigates. Page ids are the
#    Page enum lowercased, so a page invented on one side and not the other
#    fails here rather than being noticed by a parent.
$hubPages = @([regex]::Matches($hubWeb, 'HubPage\("([^"]+)"') | ForEach-Object { $_.Groups[1].Value })
$phonePages = @([regex]::Matches($manifest, '(?m)^    ([A-Z]+)\("') | ForEach-Object { $_.Groups[1].Value })
foreach ($id in $hubPages) {
    if ($phonePages -notcontains $id.ToUpper()) {
        Fail-Guard "the hub serves a page called $id, which is not a Page in SettingsSurface."
    }
}
foreach ($pg in $phonePages) {
    if ($hubPages -notcontains $pg.ToLower()) {
        Fail-Guard "the phone has a $pg settings page and the hub serves none."
    }
}

$todo = ([regex]::Matches($manifest, 'Where\.BOTH, false')).Count
if ($todo -gt 0) { Write-Host "   settings groups still to reach the hub: $todo" }

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
