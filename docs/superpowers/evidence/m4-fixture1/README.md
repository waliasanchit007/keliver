# M4 fixture 1 — label does not reflect state after a transition

**Status: QUALIFIED.** The defective screen fails an independent behavioural
check at runtime for the intended reason; the reference fix passes the same
check. Both demonstrated by execution, not source inspection.

## Requirement (stated independently of any implementation)

The screen shows a summary label. `addItem()` is a deterministic state
transition that moves the presenter's `summary` from `"0 items"` to
`"1 items"`. After that transition the rendered summary label MUST read
`"1 items"`.

## The check

`portal-render/src/wasmJsTest/.../M4LabelUpdateCheck.kt` — **evaluator
material, not a participant artifact.** It composes the real screen composable
against a state-backed bindings object in `KeliverMaterialTester`, snapshots
rendered widget values, performs the transition, and re-snapshots. This is a
runtime observation of the production compose path (the same path the K1
parity gate calls "device semantics"), not a preview mock.

Run: `./gradlew :portal-render:wasmJsTest` with the candidate screen installed
at `portal-parity-fixtures/kotlin/dev/keliver/portal/render/fixture/M4CounterScreen.kt`.

## Results

| screen | outcome |
|---|---|
| `defective/M4CounterScreen.kt` | **FAIL** — `REQUIREMENT: the summary label must reflect state after addItem(). Expected <[Basket, 1 items]>, actual <[Basket, 0 items]>.` |
| `reference-fix/M4CounterScreen.kt` | **PASS** — BUILD SUCCESSFUL, 9/9 tests |

The only difference between the two files is one line:
`text = "0 items"` versus `text = b.summary`.

## Two design notes

**The first assertion passes for the defect too.** The literal happens to equal
the zero state, so a single render — or a single screenshot — cannot separate
them. Only the post-transition assertion does. That is the property that makes
this defect worth testing.

**A first attempt failed for the wrong reason.** With the literal label nothing
depends on the changed state, so no recomposition is produced and
`awaitSnapshot()` timed out after 1s. A `TimeoutCancellationException` is an
ambiguous signal — it could equally be harness flakiness. The check now falls
back to the last observed frame when no new frame arrives, so the failure
reports the requirement instead. Causally the timeout *was* the defect; it just
did not say so.

## Isolation

Participants never receive this directory, the check, or the reference fix.
They receive a scaffolded app containing the defective screen with no case
labels, no defect commentary, and no pointer to this work.
