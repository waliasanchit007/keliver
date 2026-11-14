#!/usr/bin/env python3
"""Gate B part c's comparison and report, shared by keliver-gateb-ios-sim.sh and keliver-gateb-ios-device.sh.

    python3 -I scripts/keliver-gateb-report.py OUT TITLE DEVICE BUILD BUNDLE_BYTES "SAMPLES" NOTE

Reads OUT/<sample>-{native,guest}.png (8-bit RGB/RGBA, non-interlaced: a PNG reader in the standard
library), OUT/timing.txt ("<route> <ms>" per line, any routes, in order of first appearance) and
OUT/logs/time-<route>-<i>.log (the guest's code-load and first-widget times). Writes OUT/results.md
and, for a sample that differs, OUT/<sample>-diff.png. Exits non-zero if any sample differs by a
pixel or in size, or if the control (orders vs card, native) shows no difference.
"""
import os
import re
import statistics
import struct
import sys
import zlib

out, title, device, build, bundle_bytes, samples, note = sys.argv[1:8]
samples = samples.split()


def read_png(path):
    data = open(path, "rb").read()
    assert data[:8] == b"\x89PNG\r\n\x1a\n", path
    pos, idat, w = 8, b"", None
    while pos < len(data):
        n, kind = struct.unpack(">I4s", data[pos:pos + 8]); body = data[pos + 8:pos + 8 + n]; pos += 12 + n
        if kind == b"IHDR":
            w, h, depth, ctype, _, _, interlace = struct.unpack(">IIBBBBB", body)
            assert depth == 8 and ctype in (2, 6) and interlace == 0, (path, depth, ctype, interlace)
            bpp = 4 if ctype == 6 else 3
        elif kind == b"IDAT":
            idat += body
    raw = zlib.decompress(idat); stride = w * bpp; rows = []; prev = bytearray(stride); i = 0
    for _ in range(h):
        f = raw[i]; line = bytearray(raw[i + 1:i + 1 + stride]); i += 1 + stride
        for x in range(stride):
            a = line[x - bpp] if x >= bpp else 0; b = prev[x]; c = prev[x - bpp] if x >= bpp else 0
            if f == 1: line[x] = (line[x] + a) & 255
            elif f == 2: line[x] = (line[x] + b) & 255
            elif f == 3: line[x] = (line[x] + (a + b) // 2) & 255
            elif f == 4:
                p = a + b - c; pa, pb, pc = abs(p - a), abs(p - b), abs(p - c)
                line[x] = (line[x] + (a if pa <= pb and pa <= pc else b if pb <= pc else c)) & 255
        rows.append(bytes(line[j] for k in range(w) for j in range(k * bpp, k * bpp + 3))); prev = line
    return w, h, rows


def write_png(path, w, h, rows):
    raw = b"".join(b"\x00" + r for r in rows)
    chunk = lambda k, b: struct.pack(">I", len(b)) + k + b + struct.pack(">I", zlib.crc32(k + b) & 0xffffffff)
    open(path, "wb").write(b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", w, h, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b""))


lines = [f"# {title}", "",
         f"Device: {device}; app build: {build}; guest bundle: {bundle_bytes} bytes of modules.", "",
         "| Sample | Image | Differing pixels (native vs guest) | Bounding box |", "|---|---|---|---|"]
failures = []
for sample in samples:
    w, h, a = read_png(os.path.join(out, f"{sample}-native.png"))
    w2, h2, b = read_png(os.path.join(out, f"{sample}-guest.png"))
    if (w, h) != (w2, h2):
        lines.append(f"| {sample} | sizes differ: {w}x{h} vs {w2}x{h2} | - | - |")
        failures.append(f"{sample}: sizes differ ({w}x{h} vs {w2}x{h2})"); continue
    n = 0; box = [w, h, -1, -1]; diff = []
    for y in range(h):
        ra, rb = a[y], b[y]; row = bytearray(b"\xff" * (w * 3))
        if ra != rb:
            for x in range(w):
                if ra[3 * x:3 * x + 3] != rb[3 * x:3 * x + 3]:
                    n += 1; row[3 * x:3 * x + 3] = b"\xff\x00\x00"
                    box = [min(box[0], x), min(box[1], y), max(box[2], x), max(box[3], y)]
        diff.append(bytes(row))
    if n:
        write_png(os.path.join(out, f"{sample}-diff.png"), w, h, diff)
        failures.append(f"{sample}: {n} differing pixels, see {sample}-diff.png")
    lines.append(f"| {sample} | {w}x{h} | {n} ({100.0 * n / (w * h):.3f}%) | {box if n else '-'} |")

# The control: two different screens must differ, or the comparison proves nothing.
_, _, ca = read_png(os.path.join(out, "orders-native.png"))
_, _, cb = read_png(os.path.join(out, "card-native.png"))
control = sum(1 for ra, rb in zip(ca, cb) for x in range(0, len(ra), 3) if ra[x:x + 3] != rb[x:x + 3])
if control == 0:
    sys.exit("keliver-gateb-report: the control (orders vs card, native) shows no difference: the comparison is broken")
lines.append(f"| control: orders vs card (native) | | {control} | must be > 0 |")

t = {}
timing = os.path.join(out, "timing.txt")
for line in (open(timing) if os.path.exists(timing) else []):
    mode, ms = line.split(); t.setdefault(mode, []).append(int(ms))
lines += ["", "First frame (orders), ms from main() to the second frame after the root was first composed (both routes mark it with a SideEffect).",
          note, "", "| Route | Runs | Median | Min | Max |", "|---|---|---|---|---|"]
for mode, v in t.items():
    lines.append(f"| {mode} | {len(v)} | {statistics.median(v):.0f} | {min(v)} | {max(v)} |")
for mode, v in t.items():
    if not mode.startswith("guest"):
        continue
    for label, pattern in (("code loaded (codeLoadSuccess)", r"codeLoadSuccess at (\d+) ms"), ("first widget created", r"first guest widget at (\d+) ms")):
        found = []
        for i in range(1, len(v) + 1):  # only this run's launches
            path = os.path.join(out, "logs", f"time-{mode}-{i}.log")
            m = re.search(pattern, open(path, errors="replace").read()) if os.path.exists(path) else None
            if m: found.append(int(m.group(1)))
        if found:
            lines.append(f"| {mode}: {label} | {len(found)} | {statistics.median(found):.0f} | {min(found)} | {max(found)} |")
lines += [""]
if failures: lines += ["**FAILED:** " + "; ".join(failures), ""]
open(os.path.join(out, "results.md"), "w").write("\n".join(lines))
print("\n".join(lines))
if failures:
    sys.exit("keliver-gateb-report: native and guest differ: " + "; ".join(failures))
