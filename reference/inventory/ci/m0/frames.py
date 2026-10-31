"""M4's guest frame profile in a host's log: python3 frames.py <log file>.

Reads the "KeliverTiming: frames <nanos> n=… guest=… lead=… sink=… apply=… queue=…" lines
(logcat or the iOS console) and checks them:
  - every such line is well formed, each span "-" or p50/p95/max with p50 <= p95 <= max;
  - at least one guest frame was timed (n) and one change list applied (apply);
  - no change list reached the host later than the longest frame ended (lead <= guest).
Prints one summary line; exits 1 with the reason when a check fails.
"""
import re
import sys

SPANS = ["guest", "lead", "sink", "apply", "queue"]
LINE = re.compile(
    r"KeliverTiming: frames (\d+) n=(\d+) empty=(\d+) lists=(\d+) changes=(\d+) "
    + " ".join(rf"{s}=(-|\d+/\d+/\d+)" for s in SPANS)
    + r"\r?$"
)


def fail(reason):
    print(reason)
    sys.exit(1)


def main(path):
    with open(path, errors="replace") as f:
        raw = [l.rstrip("\n") for l in f if "KeliverTiming: frames " in l]
    if not raw:
        fail("no frames line")
    totals = dict(n=0, empty=0, lists=0, changes=0)
    worst = {s: None for s in SPANS}
    for l in raw:
        m = LINE.search(l)
        if not m:
            fail(f"a malformed frames line: {l.strip()[-160:]}")
        for i, k in enumerate(["n", "empty", "lists", "changes"]):
            totals[k] += int(m.group(i + 2))
        for i, s in enumerate(SPANS):
            v = m.group(i + 6)
            if v == "-":
                continue
            p50, p95, mx = (int(x) for x in v.split("/"))
            if not p50 <= p95 <= mx:
                fail(f"{s} is not p50 <= p95 <= max: {v}")
            worst[s] = mx if worst[s] is None else max(worst[s], mx)
    if totals["n"] < 1:
        fail(f"no guest frame was timed ({len(raw)} lines, lists={totals['lists']})")
    if totals["lists"] < 1 or worst["apply"] is None:
        fail("no change list was applied")
    if worst["lead"] is not None and worst["guest"] is not None and worst["lead"] > worst["guest"]:
        fail(f"a change list reached the host {worst['lead']} us into a frame, after the longest frame ({worst['guest']} us)")
    shown = " ".join(f"{s}<={worst[s]}us" if worst[s] is not None else f"{s}=-" for s in SPANS)
    print(f"{len(raw)} lines: frames={totals['n']} (empty {totals['empty']}) lists={totals['lists']} changes={totals['changes']}; {shown}")


if __name__ == "__main__":
    main(sys.argv[1])
