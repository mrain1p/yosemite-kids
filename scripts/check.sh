#!/usr/bin/env bash
# Yosemite Kids pre-commit check: compile, offline unit tests, worker tests.
# Usage: scripts/check.sh [--quick|--guards]
#
#   --quick   step 0 + compile
#   --guards  step 0 only. No SDK, no Gradle, no local.properties — the guards
#             read source and nothing else, which is what lets CI run them on a
#             clean checkout. That matters: these are the checks most likely to
#             stop working silently, and until CI ran them they were enforced
#             only when a human remembered to type the command.
set -euo pipefail
cd "$(dirname "$0")/.."

# Only the paths that actually invoke Gradle need an SDK.
if [ "${1:-}" != "--guards" ]; then
  [ -f local.properties ] || { echo "local.properties is missing — create it with sdk.dir=<Android SDK path>" >&2; exit 1; }
fi

# --- 0/4 invariants a test cannot state ------------------------------------
# Each is a property that holds across a whole file, so no assertion can pin
# it. See docs/PLAN-sync.md.
echo "== 0/6 source invariants"
guard_fail() { echo "guard FAILED: $1" >&2; exit 1; }

# A literal double quote, for the guards that have to look for one inside a
# pattern. Written once here because escaping it inline in this file has been
# got wrong more than once.
q='"'

# The merge must read no clock: that is what makes idempotence and
# associativity structural rather than test artifacts. The master election
# is held to the same rule: a "day later" has to be a number a test passes
# in, not a moment the machine running the test happens to be at.
for clockless in ConfigMerge MasterElection; do
  if grep -qE "currentTimeMillis|Instant\.now|System\.nanoTime" \
      "core/src/main/kotlin/io/yosemitekids/app/data/$clockless.kt"; then
    guard_fail "$clockless.kt reads a clock. Take the time as a parameter (see ConfigStamp.stamped)."
  fi
done

# Every config write goes through commit(), which stashes the API key and
# strips it from the bytes. Two write paths means the next one added forgets.
writers=$(grep -c "writeAtomically(" app/src/main/java/io/yosemitekids/app/data/ConfigStore.kt || true)
if [ "$writers" -gt 2 ]; then
  guard_fail "ConfigStore.kt calls writeAtomically outside commit(). Every write goes through commit."
fi

# buildCurrentConfig must copy the baseline: a positional constructor silently
# defaults out any field the form does not name, erasing the sync blob from
# every save and every push. The shaping lives in SettingsForm.toConfig now,
# so both files are held to it.
for f in app/src/main/java/io/yosemitekids/app/ui/Settings.kt app/src/main/java/io/yosemitekids/app/ui/SettingsForm.kt; do
  if grep -q "return Whitelist(" "$f"; then
    guard_fail "$f constructs a Whitelist. Use baseline.copy(...) so new fields are inherited."
  fi
done

# :core and :crawl are the code the Android app and the Docker hub both run.
# The moment either imports Android, the hub stops building — and the failure
# would surface in the hub's build, a long way from the edit that caused it.
androidInCore=$(grep -rlE "^import (android|androidx)\." core/src/main/kotlin crawl/src/main/kotlin || true)
if [ -n "$androidInCore" ]; then
  guard_fail "a shared module imports Android ($androidInCore). :core and :crawl must stay plain JVM so the hub can use them."
fi

# The same rule, one level up: the Android plugin in :core would let the above
# slip in without tripping the import check.
if grep -qE "com\.android|kotlin-android|libs\.plugins\.android" core/build.gradle.kts crawl/build.gradle.kts; then
  guard_fail ":core or :crawl applies an Android plugin. Both must stay plain JVM modules."
fi

# The merge's tests must live in :core, not :app. In :app they still pass, but
# they prove the merge works *on Android* — and the hub, which is the other
# consumer, would be running that logic with nothing covering it.
for t in ConfigMergeTest ConfigStampTest ConfigSyncFormatTest SyncDecisionTest; do
  [ -f "core/src/test/kotlin/io/yosemitekids/app/$t.kt" ] || \
    guard_fail "$t.kt must live in core/src/test — there it covers the hub too, in :app it does not."
done

# The hub must not depend on :app. :app is Android, and the whole reason the
# hub can share this logic is that the shared half was lifted into :core. A
# dependency here would drag the Android SDK into a container build.
if grep -qE "project\(\":app\"\)" hub/build.gradle.kts crawl/build.gradle.kts; then
  guard_fail ":hub or :crawl depends on :app. Anything they need belongs in :core or :crawl."
fi
# And the layering inside the shared code: :crawl (network, disk, clock)
# builds on :core (the pure rules), never the reverse. A rule that needed the
# crawler would drag the extractor into the merge.
if grep -qE "project\(\":crawl\"\)" core/build.gradle.kts; then
  guard_fail ":core depends on :crawl. The rules must not depend on the crawler; move the shared piece down into :core."
fi

# The hub answers /status with the keys LanClient.fullStatus parses. The hub
# cannot depend on :app to check that, so the contract lives in a test — and
# this makes sure the test is still there to check it.
if [ ! -f hub/src/test/kotlin/io/yosemitekids/hub/HubServerTest.kt ]; then
  guard_fail "HubServerTest.kt is missing — it pins the /status wire contract with :app."
fi

# This script must not stop early on its own success.
#
# `grep` exits 1 when it matches nothing, and under `set -euo pipefail` that
# ends the script — inside a command substitution, silently, with the shell
# reporting failure from a guard that actually PASSED. Every guard below the
# offending line then never runs. That is not hypothetical: it happened here,
# and the whole back half of this file was dead for as long as the tree was
# clean. Neither CI nor check.ps1 runs this script, so nothing noticed.
trap_lines=$(grep -nE "=\\\$\\(.*grep" "$0" | grep -v "|| true" | cut -d: -f1 | tr "\n" " " || true)
if [ -n "$trap_lines" ]; then
  guard_fail "check.sh line(s) $trap_lines capture grep without '|| true'. No match exits 1 and pipefail ends the gate on the clean case."
fi
# Shell scripts must reach a container with LF endings. A CRLF script has a
# shebang ending in a carriage return, which the kernel cannot resolve, and
# the error it produces is "not found" for a file that is plainly present.
# That cost half an hour on gradlew during the hub's first container build.
# git reports the working-tree ending directly, so this needs no escapes of
# its own to look for.
crlf=$(git ls-files --eol -- '*.sh' gradlew | grep -E 'w/(crlf|mixed)' | awk '{printf "%s ", $NF}' || true)
if [ -n "$crlf" ]; then
  guard_fail "CRLF line endings in: $crlf — a container cannot run these. See .gitattributes."
fi

# The hub's container must be able to take ownership of its bind-mounted
# /data. A USER instruction would start it unprivileged, that chown would be
# impossible, and the container would crash-loop on its first write with a
# permission error and no admin token anywhere in the log. Privilege is
# dropped in docker-entrypoint.sh instead, once the volume has been repaired.
if grep -qE '^USER ' hub/Dockerfile; then
  guard_fail "hub/Dockerfile has a USER line. Drop privileges in docker-entrypoint.sh, after chowning /data."
fi
if ! grep -q 'docker-entrypoint.sh' hub/Dockerfile; then
  guard_fail "hub/Dockerfile no longer installs docker-entrypoint.sh — nothing would fix /data ownership or drop root."
fi

# The entrypoint must prove the volume is writable, not infer it. Its first
# version chowned and exec'd, assuming a successful chown meant a writable
# directory — on a NAS share of mode 000 with an ACL, it does not, and the
# hub died on its first write in a restart loop. can_write() actually
# creates a file; nothing else is an answer.
if ! grep -q 'can_write' hub/docker-entrypoint.sh; then
  guard_fail "hub/docker-entrypoint.sh no longer tests writability. A chown that succeeds does not mean the volume is writable."
fi

# A doc path named in source is a promise. Renaming the doc leaves the
# pointer behind, and the place it is read is a container log at 3am.
for d in $(grep -rhoE 'docs/[A-Za-z0-9_.-]+[.]md' app/src core/src crawl/src hub/src scripts 2>/dev/null | sort -u); do
  [ -f "$d" ] || guard_fail "source points at $d, which does not exist."
done

# includeSecrets must default to true. The settings form autosaves on a
# fingerprint change and nothing else, so a key edit that stopped moving the
# default fingerprint would never be saved at all — the key lost on the phone
# itself, not merely unpropagated.
if ! grep -q 'includeSecrets: Boolean = true' core/src/main/kotlin/io/yosemitekids/app/data/ConfigJson.kt; then
  guard_fail "ConfigJson.fingerprint must keep includeSecrets defaulting to true. Only a secretless peer passes false."
fi

# The word `secretless` is the phone's own vocabulary and must not appear in
# :hub at all. A hub says whether it holds a key (`holdsKey` on /status) — a
# fact about its own storage, which it is the only party that knows — and the
# phone decides what that means for its checks. Spelling the phone's flag on
# the hub is how a peer starts asserting the phone's conclusions.
if grep -rq 'secretless' hub/src/main; then
  guard_fail ":hub must not advertise secretless. A hub reports holdsKey about itself; what that means for a fingerprint is the phone's conclusion."
fi

# `secretless` and `isHub` answer two different questions and were one flag
# until a hub could hold an API key of its own. `secretless` is only ever
# "which fingerprint is this peer judged on"; `isHub` is "what kind of thing
# is this". Reading the first to answer the second fails silently and in four
# directions at once: rediscovery sweeps the /24 for a NAS, the hub card
# cannot find it, the index relay pushes at a peer that answers 405, and
# POST /leave-hub removes nothing. Pairing.kt declares, parses and writes the
# flag; ConfigSync picks the fingerprint to compare against and
# SettingsDevices computes it. Anywhere else, someone meant isHub.
keyless_readers=$(grep -rl '[A-Za-z_)]\.secretless' app/src/main | grep -vE '/(Pairing|ConfigSync|SettingsDevices)\.kt$' || true)
if [ -n "$keyless_readers" ]; then
  guard_fail "$keyless_readers reads PairedDevice.secretless. That flag only picks a fingerprint; to ask whether a peer is the hub, read isHub."
fi

# One comparison rule, in matches(). A hand-rolled copy in the push-result
# message was missed when the secretless case was added, so the tile said
# "in sync" while the note underneath blamed an old version.
if grep -qE '[.]hash == local' app/src/main/java/io/yosemitekids/app/ui/SettingsDevices.kt; then
  guard_fail "SettingsDevices.kt compares a peer hash outside matches(). Route it through matches(expectedHash(...), ...)."
fi

# The hub's name is load-bearing twice over: the settings screen finds the
# hub by it, and pre-flag entries are migrated by it. Two copies drift.
hubname=$(grep -rc '"Yosemite Kids hub"' app/src/main/java core/src/main/kotlin 2>/dev/null | grep -v ":0$" | wc -l || true)
if [ "$hubname" -ne 1 ]; then
  guard_fail "the literal \"Yosemite Kids hub\" must appear only in PairedDevice.HUB_NAME (found in $hubname files)."
fi

# And nothing decides "is this the hub" by that name. A parent can rename the
# hub (its card, its device page), and a name check then reads it as a TV:
# rediscovery would sweep the subnet for it, the hub form would offer to join
# a second one. The flag is PairedDevice.secretless; the migration in
# parsePaired is the one place the name may still stand in for it.
hub_by_name=$(grep -rnE "[A-Za-z_]\.name == PairedDevice\.HUB_NAME" app/src/main/java core/src/main/kotlin 2>/dev/null || true)
if [ -n "$hub_by_name" ]; then
  guard_fail "a hub is recognised by name ($hub_by_name). Test PairedDevice.isHub instead; the name is editable."
fi

# --- phone/hub settings parity ---------------------------------------------
#
# SettingsSurface is the single list of settings groups, and these read it in
# both directions so the two faces cannot drift apart silently.
#
# Keyed on FIELDS, not composables. The first version of this guard counted
# "...Section(" functions and was blind to two whole categories: the Playback
# page has no section composable at all, and neither does the "Kid's shelves"
# card. An entire page was invisible to it. Every field buildCurrentConfig
# writes has to appear in the manifest, which no amount of inlining hides.
manifest=core/src/main/kotlin/io/yosemitekids/app/data/SettingsSurface.kt
settings=app/src/main/java/io/yosemitekids/app/ui/Settings.kt
hubweb=hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt

# 1. Every field the settings form writes is claimed by some group.
for f in $(awk "/fun buildCurrentConfig/,/^    }$/" "$settings" | grep -oE "^ *[a-zA-Z]+ = " | sed -E "s/ *//; s/ = //" | sort -u); do
  grep -q "$q$f$q" "$manifest" || guard_fail "Settings writes $f and no SettingsSurface group claims it. Add it to a group and say whether the hub gets it."
done

# 2. Every settings composable is declared. Both spellings, every file in the
#    ui package — KidsSection is a bare `fun` in KidsSettings.kt and was
#    missed by an earlier glob that only looked at Settings*.kt.
for fn in $(grep -hoE "fun [A-Za-z]+Section[(]" app/src/main/java/io/yosemitekids/app/ui/*.kt | sed -E "s/fun //; s/[(]//" | sort -u); do
  grep -q "$q$fn$q" "$manifest" || guard_fail "$fn is not in SettingsSurface. Add it and say whether it belongs on the hub."
done

# 3. The hub serves exactly the pages the phone navigates. Page ids are the
#    Page enum lowercased, so a page invented on one side and not the other
#    fails here rather than being noticed by a parent.
for id in $(grep -hoE "HubPage[(]$q[^$q]+$q" "$hubweb" | sed -E "s/HubPage[(]$q//; s/$q//" | sort -u); do
  upper=$(printf "%s" "$id" | tr "[:lower:]" "[:upper:]")
  grep -qE "^    $upper[(]" "$manifest" || guard_fail "the hub serves a page called $id, which is not a Page in SettingsSurface."
done
for upper in $(grep -oE "^    [A-Z]+[(]" "$manifest" | tr -d " ([" ); do
  lower=$(printf "%s" "$upper" | tr "[:upper:]" "[:lower:]")
  grep -q "HubPage[(]$q$lower$q" "$hubweb" || guard_fail "the phone has a $upper settings page and the hub serves none."
done

# Named, not counted: what is still missing should be readable.
todo=$(grep -oE "Where[.]BOTH, false" "$manifest" | wc -l || true)
[ "$todo" -eq 0 ] || echo "   settings groups still to reach the hub: $todo"

# 4. The reconcile stays runnable without a UI.
#    It was lifted out of MainViewModel so a background worker could run it;
#    reaching back into ViewModel state would quietly re-strand it there and
#    nothing would fail until a TV stopped syncing while closed.
sync=app/src/main/java/io/yosemitekids/app/data/ConfigSync.kt
if grep -qE "androidx[.]lifecycle|viewModelScope|_state[.]value" "$sync"; then
  guard_fail "ConfigSync.kt reaches into the ViewModel. Take a callback instead (see onSweeping)."
fi

# 5. One copy of what happens when a config arrives.
#    Three paths land a config now — an inbound push, this device's own
#    sweep, and the background worker. These bodies lived in MainActivity
#    under a comment reading "one lambda, both callers, so they cannot
#    drift"; a third caller is exactly when that stops being true.
arrival_owner() {   # $1 = extended-regex, $2 = what to call instead
  # One line on purpose: the lint at the top of this file reads a single
  # line at a time, so an `|| true` wrapped onto the next one looks missing.
  hits=$(grep -rlE "$1" app/src/main/java --include=*.kt | grep -vE "/(ConfigSync|ProfileLooks)[.]kt$" | tr "\n" " " || true)
  [ -z "$hits" ] || guard_fail "$1 is called outside ConfigSync.kt (in $hits). Call $2 so the arrival paths cannot drift."
}
arrival_owner "ProfileLooks[(][^)]*[)][.]ack[(]" "ConfigSync.applyArrived"
arrival_owner "KidNotices[.]configChange[(]" "ConfigSync.applyArrived"
arrival_owner "ProfileLooks[.]mergeInto[(]" "ConfigSync.adoptLooks"

# 7. The hub may reach exactly two things: YouTube, for the crawl, and the
#    devices' /sync-now, for the nudge. It holds no credential on a device and
#    must not acquire one: it is the box on the NAS, the one meant to face the
#    internet eventually. So the nudge carries no data and the device pulls
#    and authenticates as it always does, and the crawl goes through the
#    shared client with its host allow-list armed. Four clauses, because a
#    substring scan for "HttpClient" cannot tell the crawler's one client from
#    a second, unarmed one.
#    (a) hub/src opens no connection of its own outside HubNudge.kt.
outbound=$(grep -rlE "openConnection|HttpClient|Socket[(]" hub/src/main/kotlin | grep -v "/HubNudge[.]kt$" | tr "\n" " " || true)
if [ -n "$outbound" ]; then
  guard_fail "the hub makes an outbound call outside HubNudge.kt (in $outbound). The hub crawls through :crawl's Http and nudges through HubNudge; nothing else."
fi
nudge_url=$(grep -c "sync-now" hub/src/main/kotlin/io/yosemitekids/hub/HubNudge.kt || true)
[ "$nudge_url" -ge 1 ] || guard_fail "HubNudge no longer posts to /sync-now. That route is the whole contract."
#    (b) :crawl builds exactly one client, in Http.kt, and opens no raw socket.
clients=$(grep -rlE "OkHttpClient[.]Builder[(]|OkHttpClient[(]" crawl/src/main/kotlin | grep -v "/Http[.]kt$" | tr "\n" " " || true)
if [ -n "$clients" ]; then
  guard_fail ":crawl builds an OkHttpClient outside Http.kt (in $clients). One client, so the hub's allow-list covers every fetch."
fi
rawnet=$(grep -rlE "openConnection|Socket[(]" crawl/src/main/kotlin | tr "\n" " " || true)
if [ -n "$rawnet" ]; then
  guard_fail ":crawl opens a connection around the shared client (in $rawnet). Everything goes through Http.client."
fi
#    (c) the hub arms the allow-list before anything fetches.
if ! grep -q "Http.restrictTo(io.yosemitekids.app.data.Http.HUB_HOSTS)" hub/src/main/kotlin/io/yosemitekids/hub/Main.kt; then
  guard_fail "hub Main.kt does not arm Http.restrictTo(Http.HUB_HOSTS) at startup. Without it the crawler could reach any host."
fi
#    (d) the allow-list names YouTube's hosts and nothing else.
hosts=$(grep -A3 "val HUB_HOSTS" crawl/src/main/kotlin/io/yosemitekids/app/data/Http.kt | grep -oE "$q[a-z0-9.-]+$q" | tr -d "$q" | tr "\n" " " || true)
[ -n "$hosts" ] || guard_fail "Http.HUB_HOSTS is empty or unreadable; the hub's allow-list must name YouTube's hosts."
for h in $hosts; do
  case "$h" in
    youtube.com|youtu.be|googlevideo.com|ytimg.com|ggpht.com|googleusercontent.com) ;;
    *) guard_fail "Http.HUB_HOSTS names $h, which is not one of YouTube's hosts. The hub reaches YouTube and nothing else." ;;
  esac
done
# 6. A worker that nothing schedules is dead code that reads as shipped.
for w in IndexCrawlWorker ConfigSyncWorker ContentWarmWorker; do
  grep -q "$w.schedule(" app/src/main/java/io/yosemitekids/app/ui/MainActivity.kt ||
    guard_fail "$w is never scheduled from MainActivity, so it never runs."
done
# 8. The roadmap must not outlive the code it points at.
#    docs/ROADMAP.md ends in an Anchors table: each row names code an item
#    depends on. When an anchor stops resolving, that item was almost
#    certainly finished and nobody deleted it — which is precisely how the
#    old roadmap came to claim the hub had three settings pages after six
#    had shipped. Prose cannot be guarded; a reference to real code can.
roadmap=docs/ROADMAP.md
if [ -f "$roadmap" ]; then
  # Rows look like: | §2A reachability | `symbol or path` | code |
  while IFS="|" read -r _ item anchor kind _; do
    # Backticks only, then trim the ends. Stripping every space would mangle
    # an anchor like "val address: String?" into something that never matches.
    a=$(printf "%s" "$anchor" | tr -d "\140" | sed "s/^ *//; s/ *$//")
    k=$(printf "%s" "$kind" | tr -d " ")
    [ -n "$a" ] || continue
    case "$k" in
      path) [ -e "$a" ] || ls "$a" >/dev/null 2>&1 ||
        guard_fail "ROADMAP.md item$item cites $a, which no longer exists. Is that item done? Delete it and its anchor row." ;;
      code) grep -rqF "$a" app/src core/src crawl/src hub/src scripts 2>/dev/null ||
        guard_fail "ROADMAP.md item$item cites \`$a\`, which is gone from the codebase. Is that item done? Delete it and its anchor row." ;;
    esac
  done < <(grep -E "^\| §" "$roadmap" || true)
fi
# 9. The AI API key must never reach cloud backup.
#    Both XMLs are include-mode, so the key stays on the device only because
#    its store is unlisted. Three comments say "Don't add it" and nothing
#    enforced it. The failure is a real credential with a balance riding to
#    Google from one plausible-looking line added by someone who never read
#    the comment. Note secrets_plain is the Keystore-failure fallback and is
#    NOT encrypted — it is the worse of the two to ship.
backup_xml=app/src/main/res/xml/backup_rules.xml
extract_xml=app/src/main/res/xml/data_extraction_rules.xml
secretstore=app/src/main/java/io/yosemitekids/app/data/SecretStore.kt
# Guard the guard: it hardcodes the store names, so a rename must fail here
# rather than silently disarming the check below.
grep -q "FILE = ${q}secrets${q}" "$secretstore" ||
  guard_fail "SecretStore.FILE is no longer \"secrets\" — update guard 9 in check.sh and check.ps1 to match, or the backup check stops covering anything."
grep -q "FALLBACK_FILE = ${q}secrets_plain${q}" "$secretstore" ||
  guard_fail "SecretStore.FALLBACK_FILE is no longer \"secrets_plain\" — update guard 9 in check.sh and check.ps1 to match."
for f in "$backup_xml" "$extract_xml"; do
  if grep -qE "path=${q}secrets(_plain)?${q}" "$f"; then
    guard_fail "$f backs up the AI API key store. Include-mode is the only thing keeping that credential on the device — remove the line."
  fi
done
# The two files say "keep the two files in lockstep" and nothing checked that
# either. A rule added to one and not the other is backed up on some Android
# versions and not others, which is the hardest kind of bug to notice.
b_set=$(grep -oE "path=${q}[^${q}]+${q}" "$backup_xml" | sort | tr "\n" " " || true)
c_set=$(sed -n "/<cloud-backup>/,/<\/cloud-backup>/p" "$extract_xml" | grep -oE "path=${q}[^${q}]+${q}" | sort | tr "\n" " " || true)
[ "$b_set" = "$c_set" ] ||
  guard_fail "backup_rules.xml and data_extraction_rules.xml <cloud-backup> list different files. They are the API<=30 and API31+ twins of one rule and must match."
# 10. The two gate scripts must declare the same guards.
#     They are mirrors by convention and nothing checked it, so they had
#     already drifted once. CI runs only the bash one — which makes the
#     PowerShell mirror the half that can rot unnoticed, and it is the half
#     the author of this project actually runs before committing.
#
#     Compares the numbered headings, not the logic: two languages cannot be
#     diffed, but "a guard was added to one file and not the other" is the
#     failure that actually happens, and a heading is enough to catch it.
sh_guards=$(grep -oE "^# [0-9]+[.]" scripts/check.sh | tr -d " #." | sort -n | tr "\n" " " || true)
ps_guards=$(grep -oE "^# [0-9]+[.]" scripts/check.ps1 | tr -d " #." | sort -n | tr "\n" " " || true)
if [ "$sh_guards" != "$ps_guards" ]; then
  guard_fail "check.sh declares guards [$sh_guards] and check.ps1 declares [$ps_guards]. They are mirrors; add it to both."
fi
# 11. Every page the hub serves must have something to render.
#     index.html dispatches through a PAGES map and falls back to Kids for
#     an unknown id, so a page added to HubWeb.pages without a renderer
#     shows a tab with the WRONG PAGE UNDER IT rather than an error. Guard 3
#     only proves the page exists on both sides; this proves it was built.
hubhtml=hub/src/main/resources/web/index.html
for id in $(grep -hoE "HubPage[(]$q[^$q]+$q" "$hubweb" | sed -E "s/HubPage[(]$q//; s/$q//" | sort -u); do
  grep -qE "(^|[ ,{])$id: page" "$hubhtml" ||
    guard_fail "the hub serves a page called $id with no renderer in index.html ROUTES — it would silently show the root."
done
#     And every hash route the page links to, which is the same failure from
#     the other end. The GUI navigates by location.hash now, because it is
#     installed and the system Back button is the only navigation an installed
#     page has; an unknown route falls through to the root, so a parent taps a
#     row and lands back where they started with nothing to read. The root is
#     a key of its own, or the page cannot answer its own front door.
grep -qF "$q$q: page" "$hubhtml" ||
  guard_fail "index.html has no ROUTES entry for the root (a $q$q key). Every unknown route falls back to it, including #/."
for seg in $(grep -ohE "$q#/[a-z]+" "$hubhtml" | sed "s|.*#/||" | sort -u); do
  grep -qE "(^|[ ,{])$seg: page" "$hubhtml" ||
    guard_fail "index.html links to #/$seg and ROUTES has no renderer for it. The route falls back to the root, so the row appears to do nothing."
done
# The gate globs *Test.kt here, in check.ps1 and in CI. Anything else is
# skipped by all three and looks green.
misnamed=$(find app/src/test/java/io/yosemitekids/app core/src/test/kotlin/io/yosemitekids/app crawl/src/test/kotlin/io/yosemitekids/app -maxdepth 1 -type f ! -name '*Test.kt' | tr '\n' ' ')
if [ -n "$misnamed" ]; then
  guard_fail "these test files will never run: $misnamed — rename to *Test.kt"
fi

# 12. MainViewModel's working init block sits below every property.
#     It launches work that reads those properties from other threads, and
#     Kotlin runs property initialisers and init blocks in textual order: a
#     declaration below the init re-runs after it (the kid's chip choices
#     init had just loaded reset to null, every time), and a property the IO
#     thread reads before its initialiser has run is null (orderChannels got
#     sort = null and the app died at launch). Move the block, never the
#     property.
vm=app/src/main/java/io/yosemitekids/app/ui/MainViewModel.kt
last_init=$(grep -n '^    init {' "$vm" | tail -1 | cut -d: -f1 || true)
last_prop=$(grep -nE '^    (private |internal |public )?(override )?(lateinit )?(var|val) ' "$vm" | tail -1 | cut -d: -f1 || true)
if [ -z "$last_init" ] || [ -z "$last_prop" ] || [ "$last_init" -le "$last_prop" ]; then
  guard_fail "MainViewModel.kt: the init block (line ${last_init:-none}) must come after the last property declaration (line ${last_prop:-none}) — it starts work that reads them from other threads."
fi

# 13. The hub's build context is an allow-list. The image is built from the
#     repo root (CI, or a docker build on the NAS), which on the NAS also holds data/ — the family's
#     config and tokens, owned by the hub's uid and unreadable by the user
#     running the build: the build died on it before compiling anything.
#     .dockerignore must start by excluding everything and let in only what
#     the Dockerfile copies, one line per COPY source.
first=$(grep -vE '^\s*(#|$)' .dockerignore 2>/dev/null | head -1 || true)
if [ "$first" != "*" ]; then
  guard_fail ".dockerignore must begin with a bare '*' (exclude everything) — the hub build context would otherwise include data/ and .git."
fi
for src in $(grep -E '^COPY ' hub/Dockerfile | grep -v -- '--from=' | sed -E 's/^COPY +//' | awk '{NF--; print}' | tr ' ' '\n' | sort -u); do
  grep -qxF "!${src%/}" .dockerignore || grep -qxF "!${src%/}/" .dockerignore ||
    guard_fail "hub/Dockerfile copies '$src' but .dockerignore does not allow it (add '!$src') — the image build would fail with 'not found'."
done

# 14. Every route LanServer answers has a row in docs/LAN-API.md.
#     The table is where the phone side, the hub and a parent with curl learn
#     what a device does; a route added to LanServer.handle and not to the
#     table exists for nobody but its author. /join-hub and /leave-hub had
#     been missing for a whole round before this guard existed.
for r in $(grep -oE 'path == "/[a-z-]+"' app/src/main/java/io/yosemitekids/app/data/Pairing.kt | grep -oE '/[a-z-]+' | sort -u); do
  grep -qE "(GET|POST) $r[^a-z-]" docs/LAN-API.md ||
    guard_fail "LanServer answers $r and docs/LAN-API.md has no row for it. Add it to the route table."
done

# 15. The settings form adopts the whole of what it saved.
#     A save returns the STAMPED document, which is not the form: it carries
#     units a co-parent's push landed under the open form and keeps the
#     disk's copy of sections the editor left alone. Adopt that as the
#     baseline while the form keeps its own lists, and the next save shows
#     the stamper a unit in `base` and not in `next` — a deletion. A
#     co-parent's channel was tombstoned by this phone's second tap, and
#     every tap re-minted the AI unit. So `baseline` is assigned in exactly
#     one place, adopt(), which moves the form's fields in the same snapshot.
#     SettingsFormSaveTest proves the path is idempotent; this proves the
#     screen still goes through it.
settings=app/src/main/java/io/yosemitekids/app/ui/Settings.kt
baseline_writes=$(grep -cE '^\s*baseline = ' "$settings" || true)
if [ "$baseline_writes" -ne 1 ] || ! grep -q 'fun adopt(result: FormSave)' "$settings"; then
  guard_fail "Settings.kt assigns baseline in $baseline_writes places (must be exactly one, inside adopt(result: FormSave)). Route every save through saveForm() and adopt(), so the form takes the carried units too."
fi
if grep -qE 'configStore\.save\(' "$settings" && [ "$(grep -cE 'configStore\.save\(' "$settings" || true)" -ne 1 ]; then
  guard_fail "Settings.kt calls configStore.save() directly more than once (the kid migration before the form exists is the one allowed). The form's saves go through saveForm() so the stamped result is adopted."
fi

# 16. Today's bonus minutes come from two stores, each read in exactly one
#     place. The legacy LAN grant lands in prefs "bonusMs"; the config's
#     grants are taken by id into prefs "grants". SessionGuard.bonusMs() is
#     the one sum. A second reader of either store would add the two up its
#     own way, and the settings root, the stats screen and the enforcement
#     path could then disagree about how much time a kid has today.
guardsrc=app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt
for pair in 'getLong("bonusMs"' 'getString("grants"'; do
  reads=$(grep -cF "$pair" "$guardsrc" || true)
  if [ "$reads" -ne 1 ]; then
    guard_fail "SessionGuard.kt reads $pair in $reads places (must be exactly one). Sum the two stores in bonusMs() and read that."
  fi
done

# 17. One crawler, one version stamp. The crawl loop lives in :crawl
#     (IndexCrawlRun) so the hub and the phone run the same batch; a second
#     copy of the loop in the app is the drift CLAUDE.md warns about. And the
#     cursor stamp is the generated ExtractorVersion: a BuildConfig field for
#     it would let the app and the hub stamp cursors differently while
#     believing they agree, and a cursor is readable only by its own stamp.
if grep -q "EXTRACTOR_VERSION" app/build.gradle.kts; then
  guard_fail "app/build.gradle.kts defines EXTRACTOR_VERSION again. The stamp is :crawl's generated ExtractorVersion.VALUE; there is one."
fi
if grep -rq "PAGES_PER_RUN\s*=" app/src/main/java; then
  guard_fail "the app defines its own PAGES_PER_RUN. The crawl loop is IndexCrawlRun in :crawl; the worker only calls it."
fi

# 18. The mirror must at least parse. Guard 10 compares the two scripts'
#     headings, not their syntax, and the PowerShell one sat unparseable for
#     a whole round (guard 14's foreach never closed) while the bash one, the
#     only one CI runs, stayed green. So each script syntax-checks the other
#     when the other's interpreter is on this machine. On a Linux runner with
#     no PowerShell this is a no-op, which is exactly why the author's own
#     machine has to run the gate before a commit.
ps=$(command -v pwsh || command -v powershell || true)
if [ -n "$ps" ]; then
  perr=$("$ps" -NoProfile -ExecutionPolicy Bypass -Command '$e=$null; [void][System.Management.Automation.Language.Parser]::ParseFile("scripts/check.ps1", [ref]$null, [ref]$e); $e | ForEach-Object { "{0}: {1}" -f $_.Extent.StartLineNumber, $_.Message }' 2>&1 || true)
  [ -z "$perr" ] || guard_fail "scripts/check.ps1 does not parse: $perr"
fi

# 19. The hub's service worker caches the shell and never the family.
#     This origin serves a family's whole configuration behind a session
#     cookie, and anything a worker caches lands in Cache Storage, which
#     outlives the session, the sign-out and the tab. SHELL is therefore an
#     allow-list of static assets, and every other request is passed through
#     untouched — /api included. A one-line edit here would silently write
#     kids, rules and device names to disk in every browser that ever opened
#     the page.
sw=hub/src/main/resources/web/sw.js
shell_paths=$(sed -n "/^var SHELL/,/\]/p" "$sw" | grep -oE "$q/[A-Za-z0-9./-]*$q" | tr -d "$q" || true)
[ -n "$shell_paths" ] || guard_fail "cannot read SHELL out of $sw; guard 19 is blind."
for p in $shell_paths; do
  case "$p" in
    /|/manifest.webmanifest|/icon-*.png) ;;
    *) guard_fail "the hub's service worker caches $p. SHELL is a static-asset allow-list — caching anything else puts family data in Cache Storage." ;;
  esac
done
grep -q "SHELL.indexOf(url.pathname) === -1" "$sw" ||
  guard_fail "the hub's service worker no longer skips paths outside SHELL, so every request would pass through its cache."

# 20. Every asset the GUI names is actually served.
#     "/" answers anything with no route of its own, so a renamed icon does
#     not 404 — it returns the page's HTML with a 200, and the manifest is
#     merely ignored. The app then stops being installable and nothing says
#     why.
srv=hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt
named=$( { grep -oE "${q}src${q}: ${q}/[A-Za-z0-9.-]+${q}" hub/src/main/resources/web/manifest.webmanifest || true;
           grep -oE "href=${q}/[A-Za-z0-9.-]+${q}" hub/src/main/resources/web/index.html || true; } |
         grep -oE "/[A-Za-z0-9.-]+" | sort -u || true)
[ -n "$named" ] || guard_fail "neither the manifest nor index.html names a single asset; guard 20 is blind."
for a in $named; do
  grep -q "${q}$a${q}" "$srv" ||
    guard_fail "the hub GUI references $a and HubServer serves no such route — the catch-all would answer it with the page HTML, and the icon or manifest would fail silently."
done

# 21. A grant that arrives in the config has to be applied by the device
#     that receives it.
#     Grants were moved into the merged config so a television asleep when a
#     parent tapped "Add time" would find the minutes when it woke. Nothing
#     read them: Whitelist.grantsFor had no caller anywhere, and every path
#     that computes a budget takes grants as a defaulted empty list, so only
#     the granting phone and the LAN fast path ever applied one. The feature
#     was shipped, documented and dead. Nothing failed, because a function
#     with no caller breaks no test.
if ! grep -q "applyGrants(" app/src/main/java/io/yosemitekids/app/data/ConfigSync.kt; then
  guard_fail "ConfigSync no longer applies the config's grants on arrival — a device that was asleep when the parent granted time silently never gets it."
fi
if ! grep -rq "grantsFor(" app/src/main/java; then
  guard_fail "nothing in the app reads Whitelist.grantsFor, so config-carried grants reach every device and are applied by none."
fi

# 22. The hub answers a device's routes, or refuses them by name. Never with
#     the page.
#     HubServer registers "/" last so an unknown path lands on the admin GUI
#     rather than a 404 a parent has to interpret. For a human that is right;
#     for a device it is a lie. A phone sweeps /watchstate, /verdicts and
#     /stats across EVERY paired peer including the hub: all three answered
#     200 with HTML, the two mergers parsed it to nothing, and StatsCache
#     wrote index.html into files/stats_cache/ on every sweep for ever.
#     Nothing failed, because a 200 is a success.
hubsrv=hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt
device_only=$(sed -n '/val DEVICE_ONLY = setOf(/,/)$/p' "$hubsrv")
[ -n "$device_only" ] || guard_fail "HubServer.kt declares no DEVICE_ONLY set; guard 22 is blind."
for r in $(grep -oE 'path == "/[a-z-]+"' app/src/main/java/io/yosemitekids/app/data/Pairing.kt | grep -oE '/[a-z-]+' | sort -u); do
  if grep -qF "createContext(${q}$r${q})" "$hubsrv"; then continue; fi
  printf '%s' "$device_only" | grep -qF "${q}$r${q}" ||
    guard_fail "LanServer answers $r and the hub neither implements it nor names it in HubServer.DEVICE_ONLY — its catch-all would hand a device the admin page with a 200."
done

# 23. The browser mints no identifiers.
#     A kid id is a merge key — `kid|<id>`, and the key of every per-kid
#     overlay, grant, verdict and device assignment filed under that child.
#     The GUI minted them from the clock (the low eight hex of Date.now()),
#     which is sequential, guessable and identical for two kids added in the
#     same millisecond on two faces of one household. A collision does not
#     fail; it merges two children into one profile with one set of rules.
#     Reading a clock in the browser is still fine and will be needed (the
#     hub takes a grant's local date from the parent's browser on purpose,
#     PLAN-hub-parity D22) — minting an id from one is not.
huihtml=hub/src/main/resources/web/index.html
for mint in 'toString(16)' 'Math.random' 'randomUUID'; do
  if grep -qF "$mint" "$huihtml"; then
    guard_fail "$huihtml mints an id with $mint. Ids are merge keys: leave the id off and let HubWeb mint it with Profile.newId(), or two faces of one household collide and two children become one profile."
  fi
done
grep -q "Profile.newId(" hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt ||
  guard_fail "HubWeb no longer mints kid ids with Profile.newId(). Something has to: the browser deliberately sends a kid with no id at all."

# 24. A device tells the hub who it is, and the hub reads the same header.
#     The hub authenticates a device by a token IT minted at enrolment, which
#     no device has ever heard of; every device resolves
#     config.deviceProfiles by its own pairing token. X-Device-Id is the only
#     bridge between the two, and it spans two modules with nothing tying the
#     spelling together — rename it on one side and everything still
#     compiles, every test still passes, and "this device is for Emma" goes
#     back to doing nothing at all, silently, because a map lookup that
#     misses is indistinguishable from a device nobody assigned.
for f in app/src/main/java/io/yosemitekids/app/data/Pairing.kt \
         hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt; do
  grep -qF "${q}X-Device-Id${q}" "$f" ||
    guard_fail "$f no longer names ${q}X-Device-Id${q}. Both ends must spell it the same, or the hub cannot key a device→kid assignment by anything the device will ever read."
done

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
#     cost of guessing — and a second route that verified the secret its own
#     way would restore both holes without failing a single test.
hubmain=hub/src/main/kotlin
#    (a) One header name, named once. A second spelling is a second gate.
admin_hdr=$(grep -rhoF "${q}X-Admin-Token${q}" $hubmain | wc -l || true)
if [ "$admin_hdr" -ne 1 ]; then
  guard_fail "${q}X-Admin-Token${q} appears $admin_hdr times in $hubmain (must be exactly once, in adminGate). Every presentation of the admin secret goes through that one gate."
fi
#    (b) The throttle is consulted in exactly one place, and that place is the
#        gate. A route that asked mayAttempt() itself would be a route that
#        could forget to.
stray_attempt=$(grep -rlF "mayAttempt()" $hubmain | grep -vE "/(HubSessions|HubServer)[.]kt$" | tr "\n" " " || true)
if [ -n "$stray_attempt" ]; then
  guard_fail "mayAttempt() is called in $stray_attempt. Only adminGate() may ask; everything else goes through it."
fi
attempts=$(grep -cF "mayAttempt()" "$hubsrv" || true)
if [ "$attempts" -ne 1 ]; then
  guard_fail "HubServer.kt calls mayAttempt() $attempts times (must be exactly one, inside adminGate)."
fi
gate_owner=$(awk '/ fun /{last=$0} /mayAttempt\(\)/{print last; exit}' "$hubsrv" || true)
case "$gate_owner" in
  *"adminGate("*) ;;
  *) guard_fail "mayAttempt() is called from '${gate_owner:-nothing}', not from adminGate(. The throttle must run before any derivation, in the one gate." ;;
esac
#    (c) Nothing prints a credential. The boot line may name the regime — "a
#        password is set" — but a VALUE reaches a println only by interpolation
#        or concatenation, and a container log is a broadcast: docker logs
#        replays it from the beginning, Container Manager shows it in a web UI,
#        and a log driver ships it to a file whose permissions have nothing to
#        do with /data.
printed=$(grep -rniE "println|print\(|System[.]err" $hubmain | grep -iE "password|secret" | grep -E '[$+]' || true)
if [ -n "$printed" ]; then
  guard_fail "the hub prints something on a line naming a password or secret: $printed — name the regime, never the value."
fi
#    (d) Recovery is a token you already hold, not a route. A reset endpoint on
#        a box whose stated future is facing the internet is a second front
#        door, and every one of these names is what that door gets called.
for door in "/forgot" "/reset" "/recover"; do
  if grep -rqF "createContext(${q}${door}${q})" $hubmain; then
    guard_fail "the hub serves $door. Recovery is the token from the log, deliberately not a route — a reset endpoint is a second front door on a box meant to face the internet."
  fi
done

# 26. Parity per CONTROL, not per group.
#     Guards 1-3 hold the two faces to the same GROUPS, and a group is too
#     coarse to be a promise: hubReady is permanent, so a control added inside
#     a group the hub already renders slips through with nothing to notice.
#     That is not hypothetical. It had happened twice and both were live:
#     screen-time-rules claimed the hub while the hub drew four of a kid's
#     rules — no minVideoMinutes, no pause — and blocked-times claimed the hub
#     with no windows editor at all. Four clauses, because "the two faces
#     agree" is four separate properties and each fails on its own.
wl=core/src/main/kotlin/io/yosemitekids/app/data/Whitelist.kt
mtext=$(cat "$manifest")
#    (a) Every config leaf is claimed by exactly one control, or exempted by
#        name WITH a reason. A field with nothing to set it is a field a parent
#        cannot reach on either face, and it fails here on the day it is added
#        rather than in a message from a family six months later.
class_props() {   # $1 = data class name; prints its declared properties
  # One line, because the lint at the top of this file reads a line at a time.
  awk -v head="data class $1(" 'index($0, head) == 1 { inside = 1; next } inside && /^\)/ { exit } inside' "$wl" | grep -oE "^    val [A-Za-z]+" | awk '{ print $2 }' || true
}
for cls in "Whitelist:" "Limits:limits." "AiConfig:ai."; do
  props=$(class_props "${cls%%:*}")
  [ -n "$props" ] || guard_fail "guard 26 cannot read ${cls%%:*}'s properties out of $wl; it is blind."
  for p in $props; do
    path="${cls#*:}$p"
    claimed=0; exempt=0
    case "$mtext" in *"writes = $q$path$q"*) claimed=1 ;; esac
    case "$mtext" in *"$q$path$q to "*) exempt=1 ;; esac
    [ $((claimed + exempt)) -eq 1 ] ||
      guard_fail "$path is claimed by $claimed control and exempted $exempt times in SettingsSurface — want exactly one. Give it a SettingsControl with writes = $q$path$q, or add it to NOT_A_CONTROL with the reason there is nothing to set it."
  done
done
#    (e) Every kind the manifest can declare has a branch in the generic
#        renderer. Clause (b) asks only CUSTOM controls to prove themselves,
#        because everything else is supposed to be drawn from the declaration —
#        so a kind added to the enum and not to renderControl() falls through
#        to null and the control is absent from a page that still passes every
#        other check here.
kinds=$(awk -F'[{}]' '/^enum class ControlKind/ { print $2 }' "$manifest" | tr -d " " | tr "," " " || true)
[ -n "$kinds" ] || guard_fail "guard 26 cannot read ControlKind out of $manifest; it is blind."
for k in $kinds; do
  if [ "$k" != "CUSTOM" ]; then
    grep -qF "c.kind === $q$k$q" "$hubhtml" ||
      guard_fail "ControlKind.$k has no branch in renderControl() in $hubhtml, so a control of that kind draws nothing at all. Give it one — the manifest must not be able to declare something the hub silently drops."
  fi
done
#    (b)-(d), per control, read in file order: a control belongs to the section
#        declared above it, which is what lets the section's own where/hubReady
#        decide whether the hub owes it anything.
#        From the list down, so the `data class SettingsControl(` declaration
#        above it is not read as a control of its own.
records=$(sed -n "/val sections: List<SettingsSection> = listOf(/,\$p" "$manifest" | tr "\n" " " | sed -E "s/SettingsSection[(]/\n@S /g; s/SettingsControl[(]/\n@C /g")
ready=0
declared=""
while IFS= read -r rec; do
  case "$rec" in
    "@S "*)
      ready=0
      case "$rec" in *"Where.BOTH, true,"*|*"Where.HUB, true,"*) ready=1 ;; esac
      continue ;;
    "@C "*) ;;
    *) continue ;;
  esac
  rest=${rec#*$q}
  id=${rest%%$q*}
  case "$id" in
    *[!a-z0-9-]*|"") guard_fail "guard 26 cannot read a control id out of $manifest; it is blind." ;;
  esac
  declared="$declared $id"
  case "$rec" in *"kind = ControlKind.CUSTOM"*) kind=CUSTOM ;; *) kind=PLAIN ;; esac
  case "$rec" in
    *"where = Where.PHONE"*) face=PHONE ;;
    *"where = Where.HUB"*) face=HUB ;;
    *) face=BOTH ;;
  esac
  # Empty first, so `why = ""` does not read as a reason.
  case "$rec" in *"why = $q$q"*) haswhy=0 ;; *"why = $q"*) haswhy=1 ;; *) haswhy=0 ;; esac
  #  (b) A control the hub is expected to have is either drawn generically from
  #      the manifest or hand-written and marked. Nothing may be merely claimed.
  if [ "$face" != PHONE ] && [ "$ready" = 1 ] && [ "$kind" = CUSTOM ]; then
    grep -qE "data(-|set[.])control ?= ?$q$id$q" "$hubhtml" ||
      guard_fail "the control $q$id$q is on the hub's list and index.html does not build it. A CUSTOM control is hand-written, so mark its card data-control=$q$id$q; anything a generic renderer could draw should not be CUSTOM."
  fi
  #  (c) A control the phone is expected to have is asked for by id. The
  #      manifest owns the words, so the reference is load-bearing rather than
  #      ceremonial — without it there is no label to render. CUSTOM controls
  #      are exempt on purpose: their words are their own, which is what CUSTOM
  #      means, so a reference there would prove nothing.
  if [ "$face" != HUB ] && [ "$kind" != CUSTOM ]; then
    grep -rqF "ctl($q$id$q)" app/src/main/java/io/yosemitekids/app/ui ||
      guard_fail "the control $q$id$q is declared for the phone and no ui/*.kt asks for it. Render it with ctl($q$id$q), or move it to Where.HUB and say why."
  fi
  #  (d) "Specific to each" is a decision, and one with no recorded reason is
  #      re-argued every round by someone who cannot tell it from an omission.
  if [ "$face" != BOTH ] && [ "$haswhy" = 0 ]; then
    guard_fail "the control $q$id$q is ${face}-only with a blank why. Say what the other face cannot do, where the next session will meet it."
  fi
done <<RECORDS
$records
RECORDS
[ -n "$declared" ] || guard_fail "guard 26 read no controls out of $manifest; it is blind."
#        And the other direction, which (c) alone does not cover: an id the
#        phone asks for and the manifest does not declare. control() throws,
#        and it throws at render time on a screen a parent just opened.
for asked in $(grep -rhoE "ctl[(]$q[a-z0-9-]+$q[)]" app/src/main/java/io/yosemitekids/app/ui | sed -E "s/ctl[(]$q//; s/$q[)]//" | sort -u || true); do
  case " $declared " in
    *" $asked "*) ;;
    *) guard_fail "the phone asks for a settings control called $q$asked$q, which SettingsSurface does not declare. SettingsSurface.control() throws — on the screen, in front of a parent." ;;
  esac
done

# 27. The hub reads no calendar.
#     A container runs UTC and the family does not. Every local day and local
#     midnight the hub stores therefore comes from the parent's browser, and
#     the hub only bounds it: PAUSE_MAX_AHEAD_MS for a pause, and
#     GRANT_MAX_DAYS_AWAY for the day a grant names. It is the same rule
#     ConfigStamp.stamped(today = null) already encodes, and the reason
#     HubStore.edit passes null — a day that ends hours early on the NAS
#     tombstones a grant at teatime and takes a kid's minutes away.
#     Worth a guard rather than a comment because of how it fails. A hub in
#     UTC and a family in Auckland disagree for thirteen hours of every day,
#     so the symptom is bonus minutes that stop working in the evening, for
#     some households, some of the time. Nothing throws; the container is
#     right about its own clock and wrong about the family's.
for cal in "LocalDate" "Calendar" "SimpleDateFormat" "ZoneId.systemDefault"; do
  dated=$(grep -rlF "$cal" hub/src | tr "\n" " " || true)
  [ -z "$dated" ] || guard_fail "$dated names $cal. The container's clock is UTC and the family's is not — that is why HubStore.edit passes today = null. A day or a midnight arrives from the parent's browser and the hub only checks how far away it is (HubWeb.PAUSE_MAX_AHEAD_MS, HubWeb.GRANT_MAX_DAYS_AWAY)."
done

# 28. One backup envelope, because two faces write it and two faces read it.
#     The phone exports through Backup and the hub serves GET /api/backup, and
#     the whole point of taking a file off the NAS is the day the NAS is gone
#     and a phone is all that is left. Two copies of `kind` and `schema` drift
#     in one release and the file silently stops crossing — with the symptom
#     arriving on the worst day it could. So the envelope is declared once, in
#     :core, and every other file reads the constants from there.
envelope=core/src/main/kotlin/io/yosemitekids/app/data/BackupFile.kt
for word in "yosemite-kids-backup" "pickwick-backup"; do
  homes=$(grep -rlF "$q$word$q" app/src/main core/src/main crawl/src/main hub/src/main || true)
  if [ "$homes" != "$envelope" ]; then
    guard_fail "the backup envelope's $q$word$q is spelled out in [${homes:-nothing}]. It belongs in $envelope alone — a phone must be able to open a file the hub wrote, and the reverse."
  fi
done


# 29. A device route the hub answers is authenticated, every time.
#     Guard 22 holds the hub to answering a device's routes or refusing them
#     by name; this is the other half of the same decision. The moment a route
#     moves off DEVICE_ONLY it stops being a 404 to the whole LAN and starts
#     serving a body, and `/verdicts` is the first one whose body is about the
#     family's *viewing* rather than their settings — a verdict carries the
#     title, channel and thumbnail of something a child watched or was stopped
#     from watching. Forgetting `authorised(ex)` in a new handler compiles,
#     passes every other check here, and is invisible from outside unless
#     someone thinks to call the route with no token.
hubsrv=hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt
for r in $(grep -oE 'path == "/[a-z-]+"' app/src/main/java/io/yosemitekids/app/data/Pairing.kt | grep -oE '/[a-z-]+' | sort -u); do
  reg=$(grep -F "createContext(${q}$r${q})" "$hubsrv" || true)
  [ -n "$reg" ] || continue
  fn=$(printf "%s" "$reg" | sed -nE "s/.*guarded\(ex\) \{ ([a-zA-Z]+)\(ex\).*/\1/p")
  [ -n "$fn" ] || guard_fail "the hub registers $r in a shape guard 29 cannot read. Keep it as createContext(${q}$r${q}) { ex -> guarded(ex) { <handler>(ex) } } so the handler can be found."
  body=$(awk -v f="    private fun $fn(ex: HttpExchange)" 'index($0, f) == 1 { inside = 1 } inside { print; if (inside && /^    }$/) exit }' "$hubsrv")
  [ -n "$body" ] || guard_fail "guard 29 cannot find $fn(ex: HttpExchange) in $hubsrv; it is blind."
  printf "%s" "$body" | grep -q "authorised(ex)" ||
    guard_fail "the hub answers $r in $fn() without calling authorised(ex). That route is open to every peer on the LAN — take it back to DEVICE_ONLY or gate it on an enrolled token."
done

# 30. Every route the HUB registers has a row in docs/LAN-API.md too.
#     Guard 14 does this for LanServer by reading Pairing.kt, and has done
#     since /join-hub went a whole round undocumented. The hub's own routes
#     were never covered by it, and the omission is not academic: /enrol,
#     /pending, /health, /login, /logout and "/" had all been live for rounds
#     with five of them named in one sentence of prose and one not mentioned
#     at all. The hub is the half a second implementer and a parent with curl
#     meet first, because it is the box with a URL.
#
#     Scoped to the hub's own section, so a hub route cannot be excused by a
#     device row that happens to share its path — /status, /config and
#     /verdicts all appear in both tables and mean different things.
hubsrv=hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt
#
#     Table ROWS only, not the whole section: /status, /config and /verdicts
#     are all named in the paragraph introducing the table, so a section-wide
#     grep would count the sentence that says a route exists as documentation
#     of what it answers, which is the thing this guard is for.
hubdoc=$(awk '/^## The hub.s routes/ { f = 1 } f && /^[|] / { print }' docs/LAN-API.md)
[ -n "$hubdoc" ] ||
  guard_fail "docs/LAN-API.md has no \"## The hub's routes\" heading with a route table under it; guard 30 is blind."
hub_routes=$(grep -oE "createContext\(${q}/[a-z/-]*${q}" "$hubsrv" | grep -oE "/[a-z/-]*" | sort -u || true)
[ -n "$hub_routes" ] ||
  guard_fail "guard 30 read no createContext(${q}/…${q}) routes out of $hubsrv; it is blind."
for r in $hub_routes; do
  # A prefix context ("/api/") is documented by the routes underneath it, so
  # match on the stem. "/" is the page and the catch-all, and has a row of its
  # own saying exactly that.
  [ "$r" = "/" ] || r=${r%/}
  printf "%s" "$hubdoc" | grep -qE "(GET|POST) $r[^a-z-]" ||
    guard_fail "the hub registers $r and docs/LAN-API.md's hub section has no row for it. Add it to the route table — that table is the only place the hub's wire is written down."
done
# 31. The form factor is decided in exactly one place.
#     The same two-line UiModeManager check was inlined in four files and the
#     answer travelled onward under four different names (cards, rounded,
#     greet, voice), so no call site could tell whether two screens were
#     making the same decision or two different ones. FormFactor.kt is now the
#     only place that asks, and Pairing.kt is the deliberate exception: it
#     looks identical but answers "what kind of device is this on the LAN",
#     which decides whether a device auto-assumes the KID role. Folding them
#     together would couple pairing behaviour to a layout concern.
ffhits=$(grep -rl "UI_MODE_TYPE_TELEVISION" --include=*.kt app/src/main | sort || true)
ffwant="app/src/main/java/io/yosemitekids/app/data/Pairing.kt
app/src/main/java/io/yosemitekids/app/ui/FormFactor.kt"
[ "$ffhits" = "$ffwant" ] ||
  guard_fail "the form factor is detected somewhere new. Call formFactorOf() (or read LocalFormFactor in a container) instead of asking UiModeManager again. Found in:
$ffhits"

# 32. Containers read LocalFormFactor; leaves take a parameter.
#     This is the rule that keeps every @Preview and Compose test able to
#     render the other shape. Break it and nothing fails - the app compiles,
#     runs on a device, and quietly becomes impossible to check in the shape
#     you are not holding. That silence is exactly why it is a guard and not a
#     comment. A leaf wanting the form factor takes it as a parameter whose
#     default is LocalFormFactor.current, so the caller can always override.
#     Screen hosts are the sanctioned readers, named here so that adding one
#     is a visible decision in a diff rather than a habit.
ffread=$(grep -rn "LocalFormFactor.current" --include=*.kt app/src/main | grep -vE ": *FormFactor *= *LocalFormFactor\.current" | grep -vE "/(FormFactor|YosemiteScreen)\.kt:" || true)
[ -z "$ffread" ] ||
  guard_fail "LocalFormFactor.current is read outside a default-parameter expression. Containers may read it; a leaf takes 'formFactor: FormFactor = LocalFormFactor.current' so a preview or test can pass the other one:
$ffread"

if [ "${1:-}" = "--guards" ]; then echo "source invariants OK"; exit 0; fi



echo "== 1/6 compile (assembleDebug)"
./gradlew --no-daemon -q assembleDebug

if [ "${1:-}" = "--quick" ]; then echo "compile OK (quick mode)"; exit 0; fi

echo "== 2/6 core tests (no Android — the hub runs this exact code)"
./gradlew --no-daemon -q :core:test

echo "== 3/6 crawl tests (plain JVM — the hub runs this crawler too)"
./gradlew --no-daemon -q :crawl:test

echo "== 4/6 hub tests"
./gradlew --no-daemon -q :hub:test

echo "== 5/6 app unit tests (offline)"
# Every test class except the live-YouTube canaries. Both reach real YouTube
# unguarded, so a bot wall fails this gate for unrelated reasons.
args=()
for f in app/src/test/java/io/yosemitekids/app/*Test.kt; do
  name=$(basename "$f" .kt)
  case "$name" in ExtractorSmokeTest|SingleChannelProbeTest) continue ;; esac
  args+=(--tests "io.yosemitekids.app.$name")
done
./gradlew --no-daemon -q :app:testDebugUnitTest "${args[@]}"

echo "== 6/6 worker tests"
if command -v node >/dev/null 2>&1; then
  node --test worker/test/*.test.mjs
else
  echo "node not found — skipping worker tests"
fi

echo "all green"
