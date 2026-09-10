# U19 part 2 — asynchronous presenter updates

**Question.** The part-1 fix wakes the host from `LiveEngine.dispatch`. Presenter
state also changes with no action behind it. Does the preview follow?

**Answer.** In a harness where the host only frames when invalidated: no — the
completion asked for zero frames. Fixed. In a real browser: the published editor
already followed, so the browser did not reproduce the condition at all. Both
results are below, and the second one corrects a claim made in part 1.

---

## 1. The gap in the part-1 fix

`HostWakeSignal.wake()` was called from `LiveEngine.dispatch`. That covers state
written *inside* an action. It does not cover:

```kotlin
LaunchedEffect(Unit) {
  val v = repository.load()     // completes later, on its own
  status = v                    // invalidates the GUEST only
}
```

The guest composition runs on a `BroadcastFrameClock` ticked from host frames
(`EditorShell.kt`). A guest-only invalidation cannot ask the host for anything,
so under a host that frames only when invalidated the write never composes.

## 2. Fix

`portal-editor/src/wasmJsMain/kotlin/HostWakeSignal.kt`:

```kotlin
internal fun newGuestFrameClock(): BroadcastFrameClock = BroadcastFrameClock { HostWakeSignal.wake() }
```

`BroadcastFrameClock`'s `onNewAwaiters` fires when the clock goes from no
awaiters to one — precisely when the guest has work it cannot do without a
frame, whatever the source: an action, a coroutine, or a guest animation. It is
edge-triggered, so once the frame is delivered the awaiter is gone and an idle
editor stays idle. No polling, no unconditional animation loop.

`EditorShell.kt` calls `newGuestFrameClock()` where it used to construct the
clock inline. The dispatch-site wake is kept: it is the mechanism part 1
verified in a browser, and one extra frame per action costs nothing.

## 3. Regression — `LivePreviewAsyncTest.kt`

Drives the **host** as well as the guest, so the scheduling connection is under
test rather than assumed:

- a host `Recomposer` + composition whose content reads the same wake signal
  `runPortalEditor`'s content reads;
- the editor's frame pump, `withFrameNanos { guestClock.sendFrame(it) }`, as its
  own coroutine on the host clock;
- a driver that produces a host frame only while the host has pending work.

The driver never sends a guest frame directly, never calls `HostWakeSignal`, and
never pumps a fixed number of frames. Its one concession is a wake at **startup
only**, standing in for the host mounting its own content while Live starts;
every later frame has to be asked for by production code. Assertions read a
`Bind` resolved through `strB` — the accessor RenderNode uses — from inside the
guest composition.

| case | what it holds |
|---|---|
| `anAsyncCompletionReachesThePreviewWithNoInteraction` | Live shows `Loading`, the host goes idle, a gate opens, the value appears with no click, selection change or edit |
| `workStartedByAnActionLandsAfterTheDispatchRenderSettles` | an action starts work; its own render settles (the dispatch wake is spent, asserted as zero further frames); the response still lands |
| `pendingWorkFromAnEndedSessionIsNeverDelivered` | `stop()` and a screen switch each cancel work in flight; a restarted session is fresh and takes only its own response |

**Failing before the change** (same test file, only `newGuestFrameClock`'s body
reverted):

```
anAsyncCompletionReachesThePreviewWithNoInteraction
    AssertionError: the completion must ask the host for a frame; it asked for none
workStartedByAnActionLandsAfterTheDispatchRenderSettles
    AssertionError: the action's own rendering. Expected <loading>, actual <idle>
pendingWorkFromAnEndedSessionIsNeverDelivered
    AssertionError: the presenter's own async start reached the preview. Expected <loading>, actual <idle>
```

**Passing after**: `LivePreviewAsyncTest tests 3 failures 0`, alongside
`LivePreviewDispatchTest 2`, `PreviewHttpFidelityTest 3`, `RuntimeMetadataTest 6`;
`apiCheck` clean (the seam is `internal` and adds nothing to the klib dump).

One fixture bug was found and fixed on the way: sharing one buffered `Channel`
across sessions handed session 1's queued message to session 2. That was the
queue, not the editor; each session now gets its own channel.

## 4. External verification — a disposable app, identifiable artifacts

App `ledger`, scaffolded outside the checkout with `keliver-init` +
`keliver-new-editor.sh`, served by `keliver-portal` (which reported
`Preview → …/ledger/editor/build/dist/wasmJs/productionExecutable (your real
presenters)` and `real presenter — ledger` in the fidelity panel). Driven over
CDP with a raw WebSocket client, the same technique as
`scripts/keliver-capture-layout-evidence.sh`; the Chrome extension MCP was not
available this session.

The presenter's async work waits on a `localStorage` key the harness sets, so
the moment of completion is chosen, not timed:

```kotlin
private suspend fun awaitRelease(key: String) {
  while (localStorage.getItem(key) == null) delay(50)
}
```

**Isolation.** `keliver_make_run_dir` for the run directory,
`JAVA_TOOL_OPTIONS=-Duser.home=<run>/home`, and
`keliver_require_isolated_store` before anything started:
`store=<run>/home/.keliver-portal/apps/ledger-8bf88219`. Nothing outside the run
directory was written. (`GRADLE_USER_HOME` must stay pointed at the real one, or
the isolated home loses this machine's TLS truststore settings and every Gradle
download fails PKIX.)

**Candidate resolution.** Published into a disposable repo and listed first in
the app editor's `settings.gradle.kts`, so it shadows Central for that one
coordinate:

```
./gradlew :portal-editor:publishToMavenLocal -PkeliverVersion=0.3.3 \
    -DRELEASE_SIGNING_ENABLED=false -Dmaven.repo.local=<run>/m2
maven(url = uri("file://<run>/m2"))
```

| | klib sha256 (16) | app wasm produced |
|---|---|---|
| candidate | `f507c41ca61c3f0b` | `49428b8dae89026bdcf2.wasm` · `4b972087c1ec23b5` |
| published (Central) | `3d4da489cfe6c47e` | `0a6d7167e222132265b6.wasm` · `33710124a18f5f34` |

Different coordinate contents, different outputs; the candidate build was
byte-identical when repeated. Central's copy was fetched over HTTP for the
comparison, and the Gradle cache held Central's klib after the published build,
confirming which arm was which.

There is **no `~/.m2` on this machine at all** (neither `$HOME/.m2` nor the JVM's
`user.home`, which agree here). Part 1's note that the real `~/.m2` kept its
`portal-editor/0.3.3` is therefore not true of the machine's state today; this
session created no `~/.m2` and deleted nothing.

### Candidate build — every case passes

| step | interaction | canvas | State Inspector |
|---|---|---|---|
| ▶ Live | — | `Loading…` / `0 entries` | `status = "Loading…"`, `tally = "0 entries"` |
| held 4 s | none | unchanged | one distinct state for 4 s |
| gate opened | **none** | `loaded 42 entries` | `status = "loaded 42 entries"` (+204 ms) |
| canvas tap ×3 | mouse events on "Add one" | `1` → `2` → `3 entries` | matching |
| tap "Refresh" | mouse event | `refreshing…` | matching |
| held 4 s | none | unchanged | one distinct state for 4 s |
| gate opened | **none** | `refreshed 1` | `status = "refreshed 1"` (+205 ms) |
| ■ Stop | click | live values cleared | `—` |
| ▶ Live | click | `Loading…` / `0 entries` | fresh; no `loaded 42 entries`, no `refreshed 1` |

Reproduced twice from separate builds. The State Inspector settled to the
current value on its own each time — the reads that observed it were passive.

### Published 0.3.3 — also passes, which is the finding

The identical scenario against Central's `portal-editor:0.3.3`, which contains
**neither** part of the fix:

```
[published] async completion (no interaction): status="loaded 42 entries" (+206ms)
[published] canvas tap 1/2/3: tally="1 entries" / "2 entries" / "3 entries"
[published] delayed completion (no interaction): status="refreshed 1" (+203ms)
[published] after Stop: {"status":null,"tally":null}   restarted: "Loading…" / "0 entries"
```

Suspecting the 200 ms DOM polling was itself waking the host, the quietest
possible run was repeated: click Live, open the gate, then **six seconds with no
evaluate and no screenshot**, then one read.

| build | after Live | 6 s after the gate, first read |
|---|---|---|
| published, headless | `Loading…` | `loaded 42 entries` |
| published, **headed** Chrome window | `Loading…` | `loaded 42 entries` |
| candidate, headless | `Loading…` | `loaded 42 entries` |

So it is not headless-vs-headed, and it is not an artifact of polling. In this
app, on this machine, **published 0.3.3 does not exhibit U19** — neither the
async case nor part 1's synchronous `0 → 1 → 1 → 1`.

## 5. What this does and does not establish

**Does.** The guest→host coupling had a real hole: with a host that frames only
when invalidated, an asynchronous presenter update requests no frame and the
preview stalls. The fix closes it at the lifecycle boundary that owns the
question, is edge-triggered, and preserves the intended resets.

**Does not.** It does not establish that a browser leaves the host idle. Every
attempt to reproduce U19's browser symptom against published 0.3.3 in this app
failed. Part 1's "still true in published 0.3.3" is withdrawn as stated, and
part 1's original `0 → 1 → 1 → 1` reading is currently unexplained — it was
observed through a different driver (the Chrome extension MCP) in a different
app. A likely candidate worth ruling out before trusting either reading is the
documented editor cache trap (a constant-named loader keeping a stale wasm).

Three passing fixtures and one app are not a runtime-correctness guarantee.
macOS only, Chrome 152 only, one presenter, no device run this round.

**Observed and not fixed.** The right-hand BINDINGS panel's mock-value inputs
show the value they were last filled with and do not follow later live updates —
after the delayed completion the panel read `loaded 42 entries` while the State
Inspector correctly read `refreshed 1`. The State Inspector is the live panel;
the BINDINGS inputs are mock entry fields.
