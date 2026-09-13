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

| 10 | `syncPortalKey -Pkeliver.portalStore=<store WITH a key>`, then the SAME warm build dir with `-Pkeliver.devOnlyHost=true` | U22: the dev-only build must not inherit the key | after 1: `portal_ed25519.pub`; after 2: `[]` |

Row 10 is U22 without an APK, because this machine has no Android SDK wired up.

**The first version of row 10 passed for the wrong reason, and the claim under
it was wrong.** `syncPortalKey` was a `Sync`; with no source file a `Copy`/`Sync`
task is `NO-SOURCE`, which *skips the task entirely* — its actions never run, and
the directory is emptied by Gradle's stale-output cleanup rather than by anything
this build states. The cleanup does happen here (measured on macOS, and the U22
hygiene case passes on Linux CI), but an emptied directory *is* the whole U22
property, and it should not rest on a behaviour of the skip path — an independent
reproduction of the same task shape on Gradle 9.0.0 reports the key surviving.
It also made two of the three log branches unreachable: a skipped task prints
nothing.

`syncPortalKey` is no longer a `Sync`. It declares its inputs and output and
empties the directory in its own action, which always runs. Row 10 was re-run
against that, with the same result, and both previously unreachable branches now
print:

```
portal-device-android: DEVELOPMENT-ONLY host — no portal key embedded (keliver.devOnlyHost=true)
portal-device-android: DEVELOPMENT-ONLY host — no portal key embedded (no key at …/dstore-empty/keys/ed25519.pub)
```

The same property is now asserted in C16d on every platform, against a store that
actually holds a key — the earlier control pointed at an *empty* store, so it
proved only that the override unblocked dependency resolution and would have
looked identical if embedding had stopped working altogether.

Rows 8 and 9 used a throwaway `git worktree` with `scripts/keliver-store-path.sh`
replaced by a stub that exits 3. That isolates *this* change — Gradle mapping the
resolver's exit 3 to a refusal — from the resolver's own split detection, which
`keliver-store-identity-repro.sh` covers end to end. The real repo cannot be put
into a split state without creating a second store under the real
`~/.keliver-portal`, which this work is not allowed to do.

### The one that had to be measured rather than reasoned about

Row 6 failed the first time: setting `ZiplineCompileTask.signingKeys` at script
level produced an **unsigned bundle with a key present** —
`unsigned.signatures = {}`. From `afterEvaluate`, it signs.

The first explanation written down for that was wrong. The zipline plugin does
not write `signingKeys` from its own `afterEvaluate`; it writes from the task's
**registration action**, and the task is registered from the Kotlin JS plugin's
`afterEvaluate`, i.e. after this build file has been read. Gradle runs a task's
configuration actions in the order they were added, so a script-level
`configureEach` is added before the task exists and is overwritten by the
registration action. Re-measured after the correction, in the real build:

```
script-level configureEach -> signatures: {}
afterEvaluate              -> signatures: {"portal-ed25519": "975490ce…"}
```

An isolated reproduction of the same plugin shapes reports script level winning,
so this is a property of *this* build's plugin ordering and must be measured
here rather than modelled.

The invariant — nothing may write `signingKeys` after us — is not enforceable
from the build file, so it is gated:
`scripts/keliver-guest-signing-check.sh` asserts both directions, and reverting
that one line makes it fail while the build still succeeds:

```
--- a store WITH a signing key
  PASS  the guest bundle builds against a store that holds a key
  FAIL  the manifest is NOT signed with the store's identity (signatures: (none))
passed: 3   failed: 1
```

It runs in the portable CI checks.

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
  check is the only scripted caller of `-Pkeliver.portalStore`, so its
  mode-dependent assertion is first executed on Linux. C16d asserts the
  production half (the warning appears) and the dev-only half (no store is
  consulted at all) and executes on both platforms — but through
  `syncPortalKey`, not through an assembled APK.
* `apiCheck` — fails on this machine for the missing Android SDK, not for
  anything about the store. Row 2 stands in for it locally; CI runs it.

## What Linux CI found that macOS could not

Run **34771348310**, dispatched with `-f ref=8a702b230`, **failed** — correctly.
The assertion added to `keliver-device-host-hygiene-check.sh` asserted the
override warning unconditionally, and the two **development-only** builds do not
emit it: the dev-only host short-circuits before the resolver accessor, so
nothing announces the override because nothing consults a store. The assertion
was wrong; the behaviour was right.

That is now two assertions instead of one, and the dev-only direction is the
stronger of the two — the ABSENCE of the warning is the evidence that the
short-circuit holds. The same property is also asserted in C16d, against a
broken resolver, so it is covered on every platform that runs the recovery
suite and not only where an Android SDK exists.

Run 34771348310 is evidence for `8a702b230` and for nothing later. The failure
is retained deliberately: it is the only executed proof that this assertion can
fail.

## Notes

The absolute paths in `recovery-check.log` are this session's scratchpad and are
not reproducible elsewhere; the run is reproducible, the paths are not.
