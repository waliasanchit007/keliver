# Linux CI for the store/host correctness candidate

Four identities, recorded separately, because a GitHub run's `headSha` is the
SHA of the **workflow definition**, not of what an input-driven checkout built.
Conflating them is how a run gets credited to the wrong commit.

## Run 34914450783 — `7184cf467`, the candidate

| what | value |
|---|---|
| workflow-definition SHA (`run.headSha`) | `7184cf467d4a6f938c8ccf1d3dd18ecfe83fd5f5` (branch `fix/store-host-correctness`) |
| actual checkout SHA (`git rev-parse HEAD` in the job) | `7184cf467d4a6f938c8ccf1d3dd18ecfe83fd5f5` |
| `VERSION.json.sourceCommit` | `7184cf467d4a6f938c8ccf1d3dd18ecfe83fd5f5` |
| ZIP sha256 | `717dadb3c3cd5c10fde90a1f9d65401ec65b0b5935ea670ff5770394ff0cc851` |
| conclusion | **success** (`bundle`; `device` skipped, no device input) |

Per-check on Linux: disposable-parent refusal **106 / 0**, hygiene **10 / 0**,
adopter acceptance **19 / 0**, foreign-relay refusal **6 / 0**, identity contract
**11 / 0**, guest bundle signing **4 / 0**, store recovery **144 / 0**.

The refusal suite went 93 → 106: the thirteen assertions added for rounds 11 and
12. They pass here as well as on macOS, and this run is the one that exercises
them on the case-**sensitive** branch —

```
PASS  allowed: a case variant, on a case-SENSITIVE filesystem
      (filesystem is case-sensitive; both directions are asserted, neither skipped)
PASS  an inherited TRIED flag does not unprotect the JVM home
PASS  an inherited TRIED flag does not unprotect the JVM home: and the protected tree is byte-identical
```

`/bin/bash` is bash 5 here, so the lint's parse pass is not a second opinion on
this runner; the bash 3.2 parse is macOS-only. The case-INSENSITIVE direction of
the two case assertions, and the `mkdir`-then-refuse undo path that only exists
where the filesystem folds case, are covered by
[`macos-refusal.md`](macos-refusal.md) and by nothing here.

The ZIP hash differs from every earlier run and that is expected: the build is
not byte-reproducible, which is why `docs/PORTAL_TOOLS_RELEASE.md` attaches a
release asset from the verified retained artifact rather than from a rebuild.
Nothing was published by this run — every job in `portal-tools.yml` is read-only.

## Run 34828230695 — `c11e81643`, the candidate

| what | value |
|---|---|
| workflow-definition SHA (`run.headSha`) | `c11e8164306e8d224233be3568e62d3967ed7ca2` (branch `fix/store-host-correctness`) |
| actual checkout SHA (`git rev-parse HEAD` in the job) | `c11e8164306e8d224233be3568e62d3967ed7ca2` |
| `VERSION.json.sourceCommit` | `c11e8164306e8d224233be3568e62d3967ed7ca2` |
| ZIP sha256 | `5ac85c1d97f3d4290f2be2a18431ff602c50f455bce2a240e84c5bdce6e6fc8f` |
| conclusion | **success** |

Per-check on Linux: disposable-parent refusal **93 / 0**, hygiene **10 / 0**,
adopter acceptance **19 / 0**, foreign-relay refusal **6 / 0**, identity contract
**11 / 0**, guest bundle signing **4 / 0**, store recovery **144 / 0**.

## Run 34824531657 — `bc2a32550`

| what | value |
|---|---|
| workflow-definition SHA (`run.headSha`) | `bc2a325503a2daff38251030a0d46378429ad6ff` (branch `fix/store-host-correctness`) |
| actual checkout SHA (`git rev-parse HEAD` in the job) | `bc2a325503a2daff38251030a0d46378429ad6ff` |
| `VERSION.json.sourceCommit` | `bc2a325503a2daff38251030a0d46378429ad6ff` |
| ZIP sha256 | `76ee7eeb155f20e41a9f6629c1d8f84b46b68c11d85dcc6885a6c596588bf959` |
| conclusion | **success** |

Per-check on Linux: refusal **87 / 0**, recovery **144 / 0**. Evidence for
`bc2a32550` only — the sixteenth review then found the `KELIVER_JVM_HOME_MEMO`
bypass, and the seventeenth found its two symmetric partners. Neither run could
have caught them, because nothing asserted them.

## How many rounds this took, and why it is written down

Eighteen independent reviews, eighteen rejections. The store/host work the block
asked for — failing closed on store resolution, non-destructive startup, host
input validation, the recovery CLI, a portable NEW-1 — was settled at
`c201521a3`. Everything after it is **one file**,
`scripts/keliver-test-isolation-guard.sh`: a check that refuses to let a test
script's disposable-parent argument point at the real portal store. It is not on
any product path.

That check failed open, or wrote where it was refusing to, in nine consecutive
reviewed commits — thirteen counting the last four rows below, none of which is a
path spelling at all:

| # | how it got through |
|---|---|
| 1 | a parent whose own parent did not exist — empty canonicalisation matched nothing |
| 2 | a case-variant path on a case-insensitive filesystem |
| 3 | a `..` segment past a component that did not exist yet |
| 4 | `mkdir -p` created the store *between* the two refusals |
| 5 | a partial `mkdir` failure skipped the undo entirely |
| 6 | a `.` component made `rmdir` fail EINVAL and abort the undo |
| 7 | `pwd -P`'s doubled leading slash through a symlink to `/` |
| 8 | the protected ROOTS were compared raw while the candidate was resolved |
| 9 | `~user/store` — the tilde check, one character along |
| 10 | an inherited `KELIVER_JVM_HOME_MEMO` switching off the root that exists *because* `$HOME` is untrusted |
| 11 | `KELIVER_JVM_HOME_TRIED` — round 10 validated the memo and left the flag that decides whether the memo is *filled* |
| 12 | a memo validated for **shape** (absolute, exists) rather than provenance: any real directory is honoured, and an honoured memo *replaces* the JVM root |
| 13 | `java … \| awk …` — the substitution carried **awk's** status, so "java is absent/crashed/said nothing" and a real answer were indistinguishable, and the JVM root was silently dropped |

The recurring error is the same one each time: fixing the operand the review
pointed at and not its symmetric partner — candidate but not root, resolved but
not raw, `OSError` but not every exception, `~/` but not `~user`.

The tenth is different in kind and worth reading twice: a **caller-settable
variable** switched off the protected root that exists *because* `$HOME` is not
trusted. No path spelling was involved, and the refusal suite itself set that
variable.

Rounds 11 and 12 are that same error applied to round 10's own fix — the flag
next to the memo, and a memo validated for shape rather than provenance. The
mechanism is deleted now rather than guarded a third time: `java` is asked once
per call into a `local`, and nothing a caller can assign decides whether a
protected root exists. It cost the suite about 25 seconds and removed the shape.
Measured against `1c19804e6`, **nine** of the new assertions fail there, four of
them reporting that the guard *wrote inside the protected root*.

What made it converge was `keliver-refusal-check.sh`, and the discipline of
running each round's new assertions against the **previous** commit. Three of
this round's fail there. Two rounds were caught by Linux CI asserting a macOS
answer. The lint had to be rewritten four times and moved into its own file
before it could be tested at all — as a heredoc it could only scan the live
tree, where the case its logic exists for does not occur, so every mutation of
that logic passed.

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
