# Real-presenter preview — the complete adopter route

**An integration check.** Not evidence about semantic agents, and not a
correctness oracle. It establishes what an adopter must do to preview their
real presenters, and what that preview can and cannot be trusted for.

Package: `keliver-portal-tools-0.3.3-local.zip`,
sha256 `e15fa0c839e31e0456877aa439958474b81412c36e24605ebc37252ed3c254ed`
(locally built candidate; not released).

## The gap this closed

`keliver-new-editor.sh` scaffolds an editor whose `screens` map is **entirely
commented out**. The guide previously said "scaffold the app's own editor",
which implied that was sufficient. It is not: an unfilled editor previews
nothing, and the editor additionally opens in **mock mode** until ▶ Live is
pressed.

## Exact commands

A disposable app (`tally`) outside this checkout, packaged tools only.

```bash
$KP/keliver-init Tally
cd tally
# the app's OWN presenter, holding real state — see HomePresenter.kt
$KP/keliver-new-editor.sh Tally src/jsMain/kotlin/logic src/jsMain/kotlin/screens
# fill editor/src/wasmJsMain/kotlin/TallyPreview.kt — see TallyPreview.kt
cd editor && ./gradlew wasmJsBrowserDistribution && cd ..
$KP/keliver-portal .        # detects editor/ and serves it
# then press ▶ Live in the editor
```

`keliver-portal` confirmed it was serving the app's own editor:
`Preview → …/editor/build/dist/wasmJs/productionExecutable (your real presenters)`.

Maven resolution succeeded: `dev.keliver:portal-editor:0.3.3` and its
dependencies are **published on Central** (verified HTTP 200 for
`portal-editor`, `portal-render`, `portal-core`, `portal-document`), so this
route needs no local candidate artifacts.

## Observed preview behaviour

| stage | canvas | fidelity panel | state inspector |
|---|---|---|---|
| loaded, mock mode | `{tally}` | "Mock mode — press ▶ Live to run real logic" | — |
| after ▶ Live | **`0 tallied`** | "real presenter — tally (real presenter)" · "Full fidelity — no host capabilities required" | `tally = "0 tallied"`, `⚡ add` |
| after tapping "Add one" | **`1 tallied`** | unchanged | `tally = "1 tallied"` |

The action console logged `⚡ add → real presenter`. The value comes from
`HomePresenter` and the action is routed into the same bindings object — there
is no parallel mock implementation; `TallyPreview.kt` is nine lines of wiring.

## The same behaviour on the device

| | initial | tap 1 | tap 2 | tap 3 |
|---|---|---|---|---|
| device | `0 tallied` | `1 tallied` | `2 tallied` | `3 tallied` |
| preview ▶ Live | `0 tallied` | `1 tallied` | `1 tallied` | `1 tallied` |

Same app, same presenter, same screen. **The first transition matches; repeated
actions do not accumulate in the preview.** All three preview taps logged a
dispatch, so the actions arrive — the accumulated state is lost. Filed as
`U19`; not root-caused, because that means changing the live preview engine,
which was out of scope here. The guide now states the limitation instead of
implying the preview matches the app.

## Files

`HomePresenter.kt` (the app's real presenter), `home.kt` (the screen),
`TallyPreview.kt` (the editor registration an adopter must write).
