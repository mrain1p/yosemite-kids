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
Write-Host "== 0/4 source invariants" -ForegroundColor Cyan

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

# The gate discovers tests by globbing *Test.kt, in this script, check.sh and
# CI alike. A file named anything else is skipped by all three and looks green.
$misnamed = Get-ChildItem app\src\test\java\io\pickwick\app -File |
    Where-Object { $_.Name -notlike "*Test.kt" }
if ($misnamed) {
    Fail-Guard "these test files will never run: $($misnamed.Name -join ', ') — rename to *Test.kt"
}

Write-Host "== 1/3 compile (assembleDebug)" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Host "compile FAILED" -ForegroundColor Red; exit 1 }

if ($Quick) { Write-Host "compile OK (quick mode)" -ForegroundColor Green; exit 0 }

Write-Host "== 2/3 unit tests (offline)" -ForegroundColor Cyan
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

Write-Host "== 3/3 worker tests" -ForegroundColor Cyan
if (Get-Command node -ErrorAction SilentlyContinue) {
    & node --test (Get-ChildItem "worker\test\*.test.mjs" | ForEach-Object { $_.FullName })
    if ($LASTEXITCODE -ne 0) { Write-Host "worker tests FAILED" -ForegroundColor Red; exit 1 }
} else {
    Write-Host "node not found — skipping worker tests" -ForegroundColor Yellow
}

Write-Host "all green" -ForegroundColor Green
