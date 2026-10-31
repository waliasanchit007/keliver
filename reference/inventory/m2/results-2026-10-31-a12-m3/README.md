# M3 on Android: does starting the code load at host start help? Galaxy A12, 2026-10-31

M3 (`docs/ADOPTION_PLAN_2026-10.md`, item 2) is about when the code load starts.
Before M3, the host's `start()` (from `Application.onCreate`) looked the bundle
up and created the Treehouse app, and the code load began only when the first
screen asked for content. M3 calls `app.start()` as soon as the app is created,
right after the lookup answers. This run measures that one line with M2's
harness, on a slower phone than M2's.

**Result: in this setup, no change for an app that opens its Keliver screen at
launch.** In all 40 first and cold launches, with the line and without it, the
lookup answered after the screen had already opened. So the load started at the
same moment either way: when the lookup answered. The order of each run is in
`lookup-order.tsv`, taken from that run's log in `logs/`: the host's
`loading https://…` line comes after the `open` mark. The logs have no
timestamps, only order. The lookup here is slow partly because of the setup: a
new TLS connection over wireless `adb reverse` to a Python server. On a faster
network the lookup can answer before the screen opens (it did in 1 of M2's 5
cold runs on another phone), and then M3 saves that gap. M3 can only help when the first
Keliver screen opens *after* the lookup answers. An example is an existing app
that starts the host at launch and opens a Keliver screen a few seconds later.
That case is not measured here (see the end).

## What ran

- **Device:** Samsung Galaxy A12 (SM-A125F), Android 12, over wireless adb;
  thermal status 0. Build fingerprint in `*.env`.
- **App:** the reference app from the K4 run 3 work directory. Its
  `host-android` was scaffolded by the **published tools 0.3.15** (libraries
  0.4.1). It was built as the benchmark build type (release settings, minified,
  profileable, not debuggable), with `keliver.updates=next-launch` and channel
  stable.
- **Before / after:** two copies of that `host-android`. "after" adds only the
  one line in `m3-trial.diff`.
- **Bundles:** a copy of that run's static site, cut to sequences 1–9. Every
  manifest is signed with the app's key. Sequence 9, 40 modules, is the newest
  stable entry, served by `ci/w3/static_https.py` over HTTPS through
  `adb reverse`.
- **Harness:** `m2/android.sh`, 5 measured rounds after one discarded warm-up
  round, run four times in the order before, after, before-2, after-2. It ran
  from a scratch copy with two changes (`android-12-harness.diff`). First,
  Android 12's `am start -W` reports `LaunchState: UNKNOWN (0)` for the warm
  open, where the script expects `WARM`, so the copy accepts `UNKNOWN` for warm
  opens only. The timing marks already show they are warm: no `process` and no
  `code` mark. Second, the path to `ci/` is fixed, because the copy lives
  outside the checkout. No run had an error.
- **Not the template:** the measured host is the published 0.3.15 one plus
  M3's line. It predates #176's load-again (no `reload` in `Session`), so the
  fall-back and reload paths with an early start have no device run here.

## Results: median of 5 per set, in ms

| | before | after | before-2 | after-2 |
|---|---|---|---|---|
| first: process → code | 2574 | 2572 | 2973 | 3174 |
| first: process → content | 2800 | 2820 | 3460 | 3699 |
| cold: process → code | 683 | 695 | 820 | 853 |
| cold: process → content | 899 | 907 | 1202 | 1226 |
| cold: open → content | 745 | 743 | 1032 | 1056 |
| warm: open → content | 155 | 153 | 339 | 334 |

- Each pair (before/after, before-2/after-2) agrees within its own spread.
  The difference between the two pairs is the phone, not M3.
- `before.env` and `after.env` show the app compiled (`speed-profile`) after
  their runs. The second pair stayed at `verify`. When during the first pair's
  runs the compilation happened is not recorded. Pooling the four sets would
  mix the two states, so compare within a pair, knowing the first pair's two
  sets may not have seen the same state either.
- **Where the time goes on a cold launch:** the screen opens at about 160 ms.
  The lookup (`bundles/index.json`, a new TLS connection through wireless adb)
  answers after that in every run. Then come the manifest fetch and the load of
  the 40 cached modules. The load cannot start before the lookup answers, with
  M3 or without it.

## Not measured here

- **The case M3 is for:** the first Keliver screen opened after the lookup
  answers. Example: an existing app that calls `start()` at launch and shows
  its own screens first. There, before M3 the load starts when the screen
  opens. With M3 it starts at launch, so the screen can find the code already
  loaded. A run with the screen asked for 3 s after launch was set up but not
  run: the phone locked. The costs of starting at launch are in
  `docs/DEVICE_HOST.md` §4.
- iOS. The template change is the same there: `app.start()` at the end of
  `createApp`.
