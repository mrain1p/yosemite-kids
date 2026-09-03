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
