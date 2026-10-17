# M2 on Android: the reference app's benchmark build on a Samsung S25 Ultra (2026-10-17)

`m2/android.sh`, 5 measured rounds after one discarded warm-up round; every
round was good (no `m2-android.errors`). Raw rows: `m2-android.tsv`; build and
device: `m2-android.env`; medians, minimums and maximums: `summary.txt`.

## What ran

- **Device:** Samsung SM-S938B (S25 Ultra), Android 17, over wireless adb;
  thermal status 0 before the runs, battery 36.7–37.0 °C across them. The
  screen timeout was set to 10 minutes for the run (it already was).
- **App:** the reference app (`reference/inventory`, recreated by
  `bootstrap.sh` from the published tools 0.3.9, libraries 0.3.3), its publish
  target and host scaffolded by the **published tools 0.3.10**, which follow
  the app's libraries (0.3.3; host included): a Production bundle (40 modules,
  1.5 MB raw), signed; `host-android` with the 0.3.10
  template's `benchmark` build type (release settings, minified with R8,
  profileable, not debuggable), `keliver.updates=next-launch`, channel stable.
  APK 6.2 MB. It carries the libraries' merged `assets/dexopt/baseline.prof`
  (the host has no profile of its own), which `adb install` did not apply:
  dexopt status `verify` before and after the runs.
- **Bundle server:** `ci/w3/static_https.py` on the Mac, reached through
  `adb reverse` (HTTPS with a throwaway CA trusted by the benchmark source set
  only). It is HTTP/1.0 and closes every connection, so each request is a new
  TCP connection and TLS handshake over adb's wireless transport (whether TLS
  sessions resumed is not recorded).

## Results (median of 5; min–max)

| | process → open | process → code | process → content | open → content | `am start` TotalTime | PSS after content |
|---|---|---|---|---|---|---|
| **first** launch (`pm clear`: lookup, download, load) | 45 ms (36–76) | 954 ms (921–980) | **1024 ms** (976–1048) | 971 ms | 108 ms | 111,073 kB |
| **cold** (process stopped, bundle cached) | 48 ms (41–66) | 202 ms (196–219) | **243 ms** (238–263) | 196 ms | 101 ms | 88,811 kB |
| **warm** (process alive, sent home, reopened in a new task) | – | – | – | **35 ms** (30–40) | 35 ms | 91,086 kB |

- `process` is `Process.getStartElapsedRealtime()` (bindApplication): the
  fork before it is in `am start`'s TotalTime, not in these columns.
- `content` is the reference guest's first composition (`LaunchedEffect(Unit)`),
  so it lands with `first-frame` (in 8 of 10 first and cold runs; 7 and 41 ms
  later in the other two): this app has no data load to wait for.
- `am start` TotalTime is the system's time to the host activity's first
  frame (the native shell before the guest's content).
- Warm opens logged no `process` or `code` mark (asserted): same process, the
  code already loaded.

## Where the time goes

- **The host's start.** `start()` at app launch (Application.onCreate) runs
  the bundle lookup (`bundles/index.json`) and creates, but does not start,
  the Treehouse app. The code load starts when the first screen's
  `TreehouseContent` asks for content **and** the lookup has answered,
  whichever is later. In 4 of the 5 measured cold runs the lookup answered
  after `open` (48 ms after process start).
- **Cold, about 150 ms from open to code:** usually the tail of the index
  fetch, then the manifest fetch (`DefaultFreshnessCheckerNotFresh`: the
  manifest is always fetched), then loading the 40 cached modules. Two
  requests per cold launch, both over a new TLS connection.
- **First launch, about 0.9 s from open to code** (median 904 ms): 42 requests
  (the index, the manifest and 40 modules; the host downloads 4 at a time),
  1.45 MB over wireless `adb reverse`, each module written to the cache, then
  loaded. First launch minus cold is about 750 ms, the cost of fetching and
  caching the modules. That cost is likely dominated by per-connection setup
  and transfer through wireless adb, but this run does not separate them:
  per-request timing in the server log, or a keep-alive server, would. Fewer,
  larger downloads are the lever to measure (a single archive: ADOPTION_PLAN
  item 7, D2, still to be measured).
- **Warm, 35 ms:** a new screen in a running guest.

For scale: the M0 run on the same phone (2026-10-17, about 02:40 IST), with a
**debug** host and a **Development** bundle (3.3 MB, plain HTTP on
`127.0.0.1:8090`), took about 1.44 s from process to content on its **first
launch after install** (process → open 389 ms, open → code 926 ms, code →
content about 122 ms). This build's first launch takes 1.02 s (45 ms, about
904 ms and about 70 ms). Most of the 413 ms gain (344 ms) is process → open
(the release-settings host); open → code barely moved,
with less than half the bytes but TLS added. That M0 run's logs are not in
the repository.

## Not measured here

- iOS: the iPhone run needs the owner's decision on the signing team.
- A real CDN; a cold start with no network (the cache-only path); a phone
  with a slower CPU.
