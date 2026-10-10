# W1 I1 — `keliver-new-ios-host.sh`, measured (2026-10-07, local, macOS)

**The scaffolder's own output, against a real signed bundle.** On the inventory
app from `../ios-host-i0/signed-run/`, which uses the published tools 0.3.6, an
isolated relay and v1 signed with key `56ef1ef4…`:
```
keliver-new-ios-host.sh --bundle-server http://localhost:8077 --bundle-id inventory.ioshost
```
- The scaffolder found the key through the app's store and checked it.
- `xcodebuild` built `InventoryHost.app`.
- On a disposable iPhone 17 Pro simulator (iOS 26.4, deleted afterwards):

| step | result | evidence |
|---|---|---|
| launch | v1 verified and loaded (`codeLoadSuccess modules=40`); the screen reads **Inventory** | `scaf-v1.*` |
| a title edit through `/ops`, then `POST /publish` | `publish OK: bundle v2`, signed | `publish.txt` |
| relaunch | `loading …/bundles/v2/…`, `codeLoadSuccess`; the screen reads **Stockroom**: an over-the-air update on iOS | `scaf-v2.*` |

The `*.ocr.txt` files are what macOS Vision reads off each screenshot
(`reference/inventory/ci/ocr.swift`). The iOS simulator has no view-hierarchy
dump, so this is how CI checks what a screen shows.

**`IosSqlHost` (real SQLite) — 5/5 tests** (`sqltest/`, JUnit XML kept). The
template's `IosSqlHost.kt`, with only its package changed, and the same
`sqlite3.def` cinterop, compiled for Kotlin/Native **macOS**: the same
libsqlite3 and Foundation APIs, with tests running natively. The tests cover:
- create, insert and select, `NULL` included;
- `UPDATE` and `DELETE` row counts;
- a failing batch rolling back;
- data surviving a reopen;
- bad SQL as an error, not a crash.

`portal-sql` has no macOS variant, so `sqltest/src/macosMain/kotlin/wire/`
copies its four wire types. To reproduce, copy the template file in with
`sed 's/^package @@PACKAGE@@/package sqltest/'`. The inventory guest doesn't
use SQL, so on the simulator the host only opened `portal-app.db`.

**Found on the way, now fixed in the template:**
- Kotlin/Native has no `platform.sqlite3`. The SDK header is bound through
  `src/nativeInterop/cinterop/sqlite3.def`.
- The self-test caught the scaffolder not copying that `.def`. The local build
  had only passed because the file had been copied in by hand.

**Self-test** (`scripts/keliver-new-ios-host-selftest.sh`): **38/0** without a
build, **43/0** with `--build`, on macOS. That covers:
- 19 refusals, each leaving the app byte-identical;
- a complete output, with the key, servers, package, bundle id and product
  name checked;
- an ATS exception for `localhost` only, and none for `https://`;
- no development team, the build phase, Maven Central only, no development
  path;
- `xcodebuild` for the simulator;
- `checkReleaseUrls` refusing `http://` and wired to both release links;
- a malformed key failing the build.

**Not yet:** CI (I2), a foreign key and the offline start with the
*scaffolded* output (shown with the I0 code, which is the same Kotlin), images
over the network, HostHttp, a device or a release build.
