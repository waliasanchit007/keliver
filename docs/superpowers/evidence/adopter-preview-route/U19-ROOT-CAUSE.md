# U19 — root cause, fix, and verification

**Corrected finding.** The earlier record said preview state was "discarded
between dispatches". That was wrong. State was never lost; the preview stopped
re-rendering.

## Failing boundary

| leg | artifact | result |
|---|---|---|
| published | `dev.keliver:portal-editor:0.3.3` from Maven Central (no `mavenLocal` in the app's editor build) | **fails** |
| source candidate | working tree at `30b698032` | **same code** — `git diff v0.3.3 HEAD -- portal-editor/src portal-render/src` is two added test files only |

So the defect was present in both, and the browser observation was of published
0.3.3 behaviour.

## What distinguished the explanations

A temporary instrumented `ScreenPreview` logged presenter identity, state
before/after each action, and each frame, through the editor's own action
console:

```
compose: presenter#1 count=0        <- exactly ONE composition for the session
frame#1 tally=0 tallied
route: frame#1 action=add           <- canvas tap
dispatch: presenter#1 count 0 -> 1  <- state advanced
route: frame#1 action=add           <- State Inspector ⚡, same frame + presenter
dispatch: presenter#1 count 1 -> 2  <- advanced again
```

State inspector read `0 tallied` throughout. Then an unrelated document edit
(`apply_ops` while the editor was open) made the canvas jump to `2 tallied`.

| competing explanation | verdict |
|---|---|
| presenter/composition reset | **ruled out** — one `compose:` line, one presenter id |
| stale callback | **ruled out** — both routes reached the live `presenter#1` and its current state |
| registration mistake | **ruled out** — dispatches arrived and mutated the right object |
| **stale displayed value** | **confirmed** — the value was correct underneath and appeared as soon as anything re-rendered |

## Root cause

`portal-editor/src/wasmJsMain/kotlin/EditorShell.kt` — the guest composition
runs on its own `BroadcastFrameClock`, ticked from the host:

```kotlin
while (true) {
  withFrameNanos { nanos -> guestClock.sendFrame(nanos) }
  guestAdapter.emitChanges()
}
```

The coupling is one-directional. A presenter write invalidates the **guest**
recomposer, which then awaits a frame. Frames only arrive when the **host**
schedules one, and an idle host schedules none. The guest had no way to ask.

## Fix

`portal-editor/src/wasmJsMain/kotlin/HostWakeSignal.kt` (new) — a state the
host composition reads. `LiveEngine.dispatch` bumps it after delivering the
action (`LiveEngine.kt`), and `EditorShell` reads it inside the `ComposeViewport`
content. A dispatch now invalidates the host, the host schedules a frame, the
loop ticks the guest clock, and the new frame is composed.

Both dispatch routes — canvas taps (`PreviewBindings.actionSink`) and the State
Inspector's ⚡ buttons — call `LiveEngine.dispatch`, so one change covers both.
No presenter state moved to a global, no counter-specific behaviour, no bypass
of the normal preview path.

## Failing before, passing after

`LivePreviewDispatchTest.threeActionsAdvanceThePreviewedValue` drives the real
`LivePresenterHost` + `LiveEngine.dispatch` + `PreviewBindings` path, with a
host modelled as the editor's — frames only when woken.

```
before: AssertionError: three actions must be visible in the preview, not just
        in the presenter.
        Expected <[0 tallied, 1 tallied, 2 tallied, 3 tallied]>,
        actual   <[0 tallied, 0 tallied, 0 tallied, 0 tallied]>
after:  tests="2" failures="0"
```

`intendedResetBoundariesStillReset` holds the boundaries the design intends:
state accumulates across actions, but a screen change, a persona change, and
`stop()`/restart each start fresh.

## Verified from a fresh external app

App `ledger`, scaffolded by the packaged `keliver-init` outside this checkout,
with its own stateful presenter (`LedgerPresenter.kt`) and the documented
editor registration (`LedgerPreview.kt`).

**How the candidate was resolved** — kept deliberately separate from
published-Central verification:

* `./gradlew :portal-editor:publishToMavenLocal -PkeliverVersion=0.3.3
  -DRELEASE_SIGNING_ENABLED=false -Dmaven.repo.local=<disposable>/m2`
* the app's `editor/settings.gradle.kts` lists
  `maven(url = uri("file://<disposable>/m2"))` **first**, so it shadows Central
  for that one coordinate; every other artifact still comes from Central at
  0.3.3
* same coordinate, different content — candidate klib `f7715d5ff6289a08…`
  vs published `8545138758df5dc4…`
* the developer's real `~/.m2` was not written to; `portal-editor/0.3.3` there
  still has its original mtime

**Browser, through actual preview UI actions:**

| | canvas | state inspector |
|---|---|---|
| Live pressed | `0 entries` | `summary = "0 entries"` |
| tap 1 | `1 entries` | `summary = "1 entries"` |
| tap 2 | `2 entries` | `summary = "2 entries"` |
| tap 3 | `3 entries` | `summary = "3 entries"` |

**Device, same unchanged presenter:** `0 entries → 1 → 2 → 3`.

## Limits

macOS only, one app, one presenter, Chrome. One passing fixture is not a
runtime-correctness guarantee — the preview runs the real presenter, but a
green preview does not establish that an app behaves correctly.

The DOM panels update one host frame behind the action, so reading them
immediately after a click can show the previous value; the canvas and the
inspector agree once a frame has run.
