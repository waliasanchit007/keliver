# M4 pilot — enforced isolation, working runtime target (2026-09-06)

**One case. A pilot of the machinery, not validation of the thesis. The
five-case decision rule is NOT invoked and one timing difference here is not a
performance signal — see Deviations.**

## Prerequisites, all verified before dispatching anyone

| prerequisite | evidence |
|---|---|
| defective fixture runs on the runtime target | `prereq-defective-before.png`; on-screen `Basket` / `0 items` / `Add item` |
| nonblank screenshot identifies the expected app | as above — this app's own strings, no `{field}` mocks |
| the state transition is exercisable | tapped `Add item` at (136,231) → label **stayed** `0 items` (`prereq-defective-after-tap.png`) |
| both conditions have an equivalent execution path | identical `device/Main.kt` device wiring added to **both** workspaces before either ran |
| semantic participant's MCP really registers | `tools/list` over stdio returned all 10: `get_catalog`, `get_guide`, `list_projects`, `list_screens`, `get_document`, `apply_ops`, `undo`, `redo`, `find_usages`, `device_screenshot` |
| isolation boundary actually denies | see below |

## Enforced isolation

`sandbox-exec` seatbelt profiles (`sandbox-*.sb`), one per participant. Each
denies reads of: the controller's repository (which holds the check, the
reference fix, the reports and the git history), the evaluator directory,
prior participant transcripts, earlier experiment runs, this session's project
history, **and the other participant's workspace**.

Verified with a harmless evaluator-side sentinel — not an answer key — before
the trial:

```
sentinel: Operation not permitted
other ws: Operation not permitted
own ws:   jsMain            <- still readable
mcp bin:  portal-mcp        <- still runnable
```

The MCP binary and relay were **copied out of the repository** into a staged
`runtime/` directory, so running MCP does not require exposing the repo. A
build inside the sandbox was confirmed green before dispatch.

Neither final report references the sentinel, the repository, the check, or
the reference fix.

## Result

| | baseline | semantic |
|---|---|---|
| detected | **yes** | **yes** |
| diagnosis | correct, file + line | correct, file + line |
| fix | `text = b.summary` | identical |
| **independent check on final diff** | **PASS** | **PASS** |
| **verified runtime behaviour** | **yes** — tapped twice on the emulator, saw `0 → 1 items → 2 items` | **yes** — same, `0 → 1 → 2`, plus a screenshot |
| self-reported confidence | high | high |
| wall clock | 134s | 400s |
| MCP used | n/a | `list_screens`, `get_document`; `get_catalog` available, unused |

**Both conditions detected the defect, produced byte-identical fixes, and both
genuinely verified on the device.** This is the first run in which either
participant observed runtime behaviour rather than inferring it — the runbook
gave them a working device path, and both used it unprompted.

The semantic participant reported `get_document` as "independent confirmation
of the diagnosis": the prop came back `PropValue.Lit "0 items"` while the
contract exposed `summary`. It reached that conclusion from source first and
used MCP to corroborate.

## Deviations — read before citing the timings

**The two runs did not start from equivalent device state, and the wall-clock
numbers are therefore not comparable.** Freeing the shared emulator between
runs left it unusable for the second participant, so the semantic condition
had to boot an AVD itself and additionally worked around a stale Gradle daemon
error (`FileUtils.canonicalize ... Operation not permitted`, resolved with
`--stop` then `--no-daemon`). Both are environment costs, not analysis time.
The 134s vs 400s gap mostly reflects that.

Other limitations:

- One case, in a two-file app — the regime most favourable to reading source.
- The requirement was stated precisely; real defects rarely arrive specified.
- Only one emulator, so the runs were sequential rather than parallel.
- Isolation is enforced for filesystem reads. Network was not restricted.
