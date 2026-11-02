# M5's memory profile, and M4's launch frames, on an iPhone 13. 2026-11-02

M4 (the guest frame profile) and M5 (the memory profile) are in the hosts from tools 0.3.17
(`docs/DEVICE_HOST.md`, "The guest frame profile", "The memory profile"). CI has run them on the
simulator only; this is the first iPhone, after the Galaxy A12
(`../results-2026-11-02-a12-m4-m5`). The adoption retro measured that the iOS first open adds
about 76 MB (`docs/ADOPTION_RETRO_2026-10.md`); M5 was built to say where it goes.

**Result: the first open adds 74–75 MiB of footprint, and 69 % of it is the host's UI, before
QuickJS starts.** Compose and the first view add 51.3 MiB (`ui`), QuickJS's runtime 3.4 MiB, the
guest's code 11.8 MiB (4.5 MiB of it QuickJS's heap) and the first content 7.8 MiB. From one
memory cycle's close to the next the footprint grew 384–464 KiB, QuickJS 6–7 KiB.

## What ran

- **Device:** iPhone 13 (iPhone14,5), iOS 26.6.2, over the Mac's Wi-Fi; Xcode 26.4.1.
- **Host:** the reference app's `host-ios`, scaffolded by the published tools 0.3.17 zip
  (sha256 `2c6e69d6…`) with the app's public key; libraries 0.3.3 (the reference app's pin).
  **Debug build** only: a Release framework refuses `http://`, and trusting a test CA would need
  a profile installed on the phone. `UPDATES = "next-launch"`, channel stable, bundle id
  `dev.keliver.local.phonerun`, signed with a personal development team.
- **Bundles:** the reference app's static site, sequence 9 (40 modules), served read-only over
  `http://<the Mac's Wi-Fi address>:8497`; every launch loaded sequence 9 from the network
  (checked, `logs/`).
- **Script:** `ios-m45.sh` (as run; paths and the device's ids replaced), with `none` for the
  tap driver. Per round:
  - **frames:** a launch with its console (`devicectl … --console`); 8 s after the `content`
    mark, `ci/m0/frames.py` on the console;
  - **memory:** a launch of its own; 5 s after content, two memory cycles (the Darwin
    notification `<bundle id>.keliver.memory-cycle`, 25 s apart); then `ci/m0/memory.py`.
- All 3 rounds (6 launches) passed (`run.log`). The app was uninstalled at the end.

## The memory profile (footprint, KiB; the lines say KB, which are KiB)

Medians of the 3 memory launches; every point's three values are in `logs/memory-*.log`.

| point | footprint | step | QuickJS in use |
|---|---|---|---|
| `open` | 6 624 | | – |
| `ui` | 59 120 | +52 496 | – |
| `runtime` | 62 640 | +3 520 | 93 |
| `code` | 74 704 | +12 064 | 4 678 (+4 585) |
| `content` | 82 672 | +7 968 | 5 213 |
| cycle 1 `closed` (Kotlin heap 17 280) | 105 312 | | 5 144 |
| cycle 2 `closed` (Kotlin heap 17 280) | 105 696 | | 5 150 |

| per run | 1 | 2 | 3 |
|---|---|---|---|
| `open` → `content` | +76 064 | +75 456 | +76 688 |
| footprint, cycle 1 → 2 `closed` | +432 | +464 | +384 |
| QuickJS, cycle 1 → 2 | +6 | +7 | +6 |
| Kotlin heap, cycle 1 → 2 | +256 | +0 | +256 |

- **Where the first open goes:** `ui` (the host's Compose runtime and the first screen's view,
  before any guest runs) is 52 496 of the 76 048 KiB, 69 %. QuickJS's runtime and the guest's
  code together add 15 584 KiB, the first content 7 968. The retro's 76 MB is this
  `open` → `content` (75 456–76 688 KiB here).
- **No leak shown, but the footprint is not flat:** each cycle opens a second screen and
  closes it. After the first cycle the process sits 22 640 KiB above its `content` level, and the
  second cycle adds 384–464 KiB more. QuickJS's heap grew 6–7 KiB a cycle, as on the simulator
  and the Galaxy A12. Two cycles cannot tell a slow leak from caches settling; a leak bound
  needs more cycles.
- **Against the CI simulator** (debug, `docs/ADOPTION_PLAN_2026-10.md` item 2): the simulator
  starts at about 21.6 MiB at `open` and grew +1 056 to +1 440 KiB a cycle (#188, #195). The
  phone starts at 6.5 MiB and grew less per cycle; the simulator has other graphics memory and
  no memory limit, so the two do not compare closely.

## The frame profile: the launch only

The tap driver (`ci/m0/ios-tap`) needs an App ID that only an Apple account signed in to Xcode
can register; none was, so no frame here sent changes after the first. Each launch's one
`frames` line has the screen's first change list (127 changes, sent from its start, so no
`guest` or `lead`) and one frame that sent nothing:

| run | first change list: `sink` / `apply` / `queue` (µs) | the empty frame: `guest` (µs) |
|---|---|---|
| 1 | 658 / 2 568 / 14 | 6 936 |
| 2 | 426 / 1 314 / 13 | 7 327 |
| 3 | 638 / 1 519 / 12 | 7 121 |

`open` → `content` was 258–360 ms over the 6 launches (each loading the code from the network;
Debug, not a start-up measurement).

## Not measured here

- A frame that changes values (Add 1), as on the A12: needs the tap driver signed in a team
  with an Apple account in Xcode.
- A Benchmark or Release build on the phone (see the host above).
- More than two memory cycles.
