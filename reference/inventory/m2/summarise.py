#!/usr/bin/env python3
"""Summary of an M2 run: m2/summarise.py [--runs N] <m2-android.tsv> [more.tsv ...]

One table per file: for each scenario (first, cold, warm), every column's
median, minimum and maximum, and how many runs it came from ("n"). A cell of
"-" (a mark that does not apply, such as process start on a warm open) is left
out. With --runs, a scenario with fewer good rows than requested is called out,
and so is a <file>'s m2-android.errors next to it when it is not empty.
"""
import csv, os, statistics, sys

args = sys.argv[1:]
want = None
if args[:1] == ["--runs"]:
    if len(args) < 2 or not args[1].isdigit():
        sys.exit("usage: summarise.py [--runs N] <m2-android.tsv> [more.tsv ...]")
    want, args = int(args[1]), args[2:]
for path in args:
    rows = list(csv.DictReader(open(path), delimiter="\t"))
    cols = [c for c in (rows[0].keys() if rows else []) if c not in ("scenario", "run")]
    print(f"== {path}")
    for scenario in ("first", "cold", "warm"):
        rs = [r for r in rows if r["scenario"] == scenario]
        note = f"  ({len(rs)} of {want} runs good)" if want is not None and len(rs) != want else ""
        print(f"-- {scenario}: {len(rs)} runs{note}")
        for c in cols:
            vals = [r[c] for r in rs if r[c] not in ("", "-")]
            if not vals:
                continue
            if all(v.lstrip("-").isdigit() for v in vals):
                nums = sorted(int(v) for v in vals)
                med = statistics.median(nums)
                med = int(med) if med == int(med) else med
                print(f"   {c:<20} median {med:>8}  min {nums[0]:>8}  max {nums[-1]:>8}  n {len(nums)}")
            else:
                counts = {v: vals.count(v) for v in sorted(set(vals))}
                print(f"   {c:<20} " + ", ".join(f"{v} x{n}" for v, n in counts.items()))
    errors = os.path.join(os.path.dirname(path), "m2-android.errors")
    if os.path.exists(errors) and os.path.getsize(errors):
        print(f"!! failed runs ({errors}):")
        print("".join("   " + l for l in open(errors)))
