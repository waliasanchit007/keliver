# M2 on Android: the reference app's benchmark build on a Samsung S25 Ultra (2026-10-17)

`m2/android.sh`, 5 measured rounds after one discarded warm-up round; every
round was good (no `m2-android.errors`). Raw rows: `m2-android.tsv`; build and
device: `m2-android.env`; medians, minimums and maximums: `summary.txt`.

## What ran

- **Device:** Samsung SM-S938B (S25 Ultra), Android 17, over wireless adb;
  thermal status 0, battery 36.7–37.0 °C throughout.
- **App:** the reference app (`reference/inventory`, recreated by
  `bootstrap.sh` from the published tools 0.3.9, libraries 0.3.3), its publish
  target and host scaffolded by the **published tools 0.3.10**: a Production
  bundle (40 modules, 1.5 MB raw), signed; `host-android` with the 0.3.10
  template's `benchmark` build type (release settings, minified with R8,
  profileable, not debuggable), `keliver.updates=next-launch`, channel stable.
  APK 6.2 MB, compiled `verify` at install (no baseline profile); unchanged
  after the runs.
- **Bundle server:** `ci/w3/static_https.py` on the Mac, reached through
  `adb reverse` (HTTPS with a throwaway CA trusted by the benchmark source set
  only). It is HTTP/1.0 and closes every connection, so each request is a full
  TLS handshake over adb's transport.

## Results (median of 5; min–max)

| | process → open | process → code | process → content | open → content | `am start` TotalTime | PSS after content |
|---|---|---|---|---|---|---|
| **first** launch (`pm clear`: lookup, download, load) | 45 ms (36–76) | 954 ms (921–980) | **1024 ms** (976–1048) | 971 ms | 108 ms | 111,073 kB |
| **cold** (process stopped, bundle cached) | 48 ms (41–66) | 202 ms (196–219) | **243 ms** (238–263) | 196 ms | 101 ms | 88,811 kB |
| **warm** (process alive, sent home, reopened in a new task) | – | – | – | **35 ms** (30–40) | 35 ms | 91,086 kB |

- `process` is `Process.getStartElapsedRealtime()` (bindApplication): the
  fork before it is in `am start`'s TotalTime, not in these columns.
- `content` is the reference guest's first composition (`LaunchedEffect(Unit)`),
  so it lands with `first-frame`: this app has no data load to wait for.
- `am start` TotalTime is the system's time to the host activity's first
  frame (the native shell before the guest's content).
- Warm opens logged no `process` or `code` mark (asserted): same process, the
  code already loaded.

## Where the time goes

- **First launch, about 0.9 s from open to code:** 42 requests (the index, the
  manifest and 40 modules), each a new TLS connection to a Python server
  through `adb reverse`. This measures the test transport as much as the host;
  a CDN with keep-alive or HTTP/2 would be faster, and fewer, larger
  downloads (D2's single archive) would cut the per-request cost on any
  server.
- **Cold, about 150 ms from open to code:** the index and the manifest are
  fetched again (two TLS handshakes; `DefaultFreshnessCheckerNotFresh`), then
  40 cached modules are loaded and evaluated. The host's `start()` at app
  launch only looks the bundle up; the code load waits for the first screen
  (`open`, 48 ms after process start).
- **Warm, 35 ms:** a new screen in a running guest.

For scale: the M0 run on the same phone (2026-10-16) measured a **debug**
host with a **Development** bundle at about 1.44 s from process to content
on a cold start. This release-settings build with a Production bundle takes
0.24 s.

## Not measured here

- iOS: the iPhone run needs the owner's decision on the signing team.
- A real CDN; a cold start with no network (the cache-only path); a phone
  with a slower CPU.
