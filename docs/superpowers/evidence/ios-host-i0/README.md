# W1 I0 — an iOS production host from published artifacts (2026-10-07)

**Question:** can an adopter's iOS production host compile and link with
Keliver resolved only from Maven Central, as `#84` asked for Android?

**Answer, measured on macOS (Xcode 26.4.1, Kotlin 2.2.0, Compose 1.8.2, Zipline 1.22.0):
yes.** `linkDebugFrameworkIosSimulatorArm64` succeeded (`link-result.txt`).
Every Keliver dependency came from Maven Central: 54 `dev.keliver` artifacts,
all 0.3.3, no project or `mavenLocal` dependency (`keliver-artifacts.txt`).
The framework exports `MainViewControllerKt.MainViewController()` to Swift
(`framework-header-excerpt.txt`). It was built with a disposable Gradle home,
`user.home` and `KONAN_DATA_DIR`, and a dummy public key.

The host compiled here (`proj/src/iosMain/kotlin/`) is a first **production**
host, not the spike in `portal-device-ios`. It has parity with the Android
scaffold:
- production-only: no key means no fetch, and never `NO_SIGNATURE_CHECKS`;
- the lookup runs first with a 10 s timeout;
- an offline start from Zipline's pinned cache (`AcceptCachedBundle`);
- one cache per key;
- manifest URLs only on the bundle server's origin;
- `HostHttp` over `NSURLSession` with the Android path and header rules, and
  redirects refused.

**Then it ran (I1 groundwork, same day).** An Xcode shell (`app/`, adapted
from `portal-device-ios-app`) builds the framework through
`./gradlew embedAndSignAppleFrameworkForXcode`, with no development team
hard-coded. `xcodebuild -sdk iphonesimulator CODE_SIGNING_ALLOWED=NO` gave
`BUILD SUCCEEDED` (`xcodebuild-result.txt`).
- On a **disposable** iPhone 17 Pro simulator (iOS 26.4, created for the run
  and deleted afterwards), the app logged `verifying manifests with
  portal-ed25519 abababab…` and `bundle lookup failed: Could not connect to
  the server`, and showed **"No bundle"** (`run-no-server.console.txt`,
  `run-no-server.png`). The screenshot is a real image (189 colours), unlike
  the Android emulator's.
- Found on the way: an app whose `PRODUCT_NAME` equals the framework's
  `baseName` (`KeliverHost`) makes Swift ignore `import KeliverHost`. The
  scaffolder must keep them distinct.

**Still not shown:** a signed bundle loading, a foreign key refused, an
offline start from the cache. That needs a relay with a published bundle: the
rest of I1, then I2 in CI. The SQL host is
still the spike's in-memory one, and images have no network fetcher. The
debug static framework is 339 MB.

Reproduce: `measure.sh <empty dir>`.
