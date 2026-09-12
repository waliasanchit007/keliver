# M4 case 2 — paired comparison result

One case, n=1 per condition, run against the pre-registration in
`PREREGISTRATION.md` and the qualified fixture in `QUALIFICATION.md`.

## Outcome

| measure | baseline | semantic |
|---|---|---|
| detection | wrong-field binding, `cart.kt:29` | wrong-field binding, `cart.kt:29` |
| fix | `text = b.subtotal` → `text = b.total` | byte-identical |
| **independent score** | **PASS** | **PASS** |
| control (unfixed fixture) | **FAIL** on the assertion | — |
| runtime verification, per trace | yes — 3 states on the emulator | yes — 3 states on the emulator |
| portal tools discovered | n/a | **no** — no ToolSearch call |
| portal tools invoked | n/a | **no** |
| wall clock | 101 s | 160 s |
| tool calls | 14 | 19 |
| output tokens | 4 419 | 8 213 |

Both final screens are byte-identical to each other and to the reference fix.
Neither participant touched the presenter.

## What each participant actually did

Read from the controller-captured stream, not from the reports.

**Baseline** (14 calls): `find` the Kotlin files → Read presenter, screen,
device Main → **Edit** → compile → serve → restart host → read the screen →
locate the button bounds → tap, read → tap, read → stop the serve.

**Semantic** (19 calls): `find` → Read presenter, screen → inspect the build
files → **Edit** → compile → serve → poll → diagnose a serve hiccup → grep the
generated JS to confirm `get_subtotal`/`get_total` → restart host → read →
tap, read → tap, read → stop the serve.

In **both** conditions the Edit precedes every runtime action. Diagnosis was
source-based in both; the device was used to verify, not to diagnose. Both
observed all three required states live: `$0.00/$0.00`, `$12.00/$17.00`,
`$24.00/$29.00`.

## The semantic condition's tool use

The prompt told it, neutrally, that `keliver-portal` tools existed and how to
discover them, and that using them was optional. Its trace contains **no
ToolSearch call and no MCP call** — it never inspected what the tools were. Its
report says: *"`keliver-portal` MCP tools — not used; the built-in file tools
and adb workflow were sufficient."*

That is a valid outcome of **offering** the tools, and it is what the pre-
registration anticipated. It does **not** show that using them would not have
helped: the decision was made without looking at them. Whether the channel
*can* diagnose this defect is a different question, examined separately in
`DIAGNOSTIC.md` — which is **not** part of this comparison.

## Configuration parity

Both launched by `scripts/m4-run-participant.sh`:

| | baseline | semantic |
|---|---|---|
| model requested / **resolved** | sonnet / **claude-sonnet-5** | sonnet / **claude-sonnet-5** |
| allowed tools | `Bash Read Edit Write Glob Grep` | the same **+ `mcp__keliver-portal`** |
| disallowed | `WebSearch WebFetch` | same |
| MCP | `--strict-mcp-config`, empty config | `--strict-mcp-config`, portal server |
| sandbox | `sandbox-baseline.sb` | `sandbox-semantic.sb` |
| budget | 45 min wall clock | 45 min wall clock |
| prompt | `task-baseline.txt` | identical **+ 4 lines** naming the tool server and how to discover it |

`diff task-baseline.txt task-semantic.txt` is exactly those four lines.
Isolation preflight on this trial: **25/25**, reports byte-identical
afterwards.

## Readiness, verified before EACH participant

`readiness.sh <cond>`, recorded in `readiness-<cond>.txt` with screenshots:

1. stale Gradle daemons and serves killed
2. controller serves the workspace; manifest sha recorded
3. app launched — `Cart / Subtotal / $0.00 / Total / $0.00 / Add item`
4. defective transition exercised — one tap gives `$12.00 / $12.00`
5. controller serve stopped, **port 8080 confirmed free** for the participant
6. app force-stopped, so both participants start from the same state
7. daemons cleared again

Neither participant inherited a controller-owned serve — the deviation that
made the earlier pilot's timings incomparable.

## Deviations

1. **The emulator was killed by the first draft of the readiness script.**
   `lsof -ti :8080 | xargs kill` matches the emulator, which holds a *client*
   connection to the bundle server on the guest's behalf. This happened before
   any participant started; the emulator was restarted, the script narrowed to
   `-sTCP:LISTEN`, and readiness was re-run clean. **No participant ran in the
   affected environment.**
2. **Bundle manifest hashes differ** between the two workspaces
   (`99065e13…` baseline, `1b936329…` semantic) because each builds in its own
   directory. The *source* is byte-identical — verified by diff against the
   frozen fixture immediately before each run.
3. The semantic participant hit a transient serve/port hiccup and spent three
   calls diagnosing it (steps 9–13). No controller intervention; it recovered
   on its own. This is part of why its wall clock is longer, and one of several
   reasons the 101 s / 160 s gap is not a performance finding.
4. No controller intervention of any kind occurred during either run. No
   coaching, no budget change.

## Limits

One case. No generalisation, no five-case rule, no claim that the thesis is
validated or falsified. The 59-second difference is not an established
performance difference — different call counts, a transient environment hiccup,
and n=1. A completed baseline win is a valid observation and is reported as
one: **on this case, source plus build/run/screenshots was sufficient for both
participants, and the semantic channel was not consulted.**

## Files

`task-{baseline,semantic}.txt`, `sandbox-*.sb`, `mcp-config.json`,
`readiness.sh`, `readiness-*.txt` + screenshots, `*-report.txt`,
`*-toolcalls.txt`, `*-config.json`, `*-stream.jsonl.gz` (the raw captured
streams), `{baseline,semantic}-final.kt`, `scores.txt`,
`isolation-preflight.txt`.
