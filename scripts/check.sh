#!/usr/bin/env bash
# Pickwick pre-commit check: compile, offline unit tests, worker tests.
# Usage: scripts/check.sh [--quick]
set -euo pipefail
cd "$(dirname "$0")/.."

[ -f local.properties ] || { echo "local.properties is missing — create it with sdk.dir=<Android SDK path>" >&2; exit 1; }

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
