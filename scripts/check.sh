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
# UsageLedger joins them because its join is a merge in exactly the same
# sense - and see guard 44, which counts merge()'s parameters, because a
# `today: String` argument would sail straight past the grep below while
# being precisely the clock this rule exists to keep out.
for clockless in ConfigMerge MasterElection UsageLedger; do
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
#        Whitelist, Limits and AiConfig only: the classes nested inside them
#        (WhitelistEntry, Profile, TimeWindow, Grant, Pin) are not walked, so
#        a leaf like Pin.rank is covered only by whatever claims its
#        container. Extend the list below, and its twin in check.ps1, if that
#        ever stops being enough.
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

# 27. The hub reads the family's calendar, and never its own.
#     A container runs UTC and the family does not. A hub in UTC and a family
#     in Auckland disagree for thirteen hours of every day, so the symptom of
#     getting this wrong is bonus minutes that stop working in the evening,
#     for some households, some of the time. Nothing throws; the container is
#     right about its own clock and wrong about the family's.
#
#     This guard used to say "the hub reads no calendar" and ban four names.
#     The prose was already ahead of the code — a ZoneId.of(cfg.homeZone) call
#     would have passed it while the comment claimed it could not — and the
#     watch ledger has since made that path a real one: HubUsage windows the
#     family's minutes by the family's own day, taken from Whitelist.homeZone.
#     So the rule is now stated as what it actually is, in two clauses.
#
#     (a) The container's OWN calendar stays unreachable. Every local day and
#         local midnight the hub *stores* still arrives from the parent's
#         browser, bounded and never computed: HubWeb.PAUSE_MAX_AHEAD_MS for a
#         pause, GRANT_MAX_DAYS_AWAY for the day a grant names. Same rule
#         ConfigStamp.stamped(today = null) encodes, and the reason
#         HubStore.edit passes null — a day that ends hours early on the NAS
#         tombstones a grant at teatime and takes a kid's minutes away.
for cal in "LocalDate" "LocalDateTime" "Calendar" "SimpleDateFormat" "ZoneId.systemDefault" "TimeZone.getDefault"; do
  dated=$(grep -rlF "$cal" hub/src | tr "\n" " " || true)
  [ -z "$dated" ] || guard_fail "$dated names $cal. The container's clock is UTC and the family's is not — that is why HubStore.edit passes today = null. A day or a midnight arrives from the parent's browser and the hub only checks how far away it is (HubWeb.PAUSE_MAX_AHEAD_MS, HubWeb.GRANT_MAX_DAYS_AWAY); a day the hub needs for itself comes from Whitelist.homeZone through FamilyDay.zoneOrNull, which answers null rather than guessing."
done
#     (b) A zone reaches this box from the family's config or not at all.
#         Nothing above would stop the next person writing ZoneId.of("UTC") or
#         reading one out of an environment variable — both of which look
#         local and correct and are the same bug in a different hat. A
#         YOSEMITE_KIDS_TZ env var was considered and rejected for exactly
#         that reason: one more thing to get wrong on the NAS, and silently
#         wrong the week a family travels.
zoned=$(grep -rn "ZoneId\|ZoneOffset\|atZone(" hub/src/main | grep -v "homeZone" || true)
[ -z "$zoned" ] || guard_fail "the hub names a time zone that did not come from the family's config:
$zoned
A zone on this box is Whitelist.homeZone or it is nothing. Route it through FamilyDay.zoneOrNull(cfg.homeZone), which answers null when the family has named none — a container that guessed would trim a household in Auckland a day early, every day, with nothing to show for it."

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

# 33. Every shelf the home screen names is a shelf the home can draw.
#     The home is one list of shelves now, ordered by string id, and the walk
#     that draws it is a `when` on that id. Name a shelf in HomeShelf and
#     forget either half - the catalogue it must appear in, or the branch that
#     renders it - and nothing fails: the id is a legal thing to save, the
#     parent's future editor will offer to move it, and it draws nothing at
#     all, silently, on both form factors. The compiler cannot help: `when`
#     over a String has no exhaustiveness, which is the price of ids that
#     survive a build inserting a shelf in the middle.
shelf_src=core/src/main/kotlin/io/yosemitekids/app/ui/HomeSections.kt
shelves_src=app/src/main/java/io/yosemitekids/app/ui/HomeShelves.kt
shelf_ids=$(sed -n '/object HomeShelf {/,/^}/p' "$shelf_src" | grep -oE 'const val [A-Z_]+' | awk '{print $3}' || true)
[ -n "$shelf_ids" ] || guard_fail "no ids found in object HomeShelf; guard 33 is blind."
shelf_catalogue=$(sed -n '/val HOME_SHELVES/,/^)/p' "$shelf_src")
for id in $shelf_ids; do
  echo "$shelf_catalogue" | grep -q "HomeShelf\.$id\b" ||
    guard_fail "HomeShelf.$id is not in HOME_SHELVES, so no home ever draws it. Add it to the catalogue, or delete the id."
  grep -q "HomeShelf\.$id ->" "$shelves_src" ||
    guard_fail "HomeShelf.$id has no branch in HomeShelves.kt. A shelf a parent can order and the home cannot draw fails silently, on both form factors."
done

# 34. The chrome's time-left ticks by interpolation, never by re-reading.
#     SessionGuard.remainingAll() runs rolloverIfNewDay(), which WRITES to
#     preferences. The number it feeds now rides permanent chrome on every
#     screen and updates once a second, so the obvious "fix" for a stale
#     figure - ask again, faster - is a disk write per second on a television
#     that may sit on one page for hours. It would also look completely
#     correct in review and in a screenshot.
#
#     So TimeLeft.kt owns the tick and is forbidden to call the guard: it ages
#     the last authoritative read through interpolateRemainingMs and nothing
#     else. Comments may name SessionGuard - explaining why is the point - so
#     only code lines are searched.
tl_src=app/src/main/java/io/yosemitekids/app/ui/TimeLeft.kt
[ -f "$tl_src" ] ||
  guard_fail "$tl_src is gone; guard 34 is blind. The chrome's live time-left value lives there."
tl_code=$(grep -vE '^[[:space:]]*(//|\*|/\*)' "$tl_src" || true)
tl_bad=$(echo "$tl_code" | grep -nE "SessionGuard|sessionGuard" || true)
[ -z "$tl_bad" ] ||
  guard_fail "TimeLeft.kt reads SessionGuard. The 1 Hz value must be interpolated from the last authoritative read (interpolateRemainingMs), not re-read - remainingAll() writes to prefs through rolloverIfNewDay(). Found: $tl_bad"
echo "$tl_code" | grep -q "interpolateRemainingMs(" ||
  guard_fail "TimeLeft.kt no longer calls interpolateRemainingMs. Whatever now produces the chrome's time-left must be a pure function with a test, or the value goes stale again."

# 35. A focus modifier only sees what comes AFTER it in the chain.
#     Two of them, and both fail in total silence.
#
#     onFocusChanged reports on the focus targets that FOLLOW it, so a
#     container watching its own subtree writes `.onFocusChanged { }
#     .focusGroup()`. Written the other way round it observes its ancestor
#     instead, the callback never fires for anything inside, and the feature
#     built on it - the TV nav rail collapsing when the remote leaves it -
#     simply never happens. Nothing throws, nothing logs, and it reads in
#     review as a design decision rather than as a bug.
#
#     focusRestorer has the same shape: it needs a focus target after it to
#     restore INTO, so it is `.focusRestorer().focusGroup()` (or straight onto
#     a lazy container, which supplies its own). Alone at the end of a chain
#     it is dead code that looks like a fix for the exact bug it is not
#     fixing.
#
#     And it may not sit on the page beside the rail at all. It was put there
#     so a dismissed dialog would hand the remote back to the kid's tile; on
#     the emulator its exit hook swallowed the LEFT press that should have
#     reached the rail, and the focus loss it was guarding against was then
#     measured and does not happen (the content Box in YosemiteScreen.kt says
#     what was pressed). Anyone putting it back there is fixing a bug that is
#     not there and breaking the one thing the rail must do.
#
#     The rail's own pair has to exist, not just be in the right order: take
#     the focusGroup off the rail's Column and onFocusChanged has nothing
#     after it to observe, and the rail never collapses - same silence.
#
#     Whitespace is stripped first, so a chain broken over four lines - which
#     is every chain in this codebase - reads as one string.
for f in $(find app/src/main -name '*.kt'); do
  flat=$(tr -d '[:space:]' < "$f")
  case "$flat" in
    *"focusGroup().onFocusChanged"*)
      guard_fail "$(basename "$f") writes .focusGroup().onFocusChanged. onFocusChanged only observes focus targets that FOLLOW it, so that order watches the ancestor and never fires for the group's own children. Write .onFocusChanged { }.focusGroup()." ;;
  esac
  # `|| true` inside each pipeline, not after it: with `set -o pipefail` a
  # grep that matches nothing — the case for almost every file — would
  # otherwise end the whole gate on its own success.
  all=$( { echo "$flat" | grep -oE '\.focusRestorer\([^)]*\)' || true; } | wc -l | tr -d ' ')
  ok=$( { echo "$flat" | grep -oE '\.focusRestorer\([^)]*\)\.(focusGroup|focusTarget|focusProperties)\(' || true; } | wc -l | tr -d ' ')
  [ "$all" = "$ok" ] ||
    guard_fail "$(basename "$f") applies .focusRestorer() with no focus target after it. It restores into the NEXT focus target in the chain; on its own it does nothing at all. Write .focusRestorer().focusGroup()."
  [ "$all" = "0" ] || [ "$(basename "$f")" != "YosemiteScreen.kt" ] ||
    guard_fail "YosemiteScreen.kt applies .focusRestorer(). On the page beside the TV nav rail its exit hook ate the LEFT press into the rail, and the focus loss it was meant to cure was measured on the emulator and does not happen. Leave the page a plain focusGroup; the content Box there says what was pressed."
done
rail_flat=$(tr -d '[:space:]' < app/src/main/java/io/yosemitekids/app/ui/TvNavRail.kt)
echo "$rail_flat" | grep -qE '\.onFocusChanged\{[^}]*\}\.focusGroup\(\)' ||
  guard_fail "TvNavRail.kt no longer has .onFocusChanged { }.focusGroup() on the rail's Column. That pair is how the host learns the remote has left the rail; without it the rail never collapses and nothing says so."

# 36. No colour literal on a kid-facing screen.
#     Every hue a kid sees comes from the scheme (the three looks and the
#     per-kid tint) or from KidTokens (action, timeWarning, watched, offline,
#     artworkScrim, onArtwork). A literal at the point of use is how the amber
#     warning came to exist twice at two alphas, and how a white label on the
#     light look's paper went invisible - it reviews fine and fails on a
#     screen the author was not holding. Theme.kt and KidTokens.kt are where
#     literals live on purpose. Icons.kt builds ImageVectors whose path fill
#     is a placeholder every Icon() call tints over. The Settings*.kt family,
#     KidsSettings, StatsScreen, SyncActivityScreen and DigestScreen are the
#     parent's face, on the named palette in Theme.kt. Comment lines are
#     skipped, so a comment may explain a literal it replaced.
#
#     TEMPORARY: YosemiteScreen.kt and HomeScreens.kt still carry literals.
#     Other hands were in them when this landed, so they are exempt here and
#     NAMED on every run rather than hidden in the pattern. Migrate them and
#     delete them from colour_temp: the guard fails the moment an exempt file
#     is clean, so the exemption cannot outlive its reason.
colour_files=$(ls app/src/main/java/io/yosemitekids/app/ui/*.kt | grep -vE '/(Theme|KidTokens|Icons|Settings[A-Za-z]*|KidsSettings|StatsScreen|SyncActivityScreen|DigestScreen)\.kt$' || true)
colour_temp=""
colour_pat='Color\(0x|Color\.White|Color\.Black'
for f in $colour_files; do
  base=$(basename "$f")
  hits=$(grep -nE "$colour_pat" "$f" | grep -vE '^[0-9]+:[[:space:]]*(//|\*|/\*)' || true)
  case " $colour_temp " in
    *" $base "*)
      [ -n "$hits" ] ||
        guard_fail "$base is clean now: remove it from guard 36's temporary exemption (colour_temp in check.sh, colourTemp in check.ps1)."
      echo "   guard 36: $base still carries colour literals - TEMPORARY exemption, remove at merge ($(echo "$hits" | wc -l | tr -d ' ') line(s))"
      ;;
    *)
      [ -z "$hits" ] ||
        guard_fail "colour literal on a kid-facing screen in $base. Use MaterialTheme.colorScheme, or kidTokens: artworkScrim/onArtwork over a picture or a scrim, timeWarning for time running out, action for the one thing to press. Found:
$hits"
      ;;
  esac
done

# 37. The player has no focusables.
#     Every key on the TV player goes through PlayerActivity.onKeyDown: one
#     integer cursor walks the toolbar slots, the two-button cards and the
#     track sheet. A focus modifier anywhere under the player's setContent
#     takes the d-pad away from that path - OK stops toggling playback - and
#     no screenshot catches it, because the controls still draw.
#     docs/SCREENS.md states the rule; this enforces it over every Player*.kt
#     file, comment lines excluded.
focus_bad=$(grep -nHE 'focusable\(|[fF]ocusRequester|onFocusChanged|tvFocusHighlight\(|focusTarget|focusProperties|focusGroup' app/src/main/java/io/yosemitekids/app/ui/Player*.kt | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' || true)
[ -z "$focus_bad" ] ||
  guard_fail "a focus modifier in the player. Its keys are the single onKeyDown cursor (TvToolbarSlot, handleTwoButtonKey); give the new control a cursor slot instead. Found:
$focus_bad"

# 38. Every unit ConfigStamp can mint is a unit ConfigMerge.merge decides.
#     A field can be added to Whitelist, toJson and fromJson, stamped in
#     ConfigStamp, claimed or exempted in SettingsSurface, and pass guard
#     26(a) and every test — and still never merge. The merge is a set of
#     hand-written loops, one per namespace, and a namespace with no loop is
#     rebuilt from the local document and never read from the peer. Nothing
#     throws: a co-parent's edit in that namespace is dropped by the first
#     device that merges it, silently, forever — which is exactly how per-kid
#     blocks and device assignments were once lost (the stamper's "Per-kid
#     overlays" comment records it). So every key ConfigStamp mints — a
#     `fun x(...) = "ns|..."` or a `const val X = "ns"` — must be named below
#     `fun merge(` in ConfigMerge.kt: as ConfigStamp.x, ConfigStamp::x, or the
#     bare "ns" literal the overlay loops take. Only the namespace is checked;
#     whether the loop is RIGHT is what core/src/test is for.
stamp_src=core/src/main/kotlin/io/yosemitekids/app/data/ConfigStamp.kt
merge_src=core/src/main/kotlin/io/yosemitekids/app/data/ConfigMerge.kt
merge_body=$(sed -n '/fun merge(/,$p' "$merge_src")
[ -n "$merge_body" ] || guard_fail "guard 38 cannot find fun merge( in ConfigMerge.kt; it is blind."
# One "name ns" per line: the Kotlin member and the namespace it mints.
stamp_units=$(grep -E '^    (fun [a-zA-Z]+\([^)]*\) *= *"[a-z.]+\||const val [A-Z_]+ *= *"[a-z.]+")' "$stamp_src" | sed -E 's/^    (fun|const val) ([A-Za-z_]+).*= *"([a-z.]+).*$/\2 \3/' || true)
unit_count=$(printf "%s\n" "$stamp_units" | grep -c . || true)
[ "$unit_count" -ge 10 ] || guard_fail "guard 38 read only $unit_count unit keys out of ConfigStamp.kt; it is blind."
while read -r name ns; do
  [ -n "$name" ] || continue
  printf "%s" "$merge_body" | grep -qE "ConfigStamp(\.|::)$name\b" ||
    printf "%s" "$merge_body" | grep -qF "\"$ns\"" ||
    guard_fail "ConfigStamp mints the unit \"$ns\" (ConfigStamp.$name) and nothing in ConfigMerge.merge decides it, so a co-parent's edit there is dropped by the first peer that merges it. Give the namespace a loop in merge() — the grants or pinned-hero block is the shape — and prove it in core/src/test."
done <<EOF
$stamp_units
EOF

# 39. The hub advertises the project's version, not one of its own.
#     The hub is a relay and never an authority, but HubStore.edit round-trips
#     the document through ConfigJson.toJson on every admin save — so an image
#     left behind does not merely lack a new control, it DROPS the config key
#     behind it the first time a parent saves anything on the NAS. Config
#     fields ride a two-release gate so that window is survivable, and the
#     version on GET /health is what lets a stale hub be detected rather than
#     discovered.
#
#     Held equal to the app's versionName because the gate is counted in the
#     project's releases, and because bumping it is a change under hub/, which
#     is what .github/workflows/hub-image.yml watches: a release therefore
#     rebuilds the image instead of leaving it advertising the version before
#     it. Two numbers that may drift would be a version field that lies, which
#     is worse than none.
hub_gradle=hub/build.gradle.kts
hub_version=$(sed -nE 's/^val hubVersion = "([^"]+)".*/\1/p' "$hub_gradle" || true)
app_version=$(sed -nE 's/^ *versionName = "([^"]+)".*/\1/p' app/build.gradle.kts | head -1 || true)
[ -n "$hub_version" ] ||
  guard_fail "cannot read 'val hubVersion = \"…\"' out of $hub_gradle; guard 39 is blind. That literal is what GET /health advertises."
[ -n "$app_version" ] ||
  guard_fail "cannot read versionName out of app/build.gradle.kts; guard 39 is blind."
[ "$hub_version" = "$app_version" ] ||
  guard_fail "the hub says it is $hub_version and this release is $app_version. Set hubVersion in $hub_gradle to $app_version — a hub that reports the wrong version is worse than one that reports none, because the two-release config gate is checked against it."

# 40. The hub's service worker evicts only the caches it owns.
#     Cache Storage is per ORIGIN, not per worker. The activate handler used
#     to delete every key that was not its own, which is fine while this is
#     the only app here and a mutual wipe on every activation the moment it is
#     not — and a second app on this origin is the plan (the kid-facing web
#     player). The symptom would be two apps that are mysteriously never
#     available offline, in a file nobody looks at.
sw=hub/src/main/resources/web/sw.js
sw_prefix=$(sed -nE 's/^var PREFIX = "([^"]+)".*/\1/p' "$sw" || true)
[ -n "$sw_prefix" ] ||
  guard_fail "cannot read 'var PREFIX = \"…\"' out of $sw; guard 40 is blind. The worker names what it owns there."
grep -q "var CACHE = PREFIX +" "$sw" ||
  guard_fail "$sw builds CACHE without PREFIX, so what it owns and what it deletes are no longer the same thing."
sw_activate=$(awk '/addEventListener\("activate"/ { f = 1 } f { print; if (/^\}\);$/) exit }' "$sw" || true)
[ -n "$sw_activate" ] ||
  guard_fail "guard 40 cannot find the activate handler in $sw; it is blind."
printf "%s" "$sw_activate" | grep -q "caches.delete(" ||
  guard_fail "the activate handler in $sw evicts nothing; guard 40 is blind."
printf "%s" "$sw_activate" | grep -q "indexOf(PREFIX) === 0" ||
  guard_fail "the hub's service worker deletes caches it does not own. Cache Storage is per origin — filter on PREFIX before caches.delete, or the next app served from this hub and this one wipe each other's shells for ever."

# 41. Every response the hub makes carries the baseline security headers.
#     They were on the admin page alone, which is backwards: the page is the
#     one reply that is plainly ours, while a JSON error a browser was steered
#     into fetching is the one a sniffed content type or a frame has something
#     to work with. There is one funnel (respond) plus the page and the
#     assets, and each calls securityHeaders(ex) — so a fourth response path
#     added without it shows up here as a count that no longer matches.
#     HubServerTest.everyResponseCarriesTheBaselineSecurityHeaders proves the
#     three that exist actually send them; this is what notices a new one.
hubsrv=hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt
sends=$(grep -cF "ex.sendResponseHeaders(" "$hubsrv" || true)
headed=$(grep -cF "securityHeaders(ex)" "$hubsrv" || true)
[ "$sends" -ge 1 ] ||
  guard_fail "guard 41 found no ex.sendResponseHeaders( in $hubsrv; it is blind."
[ "$sends" = "$headed" ] ||
  guard_fail "$hubsrv writes $sends responses and only $headed of them call securityHeaders(ex). Every reply this server makes carries X-Content-Type-Options, X-Frame-Options and Referrer-Policy — route the new one through respond(), or call securityHeaders(ex) before sending its headers."


# 42. One place mints a pinned card, and one number is the row's ceiling.
#     Two faces edit the pinned hero — the phone's settings form and the
#     hub's browser — and the row's rules are arithmetic: ranks spaced by
#     Pins.RANK_STEP so an insert touches only the cards that moved, a cap of
#     Pins.MAX on growing a row but never on parsing one, and the same
#     visibleTo filter resolvePins applies at draw time. A second copy of any
#     of that does not throw. It drifts, and the symptom is a home screen
#     whose order differs between the television and the NAS, or a pin that
#     saves and never appears. Guard 26(a) cannot see this: it reads the
#     declared properties of Whitelist, Limits and AiConfig, and every field
#     of a Pin is nested a level below `pins`.
#     (a) Nothing outside :core builds a Pin. Every add, move and remove goes
#         through Pins.withRow.
pin_new=$(grep -rnE "[^A-Za-z]Pin\(" --include=*.kt app/src/main hub/src/main 2>/dev/null || true)
[ -z "$pin_new" ] ||
  guard_fail "a pinned card is built outside :core. The cap, the RANK_STEP spacing and the fail-closed filter live in Pins.withRow, and a card minted anywhere else has none of them. Found:
$pin_new"
#     (b) The renderer's ceiling and the editor's cap are one number. Two
#         would mean a card that saves, syncs and is never drawn.
grep -qE "^const val HOME_PINS_MAX = Pins[.]MAX$" core/src/main/kotlin/io/yosemitekids/app/ui/HomeSections.kt ||
  guard_fail "HOME_PINS_MAX must be Pins.MAX. A second 3 is a cap the home screen and the editor can disagree about, and the editor is the one a parent watches."
#     (c) The hub mints the ranks a browser sends it. index.html sends ids in
#         the parent's order and nothing else; HubWeb.applyPatch runs them
#         through Pins.withRow. Without that the page becomes the second
#         authority this guard exists to prevent.
grep -qF "Pins.withRow" hub/src/main/kotlin/io/yosemitekids/hub/HubWeb.kt ||
  guard_fail "HubWeb no longer runs an incoming home patch through Pins.withRow, so whatever ranks a browser sent are what the family gets. See HubWeb.normalisedPins."

# 43. One spelling of a family day.
#     A day is a bucket key, and a value put in one bucket and read out of
#     another is not an error anybody sees — it is a budget that resets at the
#     wrong hour, or a grant that stops counting at teatime, for some
#     households, some of the time. Four things now bucket by day (a grant's
#     date, SessionGuard's tally, the digest's channel totals, UsageLedger's
#     cells) and until FamilyDay they each spelled it themselves.
#     (a) FamilyDay is the only file in :core that formats or parses one.
#         TimeWindows keeps java.util.Calendar deliberately and is not in the
#         pattern: a window is a stretch of CLOCK on a day of the WEEK, and it
#         buckets nothing.
famday=core/src/main/kotlin/io/yosemitekids/app/data/FamilyDay.kt
[ -f "$famday" ] || guard_fail "$famday is gone; guard 43 is blind. The family day is minted in one place or it is minted in four."
for cal in "LocalDate" "SimpleDateFormat"; do
  homes=$(grep -rlF "$cal" core/src/main | sort | tr "\n" " " || true)
  homes=${homes% }
  [ -z "$homes" ] || [ "$homes" = "$famday" ] ||
    guard_fail "$cal is spelled out in [$homes]. In :core it belongs in $famday alone — a second spelling of a day splits buckets with no visible symptom."
done
#     (b) The two stores that bucket a kid's minutes take their day from
#         FamilyDay, never from a format string of their own. The display-only
#         formatters in DigestScreen, KidStats, StatsScreen and
#         SettingsImportExport are deliberately left alone: if those disagree
#         by a day the symptom is a chart, not a lockout.
for f in app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt \
         app/src/main/java/io/yosemitekids/app/data/Stats.kt; do
  if grep -qF "${q}yyyyMMdd${q}" "$f"; then
    guard_fail "$f spells a day out for itself. Take it from FamilyDay.compact(FamilyDay.of(...)) — this file decides whether a child may watch, and it must bucket the same way everything else does."
  fi
done

# 44. The watch ledger is a counter, and a counter is not curation.
#     Prohibition 12 in .claude/skills/yosemite-kids-sync is the argument and
#     this is the enforcement. A `use|<kid>|<day>` unit would look exactly
#     like `grant|<id>`, slot into the unit table without anything looking
#     odd, and cost the family a status-fetch-merge-push between every pair of
#     peers plus a hub nudge to every enrolled device, once a minute, for as
#     long as anyone is watching — while a usage line a minute wiped their
#     30-line change log in half an hour.
#     (a) The merge, the stamper, the serializer and the model do not know
#         this type exists.
for f in ConfigMerge ConfigStamp ConfigJson Whitelist SyncDecision; do
  named=$(grep -lF "UsageLedger" "core/src/main/kotlin/io/yosemitekids/app/data/$f.kt" || true)
  [ -z "$named" ] || guard_fail "$f.kt names UsageLedger. A counter has no winner, it has a join — put it back in its own document, its own file and its own lock, and read prohibition 12 in the sync skill before arguing otherwise."
done
#     (b) merge() takes exactly two ledgers and nothing else. A `today`
#         parameter would sail straight past the clockless grep at the top of
#         this file while being precisely the clock that grep exists to keep
#         out — and the laws only hold for a FIXED today, which is never the
#         case across two devices whose windows differ. The window is trim(),
#         and it is local.
grep -qE "^    fun merge\(a: Ledger, b: Ledger\): Ledger( \{)?\$" \
  core/src/main/kotlin/io/yosemitekids/app/data/UsageLedger.kt ||
  guard_fail "UsageLedger.merge is no longer 'fun merge(a: Ledger, b: Ledger): Ledger'. Two ledgers, no clock, no window: a third parameter is a tombstone TTL in disguise, and two devices pruning different sets push at each other for ever."
#     (c) The hub's config store knows nothing about watch traffic. Config
#         commits, the fingerprint, sync.log and the five-slot version ring
#         must be unmoved by a minute of viewing.
for word in "UsageLedger" "HubUsage" "usage.json"; do
  named=$(grep -lF "$word" hub/src/main/kotlin/io/yosemitekids/hub/HubStore.kt || true)
  [ -z "$named" ] || guard_fail "HubStore.kt names $word. The ledger has its own file and its own lock for a reason: watch traffic must not move a fingerprint, rotate a restore slot, or push a family's change history out of a 30-line log."
done

# 45. One reader of the daily tally.
#     Copied from guard 16's shape, which holds the two bonus stores to one
#     reader each for exactly this reason. There are seven enforcement sites
#     in SessionGuard and half a dozen screens that show a number derived from
#     them; the moment one of them reads the raw counter while the rest read
#     spentTodayMs(), a home screen promises forty minutes in front of a
#     player that stops at ten. Nothing throws, and the parent cannot explain
#     it.
tally=$(grep -cF "getLong(${q}dailyWatchedMs${q}" app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt || true)
[ "$tally" = "1" ] ||
  guard_fail "SessionGuard.kt reads dailyWatchedMs in $tally places; there is exactly one (ownWatchedMs). Anything asking what a kid has spent goes through spentTodayMs(), so the enforcer and every screen work from one number."



# 46. One answer to "may this child see this video".
#     The predicate used to sit in :app, on the class that also owns the
#     coroutine scope and the retry backoff, and the hub had no way to reach
#     it - so the moment the hub serves a kid's catalogue to a browser there
#     would be two of them. A second copy of this does not throw and does not
#     log. It lets a video the television hides appear on a tablet, and what
#     a parent reports is "the filter doesn't work", with nothing anywhere
#     saying which of the two answered.
#
#     So the decision lives in :crawl beside the verdict store it reads, and
#     :app keeps only the delegation. Both halves are checked: the predicate
#     has to still be there, and :app may not grow one of its own.
scr_pred=crawl/src/main/kotlin/io/yosemitekids/app/data/Screening.kt
[ -f "$scr_pred" ] ||
  guard_fail "$scr_pred is gone; guard 46 is blind. The visibility predicate lives there so the app and the hub run the same one."
for pred in isVisible needsScreening; do
  grep -q "fun $pred(" "$scr_pred" ||
    guard_fail "$scr_pred no longer declares fun $pred. That is the one place deciding what a child may see; :app's Screener and the hub both call it."
done
scr_app=$(grep -rn "fun isVisible(\|fun needsScreening(" --include=*.kt app/src/main | grep -v "= Screening[.]" || true)
[ -z "$scr_app" ] ||
  guard_fail "a visibility predicate in :app that is not a one-line delegation to Screening in :crawl. Two copies of this rule do not throw - they let a video the television hides appear on a tablet. Found:
$scr_app"

# 47. One home, one shelf model.
#     The shelves, the saved order, the pinned hero and the opening focus are
#     data, not drawing, and they moved to :core so a browser renders from the
#     same list the phone does. Add a shelf and both faces get it; keep a copy
#     in :app and the web home is a second implementation that is right until
#     the day somebody edits one of them. Only homeShelfCounts stayed behind,
#     because it reads UiState.
home_src=core/src/main/kotlin/io/yosemitekids/app/ui/HomeSections.kt
[ -f "$home_src" ] ||
  guard_fail "$home_src is gone; guard 47 is blind. The shared shelf model lives there."
for decl in "object HomeShelf" "val HOME_SHELVES" "resolvePins(" "fun homeSections(" "fun firstFocusableShelf("; do
  grep -q "$decl" "$home_src" ||
    guard_fail "$home_src no longer declares '$decl'. Moving a piece of the shelf model back into :app makes the browser's home a second implementation of it."
done
home_app=$(grep -rn "object HomeShelf\|val HOME_SHELVES\|fun homeSections(\|fun resolvePins(\|fun pinMeta(\|fun firstFocusableShelf(\|data class HomeSection\|data class PinnedItem" --include=*.kt app/src/main || true)
[ -z "$home_app" ] ||
  guard_fail "the shelf model is being declared in :app as well as :core. It belongs to :core so the phone, the television and the browser draw one list. Found:
$home_app"
#     And no file may be named the same in both halves of that package. Two
#     files called HomeSections.kt, one in :core and one in :app, both compile
#     to io.yosemitekids.app.ui.HomeSectionsKt - and whichever loses the
#     classpath race takes its functions with it. The build is clean; the
#     failure is a NoSuchMethodError at runtime, in a test that had nothing to
#     do with the edit. That happened while this guard was being written.
shadowed=""
for f in core/src/main/kotlin/io/yosemitekids/app/ui/*.kt; do
  b=$(basename "$f")
  if [ -f "app/src/main/java/io/yosemitekids/app/ui/$b" ]; then shadowed="$shadowed $b"; fi
done
[ -z "$shadowed" ] ||
  guard_fail "$shadowed exists in both core/.../ui and app/.../ui. Same package, same file name, same JVM class - one shadows the other on the classpath and nothing says so. Rename one for what is actually in it."

# 48. The kid's colours and type scale are generated for the browser, never
#     retyped for it.
#     The palette is one table in :core. The Android app binds it through
#     Compose; the stylesheet the hub serves is written from it by
#     :hub:generateKidTokensCss on every build. Neither may state a colour of
#     its own, and there is deliberately no copy of the stylesheet under
#     version control - it exists only in build/, so it cannot go stale and a
#     hand edit cannot survive the next build. This is the amber-warning
#     failure one level up: the same colour existing twice, in two files, at
#     two different alphas, with nothing to say which one was right.
tok=core/src/main/kotlin/io/yosemitekids/app/ui/DesignTokens.kt
[ -f "$tok" ] ||
  guard_fail "$tok is gone; guard 48 is blind. The one palette lives there."
#     (a) Nothing hand-written may be served as a stylesheet.
css_checked=$(find hub/src -name "*.css" || true)
[ -z "$css_checked" ] ||
  guard_fail "a stylesheet is checked in under hub/src ($css_checked). The kid palette is generated from $tok into build/ - a copy here is a second palette that agrees until somebody changes a colour."
#     (b) The generator is wired to the resources, and what it writes is served.
hubbuild=hub/build.gradle.kts
grep -q "generateKidTokensCss" "$hubbuild" ||
  guard_fail "$hubbuild no longer registers generateKidTokensCss. Without it the hub serves no tokens at all and every colour on the kid's page is the browser default."
grep -q "resources.srcDir(kidTokensCssDir)" "$hubbuild" ||
  guard_fail "$hubbuild no longer puts the generated stylesheet on the hub's resources. It would be written and then never packaged."
grep -qF "dependsOn(generateKidTokensCss)" "$hubbuild" ||
  guard_fail "processResources no longer depends on generateKidTokensCss in $hubbuild, so a clean build packages whatever was there last time - or nothing."
grep -qF '"/kid-tokens.css"' hub/src/main/kotlin/io/yosemitekids/hub/HubServer.kt ||
  guard_fail "HubServer serves no /kid-tokens.css. \"/\" answers anything without a route of its own with the admin page's HTML and a 200, so the stylesheet would arrive as HTML and every colour would silently be the browser default."
#     (c) The Android binding states no colour and no size of its own.
kidtok=app/src/main/java/io/yosemitekids/app/ui/KidTokens.kt
kid_lit=$(grep -nE "Color\(0x" "$kidtok" || true)
[ -z "$kid_lit" ] ||
  guard_fail "a colour literal in $kidtok. The hues live in :core's KidHues so the browser gets the same ones; a hex here is the second table. Found:
$kid_lit"
theme=app/src/main/java/io/yosemitekids/app/ui/Theme.kt
for block in YosemiteDarkColors YosemiteLightColors YosemiteTypography; do
  blk_lit=$(sed -n "/^val $block = /,/^)/p" "$theme" | grep -nE "Color\(0x|TextUnit\(" || true)
  [ -z "$blk_lit" ] ||
    guard_fail "$block in $theme states its own values. The kid-facing palette and type scale are :core's KID_DARK, KID_LIGHT and KidType, so the generated stylesheet and the app cannot disagree. Found:
$blk_lit"
done
#     (d) Every field of the table actually reaches the browser. A role added
#         to KidScheme and forgotten in roles() is not an error: it is a
#         var(--yk-...) that falls back to nothing, a card drawn on
#         transparent, and a page that looks almost right.
tok_fields=$(sed -n '/^data class KidScheme(/,/^)/p' "$tok" | grep -cE "^    val [a-zA-Z]+: Int" || true)
tok_roles=$(sed -n '/fun roles(): List<Pair<String, Int>> = listOfNotNull(/,/^    )/p' "$tok" | grep -cF '" to ' || true)
[ "$tok_fields" = "$tok_roles" ] ||
  guard_fail "KidScheme declares $tok_fields roles and roles() names $tok_roles. Every role has to be listed, or it simply is not in the stylesheet."
tok_styles=$(grep -cE "^    val [a-zA-Z]+ = TypeStyle\(" "$tok" || true)
tok_listed=$(sed -n '/val all: List<TypeStyle> = listOf(/,/^    )/p' "$tok" | tr ',' '\n' | grep -cE "^[[:space:]]*[a-z][a-zA-Z]+[[:space:]]*$" || true)
[ "$tok_styles" = "$tok_listed" ] ||
  guard_fail "KidType declares $tok_styles type styles and KidType.all lists $tok_listed. A step missing from the list is a step the browser does not have."


# 49. One answer to "does this kid share a budget", and the ledger never
#     authors the shared number.
#     Limits.budgetScope is a STRING so a mode a later build invents falls back
#     to today's behaviour on the televisions that predate it. That only holds
#     while every reader asks Limits.sharesBudget: a second `== "shared"`
#     written somewhere else is how a build that meets a scope it does not know
#     starts answering two different ways in the same house — strict on the
#     television and lenient on the tablet, with nothing to say which was
#     right. Assignment is fine; the settings switch has to write the constant.
wl=core/src/main/kotlin/io/yosemitekids/app/data/Whitelist.kt
grep -q "val sharesBudget: Boolean" "$wl" ||
  guard_fail "$wl no longer declares Limits.sharesBudget; guard 49 is blind. Unknown scopes fall back to per-device in ONE place or in as many places as there are readers."
src_main="app/src/main core/src/main crawl/src/main hub/src/main"
scope_cmp=$(grep -rnE "[!=]=[[:space:]]*BUDGET_SCOPE_SHARED|BUDGET_SCOPE_SHARED[[:space:]]*[!=]=" $src_main | grep -v "^$wl:" || true)
[ -z "$scope_cmp" ] ||
  guard_fail "a budget scope is compared against BUDGET_SCOPE_SHARED outside $wl. Ask Limits.sharesBudget — it is where a scope this build does not recognise is decided to mean per-device, and a second test of it is a second answer. Found:
$scope_cmp"
scope_lit=$(grep -rnE "budgetScope[[:space:]]*[!=]=[[:space:]]*$q" $src_main || true)
[ -z "$scope_lit" ] ||
  guard_fail "a budget scope is compared against a string literal:
$scope_lit
That is the same rule spelled a second time, and it will not be the one that gets updated. Use Limits.sharesBudget."
#     (b) The scope has to reach the enforcer. SessionGuard rebuilds its rules
#         from its own prefs mirror, so a field written to the config and not
#         to that mirror is a switch a parent turns on that changes nothing on
#         the box doing the stopping - and nothing anywhere says so.
sg=app/src/main/java/io/yosemitekids/app/data/SessionGuard.kt
[ "$(grep -cF "l_scope" "$sg")" -ge 2 ] ||
  guard_fail "$sg does not both write and read ${q}l_scope${q}. The rules the player enforces come from the prefs mirror, not from the config, so a scope that stops at saveLimits is a setting with no effect."
#     (c) A device's own ledger cell is authored from its OWN minutes.
#         watchedTodayMin() is own + peers; recordOwn writes what other devices
#         then read back and add to their own live counters. Feeding one to the
#         other is a budget that consumes itself in an afternoon, with every
#         number on every screen agreeing with every other. It has no symptom
#         short of a child being stopped at ten minutes.
for f in $(grep -rl "recordOwn(" app/src/main || true); do
  bad=$(grep -n "\.watchedTodayMin(" "$f" || true)
  [ -z "$bad" ] ||
    guard_fail "$f authors a watch-ledger cell and also reads the SHARED total:
$bad
A cell carries this device's own minutes — SessionGuard.ownWatchedTodayMin(). The shared figure is what peers add their own minutes to."
done

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
