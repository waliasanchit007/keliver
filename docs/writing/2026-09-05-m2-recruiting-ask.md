# M2 recruiting ask — one developer who isn't me

**Draft — not sent. Written 2026-09-05.**
For: one mobile engineer, one colleague in a sandbox, one person with a
side project. **Not a team, and not several people at once** — the point is
depth of observation, not sample size.

## Do not send until the release gate passes

Pointing someone at a version they cannot download is the exact failure this
milestone exists to catch. A green publish workflow does **not** establish
availability, and one resolvable POM does not establish that the scaffold
builds. The gate is:

1. **Every coordinate the scaffold needs returns 200** on Central — not just
   `keliver-host`. `keliver-init` generates dependencies on
   `keliver-material-compose`, `keliver-layout-compose` and `portal-sql`:
   ```bash
   for a in keliver-host keliver-guest keliver-material-compose \
            keliver-layout-compose portal-sql; do
     curl -s -o /dev/null -w "$a %{http_code}\n" \
       "https://repo1.maven.org/maven2/dev/keliver/$a/0.3.3/$a-0.3.3.pom"
   done
   ```
2. **A clean scaffold resolves and compiles from Central alone.**
   ```bash
   env -u KELIVER_USE_MAVEN_LOCAL KELIVER_VERSION=0.3.3 keliver-init Gatecheck
   cd gatecheck
   GRADLE_USER_HOME=$(mktemp -d) ./gradlew compileKotlinJs
   ```
   Two hazards, both of which silently produce a false pass:

   - **`env -u` is not decoration.** `keliver-init` injects `mavenLocal()`
     into the generated `settings.gradle` when `KELIVER_USE_MAVEN_LOCAL=1`,
     at scaffold time. An inherited `1` therefore bakes a local repository
     into the project, and a fresh Gradle cache does not undo it — the repo
     list itself is wrong. Merely *documenting* "must be unset" is not
     enough; unset it in the command. (Sessions that ran the earlier dogfood
     have this exported.)
   - **A fresh `GRADLE_USER_HOME`** because this machine's `~/.m2` holds a
     locally published `0.3.3` from the release preflight, and a warm Gradle
     cache would serve it. Resolving the graph from Central is the point; a
     cached hit proves nothing.

   Pinning `KELIVER_VERSION=0.3.3` makes the check independent of whichever
   `keliver-init` happens to be on `PATH`.

Only when step 2 is green is 0.3.3 adopter-ready. Until then this document
stays unsent.

---

## The short version (paste into a DM)

> I've built a thing called Keliver — you write a mobile screen once in
> Kotlin, it renders as native widgets on Android and iOS, and you can edit
> it visually in a browser where the changes land back in the same `.kt`
> file as a normal git diff.
>
> It has never been used by anyone except me, and I've just spent a day
> finding out that most of what breaks is invisible from the inside.
>
> Would you build one small real feature with it — a couple of hours, in a
> throwaway project — and write down every single place you get stuck?
>
> **The stuck bits are the deliverable.** I don't need you to finish. I need
> to know where it stops making sense. Getting stuck is a successful outcome
> and it is never your fault.

---

## The longer brief

### What you'd actually do

1. Grab the tools bundle from the latest GitHub release, run `keliver-init`
   to scaffold a project, and open the portal.
2. Build **one feature you'd plausibly ship** — a list that loads from an
   API and has an empty state, a form with validation, a detail screen you
   can navigate to. Your choice. Something with data and a state you have to
   think about, not a static page.
3. Stop after ~2 hours whether or not it works.

### The rules — these are the whole experiment

**Do not ask me for help while you're working.** This is the hard part and
it is the entire point. Every question you'd want to ask me is a thing the
framework or its docs failed to tell you, and if you ask, the failure
disappears from the record.

Instead, keep a running note. One line per stumble, in whatever form is
cheapest for you. Timestamps help but aren't required.

If you are properly, permanently stuck — 20 minutes with no progress — then
message me. Note that you did. A hard block is data too, just a different
kind.

**Do not smooth over the rough bits when you report back.** The polite
instinct — "it was fine, just took me a minute to figure out X" — destroys
the signal. X is the finding.

### What I'm measuring

Every stumble sorts into one of four buckets, and they imply completely
different fixes:

| what you thought | what it means |
|---|---|
| "How do I…?" | missing documentation |
| "I don't understand why…" | bad abstraction |
| "This seems more complicated than it should be" | DX problem |
| "I can't do…" | genuine capability gap |

The headline number is how many times you needed me. **The target is
zero**, and I fully expect it not to be.

### What's honestly broken right now

You should know what you're walking into. Stating it up front isn't
modesty — an unpleasant surprise you were warned about costs far less
goodwill than one you weren't.

- Pre-1.0, single maintainer, **no other users**. You'd be the first.
- I found six defects today that only appear from outside my own repo,
  including the portal writing stray files into your source tree when you
  opened the editor. Those are fixed. I would bet on more of the same kind.
- If you're on a corporate network with TLS inspection, the Kotlin/Wasm
  build fails claiming an npm package doesn't exist. It does exist; it's a
  certificate. Tell me and I'll point you at the fix — that one is
  documented and doesn't count as a stumble.
- Docs are decent as reference and thin as a path. That's part of what
  you'd be testing.

### What's in it for you

I want to be straight rather than oversell, because you're doing me the
favour.

- A couple of hours with an unusual take on mobile UI: one semantic
  representation shared by the running app, a visual editor, and an AI
  agent over MCP — with round-tripping to real Kotlin rather than generated
  blobs.
- Every friction you find gets fixed, with credit, and you'll see the diff.
- If it turns out to be genuinely useful to you, you'll know how to use it
  before anyone else does. If it turns out not to be, you've cost yourself
  an afternoon and saved me building on a false premise, which is worth
  more to me than a polite yes.

### What I will not do

Sit next to you. Fix things live over your shoulder. Explain the
architecture before you start. All of those make the result useless — they
turn a measurement of the framework into a measurement of my availability,
and my availability is exactly what should not be required.

---

## Notes for me, not for them

- **Hand-recruited people are polite and motivated.** This produces proofs
  2 and 3 of the decision gate (technical, UX) but structurally **cannot**
  produce proof 4 (demand). Do not read a friendly "yeah this is neat" as a
  demand signal. Demand is someone asking *"can I use this?"* unprompted —
  better, *"can my team?"*; best, *"how much?"*
- Resist fixing things mid-run, however painful it is to watch. Write it
  down, ship it after.
- Record the intervention count and the four-way classification in
  `DOGFOOD_NOTES.md` alongside Dogfood 2 and 3, so the self-run and the
  real-run sit next to each other and the gap between them is visible.
- One person, properly observed, beats three people skimmed.
