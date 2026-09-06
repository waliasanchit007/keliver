# M4 pilot — one case, enforced isolation

**Status: pilot. One task, one case, n=1 per condition. This is not thesis
validation and no timing comparison is drawn from it.**

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
| used any portal MCP tool | n/a | **no** |
| wall clock | 124 s | 213 s |

The two final screens are byte-identical (`diff baseline-final.kt
semantic-final.kt` is empty), as are the two presenters.

**The semantic participant did not use the semantic channel.** Its own TOOLS
section lists Bash, uiautomator, lsof and Read; no `keliver-portal` tool
appears. So on this one case the pilot observed no incremental benefit —
because the capability went unused, not because it was tried and failed to
help. A single one-line literal defect is visible in three lines of source,
which is the easiest possible case for the baseline and the weakest possible
case for semantic access.

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
