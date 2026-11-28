#!/usr/bin/env python3
"""MS4-04's S4: the relay's GET /reports summary after the schema-mismatch probe.

    mismatch_reports.py <summary.json> <platform> <sequence>

Checks that the relay counted exactly one `schema-mismatch` from <platform>, for
<sequence> (the probe's bundle), with the reason `schema-mismatch`, and none for
any other sequence: the probe's bundle used one unknown modifier tag on two
widgets, so the host reports it once. Prints the bundles it saw; exits 1 otherwise.
"""
import json, sys

s = json.load(open(sys.argv[1]))
platform, sequence = sys.argv[2], int(sys.argv[3])
counts = {}
for b in s.get("bundles", []):
    print("  bundle:", b.get("platform"), b.get("channel"), b.get("sequence"), "outcomes", b.get("outcomes"), "reasons", b.get("reasons"))
    if b.get("platform") != platform:
        continue
    n = (b.get("outcomes") or {}).get("schema-mismatch", 0)
    r = (b.get("reasons") or {}).get("schema-mismatch", 0)
    if n or r:
        counts[b.get("sequence")] = (n, r)
ok = counts == {sequence: (1, 1)}
print("ok" if ok else "expected one schema-mismatch report (outcome and reason) for sequence %d from %s, and none for any other; saw %s"
      % (sequence, platform, counts))
sys.exit(0 if ok else 1)
