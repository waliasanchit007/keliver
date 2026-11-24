# Google's Compose samples as guest code (research spike, stone U1, 2026-11-25)

Not built by default, never published, not in CI. Findings: the efficiency review's `eff-samples.md`
(maintainer's scratch); the tables are in `results/`.

**Input:** AndroidX's `@Sampled` functions at the commits that match the pinned Compose:

| Pinned (libs.versions.toml) | AndroidX version (Gradle module metadata) | Release commit (release notes) |
|---|---|---|
| CMP material3 1.9.0 | material3 1.4.0 | `5113b82a4ed6416d6344efa50e41db8cb65928ff` |
| CMP 1.12.2 | foundation (and animation, ui) 1.12.1 | `5e0747401bf56b243e3b5141ae080dc9bc61d29c` |

Fetch each commit into `<dir>/m3` and `<dir>/fdn` with a shallow, blob-filtered, sparse git fetch of
github.com/androidx/androidx (paths: `compose/material3/material3/samples`,
`compose/foundation/foundation/samples`, `compose/foundation/foundation-layout/samples`,
`compose/animation/animation/samples`, `compose/animation/animation-core/samples`,
`compose/ui/ui/samples`). The sources are Apache-2.0 (The Android Open Source Project) and are not
committed here.

**Run:**

```
./gradlew -Pkeliver.googleSamples=<dir> \
  :keliver-compose-google-samples-spike:compileKotlinJvm \
  :keliver-compose-google-samples-spike:compileKotlinJs --continue > compile.log 2>&1
python3 -I analyze.py compile.log . build/report
python3 -I classify.py build/report
```

Both compiles fail by design: `jvm` compiles the original sources against real Compose
Multiplatform (a failure there is Android-only API), `js` compiles the same-source rewrite against
the generated Gate A/B guest API. `analyze.py` attributes every diagnostic to its sample;
`classify.py` puts each sample in one class (compiles, a named MS4/#312 PR, experimental, no PR,
never, Android-only) and ranks the Compose names the samples use.

`src/jsMain/…/annotations/` holds stand-ins for `@Preview` and Compose's opt-in markers (no
behaviour), so that the classification shows real API gaps rather than missing annotations.
