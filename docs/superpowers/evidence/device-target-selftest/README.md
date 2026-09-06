# keliver-new-device-target: multi-screen defect (2026-09-06)

## Reproduced first

A clean app with two screen files. The command **exited 0** and wrote:

```
package /private/tmp/.../screens/detail.kt:package twoscreen
/private/tmp/.../screens/home.kt:package twoscreen.device
```

Two `package` statements, both containing filenames. Cause: `grep -m1` is
per-file, and prefixes each match with its filename when several files match.
It also paired an arbitrary first screen with an arbitrary first presenter,
so the two could have come from unrelated features.

## Fixed

Resolution moved into a validating Python pass that runs **before any file is
written**:

- the selected screen's package is read from **that screen's own file**
- the presenter is resolved independently and must live in `<root>.logic`
- ambiguity is rejected with the available names and their files
- the layout is required to be `<root>.screens` / `<root>.logic`, else rejected
- every planned build-file edit is checked for its anchor first — a missing
  `plugins { }` block, `js { browser() }` line or compose-runtime dep aborts

Failure exits 3 and leaves the app byte-identical (verified by fingerprint).

## Regression coverage

`scripts/keliver-new-device-target-selftest.sh --compile` — **22/22**
(`selftest-results.log`):

| case | assertion |
|---|---|
| single-screen starter | succeeds; exactly one package line; `single.device`; no filename in package |
| two screens, explicit selection | succeeds; one package line; imports the **selected** screen and presenter; unselected screen absent |
| ambiguous selection | exits 3; app fingerprint unchanged; no `device/` created |
| missing presenter / missing screen | exits 3; app unchanged |
| unsupported build structure (`js { nodejs() }`) | exits 3; app unchanged |
| unknown `--screen` | exits 3 |
| existing `device/` | exits 1, not overwritten |
| **generated output compiles** | single-screen **and** two-screen, `compileKotlinJs` exit 0 |

The two compile checks are the load-bearing ones: the original defect passed
every plausible string inspection and exited 0.
