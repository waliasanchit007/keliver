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
- `ios-embed.sha256`: `gradlew` and `gradle-wrapper.jar` removed (see above).
