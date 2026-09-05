#!/usr/bin/env bash
# Turn a Yosemite Kids whitelist.txt (Settings → export) into a config.json the
# emulator can be seeded with: scripts/emu.ps1 seed --real
#
# Usage: scripts/seed-from-whitelist.sh <whitelist.txt> [out.json]
# Ids follow WhitelistParser: UC… ids, playlist ids, @handles, user/x, c/x.
set -euo pipefail
in="${1:?whitelist.txt path}"
out="${2:-$(dirname "$0")/seed-config.real.json}"

entries=$(grep -v '^\s*#' "$in" | grep -v '^\s*$' | awk -F'|' '
function trim(s) { gsub(/^[ \t]+|[ \t]+$/, "", s); return s }
function esc(s) { gsub(/\\/, "\\\\", s); gsub(/"/, "\\\"", s); return s }
{
  url = trim($1); label = trim($2); id = ""; kind = "CHANNEL"; u = url
  sub(/^https?:\/\/(www\.|m\.)?youtube\.com\//, "", u)
  if (match(u, /list=[A-Za-z0-9_-]+/)) { id = substr(u, RSTART + 5, RLENGTH - 5); kind = "PLAYLIST"; url = "https://www.youtube.com/playlist?list=" id }
  else if (match(u, /^channel\/UC[A-Za-z0-9_-]{22}/)) { id = substr(u, 9, 24); url = "https://www.youtube.com/channel/" id }
  else if (match(u, /^@[A-Za-z0-9._-]+/)) { id = substr(u, RSTART, RLENGTH); url = "https://www.youtube.com/" id }
  else if (match(u, /^(user|c)\/[A-Za-z0-9._-]+/)) { id = substr(u, RSTART, RLENGTH); url = "https://www.youtube.com/" id }
  if (id == "") next
  printf "%s    { \"id\": \"%s\", \"url\": \"%s\", \"label\": \"%s\", \"kind\": \"%s\" }", (n++ ? ",\n" : ""), esc(id), esc(url), esc(label), kind
}')

cat > "$out" <<EOF
{
  "updatedAt": $(date +%s)000,
  "entries": [
$entries
  ],
  "blocked": [],
  "limits": {},
  "ai": { "enabled": false, "baseUrl": "", "model": "", "rules": "", "rulesVersion": 1 },
  "aiAllowed": [],
  "profiles": [
    {
      "id": "a1b2c3d4",
      "name": "Emma",
      "color": 4293467747,
      "avatar": "🦄",
      "age": 6,
      "limits": { "session": 15, "weekdaySessions": 4, "weekendSessions": 6, "breakMinutes": 5 }
    }
  ]
}
EOF
echo "wrote $out ($(grep -c '"id"' "$out") entries)"
