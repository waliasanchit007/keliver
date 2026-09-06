# M4 runner mechanics — verifying the instrument, not the hypothesis

`scripts/m4-runner-selftest.sh <template-trial-dir>` — **8/8**.

It builds a throwaway trial, gives both conditions a short deterministic task
that *asks* for one portal call, and checks that the runner can observe what
the first pilot could not.

| check | result |
|---|---|
| relay reachable for the semantic condition | up on :8577 |
| both conditions resolve the SAME model, recorded | `claude-sonnet-5` |
| tool-call trace retained, baseline | 1 call |
| tool-call trace retained, semantic | 3 calls |
| semantic's portal MCP call captured | `mcp__keliver-portal__get_document` ×1 |
| baseline portal MCP calls | 0 |
| a second run refuses to overwrite | exit 3 |

The task deliberately asks for the MCP call. That establishes a call **would
be observed if one happened** — it says nothing about whether a participant
solving a real task chooses to use the channel.

## The finding that bears on the pilot

The semantic participant's captured call order was:

```
1. Bash
2. ToolSearch
3. mcp__keliver-portal__get_document
```

The portal tools arrive **deferred**. Reaching one took a `ToolSearch` call
first. So in the earlier pilot, "the semantic participant did not use the
portal" and "the semantic participant never saw the portal in its tool list"
are both consistent with the evidence, and that pilot cannot separate them.
The next case must record the trace — which is now what `m4-run-participant.sh`
does by default.

## Configuration parity

Both conditions get the same `--model`, the same `--allowedTools` base list,
the same `--disallowedTools`, and `--strict-mcp-config` (baseline against an
empty MCP config, so the operator's own global servers are excluded from both
and the two runs take the same code path). The single intended difference is
that `semantic` loads the portal MCP server and may call its tools.

Files: `mechanics-task.txt`, `{baseline,semantic}-config.json`,
`{baseline,semantic}-toolcalls.txt`.
