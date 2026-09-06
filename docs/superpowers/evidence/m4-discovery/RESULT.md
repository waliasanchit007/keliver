# M4 discovery instrumentation — result

Nine runs, three arms, one fixture. Run against
[`PREREGISTRATION.md`](PREREGISTRATION.md), on the qualified case-2 fixture.

## Outcome

**No arm invoked a portal tool. Not once in nine runs — including the arm where
the tools were listed in front of them.**

| arm | listing | prompt | invoked portal tools | discovery call | fix |
|---|---|---|---|---|---|
| **A** deferred + how-to-discover | deferred | case 2's, verbatim | **0/3** | 0/3 | correct 3/3 |
| **B** deferred, availability only | deferred | availability only | **0/3** | 0/3 | correct 3/3 |
| **C** listed, availability only | **listed** | identical to B | **0/3** | n/a | correct 3/3 |

All nine exited 0 and produced the expected screen fix. **Each of the nine was
executed by the scorer individually** — twelve executions in all, including the
three retained artifacts scored from their own copies — and every one passed;
the unfixed control fails the same check:

```
a-r1..3 PASS   b-r1..3 PASS   c-r1..3 PASS   (+ 3 retained copies PASS)
control-defective FAIL — Expected <[…, Total, $17.00]>, actual <[…, $12.00]>
```

Provenance of each output — retained, reconstructed, or unestablished — is in
[`AUDIT.md`](AUDIT.md): 3 retained, 6 reconstructed with the replay validated
against ground truth, 0 unestablished.

Per-run detail in [`run/summary.txt`](run/summary.txt); wall clock 88–149 s,
11–19 tool calls.

## The manipulation worked — checked, not assumed

A null result is only informative if arm C really did list the tools. Verified
**under the exact run configuration** (same sandbox, same allowed tools, same
MCP config), with a prompt that asks for one portal call:

```
deferred  ->  ToolSearch -> mcp__keliver-portal__get_document
listed    ->                mcp__keliver-portal__get_document
```

Same participant configuration, same server; the only difference is `--tools`.
So in arm C the ten portal tools were in the model's listed tool set, and were
still not used. ([`manipulation-check/`](manipulation-check/))

`--settings '{"toolSearchEnabled":false}'` was tried first and had **no
effect** — `ToolSearch` still preceded the call. Recorded because the obvious
knob is the wrong one.

## What is supported, and what is not

**This is three arms with three repetitions each — nine runs on ONE fixture,
not nine independent defect cases.** The arms and their repetitions are kept
distinct throughout; nothing here is a sample of nine defects.

**Supported.** On this one fixture, all nine participants produced the expected
screen fix, and none invoked a portal tool — including the three runs where the
tools were listed upfront rather than behind deferred discovery.

**Not supported, and previously overstated here.** An earlier version of this
document said arm C "settles" why the earlier deferred participants did not use
the tools, in favour of *considered and declined*. **It does not.** The listed
participants' explanations are self-reports; they are consistent with their
traces, but a trace can only show that no tool was called, never why. They are
evidence about *these three participants*, and they cannot establish the
decision process of the case-2 participant or of arms A and B, who were
differently situated. The distinction case 2 opened — *not discovered* versus
*considered and declined* — remains **unresolved** for the deferred runs.

What the study does add is narrower and still worth having: with the tools
plainly in view, three participants still did not call them. Visibility alone
did not produce use here.

Also not shown:

* **not** that the tools are useless. The separately labelled forced-use
  exercise ([`../m4-case2/DIAGNOSTIC.md`](../m4-case2/DIAGNOSTIC.md))
  demonstrates that the queries **expose useful binding information** —
  `get_document` gives field identity, `find_usages` shows `total` bound by
  nothing. It does **not** establish that using the tools improves outcomes.
* **not** anything about baseline vs semantic — that stays as
  [`../m4-case2/RESULT.md`](../m4-case2/RESULT.md) reports it.
* **not** that listing never matters — one fixture, one model, one defect shape.
* A vs B (the how-to-discover sentence) shows 0/3 in both arms. With no
  variation in either, there is no contrast to interpret.

## Every knob, recorded

Identical across all nine: model requested `sonnet`, **resolved
`claude-sonnet-5`** in every run; allowed `Bash Read Edit Write Glob Grep` +
`mcp__keliver-portal`; disallowed `WebSearch WebFetch`; `--strict-mcp-config`
with a per-arm portal server; own sandbox profile, own workspace, own relay and
document store; 45-minute budget. Arm C additionally
`--tools "Bash,Read,Edit,Write,Glob,Grep"` — the manipulation.

Prompts: **B and C byte-identical**; A adds only the two-line how-to-discover
sentence. Isolation preflight on this trial: **35/35**, fixtures unchanged.

Readiness before **every one** of the nine runs: workspace screen restored to
the frozen fixture and verified, daemons and serves cleared, controller serve
up, launch `$0.00/$0.00`, defective transition exercised to `$12.00/$12.00`,
controller serve stopped, port 8080 confirmed free, app force-stopped.

## Deviations

1. **Nine earlier runs were killed and are void.** Every one exited 143
   (SIGTERM) 30–65 s in, mid-diagnosis. Cause: the readiness step ran
   `pkill -f serveDevelopmentZipline`, and that string appears **inside the
   task text**, which is passed as `claude -p "<task>"` argv — so the pattern
   matched the participant's own process. With two driver instances briefly
   overlapping, one run's readiness killed another run's participant. Fixed by
   killing the bundle server **by port** and Gradle by daemon class, never by a
   pattern that can match a participant, plus a lock directory so only one
   driver runs. The void configs and traces are kept in
   [`void-runs/`](void-runs/); **none of their data is used**, and all nine
   reported runs are fresh post-fix runs. Case 2 used the same readiness script
   but ran one participant at a time with no overlap, and all its participants
   exited 0 — unaffected.
2. **Final screens were reconstructed, not snapshotted.** The driver restores
   the frozen fixture before each replicate, so only each arm's third run
   survived on disk. The other six final screens were rebuilt by replaying each
   run's captured `Edit` parameters onto the frozen fixture. The method was
   validated by reconstructing the three that *did* survive and confirming they
   match the live workspaces byte-for-byte. A future driver should snapshot the
   screen at the end of each run.
3. No controller intervention occurred inside any of the nine reported runs. No
   coaching, no budget change.

## Limits

Nine runs, one fixture, one model, one defect shape. No generalisation, no
five-case rule, no claim about the thesis. The timing spread (88–149 s) is not
a performance measure. A null result across all three arms is reported as the
observation it is.

## Files

[`run/`](run/) — prompts, sandbox profiles, MCP configs, readiness records, the
nine reports, tool-call traces, configs, gzipped raw streams, the shared final
screen, scores, isolation preflight, `summary.txt`.
[`manipulation-check/`](manipulation-check/) — the deferred/listed proof.
[`void-runs/`](void-runs/) — the killed runs, kept as the record of the defect.
