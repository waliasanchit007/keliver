# M4 pilot comparison — one case, two conditions

**Pilot of the experimental machinery. One case. Not validation of the
thesis. The five-case decision rule is NOT invoked.**

Two runs happened. **Run 2 is the one that counts**; run 1 is retained below
because its deviations are instructive.

---

# Run 2 — corrected conditions (2026-09-06)

Both conditions were fresh `claude -p` processes (no shared context with the
work that built the fixture), same model, same task file, isolated copies of
the same defective app, `Bash`/`Read`/`Edit`/`Write`/`Glob`/`Grep` granted to
both. The semantic condition **additionally** got the real `portal-mcp` server
over stdio — `get_catalog`, `get_document`, `list_screens`, `find_usages`,
`get_guide`, `device_screenshot` — against its own relay (port 8377, store
`~/.keliver-m4run2`). No baseline capability was removed.

## Result

| | baseline | semantic |
|---|---|---|
| detected | **yes** | **yes** |
| diagnosis | correct, file + line | correct, file + line |
| fix | `text = b.summary` | identical |
| **independent check on final diff** | **PASS** | **PASS** |
| compiled it themselves | yes | yes |
| **observed the app running** | **no** | **no** (attempted) |
| self-reported confidence | high | high |
| wall clock | **85s** | **102s** |

**No detection advantage, and the semantic condition was 17s slower.**

## What the MCP tools actually did

The semantic participant used `get_document` before and after its fix: node
handle 3's `text` was `PropValue.Lit "0 items"`, and afterwards
`PropValue.Bind field="summary"`, with the contract still exposing
`summary: String` and action `addItem`. It rated this "helped (strong
corroboration)". `get_catalog` and `get_guide` were loaded but unused.

**The interesting one is `device_screenshot`.** The semantic participant
reached for it to verify at runtime — the right instinct, and the tool the
first run's design had wrongly assumed did not exist. It returned a black
screen because no live app was attached to that screen, so the participant
recorded: *"I did not watch the label change from '0 items' to '1 items' at
runtime."*

So the one runtime tool in the semantic surface was available, was used, and
did not help **in this setup** — because nothing was running to screenshot.
That is a fact about how this pilot was staged, not a property of the tool.
A comparison that wants to test runtime verification must attach a live app
first.

## What neither condition did

Neither observed the app running. Both compiled and both explicitly labelled
the runtime behaviour as inference from Compose semantics. The only runtime
observation in this experiment remains the evaluator's own check.

## Isolation

Participants ran as separate processes with cwd set to their own app copy,
which contained no git history and no evaluator material. Neither final report
references the check, the reference fix, or the evidence directories.

**Still imperfect, and stated plainly:** the filesystem was not sandboxed, so
reaching the main repository was technically possible. The semantic
condition's MCP binary lives inside that repository, so its process
necessarily touched a path there. Isolation is therefore "separate working
copies plus post-hoc inspection of the reports", not enforcement.

## Limitations

- One case, in a two-file app — the regime least favourable to a structured
  representation and most favourable to reading the source.
- The requirement was stated precisely. Real defect-finding rarely arrives
  pre-specified, and stating it narrows the search for both conditions.
- Runtime verification was available to the semantic condition but untestable
  here because no app was attached.
- Wall-clock differences of ~17s on a ~90s task are not a meaningful
  performance signal.

---

# Run 1 — superseded, deviated from the assignment

Recorded because the deviations matter. Run 1 gave the semantic condition the
relay's **HTTP endpoints via curl instead of the MCP server**, and its
isolation was instruction-only. Both violated the specified design, so its
result — also "both detected, identical fixes, 49s vs 51s" — supports only an
exploratory source-vs-source-plus-document exercise. It does **not** establish
anything about the MCP toolset, which run 2 exercises properly.
