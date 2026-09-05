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

Here are four ways a mobile UI change can be wrong while looking perfect.

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

**Two of the five did not survive as tests.** My favourite case assumed a
handler the parser can't recognise would render but never fire. That
mechanism is wrong: the unrecognised source is preserved verbatim and the
device build compiles it, so the handler is not discarded the way I'd
claimed. Whether the button actually fires I never checked — I inferred it
from reading the source, which is the same shortcut this essay is about, so
I'm not going to assert it. What is clearly lost is *editability in the
portal*, which is deliberate and documented. Another case couldn't be
planted at all: the failure it described is a compile error in any real
app.

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

## Then I got the diagnosis wrong too

I concluded from all this that the experiment was impossible: that the
agent-facing tools were purely static, so there was no way for semantics to
catch a runtime failure a screenshotting baseline would miss. I wrote it up
as a structural finding.

It was wrong three ways, and review caught all three.

I'd built my inventory of the agent tools with a search pattern that assumed
how they'd be named, then reported the filtered result as complete. It
missed one that takes a screenshot of the connected device — a runtime tool,
sitting in the list I'd just declared static.

Worse, I'd quietly changed the experiment. My own design gives the semantic
agent the portal **in addition to** everything the baseline has: source,
builds, execution, screenshots. I wrote as though it had only the document,
then derived impossibility from a restriction I'd invented.

And the underlying inference doesn't hold anyway. That a fact is *derivable*
from source doesn't tell you how quickly or reliably an agent will find it
under limited attention — which is exactly the difference the experiment
exists to measure. I'd dismissed the cross-reference tool as "grep" when it
actually walks typed binding and action nodes; that distinction is the thing
under test, and I'd defined it away.

What survives is narrow and worth stating plainly: **Keliver exposes static
semantic queries and device screenshots, but no structured API for a running
presenter's state. Whether its existing tools help an agent find defects
faster is untested.** Not disproved. Untested — I have not yet built a single
fixture that could measure it.

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
something wrong — I'd like to know which of the four failure modes you've
hit, and which one I'm missing. Especially the last part.

---

*Keliver is a maintained fork of Cash App's Redwood.
`github.com/waliasanchit007/keliver`. Browser playground, no install:
`keliver.me/keliver`.*
