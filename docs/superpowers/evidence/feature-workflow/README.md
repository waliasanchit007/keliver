# Feature workflow measurement — API-backed list (2026-09-06)

A small article-feed feature with loading / populated / empty / error / retry
states, built through the released adopter setup (`keliver-init`, 0.3.3 from
Maven Central, no Keliver checkout on the dependency path).

**This is a self-run. It measures the workflow. It does not establish newcomer
usability or demand.**

## What was verified, and how

| state | verified by | evidence |
|---|---|---|
| populated | executed test + browser render | `test-results.txt`, browser screenshot |
| empty | executed test + browser render | `test-results.txt` |
| error (non-2xx raises) | executed test | `test-results.txt` |
| retry (2nd request issued, recovers) | executed test | `test-results.txt` |
| loading | presenter state only | **not independently asserted** |

Four `FeedRepositoryTest` cases ran in ChromeHeadless — 4 tests, 0 failures,
0 skipped — against **deterministic fixture providers**, not a live service.
`FixedProvider` returns a fixed status+body; `SequencedProvider` returns 500
then 200 so retry is observable. Nothing here says anything about a real API.

`loading` is the honest gap: it is a presenter state I did not assert
independently, because the fixture providers resolve immediately.

## Platforms

- **Browser:** rendered in the portal editor; populated and empty states driven
  through binding mocks. Screen ingests with **0 RawCode** (the D14 bar).
- **Android emulator:** `codeLoadSuccess modules=45`, and after the overlay
  engaged the device rendered *this feature's* screen — accessibility dump
  shows `Articles Feed`, `{statusLine}`, three rows, `Retry`, and the overlay
  banner. See `device-android-feed.png` and `device-accessibility-text.txt`.
- **iOS: UNTESTED.** `xcode-select` is not pointed at a full Xcode; the fix
  needs the machine owner's password.

## Measured workflow

`timings.csv` has the raw marks. Cold path, from an empty directory:

| phase | elapsed |
|---|---|
| scaffold | seconds |
| implement (repo + presenter + screen) | ~4 min |
| first build → green | ~2 min, across **two failed attempts** |
| test loop incl. one lost cycle to the TLS trap | ~1 min |
| browser verification | ~2 min |
| device: build + install + diagnose overlay | **~25 min**, dominated by a 6-min Android build and a wrong-looking screen |

**The largest source of delay was not writing the feature.** It was three
environment/scaffold problems in a row: the serialization plugin missing from
the scaffold (twice — build.gradle *and* settings.gradle pluginManagement), the
yarn/TLS masquerade, and the device overlay silently showing the wrong app.
The feature code itself compiled almost immediately once the build was correct.

## Friction found

See `friction.md` — eight items, F1–F8. Four are scaffold gaps
(`keliver-init` omits the serialization plugin and its version, omits any
device target, and leaves TLS-proxy env to per-shell discipline), two are
device-loop traps, one is the recurring yarn/TLS masquerade, and one is the
blocked iOS path.

None was fixed in this run: they are scaffolder and dev-loop issues rather
than blocking product defects, and fixing them was out of scope for a
measurement. F1/F2 and F4 are the ones an adopter hits first.
