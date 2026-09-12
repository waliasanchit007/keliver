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
