# M4 falsification set — can semantics beat screenshots?

**Date:** 2026-09-05
**Status:** design, not yet executed
**Purpose:** decide whether the M4 AI-verification loop is worth building,
*before* building it.

## The claim under test

> AI can write mobile code. Keliver lets it see whether the code actually
> worked.

The mechanism that is supposed to make that true: an agent reads a
**semantic tree** — the `UiDocument`, its bindings, the catalog, live
presenter state — rather than a rendered image. The bet is that this
catches classes of failure a screenshot-driven agent cannot see.

That bet is falsifiable, and it should be falsified cheaply. The baseline
— a screenshot-driven agent looping on build output in Android Studio —
is improving on someone else's schedule, so "better than Claude/Codex +
Android Studio" is a moving target. Pick the tasks now, while nobody is
invested in the answer.

## Method

Five tasks. Each has a defect planted in a real screen. Two agents get
the same task and the same repo:

- **Baseline** — may build, run, and screenshot the app; may read source;
  may not use the portal or MCP.
- **Semantic** — additionally has `portal-mcp`: `get_catalog`,
  `get_document`, `apply_ops` (transactional, `dryRun`), `find_usages`,
  `list_screens`, `get_guide`.

Each agent must (a) decide whether the requirement is met and (b) if not,
fix it and prove the fix. Score per task: **detected / fixed / verified
its own fix**. Detection is the discriminating measure — fixing is
usually easy once you can see it.

**The decision rule, fixed in advance: if the baseline detects 4 or 5 of
5, the thesis is materially weaker than it looks and M4 should not be
built as scoped.** Write the result down either way.

## Why these five

Every one is a case where **the pixels are innocent** — a screenshot of
the running screen looks exactly like a correct implementation. They are
not invented; each maps to a failure mode this repo has already recorded.

### F1 — A binding that renders but never fires

A `Button` whose `onClick` is present in source but shaped so the portal
cannot recognise it as one of the three legal event forms, so it is
preserved as `RawCode` rather than wired to a `Bindings` action.

*Screenshot:* a normal, well-styled button.
*Semantics:* the event is `RawCode`; the action does not appear in the
generated contract; `find_usages` shows no call site.
*Grounded in:* `PORTAL_USAGE.md` — "Events accept exactly three shapes —
anything else becomes `RawCode` on purpose."

### F2 — State that looks right and isn't

A `Text` whose value is a **literal** that happens to equal what the
presenter would have produced, instead of a binding to the presenter
field.

*Screenshot:* correct string on screen.
*Semantics:* the prop is literal, not bound; the presenter field has no
consumer.
*Why it bites:* this is the single easiest way for a generating agent to
"pass" a visual check while having wired nothing. It will also pass a
screenshot diff against the design.

### F3 — A prop the codegen silently dropped

A widget prop or modifier that exists on the composable but was never
added to the `@Schema` members annotation, so codegen ignores it and the
widget renders with its default.

*Screenshot:* plausible — a default value is still a value.
*Semantics:* the prop is absent from `get_catalog`, so the document
cannot carry it.
*Grounded in:* `CLAUDE.md`, verbatim — "a new schema widget/modifier MUST
also be listed in the `@Schema` members annotation or codegen silently
ignores it." A documented silent failure is the fairest possible test.

### F4 — A preview that is lying about list data

A `forEach` over a list that is empty at runtime. The preview renders
**3 mock rows** by default.

*Screenshot (preview):* a populated, healthy-looking list.
*Reality on device:* nothing.
*Semantics:* the row count is mock-derived, not data-derived; the items
field's mock is what is driving the render.
*Grounded in:* `PORTAL_USAGE.md` — "the preview renders **3 mock rows**
per `forEach` by default." Note this one is *only* visible if the agent
knows preview ≠ device; a screenshot agent pointed at the preview is
maximally misled, which is exactly the interesting case.

### F5 — A branch suppressed by a mock, not by logic

An `if` whose `Condition` field is mocked `false`, hiding a branch in
preview while the real condition would be true.

*Screenshot:* a screen with no visible problem — the missing branch
leaves no hole.
*Semantics:* both branches exist in the document; one is suppressed by a
preview mock rather than by presenter state.

## Honest weaknesses of this set

Say these out loud rather than discovering them in the result:

- **F4 and F5 are partly unfair to the baseline** — they exploit
  preview/device divergence, and a baseline agent screenshotting the
  *device* rather than the preview may sidestep both. Run the baseline
  against the device build to keep it honest. If the semantic agent only
  wins on F4/F5, the thesis is "our preview can mislead you and our tree
  can un-mislead you", which is a much smaller claim.
- **F1 and F3 may be findable from source alone.** A careful baseline
  agent that greps the schema could catch F3 without any rendering. That
  is a legitimate win for the baseline; count it.
- **F2 is the cleanest test** and probably the one that matters most —
  there is no textual tell, and the pixels are identical.
- Five tasks is a small sample; treat a 3-2 split as noise, not evidence.

## What a pass actually licenses

Only this: that a semantics-reading agent detects defects a
screenshot-reading agent misses, on five planted cases, in one codebase.
It does **not** establish that agents build features faster, that the
loop generalises, or that anyone wants it. Those are M2 and decision-gate
questions, not M4 questions.

## Prerequisite

The loop needs a screen whose defects can be planted and reverted
cheaply. The in-repo `sample/` and the portal dogfood app both qualify;
`stashfin-sdui` — the app most of the recorded gates used — does not
exist any more (see *Post-snapshot: machine loss and restart* in
`CURRENT_STATE.md`).

---

# Run 1 results — 2026-09-05

**Verdict: the set as designed cannot decide the question. Two of the five
cases are not defects at all, one is inapplicable, one is weak, and one is
strong. Redesign before building M4.**

## What was actually run

The five cases were planted in a real screen in an app scaffolded by
`keliver-init`, served by the relay, and rendered in the portal editor.
Both channels were captured for the same screen: the rendered canvas, and
`GET /doc`.

This measures **information availability** — is the defect present in each
channel — not agent skill. It is a necessary condition for the semantic
agent's advantage and an upper bound on the screenshot agent's. It is *not*
the two-agent comparison the design calls for: the author knew where the
defects were, so no honest blind detection was possible in this run.

## Per-case outcome

### F1 — event preserved as RawCode → **INVALID, not a defect**

The premise was "renders but never fires". It is wrong. `RawCode` preserves
the original source verbatim, and the compiled device path compiles the
canonical `.kt` — so the button renders *and fires correctly at runtime*.
What is lost is portal-editability, which is a documented, deliberate
property, not a silent failure.

It is also loud rather than silent: the canvas draws an explicit `RawCode`
chip where the Button would be, and the outline lists
`RawCode "Button( text = "Re…"`. A screenshot agent would notice the button
was missing before a semantic agent finished parsing.

Verified: after ingest, `home.kt` still contains the Button unchanged.

### F2 — literal instead of a binding → **conditional discriminator**

- Semantic tree: unambiguous — `text=LIT(3 notes)` beside `text=BIND(title)`.
- **Portal canvas: also visible.** In mock mode a bound field renders as a
  `{title}` placeholder, so bound and literal look different on screen.
- Device screenshot with real data: indistinguishable.

So F2 discriminates against a *device* screenshot, and not against Keliver's
own canvas. That distinction was not in the original design.

### F3 — prop dropped because it is unregistered in `@Schema` → **inapplicable**

At adopter level this cannot happen silently: an unknown named argument is a
Kotlin compile error. The failure mode is real but belongs to framework and
schema authors, not to the consumers an agent would be working for. It
cannot be planted in the codebase the experiment targets.

### F4 — `forEach` over an empty list → **strong, and the preview is the liar**

The canvas rendered three tinted mock rows ("Title 1/2/3") for a list that is
empty at runtime, and the Bindings panel labelled it `items: rows — row
count (3)`. An agent screenshotting the preview does not merely fail to see a
problem; it sees a populated, healthy list that does not exist.

The semantic tree carries `Repeat items=LIT(items)` plus the mock row count,
so the mock-versus-data distinction is recoverable.

This is the one case that works as designed.

### F5 — branch suppressed by a Condition mock → **weak**

The canvas rendered the branch; the tree shows the `Condition` node exists.
Both channels show the same thing, and neither reveals the true runtime value
without executing the presenter. It does not discriminate.

## The meta-finding, which matters more than the tally

**The portal canvas is not a naive screenshot.** It is a semantically
enriched render: `{field}` placeholders for bindings, tinted mock rows,
explicit `RawCode` chips, a Bindings panel naming every field and its mock.
Much of what the semantic tree "uniquely" exposes is already drawn on screen.

So "screenshot versus semantic tree" is the wrong axis *within Keliver*. A
fair baseline is a screenshot loop that does **not** have the portal — an
agent driving a device build, or a competing tool — because that is the
actual alternative a mobile engineer would use.

## Consequence for M4

Do not build the loop against this set. Specifically:

1. Drop F1 and F3. One is not a defect; the other cannot be planted.
2. Keep F4, and treat it as the archetype: cases where the *preview itself
   asserts something false* are where semantics win, because there the
   screenshot is not neutral but actively wrong.
3. Re-scope F2 and F5 against a device render rather than the canvas.
4. Find at least three more cases in the F4 mould before running anything.

## Honesty note

The pre-registered rule was "if the baseline detects 4 or 5 of 5, the thesis
is materially weaker than it looks". That rule cannot be applied, because two
cases were never valid tests and no blind baseline was run. The correct
reading is neither pass nor fail: **the instrument was miscalibrated, and
running it early is what revealed that** — at the cost of an afternoon rather
than a built agent loop.

Worth noting what would have happened otherwise: F1 was the case that felt
most compelling when written, and it would have produced a confident, wrong
demonstration of a defect that does not exist.
