#!/usr/bin/env python3
"""Drive the inventory reference app on a device and check EXPECTATIONS.md.

  drive.py <serial> <evidence-dir> <scenario> [title]

Scenarios:
  dev     E1-E10 on whatever host is in the foreground (the development route)
  title   only that the list screen shows <title> as its title (D3/P2/P4/P6)
  prod    P3: Add 1 three times on Espresso beans, observed after each tap

Every observation is the device's own view hierarchy (uiautomator dump), saved
as <evidence-dir>/<scenario>-NN-<tag>.xml. Results go to <scenario>.results.json
and the exit status is the number of failed checks (capped at 100).
"""
import json
import os
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

SERIAL, EV, SCENARIO = sys.argv[1], sys.argv[2], sys.argv[3]
TITLE = sys.argv[4] if len(sys.argv) > 4 else "Inventory"
os.makedirs(EV, exist_ok=True)

results = []
seq = [0]


def adb(*args, check=True):
    return subprocess.run(["adb", "-s", SERIAL, *args], capture_output=True, text=True, check=check).stdout


def dump(tag):
    """The current view hierarchy, saved as evidence. Never a stale file."""
    seq[0] += 1
    remote = f"/sdcard/inv-{SCENARIO}-{seq[0]:02d}.xml"
    for _ in range(6):
        adb("shell", "rm", "-f", remote, check=False)
        subprocess.run(["adb", "-s", SERIAL, "shell", "uiautomator", "dump", remote], capture_output=True, text=True)
        out = adb("shell", "cat", remote, check=False)
        if out.lstrip().startswith("<?xml"):
            with open(os.path.join(EV, f"{SCENARIO}-{seq[0]:02d}-{tag}.xml"), "w") as f:
                f.write(out)
            return ET.fromstring(out[out.index("<?xml"):].split("?>", 1)[1])
        time.sleep(2)
    raise SystemExit(f"uiautomator dump failed at {tag}")


def texts(root):
    return [n.get("text", "") for n in root.iter("node") if n.get("text")]


def wait_for(tag, pred, timeout=25):
    """Dump until pred(texts) holds, or timeout. Returns (ok, texts)."""
    deadline = time.time() + timeout
    while True:
        root = dump(tag)
        t = texts(root)
        if pred(t):
            return True, t, root
        if time.time() > deadline:
            return False, t, root
        time.sleep(1.5)


def check(eid, what, ok, observed):
    results.append({"id": eid, "check": what, "ok": bool(ok), "observed": observed})
    print(f"  {'PASS' if ok else 'FAIL'}  {eid}  {what}")
    if not ok:
        print(f"        observed: {observed}")


def bounds_center(node):
    m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", node.get("bounds", ""))
    x1, y1, x2, y2 = map(int, m.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def tap_text(root, text):
    for n in root.iter("node"):
        if n.get("text") and has([n.get("text")], text):
            x, y = bounds_center(n)
            adb("shell", "input", "tap", str(x), str(y))
            return True
    return False


def edit_field(root):
    for n in root.iter("node"):
        if n.get("class") == "android.widget.EditText":
            return n
    return None


PLACEHOLDER = "Search by name or SKU"


def field_text(root):
    """The search field's content; the placeholder is what an EMPTY field reports."""
    field = edit_field(root)
    if field is None:
        return None
    t = field.get("text", "")
    return "" if t == PLACEHOLDER else t


def type_query(query, tag):
    """Replace the search field's content with query, and CHECK that it did.

    The first run's clear sent exactly len(text) DELs after MOVE_END and one was
    lost: "bean" became "b", and the next query was typed as "bzzz". So: delete
    with spares (extra DELs on an empty field are no-ops), read the field back,
    and only type once it is empty. The query is checked the same way.
    """
    _, _, root = wait_for(tag + "-field", lambda t: True, timeout=1)
    field = edit_field(root)
    if field is None:
        return False
    x, y = bounds_center(field)
    adb("shell", "input", "tap", str(x), str(y))
    time.sleep(1)
    for attempt in range(4):
        current = field_text(root) or ""
        if not current:
            break
        adb("shell", "input", "keyevent", "KEYCODE_MOVE_END")
        for _ in range(len(current) + 4):
            adb("shell", "input", "keyevent", "KEYCODE_DEL")
        time.sleep(1)
        _, _, root = wait_for(f"{tag}-cleared-{attempt}", lambda t: True, timeout=1)
    if field_text(root):
        return False
    if query:
        adb("shell", "input", "text", query)
    adb("shell", "input", "keyevent", "KEYCODE_ESCAPE")  # dismiss the keyboard
    time.sleep(1)
    _, _, root = wait_for(f"{tag}-typed", lambda t: field_text_of(t, query), timeout=10)
    return field_text(root) == query


def field_text_of(t, query):
    return query in t


def has(t, s):
    """s is on screen: a node's whole text, or one component of a MERGED node.

    Compose merges a clickable row's children into one accessibility node, so a
    ListItem can report "Espresso beans, dark roast 1kg, BEAN-001 · 12 on hand".
    Match whole components at the ", " joins, never a bare substring, so that
    "On hand: 1" cannot match "On hand: 15".
    """
    return any(x == s or x.startswith(s + ", ") or x.endswith(", " + s) or (", " + s + ", ") in x for x in t)


def tap_and_read(root, label, eid, tag, expect):
    tap_text(root, label)
    ok, t, root = wait_for(tag, lambda t: has(t, expect))
    return ok, t, root


ESP = "Espresso beans, dark roast 1kg"
FLT = "Filter beans, light roast 1kg"


def scenario_title():
    ok, t, _ = wait_for("title", lambda t: has(t, TITLE) and any("items" in x for x in t), timeout=60)
    check("TITLE", f"the list screen shows the title {TITLE!r}", ok, t)
    others = [x for x in ("Inventory", "Stockroom", "Foreign build") if x != TITLE]
    check("TITLE-ONLY", f"no other known title is shown ({', '.join(others)})", ok and not any(has(t, x) for x in others), t)


def scenario_prod():
    ok, t, root = wait_for("prod-list", lambda t: has(t, ESP), timeout=60)
    check("P3.0", "the list is on screen in production", ok, t)
    tap_text(root, ESP)
    ok, t, root = wait_for("prod-item", lambda t: has(t, "On hand: 12"))
    check("P3.1", "Espresso beans opens with On hand: 12", ok, t)
    for n in (13, 14, 15):
        ok, t, root = tap_and_read(root, "Add 1", "P3", f"prod-add-{n}", f"On hand: {n}")
        check(f"P3.{n}", f"Add 1 -> On hand: {n}", ok, [x for x in t if x.startswith("On hand")])


def scenario_dev():
    ok, t, root = wait_for("E1", lambda t: has(t, TITLE) and has(t, "8 items · 2 low on stock"), timeout=90)
    check("E1", f"launch: title {TITLE!r} and '8 items · 2 low on stock'", ok, t)
    check("E1b", "Espresso beans row with 'BEAN-001 · 12 on hand'", has(t, ESP) and has(t, "BEAN-001 · 12 on hand"), t)

    check("E2.in", "the driver typed exactly 'bean' into the search field", type_query("bean", "E2"), "see E2-typed dump")
    ok, t, root = wait_for("E2", lambda t: has(t, '2 of 8 items match "bean"'))
    check("E2", "search 'bean' -> 2 of 8, both bean rows, no Oat milk",
          ok and has(t, ESP) and has(t, FLT) and not has(t, "Oat milk 1L"), t)

    check("E3.in", "the driver replaced the query with exactly 'zzz'", type_query("zzz", "E3"), "see E3-typed dump")
    ok, t, root = wait_for("E3", lambda t: has(t, 'No items match "zzz".'))
    rows = [x for x in (ESP, FLT, "Oat milk 1L") if has(t, x)]
    check("E3", "search 'zzz' -> empty state, Clear search, no rows",
          ok and has(t, '0 of 8 items match "zzz"') and has(t, "Clear search") and not rows, t)

    tap_text(root, "Clear search")
    ok, t, root = wait_for("E4", lambda t: has(t, "8 items · 2 low on stock"))
    check("E4", "Clear search -> all 8 back, empty state gone", ok and not has(t, 'No items match "zzz".'), t)

    tap_text(root, ESP)
    ok, t, root = wait_for("E5", lambda t: has(t, "On hand: 12"))
    check("E5", "open Espresso beans -> details, On hand: 12, no adjustments, no warning",
          ok and has(t, "BEAN-001 · Aisle 1") and has(t, "No adjustments yet")
          and not any(x.startswith("Low stock") for x in t), t)

    for n in (13, 14, 15):
        ok, t, root = tap_and_read(root, "Add 1", "E6", f"E6-{n}", f"On hand: {n}")
        check(f"E6.{n}", f"Add 1 -> On hand: {n}", ok, [x for x in t if x.startswith("On hand")])
    check("E6.h", "history reads 3 adjustments: +1, +1, +1 (net +3)",
          has(t, "3 adjustments: +1, +1, +1 (net +3)"), [x for x in t if "adjust" in x])

    for n in range(14, 4, -1):
        ok, t, root = tap_and_read(root, "Remove 1", "E7", f"E7-{n}", f"On hand: {n}")
        warn = any(x.startswith("Low stock — reorder at 5") for x in t)
        expect_warn = n <= 5
        check(f"E7.{n}", f"Remove 1 -> On hand: {n}, warning {'shown' if expect_warn else 'absent'}",
              ok and warn == expect_warn, [x for x in t if x.startswith(("On hand", "Low stock"))])

    ok, t, root = tap_and_read(root, "Receive 10", "E8", "E8", "On hand: 15")
    check("E8", "Receive 10 -> On hand: 15, warning gone",
          ok and not any(x.startswith("Low stock") for x in t), [x for x in t if x.startswith(("On hand", "Low stock"))])

    tap_text(root, "Back to inventory")
    ok, t, root = wait_for("E9", lambda t: has(t, "BEAN-001 · 15 on hand"))
    check("E9", "Back -> the row reads BEAN-001 · 15 on hand; still 8 items · 2 low",
          ok and has(t, "8 items · 2 low on stock"), t)

    tap_text(root, FLT)
    ok, t, root = wait_for("E10-open", lambda t: has(t, "On hand: 4"))
    check("E10.4", "open Filter beans -> On hand: 4 with the warning",
          ok and has(t, "Low stock — reorder at 5"), t)
    for n in (3, 2, 1, 0):
        ok, t, root = tap_and_read(root, "Remove 1", "E10", f"E10-{n}", f"On hand: {n}")
        check(f"E10.{n}", f"Remove 1 -> On hand: {n}, warning shown",
              ok and has(t, "Low stock — reorder at 5"), [x for x in t if x.startswith(("On hand", "Low stock"))])
    tap_text(root, "Remove 1")
    time.sleep(3)
    _, t, root = wait_for("E10-floor", lambda t: True, timeout=1)
    check("E10.floor", "a fifth Remove 1 leaves On hand: 0 and records nothing",
          has(t, "On hand: 0") and has(t, "4 adjustments: -1, -1, -1, -1 (net -4)"),
          [x for x in t if x.startswith("On hand") or "adjust" in x])


{"dev": scenario_dev, "title": scenario_title, "prod": scenario_prod}[SCENARIO]()

with open(os.path.join(EV, f"{SCENARIO}.results.json"), "w") as f:
    json.dump(results, f, indent=1, ensure_ascii=False)
failed = sum(1 for r in results if not r["ok"])
print(f"{SCENARIO}: passed {len(results) - failed}, failed {failed}")
sys.exit(min(failed, 100))
