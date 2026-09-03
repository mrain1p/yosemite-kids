#!/usr/bin/env bash
# Pickwick pre-commit check: compile, offline unit tests, worker tests.
# Usage: scripts/check.sh [--quick]
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f local.properties ] || { echo "local.properties is missing — create it with sdk.dir=<Android SDK path>" >&2; exit 1; }

# --- 0/4 invariants a test cannot state ------------------------------------
# Each is a property that holds across a whole file, so no assertion can pin
# it. See docs/PLAN-sync.md.
echo "== 0/4 source invariants"
guard_fail() { echo "guard FAILED: $1" >&2; exit 1; }

# The merge must read no clock: that is what makes idempotence and
# associativity structural rather than test artifacts.
if grep -qE "currentTimeMillis|Instant\.now|System\.nanoTime" \
    app/src/main/java/io/pickwick/app/data/ConfigMerge.kt; then
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

# The gate globs *Test.kt here, in check.ps1 and in CI. Anything else is
# skipped by all three and looks green.
misnamed=$(find app/src/test/java/io/pickwick/app -maxdepth 1 -type f ! -name '*Test.kt' | tr '\n' ' ')
if [ -n "$misnamed" ]; then
  guard_fail "these test files will never run: $misnamed — rename to *Test.kt"
fi

echo "== 1/3 compile (assembleDebug)"
./gradlew --no-daemon -q assembleDebug

if [ "${1:-}" = "--quick" ]; then echo "compile OK (quick mode)"; exit 0; fi

echo "== 2/3 unit tests (offline)"
# Every test class except the live-YouTube canaries. Both reach real YouTube
# unguarded, so a bot wall fails this gate for unrelated reasons.
args=()
for f in app/src/test/java/io/pickwick/app/*Test.kt; do
  name=$(basename "$f" .kt)
  case "$name" in ExtractorSmokeTest|SingleChannelProbeTest) continue ;; esac
  args+=(--tests "io.pickwick.app.$name")
done
./gradlew --no-daemon -q :app:testDebugUnitTest "${args[@]}"

echo "== 3/3 worker tests"
if command -v node >/dev/null 2>&1; then
  node --test worker/test/*.test.mjs
else
  echo "node not found — skipping worker tests"
fi

echo "all green"
