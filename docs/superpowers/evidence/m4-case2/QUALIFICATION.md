# M4 case 2 — fixture qualification

Every criterion in `PREREGISTRATION.md` §4, executed before any participant
existed. **All eight hold; the case is included.**

| # | criterion | result |
|---|---|---|
| C1 | requirement concrete and stated to both conditions | in the task text, with the arithmetic |
| C2 | defective app compiles | `compileKotlinJs` BUILD SUCCESSFUL |
| C3 | fails the named assertion, for the intended reason | FAIL: `Expected <[Cart, Subtotal, $12.00, Total, $17.00]>, actual <[…, $12.00]>` |
| C4 | reference fix passes the same check | PASS |
| C5 | check exercises the rendered action path | a dead `onClick = { }` with otherwise-correct bindings FAILs |
| C6 | infrastructure failure is ERROR | unusable Gradle home + known-good input → exit 4 |
| C7 | reproducible on the emulator | launch `$0.00/$0.00`; one tap `$12.00/$12.00` |
| C8 | fixture is neutral | three source files, no git, no revealing text |

Exit codes: C3 = 1 (assertion FAIL), C4 = 0 (PASS), C5 = 1, C6 = 4 (ERROR).

## The defect

`src/jsMain/kotlin/screens/cart.kt`, the Total row: `text = b.subtotal` where
the requirement needs `b.total`. Both fields exist on `CartScreenBindings` and
the presenter computes both correctly.

The initial frame is **correct** — an empty cart makes the two fields equal —
so a screenshot of the launched app does not show the defect. `c7-launch.png`
shows `$0.00 / $0.00`; after one tap `c7-after-tap.png` shows `$12.00 / $12.00`
where Total must read `$17.00`.

## C5 in detail

The requirement is about an interaction, so the check locates the rendered
`Button` whose text is `Add item` and invokes its `onClick`, rather than
calling `addItem()` on the bindings. A variant with correct bindings and a dead
button (`onClick = { }`) FAILs the check with `actual <[Cart, Subtotal, $0.00,
…]>` — the transition never happened. The check therefore covers the action
wiring, not only the value bindings.

## What the MCP actually exposes — recorded before any participant ran

This is a factual record of the response, not a prediction that it diagnoses
the defect.

`get_document{screen: "cart"}` on the defective screen:

| node | text prop |
|---|---|
| StyledText | `Lit "Cart"` |
| StyledText | `Lit "Subtotal"` |
| StyledText | **`Bind field=subtotal`** |
| StyledText | `Lit "Total"` |
| StyledText | **`Bind field=subtotal`** |
| Button | `Lit "Add item"` + `Action addItem` |

`contract: {fields: {subtotal: String, total: String}, actions: [addItem]}`

`find_usages`:

```
subtotal -> cart: StyledText#4.text binds field 'subtotal'
            cart: StyledText#6.text binds field 'subtotal'
total    -> no usages of 'total' in project 'default'
```

**What that does and does not settle.** A `Bind` to the wrong field is still a
`Bind`: nothing in the response is marked wrong. What the response makes
available is (a) **field identity** — the row following the literal `"Total"`
binds `subtotal` — and (b) a **usage relationship** — `total` is declared in the
contract and bound by nothing, while `subtotal` is bound twice. Both are facts
about the document.

Turning either into "this is the defect" still requires interpreting the stated
requirement: that the row labelled `Total` must show subtotal-plus-shipping.
Neither query supplies that. **No claim is made here that one query diagnoses
the defect**, and none that the same facts are unavailable from reading three
files of source.

## Observed blocker during qualification, and its containment

The relay's document store defaults to `~/.keliver-portal`, a **machine-global**
directory. Booting the relay against the fresh fixture materialised a `feed`
screen from an unrelated earlier session **into the fixture's own source tree**
(`screens/feed.kt`, `screens/Compiled_feed.kt`, both `package shopcart.screens`)
— an adopter-facing defect of the same family as the previously recorded
`main.kt` leak, and here a direct threat to fixture neutrality (C8).

Contained for this run by setting `"store"` to a per-trial directory in the
fixture's `keliver.portal.json`. The leaked files are kept as
`leaked-feed.kt` / `leaked-Compiled_feed.kt` in the scratch qualification dir
and were deleted from the fixture before it was frozen; the source tree was
re-verified afterwards (three files) and recompiled clean.

Recorded, not repaired — repairing relay behaviour is out of scope for this
assignment.

A second, already-known operational hazard recurred: a Gradle daemon started
inside a sandbox is reused by a later unsandboxed invocation and fails with
`fileHashes.lock (Operation not permitted)`. Daemons are killed as part of
participant readiness.

## Files

`defective.kt` (the fixture screen as participants receive it),
`reference-fix.kt` (the one-line reference fix, `text = b.total`),
`c5-dead-button.kt`, `CartPresenter.kt`, `c3.log`–`c6.log`,
`mcp-get_document-cart.json`, `mcp-find_usages-{subtotal,total}.txt`,
`c7-launch.png`, `c7-after-tap.png`.
