# The iOS production host against a real signed bundle (2026-10-07, local)

**Set-up, all disposable, macOS, Xcode 26.4.1:**
- The **published** tools 0.3.6 zip (`fac98912…`, the verified release asset).
- The inventory reference app, recreated by hand from it: `keliver-init`,
  this repository's `reference/inventory/app` screens, logic and overlay files,
  then `keliver-new-device-target.sh` and **`keliver-new-publish-target.sh`
  from the bundle**.
- `keliver-portal --no-editor-build` with `user.home` in the run directory,
  checked by `keliver_require_isolated_store` first. The store and its keys
  were inside the run directory.
- `POST /publish`: `publish OK: bundle v1`, "signed with this app's
  portal-ed25519 key" (`publish-v1.txt`).
- The iOS host from `../proj` + `../app`, with this app's **public** key
  (`56ef1ef4…`) set in `HostConfig.kt`. It was built with `xcodebuild
  -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO` and run on a disposable
  iPhone 17 Pro simulator (iOS 26.4), created for the run and deleted
  afterwards. The simulator reaches the relay at `localhost:8077`.

**Results:**
| check | what happened | evidence |
|---|---|---|
| a signed v1 loads and renders | the key check, then `loading …/bundles/v1/manifest.zipline.json`, then `codeLoadSuccess modules=40`; the screen shows **Inventory**, the search field, "8 items · 2 low on stock" and all 8 rows | `v1-load.*` |
| offline start (relay stopped) | `bundle lookup failed`, then `lookup failed; starting from the cached bundle`, then `codeLoadSuccess modules=40` | `offline.*` |
| a host trusting another key | `codeLoadFailed: manifest signature for key portal-ed25519 did not verify!` | `foreign-key.*` |

All three screenshots are real frames (`shots.txt`).

**Also measured:** the published 0.3.6 relay created `keys/ed25519.priv` as
`-rw-------` on macOS (`key-modes.txt`). That is the U27 fix, in the
released bundle.

**Not shown:** no empty-URL load was *asserted* (the console shows none);
v1 → v2 wasn't run; this was a local run only, not CI; images, persistent
SQL and HostHttp weren't exercised. The list rows render content-width, as
REFERENCE_APP finding 7 recorded for the editor. Next: the scaffolder, then
CI (`DELIVERY_PLAN.md`).
