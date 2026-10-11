#!/usr/bin/env python3
"""W6's V1: the reports a host POSTed to the static server's /report.

    reports.py <server-log> <platform>

Reads the "REPORT <json>" lines and checks that one install sent exactly one
`loaded` (sequence 8, from the network) and then exactly one `update-applied`
(sequence 9), with the expected platform, a channel and an install id; that
nothing reported a failure; and that every record has exactly the 8 documented
fields (no free-text detail leaves the device). Prints what it found; exits 1
otherwise.
"""
import json, sys

log, platform = sys.argv[1], sys.argv[2]
reports = []
for line in open(log, encoding="utf-8", errors="replace"):
    if line.startswith("REPORT "):
        try:
            reports.append(json.loads(line[len("REPORT "):]))
        except ValueError:
            print("not JSON:", line.strip()[:200])
for r in reports:
    print("  report:", {k: r.get(k) for k in ("outcome", "sequence", "source", "platform", "channel")})
KEYS = {"installId", "channel", "hostVersion", "sequence", "source", "outcome", "reason", "platform"}
odd = [r for r in reports if set(r) != KEYS]
if odd:
    print("unexpected fields (the record is exactly %s): %s" % (sorted(KEYS), sorted(set(odd[0]))))
mine = [r for r in reports if r.get("platform") == platform and r.get("installId") and r.get("channel")]
loaded = [r for r in mine if r.get("outcome") == "loaded"]
applied = [r for r in mine if r.get("outcome") == "update-applied"]
failures = [r for r in mine if r.get("outcome") in ("update-failed", "not-loaded", "fell-back", "no-bundle", "refused")]
ok = (not odd and len(loaded) == 1 and len(applied) == 1 and not failures
      and loaded[0].get("sequence") == 8 and loaded[0].get("source") == "network"
      and applied[0].get("sequence") == 9 and loaded[0]["installId"] == applied[0]["installId"]
      and reports.index(loaded[0]) < reports.index(applied[0]))
print("ok" if ok else "missing: exactly one loaded(8, network), then one update-applied(9), no failure outcome, only the 8 fields, from one %s install" % platform)
sys.exit(0 if ok else 1)
