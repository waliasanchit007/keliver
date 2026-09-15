# The refusal suite on a case-insensitive filesystem (macOS)

Linux CI runs the refusal suite on ext4, which is case-SENSITIVE. Two of its
assertions take the other branch there, and the `mkdir`-then-refuse undo path is
reachable **only** where the filesystem folds case. That branch has therefore
never been exercised by CI, and a Linux run must not be reported as covering it.
This file is the macOS run that does.

## The filesystem was probed, not assumed

`diskutil info /` reports APFS, but APFS can be formatted either way, so the
platform name proves nothing. The suite creates `.keliver-CaseProbe` in its own
fixture directory and looks for `.keliver-caseprobe`:

```
(filesystem is case-insensitive; both directions are asserted, neither skipped)
```

The probe runs inside the disposable parent, so it describes the filesystem the
fixtures actually live on rather than the one `/` lives on.

## Result

```
scripts/keliver-refusal-check.sh <disposable>   ->  passed: 106   failed: 0   (exit 0)
```

The branch-specific assertions, which on Linux take the `must_allow` path instead:

```
PASS  refused: a case variant, on a case-insensitive filesystem
PASS  refused: a mixed-case variant + subdir, likewise
PASS  make_run_dir refuses a case variant of a store that does not exist yet
PASS  and did not bring the store into existence on the way
```

The last pair is the undo path: on a case-folding filesystem the first refusal
misses, `mkdir -p` creates the store four levels deep, the second refusal
catches it, and the undo must remove exactly what it made.

The failed-`mkdir`/`mktemp`/`cd` cleanup cases, all executed:

```
PASS  mkdir cannot create it at all: not accepted (rc=1)              + nothing left behind
PASS  mkdir fails partway through: not accepted (rc=1)                + nothing left behind
PASS  mktemp cannot create in it: not accepted (rc=1)                 + nothing left behind
PASS  cd cannot enter it: not accepted (rc=1)                         + nothing left behind
PASS  mktemp cannot create in it, via a . component: not accepted (rc=1)  + nothing left behind
PASS  cd cannot enter it, via a trailing .: not accepted (rc=1)       + nothing left behind
```

## What this run does NOT establish

It is macOS and bash 3.2 only. The case-SENSITIVE direction of those same
assertions is covered by Linux CI, and neither run covers both. Nothing here
touches a product path: the guard is a test-harness check.

## Rounds 11 and 12

The first macOS run of this suite was `1c19804e6`, at **93 / 0**. It passed, and
it was wrong to read as clearance: the seventeenth review then found two more
ways through `keliver_protected_roots`, neither of which anything asserted. The
13 assertions added since are what took it to 106, and **nine of them fail
against `1c19804e6`** — four reporting that the guard wrote inside the protected
root it was refusing:

```
FAIL  an inherited TRIED flag does not unprotect the JVM home (refuse rc=0, make_run_dir rc=0, wanted 2)
FAIL  an inherited TRIED flag does not unprotect the JVM home: it WROTE inside the protected root
FAIL  nor a memo naming a directory that really exists (refuse rc=0, make_run_dir rc=0, wanted 2)
FAIL  nor a memo naming a directory that really exists: it WROTE inside the protected root
FAIL  nor both of them together (refuse rc=0, make_run_dir rc=0, wanted 2)
FAIL  nor both of them together: it WROTE inside the protected root
FAIL  nor either of them exported into the environment (refuse rc=0, make_run_dir rc=0, wanted 2)
FAIL  nor either of them exported into the environment: it WROTE inside the protected root
FAIL  nor unsetting the stat-format cache under set -u (refuse rc=died, make_run_dir rc=died, wanted 2)
```

`rc=died` is its own finding. The first version of that harness read a status
file the killed subshell never wrote, so it graded the PREVIOUS case's result and
reported `rc=0/0`. The file is stamped `died` before each case now — and `died`
is not `2`, so a refusal that aborts still fails the assertion, which is the
point: an aborted refusal reads to the caller exactly like an allowed one.
