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


# Run 1 results — 2026-09-05 (revised 2026-09-06 after review)

**Verdict: instrument validation was INCONCLUSIVE. No case was shown to
discriminate. Two cases were shown not to be valid tests. Nothing here
supports a claim about where semantics win.**

An earlier version of this section called F4 a strong result and proposed
redesigning the set around it. Both are withdrawn; see *Corrections* below.

## What was actually run, and what that can support

The cases were planted in a real screen in a `keliver-init` app, served by
the relay, and rendered in the portal editor. Captured artifacts are in
[`../evidence/m4-run1/`](../evidence/m4-run1/) — read `doc-response.json`
before trusting any claim about the semantic channel.

This run compared **what two channels display for one configuration**. It is
not the two-agent comparison the Method specifies, and it is not a detection
result:

- The author knew where every defect was, so no blind detection occurred.
- No baseline agent was run. The Method gives the baseline **source access
  and device builds**; neither was exercised.
- No device render, no interaction trace, no assertion that any planted
  defect actually manifests at runtime.

Consequently every statement below is about *artifact contents*, not about
whether a defect exists or whether an agent would find it.

## Per-case outcome

### F1 — event preserved as RawCode → proposed mechanism REJECTED

The premise was "renders but never fires". The mechanism is wrong: `RawCode`
preserves the original source verbatim — verified by diffing `home.kt` after
ingest — and the device path compiles the canonical `.kt`.

**But that is a source-based inference, not an observation.** The button was
never rendered on a device and never clicked. What is established is that the
*stated mechanism* (source discarded, handler unwired) does not occur. Whether
the control fires is untested.

It is also not silent: the canvas draws an explicit `RawCode` chip where the
Button would be, and the outline lists it.

### F2 — literal instead of a binding → UNDETERMINED, one configuration tested

- `doc-response.json` distinguishes `text=LIT("3 notes")` from
  `text=BIND(title)`.
- The canvas rendered `{title}` for the bound field — **but only because no
  mock was set for it.** `strB` resolves a `Bind` as
  `mocks[field] ?: "{field}"` (`PreviewBindings.kt`), so a mock equal to the
  literal makes both render identically in the portal as well.

The earlier claim that F2 "works only against a device screenshot" is
withdrawn: that generalised from a single unmocked configuration. Both
configurations need testing, and a source-reading baseline may find it in
either.

### F3 — prop unregistered in `@Schema` → cannot be planted

An unknown named argument is a Kotlin compile error at adopter level. The
failure belongs to schema authors, not to the consumers an agent works for.
Unchanged from the earlier version, and the one conclusion that holds.

### F4 — `forEach` over an empty list → NOT A DETECTION RESULT (withdrawn)

Previously called the strongest case. Withdrawn on two grounds.

**It did not detect anything.** The canvas rendered three mock rows. Nothing
in either channel establishes that the runtime list is empty, or that
emptiness violates a requirement — that is a fact about the presenter and the
data, not about the document. What was observed is that a preview renders
mocks, which is its documented purpose.

**The supporting claim was false.** The earlier text said the semantic tree
carries "the mock row count". It does not. `doc-response.json` contains no
mock values: the `Repeat` node carries only `items` and `item` field names.
Row counts live in `PreviewBindings.mocks`, editor-side state read by the
Bindings panel, not in the `UiDocument` that `/doc` returns. The "row count
(3)" cited earlier was read off the editor UI and misattributed to the tree.

### F5 — branch suppressed by a Condition mock → does not discriminate

Both channels show the branch exists; neither reveals the true runtime value
without executing the presenter. Unchanged.

## Corrections to the earlier version of this section

1. **"F4 is strong / shows where semantics win" — withdrawn.** It shows mock
   rendering, not detection, and its supporting claim about the tree carrying
   the row count was factually wrong.
2. **"Redesign around the F4 archetype" — withdrawn.** Recruiting more
   "the preview lies" cases selects for exactly the narrowed claim this
   document already warned about under *Honest weaknesses*: that Keliver can
   expose its own preview's limitations. That is a much smaller thesis than
   the one under test.
3. **"The fair baseline is an agent without the portal" — not a finding.**
   The Method already specifies exactly that. It was restated as though newly
   discovered; the actual gap is that the specified baseline was never run.
4. **F2's limitation was overstated** — see above.

## What run 2 requires before it can decide anything

- **A behavioural failure-and-fix check per case.** Each replacement case must
  demonstrate, at runtime, that the application misbehaves before the fix and
  behaves after it. A case that cannot fail observably is not a defect and
  does not belong in the set.
- **Independently verifiable application failures**, not preview/device
  divergence. Preserve the Method's baseline unchanged: source access, device
  builds, no portal.
- **Both mock configurations** for any binding-shaped case.
- **Captured artifacts per case**: device render, interaction trace, `/doc`
  response, and the failing assertion.

### Procedural safeguards for the blind run

Two ways this experiment can quietly invalidate itself. Both must be closed
before an agent sees anything.

**1. No answer leakage in participant fixtures.** The run-1 fixture archived
in `../evidence/m4-run1/planted-home.kt` labels each defect in a comment
(`// F2: literal that happens to equal…`) and the surrounding prose explains
the intended failure. That artifact is kept as the historical record of what
was actually run and must not be edited — but it is unusable as a participant
input. Build **separate, neutral fixtures**: no case labels, no explanatory
comments, defect sites indistinguishable from ordinary code, and plausible
decoys so "the commented bit is the bug" is not a strategy. Agents get the
fixture and the task only — never this document, never the evidence
directory.

**2. Scoring must not depend on the agent's own verification.** Write the
failing and passing checks **before** the run, from the requirement, and keep
them out of the agent's reach. Record each agent's verification claim, then
independently score correctness against the pre-authored checks. Compare
outcomes between the baseline and semantic conditions.

This is a safeguard for the comparison, not the hypothesis. The thesis under
test is the **incremental benefit of semantic access over source plus
build/run/screenshots** — not whether agents self-report accurately. Scoring
both conditions the same way is what keeps that comparison meaningful; a
self-reported "verified" from either side would corrupt it equally. (The gap
between an agent's claim and the check's result is worth recording as a
secondary observation, but it is not what the experiment is for.)

Corollary: a case whose check cannot be written in advance is not
well-specified enough to include.

Until those exist, the set cannot support a pass or a fail, and the
pre-registered 4-of-5 rule cannot be applied.

## Honesty note

The pre-registration did its job twice. It caught F1 — the most compelling
case on paper, and the one whose proposed failure mechanism was rejected —
before an agent loop was built on it. (Whether that control fires at runtime
is still untested; "not a defect" would overstate what was shown.) It
then caught this section's own overreach: having found one case that survived,
the instinct was to promote it to a result and rebuild the set around it,
which would have converted a null result into a narrower thesis quietly
substituted for the original one. Review caught that; the artifacts are now
archived so the next reader can check rather than trust.
