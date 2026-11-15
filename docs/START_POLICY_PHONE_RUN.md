# Phone run (start policy)

The one measured run on a low-end Android phone that the owner set (2026-11-15, PR #249) as the
condition for making `parallel` the default start policy. It compares `network-first` (today's
default) with `parallel` on a production host scaffolded from this checkout's templates at PR #249's
head (`206bfe9ac`). Phone: Galaxy A12 (SM-A125F), Android 12, 60 Hz, wireless adb. Run 2026-11-15,
06:50–07:20 IST.

**Verdict: parallel is fit to be the default: yes.** On this phone it reached the guest's first
content in **714 ms against 1,304 ms** (median of 10 each, −590 ms, −45%) from a warm cache, and
it was never slower in any series. Every check the reviews asked for came out clean: no dropped
release's call reached the server, the update swap showed no blank frame, and the crash ledger kept
the running release's count through a failed update. One low finding (below) is a log line, not a
behaviour, and does not block the flip. What was not exercised is listed at the end.

## Method

- **App:** the reference app (`reference/inventory`), bootstrapped from the published tools 0.3.18
  zip, with this checkout's `scripts/keliver-new-production-host.sh` writing the host
  (`KELIVER_SCAFFOLD_FROM=repo`'s route). The host builds against Maven Central `0.3.3`, the app's
  `appRuntime.keliverVersion`. Additions for this run only, never in a template:
  - the list screen gets one `Image` per row, 8 PNGs from the same server;
  - the guest POSTs `start` (`release=<label>`) through HostHttp and calls a test bridge
    (`PhoneRunBridge.ping`) from its first composition, and does both again 500 ms after `main()`
    returns, which is when the host has bound its services (`boot`). A held app is not composed, so
    `main()` is where a dropped release's code can still call out;
  - the bridge's host side logs from `withContext(scope.main)`, the context `CallGate` holds;
  - `MainActivity` draws over the lock screen (`setShowWhenLocked`), as GATE_B_RESULTS' benchmark
    did, because the phone stays locked with a secure lock screen. Never in a real host;
  - an exported `PhoneRunCoverActivity` in the benchmark source set (check 5).
- **Build:** the template's `benchmark` build type: release settings, **minified with R8**, not
  debuggable, profileable, with the M0 timing marks, signed with the debug key. It trusts a
  throwaway CA in its own source set (`ci/w7/android-test-ca.sh`). It was built twice, as
  `keliver.startPolicy=network-first` and `=parallel`, under two application ids so that each keeps
  its own warm cache. `keliver.updates=on-resume`, channel `stable`.
- **Server:** keliver-publish (tools 0.3.18) wrote signed releases v1–v15 into a static site. A
  Python HTTPS server on the Mac served it, logged every request with its time, and answered
  `POST /api/*` as the app's API. The phone reached it through `adb reverse` on one port, over
  wireless adb, which costs about 100–280 ms per request: the lookup the policies differ by is paid
  on this link. The server could also delay `bundles/index.json` or a manifest, or answer 404 for
  one release's files, each on request.
- **Cold-start timing:** `am force-stop`, then `am start -W`, and the host's timing marks from
  logcat: `process` (`Process.getStartElapsedRealtime`) to `content` (the guest's mark, one frame
  after its first composition) and to `first-frame`. This is M2's method (`reference/inventory/m2`),
  not Macrobenchmark's StartupTimingMetric. `am start -W`'s TotalTime is recorded too; it ends at
  the activity's first frame, before any guest content. One discarded warm-up round per policy, then
  10 iterations per policy, alternating which policy goes first.
- **Phone state:** at 100%, charging (it stayed on its charger), 24.7–25.8 °C, thermal status 0, no
  power saving. Other apps were left as they were. The two hosts' processes were left running
  between their own launches in the cold series (each launch force-stops only its own app).

## 1. Cold start from a warm cache (the headline)

The lookup names the cached release (v1) every time. Under `network-first` the code loads after the
lookup and the manifest; under `parallel` it loads from the cache during the lookup and shows once
the lookup confirms it. Every `parallel` start logged `start policy: parallel (the app's setting);
this start: parallel, …` and then `the lookup confirms the cached release`; every `network-first`
start logged `this start: network-first, the lookup first`. Every start loaded sequence 1.

| ms (10 each) | process → content: median | p90 | min–max | process → first-frame: median | p90 | min–max | `am start` TotalTime median |
|---|---|---|---|---|---|---|---|
| `network-first` | **1,304** | 1,385 | 1,281–1,557 | 1,291 | 1,385 | 1,265–1,557 | 398 |
| `parallel` | **714** | 729 | 696–756 | 704 | 727 | 695–729 | 383 |
| difference | **−590** | | | −587 | | | −15 |

Where the 590 ms goes, from one pair of logs (iteration 5, times from process start). Under
`network-first` the lookup ends at 0.27 s, then the manifest is fetched (about 0.28 s on the
server's clock); the code has loaded at 0.85 s and the content shows at 1.30 s. Under `parallel` the
lookup confirms the cache at 0.26 s, the cached code (started at once) has loaded at 0.45 s, and the
content shows at 0.70 s. So the saving is the manifest fetch and the code load, which `parallel`
overlaps with the lookup, on this link. A faster network narrows the
gap and a slower one widens it. The guest here is small (42 modules, one list); GATE_B_RESULTS'
2.58 s cold start was a heavier screen.

## 2. The first start after a publish (load-named path)

Under `parallel` the host loads the cached release in a held app; the lookup names the newer one; the
held app is closed and the named release loads from the network.

**Nothing of the dropped release reached the server.** With the lookup slowed by 2.5 s (so the
dropped release had fully loaded and run `main()` before the drop), cached r5 was dropped for r6:

- the server log for that start has `API POST /api/start body=release=r6` and
  `API POST /api/boot body=release=r6`, and **no request naming r5**;
- r5's `boot` POST was held and failed unsent. The guest logged `phone run: boot POST of r5 failed:
  java.lang.IllegalStateException: HostHttp: the release that made this call was not shown (the host
  loaded a newer one): not sent`, 15 ms after the host's `the cached load is dropped (not shown)`;
- r5's bridge call did not run: the host's `PhoneRunBridge` logged `bridge ran: release r6` and
  `release boot-r6`, never `boot-r5`.

Without the delay the dropped release had not finished loading when the lookup answered (about
0.2 s after process start), so nothing of it ran (r2, r4, r5 and r7 starts). A held app's screen is
never composed, so calls made from composition cannot come from a dropped release at all; only code
run from `main()` or a service's init can, and that is what the gate holds.

Time to the named release's first content (no delay; first content, or first-frame where the content
mark was lost, r4):

| release | `parallel` (modules downloaded) | `network-first` (modules downloaded) |
|---|---|---|
| r2 | 1,064 (1) | 1,439 (1) |
| r4 | 1,768, first-frame (20) | 2,069, first-frame (20) |
| r5 | 1,010 (2) | 1,328 (0, per the server's log) |
| r6 | 3,721 with the 2.5 s lookup delay (3) | 1,488 (3) |
| r7 | 1,073 (3) | not run |

The load-named path was not slower than `network-first` on this phone: it was 300–430 ms faster in
each comparable pair (r2, r4, r5). Most likely the dropped load warms the classes Zipline and QuickJS need (inferred; not
profiled).

## 3. An on-resume update after a cache start

The parallel host started from the cache (confirmed), the next release was published while it ran,
and it was sent home and brought back after 34 s (the host looks up at most once every 30 s). The
update loaded in a new app and was swapped in. Each swap was screen-recorded
(`screenrecord`, 720×1600) and read frame by frame: the list band's share of white and of dark
pixels, and the title band (which names the release).

| swap | update loaded in the new app | hidden window (applied → old app closed) | how the old app closed | PSS before → peak → settled (MB) |
|---|---|---|---|---|
| r7 → r8 | 0.73 s | 0.34 s | `no screen draws it now` | 59.6 → 62.5 → 60.3 |
| r8 → r9 (back press, check 4) | 0.74 s | 0.25 s | `no screen draws it now` | 47.9 (home) → 65.8 → 56.1 (task in background) |
| r9 → r10 (cover activity, check 5, first try) | 0.92 s | 0.23 s | `no screen draws it now` | 48.3 (home) → 76.6 → 67.9 (cover activity's memory included) |
| r10 → r11 (cover activity, manifest delayed 4 s) | 4.94 s | 0.29 s | `no screen draws it now` | 59.2 → 69.6 → 66.5 |

- **No blank frame, no missing items, no images popping in** (r7 → r8): the title changes from
  `Inventory r7` to `Inventory r8` between two consecutive frames (1.947 s and 1.963 s into the
  recording), and the list band is identical in every frame before and after, images included
  (95.5% white, 3.1% dark). The images come from the image loader's memory cache, so the new app's
  `Image`s have them on their first frame.
- **The old app closed because no screen draws it**, never by the 10 s cap, in all four swaps; the
  new app's `first-frame` mark lands in the same millisecond.
- **Peak memory:** `dumpsys meminfo` TOTAL PSS sampled about every 0.7 s, so a shorter peak can be
  missed. The swap adds about 2–10 MB over the level after it, for well under a second. The host's
  own memory lines put the new app's QuickJS heap at about 5.3 MB in use of 7 MB.
- **The new release's calls ran once, after the swap** (`bridge ran: release r8`, `boot-r8`, one
  POST each).

## 4. A back press during the swap window

The key was timed into the hidden window (r8 → r9). By the host's log it landed at its start: the
update applied at +0.74 s after the check began, the task left the screen at about +0.8 s, and the
old app closed at +0.98 s. The press acted as an ordinary root back press: the task went to the
background. The swap still completed behind it (`no screen draws it now`, 0.25 s), and on return the
screen showed r9, complete. The reference guest registers no `BackHandler`, so the documented case
(a hidden guest's *enabled* root `BackHandler` taking the press) was not exercised.

**TalkBack: not tested.** It was off (`accessibility_enabled` 0), and settings were not changed.

## 5. The same update with another activity in front

`PhoneRunCoverActivity` (opaque, full screen) was started 0.4 s after the resume. In the first try
(r9 → r10) it became opaque only at about the moment of the swap, so the Keliver screen was most
likely still visible, so that try does not count. In the second (r10 → r11) the update's manifest
was delayed 4 s, so the cover was fully up for about 4 s before the update applied.

- **The 10 s cap did not close the old app.** The swap completed in 0.29 s by the usual path (`the
  app an update replaced is closed: no screen draws it now`), with the screen behind the cover. The
  review expected a stopped activity not to recompose; on this phone the covered screen's
  composition kept running and took the new app's content.
- **On return** (back from the cover, 22 s later) the first frame of the Keliver screen already
  showed `Inventory r11` with its full list and images. Nothing stale and no blank frame.
- The crash window opened only when the screen showed again (`sequence 11 is on trial: shown` on
  return, not at the swap).
- So the cap's own path (a screen that never composes again within 10 s) was not exercised. Inferred
  from the timing, not checked: that the activity under the cover had been stopped by the swap.

## 6. A crashing release, then a failed hotfix

The parallel host was rebuilt with `KeliverConfig.crashWindowMillis = 120_000` (this check only), so
that a guest throw 50 s after showing counts while an on-resume update (which needs 30 s) can still
happen inside the window. r14 throws 50 s after its first composition.

1. Cleared app, first start: r14 loads from the network and throws: `crash rollback: sequence 14
   failed on this install (its code threw): 1 of 2; at 2 it is set aside`.
2. Cold start: `this start: parallel …` and then `the lookup confirms the cached release`, from the
   cache (nothing healthy to go back to, so crash rollback has no say).
3. r15 published, with its files answering 404. Home and back: `update check: sequence 15 is
   available (running 14, from the cache); applying … in a new app`, then `update failed: sequence
   15 did not load (… 404); the running bundle stays`.
4. r14 throws again: `crash rollback: sequence 14 SET ASIDE after 2 failures on this install`.

**The failed update left r14 on trial with its count.** Before PR #249's round 3 the update would
have taken the trial over and the second throw would have counted for nothing. An earlier attempt
(r12/r13) also showed the start's fall-back working: the lookup named r13, its manifest answered
404, and the host fell back to the cached r12 (`falling back to the cached last good bundle`). That
r12 had already proved healthy, so that attempt says nothing about the ledger.

## Findings

- **Low: every swap logs an uncaught `IllegalStateException: Zipline closed`** (all four swaps,
  10–20 ms after `the app an update replaced is closed`). The stack is the replaced app's
  `AppLifecycle.close()` (Treehouse disposing the old content) going out to a Zipline that
  `closeRetired` has just closed. It reaches the host's uncaught-exception handler on the old app's
  Zipline thread. It did not touch the crash ledger: the new release proved healthy every time, and
  the old one is no longer live. It is noise in an adopter's logs and crash reporting. A fix would be
  to close the retired app a frame after the count reaches 0, or to drop exceptions from a retired
  app's dispatcher. **Fixed in PR #249** (after this run, on both hosts): for an app the host has
  let go of (`LoadFence` DROPPED: replaced, or a dropped held load) this one exception is logged as
  a debug line, `a replaced app's late close is ignored`, instead of `uncaughtException`
  (`LoadFence.isLateClose`, tested in `StartPolicyTest`); anything else such an app throws is still
  logged as uncaught. Not re-run on the phone: a swap's log should now show that debug line where
  this run showed the stack.
- **Harness note, not a start-policy issue:** `am force-stop` within the 10 s crash window counts as
  a failure (`the app ended while it loaded or showed`), so a measuring harness that kills the app
  early can walk a release towards being set aside. The cold series was unaffected: every start ran
  the policy it logged, and sequence 1 had nothing to roll back to.
- **Observed, same as `network-first`:** after a `parallel` start whose named release failed and fell
  back to the cache, the next on-resume check says `it applies at the next start` and makes no
  in-process update. That is `network-first`'s fall-back behaviour too.

## Not exercised

- TalkBack (off; settings not changed). D-pad or keyboard focus on the hidden layer.
- A hidden guest with an enabled root `BackHandler` during the swap (the reference guest has none).
- The 10 s cap closing an old app (the covered screen kept composing).
- An update dropped by the 120 s "never reported back" timeout; only a failed one (404) was run.
- iOS.
- A real mobile network. The lookup's cost here is TLS through `adb reverse` over Wi-Fi.
- Startup on battery or under thermal load (the phone was charging and cool throughout).
