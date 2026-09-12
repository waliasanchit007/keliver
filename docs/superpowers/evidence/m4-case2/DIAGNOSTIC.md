# M4 case 2 — separately labelled diagnostic exercise

**This is NOT part of the paired comparison in `RESULT.md`, and its result must
not be combined with it or presented as the same experimental condition.**

The comparison answered: *offered the channel, does an agent use it?* — no.
This exercise answers a different question: *required to use the channel, can
it diagnose this defect?*

## Setup

Third isolated workspace (`ws-diagnostic`), its own sandbox profile, its own
relay and store, same frozen fixture (byte-identical to the qualified one),
same pinned model — resolved **`claude-sonnet-5`**, same as both comparison
participants. Readiness verified the same way: launch `$0.00/$0.00`, one tap
`$12.00/$12.00`, port 8080 handed over free.

Prompt = the baseline task **plus a constraint on method**: reach the diagnosis
using the portal tools; do not open anything under `src/jsMain/kotlin/screens/`
or read the compiled JavaScript until the DIAGNOSIS is written; reading
`logic/` and building/running are allowed. It names no tool and points at no
defect.

## What happened

19 calls, **7 of them portal MCP calls**. Call order from the captured stream:

```
 1. ToolSearch  (select: all ten keliver-portal tools)
 2. get_guide           -> "guide not found"
 3. list_projects       -> ["default"]
 4. Bash ls -R src/jsMain/kotlin/
 5. list_screens        -> ["cart"]
 6. Read logic/CartPresenter.kt          (allowed)
 7. get_catalog
 8. get_document(cart)
 9. find_usages("total")     -> no usages
10. find_usages("subtotal")  -> StyledText#4 and #6 both bind 'subtotal'
    "I have enough to diagnose."
11. Read screens/cart.kt      <-- constraint breach, see Deviations
12. Read device/Main.kt
13. Edit screens/cart.kt
14-19. compile, serve, restart host, tap, read, tap, read
```

Fix: `text = b.subtotal` → `text = b.total`, identical to the reference fix and
to both comparison participants. **Independent score: PASS.**

Its own TOOL FINDINGS: `get_document` "decisive… located the bug without
opening `screens/`"; `find_usages("total")` → no usages, "decisive
corroboration"; `find_usages("subtotal")` "pinpointing the duplicated binding".
`get_guide` returned "guide not found" and helped nothing; `list_projects` and
`get_catalog` were orientation.

## What this does and does not show

It shows that on this defect the semantic channel **is sufficient for
diagnosis**: the three responses that mattered arrived before any screen source
was read, and the participant stated it had enough at that point.

It does **not** show the channel is faster, better, or necessary. The two
comparison participants reached the same diagnosis from three files of source
in fewer or equal calls. It does not license any claim about the comparison,
which stands as reported: offered the channel, the semantic participant did not
open it.

## Deviations

1. **The method constraint was breached.** The participant opened
   `screens/cart.kt` (call 11) *before* writing the DIAGNOSIS section, having
   said "I have enough to diagnose" immediately after call 10. The MCP
   responses provably preceded any screen-source read, so the diagnosis is
   attributable to them by call order — but the constraint's formal condition
   was not met, so this is weaker than a clean isolation of the channel. A
   future exercise should enforce it mechanically (deny reads of `screens/`
   in the sandbox until the diagnosis is submitted) rather than by instruction.
2. `get_guide` fails in a scaffolded app: it looks for `docs/PORTAL_USAGE.md`
   in the adopter's repo, which a scaffolded app does not have. Recorded, not
   fixed — out of scope here.

## Files

`task-diagnostic.txt`, `sandbox-diagnostic.sb`, `mcp-config-diagnostic.json`,
`readiness-diagnostic.txt`, `diagnostic-report.txt`,
`diagnostic-toolcalls.txt`, `diagnostic-config.json`,
`diagnostic-stream.jsonl.gz`, `diagnostic-final.kt`.
