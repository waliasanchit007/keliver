# U19 reconciliation — what the host clock actually does

**Result.** The harness was wrong, not production. The editor's host delivers
**60 frames per second, continuously and unconditionally**, so a guest-only
invalidation has never needed anything to wake the host. The failures that were
reported as proof of a production scheduling defect were the harness withholding
frames production delivers. U19's original browser observation is
**unresolved**, and neither wake change has evidence supporting it.

Timeboxed investigation, 2026-09-11. No production code was changed while
investigating; the wake mechanisms were removed afterwards in `b4102945f`, once
the measurements below were in.

---

## 1. The host frame-clock contract, from the pinned sources

Pinned: Kotlin 2.2.0, Compose Multiplatform **1.8.2** (`gradle/libs.versions.toml`).
Read from the published sources jars for that exact version
(`org.jetbrains.compose.runtime:runtime-wasm-js:1.8.2`,
`org.jetbrains.compose.ui:ui-wasm-js:1.8.2`), not from memory.

`EditorShell` parks a coroutine in `withFrameNanos` inside the host composition:

```kotlin
while (true) {
  withFrameNanos { nanos -> guestClock.sendFrame(nanos) }
  guestAdapter.emitChanges()
}
```

That waiter drives everything:

1. **Which clock it resolves to.** `Recomposer.kt:295`
   `effectCoroutineContext + broadcastFrameClock + effectJob` — a `LaunchedEffect`'s
   `withFrameNanos` uses the host **Recomposer's own** `broadcastFrameClock`.
2. **It counts as scheduling work.** `hasSchedulingWork` and `hasFrameWorkLocked`
   both include `hasBroadcastFrameClockAwaitersLocked`
   (`= !frameClockPaused && broadcastFrameClock.hasAwaiters`).
   `recordComposerModifications()` returns `hasFrameWorkLocked` when there are no
   snapshot invalidations, so the loop does **not** `continue` past the frame.
3. **So the recomposer asks for a frame every frame.** `runRecomposeAndApplyChanges`
   (`Recomposer.kt:566`): `awaitWorkAvailable()` returns immediately, then
   `parentFrameClock.withFrameNanos { … broadcastFrameClock.sendFrame(frameTime) … }`.
4. **And the parent clock is a `requestAnimationFrame`.** `BaseComposeScene.skiko.kt:70`

   ```kotlin
   private val frameClock = BroadcastFrameClock(onNewAwaiters = ::updateInvalidations)
   ```

   `updateInvalidations` calls `invalidate()`, which `ComposeWindow.web.kt:269`
   wires to `skiaLayer::needRedraw`; the redraw calls
   `scene.render(canvas, nanoTime)`, which calls `frameClock.sendFrame(nanoTime)`
   (`BaseComposeScene.skiko.kt:174`). The loop closes and repeats forever.

Nothing in the web host ever pauses that clock: `pauseCompositionFrameClock` is
never called from `ui-wasm-js` or `ui-skiko`, and there is no `visibilitychange`
handling. The only thing that stops frames is the browser not firing
`requestAnimationFrame` at all, and in that state nothing renders anyway.

**Consequence.** The premise "an idle host produces no frames, so a guest-only
invalidation stalls" is false for this editor. The pump the editor itself starts
is what keeps the host asking, and it never stops.

Note the shape of `BaseComposeScene`'s own clock: CMP uses exactly the
`BroadcastFrameClock(onNewAwaiters = …)` idiom that the part-2 patch adds. That
makes the patch idiomatic; it does not make it necessary, because the frame it
would request is already being requested.

## 2. Production instrumentation — measured, not inferred

An identical diagnostic (`FrameProbe.kt` plus three call sites: the pump, a
`SideEffect` in the `ComposeViewport` content, and `LiveEngine.applyValues`) was
applied to three source states and published to **three separate disposable
repos**. No real Maven cache was touched — there is no `~/.m2` on this machine —
and no published coordinate was overwritten outside those disposable repos.

| arm | source | klib sha256 (16) | app wasm served |
|---|---|---|---|
| **A** | `v0.3.3` (`7aac126ea`) — no wake at all, the published code | `5d9f9975643ea8fd` | `da1de314574001d62f00.wasm` |
| **B** | `6027fbe23` — part 1, dispatch-site wake | `d9db7bc4ff525480` | `e84526f169ea4c536f87.wasm` |
| **C** | `f4d6e558f` — part 2, guest-clock wake | `07f493e5c882cf0e` | `e485e34c500a8891155c.wasm` |

Same app, same presenter, same driver, same browser build. Each arm served on its
**own port** (8101/8102/8103, so distinct origins), each driven with a **fresh
Chrome profile**. Per arm the driver recorded `navigator.serviceWorker
.getRegistrations()` → **0** and `caches.keys()` → **[]**, the resource entries
actually loaded, and `typeof globalThis.__probe` → `object`, which is what proves
each arm ran its own klib rather than Central's (Central's has no probe).

### Host frames while nothing is happening

| arm | before Live | Live on, presenter waiting | after Stop |
|---|---|---|---|
| A | 181 frames / 3.005 s = **60.2/s**, hostContent 0 | 180 / 3.004 s = **59.9/s**, hostContent 0 | 180 = **59.9/s** |
| B | 180 = **60.0/s**, hostContent 0 | 180 = **59.9/s**, hostContent 0 | 180 = **59.9/s** |
| C | 180 = **59.9/s**, hostContent 0 | 180 = **59.9/s**, hostContent 0 | 181 = **60.3/s** |

The host runs at the display rate in every state, in every arm, with **zero**
recompositions of the host content. Frames are not produced by invalidating the
host; they are produced by the pump's own awaiter.

### Behaviour and cost per event

| arm | async completion (no interaction) | three canvas taps |
|---|---|---|
| A | 154 ms · hostFrames +9 · **hostContent +0** · presenterFrames +1 | 11/154/152 ms · **hostContent +0** each |
| B | 155 ms · hostFrames +9 · **hostContent +0** · presenterFrames +1 | 157/154/2 ms · **hostContent +1** each |
| C | 155 ms · hostFrames +10 · **hostContent +2** · presenterFrames +1 | 7/154/154 ms · **hostContent +1, +2, +2** |

Reading these:

- **Arm A — the published code — behaves correctly on every case**, including the
  asynchronous completion with no interaction. There is no production failure to
  fix.
- The ~155 ms is the fixture's own 50 ms gate poll plus the driver's 150 ms
  observation interval, not editor latency. It is the same in all three arms.
- Neither patch changes delivered frames or latency. Both add **host content
  recompositions that arm A does not perform**: one per action for B, one to two
  per action plus two per async completion for C. Small, but it is added work
  with no measured benefit, and it is the opposite of the earlier claim that
  "one extra frame costs nothing" — there is no extra frame, because the frame
  budget is already saturated; what is added is recomposition.
- Arm C's idle rows show `hostContent 0`, so its clock hook is genuinely
  edge-triggered: it costs nothing while the guest has no work.

## 3. The harness was suppressing frames

The old `LivePreviewAsyncTest` / `LivePreviewDispatchTest` harness ran the pump
as a plain coroutine awaiting the **parent** clock directly:

```kotlin
hostScope.launch { while (true) { withFrameNanos { nanos -> guestClock.sendFrame(nanos) } } }   // on hostClock
…
if (hostRecomposer.hasPendingWork) hostClock.sendFrame(frameTime++)
```

Because the pump never awaited the *recomposer's* clock, its awaiter never
appeared in `hasBroadcastFrameClockAwaiters`, so `hostRecomposer.hasPendingWork`
was false whenever no composition was invalidated and the driver refused to send
a frame. Production's pump is a `LaunchedEffect` inside the host composition, so
its awaiter does appear — and the recomposer requests a frame every frame.

The harness also described its stop condition ("three quiet turns of the event
loop … a real browser stops calling `requestAnimationFrame` here") as browser
behaviour. That was never verified and is wrong for this editor.

**Corrected** in `PreviewTestEditor.kt`: the pump is a `LaunchedEffect` in the
host composition, and `pump(frames)` delivers frames the way the browser does,
unconditionally.

Under the corrected harness, at `HEAD`:

```
LivePreviewAsyncTest: tests 3 failures 0
LivePreviewDispatchTest: tests 2 failures 0
```

and with **both** wake mechanisms removed (`newGuestFrameClock` returning a plain
`BroadcastFrameClock`, and `HostWakeSignal.wake()` deleted from
`LiveEngine.dispatch`):

```
NO-WAKE LivePreviewAsyncTest: tests 3 failures 0
NO-WAKE LivePreviewDispatchTest: tests 2 failures 0
```

So the corrected tests do not discriminate between the three arms — matching the
browser, where the three arms are also indistinguishable in behaviour.

**Withdrawn**: the claim that
`Expected <[0 tallied, 1 tallied, 2 tallied, 3 tallied]>, actual <[0 tallied, 0
tallied, 0 tallied, 0 tallied]>` and `"the completion must ask the host for a
frame; it asked for none"` demonstrated a production scheduling defect. They
demonstrated a harness that withheld frames.

The five tests are kept because what they assert is still worth asserting —
actions accumulate in the preview, async completions are projected, and the
intended reset boundaries reset — but they are integration tests of the
projection path, not evidence for a wake mechanism.

## 4. What is now known, and what is not

**Known.**
- The editor host delivers frames at the display rate whenever the page is
  running, driven by the editor's own pump, with no dependence on invalidation.
- Published `portal-editor:0.3.3` handles synchronous actions and asynchronous
  presenter completions correctly in this app.
- Neither wake change alters observable behaviour; both add recomposition.
- The editor consumes a frame every ~16 ms permanently, including before Live is
  pressed and after it is stopped. That is pre-existing, was not introduced by
  any of this, and is recorded here rather than acted on.

**Not known — U19's original observation is unresolved.**
The `0 → 1 → 1 → 1` reading that started this remains unexplained. It was taken
in a different app, through the Chrome extension MCP, and has not reproduced
since under any condition tried: two apps, headless and headed Chrome, polled and
completely quiet observation, published and candidate artifacts.

Status: **previously observed, currently unreproduced, cause unresolved.** The
symptom was recorded faithfully; nothing since explains it. Untested conditions
that would have to be excluded before any explanation could be offered include
the identity of the assets the observed page actually loaded, the page's
visibility during the observations, and that app's own dispatch wiring. None of
these has evidence for or against it, and none is claimed here as more likely
than another.

## 5. Recommendation

**Done — both wake changes were removed** in `b4102945f`, after this
investigation. Nothing supported them:
production never needed the frame they request, the browser cannot tell the arms
apart, and they add recomposition on every action. Calling them "hardening"
would be relabelling an unproven change, which is what the previous two blocks
did twice.

The revert removed the production parts of `1db77c27c` (part 1) and `3b3489805`
(part 2) — `HostWakeSignal.kt` including `newGuestFrameClock`, the wake call in
`LiveEngine.dispatch`, the signal read in `EditorShell`, and the two
`portal-editor.klib.api` entries the signal added — and kept
`PreviewTestEditor.kt` and the five tests, which stand on their own.
`git diff v0.3.3 -- portal-editor/src/wasmJsMain portal-editor/api` is now
**empty**.

### Reproducing this

`FrameProbe.kt` (the diagnostic) and `u19-measure.mjs` (the driver) are beside
this file, with the three per-arm outputs as `u19-arm-{A,B,C}-measure.json`.
