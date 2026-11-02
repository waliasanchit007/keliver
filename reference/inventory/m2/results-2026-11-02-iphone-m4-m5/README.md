# M5's memory profile, and M4's launch frames, on an iPhone 13. 2026-11-02

M4 (the guest frame profile) and M5 (the memory profile) are in the hosts from tools 0.3.17
(`docs/DEVICE_HOST.md`, "The guest frame profile", "The memory profile"). CI has run them on the
simulator only; this is the first iPhone, after the Galaxy A12
(`../results-2026-11-02-a12-m4-m5`). The adoption retro measured that the iOS first open adds
about 76 MB, in another app (the private brownfield one, also a debug build;
`docs/ADOPTION_RETRO_2026-10.md`); M5 was built to say where such a cost goes.

**Result: in the reference app the first open adds 74–76 MiB of footprint (77–79 MB, about the
size the retro saw), and about two thirds of it comes before QuickJS starts, at `ui`, mostly the
host's Compose UI and first view.** From one memory cycle's close to the next the footprint grew
384–464 KiB in every run, QuickJS 6–7 KiB.

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
  `pidof.py` is the script's helper that finds the app's process.

## The memory profile (footprint, KiB; the lines say KB, which are KiB)

Medians of the 3 memory launches; every point's three values are in `logs/memory-*.log`. The
step column is the difference between two medians, not the median of each run's step.

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

- **Where the first open goes:** `ui` is 67.1, 69.8 and 68.4 % of each memory run's
  `open` → `content`. It comes before QuickJS starts (`qjs` is `-` there), and it is mostly the
  host's Compose runtime and the first screen's view; but the steps overlap the bundle lookup
  (`docs/DEVICE_HOST.md`): in 4 of the 6 launches the manifest was already loading before `ui`.
  `runtime` (QuickJS holds 93 KiB there) is Zipline, the loader and the lookup more than QuickJS;
  `code` is the loader and the guest's code (QuickJS +4 585 KiB); `content` the first content.
  Over all 6 launches `open` → `content` was 75 456–77 360 KiB.
- **Growth in every run, which two cycles cannot attribute:** each cycle opens a second screen
  and closes it. After the first cycle the process sits 22 640 KiB above its `content` level,
  and the second cycle added 384–464 KiB more, QuickJS 6–7 KiB, Kotlin heap +0 or +256 KiB, and
  15 950 → 15 995 QuickJS objects in all three runs. Two cycles cannot tell a slow leak from
  caches settling (and `closed`'s footprint is approximate, `DEVICE_HOST.md`); a leak bound
  needs more cycles. QuickJS grew about as much on the CI simulator (+6 KiB) and the Galaxy A12
  (+5).
- **Against the CI simulator** (debug, `docs/ADOPTION_PLAN_2026-10.md` item 2): the simulator
  starts at about 21.6 MiB at `open` and grew +1 056 to +1 440 KiB a cycle (#188, #195). The
  phone starts at 6.5 MiB and grew less per cycle; the simulator has other graphics memory and
  no memory limit, so the two do not compare closely.

## The frame profile: the launch only

The tap driver (`ci/m0/ios-tap`) needs an App ID that only an Apple account signed in to Xcode
can register; none was, so no frame here sent changes after the first. Each launch's first
`frames` line has the screen's first change list (127 changes, sent from its start, so no
`guest` or `lead`) and a frame that sent nothing:

| launch | first change list: `sink` / `apply` / `queue` (µs) | the empty frame: `guest` (µs) |
|---|---|---|
| frames-1 | 658 / 2 568 / 14 | 6 936 |
| frames-2 | 426 / 1 314 / 13 | 7 327 |
| frames-3 | 638 / 1 519 / 12 | 7 121 |
| memory-1 | 669 / 1 923 / 13 | 7 115 |
| memory-2 | 417 / 1 715 / 12 | 7 394 |
| memory-3 | 394 / 870 / 12 | 7 015 |

The memory launches' later lines are the cycles' second screens: `sink` 217–253 µs, `apply`
280–541 µs, and once a `queue` of 15 903 µs (memory-2, a wait for the main thread).

`open` → `content` was 258–360 ms over the 6 launches (each loading the code from the network;
Debug, not a start-up measurement).

## Not measured here

- A frame that changes values (Add 1), as on the A12: needs the tap driver signed in a team
  with an Apple account in Xcode.
- A Benchmark or Release build on the phone (see the host above).
- More than two memory cycles.
