#!/usr/bin/env python3
"""W6's V1: the reports a host POSTed to the static server's /report.

    reports.py <server-log> <platform>

Reads the "REPORT <json>" lines and checks that one install sent `loaded`
(sequence 8, from the network) and then `update-applied` (sequence 9), with the
expected platform, a channel and an install id. Prints what it found; exits 1
if any of that is missing.
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
mine = [r for r in reports if r.get("platform") == platform and r.get("installId") and r.get("channel")]
loaded = [r for r in mine if r.get("outcome") == "loaded" and r.get("sequence") == 8 and r.get("source") == "network"]
applied = [r for r in mine if r.get("outcome") == "update-applied" and r.get("sequence") == 9]
ok = bool(loaded and applied and loaded[0]["installId"] == applied[0]["installId"])
ok = ok and reports.index(loaded[0]) < reports.index(applied[0])
print("ok" if ok else "missing: loaded(8, network) then update-applied(9) from one %s install" % platform)
sys.exit(0 if ok else 1)
