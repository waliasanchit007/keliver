# The pixels are innocent

**Draft — not published. Written 2026-09-05.**
Audience: mobile engineers who already use coding agents daily.
Goal: reach people who owe this project nothing (see the distribution gap
in `CURRENT_STATE.md`). Publish under your own name once you're happy
with it.

---

Ask a coding agent to add an empty state to an Android screen and it will
do a decent job. Ask it whether the empty state *works* and it will show
you a screenshot.

That is the part that hasn't been solved. Generation got good. Verification
didn't. And on mobile specifically, the loop between "the agent wrote
something" and "I know whether it was right" still runs through a build, an
emulator, and a human looking at a picture.

The picture is the problem.

## A screenshot is a weak oracle

Here are five ways a mobile UI change can be wrong while looking perfect.
None are hypothetical; each is a failure mode I've hit building a
server-driven UI framework.

**The button that doesn't do anything.** The agent writes an `onClick`. It
compiles. It renders. The handler is shaped in a way the surrounding
machinery doesn't recognise, so nothing is wired to it. The screenshot
shows a beautiful button.

**The label that isn't bound.** The agent needs to display
`user.displayName`. It writes the literal `"Sanchit Walia"` because that's
what was on screen in the design. Correct pixels. Correct string. Zero
wiring. This one also survives a screenshot diff against the mock, which
is how it gets merged.

**The prop that was silently dropped.** A widget property gets set in
source but never registered with the code generator, so it's ignored and
the widget falls back to its default. A default is still a plausible
value. Nothing errors. Nothing is red.

**The list that's full of nothing.** A preview renders three placeholder
rows because that's what previews do. At runtime the list is empty. The
screenshot of the preview is a populated, healthy-looking feed.

**The branch that was never there.** A conditional is suppressed by a
preview flag rather than by real state. The missing branch leaves no
hole — a screen with something absent looks exactly like a screen that
was supposed to look that way.

In every case the pixels are innocent. They faithfully render a broken
thing. An agent looping on screenshots will iterate to convergence and
report success, because by its own oracle it succeeded.

This isn't an argument that agents are bad at mobile. It's an argument
that we handed them the one signal that can't distinguish these cases.

## What a stronger signal would look like

If the UI has a **semantic representation** — a real tree describing what
each node is, which props are literal versus bound, which events resolve to
which handlers — then some of those failures stop being guesswork. Not
inferred from pixels. Read.

- Is that label bound to a field, or a literal? *Read it.* One says
  `BIND(title)`, the other `LIT("3 notes")`.
- Did the parser even recognise this widget, or preserve it as opaque
  source? *Read it.*
- Does the catalog have that prop at all? *Ask it.*

That is what Keliver has: screens written once in Kotlin, rendered as native
widgets on Android and iOS, with one semantic document shared by the browser
editor and by an agent over MCP — and edits from any of them writing back to
the same `.kt` file as a surgical diff rather than a generated blob. I
checked that last part end to end this week: one `SetProp` over the API
changed exactly one line of the file, comments and hand-written interfaces
untouched.

Now the part that costs me the pitch.

## I pre-registered a test of this and it failed in a way I didn't expect

I wrote down five planted defects before building anything, with a rule
fixed in advance: if a screenshot-driven agent caught four of five, the
thesis was weaker than it sounded and I'd say so.

The set never got that far. It collapsed on contact, and not where I
expected.

**Two of the five were not defects.** My favourite case — an event handler
the parser can't recognise — assumed the button would render but never
fire. Wrong: the unrecognised source is preserved verbatim and the device
build compiles it, so the button works fine. What's lost is *editability in
the portal*, which is a deliberate, documented property. Another case
couldn't be planted at all, because the failure it described is a compile
error in any real app.

**One case I called a strong win, then withdrew.** A list preview renders
three placeholder rows even when the real list is empty, so a screenshot of
the preview shows a healthy feed that doesn't exist. True, and misleading —
but it isn't *detection*. Nothing in the document says the runtime list is
empty. I'd also claimed the semantic tree carried the mock row count; it
doesn't. I'd read that off the editor's UI panel and attributed it to the
API. My own captured response disproved me.

The pattern in my errors is the same one I opened this essay complaining
about. Each mistaken case was a fact about *my tooling* — how the parser
classified something, what the preview chose to draw — that I mistook for a
fact about the application under test.

## The structural problem underneath

Then the real blocker. The baseline agent in my design gets source access,
plus build and run and screenshots. The semantic agent gets the document.

But the document is *derived from* that source. Cross-referencing usages is
grep. The catalog describes a public framework. And the one thing the
baseline has that the document doesn't — **what the running application is
actually doing** — isn't in the agent's reach at all. Keliver has a state
inspector that watches a live presenter's values, and it lives entirely
inside the browser editor's UI. No API serves it to an agent.

So the sentence I opened with — *lets it see whether the code actually
worked* — is about runtime, and the runtime channel isn't wired to agents
yet. The capability the pitch rests on isn't implemented on the path that
would use it. That isn't a falsified thesis; it's an experiment scheduled
against something that doesn't exist. The honest smaller claim that survives
is that a normalised parse makes certain static facts cheap and reliable to
extract at scale — useful, and much less exciting.

## Where this actually is

Pre-1.0, public on Maven Central, one maintainer, **no external adopter**.
The reference app it was built against was lost with a dead laptop and never
pushed, which is its own lesson. The week I spent getting back to a clean
build turned up nine defects that were invisible from inside my own repo,
including the portal writing stray files into a user's source tree the
moment they opened the editor.

So: not a pitch. What exists is a semantic representation that round-trips
to real Kotlin, a verification loop that is *not* built, and a falsification
plan that has so far mostly falsified my own instrument.

If you build mobile UI and you've watched an agent converge confidently on
something wrong — I'd like to know which of the five failure modes you've
hit, and which one I'm missing. Especially the last part.

---

*Keliver is a maintained fork of Cash App's Redwood.
`github.com/waliasanchit007/keliver`. Browser playground, no install:
`keliver.me/keliver`.*
