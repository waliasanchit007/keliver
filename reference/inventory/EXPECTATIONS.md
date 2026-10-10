# Inventory reference app — behavioural expectations

Written **before** any device run, and committed before the evidence that checks
them. `ci/drive.py` asserts exactly these, on the view hierarchy the device
reports (`uiautomator dump`). A screenshot is not the evidence: on the headless
CI emulator `screencap` returns black frames.

The data is `logic/InventoryState.kt`'s `SeedInventory`: eight items, fixed, with
no backend and no credentials. Low stock means `quantity <= reorderAt`, so at
launch two items are low: *Filter beans* (4, reorder at 5) and *Whole milk*
(9, reorder at 10).

## The list screen (`screens/inventory.kt`)

| id | action | expected on screen |
|---|---|---|
| E1 | launch | title `Inventory`; `8 items · 2 low on stock`; a row `Espresso beans, dark roast 1kg` with `BEAN-001 · 12 on hand` |
| E2 | type `bean` in the search field | `2 of 8 items match "bean"`; rows *Espresso beans* and *Filter beans*; **no** *Oat milk* row |
| E3 | replace the query with `zzz` | `0 of 8 items match "zzz"`; the empty state `No items match "zzz".` and a `Clear search` button; **no** item rows |
| E4 | tap `Clear search` | `8 items · 2 low on stock` again; the empty state is gone |

## The item screen (`screens/item.kt`)

| id | action | expected on screen |
|---|---|---|
| E5 | tap the *Espresso beans* row | `BEAN-001 · Aisle 1`; `On hand: 12`; `No adjustments yet`; **no** low-stock warning |
| E6 | tap `Add 1` three times, reading the screen after **each** tap | `On hand: 13`, then `14`, then `15`; then `3 adjustments: +1, +1, +1 (net +3)` |
| E7 | tap `Remove 1` ten times, reading after each | `14` … `5`, one step per tap; the warning `Low stock — reorder at 5` is absent at 6 and present at 5 |
| E8 | tap `Receive 10` | `On hand: 15`; the warning is gone |
| E9 | tap `Back to inventory` | the list again; the *Espresso beans* row now reads `BEAN-001 · 15 on hand`; still `8 items · 2 low on stock` |
| E10 | open *Filter beans*, tap `Remove 1` five times | `On hand: 3`, `2`, `1`, `0`, then **still** `0`; the warning shows throughout; history `4 adjustments: -1, -1, -1, -1 (net -4)` — the fifth tap changes nothing and records nothing |

E6, E7 and E10 are the repeated-update checks: each tap is observed, not only
the final state.

## The layout edit (D14)

| id | action | expected |
|---|---|---|
| D1 | `/doc` for both screens | zero `RawCode` nodes |
| D2 | `SetProp` via the relay's `/ops`: title text `Inventory` → `Stockroom`, `fontSize` 28 → 30 | `screens/inventory.kt` changes on exactly those two lines; `Compiled_inventory.kt` appears; **every file under `logic/` is byte-identical** |
| D3 | rebuild and run | the device shows `Stockroom` and no longer shows `Inventory` as the title; E1–E4's behaviour is unchanged |

## Production OTA (app-owned disposable keys)

| id | action | expected |
|---|---|---|
| P1 | build the production host with this app's public key embedded | the APK carries `assets/portal_ed25519.pub`, equal to the store's `keys/ed25519.pub`; `BuildConfig.DEV_ONLY` is false |
| P2 | publish v1 (title `Inventory`), launch the host with `--es mode prod` | the host logs that it verifies manifests with `portal-ed25519`; v1 loads (`codeLoadSuccess`); `Inventory` on screen |
| P3 | in production, tap `Add 1` three times on *Espresso beans* | `13`, `14`, `15` — the presenter runs from the signed bundle |
| P4 | apply the D2 edit, publish v2, relaunch | `Stockroom` on screen: the second signed version changed what the user sees |
| P5 | a copy of the app with its OWN store (so its own key) publishes a bundle titled `Foreign build`; the host, data cleared, fetches it | `codeLoadFailed` naming the signature; `Foreign build` never appears |
| P6 | the original relay again, host relaunched | `Stockroom` loads again, still verified |

Verification is never disabled. The generic development host from the tools
bundle is used for the development route only; it refuses production by design
and is not rebuilt, patched or reconfigured here.
