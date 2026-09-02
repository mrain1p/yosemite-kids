# Upstream tracking (Windows wrapper): runs scripts/upstream.sh under Git Bash.
# Usage: .\scripts\upstream.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$bash = "C:\Program Files\Git\bin\bash.exe"
if (-not (Test-Path $bash)) { $bash = (Get-Command bash).Source }
& $bash (Join-Path $root "scripts\upstream.sh") @args
