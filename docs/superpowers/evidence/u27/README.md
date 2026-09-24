# U27 / U29 — the private signing key's permissions, and half an identity

Measured 2026-09-24 on macOS (APFS, umask 022, JDK 17). Every relay ran with the
JVM's `user.home` inside a disposable run directory, behind
`keliver_require_isolated_store`; the Gradle home was disposable too. No real
store or key was read, listed or changed. Paths are shortened to `<run>`,
`<checkout>`, `<tools-0.3.5>`.

| file | what |
|---|---|
| `before.txt` | `scripts/keliver-key-permissions-check.sh` against the **released tools 0.3.5 bundle** — its `relay/bin/portal-relay` and `bin/keliver-adopt-legacy-store.sh`, from the zip whose sha256 is `4e1c3040…` (the published asset). 20 passed, **29 failed**, 2 skipped. |
| `after.txt` | the same check against this branch's relay and adopt script: **49 passed, 0 failed**, 2 skipped. |
| `signed.txt` | `scripts/keliver-verify-signed-bundle.sh` on this branch: a key created by the new code (`-rw-------`) signs a bundle that Zipline's `ManifestVerifier` accepts; a tampered bundle and a different key are rejected (3/0). |
| `SigningKeysTest.txt` | the unit tests (10/0) and the whole `portal-relay` module (103/0). |

The check's sections: **A** fresh key, umask 022 (with the modes of every
directory from the run directory down to the key); **B** restart; **C** umask
000; **D** an existing key with the pre-fix modes; **E** half an identity, both
directions; **F** a FAT disk image attached with `hdiutil` (macOS mounts it
`noowners`), for the relay and for adoption; **G** legacy adoption, and
`--force` over a 0644 key.

The two SKIPs are the cross-user read: this Mac has no second account reachable
without a password. `portal-tools.yml` runs the same check on the Linux runner
with a real second account (`KELIVER_PROBE_USER`), after proving that account
can read a world-readable canary beside the store.

No key material is in these files: keys are compared by hash inside the check,
every log is searched for the private key with `grep -F -f <key file>` (none
found), and a scan of this directory for 32+ hex characters finds nothing. The
"public key fingerprint" lines are the first 16 hex digits of a SHA-256 of the
public key file.
