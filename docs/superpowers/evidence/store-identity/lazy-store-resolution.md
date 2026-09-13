# Resolving the store when a task needs it — what was executed

Commit the matrix was first run against: `eeab72ebd`. Re-run and corrected
through `8a702b230`, `bcff322eb`, `9a7f681ed` and the head this document is
committed at — four independent reviews, each of which found something the run
before it had recorded wrongly.
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
| 4 | `:portal-device-android:syncPortalKey -Pkeliver.devOnlyHost=true`, broken resolver | succeeds without consulting a store | **EXIT=0**, task executes and takes the dev-only branch (it was `NO-SOURCE` when this row was first recorded; with `upToDateWhen { false }` and no `@SkipWhenEmpty` it can never be `NO-SOURCE` again) |
| 5 | `:portal-published-guest:compileProductionExecutableKotlinJsZipline`, broken resolver | refuses | **EXIT=1**, same diagnostic |
| 6 | same task, `-Pkeliver.portalStore=<disposable store WITH a key>` | signed | `unsigned.signatures = {"portal-ed25519": "975490ce…ed03"}` |
| 7 | same task, `-Pkeliver.portalStore=<disposable store with NO key>` | unsigned, not a failure | `unsigned.signatures = {}` |
| 8 | `:portal-relay:compileKotlin` in a worktree whose resolver exits 3 (split) | succeeds | **EXIT=0** |
| 9 | `:portal-device-android:syncPortalKey` in that same worktree | refuses with the split message | **EXIT=1**, `> keliver: …this app has more than one store and I will not guess` |

| 10 | `syncPortalKey -Pkeliver.portalStore=<store WITH a key>`, then the SAME warm build dir with `-Pkeliver.devOnlyHost=true` | U22: the dev-only build must not inherit the key | after 1: `portal_ed25519.pub`; after 2: `[]` |

Row 10 is U22 without an APK, because this machine has no Android SDK wired up.

**What was written under row 10 the first two times was wrong, in opposite
directions.** The accurate account, measured on this repo's Gradle 9.0.0 with an
identically shaped `Sync`: the run where the source *disappears* is **not**
`NO-SOURCE`. It executes, and it removes the stale key deterministically. Only
the run *after* that is `NO-SOURCE`. So `Sync` was not silently relying on
stale-output cleanup for the U22 transition, and the claim that it was is
withdrawn.

What `Sync` really did not do is run its **own actions** on that transition —
measured, `doFirst` does not fire — which left two of the three messages below
permanently unprintable, and it left the emptying to `Copy` semantics rather
than to anything this build states.

`syncPortalKey` is no longer a `Sync`. It empties the directory in its own
action and runs every time, because output-directory *contents* are not part of
Gradle's up-to-date check: measured, a foreign file planted in
`build/portalKeys` survives an UP-TO-DATE run of **either** shape, and that
directory is an `assets.srcDirs` entry, so anything left in it ships. Running
unconditionally closes that and costs a delete plus at most one small copy.

Row 10 was re-run against the new shape with the same result, and both
previously unreachable branches now print. The second of them also had to be
**reworded**: it said "DEVELOPMENT-ONLY host" for a build with
`keliver.devOnlyHost` absent — a production-shaped host with `DEV_ONLY=false` —
which names the wrong one of the two binaries this module produces. Unreachable
text is unreviewed text.

```
portal-device-android: no portal key embedded — this is the DEVELOPMENT-ONLY host
  (keliver.devOnlyHost=true), which refuses production mode
portal-device-android: no portal key embedded — no key at …/keys/ed25519.pub.
  This host is NOT marked development-only, so production mode will have no
  identity to verify against.
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

**Three explanations for that were written down before the right one** — "the
plugin's own `afterEvaluate`", "the task does not exist yet", "the registration
action runs last". Each survived until an independent review measured the
ordering instead of reading it. Two of them wrapped the wiring in
`afterEvaluate`, which was never needed and hid the actual rule.

The rule: the zipline plugin writes `signingKeys` from the compile task's
**registration action**, and those tasks are registered when the JS binaries are
created — by `binaries.executable()` inside `kotlin { js { … } }`. Gradle splices
a registration action into the container's action chain **at the position
`register()` was called**. Actions added before it run before it; actions added
after it run after it and win.

So the only thing that mattered was that the statement sat *above* the
`kotlin {}` block. Measured in the real build, with nothing else changed:

```
same statement ABOVE kotlin {}  -> signatures: {}
same statement BELOW kotlin {}  -> signatures: {"portal-ed25519": "…"}
```

It now sits below, as a plain `configureEach`, with no `afterEvaluate` and no
snapshot. `isEmpty()` realizes the collection first so that "the plugin
registered no compile task" fails the build instead of producing an unsigned
bundle. A task registered *after* that statement would still keep the plugin's
value; nothing registers one later today.

The shape depends on where a statement sits, so it is gated:
`scripts/keliver-guest-signing-check.sh` asserts both directions, and moving the
statement back above the `kotlin {}` block makes it fail while the build still
succeeds:

```
--- a store WITH a signing key
  PASS  the guest bundle builds against a store that holds a key
  FAIL  the manifest is NOT signed with the store's identity (signatures: (none))
passed: 3   failed: 1
```

It runs in `portal-tools.yml`'s portable checks — which fire on
`portal-tools-v*` tags and on `workflow_dispatch`, not on pull requests or
pushes to main. A release-time and on-demand gate, not a per-commit one, and it
builds the Development variant only.

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
