#!/usr/bin/env bash
#
# keliver-gateb-ios-sim — Gate B part c on a real iOS simulator app (docs/COMPOSE_GENERATOR_DESIGN.md §21).
#
#   scripts/keliver-gateb-ios-sim.sh [--out DIR] [--port PORT] [--runs N] [--debug] [--with-tests]
#   scripts/keliver-gateb-ios-sim.sh --compare-only --out DIR   (re-runs only the comparison and the report on DIR)
#
# Exits non-zero if a guest launch did not verify the manifest with the scratch key, load its code,
# or draw; if a widget was unknown; or if any sample's native and guest screenshots differ by a
# pixel or in size.
#
# Self-contained, from this checkout, prototype modules only (nothing is published):
#   1. builds the same-source samples as a SIGNED Zipline guest bundle
#      (keliver-compose-gatea-sample-guest; a scratch Ed25519 key in its build dir) and a
#      Kotlin-only iOS simulator app (keliver-compose-gatea-sample-ios) that trusts that key and
#      registers the generated gatea renderers (Gradle, with -Pkeliver.gatebIos);
#   2. serves the bundle from 127.0.0.1:PORT (default 8494; never 8077, 8096 or 8478);
#   3. CREATES a simulator (newest iOS runtime, an iPhone), with --with-tests first runs
#      :keliver-compose-gatea-composeui:iosSimulatorArm64Test on it, then boots it, fixes its
#      status bar, installs the app;
#   4. for each sample (orders, card, item: Gate B-d's ItemDetail) launches the app twice — the screen drawn by native
#      Compose, and the same file loaded from the signed bundle through the real protocol into the
#      generated renderers — screenshots each (xcrun simctl io screenshot) and compares the pixels;
#   5. times first frames: N process-cold launches per route (terminate, 1 s, launch), alternating.
#      The page cache is warm and the guest's Zipline module cache was filled by step 4, so a guest
#      launch is manifest verification over loopback plus modules from the local cache, not a first
#      download. The app logs main() to the second frame after its root was first composed;
#   6. deletes the simulator and stops the server, whatever happened.
# Results: DIR/results.md, DIR/*.png (native, guest, diff), DIR/logs/. A run first removes the
# files an earlier run left there (only those names).
#
# Needs: macOS with Xcode (xcrun simctl, codesign), python3, JDK 17 for Gradle.
set -euo pipefail
NAME=keliver-gateb-ios-sim
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
fail() { echo "$NAME: $*" >&2; exit 1; }

OUT="$ROOT/build/$NAME"; PORT=8494; RUNS=5; TYPE=release; TESTS=0; COMPARE_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --out) OUT="${2:?--out needs a directory}"; shift 2 ;;
    --port) PORT="${2:?--port needs a number}"; shift 2 ;;
    --runs) RUNS="${2:?--runs needs a number}"; shift 2 ;;
    --debug) TYPE=debug; shift ;;
    --with-tests) TESTS=1; shift ;;
    --compare-only) COMPARE_ONLY=1; shift ;;
    -h|--help) sed -n '2,32p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "$NAME: unknown argument $1 (see --help)" >&2; exit 2 ;;
  esac
done
case "$PORT" in ''|*[!0-9]*) fail "--port must be a number" ;; esac
case "$RUNS" in ''|*[!0-9]*) fail "--runs must be a number" ;; esac
case "$PORT" in 8077|8096|8478) fail "port $PORT belongs to the owner's portal or the iOS CI relay; choose another" ;; esac
command -v python3 >/dev/null || fail "python3 is needed."
SAMPLES="orders card item"

# The comparison and the report (a PNG reader in the standard library: 8-bit RGB/RGBA, as simctl
# writes). Exits non-zero if any sample differs; the control must differ.
report() { # device runtime build bundle-bytes
python3 -I - "$OUT" "$1" "$2" "$3" "$4" "$SAMPLES" <<'PY'
import os, struct, sys, zlib, statistics
out, device, runtime, build, bundle_bytes, samples = sys.argv[1:7]
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

lines = ["# Gate B part c: the simulator run", "",
         f"Device: {device}, {runtime.rsplit('.', 1)[-1]}; app build: {build}; guest bundle: {bundle_bytes} bytes of modules.", "",
         "| Sample | Screenshot | Differing pixels (native vs guest) | Bounding box |", "|---|---|---|---|"]
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
if control == 0: sys.exit("keliver-gateb-ios-sim: the control (orders vs card, native) shows no difference: the comparison is broken")
lines.append(f"| control: orders vs card (native) | | {control} | must be > 0 |")

t = {"native": [], "guest": []}
timing = os.path.join(out, "timing.txt")
for line in (open(timing) if os.path.exists(timing) else []):
    mode, ms = line.split(); t[mode].append(int(ms))
runs = len(t["guest"])
lines += ["", "First frame (orders), ms from main() to the second frame after the root was first composed (both routes mark it with a SideEffect).",
          "Process-cold launches (terminate, 1 s, launch), alternating; the page cache is warm and the guest's Zipline module cache was filled by the screenshot launches, so a guest launch is manifest verification over loopback plus modules from the local cache:", "",
          "| Route | Runs | Median | Min | Max |", "|---|---|---|---|---|"]
for mode in ("native", "guest"):
    v = t[mode]
    if v: lines.append(f"| {mode} | {len(v)} | {statistics.median(v):.0f} | {min(v)} | {max(v)} |")
import re
for label, pattern in (("guest: code loaded (codeLoadSuccess)", r"codeLoadSuccess at (\d+) ms"), ("guest: first widget created", r"first guest widget at (\d+) ms")):
    v = []
    for i in range(1, runs + 1):  # only this run's launches
        path = os.path.join(out, "logs", f"time-guest-{i}.log")
    m = re.search(pattern, open(path).read()) if os.path.exists(path) else None
        if m: v.append(int(m.group(1)))
    if v: lines.append(f"| {label} | {len(v)} | {statistics.median(v):.0f} | {min(v)} | {max(v)} |")
lines += ["", "A simulator is not a phone: the Mac's CPU and GPU run it, so the times are for comparing the two routes here, not for absolute numbers.", ""]
if failures: lines += ["**FAILED:** " + "; ".join(failures), ""]
open(os.path.join(out, "results.md"), "w").write("\n".join(lines))
print("\n".join(lines))
if failures: sys.exit("keliver-gateb-ios-sim: native and guest differ: " + "; ".join(failures))
PY
}

if [ "$COMPARE_ONLY" = 1 ]; then
  [ -d "$OUT" ] || fail "--compare-only needs an existing --out directory"
  OUT="$(cd "$OUT" && pwd -P)"
  report "(compare only)" "-" "-" "-"
  exit 0
fi

command -v xcrun >/dev/null || fail "xcrun is needed (Xcode)."
mkdir -p "$OUT/logs"
OUT="$(cd "$OUT" && pwd -P)"
# An earlier run's output would mix into this one's report: remove it (only the names this script writes).
for sample in $SAMPLES; do rm -f "$OUT/$sample-native.png" "$OUT/$sample-guest.png" "$OUT/$sample-diff.png"; done
rm -f "$OUT/timing.txt" "$OUT/results.md" "$OUT"/logs/shot-*.log "$OUT"/logs/time-*.log \
  "$OUT/logs/gradle.log" "$OUT/logs/server.log" "$OUT/logs/ios-test.log"
rm -rf "$OUT/ios-test-captures"

UDID=""; SERVER_PID=""
cleanup() {
  set +e
  if [ -n "$UDID" ]; then
    xcrun simctl terminate "$UDID" dev.keliver.gateb >/dev/null 2>&1
    xcrun simctl shutdown "$UDID" >/dev/null 2>&1
    xcrun simctl delete "$UDID" >/dev/null 2>&1 && echo "$NAME: deleted simulator $UDID"
  fi
  [ -n "$SERVER_PID" ] && kill "$SERVER_PID" 2>/dev/null && echo "$NAME: stopped the bundle server"
}
trap cleanup EXIT
trap 'exit 130' INT TERM

# 1. Build the bundle and the app.
echo "$NAME: building the guest bundle and the $TYPE app (Gradle; log in $OUT/logs/gradle.log)"
# The release link's devirtualization pass is heavy: it runs in its own process with its own heap,
# not in a shared daemon (whose heap ran out once).
( cd "$ROOT" && ./gradlew --console=plain -Pkeliver.gatebIos -PgatebBuildType="$TYPE" -DRELEASE_SIGNING_ENABLED=false \
    -Pkotlin.native.disableCompilerDaemon=true -Pkotlin.native.jvmArgs=-Xmx6g \
    :keliver-compose-gatea-sample-guest:compileProductionExecutableKotlinJsZipline \
    :keliver-compose-gatea-sample-ios:assembleGateBApp ) > "$OUT/logs/gradle.log" 2>&1 \
  || { grep -E '^e: |What went wrong' -A4 "$OUT/logs/gradle.log" | head -30 >&2; fail "the build failed; see $OUT/logs/gradle.log"; }
BUNDLE="$(dirname "$(find "$ROOT/keliver-compose-gatea-sample-guest/build/zipline" -name manifest.zipline.json -path '*roduction*' | head -1)")"
[ -f "$BUNDLE/manifest.zipline.json" ] || fail "no production manifest under keliver-compose-gatea-sample-guest/build/zipline"
grep -q '"gateb-ed25519"' "$BUNDLE/manifest.zipline.json" || fail "the manifest is not signed with the scratch key"
APP="$ROOT/keliver-compose-gatea-sample-ios/build/gateb/$TYPE/GateB.app"
[ -x "$APP/GateB" ] || fail "no app at $APP"
codesign --force --sign - "$APP" >/dev/null 2>&1 || fail "ad-hoc signing $APP failed"
BUNDLE_BYTES="$(find "$BUNDLE" -name '*.zipline' -exec cat {} + | wc -c | tr -d ' ')"
echo "$NAME: bundle $BUNDLE ($BUNDLE_BYTES bytes of .zipline modules)"

# 2. Serve it.
python3 -I -m http.server "$PORT" --bind 127.0.0.1 --directory "$BUNDLE" > "$OUT/logs/server.log" 2>&1 &
SERVER_PID=$!
MANIFEST="http://127.0.0.1:$PORT/manifest.zipline.json"
for _ in $(seq 1 50); do curl -sf -m 1 -o /dev/null "$MANIFEST" && break; sleep 0.2; done
curl -sf -m 2 -o /dev/null "$MANIFEST" || fail "the bundle server did not answer on $PORT (is the port taken?)"
# Something answered; make sure it is our server, not another one already on the port.
kill -0 "$SERVER_PID" 2>/dev/null || { cat "$OUT/logs/server.log" >&2; SERVER_PID=""; fail "the bundle server exited (port $PORT taken?)"; }

# 3. A simulator of our own.
read -r RUNTIME DEVTYPE DEVNAME < <(xcrun simctl list -j runtimes devicetypes | python3 -I -c '
import json, sys
d = json.load(sys.stdin)
ios = [r for r in d["runtimes"] if r.get("platform") == "iOS" and r.get("isAvailable")]
if not ios: sys.exit("no available iOS runtime")
r = max(ios, key=lambda r: tuple(int(x) for x in r["version"].split(".")))
supported = {t["identifier"]: t["name"] for t in r.get("supportedDeviceTypes", [])} or {t["identifier"]: t["name"] for t in d["devicetypes"]}
phones = [i for i, n in supported.items() if n.startswith("iPhone") and "Pro" not in n and "Plus" not in n and "Max" not in n and " e" not in n and "SE" not in n]
phones = phones or [i for i, n in supported.items() if n.startswith("iPhone")]
pick = sorted(phones)[-1]
print(r["identifier"], pick, supported[pick].replace(" ", "_"))
') || fail "no iOS runtime or iPhone device type"
UDID="$(xcrun simctl create "keliver-gateb-$$" "$DEVTYPE" "$RUNTIME")" || fail "simctl create failed"
echo "$NAME: created simulator $UDID (${DEVNAME//_/ }, $RUNTIME)"

if [ "$TESTS" = 1 ]; then
  echo "$NAME: :keliver-compose-gatea-composeui:iosSimulatorArm64Test on it (log in $OUT/logs/ios-test.log)"
  ( cd "$ROOT" && ./gradlew --console=plain -Pkeliver.iosTestDevice="$UDID" -DRELEASE_SIGNING_ENABLED=false \
      :keliver-compose-gatea-composeui:iosSimulatorArm64Test ) > "$OUT/logs/ios-test.log" 2>&1 \
    || { tail -40 "$OUT/logs/ios-test.log" >&2; fail "the iOS same-source test failed"; }
  cp -R "$ROOT/keliver-compose-gatea-composeui/build/gate-ios/iosSimulatorArm64Test" "$OUT/ios-test-captures" 2>/dev/null || true
  xcrun simctl shutdown "$UDID" >/dev/null 2>&1 || true
fi

xcrun simctl boot "$UDID"
xcrun simctl bootstatus "$UDID" -b > /dev/null
xcrun simctl status_bar "$UDID" override --time 9:41 --dataNetwork wifi --wifiMode active --wifiBars 3 \
  --cellularMode active --cellularBars 4 --batteryState charged --batteryLevel 100 >/dev/null 2>&1 || true
xcrun simctl ui "$UDID" appearance light >/dev/null 2>&1 || true
xcrun simctl install "$UDID" "$APP"

# Launches the app process-cold (terminate, 1 s, launch) and waits for its first-frame line in the
# launched mode; prints the milliseconds.
launch() { # mode sample tag
  local log="$OUT/logs/$3.log"
  xcrun simctl terminate "$UDID" dev.keliver.gateb >/dev/null 2>&1 || true
  sleep 1
  : > "$log"
  # The app appends its log lines to the file simctl hands it as GATEB_LOG.
  SIMCTL_CHILD_GATEB_LOG="$log" xcrun simctl launch "$UDID" dev.keliver.gateb "$1" "$2" "$MANIFEST" >/dev/null
  local line ms
  for _ in $(seq 1 300); do
    # One read of the first-frame line, then judge that same line (it must be in the launched mode).
    line="$(grep -m1 'GATEB first-frame' "$log" || true)"
    if [ -n "$line" ]; then
      ms="$(printf '%s\n' "$line" | sed -n "s/.*GATEB first-frame mode=$1 ms=\([0-9][0-9]*\)\$/\1/p")"
      [ -n "$ms" ] && { echo "$ms"; return 0; }
      break
    fi
    grep -q 'codeLoadFailed\|uncaughtException\|manifestParseFailed\|unknownWidget' "$log" && break
    sleep 0.2
  done
  echo "$NAME: no first frame in mode $1 for $2; its log:" >&2; cat "$log" >&2; return 1
}

# A guest launch must have verified the manifest with the scratch key, loaded its code, and met no
# unknown widget.
check_guest_log() { # tag
  local log="$OUT/logs/$1.log"
  grep -q 'manifestVerified key=gateb-ed25519 ' "$log" || { cat "$log" >&2; fail "$1: the manifest was not verified with the scratch key"; }
  grep -q 'codeLoadSuccess' "$log" || { cat "$log" >&2; fail "$1: no codeLoadSuccess"; }
  ! grep -q 'unknownWidget' "$log" || { cat "$log" >&2; fail "$1: an unknown widget"; }
}

# 4. Screenshots and pixels.
for sample in $SAMPLES; do
  for mode in native guest; do
    ms="$(launch "$mode" "$sample" "shot-$sample-$mode")" || fail "the $mode $sample screen did not draw"
    sleep 2 # settle (no animation in the samples; shadows and images are drawn by then)
    xcrun simctl io "$UDID" screenshot "$OUT/$sample-$mode.png" >/dev/null 2>&1
    if [ "$mode" = guest ]; then check_guest_log "shot-$sample-$mode"; fi
    echo "$NAME: $sample/$mode drawn (first frame $ms ms), $OUT/$sample-$mode.png"
  done
done

# 5. First-frame timing: alternating process-cold launches (see the header for what is warm).
: > "$OUT/timing.txt"
for i in $(seq 1 "$RUNS"); do
  for mode in native guest; do
    ms="$(launch "$mode" orders "time-$mode-$i")" || fail "timing launch $i ($mode) did not draw"
    if [ "$mode" = guest ]; then check_guest_log "time-$mode-$i"; fi
    echo "$mode $ms" >> "$OUT/timing.txt"
  done
done
xcrun simctl terminate "$UDID" dev.keliver.gateb >/dev/null 2>&1 || true

report "${DEVNAME//_/ }" "$RUNTIME" "$TYPE" "$BUNDLE_BYTES" || fail "the screens differ; see $OUT/results.md"
echo "$NAME: results in $OUT/results.md"
