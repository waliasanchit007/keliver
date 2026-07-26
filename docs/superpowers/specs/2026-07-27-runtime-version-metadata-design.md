# Runtime-version metadata handshake

**Status:** delivered and live-verified, 2026-07-27.
**Roadmap:** UI-consistency K4.

## 1. Problem

The editor can be built against a different Keliver version from the app/device
runtime it is meant to preview. Composite substitution made this concrete:
the editor rendered `0.3.1-SNAPSHOT` while the consumer app targeted `0.3.0`,
and the fidelity panel reported no version evidence.

Gradle-file parsing is not an acceptable source of truth. Versions may come
from catalogs, BOMs, dependency constraints, convention plugins, or composite
substitution. The editor's resolved dependency graph also cannot represent the
consumer runtime target: substitution can deliberately make those two values
different.

## 2. Contract

K4 adds an explicit two-sided handshake.

### Editor side

The running editor reads:

- its Keliver SemVer from `guestRedwoodVersion`, which is generated from
  `project.version` into the actual editor binary; and
- its widget/schema compatibility version from the constant passed to
  `ProtocolRedwoodComposition`.

Those two values describe what is really rendering the browser preview.

### App/runtime side

The app repo declares the runtime it targets in `keliver.portal.json`:

```json
{
  "appRuntime": {
    "keliverVersion": "0.3.1-SNAPSHOT",
    "widgetVersion": 1
  }
}
```

`PortalConfig` validates that the version is non-blank and the widget version
is positive. The relay exposes the declaration unchanged through
`GET /runtime-metadata`. No Gradle parsing or dependency-resolution heuristic
is permitted.

The declaration is repo-wide because the current portal config and device
runtime are repo-wide. A future multi-runtime repo can version this wire shape
instead of overloading project or screen names now.

## 3. Comparison semantics

The fidelity panel reports all four values:

- editor Keliver version;
- app Keliver version;
- editor widget version; and
- app widget version.

Its compatibility result is:

1. **Match** when both pairs are equal.
2. **Keliver skew** when widget versions match but SemVer differs. The preview
   may differ from the device, so the panel warns without calling the protocol
   incompatible.
3. **Widget mismatch** when widget versions differ. This is the stronger
   compatibility warning regardless of SemVer.
4. **Undeclared** when the app omits metadata. The panel names the missing
   `appRuntime` declaration; unknown never renders as a match.

This status is independent of host-capability fidelity. A preview may have all
capability implementations and still have runtime version skew.

## 4. Editor UX

Runtime compatibility is a compact block inside the existing Preview fidelity
panel. It uses the editor's established status colors and text hierarchy:

- one concise compatibility line;
- one editor-version row;
- one app-runtime row; and
- a remediation sentence only for skew, mismatch, or missing metadata.

It is visible in mock and Live modes because version skew exists before a
presenter starts. It adds no top-bar chip, modal, decorative container, or
animation.

## 5. Compatibility and rollout

- `appRuntime` is nullable so older consumer configs still load.
- The endpoint always returns a stable object with nullable `appRuntime`.
- Missing metadata is visible but does not block editing or publishing in K4.
- The new `PortalConfig` field is source-compatible through its default.
- The editor comparison/parser stays internal; no new published editor API is
  required.
- Published bundle metadata may reuse the declared runtime fields later, but
  K4 does not claim that a particular device has loaded a particular bundle.

## 6. Verification

Mechanical gates:

1. relay tests pin config parsing, validation, absent metadata, and endpoint
   JSON;
2. editor tests pin match, SemVer skew, widget mismatch, undeclared metadata,
   and wire parsing;
3. `:portal-relay:test`, `:portal-editor:wasmJsBrowserTest`,
   `:portal-editor:apiCheck`, and the dogfood Wasm distribution pass; and
4. browser verification observes a match against this repo's declaration,
   then a controlled mismatch against a temporary relay config, without
   changing the committed app target.

The controlled mismatch is test configuration only. The repository finishes
with its truthful runtime declaration and a clean verification process set.

## 7. Delivery evidence

The contract and implementation landed in two bounded commits:

- `8dd942b1a` — explicit handshake design; and
- `553d32ab6` — relay metadata, editor comparison/UI, tests, and the dogfood
  runtime declaration.

`portal-relay:test`, six editor Wasm browser tests, editor API check, and the
fresh dogfood development distribution passed. Live browser verification
observed an exact `0.3.1-SNAPSHOT`/widgets-v1 match in mock and Live modes. A
separate temporary relay declared app version `0.3.0` and produced the expected
Keliver-skew warning with both values visible.

The Android and iOS build gates also passed. Android reported
`codeLoadSuccess modules=45`; Pixel 9 and iPhone 16 Pro simulator captures
visibly rendered the Field Notes guest, closing the native regression pass.
