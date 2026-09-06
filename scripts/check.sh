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
# associativity structural rather than test artifacts.
if grep -qE "currentTimeMillis|Instant\.now|System\.nanoTime" \
    core/src/main/kotlin/io/yosemitekids/app/data/ConfigMerge.kt; then
  guard_fail "ConfigMerge.kt reads a clock. Take the time as a parameter (see ConfigStamp.stamped)."
fi

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

# Whether a peer holds no secrets is this phone's record, made at enrolment —
# never the peer's claim. A peer that could assert it would switch off the
# only content-level check on the API key, and a TV holding a revoked key
# would read "in sync" while its screening was dead. So the hub does not
# advertise it and the app does not look for it.
if grep -rq 'secretless' hub/src/main; then
  guard_fail ":hub must not advertise secretless. The flag is recorded on the phone at enrolment, not asserted by the peer."
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
  guard_fail "a hub is recognised by name ($hub_by_name). Test PairedDevice.secretless instead; the name is editable."
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
    guard_fail "the hub serves a page called $id with no renderer in index.html PAGES — it would silently show the Kids page."
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

# 13. The hub's build context is an allow-list. hub/docker-compose.yml builds
#     from the repo root, which on the NAS also holds data/ — the family's
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
