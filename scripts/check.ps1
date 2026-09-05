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

# And nothing decides "is this the hub" by that name. A parent can rename the
# hub (its card, its device page), and a name check then reads it as a TV:
# rediscovery would sweep the subnet for it, the hub form would offer to join
# a second one. The flag is PairedDevice.secretless; the migration in
# parsePaired is the one place the name may still stand in for it.
$hubByName = @(Get-ChildItem -Recurse -File app/src/main/java, core/src/main/kotlin |
    Select-String -Pattern '[A-Za-z_]\.name == PairedDevice\.HUB_NAME' |
    ForEach-Object { "$($_.Path):$($_.LineNumber)" })
if ($hubByName.Count -ne 0) {
    Fail-Guard "a hub is recognised by name ($($hubByName -join ', ')). Test PairedDevice.secretless instead; the name is editable."
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

# 4. The reconcile stays runnable without a UI.
#    It was lifted out of MainViewModel so a background worker could run it;
#    reaching back into ViewModel state would quietly re-strand it there and
#    nothing would fail until a TV stopped syncing while closed.
$syncPath = "app/src/main/java/io/pickwick/app/data/ConfigSync.kt"
$syncSrc = Get-Content $syncPath -Raw
if ($syncSrc -match 'androidx\.lifecycle|viewModelScope|_state\.value') {
    Fail-Guard "ConfigSync.kt reaches into the ViewModel. Take a callback instead (see onSweeping)."
}

# 5. One copy of what happens when a config arrives.
#    Three paths land a config now — an inbound push, this device's own
#    sweep, and the background worker. These bodies lived in MainActivity
#    under a comment reading "one lambda, both callers, so they cannot
#    drift"; a third caller is exactly when that stops being true.
$arrivalFns = @(
    @{ Name = "ProfileLooks(..).ack("; Pattern = 'ProfileLooks\([^)]*\)\.ack\(' },
    @{ Name = "KidNotices.configChange("; Pattern = 'KidNotices\.configChange\(' },
    @{ Name = "ProfileLooks.mergeInto("; Pattern = 'ProfileLooks\.mergeInto\(' }
)
foreach ($fn in $arrivalFns) {
    $offenders = Get-ChildItem -Path "app/src/main/java" -Recurse -Filter *.kt |
        Where-Object { $_.Name -ne "ConfigSync.kt" -and $_.Name -ne "ProfileLooks.kt" } |
        Where-Object { (Get-Content $_.FullName -Raw) -match $fn.Pattern }
    if ($offenders) {
        $where = ($offenders | ForEach-Object { $_.Name }) -join ", "
        Fail-Guard "$($fn.Name) is called outside ConfigSync.kt (in $where). Call ConfigSync.applyArrived/adoptLooks so the arrival paths cannot drift."
    }
}

# 7. The hub may announce a change; it may never command a device.
#    It has no credential on a device and must not acquire one: it is the
#    box on the NAS, the one meant to face the internet eventually. So its
#    single outbound call is a nudge carrying no data, and the device then
#    pulls and authenticates as it always does. Anything else here means a
#    device had to start trusting the hub as an admin.
$outbound = Get-ChildItem -Path "hub/src/main/kotlin" -Recurse -Filter *.kt |
    Where-Object { $_.Name -ne "HubNudge.kt" } |
    Where-Object { (Get-Content $_.FullName -Raw) -match 'openConnection|HttpClient|Socket\(' }
if ($outbound) {
    $where = ($outbound | ForEach-Object { $_.Name }) -join ", "
    Fail-Guard "the hub makes an outbound call outside HubNudge.kt (in $where). The hub announces; it does not command."
}
if ((Get-Content "hub/src/main/kotlin/io/pickwick/hub/HubNudge.kt" -Raw) -notmatch "sync-now") {
    Fail-Guard "HubNudge no longer posts to /sync-now. That route is the whole contract."
}
# 6. A worker that nothing schedules is dead code that reads as shipped.
$mainActivity = Get-Content "app/src/main/java/io/pickwick/app/ui/MainActivity.kt" -Raw
foreach ($w in @("IndexCrawlWorker", "ConfigSyncWorker", "ContentWarmWorker")) {
    if ($mainActivity -notmatch ([regex]::Escape("$w.schedule("))) {
        Fail-Guard "$w is never scheduled from MainActivity, so it never runs."
    }
}
# 8. The roadmap must not outlive the code it points at.
#    docs/ROADMAP.md ends in an Anchors table: each row names code an item
#    depends on. When an anchor stops resolving, that item was almost
#    certainly finished and nobody deleted it — which is precisely how the
#    old roadmap came to claim the hub had three settings pages after six
#    had shipped. Prose cannot be guarded; a reference to real code can.
if (Test-Path "docs/ROADMAP.md") {
    $roadmapSrc = Get-Content "docs/ROADMAP.md"
    $haystack = $null
    foreach ($line in $roadmapSrc) {
        if ($line -notmatch '^\|') { continue }
        $cells = $line.Split("|")
        if ($cells.Count -lt 4) { continue }
        $item = $cells[1].Trim()
        $anchor = $cells[2].Trim().Trim([char]96)
        $kind = $cells[3].Trim()
        if (-not $anchor) { continue }
        if ($kind -ne "code" -and $kind -ne "path") { continue }
        if ($kind -eq "path") {
            if (-not (Test-Path $anchor)) {
                Fail-Guard "ROADMAP.md item $item cites $anchor, which no longer exists. Is that item done? Delete it and its anchor row."
            }
        } elseif ($kind -eq "code") {
            if ($null -eq $haystack) {
                $haystack = Get-ChildItem -Path "app/src","core/src","hub/src","scripts" -Recurse -File |
                    ForEach-Object { Get-Content $_.FullName -Raw }
            }
            $found = $false
            foreach ($text in $haystack) { if ($text -and $text.Contains($anchor)) { $found = $true; break } }
            if (-not $found) {
                Fail-Guard "ROADMAP.md item $item cites ``$anchor``, which is gone from the codebase. Is that item done? Delete it and its anchor row."
            }
        }
    }
}
# 9. The AI API key must never reach cloud backup.
#    Both XMLs are include-mode, so the key stays on the device only because
#    its store is unlisted. Three comments say "Don't add it" and nothing
#    enforced it. The failure is a real credential with a balance riding to
#    Google from one plausible-looking line added by someone who never read
#    the comment. Note secrets_plain is the Keystore-failure fallback and is
#    NOT encrypted — it is the worse of the two to ship.
$backupXml = "app/src/main/res/xml/backup_rules.xml"
$extractXml = "app/src/main/res/xml/data_extraction_rules.xml"
$secretStoreSrc = Get-Content "app/src/main/java/io/pickwick/app/data/SecretStore.kt" -Raw
# Guard the guard: it hardcodes the store names, so a rename must fail here
# rather than silently disarming the check below.
if ($secretStoreSrc -notmatch 'FILE = "secrets"') {
    Fail-Guard "SecretStore.FILE is no longer ""secrets"" - update guard 9 in check.ps1 and check.sh to match, or the backup check stops covering anything."
}
if ($secretStoreSrc -notmatch 'FALLBACK_FILE = "secrets_plain"') {
    Fail-Guard "SecretStore.FALLBACK_FILE is no longer ""secrets_plain"" - update guard 9 in check.ps1 and check.sh to match."
}
foreach ($f in @($backupXml, $extractXml)) {
    if ((Get-Content $f -Raw) -match 'path="secrets(_plain)?"') {
        Fail-Guard "$f backs up the AI API key store. Include-mode is the only thing keeping that credential on the device - remove the line."
    }
}
# The two files say "keep the two files in lockstep" and nothing checked that
# either. A rule added to one and not the other is backed up on some Android
# versions and not others, which is the hardest kind of bug to notice.
$bSet = ([regex]::Matches((Get-Content $backupXml -Raw), 'path="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object) -join ","
$extractRaw = Get-Content $extractXml -Raw
$cloudBlock = [regex]::Match($extractRaw, '(?s)<cloud-backup>(.*?)</cloud-backup>').Groups[1].Value
$cSet = ([regex]::Matches($cloudBlock, 'path="([^"]+)"') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object) -join ","
if ($bSet -ne $cSet) {
    Fail-Guard "backup_rules.xml and data_extraction_rules.xml <cloud-backup> list different files. They are the API<=30 and API31+ twins of one rule and must match."
}
# 10. The two gate scripts must declare the same guards.
#     They are mirrors by convention and nothing checked it, so they had
#     already drifted once. CI runs only the bash one — which makes this
#     mirror the half that can rot unnoticed, and it is the half the author
#     of this project actually runs before committing.
#
#     Compares the numbered headings, not the logic: two languages cannot be
#     diffed, but "a guard was added to one file and not the other" is the
#     failure that actually happens, and a heading is enough to catch it.
$shNums = ((Get-Content "scripts/check.sh" |
    Where-Object { $_ -match '^# (\d+)\.' } |
    ForEach-Object { [int]$Matches[1] }) | Sort-Object -Unique) -join ","
$psNums = ((Get-Content "scripts/check.ps1" |
    Where-Object { $_ -match '^# (\d+)\.' } |
    ForEach-Object { [int]$Matches[1] }) | Sort-Object -Unique) -join ","
if ($shNums -ne $psNums) {
    Fail-Guard "check.sh declares guards [$shNums] and check.ps1 declares [$psNums]. They are mirrors; add it to both."
}
# 11. Every page the hub serves must have something to render.
#     index.html dispatches through a PAGES map and falls back to Kids for
#     an unknown id, so a page added to HubWeb.pages without a renderer
#     shows a tab with the WRONG PAGE UNDER IT rather than an error. Guard 3
#     only proves the page exists on both sides; this proves it was built.
$hubHtml = Get-Content "hub/src/main/resources/web/index.html" -Raw
foreach ($id in $hubPages) {
    if ($hubHtml -notmatch "(^|[ ,{])$id`: page") {
        Fail-Guard "the hub serves a page called $id with no renderer in index.html PAGES - it would silently show the Kids page."
    }
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
