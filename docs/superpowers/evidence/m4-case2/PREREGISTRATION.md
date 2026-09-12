# M4 case 2 — pre-registration

Written and committed **before the fixture existed** and before any participant
saw anything. Recorded at 2026-09-06T13:55Z, commit parent `b91ae7c9a`.

The literal-label pilot (`../m4-pilot-trial/`) is preserved as historical
evidence. Its runs are **not** folded into this comparison: its participants
were configured differently and no tool-call trace survived.

## 1. The requirement

A small cart screen. Stated identically to both conditions in the task text, so
neither depends on evaluator-only knowledge:

> The cart shows a **Subtotal** line and a **Total** line. Shipping is a flat
> $5.00 and applies only once the cart is non-empty. So with an empty cart both
> Subtotal and Total read `$0.00`; after one tap of "Add item" (a $12.00 item)
> Subtotal reads `$12.00` and Total reads `$17.00`.

The arithmetic is given in the task. Deciding which field the Total row must
read is therefore a matter of reading the stated requirement, not of knowing an
answer key.

## 2. The planted defect

The Total row's `text` is bound to the **wrong field**: `b.subtotal` instead of
`b.total`. Both fields exist on the bindings interface and both are correctly
computed by the presenter.

Chosen so that the initial frame is **correct** — with an empty cart the two
fields hold the same value — and the violation appears only after the
deterministic transition. A single screenshot of the launched app does not show
it.

This is a prediction about the fixture's behaviour, not about which condition
will find it. No claim is made here that this case favours either condition.

## 3. The independent behavioural check

Written from the requirement above, before any participant output exists.
`M4CartTotalCheck.summaryTotalIncludesShippingAfterAdd`, composing the real
screen against a state-backed bindings object:

1. Initial render must be `Cart / Subtotal / $0.00 / Total / $0.00 / Add item`.
2. Locate the rendered **Button** whose text is `Add item` and invoke its
   `onClick`. The requirement is about an interaction, so the check drives the
   rendered action path rather than calling `addItem()` on the bindings.
3. After that transition the rendered rows must read
   `Cart / Subtotal / $12.00 / Total / $17.00 / Add item`.

Scored for every condition by `scripts/m4-score-participant.sh`, which reports
PASS, assertion FAIL, or execution ERROR from the named test's own fresh result
file. Infrastructure that could not run the check is ERROR and says explicitly
that nothing is claimed about the participant.

## 4. Inclusion criteria

The case is included if, and only if, all of these hold. None of them refers to
a predicted advantage for either condition, and an easy baseline win is a valid
observation.

| # | criterion |
|---|---|
| C1 | The requirement is concrete and stated to both conditions |
| C2 | The defective app **compiles** |
| C3 | The defective app **fails** the named assertion, for the intended reason (Total row shows the subtotal) |
| C4 | The **reference fix** (`text = b.total`) passes the same check |
| C5 | The check exercises the rendered action path, not a direct bindings call |
| C6 | An infrastructure failure is classified ERROR, never as an application failure |
| C7 | The defect is reproducible on the emulator: launch shows `$0.00/$0.00`, one tap shows `$12.00/$12.00` |
| C8 | The fixture is neutral — no answer labels, explanatory comments, revealing git history, or evaluator artifacts |

If qualification fails inside the two-hour box, the specific reason is recorded
and case expansion stops. The case is **not** replaced repeatedly until a
likely semantic winner appears.

## 5. Participant configuration and budgets

Launched by `scripts/m4-run-participant.sh`, which pins every knob identically
and records `model_resolved`.

| | baseline | semantic |
|---|---|---|
| model | pinned, same for both (`sonnet`), resolved value recorded | same |
| allowed tools | `Bash Read Edit Write Glob Grep` | same **plus** `mcp__keliver-portal` |
| disallowed | `WebSearch WebFetch` | same |
| MCP | `--strict-mcp-config` with an EMPTY config | `--strict-mcp-config` with the real portal server |
| sandbox | `sandbox-baseline.sb` | `sandbox-semantic.sb` |
| workspace | isolated copy, fresh context | isolated copy, fresh context |
| capabilities | source, builds, execution, interaction, screenshots | the same, plus the portal MCP |
| wall-clock budget | 45 min | 45 min |

Both prompts are byte-identical except for one neutral paragraph in the
semantic prompt, required because the portal tools arrive **deferred**:

> Additional tools from a server named `keliver-portal` are available to you.
> They are not listed up front; discover them with the tool-search mechanism
> your harness provides. Using them is entirely optional.

It names no tool and hints at no defect. Using MCP is **not** required.

Budgets are not adjusted after seeing progress. No coaching, and no
intervention to rescue a diagnosis. Any controller intervention is recorded,
with a judgement on whether the attempt is still usable.

## 6. Measures

Scored separately, never merged into one number:

1. **Detection** — did the report identify the wrong-field binding?
2. **Fix correctness** — the independent check's verdict on the final screen.
3. **Runtime verification** — did the participant observe the corrected
   behaviour running, per its trace rather than its self-report?
4. **Tool use** — from the controller-captured structured stream: whether the
   portal tools were discovered, whether invoked, and where in the call order
   relative to the diagnosis and the edit.
5. Wall clock and available usage figures, reported as context, never as an
   established performance difference.

A `tools/list` probe is not evidence of use. Discovery and invocation are read
from `reports/<cond>-stream.jsonl`.

## 7. Failure handling, fixed in advance

| situation | treatment |
|---|---|
| setup failure before the participant starts | fix it, re-verify readiness, then start; recorded |
| infrastructure failure during scoring | ERROR; nothing claimed about the participant |
| participant exceeds the wall-clock budget | terminated, recorded as INCOMPLETE with whatever the trace shows |
| participant exits without editing | a valid outcome, scored on the unmodified screen |
| controller must intervene mid-run | recorded verbatim, attempt marked usable or void |
| semantic participant never invokes MCP | a valid outcome of **offering** the tools; it does not show that using them would not have helped |

## 8. Interpretation, constrained in advance

One case. No generalisation, no five-case decision rule, no claim that the
thesis is validated, falsified or impossible. Small timing differences are not
performance differences. A completed baseline win is not excluded for being
"too easy". Actual tool use is read from the trace whenever a trace exists.

Nothing here predicts that `get_document` diagnoses this defect. A `Bind` to
the wrong field is still a `Bind`. What the MCP response actually exposes is
recorded during qualification, before any participant runs.
