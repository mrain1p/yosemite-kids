#!/usr/bin/env bash
# Upstream tracking for the fork: what has itcon-pty-au/pickwick shipped since
# the fork point, and which of those commits touch files this fork changed.
# Never merges anything. Appends a dated section to docs/UPSTREAM-LOG.md.
#
# Usage: scripts/upstream.sh [--since <commit>]   (default fork point below)
set -euo pipefail
cd "$(dirname "$0")/.."

FORK_POINT="${FORK_POINT:-7ce27f9}"          # upstream commit the fork started from
UPSTREAM_URL="https://github.com/itcon-pty-au/pickwick.git"
LOG="docs/UPSTREAM-LOG.md"
STATE="docs/.upstream-seen"                   # last upstream commit already logged

if [ "${1:-}" = "--since" ]; then FORK_POINT="$2"; fi

git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
git fetch --quiet upstream main

# Files the fork has changed relative to the fork point (committed or not).
fork_files=$( { git diff --name-only "$FORK_POINT"; git diff --name-only --cached; git ls-files --others --exclude-standard; } | sort -u )

last_seen=$(cat "$STATE" 2>/dev/null || echo "$FORK_POINT")
new_commits=$(git rev-list --reverse "$last_seen..upstream/main")
upstream_head=$(git rev-parse --short upstream/main)
today=$(date +%Y-%m-%d)

if [ -z "$new_commits" ]; then
  echo "upstream/main ($upstream_head): nothing new since $(git rev-parse --short "$last_seen")"
  exit 0
fi

count=$(echo "$new_commits" | wc -l | tr -d ' ')
echo "upstream/main ($upstream_head): $count new commit(s) since $(git rev-parse --short "$last_seen")"

{
  [ -f "$LOG" ] || printf '# Upstream log\n\nWhat upstream shipped after the fork point, one section per check. "Touches fork files" = merge risk; review by hand. Recommended action is filled in during review.\n'
  printf '\n## %s — upstream/main %s, %s new commit(s)\n\n' "$today" "$upstream_head" "$count"
  # version.json is the release signal; an extractor bump is the one to take promptly.
  up_ver=$(git show upstream/main:version.json 2>/dev/null | tr -d '\n' | sed 's/  */ /g')
  up_np=$(git show upstream/main:gradle/libs.versions.toml 2>/dev/null | grep -E '^newpipeextractor' || true)
  printf 'Upstream version.json: `%s`\n\nUpstream extractor: `%s`\n\n' "$up_ver" "$up_np"
  printf '| Commit | Subject | Files | Touches fork files | Action |\n| --- | --- | --- | --- | --- |\n'
  for c in $new_commits; do
    short=$(git rev-parse --short "$c")
    subject=$(git log -1 --format=%s "$c" | sed 's/|/\\|/g')
    files=$(git diff-tree --no-commit-id --name-only -r "$c")
    # Upstream still lives under io/pickwick with the old file names; translate
    # its paths into this tree's before asking whether they overlap, or the
    # flag below silently goes blind to every renamed file.
    files=$(echo "$files" | sed -E 's#/io/pickwick/#/io/yosemitekids/#; s#/PickwickApp[.]kt$#/YosemiteKidsApp.kt#; s#/PickwickScreen[.]kt$#/YosemiteScreen.kt#; s#^[.]claude/skills/pickwick-#.claude/skills/yosemite-kids-#')
    nfiles=$(echo "$files" | grep -c . || true)
    risky=$(comm -12 <(echo "$files" | sort -u) <(echo "$fork_files") | tr '\n' ' ')
    if [ -n "$risky" ]; then flag="⚠️ $risky"; else flag="no"; fi
    printf '| `%s` | %s | %s | %s | |\n' "$short" "$subject" "$nfiles" "$flag"
  done
} >> "$LOG"

echo "$upstream_head" > "$STATE"
echo "logged to $LOG"
