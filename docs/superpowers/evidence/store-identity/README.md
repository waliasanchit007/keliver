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

[`recovery-check.log`](recovery-check.log) —
`scripts/keliver-store-recovery-check.sh`, **21 passed / 0 failed**. It runs the
commands from a bundle-shaped staging directory (`bin/` + `relay/`, assembled the
way `build-portal-tools.sh` does) against apps outside the repository.

* **C1** a real Zipline manifest signed with the store's key before a move
  verifies, with Zipline's own `ManifestVerifier`, against the public key the
  app resolves *after* the move. The result XML is checked for
  `skipped="0"` — see U26, where this same gate used to skip and report success.
* **C2** the recovered app restarts normally.
* **C3** an unrelated app keeps its own identity, is refused the recovered app's
  store, and recovery refuses it too while the owner is live. Both stores are
  byte-identical afterwards.
* **C4** two plausible claimants race to recover one store: exactly one wins,
  nothing but the `owner` marker changes, the identity survives, and the loser
  is still refused.
* **C5** `bin/keliver-portal` starts and stops the recovered app.

## What was not touched

Every run has its own `user.home`, its own stores and its own throwaway signing
keys; `keliver_require_isolated_store` runs before each relay start. No private
key is read or printed anywhere — identities are compared by a SHA-256
fingerprint of the **public** key. The developer's real `~/.keliver-portal` was
not written to.
