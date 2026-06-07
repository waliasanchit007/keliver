# API stability

Where Keliver's public API stands, and what to expect before 1.0.

## TL;DR

Keliver is **pre-1.0**. **Pin an exact version** and expect breaking API changes
across `0.x`. Every change to the public surface is intentional and reviewable
(it's tracked on every build), but we do **not** promise source/binary
compatibility until 1.0.

## What's tracked, and how

The public API of every published `keliver-*` module is checked on each build by
the Kotlin [binary-compatibility-validator]. The baselines live in each module's
`api/` directory:

- `api/<module>.api` — the JVM / Android ABI.
- `api/<module>.klib.api` — the Kotlin/Native + JS (klib) ABI.

`./gradlew apiCheck` fails if the surface drifts from its baseline, so **no API
change ships by accident**: a maintainer regenerates the baseline with
`./gradlew apiDump` as part of an intentional change, and the diff is part of
review. Codegen-internal and Yoga-internal APIs are marked non-public
(`dev.keliver.RedwoodCodegenApi`, `dev.keliver.yoga.RedwoodYogaApi`) and excluded.

## The path to 1.0

- **Lineage.** Keliver is a fork of Cash App's (now-discontinued) Redwood. The
  surface is large (~9.6k declarations) and largely inherited. The redundant
  `redwood-*` baselines left over from the Konduit→Keliver rebrand have been
  removed; the `keliver-*` baselines are authoritative.
- **Before 1.0 we will:** (1) audit and **narrow** the surface — internalize what
  adopters don't need; (2) mark genuinely-unstable APIs with `@RequiresOptIn`
  opt-in markers; (3) commit to source/binary compatibility for the locked
  surface.
- **Until then:** pin `dev.keliver:*` to an exact version, read the CHANGELOG
  before bumping, and prefer the facade artifacts **`keliver-host`** /
  **`keliver-guest`** — those are the intended adopter entry points and the most
  stable.

## Stable in practice

The surface you actually use day-to-day — the schema annotations, the codegen'd
widget/composable APIs, `keliver-http`, `keliver-treehouse(-guest)`, and the
`keliver-host` / `keliver-guest` facades — has held steady across the `0.1.x`
line and is exercised by the [sample](../sample) and a real consumer app. The
churn risk lives in the deeper, inherited modules, not the everyday API.

[binary-compatibility-validator]: https://github.com/Kotlin/binary-compatibility-validator
