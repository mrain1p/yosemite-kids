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
