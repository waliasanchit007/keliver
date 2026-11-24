| module | samples | COMPILES | MS4 | EXP | NOPR | NEVER | UNMAPPED | ANDROID |
|---|---|---|---|---|---|---|---|---|
| material3 | 179 | 10 | 136 | 0 | 12 | 4 | 0 | 17 |
| foundation-layout | 124 | 14 | 43 | 36 | 0 | 8 | 0 | 23 |
| foundation | 165 | 9 | 64 | 4 | 28 | 42 | 2 | 16 |
| animation | 49 | 1 | 27 | 2 | 6 | 7 | 0 | 6 |
| animation-core | 37 | 0 | 22 | 7 | 0 | 6 | 0 | 2 |
| ui | 141 | 7 | 17 | 4 | 25 | 69 | 0 | 19 |
| **all** | 695 | 41 | 309 | 53 | 71 | 136 | 2 | 83 |

MS4-class samples by PR they need (a sample may need several):

- MS4-12: 115 (only this PR: 52)
- MS4-18: 78 (only this PR: 12)
- MS4-19: 75 (only this PR: 3)
- MS4-15: 49 (only this PR: 10)
- MS4-39: 43 (only this PR: 0)
- MS4-16: 43 (only this PR: 12)
- MS4-20: 39 (only this PR: 19)
- MS4-17: 34 (only this PR: 7)
- G1: 32 (only this PR: 5)
- G3: 25 (only this PR: 1)
- MS4-09: 20 (only this PR: 1)
- MS4-13: 19 (only this PR: 1)
- MS4-38: 18 (only this PR: 1)
- MS4-16..18/12 (overloads, parameters): 8 (only this PR: 8)
- G1/G3: 7 (only this PR: 0)
- MS4-16..18 (unlisted M3 name): 7 (only this PR: 0)
- MS4-21: 5 (only this PR: 0)
- MS4 (prototype stub:  sticky headers are not in the generated API yet (Ga): 2 (only this PR: 1)
- MS4 (prototype stub:  'overflow': a androidx.compose.foundation.layout.Fl): 1 (only this PR: 0)
- MS4-14: 1 (only this PR: 1)
- MS4 (prototype stub:  'windowInsets': its default: `TopAppBarDefaults.win): 1 (only this PR: 0)
- trivial (pure-Kotlin ui-util: keep the real library, do not rename): 1 (only this PR: 0)

Reasons for the worse classes:

- UNMAPPED: androidx.compose.foundation.text.input.forEachChange: 1
- UNMAPPED: androidx.compose.foundation.text.input.forEachChangeReversed: 1
- EXP: FlexBox (1.12, ExperimentalFlexBoxApi): 27
- EXP: Grid (1.12, ExperimentalGridApi): 9
- EXP: Style (1.12, ExperimentalFoundationStyleApi): 6
- EXP: keyframesWithSpline / arc (ExperimentalAnimationSpecApi): 6
- EXP: media queries (ExperimentalMediaQueryApi): 4
- EXP: deferred transitions (ExperimentalDeferredTransitionApi): 3
- NOPR: focus: 20
- NOPR: gesture state with guest callbacks (draggable/scrollable/anchored): 18
- NOPR: semantics: 10
- NOPR: size/position callbacks (onSizeChanged, onGloballyPositioned): 7
- NOPR: M3 1.4 additions not in MS4-16..18 (carousel, wide rail, loading indicator): 7
- NOPR: shared elements (C7: in no PR): 6
- NOPR: text selection state (1.12): 5
- NOPR: custom Indication (guest implementation): 3
- NOPR: AnnotatedString inline content (C7: in no PR): 1
- NEVER: custom layout (measure in guest): 44
- NEVER: guest pointer code (pointerInput): 40
- NEVER: Modifier.Node API (guest node code): 37
- NEVER: host locals / layout coordinates read synchronously: 23
- NEVER: per-keystroke guest code (#312: outside the mechanism): 8
