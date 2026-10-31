# M3 on Android: does starting the code load at host start help? Galaxy A12, 2026-10-31

M3 (`docs/ADOPTION_PLAN_2026-10.md`, item 2) is about when the code load starts.
Before M3, the host's `start()` (from `Application.onCreate`) looked the bundle
up and created the Treehouse app, and the code load began only when the first
screen asked for content. M3 calls `app.start()` as soon as the app is created,
right after the lookup answers. This run measures that one line with M2's
harness, on a slower phone than M2's.

**Result: no change for an app that opens its Keliver screen at launch.** In
all 40 first and cold launches, with the line and without it, the lookup
answered after the screen had already opened. So the load started at the same
moment either way: when the lookup answered. M3 can only help when the first
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
  round, run four times in the order before, after, before-2, after-2. One
  change for this phone, in a scratch copy (`android-12-harness.diff`): Android
  12's `am start -W` reports `LaunchState: UNKNOWN (0)` for the warm open, where
  the script expects `WARM`. The scratch copy accepts `UNKNOWN` for warm opens
  only. The timing marks already show they are warm: no `process` and no `code`
  mark. No run had an error.

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
- In the first pair, background dexopt compiled the app (`speed-profile`, see
  `before.env` and `after.env`). In the second pair it stayed at `verify`.
  Pooling the four sets would mix those two states, so compare within a pair.
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
  run: the phone locked. M3 also costs the loaded code's memory at launch,
  whether or not a Keliver screen is opened.
- iOS. The template change is the same there: `app.start()` at the end of
  `createApp`.
