# Consumer smoke test — 0.3.3 (2026-09-06)

`scripts/keliver-consumer-smoke.sh 0.3.3`

## Result: PASS in 56s

```
result:    PASS: 0.3.3 resolves from Central and compiles (30 modules cached, 9 IR files)
exit:      0
```

The run was deliberately executed with **`KELIVER_USE_MAVEN_LOCAL=1` exported**
— the one variable that would silently invalidate the check by injecting
`mavenLocal()` into the generated project at scaffold time. `generated-settings.gradle`
in this directory shows the repository list the script actually produced:

```
repositories { gradlePluginPortal(); mavenCentral(); google() }
repositories { mavenCentral(); google() }
```

No `mavenLocal()`. The `env -u` stripping is load-bearing, not decorative.

## Failure paths exercised

| scenario | result | exit |
|---|---|---|
| version not on Central (`0.9.99-nope`) | `FAIL: not on Central: keliver-host keliver-guest keliver-material-compose keliver-layout-compose portal-sql` | 1 |
| scaffolder missing | `FAIL: scaffolder not executable at /nonexistent/keliver-init` | 1 |

The script cannot silently report success after a failed step: every stage
routes through `finish()`, which writes the verdict and exits with its code.

## Why the weaker signals are not the gate

Each of these was true well before a consumer could build anything:

- the publish workflow reported success — before any artifact had synced
- `maven-metadata.xml` listed `<release>0.3.3` — with 4 of 7 coordinates 404
- `keliver-host`'s POM returned 200 — with 2 of 3 scaffold deps still 404
- all five scaffold POMs returned 200 — before compilation was attempted

POM availability is preliminary. Resolution **and compilation** by a
scaffolded consumer is the acceptance gate, and the script keeps upload
success and consumer readiness as separate facts.

## Not done

Not wired into CI. The local script works; proposing CI integration should
wait until there is a demonstrated need, and a release cadence to hang it on.

## Defects found in review and fixed (2026-09-06)

**Cache was not guaranteed fresh.** The script used `$OUT_DIR/gradle-home`
with `mkdir -p`, which silently accepts existing contents. Re-running with the
same `--out` after deleting only the scaffold could therefore reuse cached
dependencies while still reporting cold resolution from Central. A reviewer
pre-seeded a marker there and the script returned PASS with the marker intact.

Now allocates a unique `mktemp -d` cache and refuses to proceed if it is
non-empty. Re-verified with the same pre-seeded directory: the run passed,
`GRADLE_USER_HOME` was a fresh temp dir, and the seeded directory still
contains only `PRE_EXISTING_MARKER` — it was never used.

**A missing option value looped forever.** `--out` with no argument left
`shift 2` failing without changing `$#`, so the `while` loop spun. Both
options now check `[ $# -ge 2 ]` and exit 2. Verified: prints
`--out needs a value` and exits 2 immediately.

## Regression suite (2026-09-06)

`scripts/keliver-consumer-smoke-selftest.sh [version]` — **7/7 passing**
(`selftest-results.log`). It pins every way the script previously could, or
could still, report a false pass:

| case | expected |
|---|---|
| missing `--out` value | exits 2, does not hang |
| missing `--scaffolder` value | exits 2, does not hang |
| unusable scaffolder (failed scaffolding step) | exits 1 |
| version absent from Central (failed resolution) | exits 1 |
| snapshot version | refused up front, exits 2 |
| **pre-existing output/cache directory** | passes, and the seeded cache is provably untouched |
| hostile `KELIVER_USE_MAVEN_LOCAL=1` | stripped; generated settings contain no `mavenLocal()` |

The two hang cases are wall-clock guarded, so a regression reintroducing the
infinite `shift` loop fails rather than stalling the suite.

### One further correction the suite surfaced

Making the cache unique initially broke the happy path: with a wholly fresh
`GRADLE_USER_HOME` the wrapper must fetch the ~130 MB Gradle distribution, and
it timed out at the wrapper's 10s limit —

```
Downloading https://services.gradle.org/distributions/gradle-9.0.0-bin.zip
failed: timeout (10000ms)
```

Only the **dependency** cache needs to be cold; that is what proves resolution
came from Central. The distribution does not. The script now symlinks
`$GUH/wrapper` to `~/.gradle/wrapper` when one exists and asserts
`caches/modules-2` is absent before resolving.

Verified on a passing run: `dependency cache empty` at start, and 30
`dev.keliver:*:0.3.3` modules present in that fresh cache afterwards — so the
artifacts were fetched during the run, not inherited.
