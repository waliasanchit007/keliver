# M5's memory cycles, eight in one launch. Galaxy A12, 2026-11-02

Two memory cycles (CI's T4, and `../results-2026-11-02-a12-m4-m5`) cannot tell a slow leak from
caches settling. This launch ran eight, for the leak bound `ci/m0/memory.py` takes
(`--leak-kb`).

## What ran

- **Device:** Samsung Galaxy A12 (SM-A125F), Android 12, wireless adb.
- **Host:** the benchmark build of `../results-2026-11-02-a12-m4-m5` (the reference app's
  `host-android` scaffolded by the published tools 0.3.17 zip, libraries 0.3.3, release settings,
  minified, profileable), compiled to `speed` after install (`run.log`); sequence 9 over HTTPS
  through `adb reverse`.
- **Script:** `android-cycles.sh` (as run; paths and the device serial replaced): one launch, then
  eight memory cycles (`am broadcast -a dev.keliver.debug.MEMORY_CYCLE`), 25 s apart, each
  opening a second screen and closing it; then `memory.py --cycles 8`. The phone was checked to
  be awake, unlocked and not on a call before each cycle.
- A second launch stopped at its second cycle when the phone went to sleep (the script checks
  before each cycle and exits, so `run.log` has no `== done` line): not counted. So this is
  **one run**.

## At each cycle's close (KiB)

| cycle | `pss` | rss | native heap | Java heap | QuickJS in use | graphics |
|---|---|---|---|---|---|---|
| 1 | 52 889 | 119 240 | 21 229 | 4 697 | 5 141 | 12 953 |
| 2 | 52 583 | 118 416 | 20 728 | 4 698 | 5 146 | 12 955 |
| 3 | 51 846 | 118 632 | 19 176 | 4 696 | 5 150 | 12 956 |
| 4 | 51 569 | 117 740 | 18 840 | 4 696 | 5 155 | 12 958 |
| 5 | 51 558 | 117 736 | 18 855 | 4 697 | 5 160 | 12 959 |
| 6 | 51 567 | 117 604 | 18 831 | 4 697 | 5 165 | 12 960 |
| 7 | 51 739 | 117 876 | 18 793 | 4 697 | 5 170 | 12 962 |
| 8 | 51 709 | 117 952 | 18 777 | 4 697 | 5 174 | 12 963 |

- **`pss` and the native heap settle:** both fall over the first four cycles (pss −1 331 KiB to
  cycle 5), then stay within 181 KiB. The Java heap does not move. So the growth two cycles
  showed on the iPhone (`../results-2026-11-02-iphone-m4-m5`) may be the same settling; this
  run cannot say for iOS.
- **QuickJS grows steadily: 4–5 KiB a cycle** (+33 KiB from cycle 1 to 8: +5, +4, +5, +5, +5,
  +5, +4), as on the CI emulator (+5; once +9, reference-app run 41430386432, T4 benchmark), the
  simulator (+6 to +8) and the iPhone (+6, +7). It is small, but it does not settle in eight
  cycles: something of each screen stays in the guest's heap. A whole screen left in the guest
  would be far more: a screen's share of QuickJS's heap is about 150 KiB (a cycle's `open` →
  `content` adds 148–165 KiB here and 145–166 on the iPhone, computed from #212's logs; from the
  second cycle on, a close frees 144–158 here and 150 on the iPhone). The first cycle's close
  frees more (228 KiB here, 234–254 on the iPhone) because it is the first forced collection
  since launch.
- **graphics** grows 1–2 KiB a cycle.

## For the leak bound

A bound on QuickJS's growth from cycle 1's close to cycle 2's of **32 KiB** is more than three
times the largest growth seen on any device (+9, the CI emulator once) and about a fifth of a
screen's share of the guest's heap (about 150 KiB). It catches a screen the guest keeps whole,
not one the host keeps, nor (over T4's two cycles) a guest leak of under about 27 KiB a cycle
above the steady 4–5. A bound on `pss` or the iOS
footprint would follow the platform's own caches (this run: −306 KiB from cycle 1 to 2; the
iPhone: +384 to +464; the CI simulator: about +0.5 to +1.5 MiB), so none is set.
