# M4 discovery instrumentation — pre-registration

Committed **before any participant ran**. Follow-on to
[`../m4-case2/`](../m4-case2/), reusing its qualified fixture, its behavioural
check, the validated runner and the isolation preflight. **No new case.**

## The question

Case 2's semantic participant was told the portal tools existed and how to
discover them, and issued **no discovery call at all**. That result cannot
distinguish two explanations:

* **not discovered** — the tools were behind deferred discovery and never
  entered the participant's field of view; or
* **considered and declined** — it knew what was on offer and judged it
  unnecessary.

This study changes one thing — whether the portal tools appear in the listed
tool set — and measures whether they are invoked. It does **not** re-run the
baseline-vs-semantic comparison and produces no claim about it.

## The manipulation, and how it was found

Deferral is not a switch. `--settings '{"toolSearchEnabled":false}'` was tried
and had **no effect** (probed: `ToolSearch` still preceded the MCP call).
Restricting the available built-in set with
`--tools "Bash,Read,Edit,Write,Glob,Grep"` **does** bring the ten portal tools
into the listing: in the probe the participant called
`mcp__keliver-portal__get_document` directly, with no `ToolSearch`.

So the effective tool surface is:

| arm | listing the participant sees |
|---|---|
| deferred | 6 built-ins + `ToolSearch` (10 portal tools reachable through it) |
| listed | 6 built-ins + the 10 portal tools (no `ToolSearch`) |

The six *permitted* built-ins are identical in every arm. Built-ins present in
the deferred arm's default set but never permitted (Task, TodoWrite, and the
rest) are absent from the listed arm's set — recorded as a confound in §6.

## Arms

Three arms. **Three replicates each, nine runs**, because tool choice is a
stochastic decision and n=1 cannot characterise it.

| arm | listing | prompt |
|---|---|---|
| **A · deferred + how-to-discover** | deferred | case 2's semantic prompt, verbatim |
| **B · deferred, availability only** | deferred | availability sentence, no discovery instruction |
| **C · listed, availability only** | listed | **byte-identical to B** |

The contrasts this buys:

* **B vs C** — prompts byte-identical, only the listing differs. This is the
  clean isolation of the variable.
* **A vs B** — listing identical, only the discovery instruction differs.
* **A** replicates case 2's semantic condition, so case 2's single observation
  gains three siblings.

Availability sentence, in all three arms:

> Tools from a server named `keliver-portal` are available to you in this
> session. Using them is entirely optional.

Arm A adds, exactly as in case 2:

> They are not listed up front — discover them with the tool-search mechanism
> your harness provides.

No arm is told which tool would reveal anything, and no arm is required to use
MCP.

## Everything else held constant

Same frozen case-2 fixture (`cart.kt` with the Total row bound to
`b.subtotal`), verified byte-identical before every run. Same task text. Same
pinned model, resolved value recorded per run. Same allowed/disallowed tools,
same `--strict-mcp-config`, same sandbox shape, same 45-minute budget. Same
readiness protocol before **every** run: daemons and serves killed, workspace
screen restored to the frozen fixture, controller serve up, launch state
`$0.00/$0.00` confirmed, defective transition exercised to `$12.00/$12.00`,
controller serve stopped, port 8080 confirmed free, app force-stopped.

## Measures

Per run, from the controller-captured stream — never from the self-report:

1. **Discovery** — was `ToolSearch` called? (arm A/B only; arm C has no such tool)
2. **Invocation** — were any `mcp__keliver-portal__*` tools called, which, how many
3. **Position** — did any MCP call precede the Edit, corroborate after it,
   assist the edit, or assist verification
4. **Fix correctness** — the independent check's verdict, PASS / FAIL / ERROR
5. Wall clock, call count, usage — context only, never a performance claim

Reported per arm as counts out of three. No significance testing on n=3.

## Failure handling, fixed in advance

| situation | treatment |
|---|---|
| setup failure before a run | fix, re-verify readiness, restart that run; recorded |
| infrastructure failure in scoring | ERROR; nothing claimed about that run |
| run exceeds 45 min | terminated, recorded INCOMPLETE with its trace |
| controller intervention | recorded verbatim; run marked usable or void |
| a run produces no edit | valid; scored on the unmodified screen |
| an arm's runs disagree with each other | reported as the observed spread, not averaged away |

## Interpretation, constrained in advance

Nine runs on one fixture with one model. This can show whether listing changes
invocation **on this case**; it cannot show that invocation helps, and it says
nothing about baseline-vs-semantic, which stays as `../m4-case2/RESULT.md`
reports it. If arm C invokes the tools and arms A and B do not, that is
evidence about **discoverability**, not about value. If arm C also declines,
that is evidence the tools were seen and not wanted — equally reportable, and
the more informative outcome for the framework's design.

No generalisation beyond this fixture. No five-case rule. No claim that the
thesis is validated or falsified.
