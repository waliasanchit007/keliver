# M4 pilot comparison — one case, two conditions (2026-09-06)

**This is a pilot of the experimental machinery. It is one case and it does
not validate the general thesis. The five-case decision rule is NOT invoked.**

## Result

| | baseline | semantic |
|---|---|---|
| detected the defect | **yes** | **yes** |
| diagnosis correct | yes (file + line) | yes (file + line) |
| fix | `text = b.summary` | `text = b.summary` (identical) |
| independent check on final diff | **PASS** | **PASS** |
| wall-clock | 49s | 51s |
| tool calls | 5 | 6 |
| tokens | 58,486 | 59,366 |
| observed runtime UI behaviour | **no** — labelled inference | **no** — labelled inference |
| self-reported confidence | high dx / medium-high runtime | high |

**On this case the semantic condition showed no detection advantage.** Both
found it, produced byte-identical fixes, and finished within two seconds of
each other. The baseline found it by reading two short files; at this scale
there was nothing for a structured representation to make cheaper.

## How each one actually found it

**Baseline** read `home.kt` and `HomePresenter.kt`, saw the literal where a
binding was expected, and grepped for other call sites. Source reading was
sufficient — as the review of the original design predicted it might be.

**Semantic** read the same source, then corroborated with `GET /doc`: the prop
came back `PropValue.Lit "0 items"` while `summary` sat in the contract
unused. After fixing, it re-queried and observed the prop had become
`PropValue.Bind field=summary` with the document version incrementing 1 → 2.
It reported the endpoints as "directly useful" for pinpointing the defect.

That is corroboration, not detection: it confirmed a conclusion the source had
already yielded. Whether that changes outcomes at a scale where source reading
is expensive — many screens, unfamiliar code, tight budgets — this case cannot
say.

## What neither condition did

**Neither observed the app actually running.** Both verified by compiling and
both explicitly labelled the runtime behaviour as an inference. The semantic
condition's extra evidence was a re-parse of source, not an execution.

So on the question the positioning cares about — *did the code actually
work?* — both conditions stopped at "it compiles and the source looks right",
which is precisely the shortcut the thesis claims to remove. The independent
check, run by the evaluator afterwards, is the only thing here that observed
runtime behaviour.

## Conditions

Both received the identical task, requirement, source access, build and
execution capability, and a ~25 minute budget on the same model. The semantic
condition additionally received a portal relay for its own app copy. No
baseline capability was removed. Isolated source directories; the semantic
relay used a distinct port (8277) and store (`~/.keliver-m4-semantic`) so the
two could not interfere.

## Limitations — read these before citing the result

- **One case.** Nothing here generalises.
- **The semantic condition used the relay's HTTP API, not `portal-mcp`
  itself.** The MCP server wraps these same endpoints, but it was not
  registered as a tool surface for the participant, so `get_catalog`,
  `find_usages` and `apply_ops` were not exercised. The substitution is
  recorded rather than glossed.
- **Isolation was procedural, not enforced.** Participants ran with filesystem
  access to the machine; they were scoped by instruction to their own
  directory and both reported reading nothing outside it. That is a
  self-report. The evaluator materials were not in their directories and
  their copies carried no git history, but a determined participant could
  have reached the main repository.
- **The task named the requirement precisely.** Real defect-finding rarely
  arrives with the requirement pre-stated, and stating it narrows the search
  dramatically for both conditions.
- **The defect was in a two-file app.** This is the regime least favourable to
  a structured representation, and most favourable to reading the source.
