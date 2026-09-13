# Resolving the store when a task needs it — what was executed

Commit under test: `eeab72ebd` (branch `fix/store-host-correctness`).
Pre-fix commit used for before/after: `72ba9fc1e`.
Platform: macOS (darwin 25.5.0), JDK 17, Gradle 9.0.0.

The "broken resolver" in every row below is `python3` replaced by a stub that
exits 127, put first on `PATH`. No override is passed unless the row says so.
Nothing here reads, writes or migrates the real store; the two rows that build a
signed bundle use a disposable store holding a disposable keypair. No private
key was printed.

## The regression, reproduced before it was fixed

```
$ PATH="$nopy:$PATH" ./gradlew -q :portal-relay:compileKotlin      # at 72ba9fc1e
EXIT=1
* Where: Build file '.../build.gradle' line: 65
* What went wrong: A problem occurred evaluating project ':portal-device-android'.
  > keliver: could not resolve this app's portal store.
```

A task with no signing identity in it, stopped by the identity resolver, during
*evaluation of a different project*.

## After

| # | what was run | expected | observed |
|---|---|---|---|
| 1 | `:portal-relay:compileKotlin`, broken resolver, no override | succeeds | **EXIT=0** |
| 2 | `:portal-relay:test`, broken resolver, no override | succeeds | **EXIT=0** |
| 3 | `:portal-device-android:syncPortalKey`, broken resolver | refuses, names the resolver | **EXIT=1**, `Could not determine the dependencies of task ':portal-device-android:syncPortalKey' > keliver: could not resolve this app's portal store. resolver: … exit: 127 … Refusing to fall back to ~/.keliver-portal` |
| 4 | `:portal-device-android:syncPortalKey -Pkeliver.devOnlyHost=true`, broken resolver | succeeds without consulting a store | **EXIT=0** (`syncPortalKey NO-SOURCE`) |
| 5 | `:portal-published-guest:compileProductionExecutableKotlinJsZipline`, broken resolver | refuses | **EXIT=1**, same diagnostic |
| 6 | same task, `-Pkeliver.portalStore=<disposable store WITH a key>` | signed | `unsigned.signatures = {"portal-ed25519": "975490ce…ed03"}` |
| 7 | same task, `-Pkeliver.portalStore=<disposable store with NO key>` | unsigned, not a failure | `unsigned.signatures = {}` |
| 8 | `:portal-relay:compileKotlin` in a worktree whose resolver exits 3 (split) | succeeds | **EXIT=0** |
| 9 | `:portal-device-android:syncPortalKey` in that same worktree | refuses with the split message | **EXIT=1**, `> keliver: …this app has more than one store and I will not guess` |

Rows 8 and 9 used a throwaway `git worktree` with `scripts/keliver-store-path.sh`
replaced by a stub that exits 3. That isolates *this* change — Gradle mapping the
resolver's exit 3 to a refusal — from the resolver's own split detection, which
`keliver-store-identity-repro.sh` covers end to end. The real repo cannot be put
into a split state without creating a second store under the real
`~/.keliver-portal`, which this work is not allowed to do.

### The one that had to be measured rather than reasoned about

Row 6 failed the first time. Setting `ZiplineCompileTask.signingKeys` during
script evaluation is silently overwritten by the zipline plugin's own
`afterEvaluate`, and the bundle came out **unsigned with a key present** —
`unsigned.signatures = {}`. Registering the same provider from a later
`afterEvaluate` produced the signature above. Had this been asserted from source
rather than run, the change would have shipped disabling signing.

## The C16d assertion can fail

The suite's new "a task that needs no identity still builds with a broken
resolver" was run against a worktree at the pre-fix commit `72ba9fc1e`:

```
OLD_unrelated_EXIT=1
  > keliver: could not resolve this app's portal store.
```

so it reports `bad` there and `ok` at `eeab72ebd`.

## The C12 assertion can fail

Against a stub log that exits non-zero, prints `✗ THE PREVIOUS BINDING COULD NOT
BE RESTORED.` and then `the store was left unchanged; nothing was modified.`:

```
new assertion: bad (regression DETECTED)
old assertion: ok (regression MISSED)
```

and against the real partial report it stays `ok`, so it is not a false positive.

## Suites

* `keliver-store-recovery-check.sh` — **133 passed, 0 failed** (`recovery-check.log`)
* `keliver-store-identity-repro.sh` — **11 passed, 0 failed** (`identity-repro.log`)
* `:portal-relay:test` — EXIT=0

## Not executed here

* `keliver-device-host-hygiene-check.sh` and the Android unit tests need an
  Android SDK, which is not wired up on this machine (`ANDROID_HOME` unset, no
  `local.properties`). Both run on Linux CI, which has its own SDK. The hygiene
  check is the only scripted caller of `-Pkeliver.portalStore`, and the
  assertion added to it that the override announces itself is therefore first
  executed on Linux — but the same assertion also runs in C16d, which executed
  here and on Linux.
* `apiCheck` — fails on this machine for the missing Android SDK, not for
  anything about the store. Row 2 stands in for it locally; CI runs it.

The absolute paths in `recovery-check.log` are this session's scratchpad and are
not reproducible elsewhere; the run is reproducible, the paths are not.
