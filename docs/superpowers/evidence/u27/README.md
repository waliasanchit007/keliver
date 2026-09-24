# U27 / U29 — the private signing key's permissions, and half an identity

Measured 2026-09-24 on macOS (APFS, umask 022, JDK 17). Every relay ran with the
JVM's `user.home` inside a disposable run directory, behind
`keliver_require_isolated_store`; the Gradle home was disposable too. No real
store or key was read, listed or changed. Paths are shortened to `<run>`,
`<checkout>`, `<tools-0.3.5>`, `<scratch>`.

| file | what |
|---|---|
| `before.txt` | `scripts/keliver-key-permissions-check.sh` against the **released tools 0.3.5 bundle** — its `relay/bin/portal-relay` and `bin/keliver-adopt-legacy-store.sh`, from the zip whose sha256 is `4e1c3040…` (the published asset). 25 passed, **36 failed**, 2 skipped. |
| `after.txt` | the same check against this branch's relay and adopt script: **61 passed, 0 failed**, 2 skipped. |
| `signed.txt` | `scripts/keliver-verify-signed-bundle.sh` on this branch: a key created by the new code signs a bundle that Zipline's `ManifestVerifier` accepts; a tampered bundle and a different key are rejected (3/0). The key's modes (`-rw-------`) were listed after the check and appended. |
| `SigningKeysTest.txt` | the unit tests (14/0) and the whole `portal-relay` module (107/0). |
| `store-checks.txt` | the store checks that start the relay or run adoption, on this branch: store-home 32/0, resolver-failure 42/0, legacy-compat 14/0, store-identity 11/0, store-recovery 136/0. |

The check's sections: **A** fresh key, umask 022, with the modes of every
directory from the run directory down to the key; **B** restart; **C** umask 000
(key 600 and `keys/` 700; the store directory, which the relay's store claim
creates 777 under that umask, is reported); **D** an existing key with the
pre-fix modes — warned, publish refused, nothing changed, the printed commands
run, then publish reaches the build; **E** half an identity, both directions —
the relay starts, warns and refuses publish, and generates nothing; **F** a FAT
disk image attached with `hdiutil` (macOS mounts it `noowners`), for the relay
and for adoption; **G** adoption: a pair, owner-only; `--force` replaces both
halves; half a legacy identity refused; a `keys/` others can write refused.

The two SKIPs are the cross-user read: this Mac has no second account reachable
without a password. `portal-tools.yml` is wired to run the same check on the
Linux runner with a real second account (`KELIVER_PROBE_USER`), after proving
that account can read a world-readable canary in the store directory. Whether
that run has a result is in the PR, not here.

No key material is in these files. Keys are compared by hash inside the check.
Its last row searches every log and publish response it wrote for every private
key it made (`grep -F -f <key file>`; 9 keys, none found), and a scan of this
directory for 32+ hex characters finds nothing. The "public key fingerprint"
lines are the first 16 hex digits of a SHA-256 of the public key file.
