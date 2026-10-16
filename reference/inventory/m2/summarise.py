#!/usr/bin/env python3
"""Medians of an M2 run: m2/summarise.py <m2-android.tsv> [more.tsv ...]

One table per file: for each scenario (first, cold, warm), the median of every
column over its runs, with the run count. Empty cells (a mark that did not
apply, such as process start in a warm open) are left out of the median.
"""
import csv, statistics, sys

for path in sys.argv[1:]:
    rows = list(csv.DictReader(open(path), delimiter="\t"))
    cols = [c for c in (rows[0].keys() if rows else []) if c not in ("scenario", "run")]
    print(f"== {path}")
    print("scenario  runs  " + "  ".join(cols))
    for scenario in ("first", "cold", "warm"):
        rs = [r for r in rows if r["scenario"] == scenario]
        if not rs:
            continue
        cells = []
        for c in cols:
            vals = [int(r[c]) for r in rs if r[c]]
            cells.append(str(int(statistics.median(vals))) if vals else "-")
        print(f"{scenario:<8}  {len(rs):>4}  " + "  ".join(f"{v:>{len(c)}}" for v, c in zip(cells, cols)))
