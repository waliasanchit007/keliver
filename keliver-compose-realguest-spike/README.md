# Research spike: the real Compose Multiplatform artifacts in a Zipline guest

eff-guest, 2026-11-24. Not product code, never published, only in the build with
`-Pkeliver.realGuestSpike`.

Question: can a guest use the REAL `ui-unit`, `ui-geometry`, `ui-util`, `ui-graphics`, `ui-text`,
`ui`, `foundation-layout`, `foundation` and `animation-core` (CMP 1.12.2, Kotlin/JS) instead of the
mirrors in `keliver-compose-values` (docs/COMPOSE_GENERATOR_DESIGN.md §4, §6, §17 row 5)? And at
what bundle size, load time and per-op cost, in the fork's QuickJS?

- `variant.gradle`, `variants/<v>/`: one Zipline guest per variant. They are `base`; then cumulative
  `unit`, `graphics`, `text`, `ui` and `foundation`; then `skia`, `anim`, `all` and `mirror`.
  `variant.gradle` lists the case sets and artifacts of each. When skiko-kjs survives DCE with its
  `./skiko.mjs` import, a throwing stub replaces that import, so that load and first use can be
  observed.
- `src/<set>/kotlin`: the cases, run through `globalThis.realGuest(cmd)` (`src/jsShared`).
- `harness/`: `RealGuestQuickJsTest` loads each production bundle into QuickJS and runs every case and
  bench. It writes `<out>/<variant>.json` and asserts the finding: every case passes except `skia.*`,
  which fail at first use.

Run: `./gradlew -Pkeliver.realGuestSpike -PrealGuestOut=<dir> :realguest-harness:test`
(add `-PrealGuestVariants=unit,anim` for a subset).
