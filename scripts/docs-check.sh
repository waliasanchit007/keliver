#!/usr/bin/env bash
# The light docs check (docs/PLAN_2026-11.md section 14): what a docs-only pull
# request needs instead of the full build. Checks the Markdown files a change
# touches:
#   1. relative links point at files that exist (http(s), mailto and pure
#      #anchors are not checked);
#   2. tables: every row has as many cells as its header (escaped \| and pipes
#      inside `code` do not count);
#   3. hygiene, on the lines the change ADDS (any file, not only Markdown): no
#      private-network addresses, no private key blocks, no Apple team ids in
#      DEVELOPMENT_TEAM. Names that must never appear (an employer, a product)
#      are not written here either: put one extended regex per line in the
#      file named by KELIVER_HYGIENE_PATTERNS (default
#      ~/.config/keliver/hygiene-patterns), which stays on the developer's
#      machine; CI runs the generic checks only.
#
# Usage: scripts/docs-check.sh [--base <git ref>] [file.md ...]
#   --base REF  the change is REF...HEAD plus the working tree (default: the
#               merge base with origin/main). Files given explicitly are checked
#               for links and tables in full; hygiene still reads only the diff.
# Exit 0 when clean, 1 when a check failed. Prints one line per finding.
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

base=""
files=()
while [ $# -gt 0 ]; do
  case "$1" in
    --base) base="$2"; shift 2 ;;
    -h|--help) sed -n '2,24p' "$0"; exit 0 ;;
    *) files+=("$1"); shift ;;
  esac
done
if [ -z "$base" ]; then
  base="$(git merge-base origin/main HEAD 2>/dev/null || git rev-parse HEAD)"
fi
if [ "${#files[@]}" -eq 0 ]; then
  while IFS= read -r f; do
    [ -n "$f" ] && [ -f "$f" ] && files+=("$f")
  done < <(git diff --name-only --diff-filter=d "$base" -- '*.md')
fi

patterns_file="${KELIVER_HYGIENE_PATTERNS:-$HOME/.config/keliver/hygiene-patterns}"
added_file="$(mktemp)"
trap 'rm -f "$added_file"' EXIT
git diff -U0 --no-color "$base" -- . ':(exclude)scripts/docs-check.sh' | { grep -E '^\+([^+]|$)' || true; } > "$added_file"

python3 - "$patterns_file" "$added_file" "${files[@]+"${files[@]}"}" <<'PY'
import os, re, sys

patterns_file, added_file, files = sys.argv[1], sys.argv[2], sys.argv[3:]
added = open(added_file, encoding='utf-8', errors='replace').read().splitlines()
fail = 0

def report(msg):
    global fail
    fail += 1
    print(msg)

link = re.compile(r'(?<!!)\[[^\]]*\]\(([^)\s]+)(?:\s+"[^"]*")?\)|!\[[^\]]*\]\(([^)\s]+)\)')
code_span = re.compile(r'`[^`]*`')

for path in files:
    try:
        text = open(path, encoding='utf-8').read()
    except OSError as e:
        report(f"{path}: unreadable ({e})")
        continue
    here = os.path.dirname(path)
    in_fence = False
    header_cells = None
    for n, line in enumerate(text.splitlines(), 1):
        if line.lstrip().startswith('```'):
            in_fence = not in_fence
            header_cells = None
            continue
        if in_fence:
            continue
        bare = code_span.sub('', line)
        # 1. links
        for m in link.finditer(bare):
            target = m.group(1) or m.group(2)
            if re.match(r'^(https?:|mailto:|#|<)', target):
                continue
            target = target.split('#', 1)[0]
            if not target:
                continue
            full = os.path.normpath(os.path.join(here, target))
            if not os.path.exists(full):
                report(f"{path}:{n}: broken link -> {target}")
        # 2. tables
        stripped = bare.strip()
        if stripped.startswith('|'):
            cells = len(re.split(r'(?<!\\)\|', stripped.strip('|')))
            if header_cells is None:
                header_cells = cells
            elif re.fullmatch(r'\|?\s*:?-{2,}:?\s*(\|\s*:?-{2,}:?\s*)*\|?', stripped):
                pass
            elif cells != header_cells:
                report(f"{path}:{n}: table row has {cells} cells, its header {header_cells}")
        else:
            header_cells = None

# 3. hygiene on added lines
generic = [
    (r'\b192\.168\.\d{1,3}\.\d{1,3}\b|\b10\.\d{1,3}\.\d{1,3}\.\d{1,3}\b|\b172\.(1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3}\b', 'a private-network address'),
    (r'-----BEGIN [A-Z ]*PRIVATE KEY-----', 'a private key block'),
    (r'DEVELOPMENT_TEAM\s*=\s*[A-Z0-9]{10}\b', 'an Apple team id'),
]
extra = []
if os.path.isfile(patterns_file):
    for p in open(patterns_file, encoding='utf-8'):
        p = p.strip()
        if p and not p.startswith('#'):
            extra.append((p, 'a name from the local hygiene list'))
for line in added:
    for rx, what in generic:
        if re.search(rx, line):
            report(f"hygiene: an added line carries {what}: {line[1:][:120]}")
    for rx, what in extra:
        if re.search(rx, line, re.I):
            report(f"hygiene: an added line carries {what} (pattern hidden)")

print(f"docs-check: {len(files)} Markdown file(s), {len(added)} added line(s), "
      f"{'local hygiene list used' if extra else 'generic hygiene only'}: "
      f"{'FAILED ' + str(fail) if fail else 'clean'}")
sys.exit(1 if fail else 0)
PY
