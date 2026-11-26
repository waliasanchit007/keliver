# NF5 R1: the reveal-only arm (A12 session plan)

R1 is the second step before any (B) PR ([`SHOW_BEFORE_COMPUTE.md`](../SHOW_BEFORE_COMPUTE.md),
section 0 item 3 and section 7, "(B) Before any (B) PR: R0 and R1"; R1's row there is the spec).
**The question:** when the host shows a hidden, already composed detail on the click, before the
guest runs, how soon is it on the screen, and is that within one frame of native? This directory
holds the phone session's scripts and queries, laid out as R0's ([`../nf5-r0/`](../nf5-r0/)). Every
step below was run end to end on an emulator on 2026-11-26 ("Dry run", at the end). Nothing here
has been run on a phone.

## The arm

Prototype and bench only: gate A's container (`keliver-compose-gatea-composeui`, `Retained.kt`)
and the three-way sample. No library outside the prototype changes.

- **The guest (`-e x r1prep`, `RevealStack.kt`).** The app's list and detail sit in the host's
  retained container (gate A's `ExtraRetained`) as two entries. The detail's entry is composed once
  and then only rebound. After a back it keeps its item, keyed `r1:parked:detail` (hidden). 300 ms
  later the guest binds it to the item the next open will tap and keys it `r1:prepared:detail`
  (hidden, revealable). The driver's next row is known: open i taps row (i − 1) mod 5. Each open
  records whether the prediction held (`n_nextHit`). The open's click then changes the key to
  `r1:shown:detail`, so the guest's step after the click recomposes the detail's counter only: series
  H's floor step ("the detail already composed with the right item").
- **The host (`R1Reveal.kt`, gate A's `LocalRetainedReveal`).** A policy the container reads:
  prepared and parked entries are never shown by the container's order (`RetainedReveal`).
  - **`reveal`**: a click in the list shows the prepared detail itself, in the frame after the
    click, before the guest has run. The container watches the final pass of the UP that a handler
    consumed, outside any scroll. The reveal lasts until the guest's own state shows the detail; it
    is then "live", with no visual change. The marks are `revealed`, `revealDrawn` (the start of the
    window draw of the frame that drew the revealed detail, read as the bench's `drawn` is),
    `revealRecorded` (when its draw was recorded, inside `Record View#draw()`) and `revealLive`.
  - **`idlemeasure`**: the hidden prepared detail is measured with the shown entry's constraints,
    and not placed, whenever it changes. That happens while idle (the preparation's batch), so the
    reveal's frame only places and draws it (3.2's "Layout (M5)").
- **What it leaves out of (B)'s reveal protocol** (3.2, I13), on purpose: no shell (the prepared
  detail is the full detail of the predicted item), no token, answer or cancel, no inertness, no
  back or `REVEAL_LIMIT` handling. The click is observed in the pointer pass, not taken from the
  generated click dispatch (M3); any click in the list reveals. None of that is on the draw path R1
  measures.
- **`x=trace`** adds the driver's own trace sections: `bench:up open <i>` (its start is the tap,
  the UP), `bench:drawn <screen> <seq>` (the window draw the bench takes as `drawn`), and the
  reveal's `r1:reveal`, `r1:revealDrawn` and `r1:revealLive`. The queries find every open by these
  markers, so no R8 lambda name is needed (R0's `upname.txt`).

## The arms (`run.sh`)

All `-e bench nav --ei n 14 --ei warmup 2 --ez localApi true`, each in a fresh process (`am start -S`)
under its own trace. There is no `--ei hold`: the driver holds each tap for 40 ms and lifts it in one
message, and every time is from the UP, like native's 28 ms (THREE_WAY_BENCHMARK.md, "How to run it").

| Arm | `-e form` | `-e x` | Trace |
|---|---|---|---|
| `native-simple` | `native` | `trace` | 50 s |
| `simple-reveal-idle` | `guest` | `r1prep,reveal,idlemeasure,trace` | 60 s |
| `simple-reveal` | `guest` | `r1prep,reveal,trace` | 60 s |
| `simple-off` | `guest` | `r1prep,trace` | 60 s |
| `native-heavy` | `native` | `heavy,trace` | 50 s |
| `heavy-reveal-idle` | `guest` | `heavy,r1prep,reveal,idlemeasure,trace` | 75 s |
| `heavy-reveal` | `guest` | `heavy,r1prep,reveal,trace` | 75 s |
| `heavy-off` | `guest` | `heavy,r1prep,trace` | 75 s |

Native's arms are those of section 7's R1 row (`-e form native -e bench nav`, and with `-e x heavy`):
their first draw is the detail with its data in hand. They run in the same session and build as the
reveal arms; each native arm runs just before its size's guest arms.

## The bar, fixed before the run

From section 7's R1 row (B-1f's bar on the same arm, round 3's M5), medians over opens 3–14, ms
from the UP:

1. **First draw.** For each size, simple and heavy, the reveal's first draw (the bench's
   `revealDrawn`) must be at most native's first draw (the bench's `drawn` on `native-<size>`) +
   16.7 ms.
2. **The reveal's draw.** With the idle measure, the reveal frame's `Record View#draw()` (trace,
   `reveal.rec_ms`: the one that holds `r1:revealDrawn`) must be under 10 ms, simple and heavy.

**R1 passes** if `simple-reveal-idle` and `heavy-reveal-idle` each meet bars 1 and 2. The arms
without the idle measure are judged against bar 1 and reported; they say whether the idle measure
is needed. If a bar is missed, the shell's first layout is the cost to cut before B4 (section 7).

**Which "first draw".** Both sides use the same mark: the start of the window's draw of the frame
that drew the content (an `OnDrawListener`), so the comparison is like for like. Compose measures,
lays out and records inside `Record View#draw()`, after that mark, so bar 2 bounds what the mark
leaves out. The frame's later readings come from the trace (below) and are reported beside native's,
with the reading named. R0 showed that "presented" is ambiguous on this phone (caveat 1 of
THREE_WAY_BENCHMARK.md's "NF5 R0"). No verdict rests on them.

**When an arm counts.** All of these must hold:
- its results file has 14 opens, `{"done":true}` and no timeout;
- the trace has the 14 opens (`count.opens`);
- on the reveal arms, at least 10 of opens 3–14 revealed (`count.reveal`, and the bench's `revealed`);
- on the guest arms, `nextHit` is 1 on every open;
- the identity check passes: on at least 10 of opens 3–14, the trace's click → composed (`ec`) is
  within 2 ms of the bench's (`composed_click`; `analyse.sh`'s "check").

An arm that fails is rerun once (`run.sh <arm>`, then `analyse.sh <arm>` and `analyse.sh bar`); if it
fails again it is reported as it is, with no verdict.

**Also reported, not judged:**
- click → composed with the reveal off, on, and on with the idle measure (the bench's
  `composed_click` and the trace's `guest.ec`). The reveal's frame takes the main thread when the
  guest's frame tick needs it, and appendix B assumed it adds nothing to the floor step.
  `count.k_reveal` says in how many opens the reveal's frame was the one that ticked the guest.
- the guest's own detail drawn (`drawn`) on each guest arm.
- the reveal frame's readings beside native's frame: `f_end`, the main thread's frame end;
  `q_drew`, the buffer queued by the draw that drew it (and `q_r0`, R0's rule for it);
  `row_end`, the app's frame-timeline row; `sf_end`, the display frame.

Prediction (written before any phone run): the reveal's first draw 13–30 ms after the UP, as the
retained back's first draw on the A12 (13–17 ms after the press, appendix B); `Record View#draw()`
about 8.5 ms with the idle measure (series C's heavy host back: 8.5–9.0 ms), more without it.

## What is measured

**The bench's own marks** (`<arm>.results.txt`, one line per open; ms from the UP): `revealed`,
`revealDrawn`, `revealRecorded`, `revealLive`, the guest's `click` and `composed` (its clock),
`drawn`, `n_nextHit`. `analyse.sh` prints them per open (`<arm>.bench.txt`), then the medians over
opens 3–14 (`<arm>.bench-med.txt`).

**The trace** ([`prelude.sql`](prelude.sql), [`measure.sql`](measure.sql)). Per open, two frames,
each found by its marker in the open's window (its UP to the next tap's UP):
- `reveal`: the doFrame that holds `r1:revealDrawn`;
- `drawn`: the doFrame that holds `bench:drawn detail *`. That is native's detail, or the guest's
  live detail.

Each has these readings, ms from the UP (R0's lesson: measure to the frame that drew the content,
and name the reading):

| Column | What |
|---|---|
| `marker` | the marker |
| `f_start`, `f_end` | that doFrame's start and end: `f_end` is the main thread's frame end |
| `rec_ms`, `ml_ms` | its `Record View#draw()` (for `reveal`, the one that holds the marker), and Compose's measure and layout inside it |
| `q_same`, `ds_ms` | the end of the first `queueBuffer` in the RenderThread's `DrawFrames <its vsync>` (empty when that draw queued nothing), and that DrawFrames' length |
| `q_drew`, `dw_ms`, `drew_own` | **the buffer queued by the draw that drew it**: the first `DrawFrames` from the doFrame on that queues a buffer, the end of that `queueBuffer`; its length; 1 when it is the doFrame's own vsync's, 0 when a later frame's. `count.<kind>.drew_own` in q2 counts the 1s |
| `q_r0` | R0's rule (`frame-end.sql`): the first `DrawFrames` of at least 2 ms from the doFrame on. It passes over a short draw that did queue the buffer, so it can read a later frame (the dry run, below) |
| `row_end` | the end of the app's actual frame-timeline row for the vsync, when not zero-length |
| `sf_end` | the end of SurfaceFlinger's display frame that row points to |

The frame's vsync id is the one in its own `Choreographer#doFrame <id>` slice, which its
`DrawFrames <id>` and its frame-timeline row carry. A late frame on Android 13 or later also has a
nested `Choreographer#doFrame - resynced to <id> in <ms>` slice with the same start; the queries
never take it. Vsync ids are tokens, not counts: they step by more than 1 per vsync.

The guest's step after the click (guest arms): `ec`, the first `zl:sendEvent` in the window (the
click) → the first `zl:composed` after it; `e_wait`, the event's end → the `zl:sendFrame` that
composed it; `k_reveal`/`k_ms`, whether the last doFrame before that frame (whose callbacks tick the
guest's frame clock) was the reveal's frame, and its length.

[`q1.sql`](q1.sql) prints every open; [`q2.sql`](q2.sql) the medians over opens 3–14 with their `n`
and the counts; [`q3.sql`](q3.sql) the main thread's and RenderThread's frames and the app's rows
around opens 3–5, to see which frame drew what (R0's ripple). `analyse.sh` also prints the
identity check and, last, `bar`: the bar's verdicts from the arms' medians.

## Steps

Conventions: `R` is a checkout of this PR's head; `N` is a scratch directory; `SERIAL` is the
A12's serial from `adb devices`. Every `am`, `pm` and `cmd` call names `dev.keliver.sample` only,
and no step changes a setting. Never `adb kill-server`, `start-server`, `connect` or `disconnect`.

**0. The phone.** Android 12 or later: the frame timeline (`row_end`, `sf_end`) needs 12; the rest
works from 10.

```
adb devices
adb -s SERIAL shell getprop ro.build.version.release
adb -s SERIAL shell dumpsys battery
adb -s SERIAL shell ls -la /data/local/tmp/nf5r1 /data/misc/perfetto-traces/
```

If `/data/local/tmp/nf5r1` exists from an earlier session, clean it first (step 7). Record the power
state: R0 and the C11 reference ran on the charger, and comparisons across power states are not
valid (THREE_WAY_BENCHMARK.md, "A12 reference for 0.5.0"). Native's arms run in the same session, so
the bar does not cross power states.

**1. The build: the APK and the commit it is built from** (MS4_PLAN.md: every NF5 measurement records
the commit it was built from). From `R`, JDK 17, with no local changes (`git status --porcelain`
prints nothing):

```
git -C R rev-parse HEAD
M2=N/m2
./gradlew publishToMavenLocal -Dmaven.repo.local=$M2 -PkeliverVersion=0.0.0-gateb \
  -PkeliverComposePrototype -DRELEASE_SIGNING_ENABLED=false
(cd sample && ./gradlew --no-daemon :host-android:assembleBenchmark \
  -Dmaven.repo.local=$M2 -PkeliverComposePrototype)
shasum -a 256 sample/host-android/build/outputs/apk/benchmark/host-android-benchmark.apk
unzip -l sample/host-android/build/outputs/apk/benchmark/host-android-benchmark.apk 'lib/*/libquickjs.so'
```

Record the commit, the APK's size and sha256, and `lib/arm64-v8a/libquickjs.so`'s size (2,618,992
bytes: the published fork `dev.keliver.zipline:*:1.28.0-keliver.1`, with Long as BigInt, the
sample's default). The prototype is published to a scratch repository, never `~/.m2`. The dry run's
APK (below) may be used instead if its sha256 matches and this PR's code has not changed since.

**The trace processor** is the one in the benchmarks APK's assets
(`assets/trace_processor_shell_aarch64`, Perfetto v45, as R0's): `(cd sample && ./gradlew
:benchmarks:assembleBenchmark -Dmaven.repo.local=$M2 -PkeliverComposePrototype)`, then
`unzip -p sample/benchmarks/build/outputs/apk/benchmark/benchmarks-benchmark.apk
assets/trace_processor_shell_aarch64 > N/tp`. R0's copy (sha256 `ae2f18ff…68a0e7d`) is the same file.

**2. Install** (as series G, H and R0):

```
adb -s SERIAL install -r -d N/host-android-benchmark.apk
adb -s SERIAL shell am broadcast -a androidx.profileinstaller.action.INSTALL_PROFILE \
  -n dev.keliver.sample/androidx.profileinstaller.ProfileInstallReceiver
adb -s SERIAL shell cmd package compile -f -m speed-profile dev.keliver.sample
```

**3. The files.**

```
adb -s SERIAL shell mkdir -p /data/local/tmp/nf5r1
adb -s SERIAL push N/tp /data/local/tmp/nf5r1/trace_processor_shell
adb -s SERIAL shell chmod 755 /data/local/tmp/nf5r1/trace_processor_shell
adb -s SERIAL push R/docs/nf5-r1/. /data/local/tmp/nf5r1/
```

**4. Run the eight arms** (about 10 minutes; detached, as R0's):

```
adb -s SERIAL shell '(nohup sh /data/local/tmp/nf5r1/run.sh < /dev/null > /data/local/tmp/nf5r1/log.txt 2>&1 &)'
```

Done when `adb -s SERIAL shell cat /data/local/tmp/nf5r1/done.txt` prints `done`. `run.sh <arm>`
runs one arm. If Perfetto rejects `symbolize_ksyms` (no trace, or a tiny one, and the error in
`log.txt`), delete that line from the phone's `cfg.txt` and run again (R0's note; R1 does not use
the kernel symbols).

**5. Check the runs.**

```
adb -s SERIAL shell cat /data/local/tmp/nf5r1/log.txt
adb -s SERIAL shell 'cd /data/local/tmp/nf5r1 && grep -c "\"bench\":\"open\"" *.results.txt && grep -c "\"done\":true" *.results.txt; grep -l timeout *.results.txt; ls -la /data/misc/perfetto-traces/'
```

Each results file: 14 opens, `{"done":true}`, no timeout; eight `nf5r1-*.pftrace`.

**6. Query, on the phone** (about 25 minutes on the A12, detached):

```
adb -s SERIAL shell '(nohup sh /data/local/tmp/nf5r1/analyse.sh all < /dev/null > /dev/null 2>&1 &)'
```

Done when `analyse-done.txt` exists; then `adb -s SERIAL shell cat /data/local/tmp/nf5r1/analyse-all.txt`.
Per arm, in order: the bench's opens and medians; the identity check; q1, q2 and q3. Last comes the
bar. Read the arm's validity ("When an arm counts") before any of its numbers. Then read q3 for opens
3–5 of each reveal arm: the reveal's doFrame, its `DrawFrames`, and whether a RenderThread-only frame
(the press's ripple) pushed it a vsync later.

**7. Keep everything, then clean the phone.**

```
mkdir -p N/r1
adb -s SERIAL pull /data/local/tmp/nf5r1 N/r1/
for a in native-simple simple-reveal-idle simple-reveal simple-off native-heavy heavy-reveal-idle heavy-reveal heavy-off; do
  adb -s SERIAL pull /data/misc/perfetto-traces/nf5r1-$a.pftrace N/r1/; done
adb -s SERIAL shell rm -rf /data/local/tmp/nf5r1
adb -s SERIAL shell 'rm -f /data/misc/perfetto-traces/nf5r1-*'
```

Record each trace's sha256. The app stays installed.

**8. Record** a new section in THREE_WAY_BENCHMARK.md (phone state, power, the commit and the APK,
each arm's validity, the bar's table, the frame readings beside native's, click → composed with the
reveal off and on). Put the verdict in SHOW_BEFORE_COMPUTE.md's section 0, in its own PR.

## Dry run, 2026-11-26 (emulator)

DRYRUN
