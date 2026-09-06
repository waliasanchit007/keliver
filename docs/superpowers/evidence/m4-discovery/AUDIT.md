# Provenance audit of the nine discovery outputs

The nine runs' final screens were reported after six of them had been
reconstructed by replaying captured `Edit` calls. This audit establishes
whether that reconstruction is sound, without rerunning any participant.

`scripts/m4-audit-streams.py` — output in [`audit/stream-audit.txt`](audit/stream-audit.txt).

## Provenance of each output

| class | runs | basis |
|---|---|---|
| **Retained directly** | a-r3, b-r3, c-r3 | survived in their workspace; copied with hashes to [`run/retained/`](run/retained/) |
| **Reconstructed, supported** | a-r1, a-r2, b-r1, b-r2, c-r1, c-r2 | replayed from the stream; see the checks below |
| **Not established** | *(none)* | — |

## Why the six reconstructions are trustworthy

Replay is sound only if the stream contains **every** change to the evaluator's
input. That was checked, not assumed:

1. **Exactly one `Edit` per run**, all targeting
   `src/jsMain/kotlin/screens/cart.kt`, and **no `tool_result` came back as an
   error**. No failed edit exists that a naive replay might have applied.
2. **No `Write` or `NotebookEdit` calls** in any of the nine runs.
3. **Every Bash redirect target was enumerated** across all nine streams. All
   of them are `/dev/null`, `&1`, a serve log or `shot.png` inside the
   participant's own scratchpad, or a `>` character inside a quoted grep
   pattern. **No redirect writes anywhere inside a workspace source tree.**
4. **Zero write-capable non-redirect shell constructs** — no `sed -i`, `tee`,
   `cp`/`mv`/`rm`/`touch`/`ln`, git mutation, `patch`, heredoc, or
   `python open(...,'w')` in any of the nine.
5. **Method validated against ground truth**: the three runs whose workspace
   survived were reconstructed by the same replay and compared to the live
   files — byte-identical in all three.
6. **The rest of the workspace is unchanged.** `logic/CartPresenter.kt` and
   `device/Main.kt` in all three workspaces still match the frozen fixture, so
   no participant fixed the defect somewhere the screen-substituting scorer
   would not see.

Audit verdict: **REPLAYABLE 9, CAVEAT 0, NOT-ESTABLISHED 0**.

## Scoring: twelve independent executions, no shared score

The earlier report ran the scorer **once** over one file and attributed the
verdict to all nine on the grounds that the nine were byte-identical. That is
now unnecessary. Every output was executed by the scorer on its own:

```
a-r1 PASS   a-r2 PASS   a-r3 PASS
b-r1 PASS   b-r2 PASS   b-r3 PASS
c-r1 PASS   c-r2 PASS   c-r3 PASS
a-r3 RETAINED PASS   b-r3 RETAINED PASS   c-r3 RETAINED PASS
```

Twelve executions, twelve PASS ([`audit/scores-per-run.txt`](audit/scores-per-run.txt)).
The three r3 artifacts were additionally scored from their retained copies, so
the retained and reconstructed paths were each exercised.

The nine outputs are in fact byte-identical (`7e297d99…`, equal to the
reference fix), but nothing in the result now depends on that.

## Fixed for future runs

The driver now retains each participant's complete `src/` tree plus
`SHA1SUMS.txt` under `outputs/<arm>-r<rep>/` **before** anything restores the
workspace ([`run/drive.sh`](run/drive.sh)). Eight lines; no runner redesign.

## Preserved

Original artifacts are unchanged. The nine void runs killed by the
`pkill -f serveDevelopmentZipline` defect remain in [`void-runs/`](void-runs/)
and none of their data is used anywhere.
