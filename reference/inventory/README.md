# Inventory — the reference app

A small stock-keeping app built from what an adopter gets: the published
[`keliver-portal-tools` 0.3.7](https://github.com/waliasanchit007/keliver/releases/tag/portal-tools-v0.3.7)
release and the `dev.keliver:*:0.3.3` libraries on Maven Central. The app itself
is never compiled against this checkout. Neither of its routes uses anything
else: development, and production. Production means signed publishing plus
the Android and iOS production hosts, all written by the zip's own `bin/`
scaffolders. Only the CI harness is this repository's: `ci/`, and the
isolation guard it sources.

**This is dogfooding, not adoption.** We wrote the app, so it can show where the
adopter route works and where it is rough. It cannot show that anyone outside
the project has adopted Keliver: nobody has.

## What it does

Two portal-owned screens, one hand-owned presenter layer, deterministic data:

| file | owner | what |
|---|---|---|
| `screens/inventory.kt` | portal | title, a search field, a summary line, an empty state (`if (b.isEmpty)`), one row per item (`b.items.forEach`) |
| `screens/item.kt` | portal | one item: quantity, a low-stock warning (`if (b.isLowStock)`), Remove 1 / Add 1 / Receive 10, adjustment history |
| `logic/InventoryState.kt` | you | eight fixed items (`SeedInventory`), search, selection, quantity adjustments (never below zero) |
| `logic/InventoryPresenter.kt`, `logic/ItemPresenter.kt` | you | the screens' `Bindings`, read live from the state |
| `logic/InventoryApp.kt` | you | navigation: the list, or the selected item |
| `device/Main.kt` | you | scaffolded by `keliver-new-device-target.sh`, then edited to mount `InventoryApp` |
| `editor/.../InventoryPreview.kt` | you | the per-app Live preview entry (both screens, real presenters) |

The behaviour it is checked against is in [`EXPECTATIONS.md`](EXPECTATIONS.md),
committed with the harness in `1ea4356d9` — the head of the first device run —
and unchanged since. (The macOS compile, ingest, edit and Live-preview runs came
before that commit.)

## Recreate it

Needs JDK 17+, python3, curl, git — the adopter guide's prerequisites.

```bash
reference/inventory/bootstrap.sh /some/empty/dir
```

That downloads the public 0.3.7 zip, checks it against the release's `.sha256`
**and** the hash pinned in the script, runs `keliver-init`, copies in `app/`'s
screens and logic, runs `keliver-new-device-target.sh` and `keliver-new-editor.sh`,
and then overlays the files the scaffolders cannot write. What it overlays is
recorded in `inventory/hand-edits.diff`. Pass a local zip as a second argument to
skip the download; it must have the same hash.

Then, from `/some/empty/dir/inventory`:

```bash
export KP=/some/empty/dir/tools/keliver-portal-tools-0.3.7/bin
./gradlew compileKotlinJs                 # type-check against Maven Central 0.3.3
$KP/keliver-new-publish-target.sh         # publishing + signing
$KP/keliver-portal .                      # relay + editor; builds editor/ first (minutes)
```

**The first `keliver-portal` start creates this app's store and a signing key**
under `~/.keliver-portal/apps/`. For a throwaway run, point the JVM's home at a
disposable directory first, and check it before starting anything:

```bash
export JAVA_TOOL_OPTIONS="-Duser.home=/some/empty/dir/home"
source <keliver-checkout>/scripts/keliver-test-isolation-guard.sh
keliver_require_isolated_store /some/empty/dir "$PWD"
```

Setting `HOME` is not enough: on macOS the JVM's `user.home` ignores it
(`docs/STORE_IDENTITY.md`).

## What CI checks

[`.github/workflows/reference-app.yml`](../../.github/workflows/reference-app.yml)
runs the whole route on a GitHub-hosted runner and an API 33 x86_64 emulator:

1. downloads the **public** release and checks its hash (`evidence/public-download.txt`);
2. `ci/prepare.sh` — bootstrap, compile, ingest with 0 RawCode, publish a signed
   v1, and build a production host that embeds this app's public key;
3. `ci/device.sh` — the development route on the tools bundle's generic host
   (E1–E10), a layout edit through the relay (D2, D3), then production: signed
   v1, repeated actions, signed v2, a bundle signed by another app's key
   rejected, and recovery (P2–P6).

What it asserts is in `EXPECTATIONS.md` (D1 and P1's key comparison in
`prepare.sh`, the rest in `device.sh`/`drive.py`). The run keeps the view dumps,
logcat, diffs, the installed APK and — from run 5 on — the published manifests
and device screenshots as an artifact. Screenshots are checked by `ci/shot.py`
and reported in `shots.results`; a blank one is recorded as BLANK and never
counted as a render. On this emulator every capture so far is BLANK (run
35967437120), so the device render has not been visually verified.

## Where the route is rough

Each of these was hit building this app; see
[`docs/REFERENCE_APP.md`](../../docs/REFERENCE_APP.md) for the full record.

* **Production needed the Keliver repository — fixed in tools 0.3.6/0.3.7.**
  The 0.3.5 bundle's `host/README.md` said to copy `sample/host-android`, which
  renders the sample's own widget schema, not keliver-material. CI now runs the
  0.3.7 zip's `bin/keliver-new-production-host.sh` (Android) and
  `bin/keliver-new-ios-host.sh` (iOS) in the app, and builds both hosts from
  Maven Central.
* **Publishing was not scaffolded.** `POST /publish` runs `publishTask`, whose
  default is Keliver's own `:portal-published-guest:…`, so on a scaffolded app it
  failed, and nothing signed. This app used to carry both settings and a
  hand-written signing block in its overlay. CI now runs the 0.3.7 zip's
  `bin/keliver-new-publish-target.sh` instead, whose block is the U31-fixed
  one.
* **The device scaffolder wires one screen.** With two screens it asks for
  `--screen`/`--presenter`, and a presenter that takes arguments means
  hand-editing `device/Main.kt`.
* **`keliver-portal` runs `editor/`'s Gradle build on every start.** The first
  build took 5 minutes here on a fresh Gradle home; later starts are an
  up-to-date check. CI never uses the editor, so it passes `--no-editor-build`.
