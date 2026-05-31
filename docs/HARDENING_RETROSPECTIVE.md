# Production-hardening retrospective

What we learned taking Konduit from "compiles and ships to Caliclan's
own apps" to "demonstrably tested, CI-enforced, adopter-ready." This
is a working retrospective — durable lessons + the concrete receipts
that earned them. Read it before the next big push so we don't
relearn these the hard way.

## The arc, in one paragraph

We shipped the codegen workstream (#18), a minimal `sample/`, and
performance baselines — then asked "is the whole of our dev complete
and tested?" *before* making a production claim. The honest answer
was no: the test suite didn't run on a clean checkout and **no CI job
ran tests at all**. Fixing that surfaced a chain of latent issues —
stripped fixtures, a package-rename migration miss, and a real codegen
bug — none of which a "does it compile" gate would ever catch. Net:
the suite went from silently-broken-and-unenforced to fully green,
comprehensive (22 inherited tests revived), and gated in CI.

## Learnings

### 1. "Compiles" ≠ "works." Verify by execution, every time.
The single most repeated lesson. Things that compiled cleanly were
broken the moment they actually ran:
- The `sample/` compiled on all platforms but its **first emulator
  run found 5 bugs** (Zipline plugin missing on `take`/`bind`
  modules, missing serialization plugin, the U12 adapter,
  silent-failure with no `EventListener`, a bogus README task).
- iOS "worked" (backed only by `compileKotlinIosSimulatorArm64`
  passing) — the **first simulator run found 3 more** (wrapper path,
  no `EventListener`, an `NSLog` varargs crash).
- The test suite "existed" — but `./gradlew test` **failed at
  configuration** and most of it didn't compile.

**Takeaway:** a green compile is necessary, not sufficient. Any
"done" claim needs an execution-backed receipt (a run, a screenshot,
a passing test), not a build log.

### 2. An unenforced test rots. Enforcement > existence.
All three CI workflows passed `-x test`; `apiCheck` wasn't wired
either. Result, undetected for months: a dangling task dependency, 22
non-compiling inherited tests, and 9 modules with no API baseline. The
tests *existed* the whole time — they just never ran.

**Takeaway:** if it isn't in CI, assume it's broken. Wire `test` +
`apiCheck` into CI the day you add them, not "later."

### 3. Forking leaves a long, quiet tail — audit string literals, not just imports.
Stripping upstream's `test-app` fixture (a deliberate, reasonable
cleanup) broke things far from the deletion:
- ~22 inherited tests across 7 modules silently stopped compiling.
- A dangling `dependsOn(":test-app:...")` failed the build outright.
- A test fixture (`FakeTreehouseView`) vanished with no compile error
  until something referenced it.
- Subtlest: the leak detector's heap-walker had a **string-literal**
  allowlist (`"app.cash"`). The `app.cash.redwood → dev.keliver`
  rename updated declarations and imports but not that data string,
  so the walker errored on `dev.keliver.…$spec$1`.

**Takeaway:** package renames and module strips need an audit of
string literals, reflection allowlists, hardcoded paths, and test
wiring — the compiler won't flag those.

### 4. Latent bugs hide behind features your own code doesn't use.
The schema codegen emitted a non-resolving `.serializer()` for stdlib
custom-type modifiers (`kotlin.time.Duration`, `kotlin.UInt`). It was
invisible because **keliver's own schemas don't use stdlib
custom-types** — only the comprehensive upstream `test-app` schema
did. Any adopter using a `Duration` modifier value would have hit it.

**Takeaway:** keep a deliberately-comprehensive test fixture that
exercises codegen paths your production modules happen not to. It's
the cheapest way to find adopter bugs before adopters do.

### 5. Adopter friction is invisible from inside the repo.
Each of these was a real day-one papercut, only visible by actually
walking the adopter path (via `sample/` and DevoStatus):
- The GitHub Packages PAT requirement (`gpr.user`/`gpr.token`).
- The Zipline [#765](https://github.com/cashapp/zipline/issues/765)
  manual `AppService` adapter (~95 LoC before we shipped the helper +
  KSP processor).
- The KSP `-2.0.x` API-line requirement (a misleading
  "too old for kotlin-X" error).

**Takeaway:** dogfood the adopter onboarding path on a clean machine
periodically. The maintainer never sees these because their
environment is already configured.

### 6. Incremental, self-contained delivery banks value and de-risks.
The U13 close was split: the codegen fix (independently valuable, with
regression tests) shipped as its own PR before the larger fixture
recovery. When the recovery hit drift, the fix was already merged and
safe.

**Takeaway:** when a fix is independently valuable, ship it on its own
— don't let it ride a larger, riskier change.

## The receipts — bugs found that a compile gate would miss

| Bug | How it surfaced | Why a build gate missed it |
|---|---|---|
| 5 sample wiring/serialization bugs | First Android emulator run | All compiled fine |
| 3 iOS bugs (incl. `NSLog` varargs crash) | First iOS simulator run | Compiled + linked fine |
| Test suite doesn't run on clean checkout | Test-completeness audit | CI never ran tests |
| `apiCheck` failing, 9 modules unbaselined | Same audit | apiCheck not in CI |
| Stdlib custom-type serializer codegen bug | Reviving the test-app fixture | Own schemas don't use the feature |
| Stripped `FakeTreehouseView` fixture | Reviving integration tests | Only a reference error, not a compile error until used |
| `JvmHeap` allowlist rename miss (`app.cash` vs `dev.keliver`) | Running leak tests | String literal, not an import |

## What changed because of this

- CI now runs `test` + `apiCheck` on every PR (was build-only).
- A gated, comprehensive inherited suite (`-PkeliverWithTestApp`,
  22 tests) runs in CI while keeping default builds lean.
- API binary-compatibility is baselined + enforced across all modules.
- `sample/` + DevoStatus are the standing dogfood of the adopter path,
  with `sample/TESTING.md` as the running case-study log.
- Known structural constraints are documented in
  [`KNOWN_BUGS.md`](./KNOWN_BUGS.md) (U12 adapter, U13 fixture, the
  KSP API-line note) rather than rediscovered.
