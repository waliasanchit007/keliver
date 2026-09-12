# M4 pilot — one case, enforced isolation

**Status: SUPERSEDED as a comparison. Retained as a record.**

Isolation held and the scoring was sound, but the two participants were not
configured alike, so the outcome cannot be attributed to semantic access. The
final diffs and the evaluator scores below remain useful; the comparison does
not. The runner defects this exposed are fixed — see *What invalidated the
comparison* — and the corrected instrument is verified in
[`m4-runner-mechanics`](../m4-runner-mechanics/). The next case must be run
with `scripts/m4-run-participant.sh`, which pins the configuration and retains
the tool-call trace.

One task, one case, n=1 per condition. Not thesis validation, and no timing
comparison is drawn from it.

Question under test: does semantic access to the UI document (the portal MCP)
give an agent an *incremental* benefit over source plus build/run/screenshots?

## Setup

| | baseline | semantic |
|---|---|---|
| workspace | `trial/ws-baseline` | `trial/ws-semantic` |
| sandbox profile | `sandbox-baseline.sb` | `sandbox-semantic.sb` |
| prompt | `task-and-runbook.txt` (identical) | identical |
| extra capability | none | `--mcp-config mcp-config.json` (10 portal tools) |
| device | shared `emulator-5554`, generic Keliver host, reset between runs | same |

Both participants started from the same defective screen
(`control-defective.kt`): the summary label is the literal `"0 items"`, so it
never reflects the count.

## Isolation

`scripts/m4-isolation-preflight.sh` — **18/18**, output in
`isolation-preflight.txt`. It plants harmless `SENTINEL-…-HARMLESS` strings in
the evaluator area, the controller repository, the other participant's
workspace, both report locations, and prior experiment output, then requires
every read to be denied from inside each profile. It never plants a real
answer key.

The leak this closes: in the previous run a participant could `cat` the other
participant's report. That read is now refused:
`cat: …/reports/baseline-report.txt: Operation not permitted`.

The preflight is itself able to fail: run against a permissive profile it
reports six `READ SUCCEEDED (leak)` failures.

Participant stdout, stderr and reports are written to `trial/reports/`, which
is outside both participants' readable areas from the start — not moved there
afterwards.

## Scoring

Scored by the evaluator's own behavioural check
(`portal-render/.../M4LabelUpdateCheck.kt`) via
`scripts/m4-score-participant.sh`, which substitutes each participant's final
screen into the parity fixture and runs the check. Scoring is therefore
independent of what a participant claimed about its own verification.

```
SCORE control-defective: FAIL — REQUIREMENT: ... Expected <[Basket, 1 items]>, actual <[Basket, 0 items]>
SCORE baseline-final:    PASS
SCORE semantic-final:    PASS
```

The control failing is what makes the two passes meaningful.

## Outcome

| | baseline | semantic |
|---|---|---|
| detected the defect | yes | yes |
| fix | `text = "0 items"` → `text = b.summary` | byte-identical |
| independent score | PASS | PASS |
| verified on the real emulator | yes (0 → 1 → 3) | yes (0 → 1 → 2 → 3 → 4) |
| used any portal MCP tool | n/a | **none reported** (self-reported, uncorroborated) |
| wall clock | 124 s | 213 s |

The two final screens are byte-identical (`diff baseline-final.kt
semantic-final.kt` is empty), as are the two presenters.

**The semantic participant reported using no portal tool** — its TOOLS section
lists Bash, uiautomator, lsof and Read, and no `keliver-portal` tool appears.
That is a participant's own account and nothing corroborates it: no tool-call
trace was retained from this run. A separate `tools/list` probe establishes
that the server was *available*, not what happened inside the session. Treat
"the channel went unused" as **self-reported and uncorroborated** for this
pilot.

The mechanics check for the corrected runner found something that bears on it:
the portal tools arrive **deferred**, and a participant reached one only after
calling `ToolSearch`. Under-discoverability and deliberate non-use are
different explanations, and this pilot cannot separate them.

Independently of that, a one-line literal defect visible in three lines of
source is the easiest possible case for the baseline and the weakest possible
case for semantic access.

## What invalidated the comparison

1. **The configurations were not held constant.** Baseline ran
   `--model sonnet` with an explicit `--allowedTools` list. Semantic ran with
   no `--model` — so the operator default, `claude-opus-5` — and
   `--permission-mode bypassPermissions`. The semantic run's resolved model was
   never recorded, so equal models cannot be established even in hindsight.
   Fixed: `scripts/m4-run-participant.sh` sets every knob identically and
   writes `model_requested`/`model_resolved` to `reports/<cond>-config.json`.
   The only intended difference is that `semantic` loads the portal MCP server.
2. **No tool-call trace was retained.** Fixed: the controller now captures
   `--output-format stream-json` into `reports/<cond>-stream.jsonl` — outside
   the participant's readable area — and derives `reports/<cond>-toolcalls.txt`
   from it. Denying a participant its own history never required losing the
   audit trail.
3. **The scorer conflated infrastructure failure with application failure.**
   Any nonzero Gradle exit read as "requirement not met at runtime"; given the
   known-correct fix and an unusable Gradle home it reported a runtime failure
   for code it never executed. Fixed: PASS / assertion FAIL / execution ERROR,
   selected from the named test's own fresh result file. The scores above were
   re-run with the corrected scorer and are unchanged.
4. **The preflight destroyed the reports it was protecting.** It wrote its
   sentinels straight onto `reports/<cond>-report.txt`; the copies in this
   directory survived only because they were taken before the rerun that
   clobbered the originals. Fixed: run-unique sentinel files and a
   before/after checksum of everything under `reports/` and `evaluator/`.

## Deviations (do not compare the timings)

1. The controller had already started `serveDevelopmentZipline` for
   `ws-semantic` on port 8080, so the semantic participant's own serve failed
   to bind. It noticed, checked the owning process with `lsof`, and proved
   bundle freshness by comparing the served `basket.js` hash before and after
   its rebuild. Correct handling, but it is work the baseline did not have to
   do. The 124 s / 213 s figures are not comparable.
2. `~/.claude/projects` is denied inside both profiles, so neither participant
   left a transcript. MCP *usage* can therefore only be read from the
   participant's own TOOLS section; MCP *availability* was verified
   independently, before and after the run, by a `tools/list` probe inside
   `sandbox-semantic.sb` returning all ten tools.
3. The relay does not log requests, so its log cannot corroborate MCP use
   either way.

## Defect found while verifying the semantic channel

`get_document` takes the screen name as `list_screens` returns it (`home`).
Passing the qualified form the relay itself prints (`default/home`) silently
returns a *different, empty* document under the double-prefixed name
`default/default_home` rather than an error. Called correctly it returns
exactly the signal the condition is meant to supply — after the fix,
`text: PropValue.Bind(field=summary)`; with the defect present it reads
`PropValue.Lit("0 items")` (`mcp-get_document-home.json`).

## Files

`task-and-runbook.txt`, `sandbox-*.sb`, `mcp-config.json`,
`isolation-preflight.txt`, `*-report.txt`, `*-timing.txt`,
`control-defective.kt`, `baseline-final.kt`, `semantic-final.kt`,
`scores.txt`, `relay-semantic.log`, `mcp-get_document-home.json`,
`prereq-semantic-before.png`, `prereq-semantic-after-tap.png` (the defect
reproduced live immediately before the semantic run: one tap, label unchanged
at `0 items`).
