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

1. **Within one frame of native, on two readings.** For each size, simple and heavy, bar 1 is
   **met only if both hold**:
   - (a) **first draw**: the reveal's first draw (the bench's `revealDrawn`) is at most native's first
     draw (the bench's `drawn` on `native-<size>`) + 16.7 ms; **and**
   - (b) **buffer queued**: the buffer queued by the draw that drew the reveal (trace, `reveal.q_drew`)
     is at most native's (`drawn.q_drew` on `native-<size>`) + 16.7 ms.

   If (a) holds and (b) does not, the verdict is **"NOT MET (no buffer in the reveal's frame)"**,
   never a pass. (If (a) fails, it is "NOT MET (first draw)", or "NOT MET (first draw and buffer)".)
2. **The reveal's draw.** With the idle measure, the reveal frame's `Record View#draw()` (trace,
   `reveal.rec_ms`: the one that holds `r1:revealDrawn`) must be under 10 ms, simple and heavy.

**R1 passes** if `simple-reveal-idle` and `heavy-reveal-idle` each meet bars 1 and 2. The arms
without the idle measure are judged against bar 1 and reported; they say whether the idle measure
is needed. If a bar is missed, the shell's first layout is the cost to cut before B4 (section 7).

**Why two readings** (the maintainer's ruling of 2026-11-26, after the emulator dry run and its
review; written before any phone run, and **the owner may still change it before the A12 run**).
The first-draw mark is the start of the window's draw of the frame that drew the content (an
`OnDrawListener`), on both sides, so (a) is like for like. But the driver's tap blocks the main
thread for 40 ms, so on both sides that mark lands in the first frame after the UP: (a) only checks
that the reveal fired in that frame. It cannot see whether that frame put anything on the screen.
The dry run found that it did not: in 47 of 48 reveal opens the reveal's own frame queued no buffer,
and the next frame showed it ("Dry run", below). (b) sees that, and it is the reading R0 recorded.
Compose measures, lays out and records inside `Record View#draw()`, after the first-draw mark, so bar
2 bounds what (a) leaves out.

**Also printed beside bar 1, per arm, not a condition: the per-open reading in vsyncs.** In how many
of opens 3–14 the reveal's buffer was queued no later than the vsync after native's own: the reveal's
`q_vs` (the app's vsyncs between the UP and the buffer's queueing; measure.sql) at most native's
median `q_vs` + 1. A one-vsync slip then reads as one frame late, not as a coin flip at 16.7 ms.

The frame's later readings (`row_end`, `sf_end`) come from the trace and are reported beside
native's, with the reading named. R0 showed that "presented" is ambiguous on this phone (caveat 1 of
THREE_WAY_BENCHMARK.md's "NF5 R0"). No verdict rests on them.

**When an arm counts.** All of these must hold:
- its results file has 14 opens, `{"done":true}` and no timeout;
- the trace has the 14 opens (`count.opens`);
- on the reveal arms, at least 10 of opens 3–14 revealed (`count.reveal`, and the bench's `revealed`);
- on the guest arms, `nextHit` is 1 on every open;
- the identity check passes: on at least 10 of opens 3–14, the trace's click → composed (`ec`) is
  within 2 ms of the bench's (`composed_click`; `analyse.sh`'s "check").

`analyse.sh bar` prints each arm's validity beside its verdict: the results file's opens, `done` and
timeouts, `count.opens`, on the reveal arms `count.reveal` and the bench's reveals, on the guest arms
the lowest `nextHit` and the identity check's result. An arm that fails any of them prints
**"INVALID"** and the failed condition instead of a verdict (and so does a reveal arm whose native arm
fails). An arm that fails is rerun once (`run.sh <arm>`, then `analyse.sh <arm>` and `analyse.sh
bar`); if it fails again it is reported as it is, with no verdict. The last line is R1's: PASSES,
DOES NOT PASS, or NO VERDICT when an arm it rests on does not count.

**Also reported, not judged:**
- click → composed with the reveal off, on, and on with the idle measure (the bench's
  `composed_click` and the trace's `guest.ec`). The reveal's frame takes the main thread when the
  guest's frame tick needs it, and appendix B assumed it adds nothing to the floor step.
  `count.k_reveal` says in how many opens the reveal's frame was the one that ticked the guest.
- the guest's own detail drawn (`drawn`) on each guest arm.
- the reveal frame's readings beside native's frame: `f_end`, the main thread's frame end;
  `q_r0`, R0's rule for the buffer; `row_end`, the app's frame-timeline row; `sf_end`, the display
  frame; and in how many opens the frame's own vsync queued the buffer (`drew_own`).
- per arm, from q4 over all 14 opens: in how many a RenderThread-only buffer came in the 200 ms
  before the DOWN and in the tap's hold, and in how many the first frame after the UP queued no
  buffer (the dry run's lost frame; "What the run shows", below).

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
| `q_vs` | `q_drew` in vsyncs: how many of the app's vsyncs fell between the UP and that buffer's queueing (the grid: the app's expected frame-timeline starts; the period: `vsync.period_ms` in q2) |

The frame's vsync id is the one in its own `Choreographer#doFrame <id>` slice, which its
`DrawFrames <id>` and its frame-timeline row carry. A late frame on Android 13 or later also has a
nested `Choreographer#doFrame - resynced to <id> in <ms>` slice with the same start; the queries
never take it. Vsync ids are tokens, not counts: they step by more than 1 per vsync.

The guest's step after the click (guest arms): `ec`, the first `zl:sendEvent` in the window (the
click) → the first `zl:composed` after it; `e_wait`, the event's end → the `zl:sendFrame` that
composed it; `k_reveal`/`k_ms`, whether the last doFrame before that frame (whose callbacks tick the
guest's frame clock) was the reveal's frame, and its length.

[`q1.sql`](q1.sql) prints every open; [`q2.sql`](q2.sql) the medians over opens 3–14 with their `n`
and the counts; [`q3.sql`](q3.sql) the main thread's and RenderThread's frames and the app's rows of
every open, from 20 ms before its DOWN to 150 ms after its UP, to see which frame drew what (R0's
ripple); [`q4.sql`](q4.sql) counts that per open: RenderThread-only buffers (an outermost
`queueBuffer` with no `DrawFrames` around it) before the DOWN, in the hold and after the UP, and
whether the first frame after the UP queued a buffer. `analyse.sh` also prints the identity check
and, last, `bar`: each arm's validity and the bar's verdicts.

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
APK (below) may be used instead, only if its sha256 is the one recorded there; then the commits
recorded are its own (`ea0c1d69c`, the prototype from `760b0de73`), not this PR's head. The head
later merged main's library changes (none in the prototype, the sample or the bench), so a build
from it is a different APK with the same arm.

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
Per arm, in order: the bench's opens and medians; the identity check; q1, q2, q3 and q4. Last comes
the bar. Read each arm's validity (the bar prints it; "When an arm counts") before any of its
numbers. Then read q4 for **every open of every arm** (native and off too), and q3 for every open
where q4 shows a RenderThread-only buffer in the hold or a first frame after the UP that queued
nothing: the reveal's doFrame, its `DrawFrames`, and whether a RenderThread-only frame took that
vsync and pushed the reveal's buffer a vsync later.

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

**Where.** The AVD `keliver_a12_api35` (arm64, Android 15, 4 cores, 60 Hz), headless, on the Mac,
as R0's dry run. Its numbers are **not results**: a fast host, no cluster split, almost no
scheduling noise. The run shows only that the pipeline works, from the build to the bar.

**Build** (step 1's commands, through the agents' scratch wrappers): the prototype published to a
scratch repository from `760b0de73` (6 min), and the sample's benchmark APK built from `ea0c1d69c`
(1 min). The two commits differ only in `sample/`, and the APK holds no file that changed after
`ea0c1d69c`. `host-android-benchmark.apk` is 26,078,079 bytes, sha256
`a03ccfa490c245e704cf3c68551cb1023227054c1b612aaabd69013350929f3a`; `lib/arm64-v8a/libquickjs.so` is
2,618,992 bytes. The trace processor was R0's copy, sha256 `ae2f18ff…68a0e7d`.

**Run.** Steps 2–7, in order: install and compile, push, the eight arms (`run.sh`, 10:27–10:37), the
check, `analyse.sh all` (about a minute on the emulator), pull, clean. Every arm: 14 opens, `done`, no
timeout; traces of 77–147 MB. Every query ran on every arm. Validity, every arm: `count.opens` 14;
on the four reveal arms `count.reveal` 12 of 12, and the bench's `revealed` on every open; `nextHit` 1
on every open of the six guest arms; the identity check passed 12 of 12 on each guest arm (`n/a` on
native). One smoke arm (`simple-reveal-idle`) ran first, and the fixes below came from it.

**What the bar printed** (medians over opens 3–14, ms from the UP; emulator, not results):

| | native | reveal + idle measure | reveal | off |
|---|---|---|---|---|
| **simple** first draw (`drawn`; `revealDrawn`) | 2 | 1 | 1 | |
| `Record View#draw()` of that frame (`rec_ms`), of it measure and layout (`ml_ms`) | 0.7 (0.6) | 0.4 (0.1) | 0.8 (0.6) | |
| its buffer queued (`q_drew`); by its own vsync (`drew_own`) | 3.6; 12 of 12 | 10.5; 0 of 12 | 10.3; 0 of 12 | |
| its display frame end (`sf_end`) | 17.4 | 25.7 | 26.0 | |
| click → composed, bench (trace `ec`) | | 7.5 (8.1) | 8 (7.9) | 9 (8.8) |
| the guest's live detail drawn (`drawn`) | | 23 | 23 | 22.5 |
| **heavy** first draw | 2 | 1 | 1 | |
| `rec_ms` (`ml_ms`) | 2.1 (1.7) | 0.6 (0.2) | 1.4 (1.0) | |
| `q_drew`; `drew_own` | 5.0; 12 of 12 | 10.6; 0 of 12 | 10.1; 1 of 12 | |
| click → composed, bench (trace) | | 10 (10.6) | 9.5 (9.8) | 10.5 (11.0) |
| the guest's live detail drawn | | 23 | 23 | 23.5 |

Bars 1 and 2 printed MET on all four reveal arms (on an emulator: no verdict); the buffer-queued
reading printed "within" on all four. The full output is `analyse-all.txt` in the dry run's pulled
directory (not committed). Its `bar` section predates the last `analyse.sh` change (the `drew_own`
counts and the buffer-queued line); `analyse.sh bar` was rerun on the emulator after it, on the same
queries' output, and the table above is that rerun.

**What the run shows about the method, not about the A12:**
- **The first frame after the UP is late on both sides.** The driver's tap blocks the main thread for
  40 ms, so the frame after the UP starts 0.4–0.8 ms after it, a vsync late (`resynced to … in
  20–30 ms`). The same holds for native and for the reveal, so the first-draw comparison stays like
  for like.
- **The reveal's own frame queued no buffer** in 47 of 48 reveal opens (`drew_own`). Its RenderThread
  draw was dropped, and the next frame queued the reveal about 10 ms after the UP, where native's own
  frame queued at 3.6–5.0. In opens 3–5 of every reveal arm, q3 shows a RenderThread-only
  `queueBuffer` 5–9 ms before the UP, during the tap's blocked 40 ms: the swap chain was stuffed when
  the reveal's frame came. That buffer is not in `simple-off` (the same guest and policy, no
  reveal) or in native. The cause is not established. Inferred: something the reveal arms do on the
  DOWN starts a RenderThread animation that the others start later (a press ripple, R0's mechanism).
  **The first-draw mark does not see this, and the buffer-queued reading does**, which is why `bar`
  prints both. The phone session reads q3 for it.
- **The idle measure moves the reveal's layout out of its frame** even here: measure and layout inside
  the reveal's `Record View#draw()`, 0.1–0.2 ms with it against 0.6–1.0 without.
- **The guest form keeps drawing a frame per vsync for about 140 ms after an open** (`simple-off`
  too), where native stops after its frame. That is the guest form's, not the reveal's.
- **R0's rule (`q_r0`) fails on short draws:** on native, whose draws take under 2 ms here, it read
  the first long draw about 755 ms later.

**What broke, and the fixes** (all in this directory or the PR's code now):
1. **The sample did not build on main's prototype stack.** Its catalog override for the published
   Zipline fork (`dev.keliver.zipline:*:1.28.0-keliver.1`) declared the `zipline` library and plugin
   aliases in settings as well as in the imported TOML, and the TOML's entry wins. So Gradle asked
   for `app.cash.zipline` at the fork's version, which does not exist. CI does not build the sample,
   so nothing had caught it. `sample/settings.gradle.kts` now imports a copy of the TOML with the
   fork's group.
2. **A hidden detail's `composed` report would have hidden the back's** (found reading the code
   before the run). The guest host keeps one report per change batch, the last. The parked detail
   recomposes in the back's batch (its counter changes), so the back would have timed out. A hidden
   detail now does not report (`LocalDetailHidden`).
3. **The wrong doFrame.** On Android 13 or later a late frame has a nested `Choreographer#doFrame -
   resynced to <id> in <ms>` slice with the same start. The prelude took it, and with it a vsync id
   that has no `DrawFrames` and no frame-timeline row (`q_same`, `row_end` and `sf_end` came out
   empty). It now takes the frame's own `Choreographer#doFrame <id>`.
4. **R0's "first DrawFrames of at least 2 ms" passed over the draw that queued the buffer.** In open 3
   of the first run, the reveal's own `DrawFrames` took 1.7 ms and queued the buffer at 5.4 ms; R0's
   rule read the next one, at 38.0 ms. `q_drew` now takes the first draw that queues a buffer, and
   R0's rule is kept beside it as `q_r0`.
5. **Vsync ids are tokens, not counts.** The first run's `drew_lag` (the difference of two ids) read
   21–50 for draws one frame apart. It is now `drew_own`: 1 when the doFrame's own vsync drew it.
6. **Empty marks shifted the columns** of the bench's per-open table (found before the run): a
   native line has no `revealed`, and awk collapses empty fields. Missing marks now print `-`.
