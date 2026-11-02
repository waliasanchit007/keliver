# M4's frame profile and M5's memory profile on a phone. Galaxy A12, 2026-11-02

M4 (the guest frame profile) and M5 (the memory profile) are in the hosts from tools 0.3.17
(`docs/DEVICE_HOST.md`, "The guest frame profile", "The memory profile"). CI has run them on the
emulator and the simulator only. This is the first phone: the same checks as CI's T3 and T4,
on the Android host scaffolded by the **published 0.3.17 zip**, debug and benchmark builds.

## What ran

- **Device:** Samsung Galaxy A12 (SM-A125F), Android 12, wireless adb (`run.env`).
- **Host:** the reference app's `host-android`, scaffolded by the published tools 0.3.17 zip
  (sha256 `2c6e69d6…`) with the app's public key; libraries 0.3.3 (the reference app's pin).
  Built as debug and as benchmark (release settings, minified, profileable), both with
  `keliver.updates=next-launch`, channel stable, and the harness's throwaway CA trusted from
  their own source sets (`ci/w7/android-test-ca.sh`), as the physical route does.
- **Compile state:** each build was compiled with `cmd package compile -m speed -f` after
  install, so background dexopt could not change the numbers between runs (it did in M3's runs,
  `../results-2026-11-02-a12-m3-late`). The benchmark build reached `speed`; ART leaves a
  debuggable app at `verify`, so the debug build ran interpreted and JIT-compiled (`run.env`).
- **Bundles:** the reference app's static site, sequence 9 (40 modules), over HTTPS through
  `adb reverse`; every launch loaded sequence 9 (checked).
- **Script:** `android-m45.sh` (scratch, as run). Per build, 3 rounds of:
  - **frames:** a launch; after its `content` mark, `ci/drive.py frames` opens Espresso beans
    and taps Add 1 twice, each step observed in the view hierarchy (`logs/*.results.json`);
    then `ci/m0/frames.py --changed` on the launch's log;
  - **memory:** a launch of its own; after its content, two memory cycles
    (`am broadcast -a dev.keliver.debug.MEMORY_CYCLE`, 25 s apart); then `ci/m0/memory.py`.
  Each launch streamed its own filtered logcat (`KeliverTiming`, `KeliverHost`), in `logs/`.
  Every launch checked first that the phone was awake, unlocked and not on a call.
- All 12 rounds passed (`run.log`).

## The frame profile (µs; median, range over the runs)

Each run's log has the same four windows: the screen's first change list (sent from its start,
not in a frame), the frame that opens Espresso beans (130 changes), and one frame per Add 1 tap
(2 changes each).

| | debug | benchmark |
|---|---|---|
| first change list, 127 changes: `sink` / `apply` | 14 301 / 44 944 | 855 / 7 408 |
| opening an item, 130 changes: `guest` | 164 702 (164 666–170 397) | 59 154 (56 879–61 638) |
| — `lead` | 146 267 | 56 102 |
| — `sink` / `apply` / `queue` | 14 301 / 37 084 / 272 | 650 / 2 675 / 148 |
| one Add 1, 2 changes (6 frames): `guest` | 28 682 (27 319–32 523) | 24 275 (22 900–26 454) |
| — `lead` | 24 515 | 22 483 |
| — `sink` / `apply` / `queue` | 1 207 / 2 537 / 408 | 217 / 337 / 134 |

- **The guest's side dominates.** In the benchmark build, a frame that changes two values costs
  about 24 ms of `guest`, 22 ms of it before the change list reaches the host (`lead`): the
  guest's composition, applying it to the guest's tree and encoding, in QuickJS. The host's own
  part (`sink` + `apply`) is under 1 ms. At 60 Hz a frame has 16.7 ms.
- Which part of `lead` is composition and which is encoding cannot be told from the host: that
  needs a span inside the guest's runtime, a library change (M4's second step).
- The debug build's host side is 5–17 times slower (not minified, interpreted); its guest side
  about 1.2 times on a 2-change frame and 2.8 times on the 130-change one (the host's work inside
  `sendFrame` grows with the list); QuickJS itself is the same in both builds.

## The memory profile (KiB)

| | debug | benchmark |
|---|---|---|
| `open`: rss | 99 776 | 83 760 |
| `content`: rss / native / QuickJS in use | 148 696 / 20 215 / 5 203 | 119 744 / 24 828 / 5 203 |
| cycle 1 `closed`: pss / graphics | 78 681 / 12 970 | 51 029 / 12 953 |
| cycle 2 `closed`: pss | 78 264 | 50 752 |
| pss, cycle 1 → 2 (each run) | −516, −352, −540 | −197, −277, −283 |
| QuickJS in use, cycle 1 → 2 | +5 | +5 |

(medians of the 3 runs except the per-run rows; each run's points are in `run.log` and its log.)

- **No growth from one cycle to the next:** in all 6 runs `pss` was lower after the second
  cycle than after the first; QuickJS's heap grew by 5 KiB. That is one data point toward the
  leak bound `memory.py` does not set yet; the iPhone's numbers are not in yet.
- QuickJS holds 5 203 KiB at `content` (4 585 KiB of it added at `code`, the guest's code;
  `memory.py`'s deltas in `run.log`), as on the emulator, in both builds.
- The benchmark build's process is about 27 MiB smaller in `pss` than the debug build's.

## Not measured here

- The iPhone (the same profiles on iOS; the Debug build only, over the LAN: see the PR).
- A release build: it compiles none of this (`src/notiming/`).
- A heavier screen than the reference app's list, and scrolling.
