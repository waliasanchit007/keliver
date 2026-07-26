# Tri-platform layout evidence (K3)

**Date:** 2026-07-27
**Status:** delivered 2026-07-27
**Roadmap:** UI consistency K3
**Predecessor:** `2026-07-24-ui-consistency-plan.md`

## 0. Decision

K3 is a scripted, committed release-evidence gate around one canonical
`layout_evidence.kt` screen.

The relay recognizes that source into one `WidgetNode` tree. The editor renders
the tree through the web Compose/Yoga host; Android and iOS render the same tree
through their native Compose/Yoga hosts using the existing dev overlay. K1
already proves that the compiled composable and this interpreted tree have the
same schema semantics. K3 therefore covers the remaining boundary: host
measurement, placement, text, image, and modifier behavior.

K3 v1 does not compare pixels for equality. Native font rasterizers, system
insets, and device densities legitimately differ. It commits stable screenshots
and a machine-readable capture manifest so a reviewer can compare geometry and
detect release-to-release drift.

## 1. Canonical evidence screen

The screen is app-owned, portal-recognizable Kotlin with no bindings, actions,
presenter, clock, random data, or mutable state. It visibly labels every region
and exercises:

- layout-container `Constraint.Fill` and `Constraint.Wrap`;
- widget `fillWidth` versus wrapped content;
- nested full-width Column/Row layout;
- start, center, end, and space-between alignment;
- fixed and flexible spacing;
- ordered background, corner, padding, border, and fixed-size modifiers;
- single-line ellipsis and multi-line text wrapping; and
- fixed-height, fill-width image geometry.

Colors, strings, dimensions, and the image URL are literals. The image has an
explicit height so network timing cannot change the surrounding layout.

## 2. Evidence-only device seam

The normal device guest remains compiled-first. In dev mode only, when
`/devstate` names `layout_evidence`, the overlay renders its tree without the
normal “live overlay” banner. This avoids contaminating one platform with
capture-only chrome while preserving the banner for every normal editing
screen.

No production route or capability is added.

## 3. Web capture mode

`?evidence=1` keeps the normal editor initialization and relay/document
behavior, but CSS hides the editor chrome and presents only the device frame:

- fixed 402 × 874 CSS-pixel frame;
- light background;
- no frame radius, shadow, selection toggle, or overlays; and
- no Live presenter mode.

The normal editor remains byte-for-byte behaviorally unchanged without the
query flag.

## 4. Deterministic environment

`scripts/keliver-capture-layout-evidence.sh` owns the repeatable setup:

- JDK 17;
- repository root and expected relay ports;
- light theme, `en-US`, font scale 1.0, and disabled animations where the
  platform exposes those controls;
- web viewport 402 × 874 at device scale factor 1;
- Android `Pixel_9` AVD;
- iOS `iPhone 16 Pro` with the recorded runtime/UDID;
- active relay screen `default/layout_evidence`;
- fresh editor, guest, Android, and iOS builds;
- readiness checks before every capture; and
- restoration of the previously active portal screen.

The script starts only missing services and terminates only processes it owns.
It accepts environment overrides for emulator and simulator identities.

## 5. Committed artifact contract

Evidence lives under:

```text
docs/superpowers/evidence/k3/<keliver-version>/
  layout-evidence-web.png
  layout-evidence-android.png
  layout-evidence-ios.png
  manifest.json
```

The manifest records:

- format version and capture date;
- Keliver/widget version;
- source commit and evidence screen path;
- browser viewport and browser version;
- Android AVD/API/model, screenshot pixels, density, locale, theme, font scale;
- iOS model/runtime/UDID, screenshot pixels, locale, and theme;
- SHA-256 for each PNG; and
- the fixed evidence assumptions.

The script rejects absent, empty, non-PNG, or implausibly small captures.

## 6. Review contract

Reviewers compare labeled regions, not raw screenshot dimensions:

1. full-width bands reach the content edges;
2. wrapped bands stop at their content;
3. the three alignment markers preserve order and vertical alignment;
4. nested borders/padding retain the same containment order;
5. ellipsis stays single-line while wrapping text remains multi-line;
6. the image region has the same full-width/fixed-height geometry; and
7. no platform clips or overlaps the final marker.

Any unexplained geometry difference blocks the release until it is fixed or
documented as an intentional platform convention.

## 7. Acceptance gates

1. The new screen passes recognizer/export round-trip tests and contains zero
   `RawCode`.
2. Web evidence mode loads the real relay tree and captures only the frame.
3. Android installs/launches, reports `codeLoadSuccess`, and captures the
   labeled screen.
4. iOS builds/installs/launches, reports `codeLoadSuccess`, and captures the
   same labeled screen.
5. All three PNGs pass structural validation and visual inspection.
6. A second script run is operationally repeatable without manual source edits.
7. Existing K1, K2a, editor, guest, Android, and iOS build gates remain green.

## 8. Non-goals

- pixel-perfect equality across platform font and image implementations;
- automatic perceptual-diff thresholds in v1;
- replacing focused Paparazzi/layout unit tests;
- recording dynamic product screens;
- changing production routing; or
- hiding a known platform divergence behind crops or normalization.

## 9. Implementation result

K3 v1 is delivered by:

- `portal-app-lib/src/commonMain/kotlin/screens/layout_evidence.kt`, recognized
  into a zero-`RawCode` relay tree;
- evidence-only web and device-preview seams that leave normal editing and
  compiled-first device behavior unchanged;
- `scripts/keliver-capture-layout-evidence.sh`, which builds every target,
  controls the capture environment, requires successful guest loads, rejects
  post-load failures and wrong-app screenshots, validates PNG structure and K3
  color sentinels, writes the manifest, and restores the previous active screen;
  and
- committed evidence under
  `docs/superpowers/evidence/k3/0.3.1-SNAPSHOT/`.

The first complete runs caught three real boundary problems before evidence
could be accepted:

1. a nested scroll container received unbounded height in the native overlay;
2. Wasm evidence parameters were read too early during module initialization;
3. the iOS host rendered under the Dynamic Island because its root did not
   consume safe-drawing insets.

They also caught two capture/fixture false positives: GPU-disabled headless
Chrome produced a blank Skia canvas, and cross-axis stretch erased the intended
wrap case. The final runner passes only after all five are corrected.

Human review of the final web, Pixel 9, and iPhone 16 Pro captures confirms all
seven review-contract points. Font metrics and system chrome differ as expected;
the labeled fill/wrap, alignment, nesting/modifier, text, image-geometry, and
final-marker relationships agree.
