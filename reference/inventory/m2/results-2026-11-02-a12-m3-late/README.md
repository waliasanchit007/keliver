# M3's case: a Keliver screen opened a few seconds after launch. Galaxy A12, 2026-11-02

M3 (`docs/ADOPTION_PLAN_2026-10.md`, item 2) starts the code load as soon as the lookup
answers, instead of when the first screen asks for content. On 2026-10-31
(`../results-2026-10-31-a12-m3`) it made no difference for an app that opens its Keliver screen
at launch: the lookup always answered after the screen had opened. This run measures the case
M3 is for: the host starts at launch, and the first Keliver screen is asked for **3 s later**,
as in an existing app that shows its own screens first.

**Result: M3 cuts the time from the screen opening to its content to about a third on a cold
start, and by about 2 s on the first launch, with the compile state fixed.** Medians of 10 runs
(two sets of 5 each, ABAB), `open` → `content` in ms:

| | before | after (M3) |
|---|---|---|
| first launch (after `pm clear`) | 2352 | 265 |
| cold (process stopped, bundle cached) | 816 | 267 |
| warm (process running, a new task) | 142 | 132 |

With M3 the code is loaded by the time the screen opens: `code` comes about 0.7 s after process
start and `open` at 3.3 s, in all 20 first and cold runs (without M3, `code` comes after `open`
in all 20). What is left is the screen's own composition and first frame. Without it, the load
starts at `open`: the manifest check and the module load (first launch: the download too) sit
between the screen opening and its content. The warm open loads nothing either way.

## What ran

- **Device:** Samsung Galaxy A12 (SM-A125F), Android 12, wireless adb; thermal status 0. Build
  fingerprint in `*.env`.
- **App:** the reference app from the K4 run 3 work directory, the same as on 2026-10-31: its
  `host-android` scaffolded by the **published tools 0.3.15**, libraries 0.3.3 (the reference
  app's pin, `appRuntime.keliverVersion`), built as the benchmark build type (release settings,
  minified, profileable, not debuggable), `keliver.updates=next-launch`, channel stable.
- **Before / after:** that host with the screen asked for 3 s after the activity starts (the
  first hunk of `m3-late-trial.diff`, in `MainActivity`); "after" also has M3's one line (the
  second hunk). Nothing else differs.
- **Bundles:** the same static site, sequences 1–9; sequence 9 (40 modules) is the newest stable
  entry, served by `ci/w3/static_https.py` over HTTPS through `adb reverse`.
- **Harness:** `m2/android.sh` from a scratch copy (`harness.diff`: the path to `ci/`, and
  `M3_COMPILE`, below), 5 measured rounds after a discarded warm-up, each variant installed fresh
  and uninstalled after its rounds. Every round checks the phone is awake, unlocked and not on a
  call. No run had an error (`*.tsv`; each run's log in `logs/`).

## Two passes, and why the second fixes the compile state

1. **As installed** (`before-late`, `after-late`, `-2`): first 2368 → 572 ms, cold 783 → 395 ms,
   but warm 159 → **291** ms, 10 of 10 runs slower.
2. **Compiled to `speed`** after each install (`-s1`, `-s2`; `cmd package compile -m speed -f`,
   the state recorded in each `.env` before and after the runs): the numbers above.

The warm difference in pass 1 was not M3. Background dexopt compiled the app (`speed-profile`)
during both before-late sets and neither after-late set (each `.env` records the state after its
runs). In every set measured, on 2026-10-31 too, a warm open took 138–166 ms when the app had
been compiled by the end of its runs and 276–343 ms when it was still at `verify`, with or
without M3. With every variant at `speed`, warm is 142 against 132 ms. The same dexopt favoured
"before" in pass 1's first and cold launches, so pass 1 understates M3's gain there.

## Not measured here

- iOS. Its template change is the same (`app.start()` at the end of `createApp`).
- A release build as an adopter ships it: no baseline profile, so a fresh install runs at
  `verify` until background dexopt; pass 1 shows both states.
- A delay other than 3 s. Any delay longer than the load (about 0.5 s cold here, 2 s on a first
  launch) should show the same; a shorter one, part of the gain.
