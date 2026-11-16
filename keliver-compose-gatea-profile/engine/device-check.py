"""Checks a device run of run-device.sh against the Mac runs, then summarises it.

Usage: python3 -I device-check.py <pulled results dir> <Mac combine.jsonl>

For every engine build, workload and recording mode, the first batch, the last open and the last
back batch must have the same hash and byte count as every Mac run of the same build, workload and
mode (the Mac's out2/combine.jsonl, which checks the same three batches). Prints the device's
figures as in docs/GUEST_PROFILE.md: the mean of the rounds' medians. Exit 1 on any mismatch,
missing run or error.
"""
import json
import os
import sys

CHECKED = ("firstHash", "openHash", "backHash", "backBytes", "backProperties", "backCreates")


def key(run):
    build, workload, _round = run["label"].split("/")
    return build, workload, run["mode"]


def main(results_dir, mac_file):
    with open(os.path.join(results_dir, "combine.jsonl")) as f:
        device = [json.loads(line) for line in f if line.strip()]
    with open(mac_file) as f:
        mac = [json.loads(line) for line in f if line.strip()]
    expected = {}
    for run in mac:
        expected.setdefault(key(run), set()).add(tuple(run[c] for c in CHECKED))
    problems = []
    for k, values in expected.items():
        if len(values) != 1:
            problems.append(f"Mac runs of {k} disagree with each other: {values}")
    rows = {}
    rounds = set()
    for run in device:
        if "error" in run:
            problems.append(f"{run['label']} {run['mode']}: {run['error']}")
            continue
        k = key(run)
        rounds.add(run["label"].split("/")[2])
        got = tuple(run[c] for c in CHECKED)
        want = expected.get(k)
        if want is None:
            problems.append(f"{run['label']} {run['mode']}: no Mac run to compare with")
        elif got not in want:
            problems.append(f"{run['label']} {run['mode']}: batches differ from the Mac: {got} vs {sorted(want)}")
        rows.setdefault(k, []).append(run)
    for k in expected:
        if k not in rows:
            problems.append(f"no device run of {k}")
    counts = {len(v) for v in rows.values()}
    if len(counts) > 1:
        problems.append(f"uneven rounds per configuration: {sorted(counts)}")

    print(f"{len(device)} device runs, rounds {sorted(rounds)}; Mac configurations {len(expected)}")
    print("build      workload    mode      back   open   first  load   (ms; mean of round medians; per-round back)")
    for k in sorted(rows):
        runs = rows[k]
        mean = lambda f: sum(r[f] for r in runs) / len(runs)
        per_round = " ".join(str(r["backMedianMs"]) for r in sorted(runs, key=lambda r: r["label"]))
        print(f"{k[0]:<10} {k[1]:<11} {k[2]:<9} {mean('backMedianMs'):6.1f} {mean('openMedianMs'):6.1f} "
              f"{mean('firstMs'):6.1f} {mean('loadMs'):6.0f}   [{per_round}]")
    state = os.path.join(results_dir, "state.jsonl")
    if not os.path.exists(state):
        problems.append("no state.jsonl: the device's state (awake, battery, thermal) was not recorded")
    else:
        print("device state (battery level, temp 0.1 C, status, AC/USB, thermal status, wakefulness):")
        with open(state) as f:
            for line in f:
                s = json.loads(line)
                print(f"  r{s['round']} {s['when']:<6} {s['time']} {s['batteryLevel']}% {s['batteryTempDeciC']} "
                      f"status={s['batteryStatus']} ac={s['acPowered']} usb={s['usbPowered']} "
                      f"thermal={s['thermalStatus']} {s['wakefulness']}")
                if s["wakefulness"] != "Awake":
                    problems.append(f"round {s['round']} {s['when']}: device was {s['wakefulness']} (timings may include sleep)")
    if problems:
        print("PROBLEMS:")
        for p in problems:
            print("  " + p)
        return 1
    print("OK: every run's first, last open and last back batch match the Mac runs")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1], sys.argv[2]))
