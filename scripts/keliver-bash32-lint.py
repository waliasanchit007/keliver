#!/usr/bin/env python3
"""Find array expansions that are fatal on bash 3.2.

Expanding an EMPTY array under `set -u` aborts the shell on bash 3.2, which is
/bin/bash on macOS. CI runs bash 5 and cannot see the class at all, so this is
the only thing standing between a new `"${arr[@]}"` and a guard that kills its
caller instead of returning.

Usage:  keliver-bash32-lint.py <dir-of-scripts>...
Prints  KELIVER|<path>:<line>:<name>|<text>   for keliver-*.sh
        OTHER|<path>:<line>:<name>|<text>     for anything else
        SCANNER-FAILED <why>                  when a file cannot be scanned
        SCANNER-OK <n>                        once, at the end, n = files read

It lives in its own file so the suite can run it against FIXTURES. As a heredoc
inside the suite it could only ever scan the live tree, and the live tree has
one guarded expansion and no line carrying a guarded and an unguarded one — the
single case the guard logic exists for. Every mutation of that logic passed.

Bias: FALSE POSITIVES. Whether a `${#x[@]}` test or an append dominates an
expansion is not something a regex decides, and an earlier version that guessed
hid two genuinely fatal shapes. Silence a real false positive with a trailing
`# lint: bash32-ok` on the line.
"""
import os, re, sys

# An array is "emptyable" if it is ever declared without a non-empty
# initialiser: any `name=()` anywhere, a declared-but-unassigned array, or one
# that is unset. Over-matching only widens the candidate set.
DECL = re.compile(r'([A-Za-z_][A-Za-z0-9_]*)=\(\s*\)')
BARE = re.compile(r'\b(?:local|declare|typeset)\s+-\w*a\w*\s+([A-Za-z_][A-Za-z0-9_]*)(?![=\w])', re.M)
UNSET = re.compile(r'\bunset\s+([A-Za-z_][A-Za-z0-9_]*)')
USE = re.compile(r'\$\{([A-Za-z_][A-Za-z0-9_]*)\[[@*]\]\}')
PRAGMA = re.compile(r'#\s*lint:\s*bash32-ok')
# A file that WRITES fixtures containing these shapes would otherwise report
# itself. The region is explicit and bounded, unlike skipping a whole file.
FIX_BEGIN = re.compile(r'#\s*lint:\s*bash32-fixtures-begin')
FIX_END = re.compile(r'#\s*lint:\s*bash32-fixtures-end')


def guarded_spans(line, name):
    """Spans of `${name[@]+ ... }`, matched by BRACE DEPTH.

    "No closing brace since the guard" was wrong in both directions: a `}` from
    a nested `${#x[@]}` inside the guard body ended it early (false positive),
    and an escaped or quoted `${name[@]+` in a string opened one that was never
    there (false negative).
    """
    spans = []
    opener = re.compile(r'\$\{' + re.escape(name) + r'\[[@*]\]\+')
    for m in opener.finditer(line):
        # Only count an opener that is really shell syntax: not backslash-escaped
        # and not inside single quotes.
        if m.start() and line[m.start() - 1] == '\\':
            continue
        if line[:m.start()].count("'") % 2:
            continue
        depth, i = 1, m.end()
        while i < len(line) and depth:
            if line.startswith('${', i):
                depth += 1
                i += 2
                continue
            if line[i] == '}':
                depth -= 1
            i += 1
        spans.append((m.start(), i))
    return spans


def _is_comment_marker(line, m):
    """True when the marker's `#` is real shell comment syntax, not text.

    A marker inside a string used to disarm scanning: a line merely MENTIONING
    the begin marker silenced the rest of the file, and a hint string containing
    the pragma silenced its own line.
    """
    head = line[:m.start()]
    return head.count("'") % 2 == 0 and head.count('"') % 2 == 0


def scan(path, text, out):
    emptyable = set(DECL.findall(text)) | set(BARE.findall(text)) | set(UNSET.findall(text))
    kind = "KELIVER" if os.path.basename(path).startswith("keliver-") else "OTHER"
    in_fixtures = False
    for i, line in enumerate(text.split("\n"), 1):
        mb = FIX_BEGIN.search(line)
        if mb and _is_comment_marker(line, mb):
            in_fixtures = True
            continue
        me = FIX_END.search(line)
        if me and _is_comment_marker(line, me):
            in_fixtures = False
            continue
        if in_fixtures:
            continue
        mp = PRAGMA.search(line)
        if mp and _is_comment_marker(line, mp):
            continue
        for m in USE.finditer(line):
            name = m.group(1)
            if name not in emptyable:
                continue
            if any(a <= m.start() < b for a, b in guarded_spans(line, name)):
                continue
            out.append("%s|%s:%d:%s|%s" % (kind, path, i, name, line.strip()[:100]))
    return 1 if in_fixtures else 0


def main(argv):
    out, scanned = [], 0
    for root in argv:
        try:
            names = sorted(f for f in os.listdir(root) if f.endswith(".sh"))
        except Exception as e:
            print("SCANNER-FAILED %s" % e)
            return 0
        for name in names:
            path = os.path.join(root, name)
            try:
                text = open(path, encoding="utf-8", errors="replace").read()
            except Exception as e:
                print("SCANNER-FAILED cannot read %s: %s" % (path, e))
                continue
            scanned += 1
            # An unclosed region reached EOF silently and left the rest of the
            # file unscanned — a scan that did not happen, reported as success.
            if scan(path, text, out):
                print("SCANNER-FAILED unclosed fixtures region in %s" % path)
                return 1
    for row in out:
        print(row)
    print("SCANNER-OK %d" % scanned)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
