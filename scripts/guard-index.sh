#!/usr/bin/env bash
# The guard index: docs/GUARDS.md, generated from scripts/check.sh.
#
# Sixty-odd guards live in two 2,000-line scripts, and most of each guard is
# the paragraph explaining why it exists. That is the right place for the
# paragraph - it is read by whoever the guard just stopped - but it makes
# "which guard covers X?" a question that costs the whole file. This script
# reads the numbered headings, the first sentence under each, the paths each
# guard inspects and whether scripts/guard-canary.sh proves it can still fail,
# and prints one table. Guard 65 keeps the checked-in copy equal to this
# output, so the index cannot rot the way a hand-kept one would.
#
#   bash scripts/guard-index.sh > docs/GUARDS.md
set -euo pipefail
cd "$(dirname "$0")/.."

canaried=$(grep -oE "^canary(_new)? [0-9]+ " scripts/guard-canary.sh | awk '{print $2}' | sort -un | tr '\n' ' ')

cat <<'HEAD'
# The guards

Every rule `scripts/check.sh` and `scripts/check.ps1` enforce on the source,
one row each. **Generated** by `scripts/guard-index.sh` from the numbered
headings in `check.sh`; guard 67 fails the gate when this file is behind them.
Regenerate with:

```bash
bash scripts/guard-index.sh > docs/GUARDS.md
```

The paragraph explaining each rule is beside the guard itself: open
`scripts/check.sh` and search for `# <number>.`. *Reads* lists the files the
guard inspects, so a change to one of them tells you which guards to expect.
*Canary* says whether `scripts/guard-canary.sh` has a case proving the guard
can still fail (guard 65 requires one from 56 upward).

| # | Rule | Reads | Canary |
| --- | --- | --- | --- |
HEAD

awk -v canaried=" $canaried" '
function flush(   i, reads, sep, mark) {
  if (num == "") return
  reads = ""; sep = ""
  for (i = 1; i <= nreads; i++) { reads = reads sep "`" readlist[i] "`"; sep = ", " }
  if (reads == "") reads = "-"
  # The first sentence only: the rest is the paragraph, and it lives beside
  # the guard. A wrapped first sentence was joined above.
  sub(/\. .*$/, ".", title)
  mark = (index(canaried, " " num " ") > 0) ? "yes" : "-"
  printf "| %s | %s | %s | %s |\n", num, title, reads, mark
}
function note(path) {
  if (path in seen) return
  seen[path] = 1; readlist[++nreads] = path
}
{
  sub(/\r$/, "")
  if ($0 ~ /^# [0-9]+\. /) {
    flush()
    num = $2; sub(/\.$/, "", num)
    title = $0; sub(/^# [0-9]+\. /, "", title)
    gsub(/\|/, "/", title)
    open = (title !~ /[.!?]$/)
    nreads = 0; delete seen
    next
  }
  # A heading that wraps: keep taking the indented comment lines until the
  # sentence ends, so the index carries the rule and not half of it.
  if (open && $0 ~ /^#  +[^ ]/) {
    more = $0; sub(/^#  +/, "", more); gsub(/\|/, "/", more)
    title = title " " more
    open = (title !~ /[.!?]$/)
    next
  }
  open = 0
  if ($0 ~ /^if \[ "\$\{1:-\}" = "--guards" \]/) { flush(); num = ""; exit }
  if (num == "") next
  line = $0
  # Every path-shaped token the guard mentions: a slash, and a source-ish
  # extension. Directories the guard greps over count too (app/src/main).
  while (match(line, /[A-Za-z0-9_.-]+(\/[A-Za-z0-9_.*-]+)+(\.(kt|kts|html|js|mjs|md|sh|ps1|yml|json|toml|css|xml|webmanifest))?/)) {
    tok = substr(line, RSTART, RLENGTH); sub(/\.$/, "", tok)
    line = substr(line, RSTART + RLENGTH)
    if (tok ~ /^(https?|http):/) continue
    if (tok ~ /^[0-9.]+\/[0-9.]+$/) continue
    if (tok !~ /^(app|core|crawl|hub|scripts|docs|worker|site|\.github|\.claude|gradle)\//) continue
    note(tok)
  }
}
END { flush() }
' scripts/check.sh
