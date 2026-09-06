# Feature workflow measurement — API-backed list (2026-09-06)

An article-feed feature with loading / populated / empty / error / retry,
built through the released adopter setup (`keliver-init`, 0.3.3 from Maven
Central, no Keliver checkout on the dependency path).

**Self-run. Measures the workflow; establishes nothing about newcomer
usability or demand.**

## Verified behaviour

Two test classes, 7 cases, all executed in ChromeHeadless.

`FeedRepositoryTest` (4) exercises the repository alone. **It cannot prove the
feature works** — it calls `load()` directly and would pass even if `retry()`
were unreachable from the UI. Kept as a unit-level check, not as evidence of
the feature.

`FeedScreenBehaviourTest` (3) drives the **real presenter through the real
screen** and asserts on rendered widget values:

| case | what it establishes |
|---|---|
| `loadingThenPopulated` | `Loading…` is observed *before* the response, then `2 articles` with rows `[Alpha, Beta]` |
| `loadingThenEmpty` | resolves to `No articles yet`, zero rows |
| `errorThenRetryButtonRecoversThroughTheUi` | error shows `Couldn't load articles`; **the rendered Retry button's `onClick` is invoked**; a `Loading…` frame is observed while the second request is in flight; recovers to `1 articles` / `[Recovered]`; `provider.calls == 2` |

The loading interval is observable because the fixture provider is **gated** —
it suspends on a `CompletableDeferred` until released, so the in-flight state
is a real frame rather than something coalesced away.

Responses are deterministic fixtures, not a live service.

### Something the frame-level assertions caught

Tapping Retry does **not** emit a `Loading…` frame if the response returns
immediately: the observed sequence was `[Couldn't load articles, 1 articles]`.
The intermediate state exists only when the request is actually in flight.
That is correct behaviour, but it means a fast endpoint gives no retry
feedback — and it is invisible to any test that does not look at frames.

## Platforms

- **Browser:** rendered in the portal editor; states driven through binding
  mocks. Screen ingests with **0 RawCode** (the D14 bar).
- **Android emulator:** `codeLoadSuccess modules=45`; after the overlay
  engaged, the device rendered *this feature's* screen — see
  `device-android-feed.png` and `device-accessibility-text.txt`.
  **Note:** that capture shows `{statusLine}` and mock rows, because the
  device is displaying the portal's interpreter overlay with preview mocks,
  not the presenter's live data. It is evidence of *rendering*, not of the
  API-backed feature working. The behavioural proof is the test table above.
- **iOS: UNTESTED.** `xcode-select` is not pointed at a full Xcode; the fix
  needs the machine owner's password.

## Measured timings — from the marks, not from memory

`timings.csv` is raw; `timing-analysis.txt` is the computed arithmetic.

**Total span, first mark to device render: 19m28s.**

| interval | elapsed |
|---|---|
| scaffold → implement start | 3m54s |
| implement (repo + presenter + screen) | 1m05s |
| build attempts (2 failures, then green) | 45s |
| yarn/TLS trap + tests green | 1m02s |
| browser verification | 2m13s |
| device: build + install | 6m04s |
| device: diagnose overlay + render | 2m42s |
| **device phase total** | **8m46s** |

An earlier version of this note claimed "~35 minutes overall, ~25 on
device/environment". Both were wrong — retrospective estimates rather than
arithmetic on the marks. The real figures are above.

**Unmeasured intervals:** the 3m54s "scaffold → implement start" gap includes
reading `SCREEN_ARCHITECTURE.md` for the HTTP recipe and inspecting the
`keliver-http` API, which were not separately marked. The presenter-through-
screen test work (three iterations to get the retry assertion right) came
after the last mark and is not in the span at all.

## Friction

`friction.md`, F1–F8. F1/F2 (serialization plugin missing from the scaffold
*and* from `pluginManagement`) and F4 (no device target in the scaffold) are
what an adopter hits first.
