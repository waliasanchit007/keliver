# Device verification of the 0.3.4 candidate on a hosted CI runner

The local route was abandoned for disk (see `emulator-attempt.md`). This is the
CI route: boot an emulator on a GitHub-hosted `ubuntu-latest` runner and drive
the **retained CI candidate APK** — never a rebuild.

Artifact under test, downloaded from the retained artifact of run
[`34587245714`](https://github.com/waliasanchit007/keliver/actions/runs/34587245714):

| | |
|---|---|
| source commit | `ec10e191aa0fc04ce6663342b23b2ccf2ce76891` |
| zip sha256 | `2b6536a0252c33c7bfd59add41b346bb6805af8198d6642346b08e0310d6dd98` |
| APK sha256 | `d4fb1605137342f6744628f1129ddaee746cb9c8a3288639f70bf7669ac76ebf` |

## Can a hosted runner run an Android emulator? Yes — measured, not assumed

Run [`34669406940`](https://github.com/waliasanchit007/keliver/actions/runs/34669406940)
answers the question the previous block left open. The emulator step's own log:

```
INFO | Checking system compatibility:
INFO |   Checking: hasSufficientDiskSpace
INFO |      Ok: Disk space requirements to run avd: `test` are met
INFO |   Checking: hasSufficientHwGpu
INFO |      Ok: Hardware GPU compatibility checks are not required
INFO |   Checking: hasSufficientSystem
INFO |      Ok: System requirements to run avd: `test` are met
...
adb -s emulator-5554 shell getprop sys.boot_completed
1
Emulator booted.
```

- `/dev/kvm` was present after the udev rule; hardware acceleration was not
  refused.
- `system-images;android-33;aosp_atd;x86_64` resolved and installed. (API 33 has
  no `default` x86_64 image, which is why an earlier attempt named `aosp_atd`.)
- Cold start to `sys.boot_completed=1` took **38 s** (03:06:16 → 03:06:54);
  SDK + image install before it took **55 s**.
- Disk was never the constraint it was locally.

So the "ephemeral runner cannot host an emulator" hypothesis is disproved. What
remains is a shell problem, below.

## Root cause 1 — `reactivecircus/android-emulator-runner` runs `script:` under dash

The same run then failed instantly, after the emulator was up:

```
[command]/usr/bin/sh -c set -euo pipefail
/usr/bin/sh: 1: set: Illegal option -o pipefail
##[error]The process '/usr/bin/sh' failed with exit code 2
```

The action executes its `script:` input with `/usr/bin/sh`, which on the Ubuntu
runner image is dash. `set -o pipefail` is a bash builtin option. Every line of
the check script after it never ran, and the evidence upload found nothing:

```
##[warning]No files were found with the provided path: prod-attempt.log ...
```

**Fix** (`f55c9d4e3`): the workflow's `script:` is now a single `bash` call into
`scripts/keliver-device-verify.sh`. That also makes the checks reviewable in the
repository, and reusable unchanged against an attached device on a machine that
has one.

## What the checks assert

`scripts/keliver-device-verify.sh`, in order:

1. **install** — the bundle's *own* `keliver-install-device-host.sh` installs
   `host/keliver-device-host-0.3.4.apk`, so the packaged sha256 sidecar gate is
   exercised rather than bypassed. Nothing is rebuilt. The APK is re-checked for
   `assets/portal_ed25519.pub` at the moment of install.
2. **production mode, cold** — `am start … --es mode prod` on a freshly
   installed host must log a refusal, show *"Production mode refused"* in the
   uiautomator dump, request no manifest or bundle, and load no guest code.
3. **the documented adopter route** — the full packaged acceptance with
   `--serial`, ending in a guest screen rendered on the device. This is what
   proves a refusal does not latch the host into a broken state.
4. **production mode, warm** — the same four assertions again, now that a
   successful development session and its dev Zipline cache exist. U22 is about
   never silently downgrading signature verification; a populated dev cache must
   not become a way in.
5. the development route still enters the development path afterwards.

No assertion was weakened to get a green run.

## Root cause 2 — the screenshots were blank, and two assertions were weaker than they read

Run [`34669956183`](https://github.com/waliasanchit007/keliver/actions/runs/34669956183)
passed all 13 checks. Inspecting its evidence rather than its exit code found
three problems, all fixed in `1dca4f3e3` and `dca19196d`:

- `adb exec-out screencap` on a `-no-window` emulator returns an **all-black
  frame**. `prod-cold.png`, `prod-warm.png` and `dev-after.png` were
  byte-identical — sha256 `ca2e7ef708be4e086e78c6921ab25cbd9b871744c1c983089c7de250f7104528`,
  1080x2340, entirely black. They proved nothing. The screenshots are gone; the
  uiautomator view hierarchy, which was already the thing being asserted on, is
  the on-screen evidence.
- `ui_dump` reused **one** remote path, so a dump that failed silently would be
  read back as the previous screen and a later check could pass on stale
  evidence. The remote path is now unique per tag and removed first.
- the on-screen assertion matched the **heading only**, so a refusal whose
  actionable message failed to render would still have passed; and the
  development-route assertion checked only for the *absence* of the refusal
  text, which an empty dump satisfies.

A related trap: the refusal message contains an apostrophe, so uiautomator
emits it as `text='…'` with single quotes. Anything matching a literal `text="`
misses it silently. The checks match on the value text.

## Result — PASSED

Run [`34670604791`](https://github.com/waliasanchit007/keliver/actions/runs/34670604791),
executing `scripts/keliver-device-verify.sh` exactly as committed at
`dca19196d`. **19 device checks passed, 0 failed**, and the packaged adopter
acceptance inside it reported **23 passed, 0 failed** (19 portable + 4 device).

Emulator: `aosp_atd` x86_64, API 33, `pixel_5`, `-no-window -no-audio
-no-boot-anim -no-snapshot -gpu swiftshader_indirect`, KVM enabled. 80 GB free
on the runner throughout.

APK under test, installed by the bundle's own installer and checksum-matched
against the packaged `.sha256` sidecar:

```
d4fb1605137342f6744628f1129ddaee746cb9c8a3288639f70bf7669ac76ebf  host/keliver-device-host-0.3.4.apk
==> installed dev.keliver.portaldevice (versionName 1.0) on emulator-5554
```

### Production mode is refused — cold and warm

Both attempts logged the refusal and rendered it. The message is the full
actionable one, not a bare heading:

```
E PortalDevice: refusing production mode: This is the generic keliver development
host: it is development-only and cannot run production mode. It ships with no
portal identity, so it has nothing to verify a signed bundle against. Build your
app's own device host with your portal's public key embedded (see host/README.md,
"Your own production host"), or drop --es mode prod to use the development route
against serveDevelopmentZipline.
```

and in the `dev.keliver.portaldevice` view hierarchy, as two `TextView`s inside
the `ComposeView`:

```
text="Production mode refused"                    bounds=[66,859][699,925]
text='This is the generic keliver development host: it is development-only …'
```

For each attempt the run also established that **no manifest or bundle was
requested** (no `prod mode: loading`, no `prod mode: verifying`, no
`manifest.zipline.json`) and that **no guest code was loaded** (no
`codeLoadSuccess`). U22's "never silently disable signature verification for a
requested production session" is therefore observed on a device, not inferred.

The **warm** attempt is the one that matters most: it runs *after* a successful
development session has populated the dev Zipline cache, and refuses
identically (different pid, fresh log).

### The development route works, and still works after a refusal

Inside the acceptance, on the device:

```
PASS  keliver-install-device-host.sh installed the host
PASS  serveDevelopmentZipline is serving the bundle
PASS  the edited title is on the device: text="My Inbox" text="Edit screens/home.kt
      — or run keliver-portal for the visual editor." text="First item"
      text="Second item" text="Refresh"
```

and after the second refusal, a plain launch:

```
D PortalDevice: onCreate — mode=dev, devOnlyHost=true
D PortalDevice: codeLoadSuccess modules=40
```

with the guest screen in the hierarchy dump — `My Inbox`, `First item`,
`Second item`, `Refresh`. A refusal does not latch the host into a broken
state.

Evidence files are under `device-run-34670604791/`; the CI artifact
`device-evidence-34587245714` on run `34670604791` is retained until
2026-12-11.

### What this does NOT establish

- One emulator, one API level (33), one ABI (x86_64). No physical device, no
  other API level, no arm64 runtime — the APK ships `lib/arm64-v8a` and
  `lib/armeabi-v7a` but neither was executed.
- The production *load* path is still unexercised end to end: this host refuses
  production by design, so nothing here verifies a signed bundle actually
  loading in an adopter's own production host.
