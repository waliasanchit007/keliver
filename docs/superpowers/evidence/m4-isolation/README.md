# M4 answer-leakage boundary (2026-09-06)

## The leak, reproduced

The committed semantic profile denied the other participant's *workspace* but
not its *outputs*. `pilot/baseline-report.txt` sat directly in the trial root:

```
$ sandbox-exec -f sandbox-semantic.sb cat pilot/baseline-report.txt
VERDICT: no (before fix); yes (after fix)
```

That report carries the diagnosis and the exact one-line reference fix.

## Fix: deny the surface, allow back a small set

The old profile enumerated individual forbidden directories, so anything new —
like a report file — defaulted to readable. Inverted:

1. **deny** the controller repo, the **entire scratchpad root** (evaluator area,
   all participant reports, every prior experiment, both workspaces), the
   controller task-transcript directory, and `~/.claude/projects`
2. **allow `file-read-metadata`** on those ancestors so paths beneath can be
   *resolved* — stat only; no content, no directory listing
3. **allow** read/write on exactly two subpaths: this participant's own
   workspace, and the shared staged `runtime/`

Step 2 was necessary: denying the scratchpad root outright broke path
traversal (`getcwd: cannot access parent directories`) and the Gradle wrapper
could not even load.

Layout — reports are written outside both readable areas **from the start**,
not relocated afterwards:

```
trial/
  evaluator/    denied to both        reports/    denied to both
  runtime/      allowed to both       ws-baseline/  allowed to baseline only
                                      ws-semantic/  allowed to semantic only
```

The staged `runtime/` holds copies of `portal-mcp` and `portal-relay`, so
running MCP never requires exposing the repository.

## Automated preflight — 18/18

`scripts/m4-isolation-preflight.sh <trial-dir>` (`preflight-results.log`).
It plants **harmless sentinels** — never a real answer key — and attempts each
read from inside each participant's profile.

Per participant: evaluator area, controller repository, other participant's
workspace, **other participant's report**, **own report location**, prior
experiment output, controller git history — all denied. Own workspace readable.

Runtime, both verified inside the sandbox: baseline builds its own fixture;
semantic runs the staged MCP server and gets `get_document` back.

## The preflight can fail

Replacing the semantic profile with a permissive `(allow default)` produces
six `READ SUCCEEDED (leak)` failures plus a readable git history. A preflight
that cannot fail proves nothing, so this is checked rather than assumed.

And the original leak under the new profile:

```
cat: …/pilot/baseline-report.txt: Operation not permitted
```
