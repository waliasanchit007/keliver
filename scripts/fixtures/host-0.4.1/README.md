# The 0.4 line's host output, byte for byte

`*.sha256` here are the sha256 of every file the host scaffolders write for a
Keliver **0.4.1** app (the selftests' fixture app `com.example.demo`, their
dummy key, the servers and channel each selftest passes):

| manifest | written by | checked by |
|---|---|---|
| `android.sha256` | `keliver-new-production-host.sh` (`host-android/`) | `keliver-new-production-host-selftest.sh` |
| `android-embed.sha256` | `keliver-new-production-host.sh --embed` (the library module, into an app on AGP 8.12.0) | the same |
| `ios.sha256` | `keliver-new-ios-host.sh` (`host-ios/`) | `keliver-new-ios-host-selftest.sh` |
| `ios-embed.sha256` | `keliver-new-ios-host.sh --embed` | the same |

Not hashed anywhere: `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`.
Only the iOS `--embed` module has them, copied from the app (in the selftest,
this checkout's own wrapper), so they would change with every wrapper bump; the
selftest checks them as copies of the app's instead. The module's
`gradle-wrapper.properties` is the scaffolders' template and stays hashed.

They were recorded from the scaffolders of `77fdf8f81` (PR #299 before its
review fixes, whose 0.4.1 output was compared file by file with `origin/main`'s:
the only differences are template comments that name the 0.5 line), so they
keep the line table (`scripts/keliver-runtime-line.sh`) and every template edit
for a later line from changing what a 0.4.x app's host gets. A deliberate change to the 0.4 line's output rewrites them: run the
selftest with `KELIVER_HOST_MANIFEST_WRITE=1`, and say why in the PR.

Changes since:
- `android-embed.sha256`, `./build.gradle`: the template's comment about the 0.5
  line now says Kotlin 2.4.21 (#303 moved the line off 2.4.20). Comment only:
  every other file, and every other line of this one, is unchanged.
- `android-embed.sha256`, `./build.gradle` again: that comment no longer names
  any line's versions; it points to `keliver-runtime-line.sh`'s table instead
  (review of #299, decision 2), so a later change to the table cannot touch
  this manifest. Comment only, as before.
- `ios-embed.sha256`: `gradlew` and `gradle-wrapper.jar` removed (see above).
- All four, deliberately (NF5 H0, docs/SHOW_BEFORE_COMPUTE.md section 9). Why the 0.4 line's
  output moves for a 0.5 change: the host Kotlin is one source for every line, and H0 adds the
  seam that keeps the 0.5 libraries' API out of it from now on: one file per line,
  `production-host/src/line-<line>/kotlin/KeliverLine.kt`. Calling through that seam changes the
  shared files once, on every line. The alternative, keeping the 0.4 output byte-identical, needs
  per-line blocks inside `KeliverHost.kt`, `KeliverScreen.kt` and `MainViewController.kt`, which
  every NF5 host PR after this one (A5's start table, session, off switch) would have to extend.
  What a 0.4.x app's host gets now:
  - new: the 0.4 line's own files, `KeliverLine.kt` (no host features handed to Treehouse,
    `TreehouseContent` called as before, without a back gate) and `HostFeatures.kt` (declares
    none, `emptySet()`); `HostFeaturesTest.kt` (in the hosts with JVM tests; it checks the form
    of the names only); `ReleaseIdentity.kt` (and `ReleaseIdentityTest.kt` on Android). The iOS
    host takes `HostFeatures.kt`, `HostFeaturesTest.kt` and `ReleaseIdentity.kt` from the
    Android templates, as it takes `KeliverLine.kt`; the iOS templates keep no copies;
  - changed: `KeliverHost.kt` and `MainViewController.kt` (the spec's base class is
    `KeliverSpec`; the last good bundle's record also carries the loaded release's sequence and
    module digest, and is written with `commit()` on Android and `synchronize()` on iOS, as the
    crash ledger already is) and `KeliverScreen.kt` (the update swap calls
    `KeliverTreehouseContent(backEnabled = !hidden)`, which the 0.4 copy ignores).
  The one behaviour change on the 0.4 line is that record: two more keys, nothing on 0.4 reads
  them, and it is written through before the load is reported. The reference-app and ios-host
  workflows build and run this output on a pull request.
  The host features are declared per line (review of #316, M2): a feature declared later goes
  into `line-0.5/kotlin/HostFeatures.kt` only, so it changes none of these manifests (one
  exception, MS4-04, below: a 0.4 host has no `schemaMismatch` to override, so the shared
  listener and `reportReason` had to move once; the maintainer accepted it, 2026-11-28). Rewritten
  once more in that review round, for `HostFeatures.kt` and `HostFeaturesTest.kt` (above),
  `KeliverLine.kt` (its header names the line's two files) and `KeliverScreen.kt` (a comment that
  now says what back does on each line).
- All four, deliberately (MS4-04, schema-mismatch reports). The feature is declared in
  `line-0.5/kotlin/HostFeatures.kt` and checked in the 0.5 line's own test file
  (`line-0.5/test/kotlin/SchemaMismatchReportsTest.kt`, which no 0.4.x host gets), as H0 set out.
  What it does, though, is a report, and only the host's shared listener and report code can make
  one: no per-line file reaches the host's report function. So three files move, and no file is
  added or removed:
  - `KeliverLine.kt` (the 0.4 line's): a `KeliverEventListener` base class, whose
    `onSchemaMismatch` nothing calls on this line (its Treehouse has no once-per-tag hook);
  - `KeliverHost.kt` and `MainViewController.kt`: the code-load listener extends it and, when it
    is called, reports through the host's report path;
  - `BundleIndex.kt` (both hosts): the `schema-mismatch` outcome and its reason in
    `reportReason`.
  No behaviour changes on the 0.4 line: an unknown tag is ignored silently, as before.
