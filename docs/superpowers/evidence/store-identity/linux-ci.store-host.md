# Linux CI for the store/host correctness candidate

Four identities, recorded separately, because a GitHub run's `headSha` is the
SHA of the **workflow definition**, not of what an input-driven checkout built.
Conflating them is how a run gets credited to the wrong commit.

## Run 34791145004 — `c201521a3`

| what | value |
|---|---|
| workflow-definition SHA (`run.headSha`) | `c201521a3b60e44301590c4113336e4f561c15c5` (branch `fix/store-host-correctness`) |
| actual checkout SHA (`git rev-parse HEAD` in the job) | `c201521a3b60e44301590c4113336e4f561c15c5` |
| `VERSION.json.sourceCommit` | `c201521a3b60e44301590c4113336e4f561c15c5` |
| ZIP sha256 | `8ca7763e8127f0e8f880aaa33bf25affc27ed6d1960fe639f0444e2f0c6e20be` |
| conclusion | **success** |

Per-check on Linux: **disposable-parent refusal 35 / 0**, hygiene **10 / 0**,
adopter acceptance **19 / 0**, foreign-relay refusal **6 / 0**, identity contract
**11 / 0**, guest bundle signing **4 / 0**, store recovery **144 / 0**.

The refusal suite reports which filesystem it is on and asserts the property for
that one — here `allowed: a case variant, on a case-SENSITIVE filesystem`, with
`(filesystem is case-sensitive; both directions are asserted, neither skipped)`.
The case-insensitive branch, and with it the mkdir-then-refuse undo path, is
reachable only on a case-folding filesystem and so runs on macOS only.

`tools=0.3.4 maven=0.3.3` — the 0.3.5 bump is parked on
`release/portal-tools-0.3.5`. A development candidate, not a release.

## Run 34775999489 — `975b3e2c8`

| what | value |
|---|---|
| workflow-definition SHA (`run.headSha`) | `975b3e2c8bbdfaf95b7920f600f3a9fa0ada6361` (branch `fix/store-host-correctness`) |
| actual checkout SHA (`git rev-parse HEAD` in the job) | `975b3e2c8bbdfaf95b7920f600f3a9fa0ada6361` |
| `VERSION.json.sourceCommit` | `975b3e2c8bbdfaf95b7920f600f3a9fa0ada6361` |
| ZIP sha256 | `86623b924b6f05c356b220dd5e2a71aa49776438b1dedc343497ca1854445e9f` |
| conclusion | **success** |

Per-check on Linux: hygiene **10 / 0**, adopter acceptance **19 / 0**,
foreign-relay refusal **6 / 0**, identity contract **11 / 0**, guest bundle
signing **4 / 0**, store recovery **145 / 0**.

Evidence for `975b3e2c8` and nothing later: the signing wiring and the
disposable-parent refusal both changed after it (the refusal did not exist yet). The row for each run below says
what that run does and does not cover, and this one gets the same treatment.

`tools=0.3.4 maven=0.3.3` — the 0.3.5 bump is parked on
`release/portal-tools-0.3.5`. This is a development candidate, not a release,
and it does not replace the published 0.3.4 asset.

## Run 34774476035 — the previous head (`9a7f681ed`)

| what | value |
|---|---|
| workflow-definition SHA (`run.headSha`) | `9a7f681ed6fa5e4a5c047e621d202c01e3bc2b21` (branch `fix/store-host-correctness`) |
| actual checkout SHA (`git rev-parse HEAD` in the job) | `9a7f681ed6fa5e4a5c047e621d202c01e3bc2b21` |
| `VERSION.json.sourceCommit` | `9a7f681ed6fa5e4a5c047e621d202c01e3bc2b21` |
| ZIP sha256 | `39048704686b0ac0df77011fac1cc38a5f8bb1666e556b59d2961c3377476f6e` |
| artifact | `keliver-portal-tools-0.3.4-9a7f681ed…`, 90,354,570 bytes |
| conclusion | **success** |

### Per-check tallies on Linux at `9a7f681ed`

| check | result |
|---|---|
| unit tests (relay, mcp, **device-android**) | green; `HostTrustPolicyTest` 8 passed |
| device-host packaging hygiene (U22) | 10 / 0 |
| packaged adopter acceptance (U21) | 19 / 0 |
| foreign-relay refusal | 6 / 0 |
| store identity contract | 11 / 0 |
| **guest bundle signing** (new) | 4 / 0 |
| store recovery outcomes | 144 / 0 |

## Earlier runs, kept for their own commits

| run | workflow definition | checkout | result | what it means |
|---|---|---|---|---|
| 34749129526 | `4e4c46e72` (main) | `72ba9fc1e` | success | evidence for `72ba9fc1e` only |
| 34771348310 | `4e4c46e72` (main) | `8a702b230` | **failure** | evidence for `8a702b230` only — and the only executed proof that the hygiene override assertion can fail. Retained deliberately. |
| 34772824205 | `4e4c46e72` (main) | `bcff322eb` | success | **does not cover the signing gate.** Dispatched with `--ref main`, so the workflow *definition* came from main, which has no `guest bundle signing` step. The checkout was `bcff322eb`, so the scripts were the branch's — but the step list was not. |
| 34773606065 | `bcff322eb` (branch) | `bcff322eb` | success | first run that actually executed the signing gate (4 / 0) |
| 34774476035 | `9a7f681ed` (branch) | `9a7f681ed` | success | evidence for `9a7f681ed` only; the signing wiring changed again after it |
| 34789127731 | `aa201f42e` (branch) | `aa201f42e` | **failure** | the refusal suite asserted the macOS case-folding answer on Linux. Evidence for `aa201f42e` only, and the first proof the suite can fail. |
| 34789552295 | `47822ebf4` (branch) | `47822ebf4` | success | first run of the platform-aware case assertion |
| 34790322143 | `4b5502a37` (branch) | `4b5502a37` | **failure** | the same macOS-answer mistake, in the newly added end-state assertions. Evidence for `4b5502a37` only. |

That fourth row is the reason these four identities are recorded separately. A
run can check out exactly the commit you asked for and still execute a different
set of steps.

## The ZIP hash identifies an artifact, not a commit

Runs 34772824205 and 34773606065 built the **same commit** `bcff322eb` and
produced **different** ZIP hashes — `0bcf0966…` and `a5c73970…`. The tools build
is not byte-reproducible (`docs/PORTAL_TOOLS_RELEASE.md`), which is why a
release asset is attached from the named retained artifact that was verified and
never from a rebuild at the tag.
