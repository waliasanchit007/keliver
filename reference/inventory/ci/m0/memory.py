"""M5's memory profile in a host's log: python3 memory.py [--cycles N] [--leak-kb K] [--leak-footprint-kb K] <log file>.

Reads the "KeliverTiming: memory <nanos> at=<point> cycle=<n> …" lines of one host, either
iOS's (the console: footprint=… peak=… qjs=…/… objects=… knheap=…) or Android's (logcat:
rss=… java=… native=… qjs=…/… objects=… pss=… graphics=…), and checks them:
  - every such line is well formed;
  - cycle 0 has open, ui, runtime, code and content, each once; ui after open, and runtime,
    code and content in that order (a host started before its first screen logs runtime and
    code before open);
  - QuickJS is read from runtime on ("-" only before it), and the guest's code grew its heap;
  - each memory cycle 1..N (--cycles, default 2) has open, ui, content and closed, closed
    last, with QuickJS read after a collection (iOS: the Kotlin heap is reported, "-" when the
    runtime did not say; Android: pss is read at closed);
  - with --leak-kb or --leak-footprint-kb: QuickJS's heap in use, or the footprint (pss on
    Android), grew by at most that from cycle 1's closed to the last one's (the screens left
    nothing behind).
Prints the deltas between the points; exits 1 with the reason when a check fails.
"""
import re
import sys

IOS = re.compile(
    r"KeliverTiming: memory (\d+) at=([a-z]+) cycle=(\d+) footprint=(\d+) peak=(-|\d+) "
    r"qjs=(-|\d+/\d+) objects=(-|\d+) knheap=(-|\d+)\r?$"
)
ANDROID = re.compile(
    r"KeliverTiming: memory (\d+) at=([a-z]+) cycle=(\d+) rss=(\d+) java=(\d+) native=(\d+) "
    r"qjs=(-|\d+/\d+) objects=(-|\d+) pss=(-|\d+) graphics=(-|\d+)\r?$"
)
POINTS0 = ["open", "ui", "runtime", "code", "content"]
POINTS = ["open", "ui", "content", "closed"]


def fail(reason):
    print(reason)
    sys.exit(1)


def parse(path):
    with open(path, errors="replace") as f:
        # "memory <nanos>": not the cycle's own "memory-cycle …" messages.
        raw = [l.rstrip("\n") for l in f if re.search(r"KeliverTiming: memory \d", l)]
    if not raw:
        fail("no memory line")
    # One host's lines: the first well-formed one says which.
    line = next((p for p in (IOS, ANDROID) for l in raw if p.search(l)), IOS)
    num = lambda v: None if v == "-" else int(v)
    rows = []
    for l in raw:
        m = line.search(l)
        if not m:
            fail(f"a malformed memory line: {l.strip()[-200:]}")
        if line is IOS:
            nanos, at, cycle, fp, peak, qjs, objects, kn = m.groups()
            row = dict(footprint=int(fp), knheap=num(kn))
        else:
            nanos, at, cycle, rss, java, native, qjs, objects, pss, graphics = m.groups()
            row = dict(rss=int(rss), java=int(java), pss=num(pss))
        used, alloc = (None, None) if qjs == "-" else (int(x) for x in qjs.split("/"))
        if used is not None and used > alloc:
            fail(f"QuickJS uses more than it allocated: {l.strip()[-200:]}")
        rows.append(dict(row, nanos=int(nanos), at=at, cycle=int(cycle), qjs=used))
    return line is ANDROID, rows


def one(rows, cycle, point):
    found = [r for r in rows if r["cycle"] == cycle and r["at"] == point]
    if len(found) != 1:
        fail(f"cycle {cycle}: '{point}' is logged {len(found)} times, not once")
    return found[0]


def main(path, cycles, leak_kb, leak_fp_kb):
    android, rows = parse(path)
    c0 = {p: one(rows, 0, p) for p in POINTS0}
    if c0["ui"]["nanos"] < c0["open"]["nanos"]:
        fail("cycle 0: 'ui' is before 'open'")
    if not c0["runtime"]["nanos"] <= c0["code"]["nanos"] <= c0["content"]["nanos"]:
        fail("cycle 0: runtime, code and content are out of order")
    for r in rows:
        if r["qjs"] is None and r["at"] not in ("open", "ui"):
            fail(f"cycle {r['cycle']}: QuickJS was not read at '{r['at']}'")
    if c0["code"]["qjs"] <= c0["runtime"]["qjs"]:
        fail(f"the guest's code did not grow QuickJS's heap: {c0['runtime']['qjs']} KB, then {c0['code']['qjs']} KB")
    closed = []
    for n in range(1, cycles + 1):
        c = {p: one(rows, n, p) for p in POINTS}
        if max(c.values(), key=lambda r: r["nanos"]) is not c["closed"]:
            fail(f"cycle {n}: 'closed' is not the last point")
        if android and c["closed"]["pss"] is None:
            fail(f"cycle {n}: pss was not read at 'closed'")
        closed.append(c["closed"])
    d = lambda a, b, k: b[k] - a[k]
    if android:
        return report_android(rows, c0, closed, d, leak_kb, leak_fp_kb)
    out = [
        f"open {c0['open']['footprint']} KB",
        f"ui {d(c0['open'], c0['ui'], 'footprint'):+}",
        f"runtime {d(c0['ui'], c0['runtime'], 'footprint'):+} (qjs {c0['runtime']['qjs']})",
        f"code {d(c0['runtime'], c0['code'], 'footprint'):+} (qjs {d(c0['runtime'], c0['code'], 'qjs'):+})",
        f"content {d(c0['code'], c0['content'], 'footprint'):+} (qjs {d(c0['code'], c0['content'], 'qjs'):+})",
    ]
    if closed:
        out.append("closed " + ", ".join(
            f"#{r['cycle']} {r['footprint']} KB (qjs {r['qjs']}, knheap {r['knheap'] if r['knheap'] is not None else '-'})" for r in closed))
    if len(closed) >= 2:
        grew_qjs = d(closed[0], closed[-1], "qjs")
        grew_fp = d(closed[0], closed[-1], "footprint")
        kn = (f"{d(closed[0], closed[-1], 'knheap'):+} KB"
              if closed[0]["knheap"] is not None and closed[-1]["knheap"] is not None else "not read")
        out.append(f"from cycle 1 to {closed[-1]['cycle']}: footprint {grew_fp:+} KB, qjs {grew_qjs:+} KB, knheap {kn}")
        if leak_kb is not None and grew_qjs > leak_kb:
            fail(f"QuickJS's heap grew {grew_qjs} KB from cycle 1's close to cycle {closed[-1]['cycle']}'s (at most {leak_kb}): " + "; ".join(out))
        if leak_fp_kb is not None and grew_fp > leak_fp_kb:
            fail(f"the footprint grew {grew_fp} KB from cycle 1's close to cycle {closed[-1]['cycle']}'s (at most {leak_fp_kb}): " + "; ".join(out))
    print(f"{len(rows)} lines; KB: " + "; ".join(out))


def report_android(rows, c0, closed, d, leak_kb, leak_pss_kb):
    # In the order they came: the host starts with the process (M3), so runtime and code are
    # often before the first screen's open.
    first, *rest = sorted(c0.values(), key=lambda r: r["nanos"])
    qjs = lambda r: f" (qjs {r['qjs']})" if r["qjs"] is not None else ""
    out = [f"{first['at']} rss {first['rss']} KB, java {first['java']}{qjs(first)}"]
    for a, b in zip([first] + rest, rest):
        q = f" (qjs {d(a, b, 'qjs'):+})" if a["qjs"] is not None and b["qjs"] is not None else qjs(b)
        out.append(f"{b['at']} {d(a, b, 'rss'):+}{q}")
    if closed:
        out.append("closed " + ", ".join(
            f"#{r['cycle']} pss {r['pss']} KB (rss {r['rss']}, qjs {r['qjs']}, java {r['java']})" for r in closed))
    if len(closed) >= 2:
        grew_qjs = d(closed[0], closed[-1], "qjs")
        grew_pss = d(closed[0], closed[-1], "pss")
        out.append(f"from cycle 1 to {closed[-1]['cycle']}: pss {grew_pss:+} KB, rss {d(closed[0], closed[-1], 'rss'):+} KB, "
                   f"qjs {grew_qjs:+} KB, java {d(closed[0], closed[-1], 'java'):+} KB")
        if leak_kb is not None and grew_qjs > leak_kb:
            fail(f"QuickJS's heap grew {grew_qjs} KB from cycle 1's close to cycle {closed[-1]['cycle']}'s (at most {leak_kb}): " + "; ".join(out))
        if leak_pss_kb is not None and grew_pss > leak_pss_kb:
            fail(f"pss grew {grew_pss} KB from cycle 1's close to cycle {closed[-1]['cycle']}'s (at most {leak_pss_kb}): " + "; ".join(out))
    print(f"{len(rows)} lines; KB: " + "; ".join(out))


if __name__ == "__main__":
    args = sys.argv[1:]
    opts = {"--cycles": 2, "--leak-kb": None, "--leak-footprint-kb": None}
    while len(args) > 1 and args[0] in opts:
        if not args[1].isdigit():
            fail(f"{args[0]} takes a number")
        opts[args[0]] = int(args[1])
        args = args[2:]
    if len(args) != 1 or args[0].startswith("-"):
        fail("usage: memory.py [--cycles N] [--leak-kb K] [--leak-footprint-kb K] <log file>")
    main(args[0], opts["--cycles"], opts["--leak-kb"], opts["--leak-footprint-kb"])
