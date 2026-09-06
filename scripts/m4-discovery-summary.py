#!/usr/bin/env python3
"""Summarise the discovery-instrumentation runs from the captured streams.

    scripts/m4-discovery-summary.py <trial-dir>

Reads reports/<arm>-r<N>-config.json and the raw stream, and reports per run:
discovery calls, portal MCP invocations, whether any MCP call preceded the
edit, and the call order. Nothing here reads a participant's self-report.
"""
import json, sys, glob, os, re, collections

T = sys.argv[1] if len(sys.argv) > 1 else "."
ARM_LABEL = {
    "a": "A  deferred + how-to-discover",
    "b": "B  deferred, availability only",
    "c": "C  listed, availability only",
}

def calls_from(stream):
    out = []
    for line in open(stream, encoding="utf-8", errors="replace"):
        line = line.strip()
        if not line:
            continue
        try:
            e = json.loads(line)
        except json.JSONDecodeError:
            continue
        for b in ((e.get("message") or {}).get("content") or []):
            if isinstance(b, dict) and b.get("type") == "tool_use":
                out.append(b.get("name", "?"))
    return out

rows = []
for cfg in sorted(glob.glob(os.path.join(T, "reports", "*-r*-config.json"))):
    m = re.search(r"/([a-z]+)-r(\d+)-config\.json$", cfg)
    if not m:
        continue
    arm, rep = m.group(1), int(m.group(2))
    d = json.load(open(cfg))
    stream = cfg.replace("-config.json", "-stream.jsonl")
    calls = calls_from(stream) if os.path.exists(stream) else []
    mcp = [c for c in calls if c.startswith("mcp__")]
    rows.append({
        "arm": arm, "rep": rep,
        "listing": d.get("listing"),
        "model": d.get("model_resolved"),
        "discovery": d.get("discovery_calls", calls.count("ToolSearch")),
        "mcp_calls": len(mcp),
        "mcp_names": sorted(set(n.split("__")[-1] for n in mcp)),
        "mcp_before_edit": d.get("mcp_preceded_edit"),
        "elapsed": d.get("elapsed_seconds"),
        "total": len(calls),
        "exit": d.get("exit_code"),
        "order": calls,
    })

rows.sort(key=lambda r: (r["arm"], r["rep"]))
print(f"{'arm':4} {'rep':3} {'listing':9} {'model':18} {'disc':4} {'mcp':4} {'pre-edit':8} {'calls':5} {'sec':4}")
for r in rows:
    print(f"{r['arm']:4} {r['rep']:<3} {str(r['listing']):9} {str(r['model']):18} "
          f"{r['discovery']:<4} {r['mcp_calls']:<4} {str(r['mcp_before_edit']):8} "
          f"{r['total']:<5} {r['elapsed']}")

print()
by_arm = collections.defaultdict(list)
for r in rows:
    by_arm[r["arm"]].append(r)
for arm in sorted(by_arm):
    rs = by_arm[arm]
    n = len(rs)
    used = sum(1 for r in rs if r["mcp_calls"] > 0)
    disc = sum(1 for r in rs if r["discovery"] > 0)
    print(f"{ARM_LABEL.get(arm, arm)}")
    print(f"    invoked portal tools: {used}/{n}    issued a discovery call: {disc}/{n}")
    tools = collections.Counter(t for r in rs for t in r["mcp_names"])
    if tools:
        print(f"    tools used: {', '.join(f'{k}x{v}' for k, v in tools.most_common())}")
    print()

print("Call orders:")
for r in rows:
    short = " ".join(c.replace("mcp__keliver-portal__", "mcp:") for c in r["order"])
    print(f"  {r['arm']}-r{r['rep']}: {short}")
