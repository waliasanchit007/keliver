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

## What a stronger signal looks like

If the UI has a **semantic representation** — a real tree describing what
each node is, which props are literal versus bound, which events resolve
to which handlers, what the presenter's state actually holds — then every
one of those five failures is directly observable. Not inferred from
pixels. Read.

- Is `onClick` wired, or preserved as unrecognised code? *Read it.*
- Is that label bound to a field, or a literal? *Read it.*
- Does the catalog even have that prop? *Ask it.*
- Are those three rows real data, or preview mocks? *Different fields.*
- Is the branch missing because of state, or a preview flag? *Both are in
  the tree.*

This is what Keliver is. Screens are written once in Kotlin, compiled to a
bundle, and rendered with native widgets on Android and iOS. That part is
ordinary server-driven UI. The part I care about is that the same screens
have one semantic representation shared by the running app, a visual
editor in the browser, and an agent over MCP — and that editing any of
those three writes back to the same `.kt` files in git, as a surgical
diff rather than a generated blob.

The cross-platform story is the enabling technology. The point is that
there is finally something for an agent to *read back*.

## How I'd know I'm wrong

I don't think you should believe the above yet, because I haven't proven
it. So here's the disproof I've committed to before running it.

Take the five failure classes. Plant each in a real screen. Give the same
task to two agents: one with a build, an emulator, and screenshots; one
that can additionally read the semantic tree. Score detection, not fixing
— fixing is easy once you can see the problem.

**If the screenshot agent catches four of five, this thesis is much weaker
than it sounds and I should say so.**

Two of the five are arguably unfair — they exploit the gap between preview
and device, and an agent screenshotting the real device sidesteps both. So
that's how I'll run the baseline. Another two might be catchable from
source alone by an agent that greps carefully, which is a legitimate win
for the baseline and gets counted as one.

That leaves the literal-instead-of-binding case as the cleanest test:
identical pixels, no textual tell, and it's the single easiest way for a
generating agent to pass a visual check while having wired nothing.

Five planted defects in one codebase is a small sample and I'll treat a
narrow split as noise. But it's a real experiment with a pre-registered
failure condition, which is more than "it feels faster."

## Where this actually is

Pre-1.0, public on Maven Central, one maintainer. Kotlin → browser →
Android → iOS works today; you can open the editor in a browser with no
install. There is currently **no external adopter** — the reference app
this was built against was lost with a dead laptop and never pushed,
which is its own lesson.

So this is not a pitch. The verification loop above is not built yet;
what exists is the representation that would make it possible, and a
falsification plan I intend to run honestly.

If you build mobile UI and you've watched an agent confidently converge
on something wrong, I'd like to hear which of the five you've hit — and
which one I'm missing.

---

*Keliver is a maintained fork of Cash App's Redwood.
`github.com/waliasanchit007/keliver`. Browser playground, no install:
`keliver.me/keliver`.*
