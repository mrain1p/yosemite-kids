#!/usr/bin/env bash
# Pickwick pre-commit check: compile, offline unit tests, worker tests.
# Usage: scripts/check.sh [--quick]
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f local.properties ] || { echo "local.properties is missing — create it with sdk.dir=<Android SDK path>" >&2; exit 1; }

# --- 0/4 invariants a test cannot state ------------------------------------
# Each is a property that holds across a whole file, so no assertion can pin
# it. See docs/PLAN-sync.md.
echo "== 0/5 source invariants"
guard_fail() { echo "guard FAILED: $1" >&2; exit 1; }

# The merge must read no clock: that is what makes idempotence and
# associativity structural rather than test artifacts.
if grep -qE "currentTimeMillis|Instant\.now|System\.nanoTime" \
    core/src/main/kotlin/io/pickwick/app/data/ConfigMerge.kt; then
  guard_fail "ConfigMerge.kt reads a clock. Take the time as a parameter (see ConfigStamp.stamped)."
fi

# Every config write goes through commit(), which stashes the API key and
# strips it from the bytes. Two write paths means the next one added forgets.
writers=$(grep -c "writeAtomically(" app/src/main/java/io/pickwick/app/data/ConfigStore.kt)
if [ "$writers" -gt 2 ]; then
  guard_fail "ConfigStore.kt calls writeAtomically outside commit(). Every write goes through commit."
fi

# buildCurrentConfig must copy the baseline: a positional constructor silently
# defaults out any field the form does not name, erasing the sync blob from
# every save and every push.
if grep -q "return Whitelist(" app/src/main/java/io/pickwick/app/ui/Settings.kt; then
  guard_fail "Settings.kt constructs a Whitelist. Use baseline.copy(...) so new fields are inherited."
fi

# :core is the code the Android app and the Docker hub both run. The moment it
# imports Android, the hub stops building — and the failure would surface in
# the hub's build, a long way from the edit that caused it.
androidInCore=$(grep -rlE "^import (android|androidx)\." core/src/main/kotlin || true)
if [ -n "$androidInCore" ]; then
  guard_fail ":core imports Android ($androidInCore). It must stay plain JVM so the hub can use it."
fi

# The same rule, one level up: the Android plugin in :core would let the above
# slip in without tripping the import check.
if grep -qE "com\.android|kotlin-android|libs\.plugins\.android" core/build.gradle.kts; then
  guard_fail ":core applies an Android plugin. It must stay a plain JVM module."
fi

# The merge's tests must live in :core, not :app. In :app they still pass, but
# they prove the merge works *on Android* — and the hub, which is the other
# consumer, would be running that logic with nothing covering it.
for t in ConfigMergeTest ConfigStampTest ConfigSyncFormatTest SyncDecisionTest; do
  [ -f "core/src/test/kotlin/io/pickwick/app/$t.kt" ] || \
    guard_fail "$t.kt must live in core/src/test — there it covers the hub too, in :app it does not."
done

# The hub must not depend on :app. :app is Android, and the whole reason the
# hub can share this logic is that the shared half was lifted into :core. A
# dependency here would drag the Android SDK into a container build.
if grep -qE "project\(\":app\"\)" hub/build.gradle.kts; then
  guard_fail ":hub depends on :app. Anything it needs belongs in :core."
fi

# The hub answers /status with the keys LanClient.fullStatus parses. The hub
# cannot depend on :app to check that, so the contract lives in a test — and
# this makes sure the test is still there to check it.
if [ ! -f hub/src/test/kotlin/io/pickwick/hub/HubServerTest.kt ]; then
  guard_fail "HubServerTest.kt is missing — it pins the /status wire contract with :app."
fi

# Shell scripts must reach a container with LF endings. A CRLF script has a
# shebang ending in a carriage return, which the kernel cannot resolve, and
# the error it produces is "not found" for a file that is plainly present.
# That cost half an hour on gradlew during the hub's first container build.
# git reports the working-tree ending directly, so this needs no escapes of
# its own to look for.
crlf=$(git ls-files --eol -- '*.sh' gradlew | grep -E 'w/(crlf|mixed)' | awk '{printf "%s ", $NF}')
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
for d in $(grep -rhoE 'docs/[A-Za-z0-9_.-]+[.]md' app/src core/src hub/src scripts 2>/dev/null | sort -u); do
  [ -f "$d" ] || guard_fail "source points at $d, which does not exist."
done

# includeSecrets must default to true. The settings form autosaves on a
# fingerprint change and nothing else, so a key edit that stopped moving the
# default fingerprint would never be saved at all — the key lost on the phone
# itself, not merely unpropagated.
if ! grep -q 'includeSecrets: Boolean = true' core/src/main/kotlin/io/pickwick/app/data/ConfigJson.kt; then
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
if grep -qE '[.]hash == local' app/src/main/java/io/pickwick/app/ui/SettingsDevices.kt; then
  guard_fail "SettingsDevices.kt compares a peer hash outside matches(). Route it through matches(expectedHash(...), ...)."
fi

# The hub's name is load-bearing twice over: the settings screen finds the
# hub by it, and pre-flag entries are migrated by it. Two copies drift.
hubname=$(grep -rc '"Pickwick hub"' app/src/main/java core/src/main/kotlin 2>/dev/null | grep -v ":0$" | wc -l)
if [ "$hubname" -ne 1 ]; then
  guard_fail "the literal \"Pickwick hub\" must appear only in PairedDevice.HUB_NAME (found in $hubname files)."
fi

# --- phone/hub settings parity ---------------------------------------------
#
# The hub GUI mirrors the phone Settings. Two UIs meant to mirror each other
# drift the moment one gains a feature, silently: nothing about a Compose
# screen says a browser page across the repo was supposed to grow the same
# control, and the drift is found by a parent who cannot do on the NAS what
# they just did on their phone.
#
# SettingsSurface.kt is the single list. These check it in both directions, so
# editing it is where the "does this belong on the hub?" decision gets made
# rather than something that can be skipped. Divergence is fine; undeclared
# divergence is not.
manifest=core/src/main/kotlin/io/pickwick/app/data/SettingsSurface.kt

# 1. Every phone section is declared.
for fn in $(grep -hoE "internal fun [A-Za-z]+Section[(]" app/src/main/java/io/pickwick/app/ui/Settings*.kt | sed -E "s/internal fun //; s/[(]//" | sort -u); do
  grep -q "\"$fn\"" "$manifest" || \
    guard_fail "$fn is not in SettingsSurface. Add it and say whether it belongs on the hub."
done

# 2. Every hub page is declared, and is not marked phone-only.
for id in $(grep -hoE "HubPage[(]\"[^\"]+\"" hub/src/main/kotlin/io/pickwick/hub/HubWeb.kt | sed -E "s/HubPage[(]\"//; s/\"//" | sort -u); do
  line=$(grep -E "SettingsSection[(]\"$id\"" "$manifest" || true)
  [ -n "$line" ] || guard_fail "the hub serves a page called $id that SettingsSurface does not list."
  case "$line" in
    *Where.PHONE*) guard_fail "$id is marked PHONE in SettingsSurface but the hub serves it." ;;
  esac
done

# 3. Anything claiming to be built on the hub must actually be there.
grep -oE "SettingsSection[(]\"[^\"]+\", \"[^\"]*\", \"[^\"]*\", Where[.]BOTH, true" "$manifest" |
  sed -E "s/SettingsSection[(]\"//; s/\".*//" | while read -r id; do
    grep -q "HubPage[(]\"$id\"" hub/src/main/kotlin/io/pickwick/hub/HubWeb.kt || \
      guard_fail "SettingsSurface says $id is ready on the hub, but HubWeb serves no such page."
  done

# Named, not counted: what is still missing should be readable, not a number.
todo=$(grep -oE "SettingsSection[(]\"[^\"]+\", \"[^\"]*\", \"[^\"]*\", Where[.]BOTH, false" "$manifest" |
  sed -E "s/SettingsSection[(]\"//; s/\".*//" | awk "{printf \"%s \", \$0}")
[ -z "$todo" ] || echo "   settings still to reach the hub: $todo"

# --- one arrival path for a config -----------------------------------------
#
# A config can land two ways: a peer pushes it to our LAN server, or this
# device merges it during its own sweep. Both must settle the kid's pending
# restyle and raise the "your rules changed" pill. Hanging that off the inbound
# path alone was invisible while phones were the only devices that swept — a
# device merging on its own never cleared the overlay, so its hash differed
# from its peer's forever and it re-merged every five minutes, and its kid was
# never told. One lambda, both callers.
acks=$(grep -rc "ProfileLooks(appContext)[.]ack(" app/src/main/java | grep -v ":0$" | wc -l)
if [ "$acks" -ne 1 ]; then
  guard_fail "ProfileLooks.ack must have exactly one call site (found $acks files). Both arrival paths share applyConfig."
fi
if ! grep -q "onConfigApplied?[.]invoke(" app/src/main/java/io/pickwick/app/ui/MainViewModel.kt; then
  guard_fail "the sweep no longer applies what it merged. A self-started merge must do what an inbound push does."
fi

# A kid's un-adopted restyle is a local rendering and must not travel. load()
# lays it over every read so it shows on this screen at once; a config sent to
# a peer carrying it puts a kid's private choice into the family document,
# where the stamps match on both sides and the merge breaks the tie on JSON
# string ordering. mergeIncoming already refuses a rendered read on the way in.
if ! grep -q "fun rawJson(): String = ConfigJson.toJson(loadForPeers())" app/src/main/java/io/pickwick/app/data/ConfigStore.kt; then
  guard_fail "rawJson must serialise loadForPeers(), not load(). load() carries the kid overlay."
fi

# A hub handed to a device must be flagged secretless. It strips the API key
# before writing and has no keystore to put it back, so a peer judged on the
# full fingerprint decides it is out of sync with the hub forever and merges
# on every single sweep.
if ! grep -A 20 'path == "/join-hub"' app/src/main/java/io/pickwick/app/data/Pairing.kt | grep -q "secretless = true"; then
  guard_fail "/join-hub must store the hub with secretless = true, or the device never reads as in sync."
fi

# The hub publishes the port it listens on. Both the ports line and the
# environment read one variable, because publishing one port while the process
# listens on another gives a container that is running, healthy and
# unreachable — the health check passes because it runs inside the container.
compose=hub/docker-compose.yml
if grep -qE "^[[:space:]]*network_mode:[[:space:]]*host" "$compose"; then
  guard_fail "host networking is back in $compose, which makes the published port inert."
fi
if [ "$(grep -o "PICKWICK_PORT:-8765" "$compose" | wc -l)" -lt 3 ]; then
  guard_fail "$compose must publish and set the port from one variable (ports twice, environment once)."
fi

# The gate globs *Test.kt here, in check.ps1 and in CI. Anything else is
# skipped by all three and looks green.
misnamed=$(find app/src/test/java/io/pickwick/app core/src/test/kotlin/io/pickwick/app -maxdepth 1 -type f ! -name '*Test.kt' | tr '\n' ' ')
if [ -n "$misnamed" ]; then
  guard_fail "these test files will never run: $misnamed — rename to *Test.kt"
fi

echo "== 1/5 compile (assembleDebug)"
./gradlew --no-daemon -q assembleDebug

if [ "${1:-}" = "--quick" ]; then echo "compile OK (quick mode)"; exit 0; fi

echo "== 2/5 core tests (no Android — the hub runs this exact code)"
./gradlew --no-daemon -q :core:test

echo "== 3/5 hub tests"
./gradlew --no-daemon -q :hub:test

echo "== 4/5 app unit tests (offline)"
# Every test class except the live-YouTube canaries. Both reach real YouTube
# unguarded, so a bot wall fails this gate for unrelated reasons.
args=()
for f in app/src/test/java/io/pickwick/app/*Test.kt; do
  name=$(basename "$f" .kt)
  case "$name" in ExtractorSmokeTest|SingleChannelProbeTest) continue ;; esac
  args+=(--tests "io.pickwick.app.$name")
done
./gradlew --no-daemon -q :app:testDebugUnitTest "${args[@]}"

echo "== 5/5 worker tests"
if command -v node >/dev/null 2>&1; then
  node --test worker/test/*.test.mjs
else
  echo "node not found — skipping worker tests"
fi

echo "all green"
