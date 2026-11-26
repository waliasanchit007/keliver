# NF5 R1: the reveal-only arm (A12 session plan)

R1 is the second step before any (B) PR ([`SHOW_BEFORE_COMPUTE.md`](../SHOW_BEFORE_COMPUTE.md),
section 0 item 3 and section 7, "(B) Before any (B) PR: R0 and R1"; R1's row there is the spec).
**The question:** when the host shows a hidden, already composed detail on the click, before the
guest runs, how soon is it on the screen, and is that within one frame of native? This directory
holds the phone session's scripts and queries, laid out as R0's ([`../nf5-r0/`](../nf5-r0/)). Every
step below was run end to end on an emulator on 2026-11-26 ("Dry runs", at the end). Nothing here
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
  markers, so no R8 lambda name is needed. **R0's `upname.txt` is not valid on this build:** the
  driver's UP is now a different lambda (`heldTap` gained the trace section), so R8 may renumber
  `ThreewayActivity$$ExternalSyntheticLambda5`. R0's queries on an APK from this branch need it
  regenerated (R0's step); R1's do not use it.

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

**Not in the eight (`run.sh <arm> ...`):** the controls of the emulator's A/B ("What the run shows").
`noripple` replaces the theme's press ripple with an indication that draws nothing (`NoIndication`),
on both forms; `listenonly` keeps the reveal's click listener but makes nothing revealable.

| Arm | `-e form` | `-e x` | Trace | Use |
|---|---|---|---|---|
| `native-simple-noripple` | `native` | `noripple,trace` | 50 s | step 4b |
| `simple-reveal-idle-noripple` | `guest` | `r1prep,reveal,idlemeasure,noripple,trace` | 60 s | step 4b |
| `native-heavy-noripple` | `native` | `heavy,noripple,trace` | 50 s | step 4b |
| `heavy-reveal-idle-noripple` | `guest` | `heavy,r1prep,reveal,idlemeasure,noripple,trace` | 75 s | step 4b |
| `simple-reveal-noripple`, `simple-off-noripple` | `guest` | `r1prep,reveal,noripple,trace`; `r1prep,noripple,trace` | 60 s | the A/B |
| `simple-listen` | `guest` | `r1prep,reveal,listenonly,trace` | 60 s | the A/B |

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
and the next frame showed it ("Dry runs", below). (b) sees that, and it is the reading R0 recorded.
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
| `q_drew`, `dw_ms`, `drew_own`, `q_src` | **the buffer that first showed it**: the end of the first buffer the RenderThread queued (an outermost `queueBuffer`) from the start of the doFrame's own `DrawFrames <vsync>` on (that DrawFrames syncs the frame's content even when it draws nothing); the length of the draw that queued it; 1 when that draw is the doFrame's own DrawFrames; and which draw it was: `own`, `draw` (a later frame's DrawFrames) or `rt` (a RenderThread-only frame: HWUI's own frame for its animations, which draws the synced content when HWUI skipped the frame's own draw). `count.<kind>.drew_own` and `count.<kind>.q_rt` in q2 count them |
| `q_r0` | R0's rule (`frame-end.sql`): the first `DrawFrames` of at least 2 ms from the doFrame on. It passes over short draws and RenderThread-only frames, so it can read a frame hundreds of ms later (native, and R0's simple-off on the A12: "What the run shows") |
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
the bar does not cross power states. **`run.sh` stops before any arm when the battery is below 30%
and neither AC nor USB powers the phone** (the C11 reference's stop,
`docs/a12-reference-2026-11-26/lib.sh`): it exits 3 and `done.txt` says `stopped: battery … before
<arm>`. Charge the phone, then run the remaining arms one by one (`run.sh <arm>`, step 4). Each arm's
`<arm>.state.txt` records the battery level and power source it ran on.

**1. The build: the APK and the commit it is built from** (MS4_PLAN.md: every NF5 measurement records
the commit it was built from). From `R`, JDK 17, with no local changes (`git status --porcelain`
prints nothing):

```
git -C R rev-parse HEAD
M2=$(cd N && pwd)/m2    # absolute: the sample's build runs from sample/, where a relative path means another directory
./gradlew publishToMavenLocal -Dmaven.repo.local=$M2 -PkeliverVersion=0.0.0-gateb \
  -PkeliverComposePrototype -DRELEASE_SIGNING_ENABLED=false
(cd sample && ./gradlew --no-daemon :host-android:assembleBenchmark \
  -Dmaven.repo.local=$M2 -PkeliverComposePrototype)
shasum -a 256 sample/host-android/build/outputs/apk/benchmark/host-android-benchmark.apk
unzip -l sample/host-android/build/outputs/apk/benchmark/host-android-benchmark.apk 'lib/*/libquickjs.so'
```

Record the commit, the APK's size and sha256, and `lib/arm64-v8a/libquickjs.so`'s size (2,618,992
bytes: the published fork `dev.keliver.zipline:*:1.28.0-keliver.1`, with Long as BigInt, the
sample's default). The prototype is published to a scratch repository, never `~/.m2`. Build from
this PR's head: the dry runs' APKs (below) come from earlier commits, and the first has neither
`listenonly` nor `noripple`.

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

Done when `adb -s SERIAL shell cat /data/local/tmp/nf5r1/done.txt` prints `done`; if it prints
`stopped: battery …`, see step 0. `run.sh <arm> ...` runs those arms. If Perfetto rejects `symbolize_ksyms` (no trace, or a tiny one, and the error in
`log.txt`), delete that line from the phone's `cfg.txt` and run again (R0's note; R1 does not use
the kernel symbols).

**4b. The ripple controls** (about 5 minutes; **recommended, and the maintainer's call**: "Decisions"
in the PR). The emulator's A/B found that the theme's press ripple keeps a RenderThread animation
running in every guest arm, and that it takes the vsync of the first frame after the driver's tap
("What the run shows"). These four arms are the two judged arms and their natives without it; `bar
noripple` reads them as a diagnostic, never as R1's verdict:

```
adb -s SERIAL shell '(nohup sh /data/local/tmp/nf5r1/run.sh native-simple-noripple simple-reveal-idle-noripple native-heavy-noripple heavy-reveal-idle-noripple < /dev/null > /data/local/tmp/nf5r1/log-4b.txt 2>&1 &)'
```

Done when `done.txt` prints `done` again.

**5. Check the runs.**

```
adb -s SERIAL shell cat /data/local/tmp/nf5r1/log.txt /data/local/tmp/nf5r1/log-4b.txt
adb -s SERIAL shell 'cd /data/local/tmp/nf5r1 && grep -c "\"bench\":\"open\"" *.results.txt && grep -c "\"done\":true" *.results.txt; grep -l timeout *.results.txt; ls -la /data/misc/perfetto-traces/'
```

Each results file: 14 opens, `{"done":true}`, no timeout; eight `nf5r1-*.pftrace` (twelve with step 4b).

**6. Query, on the phone** (about 25 minutes on the A12 for the eight arms, about 40 with step 4b;
detached). `analyse.sh all` reads every arm that ran, then prints the bar, then `bar noripple` when
step 4b ran:

```
adb -s SERIAL shell '(nohup sh /data/local/tmp/nf5r1/analyse.sh all < /dev/null > /dev/null 2>&1 &)'
```

Done when `analyse-done.txt` exists; then `adb -s SERIAL shell cat /data/local/tmp/nf5r1/analyse-all.txt`.
Per arm, in order: the bench's opens and medians; the identity check; q1, q2, q3 and q4. Last comes
the bar. Read each arm's validity (the bar prints it; "When an arm counts") before any of its
numbers. Then read q4 for **every open of every arm** (native and off too), and q3 for every open
where q4 shows a RenderThread-only buffer in the hold or a first frame after the UP that queued
nothing: the reveal's doFrame, its `DrawFrames`, and whether a RenderThread-only frame took that
vsync and pushed the reveal's buffer a vsync later. Read `q_src` beside `q_drew` (q1, and
`count.reveal.q_rt`): on the A12 a skipped frame's content may first reach the screen in a
RenderThread-only frame, as it did in R0's simple-off.

**7. Keep everything, then clean the phone.**

```
mkdir -p N/r1
adb -s SERIAL pull /data/local/tmp/nf5r1 N/r1/
for a in native-simple simple-reveal-idle simple-reveal simple-off native-heavy heavy-reveal-idle heavy-reveal heavy-off \
    native-simple-noripple simple-reveal-idle-noripple native-heavy-noripple heavy-reveal-idle-noripple; do
  adb -s SERIAL pull /data/misc/perfetto-traces/nf5r1-$a.pftrace N/r1/; done
adb -s SERIAL shell rm -rf /data/local/tmp/nf5r1
adb -s SERIAL shell 'rm -f /data/misc/perfetto-traces/nf5r1-*'
```

Record each trace's sha256. The app stays installed.

**8. Record** a new section in THREE_WAY_BENCHMARK.md (phone state, power, the commit and the APK,
each arm's validity, the bar's table, the frame readings beside native's, click → composed with the
reveal off and on). Put the verdict in SHOW_BEFORE_COMPUTE.md's section 0, in its own PR.

## Dry runs, 2026-11-26 (emulator)

**Where.** The AVD `keliver_a12_api35` (arm64, Android 15, 4 cores, 60 Hz), headless, on the Mac,
as R0's dry run. Its numbers are **not results**: a fast host, no cluster split, almost no
scheduling noise. The run shows only that the pipeline works, from the build to the bar.

**The first dry run** (sessions 1–3, 10:27–10:37): the prototype published from `760b0de73`, the APK
built from `ea0c1d69c` (26,078,079 bytes, sha256 `a03ccfa4…29f3a`), the eight arms and every query,
end to end. The fixes below (1–6) came from it. It found the lost frame ("What the run shows") but not
its cause, and its queries are superseded.

**The second dry run** (fix session 5, on the merged head; this section's numbers):
- **Build.** The prototype published to a scratch repository from `1ed97a00f` (the PR's head after
  merging main, 5 min 56 s). The sample's benchmark APK from that head (`1ed97a00f`'s sample, 26,078,075
  bytes, sha256 `2ba95671…3f3610`) built, and was not installed: two bench flags followed. The APK
  that ran the eight arms and `simple-listen` came from `81c41108f` (the head plus `listenonly`; sha256
  `aca0f7d9…7e02`); the one that ran the six `noripple` arms from `d66a2157a` (plus `noripple`; sha256
  `90f3fe03…4164`). `lib/arm64-v8a/libquickjs.so` was 2,618,992 bytes in each. The trace processor was
  R0's copy.
- **Run.** Steps 2–7: the eight arms and `simple-listen` (23:33–23:44), then the six `noripple` arms
  (23:57–00:11), each arm 14 opens, `done`, no timeout; then `analyse.sh all` over all fifteen with this
  directory's queries (about 3 minutes on the emulator; no query failed), pull, clean. Validity: every
  arm counted (`bar` printed "valid" on each: `count.opens` 14; `count.reveal` and the bench's
  reveals 12 of 12 on every reveal arm; lowest `nextHit` 1; the identity check 12 of 12).

**What the bar printed** (medians over opens 3–14, ms from the UP; emulator, not results):

| | native | reveal + idle measure | reveal | off |
|---|---|---|---|---|
| **simple** first draw (`drawn`; `revealDrawn`) | 1.5 | 1 | 2 | |
| `Record View#draw()` of that frame (`rec_ms`), of it measure and layout (`ml_ms`) | 0.8 | 0.4 (0.1) | 1.3 | |
| its buffer (`q_drew`); by its own draw (`drew_own`); by a RenderThread-only frame (`q_rt`) | 3.8; 12; 0 | 9.8; 0; 1 | 10.2; 5; 1 | |
| per open: buffer by the vsync after native's own (`q_vs` ≤ native's + 1) | | 11 of 12 | 11 of 12 | |
| its display frame end (`sf_end`) | 19.1 | 26.0 | 26.7 | |
| click → composed, bench (trace `ec`) | | 7.5 (8.1) | 9 (9.9) | 9.5 (9.7) |
| **heavy** first draw | 1.5 | 1 | 1 | |
| `rec_ms` (`ml_ms`) | 2.0 | 0.7 (0.2) | 1.3 | |
| `q_drew`; `drew_own`; `q_rt` | 4.8; 12; 0 | 9.9; 0; 2 | 9.9; 1; 2 | |
| per open, by the vsync after native's own | | 12 of 12 | 12 of 12 | |
| click → composed, bench (trace) | | 10 (10.3) | 10 (10.7) | 10.5 (10.8) |

`bar` printed bar 1 MET on (a) and (b) and bar 2 MET on both idle arms, and "R1: PASSES". On an
emulator that is no verdict: it shows that every line prints. With the ripple off (`bar noripple`,
step 4b's arms), the reveal's own frame queued its buffer in 12 of 12 opens on both sizes: `q_drew`
2.8 (simple) and 2.5 (heavy), against native's 3.9 and 4.7. The full output is `analyse-all.txt` in the
pulled directory (not committed).

**What the run shows about the method, not about the A12:**
- **The first frame after the UP is late on both sides.** The driver's tap blocks the main thread for
  40 ms, so the frame after the UP starts 0.4–0.8 ms after it, a vsync late (`resynced to … in
  20–30 ms`). The same holds for native and for the reveal, so the first-draw comparison stays like
  for like.
- **The reveal's own frame often queued no buffer** (first dry run: 47 of 48 reveal opens; second: the
  idle arms 0 of 12 by their own draw). HWUI skipped its draw, and the next frame queued the reveal
  about 10 ms after the UP, where native's own frame queued at 3.8–4.8. **The first-draw mark does not
  see this, and the buffer reading does**, which is why bar 1 reads both.
- **The A/B (review M2): the cause is the theme's press ripple and the driver, not the reveal's
  listener.** q4 counts, per open, RenderThread-only buffers (HWUI's own animation frames,
  `dispatchFrameCallbacks` → `prepareAndDraw`, no `DrawFrames`) in the tap's 40 ms hold, and whether
  the first frame after the UP queued a buffer. All 14 opens of each arm:

  | Arm | RenderThread-only buffer in the hold | first frame after the UP queued none |
  |---|---|---|
  | `native-simple`, `native-heavy` | 0, 0 | 0, 0 |
  | `simple-off`, `heavy-off` (no reveal) | 7, 7 (opens 7–10 and 12–14) | 5, 7 |
  | `simple-listen` (the listener, no reveal) | 7 (the same opens) | 7 |
  | `simple-reveal`, `simple-reveal-idle` | 13, 13 (every open but the first) | 8, 12 |
  | `heavy-reveal`, `heavy-reveal-idle` | 13, 13 | 12, 12 |
  | every `noripple` arm (six: native, reveal, idle, off) | 0 | 0 |

  - Over the seven guest arms with the ripple (98 opens), the first frame after the UP queued its
    buffer in 25 of the 25 opens with no RenderThread-only buffer in the hold. It queued none in 63 of
    the 73 that had one.
  - `simple-listen` matches `simple-off` open for open, so the listener (`revealOnClickInput`) plays
    no part.
  - With `noripple` the animation is gone from every open, and the reveal's own frame queued its
    buffer in 12 of 12 opens: 2.8 ms (simple) and 2.5 ms (heavy) after the UP, against native's 3.9
    and 4.7.
  - **The mechanism, from the trace.** The guest arms run a RenderThread animation (the main thread
    shows `RenderNodeAnimator` and `RippleHostView` messages) from the first open until about 7 s
    after the last tap: RenderThread-only buffers in 20 of the run's 21 seconds in `simple-off`, 540
    of them. Native has neither. During the hold the main thread sleeps, so the RenderThread draws its
    own frame at each vsync. The first frame after the UP then comes for a vsync the RenderThread has
    already drawn, and HWUI skips its draw ("Dropped Frame"). The frame's content still syncs, and the
    next frame draws it, about one vsync later.
  - **Inferred, not shown:** the animation is the ripple of the tapped row or of the back button,
    left running in an entry the retained container hid (hidden entries stay composed; native's
    navigation removes them). It runs more often with the reveal (13 against 7 opens) because the
    reveal hides the list in the frame after the click, before its row's ripple is drawn.
  - **What it means for the A12.** On the phone the driver also blocks the main thread through the
    hold, so the same loss will probably show in bar 1's reading (b), one vsync late (16.7 ms there,
    about 6 ms here). With a real finger the main thread is free, so the frame would not come late.
    But HWUI waits only about a quarter of a vsync for a main-thread frame before drawing its own
    animation frame. So a reveal frame longer than that may still lose its vsync while such an
    animation runs; that is inferred from the trace's `queue mFrameCallbackTask to run after 3.91ms`,
    not measured. Step 4b's ripple controls measure the reveal without the animation, beside the
    pre-registered arms.
- **R0's simple-off on the A12 shows the same mechanism.** Checked on R0's kept trace: in its opens
  5, 7, 8 and 10 the `DrawFrames` after the frame took 0.4–0.7 ms and queued nothing, and every buffer
  of the next 60 ms came from the RenderThread's own frames (`dispatchFrameCallbacks`, 5.5–8.4 ms).
  So `q_drew` now reads the first buffer of any kind after the frame's sync (`q_src` says which). It
  had read `DrawFrames` only, and so would have passed over a reveal first shown by a RenderThread
  frame on the A12. On the emulator that happened in 1–2 opens per reveal arm.
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
7. **`q_drew` read `DrawFrames` only** (fix session 5, from R0's A12 trace): a buffer queued by a
   RenderThread-only frame was passed over. It now reads every buffer from the frame's own sync on.
8. **q3 looked at opens 3–5 only, from 10 ms before the UP** (review M2): the RenderThread-only
   buffers in `simple-off` came in opens 7–10 and 12–14, so the first dry run did not see them there.
   q3 now covers every open from its DOWN, and q4 counts it.
