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
