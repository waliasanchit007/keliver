#!/usr/bin/env python3
"""W6's V2: the relay's GET /reports summary, after the development route.

    relay_reports.py <summary.json> <platform>

Checks that the relay counted a `loaded` from <platform> for sequence 1 (v1)
and for sequence 2 (v2): the host built with the relay as its report URL
reported each load there. Prints the bundles it saw; exits 1 otherwise.
"""
import json, sys

s = json.load(open(sys.argv[1]))
platform = sys.argv[2]
for b in s.get("bundles", []):
    print("  bundle:", b.get("platform"), b.get("channel"), b.get("sequence"), "installs", b.get("installs"), b.get("outcomes"))
seen = {b.get("sequence") for b in s.get("bundles", []) if b.get("platform") == platform and (b.get("outcomes") or {}).get("loaded", 0) >= 1}
ok = {1, 2} <= seen
print("ok" if ok else "missing: loaded reports for sequences 1 and 2 from %s (saw %s)" % (platform, sorted(x for x in seen if x is not None)))
sys.exit(0 if ok else 1)
