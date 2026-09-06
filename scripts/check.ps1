# Yosemite Kids pre-commit check: compile, offline unit tests, worker tests.
# Usage: .\scripts\check.ps1 [-Quick|-Guards]
#
#   -Quick    step 0 + compile
#   -Guards   step 0 only: the source guards, no SDK, no Gradle, no
#             local.properties. Mirrors check.sh --guards.
param([switch]$Quick, [switch]$Guards)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

if (-not $Guards -and -not (Test-Path "local.properties")) {
    Write-Host "local.properties is missing — create it with sdk.dir=<path to Android SDK>" -ForegroundColor Red
    exit 1
}

# --- 0/4 invariants a test cannot state ------------------------------------
# Each of these is a property that has to hold across a whole file, so no
# assertion can pin it. See docs/PLAN-sync.md.
Write-Host "== 0/6 source invariants" -ForegroundColor Cyan

function Fail-Guard($message) {
    Write-Host "guard FAILED: $message" -ForegroundColor Red
    exit 1
}

# The merge must read no clock. That is what makes idempotence and
# associativity structural rather than artifacts that hold only while a test
# freezes the clock, and it is why a TV with a bad RTC cannot drop a parent's
# active pause into the shared document.
# The master election is held to the same rule: a "day later" has to be a
# number a test passes in, not a moment the machine running the test is at.
foreach ($clockless in @("ConfigMerge", "MasterElection")) {
    $src = Get-Content "core\src\main\kotlin\io\yosemitekids\app\data\$clockless.kt" -Raw
    if ($src -match "currentTimeMillis|Instant\.now|System\.nanoTime") {
        Fail-Guard "$clockless.kt reads a clock. Take the time as a parameter (see ConfigStamp.stamped)."
    }
}

# Every config write must pass through commit(), which stashes the API key in
# SecretStore and strips it from the bytes. Two write paths means the next one
# added forgets.
$storeLines = Get-Content "app\src\main\java\io\yosemitekids\app\data\ConfigStore.kt"
$writers = ($storeLines | Select-String -Pattern "writeAtomically\(" -SimpleMatch).Count
if ($writers -gt 2) {
    Fail-Guard "ConfigStore.kt calls writeAtomically outside commit(). Every write goes through commit."
}

# buildCurrentConfig must copy the baseline, never construct a Whitelist: a
# positional constructor silently defaults out any field the form does not
# name, which would erase the sync blob from every save and every push.
# The shaping lives in SettingsForm.toConfig now, so both files are held to it.
$settingsSrc = Get-Content "app\src\main\java\io\yosemitekids\app\ui\Settings.kt" -Raw
foreach ($formFile in @("app\src\main\java\io\yosemitekids\app\ui\Settings.kt", "app\src\main\java\io\yosemitekids\app\ui\SettingsForm.kt")) {
    if ((Get-Content $formFile -Raw) -match "return Whitelist\(") {
        Fail-Guard "$formFile constructs a Whitelist. Use baseline.copy(...) so new fields are inherited."
    }
}

# :core is the code the Android app and the Docker hub both run. The moment it
# imports Android the hub cannot build it — and that failure would surface in
# the hub's build, a long way from the edit that caused it.
$androidInCore = Get-ChildItem -Recurse -File -Filter *.kt core\src\main\kotlin, crawl\src\main\kotlin |
    Select-String -Pattern '^import (android|androidx)\.' -List | ForEach-Object { $_.Path }
if ($androidInCore) {
    Fail-Guard "a shared module imports Android ($androidInCore). :core and :crawl must stay plain JVM so the hub can use them."
}

# The same rule one level up: an Android plugin here would let the above in
# without tripping the import check.
if (Select-String -Path "core\build.gradle.kts", "crawl\build.gradle.kts" -Pattern 'com\.android|kotlin-android|libs\.plugins\.android' -Quiet) {
    Fail-Guard ":core or :crawl applies an Android plugin. Both must stay plain JVM modules."
}

# The merge's tests belong in :core. In :app they still pass, but they prove
# the merge works *on Android* — and the hub, the other consumer of the exact
# same code, would be running it with nothing covering it.
foreach ($t in @("ConfigMergeTest", "ConfigStampTest", "ConfigSyncFormatTest", "SyncDecisionTest")) {
    if (-not (Test-Path "core\src\test\kotlin\io\yosemitekids\app\$t.kt")) {
        Fail-Guard "$t.kt must live in core\src\test — there it covers the hub too, in :app it does not."
    }
}

# The hub must not depend on :app. :app is Android, and the whole reason the
# hub can share this logic is that the shared half was lifted into :core. A
# dependency here would drag the Android SDK into a container build.
if (Select-String -Path "hub\build.gradle.kts", "crawl\build.gradle.kts" -Pattern 'project\(":app"\)' -Quiet) {
    Fail-Guard ":hub or :crawl depends on :app. Anything they need belongs in :core or :crawl."
}
# And the layering inside the shared code: :crawl (network, disk, clock)
# builds on :core (the pure rules), never the reverse.
if (Select-String -Path "core\build.gradle.kts" -Pattern 'project\(":crawl"\)' -Quiet) {
    Fail-Guard ":core depends on :crawl. The rules must not depend on the crawler; move the shared piece down into :core."
}

# The hub answers /status with the keys LanClient.fullStatus parses. The hub
# cannot depend on :app to check that, so the contract lives in a test — and
# this makes sure the test is still there to check it.
if (-not (Test-Path "hub\src\test\kotlin\io\yosemitekids\hub\HubServerTest.kt")) {
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
$docRefs = Get-ChildItem -Recurse -File app/src, core/src, crawl/src, hub/src, scripts |
    Select-String -Pattern 'docs/[A-Za-z0-9_.-]+[.]md' -AllMatches |
    ForEach-Object { $_.Matches.Value } | Sort-Object -Unique
foreach ($d in $docRefs) {
    if (-not (Test-Path $d)) { Fail-Guard "source points at $d, which does not exist." }
}

# includeSecrets must default to true. The settings form autosaves on a
# fingerprint change and nothing else, so a key edit that stopped moving the
# default fingerprint would never be saved at all — the key lost on the phone
# itself, not merely unpropagated.
if (-not (Select-String -Path core/src/main/kotlin/io/yosemitekids/app/data/ConfigJson.kt -Pattern 'includeSecrets: Boolean = true' -SimpleMatch -Quiet)) {
    Fail-Guard "ConfigJson.fingerprint must keep includeSecrets defaulting to true. Only a secretless peer passes false."
}

# The word `secretless` is the phone's own vocabulary and must not appear in
# :hub at all. A hub says whether it holds a key (`holdsKey` on /status) — a
# fact about its own storage, which it is the only party that knows — and the
# phone decides what that means for its checks. Spelling the phone's flag on
# the hub is how a peer starts asserting the phone's conclusions.
# Counted, not -Quiet: with pipeline input Select-String -Quiet emits one
# boolean PER FILE, and a non-empty array is truthy, so the guard would fire
# on every build no matter what the files contained.
if (@(Get-ChildItem -Recurse -File hub/src/main | Select-String -Pattern 'secretless' -SimpleMatch).Count -gt 0) {
    Fail-Guard ":hub must not advertise secretless. A hub reports holdsKey about itself; what that means for a fingerprint is the phone's conclusion."
}

# `secretless` and `isHub` answer two different questions and were one flag
# until a hub could hold an API key of its own. `secretless` is only ever
# "which fingerprint is this peer judged on"; `isHub` is "what kind of thing
# is this". Reading the first to answer the second fails silently and in four
# directions at once: rediscovery sweeps the /24 for a NAS, the hub card
# cannot find it, the index relay pushes at a peer that answers 405, and
# POST /leave-hub removes nothing. Pairing.kt declares, parses and writes the
# flag; ConfigSync picks the fingerprint to compare against and
# SettingsDevices computes it. Anywhere else, someone meant isHub.
$keylessReaders = @(Get-ChildItem -Recurse -File app/src/main |
    Select-String -Pattern '[A-Za-z_)]\.secretless' |
    ForEach-Object { $_.Path } | Sort-Object -Unique |
    Where-Object { $_ -notmatch '[\\/](Pairing|ConfigSync|SettingsDevices)\.kt$' })
if ($keylessReaders.Count -ne 0) {
    Fail-Guard "$($keylessReaders -join ', ') reads PairedDevice.secretless. That flag only picks a fingerprint; to ask whether a peer is the hub, read isHub."
}

# One comparison rule, in matches(). A hand-rolled copy in the push-result
# message was missed when the secretless case was added, so the tile said
# "in sync" while the note underneath blamed an old version.
if (Select-String -Path app/src/main/java/io/yosemitekids/app/ui/SettingsDevices.kt -Pattern '[.]hash == local' -Quiet) {
    Fail-Guard "SettingsDevices.kt compares a peer hash outside matches(). Route it through matches(expectedHash(...), ...)."
}

# The hub's name is load-bearing twice over: the settings screen finds the
# hub by it, and pre-flag entries are migrated by it. Two copies drift.
$hubName = @(Get-ChildItem -Recurse -File app/src/main/java, core/src/main/kotlin |
    Select-String -Pattern '"Yosemite Kids hub"' -SimpleMatch | ForEach-Object { $_.Path } | Sort-Object -Unique)
if ($hubName.Count -ne 1) {
    Fail-Guard "the literal `"Yosemite Kids hub`" must appear only in PairedDevice.HUB_NAME (found in $($hubName.Count) files)."
}

# And nothing decides "is this the hub" by that name. A parent can rename the
# hub (its card, its device page), and a name check then reads it as a TV:
# rediscovery would sweep the subnet for it, the hub form would offer to join
# a second one. The flag is PairedDevice.isHub; the migration in
# parsePaired is the one place the name may still stand in for it.
$hubByName = @(Get-ChildItem -Recurse -File app/src/main/java, core/src/main/kotlin |
    Select-String -Pattern '[A-Za-z_]\.name == PairedDevice\.HUB_NAME' |
    ForEach-Object { "$($_.Path):$($_.LineNumber)" })
if ($hubByName.Count -ne 0) {
    Fail-Guard "a hub is recognised by name ($($hubByName -join ', ')). Test PairedDevice.isHub instead; the name is editable."
}

# --- phone/hub settings parity ---------------------------------------------
#
# SettingsSurface is the single list of settings groups, read in both
# directions. Keyed on FIELDS rather than composables: the Playback page has
# no section composable at all, so a composable-counting guard was blind to a
# whole page.
$manifest = Get-Content core/src/main/kotlin/io/yosemitekids/app/data/SettingsSurface.kt -Raw
$settingsSrc = Get-Content app/src/main/java/io/yosemitekids/app/ui/Settings.kt -Raw
$hubWeb = Get-Content hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt -Raw

# 1. Every field the settings form writes is claimed by some group.
$build = [regex]::Match($settingsSrc, 'fun buildCurrentConfig[\s\S]*?\n    \}').Value
$written = @([regex]::Matches($build, '(?m)^\s+([a-zA-Z]+) = ') | ForEach-Object { $_.Groups[1].Value }) | Sort-Object -Unique
foreach ($f in $written) {
    if ($manifest -notlike "*`"$f`"*") {
        Fail-Guard "Settings writes $f and no SettingsSurface group claims it. Add it to a group and say whether the hub gets it."
    }
}

# 2. Every settings composable is declared, both spellings, every ui file.
$composables = @(Get-ChildItem app/src/main/java/io/yosemitekids/app/ui/*.kt |
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
$syncPath = "app/src/main/java/io/yosemitekids/app/data/ConfigSync.kt"
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

# 7. The hub may reach exactly two things: YouTube, for the crawl, and the
#    devices' /sync-now, for the nudge. It holds no credential on a device and
#    must not acquire one: it is the box on the NAS, the one meant to face the
#    internet eventually. So the nudge carries no data and the device pulls
#    and authenticates as it always does, and the crawl goes through the
#    shared client with its host allow-list armed. Four clauses, because a
#    substring scan for "HttpClient" cannot tell the crawler's one client from
#    a second, unarmed one.
#    (a) hub/src opens no connection of its own outside HubNudge.kt.
$outbound = Get-ChildItem -Path "hub/src/main/kotlin" -Recurse -Filter *.kt |
    Where-Object { $_.Name -ne "HubNudge.kt" } |
    Where-Object { (Get-Content $_.FullName -Raw) -match 'openConnection|HttpClient|Socket\(' }
if ($outbound) {
    $where = ($outbound | ForEach-Object { $_.Name }) -join ", "
    Fail-Guard "the hub makes an outbound call outside HubNudge.kt (in $where). The hub crawls through :crawl's Http and nudges through HubNudge; nothing else."
}
if ((Get-Content "hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt" -Raw) -notmatch "sync-now") {
    Fail-Guard "HubNudge no longer posts to /sync-now. That route is the whole contract."
}
#    (b) :crawl builds exactly one client, in Http.kt, and opens no raw socket.
$clients = Get-ChildItem -Path "crawl/src/main/kotlin" -Recurse -Filter *.kt |
    Where-Object { $_.Name -ne "Http.kt" } |
    Where-Object { (Get-Content $_.FullName -Raw) -match 'OkHttpClient\.Builder\(|OkHttpClient\(' }
if ($clients) {
    Fail-Guard ":crawl builds an OkHttpClient outside Http.kt (in $(($clients | ForEach-Object { $_.Name }) -join ', ')). One client, so the hub's allow-list covers every fetch."
}
$rawnet = Get-ChildItem -Path "crawl/src/main/kotlin" -Recurse -Filter *.kt |
    Where-Object { (Get-Content $_.FullName -Raw) -match 'openConnection|Socket\(' }
if ($rawnet) {
    Fail-Guard ":crawl opens a connection around the shared client (in $(($rawnet | ForEach-Object { $_.Name }) -join ', ')). Everything goes through Http.client."
}
#    (c) the hub arms the allow-list before anything fetches.
if (-not (Select-String -Path "hub/src/main/kotlin/io/yosemitekids/hub/Main.kt" -Pattern 'Http.restrictTo(io.yosemitekids.app.data.Http.HUB_HOSTS)' -SimpleMatch -Quiet)) {
    Fail-Guard "hub Main.kt does not arm Http.restrictTo(Http.HUB_HOSTS) at startup. Without it the crawler could reach any host."
}
#    (d) the allow-list names YouTube's hosts and nothing else.
$httpSrc = Get-Content "crawl/src/main/kotlin/io/yosemitekids/app/data/Http.kt"
$hostsStart = [array]::IndexOf($httpSrc, ($httpSrc | Where-Object { $_ -match 'val HUB_HOSTS' } | Select-Object -First 1))
$hostLines = if ($hostsStart -ge 0) { $httpSrc[$hostsStart..([Math]::Min($hostsStart + 3, $httpSrc.Count - 1))] } else { @() }
$hosts = @($hostLines | Select-String -Pattern '"([a-z0-9.-]+)"' -AllMatches | ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value })
if ($hosts.Count -eq 0) { Fail-Guard "Http.HUB_HOSTS is empty or unreadable; the hub's allow-list must name YouTube's hosts." }
foreach ($h in $hosts) {
    if ($h -notin @("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com", "ggpht.com", "googleusercontent.com")) {
        Fail-Guard "Http.HUB_HOSTS names $h, which is not one of YouTube's hosts. The hub reaches YouTube and nothing else."
    }
}
# 6. A worker that nothing schedules is dead code that reads as shipped.
$mainActivity = Get-Content "app/src/main/java/io/yosemitekids/app/ui/MainActivity.kt" -Raw
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
                $haystack = Get-ChildItem -Path "app/src","core/src","crawl/src","hub/src","scripts" -Recurse -File |
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
$secretStoreSrc = Get-Content "app/src/main/java/io/yosemitekids/app/data/SecretStore.kt" -Raw
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
        Fail-Guard "the hub serves a page called $id with no renderer in index.html ROUTES - it would silently show the root."
    }
}
#     And every hash route the page links to, which is the same failure from
#     the other end. The GUI navigates by location.hash now, because it is
#     installed and the system Back button is the only navigation an installed
#     page has; an unknown route falls through to the root, so a parent taps a
#     row and lands back where they started with nothing to read. The root is
#     a key of its own, or the page cannot answer its own front door.
if ($hubHtml -notmatch '""\s*: page') {
    Fail-Guard "index.html has no ROUTES entry for the root (a """" key). Every unknown route falls back to it, including #/."
}
$linked = @([regex]::Matches($hubHtml, '"#/([a-z]+)') | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
foreach ($seg in $linked) {
    if ($hubHtml -notmatch "(^|[ ,{])$seg`: page") {
        Fail-Guard "index.html links to #/$seg and ROUTES has no renderer for it. The route falls back to the root, so the row appears to do nothing."
    }
}
# The gate discovers tests by globbing *Test.kt, in this script, check.sh and
# CI alike. A file named anything else is skipped by all three and looks green.
$misnamed = Get-ChildItem app\src\test\java\io\yosemitekids\app, core\src\test\kotlin\io\yosemitekids\app, crawl\src\test\kotlin\io\yosemitekids\app -File |
    Where-Object { $_.Name -notlike "*Test.kt" }
if ($misnamed) {
    Fail-Guard "these test files will never run: $($misnamed.Name -join ', ') — rename to *Test.kt"
}

# 12. MainViewModel's working init block sits below every property.
#     It launches work that reads those properties from other threads, and
#     Kotlin runs property initialisers and init blocks in textual order: a
#     declaration below the init re-runs after it (the kid's chip choices
#     init had just loaded reset to null, every time), and a property the IO
#     thread reads before its initialiser has run is null (orderChannels got
#     sort = null and the app died at launch). Move the block, never the
#     property.
$vmLines = Get-Content "app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt"
$lastInit = 0; $lastProp = 0
for ($i = 0; $i -lt $vmLines.Count; $i++) {
    if ($vmLines[$i] -cmatch '^    init \{') { $lastInit = $i + 1 }
    if ($vmLines[$i] -cmatch '^    (private |internal |public )?(override )?(lateinit )?(var|val) ') { $lastProp = $i + 1 }
}
if ($lastInit -eq 0 -or $lastProp -eq 0 -or $lastInit -le $lastProp) {
    Fail-Guard "MainViewModel.kt: the init block (line $lastInit) must come after the last property declaration (line $lastProp) - it starts work that reads them from other threads."
}

# 13. The hub's build context is an allow-list. The image is built from the
#     repo root (CI, or a docker build on the NAS), which on the NAS also holds data/ — the family's
#     config and tokens, owned by the hub's uid and unreadable by the user
#     running the build: the build died on it before compiling anything.
#     .dockerignore must start by excluding everything and let in only what
#     the Dockerfile copies, one line per COPY source.
$diFirst = Get-Content .dockerignore -ErrorAction SilentlyContinue | Where-Object { $_ -notmatch '^\s*(#|$)' } | Select-Object -First 1
if ($diFirst -ne '*') {
    Fail-Guard ".dockerignore must begin with a bare '*' (exclude everything) - the hub build context would otherwise include data/ and .git."
}
$diAllowed = Get-Content .dockerignore | Where-Object { $_ -like '!*' } | ForEach-Object { $_.TrimStart('!').TrimEnd('/') }
foreach ($copyLine in (Get-Content hub/Dockerfile | Where-Object { $_ -cmatch '^COPY ' -and $_ -notmatch '--from=' })) {
    $parts = ($copyLine -replace '^COPY +', '') -split ' +'
    foreach ($src in $parts[0..($parts.Count - 2)]) {
        if ($diAllowed -notcontains $src.TrimEnd('/')) {
            Fail-Guard "hub/Dockerfile copies '$src' but .dockerignore does not allow it (add '!$src') - the image build would fail with 'not found'."
        }
    }
}

# 14. Every route LanServer answers has a row in docs/LAN-API.md.
#     The table is where the phone side, the hub and a parent with curl learn
#     what a device does; a route added to LanServer.handle and not to the
#     table exists for nobody but its author. /join-hub and /leave-hub had
#     been missing for a whole round before this guard existed.
$lanApi = Get-Content docs/LAN-API.md -Raw
$routes = Select-String -Path app/src/main/java/io/yosemitekids/app/data/Pairing.kt -Pattern 'path == "(/[a-z-]+)"' -AllMatches |
    ForEach-Object { $_.Matches } | ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique
foreach ($r in $routes) {
    if ($lanApi -notmatch "(GET|POST) $([regex]::Escape($r))[^a-z-]") {
        Fail-Guard "LanServer answers $r and docs/LAN-API.md has no row for it. Add it to the route table."
    }
}

# 15. The settings form adopts the whole of what it saved.
#     A save returns the STAMPED document, which is not the form: it carries
#     units a co-parent's push landed under the open form and keeps the
#     disk's copy of sections the editor left alone. Adopt that as the
#     baseline while the form keeps its own lists, and the next save shows
#     the stamper a unit in `base` and not in `next` - a deletion. A
#     co-parent's channel was tombstoned by this phone's second tap, and
#     every tap re-minted the AI unit. So `baseline` is assigned in exactly
#     one place, adopt(), which moves the form's fields in the same snapshot.
#     SettingsFormSaveTest proves the path is idempotent; this proves the
#     screen still goes through it.
$settingsLines = Get-Content "app/src/main/java/io/yosemitekids/app/ui/Settings.kt"
$baselineWrites = @($settingsLines | Where-Object { $_ -cmatch '^\s*baseline = ' }).Count
if ($baselineWrites -ne 1 -or -not ($settingsSrc -match 'fun adopt\(result: FormSave\)')) {
    Fail-Guard "Settings.kt assigns baseline in $baselineWrites places (must be exactly one, inside adopt(result: FormSave)). Route every save through saveForm() and adopt(), so the form takes the carried units too."
}
$directSaves = @($settingsLines | Where-Object { $_ -cmatch 'configStore\.save\(' }).Count
if ($directSaves -gt 1) {
    Fail-Guard "Settings.kt calls configStore.save() directly $directSaves times (the kid migration before the form exists is the one allowed). The form's saves go through saveForm() so the stamped result is adopted."
}

# 16. Today's bonus minutes come from two stores, each read in exactly one
#     place. The legacy LAN grant lands in prefs "bonusMs"; the config's
#     grants are taken by id into prefs "grants". SessionGuard.bonusMs() is
#     the one sum. A second reader of either store would add the two up its
#     own way, and the settings root, the stats screen and the enforcement
#     path could then disagree about how much time a kid has today.
$guardSrc = Get-Content "app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt"
foreach ($pair in @('getLong("bonusMs"', 'getString("grants"')) {
    $reads = @($guardSrc | Where-Object { $_.Contains($pair) }).Count
    if ($reads -ne 1) {
        Fail-Guard "SessionGuard.kt reads $pair in $reads places (must be exactly one). Sum the two stores in bonusMs() and read that."
    }
}

# 17. One crawler, one version stamp. The crawl loop lives in :crawl
#     (IndexCrawlRun) so the hub and the phone run the same batch; a second
#     copy of the loop in the app is the drift CLAUDE.md warns about. And the
#     cursor stamp is the generated ExtractorVersion: a BuildConfig field for
#     it would let the app and the hub stamp cursors differently while
#     believing they agree, and a cursor is readable only by its own stamp.
if (Select-String -Path "app/build.gradle.kts" -Pattern 'EXTRACTOR_VERSION' -SimpleMatch -CaseSensitive -Quiet) {
    Fail-Guard "app/build.gradle.kts defines EXTRACTOR_VERSION again. The stamp is :crawl's generated ExtractorVersion.VALUE; there is one."
}
# Not -Quiet: on piped files it emits one boolean per file, and an array of
# $false is still truthy in an if, so the guard would fire on every tree.
if (@(Get-ChildItem -Recurse -File -Filter *.kt app/src/main/java | Select-String -Pattern 'PAGES_PER_RUN\s*=' -CaseSensitive).Count -gt 0) {
    Fail-Guard "the app defines its own PAGES_PER_RUN. The crawl loop is IndexCrawlRun in :crawl; the worker only calls it."
}

# 18. The mirror must at least parse. Guard 10 compares the two scripts'
#     headings, not their syntax, and the PowerShell one sat unparseable for
#     a whole round (guard 14's foreach never closed) while the bash one, the
#     only one CI runs, stayed green. So each script syntax-checks the other
#     when the other's interpreter is on this machine. On a Linux runner with
#     no PowerShell this is a no-op, which is exactly why the author's own
#     machine has to run the gate before a commit.
# Git's bash, not WSL's: System32\bash.exe is a launcher for a Linux distro
# that may not exist. PATH usually resolves to Git's own bash; when it does
# not, Git for Windows keeps one under its install directory.
$bashCandidates = @(
    (Get-Command bash -ErrorAction SilentlyContinue | Where-Object { $_.Source -notmatch 'System32' } | ForEach-Object { $_.Source }),
    (Join-Path $env:ProgramFiles "Git\bin\bash.exe"),
    (Join-Path $env:ProgramFiles "Git\usr\bin\bash.exe")
) | Where-Object { $_ -and (Test-Path $_) }
$bashExe = $bashCandidates | Select-Object -First 1
if ($bashExe) {
    & $bashExe -n scripts/check.sh
    if ($LASTEXITCODE -ne 0) { Fail-Guard "scripts/check.sh does not parse (bash -n)." }
}

# 19. The hub's service worker caches the shell and never the family.
#     This origin serves a family's whole configuration behind a session
#     cookie, and anything a worker caches lands in Cache Storage, which
#     outlives the session, the sign-out and the tab. SHELL is therefore an
#     allow-list of static assets, and every other request is passed through
#     untouched — /api included.
$swSrc = Get-Content "hub/src/main/resources/web/sw.js" -Raw
$shellBlock = [regex]::Match($swSrc, '(?s)var SHELL = \[(.*?)\]').Groups[1].Value
$shellPaths = @([regex]::Matches($shellBlock, '"(/[A-Za-z0-9./-]*)"') | ForEach-Object { $_.Groups[1].Value })
if ($shellPaths.Count -eq 0) { Fail-Guard "cannot read SHELL out of sw.js; guard 19 is blind." }
foreach ($p in $shellPaths) {
    if ($p -ne "/" -and $p -ne "/manifest.webmanifest" -and $p -notmatch '^/icon-[A-Za-z0-9-]+\.png$') {
        Fail-Guard "the hub's service worker caches $p. SHELL is a static-asset allow-list — caching anything else puts family data in Cache Storage."
    }
}
if ($swSrc -notmatch [regex]::Escape("SHELL.indexOf(url.pathname) === -1")) {
    Fail-Guard "the hub's service worker no longer skips paths outside SHELL, so every request would pass through its cache."
}

# 20. Every asset the GUI names is actually served.
#     "/" answers anything with no route of its own, so a renamed icon does
#     not 404 — it returns the page's HTML with a 200, and the app quietly
#     stops being installable.
$srvSrc = Get-Content "hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt" -Raw
$named = @()
$named += [regex]::Matches((Get-Content "hub/src/main/resources/web/manifest.webmanifest" -Raw), '"src"\s*:\s*"(/[A-Za-z0-9.-]+)"') | ForEach-Object { $_.Groups[1].Value }
$named += [regex]::Matches((Get-Content "hub/src/main/resources/web/index.html" -Raw), 'href="(/[A-Za-z0-9.-]+)"') | ForEach-Object { $_.Groups[1].Value }
$named = @($named | Sort-Object -Unique)
if ($named.Count -eq 0) { Fail-Guard "neither the manifest nor index.html names a single asset; guard 20 is blind." }
foreach ($a in $named) {
    if ($srvSrc -notmatch [regex]::Escape('"' + $a + '"')) {
        Fail-Guard "the hub GUI references $a and HubServer serves no such route — the catch-all would answer it with the page HTML, and the icon or manifest would fail silently."
    }
}

# 21. A grant that arrives in the config has to be applied by the device
#     that receives it.
#     Grants were moved into the merged config so a television asleep when a
#     parent tapped "Add time" would find the minutes when it woke. Nothing
#     read them: Whitelist.grantsFor had no caller anywhere, and every path
#     that computes a budget takes grants as a defaulted empty list, so only
#     the granting phone and the LAN fast path ever applied one. The feature
#     was shipped, documented and dead. Nothing failed, because a function
#     with no caller breaks no test.
if (-not (Select-String -Path "app/src/main/java/io/yosemitekids/app/data/ConfigSync.kt" -Pattern 'applyGrants(' -SimpleMatch -Quiet)) {
    Fail-Guard "ConfigSync no longer applies the config's grants on arrival - a device that was asleep when the parent granted time silently never gets it."
}
if (@(Get-ChildItem -Recurse -File -Filter *.kt app/src/main/java | Select-String -Pattern 'grantsFor(' -SimpleMatch).Count -eq 0) {
    Fail-Guard "nothing in the app reads Whitelist.grantsFor, so config-carried grants reach every device and are applied by none."
}

# 22. The hub answers a device's routes, or refuses them by name. Never with
#     the page.
#     HubServer registers "/" last so an unknown path lands on the admin GUI
#     rather than a 404 a parent has to interpret. For a human that is right;
#     for a device it is a lie. A phone sweeps /watchstate, /verdicts and
#     /stats across EVERY paired peer including the hub: all three answered
#     200 with HTML, the two mergers parsed it to nothing, and StatsCache
#     wrote index.html into files/stats_cache/ on every sweep for ever.
#     Nothing failed, because a 200 is a success.
$hubSrvSrc = Get-Content "hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt" -Raw
$deviceOnly = ([regex]::Match($hubSrvSrc, '(?s)val DEVICE_ONLY = setOf\((.*?)\)')).Groups[1].Value
if (-not $deviceOnly) { Fail-Guard "HubServer.kt declares no DEVICE_ONLY set; guard 22 is blind." }
$lanRoutes = @([regex]::Matches(
    (Get-Content "app/src/main/java/io/yosemitekids/app/data/Pairing.kt" -Raw), 'path == "(/[a-z-]+)"') |
    ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)
foreach ($r in $lanRoutes) {
    if ($hubSrvSrc -match [regex]::Escape('createContext("' + $r + '")')) { continue }
    if ($deviceOnly -notmatch [regex]::Escape('"' + $r + '"')) {
        Fail-Guard "LanServer answers $r and the hub neither implements it nor names it in HubServer.DEVICE_ONLY - its catch-all would hand a device the admin page with a 200."
    }
}

# 23. The browser mints no identifiers.
#     A kid id is a merge key - `kid|<id>`, and the key of every per-kid
#     overlay, grant, verdict and device assignment filed under that child.
#     The GUI minted them from the clock (the low eight hex of Date.now()),
#     which is sequential, guessable and identical for two kids added in the
#     same millisecond on two faces of one household. A collision does not
#     fail; it merges two children into one profile with one set of rules.
#     Reading a clock in the browser is still fine and will be needed (the
#     hub takes a grant's local date from the parent's browser on purpose,
#     PLAN-hub-parity D22) - minting an id from one is not.
$hubUiPath = "hub/src/main/resources/web/index.html"
$hubUi = Get-Content $hubUiPath -Raw
foreach ($mint in @('toString(16)', 'Math.random', 'randomUUID')) {
    if ($hubUi.Contains($mint)) {
        Fail-Guard "$hubUiPath mints an id with $mint. Ids are merge keys: leave the id off and let HubWeb mint it with Profile.newId(), or two faces of one household collide and two children become one profile."
    }
}
if (-not (Select-String -Path "hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt" -Pattern 'Profile.newId(' -SimpleMatch -Quiet)) {
    Fail-Guard "HubWeb no longer mints kid ids with Profile.newId(). Something has to: the browser deliberately sends a kid with no id at all."
}

# 24. A device tells the hub who it is, and the hub reads the same header.
#     The hub authenticates a device by a token IT minted at enrolment, which
#     no device has ever heard of; every device resolves
#     config.deviceProfiles by its own pairing token. X-Device-Id is the only
#     bridge between the two, and it spans two modules with nothing tying the
#     spelling together - rename it on one side and everything still
#     compiles, every test still passes, and "this device is for Emma" goes
#     back to doing nothing at all, silently, because a map lookup that
#     misses is indistinguishable from a device nobody assigned.
foreach ($f in @(
    "app/src/main/java/io/yosemitekids/app/data/Pairing.kt",
    "hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt")) {
    if (-not (Select-String -Path $f -Pattern '"X-Device-Id"' -SimpleMatch -Quiet)) {
        Fail-Guard "$f no longer names ""X-Device-Id"". Both ends must spell it the same, or the hub cannot key a device to a kid by anything the device will ever read."
    }
}

# 25. One door to the hub's admin secret, and nothing that prints it.
#     Four clauses, because "the credential is handled carefully" is four
#     different properties and each fails on its own.
#
#     Before the password there was no gate: /approve and /pending compared the
#     header themselves with no rate limit at all. Against 96 bits of hex that
#     was harmless; behind a key derivation it is an unmetered guessing oracle
#     four threads wide AND a processor-exhaustion attack, because the
#     derivation is the expensive half. adminGate() checks mayAttempt() BEFORE
#     reading a body and BEFORE deriving anything, which is what bounds the
#     cost of guessing - and a second route that verified the secret its own
#     way would restore both holes without failing a single test.
$hubMain = @(Get-ChildItem -Recurse -File -Filter *.kt "hub/src/main/kotlin")
#    (a) One header name, named once. A second spelling is a second gate.
$adminHdr = 0
foreach ($f in $hubMain) {
    $adminHdr += @([regex]::Matches((Get-Content $f.FullName -Raw), [regex]::Escape('"X-Admin-Token"'))).Count
}
if ($adminHdr -ne 1) {
    Fail-Guard """X-Admin-Token"" appears $adminHdr times in hub/src/main (must be exactly once, in adminGate). Every presentation of the admin secret goes through that one gate."
}
#    (b) The throttle is consulted in exactly one place, and that place is the
#        gate. A route that asked mayAttempt() itself would be a route that
#        could forget to.
$strayAttempt = @($hubMain |
    Where-Object { $_.Name -ne "HubSessions.kt" -and $_.Name -ne "HubServer.kt" } |
    Select-String -Pattern 'mayAttempt()' -SimpleMatch |
    ForEach-Object { $_.Path })
if ($strayAttempt.Count -gt 0) {
    Fail-Guard "mayAttempt() is called in $($strayAttempt -join ' '). Only adminGate() may ask; everything else goes through it."
}
$srvLines = Get-Content "hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt"
$attempts = @($srvLines | Where-Object { $_.Contains("mayAttempt()") })
if ($attempts.Count -ne 1) {
    Fail-Guard "HubServer.kt calls mayAttempt() $($attempts.Count) times (must be exactly one, inside adminGate)."
}
$gateOwner = ""
foreach ($line in $srvLines) {
    if ($line -match ' fun ') { $gateOwner = $line }
    if ($line.Contains("mayAttempt()")) { break }
}
if (-not $gateOwner.Contains("adminGate(")) {
    Fail-Guard "mayAttempt() is called from '$gateOwner', not from adminGate(. The throttle must run before any derivation, in the one gate."
}
#    (c) Nothing prints a credential. The boot line may name the regime - "a
#        password is set" - but a VALUE reaches a println only by interpolation
#        or concatenation, and a container log is a broadcast: docker logs
#        replays it from the beginning, Container Manager shows it in a web UI,
#        and a log driver ships it to a file whose permissions have nothing to
#        do with /data.
$printed = @($hubMain | Select-String -Pattern 'println|print\(|System\.err' |
    Where-Object { $_.Line -match '(?i)password|secret' -and $_.Line -match '[\$\+]' })
if ($printed.Count -gt 0) {
    Fail-Guard "the hub prints something on a line naming a password or secret ($($printed[0].Path):$($printed[0].LineNumber)) - name the regime, never the value."
}
#    (d) Recovery is a token you already hold, not a route. A reset endpoint on
#        a box whose stated future is facing the internet is a second front
#        door, and every one of these names is what that door gets called.
foreach ($door in @("/forgot", "/reset", "/recover")) {
    if (@($hubMain | Select-String -Pattern ('createContext("' + $door + '")') -SimpleMatch).Count -gt 0) {
        Fail-Guard "the hub serves $door. Recovery is the token from the log, deliberately not a route - a reset endpoint is a second front door on a box meant to face the internet."
    }
}

# 26. Parity per CONTROL, not per group.
#     Guards 1-3 hold the two faces to the same GROUPS, and a group is too
#     coarse to be a promise: hubReady is permanent, so a control added inside
#     a group the hub already renders slips through with nothing to notice.
#     That is not hypothetical. It had happened twice and both were live:
#     screen-time-rules claimed the hub while the hub drew four of a kid's
#     rules - no minVideoMinutes, no pause - and blocked-times claimed the hub
#     with no windows editor at all. Four clauses, because "the two faces
#     agree" is four separate properties and each fails on its own.
$whitelistSrc = Get-Content "core/src/main/kotlin/io/yosemitekids/app/data/Whitelist.kt" -Raw
$uiSrc = (Get-ChildItem "app/src/main/java/io/yosemitekids/app/ui" -Filter *.kt |
    ForEach-Object { Get-Content $_.FullName -Raw }) -join "`n"

function Get-ClassProps($src, $name) {
    $m = [regex]::Match($src, "(?sm)^data class $name\((.*?)^\)")
    if (-not $m.Success) { return @() }
    @([regex]::Matches($m.Groups[1].Value, '(?m)^    val ([A-Za-z]+)') |
        ForEach-Object { $_.Groups[1].Value })
}

#    (a) Every config leaf is claimed by exactly one control, or exempted by
#        name WITH a reason. A field with nothing to set it is a field a parent
#        cannot reach on either face, and it fails here on the day it is added
#        rather than in a message from a family six months later.
foreach ($cls in @(@("Whitelist", ""), @("Limits", "limits."), @("AiConfig", "ai."))) {
    $props = Get-ClassProps $whitelistSrc $cls[0]
    if ($props.Count -eq 0) {
        Fail-Guard "guard 26 cannot read $($cls[0])'s properties out of Whitelist.kt; it is blind."
    }
    foreach ($p in $props) {
        $path = $cls[1] + $p
        $claimed = if ($manifest.Contains('writes = "' + $path + '"')) { 1 } else { 0 }
        $exempt = if ($manifest.Contains('"' + $path + '" to ')) { 1 } else { 0 }
        if (($claimed + $exempt) -ne 1) {
            Fail-Guard "$path is claimed by $claimed control and exempted $exempt times in SettingsSurface - want exactly one. Give it a SettingsControl with writes = ""$path"", or add it to NOT_A_CONTROL with the reason there is nothing to set it."
        }
    }
}

#    (e) Every kind the manifest can declare has a branch in the generic
#        renderer. Clause (b) asks only CUSTOM controls to prove themselves,
#        because everything else is supposed to be drawn from the declaration -
#        so a kind added to the enum and not to renderControl() falls through
#        to null and the control is absent from a page that still passes every
#        other check here.
$kindLine = [regex]::Match($manifest, '(?m)^enum class ControlKind \{([^}]*)\}')
if (-not $kindLine.Success) { Fail-Guard "guard 26 cannot read ControlKind out of SettingsSurface.kt; it is blind." }
foreach ($k in ($kindLine.Groups[1].Value -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })) {
    if ($k -eq "CUSTOM") { continue }
    if (-not $hubHtml.Contains('c.kind === "' + $k + '"')) {
        Fail-Guard "ControlKind.$k has no branch in renderControl() in index.html, so a control of that kind draws nothing at all. Give it one - the manifest must not be able to declare something the hub silently drops."
    }
}

#    (b)-(d), per control, read in file order: a control belongs to the section
#        declared above it, which is what lets the section's own where/hubReady
#        decide whether the hub owes it anything.
$declared = @()
$ready = $false
# From the list down, so the `data class SettingsControl(` declaration above it
# is not read as a control of its own.
$listAt = $manifest.IndexOf('val sections: List<SettingsSection> = listOf(')
if ($listAt -lt 0) { Fail-Guard "guard 26 cannot find SettingsSurface.sections; it is blind." }
$sectionsBlock = $manifest.Substring($listAt)
foreach ($t in [regex]::Matches($sectionsBlock, '(?s)(SettingsSection|SettingsControl)\((.*?)(?=SettingsSection\(|SettingsControl\(|\z)')) {
    $body = $t.Groups[2].Value
    if ($t.Groups[1].Value -eq "SettingsSection") {
        $ready = $body -match 'Where\.(BOTH|HUB), true,'
        continue
    }
    $idMatch = [regex]::Match($body, '^\s*"([a-z0-9-]+)"')
    if (-not $idMatch.Success) { Fail-Guard "guard 26 cannot read a control id out of SettingsSurface." }
    $id = $idMatch.Groups[1].Value
    $declared += $id
    $custom = $body -match 'kind = ControlKind\.CUSTOM'
    $face = if ($body -match 'where = Where\.PHONE') { "PHONE" }
        elseif ($body -match 'where = Where\.HUB') { "HUB" }
        else { "BOTH" }
    $hasWhy = ($body -match 'why = "') -and -not ($body -match 'why = ""')

    #  (b) A control the hub is expected to have is either drawn generically
    #      from the manifest or hand-written and marked. Nothing may be merely
    #      claimed.
    if ($face -ne "PHONE" -and $ready -and $custom) {
        if ($hubHtml -notmatch ('data(-|set\.)control ?= ?"' + [regex]::Escape($id) + '"')) {
            Fail-Guard "the control ""$id"" is on the hub's list and index.html does not build it. A CUSTOM control is hand-written, so mark its card data-control=""$id""; anything a generic renderer could draw should not be CUSTOM."
        }
    }
    #  (c) A control the phone is expected to have is asked for by id. The
    #      manifest owns the words, so the reference is load-bearing rather
    #      than ceremonial - without it there is no label to render. CUSTOM
    #      controls are exempt on purpose: their words are their own, which is
    #      what CUSTOM means, so a reference there would prove nothing.
    if ($face -ne "HUB" -and -not $custom) {
        if (-not $uiSrc.Contains('ctl("' + $id + '")')) {
            Fail-Guard "the control ""$id"" is declared for the phone and no ui/*.kt asks for it. Render it with ctl(""$id""), or move it to Where.HUB and say why."
        }
    }
    #  (d) "Specific to each" is a decision, and one with no recorded reason is
    #      re-argued every round by someone who cannot tell it from an omission.
    if ($face -ne "BOTH" -and -not $hasWhy) {
        Fail-Guard "the control ""$id"" is $face-only with a blank why. Say what the other face cannot do, where the next session will meet it."
    }
}
if ($declared.Count -eq 0) { Fail-Guard "guard 26 read no controls out of SettingsSurface.kt; it is blind." }
#        And the other direction, which (c) alone does not cover: an id the
#        phone asks for and the manifest does not declare. control() throws,
#        and it throws at render time on a screen a parent just opened.
foreach ($asked in @([regex]::Matches($uiSrc, 'ctl\("([a-z0-9-]+)"\)') |
        ForEach-Object { $_.Groups[1].Value } | Sort-Object -Unique)) {
    if ($declared -notcontains $asked) {
        Fail-Guard "the phone asks for a settings control called ""$asked"", which SettingsSurface does not declare. SettingsSurface.control() throws - on the screen, in front of a parent."
    }
}

# 27. The hub reads no calendar.
#     A container runs UTC and the family does not. Every local day and local
#     midnight the hub stores therefore comes from the parent's browser, and
#     the hub only bounds it: PAUSE_MAX_AHEAD_MS for a pause, and
#     GRANT_MAX_DAYS_AWAY for the day a grant names. It is the same rule
#     ConfigStamp.stamped(today = null) already encodes, and the reason
#     HubStore.edit passes null - a day that ends hours early on the NAS
#     tombstones a grant at teatime and takes a kid's minutes away.
#     Worth a guard rather than a comment because of how it fails. A hub in
#     UTC and a family in Auckland disagree for thirteen hours of every day,
#     so the symptom is bonus minutes that stop working in the evening, for
#     some households, some of the time. Nothing throws; the container is
#     right about its own clock and wrong about the family's.
foreach ($cal in @("LocalDate", "Calendar", "SimpleDateFormat", "ZoneId.systemDefault")) {
    $dated = @(Get-ChildItem "hub/src" -Recurse -File | Select-String -Pattern $cal -SimpleMatch -CaseSensitive |
        ForEach-Object { $_.Path } | Sort-Object -Unique)
    if ($dated.Count -gt 0) {
        Fail-Guard "$($dated -join ' ') names $cal. The container's clock is UTC and the family's is not - that is why HubStore.edit passes today = null. A day or a midnight arrives from the parent's browser and the hub only checks how far away it is (HubWeb.PAUSE_MAX_AHEAD_MS, HubWeb.GRANT_MAX_DAYS_AWAY)."
    }
}

# 28. One backup envelope, because two faces write it and two faces read it.
#     The phone exports through Backup and the hub serves GET /api/backup, and
#     the whole point of taking a file off the NAS is the day the NAS is gone
#     and a phone is all that is left. Two copies of `kind` and `schema` drift
#     in one release and the file silently stops crossing - with the symptom
#     arriving on the worst day it could. So the envelope is declared once, in
#     :core, and every other file reads the constants from there.
$envelope = "core/src/main/kotlin/io/yosemitekids/app/data/BackupFile.kt"
foreach ($word in @("yosemite-kids-backup", "pickwick-backup")) {
    $homes = @(Get-ChildItem "app/src/main", "core/src/main", "crawl/src/main", "hub/src/main" -Recurse -File |
        Select-String -Pattern ('"' + $word + '"') -SimpleMatch |
        ForEach-Object { (Resolve-Path -Relative $_.Path) -replace '\\', '/' -replace '^\./', '' } |
        Sort-Object -Unique)
    if ($homes.Count -ne 1 -or $homes[0] -ne $envelope) {
        Fail-Guard "the backup envelope's ""$word"" is spelled out in [$($homes -join ' ')]. It belongs in $envelope alone - a phone must be able to open a file the hub wrote, and the reverse."
    }
}

if ($Guards) { Write-Host "source invariants OK" -ForegroundColor Green; exit 0 }

Write-Host "== 1/6 compile (assembleDebug)" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q assembleDebug
if ($LASTEXITCODE -ne 0) { Write-Host "compile FAILED" -ForegroundColor Red; exit 1 }

if ($Quick) { Write-Host "compile OK (quick mode)" -ForegroundColor Green; exit 0 }

Write-Host "== 2/6 core tests (no Android — the hub runs this exact code)" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q :core:test
if ($LASTEXITCODE -ne 0) { Write-Host "core tests FAILED" -ForegroundColor Red; exit 1 }

Write-Host "== 3/6 crawl tests (plain JVM — the hub runs this crawler too)" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q :crawl:test
if ($LASTEXITCODE -ne 0) { Write-Host "crawl tests FAILED" -ForegroundColor Red; exit 1 }

Write-Host "== 4/6 hub tests" -ForegroundColor Cyan
& .\gradlew.bat --no-daemon -q :hub:test
if ($LASTEXITCODE -ne 0) { Write-Host "hub tests FAILED" -ForegroundColor Red; exit 1 }

Write-Host "== 5/6 app unit tests (offline)" -ForegroundColor Cyan
# Every test class except the live-YouTube canaries. Gradle's --tests takes
# patterns, not exclusions, so the list is built from the source tree.
# SingleChannelProbeTest calls ChannelInfo.getInfo with no runCatching and no
# Assume, so a bot wall fails this gate for reasons unrelated to the change
# being checked — the same reason ExtractorSmokeTest has always been out.
$live = @("ExtractorSmokeTest", "SingleChannelProbeTest")
$tests = Get-ChildItem app\src\test\java\io\yosemitekids\app -Filter *Test.kt |
    Where-Object { $live -notcontains $_.BaseName } |
    ForEach-Object { "--tests"; "io.yosemitekids.app.$($_.BaseName)" }
& .\gradlew.bat --no-daemon -q :app:testDebugUnitTest @tests
if ($LASTEXITCODE -ne 0) { Write-Host "unit tests FAILED — see app\build\reports\tests\testDebugUnitTest\index.html" -ForegroundColor Red; exit 1 }

Write-Host "== 6/6 worker tests" -ForegroundColor Cyan
if (Get-Command node -ErrorAction SilentlyContinue) {
    & node --test (Get-ChildItem "worker\test\*.test.mjs" | ForEach-Object { $_.FullName })
    if ($LASTEXITCODE -ne 0) { Write-Host "worker tests FAILED" -ForegroundColor Red; exit 1 }
} else {
    Write-Host "node not found — skipping worker tests" -ForegroundColor Yellow
}

Write-Host "all green" -ForegroundColor Green
