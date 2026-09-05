# Yosemite Kids emulator loop. Usage: .\scripts\emu.ps1 <verb> [args]
#   boot [headless] | tv | stop | install | seed [--real] | launch | shot <name>
#   tap X Y | key KEYCODE | text "..." | back | home | rotate | logcat | forward
#   dpad left|right|up|down|ok|back | hold-ok | wait-stream
# Every adb call targets the emulator serial so a phone/headset on USB is never touched.
# The phone AVD is emulator-5554; the TV AVD boots on emulator-5556 so both can run.
param(
    [Parameter(Position = 0)][string]$Verb = "help",
    [Parameter(Position = 1)][string]$A = "",
    [Parameter(Position = 2)][string]$B = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA "Android\Sdk" }
$adb = Join-Path $sdk "platform-tools\adb.exe"
$emu = Join-Path $sdk "emulator\emulator.exe"
$serial = if ($env:YOSEMITE_KIDS_SERIAL) { $env:YOSEMITE_KIDS_SERIAL } else { "emulator-5554" }
$pkg = "io.yosemitekids.app"
function Adb { & $adb -s $serial @args }

function Wait-Boot {
    & $adb -s $serial wait-for-device | Out-Null
    for ($i = 0; $i -lt 120; $i++) {
        $b = (& $adb -s $serial shell getprop sys.boot_completed 2>$null) -join ""
        if ($b.Trim() -eq "1") { Write-Host "booted ($serial)"; return }
        Start-Sleep -Seconds 2
    }
    throw "emulator did not boot"
}

switch ($Verb) {
    "boot" {
        # `boot headless`: no emulator window. With a window up, the host mouse
        # resting over it produces a stream of phantom touches (down/up pairs,
        # dozens a second) that scroll the shelf and tap the player's rows on
        # their own — an unattended run must be headless. Software GPU only,
        # so System UI may ANR once early on; tap Wait.
        $extra = if ($A -eq "headless") { " -no-window -gpu swiftshader_indirect" } else { " -gpu auto" }
        Start-Process -FilePath $emu -ArgumentList ("-avd pickwick_phone -port 5554 -no-snapshot-load -no-boot-anim -netdelay none -netspeed full -dns-server 8.8.8.8,1.1.1.1" + $extra) -WindowStyle Normal
        Wait-Boot
    }
    "tv" {
        # Second console port so the phone can stay up; use YOSEMITE_KIDS_SERIAL=emulator-5556 for the other verbs.
        $serial = "emulator-5556"
        Start-Process -FilePath $emu -ArgumentList "-avd pickwick_tv -port 5556 -no-snapshot-load -no-boot-anim -gpu auto -netdelay none -netspeed full -dns-server 8.8.8.8,1.1.1.1" -WindowStyle Normal
        Wait-Boot
        Write-Host 'Drive it with: $env:YOSEMITE_KIDS_SERIAL="emulator-5556"; .\scripts\emu.ps1 install / seed / launch / dpad ...'
    }
    "stop" { Adb emu kill }
    "install" {
        Set-Location $root
        & .\gradlew.bat --no-daemon -q assembleDebug
        if ($LASTEXITCODE -ne 0) { throw "build failed" }
        Adb install -r -t app\build\outputs\apk\debug\app-debug.apk
    }
    "seed" {
        $file = if ($A -eq "--real") { "scripts\seed-config.real.json" } else { "scripts\seed-config.json" }
        if (-not (Test-Path (Join-Path $root $file))) { throw "$file missing (build it with scripts/seed-from-whitelist.sh)" }
        Adb push (Join-Path $root $file) /data/local/tmp/config.json
        Adb shell run-as $pkg mkdir -p files
        Adb shell run-as $pkg cp /data/local/tmp/config.json files/config.json
        Adb shell run-as $pkg rm -f shared_prefs/limits.xml
        Adb shell am force-stop $pkg
        Write-Host "seeded $file; launch to see it"
    }
    "launch" { Adb shell am start -n "$pkg/.ui.MainActivity" | Out-Null; Start-Sleep -Seconds 2 }
    "shot" {
        $dir = Join-Path $root "build\shots"; New-Item -ItemType Directory -Force $dir | Out-Null
        $name = if ($A) { $A } else { "shot-" + (Get-Date -Format "HHmmss") }
        $out = Join-Path $dir "$name.png"
        # Not `exec-out ... > $out`: PowerShell's redirect re-encodes the bytes
        # as text (BOM + mangled high bytes) and the PNG is unreadable.
        Adb shell screencap -p /sdcard/yosemite-kids-shot.png | Out-Null
        Adb pull /sdcard/yosemite-kids-shot.png $out | Out-Null
        Write-Host $out
    }
    "tap" { Adb shell input tap $A $B }
    "key" { Adb shell input keyevent $A }
    "text" { Adb shell input text ($A -replace " ", "%s") }
    "back" { Adb shell input keyevent KEYCODE_BACK }
    "home" { Adb shell input keyevent KEYCODE_HOME }
    "dpad" {
        $code = switch ($A) {
            "left" { "KEYCODE_DPAD_LEFT" } "right" { "KEYCODE_DPAD_RIGHT" }
            "up" { "KEYCODE_DPAD_UP" } "down" { "KEYCODE_DPAD_DOWN" }
            "ok" { "KEYCODE_DPAD_CENTER" } "back" { "KEYCODE_BACK" }
            default { throw "dpad left|right|up|down|ok|back" }
        }
        $n = if ($B) { [int]$B } else { 1 }
        for ($i = 0; $i -lt $n; $i++) { Adb shell input keyevent $code }
    }
    "hold-ok" { Adb shell input keyevent --longpress KEYCODE_DPAD_CENTER }
    "wait-stream" {
        # Blocks until the player starts fetching a stream or gives up (max ~70 s).
        Adb logcat -c
        for ($i = 0; $i -lt 14; $i++) {
            Start-Sleep -Seconds 5
            $log = (Adb logcat -d -s YosemiteKids:*) -join "`n"
            if ($log -match "chunked clen|stream\[|playback failed") { Write-Host "stream event after $(($i+1)*5)s"; break }
        }
    }
    "rotate" {
        Adb shell settings put system accelerometer_rotation 0
        $cur = (Adb shell settings get system user_rotation) -join ""
        $next = if ($cur.Trim() -eq "1") { 0 } else { 1 }
        Adb shell settings put system user_rotation $next
    }
    "logcat" { Adb logcat -d -s YosemiteKids:* AndroidRuntime:E | Select-Object -Last 200 }
    "forward" { Adb forward tcp:8765 tcp:8765; Write-Host "host 127.0.0.1:8765 -> emulator LanServer" }
    default {
        Write-Host "verbs: boot tv stop install seed [--real] launch shot <name> tap X Y key KEYCODE text '...' back home dpad <dir> [n] hold-ok wait-stream rotate logcat forward"
    }
}
