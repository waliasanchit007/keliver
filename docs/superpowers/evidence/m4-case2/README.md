# M4 case 2 — wrong-field binding

A pre-registered, qualified, paired comparison on one case. Read in order:

| document | what it holds |
|---|---|
| [PREREGISTRATION.md](PREREGISTRATION.md) | requirement, planted defect, check, inclusion criteria, pinned configuration, measures, failure handling — committed **before the fixture existed** |
| [QUALIFICATION.md](QUALIFICATION.md) | all eight inclusion criteria executed; what the MCP response actually exposes |
| [RESULT.md](RESULT.md) | **the paired comparison** |
| [DIAGNOSTIC.md](DIAGNOSTIC.md) | a **separately labelled** exercise, not part of the comparison |

## The one-line summary

Both conditions produced the identical correct fix and both were independently
scored PASS; the unfixed control FAILs the same check. The semantic participant
was told the portal tools existed and how to find them, and **never looked** —
no tool-search call, no MCP call.

A third, separately labelled participant *required* to diagnose through the
tools did so successfully before reading any screen source, showing the queries
expose useful binding information. It is not evidence about the comparison, is
not merged with it, and does not show that using the tools improves outcomes.

A follow-on study ([`../m4-discovery/`](../m4-discovery/)) put the tools in the
listed tool set for three further runs; they were still not used. That does not
establish why *this* participant did not use them.

## Scale of the case

Three source files (`screens/cart.kt`, `logic/CartPresenter.kt`,
`device/Main.kt`). The app was deliberately **not** padded with irrelevant
files to make source reading expensive.

## What this case is not

Not a demonstration that semantics win, and not selected to be one. The
inclusion criteria are all about the fixture's own behaviour; none refers to a
predicted advantage. A completed baseline win is reported as a valid
observation, not explained away.
