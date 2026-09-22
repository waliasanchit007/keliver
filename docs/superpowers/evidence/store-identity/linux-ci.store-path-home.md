# Linux CI for #78 — the home the store resolver answers against

Four identities per run, recorded separately: a GitHub run's `headSha` is the
**workflow definition**, not what an input-driven checkout built.

Each run is evidence for its own commit only. A later head does not inherit a
run, however small the delta.

## Run 35780147426 — `2402601b6`

| what | value |
|---|---|
| workflow-definition SHA (`run.headSha`) | `2402601b62b6193fce657e6d237b5ca93693fa71` (branch `fix/store-path-home-discovery`) |
| actual checkout SHA (`git rev-parse HEAD` in the job) | `2402601b62b6193fce657e6d237b5ca93693fa71` |
| `VERSION.json.sourceCommit` | `2402601b62b6193fce657e6d237b5ca93693fa71` |
| ZIP sha256 | `b3c5204880330539048a4cf7d98dd9a934a16c0675102e16b6741441ab18213f` |
| jobs | `bundle` **success**; `device` skipped (no device input) |

Executed on Linux, all 0 failed:

| check | result |
|---|---|
| `keliver-resolver-failure-check.sh` | 37 / 0 |
| `keliver-store-home-check.sh` | 32 / 0 |
| `keliver-refusal-check.sh` | 134 / 0 (case-**sensitive** branch) |
| `keliver-store-recovery-check.sh` | 144 / 0 |
| adopter acceptance | 19 / 0 |
| identity contract | 11 / 0 |
| device-host hygiene | 10 / 0 |
| foreign-relay refusal | 6 / 0 |
| guest bundle signing | 4 / 0 |
| `:portal-relay:test` (includes `StoreContractTest`, 18) | passed |

**Signing.** `SignedBundleVerificationTest` ran *driven* inside the recovery
suite's C1 — a real Zipline manifest signed with a disposable store's identity,
verified against that store's public key after a relocation — reporting
`tests="3" skipped="0" failures="0"`. That is verification, tamper rejection
and foreign-key rejection. The foreign-key test's predicate no longer accepts
`"manifest"`, so a pass here means Zipline's real rejection message read as a
signature failure on this JVM as well as on macOS.

**Skipped.** 119 `SKIPPED` lines are Gradle task outcomes, not tests. One
check-level skip, pre-existing and expected: device-host hygiene's device steps
(`no --serial` — the runner has no device).

**Not exercised by this run:** the bundled callers were driven from the
**repository** copies, not from this ZIP. That gap is why the next head exists.
