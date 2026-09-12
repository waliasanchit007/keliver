# Store identity — U23 / U24 / U25.1

Evidence for the correction on `fix/store-identity-u23-u25`. The contract being
asserted is [`docs/STORE_IDENTITY.md`](../../../STORE_IDENTITY.md).

## The same script, before and after

`scripts/keliver-store-identity-repro.sh` is written against the contract, not
against the implementation, so it can be run unchanged on both sides.

| run | code under test | result |
| --- | --- | --- |
| [`repro-before.log`](repro-before.log) | `cd7430916` — remote `main`, the released 0.3.4 store code, built in a throwaway worktree | **1 passed / 8 failed** |
| [`repro-after.log`](repro-after.log) | `fix/store-identity-u23-u25` | **11 passed / 0 failed** |

What the before-run shows, measured rather than argued:

```
R1  the renamed app silently got a DIFFERENT identity (a3e5093a… -> f4f75f17…)
R2a refused with no way forward - this is U24
      Fix: remove "store" from this app's keliver.portal.json to get its own
R3  one app split across two stores, chosen by which path was typed
      identities: 7bca8b14… vs fe43ac48…
R4  unicode name DISAGREES
      kotlin: …/apps/caf----169badf7
      shell:  …/apps/café---169badf7
```

R2b fails in the before-run because `keliver-store-recover.sh` does not exist at
that commit — which is the point of U24.

## The outcomes, through the packaged commands

`scripts/keliver-store-recovery-check.sh` runs the commands from a bundle-shaped
staging directory (`bin/` + `relay/`, assembled the way `build-portal-tools.sh`
does) against apps outside the repository. The same script, both sides:

| run | code | result |
| --- | --- | --- |
| [`recovery-before.log`](recovery-before.log) | `eb17ab897` — the first version of the recovery command | **29 passed / 18 failed** |
| [`lifecycle-before.log`](lifecycle-before.log) | `43148d63e` — after that round, before the transaction-lifecycle round | **62 passed / 14 failed** |
| [`recovery-check.log`](recovery-check.log) | this branch | **77 passed / 0 failed** |

* **C1** a real Zipline manifest signed with the store's key before a move
  verifies, with Zipline's own `ManifestVerifier`, against the public key the
  app resolves *after* the move. The result XML is checked for `skipped="0"`
  and for both tests having run — see U26, where this gate used to skip and
  report success.
* **C2** the recovered app restarts normally.
* **C3** an unrelated app keeps its own identity, is refused the recovered app's
  store, and recovery refuses it too while the owner is live. Both stores are
  byte-identical afterwards.
* **C4** two plausible claimants race to recover one store: exactly one wins,
  nothing but the `owner` marker changes, the identity survives, and the loser
  is still refused.
* **C5** `bin/keliver-portal` starts and stops the recovered app.
* **C6** success means the binding works: an unusable pointer destination is
  refused before anything is mutated, a failure *during* the update rolls the
  owner marker back, and the positive case is checked by re-resolving rather
  than by exit status.
* **C7** precedence: a store pinned in `keliver.portal.json` is not overridden,
  a pointer to another identity is not ignored, a resolver refusal is reported
  rather than read as "no store", and `PORTAL_STORE` is announced and ignored.
* **C8** the same-owner split — both stores record the same canonical path —
  is recoverable, and is checked by resolving and starting the relay, not by
  the command's exit status. The unselected store is untouched.
* **C9** two recoveries targeting *different* stores for one app: at most one
  succeeds, at most one owner marker names the app, and the pointer agrees with
  it. Before the fix **both** succeeded and both stores claimed the app.
* **C10** recovery and relay startup share the app lock, and a lock whose
  holder is gone is taken over rather than blocking forever.
* **C11** interruption at each transaction boundary: before any write, between
  the two writes, and after the commit. A trap that only released the locks was
  worse than none — bash resumes at the interrupted statement once a handler
  returns, so `TERM` between the writes used to release the locks and then go on
  to finish and report success. Also: cleanup must not remove a lock that now
  belongs to a later process.
* **C12** when restoration itself fails, the backup is kept and the real partial
  state is printed — never "unchanged".
* **C13** a startup that waits for the lock re-resolves under it. Before the fix
  it claimed the store it had selected *before* the recovery ran and wrote that
  stale answer back into the pointer, undoing the rebinding it had waited for.
* **C14** a stale-lock takeover already claimed by another contender is left
  alone, and a live holder's lock is never taken over.

What the lifecycle before-run shows, in its own words:

```
C11a the interrupted recovery reported success (rc=0)
C11a the owner marker is now …/apps/c11-term
C11d cleanup removed a lock it no longer owned
C12  the backup was deleted after a failed restoration
C12  it claimed nothing changed while the state is partial
C13  startup wrote back its stale answer (pointer is now …/c13a-store-…)
C13  the abandoned store was modified
```

`SIGINT` is delivered under job control (`set -m`), because a background job
started by a non-interactive shell inherits `SIGINT` as `SIG_IGN` and `trap`
cannot override an inherited ignore — without it the signal would silently do
nothing and the case would pass for the wrong reason.

The pre-fix recovery script has no pause hook, so it was instrumented with
**only** that hook to make the same boundaries reachable;
[`lifecycle-before-instrumentation.diff`](lifecycle-before-instrumentation.diff)
is the complete change. Nothing else about the pre-fix script was touched.

### The unlocked path, before it was removed

[`lock-before.log`](lock-before.log) — executed at `4b5c7a0ad` with the
throwaway test kept beside it
([`lock-before-UnlockedPathTest.kt.txt`](lock-before-UnlockedPathTest.kt.txt)).
All three of its assertions PASSED there, which is the defect rather than a
success:

```
BEFORE: parent-unavailable          -> block ran unlocked
BEFORE: mkdir-failed-and-absent     -> block ran unlocked
BEFORE: after cleanup, later holder's lock exists = false
```

The two unlocked returns were `PortalConfig.kt:352` and `:360` at that commit.
They are gone; a vanished lock is retried, a lock that cannot be created
refuses, an unwritable holder marker is a failed acquisition, and cleanup
removes the lock only while its marker still names this process.

The relay-side lock protocol is additionally covered by `StoreLockTest`
(14 tests), which drives the interleaving between the liveness check and the
claim through a seam in `claimStaleLock` rather than hoping to hit it by timing.

What the before-run shows, in its own words:

```
C6a recovery reported success with no writable pointer destination
C6a the owner was rewritten anyway
C7a recovery bound a store the app does not resolve to
C7c a resolver refusal was swallowed and read as 'no store'
C8  resolution selects '<refused>', not …/apps/current-05a3ae07
C9  both reported success on different stores
C9  both stores claim this app
C10 the relay did not start, but not because of the lock
```

## A note on the before-runs

Both before-runs build the earlier commit in a throwaway `git worktree` and run
the CURRENT check script against it, so the assertions are identical on both
sides. `repro-before.log` predates two cosmetic changes to its script (a
portable `sha256sum`/`shasum` selection, and the R2 section being split into
R2a/R2b once a recovery command existed to name); none of its assertions
changed. One assertion in an earlier draft of C10 matched the word "recovery"
anywhere in the relay log and so matched the disposable run directory's name —
it passed for the wrong reason. It now matches the refusal's own words, and
both runs above were redone with the tightened version.

## What was not touched

Every run has its own `user.home`, its own stores and its own throwaway signing
keys; `keliver_require_isolated_store` runs before each relay start. No private
key is read or printed anywhere — identities are compared by a SHA-256
fingerprint of the **public** key. The developer's real `~/.keliver-portal` was
not written to.
