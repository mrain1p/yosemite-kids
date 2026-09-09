#!/usr/bin/env bash
#
# Who checks the checkers.
#
# scripts/check.sh holds 59 guards and nothing has ever verified that any of
# them can still fail. Each was negative-tested once, by hand, by whoever wrote
# it, and then never again — so a guard that quietly stops covering anything
# looks exactly like a guard that is happy.
#
# That is not hypothetical. In one session:
#
#   - guard 57's route regex was [a-z/.-] with no digits, so a route named
#     /kid-icon-192.png matched NOTHING. It failed OPEN, reporting the kid
#     origin as serving fewer paths than it does.
#   - guard 61(c) read the home-shelf catalogue and was blind to every You-tab
#     shelf, which is how the browser reached four of fourteen screens.
#   - guard 63, written that same day, had an awk range that never closed on a
#     one-line CSS rule, so it reported the whole stylesheet as card geometry.
#
# And the gate itself has broken three times in ways that read as findings:
# grep's exit 1 under pipefail, a PowerShell parse error exiting 0 having run
# nothing, and printf dying of EPIPE into grep -q — the last of which failed
# CI's FIRST step for three releases, so no compile and no test ran either.
#
# The rule this file makes real: **a guard is not finished until something
# other than its author can prove it fails.** Guard 64 in check.sh requires
# every guard from 56 upward to have a case here, so a new guard cannot land
# without one.
#
#   bash scripts/guard-canary.sh          # every case
#   bash scripts/guard-canary.sh 61 63    # only these
#
# It works by breaking the tree on purpose, one mutation at a time, and
# asserting the gate notices. Two safety rules follow from that and neither is
# negotiable:
#
#   1. It REFUSES to run on a dirty tree. Restoration is `git checkout --`,
#      which is only trustworthy when there is nothing of yours to lose.
#   2. It restores on any exit, including a Ctrl-C, via a trap. A canary that
#      leaves the working tree broken is worse than no canary.

set -uo pipefail
cd "$(dirname "$0")/.."

RED=$'\033[31m'; GREEN=$'\033[32m'; DIM=$'\033[2m'; OFF=$'\033[0m'

# Modified TRACKED files only. An untracked file is not at risk from
# `git checkout --` and refusing over one would mean this script could never
# be run in the commit that adds it.
dirty=$(git status --porcelain --untracked-files=no)
if [ -n "$dirty" ]; then
  echo "${RED}refusing to run on a dirty tree.${OFF}" >&2
  echo "This script breaks files on purpose and restores them with git checkout," >&2
  echo "which would throw away uncommitted work. Commit or stash first." >&2
  printf '%s\n' "$dirty" >&2
  exit 2
fi

# ONE AT A TIME, and this is not politeness.
#
# Two instances of this script race: one mutates a file while the other
# restores it, so the first one's gate run sees a clean tree and reports the
# guard as blind. That happened - guards 56 and 57 were both reported dead when
# both were fine - and it is the worst failure this script can have, because
# the conclusion "your guard does not work" is the one thing it exists to say
# and the one thing nobody double-checks.
LOCK=.git/yosemite-guard-canary.lock
if ! (set -o noclobber; echo "$$" > "$LOCK") 2>/dev/null; then
  echo "${RED}another guard-canary is already running${OFF} (pid $(cat "$LOCK" 2>/dev/null))." >&2
  echo "Two instances mutate and restore the same files and report working guards as blind." >&2
  echo "Wait for it, or remove $LOCK if you are sure it died." >&2
  exit 2
fi

TOUCHED=""
restore() {
  for f in $TOUCHED; do git checkout -- "$f" 2>/dev/null || true; done
  TOUCHED=""
  rm -f "$LOCK"
}
trap restore EXIT INT TERM

WANT="$*"
passed=0
failed=0
skipped=0

# Run the gate and answer: did it fail, and did it say the right thing?
#
# `--guards` only. The canary is about whether a guard FIRES, and compiling the
# app sixteen times to find that out would make this a thing nobody runs.
expect_guard() {
  local num="$1" file="$2" note="$3" expect="$4"
  local out rc
  out=$(bash scripts/check.sh --guards 2>&1)
  rc=$?
  git checkout -- "$file" 2>/dev/null || true
  TOUCHED=""
  if [ "$rc" -eq 0 ]; then
    echo "${RED}FAIL${OFF} guard $num did not fire — $note"
    echo "${DIM}      the mutation was applied and the gate stayed green, so this guard"
    echo "      is not covering what it claims to cover.${OFF}"
    failed=$((failed + 1))
    return
  fi
  # Fired, but was it OURS? Matched on a fragment of the guard's own message,
  # not on its number: guard messages do not carry their number, and greping
  # for one silently matched nothing — which made every case here look broken
  # when only the harness was. A different guard firing means the mutation hit
  # the wrong thing, and counting that as a pass is how a canary comes to
  # certify a guard it never exercised.
  if ! grep -qF "$expect" <<<"$out"; then
    echo "${RED}FAIL${OFF} guard $num — the gate failed, but not with this guard's message"
    echo "${DIM}      expected to see: $expect"
    echo "$(printf '%s\n' "$out" | tail -3 | sed 's/^/      /')${OFF}"
    failed=$((failed + 1))
    return
  fi
  echo "${GREEN} ok ${OFF} guard $num fires — $note"
  passed=$((passed + 1))
}

# One case. `mutate` breaks $file; `expect` is a fragment of the message the
# guard should answer with.
canary() {
  local num="$1" file="$2" note="$3" expect="$4" mutate="$5"
  if [ -n "$WANT" ]; then
    case " $WANT " in *" $num "*) ;; *) skipped=$((skipped + 1)); return ;; esac
  fi
  if [ ! -f "$file" ]; then
    echo "${RED}FAIL${OFF} guard $num — $file is gone, so the canary cannot mutate it"
    failed=$((failed + 1))
    return
  fi
  TOUCHED="$file"
  eval "$mutate"
  # THE MUTATION MUST HAVE LANDED. Without this check a pattern that quietly
  # matched nothing — a CRLF working tree defeating a \n in the regex, say —
  # reads as "the guard did not fire", which is the exact conclusion this
  # script exists to draw correctly. A canary that cannot tell a blind guard
  # from a missed edit is a canary that will one day retire a working guard.
  if git diff --quiet -- "$file"; then
    echo "${RED}FAIL${OFF} guard $num — the mutation did not change $file"
    echo "${DIM}      Nothing was tested. Fix the mutation in this file, not the guard.${OFF}"
    failed=$((failed + 1))
    TOUCHED=""
    return
  fi
  expect_guard "$num" "$file" "$note" "$expect"
}

KIDSRV=hub/src/main/kotlin/io/yosemitekids/hub/HubKidServer.kt
KIDPAGE=hub/src/main/resources/web/kid.html
HOMESTATE=app/src/main/java/io/yosemitekids/app/ui/HomeState.kt
TILES=app/src/main/java/io/yosemitekids/app/ui/Tiles.kt
CHUNKER=crawl/src/main/kotlin/io/yosemitekids/app/data/StreamChunker.kt

echo "== breaking things on purpose, one at a time"

# Each case says which guard, which file, what the mutation means, a FRAGMENT
# of the message that guard must answer with, and the mutation itself.
#
# The fragment matters. Guard messages do not carry their own numbers, so an
# earlier version of this file grepped for "guard 61", matched nothing, and
# reported every working guard as broken. Matching the words the guard actually
# says is both stricter and honest about which guard fired.
#
# Mutations use sed line addresses rather than multi-line perl regexes on
# purpose: the working tree is CRLF on Windows, and a `\n` in a slurped regex
# silently matches nothing there — which the harness now catches, but which is
# better not to write in the first place.

canary 56 "$CHUNKER" \
  "a second copy of the range arithmetic" \
  "wanted exactly one" \
  'printf "\nobject StreamChunker { /* canary */ }\n" >> "$CHUNKER"'

canary 57 "$KIDSRV" \
  "an unreviewed route on the kid origin" \
  "Adding one is a decision" \
  'sed -i "/createContext(\"\/whoami\")/i\        s.createContext(\"\/canary9\") { ex -> guarded(ex) { whoami(ex) } }" "$KIDSRV"'

canary 58 "$KIDSRV" \
  "a CORS header letting the origins read each other" \
  "the hub sets a CORS header" \
  'sed -i "/X-Content-Type-Options/i\        ex.responseHeaders.add(\"Access-Control-Allow-Origin\", \"*\")" "$KIDSRV"'

canary 59 "$KIDSRV" \
  "the kid throttle sharing the parents bucket" \
  "HubRate was added for the kid" \
  'sed -i "s/HubRate(MAX_CLAIMS_PER_WINDOW/HubRateCanary(MAX_CLAIMS_PER_WINDOW/" "$KIDSRV"'

canary 60 "$KIDSRV" \
  "a kid route that does not resolve the claim cookie" \
  "does not call watching(ex)" \
  'sed -i "/private fun you(ex: HttpExchange)/,/^    }$/ s/val browser = watching(ex) ?: return/val browser = browsers.any()/" "$KIDSRV"'

canary 61 "$KIDPAGE" \
  "the page deciding what a child may see" \
  "filters, sorts or caps something" \
  'printf "\n<script>var canary = [].filter(function(x){return x;});</script>\n" >> "$KIDPAGE"'

canary 61 "$KIDPAGE" \
  "a colour written into the page" \
  "the kid page names a colour" \
  'printf "\n<style>.canary { color: #ff00aa; }</style>\n" >> "$KIDPAGE"'

canary 62 "$HOMESTATE" \
  "a kid screen nobody decided about" \
  "it must be exactly one" \
  'sed -i "/^    data object Home : Screen/i\    data object CanaryScreen : Screen" "$HOMESTATE"'

canary 63 "$KIDPAGE" \
  "a card geometry number written twice" \
  "bare length in card geometry" \
  'sed -i "/^  \.card {/a\    margin-top: 7px;" "$KIDPAGE"'

canary 63 "$TILES" \
  "the app drawing a card corner the browser cannot read" \
  "VideoCard writes a bare corner radius" \
  'sed -i "s/RoundedCornerShape(KidGeometry.CARD_RADIUS.dp)/RoundedCornerShape(11.dp)/" "$TILES"'

echo
if [ "$failed" -gt 0 ]; then
  echo "${RED}$failed of $((passed + failed)) canaries did not fire.${OFF}"
  echo "A guard that cannot fail is not protecting anything. Fix the guard, not this file."
  exit 1
fi
echo "${GREEN}all $passed canaries fired${OFF}${skipped:+ ($skipped skipped)}"
